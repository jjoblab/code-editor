package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.lang.reflect.Field;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * ★ v2.58 — Vérifie que le pinch-zoom ANCRE le caret à sa position
 * écran ET que la hauteur du caret (lineHeight) suit le font-scale.
 *
 * <p>Comportement attendu par l'utilisateur : « lorsque je fais un pince
 * pour zoomer, le caret devrait rester fixé à sa position et agrandir ou
 * réduire en même temps que le pinch zoom ».</p>
 *
 * <p><b>Stratégie de test</b> : Robolectric renvoie des FontMetrics nuls
 * (lineHeight=0, charWidth=0). Pour exercer le code d'ancrage, on INJECTE
 * un faux {@link EditorMetrics} qui simule le comportement des fontes
 * réelles (lineHeight ∝ textSize, charWidth ∝ textSize).</p>
 *
 * @author jo@Dev
 * @since v2.58
 */
@RunWith(RobolectricTestRunner.class)
public class EditorPinchZoomCaretAnchorTest {

    /**
     * Subclass of EditorMetrics that overrides setTextSize to produce
     * deterministic, scale-proportional values — bypassing Robolectric's
     * null FontMetrics.
     *
     * <p>Mirror the real EditorMetrics.setTextSize formula:
     * lineHeight = textSize * 1.5 (rounded up), charWidth = textSize * 0.6,
     * padTop = lineHeight * 0.5, padLeft = charWidth * 0.5,
     * gutterWidth = charWidth * 5 + charWidth * 2, foldStripWidth = charWidth * 2.
     * These mirror what real Android monospace fonts produce approximately,
     * so the test reflects actual user-perceived behavior.</p>
     */
    private static final class FakeMetrics extends EditorMetrics {
        @Override
        public void setTextSize(float sizePx) {
            // Call super to update the internal textSize field (read by
            // applyPinchScale indirectly). Then overwrite the derived
            // fields with our deterministic values.
            super.setTextSize(sizePx);
            try {
                float lh = (float) Math.ceil(sizePx * 1.5);
                float cw = sizePx * 0.6f;
                setField("lineHeight", lh);
                setField("charWidth", cw);
                setField("padTop", lh * 0.5f);
                setField("padLeft", cw * 0.5f);
                setField("padRight", cw * 0.5f);
                setField("padBottom", lh * 6f);
                setField("foldStripWidth", cw * 2f);
                setField("gutterWidth", cw * 5f + cw * 2f);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void setField(String name, float value) throws Exception {
            Field f = EditorMetrics.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setFloat(this, value);
        }
    }

    private EditorView newViewWithFakeMetrics(int docLineCount, int charsPerLine) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        // Replace the final `metrics` field with our FakeMetrics via
        // reflection. EditorView's constructor already created a real
        // EditorMetrics — we override it before any layout work happens.
        try {
            Field mf = EditorView.class.getDeclaredField("metrics");
            mf.setAccessible(true);
            // Clear final to allow modification.
            mf.set(view, new FakeMetrics());
        } catch (Exception e) {
            throw new RuntimeException("Cannot inject FakeMetrics", e);
        }
        // Build a multi-line document so the caret sits on a non-trivial
        // line — exercising the line * lineHeight component. Lines must
        // be long enough that maxH > 0 even after pinch-zoom (otherwise
        // hOffset clamping will fight our anchor adjustment).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docLineCount; i++) {
            for (int j = 0; j < charsPerLine; j++) {
                sb.append((char) ('a' + (j % 26)));
            }
            sb.append('\n');
        }
        EditorSession session = new EditorSession(EditorDocument.of(sb.toString()));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    @Test
    public void pinchZoomIn_keepsCaretAnchoredVertically() {
        // 100 lines so maxV > 0 even at scale 1.5 — without this, vOffset
        // clamps to 0 and the anchor test would fail trivially (no room
        // to scroll).
        EditorView view = newViewWithFakeMetrics(100, 80);
        String text = view.session.getText();
        int line5Start = nthLineStart(text, 50);
        int caretOffset = line5Start + 7;
        view.session.setSelection(caretOffset);

        // ── Capture caret's screen Y BEFORE the pinch ──
        float[] before = view.caretScreenPos(caretOffset);
        float cyBefore = before[1];

        // ── ACT : pinch zoom IN (×1.5) ──
        view.applyPinchScale(1.5f);

        // ── ASSERT : caret's screen Y is preserved ──
        float[] after = view.caretScreenPos(caretOffset);
        float cyAfter = after[1];
        assertEquals(
            "Le caret doit rester VERTICALEMENT ancré à sa position écran "
            + "pendant le pinch-zoom (cy_before=" + cyBefore + ", cy_after="
            + cyAfter + ").",
            cyBefore, cyAfter, 0.5f);
    }

    @Test
    public void pinchZoomIn_keepsCaretAnchoredHorizontally() {
        // 100 lines × 200 chars so maxH > 0 after pinch (otherwise hOffset
        // clamps to 0 and the test can't verify the anchor).
        EditorView view = newViewWithFakeMetrics(100, 200);
        // Disable word wrap to exercise the hOffset-adjustment branch
        // (caretScreenPos for word-wrap doesn't include hOffset, but for
        // non-wrap mode it does — so we need non-wrap mode to test the
        // horizontal anchor).
        view.wordWrap = false;

        String text = view.session.getText();
        int caretOffset = nthLineStart(text, 50) + 7;
        view.session.setSelection(caretOffset);

        // Pre-scroll horizontally so hOffset > 0 — otherwise the test
        // passes trivially because hOffset stays clamped to 0.
        view.hOffset = 100f;

        float[] before = view.caretScreenPos(caretOffset);
        float cxBefore = before[0];

        view.applyPinchScale(1.5f);

        float[] after = view.caretScreenPos(caretOffset);
        float cxAfter = after[0];
        assertEquals(
            "Le caret doit rester HORIZONTALEMENT ancré à sa position écran "
            + "pendant le pinch-zoom (cx_before=" + cxBefore + ", cx_after="
            + cxAfter + ").",
            cxBefore, cxAfter, 0.5f);
    }

    @Test
    public void pinchZoomOut_keepsCaretAnchored() {
        // Pinch OUT (÷1.5 = ×0.667). Test strategy:
        //   - Use a very long line so maxH is large at BOTH scales — avoids
        //     hOffset clamping post-pinch.
        //   - Pre-scroll hOffset to a value that will still be in range
        //     after the pinch-out (the post-pinch maxH will be SMALLER
        //     since charWidth shrinks, so we need a doc long enough that
        //     the new maxH still covers our vOffset).
        //   - Pre-scroll vOffset to a value still in range after pinch-out.
        //   - Use caret at col 30 so there's enough char-width contribution
        //     to exercise the dx compensation.
        EditorView view = newViewWithFakeMetrics(500, 200);
        view.wordWrap = false;

        // Use a moderate fontScale so post-pinch-out still has lots of room.
        view.setFontScale(1.5f);
        // Pre-scroll to mid-document. With 500 lines × ~31px lh at scale 1.5,
        // maxV ≈ 15600 - 1920 = 13680. So vOffset=5000 is well within range
        // both pre- and post-pinch.
        view.vOffset = 5000f;
        // Pre-scroll horizontally. With 200 chars × ~18.9px cw at scale 1.5,
        // maxH ≈ 3780 - 1080 = 2700. So hOffset=500 is well within range
        // both pre- and post-pinch.
        view.hOffset = 500f;

        String text = view.session.getText();
        int caretOffset = nthLineStart(text, 250) + 30;
        view.session.setSelection(caretOffset);

        // IMPORTANT : after setSelection, scrollCaretIntoView may have
        // adjusted hOffset/vOffset. We re-read the actual values and use
        // them as the "before" reference, NOT the values we just set.
        // The assertion is : caretScreenPos returns the SAME coordinates
        // before and after applyPinchScale.
        float[] before = view.caretScreenPos(caretOffset);

        // Pinch OUT : scale factor 0.667 (= 1/1.5).
        view.applyPinchScale(1.0f / 1.5f);

        float[] after = view.caretScreenPos(caretOffset);
        assertEquals(
            "Le caret X doit rester ancré pendant le pinch-OUT "
            + "(cx_before=" + before[0] + ", cx_after=" + after[0] + ").",
            before[0], after[0], 0.5f);
        assertEquals(
            "Le caret Y doit rester ancré pendant le pinch-OUT "
            + "(cy_before=" + before[1] + ", cy_after=" + after[1] + ").",
            before[1], after[1], 0.5f);
    }

    @Test
    public void pinchZoom_changesFontScale() {
        // Sanity check : applyPinchScale must actually change the fontScale
        // — otherwise the anchor tests above would pass trivially (no scale
        // change = no movement = no compensation needed).
        EditorView view = newViewWithFakeMetrics(100, 80);
        float scaleBefore = view.getFontScale();
        view.applyPinchScale(1.5f);
        float scaleAfter = view.getFontScale();
        assertTrue(
            "applyPinchScale(1.5f) doit multiplier le fontScale par 1.5 "
            + "(avant=" + scaleBefore + ", après=" + scaleAfter + ").",
            scaleAfter > scaleBefore);
    }

    @Test
    public void caretHeight_scalesWithFont_afterPinchZoomIn() {
        // Verify that the caret visually GROWS with the zoom. The caret's
        // height is `lineHeight` (derived from metrics), which scales with
        // textSize. We assert this indirectly: lineHeight after pinch > before.
        EditorView view = newViewWithFakeMetrics(100, 80);
        float lhBefore = view.metrics.getLineHeight();
        view.applyPinchScale(1.5f);
        float lhAfter = view.metrics.getLineHeight();
        assertTrue(
            "lineHeight doit croître avec le pinch-zoom IN (lineHeight est "
            + "dérivé de textSize via FontMetrics → utilisé par drawCaret "
            + "pour la hauteur du caret). Avant=" + lhBefore + ", après="
            + lhAfter + ".",
            lhAfter > lhBefore);
    }

    @Test
    public void pinchZoom_clampedAtMaxFontScale_stillKeepsCaretAnchored() {
        // Edge case : pinch past MAX_FONT_SCALE → fontScale clamps to MAX,
        // applyPinchScale early-returns (no-op). Caret position trivially
        // preserved, but we must verify the early-return doesn't break
        // anything.
        EditorView view = newViewWithFakeMetrics(100, 80);
        view.setFontScale(2.6f);  // MAX_FONT_SCALE per clampFontScale.
        String text = view.session.getText();
        int caretOffset = nthLineStart(text, 50) + 7;
        view.session.setSelection(caretOffset);

        float[] before = view.caretScreenPos(caretOffset);
        // Pinch IN past MAX — should clamp to MAX (no-op).
        view.applyPinchScale(2.0f);
        float[] after = view.caretScreenPos(caretOffset);
        assertEquals(before[0], after[0], 0.5f);
        assertEquals(before[1], after[1], 0.5f);
        // fontScale stayed at MAX (no-op).
        assertEquals(2.6f, view.getFontScale(), 0.001f);
    }

    @Test
    public void pinchZoom_clampedAtMinFontScale_stillKeepsCaretAnchored() {
        EditorView view = newViewWithFakeMetrics(100, 80);
        view.setFontScale(0.6f);  // MIN_FONT_SCALE per clampFontScale.
        String text = view.session.getText();
        int caretOffset = nthLineStart(text, 50) + 7;
        view.session.setSelection(caretOffset);

        float[] before = view.caretScreenPos(caretOffset);
        // Pinch OUT past MIN — should clamp to MIN (no-op).
        view.applyPinchScale(0.5f);
        float[] after = view.caretScreenPos(caretOffset);
        assertEquals(before[0], after[0], 0.5f);
        assertEquals(before[1], after[1], 0.5f);
        assertEquals(0.6f, view.getFontScale(), 0.001f);
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** Returns the offset of the start of the n-th line (0-indexed). */
    private static int nthLineStart(String text, int n) {
        int line = 0;
        int i = 0;
        while (line < n && i < text.length()) {
            if (text.charAt(i) == '\n') {
                line++;
            }
            i++;
        }
        return i;
    }
}
