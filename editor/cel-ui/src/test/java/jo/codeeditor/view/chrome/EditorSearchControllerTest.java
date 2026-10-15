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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B10 — le contrôleur de recherche/remplacement : occurrences poussées
 * dans le viewport ({@code view.find.highlights}), navigation bouclante avec
 * caret suivant, remplacement de l'occurrence courante (plain et regex
 * avec groupes), remplacement de TOUT en une seule entrée d'annulation,
 * ré-accrochage au changement de session.
 *
 * <p>Méthodes SYNCHRONES : {@code setQuery} passe par
 * {@code scheduleRefresh} (160 ms différé) — les tests appellent
 * {@code refresh()} (ou une action) pour déclencher le calcul immédiat,
 * miroir de la barre qui avance le looper.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorSearchControllerTest {

    private static final String DOC =
        "alpha beta gamma\n"     // 0 : alpha @0, beta @6, gamma @11
        + "beta alpha\n"         // 1 : beta @17, alpha @22
        + "gamma\n"              // 2 : gamma @28
        + "\n"
        + "fin\n";

    private EditorView view;
    private EditorSession session;
    private EditorSearchController controller;

    @Before
    public void setUp() {
        Context ctx = RuntimeEnvironment.getApplication();
        view = new EditorView(ctx);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        controller = new EditorSearchController(view);
    }

    private void idleMainLooper() {
        // Le contrôleur diffère le recalcul (postDelayed 160 ms) —
        // idle() seul n'exécute PAS les tâches différées : avance le
        // temps du looper shadow au-delà du délai.
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    // ── Recherche + surlignage ─────────────────────────────────────

    @Test
    public void laRecherchePousseLesOccurrencesDansLeViewport() {
        controller.setQuery("beta");
        controller.refresh();
        assertEquals(2, controller.getMatchCount());
        assertEquals(2, view.find.highlights.size());
        assertEquals(6, view.find.highlights.get(0).start);
        assertEquals(17, view.find.highlights.get(1).start);
    }

    @Test
    public void requêteVide_aucunSurlignage() {
        controller.setQuery("beta");
        controller.refresh();
        controller.setQuery("");
        controller.refresh();
        assertTrue(view.find.highlights.isEmpty());
        assertEquals(0, controller.getMatchCount());
    }

    @Test
    public void optionsBasculesRecalculent() {
        controller.setQuery("ALPHA");
        controller.refresh();
        assertEquals("insensible à la casse par défaut", 2, controller.getMatchCount());
        controller.setCaseSensitive(true);
        assertEquals(0, controller.getMatchCount());
        controller.setCaseSensitive(false);
        assertEquals(2, controller.getMatchCount());

        controller.setQuery("alpha");
        controller.setWholeWord(true);
        assertEquals(2, controller.getMatchCount());

        controller.setRegex(true);
        controller.setQuery("gam(a)");
        assertEquals(2, controller.getMatchCount());
    }

    @Test
    public void regexInvalide_aucuneOccurrenceAucunCrash() {
        controller.setRegex(true);
        controller.setQuery("[invalid");
        controller.refresh();
        assertEquals(0, controller.getMatchCount());
    }

    // ── Navigation ─────────────────────────────────────────────────

    @Test
    public void navigationBoucleEtLeCaretSuitLaCourante() {
        controller.setQuery("alpha");
        controller.refresh();
        assertEquals(0, controller.getCurrentIndex());
        assertEquals("courante = première à partir du caret 0", 0,
                view.find.highlights.get(view.find.currentIndex).start);

        controller.next();
        assertEquals(1, controller.getCurrentIndex());
        assertEquals("le caret suit le DÉBUT de la courante", 22,
                session.getSelection().start);

        controller.next();
        assertEquals("bouclage après la dernière", 0, controller.getCurrentIndex());
        assertEquals(0, session.getSelection().start);

        controller.prev();
        assertEquals("précédent reboucle à la fin", 1, controller.getCurrentIndex());
    }

    @Test
    public void navigationSansOccurrence_indexInvalide() {
        controller.setQuery("zzz");
        controller.refresh();
        assertEquals(-1, controller.next());
        assertEquals(-1, controller.getCurrentIndex());
    }

    @Test
    public void ouvertureAmorceDepuisLaSelectionUneLigne() {
        session.setSelection(Selection.range(6, 10)); // « beta »
        String q = controller.open(DOC.substring(6, 10));
        assertEquals("beta", q);
        assertEquals("amorcée, la recherche tourne déjà", 2, controller.getMatchCount());
        assertEquals(0, controller.getCurrentIndex());
    }

    // ── Remplacement ───────────────────────────────────────────────

    @Test
    public void remplacerLOccurrenceCouranteAvance() {
        controller.setQuery("beta");
        controller.refresh();
        // Courante 0 : « beta » @6 → remplacé par « ALPHA ».
        assertTrue(controller.replaceCurrent("ALPHA"));
        assertEquals("alpha ALPHA gamma\nbeta alpha\ngamma\n\nfin\n",
                session.getText());
        assertEquals("la recherche se relance sur le nouveau texte",
                1, controller.getMatchCount());
        assertEquals("la courante suit : première occurrence ≥ fin du remplacement",
                0, controller.getCurrentIndex());
        // « alpha ALPHA gamma\n » = 18 caractères : le beta restant @18.
        assertEquals(18, view.find.highlights.get(0).start);
    }

    @Test
    public void remplacerTout_uneSeuleEntréeDannulation() {
        controller.setQuery("gamma");
        controller.refresh();
        int count = controller.replaceAll("G");
        assertEquals(2, count);
        assertEquals("alpha beta G\nbeta alpha\nG\n\nfin\n", session.getText());
        assertEquals("0 restante", 0, controller.getMatchCount());
        // UNE annulation restaure le document ENTIER.
        session.undo();
        assertEquals(DOC, session.getText());
    }

    @Test
    public void remplacerToutRegexAvecGroupes() {
        controller.setRegex(true);
        controller.setQuery("gam(ma)");
        controller.refresh();
        int count = controller.replaceAll("<$1>");
        assertEquals(2, count);
        assertEquals("alpha beta <ma>\nbeta alpha\n<ma>\n\nfin\n",
                session.getText());
    }

    @Test
    public void remplacerCouranteRegexAvecGroupes() {
        controller.setRegex(true);
        controller.setQuery("be(t)a");
        controller.refresh();
        assertEquals(0, controller.getCurrentIndex());
        assertTrue(controller.replaceCurrent("B.$1"));
        assertEquals("alpha B.t gamma\nbeta alpha\ngamma\n\nfin\n",
                session.getText());
    }

    @Test
    public void remplacementMalFormé_repliLittéral() {
        controller.setRegex(true);
        controller.setQuery("gamma");
        controller.refresh();
        // « $ » orphelin : appendReplacement lèverait — repli littéral.
        assertTrue(controller.replaceCurrent("prix $"));
        assertEquals("alpha beta prix $\nbeta alpha\ngamma\n\nfin\n",
                session.getText());
    }

    @Test
    public void remplacerSansRequête_nope() {
        controller.setQuery("");
        controller.refresh();
        assertFalse(controller.replaceCurrent("x"));
        assertEquals(0, controller.replaceAll("x"));
    }

    // ── Édition vivante + session ──────────────────────────────────

    @Test
    public void éditionDuDocument_réutiliseLaRechercheDifférée() {
        controller.setQuery("beta");
        controller.refresh();
        assertEquals(2, controller.getMatchCount());
        // L'édition (frappe IME → replaceRange) déclenche le recalcul
        // différé posé par le listener de texte de la session.
        session.replaceRange(0, 0, "beta ");
        idleMainLooper();
        assertEquals(3, controller.getMatchCount());
    }

    @Test
    public void changementDeSession_réaccrocheEtRecalcule() {
        controller.setQuery("alpha");
        controller.refresh();
        assertEquals(2, controller.getMatchCount());
        assertFalse(view.find.highlights.isEmpty());

        EditorSession nouvelle = new EditorSession(
                EditorDocument.of("alpha seul ici"));
        view.setSession(nouvelle);
        assertTrue("setSession purge le surlignage périmé",
                view.find.highlights.isEmpty());

        controller.refresh();
        assertEquals(1, controller.getMatchCount());
        // Le listener d'édition suit la NOUVELLE session : une mutation
        // y déclenche le recalcul différé (2 occurrences ensuite).
        nouvelle.replaceRange(0, 0, "alpha ");
        idleMainLooper();
        assertEquals(2, controller.getMatchCount());
    }

    @Test
    public void fermeturePurgeLeSurlignage_etLaRequêteSurvit() {
        controller.setQuery("alpha");
        controller.refresh();
        controller.close();
        assertTrue(view.find.highlights.isEmpty());
        assertEquals("la requête survit à la fermeture", "alpha",
                controller.getQuery());
    }
}
