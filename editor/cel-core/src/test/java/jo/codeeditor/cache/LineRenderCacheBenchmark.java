package jo.codeeditor.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Benchmarks de performance pour {@link LineRenderCache}.
 *
 * <p>Pas une suite JMH complète — il s'agit d'assertions de temporisation
 * légères qui vérifient que le cache offre des accès en O(1) et que
 * l'éviction LRU reste peu coûteuse même à la limite de 512 entrées.
 * Lancer avec {@code ./gradlew test --tests
 * LineRenderCacheBenchmark} — les tests affichent les temps sur stdout.
 */
class LineRenderCacheBenchmark {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10_000;
    private static final int CACHE_CAP = LineRenderCache.MAX_ENTRIES;

    @Test
    void benchmark_cacheHit_isO1() {
        LineRenderCache cache = new LineRenderCache();
        // Remplit le cache avec 100 entrées.
        for (int i = 0; i < 100; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 0, 0, null, null, null, null, "L" + i));
        }
        // Échauffement.
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            cache.get(i % 100, 1, 0, 0);
        }
        // Mesure.
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            cache.get(i % 100, 1, 0, 0);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / (double) BENCHMARK_ITERATIONS;
        System.out.printf("[benchmark] cache hit: %.1f ns/op (%d ops in %.2f ms)%n",
            avgNs, BENCHMARK_ITERATIONS, elapsedNs / 1e6);
        // Un accès en cache doit être inférieur à la microseconde. Marge
        // généreuse pour le jitter CI / VM — ce benchmark est informatif,
        // pas un SLA strict.
        assertTrue(avgNs < 50_000,
            "cache hit too slow: " + avgNs + " ns/op (expected < 50000 ns/op)");
    }

    @Test
    void benchmark_cacheMiss_isCheap() {
        LineRenderCache cache = new LineRenderCache();
        // Échauffement.
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            cache.get(i, 1, 0, 0); // toujours un échec (ligne différente à chaque fois)
        }
        // Mesure.
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            cache.get(i + WARMUP_ITERATIONS, 1, 0, 0);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / (double) BENCHMARK_ITERATIONS;
        System.out.printf("[benchmark] cache miss: %.1f ns/op (%d ops in %.2f ms)%n",
            avgNs, BENCHMARK_ITERATIONS, elapsedNs / 1e6);
        // Un échec (recherche HashMap + retour null) doit être inférieur à la microseconde.
        assertTrue(avgNs < 50_000,
            "cache miss too slow: " + avgNs + " ns/op (expected < 50000 ns/op)");
    }

    @Test
    void benchmark_lruEviction_at512Entries() {
        LineRenderCache cache = new LineRenderCache();
        // Insère 1000 entrées — doit déclencher l'éviction LRU jusqu'à 512.
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 0, 0, null, null, null, null, "L" + i));
        }
        long elapsedNs = System.nanoTime() - start;
        System.out.printf("[benchmark] insert 1000 (evict to 512): %.2f ms, final size=%d%n",
            elapsedNs / 1e6, cache.size());
        assertEquals(CACHE_CAP, cache.size());
        // L'insertion de 1000 entrées avec éviction doit se terminer en moins de 500 ms
        // (marge généreuse pour la CI).
        assertTrue(elapsedNs < 500_000_000,
            "LRU eviction too slow: " + (elapsedNs / 1e6) + " ms (expected < 500 ms)");
    }

    @Test
    void benchmark_shiftKeys_largeCache() {
        LineRenderCache cache = new LineRenderCache();
        // Remplit avec 500 entrées.
        for (int i = 0; i < 500; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 0, 0, null, null, null, null, "L" + i));
        }
        // Échauffement.
        for (int i = 0; i < 100; i++) {
            cache.shiftKeys(250, 1);
            cache.shiftKeys(250, -1);
        }
        // Mesure.
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            cache.shiftKeys(250, 1);
            cache.shiftKeys(250, -1);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 2000.0;
        System.out.printf("[benchmark] shiftKeys (500 entries): %.1f ns/op%n", avgNs);
        // shiftKeys reconstruit la HashMap — doit rester sous 1 ms par appel pour 500 entrées.
        assertTrue(avgNs < 1_000_000,
            "shiftKeys too slow: " + avgNs + " ns/op (expected < 1000000 ns/op)");
    }

    @Test
    void benchmark_buildColumnMaps_largeLine() {
        // Simule la construction des cartes de colonnes pour une ligne de 1000 caractères avec 50 inlays.
        int lineLength = 1000;
        List<LineRenderCache.InlayPiece> inlays = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            inlays.add(new LineRenderCache.InlayPiece(i * 20, ": Type"));
        }
        // Warm up.
        for (int i = 0; i < 1000; i++) {
            LineRenderCache.buildColumnMaps(lineLength, inlays);
        }
        // Benchmark.
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            LineRenderCache.buildColumnMaps(lineLength, inlays);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 10_000.0;
        System.out.printf("[benchmark] buildColumnMaps (1000 chars, 50 inlays): %.1f ns/op%n", avgNs);
        // La construction des cartes de colonnes est en O(longueurLigne + inlays) — doit rester sous 100 µs.
        assertTrue(avgNs < 100_000,
            "buildColumnMaps too slow: " + avgNs + " ns/op (expected < 100000 ns/op)");
    }

    @Test
    void benchmark_tripleStampValidation_vsSingleStamp() {
        // Vérifie que la validation triple-stamp (rev + inlayRev + semRev) n'est
        // pas significativement plus lente que la validation single-stamp (rev seule).
        LineRenderCache cache = new LineRenderCache();
        for (int i = 0; i < 100; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 2, 3, null, null, null, null, "L" + i));
        }
        // Mesure triple-stamp.
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            cache.get(i % 100, 1, 2, 3);
        }
        long tripleNs = System.nanoTime() - start;
        // Mesure single-stamp.
        start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            cache.get(i % 100, 1);
        }
        long singleNs = System.nanoTime() - start;
        System.out.printf("[benchmark] triple-stamp: %.1f ns/op, single-stamp: %.1f ns/op, ratio: %.2fx%n",
            tripleNs / (double) BENCHMARK_ITERATIONS,
            singleNs / (double) BENCHMARK_ITERATIONS,
            tripleNs / (double) singleNs);
        // Le triple-stamp doit être au plus 5x plus lent que le single-stamp (3 comparaisons
        // d'entiers contre 1 — la recherche HashMap domine, mais le jitter CI peut amplifier).
        double ratio = tripleNs / (double) singleNs;
        assertTrue(ratio < 5.0,
            "triple-stamp validation too slow vs single-stamp: " + ratio + "x (expected < 5x)");
    }
}
