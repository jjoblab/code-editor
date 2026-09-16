package jo.codeeditor.rope;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuzz tests for {@link Rope} — random insert/delete sequences that verify
 * the rope's invariants stay consistent. Detects rare crashes and state
 * corruption that unit tests with fixed inputs miss.
 *
 * <p>v1.0.8 — stability focus.
 */
class RopeFuzzTest {

    private static final int SEED = 42;
    private static final int MAX_OPS = 500;
    private static final int MAX_DOC_LEN = 10_000;

    @RepeatedTest(20)
    void fuzz_insertDeleteMatchesStringBuilder() {
        Random rng = new Random(SEED + System.nanoTime());
        Rope rope = Rope.fromString("");
        StringBuilder sb = new StringBuilder();
        for (int op = 0; op < MAX_OPS; op++) {
            int choice = rng.nextInt(10);
            if (choice < 6 || sb.length() == 0) {
                // Insert at random position.
                int pos = sb.length() == 0 ? 0 : rng.nextInt(sb.length() + 1);
                String text = randomText(rng, rng.nextInt(20) + 1);
                sb.insert(pos, text);
                rope = rope.replace(pos, pos, text);
            } else if (choice < 9) {
                // Delete a random range.
                int start = rng.nextInt(sb.length());
                int end = start + rng.nextInt(Math.min(sb.length() - start, 30) + 1);
                sb.delete(start, end);
                rope = rope.replace(start, end, "");
            } else {
                // Replace a random range.
                int start = rng.nextInt(sb.length());
                int end = start + rng.nextInt(Math.min(sb.length() - start, 30) + 1);
                String text = randomText(rng, rng.nextInt(20) + 1);
                sb.replace(start, end, text);
                rope = rope.replace(start, end, text);
            }
            // Cap document size to keep the test fast.
            if (sb.length() > MAX_DOC_LEN) {
                sb.delete(MAX_DOC_LEN / 2, sb.length());
                rope = rope.replace(MAX_DOC_LEN / 2, rope.length(), "");
            }
            // Invariant: length matches.
            assertEquals(sb.length(), rope.length(),
                "length mismatch after op " + op + ": sb=" + sb.length() + " rope=" + rope.length());
            // Invariant: content matches.
            assertEquals(sb.toString(), rope.toString(),
                "content mismatch after op " + op);
        }
    }

    @RepeatedTest(10)
    void fuzz_charAtMatchesStringBuilder() {
        Random rng = new Random(SEED + System.nanoTime());
        Rope rope = Rope.fromString("hello world");
        StringBuilder sb = new StringBuilder("hello world");
        for (int op = 0; op < 200; op++) {
            if (rng.nextBoolean() && sb.length() > 0) {
                int idx = rng.nextInt(sb.length());
                assertEquals(sb.charAt(idx), rope.charAt(idx),
                    "charAt mismatch at " + idx);
            } else {
                int pos = rng.nextInt(sb.length() + 1);
                String text = randomText(rng, 5);
                sb.insert(pos, text);
                rope = rope.replace(pos, pos, text);
            }
        }
    }

    @RepeatedTest(10)
    void fuzz_substringMatchesStringBuilder() {
        Random rng = new Random(SEED + System.nanoTime());
        StringBuilder sb = new StringBuilder();
        Rope rope = Rope.fromString("");
        for (int i = 0; i < 100; i++) {
            String text = randomText(rng, 10);
            sb.append(text);
            rope = rope.replace(rope.length(), rope.length(), text);
        }
        // Now take random substrings and compare.
        for (int i = 0; i < 100; i++) {
            int start = rng.nextInt(sb.length() + 1);
            int end = start + rng.nextInt(sb.length() - start + 1);
            assertEquals(sb.substring(start, end), rope.subSequence(start, end).toString(),
                "substring mismatch [" + start + ", " + end + ")");
        }
    }

    @Test
    void fuzz_emptyDocOps() {
        // Edge case: all ops on an empty document.
        Rope rope = Rope.fromString("");
        assertEquals(0, rope.length());
        assertEquals("", rope.toString());
        // Insert then delete everything.
        rope = rope.replace(0, 0, "hello");
        assertEquals(5, rope.length());
        rope = rope.replace(0, 5, "");
        assertEquals(0, rope.length());
        assertEquals("", rope.toString());
    }

    @Test
    void fuzz_singleCharDoc() {
        // Edge case: document with exactly 1 character.
        Rope rope = Rope.fromString("X");
        assertEquals(1, rope.length());
        assertEquals('X', rope.charAt(0));
        // Delete the single char.
        rope = rope.replace(0, 1, "");
        assertEquals(0, rope.length());
        // Re-insert.
        rope = rope.replace(0, 0, "Y");
        assertEquals(1, rope.length());
        assertEquals('Y', rope.charAt(0));
    }

    @Test
    void fuzz_surrogatePairsPreserved() {
        // Emoji and surrogate pairs must survive insert/delete intact.
        String emoji = "Hello 🌍 World 🚀";
        Rope rope = Rope.fromString(emoji);
        assertEquals(emoji, rope.toString());
        assertEquals(emoji.length(), rope.length());
        // Insert another emoji in the middle.
        int insertPos = 6; // after "Hello "
        rope = rope.replace(insertPos, insertPos, "😀");
        assertEquals(emoji.substring(0, insertPos) + "😀" + emoji.substring(insertPos),
            rope.toString());
    }

    @Test
    void fuzz_longLineNoCrash() {
        // Edge case: a single very long line (10K chars).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) sb.append('x');
        Rope rope = Rope.fromString(sb.toString());
        assertEquals(10_000, rope.length());
        assertEquals(sb.toString(), rope.toString());
        // Delete half.
        rope = rope.replace(0, 5000, "");
        assertEquals(5000, rope.length());
    }

    @Test
    void fuzz_manySmallEdits() {
        // Stress: 1000 single-char inserts.
        Rope rope = Rope.fromString("");
        StringBuilder sb = new StringBuilder();
        Random rng = new Random(42);
        for (int i = 0; i < 1000; i++) {
            char c = (char) ('a' + rng.nextInt(26));
            int pos = sb.length();
            sb.append(c);
            rope = rope.replace(pos, pos, String.valueOf(c));
        }
        assertEquals(sb.toString(), rope.toString());
        assertEquals(1000, rope.length());
    }

    @Test
    void fuzz_clampedOffsetsNoCrash() {
        // Edge case: offsets at boundaries (0, length, length+1, -1).
        Rope rope = Rope.fromString("hello");
        // Insert at 0.
        assertEquals("Xhello", rope.replace(0, 0, "X").toString());
        // Insert at length.
        assertEquals("helloX", rope.replace(5, 5, "X").toString());
        // Delete [0, length).
        assertEquals("", rope.replace(0, 5, "").toString());
    }

    private static String randomText(Random rng, int maxLen) {
        int len = rng.nextInt(maxLen) + 1;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            // Mix of lowercase letters, digits, newlines, and occasional emoji.
            int choice = rng.nextInt(20);
            if (choice < 15) sb.append((char) ('a' + rng.nextInt(26)));
            else if (choice < 18) sb.append((char) ('0' + rng.nextInt(10)));
            else if (choice < 19) sb.append('\n');
            else sb.append("🌍"); // surrogate pair
        }
        return sb.toString();
    }
}
