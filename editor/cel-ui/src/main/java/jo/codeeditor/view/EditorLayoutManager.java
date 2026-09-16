package jo.codeeditor.view;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-line layout cache for efficient text rendering.
 * <p>
 * Only lines that have been edited need re-layout. Each entry stores
 * the revision at which it was laid out, so stale entries are detected
 * and refreshed on demand.
 
 *
 * @since v1.0.0
*/
public class EditorLayoutManager {

    /**
     * Cached layout data for a single line.
     */
    public static class LineLayout {
        public final int line;
        public final int revision;
        public final float[] charPositions; // X position for each character
        public final float totalWidth;

        public LineLayout(int line, int revision, float[] charPositions, float totalWidth) {
            this.line = line;
            this.revision = revision;
            this.charPositions = charPositions;
            this.totalWidth = totalWidth;
        }
    }

    private final Map<Integer, LineLayout> cache = new HashMap<>();
    private int lastValidRevision = -1;

    /**
     * Returns the cached layout for a line, or null if not cached / stale.
     */
    public LineLayout getLayout(int line, int currentRevision) {
        LineLayout layout = cache.get(line);
        if (layout != null && layout.revision == currentRevision) {
            return layout;
        }
        return null;
    }

    /**
     * Stores a line layout in the cache.
     */
    public void putLayout(LineLayout layout) {
        cache.put(layout.line, layout);
    }

    /**
     * Invalidates all cached layouts from the given line onward.
     * Used after an edit to mark affected lines for re-layout.
     */
    public void invalidateFrom(int line) {
        cache.entrySet().removeIf(e -> e.getKey() >= line);
    }

    /**
     * Clears the entire cache.
     */
    public void clear() {
        cache.clear();
    }

    /**
     * Returns the number of cached entries.
     */
    public int size() {
        return cache.size();
    }
}
