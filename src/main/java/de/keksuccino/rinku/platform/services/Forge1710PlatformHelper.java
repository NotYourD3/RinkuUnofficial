package de.keksuccino.rinku.platform.services;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.launchwrapper.Launch;

import java.util.ArrayList;
import java.util.List;

public class Forge1710PlatformHelper implements IPlatformHelper {
    @Override
    public String getPlatformName() {
        return "forge";
    }

    @Override
    public String getPlatformDisplayName() {
        return "Forge";
    }

    @Override
    public String getLoaderVersion() {
        return Loader.instance().getFMLVersionString();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return Loader.isModLoaded(modId);
    }

    @Override
    public String getModVersion(String modId) {
        for (ModContainer mc : Loader.instance().getModList()) {
            if (modId.equals(mc.getModId())) {
                return mc.getVersion();
            }
        }
        return null;
    }

    @Override
    public List<String> getLoadedModIds() {
        List<String> ids = new ArrayList<String>();
        for (ModContainer mc : Loader.instance().getModList()) {
            ids.add(mc.getModId());
        }
        return ids;
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        try {
            Object deobf = Launch.blackboard.get("fml.deobfuscatedEnvironment");
            return Boolean.TRUE.equals(deobf);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean isOnClient() {
        return FMLCommonHandler.instance().getEffectiveSide() == Side.CLIENT;
    }

    @Override
    public int getKeyBindingKeyCode(KeyBinding keyBinding) {
        return keyBinding != null ? keyBinding.getKeyCode() : 0;
    }
}
