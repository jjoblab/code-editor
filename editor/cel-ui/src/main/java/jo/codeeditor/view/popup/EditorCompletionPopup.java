package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.state.CompletionState;

import androidx.annotation.RestrictTo;

import jo.codeeditor.document.EditorDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * Popup de complétion : état visible, pipeline asynchrone (génération +
 * debounce + lane « interactive » dédiée), filtrage local/fuzzy,
 * mots-clés intégrés et géométrie d'ancrage. Corps déplacés
 * d'EditorPopupManager à l'identique (adaptation des accès délégués).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorCompletionPopup {

    private final EditorView view;
    /** Référence au gestionnaire pour la fermeture croisée des popups. */
    private final EditorPopupManager popups;

    EditorCompletionPopup(EditorView view, EditorPopupManager popups) {
        this.view = view;
        this.popups = popups;
    }

    // ════════════════════════════════════════════════════════════════
    // Completion popup
    // ════════════════════════════════════════════════════════════════

    void setCompletionItems(List<jo.codeeditor.completion.CompletionSession.Item> items,
                            int tokenStart, String prefix) {
        view.completion.reset(items, tokenStart, prefix);
        view.invalidate();
    }

    boolean isCompletionVisible() { return view.completion.visible; }

    void dismissCompletion() {
        // Annule TOUTE livraison en vol / débouncée — sans ça une
        // réponse périmée pouvait rouvrir le popup juste après un Échap.
        completionGeneration.incrementAndGet();
        cancelPendingFetch();
        if (view.completion.visible) {
            // B7 : photographie le popup AVANT de nettoyer son état — le
            // fantôme s'efface en 110 ms par-dessus le contenu.
            view.popupFade.snuff(jo.codeeditor.view.popup.EditorPopupFade.Kind.COMPLETION);
            view.completion.clear();
            view.invalidate();
        }
    }

    // ── État du pipeline async complétion ────────────────────
    /** Compteur de génération — bump à chaque nouvelle frappe/dismiss :
     * toute réponse dont la génération ≠ courante est jetée. */
    private final java.util.concurrent.atomic.AtomicInteger completionGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Débounce avant requête moteur.
     *
     * <p>★ Nécessaire pour éviter de spammer le serveur pendant la
     * frappe rapide (10+ touches/sec) ; les caractères déclencheurs
     * (ex : {@code .}) court-circuitent entièrement ce debounce via le
     * paramètre {@code immediate=true} de {@link #scheduleAsyncFetch}.</p>
     */
    private static final int COMPLETION_DEBOUNCE_MS = 80;
    private Runnable completionDebounceTask;
    private boolean fetchInFlight = false;

    private void cancelPendingFetch() {
        android.os.Handler h = view.getHandler();
        if (completionDebounceTask != null && h != null) {
            h.removeCallbacks(completionDebounceTask);
        }
        completionDebounceTask = null;
    }

    /** Offsets des chars déclencheurs annoncés par le fournisseur LSP. */
    private java.util.List<String> triggerCharacters() {
        try {
            if (view.language != null && view.language.getCompletionProvider() != null) {
                return view.language.getCompletionProvider().getTriggerCharacters();
            }
        } catch (Exception e) {
            // Provider de complétion défaillant : repli sur « aucun
            // caractère déclencheur », journalisé.
            jo.codeeditor.util.EditorLog.warn("EditorCompletion",
                "lecture des caractères déclencheurs échouée", e);
        }
        return java.util.Collections.emptyList();
    }

    private static int tokenStartAt(CharSequence text, int caret) {
        int tokenStart = caret;
        while (tokenStart > 0) {
            char c = text.charAt(tokenStart - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                tokenStart--;
            } else {
                break;
            }
        }
        return tokenStart;
    }

    /** Refiltrage local : délègue à l'état (préservation du libellé
     *  sélectionné + fenêtre de scroll solidaire — invariants possédés
     *  par CompletionState). */
    private void applyFilteredItems(java.util.List<jo.codeeditor.completion.CompletionSession.Item> filtered,
                                    int tokenStart, String prefix) {
        view.completion.refilter(filtered, tokenStart, prefix, completionRowsVisible());
        // La complétion qui s'ouvre referme le hover
        // (exclusion mutuelle — le tooltip est refermé quand la complétion
        // s'ouvre, et le hover est refusé tant qu'elle est ouverte).
        if (view.quickDocVisible) popups.dismissQuickDoc();
        view.invalidate();
    }

    boolean completionSelectUp() {
        if (!view.completion.visible) return false;
        if (view.completion.select(-1, completionRowsVisible())) {
            view.invalidate();
        }
        return true;
    }

    boolean completionSelectDown() {
        if (!view.completion.visible) return false;
        if (view.completion.select(1, completionRowsVisible())) {
            view.invalidate();
        }
        return true;
    }

    boolean completionAccept() {
        if (!view.completion.visible || view.completion.selected < 0
            || view.completion.selected >= view.completion.items.size()
            || view.completion.tokenStart < 0) {
            return false;
        }
        jo.codeeditor.completion.CompletionSession.Item item = view.completion.items.get(view.completion.selected);
        int caret = view.session.getSelection().start;
        // ★ B21b : garde de cohérence — si le caret a RECULÉ avant le début
        // du token (flèches, clic pendant le popup), l'insertion
        // [tokenStart, caret) écraserait une plage inversée AVANT le mot :
        // rejeter l'acceptation, le popup se referme sans muter le texte.
        if (caret < view.completion.tokenStart) {
            dismissCompletion();
            return false;
        }
        if (view.session.isComposing()) {
            view.session.imeFinishComposing();
            if (view.imeBridge != null && !view.imeBridge.listener.isSyncingExtractedText()) {
                view.imeBridge.listener.onRestartInput();
            }
        }
        // L1/D-01 : plage de remplacement — le candidat peut transporter
        // une édition (textEdit, sémantique remplacement) qui couvre tout
        // le texte à écraser, y compris AVANT le token courant. Elle est
        // appliquée EXACTEMENT (jamais le token ET la plage — c'est la
        // double application qui produit « console.console.log »).
        // Sans édition : sémantique historique du token.
        int replaceStart = item.replaceStart >= 0
                ? item.replaceStart : view.completion.tokenStart;
        int replaceEnd = item.replaceEnd >= 0
                ? item.replaceEnd : caret;
        if (item.isSnippet) {
            // Format snippet : expansion par la session de snippet —
            // insertion du texte apuré (aucun marqueur $ résiduel),
            // sélection du premier champ, navigation Tab/Shift+Tab,
            // miroirs et listener de champ.
            view.session.snippets.apply(replaceStart, replaceEnd, item.insertText);
        } else {
            view.session.replaceRange(replaceStart, replaceEnd, item.insertText);
        }
        view.onTextChanged();
        dismissCompletion();
        // Action post-insertion transportée par le backend —
        // ex: application des additionalTextEdits LSP (AUTO-IMPORT d'un
        // type non importé). Exécutée APRÈS l'insertion de l'identifiant :
        // l'édition d'import pointe toujours AVANT le curseur donc les
        // offsets restent valides, et le didChange partira après celui de
        // l'identifiant (les listeners de session sont déjà réarmés).
        if (item.attachment instanceof Runnable) {
            try {
                ((Runnable) item.attachment).run();
            } catch (Exception e) {
                // Ligne de token illisible (offsets périmés) — ignorée.
                jo.codeeditor.util.EditorLog.warn("EditorCompletion",
                    "analyse du token échouée", e);
            }
        }
        return true;
    }

    /**
     * Rafraîchit le popup de complétion — version ASYNCHRONE.
     *
     * <p>Modèle appliqué :</p>
     * <ol>
     *   <li><b>Chemin rapide idem-token</b> (Étendre) : filtre local du cache
     *       base, zéro aller-retour, inchangé.</li>
     *   <li><b>Nouveau token / trigger</b> (Réouvrir) : classification O(1)
     *   sur le thread UI puis requête moteur ANNULABLE (génération +
     *   debounce) exécutée HORS thread UI ; livraison latest-wins
     *   seulement si le caret est toujours dans le token demandé.</li>
     *   <li><b>Préfixe vide</b> : légitime UNIQUEMENT après un char
     *   déclencheur (ex: {@code foo.} → membres). Sinon dismiss.</li>
     * </ol>
     *
     * <p>Anti-scintillement : pendant qu'une requête vole, l'ancienne liste
     * reste visible (inchangée) ; elle est remplacée sous les yeux quand la
     * réponse utile arrive.</p>
     */
    void refreshCompletion() {
        if (view.session == null) return;
        int caret = view.session.getSelection().start;
        // ★ B19/21 : début de token lu SUR LA ROPE (charAt en O(log n),
        // aucune matérialisation) — getText() recopiait le document ENTIER
        // à chaque frappe juste pour lire le mot courant.
        CharSequence text = view.session.getDocument().charSequence();
        int tokenStart = tokenStartAt(text, caret);
        String prefix = view.session.getDocument().subText(tokenStart, caret);

        // ── Lot 3 (audit v3.41.0) : context gate — pas de complétion
        // dans les chaînes, les commentaires ou les fichiers de log.
        // Le popup n'apporte rien dans du texte non-code et clignote
        // intempestivement quand l'utilisateur tape dans une chaîne.
        // On réutilise CodeContext.isInStringOrComment (Lot 1) pour
        // la détection string/comment. Pour log, on vérifie la
        // famille lexicale via le registre.
        if (isInCodeContext(text, caret)) {
            // OK, on continue.
        } else {
            dismissCompletion();
            return;
        }

        // ── Étendre : même token, base en cache → filtrage local pur. ──
        if (!prefix.isEmpty()
                && view.completion.baseTokenStart == tokenStart
                && !view.completion.baseItems.isEmpty()) {
            completionGeneration.incrementAndGet(); // jette toute réponse en vol pour ce token
            cancelPendingFetch();
            applyLocalFilter(tokenStart, prefix);
            return;
        }

        // ── Préfixe vide : ouverture légitime après un char déclencheur ? ──
        // Le caractère reconnu est capturé pour le contexte LSP
        // (triggerKind = TriggerCharacter) — le serveur affine alors ses
        // propositions (membres d'une instance après « . »).
        boolean triggeredByTriggerChar = false;
        char triggerChar = 0;
        if (prefix.isEmpty()) {
            if (caret > 0) {
                char prev = text.charAt(caret - 1);
                for (String t : triggerCharacters()) {
                    if (t != null && !t.isEmpty() && prev == t.charAt(0)) {
                        triggeredByTriggerChar = true;
                        triggerChar = prev;
                        break;
                    }
                }
            }
            if (!triggeredByTriggerChar) {
                dismissCompletion();
                return;
            }
        }
        // ★ Après un char déclencheur (ex : "."), on lance la
        // requête moteur IMMÉDIATEMENT (immediate=true) au lieu d'attendre
        // COMPLETION_DEBOUNCE_MS. Le contexte change radicalement après un
        // "." (membres de l'instance / sous-paquets d'un import) : le
        // debounce n'apporte rien car l'utilisateur marque une pause
        // explicite en tapant le déclencheur, et le flush synchrone garantit
        // que le serveur voit déjà le texte à jour (flushPendingChange
        // SYNCHRONE).
        // Gain perçu : ~80 ms de latence supprimés sur chaque frappe de ".".
        scheduleAsyncFetch(tokenStart, triggeredByTriggerChar,
                triggeredByTriggerChar ? triggerChar : null);
    }

    /**
     * Lot 3 (audit v3.41.0) — détermine si l'ouverture du popup de
     * complétion est légitime à la position du caret. Renvoie
     * {@code false} dans les contextes non-code :
     * <ul>
     *   <li>Le caret est à l'intérieur d'une chaîne ou d'un commentaire
     *       (ligne ou bloc) — délégué à
     *       {@code CodeContext.isInStringOrComment}.</li>
     *   <li>Le langage courant est « log » (famille LOG) — un fichier
     *       de log n'est pas du code, la complétion n'a aucun sens.</li>
     * </ul>
     * Le test est O(caret) sur le thread UI (comme findCallOpen et
     * isInString), acceptable car la frappe est debouncée. Si le
     * caret est hors d'un token valide (au début d'une ligne ou
     * après un séparateur), la complétion reste légitime (le popup
     * s'ouvrira après le prochain caractère).
     */
    private boolean isInCodeContext(CharSequence text, int caret) {
        if (view.session == null) return false;
        // Fichier de log : pas de complétion. Famille XML (xml, html,
        // svg) : la porte chaîne/commentaire est ÉCARTÉE — elle est
        // calibrée pour les littéraux C/Java (une apostrophe dans le
        // TEXTE français « c'est » basculait l'état « dans une chaîne »
        // et tuait la complétion sur tout le reste du fichier ; le
        // curseur entre les guillemets d'une VALEUR d'attribut XML
        // « android:text="|" est une zone de complétion légitime, pas
        // un littéral). Le SERVEUR LSP XML tranche déjà lui-même les
        // zones muettes (texte, fermantes, commentaires <!--, PI <?).
        String lang = view.session.getLanguage();
        if (lang != null) {
            jo.codeeditor.languages.LanguageProfile profile =
                    jo.codeeditor.languages.LanguageRegistry.forName(lang);
            if (profile != null) {
                if (profile.family == jo.codeeditor.languages.SyntaxFamily.LOG) {
                    return false;
                }
                if (profile.family == jo.codeeditor.languages.SyntaxFamily.XML) {
                    return true;
                }
            }
        }
        // Caret dans une chaîne ou un commentaire : pas de complétion.
        // CodeContext est package-private dans cel-core — on utilise
        // une approche simple : si le caret est dans une chaîne ou un
        // commentaire, isInStringOrComment renvoie true. Le prédicat
        // est conscient des chaînes, des commentaires de ligne et de
        // bloc, et est déjà utilisé par SmartTyping pour l'auto-close.
        // CodeContext n'est pas directement accessible depuis cel-ui
        // (package-private), mais EditOps expose les helpers
        // d'édition qui délèguent. On ajoute un wrapper public pour
        // isInStringOrComment.
        return !jo.codeeditor.edit.EditOps.isInStringOrComment(text, caret);
    }

    /** Point d'entrée hôte (Ctrl+Espace / bouton Cmplt) — SANS debounce,
     *  invocation EXPLICITE (triggerKind Invoked). */
    void refreshCompletionFromHost() {
        if (view.session == null) return;
        int caret = view.session.getSelection().start;
        int tokenStart = tokenStartAt(view.session.getDocument().charSequence(), caret); // ★ B19
        scheduleAsyncFetch(tokenStart, true, null);
    }

    /** Planifie la requête moteur avec debounce sauf demande explicite.
     *  {@code triggerCharacter} : caractère déclencheur capté à la frappe
     *  (transporté jusqu'au CompletionContext LSP), ou null. */
    private void scheduleAsyncFetch(int tokenStart, boolean immediate, Character triggerCharacter) {
        final int gen = completionGeneration.incrementAndGet();
        cancelPendingFetch();
        Runnable launch = () -> launchFetch(gen, tokenStart, triggerCharacter);
        android.os.Handler h = view.getHandler();
        if (!immediate && h != null) {
            completionDebounceTask = launch;
            h.postDelayed(launch, COMPLETION_DEBOUNCE_MS);
        } else {
            launchFetch(gen, tokenStart, triggerCharacter);
        }
    }

    /** Requête moteur sur la lane dédiée — le thread UI n'attend JAMAIS.
     *  {@code triggerCharacter} : caractère déclencheur (ou null) transporté
     *  jusqu'au fournisseur — il devient le CompletionContext LSP. */
    private void launchFetch(final int gen, final int tokenStart, final Character triggerCharacter) {
        if (gen != completionGeneration.get()) return; // superseded pendant le debounce
        if (view.session == null) return;
        fetchInFlight = true;
        // Lot 1+3 (audit v3.41.0) : on évite la copie complète du
        // document (O(n) sur le thread UI) tant qu'on n'en a pas
        // besoin. Le prefix est extrait via subText (O(log n +
        // taille du prefix), beaucoup plus rapide). La snapshot
        // complète String n'est prise que si un provider externe
        // est branché (son API prend String en paramètre).
        final int snapCaret = view.session.getSelection().start;
        final int safeTokenStart = Math.min(tokenStart, snapCaret);
        final jo.codeeditor.view.EditorView.CompletionProvider provider = view.completionProvider;
        final String langName = view.session.getLanguage();
        final String prefix = view.session.getDocument().subText(safeTokenStart, snapCaret);
        final String snapText = (provider != null) ? view.session.getText() : null;

        jo.codeeditor.view.EditorTaskLanes.submitCompletion(() -> {
            java.util.List<jo.codeeditor.completion.CompletionSession.Item> result = null;
            try {
                if (provider != null) {
                    result = provider.provide(snapText, snapCaret, safeTokenStart, prefix,
                            triggerCharacter);
                } else {
                    result = builtinKeywordCompletions(langName, prefix);
                }
            } catch (Exception e) {
                jo.codeeditor.util.EditorLog.warn("EditorCompletion",
                    "récupération des complétions échouée", e);
                result = null;
            }
            final java.util.List<jo.codeeditor.completion.CompletionSession.Item> fetched =
                    result != null ? result : java.util.Collections.emptyList();

            Runnable deliver = () -> {
                fetchInFlight = false;
                if (view.session == null) return;
                if (gen != completionGeneration.get()) return;          // superseded
                // Le caret doit toujours être dans le token demandé.
                // ★ B19 : lecture sur la rope — pas de copie complète.
                CharSequence cur = view.session.getDocument().charSequence();
                int curCaret = view.session.getSelection().start;
                if (curCaret < safeTokenStart
                        || Math.min(cur.length(), curCaret) < safeTokenStart
                        || tokenStartAt(cur, curCaret) != safeTokenStart) {
                    return; // une frappe plus récente couvrira ce cas
                }
                String curPrefix = view.session.getDocument().subText(safeTokenStart,
                        Math.min(cur.length(), curCaret));
                if (fetched.isEmpty()) {
                    view.completion.baseItems.clear();
                    view.completion.baseTokenStart = safeTokenStart;
                    if (view.completion.visible) dismissCompletionSoft();
                    return;
                }
                // Anti-blink + remplacement « sous les yeux » : la base
                // fraîche devient la référence du token courant.
                view.completion.baseItems = new ArrayList<>(fetched);
                view.completion.baseTokenStart = safeTokenStart;
                applyFilteredItems(filterCompletionItems(fetched, curPrefix),
                        safeTokenStart, curPrefix);
            };
            android.os.Handler hh = view.getHandler();
            if (hh != null) {
                hh.post(deliver);
            } else {
                deliver.run();
            }
        });
    }

    /** Masque sans casser le cache base (utilisé par la livraison vide). */
    private void dismissCompletionSoft() {
        view.completion.softHide();
        view.invalidate();
    }

    /** Filtrage local synchrone du cache base (chemin rapide idem-token). */
    private void applyLocalFilter(int tokenStart, String prefix) {
        List<jo.codeeditor.completion.CompletionSession.Item> filtered =
            filterCompletionItems(view.completion.baseItems, prefix);
        if (filtered.isEmpty()) {
            view.completion.softHide();
            view.invalidate();
            return;
        }
        applyFilteredItems(filtered, tokenStart, prefix);
    }

    /**
     * La bande verticale écran de la ligne du TOKEN de complétion
     * (haut et bas, en coordonnées écran), fold-aware ET wrap-aware via
     * {@link EditorView#docLineToY(int)} + {@link EditorView#rowsForDocLine(int)}.
     * Le popup ne doit JAMAIS recouvrir cette bande — c'est la ligne où le
     * caret tape.
     */
    private float[] completionCaretBand() {
        EditorDocument doc = view.session.getDocument();
        int line = EditorView.clamp(doc.lineForOffset(view.completion.tokenStart),
                0, doc.lineCount() - 1);
        float top = view.docLineToY(line) - view.vOffset;
        float bottom = top + view.rowsForDocLine(line) * view.metrics.getLineHeight();
        return new float[]{top, bottom};
    }

    /**
     * Nombre de rangées réellement affichables : min(cap, items)
     * RÉDUIT à ce qui tient au-dessus OU en dessous de la ligne du caret
     * (sans la recouvrir), minimum 1. Dessiner les rangées quoi qu'il
     * arrive posait problème : avec le clavier logiciel ouvert
     * (adjustResize), le popup était clamppé à Y=0 et recouvrait la ligne
     * de frappe.
     */
    int completionRowsVisible() {
        int maxRows = Math.min(CompletionState.MAX_ROWS, view.completion.items.size());
        if (maxRows <= 1 || view.getHeight() <= 0 || view.session == null) {
            return Math.max(1, maxRows);
        }
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = CompletionState.ROW_HEIGHT_DP * density;
        float[] band = completionCaretBand();
        // Espaces disponibles SANS recouvrir la ligne du caret :
        float below = view.getHeight() - (band[1] + COMPLETION_ANCHOR_GAP_PX);
        float above = band[0] - COMPLETION_ANCHOR_GAP_PX;
        float best = Math.max(below, above);
        int fit = (int) Math.floor(best / rowH);
        return Math.max(1, Math.min(maxRows, fit));
    }

    /** Écart visuel (px) entre le popup et la ligne du caret. */
    private static final int COMPLETION_ANCHOR_GAP_PX = 4;

    float[] completionPopupAnchor() {
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = CompletionState.ROW_HEIGHT_DP * density;
        float width = CompletionState.WIDTH_DP * density;
        int rowsToShow = completionRowsVisible();
        float popupH = rowH * rowsToShow;
        EditorDocument doc = view.session.getDocument();
        int line = EditorView.clamp(doc.lineForOffset(view.completion.tokenStart), 0, doc.lineCount() - 1);
        int col = view.completion.tokenStart - doc.lineStart(line);
        // Ancrage tenant compte des inlays — le popup suit le X du caret tissé.
        float anchorX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
            + view.visualColFor(line, col) * view.metrics.getCharWidth() - view.hOffset;
        // Bas/haut de la ligne fold+wrap-aware (l'ancienne formule brute
        // padTop + (line+1)*lineHeight plaçait l'ancre au-dessus de la
        // rangée visuelle réelle dès qu'une ligne précédente était wrappée
        // ou pliée → le popup recouvrait la ligne de frappe).
        float[] band = completionCaretBand();
        float caretBottomY = band[1];
        float caretTopY = band[0];
        // D2 : pattern « popup ancré » factorisé (préfère EN DESSOUS de la
        // ligne de frappe, bascule AU-DESSUS s'il ne tient pas — le popup
        // ne recouvre JAMAIS la ligne du caret), clamp horizontal à la
        // gouttière. Paramétrage identique au calcul historique :
        // gap 4 px, yMargin 0, seuil X 0, inset X 4 px, ancre gauche.
        float[] pos = EditorPopupAnchors.anchorAboveBelow(view,
                anchorX, caretTopY, caretBottomY, width, popupH,
                false, COMPLETION_ANCHOR_GAP_PX, 0f,
                0f, 4f, false, view.metrics.getGutterWidth());
        return new float[]{pos[0], pos[1], width, popupH, rowH};
    }

    private List<jo.codeeditor.completion.CompletionSession.Item> filterCompletionItems(
            List<jo.codeeditor.completion.CompletionSession.Item> items, String prefix) {
        if (prefix.isEmpty()) return items;
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);
        List<jo.codeeditor.completion.CompletionSession.Item> exact = new ArrayList<>();
        List<jo.codeeditor.completion.CompletionSession.Item> exactCi = new ArrayList<>();
        List<jo.codeeditor.completion.CompletionSession.Item> prefixMatch = new ArrayList<>();
        List<jo.codeeditor.completion.CompletionSession.Item> prefixMatchCi = new ArrayList<>();
        List<jo.codeeditor.completion.CompletionSession.Item> fuzzy = new ArrayList<>();
        for (jo.codeeditor.completion.CompletionSession.Item item : items) {
            // L1/D-01 : le filterText (posé par le pont LSP) sert de
            // repli quand le label n'apparie nulle part — le candidat
            // est classé selon la qualité du match sur son texte de
            // filtrage.
            String match = classifies(item.label, prefix, lowerPrefix)
                    ? item.label
                    : (item.filterText != null && !item.filterText.isEmpty()
                            && classifies(item.filterText, prefix, lowerPrefix)
                            ? item.filterText : item.label);
            if (match.equals(prefix)) {
                exact.add(item);
            } else if (match.equalsIgnoreCase(prefix)) {
                exactCi.add(item);
            } else if (match.startsWith(prefix)) {
                prefixMatch.add(item);
            } else if (match.toLowerCase(java.util.Locale.ROOT).startsWith(lowerPrefix)) {
                prefixMatchCi.add(item);
            } else if (fuzzyMatches(match, prefix)) {
                fuzzy.add(item);
            }
        }
        List<jo.codeeditor.completion.CompletionSession.Item> out = new ArrayList<>();
        out.addAll(exact);
        out.addAll(exactCi);
        out.addAll(prefixMatch);
        out.addAll(prefixMatchCi);
        out.addAll(fuzzy);
        return out;
    }

    /** Le candidat matche-t-il le préfixe sur l'un des 5 paliers ? */
    private static boolean classifies(String candidate, String prefix, String lowerPrefix) {
        return candidate.equals(prefix) || candidate.equalsIgnoreCase(prefix)
                || candidate.startsWith(prefix)
                || candidate.toLowerCase(java.util.Locale.ROOT).startsWith(lowerPrefix)
                || fuzzyMatches(candidate, prefix);
    }

    private static boolean fuzzyMatches(String candidate, String query) {
        if (query.isEmpty()) return true;
        int ci = 0, qi = 0;
        while (qi < query.length() && ci < candidate.length()) {
            if (Character.toLowerCase(candidate.charAt(ci))
                == Character.toLowerCase(query.charAt(qi))) {
                qi++;
            }
            ci++;
        }
        return qi == query.length();
    }

    private static List<jo.codeeditor.completion.CompletionSession.Item> builtinKeywordCompletions(
            String language, String prefix) {
        List<jo.codeeditor.completion.CompletionSession.Item> out = new ArrayList<>();
        // Lot 3 (audit v3.41.0) : la liste est désormais pilotée
        // par le registre des langages. Tous les langages
        // enregistrés (java, kotlin, c, c++, go, rust, python, js,
        // ts, sql, shell, yaml, json, lua, ruby, php, swift, dart,
        // groovy, scala, smali, etc.) bénéficient désormais de la
        // complétion de mots-clés — ils n'étaient que 4 avant
        // (java, kotlin, xml, markdown).
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);
        // Snippets Markdown : liste hardcoded conservée car les
        // « mots-clés » Markdown sont des snippets (# , ## , - , etc.)
        // qui ne sont pas des mots-clés au sens du registre.
        if ("markdown".equals(language) || "md".equals(language)) {
            String[] mdSnippets = {
                "# ", "## ", "### ", "- ", "* ", "1. ", "> ", "```", "```java", "```kotlin",
                "```xml", "[", "](", "![", "*italic*", "**bold**", "---", "| ", "---|---"
            };
            for (String s : mdSnippets) {
                if (s.toLowerCase(java.util.Locale.ROOT).startsWith(lowerPrefix)
                        && s.length() > prefix.length()) {
                    out.add(new jo.codeeditor.completion.CompletionSession.Item(
                        s, "snippet", s, "s", 15, 0, false, false, null));
                }
            }
            return out;
        }
        // Mots-clés du registre : tous langages enregistrés.
        jo.codeeditor.languages.LanguageProfile profile =
                jo.codeeditor.languages.LanguageRegistry.forName(language);
        if (profile == null) return out;
        if (profile.keywords == null || profile.keywords.isEmpty()) {
            return out;
        }
        for (String kw : profile.keywords) {
            if (kw == null || kw.isEmpty()) continue;
            if (kw.toLowerCase(java.util.Locale.ROOT).startsWith(lowerPrefix)
                    && kw.length() > prefix.length()) {
                out.add(new jo.codeeditor.completion.CompletionSession.Item(
                    // ★ Kind 14 (Keyword, numérotation LSP moderne) — le
                    // badge du popup en dépend. isKeyword=true reste
                    // inchangé.
                    kw, "keyword", kw, "k", 14, 0, true, false, null));
            }
        }
        return out;
    }
}
