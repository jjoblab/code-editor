package jo.codeeditor.demo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.core.app.ActivityScenario;

import android.app.Application;

/**
 * 4.8.0 — l'aide de signature du moteur maison est bornée aux langages
 * à syntaxe d'appel.
 *
 * <p>Avant, le résolveur factice répondait « Signature de
 * démonstration » sur N'IMPORTE QUELLE parenthèse — y compris dans la
 * prose Markdown ou du texte brut. Le drapeau callSyntax du profil
 * (4.8.0) borne la réponse : le résolveur reste branché (c'est
 * l'analyseur qui décide, pas la vue) mais se tait hors langages de
 * code.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class SignatureGateDemoTest {

    @Test
    public void laideDeSignatureDuMoteurMaisonSeTaitSurLaProse() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                // Onglet Markdown : le résolveur factice reste branché
                // (pré-condition) mais ne répond plus.
                DemoAnalyzer markdown = new DemoAnalyzer(
                        activity.editor, activity.session, "markdown");
                markdown.attach();
                assertNotNull("pré-condition : le résolveur est branché",
                        activity.editor.signatureHelpResolver);
                assertNull("le résolveur factice se tait sur du Markdown",
                        activity.editor.signatureHelpResolver.resolve(
                                "voir (le détail", 12));

                // Java : l'aide factice répond toujours.
                DemoAnalyzer java = new DemoAnalyzer(
                        activity.editor, activity.session, "java");
                java.attach();
                assertNotNull("sur du Java, l'aide factice répond",
                        activity.editor.signatureHelpResolver.resolve(
                                "calc(1, 2", 8));
            });
        }
    }
}
