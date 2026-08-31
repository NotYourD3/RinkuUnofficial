package de.keksuccino.rinku.mixins;

import de.keksuccino.rinku.Rinku;
import de.keksuccino.rinku.OSPlatform;
import de.keksuccino.rinku.RinkuRenderCoordinator;
import de.keksuccino.rinku.RinkuSettings;
import de.keksuccino.rinku.binarydownload.RinkuDownloadListener;
import de.keksuccino.rinku.binarydownload.RinkuDownloader;
import de.keksuccino.rinku.binarydownload.RinkuDownloaderScreen;
import de.keksuccino.rinku.platform.Services;
import de.keksuccino.rinku.util.GameDirectoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    @Unique
    private static final Logger LOGGER_RINKU = LogManager.getLogger(Rinku.MOD_ID);
    @Unique
    private static final AtomicBoolean RECURSION_DETECTOR_RINKU = new AtomicBoolean(false);
    @Unique
    private static final String JCEF_HELPER_EXECUTABLE_WINDOWS_RINKU = "jcef_helper.exe";
    @Unique
    private static final long CURRENT_PROCESS_PID_RINKU = getCurrentPid_RINKU();
    @Unique
    private static volatile boolean rinkuEarlyInitDone_RINKU = false;
    @Unique
    private static volatile Thread downloadThread_RINKU = null;

    @Unique
    private static boolean shouldHandleScreenChange_RINKU(@Nullable GuiScreen screen, boolean recursionValue) {
        return !recursionValue
            || screen instanceof GuiMainMenu
            || screen instanceof GuiSelectWorld
            || screen instanceof GuiScreenAddServer
            || screen instanceof GuiScreenServerList
            || screen instanceof GuiConnecting
            || screen instanceof GuiMultiplayer
            || screen instanceof GuiCreateWorld
            || screen instanceof GuiScreenResourcePacks;
    }

    @Shadow
    public abstract void displayGuiScreen(@Nullable GuiScreen screen);

    @Inject(at = @At("TAIL"), method = "<init>*")
    private void on_ctor_RINKU(CallbackInfo callbackInfo) {
        LOGGER_RINKU.info("MixinMinecraft ctor hook fired; rinkuEarlyInitDone_RINKU=" + rinkuEarlyInitDone_RINKU);
        if (rinkuEarlyInitDone_RINKU) return;
        rinkuEarlyInitDone_RINKU = true;

        RinkuDownloadListener.INSTANCE.setDone(false);
        RinkuDownloadListener.INSTANCE.setFailed(false);
        RinkuDownloadListener.INSTANCE.setTask(new ChatComponentTranslation("rinku.downloader.task.preparing"));

        try {
            setupLibraryPath_RINKU();
        } catch (Throwable t) {
            LOGGER_RINKU.error("setupLibraryPath_RINKU threw", t);
            failDownload_RINKU("Failed to prepare Rinku library paths", new ChatComponentTranslation("rinku.downloader.task.failed_library_paths"), t instanceof Exception ? (Exception) t : new RuntimeException(t));
            return;
        }

        Thread downloadThread = new Thread(MixinMinecraft::runDownloaderFlow_RINKU, "Rinku-Downloader");
        downloadThread.setDaemon(true);
        downloadThread.setUncaughtExceptionHandler((t, e) -> LOGGER_RINKU.error("Uncaught exception on Rinku-Downloader thread", e));
        downloadThread_RINKU = downloadThread;
        LOGGER_RINKU.info("Starting Rinku-Downloader thread.");
        downloadThread.start();
    }

    @Inject(method = "displayGuiScreen", at = @At("HEAD"), cancellable = true)
    public void before_setScreen_RINKU(@Nullable GuiScreen screen, CallbackInfo info) {
        if (!Rinku.isInitializationAllowed()) {
            return;
        }

        boolean recursionValue = RECURSION_DETECTOR_RINKU.get();
        RECURSION_DETECTOR_RINKU.set(true);

        try {
            if (!shouldHandleScreenChange_RINKU(screen, recursionValue)) {
                return;
            }

            if (RinkuDownloadListener.INSTANCE.isDone() && !RinkuDownloadListener.INSTANCE.isFailed()) {
                LOGGER_RINKU.debug("Rinku already finished downloading, scheduling loading.");
                if (!Rinku.isInitializationAllowed()) return;
                LOGGER_RINKU.debug("Rinku is attempting to load.");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER_RINKU.warn("Interrupted while waiting to initialize Rinku.", e);
                    return;
                }
                if (Rinku.isInitializationAllowed()) Rinku.initialize();
            } else if (!RinkuDownloadListener.INSTANCE.isDone() && !RinkuDownloadListener.INSTANCE.isFailed()) {
                LOGGER_RINKU.debug("Rinku has not finished loading, displaying loading screen.");
                displayGuiScreen(new RinkuDownloaderScreen(screen));
                info.cancel();
            } else if (RinkuDownloadListener.INSTANCE.isFailed()) {
                LOGGER_RINKU.error("Rinku failed to initialize!");
            }
        } finally {
            RECURSION_DETECTOR_RINKU.set(recursionValue);
        }
    }


    @Inject(method = "runTick", at = @At("HEAD"))
    private void before_runTick_RINKU(CallbackInfo ci) {
        RinkuRenderCoordinator.pumpOnRenderThread();
    }

    @Inject(method = "shutdown", at = @At("HEAD"))
    private void before_close_RINKU(CallbackInfo info) {
        LOGGER_RINKU.info("before_close_RINKU (shutdown HEAD) entered.");
        interruptDownloader_RINKU();
        RinkuRenderCoordinator.shutdownOnRenderThread();
        Rinku.shutdown();
        LOGGER_RINKU.info("before_close_RINKU done.");
    }

    @Inject(method = "shutdown", at = @At("TAIL"))
    public void after_close_RINKU(CallbackInfo info) {
        LOGGER_RINKU.info("after_close_RINKU (shutdown TAIL) entered; attempting to kill lingering jcef_helper.");

        if (!OSPlatform.getPlatform().isWindows()) {
            return;
        }

        Path rinkuLibrariesPath = resolveRinkuLibrariesPath_RINKU();
        if (rinkuLibrariesPath == null) {
            LOGGER_RINKU.warn("rinku.libraries.path is not set, skipping scoped JCEF helper cleanup.");
            return;
        }

        AtomicInteger terminatedProcesses = new AtomicInteger(0);
        try {
            List<WindowsProcessInfo> processes = enumerateWindowsProcesses_RINKU();
            for (WindowsProcessInfo process : processes) {
                try {
                    if (!shouldTerminateJcefHelper_RINKU(process, rinkuLibrariesPath)) {
                        continue;
                    }

                    if (terminateWindowsProcess_RINKU(process.pid)) {
                        terminatedProcesses.incrementAndGet();
                        LOGGER_RINKU.warn("Terminated lingering JCEF helper process (pid={}).", process.pid);
                    }
                } catch (Exception e) {
                    LOGGER_RINKU.debug("Unable to inspect process {} for scoped JCEF cleanup.", process.pid, e);
                }
            }
        } catch (Exception e) {
            LOGGER_RINKU.error("Unable to enumerate processes for scoped JCEF cleanup.", e);
            return;
        }

        if (terminatedProcesses.get() > 0) {
            LOGGER_RINKU.warn("Terminated {} lingering JCEF helper process(es) under {}.",
                    terminatedProcesses.get(), rinkuLibrariesPath);
        }

    }

    @Unique
    private static void interruptDownloader_RINKU() {
        LOGGER_RINKU.info("interruptDownloader_RINKU called.");
        // Force-close any active HTTP connection FIRST, because Java's blocking SocketInputStream ignores Thread.interrupt()
        // and the thread would otherwise hang forever in read(buffer) preventing a clean JVM shutdown.
        RinkuDownloader.cancelAnyActiveDownload_RINKU();
        Thread t = downloadThread_RINKU;
        if (t != null && t.isAlive()) {
            LOGGER_RINKU.info("Interrupting Rinku downloader thread for game shutdown.");
            t.interrupt();
            try {
                t.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (t.isAlive()) {
                LOGGER_RINKU.warn("Rinku-Downloader thread is still alive 3s after interrupt; JVM exit may be delayed.");
            }
        } else {
            LOGGER_RINKU.info("No active Rinku-Downloader thread to interrupt.");
        }
    }

    @Unique
    private static void setupLibraryPath_RINKU() throws IOException {
        Path rinkuLibrariesDirectory = GameDirectoryUtils.getGameDirectory().toPath().resolve("rinku-libraries");
        Files.createDirectories(rinkuLibrariesDirectory);
        System.setProperty("rinku.libraries.path", rinkuLibrariesDirectory.toRealPath().toString());
    }

    @Unique
    private static void runDownloaderFlow_RINKU() {
        LOGGER_RINKU.info("runDownloaderFlow_RINKU entered.");
        try {
            LOGGER_RINKU.info("Step 1: Resolving java-cef commit.");
            String javaCefCommit = Rinku.getJavaCefCommit();
            LOGGER_RINKU.info("java-cef commit: " + javaCefCommit);

            LOGGER_RINKU.info("Step 2: Loading Rinku settings.");
            RinkuSettings settings = Rinku.getSettings();
            LOGGER_RINKU.info("Settings loaded; downloadMirror='{}'; skipDownload={}", settings.getDownloadMirror(), settings.isSkipDownload());

            boolean isDev = Services.PLATFORM.isDevelopmentEnvironment();
            LOGGER_RINKU.info("isDevelopmentEnvironment={}", isDev);
            if (isDev) {
                LOGGER_RINKU.info("Development environment detected; resolving JCEF via jcef.path system property or classpath.");
                String jcefPath = System.getProperty("jcef.path");
                if (jcefPath == null || jcefPath.isEmpty()) {
                    Path rinkuLibrariesDirectory = GameDirectoryUtils.getGameDirectory().toPath().resolve("rinku-libraries");
                    Path installDir = rinkuLibrariesDirectory.resolve("java-cef-" + javaCefCommit);
                    LOGGER_RINKU.info("Checking existing JCEF directory: {}", installDir);
                    if (Files.isDirectory(installDir)) {
                        Path installDirReal = installDir.toRealPath();
                        System.setProperty("jcef.path", installDirReal.toString());
                        LOGGER_RINKU.info("Found existing JCEF installation in dev environment: " + installDir);
                        loadJarsOntoClasspath_RINKU(installDirReal);
                        configureNativeLibraryPath_RINKU(installDirReal);
                        RinkuDownloadListener.INSTANCE.setDone(true);
                        return;
                    }
                    LOGGER_RINKU.info("No existing JCEF installation found in dev environment; proceeding with a normal download.");
                } else {
                    LOGGER_RINKU.info("Using jcef.path from system property: " + jcefPath);
                    Path configuredDir = Paths.get(jcefPath).toRealPath();
                    loadJarsOntoClasspath_RINKU(configuredDir);
                    configureNativeLibraryPath_RINKU(configuredDir);
                    RinkuDownloadListener.INSTANCE.setDone(true);
                    return;
                }
            }

            LOGGER_RINKU.info("Step 3: Resolving platform and creating downloader.");
            OSPlatform platform = OSPlatform.getPlatform();
            LOGGER_RINKU.info("Resolved OS platform: {}", platform.getNormalizedName());
            RinkuDownloader downloader = new RinkuDownloader(settings.getDownloadMirror(), platform, settings.createDownloadPolicy());
            LOGGER_RINKU.info("RinkuDownloader constructed; host={}", downloader.getHost());

            // In dev environment only skip if explicitly requested in settings OR jcef.path was already supplied above.
            // Otherwise fall back to a real download so first-time dev runs still work.
            boolean skip = settings.isSkipDownload();
            LOGGER_RINKU.info("Step 4: Calling downloader.installOrUpdate(skip={})", skip);
            RinkuDownloader.InstallationResult installation = downloader.installOrUpdate(skip);
            LOGGER_RINKU.info("installOrUpdate returned OK; installedTo={}; downloaded={}", installation.installationDirectory(), installation.downloaded());
            Path installDirReal = installation.installationDirectory().toRealPath();
            System.setProperty("jcef.path", installDirReal.toString());
            loadJarsOntoClasspath_RINKU(installDirReal);
            configureNativeLibraryPath_RINKU(installDirReal);
            RinkuDownloadListener.INSTANCE.setDone(true);
            LOGGER_RINKU.info("runDownloaderFlow_RINKU completed successfully.");
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                LOGGER_RINKU.info("Rinku downloader was interrupted by game shutdown.");
                return;
            }
            LOGGER_RINKU.error("runDownloaderFlow_RINKU: IOException", e);
            failDownload_RINKU("Failed to initialize JCEF downloader", new ChatComponentTranslation("rinku.downloader.task.failed_initialization"), e);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                LOGGER_RINKU.info("Rinku downloader was interrupted by game shutdown.");
                return;
            }
            LOGGER_RINKU.error("runDownloaderFlow_RINKU: RuntimeException", e);
            failDownload_RINKU("JCEF downloader failed due to an invalid configuration", new ChatComponentTranslation("rinku.downloader.task.failed_configuration"), e);
        } catch (Throwable t) {
            LOGGER_RINKU.error("runDownloaderFlow_RINKU: FATAL uncaught throwable (will mark failed so GUI does not hang forever)", t);
            if (!RinkuDownloadListener.INSTANCE.isDone() && !RinkuDownloadListener.INSTANCE.isFailed()) {
                failDownload_RINKU("Downloader crashed with fatal error", new ChatComponentTranslation("rinku.downloader.task.failed_initialization"), t instanceof Exception ? (Exception) t : new RuntimeException(t));
            }
        }
    }

    @Unique
    private static void failDownload_RINKU(String logMessage, IChatComponent task, Exception e) {
        if (e != null) {
            LOGGER_RINKU.error(logMessage, e);
        } else {
            LOGGER_RINKU.error(logMessage);
        }
        RinkuDownloadListener.INSTANCE.setTask(task);
        RinkuDownloadListener.INSTANCE.setFailed(true);
    }

    @Unique
    private static void loadJarsOntoClasspath_RINKU(Path installDir) throws IOException {
        List<Path> jars;
        try (Stream<Path> stream = Files.list(installDir)) {
            jars = stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .filter(Files::isRegularFile)
                    .sorted((a, b) -> {
                        String an = a.getFileName().toString();
                        String bn = b.getFileName().toString();
                        boolean aJcef = an.equalsIgnoreCase("jcef.jar");
                        boolean bJcef = bn.equalsIgnoreCase("jcef.jar");
                        if (aJcef && !bJcef) return -1;
                        if (bJcef && !aJcef) return 1;
                        return an.compareTo(bn);
                    })
                    .collect(java.util.stream.Collectors.toList());
        }

        ClassLoader appClassLoader = MixinMinecraft.class.getClassLoader();
        Method addUrlMethod = null;
        Class<?> clazz = appClassLoader.getClass();
        while (clazz != null) {
            try {
                addUrlMethod = clazz.getDeclaredMethod("addURL", URL.class);
                addUrlMethod.setAccessible(true);
                break;
            } catch (NoSuchMethodException ignored) {
                clazz = clazz.getSuperclass();
            }
        }

        if (addUrlMethod == null) {
            throw new IOException("Could not locate addURL(URL) method on class loader: " + appClassLoader.getClass().getName());
        }

        for (Path jar : jars) {
            URL jarUrl = jar.toUri().toURL();
            try {
                addUrlMethod.invoke(appClassLoader, jarUrl);
                LOGGER_RINKU.info("Added JCEF JAR to classpath: {}", jar);
            } catch (ReflectiveOperationException e) {
                throw new IOException("Failed to add JAR to classpath: " + jar, e);
            }
        }
    }

    @Unique
    private static void configureNativeLibraryPath_RINKU(Path installDir) throws IOException {
        String installDirStr = installDir.toAbsolutePath().toString();

        String currentLibraryPath = System.getProperty("java.library.path", "");
        if (!currentLibraryPath.isEmpty()) {
            String sep = System.getProperty("path.separator", ";");
            boolean alreadyPresent = false;
            for (String part : currentLibraryPath.split(sep)) {
                if (part.equals(installDirStr)) {
                    alreadyPresent = true;
                    break;
                }
            }
            if (!alreadyPresent) {
                System.setProperty("java.library.path", installDirStr + sep + currentLibraryPath);
            }
        } else {
            System.setProperty("java.library.path", installDirStr);
        }

        try {
            Field userPathsField = ClassLoader.class.getDeclaredField("usr_paths");
            userPathsField.setAccessible(true);
            String[] current = (String[]) userPathsField.get(null);
            if (current != null) {
                for (String p : current) {
                    if (installDirStr.equals(p)) {
                        return;
                    }
                }
                String[] updated = new String[current.length + 1];
                updated[0] = installDirStr;
                System.arraycopy(current, 0, updated, 1, current.length);
                userPathsField.set(null, updated);
            } else {
                userPathsField.set(null, new String[]{installDirStr});
            }
            LOGGER_RINKU.info("Injected JCEF native library directory into ClassLoader.usr_paths: {}", installDirStr);
        } catch (NoSuchFieldException nsfe) {
            LOGGER_RINKU.debug("JDK ClassLoader.usr_paths field unavailable; relying on java.library.path system property only.", nsfe);
        } catch (IllegalAccessException iae) {
            LOGGER_RINKU.warn("Could not inject into ClassLoader.usr_paths; relying on java.library.path system property only.", iae);
        }

        try {
            Field sysPathsField = ClassLoader.class.getDeclaredField("sys_paths");
            sysPathsField.setAccessible(true);
            sysPathsField.set(null, null);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
        }
    }

    @Unique
    private static boolean shouldTerminateJcefHelper_RINKU(WindowsProcessInfo process, Path rinkuLibrariesPath) {
        if (process.pid == CURRENT_PROCESS_PID_RINKU) {
            return false;
        }

        if (!isJcefHelperProcess_RINKU(process)) {
            return false;
        }

        if (isExecutableInRinkuLibraries_RINKU(process, rinkuLibrariesPath)) {
            return true;
        }

        return isDescendantOfCurrentProcess_RINKU(process)
                && commandLineContainsLibrariesPath_RINKU(process, rinkuLibrariesPath);
    }

    @Unique
    private static boolean terminateWindowsProcess_RINKU(long pid) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"taskkill.exe", "/F", "/PID", String.valueOf(pid)});
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            LOGGER_RINKU.debug("Failed to terminate process pid=" + pid, e);
            return false;
        }
    }

    @Unique
    private static boolean isJcefHelperProcess_RINKU(WindowsProcessInfo process) {
        if (process.name != null && isJcefHelperExecutableName_RINKU(process.name)) {
            return true;
        }
        if (process.commandLine != null) {
            return process.commandLine.toLowerCase(Locale.ROOT).contains(JCEF_HELPER_EXECUTABLE_WINDOWS_RINKU);
        }
        return false;
    }

    @Unique
    private static boolean isJcefHelperExecutableName_RINKU(String command) {
        int lastSeparatorIndex = Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\'));
        String executableName = lastSeparatorIndex >= 0 ? command.substring(lastSeparatorIndex + 1) : command;
        return executableName.equalsIgnoreCase(JCEF_HELPER_EXECUTABLE_WINDOWS_RINKU);
    }

    @Unique
    private static boolean isExecutableInRinkuLibraries_RINKU(WindowsProcessInfo process, Path rinkuLibrariesPath) {
        String command = (process.executablePath != null) ? process.executablePath : process.commandLine;
        if (command == null) {
            return false;
        }

        try {
            Path commandPath = Paths.get(command).normalize();
            if (!commandPath.isAbsolute()) {
                return false;
            }
            return commandPath.startsWith(rinkuLibrariesPath);
        } catch (InvalidPathException ignored) {
            return false;
        }
    }

    @Unique
    private static boolean commandLineContainsLibrariesPath_RINKU(WindowsProcessInfo process, Path rinkuLibrariesPath) {
        if (process.commandLine == null) return false;
        String librariesPath = rinkuLibrariesPath.toString().toLowerCase(Locale.ROOT);
        return process.commandLine.toLowerCase(Locale.ROOT).contains(librariesPath);
    }

    @Unique
    private static boolean isDescendantOfCurrentProcess_RINKU(WindowsProcessInfo process) {
        long parentPid = process.parentPid;
        int safetyCounter = 0;
        while (parentPid != 0L && safetyCounter++ < 100) {
            if (parentPid == CURRENT_PROCESS_PID_RINKU) {
                return true;
            }
            Long nextParent = findParentPid_RINKU(parentPid);
            if (nextParent == null) {
                return false;
            }
            if (nextParent == parentPid) {
                return false;
            }
            parentPid = nextParent;
        }
        return false;
    }

    @Unique
    private static Long findParentPid_RINKU(long pid) {
        try {
            List<WindowsProcessInfo> all = enumerateWindowsProcesses_RINKU();
            for (WindowsProcessInfo info : all) {
                if (info.pid == pid) {
                    return info.parentPid;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Unique
    private static @Nullable Path resolveRinkuLibrariesPath_RINKU() {
        String configuredPath = System.getProperty("rinku.libraries.path");
        if (configuredPath == null || configuredPath.isEmpty()) {
            return null;
        }

        try {
            return Paths.get(configuredPath).toRealPath().normalize();
        } catch (IOException | InvalidPathException e) {
            try {
                return Paths.get(configuredPath).toAbsolutePath().normalize();
            } catch (InvalidPathException ignored) {
                return null;
            }
        }
    }

    @Unique
    private static long getCurrentPid_RINKU() {
        try {
            String name = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            if (name != null) {
                int at = name.indexOf('@');
                if (at > 0) {
                    return Long.parseLong(name.substring(0, at));
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }

    @Unique
    private static List<WindowsProcessInfo> enumerateWindowsProcesses_RINKU() throws IOException {
        List<WindowsProcessInfo> result = new ArrayList<>();
        ProcessBuilder pb = new ProcessBuilder(
                "wmic", "process", "get", "ProcessId,ParentProcessId,Name,ExecutablePath,CommandLine",
                "/format:csv");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!headerSkipped) {
                    headerSkipped = true;
                    continue;
                }
                String[] parts = parseCsvLine_RINKU(line);
                if (parts.length < 6) continue;
                try {
                    WindowsProcessInfo info = new WindowsProcessInfo();
                    info.node = parts[0];
                    info.commandLine = parts[1].isEmpty() ? null : parts[1];
                    info.executablePath = parts[2].isEmpty() ? null : parts[2];
                    info.name = parts[3].isEmpty() ? null : parts[3];
                    info.parentPid = parts[4].isEmpty() ? 0L : Long.parseLong(parts[4]);
                    info.pid = parts[5].isEmpty() ? 0L : Long.parseLong(parts[5]);
                    result.add(info);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        try {
            p.waitFor();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return result;
    }

    @Unique
    private static String[] parseCsvLine_RINKU(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuote = !inQuote;
                }
            } else if (c == ',' && !inQuote) {
                tokens.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString());
        return tokens.toArray(new String[0]);
    }

    @Unique
    private static final class WindowsProcessInfo {
        String node;
        String commandLine;
        String executablePath;
        String name;
        long parentPid;
        long pid;
    }

}
