package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarks for {@link EditorSession} — measures the cost of
 * the hot paths: commitText, undo/redo, style splice, line lookups.
 *
 * <p>Not a full JMH suite — lightweight timing assertions that print to
 * stdout and verify the engine stays responsive on typical document sizes.
 *
 * <p>v1.0.8 — stability focus.
 */
class EditorSessionBenchmark {

    private static final int SMALL_DOC_LINES = 100;
    private static final int MEDIUM_DOC_LINES = 1000;
    private static final int LARGE_DOC_LINES = 5000;

    @Test
    void benchmark_commitText_singleChar_mediumDoc() {
        EditorSession s = buildSession(MEDIUM_DOC_LINES);
        // Place caret in the middle.
        int midOffset = s.getDocument().length() / 2;
        s.setSelection(midOffset);
        // Warm up.
        for (int i = 0; i < 100; i++) {
            s.typeChar('x');
            s.backspace();
        }
        // Benchmark: 1000 single-char inserts.
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            s.typeChar('x');
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 1000.0;
        System.out.printf("[benchmark] typeChar (1000-line doc): %.1f ns/op (%.2f ms total)%n",
            avgNs, elapsedNs / 1e6);
        // A single-char insert on a 1000-line doc should be under 1ms.
        assertTrue(avgNs < 1_000_000,
            "typeChar too slow: " + avgNs + " ns/op (expected < 1000000 ns/op)");
    }

    @Test
    void benchmark_undoRedo_largeDoc() {
        EditorSession s = buildSession(LARGE_DOC_LINES);
        // Apply 100 edits at different positions.
        for (int i = 0; i < 100; i++) {
            int offset = (i * 100) % s.getDocument().length();
            s.setSelection(offset);
            s.typeChar('y');
        }
        // Benchmark undo.
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            s.undo();
        }
        long undoNs = System.nanoTime() - start;
        // Benchmark redo.
        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            s.redo();
        }
        long redoNs = System.nanoTime() - start;
        System.out.printf("[benchmark] undo (5000-line doc): %.1f ns/op, redo: %.1f ns/op%n",
            undoNs / 100.0, redoNs / 100.0);
        // Undo/redo on a 5000-line doc does a full restyleAll — should be under 2s each (CI headroom).
        assertTrue(undoNs < 2_000_000_000L,
            "undo too slow: " + (undoNs / 1e6) + " ms/op (expected < 2000 ms)");
        assertTrue(redoNs < 2_000_000_000L,
            "redo too slow: " + (redoNs / 1e6) + " ms/op (expected < 2000 ms)");
    }

    @Test
    void benchmark_lineForOffset_largeDoc() {
        EditorDocument doc = buildDocument(LARGE_DOC_LINES);
        // Warm up.
        for (int i = 0; i < 1000; i++) {
            doc.lineForOffset(i * 10);
        }
        // Benchmark.
        long start = System.nanoTime();
        int dummy = 0;
        for (int i = 0; i < 10_000; i++) {
            dummy += doc.lineForOffset(i % (doc.length() + 1));
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 10_000.0;
        System.out.printf("[benchmark] lineForOffset (5000-line doc): %.1f ns/op (dummy=%d)%n",
            avgNs, dummy);
        // Binary search on lineStarts — should be O(log L), sub-100µs.
        assertTrue(avgNs < 100_000,
            "lineForOffset too slow: " + avgNs + " ns/op (expected < 100000 ns/op)");
    }

    @Test
    void benchmark_lineStart_lineEnd_largeDoc() {
        EditorDocument doc = buildDocument(LARGE_DOC_LINES);
        // Warm up.
        for (int i = 0; i < 1000; i++) {
            doc.lineStart(i % doc.lineCount());
            doc.lineEnd(i % doc.lineCount());
        }
        // Benchmark.
        long start = System.nanoTime();
        int dummy = 0;
        for (int i = 0; i < 10_000; i++) {
            int line = i % doc.lineCount();
            dummy += doc.lineStart(line) + doc.lineEnd(line);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 10_000.0;
        System.out.printf("[benchmark] lineStart+lineEnd (5000-line doc): %.1f ns/op (dummy=%d)%n",
            avgNs, dummy);
        // Array index access — should be sub-10µs.
        assertTrue(avgNs < 10_000,
            "lineStart+lineEnd too slow: " + avgNs + " ns/op (expected < 10000 ns/op)");
    }

    @Test
    void benchmark_restyleAll_largeDoc() {
        EditorSession s = buildSession(LARGE_DOC_LINES);
        // Warm up.
        s.setLanguage("java");
        // Benchmark.
        long start = System.nanoTime();
        s.setLanguage("kotlin");
        s.setLanguage("java");
        long elapsedNs = System.nanoTime() - start;
        System.out.printf("[benchmark] restyleAll (5000-line doc, 2 calls): %.2f ms%n",
            elapsedNs / 1e6);
        // restyleAll is O(lines) — 5000 lines should complete in under 2s (CI headroom).
        assertTrue(elapsedNs < 2_000_000_000L,
            "restyleAll too slow: " + (elapsedNs / 1e6) + " ms (expected < 2000 ms)");
    }

    @Test
    void benchmark_styledLinesAccess_largeDoc() {
        EditorSession s = buildSession(LARGE_DOC_LINES);
        // Warm up.
        for (int i = 0; i < 100; i++) {
            s.getStyledLines().size();
        }
        // Benchmark.
        long start = System.nanoTime();
        int dummy = 0;
        for (int i = 0; i < 10_000; i++) {
            dummy += s.getStyledLines().size();
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 10_000.0;
        System.out.printf("[benchmark] getStyledLines (5000-line doc): %.1f ns/op (dummy=%d)%n",
            avgNs, dummy);
        // getStyledLines returns an unmodifiableList wrapper — should be sub-10µs.
        assertTrue(avgNs < 10_000,
            "getStyledLines too slow: " + avgNs + " ns/op (expected < 10000 ns/op)");
    }

    @Test
    void benchmark_insertNewline_splitsStylesCorrectly() {
        // Insert 100 newlines in the middle of a 1000-line doc and verify
        // the styledLines count stays consistent with lineCount.
        EditorSession s = buildSession(MEDIUM_DOC_LINES);
        int initialLines = s.getDocument().lineCount();
        for (int i = 0; i < 100; i++) {
            int offset = s.getDocument().length() / 2;
            s.setSelection(offset);
            s.commitText("\n");
            assertEquals(initialLines + i + 1, s.getDocument().lineCount(),
                "line count must match after newline insert " + i);
            assertEquals(s.getDocument().lineCount(), s.getStyledLines().size(),
                "styledLines must match lineCount after newline insert " + i);
        }
    }

    /** Builds a session with the given number of lines, each ~40 chars. */
    private static EditorSession buildSession(int lineCount) {
        StringBuilder sb = new StringBuilder(lineCount * 50);
        for (int i = 0; i < lineCount; i++) {
            sb.append("int var").append(i).append(" = ").append(i * 2)
              .append("; // comment line ").append(i).append('\n');
        }
        return new EditorSession(EditorDocument.of(sb.toString()));
    }

    /** Builds a document with the given number of lines. */
    private static EditorDocument buildDocument(int lineCount) {
        return buildSession(lineCount).getDocument();
    }
}
