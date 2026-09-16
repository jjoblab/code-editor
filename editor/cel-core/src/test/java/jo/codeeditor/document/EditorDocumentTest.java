package jo.codeeditor.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the line-indexed document model.
 */
class EditorDocumentTest {

    // ── Construction ──────────────────────────────────────────────

    @Test
    void of_emptyString() {
        EditorDocument doc = EditorDocument.of("");
        assertEquals(0, doc.length());
        assertEquals(1, doc.lineCount());
        assertEquals("", doc.getText());
    }

    @Test
    void of_singleLine() {
        EditorDocument doc = EditorDocument.of("hello");
        assertEquals(5, doc.length());
        assertEquals(1, doc.lineCount());
        assertEquals("hello", doc.getText());
    }

    @Test
    void of_multiLine() {
        EditorDocument doc = EditorDocument.of("line1\nline2\nline3");
        assertEquals(3, doc.lineCount());
        assertEquals("line1\nline2\nline3", doc.getText());
    }

    @Test
    void of_trailingNewline() {
        EditorDocument doc = EditorDocument.of("hello\n");
        assertEquals(2, doc.lineCount());
        assertEquals(0, doc.lineStart(0));
        assertEquals(6, doc.lineStart(1));
    }

    // ── lineForOffset ─────────────────────────────────────────────

    @Test
    void lineForOffset_basic() {
        EditorDocument doc = EditorDocument.of("abc\ndef\nghi");
        // a=0, b=1, c=2, \n=3, d=4, e=5, f=6, \n=7, g=8, h=9, i=10
        assertEquals(0, doc.lineForOffset(0));
        assertEquals(0, doc.lineForOffset(3));
        assertEquals(1, doc.lineForOffset(4));
        assertEquals(1, doc.lineForOffset(7));
        assertEquals(2, doc.lineForOffset(8));
        assertEquals(2, doc.lineForOffset(10));
    }

    @Test
    void lineForOffset_clamped() {
        EditorDocument doc = EditorDocument.of("hello");
        assertEquals(0, doc.lineForOffset(-1));
        assertEquals(0, doc.lineForOffset(100));
    }

    // ── lineStart / lineEnd ───────────────────────────────────────

    @Test
    void lineStart_multiLine() {
        EditorDocument doc = EditorDocument.of("abc\ndef\nghi");
        assertEquals(0, doc.lineStart(0));
        assertEquals(4, doc.lineStart(1));
        assertEquals(8, doc.lineStart(2));
    }

    @Test
    void lineEnd_multiLine() {
        EditorDocument doc = EditorDocument.of("abc\ndef\nghi");
        assertEquals(3, doc.lineEnd(0));   // 'c' at index 2, newline at 3
        assertEquals(7, doc.lineEnd(1));   // 'f' at index 6, newline at 7
        assertEquals(11, doc.lineEnd(2));  // 'i' at index 10, end = 11
    }

    @Test
    void lineStart_clamped() {
        EditorDocument doc = EditorDocument.of("hello");
        // Clamped to valid range
        assertEquals(0, doc.lineStart(-5));
        assertEquals(0, doc.lineStart(100));
    }

    // ── lineText ──────────────────────────────────────────────────

    @Test
    void lineText_multiLine() {
        EditorDocument doc = EditorDocument.of("hello\nworld\nfoo");
        assertEquals("hello", doc.lineText(0));
        assertEquals("world", doc.lineText(1));
        assertEquals("foo", doc.lineText(2));
    }

    @Test
    void lineText_emptyLine() {
        EditorDocument doc = EditorDocument.of("a\n\nb");
        assertEquals("a", doc.lineText(0));
        assertEquals("", doc.lineText(1));
        assertEquals("b", doc.lineText(2));
    }

    // ── replace ───────────────────────────────────────────────────

    @Test
    void replace_singleLine_insert() {
        EditorDocument doc = EditorDocument.of("hello");
        EditorDocument doc2 = doc.replace(5, 5, " world");
        assertEquals("hello world", doc2.getText());
        assertEquals(1, doc2.lineCount());
    }

    @Test
    void replace_singleLine_delete() {
        EditorDocument doc = EditorDocument.of("hello world");
        EditorDocument doc2 = doc.replace(5, 11, "");
        assertEquals("hello", doc2.getText());
    }

    @Test
    void replace_singleLine_replace() {
        EditorDocument doc = EditorDocument.of("hello world");
        EditorDocument doc2 = doc.replace(6, 11, "there");
        assertEquals("hello there", doc2.getText());
    }

    @Test
    void replace_insertNewline() {
        EditorDocument doc = EditorDocument.of("hello");
        EditorDocument doc2 = doc.replace(5, 5, "\nworld");
        assertEquals("hello\nworld", doc2.getText());
        assertEquals(2, doc2.lineCount());
        assertEquals("hello", doc2.lineText(0));
        assertEquals("world", doc2.lineText(1));
    }

    @Test
    void replace_deleteNewline() {
        EditorDocument doc = EditorDocument.of("hello\nworld");
        EditorDocument doc2 = doc.replace(5, 6, "");
        assertEquals("helloworld", doc2.getText());
        assertEquals(1, doc2.lineCount());
    }

    @Test
    void replace_multiLineInsert() {
        EditorDocument doc = EditorDocument.of("line1\nline3");
        // Insert "line2\n" right after the existing \n (at offset 6)
        EditorDocument doc2 = doc.replace(6, 6, "line2\n");
        assertEquals("line1\nline2\nline3", doc2.getText());
        assertEquals(3, doc2.lineCount());
        assertEquals("line1", doc2.lineText(0));
        assertEquals("line2", doc2.lineText(1));
        assertEquals("line3", doc2.lineText(2));
    }

    @Test
    void replace_preservesUnchangedLines() {
        EditorDocument doc = EditorDocument.of("aaa\nbbb\nccc\nddd");
        EditorDocument doc2 = doc.replace(4, 7, "BBB");
        assertEquals("aaa\nBBB\nccc\nddd", doc2.getText());
        assertEquals(4, doc2.lineCount());
        assertEquals("aaa", doc2.lineText(0));
        assertEquals("BBB", doc2.lineText(1));
        assertEquals("ccc", doc2.lineText(2));
        assertEquals("ddd", doc2.lineText(3));
    }

    @Test
    void replace_revisionIncrements() {
        EditorDocument doc = EditorDocument.of("hello");
        assertEquals(0, doc.getRevision());
        EditorDocument doc2 = doc.replace(5, 5, " world");
        assertEquals(1, doc2.getRevision());
    }

    @Test
    void replace_noOp_returnsSameRevision() {
        EditorDocument doc = EditorDocument.of("hello");
        EditorDocument doc2 = doc.replace(2, 2, "");
        assertEquals(0, doc2.getRevision());
    }

    // ── charAt ────────────────────────────────────────────────────

    @Test
    void charAt_correctAfterReplace() {
        EditorDocument doc = EditorDocument.of("hello");
        EditorDocument doc2 = doc.replace(5, 5, " world");
        assertEquals('h', doc2.charAt(0));
        assertEquals(' ', doc2.charAt(5));
        assertEquals('w', doc2.charAt(6));
    }

    // ── Edge cases ────────────────────────────────────────────────

    @Test
    void replace_entireDocument() {
        EditorDocument doc = EditorDocument.of("old content");
        EditorDocument doc2 = doc.replace(0, doc.length(), "new content");
        assertEquals("new content", doc2.getText());
    }

    @Test
    void replace_atBoundary() {
        EditorDocument doc = EditorDocument.of("abc");
        EditorDocument doc2 = doc.replace(0, 0, "X");
        assertEquals("Xabc", doc2.getText());
        EditorDocument doc3 = doc.replace(3, 3, "X");
        assertEquals("abcX", doc3.getText());
    }

    @Test
    void multipleReplaces_maintainConsistency() {
        EditorDocument doc = EditorDocument.of("line1\nline2\nline3");
        doc = doc.replace(0, 0, "// ");  // comment line 1
        assertEquals("// line1\nline2\nline3", doc.getText());
        assertEquals(3, doc.lineCount());

        // "// line1\n" is 9 chars, so line 2 starts at 9
        doc = doc.replace(9, 9, "// ");  // comment line 2
        assertEquals("// line1\n// line2\nline3", doc.getText());
        assertEquals(3, doc.lineCount());
    }
}
