package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.view.CompletionResult;

/**
 * 4.3.0 — la vitrine démo du drapeau « liste tronquée » : le moteur
 * maison plafonne sa base et la marque incomplète quand la simulation
 * est armée, chaque requête est comptée et journalisée, et les
 * réglages hôtes (honneur au drapeau, anti-rebond) sont relayés par
 * l'atelier.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class CompletionTruncatedDemoTest {

    // ── Le fournisseur plafonne et marque ──────────────────────────

    @Test
    public void laSimulationPlafonneLaBaseEtLaMarqueTronquee() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                DemoAnalyzer analyzer = new DemoAnalyzer(
                        activity.editor, activity.session, "java");

                // Base complète : snippets + fonctions + types + auto-
                // imports + mots-clés — largement au-delà du plafond.
                analyzer.setIncompleteSimulation(false);
                CompletionResult full = analyzer.completionResultFor(
                        "Liste", 5, 0, "L", null);
                assertTrue("La base du moteur maison dépasse le plafond"
                                + " (sinon la simulation ne démontrerait rien)",
                        full.items.size() > DemoAnalyzer.TRUNCATED_CAP);
                assertFalse("Une liste non plafonnée est réputée complète",
                        full.isIncomplete);

                // Simulation armée : même requête, base tronquée.
                analyzer.setIncompleteSimulation(true);
                CompletionResult capped = analyzer.completionResultFor(
                        "Liste", 5, 0, "L", null);
                assertEquals("La base livrée est plafonnée",
                        DemoAnalyzer.TRUNCATED_CAP, capped.items.size());
                assertTrue("La base plafonnée porte le drapeau tronqué —"
                                + " c'est lui que le popup honore",
                        capped.isIncomplete);
            });
        }
    }

    @Test
    public void chaqueRequeteEstCompteeEtJournalisee() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                DemoAnalyzer analyzer = new DemoAnalyzer(
                        activity.editor, activity.session, "java");
                List<String> journal = new ArrayList<>();
                analyzer.setLogger(journal::add);

                assertEquals(0, analyzer.requestCount());
                analyzer.completionResultFor("a", 1, 0, "a", null);
                analyzer.completionResultFor("ap", 2, 0, "ap", '.');
                analyzer.completionResultFor("app", 3, 0, "app", null);

                assertEquals("Chaque appel du popup compte comme une requête",
                        3, analyzer.requestCount());
                assertEquals("Chaque requête laisse une ligne au journal",
                        3, journal.size());
                assertTrue("La ligne porte le préfixe demandé",
                        journal.get(1).contains("ap"));
                assertTrue("La ligne porte le déclencheur quand il existe",
                        journal.get(1).contains("."));
            });
        }
    }

    // ── L'atelier relaie les réglages 4.3.0 ────────────────────────

    @Test
    public void latelierRelaieHonneurAuDrapeauEtAntiRebond() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                // Défauts de la bibliothèque : honneur actif, 80 ms.
                assertTrue("Honneur au drapeau actif par défaut",
                        activity.editor.isHonorIncompleteCompletion());
                assertEquals("Anti-rebond par défaut : 80 ms",
                        80, activity.editor.getCompletionDebounceMs());

                activity.editor.setHonorIncompleteCompletion(false);
                assertFalse("Le réglage hôte coupe la re-requête",
                        activity.editor.isHonorIncompleteCompletion());

                activity.applyCompletionDebounce(500);
                assertEquals("L'anti-rebond est réglable par l'hôte",
                        500, activity.editor.getCompletionDebounceMs());

                // Remise aux défauts (propreté pour les autres tests).
                activity.editor.setHonorIncompleteCompletion(true);
                activity.applyCompletionDebounce(80);
            });
        }
    }

    @Test
    public void laSimulationSappliqueATousLesOngletsOuvertsEtAVenir() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                // Deux échantillons ouverts (l'onglet Java initial + un
                // second) : la bascule porte sur TOUS les analyseurs.
                activity.openSample("Python", "python");
                activity.applyTruncatedSimulation(true);
                assertTrue(activity.isTruncatedSimulation());
                for (EditorActivity.OpenFile file : activity.files) {
                    assertTrue("L'analyseur de « " + file.label
                                    + " » doit être en mode plafonné",
                            file.analyzer.isIncompleteSimulation());
                }

                // Un onglet OUVERT APRÈS la bascule hérite de l'état.
                activity.openSample("Rust", "rust");
                EditorActivity.OpenFile last =
                        activity.files.get(activity.files.size() - 1);
                assertTrue("Le nouvel onglet hérite de la simulation armée",
                        last.analyzer.isIncompleteSimulation());

                // Retour à l'état complet : tout le monde suit.
                activity.applyTruncatedSimulation(false);
                assertFalse(activity.isTruncatedSimulation());
                for (EditorActivity.OpenFile file : activity.files) {
                    assertFalse(file.analyzer.isIncompleteSimulation());
                }
            });
        }
    }
}
