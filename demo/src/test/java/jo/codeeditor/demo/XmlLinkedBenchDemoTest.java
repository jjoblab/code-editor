package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import jo.codeeditor.document.Selection;

/**
 * Banc XML lié (4.5.1) : l'échantillon « XML lié » vit dans le tiroir
 * sur le langage éditeur xml (le renommage lié est gardé par la
 * famille XML du profil, 4.5.0) — la démonstration de bout en bout :
 * une frappe dans le nom OUVRANT renomme AUSSI la fermante, et une
 * seule annulation retire les deux insertions.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class XmlLinkedBenchDemoTest {

    @Test
    public void léchantillonPorteDesPairesPropres() {
        String sample = DemoSamples.sampleFor("xml-linked");
        assertTrue("Paire LinearLayout présente",
                sample.contains("<LinearLayout") && sample.contains("</LinearLayout>"));
        assertTrue("Mode d'emploi embarqué",
                sample.contains("BANC XML LIÉ"));
        assertEquals("Le tiroir expose l'échantillon",
                "xml-linked", DemoSamples.LANGUAGES.get("XML lié"));
    }

    @Test
    public void laFrappeDansLeNomOuvrantRenommeLaFermante() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(),
                EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_ID, "xml-linked");
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_LABEL, "XML lié");
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertEquals("L'échantillon vit sur le profil xml (famille XML)",
                        "xml", activity.session.getLanguage());

                // Caret juste après « LinearL » dans le nom ouvrant.
                // Un « Z » diverge du texte d'origine (un « a » ne se
                // verrait pas : LinearLayout contient déjà LinearLa).
                String text = activity.session.getText();
                int caret = text.indexOf("<LinearLayout") + "<LinearL".length();
                activity.session.setSelection(Selection.cursor(caret));

                activity.session.typeChar('Z');

                String apres = activity.session.getText();
                assertTrue("L'ouvrante reçoit la frappe",
                        apres.contains("<LinearLZayout"));
                assertTrue("…et la fermante suit SANS nouvelle frappe",
                        apres.contains("</LinearLZayout"));

                // Une seule étape d'annulation retire les DEUX insertions.
                assertTrue("Le lot d'annulation existe", activity.session.undo());
                assertFalse("Retour franc : plus de LinearLZ",
                        activity.session.getText().contains("LinearLZ"));
            });
        }
    }
}
