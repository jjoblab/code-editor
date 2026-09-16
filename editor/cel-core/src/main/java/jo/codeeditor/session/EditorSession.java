package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.edit.CommentSyntax;
import jo.codeeditor.edit.EditOps;
import jo.codeeditor.edit.RangeEdit;
import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.SyntaxHighlighter;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.shift.EditSpan;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Full edit engine: owns the document, selection, undo stack, diagnostics,
 * semantic tokens, fold regions, and inlay hints.
 * <p>
 * All mutations funnel through {@link #replaceRange(int, int, String)}.
 * <p>
 * Supports: batch editing, undo/redo with coalescing, smart editing via EditOps,
 * IME bridge, bracket auto-close, indent/dedent, comment toggling, line operations.
 
 *
 * @since v1.0.0
*/
public class EditorSession {

    // ── Core state ────────────────────────────────────────────────

    private EditorDocument doc;
    private Selection selection;
    private final UndoManager undoManager;
    private final SyntaxHighlighter highlighter;
    /**
     * v2.45 — Volatile so that background restyle thread's swap
     * ({@code styledLines = newStyled}) is visible to the UI thread
     * without synchronization. The list itself is replaced (not
     * mutated) on each restyle, so a reader iterating an old
     * reference still sees a consistent snapshot.
     */
    private volatile List<StyledLine> styledLines;
    private String language = "java";
    /**
     * v3.35.0 (roadmap item 1 / bug B11) — comment syntax override for hosts
     * with languages {@link CommentSyntax#forLanguage(String)} doesn't know.
     * When null (the default), the syntax is derived from {@link #language}.
     */
    private volatile CommentSyntax commentSyntaxOverride;

    // ── Diagnostics / tokens / folds / inlays ─────────────────────
    // v3.31.1: volatile so that cross-thread reads (LSP reader thread → UI
    // thread) always see the latest field reference after a setXxx() call.
    // The lists themselves are replaced (not mutated) on each setter, so a
    // reader iterating an old reference still sees a consistent snapshot.
    private volatile List<DiagnosticShift.Diagnostic> diagnostics = new ArrayList<>();
    private volatile List<DiagnosticShift.SemanticToken> semanticTokens = new ArrayList<>();
    private volatile List<DiagnosticShift.FoldRegion> foldRegions = new ArrayList<>();
    /**
     * v3.35.0 (roadmap item 3 / hotspot P3) — monotonic revision of the fold
     * set. EVERY mutation of {@link #foldRegions} (replacement by
     * {@link #setFoldRegions}, in-place {@code set()} by
     * {@link #toggleFoldAtLine} / {@link #expandFoldAt}, re-assignment after
     * {@code DiagnosticShift.shiftFoldRegions} on edits) bumps this counter.
     * The view memoizes its fold prefix-sum index on
     * {@code (session identity, foldRev, doc identity)} — a plain list
     * reference compare does NOT work because {@link #getFoldRegions()}
     * wraps a NEW unmodifiable view on every call, and because
     * {@code toggleFoldAtLine} mutates the backing list in place.
     */
    private volatile int foldRev;
    /**
     * ★ v2.33 — les {@code collapsedByDefault} serveur (imports) ont-ils été
     * appliqués une première fois pour CE document ? (portage
     * {@code defaultFoldsApplied} de CodeAssist — cf. applyCodeFolds.)
     */
    private boolean foldDefaultsApplied = false;
    private volatile List<DiagnosticShift.InlayHint> inlayHints = new ArrayList<>();

    // ── Per-line / global revision stamps for the render cache (v1.0.7) ──
    // lineTextRevisions[i] is bumped ONLY when line i is re-tokenized
    // (in spliceStyles or restyleAll). The view's LineRenderCache uses
    // these per-line stamps so a single edit doesn't invalidate every
    // cached line — only the lines whose text actually changed.
    private int[] lineTextRevisions = new int[0];
    private int lineTextRevCounter = 1;
    // Bumped when setInlayHints / setSemanticTokens is called (the global
    // list is replaced). On a text edit, inlay/sem positions are SHIFTED
    // in-place (DiagnosticShift.shift*) — that doesn't bump these revs
    // because the cache entries shift alongside via onLinesShifted.
    private int inlayHintsRev = 0;
    private int semTokensRev = 0;
    // Listener fired after spliceStyles so the view's render cache can
    // shiftKeys its entries to match the new line layout.
    private OnLinesShiftedListener linesShiftListener;

    // ★ v0.1.0.49-v2.20 — Mode lecture-seule (consoles log embarquées).
    // Quand actif, toute mutation de texte via replaceRange*/commitText/
    // typeChar/backspace est ignorée silencieusement — la sélection,
    // le scroll et la copie restent fonctionnels. Utilisé par
    // ConsoleLogView côté app CodeIDE pour empêcher l'édition accidentelle
    // du contenu console tout en profitant du rendu/coloration de l'éditeur.
    private volatile boolean readOnly = false;

    // ── Batch editing ─────────────────────────────────────────────

    private int batchDepth = 0;
    private List<EditOp> batchEdits = new ArrayList<>();
    private int batchSelBefore = -1;

    // ── IME ───────────────────────────────────────────────────────

    private ImeListener imeListener;
    private int composingStart = -1;
    private int composingEnd = -1;

    /**
     * Listener for IME events.
     * <p>
     * Implemented by the host platform (typically the Android View's
     * InputConnection bridge) so that {@link EditorSession} can push
     * state changes back to the InputMethodManager.
     */
    public interface ImeListener {
        /** Notified after every text edit. The {@code span} is null when only the selection moved. */
        void onTextChanged(jo.codeeditor.shift.EditSpan span);

        /** Notified after every selection / composing-region change. */
        void onSelectionChanged(int selStart, int selEnd, int composingStart, int composingEnd);

        /**
         * Request the host to call {@code InputMethodManager.restartInput(view)}.
         * Used after a smart-edit diverged from what the IME delivered, so the
         * IME drops its stale composing buffer and resyncs from
         * {@link #getSelection()} / {@link #getComposingRegion()}.
         */
        void onRestartInput();

        /**
         * @return true if the IME is currently monitoring the extracted text
         *     (i.e. {@code getExtractedText} armed a monitor). When true, the
         *     session skips the disruptive {@link #onRestartInput()} on smart
         *     edits because the per-edit extracted-text push already keeps the
         *     IME exact.
         */
        boolean isSyncingExtractedText();
    }

    // ── Callbacks ─────────────────────────────────────────────────

    /**
     * v3.3.3: Changed from a single listener to a list so that the LSP
     * layer can register its didChange forwarder WITHOUT replacing the
     * demo app's updateStats listener. The previous setOnTextEditListener
     * behavior (single listener) is preserved — calling it removes all
     * existing listeners and adds the new one.
     */
    private final java.util.List<OnTextEditListener> onTextEditListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private OnSnippetEditListener onSnippetEditListener;

    public interface OnTextEditListener {
        void onTextEdit(int start, int end, String inserted);
    }

    public interface OnSnippetEditListener {
        void onSnippetEdit(int start, int end, String inserted);
    }

    /**
     * v3.3.3: Adds a text-edit listener WITHOUT removing existing ones.
     * Use this from library code (e.g. LSP) to chain listeners.
     */
    public void addOnTextEditListener(OnTextEditListener listener) {
        if (listener != null) onTextEditListeners.add(listener);
    }

    /** v3.3.3: Removes a previously-added text-edit listener. */
    public void removeOnTextEditListener(OnTextEditListener listener) {
        onTextEditListeners.remove(listener);
    }

    /**
     * Listener fired after {@link EditorSession#spliceStyles} so the view's
     * {@link jo.codeeditor.cache.LineRenderCache} can shift its keys to
     * match the new line layout. {@code delta > 0} means lines were
     * inserted at/after {@code fromLine}; {@code delta < 0} means lines
     * were removed. {@link #onLinesReset()} is fired on full restyle
     * (undo/redo/setLanguage) where every line is re-tokenized and the
     * cache should be cleared entirely.
     */
    public interface OnLinesShiftedListener {
        void onLinesShifted(int fromLine, int delta);
        void onLinesReset();
    }

    // ── Constants ─────────────────────────────────────────────────

    private static final int MAX_UNDO_STEPS = 300;
    private static final String TAB_STRING = "    ";
    // v3.35.0: LINE_COMMENT ("// ") removed — comment prefixes are now
    // language-driven (CommentSyntax.forLanguage / getCommentSyntax).

    // ── Constructors ──────────────────────────────────────────────

    public EditorSession() {
        this(EditorDocument.of(""));
    }

    public EditorSession(EditorDocument doc) {
        this.doc = doc;
        this.selection = Selection.cursor(0);
        this.undoManager = new UndoManager(MAX_UNDO_STEPS);
        this.highlighter = new SyntaxHighlighter();
        this.styledLines = new ArrayList<>();
        restyleAll();
    }

    // ── Accessors ─────────────────────────────────────────────────

    public EditorDocument getDocument() { return doc; }
    public Selection getSelection() { return selection; }
    public void setSelection(Selection sel) {
        this.selection = sel;
        notifySelectionChanged();
    }
    public void setSelection(int offset) {
        this.selection = Selection.cursor(offset);
        notifySelectionChanged();
    }
    public String getText() { return doc.getText(); }
    public String getLanguage() { return language; }

    /**
     * v3.36.0 — The language profile registered for this session's language
     * id (roadmap item 7), or null when the id is unknown to the
     * {@link jo.codeeditor.languages.LanguageRegistry}. The profile exposes
     * the family, aliases, extensions, keyword set and comment syntax the
     * editor is actually using for this session.
     */
    public jo.codeeditor.languages.LanguageProfile getLanguageProfile() {
        return jo.codeeditor.languages.LanguageRegistry.forName(language);
    }

    public List<StyledLine> getStyledLines() { return Collections.unmodifiableList(styledLines); }
    public UndoManager getUndoManager() { return undoManager; }
    public List<DiagnosticShift.Diagnostic> getDiagnostics() {
        // v3.34.0: the defensive ArrayList copy is GONE. Every setter and
        // every edit path REPLACES the list (setDiagnostics does
        // `new ArrayList<>(...)`, doReplaceRange does
        // `diagnostics = DiagnosticShift.shiftDiagnostics(...)` which
        // returns a fresh list) — the published list is never mutated
        // after assignment, so an unmodifiable view of it is exactly as
        // safe as a copy. The copy was O(list) per CALL, and the draw
        // path called it once per cache-missed line (see layoutForLine),
        // i.e. O(visibleLines x diagnostics) per scroll — all of it
        // pure garbage for the GC. Return the view instead.
        return Collections.unmodifiableList(diagnostics);
    }
    public List<DiagnosticShift.SemanticToken> getSemanticTokens() {
        // v3.34.0: same copy-elimination as getDiagnostics().
        return Collections.unmodifiableList(semanticTokens);
    }
    public List<DiagnosticShift.FoldRegion> getFoldRegions() {
        // v3.34.0: same copy-elimination as getDiagnostics().
        return Collections.unmodifiableList(foldRegions);
    }
    public List<DiagnosticShift.InlayHint> getInlayHints() {
        // v3.34.0: same copy-elimination as getDiagnostics().
        return Collections.unmodifiableList(inlayHints);
    }

    public void setLanguage(String language) {
        this.language = language;
        // ★ v2.55 — Async restyle pour le parser MAISON (avant v2.55,
        // ce path n'était déclenché QUE quand textMateEnabled était vrai,
        // c.-à-d. doc ≤ 800 lignes ET tm4e registered — donc jamais pour
        // le parser maison seul).
        //
        // v3.37.0 (B13) — l'appel SyntaxHighlighter.setTextMateEnabled(false)
        // a été RETIRÉ : ce mutateur d'état global statique (introduit v2.44)
        // était un design smell — deux sessions partageaient le drapeau,
        // si bien qu'un grand document coupait la délégation TextMate de
        // TOUS les autres onglets ouverts. La protection grands documents
        // est désormais locale à chaque boucle de restyle : chaque site
        // d'appel à styleLine passe allowTextMate = (lineCount <=
        // TEXTMATE_LINE_LIMIT), calculé sur SA propre taille de document.
        //
        // La mécanique d'async est robuste :
        //   - Snapshot du doc (immutable EditorDocument) → pas de race
        //     avec replaceRange pendant le travail
        //   - Cancellation via pendingRestyle (AtomicReference<Future>)
        //   - Stale-result detection via restyleGeneration (volatile)
        //   - Doc-changed detection via doc != docAtStart (ref identity)
        //   - Atomic swap du volatile styledLines
        //   - Thread interruption check dans la boucle worker
        //   - Listener cross-thread (EditorView posts onLinesReset sur l'UI)
        restyleAllAsync();
        // v3.3.0: Auto-detect fold regions when the language is set.
        detectFolds();
        // ★ v2.33 : nouveau document/langage → les défauts de pliage serveur
        // (imports repliés) peuvent s'appliquer une première fois.
        foldDefaultsApplied = false;
    }

    /**
     * v3.37.0 (B13) — Large-document TextMate circuit breaker, decided
     * per restyle pass (replaces the removed global static
     * {@code SyntaxHighlighter.textMateEnabled} toggle and the deprecated
     * v2.44 {@code MAX_LINES_FOR_TEXTMATE = 800} constant).
     *
     * <p>Documents with more lines than this limit skip TextMate
     * delegation and use the built-in tokenizer, whose per-line cost is
     * 10–50× lower. The gate is passed as a parameter to
     * {@code styleLine(line, state, language, allowTextMate)} at every
     * call site, so it is computed from THIS session's document — a
     * 5000-line tab no longer disables TextMate for a small tab in
     * another EditorView (the exact design smell B13 removed).</p>
     */
    static final int TEXTMATE_LINE_LIMIT = 800;

    /**
     * Detects fold regions for the current document + language.
     * Called automatically by {@link #setLanguage(String)} and can be
     * called manually after a large text change.
     *
     * @since v3.3.0
     */
    public void detectFolds() {
        List<DiagnosticShift.FoldRegion> folds =
            jo.codeeditor.highlight.FoldDetector.detect(doc.getText(), language);
        // Preserve collapsed state from existing folds.
        // v3.3.0 fix: iterate over `folds` (the fresh list) and replace
        // entries that match old collapsed folds — NOT foldRegions.
        for (int i = 0; i < folds.size(); i++) {
            DiagnosticShift.FoldRegion fresh = folds.get(i);
            for (DiagnosticShift.FoldRegion old : foldRegions) {
                if (old.start == fresh.start && old.end == fresh.end && old.collapsed) {
                    folds.set(i, new DiagnosticShift.FoldRegion(
                        fresh.start, fresh.end, fresh.placeholder,
                        fresh.kind, true));
                    break;
                }
            }
        }
        setFoldRegions(folds);
    }

    public void setOnTextEditListener(OnTextEditListener listener) {
        onTextEditListeners.clear();
        if (listener != null) onTextEditListeners.add(listener);
    }

    public void setOnSnippetEditListener(OnSnippetEditListener listener) {
        this.onSnippetEditListener = listener;
    }

    /**
     * Registers a listener fired after every line splice / full restyle.
     * The view uses this to keep its {@link jo.codeeditor.cache.LineRenderCache}
     * keys aligned with the document's line layout.
     */
    public void setOnLinesShiftedListener(OnLinesShiftedListener listener) {
        this.linesShiftListener = listener;
    }

    /**
     * Returns the per-line text revision stamp for the given line. The
     * view's render cache uses this to skip re-tokenizing lines that
     * haven't changed since the last frame.
     */
    public int getLineTextRevision(int line) {
        if (line < 0 || line >= lineTextRevisions.length) return 0;
        return lineTextRevisions[line];
    }

    /** Global inlay-hint revision (bumped on {@link #setInlayHints}). */
    public int getInlayHintsRevision() { return inlayHintsRev; }

    // ── v3.34.0 — Per-line bucket indexes (CodeAssist 3.20 LineOverlay idea) ──

    /**
     * Memoized bucket index: hint line → hints starting on that line.
     * Rebuilt lazily when the source list reference changes (the list is
     * replaced — never mutated — by every setter/edit path, so identity
     * is a rock-solid invalidation key). Port of the CodeAssist v3.20
     * `LineOverlay.update()` memoization: a re-push of the same list is
     * free, an edit costs ONE O(H) rebuild instead of one full-list
     * filter PER cache-missed line in the draw path.
     */
    private volatile Map<Integer, List<DiagnosticShift.InlayHint>> inlayHintsByLine;
    private volatile List<DiagnosticShift.InlayHint> inlayHintsIndexedFor = Collections.emptyList();

    /** Same memoization for semantic tokens (tokens may span lines). */
    private volatile Map<Integer, List<DiagnosticShift.SemanticToken>> semTokensByLine;
    private volatile List<DiagnosticShift.SemanticToken> semTokensIndexedFor = Collections.emptyList();

    /** v3.36.0 — same memoization for diagnostics grouped by start line. */
    private volatile Map<Integer, List<DiagnosticShift.Diagnostic>> diagnosticsByLine;
    private volatile List<DiagnosticShift.Diagnostic> diagnosticsIndexedFor = Collections.emptyList();

    /**
     * v3.36.0 — Returns every diagnostic whose START offset sits on the
     * given document line (roadmap item 5, port of CodeAssist v3.20's
     * {@code diagnosticsByStartLine()}). The bucket is sorted
     * most-severe-first (then by start offset), so the first element is the
     * line's "primary" diagnostic — the one the chip shows. Memoized on
     * the diagnostics list reference with the same pattern as
     * {@link #getInlayHintsForLine} (v3.34.0): re-pushing the same list is
     * free, an edit (which replaces the list via DiagnosticShift) costs
     * one O(D) rebuild instead of an O(D) filter per queried line.
     */
    public List<DiagnosticShift.Diagnostic> getDiagnosticsForLine(int line) {
        List<DiagnosticShift.Diagnostic> source = diagnostics;
        Map<Integer, List<DiagnosticShift.Diagnostic>> idx = diagnosticsByLine;
        if (idx == null || diagnosticsIndexedFor != source) {
            idx = buildDiagnosticBuckets(source);
            diagnosticsByLine = idx;
            diagnosticsIndexedFor = source;
        }
        List<DiagnosticShift.Diagnostic> bucket = idx.get(line);
        return bucket != null ? bucket : Collections.emptyList();
    }

    private Map<Integer, List<DiagnosticShift.Diagnostic>> buildDiagnosticBuckets(
            List<DiagnosticShift.Diagnostic> source) {
        if (source.isEmpty()) return Collections.emptyMap();
        Map<Integer, List<DiagnosticShift.Diagnostic>> m = new HashMap<>(source.size() * 2);
        for (DiagnosticShift.Diagnostic d : source) {
            int start = Math.max(0, Math.min(d.start, doc.length()));
            int line = doc.lineForOffset(start);
            m.computeIfAbsent(line, k -> new ArrayList<>(2)).add(d);
        }
        for (List<DiagnosticShift.Diagnostic> bucket : m.values()) {
            bucket.sort((a, b) -> a.severity != b.severity
                    ? b.severity - a.severity : a.start - b.start);
        }
        return m;
    }

    /**
     * v3.34.0 — Returns the inlay hints that fall on the given document
     * line (hint offset within [lineStart, lineEnd]). O(bucket) instead
     * of the previous O(total hints) full-list filter per line.
     */
    public List<DiagnosticShift.InlayHint> getInlayHintsForLine(int line) {
        List<DiagnosticShift.InlayHint> source = inlayHints;
        Map<Integer, List<DiagnosticShift.InlayHint>> idx = inlayHintsByLine;
        if (idx == null || inlayHintsIndexedFor != source) {
            idx = buildInlayBuckets(source);
            inlayHintsByLine = idx;
            inlayHintsIndexedFor = source;
        }
        List<DiagnosticShift.InlayHint> bucket = idx.get(line);
        return bucket != null ? bucket : Collections.emptyList();
    }

    /**
     * v3.34.0 — Returns the semantic tokens intersecting the given
     * document line (a token spanning lines 5..9 appears in every
     * bucket from 5 to 9). O(bucket) instead of O(total tokens) per line.
     */
    public List<DiagnosticShift.SemanticToken> getSemanticTokensForLine(int line) {
        List<DiagnosticShift.SemanticToken> source = semanticTokens;
        Map<Integer, List<DiagnosticShift.SemanticToken>> idx = semTokensByLine;
        if (idx == null || semTokensIndexedFor != source) {
            idx = buildSemBuckets(source);
            semTokensByLine = idx;
            semTokensIndexedFor = source;
        }
        List<DiagnosticShift.SemanticToken> bucket = idx.get(line);
        return bucket != null ? bucket : Collections.emptyList();
    }

    private Map<Integer, List<DiagnosticShift.InlayHint>> buildInlayBuckets(
            List<DiagnosticShift.InlayHint> source) {
        if (source.isEmpty()) return Collections.emptyMap();
        Map<Integer, List<DiagnosticShift.InlayHint>> m = new HashMap<>(source.size() * 2);
        for (DiagnosticShift.InlayHint h : source) {
            int line = doc.lineForOffset(Math.max(0, Math.min(h.offset, doc.length())));
            m.computeIfAbsent(line, k -> new ArrayList<>(2)).add(h);
        }
        return m;
    }

    private Map<Integer, List<DiagnosticShift.SemanticToken>> buildSemBuckets(
            List<DiagnosticShift.SemanticToken> source) {
        if (source.isEmpty()) return Collections.emptyMap();
        Map<Integer, List<DiagnosticShift.SemanticToken>> m = new HashMap<>(source.size() * 2);
        for (DiagnosticShift.SemanticToken t : source) {
            int start = Math.max(0, Math.min(t.start, doc.length()));
            int end = Math.max(start, Math.min(t.start + t.length, doc.length()));
            int startLine = doc.lineForOffset(start);
            int endLine = doc.lineForOffset(Math.max(start, end - 1));
            for (int line = startLine; line <= endLine; line++) {
                m.computeIfAbsent(line, k -> new ArrayList<>(2)).add(t);
            }
        }
        return m;
    }

    /** Global semantic-token revision (bumped on {@link #setSemanticTokens}). */
    public int getSemanticTokensRevision() { return semTokensRev; }

    public void setImeListener(ImeListener listener) {
        this.imeListener = listener;
    }

    // ── Diagnostics / tokens management ───────────────────────────

    public void setDiagnostics(List<DiagnosticShift.Diagnostic> diagnostics) {
        this.diagnostics = new ArrayList<>(diagnostics);
    }

    public void setSemanticTokens(List<DiagnosticShift.SemanticToken> tokens) {
        this.semanticTokens = new ArrayList<>(tokens);
        // Bump the global sem-token revision so the view's render cache
        // misses on every line that had a cached sem span list.
        semTokensRev++;
    }

    public void setFoldRegions(List<DiagnosticShift.FoldRegion> regions) {
        this.foldRegions = new ArrayList<>(regions);
        // v3.35.0: invalidate the view's fold prefix-sum index.
        foldRev++;
    }

    /**
     * v3.35.0 — revision of the fold set (bumped on every mutation).
     * Consumers memoize fold-derived indexes on this value.
     */
    public int getFoldRevision() {
        return foldRev;
    }

    /**
     * ★ v2.33 — applique un jeu de folds AUTORITATIF venu du serveur LSP
     * (portage {@code applyCodeFolds} de CodeAssist / EditorSession.kt) :
     * <ul>
     *   <li>une région PRÉCÉDÉMMENT repliée (même [start, end]) le reste ;</li>
     *   <li>{@code collapsedByDefault} s'applique UNE SEULE FOIS par
     *       document — un re-tir serveur (après une édition) ne re-plie pas
     *       une région que l'utilisateur a dépliée ;</li>
     *   <li>une région fraîchement apparue suit son défaut.</li>
     * </ul>
     */
    public void applyCodeFolds(List<DiagnosticShift.FoldRegion> fresh) {
        List<DiagnosticShift.FoldRegion> next = new ArrayList<>(fresh.size());
        for (DiagnosticShift.FoldRegion r : fresh) {
            boolean keep = false;
            for (DiagnosticShift.FoldRegion old : foldRegions) {
                if (old.collapsed && old.start == r.start && old.end == r.end) {
                    keep = true;
                    break;
                }
            }
            if (!keep && r.collapsedByDefault && !foldDefaultsApplied) {
                keep = true;
            }
            next.add(keep && !r.collapsed
                    ? new DiagnosticShift.FoldRegion(r.start, r.end,
                            r.placeholder, r.kind, true, r.collapsedByDefault)
                    : r);
        }
        foldDefaultsApplied = true;
        setFoldRegions(next);
    }

    /**
     * Toggles the collapsed state of any fold region that STARTS at the given
     * document line. Returns true if a fold was toggled.
     * <p>
     * Fold regions are matched by line — the caller (typically a gutter tap)
     * passes the doc line of the chevron it wants to toggle.
     */
    public boolean toggleFoldAtLine(int docLine) {
        int lineStart = doc.lineStart(docLine);
        for (int i = 0; i < foldRegions.size(); i++) {
            DiagnosticShift.FoldRegion r = foldRegions.get(i);
            int rStartLine = doc.lineForOffset(r.start);
            if (rStartLine == docLine) {
                DiagnosticShift.FoldRegion toggled = new DiagnosticShift.FoldRegion(
                    r.start, r.end, r.placeholder, r.kind, !r.collapsed);
                foldRegions.set(i, toggled);
                foldRev++;
                return true;
            }
            // Also accept a region whose start offset equals the line start,
            // even if lineForOffset rounded differently.
            if (r.start == lineStart) {
                DiagnosticShift.FoldRegion toggled = new DiagnosticShift.FoldRegion(
                    r.start, r.end, r.placeholder, r.kind, !r.collapsed);
                foldRegions.set(i, toggled);
                foldRev++;
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the currently-collapsed fold regions (a fresh list).
     * Used by the view to build a {@link jo.codeeditor.fold.FoldModel} for
     * rendering and offset↔visual-row mapping.
     */
    public List<DiagnosticShift.FoldRegion> getCollapsedFolds() {
        List<DiagnosticShift.FoldRegion> out = new ArrayList<>();
        for (DiagnosticShift.FoldRegion r : foldRegions) {
            if (r.collapsed) out.add(r);
        }
        return out;
    }

    /**
     * Returns true if the given document line is currently hidden by a
     * collapsed fold region. Convenience method — the view uses this to
     * skip drawing hidden lines.
     */
    public boolean isLineFolded(int docLine) {
        for (DiagnosticShift.FoldRegion r : foldRegions) {
            if (!r.collapsed) continue;
            int startLine = doc.lineForOffset(r.start);
            int endLine = doc.lineForOffset(r.end);
            if (docLine > startLine && docLine <= endLine) return true;
        }
        return false;
    }

    /**
     * If the caret is inside a collapsed fold region, expand it. Used by
     * programmatic navigation (go-to-def, rename, search results) so the
     * caret never lands in hidden text.
     */
    public void expandFoldAt(int offset) {
        for (int i = 0; i < foldRegions.size(); i++) {
            DiagnosticShift.FoldRegion r = foldRegions.get(i);
            if (r.collapsed && r.start < offset && offset < r.end) {
                foldRegions.set(i, new DiagnosticShift.FoldRegion(
                    r.start, r.end, r.placeholder, r.kind, false));
                foldRev++;
                return;
            }
        }
    }

    public void setInlayHints(List<DiagnosticShift.InlayHint> hints) {
        this.inlayHints = new ArrayList<>(hints);
        // Bump the global inlay revision so the view's render cache misses
        // on every line that had a cached inlay list and rebuilds it from
        // the new global list.
        inlayHintsRev++;
    }

    // ── Batch editing ─────────────────────────────────────────────

    /**
     * Begin a batch edit. Multiple edits within a batch are grouped into
     * a single undo step.
     */
    public void beginBatch() {
        if (batchDepth == 0) {
            batchEdits.clear();
            batchSelBefore = selection.start;
        }
        batchDepth++;
    }

    /**
     * End a batch edit. If this closes the outermost batch, the grouped
     * edits are pushed as a single undo step.
     */
    public void endBatch() {
        batchDepth--;
        if (batchDepth <= 0) {
            batchDepth = 0;
            if (!batchEdits.isEmpty()) {
                UndoStep step = new UndoStep(batchEdits, batchSelBefore, selection.start);
                undoManager.pushStep(step);
                batchEdits.clear();
            }
        }
    }

    public boolean isInBatch() { return batchDepth > 0; }

    /**
     * ★ v0.1.0.49-v2.20 — Active/désactive le mode lecture-seule.
     * En lecture-seule, les mutations de texte sont ignorées ; le caret,
     * la sélection, la copie et le scroll continuent de fonctionner.
     */
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    public boolean isReadOnly() { return readOnly; }

    // ── Core mutation ─────────────────────────────────────────────

    /**
     * THE mutation point. Everything funnels through here.
     * Records undo (with coalescing for single-char typing),
     * splices line styles incrementally, shifts diagnostics/tokens/folds/inlays.
     * <p>
     * After the call, the selection is set to a cursor at {@code start + insertion.length()}.
     * If you need a different caret position (e.g. skip-over), use
     * {@link #replaceRangeWithCaret(int, int, String, int)} instead.
     */
    public void replaceRange(int start, int end, String insertion) {
        replaceRangeWithCaret(start, end, insertion, start + insertion.length());
    }

    /**
     * Variant of {@link #replaceRange(int, int, String)} that lets the caller
     * specify the final caret position explicitly. Needed for smart-edit operations
     * like skip-over, empty-pair expansion, or smart indent where the caret isn't
     * simply {@code start + insertion.length()}.
     * <p>
     * If the edit is a no-op (start == end AND insertion is empty AND caret is
     * unchanged), nothing is recorded on the undo stack and no callback fires.
     */
    public void replaceRangeWithCaret(int start, int end, String insertion, int caretAfter) {
        if (readOnly && !(start == end && (insertion == null || insertion.isEmpty()))) {
            // Lecture-seule : on ignore silencieusement la mutation. C'est
            // aussi le chemin d'application des undo/redo et du paste/cut —
            // tout est couvert par ce seul point d'entrée.
            return;
        }
        if (start == end && (insertion == null || insertion.isEmpty())) {
            // Pure cursor move (e.g. skip-over). Don't pollute the undo stack.
            selection = Selection.cursor(caretAfter);
            notifySelectionChanged();
            return;
        }
        // Was the IME composing when this edit landed? If so, we'll need
        // to force a restartInput afterwards — the IME keeps its own
        // composing buffer (the pre-accept prefix) and the next keystroke
        // would re-insert it without a restart.
        boolean wasComposing = isComposing();
        EditSpan span = doReplaceRange(start, end, insertion, caretAfter);
        // v3.3.3: Notify all chained listeners (was a single listener).
        if (!onTextEditListeners.isEmpty()) {
            int s = span.start, e = span.start + span.removed;
            for (OnTextEditListener l : onTextEditListeners) {
                l.onTextEdit(s, e, insertion);
            }
        }
        if (imeListener != null) {
            imeListener.onTextChanged(span);
            imeListener.onSelectionChanged(
                selection.start, selection.end, composingStart, composingEnd);
            if (wasComposing && !imeListener.isSyncingExtractedText()) {
                imeListener.onRestartInput();
            }
        }
    }

    /**
     * Internal mutation. Performs the actual splice of doc/styles/diagnostics/etc.
     * and shifts the composing region. Does NOT fire onTextChanged or
     * onSelectionChanged — callers (which know whether they were composing
     * or not) are responsible for that.
     * <p>
     * Returns the {@link EditSpan} so the caller can include it in its
     * callback payload.
     */
    private EditSpan doReplaceRange(int start, int end, String insertion, int caretAfter) {
        if (insertion == null) insertion = "";

        // Clamp bounds to the document — a stale offset from a race
        // between an edit and an invalidate must never crash the engine.
        start = Math.max(0, Math.min(start, doc.length()));
        end = Math.max(start, Math.min(end, doc.length()));
        caretAfter = Math.max(0, Math.min(caretAfter, doc.length() + insertion.length()));

        // If the edit turns out to be a no-op after clamping, just move the caret.
        if (start == end && insertion.isEmpty()) {
            selection = Selection.cursor(caretAfter);
            return new EditSpan(start, 0, 0);
        }

        String removed = "";
        if (end > start) {
            removed = doc.getText().substring(start, end);
        }
        EditOp op = new EditOp(start, removed, insertion);
        int selBefore = selection.start;

        // Record undo
        if (isInBatch()) {
            batchEdits.add(op);
        } else if (!undoManager.tryCoalesce(op, caretAfter)) {
            UndoStep step = UndoStep.single(op, selBefore, caretAfter);
            undoManager.pushStep(step);
        }

        // Compute line range for style splicing
        int firstLine = doc.lineForOffset(start);
        int lastLine = (end > start) ? doc.lineForOffset(end) : firstLine;
        int removedLines = lastLine - firstLine + 1;

        // Create EditSpan for shifting
        EditSpan span = new EditSpan(start, end - start, insertion.length());

        // Apply to document
        doc = doc.replace(start, end, insertion);
        selection = Selection.cursor(caretAfter);

        // Splice styles
        spliceStyles(firstLine, removedLines, insertion);

        // Shift diagnostics, semantic tokens, fold regions, inlay hints
        diagnostics = DiagnosticShift.shiftDiagnostics(diagnostics, span);
        semanticTokens = DiagnosticShift.shiftSemanticTokens(semanticTokens, span);
        foldRegions = DiagnosticShift.shiftFoldRegions(foldRegions, span);
        foldRev++;   // v3.35.0: fold offsets changed — view index is stale.
        inlayHints = DiagnosticShift.shiftInlayHints(inlayHints, span);

        // Shift the composing region too — if it overlapped the edit,
        // the IME's mirror would otherwise point at the wrong text.
        // Use mapStart for BOTH ends (left-gravity) because the composing
        // region is a single semantic unit — an edit at its boundary should
        // NOT extend it. (Diagnostic ranges use right-gravity for ends, which
        // would wrongly extend the composing region when text is inserted
        // right after it.)
        if (composingStart >= 0) {
            composingStart = DiagnosticShift.mapStart(composingStart, span);
            composingEnd = DiagnosticShift.mapStart(composingEnd, span);
            if (composingStart >= composingEnd) {
                composingStart = -1;
                composingEnd = -1;
            }
        }
        return span;
    }

    /** Pushes the current selection / composing state to the IME listener. */
    private void notifySelectionChanged() {
        if (imeListener != null) {
            imeListener.onSelectionChanged(
                selection.start, selection.end, composingStart, composingEnd);
        }
    }

    private void spliceStyles(int firstLine, int removedLines, String insertion) {
        int newLineCount = 1;
        for (int i = 0; i < insertion.length(); i++) {
            if (insertion.charAt(i) == '\n') newLineCount++;
        }
        // v3.37.0 (B13): TextMate gate computed from THIS document's size
        // (was: global static toggle shared across all sessions).
        boolean allowTextMate = doc.lineCount() <= TEXTMATE_LINE_LIMIT;
        for (int i = 0; i < removedLines && firstLine < styledLines.size(); i++) {
            styledLines.remove(firstLine);
        }
        // The per-line text revision array is rebuilt below, parallel to the
        // updated styledLines list — stamps for unchanged lines are preserved,
        // stamps for re-tokenized lines are bumped.
        int entryState = (firstLine > 0 && firstLine - 1 < styledLines.size())
            ? styledLines.get(firstLine - 1).exitState
            : LexState.NORMAL;
        for (int i = 0; i < newLineCount; i++) {
            int lineNum = firstLine + i;
            String lineText = doc.lineText(lineNum);
            StyledLine styled = highlighter.styleLine(lineText, entryState, language, allowTextMate);
            styledLines.add(lineNum, styled);
            entryState = styled.exitState;
        }
        int nextLine = firstLine + newLineCount;
        while (nextLine < styledLines.size()) {
            int prevState = styledLines.get(nextLine - 1).exitState;
            StyledLine current = styledLines.get(nextLine);
            if (current.entryState == prevState) break;
            String lineText = doc.lineText(nextLine);
            StyledLine restyled = highlighter.styleLine(lineText, prevState, language, allowTextMate);
            styledLines.set(nextLine, restyled);
            nextLine++;
        }

        // Rebuild the per-line text revision array parallel to styledLines,
        // bumping the stamp for every line whose StyledLine instance is
        // newly-created or re-tokenized in this splice. Lines whose
        // StyledLine instance is unchanged keep their old stamp — the
        // view's render cache stays valid for them.
        int totalLines = styledLines.size();
        int[] newRevs = new int[totalLines];
        // Preserve stamps for lines BEFORE firstLine (unchanged).
        for (int i = 0; i < firstLine && i < newRevs.length; i++) {
            newRevs[i] = (i < lineTextRevisions.length) ? lineTextRevisions[i] : 0;
        }
        // Bump stamps for the freshly-tokenized lines [firstLine, firstLine + newLineCount).
        for (int i = firstLine; i < firstLine + newLineCount && i < newRevs.length; i++) {
            newRevs[i] = ++lineTextRevCounter;
        }
        // For lines AFTER the spliced region: if their StyledLine instance
        // changed (re-tokenized by the entry-state cascade), bump the stamp.
        // Otherwise carry over the old stamp.
        for (int i = firstLine + newLineCount; i < newRevs.length; i++) {
            int oldIdx = i - newLineCount + removedLines;
            if (oldIdx >= 0 && oldIdx < lineTextRevisions.length
                && i < styledLines.size() && oldIdx + firstLine < styledLines.size()) {
                // The cascade loop above may have replaced this line's StyledLine.
                // We can't easily detect that here — bump conservatively if the
                // cascade reached this line (nextLine > i would mean the cascade
                // stopped before this line, so the instance is unchanged).
                // For simplicity, bump everything from firstLine+newLineCount up
                // to nextLine (the cascade end). Past nextLine, the instance is
                // the same as before the splice — carry over the stamp.
                if (i < nextLine) {
                    newRevs[i] = ++lineTextRevCounter;
                } else {
                    newRevs[i] = lineTextRevisions[oldIdx];
                }
            } else {
                newRevs[i] = ++lineTextRevCounter;
            }
        }
        lineTextRevisions = newRevs;

        // Notify the view's render cache so it can shiftKeys its entries
        // to the new line layout. delta = newLineCount - removedLines.
        if (linesShiftListener != null) {
            int delta = newLineCount - removedLines;
            if (delta != 0) {
                linesShiftListener.onLinesShifted(firstLine, delta);
            }
        }
    }

    private void restyleAll() {
        styledLines.clear();
        // v3.37.0 (B13): per-call TextMate gate (was: global static toggle).
        boolean allowTextMate = doc.lineCount() <= TEXTMATE_LINE_LIMIT;
        int state = LexState.NORMAL;
        for (int i = 0; i < doc.lineCount(); i++) {
            String lineText = doc.lineText(i);
            StyledLine styled = highlighter.styleLine(lineText, state, language, allowTextMate);
            styledLines.add(styled);
            state = styled.exitState;
        }
        // Every line is re-tokenized — bump every per-line stamp so the
        // view's render cache misses on every line and rebuilds.
        lineTextRevisions = new int[doc.lineCount()];
        for (int i = 0; i < lineTextRevisions.length; i++) {
            lineTextRevisions[i] = ++lineTextRevCounter;
        }
        // Tell the view's cache to drop everything — line layout may have
        // changed (e.g. undo of a newline insert removes a line) and the
        // keys no longer line up.
        if (linesShiftListener != null) {
            linesShiftListener.onLinesReset();
        }
    }

    // ── v2.45 — Async restyle path (off-UI-thread tokenization) ──────────

    /**
     * v2.45 — Single-thread executor for background restyle work.
     *
     * <p>Single-threaded so that:
     * <ul>
     *   <li>Restyle tasks are serialized — no concurrent tokenization
     *       on the same session (which would race on {@link #stateMaps}
     *       inside {@link SyntaxHighlighter} / {@code TextMateTokenizerImpl}).</li>
     *   <li>Cancellation of a pending restyle (via {@code Future.cancel(true)})
     *       interrupts the worker thread — the worker checks
     *       {@code Thread.interrupted()} in its loop and aborts early.</li>
     * </ul>
     *
     * <p>Daemon priority so it doesn't block JVM shutdown. Priority
     * below NORM so the UI thread wins CPU contests.
     */
    private final ExecutorService restyleExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EditorSession-restyle");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.setDaemon(true);
        return t;
    });

    /**
     * v2.45 — Currently pending async restyle, if any. Used to cancel
     * an in-flight restyle when a new one is requested (e.g. user
     * opens a different file before the first finished).
     *
     * <p>Atomic so the cancel-and-replace is race-free across the
     * UI thread (submitting new work) and the worker thread (clearing
     * the field on completion).
     */
    private final AtomicReference<Future<?>> pendingRestyle = new AtomicReference<>();

    /**
     * v2.45 — Monotonic token used to detect stale async results.
     *
     * <p>Each call to {@link #restyleAllAsync()} increments this counter
     * and captures the new value. The worker thread captures the value
     * at start; before swapping results, it checks that its captured
     * value still equals the current field. If not, a newer restyle
     * was issued — drop the result.
     *
     * <p>Atomic isn't needed here — the field is only mutated on the
     * UI thread (caller of {@link #setLanguage} / etc.), and read on
     * the worker thread. Volatile is sufficient for visibility.
     */
    private volatile long restyleGeneration = 0;

    /**
     * v2.45 — Async version of {@link #restyleAll()}. Kicks off the
     * tokenization work on a background thread; the UI thread is
     * free to return immediately to the user (file open completes
     * fast even for a 5 000-line HTML file with TextMate enabled).
     *
     * <p>Behavior:
     * <ol>
     *   <li>Cancels any previously pending async restyle.</li>
     *   <li>Snapshots the doc text + line count + language at call time
     *       (EditorDocument is immutable, so {@link EditorDocument#getText()}
     *       is a stable String reference).</li>
     *   <li>Clears {@link #styledLines} to an empty list so stale spans
     *       aren't drawn during the async gap. The view will fall back
     *       to plain text rendering until the new styledLines arrive.</li>
     *   <li>Submits a task to {@link #restyleExecutor} that:
     *       <ul>
     *         <li>Builds a fresh {@code List<StyledLine>} from the snapshot.</li>
     *         <li>Re-checks the snapshot against the current doc — if
     *             the doc has changed (replaceRange happened), drops
     *             the result.</li>
     *         <li>Swaps {@link #styledLines} to the new list atomically
     *             (volatile field assignment).</li>
     *         <li>Resets {@link #lineTextRevisions}.</li>
     *         <li>Calls {@link OnLinesShiftedListener#onLinesReset()}
     *             — the listener is expected to post the call to the
     *             UI thread if needed (mirrors the
     *             {@code notifyDiagnosticsChanged} pattern in EditorView).</li>
     *       </ul>
     *   </li>
     * </ol>
     *
     * <p><b>Why no {@code invalidate()} here?</b> The EditorSession is
     * pure-Java and doesn't touch the View. The view's listener
     * (EditorView.cacheShiftListener) is responsible for both clearing
     * the render cache AND calling {@code invalidate()} on the view.
     *
     * @since v2.45
     */
    void restyleAllAsync() {
        // v3.34.0: after dispose() the executor is shut down — submitting
        // would throw RejectedExecutionException. Fall back to the
        // synchronous path: correct, just blocking. Edits still work.
        if (disposed) {
            restyleAll();
            return;
        }
        // v3.34.0: synchronized — restyleAllAsync can now also be called
        // from the WORKER thread (doc-changed re-schedule inside
        // doAsyncRestyle). Without the monitor, a concurrent UI-thread
        // setLanguage() could race the bookkeeping below (notably the
        // non-atomic ++restyleGeneration and the lineTextRevisions array
        // rebuild) producing two tasks with the same generation, both
        // passing the stale check. Serializing the submission path makes
        // the generation strictly monotonic across all callers.
        synchronized (this) {
        // Snapshot the doc state.
        final String textSnapshot = doc.getText();
        final int lineCountSnapshot = doc.lineCount();
        final String langSnapshot = language;
        final long myGeneration = ++restyleGeneration;

        // Cancel any pending restyle.
        Future<?> prev = pendingRestyle.getAndSet(null);
        if (prev != null) {
            prev.cancel(/*mayInterrupt=*/true);
        }

        // v2.45 — Do NOT clear styledLines here. Keep the old (stale)
        // tokens visible during the async gap. Rationale:
        // - The incremental edit path (replaceRange → spliceStyles)
        //   operates on the live styledLines list. If we cleared it
        //   here, an edit during the async gap would leave a partial
        //   list, and the async worker's stale-result drop would leave
        //   us in an inconsistent state (styledLines.size() !=
        //   doc.lineCount()).
        // - With the keep-old approach, the user sees briefly-wrong
        //   colors (e.g., Java colors on an HTML file for ~200ms) until
        //   the async completes and the new tokens swap in atomically.
        // - The view's renderer gracefully handles styledLines.size() <
        //   doc.lineCount() (it falls through to plain text), so no
        //   crash if the new doc has more lines than the old styledLines.
        //
        // We DO reset lineTextRevisions to force a render cache miss
        // on every line, so the view rebuilds its cached line renders
        // from the new styledLines (post-swap).
        lineTextRevisions = new int[Math.max(lineTextRevisions.length, lineCountSnapshot)];
        for (int i = 0; i < lineTextRevisions.length; i++) {
            lineTextRevisions[i] = ++lineTextRevCounter;
        }

        // Capture for the worker's stale-result check.
        final EditorDocument docAtStart = doc;

        Future<?> task = restyleExecutor.submit(() -> {
            try {
                doAsyncRestyle(textSnapshot, lineCountSnapshot, langSnapshot,
                    myGeneration, docAtStart);
            } catch (Throwable t) {
                // Defensive: log and bail. The session is still
                // functional with the old styledLines — the user just
                // sees stale colors until the next edit triggers a
                // (sync) restyle.
                System.err.println("EditorSession async restyle failed: " + t);
            }
        });
        pendingRestyle.set(task);
        } // synchronized(this)
    }

    /**
     * v2.45 — Worker-side implementation of async restyle.
     *
     * <p>Builds the new {@code List<StyledLine>} from the snapshot,
     * checks for staleness, then atomically swaps the field and
     * notifies the listener.
     */
    private void doAsyncRestyle(String textSnapshot, int lineCountSnapshot,
            String langSnapshot, long myGeneration, EditorDocument docAtStart) {
        // Build the new styledLines from the text snapshot.
        // Optimization: avoid calling doc.lineText(i) (O(N) per call →
        // O(N^2) total) by splitting the snapshot text ourselves.
        List<StyledLine> newStyled = new ArrayList<>(lineCountSnapshot);
        int state = LexState.NORMAL;
        // v3.37.0 (B13): TextMate gate from the SNAPSHOT's own size —
        // decided per async pass, immune to cross-session interference.
        boolean allowTextMate = lineCountSnapshot <= TEXTMATE_LINE_LIMIT;
        int start = 0;
        for (int i = 0; i < lineCountSnapshot; i++) {
            // Check for interruption (cancellation).
            if (Thread.interrupted()) {
                // A newer restyle was issued — abort silently.
                return;
            }
            int end = textSnapshot.indexOf('\n', start);
            String lineText = (end < 0)
                ? textSnapshot.substring(start)
                : textSnapshot.substring(start, end);
            StyledLine styled = highlighter.styleLine(lineText, state, langSnapshot, allowTextMate);
            newStyled.add(styled);
            state = styled.exitState;
            if (end < 0) break;
            start = end + 1;
        }

        // Stale-result check: if a newer restyle was issued, abort.
        if (restyleGeneration != myGeneration) {
            return;
        }
        // Doc-changed check: if the doc reference has been replaced
        // (replaceRange / undo / redo happened during the async work),
        // the snapshot is stale — v3.34.0 fix: do NOT drop silently.
        // Before v3.34.0 the result was dropped without re-scheduling, so
        // an edit landing inside the async gap left the document with
        // PERMANENTLY mixed-language highlighting: spliceStyles had
        // re-tokenized only the edited lines with the NEW language while
        // every other line kept the OLD language's tokens (e.g. switch a
        // 5000-line file from Java to XML, type one char within ~200 ms:
        // that line rendered as XML, the 4999 others stayed Java-colored
        // until the next setLanguage/undo). Re-submit a restyle for the
        // CURRENT doc — restyleAllAsync snapshots fresh state, so the
        // next pass picks up the post-edit text.
        if (doc != docAtStart) {
            restyleAllAsync();
            return;
        }

        // Atomically swap styledLines. Volatile write — visible to
        // the UI thread on the next read of getStyledLines().
        styledLines = newStyled;

        // Reset lineTextRevisions to force the render cache to miss
        // on every line. (This is the same logic as in the sync path.)
        int[] newRevs = new int[newStyled.size()];
        for (int i = 0; i < newRevs.length; i++) {
            newRevs[i] = ++lineTextRevCounter;
        }
        lineTextRevisions = newRevs;

        // Notify the listener. The listener is expected to be the
        // EditorView's cacheShiftListener, which posts the work to
        // the UI thread (mirrors the notifyDiagnosticsChanged pattern).
        if (linesShiftListener != null) {
            linesShiftListener.onLinesReset();
        }

        // Note: we don't clear pendingRestyle here — the Future is
        // already complete by the time we reach this point (we're
        // running inside it). isAsyncRestylePending() returns false
        // for completed futures, so callers see the right state.
        // The next restyleAllAsync() will cancel us via
        // pendingRestyle.getAndSet(null) — which is a no-op on a
        // completed Future.
    }

    /**
     * v2.45 — Waits for any pending async restyle to complete (blocking).
     *
     * <p>For tests: ensures that assertions on styledLines see the
     * post-restyle state, not the in-flight gap.
     *
     * @throws InterruptedException if the calling thread is interrupted
     * @since v2.45
     */
    void awaitPendingRestyle() throws InterruptedException {
        Future<?> task = pendingRestyle.get();
        if (task != null) {
            try {
                task.get();
            } catch (java.util.concurrent.ExecutionException e) {
                // Restyle failed — log and continue. styledLines may be empty.
                System.err.println("EditorSession awaitPendingRestyle: " + e);
            }
        }
    }

    /**
     * v2.45 — Whether an async restyle is currently in flight.
     * For tests + diagnostics.
     *
     * @since v2.45
     */
    boolean isAsyncRestylePending() {
        Future<?> task = pendingRestyle.get();
        return task != null && !task.isDone();
    }

    // ── Text insertion (delegates to EditOps) ─────────────────────

    /**
     * Insert text at cursor. Uses smartInsert for single chars, plain for multi.
     */
    public void commitText(String text) {
        if (text.length() == 1) {
            typeChar(text.charAt(0));
            return;
        }
        replaceRange(selection.start, selection.end, text);
    }

    /**
     * Type a single character with smart editing.
     * <p>
     * If the smart-edit result diverges from "replace selection with the
     * typed char and place the caret right after" (e.g. auto-close bracket,
     * skip-over closer), the IME's model is now stale — we force a
     * {@code restartInput} so it resyncs. The restart is skipped when an
     * extracted-text monitor is active — the per-edit push already keeps
     * that IME exact.
     */
    public void typeChar(char ch) {
        int selMin = Math.min(selection.start, selection.end);
        int selMax = Math.max(selection.start, selection.end);
        RangeEdit re = EditOps.smartInsert(doc.getText(), selMin, selMax, ch, language);
        replaceRangeWithCaret(re.start, re.end, re.text, re.caret);
        // Detect divergence from the literal "type this char" semantics the IME expects.
        boolean diverged = re.start != selMin || re.end != selMax
            || !re.text.equals(String.valueOf(ch)) || re.caret != selMin + 1;
        if (diverged && imeListener != null && !imeListener.isSyncingExtractedText()) {
            // Skip-over closer (no text change) needs the restart even with
            // an extracted-text monitor — no extracted-text push follows.
            boolean skipOverNoText = re.start == re.end && re.text.isEmpty();
            if (skipOverNoText || !imeListener.isSyncingExtractedText()) {
                imeListener.onRestartInput();
            }
        }
    }

    /**
     * Smart backspace with empty-pair deletion, blank-line collapse, smart indent.
     */
    public void backspace() {
        backspace(false);
    }

    /**
     * Backspace with optional word-level deletion.
     */
    public void backspace(boolean word) {
        if (word) {
            int pos = EditOps.wordBoundaryLeft(doc.getText(), selection.start);
            replaceRangeWithCaret(pos, selection.start, "", pos);
            return;
        }
        int selMin = Math.min(selection.start, selection.end);
        int selMax = Math.max(selection.start, selection.end);
        RangeEdit re = EditOps.smartBackspace(doc.getText(), selMin, selMax, language);
        replaceRangeWithCaret(re.start, re.end, re.text, re.caret);
        // Detect divergence from the literal "delete one char before caret"
        // semantics the IME expects. The smart rules may have removed an
        // empty pair (2 chars), collapsed a blank line (1 char + indent),
        // or dedented (4 spaces).
        boolean diverged = re.start != Math.max(0, selMin - 1)
            || re.end != selMax || !re.text.isEmpty();
        if (diverged && imeListener != null && !imeListener.isSyncingExtractedText()) {
            imeListener.onRestartInput();
        }
    }

    /**
     * Forward delete with pair-awareness.
     */
    public void deleteForward() {
        deleteForward(false);
    }

    /**
     * Forward delete with optional word-level deletion.
     */
    public void deleteForward(boolean word) {
        if (word) {
            int pos = EditOps.wordBoundaryRight(doc.getText(), selection.start);
            replaceRangeWithCaret(selection.start, pos, "", selection.start);
            return;
        }
        RangeEdit re = EditOps.smartDeleteForward(doc.getText(), selection.start, selection.end, language);
        replaceRangeWithCaret(re.start, re.end, re.text, re.caret);
    }

    // ── Indent / Dedent ───────────────────────────────────────────

    public void indent() {
        int startLine = doc.lineForOffset(selection.start);
        int endLine = doc.lineForOffset(selection.end);
        // If the selection ends exactly at a line start (and not on the same
        // line as the start), exclude that next line — it's not really selected.
        if (selection.end != selection.start
            && selection.end == doc.lineStart(endLine)
            && endLine > startLine) {
            endLine--;
        }
        if (endLine < startLine) return;
        indentLines(startLine, endLine, true);
    }

    public void dedent() {
        int startLine = doc.lineForOffset(selection.start);
        int endLine = doc.lineForOffset(selection.end);
        if (selection.end != selection.start
            && selection.end == doc.lineStart(endLine)
            && endLine > startLine) {
            endLine--;
        }
        if (endLine < startLine) return;
        indentLines(startLine, endLine, false);
    }

    private void indentLines(int startLine, int endLine, boolean indent) {
        String indentUnit = EditOps.detectIndentUnit(doc.getText());
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            String line = doc.lineText(i);
            if (i > startLine) sb.append('\n');
            if (indent) {
                sb.append(indentUnit).append(line);
            } else {
                if (line.startsWith(indentUnit)) {
                    sb.append(line.substring(indentUnit.length()));
                } else {
                    int spaces = 0;
                    int maxRemove = indentUnit.length();
                    while (spaces < line.length() && spaces < maxRemove && line.charAt(spaces) == ' ') {
                        spaces++;
                    }
                    sb.append(line.substring(spaces));
                }
            }
        }
        int rangeStart = doc.lineStart(startLine);
        int rangeEnd = doc.lineEnd(endLine);
        replaceRange(rangeStart, rangeEnd, sb.toString());
    }

    // ── Comment toggling ──────────────────────────────────────────

    /**
     * v3.35.0 (roadmap item 1 / bug B11) — the effective comment syntax.
     * Explicit {@link #setCommentSyntax(CommentSyntax)} override wins,
     * otherwise the syntax is resolved from the current language id via
     * {@link CommentSyntax#forLanguage(String)} (Java/C-family default
     * preserved byte for byte).
     */
    public CommentSyntax getCommentSyntax() {
        CommentSyntax override = commentSyntaxOverride;
        if (override != null) return override;
        return CommentSyntax.forLanguage(language);
    }

    /**
     * Overrides the comment syntax derived from the language id — for hosts
     * editing languages {@link CommentSyntax#forLanguage(String)} doesn't
     * know. Pass {@code null} to go back to language-driven resolution.
     */
    public void setCommentSyntax(CommentSyntax syntax) {
        this.commentSyntaxOverride = syntax;
    }

    /**
     * Computes the [startLine, endLine] range of lines the comment toggle
     * applies to, from the current selection. A selection end sitting
     * exactly at a line start does NOT include that still-empty line.
     * Returns null when the range is empty.
     */
    private int[] commentLineRange() {
        int startLine = doc.lineForOffset(selection.start);
        int endLine = doc.lineForOffset(selection.end);
        // If the selection end sits exactly at a line start, don't include that
        // (still-empty) line in the range — it's the start of the next line, not
        // the end of the current one.
        if (selection.end != selection.start
            && selection.end == doc.lineStart(endLine)
            && endLine > startLine) {
            endLine--;
        }
        if (endLine < startLine) return null;
        return new int[]{startLine, endLine};
    }

    /**
     * Toggles line comments over the selection, using the CURRENT language's
     * comment syntax.
     *
     * <p>v3.35.0 (bug B11): before this release the prefix was hardcoded to
     * {@code "//"} — wrong for Python ({@code #}), Lua ({@code --}), SQL
     * ({@code --}), XML/HTML/Markdown (no line comment) and JSON (no comment
     * at all). Resolution now goes through {@link #getCommentSyntax()}:</p>
     * <ul>
     *   <li>languages WITH a line comment (Java, Python, Lua, SQL…) toggle
     *       that prefix exactly like the old C-family behavior (insert
     *       {@code prefix + ' '}, strip prefix + one optional space);</li>
     *   <li>languages with ONLY a block pair (XML/HTML/Markdown) fall back to
     *       wrapping each line in {@code blockStart … blockEnd} — the VS Code
     *       behavior for XML;</li>
     *   <li>languages with NO comment syntax (JSON) are a documented no-op.</li>
     * </ul>
     */
    public void toggleLineComment() {
        CommentSyntax syntax = getCommentSyntax();
        if (syntax.hasLine()) {
            int[] range = commentLineRange();
            if (range != null) toggleLineCommentWithPrefix(syntax.lineComment, range[0], range[1]);
        } else if (syntax.hasBlock()) {
            int[] range = commentLineRange();
            if (range != null) toggleLineCommentWithBlockPair(syntax.blockStart, syntax.blockEnd, range[0], range[1]);
        }
        // No line AND no block comment (JSON): documented no-op.
    }

    /**
     * Legacy C-family behavior, parameterized by the comment prefix.
     * A line is considered "commented" only if it has non-whitespace content
     * starting with the prefix. Pure blank lines are ignored when deciding
     * whether to add or remove comments, but they still receive the prefix
     * when commenting (to keep the block consistent).
     */
    private void toggleLineCommentWithPrefix(String commentPrefix, int startLine, int endLine) {
        boolean allCommented = true;
        for (int i = startLine; i <= endLine; i++) {
            String trimmed = doc.lineText(i).trim();
            if (trimmed.isEmpty()) continue;            // ignore blank lines
            if (!trimmed.startsWith(commentPrefix)) {
                allCommented = false;
                break;
            }
        }
        // If every non-blank line was commented, we uncomment.
        // Edge case: if there are no non-blank lines, default to commenting.
        boolean hasNonBlank = false;
        for (int i = startLine; i <= endLine; i++) {
            if (!doc.lineText(i).trim().isEmpty()) { hasNonBlank = true; break; }
        }
        if (!hasNonBlank) allCommented = false;

        int prefixLen = commentPrefix.length();
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            String line = doc.lineText(i);
            if (i > startLine) sb.append('\n');
            if (allCommented) {
                int idx = line.indexOf(commentPrefix);
                if (idx >= 0) {
                    if (idx + prefixLen < line.length() && line.charAt(idx + prefixLen) == ' ') {
                        sb.append(line, 0, idx).append(line.substring(idx + prefixLen + 1));
                    } else {
                        sb.append(line, 0, idx).append(line.substring(idx + prefixLen));
                    }
                } else {
                    sb.append(line);
                }
            } else {
                sb.append(commentPrefix).append(' ').append(line);
            }
        }
        int rangeStart = doc.lineStart(startLine);
        int rangeEnd = doc.lineEnd(endLine);
        replaceRange(rangeStart, rangeEnd, sb.toString());
        // v3.35.0: restore a selection covering the WHOLE transformed block.
        // replaceRange() collapses the selection to a caret, which made the
        // multi-line round-trip impossible — the second toggle only saw the
        // caret's line and uncommented it alone. Pinning the selection on
        // the replaced range lets the next toggle see every line again.
        int selEnd = Math.min(rangeStart + sb.length(), doc.length());
        setSelection(Selection.range(rangeStart, Math.max(rangeStart, selEnd)));
    }

    /**
     * v3.35.0 — VS Code-style fallback for line-less syntaxes (XML, HTML,
     * Markdown): each line gets wrapped in {@code blockStart … blockEnd}.
     * Uncommenting strips the pair plus the (optional) spaces glued to it.
     */
    private void toggleLineCommentWithBlockPair(String blockStart, String blockEnd, int startLine, int endLine) {
        boolean allCommented = true;
        for (int i = startLine; i <= endLine; i++) {
            String trimmed = doc.lineText(i).trim();
            if (trimmed.isEmpty()) continue;            // ignore blank lines
            if (!(trimmed.startsWith(blockStart) && trimmed.endsWith(blockEnd))) {
                allCommented = false;
                break;
            }
        }
        boolean hasNonBlank = false;
        for (int i = startLine; i <= endLine; i++) {
            if (!doc.lineText(i).trim().isEmpty()) { hasNonBlank = true; break; }
        }
        if (!hasNonBlank) allCommented = false;

        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            String line = doc.lineText(i);
            if (i > startLine) sb.append('\n');
            if (allCommented) {
                // Strip the closer (right side) FIRST so removing it can't
                // shift the opener's index (the opener is always to its left).
                String cur = line;
                int endIdx = cur.lastIndexOf(blockEnd);
                if (endIdx >= 0) {
                    int cutEnd = endIdx;
                    if (cutEnd > 0 && cur.charAt(cutEnd - 1) == ' ') cutEnd--;
                    cur = cur.substring(0, cutEnd) + cur.substring(endIdx + blockEnd.length());
                }
                int startIdx = cur.indexOf(blockStart);
                if (startIdx >= 0) {
                    int after = startIdx + blockStart.length();
                    if (after < cur.length() && cur.charAt(after) == ' ') after++;
                    cur = cur.substring(0, startIdx) + cur.substring(after);
                }
                sb.append(cur);
            } else if (line.trim().isEmpty()) {
                // Blank line: keep it minimal — just the pair, no inner spaces.
                sb.append(blockStart).append(blockEnd);
            } else {
                sb.append(blockStart).append(' ').append(line).append(' ').append(blockEnd);
            }
        }
        int rangeStart = doc.lineStart(startLine);
        int rangeEnd = doc.lineEnd(endLine);
        replaceRange(rangeStart, rangeEnd, sb.toString());
        // v3.35.0: same multi-line round-trip fix as the prefix variant —
        // keep the selection on the whole transformed block.
        int selEnd = Math.min(rangeStart + sb.length(), doc.length());
        setSelection(Selection.range(rangeStart, Math.max(rangeStart, selEnd)));
    }

    /**
     * Toggles a block comment around the selection, using the CURRENT
     * language's block pair.
     *
     * <p>v3.35.0 (bug B11): the pair was hardcoded to {@code /* … *&#47;}
     * before; it now comes from {@link #getCommentSyntax()} (XML gets
     * {@code <!-- … -->}, Lua {@code --[[ … ]]}…). Languages without a block
     * comment (Python, JSON, shell…) are a documented no-op — previously
     * they got C-style garbage inserted.</p>
     */
    public void toggleBlockComment() {
        CommentSyntax syntax = getCommentSyntax();
        if (!syntax.hasBlock()) return;
        String blockStart = syntax.blockStart;
        String blockEnd = syntax.blockEnd;
        int start = selection.start;
        int end = selection.end;
        String text = doc.getText();

        // Check if the selection (or the area immediately surrounding it) is
        // already wrapped in blockStart … blockEnd. We look at the chars just
        // BEFORE start and just AT end so the test works even when start == 0
        // or end == len.
        boolean alreadyWrapped =
            start >= blockStart.length()
            && end + blockEnd.length() <= text.length()
            && text.regionMatches(start - blockStart.length(), blockStart, 0, blockStart.length())
            && text.regionMatches(end, blockEnd, 0, blockEnd.length());

        // Group the (possibly two) edits into a single undo step.
        beginBatch();
        try {
            if (alreadyWrapped) {
                // Remove the closer (right side) first, then the opener (at
                // the left), so the left-side offset stays valid.
                // v3.35.0: an optional SPACE glued to a delimiter is removed
                // with it — the wrap inserts the pair with readability
                // spaces, and the old unwrap left them behind ("  content  ").
                int closerEnd = end + blockEnd.length();
                int closerStart = end;
                if (closerStart > start && text.charAt(closerStart - 1) == ' ') {
                    closerStart--;
                }
                int openerStart = start - blockStart.length();
                int openerEnd = start;
                if (openerEnd < closerStart && text.charAt(openerEnd) == ' ') {
                    openerEnd++;
                }
                int innerLen = closerStart - openerEnd;
                replaceRange(closerStart, closerEnd, "");
                replaceRange(openerStart, openerEnd, "");
                // v3.35.0: restore the selection on the uncommented content.
                int selEnd = Math.min(openerStart + Math.max(0, innerLen), doc.length());
                setSelection(Selection.range(Math.max(0, openerStart), Math.max(0, selEnd)));
            } else {
                // Insert the closer at the right first, then the opener at the left.
                replaceRange(end, end, " " + blockEnd);
                replaceRange(start, start, blockStart + " ");
                // v3.35.0: pin the selection right AFTER the opener and right
                // BEFORE the closer (+ the two inserted spaces) so the next
                // toggle detects the pair and unwraps exactly this block —
                // replaceRange() had collapsed the selection to a caret,
                // making comment→uncomment round-trips impossible.
                int selStart = Math.min(start + blockStart.length(), doc.length());
                int selEnd2 = Math.min(end + blockStart.length() + 2, doc.length());
                setSelection(Selection.range(selStart, Math.max(selStart, selEnd2)));
            }
        } finally {
            endBatch();
        }
    }

    // ── Selection operations ──────────────────────────────────────

    public void selectAll() {
        selection = Selection.range(0, doc.length());
    }

    public void selectWordAt(int offset) {
        int[] range = EditOps.wordRangeAt(doc.getText(), offset);
        selection = Selection.range(range[0], range[1]);
    }

    public void selectLineAt(int offset) {
        int line = doc.lineForOffset(offset);
        int start = doc.lineStart(line);
        int end = doc.lineEnd(line);
        selection = Selection.range(start, end);
    }

    // ── Cursor movement ───────────────────────────────────────────

    public void moveHorizontal(int delta, boolean selecting) {
        int newPos = Math.max(0, Math.min(doc.length(), selection.start + delta));
        if (selecting) {
            int anchor = selection.isCursor() ? selection.start : (delta > 0 ? selection.start : selection.end);
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    public void moveVertical(int lines, boolean selecting) {
        int currentLine = doc.lineForOffset(selection.start);
        int currentCol = selection.start - doc.lineStart(currentLine);
        int targetLine = Math.max(0, Math.min(doc.lineCount() - 1, currentLine + lines));
        int targetLineStart = doc.lineStart(targetLine);
        int targetLineEnd = doc.lineEnd(targetLine);
        int newPos = Math.min(targetLineStart + currentCol, targetLineEnd);

        if (selecting) {
            int anchor = selection.start;
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    public void moveLineStart(boolean selecting) {
        int line = doc.lineForOffset(selection.start);
        int lineStart = doc.lineStart(line);
        String lineText = doc.lineText(line);
        // Smart line start: go to first non-whitespace, or line start if already there
        int firstNonWs = lineStart;
        for (int i = 0; i < lineText.length(); i++) {
            if (!Character.isWhitespace(lineText.charAt(i))) {
                firstNonWs = lineStart + i;
                break;
            }
        }
        int newPos = (selection.start == firstNonWs) ? lineStart : firstNonWs;

        if (selecting) {
            int anchor = selection.start;
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    public void moveLineEnd(boolean selecting) {
        int line = doc.lineForOffset(selection.start);
        int newPos = doc.lineEnd(line);
        if (selecting) {
            int anchor = selection.start;
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    public void moveDocBoundary(boolean toEnd, boolean selecting) {
        int newPos = toEnd ? doc.length() : 0;
        if (selecting) {
            int anchor = selection.start;
            selection = Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos));
        } else {
            selection = Selection.cursor(newPos);
        }
    }

    // ── Line operations ───────────────────────────────────────────

    public void duplicateSelection() {
        if (selection.isCursor()) {
            duplicateLine();
            return;
        }
        String selected = doc.getText().substring(selection.start, selection.end);
        replaceRange(selection.end, selection.end, selected);
    }

    public void duplicateLine() {
        int startLine = doc.lineForOffset(selection.start);
        int endLine = doc.lineForOffset(selection.end);
        int insertPos = doc.lineEnd(endLine);
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        for (int i = startLine; i <= endLine; i++) {
            if (i > startLine) sb.append('\n');
            sb.append(doc.lineText(i));
        }
        replaceRange(insertPos, insertPos, sb.toString());
    }

    public void moveLines(boolean down) {
        if (down) {
            moveLineDown();
        } else {
            moveLineUp();
        }
    }

    public void deleteLines() {
        int startLine = doc.lineForOffset(selection.start);
        int endLine = doc.lineForOffset(selection.end);
        int rangeStart = doc.lineStart(startLine);
        int rangeEnd = doc.lineEnd(endLine);
        if (rangeEnd < doc.length()) {
            rangeEnd++;
        } else if (rangeStart > 0) {
            rangeStart--;
        }
        replaceRange(rangeStart, rangeEnd, "");
    }

    public void moveLineUp() {
        int line = doc.lineForOffset(selection.start);
        if (line <= 0) return;
        String currentLine = doc.lineText(line);
        String prevLine = doc.lineText(line - 1);
        int prevStart = doc.lineStart(line - 1);
        int currEnd = doc.lineEnd(line);
        replaceRange(prevStart, currEnd, currentLine + "\n" + prevLine);
    }

    public void moveLineDown() {
        int line = doc.lineForOffset(selection.start);
        if (line >= doc.lineCount() - 1) return;
        String currentLine = doc.lineText(line);
        String nextLine = doc.lineText(line + 1);
        int currStart = doc.lineStart(line);
        int nextEnd = doc.lineEnd(line + 1);
        replaceRange(currStart, nextEnd, nextLine + "\n" + currentLine);
    }

    public void joinLines() {
        int line = doc.lineForOffset(selection.start);
        if (line >= doc.lineCount() - 1) return;
        int lineEnd = doc.lineEnd(line);
        int nextEnd = doc.lineEnd(line + 1);
        String nextLine = doc.lineText(line + 1);
        replaceRange(lineEnd, Math.min(nextEnd, doc.length()), " " + nextLine.trim());
    }

    // ── Clipboard operations ──────────────────────────────────────

    public String selectedText() {
        if (selection.isCursor()) return "";
        return doc.getText().substring(selection.start, selection.end);
    }

    public String cutSelection() {
        String text = selectedText();
        if (!text.isEmpty()) {
            replaceRange(selection.start, selection.end, "");
        }
        return text;
    }

    // ── Diagnostics navigation ────────────────────────────────────

    public void goToDiagnostic(boolean forward) {
        if (diagnostics.isEmpty()) return;
        int pos = selection.start;
        DiagnosticShift.Diagnostic best = null;

        if (forward) {
            for (DiagnosticShift.Diagnostic d : diagnostics) {
                if (d.start > pos && (best == null || d.start < best.start)) {
                    best = d;
                }
            }
            if (best == null) best = diagnostics.get(0); // wrap
        } else {
            for (DiagnosticShift.Diagnostic d : diagnostics) {
                if (d.start < pos && (best == null || d.start > best.start)) {
                    best = d;
                }
            }
            if (best == null) best = diagnostics.get(diagnostics.size() - 1); // wrap
        }

        selection = Selection.cursor(best.start);
    }

    // ── Delete surrounding ────────────────────────────────────────

    public void deleteSurrounding(int beforeLength, int afterLength) {
        int start = selection.start - beforeLength;
        int end = selection.end + afterLength;
        start = Math.max(0, start);
        end = Math.min(doc.length(), end);
        if (start < end) replaceRange(start, end, "");
    }

    // ── Undo / Redo ───────────────────────────────────────────────

    public boolean undo() {
        UndoStep step = undoManager.undo();
        if (step == null) return false;
        for (int i = step.edits.size() - 1; i >= 0; i--) {
            EditOp op = step.edits.get(i);
            EditOp inv = op.inverse();
            doc = doc.replace(inv.start, inv.start + inv.removed.length(), inv.inserted);
        }
        selection = Selection.cursor(step.selBefore);
        // ★ v2.55 — Async restyle pour undo. Avant, restyleAll() synchro
        // pouvait bloquer l'UI 200 ms+ sur un gros fichier lors d'un Ctrl+Z.
        // Maintenant async : le doc snapshot est le nouveau doc (post-undo)
        // car la mutation doc = doc.replace(...) est faite juste au-dessus,
        // donc docAtStart capturé dans restyleAllAsync == ce nouveau doc,
        // et le check doc != docAtStart renvoie false → le restyle n'est
        // pas droppé.
        restyleAllAsync();
        // v1.0.8 bugfix (Bug 2): notify the IME listener so the view knows
        // the caret moved and the text changed — without this, undo/redo
        // left the caret frozen (no blink restart, no scroll-into-view).
        if (imeListener != null) {
            imeListener.onTextChanged(new EditSpan(0, 0, 0));
            imeListener.onSelectionChanged(
                selection.start, selection.end, composingStart, composingEnd);
        }
        return true;
    }

    public boolean redo() {
        UndoStep step = undoManager.redo();
        if (step == null) return false;
        for (EditOp op : step.edits) {
            doc = doc.replace(op.start, op.start + op.removed.length(), op.inserted);
        }
        selection = Selection.cursor(step.selAfter);
        // ★ v2.55 — Async restyle pour redo (même logique que undo).
        restyleAllAsync();
        // v1.0.8 bugfix (Bug 2): same as undo — notify the listener.
        if (imeListener != null) {
            imeListener.onTextChanged(new EditSpan(0, 0, 0));
            imeListener.onSelectionChanged(
                selection.start, selection.end, composingStart, composingEnd);
        }
        return true;
    }

    // ── IME bridge ────────────────────────────────────────────────

    /**
     * IME commits text (replaces composing region or inserts at cursor).
     * <p>
     * Handles SwiftKey / Gboard auto-space after punctuation in BOTH shapes:
     * <ul>
     *   <li><b>Bundled</b> — the IME commits {@code "p "} as one string with
     *       {@code newCursorPosition == 1}. We strip the trailing space and
     *       force a {@code restartInput}.</li>
     *   <li><b>Split</b> — the IME commits {@code "p"} then a separate
     *       {@code " "} in the same batch. We swallow the bare-space commit
     *       when it follows a symbol in the same batch.</li>
     * </ul>
     * Without this, typing {@code foo() ;} becomes {@code foo() ;} (with the
     * auto-space), which is wrong for code.
     */
    public void imeCommitText(String text) {
        if (text == null || text.isEmpty()) {
            // Empty commit = IME confirming the end of composing. Just clear.
            composingStart = -1;
            composingEnd = -1;
            return;
        }
        // Detect bundled auto-space: "p " with the cursor at position 1
        // (the IME expects the caret to land BEFORE the space, so the user
        // can keep typing). The trailing space is the keyboard's auto-space
        // after a punctuation symbol.
        if (text.length() >= 2 && text.charAt(text.length() - 1) == ' '
            && isAutoSpacedSymbol(text.charAt(text.length() - 2))) {
            String stripped = text.substring(0, text.length() - 1);
            int commitStart, commitEnd;
            if (composingStart >= 0 && composingEnd > composingStart) {
                commitStart = composingStart;
                commitEnd = composingEnd;
                composingStart = -1;
                composingEnd = -1;
            } else {
                commitStart = selection.start;
                commitEnd = selection.end;
            }
            replaceRange(commitStart, commitEnd, stripped);
            // The IME's model still has the phantom space — force a restart.
            if (imeListener != null && !imeListener.isSyncingExtractedText()) {
                imeListener.onRestartInput();
            }
            return;
        }
        // Detect split auto-space: a bare " " committed right after a symbol
        // commit in the same batch. Track the last symbol-commit offset so we
        // can detect this on the next commit.
        if (text.equals(" ") && batchImeSymbolCommitOffset >= 0) {
            // This is the keyboard's auto-space after a symbol — swallow it.
            batchImeSymbolCommitOffset = -1;
            if (imeListener != null && !imeListener.isSyncingExtractedText()) {
                imeListener.onRestartInput();
            }
            return;
        }
        // Track symbol commits so the next bare-space commit can be detected
        // as the keyboard's auto-space.
        if (text.length() == 1 && isAutoSpacedSymbol(text.charAt(0))) {
            batchImeSymbolCommitOffset = selection.start;
        } else {
            batchImeSymbolCommitOffset = -1;
        }
        if (composingStart >= 0 && composingEnd > composingStart) {
            // Replace composing region
            int commitStart = composingStart;
            int commitEnd = composingEnd;
            composingStart = -1;
            composingEnd = -1;
            replaceRange(commitStart, commitEnd, text);
        } else {
            replaceRange(selection.start, selection.end, text);
        }
    }

    /** Last offset where a symbol was committed in this batch (for split auto-space detection). */
    private int batchImeSymbolCommitOffset = -1;

    /** Symbols that SwiftKey/Gboard auto-space after. */
    private static boolean isAutoSpacedSymbol(char c) {
        return c == ')' || c == ']' || c == '}' || c == ';'
            || c == ',' || c == '.' || c == '!' || c == '?'
            || c == ':' || c == '%';
    }

    /**
     * IME sets composing text (shown as inline preview).
     * <p>
     * Always REPLACES the existing composing region (or the current
     * selection if no composing region is set yet). Appending instead of
     * replacing breaks the IME's "I sent you a new composing word, replace
     * the old one" contract — typing "hello" then backspace would produce
     * "hellohell" because the IME's delete targets the previous word.
     */
    public void imeSetComposingText(String text, int newCursorPosition) {
        int regionStart, regionEnd;
        if (composingStart >= 0) {
            regionStart = composingStart;
            regionEnd = composingEnd;
        } else {
            // First composing call — replace the current selection so the
            // composing word replaces whatever was selected.
            regionStart = selection.start;
            regionEnd = selection.end;
        }
        // Splice the new composing text in.
        int insertionLen = text.length();
        // Use a direct mutation so we don't recursively fire onRestartInput
        // for our own composing edits.
        doReplaceRange(regionStart, regionEnd, text, regionStart + insertionLen);
        // Update the composing region to the new text range.
        composingStart = regionStart;
        composingEnd = regionStart + insertionLen;
        // Compute caret per IME contract:
        //   newCaretPos > 0  → relative to END of new composing text
        //   newCaretPos <= 0 → relative to START of new composing text
        // (with newCaretPos == 1 → immediately after the inserted text)
        int caretPos;
        if (newCursorPosition > 0) {
            caretPos = composingEnd + (newCursorPosition - 1);
        } else {
            caretPos = composingStart + newCursorPosition;
        }
        caretPos = Math.max(0, Math.min(doc.length(), caretPos));
        selection = Selection.cursor(caretPos);
        notifySelectionChanged();
    }

    /**
     * IME sets the composing region.
     */
    public void imeSetComposingRegion(int start, int end) {
        if (start >= end) {
            composingStart = -1;
            composingEnd = -1;
        } else {
            composingStart = Math.max(0, Math.min(start, doc.length()));
            composingEnd = Math.max(composingStart, Math.min(end, doc.length()));
        }
        notifySelectionChanged();
    }

    /**
     * IME finishes composing (commits the composing text as final).
     */
    public void imeFinishComposing() {
        composingStart = -1;
        composingEnd = -1;
        notifySelectionChanged();
    }

    /**
     * IME deletes surrounding text relative to cursor.
     * <p>
     * Whitespace deletes stay LITERAL — SwiftKey's "no space before
     * punctuation" swap is byte-identical to a user backspace tap, and
     * the smart-backspace rules would eat real code on every swap (the
     * "typing ) deletes my indent" bug). The smart rules apply only
     * through {@link #backspace()}.
     */
    public void imeDeleteSurrounding(int beforeLength, int afterLength) {
        int selStart = selection.start;
        int selEnd = selection.end;
        int delStart = Math.max(0, selStart - beforeLength);
        int delEnd = Math.min(doc.length(), selEnd + afterLength);
        if (delEnd > delStart) {
            replaceRange(delStart, delEnd, "");
        }
    }

    /**
     * IME sets the selection (absolute offsets). Clamped to the document.
     */
    public void imeSetSelection(int start, int end) {
        start = Math.max(0, Math.min(start, doc.length()));
        end = Math.max(0, Math.min(end, doc.length()));
        if (start == end) {
            selection = Selection.cursor(start);
        } else {
            selection = Selection.range(Math.min(start, end), Math.max(start, end));
        }
        notifySelectionChanged();
    }

    /**
     * IME replaces an absolute range (API 34+ {@code replaceText}). Used by
     * some IMEs for retro-correction. Routes through {@link #replaceRange}
     * so undo, style splice, and diagnostics shift all stay consistent.
     */
    public void imeReplaceText(int start, int end, String text, int newCursorPosition) {
        int regionStart = Math.max(0, Math.min(start, doc.length()));
        int regionEnd = Math.max(regionStart, Math.min(end, doc.length()));
        int newCaret;
        if (newCursorPosition > 0) {
            newCaret = regionStart + text.length() + (newCursorPosition - 1);
        } else {
            newCaret = regionStart + newCursorPosition;
        }
        newCaret = Math.max(0, Math.min(doc.length() + text.length(), newCaret));
        replaceRangeWithCaret(regionStart, regionEnd, text, newCaret);
    }

    /**
     * Returns up to {@code n} chars before the caret. Used by IMEs that
     * read context for prediction. Windowed to MAX_IPC_TEXT to stay
     * Binder-safe.
     */
    public String imeTextBeforeCursor(int n) {
        int end = selection.start;
        int start = Math.max(0, end - Math.min(n, MAX_IPC_TEXT));
        return doc.getText().substring(start, end);
    }

    /**
     * Returns up to {@code n} chars after the caret. Used by IMEs that
     * read context for prediction. Windowed to MAX_IPC_TEXT to stay
     * Binder-safe.
     */
    public String imeTextAfterCursor(int n) {
        int start = selection.end;
        int end = Math.min(doc.length(), start + Math.min(n, MAX_IPC_TEXT));
        return doc.getText().substring(start, end);
    }

    /**
     * Returns the current composing region, or null if not composing.
     */
    public int[] getComposingRegion() {
        if (composingStart < 0) return null;
        return new int[]{composingStart, composingEnd};
    }

    /**
     * Returns true if the IME is in composing state.
     */
    public boolean isComposing() {
        return composingStart >= 0;
    }

    /** Cap on the size of any text payload pushed across the IME binder. */
    private static final int MAX_IPC_TEXT = 4096;

    // ── v3.34.0 — Lifecycle disposal ─────────────────────────────

    /**
     * v3.34.0 — Releases the session's background resources.
     *
     * <p>Before v3.34.0, every {@code EditorSession} spawned a single-thread
     * executor ({@link #restyleExecutor}) that was NEVER shut down: an app
     * opening one editor per file tab leaked one thread (and its pending
     * task queue) per session for the lifetime of the process. The threads
     * were daemons so the JVM still exited, but on Android each leaked
     * thread kept its session's document rope strongly reachable — a
     * slow, invisible memory leak for long editing sessions.
     *
     * <p>Call this when the editor is destroyed for good (typically from
     * {@code EditorView.onDetachedFromWindow} after a
     * {@code onAttachStateChangeListener#onViewDetachedFromWindow} that is
     * NOT followed by a re-attach — the built-in {@code EditorView} calls
     * it for you). After {@code dispose()}: pending async restyles are
     * cancelled, the executor is shut down, and any later
     * {@code setLanguage}/undo/redo falls back to the SYNCHRONOUS
     * {@link #restyleAll()} path (correct, just blocking). Text edits
     * remain fully functional.
     */
    public void dispose() {
        Future<?> prev = pendingRestyle.getAndSet(null);
        if (prev != null) {
            prev.cancel(true);
        }
        restyleExecutor.shutdownNow();
        disposed = true;
    }

    /** v3.34.0 — Whether {@link #dispose()} has been called. */
    public boolean isDisposed() {
        return disposed;
    }

    /** v3.34.0 — Set by dispose(); makes later restyle requests synchronous. */
    private volatile boolean disposed = false;
}
