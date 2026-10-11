package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Test de fumée : lance réellement MainActivity (cycle onCreate complet,
 * éditeur câblé, session Java par défaut) puis bascule vers Python et
 * vérifie que la session est remplacée proprement.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class MainActivitySmokeTest {

    @Test
    public void leLancementAfficheLEditeurAvecLEchantillonJava() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull("L'EditorView doit être câblé à une session",
                        activity.session);
                String text = activity.session.getText();
                assertTrue("L'échantillon Java doit être chargé",
                        text.contains("public final class Calculator"));
                assertEquals("java", activity.session.getLanguage());
            });
        }
    }

    @Test
    public void laBasculeDeLangageRemplaceLaSession() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                EditorSession avant = activity.session;
                activity.loadLanguage("Python", "python");
                EditorSession apres = activity.session;
                assertTrue("La session doit être remplacée", avant != apres);
                assertEquals("python", apres.getLanguage());
                assertTrue("L'échantillon Python doit être chargé",
                        apres.getText().contains("from dataclasses import"));
            });
        }
    }

    @Test
    public void lesDiagnosticsFacticesSontPublies() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                // L'analyseur factice doit avoir publié des diagnostics :
                // l'échantillon Java contient un TODO et un FIXME.
                assertTrue("Des diagnostics doivent être publiés",
                        !activity.session.getDiagnostics().isEmpty());
            });
        }
    }

    @Test
    public void leSchemeAppliqueLePresetPuisSeRetire() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                // Lot 4 #25 : la bascule Scheme applique le preset «
                // Crépuscule ambré » (sélection/caret/commentaires/fonctions
                // recolorés) puis null revient au thème brut.
                activity.applyColorScheme(true);
                assertNotNull("Le scheme doit être actif",
                        activity.editor.getColorScheme());
                assertTrue("Le scheme doit porter des overrides sombres",
                        activity.editor.getColorScheme()
                                .overrideCount(true) > 0);
                activity.applyColorScheme(false);
                assertNull("Scheme retiré = thème brut",
                        activity.editor.getColorScheme());
            });
        }
    }

    @Test
    public void letendreDeSelectionCascadeCurseurMotSyntagme() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                // Lot 4 #27 : curseur au milieu d'un identifiant → mot →
                // syntagme (span syntaxique ou crochets) — chaque cran
                // élargit strictement.
                String text = activity.session.getText();
                int anchor = text.indexOf("Calculator");
                assertTrue("L'échantillon Java contient Calculator", anchor >= 0);
                activity.session.setSelection(anchor + 4);

                assertTrue("Curseur → mot", activity.editor.expandSelection());
                jo.codeeditor.document.Selection mot =
                        activity.session.getSelection();
                assertFalse("Le mot reste une sélection (pas la fin)",
                        mot.isCursor());
                assertEquals("La sélection couvre l'identifiant",
                        "Calculator",
                        text.substring(mot.start, mot.end));

                assertTrue("Mot → syntagme/ligne",
                        activity.editor.expandSelection());
                jo.codeeditor.document.Selection elargi =
                        activity.session.getSelection();
                assertTrue("Chaque cran élargit strictement",
                        elargi.end - elargi.start
                                > mot.end - mot.start);
            });
        }
    }

    @Test
    public void lesDistinctionsFinesSontPoseesDansLesDeuxThemes() {
        // Lot 4 #26 : la démo pose les 8 attributs VIRTUELS dans ses
        // deux thèmes — sans eux, ces tokens retombent silencieusement
        // sur la couleur de leur parent.
        EditorTheme nuit = DemoTheme.emeraldNight();
        assertNotEquals("Javadoc ≠ commentaire",
                nuit.colorForToken(TokenType.COMMENT),
                nuit.colorForToken(TokenType.DOC_COMMENT));
        assertNotEquals("Contrôle de flux ≠ mot-clé",
                nuit.colorForToken(TokenType.KEYWORD),
                nuit.colorForToken(TokenType.KEYWORD_CONTROL));
        assertNotEquals("Modificateur ≠ mot-clé",
                nuit.colorForToken(TokenType.KEYWORD),
                nuit.colorForToken(TokenType.KEYWORD_MODIFIER));
        assertNotEquals("Caractère ≠ chaîne",
                nuit.colorForToken(TokenType.STRING),
                nuit.colorForToken(TokenType.CHAR));
        assertNotEquals("Chaîne brute ≠ chaîne",
                nuit.colorForToken(TokenType.STRING),
                nuit.colorForToken(TokenType.RAW_STRING));
        assertNotEquals("Espace de nom ≠ type",
                nuit.colorForToken(TokenType.TYPE),
                nuit.colorForToken(TokenType.NAMESPACE));
        assertNotEquals("Entité ≠ échappement",
                nuit.colorForToken(TokenType.ESCAPE),
                nuit.colorForToken(TokenType.ENTITY));
        assertNotEquals("Emphase ≠ annotation",
                nuit.colorForToken(TokenType.ANNOTATION),
                nuit.colorForToken(TokenType.EMPHASIS));

        EditorTheme jour = DemoTheme.emeraldDay();
        assertNotEquals("Jour : javadoc ≠ commentaire",
                jour.colorForToken(TokenType.COMMENT),
                jour.colorForToken(TokenType.DOC_COMMENT));
        assertNotEquals("Jour : contrôle ≠ mot-clé",
                jour.colorForToken(TokenType.KEYWORD),
                jour.colorForToken(TokenType.KEYWORD_CONTROL));
    }

    @Test
    public void laBasculeDeThemeSuitLeSchemeActif() {
        try (ActivityScenario<MainActivity> scenario =
                     ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                // Lot 4 #25 : avec un scheme actif, la bascule Thème
                // re-résout la cascade sur la nouvelle base (le mode du
                // scheme suit le thème clair/sombre).
                activity.applyColorScheme(true);
                activity.applyEditorTheme(true);
                assertFalse("Mode du scheme suit le thème clair",
                        activity.editor.isSchemeDarkMode());
                activity.applyEditorTheme(false);
                assertTrue("Mode du scheme suit le thème sombre",
                        activity.editor.isSchemeDarkMode());
            });
        }
    }
}
