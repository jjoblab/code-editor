package jo.codeeditor.demo;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Test de fumée de l'écran d'accueil : lancement réel (cycle onCreate
 * complet), présence du titre et des cartes de capacité.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class HomeActivitySmokeTest {

    @Test
    public void leLancementAfficheLAccueilAvecLesCartes() {
        try (ActivityScenario<HomeActivity> scenario =
                     ActivityScenario.launch(HomeActivity.class)) {
            scenario.onActivity(activity -> {
                assertNotNull(activity);
                assertTrue("L'activité n'est pas en train de finir",
                        !activity.isFinishing());
                TextView titre = trouverTextView(activity, activity
                        .getString(R.string.home_title));
                assertNotNull("Le titre de l'accueil doit être rendu", titre);
                assertNotNull("La carte LSP doit être rendue",
                        trouverTextView(activity, activity
                                .getString(R.string.home_card_lsp_title)));
            });
        }
    }

    private static TextView trouverTextView(HomeActivity activity, String texte) {
        android.view.View racine = activity.findViewById(android.R.id.content);
        return chercher(racine, texte);
    }

    private static TextView chercher(android.view.View vue, String texte) {
        if (vue instanceof TextView) {
            TextView tv = (TextView) vue;
            if (texte.equals(tv.getText().toString())) return tv;
        }
        if (vue instanceof android.view.ViewGroup) {
            android.view.ViewGroup groupe = (android.view.ViewGroup) vue;
            for (int i = 0; i < groupe.getChildCount(); i++) {
                TextView trouvé = chercher(groupe.getChildAt(i), texte);
                if (trouvé != null) return trouvé;
            }
        }
        return null;
    }
}
