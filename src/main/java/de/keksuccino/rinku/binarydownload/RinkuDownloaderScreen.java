package de.keksuccino.rinku.binarydownload;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;

@SideOnly(Side.CLIENT)
public class RinkuDownloaderScreen extends GuiScreen {

    private final GuiScreen parent;
    private final IChatComponent title;

    public RinkuDownloaderScreen(@Nullable GuiScreen parent) {
        this.parent = parent;
        IChatComponent titleComp = new ChatComponentTranslation("rinku.downloader.title");
        this.title = titleComp;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        this.drawDefaultBackground();

        int cx = this.width / 2;
        int cy = this.height / 2;
        int progressBarHeight = 14;
        int progressBarWidth = this.width / 3;

        int barX = cx - progressBarWidth / 2;
        int barY = cy - progressBarHeight / 2;

        drawRect(barX, barY, barX + progressBarWidth, barY + progressBarHeight, 0xFFFFFFFF);
        drawRect(barX + 2, barY + 2, barX + progressBarWidth - 2, barY + progressBarHeight - 2, 0xFF000000);
        int fillWidth = (int) ((progressBarWidth - 4) * RinkuDownloadListener.INSTANCE.getProgress());
        drawRect(barX + 4, barY + 4, barX + 4 + fillWidth, barY + progressBarHeight - 4, 0xFFFFFFFF);

        IChatComponent[] text = new IChatComponent[] {
                RinkuDownloadListener.INSTANCE.getTask(),
                new ChatComponentTranslation("rinku.downloader.progress", Math.round(RinkuDownloadListener.INSTANCE.getProgress() * 100)),
        };

        int lineHeight = this.fontRendererObj.FONT_HEIGHT;
        int oSet = ((lineHeight / 2) + ((lineHeight + 2) * (text.length + 2))) + 4;
        int textY = cy - oSet;

        String titleStr = EnumChatFormatting.GOLD + this.title.getFormattedText();
        this.drawCenteredString(this.fontRendererObj, titleStr, cx, textY, 0xFFFFFF);
        textY += lineHeight + 2;

        int index = 0;
        for (IChatComponent s : text) {
            if (index == 1) {
                textY += lineHeight + 2;
            }
            textY += lineHeight + 2;
            String line = (s != null) ? s.getFormattedText() : "";
            this.drawCenteredString(this.fontRendererObj, line, cx, textY, 0xFFFFFF);
            index++;
        }
    }

    @Override
    public void updateScreen() {
        if (RinkuDownloadListener.INSTANCE.isDone() || RinkuDownloadListener.INSTANCE.isFailed()) {
            Minecraft.getMinecraft().displayGuiScreen(this.parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }

}
