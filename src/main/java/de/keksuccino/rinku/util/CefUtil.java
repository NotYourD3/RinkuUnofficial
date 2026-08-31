package de.keksuccino.rinku.util;

import de.keksuccino.rinku.OSPlatform;
import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.RinkuSettings;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * This class mostly just interacts with org.cef.* for internal use in {@link Rinku}.
 */
public final class CefUtil {

    private static final Logger LOGGER = LogManager.getLogger("CefUtil");

    private static boolean init;
    private static CefApp cefAppInstance;
    private static CefClient cefClientInstance;
    private static volatile String effectiveDesktopUserAgent;

    private CefUtil() {}

    public static void addUnixExecutePermissions(Path file) throws IOException {
        PosixFileAttributeView posixView = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posixView == null) {
            addPortableExecutePermissions(file);
            return;
        }

        Set<PosixFilePermission> existing = posixView.readAttributes().permissions();
        Set<PosixFilePermission> updated = EnumSet.noneOf(PosixFilePermission.class);
        updated.addAll(existing);
        updated.add(PosixFilePermission.OWNER_EXECUTE);
        if (existing.contains(PosixFilePermission.GROUP_READ)) {
            updated.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if (existing.contains(PosixFilePermission.OTHERS_READ)) {
            updated.add(PosixFilePermission.OTHERS_EXECUTE);
        }
        posixView.setPermissions(updated);
    }

    public static void addPortableExecutePermissions(Path file) throws IOException {
        boolean changed;
        try {
            changed = file.toFile().setExecutable(true, false);
        } catch (SecurityException | UnsupportedOperationException failure) {
            throw new IOException("Could not set executable permissions on " + file, failure);
        }
        if (!Files.isExecutable(file)) {
            throw new IOException("Could not set executable permissions on " + file + "; File.setExecutable returned " + changed);
        }
    }

    public static List<Path> unixExecutablePaths(Path installation, OSPlatform platform) {
        if (platform.isLinux()) {
            return Arrays.asList(installation.resolve("jcef_helper"), installation.resolve("chrome-sandbox"));
        }
        if (!platform.isMacOS()) {
            return Collections.emptyList();
        }
        Path contents = installation.resolve("jcef_app.app/Contents");
        Path frameworks = contents.resolve("Frameworks");
        return Arrays.asList(contents.resolve("MacOS/JavaAppLauncher"), frameworks.resolve("Chromium Embedded Framework.framework/Chromium Embedded Framework"), frameworks.resolve("jcef Helper.app/Contents/MacOS/jcef Helper"), frameworks.resolve("jcef Helper (Alerts).app/Contents/MacOS/jcef Helper (Alerts)"), frameworks.resolve("jcef Helper (GPU).app/Contents/MacOS/jcef Helper (GPU)"), frameworks.resolve("jcef Helper (Plugin).app/Contents/MacOS/jcef Helper (Plugin)"), frameworks.resolve("jcef Helper (Renderer).app/Contents/MacOS/jcef Helper (Renderer)"));
    }

    private static void ensureUnixExecutables(Path installation, OSPlatform platform) {
        for (Path file : unixExecutablePaths(installation, platform)) {
            try {
                addUnixExecutePermissions(file);
            } catch (IOException e) {
                LOGGER.error("Failed to set " + file + " as executable.", e);
            }
        }
    }

    public static boolean init() {
        OSPlatform platform = OSPlatform.getPlatform();
        String configuredJcefPath = System.getProperty("jcef.path");
        if (configuredJcefPath == null || configuredJcefPath.trim().isEmpty()) {
            LOGGER.error("JCEF installation path is unavailable; the downloader must finish before CEF initialization.");
            return false;
        }
        Path jcefInstallation = Paths.get(configuredJcefPath);

        // Archive modes are canonicalized during extraction. This remains a non-destructive fallback
        // for installations copied by tools that discarded executable bits.
        ensureUnixExecutables(jcefInstallation, platform);

        RinkuSettings settings = Rinku.getSettings();
        ArrayList<String> cefSwitchesList = new ArrayList<>();
        cefSwitchesList.add("--autoplay-policy=no-user-gesture-required");
        cefSwitchesList.add("--disable-features=ImmersiveReadAnything");
        cefSwitchesList.add("--disable-mobile-emulation");
        cefSwitchesList.add("--use-mobile-user-agent=false");
        cefSwitchesList.add("--device-scale-factor=1");
        cefSwitchesList.add("--force-device-scale-factor=1");
        if (settings.isDisableWebSecurity()) {
            cefSwitchesList.add("--disable-web-security");
        }
        if (settings.isEnableWidevineCdm()) {
            cefSwitchesList.add("--enable-widevine-cdm");
        }
        String[] cefSwitches = cefSwitchesList.toArray(new String[0]);

        if (!CefApp.startup(cefSwitches)) {
            return false;
        }

        CefSettings cefSettings = new CefSettings();
        cefSettings.windowless_rendering_enabled = true;
        if (settings.isUsingCache()) {
            Path cachePath = resolvePersistentCefCachePath().toAbsolutePath();
            try {
                Files.createDirectories(cachePath);
                // jcef wants an absolute path, so make sure it's absolute.
                cefSettings.cache_path = cachePath.toString();
                cefSettings.persist_session_cookies = true;
                LOGGER.info("Using persistent Rinku browser data directory: {}", cachePath);
            } catch (IOException e) {
                LOGGER.warn("Failed to create persistent Rinku cache directory {}. Falling back to non-persistent browser data.", cachePath, e);
            }
        }
        cefSettings.log_severity = settings.getNativeCefLogSeverity();
        cefSettings.background_color = cefSettings.new ColorType(0, 255, 255, 255);
        // Set the user agent if there's one defined in RinkuSettings
        if (settings.getUserAgent() != null) {
            cefSettings.user_agent = settings.getUserAgent();
            effectiveDesktopUserAgent = settings.getUserAgent();
        } else {
            // Use an explicit desktop Chrome user agent to prevent sites from serving mobile layouts.
            // We keep the "Rinku/2" product token appended for compatibility with the previous workaround.
            String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            String osPart;
            if (osName.contains("win")) {
                osPart = "Windows NT 10.0; Win64; x64";
            } else if (osName.contains("mac")) {
                osPart = "Macintosh; Intel Mac OS X 10_15_7";
            } else {
                osPart = "X11; Linux x86_64";
            }
            // A stable, well-known desktop Chrome version string - exact minor version isn't critical for
            // convincing responsive sites to serve the desktop layout.
            effectiveDesktopUserAgent = "Mozilla/5.0 (" + osPart + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Rinku/2";
            cefSettings.user_agent = effectiveDesktopUserAgent;
        }

        cefAppInstance = CefApp.getInstance(cefSwitches, cefSettings);
        cefClientInstance = cefAppInstance.createClient();

        return init = true;
    }

    public static void shutdown() {
        if (isInit()) {
            init = false;
            cefClientInstance.dispose();
            cefAppInstance.dispose();
        }
    }

    public static boolean isInit() {
        return init;
    }

    public static CefApp getCefApp() {
        return cefAppInstance;
    }

    public static CefClient getCefClient() {
        return cefClientInstance;
    }

    public static String getEffectiveDesktopUserAgent() {
        return effectiveDesktopUserAgent;
    }

    private static Path resolvePersistentCefCachePath() {
        return resolvePersistentDataRoot().resolve("cef-cache");
    }

    private static Path resolvePersistentDataRoot() {
        OSPlatform platform = OSPlatform.getPlatform();
        String userHome = System.getProperty("user.home", ".");

        if (platform.isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.trim().isEmpty()) {
                return Paths.get(localAppData).resolve("Rinku");
            }

            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.trim().isEmpty()) {
                return Paths.get(appData).resolve("Rinku");
            }

            return Paths.get(userHome, "AppData", "Local", "Rinku");
        }

        if (platform.isMacOS()) {
            return Paths.get(userHome, "Library", "Application Support", "Rinku");
        }

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome != null && !xdgDataHome.trim().isEmpty()) {
            return Paths.get(xdgDataHome).resolve("rinku");
        }

        return Paths.get(userHome, ".local", "share", "rinku");
    }

}
