package jo.codeeditor.demo;

import static org.junit.Assert.assertEquals;

import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ActivityScenario;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import jo.codeeditor.view.EditorView;

/**
 * La persistance de l'apprentissage VUE PAR LA DÉMO (4.6.0) : le
 * magasin JSON fait l'aller-retour complet (photographie → chaîne →
 * relecture), les données corrompues sont muettes, et le CYCLE DE VIE
 * de l'atelier photographie au onStop / restaure au onCreate —
 * l'apprentissage survit à la mort de l'activité.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class LearningPersistenceDemoTest {

    private EditorView nouvelleVue() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    @Test
    public void allerRetourJson_complet() {
        EditorView source = nouvelleVue();
        source.completionHistory.record("java", "Text");
        source.completionHistory.record("java", "Text");
        source.completionHistory.record("xml", "LinearLayout");
        long boostText = source.completionHistory.boost("java", "Text");
        long boostLayout = source.completionHistory.boost("xml", "LinearLayout");

        String json = LearningStateStore.snapshotJson(source);
        EditorView cible = nouvelleVue();
        LearningStateStore.restore(cible, json);

        // 2 identifiants : « Text » (java, fréquence 2) et « LinearLayout ».
        assertEquals(2, cible.completionHistory.size());
        assertEquals(boostText, cible.completionHistory.boost("java", "Text"));
        assertEquals(boostLayout,
                cible.completionHistory.boost("xml", "LinearLayout"));
    }

    @Test
    public void jsonVideOuNul_muets() {
        EditorView view = nouvelleVue();
        view.completionHistory.record("java", "Text");
        LearningStateStore.restore(view, null);
        LearningStateStore.restore(view, "");
        assertEquals("null et vide ne touchent à rien",
                1L, view.completionHistory.size());
    }

    @Test
    public void jsonCorrompu_repartViergeSansCrash() {
        EditorView view = nouvelleVue();
        view.completionHistory.record("java", "Text");
        LearningStateStore.restore(view, "{pas du json");
        LearningStateStore.restore(view, "{\"version\":1,\"languages\":42}");
        assertEquals("les données illisibles n'écrasent pas l'existant",
                1L, view.completionHistory.size());
    }

    @Test
    public void versionInconnue_refuseeParLaBibliotheque() {
        EditorView source = nouvelleVue();
        source.completionHistory.record("java", "Text");
        String json = LearningStateStore.snapshotJson(source)
                .replace("\"version\":1", "\"version\":99");

        EditorView cible = nouvelleVue();
        LearningStateStore.restore(cible, json);
        assertEquals("un format du futur n'est jamais interprété à l'aveugle",
                0L, cible.completionHistory.size());
    }

    @Test
    public void cycleDeVieDeLatelier_photographieEtRestaure() {
        // Session 1 : apprendre, quitter (onStop photographie dans les
        // préférences de la démo).
        try (ActivityScenario<EditorActivity> sc1 =
                     ActivityScenario.launch(EditorActivity.class)) {
            sc1.onActivity(activity -> {
                activity.editor.completionHistory.record("java", "Text");
                activity.editor.completionHistory.record("java", "Intent");
            });
            sc1.moveToState(androidx.lifecycle.Lifecycle.State.DESTROYED);
        }

        // Session 2 : l'atelier rouvre, l'historique de la session 1
        // doit être LÀ dès le onCreate.
        try (ActivityScenario<EditorActivity> sc2 =
                     ActivityScenario.launch(EditorActivity.class)) {
            sc2.onActivity(activity ->
                    assertEquals("l'apprentissage survit à la mort de l'activité",
                            2L, activity.editor.completionHistory.size()));
        }
    }

    @Test
    public void lEtatDeApprentissageSeBasculeDepuisLatelier() {
        try (ActivityScenario<EditorActivity> scenario =
                     ActivityScenario.launch(EditorActivity.class)) {
            scenario.onActivity(activity -> {
                assertTrue("Miroir XML lié actif par défaut",
                        activity.isLinkedTagEdits());
                activity.applyLinkedTagEdits(false);
                assertTrue("Le menu coupe le miroir",
                        !activity.isLinkedTagEdits());
                activity.applyLinkedTagEdits(true);
                assertTrue("Et le réactive",
                        activity.isLinkedTagEdits());
            });
        }
    }

    @Test
    public void snapshotJson_videMaisValide() {
        EditorView view = nouvelleVue();
        String json = LearningStateStore.snapshotJson(view);
        // Une vue vierge se photographie en un JSON valide et non null.
        assertTrue(json != null && json.contains("\"version\":1"));
        EditorView cible = nouvelleVue();
        LearningStateStore.restore(cible, json);
        assertEquals(0, cible.completionHistory.size());
    }
}
