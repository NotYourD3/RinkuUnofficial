package de.keksuccino.rinku.util;

import de.keksuccino.rinku.platform.Services;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class GameDirectoryUtils {

    private static final Logger LOGGER = LogManager.getLogger("GameDirectoryUtils");

    public static File getGameDirectory() {
        try {
            if (Services.PLATFORM.isOnClient()) {
                Minecraft minecraft = Minecraft.getMinecraft();
                if ((minecraft != null) && (minecraft.mcDataDir != null)) {
                    return minecraft.mcDataDir;
                }
            } else {
                Path path = Paths.get("server.properties");
                return path.toAbsolutePath().getParent().toFile();
            }
        } catch (Exception ex) {
            LOGGER.error("[Rinku] Failed to get game directory!", ex);
        }
        Path workingDirectory = Paths.get("").toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory)) {
            return workingDirectory.toFile();
        }
        return new File(".");
    }

    public static boolean isExistingGameDirectoryPath(String path) {
        Objects.requireNonNull(path);
        path = path.replace("\\", "/");
        String gameDir = getGameDirectory().getAbsolutePath().replace("\\", "/");
        if (!path.startsWith(gameDir)) {
            path = gameDir + "/" + path;
        }
        return new File(path).exists();
    }

    public static String getAbsoluteGameDirectoryPath(String path) {
        try {
            path = path.replace("\\", "/");
            String gameDir = getGameDirectory().getAbsolutePath().replace("\\", "/");
            if (!path.startsWith(gameDir)) {
                if (path.startsWith("/")) path = path.substring(1);
                return gameDir + "/" + path;
            }
        } catch (Exception ex) {
            LOGGER.error("[Rinku] Failed to get absolute game directory path!", ex);
        }
        return path;
    }

    public static String getPathWithoutGameDirectory(String path) {
        Objects.requireNonNull(path);
        File f = new File(getAbsoluteGameDirectoryPath(path));
        String p = f.getAbsolutePath().replace("\\", "/").replace(getGameDirectory().getAbsolutePath().replace("\\", "/"), "");
        if (p.startsWith("/")) p = p.substring(1);
        return p;
    }

}
