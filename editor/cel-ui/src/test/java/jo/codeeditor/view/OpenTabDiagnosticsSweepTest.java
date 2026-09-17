package jo.codeeditor.view;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.LooperMode;

import android.content.Context;
import android.widget.FrameLayout;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.model.Diagnostic;
import jo.codeeditor.lang.provider.DiagnosticsProvider;
import jo.codeeditor.lang.EmptyLanguage;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.Assert.*;

/**
 * Tests du sweep diagnostics des onglets ouverts.
 *
 * <p>Vérifie : l'application des diagnostics sur un onglet arrière-plan
 * (calcul hors main thread, application sur le main looper), les règles
 * d'éligibilité (read-only sauté, gros fichier sauté, sans provider
 * sauté, vue détachée sautée), le stale-guard (changement de session
 * pendant le vol → résultat jeté), l'annulation, et la tolérance aux
 * providers qui throw (comptés comme sautés, le sweep continue).</p>
 *
 * <p>L'exécuteur partagé est remplacé par un exécuteur direct (réflexion)
 * pour un test déterministe : le calcul tourne inline, les applications
 * passent par le main looper de Robolectric (idle()).</p>
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class OpenTabDiagnosticsSweepTest {

    /** Un langage dont le provider de diagnostics renvoie des diagnostics fixes. */
    private static class TestLang extends EmptyLanguage {
        final List<Diagnostic> result;
        /** Effet de bord optionnel exécuté à chaque compute — simule une
         *  activité hôte en plein sweep (changement d'onglet, teardown…). */
        Runnable onCompute;

        TestLang(List<Diagnostic> result) {
            this.result = result;
        }

        @Override
        public DiagnosticsProvider getDiagnosticsProvider() {
            return text -> {
                if (onCompute != null) onCompute.run();
                return result;
            };
        }
    }

    /** Un langage dont le provider throw à partir du SECOND appel (le
     *  premier appel sert le debounce inline propre à la vue au moment du
     *  setLanguage). */
    private static class ThrowingLang extends EmptyLanguage {
        boolean thrown = false;

        @Override
        public DiagnosticsProvider getDiagnosticsProvider() {
            return text -> {
                if (thrown) throw new IllegalStateException("provider crash");
                thrown = true;
                return new ArrayList<>();
            };
        }
    }

    @Before
    public void useDirectExecutor() throws Exception {
        Field f = OpenTabDiagnosticsSweep.class.getDeclaredField("sharedExecutor");
        f.setAccessible(true);
        Executor direct = Runnable::run;
        f.set(null, direct);
    }

    private EditorView newTab(String doc, jo.codeeditor.lang.Language lang) {
        Context ctx = RuntimeEnvironment.getApplication();
        // Attache à un parent — le sweep saute les vues détachées (teardown).
        FrameLayout parent = new FrameLayout(ctx);
        EditorView view = new EditorView(ctx);
        parent.addView(view);
        view.setSession(new EditorSession(EditorDocument.of(doc)));
        if (lang != null) {
            view.setLanguage(lang);
            // setLanguage arme/exécute la tâche de diagnostics debouncée
            // PROPRE à la vue (inline quand la vue n'a pas encore de
            // handler). Nettoie son résultat pour que les tests ci-dessous
            // n'observent que le travail du SWEEP.
            view.getSession().setDiagnostics(new ArrayList<>());
        }
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    private static void drainMainLooper() {
        // idleFor avance l'horloge (en pause) de Robolectric, donc la
        // cascade postDelayed(40ms) du sweep — apply → scheduleNext →
        // onglet suivant — tourne jusqu'au bout. runToEndOfTasks()
        // N'exécute PAS les tâches postées avec délai pendant l'exécution.
        Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(Duration.ofSeconds(5));
    }

    private static List<Diagnostic> oneError(String message) {
        List<Diagnostic> out = new ArrayList<>();
        out.add(new Diagnostic(0, 5, 3, message, "E1"));
        return out;
    }

    // ── Le chemin nominal ────────────────────────────────────────

    @Test
    public void sweep_appliesDiagnosticsToBackgroundTab() {
        EditorView tab = newTab("class A {}\n", new TestLang(oneError("swept error")));
        assertTrue(tab.getSession().getDiagnostics().isEmpty());

        OpenTabDiagnosticsSweep.start(new ArrayList<>(List.of(tab)));
        drainMainLooper();

        assertEquals(1, tab.getSession().getDiagnostics().size());
        assertEquals("swept error", tab.getSession().getDiagnostics().get(0).message);
        assertEquals(3, tab.getSession().getDiagnostics().get(0).severity);
    }

    @Test
    public void sweep_walksMultipleTabs_inOrderWithGap() {
        EditorView t1 = newTab("class A {}\n", new TestLang(oneError("e1")));
        EditorView t2 = newTab("class B {}\n", new TestLang(oneError("e2")));
        OpenTabDiagnosticsSweep sweep = OpenTabDiagnosticsSweep.start(List.of(t1, t2));
        drainMainLooper();
        assertEquals("e1", t1.getSession().getDiagnostics().get(0).message);
        assertEquals("e2", t2.getSession().getDiagnostics().get(0).message);
        assertEquals(2, sweep.processedCount());
        assertEquals(0, sweep.skippedCount());
    }

    // ── Règles d'éligibilité ─────────────────────────────────────

    @Test
    public void sweep_skipsReadOnlyTabs() {
        EditorView tab = newTab("class A {}\n", new TestLang(oneError("never")));
        tab.getSession().setReadOnly(true);
        OpenTabDiagnosticsSweep.start(List.of(tab));
        drainMainLooper();
        assertTrue("read-only tabs are not re-analyzed",
                tab.getSession().getDiagnostics().isEmpty());
    }

    @Test
    public void sweep_skipsLargeDocuments() {
        // > 50k lignes franchit EditorDocument.isLarge() — même seuil que
        // l'éditeur applique à l'analyse sémantique.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50_100; i++) sb.append('x').append('\n');
        EditorView tab = newTab(sb.toString(), new TestLang(oneError("never")));
        assertTrue(tab.getSession().getDocument().isLarge());
        OpenTabDiagnosticsSweep.start(List.of(tab));
        drainMainLooper();
        assertTrue("large documents are skipped",
                tab.getSession().getDiagnostics().isEmpty());
    }

    @Test
    public void sweep_skipsTabsWithoutProvider() {
        EditorView tab = newTab("class A {}\n", null); // EmptyLanguage, pas de provider
        OpenTabDiagnosticsSweep.start(List.of(tab));
        drainMainLooper();
        assertTrue(tab.getSession().getDiagnostics().isEmpty());
    }

    @Test
    public void sweep_skipsDetachedViews() {
        EditorView attached = newTab("class A {}\n", new TestLang(oneError("applied")));
        EditorView detached = newTab("class B {}\n", new TestLang(oneError("never")));
        ((FrameLayout) detached.getParent()).removeView(detached);
        OpenTabDiagnosticsSweep sweep = OpenTabDiagnosticsSweep.start(List.of(detached, attached));
        drainMainLooper();
        assertTrue(detached.getSession().getDiagnostics().isEmpty());
        assertEquals(1, attached.getSession().getDiagnostics().size());
        assertEquals(1, sweep.skippedCount());
        assertEquals(1, sweep.processedCount());
    }

    // ── Robustesse ────────────────────────────────────────────────

    @Test
    public void sweep_throwingProviderIsCountedAsSkipped_andWalkContinues() {
        EditorView bad = newTab("class A {}\n", new ThrowingLang());
        EditorView good = newTab("class B {}\n", new TestLang(oneError("ok")));
        OpenTabDiagnosticsSweep sweep = OpenTabDiagnosticsSweep.start(List.of(bad, good));
        drainMainLooper();
        assertTrue(bad.getSession().getDiagnostics().isEmpty());
        assertEquals(1, good.getSession().getDiagnostics().size());
        assertEquals(1, sweep.skippedCount());
        assertEquals(1, sweep.processedCount());
    }

    @Test
    public void sweep_staleSessionResultIsDiscarded() {
        TestLang lang = new TestLang(oneError("stale"));
        EditorView tab = newTab("class A {}\n", lang);
        // La tâche de diagnostics inline propre à la vue a déjà tourné
        // pendant setLanguage — arme l'effet de bord en plein vol
        // uniquement pour le compute du SWEEP : swappe la session PENDANT
        // que le calcul d'arrière-plan tourne (l'hôte a changé de fichier
        // en plein sweep).
        lang.onCompute = () -> tab.setSession(
                new EditorSession(EditorDocument.of("class C {}\n")));
        OpenTabDiagnosticsSweep sweep = OpenTabDiagnosticsSweep.start(List.of(tab));
        drainMainLooper();
        // analyze a capturé l'ANCIENNE session ; l'apply en voit une autre
        // → jeté + compté comme sauté, jamais appliqué.
        assertEquals(0, sweep.processedCount());
        assertEquals(1, sweep.skippedCount());
    }

    @Test
    public void cancel_stopsTheWalk() {
        EditorView t1 = newTab("class A {}\n", new TestLang(oneError("first")));
        EditorView t2 = newTab("class B {}\n", new TestLang(oneError("second")));
        OpenTabDiagnosticsSweep sweep = OpenTabDiagnosticsSweep.start(List.of(t1, t2));
        // Annule avant que le main looper n'exécute quoi que ce soit.
        sweep.cancel();
        drainMainLooper();
        assertTrue(t1.getSession().getDiagnostics().isEmpty());
        assertTrue(t2.getSession().getDiagnostics().isEmpty());
    }

    @Test
    public void emptyTabList_isANoOp() {
        OpenTabDiagnosticsSweep sweep = OpenTabDiagnosticsSweep.start(new ArrayList<>());
        drainMainLooper();
        assertEquals(0, sweep.processedCount());
        assertEquals(0, sweep.skippedCount());
    }
}
