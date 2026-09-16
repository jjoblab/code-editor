package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Canvas;

import static org.junit.Assert.*;

/**
 * Robolectric tests for v2.39 features:
 * <ul>
 *   <li>Markdown/HTML preview badge → popup sheet overlay
 *       ({@link EditorView#openPreview(boolean)}, {@link EditorPreviewSheet})</li>
 *   <li>Tap-and-hold hover toggle
 *       ({@link EditorView#setTouchHoverEnabled(boolean)})</li>
 *   <li>Signature help Up/Down keyboard navigation
 *       ({@link EditorView#cycleSignatureHelp(int)},
 *        {@link EditorView#getEffectiveActiveSignature()})</li>
 * </ul>
 *
 * @author jo@Dev
 * @since v2.39
 */
@RunWith(RobolectricTestRunner.class)
public class EditorPreviewSheetTest {

    private EditorView createEditor() {
        Context ctx = RuntimeEnvironment.getApplication();
        return new EditorView(ctx);
    }

    /**
     * A minimal {@link EditorPreviewHost} that declares {@code .md}/{@code .html}/{@code .xml}
     * as previewable and returns {@code null} from {@code onCreatePreviewView}
     * (canvas fallback path).
     */
    private static class StubHost implements EditorPreviewHost {
        @Override public boolean canPreview(String fileName) {
            if (fileName == null) return false;
            String l = fileName.toLowerCase();
            return l.endsWith(".md") || l.endsWith(".html") || l.endsWith(".xml");
        }
        @Override
        public void onPreviewModeChanged(EditorView.PreviewMode mode,
                                          int previewLeft, int previewWidth) {}
        @Override public void onPreviewContentChanged(CharSequence text) {}
        @Override public void drawPreview(Canvas canvas, float offsetX, float offsetY) {}
        @Override public boolean hasPreviewContent() { return true; }
        @Override public boolean hitTestPreview(float x, float y) { return false; }
    }

    // ── Touch hover toggle ─────────────────────────────────────────

    @Test
    public void touchHoverEnabled_defaultFalse() {
        EditorView view = createEditor();
        assertFalse("touch hover should default to false (legacy long-press)",
            view.isTouchHoverEnabled());
    }

    @Test
    public void touchHoverEnabled_toggleReflectsSetter() {
        EditorView view = createEditor();
        view.setTouchHoverEnabled(true);
        assertTrue(view.isTouchHoverEnabled());
        view.setTouchHoverEnabled(false);
        assertFalse(view.isTouchHoverEnabled());
    }

    // ── Preview: fileName → previewable detection ─────────────────

    @Test
    public void setFileName_withPreviewHost_marksPreviewable() {
        EditorView view = createEditor();
        view.setPreviewHost(new StubHost());
        view.setFileName("README.md");
        assertTrue("README.md should be previewable", view.previewable);
    }

    @Test
    public void setFileName_withNonPreviewableExtension_notPreviewable() {
        EditorView view = createEditor();
        view.setPreviewHost(new StubHost());
        view.setFileName("Main.java");
        assertFalse("Main.java should NOT be previewable", view.previewable);
    }

    @Test
    public void getFileName_returnsLastSet() {
        EditorView view = createEditor();
        view.setFileName("docs/intro.html");
        assertEquals("docs/intro.html", view.getFileName());
        view.setFileName(null);
        assertEquals("", view.getFileName());
    }

    // ── PreviewMode enum ───────────────────────────────────────────

    @Test
    public void previewMode_noneByDefault() {
        EditorView view = createEditor();
        assertEquals(EditorView.PreviewMode.NONE, view.getPreviewMode());
    }

    @Test
    public void previewMode_isSheetFlagFalseForNoneAndInline() {
        assertFalse(EditorView.PreviewMode.NONE.isSheet());
        assertFalse(EditorView.PreviewMode.SPLIT.isSheet());
        assertFalse(EditorView.PreviewMode.FULL.isSheet());
    }

    @Test
    public void previewMode_isSheetFlagTrueForSheetModes() {
        assertTrue(EditorView.PreviewMode.SHEET_SPLIT.isSheet());
        assertTrue(EditorView.PreviewMode.SHEET_FULL.isSheet());
    }

    // ── Sheet overlay lifecycle ────────────────────────────────────

    @Test
    public void getPreviewSheet_nullWhenNone() {
        EditorView view = createEditor();
        assertNull(view.getPreviewSheet());
    }

    @Test
    public void closePreviewSheet_isSafeWhenNoSheet() {
        EditorView view = createEditor();
        // Should not throw when no sheet is open.
        view.closePreviewSheet();
        assertEquals(EditorView.PreviewMode.NONE, view.getPreviewMode());
    }

    @Test
    public void isXmlPreviewActive_falseForSheetModes() {
        EditorView view = createEditor();
        view.setPreviewHost(new StubHost());
        // SHEET_* modes never draw on the editor canvas.
        view.setPreviewMode(EditorView.PreviewMode.SHEET_SPLIT);
        assertFalse("SHEET_SPLIT should not draw on editor canvas",
            view.isXmlPreviewActive());
        view.setPreviewMode(EditorView.PreviewMode.SHEET_FULL);
        assertFalse("SHEET_FULL should not draw on editor canvas",
            view.isXmlPreviewActive());
    }

    // ── Signature help Up/Down navigation ─────────────────────────

    @Test
    public void getEffectiveActiveSignature_noHelp_returnsNegativeOne() {
        EditorView view = createEditor();
        // No signature help populated → effective active = -1.
        assertEquals(-1, view.getEffectiveActiveSignature());
    }

    @Test
    public void cycleSignatureHelp_withNoHelp_isSafeNoOp() {
        EditorView view = createEditor();
        // Should not crash when no help is loaded.
        view.cycleSignatureHelp(1);
        view.cycleSignatureHelp(-1);
        assertEquals(-1, view.getEffectiveActiveSignature());
    }

    // ── v2.42 — Defensive detach in EditorPreviewSheet.show ─────────

    /**
     * Stub host that returns the SAME cached View across calls —
     * simulates the AppEditorPreviewHost pattern (cached WebView reused).
     */
    private static class CachedViewHost implements EditorPreviewHost {
        private android.view.View cachedBody;

        @Override public boolean canPreview(String fileName) {
            if (fileName == null) return false;
            String l = fileName.toLowerCase();
            return l.endsWith(".md") || l.endsWith(".html");
        }
        @Override
        public void onPreviewModeChanged(EditorView.PreviewMode mode,
                                          int previewLeft, int previewWidth) {}
        @Override public void onPreviewContentChanged(CharSequence text) {}
        @Override public void drawPreview(Canvas canvas, float offsetX, float offsetY) {}
        @Override public boolean hasPreviewContent() { return true; }
        @Override public boolean hitTestPreview(float x, float y) { return false; }
        @Override
        public android.view.View onCreatePreviewView(Context ctx, EditorView editor,
                                                     EditorView.PreviewMode mode) {
            // Return the SAME View on every call — this is the AppEditorPreviewHost
            // pattern. Without defensive detach, the second openPreview call would
            // throw IllegalStateException: "child already has a parent".
            if (cachedBody == null) {
                cachedBody = new android.view.View(ctx);
            }
            return cachedBody;
        }
    }

    @Test
    public void openPreviewSheet_cachedBodyViewAcrossOpen_reusesWithoutCrash() {
        // Regression for the user-reported crash on EditorPreviewSheet.show:102
        // (java.lang.IllegalStateException: The specified child already has a parent).
        // The host caches the WebView; the second open tried to add it to a new
        // bodyFrame without detaching first. The fix detaches the bodyView in
        // EditorPreviewSheet.show() before re-adding it.
        EditorView view = createEditor();
        view.setPreviewHost(new CachedViewHost());
        view.setFileName("README.md");
        // EditorView.openPreviewSheet bails early (post-defer) when width/height
        // are 0. Measure + layout the editor to non-zero dims so the sheet opens
        // synchronously inside the test.
        int w = 1024, h = 768;
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        // First open — sheet shows, cached body gets a parent.
        view.setPreviewMode(EditorView.PreviewMode.SHEET_SPLIT);
        EditorPreviewSheet sheet1 = view.getPreviewSheet();
        assertTrue("first open should succeed (sheet non-null)",
            sheet1 != null);
        assertTrue("first open should show", sheet1.isShowing());
        // Close.
        view.closePreviewSheet();
        assertEquals(EditorView.PreviewMode.NONE, view.getPreviewMode());
        // Second open — must NOT throw IllegalStateException (the v2.42 fix).
        view.setPreviewMode(EditorView.PreviewMode.SHEET_SPLIT);
        EditorPreviewSheet sheet2 = view.getPreviewSheet();
        assertTrue("second open should succeed after defensive detach (sheet non-null)",
            sheet2 != null);
        assertTrue("second open should show", sheet2.isShowing());
        // Cleanup.
        view.closePreviewSheet();
    }
}
