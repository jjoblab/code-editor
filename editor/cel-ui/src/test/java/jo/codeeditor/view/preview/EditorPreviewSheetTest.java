package jo.codeeditor.view.preview;

import jo.codeeditor.view.EditorView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Canvas;

import static org.junit.Assert.*;

/**
 * Tests Robolectric :
 * <ul>
 *   <li>Badge preview Markdown/HTML → overlay sheet popup
 *       ({@link EditorView#openPreview(boolean)}, {@link EditorPreviewSheet})</li>
 *   <li>Bascule hover par appui-long tactile
 *       ({@link EditorView#setTouchHoverEnabled(boolean)})</li>
 *   <li>Navigation clavier Haut/Bas de l'aide de signature
 *       ({@link EditorView#cycleSignatureHelp(int)},
 *        {@link EditorView#getEffectiveActiveSignature()})</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class EditorPreviewSheetTest {

    private EditorView createEditor() {
        Context ctx = RuntimeEnvironment.getApplication();
        return new EditorView(ctx);
    }

    /**
     * Un {@link EditorPreviewHost} minimal qui déclare {@code .md}/{@code .html}/{@code .xml}
     * prévisualisables et renvoie {@code null} depuis {@code onCreatePreviewView}
     * (chemin de repli canvas).
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

    // ── Bascule hover tactile ──────────────────────────────────────

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

    // ── Preview : fileName → détection prévisualisable ─────────────

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

    // ── Énum PreviewMode ───────────────────────────────────────────

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

    // ── Cycle de vie de l'overlay sheet ────────────────────────────

    @Test
    public void getPreviewSheet_nullWhenNone() {
        EditorView view = createEditor();
        assertNull(view.getPreviewSheet());
    }

    @Test
    public void closePreviewSheet_isSafeWhenNoSheet() {
        EditorView view = createEditor();
        // Ne doit pas throw quand aucune sheet n'est ouverte.
        view.closePreviewSheet();
        assertEquals(EditorView.PreviewMode.NONE, view.getPreviewMode());
    }

    @Test
    public void isXmlPreviewActive_falseForSheetModes() {
        EditorView view = createEditor();
        view.setPreviewHost(new StubHost());
        // Les modes SHEET_* ne dessinent jamais sur le canvas de l'éditeur.
        view.setPreviewMode(EditorView.PreviewMode.SHEET_SPLIT);
        assertFalse("SHEET_SPLIT should not draw on editor canvas",
            view.isXmlPreviewActive());
        view.setPreviewMode(EditorView.PreviewMode.SHEET_FULL);
        assertFalse("SHEET_FULL should not draw on editor canvas",
            view.isXmlPreviewActive());
    }

    // ── Navigation Haut/Bas de l'aide de signature ────────────────

    @Test
    public void getEffectiveActiveSignature_noHelp_returnsNegativeOne() {
        EditorView view = createEditor();
        // Aucune aide de signature peuplée → actif effectif = -1.
        assertEquals(-1, view.getEffectiveActiveSignature());
    }

    @Test
    public void cycleSignatureHelp_withNoHelp_isSafeNoOp() {
        EditorView view = createEditor();
        // Ne doit pas crasher quand aucune aide n'est chargée.
        view.cycleSignatureHelp(1);
        view.cycleSignatureHelp(-1);
        assertEquals(-1, view.getEffectiveActiveSignature());
    }

    // ── Détachement défensif dans EditorPreviewSheet.show ──────────

    /**
     * Stub d'hôte qui renvoie la MÊME View en cache à chaque appel —
     * simule le patron AppEditorPreviewHost (WebView en cache réutilisée).
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
            // Renvoie la MÊME View à chaque appel — c'est le patron
            // AppEditorPreviewHost. Sans détachement défensif, le second
            // appel openPreview lèverait IllegalStateException :
            // « child already has a parent ».
            if (cachedBody == null) {
                cachedBody = new android.view.View(ctx);
            }
            return cachedBody;
        }
    }

    @Test
    public void openPreviewSheet_cachedBodyViewAcrossOpen_reusesWithoutCrash() {
        // Régression du crash signalé sur EditorPreviewSheet.show:102
        // (java.lang.IllegalStateException: The specified child already has
        // a parent). L'hôte met la WebView en cache ; la seconde ouverture
        // tentait de l'ajouter à un nouveau bodyFrame sans la détacher
        // d'abord. Le correctif détache le bodyView dans
        // EditorPreviewSheet.show() avant de le ré-ajouter.
        EditorView view = createEditor();
        view.setPreviewHost(new CachedViewHost());
        view.setFileName("README.md");
        // EditorView.openPreviewSheet abandonne tôt (post-defer) quand
        // width/height sont 0. Measure + layout de l'éditeur à des
        // dimensions non nulles pour que la sheet s'ouvre de façon
        // synchrone dans le test.
        int w = 1024, h = 768;
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
        // Première ouverture — la sheet s'affiche, le body en cache reçoit
        // un parent.
        view.setPreviewMode(EditorView.PreviewMode.SHEET_SPLIT);
        EditorPreviewSheet sheet1 = view.getPreviewSheet();
        assertTrue("first open should succeed (sheet non-null)",
            sheet1 != null);
        assertTrue("first open should show", sheet1.isShowing());
        // Fermeture.
        view.closePreviewSheet();
        assertEquals(EditorView.PreviewMode.NONE, view.getPreviewMode());
        // Seconde ouverture — ne doit PAS lever IllegalStateException
        // (le détachement défensif).
        view.setPreviewMode(EditorView.PreviewMode.SHEET_SPLIT);
        EditorPreviewSheet sheet2 = view.getPreviewSheet();
        assertTrue("second open should succeed after defensive detach (sheet non-null)",
            sheet2 != null);
        assertTrue("second open should show", sheet2.isShowing());
        // Nettoyage.
        view.closePreviewSheet();
    }
}
