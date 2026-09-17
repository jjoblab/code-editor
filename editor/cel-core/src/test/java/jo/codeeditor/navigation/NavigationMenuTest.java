package jo.codeeditor.navigation;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests JVM pur pour les helpers statiques de filtrage de symboles de
 * {@link NavigationMenu}.
 *
 * <p>Le popup go-to-symbol lui-même est dessiné sur Canvas dans la couche
 * View, mais la logique de filtre (préfixe + sous-séquence camel-hump)
 * est du Java pur et testée ici.
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
        // "gS" doit correspondre à "getString" via camel-hump (g à 0, S à 1).
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
        // "AL" doit correspondre à "ArrayList" via camel-hump.
        var filtered = NavigationMenu.filter(symbols, "AL");
        assertEquals(1, filtered.size());
        assertEquals("ArrayList", filtered.get(0).name);
        // "HM" doit correspondre à "HashMap".
        var filtered2 = NavigationMenu.filter(symbols, "HM");
        assertEquals(1, filtered2.size());
        assertEquals("HashMap", filtered2.get(0).name);
    }

    @Test
    void filter_underscoreWordStart() {
        var symbols = Arrays.asList(
            new NavigationMenu.Symbol("get_foo", 0, "method", ""),
            new NavigationMenu.Symbol("other", 10, "field", ""));
        // "gf" doit correspondre à "get_foo" — g à 0 (début de mot), f à 4 (après _).
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
        // Règle de correspondance : l'algo exige pi==0 OU isWordStart OU
        // !prevMatched. "AL" correspond à "ArrayList" — A à 0 (pi==0),
        // S à 5 (début de mot, majuscule). En revanche, un caractère qui
        // suit directement une majuscule appariée (prevMatched=true) et
        // qui n'est pas un début de mot ne peut pas être apparié.
        assertTrue(NavigationMenu.isCamelHumpSubsequence("ArrayList", "AL"));
    }

    @Test
    void isCamelHumpSubsequence_strictContiguousDoesNotMatch() {
        // "get" ne correspond PAS à "getString" via camel-hump — l'algo
        // exige des débuts de mot ou des correspondances non contiguës après
        // le premier caractère. La méthode filter rattrape ce cas via
        // startsWith.
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
