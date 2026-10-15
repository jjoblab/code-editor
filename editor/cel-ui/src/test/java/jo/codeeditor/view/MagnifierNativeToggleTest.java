package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M3 (P2) — verrouille la <b>bascule de la loupe native (opt-in,
 * API 28+)</b> :
 *
 * <ul>
 *   <li><b>API &lt; 28</b> — même avec le drapeau natif actif, la loupe
 *       CUSTOM reste dessinée (repli — Robolectric ne peut pas
 *       instancier le Magnifier plateforme, la spéc demande de tester
 *       la bascule) ;</li>
 *   <li><b>Drapeau à faux (défaut)</b> — la loupe custom est dessinée,
 *       comportement inchangé ;</li>
 *   <li><b>API ≥ 28 + drapeau</b> — la loupe custom N'EST PAS dessinée
 *       (voie native — vérifiée par l'absence d'anneau custom, le
 *       widget plateforme étant un fenêtrage système, pas du canvas).</li>
 * </ul>
 *
 * <p>La décision de bascule elle-même (drapeau × niveau d'API) est
 * verrouillée en isolement par {@code MagnifierNativeDecisionTest}
 * (package render).</p>
 *
 * <p>Mise en scène : drag réel de la poignée de caret (DOWN puis
 * MOVE), frame rendue sur un canvas d'enregistrement qui capture
 * chaque cercle (l'anneau de la bulle custom = drawCircle au rayon
 * 60 dp).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class MagnifierNativeToggleTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2 — caret sur « greet »
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private static final class CircleEvent {
        final float cx, cy, radius;
        CircleEvent(float cx, float cy, float radius) {
            this.cx = cx; this.cy = cy; this.radius = radius;
        }
    }

    /** Canvas d'enregistrement : capture chaque drawCircle. */
    private static final class RecordingCanvas extends Canvas {
        final List<CircleEvent> circles = new ArrayList<>();
        @Override
        public void drawCircle(float cx, float cy, float radius, Paint paint) {
            circles.add(new CircleEvent(cx, cy, radius));
            super.drawCircle(cx, cy, radius, paint);
        }
    }

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
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    /** Amorce un drag de la poignée de caret (collapsed) : DOWN puis
     *  MOVE — la bulle s'allume au premier MOVE. */
    private void dragCaretHandle(EditorView view, int caret) {
        view.getSession().setSelection(caret);
        view.handlesVisible = true;
        float[] pos = view.caretScreenPos(caret);
        float density = view.getResources().getDisplayMetrics().density;
        float hx = pos[0];
        float hy = pos[1] + view.metrics.getLineHeight()
                + EditorView.HANDLE_RADIUS_DP * density * 0.6f;
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, hx, hy);
        assertEquals(3, view.handleDragMode);
        send(view, MotionEvent.ACTION_MOVE, down, down + 40, hx + 30f, hy);
        assertTrue("la loupe doit être active au premier MOVE",
                view.magnifierActive);
    }

    /** Vrai si la frame dessine l'anneau de la bulle custom (cercle au
     *  rayon 60 dp). */
    private static boolean customBubbleDrawn(EditorView view) {
        float density = view.getResources().getDisplayMetrics().density;
        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);
        for (CircleEvent c : canvas.circles) {
            if (Math.abs(c.radius - 60f * density) < 0.5f) return true;
        }
        return false;
    }

    @Test
    @Config(sdk = 27)
    public void api27_drapeauActif_repliCustomDessine() {
        EditorView view = newView();
        view.setNativeMagnifierEnabled(true);
        assertTrue("le drapeau natif doit être actif",
                view.isNativeMagnifierEnabled());
        dragCaretHandle(view, DOC.indexOf("greet"));
        assertTrue("API < 28 : la loupe CUSTOM doit rester dessinée "
                + "(repli — l'anneau 60dp est présent)",
                customBubbleDrawn(view));
    }

    @Test
    public void drapeauFaux_defaut_customDessine() {
        EditorView view = newView();
        dragCaretHandle(view, DOC.indexOf("greet"));
        assertTrue("défaut (drapeau faux) : la loupe custom doit être "
                + "dessinée — comportement inchangé",
                customBubbleDrawn(view));
    }

    @Test
    @Config(sdk = 34)
    public void api34_drapeauActif_customPasDessinee() {
        EditorView view = newView();
        view.setNativeMagnifierEnabled(true);
        dragCaretHandle(view, DOC.indexOf("greet"));
        // Le widget plateforme est un fenêtrage système : rien sur le
        // canvas — l'anneau custom (rayon 60dp) ne doit PAS être dessiné.
        assertTrue("API ≥ 28 + drapeau : la voie NATIVE doit être prise "
                        + "(aucun anneau custom sur le canvas)",
                !customBubbleDrawn(view));
    }
}
