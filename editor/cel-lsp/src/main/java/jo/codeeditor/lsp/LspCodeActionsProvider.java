package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.CodeAction;
import jo.codeeditor.lang.provider.CodeActionsProvider;
import jo.codeeditor.lang.model.Diagnostic;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traduit les actions de code du SPI éditeur (quick-fixes, refactors) en
 * requête LSP {@code textDocument/codeAction} — en reconstituant les
 * diagnostics de la ligne pour le contexte — et capture le
 * {@code WorkspaceEdit} de chaque action dans son {@code Runnable}
 * d'application. Instancié par {@link LspLanguage} quand le serveur annonce
 * la capability codeActionProvider.
 */
class LspCodeActionsProvider implements CodeActionsProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspCodeActionsProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<CodeAction> codeActions(CharSequence text, int line) {
        LanguageServer server = wrapper.getServer();
        if (server == null) return Collections.emptyList();
        // Flush didChange en attente (cf. complete). Les
        // quick-fixes doivent s'appuyer sur le texte live.
        editor.flushPendingChange();
        org.eclipse.lsp4j.CodeActionParams params = new org.eclipse.lsp4j.CodeActionParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        // Convertit la ligne en plage — utilise la ligne entière.
        org.eclipse.lsp4j.Range range = new org.eclipse.lsp4j.Range(
            new org.eclipse.lsp4j.Position(line, 0),
            new org.eclipse.lsp4j.Position(line, Integer.MAX_VALUE));
        params.setRange(range);
        // Construit un CodeActionContext correct :
        //   1. La liste des diagnostics intersectant cette plage — sans
        //      eux, le codeAction() du serveur rend vide immédiatement
        //      (JavaTextDocumentService.codeAction L155).
        //   2. Les CodeActionKinds pris en charge (quickfix + refactor).
        // Un contexte null fait toujours rendre 0 action au serveur →
        // aucune ampoule affichée.
        List<org.eclipse.lsp4j.Diagnostic> intersecting = new ArrayList<>();
        for (Diagnostic d : editor.getCachedDiagnostics()) {
            // Reconvertit l'offset éditeur en Position LSP pour obtenir
            // la ligne.
            org.eclipse.lsp4j.Position diagPos = editor.offsetToPosition(d.start);
            if (diagPos.getLine() == line) {
                org.eclipse.lsp4j.Diagnostic lspDiag = new org.eclipse.lsp4j.Diagnostic();
                org.eclipse.lsp4j.Position diagStart = editor.offsetToPosition(d.start);
                org.eclipse.lsp4j.Position diagEnd = editor.offsetToPosition(d.end);
                lspDiag.setRange(new org.eclipse.lsp4j.Range(diagStart, diagEnd));
                lspDiag.setMessage(d.message);
                org.eclipse.lsp4j.DiagnosticSeverity sev;
                switch (d.severity) {
                    case 3: sev = org.eclipse.lsp4j.DiagnosticSeverity.Error; break;
                    case 2: sev = org.eclipse.lsp4j.DiagnosticSeverity.Warning; break;
                    default: sev = org.eclipse.lsp4j.DiagnosticSeverity.Information; break;
                }
                lspDiag.setSeverity(sev);
                intersecting.add(lspDiag);
            }
        }
        org.eclipse.lsp4j.CodeActionContext ctx = new org.eclipse.lsp4j.CodeActionContext(
            intersecting,
            java.util.List.of(
                org.eclipse.lsp4j.CodeActionKind.QuickFix,
                org.eclipse.lsp4j.CodeActionKind.Refactor,
                org.eclipse.lsp4j.CodeActionKind.RefactorExtract,
                org.eclipse.lsp4j.CodeActionKind.RefactorInline,
                org.eclipse.lsp4j.CodeActionKind.Source,
                org.eclipse.lsp4j.CodeActionKind.SourceOrganizeImports));
        params.setContext(ctx);
        log("codeAction: requesting for line=" + line
            + " (context: " + intersecting.size() + " diagnostics)");
        try {
            CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>> future =
                server.getTextDocumentService().codeAction(params);
            List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> result =
                future.get(3, TimeUnit.SECONDS);
            if (result == null || result.isEmpty()) {
                log("codeAction: no results");
                return Collections.emptyList();
            }
            log("codeAction: got " + result.size() + " actions");
            List<CodeAction> out = new ArrayList<>();
            for (org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction> e : result) {
                String title;
                String kind = "";
                // Capture le WorkspaceEdit pour que le Runnable apply
                // puisse l'appliquer quand l'utilisateur tape l'action.
                // Sans cela, apply serait null → taper l'action n'aurait
                // aucun effet visible.
                final org.eclipse.lsp4j.WorkspaceEdit editToApply;
                if (e.isLeft()) {
                    title = e.getLeft().getTitle();
                    editToApply = null;
                } else {
                    org.eclipse.lsp4j.CodeAction ca = e.getRight();
                    title = ca.getTitle();
                    kind = ca.getKind() != null ? ca.getKind() : "";
                    editToApply = ca.getEdit();
                }
                final String actionTitle = title;
                final LspEditor editorRef = editor;
                Runnable apply = () -> {
                    if (editToApply == null) {
                        log("codeAction: '" + actionTitle + "' has no edit — nothing to apply");
                        return;
                    }
                    try {
                        editorRef.applyWorkspaceEdit(editToApply);
                        log("codeAction: applied '" + actionTitle + "'");
                    } catch (Throwable t) {
                        log("codeAction: FAILED to apply '" + actionTitle + "': " + t.getMessage());
                    }
                };
                out.add(new CodeAction(title, kind, false, apply));
            }
            return out;
        } catch (Exception e) {
            log("codeAction: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private void log(String message) {
        android.util.Log.i("LspCodeAction", message);
        if (logSink != null) logSink.log(message);
    }
}
