package jo.codeeditor.view.render;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * M3 (P2) — verrouille en isolement la <b>décision de bascule</b> vers
 * la loupe native ({@code EditorChromePainter.nativeMagnifierUsable}) :
 * drapeau de l'hôte × niveau d'API (le widget plateforme
 * {@code android.widget.Magnifier} n'existe qu'à partir d'API 28).
 *
 * <p>Robolectric ne peut pas instancier le Magnifier natif — la
 * spécification du chantier demande de tester la BASCULE (API &lt; 28
 * → custom actif ; drapeau à faux → custom actif), ce que
 * {@code MagnifierNativeToggleTest} (package view) fait par le rendu ;
 * CE test verrouille la décision pure (toutes les API simulées).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class MagnifierNativeDecisionTest {

    @Test
    @Config(sdk = 27)
    public void api27_drapeauActif_nativeImpossible() {
        assertFalse("API < 28 : le widget plateforme n'existe pas — custom",
                EditorChromePainter.nativeMagnifierUsable(true));
    }

    @Test
    @Config(sdk = 28)
    public void api28_drapeauActif_nativePossible() {
        assertTrue("API 28 (Pie) : le widget existe — voie native",
                EditorChromePainter.nativeMagnifierUsable(true));
    }

    @Test
    @Config(sdk = 34)
    public void api34_drapeauActif_nativePossible() {
        assertTrue(EditorChromePainter.nativeMagnifierUsable(true));
    }

    @Test
    @Config(sdk = 34)
    public void drapeauFaux_customToujours() {
        assertFalse("drapeau à faux (défaut) : la voie custom reste active",
                EditorChromePainter.nativeMagnifierUsable(false));
    }
}
