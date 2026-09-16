package jo.codeeditor.view;

/**
 * v3.36.0 — Zoom state extracted from EditorView (roadmap item 11:
 * "extraire metrics/scroll-state/popup-anchors vers les managers" —
 * this is the metrics/zoom half).
 *
 * <p>Owns the font scale and every way to change it: direct
 * {@link #setFontScale(float)}, caret-anchored pinch
 * {@link #applyPinchScale(float)} (v2.58 port — the caret stays visually
 * fixed while the viewport virtually scrolls under it), and the
 * {@code increaseFontSize}/{@code decreaseFontSize} helpers (v3.18). The
 * bodies are verbatim moves from EditorView; EditorView keeps delegating
 * wrappers with the historical public signatures.</p>
 *
 * @since v3.36.0
 */
final class EditorZoomController {

    static final float MIN_FONT_SCALE = 0.6f;
    static final float MAX_FONT_SCALE = 2.6f;

    private final EditorView view;

    /** Current font scale (1 = base). */
    float fontScale = 1.0f;

    EditorZoomController(EditorView view) {
        this.view = view;
    }

    /** Clamps the scale to [0.6, 2.6]. */
    static float clampFontScale(float s) {
        if (s < MIN_FONT_SCALE) return MIN_FONT_SCALE;
        if (s > MAX_FONT_SCALE) return MAX_FONT_SCALE;
        return s;
    }

    /** Sets the font scale directly (clamped to [0.6, 2.6]). */
    void setFontScale(float scale) {
        fontScale = clampFontScale(scale);
        view.metrics.setTextSize(view.spToPx(EditorView.BASE_TEXT_SIZE_SP) * fontScale);
        // Re-clamp scroll offsets — content size changed.
        view.vOffset = EditorView.clamp(view.vOffset, 0, view.maxV());
        view.hOffset = EditorView.clamp(view.hOffset, 0, view.maxH());
        view.requestLayout();
        view.invalidate();
    }

public void applyPinchScale(float scaleFactor) {
        float newScale = clampFontScale(fontScale * scaleFactor);
        if (newScale == fontScale) return; // no-op (clamp saturé)

        // (1) Capture caret's CURRENT screen position (old metrics).
        float cx = 0f, cy = 0f;
        boolean hasCaret = (view.session != null && !view.session.isReadOnly());
        if (hasCaret) {
            int caretOffset = view.session.getSelection().start;
            float[] pos = view.caretScreenPos(caretOffset);
            cx = pos[0];
            cy = pos[1];
        }

        // (2) Apply the new scale (mirrors setFontScale's body but without
        // requestLayout — pinch fires continuously, requestLayout would
        // thrash the framework. EditorView's onMeasure will be re-run by
        // the next invalidate anyway).
        fontScale = newScale;
        view.metrics.setTextSize(view.spToPx(EditorView.BASE_TEXT_SIZE_SP) * fontScale);

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
            float[] newPos = view.caretScreenPos(view.session.getSelection().start);
            float dx = newPos[0] - cx;
            float dy = newPos[1] - cy;
            view.hOffset += dx;
            view.vOffset += dy;
        }

        // (4) Clamp to valid scroll range (post-scale).
        view.vOffset = EditorView.clamp(view.vOffset, 0, view.maxV());
        view.hOffset = EditorView.clamp(view.hOffset, 0, view.maxH());

        // (5) Cancel any in-flight caret glide — the caret is anchored
        // by our offset adjustment, a glide would override it.
        if (view.caretAnim != null) {
            view.caretAnim.onEditOrMove();
        }

        view.invalidate();
    }


    // v3.18.0: Convenience methods for font size +/- from Canvas icons.
    void increaseFontSize() {
        setFontScale(clampFontScale(fontScale * 1.15f));
    }

    void decreaseFontSize() {
        setFontScale(clampFontScale(fontScale / 1.15f));
    }
}
