package jo.codeeditor.find;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Occurrences entières du mot sous le caret : extraction bidirectionnelle
 * du mot ({@link WordOccurrences#wordAt}), éligibilité (identifiants,
 * jamais les littéraux numériques ni la ponctuation) et collecte des
 * occurrences ENTIÈRES avec les frontières identifiant — {@code _} et
 * {@code $} ne sont PAS des frontières, contrairement au mode « mot
 * entier » de la recherche.
 */
class WordOccurrencesTest {

    @Test
    void wordAt_caretAuMilieuDuMot() {
        // « val|ue » : la plage couvre le mot entier.
        int[] r = WordOccurrences.wordAt("int value = 1;", 6);
        assertNotNull(r);
        assertEquals(4, r[0]);
        assertEquals(9, r[1]);
    }

    @Test
    void wordAt_caretJusteApresLeMot() {
        // Le caractère identifiant juste AVANT a priorité : « value| »
        // reste rattaché à « value ».
        int[] r = WordOccurrences.wordAt("int value = 1;", 9);
        assertNotNull(r);
        assertEquals(4, r[0]);
        assertEquals(9, r[1]);
    }

    @Test
    void wordAt_caretJusteAvantLeMot() {
        // « |value » (après un blanc) : le mot suivant est pris.
        int[] r = WordOccurrences.wordAt("int value = 1;", 4);
        assertNotNull(r);
        assertEquals(4, r[0]);
        assertEquals(9, r[1]);
    }

    @Test
    void wordAt_caretSurBlancOuPonctuation_null() {
        // Position 3 = juste APRÈS « int » : rattaché au mot par
        // conception. Un vrai blanc isolé : entre « = » et « 1 ».
        assertNull(WordOccurrences.wordAt("int value = 1;", 11));  // blanc
        assertNull(WordOccurrences.wordAt("int value = 1;", 10));  // « = »
        assertNull(WordOccurrences.wordAt("int value = 1;", 14));  // « ; » en fin
        assertNull(WordOccurrences.wordAt("", 0));                 // vide
    }

    @Test
    void wordAt_underscoreEtDollarSontDesCaracteresDeMot() {
        // « mon_mot$ » est UN mot : le caret sur le « _ » reste dedans.
        int[] r = WordOccurrences.wordAt("mon_mot$", 3);
        assertNotNull(r);
        assertEquals(0, r[0]);
        assertEquals(8, r[1]);
    }

    @Test
    void eligible_identifiantsOuiLitterauxNon() {
        assertTrue(WordOccurrences.isEligibleWord("value"));
        assertTrue(WordOccurrences.isEligibleWord("_private"));
        assertTrue(WordOccurrences.isEligibleWord("$field"));
        assertTrue(WordOccurrences.isEligibleWord("x2"));
        // Littéral numérique : premier chiffre → non éligible.
        assertFalse(WordOccurrences.isEligibleWord("42"));
        // Ponctuation / opérateurs : non éligibles.
        assertFalse(WordOccurrences.isEligibleWord(";"));
        assertFalse(WordOccurrences.isEligibleWord("->"));
        assertFalse(WordOccurrences.isEligibleWord(""));
    }

    @Test
    void findAll_occurrencesEntieresUniquement() {
        // « count » apparaît 2 fois entières (offsets 4 et 31) ;
        // « countdown » et « account » contiennent le mot SANS être
        // des mots entiers.
        List<Match> m = WordOccurrences.findAll(
                "int count = 1; int countdown = count + account;", "count");
        assertEquals(2, m.size());
        assertEquals(4, m.get(0).start);
        assertEquals(9, m.get(0).end);
        assertEquals(31, m.get(1).start);
        assertEquals(36, m.get(1).end);
    }

    @Test
    void findAll_underscoreNestPasUneFrontiere() {
        // Contrairement au mode « mot entier » de la recherche,
        // « mon_mot » ne matche PAS dans « mon_mots » (le « s » rompt la
        // frontière droite) et « mot » ne matche pas non plus (frontière
        // gauche = « _ », un caractère de mot).
        List<Match> m = WordOccurrences.findAll("mon_mot mon_mots mot", "mon_mot");
        assertEquals(1, m.size());
        assertEquals(0, m.get(0).start);
        List<Match> m2 = WordOccurrences.findAll("mon_mot", "mot");
        assertTrue(m2.isEmpty());
    }

    @Test
    void findAll_casseExacte() {
        // « Value » et « value » sont deux mots différents.
        List<Match> m = WordOccurrences.findAll("Value value VALUE", "value");
        assertEquals(1, m.size());
        assertEquals(6, m.get(0).start);
    }

    @Test
    void findAll_occurrencesAuxExtremitesDuTexte() {
        // Le mot en tête et en queue de texte : frontières = extrémités.
        List<Match> m = WordOccurrences.findAll("abc x abc", "abc");
        assertEquals(2, m.size());
        assertEquals(0, m.get(0).start);
        assertEquals(6, m.get(1).start);
    }

    @Test
    void indexOfContaining_retrouveLOccurrenceDuCaret() {
        List<Match> m = WordOccurrences.findAll("a b a b a", "a");
        assertEquals(3, m.size());
        assertEquals(0, WordOccurrences.indexOfContaining(m, 0));
        assertEquals(1, WordOccurrences.indexOfContaining(m, 4));
        assertEquals(2, WordOccurrences.indexOfContaining(m, 8));
        assertEquals(-1, WordOccurrences.indexOfContaining(m, 2));  // entre deux
    }
}
