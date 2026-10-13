package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * UX-5 — verrouille la disparition des <b>zones de tap fantômes de
 * l'ancienne toolbar canvas</b> (A+, A−, ¶, ligatures).
 *
 * <p>Contexte : les icônes de toolbar ne sont plus dessinées sur le
 * canvas depuis la décomposition de l'ancienne barre (classe supprimée
 * en B15 ; les boutons vivent dans les barres d'hôte —
 * BreadcrumbBar.addAction), mais le
 * hit-test historique {@code EditorPopupAnchors.hitTestToolbarIcons}
 * restait branché dans {@code EditorTapResolver} : un tap en haut à
 * droite de l'éditeur déclenchait zoom/dézoom/bascules <b>sans aucun
 * retour visuel</b> (retour terrain post-3.58.0 : « des boutons
 * invisibles »).</p>
 *
 * <ul>
 *   <li><b>Zone A+</b> — le tap ne change PAS la taille de police ;</li>
 *   <li><b>Zone A−</b> — le tap ne change PAS la taille de police ;</li>
 *   <li><b>Zone ¶</b> — le tap ne bascule PAS les non-imprimables ;</li>
 *   <li><b>Zone ligatures</b> — le tap ne bascule PAS les ligatures ;</li>
 *   <li><b>Cascade normale</b> — le tap retombe dans le placement de
 *       caret (il n'est pas absorbé par une action invisible).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code SquiggleTapOpensAnchoredPopupTest}). Les zones fantômes sont
 * recalculées avec les mêmes constantes que l'ancien hit-test (22 dp
 * d'icône, 8 dp de marge, disposition droite→gauche [lig][¶][A−][A+]).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class EditorGhostToolbarTapTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Ancienne taille d'icône de toolbar (EditorPreviewController). */
    private static final float ICON_SIZE_DP = 22f;
    /** Ancienne marge (EditorPreviewController). */
    private static final float ICON_MARGIN_DP = 8f;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        session.setSelection(0);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
            setFloat(view.metrics, "gutterWidth", charWidth * 7f);
            setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    /**
     * Centre de l'ancienne zone fantôme {@code index} (0=A+, 1=A−,
     * 2=¶, 3=ligatures) — recalcule la géométrie de
     * {@code hitTestToolbarIcons} (droite→gauche : [lig][¶][A−][A+]).
     */
    private static float[] ghostZoneCenter(EditorView view, int index) {
        float density = view.getResources().getDisplayMetrics().density;
        float iconSize = ICON_SIZE_DP * density;
        float margin = ICON_MARGIN_DP * density;
        float iconY = margin;
        // Disposition de droite à gauche : [lig] [¶] [A-] [A+]
        float startX = view.getWidth() - margin;
        float ligX = startX - iconSize;
        float npX = ligX - iconSize - margin * 0.5f;
        float aMinusX = npX - iconSize - margin * 0.5f;
        float aPlusX = aMinusX - iconSize - margin * 0.5f;
        float[] xs = {aPlusX, aMinusX, npX, ligX};
        return new float[]{xs[index] + iconSize * 0.5f, iconY + iconSize * 0.5f};
    }

    @Test
    public void tapSurLaZoneAPlus_neChangePasLaTailleDePolice() {
        EditorView view = newView();
        assertEquals(1f, view.getFontScale(), 0f);

        float[] zone = ghostZoneCenter(view, 0);
        tap(view, zone[0], zone[1]);

        assertEquals("le tap sur la zone A+ fantôme ne doit plus zoomer",
                1f, view.getFontScale(), 0f);
    }

    @Test
    public void tapSurLaZoneAMoins_neChangePasLaTailleDePolice() {
        EditorView view = newView();
        assertEquals(1f, view.getFontScale(), 0f);

        float[] zone = ghostZoneCenter(view, 1);
        tap(view, zone[0], zone[1]);

        assertEquals("le tap sur la zone A− fantôme ne doit plus dézoomer",
                1f, view.getFontScale(), 0f);
    }

    @Test
    public void tapSurLaZoneNonImprimables_neBasculePas() {
        EditorView view = newView();
        assertFalse(view.showNonPrintable);

        float[] zone = ghostZoneCenter(view, 2);
        tap(view, zone[0], zone[1]);

        assertFalse("le tap sur la zone ¶ fantôme ne doit plus basculer",
                view.showNonPrintable);
    }

    @Test
    public void tapSurLaZoneLigatures_neBasculePas() {
        EditorView view = newView();
        assertFalse(view.fontLigatures);

        float[] zone = ghostZoneCenter(view, 3);
        tap(view, zone[0], zone[1]);

        assertFalse("le tap sur la zone ligatures fantôme ne doit plus basculer",
                view.fontLigatures);
    }

    @Test
    public void tapEnHautADroite_redevientUnPlacementDeCaretNormal() {
        // Une vue FRAÎCHE par tap : deux taps consécutifs sur la même vue
        // (boucle rapide) seraient comptés double-tap (extension de
        // sélection) — on isole chaque cascade au lieu de l'enchaîner.
        for (int i = 0; i < 4; i++) {
            EditorView view = newView();
            float[] zone = ghostZoneCenter(view, i);
            tap(view, zone[0], zone[1]);
            assertTrue("le tap " + i + " doit poser le caret (cascade normale)",
                    view.getSession().getSelection().isCursor());

            // Et l'état d'affichage reste vierge après le tap.
            assertEquals("le tap " + i + " ne doit pas zoomer",
                    1f, view.getFontScale(), 0f);
            assertFalse("le tap " + i + " ne doit pas basculer ¶",
                    view.showNonPrintable);
            assertFalse("le tap " + i + " ne doit pas basculer les ligatures",
                    view.fontLigatures);
        }
    }
}
