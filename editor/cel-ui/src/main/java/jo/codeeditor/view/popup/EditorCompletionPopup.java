package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

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

    // ── Exécuteurs dédiés (modèle CodeAssist) ─────────────────────
    /**
     * Lane « interactive » côté éditeur : la complétion possède son propre
     * worker mono-thread daemon — AUCUN autre consommateur (hover,
     * highlights, inlays…) ne peut la retarder. Toute la mécanique de
     * requête/réponse LSP se déroule ici : le thread UI n'y touche plus
     * jamais un {@code future.get()}.
     */
    private static final java.util.concurrent.ExecutorService COMPLETION_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "code-editor-completion");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY + 1);
                return t;
            });

    // ════════════════════════════════════════════════════════════════
    // Completion popup
    // ════════════════════════════════════════════════════════════════

    void setCompletionItems(List<jo.codeeditor.completion.CompletionSession.Item> items,
                            int tokenStart, String prefix) {
        view.completionItems.clear();
        if (items != null) view.completionItems.addAll(items);
        view.completionTokenStart = tokenStart;
        view.completionPrefix = prefix == null ? "" : prefix;
        view.completionSelected = 0;
        view.completionScrollOffset = 0;
        view.completionVisible = !view.completionItems.isEmpty();
        view.invalidate();
    }

    boolean isCompletionVisible() { return view.completionVisible; }

    void dismissCompletion() {
        // Annule TOUTE livraison en vol / débouncée — sans ça une
        // réponse périmée pouvait rouvrir le popup juste après un Échap.
        completionGeneration.incrementAndGet();
        cancelPendingFetch();
        if (view.completionVisible) {
            view.completionVisible = false;
            view.completionItems.clear();
            view.completionBaseItems.clear();
            view.completionBaseTokenStart = -1;
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
        } catch (Throwable ignored) {
        }
        return java.util.Collections.emptyList();
    }

    private static int tokenStartAt(String text, int caret) {
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

    /** Préserve l'item sélectionné par label au remplacement de liste. */
    private void applyFilteredItems(java.util.List<jo.codeeditor.completion.CompletionSession.Item> filtered,
                                    int tokenStart, String prefix) {
        String prevSelectedLabel = (view.completionSelected >= 0
            && view.completionSelected < view.completionItems.size())
            ? view.completionItems.get(view.completionSelected).label : null;
        view.completionItems.clear();
        view.completionItems.addAll(filtered);
        view.completionTokenStart = tokenStart;
        view.completionPrefix = prefix;
        int newSel = 0;
        if (prevSelectedLabel != null) {
            for (int i = 0; i < view.completionItems.size(); i++) {
                if (view.completionItems.get(i).label.equals(prevSelectedLabel)) {
                    newSel = i;
                    break;
                }
            }
        }
        view.completionSelected = newSel;
        int rowsVisible = completionRowsVisible();
        if (view.completionSelected < view.completionScrollOffset) {
            view.completionScrollOffset = view.completionSelected;
        } else if (view.completionSelected >= view.completionScrollOffset + rowsVisible) {
            view.completionScrollOffset = view.completionSelected - rowsVisible + 1;
        }
        view.completionVisible = true;
        // Parité Sora : la complétion qui s'ouvre referme le hover
        // (exclusion mutuelle — Sora dismiss le tooltip quand la complétion
        // s'ouvre, et refuse le hover tant qu'elle est ouverte).
        if (view.quickDocVisible) popups.dismissQuickDoc();
        view.invalidate();
    }

    boolean completionSelectUp() {
        if (!view.completionVisible) return false;
        if (view.completionSelected > 0) {
            view.completionSelected--;
            if (view.completionSelected < view.completionScrollOffset) {
                view.completionScrollOffset = view.completionSelected;
            }
            view.invalidate();
        }
        return true;
    }

    boolean completionSelectDown() {
        if (!view.completionVisible) return false;
        if (view.completionSelected < view.completionItems.size() - 1) {
            view.completionSelected++;
            int rowsVisible = completionRowsVisible();
            if (view.completionSelected >= view.completionScrollOffset + rowsVisible) {
                view.completionScrollOffset = view.completionSelected - rowsVisible + 1;
            }
            view.invalidate();
        }
        return true;
    }

    boolean completionAccept() {
        if (!view.completionVisible || view.completionSelected < 0
            || view.completionSelected >= view.completionItems.size()
            || view.completionTokenStart < 0) {
            return false;
        }
        jo.codeeditor.completion.CompletionSession.Item item = view.completionItems.get(view.completionSelected);
        int caret = view.session.getSelection().start;
        if (view.session.isComposing()) {
            view.session.imeFinishComposing();
            if (view.imeBridge != null && !view.imeBridge.listener.isSyncingExtractedText()) {
                view.imeBridge.listener.onRestartInput();
            }
        }
        view.session.replaceRange(view.completionTokenStart, caret, item.insertText);
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
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    /**
     * Rafraîchit le popup de complétion — version ASYNCHRONE.
     *
     * <p>Modèle CodeAssist appliqué :</p>
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
        String text = view.session.getText();
        int tokenStart = tokenStartAt(text, caret);
        String prefix = text.substring(tokenStart, caret);

        // ── Étendre : même token, base en cache → filtrage local pur. ──
        if (!prefix.isEmpty()
                && view.completionBaseTokenStart == tokenStart
                && !view.completionBaseItems.isEmpty()) {
            completionGeneration.incrementAndGet(); // jette toute réponse en vol pour ce token
            cancelPendingFetch();
            applyLocalFilter(tokenStart, prefix);
            return;
        }

        // ── Préfixe vide : ouverture légitime après un char déclencheur ? ──
        boolean triggeredByTriggerChar = false;
        if (prefix.isEmpty()) {
            boolean triggered = false;
            if (caret > 0) {
                char prev = text.charAt(caret - 1);
                for (String t : triggerCharacters()) {
                    if (t != null && !t.isEmpty() && prev == t.charAt(0)) {
                        triggered = true;
                        break;
                    }
                }
            }
            if (!triggered) {
                dismissCompletion();
                return;
            }
            triggeredByTriggerChar = true;
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
        scheduleAsyncFetch(tokenStart, triggeredByTriggerChar);
    }

    /** Point d'entrée hôte (Ctrl+Espace / bouton Cmplt) — SANS debounce. */
    void refreshCompletionFromHost() {
        if (view.session == null) return;
        int caret = view.session.getSelection().start;
        int tokenStart = tokenStartAt(view.session.getText(), caret);
        scheduleAsyncFetch(tokenStart, true);
    }

    /** Planifie la requête moteur avec debounce sauf demande explicite. */
    private void scheduleAsyncFetch(int tokenStart, boolean immediate) {
        final int gen = completionGeneration.incrementAndGet();
        cancelPendingFetch();
        Runnable launch = () -> launchFetch(gen, tokenStart);
        android.os.Handler h = view.getHandler();
        if (!immediate && h != null) {
            completionDebounceTask = launch;
            h.postDelayed(launch, COMPLETION_DEBOUNCE_MS);
        } else {
            launchFetch(gen, tokenStart);
        }
    }

    /** Requête moteur sur la lane dédiée — le thread UI n'attend JAMAIS. */
    private void launchFetch(final int gen, final int tokenStart) {
        if (gen != completionGeneration.get()) return; // superseded pendant le debounce
        if (view.session == null) return;
        fetchInFlight = true;
        final String snapText = view.session.getText();
        final int snapCaret = view.session.getSelection().start;
        final int safeTokenStart = Math.min(tokenStart, snapCaret);
        final String prefix = snapText.substring(safeTokenStart, snapCaret);
        final jo.codeeditor.view.EditorView.CompletionProvider provider = view.completionProvider;
        final String langName = view.session.getLanguage();

        COMPLETION_EXECUTOR.execute(() -> {
            java.util.List<jo.codeeditor.completion.CompletionSession.Item> result = null;
            try {
                if (provider != null) {
                    result = provider.provide(snapText, snapCaret, safeTokenStart, prefix);
                } else {
                    result = builtinKeywordCompletions(langName, prefix);
                }
            } catch (Throwable ignored) {
                result = null;
            }
            final java.util.List<jo.codeeditor.completion.CompletionSession.Item> fetched =
                    result != null ? result : java.util.Collections.emptyList();

            Runnable deliver = () -> {
                fetchInFlight = false;
                if (view.session == null) return;
                if (gen != completionGeneration.get()) return;          // superseded
                // Le caret doit toujours être dans le token demandé.
                String cur = view.session.getText();
                int curCaret = view.session.getSelection().start;
                if (curCaret < safeTokenStart
                        || Math.min(cur.length(), curCaret) < safeTokenStart
                        || tokenStartAt(cur, curCaret) != safeTokenStart) {
                    return; // une frappe plus récente couvrira ce cas
                }
                String curPrefix = cur.substring(safeTokenStart,
                        Math.min(cur.length(), curCaret));
                if (fetched.isEmpty()) {
                    view.completionBaseItems.clear();
                    view.completionBaseTokenStart = safeTokenStart;
                    if (view.completionVisible) dismissCompletionSoft();
                    return;
                }
                // Anti-blink + remplacement « sous les yeux » : la base
                // fraîche devient la référence du token courant.
                view.completionBaseItems = new ArrayList<>(fetched);
                view.completionBaseTokenStart = safeTokenStart;
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
        view.completionVisible = false;
        view.invalidate();
    }

    /** Filtrage local synchrone du cache base (chemin rapide idem-token). */
    private void applyLocalFilter(int tokenStart, String prefix) {
        List<jo.codeeditor.completion.CompletionSession.Item> filtered =
            filterCompletionItems(view.completionBaseItems, prefix);
        if (filtered.isEmpty()) {
            view.completionVisible = false;
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
        int line = EditorView.clamp(doc.lineForOffset(view.completionTokenStart),
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
        int maxRows = Math.min(view.COMPLETION_MAX_ROWS, view.completionItems.size());
        if (maxRows <= 1 || view.getHeight() <= 0 || view.session == null) {
            return Math.max(1, maxRows);
        }
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.COMPLETION_ROW_HEIGHT_DP * density;
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
        float rowH = view.COMPLETION_ROW_HEIGHT_DP * density;
        float width = view.COMPLETION_WIDTH_DP * density;
        int rowsToShow = completionRowsVisible();
        float popupH = rowH * rowsToShow;
        EditorDocument doc = view.session.getDocument();
        int line = EditorView.clamp(doc.lineForOffset(view.completionTokenStart), 0, doc.lineCount() - 1);
        int col = view.completionTokenStart - doc.lineStart(line);
        // Ancrage tenant compte des inlays — le popup suit le X du caret tissé.
        float anchorX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
            + view.visualColFor(line, col) * view.metrics.getCharWidth() - view.hOffset;
        // Bas de la ligne fold+wrap-aware (l'ancienne formule brute
        // padTop + (line+1)*lineHeight plaçait l'ancre au-dessus de la
        // rangée visuelle réelle dès qu'une ligne précédente était wrappée
        // ou pliée → le popup recouvrait la ligne de frappe).
        float[] band = completionCaretBand();
        float caretBottomY = band[1];
        float caretTopY = band[0];
        float anchorY = caretBottomY + COMPLETION_ANCHOR_GAP_PX;
        float viewW = view.getWidth();
        if (anchorX + width > viewW) anchorX = Math.max(view.metrics.getGutterWidth(), viewW - width - 4);
        // Le popup ne recouvre JAMAIS la ligne du caret — s'il ne
        // tient pas en dessous il passe AU-DESSUS (bas du popup = haut de
        // la ligne − gap). Un simple clamp Math.max(0, …) laissait le popup
        // démarrer à Y=0 et couvrir la frappe.
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, caretTopY - COMPLETION_ANCHOR_GAP_PX - popupH);
        }
        return new float[]{anchorX, anchorY, width, popupH, rowH};
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
            String label = item.label;
            if (label.equals(prefix)) {
                exact.add(item);
            } else if (label.equalsIgnoreCase(prefix)) {
                exactCi.add(item);
            } else if (label.startsWith(prefix)) {
                prefixMatch.add(item);
            } else if (label.toLowerCase(java.util.Locale.ROOT).startsWith(lowerPrefix)) {
                prefixMatchCi.add(item);
            } else if (fuzzyMatches(label, prefix)) {
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
        String[] kws;
        switch (language == null ? "" : language) {
            case "java": kws = new String[]{
                "public", "private", "protected", "static", "final", "void", "int", "long",
                "double", "float", "boolean", "char", "byte", "short", "class", "interface",
                "enum", "extends", "implements", "import", "package", "new", "return", "if",
                "else", "for", "while", "do", "switch", "case", "break", "continue",
                "try", "catch", "finally", "throw", "throws", "this", "super", "null",
                "true", "false", "instanceof", "synchronized", "volatile", "transient",
                "abstract", "default"
            }; break;
            case "kotlin": kws = new String[]{
                "fun", "val", "var", "class", "object", "interface", "enum", "sealed",
                "data", "when", "is", "as", "in", "out", "by", "init", "constructor",
                "companion", "override", "open", "abstract", "final", "private", "protected",
                "public", "internal", "import", "package", "return", "if", "else", "for",
                "while", "do", "break", "continue", "try", "catch", "finally", "throw",
                "this", "super", "null", "true", "false", "typealias", "operator", "infix",
                "inline", "suspend", "lateinit"
            }; break;
            case "xml": kws = new String[]{
                "android:layout_width", "android:layout_height", "android:layout_margin",
                "android:layout_padding", "android:id", "android:text", "android:textSize",
                "android:textColor", "android:background", "android:orientation",
                "android:gravity", "android:layout_gravity", "android:layout_weight",
                "android:src", "android:visibility", "android:enabled", "android:onClick",
                "android:hint", "android:inputType", "android:maxLines", "android:minLines",
                "LinearLayout", "RelativeLayout", "FrameLayout", "ConstraintLayout",
                "TextView", "Button", "EditText", "ImageView", "ScrollView", "RecyclerView"
            }; break;
            case "markdown": kws = new String[]{
                "# ", "## ", "### ", "- ", "* ", "1. ", "> ", "```", "```java", "```kotlin",
                "```xml", "[", "](", "![", "*italic*", "**bold**", "---", "| ", "---|---"
            }; break;
            default: kws = new String[0];
        }
        String lowerPrefix = prefix.toLowerCase(java.util.Locale.ROOT);
        for (String kw : kws) {
            if (kw.toLowerCase(java.util.Locale.ROOT).startsWith(lowerPrefix) && kw.length() > prefix.length()) {
                out.add(new jo.codeeditor.completion.CompletionSession.Item(
                    // ★ Kind 14 (Keyword, numérotation LSP moderne) — le
                    // badge du popup en dépend. isKeyword=true reste
                    // inchangé.
                    kw, "keyword", kw, "k", 14, 0, true, false));
            }
        }
        return out;
    }
}
