package jo.codeeditor.view;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.TimeUnit;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.find.Match;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.chrome.EditorSearchController;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Surbrillance des occurrences entières du mot sous le caret (4.4.0) :
 * allumage au repos (débounce 300 ms), règle « au moins deux »,
 * inéligibilité des littéraux numériques et de la ponctuation,
 * extinction sur sélection en plage, cédage devant la session de
 * recherche OUVERTE et devant un hôte propriétaire de la voie,
 * reprise après fermeture/libération, coupure des très gros documents,
 * purge au changement de session, bascule publique.
 *
 * <p>Le calcul est différé (postDelayed 300 ms) — les tests avancent le
 * looper shadow au-delà du délai ({@code idle()} seul n'exécute pas les
 * tâches différées).</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorWordOccurrencesTest {

    /** « count » en mot entier aux offsets 4, 19 et 27 ; « int » @0 et @15. */
    private static final String DOC =
        "int count = 1;\n"          // 0..14
        + "int count = count + 2;\n";  // 15..37

    private EditorView view;
    private EditorSession session;

    @Before
    public void setUp() {
        Context ctx = RuntimeEnvironment.getApplication();
        view = new EditorView(ctx);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
    }

    private void idleAuRepos() {
        Shadows.shadowOf(Looper.getMainLooper())
                .idleFor(450, TimeUnit.MILLISECONDS);
    }

    private void caret(int offset) {
        session.setSelection(offset);
    }

    // ── Allumage ─────────────────────────────────────────────────────

    @Test
    public void auReposDuCaret_lesOccurrencesEntieresSallument() {
        caret(6);   // dans le premier « count »
        idleAuRepos();
        assertEquals(3, view.find.highlights.size());
        assertEquals(4, view.find.highlights.get(0).start);
        assertEquals(19, view.find.highlights.get(1).start);
        assertEquals(27, view.find.highlights.get(2).start);
        // L'occurrence du caret est la courante (teinte forte).
        assertEquals(0, view.find.currentIndex);
    }

    @Test
    public void caretJusteApresLeMot_leMotResteRattache() {
        caret(9);   // juste après le premier « count »
        idleAuRepos();
        assertEquals(3, view.find.highlights.size());
        assertEquals(0, view.find.currentIndex);
    }

    @Test
    public void motUnique_aucunSurlignage() {
        EditorSession unique = new EditorSession(EditorDocument.of("seul\nmot\nseul\n"));
        view.setSession(unique);
        unique.setSelection(5);   // « mot », une seule occurrence
        idleAuRepos();
        assertTrue("un mot unique n'allume rien", view.find.highlights.isEmpty());
    }

    @Test
    public void litteralNumeriqueEtPonctuation_neSallumentPas() {
        caret(12);  // « 1 »
        idleAuRepos();
        assertTrue(view.find.highlights.isEmpty());
        caret(10);  // « = »
        idleAuRepos();
        assertTrue(view.find.highlights.isEmpty());
    }

    @Test
    public void selectionEnPlage_eteint() {
        caret(6);
        idleAuRepos();
        assertEquals(3, view.find.highlights.size());
        session.setSelection(jo.codeeditor.document.Selection.range(4, 9));   // plage sur le premier « count »
        idleAuRepos();
        assertTrue("une sélection en plage n'a pas de « mot sous le caret »",
                view.find.highlights.isEmpty());
    }

    // ── Cédage de la voie ────────────────────────────────────────────

    @Test
    public void laRechercheOuverteGardeLaPriorite_puisRepriseALaFermeture() {
        EditorSearchController recherche = new EditorSearchController(view);
        recherche.setQuery("int");
        recherche.refresh();
        // Le caret va sur « count » : le producteur interne doit céder.
        caret(6);
        idleAuRepos();
        assertEquals("les surlignages de la recherche survivent", 2,
                view.find.highlights.size());
        assertEquals(0, view.find.highlights.get(0).start);
        assertEquals(15, view.find.highlights.get(1).start);
        // Fermeture : la voie est rendue, le prochain déplacement du
        // caret ré-allume les occurrences du mot.
        recherche.close();
        assertTrue(view.find.highlights.isEmpty());
        caret(21);   // dans le second « count »
        idleAuRepos();
        assertEquals(3, view.find.highlights.size());
        assertEquals(1, view.find.currentIndex);
    }

    @Test
    public void hoteProprietaire_laVoieLuiResteJusquaLibération() {
        view.setFindHighlights(List.of(new Match(0, 3)), 0);
        caret(6);
        idleAuRepos();
        assertEquals("l'hôte garde ses surlignages", 1, view.find.highlights.size());
        assertEquals(0, view.find.highlights.get(0).start);
        // Libération par poussée vide : le producteur reprend.
        view.setFindHighlights(null, -1);
        caret(21);
        idleAuRepos();
        assertEquals(3, view.find.highlights.size());
    }

    // ── Coupures ─────────────────────────────────────────────────────

    @Test
    public void tresGrosDocument_coupe() {
        // 50 001 lignes : au-delà du seuil isLarge (50 000).
        StringBuilder gros = new StringBuilder();
        for (int i = 0; i < 50_001; i++) gros.append("count\n");
        EditorSession grande = new EditorSession(EditorDocument.of(gros.toString()));
        view.setSession(grande);
        grande.setSelection(7);
        idleAuRepos();
        assertTrue("le balayage complet est coupé sur les très gros documents",
                view.find.highlights.isEmpty());
    }

    @Test
    public void basculePublique_purgeEtRetablit() {
        assertTrue(view.isWordOccurrencesEnabled());
        caret(6);
        idleAuRepos();
        assertEquals(3, view.find.highlights.size());
        view.setWordOccurrencesEnabled(false);
        assertFalse(view.isWordOccurrencesEnabled());
        assertTrue("la désactivation purge la voie", view.find.highlights.isEmpty());
        view.setWordOccurrencesEnabled(true);
        idleAuRepos();
        assertEquals("la réactivation ré-allume au repos suivant", 3,
                view.find.highlights.size());
    }

    @Test
    public void changementDeSession_annuleEtRecalcule() {
        caret(6);
        idleAuRepos();
        assertEquals(3, view.find.highlights.size());
        // Nouvelle session : le caret retombe à 0 sur « autre » (×2).
        EditorSession autre = new EditorSession(EditorDocument.of("autre\nautre\n"));
        view.setSession(autre);
        idleAuRepos();
        assertEquals(2, view.find.highlights.size());
        assertEquals(0, view.find.highlights.get(0).start);
        assertEquals(6, view.find.highlights.get(1).start);
    }

    @Test
    public void editionRecalculeLesOccurrences() {
        caret(6);
        idleAuRepos();
        assertEquals(3, view.find.highlights.size());
        // Remplace le second « count » (offset 19) par « total » : il ne
        // reste que deux occurrences entières, caret dans le troisième.
        session.replaceRangeWithCaret(19, 24, "total", 28);
        idleAuRepos();
        assertEquals(2, view.find.highlights.size());
        assertEquals(4, view.find.highlights.get(0).start);
        assertEquals(27, view.find.highlights.get(1).start);
    }
}
