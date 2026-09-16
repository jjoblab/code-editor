package jo.codeeditor.cache;

import java.util.*;

/**
 * Per-line layout/render cache with revision-based invalidation.
 * Supports inlay pieces (phantom text) and semantic spans.
 * Ported from CodeAssist LineRenderCache.kt.
 *
 * <p>v1.0.7 — extended with triple-stamp validation ({@code rev} +
 * {@code inlayRev} + {@code semRev}), an opaque {@code layout} payload
 * (typed by the caller — typically a {@code StyledLine} or a wrapper
 * holding the per-line filtered inlays/sem spans + column maps), a
 * {@code lastUsed} monotonic stamp for LRU, and a hard cap of 512
 * entries with LRU eviction.
 *
 * <p>Per-line revision stamps (Gap 9b) — a global revision counter
 * would invalidate every cached line on every edit, defeating the
 * cache. The caller bumps a per-line stamp only for the lines that
 * were actually re-tokenized, and the cache validates the entry
 * against the triple (rev, inlayRev, semRev) it was stored with.
 
 *
 * @since v1.0.7
*/
public class LineRenderCache {

    /** Hard cap on cached entries. LRU eviction kicks in above this. */
    public static final int MAX_ENTRIES = 512;

    /**
     * A piece of phantom/inlay text inserted at a column.
     */
    public static final class InlayPiece {
        public final int col;
        public final String text;

        public InlayPiece(int col, String text) {
            this.col = col;
            this.text = text;
        }

        @Override
        public String toString() {
            return "InlayPiece(col=" + col + ", text=\"" + text + "\")";
        }
    }

    /**
     * A semantic highlight span.
     */
    public static final class SemSpan {
        public final int start;
        public final int end;
        public final int color;

        public SemSpan(int start, int end, int color) {
            this.start = start;
            this.end = end;
            this.color = color;
        }

        @Override
        public String toString() {
            return "SemSpan([" + start + "," + end + ") color=" + Integer.toHexString(color) + ")";
        }
    }

    /**
     * Cached layout data for a single line.
     *
     * <p>{@code revision} is the per-line text revision stamp at the time
     * of caching. {@code inlayRev} / {@code semRev} are the global inlay
     * hint / semantic token revision stamps at the time of caching.
     * {@code layout} is an opaque payload set by the caller (e.g. the
     * view's per-line filtered layout). {@code lastUsed} is bumped on
     * every {@link #get} hit so the LRU eviction in {@link #put} can
     * pick the least-recently-used entry.
     */
    public static final class LineCacheEntry {
        public final int line;
        public final int revision;
        public final int inlayRev;
        public final int semRev;
        public final List<InlayPiece> inlays;
        public final List<SemSpan> semSpans;
        /** Maps raw column to visual column (accounting for inlays before it). */
        public final int[] rawToVisual;
        /** Maps visual column back to raw column. */
        public final int[] visualToRaw;
        /** Opaque caller-typed payload (e.g. a StyledLine or a wrapper). */
        public Object layout;
        /** Monotonic stamp bumped on every cache hit, used for LRU eviction. */
        public long lastUsed;

        /**
         * Backward-compatible constructor (v1.0.6) — single text revision,
         * no inlay/sem stamps, no layout payload.
         */
        public LineCacheEntry(int line, int revision, List<InlayPiece> inlays,
                              List<SemSpan> semSpans, int[] rawToVisual, int[] visualToRaw) {
            this(line, revision, 0, 0, inlays, semSpans, rawToVisual, visualToRaw, null);
        }

        /**
         * Full constructor (v1.0.7) — triple-stamp validation + layout payload.
         */
        public LineCacheEntry(int line, int revision, int inlayRev, int semRev,
                              List<InlayPiece> inlays, List<SemSpan> semSpans,
                              int[] rawToVisual, int[] visualToRaw, Object layout) {
            this.line = line;
            this.revision = revision;
            this.inlayRev = inlayRev;
            this.semRev = semRev;
            this.inlays = inlays != null ? Collections.unmodifiableList(inlays) : Collections.emptyList();
            this.semSpans = semSpans != null ? Collections.unmodifiableList(semSpans) : Collections.emptyList();
            this.rawToVisual = rawToVisual;
            this.visualToRaw = visualToRaw;
            this.layout = layout;
            this.lastUsed = 0;
        }
    }

    private final Map<Integer, LineCacheEntry> cache = new LinkedHashMap<Integer, LineCacheEntry>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, LineCacheEntry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    /**
     * v3.34.0 — Per-line revision stamps stored in PARALLEL PRIMITIVE
     * ARRAYS instead of {@code HashMap<Integer, Integer>}.
     *
     * <p>Port of the CodeAssist v3.20 {@code LineOverlay<T>} fix (commit
     * 62f7b7a00): the old implementation re-keyed the maps with
     * {@code mapKeys{}}-style rebuilds — one full HashMap reallocation
     * plus Integer boxing for EVERY line — on every line splice, i.e.
     * on every Enter keypress. CodeAssist measured 0.309 ms + 426 KB of
     * allocations per newline on a 4000-line file (1 GC every 8 newlines)
     * and brought it to 0.031 ms / 4.3 KB with two {@code copyInto}
     * region moves; this port uses {@code System.arraycopy} the same way.
     *
     * <p>Dense-by-line arrays are the right trade here: line numbers are
     * the index domain, the arrays grow geometrically, and 50k lines of
     * {@code int[]} is ~400 KB vs several MB of boxed HashMap entries.
     */
    private static final class LineOverlay {
        /** Sentinel for "no revision recorded for this line" — matches the
         *  old HashMap#getOrDefault(line, -1) semantics for lines that were
         *  spliced into existence without ever being written. */
        private static final int ABSENT = Integer.MIN_VALUE;

        int[] values = new int[16];
        int length = 0;

        int get(int line, int notFound) {
            if (line < 0 || line >= length) return notFound;
            int v = values[line];
            return v == ABSENT ? notFound : v;
        }

        void put(int line, int value) {
            ensure(line + 1);
            if (line >= length) {
                java.util.Arrays.fill(values, length, line + 1, ABSENT);
                length = line + 1;
            }
            values[line] = value;
        }

        void splice(int fromLine, int delta) {
            if (delta == 0 || length == 0) return;
            if (fromLine >= length) return;
            if (delta > 0) {
                ensure(length + delta);
                System.arraycopy(values, fromLine, values, fromLine + delta, length - fromLine);
                java.util.Arrays.fill(values, fromLine, fromLine + delta, ABSENT);
                length += delta;
            } else {
                int remove = Math.min(-delta, length - fromLine);
                System.arraycopy(values, fromLine + remove, values, fromLine, length - fromLine - remove);
                length -= remove;
            }
        }

        void removeFrom(int fromLine) {
            if (fromLine < length) length = Math.max(0, fromLine);
        }

        void clear() {
            length = 0;
        }

        private void ensure(int cap) {
            if (cap <= values.length) return;
            int newCap = Math.max(cap, values.length * 2);
            values = java.util.Arrays.copyOf(values, newCap);
        }
    }

    /** Per-line revision stamps for inlay invalidation (v3.34.0: array-backed). */
    private final LineOverlay inlayRevisions = new LineOverlay();
    /** Per-line revision stamps for semantic span invalidation (v3.34.0: array-backed). */
    private final LineOverlay semRevisions = new LineOverlay();
    /** Monotonic counter — every {@link #get} hit bumps this and assigns it
     *  to the entry's {@code lastUsed}. Used by {@link #evictLru(int)} when
     *  the {@link LinkedHashMap} hasn't collected the stale entry yet. */
    private long clock = 0;

    /**
     * Get a cached line entry, or null if stale/missing. Single-stamp
     * validation (text revision only) — kept for backward compatibility
     * with v1.0.6 callers and tests.
     */
    public LineCacheEntry get(int line, int currentRevision) {
        LineCacheEntry entry = cache.get(line);
        if (entry != null && entry.revision == currentRevision) {
            entry.lastUsed = ++clock;
            return entry;
        }
        return null;
    }

    /**
     * Get a cached line entry, or null if stale/missing. Triple-stamp
     * validation (text + inlay + sem). Use this in the draw path so a
     * global inlay/sem update doesn't invalidate every cached line —
     * only lines whose inlay/sem rev actually changed miss.
     */
    public LineCacheEntry get(int line, int rev, int inlayRev, int semRev) {
        LineCacheEntry entry = cache.get(line);
        if (entry != null
                && entry.revision == rev
                && entry.inlayRev == inlayRev
                && entry.semRev == semRev) {
            entry.lastUsed = ++clock;
            return entry;
        }
        return null;
    }

    /**
     * Store a line cache entry. Evicts the least-recently-used entry
     * when the cache exceeds {@link #MAX_ENTRIES}.
     */
    public void put(LineCacheEntry entry) {
        entry.lastUsed = ++clock;
        cache.put(entry.line, entry);
        // Defensive eviction — the LinkedHashMap's removeEldestEntry already
        // caps the size, but LRU semantics depend on access-order which is
        // only refreshed by get/put. If many entries are inserted without
        // being read, the eldest is evicted automatically.
        if (cache.size() > MAX_ENTRIES) {
            evictLru(MAX_ENTRIES);
        }
    }

    /**
     * Manually evict the oldest entries until the cache fits in {@code cap}.
     * Public so the host can force a shrink on memory pressure.
     */
    public void evictLru(int cap) {
        if (cache.size() <= cap) return;
        // Build a list of (line, lastUsed) and sort by lastUsed ascending.
        List<long[]> stamps = new ArrayList<>(cache.size());
        for (Map.Entry<Integer, LineCacheEntry> e : cache.entrySet()) {
            stamps.add(new long[]{e.getKey(), e.getValue().lastUsed});
        }
        stamps.sort((a, b) -> Long.compare(a[1], b[1]));
        int toRemove = cache.size() - cap;
        for (int i = 0; i < toRemove && i < stamps.size(); i++) {
            cache.remove((int) stamps.get(i)[0]);
        }
    }

    /**
     * Invalidate all cached entries from the given line onward.
     */
    public void invalidateFrom(int line) {
        cache.entrySet().removeIf(e -> e.getKey() >= line);
        inlayRevisions.removeFrom(line);
        semRevisions.removeFrom(line);
    }

    /**
     * Shift cache keys after a line splice (insertion/deletion).
     * v3.34.0: the two revision overlays now SPLICE in place via
     * System.arraycopy (CodeAssist LineOverlay) instead of rebuilding
     * HashMaps; the bounded 512-entry layout cache still re-keys (a
     * map cannot splice) but into a pre-sized replacement.
     */
    public void shiftKeys(int fromLine, int delta) {
        if (delta == 0) return;
        Map<Integer, LineCacheEntry> newCache = new LinkedHashMap<>(Math.max(64, cache.size() * 2), 0.75f, true);
        for (var entry : cache.entrySet()) {
            int key = entry.getKey();
            if (key < fromLine) {
                newCache.put(key, entry.getValue());
            } else {
                int newKey = key + delta;
                if (newKey >= 0) {
                    newCache.put(newKey, entry.getValue());
                }
            }
        }
        cache.clear();
        cache.putAll(newCache);

        // Shift the revision overlays — O(spliced region), zero boxing.
        inlayRevisions.splice(fromLine, delta);
        semRevisions.splice(fromLine, delta);
    }

    /**
     * Build raw-to-visual and visual-to-raw column maps from inlay pieces.
     *
     * @param lineLength the raw line length
     * @param inlays     inlay pieces sorted by col
     * @return [rawToVisual, visualToRaw]
     */
    public static int[][] buildColumnMaps(int lineLength, List<InlayPiece> inlays) {
        // rawToVisual[rawCol] = visualCol
        int[] rawToVisual = new int[lineLength + 1];
        int[] visualToRaw = new int[lineLength + 1 + totalInlayLength(inlays)];

        int rawCol = 0;
        int visCol = 0;
        int inlayIdx = 0;

        while (rawCol <= lineLength) {
            rawToVisual[rawCol] = visCol;
            visualToRaw[visCol] = rawCol;

            // Insert any inlays at this raw col
            while (inlayIdx < inlays.size() && inlays.get(inlayIdx).col == rawCol) {
                for (int j = 0; j < inlays.get(inlayIdx).text.length(); j++) {
                    visCol++;
                    if (visCol < visualToRaw.length) {
                        visualToRaw[visCol] = rawCol; // maps back to the raw col
                    }
                }
                inlayIdx++;
            }

            if (rawCol < lineLength) {
                rawCol++;
                visCol++;
            } else {
                break;
            }
        }

        return new int[][]{rawToVisual, visualToRaw};
    }

    private static int totalInlayLength(List<InlayPiece> inlays) {
        int total = 0;
        if (inlays == null) return total;
        for (InlayPiece p : inlays) total += p.text.length();
        return total;
    }

    /**
     * Update the inlay revision for a line.
     */
    public void setInlayRevision(int line, int revision) {
        inlayRevisions.put(line, revision);
    }

    /**
     * Get the inlay revision for a line.
     */
    public int getInlayRevision(int line) {
        return inlayRevisions.get(line, -1);
    }

    /**
     * Update the semantic spans revision for a line.
     */
    public void setSemRevision(int line, int revision) {
        semRevisions.put(line, revision);
    }

    /**
     * Get the semantic spans revision for a line.
     */
    public int getSemRevision(int line) {
        return semRevisions.get(line, -1);
    }

    /**
     * Clear the entire cache.
     */
    public void clear() {
        cache.clear();
        inlayRevisions.clear();
        semRevisions.clear();
    }

    /**
     * Returns the number of cached entries.
     */
    public int size() {
        return cache.size();
    }
}
