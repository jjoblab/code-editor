package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CompletionSessionTest {

    private CompletionSession.Item item(String label, int score, boolean keyword) {
        return new CompletionSession.Item(label, "", label, "", 1, score, keyword, false);
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
    void matcher_camelHump() {
        assertEquals(CompletionMatcher.TIER_CAMEL_HUMP,
                CompletionMatcher.tier("getString", "gS"));
        // Les runs contigus comptent comme humps canoniques.
        assertEquals(CompletionMatcher.TIER_PREFIX,
                CompletionMatcher.tier("getString", "get"));
        // Les initiales de mots passent devant les sous-séquences
        // quelconques : « LLM » est un hump sur « LazyListModifier »…
        assertEquals(CompletionMatcher.TIER_CAMEL_HUMP,
                CompletionMatcher.tier("LazyListModifier", "LLM"));
        // … mais une simple sous-séquence sur « parallelStream ».
        assertEquals(CompletionMatcher.TIER_SUBSEQUENCE,
                CompletionMatcher.tier("parallelStream", "LLM"));
    }
    @Test
    void matcher_filterTextRepli() {
        // Le filterText du fournisseur sert de repli — et peut améliorer
        // le palier du libellé (exact du texte de filtrage contre
        // préfixe insensible du libellé).
        assertEquals(CompletionMatcher.TIER_PREFIX,
                CompletionMatcher.tier("toString()", "tostring", "tost"));
        assertEquals(CompletionMatcher.TIER_EXACT,
                CompletionMatcher.tier("toString()", "tostr", "tostr"));
        assertEquals(CompletionMatcher.TIER_NONE,
                CompletionMatcher.tier("toString()", null, "xyz"));
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
