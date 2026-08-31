package de.keksuccino.rinku;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefAuthCallback;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefCallback;
import org.cef.callback.CefContextMenuParams;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefMenuModel;
import de.keksuccino.rinku.util.CefUtil;
import org.cef.handler.CefAudioHandler;
import org.cef.handler.CefContextMenuHandler;
import org.cef.handler.CefCookieAccessFilter;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefDownloadHandler;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefRequestHandler;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.misc.BoolRef;
import org.cef.misc.CefAudioParameters;
import org.cef.misc.DataPointer;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;
import org.cef.network.CefURLRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public class RinkuClient implements CefLoadHandler, CefContextMenuHandler, CefDisplayHandler, CefAudioHandler, CefDownloadHandler, CefRequestHandler {

    private static final Logger LOGGER = LogManager.getLogger("RinkuClient");

    private final CefClient handle;
    private final List<CefLoadHandler> loadHandlers = new CopyOnWriteArrayList<>();
    private final List<CefContextMenuHandler> contextMenuHandlers = new CopyOnWriteArrayList<>();
    private final List<CefDisplayHandler> displayHandlers = new CopyOnWriteArrayList<>();
    private final List<CefAudioHandler> audioHandlers = new CopyOnWriteArrayList<>();
    private final RinkuDownloadHandlerRelay downloadHandlerRelay = new RinkuDownloadHandlerRelay();
    private final DesktopHeadersResourceRequestHandler desktopHeadersHandler = new DesktopHeadersResourceRequestHandler();

    public RinkuClient(CefClient cefClient) {
        handle = cefClient;
        cefClient.addLoadHandler(this);
        cefClient.addContextMenuHandler(this);
        cefClient.addDisplayHandler(this);
        cefClient.addAudioHandler(this);
        cefClient.addDownloadHandler(this);
        cefClient.addRequestHandler(this);
    }

    public CefClient getHandle() {
        return handle;
    }

    public void addLoadHandler(CefLoadHandler handler) {
        loadHandlers.add(handler);
    }

    @Override
    public void onLoadingStateChange(CefBrowser browser, boolean isLoading, boolean canGoBack, boolean canGoForward) {
        for (CefLoadHandler loadHandler : loadHandlers)
            loadHandler.onLoadingStateChange(browser, isLoading, canGoBack, canGoForward);
    }

    @Override
    public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
        for (CefLoadHandler loadHandler : loadHandlers) loadHandler.onLoadStart(browser, frame, transitionType);
    }

    @Override
    public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
        for (CefLoadHandler loadHandler : loadHandlers) loadHandler.onLoadEnd(browser, frame, httpStatusCode);
    }

    @Override
    public void onLoadError(CefBrowser browser, CefFrame frame, ErrorCode errorCode, String errorText, String failedUrl) {
        for (CefLoadHandler loadHandler : loadHandlers)
            loadHandler.onLoadError(browser, frame, errorCode, errorText, failedUrl);
    }

    public void addContextMenuHandler(CefContextMenuHandler handler) {
        contextMenuHandlers.add(handler);
    }

    @Override
    public void onBeforeContextMenu(CefBrowser browser, CefFrame frame, CefContextMenuParams params, CefMenuModel model) {
        for (CefContextMenuHandler contextMenuHandler : contextMenuHandlers)
            contextMenuHandler.onBeforeContextMenu(browser, frame, params, model);
    }

    @Override
    public boolean onContextMenuCommand(CefBrowser browser, CefFrame frame, CefContextMenuParams params, int commandId, int eventFlags) {
        for (CefContextMenuHandler contextMenuHandler : contextMenuHandlers)
            if (contextMenuHandler.onContextMenuCommand(browser, frame, params, commandId, eventFlags))
                return true;
        return false;
    }

    @Override
    public void onContextMenuDismissed(CefBrowser browser, CefFrame frame) {
        for (CefContextMenuHandler contextMenuHandler : contextMenuHandlers)
            contextMenuHandler.onContextMenuDismissed(browser, frame);
    }

    public void addDisplayHandler(CefDisplayHandler handler) {
        displayHandlers.add(handler);
    }

    public void removeDisplayHandler(CefDisplayHandler handler) {
        displayHandlers.remove(handler);
    }

    @Override
    public void onAddressChange(CefBrowser browser, CefFrame frame, String url) {
        for (CefDisplayHandler displayHandler : displayHandlers) displayHandler.onAddressChange(browser, frame, url);
    }

    @Override
    public void onTitleChange(CefBrowser browser, String title) {
        for (CefDisplayHandler displayHandler : displayHandlers) displayHandler.onTitleChange(browser, title);
    }

    @Override
    public boolean onTooltip(CefBrowser browser, String text) {
        for (CefDisplayHandler displayHandler : displayHandlers)
            if (displayHandler.onTooltip(browser, text))
                return true;
        return false;
    }

    @Override
    public void onStatusMessage(CefBrowser browser, String value) {
        for (CefDisplayHandler displayHandler : displayHandlers) displayHandler.onStatusMessage(browser, value);
    }

    @Override
    public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
        for (CefDisplayHandler displayHandler : displayHandlers)
            if (displayHandler.onConsoleMessage(browser, level, message, source, line))
                return true;

        if (shouldForwardConsoleMessageToMcLog(level)) {
            logConsoleMessageToMcLog(browser, level, message, source, line);
        }

        // Always consume here so CEF doesn't bypass our filtering and spam the process console.
        return true;
    }

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        for (CefDisplayHandler displayHandler : displayHandlers)
            if (displayHandler.onCursorChange(browser, cursorType))
                return true;
        return false;
    }

    public void addAudioHandler(CefAudioHandler handler) {
        audioHandlers.add(handler);
    }

    @Override
    public boolean getAudioParameters(CefBrowser browser, CefAudioParameters params) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            if (audioHandler.getAudioParameters(browser, params))
                return true;
        }
        return false;
    }

    @Override
    public void onAudioStreamStarted(CefBrowser browser, CefAudioParameters params, int channels) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            audioHandler.onAudioStreamStarted(browser, params, channels);
        }
    }

    @Override
    public void onAudioStreamPacket(CefBrowser browser, DataPointer data, int frames, long pts) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            audioHandler.onAudioStreamPacket(browser, data, frames, pts);
        }
    }

    @Override
    public void onAudioStreamStopped(CefBrowser browser) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            audioHandler.onAudioStreamStopped(browser);
        }
    }

    @Override
    public void onAudioStreamError(CefBrowser browser, String text) {
        for (CefAudioHandler audioHandler : audioHandlers) {
            audioHandler.onAudioStreamError(browser, text);
        }
        LOGGER.warn("An audio stream threw an error: " + text);
    }

    public void addDownloadHandler(CefDownloadHandler handler) {
        downloadHandlerRelay.addHandler(handler);
    }

    @Override
    public boolean canDownload(CefBrowser browser, String url, String requestMethod) {
        return downloadHandlerRelay.canDownload(browser, url, requestMethod);
    }

    @Override
    public boolean onBeforeDownloadWithDecision(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
        return downloadHandlerRelay.onBeforeDownloadWithDecision(browser, downloadItem, suggestedName, callback);
    }

    @Override
    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {
        downloadHandlerRelay.onDownloadUpdated(browser, downloadItem, callback);
    }

    // ==================== CefRequestHandler implementation ====================

    @Override
    public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request, boolean user_gesture, boolean is_redirect) {
        return false;
    }

    @Override
    public boolean onOpenURLFromTab(CefBrowser browser, CefFrame frame, String target_url, boolean user_gesture) {
        return false;
    }

    @Override
    public CefResourceRequestHandler getResourceRequestHandler(CefBrowser browser, CefFrame frame, CefRequest request, boolean isNavigation, boolean isDownload, String requestInitiator, BoolRef disableDefaultHandling) {
        return desktopHeadersHandler;
    }

    @Override
    public boolean getAuthCredentials(CefBrowser browser, String origin_url, boolean isProxy, String host, int port, String realm, String scheme, CefAuthCallback callback) {
        return false;
    }

    @Override
    public boolean onCertificateError(CefBrowser browser, ErrorCode cert_error, String request_url, CefCallback callback) {
        return false;
    }

    @Override
    public void onRenderProcessTerminated(CefBrowser browser, TerminationStatus status, int error_code, String error_string) {
        LOGGER.warn("Render process terminated for browser {}: status={} error_code={} error_string={}",
                browser == null ? -1 : browser.getIdentifier(), status, error_code, error_string);
    }

    private static boolean shouldForwardConsoleMessageToMcLog(CefSettings.LogSeverity level) {
        CefSettings.LogSeverity threshold = Rinku.getSettings().getConsoleLogForwardingMinSeverity();
        CefSettings.LogSeverity effectiveThreshold = threshold == null
                ? CefSettings.LogSeverity.LOGSEVERITY_DISABLE
                : threshold;
        if (effectiveThreshold == CefSettings.LogSeverity.LOGSEVERITY_DISABLE) {
            return false;
        }

        CefSettings.LogSeverity effectiveLevel = level == null
                ? CefSettings.LogSeverity.LOGSEVERITY_DEFAULT
                : level;
        return getSeverityRank(effectiveLevel) >= getSeverityRank(effectiveThreshold);
    }

    private static int getSeverityRank(CefSettings.LogSeverity severity) {
        return switch (severity) {
            case LOGSEVERITY_VERBOSE -> 0;
            case LOGSEVERITY_DEFAULT, LOGSEVERITY_INFO -> 1;
            case LOGSEVERITY_WARNING -> 2;
            case LOGSEVERITY_ERROR, LOGSEVERITY_FATAL -> 3;
            case LOGSEVERITY_DISABLE -> 4;
        };
    }

    private static void logConsoleMessageToMcLog(
            CefBrowser browser,
            CefSettings.LogSeverity level,
            String message,
            String source,
            int line
    ) {
        int browserId = browser == null ? -1 : browser.getIdentifier();
        String sourceValue = source == null || source.trim().isEmpty() ? "<unknown>" : source;
        String messageValue = message == null ? "<null>" : message;
        CefSettings.LogSeverity effectiveLevel = level == null
                ? CefSettings.LogSeverity.LOGSEVERITY_DEFAULT
                : level;

        switch (effectiveLevel) {
            case LOGSEVERITY_VERBOSE -> LOGGER.debug("[CEF Console][{}] {}:{} - {}", browserId, sourceValue, line, messageValue);
            case LOGSEVERITY_DEFAULT, LOGSEVERITY_INFO ->
                    LOGGER.info("[CEF Console][{}] {}:{} - {}", browserId, sourceValue, line, messageValue);
            case LOGSEVERITY_WARNING ->
                    LOGGER.warn("[CEF Console][{}] {}:{} - {}", browserId, sourceValue, line, messageValue);
            case LOGSEVERITY_ERROR, LOGSEVERITY_FATAL ->
                    LOGGER.error("[CEF Console][{}] {}:{} - {}", browserId, sourceValue, line, messageValue);
            case LOGSEVERITY_DISABLE -> {
                // Nothing to forward.
            }
        }
    }

    /**
     * A CefResourceRequestHandler that enforces desktop-style request headers on every outgoing
     * request.  This is the most reliable place in the CEF handler stack to mutate a request's
     * headers (the request object is explicitly documented as mutable here).
     */
    private static class DesktopHeadersResourceRequestHandler implements CefResourceRequestHandler {
        @Override
        public CefCookieAccessFilter getCookieAccessFilter(CefBrowser browser, CefFrame frame, CefRequest request) {
            return null;
        }

        @Override
        public boolean onBeforeResourceLoad(CefBrowser browser, CefFrame frame, CefRequest request) {
            enforceDesktopRequestHeaders(request);
            return false;
        }

        @Override
        public CefResourceHandler getResourceHandler(CefBrowser browser, CefFrame frame, CefRequest request) {
            return null;
        }

        @Override
        public void onResourceRedirect(CefBrowser browser, CefFrame frame, CefRequest request, CefResponse response, StringRef new_url) {
        }

        @Override
        public boolean onResourceResponse(CefBrowser browser, CefFrame frame, CefRequest request, CefResponse response) {
            return false;
        }

        @Override
        public void onResourceLoadComplete(CefBrowser browser, CefFrame frame, CefRequest request, CefResponse response, CefURLRequest.Status status, long receivedContentLength) {
        }

        @Override
        public void onProtocolExecution(CefBrowser browser, CefFrame frame, CefRequest request, BoolRef allowOsExecution) {
        }
    }

    private static void enforceDesktopRequestHeaders(CefRequest request) {
        if (request == null) return;
        String desktopUA = CefUtil.getEffectiveDesktopUserAgent();
        if (desktopUA == null || desktopUA.isEmpty()) return;

        Map<String, String> headers = new HashMap<>();
        request.getHeaderMap(headers);

        headers.put("User-Agent", desktopUA);
        headers.put("Sec-CH-UA-Mobile", "?0");
        headers.remove("Sec-CH-UA-Platform-Version");
        headers.remove("Sec-CH-UA-Model");
        headers.remove("Sec-CH-UA-WoW64");

        request.setHeaderMap(headers);
    }
}
