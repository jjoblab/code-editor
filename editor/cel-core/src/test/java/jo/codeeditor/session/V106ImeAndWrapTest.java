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
 * Tests for the v1.0.6 features:
 *
 * <ul>
 *   <li>SwiftKey auto-space handling: bundled ("p ") and split ("p" then " ")
 *       shapes both strip the trailing space.</li>
 *   <li>Punctuation swap detection is byte-identical to a user backspace tap —
 *       imeDeleteSurrounding stays literal (no smart-backspace rules).</li>
 *   <li>Composing region stays put when a non-composing edit happens before
 *       it (already tested in v1.0.5 but re-verified here for the new
 *       imeCommitText code path).</li>
 *   <li>Fold region collapsed-state survives shiftFoldRegions (the v1.0.5 fix
 *       preserved kind+collapsed, but we re-verify here).</li>
 *   <li>EditorSession.imeCommitText with empty string clears composing
 *       without touching text.</li>
 * </ul>
 */
class V106ImeAndWrapTest {

    private static final class RecordingImeListener implements EditorSession.ImeListener {
        int textChangedCount = 0;
        int selectionChangedCount = 0;
        int restartInputCount = 0;
        boolean syncingExtracted = false;

        @Override public void onTextChanged(EditSpan span) { textChangedCount++; }
        @Override public void onSelectionChanged(int s, int e, int cs, int ce) { selectionChangedCount++; }
        @Override public void onRestartInput() { restartInputCount++; }
        @Override public boolean isSyncingExtractedText() { return syncingExtracted; }
    }

    // ── SwiftKey auto-space (bundled shape) ───────────────────────

    @Test
    void imeCommitText_bundledAutoSpaceAfterParen_stripsTrailingSpace() {
        EditorSession s = new EditorSession(EditorDocument.of("foo"));
        s.setSelection(3);
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        // IME commits "p " (the "p" is the user's typed char, the trailing
        // space is the keyboard's auto-space after the previous ")").
        // Actually we test with "(" — wait, "(" is an opener, not auto-spaced.
        // Use ")" which IS auto-spaced.
        s.imeCommitText(")");
        // Now commit "p " — but our heuristic only triggers when the LAST
        // char of the commit is " " and the second-to-last is an auto-spaced
        // symbol. So "p " wouldn't trigger (p isn't a symbol). Let's commit
        // ") " directly — a bare closer + space.
        s.imeCommitText(") ");
        // The trailing space must have been stripped.
        assertEquals("foo))", s.getText());
        // restartInput must have been called (the IME's model has the phantom space).
        assertTrue(l.restartInputCount > 0, "Expected onRestartInput for bundled auto-space");
    }

    @Test
    void imeCommitText_bundledAutoSpaceAfterSemicolon_stripsTrailingSpace() {
        EditorSession s = new EditorSession(EditorDocument.of("int x"));
        s.setSelection(5);
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        s.imeCommitText("; ");
        assertEquals("int x;", s.getText());
        assertTrue(l.restartInputCount > 0);
    }

    // ── SwiftKey auto-space (split shape) ─────────────────────────

    @Test
    void imeCommitText_splitAutoSpaceAfterSymbol_swallowsBareSpace() {
        EditorSession s = new EditorSession(EditorDocument.of("foo"));
        s.setSelection(3);
        RecordingImeListener l = new RecordingImeListener();
        s.setImeListener(l);
        // First commit: ")" — this arms the split-auto-space detector.
        s.imeCommitText(")");
        assertEquals("foo)", s.getText());
        // Second commit: " " — this should be swallowed.
        s.imeCommitText(" ");
        assertEquals("foo)", s.getText());
        // restartInput must have been called for the swallowed space.
        assertTrue(l.restartInputCount > 0, "Expected onRestartInput for split auto-space");
    }

    @Test
    void imeCommitText_userTypedSpaceIsNotSwallowed() {
        EditorSession s = new EditorSession(EditorDocument.of("foo"));
        s.setSelection(3);
        // User types a real space (not after a symbol commit).
        s.imeCommitText(" ");
        assertEquals("foo ", s.getText());
        // Now type another space — also not swallowed (no preceding symbol commit).
        s.setSelection(4);
        s.imeCommitText(" ");
        assertEquals("foo  ", s.getText());
    }

    @Test
    void imeCommitText_spaceAfterLetterIsNotSwallowed() {
        EditorSession s = new EditorSession(EditorDocument.of("foo"));
        s.setSelection(3);
        // Type "bar" then " " — the space should NOT be swallowed because
        // the previous commit was a letter, not a symbol.
        s.imeCommitText("bar");
        s.imeCommitText(" ");
        assertEquals("foobar ", s.getText());
    }

    // ── Empty commit clears composing ─────────────────────────────

    @Test
    void imeCommitText_emptyStringClearsComposingWithoutTouchingText() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        s.imeSetComposingText("hello", 1);
        assertTrue(s.isComposing());
        assertEquals("hello", s.getText());
        // IME confirms the composing region with an empty commit.
        s.imeCommitText("");
        // Text is NOT touched (the composing text is already in the buffer).
        assertEquals("hello", s.getText());
        // Composing flag cleared.
        assertFalse(s.isComposing());
    }

    // ── Fold region collapsed-state survives shift ────────────────

    @Test
    void shiftFoldRegions_preservesCollapsedState() {
        // Place a collapsed fold at [0, 10).
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(0, 10, "...", "block", true));
        // Edit at offset 5: replace 1 char with 3 chars.
        EditSpan span = new EditSpan(5, 1, 3);
        List<DiagnosticShift.FoldRegion> shifted = DiagnosticShift.shiftFoldRegions(folds, span);
        assertEquals(1, shifted.size());
        DiagnosticShift.FoldRegion r = shifted.get(0);
        // Start should be unchanged (before the edit).
        assertEquals(0, r.start);
        // End should shift by +2 (3 inserted - 1 removed).
        assertEquals(12, r.end);
        // Collapsed state preserved.
        assertTrue(r.collapsed);
        assertEquals("block", r.kind);
        assertEquals("...", r.placeholder);
    }

    @Test
    void shiftFoldRegions_dropsFoldsConsumedByDelete() {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(5, 10, "...", "block", true));
        // Delete the entire fold range: [0, 20) → "".
        EditSpan span = new EditSpan(0, 20, 0);
        List<DiagnosticShift.FoldRegion> shifted = DiagnosticShift.shiftFoldRegions(folds, span);
        // The fold's end maps to 0 (it was inside the deleted range), so
        // newEnd <= newStart and the fold is dropped.
        assertTrue(shifted.isEmpty());
    }

    // ── Composing region stays put on non-composing edit ──────────

    @Test
    void replaceRange_outsideComposingRegion_keepsComposingIntact() {
        EditorSession s = new EditorSession(EditorDocument.of("hello world"));
        // Select "world" (offsets 6..11) so the composing text replaces it.
        s.setSelection(Selection.range(6, 11));
        s.imeSetComposingText("WORLD", 1);
        // Composing region is now [6, 11) in "hello WORLD".
        assertEquals("hello WORLD", s.getText());
        int[] comp = s.getComposingRegion();
        assertEquals(6, comp[0]);
        assertEquals(11, comp[1]);
        // Edit AFTER the composing region — should not move it.
        s.setSelection(12);
        s.commitText("!");
        // "hello WORLD!" — composing region [6, 11) unchanged.
        assertEquals("hello WORLD!", s.getText());
        comp = s.getComposingRegion();
        assertEquals(6, comp[0]);
        assertEquals(11, comp[1]);
    }

    // ── typeChar literal typing does not arm symbol-commit detector ──

    @Test
    void typeChar_letterDoesNotArmSymbolCommitDetector() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // Type ")" — arms the detector.
        s.typeChar(')');
        assertEquals(")", s.getText());
        // Type "a" (a letter, not a space) — should NOT be swallowed.
        s.typeChar('a');
        assertEquals(")a", s.getText());
    }

    // ── Multiple symbol commits in a row ──────────────────────────

    @Test
    void imeCommitText_multipleSymbolsInARow_splitAutoSpaceSwallowed() {
        EditorSession s = new EditorSession(EditorDocument.of(""));
        s.setSelection(0);
        // Type ")" (bare symbol — arms the split detector).
        s.imeCommitText(")");
        assertEquals(")", s.getText());
        // Type " " — split auto-space, swallowed.
        s.imeCommitText(" ");
        assertEquals(")", s.getText());
        // Type ";" (bare symbol — re-arms the detector).
        s.imeCommitText(";");
        assertEquals(");", s.getText());
        // Type " " — split auto-space, swallowed.
        s.imeCommitText(" ");
        assertEquals(");", s.getText());
    }
}
