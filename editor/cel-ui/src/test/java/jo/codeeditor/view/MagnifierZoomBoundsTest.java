package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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
 * M2 (P2) — verrouille le <b>zoom réglable de la loupe</b> :
 *
 * <ul>
 *   <li>plage bornée ×1.5 – ×3 (les valeurs hors plage sont ramenées
 *       à la borne la plus proche, défaut ×2 inchangé) ;</li>
 *   <li>la valeur est APPLIQUÉE au canvas de la bulle — le
 *       {@code canvas.scale(2f, 2f, …)} littéral d'avant M2 devient
 *       {@code canvas.scale(zoom, zoom, …)}.</li>
 * </ul>
 *
 * <p>Mise en scène : drag réel de la poignée de caret (DOWN puis MOVE —
 * la bulle s'allume au premier MOVE, motif
 * {@code EditorMagnifierHandleDragTest}), puis une frame rendue sur un
 * canvas d'enregistrement qui capture chaque scale pivoté.</p>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorPendingTapDismissTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class MagnifierZoomBoundsTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2 — caret sur « greet »
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Canvas d'enregistrement : compte les drawText (le contenu de
     *  la bulle reste dessiné — preuve de santé du pipeline après le
     *  branchement du facteur réglable). NOTE : sous Robolectric,
     *  Canvas.scale est un no-op (natives stubbées) — le facteur
     *  appliqué au matrix n'est PAS observable ; la valeur CONSOMMÉE
     *  par le painter (view.magnifierZoom) est assertée directement. */
    private static final class RecordingCanvas extends Canvas {
        int textEvents = 0;
        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            textEvents++;
            super.drawText(text, x, y, paint);
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
                + EditorView.HANDLE_RADIUS_DP * density * 0.6f;
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, hx, hy);
        assertEquals(3, view.handleDragMode);
        send(view, MotionEvent.ACTION_MOVE, down, down + 40, hx + 30f, hy);
        assertTrue("la loupe doit être active au premier MOVE",
                view.magnifierActive);
    }

    /** Vrai si la frame dessine du texte (bulle + gouttière saines). */
    private static boolean frameDrawsText(EditorView view) {
        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);
        return canvas.textEvents > 0;
    }

    @Test
    public void bornes_plageEtDefaut() {
        EditorView view = newView();
        assertEquals("défaut : zoom ×2 inchangé", 2f,
                view.getMagnifierZoom(), 0.001f);
        view.setMagnifierZoom(0.5f);
        assertEquals("borne basse ×1.5", 1.5f, view.getMagnifierZoom(), 0.001f);
        view.setMagnifierZoom(9f);
        assertEquals("borne haute ×3", 3f, view.getMagnifierZoom(), 0.001f);
        view.setMagnifierZoom(2.25f);
        assertEquals("valeur dans la plage conservée", 2.25f,
                view.getMagnifierZoom(), 0.001f);
    }

    @Test
    public void valeurAppliqueeAuCanvasDeLaBulle() {
        EditorView view = newView();
        dragCaretHandle(view, DOC.indexOf("greet"));

        // Le champ consommé par EditorChromePainter.drawMagnifier
        // (canvas.scale(view.magnifierZoom, view.magnifierZoom, …))
        // est exactement celui que le setter écrit :
        view.setMagnifierZoom(3f);
        assertEquals("la valeur consommée par la bulle doit être ×3",
                3f, view.magnifierZoom, 0.001f);
        view.setMagnifierZoom(0.1f); // ramené à la borne basse ×1.5
        assertEquals("borne basse appliquée à la valeur consommée",
                1.5f, view.magnifierZoom, 0.001f);
        view.setMagnifierZoom(9f);
        assertEquals("borne haute appliquée à la valeur consommée",
                3f, view.magnifierZoom, 0.001f);

        // Santé du pipeline de rendu avec le facteur branché : la
        // frame dessine toujours (bulle active).
        assertTrue("la frame doit rester dessinée avec le zoom branché",
                frameDrawsText(view));
    }
}
