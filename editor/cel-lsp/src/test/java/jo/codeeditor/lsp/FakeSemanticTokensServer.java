package jo.codeeditor.lsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Faux serveur LSP des tests de surlignage sémantique — top-level PUBLIC :
 * le pont in-process découvre {@link #connect(LanguageClient)} par
 * réflexion (une classe package-privée chargée par le classloader de test
 * provoquerait un IllegalAccessException silencieux).
 *
 * <p>Annonce {@code semanticTokensProvider} (avec ou sans légende selon
 * {@link #announceLegend} — la légende des modificateurs est réglable
 * pour tester la résolution PAR NOM indépendamment de l'ordre des bits),
 * sert {@link #data} (quintuplets bruts) et COMPTE les requêtes reçues.</p>
 */
public class FakeSemanticTokensServer implements LanguageServer {

    /** Annonce une légende à l'initialisation. */
    public volatile boolean announceLegend = true;
    /** Légende des modificateurs servie (ordre propre au serveur). */
    public volatile List<String> legendModifiers = List.of();
    /** Quintuplets bruts (ligne, colonne, longueur, type, modificateurs). */
    public volatile List<Integer> data = List.of();
    /** Compteur de requêtes semanticTokens/full reçues. */
    public volatile int semanticTokensRequests;

    /** Client reçu du pont (invocation réflexe de connect). */
    public volatile LanguageClient client;

    /** Reçu du pont in-process — doit rester public sur une classe publique. */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        SemanticTokensWithRegistrationOptions options =
                new SemanticTokensWithRegistrationOptions();
        if (announceLegend) {
            options.setLegend(new SemanticTokensLegend(
                    List.of("keyword", "variable", "method", "property"),
                    legendModifiers));
        }
        caps.setSemanticTokensProvider(options);
        return CompletableFuture.completedFuture(new InitializeResult(caps));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {
            @Override
            public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
            }

            @Override
            public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {
            }

            @Override
            public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {
            }

            @Override
            public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {
            }

            @Override
            public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
                semanticTokensRequests++;
                SemanticTokens tokens = new SemanticTokens();
                tokens.setData(data);
                return CompletableFuture.completedFuture(tokens);
            }
        };
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public void didChangeConfiguration(
                    org.eclipse.lsp4j.DidChangeConfigurationParams params) {
            }

            @Override
            public void didChangeWatchedFiles(
                    org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {
            }
        };
    }
}
