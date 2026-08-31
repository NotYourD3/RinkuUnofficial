package de.keksuccino.rinku;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import net.minecraft.client.Minecraft;

@SideOnly(Side.CLIENT)
public final class RenderStateBridge {

    private static final Thread mainThread;

    static {
        Thread t = null;
        try {
            t = Minecraft.class.getMethod("getMinecraft").getDeclaringClass() != null ? Thread.currentThread() : null;
        } catch (Throwable ignored) {}
        mainThread = t != null ? t : Thread.currentThread();
    }

    private RenderStateBridge() {}

    public static boolean isOnRenderThread() {
        return Thread.currentThread().getId() == mainThread.getId();
    }

    public static void assertOnRenderThread() {
    }
}
