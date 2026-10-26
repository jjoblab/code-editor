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
 * Banc viewport (4.5.1) : l'insertion d'un bloc de 30 lignes EN TÊTE
 * de document emprunte le chemin exact des éditions additionnelles
 * d'une acceptation de complétion (un seul lot d'annulation,
 * sélection restaurée content-stable) — ce que la compensation du
 * viewport (4.5.0) maintient à l'écran quand l'ancrage est défilé.
 *
 * <p>La compensation ELLE-MÊME est verrouillée côté bibliothèque
 * (ViewportShiftCompensationTest, métriques injectées) ; ici c'est le
 * CHEMIN DÉMO qui est prouvé : une étape d'annulation, bloc
 * reconnaissable, retrait franc, sélection stable en CONTENU.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class ViewportBenchDemoTest {

    @Test
    public void linsertionDuBlocSeFaitEnUneSeuleEtapeDAnnulation() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(),
                EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_ID, "java");
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_LABEL, "Java");
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                String avant = activity.session.getText();
                int lignesAvant = activity.session.getDocument().lineCount();

                activity.applyViewportBenchInsert();

                String apres = activity.session.getText();
                assertTrue("Le bloc marque la tête du document",
                        apres.startsWith(EditorActivity.VIEWPORT_BENCH_MARK));
                assertEquals("30 lignes insérées",
                        lignesAvant + EditorActivity.VIEWPORT_BENCH_LINES,
                        activity.session.getDocument().lineCount());

                // UNE annulation retire le bloc ENTIER (un lot, pas 30
                // étapes de frappe).
                assertTrue("Le lot d'annulation existe",
                        activity.session.undo());
                assertEquals("Retour franc au texte d'origine",
                        avant, activity.session.getText());
            });
        }
    }

    @Test
    public void laSelectionResteStableEnContenuApresInsertion() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(),
                EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_ID, "java");
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_LABEL, "Java");
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                // Caret au début de la 5e ligne : SOUS le futur bloc.
                String texteAvant = activity.session.getText();
                int caret = activity.session.getDocument().lineStart(5);
                activity.session.setSelection(Selection.cursor(caret));
                String sousLesYeux = texteAvant.substring(caret, caret + 24);

                activity.applyViewportBenchInsert();

                // Delta MESURÉ (les lignes du bloc varient d'un caractère
                // selon leur numéro) : le caret doit être décalé d'autant.
                int delta = activity.session.getText().length()
                        - texteAvant.length();
                int nouveau = activity.session.getSelection().start;
                assertEquals("Le caret suit l'insertion du bloc",
                        caret + delta, nouveau);
                assertEquals("…et désigne le MÊME contenu (content-stable)",
                        sousLesYeux,
                        activity.session.getText().substring(nouveau,
                                nouveau + 24));
            });
        }
    }

    @Test
    public void leRetraitEnleveTousLesBlocsSuperposes() {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(),
                EditorActivity.class);
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_ID, "java");
        intent.putExtra(EditorActivity.EXTRA_SAMPLE_LABEL, "Java");
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(intent)) {
            scenario.onActivity(activity -> {
                String original = activity.session.getText();
                activity.applyViewportBenchInsert();
                activity.applyViewportBenchInsert();

                // Deux blocs superposés = 60 lignes de banc en tête.
                assertEquals(2 * EditorActivity.VIEWPORT_BENCH_LINES,
                        activity.session.getDocument().lineCount()
                                - original.split("\n", -1).length);

                activity.applyViewportBenchRemove();
                assertEquals("Les DEUX blocs partent d'un coup",
                        original, activity.session.getText());
                assertFalse("Plus aucune ligne marquée",
                        activity.session.getText()
                                .contains(EditorActivity.VIEWPORT_BENCH_MARK));

                // Retrait sans bloc : no-op franc.
                activity.applyViewportBenchRemove();
                assertEquals(original, activity.session.getText());
            });
        }
    }
}
