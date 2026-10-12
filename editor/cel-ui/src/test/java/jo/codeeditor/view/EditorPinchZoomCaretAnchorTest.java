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
 * Vérifie que le pinch-zoom ANCRE le caret à sa position
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
 */
@RunWith(RobolectricTestRunner.class)
public class EditorPinchZoomCaretAnchorTest {

    /**
     * Sous-classe d'EditorMetrics qui surcharge setTextSize pour produire
     * des valeurs déterministes, proportionnelles à l'échelle — contourne
     * les FontMetrics nuls de Robolectric.
     *
     * <p>Reprend la formule réelle d'EditorMetrics.setTextSize :
     * lineHeight = textSize * 1.5 (arrondi au supérieur), charWidth =
     * textSize * 0.6, padTop = lineHeight * 0.5, padLeft = charWidth * 0.5,
     * gutterWidth = charWidth * 5 + charWidth * 2, foldStripWidth =
     * charWidth * 2. Ces valeurs reflètent approximativement ce que
     * produisent les fontes monospace réelles d'Android, donc le test
     * reflète le comportement perçu par l'utilisateur.</p>
     */
    private static final class FakeMetrics extends EditorMetrics {
        @Override
        public void setTextSize(float sizePx) {
            // Appelle super pour mettre à jour le champ textSize interne (lu
            // indirectement par applyPinchScale). Puis écrase les champs
            // dérivés avec nos valeurs déterministes.
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
        // Remplace le champ final `metrics` par notre FakeMetrics via
        // réflexion. Le constructeur d'EditorView a déjà créé un vrai
        // EditorMetrics — on le remplace avant tout travail de layout.
        try {
            Field mf = EditorView.class.getDeclaredField("metrics");
            mf.setAccessible(true);
            // Neutralise final pour permettre la modification.
            mf.set(view, new FakeMetrics());
        } catch (Exception e) {
            throw new RuntimeException("Cannot inject FakeMetrics", e);
        }
        // Construit un document multi-lignes pour que le caret soit sur une
        // ligne non triviale — exerce la composante ligne * lineHeight. Les
        // lignes doivent être assez longues pour que maxH > 0 même après
        // pinch-zoom (sinon le clamp de hOffset contrerait notre ajustement
        // d'ancrage).
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
        // 100 lignes pour que maxV > 0 même à l'échelle 1.5 — sinon vOffset
        // clampe à 0 et le test d'ancrage passerait trivialement (aucune
        // marge de scroll).
        EditorView view = newViewWithFakeMetrics(100, 80);
        String text = view.session.getText();
        int line5Start = nthLineStart(text, 50);
        int caretOffset = line5Start + 7;
        view.session.setSelection(caretOffset);

        // ── Capture le Y écran du caret AVANT le pinch ──
        float[] before = view.caretScreenPos(caretOffset);
        float cyBefore = before[1];

        // ── ACTE : pinch zoom IN (×1.5) ──
        view.applyPinchScale(1.5f);

        // ── ASSERT : le Y écran du caret est préservé ──
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
        // 100 lignes × 200 caractères pour que maxH > 0 après le pinch
        // (sinon hOffset clampe à 0 et le test ne peut pas vérifier l'ancrage).
        EditorView view = newViewWithFakeMetrics(100, 200);
        // Désactive le word wrap pour exercer la branche d'ajustement de
        // hOffset (caretScreenPos en mode word-wrap n'inclut pas hOffset,
        // mais en mode non-wrap oui — il faut donc le mode non-wrap pour
        // tester l'ancrage horizontal).
        view.wordWrap = false;

        String text = view.session.getText();
        int caretOffset = nthLineStart(text, 50) + 7;
        view.session.setSelection(caretOffset);

        // Pré-scrolle horizontalement pour que hOffset > 0 — sinon le test
        // passe trivialement car hOffset reste clampe à 0.
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
        // Pinch OUT (÷1.5 = ×0.667). Stratégie de test :
        //   - Une très longue ligne pour que maxH soit grand aux DEUX
        //     échelles — évite le clamp de hOffset post-pinch.
        //   - Pré-scroll de hOffset à une valeur encore dans les bornes
        //     après le pinch-out (le maxH post-pinch sera PLUS PETIT car
        //     charWidth rétrécit — il faut un document assez long pour que
        //     le nouveau maxH couvre encore notre vOffset).
        //   - Pré-scroll de vOffset à une valeur encore dans les bornes
        //     après le pinch-out.
        //   - Caret à la col 30 pour une contribution de largeur de
        //     caractère suffisante et exercer la compensation dx.
        EditorView view = newViewWithFakeMetrics(500, 200);
        view.wordWrap = false;

        // Un fontScale modéré pour que le post-pinch-out garde beaucoup de
        // marge.
        view.setFontScale(1.5f);
        // Pré-scroll au milieu du document. Avec 500 lignes × ~31px de lh
        // à l'échelle 1.5, maxV ≈ 15600 - 1920 = 13680. vOffset=5000 est
        // donc bien dans les bornes avant et après le pinch.
        view.vOffset = 5000f;
        // Pré-scroll horizontal. Avec 200 caractères × ~18.9px de cw à
        // l'échelle 1.5, maxH ≈ 3780 - 1080 = 2700. hOffset=500 est donc
        // bien dans les bornes avant et après le pinch.
        view.hOffset = 500f;

        String text = view.session.getText();
        int caretOffset = nthLineStart(text, 250) + 30;
        view.session.setSelection(caretOffset);

        // IMPORTANT : après setSelection, scrollCaretIntoView a pu ajuster
        // hOffset/vOffset. On relit les valeurs réelles et on les utilise
        // comme référence « avant », PAS les valeurs qu'on vient de poser.
        // L'assertion est : caretScreenPos renvoie les MÊMES coordonnées
        // avant et après applyPinchScale.
        float[] before = view.caretScreenPos(caretOffset);

        // Pinch OUT : facteur d'échelle 0.667 (= 1/1.5).
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
        // Vérification de santé : applyPinchScale doit vraiment changer le
        // fontScale — sinon les tests d'ancrage ci-dessus passeraient
        // trivialement (pas de changement d'échelle = pas de mouvement =
        // aucune compensation requise).
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
        // Vérifie que le caret grossit visuellement avec le zoom. La hauteur
        // du caret est `lineHeight` (dérivée des métriques), qui suit
        // textSize. On l'affirme indirectement : lineHeight après pinch > avant.
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
        // Cas limite : pinch au-delà de MAX_FONT_SCALE → fontScale clampe à
        // MAX, applyPinchScale retourne tôt (no-op). Position du caret
        // trivialement préservée, mais il faut vérifier que ce retour
        // anticipé ne casse rien.
        EditorView view = newViewWithFakeMetrics(100, 80);
        view.setFontScale(2.6f);  // MAX_FONT_SCALE selon clampFontScale.
        String text = view.session.getText();
        int caretOffset = nthLineStart(text, 50) + 7;
        view.session.setSelection(caretOffset);

        float[] before = view.caretScreenPos(caretOffset);
        // Pinch IN au-delà de MAX — doit clamper à MAX (no-op).
        view.applyPinchScale(2.0f);
        float[] after = view.caretScreenPos(caretOffset);
        assertEquals(before[0], after[0], 0.5f);
        assertEquals(before[1], after[1], 0.5f);
        // fontScale resté à MAX (no-op).
        assertEquals(2.6f, view.getFontScale(), 0.001f);
    }

    @Test
    public void pinchZoom_clampedAtMinFontScale_stillKeepsCaretAnchored() {
        EditorView view = newViewWithFakeMetrics(100, 80);
        view.setFontScale(0.6f);  // MIN_FONT_SCALE selon clampFontScale.
        String text = view.session.getText();
        int caretOffset = nthLineStart(text, 50) + 7;
        view.session.setSelection(caretOffset);

        float[] before = view.caretScreenPos(caretOffset);
        // Pinch OUT au-delà de MIN — doit clamper à MIN (no-op).
        view.applyPinchScale(0.5f);
        float[] after = view.caretScreenPos(caretOffset);
        assertEquals(before[0], after[0], 0.5f);
        assertEquals(before[1], after[1], 0.5f);
        assertEquals(0.6f, view.getFontScale(), 0.001f);
    }

    // ── Aides ───────────────────────────────────────────────────────

    /** Renvoie l'offset du début de la n-ième ligne (0-indexé). */
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
