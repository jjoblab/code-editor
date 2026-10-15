package jo.codeeditor.view.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.completion.CompletionSession;

/**
 * Tests JVM purs de l'objet d'état du popup de complétion (plan 01,
 * étape 2) — AUCUNE vue, AUCUN Robolectric : les invariants de
 * sélection / fenêtre de scroll / visibilité sont possédés et vérifiés
 * ici, ce qui était impossible quand les champs vivaient libres dans
 * EditorView.
 */
public class CompletionStateTest {

    private static CompletionSession.Item item(String label) {
        return new CompletionSession.Item(label, null, label, null,
                1, 0, false, false);
    }

    private static List<CompletionSession.Item> items(String... labels) {
        List<CompletionSession.Item> list = new ArrayList<>();
        for (String l : labels) list.add(item(l));
        return list;
    }

    // ── reset (voie EditorPopupManager.setCompletionItems) ────────

    @Test
    public void reset_listeVide_popupInvisible() {
        CompletionState state = new CompletionState();
        state.reset(items("alpha"), 0, "al");
        assertTrue(state.visible);

        state.reset(new ArrayList<>(), 0, "");
        assertFalse(state.visible);
        assertTrue(state.items.isEmpty());
        assertEquals(0, state.selected);
        assertEquals(0, state.scrollOffset);
    }

    @Test
    public void reset_listeNonVide_selectionZeroPrefixNormalise() {
        CompletionState state = new CompletionState();
        state.reset(items("alpha", "beta"), 3, null);
        assertTrue(state.visible);
        assertEquals(0, state.selected);
        assertEquals("", state.prefix);
        assertEquals(3, state.tokenStart);
    }

    // ── refilter (chemin rapide idem-token) ───────────────────────

    @Test
    public void refilter_itemSelectionneSurvitParLibelle() {
        CompletionState state = new CompletionState();
        state.reset(items("alpha", "bravo", "charlie"), 0, "");
        state.select(1, 3); // sélection = bravo

        state.refilter(items("alpha", "charlie", "bravo"), 0, "b", 3);
        assertEquals(2, state.selected);
        assertTrue(state.visible);
    }

    @Test
    public void refilter_selectionDisparueRetombeAZero() {
        CompletionState state = new CompletionState();
        state.reset(items("alpha", "bravo"), 0, "");
        state.select(1, 2); // bravo

        state.refilter(items("alpha", "zulu"), 0, "", 2);
        assertEquals(0, state.selected);
    }

    @Test
    public void refilter_fenetreSuitSelectionVersLeBas() {
        CompletionState state = new CompletionState();
        state.reset(items("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"), 0, "");
        state.select(7, 4); // sélection sous la fenêtre → recentrée
        assertEquals(4, state.scrollOffset); // 4..7 visibles

        // Refiltrage du même jeu (copie defensive) : le libellé
        // sélectionné survit, la fenêtre se recentre de la même façon.
        state.scrollOffset = 0;
        state.refilter(new ArrayList<>(state.items), 0, "", 4);
        assertEquals(7, state.selected);
        assertEquals(4, state.scrollOffset);
    }

    // ── select (flèches haut/bas) ─────────────────────────────────

    @Test
    public void select_bornesDeListeRefusees() {
        CompletionState state = new CompletionState();
        state.reset(items("seul"), 0, "");
        assertFalse(state.select(-1, 1));
        assertFalse(state.select(1, 1));
        assertEquals(0, state.selected);
    }

    @Test
    public void select_fenetreSuiviDansLesDeuxSens() {
        CompletionState state = new CompletionState();
        state.reset(items("a", "b", "c", "d", "e", "f", "g", "h"), 0, "");
        // fenêtre de 3 : offsets valides 0..5
        assertTrue(state.select(3, 3)); // sélection 3, fenêtre 0..2 → recentre
        assertEquals(3, state.selected);
        assertEquals(1, state.scrollOffset); // 1..3 visibles
        assertTrue(state.select(1, 3)); // sélection 4, fenêtre 2..4
        assertEquals(2, state.scrollOffset);
        assertTrue(state.select(-4, 3)); // sélection 0, la fenêtre REMONTE
        assertEquals(0, state.selected);
        assertEquals(0, state.scrollOffset);
    }

    // ── scrollBy (drag vertical de la liste) ──────────────────────

    @Test
    public void scrollBy_deplaceFenetreEtSelectionEnsemble() {
        CompletionState state = new CompletionState();
        state.reset(items("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"), 0, "");
        assertTrue(state.scrollBy(3, 4));
        assertEquals(3, state.scrollOffset);
        assertEquals(3, state.selected);

        // Butee basse : offset max = 10 - 4 = 6, sélection max = 9.
        assertTrue(state.scrollBy(100, 4));
        assertEquals(6, state.scrollOffset);
        assertEquals(9, state.selected);

        // Déjà en bute : aucun mouvement, retour false.
        assertFalse(state.scrollBy(1, 4));
        assertEquals(6, state.scrollOffset);
        assertEquals(9, state.selected);
    }

    // ── clear / softHide ──────────────────────────────────────────

    @Test
    public void clear_videToutEtInvalideLeCacheDeBase() {
        CompletionState state = new CompletionState();
        state.reset(items("alpha"), 0, "a");
        state.baseItems = items("alpha", "beta");
        state.baseTokenStart = 0;

        state.clear();
        assertFalse(state.visible);
        assertTrue(state.items.isEmpty());
        assertTrue(state.baseItems.isEmpty());
        assertEquals(-1, state.baseTokenStart);
    }

    @Test
    public void softHide_gardeItemsEtCache() {
        CompletionState state = new CompletionState();
        state.reset(items("alpha"), 0, "a");
        state.baseItems = items("alpha", "beta");
        state.baseTokenStart = 0;

        state.softHide();
        assertFalse(state.visible);
        assertEquals(1, state.items.size());
        assertEquals(2, state.baseItems.size());
        assertEquals(0, state.baseTokenStart);
    }
}
