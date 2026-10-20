package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.CodeAction;
import jo.codeeditor.lang.provider.CodeActionsProvider;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Traduit les actions de code du SPI éditeur (quick-fixes, refactors) en
 * requête LSP {@code textDocument/codeAction} — en retransmettant dans
 * le contexte les diagnostics publiés par le serveur POUR LA PLAGE
 * DEMANDÉE, avec leur identité INTÉGRALE : plage, sévérité, code,
 * données structurées ({@code data}), source et tags sont conservés
 * tels quels (diagnostics bruts en cache). Le serveur dérive ses
 * quick-fixes exacts de cette identité — l'amputer (n'envoyer que
 * message + sévérité reconstruits) vide structurellement sa réponse.
 * Capture le {@code WorkspaceEdit} de chaque action dans son
 * {@code Runnable} d'application. Instancié par {@link LspLanguage}
 * quand le serveur annonce la capability codeActionProvider.
 */
class LspCodeActionsProvider implements CodeActionsProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    /** Borné : un serveur muet ne doit pas figer la voie « features ». */
    private static final long CODE_ACTION_TIMEOUT_MS = 3000;

    /** Borné : l'exécution d'une commande serveur ne doit pas figer l'application. */
    private static final long COMMAND_TIMEOUT_MS = 10000;

    LspCodeActionsProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public List<CodeAction> codeActions(CharSequence text, int line) {
        if (text == null) return Collections.emptyList();
        // Plage = ligne entière demandée (compat : le SPI historique
        // raisonne par ligne).
        org.eclipse.lsp4j.Range range = new org.eclipse.lsp4j.Range(
            new org.eclipse.lsp4j.Position(Math.max(0, line), 0),
            new org.eclipse.lsp4j.Position(Math.max(0, line), Integer.MAX_VALUE));
        return requestCodeActions(text.toString(), range,
            java.util.List.of(
                org.eclipse.lsp4j.CodeActionKind.QuickFix,
                org.eclipse.lsp4j.CodeActionKind.Refactor,
                org.eclipse.lsp4j.CodeActionKind.RefactorExtract,
                org.eclipse.lsp4j.CodeActionKind.RefactorInline,
                org.eclipse.lsp4j.CodeActionKind.Source,
                org.eclipse.lsp4j.CodeActionKind.SourceOrganizeImports));
    }

    @Override
    public List<CodeAction> codeActions(CharSequence text, int caretOffset,
            int selectionStart, int selectionEnd, List<String> onlyKinds) {
        if (text == null) return Collections.emptyList();
        // Plage visée par l'invocation : sélection active, sinon mot sous
        // le caret, sinon ligne du caret — UNE requête, émise au moment
        // où l'utilisateur demande les actions (contenu fraîchement
        // calculé, pas un balayage pré-calculé).
        org.eclipse.lsp4j.Range range = LspTextUtils.effectiveRange(
                text.toString(), caretOffset, selectionStart, selectionEnd);
        return requestCodeActions(text.toString(), range, onlyKinds);
    }

    /**
     * Le cœur partagé : une requête {@code textDocument/codeAction} pour
     * la plage donnée, avec le contexte de diagnostics intersectant la
     * plage (identité complète — cf. {@link LspDiagnosticsManager}).
     */
    private List<CodeAction> requestCodeActions(String src, org.eclipse.lsp4j.Range range,
            List<String> onlyKinds) {
        LanguageServer server = editor.readyServer(wrapper); // garde commun 3.74.0 : null si déconnecté (C1)
        if (server == null) return Collections.emptyList();
        // Flush didChange en attente (cf. complete). Les quick-fixes
        // doivent s'appuyer sur le texte live.
        editor.flushPendingChange();
        org.eclipse.lsp4j.CodeActionParams params = new org.eclipse.lsp4j.CodeActionParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        params.setRange(range);
        params.setContext(contextFor(range, onlyKinds));
        log("codeAction: requesting for range " + describe(range)
            + " (context: " + params.getContext().getDiagnostics().size() + " diagnostics)");
        // ★ Fraîcheur : capture la version du document AVANT d'envoyer la
        // requête — à l'acceptation de l'action (asynchrone), un écart
        // signifie que l'utilisateur a modifié le document : les
        // positions du serveur seraient périmées → l'édition sera refusée.
        final int versionAtRequest = editor.getDocumentVersion();
        try {
            CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>> future =
                server.getTextDocumentService().codeAction(params);
            List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> result =
                future.get(CODE_ACTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (result == null || result.isEmpty()) {
                log("codeAction: no results");
                return Collections.emptyList();
            }
            log("codeAction: got " + result.size() + " actions");
            List<CodeAction> out = new ArrayList<>();
            for (org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction> e : result) {
                if (e == null) continue;
                out.add(toAction(e, versionAtRequest));
            }
            sortForMenu(out);
            return out;
        } catch (Exception e) {
            log("codeAction: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Le contexte de la requête : les diagnostics du cache brut qui
     * INTERSECTENT la plage demandée — pas seulement ceux qui COMMENCENT
     * dessus (un diagnostic multi-lignes couvrant le caret doit être
     * transmis), et surtout transmis SANS RECONSTRUCTION : l'objet LSP
     * brut porte le code, les données structurées, la source et les tags
     * dont le serveur a besoin pour proposer des fixes exacts.
     */
    private org.eclipse.lsp4j.CodeActionContext contextFor(org.eclipse.lsp4j.Range range,
            List<String> onlyKinds) {
        List<org.eclipse.lsp4j.Diagnostic> intersecting = new ArrayList<>();
        for (org.eclipse.lsp4j.Diagnostic d : editor.cachedLspDiagnostics()) {
            if (d == null || d.getRange() == null) continue;
            if (LspTextUtils.rangesIntersect(d.getRange(), range)) {
                intersecting.add(d);
            }
        }
        org.eclipse.lsp4j.CodeActionContext ctx =
            new org.eclipse.lsp4j.CodeActionContext(intersecting, onlyKinds);
        ctx.setTriggerKind(org.eclipse.lsp4j.CodeActionTriggerKind.Invoked);
        return ctx;
    }

    /**
     * Une entrée de réponse LSP → action du SPI. Le {@code WorkspaceEdit}
     * est capturé pour que le {@code Runnable} apply puisse l'appliquer
     * quand l'utilisateur choisit l'action — sans cela, apply serait
     * vide et choisir l'action n'aurait aucun effet visible.
     */
    private CodeAction toAction(
            org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction> e,
            int versionAtRequest) {
        if (e.isLeft()) {
            return commandAction(e.getLeft(), versionAtRequest);
        }
        org.eclipse.lsp4j.CodeAction ca = e.getRight();
        if (ca == null) return null;
        final String actionTitle = ca.getTitle() != null ? ca.getTitle() : "";
        final String kind = ca.getKind() != null ? ca.getKind() : "";
        final boolean preferred = Boolean.TRUE.equals(ca.getIsPreferred());
        final String disabledReason = ca.getDisabled() != null
                ? ca.getDisabled().getReason() : null;
        final org.eclipse.lsp4j.WorkspaceEdit editToApply = ca.getEdit();
        final org.eclipse.lsp4j.CodeAction unresolved = ca;
        final LspEditor editorRef = editor;
        final LanguageServerWrapper wrapperRef = wrapper;
        Runnable apply = () -> {
            if (disabledReason != null) {
                log("codeAction: '" + actionTitle + "' est désactivée ("
                        + disabledReason + ") — non appliquée");
                return;
            }
            org.eclipse.lsp4j.WorkspaceEdit edit = editToApply;
            if (edit == null) {
                // Résolution différée : le serveur a gardé l'édition hors
                // de la réponse (données dans le champ data) et annonce
                // resolveProvider — on résout à L'APPLICATION, une seule
                // fois, puis on applique l'édition obtenue.
                edit = resolveEdit(wrapperRef, unresolved);
                if (edit == null) {
                    log("codeAction: '" + actionTitle + "' has no edit — nothing to apply");
                    return;
                }
            }
            try {
                editorRef.applyWorkspaceEdit(edit, versionAtRequest);
                log("codeAction: applied '" + actionTitle + "'");
            } catch (Exception t) {
                log("codeAction: FAILED to apply '" + actionTitle + "': " + t.getMessage());
            }
        };
        return new CodeAction(actionTitle, kind, preferred, disabledReason, apply);
    }

    /**
     * Résout une action non résolue via {@code codeAction/resolve} —
     * seulement si le serveur l'annonce (resolveProvider) et porte
     * l'édition. Borné : un serveur muet ne doit pas figer l'application.
     */
    private static org.eclipse.lsp4j.WorkspaceEdit resolveEdit(
            LanguageServerWrapper wrapperRef, org.eclipse.lsp4j.CodeAction unresolved) {
        if (unresolved == null || !resolveSupported(wrapperRef)) return null;
        try {
            LanguageServer server = wrapperRef.getServer();
            if (server == null) return null;
            org.eclipse.lsp4j.CodeAction resolved = server.getTextDocumentService()
                    .resolveCodeAction(unresolved)
                    .get(CODE_ACTION_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            return resolved != null ? resolved.getEdit() : null;
        } catch (Exception e) {
            // D-07 : la réponse du serveur est malformée, muette ou
            // trop lente — journalisée en warn (l'ancien catch muet
            // cachait les serveurs bogués) puis repli null : l'action
            // n'est tout simplement pas résolue.
            jo.codeeditor.util.EditorLog.warn("LspCodeAction",
                    "résolution d'une action de code échouée (réponse serveur "
                            + "malformée, muette ou trop lente)", e);
            return null;
        }
    }

    /** True si le serveur annonce la résolution différée des actions. */
    private static boolean resolveSupported(LanguageServerWrapper wrapperRef) {
        org.eclipse.lsp4j.ServerCapabilities caps = wrapperRef.getCapabilities();
        if (caps == null || caps.getCodeActionProvider() == null) return false;
        if (!caps.getCodeActionProvider().isRight()) return false;
        org.eclipse.lsp4j.CodeActionOptions options =
                caps.getCodeActionProvider().getRight();
        return options != null && Boolean.TRUE.equals(options.getResolveProvider());
    }

    /**
     * Une action à COMMANDE (les serveurs qui travaillent en deux temps :
     * la commande fait le travail, l'édition éventuelle revient soit en
     * valeur de retour, soit poussée via workspace/applyEdit — ce second
     * chemin est déjà câblé côté client). L'application émet
     * workspace/executeCommand ; si la réponse porte un WorkspaceEdit,
     * il est appliqué par la voie commune ; sinon le compte rendu est
     * journalisé — plus de no-op silencieux.
     */
    private CodeAction commandAction(org.eclipse.lsp4j.Command command, int versionAtRequest) {
        final String actionTitle = command != null && command.getTitle() != null
                ? command.getTitle() : "";
        final String commandId = command != null ? command.getCommand() : null;
        final List<Object> commandArgs = command != null ? command.getArguments() : null;
        final LspEditor editorRef = editor;
        final LanguageServerWrapper wrapperRef = wrapper;
        Runnable apply = () -> {
            LanguageServer server = wrapperRef.getServer();
            if (server == null || commandId == null) {
                log("codeAction: '" + actionTitle + "' — serveur parti, commande abandonnée");
                return;
            }
            try {
                Object result = server.getWorkspaceService().executeCommand(
                        new org.eclipse.lsp4j.ExecuteCommandParams(commandId, commandArgs))
                        .get(COMMAND_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
                org.eclipse.lsp4j.WorkspaceEdit edit = asWorkspaceEdit(result);
                if (edit != null) {
                    editorRef.applyWorkspaceEdit(edit, versionAtRequest);
                    log("codeAction: command '" + commandId + "' executed — edit applied");
                } else {
                    log("codeAction: command '" + commandId
                            + "' executed (no workspace edit returned)");
                }
            } catch (Exception t) {
                log("codeAction: FAILED to execute command '" + commandId + "': " + t.getMessage());
            }
        };
        return new CodeAction(actionTitle, "", false, apply);
    }

    /**
     * Le résultat d'une commande devient édition s'il a la forme d'un
     * WorkspaceEdit (champs changes/documentChanges) — l'objet arrive
     * typiquement désérialisé en arbre JSON par le transport, ou déjà
     * typé selon le serveur.
     */
    private static org.eclipse.lsp4j.WorkspaceEdit asWorkspaceEdit(Object result) {
        if (result == null) return null;
        if (result instanceof org.eclipse.lsp4j.WorkspaceEdit) {
            return (org.eclipse.lsp4j.WorkspaceEdit) result;
        }
        try {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            com.google.gson.JsonElement el = result instanceof com.google.gson.JsonElement
                    ? (com.google.gson.JsonElement) result
                    : gson.toJsonTree(result);
            if (!el.isJsonObject()) return null;
            com.google.gson.JsonObject o = el.getAsJsonObject();
            if (!o.has("changes") && !o.has("documentChanges")) return null;
            return gson.fromJson(el, org.eclipse.lsp4j.WorkspaceEdit.class);
        } catch (Exception e) {
            // D-07 : la valeur retournée par la commande n'a pas la
            // forme d'un WorkspaceEdit malgré ses champs — journalisée
            // en warn (l'ancien catch muet cachait les réponses
            // boguées) puis repli null : la commande est réputée sans
            // édition, sans faire échouer l'application.
            jo.codeeditor.util.EditorLog.warn("LspCodeAction",
                    "réponse de commande ininterprétable en édition d'espace "
                            + "de travail (réponse serveur malformée)", e);
            return null;
        }
    }

    /** Description compacte d'une plage pour les journaux. */
    private static String describe(org.eclipse.lsp4j.Range r) {
        return r.getStart().getLine() + ":" + r.getStart().getCharacter()
            + "-" + r.getEnd().getLine() + ":" + r.getEnd().getCharacter();
    }

    /**
     * Classement du menu : corrections d'abord (quickfix.*), puis
     * refactorings, puis actions de source ; au sein de chaque groupe,
     * l'action marquée PRÉFÉRÉE par le serveur passe en tête. Tri stable
     * (l'ordre du serveur est conservé à qualité égale).
     */
    private static void sortForMenu(List<CodeAction> actions) {
        actions.sort(java.util.Comparator
            .comparingInt((CodeAction a) -> kindRank(a.kind))
            .thenComparingInt(a -> a.isPreferred ? 0 : 1));
    }

    /** Priorité d'affichage par famille : quickfix < refactor < source < autres. */
    private static int kindRank(String kind) {
        if (kind == null || kind.isEmpty()) return 3;
        if (kind.startsWith("quickfix")) return 0;
        if (kind.startsWith("refactor")) return 1;
        if (kind.startsWith("source")) return 2;
        return 3;
    }

    private void log(String message) {
        jo.codeeditor.util.EditorLog.info("LspCodeAction", message);
        if (logSink != null) logSink.log(message);
    }
}
