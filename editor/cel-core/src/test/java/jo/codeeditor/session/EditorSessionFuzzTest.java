package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fuzz tests for {@link EditorSession} — random edit sequences that verify
 * the session's invariants (line count, styled-lines count, selection
 * bounds, undo/redo consistency) stay correct. Detects rare state
 * corruption that fixed-input unit tests miss.
 *
 * <p>v1.0.8 — stability focus.
 */
class EditorSessionFuzzTest {

    private static final int SEED = 1234;
    private static final int MAX_OPS = 300;

    @RepeatedTest(10)
    void fuzz_commitTextKeepsInvariants() {
        Random rng = new Random(SEED + System.nanoTime());
        EditorSession s = new EditorSession(EditorDocument.of(""));
        for (int op = 0; op < MAX_OPS; op++) {
            int choice = rng.nextInt(8);
            switch (choice) {
                case 0: case 1:
                    // Insert a random string at the caret.
                    s.commitText(randomText(rng, 15));
                    break;
                case 2:
                    // Backspace.
                    if (s.getSelection().start > 0) s.backspace();
                    break;
                case 3:
                    // Move caret to a random offset.
                    s.setSelection(rng.nextInt(s.getText().length() + 1));
                    break;
                case 4:
                    // Select a random range.
                    int a = rng.nextInt(s.getText().length() + 1);
                    int b = rng.nextInt(s.getText().length() + 1);
                    s.setSelection(Selection.range(Math.min(a, b), Math.max(a, b)));
                    break;
                case 5:
                    // Type a single char.
                    s.typeChar((char) ('a' + rng.nextInt(26)));
                    break;
                case 6:
                    // Undo (if possible).
                    s.undo();
                    break;
                case 7:
                    // Redo (if possible).
                    s.redo();
                    break;
            }
            verifyInvariants(s, "after op " + op);
        }
    }

    @RepeatedTest(5)
    void fuzz_undoRedoRoundTripPreservesContent() {
        Random rng = new Random(SEED + System.nanoTime());
        EditorSession s = new EditorSession(EditorDocument.of(""));
        // Apply 50 random edits, snapshot the text + caret.
        for (int i = 0; i < 50; i++) {
            s.commitText(randomText(rng, 10));
        }
        String textAfterEdits = s.getText();
        int caretAfterEdits = s.getSelection().start;
        // Undo all 50.
        for (int i = 0; i < 50; i++) {
            assertTrue(s.undo(), "undo " + i + " should succeed");
        }
        assertEquals("", s.getText(), "after undoing all edits, doc must be empty");
        // Redo all 50.
        for (int i = 0; i < 50; i++) {
            assertTrue(s.redo(), "redo " + i + " should succeed");
        }
        assertEquals(textAfterEdits, s.getText(),
            "redo must restore the exact text after a full undo");
        assertEquals(caretAfterEdits, s.getSelection().start,
            "redo must restore the exact caret position after a full undo");
    }

    @Test
    void fuzz_emptyDocAllOps() {
        // Edge case: every op on an empty document must be a no-op or safe.
        EditorSession s = new EditorSession(EditorDocument.of(""));
        // Backspace on empty doc — no crash.
        s.backspace();
        assertEquals(0, s.getText().length());
        // Undo on empty doc — no crash, returns false.
        assertFalse(s.undo());
        // Redo on empty doc — no crash, returns false.
        assertFalse(s.redo());
        // Select word at offset 0 on empty doc.
        s.selectWordAt(0);
        assertTrue(s.getSelection().isCursor());
        // Move caret — no crash.
        s.moveHorizontal(-1, false);
        s.moveHorizontal(1, false);
        s.moveVertical(-1, false);
        s.moveVertical(1, false);
        verifyInvariants(s, "after empty-doc ops");
    }

    @Test
    void fuzz_singleCharDoc() {
        EditorSession s = new EditorSession(EditorDocument.of("X"));
        assertEquals(1, s.getText().length());
        assertEquals(1, s.getDocument().lineCount());
        // Type at the end.
        s.setSelection(1);
        s.typeChar('Y');
        assertEquals("XY", s.getText());
        // Backspace.
        s.backspace();
        assertEquals("X", s.getText());
        verifyInvariants(s, "after single-char ops");
    }

    @Test
    void fuzz_surrogatePairsInDocument() {
        // Emoji and surrogate pairs must not corrupt the line index.
        EditorSession s = new EditorSession(EditorDocument.of("Hello 🌍 World"));
        s.setSelection(6);
        s.typeChar('!');
        assertEquals("Hello !🌍 World", s.getText());
        verifyInvariants(s, "after surrogate insert");
    }

    @Test
    void fuzz_manyNewlinesLineCount() {
        // Stress: insert 100 newlines and verify line count.
        EditorSession s = new EditorSession(EditorDocument.of(""));
        for (int i = 0; i < 100; i++) {
            s.commitText("line\n");
        }
        // 100 "line\n" = 100 lines + 1 empty line at the end = 101 lines.
        // Actually "line\n" * 100 = "line\nline\n...line\n" — the last \n
        // creates an empty line, so lineCount = 101.
        assertEquals(101, s.getDocument().lineCount(),
            "100 newlines must produce 101 lines");
        // Styled lines must match line count.
        assertEquals(s.getDocument().lineCount(), s.getStyledLines().size(),
            "styledLines.size must match doc.lineCount");
        verifyInvariants(s, "after 100 newlines");
    }

    @Test
    void fuzz_selectionAlwaysInBounds() {
        // After any edit, the selection must be within [0, doc.length()].
        Random rng = new Random(42);
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        for (int i = 0; i < 100; i++) {
            s.setSelection(rng.nextInt(20)); // may be past the end
            s.commitText(randomText(rng, 5));
            Selection sel = s.getSelection();
            assertTrue(sel.start >= 0 && sel.start <= s.getDocument().length(),
                "selection.start out of bounds: " + sel.start);
            assertTrue(sel.end >= 0 && sel.end <= s.getDocument().length(),
                "selection.end out of bounds: " + sel.end);
        }
    }

    @Test
    void fuzz_composingRegionClearedOnFinish() {
        // After imeFinishComposing, isComposing() must be false.
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(2);
        s.imeSetComposingText("XYZ", 1);
        assertTrue(s.isComposing());
        s.imeFinishComposing();
        assertFalse(s.isComposing());
    }

    @Test
    void fuzz_batchEditGroupsUndo() {
        // Edits inside a batch must be grouped into a single undo step.
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.beginBatch();
        s.commitText("a");
        s.commitText("b");
        s.commitText("c");
        s.endBatch();
        assertEquals("abc", s.getText());
        // One undo must revert all three.
        assertTrue(s.undo());
        assertEquals("", s.getText());
    }

    /** Verifies the session's internal invariants. */
    private static void verifyInvariants(EditorSession s, String context) {
        // ★ v2.55 — Undo/redo déclenchent maintenant restyleAllAsync au lieu
        // de restyleAll sync. Pendant le gap async, styledLines peut ne pas
        // matcher doc.lineCount() (comportement documenté dans restyleAllAsync).
        // On attend le pending restyle pour vérifier la cohérence finale.
        if (s.isAsyncRestylePending()) {
            try { s.awaitPendingRestyle(); } catch (InterruptedException ignored) {}
        }
        EditorDocument doc = s.getDocument();
        // Invariant 1: styledLines.size == doc.lineCount.
        assertEquals(doc.lineCount(), s.getStyledLines().size(),
            "styledLines.size mismatch " + context);
        // Invariant 2: selection in bounds.
        Selection sel = s.getSelection();
        assertTrue(sel.start >= 0 && sel.start <= doc.length(),
            "selection.start out of bounds " + context + ": " + sel.start);
        assertTrue(sel.end >= 0 && sel.end <= doc.length(),
            "selection.end out of bounds " + context + ": " + sel.end);
        // Invariant 3: composing region in bounds (if active).
        if (s.isComposing()) {
            int[] comp = s.getComposingRegion();
            assertNotNull(comp);
            assertTrue(comp[0] >= 0 && comp[0] <= doc.length(),
                "composingStart out of bounds " + context);
            assertTrue(comp[1] >= 0 && comp[1] <= doc.length(),
                "composingEnd out of bounds " + context);
        }
        // Invariant 4: text consistency — getText() matches doc.getText().
        assertEquals(doc.getText(), s.getText(),
            "session.getText() != doc.getText() " + context);
        // Invariant 5: lineStarts are monotonic and within bounds.
        for (int i = 0; i < doc.lineCount(); i++) {
            int start = doc.lineStart(i);
            int end = doc.lineEnd(i);
            assertTrue(start >= 0 && start <= doc.length(),
                "lineStart[" + i + "] out of bounds " + context);
            assertTrue(end >= start && end <= doc.length(),
                "lineEnd[" + i + "] out of bounds " + context);
        }
    }

    private static String randomText(Random rng, int maxLen) {
        int len = rng.nextInt(maxLen) + 1;
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int choice = rng.nextInt(20);
            if (choice < 15) sb.append((char) ('a' + rng.nextInt(26)));
            else if (choice < 18) sb.append((char) ('0' + rng.nextInt(10)));
            else if (choice < 19) sb.append('\n');
            else sb.append("🌍");
        }
        return sb.toString();
    }
}
