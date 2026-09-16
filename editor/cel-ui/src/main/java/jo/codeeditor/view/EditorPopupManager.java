package jo.codeeditor.view;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.10.0: Extracted from EditorView — manages all popup state and logic:
 * completion, signature help, quick doc, code actions, go-to-symbol,
 * go-to-line, rename, diagnostic popup, diagnostic sheet.
 *
 * <p>Each popup has a {@code show()}, {@code dismiss()}, and (where
 * applicable) {@code hitTest()} and {@code accept()} method. The popup
 * state fields (visibility flags, selected index, scroll offset, etc.)
 * live here; the actual Canvas drawing stays in {@link EditorRenderer}
 * and reads the state via the {@code view} reference.
 *
 * <p>EditorView delegates its public popup API to this class:
 * <pre>
 *   public void showGoToSymbol() { popupManager.showGoToSymbol(); }
 *   public void dismissGoToSymbol() { popupManager.dismissGoToSymbol(); }
 *   ...
 * </pre>
 *
 * @since v3.10.0
 */
class EditorPopupManager {

    private final EditorView view;

    EditorPopupManager(EditorView view) {
        this.view = view;
    }

    // ── v0.1.0.50 : exécuteurs dédiés (modèle CodeAssist) ─────────
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

    /**
     * Lane « features » : signature help, quick doc… Isolée de la lane
     * complétion. Les tâches planifiées par EditorView (highlights, inlays,
     * code actions) y sont aussi soumises.
     */
    static final java.util.concurrent.ExecutorService FEATURE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "code-editor-features");
                t.setDaemon(true);
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
        // v0.1.0.50: annule TOUTE livraison en vol / débounced — sans ça une
        // réponse périmée pouvait rouvrir le popup juste après un Esc.
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

    // ── v0.1.0.50 : état du pipeline async complétion ────────────
    /** Compteur de génération — bump à chaque nouvelle frappe/dismiss :
     * toute réponse dont la génération ≠ courante est jetée. */
    private final java.util.concurrent.atomic.AtomicInteger completionGeneration =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Débounce avant requête moteur.
     *
     * <p>★ v2.58 — abaissé de 120 ms à 80 ms pour réduire le retard
     * perçu après le fix v2.57 ({@code flushPendingChange()} SYNCHRONE
     * avant chaque requête LSP). Le debounce reste nécessaire pour
     * éviter de spammer le serveur pendant la frappe rapide (10+
     * touches/sec) ; les caractères déclencheurs (ex : {@code .})
     * court-circuitent entièrement ce debounce via le paramètre
     * {@code immediate=true} de {@link #scheduleAsyncFetch}.</p>
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
        // v2.38 — parité Sora : la complétion qui s'ouvre referme le hover
        // (exclusion mutuelle — Sora dismiss le tooltip quand la complétion
        // s'ouvre, et refuse le hover tant qu'elle est ouverte).
        if (view.quickDocVisible) dismissQuickDoc();
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
        // v0.1.0.50 : action post-insertion transportée par le backend —
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
     * v0.1.0.50 : rafraîchit le popup de complétion — version ASYNCHRONE.
     *
     * <p>Modèle CodeAssist appliqué :</p>
     * <ol>
     *   <li><b>Chemin rapide idem-token</b> (Étendre) : filtre local du cache
     *       base, zéro aller-retour, inchangé.</li>
     *   <li><b>Nouveau token / trigger</b> (Réouvrir) : classification O(1)
     *   sur le thread UI puis requête moteur ANNULABLE (génération +
     *   debounce 120 ms) exécutée HORS thread UI ; livraison latest-wins
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
        // ★ v2.58 — après un char déclencheur (ex : "."), on lance la
        // requête moteur IMMÉDIATEMENT (immediate=true) au lieu d'attendre
        // COMPLETION_DEBOUNCE_MS. Le contexte change radicalement après un
        // "." (membres de l'instance / sous-paquets d'un import) : le
        // debounce n'apporte rien car l'utilisateur marque une pause
        // explicite en tapant le déclencheur, et le fix v2.57 garantit que
        // le serveur voit déjà le texte à jour (flushPendingChange SYNCHRONE).
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
     * v2.38 — La bande verticale écran de la ligne du TOKEN de complétion
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
     * v2.38 — Nombre de rangées réellement affichables : min(cap, items)
     * RÉDUIT à ce qui tient au-dessus OU en dessous de la ligne du caret
     * (sans la recouvrir), minimum 1. Avant, 8 rangées (224dp) étaient
     * dessinées quoi qu'il arrive : avec le clavier logiciel ouvert
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

    /** v2.38 — écart visuel (px) entre le popup et la ligne du caret. */
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
        // v2.31: inlay-aware anchor — the popup follows the woven caret X.
        float anchorX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
            + view.visualColFor(line, col) * view.metrics.getCharWidth() - view.hOffset;
        // v2.38: bas de la ligne fold+wrap-aware (l'ancienne formule brute
        // padTop + (line+1)*lineHeight plaçait l'ancre au-dessus de la
        // rangée visuelle réelle dès qu'une ligne précédente était wrappée
        // ou pliée → le popup recouvrait la ligne de frappe).
        float[] band = completionCaretBand();
        float caretBottomY = band[1];
        float caretTopY = band[0];
        float anchorY = caretBottomY + COMPLETION_ANCHOR_GAP_PX;
        float viewW = view.getWidth();
        if (anchorX + width > viewW) anchorX = Math.max(view.metrics.getGutterWidth(), viewW - width - 4);
        // v2.38: le popup ne recouvre JAMAIS la ligne du caret — s'il ne
        // tient pas en dessous il passe AU-DESSUS (bas du popup = haut de
        // la ligne − gap). L'ancien clamp Math.max(0, …) laissait le popup
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
                    // ★ v2.30 : kind corrigé 12 (Value !) → 14 (Keyword,
                    // numérotation LSP moderne) — le badge du popup en
                    // dépendait. isKeyword=true reste inchangé.
                    kw, "keyword", kw, "k", 14, 0, true, false));
            }
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    // Signature help popup
    // ════════════════════════════════════════════════════════════════

    void refreshSignatureHelpFromHost() {
        triggerSignatureHelp();
    }

    void dismissSignatureHelp() {
        sigHelpGeneration++;
        view.signatureHelpController.dismiss();
        view.signatureHelpVisible = false;
        view.signatureHelpData = null;
        view.invalidate();
    }

    /** v0.1.0.50 : génération de la résolution signature help (annulation). */
    private volatile int sigHelpGeneration = 0;

    /**
     * v0.1.0.50 : résolution DÉPORTÉE hors du thread UI. Avant cette
     * correction, l'appel LSP (timeout 10 s !) partait à CHAQUE édition dès
     * que le curseur était entre parenthèses — gel du clavier garanti sur
     * un serveur lent. La machine à états du contrôleur (gate findCallOpen,
     * dismissed, reset au changement d'appel) s'exécute désormais sur la
     * lane « features » ; seule l'application visuelle revient sur l'UI,
     * garde de génération en main.
     */
    void refreshSignatureHelp() {
        if (view.session == null) return;
        int caret = view.session.getSelection().start;
        String text = view.session.getText();
        if (view.signatureHelpResolver != null) {
            // Gate locale O(1) : hors appel ou dismissed → rien à demander.
            int callOpen = jo.codeeditor.completion.SignatureHelpController.findCallOpen(text, caret);
            if (callOpen < 0 || view.signatureHelpController.isDismissed()) {
                if (view.signatureHelpVisible || view.signatureHelpData != null) {
                    view.signatureHelpVisible = false;
                    view.signatureHelpData = null;
                    view.invalidate();
                }
                return;
            }
            final int gen = ++sigHelpGeneration;
            final String fText = text;
            final int fCaret = caret;
            FEATURE_EXECUTOR.execute(() -> {
                try {
                    // Le contrôleur invoque lui-même le resolver LSP (via
                    // son listener) — c'est CET appel bloquant qui vit
                    // maintenant en dehors du thread UI.
                    view.signatureHelpController.resolve(fText, fCaret);
                } catch (Throwable ignored) {
                }
                final jo.codeeditor.completion.SignatureHelpController.SignatureHelp help =
                        view.signatureHelpController.getHelp();
                final boolean hasSig = help != null && help.signatures != null
                        && !help.signatures.isEmpty();
                android.os.Handler h = view.getHandler();
                Runnable apply = () -> {
                    if (gen != sigHelpGeneration || view.session == null) return;
                    view.signatureHelpData = help;
                    view.signatureHelpVisible = hasSig;
                    view.invalidate();
                };
                if (h != null) h.post(apply); else apply.run();
            });
        } else {
            int callOpen = jo.codeeditor.completion.SignatureHelpController.findCallOpen(text, caret);
            if (callOpen < 0 || view.signatureHelpController.isDismissed()) {
                view.signatureHelpVisible = false;
                view.signatureHelpData = null;
            } else {
                int paramIdx = jo.codeeditor.completion.SignatureHelpController
                    .activeParameterIndex(text, callOpen, caret);
                String name = extractFunctionName(text, callOpen);
                String label = name + "(…)";
                java.util.List<jo.codeeditor.completion.SignatureHelpController.Parameter> params =
                    java.util.Collections.singletonList(
                        new jo.codeeditor.completion.SignatureHelpController.Parameter(
                            "param " + paramIdx, ""));
                java.util.List<jo.codeeditor.completion.SignatureHelpController.Signature> sigs =
                    java.util.Collections.singletonList(
                        new jo.codeeditor.completion.SignatureHelpController.Signature(
                            label, "Active parameter: " + paramIdx, params, paramIdx));
                view.signatureHelpData = new jo.codeeditor.completion.SignatureHelpController.SignatureHelp(
                    sigs, 0, paramIdx);
                view.signatureHelpVisible = true;
            }
        }
        view.invalidate();
    }

    void triggerSignatureHelp() {
        if (view.session == null) return;
        view.signatureHelpController.triggerExplicit(view.session.getText(), view.session.getSelection().start);
        refreshSignatureHelp();
    }

    private static String extractFunctionName(String text, int callOpen) {
        if (callOpen <= 0) return "function";
        int end = callOpen;
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                start--;
            } else {
                break;
            }
        }
        if (start >= end) return "function";
        return text.substring(start, end);
    }

    // ════════════════════════════════════════════════════════════════
    // Quick doc popup
    // ════════════════════════════════════════════════════════════════

    /** v0.1.0.50 : génération du quick-doc (annulation des résolutions). */
    private volatile int quickDocGeneration = 0;

    /**
     * v0.1.0.50 : la résolution hover LSP (jusqu'à 10 s) quitte le thread
     * UI — déclenchée par appui long / survol, elle gèle auparavant toute
     * saisie tant que le serveur ne répond pas. La popup n'est montrée que
     * lorsque le contenu est prêt ; une frappe/édition entre-temps invalide
     * la livraison (génération).
     */
    void showQuickDoc(int offset) {
        if (view.session == null || view.quickDocResolver == null) return;
        // v2.38 — parité Sora : pas de hover quand la complétion est
        // ouverte (le tooltip ne vole pas le focus à la popup).
        if (view.completionVisible) return;
        final String text = view.session.getText();
        final int gen = ++quickDocGeneration;
        final int fOffset = offset;
        FEATURE_EXECUTOR.execute(() -> {
            String rawDoc = null;
            try {
                rawDoc = view.quickDocResolver.resolve(text, fOffset);
            } catch (Throwable ignored) {
            }
            final String raw = rawDoc;
            final jo.codeeditor.doc.QuickDoc.QuickDocContent content =
                    (raw == null || raw.isEmpty())
                            ? null
                            : jo.codeeditor.doc.QuickDoc.parseQuickDoc(raw, view.session.getLanguage());
            android.os.Handler h = view.getHandler();
            Runnable apply = () -> {
                if (gen != quickDocGeneration || view.session == null) return;
                if (content == null || content.isEmpty()) {
                    dismissQuickDoc();
                    return;
                }
                view.quickDocContent = content;
                // v2.38 — le popup SUIT le texte : on ne stocke plus des
                // coordonnées écran figées mais l'OFFSET d'ancrage — le
                // renderer recalcule X/Y à chaque frame (wrap/fold-aware)
                // et referme le popup quand la ligne sort du viewport
                // (parité Sora HoverWindow : suit le contenu, dismiss quand
                // l'ancre sort de la vue).
                view.quickDocAnchorOffset = fOffset;
                view.quickDocScrollY = 0f;
                view.quickDocVisible = true;
                view.invalidate();
            };
            if (h != null) h.post(apply); else apply.run();
        });
    }

    void dismissQuickDoc() {
        quickDocGeneration++;
        view.quickDocVisible = false;
        view.quickDocContent = null;
        view.invalidate();
    }

    // ════════════════════════════════════════════════════════════════
    // Code actions popup
    // ════════════════════════════════════════════════════════════════

    /** v0.1.0.50 : génération du scan d'actions de code (annulation). */
    private volatile int codeActionsGeneration = 0;

    /**
     * v0.1.0.50 : le scan demandait UNE requête LSP PAR ligne visible
     * (3 s de timeout chacune) SUR LE THREAD UI, toutes les 800 ms. Le
     * travail quitte désormais l'UI ; seule la dernière demande (génération
     * courante) publie son résultat.
     */
    void refreshCodeActions(int firstVisible, int lastVisible) {
        if (view.codeActionsResolver == null || view.session == null) {
            view.codeActionsByLine.clear();
            return;
        }
        final int gen = ++codeActionsGeneration;
        final String text = view.session.getText();
        final int first = firstVisible;
        final int last = lastVisible;
        FEATURE_EXECUTOR.execute(() -> {
            java.util.Map<Integer, List<EditorView.CodeAction>> found =
                    new java.util.HashMap<>();
            for (int line = first; line <= last; line++) {
                List<EditorView.CodeAction> actions;
                try {
                    actions = view.codeActionsResolver.resolve(text, line);
                } catch (Throwable t) {
                    actions = null;
                }
                if (actions != null && !actions.isEmpty()) {
                    found.put(line, actions);
                }
            }
            android.os.Handler h = view.getHandler();
            Runnable apply = () -> {
                if (gen != codeActionsGeneration || view.session == null) return;
                view.codeActionsByLine.clear();
                view.codeActionsByLine.putAll(found);
            };
            if (h != null) h.post(apply); else apply.run();
        });
    }

    void showCodeActions(int line) {
        if (!view.codeActionsByLine.containsKey(line)) return;
        view.codeActionsPopupLine = line;
        view.codeActionsSelected = 0;
        view.codeActionsPopupVisible = true;
        view.invalidate();
    }

    void dismissCodeActions() {
        view.codeActionsPopupVisible = false;
        view.codeActionsPopupLine = -1;
        view.invalidate();
    }

    boolean applySelectedCodeAction() {
        if (!view.codeActionsPopupVisible || view.codeActionsPopupLine < 0) return false;
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(view.codeActionsPopupLine);
        if (actions == null || view.codeActionsSelected < 0 || view.codeActionsSelected >= actions.size()) {
            return false;
        }
        EditorView.CodeAction a = actions.get(view.codeActionsSelected);
        dismissCodeActions();
        if (a.apply != null) a.apply.run();
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    // v2.36 : menu contextuel unifié (portage NavMenu de CodeAssist)
    // ════════════════════════════════════════════════════════════════

    /** v2.36 : génération du menu contextuel unifié (annulation async). */
    private volatile int navMenuGeneration = 0;

    /**
     * v2.36 → v2.37 — ouvre le menu contextuel unifié (toolbar de sélection
     * → Actions ⋯). Portage du {@code openNavMenu} de CodeAssist : les
     * options GO TO applicables au caret sont résolues HORS thread UI dans
     * l'ordre des {@code NavKind} de CodeAssist — Declaration
     * (definitionResolver), Implementations (implementationsResolver,
     * v2.37), Type declaration (typeDefinitionResolver), Super
     * (superResolver, v2.37) — puis les quick-fixes/intentions viennent du
     * cache {@code codeActionsByLine} de la ligne du caret (snapshot AVANT
     * dispatch — la map vit sur le thread UI). Le menu apparaît quand tout
     * est prêt ; une nouvelle demande invalide la livraison (génération).
     */
    void showNavMenu(int line, int offset) {
        if (view.session == null) return;
        final String text = view.session.getText();
        final int gen = ++navMenuGeneration;
        final int fLine = line;
        final int fOffset = offset;
        // Snapshot UI-thread du cache d'actions de la ligne.
        final List<EditorView.CodeAction> lineActions =
                new ArrayList<>(view.codeActionsByLine.getOrDefault(fLine,
                        java.util.Collections.emptyList()));
        final EditorView.DefinitionResolver defResolver = view.definitionResolver;
        final EditorView.ImplementationsResolver implResolver =
                view.implementationsResolver;
        final EditorView.TypeDefinitionResolver typeDefResolver = view.typeDefinitionResolver;
        final EditorView.SuperResolver superResolver = view.superResolver;
        FEATURE_EXECUTOR.execute(() -> {
            List<NavigationMenu.NavOption> options = new ArrayList<>();
            // GO TO — Declaration (textDocument/definition) — NavKind #1.
            if (defResolver != null) {
                List<jo.codeeditor.lang.DefinitionLocation> targets = null;
                try {
                    targets = defResolver.resolve(text, fOffset);
                } catch (Throwable ignored) {
                }
                List<NavigationMenu.NavTarget> navTargets = toNavTargets(targets);
                if (!navTargets.isEmpty()) {
                    options.add(new NavigationMenu.NavOption("Declaration", navTargets));
                }
            }
            // GO TO — Implementations (textDocument/implementation, v2.37)
            // — NavKind #2 : les héritiers DIRECTS du type en contexte.
            if (implResolver != null) {
                List<jo.codeeditor.lang.DefinitionLocation> targets = null;
                try {
                    targets = implResolver.resolve(text, fOffset);
                } catch (Throwable ignored) {
                }
                List<NavigationMenu.NavTarget> navTargets = toNavTargets(targets);
                if (!navTargets.isEmpty()) {
                    options.add(new NavigationMenu.NavOption("Implementations", navTargets));
                }
            }
            // GO TO — Type declaration (textDocument/typeDefinition, v2.36)
            // — NavKind #3.
            if (typeDefResolver != null) {
                List<jo.codeeditor.lang.DefinitionLocation> targets = null;
                try {
                    targets = typeDefResolver.resolve(text, fOffset);
                } catch (Throwable ignored) {
                }
                List<NavigationMenu.NavTarget> navTargets = toNavTargets(targets);
                if (!navTargets.isEmpty()) {
                    options.add(new NavigationMenu.NavOption("Type declaration", navTargets));
                }
            }
            // GO TO — Super (textDocument/superDefinition, v2.37) —
            // NavKind #4 : le membre outrepassé / les supertypes DIRECTS.
            if (superResolver != null) {
                List<jo.codeeditor.lang.DefinitionLocation> targets = null;
                try {
                    targets = superResolver.resolve(text, fOffset);
                } catch (Throwable ignored) {
                }
                List<NavigationMenu.NavTarget> navTargets = toNavTargets(targets);
                if (!navTargets.isEmpty()) {
                    options.add(new NavigationMenu.NavOption("Super", navTargets));
                }
            }
            // QUICK FIXES vs INTENTIONS — kind « quickfix » vs le reste
            // (parité NavigationMenu.kt : UiActionKind.QUICK_FIX / autres).
            final List<EditorView.CodeAction> quickFixes = new ArrayList<>();
            final List<EditorView.CodeAction> intentions = new ArrayList<>();
            for (EditorView.CodeAction a : lineActions) {
                if (a == null) continue;
                if (a.kind != null && a.kind.startsWith("quickfix")) {
                    quickFixes.add(a);
                } else {
                    intentions.add(a);
                }
            }
            android.os.Handler h = view.getHandler();
            Runnable apply = () -> {
                if (gen != navMenuGeneration || view.session == null) return;
                view.navMenuLine = fLine;
                view.navMenuCaretOffset = fOffset;
                view.navMenuOptions = options;
                view.navMenuQuickFixes = quickFixes;
                view.navMenuIntentions = intentions;
                view.navMenuResultsMode = false;
                view.navMenuTargets = new ArrayList<>();
                view.navMenuScrollY = 0;
                view.navMenuPressedIdx = -1;
                view.navMenuVisible = true;
                view.invalidate();
            };
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /**
     * v2.36 → v2.37 — DefinitionLocation → NavTarget. Le libellé picker :
     * le {@code displayName} s'il est SIGNIFICATIF (les providers
     * Implementations/Super transportent « Simple  ·  pkg » /
     * « name  ·  Super »), sinon le nom COURT du fichier — v2.37 : un
     * displayName qui EST le chemin/URI (le provider definition l'envoie
     * brut) tombe désormais sur le nom court, plus lisible que
     * « file:///storage/emulated/0/… ».
     */
    private static List<NavigationMenu.NavTarget> toNavTargets(
            List<jo.codeeditor.lang.DefinitionLocation> targets) {
        List<NavigationMenu.NavTarget> out = new ArrayList<>();
        if (targets == null) return out;
        for (jo.codeeditor.lang.DefinitionLocation loc : targets) {
            if (loc == null) continue;
            String label = loc.displayName != null && !loc.displayName.isEmpty()
                    ? loc.displayName : shortFileName(loc.path);
            if (label.startsWith("file://") || label.startsWith("/")
                    || label.equals(loc.path)) {
                label = shortFileName(loc.path);
            }
            out.add(new NavigationMenu.NavTarget(loc.path, loc.offset, label));
        }
        return out;
    }

    /** v2.36 — le dernier segment d'un chemin/URI (« MainActivity.java »). */
    static String shortFileName(String path) {
        if (path == null || path.isEmpty()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Referme le menu contextuel unifié. */
    void dismissNavMenu() {
        navMenuGeneration++;
        view.navMenuVisible = false;
        view.navMenuPressedIdx = -1;
        view.invalidate();
    }

    /**
     * v2.36 — pick d'une option GO TO : cible unique → navigation directe ;
     * plusieurs cibles → bascule en mode RESULTS (picker, parité
     * {@code NavMenuState.Results}).
     */
    void navMenuPickOption(NavigationMenu.NavOption option) {
        if (option == null) return;
        if (option.targets.size() == 1) {
            navMenuNavigate(option.targets.get(0));
            return;
        }
        view.navMenuTargets = new ArrayList<>(option.targets);
        view.navMenuResultsMode = true;
        view.navMenuScrollY = 0;
        view.navMenuPressedIdx = -1;
        view.invalidate();
    }

    /** v2.36 — pick d'une action (quick fix ou intention) : applique + ferme. */
    void navMenuPickAction(EditorView.CodeAction action) {
        if (action == null) return;
        dismissNavMenu();
        if (action.apply != null) action.apply.run();
    }

    /**
     * v2.36 — pick d'une cible (mode RESULTS) : navigation directe (parité
     * {@code onPick}).
     */
    void navMenuPickTarget(NavigationMenu.NavTarget target) {
        if (target == null) return;
        navMenuNavigate(target);
    }

    /**
     * v2.36 — navigue vers une cible : même fichier → saut du caret +
     * scroll ; autre fichier → {@code definitionListener} (l'hôte ouvre le
     * fichier — parité jumpToDefinition / openNavMenu de CodeAssist).
     */
    private void navMenuNavigate(NavigationMenu.NavTarget target) {
        dismissNavMenu();
        if (view.session == null || target == null) return;
        String path = target.path != null ? target.path : "";
        boolean sameFile = path.isEmpty()
                || path.equals(view.currentFilePath)
                || path.equals("file:///" + view.currentFilePath);
        if (sameFile) {
            view.session.setSelection(Math.max(0, Math.min(target.offset,
                    view.session.getDocument().length())));
            view.scrollManager.scrollCaretIntoView();
            view.invalidate();
        } else if (view.definitionListener != null) {
            List<jo.codeeditor.lang.DefinitionLocation> locations = new ArrayList<>();
            locations.add(new jo.codeeditor.lang.DefinitionLocation(
                    target.path, target.offset, target.displayName));
            view.definitionListener.onDefinitionRequested(locations);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Go-to-symbol popup
    // ════════════════════════════════════════════════════════════════

    /** v0.1.0.50 : génération du documentSymbol (annulation). */
    private volatile int symbolGeneration = 0;

    /**
     * v0.1.0.50 : la résolution documentSymbol (3 s LSP) quitte le thread
     * UI — la popup s'ouvre sur le résultat quand il arrive.
     */
    void showGoToSymbol() {
        if (view.session == null || view.symbolResolver == null) return;
        final int gen = ++symbolGeneration;
        final String text = view.session.getText();
        FEATURE_EXECUTOR.execute(() -> {
            java.util.List<NavigationMenu.Symbol> symbols = null;
            try {
                symbols = view.symbolResolver.resolve(text);
            } catch (Throwable ignored) {
            }
            final java.util.List<NavigationMenu.Symbol> all =
                    symbols != null ? symbols : new ArrayList<>();
            android.os.Handler h = view.getHandler();
            Runnable apply = () -> {
                if (gen != symbolGeneration || view.session == null) return;
                view.goToSymbolAll = all;
                view.goToSymbolFilter = "";
                view.goToSymbolFiltered = new ArrayList<>(view.goToSymbolAll);
                view.goToSymbolSelected = 0;
                view.goToSymbolScrollOffset = 0;
                view.goToSymbolVisible = !view.goToSymbolFiltered.isEmpty();
                view.invalidate();
            };
            if (h != null) h.post(apply); else apply.run();
        });
    }

    void dismissGoToSymbol() {
        view.goToSymbolVisible = false;
        view.invalidate();
    }

    void setGoToSymbolFilter(String filter) {
        view.goToSymbolFilter = filter == null ? "" : filter;
        view.goToSymbolFiltered = NavigationMenu.filter(view.goToSymbolAll, view.goToSymbolFilter);
        view.goToSymbolSelected = 0;
        view.goToSymbolScrollOffset = 0;
        view.invalidate();
    }

    boolean goToSymbolSelect(int delta) {
        if (!view.goToSymbolVisible || view.goToSymbolFiltered.isEmpty()) return false;
        view.goToSymbolSelected = Math.max(0, Math.min(view.goToSymbolFiltered.size() - 1,
            view.goToSymbolSelected + delta));
        if (view.goToSymbolSelected < view.goToSymbolScrollOffset) {
            view.goToSymbolScrollOffset = view.goToSymbolSelected;
        } else if (view.goToSymbolSelected >= view.goToSymbolScrollOffset + view.GO_TO_SYMBOL_MAX_ROWS) {
            view.goToSymbolScrollOffset = view.goToSymbolSelected - view.GO_TO_SYMBOL_MAX_ROWS + 1;
        }
        view.invalidate();
        return true;
    }

    boolean goToSymbolAccept() {
        if (!view.goToSymbolVisible || view.goToSymbolSelected < 0
            || view.goToSymbolSelected >= view.goToSymbolFiltered.size()) {
            return false;
        }
        NavigationMenu.Symbol s = view.goToSymbolFiltered.get(view.goToSymbolSelected);
        int offset = EditorView.clamp(s.offset, 0, view.session.getDocument().length());
        view.session.expandFoldAt(offset);
        view.session.setSelection(offset);
        view.scrollToOffset(offset);
        dismissGoToSymbol();
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    // Go-to-line popup (uses Android PopupWindow + EditText)
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.38 — Carte flottante « liquid glass » (portage RenamePopup /
     * GoToLinePopup de CodeAssist) : 320dp de large, fond glassThick
     * (theme.glassBg) coins 18dp, bordure 1dp glassEdge, padding 16dp,
     * titre bodySmall semibold, champ fond surfaceContainerHigh coins 12dp,
     * hint labelSmall sous le champ. Construite en code (GradientDrawable)
     * aux couleurs du thème de l'éditeur (cohérence avec les popups Canvas).
     */
    static final class GlassCard {
        final LinearLayout container;
        final EditText field;
        GlassCard(LinearLayout container, EditText field) {
            this.container = container;
            this.field = field;
        }
    }

    /**
     * v2.38 — Construit la carte glass commune (rename / go-to-line).
     * Le caller branche ses propres setOnEditorActionListener/
     * setOnKeyListener + crée le PopupWindow + showAtLocation.
     */
    private GlassCard buildGlassCard(Context ctx, String title,
                                     String prefill, String hint, int inputType) {
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = view.dp(16);
        container.setPadding(pad, pad, pad, pad);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                view.dp(320), LinearLayout.LayoutParams.WRAP_CONTENT));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(view.dp(18));
        bg.setColor(view.theme.glassBg);
        bg.setStroke(view.dp(1), view.theme.glassBorder);
        container.setBackground(bg);

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextColor(view.theme.gutterText);
        titleView.setTextSize(12);
        titleView.setTypeface(titleView.getTypeface(),
                android.graphics.Typeface.BOLD);
        container.addView(titleView);
        ((LinearLayout.LayoutParams) titleView.getLayoutParams()).bottomMargin =
                view.dp(8);

        EditText field = new EditText(ctx);
        if (prefill != null) field.setText(prefill);
        if (hint != null) field.setHint(hint);
        field.setTextColor(view.theme.textColor);
        field.setHintTextColor(view.theme.gutterText);
        android.graphics.drawable.GradientDrawable fieldBg =
                new android.graphics.drawable.GradientDrawable();
        fieldBg.setCornerRadius(view.dp(12));
        fieldBg.setColor(view.theme.selection);
        fieldBg.setStroke(view.dp(1), view.theme.glassBorder);
        field.setBackground(fieldBg);
        int fpad = view.dp(12), fpadv = view.dp(10);
        field.setPadding(fpad, fpadv, fpad, fpadv);
        field.setTextSize(16);
        field.setInputType(inputType);
        field.setTypeface(view.metrics.getTypeface());
        container.addView(field);
        ((LinearLayout.LayoutParams) field.getLayoutParams()).bottomMargin =
                view.dp(6);

        if (hint != null) {
            TextView hintView = new TextView(ctx);
            hintView.setText(hint);
            hintView.setTextColor(view.theme.gutterText);
            hintView.setTextSize(11);
            container.addView(hintView);
        }
        return new GlassCard(container, field);
    }

    void showGoToLine() {
        if (view.session == null) return;
        dismissGoToLine();
        view.goToLineVisible = true;
        int lineCount = view.session.getDocument().lineCount();
        Context ctx = view.getContext();
        // v2.38 — carte glass CodeAssist (avant : LinearLayout brut theming
        // gutterBg, pas d'arrondi, pas de séparation titre/champ/hint).
        GlassCard card = buildGlassCard(ctx,
                "Go to line",
                null,
                "Line 1–" + lineCount + "  (line or line:column)",
                InputType.TYPE_CLASS_NUMBER);
        final EditText field = card.field;
        field.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                acceptGoToLineInput(((EditText) v).getText().toString());
                return true;
            }
            return false;
        });
        field.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                acceptGoToLineInput(((EditText) v).getText().toString());
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dismissGoToLine();
                return true;
            }
            return false;
        });
        view.goToLinePopup = new PopupWindow(card.container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true);
        view.goToLinePopup.setFocusable(true);
        view.goToLinePopup.setOnDismissListener(() -> {
            view.goToLineVisible = false;
            view.goToLinePopup = null;
        });
        view.goToLinePopup.showAtLocation(view, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, view.dp(48));
        field.requestFocus();
    }

    void acceptGoToLineInput(String input) {
        if (view.session == null || input == null) {
            dismissGoToLine();
            return;
        }
        input = input.trim();
        int line, col = 1;
        int colonIdx = input.indexOf(':');
        try {
            if (colonIdx >= 0) {
                line = Integer.parseInt(input.substring(0, colonIdx).trim());
                col = Integer.parseInt(input.substring(colonIdx + 1).trim());
            } else {
                line = Integer.parseInt(input);
            }
        } catch (NumberFormatException e) {
            dismissGoToLine();
            return;
        }
        line = EditorView.clamp(line - 1, 0, view.session.getDocument().lineCount() - 1);
        col = Math.max(1, col);
        int lineStart = view.session.getDocument().lineStart(line);
        int lineEnd = view.session.getDocument().lineEnd(line);
        int targetOffset = Math.min(lineStart + col - 1, lineEnd);
        view.session.expandFoldAt(targetOffset);
        view.session.setSelection(targetOffset);
        view.scrollToLine(line);
        dismissGoToLine();
    }

    void dismissGoToLine() {
        view.goToLineVisible = false;
        if (view.goToLinePopup != null) {
            view.goToLinePopup.dismiss();
            view.goToLinePopup = null;
        }
        view.invalidate();
    }

    boolean isGoToLineVisible() { return view.goToLineVisible; }

    @Deprecated
    void setGoToLineText(String text) {
        view.goToLineText = text == null ? "" : text;
        view.invalidate();
    }

    @Deprecated
    boolean acceptGoToLine() {
        acceptGoToLineInput(view.goToLineText);
        return view.goToLineVisible;
    }

    // ════════════════════════════════════════════════════════════════
    // Rename popup (uses Android PopupWindow + EditText)
    // ════════════════════════════════════════════════════════════════

    void showRename() {
        if (view.session == null) return;
        dismissRename();
        EditorDocument doc = view.session.getDocument();
        int caret = view.session.getSelection().start;
        int line = doc.lineForOffset(caret);
        int lineStart = doc.lineStart(line);
        String lineText = doc.lineText(line);
        int col = caret - lineStart;
        int startCol = col;
        while (startCol > 0) {
            char c = lineText.charAt(startCol - 1);
            if (Character.isLetterOrDigit(c) || c == '_') startCol--;
            else break;
        }
        int endCol = col;
        while (endCol < lineText.length()) {
            char c = lineText.charAt(endCol);
            if (Character.isLetterOrDigit(c) || c == '_') endCol++;
            else break;
        }
        view.renameStartOffset = lineStart + startCol;
        view.renameEndOffset = lineStart + endCol;
        view.renameText = lineText.substring(startCol, endCol);
        if (view.renameText.isEmpty()) return;
        view.renameVisible = true;
        Context ctx = view.getContext();
        // v2.38 — carte glass CodeAssist (avant : LinearLayout brut theming
        // gutterBg). Titre « Rename \"x\" » ; hint « Enter to rename 'x',
        // Esc to cancel » (parité EditorOverlays.kt).
        GlassCard card = buildGlassCard(ctx,
                "Rename \"" + view.renameText + "\"",
                view.renameText,
                "Enter to rename '" + view.renameText + "', Esc to cancel",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText field = card.field;
        field.selectAll();
        field.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                acceptRenameInput(((EditText) v).getText().toString());
                return true;
            }
            return false;
        });
        field.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                acceptRenameInput(((EditText) v).getText().toString());
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dismissRename();
                return true;
            }
            return false;
        });
        view.renamePopup = new PopupWindow(card.container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true);
        view.renamePopup.setFocusable(true);
        view.renamePopup.setOnDismissListener(() -> {
            view.renameVisible = false;
            view.renamePopup = null;
        });
        view.renamePopup.showAtLocation(view, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, view.dp(48));
        field.requestFocus();
    }

    boolean acceptRenameInput(String newName) {
        if (view.session == null || view.renameStartOffset < 0 || view.renameEndOffset < 0
            || newName == null || newName.isEmpty()) {
            dismissRename();
            return false;
        }
        EditorDocument doc = view.session.getDocument();
        if (view.renameEndOffset > doc.length()) {
            dismissRename();
            return false;
        }
        String oldName = doc.getText().substring(view.renameStartOffset, view.renameEndOffset);
        if (oldName.isEmpty() || oldName.equals(newName)) {
            dismissRename();
            return false;
        }
        // v3.33.11: Prefer the LSP rename resolver when plugged in — it
        // returns the new full text after applying WorkspaceEdit (which
        // respects scope and type-aware renaming). Falls back to the
        // substring-matching heuristic when no resolver is set (e.g. for
        // EmptyLanguage or a server that doesn't support rename).
        //
        // v0.1.0.50 : la requête LSP rename (timeout 10 s) quitte le thread
        // UI — le dialogue se ferme immédiatement, l'application des edits
        // arrive sur l'UI quand le serveur répond ; en cas d'échec on retombe
        // sur l'heuristique locale.
        final String text = doc.getText();
        final jo.codeeditor.view.EditorView.RenameResolver resolver = view.renameResolver;
        final int caretOffset = view.renameStartOffset;
        final String fNewName = newName;
        final String fOldName = oldName;
        if (resolver != null) {
            dismissRename();
            FEATURE_EXECUTOR.execute(() -> {
                String tmp = null;
                try {
                    tmp = resolver.rename(text, caretOffset, fNewName);
                } catch (Throwable ignored) {
                }
                final String newText = tmp;
                android.os.Handler h = view.getHandler();
                Runnable apply = () -> {
                    boolean replaced = false;
                    if (newText != null && !newText.equals(text)) {
                        try {
                            view.session.replaceRange(0,
                                view.session.getDocument().length(), newText);
                            view.notifyTextChanged();
                            replaced = true;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!replaced) {
                        // Fallback : heuristique locale par identifiant.
                        String fallback = substringRename(text, fOldName, fNewName);
                        if (fallback != null) {
                            view.session.replaceRange(0,
                                view.session.getDocument().length(), fallback);
                            view.notifyTextChanged();
                        }
                    }
                };
                if (h != null) h.post(apply); else apply.run();
            });
            return true;
        }
        String fallback = substringRename(text, fOldName, fNewName);
        if (fallback != null) {
            view.session.replaceRange(0, doc.length(), fallback);
            view.notifyTextChanged();
        }
        dismissRename();
        return fallback != null;
    }

    /**
     * Fallback historique : remplacement par identifiant exact (bords de
     * mot respectés), sans conscience de portée. Retourne null si aucun
     * remplacement effectué.
     */
    private static String substringRename(String text, String oldName, String newName) {
        StringBuilder sb = new StringBuilder(text.length() + newName.length());
        int i = 0;
        int replaced = 0;
        while (i < text.length()) {
            if (i + oldName.length() <= text.length()
                && text.substring(i, i + oldName.length()).equals(oldName)
                && (i == 0 || (!Character.isLetterOrDigit(text.charAt(i - 1)) && text.charAt(i - 1) != '_'))
                && (i + oldName.length() == text.length()
                    || (!Character.isLetterOrDigit(text.charAt(i + oldName.length()))
                        && text.charAt(i + oldName.length()) != '_'))) {
                sb.append(newName);
                i += oldName.length();
                replaced++;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return replaced > 0 ? sb.toString() : null;
    }

    void dismissRename() {
        view.renameVisible = false;
        view.renameStartOffset = -1;
        view.renameEndOffset = -1;
        if (view.renamePopup != null) {
            view.renamePopup.dismiss();
            view.renamePopup = null;
        }
        view.invalidate();
    }

    boolean isRenameVisible() { return view.renameVisible; }

    @Deprecated
    void setRenameText(String text) {
        view.renameText = text == null ? "" : text;
        view.invalidate();
    }

    @Deprecated
    boolean acceptRename() {
        return acceptRenameInput(view.renameText);
    }

    // ════════════════════════════════════════════════════════════════
    // Diagnostic popup + sheet
    // ════════════════════════════════════════════════════════════════

    void showDiagnosticSheet() {
        view.diagnosticSheetVisible = true;
        view.diagnosticSheetScroll = 0;
        view.invalidate();
    }

    void dismissDiagnosticSheet() {
        view.diagnosticSheetVisible = false;
        view.invalidate();
    }

    boolean isDiagnosticSheetVisible() { return view.diagnosticSheetVisible; }

    void showDiagnosticPopup(DiagnosticShift.Diagnostic diag, int offset) {
        view.diagnosticPopupItem = diag;
        view.diagnosticPopupOffset = offset;
        view.diagnosticPopupVisible = true;
        // v3.36.0 (roadmap item 5): opening the detail popup from the
        // grouped list sheet closes that sheet (chip → list → detail chain).
        view.diagnosticListSheetLine = -1;
        if (view.completionVisible) dismissCompletion();
        if (view.quickDocVisible) dismissQuickDoc();
        if (view.signatureHelpVisible) dismissSignatureHelp();
        if (view.codeActionsPopupVisible) dismissCodeActions();
        // v2.36 : le menu contextuel unifié ferme aussi (popup exclusif).
        if (view.navMenuVisible) dismissNavMenu();
        view.invalidate();
    }

    void dismissDiagnosticPopup() {
        view.diagnosticPopupVisible = false;
        view.diagnosticPopupItem = null;
        view.diagnosticPopupOffset = -1;
        view.invalidate();
    }

    // ── v3.36.0: Grouped diagnostic list sheet (roadmap item 5) ────

    /**
     * Opens the grouped sheet listing every diagnostic whose start sits on
     * {@code line} (CodeAssist diagnosticsByStartLine port). A line with a
     * single diagnostic opens the detail popup directly instead.
     */
    void showDiagnosticListSheet(int line) {
        if (view.session == null) return;
        if (line < 0 || line >= view.session.getDocument().lineCount()) return;
        List<DiagnosticShift.Diagnostic> group =
                view.session.getDiagnosticsForLine(line);
        if (group.isEmpty()) return;
        if (group.size() == 1) {
            // Fast path: a single diagnostic — skip the list, open detail.
            showDiagnosticPopup(group.get(0), group.get(0).start);
            return;
        }
        view.diagnosticListSheetLine = line;
        if (view.completionVisible) dismissCompletion();
        if (view.quickDocVisible) dismissQuickDoc();
        if (view.signatureHelpVisible) dismissSignatureHelp();
        if (view.codeActionsPopupVisible) dismissCodeActions();
        if (view.navMenuVisible) dismissNavMenu();
        view.invalidate();
    }

    void dismissDiagnosticListSheet() {
        view.diagnosticListSheetLine = -1;
        view.invalidate();
    }
}
