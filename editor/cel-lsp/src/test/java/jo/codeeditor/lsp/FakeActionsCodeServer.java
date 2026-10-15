package jo.codeeditor.lsp;

import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Faux serveur LSP des tests d'actions de code — top-level PUBLIC :
 * le pont in-process découvre {@link #connect(LanguageClient)} par
 * réflexion {@code getMethod + invoke} ; une classe package-privée
 * chargée par le classloader de test (différent de celui de la
 * production) provoque un IllegalAccessException silencieux.
 *
 * <p>Contrat répliqué du serveur réel : les quick-fixes ne sont proposés
 * QUE si le diagnostic du contexte porte son identité complète (code +
 * données structurées) — c'est d'elle, pas du message localisé, que
 * dépend la correction exacte. Les paramètres reçus sont capturés pour
 * les assertions, ainsi que le nombre de requêtes codeAction.</p>
 */
public class FakeActionsCodeServer implements LanguageServer {

    /** Client reçu du pont (invocation réflexe de connect). */
    public volatile LanguageClient client;

    /** Dernier CodeActionParams reçu (assertions). */
    public volatile CodeActionParams lastParams;

    /** Compteur de requêtes codeAction reçues. */
    public volatile int codeActionRequests;

    /** Requêtes executeCommand reçues (commande, arguments). */
    public final List<org.eclipse.lsp4j.ExecuteCommandParams> executeCommandRequests =
            new ArrayList<>();

    /** Réponse codeAction imposée par le test (prioritaire sur la réponse par défaut). */
    public volatile List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> answerOverride;

    /** Valeur retournée par workspace/executeCommand (null par défaut). */
    public volatile Object commandResult;

    /** Annonce codeActionOptions.resolveProvider quand vrai. */
    public volatile boolean resolveProvider;

    /** Annonce renameProvider avec prepareSupport quand vrai. */
    public volatile boolean renamePrepareProvider;

    /** Placeholder servi par textDocument/prepareRename (null = refus). */
    public volatile String prepareRenamePlaceholder = "foo";

    /** Compteur de requêtes prepareRename reçues. */
    public volatile int prepareRenameRequests;

    /** Résolutions par TITRE (l'objet résolu arrive désérialisé — l'identité objet est perdue). */
    /** D-07 : résolution échoue (future rompue) quand vrai. */
    public volatile boolean resolveFail;

    public final Map<String, org.eclipse.lsp4j.CodeAction> resolveByTitle =
            new LinkedHashMap<>();

    /** Compteur de requêtes codeAction/resolve reçues. */
    public volatile int resolveRequests;

    /** InitializeParams reçus (assertions de négociation). */
    public volatile InitializeParams lastInitialize;

    /**
     * Reçu du pont in-process (découvert par réflexion — doit rester
     * public sur une classe publique).
     */
    public void connect(LanguageClient client) {
        this.client = client;
    }

    /** Publie un diagnostic IDENTIFIÉ (code, data, source) sur l'URI. */
    public void publishIdentityDiagnostics(String uri) {
        publishIdentityDiagnostics(uri, "famille:1", List.of("Foo", "bar"));
    }

    /** Publie des diagnostics EXACTS fournis par le test. */
    public void publishDiagnostics(String uri, List<Diagnostic> diagnostics) {
        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri(uri);
        params.setDiagnostics(diagnostics);
        client.publishDiagnostics(params);
    }

    /** Variante paramétrable de la publication identifiée. */
    public void publishIdentityDiagnostics(String uri, String code, List<String> args) {
        Diagnostic d = new Diagnostic();
        d.setRange(new Range(new Position(1, 17), new Position(1, 20)));
        d.setMessage("foo cannot be resolved to a type");
        d.setSeverity(DiagnosticSeverity.Error);
        d.setSource("compilateur");
        d.setCode(code);
        d.setData(new ArrayList<>(args));
        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri(uri);
        params.setDiagnostics(List.of(d));
        client.publishDiagnostics(params);
    }

    /** La réponse codeAction à servir (par défaut : identité requise). */
    protected List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> answer(
            CodeActionParams params) {
        if (answerOverride != null) return answerOverride;
        List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> out =
                new ArrayList<>();
        if (params.getContext() == null || params.getContext().getDiagnostics() == null) {
            return out;
        }
        for (Diagnostic d : params.getContext().getDiagnostics()) {
            if (identityIntacte(d)) {
                org.eclipse.lsp4j.CodeAction ca =
                        new org.eclipse.lsp4j.CodeAction("Importer Foo (java.util)");
                ca.setKind(CodeActionKind.QuickFix);
                WorkspaceEdit edit = new WorkspaceEdit();
                Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
                changes.put(params.getTextDocument().getUri(), List.of(
                        new TextEdit(new Range(new Position(0, 0), new Position(0, 0)),
                                "import java.util.Foo;\n")));
                edit.setChanges(changes);
                ca.setEdit(edit);
                out.add(Either.forRight(ca));
            }
        }
        return out;
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        lastInitialize = params;
        ServerCapabilities caps = new ServerCapabilities();
        if (resolveProvider) {
            org.eclipse.lsp4j.CodeActionOptions options = new org.eclipse.lsp4j.CodeActionOptions();
            options.setResolveProvider(true);
            caps.setCodeActionProvider(Either.forRight(options));
        } else {
            caps.setCodeActionProvider(Either.forLeft(true));
        }
        if (renamePrepareProvider) {
            org.eclipse.lsp4j.RenameOptions options = new org.eclipse.lsp4j.RenameOptions();
            options.setPrepareProvider(true);
            caps.setRenameProvider(Either.forRight(options));
        } else {
            caps.setRenameProvider(Either.forLeft(true));
        }
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
            public void didOpen(DidOpenTextDocumentParams params) {
                publishIdentityDiagnostics(params.getTextDocument().getUri());
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
            public CompletableFuture<List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>> codeAction(
                    CodeActionParams params) {
                lastParams = params;
                codeActionRequests++;
                return CompletableFuture.completedFuture(answer(params));
            }

            @Override
            public CompletableFuture<org.eclipse.lsp4j.CodeAction> resolveCodeAction(
                    org.eclipse.lsp4j.CodeAction unresolved) {
                resolveRequests++;
                if (resolveFail) {
                    CompletableFuture<org.eclipse.lsp4j.CodeAction> rompue =
                            new CompletableFuture<>();
                    rompue.completeExceptionally(
                            new RuntimeException("réponse malformée simulée"));
                    return rompue;
                }
                org.eclipse.lsp4j.CodeAction resolved = unresolved != null
                        ? resolveByTitle.get(unresolved.getTitle()) : null;
                return CompletableFuture.completedFuture(
                        resolved != null ? resolved : unresolved);
            }

            @Override
            public CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either3<
                    org.eclipse.lsp4j.Range, org.eclipse.lsp4j.PrepareRenameResult,
                    org.eclipse.lsp4j.PrepareRenameDefaultBehavior>> prepareRename(
                    org.eclipse.lsp4j.PrepareRenameParams params) {
                prepareRenameRequests++;
                if (prepareRenamePlaceholder == null) {
                    return CompletableFuture.completedFuture(null);
                }
                return CompletableFuture.completedFuture(
                        org.eclipse.lsp4j.jsonrpc.messages.Either3.forSecond(
                                new org.eclipse.lsp4j.PrepareRenameResult(
                                        new org.eclipse.lsp4j.Range(
                                                new org.eclipse.lsp4j.Position(1, 15),
                                                new org.eclipse.lsp4j.Position(1, 18)),
                                        prepareRenamePlaceholder)));
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

            @Override
            public CompletableFuture<Object> executeCommand(
                    org.eclipse.lsp4j.ExecuteCommandParams params) {
                executeCommandRequests.add(params);
                return CompletableFuture.completedFuture(commandResult);
            }
        };
    }

    /** Le contrat du serveur réel : code + data présents et exacts. */
    static boolean identityIntacte(Diagnostic d) {
        if (d.getCode() == null || !"famille:1".equals(d.getCode().getLeft())) {
            return false;
        }
        Object data = d.getData();
        if (data instanceof List<?> list) {
            return list.contains("Foo") && list.contains("bar");
        }
        if (data instanceof com.google.gson.JsonElement je && je.isJsonArray()) {
            String s = je.toString();
            return s.contains("Foo") && s.contains("bar");
        }
        return false;
    }
}
