package jo.codeeditor.completion;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests par tables de l'appariement unifié : paliers (échelle à 6
 * niveaux), identifiant de tête, positions de surbrillance et groupes.
 *
 * <p>La table couvre explicitement : libellés à signature (parenthèses,
 * paramètres typés), génériques, {@code $}, {@code _}, noms XML
 * qualifiés et ressources ({@code android:layout_width},
 * {@code @string/foo}), noms pleinement qualifiés, casse mixte, et le
 * préfixe plus long que le libellé.</p>
 */
class CompletionMatcherTest {

    // ── Table de paliers (libellé, préfixe, palier attendu) ────────

    /** Une ligne de la table de paliers. */
    private static void assertTier(int expected, String label, String prefix) {
        int actual = CompletionMatcher.tier(label, null, prefix);
        assertEquals(expected, actual,
                "palier de « " + prefix + " » sur « " + label + " »");
    }

    @Test
    void table_paliers_signatures() {
        // Le libellé entier ne fait plus foi : l'identifiant de tête.
        assertTier(CompletionMatcher.TIER_EXACT, "Text", "Text");
        assertTier(CompletionMatcher.TIER_EXACT, "Text()", "Text");
        assertTier(CompletionMatcher.TIER_EXACT, "Text(text: String)", "Text");
        assertTier(CompletionMatcher.TIER_EXACT_CI, "Text(text: String)", "text");
        assertTier(CompletionMatcher.TIER_PREFIX, "TextField()", "Text");
        assertTier(CompletionMatcher.TIER_PREFIX, "TextView", "Text");
        assertTier(CompletionMatcher.TIER_PREFIX_CI, "TextField()", "text");
        assertTier(CompletionMatcher.TIER_PREFIX_CI, "TextView", "tEXt");
        assertTier(CompletionMatcher.TIER_EXACT, "foo()", "foo");
        assertTier(CompletionMatcher.TIER_PREFIX, "fooBar()", "foo");
        assertTier(CompletionMatcher.TIER_EXACT_CI, "Foo", "foo");
        assertTier(CompletionMatcher.TIER_EXACT, "foo(int a, int b)", "foo");
        // Le préfixe avec parenthèse ouverte passe par le libellé
        // entier (relâche limitée aux paliers exact/préfixe).
        assertTier(CompletionMatcher.TIER_PREFIX, "Text()", "Text(");
    }

    @Test
    void table_paliers_generiquesEtSpeciaux() {
        assertTier(CompletionMatcher.TIER_PREFIX, "List<T>", "Lis");
        assertTier(CompletionMatcher.TIER_EXACT_CI, "Map<K, V>", "map");
        assertTier(CompletionMatcher.TIER_PREFIX_CI, "Map<K, V>", "ma");
        assertTier(CompletionMatcher.TIER_PREFIX, "$foo", "$f");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "foo_bar", "fb");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "foo_bar", "fob");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "get_layout_width", "glw");
    }

    @Test
    void table_paliers_xml() {
        // Noms XML qualifiés : les deux-points font partie du nom tant
        // qu'un identifiant suit.
        assertTier(CompletionMatcher.TIER_PREFIX, "android:layout_width", "android");
        assertTier(CompletionMatcher.TIER_PREFIX, "android:layout_width", "android:la");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "android:layout_width", "alw");
        // Ressource : @ et / appartiennent au nom.
        assertTier(CompletionMatcher.TIER_PREFIX, "@string/foo", "@s");
        assertTier(CompletionMatcher.TIER_EXACT, "@string/foo", "@string/foo");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "@string/foo", "str");
        // Kotlin « x: Int » : l'identifiant s'arrête à « x » — mais le
        // libellé ENTIER reste accepté comme exact (règle C.1).
        assertTier(CompletionMatcher.TIER_EXACT, "x: Int", "x");
        assertTier(CompletionMatcher.TIER_EXACT, "x: Int", "x: Int");
    }

    @Test
    void table_paliers_camelHumps() {
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "getString", "gS");
        assertTier(CompletionMatcher.TIER_PREFIX, "getString", "get");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "getString", "etS");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "getTextView", "gtv");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "LazyListModifier", "LLM");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "toString", "tS");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "getView", "gV");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "HTMLParser", "HP");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "HTMLParser", "hp");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "com.example.Foo", "cF");
        assertTier(CompletionMatcher.TIER_CAMEL_HUMP, "com.example.Foo", "cef");
        // La signature ne participe JAMAIS au flou : les humps ne
        // traversent pas les parenthèses de « Text(text: String) ».
        assertTier(CompletionMatcher.TIER_NONE, "Text(text: String)", "tS");
    }

    @Test
    void table_paliers_sousSequencesEtNuls() {
        assertTier(CompletionMatcher.TIER_SUBSEQUENCE, "parallelStream", "LLM");
        assertTier(CompletionMatcher.TIER_SUBSEQUENCE, "column", "lm");
        assertTier(CompletionMatcher.TIER_NONE, "getString", "xS");
        assertTier(CompletionMatcher.TIER_NONE, "hello", "helloWorld");
        assertTier(CompletionMatcher.TIER_NONE, "setString", "get");
        // Préfixe vide : tout correspond (contrat historique).
        assertTier(CompletionMatcher.TIER_EXACT, "anything", "");
        assertTier(CompletionMatcher.TIER_EXACT, "anything", null);
    }

    @Test
    void table_paliers_filterTextEnRepli() {
        // Le filterText ne sert que si le libellé n'apparie pas.
        assertEquals(CompletionMatcher.TIER_PREFIX,
                CompletionMatcher.tier("toString()", "tostring", "tostr"));
        assertEquals(CompletionMatcher.TIER_NONE,
                CompletionMatcher.tier("toString()", null, "xyz"));
        assertEquals(CompletionMatcher.TIER_EXACT,
                CompletionMatcher.tier("Text()", "Text", "Text"));
        // Le meilleur des trois textes gagne.
        assertEquals(CompletionMatcher.TIER_EXACT_CI,
                CompletionMatcher.tier("Text()", "Text", "text"));
    }

    // ── Identifiant de tête ────────────────────────────────────────

    @Test
    void leadingIdentifier_casNominaux() {
        assertEquals("Text", CompletionMatcher.leadingIdentifier("Text(text: String)"));
        assertEquals("foo", CompletionMatcher.leadingIdentifier("foo()"));
        assertEquals("android:layout_width",
                CompletionMatcher.leadingIdentifier("android:layout_width"));
        assertEquals("@string/foo", CompletionMatcher.leadingIdentifier("@string/foo"));
        assertEquals("x", CompletionMatcher.leadingIdentifier("x: Int"));
        assertEquals("List", CompletionMatcher.leadingIdentifier("List<T>"));
        assertEquals("com.example.Foo", CompletionMatcher.leadingIdentifier("com.example.Foo"));
        assertEquals("foo", CompletionMatcher.leadingIdentifier("foo."));
        assertEquals("$foo", CompletionMatcher.leadingIdentifier("$foo"));
        assertEquals("", CompletionMatcher.leadingIdentifier(""));
        assertEquals("", CompletionMatcher.leadingIdentifier(null));
        assertEquals("foo", CompletionMatcher.leadingIdentifier("foo bar"));
        assertEquals("Map", CompletionMatcher.leadingIdentifier("Map<K, V>"));
    }

    // ── Positions de surbrillance ──────────────────────────────────

    @Test
    void matchPositions_ceQuiAFaitMatcher() {
        assertEquals(List.of(0, 4, 8),
                CompletionMatcher.matchPositionsForItem("LazyListModifier", null, "LLM"));
        assertEquals(List.of(0, 3, 7),
                CompletionMatcher.matchPositionsForItem("getTextView", null, "gtv"));
        assertEquals(List.of(0, 1, 2),
                CompletionMatcher.matchPositionsForItem("getString", null, "get"));
        assertEquals(List.of(0, 1, 2),
                CompletionMatcher.matchPositionsForItem("TextField()", null, "Tex"));
        assertEquals(List.of(0, 1, 2, 3),
                CompletionMatcher.matchPositionsForItem("Text(text: String)", null, "Text"));
        assertEquals(List.of(0, 3),
                CompletionMatcher.matchPositionsForItem("getString()", null, "gS"));
        assertEquals(List.of(0, 1, 2, 3),
                CompletionMatcher.matchPositionsForItem("Text()", null, "text"));
    }

    @Test
    void matchPositions_filterTextSeulementSiAligne() {
        // Le filterText peut sauver un libellé qui n'apparie pas…
        assertEquals(List.of(0, 1),
                CompletionMatcher.matchPositionsForItem("AB", "zz", "zz"));
        // … mais ses indices ne sont reportés sur le libellé que si les
        // deux textes ont la même longueur (sinon : rien en gras, plutôt
        // qu'un gras mensonger).
        assertTrue(CompletionMatcher.matchPositionsForItem("ABC", "zz", "zz").isEmpty());
        // Aucune correspondance : rien en gras.
        assertTrue(CompletionMatcher.matchPositionsForItem("hello", null, "xyz").isEmpty());
    }

    @Test
    void matchPositions_prefixeVide_toutesLesPositions() {
        assertEquals(List.of(0, 1, 2),
                CompletionMatcher.matchPositions("abc", ""));
        assertEquals(List.of(0, 1, 2),
                CompletionMatcher.matchPositionsForItem("abc", null, ""));
    }

    // ── Groupes ────────────────────────────────────────────────────

    @Test
    void groupes_resoluAvantMotCleAvantSnippetAvantMotDuBuffer() {
        assertEquals(CompletionMatcher.GROUP_RESOLVED, CompletionMatcher.group(false, false, 2));
        assertEquals(CompletionMatcher.GROUP_RESOLVED, CompletionMatcher.group(false, false, 7));
        assertEquals(CompletionMatcher.GROUP_RESOLVED, CompletionMatcher.group(false, false, 0));
        assertEquals(CompletionMatcher.GROUP_KEYWORD, CompletionMatcher.group(true, false, 14));
        assertEquals(CompletionMatcher.GROUP_KEYWORD, CompletionMatcher.group(false, false, 14));
        assertEquals(CompletionMatcher.GROUP_SNIPPET, CompletionMatcher.group(false, true, 15));
        assertEquals(CompletionMatcher.GROUP_SNIPPET, CompletionMatcher.group(false, false, 15));
        assertEquals(CompletionMatcher.GROUP_BUFFER_WORD, CompletionMatcher.group(false, false, 1));
    }

    @Test
    void matches_délègueAuPalier() {
        assertTrue(CompletionMatcher.matches("Text()", null, "Text"));
        assertTrue(CompletionMatcher.matches("column", null, "lm"));
        assertFalse(CompletionMatcher.matches("hello", null, "helloWorld"));
        assertTrue(CompletionMatcher.matches("hello", null, ""));
    }

    @Test
    void camelHumpSubsequence_retourArrierePremierCaractere() {
        // La première occurrence du premier caractère ne mène à rien :
        // les suivantes sont essayées (« ab » sur « aXbab » — a(3)+b(4)
        // en run contigu, pas a(0) isolé).
        assertTrue(CompletionMatcher.camelHumpSubsequence("aXbab", "ab"));
        assertFalse(CompletionMatcher.camelHumpSubsequence("getString", "gtr"));
        assertTrue(CompletionMatcher.camelHumpSubsequence("getString", "get"));
        assertTrue(CompletionMatcher.camelHumpSubsequence("anything", ""));
        assertFalse(CompletionMatcher.camelHumpSubsequence("", "x"));
    }
}
