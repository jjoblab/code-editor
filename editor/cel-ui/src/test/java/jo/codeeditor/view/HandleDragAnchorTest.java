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
 * L'ancre du drag de poignée doit être FIGÉE au DOWN : quand le doigt
 * franchit l'autre poignée, la sélection se renormalise (start/end
 * inversés) — relire la sélection à chaque MOVE pour déterminer l'ancre
 * faisait alors « sauter » l'ancre (elle devenait la position précédente
 * du doigt) et la sélection se réduisait à une mini-plage suivant le
 * doigt.
 */
@RunWith(RobolectricTestRunner.class)
public class HandleDragAnchorTest {

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

    /** Coordonnées écran du MILIEU du caractère à l'offset donné. */
    private float[] midCharPos(EditorView view, int offset) {
        float[] pos = view.caretScreenPos(offset);
        return new float[]{pos[0] + CHAR_W / 2f, pos[1] + LINE_H / 2f};
    }

    @Test
    public void draggingEndHandle_acrossStartHandle_keepsAnchorFrozen() {
        EditorView view = newView();
        int a = DOC.indexOf("greet");           // début de sélection
        int b = DOC.indexOf("name") + 4;        // fin de sélection
        view.getSession().setSelection(Selection.range(a, b));
        view.handlesVisible = true;

        // Poignée de FIN (hitTestHandle 2).
        float[] posB = view.caretScreenPos(b);
        float density = view.getResources().getDisplayMetrics().density;
        float hbX = posB[0];
        float hbY = posB[1] + view.metrics.getLineHeight()
                + view.HANDLE_RADIUS_DP * density * 0.6f;

        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, hbX, hbY);
        assertEquals(2, view.handleDragMode);

        // MOVE 1 : le doigt passe AVANT le début de la sélection
        // (colonne 0 de la ligne 2) — franchissement de l'autre poignée.
        int t1 = DOC.indexOf("        greet");
        float[] p1 = midCharPos(view, t1);
        send(view, MotionEvent.ACTION_MOVE, down, down + 60, p1[0], p1[1]);
        Selection s1 = view.getSession().getSelection();
        assertTrue("après franchissement, l'ancre originale (début "
                + a + ") doit rester une borne de la sélection [" + s1.start + "," + s1.end + "]",
                s1.start == a || s1.end == a);

        // MOVE 2 : encore plus à gauche (dans la ligne 1) — l'ancre doit
        // rester figée au MOVE suivant, pas suivre le doigt.
        int t2 = DOC.indexOf("    void run()") + 4;
        float[] p2 = midCharPos(view, t2);
        send(view, MotionEvent.ACTION_MOVE, down, down + 120, p2[0], p2[1]);
        Selection s2 = view.getSession().getSelection();
        assertTrue("l'ancre doit rester figée à travers les MOVE successifs"
                + " (sélection [" + s2.start + "," + s2.end + "])",
                s2.start == a || s2.end == a);
        // La sélection s'étend de l'ancre jusqu'au doigt — elle ne se
        // réduit pas à une mini-plage autour du doigt.
        int other = s2.start == a ? s2.end : s2.start;
        assertTrue("la borne mobile doit suivre le doigt (≈" + t2 + ")",
                Math.abs(other - t2) <= 2);

        send(view, MotionEvent.ACTION_UP, down, down + 160, p2[0], p2[1]);
        assertEquals(0, view.handleDragMode);
    }

    @Test
    public void draggingStartHandle_acrossEndHandle_keepsAnchorFrozen() {
        EditorView view = newView();
        int a = DOC.indexOf("greet");
        int b = DOC.indexOf("name") + 4;
        view.getSession().setSelection(Selection.range(a, b));
        view.handlesVisible = true;

        // Poignée de DÉBUT (hitTestHandle 1).
        float[] posA = view.caretScreenPos(a);
        float density = view.getResources().getDisplayMetrics().density;
        float haX = posA[0];
        float haY = posA[1] + view.metrics.getLineHeight()
                + view.HANDLE_RADIUS_DP * density * 0.6f;

        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, haX, haY);
        assertEquals(1, view.handleDragMode);

        // MOVE : le doigt passe APRÈS la fin de la sélection (le « ; »).
        int t = DOC.indexOf(");\n") + 1;
        float[] p = midCharPos(view, t);
        send(view, MotionEvent.ACTION_MOVE, down, down + 60, p[0], p[1]);
        Selection s = view.getSession().getSelection();
        assertTrue("l'ancre (fin originale " + b + ") doit rester une borne"
                + " (sélection [" + s.start + "," + s.end + "])",
                s.start == b || s.end == b);
        int other = s.start == b ? s.end : s.start;
        assertTrue("la borne mobile doit suivre le doigt (≈" + t + ")",
                Math.abs(other - t) <= 2);

        send(view, MotionEvent.ACTION_UP, down, down + 100, p[0], p[1]);
    }
}
