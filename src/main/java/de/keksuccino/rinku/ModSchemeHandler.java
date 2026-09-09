package de.keksuccino.rinku;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

public class ModSchemeHandler implements CefResourceHandler {

    private static final Logger LOGGER = LogManager.getLogger("ModScheme");

    private String contentType = null;
    private InputStream is = null;
    private int httpStatus = 200;
    private String httpStatusText = "OK";
    private final String url;

    public ModSchemeHandler(String url) {
        this.url = url;
    }

    private static InputStream getResource(String path) {
        // LaunchClassLoader / Forge 环境下，优先用上下文 ClassLoader（通常是加载 mod 的那个）
        ClassLoader ctx = Thread.currentThread()
            .getContextClassLoader();
        if (ctx != null) {
            InputStream in = ctx.getResourceAsStream(path);
            if (in != null) return in;
        }
        // Fallback 1: 本类自身的 ClassLoader
        ClassLoader self = ModSchemeHandler.class.getClassLoader();
        if (self != null && self != ctx) {
            InputStream in = self.getResourceAsStream(path);
            if (in != null) return in;
        }
        // Fallback 2: 系统 ClassLoader（某些开发环境 / 简单 classpath 场景）
        try {
            InputStream in = ClassLoader.getSystemResourceAsStream(path);
            if (in != null) return in;
        } catch (Throwable ignored) {}
        // Fallback 3: 对 Minecraft.class 的 ClassLoader（LaunchWrapper 环境）
        try {
            ClassLoader mc = Class.forName("net.minecraft.client.Minecraft")
                .getClassLoader();
            if (mc != null && mc != ctx && mc != self) {
                InputStream in = mc.getResourceAsStream(path);
                if (in != null) return in;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public boolean processRequest(CefRequest cefRequest, CefCallback cefCallback) {
        String url = this.url.substring("mod://".length());

        // 处理 URL 中的 query 参数 / hash（CEF 可能带 ?query 或 #fragment）
        int qPos = Math.min(
            url.indexOf('?') >= 0 ? url.indexOf('?') : Integer.MAX_VALUE,
            url.indexOf('#') >= 0 ? url.indexOf('#') : Integer.MAX_VALUE);
        if (qPos != Integer.MAX_VALUE) url = url.substring(0, qPos);

        int pos = url.indexOf('/');
        if (pos < 0) {
            serve404("Malformed mod URL (no modid/filename separators): " + this.url);
            cefCallback.Continue();
            return true;
        }

        String mod = removeSlashes(url.substring(0, pos));
        String loc = removeSlashes(url.substring(pos + 1));

        if (mod.length() <= 0 || loc.length() <= 0 || mod.charAt(0) == '.' || loc.charAt(0) == '.') {
            LOGGER.warn("[ModScheme] Invalid URL: {}", this.url);
            serve404("Invalid mod URL: " + this.url);
            cefCallback.Continue();
            return true;
        }

        // 注意：ClassLoader.getResourceAsStream() 的路径**不能以 '/' 开头**。
        String resourcePath = "assets/" + mod.toLowerCase(Locale.US) + "/" + loc.toLowerCase(Locale.US);
        LOGGER.debug("[ModScheme] Looking up resource via ClassLoader: {}", resourcePath);

        is = getResource(resourcePath);
        if (is == null) {
            LOGGER.warn("[ModScheme] Resource NOT found: {} (looked for {})", this.url, resourcePath);
            serve404(
                "Rinku Mod-Scheme: Resource not found\n\n" + "Requested URL: "
                    + this.url
                    + "\n"
                    + "Resource path: "
                    + resourcePath
                    + "\n\n"
                    + "Make sure the file exists in your mod JAR at assets/<modid>/html/<filename>.html");
            cefCallback.Continue();
            return true;
        }

        contentType = null;
        pos = loc.lastIndexOf('.');
        if (pos >= 0 && pos < loc.length() - 1) {
            String ext = loc.substring(pos + 1);
            contentType = MIMEUtil.mimeFromExtension(ext);
            LOGGER.debug("[ModScheme] Resolved content-type: {} for extension: {}", contentType, ext);
        }

        httpStatus = 200;
        httpStatusText = "OK";
        cefCallback.Continue();
        return true;
    }

    private void serve404(String message) {
        httpStatus = 404;
        httpStatusText = "Not Found";
        contentType = "text/html";
        String body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>404 - Resource not found</title>"
            + "<style>body{font-family:Segoe UI,Arial,sans-serif;padding:40px;color:#333;background:#eee}"
            + "h1{color:#b00}pre{background:#fff;padding:12px;border:1px solid #ccc;white-space:pre-wrap}</style>"
            + "</head><body><h1>404 &mdash; mod:// resource not found</h1><pre>"
            + escapeHtml(message)
            + "</pre></body></html>";
        is = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }

    private static String escapeHtml(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '&':
                    sb.append("&amp;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String removeSlashes(String loc) {
        int i = 0;
        while (i < loc.length() && loc.charAt(i) == '/') i++;
        return loc.substring(i);
    }

    @Override
    public void getResponseHeaders(CefResponse cefResponse, IntRef contentLength, StringRef redir) {
        if (contentType != null) cefResponse.setMimeType(contentType);
        cefResponse.setStatus(httpStatus);
        cefResponse.setStatusText(httpStatusText);
        contentLength.set(0);
    }

    @Override
    public boolean readResponse(byte[] output, int bytesToRead, IntRef bytesRead, CefCallback cefCallback) {
        if (is == null) {
            bytesRead.set(0);
            return false;
        }
        try {
            int ret = is.read(output, 0, bytesToRead);
            if (ret <= 0) {
                is.close();
                is = null;
                bytesRead.set(0);
                return false;
            }
            bytesRead.set(ret);
            return true;
        } catch (IOException e) {
            LOGGER.error("[ModScheme] Failed to read mod scheme resource stream for URL {}", this.url, e);
            try {
                is.close();
            } catch (Throwable ignored) {}
            is = null;
            return false;
        }
    }

    @Override
    public void cancel() {
        try {
            if (is != null) is.close();
        } catch (Throwable ignored) {}
        is = null;
    }

}
