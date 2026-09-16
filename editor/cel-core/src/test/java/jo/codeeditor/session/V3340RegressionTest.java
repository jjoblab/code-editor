package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.shift.DiagnosticShift;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3.34.0 regression tests — lifecycle disposal, per-line buckets and the
 * async-restyle re-schedule fix.
 *
 * <p>Each test pins one of the fixes shipped in v3.34.0 so a future change
 * can't silently bring the bug back.</p>
 */
class V3340RegressionTest {

    // ── dispose(): releases the restyle executor ───────────────────

    @Test
    void dispose_isDisposed_flagAndPendingCancel() throws Exception {
        EditorSession s = new EditorSession(EditorDocument.of("line1\nline2\nline3"));
        assertFalse(s.isDisposed(), "fresh session must not be disposed");
        // Trigger an async restyle so there IS something pending to cancel.
        s.setLanguage("kotlin");
        s.dispose();
        assertTrue(s.isDisposed(), "dispose() must set the flag");
        // No pending restyle survives the dispose.
        assertFalse(s.isAsyncRestylePending(), "dispose() must cancel pending restyles");
    }

    @Test
    void dispose_fallsBackToSyncRestyle() {
        EditorSession s = new EditorSession(EditorDocument.of("public class A {}"));
        s.dispose();
        // setLanguage after dispose must NOT throw RejectedExecutionException —
        // it falls back to the synchronous restyle path.
        assertDoesNotThrow(() -> s.setLanguage("xml"));
        assertEquals(1, s.getStyledLines().size(), "one-line document → one styled line");
        // And the doc is still fully editable afterwards ("public class A {}"
        // is 17 chars — appending X at the end gives 18).
        s.setSelection(17);
        s.commitText("X");
        assertEquals(18, s.getText().length());
    }

    // ── Per-line buckets (getInlayHintsForLine / getSemanticTokensForLine) ──

    private static DiagnosticShift.InlayHint hint(int offset, String text) {
        return new DiagnosticShift.InlayHint(offset, text, false);
    }

    private static DiagnosticShift.SemanticToken token(int start, int length, int type) {
        return new DiagnosticShift.SemanticToken(start, length, type);
    }

    @Test
    void inlayHintsForLine_bucketsByOffset() {
        EditorSession s = new EditorSession(EditorDocument.of("aa\nbbbb\ncc\ndd"));
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(hint(0, "h0"));    // line 0, col 0
        hints.add(hint(3, "h3"));    // line 1, col 0
        hints.add(hint(6, "h6"));    // line 1, col 3
        hints.add(hint(11, "h11"));  // line 3, col 0 (doc len 12, line 3 = [11,12))
        s.setInlayHints(hints);
        assertEquals(1, s.getInlayHintsForLine(0).size());
        assertEquals(2, s.getInlayHintsForLine(1).size());
        assertEquals(0, s.getInlayHintsForLine(2).size(), "line 2 has no hints");
        assertEquals(1, s.getInlayHintsForLine(3).size());
        // Line far beyond any hint → empty, not null, not an exception.
        assertTrue(s.getInlayHintsForLine(999).isEmpty());
    }

    @Test
    void inlayHintsForLine_indexRebuiltAfterEdit() {
        EditorSession s = new EditorSession(EditorDocument.of("aaaa\nbbbb"));
        s.setInlayHints(new ArrayList<>(List.of(hint(5, "x")))); // line 1
        assertEquals(1, s.getInlayHintsForLine(1).size());
        // Insert a newline at offset 2 — the hint at old-offset 5 shifts to
        // offset 6 and now lives on line 2.
        s.setSelection(2);
        s.commitText("\n");
        // The shift moved the hint; the bucket index must be rebuilt from the
        // NEW list (different reference) and reflect the NEW offsets.
        List<DiagnosticShift.InlayHint> shifted = s.getInlayHints();
        assertEquals(6, shifted.get(0).offset, "DiagnosticShift must move the hint past the edit");
        assertEquals(1, s.getInlayHintsForLine(2).size(), "hint now on line 2");
        assertEquals(0, s.getInlayHintsForLine(1).size());
    }

    @Test
    void semanticTokensForLine_multiLineTokenAppearsInEveryBucket() {
        // Doc layout: line0=[0,4] line1=[5,9] line2=[10,14] line3=[15,18].
        // Token [0,14) spans lines 0..2 — must be visible in every one of
        // those buckets, and nowhere else.
        EditorSession s = new EditorSession(EditorDocument.of("aaaa\nbbbb\ncccc\ndddd"));
        s.setSemanticTokens(new ArrayList<>(List.of(token(0, 14, 1))));
        for (int line = 0; line <= 2; line++) {
            assertEquals(1, s.getSemanticTokensForLine(line).size(),
                    "multi-line token must appear in line " + line + "'s bucket");
        }
        assertEquals(0, s.getSemanticTokensForLine(3).size(), "token ends before line 3");
    }

    @Test
    void semanticTokensForLine_clampedToDocument() {
        EditorSession s = new EditorSession(EditorDocument.of("ab\ncd"));
        // Pathological token past the end — buckets must not throw.
        s.setSemanticTokens(new ArrayList<>(List.of(token(5, 100, 1))));
        assertDoesNotThrow(() -> s.getSemanticTokensForLine(0));
        assertDoesNotThrow(() -> s.getSemanticTokensForLine(1));
    }

    // ── Getters: no-copy views stay consistent with the live state ──

    @Test
    void diagnosticsGetter_returnsLiveSnapshotView() {
        EditorSession s = new EditorSession(EditorDocument.of("x"));
        List<DiagnosticShift.Diagnostic> d1 = s.getDiagnostics();
        List<DiagnosticShift.Diagnostic> d2 = s.getDiagnostics();
        // Same underlying (immutable-after-publish) list — no defensive copy.
        // Both views are equal and read-only.
        assertEquals(d1, d2);
        assertThrows(UnsupportedOperationException.class, () -> d1.add(null));
    }

    // ── Async restyle: an edit during the gap must NOT leave stale tokens ──

    @Test
    void editDuringAsyncRestyleGap_eventuallyRestylesWholeDocument() throws Exception {
        // Regression for the v3.34.0 fix: before it, an edit landing inside
        // the async restyle gap was detected (doc != docAtStart) but the
        // result was dropped WITHOUT re-scheduling — the document kept the
        // OLD language's tokens for every line except the edited one.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("int x").append(i).append(" = 0;\n");
        }
        EditorSession s = new EditorSession(EditorDocument.of(sb.toString()));
        // Switch language (triggers async restyle) and IMMEDIATELY edit —
        // the edit lands inside the async gap with near-certainty.
        s.setLanguage("xml");
        int end = s.getText().length();
        s.setSelection(end - 1);
        s.commitText("y");
        // Wait for all pending (and re-scheduled) restyles to drain.
        long deadline = System.currentTimeMillis() + 10_000;
        while (s.isAsyncRestylePending() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        s.awaitPendingRestyle();
        // The final styledLines must be tokenized in the NEW language for
        // EVERY line — spot-check a few lines' entry state consistency.
        List<StyledLine> styled = s.getStyledLines();
        assertEquals(s.getDocument().lineCount(), styled.size(),
                "styledLines must cover the whole document after the gap edit");
        // Each line's entryState must equal the previous line's exitState —
        // a mixed-language state would break this chain somewhere.
        for (int i = 1; i < styled.size(); i++) {
            assertEquals(styled.get(i - 1).exitState, styled.get(i).entryState,
                    "tokenization chain broken at line " + i + " — mixed-language state");
        }
    }
}
