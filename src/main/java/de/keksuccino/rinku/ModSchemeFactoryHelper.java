package de.keksuccino.rinku;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefApp;
import org.cef.handler.CefResourceHandler;

/**
 * 把对 JCEF 类的直接引用（{@link CefApp}、{@link CefResourceHandler} 等）
 * 从 {@link Rinku} 主类中隔离出来。虽然 Rinku.initialize() 是在下载完 JCEF 之后才被调用，
 * 但把所有 JCEF 强转和具体 JCEF API 调用集中到一个类里能降低"意外触发早期解析"的风险。
 */
final class ModSchemeFactoryHelper {

    private static final Logger LOGGER = LogManager.getLogger("ModScheme");

    private ModSchemeFactoryHelper() {}

    static boolean registerFactory(CefApp app) {
        LOGGER.info("[ModScheme] Calling CefApp.registerSchemeHandlerFactory: scheme=mod, domain=\"\"");
        boolean ok = app.registerSchemeHandlerFactory(
            "mod",
            "",
            (browser, frame, url, request) -> {
                LOGGER.debug("[ModScheme] factory called, request URL={}", request.getURL());
                return (CefResourceHandler) ModScheme.createHandler(request.getURL());
            });
        LOGGER.info("[ModScheme] registerSchemeHandlerFactory -> {}", ok);
        return ok;
    }

}
