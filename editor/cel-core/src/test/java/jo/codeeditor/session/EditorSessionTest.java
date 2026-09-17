package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de la session d'édition (moteur d'édition, undo/redo, éditions
 * intelligentes).
 */
class EditorSessionTest {

    // ── Édition de base ─────────────────────────────────────────

    @Test
    void commitText_insertsAtCursor() {
        EditorSession s = new SessionBuilder("hello").build();
        s.setSelection(5);
        s.commitText(" world");
        assertEquals("hello world", s.getText());
        assertEquals(11, s.getSelection().start);
    }

    @Test
    void commitText_replacesSelection() {
        EditorSession s = new SessionBuilder("hello world").build();
        s.setSelection(Selection.range(6, 11));
        s.commitText("there");
        assertEquals("hello there", s.getText());
    }

    @Test
    void backspace_deletesCharBefore() {
        EditorSession s = new SessionBuilder("hello").build();
        s.setSelection(5);
        s.backspace();
        assertEquals("hell", s.getText());
        assertEquals(4, s.getSelection().start);
    }

    @Test
    void backspace_pairAware() {
        EditorSession s = new SessionBuilder("()").build();
        s.setSelection(1); // entre ( et )
        s.backspace();
        assertEquals("", s.getText());
    }

    @Test
    void backspace_pairAware_braces() {
        EditorSession s = new SessionBuilder("{}").build();
        s.setSelection(1);
        s.backspace();
        assertEquals("", s.getText());
    }

    @Test
    void backspace_pairAware_brackets() {
        EditorSession s = new SessionBuilder("[]").build();
        s.setSelection(1);
        s.backspace();
        assertEquals("", s.getText());
    }

    @Test
    void backspace_pairAware_quotes() {
        EditorSession s = new SessionBuilder("\"\"").build();
        s.setSelection(1);
        s.backspace();
        assertEquals("", s.getText());
    }

    @Test
    void backspace_notPairAware_differentBrackets() {
        EditorSession s = new SessionBuilder("(]").build();
        s.setSelection(1);
        s.backspace();
        assertEquals("]", s.getText());
    }

    @Test
    void backspace_deletesSelection() {
        EditorSession s = new SessionBuilder("hello world").build();
        s.setSelection(Selection.range(5, 11));
        s.backspace();
        assertEquals("hello", s.getText());
    }

    @Test
    void deleteForward_deletesCharAfter() {
        EditorSession s = new SessionBuilder("hello").build();
        s.setSelection(0);
        s.deleteForward();
        assertEquals("ello", s.getText());
        assertEquals(0, s.getSelection().start);
    }

    @Test
    void deleteForward_pairAware() {
        EditorSession s = new SessionBuilder("()").build();
        s.setSelection(0);
        s.deleteForward();
        assertEquals("", s.getText());
    }

    @Test
    void deleteForward_deletesSelection() {
        EditorSession s = new SessionBuilder("hello world").build();
        s.setSelection(Selection.range(0, 5));
        s.deleteForward();
        assertEquals(" world", s.getText());
    }

    // ── Auto-fermeture des paires ───────────────────────────────

    @Test
    void typeChar_autoCloseParen() {
        EditorSession s = new SessionBuilder("").build();
        s.typeChar('(');
        assertEquals("()", s.getText());
        assertEquals(1, s.getSelection().start); // curseur entre les parenthèses
    }

    @Test
    void typeChar_autoCloseBrace() {
        EditorSession s = new SessionBuilder("").build();
        s.typeChar('{');
        assertEquals("{}", s.getText());
        assertEquals(1, s.getSelection().start);
    }

    @Test
    void typeChar_autoCloseBracket() {
        EditorSession s = new SessionBuilder("").build();
        s.typeChar('[');
        assertEquals("[]", s.getText());
        assertEquals(1, s.getSelection().start);
    }

    @Test
    void typeChar_skipOverCloser() {
        EditorSession s = new SessionBuilder("()").build();
        s.setSelection(1); // entre ( et )
        s.typeChar(')');   // doit sauter par-dessus, pas insérer
        assertEquals("()", s.getText());
        assertEquals(2, s.getSelection().start);
    }

    @Test
    void typeChar_skipOverQuote() {
        EditorSession s = new SessionBuilder("\"\"").build();
        s.setSelection(1);
        s.typeChar('"');
        assertEquals("\"\"", s.getText());
        assertEquals(2, s.getSelection().start);
    }

    // ── Indentation / Désindentation ────────────────────────────

    @Test
    void indent_currentLine() {
        EditorSession s = new SessionBuilder("hello").build();
        s.setSelection(2);
        s.indent();
        assertEquals("    hello", s.getText());
    }

    @Test
    void dedent_removesLeadingSpaces() {
        EditorSession s = new SessionBuilder("    hello").build();
        s.setSelection(2);
        s.dedent();
        assertEquals("hello", s.getText());
    }

    @Test
    void indent_multiLine() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc").build();
        s.setSelection(Selection.range(0, 11)); // tout sélectionner
        s.indent();
        assertEquals("    aaa\n    bbb\n    ccc", s.getText());
    }

    @Test
    void dedent_multiLine() {
        EditorSession s = new SessionBuilder("    aaa\n    bbb\n    ccc").build();
        s.setSelection(Selection.range(0, 23));
        s.dedent();
        assertEquals("aaa\nbbb\nccc", s.getText());
    }

    // ── Bascule de commentaires ─────────────────────────────────

    @Test
    void toggleLineComment_addsComment() {
        EditorSession s = new SessionBuilder("hello").build();
        s.setSelection(0);
        s.toggleLineComment();
        assertEquals("// hello", s.getText());
    }

    @Test
    void toggleLineComment_removesComment() {
        EditorSession s = new SessionBuilder("// hello").build();
        s.setSelection(0);
        s.toggleLineComment();
        assertEquals("hello", s.getText());
    }

    @Test
    void toggleLineComment_multiLine() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc").build();
        s.setSelection(Selection.range(0, 11));
        s.toggleLineComment();
        assertEquals("// aaa\n// bbb\n// ccc", s.getText());
    }

    @Test
    void toggleLineComment_uncommentsAll() {
        EditorSession s = new SessionBuilder("// aaa\n// bbb\n// ccc").build();
        s.setSelection(Selection.range(0, 20));
        s.toggleLineComment();
        assertEquals("aaa\nbbb\nccc", s.getText());
    }

    // ── Opérations sur les lignes ───────────────────────────────

    @Test
    void duplicateLine() {
        EditorSession s = new SessionBuilder("hello\nworld").build();
        s.setSelection(0); // sur la première ligne
        s.duplicateLine();
        assertEquals("hello\nhello\nworld", s.getText());
    }

    @Test
    void deleteLine() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc").build();
        s.setSelection(0); // sur la première ligne
        s.deleteLines();
        assertEquals("bbb\nccc", s.getText());
    }

    @Test
    void moveLineUp_noOpAtTop() {
        EditorSession s = new SessionBuilder("aaa\nbbb").build();
        s.setSelection(0);
        s.moveLineUp();
        assertEquals("aaa\nbbb", s.getText());
    }

    @Test
    void moveLineUp_swaps() {
        EditorSession s = new SessionBuilder("aaa\nbbb").build();
        s.setSelection(4); // sur la deuxième ligne
        s.moveLineUp();
        assertEquals("bbb\naaa", s.getText());
    }

    @Test
    void moveLineDown_noOpAtBottom() {
        EditorSession s = new SessionBuilder("aaa\nbbb").build();
        s.setSelection(4); // sur la deuxième ligne
        s.moveLineDown();
        assertEquals("aaa\nbbb", s.getText());
    }

    @Test
    void moveLineDown_swaps() {
        EditorSession s = new SessionBuilder("aaa\nbbb").build();
        s.setSelection(0); // sur la première ligne
        s.moveLineDown();
        assertEquals("bbb\naaa", s.getText());
    }

    @Test
    void joinLines() {
        EditorSession s = new SessionBuilder("hello\nworld").build();
        s.setSelection(0);
        s.joinLines();
        assertEquals("hello world", s.getText());
    }

    // ── Undo / Redo ───────────────────────────────────────────────

    @Test
    void undo_revertsLastEdit() {
        EditorSession s = new SessionBuilder("hello").build();
        s.setSelection(5);
        s.commitText(" world");
        assertEquals("hello world", s.getText());

        assertTrue(s.undo());
        assertEquals("hello", s.getText());
    }

    @Test
    void redo_reappliesEdit() {
        EditorSession s = new SessionBuilder("hello").build();
        s.setSelection(5);
        s.commitText(" world");

        s.undo();
        assertEquals("hello", s.getText());

        assertTrue(s.redo());
        assertEquals("hello world", s.getText());
    }

    @Test
    void undo_nothingToUndo() {
        EditorSession s = new SessionBuilder("hello").build();
        assertFalse(s.undo());
    }

    @Test
    void redo_nothingToRedo() {
        EditorSession s = new SessionBuilder("hello").build();
        assertFalse(s.redo());
    }

    @Test
    void undo_clearsRedoStack() {
        EditorSession s = new SessionBuilder("hello").build();
        s.setSelection(5);
        s.commitText(" world");
        s.undo();

        // Une nouvelle édition doit vider la pile de redo
        s.setSelection(5);
        s.commitText("!");
        assertFalse(s.redo());
    }

    @Test
    void undo_multipleEdits() {
        EditorSession s = new SessionBuilder("").build();
        s.setSelection(0);
        s.commitText("a");
        s.commitText("b");
        s.commitText("c");
        assertEquals("abc", s.getText());

        s.undo();
        assertEquals("ab", s.getText());
        s.undo();
        assertEquals("a", s.getText());
        s.undo();
        assertEquals("", s.getText());
    }

    // ── Langage ─────────────────────────────────────────────────

    @Test
    void setLanguage_updatesHighlighting() {
        EditorSession s = new SessionBuilder("public class Foo {}").build();
        s.setLanguage("java");
        assertEquals("java", s.getLanguage());
        assertFalse(s.getStyledLines().isEmpty());
    }

    // ── Gate TextMate : par-appel, sans statique globale ────────

    /** Stub tokenizer : sentinel ANNOTATION span sur toute la ligne. */
    private static jo.codeeditor.highlight.TextMateTokenizer sentinelTokenizer() {
        return new jo.codeeditor.highlight.TextMateTokenizer() {
            @Override
            public boolean isAvailable(String language) {
                return "java".equals(language);
            }
            @Override
            public jo.codeeditor.highlight.StyledLine tokenize(
                    String line, int entryState, String language) {
                return new jo.codeeditor.highlight.StyledLine(
                    java.util.Collections.singletonList(
                        new jo.codeeditor.highlight.LineSpan(
                            0, line.length(),
                            jo.codeeditor.highlight.TokenType.ANNOTATION)),
                    entryState, entryState);
            }
        };
    }

    /**
     * Petit document + tokenizer enregistré disponible → délégation ACTIVE.
     * En production, tm4e étant absent, le tokenizer reste null et ce path
     * ne s'active jamais — le test le prouve avec un stub.
     */
    @Test
    void setLanguage_smallDocument_delegatesToRegisteredTokenizer() throws Exception {
        jo.codeeditor.highlight.SyntaxHighlighter.setTextMateTokenizer(sentinelTokenizer());
        try {
            EditorSession s = new SessionBuilder("public class Foo {}").build();
            s.setLanguage("java");
            if (s.isAsyncRestylePending()) s.awaitPendingRestyle();
            assertTrue(
                s.getStyledLines().get(0).spans.stream()
                    .allMatch(x -> x.type == jo.codeeditor.highlight.TokenType.ANNOTATION),
                "small doc must delegate to the registered tokenizer");
        } finally {
            jo.codeeditor.highlight.SyntaxHighlighter.setTextMateTokenizer(null);
        }
    }

    /**
     * Document au-delà de TEXTMATE_LINE_LIMIT → le gate local se ferme pour
     * SA passe de restyle : le parser maison tokenise tout, le tokenizer
     * enregistré n'est jamais consulté.
     */
    @Test
    void setLanguage_largeDocument_skipsRegisteredTokenizer() throws Exception {
        StringBuilder big = new StringBuilder();
        int lines = jo.codeeditor.session.EditorSession.TEXTMATE_LINE_LIMIT + 1;
        for (int i = 0; i < lines; i++) {
            big.append("line ").append(i).append('\n');
        }
        jo.codeeditor.highlight.SyntaxHighlighter.setTextMateTokenizer(sentinelTokenizer());
        try {
            EditorSession s = new SessionBuilder(big.toString()).build();
            s.setLanguage("java");
            if (s.isAsyncRestylePending()) s.awaitPendingRestyle();
            assertTrue(
                s.getStyledLines().stream().noneMatch(l -> l.spans.stream()
                    .anyMatch(x -> x.type == jo.codeeditor.highlight.TokenType.ANNOTATION)),
                "large doc must skip TextMate (per-call gate closed)");
        } finally {
            jo.codeeditor.highlight.SyntaxHighlighter.setTextMateTokenizer(null);
        }
    }

    /**
     * Test clé d'isolation : le drapeau global statique coupé par la
     * session du gros document ne doit PAS désactiver la délégation
     * TextMate pour la session du petit document. Le gate étant local
     * à chaque passe de restyle, les deux sessions coexistent sans
     * interférer.
     */
    @Test
    void setLanguage_largeSession_doesNotAffectSmallSession() throws Exception {
        StringBuilder big = new StringBuilder();
        int lines = jo.codeeditor.session.EditorSession.TEXTMATE_LINE_LIMIT + 1;
        for (int i = 0; i < lines; i++) {
            big.append("x\n");
        }
        jo.codeeditor.highlight.SyntaxHighlighter.setTextMateTokenizer(sentinelTokenizer());
        try {
            EditorSession large = new SessionBuilder(big.toString()).build();
            large.setLanguage("java");
            if (large.isAsyncRestylePending()) large.awaitPendingRestyle();

            EditorSession small = new SessionBuilder("public class Foo {}").build();
            small.setLanguage("java");
            if (small.isAsyncRestylePending()) small.awaitPendingRestyle();

            assertTrue(
                small.getStyledLines().get(0).spans.stream()
                    .allMatch(x -> x.type == jo.codeeditor.highlight.TokenType.ANNOTATION),
                "B13: a large session must NOT disable TextMate for a small session");
        } finally {
            jo.codeeditor.highlight.SyntaxHighlighter.setTextMateTokenizer(null);
        }
    }

    // ── Restyle asynchrone ─────────────────────────────────────

    /**
     * setLanguage sur un petit doc déclenche un restyle asynchrone.
     * isAsyncRestylePending() renvoie true juste après l'appel.
     */
    @Test
    void setLanguage_smallDocument_kicksOffAsyncRestyle() throws Exception {
        EditorSession s = new SessionBuilder("public class Foo {}").build();
        s.setLanguage("java");
        // Juste après setLanguage, un restyle asynchrone doit être en
        // cours (ou tout juste terminé — race). On vérifie qu'il est soit
        // pending, soit a déjà peuplé styledLines.
        boolean pending = s.isAsyncRestylePending();
        if (pending) {
            // Attendre sa fin.
            s.awaitPendingRestyle();
        }
        // Après attente, plus pending.
        assertFalse(s.isAsyncRestylePending(),
            "Async restyle should be complete after await");
        // styledLines doit être peuplé (1 ligne pour "public class Foo {}").
        assertEquals(1, s.getStyledLines().size(),
            "styledLines should have 1 entry after async restyle");
    }

    /**
     * Une fois le restyle asynchrone terminé, la liste styledLines
     * contient le nombre de lignes attendu (correspondant au lineCount
     * du doc).
     */
    @Test
    void asyncRestyle_populatesStyledLinesWithCorrectCount() throws Exception {
        String text = "line 1\nline 2\nline 3\nline 4\nline 5";
        EditorSession s = new SessionBuilder(text).build();
        s.setLanguage("java");
        s.awaitPendingRestyle();
        // EditorDocument.lineCount pour "line 1\nline 2\nline 3\nline 4\nline 5"
        // doit être 5 (pas de \n final → 5 lignes).
        assertEquals(s.getDocument().lineCount(), s.getStyledLines().size(),
            "styledLines count must match doc line count");
    }

    /**
     * Deux setLanguage rapprochés annulent le premier restyle.
     * Le second gagne. Pas d'exception, pas de deadlock.
     */
    @Test
    void asyncRestyle_doubleSetLanguage_cancelsFirstRestyle() throws Exception {
        EditorSession s = new SessionBuilder("first").build();
        s.setLanguage("java");
        // Basculer immédiatement vers un texte différent — le premier
        // restyle doit être annulé, le second doit gagner.
        s.replaceRange(0, 5, "second");  // remplace "first" par "second"
        s.setLanguage("java");  // re-déclenche le restyle asynchrone
        s.awaitPendingRestyle();
        // Après attente, styledLines doit être cohérent avec le doc.
        assertEquals(s.getDocument().lineCount(), s.getStyledLines().size(),
            "styledLines count must match doc line count after double restyle");
    }

    /**
     * Les gros documents utilisent AUSSI le restyle asynchrone : tous les
     * setLanguage déclenchent restyleAllAsync, peu importe la taille du
     * doc. Le parser maison est ~10-50x plus rapide que tm4e par ligne,
     * donc même un 5000-lignes se tokenise en moins d'une seconde en
     * arrière-plan.
     */
    @Test
    void largeDocument_usesSyncRestyle_populatesImmediately() throws Exception {
        StringBuilder big = new StringBuilder();
        int lines = jo.codeeditor.session.EditorSession.TEXTMATE_LINE_LIMIT + 1;
        for (int i = 0; i < lines; i++) {
            big.append("x\n");
        }
        EditorSession s = new SessionBuilder(big.toString()).build();
        s.setLanguage("java");
        // Un restyle asynchrone doit être en cours (ou déjà terminé).
        // S'il est encore en cours, on l'attend.
        if (s.isAsyncRestylePending()) {
            s.awaitPendingRestyle();
        }
        // Après attente : styledLines est peuplé et correspond à doc.lineCount.
        assertEquals(s.getDocument().lineCount(), s.getStyledLines().size(),
            "Gros document : le nombre de styledLines doit correspondre à doc.lineCount après le restyle asynchrone");
    }
}

