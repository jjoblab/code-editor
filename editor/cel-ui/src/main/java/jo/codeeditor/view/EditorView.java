package jo.codeeditor.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.find.Match;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.PopupCoordinator;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;

/**
 * Android custom View for code editing with Canvas rendering.
 *
 * <p>Architecture follows the CodeAssist reference editor:
 * <ul>
 *   <li><b>IME bridge</b> — explicit {@code wantsKeyboard} flag set only by a
 *       user tap; {@code onCreateInputConnection} configures
 *       {@code EditorInfo} with the right inputType/imeOptions/initialSel
 *       and returns an {@link EditorImeBridge.EditorInputConnection} that overrides every
 *       text-operation method, including {@code replaceText} (API 34),
 *       {@code closeConnection} (generation-guarded), {@code getExtractedText}
 *       (with monitor arming), and {@code beginBatchEdit}/{@code endBatchEdit}
 *       (mapped to {@link EditorSession#beginBatch()}/{@link EditorSession#endBatch()}).</li>
 *   <li><b>Touch</b> — single-finger scroll + drag-select with slop
 *       disambiguation, long-press word select, double/triple tap word/line
 *       select, pinch zoom on a separate {@link ScaleGestureDetector}.</li>
 *   <li><b>Scroll</b> — vertical + horizontal with clamping to
 *       {@code [0, maxV()]} and {@code [0, maxH()]}, momentum fling via
 *       {@link OverScroller}, auto-scroll caret into view on every edit.</li>
 *   <li><b>Render</b> — single Canvas pass: background → current-line band
 *       → gutter → selection → text → squiggles → indent guides → caret.
 *       All offsets clamped; the selection fill is wrapped in
 *       {@code try/catch} so a one-frame stale offset can never crash the
 *       whole editor.</li>
 *   <li><b>Caret</b> — 2px accent bar; blink goes solid on every edit /
 *       caret move and only resumes after 530ms of inactivity.</li>
 *   <li><b>Hardware keys</b> — full {@code onKeyDown} override for backspace,
 *       arrows, Enter, Tab, Home/End, PageUp/Down, Ctrl+Z/Y/A/C/V/X, Ctrl±/0
 *       zoom.</li>
 *   <li><b>Clipboard</b> — {@link #copy()}, {@link #cut()}, {@link #paste()}
 *       via {@link ClipboardManager}.</li>
 * </ul>
 
 *
 * @since v1.0.0
*/
public class EditorView extends View {

    EditorSession session;
    final EditorMetrics metrics;
    EditorTheme theme;
    final GutterView gutterView;

    // ── Scroll state ──────────────────────────────────────────────
    float vOffset = 0;
    float hOffset = 0;

    // ── Zoom ───────────────────────────────────────────────────────
    float fontScale = 1.0f;
    private static final float MIN_FONT_SCALE = 0.6f;
    private static final float MAX_FONT_SCALE = 2.6f;
    static final float BASE_TEXT_SIZE_SP = 14f;

    // ── Find highlights (visual decoration) ───────────────────────
    // Set by the host (e.g. Find/Replace bar) — every match in the
    // viewport is tinted theme.findMatch, the current one theme.findCurrent.
    final List<Match> findHighlights = new ArrayList<>();
    int findCurrentIndex = -1;

    // ── Completion popup ───────────────────────────────────────────
    // Simple keyword-based completion built into the view. The host can
    // also drive a richer CompletionController externally and feed items
    // via setCompletionItems(items, tokenStart, prefix).
    final List<jo.codeeditor.completion.CompletionSession.Item> completionItems = new ArrayList<>();
    int completionSelected = 0;
    int completionScrollOffset = 0;
    int completionTokenStart = -1;
    String completionPrefix = "";
    boolean completionVisible = false;
    // v3.3.5: Client-side cache for completion filtering.
    // When the provider returns items for a token, we cache them as the
    // "base" set. On subsequent keystrokes that extend the same token,
    // we filter the cached set by prefix (case-insensitive + fuzzy)
    // instead of re-querying the provider. This is the CodeAssist pattern
    // — keeps the popup responsive and stable while a slow LSP server
    // catches up.
    List<jo.codeeditor.completion.CompletionSession.Item> completionBaseItems = new ArrayList<>();
    int completionBaseTokenStart = -1;
    static final int COMPLETION_MAX_ROWS = 8;
    static final float COMPLETION_ROW_HEIGHT_DP = 28f;
    static final float COMPLETION_WIDTH_DP = 280f;
    CompletionProvider completionProvider;

    // ── Word wrap ──────────────────────────────────────────────────
    // When enabled, long lines wrap across multiple visual rows inside the
    // text area. The wrap model caches per-line row counts and provides
    // O(log L) doc-line ↔ visual-row mapping via prefix sums.
    boolean wordWrap = false;
    jo.codeeditor.wrap.WrapModel wrapModel;
    int wrapWidthPx = 0;

    /**
     * ★ v2.33 — étendue horizontale (coords CONTENU hors-gutter) de la chip
     * diagnostic la plus à droite, mesurée par le draw pass
     * (pattern {@code chipExtent} de CodeAssist EditorGeometry) : une chip
     * qui déborde de sa ligne étend la largeur scrollable (maxH).
     * 0 = aucune chip dessinée ce frame.
     */
    float chipExtentContentX = 0f;

    /**
     * ★ v2.33 — géométrie de pliage d'UNE ligne en mode word-wrap (source
     * unique partagée par le rendu, le caret/tap, le scroll et les chips —
     * réplique exacte des règles de drawWrappedLine v3.31.1) : 1re rangée
     * pleine largeur à {@code textAreaLeft}, rangées de continuation plus
     * étroites indentées du leading-whitespace (cap à la demi-largeur).
     *
     * <p>AVANT : la formule de comptage ({@code ceil(len / maxCols)}) et le
     * découpage ({@code maxCols + (r-1)*colsPerCont}) DIVERGEAIENT dès que
     * l'indent de continuation > 0 — la queue de la ligne (jusqu à
     * {@code wrapIndentCols} caractères) n'était JAMAIS dessinée.</p>
     */
    static final class WrapRows {
        final int maxColsPerRow;   // capacité de la 1re rangée
        final int wrapIndentCols;  // indentation (cols) des rangées de continuation
        final int colsPerCont;     // capacité d'une rangée de continuation
        final int rows;            // nombre total de rangées (≥1)

        WrapRows(int maxColsPerRow, int wrapIndentCols, int colsPerCont, int rows) {
            this.maxColsPerRow = maxColsPerRow;
            this.wrapIndentCols = wrapIndentCols;
            this.colsPerCont = colsPerCont;
            this.rows = rows;
        }

        /** Colonne de début de la rangée {@code r} dans la ligne brute. */
        int rowStartCol(int r) {
            return r <= 0 ? 0 : maxColsPerRow + (r - 1) * colsPerCont;
        }

        /** Colonne de fin (EXCLUSIVE, bornée à lineLen) de la rangée {@code r}. */
        int rowEndCol(int r, int lineLen) {
            return Math.min(rowStartCol(r) + (r == 0 ? maxColsPerRow : colsPerCont),
                    lineLen);
        }

        /** La rangée (base 0) contenant la colonne {@code col}. */
        int rowForCol(int col) {
            if (col < maxColsPerRow) return 0;
            return 1 + (col - maxColsPerRow) / colsPerCont;
        }
    }

    /**
     * Calcule la géométrie de pliage d'une ligne (word-wrap). Hors wrap,
     * retourne une rangée unique pleine largeur.
     */
    WrapRows wrapRowsFor(int line, int lineLen) {
        if (!wordWrap || wrapModel == null || wrapWidthPx <= 0) {
            return new WrapRows(Integer.MAX_VALUE / 4, 0, Integer.MAX_VALUE / 4, 1);
        }
        float charWidth = metrics.getCharWidth();
        int maxColsPerRow = Math.max(1, (int) (wrapWidthPx / charWidth));
        String text = lineLen > 0 ? session.getDocument().lineText(line) : "";
        int leadingWs = 0;
        for (int i = 0; i < text.length() && leadingWs < lineLen; i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') leadingWs++;
            else break;
        }
        int maxIndent = Math.max(0, maxColsPerRow / 2 - 1);
        int wrapIndentCols = Math.min(leadingWs, maxIndent);
        int colsPerCont = Math.max(1, maxColsPerRow - wrapIndentCols);
        int rows = lineLen <= maxColsPerRow ? 1
                : 1 + (lineLen - maxColsPerRow + colsPerCont - 1) / colsPerCont;
        return new WrapRows(maxColsPerRow, wrapIndentCols, colsPerCont, rows);
    }

    // ── Per-line render cache (v1.0.7 — Gap 3) ────────────────────
    // Caches, per doc line, the StyledLine plus the per-line filtered
    // inlay pieces + sem spans + raw↔visual column maps. Validated by
    // a triple-stamp (text rev + inlay rev + sem rev) so a single
    // edit only invalidates the lines whose text actually changed —
    // not the whole viewport. LRU-evicted at 512 entries.
    final LineRenderCache renderCache = new LineRenderCache();

    // ── v3.35.0 — Fold prefix-sum index (roadmap item 3 / hotspot P3) ──
    // countHiddenLinesAbove() used to walk the WHOLE fold list (with a
    // lineForOffset binary search per region) on EVERY call — and the
    // draw path calls it once per visible line via docLineToY, making
    // drawing O(viewport × folds × log lines) with folds collapsed.
    // This index stores the collapsed folds as merged, sorted, parallel
    // arrays and answers hiddenAbove(line) / isHidden(line) in O(log folds).
    //
    // Cache key: (session identity, session.getFoldRevision(), doc
    // identity). A list-reference compare alone can NOT be used because
    // getFoldRegions() wraps a NEW unmodifiable view on every call and
    // toggleFoldAtLine()/expandFoldAt() mutate the backing list in place
    // — the revision counter bumped by every mutation is the only
    // reliable invalidation signal.
    private EditorSession foldIndexSession;
    private int foldIndexRev = -1;
    private EditorDocument foldIndexDoc;
    private FoldIndex foldIndexCache = FoldIndex.EMPTY;

    /**
     * v3.35.0 — merged/sorted collapsed-fold index with prefix sums.
     * Overlapping regions are MERGED (the pre-v3.35.0 code could
     * double-count them in countHiddenLinesAbove; the union is the
     * correct semantics and matches FoldModel.mergeRegions).
     */
    static final class FoldIndex {
        final int[] startLines;    // merged, sorted, non-overlapping
        final int[] endLines;      // (endLines[i] - startLines[i]) hidden lines each
        final int[] hiddenBefore;  // prefix sums of (end - start), size n+1
        final int[] startSum;      // prefix sums of startLines, size n+1

        static final FoldIndex EMPTY = new FoldIndex(
                new int[0], new int[0], new int[0], new int[0]);

        private FoldIndex(int[] startLines, int[] endLines,
                          int[] hiddenBefore, int[] startSum) {
            this.startLines = startLines;
            this.endLines = endLines;
            this.hiddenBefore = hiddenBefore;
            this.startSum = startSum;
        }

        static FoldIndex build(List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds,
                               EditorDocument doc) {
            int n = 0;
            for (jo.codeeditor.shift.DiagnosticShift.FoldRegion r : folds) {
                if (r.collapsed) n++;
            }
            if (n == 0) return EMPTY;
            int[] s = new int[n];
            int[] e = new int[n];
            int m = 0;
            for (jo.codeeditor.shift.DiagnosticShift.FoldRegion r : folds) {
                if (!r.collapsed) continue;
                s[m] = doc.lineForOffset(r.start);
                e[m] = doc.lineForOffset(r.end);
                if (e[m] < s[m]) { int t = s[m]; s[m] = e[m]; e[m] = t; }
                m++;
            }
            // Sort both parallel arrays by startLine (index sort — fold
            // counts are small, typically tens).
            Integer[] order = new Integer[m];
            for (int i = 0; i < m; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(s[a], s[b]));
            int[] ss = new int[m];
            int[] ee = new int[m];
            for (int i = 0; i < m; i++) { ss[i] = s[order[i]]; ee[i] = e[order[i]]; }
            // Merge overlapping regions (union — same semantics as
            // FoldModel.mergeRegions).
            int[] ms = new int[m];
            int[] me = new int[m];
            int k = 0;
            for (int i = 0; i < m; i++) {
                if (k > 0 && ss[i] <= me[k - 1]) {
                    if (ee[i] > me[k - 1]) me[k - 1] = ee[i];
                } else {
                    ms[k] = ss[i];
                    me[k] = ee[i];
                    k++;
                }
            }
            // Prefix sums: hiddenBefore[t] = Σ_{i<t} (me[i]-ms[i]);
            // startSum[t] = Σ_{i<t} ms[i].
            int[] hiddenBefore = new int[k + 1];
            int[] startSum = new int[k + 1];
            for (int i = 0; i < k; i++) {
                hiddenBefore[i + 1] = hiddenBefore[i] + (me[i] - ms[i]);
                startSum[i + 1] = startSum[i] + ms[i];
            }
            return new FoldIndex(java.util.Arrays.copyOf(ms, k),
                    java.util.Arrays.copyOf(me, k), hiddenBefore, startSum);
        }

        boolean isEmpty() { return startLines.length == 0; }

        /**
         * Number of doc lines strictly ABOVE {@code docLine} that are
         * hidden by collapsed folds. O(log folds).
         */
        int hiddenAbove(int docLine) {
            int n = startLines.length;
            if (n == 0 || docLine <= 0) return 0;
            // Folds [0, k) start strictly above docLine.
            int k = lowerBound(startLines, docLine);
            if (k == 0) return 0;
            // Among them, folds [0, j) end strictly above docLine too
            // (fully above — endLines is sorted since regions are merged).
            int j = lowerBound(endLines, docLine);
            int sum = hiddenBefore[j];
            // Folds [j, k) straddle docLine: each hides (docLine-1-startLine)
            // lines above it.
            sum += (k - j) * (docLine - 1) - (startSum[k] - startSum[j]);
            return sum;
        }

        /** True when {@code docLine} is INSIDE a collapsed fold — i.e.
         * startLine < docLine <= endLine for some merged region. O(log folds). */
        boolean isHidden(int docLine) {
            int n = startLines.length;
            if (n == 0 || docLine <= 0) return false;
            int k = lowerBound(startLines, docLine);
            if (k == 0) return false;
            // Regions are sorted & non-overlapping: the only candidate
            // with start < docLine is index k-1, and (since endLines is
            // sorted) no earlier region can reach docLine either.
            return endLines[k - 1] >= docLine;
        }

        private static int lowerBound(int[] a, int key) {
            int lo = 0, hi = a.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (a[mid] < key) lo = mid + 1; else hi = mid;
            }
            return lo;
        }
    }

    /** v3.35.0 — the memoized fold index, rebuilt when
     *  (session, fold revision, document) changes. */
    private FoldIndex foldIndex() {
        EditorSession s = session;
        if (s == null) return FoldIndex.EMPTY;
        if (s != foldIndexSession || s.getFoldRevision() != foldIndexRev
                || s.getDocument() != foldIndexDoc) {
            foldIndexSession = s;
            foldIndexRev = s.getFoldRevision();
            foldIndexDoc = s.getDocument();
            foldIndexCache = FoldIndex.build(s.getFoldRegions(), foldIndexDoc);
        }
        return foldIndexCache;
    }

    /**
     * v3.35.0 — O(log folds) replacement for {@code session.isLineFolded()}
     * in the draw / hit-test paths (same semantics: union of the
     * {@code (startLine, endLine]} ranges of collapsed folds).
     */
    boolean isLineFoldedCached(int docLine) {
        return foldIndex().isHidden(docLine);
    }

    // ── v3.35.0 — Content-addressed shaped-layout cache (roadmap item 2) ──
    // Port of CodeAssist 3.20's rememberTextMeasurer(cacheSize = 64):
    // ~25% of the lines of a real file are byte-identical ("}", "    }",
    // ""…) and the ligature-mode draw path used to rebuild a
    // SpannableStringBuilder + a StaticLayout (native text shaping) for
    // EVERY line on EVERY frame — scroll, caret blink, selection drag.
    // This LRU memoizes the shaped layout by line CONTENT so identical
    // lines share a single StaticLayout.
    //
    // Cache key: the line TEXT itself. Entry validity: a signature of the
    // line's spans (start, end, type) + the base paint color, PLUS global
    // invalidation when the font (typeface/textSize → EditorMetrics
    // font revision) or the theme (colors baked into the spans) changes.
    // Session identity deliberately does NOT participate: same text +
    // same spans + same font + same theme = same pixels, whatever the
    // document — that's the whole point of content addressing.
    static final int SHAPED_CACHE_CAPACITY = 64;

    private static final class ShapedEntry {
        final android.text.StaticLayout layout;
        final int spansSig;
        ShapedEntry(android.text.StaticLayout layout, int spansSig) {
            this.layout = layout;
            this.spansSig = spansSig;
        }
    }

    private final java.util.LinkedHashMap<String, ShapedEntry> shapedLayoutCache =
            new java.util.LinkedHashMap<String, ShapedEntry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, ShapedEntry> eldest) {
                    return size() > SHAPED_CACHE_CAPACITY;
                }
            };
    private int shapedCacheFontRev = -1;
    private EditorTheme shapedCacheTheme;

    /**
     * v3.35.0 — returns a shaped {@link android.text.StaticLayout} for the
     * ligature-mode drawing of {@code lineText}, memoized content-addressed
     * (roadmap item 2). Identical lines (same text, same span signature,
     * same base color, same font generation, same theme) share ONE layout
     * instead of paying the SpannableStringBuilder + shaping cost per frame.
     *
     * <p>Thread-safety: called from the UI thread only (draw path).</p>
     */
    android.text.StaticLayout shapedLayoutFor(String lineText, StyledLine styled,
                                              android.graphics.Paint paint) {
        int fontRev = metrics.getFontRevision();
        if (fontRev != shapedCacheFontRev || theme != shapedCacheTheme) {
            // Typeface / text size / theme colors changed — every cached
            // layout is stale (colors and font are baked into the spans
            // and the TextPaint captured at build time).
            shapedLayoutCache.clear();
            shapedCacheFontRev = fontRev;
            shapedCacheTheme = theme;
        }
        int sig = shapedSignature(styled, paint);
        ShapedEntry e = shapedLayoutCache.get(lineText);
        if (e != null && e.spansSig == sig) return e.layout;

        android.text.SpannableStringBuilder ssb =
                new android.text.SpannableStringBuilder(lineText);
        if (styled != null && styled.spans != null) {
            for (LineSpan span : styled.spans) {
                int start = clamp(span.startCol, 0, lineText.length());
                int end = clamp(span.endCol, 0, lineText.length());
                if (start >= end) continue;
                int color = theme.colorForToken(span.type);
                ssb.setSpan(new android.text.style.ForegroundColorSpan(color),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                // v2.55 — StyleSpan pour COMMENT (italic) et KEYWORD (bold).
                if (span.type == jo.codeeditor.highlight.TokenType.COMMENT) {
                    ssb.setSpan(new android.text.style.StyleSpan(
                            android.graphics.Typeface.ITALIC),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (span.type == jo.codeeditor.highlight.TokenType.KEYWORD) {
                    ssb.setSpan(new android.text.style.StyleSpan(
                            android.graphics.Typeface.BOLD),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (span.type == jo.codeeditor.highlight.TokenType.ANNOTATION) {
                    // Annotations en gras aussi pour les distinguer
                    // rapidement des types normaux.
                    ssb.setSpan(new android.text.style.StyleSpan(
                            android.graphics.Typeface.BOLD),
                        start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        android.text.StaticLayout sl = new android.text.StaticLayout(
            ssb, new android.text.TextPaint(paint), Integer.MAX_VALUE,
            android.text.Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
        shapedLayoutCache.put(lineText, new ShapedEntry(sl, sig));
        return sl;
    }

    /** v3.35.0 — quick validity signature: spans (start, end, type) +
     *  the base paint color (mutated at draw time — e.g. the magnifier
     *  path — so it must participate to avoid serving a stale base color). */
    private static int shapedSignature(StyledLine styled, android.graphics.Paint paint) {
        int h = paint.getColor();
        if (styled == null || styled.spans == null) return h;
        for (LineSpan span : styled.spans) {
            h = h * 31 + span.startCol;
            h = h * 31 + span.endCol;
            h = h * 31 + span.type.ordinal();
        }
        return h;
    }

    /** v3.35.0 — number of memoized shaped layouts (tests/diagnostics). */
    int shapedLayoutCacheSize() {
        return shapedLayoutCache.size();
    }

    private final EditorSession.OnLinesShiftedListener cacheShiftListener =
        new EditorSession.OnLinesShiftedListener() {
            @Override
            public void onLinesShifted(int fromLine, int delta) {
                renderCache.shiftKeys(fromLine, delta);
            }
            @Override
            public void onLinesReset() {
                // v2.45 — EditorSession.restyleAllAsync may call this
                // from a background thread (the restyleExecutor worker).
                // renderCache.clear() mutates a HashMap and must run on
                // the UI thread; invalidate() must also be on the UI
                // thread for the view to redraw correctly. Mirror the
                // notifyDiagnosticsChanged cross-thread pattern.
                if (getHandler() != null
                        && Thread.currentThread() != getHandler().getLooper().getThread()) {
                    getHandler().post(() -> {
                        renderCache.clear();
                        invalidate();
                    });
                } else {
                    renderCache.clear();
                    invalidate();
                }
            }
        };

    // ── Signature help popup (v1.0.7 — Gap 2) ────────────────────
    // Shows function signature help above the caret when the caret is
    // inside a function call. Anchored ABOVE the caret line (unlike
    // completion which is BELOW). Falls back to a synthetic "function(…)
    // param N" popup when no resolver is plugged in — useful for the
    // demo and for languages without a language server.
    final jo.codeeditor.completion.SignatureHelpController signatureHelpController =
        new jo.codeeditor.completion.SignatureHelpController();
    boolean signatureHelpVisible = false;
    jo.codeeditor.completion.SignatureHelpController.SignatureHelp signatureHelpData;
    SignatureHelpResolver signatureHelpResolver;

    /**
     * Resolver interface for signature help — the host plugs one in to
     * feed language-aware signatures. Without a resolver, the popup
     * falls back to a synthetic "function(…) param N" hint.
     */
    public interface SignatureHelpResolver {
        jo.codeeditor.completion.SignatureHelpController.SignatureHelp resolve(
            String text, int caret);
    }

    // ── Quick doc popup (v1.0.7 — Gap 4) ──────────────────────────
    // Shows documentation for the symbol at the caret / hover position.
    // Desktop: hover > 500ms triggers. Mobile: long-press symbol triggers.
    // Dismissed by tap elsewhere, scroll, edit, or Esc.
    QuickDocResolver quickDocResolver;
    boolean quickDocVisible = false;
    jo.codeeditor.doc.QuickDoc.QuickDocContent quickDocContent;
    // v3.3.10: Diagnostics provider — stored so we can re-run diagnostics
    // on every text change (debounced). Without this, diagnostics were only
    // computed ONCE 500ms after setLanguage and never refreshed.
    private jo.codeeditor.lang.DiagnosticsProvider diagnosticsProviderSpi;
    private Runnable diagnosticsTask;
    private static final int DIAGNOSTICS_DEBOUNCE_MS = 600;
    float quickDocX, quickDocY;
    /** v2.38 — offset d'ancrage du quick doc : le popup est REPOSITIONNÉ à chaque frame depuis cet offset (il suit le texte au scroll, motif Sora HoverWindow) au lieu de rester figé en coordonnées écran. */
    int quickDocAnchorOffset;
    /** v2.38 — scroll vertical du corps du quick doc (drag sur le popup, motif NavMenu). */
    float quickDocScrollY;
    private static final long QUICK_DOC_HOVER_DELAY_MS = 500;
    /** v2.38 — slop souris (px) avant de re-résoudre le symbole survolé (Sora : HOVER_TAP_SLOP = 20px) — un tremblement sous le seuil ne redémarre PAS le dwell. */
    private static final float HOVER_SLOP_PX = 20f;
    // v2.38 — position du POINTEUR (pas du caret !) sous laquelle résoudre
    // le hover : l'ancien callback résolvait à selection.start → survoler
    // un autre symbole sans bouger le caret affichait le MAUVAIS doc.
    private float hoverX, hoverY;
    private boolean hoverPositionValid = false;
    private final Runnable quickDocHoverAction = () -> {
        if (session != null && hoverPositionValid) {
            // Résout sous le POINTEUR (Sora : la CharPosition sous la
            // souris, jamais le caret).
            int offset = offsetAt(hoverX, hoverY);
            if (offset >= 0) showQuickDoc(offset);
        }
    };

    /**
     * Resolver for the quick-doc popup. Returns the raw doc comment text
     * (Javadoc/KDoc); the view parses it via {@link jo.codeeditor.doc.QuickDoc#parseQuickDoc}.
     */
    public interface QuickDocResolver {
        String resolve(String text, int offset);
    }

    // ── Code actions lightbulb (v1.0.7 — Gap 5) ──────────────────
    // Shows a 💡 in the gutter for every visible line that has at least
    // one code action. Tap the bulb → popup with the action list.
    // Tap an action → apply.run() (the resolver supplies a Runnable).
    CodeActionsResolver codeActionsResolver;
    final java.util.Map<Integer, List<CodeAction>> codeActionsByLine = new java.util.HashMap<>();
    boolean codeActionsPopupVisible = false;
    int codeActionsPopupLine = -1;
    int codeActionsSelected = 0;
    static final float CODE_ACTIONS_POPUP_WIDTH_DP = 260f;
    static final float CODE_ACTIONS_ROW_HEIGHT_DP = 28f;
    static final int CODE_ACTIONS_MAX_ROWS = 8;

    /**
     * Resolver for code actions. Returns a list of {@link CodeAction}s
     * for the given document text + line.
     */
    public interface CodeActionsResolver {
        List<CodeAction> resolve(String text, int line);
    }

    /**
     * A single code action (Gap 5) — title, kind, and a Runnable to apply it.
     */
    public static final class CodeAction {
        public final String title;
        public final String kind; // "quickfix", "refactor", "source"
        public final Runnable apply;
        public CodeAction(String title, String kind, Runnable apply) {
            this.title = title != null ? title : "";
            this.kind = kind != null ? kind : "quickfix";
            this.apply = apply;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // v2.36: Menu contextuel unifié (portage du NavMenu de CodeAssist)
    // ════════════════════════════════════════════════════════════════
    // Le bouton « Actions ⋯ » de la toolbar de sélection ouvre le menu
    // contextuel unifié de l'éditeur : sections GO TO (options de navigation
    // applicables au caret), QUICK FIXES (kind quickfix) et INTENTIONS
    // (autres kinds), chacune affichée seulement si non-vide — ou le note
    // « Nothing found in source. » quand tout est vide. Un pick GO TO
    // multi-cibles bascule en mode RESULTS (picker de cibles).

    /** Le menu est-il visible ? */
    boolean navMenuVisible = false;
    /** Mode picker : true = liste de cibles d'une option GO TO. */
    boolean navMenuResultsMode = false;
    /** Ligne d'ancrage du popup (sous la ligne du caret). */
    int navMenuLine = -1;
    /** Offset caret de résolution (l'extrémité START de la sélection). */
    int navMenuCaretOffset = -1;
    /** Options GO TO applicables (mode Menu) — Declaration /
     *  Implementations / Type declaration / Super (ordre NavKind
     *  CodeAssist). */
    List<NavigationMenu.NavOption> navMenuOptions = new ArrayList<>();
    /** Quick fixes (kind quickfix) de la ligne du caret (mode Menu). */
    List<CodeAction> navMenuQuickFixes = new ArrayList<>();
    /** Intentions (kind != quickfix) de la ligne du caret (mode Menu). */
    List<CodeAction> navMenuIntentions = new ArrayList<>();
    /** Cibles du picker (mode Results). */
    List<NavigationMenu.NavTarget> navMenuTargets = new ArrayList<>();
    /** Index de rangée pressée (-1 = aucune) — feedback press. */
    int navMenuPressedIdx = -1;
    /** Décalage vertical de scroll du contenu (pixels), borné. */
    float navMenuScrollY = 0;

    static final float NAV_MENU_ROW_HEIGHT_DP = 40f;
    static final float NAV_MENU_HEADER_HEIGHT_DP = 26f;
    static final float NAV_MENU_MAX_HEIGHT_DP = 360f;
    static final float NAV_MENU_MIN_WIDTH_DP = 240f;
    static final float NAV_MENU_MAX_WIDTH_DP = 320f;
    static final float NAV_MENU_GAP_DP = 6f;
    static final float NAV_MENU_MARGIN_DP = 8f;

    /**
     * v2.36 — une rangée du menu contextuel unifié. SOURCE UNIQUE du
     * rendu et du hit-test (pattern SelectionToolbarMetrics).
     */
    static final class NavMenuRow {
        static final int TYPE_HEADER = 0;
        static final int TYPE_OPTION = 1;
        static final int TYPE_ACTION = 2;
        static final int TYPE_TARGET = 3;
        static final int TYPE_NOTHING = 4;
        /** Section de la rangée (mode Menu) : 0 = GO TO, 1 = QUICK FIXES, 2 = INTENTIONS. */
        static final int SECTION_GO_TO = 0;
        static final int SECTION_QUICK_FIXES = 1;
        static final int SECTION_INTENTIONS = 2;
        final int type;
        /** Index dans la liste de section (option / action / target). */
        final int index;
        /** La section d'une rangée TYPE_ACTION (SECTION_*). */
        final int section;
        /** NavigationMenu.NavOption (TYPE_OPTION) ou CodeAction (TYPE_ACTION). */
        final Object ref;
        NavMenuRow(int type, int index, int section, Object ref) {
            this.type = type;
            this.index = index;
            this.section = section;
            this.ref = ref;
        }
    }

    // ── Go-to-symbol popup (v1.0.7 — Gap 6) ──────────────────────
    // Centred-at-top popup with a filter field and a scrollable symbol list.
    // Filter is case-insensitive prefix + camel-hump (NavigationMenu.filter).
    SymbolResolver symbolResolver;
    boolean goToSymbolVisible = false;
    String goToSymbolFilter = "";
    List<NavigationMenu.Symbol> goToSymbolAll = new ArrayList<>();
    List<NavigationMenu.Symbol> goToSymbolFiltered = new ArrayList<>();
    int goToSymbolSelected = 0;
    int goToSymbolScrollOffset = 0;
    static final float GO_TO_SYMBOL_WIDTH_DP = 320f;
    static final float GO_TO_SYMBOL_ROW_HEIGHT_DP = 26f;
    static final int GO_TO_SYMBOL_MAX_ROWS = 10;
    static final float GO_TO_SYMBOL_RADIUS_DP = 6f;

    /**
     * Resolver for the go-to-symbol popup. Returns ALL symbols in the
     * document; the view filters via {@link NavigationMenu#filter} on
     * every keystroke in the popup's filter field.
     */
    public interface SymbolResolver {
        List<NavigationMenu.Symbol> resolve(String text);
    }

    // ── v3.33.11: LSP providers wiring ─────────────────────────────
    // The following resolvers bridge the SPI Language providers (definition,
    // references, document highlights, rename, formatter, inlay hints) to
    // the editor's UI / draw paths. setLanguage() wires them automatically
    // when the SPI provider returns non-null.

    /** Resolver for go-to-definition. Returns a list of target locations. */
    public interface DefinitionResolver {
        List<jo.codeeditor.lang.DefinitionLocation> resolve(String text, int offset);
    }
    DefinitionResolver definitionResolver;

    /**
     * v2.36 — resolver pour le go-to-TYPE-declaration (le type du symbole au
     * caret, {@code Foo x = …} → {@code class Foo}). Nourrit la section
     * GO TO du menu contextuel unifié (toolbar de sélection → Actions ⋯).
     */
    public interface TypeDefinitionResolver {
        List<jo.codeeditor.lang.DefinitionLocation> resolve(String text, int offset);
    }
    TypeDefinitionResolver typeDefinitionResolver;

    /**
     * v2.37 — resolver pour le go-to-IMPLEMENTATIONS (les héritiers DIRECTS
     * du type en contexte au caret — une référence de type, sinon la classe
     * englobante). Section GO TO du menu contextuel unifié (portage des
     * {@code implementationTargets} de CodeAssist — icône layers).
     */
    public interface ImplementationsResolver {
        List<jo.codeeditor.lang.DefinitionLocation> resolve(String text, int offset);
    }
    ImplementationsResolver implementationsResolver;

    /**
     * v2.37 — resolver pour le go-to-SUPER (le membre outrepassé dans chaque
     * supertype, sinon les supertypes DIRECTS du type en contexte). Section
     * GO TO du menu contextuel unifié (portage des {@code superTargets} de
     * CodeAssist — icône pin).
     */
    public interface SuperResolver {
        List<jo.codeeditor.lang.DefinitionLocation> resolve(String text, int offset);
    }
    SuperResolver superResolver;

    /** Resolver for find-references. Returns a list of usage locations. */
    public interface ReferencesResolver {
        List<jo.codeeditor.lang.DefinitionLocation> resolve(String text, int offset);
    }
    ReferencesResolver referencesResolver;

    /**
     * Resolver for document highlights (occurrences of the symbol under
     * the caret). Returns a list of [start, end] pairs (offsets).
     */
    public interface DocumentHighlightResolver {
        /** Returns int[]{start, end} pairs of highlighted ranges. */
        List<int[]> resolve(String text, int offset);
    }
    DocumentHighlightResolver documentHighlightResolver;
    /** Cached document-highlight ranges; invalidated on caret move + edit. */
    final List<int[]> documentHighlights = new ArrayList<>();

    // ════════════════════════════════════════════════════════════════
    // v2.32: Matching-bracket highlight (CodeAssist parity)
    // ════════════════════════════════════════════════════════════════

    /**
     * The currently highlighted bracket pair, as document offsets of the
     * OPEN and CLOSE characters — or {@code null} when the caret is not
     * adjacent to a bracket (or the bracket is unmatched). Recomputed
     * synchronously on every caret move + edit by
     * {@link #updateBracketPair()} — the scan is bounded
     * ({@link #BRACKET_SCAN_LIMIT}) so an unmatched bracket in a huge
     * file can't cost O(N) per keystroke.
     */
    int[] bracketPair;

    /**
     * v2.32: Cap on the bracket-match scan (CodeAssist
     * {@code BRACKET_SCAN_LIMIT = 50_000}) so an unmatched bracket in a
     * huge file just yields no highlight instead of walking the whole
     * document.
     */
    static final int BRACKET_SCAN_LIMIT = 50_000;

    /**
     * v2.32: Port of CodeAssist {@code EditorEdits.matchingBracket}. Finds
     * the matching bracket for the bracket immediately BEFORE or AT
     * {@code caret}, as {@code [openOffset, closeOffset]}, or null.
     *
     * <p>Probes {@code caret-1} first (cursor right after a close bracket
     * like {@code }|} or {@code )|} highlights the matching opener), then
     * {@code caret} (cursor ON an open bracket highlights the closer).
     * Naive depth scan ignoring strings/comments — fine for highlighting,
     * exactly like CodeAssist.
     */
    static int[] matchingBracket(CharSequence text, int caret) {
        if (text == null) return null;
        for (int probe : new int[]{caret - 1, caret}) {
            if (probe < 0 || probe >= text.length()) continue;
            char ch = text.charAt(probe);
            char close = closeOf(ch);
            if (close != 0) {
                int depth = 0;
                int i = probe;
                int limit = Math.min(text.length(), probe + BRACKET_SCAN_LIMIT);
                while (i < limit) {
                    char c = text.charAt(i);
                    if (c == ch) depth++;
                    else if (c == close) {
                        depth--;
                        if (depth == 0) return new int[]{probe, i};
                    }
                    i++;
                }
            } else {
                char open = openOf(ch);
                if (open == 0) continue;
                int depth = 0;
                int i = probe;
                int limit = Math.max(0, probe - BRACKET_SCAN_LIMIT);
                while (i >= limit) {
                    char c = text.charAt(i);
                    if (c == ch) depth++;
                    else if (c == open) {
                        depth--;
                        if (depth == 0) return new int[]{i, probe};
                    }
                    i--;
                }
            }
        }
        return null;
    }

    /** Returns the closing bracket for an opening one, or 0 if not a bracket. */
    private static char closeOf(char c) {
        switch (c) {
            case '(': return ')';
            case '[': return ']';
            case '{': return '}';
            default: return 0;
        }
    }

    /** Returns the opening bracket for a closing one, or 0 if not a bracket. */
    private static char openOf(char c) {
        switch (c) {
            case ')': return '(';
            case ']': return '[';
            case '}': return '{';
            default: return 0;
        }
    }

    /**
     * v2.32: Recomputes {@link #bracketPair} from the current selection.
     * Called synchronously on caret moves, edits, and language/session
     * changes — the scan is bounded and typically stops at the match, so
     * this is safe to run on the UI thread (CodeAssist recomputes it on
     * every recomposition the same way).
     */
    void updateBracketPair() {
        if (session == null) {
            bracketPair = null;
            return;
        }
        Selection sel = session.getSelection();
        bracketPair = matchingBracket(session.getText(), sel.start);
    }

    /** Resolver for rename. Returns the new full text after rename. */
    public interface RenameResolver {
        /** Returns the new text, or null if rename failed. */
        String rename(String text, int offset, String newName);
    }
    RenameResolver renameResolver;

    /** Resolver for formatting. Returns the new full text. */
    public interface FormatterResolver {
        String format(String text);
    }
    FormatterResolver formatterResolver;

    /** Provider for inlay hints. Pushed to session.setInlayHints() debounced. */
    jo.codeeditor.lang.InlayHintProvider inlayHintProviderSpi;
    private Runnable inlayHintTask;
    private static final int INLAY_HINT_DEBOUNCE_MS = 800;

    /** v0.1.0.50 : Schedules a debounced document-highlight refresh. */
    void scheduleDocumentHighlights() {
        if (documentHighlightResolver == null) return;
        if (getHandler() != null) {
            getHandler().removeCallbacks(documentHighlightTask);
            getHandler().postDelayed(documentHighlightTask, 400);
        }
    }
    /** v0.1.0.50 : génération du documentHighlight (annulation). */
    private volatile int docHighlightGeneration = 0;
    private final Runnable documentHighlightTask = () -> {
        if (session == null || documentHighlightResolver == null) return;
        Selection sel = session.getSelection();
        if (!sel.isCursor()) {
            docHighlightGeneration++;
            documentHighlights.clear();
            invalidate();
            return;
        }
        // v0.1.0.50 : la requête LSP (timeout 10 s, appelée toutes les
        // 400 ms !) quitte le thread UI ; livraison latest-wins.
        final int gen = ++docHighlightGeneration;
        final String text = session.getText().toString();
        final int caret = sel.start;
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            List<int[]> result = null;
            try {
                result = documentHighlightResolver.resolve(text, caret);
            } catch (Exception ignored) {
            }
            final List<int[]> fetched = result;
            Runnable apply = () -> {
                if (gen != docHighlightGeneration || session == null) return;
                documentHighlights.clear();
                if (fetched != null) documentHighlights.addAll(fetched);
                invalidate();
            };
            android.os.Handler h = getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    };

    /** v3.33.11: Schedules a debounced inlay-hint refresh. */
    private void scheduleInlayHints() {
        if (inlayHintProviderSpi == null || inlayHintTask == null) return;
        if (getHandler() != null) {
            getHandler().removeCallbacks(inlayHintTask);
            getHandler().postDelayed(inlayHintTask, INLAY_HINT_DEBOUNCE_MS);
        }
    }

    /** v0.1.0.50 : génération des inlay hints (annulation). */
    private volatile int inlayGeneration = 0;

    /** v3.33.11: References popup state (reuses the go-to-symbol UI). */
    boolean referencesPopupVisible = false;
    String referencesFilter = "";
    List<NavigationMenu.Symbol> referencesAll = new ArrayList<>();
    List<NavigationMenu.Symbol> referencesFiltered = new ArrayList<>();
    int referencesSelected = 0;
    int referencesScrollOffset = 0;

    // v3.33.5: BlockEditor, SymbolBarView, EditorBarTools, EditorLayoutManager supprimés (code mort).
    // v3.18.0: Non-printable characters display (spaces, tabs, newlines).
    boolean showNonPrintable = false;

    // ★ v2.59 — Caret visibility toggle. Default true (caret visible).
    // Découple « cacher le caret » de EditorSession.setReadOnly(boolean) :
    // avant, setReadOnly(true) était utilisé par ConsoleLogView pour
    // empêcher l'utilisateur de taper ET cacher le caret — mais ce flag
    // bloque AUSSI les mutations programmatiques (replaceRange dans
    // appendLine/setContent/…) → la console restait vide à vie.
    // Désormais, ConsoleLogView passe setFocusable(false) (bloque IME)
    // + setCaretVisible(false) (cache le caret) sans toucher à setReadOnly
    // (le programme peut muter le document). Voir EditorRenderer.drawCaret.
    boolean caretVisible = true;

    // v3.18.0: Magnifier state — re-armed in v2.35 for HANDLE DRAG only
    // (dragHandle feeds X/Y; UP/CANCEL deactivate). The bubble itself is
    // drawn by EditorRenderer.drawMagnifier (60dp, 2x zoom, ±3 lines,
    // inlay-aware). Never active during scroll/drag-select — that was the
    // v3.18.0 "interferes with selection" failure mode.
    boolean magnifierActive = false;
    float magnifierX = 0;
    float magnifierY = 0;

    // v3.19.0: Font ligatures toggle. When enabled, the editor draws each line
    // as a single drawText call (instead of per-span) so that ligatures like
    // ->, =>, ==, !=, >=, <=, &&, ||, :: form properly. Syntax highlighting
    // is disabled when ligatures are on (trade-off: ligatures vs colors).
    boolean fontLigatures = false;

    // v3.31.1: Minimap — VS Code-style miniature rendering of the entire file
    // drawn in a narrow strip on the right edge. Shows the document structure
    // at a glance + indicates the current viewport rectangle. Tap/drag to scroll.
    boolean minimapEnabled = false;
    static final float MINIMAP_WIDTH_DP = 60f;
    static final float MINIMAP_LINE_HEIGHT_PX = 2.5f;  // px per doc line

    // ── EditorOverlayLayers (v1.0.7 — Gap 8) ────────────────────
    // Lightweight Canvas-drawn overlays: diagnostic chips, selection
    // toolbar, go-to-line popup, rename popup, diagnostic sheet.
    // v1.0.9: go-to-line and rename now use real Android PopupWindow +
    // EditText (the Canvas-only versions couldn't receive keyboard input).
    boolean diagnosticChipsEnabled = true;
    boolean selectionToolbarVisible = false;
    // ── v2.34: selection toolbar — CodeAssist SelectionToolbar parity ──
    // The toolbar now also works in COLLAPSED mode (re-tap on the caret →
    // Paste/Select all + icon buttons only), supports press feedback and an
    // entrance animation (entrancePop + per-item cascade port).
    /** uptimeMillis of the last show — drives the entrance animation. */
    long selectionToolbarShownAt = 0L;
    /** Index of the pressed item (press feedback), -1 = none. */
    int selectionToolbarPressedIdx = -1;
    static final int SEL_ACT_COPY = 0;
    static final int SEL_ACT_CUT = 1;
    static final int SEL_ACT_PASTE = 2;
    static final int SEL_ACT_SELECT_ALL = 3;
    static final int SEL_ACT_DOCS = 4;
    static final int SEL_ACT_ACTIONS = 5;
    boolean goToLineVisible = false;
    String goToLineText = "";
    android.widget.PopupWindow goToLinePopup;
    boolean renameVisible = false;
    String renameText = "";
    int renameStartOffset = -1;
    int renameEndOffset = -1;
    android.widget.PopupWindow renamePopup;
    boolean diagnosticSheetVisible = false;
    // v3.4.0: Per-diagnostic popup (CodeAssist DiagnosticSheet pattern).
    // Shows the FULL message + quick-fixes for a single tapped diagnostic.
    // v2.31: redrawn as a CodeAssist-style bottom sheet (scrim + rounded
    // panel + header with severity label + × close + quick-fix rows).
    boolean diagnosticPopupVisible = false;
    DiagnosticShift.Diagnostic diagnosticPopupItem = null;
    int diagnosticPopupOffset = -1;
    int diagnosticSheetScroll = 0;

    // ── v2.31: Diagnostic sheet geometry (CodeAssist DiagnosticSheet port) ──
    static final float DIAG_SHEET_HEADER_DP = 46f;
    static final float DIAG_SHEET_MSG_LINE_DP = 19f;
    static final int DIAG_SHEET_MAX_MSG_LINES = 6;
    static final float DIAG_SHEET_ACTION_ROW_DP = 44f;
    static final float DIAG_SHEET_ACTIONS_BLOCK_DP = 36f;
    static final float DIAG_SHEET_BOTTOM_PAD_DP = 10f;
    static final float DIAG_SHEET_RADIUS_DP = 16f;

    // ── v2.31: Diagnostic chip (CodeAssist DiagnosticChip port) ──
    // One pill per line — the most severe Error/Warning — placed after the
    // line end; tapping it opens the diagnostic sheet.
    static final float DIAG_CHIP_GAP_CHARS = 3f;
    // ── Selection handles (mobile) ─────────────────────────────────
    // After a long-press or double-tap, two draggable handles appear at
    // the start and end of the selection. Dragging a handle moves that
    // end of the selection.
    boolean handlesVisible = false;
    int handleDragMode = 0; // 0=none, 1=start, 2=end, 3=collapsed-caret
    static final float HANDLE_RADIUS_DP = 8f;
    static final float HANDLE_TAP_RADIUS_DP = 16f;

    // ── IME state ──────────────────────────────────────────────────
    /**
     * Set ONLY by an explicit user tap. Focus alone never raises the
     * keyboard — opening a file, switching tabs, returning from a sheet
     * must not pop the IME. Losing focus clears the flag so a passive
     * refocus stays silent.
     */
    boolean wantsKeyboard = false;
    final EditorImeBridge imeBridge = new EditorImeBridge(this);
    final EditorScrollManager scrollManager;
    int connectionGeneration = 0;

    // ── Caret blink + glide ────────────────────────────────────────
    // v3.33.10: ALL caret state lives in caretAnim. The renderer reads
    // caretAnim.animX/animY directly. lastEditTime is the only caret-
    // adjacent field kept on EditorView because it's also read by the
    // scroll manager and the IME bridge as a "last user activity"
    // timestamp — caretAnim.updateBlink() reads it via the view ref.
    final CaretAnimator caretAnim = new CaretAnimator(this);
    long lastEditTime = 0;

    // ── Diagnostic squiggle ───────────────────────────────────────

    // ── Indent guides ──────────────────────────────────────────────

    // ── Clipboard cap (Binder-safe) ───────────────────────────────
    /**
     * v2.34 — cap presse-papiers : 200 000 caractères en gardant la FIN
     * (parité CodeAssist {@code clipForClipboard} — prévient la
     * TransactionTooLargeException du binder ~1 Mo tout en préservant la
     * partie utile d'une longue sélection, la fin). Avant : 1 M en gardant
     * le début.
     */
    private static final int MAX_CLIPBOARD_CHARS = 200_000;

    // ── Extracted-text window cap (Binder-safe) ───────────────────
    static final int MAX_EXTRACT_CHARS = 100_000;
    int extractedTextMonitorToken = -1;

    // ── Cursor anchor info (G9i, API 21+) ────────────────────────
    // Some IMEs (Japanese/Chinese) request cursor anchor updates to
    // position their candidate window. 0 = not monitoring; the IME
    // sets this via InputConnection.requestCursorUpdates.
    int cursorAnchorMonitorMode = 0;

    // ── Reusable paints (avoid GC in onDraw) ──────────────────────
    final Paint bgPaint = new Paint();
    final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint selPaint = new Paint();
    final Paint caretPaint = new Paint();
    final Paint squigglePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint guidePaint = new Paint();

    // v3.8.0: All Canvas drawing is delegated to this renderer. EditorView
    // keeps its state fields, public API, input handling, IME, etc. — only
    // the draw methods moved. The renderer accesses EditorView's
    // package-private fields via the `view` reference.
    private final EditorRenderer renderer;
    final EditorInputHandler inputHandler;
    final EditorPopupManager popupManager;
    private final EditorKeyHandler keyHandler;

    // ── Language SPI (v2.0.0) ────────────────────────────────────
    // The Language instance provides all language intelligence (completion,
    // hover, signature help, diagnostics, etc.) via optional provider
    // methods. When set, it replaces the v1.x per-feature resolvers.
    /** v0.1.0.50 : accès package pour EditorPopupManager (trigger chars). */
    Language language;
    /** Coordinates z-order + dismissal of all editor popups. v2.0.0. */
    private final PopupCoordinator popupCoordinator = new PopupCoordinator();

    // v3.31.1: StyleReceiver implementation — bridges the Language SPI's
    // incremental Analyzer to the EditorSession's styledLines. The analyzer
    // calls onStylesUpdated on a worker thread; we post to the UI thread to
    // invalidate the affected lines + redraw.
    private final jo.codeeditor.lang.StyleReceiver styleReceiver =
            new jo.codeeditor.lang.StyleReceiver() {
                @Override
                public void onStylesUpdated(int startLine, int endLine) {
                    if (getHandler() != null) {
                        getHandler().post(() -> {
                            // LineRenderCache n'a pas d'invalidateRange direct :
                            // on invalide à partir de startLine (toutes les
                            // lignes suivantes seront re-tokénisées par le
                            // prochain appel à analyzer.styledLine(i)).
                            renderCache.invalidateFrom(startLine);
                            invalidate();
                        });
                    } else {
                        renderCache.invalidateFrom(startLine);
                        invalidate();
                    }
                }
                @Override
                public void onBlocksUpdated() {
                    if (getHandler() != null) {
                        getHandler().post(EditorView.this::invalidateBlocks);
                    } else {
                        invalidateBlocks();
                    }
                }
            };

    private void invalidateBlocks() {
        if (session == null || language == null) return;
        try {
            java.util.List<jo.codeeditor.lang.CodeBlock> blocks =
                    language.getAnalyzer().computeBlocks();
            if (!blocks.isEmpty()) {
                java.util.List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> regions =
                        new java.util.ArrayList<>(blocks.size());
                for (jo.codeeditor.lang.CodeBlock b : blocks) {
                    regions.add(new jo.codeeditor.shift.DiagnosticShift.FoldRegion(
                            b.start, b.end, b.placeholder, b.kind, b.collapsed));
                }
                session.setFoldRegions(regions);
                invalidate();
            }
        } catch (Throwable t) {
            // Silent — the analyzer may not implement computeBlocks reliably.
        }
    }

    // ── Listeners ──────────────────────────────────────────────────
    OnSelectionChangedListener selectionListener;
    // v3.4.0: Additional selection listeners (BreadcrumbBar, etc.) that
    // don't replace the primary one. setOnSelectionChangedListener sets
    // the primary; addOnSelectionChangedListener adds a secondary.
    final java.util.List<OnSelectionChangedListener> extraSelectionListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int line, int col, boolean isCursor);
    }

    // ════════════════════════════════════════════════════════════════
    // Construction
    // ════════════════════════════════════════════════════════════════

    public EditorView(Context context) {
        this(context, null);
    }

    /**
     * XML layout constructor — required when the EditorView is declared
     * in an XML layout file. Android's LayoutInflater calls this with
     * the Context and AttributeSet from the XML tag.
     *
     * @since v3.1.0
     */
    public EditorView(Context context, android.util.AttributeSet attrs) {
        super(context, attrs);
        this.metrics = new EditorMetrics();
        this.theme = EditorTheme.dark();
        this.gutterView = new GutterView(metrics, theme);
        // v3.7.2: pass screen density so the diagnostic dot can be sized in dp.
        this.gutterView.setDensity(getResources().getDisplayMetrics().density);
        // v3.5.0: wire the gutter's fold-awareness to the session's
        // isLineFolded() so line numbers stay aligned when folds collapse.
        this.gutterView.setHiddenLineChecker(line -> {
            EditorSession s = session;
            // v3.35.0: O(log folds) fold-index lookup instead of the
            // O(folds) session walk (hotspot P3 — the gutter checks every
            // visible line on every draw).
            return s != null && isLineFoldedCached(line);
        });
        // v3.33.5: EditorLayoutManager supprimé (code mort).
        this.session = new EditorSession();
        this.session.setImeListener(imeBridge.listener);
        this.session.setOnLinesShiftedListener(cacheShiftListener);
        // v3.8.0: instantiate the renderer that owns all draw* methods.
        this.renderer = new EditorRenderer(this);
        this.inputHandler = new EditorInputHandler(this);
        this.popupManager = new EditorPopupManager(this);
        this.scrollManager = new EditorScrollManager(this);
        this.keyHandler = new EditorKeyHandler(this);

        // Default focusability so the framework routes keys here.
        setFocusable(true);
        setFocusableInTouchMode(true);

        // v1.0.7 — Gap 9g: mouse hover triggers quick doc after 500ms
        // (desktop / ChromeOS / DeX). The hover callback is a no-op on
        // touch-only devices (HoverEvent isn't dispatched for touches).
        // ★ v2.38 — parité Sora LspEditorHoverEvent : le dwell est
        // redémarré à chaque déplacement au-delà du slop (20px) et résout
        // le symbole sous le POINTEUR (plus sous le caret).
        setOnHoverListener((v, event) -> {
            if (getHandler() == null) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE: {
                    float nx = event.getX(), ny = event.getY();
                    float dx = nx - hoverX, dy = ny - hoverY;
                    boolean movedBeyondSlop = !hoverPositionValid
                            || dx * dx + dy * dy > HOVER_SLOP_PX * HOVER_SLOP_PX;
                    if (movedBeyondSlop) {
                        hoverX = nx;
                        hoverY = ny;
                        hoverPositionValid = true;
                        getHandler().removeCallbacks(quickDocHoverAction);
                        getHandler().postDelayed(quickDocHoverAction,
                                QUICK_DOC_HOVER_DELAY_MS);
                    }
                    break;
                }
                case MotionEvent.ACTION_HOVER_EXIT:
                case MotionEvent.ACTION_CANCEL:
                    hoverPositionValid = false;
                    getHandler().removeCallbacks(quickDocHoverAction);
                    break;
            }
            return false; // don't consume — let the framework still dispatch.
        });

        // Re-apply metrics with the correct density.
        metrics.setTextSize(spToPx(BASE_TEXT_SIZE_SP));
    }

    /** Hooks the session up and registers the IME bridge. */
    public void setSession(EditorSession session) {
        // Detach from the previous session.
        if (this.session != null) {
            this.session.setImeListener(null);
            this.session.setOnLinesShiftedListener(null);
        }
        this.session = session;
        this.session.setImeListener(imeBridge.listener);
        this.session.setOnLinesShiftedListener(cacheShiftListener);
        renderCache.clear();
        // Reset the caret glide so the first draw in the new session snaps.
        caretAnim.reset();
        // v3.33.5: EditorLayoutManager supprimé (code mort).
        // Reset scroll so a stale offset from the previous document doesn't strand the viewport.
        vOffset = 0;
        hOffset = 0;
        invalidate();
    }

    public EditorSession getSession() { return session; }

    public void setTheme(EditorTheme theme) {
        this.theme = theme;
        // v3.35.0: shaped layouts have theme colors baked into their spans —
        // a theme swap invalidates every cached StaticLayout.
        shapedLayoutCache.clear();
        gutterView.setTheme(theme);
        invalidate();
    }

    /**
     * Sets the {@link Language} that provides all language intelligence
     * (completion, hover, signature help, diagnostics, etc.).
     *
     * <p>v3.0.1 fix: now properly bridges ALL SPI providers to the v1.x
     * UI rendering paths. When a {@link Language} is set, the editor:
     * <ul>
     *   <li>Creates adapter wrappers that delegate each v1.x resolver
     *       ({@code completionProvider}, {@code signatureHelpResolver},
     *       {@code quickDocResolver}, {@code codeActionsResolver},
     *       {@code symbolResolver}) to the corresponding
     *       {@code language.getXxxProvider()} method</li>
     *   <li>Resets the analyzer with the current document text</li>
     *   <li>Invalidates the view to trigger a redraw</li>
     * </ul>
     *
     * <p>If a provider returns {@code null} (not supported by this language),
     * the corresponding v1.x resolver is set to {@code null} too — the
     * feature is simply disabled.
     *
     * @param language the language, or {@code null} to detach
     * @since v2.0.0
     */
    public void setLanguage(Language language) {
        if (this.language != null) {
            this.language.destroy();
        }
        // v3.2.0 fix: If language is null, use EmptyLanguage (not null)
        // so all v1.x resolvers are properly cleared. This prevents stale
        // resolvers from a previous language (e.g. Java) from firing on
        // a different file type (e.g. XML). Same pattern as Sora Editor's
        // EmptyLanguage.
        if (language == null) {
            language = new jo.codeeditor.lang.EmptyLanguage();
        }
        this.language = language;
        if (session != null) {
            // Wire the analyzer.
            // v3.31.1: attache le StyleReceiver pour que l'analyzer puisse
            // pousser des mises à jour de style incrémentales depuis un
            // worker thread. Le receiver poste sur l'UI thread pour
            // invalider les lignes affectées.
            try {
                language.getAnalyzer().setReceiver(styleReceiver);
            } catch (Throwable t) {
                // Some analyzers may not support setReceiver (legacy).
            }
            language.getAnalyzer().reset(session.getText());

            // ── Bridge SPI providers → v1.x UI rendering paths ──────
            // Each adapter wraps the SPI provider so the existing draw /
            // hit-test code in EditorView works unchanged.

            // Completion
            if (language.getCompletionProvider() != null) {
                final jo.codeeditor.lang.CompletionProvider spiComp =
                    language.getCompletionProvider();
                setCompletionProvider((text, caret, tokenStart, prefix) -> {
                    java.util.List<jo.codeeditor.completion.CompletionSession.Item> items =
                        new java.util.ArrayList<>();
                    jo.codeeditor.lang.CompletionPublisher pub = new CompletionPublisherAdapter(items);
                    spiComp.complete(text, caret, pub);
                    return items;
                });
            } else {
                setCompletionProvider(null);
            }

            // Signature help
            if (language.getSignatureHelpProvider() != null) {
                final jo.codeeditor.lang.SignatureHelpProvider spiSig =
                    language.getSignatureHelpProvider();
                setSignatureHelpResolver((text, caret) -> {
                    jo.codeeditor.lang.SignatureHelp help = spiSig.signatureHelp(text, caret);
                    if (help == null) return null;
                    // Convert SPI SignatureHelp → v1.x SignatureHelpController.SignatureHelp
                    java.util.List<jo.codeeditor.completion.SignatureHelpController.Signature> sigs =
                        new java.util.ArrayList<>();
                    for (jo.codeeditor.lang.Signature s : help.signatures) {
                        java.util.List<jo.codeeditor.completion.SignatureHelpController.Parameter> params =
                            new java.util.ArrayList<>();
                        for (jo.codeeditor.lang.Parameter p : s.parameters) {
                            params.add(new jo.codeeditor.completion.SignatureHelpController.Parameter(
                                p.label, p.documentation));
                        }
                        sigs.add(new jo.codeeditor.completion.SignatureHelpController.Signature(
                            s.label, s.documentation, params, s.activeParameter));
                    }
                    return new jo.codeeditor.completion.SignatureHelpController.SignatureHelp(
                        sigs, help.activeSignature, help.activeParameter);
                });
            } else {
                setSignatureHelpResolver(null);
            }

            // Hover / quick doc
            if (language.getHoverProvider() != null) {
                final jo.codeeditor.lang.HoverProvider spiHover =
                    language.getHoverProvider();
                setQuickDocResolver((text, offset) -> {
                    jo.codeeditor.lang.HoverContent content = spiHover.hover(text, offset);
                    if (content == null || content.isEmpty()) return null;
                    // Return as a Javadoc-like string for QuickDoc.parseQuickDoc.
                    String doc = content.markdown;
                    if (doc != null && !doc.isEmpty()) {
                        return "/**\n * " + doc.replace("\n", "\n * ") + "\n */";
                    }
                    return content.signature;
                });
            } else {
                setQuickDocResolver(null);
            }

            // Code actions
            if (language.getCodeActionsProvider() != null) {
                final jo.codeeditor.lang.CodeActionsProvider spiActions =
                    language.getCodeActionsProvider();
                setCodeActionsResolver((text, line) -> {
                    java.util.List<CodeAction> out = new java.util.ArrayList<>();
                    for (jo.codeeditor.lang.CodeAction a : spiActions.codeActions(text, line)) {
                        out.add(new CodeAction(a.title, a.kind, a.apply));
                    }
                    return out;
                });
            } else {
                setCodeActionsResolver(null);
            }

            // Go-to-symbol
            if (language.getSymbolProvider() != null) {
                final jo.codeeditor.lang.SymbolProvider spiSym =
                    language.getSymbolProvider();
                setSymbolResolver((text) -> {
                    java.util.List<NavigationMenu.Symbol> out = new java.util.ArrayList<>();
                    for (jo.codeeditor.lang.Symbol s : spiSym.symbols(text)) {
                        out.add(new NavigationMenu.Symbol(s.name, s.offset, s.kind, s.container));
                    }
                    return out;
                });
            } else {
                setSymbolResolver(null);
            }

            // Diagnostics — push to the session if available.
            // v3.3.10: Store the provider + task so onTextChanged() can
            // re-run diagnostics (debounced). Previously diagnostics were
            // only computed ONCE 500ms after setLanguage and never refreshed
            // — so typing a syntax error didn't show a squiggle.
            if (language.getDiagnosticsProvider() != null) {
                diagnosticsProviderSpi = language.getDiagnosticsProvider();
                diagnosticsTask = () -> {
                    if (session == null || diagnosticsProviderSpi == null) return;
                    java.util.List<jo.codeeditor.lang.Diagnostic> diags =
                        diagnosticsProviderSpi.computeDiagnostics(session.getText());
                    java.util.List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> legacy =
                        new java.util.ArrayList<>();
                    for (jo.codeeditor.lang.Diagnostic d : diags) {
                        legacy.add(new jo.codeeditor.shift.DiagnosticShift.Diagnostic(
                            d.start, d.end, d.severity, d.message));
                    }
                    session.setDiagnostics(legacy);
                    // v3.7.1 Bugfix (Bug 6a): push severity-per-line array to
                    // the GutterView so it can draw the red/yellow diagnostic
                    // dot in front of the line number.
                    pushDiagnosticsToGutter();
                    invalidate();
                };
                // Initial run (debounced).
                scheduleDiagnostics();
            } else {
                diagnosticsProviderSpi = null;
                diagnosticsTask = null;
                session.setDiagnostics(new java.util.ArrayList<>());
                // v3.7.1: clear the gutter's diagnostic dots too.
                pushDiagnosticsToGutter();
            }

            // ── v3.33.11: Wire the remaining SPI providers ─────────────
            // These bridge the LSP-backed providers to the editor's UI /
            // draw paths. Each is conditional on the SPI provider being
            // non-null — if a server doesn't support a feature, the
            // resolver is set to null and the corresponding UI element
            // is simply disabled (matching the v1.x pattern).

            // Definition (go-to-definition)
            if (language.getDefinitionProvider() != null) {
                final jo.codeeditor.lang.DefinitionProvider spiDef =
                    language.getDefinitionProvider();
                setDefinitionResolver((text, offset) -> spiDef.definitions(text, offset));
            } else {
                setDefinitionResolver(null);
            }

            // ★ v2.36 : type-definition (go-to-type-declaration) — la section
            // GO TO du menu contextuel unifié (NavMenu port).
            if (language.getTypeDefinitionProvider() != null) {
                final jo.codeeditor.lang.TypeDefinitionProvider spiTypeDef =
                    language.getTypeDefinitionProvider();
                setTypeDefinitionResolver((text, offset) ->
                    spiTypeDef.typeDefinitions(text, offset));
            } else {
                setTypeDefinitionResolver(null);
            }

            // ★ v2.37 : implementations (go-to-implementations) et super
            // (go-to-super) — complètent la section GO TO du menu contextuel
            // unifié aux QUATRE options de CodeAssist.
            if (language.getImplementationsProvider() != null) {
                final jo.codeeditor.lang.ImplementationsProvider spiImpl =
                    language.getImplementationsProvider();
                setImplementationsResolver((text, offset) ->
                    spiImpl.implementations(text, offset));
            } else {
                setImplementationsResolver(null);
            }

            if (language.getSuperDefinitionProvider() != null) {
                final jo.codeeditor.lang.SuperDefinitionProvider spiSuper =
                    language.getSuperDefinitionProvider();
                setSuperResolver((text, offset) ->
                    spiSuper.superTargets(text, offset));
            } else {
                setSuperResolver(null);
            }

            // References (find-references)
            if (language.getReferencesProvider() != null) {
                final jo.codeeditor.lang.ReferencesProvider spiRef =
                    language.getReferencesProvider();
                setReferencesResolver((text, offset) -> spiRef.references(text, offset));
            } else {
                setReferencesResolver(null);
            }

            // Document highlights (occurrences)
            if (language.getDocumentHighlightProvider() != null) {
                final jo.codeeditor.lang.DocumentHighlightProvider spiHl =
                    language.getDocumentHighlightProvider();
                setDocumentHighlightResolver((text, offset) -> {
                    java.util.List<jo.codeeditor.lang.DocumentHighlight> hls =
                        spiHl.highlights(text, offset);
                    java.util.List<int[]> out = new java.util.ArrayList<>();
                    if (hls != null) {
                        for (jo.codeeditor.lang.DocumentHighlight h : hls) {
                            out.add(new int[]{h.start, h.end});
                        }
                    }
                    return out;
                });
            } else {
                setDocumentHighlightResolver(null);
            }

            // Rename — replaces the substring-matching fallback when the
            // language server supports LSP rename.
            if (language.getRenameProvider() != null) {
                final jo.codeeditor.lang.RenameProvider spiRename =
                    language.getRenameProvider();
                setRenameResolver((text, offset, newName) -> {
                    jo.codeeditor.lang.RenameResult result = spiRename.rename(text, offset, newName);
                    if (result == null) return null;
                    // Apply the edits to produce the new full text.
                    StringBuilder sb = new StringBuilder(text);
                    // Copy edits into a mutable list, then sort descending
                    // so earlier offsets stay valid as we replace.
                    java.util.List<jo.codeeditor.lang.TextEdit> edits =
                        new java.util.ArrayList<>(result.edits);
                    edits.sort((a, b) -> Integer.compare(b.start, a.start));
                    for (jo.codeeditor.lang.TextEdit e : edits) {
                        if (e.start >= 0 && e.end <= sb.length() && e.start <= e.end) {
                            sb.replace(e.start, e.end, e.newText);
                        }
                    }
                    return sb.toString();
                });
            } else {
                setRenameResolver(null);
            }

            // Formatter
            if (language.getFormatter() != null) {
                final jo.codeeditor.lang.Formatter spiFmt = language.getFormatter();
                setFormatterResolver((text) -> spiFmt.format(text, 0, text.length()).toString());
            } else {
                setFormatterResolver(null);
            }

            // Inlay hints — pushed to session.setInlayHints() debounced
            // after each edit. The renderer's drawCachedInlays() reads
            // them per-line.
            if (language.getInlayHintProvider() != null) {
                inlayHintProviderSpi = language.getInlayHintProvider();
                inlayHintTask = () -> {
                    if (session == null || inlayHintProviderSpi == null) return;
                    EditorDocument doc = session.getDocument();
                    // v2.32: request the WHOLE document — CodeAssist parity.
                    // Its engine daemon asks {@code hintsAt(path, text, 0,
                    // text.length)} for the full buffer, so hints are
                    // available on every line the user scrolls to. The old
                    // viewport-only request (visible lines ±4, computed from
                    // vOffset/getHeight()) was broken in two ways: at
                    // startup getHeight()==0 so only ~5 lines got hints, and
                    // nothing re-requested on scroll — hints never appeared
                    // below the first screenful ("hintlay pas câblé").
                    // The internal server computes hints for the whole file
                    // regardless of the requested range, so the full-range
                    // request costs the same.
                    int first = 0;
                    int last = Math.max(0, doc.lineCount() - 1);
                    // v0.1.0.50 : la requête inlayHint LSP (5 s) quitte le
                    // thread UI ; livraison latest-wins par génération.
                    final int gen = ++inlayGeneration;
                    final CharSequence text = session.getText();
                    final int fFirst = first;
                    final int fLast = last;
                    EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
                        java.util.List<jo.codeeditor.lang.InlayHint> spiHints = null;
                        try {
                            spiHints = inlayHintProviderSpi.inlayHints(text, fFirst, fLast);
                        } catch (Exception ignored) {
                        }
                        java.util.List<DiagnosticShift.InlayHint> legacy =
                                new java.util.ArrayList<>();
                        if (spiHints != null) {
                            for (jo.codeeditor.lang.InlayHint hint : spiHints) {
                                legacy.add(new DiagnosticShift.InlayHint(hint.offset, hint.text, true));
                            }
                        }
                        final java.util.List<DiagnosticShift.InlayHint> computed = legacy;
                        Runnable apply = () -> {
                            if (gen != inlayGeneration || session == null) return;
                            session.setInlayHints(computed);
                            invalidate();
                        };
                        android.os.Handler h2 = getHandler();
                        if (h2 != null) h2.post(apply); else apply.run();
                    });
                };
                // Initial run (debounced).
                scheduleInlayHints();
            } else {
                inlayHintProviderSpi = null;
                inlayHintTask = null;
                session.setInlayHints(new java.util.ArrayList<>());
            }

            // Trigger an initial document-highlights refresh at the current caret.
            scheduleDocumentHighlights();
            // v2.32: initial matching-bracket state for the loaded document.
            updateBracketPair();
        }
        invalidate();
    }

    /**
     * v3.3.10: Schedules a debounced diagnostics refresh.
     * Called from {@link #setLanguage} (initial) and {@link #onTextChanged}
     * (on every edit). Cancels any pending run and re-posts after
     * {@link #DIAGNOSTICS_DEBOUNCE_MS} ms.
     */
    private void scheduleDiagnostics() {
        if (diagnosticsTask == null) return;
        if (getHandler() != null) {
            getHandler().removeCallbacks(diagnosticsTask);
            getHandler().postDelayed(diagnosticsTask, DIAGNOSTICS_DEBOUNCE_MS);
        } else {
            // View not attached — run immediately.
            diagnosticsTask.run();
        }
    }

    // v3.15.0: Debounced code actions refresh — replaces the per-frame
    // refreshCodeActions call that was blocking the UI thread.
    private Runnable codeActionsTask;
    private static final int CODE_ACTIONS_DEBOUNCE_MS = 800;
    private void scheduleCodeActionsRefresh() {
        if (codeActionsResolver == null) return;
        if (codeActionsTask == null) {
            codeActionsTask = () -> {
                if (session == null) return;
                int first = Math.max(0, (int) (vOffset / metrics.getLineHeight()) - 1);
                int last = Math.min(session.getDocument().lineCount() - 1,
                    (int) ((vOffset + getHeight()) / metrics.getLineHeight()) + 1);
                popupManager.refreshCodeActions(first, last);
                invalidate();
            };
        }
        if (getHandler() != null) {
            getHandler().removeCallbacks(codeActionsTask);
            getHandler().postDelayed(codeActionsTask, CODE_ACTIONS_DEBOUNCE_MS);
        }
    }

    /**
     * v3.7.1: Public hook for external diagnostic producers (e.g. the LSP
     * client in :cel-lsp calling {@code session.setDiagnostics(...)} directly)
     * to notify the EditorView that diagnostics have changed. The EditorView
     * then rebuilds the severity-per-line array and pushes it to the GutterView
     * so the red/yellow diagnostic dots stay in sync.
     *
     * <p><b>Thread-safety (v3.31.1):</b> may be called from any thread
     * (typically the LSP JSON-RPC reader thread). If the call is not on the
     * UI thread, the actual work ({@link #pushDiagnosticsToGutter()} +
     * {@link #invalidate()}) is posted to the view's {@link android.os.Handler}.
     * This prevents races where the diagnostics list is mutated mid-render.</p>
     *
     * <p>Internal callers (the {@link #diagnosticsTask} lambda above) already
     * call {@link #pushDiagnosticsToGutter()} inline, so they don't need to
     * call this method. External callers (LspEditor) should call this after
     * {@code session.setDiagnostics(...)}.
     */
    public void notifyDiagnosticsChanged() {
        if (getHandler() != null
                && Thread.currentThread() != getHandler().getLooper().getThread()) {
            // Cross-thread call: post to the view's Handler so the gutter
            // rebuild + invalidate run on the UI thread.
            getHandler().post(this::pushDiagnosticsToGutterAndInvalidate);
        } else {
            pushDiagnosticsToGutterAndInvalidate();
        }
    }

    private void pushDiagnosticsToGutterAndInvalidate() {
        pushDiagnosticsToGutter();
        // v0.1.0.50 : notifie l'hôte (onglet Problèmes live du bottom sheet).
        if (diagnosticsPublishedListener != null && session != null) {
            try {
                diagnosticsPublishedListener.onDiagnosticsPublished(
                        session.getDiagnostics());
            } catch (Throwable ignored) {
            }
        }
        invalidate();
    }

    /** v0.1.0.50 : hôte écoutant les publications de diagnostics LSP. */
    public interface OnDiagnosticsPublishedListener {
        void onDiagnosticsPublished(List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> diagnostics);
    }
    private OnDiagnosticsPublishedListener diagnosticsPublishedListener;

    public void setOnDiagnosticsPublishedListener(OnDiagnosticsPublishedListener listener) {
        this.diagnosticsPublishedListener = listener;
    }

    /**
     * v3.7.1 Bugfix (Bug 6a): Builds a severity-per-line array from the
     * session's diagnostics and pushes it to the GutterView so it can draw
     * the red/yellow diagnostic dot in front of the line number. If multiple
     * diagnostics fall on the same line, the highest severity wins (error
     * beats warning beats info).
     */
    private void pushDiagnosticsToGutter() {
        if (session == null || gutterView == null) {
            return;
        }
        EditorDocument doc = session.getDocument();
        if (doc == null) {
            gutterView.setDiagnostics(new int[0]);
            return;
        }
        int lineCount = doc.lineCount();
        int[] severityPerLine = new int[lineCount];
        List<DiagnosticShift.Diagnostic> diags = session.getDiagnostics();
        for (DiagnosticShift.Diagnostic d : diags) {
            int line = doc.lineForOffset(d.start);
            if (line < 0 || line >= lineCount) continue;
            // Max severity wins (3=error > 2=warning > 1=info > 0=none).
            if (d.severity > severityPerLine[line]) {
                severityPerLine[line] = d.severity;
            }
        }
        gutterView.setDiagnostics(severityPerLine);
    }

    /**
     * Adapter that collects CompletionItems from a
     * {@link jo.codeeditor.lang.CompletionPublisher} into a list for the
     * v1.x {@code setCompletionProvider} callback.
     */
    private static class CompletionPublisherAdapter implements jo.codeeditor.lang.CompletionPublisher {
        private final java.util.List<jo.codeeditor.completion.CompletionSession.Item> items;
        private boolean cancelled = false;

        CompletionPublisherAdapter(java.util.List<jo.codeeditor.completion.CompletionSession.Item> items) {
            this.items = items;
        }

        @Override
        public void addItem(jo.codeeditor.lang.CompletionItem item) {
            if (cancelled) return;
            // ★ v2.30 — CORRECTIF de mapping + badge de type :
            // AVANT : `item.sortPriority` passait dans le champ kind (int)
            // — le vrai kind LSP n'atteignait JAMAIS le renderer, et le
            // badge de type était impossible ; `isKeyword` recevait
            // `!item.isSnippet` (tout candidat non-snippet était marqué
            // mot-clé, détraquant le reRanked).
            // APRÈS : le kind LSP numérique (kindCode, avec secours dérivé
            // de l'icône string historique) arrive jusqu'au renderer pour
            // le badge ; isKeyword n'est vrai QUE pour un vrai mot-clé.
            int kindCode = item.kindCode != 0
                    ? item.kindCode : legacyIconToKind(item.kind);
            items.add(new jo.codeeditor.completion.CompletionSession.Item(
                item.label, item.detail, item.insertText, item.kind,
                kindCode,
                item.sortPriority,
                kindCode == 14,   // LSP Keyword
                item.isSnippet,
                item.kindTag,
                item.postApplyAction));
        }

        /** v2.30 : kind LSP dérivé de l'icône string historique
         *  (providers v1.x sans kindCode). */
        private static int legacyIconToKind(String icon) {
            if (icon == null) return 0;
            switch (icon) {
                case "k": return 14; // Keyword
                case "m": return 2;  // Method
                case "f": return 5;  // Field
                case "c": return 7;  // Class
                case "i": return 8;  // Interface
                case "e": return 13; // Enum
                case "p": return 9;  // Module (package)
                default: return 0;
            }
        }

        @Override
        public void addItems(java.util.List<jo.codeeditor.lang.CompletionItem> items) {
            for (jo.codeeditor.lang.CompletionItem item : items) addItem(item);
        }

        @Override
        public void flush() {}

        @Override
        public void cancel() { cancelled = true; }

        @Override
        public boolean isCancelled() { return cancelled; }
    }

    /**
     * Returns the current {@link Language}, or {@code null} if none is set.
     *
     * @since v2.0.0
     */
    public Language getLanguage() {
        return language;
    }

    /**
     * Returns the {@link PopupCoordinator} that manages z-order and dismissal
     * of all editor popups.
     *
     * @since v2.0.0
     */
    public PopupCoordinator getPopupCoordinator() {
        return popupCoordinator;
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener l) {
        this.selectionListener = l;
    }

    /**
     * ★ v0.1.0.49-v2.20 — Listener de position de scroll vertical.
     *
     * <p>Notifié après chaque mutation du {@code vOffset} : drag doigt,
     * fling, scroll programmatique ({@code scrollToLine/Offset/By}) et
     * frames d'inertie de {@code computeScroll()}.</p>
     *
     * <p>Usage principal : le sticky-bottom des consoles log embarquées
     * (ConsoleLogView côté app CodeIDE) — le consommateur compare
     * {@code vOffset} à {@code maxVOffset} pour détecter « collé en bas ».</p>
     */
    public interface OnScrollPositionListener {
        void onScrollPosition(float vOffset, float maxVOffset);
    }

    private OnScrollPositionListener scrollPositionListener;

    public void setOnScrollPositionListener(OnScrollPositionListener l) {
        this.scrollPositionListener = l;
    }

    /** Notifie le listener (package-private — appelé depuis les managers). */
    void notifyScrollPositionChanged() {
        OnScrollPositionListener l = scrollPositionListener;
        if (l != null) {
            float max = maxV();
            l.onScrollPosition(vOffset, max);
        }
        // v3.34.0: idle viewport prefetch — re-armed on every scroll so the
        // task only fires once scrolling has been QUIET for 150 ms (never
        // during a fling — CodeAssist measured that prefetching mid-fling
        // just does the next frame's work one frame early, for nothing).
        scheduleViewportPrefetch();
    }

    // ── v3.34.0 — Idle viewport prefetch (CodeAssist 3.20 backport) ──────

    /** Quiet period before prefetching lines around the viewport. */
    private static final int PREFETCH_IDLE_MS = 150;
    /** Prefetch in chunks of this many lines, pausing between chunks as a
     *  cancellation point (the task re-checks the scroll offset each chunk). */
    private static final int PREFETCH_CHUNK = 8;
    /** Pause between chunks (ms) — keeps the main thread responsive. */
    private static final int PREFETCH_CHUNK_PAUSE_MS = 4;

    private Runnable viewportPrefetchTask;

    /**
     * (Re-)arms the idle prefetch. Cheap when called repeatedly during a
     * fling — one removeCallbacks + one postDelayed.
     */
    private void scheduleViewportPrefetch() {
        if (session == null || getWidth() == 0 || getHeight() == 0) return;
        if (viewportPrefetchTask == null) {
            viewportPrefetchTask = () -> runViewportPrefetch();
        }
        if (getHandler() != null) {
            getHandler().removeCallbacks(viewportPrefetchTask);
            getHandler().postDelayed(viewportPrefetchTask, PREFETCH_IDLE_MS);
        }
    }

    /**
     * Warms the render cache for up to one viewport above and below the
     * visible range, so landing on cold text after a fling / goto / search
     * jump doesn't pay the per-line layout cost inside the draw frame
     * (CodeAssist measured ~3.6 ms + 1.2 MB in a single frame when
     * arriving on unshaped text — the layout is ~96% of the cost of a
     * line entering the viewport).
     *
     * <p>Order (CodeAssist prefetchOrder): BOTTOM FIRST, alternating
     * below/above — users read and scroll down, and an interrupted
     * prefetch leaves both edges half-warm instead of one cold edge.
     * Lines hidden by collapsed folds are skipped. The task bails out
     * as soon as the viewport moved (the next scroll re-arms it).
     */
    private void runViewportPrefetch() {
        EditorSession s = session;
        if (s == null || metrics == null || getWidth() == 0) return;
        EditorDocument doc = s.getDocument();
        int lineCount = doc.lineCount();
        if (lineCount == 0) return;
        float lineHeight = metrics.getLineHeight();
        if (lineHeight <= 0) return;
        int first = Math.max(0, (int) (vOffset / lineHeight) - 1);
        int last = Math.min(lineCount - 1,
                (int) ((vOffset + getHeight()) / lineHeight) + 1);
        int span = Math.max(1, last - first);
        // Scope: one viewport below + one above (working set ≈ 3 viewports,
        // can never evict the on-screen entries from the 512-entry LRU).
        int belowStart = last + 1;
        int belowEnd = Math.min(lineCount - 1, last + span);
        int aboveStart = Math.max(0, first - span);
        int aboveEnd = first - 1;

        // Alternate below/above in chunks of PREFETCH_CHUNK, bottom-first.
        int bi = belowStart, ai = aboveEnd; // ai walks DOWN from aboveEnd
        boolean moreBelow = bi <= belowEnd;
        boolean moreAbove = ai >= aboveStart;
        while (moreBelow || moreAbove) {
            // Cancellation point: viewport moved → stop (re-armed by the
            // scroll itself). Reading vOffset here is fine — same thread.
            int nowFirst = Math.max(0, (int) (vOffset / lineHeight) - 1);
            if (Math.abs(nowFirst - first) > span / 2) return;

            if (moreBelow) {
                for (int n = 0; n < PREFETCH_CHUNK && bi <= belowEnd; n++, bi++) {
                    prefetchLine(s, doc, bi);
                }
                moreBelow = bi <= belowEnd;
            }
            if (moreAbove) {
                for (int n = 0; n < PREFETCH_CHUNK && ai >= aboveStart; n++, ai--) {
                    prefetchLine(s, doc, ai);
                }
                moreAbove = ai >= aboveStart;
            }
            if ((moreBelow || moreAbove)
                    && getHandler() != null) {
                // Pause between chunks — posts a continuation AFTER pending
                // input/draw messages, keeping the UI responsive. The
                // continuation simply re-runs the whole computation from the
                // CURRENT viewport: already-prefetched lines are cache hits
                // (one map lookup), so the restart converges instead of
                // redoing work, and a scroll during the pause naturally
                // re-targets the prefetch.
                getHandler().postDelayed(this::runViewportPrefetch,
                        PREFETCH_CHUNK_PAUSE_MS);
                return;
            }
        }
    }

    /** Warms the cache for one line if not already cached and not folded. */
    private void prefetchLine(EditorSession s, EditorDocument doc, int line) {
        try {
            if (isLineFoldedCached(line)) return; // hidden by a collapsed fold
            String text = doc.lineText(line);
            layoutForLine(line, text);       // populates cache on miss
        } catch (Exception ignored) {
            // Stale offset between the range computation and the fetch —
            // skip, the draw path recomputes authoritatively.
        }
    }

    /** v3.4.0: Adds a selection listener WITHOUT replacing the primary. */
    public void addOnSelectionChangedListener(OnSelectionChangedListener l) {
        if (l != null) extraSelectionListeners.add(l);
    }

    /**
     * v3.31.1: Removes a previously-added secondary selection listener.
     * Useful for BreadcrumbBar / overlay hosts that need to clean up on
     * detach to avoid leaking the listener (and the host View it captures).
     *
     * @param l the listener to remove; null is a no-op
     * @since v3.31.1
     */
    public void removeOnSelectionChangedListener(OnSelectionChangedListener l) {
        if (l != null) extraSelectionListeners.remove(l);
    }

    public EditorMetrics getMetrics() { return metrics; }
    public EditorTheme getTheme() { return theme; }
    public float getFontScale() { return fontScale; }
    public float getVOffset() { return vOffset; }
    public float getHOffset() { return hOffset; }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Wrap model depends on viewport width — rebuild on size change.
        if (wordWrap) rebuildWrapModel();
        // Re-clamp scroll to the new max.
        vOffset = clamp(vOffset, 0, maxV());
        hOffset = clamp(hOffset, 0, maxH());
        // v2.39: resize the preview sheet to match the new editor bounds.
        // The sheet itself is anchored to our window position; we ask it
        // to re-evaluate its size by re-applying the current mode.
        if (previewSheet != null && previewMode.isSheet()) {
            previewSheet.switchMode(previewMode);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // v2.39: dismiss any open preview sheet so we don't leak a popup
        // pointing at a detached editor (which would crash on touch).
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        // v3.34.0: full pending-callback cleanup. Before this, a view
        // detached with runnable callbacks still posted (idle prefetch,
        // debounced code-actions refresh, tap-hold hover, multi-tap
        // dismiss) kept running them against a DETACHED view — the view
        // itself was strongly reachable from its Handler until every
        // callback had fired, and each callback could touch state the
        // host had already torn down.
        if (getHandler() != null) {
            if (viewportPrefetchTask != null) {
                getHandler().removeCallbacks(viewportPrefetchTask);
            }
            if (codeActionsTask != null) {
                getHandler().removeCallbacks(codeActionsTask);
            }
        }
        if (inputHandler != null) {
            inputHandler.cancelPendingCallbacks();
        }
        // v3.34.0: cancel any in-flight caret glide ValueAnimator — a running
        // animator on a detached view keeps a postInvalidateOnAnimation loop
        // alive (and the view reachable) until it finishes. The blink itself
        // is time-based and driven by the draw path, so it dies with the view.
        caretAnim.cancelGlide();
    }

    /**
     * Returns the Y coordinate (in content space, before vOffset) of the
     * TOP of the given document line. When word wrap is enabled, this
     * accounts for the extra rows of preceding wrapped lines.
     */
    float docLineToY(int docLine) {
        if (!wordWrap || wrapModel == null) {
            // v3.5.0: account for collapsed fold regions — hidden lines
            // occupy no visual row, so every doc line below a collapsed
            // fold is pulled UP by (count of hidden lines above it) ×
            // lineHeight. Without this, collapsing a fold left a visual
            // gap (the hidden lines were skipped by the draw loop, but
            // the lines after them were still drawn at their original Y).
            int hiddenAbove = countHiddenLinesAbove(docLine);
            return metrics.getPadTop() + (docLine - hiddenAbove) * metrics.getLineHeight();
        }
        return metrics.getPadTop() + wrapModel.topRow(docLine) * metrics.getLineHeight();
    }

    /**
     * v3.5.0: Returns the number of document lines ABOVE {@code docLine}
     * that are currently hidden by a collapsed fold. Used by
     * {@link #docLineToY(int)} and {@link #docLineForScreenY(float)} to
     * map between doc-line space and visual-row space when folds are
     * collapsed.
     * <p>O(docLine) per call — acceptable for typical editor files
     * (&lt; 5k lines). A prefix-sum cache could be added if this becomes
     * a hot path on very large documents.
     */
    int countHiddenLinesAbove(int docLine) {
        // v3.35.0 (hotspot P3): O(log folds) via the memoized FoldIndex
        // prefix-sum — was O(folds × log lines) per call, paid once per
        // VISIBLE line in the draw path (docLineToY).
        return foldIndex().hiddenAbove(docLine);
    }

    /**
     * Returns the number of visual rows occupied by the given document line.
     * Always ≥ 1.
     */
    int rowsForDocLine(int docLine) {
        if (!wordWrap || wrapModel == null) return 1;
        return wrapModel.rowsOf(docLine);
    }

    /**
     * Maps a screen Y coordinate back to a document line, accounting for
     * wrapped rows AND collapsed folds. Used by tap-to-position-caret and
     * scroll calculations.
     */
    int docLineForScreenY(float screenY) {
        float contentY = screenY + vOffset - metrics.getPadTop();
        float lineHeight = metrics.getLineHeight();
        int visualRow = (int) (contentY / lineHeight);
        if (!wordWrap || wrapModel == null) {
            // v3.5.0: invert the fold-aware Y mapping. v3.35.0 (hotspot P3):
            // the old implementation walked EVERY doc line (O(docLineCount ×
            // folds) per tap) — the mapping is now a binary search over
            // visibleIndex(l) = l - hiddenAbove(l), which is non-decreasing,
            // plus a skip-forward inside the (at most one) fold straddling
            // the result. Same semantics as the old walk, including the
            // final fallback to the last doc line.
            if (session == null) return Math.max(0, visualRow);
            EditorDocument doc = session.getDocument();
            if (doc == null) return Math.max(0, visualRow);
            FoldIndex idx = foldIndex();
            int docLineCount = doc.lineCount();
            if (idx.isEmpty() || docLineCount == 0) {
                return Math.max(0, visualRow);
            }
            // Old walk never matched a negative visualRow — it fell through
            // to the last line. Preserve that.
            if (visualRow < 0) return Math.max(0, docLineCount - 1);
            // Smallest line with visibleIndex >= visualRow.
            int lo = 0, hi = docLineCount - 1, best = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (mid - idx.hiddenAbove(mid) >= visualRow) {
                    best = mid;
                    hi = mid - 1;
                } else {
                    lo = mid + 1;
                }
            }
            if (best < 0) return Math.max(0, docLineCount - 1);
            // `best` may itself be hidden (visibleIndex doesn't increase on
            // hidden lines) — skip forward to the next VISIBLE line.
            while (best < docLineCount && idx.isHidden(best)) best++;
            if (best >= docLineCount) return Math.max(0, docLineCount - 1);
            return best;
        }
        return wrapModel.docLineForRow(visualRow);
    }

    /**
     * Returns the column within the given doc line for a wrapped row offset.
     * ★ v2.33 : continuation-aware (les rangées de continuation démarrent à
     * {@code maxColsPerRow + (r-1)*colsPerCont}) — était
     * {@code rowInLine * maxColsPerRow} uniforme.
     */
    int wrappedColFor(int docLine, int rowInLine, int colInRow) {
        if (!wordWrap || wrapModel == null) return colInRow;
        EditorDocument doc = session.getDocument();
        int lineLen = doc.lineEnd(docLine) - doc.lineStart(docLine);
        WrapRows wr = wrapRowsFor(docLine, lineLen);
        return wr.rowStartCol(rowInLine) + colInRow;
    }

    /**
     * Sets the find-match highlights drawn in the viewport. Pass an empty
     * list to clear. {@code currentIndex} is the index (within the list)
     * of the "current" match — drawn with {@code theme.findCurrent} instead
     * of {@code theme.findMatch}.
     */
    public void setFindHighlights(List<Match> matches, int currentIndex) {
        findHighlights.clear();
        if (matches != null) findHighlights.addAll(matches);
        findCurrentIndex = currentIndex;
        invalidate();
    }

    /**
     * Scrolls the editor so the given document offset is visible. Used by
     * Find/Replace navigation, go-to-def, etc.
     */
    public void scrollToOffset(int offset) {
        scrollManager.scrollToOffset(offset);
    }

    /**
     * Toggles word wrap. When enabled, long lines wrap across multiple visual
     * rows inside the text area; horizontal scroll is disabled.
     */
    public void setWordWrap(boolean enabled) {
        if (this.wordWrap == enabled) return;
        this.wordWrap = enabled;
        if (enabled) {
            this.hOffset = 0;
            rebuildWrapModel();
        } else {
            this.wrapModel = null;
        }
        invalidate();
    }

    public boolean isWordWrap() { return wordWrap; }

    // ════════════════════════════════════════════════════════════════
    // Minimap (v3.31.1)
    // ════════════════════════════════════════════════════════════════

    /**
     * v3.31.1: Enables or disables the minimap — a small preview of the entire
     * file rendered in a strip on the right edge. The minimap uses the existing
     * {@link jo.codeeditor.cache.LineRenderCache} to draw each line's
     * token-colored structure at a tiny scale (2.5 px per doc line, 60 dp wide).
     * A scrollbar-like rectangle indicates the current viewport.
     *
     * @param enabled {@code true} to show the minimap, {@code false} to hide
     * @since v3.31.1
     */
    public void setMinimapEnabled(boolean enabled) {
        if (this.minimapEnabled == enabled) return;
        this.minimapEnabled = enabled;
        invalidate();
    }

    /** @return {@code true} if the minimap is currently visible. */
    public boolean isMinimapEnabled() { return minimapEnabled; }

    /**
     * Returns the X coordinate where the minimap starts (right edge minus
     * minimap width). Returns the full width when the minimap is disabled.
     */
    int getMinimapLeft() {
        if (!minimapEnabled) return getWidth();
        float density = getResources().getDisplayMetrics().density;
        return (int) (getWidth() - MINIMAP_WIDTH_DP * density);
    }

    /** Returns the minimap width in pixels (0 when disabled). */
    int getMinimapWidth() {
        if (!minimapEnabled) return 0;
        float density = getResources().getDisplayMetrics().density;
        return (int) (MINIMAP_WIDTH_DP * density);
    }

    // ════════════════════════════════════════════════════════════════
    // Preview mode (v3.13.0)
    // ════════════════════════════════════════════════════════════════

    /**
     * v3.13.0: Preview mode for Markdown/HTML files. When enabled, the editor
     * reserves a portion of its width for a preview pane (rendered by the host
     * via a WebView overlay). The editor draws a divider line and clips its
     * text to the remaining width.
     *
     * <p>The host creates a WebView as a sibling view (in a FrameLayout),
     * positions it at {@link #getPreviewLeft()}, and calls
     * {@link #getPreviewWidth()} for the width. The host also listens for
     * {@link OnPreviewModeChangedListener} to know when to show/hide/resize
     * the WebView.
     *
     * <p>v2.39 adds {@link #SHEET_SPLIT} and {@link #SHEET_FULL}: instead of
     * reserving inline space, the editor opens a popup sheet overlay
     * ({@link EditorPreviewSheet}) anchored to the editor view. The editor's
     * text area stays at full width — the sheet floats on top. This mode is
     * used for {@code .md}/{@code .html} files where the host provides a
     * WebView body via {@link EditorPreviewHost#onCreatePreviewView}.
     */
    public enum PreviewMode {
        /** No preview — editor takes full width. */
        NONE,
        /** Split — editor on left, preview on right (each ~50% width). */
        SPLIT,
        /** Full — preview takes full width, editor hidden. */
        FULL,
        /**
         * v2.39: Popup sheet docked to the right half — preview overlay,
         * editor text area remains full-width underneath. Used for
         * {@code .md}/{@code .html} files where the host provides a View
         * (typically a WebView) for the sheet body.
         */
        SHEET_SPLIT,
        /**
         * v2.39: Popup sheet covering the full editor area — preview overlay,
         * editor text area remains full-width underneath. Used for
         * {@code .md}/{@code .html} files.
         */
        SHEET_FULL;

        /** v2.39: True for sheet-overlay modes (popup) vs inline modes. */
        public boolean isSheet() {
            return this == SHEET_SPLIT || this == SHEET_FULL;
        }
    }

    PreviewMode previewMode = PreviewMode.NONE;
    private OnPreviewModeChangedListener previewModeListener;

    // v3.17.0: Canvas-drawn preview icons (top-right corner).
    // When previewable = true, two small icons are drawn:
    // - split preview icon (left of the pair)
    // - full preview icon (right of the pair)
    // Tapping an icon toggles the preview mode.
    boolean previewable = false;
    private String currentFileName = "";
    private static final float PREVIEW_ICON_SIZE_DP = 22f;
    private static final float PREVIEW_ICON_MARGIN_DP = 8f;

    // v3.31.0: DECOUPLED preview host. The host app provides preview
    // functionality (XML inflation, Markdown rendering, etc.) via this
    // interface. The editor library no longer depends on preview modules.
    private EditorPreviewHost previewHost;
    /** v2.39: Active popup sheet overlay when previewMode is a SHEET_* mode. */
    EditorPreviewSheet previewSheet;
    private final Runnable previewUpdateTask = () -> {
        updatePreviewContent();
        invalidate();
    };

    /**
     * v3.31.0: Registers the preview host that provides preview rendering.
     *
     * <p>Without a host, the editor cannot preview any file. The host is
     * typically set once in the host Activity's onCreate():
     * <pre>{@code
     * editorView.setPreviewHost(new XmlPreviewHost(this));
     * }</pre>
     *
     * @param host the preview host, or null to disable preview
     * @since v3.31.0
     */
    public void setPreviewHost(EditorPreviewHost host) {
        this.previewHost = host;
    }

    public EditorPreviewHost getPreviewHost() {
        return previewHost;
    }

    /**
     * v3.17.0: Sets the current file name so the editor can detect whether
     * preview is available (.md, .markdown, .html, .htm, .xml layout).
     * When previewable, the editor draws preview icons in the top-right corner.
     */
    public void setFileName(String fileName) {
        this.currentFileName = fileName != null ? fileName : "";
        // v3.31.0: Delegate previewability check to the host.
        this.previewable = (previewHost != null && previewHost.canPreview(this.currentFileName));
        invalidate();
    }

    /**
     * v2.39: Returns the last file name set via {@link #setFileName(String)},
     * or the empty string if none was set. Used by the preview sheet to
     * display the file name in its header.
     *
     * @since v2.39
     */
    public String getFileName() {
        return currentFileName;
    }

    private static boolean isPreviewableFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".md")
            || lower.endsWith(".markdown")
            || lower.endsWith(".html")
            || lower.endsWith(".htm")
            || lower.endsWith(".xml");
    }

    private static boolean isXmlLayoutFile(String fileName) {
        if (fileName == null) return false;
        return fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".xml");
    }

    /**
     * v3.17.0: Hit-tests the preview icons. Returns:
     * 0 = no hit
     * 1 = split preview icon hit
     * 2 = full preview icon hit
     *
     * <p>v2.39: SHEET_* modes hide the badges (the sheet chrome owns the
     * close interaction). The sheet itself can switch between SHEET_SPLIT
     * and SHEET_FULL via its header toggle.
     */
    int hitTestPreviewIcons(float x, float y) {
        if (!previewable || previewMode != PreviewMode.NONE) return 0;
        float density = getResources().getDisplayMetrics().density;
        float iconSize = PREVIEW_ICON_SIZE_DP * density;
        float margin = PREVIEW_ICON_MARGIN_DP * density;
        float iconY = margin;
        float iconW = iconSize;
        // v3.20.1: toolbar icons are gone (moved to EditorBarTools), so
        // preview icons are now at the rightmost edge.
        float fullX = getWidth() - margin - iconW;
        float splitX = fullX - iconW - margin * 0.5f;
        if (y >= iconY && y <= iconY + iconW) {
            if (x >= splitX && x <= splitX + iconW) return 1;
            if (x >= fullX && x <= fullX + iconW) return 2;
        }
        return 0;
    }

    /**
     * Listener for preview mode changes. The host implements this to
     * show/hide/resize its WebView overlay.
     */
    public interface OnPreviewModeChangedListener {
        void onPreviewModeChanged(PreviewMode mode, int previewLeft, int previewWidth);
    }

    public void setOnPreviewModeChangedListener(OnPreviewModeChangedListener listener) {
        this.previewModeListener = listener;
    }

    /**
     * Sets the preview mode. When SPLIT or FULL, the editor reserves space
     * for the preview pane and notifies the listener with the preview bounds.
     *
     * <p>v2.39: When mode is {@link PreviewMode#SHEET_SPLIT} or
     * {@link PreviewMode#SHEET_FULL}, the editor opens a popup sheet overlay
     * (see {@link EditorPreviewSheet}) instead of reserving inline space.
     * The text area remains full-width and the sheet floats on top.
     */
    public void setPreviewMode(PreviewMode mode) {
        if (this.previewMode == mode) return;
        // v2.39: close any existing sheet before switching modes.
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        this.previewMode = mode;
        // Rebuild wrap model since the text-area width changed.
        // (SHEET_* modes do NOT change text width — wrap model stays valid.)
        if (wordWrap && !mode.isSheet()) rebuildWrapModel();
        // v3.31.0: Notify the preview host of the mode change.
        if (previewHost != null) {
            previewHost.onPreviewModeChanged(previewMode, getPreviewLeft(), getPreviewWidth());
            // Also push the current content immediately.
            if (previewMode != PreviewMode.NONE && session != null) {
                previewHost.onPreviewContentChanged(session.getText());
            }
        }
        // v2.39: open the sheet overlay for SHEET_* modes.
        if (mode.isSheet() && previewHost != null) {
            openPreviewSheet(mode);
        }
        invalidate();
        notifyPreviewModeChanged();
    }

    /**
     * v2.39: Convenience entry point invoked when the user taps one of the
     * preview badges drawn by {@link EditorRenderer#drawPreviewIcons}.
     *
     * <p>For {@code .md}/{@code .html} files (where the host typically
     * provides a WebView body via {@link EditorPreviewHost#onCreatePreviewView}),
     * this opens a popup sheet overlay ({@link PreviewMode#SHEET_SPLIT} or
     * {@link PreviewMode#SHEET_FULL}) so the editor's text area remains
     * full-width and the sheet floats on top.
     *
     * <p>For {@code .xml} layout files (where the host renders via
     * {@link EditorPreviewHost#drawPreview(Canvas, float, float)} into the
     * editor canvas), this opens the legacy inline {@link PreviewMode#SPLIT}
     * or {@link PreviewMode#FULL} mode.
     *
     * @param full true for the full-screen badge, false for the split badge
     * @since v2.39
     */
    public void openPreview(boolean full) {
        if (!previewable || previewHost == null) return;
        String lower = currentFileName.toLowerCase(java.util.Locale.ROOT);
        boolean useSheet = lower.endsWith(".md")
            || lower.endsWith(".markdown")
            || lower.endsWith(".html")
            || lower.endsWith(".htm");
        PreviewMode target = useSheet
            ? (full ? PreviewMode.SHEET_FULL : PreviewMode.SHEET_SPLIT)
            : (full ? PreviewMode.FULL : PreviewMode.SPLIT);
        setPreviewMode(target);
    }

    /**
     * v2.39: Opens the popup sheet overlay for the given SHEET_* mode.
     * Package-private — callers go through {@link #setPreviewMode} or
     * {@link #openPreview}.
     */
    void openPreviewSheet(PreviewMode mode) {
        if (!mode.isSheet()) return;
        if (previewHost == null) return;
        if (getWidth() == 0 || getHeight() == 0) {
            // Defer until laid out — schedule a retry on the next predraw.
            post(() -> openPreviewSheet(mode));
            return;
        }
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        previewSheet = new EditorPreviewSheet(this, mode);
        previewSheet.show();
    }

    /**
     * v2.39: Closes the active popup sheet (if any) and returns to
     * {@link PreviewMode#NONE}. Called when the user taps the sheet's
     * close button, taps outside the sheet, or presses Back.
     */
    public void closePreviewSheet() {
        if (previewSheet != null) {
            previewSheet.dismiss();
            previewSheet = null;
        }
        if (previewMode.isSheet()) {
            previewMode = PreviewMode.NONE;
            if (previewHost != null) {
                previewHost.onPreviewModeChanged(previewMode, getPreviewLeft(), getPreviewWidth());
            }
            invalidate();
            notifyPreviewModeChanged();
        }
    }

    /**
     * v2.39: Returns the active popup sheet, or null when no SHEET_* mode
     * is active. Exposed so {@link EditorRenderer} and the test harness can
     * query sheet state without going through package-private fields.
     */
    public EditorPreviewSheet getPreviewSheet() {
        return previewSheet;
    }

    /**
     * v3.31.0: Pushes the current editor text to the preview host.
     * Called (debounced) after text changes when preview is active.
     *
     * <p>v2.39: Also pushes content to the popup sheet body (if any) so
     * the host's WebView can re-render Markdown/HTML live as the user types.
     */
    private void updatePreviewContent() {
        if (previewHost == null || session == null) return;
        if (previewMode == PreviewMode.NONE) return;
        previewHost.onPreviewContentChanged(session.getText());
        // v2.39: the sheet body View is owned by the host — once we've
        // pushed new content via onPreviewContentChanged, ask the sheet
        // to invalidate its body so the WebView redraws.
        if (previewSheet != null) {
            previewSheet.refreshBody();
        }
    }

    /**
     * v3.31.0: Returns true if the preview host has content ready to draw
     * <em>onto the editor canvas</em> (inline SPLIT/FULL XML layout preview).
     *
     * <p>v2.39: SHEET_* modes never draw on the editor canvas (the sheet
     * has its own surface), so this returns false for them.
     */
    public boolean isXmlPreviewActive() {
        if (previewMode.isSheet()) return false;
        return previewMode != PreviewMode.NONE
            && previewHost != null
            && previewHost.hasPreviewContent();
    }

    public PreviewMode getPreviewMode() { return previewMode; }

    /**
     * Returns the X coordinate where the preview pane starts.
     *
     * <p>v2.39: SHEET_* modes return the sheet bounds — but the editor
     * does NOT shrink its text area for sheet modes (the sheet is an
     * overlay). These values are passed to the host so it knows where
     * the sheet's body lives.
     */
    public int getPreviewLeft() {
        if (previewMode == PreviewMode.NONE) return getWidth();
        if (previewMode == PreviewMode.FULL || previewMode == PreviewMode.SHEET_FULL) return 0;
        // SPLIT or SHEET_SPLIT: preview takes the right half.
        return getWidth() / 2;
    }

    /**
     * Returns the width of the preview pane.
     *
     * <p>v2.39: For SHEET_* modes, this is the sheet body width — passed
     * to the host so it knows the canvas dimensions (when using
     * {@link EditorPreviewHost#drawPreview} as a fallback).
     */
    public int getPreviewWidth() {
        if (previewMode == PreviewMode.NONE) return 0;
        if (previewMode == PreviewMode.FULL || previewMode == PreviewMode.SHEET_FULL) return getWidth();
        // SPLIT or SHEET_SPLIT.
        return getWidth() - getWidth() / 2;
    }

    /**
     * Returns the effective text-area width available for text rendering.
     * When preview is active, this is reduced to leave room for the preview.
     *
     * <p>v2.39: SHEET_* modes do NOT reduce the text-area width — the sheet
     * floats on top of the editor, so the text wraps as if no preview
     * were active (the user can still see and edit the text by dismissing
     * the sheet).
     */
    int getEffectiveTextWidth() {
        int fullWidth = (int) (getWidth() - metrics.getGutterWidth() - metrics.getPadLeft() - metrics.getPadRight());
        if (previewMode == PreviewMode.NONE || previewMode.isSheet()) return fullWidth;
        if (previewMode == PreviewMode.FULL) return 0;
        // SPLIT: text takes the left half.
        return (int) ((getWidth() / 2) - metrics.getGutterWidth() - metrics.getPadLeft() - metrics.getPadRight());
    }

    private void notifyPreviewModeChanged() {
        if (previewModeListener != null) {
            previewModeListener.onPreviewModeChanged(previewMode, getPreviewLeft(), getPreviewWidth());
        }
    }

    /** v3.17.0: Applies an alpha multiplier to an ARGB color (for preview icons). */
    static int applyAlphaToColor(int color, float alpha) {
        int a = (color >>> 24) & 0xFF;
        int newA = (int) (a * alpha);
        return (newA << 24) | (color & 0x00FFFFFF);
    }

    /** Rebuilds the wrap model from the current document + viewport width. */
    private void rebuildWrapModel() {
        if (session == null) return;
        int lineCount = session.getDocument().lineCount();
        if (wrapModel == null || wrapModel.getLineCount() != lineCount) {
            wrapModel = new jo.codeeditor.wrap.WrapModel(lineCount);
        }
        // v3.13.0: use effective text width (reduced when preview is on).
        int textAreaW = getEffectiveTextWidth();
        wrapWidthPx = Math.max(1, textAreaW);
        // ★ v2.33 : le comptage passe par wrapRowsFor — même formule que le
        // DÉCOUPAGE du rendu (continuation indentée = rangée plus étroite).
        // AVANT : ceil(len/maxCols) — les lignes indentées repliées
        // perdaient leur queue (rangées non dessinées) et les Y des lignes
        // suivantes étaient trop courts d'autant.
        EditorDocument doc = session.getDocument();
        for (int i = 0; i < lineCount; i++) {
            int lineLen = doc.lineEnd(i) - doc.lineStart(i);
            wrapModel.setRows(i, wrapRowsFor(i, lineLen).rows);
        }
    }

    /** Public entry point for "show the keyboard now" (toolbar button / FAB). */
    public void showSoftKeyboard() {
        wantsKeyboard = true;
        requestFocus();
        InputMethodManager imm = imm();
        if (imm != null) {
            imm.showSoftInput(this, 0);
        }
    }

    /** Public entry point for "hide the keyboard now". */
    public void hideSoftKeyboard() {
        wantsKeyboard = false;
        InputMethodManager imm = imm();
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Drawing
    // ════════════════════════════════════════════════════════════════

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // v3.8.0: all drawing is delegated to EditorRenderer.
        renderer.draw(canvas);
    }
    /** Returns the collapsed fold region that STARTS at {@code docLine}, or null. */
    DiagnosticShift.FoldRegion collapsedFoldStartingAtLine(int docLine) {
        for (DiagnosticShift.FoldRegion r : session.getFoldRegions()) {
            if (!r.collapsed) continue;
            if (session.getDocument().lineForOffset(r.start) == docLine) return r;
        }
        return null;
    }

    /**
     * Overlays semantic tokens on top of the lexical syntax-highlight spans.
     * Semantic tokens come from the language server (method, class, enum, etc.)
     * and override the lexer's best-guess color when present.
     */
    static jo.codeeditor.highlight.TokenType semanticTypeToTokenType(int semType) {
        // LSP-style semantic token types: 0=namespace 1=type 2=class 3=enum
        // 4=interface 5=struct 6=parameter 7=variable 8=property 9=method
        // 10=function 11=keyword 12=number 13=string 14=comment ...
        // ★ R3 (v0.1.0.51-v2.22) : couverture élargie aux tokens que le
        // serveur Java alimente désormais (enums, params, locales, champs)
        // via la légende canonique de JdtSemanticHighlighter.TOKEN_TYPES.
        switch (semType) {
            case 1: case 2: case 3: case 5:
                return jo.codeeditor.highlight.TokenType.TYPE;
            case 4:
                return jo.codeeditor.highlight.TokenType.ANNOTATION;
            case 6: case 7:
                return jo.codeeditor.highlight.TokenType.VARIABLE;
            case 8:
                return jo.codeeditor.highlight.TokenType.PROPERTY;
            case 9: case 10: return jo.codeeditor.highlight.TokenType.FUNC;
            case 11: return jo.codeeditor.highlight.TokenType.KEYWORD;
            case 12: return jo.codeeditor.highlight.TokenType.NUMBER;
            case 13: return jo.codeeditor.highlight.TokenType.STRING;
            case 14: return jo.codeeditor.highlight.TokenType.COMMENT;
            default: return null;
        }
    }

    /**
     * Returns the cached per-line layout (StyledLine + filtered inlays +
     * filtered sem spans + raw↔visual column maps). On a cache miss the
     * filtered lists are computed from the session's global lists and
     * stored, so subsequent frames for an unchanged line are O(1).
     *
     * <p>Triple-stamp validation (text rev + inlay rev + sem rev) means
     * a single keystroke only invalidates the lines whose text actually
     * changed — not the whole viewport. LRU-evicted at 512 entries.
     */
    LineRenderCache.LineCacheEntry layoutForLine(int lineNum, String lineText) {
        if (session == null) return null;
        EditorDocument doc = session.getDocument();
        if (lineNum < 0 || lineNum >= doc.lineCount()) return null;
        int textRev = session.getLineTextRevision(lineNum);
        int inlayRev = session.getInlayHintsRevision();
        int semRev = session.getSemanticTokensRevision();
        LineRenderCache.LineCacheEntry entry = renderCache.get(lineNum, textRev, inlayRev, semRev);
        if (entry != null) return entry;
        // Cache miss — compute the filtered per-line inlays + sem spans.
        int lineStart = doc.lineStart(lineNum);
        int lineEnd = doc.lineEnd(lineNum);
        // v3.34.0: filter from the session's PER-LINE BUCKETS instead of
        // iterating the full global lists (and the old getters also made
        // a defensive copy of the whole list per call — O(hints + tokens)
        // allocations per missed line, i.e. per scroll). Bucket access is
        // O(bucket size); the index is memoized on the source list
        // identity and rebuilt once per setInlayHints/setSemanticTokens
        // or edit (CodeAssist 3.20 LineOverlay.update() approach).
        List<DiagnosticShift.InlayHint> lineHints = session.getInlayHintsForLine(lineNum);
        List<LineRenderCache.InlayPiece> inlays = new ArrayList<>(lineHints.size());
        for (DiagnosticShift.InlayHint h : lineHints) {
            int col = h.offset - lineStart;
            inlays.add(new LineRenderCache.InlayPiece(col, h.text));
        }
        // v2.32 bugfix: buildColumnMaps ASSUMES the pieces are sorted by col
        // (it advances a single index while rawCol grows). The global hint
        // list is NOT sorted per line — the server appends the var-type
        // hints AFTER the parameter hints, so a line like
        // {@code var x = max(a, b);} receives [param@17, param@20, var@7]
        // and the var hint (col < current index) was silently DROPPED from
        // the column maps → the hint never rendered and the text after it
        // wasn't shifted. Sort (stable) fixes the weave.
        if (inlays.size() > 1) {
            inlays.sort((a, b) -> Integer.compare(a.col, b.col));
        }
        // Filter semantic tokens to those that intersect this line, and
        // convert them to per-line SemSpans (column-relative + ARGB color).
        List<DiagnosticShift.SemanticToken> lineTokens = session.getSemanticTokensForLine(lineNum);
        List<LineRenderCache.SemSpan> semSpans = new ArrayList<>(lineTokens.size());
        for (DiagnosticShift.SemanticToken t : lineTokens) {
            int tokEnd = t.start + t.length;
            if (t.start >= lineEnd || tokEnd <= lineStart) continue;
            int startCol = clamp(t.start - lineStart, 0, lineText.length());
            int endCol = clamp(tokEnd - lineStart, 0, lineText.length());
            if (startCol >= endCol) continue;
            TokenType ttype = semanticTypeToTokenType(t.type);
            if (ttype == null) continue;
            int color = theme.colorForToken(ttype);
            semSpans.add(new LineRenderCache.SemSpan(startCol, endCol, color));
        }
        // Build raw↔visual column maps from the inlays.
        int[][] maps = LineRenderCache.buildColumnMaps(lineText.length(), inlays);
        StyledLine styled = null;
        List<StyledLine> styledLines = session.getStyledLines();
        if (lineNum < styledLines.size()) {
            styled = styledLines.get(lineNum);
        }
        LineRenderCache.LineCacheEntry newEntry = new LineRenderCache.LineCacheEntry(
            lineNum, textRev, inlayRev, semRev,
            inlays, semSpans, maps[0], maps[1], styled);
        renderCache.put(newEntry);
        return newEntry;
    }

    /** Returns the cached StyledLine for the given line, or null. */
    private StyledLine cachedStyledFor(int lineNum, String lineText) {
        LineRenderCache.LineCacheEntry e = layoutForLine(lineNum, lineText);
        return e != null ? (StyledLine) e.layout : null;
    }

    // ════════════════════════════════════════════════════════════════
    // v2.31: Inlay-aware column mapping (CodeAssist rawToVisual/visualToRaw)
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.31: Raw document column → VISUAL (inlay-woven) column for the given
     * line. A hint anchored AT {@code rawCol} occupies the columns between
     * {@code rawToVisual[rawCol]} and {@code rawToVisual[rawCol] + hintLen},
     * so the caret for {@code rawCol} anchors just BEFORE the hint — exactly
     * CodeAssist's {@code rawToVisual} semantics.
     *
     * <p>Falls back to the raw column when the line has no inlays, is a
     * collapsed-fold composite (hints are never woven there), or the cache
     * has no entry — so callers can use it unconditionally in draw paths.
     */
    int visualColFor(int line, int rawCol) {
        if (session == null) return rawCol;
        EditorDocument doc = session.getDocument();
        if (doc == null || line < 0 || line >= doc.lineCount()) return rawCol;
        LineRenderCache.LineCacheEntry e = layoutForLine(line, doc.lineText(line));
        if (e == null || e.inlays.isEmpty() || e.rawToVisual == null) return rawCol;
        if (collapsedFoldStartingAtLine(line) != null) return rawCol; // composite: identity
        if (rawCol < 0) return 0;
        if (rawCol >= e.rawToVisual.length) return e.rawToVisual[e.rawToVisual.length - 1];
        return e.rawToVisual[rawCol];
    }

    /**
     * v2.31: VISUAL (inlay-woven) column → raw document column. A hit inside
     * a hint snaps to its anchor column (CodeAssist {@code visualToRaw}
     * semantics) so tapping a hint places the caret at the hinted character.
     */
    int rawColFor(int line, int visualCol) {
        if (session == null) return visualCol;
        EditorDocument doc = session.getDocument();
        if (doc == null || line < 0 || line >= doc.lineCount()) return visualCol;
        int lineLen = doc.lineEnd(line) - doc.lineStart(line);
        LineRenderCache.LineCacheEntry e = layoutForLine(line, doc.lineText(line));
        if (e == null || e.inlays.isEmpty() || e.visualToRaw == null) return visualCol;
        if (collapsedFoldStartingAtLine(line) != null) return visualCol;
        if (visualCol <= 0) return 0;
        if (visualCol >= e.visualToRaw.length) return lineLen;
        return e.visualToRaw[visualCol];
    }

    // ════════════════════════════════════════════════════════════════
    // v2.31: Diagnostic sheet / chip geometry (shared by draw + hit-test)
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.31: Word-wraps {@code msg} with the diagnostic-sheet text paint and
     * returns the number of visual lines (min 1). Used by BOTH the draw pass
     * and the hit-test so they always agree on the panel height.
     */
    int countWrappedLines(String msg, float maxW) {
        if (msg == null || msg.isEmpty()) return 1;
        int lines = 1;
        StringBuilder cur = new StringBuilder();
        for (String word : msg.split("\\s+")) {
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (textPaint.measureText(test) > maxW && cur.length() > 0) {
                lines++;
                cur = new StringBuilder(word);
            } else {
                cur = cur.length() == 0 ? new StringBuilder(word) : cur.append(" ").append(word);
            }
        }
        return lines;
    }

    /**
     * v2.31: Shared geometry of the diagnostic sheet (the CodeAssist
     * DiagnosticSheet port). Returns {@code null} when nothing is showing,
     * else {@code [panelTop, panelBottom, actionStartY, actionRowH,
     * closeCx, closeCy, closeR]} — the single source of truth used by the
     * renderer AND the tap hit-test.
     */
    float[] diagnosticSheetMetrics() {
        if (!diagnosticPopupVisible || diagnosticPopupItem == null || session == null) return null;
        DiagnosticShift.Diagnostic d = diagnosticPopupItem;
        float density = getResources().getDisplayMetrics().density;
        int line = session.getDocument().lineForOffset(d.start);
        List<CodeAction> actions = codeActionsByLine.get(line);
        int actionCount = actions != null ? actions.size() : 0;
        String msg = d.message != null ? d.message : "";
        textPaint.setTypeface(metrics.getTypeface());
        textPaint.setTextSize(metrics.getTextSize() * 0.85f);
        int msgLines = Math.min(countWrappedLines(msg, getWidth() - 24 * density),
            DIAG_SHEET_MAX_MSG_LINES);
        textPaint.setTextSize(metrics.getTextSize());
        float headerH = DIAG_SHEET_HEADER_DP * density;
        float msgH = Math.max(1, msgLines) * DIAG_SHEET_MSG_LINE_DP * density;
        float actionsH = actionCount > 0
            ? DIAG_SHEET_ACTIONS_BLOCK_DP * density + actionCount * DIAG_SHEET_ACTION_ROW_DP * density
            : 0;
        float sheetH = headerH + msgH + actionsH + DIAG_SHEET_BOTTOM_PAD_DP * density;
        float panelTop = Math.max(0, getHeight() - sheetH);
        float actionStartY = panelTop + headerH + msgH + DIAG_SHEET_ACTIONS_BLOCK_DP * density;
        return new float[]{
            panelTop, getHeight(), actionStartY, DIAG_SHEET_ACTION_ROW_DP * density,
            getWidth() - 30 * density, panelTop + headerH * 0.5f, 15 * density};
    }

    /**
     * v2.34 — géométrie partagée + liste d'actions de la toolbar flottante de
     * sélection (portage {@code SelectionToolbar} de CodeAssist). SOURCE
     * UNIQUE de vérité pour le rendu ({@code EditorRenderer.drawSelectionToolbar})
     * ET le hit-test ({@code EditorInputHandler}) — avant v2.34, le calcul de
     * layout était dupliqué des deux côtés (risque de divergence).
     *
     * <p>Jeu d'actions (parité CodeAssist {@code EditorOverlays.kt}) :</p>
     * <ul>
     *   <li><b>Copy / Cut</b> — uniquement si la sélection est non-collapsed ;</li>
     *   <li><b>Paste / Select all</b> — toujours (mode collapsed : re-tap sur
     *       le caret, comme le toggle {@code reTap} de CodeAssist) ;</li>
     *   <li><b>divider</b> — si au moins un bouton icône est présent ;</li>
     *   <li><b>Docs ℹ</b> — si un quick-doc resolver est câblé ;</li>
     *   <li><b>Actions ⋯</b> — si des quick-fixes existent pour la ligne de la
     *       sélection ({@code codeActionsByLine} — équivalent CodeAssist du
     *       menu contextuel Quick fixes).</li>
     * </ul>
     */
    SelectionToolbarMetrics selectionToolbarMetrics() {
        if (!selectionToolbarVisible || session == null) return null;
        Selection sel = session.getSelection();
        float density = getResources().getDisplayMetrics().density;

        // Ancre = extrémité ACTIVE de la sélection (le bout qui suit le
        // doigt — parité CodeAssist geometry.caretGeometry(selActive)).
        // Inlay-aware + fold-aware via caretScreenPos.
        float[] anchor = caretScreenPos(sel.end);
        float anchorScreenX = anchor[0];
        float anchorScreenY = anchor[1];

        // ★ v2.36 — parité CodeAssist EXACTE : CodeEditor.kt fournit TOUJOURS
        // onDocs ET onMenu non-null — la pill affiche donc en permanence
        // Copy/Cut (si sélection) + Paste + Select all | ℹ Docs | ⋯ Actions.
        // Avant, les icônes étaient masquées sans quick-fixes sur la ligne :
        // l'utilisateur perdait l'accès à Docs et au menu GO TO.
        boolean hasSelection = !sel.isCursor();

        // ── Construction de la liste d'actions ──────────────────────
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        textPaint.setTextSize(13 * density);
        SelectionToolbarMetrics m = new SelectionToolbarMetrics();
        m.padX = 12 * density;
        m.padY = 8 * density;
        m.btnGap = 2 * density;
        m.h = 14 * density + 2 * m.padY;
        m.radius = m.h * 0.5f;
        float iconW = 32 * density; // 16dp icon + 2×8dp padding

        if (hasSelection) {
            m.addText(SEL_ACT_COPY, "Copy", textPaint.measureText("Copy"));
            m.addText(SEL_ACT_CUT, "Cut", textPaint.measureText("Cut"));
        }
        m.addText(SEL_ACT_PASTE, "Paste", textPaint.measureText("Paste"));
        m.addText(SEL_ACT_SELECT_ALL, "Select all", textPaint.measureText("Select all"));
        m.addIcon(SEL_ACT_DOCS);
        m.addIcon(SEL_ACT_ACTIONS);
        m.dividerCount = 1;
        float dividerW = 6 * density;

        // ── Layout provisionnel (une passe) + largeur totale ────────
        // Le divider occupe un gap dédié (dividerW) à la transition entre
        // le groupe texte et le groupe icônes — pas de chevauchement.
        m.applyIconWidth(iconW);
        float cursor = 0;
        for (int i = 0; i < m.count; i++) {
            m.itemX[i] = cursor;
            cursor += m.itemW[i];
            boolean dividerBefore = m.dividerCount > 0 && m.isIcon[i]
                    && i > 0 && !m.isIcon[i - 1];
            cursor += (i == m.count - 1) ? 0 : (dividerBefore ? dividerW : m.btnGap);
        }
        float totalW = cursor;
        if (m.dividerCount > 0) {
            m.dividerX = m.firstIconX() - dividerW * 0.5f;
        }

        // ── Position de la pill (ancre centrée, clamp viewport) ─────
        m.w = totalW;
        m.x = anchorScreenX - totalW * 0.5f;
        if (m.x < 4) m.x = 4;
        if (m.x + totalW > getWidth() - 4) m.x = getWidth() - totalW - 4;
        if (m.x < 0) m.x = 0; // viewport plus étroit que la pill
        m.y = anchorScreenY - m.h - 6 * density;
        if (m.y < 4) {
            // Pas de place au-dessus — bascule SOUS la ligne (amélioration
            // locale conservée : CodeAssist clampe à 0).
            m.y = anchorScreenY + metrics.getLineHeight() + 6 * density;
        }
        // Décalage du layout provisionnel vers la position finale.
        for (int i = 0; i < m.count; i++) {
            m.itemX[i] += m.x;
        }
        m.dividerX += m.x;
        textPaint.setTypeface(metrics.getTypeface());
        textPaint.setTextSize(metrics.getTextSize());
        return m;
    }

    /**
     * v2.34 — géométrie de la toolbar de sélection : la pill + les items
     * actionnables (texte ou icône) + les positions des dividers. Consommée
     * par le rendu et le hit-test (une seule source de layout).
     */
    static final class SelectionToolbarMetrics {
        float x, y;                       // coin haut-gauche de la pill
        float w, h;                       // dimensions de la pill
        float radius;                     // rayon (pill complète)
        float padX, padY, btnGap;
        int count;                        // items actionnables
        final float[] itemX = new float[8];
        final float[] itemW = new float[8];
        final int[] action = new int[8];
        final String[] label = new String[8]; // null pour un item icône
        final boolean[] isIcon = new boolean[8];
        float dividerX = Float.MIN_VALUE;  // 1 divider max (avant les icônes)
        int dividerCount;

        void addText(int act, String text, float width) {
            itemX[count] = -1; // rempli par la passe de layout
            itemW[count] = width + 2 * padX;
            action[count] = act;
            label[count] = text;
            isIcon[count] = false;
            count++;
        }

        void addIcon(int act) {
            itemX[count] = -1;
            itemW[count] = 0; // rempli par applyIconWidth(iconW)
            action[count] = act;
            label[count] = null;
            isIcon[count] = true;
            count++;
        }

        /** La position (provisionnelle) du premier item icône. */
        float firstIconX() {
            for (int i = 0; i < count; i++) {
                if (isIcon[i]) return itemX[i];
            }
            return Float.MIN_VALUE;
        }

        /** Applique la largeur réelle des items icônes. */
        void applyIconWidth(float iconW) {
            for (int i = 0; i < count; i++) {
                if (isIcon[i]) itemW[i] = iconW;
            }
        }

        /** True si (x, y) est dans la pill (le geste y est englouti). */
        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        /**
         * L'INDEX d'item à (px, py), ou -1. Les dividers et les gaps entre
         * items ne sont pas actionnables (parité CodeAssist : seuls les items
         * ont un onClick).
         */
        int itemIndexAt(float px, float py) {
            if (!contains(px, py)) return -1;
            for (int i = 0; i < count; i++) {
                if (px >= itemX[i] && px < itemX[i] + itemW[i]) return i;
            }
            return -1;
        }

        /** L'action à (px, py), ou -1 (délègue à {@link #itemIndexAt}). */
        int actionAt(float px, float py) {
            int i = itemIndexAt(px, py);
            return i < 0 ? -1 : action[i];
        }
    }

    // ════════════════════════════════════════════════════════════════
    // v2.36 : NavMenu — métriques du menu contextuel unifié
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.36 — construit la liste ordonnée des rangées du menu (pattern
     * NavigationMenu.kt de CodeAssist) : sections en majuscules affichées
     * seulement si non-vides, ou « Nothing found in source. ».
     */
    List<NavMenuRow> navMenuRows() {
        List<NavMenuRow> rows = new ArrayList<>();
        if (navMenuResultsMode) {
            if (navMenuTargets.isEmpty()) {
                rows.add(new NavMenuRow(NavMenuRow.TYPE_NOTHING, 0, 0, null));
            } else {
                for (int i = 0; i < navMenuTargets.size(); i++) {
                    rows.add(new NavMenuRow(NavMenuRow.TYPE_TARGET, i, 0,
                            navMenuTargets.get(i)));
                }
            }
            return rows;
        }
        boolean empty = navMenuOptions.isEmpty()
                && navMenuQuickFixes.isEmpty()
                && navMenuIntentions.isEmpty();
        if (empty) {
            rows.add(new NavMenuRow(NavMenuRow.TYPE_NOTHING, 0, 0, null));
            return rows;
        }
        if (!navMenuOptions.isEmpty()) {
            rows.add(new NavMenuRow(NavMenuRow.TYPE_HEADER, 0,
                    NavMenuRow.SECTION_GO_TO, "GO TO"));
            for (int i = 0; i < navMenuOptions.size(); i++) {
                rows.add(new NavMenuRow(NavMenuRow.TYPE_OPTION, i,
                        NavMenuRow.SECTION_GO_TO, navMenuOptions.get(i)));
            }
        }
        if (!navMenuQuickFixes.isEmpty()) {
            rows.add(new NavMenuRow(NavMenuRow.TYPE_HEADER, 0,
                    NavMenuRow.SECTION_QUICK_FIXES, "QUICK FIXES"));
            for (int i = 0; i < navMenuQuickFixes.size(); i++) {
                rows.add(new NavMenuRow(NavMenuRow.TYPE_ACTION, i,
                        NavMenuRow.SECTION_QUICK_FIXES, navMenuQuickFixes.get(i)));
            }
        }
        if (!navMenuIntentions.isEmpty()) {
            rows.add(new NavMenuRow(NavMenuRow.TYPE_HEADER, 0,
                    NavMenuRow.SECTION_INTENTIONS, "INTENTIONS"));
            for (int i = 0; i < navMenuIntentions.size(); i++) {
                rows.add(new NavMenuRow(NavMenuRow.TYPE_ACTION, i,
                        NavMenuRow.SECTION_INTENTIONS, navMenuIntentions.get(i)));
            }
        }
        return rows;
    }

    /**
     * v2.36 — l'action d'une rangée TYPE_ACTION, résolue depuis sa section.
     */
    CodeAction navMenuActionAt(NavMenuRow row) {
        return row.ref instanceof CodeAction ? (CodeAction) row.ref : null;
    }

    /**
     * v2.36 — hauteur totale du CONTENU du menu (sans clamp viewport) en
     * pixels : headers + rangées. Source du clamp de scroll.
     */
    float navMenuContentHeight() {
        float density = getResources().getDisplayMetrics().density;
        float rowH = NAV_MENU_ROW_HEIGHT_DP * density;
        float headerH = NAV_MENU_HEADER_HEIGHT_DP * density;
        float h = 0;
        for (NavMenuRow r : navMenuRows()) {
            h += r.type == NavMenuRow.TYPE_HEADER ? headerH : rowH;
        }
        return h;
    }

    /**
     * v2.36 — géométrie du popup : {x, y, w, h} du rectangle, ancré SOUS la
     * ligne du caret (parité NavMenuLayer : caretX clampé au gutter, bas de
     * ligne + 6dp, marge 8dp, bascule AU-DESSUS si le bas déborde). La
     * hauteur visible est bornée à NAV_MENU_MAX_HEIGHT_DP (360) — le contenu
     * déborde via navMenuScrollY.
     */
    float[] navMenuMetrics() {
        if (!navMenuVisible || navMenuLine < 0) return null;
        float density = getResources().getDisplayMetrics().density;
        float contentH = navMenuContentHeight();
        float maxH = NAV_MENU_MAX_HEIGHT_DP * density;
        float h = Math.min(contentH, maxH);
        float w = Math.min(NAV_MENU_MAX_WIDTH_DP * density,
                Math.max(NAV_MENU_MIN_WIDTH_DP * density,
                        (getWidth() - 2 * NAV_MENU_MARGIN_DP * density) * 0.9f));
        // Ancre : X du caret (clampé ≥ gutter + marge), bas de la ligne.
        float[] caret = caretScreenPos(navMenuCaretOffset);
        float anchorX = Math.max(metrics.getGutterWidth() + NAV_MENU_MARGIN_DP * density,
                caret[0]);
        float anchorY = docLineToY(navMenuLine) - vOffset
                + metrics.getLineHeight() + NAV_MENU_GAP_DP * density;
        float x = anchorX - NAV_MENU_MARGIN_DP * density;
        if (x + w > getWidth() - NAV_MENU_MARGIN_DP * density) {
            x = getWidth() - NAV_MENU_MARGIN_DP * density - w;
        }
        if (x < NAV_MENU_MARGIN_DP * density) x = NAV_MENU_MARGIN_DP * density;
        float y = anchorY;
        if (y + h > getHeight() - NAV_MENU_MARGIN_DP * density) {
            // Pas de place en dessous — bascule AU-DESSUS de la ligne.
            y = docLineToY(navMenuLine) - vOffset - h - NAV_MENU_GAP_DP * density;
        }
        if (y < NAV_MENU_MARGIN_DP * density) y = NAV_MENU_MARGIN_DP * density;
        return new float[]{x, y, w, h};
    }

    /**
     * v2.31: The diagnostic CHIP pill geometry for {@code d} on {@code line}
     * (CodeAssist DiagnosticChip port): a severity-tinted pill placed after
     * the line end + a 3-char gap, vertically centred on the line. Returns
     * {@code [x, y, w, h]} or null when the pill would be fully off-screen.
     * Single source of truth for the draw pass and the tap hit-test.
     */
    float[] diagnosticChipMetrics(DiagnosticShift.Diagnostic d, int line) {
        if (session == null) return null;
        EditorDocument doc = session.getDocument();
        if (line < 0 || line >= doc.lineCount()) return null;
        float density = getResources().getDisplayMetrics().density;
        float charWidth = metrics.getCharWidth();
        float lineHeight = metrics.getLineHeight();
        int lineLen = doc.lineEnd(line) - doc.lineStart(line);
        float chipX;
        float y;
        if (wordWrap && wrapModel != null) {
            // ★ v2.33 — CodeAssist DiagnosticChipsLayer : la chip se place
            // après la FIN de la DERNIÈRE rangée repliée (lastSub), pas sur
            // la première ni à la longueur NON repliée (l'ancienne approximation
            // posait la pill au-delà du bord droit → pill invisible).
            WrapRows wr = wrapRowsFor(line, lineLen);
            int lastRow = wr.rows - 1;
            float textAreaLeft = metrics.getGutterWidth() + metrics.getPadLeft();
            if (lastRow == 0) {
                // Rangée unique : les inlays sont tissés → colonne VISUELLE.
                int visualLen = visualColFor(line, lineLen);
                chipX = textAreaLeft + visualLen * charWidth
                        + charWidth * DIAG_CHIP_GAP_CHARS;
            } else {
                int rowStart = wr.rowStartCol(lastRow);
                int rowEnd = wr.rowEndCol(lastRow, lineLen);
                chipX = textAreaLeft + wr.wrapIndentCols * charWidth
                        + (rowEnd - rowStart) * charWidth
                        + charWidth * DIAG_CHIP_GAP_CHARS;
            }
            y = docLineToY(line) + lastRow * lineHeight - vOffset;
        } else {
            // Inlay-aware visual line length so the chip never covers a hint.
            int visualLen = visualColFor(line, lineLen);
            chipX = metrics.getGutterWidth() + metrics.getPadLeft()
                + visualLen * charWidth - hOffset + charWidth * DIAG_CHIP_GAP_CHARS;
            y = docLineToY(line) - vOffset;
        }
        if (y + lineHeight < 0 || y > getHeight()) return null;
        // Content-sized pill height (~1.24em, CodeAssist) centred in the row.
        float pillH = metrics.getTextSize() * 1.25f;
        float pillY = y + (lineHeight - pillH) * 0.5f;
        // Message truncated with an ellipsis so the pill fits the viewport.
        textPaint.setTypeface(metrics.getTypeface());
        textPaint.setTextSize(metrics.getTextSize());
        textPaint.setFakeBoldText(true);
        String msg = d.message != null ? d.message : "";
        float padX = 6 * density;
        float iconR = metrics.getTextSize() * 0.26f;
        float iconGap = 5 * density;
        float avail = getWidth() - chipX - 4 * density - padX * 2 - iconR * 2 - iconGap;
        String label = msg;
        if (label.length() > 90) label = label.substring(0, 88) + "…";
        while (label.length() > 1 && textPaint.measureText(label) > avail) {
            label = label.substring(0, label.length() - 2) + "…";
        }
        textPaint.setFakeBoldText(false);
        float textW = textPaint.measureText(label);
        float pillW = padX + iconR * 2 + iconGap + textW + padX;
        if (chipX + pillW < metrics.getGutterWidth()) return null; // fully under the gutter
        return new float[]{chipX, pillY, pillW, pillH, iconR, iconGap, padX};
    }

    /**
     * v2.31: The most severe Error/Warning diagnostic whose start sits on
     * {@code line} — the one that gets a chip (CodeAssist parity: Info/Hint
     * stay squiggle+gutter only). Exposed for the chip hit-test.
     */
    DiagnosticShift.Diagnostic chipDiagnosticForLine(int line) {
        if (session == null) return null;
        EditorDocument doc = session.getDocument();
        if (doc == null || line < 0 || line >= doc.lineCount()) return null;
        int lineStart = doc.lineStart(line);
        int lineEnd = doc.lineEnd(line);
        DiagnosticShift.Diagnostic best = null;
        for (DiagnosticShift.Diagnostic d : session.getDiagnostics()) {
            if (d.severity != 3 && d.severity != 2) continue;
            if (d.start < lineStart || d.start > lineEnd) continue;
            if (best == null || d.severity > best.severity) best = d;
        }
        return best;
    }

    /**
     * v2.31: The diagnostic chip under the screen point (x, y), or null —
     * drives the chip tap → sheet interaction (CodeAssist
     * DiagnosticChipsLayer's {@code onClick = onOpenSheet}).
     */
    DiagnosticShift.Diagnostic findDiagnosticChipAt(float x, float y) {
        if (!diagnosticChipsEnabled || session == null) return null;
        EditorDocument doc = session.getDocument();
        int line = docLineForScreenY(y);
        if (line < 0 || line >= doc.lineCount()) return null;
        if (isLineFoldedCached(line)) return null;
        DiagnosticShift.Diagnostic d = chipDiagnosticForLine(line);
        if (d == null) return null;
        float[] m = diagnosticChipMetrics(d, line);
        if (m == null) return null;
        if (x >= m[0] && x <= m[0] + m[2] && y >= m[1] && y <= m[1] + m[3]) return d;
        return null;
    }

    /**
     * v3.33.10: Helper kept for callers that previously went through
     * EditorView (EditorScrollManager, etc.). Delegates to
     * {@link CaretAnimator#cancelGlide()}.
     */
    void cancelCaretAnimation() {
        caretAnim.cancelGlide();
    }

    // ════════════════════════════════════════════════════════════════
    // IME integration
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
        return imeBridge.onCreateInputConnection(outAttrs);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            // Don't pop the keyboard back up if the user switched away.
            // We keep wantsKeyboard armed so on resume we can re-show,
            // but we don't actively grab focus.
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) {
            // Losing focus clears the explicit-keyboard-request flag — a
            // passive refocus (sheet closing, returning from another
            // screen) stays silent.
            wantsKeyboard = false;
            hideSoftKeyboard();
            // v2.38 — parité Sora EditorFocusChangeEvent : la perte de
            // focus referme le hover/quick doc.
            if (quickDocVisible) dismissQuickDoc();
        } else if (wantsKeyboard) {
            InputMethodManager imm = imm();
            if (imm != null) imm.showSoftInput(this, 0);
        }
    }

    /**
     * Public hook for the host to signal that a programmatic edit was
     * applied to the session (e.g. via {@link EditorSession#replaceRange}
     * outside the IME path). Refreshes the caret blink, scroll-into-view,
     * completion popup, and signature help — the same bookkeeping the IME
     * path does automatically.
     *
     * @since v1.0.8
     */
    public void notifyTextChanged() {
        onTextChanged();
        // v3.20.1: Invalidate parent EditorBarTools so undo/redo button
        // enabled/disabled states update after every edit.
        android.view.ViewParent p = getParent();
        if (p instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup) p;
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (parent.getChildAt(i) instanceof EditorBarTools) {
                    EditorBarTools bar = (EditorBarTools) parent.getChildAt(i);
                    for (int j = 0; j < bar.getChildCount(); j++) {
                        bar.getChildAt(j).invalidate();
                    }
                }
            }
        }
    }

    /** Called by the InputConnection after every edit so we can refresh
     *  the caret solid timer and scroll the caret into view. Also refreshes
     *  the completion popup (if visible). */
    void onTextChanged() {
        // v3.33.10: Single entry point — CaretAnimator.onEditOrMove() updates
        // lastEditTime, resets the blink toggle, makes the caret visible, and
        // cancels any in-flight glide. (cancelCaretAnimation() below is now
        // redundant but kept for clarity — onEditOrMove() already cancels.)
        caretAnim.onEditOrMove();
        // Rebuild the wrap model — the document's line count or content may
        // have changed, and that affects per-line wrap-row counts.
        if (wordWrap) rebuildWrapModel();
        scrollManager.scrollCaretIntoView();
        refreshCompletion();
        refreshSignatureHelp();
        // v3.3.10: Re-run diagnostics (debounced) so squiggles update as
        // the user types. Previously diagnostics were only computed ONCE
        // 500ms after setLanguage and never refreshed.
        scheduleDiagnostics();
        // v3.15.0: Refresh code actions (debounced) — was previously called
        // every frame from the draw path, blocking the UI on LSP calls.
        scheduleCodeActionsRefresh();
        // v3.33.11: Refresh inlay hints (debounced) — type annotations may
        // change as the user types (e.g. var x = ...).
        scheduleInlayHints();
        // v3.33.11: Refresh document highlights (debounced) — occurrences of
        // the symbol under the caret may have changed.
        scheduleDocumentHighlights();
        // v2.32: matching-bracket highlight — the edit may have added or
        // removed the bracket at the caret, recompute synchronously.
        updateBracketPair();
        // v3.31.0: Update preview content (debounced via postDelayed).
        if (previewMode != PreviewMode.NONE && previewHost != null) {
            getHandler().removeCallbacks(previewUpdateTask);
            getHandler().postDelayed(previewUpdateTask, 200);
        }
        // Quick doc + code actions + selection toolbar dismiss on edit.
        if (quickDocVisible) dismissQuickDoc();
        if (codeActionsPopupVisible) dismissCodeActions();
        if (diagnosticPopupVisible) dismissDiagnosticPopup();
        // v2.36 : le menu contextuel unifié referme aussi sur édition.
        if (navMenuVisible) dismissNavMenu();
        // v1.0.8 bugfix (Bug 5): dismiss the selection toolbar on edit —
        // the selection may have moved or been replaced.
        // v2.34 : reset aussi l'item pressé (feedback) + les poignées.
        // v2.35 : cancel aussi le tap-dismiss différé — committer un caret
        // tapé AVANT l'édition déplacerait le caret après l'insert.
        selectionToolbarVisible = false;
        selectionToolbarPressedIdx = -1;
        handlesVisible = false;
        inputHandler.cancelPendingTapDismiss();
        invalidate();
    }

    // ════════════════════════════════════════════════════════════════
    // Touch & gestures
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return inputHandler.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        inputHandler.computeScroll();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    /**
     * G9h: Right-click (secondary mouse button) opens a context menu with
     * Copy / Cut / Paste / Select All / Undo / Redo. Desktop / ChromeOS / DeX
     * dispatch right-clicks as {@code ACTION_BUTTON_PRESS} with
     * {@code getActionButton() == BUTTON_SECONDARY}.
     *
     * @since v1.0.9 (G9h)
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (inputHandler.onGenericMotionEvent(event)) return true;
        return super.onGenericMotionEvent(event);
    }

    /**
     * Builds and shows an Android {@link android.widget.PopupMenu} with the
     * standard editor actions. Used by G9h (right-click) and can be called
     * directly by the host for a hardware menu key.
     *
     * @since v1.0.9 (G9h)
     */
    public void showEditorContextMenu(float anchorX, float anchorY) {
        inputHandler.showEditorContextMenu(anchorX, anchorY);
    }

    /**
     * Returns the screen-space coordinates of the given offset's caret, used
     * to position selection handles.
     */
    float[] caretScreenPos(int offset) {
        EditorDocument doc = session.getDocument();
        int line = clamp(doc.lineForOffset(offset), 0, doc.lineCount() - 1);
        int col = offset - doc.lineStart(line);
        float charWidth = metrics.getCharWidth();
        float x;
        float y = docLineToY(line) - vOffset;
        if (wordWrap && wrapModel != null) {
            // ★ v2.33 — mapping rangée/colonne via wrapRowsFor (les rangées de
            // continuation sont PLUS ÉTROITES que la première — l'ancien
            // col/maxColsPerRow plaçait le caret une rangée trop haut dès
            // que l'indent de continuation > 0).
            WrapRows wr = wrapRowsFor(line, doc.lineEnd(line) - doc.lineStart(line));
            int rowInLine = wr.rowForCol(col);
            int colInRow = col - wr.rowStartCol(rowInLine);
            y += rowInLine * metrics.getLineHeight();
            x = metrics.getGutterWidth() + metrics.getPadLeft()
                    + (rowInLine > 0 ? wr.wrapIndentCols * charWidth : 0)
                    + colInRow * charWidth;
        } else {
            // v3.7.1 Bugfix (Bug 4): fold-aware caret X. Default to the
            // standard col*charWidth computation; if the caret lands on a
            // fold-start line past the prefix, or on a fold-end line suffix,
            // override x with the visible-position computation.
            // v2.31: inlay-aware — the caret anchors at the VISUAL (woven)
            // column, i.e. just BEFORE a hint anchored at its raw column
            // (CodeAssist rawToVisual semantics).
            x = metrics.getGutterWidth() + metrics.getPadLeft()
                + visualColFor(line, col) * charWidth - hOffset;

            // Fold-start line: caret past the prefix snaps to the right edge
            // of the prefix (just before the {...} chip) so the caret isn't
            // drawn on top of the chip.
            DiagnosticShift.FoldRegion fold = collapsedFoldStartingAtLine(line);
            if (fold != null) {
                int startLine = doc.lineForOffset(fold.start);
                int prefixEndCol = fold.start - doc.lineStart(startLine);
                if (col > prefixEndCol) {
                    x = metrics.getGutterWidth() + metrics.getPadLeft()
                        + prefixEndCol * charWidth - hOffset;
                }
            } else if (session != null) {
                // Fold-end line: if col is in the suffix region, remap X to
                // prefixW + placeholderW + colInSuffix*charWidth so the caret
                // renders at the correct visible position on the composite line.
                for (DiagnosticShift.FoldRegion r : session.getFoldRegions()) {
                    if (!r.collapsed) continue;
                    int eLine = doc.lineForOffset(r.end);
                    if (eLine != line) continue;
                    int sLine = doc.lineForOffset(r.start);
                    String firstLine = doc.lineText(sLine);
                    String lastLine = line < doc.lineCount() ? doc.lineText(line) : "";
                    int prefixEnd = clamp(r.start - doc.lineStart(sLine), 0, firstLine.length());
                    int suffixStart = clamp(r.end - doc.lineStart(line), 0, lastLine.length());
                    int suffixLen = lastLine.length() - suffixStart;
                    if (col >= suffixStart && col <= suffixStart + suffixLen) {
                        int colInSuffix = col - suffixStart;
                        x = metrics.getGutterWidth() + metrics.getPadLeft()
                            + (prefixEnd + r.placeholder.length() + colInSuffix) * charWidth - hOffset;
                    }
                    break; // Only one fold can end on a given line.
                }
            }
        }
        return new float[]{x, y};
    }

    /** Draws the selection handles as filled circles below the anchor lines. */
    /** Maps a screen (x, y) to a document offset, clamped to the line's end.
     *  v1.0.8 bugfix: the previous code double-subtracted padLeft+gutterWidth
     *  (once in offsetAt, once inside xToCol), which shifted every tap to the
     *  left by ~5.5 chars. Now we compute col directly from the text-area
     *  relative X — no delegation to xToCol.
     *
     *  <p>v3.7.1 bugfix (Bug 4): when the tapped line is a fold-START line
     *  (i.e. has a collapsed fold region like {@code public int add(int a, int b) {...}}),
     *  the visible text is only {@code prefix + placeholder + suffix} (e.g.
     *  {@code public int add(int a, int b) {...}}). The previous code clamped
     *  col to the FULL doc-line length, which meant tapping past the visible
     *  end placed the caret on a hidden column (inside the {@code {...}} body
     *  that the user can't see). Now we clamp to the composite visible length
     *  so the caret can only land on visible positions: either inside the
     *  prefix, on the placeholder chip, or inside the suffix. We also support
     *  the user wanting to place the caret RIGHT AFTER the {@code {...}} chip
     *  (on the suffix, e.g. the closing {@code }}) — that's a valid visible
     *  position and should be reachable. */
    int offsetAt(float x, float y) {
        EditorDocument doc = session.getDocument();
        int line;
        int col;
        float charWidth = metrics.getCharWidth();
        float textAreaLeft = metrics.getGutterWidth() + metrics.getPadLeft();
        if (wordWrap && wrapModel != null) {
            // Wrap mode: figure out which doc line + which row in that line.
            line = clamp(docLineForScreenY(y), 0, doc.lineCount() - 1);
            float lineTopY = docLineToY(line);
            int lineLen = doc.lineEnd(line) - doc.lineStart(line);
            WrapRows wr = wrapRowsFor(line, lineLen);
            int rowInLine = (int) ((y + vOffset - lineTopY) / metrics.getLineHeight());
            rowInLine = clamp(rowInLine, 0, wr.rows - 1);
            // ★ v2.33 : inversion fidèle de la géométrie de pliage — la
            // rangée de continuation est indentée et plus étroite ; le tap
            // y atterrit sur la BONNE colonne (l'ancien col*maxColsPerRow
            // décalait le caret vers la droite sur les rangées indentées).
            float rowX = rowInLine > 0
                    ? textAreaLeft + wr.wrapIndentCols * charWidth : textAreaLeft;
            int colInRow = (int) ((x - rowX) / charWidth + 0.5f);
            int rowCap = rowInLine == 0 ? wr.maxColsPerRow : wr.colsPerCont;
            colInRow = clamp(colInRow, 0, rowCap);
            col = wr.rowStartCol(rowInLine) + colInRow;
            col = clamp(col, 0, lineLen);
        } else {
            // v3.7.0 Bugfix (Bug 1 CRITIQUE): Non-wrap path used raw
            // (contentY - padTop) / lineHeight to compute the doc line, which
            // is NOT fold-aware — when folds above the tap point are collapsed,
            // the returned line was shifted UP by the number of hidden lines,
            // causing text insertion INSIDE the collapsed region (caret landed
            // on a hidden line, typed text was invisible until the fold was
            // expanded). Now route through the same fold-aware mapper that the
            // wrap branch and handleTap fold-strip path already use.
            line = clamp(docLineForScreenY(y), 0, doc.lineCount() - 1);
            // Column from text-area-relative X (account for horizontal scroll).
            // textAreaLeft is where col 0 is drawn on screen (minus hOffset).
            float colScreenX = textAreaLeft - hOffset;
            int visualCol = (int) ((x - colScreenX) / charWidth + 0.5f);
            // v2.31: inlay-aware — map the tapped VISUAL column back to the
            // raw document column; a hit inside a hint snaps to its anchor
            // (CodeAssist visualToRaw semantics).
            col = rawColFor(line, visualCol);

            // v3.7.1 Bugfix (Bug 4): if this line has a collapsed fold, clamp
            // col to the COMPOSITE visible length (prefix + placeholder + suffix),
            // not the full doc-line length. This lets the user tap right after
            // the {...} chip — the caret lands at the start of the suffix
            // (e.g. on the closing }) instead of disappearing into the hidden
            // body of the fold.
            DiagnosticShift.FoldRegion fold = collapsedFoldStartingAtLine(line);
            if (fold != null) {
                int startLine = doc.lineForOffset(fold.start);
                int endLine = doc.lineForOffset(fold.end);
                String firstLine = doc.lineText(startLine);
                String lastLine = endLine < doc.lineCount() ? doc.lineText(endLine) : "";
                int prefixEndCol = clamp(fold.start - doc.lineStart(startLine), 0, firstLine.length());
                int suffixStartCol = clamp(fold.end - doc.lineStart(endLine), 0, lastLine.length());
                int suffixLen = lastLine.length() - suffixStartCol;
                int compositeLen = prefixEndCol + fold.placeholder.length() + suffixLen;
                // Clamp the tapped col to the composite visible length.
                col = clamp(col, 0, compositeLen);
                // Now remap the visible col to a doc offset:
                // - col ∈ [0, prefixEndCol] → doc col = col (prefix region)
                // - col ∈ [prefixEndCol, prefixEndCol + placeholder.length()] →
                //   snap to fold.end (just before the suffix, i.e. on the }).
                //   This is the "right after the {...}" position the user wants.
                // - col ∈ [prefixEndCol + placeholder.length(), compositeLen] →
                //   doc col = suffixStartCol + (col - prefixEndCol - placeholder.length())
                //   (somewhere inside the suffix)
                if (col <= prefixEndCol) {
                    // Prefix region — col is already the doc col.
                } else if (col <= prefixEndCol + fold.placeholder.length()) {
                    // v3.17.0: Tapped on the placeholder chip — place the caret
                    // AFTER the chip (at the start of the suffix, i.e. on the }).
                    // Previously snapped to prefixEndCol (before the chip) which
                    // made the caret invisible to the user.
                    // Map to the END line's suffix start.
                    int endLineStart = doc.lineStart(endLine);
                    int endLineEnd = doc.lineEnd(endLine);
                    return Math.min(endLineStart + suffixStartCol, endLineEnd);
                } else {
                    // Tapped past the placeholder — map to the suffix on the
                    // END line. Return an offset on the endLine so the caret
                    // renders at the correct X (suffixX + colInSuffix*charWidth).
                    int colInSuffix = col - prefixEndCol - fold.placeholder.length();
                    int endLineDocCol = suffixStartCol + colInSuffix;
                    int endLineStart = doc.lineStart(endLine);
                    int endLineEnd = doc.lineEnd(endLine);
                    return Math.min(endLineStart + endLineDocCol, endLineEnd);
                }
            } else {
                int lineLen = doc.lineEnd(line) - doc.lineStart(line);
                col = clamp(col, 0, lineLen);
            }
        }
        int lineStart = doc.lineStart(line);
        int lineEnd = doc.lineEnd(line);
        return Math.min(lineStart + col, lineEnd);
    }

    // ════════════════════════════════════════════════════════════════
    // Hardware keys
    // ════════════════════════════════════════════════════════════════

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (session == null) return super.onKeyDown(keyCode, event);
        if (keyHandler.onKeyDown(keyCode, event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    // ════════════════════════════════════════════════════════════════
    // Clipboard
    // ════════════════════════════════════════════════════════════════

    /** Copies the current selection (or does nothing if cursor). */
    public void copy() {
        String sel = session.selectedText();
        if (sel == null || sel.isEmpty()) return;
        setClipboard(sel);
    }

    /** Cuts the current selection to the clipboard (does nothing if cursor). */
    public void cut() {
        String sel = session.selectedText();
        if (sel == null || sel.isEmpty()) return;
        setClipboard(sel);
        // Delete the selection.
        int start = Math.min(session.getSelection().start, session.getSelection().end);
        int end = Math.max(session.getSelection().start, session.getSelection().end);
        session.replaceRange(start, end, "");
        onTextChanged();
    }

    /** Pastes the clipboard at the caret (replacing any selection). */
    public void paste() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData.Item item = cm.getPrimaryClip() == null ? null : cm.getPrimaryClip().getItemAt(0);
        if (item == null) return;
        CharSequence text = item.getText();
        if (text == null) return;
        String s = text.toString();
        if (s.length() > MAX_CLIPBOARD_CHARS) s = s.substring(0, MAX_CLIPBOARD_CHARS);
        session.commitText(s);
        onTextChanged();
    }

    private void setClipboard(String text) {
        // v2.34: keep the TAIL (CodeAssist clipForClipboard) — the useful
        // part of an oversized selection is its end (logs, generated code).
        if (text.length() > MAX_CLIPBOARD_CHARS) {
            text = text.substring(text.length() - MAX_CLIPBOARD_CHARS);
        }
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Code Editor", text));
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Scrolling
    // ════════════════════════════════════════════════════════════════

    /** Scrolls the caret into view after every edit / caret move. */
    void scrollCaretIntoView() {
        scrollManager.scrollCaretIntoView();
    }

    public void scrollToLine(int line) {
        scrollManager.scrollToLine(line);
    }

    public void scrollBy(float dy) {
        scrollManager.scrollBy(dy);
    }

    public void scrollHorizontallyBy(float dx) {
        scrollManager.scrollHorizontallyBy(dx);
    }

    /** Max vertical scroll: content height minus viewport height, at least 0. */
    float maxV() {
        return scrollManager.maxV();
    }

    /** Max horizontal scroll: longest line's width minus text-area width, at least 0. */
    float maxH() {
        return scrollManager.maxH();
    }

    // ════════════════════════════════════════════════════════════════
    // Zoom
    // ════════════════════════════════════════════════════════════════

    /** Sets the font scale directly (clamped to [0.6, 2.6]). */
    public void setFontScale(float scale) {
        fontScale = clampFontScale(scale);
        metrics.setTextSize(spToPx(BASE_TEXT_SIZE_SP) * fontScale);
        // Re-clamp scroll offsets — content size changed.
        vOffset = clamp(vOffset, 0, maxV());
        hOffset = clamp(hOffset, 0, maxH());
        requestLayout();
        invalidate();
    }

    /**
     * ★ v2.58 — Applique un scale pinch en ANCRANT le caret à sa position
     * écran actuelle. Le caret reste visuellement fixe pendant le pinch
     * (le viewport défile virtuellement sous lui) ET sa taille suit le
     * font-scale (lineHeight est dérivé de metrics, déjà mis à jour).
     *
     * <p><b>Comportement attendu par l'utilisateur</b> : « lorsque je fais
     * un pince pour zoomer, le caret devrait rester fixé à sa position et
     * agrandir ou réduire en même temps que le pinch zoom ».</p>
     *
     * <p><b>Algorithme</b> :
     * <ol>
     *   <li>Capturer (cx, cy) = {@link #caretScreenPos(int)} du caret
     *       AVANT d'appliquer le scale (avec les anciennes metrics).</li>
     *   <li>Appliquer le nouveau scale → metrics re-set, lineHeight /
     *       charWidth changent → {@link #caretScreenPos(int)} renverrait
     *       une nouvelle position (nx, ny) si les offsets restaient
     *       inchangés.</li>
     *   <li>Calculer le delta (cx - nx, cy - ny) et l'ajouter à
     *       {@link #hOffset} / {@link #vOffset} (puis clamp).</li>
     * </ol>
     * Le caret est maintenant à (cx, cy) écran — sa position visuelle
     * n'a pas bougé, mais sa hauteur/largeur suivent le nouveau font.
     */
    public void applyPinchScale(float scaleFactor) {
        float newScale = clampFontScale(fontScale * scaleFactor);
        if (newScale == fontScale) return; // no-op (clamp saturé)

        // (1) Capture caret's CURRENT screen position (old metrics).
        float cx = 0f, cy = 0f;
        boolean hasCaret = (session != null && !session.isReadOnly());
        if (hasCaret) {
            int caretOffset = session.getSelection().start;
            float[] pos = caretScreenPos(caretOffset);
            cx = pos[0];
            cy = pos[1];
        }

        // (2) Apply the new scale (mirrors setFontScale's body but without
        // requestLayout — pinch fires continuously, requestLayout would
        // thrash the framework. EditorView's onMeasure will be re-run by
        // the next invalidate anyway).
        fontScale = newScale;
        metrics.setTextSize(spToPx(BASE_TEXT_SIZE_SP) * fontScale);

        // (3) If we had a caret, adjust offsets to keep it anchored.
        if (hasCaret) {
            // Recompute caret position with the NEW metrics and the OLD
            // offsets — caretScreenPos reads vOffset/hOffset live, so
            // this returns where the caret WOULD land if we didn't touch
            // the offsets.
            //
            // Math : on veut newPos_after == (cx, cy) (caret ancré).
            //   pos = anchor(line, col, metrics) - offset
            //   où anchor = padTop + line * lh (vertical), et
            //   gutterW + padLeft + col * charWidth (horizontal).
            //
            //   Avant le scale  : cy  = anchor_old - vOffset_old
            //   Après (métriques nouvelles, offsets inchangés) :
            //     newPos_y = anchor_new - vOffset_old
            //   On veut : anchor_new - vOffset_new = cy
            //     → vOffset_new = anchor_new - cy
            //                = (newPos_y + vOffset_old) - cy
            //                = vOffset_old + (newPos_y - cy)
            //
            //   Donc : vOffset += (newPos_y - cy)
            //   (et symétriquement hOffset += (newPos_x - cx))
            //
            // NOTE : le signe est (+) car on veut AMENER le caret à cy,
            // pas l'éloigner. C'est la position NOUVELLE (newPos) moins
            // l'ANCIENNE (cx, cy), ce qui est intuitif : "combien le caret
            // a bougé à cause du scale → compenser ce mouvement".
            float[] newPos = caretScreenPos(session.getSelection().start);
            float dx = newPos[0] - cx;
            float dy = newPos[1] - cy;
            hOffset += dx;
            vOffset += dy;
        }

        // (4) Clamp to valid scroll range (post-scale).
        vOffset = clamp(vOffset, 0, maxV());
        hOffset = clamp(hOffset, 0, maxH());

        // (5) Cancel any in-flight caret glide — the caret is anchored
        // by our offset adjustment, a glide would override it.
        if (caretAnim != null) {
            caretAnim.onEditOrMove();
        }

        invalidate();
    }

    // v3.18.0: Convenience methods for font size +/- from Canvas icons.
    public void increaseFontSize() {
        setFontScale(clampFontScale(fontScale * 1.15f));
    }
    public void decreaseFontSize() {
        setFontScale(clampFontScale(fontScale / 1.15f));
    }

    // v3.18.0: Non-printable characters toggle.
    public void setShowNonPrintable(boolean show) {
        this.showNonPrintable = show;
        invalidate();
    }
    public boolean isShowNonPrintable() { return showNonPrintable; }

    // ★ v2.59 — Caret visibility toggle. Default true (visible).
    // Cache ou affiche le caret blinking indépendamment de EditorSession.readOnly.
    // Sert aux surfaces en lecture-seule qui veulent quand même muter le
    // document programmatiquement (ex : ConsoleLogView.appendLine) sans
    // montrer de point d'insertion à l'utilisateur.
    public void setCaretVisible(boolean visible) {
        this.caretVisible = visible;
        // Réinitialise l'état du blink pour éviter un caret figé dans
        // l'état précédent au moment du toggle.
        caretAnim.onEditOrMove();
        invalidate();
    }
    public boolean isCaretVisible() { return caretVisible; }

    // v3.19.0: Font ligatures toggle.
    public void setFontLigatures(boolean enabled) {
        this.fontLigatures = enabled;
        invalidate();
    }
    public boolean isFontLigatures() { return fontLigatures; }

    /**
     * v2.39: Tap-and-hold hover toggle (parité Sora "alwaysShowOnTouchHover").
     *
     * <p>When enabled, a 500ms dwell without movement/lift shows the
     * quick doc popup at the touched offset — WITHOUT triggering the
     * classic long-press selection (no handles, no toolbar, no caret
     * move). The classic long-press (400ms → selection + handles +
     * toolbar + quick doc) is mutually exclusive with this mode: when
     * touchHover is on, the GestureDetector's long-press is suppressed
     * for the duration of each gesture.</p>
     *
     * <p>Default: {@code false} (preserves legacy v2.31-v2.38 behavior
     * where long-press fires both selection and quick doc).</p>
     *
     * @param enabled true to enable tap-and-hold hover, false for classic long-press
     * @since v2.39
     */
    public void setTouchHoverEnabled(boolean enabled) {
        this.touchHoverEnabled = enabled;
    }
    /** v2.39: Returns whether tap-and-hold hover is enabled. */
    public boolean isTouchHoverEnabled() { return touchHoverEnabled; }
    boolean touchHoverEnabled = false;

    // v3.18.0: Hit-tests the toolbar icons (A+, A-, ¶, lig) in the top-right corner.
    // Returns: 0=no hit, 1=A+, 2=A-, 3=¶ (non-printable), 4=lig (ligatures)
    int hitTestToolbarIcons(float x, float y) {
        float density = getResources().getDisplayMetrics().density;
        float iconSize = PREVIEW_ICON_SIZE_DP * density;
        float margin = PREVIEW_ICON_MARGIN_DP * density;
        float iconY = margin;
        // Layout right-to-left: [lig] [¶] [A-] [A+]
        float startX = getWidth() - margin;
        float ligX = startX - iconSize;
        float npX = ligX - iconSize - margin * 0.5f;
        float aMinusX = npX - iconSize - margin * 0.5f;
        float aPlusX = aMinusX - iconSize - margin * 0.5f;
        if (y >= iconY && y <= iconY + iconSize) {
            if (x >= aPlusX && x <= aPlusX + iconSize) return 1;
            if (x >= aMinusX && x <= aMinusX + iconSize) return 2;
            if (x >= npX && x <= npX + iconSize) return 3;
            if (x >= ligX && x <= ligX + iconSize) return 4;
        }
        return 0;
    }

    static float clampFontScale(float s) {
        return Math.max(MIN_FONT_SCALE, Math.min(MAX_FONT_SCALE, s));
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    InputMethodManager imm() {
        return (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    float spToPx(float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
            getResources().getDisplayMetrics());
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static Selection clampSelection(Selection sel, EditorDocument doc) {
        int start = clamp(sel.start, 0, doc.length());
        int end = clamp(sel.end, 0, doc.length());
        return new Selection(start, end);
    }

    static int countLeadingWhitespace(String s) {
        int n = 0;
        while (n < s.length()) {
            char c = s.charAt(n);
            if (c == ' ' || c == '\t') n++;
            else break;
        }
        return n;
    }

    /** Optional listener for the host Activity to react to Ctrl+F. */
    public interface OnFindRequestedListener {
        void onFindRequested();
    }

    /** Optional listener for the host Activity to react to Ctrl+S. */
    public interface OnSaveRequestedListener {
        void onSaveRequested();
    }

    // ════════════════════════════════════════════════════════════════
    // Completion popup
    // ════════════════════════════════════════════════════════════════

    /**
     * Optional completion provider — the host plugs one in to feed
     * language-aware completions. Without a provider, the view falls back
     * to built-in keyword completion for java/kotlin/xml/markdown.
     */
    public interface CompletionProvider {
        /**
         * @param text     the full document text
         * @param caret    the caret offset
         * @param tokenStart the offset where the identifier being typed began
         * @param prefix   the typed prefix so far (may be empty)
         * @return a list of completion items (empty list if no completions)
         */
        List<jo.codeeditor.completion.CompletionSession.Item> provide(
            String text, int caret, int tokenStart, String prefix);
    }

    public void setCompletionProvider(CompletionProvider provider) {
        this.completionProvider = provider;
    }

    /**
     * Plugs in a signature-help resolver. Without one, the popup falls
     * back to a synthetic "function(…) param N" hint derived from local
     * call-context scanning.
     */
    public void setSignatureHelpResolver(SignatureHelpResolver resolver) {
        this.signatureHelpResolver = resolver;
        signatureHelpController.setListener(resolver != null
            ? (offset, callOpen) -> resolver.resolve(session.getText(), offset)
            : null);
    }

    /**
     * Public entry point so the host can trigger signature help explicitly
     * (e.g. a "Sig" button in the toolbar or the user pressing Ctrl+P).
     */
    public void refreshSignatureHelpFromHost() {
        popupManager.refreshSignatureHelpFromHost();
    }

    /** Hides the signature help popup. */
    public void dismissSignatureHelp() {
        popupManager.dismissSignatureHelp();
    }

    /**
     * v2.39: Cycles the active signature (overload) by {@code +1} (Down)
     * or {@code -1} (Up), wrapping around. Called by the keyboard handler
     * when the user presses Up/Down while the signature help popup is open.
     *
     * <p>The chosen overload persists across {@link #refreshSignatureHelp}
     * refreshes within the same call, so the user can keep typing
     * arguments and the popup stays on the chosen signature. When the
     * caret moves to a different call, the override is cleared.
     *
     * @param direction {@code +1} for next overload, {@code -1} for previous
     * @since v2.39
     */
    public void cycleSignatureHelp(int direction) {
        int newIdx = signatureHelpController.cycleActiveSignature(direction);
        if (newIdx >= 0) {
            invalidate();
        }
    }

    /**
     * v2.39: Returns the effective active signature index, taking into
     * account the user's override (set via {@link #cycleSignatureHelp}).
     * Renderers should read this instead of {@code signatureHelpData.activeSignature}
     * so Up/Down keyboard navigation is reflected in the popup.
     *
     * @return the active signature index, or {@code -1} if no help is available
     * @since v2.39
     */
    public int getEffectiveActiveSignature() {
        return signatureHelpController.getEffectiveActiveSignature();
    }

    /**
     * Triggers signature help at the current caret. Called on selection
     * changes and after edits — the controller internally decides
     * whether to keep the popup open (caret still inside the same call)
     * or hide it (caret left the call or the user dismissed).
     */
    void refreshSignatureHelp() {
        popupManager.refreshSignatureHelp();
    }

    /** Explicit Ctrl+P trigger — clears dismissed state and re-resolves. */
    void triggerSignatureHelp() {
        popupManager.triggerSignatureHelp();
    }

    // ════════════════════════════════════════════════════════════════
    // Quick doc popup (Gap 4)
    // ════════════════════════════════════════════════════════════════

    /** Plugs in a quick-doc resolver. */
    public void setQuickDocResolver(QuickDocResolver resolver) {
        this.quickDocResolver = resolver;
    }

    /**
     * Shows the quick-doc popup for the symbol at the given offset.
     * If a resolver is plugged in, calls it to get the raw doc text;
     * otherwise the popup is a no-op.
     */
    public void showQuickDoc(int offset) {
        popupManager.showQuickDoc(offset);
    }

    /** Hides the quick-doc popup. */
    public void dismissQuickDoc() {
        popupManager.dismissQuickDoc();
    }

    /** Word-wraps {@code text} to fit {@code maxWidth} (px). */
    // ════════════════════════════════════════════════════════════════
    // Code actions lightbulb (Gap 5)
    // ════════════════════════════════════════════════════════════════

    /** Plugs in a code-actions resolver. */
    public void setCodeActionsResolver(CodeActionsResolver resolver) {
        this.codeActionsResolver = resolver;
    }

    /**
     * Refreshes the per-line code-action cache. Called from {@link #onTextChanged}
     * and after big selection moves. Walks the visible line range, calls the
     * resolver for each, and stores the result in {@link #codeActionsByLine}.
     */
    void refreshCodeActions(int firstVisible, int lastVisible) {
        popupManager.refreshCodeActions(firstVisible, lastVisible);
    }

    /**
     * Draws a lightbulb 💡 in the fold strip for every visible line that has
     * code actions. Tapping the bulb opens the actions popup.
     *
     * <p>v1.0.9 redesign: the bulb is now drawn as a proper lightbulb glyph
     * (a filled circle + a small base rectangle, in amber) centered in the
     * fold strip column — no more "B" letter. It no longer overlaps line
     * numbers because the fold strip is a dedicated column (v1.0.8 fix).
     *
    /** Opens the code-actions popup for the given line. */
    public void showCodeActions(int line) {
        popupManager.showCodeActions(line);
    }

    // ════════════════════════════════════════════════════════════════
    // v2.36 : menu contextuel unifié (portage NavMenu de CodeAssist)
    // ════════════════════════════════════════════════════════════════

    /** Branche le resolver go-to-type-declaration. */
    public void setTypeDefinitionResolver(TypeDefinitionResolver resolver) {
        this.typeDefinitionResolver = resolver;
    }

    /** v2.37 — branche le resolver go-to-implementations. */
    public void setImplementationsResolver(ImplementationsResolver resolver) {
        this.implementationsResolver = resolver;
    }

    /** v2.37 — branche le resolver go-to-super. */
    public void setSuperResolver(SuperResolver resolver) {
        this.superResolver = resolver;
    }

    /**
     * v2.36 — ouvre le menu contextuel unifié (le bouton Actions ⋯ de la
     * toolbar de sélection) : sections GO TO / QUICK FIXES / INTENTIONS.
     * La résolution (definition + typeDefinition + quick-fixes de la ligne)
     * est asynchrone — le menu apparaît quand le contenu est prêt.
     *
     * @param line   la ligne d'ancrage (celle du caret)
     * @param offset l'offset caret de résolution
     */
    public void showNavMenu(int line, int offset) {
        popupManager.showNavMenu(line, offset);
    }

    /** Referme le menu contextuel unifié. */
    public void dismissNavMenu() {
        popupManager.dismissNavMenu();
    }

    /**
     * Returns true if the given line has an Error/Warning diagnostic.
     * Used by the lightbulb draw + hit-test to gate the bulb on diagnostic
     * lines only (matching CodeAssist's behavior).
     */
    boolean lineHasDiagnostic(int line) {
        if (session == null) return false;
        for (DiagnosticShift.Diagnostic d : session.getDiagnostics()) {
            if (d.severity != 3 && d.severity != 2) continue; // only error/warning
            int ln = session.getDocument().lineForOffset(d.start);
            if (ln == line) return true;
        }
        return false;
    }

    /** Dismisses the code-actions popup. */
    public void dismissCodeActions() {
        popupManager.dismissCodeActions();
    }

    /** Applies the currently-selected code action. */
    public boolean applySelectedCodeAction() {
        return popupManager.applySelectedCodeAction();
    }

    // ════════════════════════════════════════════════════════════════
    // Go-to-symbol popup (Gap 6)
    // ════════════════════════════════════════════════════════════════

    /** Plugs in a symbol resolver. */
    public void setSymbolResolver(SymbolResolver resolver) {
        this.symbolResolver = resolver;
    }

    // ════════════════════════════════════════════════════════════════
    // v3.33.11: Definition / References / DocumentHighlight / Rename / Formatter
    // ════════════════════════════════════════════════════════════════

    /** Plugs in a definition resolver for go-to-definition. */
    public void setDefinitionResolver(DefinitionResolver resolver) {
        this.definitionResolver = resolver;
    }

    /**
     * Triggers go-to-definition at the current caret. If the resolver
     * returns a single target in the SAME file, navigates to it directly.
     * If multiple targets or cross-file, returns the list via the
     * {@link OnDefinitionRequestedListener} (host decides how to display).
     *
     * <p>v0.1.0.50 : la résolution LSP quitte le thread UI — l'hôte peut
     * afficher un feedback « recherche… » immédiat et le résultat est
     * appliqué dès qu'il arrive.</p>
     */
    public void jumpToDefinition() {
        if (definitionResolver == null || session == null) return;
        final Selection sel = session.getSelection();
        final String text = session.getText().toString();
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            List<jo.codeeditor.lang.DefinitionLocation> targets = null;
            try {
                targets = definitionResolver.resolve(text, sel.start);
            } catch (Exception ignored) {
            }
            final List<jo.codeeditor.lang.DefinitionLocation> resolved = targets;
            Runnable apply = () -> {
                if (session == null) return;
                if (resolved == null || resolved.isEmpty()) return;
                if (resolved.size() == 1) {
                    jo.codeeditor.lang.DefinitionLocation loc = resolved.get(0);
                    if (loc.path == null || loc.path.isEmpty()
                        || loc.path.equals(currentFilePath)
                        || loc.path.equals("file:///" + currentFilePath)) {
                        // Same file — jump directly.
                        session.setSelection(loc.offset);
                        scrollManager.scrollCaretIntoView();
                        invalidate();
                    } else if (definitionListener != null) {
                        definitionListener.onDefinitionRequested(resolved);
                    }
                } else if (definitionListener != null) {
                    definitionListener.onDefinitionRequested(resolved);
                }
            };
            android.os.Handler h = getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /** Listener invoked when go-to-definition produces multiple targets or a cross-file target. */
    public interface OnDefinitionRequestedListener {
        void onDefinitionRequested(List<jo.codeeditor.lang.DefinitionLocation> targets);
    }
    OnDefinitionRequestedListener definitionListener;
    public void setOnDefinitionRequestedListener(OnDefinitionRequestedListener l) {
        this.definitionListener = l;
    }

    /**
     * Optional: the host sets this so jumpToDefinition can recognise same-file URIs.
     * v2.36 : package-private — navMenuNavigate (EditorPopupManager) teste le même-fichier.
     */
    String currentFilePath = "";
    public void setCurrentFilePath(String path) {
        this.currentFilePath = path != null ? path : "";
    }

    /** Plugs in a references resolver for find-references. */
    public void setReferencesResolver(ReferencesResolver resolver) {
        this.referencesResolver = resolver;
    }

    /**
     * Triggers find-references at the current caret. Calls the resolver
     * and shows the results in a popup (reuses the go-to-symbol UI layout).
     *
     * <p>v0.1.0.50 : résolution LSP (15 s !) déportée hors du thread UI,
     * annulable par génération.</p>
     */
    public void showReferences() {
        if (referencesResolver == null || session == null) return;
        final Selection sel = session.getSelection();
        final String text = session.getText().toString();
        final int gen = ++referencesGeneration;
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            List<jo.codeeditor.lang.DefinitionLocation> targets = null;
            try {
                targets = referencesResolver.resolve(text, sel.start);
            } catch (Exception ignored) {
            }
            final List<jo.codeeditor.lang.DefinitionLocation> resolved = targets;
            Runnable apply = () -> {
                if (gen != referencesGeneration || session == null) return;
                if (resolved == null || resolved.isEmpty()) return;
                // Convert to NavigationMenu.Symbol so we can reuse the symbol popup UI.
                referencesAll.clear();
                for (jo.codeeditor.lang.DefinitionLocation loc : resolved) {
                    String label = loc.displayName != null && !loc.displayName.isEmpty()
                        ? loc.displayName : loc.path;
                    referencesAll.add(new NavigationMenu.Symbol(label, loc.offset, "reference", loc.path));
                }
                referencesFilter = "";
                referencesFiltered.clear();
                referencesFiltered.addAll(referencesAll);
                referencesSelected = 0;
                referencesScrollOffset = 0;
                referencesPopupVisible = true;
                invalidate();
            };
            android.os.Handler h = getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /** v0.1.0.50 : génération de la recherche de références. */
    private volatile int referencesGeneration = 0;

    /**
     * v0.1.0.50 : navigation inter-fichiers. L'hôte (ProjectActivity)
     * branche ce listener pour ouvrir le fichier cible à l'offset donné.
     * Utilisé par {@link #referencesAccept()} quand l'usage sélectionné se
     * trouve dans un autre fichier.
     */
    public interface OnNavigateToFileListener {
        void navigateToFile(String pathOrUri, int offset);
    }
    OnNavigateToFileListener navigateToFileListener;

    public void setOnNavigateToFileListener(OnNavigateToFileListener listener) {
        this.navigateToFileListener = listener;
    }

    /** Dismisses the references popup. */
    public void dismissReferences() {
        referencesPopupVisible = false;
        invalidate();
    }

    public boolean isReferencesVisible() { return referencesPopupVisible; }

    /** Moves the references popup selection by delta. */
    public boolean referencesSelect(int delta) {
        if (!referencesPopupVisible || referencesFiltered.isEmpty()) return false;
        referencesSelected = Math.max(0,
            Math.min(referencesFiltered.size() - 1, referencesSelected + delta));
        invalidate();
        return true;
    }

    /** Accepts the currently-selected reference and navigates to it. */
    public boolean referencesAccept() {
        if (!referencesPopupVisible || referencesFiltered.isEmpty()) return false;
        NavigationMenu.Symbol s = referencesFiltered.get(referencesSelected);
        // v0.1.0.50 : si l'usage pointe vers un AUTRE fichier, on délègue
        // la navigation inter-fichiers à l'hôte (ouverture du fichier cible
        // au bon offset). Sinon saut direct dans le fichier courant.
        dismissReferences();
        String container = s.container;
        boolean sameFile = container == null || container.isEmpty()
            || container.equals(currentFilePath)
            || container.equals("file:///" + currentFilePath)
            || container.equals("file://" + currentFilePath);
        if (!sameFile && navigateToFileListener != null) {
            navigateToFileListener.navigateToFile(container, s.offset);
        } else {
            session.expandFoldAt(s.offset);
            session.setSelection(s.offset);
            scrollManager.scrollCaretIntoView();
        }
        invalidate();
        return true;
    }

    /** Updates the references popup filter (re-uses NavigationMenu.filter). */
    public void setReferencesFilter(String filter) {
        referencesFilter = filter != null ? filter : "";
        referencesFiltered.clear();
        referencesFiltered.addAll(NavigationMenu.filter(referencesAll, referencesFilter));
        if (referencesSelected >= referencesFiltered.size()) {
            referencesSelected = Math.max(0, referencesFiltered.size() - 1);
        }
        invalidate();
    }

    /** Plugs in a document-highlight resolver. */
    public void setDocumentHighlightResolver(DocumentHighlightResolver resolver) {
        this.documentHighlightResolver = resolver;
    }

    /** Plugs in a rename resolver (replaces the substring-matching fallback). */
    public void setRenameResolver(RenameResolver resolver) {
        this.renameResolver = resolver;
    }

    /** Plugs in a formatter resolver. */
    public void setFormatterResolver(FormatterResolver resolver) {
        this.formatterResolver = resolver;
    }

    /**
     * Formats the entire document via the plugged-in formatter resolver.
     * v0.1.0.50 : la requête LSP formatting (5 s) quitte le thread UI ; le
     * résultat est appliqué en un seul step d'undo quand il arrive.
     *
     * @param callback exécuté sur le thread UI après application :
     *                 {@code Boolean.TRUE} si le texte a changé, FALSE sinon,
     *                 null si aucun formatteur. Peut être null.
     */
    public void formatDocument(java.util.function.Consumer<Boolean> callback) {
        if (formatterResolver == null || session == null) {
            if (callback != null) callback.accept(null);
            return;
        }
        final String current = session.getText().toString();
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            String formatted = null;
            try {
                formatted = formatterResolver.format(current);
            } catch (Exception e) {
            }
            final String result = formatted;
            Runnable apply = () -> {
                boolean changed = false;
                if (result != null && !result.equals(current) && session != null) {
                    try {
                        int caret = session.getSelection().start;
                        session.replaceRange(0, session.getDocument().length(), result);
                        int newCaret = Math.min(caret, result.length());
                        session.setSelection(newCaret);
                        invalidate();
                        changed = true;
                    } catch (Exception ignored) {
                    }
                }
                if (callback != null) callback.accept(changed);
            };
            android.os.Handler h = getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /** Ancienne API synchrone conservée pour compatibilité — retourne
     * toujours false (le formatage est désormais asynchrone). */
    public boolean formatDocument() {
        return false;
    }

    /**
     * Opens the go-to-symbol popup. Calls the resolver to get the full
     * symbol list, then filters by the current filter text.
     */
    public void showGoToSymbol() {
        popupManager.showGoToSymbol();
    }

    /**
     * v0.1.0.50 : exécute l'action « source.organizeImports » via le
     * fournisseur d'actions LSP (l'action est reconstruite par le serveur
     * à la demande et applique son WorkspaceEdit localement). Résout en
     * arrière-plan — le thread UI n'attend jamais.
     *
     * @param callback thread UI : TRUE si une action a été trouvée+exécutée,
     *                 FALSE sinon (aucun provider / action absente).
     */
    public void performOrganizeImports(java.util.function.Consumer<Boolean> callback) {
        jo.codeeditor.lang.CodeActionsProvider provider =
                language != null ? language.getCodeActionsProvider() : null;
        if (provider == null || session == null) {
            if (callback != null) callback.accept(false);
            return;
        }
        final int caretLine;
        try {
            caretLine = session.getDocument().lineForOffset(session.getSelection().start);
        } catch (Throwable t) {
            if (callback != null) callback.accept(false);
            return;
        }
        final String text = session.getText();
        final jo.codeeditor.lang.CodeActionsProvider fProvider = provider;
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            // Le serveur propose « Organiser les imports » quelle que soit
            // la ligne demandée ; on sonde autour du curseur par sécurité.
            EditorDocument doc = session != null ? session.getDocument() : null;
            int max = doc != null ? doc.lineCount() - 1 : caretLine;
            List<jo.codeeditor.lang.CodeAction> found = new java.util.ArrayList<>();
            int[] probes = {caretLine, 0};
            for (int probe : probes) {
                if (!found.isEmpty()) break;
                if (probe < 0 || probe > max) continue;
                try {
                    for (jo.codeeditor.lang.CodeAction a : fProvider.codeActions(text, probe)) {
                        String kind = a.kind == null ? "" : a.kind;
                        if (kind.contains("source") && !found.contains(a)) found.add(a);
                    }
                } catch (Throwable ignored) {
                }
            }
            final boolean anySourceAction = !found.isEmpty();
            Runnable apply = () -> {
                boolean applied = false;
                if (anySourceAction && session != null) {
                    try {
                        for (jo.codeeditor.lang.CodeAction a : found) {
                            a.apply.run();
                            applied = true;
                            break; // une seule action source suffit
                        }
                    } catch (Throwable ignored) {
                        applied = false;
                    }
                }
                if (callback != null) callback.accept(applied);
            };
            android.os.Handler h = getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /** Dismisses the go-to-symbol popup. */
    public void dismissGoToSymbol() {
        popupManager.dismissGoToSymbol();
    }

    /**
     * Updates the filter text and re-filters the symbol list. Called
     * by the host on every keystroke in the popup's filter field.
     */
    public void setGoToSymbolFilter(String filter) {
        popupManager.setGoToSymbolFilter(filter);
    }

    /** Moves the go-to-symbol selection by the given delta. */
    public boolean goToSymbolSelect(int delta) {
        return popupManager.goToSymbolSelect(delta);
    }

    /** Accepts the currently-selected symbol and navigates to it. */
    public boolean goToSymbolAccept() {
        return popupManager.goToSymbolAccept();
    }

    /** Draws the go-to-symbol popup: filter field + scrollable list. */
    /** Public entry for an external completion source (e.g. CompletionController). */
    public void setCompletionItems(List<jo.codeeditor.completion.CompletionSession.Item> items,
                                    int tokenStart, String prefix) {
        popupManager.setCompletionItems(items, tokenStart, prefix);
    }

    public boolean isCompletionVisible() { return popupManager.isCompletionVisible(); }

    public void dismissCompletion() {
        popupManager.dismissCompletion();
    }

    /** Move selection up in the completion popup. Returns true if handled. */
    public boolean completionSelectUp() {
        return popupManager.completionSelectUp();
    }

    /** Move selection down in the completion popup. Returns true if handled. */
    public boolean completionSelectDown() {
        return popupManager.completionSelectDown();
    }

    /** Accept the currently selected completion. Returns true if a completion was accepted. */
    public boolean completionAccept() {
        return popupManager.completionAccept();
    }

    /**
     * v3.3.5: Refresh the completion popup based on the current caret context.
     * <p>Uses client-side filtering (CodeAssist pattern):
     * <ol>
     *   <li>If the caret is still on the SAME token as the cached base set,
     *       filter the cached set by prefix (case-insensitive + fuzzy
     *       subsequence) — NO provider round-trip. This keeps the popup
     *       responsive on every keystroke even with a slow LSP server.</li>
     *   <li>If the caret moved to a NEW token (or there's no cache), query
     *       the provider and cache the result as the new base set.</li>
     * </ol>
     * <p>The cache is invalidated when:
     * <ul>
     *   <li>The caret moves to a different token (prefix doesn't start at
     *       the same offset)</li>
     *   <li>The prefix becomes empty (token ended)</li>
     *   <li>The user accepts or dismisses the popup</li>
     * </ul>
     */
    void refreshCompletion() {
        popupManager.refreshCompletion();
    }

    /**
     * Public entry point so the host can trigger completion explicitly
     * (e.g. a "Cmplt" button in the toolbar).
     */
    public void refreshCompletionFromHost() {
        popupManager.refreshCompletionFromHost();
    }

    /**
     * Computes the completion popup's anchor (top-left) in screen coordinates.
     * Shared by {@link #drawCompletionPopup} and {@link #hitTestCompletionPopup}
     * so they always agree on the popup's position.
     */
    float[] completionPopupAnchor() {
        return popupManager.completionPopupAnchor();
    }

    /**
     * v2.38 — Géométrie du popup quick doc : {anchorX, anchorY, popupW,
     * popupH, contentH, rowH} ou null. Source unique rendu (renderer) /
     * hit-test (input handler) — le popup suit le texte au scroll.
     */
    float[] quickDocMetrics() {
        return renderer.quickDocMetrics();
    }


    // ════════════════════════════════════════════════════════════════
    // Block editor mode (v1.0.7 — Gap 7) + EditorOverlayLayers (Gap 8)
    // ════════════════════════════════════════════════════════════════

    // v3.33.5: setBlockMode, isBlockMode, getBlockEditor supprimés (BlockEditor = code mort).

    // ════════════════════════════════════════════════════════════════
    // EditorOverlayLayers (v1.0.7 — Gap 8)
    // ════════════════════════════════════════════════════════════════

    /** Toggles inline diagnostic chips (Gap 8). */
    public void setDiagnosticChipsEnabled(boolean enabled) {
        this.diagnosticChipsEnabled = enabled;
        invalidate();
    }

    public boolean isDiagnosticChipsEnabled() { return diagnosticChipsEnabled; }

    /**
     * Opens the go-to-line popup (Gap 8). v1.0.9: uses a real Android
     * {@link android.widget.PopupWindow} with an {@link android.widget.EditText}
     * so the user can type a line number with the IME. Accepts
     * {@code line} or {@code line:column} (1-based). Enter navigates,
     * Esc cancels.
     */
    public void showGoToLine() {
        popupManager.showGoToLine();
    }

    /** Parses "line" or "line:col" (1-based) and navigates. */
    void acceptGoToLineInput(String input) {
        popupManager.acceptGoToLineInput(input);
    }

    public void dismissGoToLine() {
        popupManager.dismissGoToLine();
    }

    public boolean isGoToLineVisible() { return popupManager.isGoToLineVisible(); }

    /** @deprecated Use the popup-based {@link #showGoToLine()} instead. */
    @Deprecated
    public void setGoToLineText(String text) {
        popupManager.setGoToLineText(text);
    }

    /** @deprecated Use the popup-based {@link #showGoToLine()} instead. */
    @Deprecated
    public boolean acceptGoToLine() {
        return popupManager.acceptGoToLine();
    }

    /**
     * Opens the rename popup (Gap 8). v1.0.9: uses a real Android
     * {@link android.widget.PopupWindow} with an {@link android.widget.EditText}
     * pre-filled with the identifier at the caret. Enter renames all
     * occurrences, Esc cancels.
     */
    public void showRename() {
        popupManager.showRename();
    }

    /** Applies the rename: replaces all identifier-equal occurrences. */
    boolean acceptRenameInput(String newName) {
        return popupManager.acceptRenameInput(newName);
    }

    public void dismissRename() {
        popupManager.dismissRename();
    }

    public boolean isRenameVisible() { return popupManager.isRenameVisible(); }

    /** @deprecated Use the popup-based {@link #showRename()} instead. */
    @Deprecated
    public void setRenameText(String text) {
        popupManager.setRenameText(text);
    }

    /** @deprecated Use the popup-based {@link #showRename()} instead. */
    @Deprecated
    public boolean acceptRename() {
        return popupManager.acceptRename();
    }

    /** Opens the diagnostic sheet (Gap 8) — a bottom sheet listing all diagnostics. */
    public void showDiagnosticSheet() {
        popupManager.showDiagnosticSheet();
    }

    public void dismissDiagnosticSheet() {
        popupManager.dismissDiagnosticSheet();
    }

    public boolean isDiagnosticSheetVisible() { return popupManager.isDiagnosticSheetVisible(); }

    /** v2.31: True when the per-diagnostic sheet (CodeAssist style) is up. */
    public boolean isDiagnosticPopupVisible() { return diagnosticPopupVisible; }

    /**
     * Draws the selection toolbar (Gap 8) — a floating row of Copy/Cut/Paste/
     * Select All buttons above the start of the selection.
     */
    /**
     * Draws the floating selection toolbar — a frosted pill anchored above
     * the ACTIVE end of the selection (matching CodeAssist's UX where the
     * toolbar follows the finger to where the user finished selecting).
     *
    /** Shows the floating selection toolbar (Copy/Cut/Paste/Select All). */
    public void showSelectionToolbar() {
        inputHandler.showSelectionToolbar();
    }

    public void dismissSelectionToolbar() {
        inputHandler.dismissSelectionToolbar();
    }

    /**
     * Handles a tap on the selection toolbar. Returns true if the tap
     * was consumed. v1.0.9: matches the new pill layout — measures each
     * button's actual width instead of assuming equal button sizes.
     */
    public boolean handleSelectionToolbarTap(float x, float y) {
        return inputHandler.handleSelectionToolbarTap(x, y);
    }

    // v1.0.9: drawGoToLinePopup and drawRenamePopup removed — go-to-line
    // and rename now use real Android PopupWindow + EditText (see
    // showGoToLine / showRename) so they can receive keyboard input.

    /** Converts dp to px. */
    int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * v3.4.0: Finds the diagnostic at the given offset, or null.
     * v2.32: no longer used by handleTap — the squiggle is not tappable
     * (CodeAssist parity: only the chip and the gutter dot open the sheet).
     * Kept for tests and potential long-press/quick-doc integrations.
     */
    DiagnosticShift.Diagnostic findDiagnosticAt(int offset) {
        if (session == null) return null;
        List<DiagnosticShift.Diagnostic> diags = session.getDiagnostics();
        for (DiagnosticShift.Diagnostic d : diags) {
            if (offset >= d.start && offset <= d.end) {
                return d;
            }
        }
        return null;
    }

    /**
     * v3.7.1 Bugfix (Bug 6b): Finds the first diagnostic that STARTS on the
     * given document line, or null. Used by handleTap to open the diagnostic
     * popup when the user taps anywhere on a line that has a diagnostic
     * (not just on the squiggle range itself). Mirrors the existing
     * {@link #lineHasDiagnostic(int)} gate but returns the Diagnostic
     * object instead of a boolean.
     *
     * <p>Errors (severity 3) are preferred over warnings (severity 2),
     * which are preferred over info (severity 1) — if a line has both an
     * error and a warning, the popup shows the error first.
     */
    DiagnosticShift.Diagnostic findDiagnosticAtLine(int line) {
        if (session == null) return null;
        EditorDocument doc = session.getDocument();
        if (doc == null || line < 0 || line >= doc.lineCount()) return null;
        int lineStart = doc.lineStart(line);
        int lineEnd = doc.lineEnd(line);
        List<DiagnosticShift.Diagnostic> diags = session.getDiagnostics();
        DiagnosticShift.Diagnostic best = null;
        for (DiagnosticShift.Diagnostic d : diags) {
            // Diagnostic starts on this line if its start offset is in
            // [lineStart, lineEnd].
            if (d.start < lineStart || d.start > lineEnd) continue;
            if (best == null || d.severity > best.severity) {
                best = d;
            }
        }
        return best;
    }

    /**
     * v3.4.0: Shows a per-diagnostic popup (CodeAssist DiagnosticSheet pattern).
     * Displays the FULL diagnostic message + any quick-fixes from the code
     * actions resolver. Anchored above the diagnostic's line.
     */
    void showDiagnosticPopup(DiagnosticShift.Diagnostic diag, int offset) {
        popupManager.showDiagnosticPopup(diag, offset);
    }

    /** v3.4.0: Dismisses the per-diagnostic popup. */
    public void dismissDiagnosticPopup() {
        popupManager.dismissDiagnosticPopup();
    }

    /** Draws the diagnostic sheet (bottom sheet listing all diagnostics — Gap 8). */
}
