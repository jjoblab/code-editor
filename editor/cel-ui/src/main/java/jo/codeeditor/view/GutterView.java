package jo.codeeditor.view;

import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Renders line numbers and diagnostic dots in the gutter area.
 *
 * <p>v3.5.0: fold-aware. When a {@link #setHiddenLineChecker(IntPredicate)
 * hidden-line checker} is set, hidden lines are skipped and the remaining
 * line numbers are pulled up to fill the gap — matching the text area's
 * fold-aware Y mapping in {@link EditorView#docLineToY(int)}. Without this,
 * collapsing a fold left the gutter line numbers misaligned with the text.
 *
 * @since v1.0.0
*/
public class GutterView {

    private final EditorMetrics metrics;
    private EditorTheme theme;

    // Diagnostic data: severity per line (0=none, 1=info, 2=warning, 3=error)
    private int[] diagnostics = new int[0];

    /** v3.5.0: returns true if the given doc line is hidden by a collapsed fold. */
    private IntPredicate hiddenLineChecker;

    /** v3.7.2: screen density (px/dp), used to size the diagnostic dot. */
    private float density = 1f;

    /**
     * v3.36.0 (roadmap item 9) — plugin gutter marks: line → ARGB color.
     * Drawn as a thin vertical bar at the RIGHT edge of the line-number
     * area (VCS-blame style) — opposite corner from the diagnostic dots,
     * so the two never collide. Set by the renderer from the painter
     * host's frame.
     */
    private Map<Integer, Integer> pluginMarks = new HashMap<>(0);

    public GutterView(EditorMetrics metrics, EditorTheme theme) {
        this.metrics = metrics;
        this.theme = theme;
    }

    public void setTheme(EditorTheme theme) {
        this.theme = theme;
    }

    /**
     * v3.7.2: Sets the screen density (px per dp). Used to size the
     * diagnostic dot (radius = 2.5dp, position = 5dp + radius from left).
     * If never called, defaults to 1 (treats dp values as px — for tests).
     */
    public void setDensity(float density) {
        this.density = density;
    }

    /**
     * Sets the diagnostic severity for each line.
     */
    public void setDiagnostics(int[] diagnostics) {
        this.diagnostics = diagnostics != null ? diagnostics : new int[0];
    }

    /**
     * v3.5.0: Sets the predicate that reports whether a document line is
     * currently hidden by a collapsed fold region. When set, {@link #draw}
     * skips hidden lines and compacts the remaining line numbers so the
     * gutter stays aligned with the fold-aware text area.
     *
     * @param checker predicate returning true for hidden doc lines, or null
     *                to disable fold-aware rendering
     */
    public void setHiddenLineChecker(IntPredicate checker) {
        this.hiddenLineChecker = checker;
    }

    /**
     * v3.36.0 (roadmap item 9) — sets the plugin gutter marks (line → ARGB
     * color). An empty/null map clears them.
     */
    public void setPluginMarks(Map<Integer, Integer> lineToColor) {
        this.pluginMarks = lineToColor != null ? lineToColor : new HashMap<>(0);
    }

    /**
     * Draws the gutter: background, separator line, line numbers, and diagnostic dots.
     * <p>
     * v1.0.8 bugfix (Bug 6): line numbers are right-aligned at
     * {@code gutterWidth - foldStripWidth - 0.5*charWidth} (i.e. at the end
     * of the line-number area, BEFORE the fold strip). Previously they were
     * right-aligned at {@code gutterWidth - 0.5*charWidth}, which put them
     * on top of the fold chevron.
     * <p>
     * v3.5.0: when a hidden-line checker is set, hidden lines are skipped
     * and the Y of each visible line is computed by walking doc lines and
     * counting only visible ones (matching EditorView.docLineToY).
     * <p>
     * v3.13.0: the gutter background is now semi-transparent (glass effect)
     * so the user can faintly see the text scrolling behind it. The alpha
     * is tuned to be readable (line numbers still legible) while letting
     * the text color bleed through subtly.
     */
    public void draw(Canvas canvas, float scrollTop, float viewHeight, int totalLines, int currentLine) {
        float gutterWidth = metrics.getGutterWidth();
        float foldStripWidth = metrics.getFoldStripWidth();
        float lineNumberAreaRight = gutterWidth - foldStripWidth;
        float lineHeight = metrics.getLineHeight();
        float paddingTop = metrics.getPadTop();

        // v3.13.0: Glass/semi-transparent gutter background.
        // Instead of a fully opaque fill, we use ~88% alpha so the text
        // scrolling behind the gutter is faintly visible — a frosted-glass
        // effect similar to VS Code's translucent gutter.
        Paint bgPaint = new Paint();
        bgPaint.setColor(applyAlpha(theme.gutterBg, 0.88f));
        canvas.drawRect(0, 0, gutterWidth, viewHeight, bgPaint);

        // Separator line
        Paint sepPaint = new Paint();
        sepPaint.setColor(theme.gutterBorder);
        sepPaint.setStrokeWidth(1f);
        canvas.drawLine(gutterWidth, 0, gutterWidth, viewHeight, sepPaint);

        // Line numbers — right-aligned at the end of the line-number area
        // (BEFORE the fold strip), with a 0.5-char right padding.
        Paint numberPaint = new Paint(metrics.getGutterPaint());
        numberPaint.setColor(theme.gutterText);

        float textX = lineNumberAreaRight - metrics.getCharWidth() * 0.5f;

        if (hiddenLineChecker != null) {
            // v3.5.0: fold-aware path. Walk all doc lines, skip hidden ones,
            // and draw each visible line at (visibleRowSoFar × lineHeight).
            int visibleRow = 0;
            for (int i = 0; i < totalLines; i++) {
                if (hiddenLineChecker.test(i)) continue;
                float y = paddingTop + visibleRow * lineHeight - scrollTop;
                visibleRow++;
                // Skip lines above the viewport (but keep counting visibleRow
                // so the Y stays correct).
                if (y + lineHeight < 0) continue;
                if (y > viewHeight) break;
                drawLineNumber(canvas, numberPaint, textX, y, lineHeight, i, currentLine, lineNumberAreaRight);
            }
        } else {
            // Legacy path: no folds, every doc line is visible.
            int firstLine = (int) (scrollTop / lineHeight);
            int lastLine = Math.min(totalLines - 1, (int) ((scrollTop + viewHeight) / lineHeight));
            for (int i = firstLine; i <= lastLine; i++) {
                float y = paddingTop + i * lineHeight - scrollTop;
                drawLineNumber(canvas, numberPaint, textX, y, lineHeight, i, currentLine, lineNumberAreaRight);
            }
        }
    }

    /** Draws a single line number + diagnostic dot at the given Y.
     *
     *  <p>v3.7.1: introduced the diagnostic dot, initially placed just-left
     *  of the line number.
     *
     *  <p>v3.7.2 bugfix (Bug 7): the dot was too close to the line number
     *  (3px gap). CodeAssist pins the dot to the FAR LEFT edge of the gutter
     *  (center at {@code 5dp + dotR} from the left edge), with the line
     *  number right-aligned at the other end of the gutter — so dot and
     *  number are on opposite ends, separated by ~50-60dp of empty space.
     *  We now match that layout. The dot is also smaller (radius = 2.5dp
     *  instead of 0.35*charWidth which was ~3.5dp) and a solid filled
     *  circle (no inner highlight halo — CodeAssist draws a plain circle).
     */
    private void drawLineNumber(Canvas canvas, Paint numberPaint, float textX,
                                 float y, float lineHeight, int lineIdx, int currentLine,
                                 float lineNumberAreaRight) {
        String lineStr = String.valueOf(lineIdx + 1);
        float lineNumberWidth = numberPaint.measureText(lineStr);

        if (lineIdx == currentLine) {
            numberPaint.setFakeBoldText(true);
            numberPaint.setColor(theme.textColor);
        }
        canvas.drawText(lineStr, textX - lineNumberWidth,
            y + lineHeight * 0.75f, numberPaint);
        if (lineIdx == currentLine) {
            numberPaint.setFakeBoldText(false);
            numberPaint.setColor(theme.gutterText);
        }

        // v3.7.2: CodeAssist-style diagnostic dot — pinned to the LEFT edge
        // of the gutter (not next to the line number). Center x = 5dp + dotR
        // from the left edge. Solid filled circle.
        // v3.13.0: enlarged from 2.5dp to 3.5dp radius per user request.
        // v2.31: trimmed back to 3dp ("un petit peu") — still above CodeAssist's
        // 2.5dp but visually lighter next to the line numbers.
        if (lineIdx < diagnostics.length && diagnostics[lineIdx] > 0) {
            // Only draw dots for errors (3) and warnings (2) — CodeAssist
            // draws no gutter dot for info (severity 1). Info gets a squiggle
            // in the text area but no gutter indicator.
            int sev = diagnostics[lineIdx];
            if (sev < 2) return; // skip info — matches CodeAssist
            float dotR = 3.0f * density; // v2.31: was 3.5dp
            float dotCenterX = 5f * density + dotR;
            float dotY = y + lineHeight * 0.5f;
            Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            dotPaint.setColor(getDiagnosticColor(sev));
            dotPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(dotCenterX, dotY, dotR, dotPaint);
        }

        // v3.36.0 (roadmap item 9) — plugin gutter mark: thin vertical bar
        // at the RIGHT edge of the line-number area (2dp wide, 60% of the
        // row height), VCS-blame style. Drawn last so it stays visible over
        // the glass background.
        Integer markColor = pluginMarks.get(lineIdx);
        if (markColor != null) {
            Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            markPaint.setColor(markColor);
            markPaint.setStyle(Paint.Style.FILL);
            float barX = lineNumberAreaRight - 2.5f * density;
            float barY = y + lineHeight * 0.2f;
            canvas.drawRect(barX, barY, barX + 2f * density,
                    barY + lineHeight * 0.6f, markPaint);
        }
    }

    private int getDiagnosticColor(int severity) {
        switch (severity) {
            case 3: return theme.error;
            case 2: return theme.warning;
            case 1: return theme.info;
            default: return 0;
        }
    }

    /**
     * v3.13.0: Applies an alpha multiplier to an ARGB color. Used by the
     * glass-effect gutter background.
     */
    private static int applyAlpha(int color, float alpha) {
        int a = (color >>> 24) & 0xFF;
        int newA = (int) (a * alpha);
        return (newA << 24) | (color & 0x00FFFFFF);
    }
}
