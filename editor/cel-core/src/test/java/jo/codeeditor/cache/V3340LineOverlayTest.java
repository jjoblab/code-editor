package jo.codeeditor.cache;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3.34.0 regression tests — LineOverlay-backed revision stamps.
 *
 * <p>Port of the CodeAssist v3.20 {@code LineOverlay<T>} pattern: the
 * per-line inlay/sem revision stamps now live in parallel primitive arrays
 * spliced with {@code System.arraycopy} instead of re-built HashMaps. These
 * tests pin the OBSERVABLE semantics that must not change: absent lines
 * return -1 (both before any write and after being spliced into existence),
 * splices move values with their lines, and removeFrom truncates.</p>
 */
class V3340LineOverlayTest {

    @Test
    void absentLine_returnsNotFound() {
        LineRenderCache c = new LineRenderCache();
        assertEquals(-1, c.getInlayRevision(0));
        assertEquals(-1, c.getSemRevision(42));
    }

    @Test
    void putThenGet_roundTrip() {
        LineRenderCache c = new LineRenderCache();
        c.setInlayRevision(0, 7);
        c.setInlayRevision(5, 9);
        c.setSemRevision(3, 11);
        assertEquals(7, c.getInlayRevision(0));
        assertEquals(9, c.getInlayRevision(5));
        assertEquals(-1, c.getInlayRevision(1), "never-written line stays absent");
        assertEquals(11, c.getSemRevision(3));
    }

    @Test
    void splice_insertion_newLinesAreAbsent() {
        LineRenderCache c = new LineRenderCache();
        for (int i = 0; i < 6; i++) c.setInlayRevision(i, 100 + i);
        // Insert 2 lines at index 2 (lines 2..3 are new).
        c.shiftKeys(2, 2);
        assertEquals(100, c.getInlayRevision(0));
        assertEquals(101, c.getInlayRevision(1));
        assertEquals(-1, c.getInlayRevision(2), "spliced-in line must be absent");
        assertEquals(-1, c.getInlayRevision(3), "spliced-in line must be absent");
        assertEquals(102, c.getInlayRevision(4), "old line 2 shifts to 4");
        assertEquals(103, c.getInlayRevision(5), "old line 3 shifts to 5");
        assertEquals(104, c.getInlayRevision(6), "old line 4 shifts to 6");
        assertEquals(105, c.getInlayRevision(7), "old line 5 shifts to 7");
    }

    @Test
    void splice_deletion_valuesCollapse() {
        LineRenderCache c = new LineRenderCache();
        for (int i = 0; i < 6; i++) c.setSemRevision(i, 200 + i);
        // Delete 2 lines at index 1 (lines 1..2 removed).
        c.shiftKeys(1, -2);
        assertEquals(200, c.getSemRevision(0));
        assertEquals(203, c.getSemRevision(1), "old line 3 collapses to 1");
        assertEquals(205, c.getSemRevision(3), "old line 5 collapses to 3");
        assertEquals(-1, c.getSemRevision(4), "deleted tail is gone");
    }

    @Test
    void removeFrom_truncatesOnlyTail() {
        LineRenderCache c = new LineRenderCache();
        for (int i = 0; i < 5; i++) c.setInlayRevision(i, 300 + i);
        c.invalidateFrom(3);
        assertEquals(302, c.getInlayRevision(2));
        assertEquals(-1, c.getInlayRevision(3), "line 3 invalidated");
        assertEquals(-1, c.getInlayRevision(4), "line 4 invalidated");
    }

    @Test
    void clear_resetsEverything() {
        LineRenderCache c = new LineRenderCache();
        for (int i = 0; i < 5; i++) {
            c.setInlayRevision(i, i);
            c.setSemRevision(i, i);
        }
        c.clear();
        for (int i = 0; i < 5; i++) {
            assertEquals(-1, c.getInlayRevision(i));
            assertEquals(-1, c.getSemRevision(i));
        }
    }

    @Test
    void shiftKeys_layoutEntriesStillShift() {
        // The bounded 512-entry layout cache must keep its documented
        // shiftKeys contract (v1.0.7 tests) now that the overlays splice.
        LineRenderCache c = new LineRenderCache();
        List<LineRenderCache.InlayPiece> noInlays = new ArrayList<>();
        List<LineRenderCache.SemSpan> noSpans = new ArrayList<>();
        c.put(new LineRenderCache.LineCacheEntry(
            4, 1, 0, 0, noInlays, noSpans, new int[2], new int[2], "layout-4"));
        c.shiftKeys(2, 3);
        LineRenderCache.LineCacheEntry moved = c.get(7, 1, 0, 0);
        assertNotNull(moved, "entry for line 4 moves to line 7 after +3 at 2");
        assertEquals("layout-4", moved.layout);
        assertNull(c.get(4, 1, 0, 0), "old key 4 is vacated");
    }
}
