package de.keksuccino.rinku.platform.services;

import net.minecraft.client.settings.KeyBinding;
import java.util.List;

public interface IPlatformHelper {

    String getPlatformName();

    String getPlatformDisplayName();

    String getLoaderVersion();

    boolean isModLoaded(String modId);

    String getModVersion(String modId);

    List<String> getLoadedModIds();

    boolean isDevelopmentEnvironment();

    boolean isOnClient();

    int getKeyBindingKeyCode(KeyBinding keyBinding);

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }

}
