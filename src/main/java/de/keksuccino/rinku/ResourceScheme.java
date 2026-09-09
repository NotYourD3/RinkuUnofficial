package de.keksuccino.rinku;

import java.lang.reflect.Constructor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class ResourceScheme {

    private static final Logger LOGGER = LogManager.getLogger("ResourceScheme");

    private static Constructor<?> HANDLER_CTOR;
    private static boolean init = false;

    private ResourceScheme() {}

    private static void init() {
        if (init) return;
        try {
            Class<?> handlerClass = Class.forName("de.keksuccino.rinku.ResourceSchemeHandler");
            HANDLER_CTOR = handlerClass.getConstructor(String.class);
        } catch (Throwable t) {
            LOGGER.error(
                "[ResourceScheme] Failed to reflect ResourceSchemeHandler constructor! resource:// scheme will not work.",
                t);
            HANDLER_CTOR = null;
        } finally {
            init = true;
        }
    }

    /**
     * 注意：此方法故意返回 {@link Object} 而不是 {@code CefResourceHandler}，
     * 因为 Rinku 使用的是运行时动态下载 JCEF 的机制。
     * 如果本类直接引用 JCEF 类，那么在 JCEF 尚未下载完成的阶段
     * （例如 Minecraft 构造期间启动下载器线程时），
     * {@code LaunchClassLoader} 会因为无法解析 JCEF 类而抛出 {@link NoClassDefFoundError}。
     * 通过反射 + 二级类（ResourceSchemeHandler）可以安全地延迟加载。
     */
    public static Object createHandler(String url) {
        init();
        if (HANDLER_CTOR == null) {
            LOGGER.error("[ResourceScheme] createHandler called but HANDLER_CTOR is not initialized!");
            return null;
        }
        try {
            return HANDLER_CTOR.newInstance(url);
        } catch (Throwable t) {
            LOGGER.error("[ResourceScheme] Failed to instantiate ResourceSchemeHandler for URL: " + url, t);
            return null;
        }
    }

}
