package jo.codeeditor.lsp;

import jo.codeeditor.lang.provider.CompletionProvider;
import jo.codeeditor.lang.provider.CompletionPublisher;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.services.LanguageServer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.ToIntFunction;

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
        // Invocation explicite (raccourci, menu) : pas de caractère
        // déclencheur — Invoked.
        complete(text, caret, (String) null, publisher);
    }

    @Override
    public void complete(CharSequence text, int caret, String triggerCharacter,
                         CompletionPublisher publisher) {
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
        // les requêtes sans lui. Trigger kind réel (modèle Alt+Entrée /
        // Android Studio) : TriggerCharacter + le caractère quand la
        // complétion a été auto-déclenchée par sa frappe (ex. « . » →
        // membres d'une instance), Invoked pour toute demande explicite.
        org.eclipse.lsp4j.CompletionContext ctx = new org.eclipse.lsp4j.CompletionContext();
        if (triggerCharacter != null && !triggerCharacter.isEmpty()) {
            ctx.setTriggerKind(org.eclipse.lsp4j.CompletionTriggerKind.TriggerCharacter);
            ctx.setTriggerCharacter(String.valueOf(triggerCharacter.charAt(0)));
        } else {
            ctx.setTriggerKind(org.eclipse.lsp4j.CompletionTriggerKind.Invoked);
        }
        params.setContext(ctx);
        log("completion: requesting at caret=" + caret
                + (triggerCharacter != null ? " trigger=" + triggerCharacter : " invoked"), null);
        // ★ Fraîcheur : version du document au moment de la requête —
        // l'acceptation du candidat est asynchrone, un écart (l'utilisateur
        // a tapé) fera refuser les additionalTextEdits plutôt que de
        // corrompre le texte.
        final int versionAtRequest = editor.getDocumentVersion();
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
                final int docLen = editor.documentLength();
                int rank = items.size();
                for (org.eclipse.lsp4j.CompletionItem lspItem : items) {
                    jo.codeeditor.lang.model.CompletionItem out = convertItem(
                            lspItem, Math.max(1, rank--),
                            pos -> editorRef.positionToOffset(pos), caret, docLen);
                    // Auto-import — transporte les
                    // additionalTextEdits (insertion de l'instruction
                    // import) vers l'éditeur via un hook post-accept.
                    List<org.eclipse.lsp4j.TextEdit> extra = lspItem.getAdditionalTextEdits();
                    if (extra != null && !extra.isEmpty()) {
                        final List<org.eclipse.lsp4j.TextEdit> extraEdits =
                            new ArrayList<>(extra);
                        final String itemLabel = out.label;
                        out.postApplyAction = () -> {
                            try {
                                editorRef.applyAdditionalEdits(extraEdits, versionAtRequest);
                                android.util.Log.i("LspCompletion",
                                    "auto-import appliqué pour " + itemLabel);
                            } catch (Exception t) {
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

    // ── Conversion LSP → SPI (L1/D-01) ────────────────────────────

    /**
     * Convertit un item de complétion LSP en item SPI — statique pure
     * (fonction d'offsets injectée) pour être testée sans vue liée.
     *
     * <p>Transporte les champs ignorés historiquement :</p>
     * <ul>
     *   <li>{@code insertTextFormat} → {@code isSnippet} (le texte de
     *   snippet voyage brut ; l'expansion est du ressort de la session
     *   d'édition à l'acceptation) ;</li>
     *   <li>{@code textEdit} (variante {@code TextEdit} ou
     *   {@code InsertReplaceEdit}) → plage de remplacement en offsets,
     *   <b>sémantique remplacement</b> retenue et documentée : la plage
     *   {@code replace} est privilégiée (elle couvre le texte à écraser,
     *   y compris le préfixe déjà tapé), la plage {@code insert} n'étant
     *   qu'un repli ; le {@code newText} de l'édition prend précédence
     *   sur {@code insertText} (le protocole ignore ce dernier quand
     *   une édition est fournie) ;</li>
     *   <li>{@code filterText} et {@code sortText}.</li>
     * </ul>
     *
     * <p>Normalisation de la plage (serveurs bogués tolérés) :
     * bornage {@code [0, docLength]} quand la longueur est connue,
     * réordonnancement d'une plage inversée, rejet si la plage démarre
     * strictement après le caret (repli sur la sémantique token, qui
     * remplace {@code [tokenStart, caret)}).</p>
     *
     * @param lspItem   l'item du serveur
     * @param rank      rang de tri (plus haut = meilleur)
     * @param toOffset  conversion Position LSP → offset document
     * @param caret     offset du caret au moment de la requête
     * @param docLength longueur du document, ou -1 si inconnue
     * @return l'item SPI prêt pour le publisher
     */
    static jo.codeeditor.lang.model.CompletionItem convertItem(
            org.eclipse.lsp4j.CompletionItem lspItem, int rank,
            ToIntFunction<Position> toOffset, int caret, int docLength) {
        String label = lspItem.getLabel();
        String detail = lspItem.getDetail() != null ? lspItem.getDetail() : "";
        String insertText = lspItem.getInsertText() != null ? lspItem.getInsertText() : label;
        String kind = lspKindToString(lspItem.getKind());
        // Badge de type : le kind LSP NUMÉRIQUE voyage jusqu'au renderer ;
        // le champ data transporte le raffinement (« annotation »,
        // « package »…) que le protocole ne distingue pas.
        int kindCode = lspItem.getKind() != null ? lspItem.getKind().getValue() : 0;
        String kindTag = lspKindTag(lspItem.getData());
        // Format : snippet (LSP 3.17 — s'applique à insertText comme au
        // newText d'une édition).
        boolean isSnippet = lspItem.getInsertTextFormat() == InsertTextFormat.Snippet;
        // Édition de texte : plage de remplacement + newText prioritaire.
        int replaceStart = -1;
        int replaceEnd = -1;
        org.eclipse.lsp4j.jsonrpc.messages.Either<TextEdit,
                org.eclipse.lsp4j.InsertReplaceEdit> edit = lspItem.getTextEdit();
        if (edit != null) {
            String newText = editNewText(edit);
            if (newText != null) insertText = newText;
            int[] range = normalizedReplaceRange(edit, toOffset, caret, docLength);
            if (range != null) {
                replaceStart = range[0];
                replaceEnd = range[1];
            }
        }
        return new jo.codeeditor.lang.model.CompletionItem(
                label, detail, insertText, kind,
                kindCode, kindTag,
                // Plus haut = meilleur ; la 1re proposition du serveur
                // obtient le score maximal.
                rank, isSnippet,
                lspItem.getFilterText(), lspItem.getSortText(),
                replaceStart, replaceEnd);
    }

    /**
     * Plage de remplacement normalisée d'une édition LSP, en offsets
     * document. Retourne {@code null} quand l'édition est inutilisable
     * (absente, bornes null, entièrement après le caret).
     *
     * <p>Règles (les serveurs bogués ne doivent pas corrompre le texte) :</p>
     * <ol>
     *   <li>Choix de la plage : {@code InsertReplaceEdit} → plage
     *       <b>replace</b> (repli : insert) ; {@code TextEdit} → sa
     *       range.</li>
     *   <li>Conversion en offsets, puis bornage à {@code [0, docLength]}
     *       (fin de document).</li>
     *   <li>Plage inversée → réordonnée.</li>
     *   <li>Démarre strictement après le caret → rejet.</li>
     * </ol>
     */
    static int[] normalizedReplaceRange(
            org.eclipse.lsp4j.jsonrpc.messages.Either<TextEdit,
                    org.eclipse.lsp4j.InsertReplaceEdit> edit,
            ToIntFunction<Position> toOffset, int caret, int docLength) {
        if (edit == null) return null;
        Range range = null;
        if (edit.isLeft() && edit.getLeft() != null) {
            range = edit.getLeft().getRange();
        } else if (edit.isRight() && edit.getRight() != null) {
            org.eclipse.lsp4j.InsertReplaceEdit ire = edit.getRight();
            // Sémantique remplacement : la plage replace couvre le texte
            // à écraser ; la plage insert ne sert que de repli.
            range = ire.getReplace() != null ? ire.getReplace() : ire.getInsert();
        }
        if (range == null || range.getStart() == null || range.getEnd() == null) return null;
        int start = toOffset.applyAsInt(range.getStart());
        int end = toOffset.applyAsInt(range.getEnd());
        if (docLength >= 0) {
            start = Math.max(0, Math.min(start, docLength));
            end = Math.max(0, Math.min(end, docLength));
        } else {
            start = Math.max(0, start);
            end = Math.max(0, end);
        }
        // Plage inversée (serveur bogué) : réordonner.
        if (start > end) {
            int t = start;
            start = end;
            end = t;
        }
        // Plage entièrement après le caret : incohérente avec une
        // acceptation au caret — rejet, repli sur la sémantique token.
        if (start > caret) return null;
        return new int[]{start, end};
    }

    /**
     * Le newText d'une édition LSP (variante TextEdit ou
     * InsertReplaceEdit), ou null.
     */
    static String editNewText(
            org.eclipse.lsp4j.jsonrpc.messages.Either<TextEdit,
                    org.eclipse.lsp4j.InsertReplaceEdit> edit) {
        if (edit == null) return null;
        if (edit.isLeft() && edit.getLeft() != null) return edit.getLeft().getNewText();
        if (edit.isRight() && edit.getRight() != null) return edit.getRight().getNewText();
        return null;
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
                } catch (Exception e) {
                    // Le cast/accès typé peut échouer sur un serveur
                    // exotique — journalisé, repli sur « pas de liste ».
                    android.util.Log.w("LspCompletion",
                        "réponse completion illisible : " + e);
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
