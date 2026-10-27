package jo.codeeditor.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * Faux serveur LSP des tests de résolution de complétion — top-level
 * PUBLIC : le pont in-process découvre {@link #connect(LanguageClient)}
 * par réflexion.
 *
 * <p>Annonce {@code completionProvider} (avec ou sans
 * {@code resolveProvider} selon {@link #announceResolve}) ; sert deux
 * candidats — l'un avec documentation DIRECTE, l'autre résolu par
 * {@code completionItem/resolve} — et COMPTE les requêtes reçues pour
 * les assertions du contrat : la résolution est paresseuse, silencieuse
 * sans annonce, et la documentation résolue est mise en cache.</p>
 */
public class FakeCompletionResolveServer implements LanguageServer {

    /** Annonce resolveProvider à l'initialisation (négociée une fois). */
    public volatile boolean announceResolve;
    /** Documentation servie par completionItem/resolve (null = silence). */
    public volatile String resolveDoc = "Ajoute un élément à la fin de la liste. @param x l'élément @return vrai si ajouté";
    /** Compteur de requêtes textDocument/completion reçues. */
    public volatile int completionRequests;
    /** Compteur de requêtes completionItem/resolve reçues. */
    public volatile int resolveRequests;
    /** Dernier item reçu par completionItem/resolve. */
    public volatile CompletionItem lastResolvedItem;

    /** Client reçu du pont (invocation réflexe de connect). */
    public volatile LanguageClient client;

    /** Reçu du pont in-process — doit rester public sur une classe publique. */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities caps = new ServerCapabilities();
        CompletionOptions options = new CompletionOptions();
        options.setResolveProvider(announceResolve);
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
            public CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<
                    java.util.List<CompletionItem>, CompletionList>> completion(
                    CompletionParams position) {
                completionRequests++;
                CompletionItem direct = new CompletionItem();
                direct.setLabel("toString");
                direct.setDocumentation("La représentation textuelle de l'objet");
                CompletionItem lazy = new CompletionItem();
                lazy.setLabel("add");
                // Pas de documentation : elle ne vient qu'au resolve —
                // le champ data fait l'aller-retour (la clé du test).
                lazy.setData("add-magic-data");
                CompletionList list = new CompletionList(false, Arrays.asList(direct, lazy));
                return CompletableFuture.completedFuture(
                        org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(list));
            }

            @Override
            public CompletableFuture<CompletionItem> resolveCompletionItem(
                    CompletionItem item) {
                resolveRequests++;
                lastResolvedItem = item;
                if (resolveDoc == null) {
                    // Silence du serveur : jamais résolu.
                    return CompletableFuture.completedFuture(item);
                }
                CompletionItem resolved = new CompletionItem();
                resolved.setLabel(item.getLabel());
                resolved.setData(item.getData());
                resolved.setDocumentation(resolveDoc);
                return CompletableFuture.completedFuture(resolved);
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
