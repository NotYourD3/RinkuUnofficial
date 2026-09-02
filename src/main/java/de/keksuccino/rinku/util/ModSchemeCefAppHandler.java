package de.keksuccino.rinku.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.callback.CefSchemeRegistrar;
import org.cef.handler.CefAppHandlerAdapter;

/**
 * 在 CefApp 初始化的早期阶段向 CEF（以及所有子进程）注册 "mod" 自定义 scheme。<p>
 *
 * 如果不通过 {@link CefSchemeRegistrar#addCustomScheme} 显式注册，
 * 虽然 browser 进程中可以用 {@code registerSchemeHandlerFactory}
 * 挂接处理工厂，但 renderer / zygote 等子进程并不知道 "mod" 这个 scheme，
 * 于是在网络栈层面就被拒掉了 —— 表现为 {@code ERR_UNKNOWN_URL_SCHEME}。
 * <p>
 * 这个 Handler 必须在 {@code CefApp.getInstance()} 之前通过
 * {@code CefApp.addAppHandler()} 设置，否则 CEF 的子进程回调不会触发。
 */
public class ModSchemeCefAppHandler extends CefAppHandlerAdapter {

    private static final Logger LOGGER = LogManager.getLogger("ModScheme");

    public ModSchemeCefAppHandler(String[] args) {
        super(args);
    }

    @Override
    public void onRegisterCustomSchemes(CefSchemeRegistrar registrar) {
        LOGGER.info("[ModScheme] onRegisterCustomSchemes callback called (all processes)");

        // 参数意义参考 CEF / JCEF 文档：
        //   schemeName, isStandard, isLocal, isDisplayIsolated,
        //   isSecure, isCorsEnabled, isCspBypassing, isFetchEnabled
        //
        // "mod://" 是一个"类 file" 的本地资源协议：
        //  - 标准（不需要特殊的 scheme 语法处理）= true
        //  - 本地资源 = true
        //  - 不隔离显示 = false
        //  - 安全（等同于 https 的能力，允许访问 canvas/DOM 等）= true
        //  - 允许跨域 CORS  = true (便于页面 fetch 其他 mod)
        //  - 不绕过 CSP = false
        //  - 允许 Fetch API / Service Worker 访问 = true
        boolean registered = registrar.addCustomScheme(
            "mod",
            true,    // isStandard
            true,    // isLocal
            false,   // isDisplayIsolated
            true,    // isSecure
            true,    // isCorsEnabled
            false,   // isCspBypassing
            true     // isFetchEnabled
        );
        LOGGER.info("[ModScheme] registrar.addCustomScheme(\"mod\", ...) -> " + registered);
    }
}
