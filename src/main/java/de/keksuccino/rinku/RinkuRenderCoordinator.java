package de.keksuccino.rinku;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

public final class RinkuRenderCoordinator {

    private static final Logger LOGGER = LogManager.getLogger("RinkuRenderCoordinator");
    private static final RenderThreadMailboxCoordinator<Object> BROWSERS = new RenderThreadMailboxCoordinator<>();

    private RinkuRenderCoordinator() {}

    static boolean register(Object browser) {
        return BROWSERS.register(browser);
    }

    static void unregister(Object browser) {
        BROWSERS.unregister(browser);
    }

    public static void pumpOnRenderThread() {
        if (!Rinku.isInitialized()) return;
        RenderStateBridge.assertOnRenderThread();
        BROWSERS.pump(RinkuRenderCoordinator::invokePump, RinkuRenderCoordinator::logBrowserFailure);
    }

    public static void shutdownOnRenderThread() {
        RenderStateBridge.assertOnRenderThread();
        BROWSERS.shutdown(RinkuRenderCoordinator::invokeShutdown, RinkuRenderCoordinator::logBrowserFailure);
    }

    private static final class Holder {
        static final Method PUMP_METHOD;
        static final Method SHUTDOWN_METHOD;
        static {
            Method p = null;
            Method s = null;
            try {
                Class<?> cefBrowserClass = Class.forName("de.keksuccino.rinku.RinkuBrowser");
                p = cefBrowserClass.getDeclaredMethod("pumpAsyncPaintsOnRenderThread");
                s = cefBrowserClass.getDeclaredMethod("shutdownOnRenderThread");
                p.setAccessible(true);
                s.setAccessible(true);
            } catch (Throwable t) {
                LOGGER.warn("[RINKU] Failed to resolve RinkuBrowser render-thread methods.", t);
            }
            PUMP_METHOD = p;
            SHUTDOWN_METHOD = s;
        }
    }

    private static void invokePump(Object browser) {
        try {
            if (Holder.PUMP_METHOD != null) {
                Holder.PUMP_METHOD.invoke(browser);
            }
        } catch (Throwable t) {
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof Error) throw (Error) t;
            throw new RuntimeException(t);
        }
    }

    private static void invokeShutdown(Object browser) {
        try {
            if (Holder.SHUTDOWN_METHOD != null) {
                Holder.SHUTDOWN_METHOD.invoke(browser);
            }
        } catch (Throwable t) {
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            if (t instanceof Error) throw (Error) t;
            throw new RuntimeException(t);
        }
    }

    private static void logBrowserFailure(Object browser, Throwable failure) {
        LOGGER.warn("Rinku browser render-thread lifecycle operation failed.", failure);
    }

}
