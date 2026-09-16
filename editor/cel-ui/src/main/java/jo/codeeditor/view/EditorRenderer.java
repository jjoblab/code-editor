package jo.codeeditor.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.find.Match;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.8.0: Extracted from {@link EditorView} — holds all Canvas drawing methods.
 *
 * <p>{@link EditorView#onDraw(Canvas)} delegates to {@link #draw(Canvas)}. All
 * field accesses go through the {@code view} reference (fields are
 * package-private on EditorView). Helper methods that are shared with
 * non-draw code paths (e.g. {@link EditorView#docLineToY(int)},
 * {@link EditorView#layoutForLine(int, String)},
 * {@link EditorView#caretScreenPos(int)}) are likewise package-private on
 * EditorView and called through {@code view}.
 *
 * <p>Pure draw-only helpers ({@link #getSquiggleColor(int)},
 * {@link #applyAlpha(int, float)}, {@link #parseColorLiteral(String)},
 * {@link #getContrastColor(int)}, {@link #findParameterRangeInLabel(String, int)},
 * {@link #wrapText(String, float, Paint)}) live here.
 *
 * @since v3.8.0
 */
class EditorRenderer {
    private final EditorView view;

    // ── Constants moved from EditorView (draw-only) ──────────────
    // Completion popup border radius (the row-height / width / max-rows
    // constants stay on EditorView because the touch / hit-test code also
    // needs them).
    // v2.38 : 12dp — coins TOP seuls, plus visibles (avant 6dp uniformes).
    static final float COMPLETION_BORDER_RADIUS_DP = 12f;
    // Signature help popup
    static final float SIGNATURE_HELP_ROW_HEIGHT_DP = 22f;
    static final float SIGNATURE_HELP_WIDTH_DP = 320f;
    // v2.38 : 12dp (anciens 6dp) + bande documentation sous la liste.
    static final float SIGNATURE_HELP_RADIUS_DP = 12f;
    static final int SIGNATURE_HELP_MAX_ROWS = 10;
    /** v2.38 — lignes max de la documentation de la signature active. */
    static final int SIGNATURE_DOC_MAX_LINES = 6;
    // Quick doc popup
    // v2.38 : 12dp (anciens 6dp uniformes) — carte flottante.
    static final float QUICK_DOC_MAX_WIDTH_DP = 320f;
    static final float QUICK_DOC_MAX_HEIGHT_DP = 300f;
    static final float QUICK_DOC_RADIUS_DP = 12f;
    // Code actions lightbulb
    // v2.31: reduced from 7dp — the bulb (+ glow ring) overflowed the fold
    // strip (~16.8dp) and the line height; 5.5dp keeps it comfortably inside
    // ("réduire un petit peu la taille de l'info-bulb dans le gutter").
    static final float LIGHTBULB_RADIUS_DP = 5.5f;
    // Overlay layers (diagnostic chips / popup / sheet)
    static final float OVERLAY_RADIUS_DP = 6f;
    static final float OVERLAY_ROW_HEIGHT_DP = 26f;
    // Diagnostic squiggle
    static final float SQUIGGLE_PERIOD = 6f;
    static final float SQUIGGLE_AMPLITUDE = 2f;
    // Indent guides
    static final int INDENT_UNIT_COLS = 4;
    // Caret blink (solid-after-edit + blink period)
    static final long CARET_BLINK_MS = 530;
    static final long CARET_SOLID_AFTER_EDIT_MS = 530;

    // v3.31.1: reusable scratch objects — avoid per-frame / per-line allocation.
    // Single-threaded (only used from onDraw on the UI thread), so no sync needed.
    private final Path scratchPath = new Path();
    /** v2.38 — rayons par coin du popup de complétion (réutilisé chaque frame). */
    private final float[] completionCornerRadii = new float[8];
    private final RectF scratchRectF = new RectF();

    EditorRenderer(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Main entry point — called by EditorView.onDraw()
    // ════════════════════════════════════════════════════════════════

    /** Main entry point — called by {@link EditorView#onDraw(Canvas)}. */
    void draw(Canvas canvas) {
        if (view.session == null) return;

        // v3.33.5: BlockEditor mode supprimé (code mort).

        final EditorDocument doc = view.session.getDocument();
        final List<StyledLine> styledLines = view.session.getStyledLines();
        final Selection sel = view.clampSelection(view.session.getSelection(), doc);

        final int width = view.getWidth();
        final int height = view.getHeight();
        final float lineHeight = view.metrics.getLineHeight();
        final float paddingTop = view.metrics.getPadTop();
        final float paddingLeft = view.metrics.getPadLeft();
        final float gutterWidth = view.metrics.getGutterWidth();
        final float textAreaLeft = gutterWidth + paddingLeft;

        final int currentLine = clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        // v3.5.0: firstVisible/lastVisible are VISUAL ROWS (not doc lines).
        // When collapsed folds exist, visual row N maps to a doc line > N
        // (because hidden lines are skipped). Using visual rows directly as
        // doc-line indices makes the draw loop iterate too narrow a range —
        // the bottom of the viewport isn't redrawn, and non-fold-aware draw
        // passes (selection, indent guides, squiggles) draw at wrong Y.
        // Fix: when folds are present, compute the doc-line range via the
        // fold-aware docLineForScreenY mapper (same as word-wrap does).
        boolean hasCollapsedFolds = !view.session.getCollapsedFolds().isEmpty();
        final int firstVisible = Math.max(0, (int) Math.floor(view.vOffset / lineHeight) - 1);
        final int lastVisible  = Math.min(doc.lineCount() - 1,
                                          (int) Math.ceil((view.vOffset + height) / lineHeight) + 1);
        // When word wrap is on, the visible doc-line range is wider because each
        // line spans several visual rows. Compute an inclusive doc-line range.
        // v3.5.0: also compute fold-aware range when collapsed folds exist.
        final int firstDocVisible = (view.wordWrap || hasCollapsedFolds)
            ? clamp(view.docLineForScreenY(0), 0, doc.lineCount() - 1)
            : firstVisible;
        final int lastDocVisible = (view.wordWrap || hasCollapsedFolds)
            ? clamp(view.docLineForScreenY(height), 0, doc.lineCount() - 1)
            : lastVisible;

        // 1. Editor background
        view.bgPaint.setColor(view.theme.editorBg);
        canvas.drawRect(0, 0, width, height, view.bgPaint);

        // v3.15.0: refreshCodeActions is NO LONGER called from the draw path.
        // It was blocking the UI thread on every frame with LSP calls.
        // It's now debounced via scheduleCodeActionsRefresh() in onTextChanged()
        // and on large scroll/selection changes.

        // 2. Current-line band (only when caret is collapsed — no band during a selection)
        if (sel.isCursor()) {
            float lineY = view.docLineToY(currentLine) - view.vOffset;
            int rows = view.wordWrap ? view.rowsForDocLine(currentLine) : 1;
            float bandH = lineHeight * rows;
            if (lineY < height && lineY + bandH > 0) {
                view.bgPaint.setColor(view.theme.currentLine);
                canvas.drawRect(gutterWidth, lineY, width, lineY + bandH, view.bgPaint);
            }
        }

        // 3. Indent guides (faint verticals at each INDENT_UNIT_COLS) — drawn before text
        drawIndentGuides(canvas, doc, firstVisible, lastVisible, textAreaLeft, lineHeight, paddingTop);

        // v3.16.0: Gutter is now drawn AFTER text (as a semi-transparent
        // overlay) so the text scrolling behind it is faintly visible.
        // Previously drawn at step 4 (before text) as opaque — now moved
        // to after the text+squiggles block. Only the fold chevrons are
        // drawn here (in the gutter clip) so they're on top of the text.
        // The full gutter background + line numbers are drawn later.

        // 5. Selection highlight (per-line band, one rect per line in viewport)
        if (!sel.isCursor()) {
            try {
                drawSelection(canvas, doc, sel, textAreaLeft, lineHeight, paddingTop, firstVisible, lastVisible);
            } catch (RuntimeException ignored) {
            }
        }

        // 6. Find highlights (every match tinted; the current match stronger).
        //    Drawn BEFORE text so the syntax colors sit on top.
        if (!view.findHighlights.isEmpty()) {
            try {
                drawFindHighlights(canvas, doc, textAreaLeft, lineHeight, paddingTop,
                                    firstVisible, lastVisible);
            } catch (RuntimeException ignored) {}
        }

        // 6.5. Document highlights (v3.33.11) — LSP occurrences of the
        //      symbol under the caret. Drawn as a soft-tint rectangle
        //      behind the text (between find-highlights and text).
        if (!view.documentHighlights.isEmpty()) {
            try {
                drawDocumentHighlights(canvas, doc, textAreaLeft, lineHeight,
                    paddingTop, firstVisible, lastVisible);
            } catch (RuntimeException ignored) {}
        }

        // v3.16.0: When preview mode is active, the text area is clipped to
        // exclude the preview pane. In FULL mode, no text is drawn at all.
        int effectiveWidth = width;
        if (view.previewMode == EditorView.PreviewMode.SPLIT) {
            effectiveWidth = width / 2;
        } else if (view.previewMode == EditorView.PreviewMode.FULL) {
            view.selPaint.setColor(view.theme.gutterBorder);
            view.selPaint.setStrokeWidth(1f);
            canvas.drawLine(0, 0, 0, height, view.selPaint);
            return;
        }

        // 7. Text — v3.16.0: NO gutter clip! Text is drawn full-width so it
        //    scrolls under the gutter area. The gutter (semi-transparent)
        //    is drawn ON TOP of the text later, creating the glass effect.
        //    text for fold-start lines + inlay hints woven in + semantic tokens
        //    overlaid on top of lexical spans. Word-wrap-aware: each doc line
        //    may span multiple visual rows, and we render each row separately.
        //    The per-line render cache (Gap 3) supplies the StyledLine +
        //    filtered inlays + filtered sem spans so the draw path is O(visible
        //    lines) instead of O(visible lines × global token count).
        //
        //    v3.13.0: squiggles are now drawn INSIDE this clipRect block so
        //    they can never extend into the gutter area. Previously they were
        //    drawn after canvas.restore() with no clip, which meant a squiggle
        //    at column 0 on a line scrolled right (hOffset > 0) would draw
        //    on top of the gutter — appearing to float above the gutter
        //    instead of being clipped behind it.
        // v3.16.0: clip to effectiveWidth (excludes preview pane) but NOT
        // to gutterWidth — text scrolls under the gutter and the gutter
        // overlay is drawn on top later.
        canvas.save();
        canvas.clipRect(0, 0, effectiveWidth, height);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
        int docStart = Math.min(firstVisible, firstDocVisible);
        int docEnd = Math.max(lastVisible, lastDocVisible);
        for (int i = docStart; i <= docEnd; i++) {
            // Fold: skip hidden lines entirely. They occupy no visual row.
            if (view.isLineFoldedCached(i)) continue;
            float lineY = view.docLineToY(i) - view.vOffset;
            String lineText = doc.lineText(i);

            // Composite text for the START line of a collapsed fold:
            // "if (x) {" + "…" + "}" on a single visual row.
            DiagnosticShift.FoldRegion collapsed = view.collapsedFoldStartingAtLine(i);
            if (collapsed != null) {
                drawCompositeFoldLine(canvas, doc, collapsed, textAreaLeft - view.hOffset, lineY, view.textPaint);
                continue;
            }

            if (view.wordWrap && view.wrapModel != null && view.wrapModel.rowsOf(i) > 1) {
                // Wrapped line: render each visual row separately.
                drawWrappedLine(canvas, doc, styledLines, i, lineText, textAreaLeft, lineY, lineHeight);
            } else {
                // Cache lookup: O(1) hit on unchanged lines, recomputes the
                // filtered inlays/sem spans + column maps on miss.
                LineRenderCache.LineCacheEntry layout = view.layoutForLine(i, lineText);
                StyledLine styled = layout != null ? (StyledLine) layout.layout : null;
                if (styled != null) {
                    drawStyledLine(canvas, styled, lineText, textAreaLeft - view.hOffset, lineY, view.textPaint,
                        layout.inlays, layout.rawToVisual);
                    // v3.18.0: Draw non-printable chars if enabled.
                    if (view.showNonPrintable) {
                        drawNonPrintableChars(canvas, lineText, textAreaLeft - view.hOffset, lineY,
                            view.metrics.getCharWidth(), lineHeight, layout.inlays, layout.rawToVisual);
                    }
                    // Semantic token overlay (recolors specific ranges with
                    // server-provided token types — e.g. method/class/enum).
                    // Uses the cached per-line filtered spans.
                    if (layout.semSpans != null && !layout.semSpans.isEmpty()) {
                        drawCachedSemSpans(canvas, layout.semSpans, lineText,
                            textAreaLeft - view.hOffset, lineY, view.textPaint,
                            layout.inlays, layout.rawToVisual);
                    }
                    // Inlay hints: phantom text woven INTO the line (v2.31 —
                    // the text after a hint is shifted right, CodeAssist parity).
                    if (layout.inlays != null && !layout.inlays.isEmpty()) {
                        drawCachedInlays(canvas, layout.inlays, layout.rawToVisual, lineY, view.textPaint);
                    }
                } else {
                    view.textPaint.setColor(view.theme.textColor);
                    canvas.drawText(lineText, textAreaLeft - view.hOffset, lineY + lineHeight * 0.78f, view.textPaint);
                }
            }
        }
        // Inlay hints that fall on lines OUTSIDE the cache-warm visible range
        // (rare — happens only when the cache was just cleared) are drawn via
        // the legacy global-iteration path so we never miss one.
        // drawInlayHints() is intentionally NOT called here — every visible
        // line's inlays are drawn per-line via drawCachedInlays() above.

        // v3.13.0: Diagnostic squiggles — drawn INSIDE the text-area clip
        // so they can never extend into the gutter area. Previously they
        // were drawn after canvas.restore() with no clip, which meant a
        // squiggle at column 0 on a line scrolled right (hOffset > 0)
        // would draw on top of the gutter — appearing to float above the
        // gutter instead of being clipped behind it.
        drawSquiggles(canvas, doc, firstVisible, lastVisible, textAreaLeft, lineHeight, paddingTop);

        // v2.32: matching-bracket boxes (CodeAssist parity) — a 1px STROKE
        // rectangle around each bracket of the pair under/behind the caret,
        // in the accent color at 45% alpha. Drawn after the squiggles and
        // inside the text-area clip, exactly like CodeAssist's EditorRendering.
        drawBracketMatchBoxes(canvas, doc, textAreaLeft, lineHeight, firstVisible, lastVisible);

        canvas.restore();

        // v3.16.0: Draw the gutter ON TOP of the text as a semi-transparent
        // overlay. This creates the glass effect — text scrolling behind the
        // gutter is faintly visible through the 88%-alpha background.
        canvas.save();
        canvas.clipRect(0, 0, gutterWidth, height);
        view.gutterView.draw(canvas, view.vOffset, height, doc.lineCount(), currentLine);
        drawFoldChevrons(canvas, doc, firstVisible, lastVisible, lineHeight, paddingTop);
        canvas.restore();

        // v3.13.0: Draw preview divider line in SPLIT mode.
        if (view.previewMode == EditorView.PreviewMode.SPLIT) {
            int dividerX = width / 2;
            view.selPaint.setColor(view.theme.gutterBorder);
            view.selPaint.setStrokeWidth(1f);
            canvas.drawLine(dividerX, 0, dividerX, height, view.selPaint);
        }

        // 9. Caret
        drawCaret(canvas, doc, sel, textAreaLeft, lineHeight, paddingTop);

        // 10. Selection handles (mobile) — drawn after the caret so they
        //     sit on top of the text and are easy to grab.
        drawSelectionHandles(canvas);

        // 11. Completion popup (drawn last so it sits on top of everything)
        drawCompletionPopup(canvas);

        // 12. Signature help popup (drawn last — sits above the caret line)
        drawSignatureHelpPopup(canvas);

        // 13. Quick doc popup (Gap 4)
        drawQuickDocPopup(canvas);

        // 14. Code actions lightbulbs + popup (Gap 5)
        drawCodeActionsBulbs(canvas, firstVisible, lastVisible, lineHeight, paddingTop);
        drawCodeActionsPopup(canvas);
        // 14.5 v2.36: menu contextuel unifié (NavMenu — toolbar de sélection)
        drawNavMenu(canvas);

        // 15. Go-to-symbol popup (Gap 6)
        drawGoToSymbolPopup(canvas);

        // 16. EditorOverlayLayers (Gap 8): diagnostic chips + selection
        //     toolbar + diagnostic sheet. (Go-to-line and rename now use
        //     real Android PopupWindow — v1.0.9 — so they're not drawn
        //     on the Canvas.)
        if (view.diagnosticChipsEnabled) {
            drawDiagnosticChips(canvas, doc, firstVisible, lastVisible, lineHeight, paddingTop);
        }
        drawSelectionToolbar(canvas);
        drawDiagnosticPopup(canvas);
        drawDiagnosticSheet(canvas);

        // v3.17.0: Draw preview icons in the top-right corner when the file
        // is previewable (.md/.html/.xml) and preview mode is NONE.
        drawPreviewIcons(canvas);
        // v3.22.0: Draw XML layout preview if active.
        if (view.isXmlPreviewActive()) {
            drawXmlPreview(canvas);
        }
        // v3.20.0: Toolbar icons (A+, A-, ¶, ==) moved to EditorBarTools view.
        // v3.18.0: Draw magnifier if active (currently disabled).
        drawMagnifier(canvas);

        // v3.31.1: Draw the minimap on the right edge.
        if (view.minimapEnabled) {
            drawMinimap(canvas);
        }
    }

    /**
     * v3.31.1: Draws a tiny preview of the entire file on the right edge of
     * the editor. Each document line is rendered as a single horizontal row
     * of colored segments — scaled down to {@link EditorView#MINIMAP_LINE_HEIGHT_PX}
     * px tall and {@link EditorView#MINIMAP_WIDTH_DP} dp wide. A
     * scrollbar-like rectangle indicates the current viewport.
     *
     * <p>The minimap uses the session's {@link StyledLine}s (already computed
     * by the {@link jo.codeeditor.highlight.SyntaxHighlighter}) so the colors
     * match the editor. We only iterate over the visible lines (after the
     * vOffset) plus a few above/below — drawing the entire file would cost
     * O(N) per frame, which is too slow for 50k-LOC files.</p>
     */
    private void drawMinimap(Canvas canvas) {
        if (view.session == null) return;
        EditorDocument doc = view.session.getDocument();
        int lineCount = doc.lineCount();
        if (lineCount <= 0) return;

        int minimapLeft = view.getMinimapLeft();
        int minimapWidth = view.getMinimapWidth();
        int height = view.getHeight();
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = EditorView.MINIMAP_LINE_HEIGHT_PX * density;

        // Background of the minimap strip.
        view.bgPaint.setColor(view.applyAlphaToColor(view.theme.editorBg, 1f));
        // Slightly different from the editor bg so the strip is visible.
        int darker = darkenColor(view.theme.editorBg, 0.85f);
        view.bgPaint.setColor(darker);
        canvas.drawRect(minimapLeft, 0, minimapLeft + minimapWidth, height, view.bgPaint);

        // Divider line on the left edge of the minimap.
        view.selPaint.setColor(view.theme.gutterBorder);
        view.selPaint.setStrokeWidth(1f);
        canvas.drawLine(minimapLeft, 0, minimapLeft, height, view.selPaint);

        // Determine which doc lines fall within the minimap viewport.
        // We render ~height/rowH lines starting from the line at the top of
        // the editor's viewport.
        float editorLineHeight = view.metrics.getLineHeight();
        int firstVisibleDocLine = Math.max(0, (int) (view.vOffset / editorLineHeight) - 5);
        int visibleRows = (int) (height / rowH) + 10;
        int lastVisibleDocLine = Math.min(lineCount - 1, firstVisibleDocLine + visibleRows);

        // For each visible line, draw a row of colored segments based on
        // the StyledLine's token spans.
        java.util.List<jo.codeeditor.highlight.StyledLine> styledLines = view.session.getStyledLines();
        float charWidth = view.metrics.getCharWidth();
        float maxCharsPerRow = Math.max(1, minimapWidth / Math.max(1, charWidth));

        for (int i = firstVisibleDocLine; i <= lastVisibleDocLine; i++) {
            float y = i * rowH - (view.vOffset / editorLineHeight) * rowH;
            if (y < -rowH || y > height) continue;
            if (i >= styledLines.size()) break;
            jo.codeeditor.highlight.StyledLine sl = styledLines.get(i);
            if (sl == null) continue;
            java.util.List<jo.codeeditor.highlight.LineSpan> spans = sl.spans;
            if (spans == null || spans.isEmpty()) continue;
            float x = minimapLeft;
            for (jo.codeeditor.highlight.LineSpan span : spans) {
                if (span == null) continue;
                int color = view.theme.colorForToken(span.type);
                view.squigglePaint.setColor(color);
                view.squigglePaint.setStyle(android.graphics.Paint.Style.FILL);
                // Each span is drawn as a thin horizontal bar — width
                // proportional to span length, capped at minimap width.
                float segW = Math.min(span.endCol - span.startCol, maxCharsPerRow) * (charWidth * 0.4f);
                if (segW < 0.5f) segW = 0.5f;
                canvas.drawRect(x, y, x + segW, y + rowH, view.squigglePaint);
                x += segW;
                if (x >= minimapLeft + minimapWidth) break;
            }
        }

        // Viewport rectangle: shows which portion of the file is currently visible.
        float viewportY = 0;
        float viewportH = (height / editorLineHeight) * rowH;
        view.selPaint.setColor(view.applyAlphaToColor(view.theme.selection, 0.25f));
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        canvas.drawRect(minimapLeft, viewportY, minimapLeft + minimapWidth, viewportY + viewportH, view.selPaint);
        view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
        view.selPaint.setStrokeWidth(1f);
        view.selPaint.setColor(view.applyAlphaToColor(view.theme.selection, 0.6f));
        canvas.drawRect(minimapLeft, viewportY, minimapLeft + minimapWidth, viewportY + viewportH, view.selPaint);
    }

    /** Darkens an ARGB color by the given factor (0..1). */
    private static int darkenColor(int color, float factor) {
        int a = (color >>> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * v3.22.0: Draws the XML layout preview in the preview pane area.
     * v3.31.0: Now delegates to the EditorPreviewHost (decoupled).
     */
    private void drawXmlPreview(Canvas canvas) {
        EditorPreviewHost host = view.getPreviewHost();
        if (host == null || !host.hasPreviewContent()) return;

        int previewLeft = view.getPreviewLeft();
        int previewWidth = view.getPreviewWidth();
        int height = view.getHeight();

        // Draw preview background.
        view.bgPaint.setColor(view.theme.editorBg);
        canvas.drawRect(previewLeft, 0, previewLeft + previewWidth, height, view.bgPaint);

        // Draw the preview via the host.
        host.drawPreview(canvas, previewLeft, 0);

        // Draw preview divider line (same as the SPLIT divider).
        view.selPaint.setColor(view.theme.gutterBorder);
        view.selPaint.setStrokeWidth(1f);
        canvas.drawLine(previewLeft, 0, previewLeft, height, view.selPaint);
    }

    /**
     * v3.17.0: Draws the split and full preview icons in the top-right corner.
     * Only drawn when the file is previewable and preview mode is NONE.
     */
    private void drawPreviewIcons(Canvas canvas) {
        if (!view.previewable || view.previewMode != EditorView.PreviewMode.NONE) return;
        float density = view.getResources().getDisplayMetrics().density;
        float iconSize = 24f * density;
        float margin = 8f * density;
        float iconY = margin;
        float iconW = iconSize;
        float fullX = view.getWidth() - margin - iconW;
        float splitX = fullX - iconW - margin * 0.5f;
        int iconColor = view.applyAlphaToColor(view.theme.gutterText, 0.65f);
        int accentColor = view.applyAlphaToColor(view.theme.keyword, 0.85f);
        float cornerR = 3f * density;

        // Split preview icon: two filled rounded rects side by side.
        // Left pane (editor) — accent color tint.
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        view.selPaint.setColor(view.applyAlphaToColor(accentColor, 0.35f));
        float paneW = iconW * 0.38f;
        float paneH = iconW * 0.7f;
        float paneY = iconY + (iconW - paneH) * 0.5f;
        android.graphics.RectF leftPane = new android.graphics.RectF(
            splitX + iconW * 0.05f, paneY,
            splitX + iconW * 0.05f + paneW, paneY + paneH);
        canvas.drawRoundRect(leftPane, cornerR, cornerR, view.selPaint);
        // Left pane border.
        view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
        view.selPaint.setStrokeWidth(1.2f * density);
        view.selPaint.setColor(iconColor);
        canvas.drawRoundRect(leftPane, cornerR, cornerR, view.selPaint);
        // Right pane (preview) — outline only.
        android.graphics.RectF rightPane = new android.graphics.RectF(
            splitX + iconW * 0.55f, paneY,
            splitX + iconW * 0.55f + paneW, paneY + paneH);
        view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
        canvas.drawRoundRect(rightPane, cornerR, cornerR, view.selPaint);
        // Vertical divider line between panes.
        float divX = splitX + iconW * 0.5f;
        canvas.drawLine(divX, paneY + 2f * density, divX, paneY + paneH - 2f * density, view.selPaint);

        // Full preview icon: filled eye with pupil.
        float cx = fullX + iconW * 0.5f;
        float cy = iconY + iconW * 0.5f;
        float eyeW = iconW * 0.7f;
        float eyeH = iconW * 0.42f;
        // Eye shape — filled almond.
        android.graphics.Path eyePath = new android.graphics.Path();
        eyePath.moveTo(cx - eyeW * 0.5f, cy);
        // Top curve.
        android.graphics.RectF topArc = new android.graphics.RectF(
            cx - eyeW * 0.5f, cy - eyeH,
            cx + eyeW * 0.5f, cy + eyeH);
        eyePath.addArc(topArc, 200, 140);
        // Bottom curve (mirror).
        android.graphics.RectF botArc = new android.graphics.RectF(
            cx - eyeW * 0.5f, cy - eyeH * 0.3f,
            cx + eyeW * 0.5f, cy + eyeH * 1.7f);
        eyePath.arcTo(botArc, 20, 140);
        eyePath.close();
        // Fill eye background.
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        view.selPaint.setColor(view.applyAlphaToColor(iconColor, 0.15f));
        canvas.drawPath(eyePath, view.selPaint);
        // Eye outline.
        view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
        view.selPaint.setStrokeWidth(1.5f * density);
        view.selPaint.setColor(iconColor);
        canvas.drawPath(eyePath, view.selPaint);
        // Pupil (filled circle).
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        view.selPaint.setColor(iconColor);
        canvas.drawCircle(cx, cy, iconW * 0.12f, view.selPaint);
    }

    // ════════════════════════════════════════════════════════════════
    // v3.18.0: Non-printable characters
    // ════════════════════════════════════════════════════════════════

    /**
     * v3.18.0: Draws non-printable character indicators (spaces, tabs, newlines)
     * for the given line. Called after the styled line text is drawn.
     *
     * <p>Visual representation:
     * <ul>
     *   <li>Space → faint middle dot (·) centered in the char cell</li>
     *   <li>Tab → faint right-arrow (→) centered in the char cell</li>
     *   <li>Trailing newline → faint ¬ at the end of the line</li>
     * </ul>
     */
    private void drawNonPrintableChars(Canvas canvas, String lineText, float x, float y,
                                        float charWidth, float lineHeight,
                                        List<LineRenderCache.InlayPiece> inlays, int[] rawToVisual) {
        view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
        view.textPaint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.35f));
        view.textPaint.setFakeBoldText(false);
        for (int i = 0; i < lineText.length(); i++) {
            char c = lineText.charAt(i);
            // v2.31: inlay-aware position — the visual column of raw char i is
            // rawToVisual[i] + (inlays anchored AT i woven before it).
            int vis = (rawToVisual != null && i < rawToVisual.length) ? rawToVisual[i] : i;
            if (c == ' ') {
                canvas.drawText("·", x + vis * charWidth + charWidth * 0.35f,
                    y + lineHeight * 0.78f, view.textPaint);
            } else if (c == '\t') {
                canvas.drawText("→", x + vis * charWidth + charWidth * 0.2f,
                    y + lineHeight * 0.78f, view.textPaint);
            }
        }
        // Trailing newline indicator (¬) at end of line.
        int endVis = (rawToVisual != null && lineText.length() < rawToVisual.length)
            ? rawToVisual[lineText.length()] : lineText.length();
        canvas.drawText("¬", x + endVis * charWidth + charWidth * 0.2f,
            y + lineHeight * 0.78f, view.textPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // v3.18.0: Magnifier
    // ════════════════════════════════════════════════════════════════

    /**
     * v3.18.0: Draws a magnifier (zoom bubble) above the finger position
     * during drag operations. Shows a 2x zoomed view of the text around
     * the magnifier center.
     */
    private void drawMagnifier(Canvas canvas) {
        if (!view.magnifierActive) return;
        float density = view.getResources().getDisplayMetrics().density;
        float magRadius = 60f * density; // magnifier radius
        float magCx = view.magnifierX;
        // Position the magnifier ABOVE the finger so it doesn't cover content.
        float magCy = view.magnifierY - magRadius * 1.8f;
        // Clamp so it doesn't go off-screen.
        magCy = Math.max(magRadius, magCy);

        // Save the canvas state.
        canvas.save();
        // Clip to a circle.
        android.graphics.Path clipPath = new android.graphics.Path();
        clipPath.addCircle(magCx, magCy, magRadius, android.graphics.Path.Direction.CW);
        canvas.clipPath(clipPath);

        // Draw the magnifier background.
        view.bgPaint.setColor(view.theme.editorBg);
        canvas.drawRect(magCx - magRadius, magCy - magRadius, magCx + magRadius, magCy + magRadius, view.bgPaint);

        // Scale the canvas around the magnifier center (2x zoom).
        canvas.scale(2f, 2f, magCx, magCy);
        // Translate so the content under the finger is centered in the magnifier.
        canvas.translate(-(view.magnifierX - magCx), -(view.magnifierY - magCy));

        // Re-draw the text content at the magnifier position.
        // We use the existing draw pipeline by calling draw() recursively —
        // but that's too heavy. Instead, just draw a few lines around the
        // touch point.
        if (view.session != null) {
            jo.codeeditor.document.EditorDocument doc = view.session.getDocument();
            int caretLine = view.docLineForScreenY(view.magnifierY);
            int firstLine = Math.max(0, caretLine - 3);
            int lastLine = Math.min(doc.lineCount() - 1, caretLine + 3);
            float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
            float lineHeight = view.metrics.getLineHeight();
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize());
            for (int i = firstLine; i <= lastLine; i++) {
                if (view.isLineFoldedCached(i)) continue;
                float lineY = view.docLineToY(i) - view.vOffset;
                String lineText = doc.lineText(i);
                jo.codeeditor.highlight.StyledLine styled = null;
                java.util.List<jo.codeeditor.highlight.StyledLine> styledLines = view.session.getStyledLines();
                if (i < styledLines.size()) styled = styledLines.get(i);
                if (styled != null) {
                    // v2.31: magnifier path — weave the line's inlays too so
                    // the zoomed view matches the real rendering.
                    LineRenderCache.LineCacheEntry layout = view.layoutForLine(i, lineText);
                    drawStyledLine(canvas, styled, lineText, textAreaLeft - view.hOffset, lineY, view.textPaint,
                        layout != null ? layout.inlays : null,
                        layout != null ? layout.rawToVisual : null);
                } else {
                    view.textPaint.setColor(view.theme.textColor);
                    canvas.drawText(lineText, textAreaLeft - view.hOffset, lineY + lineHeight * 0.78f, view.textPaint);
                }
            }
        }
        canvas.restore();

        // Draw the magnifier border (ring).
        view.selPaint.setStyle(Paint.Style.STROKE);
        view.selPaint.setStrokeWidth(2f * density);
        view.selPaint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.5f));
        canvas.drawCircle(magCx, magCy, magRadius, view.selPaint);
        // Subtle inner shadow ring.
        view.selPaint.setStrokeWidth(1f * density);
        view.selPaint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.2f));
        canvas.drawCircle(magCx, magCy, magRadius - 2f * density, view.selPaint);
    }

    // ════════════════════════════════════════════════════════════════
    // Signature help popup
    // ════════════════════════════════════════════════════════════════

    /**
     * Draws the signature help popup. Anchored ABOVE the caret line
     * (unlike the completion popup which is BELOW). If there's no room
     * above, falls back to below. Each signature is one row; the active
     * parameter is highlighted in bold + accent color.
     */
    void drawSignatureHelpPopup(Canvas canvas) {
        if (!view.signatureHelpVisible || view.signatureHelpData == null
            || view.signatureHelpData.signatures == null
            || view.signatureHelpData.signatures.isEmpty()) {
            return;
        }
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = SIGNATURE_HELP_ROW_HEIGHT_DP * density;
        float width = SIGNATURE_HELP_WIDTH_DP * density;
        float radius = SIGNATURE_HELP_RADIUS_DP * density;
        int rowsToShow = Math.min(SIGNATURE_HELP_MAX_ROWS, view.signatureHelpData.signatures.size());

        // ── ★ v2.38 — documentation de la signature ACTIVE (javadoc de sa
        // déclaration source) : bande sous la liste des surcharges, motif
        // IntelliJ/CodeAssist. Parsée par QuickDoc (description + sections
        // compactées), cap SIGNATURE_DOC_MAX_LINES lignes.
        //
        // ── ★ v2.39 — l'index actif vient du controller (override clavier
        // Up/Down via cycleActiveSignature). Si l'override n'est pas posé,
        // on retombe sur activeSignature du serveur LSP.
        List<String> docLines = new ArrayList<>();
        int activeIdx = view.getEffectiveActiveSignature();
        if (activeIdx < 0) {
            activeIdx = Math.max(0, Math.min(view.signatureHelpData.activeSignature,
                    view.signatureHelpData.signatures.size() - 1));
        }
        jo.codeeditor.completion.SignatureHelpController.Signature activeSig =
                view.signatureHelpData.signatures.get(activeIdx);
        if (activeSig != null && activeSig.documentation != null
                && !activeSig.documentation.isEmpty()) {
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            jo.codeeditor.doc.QuickDoc.QuickDocContent parsed =
                    jo.codeeditor.doc.QuickDoc.parseQuickDoc(
                            activeSig.documentation, "java");
            float docWidth = width - 16 * density;
            if (!parsed.description.isEmpty()) {
                docLines.addAll(wrapText(parsed.description, docWidth, view.textPaint));
            }
            for (jo.codeeditor.doc.QuickDoc.DocSection s : parsed.sections) {
                if (docLines.size() >= SIGNATURE_DOC_MAX_LINES) break;
                for (String item : s.items) {
                    if (docLines.size() >= SIGNATURE_DOC_MAX_LINES) break;
                    String line = s.title + " " + item;
                    docLines.addAll(wrapText(line, docWidth, view.textPaint));
                }
            }
            if (docLines.size() > SIGNATURE_DOC_MAX_LINES) {
                docLines = new ArrayList<>(
                        docLines.subList(0, SIGNATURE_DOC_MAX_LINES));
            }
        }
        float docRowH = view.metrics.getTextSize() * 0.75f * 1.3f;
        float docH = docLines.isEmpty() ? 0
                : docLines.size() * docRowH + 8 * density;
        float popupH = rowH * rowsToShow + docH;

        // Anchor X = caret column, Y = top of caret line.
        // v2.31: inlay-aware — the popup follows the woven caret X.
        EditorDocument doc = view.session.getDocument();
        Selection sel = view.clampSelection(view.session.getSelection(), doc);
        int line = clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        int col = sel.start - doc.lineStart(line);
        float anchorX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
            + view.visualColFor(line, col) * view.metrics.getCharWidth() - view.hOffset;
        float caretY = view.docLineToY(line) - view.vOffset;
        // Default: popup sits ABOVE the caret line.
        float anchorY = caretY - 4 - popupH;
        // If popup doesn't fit above, flip below.
        if (anchorY < 0) {
            anchorY = caretY + view.metrics.getLineHeight() + 4;
        }
        // v2.38 : clamp bas — le popup ne sort plus de l'éditeur.
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, view.getHeight() - popupH - 4);
        }
        // Clamp horizontally.
        float viewW = view.getWidth();
        if (anchorX + width > viewW) {
            anchorX = Math.max(view.metrics.getGutterWidth(), viewW - width - 4);
        }

        // Background + border
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + popupH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);

        // Rows — v2.38 : clippées au rect (labels longs ellipsisés de fait).
        canvas.save();
        canvas.clipRect(rect);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        float padX = 8 * density;
        for (int i = 0; i < rowsToShow; i++) {
            jo.codeeditor.completion.SignatureHelpController.Signature sig =
                view.signatureHelpData.signatures.get(i);
            float y = anchorY + i * rowH;
            // Highlight active signature row.
            // v2.39: use the effective active signature (override-aware).
            if (i == activeIdx) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            // Draw the label. The active parameter is rendered in accent
            // color + bold by splitting the label around it.
            String label = sig.label;
            int activeParam = sig.activeParameter;
            // Find the active parameter's range within the label (best-effort:
            // split by commas at depth 0 — same logic as activeParameterIndex).
            int[] paramRange = findParameterRangeInLabel(label, activeParam);
            if (paramRange == null) {
                view.textPaint.setColor(view.theme.textColor);
                canvas.drawText(label, anchorX + padX, y + rowH * 0.7f, view.textPaint);
            } else {
                // Draw pre-param, param (accent + bold), post-param.
                String pre = label.substring(0, paramRange[0]);
                String param = label.substring(paramRange[0], paramRange[1]);
                String post = label.substring(paramRange[1]);
                float x = anchorX + padX;
                if (!pre.isEmpty()) {
                    view.textPaint.setColor(view.theme.textColor);
                    view.textPaint.setTypeface(view.metrics.getTypeface());
                    canvas.drawText(pre, x, y + rowH * 0.7f, view.textPaint);
                    x += view.textPaint.measureText(pre);
                }
                if (!param.isEmpty()) {
                    view.textPaint.setColor(view.theme.func);
                    android.graphics.Typeface bold = android.graphics.Typeface.create(
                        view.metrics.getTypeface(), android.graphics.Typeface.BOLD);
                    view.textPaint.setTypeface(bold);
                    canvas.drawText(param, x, y + rowH * 0.7f, view.textPaint);
                    x += view.textPaint.measureText(param);
                }
                if (!post.isEmpty()) {
                    view.textPaint.setColor(view.theme.textColor);
                    view.textPaint.setTypeface(view.metrics.getTypeface());
                    canvas.drawText(post, x, y + rowH * 0.7f, view.textPaint);
                }
            }
        }
        // ── ★ v2.38 — bande documentation de la signature ACTIVE ──
        if (!docLines.isEmpty()) {
            float docTop = anchorY + rowsToShow * rowH;
            // Divider + fond légèrement teinté.
            view.caretPaint.setStyle(Paint.Style.STROKE);
            view.caretPaint.setStrokeWidth(1f);
            view.caretPaint.setColor(view.theme.glassBorder);
            canvas.drawLine(anchorX, docTop, anchorX + width, docTop,
                    view.caretPaint);
            view.selPaint.setColor(view.theme.selection);
            canvas.drawRect(anchorX, docTop, anchorX + width,
                    anchorY + popupH, view.selPaint);
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            for (int i = 0; i < docLines.size(); i++) {
                String l = docLines.get(i);
                boolean isTag = !l.isEmpty() && l.charAt(0) == '@';
                view.textPaint.setColor(isTag
                        ? view.theme.keyword : view.theme.textColor);
                float baseline = docTop + 4 * density + i * docRowH
                        + docRowH * 0.75f;
                canvas.drawText(l, anchorX + padX, baseline, view.textPaint);
            }
        }
        canvas.restore();
        // Reset paint state.
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    /**
     * Best-effort: finds the [start, end) range of the active parameter
     * inside a signature label like {@code "foo(int x, String y, int z)"}
     * so we can render it in accent color. Returns null if not found.
     */
    private static int[] findParameterRangeInLabel(String label, int activeParam) {
        int openIdx = label.indexOf('(');
        if (openIdx < 0) return null;
        int closeIdx = label.lastIndexOf(')');
        if (closeIdx <= openIdx) return null;
        int depth = 0;
        int paramStart = openIdx + 1;
        int paramIdx = 0;
        for (int i = openIdx + 1; i < closeIdx; i++) {
            char c = label.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                if (paramIdx == activeParam) {
                    return new int[]{paramStart, i};
                }
                paramStart = i + 1;
                paramIdx++;
            }
        }
        // Last param
        if (paramIdx == activeParam && paramStart < closeIdx) {
            return new int[]{paramStart, closeIdx};
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // Composite fold line
    // ════════════════════════════════════════════════════════════════

    /**
     * Draws the composite text for a fold-start line: prefix + placeholder + suffix.
     * The placeholder is tinted with a faint chip background so the fold is
     * visually distinct from a regular line.
     *
     * <p>v3.7.1 bugfix (Bug 5): previously the entire composite line (prefix,
     * placeholder, suffix) was drawn in a single {@code theme.textColor},
     * losing all syntax highlighting on the visible part of the line. So a
     * folded {@code public int add(int a, int b) {...}} lost its keyword
     * color (public/int) and type color (int) — everything looked white.
     * CodeAssist keeps the visible part colored; we now do the same by
     * delegating the prefix slice to {@link #drawStyledLine} (clipped to
     * {@code [0, prefixEndCol]}) and the suffix slice to a second
     * {@code drawStyledLine} call on the endLine's StyledLine (clipped to
     * {@code [suffixStartCol, lastLine.length())} with an X offset for the
     * prefix + placeholder widths).
     */
    void drawCompositeFoldLine(Canvas canvas, EditorDocument doc,
                                DiagnosticShift.FoldRegion fold,
                                float x, float y, Paint paint) {
        float charWidth = view.metrics.getCharWidth();
        float lineHeight = view.metrics.getLineHeight();
        int startLine = doc.lineForOffset(fold.start);
        int endLine = doc.lineForOffset(fold.end);
        String firstLine = doc.lineText(startLine);
        String lastLine = endLine < doc.lineCount() ? doc.lineText(endLine) : "";
        int prefixEndCol = clamp(fold.start - doc.lineStart(startLine), 0, firstLine.length());
        int suffixStartCol = clamp(fold.end - doc.lineStart(endLine), 0, lastLine.length());
        String prefix = firstLine.substring(0, prefixEndCol);
        String suffix = lastLine.substring(suffixStartCol);
        float prefixW = prefix.length() * charWidth;
        float placeW = fold.placeholder.length() * charWidth;

        // v3.7.1: Draw the prefix with syntax highlighting by looking up
        // the startLine's StyledLine and walking its spans, clipped to
        // [0, prefixEndCol]. Falls back to theme.textColor if no styles
        // are available (e.g. no Language set).
        boolean prefixColored = false;
        if (!prefix.isEmpty() && view.session != null) {
            LineRenderCache.LineCacheEntry layout = view.layoutForLine(startLine, firstLine);
            StyledLine styled = layout != null ? (StyledLine) layout.layout : null;
            if (styled != null && styled.spans != null && !styled.spans.isEmpty()) {
                // Walk the startLine's spans but clip each to [0, prefixEndCol].
                // We can't just call drawStyledLine(canvas, styled, firstLine, x, y, paint)
                // because that would draw the ENTIRE line (including the part
                // past the fold start that's hidden by the placeholder chip).
                // So we replicate drawStyledLine's body but skip spans that
                // don't intersect [0, prefixEndCol].
                for (LineSpan span : styled.spans) {
                    int start = clamp(span.startCol, 0, firstLine.length());
                    int end = clamp(span.endCol, 0, firstLine.length());
                    // Clip to prefix range.
                    if (end <= 0 || start >= prefixEndCol) continue;
                    int clippedStart = Math.max(start, 0);
                    int clippedEnd = Math.min(end, prefixEndCol);
                    if (clippedEnd <= clippedStart) continue;
                    String tokenText = firstLine.substring(clippedStart, clippedEnd);
                    // v3.3.1 color-preview support — keep parity with drawStyledLine.
                    Integer colorBg = null;
                    if (tokenText.startsWith("#") && tokenText.length() >= 4) {
                        colorBg = parseColorLiteral(tokenText);
                    }
                    if (colorBg != null) {
                        float bgX1 = x + clippedStart * charWidth;
                        float bgX2 = x + clippedEnd * charWidth;
                        float bgY1 = y + lineHeight * 0.1f;
                        float bgY2 = y + lineHeight * 0.9f;
                        view.selPaint.setColor(colorBg);
                        view.selPaint.setAntiAlias(false);
                        android.graphics.RectF bgRect = new android.graphics.RectF(bgX1, bgY1, bgX2, bgY2);
                        canvas.drawRoundRect(bgRect, lineHeight * 0.15f, lineHeight * 0.15f, view.selPaint);
                        view.selPaint.setAntiAlias(true);
                        paint.setColor(getContrastColor(colorBg));
                    } else {
                        paint.setColor(view.theme.colorForToken(span.type));
                    }
                    canvas.drawText(tokenText, x + clippedStart * charWidth,
                        y + lineHeight * 0.78f, paint);
                }
                prefixColored = true;
            }
        }
        if (!prefixColored && !prefix.isEmpty()) {
            paint.setColor(view.theme.textColor);
            canvas.drawText(prefix, x, y + lineHeight * 0.78f, paint);
        }

        // Draw placeholder chip background + text.
        view.selPaint.setColor(view.theme.findMatch);
        canvas.drawRect(x + prefixW, y, x + prefixW + placeW, y + lineHeight, view.selPaint);
        // v3.7.1: tint the placeholder text slightly so it reads as a chip
        // (CodeAssist uses a dimmer color for the … marker).
        paint.setColor(view.theme.annotation != 0 ? view.theme.annotation : view.theme.textColor);
        canvas.drawText(fold.placeholder, x + prefixW, y + lineHeight * 0.78f, paint);

        // v3.7.1: Draw the suffix with syntax highlighting by looking up
        // the endLine's StyledLine and walking its spans, clipped to
        // [suffixStartCol, lastLine.length()). The X offset accounts for
        // prefixW + placeW (everything drawn before the suffix).
        boolean suffixColored = false;
        if (!suffix.isEmpty() && view.session != null) {
            LineRenderCache.LineCacheEntry layout = view.layoutForLine(endLine, lastLine);
            StyledLine styled = layout != null ? (StyledLine) layout.layout : null;
            if (styled != null && styled.spans != null && !styled.spans.isEmpty()) {
                float suffixX = x + prefixW + placeW;
                for (LineSpan span : styled.spans) {
                    int start = clamp(span.startCol, 0, lastLine.length());
                    int end = clamp(span.endCol, 0, lastLine.length());
                    // Clip to suffix range [suffixStartCol, lastLine.length()).
                    if (end <= suffixStartCol || start >= lastLine.length()) continue;
                    int clippedStart = Math.max(start, suffixStartCol);
                    int clippedEnd = Math.min(end, lastLine.length());
                    if (clippedEnd <= clippedStart) continue;
                    String tokenText = lastLine.substring(clippedStart, clippedEnd);
                    Integer colorBg = null;
                    if (tokenText.startsWith("#") && tokenText.length() >= 4) {
                        colorBg = parseColorLiteral(tokenText);
                    }
                    if (colorBg != null) {
                        float bgX1 = suffixX + (clippedStart - suffixStartCol) * charWidth;
                        float bgX2 = suffixX + (clippedEnd - suffixStartCol) * charWidth;
                        float bgY1 = y + lineHeight * 0.1f;
                        float bgY2 = y + lineHeight * 0.9f;
                        view.selPaint.setColor(colorBg);
                        view.selPaint.setAntiAlias(false);
                        android.graphics.RectF bgRect = new android.graphics.RectF(bgX1, bgY1, bgX2, bgY2);
                        canvas.drawRoundRect(bgRect, lineHeight * 0.15f, lineHeight * 0.15f, view.selPaint);
                        view.selPaint.setAntiAlias(true);
                        paint.setColor(getContrastColor(colorBg));
                    } else {
                        paint.setColor(view.theme.colorForToken(span.type));
                    }
                    canvas.drawText(tokenText,
                        suffixX + (clippedStart - suffixStartCol) * charWidth,
                        y + lineHeight * 0.78f, paint);
                }
                suffixColored = true;
            }
        }
        if (!suffixColored && !suffix.isEmpty()) {
            paint.setColor(view.theme.textColor);
            canvas.drawText(suffix, x + prefixW + placeW, y + lineHeight * 0.78f, paint);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Fold chevrons
    // ════════════════════════════════════════════════════════════════

    /**
     * Draws fold chevrons. v3.2.0 fix (CodeAssist pattern):
     * Collapsed → always show chevron. Open → only on caret line.
     *
     * <p>v3.7.2 bugfix (Bug 7): the chevron was too big and stroked (1.4f
     * stroke width with {@code r2 = foldStripWidth * 0.25} ≈ 3.5dp+ at
     * default density). CodeAssist uses a small FILLED triangle with
     * {@code r = 3.2dp} and {@code alpha = 0.8f}. We now match that:
     * filled path (Style.FILL), {@code r = 3.2dp}, color = gutterText
     * at 0.8 alpha. Visually much softer and less obtrusive.
     */
    void drawFoldChevrons(Canvas canvas, EditorDocument doc,
                           int firstVisible, int lastVisible,
                           float lineHeight, float paddingTop) {
        if (view.session.getFoldRegions().isEmpty()) return;
        float foldStripX = view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth() * 0.5f;
        int caretLine = clamp(doc.lineForOffset(view.session.getSelection().start), 0, doc.lineCount() - 1);
        // v3.7.2: CodeAssist-style — filled triangle, r = 3.2dp, alpha 0.8.
        float density = view.getResources().getDisplayMetrics().density;
        float r = 3.2f * density; // half-extent in pixels
        view.caretPaint.setStyle(Paint.Style.FILL);
        view.caretPaint.setAntiAlias(true);
        Path p = scratchPath;
        for (DiagnosticShift.FoldRegion region : view.session.getFoldRegions()) {
            int startLine = doc.lineForOffset(region.start);
            if (startLine < firstVisible || startLine > lastVisible) continue;
            if (!region.collapsed && startLine != caretLine) continue;
            float y = view.docLineToY(startLine) - view.vOffset + lineHeight * 0.5f;
            // v3.7.2: apply 0.8 alpha to the gutterText color (CodeAssist
            // uses color.copy(alpha = 0.8f)).
            int baseColor = view.theme.gutterText;
            view.caretPaint.setColor(applyAlpha(baseColor, 0.8f));
            p.reset();
            if (region.collapsed) {
                // ▸ points right — width ~r*1.3, height ~2r (CodeAssist shape)
                p.moveTo(foldStripX - r * 0.6f, y - r);
                p.lineTo(foldStripX + r * 0.7f, y);
                p.lineTo(foldStripX - r * 0.6f, y + r);
                p.close();
            } else {
                // ▾ points down — width ~2r, height ~r*1.3
                p.moveTo(foldStripX - r, y - r * 0.6f);
                p.lineTo(foldStripX + r, y - r * 0.6f);
                p.lineTo(foldStripX, y + r * 0.7f);
                p.close();
            }
            canvas.drawPath(p, view.caretPaint);
        }
    }

    /**
     * v3.7.2: Applies an alpha multiplier to an ARGB color. Used by
     * {@link #drawFoldChevrons} to match CodeAssist's 0.8-alpha chevron.
     */
    private static int applyAlpha(int color, float alpha) {
        int a = (color >>> 24) & 0xFF;
        int newA = (int) (a * alpha);
        return (newA << 24) | (color & 0x00FFFFFF);
    }

    // ════════════════════════════════════════════════════════════════
    // Inlay hints (legacy global-iteration path — kept for parity, not called)
    // ════════════════════════════════════════════════════════════════

    /** Draws inlay hints as phantom text at their anchor columns. */
    void drawInlayHints(Canvas canvas, EditorDocument doc,
                         int firstVisible, int lastVisible,
                         float textAreaLeft, float lineHeight, float paddingTop) {
        List<DiagnosticShift.InlayHint> hints = view.session.getInlayHints();
        if (hints.isEmpty()) return;
        float charWidth = view.metrics.getCharWidth();
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        view.textPaint.setColor(view.theme.annotation);
        // Subtle background
        view.selPaint.setColor(view.theme.findMatch);
        for (DiagnosticShift.InlayHint h : hints) {
            int line = doc.lineForOffset(h.offset);
            if (line < firstVisible || line > lastVisible) continue;
            // v3.5.0: skip inlay hints on hidden (folded) lines.
            if (view.isLineFoldedCached(line)) continue;
            int col = h.offset - doc.lineStart(line);
            float x = textAreaLeft + col * charWidth - view.hOffset;
            float y = view.docLineToY(line) - view.vOffset; // v3.5.0: fold-aware Y
            float w = view.textPaint.measureText(h.text);
            // Chip background
            view.selPaint.setAlpha(120);
            canvas.drawRect(x, y + lineHeight * 0.15f, x + w + 4, y + lineHeight * 0.85f, view.selPaint);
            view.selPaint.setAlpha(255);
            canvas.drawText(h.text, x + 2, y + lineHeight * 0.7f, view.textPaint);
        }
        // Restore text paint size
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // Semantic tokens (legacy global-iteration path — kept for parity, not called)
    // ════════════════════════════════════════════════════════════════

    /**
     * Overlays semantic tokens on top of the lexical syntax-highlight spans.
     * Semantic tokens come from the language server (method, class, enum, etc.)
     * and override the lexer's best-guess color when present.
     *
     * <p>Legacy path — iterates the global token list. Kept for callers that
     * don't yet use the per-line cache (e.g. wrapped lines). The cached path
     * goes through {@link #drawCachedSemSpans}.
     */
    void drawSemanticTokens(Canvas canvas, EditorDocument doc, int lineNum,
                             String lineText, float x, float y, Paint paint) {
        List<DiagnosticShift.SemanticToken> tokens = view.session.getSemanticTokens();
        if (tokens.isEmpty()) return;
        int lineStart = doc.lineStart(lineNum);
        int lineEnd = doc.lineEnd(lineNum);
        float charWidth = view.metrics.getCharWidth();
        float lineHeight = view.metrics.getLineHeight();
        for (DiagnosticShift.SemanticToken t : tokens) {
            int tokEnd = t.start + t.length;
            if (t.start >= lineEnd || tokEnd <= lineStart) continue;
            int startCol = clamp(t.start - lineStart, 0, lineText.length());
            int endCol = clamp(tokEnd - lineStart, 0, lineText.length());
            if (startCol >= endCol) continue;
            // Map semantic type → token color (reuse lexer's palette).
            TokenType ttype = EditorView.semanticTypeToTokenType(t.type);
            if (ttype == null) continue;
            paint.setColor(view.theme.colorForToken(ttype));
            String piece = lineText.substring(startCol, endCol);
            canvas.drawText(piece, x + startCol * charWidth, y + lineHeight * 0.78f, paint);
        }
    }

    /**
     * Draws the cached per-line semantic spans (computed once on cache miss
     * by {@link EditorView#layoutForLine}). Equivalent to {@link #drawSemanticTokens}
     * but O(spans on this line) instead of O(global token count).
     *
     * <p>v2.31: inlay-aware — spans are drawn at VISUAL (woven) columns so a
     * semantically-colored range lands exactly on top of the (shifted) text.
     */
    void drawCachedSemSpans(Canvas canvas, List<LineRenderCache.SemSpan> spans,
                             String lineText, float x, float y, Paint paint,
                             List<LineRenderCache.InlayPiece> inlays, int[] rawToVisual) {
        for (LineRenderCache.SemSpan s : spans) {
            int start = clamp(s.start, 0, lineText.length());
            int end = clamp(s.end, 0, lineText.length());
            if (start >= end) continue;
            paint.setColor(s.color);
            drawRawRange(canvas, lineText, inlays, rawToVisual, start, end, x, y, paint);
        }
    }

    /**
     * Draws cached per-line inlay hints as phantom text woven INTO the line
     * (v2.31 — CodeAssist parity). The hint is drawn at its anchor's VISUAL
     * column (rawToVisual[col], i.e. just before the anchored character) and
     * occupies {@code text.length()} visual columns; the text after it was
     * already shifted right by {@link #drawRawRange}, so the hint NEVER
     * overlaps the code.
     *
     * <p>Style: CodeAssist renders inlays as dim italic text with no
     * background chip ({@code textTertiary + FontStyle.Italic}) — we match
     * with the gutter-text tone + a slight skew (fake italic) at 85% size.
     */
    void drawCachedInlays(Canvas canvas, List<LineRenderCache.InlayPiece> inlays,
                           int[] rawToVisual, float lineY, Paint paint) {
        float charWidth = view.metrics.getCharWidth();
        float lineHeight = view.metrics.getLineHeight();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        paint.setTypeface(view.metrics.getTypeface());
        paint.setTextSize(view.metrics.getTextSize() * 0.85f);
        paint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.9f));
        paint.setTextSkewX(-0.25f); // fake italic for monospace
        for (LineRenderCache.InlayPiece p : inlays) {
            int vis = (rawToVisual != null && p.col >= 0 && p.col < rawToVisual.length)
                ? rawToVisual[p.col] : p.col;
            float x = textAreaLeft + vis * charWidth - view.hOffset;
            if (x + p.text.length() * charWidth >= view.metrics.getGutterWidth()) {
                canvas.drawText(p.text, x + charWidth * 0.08f, lineY + lineHeight * 0.75f, paint);
            }
        }
        paint.setTextSkewX(0f);
        paint.setTextSize(view.metrics.getTextSize());
    }

    /**
     * v2.31: Draws the raw text range {@code [start, end)} of a line with the
     * line's inlays WOVEN IN — the canonical "phantom text" draw. Every slice
     * between two inlay anchors is drawn at its spliced (visual) column, so
     * the code AFTER a hint is shifted right by the hint's width. This is the
     * exact canvas equivalent of CodeAssist's {@code buildInlayAnnotated}:
     * the visual column of slice start = {@code rawToVisual[start]}, and each
     * inlay anchored inside the range advances the visual cursor by its text
     * length before the anchored character is drawn.
     */
    private void drawRawRange(Canvas canvas, String lineText,
                               List<LineRenderCache.InlayPiece> inlays, int[] rawToVisual,
                               int start, int end, float x, float y, Paint paint) {
        float charWidth = view.metrics.getCharWidth();
        float baseline = y + view.metrics.getLineHeight() * 0.78f;
        if (inlays == null || inlays.isEmpty()) {
            canvas.drawText(lineText, start, end, x + start * charWidth, baseline, paint);
            return;
        }
        // Visual cursor starts at rawToVisual[start] (= start + inlays strictly
        // before start) — an inlay anchored AT start advances it further.
        int vis = (rawToVisual != null && start >= 0 && start < rawToVisual.length)
            ? rawToVisual[start] : start;
        int pos = start;
        int i = 0;
        while (i < inlays.size() && inlays.get(i).col < start) i++;
        while (pos < end) {
            if (i < inlays.size() && inlays.get(i).col <= pos) {
                // Inlay anchored here: it occupies visual columns (drawn
                // separately by drawCachedInlays) — advance past its width.
                vis += inlays.get(i).text.length();
                i++;
                continue;
            }
            int next = (i < inlays.size() && inlays.get(i).col < end) ? inlays.get(i).col : end;
            canvas.drawText(lineText, pos, next, x + vis * charWidth, baseline, paint);
            vis += next - pos;
            pos = next;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Find highlights
    // ════════════════════════════════════════════════════════════════

    /**
     * Draws every find-match in the viewport as a faint band, and the current
     * match as a stronger band. Drawn BEFORE the text so syntax colors sit
     * on top.
     */
    void drawFindHighlights(Canvas canvas, EditorDocument doc,
                             float textAreaLeft, float lineHeight, float paddingTop,
                             int firstVisible, int lastVisible) {
        float charWidth = view.metrics.getCharWidth();
        for (int i = 0; i < view.findHighlights.size(); i++) {
            Match m = view.findHighlights.get(i);
            int line = doc.lineForOffset(m.start);
            if (line < firstVisible || line > lastVisible) continue;
            // v3.5.0: skip find highlights on hidden (folded) lines.
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int startCol = clamp(m.start - lineStart, 0, doc.lineEnd(line) - lineStart);
            int endCol = clamp(m.end - lineStart, 0, doc.lineEnd(line) - lineStart);
            if (endCol <= startCol) endCol = startCol + 1;
            float y = view.docLineToY(line) - view.vOffset; // v3.5.0: fold-aware Y
            float x1 = textAreaLeft + view.visualColFor(line, startCol) * charWidth - view.hOffset;
            float x2 = textAreaLeft + view.visualColFor(line, endCol) * charWidth - view.hOffset;
            view.selPaint.setColor(i == view.findCurrentIndex ? view.theme.findCurrent : view.theme.findMatch);
            canvas.drawRect(x1, y, x2, y + lineHeight, view.selPaint);
        }
    }

    /**
     * v3.33.11: Draws document-highlight ranges (LSP occurrences of the
     * symbol under the caret) as soft-tint rectangles behind the text.
     * Same style as find-highlights but using {@code theme.annotation}
     * (faded gray-green) so they're visually distinct from find matches.
     *
     * <p>Drawn AFTER find-highlights so they don't compete visually — find
     * matches (user-driven) take priority over LSP occurrences (passive).</p>
     */
    void drawDocumentHighlights(Canvas canvas, EditorDocument doc,
                                float textAreaLeft, float lineHeight, float paddingTop,
                                int firstVisible, int lastVisible) {
        float charWidth = view.metrics.getCharWidth();
        // Use the dedicated 'occurrence' theme color — distinct from
        // findMatch so the user can tell apart LSP occurrences (passive)
        // from explicit find matches (active).
        view.selPaint.setColor(view.theme.occurrence);
        view.selPaint.setAlpha(100);
        for (int[] range : view.documentHighlights) {
            if (range == null || range.length < 2) continue;
            int start = range[0];
            int end = range[1];
            if (end <= start) end = start + 1;
            int line = doc.lineForOffset(start);
            if (line < firstVisible || line > lastVisible) continue;
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int startCol = clamp(start - lineStart, 0, doc.lineEnd(line) - lineStart);
            int endCol = clamp(end - lineStart, 0, doc.lineEnd(line) - lineStart);
            if (endCol <= startCol) endCol = startCol + 1;
            float y = view.docLineToY(line) - view.vOffset;
            float x1 = textAreaLeft + view.visualColFor(line, startCol) * charWidth - view.hOffset;
            float x2 = textAreaLeft + view.visualColFor(line, endCol) * charWidth - view.hOffset;
            canvas.drawRect(x1, y, x2, y + lineHeight, view.selPaint);
        }
        view.selPaint.setAlpha(255);
    }

    /**
     * v2.32: Draws the matching-bracket boxes (CodeAssist parity — port of
     * {@code EditorRendering.kt}'s "matching-bracket boxes" block). A 1px
     * stroke rectangle outlines BOTH the open and the close bracket of the
     * pair adjacent to the caret, in the caret color at 45% alpha
     * (CodeAssist: {@code colors.caret.copy(alpha = 0.45f)}).
     *
     * <p>Positioning is inlay-aware ({@link EditorView#visualColFor} — an
     * inlay hint woven before the bracket shifts the box right, exactly
     * where the glyph actually renders) and fold-aware (a bracket inside a
     * collapsed region is skipped; the composite line shows the raw col).
     */
    void drawBracketMatchBoxes(Canvas canvas, EditorDocument doc,
                               float textAreaLeft, float lineHeight,
                               int firstVisible, int lastVisible) {
        if (view.bracketPair == null) return;
        float charWidth = view.metrics.getCharWidth();
        for (int off : view.bracketPair) {
            if (off < 0 || off >= doc.length()) continue;
            int line = doc.lineForOffset(off);
            if (line < firstVisible || line > lastVisible) continue;
            // Skip brackets hidden by a collapsed fold.
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int col = clamp(off - lineStart, 0, doc.lineEnd(line) - lineStart);
            float y = view.docLineToY(line) - view.vOffset;
            float x1 = textAreaLeft + view.visualColFor(line, col) * charWidth - view.hOffset;
            float x2 = x1 + charWidth;
            // Skip when fully scrolled out of the visible column range.
            if (x2 <= view.metrics.getGutterWidth() || x1 >= canvas.getWidth()) continue;
            view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
            view.selPaint.setStrokeWidth(1f);
            view.selPaint.setColor(view.applyAlphaToColor(view.theme.caret, 0.45f));
            canvas.drawRect(x1, y, x2, y + lineHeight, view.selPaint);
            view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
            view.selPaint.setStrokeWidth(0f);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Styled line + color helpers
    // ════════════════════════════════════════════════════════════════

    void drawStyledLine(Canvas canvas, StyledLine styled, String lineText,
                         float x, float y, Paint paint,
                         List<LineRenderCache.InlayPiece> inlays, int[] rawToVisual) {
        float lineHeight = view.metrics.getLineHeight();
        float charWidth = view.metrics.getCharWidth();

        // v3.19.1: Font ligatures mode — use StaticLayout with
        // ForegroundColorSpan so ligatures form (≠ from !=, → from ->, etc.)
        // AND syntax highlighting colors are preserved. StaticLayout processes
        // the text through the font's shaping engine which applies ligatures,
        // unlike Canvas.drawText which draws each glyph independently.
        //
        // ★ v2.55 — Rich spans : en plus des ForegroundColorSpan, on pose
        // des StyleSpan pour rendre les COMMENT en italique et les KEYWORD
        // en gras. Ces effets visuels améliorent la lisibilité (les
        // commentaires reculent, les mots-clés avancent) — c'est la
        // convention de tous les éditeurs pro (VS Code, IntelliJ, Sublime).
        //
        // ★ v3.35.0 (roadmap item 2) — le StaticLayout façonné est
        // mémoïsé CONTENTU-ADRESSÉ par EditorView.shapedLayoutFor (portage
        // du rememberTextMeasurer(cacheSize=64) de CodeAssist 3.20) :
        // ~25 % des lignes d'un fichier réel sont identiques ("}", "    }")
        // et le façonnage + le SpannableStringBuilder par frame étaient le
        // coût dominant du path ligatures. Les lignes identiques partagent
        // désormais UN seul layout.
        if (view.fontLigatures && styled != null && styled.spans != null) {
            // Use StaticLayout to draw — it processes ligatures.
            android.text.StaticLayout sl = view.shapedLayoutFor(lineText, styled, paint);
            canvas.save();
            canvas.translate(x, y + lineHeight * 0.78f - sl.getLineBaseline(0));
            sl.draw(canvas);
            canvas.restore();
            return;
        }

        // ★ v2.55 — Non-ligatures path : on garde l'ancien rendu drawText
        // mais on toggle paint.setTypeface() par span pour le rendu
        // italique/gras. Comme le path ligatures est le défaut (98% des
        // devices modernes supportent les ligatures de JetBrains Mono),
        // ce path est rarement pris — mais on garde la cohérence visuelle.
        android.graphics.Typeface savedTypeface = paint.getTypeface();
        for (LineSpan span : styled.spans) {
            int start = clamp(span.startCol, 0, lineText.length());
            int end = clamp(span.endCol, 0, lineText.length());
            if (start >= end) continue;
            String tokenText = lineText.substring(start, end);

            // v2.55 — Appliquer italique/gras pour COMMENT/KEYWORD/ANNOTATION.
            int styleFlag = 0;  // Typeface.NORMAL
            if (span.type == jo.codeeditor.highlight.TokenType.COMMENT) {
                styleFlag = android.graphics.Typeface.ITALIC;
            } else if (span.type == jo.codeeditor.highlight.TokenType.KEYWORD
                    || span.type == jo.codeeditor.highlight.TokenType.ANNOTATION) {
                styleFlag = android.graphics.Typeface.BOLD;
            }
            if (styleFlag != 0) {
                paint.setTypeface(android.graphics.Typeface.create(
                    savedTypeface, styleFlag));
            } else if (paint.getTypeface() != savedTypeface) {
                paint.setTypeface(savedTypeface);
            }

            // v3.3.1: Color preview — the code itself gets a colored background.
            // #RRGGBB, #RGB, #RRGGBBAA, #RGBA are rendered with the actual
            // color as background, and a contrasting text color on top.
            Integer colorBg = null;
            if (tokenText.startsWith("#") && tokenText.length() >= 4) {
                colorBg = parseColorLiteral(tokenText);
            }
            if (colorBg != null) {
                // Draw the colored background behind the token text.
                float bgX1 = x + start * charWidth;
                float bgX2 = x + end * charWidth;
                float bgY1 = y + lineHeight * 0.1f;
                float bgY2 = y + lineHeight * 0.9f;
                view.selPaint.setColor(colorBg);
                view.selPaint.setAntiAlias(false);
                // Rounded rect for a nicer look.
                android.graphics.RectF bgRect = new android.graphics.RectF(bgX1, bgY1, bgX2, bgY2);
                canvas.drawRoundRect(bgRect, lineHeight * 0.15f, lineHeight * 0.15f, view.selPaint);
                view.selPaint.setAntiAlias(true);
                // Pick contrasting text color (black or white based on luminance).
                int contrastColor = getContrastColor(colorBg);
                paint.setColor(contrastColor);
            } else {
                paint.setColor(view.theme.colorForToken(span.type));
            }
            // v2.31: inlay-aware draw — slices at inlay anchors are drawn at
            // their spliced (visual) columns so the code after a hint shifts
            // right instead of being overlapped by it.
            drawRawRange(canvas, lineText, inlays, rawToVisual, start, end, x, y, paint);
        }
    }

    /**
     * v3.3.1: Returns black or white depending on which has better contrast
     * with the given background color.
     */
    private static int getContrastColor(int bgColor) {
        int r = (bgColor >> 16) & 0xFF;
        int g = (bgColor >> 8) & 0xFF;
        int b = bgColor & 0xFF;
        // Relative luminance (perceptual brightness).
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.5 ? 0xFF000000 : 0xFFFFFFFF;
    }

    /**
     * v3.3.0: Parses a color literal (#RGB, #RRGGBB, #RRGGBBAA, #AARRGGBB)
     * into an Android color int. Returns null if not a valid color.
     */
    private static Integer parseColorLiteral(String text) {
        if (text == null || !text.startsWith("#")) return null;
        try {
            int len = text.length() - 1; // without #
            if (len == 3) {
                // #RGB → #RRGGBB
                String r = String.valueOf(text.charAt(1));
                String g = String.valueOf(text.charAt(2));
                String b = String.valueOf(text.charAt(3));
                return (0xFF000000 | Integer.parseInt(r + r + g + g + b + b, 16));
            } else if (len == 4) {
                // #RGBA → #RRGGBBAA
                String r = String.valueOf(text.charAt(1));
                String g = String.valueOf(text.charAt(2));
                String b = String.valueOf(text.charAt(3));
                String a = String.valueOf(text.charAt(4));
                int rgb = Integer.parseInt(r + r + g + g + b + b, 16);
                int alpha = Integer.parseInt(a + a, 16);
                return (alpha << 24) | rgb;
            } else if (len == 6 || len == 8) {
                return (int) android.graphics.Color.parseColor(text);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // Wrapped line
    // ════════════════════════════════════════════════════════════════

    /**
     * Draws a wrapped line as N visual rows. Each row shows the slice of the
     * line corresponding to its column range, using the lexer's span colors.
     *
     * <p>v3.31.1: continuation rows are now indented by the line's leading-
     * whitespace column (VS Code / IntelliJ style). The first row is drawn
     * at {@code textAreaLeft}; subsequent rows are drawn at
     * {@code textAreaLeft + leadingWhitespaceCols * charWidth} so a wrapped
     * Java method body lines up under the method name, not under column 0.</p>
     */
    void drawWrappedLine(Canvas canvas, EditorDocument doc,
                          List<StyledLine> styledLines, int lineNum,
                          String lineText, float textAreaLeft,
                          float lineTopY, float lineHeight) {
        float charWidth = view.metrics.getCharWidth();
        int lineLen = lineText.length();
        // ★ v2.33 : géométrie UNIQUE (EditorView.wrapRowsFor) — comptage et
        // découpage ne peuvent plus diverger. AVANT : rows était
        // ceil(len/maxCols) mais le découpage des rangées de continuation
        // utilisait colsPerContinuationRow ÉTROIT → la queue de la ligne
        // (jusqu'à wrapIndentCols caractères) n'était jamais dessinée.
        EditorView.WrapRows wr = view.wrapRowsFor(lineNum, lineLen);
        int rows = wr.rows;
        StyledLine styled = lineNum < styledLines.size() ? styledLines.get(lineNum) : null;

        for (int r = 0; r < rows; r++) {
            int rowStartCol = wr.rowStartCol(r);
            int rowEndCol = wr.rowEndCol(r, lineLen);
            float rowX = r == 0 ? textAreaLeft
                    : textAreaLeft + wr.wrapIndentCols * charWidth;
            float rowY = lineTopY + r * lineHeight;
            if (rowEndCol <= rowStartCol) continue;
            if (styled != null) {
                // Draw each span clipped to this row's column range.
                for (LineSpan span : styled.spans) {
                    int start = clamp(span.startCol, 0, lineLen);
                    int end = clamp(span.endCol, 0, lineLen);
                    if (end <= rowStartCol || start >= rowEndCol) continue;
                    int drawStart = Math.max(start, rowStartCol);
                    int drawEnd = Math.min(end, rowEndCol);
                    if (drawStart >= drawEnd) continue;
                    view.textPaint.setColor(view.theme.colorForToken(span.type));
                    String tokenText = lineText.substring(drawStart, drawEnd);
                    canvas.drawText(tokenText,
                        rowX + (drawStart - rowStartCol) * charWidth,
                        rowY + lineHeight * 0.78f, view.textPaint);
                }
            } else {
                view.textPaint.setColor(view.theme.textColor);
                String rowText = lineText.substring(rowStartCol, rowEndCol);
                canvas.drawText(rowText, rowX, rowY + lineHeight * 0.78f, view.textPaint);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Selection highlight
    // ════════════════════════════════════════════════════════════════

    void drawSelection(Canvas canvas, EditorDocument doc, Selection sel,
                        float textAreaLeft, float lineHeight, float paddingTop,
                        int firstVisible, int lastVisible) {
        view.selPaint.setColor(view.theme.selection);
        int selStartLine = clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        int selEndLine = clamp(doc.lineForOffset(Math.max(0, sel.end - 1)), 0, doc.lineCount() - 1);
        float charWidth = view.metrics.getCharWidth();
        for (int i = Math.max(selStartLine, firstVisible); i <= Math.min(selEndLine, lastVisible); i++) {
            // v3.5.0: skip hidden (folded) lines — no selection band in the gap.
            if (view.isLineFoldedCached(i)) continue;
            float y = view.docLineToY(i) - view.vOffset; // v3.5.0: fold-aware Y
            int lineStart = doc.lineStart(i);
            int lineEnd = doc.lineEnd(i);
            int colStart = (i == selStartLine) ? sel.start - lineStart : 0;
            int colEnd = (i == selEndLine) ? sel.end - lineStart : lineEnd - lineStart;
            colStart = clamp(colStart, 0, lineEnd - lineStart);
            colEnd = clamp(colEnd, 0, lineEnd - lineStart);
            // v2.31: inlay-aware — map raw columns to woven visual columns so
            // the selection band tracks the shifted text.
            float x1 = textAreaLeft + view.visualColFor(i, colStart) * charWidth - view.hOffset;
            float x2 = textAreaLeft + view.visualColFor(i, colEnd) * charWidth - view.hOffset;
            // Trailing marker past EOL so an empty selected line still shows a band.
            float trailing = (colEnd == colStart) ? charWidth * 0.6f : 0;
            canvas.drawRect(x1, y, Math.max(x1 + 1, x2 + trailing), y + lineHeight, view.selPaint);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Caret
    // ════════════════════════════════════════════════════════════════

    void drawCaret(Canvas canvas, EditorDocument doc, Selection sel,
                    float textAreaLeft, float lineHeight, float paddingTop) {
        if (!sel.isCursor()) return;
        // ★ v2.59 — Caret visibility is now driven by EditorView.caretVisible,
        // NOT by EditorSession.readOnly. Avant, ce check était
        // `if (view.session != null && view.session.isReadOnly()) return;`
        // ce qui empêchait ConsoleLogView d'utiliser setReadOnly(true) PUIS
        // de muter le document programmatiquement (appendLine était no-op
        // car EditorSession.replaceRangeWithCaret bloque sous readOnly=true).
        // ConsoleLogView appelle maintenant setFocusable(false) (bloque IME)
        // + setCaretVisible(false) (cache le caret) — la session reste
        // mutable, le caret reste masqué. Comportement identique côté UI,
        // mais la console affiche ENFIN son contenu (fix bug v2.58).
        if (!view.caretVisible) return;

        // ── v3.33.10: CaretAnimator owns ALL blink + glide state ──────
        // The renderer delegates blink to caretAnim.updateBlink() and
        // glide to caretAnim.snapTo()/glideTo(). It reads caretAnim.animX/Y
        // for the final draw position. There are NO duplicate alias fields
        // on EditorView — this was the source of the regression where the
        // snap branch in drawCaret overwrote the just-updated alias with
        // the animator's stale animX/animY (left over from a cancelled
        // glide), causing a one-frame visual lag on every keystroke.
        CaretAnimator ca = view.caretAnim;

        // Blink logic — solid for SOLID_AFTER_EDIT_MS after every edit / caret move;
        // then blink on/off every BLINK_MS. Caret is solid while typing — no "ghost caret".
        boolean drawVisible = ca.updateBlink();
        // Schedule the next redraw so the blink actually toggles.
        long now = System.currentTimeMillis();
        long sinceEdit = now - view.lastEditTime;
        if (sinceEdit < CARET_SOLID_AFTER_EDIT_MS) {
            // Solid phase — schedule a redraw right after the solid period ends.
            long delay = CARET_SOLID_AFTER_EDIT_MS - sinceEdit + 1;
            view.postInvalidateDelayed(delay);
        } else {
            long elapsed = now - ca.lastToggle;
            long delay = CARET_BLINK_MS - elapsed + 1;
            if (delay <= 0) delay = CARET_BLINK_MS;
            view.postInvalidateDelayed(delay);
        }
        if (!drawVisible) return;

        int line = clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        int col = sel.start - doc.lineStart(line);
        float[] screenPos = view.caretScreenPos(sel.start);
        float targetX = screenPos[0];
        float targetY = screenPos[1];
        // ★ v2.33 : le décalage de rangée wrap est DÉJÀ calculé par
        // caretScreenPos (source unique) — l'ancien bloc le RAJOUTAIT une
        // seconde fois : en mode wrap le caret était dessiné rowInLine
        // rangées TROP BAS.
        // Update the glide target. Snap if: first placement, OR the text
        // was edited (rev != current doc revision), OR the jump is bigger
        // than one viewport, OR word wrap is active.
        int docRev = doc.getRevision();
        boolean snap = !ca.ready
            || ca.rev != docRev
            || view.wordWrap
            || Math.abs(targetY - ca.animY) > view.getHeight()
            || Math.abs(targetX - ca.animX) > view.getWidth();
        if (snap) {
            // Snap directly to target — single source of truth.
            ca.snapTo(targetX, targetY, docRev);
        } else if (targetX != ca.targetX || targetY != ca.targetY) {
            // New in-viewport move — start a glide from current to target.
            ca.glideTo(targetX, targetY, docRev);
        }
        // Don't draw if the caret is outside the viewport.
        if (ca.animY + lineHeight < 0 || ca.animY > view.getHeight()) return;

        view.caretPaint.setColor(view.theme.caret);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(2f);
        canvas.drawLine(ca.animX, ca.animY, ca.animX, ca.animY + lineHeight, view.caretPaint);
    }

    // ════════════════════════════════════════════════════════════════
    // Diagnostic squiggles
    // ════════════════════════════════════════════════════════════════

    void drawSquiggles(Canvas canvas, EditorDocument doc,
                        int firstVisible, int lastVisible,
                        float textAreaLeft, float lineHeight, float paddingTop) {
        List<DiagnosticShift.Diagnostic> diags = view.session.getDiagnostics();
        if (diags.isEmpty()) return;
        float charWidth = view.metrics.getCharWidth();
        for (DiagnosticShift.Diagnostic d : diags) {
            int line = doc.lineForOffset(d.start);
            if (line < firstVisible || line > lastVisible) continue;
            // v3.5.0: skip squiggles on hidden (folded) lines.
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int lineEnd = doc.lineEnd(line);
            int startCol = clamp(d.start - lineStart, 0, lineEnd - lineStart);
            int endCol = clamp(d.end - lineStart, 0, lineEnd - lineStart);
            if (endCol <= startCol) endCol = startCol + 1;
            float y = view.docLineToY(line) - view.vOffset; // v3.5.0: fold-aware Y
            float baseY = y + lineHeight - 2;
            view.squigglePaint.setColor(getSquiggleColor(d.severity));
            view.squigglePaint.setStrokeWidth(1.4f);
            view.squigglePaint.setStyle(Paint.Style.STROKE);
            view.squigglePaint.setAntiAlias(true);
            // v2.31: inlay-aware visual columns — the squiggle spans the same
            // screen range as the (shifted) text it underlines.
            float x1 = textAreaLeft + view.visualColFor(line, startCol) * charWidth - view.hOffset;
            float x2 = textAreaLeft + view.visualColFor(line, endCol) * charWidth - view.hOffset;
            Path path = scratchPath;
            path.reset();
            path.moveTo(x1, baseY);
            float step = SQUIGGLE_PERIOD / 2;
            float x = x1;
            boolean up = true;
            while (x < x2) {
                float nx = Math.min(x + step, x2);
                path.lineTo(nx, up ? baseY - SQUIGGLE_AMPLITUDE : baseY + SQUIGGLE_AMPLITUDE);
                x = nx;
                up = !up;
            }
            canvas.drawPath(path, view.squigglePaint);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Indent guides
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.31 — Modernized indent guides (CodeAssist "bracket lines" parity):
     * <ul>
     *   <li><b>Bridging</b> — a BLANK line inherits the shallower indent of
     *       its nearest non-blank neighbours, so a guide spans a block's empty
     *       rows as ONE continuous vertical line instead of a dashed one.</li>
     *   <li><b>Strict levels</b> — a guide is drawn at each 4-column level
     *       STRICTLY below the line's own indent (never under the first
     *       character), matching CodeAssist's {@code level < cols}.</li>
     *   <li><b>Rounded caps</b> — softer, modern line endings.</li>
     * </ul>
     */
    void drawIndentGuides(Canvas canvas, EditorDocument doc,
                           int firstVisible, int lastVisible,
                           float textAreaLeft, float lineHeight, float paddingTop) {
        view.guidePaint.setColor(view.theme.indentGuide);
        view.guidePaint.setStrokeWidth(1f);
        view.guidePaint.setStrokeCap(Paint.Cap.ROUND);
        float charWidth = view.metrics.getCharWidth();
        for (int i = firstVisible; i <= lastVisible; i++) {
            // v3.5.0: skip indent guides on hidden (folded) lines.
            if (view.isLineFoldedCached(i)) continue;
            String lineText = doc.lineText(i);
            int cols = leadingIndentOrBlank(lineText);
            if (cols < 0) {
                // Blank line — bridge with the shallower of the nearest
                // non-blank neighbours (scan bounded to 64 rows each way).
                int up = i - 1, upCapped = 64;
                while (up >= 0 && upCapped-- > 0 && leadingIndentOrBlank(doc.lineText(up)) < 0) up--;
                int dn = i + 1, dnCapped = 64;
                int lineCount = doc.lineCount();
                while (dn < lineCount && dnCapped-- > 0 && leadingIndentOrBlank(doc.lineText(dn)) < 0) dn++;
                int a = (up >= 0) ? leadingIndentOrBlank(doc.lineText(up)) : 0;
                int b = (dn < lineCount) ? leadingIndentOrBlank(doc.lineText(dn)) : 0;
                cols = Math.min(a, b);
            }
            if (cols < 0) cols = 0;
            if (cols < INDENT_UNIT_COLS) continue;
            float y = view.docLineToY(i) - view.vOffset; // v3.5.0: fold-aware Y
            int level = INDENT_UNIT_COLS;
            while (level < cols) {
                float x = textAreaLeft + level * charWidth - view.hOffset;
                if (x >= view.metrics.getGutterWidth()) {
                    canvas.drawLine(x, y, x, y + lineHeight, view.guidePaint);
                }
                level += INDENT_UNIT_COLS;
            }
        }
    }

    /**
     * v2.31: Leading-whitespace width in columns, or -1 for a BLANK line
     * (only whitespace — no visible char). Mirrors CodeAssist's
     * {@code leadingIndentCols} sentinel so the guide layer can bridge.
     */
    static int leadingIndentOrBlank(String s) {
        if (s == null) return 0;
        int i = 0;
        int cols = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ') cols++;
            else if (c == '\t') cols += 4; // flat advance, matching the guide grid
            else return cols;
            i++;
        }
        return -1;
    }

    private int getSquiggleColor(int severity) {
        switch (severity) {
            case 3: return view.theme.error;
            case 2: return view.theme.warning;
            default: return view.theme.info;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Selection handles (mobile)
    // ════════════════════════════════════════════════════════════════

    /** Draws the selection handles as filled circles below the anchor lines. */
    void drawSelectionHandles(Canvas canvas) {
        if (!view.handlesVisible) return;
        Selection sel = view.session.getSelection();
        float density = view.getResources().getDisplayMetrics().density;
        float r = view.HANDLE_RADIUS_DP * density;
        view.selPaint.setColor(view.theme.caret);
        view.selPaint.setAntiAlias(true);
        if (sel.isCursor()) {
            float[] pos = view.caretScreenPos(sel.start);
            canvas.drawCircle(pos[0], pos[1] + view.metrics.getLineHeight() + r * 0.6f, r, view.selPaint);
        } else {
            float[] posA = view.caretScreenPos(sel.start);
            canvas.drawCircle(posA[0], posA[1] + view.metrics.getLineHeight() + r * 0.6f, r, view.selPaint);
            float[] posB = view.caretScreenPos(sel.end);
            canvas.drawCircle(posB[0], posB[1] + view.metrics.getLineHeight() + r * 0.6f, r, view.selPaint);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Quick doc popup
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.38 — Une ligne rendue du quick doc, avec son style.
     * SIG = signature (fence) sur fond teinté ; SECTION = titre @param/… ;
     * ITEM = élément de section indenté ; TEXT = corps de description.
     */
    static final class DocRow {
        static final int SIG = 0;
        static final int TEXT = 1;
        static final int SECTION = 2;
        static final int ITEM = 3;
        final int type;
        final String text;
        DocRow(int type, String text) { this.type = type; this.text = text; }
    }

    /**
     * v2.38 — Construit les lignes du quick doc : signature (fences)
     * en tête, puis description wrappée, puis sections. Le wrap est fait
     * sur le texte BRUT (les backticks de l'inline code ne changent pas la
     * largeur — seule la couleur change au rendu).
     */
    private List<DocRow> buildQuickDocRows(float textWidth, Paint paint) {
        List<DocRow> rows = new ArrayList<>();
        jo.codeeditor.doc.QuickDoc.QuickDocContent c = view.quickDocContent;
        if (c == null) return rows;
        if (c.signature != null && !c.signature.isEmpty()) {
            for (String line : c.signature.split("\n", -1)) {
                for (String w : wrapText(line, textWidth - 16, paint)) {
                    rows.add(new DocRow(DocRow.SIG, w));
                }
            }
        }
        if (c.description != null && !c.description.isEmpty()) {
            for (String w : wrapText(c.description, textWidth, paint)) {
                rows.add(new DocRow(DocRow.TEXT, w));
            }
        }
        for (jo.codeeditor.doc.QuickDoc.DocSection s : c.sections) {
            rows.add(new DocRow(DocRow.SECTION, s.title));
            for (String item : s.items) {
                for (String w : wrapText(item, textWidth - 12, paint)) {
                    rows.add(new DocRow(DocRow.ITEM, "  " + w));
                }
            }
        }
        return rows;
    }

    /**
     * v2.38 — Géométrie du quick doc : source unique pour le rendu ET le
     * hit-test. {@code out} reçoit {anchorX, anchorY, popupW, popupH,
     * contentH, rowH} en px écran, ou la méthode rend null (popup absent ou
     * ligne d'ancre hors viewport → le popup suit le texte au scroll,
     * parité Sora HoverWindow).
     */
    float[] quickDocMetrics() {
        if (!view.quickDocVisible || view.quickDocContent == null
                || view.session == null) {
            return null;
        }
        float density = view.getResources().getDisplayMetrics().density;
        // Largeur : 80 % de l'éditeur (Sora), cap 320dp.
        float maxW = Math.min(QUICK_DOC_MAX_WIDTH_DP * density,
                view.getWidth() * 0.8f);
        float maxH = QUICK_DOC_MAX_HEIGHT_DP * density;
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        float padX = 10 * density;
        float padY = 8 * density;
        float rowH = view.metrics.getTextSize() * 1.25f;
        List<DocRow> rows = buildQuickDocRows(maxW - 2 * padX, view.textPaint);
        if (rows.isEmpty()) return null;
        // Largeur réelle = plus longue ligne, capée.
        float textW = 0;
        for (DocRow r : rows) {
            float w = view.textPaint.measureText(r.text);
            if (w > textW) textW = w;
        }
        float popupW = Math.min(maxW, textW + 2 * padX);
        float contentH = rows.size() * rowH + 2 * padY;
        float popupH = Math.min(maxH, contentH);
        // ── Ancrage Sora HoverWindow.updateWindowPosition ──
        EditorDocument doc = view.session.getDocument();
        int safeOffset = Math.min(view.quickDocAnchorOffset, doc.length());
        int line = EditorView.clamp(doc.lineForOffset(safeOffset), 0, doc.lineCount() - 1);
        int col = safeOffset - doc.lineStart(line);
        float charX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
                + (view.visualColFor(line, col) + 0.5f) * view.metrics.getCharWidth()
                - view.hOffset;
        float lineTopY = view.docLineToY(line) - view.vOffset;
        float lineBottomY = lineTopY + view.rowsForDocLine(line)
                * view.metrics.getLineHeight();
        // L'ancre sort du viewport → le popup ne se dessine pas (il
        // reviendra si la ligne revient — il suit le texte).
        if (lineBottomY < 0 || lineTopY > view.getHeight()) return null;
        // Centré sur le caractère (Sora : charX - width/2), clampé.
        float anchorX = charX - popupW * 0.5f;
        if (anchorX + popupW > view.getWidth()) {
            anchorX = view.getWidth() - popupW - 4 * density;
        }
        if (anchorX < view.metrics.getGutterWidth()) {
            anchorX = view.metrics.getGutterWidth();
        }
        // AU-DESSUS de la ligne par défaut (Sora), en dessous sinon.
        float gap = 10 * density;
        float anchorY = lineTopY - gap - popupH;
        if (anchorY < 0) anchorY = lineBottomY + gap;
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, view.getHeight() - popupH - 4 * density);
        }
        // Compat : les anciens champs de coordonnées restent nourris.
        view.quickDocX = charX;
        view.quickDocY = lineTopY;
        return new float[]{anchorX, anchorY, popupW, popupH, contentH, rowH};
    }

    /**
     * Draws the quick-doc popup — v2.38 : ancrage Sora (centré sur le
     * caractère, au-dessus de la ligne par défaut), le popup SUIT le texte
     * au scroll, signature (fence) en tête sur fond teinté, inline code
     * coloré, corps scrollable au drag (plus de clip muet).
     */
    void drawQuickDocPopup(Canvas canvas) {
        if (!view.quickDocVisible || view.quickDocContent == null) return;
        float[] m = quickDocMetrics();
        if (m == null) return;
        float density = view.getResources().getDisplayMetrics().density;
        float radius = QUICK_DOC_RADIUS_DP * density;
        float anchorX = m[0], anchorY = m[1];
        float popupW = m[2], popupH = m[3], contentH = m[4], rowH = m[5];
        float padX = 10 * density;
        float padY = 8 * density;

        // Clamp du scroll du corps (le drag met à jour quickDocScrollY).
        float maxScroll = Math.max(0, contentH - popupH);
        if (view.quickDocScrollY < 0) view.quickDocScrollY = 0;
        if (view.quickDocScrollY > maxScroll) view.quickDocScrollY = maxScroll;
        float scrollY = view.quickDocScrollY;

        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        List<DocRow> rows = buildQuickDocRows(popupW - 2 * padX, view.textPaint);

        // Background + border
        RectF rect = new RectF(anchorX, anchorY, anchorX + popupW, anchorY + popupH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);

        // Corps scrollable, clippé au rect.
        canvas.save();
        canvas.clipRect(rect);
        float sigBgBottom = -1f;
        for (int i = 0; i < rows.size(); i++) {
            DocRow r = rows.get(i);
            float rowTop = anchorY + padY + i * rowH - scrollY;
            float rowBottom = rowTop + rowH;
            if (rowBottom < anchorY || rowTop > anchorY + popupH) continue;
            float baseline = rowTop + rowH * 0.78f;
            if (r.type == DocRow.SIG) {
                // Bande teinte pleine largeur pour la signature.
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX, rowTop - 1, anchorX + popupW,
                        rowBottom, view.selPaint);
                sigBgBottom = rowBottom;
                drawInlineCodeLine(canvas, r.text, anchorX + padX, baseline,
                        view.theme.textColor, view.theme.func);
            } else if (r.type == DocRow.SECTION) {
                view.textPaint.setColor(view.theme.keyword);
                view.textPaint.setFakeBoldText(true);
                canvas.drawText(r.text, anchorX + padX, baseline, view.textPaint);
                view.textPaint.setFakeBoldText(false);
            } else {
                drawInlineCodeLine(canvas, r.text, anchorX + padX, baseline,
                        view.theme.textColor, view.theme.func);
            }
        }
        // Divider sous la bande signature (motif QuickDocPopup CodeAssist).
        if (sigBgBottom >= 0) {
            view.caretPaint.setStyle(Paint.Style.STROKE);
            view.caretPaint.setStrokeWidth(1f);
            view.caretPaint.setColor(view.theme.glassBorder);
            canvas.drawLine(anchorX, sigBgBottom, anchorX + popupW, sigBgBottom,
                    view.caretPaint);
        }
        // Scrollbar si le corps déborde.
        if (contentH > popupH) {
            float sbX = anchorX + popupW - 3 * density;
            float sbH = popupH * popupH / contentH;
            float sbY = anchorY + (popupH - sbH) * (scrollY / maxScroll);
            view.selPaint.setColor(view.theme.gutterBorder);
            canvas.drawRect(sbX, sbY, sbX + 2 * density, sbY + sbH, view.selPaint);
        }
        canvas.restore();
        // Reset paint
        view.textPaint.setTextSize(view.metrics.getTextSize());
        view.textPaint.setTypeface(view.metrics.getTypeface());
    }

    /**
     * v2.38 — Dessine une ligne en alternant segments normaux et segments
     * {@code `inline code`} (backticks conservés au rendu, code en couleur
     * accent — la police est déjà monospace dans l'éditeur).
     */
    private void drawInlineCodeLine(Canvas canvas, String line, float x,
                                    float baseline, int normalColor, int codeColor) {
        if (line == null || line.isEmpty()) return;
        int start = 0;
        boolean inCode = false;
        float cx = x;
        while (start < line.length()) {
            int tick = line.indexOf('`', start);
            String seg = tick >= 0 ? line.substring(start, tick)
                    : line.substring(start);
            if (!seg.isEmpty()) {
                view.textPaint.setColor(inCode ? codeColor : normalColor);
                canvas.drawText(seg, cx, baseline, view.textPaint);
                cx += view.textPaint.measureText(seg);
            }
            if (tick < 0) break;
            // Rend le backtick en couleur code (il reste visible, délimiteur
            // de code comme dans le rendu CodeAssist).
            view.textPaint.setColor(codeColor);
            canvas.drawText("`", cx, baseline, view.textPaint);
            cx += view.textPaint.measureText("`");
            start = tick + 1;
            inCode = !inCode;
        }
    }

    /** Word-wraps {@code text} to fit {@code maxWidth} (px). */
    private static List<String> wrapText(String text, float maxWidth, Paint paint) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] parts = text.split("\n");
        for (String part : parts) {
            if (part.isEmpty()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                line.append(part.charAt(i));
                if (paint.measureText(line.toString()) > maxWidth) {
                    // Step back to the last space.
                    int lastSpace = line.lastIndexOf(" ");
                    if (lastSpace > 0) {
                        out.add(line.substring(0, lastSpace));
                        line = new StringBuilder(line.substring(lastSpace + 1));
                    } else {
                        out.add(line.toString());
                        line = new StringBuilder();
                    }
                }
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    // Code actions lightbulb + popup
    // ════════════════════════════════════════════════════════════════

    /**
     * Draws a lightbulb 💡 in the fold strip for every visible line that has
     * code actions. Tapping the bulb opens the actions popup.
     *
     * <p>v1.0.9 redesign: the bulb is now drawn as a proper lightbulb glyph
     * (a filled circle + a small base rectangle, in amber) centered in the
     * fold strip column — no more "B" letter. It no longer overlaps line
     * numbers because the fold strip is a dedicated column (v1.0.8 fix).
     *
     * <p>v1.0.9 bugfix (Bug B): the bulb is only drawn on lines that have a
     * diagnostic (Error/Warning) — matching CodeAssist's behavior where the
     * lightbulb is tied to quick-fixes on errors, not shown on every line.
     */
    void drawCodeActionsBulbs(Canvas canvas, int firstVisible, int lastVisible,
                               float lineHeight, float paddingTop) {
        if (view.codeActionsByLine.isEmpty()) return;
        float density = view.getResources().getDisplayMetrics().density;
        float bulbR = LIGHTBULB_RADIUS_DP * density;
        // Center of the fold strip column.
        float bulbX = view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth() * 0.5f;
        view.selPaint.setAntiAlias(true);
        // Build the set of lines that have diagnostics (Error/Warning only).
        java.util.Set<Integer> diagLines = new java.util.HashSet<>();
        for (DiagnosticShift.Diagnostic d : view.session.getDiagnostics()) {
            if (d.severity == 3 || d.severity == 2) { // error or warning
                int ln = view.session.getDocument().lineForOffset(d.start);
                diagLines.add(ln);
            }
        }
        for (java.util.Map.Entry<Integer, List<EditorView.CodeAction>> e : view.codeActionsByLine.entrySet()) {
            int line = e.getKey();
            if (line < firstVisible || line > lastVisible) continue;
            // v1.0.9 bugfix (Bug B): only show the bulb on lines that have
            // a diagnostic. Without this gate, a resolver that returns
            // actions for every line (like the demo resolver) would spam
            // bulbs on every line, drowning the fold chevrons.
            if (!diagLines.contains(line)) continue;
            // v3.5.0: skip bulb on hidden (folded) lines + use fold-aware Y.
            if (view.isLineFoldedCached(line)) continue;
            float cy = view.docLineToY(line) - view.vOffset + lineHeight * 0.5f; // v3.5.0: fold-aware
            // Draw the bulb glyph: a filled circle (the bulb) + a small
            // base rectangle (the socket) in amber.
            view.selPaint.setColor(android.graphics.Color.rgb(255, 193, 7)); // amber 500
            canvas.drawCircle(bulbX, cy - bulbR * 0.2f, bulbR, view.selPaint);
            // Socket: a small rounded rect below the bulb.
            float socketW = bulbR * 0.8f;
            float socketH = bulbR * 0.5f;
            android.graphics.RectF socket = new android.graphics.RectF(
                bulbX - socketW * 0.5f, cy + bulbR * 0.6f,
                bulbX + socketW * 0.5f, cy + bulbR * 0.6f + socketH);
            view.selPaint.setColor(android.graphics.Color.rgb(120, 90, 0));
            canvas.drawRoundRect(socket, bulbR * 0.15f, bulbR * 0.15f, view.selPaint);
            // Subtle glow ring around the bulb to make it pop on dark themes.
            view.selPaint.setStyle(Paint.Style.STROKE);
            view.selPaint.setStrokeWidth(1f);
            view.selPaint.setColor(android.graphics.Color.argb(60, 255, 193, 7));
            canvas.drawCircle(bulbX, cy - bulbR * 0.2f, bulbR + 1.5f * density, view.selPaint);
            view.selPaint.setStyle(Paint.Style.FILL);
        }
    }

    /**
     * Draws the code-actions popup anchored to the lightbulb of the
     * active line. Each row is one action; tap to apply.
     */
    void drawCodeActionsPopup(Canvas canvas) {
        if (!view.codeActionsPopupVisible || view.codeActionsPopupLine < 0) return;
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(view.codeActionsPopupLine);
        if (actions == null || actions.isEmpty()) {
            view.codeActionsPopupVisible = false;
            return;
        }
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.CODE_ACTIONS_ROW_HEIGHT_DP * density;
        float width = view.CODE_ACTIONS_POPUP_WIDTH_DP * density;
        float radius = QUICK_DOC_RADIUS_DP * density;
        int rowsToShow = Math.min(view.CODE_ACTIONS_MAX_ROWS, actions.size());
        float popupH = rowH * rowsToShow;
        float lineHeight = view.metrics.getLineHeight();
        float paddingTop = view.metrics.getPadTop();
        float anchorX = view.metrics.getGutterWidth() + 4 * density;
        float anchorY = view.docLineToY(view.codeActionsPopupLine) - view.vOffset; // v3.5.0: fold-aware
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, anchorY - popupH + lineHeight);
        }
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + popupH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
        float padX = 8 * density;
        for (int i = 0; i < rowsToShow; i++) {
            EditorView.CodeAction a = actions.get(i);
            float y = anchorY + i * rowH;
            if (i == view.codeActionsSelected) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            view.textPaint.setColor(view.theme.textColor);
            canvas.drawText(a.title, anchorX + padX, y + rowH * 0.7f, view.textPaint);
            // Kind badge on the right
            if (!a.kind.isEmpty()) {
                float titleW = view.textPaint.measureText(a.title);
                view.textPaint.setColor(view.theme.gutterText);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
                canvas.drawText(a.kind, anchorX + padX + titleW + 8 * density,
                    y + rowH * 0.7f, view.textPaint);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            }
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // v2.36 : menu contextuel unifié (portage NavMenu de CodeAssist)
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.36 — dessine le menu contextuel unifié : carte glass arrondie,
     * sections en majuscules (GO TO / QUICK FIXES / INTENTIONS, affichées
     * seulement si non-vides), rangées icône + label, « Nothing found in
     * source. » quand tout est vide. Parité NavigationMenu.kt : rangées 40dp,
     * icône 16dp + gap 10dp, header labelSmall semibold, press → fond teinté
     * accent + icône accent. Contenu scrollable (navMenuScrollY) borné à
     * 360dp.
     */
    void drawNavMenu(Canvas canvas) {
        if (!view.navMenuVisible) return;
        float[] m = view.navMenuMetrics();
        if (m == null) return;
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        if (rows.isEmpty()) return;
        float density = view.getResources().getDisplayMetrics().density;
        float x = m[0], y = m[1], w = m[2], h = m[3];
        float rowH = view.NAV_MENU_ROW_HEIGHT_DP * density;
        float headerH = view.NAV_MENU_HEADER_HEIGHT_DP * density;
        float radius = 8 * density;

        // ── Carte glass (pattern drawQuickDocPopup) ─────────────────
        RectF rect = new RectF(x, y, x + w, y + h);
        view.bgPaint.setStyle(Paint.Style.FILL);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);
        view.caretPaint.setStyle(Paint.Style.FILL);

        // ── Contenu, clippé + décalé du scroll ──────────────────────
        int save = canvas.save();
        canvas.clipRect(rect);
        float contentY = y - view.navMenuScrollY;
        for (int i = 0; i < rows.size(); i++) {
            EditorView.NavMenuRow row = rows.get(i);
            float rh = row.type == EditorView.NavMenuRow.TYPE_HEADER
                    ? headerH : rowH;
            float rowTop = contentY;
            float rowBottom = rowTop + rh;
            contentY = rowBottom;
            if (rowBottom < y || rowTop > y + h) continue; // hors fenêtre

            if (row.type == EditorView.NavMenuRow.TYPE_HEADER) {
                // SectionHeader : UPPERCASE, labelSmall semibold, outline.
                view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                view.textPaint.setTextSize(11 * density);
                view.textPaint.setColor(view.theme.gutterText);
                String header = String.valueOf(row.ref);
                canvas.drawText(header, x + 12 * density,
                        rowTop + headerH * 0.78f, view.textPaint);
                view.textPaint.setTypeface(view.metrics.getTypeface());
                view.textPaint.setTextSize(view.metrics.getTextSize());
                continue;
            }

            if (row.type == EditorView.NavMenuRow.TYPE_NOTHING) {
                // NothingFound : bodyMedium, outline, padding 14×12dp.
                view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
                view.textPaint.setTextSize(13 * density);
                view.textPaint.setColor(view.theme.gutterText);
                canvas.drawText("Nothing found in source.",
                        x + 14 * density, rowTop + rowH * 0.68f, view.textPaint);
                view.textPaint.setTypeface(view.metrics.getTypeface());
                view.textPaint.setTextSize(view.metrics.getTextSize());
                continue;
            }

            // ── Rangée actionnable : press feedback + icône + label ──
            boolean pressed = view.navMenuPressedIdx == i;
            if (pressed) {
                view.bgPaint.setStyle(Paint.Style.FILL);
                view.bgPaint.setColor(withAlpha(view.theme.caret, 0.22f));
                canvas.drawRoundRect(new RectF(x + 1, rowTop, x + w - 1, rowBottom),
                        6 * density, 6 * density, view.bgPaint);
            }
            int contentColor = pressed ? view.theme.caret : view.theme.textColor;
            float iconCx = x + 12 * density + 8 * density;
            float iconCy = rowTop + rowH * 0.5f;
            drawNavMenuIcon(canvas, row, iconCx, iconCy, contentColor, density);

            view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
            view.textPaint.setTextSize(13 * density);
            view.textPaint.setColor(contentColor);
            String label = navMenuRowLabel(row);
            if (label != null) {
                // Ellipsise : coupe + « … » si le label déborde.
                float maxLabelW = w - 12 * density - 16 * density - 10 * density
                        - 12 * density;
                String drawn = label;
                if (view.textPaint.measureText(label) > maxLabelW) {
                    StringBuilder sb = new StringBuilder(label);
                    while (sb.length() > 1
                            && view.textPaint.measureText(sb + "…") > maxLabelW) {
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    drawn = sb + "…";
                }
                canvas.drawText(drawn, x + 12 * density + 16 * density + 10 * density,
                        rowTop + rowH * 0.5f + view.textPaint.measureText("A") * 0.32f,
                        view.textPaint);
            }
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize());
        }
        canvas.restoreToCount(save);
        view.caretPaint.setStrokeWidth(1f);
    }

    /** v2.36 — le libellé d'une rangée actionnable. */
    private String navMenuRowLabel(EditorView.NavMenuRow row) {
        if (row.ref instanceof NavigationMenu.NavOption) {
            return ((NavigationMenu.NavOption) row.ref).label;
        }
        if (row.ref instanceof EditorView.CodeAction) {
            return ((EditorView.CodeAction) row.ref).title;
        }
        if (row.ref instanceof NavigationMenu.NavTarget) {
            return ((NavigationMenu.NavTarget) row.ref).displayName;
        }
        return null;
    }

    /**
     * v2.36 → v2.37 — icône 16dp d'une rangée, équivalents canvas des
     * CaIcons de CodeAssist : code (chevrons ‹ ›, Declaration), layers
     * (losange + chevrons empilés, Implementations), box (hexagone, Type
     * declaration), pin (tête + tige, Super), gear (quick fixes),
     * lightbulb (intentions), dot (cibles génériques).
     */
    private void drawNavMenuIcon(Canvas canvas, EditorView.NavMenuRow row,
            float cx, float cy, int color, float density) {
        float s = density; // échelle : la grille de référence est 16dp
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1.4f * density);
        view.caretPaint.setColor(color);
        switch (row.type) {
            case EditorView.NavMenuRow.TYPE_OPTION: {
                // navIcon(kind) : Declaration → CaIcons.code (M9 8l-4 4 4 4
                // M15 8l4 4-4 4) ; Implementations → CaIcons.layers
                // (M12 4l8 4-8 4-8-4zM4 12l8 4 8-4M4 16l8 4 8-4) ;
                // Type declaration → CaIcons.box ; Super → CaIcons.pin
                // (M9 4h6l-.8 5 2.3 2.5h-9L9.8 9z + M12 13.5V20).
                String label = row.ref instanceof NavigationMenu.NavOption
                        ? ((NavigationMenu.NavOption) row.ref).label : "";
                if ("Type declaration".equals(label)) {
                    // Box : hexagone M12 3.3 l7.5 4.2 v9.2 L12 20.9 4.5 16.7 V7.5 z
                    // + arêtes internes (échelle /24 → 16dp : ×2/3).
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(cx, cy - 5.8f * s);
                    p.lineTo(cx + 5.0f * s, cy - 2.9f * s);
                    p.lineTo(cx + 5.0f * s, cy + 2.9f * s);
                    p.lineTo(cx, cy + 5.8f * s);
                    p.lineTo(cx - 5.0f * s, cy + 2.9f * s);
                    p.lineTo(cx - 5.0f * s, cy - 2.9f * s);
                    p.close();
                    canvas.drawPath(p, view.caretPaint);
                    canvas.drawLine(cx - 5.0f * s, cy - 2.9f * s, cx, cy,
                            view.caretPaint);
                    canvas.drawLine(cx, cy, cx + 5.0f * s, cy - 2.9f * s,
                            view.caretPaint);
                    canvas.drawLine(cx, cy, cx, cy + 5.8f * s, view.caretPaint);
                } else if ("Implementations".equals(label)) {
                    // Layers : losange supérieur + deux chevrons descendants
                    // (path 24 de CodeAssist, ×0.7 pour le poids visuel des
                    // autres icônes nav).
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(cx, cy - 5.6f * s);
                    p.lineTo(cx + 5.6f * s, cy - 2.8f * s);
                    p.lineTo(cx, cy);
                    p.lineTo(cx - 5.6f * s, cy - 2.8f * s);
                    p.close();
                    canvas.drawPath(p, view.caretPaint);
                    p.reset();
                    p.moveTo(cx - 5.6f * s, cy + 2.8f * s);
                    p.lineTo(cx, cy + 5.6f * s);
                    p.lineTo(cx + 5.6f * s, cy + 2.8f * s);
                    canvas.drawPath(p, view.caretPaint);
                    canvas.drawLine(cx - 5.6f * s, cy + 5.6f * s,
                            cx, cy + 8.4f * s, view.caretPaint);
                    canvas.drawLine(cx, cy + 8.4f * s,
                            cx + 5.6f * s, cy + 5.6f * s, view.caretPaint);
                } else if ("Super".equals(label)) {
                    // Pin : tête trapézoïdale + tige verticale (path 24 de
                    // CodeAssist, ×2/3 centré).
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(cx - 2.0f * s, cy - 5.3f * s);
                    p.lineTo(cx + 2.0f * s, cy - 5.3f * s);
                    p.lineTo(cx + 1.5f * s, cy - 2.0f * s);
                    p.lineTo(cx + 3.0f * s, cy - 0.3f * s);
                    p.lineTo(cx - 3.0f * s, cy - 0.3f * s);
                    p.lineTo(cx - 1.5f * s, cy - 2.0f * s);
                    p.close();
                    canvas.drawPath(p, view.caretPaint);
                    canvas.drawLine(cx, cy + 1.0f * s, cx, cy + 5.3f * s,
                            view.caretPaint);
                } else {
                    // Code : deux chevrons.
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(cx - 3.3f * s, cy - 2.7f * s);
                    p.lineTo(cx - 6.0f * s, cy);
                    p.lineTo(cx - 3.3f * s, cy + 2.7f * s);
                    p.moveTo(cx + 3.3f * s, cy - 2.7f * s);
                    p.lineTo(cx + 6.0f * s, cy);
                    p.lineTo(cx + 3.3f * s, cy + 2.7f * s);
                    canvas.drawPath(p, view.caretPaint);
                }
                break;
            }
            case EditorView.NavMenuRow.TYPE_ACTION: {
                // gear (quick fixes) vs lightbulb (intentions).
                boolean isQuickFix = row.section == EditorView.NavMenuRow.SECTION_QUICK_FIXES;
                if (isQuickFix) {
                    // Gear : anneau + moyeu + 8 dents radiales.
                    canvas.drawCircle(cx, cy, 4.6f * s, view.caretPaint);
                    canvas.drawCircle(cx, cy, 1.8f * s, view.caretPaint);
                    for (int k = 0; k < 8; k++) {
                        double a = Math.toRadians(k * 45.0);
                        float x1 = cx + (float) Math.cos(a) * 4.6f * s;
                        float y1 = cy + (float) Math.sin(a) * 4.6f * s;
                        float x2 = cx + (float) Math.cos(a) * 6.2f * s;
                        float y2 = cy + (float) Math.sin(a) * 6.2f * s;
                        canvas.drawLine(x1, y1, x2, y2, view.caretPaint);
                    }
                } else {
                    // Lightbulb : dôme + base (2 traits).
                    RectF bulb = new RectF(cx - 3.0f * s, cy - 5.2f * s,
                            cx + 3.0f * s, cy + 1.2f * s);
                    canvas.drawArc(bulb, 180, 180, false, view.caretPaint);
                    canvas.drawLine(cx - 3.0f * s, cy - 2.0f * s,
                            cx - 3.0f * s, cy + 1.2f * s, view.caretPaint);
                    canvas.drawLine(cx + 3.0f * s, cy - 2.0f * s,
                            cx + 3.0f * s, cy + 1.2f * s, view.caretPaint);
                    canvas.drawLine(cx - 2.4f * s, cy + 3.2f * s,
                            cx + 2.4f * s, cy + 3.2f * s, view.caretPaint);
                    canvas.drawLine(cx - 1.8f * s, cy + 5.6f * s,
                            cx + 1.8f * s, cy + 5.6f * s, view.caretPaint);
                }
                break;
            }
            default: {
                // TYPE_TARGET → dot (cercle plein r=2.3).
                view.bgPaint.setStyle(Paint.Style.FILL);
                view.bgPaint.setColor(color);
                canvas.drawCircle(cx, cy, 2.3f * s, view.bgPaint);
                break;
            }
        }
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setStyle(Paint.Style.FILL);
    }

    // ════════════════════════════════════════════════════════════════
    // Go-to-symbol popup
    // ════════════════════════════════════════════════════════════════

    /** Draws the go-to-symbol popup: filter field + scrollable list. */
    void drawGoToSymbolPopup(Canvas canvas) {
        if (!view.goToSymbolVisible) return;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.GO_TO_SYMBOL_ROW_HEIGHT_DP * density;
        float width = view.GO_TO_SYMBOL_WIDTH_DP * density;
        float radius = view.GO_TO_SYMBOL_RADIUS_DP * density;
        float filterH = rowH;
        int rowsToShow = Math.min(view.GO_TO_SYMBOL_MAX_ROWS, view.goToSymbolFiltered.size());
        float popupH = filterH + rowH * rowsToShow;
        // Centred at top.
        float anchorX = (view.getWidth() - width) * 0.5f;
        float anchorY = 8 * density;
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + popupH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);
        // Filter field (just render the text — input is handled by the host).
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
        view.textPaint.setColor(view.theme.gutterText);
        float padX = 8 * density;
        String filterLabel = "Filter: " + view.goToSymbolFilter;
        canvas.drawText(filterLabel, anchorX + padX, anchorY + filterH * 0.7f, view.textPaint);
        // Caret at end of filter
        float caretX = anchorX + padX + view.textPaint.measureText(filterLabel);
        view.caretPaint.setColor(view.theme.caret);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1.5f);
        canvas.drawLine(caretX, anchorY + 4, caretX, anchorY + filterH - 4, view.caretPaint);
        // Divider
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawLine(anchorX, anchorY + filterH, anchorX + width, anchorY + filterH, view.caretPaint);
        // Rows
        for (int i = 0; i < rowsToShow; i++) {
            int idx = view.goToSymbolScrollOffset + i;
            if (idx >= view.goToSymbolFiltered.size()) break;
            NavigationMenu.Symbol s = view.goToSymbolFiltered.get(idx);
            float y = anchorY + filterH + i * rowH;
            if (idx == view.goToSymbolSelected) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            view.textPaint.setColor(view.theme.textColor);
            canvas.drawText(s.name, anchorX + padX, y + rowH * 0.7f, view.textPaint);
            // Kind + container on the right
            String detail = s.kind + (s.container.isEmpty() ? "" : " — " + s.container);
            if (!detail.isEmpty()) {
                view.textPaint.setColor(view.theme.gutterText);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
                canvas.drawText(detail, anchorX + width - padX - view.textPaint.measureText(detail),
                    y + rowH * 0.7f, view.textPaint);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            }
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // Completion popup
    // ════════════════════════════════════════════════════════════════

    /**
     * Draws the completion popup as an overlay above the editor content.
     * Anchored at the token start, below the caret line, clamped to the viewport.
     *
     * <p>★ v2.30 — badge de type par suggestion (portage du KindBadge de
     * CodeAssist) : un carré arrondi teinté précède chaque label — « K »
     * violet pour un mot-clé, « C » doré pour une classe, « I » cyan pour
     * une interface, « E » orange pour un enum, « M » pour une méthode,
     * « F » bleu pour un champ, « v » pour une variable, « p » gris pour
     * un package, « @ » pour une annotation… Les caractères du label qui
     * matchent le préfixe tapé sont mis en valeur (accent + gras), le
     * detail (signature/package) passe aligné à droite.</p>
     */
    void drawCompletionPopup(Canvas canvas) {
        if (!view.completionVisible || view.completionItems.isEmpty()) return;
        float[] anchor = view.completionPopupAnchor();
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.COMPLETION_ROW_HEIGHT_DP * density;
        float width = view.COMPLETION_WIDTH_DP * density;
        float radius = COMPLETION_BORDER_RADIUS_DP * density;
        // v2.38 : l'ancre est la SOURCE UNIQUE — le nombre de rangées
        // dérive de sa hauteur (elle réduit les rangées quand le viewport
        // est petit, cf. completionRowsVisible).
        int rowsToShow = Math.max(1, Math.round(anchor[3] / rowH));
        float popupH = rowH * rowsToShow;
        float anchorX = anchor[0];
        float anchorY = anchor[1];

        // Background + border — v2.38 : coins TOP-LEFT/TOP-RIGHT arrondis
        // uniquement (le haut du popup « s'attache » à la ligne du caret,
        // motif bottom-docked de la diagnostic sheet inversé).
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + popupH);
        float[] radii = completionCornerRadii;
        radii[0] = radius; radii[1] = radius;   // top-left
        radii[2] = radius; radii[3] = radius;   // top-right
        radii[4] = 0f; radii[5] = 0f;           // bottom-right (droit)
        radii[6] = 0f; radii[7] = 0f;           // bottom-left (droit)
        Path bg = scratchPath;
        bg.reset();
        bg.addRoundRect(rect, radii, Path.Direction.CW);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawPath(bg, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawPath(bg, view.caretPaint);

        // Rows
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        float padX = 8 * density;
        // v2.38 : clip au path arrondi — la sélection de rangée et le
        // label ne débordent plus sur les coins droits.
        canvas.save();
        canvas.clipPath(bg);
        // ★ v2.30 : thème sombre ? (pour assombrir la palette badge sur glass clair)
        boolean darkTheme = (view.theme.editorBg & 0xFFFFFF) < 0x808080;
        for (int i = 0; i < rowsToShow; i++) {
            int idx = view.completionScrollOffset + i;
            if (idx >= view.completionItems.size()) break;
            float y = anchorY + i * rowH;
            // Highlight selected row
            if (idx == view.completionSelected) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            jo.codeeditor.completion.CompletionSession.Item item = view.completionItems.get(idx);
            drawCompletionRow(canvas, item, anchorX, y, width, rowH, padX, density, darkTheme);
        }
        // Reset text paint size
        view.textPaint.setTextSize(view.metrics.getTextSize());
        // Scrollbar if there are more items than visible
        if (view.completionItems.size() > rowsToShow) {
            float sbX = anchorX + width - 3 * density;
            float sbH = popupH * rowsToShow / (float) view.completionItems.size();
            float sbY = anchorY + (popupH - sbH) * (view.completionScrollOffset / (float)(view.completionItems.size() - rowsToShow));
            view.selPaint.setColor(view.theme.gutterBorder);
            canvas.drawRect(sbX, sbY, sbX + 2 * density, sbY + sbH, view.selPaint);
        }
        canvas.restore();
    }

    /**
     * ★ v2.30 — Une ligne du popup : badge de type (carré arrondi teinté +
     * glyphe) puis label (caractères matchés du préfixe en accent/gras) et
     * detail aligné à droite (signature, package). Le label est clippé à
     * la zone restante pour ne jamais recouvrir le detail.
     */
    private void drawCompletionRow(Canvas canvas,
                                   jo.codeeditor.completion.CompletionSession.Item item,
                                   float anchorX, float y, float width, float rowH,
                                   float padX, float density, boolean darkTheme) {
        String label = item.label;

        // ── Badge de type (portage KindBadge de CodeAssist) ──
        jo.codeeditor.completion.CompletionKindBadge.Meta badge =
                jo.codeeditor.completion.CompletionKindBadge.meta(
                        item.kind, item.kindTag, item.icon, darkTheme,
                        view.theme.func);
        float badgeSize = rowH - 10 * density;   // 28dp row → 18dp badge
        float badgeLeft = anchorX + padX;
        float badgeTop = y + (rowH - badgeSize) * 0.5f;
        RectF badgeRect = new RectF(badgeLeft, badgeTop, badgeLeft + badgeSize,
                badgeTop + badgeSize);

        // Fond : teinte du kind à 20% d'alpha (motif CodeAssist).
        view.bgPaint.setColor((badge.color & 0x00FFFFFF)
                | (0x33 << 24)); // alpha 51 ≈ 0.20
        float badgeRadius = 4 * density;
        canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, view.bgPaint);

        // Glyphe centré (police code, semi-gras — fake bold sur Canvas).
        view.textPaint.setTextSize(badgeSize
                * jo.codeeditor.completion.CompletionKindBadge.glyphSizeFactor(badge.glyph));
        view.textPaint.setColor(badge.color);
        view.textPaint.setFakeBoldText(true);
        Paint.FontMetrics bfm = view.textPaint.getFontMetrics();
        float glyphBaseY = badgeTop + badgeSize * 0.5f
                - (bfm.ascent + bfm.descent) * 0.5f;
        float glyphW = view.textPaint.measureText(badge.glyph);
        canvas.drawText(badge.glyph, badgeLeft + (badgeSize - glyphW) * 0.5f,
                glyphBaseY, view.textPaint);
        view.textPaint.setFakeBoldText(false);
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);

        // ── Detail aligné à droite (mesuré AVANT la zone label) ──
        String detail = (item.detail != null) ? item.detail : "";
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
        float detailW = detail.isEmpty() ? 0f : view.textPaint.measureText(detail);
        float detailRight = anchorX + width - padX;
        float detailLeft = detail.isEmpty() ? detailRight : detailRight - detailW;

        // ── Label : caractères matchés au préfixe en accent + gras ──
        float textX = badgeLeft + badgeSize + 6 * density;
        float labelMaxRight = detail.isEmpty()
                ? (anchorX + width - padX)
                : (detailLeft - 8 * density);
        if (labelMaxRight <= textX) {
            // Aucune place pour le label (popup trop étroit) — badge seul.
            if (!detail.isEmpty()) {
                view.textPaint.setColor(view.theme.gutterText);
                canvas.drawText(detail, detailLeft, y + rowH * 0.7f, view.textPaint);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            }
            return;
        }

        String prefix = view.completionPrefix != null ? view.completionPrefix : "";
        java.util.List<Integer> matched = (prefix.isEmpty())
                ? java.util.Collections.emptyList()
                : jo.codeeditor.completion.CompletionSession.matchPositions(label, prefix);

        canvas.save();
        canvas.clipRect(textX, y, labelMaxRight, y + rowH);
        if (matched.isEmpty()) {
            view.textPaint.setColor(view.theme.keyword);
            canvas.drawText(label, textX, y + rowH * 0.7f, view.textPaint);
        } else {
            // Runs contigus : matché (accent + gras) / non-matché (normal).
            float x = textX;
            int n = label.length();
            int k = 0;
            while (k < n) {
                boolean isMatch = matched.contains(k);
                int runEnd = k;
                while (runEnd + 1 < n
                        && matched.contains(runEnd + 1) == isMatch) {
                    runEnd++;
                }
                String seg = label.substring(k, runEnd + 1);
                view.textPaint.setColor(isMatch ? view.theme.func : view.theme.keyword);
                view.textPaint.setFakeBoldText(isMatch);
                canvas.drawText(seg, x, y + rowH * 0.7f, view.textPaint);
                view.textPaint.setFakeBoldText(false);
                x += view.textPaint.measureText(seg);
                k = runEnd + 1;
            }
        }
        canvas.restore();

        // ── Detail (couleur atténuée, à droite) ──
        if (!detail.isEmpty()) {
            view.textPaint.setColor(view.theme.gutterText);
            canvas.drawText(detail, detailLeft, y + rowH * 0.7f, view.textPaint);
        }
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
    }

    // ════════════════════════════════════════════════════════════════
    // Diagnostic chips overlay
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.31 — CodeAssist DiagnosticChip port: ONE pill per line (the most
     * severe Error/Warning diagnostic), placed after the line end + a 3-char
     * gap, vertically centred on the row. Severity colour at 16% alpha
     * background (full pill shape, no border), severity-coloured dot icon +
     * semibold message. Tapping it opens the diagnostic sheet (hit-test in
     * EditorInputHandler.handleTap via EditorView.findDiagnosticChipAt).
     *
     * <p>The chips layer is clipped to the code area (right of the gutter)
     * so a chip that scrolls left slides UNDER the gutter instead of
     * overlapping it — same as CodeAssist's DiagnosticChipsLayer clip.
     * Info/Hint diagnostics get NO chip (squiggle + gutter dot only).
     */
    void drawDiagnosticChips(Canvas canvas, EditorDocument doc,
                              int firstVisible, int lastVisible,
                              float lineHeight, float paddingTop) {
        // ★ v2.33 — chipExtent (pattern CodeAssist EditorGeometry) : remis à
        // zéro à chaque frame puis étendu par la chip la plus à droite —
        // maxH() l'utilise pour rendre scrollable le débordement de chip.
        view.chipExtentContentX = 0f;
        if (!view.diagnosticChipsEnabled) return;
        List<DiagnosticShift.Diagnostic> diags = view.session.getDiagnostics();
        if (diags.isEmpty()) return;
        // One chip per line — the most severe Error/Warning on it.
        java.util.Map<Integer, DiagnosticShift.Diagnostic> chipPerLine = new java.util.HashMap<>();
        for (DiagnosticShift.Diagnostic d : diags) {
            if (d.severity != 3 && d.severity != 2) continue; // Error/Warning only
            int line = doc.lineForOffset(d.start);
            if (line < firstVisible || line > lastVisible) continue;
            if (view.isLineFoldedCached(line)) continue;
            DiagnosticShift.Diagnostic cur = chipPerLine.get(line);
            if (cur == null || d.severity > cur.severity) chipPerLine.put(line, d);
        }
        if (chipPerLine.isEmpty()) return;
        canvas.save();
        canvas.clipRect(view.metrics.getGutterWidth(), 0, view.getWidth(), view.getHeight());
        for (java.util.Map.Entry<Integer, DiagnosticShift.Diagnostic> e : chipPerLine.entrySet()) {
            DiagnosticShift.Diagnostic d = e.getValue();
            float[] m = view.diagnosticChipMetrics(d, e.getKey());
            if (m == null) continue;
            float x = m[0], y = m[1], w = m[2], h = m[3], iconR = m[4], iconGap = m[5], padX = m[6];
            int color = getSquiggleColor(d.severity);
            RectF rect = new RectF(x, y, x + w, y + h);
            // Pill background: severity colour at 16% alpha (CodeAssist).
            view.bgPaint.setColor(color);
            view.bgPaint.setAlpha(41); // 0.16 × 255
            canvas.drawRoundRect(rect, h * 0.5f, h * 0.5f, view.bgPaint);
            view.bgPaint.setAlpha(255);
            // Icon substitute: a filled severity-coloured dot (CodeAssist uses
            // CaIcons.error/warning — a dot reads equally at this size).
            view.selPaint.setColor(color);
            view.selPaint.setAntiAlias(true);
            canvas.drawCircle(x + padX + iconR, y + h * 0.5f, iconR, view.selPaint);
            // Label: full code size, semibold, severity colour, one line.
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize());
            view.textPaint.setFakeBoldText(true);
            view.textPaint.setColor(color);
            String label = d.message != null ? d.message : "";
            // Re-truncate identically to diagnosticChipMetrics (shared sizing).
            float avail = view.getWidth() - x - 4 * view.getResources().getDisplayMetrics().density
                - padX * 2 - iconR * 2 - iconGap;
            if (label.length() > 90) label = label.substring(0, 88) + "…";
            while (label.length() > 1 && view.textPaint.measureText(label) > avail) {
                label = label.substring(0, label.length() - 2) + "…";
            }
            float baseline = y + h * 0.5f
                - (view.textPaint.ascent() + view.textPaint.descent()) * 0.5f;
            canvas.drawText(label, x + padX + iconR * 2 + iconGap, baseline, view.textPaint);
            // ★ v2.33 — étendue CONTENU (hors gutter, indépendante du scroll :
            // +hOffset annule la soustraction écran) de la chip — maxH()
            // l'utilise pour rendre scrollable le débordement (pattern
            // onChipExtent de CodeAssist).
            float textW = view.textPaint.measureText(label);
            float extent = x - view.metrics.getGutterWidth() + view.hOffset
                    + padX + iconR * 2 + iconGap + textW + padX;
            if (extent > view.chipExtentContentX) {
                view.chipExtentContentX = extent;
            }
            view.textPaint.setFakeBoldText(false);
        }
        canvas.restore();
    }

    // ════════════════════════════════════════════════════════════════
    // Selection toolbar (floating Copy/Cut/Paste/All)
    // ════════════════════════════════════════════════════════════════

    /**
     * Draws the floating selection toolbar — a frosted pill anchored above
     * the ACTIVE end of the selection (matching CodeAssist's UX where the
     * toolbar follows the finger to where the user finished selecting).
     *
     * <p><b>v2.34 — portage complet {@code SelectionToolbar} de CodeAssist :</b></p>
     * <ul>
     *   <li><b>géométrie partagée</b> — le layout vient de
     *       {@link EditorView#selectionToolbarMetrics()} (source unique pour
     *       le rendu ET le hit-test — avant, le calcul était dupliqué) ;</li>
     *   <li><b>mode collapsed</b> — Copy/Cut disparaissent quand la sélection
     *       est vide (re-tap sur le caret → Paste / Select all + icônes) ;</li>
     *   <li><b>boutons Docs ℹ / Actions ⋯</b> après un divider conditionnel ;</li>
     *   <li><b>animation d'entrée</b> — portage {@code entrancePop} (scale
     *       0.96→1 + fade, 160 ms) et cascade par item
     *       ({@code toolbarItemEntrance} : fade + montée 5dp, 24 ms/item) ;</li>
     *   <li><b>feedback press</b> — fond teinté accent 22 % + texte accent
     *       sur l'item pressé (équivalent pressScale/primaryContainer).</li>
     * </ul>
     */
    void drawSelectionToolbar(Canvas canvas) {
        if (!view.selectionToolbarVisible || view.session == null) return;
        EditorView.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        if (m == null || m.count == 0) return;
        float density = view.getResources().getDisplayMetrics().density;

        // ── Animation d'entrée (portage entrancePop + cascade) ────────
        long now = android.os.SystemClock.uptimeMillis();
        float tOverall = (now - view.selectionToolbarShownAt) / 160f;
        boolean animating = tOverall >= 0 && tOverall < 2.2f;
        float pop = clamp01(tOverall);
        float ease = 1f - (1f - pop) * (1f - pop) * (1f - pop); // easeOutCubic
        float scale = 0.96f + 0.04f * ease;
        float overallAlpha = clamp01(tOverall * 1.4f);
        if (animating) view.postInvalidateOnAnimation(); // boucle de frames

        int saveCount = canvas.save();
        if (animating) {
            canvas.scale(scale, scale, m.x + m.w * 0.5f, m.y + m.h * 0.5f);
        }

        // Shadow (a slightly larger, semi-transparent rounded rect).
        RectF shadowRect = new RectF(m.x + 1, m.y + 2, m.x + m.w + 1, m.y + m.h + 2);
        view.bgPaint.setStyle(Paint.Style.FILL);
        view.bgPaint.setColor(withAlpha(0xFF000000, 0.24f * overallAlpha));
        canvas.drawRoundRect(shadowRect, m.radius, m.radius, view.bgPaint);
        // Glass pill background.
        RectF rect = new RectF(m.x, m.y, m.x + m.w, m.y + m.h);
        view.bgPaint.setColor(withAlpha(view.theme.glassBg, overallAlpha));
        canvas.drawRoundRect(rect, m.radius, m.radius, view.bgPaint);
        // Border.
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(withAlpha(view.theme.glassBorder, overallAlpha));
        canvas.drawRoundRect(rect, m.radius, m.radius, view.caretPaint);

        // Divider conditionnel (CodeAssist ToolbarDivider — entre le groupe
        // texte et le groupe d'icônes).
        if (m.dividerCount > 0 && m.dividerX != Float.MIN_VALUE) {
            view.caretPaint.setStrokeWidth(0.5f);
            canvas.drawLine(m.dividerX, m.y + m.padY, m.dividerX,
                    m.y + m.h - m.padY, view.caretPaint);
        }

        // Items.
        view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        view.textPaint.setTextSize(13 * density);
        for (int i = 0; i < m.count; i++) {
            // Cascade par item (CodeAssist toolbarItemEntrance : index*30ms,
            // fade + rise 5dp — resserré ici à 24ms pour la snappiness tactile).
            float p = animating
                    ? clamp01((now - view.selectionToolbarShownAt - i * 24f) / 120f)
                    : 1f;
            float itemAlpha = overallAlpha * p;
            if (itemAlpha <= 0f) continue;
            float dy = (1f - p) * 5 * density; // montée depuis 5dp dessous
            boolean pressed = view.selectionToolbarPressedIdx == i;
            int contentColor = pressed ? view.theme.caret : view.theme.textColor;

            if (pressed) {
                // Feedback press : fond teinté accent, coins arrondis.
                RectF pr = new RectF(m.itemX[i], m.y + 2 * density,
                        m.itemX[i] + m.itemW[i], m.y + m.h - 2 * density);
                view.bgPaint.setColor(withAlpha(view.theme.caret, 0.22f * itemAlpha));
                canvas.drawRoundRect(pr, m.radius * 0.7f, m.radius * 0.7f, view.bgPaint);
            }

            if (m.isIcon[i]) {
                drawSelectionToolbarIcon(canvas, m, i, contentColor, itemAlpha, dy, density);
            } else {
                float labelX = m.itemX[i] + m.padX;
                float labelY = m.y + m.h * 0.5f
                        + view.textPaint.measureText("A") * 0.35f + dy;
                view.textPaint.setColor(withAlpha(contentColor, itemAlpha));
                canvas.drawText(m.label[i], labelX, labelY, view.textPaint);
            }
        }
        // Reset paints.
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(saveCount);
    }

    /**
     * v2.34 — dessine un bouton icône de la toolbar de sélection :
     * <b>Docs</b> = cercle + « i » (CaIcons.info), <b>Actions</b> = trois
     * points (CaIcons.ellipsis). 16dp, teinte atténuée — parité
     * ToolbarIconItem de CodeAssist (onSurfaceVariant).
     */
    private void drawSelectionToolbarIcon(Canvas canvas,
            EditorView.SelectionToolbarMetrics m, int i, int color,
            float alpha, float dy, float density) {
        float cx = m.itemX[i] + m.itemW[i] * 0.5f;
        float cy = m.y + m.h * 0.5f + dy;
        int c = withAlpha(color, alpha);
        view.bgPaint.setStyle(Paint.Style.FILL);
        if (m.action[i] == EditorView.SEL_ACT_DOCS) {
            // ℹ — cercle 14dp + « i » centré.
            view.caretPaint.setStyle(Paint.Style.STROKE);
            view.caretPaint.setStrokeWidth(1.2f * density);
            view.caretPaint.setColor(c);
            canvas.drawCircle(cx, cy, 7 * density, view.caretPaint);
            view.caretPaint.setStyle(Paint.Style.FILL);
            view.textPaint.setTextSize(11 * density);
            float tw = view.textPaint.measureText("i");
            float halfH = view.textPaint.measureText("A") * 0.32f;
            view.textPaint.setColor(c);
            canvas.drawText("i", cx - tw * 0.5f, cy + halfH, view.textPaint);
            view.textPaint.setTextSize(13 * density);
        } else {
            // ⋯ — trois points pleins.
            view.bgPaint.setColor(c);
            float r = 1.2f * density;
            float gap = 3.5f * density;
            canvas.drawCircle(cx - gap, cy, r, view.bgPaint);
            canvas.drawCircle(cx, cy, r, view.bgPaint);
            canvas.drawCircle(cx + gap, cy, r, view.bgPaint);
        }
    }

    /** v2.34 — applique une alpha relative (0..1) à une couleur ARGB. */
    private static int withAlpha(int color, float alpha) {
        if (alpha <= 0f) return color & 0x00FFFFFF;
        if (alpha >= 1f) return color;
        int a = Math.round(android.graphics.Color.alpha(color) * alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    // ════════════════════════════════════════════════════════════════
    // Diagnostic popup (per-diagnostic sheet)
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.31 — CodeAssist DiagnosticSheet port: a modal bottom sheet for ONE
     * diagnostic. Layout (shared geometry via {@link
     * EditorView#diagnosticSheetMetrics}, also used by the tap hit-test):
     * <ul>
     *   <li><b>Scrim</b> over the editor above the panel (tap = dismiss) —
     *       the gesture layer consumes touches while the sheet is up.</li>
     *   <li><b>Panel</b> docked at the bottom with rounded TOP corners, glass
     *       background and a hairline border.</li>
     *   <li><b>Header</b> — severity dot + coloured severity label + a round
     *       × close button.</li>
     *   <li><b>Message</b> — the FULL text, word-wrapped (≤ 6 lines).</li>
     *   <li><b>Quick fixes</b> — a divider + label + one 44dp row per action;
     *       tapping a row applies it.</li>
     * </ul>
     * Opened by: tapping the gutter dot, tapping a squiggle range, or
     * tapping a diagnostic chip on the line.
     */
    void drawDiagnosticPopup(Canvas canvas) {
        if (!view.diagnosticPopupVisible || view.diagnosticPopupItem == null || view.session == null) return;
        DiagnosticShift.Diagnostic d = view.diagnosticPopupItem;
        float density = view.getResources().getDisplayMetrics().density;
        float[] m = view.diagnosticSheetMetrics();
        if (m == null) return;
        float panelTop = m[0], panelBottom = m[1], actionStartY = m[2], actionRowH = m[3];
        float closeCx = m[4], closeCy = m[5], closeR = m[6];
        float width = view.getWidth();
        float radius = EditorView.DIAG_SHEET_RADIUS_DP * density;
        float padX = 16 * density;

        // 1. Scrim over the editor above the panel (tap = dismiss).
        view.bgPaint.setColor(android.graphics.Color.argb(96, 0, 0, 0));
        canvas.drawRect(0, 0, width, panelTop, view.bgPaint);

        // 2. Panel — rounded TOP corners only (bottom-docked sheet shape).
        Path panel = scratchPath;
        panel.reset();
        panel.moveTo(0, panelBottom);
        panel.lineTo(0, panelTop + radius);
        panel.quadTo(0, panelTop, radius, panelTop);
        panel.lineTo(width - radius, panelTop);
        panel.quadTo(width, panelTop, width, panelTop + radius);
        panel.lineTo(width, panelBottom);
        panel.close();
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawPath(panel, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawPath(panel, view.caretPaint);

        // 3. Header — severity dot + label + close button.
        int color = getSquiggleColor(d.severity);
        view.selPaint.setColor(color);
        view.selPaint.setAntiAlias(true);
        canvas.drawCircle(padX + 5 * density, closeCy, 5 * density, view.selPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        view.textPaint.setFakeBoldText(true);
        view.textPaint.setColor(color);
        String sevLabel = d.severity == 3 ? "Error" : d.severity == 2 ? "Warning" : "Info";
        canvas.drawText(sevLabel, padX + 14 * density, closeCy + view.metrics.getTextSize() * 0.30f, view.textPaint);
        view.textPaint.setFakeBoldText(false);
        // Close (×): outline circle + glyph.
        view.caretPaint.setStrokeWidth(1.2f * density);
        view.caretPaint.setColor(view.theme.gutterText);
        canvas.drawCircle(closeCx, closeCy, closeR * 0.62f, view.caretPaint);
        view.textPaint.setColor(view.theme.gutterText);
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
        float xGlyphW = view.textPaint.measureText("×") * 0.5f;
        canvas.drawText("×", closeCx - xGlyphW, closeCy + view.metrics.getTextSize() * 0.32f, view.textPaint);

        // 4. Message — full text, word-wrapped, ≤ DIAG_SHEET_MAX_MSG_LINES.
        view.textPaint.setColor(view.theme.textColor);
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        String msg = d.message != null ? d.message : "";
        float msgY = panelTop + EditorView.DIAG_SHEET_HEADER_DP * density
            + EditorView.DIAG_SHEET_MSG_LINE_DP * density * 0.8f;
        float maxMsgW = width - padX * 2;
        StringBuilder line = new StringBuilder();
        int drawn = 1;
        for (String word : msg.split("\\s+")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (view.textPaint.measureText(test) > maxMsgW && line.length() > 0) {
                canvas.drawText(line.toString(), padX, msgY, view.textPaint);
                msgY += EditorView.DIAG_SHEET_MSG_LINE_DP * density;
                if (++drawn > EditorView.DIAG_SHEET_MAX_MSG_LINES) break;
                line = new StringBuilder(word);
            } else {
                line = line.length() == 0 ? new StringBuilder(word) : line.append(" ").append(word);
            }
        }
        if (line.length() > 0 && drawn <= EditorView.DIAG_SHEET_MAX_MSG_LINES) {
            canvas.drawText(line.toString(), padX, msgY, view.textPaint);
        }

        // 5. Quick fixes — divider + label + rows.
        int dl = view.session.getDocument().lineForOffset(d.start);
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(dl);
        if (actions != null && !actions.isEmpty()) {
            view.caretPaint.setStrokeWidth(1f);
            view.caretPaint.setColor(view.theme.glassBorder);
            canvas.drawLine(padX, actionStartY - EditorView.DIAG_SHEET_ACTIONS_BLOCK_DP * density * 0.72f,
                width - padX, actionStartY - EditorView.DIAG_SHEET_ACTIONS_BLOCK_DP * density * 0.72f, view.caretPaint);
            view.textPaint.setColor(view.theme.gutterText);
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
            view.textPaint.setFakeBoldText(true);
            canvas.drawText("QUICK FIXES", padX,
                actionStartY - EditorView.DIAG_SHEET_ACTIONS_BLOCK_DP * density * 0.28f, view.textPaint);
            view.textPaint.setFakeBoldText(false);
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            for (int i = 0; i < actions.size(); i++) {
                EditorView.CodeAction a = actions.get(i);
                float rowY = actionStartY + i * actionRowH;
                if (rowY + actionRowH > panelBottom) break;
                // Row glyph: filled dot — accent for quickfix, muted otherwise.
                view.selPaint.setColor(a.kind != null && a.kind.equals("quickfix")
                    ? view.theme.keyword : view.theme.gutterText);
                canvas.drawCircle(padX + 3.2f * density, rowY + actionRowH * 0.5f, 3.2f * density, view.selPaint);
                view.textPaint.setColor(view.theme.textColor);
                canvas.drawText(a.title, padX + 12 * density, rowY + actionRowH * 0.5f
                    + view.metrics.getTextSize() * 0.32f, view.textPaint);
            }
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // Diagnostic sheet (bottom sheet listing all diagnostics — Gap 8)
    // ════════════════════════════════════════════════════════════════

    /** Draws the diagnostic sheet (bottom sheet listing all diagnostics — Gap 8). */
    void drawDiagnosticSheet(Canvas canvas) {
        if (!view.diagnosticSheetVisible) return;
        List<DiagnosticShift.Diagnostic> diags = view.session.getDiagnostics();
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = OVERLAY_ROW_HEIGHT_DP * density;
        float radius = OVERLAY_RADIUS_DP * density;
        int maxRows = 8;
        int rowsToShow = Math.min(maxRows, diags.size() + 1);
        float sheetH = rowH * rowsToShow;
        float width = view.getWidth();
        float anchorX = 0;
        float anchorY = view.getHeight() - sheetH;
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + sheetH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRect(rect, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawLine(anchorX, anchorY, anchorX + width, anchorY, view.caretPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        // Header
        view.textPaint.setColor(view.theme.keyword);
        canvas.drawText("Diagnostics (" + diags.size() + ")",
            anchorX + 12 * density, anchorY + rowH * 0.7f, view.textPaint);
        // Rows
        for (int i = 0; i < Math.min(rowsToShow - 1, diags.size()); i++) {
            int idx = view.diagnosticSheetScroll + i;
            if (idx >= diags.size()) break;
            DiagnosticShift.Diagnostic d = diags.get(idx);
            float y = anchorY + (i + 1) * rowH;
            String sev = d.severity == 3 ? "ERR" : d.severity == 2 ? "WRN" : "INF";
            view.textPaint.setColor(getSquiggleColor(d.severity));
            canvas.drawText(sev, anchorX + 12 * density, y + rowH * 0.7f, view.textPaint);
            String msg = (d.message == null ? "" : d.message);
            if (msg.length() > 60) msg = msg.substring(0, 57) + "...";
            view.textPaint.setColor(view.theme.textColor);
            canvas.drawText(msg, anchorX + 60 * density, y + rowH * 0.7f, view.textPaint);
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // Local helpers (delegating to EditorView where shared)
    // ════════════════════════════════════════════════════════════════

    /**
     * Delegates to {@link EditorView#clamp(int, int, int)} so the draw
     * methods can use a bare {@code clamp(...)} call without the class
     * qualifier.
     */
    private static int clamp(int v, int lo, int hi) {
        return EditorView.clamp(v, lo, hi);
    }
}
