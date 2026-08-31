package de.keksuccino.rinku.example;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuBrowser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

@SideOnly(Side.CLIENT)
public class ExampleScreen extends GuiScreen {

    private static final int FRAME_MARGIN = 20;
    private static final int NAV_BAR_HEIGHT = 20;
    private static final int NAV_BAR_GAP = 6;
    private static final int NAV_BUTTON_WIDTH = 24;
    private static final int NAV_SPACING = 4;
    private static final int LOADING_BAR_HEIGHT = 2;
    private static final int LOADING_BAR_TRACK_COLOR = 0x55000000;
    private static final int LOADING_BAR_FILL_COLOR = 0xFF3BA8FF;
    private static final String DEFAULT_URL = "https://www.google.com";

    private static final int BUTTON_ID_BACK = 0;
    private static final int BUTTON_ID_FORWARD = 1;
    private static final int BUTTON_ID_RELOAD = 2;

    private final IChatComponent title;
    private RinkuBrowser browser;
    private GuiTextField urlBox;
    private GuiButton backButton;
    private GuiButton forwardButton;
    private GuiButton reloadButton;
    private CefDisplayHandler addressBarDisplayHandler;

    public ExampleScreen(IChatComponent component) {
        this.title = component;
    }

    @Override
    public void initGui() {
        super.initGui();
        if (browser == null) {
            boolean transparent = true;
            browser = Rinku.createBrowser(DEFAULT_URL, transparent);
        }
        registerAddressBarDisplayHandler();
        initNavigationWidgets();
        resizeBrowser();
        refreshNavigationState();
    }

    private void registerAddressBarDisplayHandler() {
        if (addressBarDisplayHandler != null) {
            return;
        }

        addressBarDisplayHandler = new CefDisplayHandlerAdapter() {
            @Override
            public void onAddressChange(CefBrowser cefBrowser, CefFrame frame, String url) {
                if (browser == null || cefBrowser == null || frame == null || !frame.isMain()) {
                    return;
                }
                if (cefBrowser.getIdentifier() != browser.getIdentifier()) {
                    return;
                }

                Minecraft.getMinecraft().func_152344_a(() -> {
                    if (Minecraft.getMinecraft().currentScreen != ExampleScreen.this || urlBox == null || url == null) {
                        return;
                    }
                    if (!url.equals(urlBox.getText())) {
                        urlBox.setText(url);
                    }
                });
            }
        };
        Rinku.getClient().addDisplayHandler(addressBarDisplayHandler);
    }

    private void initNavigationWidgets() {
        int navX = FRAME_MARGIN;
        int navY = FRAME_MARGIN;

        this.buttonList.clear();

        backButton = new GuiButton(BUTTON_ID_BACK, navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT, "<");
        this.buttonList.add(backButton);
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;

        forwardButton = new GuiButton(BUTTON_ID_FORWARD, navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT, ">");
        this.buttonList.add(forwardButton);
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;

        reloadButton = new GuiButton(BUTTON_ID_RELOAD, navX, navY, NAV_BUTTON_WIDTH, NAV_BAR_HEIGHT, "R");
        this.buttonList.add(reloadButton);
        navX += NAV_BUTTON_WIDTH + NAV_SPACING;

        int urlWidth = Math.max(60, this.width - FRAME_MARGIN - navX);
        urlBox = new GuiTextField(this.fontRendererObj, navX, navY, urlWidth, NAV_BAR_HEIGHT);
        urlBox.setMaxStringLength(2048);
        String currentUrl = browser.getURL();
        urlBox.setText(currentUrl == null || currentUrl.isEmpty() ? DEFAULT_URL : currentUrl);
    }

    private int getBrowserX() {
        return FRAME_MARGIN;
    }

    private int getBrowserY() {
        return FRAME_MARGIN + NAV_BAR_HEIGHT + NAV_BAR_GAP;
    }

    private int getBrowserWidth() {
        return Math.max(1, this.width - FRAME_MARGIN * 2);
    }

    private int getBrowserHeight() {
        return Math.max(1, this.height - getBrowserY() - FRAME_MARGIN);
    }

    private boolean isInBrowserBounds(double x, double y) {
        int browserX = getBrowserX();
        int browserY = getBrowserY();
        return x >= browserX && y >= browserY && x < (browserX + getBrowserWidth()) && y < (browserY + getBrowserHeight());
    }

    private int mouseX(double x) {
        return (int) ((x - getBrowserX()) * this.mc.gameSettings.guiScale);
    }

    private int mouseY(double y) {
        return (int) ((y - getBrowserY()) * this.mc.gameSettings.guiScale);
    }

    private void resizeBrowser() {
        if (browser != null) {
            int scale = this.mc.gameSettings.guiScale;
            if (scale <= 0) scale = 1;
            browser.resize((int) (getBrowserWidth() * scale), (int) (getBrowserHeight() * scale));
        }
    }

    @Override
    public void setWorldAndResolution(Minecraft mc, int width, int height) {
        super.setWorldAndResolution(mc, width, height);
        resizeBrowser();
    }

    @Override
    public void onGuiClosed() {
        if (addressBarDisplayHandler != null && Rinku.isInitialized()) {
            Rinku.getClient().removeDisplayHandler(addressBarDisplayHandler);
        }
        addressBarDisplayHandler = null;
        if (browser != null) {
            browser.close();
        }
        super.onGuiClosed();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (urlBox != null) {
            urlBox.updateCursorCounter();
        }
        refreshNavigationState();
    }

    private void refreshNavigationState() {
        if (browser == null) {
            return;
        }

        if (backButton != null) {
            backButton.enabled = browser.canGoBack();
        }
        if (forwardButton != null) {
            forwardButton.enabled = browser.canGoForward();
        }
        if (reloadButton != null) {
            reloadButton.enabled = true;
        }

        if (urlBox != null && !urlBox.isFocused()) {
            String currentUrl = browser.getURL();
            if (currentUrl != null && !currentUrl.isEmpty() && !currentUrl.equals(urlBox.getText())) {
                urlBox.setText(currentUrl);
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null || browser == null) return;
        if (button.id == BUTTON_ID_BACK) {
            browser.goBack();
        } else if (button.id == BUTTON_ID_FORWARD) {
            browser.goForward();
        } else if (button.id == BUTTON_ID_RELOAD) {
            browser.reload();
        }
    }

    private void navigateFromUrlField() {
        if (urlBox == null) {
            return;
        }

        String input = urlBox.getText();
        if (input == null) {
            return;
        }
        input = input.trim();
        if (input.isEmpty()) {
            return;
        }

        String normalizedUrl = normalizeUrl(input);
        urlBox.setText(normalizedUrl);
        browser.loadURL(normalizedUrl);
        browser.setFocus(true);
    }

    private String normalizeUrl(String input) {
        if (input.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
            return input;
        }
        return "https://" + input;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        super.drawScreen(mouseX, mouseY, partial);
        renderLoadingIndicator();

        if (urlBox != null) {
            urlBox.drawTextBox();
        }

        if (browser != null && browser.isTextureReady()) {
            renderBrowserTexture();
        }
    }

    private void renderBrowserTexture() {
        ResourceLocation textureLocation = browser.getTextureIdentifier();
        if (textureLocation == null) {
            return;
        }

        int frameRenderWidth = getBrowserWidth();
        int frameRenderHeight = getBrowserHeight();
        int x = getBrowserX();
        int y = getBrowserY();

        this.mc.getTextureManager().bindTexture(textureLocation);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        func_146110_a(x, y, 0.0F, 0.0F, frameRenderWidth, frameRenderHeight, frameRenderWidth, frameRenderHeight);
        GL11.glDisable(GL11.GL_BLEND);
    }

    private void renderLoadingIndicator() {
        if (browser == null || urlBox == null || !browser.isLoading()) {
            return;
        }

        int barX = urlBox.xPosition;
        int barY = urlBox.yPosition + urlBox.height + 1;
        int barWidth = urlBox.width;
        int barBottom = barY + LOADING_BAR_HEIGHT;
        drawRect(barX, barY, barX + barWidth, barBottom, LOADING_BAR_TRACK_COLOR);

        int segmentWidth = Math.max(20, barWidth / 4);
        int travelRange = barWidth + segmentWidth;
        int animatedOffset = (int) ((System.currentTimeMillis() / 6L) % travelRange) - segmentWidth;
        int segmentStart = Math.max(barX, barX + animatedOffset);
        int segmentEnd = Math.min(barX + barWidth, barX + animatedOffset + segmentWidth);
        if (segmentEnd > segmentStart) {
            drawRect(segmentStart, barY, segmentEnd, barBottom, LOADING_BAR_FILL_COLOR);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        try {
            super.mouseClicked(mouseX, mouseY, mouseButton);
        } catch (Exception ignored) {
        }

        if (urlBox != null) {
            urlBox.mouseClicked(mouseX, mouseY, mouseButton);
            if (urlBox.isFocused()) return;
        }

        if (!isInBrowserBounds(mouseX, mouseY)) {
            return;
        }

        browser.sendMousePress(mouseX(mouseX), mouseY(mouseY), mouseButton);
        browser.setFocus(true);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);

        if (state == -1) return;

        if (isInBrowserBounds(mouseX, mouseY)) {
            browser.sendMouseRelease(mouseX(mouseX), mouseY(mouseY), state);
            browser.setFocus(true);
        } else if (urlBox != null) {
            urlBox.setFocused(false);
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int dw = org.lwjgl.input.Mouse.getEventDWheel();
        if (dw != 0) {
            int i = org.lwjgl.input.Mouse.getEventX() * this.width / this.mc.displayWidth;
            int j = this.height - org.lwjgl.input.Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
            if (isInBrowserBounds(i, j)) {
                browser.sendMouseWheel(mouseX(i), mouseY(j), dw > 0 ? 1 : -1, 0);
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (urlBox != null && urlBox.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                navigateFromUrlField();
                urlBox.setFocused(false);
                if (browser != null) browser.setFocus(true);
                return;
            }
            urlBox.textboxKeyTyped(typedChar, keyCode);
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(null);
            return;
        }

        if (typedChar == (char) 0 && keyCode == 0) return;

        if (browser != null) {
            if (Keyboard.getEventKeyState()) {
                browser.sendKeyPress(keyCode, 0, 0);
            }
            browser.sendKeyTyped(typedChar, 0);
            browser.setFocus(true);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

}
