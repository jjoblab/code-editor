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

import java.util.List;

import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.view.CompletionResult;

/**
 * 4.4.0 — les vitrines démo des chantiers P1 : le banc de modificateurs
 * sémantiques (jetons injectés sans serveur, marqués par fréquence), la
 * bascule des occurrences du mot courant relayée par l'atelier, et les
 * candidats du moteur maison qui portent enfin une documentation (le
 * panneau du popup l'affiche sous la liste).
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class SemanticBenchDemoTest {

    // ── Le banc marque les identifiants fréquents ───────────────────

    @Test
    public void leBancMarqueLesTroisIdentifiantsLesPlusFrequents() {
        // « alpha » ×3 (déprécié), « beta » ×2 (statique), « gamma » ×1
        // (abstrait) — chaque occurrence ENTIÈRE porte le modificateur
        // du rang de son mot.
        String texte = "alpha alpha alpha beta beta gamma\n";
        List<DiagnosticShift.SemanticToken> tokens =
                DemoAnalyzer.semanticBenchTokens(texte);
        assertEquals("trois mots marqués, toutes occurrences comprises",
                3 + 2 + 1, tokens.size());
        int dépréciés = 0, statiques = 0, abstraits = 0;
        for (DiagnosticShift.SemanticToken t : tokens) {
            if (t.modifiers == DiagnosticShift.SemanticToken.MOD_DEPRECATED) dépréciés++;
            else if (t.modifiers == DiagnosticShift.SemanticToken.MOD_STATIC) statiques++;
            else if (t.modifiers == DiagnosticShift.SemanticToken.MOD_ABSTRACT) abstraits++;
        }
        assertEquals(3, dépréciés);
        assertEquals(2, statiques);
        assertEquals(1, abstraits);
    }

    @Test
    public void leBancSarmeSurTousLesOngletsEtLesSuivants() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                activity.openSample("Python", "python");
                activity.applySemanticBench(true);
                assertTrue(activity.isSemanticBench());
                // Chaque session ouverte porte des jetons non vides.
                for (EditorActivity.OpenFile file : activity.files) {
                    assertFalse("l'onglet « " + file.label + " » est marqué",
                            file.session.getSemanticTokens().isEmpty());
                }
                // Un onglet OUVERT APRÈS la bascule hérite du banc.
                activity.openSample("Rust", "rust");
                EditorActivity.OpenFile dernier =
                        activity.files.get(activity.files.size() - 1);
                assertFalse("le nouvel onglet hérite du banc armé",
                        dernier.session.getSemanticTokens().isEmpty());

                // Extinction : tout le monde est nettoyé.
                activity.applySemanticBench(false);
                assertFalse(activity.isSemanticBench());
                for (EditorActivity.OpenFile file : activity.files) {
                    assertTrue("l'onglet « " + file.label + " » est nettoyé",
                            file.session.getSemanticTokens().isEmpty());
                }
            });
        }
    }

    // ── L'atelier relaie la bascule occurrences ─────────────────────

    @Test
    public void latelierRelaieLaBasculeOccurrencesDuMotCourant() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue("Occurrences actives par défaut",
                        activity.editor.isWordOccurrencesEnabled());
                activity.applyWordOccurrences(false);
                assertFalse("La bascule hôte coupe la surbrillance",
                        activity.editor.isWordOccurrencesEnabled());
                activity.applyWordOccurrences(true);
                assertTrue(activity.editor.isWordOccurrencesEnabled());
            });
        }
    }

    // ── Les candidats du moteur portent une documentation ───────────

    @Test
    public void lesCandidatsDuMoteurPortentDeLaDocumentation() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                DemoAnalyzer analyzer = new DemoAnalyzer(
                        activity.editor, activity.session, "java");
                CompletionResult base = analyzer.completionResultFor(
                        "pri", 3, 0, "pri", null);
                String documentation = null;
                for (jo.codeeditor.completion.CompletionSession.Item item : base.items) {
                    if ("println".equals(item.label)) {
                        documentation = item.documentation;
                    }
                }
                assertTrue("« println » porte une documentation que le panneau "
                                + "du popup affichera",
                        documentation != null && documentation.contains("Affiche"));
            });
        }
    }
}
