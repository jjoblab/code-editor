package jo.codeeditor.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance benchmarks for {@link LineRenderCache}.
 *
 * <p>Not a full JMH suite — these are lightweight timing assertions that
 * verify the cache delivers O(1) hits and that LRU eviction stays cheap
 * even at the 512-entry cap. Run with {@code ./gradlew test --tests
 * LineRenderCacheBenchmark} — the tests print timing to stdout.
 *
 * <p>v1.0.8 — stability focus.
 */
class LineRenderCacheBenchmark {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10_000;
    private static final int CACHE_CAP = LineRenderCache.MAX_ENTRIES;

    @Test
    void benchmark_cacheHit_isO1() {
        LineRenderCache cache = new LineRenderCache();
        // Fill the cache with 100 entries.
        for (int i = 0; i < 100; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 0, 0, null, null, null, null, "L" + i));
        }
        // Warm up.
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            cache.get(i % 100, 1, 0, 0);
        }
        // Benchmark.
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            cache.get(i % 100, 1, 0, 0);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / (double) BENCHMARK_ITERATIONS;
        System.out.printf("[benchmark] cache hit: %.1f ns/op (%d ops in %.2f ms)%n",
            avgNs, BENCHMARK_ITERATIONS, elapsedNs / 1e6);
        // A cache hit should be sub-microsecond. Allow generous headroom for
        // CI / VM jitter — the benchmark is informational, not a hard SLA.
        assertTrue(avgNs < 50_000,
            "cache hit too slow: " + avgNs + " ns/op (expected < 50000 ns/op)");
    }

    @Test
    void benchmark_cacheMiss_isCheap() {
        LineRenderCache cache = new LineRenderCache();
        // Warm up.
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            cache.get(i, 1, 0, 0); // always a miss (different line each time)
        }
        // Benchmark.
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            cache.get(i + WARMUP_ITERATIONS, 1, 0, 0);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / (double) BENCHMARK_ITERATIONS;
        System.out.printf("[benchmark] cache miss: %.1f ns/op (%d ops in %.2f ms)%n",
            avgNs, BENCHMARK_ITERATIONS, elapsedNs / 1e6);
        // A miss (HashMap lookup + return null) should be sub-microsecond.
        assertTrue(avgNs < 50_000,
            "cache miss too slow: " + avgNs + " ns/op (expected < 50000 ns/op)");
    }

    @Test
    void benchmark_lruEviction_at512Entries() {
        LineRenderCache cache = new LineRenderCache();
        // Insert 1000 entries — should trigger LRU eviction down to 512.
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 0, 0, null, null, null, null, "L" + i));
        }
        long elapsedNs = System.nanoTime() - start;
        System.out.printf("[benchmark] insert 1000 (evict to 512): %.2f ms, final size=%d%n",
            elapsedNs / 1e6, cache.size());
        assertEquals(CACHE_CAP, cache.size());
        // Insertion of 1000 entries with eviction should complete in under 500ms
        // (generous headroom for CI).
        assertTrue(elapsedNs < 500_000_000,
            "LRU eviction too slow: " + (elapsedNs / 1e6) + " ms (expected < 500 ms)");
    }

    @Test
    void benchmark_shiftKeys_largeCache() {
        LineRenderCache cache = new LineRenderCache();
        // Fill with 500 entries.
        for (int i = 0; i < 500; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 0, 0, null, null, null, null, "L" + i));
        }
        // Warm up.
        for (int i = 0; i < 100; i++) {
            cache.shiftKeys(250, 1);
            cache.shiftKeys(250, -1);
        }
        // Benchmark.
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            cache.shiftKeys(250, 1);
            cache.shiftKeys(250, -1);
        }
        long elapsedNs = System.nanoTime() - start;
        double avgNs = elapsedNs / 2000.0;
        System.out.printf("[benchmark] shiftKeys (500 entries): %.1f ns/op%n", avgNs);
        // shiftKeys rebuilds the HashMap — should be under 1ms per call for 500 entries.
        assertTrue(avgNs < 1_000_000,
            "shiftKeys too slow: " + avgNs + " ns/op (expected < 1000000 ns/op)");
    }

    @Test
    void benchmark_buildColumnMaps_largeLine() {
        // Simulate the column-map build for a 1000-char line with 50 inlays.
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
        // Building column maps is O(lineLength + inlays) — should be under 100µs.
        assertTrue(avgNs < 100_000,
            "buildColumnMaps too slow: " + avgNs + " ns/op (expected < 100000 ns/op)");
    }

    @Test
    void benchmark_tripleStampValidation_vsSingleStamp() {
        // Verify that triple-stamp validation (rev + inlayRev + semRev) is
        // not significantly slower than single-stamp (rev only).
        LineRenderCache cache = new LineRenderCache();
        for (int i = 0; i < 100; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 2, 3, null, null, null, null, "L" + i));
        }
        // Triple-stamp benchmark.
        long start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            cache.get(i % 100, 1, 2, 3);
        }
        long tripleNs = System.nanoTime() - start;
        // Single-stamp benchmark.
        start = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            cache.get(i % 100, 1);
        }
        long singleNs = System.nanoTime() - start;
        System.out.printf("[benchmark] triple-stamp: %.1f ns/op, single-stamp: %.1f ns/op, ratio: %.2fx%n",
            tripleNs / (double) BENCHMARK_ITERATIONS,
            singleNs / (double) BENCHMARK_ITERATIONS,
            tripleNs / (double) singleNs);
        // Triple-stamp should be at most 5x slower than single-stamp (3 int
        // compares vs 1 — the HashMap lookup dominates, but CI jitter can amplify).
        double ratio = tripleNs / (double) singleNs;
        assertTrue(ratio < 5.0,
            "triple-stamp validation too slow vs single-stamp: " + ratio + "x (expected < 5x)");
    }
}
