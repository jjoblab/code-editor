package jo.codeeditor.view.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jo.codeeditor.find.Match;

/**
 * Tests JVM purs de l'état de rendu des surlignages de recherche
 * (plan 01, étape 3) — zéro vue, zéro Robolectric : l'invariant
 * « index courant toujours dans les bornes » et la copie défensive
 * sont possédés et vérifiés ici.
 */
public class FindStateTest {

    private static List<Match> matches(int... offsets) {
        List<Match> list = new ArrayList<>();
        for (int i = 0; i < offsets.length; i += 2) {
            list.add(new Match(offsets[i], offsets[i + 1]));
        }
        return list;
    }

    @Test
    public void set_copieLaListe_sourceMutableSansEffet() {
        FindState state = new FindState();
        List<Match> source = matches(0, 2, 5, 9);
        state.set(source, 1);
        source.add(new Match(100, 110));
        assertEquals(2, state.highlights.size());
    }

    @Test
    public void set_indexHorsBornesRameneADerniereOccurrence() {
        FindState state = new FindState();
        state.set(matches(0, 2, 5, 9, 12, 15), 99);
        // Invariant : jamais de teinte « courante » sur un index absent.
        assertEquals(2, state.currentIndex);
    }

    @Test
    public void set_indexNegatifAucuneCourante() {
        FindState state = new FindState();
        state.set(matches(0, 2), -3);
        assertEquals(-1, state.currentIndex);
        assertTrue(!state.isEmpty());
    }

    @Test
    public void set_listeVideAucuneCourante() {
        FindState state = new FindState();
        state.set(matches(0, 2), 0);
        state.set(new ArrayList<>(), 0);
        assertTrue(state.isEmpty());
        assertEquals(-1, state.currentIndex);

        state.set(null, 3);
        assertTrue(state.isEmpty());
        assertEquals(-1, state.currentIndex);
    }

    @Test
    public void clear_purgeOccurrencesEtCourante() {
        FindState state = new FindState();
        state.set(Arrays.asList(new Match(1, 4), new Match(8, 12)), 1);
        assertEquals(1, state.currentIndex);

        state.clear();
        assertTrue(state.isEmpty());
        assertEquals(-1, state.currentIndex);
    }

    @Test
    public void set_indexExactResteInchange() {
        FindState state = new FindState();
        state.set(matches(0, 3, 7, 11), 1);
        assertEquals(1, state.currentIndex);
    }
}
