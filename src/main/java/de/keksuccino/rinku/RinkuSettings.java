package de.keksuccino.rinku;

import de.keksuccino.rinku.binarydownload.RinkuDownloader;
import de.keksuccino.rinku.util.GameDirectoryUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

public class RinkuSettings {
    private static final Logger LOGGER = LogManager.getLogger("RinkuSettings");

    static final int MIN_DOWNLOAD_TIMEOUT_MS = 1_000;
    static final int MAX_DOWNLOAD_TIMEOUT_MS = 300_000;
    static final long MIN_DOWNLOAD_ARCHIVE_BYTES = 1_048_576L;
    static final long MAX_DOWNLOAD_ARCHIVE_BYTES = 5_000L * 1024L * 1024L;
    static final long MIN_DOWNLOAD_CHECKSUM_BYTES = 512L;
    static final long MAX_DOWNLOAD_CHECKSUM_BYTES = 1_048_576L;
    static final long MIN_DOWNLOAD_EXTRACTED_BYTES = 1_048_576L;
    static final long MAX_DOWNLOAD_EXTRACTED_BYTES = 10_000L * 1024L * 1024L;
    static final int MIN_BROWSER_PRELOAD_POOL_SIZE = 0;
    static final int MAX_BROWSER_PRELOAD_POOL_SIZE = 4;

    private static final String DEFAULT_DOWNLOAD_MIRROR = RinkuDownloader.OFFICIAL_MIRROR;
    private static final RinkuDownloader.MirrorPolicy DEFAULT_DOWNLOAD_MIRROR_POLICY = RinkuDownloader.MirrorPolicy.OFFICIAL_ONLY;
    private static final boolean DEFAULT_ENFORCE_DOWNLOAD_CHECKSUMS = true;
    private static final int DEFAULT_DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_DOWNLOAD_READ_TIMEOUT_MS = 60_000;
    private static final long DEFAULT_DOWNLOAD_MAX_ARCHIVE_BYTES = 750L * 1024L * 1024L;
    private static final long DEFAULT_DOWNLOAD_MAX_CHECKSUM_BYTES = 64L * 1024L;
    private static final long DEFAULT_DOWNLOAD_MAX_EXTRACTED_BYTES = 2_000L * 1024L * 1024L;
    private static final boolean DEFAULT_CEF_DISABLE_WEB_SECURITY = true;
    private static final boolean DEFAULT_CEF_ENABLE_WIDEVINE_CDM = true;
    // Keep these as plain String names and do NOT write:
    //     private static final CefSettings.LogSeverity X = CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
    // Referencing a static enum constant in <clinit> forces the JVM to resolve org.cef.CefSettings$LogSeverity
    // while RinkuSettings is still initializing, which happens BEFORE the JCEF downloader has a chance to
    // install the native binaries and produced NoClassDefFoundError: org/cef/CefSettings$LogSeverity.
    // Method signatures can still safely mention CefSettings.LogSeverity because the JVM does not eagerly
    // resolve return/parameter descriptor classes — resolution only happens when that specific method is
    // actually INVOKED, and callers of these getters/setters run well after the downloader finished.
    private static final String DEFAULT_NATIVE_CEF_LOG_SEVERITY_NAME = "LOGSEVERITY_DISABLE";
    private static final String DEFAULT_CONSOLE_LOG_FORWARDING_MIN_SEVERITY_NAME = "LOGSEVERITY_DISABLE";
    private static final boolean DEFAULT_BROWSER_PRELOAD_ENABLED = true;
    private static final int DEFAULT_BROWSER_PRELOAD_TRANSPARENT_POOL_SIZE = 1;
    private static final int DEFAULT_BROWSER_PRELOAD_OPAQUE_POOL_SIZE = 1;

    private boolean skipDownload;
    private String downloadMirror;
    private RinkuDownloader.MirrorPolicy downloadMirrorPolicy;
    private boolean enforceDownloadChecksums;
    private int downloadConnectTimeoutMs;
    private int downloadReadTimeoutMs;
    private long downloadMaxArchiveBytes;
    private long downloadMaxChecksumBytes;
    private long downloadMaxExtractedBytes;
    private String userAgent;
    private boolean useCache;
    private boolean cefDisableWebSecurity;
    private boolean cefEnableWidevineCdm;
    // Stored as CefSettings.LogSeverity.name() so <init>/resetDefaults doesn't touch the org.cef enum.
    private String nativeCefLogSeverityName;
    private String consoleLogForwardingMinSeverityName;
    private boolean browserPreloadEnabled;
    private int browserPreloadTransparentPoolSize;
    private int browserPreloadOpaquePoolSize;
    private final Object asyncSaveLock = new Object();
    private CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);

    public RinkuSettings() {
        resetDefaults();
    }

    private void resetDefaults() {
        skipDownload = false;
        downloadMirror = DEFAULT_DOWNLOAD_MIRROR;
        downloadMirrorPolicy = DEFAULT_DOWNLOAD_MIRROR_POLICY;
        enforceDownloadChecksums = DEFAULT_ENFORCE_DOWNLOAD_CHECKSUMS;
        downloadConnectTimeoutMs = DEFAULT_DOWNLOAD_CONNECT_TIMEOUT_MS;
        downloadReadTimeoutMs = DEFAULT_DOWNLOAD_READ_TIMEOUT_MS;
        downloadMaxArchiveBytes = DEFAULT_DOWNLOAD_MAX_ARCHIVE_BYTES;
        downloadMaxChecksumBytes = DEFAULT_DOWNLOAD_MAX_CHECKSUM_BYTES;
        downloadMaxExtractedBytes = DEFAULT_DOWNLOAD_MAX_EXTRACTED_BYTES;
        userAgent = null;
        useCache = true;
        cefDisableWebSecurity = DEFAULT_CEF_DISABLE_WEB_SECURITY;
        cefEnableWidevineCdm = DEFAULT_CEF_ENABLE_WIDEVINE_CDM;
        nativeCefLogSeverityName = DEFAULT_NATIVE_CEF_LOG_SEVERITY_NAME;
        consoleLogForwardingMinSeverityName = DEFAULT_CONSOLE_LOG_FORWARDING_MIN_SEVERITY_NAME;
        browserPreloadEnabled = DEFAULT_BROWSER_PRELOAD_ENABLED;
        browserPreloadTransparentPoolSize = DEFAULT_BROWSER_PRELOAD_TRANSPARENT_POOL_SIZE;
        browserPreloadOpaquePoolSize = DEFAULT_BROWSER_PRELOAD_OPAQUE_POOL_SIZE;
    }

    public boolean isSkipDownload() {
        return skipDownload;
    }

    public void setSkipDownload(boolean skipDownload) {
        this.skipDownload = skipDownload;
        saveAsync();
    }

    public String getDownloadMirror() {
        return downloadMirror;
    }

    public void setDownloadMirror(String downloadMirror) {
        this.downloadMirror = parseMirror(downloadMirror, DEFAULT_DOWNLOAD_MIRROR, "download-mirror", downloadMirrorPolicy == RinkuDownloader.MirrorPolicy.CONFIGURED_ONLY);
        saveAsync();
    }

    public RinkuDownloader.MirrorPolicy getDownloadMirrorPolicy() {
        return downloadMirrorPolicy;
    }

    public void setDownloadMirrorPolicy(RinkuDownloader.MirrorPolicy downloadMirrorPolicy) {
        this.downloadMirrorPolicy = downloadMirrorPolicy == null ? DEFAULT_DOWNLOAD_MIRROR_POLICY : downloadMirrorPolicy;
        saveAsync();
    }

    public boolean isEnforceDownloadChecksums() {
        return enforceDownloadChecksums;
    }

    public void setEnforceDownloadChecksums(boolean enforceDownloadChecksums) {
        this.enforceDownloadChecksums = enforceDownloadChecksums;
        saveAsync();
    }

    public int getDownloadConnectTimeoutMs() {
        return downloadConnectTimeoutMs;
    }

    public void setDownloadConnectTimeoutMs(int downloadConnectTimeoutMs) {
        this.downloadConnectTimeoutMs = clampInt(downloadConnectTimeoutMs, MIN_DOWNLOAD_TIMEOUT_MS, MAX_DOWNLOAD_TIMEOUT_MS, DEFAULT_DOWNLOAD_CONNECT_TIMEOUT_MS, "download-connect-timeout-ms");
        saveAsync();
    }

    public int getDownloadReadTimeoutMs() {
        return downloadReadTimeoutMs;
    }

    public void setDownloadReadTimeoutMs(int downloadReadTimeoutMs) {
        this.downloadReadTimeoutMs = clampInt(downloadReadTimeoutMs, MIN_DOWNLOAD_TIMEOUT_MS, MAX_DOWNLOAD_TIMEOUT_MS, DEFAULT_DOWNLOAD_READ_TIMEOUT_MS, "download-read-timeout-ms");
        saveAsync();
    }

    public long getDownloadMaxArchiveBytes() {
        return downloadMaxArchiveBytes;
    }

    public void setDownloadMaxArchiveBytes(long downloadMaxArchiveBytes) {
        this.downloadMaxArchiveBytes = clampLong(downloadMaxArchiveBytes, MIN_DOWNLOAD_ARCHIVE_BYTES, MAX_DOWNLOAD_ARCHIVE_BYTES, DEFAULT_DOWNLOAD_MAX_ARCHIVE_BYTES, "download-max-archive-bytes");
        saveAsync();
    }

    public long getDownloadMaxChecksumBytes() {
        return downloadMaxChecksumBytes;
    }

    public void setDownloadMaxChecksumBytes(long downloadMaxChecksumBytes) {
        this.downloadMaxChecksumBytes = clampLong(downloadMaxChecksumBytes, MIN_DOWNLOAD_CHECKSUM_BYTES, MAX_DOWNLOAD_CHECKSUM_BYTES, DEFAULT_DOWNLOAD_MAX_CHECKSUM_BYTES, "download-max-checksum-bytes");
        saveAsync();
    }

    public long getDownloadMaxExtractedBytes() {
        return downloadMaxExtractedBytes;
    }

    public void setDownloadMaxExtractedBytes(long downloadMaxExtractedBytes) {
        this.downloadMaxExtractedBytes = clampLong(downloadMaxExtractedBytes, MIN_DOWNLOAD_EXTRACTED_BYTES, MAX_DOWNLOAD_EXTRACTED_BYTES, DEFAULT_DOWNLOAD_MAX_EXTRACTED_BYTES, "download-max-extracted-bytes");
        saveAsync();
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = parseUserAgent(userAgent);
        saveAsync();
    }

    public boolean isUsingCache() {
        return useCache;
    }

    public void setUseCache(boolean useCache) {
        this.useCache = useCache;
        saveAsync();
    }

    public boolean isDisableWebSecurity() {
        return cefDisableWebSecurity;
    }

    public void setDisableWebSecurity(boolean cefDisableWebSecurity) {
        this.cefDisableWebSecurity = cefDisableWebSecurity;
        saveAsync();
    }

    public boolean isEnableWidevineCdm() {
        return cefEnableWidevineCdm;
    }

    public void setEnableWidevineCdm(boolean cefEnableWidevineCdm) {
        this.cefEnableWidevineCdm = cefEnableWidevineCdm;
        saveAsync();
    }

    // Resolve the string-backed value to the actual CefSettings.LogSeverity enum lazily.
    // Callers only invoke these getters AFTER the downloader finished, so org.cef.* is guaranteed
    // to be available on the classpath and the reflective Enum.valueOf will never fail.
    private static CefSettings.LogSeverity logSeverityFromName(String rawName, String fallbackDefaultName) {
        String normalized = normalizeLogSeverityName(rawName, fallbackDefaultName);
        return CefSettings.LogSeverity.valueOf(normalized);
    }

    public CefSettings.LogSeverity getNativeCefLogSeverity() {
        return logSeverityFromName(nativeCefLogSeverityName, DEFAULT_NATIVE_CEF_LOG_SEVERITY_NAME);
    }

    public void setNativeCefLogSeverity(CefSettings.LogSeverity nativeCefLogSeverity) {
        this.nativeCefLogSeverityName = nativeCefLogSeverity == null
                ? DEFAULT_NATIVE_CEF_LOG_SEVERITY_NAME
                : normalizeLogSeverityName(nativeCefLogSeverity.name(), DEFAULT_NATIVE_CEF_LOG_SEVERITY_NAME);
        saveAsync();
    }

    public CefSettings.LogSeverity getConsoleLogForwardingMinSeverity() {
        return logSeverityFromName(consoleLogForwardingMinSeverityName, DEFAULT_CONSOLE_LOG_FORWARDING_MIN_SEVERITY_NAME);
    }

    public void setConsoleLogForwardingMinSeverity(CefSettings.LogSeverity consoleLogForwardingMinSeverity) {
        this.consoleLogForwardingMinSeverityName = consoleLogForwardingMinSeverity == null
                ? DEFAULT_CONSOLE_LOG_FORWARDING_MIN_SEVERITY_NAME
                : normalizeLogSeverityName(consoleLogForwardingMinSeverity.name(), DEFAULT_CONSOLE_LOG_FORWARDING_MIN_SEVERITY_NAME);
        saveAsync();
    }

    public boolean isBrowserPreloadEnabled() {
        return browserPreloadEnabled;
    }

    public void setBrowserPreloadEnabled(boolean browserPreloadEnabled) {
        this.browserPreloadEnabled = browserPreloadEnabled;
        saveAsync();
        Rinku.refreshPreloadedBrowserPool();
    }

    public int getBrowserPreloadTransparentPoolSize() {
        return browserPreloadTransparentPoolSize;
    }

    public void setBrowserPreloadTransparentPoolSize(int browserPreloadTransparentPoolSize) {
        this.browserPreloadTransparentPoolSize = clampInt(browserPreloadTransparentPoolSize, MIN_BROWSER_PRELOAD_POOL_SIZE, MAX_BROWSER_PRELOAD_POOL_SIZE, DEFAULT_BROWSER_PRELOAD_TRANSPARENT_POOL_SIZE, "browser-preload-transparent-pool-size");
        saveAsync();
        Rinku.refreshPreloadedBrowserPool();
    }

    public int getBrowserPreloadOpaquePoolSize() {
        return browserPreloadOpaquePoolSize;
    }

    public void setBrowserPreloadOpaquePoolSize(int browserPreloadOpaquePoolSize) {
        this.browserPreloadOpaquePoolSize = clampInt(browserPreloadOpaquePoolSize, MIN_BROWSER_PRELOAD_POOL_SIZE, MAX_BROWSER_PRELOAD_POOL_SIZE, DEFAULT_BROWSER_PRELOAD_OPAQUE_POOL_SIZE, "browser-preload-opaque-pool-size");
        saveAsync();
        Rinku.refreshPreloadedBrowserPool();
    }

    public RinkuDownloader.DownloadPolicy createDownloadPolicy() {
        return new RinkuDownloader.DownloadPolicy(
                downloadMirrorPolicy,
                enforceDownloadChecksums,
                downloadConnectTimeoutMs,
                downloadReadTimeoutMs,
                downloadMaxArchiveBytes,
                downloadMaxChecksumBytes,
                downloadMaxExtractedBytes
        );
    }

    public void saveAsync() {
        synchronized (asyncSaveLock) {
            // Every setter may request a save in the same frame. Chaining writes prevents concurrent
            // FileOutputStreams from corrupting rinku.properties while retaining non-blocking setters.
            pendingSave = pendingSave.exceptionally(failure -> null).thenRunAsync(this::saveQuietly);
        }
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            LOGGER.error("Failed to save Rinku settings", e);
        }
    }

    public void save() throws IOException {
        File file = getSettingsFile();

        file.getParentFile().mkdirs();

        if (!file.exists()) {
            file.createNewFile();
        }

        Properties properties = new Properties();
        properties.setProperty("skip-download", String.valueOf(skipDownload));
        properties.setProperty("download-mirror", downloadMirror == null ? "" : downloadMirror);
        properties.setProperty("download-mirror-policy", downloadMirrorPolicy.name());
        properties.setProperty("enforce-download-checksums", String.valueOf(enforceDownloadChecksums));
        properties.setProperty("download-connect-timeout-ms", String.valueOf(downloadConnectTimeoutMs));
        properties.setProperty("download-read-timeout-ms", String.valueOf(downloadReadTimeoutMs));
        properties.setProperty("download-max-archive-bytes", String.valueOf(downloadMaxArchiveBytes));
        properties.setProperty("download-max-checksum-bytes", String.valueOf(downloadMaxChecksumBytes));
        properties.setProperty("download-max-extracted-bytes", String.valueOf(downloadMaxExtractedBytes));
        properties.setProperty("user-agent", userAgent == null ? "" : userAgent);
        properties.setProperty("use-cache", String.valueOf(useCache));
        properties.setProperty("cef-disable-web-security", String.valueOf(cefDisableWebSecurity));
        properties.setProperty("cef-enable-widevine-cdm", String.valueOf(cefEnableWidevineCdm));
        properties.setProperty("cef-native-log-severity", nativeCefLogSeverityName);
        properties.setProperty("cef-console-log-forwarding-min-severity", consoleLogForwardingMinSeverityName);
        properties.setProperty("browser-preload-enabled", String.valueOf(browserPreloadEnabled));
        properties.setProperty("browser-preload-transparent-pool-size", String.valueOf(browserPreloadTransparentPoolSize));
        properties.setProperty("browser-preload-opaque-pool-size", String.valueOf(browserPreloadOpaquePoolSize));

        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, null);
        }
    }

    public void load() throws IOException {
        File file = getSettingsFile();

        if (!file.exists()) {
            save();
        }

        Properties properties = new Properties();

        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }

        resetDefaults();

        skipDownload = parseBoolean(properties, "skip-download", skipDownload);
        downloadMirrorPolicy = parseMirrorPolicy(properties.getProperty("download-mirror-policy"), downloadMirrorPolicy);
        downloadMirror = parseMirror(properties.getProperty("download-mirror"), downloadMirror, "download-mirror", downloadMirrorPolicy == RinkuDownloader.MirrorPolicy.CONFIGURED_ONLY);
        enforceDownloadChecksums = parseBoolean(properties, "enforce-download-checksums", enforceDownloadChecksums);
        downloadConnectTimeoutMs = parseInt(properties, "download-connect-timeout-ms", downloadConnectTimeoutMs, MIN_DOWNLOAD_TIMEOUT_MS, MAX_DOWNLOAD_TIMEOUT_MS);
        downloadReadTimeoutMs = parseInt(properties, "download-read-timeout-ms", downloadReadTimeoutMs, MIN_DOWNLOAD_TIMEOUT_MS, MAX_DOWNLOAD_TIMEOUT_MS);
        downloadMaxArchiveBytes = parseLong(properties, "download-max-archive-bytes", downloadMaxArchiveBytes, MIN_DOWNLOAD_ARCHIVE_BYTES, MAX_DOWNLOAD_ARCHIVE_BYTES);
        downloadMaxChecksumBytes = parseLong(properties, "download-max-checksum-bytes", downloadMaxChecksumBytes, MIN_DOWNLOAD_CHECKSUM_BYTES, MAX_DOWNLOAD_CHECKSUM_BYTES);
        downloadMaxExtractedBytes = parseLong(properties, "download-max-extracted-bytes", downloadMaxExtractedBytes, MIN_DOWNLOAD_EXTRACTED_BYTES, MAX_DOWNLOAD_EXTRACTED_BYTES);
        userAgent = parseUserAgent(properties.getProperty("user-agent"));
        useCache = parseBoolean(properties, "use-cache", useCache);
        cefDisableWebSecurity = parseBoolean(properties, "cef-disable-web-security", cefDisableWebSecurity);
        cefEnableWidevineCdm = parseBoolean(properties, "cef-enable-widevine-cdm", cefEnableWidevineCdm);
        nativeCefLogSeverityName = parseLogSeverityName(
                properties.getProperty("cef-native-log-severity"),
                nativeCefLogSeverityName,
                "cef-native-log-severity"
        );
        consoleLogForwardingMinSeverityName = parseLogSeverityName(
                properties.getProperty("cef-console-log-forwarding-min-severity"),
                consoleLogForwardingMinSeverityName,
                "cef-console-log-forwarding-min-severity"
        );
        browserPreloadEnabled = parseBoolean(properties, "browser-preload-enabled", browserPreloadEnabled);
        browserPreloadTransparentPoolSize = parseInt(properties, "browser-preload-transparent-pool-size", browserPreloadTransparentPoolSize, MIN_BROWSER_PRELOAD_POOL_SIZE, MAX_BROWSER_PRELOAD_POOL_SIZE);
        browserPreloadOpaquePoolSize = parseInt(properties, "browser-preload-opaque-pool-size", browserPreloadOpaquePoolSize, MIN_BROWSER_PRELOAD_POOL_SIZE, MAX_BROWSER_PRELOAD_POOL_SIZE);
    }

    private static RinkuDownloader.MirrorPolicy parseMirrorPolicy(String raw, RinkuDownloader.MirrorPolicy fallback) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return RinkuDownloader.MirrorPolicy.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid rinku.properties value for download-mirror-policy: {}", raw);
            return fallback;
        }
    }

    private static String normalizeLogSeverityName(String raw, String fallbackDefaultName) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallbackDefaultName;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("OFF".equals(normalized) || "NONE".equals(normalized)) {
            return "LOGSEVERITY_DISABLE";
        }
        if (!normalized.startsWith("LOGSEVERITY_")) {
            return "LOGSEVERITY_" + normalized;
        }
        return normalized;
    }

    private static String parseLogSeverityName(String raw, String fallback, String key) {
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String candidate = normalizeLogSeverityName(raw, null);
        if (candidate == null) {
            return fallback;
        }
        // Validate the name syntactically so the later lazy enum resolution never fails on garbage input.
        switch (candidate) {
            case "LOGSEVERITY_DISABLE":
            case "LOGSEVERITY_ERROR":
            case "LOGSEVERITY_ERROR_REPORT":
            case "LOGSEVERITY_WARNING":
            case "LOGSEVERITY_INFO":
            case "LOGSEVERITY_VERBOSE":
            case "LOGSEVERITY_DEBUG":
            case "LOGSEVERITY_DEFAULT":
                return candidate;
            default:
                LOGGER.warn("Invalid rinku.properties value for {}: {}", key, raw);
                return fallback;
        }
    }

    private static boolean parseBoolean(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "y".equals(normalized) || "on".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "n".equals(normalized) || "off".equals(normalized)) {
            return false;
        }
        LOGGER.warn("Invalid rinku.properties value for {}: {}", key, raw);
        return fallback;
    }

    private static int parseInt(Properties properties, String key, int fallback, int min, int max) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return clampInt(Integer.parseInt(raw.trim()), min, max, fallback, key);
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid rinku.properties value for {}: {}", key, raw);
            return fallback;
        }
    }

    private static long parseLong(Properties properties, String key, long fallback, long min, long max) {
        String raw = properties.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        try {
            return clampLong(Long.parseLong(raw.trim()), min, max, fallback, key);
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid rinku.properties value for {}: {}", key, raw);
            return fallback;
        }
    }

    private static int clampInt(int value, int min, int max, int fallback, String key) {
        if (value < min) {
            LOGGER.warn("Clamping rinku.properties {} to the minimum allowed value: {} (was {})", key, min, value);
            return min;
        }
        if (value > max) {
            LOGGER.warn("Clamping rinku.properties {} to the maximum allowed value: {} (was {})", key, max, value);
            return max;
        }
        return value;
    }

    private static long clampLong(long value, long min, long max, long fallback, String key) {
        if (value < min) {
            LOGGER.warn("Clamping rinku.properties {} to the minimum allowed value: {} (was {})", key, min, value);
            return min;
        }
        if (value > max) {
            LOGGER.warn("Clamping rinku.properties {} to the maximum allowed value: {} (was {})", key, max, value);
            return max;
        }
        return value;
    }

    private static String parseMirror(String raw, String fallback, String key, boolean strictConfiguredOnly) {
        if (raw == null || raw.trim().isEmpty()) {
            if (strictConfiguredOnly) {
                throw new IllegalArgumentException("Rinku settings value '" + key + "' cannot be blank when the mirror policy is CONFIGURED_ONLY");
            }
            return fallback;
        }
        String candidate = RinkuDownloader.normalizeOfficialMirror(raw.trim());
        if (candidate == null) {
            return fallback;
        }
        try {
            RinkuDownloadMirror.parse(candidate);
        } catch (IllegalArgumentException e) {
            if (strictConfiguredOnly) {
                throw new IllegalArgumentException("Rinku settings value '" + key + "' is not a valid URL for CONFIGURED_ONLY: '" + raw + "'", e);
            }
            LOGGER.warn("Ignoring invalid {} in rinku.properties: {}", key, raw);
            return fallback;
        }
        return candidate;
    }

    private static String parseUserAgent(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static File getSettingsFile() {
        return GameDirectoryUtils.getGameDirectory().toPath().resolve("config").resolve("rinku.properties").toFile();
    }
}
