package de.keksuccino.rinku.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Publishes the jcef.dll bundled inside the Rinku jar into the active JCEF installation before
 * {@link CefUtil#init()} calls CefApp.startup().
 *
 * <p>The GPU zero-copy fix lives in the native library, not in Java bytecode. Shipping the DLL as a
 * jar resource makes a single copied file enough to validate the fix in a production (SRG) game:
 * the installed JCEF cache is checked on every startup and replaced whenever its jcef.dll differs
 * from the bundled one. All failures are non-fatal - the game simply falls back to the stock DLL
 * and to the CPU readback path.</p>
 *
 * <p>Set {@code -Drinku.skipBundledJcefDll=true} to opt out and use the installed DLL unchanged.</p>
 */
public final class BundledJcefNative {

    private static final Logger LOGGER = LogManager.getLogger("RinkuZeroCopy");
    private static final String RESOURCE = "/rinku_native/jcef.dll";
    private static final String DLL_NAME = "jcef.dll";
    private static final String SKIP_PROPERTY = "rinku.skipBundledJcefDll";

    private BundledJcefNative() {}

    /**
     * @return true when the bundled DLL is the one that will be loaded (or was already installed).
     */
    public static boolean install(Path installation) {
        if (Boolean.parseBoolean(System.getProperty(SKIP_PROPERTY, "false"))) {
            LOGGER.info("[ZeroCopy] Bundled jcef.dll disabled via -D" + SKIP_PROPERTY + "; using stock DLL.");
            return false;
        }
        if (!isWindows64()) {
            LOGGER.info("[ZeroCopy] Not a 64-bit Windows platform; bundled jcef.dll not applicable.");
            return false;
        }
        if (installation == null) {
            LOGGER.warn("[ZeroCopy] No JCEF installation path; bundled DLL cannot be installed.");
            return false;
        }

        try {
            byte[] bundled = readResource();
            if (bundled == null) {
                LOGGER.warn("[ZeroCopy] This jar has no " + RESOURCE + "; using stock DLL (no zero-copy).");
                return false;
            }

            Path target = installation.resolve(DLL_NAME)
                .toAbsolutePath()
                .normalize();
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                byte[] existing = Files.readAllBytes(target);
                if (Arrays.equals(existing, bundled)) {
                    LOGGER.info(
                        "[ZeroCopy] Bundled jcef.dll already active ({} bytes, sha256={}) at {}",
                        bundled.length,
                        shortSha256(bundled),
                        target);
                    return true;
                }
            }

            Path temp = target.resolveSibling(DLL_NAME + ".rinku.tmp");
            Files.write(
                temp,
                bundled,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            LOGGER.info(
                "[ZeroCopy] Installed bundled jcef.dll ({} bytes, sha256={}) -> {}",
                bundled.length,
                shortSha256(bundled),
                target);
            return true;
        } catch (Throwable failure) {
            LOGGER.error(
                "[ZeroCopy] Could not install bundled jcef.dll; GPU zero-copy will NOT be active. "
                    + "Make the JCEF cache writable, or copy the DLL from the jar manually. "
                    + "Use -D" + SKIP_PROPERTY + "=true to silence this.",
                failure);
            return false;
        }
    }

    private static byte[] readResource() throws IOException {
        try (InputStream stream = BundledJcefNative.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(2 << 20);
            byte[] chunk = new byte[16384];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                out.write(chunk, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static boolean isWindows64() {
        String os = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return false;
        }
        String arch = System.getProperty("os.arch", "")
            .toLowerCase(Locale.ROOT);
        String dataModel = System.getProperty("sun.arch.data.model", "");
        return arch.contains("64") || "64".equals(dataModel);
    }

    private static String shortSha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes);
            StringBuilder text = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                text.append(String.format("%02x", digest[i] & 0xff));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return "n/a";
        }
    }
}
