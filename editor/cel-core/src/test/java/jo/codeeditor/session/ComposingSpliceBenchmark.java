package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7 (plan 09) — mesure du coût d'épissure des mises à jour de
 * composition : chaque {@code setComposingText} réécrivait TOUTE la
 * région de composition. Le scénario de référence est la frappe CJK :
 * le clavier renvoie le mot complet à chaque caractère tapé
 * (« こ » → « こん » → … → « こんにちは »), l'éditeur épissait donc la
 * région entière à chaque frappe.
 *
 * <p>Consignes (document 25 000 lignes ≈ 1 M de caractères, caret en
 * fin) : la passe R7 (écart minimal — préfixe/suffixe communs, seule la
 * différence médiane est épissée) doit réduire le coût par mise à jour
 * sans toucher au budget de frappe perceptible.</p>
 *
 * <p>Pas une suite JMH — des timings légers affichés sur stdout, avec
 * une borne haute de garde-fou, miroir de {@link EditorSessionBenchmark}.</p>
 */
class ComposingSpliceBenchmark {

    /** Le mot de composition du scénario (5 mises à jour croissantes). */
    private static final String[] KANA_STEPS = {"こ", "こん", "こんに", "こんにち", "こんにちは"};

    private static final int CYCLES = 200;

    @Test
    void benchmark_setComposingText_compositionCJK_hugeDoc() {
        EditorSession s = buildSession(25_000);
        int end = s.getDocument().length();
        s.setSelection(end);

        // Échauffement (cache JIT + mémoïsation de la rope).
        runCycles(s, 20, end);

        long start = System.nanoTime();
        runCycles(s, CYCLES, end);
        long elapsedNs = System.nanoTime() - start;
        int updates = CYCLES * KANA_STEPS.length;
        double avgNs = elapsedNs / (double) updates;
        System.out.printf("[benchmark] setComposingText CJK (25k lignes, caret en fin): "
                + "%.1f ns/op (%.3f ms/op, %d mises à jour)%n", avgNs, avgNs / 1e6, updates);
        // Budget de frappe perceptible : une mise à jour de composition sur
        // un document d'un million de caractères doit rester sous 2 ms.
        assertTrue(avgNs < 2_000_000,
            "setComposingText too slow: " + avgNs + " ns/op (expected < 2000000 ns/op)");
    }

    /**
     * Variante PHRASE LONGUE : la composition croît un caractère à la fois
     * jusqu'à 60 caractères (correction automatique d'une phrase entière,
     * composition CJK longue). L'épissure intégrale réécrivait en moyenne
     * la MOITIÉ de la région à chaque frappe ; l'écart minimal n'épisse
     * que le caractère ajouté.
     */
    @Test
    void benchmark_setComposingText_compositionLongue_hugeDoc() {
        EditorSession s = buildSession(25_000);
        int end = s.getDocument().length();
        s.setSelection(end);
        String word = "こんにちは世界、これはとても長い文章の自動修正のテストです。"; // 60 caractères ≈
        int len = word.codePointCount(0, word.length());
        // Échauffement : un cycle complet.
        composeAndClear(s, word, 1);

        long start = System.nanoTime();
        int cycles = 40;
        composeAndClear(s, word, cycles);
        long elapsedNs = System.nanoTime() - start;
        int updates = cycles * len;
        double avgNs = elapsedNs / (double) updates;
        System.out.printf("[benchmark] setComposingText phrase longue %d car. (25k lignes): "
                + "%.1f ns/op (%.3f ms/op, %d mises à jour)%n", len, avgNs, avgNs / 1e6, updates);
        assertTrue(avgNs < 2_000_000,
            "setComposingText too slow: " + avgNs + " ns/op (expected < 2000000 ns/op)");
    }

    /** Compose le mot par ajouts successifs d'un caractère puis efface. */
    private static void composeAndClear(EditorSession s, String word, int cycles) {
        for (int c = 0; c < cycles; c++) {
            // Croissance : « こ », « こん », … — chaque étape ajoute UN
            // caractère à la composition (code point par code point).
            int cps = word.codePointCount(0, word.length());
            for (int i = 1; i <= cps; i++) {
                int endIdx = word.offsetByCodePoints(0, i);
                s.imeSetComposingText(word.substring(0, endIdx), 1);
            }
            s.imeFinishComposing();
            s.imeDeleteSurrounding(word.length(), 0);
        }
    }

    /** Compose le mot par mises à jour croissantes puis efface (retour à l'état initial). */
    private static void runCycles(EditorSession s, int cycles, int baseEnd) {
        for (int c = 0; c < cycles; c++) {
            for (String step : KANA_STEPS) {
                s.imeSetComposingText(step, 1);
            }
            s.imeFinishComposing();
            s.imeDeleteSurrounding(KANA_STEPS[KANA_STEPS.length - 1].length(), 0);
        }
    }

    private static EditorSession buildSession(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append("int var").append(i).append(" = computeValue(").append(i)
                .append(") + otherThing(").append(i * 7).append(");\n");
        }
        return new EditorSession(EditorDocument.of(sb.toString()));
    }
}
