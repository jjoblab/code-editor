package jo.codeeditor.snippet;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SnippetSessionTest {

    @Test
    void parseSimpleSnippet() {
        var session = SnippetSession.parse("if ($1) {\n    $0\n}", 0);
        assertNotNull(session);
        assertNotNull(session.current());
    }

    @Test
    void parseWithPlaceholders() {
        var session = SnippetSession.parse("for (${1:int i = 0}; ${2:i < 10}; ${3:i++}) {\n    $0\n}", 0);
        assertNotNull(session);
        assertNotNull(session.current());
    }

    @Test
    void nextStepsThroughStops() {
        var session = SnippetSession.parse("$1 then $2 then $0", 0);
        assertNotNull(session);
        var first = session.current();
        assertNotNull(first);
        var second = session.next();
        assertNotNull(second);
    }

    @Test
    void fieldRanges_returnsRanges() {
        var session = SnippetSession.parse("if ($1) {\n    $0\n}", 0);
        assertNotNull(session);
        var ranges = session.fieldRanges();
        assertNotNull(ranges);
    }

    @Test
    void prev_noOpAtFirst() {
        var session = SnippetSession.parse("$1 $2 $0", 0);
        assertNotNull(session);
        var prev = session.prev();
        // At first stop, prev should return null or same
        // Just verify no exception
    }

    @Test
    void parseWithBaseOffset() {
        var session = SnippetSession.parse("$1 + $2 = $0", 10);
        assertNotNull(session);
        var current = session.current();
        assertNotNull(current);
        assertTrue(current.start >= 10);
    }
}
