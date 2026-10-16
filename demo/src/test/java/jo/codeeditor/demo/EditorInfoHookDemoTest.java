package jo.codeeditor.demo;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.inputmethod.EditorInfo;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * R9 (plan 09) — l'atelier démo utilise les hooks hôte de l'EditorInfo :
 * la personnalisation (ici NO_PERSONALIZED_LEARNING, frappe de code
 * privé) doit se retrouver dans l'EditorInfo produit à la connexion, SANS
 * que la démo ne patche la bibliothèque.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class EditorInfoHookDemoTest {

    @Test
    public void atelier_coupeApprentissagePersonnaliseDuClavier() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity.editor);
                EditorInfo info = new EditorInfo();
                activity.editor.onCreateInputConnection(info);
                assertTrue("la démo coupe l'apprentissage personnalisé via le hook R9",
                        (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
                assertTrue("les valeurs par défaut de la bibliothèque survivent",
                        (info.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0);
            });
        }
    }
}
