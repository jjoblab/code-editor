package jo.codeeditor.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests de la représentation de la sélection de texte.
 */
class SelectionTest {

    @Test
    void cursor_isCollapsed() {
        Selection s = Selection.cursor(5);
        assertTrue(s.isCursor());
        assertEquals(5, s.start);
        assertEquals(5, s.end);
        assertEquals(0, s.length());
    }

    @Test
    void range_isNotCollapsed() {
        Selection s = Selection.range(3, 7);
        assertFalse(s.isCursor());
        assertEquals(3, s.start);
        assertEquals(7, s.end);
        assertEquals(4, s.length());
    }

    @Test
    void constructor_normalizesReversedRange() {
        Selection s = new Selection(10, 5);
        assertEquals(5, s.start);
        assertEquals(10, s.end);
    }

    @Test
    void constructor_throwsOnNegative() {
        assertThrows(IllegalArgumentException.class, () -> new Selection(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new Selection(5, -1));
    }

    @Test
    void shift_movesBoth() {
        Selection s = Selection.range(3, 7);
        Selection shifted = s.shift(10);
        assertEquals(13, shifted.start);
        assertEquals(17, shifted.end);
    }

    @Test
    void adjustForEdit_beforeSelection() {
        Selection s = Selection.range(10, 15);
        // Édition à l'offset 2 : 3 caractères supprimés, 1 caractère inséré (delta = -2)
        Selection adj = s.adjustForEdit(2, 3, 1);
        assertEquals(8, adj.start);
        assertEquals(13, adj.end);
    }

    @Test
    void adjustForEdit_afterSelection() {
        Selection s = Selection.range(2, 5);
        // Édition à l'offset 10 (après la sélection)
        Selection adj = s.adjustForEdit(10, 3, 1);
        assertEquals(2, adj.start);
        assertEquals(5, adj.end);
    }

    @Test
    void adjustForEdit_insideRemovedRange() {
        Selection s = Selection.range(5, 8);
        // L'édition supprime les caractères 3-10
        Selection adj = s.adjustForEdit(3, 7, 0);
        assertEquals(3, adj.start);
        assertEquals(3, adj.end);
    }

    @Test
    void equals_sameRange() {
        Selection a = Selection.range(3, 7);
        Selection b = Selection.range(3, 7);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equals_differentRange() {
        Selection a = Selection.range(3, 7);
        Selection b = Selection.range(3, 8);
        assertNotEquals(a, b);
    }

    @Test
    void toString_format() {
        Selection s = Selection.range(3, 7);
        assertEquals("Selection(3, 7)", s.toString());
    }
}
