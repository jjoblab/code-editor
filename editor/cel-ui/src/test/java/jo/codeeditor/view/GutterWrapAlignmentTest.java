package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Canvas;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import org.robolectric.shadows.ShadowCanvas;

import java.lang.reflect.Field;

import static org.junit.Assert.assertTrue;

/**
 * B11 + B20 (audit) : la gouttière doit s'aligner sur la géométrie
 * réelle du texte (consciente du word-wrap) et démarrer son itération à
 * la première ligne VISIBLE (O(visible)) au lieu de compter une rangée
 * par ligne depuis la ligne 0.
 *
 * <p>Avant : les numéros étaient désalignés dès la première ligne
 * wrappée (la gouttière comptait 1 rangée par ligne, le texte
 * rowsOf(ligne) rangées) et le chemin sensible aux replis parcourait
 * toutes les lignes du document à chaque frame.</p>
 *
 * <p>Modèle : 100 lignes de 150 caractères = 2 rangées wrap chacune à
 * ~100 colonnes par rangée. La ligne 5 commence à la rangée 10 (et NON
 * à la rangée 5) ; son numéro « 6 » doit être dessiné au Y réel de la
 * ligne. Avec vOffset calé sur la rangée 40 (ligne 20), la gouttière ne
 * doit dessiner AUCUN numéro de ligne au-dessus de ~19.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class GutterWrapAlignmentTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;

    private static String hundredWrappedLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            for (int c = 0; c < 150; c++) sb.append((char) ('a' + (i % 26)));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void injectMetrics(EditorView view) {
        try {
            setFloat(view.metrics, "lineHeight", LINE_H);
            setFloat(view.metrics, "charWidth", CHAR_W);
            setFloat(view.metrics, "padTop", PAD_TOP);
            setFloat(view.metrics, "padBottom", 0f);
            setFloat(view.metrics, "padLeft", CHAR_W * 0.5f);
            setFloat(view.metrics, "gutterWidth", CHAR_W * 7f);
            setFloat(view.metrics, "foldStripWidth", CHAR_W * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    /** Rend la vue et collecte (texte, y) de chaque drawText du shadow. */
    private static java.util.List<float[]> numberYs(EditorView view,
            java.util.List<String> texts) {
        Canvas canvas = new Canvas();
        view.draw(canvas);
        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        java.util.List<float[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < shadow.getTextHistoryCount(); i++) {
            ShadowCanvas.TextHistoryEvent e = shadow.getDrawnTextEvent(i);
            texts.add(e.text);
            out.add(new float[]{e.x, e.y});
        }
        return out;
    }

    @Test
    public void gutterNumbers_followWrapGeometry() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of(hundredWrappedLines()));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        view.setWordWrap(true);

        // La ligne 5 occupe les rangées 10-11 : son numéro « 6 » doit
        // être dessiné au Y réel (padTop + 10*lineHeight + 0.75*lineH),
        // pas au Y « 1 rangée par ligne » (padTop + 5*lineHeight + ...).
        float expectedY = view.docLineToY(5) + LINE_H * 0.75f;
        float wrongY = PAD_TOP + 5 * LINE_H + LINE_H * 0.75f;
        // Les deux Y diffèrent bien (précondition du test).
        assertTrue(Math.abs(expectedY - wrongY) > LINE_H);

        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.List<float[]> pos = numberYs(view, texts);
        boolean found = false;
        for (int i = 0; i < texts.size(); i++) {
            if ("6".equals(texts.get(i))) {
                assertTrue("le numéro 6 doit être au Y de la géométrie wrap ("
                        + pos.get(i)[1] + " ≠ attendu " + expectedY + ")",
                        Math.abs(pos.get(i)[1] - expectedY) < 1f);
                found = true;
            }
        }
        assertTrue("le numéro de la ligne 5 (« 6 ») doit être dessiné", found);
    }

    @Test
    public void gutterIteration_startsAtFirstVisibleLine() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of(hundredWrappedLines()));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        view.setWordWrap(true);

        // Scroll vertical : rangée 40 = milieu de la ligne 20. La
        // gouttière ne doit dessiner AUCUN numéro au-dessus de la ligne
        // ~19 (l'ancien chemin itérait depuis la ligne 0 en comptant
        // toutes les lignes au-dessus du viewport).
        view.vOffset = PAD_TOP + 40 * LINE_H;
        view.invalidate();

        java.util.List<String> texts = new java.util.ArrayList<>();
        numberYs(view, texts);
        int minLine = Integer.MAX_VALUE;
        int maxLine = Integer.MIN_VALUE;
        int count = 0;
        for (String t : texts) {
            if (t.matches("\\d{1,3}")) {
                int n = Integer.parseInt(t);
                if (t.length() <= 2 && (n >= 1 && n <= 100)) {
                    minLine = Math.min(minLine, n);
                    maxLine = Math.max(maxLine, n);
                    count++;
                }
            }
        }
        assertTrue("aucun numéro dessiné", count > 0);
        assertTrue("la gouttière dessine des numéros AU-DESSUS du viewport"
                + " (min = " + minLine + ", viewport commence à la ligne ~20)",
                minLine >= 19);
        // Viewport 1920/40 = 48 rangées → au plus ~25 lignes visibles
        // (2 rangées par ligne) + marges — jamais 100 numéros.
        assertTrue("trop de numéros dessinés (" + count
                + ") — l'itération doit être bornée au viewport",
                count <= 40);
    }
}
