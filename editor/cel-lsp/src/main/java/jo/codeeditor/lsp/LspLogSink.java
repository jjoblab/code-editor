package jo.codeeditor.lsp;

/**
 * v3.3.3: Sink for LSP activity logs. The demo app implements this to
 * surface LSP request/response/timeout/error events in its log panel,
 * so the user can see exactly what's happening when a server connection
 * appears "stuck" or features don't fire.
 *
 * @since v3.3.3
 */
public interface LspLogSink {
    /**
     * Logs a single message. Implementations should be thread-safe —
     * LSP callbacks fire on the LSP4J executor thread, not the UI thread.
     */
    void log(String message);
}
