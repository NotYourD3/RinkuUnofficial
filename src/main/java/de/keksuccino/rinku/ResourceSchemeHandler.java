package de.keksuccino.rinku;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

/**
 * "resource://" scheme 的处理器，走 Minecraft 原版 {@link IResourceManager} 查找资源。
 * <p>
 * URL 格式：{@code resource://<domain>/<path>}
 * <p>
 * 例如 {@code resource://minecraft/textures/blocks/stone.png} 会被解析为
 * {@code new ResourceLocation("minecraft", "textures/blocks/stone.png")}。
 * <p>
 * 与 {@link ModSchemeHandler}（直接从 mod jar 的 ClassLoader 读取，绕过资源包系统）不同，
 * 本处理器走原版资源管理器，因此资源包可以覆盖同名资源。
 * <p>
 * 注意：1.7.10 的 {@link IResourceManager} 实现不是线程安全的（内部使用普通 HashMap / ArrayList），
 * 而 CEF 的 {@code processRequest} 运行在 CEF IO 线程上。因此资源查找通过
 * {@link Minecraft#func_152344_a(Runnable)} 调度到 Minecraft 主线程执行，完成后再回调
 * {@link CefCallback#Continue()}。
 */
public class ResourceSchemeHandler implements CefResourceHandler {

    private static final Logger LOGGER = LogManager.getLogger("ResourceScheme");

    private String contentType = null;
    private InputStream is = null;
    private int httpStatus = 200;
    private String httpStatusText = "OK";
    private final String url;

    public ResourceSchemeHandler(String url) {
        this.url = url;
    }

    @Override
    public boolean processRequest(CefRequest cefRequest, CefCallback cefCallback) {
        String url = this.url.substring("resource://".length());

        // 处理 URL 中的 query 参数 / hash（CEF 可能带 ?query 或 #fragment）
        int qPos = Math.min(
            url.indexOf('?') >= 0 ? url.indexOf('?') : Integer.MAX_VALUE,
            url.indexOf('#') >= 0 ? url.indexOf('#') : Integer.MAX_VALUE);
        if (qPos != Integer.MAX_VALUE) url = url.substring(0, qPos);

        int pos = url.indexOf('/');
        if (pos < 0) {
            serve404("Malformed resource URL (no domain/path separator): " + this.url);
            cefCallback.Continue();
            return true;
        }

        String domain = removeSlashes(url.substring(0, pos));
        String path = removeSlashes(url.substring(pos + 1));

        if (domain.length() <= 0 || path.length() <= 0 || domain.charAt(0) == '.' || path.charAt(0) == '.') {
            LOGGER.warn("[ResourceScheme] Invalid URL: {}", this.url);
            serve404("Invalid resource URL: " + this.url);
            cefCallback.Continue();
            return true;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            serve404("Minecraft instance not available");
            cefCallback.Continue();
            return true;
        }

        // IResourceManager 不是线程安全的，必须在 Minecraft 主线程访问。
        // 这里通过 func_152344_a (1.7.10 的 addScheduledTask 等价物) 调度到主线程，
        // 完成后再调用 cefCallback.Continue() 通知 CEF 继续。processRequest 返回 true 表示异步处理。
        final String finalDomain = domain.toLowerCase(Locale.US);
        final String finalPath = path;
        mc.func_152344_a(() -> {
            try {
                IResourceManager rm = mc.getResourceManager();
                ResourceLocation loc = new ResourceLocation(finalDomain, finalPath);
                IResource res = rm.getResource(loc);
                is = res.getInputStream();

                contentType = null;
                int dotPos = finalPath.lastIndexOf('.');
                if (dotPos >= 0 && dotPos < finalPath.length() - 1) {
                    String ext = finalPath.substring(dotPos + 1);
                    contentType = MIMEUtil.mimeFromExtension(ext);
                    LOGGER.debug("[ResourceScheme] Resolved content-type: {} for extension: {}", contentType, ext);
                }

                httpStatus = 200;
                httpStatusText = "OK";
            } catch (FileNotFoundException e) {
                LOGGER.warn("[ResourceScheme] Resource NOT found: {}", this.url);
                serve404(
                    "Rinku Resource-Scheme: Resource not found\n\n" + "Requested URL: "
                        + this.url
                        + "\n"
                        + "ResourceLocation: "
                        + finalDomain
                        + ":"
                        + finalPath
                        + "\n\n"
                        + "Make sure the file exists at assets/<domain>/"
                        + finalPath
                        + " (resource packs may override it).");
            } catch (IOException e) {
                LOGGER.error("[ResourceScheme] Failed to read resource for URL {}", this.url, e);
                serve404(
                    "Rinku Resource-Scheme: IO error reading resource\n\n" + "Requested URL: "
                        + this.url
                        + "\n"
                        + "Error: "
                        + e.getMessage());
            } finally {
                cefCallback.Continue();
            }
        });

        return true;
    }

    private void serve404(String message) {
        httpStatus = 404;
        httpStatusText = "Not Found";
        contentType = "text/html";
        String body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>404 - Resource not found</title>"
            + "<style>body{font-family:Segoe UI,Arial,sans-serif;padding:40px;color:#333;background:#eee}"
            + "h1{color:#b00}pre{background:#fff;padding:12px;border:1px solid #ccc;white-space:pre-wrap}</style>"
            + "</head><body><h1>404 &mdash; resource:// resource not found</h1><pre>"
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
            LOGGER.error("[ResourceScheme] Failed to read resource stream for URL {}", this.url, e);
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
