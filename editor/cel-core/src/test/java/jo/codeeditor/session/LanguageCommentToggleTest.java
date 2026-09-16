package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.edit.CommentSyntax;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3.35.0 (roadmap item 1 / bug B11) — behavior of
 * {@link EditorSession#toggleLineComment()} / {@link EditorSession#toggleBlockComment()}
 * across languages. Before v3.35.0 both methods hardcoded C-style tokens:
 * Python files received {@code // def foo():}, XML files received
 * {@code // <node>}, and JSON files received comments they can't have.
 *
 * <p>Also pins the OVERRIDE API ({@link EditorSession#setCommentSyntax})
 * and the byte-for-byte preservation of the legacy Java behavior (the
 * pre-existing RegressionFixTest suite keeps covering the edge cases).</p>
 */
class LanguageCommentToggleTest {

    private static EditorSession session(String language, String text) {
        EditorSession s = new EditorSession(EditorDocument.of(text));
        s.setLanguage(language);
        // setLanguage triggers an async restyle — not needed for these
        // tests, and we don't want the executor to outlive the test.
        s.dispose();
        return s;
    }

    private static void selectAll(EditorSession s) {
        s.setSelection(Selection.range(0, s.getText().length()));
    }

    // ── Legacy Java behavior is preserved ────────────────────────────

    @Test
    void java_toggleLineComment_addsSlashPrefixWithSpace() {
        EditorSession s = session("java", "hello");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("// hello", s.getText());
    }

    @Test
    void java_toggleLineComment_roundTrip() {
        EditorSession s = session("java", "hello");
        selectAll(s);
        s.toggleLineComment();
        s.toggleLineComment();
        assertEquals("hello", s.getText());
    }

    @Test
    void java_toggleLineComment_multiLine() {
        EditorSession s = session("java", "a\nb");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("// a\n// b", s.getText());
    }

    @Test
    void java_toggleBlockComment_wrapsSelection() {
        EditorSession s = session("java", "hello");
        s.setSelection(Selection.range(0, 5));
        s.toggleBlockComment();
        assertEquals("/* hello */", s.getText());
        // And unwraps on the second toggle (single undo step each).
        s.toggleBlockComment();
        assertEquals("hello", s.getText());
    }

    // ── Python: '#' line comments, block is a no-op ──────────────────

    @Test
    void python_toggleLineComment_usesHash() {
        EditorSession s = session("python", "def foo():");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("# def foo():", s.getText());
    }

    @Test
    void python_toggleLineComment_uncommentsHash() {
        EditorSession s = session("python", "# def foo():");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("def foo():", s.getText());
    }

    @Test
    void python_toggleLineComment_multiLineRoundTrip() {
        EditorSession s = session("python", "import os\nimport sys");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("# import os\n# import sys", s.getText());
        s.toggleLineComment();
        assertEquals("import os\nimport sys", s.getText());
    }

    @Test
    void python_toggleBlockComment_isNoOp() {
        EditorSession s = session("python", "def foo():");
        s.setSelection(Selection.range(0, 8));
        s.toggleBlockComment();
        assertEquals("def foo():", s.getText());
    }

    // ── XML: line toggle falls back to the block pair (VS Code) ──────

    @Test
    void xml_toggleLineComment_wrapsLinesInBlockPair() {
        EditorSession s = session("xml", "<node>");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("<!-- <node> -->", s.getText());
    }

    @Test
    void xml_toggleLineComment_multiLine() {
        EditorSession s = session("xml", "<a>\n<b>");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("<!-- <a> -->\n<!-- <b> -->", s.getText());
    }

    @Test
    void xml_toggleLineComment_roundTrip() {
        EditorSession s = session("xml", "<node attr=\"1\">");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("<!-- <node attr=\"1\"> -->", s.getText());
        s.toggleLineComment();
        assertEquals("<node attr=\"1\">", s.getText());
    }

    @Test
    void xml_toggleLineComment_blankLineGetsBarePair() {
        EditorSession s = session("xml", "<a>\n\n<b>");
        selectAll(s);
        s.toggleLineComment();
        // Blank line keeps a minimal pair (no inner spaces to strip on undo).
        assertEquals("<!-- <a> -->\n<!---->\n<!-- <b> -->", s.getText());
        s.toggleLineComment();
        assertEquals("<a>\n\n<b>", s.getText());
    }

    @Test
    void xml_toggleBlockComment_usesXmlPair() {
        EditorSession s = session("xml", "<node>");
        s.setSelection(Selection.range(0, 6));
        s.toggleBlockComment();
        assertEquals("<!-- <node> -->", s.getText());
        s.toggleBlockComment();
        assertEquals("<node>", s.getText());
    }

    // ── Markdown: same XML-family block pair ─────────────────────────

    @Test
    void markdown_toggleLineComment_wrapsInHtmlPair() {
        EditorSession s = session("markdown", "# Title");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("<!-- # Title -->", s.getText());
        s.toggleLineComment();
        assertEquals("# Title", s.getText());
    }

    // ── JSON: both toggles are no-ops ────────────────────────────────

    @Test
    void json_toggleLineComment_isNoOp() {
        EditorSession s = session("json", "{\"a\": 1}");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("{\"a\": 1}", s.getText());
    }

    @Test
    void json_toggleBlockComment_isNoOp() {
        EditorSession s = session("json", "{\"a\": 1}");
        s.setSelection(Selection.range(1, 6));
        s.toggleBlockComment();
        assertEquals("{\"a\": 1}", s.getText());
    }

    // ── Lua & SQL ────────────────────────────────────────────────────

    @Test
    void lua_toggleLineComment_usesDoubleDash() {
        EditorSession s = session("lua", "local x = 1");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("-- local x = 1", s.getText());
        s.toggleLineComment();
        assertEquals("local x = 1", s.getText());
    }

    @Test
    void lua_toggleBlockComment_usesLongBracket() {
        EditorSession s = session("lua", "local x = 1");
        s.setSelection(Selection.range(0, 11));
        s.toggleBlockComment();
        assertEquals("--[[ local x = 1 ]]", s.getText());
        s.toggleBlockComment();
        assertEquals("local x = 1", s.getText());
    }

    @Test
    void sql_toggleLineComment_usesDoubleDash() {
        EditorSession s = session("sql", "SELECT * FROM t");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("-- SELECT * FROM t", s.getText());
        s.toggleLineComment();
        assertEquals("SELECT * FROM t", s.getText());
    }

    @Test
    void sql_toggleBlockComment_usesCStylePair() {
        EditorSession s = session("sql", "SELECT 1");
        s.setSelection(Selection.range(0, 8));
        s.toggleBlockComment();
        assertEquals("/* SELECT 1 */", s.getText());
    }

    // ── Shell ────────────────────────────────────────────────────────

    @Test
    void shell_toggleLineComment_usesHash() {
        EditorSession s = session("sh", "ls -la");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("# ls -la", s.getText());
        s.toggleLineComment();
        assertEquals("ls -la", s.getText());
    }

    // ── getCommentSyntax / setCommentSyntax (override API) ──────────

    @Test
    void getCommentSyntax_derivesFromLanguage() {
        EditorSession s = session("python", "x");
        assertEquals(CommentSyntax.forLanguage("python"), s.getCommentSyntax());
        s.setLanguage("xml");
        assertEquals(CommentSyntax.forLanguage("xml"), s.getCommentSyntax());
    }

    @Test
    void setCommentSyntax_overridesLanguageResolution() {
        EditorSession s = session("java", "hello");
        // A custom language the table doesn't know — e.g. Vimscript.
        s.setCommentSyntax(new CommentSyntax("\"", null, null));
        selectAll(s);
        s.toggleLineComment();
        assertEquals("\" hello", s.getText());
        s.toggleLineComment();
        assertEquals("hello", s.getText());
    }

    @Test
    void setCommentSyntax_nullRestoresLanguageResolution() {
        EditorSession s = session("python", "x");
        s.setCommentSyntax(CommentSyntax.C_STYLE);
        assertEquals(CommentSyntax.C_STYLE, s.getCommentSyntax());
        s.setCommentSyntax(null);
        assertEquals(CommentSyntax.forLanguage("python"), s.getCommentSyntax());
    }

    // ── Undo integration ─────────────────────────────────────────────

    @Test
    void python_toggleLineComment_isUndoableInOneStep() {
        EditorSession s = session("python", "a\nb\nc");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("# a\n# b\n# c", s.getText());
        assertTrue(s.undo());
        assertEquals("a\nb\nc", s.getText());
        assertTrue(s.redo());
        assertEquals("# a\n# b\n# c", s.getText());
    }

    @Test
    void xml_toggleBlockComment_isSingleUndoStep() {
        EditorSession s = session("xml", "hello world");
        s.setSelection(Selection.range(0, 11));
        s.toggleBlockComment();
        assertEquals("<!-- hello world -->", s.getText());
        assertTrue(s.undo());
        assertEquals("hello world", s.getText());
        assertFalse(s.undo());
    }
}
