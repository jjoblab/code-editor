package jo.codeeditor.wrap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WrapModelTest {

    @Test
    void constructor_defaultOneRowPerLine() {
        WrapModel m = new WrapModel(10);
        for (int i = 0; i < 10; i++) {
            assertEquals(1, m.rowsOf(i));
        }
    }

    @Test
    void topRow_firstLine() {
        WrapModel m = new WrapModel(5);
        assertEquals(0, m.topRow(0));
    }

    @Test
    void topRow_secondLine() {
        WrapModel m = new WrapModel(5);
        assertEquals(1, m.topRow(1));
    }

    @Test
    void topRow_withWrap() {
        WrapModel m = new WrapModel(3);
        m.setRows(0, 3); // line 0 wraps to 3 rows
        assertEquals(0, m.topRow(0));
        assertEquals(3, m.topRow(1));
        assertEquals(4, m.topRow(2));
    }

    @Test
    void docLineForRow_noWrap() {
        WrapModel m = new WrapModel(5);
        for (int i = 0; i < 5; i++) {
            assertEquals(i, m.docLineForRow(i));
        }
    }

    @Test
    void docLineForRow_withWrap() {
        WrapModel m = new WrapModel(3);
        m.setRows(0, 2); // line 0 takes 2 rows
        assertEquals(0, m.docLineForRow(0));
        assertEquals(0, m.docLineForRow(1));
        assertEquals(1, m.docLineForRow(2));
        assertEquals(2, m.docLineForRow(3));
    }

    @Test
    void resize_grow() {
        WrapModel m = new WrapModel(3);
        m.resize(5);
        assertEquals(1, m.rowsOf(3));
        assertEquals(1, m.rowsOf(4));
    }

    @Test
    void resize_shrink() {
        WrapModel m = new WrapModel(5);
        m.resize(3);
        // Lines 0-2 should still have 1 row each
        assertEquals(1, m.rowsOf(0));
        assertEquals(1, m.rowsOf(2));
    }

    @Test
    void setRows_clampsToOne() {
        WrapModel m = new WrapModel(3);
        m.setRows(0, 0); // should clamp to 1
        assertEquals(1, m.rowsOf(0));
    }

    @Test
    void setRows_outOfRange() {
        WrapModel m = new WrapModel(3);
        m.setRows(-1, 5); // no exception
        m.setRows(10, 5); // no exception
    }

    @Test
    void totalRows() {
        WrapModel m = new WrapModel(3);
        m.setRows(0, 2);
        m.setRows(1, 3);
        // total = 2 + 3 + 1 = 6
        assertEquals(6, m.totalRows());
    }

    @Test
    void rowsOf() {
        WrapModel m = new WrapModel(3);
        m.setRows(1, 4);
        assertEquals(1, m.rowsOf(0));
        assertEquals(4, m.rowsOf(1));
        assertEquals(1, m.rowsOf(2));
    }
}
