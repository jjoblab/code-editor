package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Deux défauts liés aux plis et au zoom :
 * <ul>
 *   <li>maxV() comptait TOUTES les lignes du document sans soustraire les
 *       lignes masquées par les plis repliés — on pouvait scroller
 *       ~n×lineHeight dans le vide sous la dernière ligne visible ;</li>
 *   <li>le changement de taille de police (pinch-zoom, A+/A-) ne
 *       reconstruisait pas le modèle de word-wrap — les rangées restaient
 *       calculées pour l'ancienne taille (lignes débordant à droite).</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class ScrollMaxVAndZoomWrapTest {

    private static final String DOC = buildDoc();

    private static String buildDoc() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) sb.append("line ").append(i).append('\n');
        return sb.toString();
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        return view;
    }

    private static void injectMetrics(EditorView view) {
        try {
            java.lang.reflect.Field f;
            f = view.metrics.getClass().getDeclaredField("lineHeight");
            f.setAccessible(true); f.setFloat(view.metrics, 40f);
            f = view.metrics.getClass().getDeclaredField("charWidth");
            f.setAccessible(true); f.setFloat(view.metrics, 10f);
            f = view.metrics.getClass().getDeclaredField("padTop");
            f.setAccessible(true); f.setFloat(view.metrics, 20f);
            f = view.metrics.getClass().getDeclaredField("padLeft");
            f.setAccessible(true); f.setFloat(view.metrics, 5f);
            f = view.metrics.getClass().getDeclaredField("gutterWidth");
            f.setAccessible(true); f.setFloat(view.metrics, 70f);
            f = view.metrics.getClass().getDeclaredField("foldStripWidth");
            f.setAccessible(true); f.setFloat(view.metrics, 20f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    public void maxV_subtractsLinesHiddenByCollapsedFolds() {
        EditorView view = newView();
        float maxVBefore = view.maxV();
        assertTrue("contenu plus haut que le viewport", maxVBefore > 0);

        // Replie les lignes 0..49 (bornes en OFFSETS) : 50 lignes cachées.
        jo.codeeditor.document.EditorDocument doc = view.getSession().getDocument();
        int foldStart = doc.lineEnd(0);
        int foldEnd = doc.lineStart(50);
        view.getSession().applyCodeFolds(java.util.Collections.singletonList(
                new DiagnosticShift.FoldRegion(foldStart, foldEnd, "…", "block", true, false)));
        view.invalidate();

        assertEquals(50, view.totalHiddenLines());
        // 10 lignes visibles × 40 px + paddings ≈ 460 px < 1920 px de
        // viewport : le défilement vertical doit être ÉPUISÉ (0), pas
        // laisser ~50×40 px de vide scrollable sous la dernière ligne.
        assertEquals("maxV doit être épuisé quand le contenu repli tient"
                + " dans le viewport", 0f, view.maxV(), 0.01f);
    }

    @Test
    public void fontScaleChange_rebuildsWrapModel() {
        EditorView view = newView();
        // Une ligne très longue avec wrap actif.
        String longLine = new String(new char[400]).replace('\0', 'x');
        EditorSession s = new EditorSession(EditorDocument.of(longLine + "\n"));
        view.setSession(s);
        injectMetrics(view);
        view.setWordWrap(true);
        view.rebuildWrapModel();
        int rowsAtScale1 = view.rowsForDocLine(0);
        assertTrue("la longue ligne doit occuper plusieurs rangées (obtenu "
                + rowsAtScale1 + ")", rowsAtScale1 > 3);

        // Zoom ×2 : la taille de police (donc la largeur de colonne)
        // change — le modèle de wrap doit être RECONSTRUIT avec les
        // nouvelles métriques : rowsForDocLine doit rester cohérent avec
        // la géométrie courante (wrapRowsFor), pas garder les rangées de
        // l'ancienne taille.
        view.setFontScale(2.0f);
        int expected = view.wrapRowsFor(0, longLine.length()).rows;
        int actual = view.rowsForDocLine(0);
        assertEquals("le modèle de wrap doit refléter les métriques post-zoom"
                + " (attendu " + expected + ", le modèle garde " + actual + ")",
                expected, actual);
    }
}
