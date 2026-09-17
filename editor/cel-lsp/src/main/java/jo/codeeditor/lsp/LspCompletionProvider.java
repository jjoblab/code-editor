package jo.codeeditor.lsp;

import jo.codeeditor.lang.provider.CompletionProvider;
import jo.codeeditor.lang.provider.CompletionPublisher;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Traduit les demandes de complétion du SPI éditeur en requêtes LSP
 * {@code textDocument/completion}, puis convertit les propositions du serveur
 * en éléments affichables (badge de type, auto-import, rang de tri).
 * Instancié par {@link LspLanguage} quand le serveur annonce la capability
 * completionProvider.
 */
class LspCompletionProvider implements CompletionProvider {
    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspLogSink logSink;

    LspCompletionProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
        this.wrapper = wrapper;
        this.editor = editor;
        this.logSink = logSink;
    }

    @Override
    public void complete(CharSequence text, int caret, CompletionPublisher publisher) {
        LanguageServer server = wrapper.getServer();
        if (server == null) {
            log("completion: server is null — not connected?", null);
            return;
        }
        // Flush SYNCHRONE du didChange en attente AVANT la
        // requête completion. Sans ceci, le debounce didChange (300 ms)
        // dépasse le debounce completion (120 ms) → le serveur lit
        // openDocuments STALE → la détection de contexte
        // IMPORT_REFERENCE échoue (l'instruction « import android. »
        // n'est pas encore visible côté serveur) → repli sur
        // NAME_REFERENCE → mots-clés à la place des sous-paquets
        // (repro user : « import android. → keywords »).
        // Le flush est SYNCHRONE : la notification didChange est enfilée
        // AVANT la requête completion dans la même file LSP4J FIFO →
        // le serveur traite didChange PUIS completion, avec le texte
        // à jour.
        editor.flushPendingChange();
        CompletionParams params = new CompletionParams();
        params.setTextDocument(editor.getTextDocumentIdentifier());
        params.setPosition(editor.offsetToPosition(caret));
        // Définit le CompletionContext — certains serveurs rejettent
        // les requêtes sans lui. Trigger kind Invoked (demande explicite
        // de l'utilisateur).
        org.eclipse.lsp4j.CompletionContext ctx = new org.eclipse.lsp4j.CompletionContext();
        ctx.setTriggerKind(org.eclipse.lsp4j.CompletionTriggerKind.Invoked);
        params.setContext(ctx);
        log("completion: requesting at caret=" + caret, null);
        try {
            CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<org.eclipse.lsp4j.CompletionItem>, CompletionList>> future =
                server.getTextDocumentService().completion(params);
            org.eclipse.lsp4j.jsonrpc.messages.Either<List<org.eclipse.lsp4j.CompletionItem>, CompletionList> result =
                future.get(15, TimeUnit.SECONDS);
            List<org.eclipse.lsp4j.CompletionItem> items = result.isLeft()
                ? result.getLeft() : result.getRight().getItems();
            int count = items != null ? items.size() : 0;
            log("completion: got " + count + " items", null);
            if (items != null) {
                // Préserve l'ordre classement du serveur.
                // Le client regroupe par qualité de match (exact /
                // préfixe / flou) mais garde l'ordre d'insertion DANS
                // chaque groupe — un rank décroissant par position fait
                // donc remonter les meilleures propositions du serveur
                // au lieu du relevé uniforme "50" qui aplatissait le
                // tri (ordre résiduel quasi alphabétique).
                final LspEditor editorRef = editor;
                int rank = items.size();
                for (org.eclipse.lsp4j.CompletionItem lspItem : items) {
                    String label = lspItem.getLabel();
                    String detail = lspItem.getDetail() != null ? lspItem.getDetail() : "";
                    String insertText = lspItem.getInsertText() != null ? lspItem.getInsertText() : label;
                    String kind = lspKindToString(lspItem.getKind());
                    // Badge de type : le kind LSP NUMÉRIQUE voyage
                    // jusqu'au renderer ; le champ data transporte le
                    // raffinement (« annotation », « package »…) que le
                    // protocole ne distingue pas.
                    int kindCode = lspItem.getKind() != null
                            ? lspItem.getKind().getValue() : 0;
                    String kindTag = lspKindTag(lspItem.getData());
                    jo.codeeditor.lang.model.CompletionItem out =
                        new jo.codeeditor.lang.model.CompletionItem(
                            label, detail, insertText, kind,
                            kindCode, kindTag,
                            // Plus haut = meilleur ; la 1re proposition
                            // du serveur obtient le score maximal.
                            Math.max(1, rank--), false);
                    // Auto-import — transporte les
                    // additionalTextEdits (insertion de l'instruction
                    // import) vers l'éditeur via un hook post-accept.
                    List<org.eclipse.lsp4j.TextEdit> extra = lspItem.getAdditionalTextEdits();
                    if (extra != null && !extra.isEmpty()) {
                        final List<org.eclipse.lsp4j.TextEdit> extraEdits =
                            new ArrayList<>(extra);
                        final String itemLabel = label;
                        out.postApplyAction = () -> {
                            try {
                                editorRef.applyAdditionalEdits(extraEdits);
                                android.util.Log.i("LspCompletion",
                                    "auto-import appliqué pour " + itemLabel);
                            } catch (Throwable t) {
                                android.util.Log.w("LspCompletion",
                                    "auto-import échoué pour "
                                        + itemLabel + ": " + t.getMessage());
                            }
                        };
                    }
                    publisher.addItem(out);
                }
            }
        } catch (TimeoutException e) {
            log("completion: TIMEOUT (5s) — server too slow or hung", e);
        } catch (InterruptedException | ExecutionException e) {
            // Journalise la chaîne de causes complète —
            // ResponseErrorException enveloppe le message d'erreur du
            // serveur.
            String msg = "completion: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
            Throwable cause = e.getCause();
            while (cause != null) {
                msg += "\n  caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                cause = cause.getCause();
            }
            log(msg, e);
        }
        publisher.flush();
    }

    @Override
    public List<String> getTriggerCharacters() {
        if (wrapper.getCapabilities() != null
            && wrapper.getCapabilities().getCompletionProvider() != null
            && wrapper.getCapabilities().getCompletionProvider().getTriggerCharacters() != null) {
            return wrapper.getCapabilities().getCompletionProvider().getTriggerCharacters();
        }
        return Collections.emptyList();
    }

    private void log(String message, Throwable t) {
        android.util.Log.i("LspCompletion", message);
        if (t != null) android.util.Log.e("LspCompletion", message, t);
        if (logSink != null) logSink.log(message);
    }

    private static String lspKindToString(org.eclipse.lsp4j.CompletionItemKind kind) {
        if (kind == null) return "v";
        switch (kind) {
            case Method: case Function: case Constructor: return "m";
            case Field: case Variable: case Property: return "f";
            case Class: case Interface: case Struct: case Enum: return "c";
            case Keyword: return "k";
            default: return "v";
        }
    }

    /**
     * Raffinement de kind transporté dans le champ LSP
     * {@code data} (string côté serveur lspjava ; après le round-trip
     * JSON-RPC il peut arriver en String, JsonPrimitive ou autre —
     * on accepte les formes plausibles, null sinon).
     *
     * <p>Usage : une ANNOTATION Java voyage en CompletionItemKind.Class
     * (le protocole LSP n'a pas de kind dédié) — le serveur la tague
     * {@code "annotation"} pour que le badge affiche « @ » et non « C ».</p>
     */
    private static String lspKindTag(Object data) {
        if (data == null) return null;
        String s = null;
        if (data instanceof String) {
            s = (String) data;
        } else if (data instanceof com.google.gson.JsonElement) {
            com.google.gson.JsonElement je = (com.google.gson.JsonElement) data;
            if (je.isJsonPrimitive()) {
                try {
                    s = je.getAsString();
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        if (s == null) return null;
        switch (s) {
            case "annotation": case "package": case "record":
            case "parameter":
                return s;
            default:
                return null;
        }
    }
}
