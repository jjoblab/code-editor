package jo.codeeditor.view.chrome;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B10 — la barre de recherche : cycle ouvert/fermé (GONE par défaut),
 * amorçage de la requête depuis la sélection, frappe vivante (watcher
 * → recalcul différé), compte « i/n », bascules Aa/ab/.* et rangée de
 * remplacement. Le contrôleur a SA suite ({@code EditorSearchControllerTest})
 * — ici on verrouille la VUE qui le pilote.
 */
@RunWith(RobolectricTestRunner.class)
public class EditorSearchBarTest {

    private static final String DOC =
        "alpha beta gamma\nbeta alpha\ngamma\n";

    private EditorView view;
    private EditorSession session;
    private EditorSearchBar bar;

    @Before
    public void setUp() {
        Context ctx = RuntimeEnvironment.getApplication();
        view = new EditorView(ctx);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        bar = new EditorSearchBar(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(bar);
        root.measure(1080, 1920);
        root.layout(0, 0, 1080, 1920);
        bar.bind(view);
    }

    private void idleMainLooper() {
        // Le contrôleur diffère le recalcul (postDelayed 160 ms) —
        // idle() seul n'exécute PAS les tâches différées : avance le
        // temps du looper shadow au-delà du délai.
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Test
    public void cachéeParDéfaut_ouverteParOpen_referméeParClose() {
        assertEquals(View.GONE, bar.getVisibility());
        bar.open();
        assertEquals(View.VISIBLE, bar.getVisibility());
        assertTrue(bar.isOpen());

        bar.close();
        assertEquals(View.GONE, bar.getVisibility());
        assertFalse(bar.isOpen());
        assertTrue("close purge le surlignage", view.find.highlights.isEmpty());
    }

    @Test
    public void ouvertureAmorceLaRequêteDepuisLaSélection() {
        session.setSelection(Selection.range(6, 10)); // « beta »
        bar.open();
        assertEquals("beta", bar.queryFieldText());
        // Le compte est déjà vivant après l'ouverture.
        assertEquals(2, view.find.highlights.size());
    }

    @Test
    public void frappeDansLeChamp_rechercheVivanteEtCompte() {
        bar.open();
        bar.setQueryFieldText("alpha");
        // Le watcher passe par le différé (160 ms) — avance le looper.
        idleMainLooper();
        assertEquals(2, view.find.highlights.size());
    }

    @Test
    public void laFermetureSurvitÀLaRéouverture_avecLaRequête() {
        bar.open();
        bar.setQueryFieldText("gamma");
        idleMainLooper();
        bar.close();
        bar.open();
        assertEquals("gamma", bar.queryFieldText());
        assertEquals(2, view.find.highlights.size());
    }

    @Test
    public void navigationPendantLOuverture_viaChampOuBoutons() {
        bar.open();
        bar.setQueryFieldText("beta");
        idleMainLooper();
        // Enter du champ = action SEARCH = occurrence suivante.
        bar.pressQueryAction();
        assertEquals(1, view.find.currentIndex);
    }

    @Test
    public void changementDOptionsDepuisLaBarre() {
        bar.open();
        bar.setQueryFieldText("ALPHA");
        idleMainLooper();
        assertEquals("insensible par défaut", 2, view.find.highlights.size());

        bar.toggleCaseChip();
        assertEquals("Aa = sensible : plus rien", 0, view.find.highlights.size());
        bar.toggleCaseChip();
        assertEquals(2, view.find.highlights.size());

        bar.toggleRegexChip();
        bar.setQueryFieldText("al(pha)");
        idleMainLooper();
        assertEquals(2, view.find.highlights.size());
    }

    @Test
    public void rangéeRemplacer_invisiblePuisVisible() {
        bar.open();
        assertFalse(bar.replaceRowVisible());
        bar.toggleReplaceChip();
        assertTrue(bar.replaceRowVisible());
        assertEquals("le contrôleur suit la bascule", true,
                bar.controllerState().isReplaceMode());
    }

    @Test
    public void remplacerDepuisLaBarre() {
        bar.open();
        bar.setQueryFieldText("gamma");
        idleMainLooper();
        bar.toggleReplaceChip();
        bar.setReplaceFieldText("G");
        bar.pressReplaceOne();
        assertEquals("alpha beta G\nbeta alpha\ngamma\n", session.getText());
        bar.pressReplaceAll();
        assertEquals("alpha beta G\nbeta alpha\nG\n", session.getText());
    }

    @Test
    public void refreshAprèsChangementDeSession() {
        bar.open();
        bar.setQueryFieldText("beta");
        idleMainLooper();
        assertEquals(2, view.find.highlights.size());

        EditorSession autre = new EditorSession(
                EditorDocument.of("beta beta beta"));
        view.setSession(autre);
        assertTrue(view.find.highlights.isEmpty());

        bar.refresh();
        assertEquals(3, view.find.highlights.size());
    }
}
