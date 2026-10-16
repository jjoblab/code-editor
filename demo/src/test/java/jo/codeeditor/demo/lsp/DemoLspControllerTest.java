package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.view.EditorView;

/**
 * La chaîne LSP COMPLÈTE de la démo, de bout en bout :
 * {@link DemoLspController} → {@code LspProject} →
 * {@link MiniJavaServerDefinition} → pont in-process →
 * {@link MiniJavaLanguageServer} → publication JSON-RPC →
 * {@code LspDiagnosticsManager} → la SESSION de l'éditeur.
 *
 * <p>La vue n'est PAS attachée à une fenêtre : la publication s'exécute
 * en ligne (même pattern que les tests {@code cel-lsp}) — aucun looper
 * à faire avancer.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class DemoLspControllerTest {

    private static final String CODE_CASSÉ =
            "public class Atelier {\n"
            + "    public void casse() {\n"
            + "        int ouverture = 1\n"
            + "    }\n"
            + "}\n";

    private static boolean attendre(long timeoutMs, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long fin = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < fin) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    public void laChaineComplete_pousseLesDiagnosticsDuServeurDansLaSession()
            throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        DemoLspController controller = new DemoLspController(app);

        EditorView view = new EditorView(app);
        EditorSession session = new EditorSession(
                EditorDocument.of(CODE_CASSÉ));
        view.setSession(session);

        final List<DiagnosticShift.Diagnostic> reçus = new CopyOnWriteArrayList<>();
        view.setOnDiagnosticsPublishedListener(reçus::addAll);

        assertTrue("La connexion doit être lancée",
                controller.connect(view, session));
        assertTrue("Le mini serveur doit se connecter (initialize + didOpen)",
                controller.awaitConnected(15000));

        assertTrue("Les diagnostics du serveur doivent arriver en session",
                attendre(15000, () -> !reçus.isEmpty()));
        assertTrue("Le diagnostic point-virgule manquant doit être là",
                reçus.stream().anyMatch(d -> d.message.contains(";")));

        // Les diagnostics convertis restent accessibles au contrôleur
        // (panneau Diagnostics de l'atelier).
        assertTrue(controller.diagnostics().size() >= 1);

        // Déconnexion : didClose (le serveur vide ses diagnostics) puis
        // purge locale — la session ne garde rien de périmé.
        controller.disconnect(session);
        assertTrue("La session est purgée après déconnexion",
                session.getDiagnostics().isEmpty());
        assertEquals("Le contrôleur revient à OFF",
                DemoLspController.State.OFF, controller.getState());

        controller.shutdown();
    }

    @Test
    public void deconnexionSansConnexion_estInoffensive() {
        Application app = RuntimeEnvironment.getApplication();
        DemoLspController controller = new DemoLspController(app);
        EditorSession session = new EditorSession(
                EditorDocument.of("class A {\n}\n"));
        controller.disconnect(session);
        assertEquals(DemoLspController.State.OFF, controller.getState());
        controller.shutdown();
    }
}
