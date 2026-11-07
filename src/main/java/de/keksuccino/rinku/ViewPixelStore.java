package de.keksuccino.rinku;

import java.awt.Rectangle;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A persistent CPU-side mirror of the browser view frame.
 *
 * <p>
 * CEF's {@code onPaint} buffer is only valid for the duration of the callback. The usual way to hand
 * those pixels to the render thread is to clone the whole frame (several megabytes on a large
 * screen) on CEF's UI thread, and then copy it once more into a pixel-unpack buffer before OpenGL
 * can upload it. Both copies happen for <em>every</em> frame.
 *
 * <p>
 * This store keeps one full-size buffer for the entire lifetime of the browser. A paint callback
 * copies only the changed rows into it, and the render thread uploads directly from that same
 * buffer. Neither thread ever waits for the other: when the render thread owns the mirror the
 * callback falls back to the bounded compact-frame mailbox, and when the callback owns it the
 * render thread skips the frame and requests a full resync. The store still holds the newest pixels,
 * so a skipped frame is repaired by the next full upload instead of losing content.
 */
final class ViewPixelStore {

    @FunctionalInterface
    interface UploadAction {

        void upload(ByteBuffer pixels, Rectangle[] dirtyRects, int width, int height, boolean fullFrame);
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final DirectBufferPool bufferPool;
    private ByteBuffer pixels;
    private int width;
    private int height;
    private volatile boolean complete;

    ViewPixelStore(DirectBufferPool bufferPool) {
        this.bufferPool = bufferPool;
    }

    /**
     * Called on the CEF callback thread. Copies just the dirty rows from the callback-scoped buffer
     * into the persistent mirror. A resize or an invalidated mirror is repaired with one (rare) full
     * frame copy.
     *
     * @return false when the render thread currently owns the mirror, or when the input is unusable;
     *         the caller must then use the compact-frame fallback
     */
    boolean tryPatch(Rectangle[] dirtyRects, ByteBuffer source, int frameWidth, int frameHeight) {
        if (source == null || dirtyRects == null || frameWidth <= 0 || frameHeight <= 0) {
            return false;
        }
        long requiredLong = (long) frameWidth * frameHeight * 4L;
        if (requiredLong <= 0L || requiredLong > Integer.MAX_VALUE) {
            return false;
        }
        int required = (int) requiredLong;
        if (source.capacity() < required) {
            return false;
        }
        long srcAddress = MemoryUtilBridge.memAddress(source);
        if (srcAddress == 0L) {
            return false;
        }
        if (!lock.tryLock()) {
            return false;
        }
        try {
            boolean resized = pixels == null || width != frameWidth || height != frameHeight;
            boolean needFullCopy = resized || !complete;
            if (resized) {
                if (pixels != null) {
                    bufferPool.release(pixels);
                }
                pixels = bufferPool.acquire(required);
                if (pixels == null) {
                    return false;
                }
                width = frameWidth;
                height = frameHeight;
                complete = false;
            }
            long dstAddress = MemoryUtilBridge.memAddress(pixels);
            if (dstAddress == 0L) {
                complete = false;
                return false;
            }
            if (needFullCopy) {
                MemoryUtilBridge.memCopy(srcAddress, dstAddress, required);
                complete = true;
                return true;
            }
            for (Rectangle dirtyRect : dirtyRects) {
                Rectangle clipped = clip(dirtyRect, frameWidth, frameHeight);
                if (clipped == null) {
                    continue;
                }
                int bytesPerRow = clipped.width << 2;
                for (int row = 0; row < clipped.height; row++) {
                    long offset = (((long) (clipped.y + row) * frameWidth) + clipped.x) << 2;
                    MemoryUtilBridge.memCopy(srcAddress + offset, dstAddress + offset, bytesPerRow);
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Called on the render thread. Runs the OpenGL upload while the mirror is locked, so the CEF
     * thread cannot patch the same rows underneath us. Uploading straight from a plain direct
     * buffer also avoids the extra CPU copy that a freshly mapped pixel-unpack buffer would need.
     */
    boolean tryUpload(Rectangle[] dirtyRects, int frameWidth, int frameHeight, boolean fullFrame,
        UploadAction action) {
        if (!lock.tryLock()) {
            return false;
        }
        try {
            if (pixels == null || !complete || width != frameWidth || height != frameHeight) {
                return false;
            }
            pixels.clear();
            action.upload(pixels, dirtyRects, frameWidth, frameHeight, fullFrame);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Marks the mirror as stale. Used when an accelerated (shared texture) frame bypasses the
     * software callback entirely, so the next software frame must restore a complete image.
     */
    void invalidate() {
        complete = false;
    }

    void dispose() {
        lock.lock();
        try {
            if (pixels != null) {
                bufferPool.release(pixels);
                pixels = null;
            }
            width = 0;
            height = 0;
            complete = false;
        } finally {
            lock.unlock();
        }
    }

    private static Rectangle clip(Rectangle rect, int maxWidth, int maxHeight) {
        if (rect == null || maxWidth <= 0 || maxHeight <= 0) {
            return null;
        }
        int x = Math.max(0, rect.x);
        int y = Math.max(0, rect.y);
        int maxX = Math.min(maxWidth, rect.x + rect.width);
        int maxY = Math.min(maxHeight, rect.y + rect.height);
        int width = maxX - x;
        int height = maxY - y;
        return width <= 0 || height <= 0 ? null : new Rectangle(x, y, width, height);
    }
}
