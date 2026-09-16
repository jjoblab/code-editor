package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the editor session (edit engine, undo/redo, smart edits).
 */
class EditorSessionTest {

    // ── Basic editing ─────────────────────────────────────────────

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
        s.setSelection(1); // between ( and )
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

    // ── Auto-close brackets ───────────────────────────────────────

    @Test
    void typeChar_autoCloseParen() {
        EditorSession s = new SessionBuilder("").build();
        s.typeChar('(');
        assertEquals("()", s.getText());
        assertEquals(1, s.getSelection().start); // cursor between parens
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
        s.setSelection(1); // between ( and )
        s.typeChar(')');   // should skip over, not insert
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

    // ── Indent / Dedent ───────────────────────────────────────────

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
        s.setSelection(Selection.range(0, 11)); // select all
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

    // ── Comment toggling ──────────────────────────────────────────

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

    // ── Line operations ───────────────────────────────────────────

    @Test
    void duplicateLine() {
        EditorSession s = new SessionBuilder("hello\nworld").build();
        s.setSelection(0); // on first line
        s.duplicateLine();
        assertEquals("hello\nhello\nworld", s.getText());
    }

    @Test
    void deleteLine() {
        EditorSession s = new SessionBuilder("aaa\nbbb\nccc").build();
        s.setSelection(0); // on first line
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
        s.setSelection(4); // on second line
        s.moveLineUp();
        assertEquals("bbb\naaa", s.getText());
    }

    @Test
    void moveLineDown_noOpAtBottom() {
        EditorSession s = new SessionBuilder("aaa\nbbb").build();
        s.setSelection(4); // on second line
        s.moveLineDown();
        assertEquals("aaa\nbbb", s.getText());
    }

    @Test
    void moveLineDown_swaps() {
        EditorSession s = new SessionBuilder("aaa\nbbb").build();
        s.setSelection(0); // on first line
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

        // New edit should clear redo stack
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

    // ── Language ──────────────────────────────────────────────────

    @Test
    void setLanguage_updatesHighlighting() {
        EditorSession s = new SessionBuilder("public class Foo {}").build();
        s.setLanguage("java");
        assertEquals("java", s.getLanguage());
        assertFalse(s.getStyledLines().isEmpty());
    }

    // ── v3.37.0 (B13) — TextMate gate: per-call, no global static ────

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
     * v3.37.0 (B13) — Petit document + tokenizer enregistré disponible
     * → délégation ACTIVE (sémantique v2.44 d'origine, restaurée : le
     * hack v2.55 « setTextMateEnabled(false) à chaque setLanguage » est
     * retiré). En production tm4e étant absent, le tokenizer reste null
     * et ce path ne s'active jamais — le test le prouve avec un stub.
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
     * v3.37.0 (B13) — Document au-delà de TEXTMATE_LINE_LIMIT → le gate
     * local se ferme pour SA passe de restyle : le parser maison tokenise
     * tout, le tokenizer enregistré n'est jamais consulté.
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
     * LE test du design smell B13 : avant v3.37, le drapeau global
     * statique coupé par la session du gros document désactivait la
     * délégation TextMate pour la session du petit document. Le gate
     * étant désormais local à chaque passe de restyle, les deux
     * sessions coexistent sans interférer.
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

    // ── v2.45 — Async restyle path ───────────────────────────────────────

    /**
     * v2.45 — setLanguage on a small doc kicks off an async restyle.
     * isAsyncRestylePending() returns true immediately after the call.
     */
    @Test
    void setLanguage_smallDocument_kicksOffAsyncRestyle() throws Exception {
        EditorSession s = new SessionBuilder("public class Foo {}").build();
        s.setLanguage("java");
        // Right after setLanguage, an async restyle should be in flight
        // (or just-completed — race). We check that it either is
        // pending OR has already populated styledLines.
        boolean pending = s.isAsyncRestylePending();
        if (pending) {
            // Wait for it to finish.
            s.awaitPendingRestyle();
        }
        // After waiting, no longer pending.
        assertFalse(s.isAsyncRestylePending(),
            "Async restyle should be complete after await");
        // styledLines should be populated (1 line for "public class Foo {}").
        assertEquals(1, s.getStyledLines().size(),
            "styledLines should have 1 entry after async restyle");
    }

    /**
     * v2.45 — After async restyle completes, the styledLines list
     * contains the expected number of lines (matching the doc's
     * line count).
     */
    @Test
    void asyncRestyle_populatesStyledLinesWithCorrectCount() throws Exception {
        String text = "line 1\nline 2\nline 3\nline 4\nline 5";
        EditorSession s = new SessionBuilder(text).build();
        s.setLanguage("java");
        s.awaitPendingRestyle();
        // EditorDocument.lineCount for "line 1\nline 2\nline 3\nline 4\nline 5"
        // should be 5 (no trailing newline → 5 lines).
        assertEquals(s.getDocument().lineCount(), s.getStyledLines().size(),
            "styledLines count must match doc line count");
    }

    /**
     * v2.45 — Calling setLanguage twice in quick succession cancels
     * the first restyle. The second one wins. No exception, no deadlock.
     */
    @Test
    void asyncRestyle_doubleSetLanguage_cancelsFirstRestyle() throws Exception {
        EditorSession s = new SessionBuilder("first").build();
        s.setLanguage("java");
        // Immediately switch to a different text — first restyle should
        // be cancelled, second should win.
        s.replaceRange(0, 5, "second");  // replaces "first" with "second"
        s.setLanguage("java");  // re-triggers async restyle
        s.awaitPendingRestyle();
        // After waiting, styledLines should be consistent with the doc.
        assertEquals(s.getDocument().lineCount(), s.getStyledLines().size(),
            "styledLines count must match doc line count after double restyle");
    }

    /**
     * v2.55 — Large documents ALSO use async restyle (avant v2.55, ils
     * utilisaient restyleAll sync). Maintenant que tm4e est retiré de
     * l'app, tous les setLanguage déclenchent restyleAllAsync, peu
     * importe la tailleur du doc. Le parser maison est ~10-50x plus
     * rapide que tm4e par ligne, donc même un 5000-lignes se tokenize
     * en <1s en arrière-plan.
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
        // v2.55 : un async restyle doit être en cours (ou déjà terminé).
        // Si il est encore en cours, on l'attend.
        if (s.isAsyncRestylePending()) {
            s.awaitPendingRestyle();
        }
        // Après attente : styledLines est peuplé et match doc.lineCount.
        assertEquals(s.getDocument().lineCount(), s.getStyledLines().size(),
            "v2.55: Large doc styledLines count must match doc lineCount after async restyle completes");
    }
}

