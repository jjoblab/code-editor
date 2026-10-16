package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import jo.codeeditor.session.EditorSession;

/**
 * Test de fumée : lance réellement l'atelier (cycle onCreate complet,
 * éditeur câblé, onglet Java par défaut), vérifie la coquille
 * multi-fichiers (ouverture/sélection/fermeture d'onglets) et le
 * lancement du moteur LSP via l'extra d'intention de la carte d'accueil.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class EditorActivitySmokeTest {

    @Test
    public void leLancementAfficheLatelierAvecLEchantillonJava() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull("L'EditorView doit être câblé à une session",
                        activity.session);
                String text = activity.session.getText();
                assertTrue("L'échantillon Java doit être chargé",
                        text.contains("public final class Calculator"));
                assertEquals("java", activity.session.getLanguage());
                assertEquals("Un onglet ouvert au lancement", 1, activity.files.size());
                assertTrue("Le panneau Symboles voit l'outline du document",
                        activity.files.get(0).analyzer.outline(text).size() > 0);
            });
        }
    }

    @Test
    public void laCoquilleMultiFichiersOuvreSelectionneEtFerme() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                activity.openSample("Python", "python");
                assertEquals("Deux onglets ouverts", 2, activity.files.size());
                EditorSession python = activity.session;
                assertTrue("L'échantillon Python doit être chargé",
                        python.getText().contains("from dataclasses import"));
                assertEquals("python", python.getLanguage());

                activity.openSample("Java", "java");
                assertEquals("Toujours deux onglets (réutilisation)",
                        2, activity.files.size());
                assertTrue("La sélection revient à Java",
                        activity.session.getText().contains("Calculator"));

                activity.closeFile(1);
                assertEquals("L'onglet Python est fermé", 1, activity.files.size());
                assertTrue("Java reste l'onglet courant",
                        activity.session.getText().contains("Calculator"));
            });
        }
    }

    @Test
    public void lextraLspLanceLeMoteurLspSurJava() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(),
                EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_ID, "java");
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_LABEL, "Java");
        intent.putExtra(EditorActivity.EXTRA_LSP, true);
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertTrue("La connexion LSP doit être lancée (carte Accueil)",
                        activity.lsp.awaitConnected(15000));
            });
        }
    }
}
