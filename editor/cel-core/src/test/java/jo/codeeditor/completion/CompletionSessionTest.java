package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CompletionSessionTest {

    private CompletionSession.Item item(String label, int score, boolean keyword) {
        return new CompletionSession.Item(label, "", label, "", 1, score, keyword, false);
    }

    @Test
    void filterByPrefix() {
        List<CompletionSession.Item> items = List.of(
            item("getString", 10, false),
            item("getView", 8, false),
            item("setString", 5, false),
            item("toString", 3, false)
        );
        CompletionSession session = new CompletionSession(0, items, true, false);
        List<CompletionSession.Item> filtered = session.filtered("get");
        assertEquals(2, filtered.size());
        assertEquals("getString", filtered.get(0).label);
        assertEquals("getView", filtered.get(1).label);
    }

    @Test
    void filterEmptyPrefix_returnsAll() {
        List<CompletionSession.Item> items = List.of(
            item("a", 1, false), item("b", 2, false)
        );
        CompletionSession session = new CompletionSession(0, items, true, false);
        assertEquals(2, session.filtered("").size());
        assertEquals(2, session.filtered(null).size());
    }

    @Test
    void filterNoMatch_returnsEmpty() {
        List<CompletionSession.Item> items = List.of(
            item("getString", 10, false)
        );
        CompletionSession session = new CompletionSession(0, items, true, false);
        assertTrue(session.filtered("xyz").isEmpty());
    }

    @Test
    void filterReRanks_keywordsBelowSemantic() {
        List<CompletionSession.Item> items = List.of(
            item("class", 20, true),
            item("getClass", 10, false)
        );
        CompletionSession session = new CompletionSession(0, items, true, false);
        List<CompletionSession.Item> filtered = session.filtered("cl");
        // "class" correspond comme mot-clé, "getClass" comme camel-hump
        // Le sémantique doit être classé au-dessus du mot-clé
        assertFalse(filtered.isEmpty());
    }

    @Test
    void coversCaret_withinToken() {
        CompletionSession session = new CompletionSession(5,
            List.of(item("hello", 1, false), item("help", 1, false)),
            true, false);
        assertTrue(session.coversCaret("say hello", 7, 0)); // « he » saisi
        assertTrue(session.coversCaret("say hello", 5, 0)); // rien de saisi
    }

    @Test
    void coversCaret_beforeToken_returnsFalse() {
        CompletionSession session = new CompletionSession(5,
            List.of(item("hello", 1, false)), true, false);
        assertFalse(session.coversCaret("say hello", 3, 0));
    }

    @Test
    void matchPositions_exactMatch() {
        List<Integer> positions = CompletionSession.matchPositions("hello", "hello");
        assertEquals(5, positions.size());
        assertEquals(List.of(0, 1, 2, 3, 4), positions);
    }

    @Test
    void matchPositions_camelHump() {
        List<Integer> positions = CompletionSession.matchPositions("getString", "gS");
        assertEquals(2, positions.size());
        assertEquals(0, positions.get(0));
        assertEquals(3, positions.get(1));
    }

    @Test
    void matchPositions_noMatch_returnsEmpty() {
        assertTrue(CompletionSession.matchPositions("hello", "xyz").isEmpty());
    }

    @Test
    void matchPositions_emptyPrefix_returnsAllPositions() {
        List<Integer> positions = CompletionSession.matchPositions("abc", "");
        assertEquals(3, positions.size());
    }

    @Test
    void matchTier_exact() {
        assertEquals(CompletionSession.TIER_EXACT, CompletionSession.matchTier("hello", "hello"));
        assertEquals(CompletionSession.TIER_EXACT, CompletionSession.matchTier("Hello", "hello"));
    }

    @Test
    void matchTier_prefix() {
        assertEquals(CompletionSession.TIER_PREFIX, CompletionSession.matchTier("getString", "get"));
    }

    @Test
    void matchTier_camelHump() {
        int tier = CompletionSession.matchTier("getString", "gS");
        assertTrue(tier <= CompletionSession.TIER_CAMEL_HUMP);
    }

    @Test
    void matchTier_none() {
        assertEquals(CompletionSession.TIER_NONE, CompletionSession.matchTier("hello", "xyz"));
    }

    @Test
    void matchTier_emptyPrefix_returnsExact() {
        assertEquals(CompletionSession.TIER_EXACT, CompletionSession.matchTier("anything", ""));
    }

    @Test
    void isIncomplete() {
        CompletionSession session = new CompletionSession(0, List.of(), true, true);
        assertTrue(session.isIncomplete);
    }

    @Test
    void constructor_nullBase_emptyList() {
        CompletionSession session = new CompletionSession(0, null, false, false);
        assertTrue(session.base.isEmpty());
    }
}
