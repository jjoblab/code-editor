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

    /**
     * Dernier lot servi, par libellé — la cible de
     * {@code completionItem/resolve} (le champ {@code data} du protocole
     * doit faire l'aller-retour TEL QUEL : c'est l'item ORIGINAL qu'on
     * renvoie au serveur, jamais une reconstruction). Vidé à chaque lot.
     */
    private final java.util.Map<String, org.eclipse.lsp4j.CompletionItem> lastBatchByLabel =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Documentations déjà résolues, par libellé — un lot re-servi (re-
     * requête du même token) les affiche IMMÉDIATEMENT sans nouvelle
     * requête de résolution. Borné par la taille des lots (le drapeau
     * « liste tronquée » plafonne déjà la base côté serveur).
     */
    private final java.util.Map<String, String> resolvedDocumentation =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        LanguageServer server = editor.readyServer(wrapper); // garde commun 3.74.0 : null si déconnecté (C1)
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
        // Drapeau « liste tronquée » du protocole (CompletionList.
        // isIncomplete) : vrai = le serveur a PLAFONNÉ sa réponse et
        // pourrait produire d'autres candidats pour le même token.
        // Transporté jusqu'au publisher : le client saura que son
        // filtrage local travaille sur une base partielle (un
        // candidat écarté par le plafond ne peut pas réapparaître en
        // filtrant davantage) et re-requêtera à chaque édition du
        // préfixe. Une liste nue (Either.left) est réputée complète.
        boolean listIncomplete = false;
        try {
            CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<org.eclipse.lsp4j.CompletionItem>, CompletionList>> future =
                server.getTextDocumentService().completion(params);
            org.eclipse.lsp4j.jsonrpc.messages.Either<List<org.eclipse.lsp4j.CompletionItem>, CompletionList> result =
                LspRequestTimeouts.await(future, wrapper.requestTimeouts().completionMs());
            List<org.eclipse.lsp4j.CompletionItem> items;
            if (result.isLeft()) {
                items = result.getLeft();
            } else {
                CompletionList list = result.getRight();
                items = list != null ? list.getItems() : null;
                listIncomplete = list != null && list.isIncomplete();
            }
            int count = items != null ? items.size() : 0;
            log("completion: got " + count + " items"
                    + (listIncomplete ? " (liste incomplète)" : ""), null);
            if (items != null) {
                // La cible du resolve : le lot ORIGINAL par libellé — le
                // champ data du protocole fait l'aller-retour tel quel.
                lastBatchByLabel.clear();
                for (org.eclipse.lsp4j.CompletionItem lspItem : items) {
                    if (lspItem.getLabel() != null) {
                        lastBatchByLabel.put(lspItem.getLabel(), lspItem);
                    }
                }
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
                    // Documentation déjà résolue pour ce libellé (lot
                    // re-servi) : pré-remplie — le popup n'attend plus le
                    // séjour de sélection, le panneau est immédiat.
                    if (out.documentation == null) {
                        String cached = resolvedDocumentation.get(out.label);
                        if (cached != null) {
                            out = withDocumentation(out, cached);
                        }
                    }
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
                                jo.codeeditor.util.EditorLog.info("LspCompletion",
                                    "auto-import appliqué pour " + itemLabel);
                            } catch (Exception t) {
                                jo.codeeditor.util.EditorLog.warn("LspCompletion",
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
        // Le drapeau voyage avec le lot : posé AVANT le flush pour que
        // le consommateur lise un état cohérent (items + troncature).
        // Idempotent, sans effet pour les publishers qui l'ignorent.
        publisher.setIncomplete(listIncomplete);
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

    /**
     * Le serveur annonce-t-il {@code resolveProvider} ? — la porte de
     * la résolution différée : sans elle, AUCUNE requête
     * {@code completionItem/resolve} n'est émise (le protocole
     * l'interdit, la discipline des inlay hints appliquée à la
     * complétion).
     */
    private boolean resolveSupported() {
        return wrapper.getCapabilities() != null
            && wrapper.getCapabilities().getCompletionProvider() != null
            && Boolean.TRUE.equals(
                wrapper.getCapabilities().getCompletionProvider().getResolveProvider());
    }

    /**
     * ★ 4.7.0 — Résolution PARESSEUSE de la documentation d'un candidat
     * du dernier lot ({@code completionItem/resolve}). Le contrat du
     * SPI est respecté à la lettre : jamais bloquant pour l'appelant
     * (la requête part en asynchrone sur les threads du pont), callback
     * depuis n'importe quel thread, {@code null} quand il n'y a rien à
     * résoudre.
     *
     * <p>Garde-fous :</p>
     * <ul>
     *   <li>porte capacité {@code resolveProvider} — sans annonce,
     *       callback null immédiat, zéro requête ;</li>
     *   <li>le libellé doit être CONNU du dernier lot (c'est l'item
     *       ORIGINAL qui voyage au serveur — son champ {@code data}
     *       fait l'aller-retour) ; un libellé inconnu est ignoré sans
     *       requête ;</li>
     *   <li>cache : une documentation déjà résolue est resservie sans
     *       nouvelle requête (et pré-remplit les lots suivants) ;</li>
     *   <li>timeout {@code completionResolveMs} (3 s par défaut) — un
     *       serveur silencieux rend la main, callback null ;</li>
     *   <li>erreur/annulation : callback null, jamais d'exception vers
     *       l'appelant.</li>
     * </ul>
     */
    @Override
    public void resolveDocumentation(String label,
                                     java.util.function.Consumer<String> callback) {
        if (label == null) {
            callback.accept(null);
            return;
        }
        // Cache d'abord : une documentation déjà résolue ne re-requête
        // JAMAIS le serveur.
        String cached = resolvedDocumentation.get(label);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        LanguageServer server = editor.readyServer(wrapper);
        if (server == null || !resolveSupported()) {
            callback.accept(null);
            return;
        }
        org.eclipse.lsp4j.CompletionItem original = lastBatchByLabel.get(label);
        if (original == null) {
            // Libellé inconnu du dernier lot (requête périmée, item
            // reconstruit par l'appelant) : sans le champ data de
            // l'original, le serveur ne peut rien résoudre — ignoré.
            callback.accept(null);
            return;
        }
        log("completion/resolve: requesting doc for \"" + label + "\"", null);
        final long timeoutMs = wrapper.requestTimeouts().completionResolveMs();
        java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.CompletionItem> future =
                server.getTextDocumentService().resolveCompletionItem(original);
        // Timeout compatible minSdk 24 : CompletableFuture.orTimeout exige
        // l'API 31 — une tâche planifiée complète le futur en exception à
        // l'échéance, même sémantique (TimeoutException à la livraison),
        // l'appelant n'est JAMAIS bloqué (contrat du SPI). Le planificateur
        // est POSSÉDÉ par le wrapper (fermé au shutdown — aucun thread
        // cel-lsp-* ne survit à la déconnexion).
        final java.util.concurrent.ScheduledFuture<?> garde =
                wrapper.scheduleResolveTimeout(() -> {
                    future.completeExceptionally(
                            new java.util.concurrent.TimeoutException(
                                    "completionItem/resolve > " + timeoutMs + " ms"));
                }, timeoutMs);
        future.whenComplete((resolved, err) -> {
            if (garde != null) {
                garde.cancel(false);
            }
            String doc = null;
            if (err == null && resolved != null) {
                doc = documentationOf(resolved);
            } else if (err != null) {
                log("completion/resolve: " + label + " — " + err.getClass().getSimpleName(), null);
            }
            if (doc != null) {
                resolvedDocumentation.put(label, doc);
            }
            // Le callback peut venir d'ICI de n'importe quel thread
            // — le consommateur marshalle vers le sien (contrat SPI).
            try {
                callback.accept(doc);
            } catch (Exception t) {
                // Un consommateur mort ne doit pas tuer le thread du pont.
                jo.codeeditor.util.EditorLog.warn("LspCompletion",
                    "callback resolve échoué", t);
            }
        });
    }

    /** Copie d'un item SPI avec la documentation posée (champs finaux). */
    private static jo.codeeditor.lang.model.CompletionItem withDocumentation(
            jo.codeeditor.lang.model.CompletionItem item, String documentation) {
        jo.codeeditor.lang.model.CompletionItem copy =
            new jo.codeeditor.lang.model.CompletionItem(
                item.label, item.detail, item.insertText, item.kind, item.kindCode,
                item.kindTag, item.sortPriority, item.isSnippet, item.filterText,
                item.sortText, item.replaceStart, item.replaceEnd, item.container,
                documentation);
        copy.postApplyAction = item.postApplyAction;
        return copy;
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
        // Champs riches (4.3.0) — origine du symbole et documentation.
        // labelDetails.description : l'origine directe du protocole
        // (paquet d'un type, classe déclarante d'un membre).
        org.eclipse.lsp4j.CompletionItemLabelDetails labelDetails = lspItem.getLabelDetails();
        String container = null;
        if (labelDetails != null && labelDetails.getDescription() != null
                && !labelDetails.getDescription().isEmpty()) {
            container = labelDetails.getDescription();
        } else {
            // Repli : la partie qualifiée du detail quand c'est un nom
            // pleinement qualifié sans signature — « java.util.ArrayList »
            // (type) donne « java.util », « pkg.Type.bar » (membre) donne
            // « pkg.Type » : dans les deux cas on retire le dernier
            // segment, celui que le libellé affiche déjà.
            container = containerFromDetail(detail);
        }
        // labelDetails.detail (la signature) enrichit un detail vide —
        // la place du detail dans le popup ne change pas.
        if ((detail.isEmpty()) && labelDetails != null
                && labelDetails.getDetail() != null && !labelDetails.getDetail().isEmpty()) {
            detail = labelDetails.getDetail();
        }
        // documentation : texte brut ou MarkupContent — transportée telle
        // quelle (le rendu est du ressort de l'hôte).
        String documentation = documentationOf(lspItem);
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
                replaceStart, replaceEnd,
                container, documentation);
    }

    /**
     * Origine dérivée du champ LSP {@code detail} quand
     * {@code labelDetails.description} est absent : si le detail est un
     * nom pleinement qualifié (au moins deux segments séparés par des
     * points, sans signature — les arguments génériques sont ignorés),
     * le conteneur est ce nom moins son dernier segment (celui que le
     * libellé affiche déjà). Sinon null (origine inconnue).
     */
    private static String containerFromDetail(String detail) {
        if (detail == null || detail.isEmpty()) return null;
        // Coupe au premier caractère hors identifiant (génériques,
        // signature, unité de retour…).
        int end = 0;
        while (end < detail.length()) {
            char c = detail.charAt(end);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                end++;
            } else {
                break;
            }
        }
        String qualified = detail.substring(0, end);
        int lastDot = qualified.lastIndexOf('.');
        // Au moins deux segments (« pkg.Type » ou « pkg.Type.membre ») ;
        // le dernier point ne peut pas être final (« foo. »).
        if (lastDot <= 0 || lastDot == qualified.length() - 1) return null;
        return qualified.substring(0, lastDot);
    }

    /**
     * La documentation LSP d'un item — texte brut ou
     * {@code MarkupContent} (la valeur est transportée telle quelle).
     */
    private static String documentationOf(org.eclipse.lsp4j.CompletionItem lspItem) {
        org.eclipse.lsp4j.jsonrpc.messages.Either<String,
                org.eclipse.lsp4j.MarkupContent> doc = lspItem.getDocumentation();
        if (doc == null) return null;
        if (doc.isLeft() && doc.getLeft() != null && !doc.getLeft().isEmpty()) {
            return doc.getLeft();
        }
        if (doc.isRight() && doc.getRight() != null
                && doc.getRight().getValue() != null && !doc.getRight().getValue().isEmpty()) {
            return doc.getRight().getValue();
        }
        return null;
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
        jo.codeeditor.util.EditorLog.info("LspCompletion", message);
        if (t != null) jo.codeeditor.util.EditorLog.error("LspCompletion", message, t);
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
                    jo.codeeditor.util.EditorLog.warn("LspCompletion",
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
