package jo.codeeditor.cache;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LineRenderCacheTest {

    @Test
    void inlayPiece_creation() {
        var p = new LineRenderCache.InlayPiece(5, ": String");
        assertEquals(5, p.col);
        assertEquals(": String", p.text);
    }

    @Test
    void semSpan_creation() {
        var s = new LineRenderCache.SemSpan(0, 10, 0xFF0000);
        assertEquals(0, s.start);
        assertEquals(10, s.end);
        assertEquals(0xFF0000, s.color);
    }

    @Test
    void putAndGet() {
        var cache = new LineRenderCache();
        var entry = new LineRenderCache.LineCacheEntry(
            0, 1,
            List.of(new LineRenderCache.InlayPiece(3, "xx")),
            List.of(new LineRenderCache.SemSpan(0, 5, 0xFF0000)),
            new int[]{0, 1, 2, 5, 6},
            new int[]{0, 1, 2, 3, 5}
        );
        cache.put(entry);

        var got = cache.get(0, 1);
        assertNotNull(got);
        assertEquals(0, got.line);
        assertEquals(1, got.revision);
        assertEquals(1, got.inlays.size());
        assertEquals("xx", got.inlays.get(0).text);
    }

    @Test
    void get_staleRevision_returnsNull() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(0, 1, null, null, null, null));
        assertNull(cache.get(0, 2)); // révision différente
    }

    @Test
    void get_missingLine_returnsNull() {
        var cache = new LineRenderCache();
        assertNull(cache.get(5, 0));
    }

    @Test
    void invalidateFrom() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(0, 1, null, null, null, null));
        cache.put(new LineRenderCache.LineCacheEntry(1, 1, null, null, null, null));
        cache.put(new LineRenderCache.LineCacheEntry(2, 1, null, null, null, null));

        cache.invalidateFrom(1); // invalide les lignes 1 et au-delà

        assertNotNull(cache.get(0, 1)); // la ligne 0 survit
        assertNull(cache.get(1, 1));   // la ligne 1 a disparu
        assertNull(cache.get(2, 1));   // la ligne 2 a disparu
    }

    @Test
    void shiftKeys() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(0, 1, null, null, null, null));
        cache.put(new LineRenderCache.LineCacheEntry(1, 1, null, null, null, null));
        cache.put(new LineRenderCache.LineCacheEntry(2, 1, null, null, null, null));

        cache.shiftKeys(1, 2); // décale les lignes à partir de 1 de +2

        assertNotNull(cache.get(0, 1)); // la ligne 0 reste en place
        assertNotNull(cache.get(3, 1)); // ancienne ligne 1 → ligne 3
        assertNotNull(cache.get(4, 1)); // ancienne ligne 2 → ligne 4
    }

    @Test
    void lineCacheEntry_rawToVisualMapping() {
        int[] r2v = {0, 1, 2, 5, 6}; // un inlay à la col 3 ajoute 2 colonnes visuelles
        int[] v2r = {0, 1, 2, 3, 5};
        var entry = new LineRenderCache.LineCacheEntry(
            0, 1, List.of(new LineRenderCache.InlayPiece(3, "xx")),
            List.of(), r2v, v2r
        );

        // rawToVisual : colonne brute 3 → colonne visuelle 5
        assertEquals(5, entry.rawToVisual[3]);
        // visualToRaw : vérifie que le mapping existe
        assertEquals(5, entry.visualToRaw.length);
    }

    // ── Validation triple-stamp + LRU + charge utile de layout ───────

    @Test
    void tripleStamp_hit_returnsEntry() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(
            0, 1, 2, 3, null, null, null, null, "layout-0"));
        var got = cache.get(0, 1, 2, 3);
        assertNotNull(got);
        assertEquals("layout-0", got.layout);
    }

    @Test
    void tripleStamp_missOnTextRev_returnsNull() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(0, 1, 2, 3, null, null, null, null, "x"));
        assertNull(cache.get(0, 99, 2, 3));
    }

    @Test
    void tripleStamp_missOnInlayRev_returnsNull() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(0, 1, 2, 3, null, null, null, null, "x"));
        assertNull(cache.get(0, 1, 99, 3));
    }

    @Test
    void tripleStamp_missOnSemRev_returnsNull() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(0, 1, 2, 3, null, null, null, null, "x"));
        assertNull(cache.get(0, 1, 2, 99));
    }

    @Test
    void layoutField_roundTrip_preservesObject() {
        var cache = new LineRenderCache();
        var styled = new jo.codeeditor.highlight.StyledLine(
            java.util.Collections.emptyList(), 0, 0);
        cache.put(new LineRenderCache.LineCacheEntry(
            5, 1, 0, 0, null, null, null, null, styled));
        var got = cache.get(5, 1, 0, 0);
        assertNotNull(got);
        assertSame(styled, got.layout);
    }

    @Test
    void lruEviction_at512Entries() {
        var cache = new LineRenderCache();
        // Insère 600 entrées — le cache doit plafonner à 512.
        for (int i = 0; i < 600; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 0, 0, null, null, null, null, "L" + i));
        }
        assertEquals(512, cache.size());
        // Les entrées les plus anciennes (0..87) doivent être évincées.
        assertNull(cache.get(0, 1, 0, 0));
        assertNull(cache.get(87, 1, 0, 0));
        // Les 512 dernières entrées (88..599) doivent encore être présentes.
        assertNotNull(cache.get(88, 1, 0, 0));
        assertNotNull(cache.get(599, 1, 0, 0));
    }

    @Test
    void lruEviction_bumpOnGet_keepsEntry() {
        var cache = new LineRenderCache();
        // Insère 512 entrées.
        for (int i = 0; i < 512; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 0, 0, null, null, null, null, "L" + i));
        }
        // Accède à l'entrée 0 pour en faire la plus récemment utilisée.
        assertNotNull(cache.get(0, 1, 0, 0));
        // Insère 100 entrées de plus — l'entrée 0 doit survivre (elle vient d'être utilisée).
        for (int i = 512; i < 612; i++) {
            cache.put(new LineRenderCache.LineCacheEntry(
                i, 1, 0, 0, null, null, null, null, "L" + i));
        }
        assertNotNull(cache.get(0, 1, 0, 0));
        // Une entrée intermédiaire doit être évincée.
        boolean anyEvicted = false;
        for (int i = 1; i < 100; i++) {
            if (cache.get(i, 1, 0, 0) == null) {
                anyEvicted = true;
                break;
            }
        }
        assertTrue(anyEvicted);
    }

    @Test
    void shiftKeys_preservesLayoutAndStamps() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(
            1, 10, 20, 30, null, null, null, null, "L1"));
        cache.put(new LineRenderCache.LineCacheEntry(
            2, 11, 21, 31, null, null, null, null, "L2"));
        cache.shiftKeys(1, 2);
        var got = cache.get(3, 10, 20, 30);
        assertNotNull(got);
        assertEquals("L1", got.layout);
        var got2 = cache.get(4, 11, 21, 31);
        assertNotNull(got2);
        assertEquals("L2", got2.layout);
    }

    @Test
    void invalidateFrom_clearsEntries() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(
            0, 1, 0, 0, null, null, null, null, "L0"));
        cache.put(new LineRenderCache.LineCacheEntry(
            1, 1, 0, 0, null, null, null, null, "L1"));
        cache.put(new LineRenderCache.LineCacheEntry(
            2, 1, 0, 0, null, null, null, null, "L2"));
        cache.invalidateFrom(1);
        assertNotNull(cache.get(0, 1, 0, 0));
        assertNull(cache.get(1, 1, 0, 0));
        assertNull(cache.get(2, 1, 0, 0));
    }

    @Test
    void clear_dropsEverything() {
        var cache = new LineRenderCache();
        cache.put(new LineRenderCache.LineCacheEntry(
            0, 1, 0, 0, null, null, null, null, "L0"));
        cache.put(new LineRenderCache.LineCacheEntry(
            1, 1, 0, 0, null, null, null, null, "L1"));
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void buildColumnMaps_withAndWithoutInlays() {
        // Sans inlays — rawToVisual doit être l'identité.
        int[][] noInlays = LineRenderCache.buildColumnMaps(3, java.util.Collections.emptyList());
        assertEquals(0, noInlays[0][0]);
        assertEquals(1, noInlays[0][1]);
        assertEquals(2, noInlays[0][2]);
        assertEquals(3, noInlays[0][3]);
        // Un inlay à la col 1 de longueur 2 — colonnes visuelles décalées.
        int[][] withInlays = LineRenderCache.buildColumnMaps(3,
            java.util.List.of(new LineRenderCache.InlayPiece(1, "ab")));
        // rawToVisual[0] = 0, rawToVisual[1] = 1, rawToVisual[2] = 4 (1 + 2 caractères d'inlay + 1)
        assertEquals(0, withInlays[0][0]);
        assertEquals(1, withInlays[0][1]);
        assertEquals(4, withInlays[0][2]);
    }
}
