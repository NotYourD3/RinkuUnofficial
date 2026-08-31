package de.keksuccino.rinku;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import de.keksuccino.rinku.binarydownload.RinkuDownloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import org.cef.CefSettings;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@SideOnly(Side.CLIENT)
public class OptionsScreen extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_ROW_MAX_WIDTH = 360;
    private static final int EDITABLE_LABEL_WIDTH = 155;
    private static final int EDITABLE_ROW_GAP = 5;
    private static final int OPTION_ROW_ADVANCE = 26;
    private static final int OPTION_SECTION_PADDING_TOP = 10;
    private static final int CYCLE_VALUE_COLOR = 0xFFAA00;
    private static final int INVALID_TEXT_COLOR = 0xFFFF5555;
    private static final int DEFAULT_TEXT_COLOR = 0xE0E0E0;

    private static final int BROWSER_TAB_INDEX = 0;
    private static final int DOWNLOADS_TAB_INDEX = 1;
    private static final int ADVANCED_TAB_INDEX = 2;

    private static final int ID_TAB_BROWSER = 1000;
    private static final int ID_TAB_DOWNLOADS = 1001;
    private static final int ID_TAB_ADVANCED = 1002;
    private static final int ID_DONE = 1003;
    private static final int ID_OPTION_START = 2000;

    @Nullable
    private final GuiScreen parent;
    private final RinkuSettings settings;
    private final Map<String, String> pendingTextValues = new HashMap<>();
    private final List<TextOptionControl<?>> textOptionControls = new ArrayList<>();

    private int currentTabIndex = BROWSER_TAB_INDEX;
    private GuiButton tabBrowserButton;
    private GuiButton tabDownloadsButton;
    private GuiButton tabAdvancedButton;
    private GuiButton doneButton;
    private int optionIdCounter;

    private final List<RunnableOptionButton> currentTabOptionButtons = new ArrayList<>();
    private final List<GuiTextField> currentTabTextFields = new ArrayList<>();
    private final List<RunnableLabel> currentTabLabels = new ArrayList<>();

    private GuiButton transparentPoolSizeButton;
    private GuiButton opaquePoolSizeButton;
    private GuiButton preloadEnabledButton;
    private GuiButton mirrorPolicyButton;

    public OptionsScreen(@Nullable GuiScreen parent) {
        this.parent = parent;
        this.settings = Rinku.getSettings();
        this.initializePendingTextValues();
    }

    private void initializePendingTextValues() {
        this.pendingTextValues.put("user-agent", this.settings.getUserAgent() == null ? "" : this.settings.getUserAgent());
        this.pendingTextValues.put("download-mirror", this.settings.getDownloadMirror() == null ? "" : this.settings.getDownloadMirror());
        this.pendingTextValues.put("download-connect-timeout-ms", Integer.toString(this.settings.getDownloadConnectTimeoutMs()));
        this.pendingTextValues.put("download-read-timeout-ms", Integer.toString(this.settings.getDownloadReadTimeoutMs()));
        this.pendingTextValues.put("download-max-archive-bytes", Long.toString(this.settings.getDownloadMaxArchiveBytes()));
        this.pendingTextValues.put("download-max-checksum-bytes", Long.toString(this.settings.getDownloadMaxChecksumBytes()));
        this.pendingTextValues.put("download-max-extracted-bytes", Long.toString(this.settings.getDownloadMaxExtractedBytes()));
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.currentTabOptionButtons.clear();
        this.currentTabTextFields.clear();
        this.currentTabLabels.clear();
        this.textOptionControls.clear();
        this.transparentPoolSizeButton = null;
        this.opaquePoolSizeButton = null;
        this.preloadEnabledButton = null;
        this.mirrorPolicyButton = null;
        this.optionIdCounter = ID_OPTION_START;

        int tabWidth = this.width / 3;
        int tabY = 20;
        tabBrowserButton = new GuiButton(ID_TAB_BROWSER, 0, tabY, tabWidth, BUTTON_HEIGHT, translate("rinku.options.tab.browser"));
        tabDownloadsButton = new GuiButton(ID_TAB_DOWNLOADS, tabWidth, tabY, tabWidth, BUTTON_HEIGHT, translate("rinku.options.tab.downloads"));
        tabAdvancedButton = new GuiButton(ID_TAB_ADVANCED, tabWidth * 2, tabY, this.width - tabWidth * 2, BUTTON_HEIGHT, translate("rinku.options.tab.advanced"));
        this.buttonList.add(tabBrowserButton);
        this.buttonList.add(tabDownloadsButton);
        this.buttonList.add(tabAdvancedButton);

        int doneW = 150;
        doneButton = new GuiButton(ID_DONE, (this.width - doneW) / 2, this.height - BUTTON_HEIGHT - 10, doneW, BUTTON_HEIGHT, translate("gui.done"));
        this.buttonList.add(doneButton);

        buildCurrentTab();
    }

    private void buildCurrentTab() {
        for (RunnableOptionButton rob : currentTabOptionButtons) {
            this.buttonList.remove(rob.button);
        }
        currentTabOptionButtons.clear();
        currentTabTextFields.clear();
        currentTabLabels.clear();
        for (GuiTextField tf : currentTabTextFields) {
        }
        currentTabTextFields.clear();
        currentTabLabels.clear();

        tabBrowserButton.enabled = (currentTabIndex != BROWSER_TAB_INDEX);
        tabDownloadsButton.enabled = (currentTabIndex != DOWNLOADS_TAB_INDEX);
        tabAdvancedButton.enabled = (currentTabIndex != ADVANCED_TAB_INDEX);

        if (currentTabIndex == BROWSER_TAB_INDEX) buildBrowserTab();
        else if (currentTabIndex == DOWNLOADS_TAB_INDEX) buildDownloadsTab();
        else buildAdvancedTab();
    }

    private void buildBrowserTab() {
        int rowY = 60 + OPTION_SECTION_PADDING_TOP;

        addTextOption(rowY, BROWSER_TAB_INDEX, new TextOption<>("user-agent", "rinku.options.user_agent", "rinku.options.user_agent.desc", 512, RinkuOptionsInput::parseUserAgent, this.settings::getUserAgent, this.settings::setUserAgent, translate("rinku.options.validation.user_agent")));
        rowY += OPTION_ROW_ADVANCE * 2;

        addFullWidthOption(rowY, this.buildBooleanButton("rinku.options.use_cache", "rinku.options.use_cache.desc", this.settings::isUsingCache, this.settings::setUseCache));
        rowY += OPTION_ROW_ADVANCE;

        addFullWidthOption(rowY, this.buildBooleanButton("rinku.options.enable_widevine", "rinku.options.enable_widevine.desc", this.settings::isEnableWidevineCdm, this.settings::setEnableWidevineCdm));
        rowY += OPTION_ROW_ADVANCE + OPTION_SECTION_PADDING_TOP;

        this.preloadEnabledButton = this.buildPreloadEnabledButton();
        addFullWidthOption(rowY, this.preloadEnabledButton);
        rowY += OPTION_ROW_ADVANCE;

        this.transparentPoolSizeButton = this.buildPoolSizeButton("rinku.options.preload_transparent_pool_size", this.settings::getBrowserPreloadTransparentPoolSize, this.settings::setBrowserPreloadTransparentPoolSize);
        addFullWidthOption(rowY, this.transparentPoolSizeButton);
        rowY += OPTION_ROW_ADVANCE;

        this.opaquePoolSizeButton = this.buildPoolSizeButton("rinku.options.preload_opaque_pool_size", this.settings::getBrowserPreloadOpaquePoolSize, this.settings::setBrowserPreloadOpaquePoolSize);
        addFullWidthOption(rowY, this.opaquePoolSizeButton);

        updatePreloadControls();
    }

    private void buildDownloadsTab() {
        int rowY = 60 + OPTION_SECTION_PADDING_TOP;

        addFullWidthOption(rowY, this.buildBooleanButton("rinku.options.skip_download", "rinku.options.skip_download.desc", this.settings::isSkipDownload, this.settings::setSkipDownload));
        rowY += OPTION_ROW_ADVANCE;

        this.mirrorPolicyButton = this.buildMirrorPolicyButton();
        addFullWidthOption(rowY, this.mirrorPolicyButton);
        rowY += OPTION_ROW_ADVANCE;

        addTextOption(rowY, DOWNLOADS_TAB_INDEX, new TextOption<>("download-mirror", "rinku.options.download_mirror", "rinku.options.download_mirror.desc", 2048, this::parseDownloadMirror, this.settings::getDownloadMirror, this.settings::setDownloadMirror, translate("rinku.options.validation.download_mirror")));
        rowY += OPTION_ROW_ADVANCE * 2;

        addFullWidthOption(rowY, this.buildBooleanButton("rinku.options.enforce_checksums", "rinku.options.enforce_checksums.desc", this.settings::isEnforceDownloadChecksums, this.settings::setEnforceDownloadChecksums));
        rowY += OPTION_ROW_ADVANCE;

        addTextOption(rowY, DOWNLOADS_TAB_INDEX, new TextOption<>("download-connect-timeout-ms", "rinku.options.connect_timeout", "rinku.options.connect_timeout.desc", 6, value -> RinkuOptionsInput.parseInt(value, RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS), this.settings::getDownloadConnectTimeoutMs, this.settings::setDownloadConnectTimeoutMs, translate("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS)));
        rowY += OPTION_ROW_ADVANCE * 2;

        addTextOption(rowY, DOWNLOADS_TAB_INDEX, new TextOption<>("download-read-timeout-ms", "rinku.options.read_timeout", "rinku.options.read_timeout.desc", 6, value -> RinkuOptionsInput.parseInt(value, RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS), this.settings::getDownloadReadTimeoutMs, this.settings::setDownloadReadTimeoutMs, translate("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_TIMEOUT_MS, RinkuSettings.MAX_DOWNLOAD_TIMEOUT_MS)));
        rowY += OPTION_ROW_ADVANCE * 2;

        addTextOption(rowY, DOWNLOADS_TAB_INDEX, new TextOption<>("download-max-archive-bytes", "rinku.options.max_archive_bytes", "rinku.options.max_archive_bytes.desc", 10, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_ARCHIVE_BYTES, RinkuSettings.MAX_DOWNLOAD_ARCHIVE_BYTES), this.settings::getDownloadMaxArchiveBytes, this.settings::setDownloadMaxArchiveBytes, translate("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_ARCHIVE_BYTES, RinkuSettings.MAX_DOWNLOAD_ARCHIVE_BYTES)));
        rowY += OPTION_ROW_ADVANCE * 2;

        addTextOption(rowY, DOWNLOADS_TAB_INDEX, new TextOption<>("download-max-checksum-bytes", "rinku.options.max_checksum_bytes", "rinku.options.max_checksum_bytes.desc", 7, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_CHECKSUM_BYTES, RinkuSettings.MAX_DOWNLOAD_CHECKSUM_BYTES), this.settings::getDownloadMaxChecksumBytes, this.settings::setDownloadMaxChecksumBytes, translate("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_CHECKSUM_BYTES, RinkuSettings.MAX_DOWNLOAD_CHECKSUM_BYTES)));
        rowY += OPTION_ROW_ADVANCE * 2;

        addTextOption(rowY, DOWNLOADS_TAB_INDEX, new TextOption<>("download-max-extracted-bytes", "rinku.options.max_extracted_bytes", "rinku.options.max_extracted_bytes.desc", 11, value -> RinkuOptionsInput.parseLong(value, RinkuSettings.MIN_DOWNLOAD_EXTRACTED_BYTES, RinkuSettings.MAX_DOWNLOAD_EXTRACTED_BYTES), this.settings::getDownloadMaxExtractedBytes, this.settings::setDownloadMaxExtractedBytes, translate("rinku.options.validation.integer_range", RinkuSettings.MIN_DOWNLOAD_EXTRACTED_BYTES, RinkuSettings.MAX_DOWNLOAD_EXTRACTED_BYTES)));
    }

    private void buildAdvancedTab() {
        int rowY = 60 + OPTION_SECTION_PADDING_TOP;

        addFullWidthOption(rowY, this.buildBooleanButton("rinku.options.disable_web_security", "rinku.options.disable_web_security.desc", this.settings::isDisableWebSecurity, this.settings::setDisableWebSecurity));
        rowY += OPTION_ROW_ADVANCE;

        addFullWidthOption(rowY, this.buildLogSeverityButton("rinku.options.native_log_severity", this.settings::getNativeCefLogSeverity, this.settings::setNativeCefLogSeverity));
        rowY += OPTION_ROW_ADVANCE;

        addFullWidthOption(rowY, this.buildLogSeverityButton("rinku.options.console_log_severity", this.settings::getConsoleLogForwardingMinSeverity, this.settings::setConsoleLogForwardingMinSeverity));
    }

    private GuiButton buildPreloadEnabledButton() {
        String msg = booleanOptionMessage("rinku.options.preload_enabled", this.settings.isBrowserPreloadEnabled());
        int id = optionIdCounter++;
        GuiButton button = new GuiButton(id, 0, 0, getButtonWidth(), BUTTON_HEIGHT, msg);
        RunnableOptionButton rob = new RunnableOptionButton(button, () -> {
            this.settings.setBrowserPreloadEnabled(!this.settings.isBrowserPreloadEnabled());
            button.displayString = booleanOptionMessage("rinku.options.preload_enabled", this.settings.isBrowserPreloadEnabled());
            updatePreloadControls();
        }, translate("rinku.options.preload_enabled.desc"));
        currentTabOptionButtons.add(rob);
        this.buttonList.add(button);
        return button;
    }

    private GuiButton buildMirrorPolicyButton() {
        String msg = mirrorPolicyMessage();
        int id = optionIdCounter++;
        GuiButton button = new GuiButton(id, 0, 0, getButtonWidth(), BUTTON_HEIGHT, msg);
        RunnableOptionButton rob = new RunnableOptionButton(button, () -> {
            this.settings.setDownloadMirrorPolicy(nextValue(this.settings.getDownloadMirrorPolicy(), RinkuDownloader.MirrorPolicy.values()));
            button.displayString = mirrorPolicyMessage();
        }, translate("rinku.options.download_mirror_policy.desc"));
        currentTabOptionButtons.add(rob);
        this.buttonList.add(button);
        return button;
    }

    private GuiButton buildLogSeverityButton(String labelKey, Supplier<CefSettings.LogSeverity> getter, Consumer<CefSettings.LogSeverity> setter) {
        String msg = logSeverityMessage(labelKey, getter.get());
        int id = optionIdCounter++;
        GuiButton button = new GuiButton(id, 0, 0, getButtonWidth(), BUTTON_HEIGHT, msg);
        RunnableOptionButton rob = new RunnableOptionButton(button, () -> {
            CefSettings.LogSeverity next = nextValue(getter.get(), CefSettings.LogSeverity.values());
            setter.accept(next);
            button.displayString = logSeverityMessage(labelKey, next);
        }, translate(labelKey + ".desc"));
        currentTabOptionButtons.add(rob);
        this.buttonList.add(button);
        return button;
    }

    private GuiButton buildPoolSizeButton(String labelKey, Supplier<Integer> getter, Consumer<Integer> setter) {
        String msg = integerOptionMessage(labelKey, getter.get());
        int id = optionIdCounter++;
        GuiButton button = new GuiButton(id, 0, 0, getButtonWidth(), BUTTON_HEIGHT, msg);
        RunnableOptionButton rob = new RunnableOptionButton(button, () -> {
            int next = getter.get() >= RinkuSettings.MAX_BROWSER_PRELOAD_POOL_SIZE ? RinkuSettings.MIN_BROWSER_PRELOAD_POOL_SIZE : getter.get() + 1;
            setter.accept(next);
            button.displayString = integerOptionMessage(labelKey, next);
        }, translate(labelKey + ".desc"));
        currentTabOptionButtons.add(rob);
        this.buttonList.add(button);
        return button;
    }

    private GuiButton buildBooleanButton(String labelKey, String descriptionKey, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        String msg = booleanOptionMessage(labelKey, getter.get());
        int id = optionIdCounter++;
        GuiButton button = new GuiButton(id, 0, 0, getButtonWidth(), BUTTON_HEIGHT, msg);
        RunnableOptionButton rob = new RunnableOptionButton(button, () -> {
            boolean next = !getter.get();
            setter.accept(next);
            button.displayString = booleanOptionMessage(labelKey, next);
        }, translate(descriptionKey));
        currentTabOptionButtons.add(rob);
        this.buttonList.add(button);
        return button;
    }

    private void addFullWidthOption(int rowY, GuiButton button) {
        int bw = getButtonWidth();
        button.xPosition = (this.width - bw) / 2;
        button.yPosition = rowY;
        button.width = bw;
    }

    private <T> void addTextOption(int rowY, int tabIndex, TextOption<T> option) {
        int rowWidth = getButtonWidth();
        int labelWidth = Math.min(EDITABLE_LABEL_WIDTH, Math.max(80, rowWidth / 2));
        int editWidth = Math.max(40, rowWidth - labelWidth - EDITABLE_ROW_GAP);
        int leftX = (this.width - rowWidth) / 2;

        String labelStr = translate(option.labelKey());
        RunnableLabel rl = new RunnableLabel(leftX, rowY, labelWidth, BUTTON_HEIGHT, labelStr, translate(option.descriptionKey()));
        currentTabLabels.add(rl);

        GuiTextField editBox = new GuiTextField(this.fontRendererObj, leftX + labelWidth + EDITABLE_ROW_GAP, rowY, editWidth, BUTTON_HEIGHT);
        String initialValue = this.pendingTextValues.getOrDefault(option.id(), "");
        editBox.setMaxStringLength(Math.max(option.maxLength(), initialValue.length()));
        editBox.setText(initialValue);
        currentTabTextFields.add(editBox);

        TextOptionControl<T> control = new TextOptionControl<>(tabIndex, option, editBox, translate(option.descriptionKey()), translateOptionInvalid(option));
        this.textOptionControls.add(control);
    }

    private String parseDownloadMirror(String value) {
        return RinkuOptionsInput.parseMirror(value, this.settings.getDownloadMirrorPolicy() != RinkuDownloader.MirrorPolicy.CONFIGURED_ONLY);
    }

    private void updatePreloadControls() {
        boolean active = this.settings.isBrowserPreloadEnabled();
        if (this.transparentPoolSizeButton != null) this.transparentPoolSizeButton.enabled = active;
        if (this.opaquePoolSizeButton != null) this.opaquePoolSizeButton.enabled = active;
    }

    private void syncPendingFromTextFields() {
        for (TextOptionControl<?> control : this.textOptionControls) {
            this.pendingTextValues.put(control.option.id(), control.editBox.getText());
        }
    }

    private void validateTextOptions() {
        for (TextOptionControl<?> control : this.textOptionControls) control.validate();
    }

    private boolean applyTextOptions() {
        syncPendingFromTextFields();
        TextOptionControl<?> firstInvalid = null;
        for (TextOptionControl<?> control : this.textOptionControls) {
            if (!control.validate() && firstInvalid == null) firstInvalid = control;
        }
        if (firstInvalid != null) {
            this.currentTabIndex = firstInvalid.tabIndex;
            initGui();
            return false;
        }
        for (TextOptionControl<?> control : this.textOptionControls) control.applyParsedValue();
        return true;
    }

    private String booleanOptionMessage(String labelKey, boolean enabled) {
        EnumChatFormatting valueColor = enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED;
        String valueName = translate(enabled ? "rinku.options.toggle.enabled" : "rinku.options.toggle.disabled");
        String value = valueColor + valueName + EnumChatFormatting.RESET;
        return translatedFormatted(labelKey, value);
    }

    private String integerOptionMessage(String labelKey, int value) {
        String valueStr = EnumChatFormatting.GOLD.toString() + value + EnumChatFormatting.RESET;
        return translatedFormatted(labelKey, valueStr);
    }

    private String mirrorPolicyMessage() {
        String valueKey = "rinku.options.download_mirror_policy." + this.settings.getDownloadMirrorPolicy().name().toLowerCase(java.util.Locale.ROOT);
        String valueName = translate(valueKey);
        String value = withColor(valueName, CYCLE_VALUE_COLOR);
        return translatedFormatted("rinku.options.download_mirror_policy", value);
    }

    private String logSeverityMessage(String labelKey, CefSettings.LogSeverity severity) {
        String severityName = severity.name().substring("LOGSEVERITY_".length()).toLowerCase(java.util.Locale.ROOT);
        String valueName = translate("rinku.options.log_severity." + severityName);
        String value = withColor(valueName, CYCLE_VALUE_COLOR);
        return translatedFormatted(labelKey, value);
    }

    private static String withColor(String s, int color) {
        return EnumChatFormatting.GOLD.toString() + s + EnumChatFormatting.RESET;
    }

    private int getButtonWidth() {
        return Math.min(BUTTON_ROW_MAX_WIDTH, this.width - 40);
    }

    private static <T> T nextValue(T current, T[] values) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == current) return values[(index + 1) % values.length];
        }
        return values[0];
    }

    private static String translate(String key, Object... args) {
        try {
            IChatComponent c = new ChatComponentTranslation(key, args);
            return c.getFormattedText();
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder(key);
            for (Object a : args) { sb.append(' ').append(String.valueOf(a)); }
            return sb.toString();
        }
    }

    private static String translatedFormatted(String key, Object... args) {
        return translate(key, args);
    }

    private static String translateOptionInvalid(TextOption<?> option) {
        try {
            if (option.invalidMessage instanceof IChatComponent) {
                return ((IChatComponent) option.invalidMessage).getFormattedText();
            }
            return String.valueOf(option.invalidMessage);
        } catch (Exception e) {
            return "Invalid value";
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (GuiTextField tf : currentTabTextFields) tf.updateCursorCounter();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == null) return;
        if (button.id == ID_DONE) {
            if (applyTextOptions()) {
                this.mc.displayGuiScreen(this.parent);
            }
            return;
        }
        if (button.id == ID_TAB_BROWSER) {
            syncPendingFromTextFields();
            validateTextOptions();
            currentTabIndex = BROWSER_TAB_INDEX;
            buildCurrentTab();
            return;
        }
        if (button.id == ID_TAB_DOWNLOADS) {
            syncPendingFromTextFields();
            validateTextOptions();
            currentTabIndex = DOWNLOADS_TAB_INDEX;
            buildCurrentTab();
            return;
        }
        if (button.id == ID_TAB_ADVANCED) {
            syncPendingFromTextFields();
            validateTextOptions();
            currentTabIndex = ADVANCED_TAB_INDEX;
            buildCurrentTab();
            return;
        }
        for (RunnableOptionButton rob : currentTabOptionButtons) {
            if (rob.button == button) {
                rob.onPress.run();
                return;
            }
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        try { super.mouseClicked(mouseX, mouseY, mouseButton); } catch (Exception ignored) {}
        for (GuiTextField tf : currentTabTextFields) tf.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        for (GuiTextField tf : currentTabTextFields) {
            if (tf.isFocused()) {
                if (tf.textboxKeyTyped(typedChar, keyCode)) {
                    syncPendingFromTextFields();
                    validateTextOptions();
                }
                return;
            }
        }
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) {
            if (applyTextOptions()) {
                this.mc.displayGuiScreen(this.parent);
            }
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTick) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRendererObj, translate("rinku.options"), this.width / 2, 6, 0xFFFFFF);
        drawRect(0, 46, this.width, 48, 0xFFAAAAAA);
        drawRect(0, this.height - BUTTON_HEIGHT - 18, this.width, this.height - BUTTON_HEIGHT - 16, 0xFFAAAAAA);

        for (GuiTextField tf : currentTabTextFields) tf.drawTextBox();
        for (RunnableLabel rl : currentTabLabels) rl.draw(this.fontRendererObj);

        try {
            super.drawScreen(mouseX, mouseY, partialTick);
        } catch (Exception ignored) {
            for (Object o : this.buttonList) {
                GuiButton b = (GuiButton) o;
                try { b.drawButton(this.mc, mouseX, mouseY); } catch (Exception ignored2) {}
            }
        }

        for (RunnableLabel rl : currentTabLabels) {
            if (rl.isHovered(mouseX, mouseY) && rl.tooltip != null && !rl.tooltip.isEmpty()) {
                this.func_146283_a(java.util.Collections.singletonList(rl.tooltip), mouseX, mouseY);
            }
        }
        for (RunnableOptionButton rob : currentTabOptionButtons) {
            if (rob.button.func_146115_a() && rob.tooltip != null && !rob.tooltip.isEmpty()) {
                this.func_146283_a(java.util.Collections.singletonList(rob.tooltip), mouseX, mouseY);
            }
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return true;
    }

    private static final class RunnableOptionButton {
        final GuiButton button;
        final Runnable onPress;
        final String tooltip;
        RunnableOptionButton(GuiButton button, Runnable onPress, String tooltip) {
            this.button = button;
            this.onPress = onPress;
            this.tooltip = tooltip;
        }
    }

    private static final class RunnableLabel {
        final int x, y, w, h;
        final String text;
        final String tooltip;
        RunnableLabel(int x, int y, int w, int h, String text, String tooltip) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.text = text; this.tooltip = tooltip;
        }
        boolean isHovered(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
        void draw(net.minecraft.client.gui.FontRenderer fr) {
            fr.drawStringWithShadow(truncate(fr, text, w), x, y + (h - fr.FONT_HEIGHT) / 2, 0xFFFFFF);
        }
        private static String truncate(net.minecraft.client.gui.FontRenderer fr, String s, int maxW) {
            if (fr.getStringWidth(s) <= maxW) return s;
            String ellipsis = "...";
            int w = fr.getStringWidth(ellipsis);
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (w + fr.getCharWidth(c) > maxW) break;
                sb.append(c);
                w += fr.getCharWidth(c);
            }
            return sb + ellipsis;
        }
    }

    private static final class TextOption<T> {
        private final String id;
        private final String labelKey;
        private final String descriptionKey;
        private final int maxLength;
        private final Function<String, T> parser;
        private final Supplier<T> currentValue;
        private final Consumer<T> applier;
        private final Object invalidMessage;
        TextOption(String id, String labelKey, String descriptionKey, int maxLength, Function<String, T> parser, Supplier<T> currentValue, Consumer<T> applier, Object invalidMessage) {
            this.id = id;
            this.labelKey = labelKey;
            this.descriptionKey = descriptionKey;
            this.maxLength = maxLength;
            this.parser = parser;
            this.currentValue = currentValue;
            this.applier = applier;
            this.invalidMessage = invalidMessage;
        }
        String id() { return id; }
        String labelKey() { return labelKey; }
        String descriptionKey() { return descriptionKey; }
        int maxLength() { return maxLength; }
        Function<String, T> parser() { return parser; }
        Supplier<T> currentValue() { return currentValue; }
        Consumer<T> applier() { return applier; }
    }

    private final class TextOptionControl<T> {
        private final int tabIndex;
        private final TextOption<T> option;
        private final GuiTextField editBox;
        private final String description;
        private final String invalidMessage;
        @Nullable
        private T parsedValue;

        private TextOptionControl(int tabIndex, TextOption<T> option, GuiTextField editBox, String description, String invalidMessage) {
            this.tabIndex = tabIndex;
            this.option = option;
            this.editBox = editBox;
            this.description = description;
            this.invalidMessage = invalidMessage;
            validate();
        }

        private boolean validate() {
            try {
                this.parsedValue = this.option.parser().apply(this.editBox.getText());
                this.editBox.setTextColor(DEFAULT_TEXT_COLOR);
                return true;
            } catch (IllegalArgumentException exception) {
                this.parsedValue = null;
                this.editBox.setTextColor(INVALID_TEXT_COLOR);
                return false;
            }
        }

        private void applyParsedValue() {
            if (!Objects.equals(this.parsedValue, this.option.currentValue().get())) {
                this.option.applier().accept(this.parsedValue);
            }
        }
    }

}
