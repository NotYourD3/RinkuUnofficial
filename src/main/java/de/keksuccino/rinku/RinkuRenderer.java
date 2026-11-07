package de.keksuccino.rinku;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLContext;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public class RinkuRenderer {

    private final boolean transparent;
    private int glTextureId = 0;
    private int externalGlTextureId = 0;
    private boolean useExternalTexture = false;
    private int externalTextureWidth = 0;
    private int externalTextureHeight = 0;
    private int textureWidth = 0;
    private int textureHeight = 0;
    /** 1x1 fully transparent texture used until CEF delivers its first real frame. */
    private int placeholderGlTextureId = 0;
    /** True only after a real software or GPU frame has been published. */
    private volatile boolean framePresented = false;

    private final ResourceLocation textureIdentifier;
    private RinkuDirectTexture directTexture;
    private boolean textureRegistered = false;
    private ByteBuffer fallbackRgbaUploadBuffer;
    private final int[] pixelUnpackPboIds = new int[2];
    private int pboToggle = 0;

    private static Method nglTexImage2DMethod;
    private static Method nglTexSubImage2DMethod;
    private static Field glTexImage2DPointerField;
    private static Field glTexSubImage2DPointerField;
    private static boolean nativeTexMethodsResolved = false;

    protected RinkuRenderer(boolean transparent) {
        this.transparent = transparent;
        String uniqueId = UUID.randomUUID()
            .toString()
            .toLowerCase()
            .replace("-", "");
        this.textureIdentifier = new ResourceLocation("rinku", "browser_" + uniqueId);
    }

    public void initialize() {
        directTexture = new RinkuDirectTexture();
        // GL texture id 0 is NOT "no texture": it is a valid opaque WHITE texture. If
        // TextureManager binds it before CEF's first frame, the whole UI flashes white.
        // Register a 1x1 fully transparent placeholder as the initial source instead.
        if (placeholderGlTextureId == 0) {
            int id = GL11.glGenTextures();
            if (id != 0) {
                ByteBuffer pixel = ByteBuffer.allocateDirect(4);
                pixel.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
                pixel.flip();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
                GL11.glTexImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    GL11.GL_RGBA,
                    1,
                    1,
                    0,
                    GL11.GL_RGBA,
                    GL11.GL_UNSIGNED_BYTE,
                    pixel);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                placeholderGlTextureId = id;
            }
        }
        Minecraft.getMinecraft()
            .getTextureManager()
            .loadTexture(textureIdentifier, directTexture);
        textureRegistered = true;
        if (placeholderGlTextureId != 0) {
            directTexture.bindTexture(placeholderGlTextureId, 1, 1);
        }
        syncDirectTextureViewIfNeeded();
    }

    public int getTextureIdLegacy() {
        return glTextureId;
    }

    public ResourceLocation getTextureIdentifier() {
        return textureIdentifier;
    }

    public boolean isTextureReady() {
        // A single source of truth: only advertise readiness once a real frame has been
        // published AND the TextureManager-facing view points at that frame. The old GPU
        // branch returned true as soon as the external id was set, even if the view had
        // not been rebound (which left it sampling texture 0 = opaque white).
        return framePresented && textureRegistered && directTexture != null
            && directTexture.isTextureViewReady()
            && directTexture.getTextureGlId() != 0
            && directTexture.getTextureGlId() != placeholderGlTextureId;
    }

    public int getTextureID() {
        return useExternalTexture ? externalGlTextureId : glTextureId;
    }

    public boolean supportsDirtyRectUpload() {
        return glTextureId != 0;
    }

    public int getTextureWidth() {
        return useExternalTexture ? externalTextureWidth : textureWidth;
    }

    public int getTextureHeight() {
        return useExternalTexture ? externalTextureHeight : textureHeight;
    }

    public boolean isTransparent() {
        return transparent;
    }

    public void setExternalTexture(int glId, int width, int height) {
        this.externalGlTextureId = glId;
        this.externalTextureWidth = width;
        this.externalTextureHeight = height;
        this.useExternalTexture = (glId != 0);
        this.framePresented = (glId != 0);
        // Consumers that draw via getTextureIdentifier() go through Minecraft's TextureManager,
        // which binds RinkuDirectTexture.getGlTextureId(). Without this rebind those consumers
        // would keep sampling texture 0 (pure white) whenever only accelerated frames arrive.
        syncDirectTextureViewIfNeeded();
    }

    public void clearExternalTexture() {
        this.externalGlTextureId = 0;
        this.externalTextureWidth = 0;
        this.externalTextureHeight = 0;
        this.useExternalTexture = false;
        this.framePresented = false;
        syncDirectTextureViewIfNeeded();
    }

    public boolean usesExternalTexture() {
        return useExternalTexture;
    }

    /**
     * Called when the browser navigates to a new document. Rinku reuses preloaded browsers
     * whose start page is about:blank; without this the texture still holds that opaque white
     * frame and consumers draw it until the new page paints. Mark the content stale and point
     * the TextureManager view back at the transparent placeholder until the first real frame
     * of the new document arrives.
     */
    protected void markContentStale() {
        this.framePresented = false;
        if (directTexture != null && placeholderGlTextureId != 0) {
            directTexture.bindTexture(placeholderGlTextureId, 1, 1);
        }
    }

    protected void cleanup() {
        // TextureManager.deleteTexture() deletes whatever id RinkuDirectTexture currently points
        // at. During a GPU frame that id belongs to the native interop layer, so detach it first
        // to avoid deleting a texture we do not own.
        useExternalTexture = false;
        framePresented = false;
        externalGlTextureId = 0;
        externalTextureWidth = 0;
        externalTextureHeight = 0;
        // Detach completely: TextureManager.deleteTexture() deletes whatever id this view
        // reports. Pointing it at 0 (a no-op delete) keeps both the native interop texture
        // and our placeholder out of the TextureManager's ownership.
        if (directTexture != null) {
            directTexture.bindTexture(0, 0, 0);
        }
        if (glTextureId != 0) {
            GL11.glDeleteTextures(glTextureId);
            glTextureId = 0;
        }
        if (placeholderGlTextureId != 0) {
            GL11.glDeleteTextures(placeholderGlTextureId);
            placeholderGlTextureId = 0;
        }
        for (int i = 0; i < pixelUnpackPboIds.length; i++) {
            if (pixelUnpackPboIds[i] != 0) {
                GL15.glDeleteBuffers(pixelUnpackPboIds[i]);
                pixelUnpackPboIds[i] = 0;
            }
        }
        fallbackRgbaUploadBuffer = null;

        if (textureRegistered && textureIdentifier != null
            && Minecraft.getMinecraft() != null
            && Minecraft.getMinecraft()
                .getTextureManager() != null) {
            try {
                Minecraft.getMinecraft()
                    .getTextureManager()
                    .deleteTexture(textureIdentifier);
            } catch (Exception ignored) {}
            textureRegistered = false;
        }
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
        onPaint(buffer, width, height, true);
    }

    /**
     * Uploads a full frame straight from a persistent direct buffer. Unlike the PBO path this does
     * not allocate a GPU buffer and copy the pixels into it first, so the frame only ever pays for
     * one CPU copy (the dirty-row patch performed by {@link ViewPixelStore}).
     */
    protected void onPaintDirect(ByteBuffer buffer, int width, int height) {
        onPaint(buffer, width, height, false);
    }

    private void onPaint(ByteBuffer buffer, int width, int height, boolean usePbo) {
        if (glTextureId == 0 || textureWidth != width || textureHeight != height) {
            if (glTextureId != 0) {
                GL11.glDeleteTextures(glTextureId);
            }
            glTextureId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
            textureWidth = width;
            textureHeight = height;
        }

        framePresented = true;
        syncDirectTextureViewIfNeeded();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        if (usePbo && uploadImageWithPbo(buffer, width, height)) {
            resetGlPixelStoreState();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            return;
        }
        GL11.glTexImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            GL11.GL_RGBA,
            width,
            height,
            0,
            GL12.GL_BGRA,
            GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
            buffer);
        resetGlPixelStoreState();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        onPaint(buffer, x, y, width, height, true);
    }

    /** Dirty-rectangle upload straight from the persistent view mirror, without a PBO copy. */
    protected void onPaintDirect(ByteBuffer buffer, int x, int y, int width, int height) {
        onPaint(buffer, x, y, width, height, false);
    }

    private void onPaint(ByteBuffer buffer, int x, int y, int width, int height, boolean usePbo) {
        framePresented = true;
        syncDirectTextureViewIfNeeded();
        if (glTextureId != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
            if (!(usePbo && uploadSubImageWithPbo(buffer, x, y, width, height))) {
                GL11.glTexSubImage2D(
                    GL11.GL_TEXTURE_2D,
                    0,
                    x,
                    y,
                    width,
                    height,
                    GL12.GL_BGRA,
                    GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                    buffer);
            }
            resetGlPixelStoreState();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            return;
        }
        uploadWithFallback(buffer, x, y, width, height);
    }

    private void syncDirectTextureViewIfNeeded() {
        if (!textureRegistered || directTexture == null) {
            return;
        }
        // When a GPU (accelerated-paint) frame is active the software texture id may still be 0.
        // Bind the effective source, otherwise TextureManager.bindTexture(textureIdentifier)
        // resolves to texture 0 and the browser renders as a solid white quad.
        int sourceId = useExternalTexture ? externalGlTextureId : glTextureId;
        int sourceWidth = useExternalTexture ? externalTextureWidth : textureWidth;
        int sourceHeight = useExternalTexture ? externalTextureHeight : textureHeight;
        if (sourceId == 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        boolean needsRebind = !directTexture.isTextureViewReady() || directTexture.getWidth() != sourceWidth
            || directTexture.getHeight() != sourceHeight
            || directTexture.getTextureGlId() != sourceId;
        if (needsRebind) {
            directTexture.bindTexture(sourceId, sourceWidth, sourceHeight);
        }
    }

    private static synchronized boolean ensureNativeTexMethodsResolved() {
        if (nativeTexMethodsResolved) {
            return nglTexImage2DMethod != null && nglTexSubImage2DMethod != null;
        }
        nativeTexMethodsResolved = true;
        try {
            nglTexImage2DMethod = GL11.class.getDeclaredMethod(
                "nglTexImage2D",
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                long.class,
                long.class);
            nglTexImage2DMethod.setAccessible(true);
            nglTexSubImage2DMethod = GL11.class.getDeclaredMethod(
                "nglTexSubImage2D",
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                int.class,
                long.class,
                long.class);
            nglTexSubImage2DMethod.setAccessible(true);
            glTexImage2DPointerField = ContextCapabilities.class.getDeclaredField("glTexImage2D");
            glTexImage2DPointerField.setAccessible(true);
            glTexSubImage2DPointerField = ContextCapabilities.class.getDeclaredField("glTexSubImage2D");
            glTexSubImage2DPointerField.setAccessible(true);
            return true;
        } catch (Throwable t) {
            nglTexImage2DMethod = null;
            nglTexSubImage2DMethod = null;
            glTexImage2DPointerField = null;
            glTexSubImage2DPointerField = null;
            return false;
        }
    }

    private static void nglTexImage2D(int target, int level, int internalformat, int width, int height, int border,
        int format, int type) {
        try {
            long fn = glTexImage2DPointerField.getLong(GLContext.getCapabilities());
            nglTexImage2DMethod
                .invoke(null, target, level, internalformat, width, height, border, format, type, 0L, fn);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke nglTexImage2D via reflection", t);
        }
    }

    private static void nglTexSubImage2D(int target, int level, int xoffset, int yoffset, int width, int height,
        int format, int type) {
        try {
            long fn = glTexSubImage2DPointerField.getLong(GLContext.getCapabilities());
            nglTexSubImage2DMethod.invoke(null, target, level, xoffset, yoffset, width, height, format, type, 0L, fn);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to invoke nglTexSubImage2D via reflection", t);
        }
    }

    private int getOrCreatePbo() {
        int idx = pboToggle & 1;
        if (pixelUnpackPboIds[idx] == 0) {
            pixelUnpackPboIds[idx] = GL15.glGenBuffers();
            if (pixelUnpackPboIds[idx] == 0) {
                return 0;
            }
        }
        pboToggle++;
        return pixelUnpackPboIds[idx];
    }

    private boolean uploadSubImageWithPbo(ByteBuffer buffer, int x, int y, int width, int height) {
        int size = buffer.remaining();
        if (size <= 0) {
            return false;
        }
        if (!ensureNativeTexMethodsResolved()) {
            return false;
        }
        int pboId = getOrCreatePbo();
        if (pboId == 0) {
            return false;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pboId);
        try {
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size, GL15.GL_STREAM_DRAW);
            ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, GL15.GL_WRITE_ONLY, size, null);
            if (mapped == null) {
                return false;
            }
            mapped.put(buffer);
            GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
            nglTexSubImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                x,
                y,
                width,
                height,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV);
            return true;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }
    }

    private boolean uploadImageWithPbo(ByteBuffer buffer, int width, int height) {
        int size = buffer.remaining();
        if (size <= 0) {
            return false;
        }
        if (!ensureNativeTexMethodsResolved()) {
            return false;
        }
        int pboId = getOrCreatePbo();
        if (pboId == 0) {
            return false;
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pboId);
        try {
            GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, size, GL15.GL_STREAM_DRAW);
            ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, GL15.GL_WRITE_ONLY, size, null);
            if (mapped == null) {
                return false;
            }
            mapped.put(buffer);
            GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);
            nglTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                GL11.GL_RGBA,
                width,
                height,
                0,
                GL12.GL_BGRA,
                GL12.GL_UNSIGNED_INT_8_8_8_8_REV);
            return true;
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        }
    }

    private void uploadWithFallback(ByteBuffer buffer, int destinationX, int destinationY, int copyWidth,
        int copyHeight) {
        if (glTextureId == 0 || buffer == null) return;
        int requiredBytes = copyWidth * copyHeight * 4;
        if (requiredBytes <= 0 || buffer.capacity() < requiredBytes) return;
        ByteBuffer uploadBuffer = convertBgraToRgba(buffer, requiredBytes);
        if (uploadBuffer == null) return;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, copyWidth);
        GL11.glTexSubImage2D(
            GL11.GL_TEXTURE_2D,
            0,
            destinationX,
            destinationY,
            copyWidth,
            copyHeight,
            GL11.GL_RGBA,
            GL11.GL_UNSIGNED_BYTE,
            uploadBuffer.slice());
        resetGlPixelStoreState();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    private static void resetGlPixelStoreState() {
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
    }

    private ByteBuffer convertBgraToRgba(ByteBuffer sourceBuffer, int requiredBytes) {
        if (requiredBytes <= 0 || (requiredBytes & 3) != 0) return null;
        if (fallbackRgbaUploadBuffer == null || fallbackRgbaUploadBuffer.capacity() < requiredBytes) {
            fallbackRgbaUploadBuffer = ByteBuffer.allocateDirect(requiredBytes);
        }
        ByteBuffer src = sourceBuffer.duplicate();
        src.position(0);
        src.limit(requiredBytes);
        ByteBuffer dst = fallbackRgbaUploadBuffer.duplicate();
        dst.clear();
        dst.limit(requiredBytes);
        for (int i = 0; i < requiredBytes; i += 4) {
            byte b = src.get(i);
            byte g = src.get(i + 1);
            byte r = src.get(i + 2);
            byte a = src.get(i + 3);
            dst.put(i, r);
            dst.put(i + 1, g);
            dst.put(i + 2, b);
            dst.put(i + 3, a);
        }
        dst.position(0);
        return dst;
    }
}
