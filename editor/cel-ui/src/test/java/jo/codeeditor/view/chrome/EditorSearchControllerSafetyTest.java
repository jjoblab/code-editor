package jo.codeeditor.view.chrome;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.find.FindReplace;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * D-05 — le contrôleur de recherche expose les états du garde-fou
 * (budget temps, plafond de résultats) : une regex catastrophique sur
 * texte adverse rend la main sans figer le thread appelant (la
 * recherche du contrôleur tourne sur le thread UI — un ANR sans le
 * garde-fou), et le remplacement intégral avorte proprement.
 *
 * <p>Rouge avant : {@code refresh()} appelait
 * {@code FindReplace.findMatches} sans borne — regex catastrophique =
 * gel du thread principal (ANR après 5 s), aucun état exposé.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorSearchControllerSafetyTest {

    private EditorView newView(EditorSession session) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(session);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    @Test(timeout = 10_000)
    public void regexCatastrophique_étatTropCoûteuseExposé_sansGel() {
        String adverse = "a".repeat(60_000);
        EditorSession session = new EditorSession(EditorDocument.of(adverse));
        session.setLanguage("java");
        EditorView view = newView(session);
        EditorSearchController controller = new EditorSearchController(view);

        controller.setRegex(true);
        controller.setQuery("(a+)+b");
        long t0 = System.nanoTime();
        controller.refresh();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertTrue("l'état trop coûteuse est exposé au contrôleur",
                controller.isTooCostly());
        assertEquals("aucune correspondance fiable n'est affichée",
                0, controller.getMatchCount());
        assertTrue("le thread appelant (UI) n'est pas gelé (mesuré "
                + ms + " ms)", ms < 2_000);
        assertFalse(controller.isTruncated());
    }

    @Test(timeout = 10_000)
    public void regexSaine_étatsAbsents_comportementInchangé() {
        EditorSession session = new EditorSession(
                EditorDocument.of("int foo = 1;\nint bar = foo + foo;\n"));
        EditorView view = newView(session);
        EditorSearchController controller = new EditorSearchController(view);

        controller.setRegex(true);
        controller.setQuery("foo+");
        controller.refresh();

        assertEquals(3, controller.getMatchCount());
        assertFalse(controller.isTooCostly());
        assertFalse(controller.isTruncated());
    }

    @Test(timeout = 10_000)
    public void plafondDeRésultats_étatTronquéExposé() {
        String texte = "a".repeat(200_000);
        EditorSession session = new EditorSession(EditorDocument.of(texte));
        EditorView view = newView(session);
        EditorSearchController controller = new EditorSearchController(view);

        controller.setQuery("a");
        controller.refresh();

        assertEquals("la collecte est bornée au plafond",
                FindReplace.MAX_MATCHES, controller.getMatchCount());
        assertTrue("l'état tronqué est exposé au contrôleur",
                controller.isTruncated());
        assertFalse(controller.isTooCostly());
    }

    @Test(timeout = 10_000)
    public void remplacerToutAvecMotifCatastrophique_avorteSansMutation() {
        String adverse = "a".repeat(60_000);
        EditorSession session = new EditorSession(EditorDocument.of(adverse));
        EditorView view = newView(session);
        EditorSearchController controller = new EditorSearchController(view);

        controller.setRegex(true);
        controller.setQuery("(a+)+b");
        controller.refresh();

        int remplacés = controller.replaceAll("X");

        assertEquals("aucun remplacement (le document n'est pas muté à moitié)",
                0, remplacés);
        assertEquals("le texte d'origine est intact",
                adverse, session.getDocument().getText());
    }
}
