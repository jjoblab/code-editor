package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.chrome.GutterView;
import jo.codeeditor.view.preview.EditorPreviewHost;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B9c (retour terrain n°6) — verrouille l'icône d'aperçu de la
 * GOUTTIÈRE « comme Android Studio » : un œil posé sur la RANGÉE DU
 * CARET, visible pour un fichier prévisualisable, état actif quand la
 * feuille est ouverte, tap = BASCULE (ouvrir / fermer).
 *
 * <ul>
 *   <li>le rectangle est DÉTERMINISTE (même formule au dessin et au
 *       hit-test — GutterView.previewActionRect) ;</li>
 *   <li>le tap sur la pill bascule la feuille (SHEET_SPLIT → NONE) SANS
 *       déplacer le caret (le geste appartient à l'icône) ;</li>
 *   <li>aucune zone tactile fantôme : fichier non prévisualisable ou
 *       rangée du caret hors écran → pas de hit.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class GutterPreviewActionTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;

    private static final String DOC =
        "# Titre\n"                    // 0
        + "paragraphe court\n"         // 1
        + "- item un\n"                // 2
        + "- item deux\n"              // 3
        + "fin du markdown\n";         // 4

    /** Hôte d'aperçu minimal : prévisualise .md/.html, corps canvas vide. */
    private static final class StubPreviewHost implements EditorPreviewHost {
        @Override public boolean canPreview(String fileName) {
            String n = fileName == null ? "" : fileName.toLowerCase();
            return n.endsWith(".md") || n.endsWith(".markdown")
                    || n.endsWith(".html") || n.endsWith(".htm");
        }
        @Override public void onPreviewModeChanged(EditorView.PreviewMode mode,
                int previewLeft, int previewWidth) { }
        @Override public void onPreviewContentChanged(CharSequence text) { }
        @Override public void drawPreview(Canvas canvas, float offsetX, float offsetY) { }
        @Override public boolean hasPreviewContent() { return false; }
        @Override public boolean hitTestPreview(float x, float y) { return false; }
    }

    private EditorView newView(String fileName) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.setPreviewHost(new StubPreviewHost());
        view.setFileName(fileName);
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
            setFloat(view.metrics, "gutterWidth", charWidth * 7f);
            setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    // ── 1. Rectangle déterministe ─────────────────────────────────

    @Test
    public void previewActionRect_estDéterministeEtCentréSurLaRangée() {
        EditorView view = newView("exemple.md");
        RectF r = new RectF();
        GutterView.previewActionRect(view.metrics, 1f, 220f, LINE_H, r);
        // Zone des numéros : gutter 70 − fold 20 = 50 → centre X = 25.
        // Côté borné par min(26dp, lineHeight 40) = 26.
        assertEquals(25f, r.centerX(), 0.01f);
        // Centre vertical = 220 + 40/2 = 240.
        assertEquals(240f, r.centerY(), 0.01f);
        assertEquals(26f, r.width(), 0.01f);
        assertEquals(26f, r.height(), 0.01f);
        // Deux appels → mêmes valeurs (aucun état mutable).
        RectF r2 = new RectF();
        GutterView.previewActionRect(view.metrics, 1f, 220f, LINE_H, r2);
        assertEquals(r, r2);
    }

    // ── 2. Hit-test ───────────────────────────────────────────────

    @Test
    public void hitTest_caretVisibleEtFichierMd_vraiDansLaPill() {
        EditorView view = newView("exemple.md");
        view.getSession().setSelection(DOC.indexOf("paragraphe")); // ligne 1
        int caretLine = 1;
        float rowTop = view.docLineToY(caretLine) - view.vOffset;
        float cx = (view.metrics.getGutterWidth()
                - view.metrics.getFoldStripWidth()) * 0.5f;
        float cy = rowTop + LINE_H * 0.5f;

        assertTrue("hit dans la pill de la rangée du caret",
                view.hitTestGutterPreviewAction(cx, cy));
        // La pill couvre la rangée, pas la suivante.
        assertFalse("pas de hit sur la rangée suivante",
                view.hitTestGutterPreviewAction(cx, cy + LINE_H));
        // Hors gouttière → faux (c'est une action de GOUTTIÈRE).
        assertFalse("pas de hit hors de la gouttière",
                view.hitTestGutterPreviewAction(
                        view.metrics.getGutterWidth() + 50f, cy));
    }

    @Test
    public void hitTest_fichierNonPrévisualisable_aucuneZoneFantôme() {
        EditorView view = newView("Main.java");
        view.getSession().setSelection(DOC.indexOf("paragraphe"));
        int caretLine = 1;
        float rowTop = view.docLineToY(caretLine) - view.vOffset;
        float cx = (view.metrics.getGutterWidth()
                - view.metrics.getFoldStripWidth()) * 0.5f;
        assertFalse("pas de zone tactile pour un fichier non prévisualisable",
                view.hitTestGutterPreviewAction(cx, rowTop + LINE_H * 0.5f));
    }

    @Test
    public void hitTest_rangéeHorsÉcran_aucuneZoneFantôme() {
        EditorView view = newView("exemple.md");
        view.getSession().setSelection(DOC.indexOf("paragraphe"));
        // Défile la rangée du caret hors écran (au-dessus du viewport).
        view.vOffset = 4000f;
        float cx = (view.metrics.getGutterWidth()
                - view.metrics.getFoldStripWidth()) * 0.5f;
        assertFalse("pas de zone tactile quand la rangée du caret est hors écran",
                view.hitTestGutterPreviewAction(cx, 300f));
    }

    // ── 3. Tap = bascule de la feuille, caret non déplacé ─────────

    @Test
    public void tapSurLIcône_basculeLaFeuilleSansDéplacerLeCaret() {
        EditorView view = newView("exemple.md");
        int caretOffset = DOC.indexOf("paragraphe");
        view.getSession().setSelection(caretOffset);
        Selection selBefore = view.getSession().getSelection();

        int caretLine = 1;
        float rowTop = view.docLineToY(caretLine) - view.vOffset;
        float cx = (view.metrics.getGutterWidth()
                - view.metrics.getFoldStripWidth()) * 0.5f;
        float cy = rowTop + LINE_H * 0.5f;

        // Tap complet (DOWN dans la zone numéros → UP tap-like → handleTap).
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, cx, cy);
        send(view, MotionEvent.ACTION_UP, down, down + 60, cx, cy);
        idle();

        assertEquals("le tap ouvre la feuille d'aperçu scindée",
                EditorView.PreviewMode.SHEET_SPLIT, view.previewMode);
        assertEquals("le caret n'est PAS déplacé par le tap d'icône",
                selBefore, view.getSession().getSelection());

        // Second tap : referme (bascule exacte de l'œil du fil d'Ariane).
        long down2 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down2, down2, cx, cy);
        send(view, MotionEvent.ACTION_UP, down2, down2 + 60, cx, cy);
        idle();

        assertEquals("le second tap referme la feuille",
                EditorView.PreviewMode.NONE, view.previewMode);
    }

    @Test
    public void setPreviewAction_porteLÉtatPourLeDessin() {
        EditorView view = newView("exemple.md");
        // Le renderer pousse l'état chaque frame — le getter de service
        // n'existe pas (champs privés), on vérifie via un GutterView neuf
        // que setPreviewAction ne casse rien et reste silencieux.
        GutterView gutter = new GutterView(view.metrics, view.getTheme());
        gutter.setDensity(1f);
        gutter.setPreviewAction(true, false);
        gutter.setPreviewAction(true, true);
        gutter.setPreviewAction(false, false);
        // Sans crash ni état observable cassé : le dessin est couvert par
        // renderSmoke (Robolectric rastérise en no-op, l'important est
        // l'absence d'exception).
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                100, 400, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        gutter.setPreviewAction(true, true);
        gutter.draw(canvas, 0f, 400f, 5, 1);
        bmp.recycle();
    }
}
