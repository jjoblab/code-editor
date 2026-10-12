package jo.codeeditor.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Faux serveur LSP des tests de complétion — top-level PUBLIC : le pont
 * in-process découvre {@link #connect(LanguageClient)} par réflexion
 * {@code getMethod + invoke} ; une classe package-privée chargée par le
 * classloader de test (différent de celui de la production) provoque un
 * IllegalAccessException silencieux.
 *
 * <p>Annonce {@code completionProvider} avec le caractère déclencheur
 * « . » ; capture chaque {@link CompletionParams} reçu pour les
 * assertions de contexte (triggerKind, triggerCharacter) — le contrat
 * P2-9 : la complétion auto-déclenchée par la frappe d'un caractère
 * déclencheur doit se distinguer de l'invocation explicite dans le
 * {@code CompletionContext} envoyé au serveur.</p>
 */
public class FakeCompletionTriggerServer implements LanguageServer {

    /** Client reçu du pont (invocation réflexe de connect). */
    public volatile LanguageClient client;

    /** Dernier CompletionParams reçu (assertions de contexte). */
    public volatile CompletionParams lastCompletion;

    /** Compteur de requêtes completion reçues. */
    public volatile int completionRequests;

    /**
     * Reçu du pont in-process (découvert par réflexion — doit rester
     * public sur une classe publique).
     */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        CompletionOptions options = new CompletionOptions();
        options.setTriggerCharacters(List.of("."));
        caps.setCompletionProvider(options);
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
            public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
                    CompletionParams params) {
                lastCompletion = params;
                completionRequests++;
                List<CompletionItem> items = new ArrayList<>();
                return CompletableFuture.completedFuture(Either.forLeft(items));
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
