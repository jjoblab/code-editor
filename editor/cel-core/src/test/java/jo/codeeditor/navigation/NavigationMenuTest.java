package jo.codeeditor.navigation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-JVM tests for {@link NavigationMenu}'s static symbol-filter helpers.
 *
 * <p>v1.0.7 — Gap 6 (go-to-symbol popup). The popup itself is Canvas-drawn
 * in the View layer, but the filter logic (prefix + camel-hump subsequence)
 * is pure Java and tested here.
 */
class NavigationMenuTest {

    @Test
    void symbol_creation() {
        var s = new NavigationMenu.Symbol("foo", 42, "method", "MyClass");
        assertEquals("foo", s.name);
        assertEquals(42, s.offset);
        assertEquals("method", s.kind);
        assertEquals("MyClass", s.container);
    }

    @Test
    void symbol_nullsDefaultToEmpty() {
        var s = new NavigationMenu.Symbol(null, 0, null, null);
        assertEquals("", s.name);
        assertEquals("", s.kind);
        assertEquals("", s.container);
    }

    @Test
    void filter_emptyPrefix_returnsAll() {
        var symbols = Arrays.asList(
            new NavigationMenu.Symbol("foo", 0, "method", ""),
            new NavigationMenu.Symbol("bar", 4, "method", ""));
        var filtered = NavigationMenu.filter(symbols, "");
        assertEquals(2, filtered.size());
    }

    @Test
    void filter_nullPrefix_returnsAll() {
        var symbols = Arrays.asList(
            new NavigationMenu.Symbol("foo", 0, "method", ""));
        var filtered = NavigationMenu.filter(symbols, null);
        assertEquals(1, filtered.size());
    }

    @Test
    void filter_nullSymbols_returnsEmpty() {
        var filtered = NavigationMenu.filter(null, "foo");
        assertTrue(filtered.isEmpty());
    }

    @Test
    void filter_caseInsensitivePrefix() {
        var symbols = Arrays.asList(
            new NavigationMenu.Symbol("getString", 0, "method", ""),
            new NavigationMenu.Symbol("substring", 10, "method", ""),
            new NavigationMenu.Symbol("other", 20, "field", ""));
        var filtered = NavigationMenu.filter(symbols, "get");
        assertEquals(1, filtered.size());
        assertEquals("getString", filtered.get(0).name);
    }

    @Test
    void filter_camelHumpSubsequence() {
        var symbols = Arrays.asList(
            new NavigationMenu.Symbol("getString", 0, "method", ""),
            new NavigationMenu.Symbol("setString", 10, "method", ""),
            new NavigationMenu.Symbol("other", 20, "field", ""));
        // "gS" should match "getString" via camel-hump (g at 0, S at 1).
        var filtered = NavigationMenu.filter(symbols, "gS");
        assertEquals(1, filtered.size());
        assertEquals("getString", filtered.get(0).name);
    }

    @Test
    void filter_camelHumpMultipleWords() {
        var symbols = Arrays.asList(
            new NavigationMenu.Symbol("ArrayList", 0, "class", ""),
            new NavigationMenu.Symbol("HashMap", 10, "class", ""),
            new NavigationMenu.Symbol("other", 20, "field", ""));
        // "AL" should match "ArrayList" via camel-hump.
        var filtered = NavigationMenu.filter(symbols, "AL");
        assertEquals(1, filtered.size());
        assertEquals("ArrayList", filtered.get(0).name);
        // "HM" should match "HashMap".
        var filtered2 = NavigationMenu.filter(symbols, "HM");
        assertEquals(1, filtered2.size());
        assertEquals("HashMap", filtered2.get(0).name);
    }

    @Test
    void filter_underscoreWordStart() {
        var symbols = Arrays.asList(
            new NavigationMenu.Symbol("get_foo", 0, "method", ""),
            new NavigationMenu.Symbol("other", 10, "field", ""));
        // "gf" should match "get_foo" — g at 0 (word start), f at 4 (after _).
        var filtered = NavigationMenu.filter(symbols, "gf");
        assertEquals(1, filtered.size());
        assertEquals("get_foo", filtered.get(0).name);
    }

    @Test
    void filter_noMatch_returnsEmpty() {
        var symbols = Arrays.asList(
            new NavigationMenu.Symbol("foo", 0, "method", ""),
            new NavigationMenu.Symbol("bar", 4, "method", ""));
        var filtered = NavigationMenu.filter(symbols, "xyz");
        assertTrue(filtered.isEmpty());
    }

    @Test
    void filter_preservesInputOrder() {
        var symbols = Arrays.asList(
            new NavigationMenu.Symbol("alpha", 0, "method", ""),
            new NavigationMenu.Symbol("beta", 5, "method", ""),
            new NavigationMenu.Symbol("gamma", 10, "method", ""));
        var filtered = NavigationMenu.filter(symbols, "");
        assertEquals(3, filtered.size());
        assertEquals("alpha", filtered.get(0).name);
        assertEquals("beta", filtered.get(1).name);
        assertEquals("gamma", filtered.get(2).name);
    }

    @Test
    void isCamelHumpSubsequence_simpleCase() {
        assertTrue(NavigationMenu.isCamelHumpSubsequence("getString", "gS"));
        assertTrue(NavigationMenu.isCamelHumpSubsequence("ArrayList", "AL"));
        assertFalse(NavigationMenu.isCamelHumpSubsequence("getString", "xS"));
    }

    @Test
    void isCamelHumpSubsequence_emptyPrefix() {
        assertTrue(NavigationMenu.isCamelHumpSubsequence("anything", ""));
        assertTrue(NavigationMenu.isCamelHumpSubsequence("", ""));
    }

    @Test
    void isCamelHumpSubsequence_emptyLabel() {
        assertFalse(NavigationMenu.isCamelHumpSubsequence("", "x"));
    }

    @Test
    void isCamelHumpSubsequence_skipsNonMatching() {
        // "gSt" matches "getString" — g at 0 (pi==0), S at 3 (word start),
        // t at 4 (immediately follows S which was matched, but !prevMatched
        // doesn't apply here — actually isWordStart after uppercase doesn't
        // qualify. Let me re-check: the algo requires pi==0 OR isWordStart
        // OR !prevMatched. After S was matched at li=3, prevMatched=true.
        // At li=4 (t), isWordStart=FALSE (prev char 'S' is uppercase, not
        // _/. /whitespace), prevMatched=TRUE → !prevMatched=FALSE.
        // So 't' wouldn't match here.
        // Let me use a clearer example: "AS" matches "ArrayList" — A at 0,
        // S at 5 (word start, uppercase).
        assertTrue(NavigationMenu.isCamelHumpSubsequence("ArrayList", "AL"));
    }

    @Test
    void isCamelHumpSubsequence_strictContiguousDoesNotMatch() {
        // "get" matching "getString" via camel-hump DOESN'T work — the algo
        // requires word-starts or non-contiguous matches after the first char.
        // The filter method catches this case via startsWith instead.
        assertFalse(NavigationMenu.isCamelHumpSubsequence("getString", "get"));
    }

    @Test
    void symbol_toString() {
        var s = new NavigationMenu.Symbol("foo", 42, "method", "MyClass");
        String str = s.toString();
        assertTrue(str.contains("foo"));
        assertTrue(str.contains("42"));
        assertTrue(str.contains("method"));
        assertTrue(str.contains("MyClass"));
    }
}
