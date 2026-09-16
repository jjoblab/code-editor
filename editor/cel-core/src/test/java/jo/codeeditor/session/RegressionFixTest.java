package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the bugs fixed in v1.0.2.
 *
 * <p>Each test verifies a specific previously-broken behavior:
 *
 * <ul>
 *   <li>{@code typeChar} skip-over must NOT push an undo step.</li>
 *   <li>{@code toggleLineComment} must not comment blank lines as "allCommented".</li>
 *   <li>{@code toggleBlockComment} must be a single undo step.</li>
 *   <li>{@code indent}/{@code dedent} must not include the trailing line when the
 *       selection ends exactly at a line start.</li>
 *   <li>{@code typeChar} caret must end up at the right position after smart
 *       newline expansion.</li>
 * </ul>
 */
class RegressionFixTest {

    // ── Bug #1: typeChar skip-over must not pollute undo ──────────

    @Test
    void typeChar_skipOverCloser_doesNotRecordUndo() {
        EditorSession s = new EditorSession(EditorDocument.of("()"));
        s.setSelection(1);                  // caret between '(' and ')'
        s.typeChar(')');                    // skip-over: caret moves to 2

        assertEquals("()", s.getText(), "Skip-over must not insert anything");
        assertEquals(2, s.getSelection().start, "Caret must move past the closer");

        // The undo stack must be empty — there was nothing to undo.
        assertFalse(s.undo(), "Skip-over must not produce an undo step");
    }

    @Test
    void typeChar_skipOverQuote_doesNotRecordUndo() {
        EditorSession s = new EditorSession(EditorDocument.of("\"\""));
        s.setSelection(1);
        s.typeChar('"');

        assertEquals("\"\"", s.getText());
        assertEquals(2, s.getSelection().start);
        assertFalse(s.undo());
    }

    // ── Bug #2: typeChar with auto-close must set caret correctly ─

    @Test
    void typeChar_autoCloseParen_setsCaretBetweenPair() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(5);
        s.typeChar('(');

        assertEquals("hello()", s.getText());
        assertEquals(6, s.getSelection().start, "Caret must be between '(' and ')'");
    }

    // ── Bug #5: toggleLineComment with blank lines ────────────────

    @Test
    void toggleLineComment_ignoresBlankLinesForAllCommentedCheck() {
        // Line 0 is "// a", line 1 is blank, line 2 is "// c".
        // allCommented should be TRUE (blank lines are ignored), so the call
        // should UNCOMMENT rather than re-comment.
        EditorSession s = new EditorSession(EditorDocument.of("// a\n\n// c"));
        s.setSelection(Selection.range(0, 8));

        s.toggleLineComment();

        // After uncomment, both non-blank lines lose their prefix, the blank
        // line stays blank.
        assertEquals("a\n\nc", s.getText());
    }

    @Test
    void toggleLineComment_commentsBlockWithBlankLines() {
        // No lines are commented yet — toggle should comment all (incl. blank).
        EditorSession s = new EditorSession(EditorDocument.of("a\n\nb"));
        s.setSelection(Selection.range(0, 4));

        s.toggleLineComment();

        assertEquals("// a\n// \n// b", s.getText());
    }

    @Test
    void toggleLineComment_undoRoundTrip() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\nc"));
        s.setSelection(Selection.range(0, 5));

        s.toggleLineComment();
        String commented = s.getText();
        assertNotEquals("a\nb\nc", commented);

        assertTrue(s.undo());
        assertEquals("a\nb\nc", s.getText());
    }

    // ── Bug #6: toggleBlockComment must be a single undo step ─────

    @Test
    void toggleBlockComment_isSingleUndoStep() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.setSelection(Selection.range(0, 5));      // select "hello"

        s.toggleBlockComment();                      // add /* */ around it
        // Note: the impl inserts "/* " and " */" (with spaces) around the
        // selection, so the result is "/* hello */ world".
        assertEquals("/* hello */ world", s.getText());

        int depthBeforeUndo = s.getUndoManager().undoDepth();
        assertTrue(depthBeforeUndo >= 1, "Should have at least one undo step, got " + depthBeforeUndo);

        boolean undone = s.undo();
        assertTrue(undone, "undo() should return true");
        assertEquals("hello world", s.getText(), "Single undo must remove both /* and */");
    }

    @Test
    void toggleBlockComment_unwrapWhenAlreadyWrapped() {
        // Use "/*hello*/" (no spaces) so that start=2 sits exactly after "/*"
        // and end=7 sits exactly before "*/" — that's what the impl detects.
        EditorSession s = new EditorSession(EditorDocument.of("/*hello*/ world"));
        s.setSelection(Selection.range(2, 7));      // inside "hello"

        s.toggleBlockComment();
        assertEquals("hello world", s.getText());
    }

    // ── Bug #8: indent/dedent must not include the next empty line ─

    @Test
    void indent_doesNotIncludeTrailingEmptyLineWhenSelectionEndsAtLineStart() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\nc"));
        // Select from offset 0 to offset 2 (which is lineStart(1)).
        // The selection visually covers "a\n" — it should NOT indent line "b".
        s.setSelection(Selection.range(0, 2));

        s.indent();

        assertEquals("    a\nb\nc", s.getText(), "Only line 0 should be indented");
    }

    @Test
    void indent_fullSelection_includesAllLines() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\nc"));
        s.setSelection(Selection.range(0, 5));      // covers all of "a\nb\nc"

        s.indent();

        assertEquals("    a\n    b\n    c", s.getText());
    }

    @Test
    void indent_dedent_roundTrip() {
        EditorSession s = new EditorSession(EditorDocument.of("a\nb\nc"));
        s.setSelection(Selection.range(0, 5));

        s.indent();
        s.setSelection(Selection.range(0, s.getDocument().length()));
        s.dedent();

        assertEquals("a\nb\nc", s.getText());
    }

    // ── Bug #2 (caret): backspace empty pair deletes both chars ───

    @Test
    void backspace_emptyPair_deletesBoth() {
        EditorSession s = new EditorSession(EditorDocument.of("()"));
        s.setSelection(1);

        s.backspace();

        assertEquals("", s.getText());
        assertEquals(0, s.getSelection().start);
    }

    @Test
    void backspace_emptyBraces_deletesBoth() {
        EditorSession s = new EditorSession(EditorDocument.of("{}"));
        s.setSelection(1);

        s.backspace();

        assertEquals("", s.getText());
    }

    // ── isInString now ignores comments (Bug #4) ──────────────────

    @Test
    void smartEnter_insideLineComment_treatsAsCode() {
        // The caret is right after "// " inside a line comment.
        // Previously the lexer thought we were in a string (because of the
        // quotes below on the same line) and refused smart indent. Now line
        // comments are skipped and smart indent works normally.
        EditorSession s = new EditorSession(EditorDocument.of("// comment \"x\""));
        s.setLanguage("java");
        s.setSelection(s.getDocument().length());

        s.commitText("\n");

        // The new line should just inherit the indent (none here), so the
        // result is the original line + "\n" + "" (no extra indent).
        assertEquals("// comment \"x\"\n", s.getText());
    }

    // ── replaceRangeWithCaret: explicit caret for smart ops ───────

    @Test
    void replaceRangeWithCaret_noOpDoesNotPushUndo() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(2);
        int undoDepthBefore = s.getUndoManager().undoDepth();

        // No-op: start == end, insertion is empty.
        s.replaceRangeWithCaret(2, 2, "", 5);

        assertEquals(undoDepthBefore, s.getUndoManager().undoDepth(),
            "No-op replaceRangeWithCaret must not push undo");
        assertEquals(5, s.getSelection().start, "Caret must move to 5");
    }

    @Test
    void replaceRangeWithCaret_customCaretAfterInsert() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(0);

        // Insert "WORLD" but leave caret at offset 2 (not at end).
        s.replaceRangeWithCaret(0, 0, "WORLD", 2);

        assertEquals("WORLDhello", s.getText());
        assertEquals(2, s.getSelection().start);
    }
}
