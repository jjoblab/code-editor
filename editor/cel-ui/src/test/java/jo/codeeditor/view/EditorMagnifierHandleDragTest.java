package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * ★ v2.35 — verrouille la RÉ-ACTIVATION du magnifier (code dormant v3.18.0,
 * « interferes with selection ») : la bulle ne vit QUE pendant le drag des
 * poignées de sélection — activée au premier MOVE (un simple tap sur la
 * poignée ne la fait pas flasher), désactivée sur UP/CANCEL, et JAMAIS
 * pendant un scroll/drag-select ordinaire.
 *
 * @author jo@Dev
 * @since v2.35
 */
@RunWith(RobolectricTestRunner.class)
public class EditorMagnifierHandleDragTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;
    private static final float PAD_LEFT = 5f;
    private static final float GUTTER_W = 70f;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
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

    /** Position écran de la poignée de DÉBUT de sélection (hitTestHandle 1). */
    private float[] startHandlePos(EditorView view) {
        Selection sel = view.getSession().getSelection();
        float[] pos = view.caretScreenPos(sel.start);
        float density = view.getResources().getDisplayMetrics().density;
        return new float[]{pos[0], pos[1] + view.metrics.getLineHeight()
                + view.HANDLE_RADIUS_DP * density * 0.6f};
    }

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    @Test
    public void handleDrag_activatesMagnifierOnFirstMove_andFeedsFinger() {
        EditorView view = newView();
        int start = DOC.indexOf("greet");
        int end = DOC.indexOf("name") + 4;
        view.getSession().setSelection(Selection.range(start, end));
        view.handlesVisible = true;

        float[] handle = startHandlePos(view);
        long down = SystemClock.uptimeMillis();

        // DOWN sur la poignée : le geste est capturé, mais la bulle ne
        // s'allume PAS encore (un tap sur la poignée ne doit pas flasher).
        send(view, MotionEvent.ACTION_DOWN, down, down, handle[0], handle[1]);
        assertEquals(1, view.handleDragMode);
        assertFalse("DOWN alone must not show the magnifier", view.magnifierActive);

        // Premier MOVE : la bulle s'allume et suit le doigt.
        send(view, MotionEvent.ACTION_MOVE, down, down + 50, handle[0] + 40f, handle[1] + 12f);
        assertTrue("first MOVE must activate the magnifier", view.magnifierActive);
        assertEquals(handle[0] + 40f, view.magnifierX, 0.01f);
        assertEquals(handle[1] + 12f, view.magnifierY, 0.01f);

        // MOVE suivant : la position continue de suivre.
        send(view, MotionEvent.ACTION_MOVE, down, down + 90, handle[0] + 80f, handle[1] + 30f);
        assertEquals(handle[0] + 80f, view.magnifierX, 0.01f);

        // UP : la bulle s'éteint.
        send(view, MotionEvent.ACTION_UP, down, down + 120, handle[0] + 80f, handle[1] + 30f);
        assertFalse("UP must deactivate the magnifier", view.magnifierActive);
        assertEquals(0, view.handleDragMode);
    }

    @Test
    public void collapsedCaretHandleDrag_alsoMagnifies() {
        EditorView view = newView();
        int caret = DOC.indexOf("greet");
        view.getSession().setSelection(caret);
        view.handlesVisible = true;

        // Poignée du caret collapsed (hitTestHandle 3).
        float[] pos = view.caretScreenPos(caret);
        float density = view.getResources().getDisplayMetrics().density;
        float hx = pos[0];
        float hy = pos[1] + view.metrics.getLineHeight()
                + view.HANDLE_RADIUS_DP * density * 0.6f;

        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, hx, hy);
        assertEquals(3, view.handleDragMode);
        send(view, MotionEvent.ACTION_MOVE, down, down + 40, hx + 30f, hy);
        assertTrue(view.magnifierActive);
        send(view, MotionEvent.ACTION_UP, down, down + 80, hx + 30f, hy);
        assertFalse(view.magnifierActive);
    }

    @Test
    public void plainScrollDrag_neverActivatesMagnifier() {
        EditorView view = newView();
        // Un drag-scroll classique (texte, au-delà du slop) : PAS de bulle —
        // c'était le « interferes with selection » de v3.18.0.
        float x = GUTTER_W + 200f;
        float y = PAD_TOP + LINE_H * 2f;
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, x, y);
        send(view, MotionEvent.ACTION_MOVE, down, down + 60, x - 30f, y + 120f);
        send(view, MotionEvent.ACTION_MOVE, down, down + 120, x - 30f, y + 240f);
        send(view, MotionEvent.ACTION_UP, down, down + 160, x - 30f, y + 240f);
        assertFalse("a scroll drag must never show the magnifier",
                view.magnifierActive);
    }

    @Test
    public void renderSmoke_magnifierDrawnWithoutCrash() {
        EditorView view = newView();
        int start = DOC.indexOf("greet");
        view.getSession().setSelection(Selection.range(start, start + 5));
        view.magnifierActive = true;
        view.magnifierX = 300f;
        view.magnifierY = 400f;
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        try {
            view.draw(canvas);
        } finally {
            bmp.recycle();
        }
        // Aucune exception = le pipeline drawMagnifier (±3 lignes tissées
        // inlay-aware, clip circulaire, zoom 2x) est vivant.
    }
}
