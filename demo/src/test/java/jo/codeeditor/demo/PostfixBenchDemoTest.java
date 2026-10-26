package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Intent;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Banc postfix (4.5.1) : l'échantillon « Postfix (Java) » vit dans le
 * tiroir sur le langage éditeur java — le postfix est une donnée du
 * PROFIL de langage (table additive + défauts de famille), pas de
 * l'analyseur factice — et le dialogue du banc liste la table du
 * profil courant (.sout pour Java) avec le mode d'emploi.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class PostfixBenchDemoTest {

    @Test
    public void léchantillonPostfixGuideLesEssais() {
        String sample = DemoSamples.sampleFor("postfix-java");
        assertTrue("L'échantillon porte le mode d'emploi (.sout)",
                sample.contains(".sout"));
        assertTrue("…des expressions prêtes à postfixer",
                sample.contains("resultat") && sample.contains("liste"));
        assertTrue("…et les défauts C_LIKE documentés",
                sample.contains(".notnull"));
        assertEquals("Le tiroir expose l'échantillon",
                "postfix-java", DemoSamples.LANGUAGES.get("Postfix (Java)"));
    }

    @Test
    public void laTableDuProfilJavaListeSout() {
        String text = EditorActivity.postfixBenchText("java");
        assertTrue("La table du profil java cite .sout",
                text.contains("sout"));
        assertTrue("…avec son gabarit exact",
                text.contains("System.out.println"));
        assertTrue("Les défauts C_LIKE sont documentés",
                text.contains(".if") && text.contains(".while"));
        assertTrue("Le mode d'emploi est embarqué",
                text.contains("validez"));
    }

    @Test
    public void unLangageSansProfilResteLisible() {
        String text = EditorActivity.postfixBenchText("langage-inconnu");
        assertTrue("Aucune table déclarée, sans plantage",
                text.contains("aucune entrée"));
        assertTrue("La famille sans défaut l'explique",
                text.contains("aucun"));
    }

    @Test
    public void louvertureDeLéchantillonRègleLeLangageJava() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(),
                EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_ID, "postfix-java");
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_LABEL, "Postfix (Java)");
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                assertEquals("L'échantillon postfix vit sur le profil java",
                        "java", activity.session.getLanguage());
                assertTrue("Le contenu est celui du banc",
                        activity.session.getText().contains("BancPostfix"));
            });
        }
    }
}
