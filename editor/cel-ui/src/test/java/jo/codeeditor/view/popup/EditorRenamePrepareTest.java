package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * Le popup de renommage consomme la pré-validation du serveur
 * (textDocument/prepareRename via EditorView.renamePrepareResolver) :
 *
 * <ul>
 *   <li>placeholder proposé → champ et état mis à jour (nom du serveur
 *       pré-rempli) ;</li>
 *   <li>réponse null (symbole non renommable) → REFUS PROPRE à
 *       l'ouverture (popup refermé immédiatement) au lieu d'un échec
 *       au moment de la validation ;</li>
 *   <li>sans pré-validation branchée → heuristique locale inchangée.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorRenamePrepareTest {

    private static final String DOC =
        "public class A {\n"
        + "    String s = foo;\n"
        + "}\n";

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    /** Attends que la condition devienne vraie (voie features). */
    /** A-06 : attente bornée, déléguée au helper du module. */
    private static boolean attends(long timeoutMs, java.util.function.BooleanSupplier cond) {
        return jo.codeeditor.AttenteBornee.jusquA(timeoutMs, cond);
    }

    @Test
    public void placeholderServi_champPrérempli() throws Exception {
        EditorView view = newView();
        view.renamePrepareResolver = (text, offset) -> "bar";
        view.getSession().setSelection(DOC.indexOf("foo") + 1);

        EditorRenamePopup popup = new EditorRenamePopup(view);
        popup.showRename();

        // État FINAL : le placeholder du serveur remonte au champ (la
        // montée asynchrone peut précéder la lecture — on n'asserte pas
        // l'état intermédiaire heuristique, racy par nature).
        assertTrue("le placeholder du serveur doit remonter au champ",
                attends(15000, () -> "bar".equals(popup.renameText)));
        assertTrue("le popup reste ouvert", popup.renameVisible);
    }

    @Test
    public void symboleNonRénombrable_refusPropre() throws Exception {
        EditorView view = newView();
        view.renamePrepareResolver = (text, offset) -> null;
        view.getSession().setSelection(DOC.indexOf("foo") + 1);

        EditorRenamePopup popup = new EditorRenamePopup(view);
        popup.showRename();

        // État FINAL : le refus du serveur referme le popup (le refus
        // peut arriver avant ou après la lecture — seul l'état final
        // compte).
        assertTrue("le popup doit se refermer (refus du serveur)",
                attends(15000, () -> !popup.renameVisible));
    }

    @Test
    public void sansPréValidation_heuristiqueLocaleInchangée() throws Exception {
        EditorView view = newView();
        view.renamePrepareResolver = null;
        view.getSession().setSelection(DOC.indexOf("foo") + 1);

        EditorRenamePopup popup = new EditorRenamePopup(view);
        popup.showRename();
        // Aucune requête : le popup reste ouvert avec le mot local.
        Thread.sleep(300);
        assertTrue(popup.renameVisible);
        assertEquals("foo", popup.renameText);
    }
}
