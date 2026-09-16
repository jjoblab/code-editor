package jo.codeeditor.rope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the immutable balanced rope data structure.
 */
class RopeTest {

    // ── Construction ──────────────────────────────────────────────

    @Test
    void emptyRope_hasZeroLength() {
        Rope r = Rope.EMPTY;
        assertEquals(0, r.length());
        assertEquals("", r.toString());
    }

    @Test
    void fromString_shortText_createsLeaf() {
        Rope r = Rope.fromString("hello");
        assertEquals(5, r.length());
        assertEquals("hello", r.toString());
        assertInstanceOf(Rope.Leaf.class, r);
    }

    @Test
    void fromString_longText_createsBranches() {
        String text = "A".repeat(2000);
        Rope r = Rope.fromString(text);
        assertEquals(2000, r.length());
        assertEquals(text, r.toString());
        assertInstanceOf(Rope.Branch.class, r);
    }

    @Test
    void fromString_null_returnsEmpty() {
        Rope r = Rope.fromString(null);
        assertSame(Rope.EMPTY, r);
    }

    @Test
    void fromString_empty_returnsEmpty() {
        Rope r = Rope.fromString("");
        assertSame(Rope.EMPTY, r);
    }

    // ── charAt ────────────────────────────────────────────────────

    @Test
    void charAt_returnsCorrectChars() {
        Rope r = Rope.fromString("abcdef");
        assertEquals('a', r.charAt(0));
        assertEquals('c', r.charAt(2));
        assertEquals('f', r.charAt(5));
    }

    @Test
    void charAt_throwsOnNegativeIndex() {
        Rope r = Rope.fromString("hello");
        assertThrows(IndexOutOfBoundsException.class, () -> r.charAt(-1));
    }

    @Test
    void charAt_throwsOnOutOfBounds() {
        Rope r = Rope.fromString("hello");
        assertThrows(IndexOutOfBoundsException.class, () -> r.charAt(5));
    }

    @Test
    void charAt_worksAcrossBranches() {
        String text = "A".repeat(600) + "B".repeat(600);
        Rope r = Rope.fromString(text);
        assertInstanceOf(Rope.Branch.class, r);
        assertEquals('A', r.charAt(0));
        assertEquals('A', r.charAt(599));
        assertEquals('B', r.charAt(600));
        assertEquals('B', r.charAt(1199));
    }

    // ── substring / sub ───────────────────────────────────────────

    @Test
    void sub_fullRange_returnsSameContent() {
        Rope r = Rope.fromString("hello world");
        assertEquals("hello world", r.sub(0, 11).toString());
    }

    @Test
    void substring_partial_returnsCorrectSlice() {
        Rope r = Rope.fromString("hello world");
        assertEquals("world", r.sub(6, 11).toString());
        assertEquals("lo wo", r.sub(3, 8).toString());
    }

    @Test
    void substring_emptyRange_returnsEmpty() {
        Rope r = Rope.fromString("hello");
        Rope sub = r.sub(3, 3);
        assertEquals(0, sub.length());
    }

    @Test
    void subSequence_matchesSubstring() {
        Rope r = Rope.fromString("abcdefghij");
        CharSequence cs = r.subSequence(2, 7);
        assertEquals("cdefg", cs.toString());
    }

    // ── replace ───────────────────────────────────────────────────

    @Test
    void replace_insertAtStart() {
        Rope r = Rope.fromString("world");
        Rope result = r.replace(0, 0, "hello ");
        assertEquals("hello world", result.toString());
    }

    @Test
    void replace_insertAtEnd() {
        Rope r = Rope.fromString("hello");
        Rope result = r.replace(5, 5, " world");
        assertEquals("hello world", result.toString());
    }

    @Test
    void replace_deleteRange() {
        Rope r = Rope.fromString("hello cruel world");
        Rope result = r.replace(6, 12, "");
        assertEquals("hello world", result.toString());
    }

    @Test
    void replace_replaceRange() {
        Rope r = Rope.fromString("hello world");
        Rope result = r.replace(6, 11, "there");
        assertEquals("hello there", result.toString());
    }

    @Test
    void replace_singleCharInsert() {
        Rope r = Rope.fromString("hllo");
        Rope result = r.replace(1, 1, "e");
        assertEquals("hello", result.toString());
    }

    @Test
    void replace_noOp_returnsSameContent() {
        Rope r = Rope.fromString("hello");
        Rope result = r.replace(2, 2, "");
        assertEquals("hello", result.toString());
    }

    // ── concat ────────────────────────────────────────────────────

    @Test
    void concat_twoRopes() {
        Rope a = Rope.fromString("hello ");
        Rope b = Rope.fromString("world");
        Rope c = Rope.concat(a, b);
        assertEquals("hello world", c.toString());
    }

    @Test
    void concat_withEmpty() {
        Rope a = Rope.fromString("hello");
        assertSame(a, Rope.concat(a, Rope.EMPTY));
        assertSame(a, Rope.concat(Rope.EMPTY, a));
    }

    @Test
    void concat_smallLeavesMerge() {
        Rope a = Rope.fromString("ab");
        Rope b = Rope.fromString("cd");
        Rope c = Rope.concat(a, b);
        assertEquals("abcd", c.toString());
        assertInstanceOf(Rope.Leaf.class, c);
    }

    // ── Balance ───────────────────────────────────────────────────

    @Test
    void balance_maintainsInvariant() {
        Rope r = Rope.EMPTY;
        for (int i = 0; i < 1000; i++) {
            r = Rope.concat(r, Rope.fromString(String.valueOf(i)));
        }
        String expected = r.toString();
        assertTrue(expected.startsWith("01234567891011"));
        assertEquals(2890, r.length());
    }

    @Test
    void balance_invariant_afterRebalance() {
        // Build deeply unbalanced rope
        Rope r = Rope.fromString("x");
        for (int i = 0; i < 200; i++) {
            r = Rope.concat(r, Rope.fromString("y"));
        }
        // Rebalance
        Rope balanced = r.rebalance();
        assertEquals(r.toString(), balanced.toString());
        // Balanced tree should have reasonable depth
        assertTrue(balanced.depth <= 20, "Depth should be reasonable after rebalance, got " + balanced.depth);
    }

    @Test
    void rebalance_afterManyConcats() {
        Rope r = Rope.fromString("start");
        for (int i = 0; i < 100; i++) {
            r = r.replace(r.length(), r.length(), "x");
        }
        assertEquals("start" + "x".repeat(100), r.toString());
        assertEquals(105, r.length());
    }

    // ── Large text ────────────────────────────────────────────────

    @Test
    void largeText_operations() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("Line ").append(i).append(" of the document.\n");
        }
        String text = sb.toString();
        Rope r = Rope.fromString(text);

        assertEquals(text.length(), r.length());
        assertEquals(text.charAt(0), r.charAt(0));
        assertEquals(text.charAt(text.length() - 1), r.charAt(text.length() - 1));

        int mid = text.length() / 2;
        Rope edited = r.replace(mid, mid + 5, "XXXXX");
        assertEquals(text.length(), edited.length());
        assertEquals('X', edited.charAt(mid));
    }

    // ── CharSequence contract ─────────────────────────────────────

    @Test
    void implementsCharSequence() {
        Rope r = Rope.fromString("hello");
        CharSequence cs = r;
        assertEquals(5, cs.length());
        assertEquals('h', cs.charAt(0));
        assertEquals("llo", cs.subSequence(2, 5).toString());
    }

    // ── Immutability ──────────────────────────────────────────────

    @Test
    void replace_doesNotMutateOriginal() {
        Rope r = Rope.fromString("hello");
        Rope modified = r.replace(0, 0, "X");
        assertEquals("hello", r.toString());
        assertEquals("Xhello", modified.toString());
    }

    @Test
    void concat_doesNotMutateOriginals() {
        Rope a = Rope.fromString("hello");
        Rope b = Rope.fromString("world");
        Rope c = Rope.concat(a, b);
        assertEquals("hello", a.toString());
        assertEquals("world", b.toString());
        assertEquals("helloworld", c.toString());
    }
}
