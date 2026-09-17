package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.jsonrpc.Launcher;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Gère le cycle de vie d'une instance unique de serveur de langage :
 * launcher LSP4J, poignée de main initialize et négociation de capacités.
 *
 * <p>Un wrapper par définition de serveur. Plusieurs {@link LspEditor}
 * peuvent partager le même wrapper (un serveur gère plusieurs fichiers).
 */
public class LanguageServerWrapper {

    private final LanguageServerDefinition definition;
    private final String workspacePath;
    private final ExecutorService executor;
    private LanguageServer server;
    /**
     * volatile — écrite depuis le thread exécuteur (rappel d'initialize)
     * et lue depuis les threads UI/LSP. Sans cela, un lecteur pourrait voir
     * une carte de capacités null/périmée APRÈS que initialized est passé
     * à true.
     */
    private volatile ServerCapabilities capabilities;
    private StreamConnectionProvider connectionProvider;
    private volatile boolean initialized = false;
    /**
     * Le launcher — construit avec {@link CodeIdeLanguageServer} comme
     * interface remote (la méthode custom superDefinition doit figurer
     * dans la carte de désérialisation du client — cf.
     * CodeIdeLanguageServer) ; exposé aussi pour son RemoteEndpoint
     * (requêtes LSP personnalisées par nom).
     */
    private Launcher<CodeIdeLanguageServer> launcher;
    /** L'instance du client, pour que LspEditor puisse se déclarer gestionnaire des diagnostics. */
    private DefaultLanguageClient client;

    LanguageServerWrapper(LanguageServerDefinition definition, String workspacePath,
                          ExecutorService executor) {
        this.definition = definition;
        this.workspacePath = workspacePath;
        this.executor = executor;
    }

    /**
     * Démarre le serveur et effectue la poignée de main initialize LSP.
     * Retourne un futur complété quand le serveur est prêt.
     * <p>Envoie la notification {@code initialized} après la poignée de main
     * initialize, et définit les capacités client. Sans cela, de nombreux
     * serveurs (dont EmmyLua) refusent de servir les requêtes de complétion
     * et répondent « Internal error ».
     */
    public synchronized CompletableFuture<Void> start() {
        if (initialized) return CompletableFuture.completedFuture(null);
        // Démarre la connexion.
        connectionProvider = definition.createConnectionProvider(workspacePath);
        StreamConnectionProvider.StreamPair streams = connectionProvider.start();
        // Crée le launcher LSP4J.
        // Interface remote = CodeIdeLanguageServer (étend
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
        // Poignée de main initialize.
        InitializeParams params = new InitializeParams();
        params.setRootUri("file://" + workspacePath);
        // Définit workspaceFolders — EmmyLua s'en sert pour enregistrer la
        // racine de l'espace de travail et résoudre les fichiers source.
        // Sans lui, le serveur ne trouve pas l'espace de travail et certaines
        // fonctionnalités (diagnostics, go-to-def inter-fichiers) ne marchent
        // pas.
        org.eclipse.lsp4j.WorkspaceFolder wsFolder = new org.eclipse.lsp4j.WorkspaceFolder();
        wsFolder.setUri("file://" + workspacePath);
        wsFolder.setName(new java.io.File(workspacePath).getName());
        params.setWorkspaceFolders(java.util.Collections.singletonList(wsFolder));
        // Définit les capacités client — indique au serveur ce que nous
        // prenons en charge.
        org.eclipse.lsp4j.ClientCapabilities clientCaps = new org.eclipse.lsp4j.ClientCapabilities();
        org.eclipse.lsp4j.WorkspaceClientCapabilities wsCaps = new org.eclipse.lsp4j.WorkspaceClientCapabilities();
        // Indique au serveur que didChangeConfiguration est pris en charge.
        org.eclipse.lsp4j.DidChangeConfigurationCapabilities dccCaps =
            new org.eclipse.lsp4j.DidChangeConfigurationCapabilities();
        wsCaps.setDidChangeConfiguration(dccCaps);
        // Prise en charge des dossiers d'espace de travail (Boolean, pas
        // une classe en 0.22.0).
        wsCaps.setWorkspaceFolders(Boolean.TRUE);
        wsCaps.setConfiguration(Boolean.TRUE);
        clientCaps.setWorkspace(wsCaps);
        org.eclipse.lsp4j.TextDocumentClientCapabilities tdCaps = new org.eclipse.lsp4j.TextDocumentClientCapabilities();
        // Complétion : contexte pris en charge (caractères déclencheurs
        // + invocation).
        org.eclipse.lsp4j.CompletionCapabilities completionCaps =
            new org.eclipse.lsp4j.CompletionCapabilities();
        tdCaps.setCompletion(completionCaps);
        // Hover : markdown pris en charge.
        org.eclipse.lsp4j.HoverCapabilities hoverCaps =
            new org.eclipse.lsp4j.HoverCapabilities();
        hoverCaps.setContentFormat(java.util.Arrays.asList(
            org.eclipse.lsp4j.MarkupKind.MARKDOWN, org.eclipse.lsp4j.MarkupKind.PLAINTEXT));
        tdCaps.setHover(hoverCaps);
        // Aide à la signature.
        org.eclipse.lsp4j.SignatureHelpCapabilities sigCaps =
            new org.eclipse.lsp4j.SignatureHelpCapabilities();
        tdCaps.setSignatureHelp(sigCaps);
        // Diagnostics publiés — indique au serveur que les infos liées sont
        // prises en charge.
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
            // Envoie la notification initialized — exigée par la spécification
            // LSP. Sans elle, les serveurs peuvent refuser de servir les
            // requêtes.
            try {
                org.eclipse.lsp4j.InitializedParams initParams = new org.eclipse.lsp4j.InitializedParams();
                server.initialized(initParams);
            } catch (Exception e) {
                // Certains serveurs n'en ont peut-être pas besoin — journaliser
                // sans échouer.
                android.util.Log.w("LspWrapper", "initialized notification failed: " + e.getMessage());
            }
        }).exceptionally(throwable -> {
            initialized = false;
            throw new RuntimeException("LSP initialize failed", throwable);
        });
    }

    /** Retourne le proxy du serveur de langage, ou null si non démarré. */
    public LanguageServer getServer() { return server; }

    /** Retourne l'instance du client pour le transfert des diagnostics. */
    public DefaultLanguageClient getClient() { return client; }

    /** Retourne les capacités du serveur, ou null si pas encore initialisé. */
    public ServerCapabilities getCapabilities() { return capabilities; }

    /** Retourne true si le serveur est initialisé et prêt. */
    public boolean isInitialized() { return initialized; }

    /**
     * Le serveur VU À TRAVERS l'interface étendue (typé
     * {@link CodeIdeLanguageServer}), ou null si non démarré. Le proxy
     * implémente toujours CodeIdeLanguageServer (interface remote du
     * launcher) — le cast est sûr après start().
     */
    public CodeIdeLanguageServer getExtendedServer() {
        return launcher != null ? launcher.getRemoteProxy() : null;
    }

    /**
     * Le RemoteEndpoint du launcher : permet d'envoyer des requêtes LSP
     * PERSONNALISÉES par leur nom (ex.
     * {@code textDocument/superDefinition}) sans interface proxy dédiée.
     * Un serveur tiers qui ne connaît pas la méthode répond « method not
     * found » — l'appelant avale l'erreur et rend vide.
     */
    public org.eclipse.lsp4j.jsonrpc.RemoteEndpoint getRemoteEndpoint() {
        Launcher<CodeIdeLanguageServer> l = launcher;
        return l != null ? l.getRemoteEndpoint() : null;
    }

    /**
     * Arrête le serveur.
     *
     * <p>(1) La requête shutdown est BORNÉE — un
     * {@code server.shutdown().join()} bloquerait le thread appelant
     * À JAMAIS si le processus serveur était figé, ce qui sur Android
     * signifie un ANR garanti quand l'arrêt vient du thread principal
     * (destruction de l'activité). On attend au plus 2 s, puis on force la
     * fermeture des flux quoi qu'il arrive — un serveur qui a ignoré la
     * requête polie est tué en fermant son stdin. (2) synchronized comme
     * start() — un arrêt concourant avec un start() pourrait sinon annuler
     * le launcher entre sa création et l'appel initialize.
     */
    public synchronized void shutdown() {
        if (server != null) {
            try {
                server.shutdown().get(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // Expiration / erreur / interruption — continuer vers exit +
                // fermeture des flux. Un serveur figé ne doit pas figer
                // l'appelant.
            }
            try {
                if (definition.callExitForLanguageServer()) {
                    server.exit();
                }
            } catch (Exception ignored) {
                // exit() sur un processus déjà mort lève une exception —
                // sans importance.
            }
            server = null;
        }
        if (connectionProvider != null) {
            try {
                connectionProvider.stop();
            } catch (Exception ignored) {
                // Au mieux — les flux peuvent déjà être fermés.
            }
            connectionProvider = null;
        }
        launcher = null;
        initialized = false;
    }
}
