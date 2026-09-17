package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmarks de performance pour {@link EditorSession} — mesure le coût des
 * hot paths : commitText, undo/redo, splice de styles, lookups de lignes.
 *
 * <p>Pas une suite JMH complète — des assertions de timing légères qui
 * affichent sur stdout et vérifient que le moteur reste réactif sur des
 * tailles de documents typiques.
 */
class EditorSessionBenchmark {

    private static final int SMALL_DOC_LINES = 100;
    private static final int MEDIUM_DOC_LINES = 1000;
    private static final int LARGE_DOC_LINES = 5000;

    @Test
    void benchmark_commitText_singleChar_mediumDoc() {
        EditorSession s = buildSession(MEDIUM_DOC_LINES);
        // Placer le caret au milieu.
        int midOffset = s.getDocument().length() / 2;
        s.setSelection(midOffset);
        // Échauffement.
        for (int i = 0; i < 100; i++) {
            s.typeChar('x');
            s.backspace();
        }
        // Benchmark : 1000 insertions d'un caractère.
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            s.typeChar('x');
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 1000.0;
        System.out.printf("[benchmark] typeChar (1000-line doc): %.1f ns/op (%.2f ms total)%n",
            avgNs, elapsedNs / 1e6);
        // Une insertion d'un caractère sur un doc de 1000 lignes doit
        // rester sous 1 ms.
        assertTrue(avgNs < 1_000_000,
            "typeChar too slow: " + avgNs + " ns/op (expected < 1000000 ns/op)");
    }

    @Test
    void benchmark_undoRedo_largeDoc() {
        EditorSession s = buildSession(LARGE_DOC_LINES);
        // Appliquer 100 éditions à des positions différentes.
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
        // Undo/redo sur un doc de 5000 lignes fait un restyleAll complet —
        // doit rester sous 2 s chacun (marge CI).
        assertTrue(undoNs < 2_000_000_000L,
            "undo too slow: " + (undoNs / 1e6) + " ms/op (expected < 2000 ms)");
        assertTrue(redoNs < 2_000_000_000L,
            "redo too slow: " + (redoNs / 1e6) + " ms/op (expected < 2000 ms)");
    }

    @Test
    void benchmark_lineForOffset_largeDoc() {
        EditorDocument doc = buildDocument(LARGE_DOC_LINES);
        // Échauffement.
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
        // Recherche binaire sur lineStarts — doit être O(log L), sous 100 µs.
        assertTrue(avgNs < 100_000,
            "lineForOffset too slow: " + avgNs + " ns/op (expected < 100000 ns/op)");
    }

    @Test
    void benchmark_lineStart_lineEnd_largeDoc() {
        EditorDocument doc = buildDocument(LARGE_DOC_LINES);
        // Échauffement.
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
        // Accès par index de tableau — doit rester sous 10 µs.
        assertTrue(avgNs < 10_000,
            "lineStart+lineEnd too slow: " + avgNs + " ns/op (expected < 10000 ns/op)");
    }

    @Test
    void benchmark_restyleAll_largeDoc() {
        EditorSession s = buildSession(LARGE_DOC_LINES);
        // Échauffement.
        s.setLanguage("java");
        // Benchmark.
        long start = System.nanoTime();
        s.setLanguage("kotlin");
        s.setLanguage("java");
        long elapsedNs = System.nanoTime() - start;
        System.out.printf("[benchmark] restyleAll (5000-line doc, 2 calls): %.2f ms%n",
            elapsedNs / 1e6);
        // restyleAll est O(lignes) — 5000 lignes doivent se faire en
        // moins de 2 s (marge CI).
        assertTrue(elapsedNs < 2_000_000_000L,
            "restyleAll too slow: " + (elapsedNs / 1e6) + " ms (expected < 2000 ms)");
    }

    @Test
    void benchmark_styledLinesAccess_largeDoc() {
        EditorSession s = buildSession(LARGE_DOC_LINES);
        // Échauffement.
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
        // getStyledLines renvoie un wrapper unmodifiableList — doit rester
        // sous 10 µs.
        assertTrue(avgNs < 10_000,
            "getStyledLines too slow: " + avgNs + " ns/op (expected < 10000 ns/op)");
    }

    @Test
    void benchmark_insertNewline_splitsStylesCorrectly() {
        // Insérer 100 sauts de ligne au milieu d'un doc de 1000 lignes et
        // vérifier que le nombre de styledLines reste cohérent avec
        // lineCount.
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

    /** Construit une session avec le nombre de lignes donné, chacune ~40 caractères. */
    private static EditorSession buildSession(int lineCount) {
        StringBuilder sb = new StringBuilder(lineCount * 50);
        for (int i = 0; i < lineCount; i++) {
            sb.append("int var").append(i).append(" = ").append(i * 2)
              .append("; // comment line ").append(i).append('\n');
        }
        return new EditorSession(EditorDocument.of(sb.toString()));
    }

    /** Construit un document avec le nombre de lignes donné. */
    private static EditorDocument buildDocument(int lineCount) {
        return buildSession(lineCount).getDocument();
    }
}
