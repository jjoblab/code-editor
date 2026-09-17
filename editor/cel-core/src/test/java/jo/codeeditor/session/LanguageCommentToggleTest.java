package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.edit.CommentSyntax;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comportement de {@link EditorSession#toggleLineComment()} /
 * {@link EditorSession#toggleBlockComment()} selon le langage. Les deux
 * méthodes ne doivent PAS coder en dur des tokens C : les fichiers Python
 * ne doivent pas recevoir {@code // def foo():}, les fichiers XML ne
 * doivent pas recevoir {@code // <node>}, et les fichiers JSON ne doivent
 * pas recevoir de commentaires qu'ils ne peuvent pas avoir.
 *
 * <p>Épingle aussi l'API d'override ({@link EditorSession#setCommentSyntax})
 * et la préservation à l'octet près du comportement Java historique (la
 * suite RegressionFixTest continue de couvrir les cas limites).</p>
 */
class LanguageCommentToggleTest {

    private static EditorSession session(String language, String text) {
        EditorSession s = new EditorSession(EditorDocument.of(text));
        s.setLanguage(language);
        // setLanguage déclenche un restyle asynchrone — inutile pour ces
        // tests, et on ne veut pas que l'exécuteur survive au test.
        s.dispose();
        return s;
    }

    private static void selectAll(EditorSession s) {
        s.setSelection(Selection.range(0, s.getText().length()));
    }

    // ── Le comportement Java historique est préservé ───────────────

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
        // Et déroule au second toggle (une seule étape d'undo à chaque fois).
        s.toggleBlockComment();
        assertEquals("hello", s.getText());
    }

    // ── Python : commentaires de ligne '#', bloc = no-op ──────────

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

    // ── XML : le toggle de ligne retombe sur la paire de bloc (VS Code) ──

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
        // La ligne vide garde une paire minimale (pas d'espaces internes
        // à retirer à l'undo).
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

    // ── Markdown : même paire de bloc que la famille XML ──────────

    @Test
    void markdown_toggleLineComment_wrapsInHtmlPair() {
        EditorSession s = session("markdown", "# Title");
        selectAll(s);
        s.toggleLineComment();
        assertEquals("<!-- # Title -->", s.getText());
        s.toggleLineComment();
        assertEquals("# Title", s.getText());
    }

    // ── JSON : les deux toggles sont des no-ops ───────────────────

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

    // ── Shell ────────────────────────────────────────────────────

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
        // Un langage personnalisé inconnu de la table — ex. Vimscript.
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

    // ── Intégration undo ─────────────────────────────────────────

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
