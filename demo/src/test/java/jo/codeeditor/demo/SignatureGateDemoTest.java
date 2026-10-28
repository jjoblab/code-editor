package jo.codeeditor.demo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import androidx.test.core.app.ActivityScenario;

import android.app.Application;

/**
 * 4.8.0 — l'aide de signature du moteur maison est bornée aux langages
 * à syntaxe d'appel. ★ 4.11.0 — le résolveur n'est plus FACTICE : il
 * sert les VRAIES signatures de la méthode appelée (bibliothèque
 * embarquée + méthodes déclarées dans le buffer) et se tient
 * honnêtement muet sur une méthode inconnue — c'est le repli local de
 * la vue (l'appel vivant) qui prend le relais, jamais une signature
 * inventée.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class SignatureGateDemoTest {

    @Test
    public void laideDeSignatureDuMoteurMaisonSeTaitSurLaProse() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                // Onglet Markdown : le résolveur reste branché
                // (pré-condition) mais ne répond plus.
                DemoAnalyzer markdown = new DemoAnalyzer(
                        activity.editor, activity.session, "markdown");
                markdown.attach();
                assertNotNull("pré-condition : le résolveur est branché",
                        activity.editor.signatureHelpResolver);
                assertNull("le résolveur se tait sur du Markdown",
                        activity.editor.signatureHelpResolver.resolve(
                                "voir (le détail", 12));

                // Java : le résolveur sert les VRAIES signatures.
                DemoAnalyzer java = new DemoAnalyzer(
                        activity.editor, activity.session, "java");
                java.attach();
                jo.codeeditor.completion.SignatureHelpController.SignatureHelp aide =
                        activity.editor.signatureHelpResolver.resolve(
                                "System.out.println(\"salut\", ", 28);
                assertNotNull("sur du Java, l'aide réelle répond", aide);
                assertEquals("println()", aide.signatures.get(0).label);
                assertEquals("println(String x)", aide.signatures.get(1).label);
                assertEquals("le paramètre actif suit la virgule frappée",
                        1, aide.activeParameter);

                // Une méthode INCONNUE n'invente plus de signature —
                // null : le repli local de la vue prend le relais.
                assertNull("une méthode inconnue ne fabrique rien",
                        activity.editor.signatureHelpResolver.resolve(
                                "calc(1, 2", 8));
            });
        }
    }
}
