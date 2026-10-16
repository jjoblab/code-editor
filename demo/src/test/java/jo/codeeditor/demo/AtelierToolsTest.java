package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Test de fumée des outils de l'atelier (refonte UX 3.73.0) : bascule
 * TextMate ↔ voie intégrée (bug de disparition du menu), panneaux en
 * bottom sheet (états du behavior), annuler/rétablir, remplacer
 * tout/suivant et lecture seule — tout sur l'atelier réellement lancé.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class AtelierToolsTest {

    // ── Bascule TextMate ↔ voie intégrée ───────────────────────────

    @Test
    public void laBasculeTextMateResteVisibleDansLesDeuxSens() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                // Avant toute activation : la bascule est VISIBLE sur
                // Java (le bug de la refonte la masquait — isAvailable
                // ne dit vrai qu'une fois la grammaire chargée).
                assertTrue("La bascule TextMate doit être visible sur Java"
                                + " avant tout chargement",
                        activity.grammarToggleVisible());
                assertFalse("Et non cochée tant que la voie intégrée est active",
                        activity.grammarToggleChecked());

                // Activation → grammaire chargée → cochée.
                activity.applyGrammarMode(true);
                assertTrue(activity.grammarToggleChecked());

                // Désactivation → désenregistrement → NON cochée mais
                // TOUJOURS VISIBLE (retour possible : le correctif).
                activity.applyGrammarMode(false);
                assertFalse(activity.grammarToggleChecked());
                assertTrue("La bascule doit rester visible après désactivation"
                                + " — sinon impossible de revenir aux grammaires",
                        activity.grammarToggleVisible());

                // Retour : la bascule reste utilisable en boucle.
                activity.applyGrammarMode(true);
                assertTrue(activity.grammarToggleChecked());
            });
        }
    }

    // ── Bottom sheet des panneaux outils ───────────────────────────

    @Test
    public void lesPanneauxOutilsSontUnBottomSheetRepliable() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue("Déplié au lancement", activity.panels.isExpanded());
                activity.panels.setExpanded(false);
                assertFalse("Replié sur demande (peek = en-tête)",
                        activity.panels.isExpanded());
                activity.panels.setExpanded(true);
                assertTrue("Redépliable", activity.panels.isExpanded());
            });
        }
    }

    // ── Annuler / rétablir ──────────────────────────────────────────

    @Test
    public void annulerPuisRétablirRejoueLesÉditions() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                String before = activity.session.getText();
                activity.session.replaceRange(0, 0, "// ajout de test\n");
                assertEquals("L'insertion est appliquée",
                        "// ajout de test\n" + before, activity.session.getText());

                activity.doUndo();
                assertEquals("L'annulation restaure le texte initial",
                        before, activity.session.getText());

                activity.doRedo();
                assertNotEquals("Le rétablissement rejoue l'insertion",
                        before, activity.session.getText());
                assertEquals("// ajout de test\n" + before,
                        activity.session.getText());
            });
        }
    }

    // ── Remplacer tout / occurrence suivante ────────────────────────

    @Test
    public void remplacerToutInverseChaqueOccurrenceEtAnnonceLeCompte() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                int count = activity.replaceAll("public", "private");
                assertTrue("L'échantillon Java contient des « public »",
                        count > 0);
                assertFalse("Toutes les occurrences sont remplacées",
                        activity.session.getText().contains("public"));
                assertTrue(activity.session.getText().contains("private"));
            });
        }
    }

    @Test
    public void remplacerSuivantRemplaceUneSeuleOccurrenceAvecRebouclage() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                String before = activity.session.getText();
                assertTrue("La première occurrence est remplacée",
                        activity.replaceNext("public", "internal"));
                assertNotEquals(before, activity.session.getText());
                // Une seule occurrence a bougé : les autres survivent
                // (l'échantillon Java en compte plusieurs).
                assertTrue("Les occurrences suivantes restent",
                        activity.session.getText().contains("public"));
                // Occurrence inexistante : échec propre, texte intact.
                String after = activity.session.getText();
                assertFalse("Aucune occurrence introuvable",
                        activity.replaceNext("zzzz_inexistant", "x"));
                assertEquals(after, activity.session.getText());
            });
        }
    }

    // ── Lecture seule ───────────────────────────────────────────────

    @Test
    public void laLectureSeuleBloqueLaFrappeEtSeRetire() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertFalse(activity.session.isReadOnly());
                activity.toggleReadOnly(true);
                assertTrue("La session est verrouillée", activity.session.isReadOnly());
                // En lecture seule, l'édition directe est refusée.
                int length = activity.session.getText().length();
                activity.session.replaceRange(0, 0, "x");
                assertEquals("La frappe est bloquée en lecture seule",
                        length, activity.session.getText().length());
                activity.toggleReadOnly(false);
                assertFalse(activity.session.isReadOnly());
            });
        }
    }

    // ── Point d'arrêt dans le débordement du toolbar de sélection ──

    @Test
    public void leToolbarDeSélectionPorteUneActionPointDArrêtQuiBascule() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                jo.codeeditor.view.SelectionAction breakpoint = null;
                for (jo.codeeditor.view.SelectionAction action
                        : activity.editor.popupManager.selActions.actions) {
                    if ("demo.breakpoint".equals(action.id)) breakpoint = action;
                }
                assertNotNull("l'action Point d'arrêt est injectée dans le"
                        + " toolbar de sélection (débordement « ⋯ »)",
                        breakpoint);
                assertEquals("l'action porte l'icône disque plein du moteur",
                        jo.codeeditor.view.icons.EditorIcons.Icon.BREAKPOINT,
                        breakpoint.icon);
                assertTrue("l'action est active (session vivante)",
                        breakpoint.isEnabled());

                // Bascule : pose le point d'arrêt de gouttière sur la
                // ligne du début de la sélection, puis le retire.
                int line = activity.session.getDocument()
                        .lineForOffset(activity.session.getSelection().start);
                breakpoint.perform.run();
                assertTrue("le point d'arrêt est posé sur la ligne",
                        activity.editor.gutterActions.actionForLine(line) != null);
                breakpoint.perform.run();
                assertTrue("le second tap retire le point d'arrêt",
                        activity.editor.gutterActions.actionForLine(line) == null);
            });
        }
    }
}
