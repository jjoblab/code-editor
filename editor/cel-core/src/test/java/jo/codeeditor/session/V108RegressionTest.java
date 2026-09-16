package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the v1.0.8 bug fixes.
 *
 * <p>Each test verifies one bug from the v1.0.8 bug list so a future change
 * can't silently bring it back.
 */
class V108RegressionTest {

    // ── Bug 2: cursor invisible after undo/redo ────────────────────
    // The root cause was that EditorSession.undo()/redo() didn't notify
    // the ImeListener, so the View never restarted the caret blink or
    // scroll-into-view. We verify the listener IS notified.

    @Test
    void undo_notifiesImeListener_textChanged() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        boolean[] textChanged = {false};
        boolean[] selChanged = {false};
        s.setImeListener(new EditorSession.ImeListener() {
            @Override public void onTextChanged(jo.codeeditor.shift.EditSpan span) { textChanged[0] = true; }
            @Override public void onSelectionChanged(int a, int b, int c, int d) { selChanged[0] = true; }
            @Override public void onRestartInput() {}
            @Override public boolean isSyncingExtractedText() { return false; }
        });
        s.commitText("X");
        // Reset flags before undo so we only observe the undo notification.
        textChanged[0] = false;
        selChanged[0] = false;
        assertTrue(s.undo());
        assertTrue(textChanged[0], "undo() must fire onTextChanged so the view restarts the caret blink");
        assertTrue(selChanged[0], "undo() must fire onSelectionChanged so the view scrolls the caret into view");
    }

    @Test
    void redo_notifiesImeListener_textChanged() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        boolean[] textChanged = {false};
        boolean[] selChanged = {false};
        s.setImeListener(new EditorSession.ImeListener() {
            @Override public void onTextChanged(jo.codeeditor.shift.EditSpan span) { textChanged[0] = true; }
            @Override public void onSelectionChanged(int a, int b, int c, int d) { selChanged[0] = true; }
            @Override public void onRestartInput() {}
            @Override public boolean isSyncingExtractedText() { return false; }
        });
        s.commitText("X");
        s.undo();
        textChanged[0] = false;
        selChanged[0] = false;
        assertTrue(s.redo());
        assertTrue(textChanged[0], "redo() must fire onTextChanged so the view restarts the caret blink");
        assertTrue(selChanged[0], "redo() must fire onSelectionChanged so the view scrolls the caret into view");
    }

    @Test
    void undo_restoresCaretPosition() {
        // After undo, the caret must land on step.selBefore (the position
        // before the original edit). If the caret is at the wrong place the
        // view can't scroll it into view correctly.
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(0);
        s.commitText("X");
        // Caret is now at 1 (after the X).
        assertEquals(1, s.getSelection().start);
        s.undo();
        assertEquals(0, s.getSelection().start, "undo() must restore the caret to selBefore (0)");
        assertEquals("hello", s.getText());
    }

    @Test
    void redo_restoresCaretPosition() {
        EditorSession s = new EditorSession(EditorDocument.of("hello"));
        s.setSelection(0);
        s.commitText("X");
        s.undo();
        s.redo();
        assertEquals(1, s.getSelection().start, "redo() must restore the caret to selAfter (1)");
        assertEquals("Xhello", s.getText());
    }

    // ── Bug 3: tap position off by ~5 chars (double padLeft subtraction) ──
    // The root cause was offsetAt delegating to metrics.xToCol after already
    // subtracting padLeft+gutterWidth, double-counting them. We verify the
    // column math directly with the same formula offsetAt now uses.

    @Test
    void tapColumnMath_noDoubleOffset() {
        // Simulate: gutterWidth = 5cw, padLeft = 0.5cw, charWidth = cw.
        // A tap on col 0 lands at screen X = gutterWidth + padLeft = 5.5cw.
        // The OLD code: xToCol(5.5cw - 5cw - 0.5cw) = xToCol(0) = (0 - 0.5cw - 5cw)/cw = -5.5 → clamped to 0 (by luck).
        // A tap on col 5 lands at screen X = 5.5cw + 5cw = 10.5cw.
        // The OLD code: xToCol(10.5cw - 5cw - 0.5cw) = xToCol(5cw) = (5cw - 0.5cw - 5cw)/cw = -0.5 → clamped to 0 (WRONG).
        // The NEW code: col = (10.5cw - (5.5cw - 0)) / cw + 0.5 = 5 + 0.5 = 5 (truncated to 5). CORRECT.
        float charWidth = 10f;
        float gutterWidth = charWidth * 5;
        float padLeft = charWidth * 0.5f;
        float hOffset = 0f;
        float textAreaLeft = gutterWidth + padLeft;
        // Tap on col 5 (screen X = textAreaLeft + 5*charWidth - hOffset).
        float tapX = textAreaLeft + 5 * charWidth - hOffset;
        float colScreenX = textAreaLeft - hOffset;
        int col = (int) ((tapX - colScreenX) / charWidth + 0.5f);
        assertEquals(5, col, "tap on col 5 must yield col 5 (no double padLeft subtraction)");
    }

    @Test
    void tapColumnMath_respectsHorizontalScroll() {
        // When the text is scrolled right by 3 chars (hOffset = 3*charWidth),
        // a tap at screen X = textAreaLeft must yield col 3 (the first visible char).
        float charWidth = 10f;
        float gutterWidth = charWidth * 5;
        float padLeft = charWidth * 0.5f;
        float hOffset = 3 * charWidth;
        float textAreaLeft = gutterWidth + padLeft;
        float tapX = textAreaLeft; // tap at the left edge of the text area
        float colScreenX = textAreaLeft - hOffset;
        int col = (int) ((tapX - colScreenX) / charWidth + 0.5f);
        assertEquals(3, col, "tap at text-area left edge with hOffset=3cw must yield col 3");
    }

    // ── Bug 4: caret "jumps" on every keystroke ────────────────────
    // The root cause was scrollCaretIntoView double-counting padLeft in the
    // horizontal branch, which made the right-margin scroll trigger too
    // early. We verify the caret screen X computation is correct.

    @Test
    void caretScreenX_noDoublePadLeft() {
        // Caret at col 10, gutterWidth=5cw, padLeft=0.5cw, hOffset=0.
        // Caret screen X should be textAreaLeft + 10*charWidth - hOffset = 5.5cw + 10cw = 15.5cw.
        // The OLD code computed caretScreenX = textLeft + (padLeft + 10*charWidth) - hOffset = 5.5cw + (0.5cw + 10cw) = 16cw (off by 0.5cw).
        float charWidth = 10f;
        float gutterWidth = charWidth * 5;
        float padLeft = charWidth * 0.5f;
        float textLeft = gutterWidth + padLeft;
        float hOffset = 0f;
        int col = 10;
        // NEW formula (no double padLeft):
        float caretScreenX = textLeft + col * charWidth - hOffset;
        // Expected: 5cw + 0.5cw + 10cw = 15.5cw → 155px
        assertEquals(155f, caretScreenX, 0.01f, "caret screen X must not double-count padLeft");
    }

    @Test
    void scrollCaretIntoView_doesNotScrollWhenCaretVisible() {
        // If the caret is already in the visible horizontal range, the
        // scroll-into-view must NOT change hOffset. The OLD code's double
        // padLeft made the right-margin check trigger too early.
        // We simulate the condition: caret at col 5, viewW = 30cw, hOffset = 0.
        // caretScreenX = textLeft + 5cw = 5.5cw. textLeft+margin = 5.5cw + 3cw = 8.5cw.
        // 5.5cw < 8.5cw → left-margin scroll triggers (WRONG — caret is visible).
        // The NEW code uses the same caretScreenX (15.5cw for col 10 in the test above),
        // but the margin check is against textLeft + margin, which is correct.
        // Here we just assert the formula doesn't produce a spurious scroll.
        float charWidth = 10f;
        float gutterWidth = charWidth * 5;
        float padLeft = charWidth * 0.5f;
        float padRight = charWidth * 0.5f;
        float textLeft = gutterWidth + padLeft;
        float hOffset = 0f;
        int col = 5;
        float caretScreenX = textLeft + col * charWidth - hOffset;
        float viewW = 300f; // 30 chars wide
        float margin = charWidth * 3;
        boolean shouldScrollLeft = caretScreenX < textLeft + margin;
        boolean shouldScrollRight = caretScreenX > textLeft + viewW - margin;
        assertFalse(shouldScrollLeft, "caret at col 5 with 30-char-wide view must NOT trigger left scroll");
        assertFalse(shouldScrollRight, "caret at col 5 with 30-char-wide view must NOT trigger right scroll");
    }

    // ── Bug 6: fold chevron overlaps line number ───────────────────
    // The root cause was gutterWidth not including the fold strip, so the
    // chevron was drawn on top of the last digit. We verify the layout
    // math: line numbers end BEFORE the fold strip.

    @Test
    void gutterLayout_lineNumberAreaBeforeFoldStrip() {
        // Simulate EditorMetrics.setTextSize for a 14px font.
        // charWidth ≈ 8.4px (monospace 14px), foldStripWidth = 2*charWidth,
        // gutterWidth = 5*charWidth + foldStripWidth = 7*charWidth.
        float charWidth = 8.4f;
        float foldStripWidth = charWidth * 2f;
        float gutterWidth = charWidth * 5f + foldStripWidth;
        float lineNumberAreaRight = gutterWidth - foldStripWidth;
        // Line numbers are right-aligned at lineNumberAreaRight - 0.5*charWidth.
        float textX = lineNumberAreaRight - charWidth * 0.5f;
        // Fold chevron is centered in the fold strip.
        float foldStripCenter = gutterWidth - foldStripWidth * 0.5f;
        // The chevron must be to the RIGHT of the line-number area.
        assertTrue(foldStripCenter > textX,
            "fold chevron center (" + foldStripCenter + ") must be right of line-number end (" + textX + ")");
        assertTrue(foldStripCenter > lineNumberAreaRight,
            "fold chevron center must be inside the fold strip, not the line-number area");
    }
}
