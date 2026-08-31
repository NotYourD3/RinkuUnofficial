package de.keksuccino.rinku;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.callback.CefCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class ModSchemeHandler implements CefResourceHandler {
    private static final Logger LOGGER = LogManager.getLogger("ModScheme");

    private String contentType = null;
    private InputStream is = null;
    private final String url;

    public ModSchemeHandler(String url) {
        this.url = url;
    }

    @Override
    public boolean processRequest(CefRequest cefRequest, CefCallback cefCallback) {
        String url = this.url.substring("mod://".length());

        int pos = url.indexOf('/');
        if (pos < 0) {
            cefCallback.cancel();
            return false;
        }

        String mod = removeSlashes(url.substring(0, pos));
        String loc = removeSlashes(url.substring(pos + 1));

        if (mod.length() <= 0 || loc.length() <= 0 || mod.charAt(0) == '.' || loc.charAt(0) == '.') {
            LOGGER.warn("Invalid URL " + url);
            cefCallback.cancel();
            return false;
        }

        is = ModSchemeHandler.class.getClassLoader().getResourceAsStream("/assets/" + mod.toLowerCase(Locale.US) + "/html/" + loc.toLowerCase(Locale.US));
        if (is == null) {
            LOGGER.warn("Resource " + url + " NOT found!");
            cefCallback.cancel();
            return false;
        }

        contentType = null;
        pos = loc.lastIndexOf('.');
        if (pos >= 0 && pos < loc.length() - 2)
            contentType = MIMEUtil.mimeFromExtension(loc.substring(pos + 1));

        cefCallback.Continue();
        return true;
    }

    private static String removeSlashes(String loc) {
        int i = 0;
        while (i < loc.length() && loc.charAt(i) == '/')
            i++;
        return loc.substring(i);
    }

    @Override
    public void getResponseHeaders(CefResponse cefResponse, IntRef contentLength, StringRef redir) {
        if (contentType != null)
            cefResponse.setMimeType(contentType);

        cefResponse.setStatus(200);
        cefResponse.setStatusText("OK");
        contentLength.set(0);
    }

    @Override
    public boolean readResponse(byte[] output, int bytesToRead, IntRef bytesRead, CefCallback cefCallback) {
        try {
            int ret = is.read(output, 0, bytesToRead);
            if (ret <= 0) {
                is.close();
                bytesRead.set(0);
                return false;
            }
            bytesRead.set(ret);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to read mod scheme resource stream for URL {}", this.url, e);
            try {
                is.close();
            } catch (Throwable ignored) {
            }
            return false;
        }
    }

    @Override
    public void cancel() {
        try {
            is.close();
        } catch (Throwable ignored) {
        }
    }
}
