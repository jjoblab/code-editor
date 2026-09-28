package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Drag-select au doigt (tap puis glissement court) : l'ancre est figée à
 * l'armement (position du caret posé par le tap). Deux défauts corrigés :
 * <ul>
 *   <li>l'UP du tap désarmait le drag-select immédiatement après l'avoir
 *       armé (le reset d'état suivait handleTap) — le glissement ne
 *       sélectionnait jamais rien ;</li>
 *   <li>handleTouchDrag relisait min(start,end) à chaque MOVE — le doigt
 *       ne pouvait pas franchir le point de départ (retour en arrière =
 *       effondrement de la sélection sur le caret).</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class TouchDragSelectAnchorTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

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

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    /** Coordonnées écran visant le QUART GAUCHE du caractère à l'offset
     *  donné (offsetAt arrondit : viser le centre du caractère donnerait
     *  le caractère suivant). */
    private float[] midCharPos(EditorView view, int offset) {
        float[] pos = view.caretScreenPos(offset);
        return new float[]{pos[0] + CHAR_W * 0.25f, pos[1] + LINE_H / 2f};
    }

    @Test
    public void tapThenDrag_selectsAroundTapCaret_andShrinksBackwards() {
        EditorView view = newView();
        int c = DOC.indexOf("name");  // caret posé par le tap

        // 1. Tap simple : pose le caret et arme le drag-select.
        float[] pc = midCharPos(view, c);
        long t0 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, t0, t0, pc[0], pc[1]);
        send(view, MotionEvent.ACTION_UP, t0, t0 + 40, pc[0], pc[1]);
        Selection afterTap = view.getSession().getSelection();
        assertTrue("le tap doit poser le caret", afterTap.isCursor());
        assertEquals(c, afterTap.start);

        // 2. Glissement court (sous le tap-slop de 24 px — le drag-select
        //    est un micro-ajustement par conception) vers la DROITE du
        //    caret : la sélection s'étend du caret au doigt.
        int right = c + 2;  // 20 px de delta — sous le slop
        float[] pr = midCharPos(view, right);
        long t1 = t0 + 300;
        send(view, MotionEvent.ACTION_DOWN, t1, t1, pc[0], pc[1]);
        send(view, MotionEvent.ACTION_MOVE, t1, t1 + 30, pr[0], pr[1]);
        Selection s1 = view.getSession().getSelection();
        assertTrue("le drag doit produire une vraie plage [" + s1.start
                + "," + s1.end + "] (le drag-select doit être armé par le tap)",
                s1.start < s1.end);
        assertEquals("le caret du tap est l'ancre", c, s1.start);

        // 3. Toujours dans le MÊME geste, retour en arrière qui FRANCHIT le
        //    caret : la sélection doit s'étendre dans l'autre sens (le
        //    caret reste une borne), pas s'effondrer sur le caret.
        int left = c - 1;  // avant le '(' de greet(
        float[] pl = midCharPos(view, left);
        send(view, MotionEvent.ACTION_MOVE, t1, t1 + 60, pl[0], pl[1]);
        Selection s2 = view.getSession().getSelection();
        assertTrue("le franchissement du caret doit donner une vraie plage ["
                + s2.start + "," + s2.end + "] qui contient le caret",
                s2.start < s2.end);
        assertEquals("le caret du tap doit rester une borne (ancre figée)", c, s2.end);
        assertTrue("la borne mobile suit le doigt (≈" + left + ")",
                Math.abs(s2.start - left) <= 2);

        // 4. UP : le geste a dragué — l'UP ne doit PAS résoudre un tap qui
        //    reposerait le caret et écraserait la sélection du drag.
        send(view, MotionEvent.ACTION_UP, t1, t1 + 90, pl[0], pl[1]);
        Selection s3 = view.getSession().getSelection();
        assertEquals("l'UP d'un drag ne doit pas écraser la sélection",
                s2, s3);
    }
}
