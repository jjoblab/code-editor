package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Application;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.RobolectricTestRunner;

import jo.codeeditor.view.chrome.EditorTheme;

/**
 * L'identité de thème de l'atelier survit aux changements d'onglet :
 * la base émeraude (claire ou sombre) est reposée par la voie du
 * scheme à chaque sélection — l'ancien setTheme(light()) écrasait la
 * base par le thème clair standard en contournant la cascade.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class ThemeIdentityDemoTest {

    @Test
    public void lidentiteEmeraudeClaireSurvitAuxChangementsDonglet() {
        // Thème clair demandé dans les Réglages, posé avant le lancement.
        new EditorPrefs(RuntimeEnvironment.getApplication())
                .set(EditorPrefs.keyLight(), true);
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                // Base émeraude claire au lancement (applyPrefsToEditor).
                assertEquals("Base émeraude claire au lancement",
                        DemoTheme.emeraldDay().editorBg,
                        activity.editor.getTheme().editorBg);

                // Bascule d'onglet : la base est REPOSÉE — l'ancien
                // setTheme(light()) la remplaçait par le thème clair
                // standard (fond 0xFFFFFFFF).
                activity.openSample("Python", "python");
                assertEquals("La base émeraude survit à la bascule d'onglet",
                        DemoTheme.emeraldDay().editorBg,
                        activity.editor.getTheme().editorBg);
                assertNotEquals("Le thème clair standard ne doit PAS"
                                + " remplacer la base émeraude",
                        EditorTheme.light().editorBg,
                        activity.editor.getTheme().editorBg);
            });
        }
    }

    @Test
    public void lidentiteEmeraudeSombreResteLaBaseParDefaut() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                activity.openSample("Python", "python");
                assertNotNull(activity.editor.getTheme());
                assertEquals("Base émeraude sombre par défaut, y compris"
                                + " après bascule d'onglet",
                        DemoTheme.emeraldNight().editorBg,
                        activity.editor.getTheme().editorBg);
            });
        }
    }
}
