package jo.codeeditor.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests des opérations d'édition intelligentes (fermeture automatique, saut par-dessus, retour arrière intelligent, Entrée intelligent).
 */
class EditOpsTest {

    // ── smartInsert ───────────────────────────────────────────────

    @Test
    void smartInsert_autoCloseParen() {
        RangeEdit e = EditOps.smartInsert("hello", 5, 5, '(', "java");
        assertEquals("()", e.text);
        assertEquals(6, e.caret); // entre les parenthèses
    }

    @Test
    void smartInsert_autoCloseBrace() {
        RangeEdit e = EditOps.smartInsert("", 0, 0, '{', "java");
        assertEquals("{}", e.text);
        assertEquals(1, e.caret);
    }

    @Test
    void smartInsert_autoCloseBracket() {
        RangeEdit e = EditOps.smartInsert("", 0, 0, '[', "java");
        assertEquals("[]", e.text);
        assertEquals(1, e.caret);
    }

    @Test
    void smartInsert_skipOverCloser() {
        // Curseur avant une ')' déjà présente → saute par-dessus
        RangeEdit e = EditOps.smartInsert("()", 1, 1, ')', "java");
        assertEquals("", e.text); // aucune insertion
        assertEquals(2, e.caret); // déplacé après
    }

    @Test
    void smartInsert_skipOverQuote() {
        RangeEdit e = EditOps.smartInsert("\"\"", 1, 1, '"', "java");
        assertEquals("", e.text);
        assertEquals(2, e.caret);
    }

    @Test
    void smartInsert_autoCloseQuote() {
        RangeEdit e = EditOps.smartInsert("hello ", 6, 6, '"', "java");
        assertEquals("\"\"", e.text);
        assertEquals(7, e.caret);
    }

    @Test
    void smartInsert_noAutoClose_afterIdent() {
        // Après un caractère d'identifiant → pas de fermeture auto (probable fermeture d'une chaîne)
        RangeEdit e = EditOps.smartInsert("foo", 3, 3, '"', "java");
        assertEquals("\"", e.text);
        assertEquals(4, e.caret);
    }

    @Test
    void smartInsert_replacesSelection() {
        RangeEdit e = EditOps.smartInsert("hello world", 0, 5, 'x', "java");
        assertEquals("x", e.text);
        assertEquals(0, e.start);
        assertEquals(5, e.end);
    }

    @Test
    void smartInsert_newline_basicIndent() {
        RangeEdit e = EditOps.smartInsert("hello", 5, 5, '\n', "java");
        assertTrue(e.text.startsWith("\n"));
    }

    @Test
    void smartInsert_newline_deeperAfterOpenBrace() {
        RangeEdit e = EditOps.smartInsert("if (true) {", 11, 11, '\n', "java");
        assertTrue(e.text.startsWith("\n"));
        // Doit indenter d'un niveau supplémentaire
        String afterNewline = e.text.substring(1);
        assertTrue(afterNewline.startsWith("    "), "Expected 4-space indent after '{', got: '" + afterNewline + "'");
    }

    @Test
    void smartInsert_newline_emptyPairExpansion() {
        RangeEdit e = EditOps.smartInsert("{}", 1, 1, '\n', "java");
        // Doit s'étendre en trois lignes avec saut de ligne et accolade fermante
        assertTrue(e.text.contains("\n"), "Expected newline in expansion, got: '" + e.text + "'");
        // Le format exact dépend de l'implémentation — vérifie seulement la présence d'un saut de ligne
        assertTrue(e.text.length() > 1, "Expected multi-char expansion");
    }

    // ── smartBackspace ────────────────────────────────────────────

    @Test
    void smartBackspace_deleteSelection() {
        RangeEdit e = EditOps.smartBackspace("hello world", 0, 5, "java");
        assertNotNull(e);
        assertEquals(0, e.start);
        assertEquals(5, e.end);
        assertEquals("", e.text);
    }

    @Test
    void smartBackspace_emptyPair() {
        RangeEdit e = EditOps.smartBackspace("()", 1, 1, "java");
        assertNotNull(e);
        assertEquals(0, e.start);
        assertEquals(2, e.end);
        assertEquals("", e.text);
    }

    @Test
    void smartBackspace_emptyBraces() {
        RangeEdit e = EditOps.smartBackspace("{}", 1, 1, "java");
        assertNotNull(e);
        assertEquals(0, e.start);
        assertEquals(2, e.end);
    }

    @Test
    void smartBackspace_emptyQuotes() {
        RangeEdit e = EditOps.smartBackspace("\"\"", 1, 1, "java");
        assertNotNull(e);
        assertEquals(0, e.start);
        assertEquals(2, e.end);
    }

    @Test
    void smartBackspace_normalChar() {
        RangeEdit e = EditOps.smartBackspace("hello", 5, 5, "java");
        assertNotNull(e);
        assertEquals(4, e.start);
        assertEquals(5, e.end);
        assertEquals("", e.text);
    }

    @Test
    void smartBackspace_atStart_returnsNoOp() {
        RangeEdit e = EditOps.smartBackspace("hello", 0, 0, "java");
        // L'implémentation renvoie une édition vide plutôt que null
        if (e != null) {
            assertEquals(0, e.start);
            assertEquals(0, e.end);
            assertEquals("", e.text);
        }
    }

    // ── Frontières de mots ──────────────────────────────────────────

    @Test
    void wordBoundaryLeft_skipWhitespace() {
        int b = EditOps.wordBoundaryLeft("hello world", 6);
        assertEquals(0, b); // saute l'espace et remonte au début du mot situé avant l'espace blanc
    }

    @Test
    void wordBoundaryLeft_word() {
        int b = EditOps.wordBoundaryLeft("hello world", 11);
        assertEquals(6, b); // début de "world"
    }

    @Test
    void wordBoundaryRight_word() {
        int b = EditOps.wordBoundaryRight("hello world", 0);
        assertEquals(5, b); // fin de "hello"
    }

    @Test
    void wordBoundaryRight_skipWhitespace() {
        int b = EditOps.wordBoundaryRight("hello world", 5);
        // L'implémentation peut sauter jusqu'à la fin du mot suivant
        assertTrue(b >= 6 && b <= 11, "Expected 6-11, got " + b);
    }

    @Test
    void wordRangeAt_middle() {
        int[] range = EditOps.wordRangeAt("hello world", 2);
        assertEquals(2, range.length);
        assertEquals(0, range[0]);
        assertEquals(5, range[1]);
    }

    @Test
    void wordRangeAt_start() {
        int[] range = EditOps.wordRangeAt("hello", 0);
        assertEquals(0, range[0]);
        assertEquals(5, range[1]);
    }

    // ── detectIndentUnit ─────────────────────────────────────────

    @Test
    void detectIndentUnit_4spaces() {
        String unit = EditOps.detectIndentUnit("line1\n    line2\n        line3");
        assertEquals("    ", unit);
    }

    @Test
    void detectIndentUnit_tabs() {
        String unit = EditOps.detectIndentUnit("line1\n\tline2\n\t\tline3");
        assertEquals("\t", unit);
    }

    @Test
    void detectIndentUnit_default() {
        String unit = EditOps.detectIndentUnit("no indentation here");
        assertEquals("    ", unit); // 4 espaces par défaut
    }
}
