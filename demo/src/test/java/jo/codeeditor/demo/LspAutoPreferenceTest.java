package jo.codeeditor.demo;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

/**
 * La préférence « LSP automatique » (écran Réglages) est enfin
 * consommée par l'atelier : posée AVANT le lancement, elle branche le
 * mini serveur sur le premier fichier Java sans extra d'intention —
 * et une extinction MANUELLE garde la main sur la reconnexion
 * automatique jusqu'au prochain changement de la préférence.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class LspAutoPreferenceTest {

    @Test
    public void laPreferenceBrancheLeMoteurSansExtraDIntention() {
        // Préférence posée avant le lancement — l'écran Réglages écrit,
        // l'atelier relit.
        new EditorPrefs(RuntimeEnvironment.getApplication())
                .set(EditorPrefs.keyLspAuto(), true);
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue("La préférence « LSP automatique » doit"
                                + " brancher le moteur sur l'onglet Java"
                                + " par défaut (elle n'était jamais lue)",
                        activity.lsp.awaitConnected(15000));
                assertTrue(activity.lsp.isLspActive());
            });
        }
    }

    @Test
    public void lextinctionManuelleGardeLaMainSurLaPreference() {
        new EditorPrefs(RuntimeEnvironment.getApplication())
                .set(EditorPrefs.keyLspAuto(), true);
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue(activity.lsp.awaitConnected(15000));

                // Extinction manuelle (menu « Moteur LSP ») : la main
                // revient à l'utilisateur.
                activity.toggleLsp(false);
                assertFalse("Le moteur est éteint sur demande",
                        activity.lsp.isLspActive());

                // Détour par un autre onglet puis retour au Java : la
                // préférence encore posée NE rebranche PAS — la
                // suppression posée par l'extinction manuelle vit
                // jusqu'au prochain changement de préférence.
                activity.openSample("Python", "python");
                activity.selectFile(0);
                assertFalse("Pas de reconnexion automatique derrière"
                                + " une extinction manuelle",
                        activity.lsp.isLspActive());
            });
        }
    }
}
