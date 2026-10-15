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
    void benchmark_commitText_multiChar_hugeDoc_prefixe() {
        // P-01 (audit 3.69.0) : protocole du lot L0 — document ~1 M de
        // caractères (25 000 lignes), caret en FIN, commitText de
        // 2 caractères × 200. Mesure de base consignée en L0 :
        // 6,38 ms/op — la simulation postfix matérialise le PRÉFIXE
        // ENTIER du document à chaque frappe multi-caractères
        // (subSequence(0, pos).toString() + texte). La complétion
        // postfix ne lit que l'arrière-garde (postfix ≤ 8 caractères,
        // expression courante, retrait de ligne) : après correctif,
        // la vue paresseuse ne copie plus rien et l'op tombe au coût
        // du splice seul.
        final int LINES = 25_000;
        EditorSession s = buildSession(LINES);
        int end = s.getDocument().length();
        s.setSelection(end);
        // Échauffement (cache JIT + memoïsation getText).
        for (int i = 0; i < 20; i++) {
            s.commitText("ab");
        }
        end = s.getDocument().length();
        s.setSelection(end);
        // Benchmark : 200 insertions de 2 caractères en fin de document.
        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            s.commitText("xy");
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 200.0;
        System.out.printf("[benchmark] commitText 2 caractères (25k lignes, caret en fin): "
                + "%.1f ns/op (%.2f ms/op)%n", avgNs, avgNs / 1e6);
        // Budget de frappe perceptible : une frappe multi-caractères sur
        // un document d'un million de caractères doit rester sous 2,5 ms
        // (la base L0 mesurait 6,38 ms/op — le test est ROUGE avant le
        // correctif P-01, VERT après).
        assertTrue(avgNs < 2_500_000,
            "commitText multi-caractères trop lent: " + (avgNs / 1e6)
                + " ms/op (attendu < 2.5 ms/op — P-01)");
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

    @Test
    void benchmark_lineText_cheminDeFrappe_50kLignes() {
        // ★ B19/Lot 4 : mesure l'ancien chemin (getText().substring —
        // matérialise TOUT le document à chaque appel après édition)
        // contre le nouveau (lineText → subText, O(log n + tranche)).
        // Chiffres consignés dans docs/RAPPORT_VERIFICATION.md.
        final int LINES = 50_000;
        EditorDocument doc = buildDocument(LINES);
        // Échauffement des deux chemins.
        for (int i = 0; i < 200; i++) {
            doc.lineText(i % LINES);
            int st = doc.lineStart(i % LINES);
            doc.getText().substring(st, Math.min(st + 30, doc.length()));
        }
        // -- ANCIEN chemin : getText() (cache invalidé par l'édition) +
        // substring.
        doc = doc.replace(100, 100, "x"); // une frappe → cache invalidé
        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            int st = doc.lineStart((i * 37) % LINES);
            String l = doc.getText().substring(st, Math.min(st + 30, doc.length()));
            if (l.isEmpty()) fail();
        }
        long ancienNs = System.nanoTime() - t0;
        // -- NOUVEAU chemin : lineText (subText, pas de matérialisation).
        doc = doc.replace(100, 100, "y"); // cache invalidé à nouveau
        long t1 = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            String l = doc.lineText((i * 37) % LINES);
            if (l.isEmpty()) fail();
        }
        long nouveauNs = System.nanoTime() - t1;
        System.out.printf("[benchmark] lineText 50k lignes (1000 lectures après édition) : "
                + "ancien (getText+substring) %d ns/op, nouveau (subText) %d ns/op, "
                + "gain x%.1f%n",
            ancienNs / 1000, nouveauNs / 1000, (double) ancienNs / Math.max(1, nouveauNs));
        // Le nouveau chemin ne doit pas être plus lent que l'ancien.
        assertTrue(nouveauNs <= ancienNs,
            "lineText (subText) doit battre getText().substring : "
                + nouveauNs + " vs " + ancienNs + " ns");
    }

    @Test
    void benchmark_replace_spliceIndexLignes_50kLignes() {
        // Mesure pour l'étape 2 (index de lignes sous-linéaire) : le
        // splice est O(lignes) par édition. Chiffres consignés dans le
        // compte rendu — la décision d'implémenter une structure
        // sous-linéaire (comptage par nœud / Fenwick) se prend sur ces
        // nombres.
        final int LINES = 50_000;
        EditorDocument doc = buildDocument(LINES);
        // Échauffement.
        for (int i = 0; i < 50; i++) {
            doc = doc.replace(100, 100, "x");
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            doc = doc.replace(100, 100, "x");
        }
        long parEdition = (System.nanoTime() - t0) / 200;
        System.out.printf("[benchmark] replace + splice index (50k lignes) : "
                + "%d ns/édition (%.2f ms)%n", parEdition, parEdition / 1e6);
        // Budget de frappe : une édition sur 50k lignes doit rester sous
        // le seuil perceptible (~2 ms).
        assertTrue(parEdition < 2_000_000,
            "replace trop lent sur 50k lignes : " + parEdition + " ns");
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
