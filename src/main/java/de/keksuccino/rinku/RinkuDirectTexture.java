package de.keksuccino.rinku;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.resources.IResourceManager;
import org.lwjgl.opengl.GL11;

import java.io.IOException;

@SideOnly(Side.CLIENT)
public class RinkuDirectTexture extends AbstractTexture {

    private int width;
    private int height;
    private int glTextureId;
    private boolean ready;

    public RinkuDirectTexture() {
    }

    public void bindTexture(int textureSourceGlId, int width, int height) {
        this.glTextureId = textureSourceGlId;
        this.width = width;
        this.height = height;
        this.ready = (textureSourceGlId != 0) && (width > 0) && (height > 0);
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getTextureGlId() {
        return this.glTextureId;
    }

    public boolean isTextureViewReady() {
        return this.ready;
    }

    @Override
    public void loadTexture(IResourceManager p_110551_1_) throws IOException {
    }

    @Override
    public int getGlTextureId() {
        return this.glTextureId;
    }

    public void deleteGlTexture() {
        if (this.glTextureId != 0) {
            GL11.glDeleteTextures(this.glTextureId);
            this.glTextureId = 0;
            this.ready = false;
        }
    }

}
