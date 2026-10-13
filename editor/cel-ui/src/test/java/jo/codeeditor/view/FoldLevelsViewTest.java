package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.FoldDetector;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B14 (P1-5) — intégration VUE des plis par niveau : l'index mémoïsé
 * de la vue ({@code isLineFoldedCached}, rebuild sur la révision de
 * plis) suit les commandes de session « Replier au niveau N » / «
 * tout déplier » — la cascade foldRev → invalidation → lignes
 * masquées sans re-pose de session.
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class FoldLevelsViewTest {

    // Même document que CollapseFoldLevelsTest : classe (niveau 1) >
    // méthode m (niveau 2) > if (niveau 3).
    private static final String DOC =
        "class A {\n"
        + "    int x = 0;\n"
        + "    void m() {\n"
        + "        if (x > 0) {\n"
        + "            x--;\n"
        + "        }\n"
        + "    }\n"
        + "    void n() {\n"
        + "    }\n"
        + "}\n";

    private EditorView newViewAvecPlis() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        session.setFoldRegions(FoldDetector.detect(DOC, "java"));
        return view;
    }

    @Test
    public void replierNiveau1_lIndexDeVueSuitLaRévision() {
        EditorView view = newViewAvecPlis();
        EditorSession session = view.getSession();
        // Avant : rien de masqué.
        assertFalse(view.isLineFoldedCached(1));
        // « Replier au niveau 1 » → la révision de plis change → l'index
        // mémoïsé se RECONSTRUIT sans re-pose de session.
        session.collapseFoldsAtLevel(1);
        assertTrue("l'index de vue suit le repli par niveau (ligne 1 "
                + "masquée par la classe repliée)",
                view.isLineFoldedCached(1));
        assertTrue(view.isLineFoldedCached(4));
    }

    @Test
    public void replierNiveau2_laLigneHorsMéthodeResteVisible() {
        EditorView view = newViewAvecPlis();
        EditorSession session = view.getSession();
        session.collapseFoldsAtLevel(2);
        assertTrue("ligne interne de m masquée", view.isLineFoldedCached(4));
        assertFalse("ligne 1 hors m : visible (classe ouverte)",
                view.isLineFoldedCached(1));
    }

    @Test
    public void toutDéplier_lIndexRevientÀVide() {
        EditorView view = newViewAvecPlis();
        EditorSession session = view.getSession();
        session.collapseAllFolds();
        assertTrue(view.isLineFoldedCached(1));
        session.expandAllFolds();
        assertFalse(view.isLineFoldedCached(1));
        assertFalse(view.isLineFoldedCached(4));
    }
}
