package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * B25 (retour terrain) — « le menu overflow du toolbar de sélection
 * est trop large ». Avant : largeur = min(320dp, max(240dp, 90 % de
 * la vue)) → 320dp sur un écran de 360dp, la carte engloutissait
 * presque tout l'éditeur pour lister huit actions courtes. Après :
 * la largeur suit le CONTENU (libellé le plus large + budget
 * icône/gaps/paddings du painter), bornée [168dp, 320dp] et clampée
 * à la vue moins les marges.
 *
 * <p>NOTE Robolectric : fontes legacy → measureText ≈ 0 ; le contenu
 * retombe donc au plancher 168dp en test — exactement le
 * comportement attendu pour des libellés courts. Le plafond 320dp
 * (libellés longs, ellipsés par le painter) reste couvert par
 * {@code EditorNavMenuTest.metrics_anchoredBelowCaretLine…}.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class NavMenuOverflowWidthTest {

    private static final String DOC = "colors {\n  bg: #fff;\n}\n";

    private EditorView newView(int widthPx) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(widthPx, 1920);
        view.layout(0, 0, widthPx, 1920);
        TestMetrics.injecter(view, 40f, 10f);
        return view;
    }

    private static void openOverflow(EditorView view, String... labels) {
        for (String label : labels) {
            view.popupManager.selActions.overflow.add(
                    new SelectionAction("id." + label, label, () -> { }));
        }
        view.navMenuVisible = true;
        view.navMenuLine = 1;
        view.navMenuCaretOffset = DOC.length() - 2;
    }

    @Test
    public void débordement_actionsCourtes_carteSerrée() {
        EditorView view = newView(360);
        openOverflow(view, "Partager", "Dupliquer", "Commenter",
                "Majuscules", "Déplacer ↑", "Déplacer ↓",
                "Déplacer le bloc", "Point d'arrêt");
        float density = view.getResources().getDisplayMetrics().density;

        float[] m = view.navMenuMetrics();
        assertNotNull(m);
        assertTrue("huit actions courtes → largeur serrée (≤ 240dp, "
                        + "l'ancienne largeur minimale)",
                m[2] <= 240f * density - 1f);
        assertTrue("plancher 168dp respecté",
                m[2] >= EditorView.NAV_MENU_MIN_WIDTH_DP * density - 1f);
        assertTrue("la carte tient dans la vue",
                m[0] + m[2] <= view.getWidth()
                        + 2 * EditorView.NAV_MENU_MARGIN_DP * density);
    }

    @Test
    public void vueÉtroite_largeurClampéeÀLaVue() {
        EditorView view = newView(150);
        openOverflow(view, "Partager", "Dupliquer");
        float density = view.getResources().getDisplayMetrics().density;

        float[] m = view.navMenuMetrics();
        assertNotNull(m);
        assertTrue("vue plus étroite que le plancher → clamp à vue − marges",
                m[2] <= 150f - 2 * EditorView.NAV_MENU_MARGIN_DP * density + 1f);
        assertTrue("la carte reste dans les bords",
                m[0] >= 0f && m[0] + m[2] <= view.getWidth() + 1f);
    }

    @Test
    public void menuVide_plancherPasRienTrouvé() {
        EditorView view = newView(360);
        view.navMenuVisible = true;
        view.navMenuLine = 1;
        view.navMenuCaretOffset = DOC.length() - 2;
        float density = view.getResources().getDisplayMetrics().density;

        float[] m = view.navMenuMetrics();
        assertNotNull(m);
        assertTrue("« Nothing found » garde le plancher",
                m[2] >= EditorView.NAV_MENU_MIN_WIDTH_DP * density - 1f);
    }
}
