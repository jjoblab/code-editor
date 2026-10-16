package jo.codeeditor.demo.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mini serveur de langage Java de la démo — <b>diagnostics uniquement</b>.
 *
 * <p>C'est la vitrine du module {@code :cel-lsp} : un VRAI serveur LSP
 * (protocole JSON-RPC via LSP4J, poignée de main {@code initialize},
 * notifications {@code didOpen}/{@code didChange}/{@code didClose},
 * publication {@code textDocument/publishDiagnostics}), implémenté en
 * ~200 lignes. Il tourne <b>in-process</b> derrière un
 * {@code InProcessStreamConnectionProvider} : le trafic traverse le
 * cadre LSP de la bibliothèque exactement comme avec un serveur externe
 * (sous-processus, socket), seul le transport change.</p>
 *
 * <p><b>Capacités annoncées :</b> {@code textDocumentSync = Full} — le
 * client renvoie le texte complet à chaque changement, le plus simple
 * et le plus robuste pour un mini serveur (le synchroniseur de
 * {@code cel-lsp} gère nativement ce mode). Aucune autre capacité :
 * pas de complétion, pas de hover — la démontre le contrat
 * « le serveur ne fait QUE des diagnostics », et les popups de
 * l'éditeur (complétion synthétique du moteur maison) restent
 * disponibles en basculant le moteur.</p>
 *
 * <p><b>Connexion du client :</b> la méthode publique
 * {@link #connect(LanguageClient)} est invoquée par réflexion par le
 * pont in-process — elle DOIT rester publique sur une classe publique
 * (voir {@code InProcessStreamConnectionProvider.start}).</p>
 *
 * <p>Pur JVM (aucune dépendance Android) : testable directement en JUnit
 * avec un {@link LanguageClient} d'enregistrement (voir
 * {@code MiniJavaLanguageServerTest}).</p>
 */
public class MiniJavaLanguageServer implements LanguageServer {

    /** Source annoncée dans les diagnostics LSP. */
    static final String SOURCE = "mini-java";

    /** Le client LSP reçu du pont in-process (canal de publication). */
    private volatile LanguageClient client;

    /** Documents ouverts — URI → dernier texte connu (sync Full). */
    private final Map<String, String> openDocuments = new ConcurrentHashMap<>();

    /** Client reçu du pont — appelé par réflexion, doit rester public. */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        // Sync FULL : le client renvoie le texte complet à chaque
        // didChange — l'analyse repart toujours de la source exacte.
        caps.setTextDocumentSync(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(
                TextDocumentSyncKind.Full));
        return CompletableFuture.completedFuture(new InitializeResult(caps));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // Rien à nettoyer : le pont ferme les streams et coupe les
        // threads (stop() du provider in-process).
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {
            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
                String uri = params.getTextDocument().getUri();
                openDocuments.put(uri, params.getTextDocument().getText());
                analyzeAndPublish(uri);
            }

            @Override
            public void didChange(DidChangeTextDocumentParams params) {
                String uri = params.getTextDocument().getUri();
                // Sync Full : le premier changement porte le texte
                // complet (les suivants n'existent pas dans ce mode).
                if (!params.getContentChanges().isEmpty()) {
                    openDocuments.put(uri,
                            params.getContentChanges().get(0).getText());
                    analyzeAndPublish(uri);
                }
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
                String uri = params.getTextDocument().getUri();
                openDocuments.remove(uri);
                // Convention LSP : à la fermeture, le serveur vide les
                // diagnostics du document.
                publish(uri, new ArrayList<>());
            }

            @Override
            public void didSave(DidSaveTextDocumentParams params) {
                // Rien : l'analyse est déjà relancée à chaque didChange.
            }
            // Les autres requêtes (completion, hover, definition…)
            // héritent des implémentations par défaut de LSP4J : ce
            // serveur n'annonce aucune capacité correspondante.
        };
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public void didChangeConfiguration(
                    org.eclipse.lsp4j.DidChangeConfigurationParams params) {
                // Rien : le mini serveur n'a aucun réglage client.
            }

            @Override
            public void didChangeWatchedFiles(
                    org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {
                // Rien : le mini serveur ne regarde pas l'espace de
                // travail hors documents ouverts.
            }
            // Les autres notifications (didChangeConfiguration,
            // executeCommand…) héritent des implémentations par défaut
            // de LSP4J — ce serveur n'annonce aucune capacité d'espace
            // de travail.
        };
    }

    /**
     * Analyse le document ouvert et pousse les diagnostics au client.
     *
     * <p>Exécuté sur un thread du pont in-process (jamais le thread UI) :
     * la publication voyage par JSON-RPC et remonte au
     * {@code LspDiagnosticsManager} qui, lui, marshalle vers le thread
     * UI de l'hôte.</p>
     */
    private void analyzeAndPublish(String uri) {
        String text = openDocuments.get(uri);
        if (text == null) return;
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (MiniJavaParser.Issue issue : MiniJavaParser.parse(text)) {
            Diagnostic diag = new Diagnostic();
            diag.setRange(new Range(
                    new Position(issue.line, issue.character),
                    new Position(issue.endLine, issue.endCharacter)));
            diag.setSeverity(DiagnosticSeverity.Error);
            diag.setSource(SOURCE);
            diag.setCode(issue.code);
            diag.setMessage(issue.message);
            diagnostics.add(diag);
        }
        publish(uri, diagnostics);
    }

    private void publish(String uri, List<Diagnostic> diagnostics) {
        LanguageClient c = client;
        if (c != null) {
            c.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
        }
    }
}
