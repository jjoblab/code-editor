package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.shift.EditSpan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the v1.0.5 features:
 *
 * <ul>
 *   <li>Composing region replace-not-append (typing "hello" then backspace
 *       must NOT produce "hellohell").</li>
 *   <li>{@code imeSetComposingText} honors the {@code newCursorPosition}
 *       contract (positive = relative to end, negative = relative to start).</li>
 *   <li>{@code imeSetSelection} / {@code imeSetComposingRegion} clamp to
 *       the document bounds.</li>
 *   <li>{@code imeDeleteSurrounding} deletes a literal char range (no smart
 *       backspace rules — SwiftKey's punctuation swap is byte-identical to
 *       a user backspace tap).</li>
 *   <li>{@code imeReplaceText} (API 34) routes through replaceRangeWithCaret.</li>
 *   <li>{@code imeTextBeforeCursor} / {@code imeTextAfterCursor} return the
 *       right slice and are windowed to MAX_IPC_TEXT.</li>
 *   <li>{@code toggleFoldAtLine} / {@code isLineFolded} / {@code expandFoldAt}
 *       behave as expected.</li>
 *   <li>{@code EditorSession.ImeListener} receives {@code onTextChanged},
 *       {@code onSelectionChanged}, {@code onRestartInput} on the right
 *       events.</li>
 * </ul>
 */
class V105ImeAndFoldTest {

    // ── ImeListener test double ───────────────────────────────────

    private static final class RecordingImeListener implements EditorSession.ImeListener {
        int textChangedCount = 0;
        int selectionChangedCount = 0;
        int restartInputCount = 0;
        EditSpan lastSpan = null;
        int lastSelStart = -1, lastSelEnd = -1, lastCompStart = -2, lastCompEnd = -2;
        boolean syncingExtracted = false;

        @Override public void onTextChanged(EditSpan span) {
            textChangedCount++;
            lastSpan = span;
        }
        @Override public void onSelectionChanged(int selStart, int selEnd,
                                                  int composingStart, int composingEnd) {
            selectionChangedCount++;
            lastSelStart = selStart;
            lastSelEnd = selEnd;
            lastCompStart = composingStart;
            lastCompEnd = composingEnd;
        }
        @Override public void onRestartInput() {
            restartInputCount++;
        }
        @Override public boolean isSyncingExtractedText() {
            return syncingExtracted;
        }
    }

    // ── Composing region: replace-not-append ──────────────────────

    @Test
    void imeSetComposingText_replacesExistingComposingRegion() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // First call: insert "hell" as composing at caret.
        s.imeSetComposingText("hell", 1);
        assertEquals("hell", s.getText());
        assertEquals(4, s.getSelection().start);
        // Second call: replace the composing region with "hello".
        s.imeSetComposingText("hello", 1);
        assertEquals("hello", s.getText());
        assertEquals(5, s.getSelection().start);
        // Third call: replace "hello" with "help" — the "lo" must be GONE.
        s.imeSetComposingText("help", 1);
        assertEquals("help", s.getText());
        assertEquals(4, s.getSelection().start);
    }

    @Test
    void imeSetComposingText_backspaceDoesNotProduce_hellohell() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // Type "hello" via composing.
        s.imeSetComposingText("hello", 1);
        assertEquals("hello", s.getText());
        // IME sends setComposingText("") or finishComposingText + deleteSurrounding.
        // The common case: setComposingText("hell", 1) → must replace "hello" with "hell".
        s.imeSetComposingText("hell", 1);
        assertEquals("hell", s.getText());
        // Backspace again → "hel"
        s.imeSetComposingText("hel", 1);
        assertEquals("hel", s.getText());
        // The historical bug: composing was APPENDED, producing "hellohell".
        assertNotEquals("hellohell", s.getText());
        assertNotEquals("hellohellhell", s.getText());
    }

    @Test
    void imeSetComposingText_firstCallOnSelectionReplacesSelection() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        // Select "hello".
        s.setSelection(Selection.range(0, 5));
        // IME starts composing — the composing word must REPLACE the selection.
        s.imeSetComposingText("HELLO", 1);
        assertEquals("HELLO world", s.getText());
        assertEquals(5, s.getSelection().start);
    }

    // ── newCursorPosition contract ────────────────────────────────

    @Test
    void imeSetComposingText_newCursorPositionPositive_isRelativeToEnd() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // newCaretPos = 1 → caret immediately after the inserted text.
        s.imeSetComposingText("abc", 1);
        assertEquals(3, s.getSelection().start);
        // newCaretPos = 2 → caret one char past the composing end. Clamped
        // to doc.length() when the composition is the entire document.
        s.imeSetComposingText("abcde", 2);
        assertEquals(5, s.getSelection().start); // doc.length() = 5, clamped
    }

    @Test
    void imeSetComposingText_newCursorPositionNegative_isRelativeToStart() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // newCaretPos = 0 → caret at the start of the composing region.
        s.imeSetComposingText("abc", 0);
        assertEquals(0, s.getSelection().start);
    }

    @Test
    void imeSetComposingText_newCursorPositionNegativeClampedToZero() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // A bugged IME sends a large negative newCaretPos — must not crash.
        s.imeSetComposingText("abc", -1000);
        assertEquals(0, s.getSelection().start);
    }

    // ── imeSetSelection / imeSetComposingRegion clamping ─────────

    @Test
    void imeSetSelection_clampsToDocumentBounds() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.imeSetSelection(-10, 1000);
        assertEquals(0, s.getSelection().start);
        assertEquals(5, s.getSelection().end);
    }

    @Test
    void imeSetComposingRegion_emptyRangeClearsComposing() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.imeSetComposingRegion(1, 4);
        assertTrue(s.isComposing());
        // Empty region clears.
        s.imeSetComposingRegion(2, 2);
        assertFalse(s.isComposing());
    }

    @Test
    void imeFinishComposing_clearsStateWithoutTouchingText() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        s.imeSetComposingText("hello", 1);
        assertTrue(s.isComposing());
        assertEquals("hello", s.getText());
        // Finish composing — text stays, composing flag clears.
        s.imeFinishComposing();
        assertEquals("hello", s.getText());
        assertFalse(s.isComposing());
    }

    // ── imeDeleteSurrounding ─────────────────────────────────────

    @Test
    void imeDeleteSurrounding_deletesLiteralRange() {
        EditorSession s = new EditorSession(EditorDocument.of("abcdef"));
        s.setSelection(3); // between 'c' and 'd'
        s.imeDeleteSurrounding(2, 1); // delete 2 before + 1 after → "abc" -1 + "ef" → wait
        // abc|def → delete 2 before (bc) and 1 after (d) → a|ef
        assertEquals("aef", s.getText());
    }

    @Test
    void imeDeleteSurrounding_doesNotApplySmartBackspaceRules() {
        // The smart-backspace rule on `()` empty pair would delete both chars.
        // imeDeleteSurrounding(1, 0) on "()" must delete ONE char only.
        EditorSession s = new EditorSession(EditorDocument.of("()"));
        s.setSelection(1); // between '(' and ')'
        s.imeDeleteSurrounding(1, 0);
        assertEquals(")", s.getText());
    }

    // ── imeReplaceText (API 34) ───────────────────────────────────

    @Test
    void imeReplaceText_replacesRangeAndSetsCaret() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.imeReplaceText(0, 5, "HELLO", 1);
        assertEquals("HELLO world", s.getText());
        // Caret immediately after the inserted text (newCaretPos=1 → end+0).
        assertEquals(5, s.getSelection().start);
    }

    // ── imeTextBeforeCursor / imeTextAfterCursor ─────────────────

    @Test
    void imeTextBeforeCursor_returnsUpToNCharsBeforeCaret() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.setSelection(7); // at second 'o' in "world" (offset 7 → after "hello w")
        assertEquals("hello w", s.imeTextBeforeCursor(7));
        // Larger n returns the whole text up to the caret.
        assertEquals("hello w", s.imeTextBeforeCursor(100));
    }

    @Test
    void imeTextAfterCursor_returnsUpToNCharsAfterCaret() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.setSelection(6); // at 'w'
        assertEquals("world", s.imeTextAfterCursor(5));
        assertEquals("world", s.imeTextAfterCursor(100));
    }

    // ── ImeListener wiring ───────────────────────────────────────

    @Test
    void setSelection_notifiesImeListener() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        s.setSelection(3);
        assertEquals(1, l.selectionChangedCount);
        assertEquals(3, l.lastSelStart);
        assertEquals(3, l.lastSelEnd);
        assertEquals(-1, l.lastCompStart); // composingStart is -1 when not composing
    }

    @Test
    void replaceRange_notifiesImeListenerWithSpanAndSelection() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        s.replaceRange(0, 5, "HELLO");
        assertEquals(1, l.textChangedCount);
        assertNotNull(l.lastSpan);
        assertEquals(5, l.lastSpan.removed);
        assertEquals(5, l.lastSpan.added);
        assertEquals(5, l.lastSelStart);
        assertEquals(5, l.lastSelEnd);
    }

    @Test
    void typeChar_smartEditDivergenceTriggersRestartInput() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        // Type '(': smart-insert should auto-close to '()' with caret between.
        s.typeChar('(');
        assertEquals("()", s.getText());
        assertEquals(1, s.getSelection().start);
        // The smart-edit diverged from "literal type this char" → restartInput.
        assertTrue(l.restartInputCount > 0, "Expected onRestartInput to be called for divergent smart-edit");
    }

    @Test
    void typeChar_literalTypingDoesNotTriggerRestartInput() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        s.setSelection(5); // at end of "hello"
        // Type 'a' — should be a literal insert, no smart-edit divergence.
        s.typeChar('a');
        assertEquals("helloa", s.getText());
        assertEquals(0, l.restartInputCount);
    }

    // ── Fold management ──────────────────────────────────────────

    @Test
    void toggleFoldAtLine_togglesCollapsedState() {
        EditorSession s = new EditorSession(EditorDocument.of("public class A {\n    int x;\n    int y;\n}\n"));
        // Place a fold region starting at line 0 (offset 0).
        int classEnd = s.getText().indexOf('}');
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(0, classEnd + 1, "{...}", "block", false));
        s.setFoldRegions(folds);
        // Line 0 is NOT folded (region starts AT line 0 but isn't collapsed).
        assertFalse(s.isLineFolded(1));
        // Toggle the fold at line 0 → collapses.
        assertTrue(s.toggleFoldAtLine(0));
        assertTrue(s.isLineFolded(1));
        assertTrue(s.isLineFolded(2));
        assertFalse(s.isLineFolded(0)); // start line itself is visible (composite)
        // Toggle again → expands.
        assertTrue(s.toggleFoldAtLine(0));
        assertFalse(s.isLineFolded(1));
    }

    @Test
    void toggleFoldAtLine_unknownLineReturnsFalse() {
        EditorSession s = new EditorSession(EditorDocument.of("abc\ndef\n"));
        assertFalse(s.toggleFoldAtLine(0));
    }

    @Test
    void expandFoldAt_expandsCollapsedFoldContainingOffset() {
        EditorSession s = new EditorSession(EditorDocument.of("public class A {\n    int x;\n    int y;\n}\n"));
        int classEnd = s.getText().indexOf('}');
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(0, classEnd + 1, "{...}", "block", true));
        s.setFoldRegions(folds);
        assertTrue(s.isLineFolded(1));
        // Caret lands at offset 25 (inside the fold).
        s.expandFoldAt(25);
        assertFalse(s.isLineFolded(1));
    }

    @Test
    void getCollapsedFolds_returnsOnlyCollapsed() {
        EditorSession s = new EditorSession(EditorDocument.of("aaaa\n"));
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(0, 4, "...", "block", true));
        folds.add(new DiagnosticShift.FoldRegion(0, 4, "...", "block", false));
        s.setFoldRegions(folds);
        assertEquals(1, s.getCollapsedFolds().size());
    }

    // ── Composing region shifts on edit ──────────────────────────

    @Test
    void replaceRange_shiftsComposingRegion() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        s.setSelection(2);
        s.imeSetComposingText("XYZ", 1);
        // Buffer is now "heXYZllo world", composing region [2,5].
        assertEquals("heXYZllo world", s.getText());
        int[] comp = s.getComposingRegion();
        assertNotNull(comp);
        assertEquals(2, comp[0]);
        assertEquals(5, comp[1]);
        // Insert a single char BEFORE the composing region — the region must shift.
        s.setSelection(0);
        s.commitText("A"); // smart-insert just inserts the char.
        // Buffer is now "AheXYZllo world", composing region must be [3,6].
        comp = s.getComposingRegion();
        assertNotNull(comp);
        assertEquals(3, comp[0]);
        assertEquals(6, comp[1]);
    }
}
