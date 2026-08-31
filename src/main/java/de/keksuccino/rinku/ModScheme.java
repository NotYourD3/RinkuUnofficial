package de.keksuccino.rinku;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;

public class ModScheme {
    private static final Logger LOGGER = LogManager.getLogger("ModScheme");
    private static Constructor<?> HANDLER_CTOR;

    public static Object createHandler(String url) {
        try {
            if (HANDLER_CTOR == null) {
                Class<?> handlerCls = Class.forName("de.keksuccino.rinku.ModSchemeHandler", true, ModScheme.class.getClassLoader());
                HANDLER_CTOR = handlerCls.getDeclaredConstructor(String.class);
            }
            return HANDLER_CTOR.newInstance(url);
        } catch (Throwable t) {
            LOGGER.error("Failed to instantiate ModSchemeHandler for URL: " + url, t);
            throw new RuntimeException(t);
        }
    }
}
