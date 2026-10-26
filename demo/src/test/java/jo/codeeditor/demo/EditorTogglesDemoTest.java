package jo.codeeditor.demo;

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
}
