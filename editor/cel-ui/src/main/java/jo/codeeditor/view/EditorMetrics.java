package jo.codeeditor.view;

import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Text metrics for the editor: line height, character width, padding.
 * Uses a monospace font for predictable character sizing.
 
 *
 * @since v1.0.0
*/
public class EditorMetrics {

    private float lineHeight;
    private float charWidth;
    private float padTop;
    private float padLeft;
    private float padRight;
    private float padBottom;
    private float gutterWidth;
    private float foldStripWidth;
    private float textSize;

    /**
     * v3.35.0 (roadmap item 2) — bumped on EVERY typeface / text-size
     * change. The view's content-addressed shaped-layout cache
     * invalidates on this revision: font properties are baked into each
     * memoized StaticLayout's TextPaint at build time.
     */
    private int fontRev;

    private final Paint textPaint;
    private final Paint gutterPaint;

    public EditorMetrics() {
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gutterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.MONOSPACE);
        gutterPaint.setTypeface(Typeface.MONOSPACE);
        setTextSize(14f);
    }

    /**
     * Recalculates all metrics based on the given text size (in pixels).
     * <p>
     * {@code lineHeight} is derived from {@link Paint#getFontMetrics} so it
     * matches the actual advance between two wrapped rows of the same
     * paragraph — using {@code textSize * 1.3} was off by ~1px and the drift
     * accumulated into visible overlap/gap when word wrap landed.
     * <p>
     * v1.0.8 bugfix (Bug 6): the gutter width now INCLUDES the fold strip
     * (line-number area + fold strip), so fold chevrons no longer overlap
     * line numbers. Previously the fold strip was drawn inside the
     * line-number area, causing the chevron to sit on top of the last digit.
     */
    public void setTextSize(float sizePx) {
        this.textSize = sizePx;
        fontRev++;   // v3.35.0: font generation changed — shaped layouts stale.
        textPaint.setTextSize(sizePx);
        gutterPaint.setTextSize(sizePx * 0.85f);

        // Real font metrics — pixel-exact inter-row advance.
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        lineHeight = (float) Math.ceil(fm.bottom - fm.top + fm.leading);
        // Monospace: all chars same width. measureText returns the advance.
        charWidth = textPaint.measureText("M");
        padTop = lineHeight * 0.5f;
        padLeft = charWidth * 0.5f;
        padRight = charWidth * 0.5f;
        // Generous bottom padding so the last line can scroll well above the IME.
        padBottom = lineHeight * 6f;
        // Fold strip is a dedicated column to the RIGHT of the line numbers.
        // ~2 chars wide so the chevron has breathing room.
        foldStripWidth = charWidth * 2f;
        // Gutter = line-number area (5 chars for ~4 digits + 1 padding) +
        // fold strip. The line numbers are right-aligned within the
        // line-number area (i.e. ending at gutterWidth - foldStripWidth).
        gutterWidth = charWidth * 5 + foldStripWidth;
    }

    public float getLineHeight() { return lineHeight; }
    public float getCharWidth() { return charWidth; }
    public float getPadTop() { return padTop; }
    public float getPadLeft() { return padLeft; }
    public float getPadRight() { return padRight; }
    public float getPadBottom() { return padBottom; }
    public float getGutterWidth() { return gutterWidth; }
    public float getFoldStripWidth() { return foldStripWidth; }
    public float getTextSize() { return textSize; }

    // Legacy aliases
    public float getPaddingTop() { return padTop; }
    public float getPaddingLeft() { return padLeft; }

    public Paint getTextPaint() { return textPaint; }
    public Paint getGutterPaint() { return gutterPaint; }
    public Typeface getTypeface() { return textPaint.getTypeface(); }
    /** v3.35.0 — font generation (bumped on typeface/text-size change). */
    public int getFontRevision() { return fontRev; }
    public void setTypeface(Typeface tf) {
        fontRev++;   // v3.35.0: font generation changed — shaped layouts stale.
        textPaint.setTypeface(tf);
        gutterPaint.setTypeface(tf);
    }

    // v3.33.5: lineToY, yToLine, colToX, xToCol, computeWidth supprimés (code mort —
    // EditorView reimplements the same math inline).
}
