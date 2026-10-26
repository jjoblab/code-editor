package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Les bascules d'affichage de l'atelier relayées jusqu'à l'éditeur :
 * bande d'erreurs (B11) et sélecteur de couleur au code hex (B26) —
 * deux réglages publics de la vue qui n'étaient pas démontrés.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class EditorTogglesDemoTest {

    @Test
    public void laBandeDErreursSeBasculeDepuisLatelier() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                // Activée par défaut (parité Android Studio).
                assertTrue("Bande d'erreurs visible par défaut",
                        activity.editor.isErrorStripeEnabled());

                activity.applyErrorStripe(false);
                assertFalse("Le menu cache la bande d'erreurs",
                        activity.editor.isErrorStripeEnabled());

                activity.applyErrorStripe(true);
                assertTrue("Et la remontre",
                        activity.editor.isErrorStripeEnabled());
            });
        }
    }

    @Test
    public void leSelecteurDeCouleurSeBasculeDepuisLatelier() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue("Sélecteur de couleur actif par défaut",
                        activity.editor.isColorPickerEnabled());

                activity.applyColorPicker(false);
                assertFalse("Le menu coupe le sélecteur — un tap sur un"
                                + " code hex replace le caret (historique)",
                        activity.editor.isColorPickerEnabled());

                activity.applyColorPicker(true);
                assertTrue("Et le réactive",
                        activity.editor.isColorPickerEnabled());
            });
        }
    }

    @Test
    public void lApprentissageDuClassementSeBasculeDepuisLatelier() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                // Activé par défaut (4.5.0).
                assertTrue("Apprentissage actif par défaut",
                        activity.isRankingLearning());

                activity.applyRankingLearning(false);
                assertFalse("Le menu coupe l'apprentissage",
                        activity.isRankingLearning());

                // La purge ne change pas la bascule, juste l'historique.
                activity.clearRankingLearning();
                assertFalse(activity.isRankingLearning());

                activity.applyRankingLearning(true);
                assertTrue("Et le réactive",
                        activity.isRankingLearning());
            });
        }
    }

    @Test
    public void leBancGabaritsArmeTousLesOnglets() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertFalse("Banc gabarits éteint par défaut",
                        activity.isTemplatesBench());

                activity.applyTemplatesBench(true);
                assertTrue(activity.isTemplatesBench());
                // L'analyseur de l'onglet courant est armé, et le
                // contexte de macros posé sur sa session.
                assertFalse(activity.files.isEmpty());
                assertTrue(activity.files.get(0).analyzer.isTemplatesBench());
                assertTrue(activity.files.get(0).session.snippets.getMacroContext() != null);

                activity.applyTemplatesBench(false);
                assertFalse(activity.isTemplatesBench());
                assertFalse(activity.files.get(0).analyzer.isTemplatesBench());
                assertTrue(activity.files.get(0).session.snippets.getMacroContext() == null);
            });
        }
    }

    @Test
    public void leModeGrosFichersSePoseDepuisLatelier() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertEquals("AUTO par défaut",
                        jo.codeeditor.view.EditorView.LargeFileMode.AUTO,
                        activity.getLargeFileMode());

                activity.applyLargeFileMode(jo.codeeditor.view.EditorView.LargeFileMode.ON);
                assertEquals(jo.codeeditor.view.EditorView.LargeFileMode.ON,
                        activity.getLargeFileMode());

                activity.applyLargeFileMode(jo.codeeditor.view.EditorView.LargeFileMode.OFF);
                assertEquals(jo.codeeditor.view.EditorView.LargeFileMode.OFF,
                        activity.getLargeFileMode());

                activity.applyLargeFileMode(jo.codeeditor.view.EditorView.LargeFileMode.AUTO);
                assertEquals(jo.codeeditor.view.EditorView.LargeFileMode.AUTO,
                        activity.getLargeFileMode());
            });
        }
    }

    @Test
    public void laBarreDeTouchesSeMontreDepuisLatelier() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                // Cachée par défaut — strictement additif.
                assertFalse("Barre de touches cachée par défaut",
                        activity.isTouchBarVisible());
                assertEquals(android.view.View.GONE, activity.touchBar.getVisibility());

                activity.applyTouchBar(true);
                assertTrue(activity.isTouchBarVisible());
                assertEquals(android.view.View.VISIBLE, activity.touchBar.getVisibility());
                // La rangée par défaut est posée au bind.
                assertEquals(5, activity.touchBar.getActions().size());

                activity.applyTouchBar(false);
                assertFalse(activity.isTouchBarVisible());
                assertEquals(android.view.View.GONE, activity.touchBar.getVisibility());
            });
        }
    }
}
