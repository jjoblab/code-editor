package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

/**
 * v3.11.0: Extracted from EditorView — handles all scroll mechanics:
 * caret-into-view, line/offset scrolling, max scroll bounds.
 *
 * <p>EditorView delegates {@code scrollCaretIntoView}, {@code scrollToLine},
 * {@code scrollToOffset}, {@code scrollBy}, {@code scrollHorizontallyBy},
 * {@code maxV}, {@code maxH} to this class. The scroll state ({@code vOffset},
 * {@code hOffset}) stays in EditorView (package-private) and is mutated
 * directly by this manager.
 *
 * @since v3.11.0
 */
class EditorScrollManager {

    private final EditorView view;

    EditorScrollManager(EditorView view) {
        this.view = view;
    }

    /**
     * Scrolls the caret into view after every edit / caret move.
     * Word-wrap-aware: computes the caret's Y from the wrap model so a
     * caret on the 3rd wrapped row of a long line scrolls correctly.
     */
    void scrollCaretIntoView() {
        if (view.session == null || view.getWidth() == 0 || view.getHeight() == 0) return;
        EditorDocument doc = view.session.getDocument();
        int caret = view.session.getSelection().start;
        int line = EditorView.clamp(doc.lineForOffset(caret), 0, doc.lineCount() - 1);
        int col = caret - doc.lineStart(line);
        float lineHeight = view.metrics.getLineHeight();
        float charWidth = view.metrics.getCharWidth();
        float textLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        // Vertical: doc-line top, plus extra rows if the caret is on a wrapped row.
        float caretY = view.docLineToY(line);
        if (view.wordWrap && view.wrapModel != null) {
            // ★ v2.33 — rangée via wrapRowsFor (les rangées de continuation
            // sont plus étroites : l'ancien col/maxColsPerRow était décalé).
            EditorDocument doc2 = view.session.getDocument();
            int lineLen = doc2.lineEnd(line) - doc2.lineStart(line);
            EditorView.WrapRows wr = view.wrapRowsFor(line, lineLen);
            caretY += wr.rowForCol(col) * lineHeight;
        }
        float viewH = view.getHeight();
        // Vertical — only scroll if the caret is actually outside the viewport.
        if (caretY < view.vOffset) {
            view.vOffset = Math.max(0, caretY - lineHeight);
        } else if (caretY + lineHeight > view.vOffset + viewH) {
            view.vOffset = caretY + lineHeight - viewH;
        }
        view.vOffset = EditorView.clamp(view.vOffset, 0, maxV());

        // Horizontal — skipped in wrap mode (every row is fully visible).
        // v3.7.1 Bugfix (Bug 4): use caretScreenPos() so the caret's X is
        // fold-aware — if the caret is on a fold-start or fold-end line,
        // the visible X may differ from col*charWidth.
        if (!view.wordWrap) {
            float[] screenPos = view.caretScreenPos(caret);
            float caretScreenX = screenPos[0];
            float viewW = view.getWidth() - textLeft - view.metrics.getPadRight();
            float margin = charWidth * 3;
            if (caretScreenX < textLeft + margin) {
                float visibleCol = (caretScreenX + view.hOffset - textLeft) / charWidth;
                view.hOffset = Math.max(0, visibleCol * charWidth - margin);
            } else if (caretScreenX > textLeft + viewW - margin) {
                float visibleCol = (caretScreenX + view.hOffset - textLeft) / charWidth;
                view.hOffset = visibleCol * charWidth - viewW + margin;
            }
            view.hOffset = EditorView.clamp(view.hOffset, 0, maxH());
        }

        view.invalidate();
    }

    void scrollToLine(int line) {
        float lineHeight = view.metrics.getLineHeight();
        float y = view.docLineToY(line);
        float viewHeight = view.getHeight();
        if (y < view.vOffset) {
            view.vOffset = y;
        } else if (y + lineHeight > view.vOffset + viewHeight) {
            view.vOffset = y + lineHeight - viewHeight;
        }
        view.vOffset = EditorView.clamp(view.vOffset, 0, maxV());
        // ★ v0.1.0.49-v2.20 — Notifie le sticky-bottom des consoles.
        view.notifyScrollPositionChanged();
        view.invalidate();
    }

    void scrollToOffset(int offset) {
        if (view.session == null) return;
        EditorDocument doc = view.session.getDocument();
        int line = EditorView.clamp(doc.lineForOffset(offset), 0, doc.lineCount() - 1);
        view.session.expandFoldAt(offset);
        scrollToLine(line);
    }

    void scrollBy(float dy) {
        float newV = EditorView.clamp(view.vOffset + dy, 0, maxV());
        if (newV != view.vOffset) {
            view.vOffset = newV;
            // ★ v0.1.0.49-v2.20 — Notifie le sticky-bottom des consoles.
            view.notifyScrollPositionChanged();
            view.invalidate();
        }
    }

    void scrollHorizontallyBy(float dx) {
        float newH = EditorView.clamp(view.hOffset + dx, 0, maxH());
        if (newH != view.hOffset) {
            view.hOffset = newH;
            view.invalidate();
        }
    }

    /**
     * Max vertical scroll: content height minus viewport height, at least 0.
     * When word wrap is on, the content height includes the extra rows
     * from each wrapped line.
     */
    float maxV() {
        if (view.session == null) return 0;
        float contentH;
        if (view.wordWrap && view.wrapModel != null) {
            contentH = view.metrics.getPadTop()
                + view.wrapModel.totalRows() * view.metrics.getLineHeight()
                + view.metrics.getPadBottom();
        } else {
            contentH = view.metrics.getPadTop()
                + view.session.getDocument().lineCount() * view.metrics.getLineHeight()
                + view.metrics.getPadBottom();
        }
        return Math.max(0, contentH - view.getHeight());
    }

    /**
     * Max horizontal scroll: longest line's width minus text-area width, at least 0.
     * Returns 0 when word wrap is on (no horizontal scroll in wrap mode).
     *
     * <p>★ v2.33 — l'étendue tient compte des inlay hints tissés au-delà de
     * la fin de ligne (longueur VISUELLE) ET du débordement des chips
     * diagnostics ({@code chipExtentContentX}, mesuré au draw pass —
     * pattern {@code contentWidth()} de CodeAssist EditorGeometry) : un
     * hint/chip qui dépasse la ligne la plus longue est désormais
     * ATTEIGNABLE au scroll horizontal.</p>
     *
     * <p>v3.35.0 (roadmap item 4 / hotspot P4) — memoized max column count:
     * maxH() used to scan EVERY line of the document plus the inlay
     * extras on EVERY call — and it is called several times per frame
     * (scroll clamping in scrollBy / scrollHorizontallyBy / fling /
     * caret-into-view). The scan now runs ONCE per
     * {@code (session, document revision, inlay revision)}:</p>
     *
     * <ul>
     *   <li>{@link EditorDocument} is IMMUTABLE and replaced on every edit,
     *       so the document reference alone is a reliable edition key —
     *       a true incremental max (maintaining the max across edits)
     *       would need line-level tracking of WHICH line was the longest,
     *       fragile next to inlay shifts, for no measurable gain (one
     *       O(lines) scan per keystroke instead of several per frame);</li>
     *   <li>{@code setInlayHints} bumps the session's inlay revision, and
     *       an edit shifts the hints while replacing the document — both
     *       paths invalidate the memo.</li>
     * </ul>
     *
     * <p>Pixel conversion (charWidth) and the chip extent
     * ({@code chipExtentContentX}, measured at draw time) deliberately stay
     * OUT of the memo: the cached value is in COLUMNS, so font-size changes
     * don't need to invalidate it.</p>
     */
    private EditorDocument maxColsDoc;
    private EditorSession maxColsSession;
    private int maxColsInlayRev = Integer.MIN_VALUE;
    private int cachedMaxCols = 0;

    float maxH() {
        if (view.session == null) return 0;
        if (view.wordWrap) return 0;
        EditorDocument doc = view.session.getDocument();
        if (doc == null) return 0;
        if (doc != maxColsDoc || view.session != maxColsSession
                || view.session.getInlayHintsRevision() != maxColsInlayRev) {
            cachedMaxCols = computeMaxCols(doc);
            maxColsDoc = doc;
            maxColsSession = view.session;
            maxColsInlayRev = view.session.getInlayHintsRevision();
        }
        int maxCols = cachedMaxCols;
        float contentW = view.metrics.getPadLeft()
                + (maxCols + 1) * view.metrics.getCharWidth();
        // ★ Chips : le débordement le plus à droite étend la largeur
        // scrollable (mesuré au frame précédent — rafraîchi à chaque draw).
        if (view.chipExtentContentX > contentW) {
            contentW = view.chipExtentContentX;
        }
        contentW += view.metrics.getPadRight();
        float textAreaW = view.getWidth() - view.metrics.getGutterWidth();
        return Math.max(0, contentW - textAreaW);
    }

    /**
     * The actual O(lines + hints) scan — runs once per document/inlay
     * revision instead of once per maxH() call.
     */
    private int computeMaxCols(EditorDocument doc) {
        int maxCols = 0;
        for (int i = 0; i < doc.lineCount(); i++) {
            int len = doc.lineEnd(i) - doc.lineStart(i);
            if (len > maxCols) maxCols = len;
        }
        // ★ Inlays : la longueur VISUELLE d'une ligne = len + Σ(longueur des
        // hints de la ligne) — O(hints), sans lookup de cache par ligne.
        java.util.List<jo.codeeditor.shift.DiagnosticShift.InlayHint> hints =
                view.session.getInlayHints();
        if (!hints.isEmpty()) {
            java.util.HashMap<Integer, Integer> extra = new java.util.HashMap<>();
            for (jo.codeeditor.shift.DiagnosticShift.InlayHint h : hints) {
                if (h.text == null) continue;
                int ln = doc.lineForOffset(Math.max(0,
                        Math.min(h.offset, doc.length())));
                extra.merge(ln, h.text.length(), Integer::sum);
            }
            for (int ln : extra.keySet()) {
                int visual = doc.lineEnd(ln) - doc.lineStart(ln) + extra.get(ln);
                if (visual > maxCols) maxCols = visual;
            }
        }
        return maxCols;
    }
}
