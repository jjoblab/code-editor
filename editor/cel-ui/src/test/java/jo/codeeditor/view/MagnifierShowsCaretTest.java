package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M1 (P1) — verrouille la <b>barre de caret dans la loupe</b> :
 *
 * <p>La loupe sert précisément à régler la position du caret ENTRE
 * deux caractères — l'objet de la manœuvre doit être visible dans
 * l'instrument de visée. Avant M1, le contenu redessiné était le
 * texte seul : ni caret, ni bande, ni squiggle.</p>
 *
 * <p>Mise en scène : drag réel de la poignée de caret (DOWN puis
 * MOVE — la bulle s'allume au premier MOVE, motif
 * {@code EditorMagnifierHandleDragTest}), puis une frame rendue sur
 * un canvas d'enregistrement. Le caret principal (hors bulle) ET le
 * caret redessiné dans la bulle produisent CHACUN une barre
 * verticale pleine ligne en couleur {@code theme.caret} : deux
 * barres = le caret est visible dans la loupe ; une seule = il ne
 * l'est pas (état d'avant M1).</p>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorPendingTapDismissTest}). Le canvas d'enregistrement
 * capture la couleur À L'INSTANT du draw — les events de
 * ShadowCanvas gardent la RÉFÉRENCE du paint muté au fil de la
 * frame (la couleur y serait celle du dernier usage).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class MagnifierShowsCaretTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2 — caret sur « greet »
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Barre de caret verticale : même x, hauteur = une pleine ligne. */
    private static final class LineEvent {
        final float x1, y1, x2, y2;
        final int color;
        LineEvent(float x1, float y1, float x2, float y2, int color) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.color = color;
        }
        boolean isVerticalBar(float lineHeight) {
            return Math.abs(x2 - x1) < 0.5f && Math.abs((y2 - y1) - lineHeight) < 2f;
        }
    }

    /** Canvas d'enregistrement : capture chaque drawLine avec la
     *  couleur courante du paint. */
    private static final class RecordingCanvas extends Canvas {
        final List<LineEvent> lines = new ArrayList<>();
        @Override
        public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
            lines.add(new LineEvent(startX, startY, stopX, stopY, paint.getColor()));
            super.drawLine(startX, startY, stopX, stopY, paint);
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

    /** Amorce un drag de la poignée de caret (collapsed) : DOWN puis
     *  MOVE — la bulle s'allume au premier MOVE. */
    private void dragCaretHandle(EditorView view, int caret) {
        view.getSession().setSelection(caret);
        view.handlesVisible = true;
        float[] pos = view.caretScreenPos(caret);
        float density = view.getResources().getDisplayMetrics().density;
        float hx = pos[0];
        float hy = pos[1] + view.metrics.getLineHeight()
                + view.HANDLE_RADIUS_DP * density * 0.6f;
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, hx, hy);
        assertEquals(3, view.handleDragMode);
        send(view, MotionEvent.ACTION_MOVE, down, down + 40, hx + 30f, hy);
        assertTrue("la loupe doit être active au premier MOVE",
                view.magnifierActive);
    }

    @Test
    public void drag_de_la_poignee_caret_barre_visible_dans_la_bullee() {
        EditorView view = newView();
        dragCaretHandle(view, DOC.indexOf("greet"));
        // Le clignotement du caret est à cheval sur l'heure d'exécution
        // (phase on/off 50/50 au repos) : marquer « récemment déplacé »
        // pour une phase pleine déterministe — comme le fait tout geste
        // réel via EditorInputHandler → onEditOrMove().
        view.caretAnim.onEditOrMove();

        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);

        int caretColor = view.theme.caret;
        int bars = 0;
        for (LineEvent l : canvas.lines) {
            if (l.color == caretColor && l.isVerticalBar(LINE_H)) bars++;
        }
        assertTrue("la barre de caret doit être visible DANS la loupe"
                + " (barres pleine ligne couleur caret trouvées : " + bars
                + " — 1 = principal seul, état d'avant M1 ; 2 = principal"
                + " + bulle)",
                bars >= 2);
    }

    @Test
    public void sans_loupe_une_seule_barre_caret() {
        // Contre-preuve : hors drag (pas de loupe), une seule barre —
        // le second comptage du test précédent vient bien de la bulle.
        EditorView view = newView();
        view.getSession().setSelection(DOC.indexOf("greet"));
        view.handlesVisible = true;
        view.caretAnim.onEditOrMove();

        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);

        int caretColor = view.theme.caret;
        int bars = 0;
        for (LineEvent l : canvas.lines) {
            if (l.color == caretColor && l.isVerticalBar(LINE_H)) bars++;
        }
        assertEquals(1, bars);
    }
}
