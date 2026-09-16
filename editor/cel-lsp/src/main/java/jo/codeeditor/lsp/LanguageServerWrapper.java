package jo.codeeditor.lsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Manages the lifecycle of a single language server instance. Handles the
 * LSP4J launcher, the initialize handshake, and capability negotiation.
 *
 * <p>One wrapper per server definition. Multiple {@link LspEditor}s can
 * share the same wrapper (one server handles multiple files).
 *
 * @since v2.2.0
 */
public class LanguageServerWrapper {

    private final LanguageServerDefinition definition;
    private final String workspacePath;
    private final ExecutorService executor;
    private LanguageServer server;
    /**
     * v3.34.0: volatile — written from the executor thread (initialize
     * callback) and read from UI/LSP threads. Without it a reader could
     * see a null/stale capabilities map AFTER initialized flipped true.
     */
    private volatile ServerCapabilities capabilities;
    private StreamConnectionProvider connectionProvider;
    private volatile boolean initialized = false;
    /**
     * v2.37 : le launcher — construit avec {@link CodeIdeLanguageServer}
     * comme interface remote (la méthode custom superDefinition doit
     * figurer dans la carte de désérialisation du client — cf.
     * CodeIdeLanguageServer) ; exposé aussi pour son RemoteEndpoint
     * (requêtes LSP personnalisées par nom).
     */
    private Launcher<CodeIdeLanguageServer> launcher;
    /** v3.3.2: The client instance, so LspEditor can set itself as the diagnostic handler. */
    private DefaultLanguageClient client;

    LanguageServerWrapper(LanguageServerDefinition definition, String workspacePath,
                          ExecutorService executor) {
        this.definition = definition;
        this.workspacePath = workspacePath;
        this.executor = executor;
    }

    /**
     * Starts the server and performs the LSP initialize handshake.
     * Returns a future that completes when the server is ready.
     * <p>v3.3.4 fix: Send the {@code initialized} notification after the
     * initialize handshake, and set client capabilities. Without these,
     * many servers (including EmmyLua) refuse to serve completion requests
     * and return "Internal error".
     */
    public synchronized CompletableFuture<Void> start() {
        if (initialized) return CompletableFuture.completedFuture(null);
        // Start the connection.
        connectionProvider = definition.createConnectionProvider(workspacePath);
        StreamConnectionProvider.StreamPair streams = connectionProvider.start();
        // Create the LSP4J launcher.
        // ★ v2.37 : interface remote = CodeIdeLanguageServer (étend
        // LanguageServer — les méthodes standard sont inchangées) pour que
        // la carte de désérialisation du client connaisse la méthode custom
        // textDocument/superDefinition ET son type de retour — sans elle,
        // le result des réponses custom est silencieusement jeté (null).
        LanguageClient client = new DefaultLanguageClient();
        this.client = (DefaultLanguageClient) client;
        Launcher<CodeIdeLanguageServer> launcher = Launcher.createLauncher(
            client, CodeIdeLanguageServer.class, streams.input, streams.output, executor, null);
        launcher.startListening();
        server = launcher.getRemoteProxy();
        this.launcher = launcher;
        // Initialize handshake.
        InitializeParams params = new InitializeParams();
        params.setRootUri("file://" + workspacePath);
        // v3.3.7: Set workspaceFolders — EmmyLua uses this to register the
        // workspace root and resolve source files. Without it, the server
        // can't find the workspace and some features (diagnostics, go-to-def
        // across files) don't work.
        org.eclipse.lsp4j.WorkspaceFolder wsFolder = new org.eclipse.lsp4j.WorkspaceFolder();
        wsFolder.setUri("file://" + workspacePath);
        wsFolder.setName(new java.io.File(workspacePath).getName());
        params.setWorkspaceFolders(java.util.Collections.singletonList(wsFolder));
        // v3.3.4: Set client capabilities — tells the server what we support.
        org.eclipse.lsp4j.ClientCapabilities clientCaps = new org.eclipse.lsp4j.ClientCapabilities();
        org.eclipse.lsp4j.WorkspaceClientCapabilities wsCaps = new org.eclipse.lsp4j.WorkspaceClientCapabilities();
        // v3.3.7: Tell the server we support didChangeConfiguration.
        org.eclipse.lsp4j.DidChangeConfigurationCapabilities dccCaps =
            new org.eclipse.lsp4j.DidChangeConfigurationCapabilities();
        wsCaps.setDidChangeConfiguration(dccCaps);
        // v3.3.7: Support workspace folders (Boolean, not a class in 0.22.0).
        wsCaps.setWorkspaceFolders(Boolean.TRUE);
        wsCaps.setConfiguration(Boolean.TRUE);
        clientCaps.setWorkspace(wsCaps);
        org.eclipse.lsp4j.TextDocumentClientCapabilities tdCaps = new org.eclipse.lsp4j.TextDocumentClientCapabilities();
        // Completion: we support context (trigger characters + invoked).
        org.eclipse.lsp4j.CompletionCapabilities completionCaps =
            new org.eclipse.lsp4j.CompletionCapabilities();
        tdCaps.setCompletion(completionCaps);
        // Hover: we support markdown.
        org.eclipse.lsp4j.HoverCapabilities hoverCaps =
            new org.eclipse.lsp4j.HoverCapabilities();
        hoverCaps.setContentFormat(java.util.Arrays.asList(
            org.eclipse.lsp4j.MarkupKind.MARKDOWN, org.eclipse.lsp4j.MarkupKind.PLAINTEXT));
        tdCaps.setHover(hoverCaps);
        // Signature help.
        org.eclipse.lsp4j.SignatureHelpCapabilities sigCaps =
            new org.eclipse.lsp4j.SignatureHelpCapabilities();
        tdCaps.setSignatureHelp(sigCaps);
        // v3.3.7: Publish diagnostics — tell the server we support related info.
        org.eclipse.lsp4j.PublishDiagnosticsCapabilities pdCaps =
            new org.eclipse.lsp4j.PublishDiagnosticsCapabilities();
        pdCaps.setRelatedInformation(true);
        tdCaps.setPublishDiagnostics(pdCaps);
        clientCaps.setTextDocument(tdCaps);
        params.setCapabilities(clientCaps);
        if (definition.getInitializationOptions() != null) {
            params.setInitializationOptions(definition.getInitializationOptions());
        }
        CompletableFuture<InitializeResult> initFuture = server.initialize(params);
        return initFuture.thenAccept(result -> {
            capabilities = result.getCapabilities();
            initialized = true;
            // v3.3.4: Send the initialized notification — required by the LSP
            // spec. Without it, servers may refuse to serve requests.
            try {
                org.eclipse.lsp4j.InitializedParams initParams = new org.eclipse.lsp4j.InitializedParams();
                server.initialized(initParams);
            } catch (Exception e) {
                // Some servers might not need this — log but don't fail.
                android.util.Log.w("LspWrapper", "initialized notification failed: " + e.getMessage());
            }
        }).exceptionally(throwable -> {
            initialized = false;
            throw new RuntimeException("LSP initialize failed", throwable);
        });
    }

    /** Returns the language server proxy, or null if not started. */
    public LanguageServer getServer() { return server; }

    /** v3.3.2: Returns the client instance for diagnostic forwarding. */
    public DefaultLanguageClient getClient() { return client; }

    /** Returns the server capabilities, or null if not yet initialized. */
    public ServerCapabilities getCapabilities() { return capabilities; }

    /** Returns true if the server is initialized and ready. */
    public boolean isInitialized() { return initialized; }

    /**
     * v2.37 — le serveur VU À TRAVERS l'interface étendue (typé
     * {@link CodeIdeLanguageServer}), ou null si non démarré. Le proxy
     * implémente toujours CodeIdeLanguageServer (interface remote du
     * launcher) — le cast est sûr après start().
     */
    public CodeIdeLanguageServer getExtendedServer() {
        return launcher != null ? launcher.getRemoteProxy() : null;
    }

    /**
     * v2.37 — le RemoteEndpoint du launcher : permet d'envoyer des
     * requêtes LSP PERSONNALISÉES par leur nom (ex.
     * {@code textDocument/superDefinition}) sans interface proxy dédiée.
     * Un serveur tiers qui ne connaît pas la méthode répond « method not
     * found » — l'appelant avale l'erreur et rend vide.
     */
    public org.eclipse.lsp4j.jsonrpc.RemoteEndpoint getRemoteEndpoint() {
        Launcher<CodeIdeLanguageServer> l = launcher;
        return l != null ? l.getRemoteEndpoint() : null;
    }

    /**
     * Shuts down the server.
     *
     * <p>v3.34.0 fix: (1) the shutdown request is now BOUNDED — the old
     * {@code server.shutdown().join()} blocked the calling thread
     * FOREVER if the server process was hung, which on Android meant a
     * guaranteed ANR when shutdown was called from the main thread
     * (activity teardown). We now wait at most 2 s, then force-close the
     * streams regardless — a server that ignored the polite request is
     * killed by closing its stdin anyway. (2) synchronized to match
     * start() — a shutdown racing a start() could previously null the
     * launcher between its creation and the initialize call.
     */
    public synchronized void shutdown() {
        if (server != null) {
            try {
                server.shutdown().get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Timeout / error / interruption — fall through to exit +
                // stream close. A hung server must not hang the caller.
            }
            try {
                if (definition.callExitForLanguageServer()) {
                    server.exit();
                }
            } catch (Exception ignored) {
                // exit() on an already-dead process throws — irrelevant.
            }
            server = null;
        }
        if (connectionProvider != null) {
            try {
                connectionProvider.stop();
            } catch (Exception ignored) {
                // Best effort — the streams may already be closed.
            }
            connectionProvider = null;
        }
        launcher = null;
        initialized = false;
    }
}
