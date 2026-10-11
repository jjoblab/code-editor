package jo.codeeditor.find;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de la correspondance rechercher/remplacer.
 */
class FindReplaceTest {

    @Test
    void findMatches_caseSensitive() {
        List<Match> m = FindReplace.findMatches("Hello hello HELLO", "hello",
            new FindOptions(true, false, false));
        assertEquals(1, m.size());
        assertEquals(6, m.get(0).start);
        assertEquals(11, m.get(0).end);
    }

    @Test
    void findMatches_caseInsensitive() {
        List<Match> m = FindReplace.findMatches("Hello hello HELLO", "hello",
            new FindOptions(false, false, false));
        assertEquals(3, m.size());
    }

    @Test
    void findMatches_wholeWord() {
        List<Match> m = FindReplace.findMatches("hello helloworld hello", "hello",
            new FindOptions(true, true, false));
        assertEquals(2, m.size());
        assertEquals(0, m.get(0).start);
        assertEquals(5, m.get(0).end);
        // "hello helloworld hello" — le second "hello" commence à 17
        assertEquals(17, m.get(1).start);
    }

    @Test
    void findMatches_regex() {
        List<Match> m = FindReplace.findMatches("abc 123 def 456", "\\d+",
            new FindOptions(false, false, true));
        assertEquals(2, m.size());
        assertEquals(4, m.get(0).start);
        assertEquals(7, m.get(0).end);
    }

    @Test
    void findMatches_regex_invalid_returnsEmpty() {
        List<Match> m = FindReplace.findMatches("hello", "[invalid",
            new FindOptions(false, false, true));
        assertTrue(m.isEmpty());
    }

    @Test
    void findMatches_emptyQuery() {
        List<Match> m = FindReplace.findMatches("hello", "", new FindOptions());
        assertTrue(m.isEmpty());
    }

    @Test
    void findMatches_noMatch() {
        List<Match> m = FindReplace.findMatches("hello world", "xyz", new FindOptions());
        assertTrue(m.isEmpty());
    }

    @Test
    void findMatches_multipleOverlapping() {
        List<Match> m = FindReplace.findMatches("aaaaa", "aaa",
            new FindOptions(true, false, false));
        // L'implémentation peut trouver des correspondances qui se chevauchent
        assertTrue(m.size() >= 2, "Expected at least 2 matches, got " + m.size());
    }

    @Test
    void matchIndexFrom_wraps() {
        List<Match> m = FindReplace.findMatches("a b a b", "a",
            new FindOptions(true, false, false));
        assertEquals(2, m.size());
        // Depuis un caret après la dernière correspondance → boucle sur 0
        int idx = FindReplace.matchIndexFrom(m, 10);
        assertEquals(0, idx);
    }

    @Test
    void matchIndexFrom_exact() {
        List<Match> m = FindReplace.findMatches("a b a b", "a",
            new FindOptions(true, false, false));
        int idx = FindReplace.matchIndexFrom(m, 0);
        assertEquals(0, idx);
    }

    @Test
    void matchIndexFrom_between() {
        List<Match> m = FindReplace.findMatches("a b a b", "a",
            new FindOptions(true, false, false));
        // Caret à 2 (entre le premier 'a' à 0 et le second 'a' à 4)
        int idx = FindReplace.matchIndexFrom(m, 2);
        assertEquals(1, idx); // seconde correspondance
    }

    @Test
    void matchIndexFrom_empty_returnsNeg1() {
        int idx = FindReplace.matchIndexFrom(List.of(), 0);
        assertEquals(-1, idx);
    }
}
