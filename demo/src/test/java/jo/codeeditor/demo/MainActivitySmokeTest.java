package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import jo.codeeditor.session.EditorSession;

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
}
