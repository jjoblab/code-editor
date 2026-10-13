package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.chrome.EditorFastScroller;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B11 (P0-3, feuille de route « écarts Android Studio ») — verrouille le
 * fast-scroller de gouttière : le drag dans la zone des numéros devient un
 * positionnement ABSOLU du viewport (le pouce suit la hauteur du doigt),
 * avec retour visuel, et le tap reste un tap.
 *
 * <ul>
 *   <li>drag au-delà du slop → vOffset suit la proportion absolue
 *       (top / (viewH − thumbH) × maxV) et le pouce est ACTIF ;</li>
 *   <li>sous le slop : RIEN (le jitter d'un tap ne doit ni flasher le
 *       pouce ni défiler) ;</li>
 *   <li>relâchement : le drag s'arrête SANS fling (positionnement absolu) ;</li>
 *   <li>annulation / pincement : disparition immédiate, sans fondu.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class FastScrollGutterDragTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;
    private static final float PAD_TOP = 20f;
    private static final float PAD_BOTTOM = 0f;
    private static final int LINES = 200;

    private static String doc() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LINES; i++) {
            sb.append("ligne ").append(i).append('\n');
        }
        return sb.toString();
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc()));
        view.setSession(session);
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        injectMetrics(view, LINE_H, CHAR_W, PAD_TOP, PAD_BOTTOM);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight,
            float charWidth, float padTop, float padBottom) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", padTop);
            setFloat(view.metrics, "padBottom", padBottom);
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

    /** Zone des numéros : x < gutterWidth − foldStrip = 70 − 20 = 50. */
    private static final float GUTTER_X = 20f;

    // ── 1. Drag de gouttière = position absolue ───────────────────

    @Test
    public void dragDeGouttièrePositionneLeViewportEnAbsolu() {
        EditorView view = newView();
        float maxV = view.maxV();
        assertTrue("document plus long que le viewport",
                maxV > 0f);
        float contentH = maxV + VIEW_H;
        float density = view.getResources().getDisplayMetrics().density;
        float thumbH = EditorFastScroller.thumbHeightFor(VIEW_H, contentH, density);
        float span = VIEW_H - thumbH;
        float y = 960f;
        float expected = Math.max(0f, Math.min(span, y - thumbH * 0.5f))
                / span * maxV;

        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, GUTTER_X, 500f);
        send(view, MotionEvent.ACTION_MOVE, down, down + 50, GUTTER_X, y);
        idle();

        assertTrue("le pouce du fast-scroller est actif pendant le drag",
                view.fastScroller.isActive());
        assertEquals("vOffset suit la proportion ABSOLUE du doigt",
                expected, view.vOffset, 0.5f);
    }

    // ── 2. Sous le slop : rien ─────────────────────────────────────

    @Test
    public void sousLeSlopRienNeBouge() {
        EditorView view = newView();
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, GUTTER_X, 500f);
        // Jitter de 10 px — sous le touch-slop de 24 px.
        send(view, MotionEvent.ACTION_MOVE, down, down + 30, GUTTER_X, 510f);
        idle();

        assertFalse("aucun pouce pour un mouvement sous le slop",
                view.fastScroller.isActive());
        assertEquals("aucun défilement pour un mouvement sous le slop",
                0f, view.vOffset, 0.01f);
    }

    // ── 3. Tap de gouttière sans mouvement = un tap ────────────────

    @Test
    public void tapDeGouttièreSansMouvementNeRéveillePasLePouce() {
        EditorView view = newView();
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, GUTTER_X, 500f);
        send(view, MotionEvent.ACTION_UP, down, down + 60, GUTTER_X, 500f);
        idle();

        assertFalse("un tap (sans mouvement) n'affiche jamais le pouce",
                view.fastScroller.isActive());
        assertEquals("un tap de gouttière ne défile pas",
                0f, view.vOffset, 0.01f);
    }

    // ── 4. Relâchement : arrêt sans fling ──────────────────────────

    @Test
    public void relâchementArrêteLeDragSansFling() {
        EditorView view = newView();
        float maxV = view.maxV();
        float contentH = maxV + VIEW_H;
        float density = view.getResources().getDisplayMetrics().density;
        float thumbH = EditorFastScroller.thumbHeightFor(VIEW_H, contentH, density);
        float span = VIEW_H - thumbH;
        float y = 960f;
        float expected = Math.max(0f, Math.min(span, y - thumbH * 0.5f))
                / span * maxV;

        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, GUTTER_X, 500f);
        send(view, MotionEvent.ACTION_MOVE, down, down + 50, GUTTER_X, y);
        send(view, MotionEvent.ACTION_UP, down, down + 100, GUTTER_X, y);
        idle();

        assertFalse("le pouce n'est plus actif après le relâchement",
                view.fastScroller.isActive());
        // Positionnement ABSOLU : la position du relâchement est conservée
        // telle quelle — un fling la casserait (geste de navigation, pas
        // d'inertie).
        assertEquals("la position absolue est conservée, sans fling",
                expected, view.vOffset, 0.5f);
    }

    // ── 5. Annulation : disparition immédiate ──────────────────────

    @Test
    public void annulationMasqueLePouceImmédiatement() {
        EditorView view = newView();
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, GUTTER_X, 500f);
        send(view, MotionEvent.ACTION_MOVE, down, down + 50, GUTTER_X, 960f);
        send(view, MotionEvent.ACTION_CANCEL, down, down + 80, GUTTER_X, 960f);
        idle();

        assertFalse("CANCEL masque le pouce immédiatement (sans fondu)",
                view.fastScroller.isActive());
        assertFalse("le fast-scroller est désarmé après CANCEL",
                view.fastScroller.isArmed());
    }
}
