package de.keksuccino.rinku;

import static de.keksuccino.rinku.GlfwConstantsBridge.*;
import static org.lwjgl.opengl.GL12.*;

import java.awt.Point;
import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefBrowserSettings;
import org.cef.CefColor;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefBrowserOsr;
import org.cef.callback.CefDragData;
import org.cef.event.CefKeyEvent;
import org.cef.event.CefMouseEvent;
import org.cef.event.CefMouseWheelEvent;
import org.cef.misc.CefCursorType;
import org.cef.misc.CefSharedTexture;
import org.lwjgl.opengl.GL11;

import de.keksuccino.rinku.listeners.RinkuCursorChangeListener;

/**
 * An instance of an "Off-screen rendered" Chromium web browser.
 * Complete with a renderer, keyboard and mouse inputs, optional
 * browser control shortcuts, cursor handling, drag & drop support.
 */
public class RinkuBrowser extends CefBrowserOsr {

    private static final Logger LOGGER = LogManager.getLogger("RinkuBrowser");
    private static final int MAX_PENDING_PAINT_STREAMS = 2;
    /**
     * Set -Drinku.disableViewPixelStore=true to fall back to the old per-frame clone path, e.g. when
     * comparing behaviour on a driver that mishandles GL_UNPACK_SKIP_PIXELS/ROWS.
     */
    private static final boolean VIEW_PIXEL_STORE_ENABLED = !Boolean.getBoolean("rinku.disableViewPixelStore");
    private static final BrowserCursorController CURSOR_CONTROLLER = new BrowserCursorController();
    private static final BrowserCursorController.CursorBackend CURSOR_BACKEND = new GlfwCursorBackend();

    /**
     * The renderer for the browser.
     */
    private final RinkuRenderer renderer;
    private final DirectBufferPool frameBufferPool = new DirectBufferPool();
    private final ViewPixelStore viewPixelStore = new ViewPixelStore(frameBufferPool);
    private final AsyncResourceLeaseManager<PaintSurface, AsyncPaintFrame> asyncPaintBufferLeases = new AsyncResourceLeaseManager<>(
        frame -> {
            frameBufferPool.release(frame.fullBuffer());
            frameBufferPool.release(frame.compactBuffer());
        },
        AsyncPaintFrame::requireFullUpload,
        MAX_PENDING_PAINT_STREAMS);
    private final ReentrantLock paintCallbackLock = new ReentrantLock();
    private final BrowserCloseController closeController = new BrowserCloseController();
    private final AtomicBoolean deferredNativeClose = new AtomicBoolean();
    private final PopupPaintState popupPaintState = new PopupPaintState();
    private boolean rendererInitialized;
    private boolean rendererCleanupStarted;
    private int renderOperationDepth;
    private boolean sharedTextureSupported;
    private boolean sharedTextureSupportChecked;
    private ByteBuffer sharedTextureReadBuffer;
    private int sharedTextureReadBufferSize;
    private final RinkuDragSessionController.Callbacks<CefDragData> dragCallbacks = new RinkuDragSessionController.Callbacks<>() {

        @Override
        public void targetEnter(CefDragData dragData, int x, int y, int modifiers, int allowedOperations) {
            RinkuBrowser.this.dragTargetDragEnter(dragData, new Point(x, y), modifiers, allowedOperations);
        }

        @Override
        public void targetDrop(int x, int y, int modifiers) {
            RinkuBrowser.this.dragTargetDrop(new Point(x, y), modifiers);
        }

        @Override
        public void targetLeave() {
            RinkuBrowser.this.dragTargetDragLeave();
        }

        @Override
        public void sourceEndedAt(int x, int y, int operation) {
            RinkuBrowser.this.dragSourceEndedAt(new Point(x, y), operation);
        }

        @Override
        public void sourceSystemDragEnded() {
            RinkuBrowser.this.dragSourceSystemDragEnded();
        }
    };
    /**
     * Stores information about drag & drop.
     */
    private final RinkuDragContext dragContext = new RinkuDragContext(dragCallbacks);
    /**
     * A listener that defines that happens when a cursor changes in the browser.
     * E.g. when you've hovered over a button, an input box, are selecting text, etc...
     * A default listener is created in the constructor that sets the cursor type to
     * the appropriate cursor based on the event.
     */
    private RinkuCursorChangeListener cursorChangeListener;
    /**
     * Whether Rinku should mimic the controls of a typical web browser.
     * E.g. CTRL+R for reload, CTRL+Left for back, CTRL+Right for forward, etc...
     */
    private boolean browserControls = true;
    /**
     * Used to track when a full repaint should occur.
     */
    private int lastWidth = 0, lastHeight = 0;
    /**
     * A bitset representing what mouse buttons are currently pressed.
     * CEF is a bit odd and implements mouse buttons as a part of modifier flags.
     */
    private int btnMask = 0;
    /**
     * Tracks right-alt state so we can distinguish AltGr text input
     * from regular Ctrl+Alt shortcuts.
     */
    private boolean rightAltDown = false;

    // Data relating to popups and graphics
    // Marked as protected in-case a mod wants to extend RinkuBrowser and override the repaint logic
    protected volatile ByteBuffer popupGraphics;
    protected volatile Rectangle popupSize;
    protected volatile boolean showPopup = false;
    protected volatile boolean popupDrawn = false;

    public RinkuBrowser(RinkuClient client, String url, boolean transparent) {
        super(client.getHandle(), url, transparent, null, createBrowserSettings(transparent));
        renderer = new RinkuRenderer(transparent);
        cursorChangeListener = (cefCursorID) -> setCursor(resolveCursorType(cefCursorID));
        if (!RinkuRenderCoordinator.register(this)) {
            IllegalStateException registrationFailure = new IllegalStateException(
                "Cannot create a Rinku browser after render shutdown has started");
            try {
                closeBrowser(false);
            } catch (Throwable lifecycleFailure) {
                addSuppressed(registrationFailure, lifecycleFailure);
            }
            throw registrationFailure;
        }
        if (RenderStateBridge.isOnRenderThread()) {
            initializeRendererOnRenderThread();
        }
    }

    private static CefBrowserSettings createBrowserSettings(boolean transparent) {
        CefBrowserSettings settings = new CefBrowserSettings();
        settings.windowless_frame_rate = Rinku.getSettings()
            .getBrowserFrameRate();
        if (transparent) {
            // CEF paints an opaque white frame before the document is loaded. Asking for
            // transparent painting explicitly (alpha = 0) makes that pre-document frame
            // transparent too, so opening a UI shows the world instead of a white flash.
            // The global CefSettings.background_color alone is not applied to the
            // accelerated-paint (shared texture) path on every CEF build.
            settings.background_color = new CefColor(0, 0, 0, 0);
        }
        return settings;
    }

    public RinkuRenderer getRenderer() {
        return renderer;
    }

    @Override
    public void loadURL(String url) {
        // Rinku hands out preloaded browsers whose start page is about:blank. Until the new
        // document paints its first frame the texture still holds that opaque white frame,
        // which is exactly the white flash seen when a UI opens. Invalidate it first.
        if (renderer != null) {
            renderer.markContentStale();
        }
        super.loadURL(url);
    }

    @Override
    public void reload() {
        if (renderer != null) {
            renderer.markContentStale();
        }
        super.reload();
    }

    /**
     * Convenience method to get the Identifier for this browser's texture.
     * This can be used directly with GuiGraphics rendering methods.
     *
     * @return The Identifier for this browser's texture, or null if not initialized
     */
    public ResourceLocation getTextureIdentifier() {
        return renderer != null && renderer.isTextureReady() ? renderer.getTextureIdentifier() : null;
    }

    /**
     * Check if the browser's texture is ready for rendering.
     *
     * @return true if the texture is initialized and ready to be rendered
     */
    public boolean isTextureReady() {
        return renderer != null && renderer.isTextureReady();
    }

    public RinkuCursorChangeListener getCursorChangeListener() {
        return cursorChangeListener;
    }

    public void setCursorChangeListener(RinkuCursorChangeListener cursorChangeListener) {
        this.cursorChangeListener = cursorChangeListener;
    }

    public boolean usingBrowserControls() {
        return browserControls;
    }

    /**
     * Enabling browser controls tells Rinku to mimic the behavior of an actual browser.
     * CTRL+R for reload, CTRL+Left for back, CTRL+Right for forward, etc...
     *
     * @param browserControls whether browser controls should be enabled
     * @return the browser instance
     */
    public RinkuBrowser useBrowserControls(boolean browserControls) {
        this.browserControls = browserControls;
        return this;
    }

    public RinkuDragContext getDragContext() {
        return dragContext;
    }

    // Popups
    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        paintCallbackLock.lock();
        try {
            super.onPopupShow(browser, show);
            showPopup = show;
            if (popupPaintState.updateVisibility(show)) {
                popupDrawn = false;
                requestPopupStateResync();
            }
        } finally {
            paintCallbackLock.unlock();
        }
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        paintCallbackLock.lock();
        try {
            super.onPopupSize(browser, size);
            boolean geometryChanged = popupPaintState.updateGeometry(size);
            popupSize = popupPaintState.geometry();
            if (!geometryChanged) {
                return;
            }

            popupDrawn = false;
            try {
                if (popupSize == null) {
                    popupGraphics = null;
                    return;
                }
                int popupBufferSize = getRequiredBufferSize(popupSize.width, popupSize.height);
                if (popupBufferSize <= 0) {
                    popupGraphics = null;
                    return;
                }
                if (popupGraphics == null || popupGraphics.capacity() != popupBufferSize) {
                    popupGraphics = ByteBuffer.allocateDirect(popupBufferSize);
                }
            } finally {
                requestPopupStateResync();
            }
        } finally {
            paintCallbackLock.unlock();
        }
    }

    private void requestPopupStateResync() {
        asyncPaintBufferLeases.requireResync(PaintSurface.VIEW);
        asyncPaintBufferLeases.requireResync(PaintSurface.POPUP);
        if (asyncPaintBufferLeases.isAccepting()) {
            invalidate();
        }
    }

    // Graphics
    /**
     * Paint listeners are notified only after Rinku has consumed the callback buffer synchronously or has copied it
     * into the bounded latest-frame mailbox. Replaced pending frames are coalesced into a full upload of the newest
     * full-frame copy for that view or popup stream.
     */
    private volatile boolean onPaintLogged = false;

    @Override
    public void onPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width,
        int height) {
        if (!onPaintLogged) {
            onPaintLogged = true;
            LOGGER.info(
                "onPaint (software) called: popup=" + popup
                    + ", width="
                    + width
                    + ", height="
                    + height
                    + ", dirtyRects="
                    + (dirtyRects == null ? "null" : dirtyRects.length));
        }
        if (dirtyRects == null || dirtyRects.length == 0 || buffer == null) return;
        if (onPaint(popup, dirtyRects, buffer, width, height)) {
            // The base class gives listeners isolated callback-scoped views.
            super.onPaint(browser, popup, dirtyRects, buffer, width, height);
        }
    }

    @Override
    public void onAcceleratedPaint(CefBrowser browser, boolean popup, Rectangle[] dirtyRects, long sharedTextureHandle,
        int colorFormat) {
        if (dirtyRects == null || dirtyRects.length == 0 || sharedTextureHandle == 0) return;
        if (!isSharedTextureSupported()) return;

        int width = 0;
        int height = 0;
        if (!popup) {
            width = browser_rect_.width;
            height = browser_rect_.height;
        } else {
            Rectangle popupRect = popupPaintState.geometry();
            if (popupRect != null) {
                width = popupRect.width;
                height = popupRect.height;
            }
        }
        if (width <= 0 || height <= 0) return;

        // Once the GL side has reported that it cannot bind the shared texture, stop attempting
        // the VRAM-only path: those frames would never reach the screen. Fall through to the CPU
        // readback so the view keeps rendering (slow, but correct) on unsupported drivers.
        if (!popup && !gpuBindFailureLogged && !sharedTextureNativeUnavailable) {
            // CEF returns the shared texture to its pool as soon as this callback returns, and the
            // resource may not be cached or touched outside the callback (see OnAcceleratedPaint in
            // CEF's cef_render_handler.h). Everything needing the handle must happen right here.
            boolean copied;
            try {
                copied = CefSharedTexture.copySharedTextureFrame(sharedTextureHandle, width, height);
            } catch (LinkageError mismatch) {
                // A stale jcef.dll has no copySharedTextureFrame. Keep the old readback path
                // instead of breaking every accelerated paint callback.
                sharedTextureNativeUnavailable = true;
                LOGGER.warn("jcef.dll is older than this Rinku build; GPU zero-copy disabled. "
                    + "Replace jcef.dll with the matching build. " + mismatch);
                copied = false;
            }
            if (copied) {
                // The frame now lives in a VRAM texture owned by us. The queued handle is only a
                // marker now; the native side no longer dereferences it on the render thread.
                tryZeroCopyPaint(popup, dirtyRects, sharedTextureHandle, width, height);
                // A saturated mailbox means a dropped frame. Never defer handle consumption.
                return;
            }
            logSharedTextureDiagnostics();
        }

        // Fallback for drivers without WGL_NV_DX_interop: GPU -> CPU readback, still inside the
        // callback so the handle is valid. Slow, but keeps the view alive on unsupported GPUs.
        int requiredSize = width * height * 4;
        ByteBuffer readBuffer = acquireSharedTextureReadBuffer(requiredSize);
        if (readBuffer == null) return;

        readBuffer.clear();
        boolean ok = CefSharedTexture.readPixels(sharedTextureHandle, width, height, readBuffer);
        if (!ok) {
            if (!readPixelsFailureLogged) {
                readPixelsFailureLogged = true;
                LOGGER.warn("Shared texture readPixels failed, w=" + width + " h=" + height);
            }
            return;
        }
        readBuffer.limit(requiredSize);
        readBuffer.position(0);

        if (onPaint(popup, dirtyRects, readBuffer, width, height)) {
            super.onPaint(browser, popup, dirtyRects, readBuffer, width, height);
        }
    }

    private volatile boolean sharedTextureDiagnosticsLogged = false;
    private volatile boolean gpuBindFailureLogged = false;
    private volatile boolean sharedTextureNativeUnavailable = false;
    private volatile boolean readPixelsFailureLogged = false;

    private String sharedTextureDiagnosticsText() {
        try {
            return CefSharedTexture.getDiagnostics();
        } catch (Throwable t) {
            return "(diagnostics unavailable: " + t + ")";
        }
    }

    private void logSharedTextureDiagnostics() {
        if (sharedTextureDiagnosticsLogged) return;
        sharedTextureDiagnosticsLogged = true;
        LOGGER.warn("GPU zero-copy unavailable; falling back to CPU readback. "
            + sharedTextureDiagnosticsText());
    }

    private boolean tryZeroCopyPaint(boolean popup, Rectangle[] dirtyRects, long sharedTextureHandle, int width,
        int height) {
        Rectangle[] dirtyRectsCopy;
        Rectangle popupRectSnapshot;
        boolean showPopupSnapshot;
        long popupStateGeneration;
        paintCallbackLock.lock();
        try {
            if (!asyncPaintBufferLeases.isAccepting() || closeController.isCloseRequested()) {
                return false;
            }
            dirtyRectsCopy = copyDirtyRects(dirtyRects);
            popupRectSnapshot = popupPaintState.geometry();
            showPopupSnapshot = popupPaintState.visible();
            popupStateGeneration = popupPaintState.generation();
        } finally {
            paintCallbackLock.unlock();
        }

        PaintSurface surface = PaintSurface.fromPopup(popup);
        return asyncPaintBufferLeases.offer(
            surface,
            (requireFullUpload, replacedFrame) -> createAsyncPaintFrame(
                surface,
                dirtyRectsCopy,
                null,
                width,
                height,
                requireFullUpload,
                replacedFrame,
                popupRectSnapshot,
                showPopupSnapshot,
                popupStateGeneration,
                sharedTextureHandle),
            this::renderAsyncPaintFrame,
            this::logAsyncPaintFailure);
    }

    private boolean isSharedTextureSupported() {
        if (!sharedTextureSupportChecked) {
            try {
                sharedTextureSupported = CefSharedTexture.isSupported();
            } catch (Throwable t) {
                sharedTextureSupported = false;
            }
            sharedTextureSupportChecked = true;
            LOGGER.info("Shared texture support: " + sharedTextureSupported);
        }
        return sharedTextureSupported;
    }

    private ByteBuffer acquireSharedTextureReadBuffer(int requiredSize) {
        if (sharedTextureReadBuffer == null || sharedTextureReadBufferSize < requiredSize) {
            if (sharedTextureReadBuffer != null) {
                MemoryUtilBridge.memFree(sharedTextureReadBuffer);
            }
            sharedTextureReadBuffer = MemoryUtilBridge.memAlloc(requiredSize);
            sharedTextureReadBufferSize = requiredSize;
        }
        return sharedTextureReadBuffer;
    }

    private boolean onPaint(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        if (RenderStateBridge.isOnRenderThread()) {
            paintCallbackLock.lock();
            try {
                if (!asyncPaintBufferLeases.isAccepting() || closeController.isCloseRequested()) {
                    return false;
                }
                // Apply older copied callbacks first so this callback can safely upload only its dirty regions.
                pumpAsyncPaintsOnRenderThread();
                if (closeController.isCloseRequested()) {
                    return false;
                }
                Rectangle[] dirtyRectsCopy = copyDirtyRects(dirtyRects);
                Rectangle popupRectSnapshot = popupPaintState.geometry();
                boolean showPopupSnapshot = popupPaintState.visible();
                long popupStateGeneration = popupPaintState.generation();
                PaintSurface surface = PaintSurface.fromPopup(popup);
                boolean forceFullUpload = asyncPaintBufferLeases.consumeResync(surface);
                // Keep the persistent mirror in sync even for the rare callback that already runs
                // on the render thread, otherwise a later resync would upload stale rows.
                if (!popup && !viewPixelStore.tryPatch(dirtyRectsCopy, buffer, width, height)) {
                    viewPixelStore.invalidate();
                }
                beginRenderOperation();
                try {
                    onPaintRenderThread(
                        popup,
                        dirtyRectsCopy,
                        buffer,
                        null,
                        null,
                        width,
                        height,
                        popupRectSnapshot,
                        showPopupSnapshot,
                        popupStateGeneration,
                        forceFullUpload);
                } catch (Throwable failure) {
                    if (popup) {
                        invalidateRetainedPopupPixels();
                    }
                    asyncPaintBufferLeases.requireResync(surface);
                    throw failure;
                } finally {
                    endRenderOperation();
                }
                return !closeController.isCloseRequested();
            } finally {
                paintCallbackLock.unlock();
            }
        }

        // Async path: hold the lock only long enough to snapshot shared popup state. The expensive pixel
        // copy runs outside the lock so the render thread can upload previous frames without blocking.
        Rectangle[] dirtyRectsCopy;
        Rectangle popupRectSnapshot;
        boolean showPopupSnapshot;
        long popupStateGeneration;
        paintCallbackLock.lock();
        try {
            if (!asyncPaintBufferLeases.isAccepting() || closeController.isCloseRequested()) {
                return false;
            }
            dirtyRectsCopy = copyDirtyRects(dirtyRects);
            popupRectSnapshot = popupPaintState.geometry();
            showPopupSnapshot = popupPaintState.visible();
            popupStateGeneration = popupPaintState.generation();
        } finally {
            paintCallbackLock.unlock();
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            return false;
        }
        PaintSurface surface = PaintSurface.fromPopup(popup);
        return asyncPaintBufferLeases.offer(
            surface,
            (requireFullUpload, replacedFrame) -> createAsyncPaintFrame(
                surface,
                dirtyRectsCopy,
                buffer,
                width,
                height,
                requireFullUpload,
                replacedFrame,
                popupRectSnapshot,
                showPopupSnapshot,
                popupStateGeneration,
                0L),
            this::renderAsyncPaintFrame,
            this::logAsyncPaintFailure);
    }

    private void logAsyncPaintFailure(Throwable failure) {
        LOGGER.warn("Asynchronous browser paint failed.", failure);
    }

    private AsyncPaintFrame createAsyncPaintFrame(PaintSurface surface, Rectangle[] dirtyRects, ByteBuffer buffer,
        int width, int height, boolean requireFullUpload, AsyncPaintFrame replacedFrame, Rectangle popupRectSnapshot,
        boolean showPopupSnapshot, long popupStateGeneration, long sharedTextureHandle) {
        Rectangle[] dirtyRectsCopy = dirtyRects;

        if (sharedTextureHandle != 0) {
            return new AsyncPaintFrame(
                surface,
                dirtyRectsCopy,
                null,
                null,
                null,
                width,
                height,
                popupRectSnapshot,
                showPopupSnapshot,
                popupStateGeneration,
                sharedTextureHandle);
        }

        if (surface == PaintSurface.VIEW) {
            Rectangle[] mergedDirtyRects = mergeDirtyRects(dirtyRectsCopy, replacedFrame);
            if (VIEW_PIXEL_STORE_ENABLED) {
                // Preferred path: patch only the changed rows into the persistent mirror. The queued
                // frame carries no pixel buffer, so a large-screen repaint no longer clones several
                // MB on CEF's UI thread or allocates/frees that memory every frame.
                if (viewPixelStore.tryPatch(mergedDirtyRects, buffer, width, height)) {
                    AsyncPaintFrame storeFrame = AsyncPaintFrame.forViewStore(
                        mergedDirtyRects,
                        width,
                        height,
                        popupRectSnapshot,
                        showPopupSnapshot,
                        popupStateGeneration);
                    // A resync means an earlier frame was skipped. The mirror holds an entire frame,
                    // so a full upload repairs the texture without cloning another multi-MB buffer.
                    if (requireFullUpload) {
                        storeFrame.requireFullUpload();
                    }
                    return storeFrame;
                }
                // The render thread owned the mirror during this callback. Mark it stale so a later
                // resync cannot upload rows that this fallback frame never patched into it.
                viewPixelStore.invalidate();
            }
            if (!requireFullUpload) {
                CompactCopyResult compact = copyCompactViewRects(mergedDirtyRects, buffer, width, height);
                if (compact != null) {
                    return new AsyncPaintFrame(
                        surface,
                        mergedDirtyRects,
                        null,
                        compact.buffer(),
                        compact.regions(),
                        width,
                        height,
                        popupRectSnapshot,
                        showPopupSnapshot,
                        popupStateGeneration,
                        0L);
                }
            }
        }

        ByteBuffer bufferCopy = cloneBufferForAsyncPaint(buffer);
        try {
            return new AsyncPaintFrame(
                surface,
                dirtyRectsCopy,
                bufferCopy,
                null,
                null,
                width,
                height,
                popupRectSnapshot,
                showPopupSnapshot,
                popupStateGeneration,
                0L);
        } catch (Throwable failure) {
            frameBufferPool.release(bufferCopy);
            throw failure;
        }
    }

    private static Rectangle[] mergeDirtyRects(Rectangle[] current, AsyncPaintFrame replacedFrame) {
        if (replacedFrame == null || replacedFrame.surface() != PaintSurface.VIEW) {
            return current;
        }
        Rectangle[] replaced = replacedFrame.dirtyRects();
        if (replaced == null || replaced.length == 0) {
            return current;
        }
        if (current == null || current.length == 0) {
            return replaced;
        }
        Rectangle[] merged = new Rectangle[current.length + replaced.length];
        System.arraycopy(current, 0, merged, 0, current.length);
        System.arraycopy(replaced, 0, merged, current.length, replaced.length);
        return merged;
    }

    private CompactCopyResult copyCompactViewRects(Rectangle[] dirtyRects, ByteBuffer buffer, int width,
        int height) {
        if (dirtyRects == null || dirtyRects.length == 0 || buffer == null) {
            return null;
        }

        int totalBytes = 0;
        List<Rectangle> validRects = new ArrayList<>(dirtyRects.length);
        for (Rectangle dirtyRect : dirtyRects) {
            Rectangle clipped = clipRect(dirtyRect, width, height);
            if (clipped != null) {
                validRects.add(clipped);
                totalBytes += clipped.width * clipped.height * 4;
            }
        }
        if (validRects.isEmpty() || totalBytes <= 0) {
            return null;
        }

        ByteBuffer compact = frameBufferPool.acquire(totalBytes);
        DirtyRegion[] regions = new DirtyRegion[validRects.size()];
        long srcAddress = MemoryUtilBridge.memAddress(buffer);
        long dstAddress = MemoryUtilBridge.memAddress(compact);
        int offset = 0;
        try {
            if (srcAddress == 0L || dstAddress == 0L) {
                frameBufferPool.release(compact);
                return null;
            }
            // One native memcpy per changed row, straight into a pooled buffer. The old
            // ByteBuffer.put(ByteBuffer) loop rebuilt a sliced view for every row and, more
            // importantly, allocated a fresh off-heap buffer of this size on every callback.
            for (int i = 0; i < validRects.size(); i++) {
                Rectangle rect = validRects.get(i);
                int bytesPerRow = rect.width << 2;
                for (int row = 0; row < rect.height; row++) {
                    long srcIndex = (((long) (rect.y + row) * width) + rect.x) << 2;
                    MemoryUtilBridge.memCopy(
                        srcAddress + srcIndex,
                        dstAddress + offset + (long) row * bytesPerRow,
                        bytesPerRow);
                }
                regions[i] = new DirtyRegion(rect.x, rect.y, rect.width, rect.height, offset);
                offset += bytesPerRow * rect.height;
            }
            compact.clear();
            compact.limit(totalBytes);
            return new CompactCopyResult(compact, regions);
        } catch (Throwable failure) {
            frameBufferPool.release(compact);
            throw failure;
        }
    }

    private void renderAsyncPaintFrame(AsyncPaintFrame frame) {
        paintCallbackLock.lock();
        try {
            Rectangle popupRect = frame.popupRect();
            boolean showPopupSnapshot = frame.showPopup();
            long popupStateGeneration = frame.popupStateGeneration();
            boolean forceFullUpload = frame.requiresFullUpload();
            if (!popupPaintState.isCurrentGeneration(popupStateGeneration)) {
                if (frame.surface() == PaintSurface.POPUP) {
                    asyncPaintBufferLeases.requireResync(PaintSurface.POPUP);
                    return;
                }
                popupRect = popupPaintState.geometry();
                showPopupSnapshot = popupPaintState.visible();
                popupStateGeneration = popupPaintState.generation();
                forceFullUpload = true;
            }

            if (frame.surface() == PaintSurface.VIEW && frame.sharedTextureHandle() != 0) {
                // Pixels were already copied into our own VRAM texture inside the CEF callback.
                // Publish and lock that texture here; the CEF handle is already invalid and must
                // never be dereferenced on this thread.
                int glTexId = CefSharedTexture.lockSharedTexture(
                    frame.sharedTextureHandle(), frame.width(), frame.height());
                if (glTexId != 0) {
                    // The GL texture covers the full CEF frame (viewport * device scale factor),
                    // not the logical viewport. Report its real size so getTextureWidth/Height()
                    // and the direct-texture view stay in sync with what UV 0..1 samples.
                    int textureW = frame.width();
                    int textureH = frame.height();
                    try {
                        long size = CefSharedTexture.getFrameSize();
                        int w = (int) (size >>> 32);
                        int h = (int) (size & 0xFFFFFFFFL);
                        if (w > 0 && h > 0) {
                            textureW = w;
                            textureH = h;
                        }
                    } catch (Throwable ignored) {
                        // Old jcef.dll without getFrameSize(): keep the viewport size. The frame
                        // itself is still correct because texture coordinates are normalized.
                    }
                    renderer.setExternalTexture(glTexId, textureW, textureH);
                    // GPU frames bypass the software callback. Force the next software frame to
                    // rebuild a complete mirror before any incremental upload is allowed again.
                    viewPixelStore.invalidate();
                    lastWidth = frame.width();
                    lastHeight = frame.height();
                    restorePopupAfterViewPaint(frame.width(), frame.height(),
                        popupRect, showPopupSnapshot, popupStateGeneration);
                    return;
                }
                if (!gpuBindFailureLogged) {
                    gpuBindFailureLogged = true;
                    LOGGER.warn("GPU frame binding failed, keeping the previous frame. "
                        + sharedTextureDiagnosticsText());
                }
                // Do NOT readPixels here: CEF released that texture when the callback returned.
                // The next frame retries binding automatically.
                return;
            }

            if (frame.surface() == PaintSurface.VIEW) {
                // A software frame (or a CPU readback fallback) now drives the view. Drop the
                // external GPU binding so TextureManager resolves to the software texture.
                renderer.clearExternalTexture();
            }

            if (frame.useViewStore()) {
                boolean fullFrame = forceFullUpload || lastWidth != frame.width()
                    || lastHeight != frame.height()
                    || !renderer.supportsDirtyRectUpload();
                boolean uploaded = viewPixelStore.tryUpload(
                    frame.dirtyRects(),
                    frame.width(),
                    frame.height(),
                    fullFrame,
                    this::uploadViewPixelsFromStore);
                if (!uploaded) {
                    // The mirror was busy or stale. It still owns the newest pixels, so the next
                    // accepted frame repairs the texture with a single full upload.
                    asyncPaintBufferLeases.requireResync(PaintSurface.VIEW);
                    return;
                }
                lastWidth = frame.width();
                lastHeight = frame.height();
                restorePopupAfterViewPaint(frame.width(), frame.height(),
                    popupRect, showPopupSnapshot, popupStateGeneration);
                return;
            }

            try {
                onPaintRenderThread(
                    frame.surface() == PaintSurface.POPUP,
                    frame.dirtyRects(),
                    frame.fullBuffer(),
                    frame.compactBuffer(),
                    frame.compactRegions(),
                    frame.width(),
                    frame.height(),
                    popupRect,
                    showPopupSnapshot,
                    popupStateGeneration,
                    forceFullUpload);
            } catch (Throwable failure) {
                if (frame.surface() == PaintSurface.POPUP) {
                    invalidateRetainedPopupPixels();
                }
                throw failure;
            }
        } finally {
            paintCallbackLock.unlock();
        }
    }

    void pumpAsyncPaintsOnRenderThread() {
        RenderStateBridge.assertOnRenderThread();
        if (rendererCleanupStarted) {
            return;
        }
        if (closeController.isCloseRequested()) {
            cleanupBrowserResourcesOnRenderThread();
            return;
        }
        initializeRendererOnRenderThread();
        beginRenderOperation();
        try {
            asyncPaintBufferLeases.drain(MAX_PENDING_PAINT_STREAMS);
        } finally {
            endRenderOperation();
        }
    }

    void shutdownOnRenderThread() {
        RenderStateBridge.assertOnRenderThread();
        closeBrowser(true);
    }

    /**
     * Uploads from the persistent mirror. The buffer is a full-size frame, so GL's unpack
     * row-length / skip state selects each dirty sub-rectangle without any additional CPU copy.
     */
    private void uploadViewPixelsFromStore(ByteBuffer pixels, Rectangle[] dirtyRects, int width, int height,
        boolean fullFrame) {
        if (fullFrame) {
            renderer.onPaintDirect(pixels, width, height);
            return;
        }
        for (Rectangle dirtyRect : dirtyRects) {
            Rectangle clipped = clipRect(dirtyRect, width, height);
            if (clipped == null) {
                continue;
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, renderer.getTextureID());
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, clipped.x);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, clipped.y);
            renderer.onPaintDirect(pixels, clipped.x, clipped.y, clipped.width, clipped.height);
        }
    }

    private void onPaintRenderThread(boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, ByteBuffer compactBuffer,
        DirtyRegion[] compactRegions, int width, int height, Rectangle popupRect, boolean showPopupSnapshot,
        long popupStateGeneration, boolean forceFullUpload) {
        if (!popup) {
            if (forceFullUpload || lastWidth != width || lastHeight != height || !renderer.supportsDirtyRectUpload()) {
                if (buffer == null) {
                    // A compact frame cannot satisfy a full upload request. Drop it and resync so the
                    // next callback carries a complete frame.
                    asyncPaintBufferLeases.requireResync(PaintSurface.VIEW);
                    return;
                }
                lastWidth = width;
                lastHeight = height;
                renderer.onPaint(buffer, width, height);
                restorePopupAfterViewPaint(width, height, popupRect, showPopupSnapshot, popupStateGeneration);
                return;
            }

            if (compactBuffer != null && compactRegions != null) {
                for (DirtyRegion region : compactRegions) {
                    if (region.width <= 0 || region.height <= 0) {
                        continue;
                    }
                    ByteBuffer regionView = compactBuffer.duplicate();
                    regionView.position(region.offset);
                    regionView.limit(region.offset + region.bytes);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, renderer.getTextureID());
                    GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, region.width);
                    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
                    renderer.onPaint(regionView, region.destX, region.destY, region.width, region.height);
                }
            } else {
                for (Rectangle dirtyRect : dirtyRects) {
                    Rectangle clippedRect = clipRect(dirtyRect, width, height);
                    if (clippedRect == null) {
                        continue;
                    }

                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, renderer.getTextureID());
                    GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width);
                    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, clippedRect.x);
                    GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, clippedRect.y);
                    renderer.onPaint(buffer, clippedRect.x, clippedRect.y, clippedRect.width, clippedRect.height);
                }
            }

            restorePopupAfterViewPaint(width, height, popupRect, showPopupSnapshot, popupStateGeneration);
        } else {
            if (!popupPaintState.acceptsPaint(popupStateGeneration, popupRect, showPopupSnapshot, width, height)
                || !renderer.supportsDirtyRectUpload()) {
                asyncPaintBufferLeases.requireResync(PaintSurface.POPUP);
                return;
            }

            int requiredPopupBufferSize = getRequiredBufferSize(popupRect.width, popupRect.height);
            if (requiredPopupBufferSize <= 0 || buffer.capacity() < requiredPopupBufferSize) {
                asyncPaintBufferLeases.requireResync(PaintSurface.POPUP);
                return;
            }

            ByteBuffer popupBuffer = popupGraphics;
            if (popupBuffer == null || popupBuffer.capacity() != requiredPopupBufferSize) {
                popupBuffer = ByteBuffer.allocateDirect(requiredPopupBufferSize);
                popupGraphics = popupBuffer;
                invalidateRetainedPopupPixels();
                forceFullUpload = true;
            }

            forceFullUpload = forceFullUpload
                || popupPaintState.requiresFullPaint(popupStateGeneration, popupRect, showPopupSnapshot);
            boolean copiedCompleteFullFrame = false;
            Rectangle[] paintRects = forceFullUpload ? new Rectangle[] { new Rectangle(0, 0, width, height) }
                : dirtyRects;
            for (Rectangle dirtyRect : paintRects) {
                PopupPaintGeometry.PaintPlan paintPlan = PopupPaintGeometry.plan(
                    dirtyRect,
                    width,
                    height,
                    popupRect.x,
                    popupRect.y,
                    renderer.getTextureWidth(),
                    renderer.getTextureHeight());
                if (paintPlan == null) {
                    continue;
                }

                // Retention stays in popup-local callback space and must include pixels which are currently offscreen.
                copyRectRows(buffer, width, popupBuffer, popupRect.width, paintPlan.retainedSource());
                copiedCompleteFullFrame |= paintPlan.completeSourceFrame();

                PopupPaintGeometry.Upload upload = paintPlan.upload();
                if (upload == null) {
                    continue;
                }
                PopupPaintGeometry.Region uploadSource = upload.source();
                PopupPaintGeometry.Region uploadDestination = upload.destination();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, renderer.getTextureID());
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, uploadSource.x());
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, uploadSource.y());
                renderer.onPaint(
                    buffer,
                    uploadDestination.x(),
                    uploadDestination.y(),
                    uploadDestination.width(),
                    uploadDestination.height());
            }

            // Full retained callback pixels are valid even when the popup had no visible destination pixels to upload.
            if (forceFullUpload && (!copiedCompleteFullFrame || !popupPaintState
                .markFullPainted(popupStateGeneration, popupRect, showPopupSnapshot, width, height))) {
                invalidateRetainedPopupPixels();
                asyncPaintBufferLeases.requireResync(PaintSurface.POPUP);
                return;
            }
            popupDrawn = popupPaintState.canComposite(popupStateGeneration, popupRect, showPopupSnapshot);
        }
    }

    private void restorePopupAfterViewPaint(int viewWidth, int viewHeight, Rectangle popupRect,
        boolean showPopupSnapshot, long popupStateGeneration) {
        if (!popupDrawn || !popupPaintState.canComposite(popupStateGeneration, popupRect, showPopupSnapshot)) {
            return;
        }
        PopupPaintGeometry.PaintPlan paintPlan = PopupPaintGeometry.plan(
            new Rectangle(0, 0, popupRect.width, popupRect.height),
            popupRect.width,
            popupRect.height,
            popupRect.x,
            popupRect.y,
            viewWidth,
            viewHeight);
        if (paintPlan == null || paintPlan.upload() == null) {
            return;
        }
        ByteBuffer popupBuffer = popupGraphics;
        int requiredPopupBufferSize = getRequiredBufferSize(popupRect.width, popupRect.height);
        if (popupBuffer == null || requiredPopupBufferSize <= 0 || popupBuffer.capacity() < requiredPopupBufferSize) {
            invalidateRetainedPopupPixels();
            asyncPaintBufferLeases.requireResync(PaintSurface.POPUP);
            return;
        }
        PopupPaintGeometry.Upload upload = paintPlan.upload();
        PopupPaintGeometry.Region uploadSource = upload.source();
        PopupPaintGeometry.Region uploadDestination = upload.destination();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, renderer.getTextureID());
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, popupRect.width);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, uploadSource.x());
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, uploadSource.y());
        renderer.onPaint(
            popupBuffer,
            uploadDestination.x(),
            uploadDestination.y(),
            uploadDestination.width(),
            uploadDestination.height());
    }

    private void invalidateRetainedPopupPixels() {
        popupPaintState.invalidateRetainedPixels();
        popupDrawn = false;
    }

    private static Rectangle[] copyDirtyRects(Rectangle[] dirtyRects) {
        Rectangle[] copy = new Rectangle[dirtyRects.length];
        for (int i = 0; i < dirtyRects.length; i++) {
            Rectangle dirtyRect = dirtyRects[i];
            copy[i] = dirtyRect == null ? null : new Rectangle(dirtyRect);
        }
        return copy;
    }

    private static void copyRectRows(ByteBuffer src, int srcWidth, ByteBuffer dst, int dstWidth,
        PopupPaintGeometry.Region rect) {
        long srcAddr = MemoryUtilBridge.memAddress(src);
        long dstAddr = MemoryUtilBridge.memAddress(dst);
        int bytesPerRow = rect.width() << 2;
        for (int row = 0; row < rect.height(); row++) {
            int srcOffset = ((rect.y() + row) * srcWidth + rect.x()) << 2;
            int dstOffset = ((rect.y() + row) * dstWidth + rect.x()) << 2;
            MemoryUtilBridge.memCopy(srcAddr + srcOffset, dstAddr + dstOffset, bytesPerRow);
        }
    }

    private static Rectangle clipRect(Rectangle rect, int maxWidth, int maxHeight) {
        if (rect == null || maxWidth <= 0 || maxHeight <= 0) {
            return null;
        }

        int x = Math.max(0, rect.x);
        int y = Math.max(0, rect.y);
        int maxX = Math.min(maxWidth, rect.x + rect.width);
        int maxY = Math.min(maxHeight, rect.y + rect.height);
        int width = maxX - x;
        int height = maxY - y;

        if (width <= 0 || height <= 0) {
            return null;
        }

        return new Rectangle(x, y, width, height);
    }

    private static int getRequiredBufferSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return 0;
        }

        long bufferSize = (long) width * height * 4L;
        if (bufferSize <= 0L || bufferSize > Integer.MAX_VALUE) {
            return 0;
        }

        return (int) bufferSize;
    }

    private ByteBuffer cloneBufferForAsyncPaint(ByteBuffer source) {
        int bytes = source.capacity();
        ByteBuffer copy = frameBufferPool.acquire(bytes);
        try {
            long srcAddress = MemoryUtilBridge.memAddress(source);
            long dstAddress = MemoryUtilBridge.memAddress(copy);
            if (srcAddress != 0L && dstAddress != 0L) {
                MemoryUtilBridge.memCopy(srcAddress, dstAddress, bytes);
            } else {
                copy.put(source.duplicate());
            }
            copy.clear();
            copy.limit(bytes);
            return copy;
        } catch (Throwable failure) {
            frameBufferPool.release(copy);
            throw failure;
        }
    }

    public void resize(int width, int height) {
        browser_rect_.setBounds(0, 0, width, height);
        wasResized(width, height);
    }

    /**
     * Sets the device scale factor (DPR) for this off-screen browser.
     * <p>
     * The CSS viewport size is controlled by {@link #resize(int, int)}, while the
     * physical render resolution becomes {@code viewport * scaleFactor}. CSS layout
     * (including font sizes) stays in logical (viewport) units, so increasing the
     * scale factor produces a sharper texture without shrinking page content.
     *
     * @param scaleFactor device pixel ratio, must be >= 1.0
     */
    public void setDeviceScaleFactor(double scaleFactor) {
        if (scaleFactor < 1.0) scaleFactor = 1.0;
        updateScreenInfo(scaleFactor, 32, 8);
    }

    // Inputs
    public void sendKeyPress(int keyCode, long scanCode, int modifiers) {
        updateModifierStateOnKeyPress(keyCode);
        int normalizedModifiers = normalizeAltGrModifiers(modifiers);

        if (browserControls) {
            if (normalizedModifiers == GLFW_MOD_CONTROL) {
                if (keyCode == GLFW_KEY_R) {
                    reload();
                    return;
                } else if (keyCode == GLFW_KEY_EQUAL) {
                    if (getZoomLevel() < 9) setZoomLevel(getZoomLevel() + 1);
                    return;
                } else if (keyCode == GLFW_KEY_MINUS) {
                    if (getZoomLevel() > -9) setZoomLevel(getZoomLevel() - 1);
                    return;
                } else if (keyCode == GLFW_KEY_0) {
                    setZoomLevel(0);
                    return;
                }
            } else if (normalizedModifiers == GLFW_MOD_ALT) {
                if (keyCode == GLFW_KEY_LEFT && canGoBack()) {
                    goBack();
                    return;
                } else if (keyCode == GLFW_KEY_RIGHT && canGoForward()) {
                    goForward();
                    return;
                }
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_PRESS, keyCode, (char) keyCode, normalizedModifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
    }

    public void sendKeyRelease(int keyCode, long scanCode, int modifiers) {
        int normalizedModifiers = normalizeAltGrModifiers(modifiers);

        if (browserControls) {
            if (normalizedModifiers == GLFW_MOD_CONTROL) {
                if (keyCode == GLFW_KEY_R) return;
                else if (keyCode == GLFW_KEY_EQUAL) return;
                else if (keyCode == GLFW_KEY_MINUS) return;
                else if (keyCode == GLFW_KEY_0) return;
            } else if (normalizedModifiers == GLFW_MOD_ALT) {
                if (keyCode == GLFW_KEY_LEFT && canGoBack()) return;
                else if (keyCode == GLFW_KEY_RIGHT && canGoForward()) return;
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_RELEASE, keyCode, (char) keyCode, normalizedModifiers);
        e.scancode = scanCode;
        sendKeyEvent(e);
        updateModifierStateOnKeyRelease(keyCode);
    }

    public void sendKeyTyped(char c, int modifiers) {
        int normalizedModifiers = normalizeAltGrModifiers(modifiers);

        if (browserControls) {
            if (normalizedModifiers == GLFW_MOD_CONTROL) {
                if ((int) c == GLFW_KEY_R) return;
                else if ((int) c == GLFW_KEY_EQUAL) return;
                else if ((int) c == GLFW_KEY_MINUS) return;
                else if ((int) c == GLFW_KEY_0) return;
            } else if (normalizedModifiers == GLFW_MOD_ALT) {
                if ((int) c == GLFW_KEY_LEFT && canGoBack()) return;
                else if ((int) c == GLFW_KEY_RIGHT && canGoForward()) return;
            }
        }

        CefKeyEvent e = new CefKeyEvent(CefKeyEvent.KEY_TYPE, c, c, normalizedModifiers);
        sendKeyEvent(e);
    }

    private void updateModifierStateOnKeyPress(int keyCode) {
        if (keyCode == GLFW_KEY_RIGHT_ALT) {
            rightAltDown = true;
        }
    }

    private void updateModifierStateOnKeyRelease(int keyCode) {
        if (keyCode == GLFW_KEY_RIGHT_ALT) {
            rightAltDown = false;
        }
    }

    private int normalizeAltGrModifiers(int modifiers) {
        if (rightAltDown && (modifiers & GLFW_MOD_ALT) == 0) {
            rightAltDown = false;
        }

        // GLFW reports AltGr as Ctrl+Alt on many layouts.
        if (!rightAltDown) {
            return modifiers;
        }

        if ((modifiers & GLFW_MOD_CONTROL) == 0 || (modifiers & GLFW_MOD_ALT) == 0) {
            return modifiers;
        }

        return modifiers & ~(GLFW_MOD_CONTROL | GLFW_MOD_ALT);
    }

    public void sendMouseMove(int mouseX, int mouseY) {
        CefMouseEvent e = new CefMouseEvent(
            CefMouseEvent.MOUSE_MOVED,
            mouseX,
            mouseY,
            0,
            0,
            dragContext.getVirtualModifiers(btnMask));
        sendMouseEvent(e);

        if (dragContext.isDragging()) this.dragTargetDragOver(new Point(mouseX, mouseY), 0, dragContext.getMask());
    }

    // TODO: it may be necessary to add modifiers here
    public void sendMousePress(int mouseX, int mouseY, int button) {
        // for some reason, middle and right are swapped in MC
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0) btnMask |= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1) btnMask |= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2) btnMask |= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(GLFW_PRESS, mouseX, mouseY, 1, button, btnMask);
        sendMouseEvent(e);
    }

    // TODO: it may be necessary to add modifiers here
    public void sendMouseRelease(int mouseX, int mouseY, int button) {
        // For some reason, middle and right are swapped in MC
        if (button == 1) button = 2;
        else if (button == 2) button = 1;

        if (button == 0 && (btnMask & CefMouseEvent.BUTTON1_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON1_MASK;
        else if (button == 1 && (btnMask & CefMouseEvent.BUTTON2_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON2_MASK;
        else if (button == 2 && (btnMask & CefMouseEvent.BUTTON3_MASK) != 0) btnMask ^= CefMouseEvent.BUTTON3_MASK;

        CefMouseEvent e = new CefMouseEvent(GLFW_RELEASE, mouseX, mouseY, 1, button, btnMask);
        sendMouseEvent(e);

        // drag&drop
        if (dragContext.isDragging()) {
            if (button == 0) {
                finishDragging(mouseX, mouseY);
            }
        }
    }

    // TODO: smooth scrolling
    public void sendMouseWheel(int mouseX, int mouseY, double amount, int modifiers) {
        if (browserControls) {
            if ((modifiers & GLFW_MOD_CONTROL) != 0) {
                if (amount > 0) {
                    if (getZoomLevel() < 9) setZoomLevel(getZoomLevel() + 1);
                } else if (getZoomLevel() > -9) setZoomLevel(getZoomLevel() - 1);
                return;
            }
        }

        // macOS generally has a slow scroll speed that feels more natural with their magic mice / trackpads
        if (!OSPlatform.getPlatform()
            .isMacOS()) {
            // This removes the feeling of "smooth scroll"
            if (amount < 0) {
                amount = Math.floor(amount);
            } else {
                amount = Math.ceil(amount);
            }

            // This feels about equivalent to chromium with smooth scrolling disabled -ds58
            amount = amount * 3;
        }

        CefMouseWheelEvent e = new CefMouseWheelEvent(
            CefMouseWheelEvent.WHEEL_UNIT_SCROLL,
            mouseX,
            mouseY,
            amount,
            modifiers);
        sendMouseWheelEvent(e);
    }

    // Drag & drop
    /**
     * Rinku is both the source and target for its emulated OSR drag. CEF requires every target
     * callback to precede source completion: a drop uses TargetDrop -> SourceEndedAt ->
     * SourceSystemDragEnded, while cancellation uses the explicitly permitted TargetLeave ->
     * SourceSystemDragEnded path. TargetLeave is not sent after a successful drop because CEF
     * defines leave and drop as alternative target endings.
     */
    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        try {
            boolean handled = dragContext.startDraggingOwned(dragData, mask, x, y, btnMask);
            if (!handled && !dragContext.isDragging()) restoreActualCursorAfterFailedStart(null);
            completeDeferredNativeCloseAfterStart(null);
            return handled;
        } catch (RuntimeException | Error failure) {
            if (!dragContext.isDragging()) restoreActualCursorAfterFailedStart(failure);
            completeDeferredNativeCloseAfterStart(failure);
            throw failure;
        }
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {
        if (dragContext.updateCursor(operation)) notifyCursorChange(this, dragContext.getCurrentVirtualCursor());
        super.updateDragCursor(browser, operation);
    }

    // Expose drag & drop functions
    public void startDragging(CefDragData dragData, int mask, int x, int y) { // Overload since the JCEF method requires
                                                                              // a browser, which then goes unused
        startDragging(this, dragData, mask, x, y);
    }

    public void finishDragging(int x, int y) {
        completeDragLifecycle(() -> dragContext.finishDragging(x, y, btnMask));
    }

    public void cancelDrag() {
        completeDragLifecycle(dragContext::cancelDrag);
    }

    private void completeDragLifecycle(DragCompletion completion) {
        boolean wasDragging = dragContext.isDragging();
        Throwable failure = null;
        try {
            completion.complete();
        } catch (RuntimeException | Error completionFailure) {
            failure = completionFailure;
        }
        if (wasDragging && !dragContext.isDragging()) {
            try {
                notifyCursorChange(this, dragContext.getActualCursor());
            } catch (RuntimeException | Error cursorFailure) {
                failure = mergeFailure(failure, cursorFailure);
            }
        }
        try {
            completeDeferredNativeClose();
        } catch (RuntimeException | Error closeFailure) {
            failure = mergeFailure(failure, closeFailure);
        }
        rethrowLifecycleFailure(failure);
    }

    private void completeDeferredNativeCloseAfterStart(Throwable primaryFailure) {
        try {
            completeDeferredNativeClose();
        } catch (RuntimeException | Error closeFailure) {
            if (primaryFailure == null) {
                LOGGER.warn("Failed to continue a browser close after rejecting an in-progress drag.", closeFailure);
            } else {
                addSuppressed(primaryFailure, closeFailure);
            }
        }
    }

    private void restoreActualCursorAfterFailedStart(Throwable primaryFailure) {
        try {
            notifyCursorChange(this, dragContext.getActualCursor());
        } catch (RuntimeException | Error cursorFailure) {
            if (primaryFailure == null) {
                LOGGER.warn("Failed to restore the browser cursor after rejecting a drag.", cursorFailure);
            } else {
                addSuppressed(primaryFailure, cursorFailure);
            }
        }
    }

    // Closing
    public void close() {
        close(true);
    }

    @Override
    public void close(boolean force) {
        if (!force) {
            // A before-unload handler may cancel a non-forced close. Keep paint admission and GL
            // resources alive until CEF confirms terminal closure through onBeforeClose().
            super.close(false);
            return;
        }
        closeBrowser(RenderStateBridge.isOnRenderThread());
    }

    @Override
    public void onBeforeClose() {
        Throwable failure = null;
        try {
            requestClose();
        } catch (Throwable closeRequestFailure) {
            failure = closeRequestFailure;
        }
        closeController.markNativeClosed();
        if (RenderStateBridge.isOnRenderThread()) {
            try {
                cleanupBrowserResourcesOnRenderThread();
            } catch (Throwable cleanupFailure) {
                failure = mergeFailure(failure, cleanupFailure);
            }
        }
        try {
            super.onBeforeClose();
        } catch (Throwable nativeLifecycleFailure) {
            failure = mergeFailure(failure, nativeLifecycleFailure);
        }
        rethrowLifecycleFailure(failure);
    }

    private void requestClose() {
        closeController.requestClose(this::stopBrowserResourceAdmission);
    }

    private void stopBrowserResourceAdmission() {
        Throwable failure = null;
        try {
            asyncPaintBufferLeases.close();
        } catch (RuntimeException | Error paintFailure) {
            failure = paintFailure;
        }
        deferredNativeClose.set(true);
        try {
            // Forced close reaches this path before super.close(true) makes CefBrowser_N reject
            // drag callbacks. If close re-enters from one of those callbacks, native close must be
            // deferred until the outer transition sends every target/source completion callback.
            dragContext.close();
        } catch (RuntimeException | Error dragFailure) {
            failure = mergeFailure(failure, dragFailure);
        } finally {
            if (!dragContext.isTransitioning()) deferredNativeClose.set(false);
        }
        rethrowLifecycleFailure(failure);
    }

    private void closeNativeBrowser() {
        closeController.closeNative(() -> super.close(true));
    }

    private void completeDeferredNativeClose() {
        if (dragContext.isTransitioning() || !deferredNativeClose.compareAndSet(true, false)) return;
        closeNativeBrowser();
    }

    private void closeBrowser(boolean cleanupRenderer) {
        Throwable failure = null;
        try {
            requestClose();
        } catch (Throwable closeRequestFailure) {
            failure = closeRequestFailure;
        }
        if (cleanupRenderer) {
            try {
                cleanupBrowserResourcesOnRenderThread();
            } catch (Throwable cleanupFailure) {
                failure = mergeFailure(failure, cleanupFailure);
            }
        }
        if (!deferredNativeClose.get()) {
            try {
                closeNativeBrowser();
            } catch (Throwable nativeCloseFailure) {
                failure = mergeFailure(failure, nativeCloseFailure);
            }
        }
        rethrowLifecycleFailure(failure);
    }

    private void initializeRendererOnRenderThread() {
        RenderStateBridge.assertOnRenderThread();
        if (rendererInitialized || rendererCleanupStarted || closeController.isCloseRequested()) {
            return;
        }
        try {
            renderer.initialize();
            rendererInitialized = true;
        } catch (Throwable initializationFailure) {
            try {
                closeBrowser(true);
            } catch (Throwable lifecycleFailure) {
                addSuppressed(initializationFailure, lifecycleFailure);
            }
            throw initializationFailure;
        }
    }

    private void beginRenderOperation() {
        RenderStateBridge.assertOnRenderThread();
        if (rendererCleanupStarted) {
            throw new IllegalStateException("Browser renderer has already been cleaned up");
        }
        renderOperationDepth++;
    }

    private void endRenderOperation() {
        renderOperationDepth--;
        if (renderOperationDepth < 0) {
            renderOperationDepth = 0;
            throw new IllegalStateException("Browser render operation depth became negative");
        }
        if (renderOperationDepth == 0 && closeController.isCloseRequested()) {
            cleanupBrowserResourcesOnRenderThread();
        }
    }

    private void cleanupBrowserResourcesOnRenderThread() {
        RenderStateBridge.assertOnRenderThread();
        if (rendererCleanupStarted || renderOperationDepth > 0) {
            return;
        }
        rendererCleanupStarted = true;
        Throwable failure = null;
        try {
            CefSharedTexture.unlockSharedTexture();
            CefSharedTexture.cleanup();
            renderer.clearExternalTexture();
        } catch (Throwable cleanupFailure) {
            failure = cleanupFailure;
        }
        try {
            renderer.cleanup();
        } catch (Throwable cleanupFailure) {
            failure = mergeFailure(failure, cleanupFailure);
        }
        try {
            if (sharedTextureReadBuffer != null) {
                MemoryUtilBridge.memFree(sharedTextureReadBuffer);
                sharedTextureReadBuffer = null;
                sharedTextureReadBufferSize = 0;
            }
        } catch (Throwable bufferCleanupFailure) {
            failure = mergeFailure(failure, bufferCleanupFailure);
        }
        try {
            viewPixelStore.dispose();
            frameBufferPool.clear();
        } catch (Throwable frameBufferFailure) {
            failure = mergeFailure(failure, frameBufferFailure);
        }
        try {
            dragContext.close();
        } catch (Throwable dragCleanupFailure) {
            failure = mergeFailure(failure, dragCleanupFailure);
        }
        try {
            if (cursorChangeListener != null) {
                cursorChangeListener.onCursorChange(0);
            }
        } catch (Throwable cursorFailure) {
            if (failure == null) {
                failure = cursorFailure;
            } else if (failure != cursorFailure) {
                failure.addSuppressed(cursorFailure);
            }
        } finally {
            RinkuRenderCoordinator.unregister(this);
        }
        if (failure != null) {
            LOGGER.warn("Failed to clean up browser render-thread resources.", failure);
        }
    }

    private static void addSuppressed(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure != secondaryFailure) {
            primaryFailure.addSuppressed(secondaryFailure);
        }
    }

    private static Throwable mergeFailure(Throwable primaryFailure, Throwable secondaryFailure) {
        if (primaryFailure == null) {
            return secondaryFailure;
        }
        addSuppressed(primaryFailure, secondaryFailure);
        return primaryFailure;
    }

    private static void rethrowLifecycleFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error errorFailure) {
            throw errorFailure;
        }
        if (failure != null) {
            throw new IllegalStateException("Browser lifecycle action failed", failure);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            requestClose();
        } finally {
            super.finalize();
        }
    }

    // Cursor handling
    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return notifyCursorChange(browser, dragContext.getVirtualCursor(cursorType));
    }

    private boolean notifyCursorChange(CefBrowser browser, int cursorType) {
        cursorChangeListener.onCursorChange(cursorType);
        return super.onCursorChange(browser, cursorType);
    }

    public void setCursor(CefCursorType cursorType) {
        Objects.requireNonNull(cursorType);
        try {
            CURSOR_CONTROLLER.apply(cursorType, CURSOR_BACKEND);
        } catch (Throwable ignored) {}
    }

    private static CefCursorType resolveCursorType(int cursorTypeId) {
        return CefCursorType.fromId(cursorTypeId);
    }

    private static final class GlfwCursorBackend implements BrowserCursorController.CursorBackend {

        @Override
        public boolean isMouseGrabbed() {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                return (mc != null) && mc.gameSettings.pauseOnLostFocus && org.lwjgl.input.Mouse.isGrabbed();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public void hideCursor() {
            try {
                Rinku.setCursorType(CefCursorType.NONE);
            } catch (Throwable ignored) {}
        }

        @Override
        public void showCursor(CefCursorType cursorType) {
            try {
                Rinku.setCursorType(cursorType);
            } catch (Throwable ignored) {}
        }

    }

    @FunctionalInterface
    private interface DragCompletion {

        boolean complete();
    }

    private enum PaintSurface {

        VIEW,
        POPUP;

        private static PaintSurface fromPopup(boolean popup) {
            return popup ? POPUP : VIEW;
        }
    }

    private static final class DirtyRegion {

        private final int destX;
        private final int destY;
        private final int width;
        private final int height;
        private final int offset;
        private final int bytes;

        private DirtyRegion(int destX, int destY, int width, int height, int offset) {
            this.destX = destX;
            this.destY = destY;
            this.width = width;
            this.height = height;
            this.offset = offset;
            this.bytes = width * height * 4;
        }
    }

    private static final class CompactCopyResult {

        private final ByteBuffer buffer;
        private final DirtyRegion[] regions;

        private CompactCopyResult(ByteBuffer buffer, DirtyRegion[] regions) {
            this.buffer = buffer;
            this.regions = regions;
        }

        private ByteBuffer buffer() {
            return buffer;
        }

        private DirtyRegion[] regions() {
            return regions;
        }
    }

    private static final class AsyncPaintFrame {

        private final PaintSurface surface;
        private final Rectangle[] dirtyRects;
        private final ByteBuffer fullBuffer;
        private final ByteBuffer compactBuffer;
        private final DirtyRegion[] compactRegions;
        private final int width;
        private final int height;
        private final Rectangle popupRect;
        private final boolean showPopup;
        private final long popupStateGeneration;
        private final long sharedTextureHandle;
        private final boolean useViewStore;
        private volatile boolean fullUpload;

        private AsyncPaintFrame(PaintSurface surface, Rectangle[] dirtyRects, ByteBuffer fullBuffer,
            ByteBuffer compactBuffer, DirtyRegion[] compactRegions, int width, int height, Rectangle popupRect,
            boolean showPopup, long popupStateGeneration, long sharedTextureHandle) {
            this(
                surface,
                dirtyRects,
                fullBuffer,
                compactBuffer,
                compactRegions,
                width,
                height,
                popupRect,
                showPopup,
                popupStateGeneration,
                sharedTextureHandle,
                false);
        }

        private AsyncPaintFrame(PaintSurface surface, Rectangle[] dirtyRects, ByteBuffer fullBuffer,
            ByteBuffer compactBuffer, DirtyRegion[] compactRegions, int width, int height, Rectangle popupRect,
            boolean showPopup, long popupStateGeneration, long sharedTextureHandle, boolean useViewStore) {
            this.surface = surface;
            this.dirtyRects = dirtyRects;
            this.fullBuffer = fullBuffer;
            this.compactBuffer = compactBuffer;
            this.compactRegions = compactRegions;
            this.width = width;
            this.height = height;
            this.popupRect = popupRect;
            this.showPopup = showPopup;
            this.popupStateGeneration = popupStateGeneration;
            this.sharedTextureHandle = sharedTextureHandle;
            this.useViewStore = useViewStore;
        }

        /** A frame whose pixels already live in the persistent view mirror; no buffer ownership. */
        private static AsyncPaintFrame forViewStore(Rectangle[] dirtyRects, int width, int height,
            Rectangle popupRect, boolean showPopup, long popupStateGeneration) {
            return new AsyncPaintFrame(
                PaintSurface.VIEW,
                dirtyRects,
                null,
                null,
                null,
                width,
                height,
                popupRect,
                showPopup,
                popupStateGeneration,
                0L,
                true);
        }

        private PaintSurface surface() {
            return surface;
        }

        private Rectangle[] dirtyRects() {
            return dirtyRects;
        }

        private ByteBuffer fullBuffer() {
            return fullBuffer;
        }

        private ByteBuffer compactBuffer() {
            return compactBuffer;
        }

        private DirtyRegion[] compactRegions() {
            return compactRegions;
        }

        private int width() {
            return width;
        }

        private int height() {
            return height;
        }

        private Rectangle popupRect() {
            return popupRect;
        }

        private boolean showPopup() {
            return showPopup;
        }

        private long popupStateGeneration() {
            return popupStateGeneration;
        }

        private long sharedTextureHandle() {
            return sharedTextureHandle;
        }

        private boolean useViewStore() {
            return useViewStore;
        }

        private void requireFullUpload() {
            fullUpload = true;
        }

        private boolean requiresFullUpload() {
            return fullUpload;
        }
    }

}
