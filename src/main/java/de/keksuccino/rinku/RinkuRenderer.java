package de.keksuccino.rinku;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.ByteBuffer;
import java.util.UUID;

@SideOnly(Side.CLIENT)
public class RinkuRenderer {
    private final boolean transparent;
    private int glTextureId = 0;
    private int textureWidth = 0;
    private int textureHeight = 0;

    private final ResourceLocation textureIdentifier;
    private RinkuDirectTexture directTexture;
    private boolean textureRegistered = false;
    private ByteBuffer fallbackRgbaUploadBuffer;

    protected RinkuRenderer(boolean transparent) {
        this.transparent = transparent;
        String uniqueId = UUID.randomUUID().toString().toLowerCase().replace("-", "");
        this.textureIdentifier = new ResourceLocation("rinku", "browser_" + uniqueId);
    }

    public void initialize() {
        directTexture = new RinkuDirectTexture();
        Minecraft.getMinecraft().getTextureManager().loadTexture(textureIdentifier, directTexture);
        textureRegistered = true;
        syncDirectTextureViewIfNeeded();
    }

    public int getTextureIdLegacy() {
        return glTextureId;
    }

    public ResourceLocation getTextureIdentifier() {
        return textureIdentifier;
    }

    public boolean isTextureReady() {
        return glTextureId != 0 && textureRegistered && directTexture != null && directTexture.isTextureViewReady();
    }

    public int getTextureID() {
        return glTextureId;
    }

    public boolean supportsDirtyRectUpload() {
        return glTextureId != 0;
    }

    public int getTextureWidth() {
        return textureWidth;
    }

    public int getTextureHeight() {
        return textureHeight;
    }

    public boolean isTransparent() {
        return transparent;
    }

    protected void cleanup() {
        if (glTextureId != 0) {
            GL11.glDeleteTextures(glTextureId);
            glTextureId = 0;
        }
        fallbackRgbaUploadBuffer = null;

        if (textureRegistered && textureIdentifier != null && Minecraft.getMinecraft() != null && Minecraft.getMinecraft().getTextureManager() != null) {
            try {
                Minecraft.getMinecraft().getTextureManager().deleteTexture(textureIdentifier);
            } catch (Exception ignored) {}
            textureRegistered = false;
        }
    }

    protected void onPaint(ByteBuffer buffer, int width, int height) {
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

        syncDirectTextureViewIfNeeded();

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, width);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, width, height, 0,
                GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
        resetGlPixelStoreState();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
    }

    protected void onPaint(ByteBuffer buffer, int x, int y, int width, int height) {
        syncDirectTextureViewIfNeeded();
        if (glTextureId != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, width, height, GL12.GL_BGRA,
                    GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            resetGlPixelStoreState();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            return;
        }
        uploadWithFallback(buffer, x, y, width, height);
    }

    private void syncDirectTextureViewIfNeeded() {
        if (!textureRegistered || directTexture == null || glTextureId == 0) {
            return;
        }
        boolean needsRebind = !directTexture.isTextureViewReady()
                || directTexture.getWidth() != textureWidth
                || directTexture.getHeight() != textureHeight
                || directTexture.getTextureGlId() != glTextureId;
        if (needsRebind) {
            directTexture.bindTexture(glTextureId, textureWidth, textureHeight);
        }
    }

    private void uploadWithFallback(ByteBuffer buffer, int destinationX, int destinationY, int copyWidth, int copyHeight) {
        if (glTextureId == 0 || buffer == null) return;
        int requiredBytes = copyWidth * copyHeight * 4;
        if (requiredBytes <= 0 || buffer.capacity() < requiredBytes) return;
        ByteBuffer uploadBuffer = convertBgraToRgba(buffer, requiredBytes);
        if (uploadBuffer == null) return;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glTextureId);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, copyWidth);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, destinationX, destinationY, copyWidth, copyHeight,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, uploadBuffer.slice());
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
