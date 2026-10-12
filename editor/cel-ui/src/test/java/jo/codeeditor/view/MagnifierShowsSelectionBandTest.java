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
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * M1 (P1) — verrouille la <b>bande de sélection et le squiggle dans
 * la loupe</b> :
 *
 * <ul>
 *   <li><b>Bande de sélection</b> — le rect de la plage sélectionnée
 *       sur la ligne, sous le texte, même peinture que le pipeline
 *       principal : deux bandes pleine ligne couleur
 *       {@code theme.selection} dans la frame = la principale + celle
 *       redessinée dans la bulle ;</li>
 *   <li><b>Squiggle de diagnostic</b> — l'ondulé sous la plage
 *       diagnostiquée, même peinture qu'EditorDiagnosticsPainter :
 *       deux paths couleur {@code theme.error} = principal + bulle.</li>
 * </ul>
 *
 * <p>La loupe sert à régler la BORNE d'une sélection — l'objet de la
 * manœuvre doit être visible dans l'instrument de visée. Avant M1,
 * le contenu redessiné était le texte seul.</p>
 *
 * <p>Mise en scène : drag réel de la poignée de DÉBUT de sélection
 * (la bulle s'allume au premier MOVE, motif
 * {@code EditorMagnifierHandleDragTest}), frame rendue sur un canvas
 * d'enregistrement (couleur captée À L'INSTANT du draw — les events
 * de ShadowCanvas gardent la référence du paint muté).</p>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorPendingTapDismissTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class MagnifierShowsSelectionBandTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2 — « greet » cols 8..12
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** « greet » : [45, 50). */
    private static final int GREET = DOC.indexOf("greet");

    private static final class RectEvent {
        final float l, t, r, b;
        final int color;
        RectEvent(float l, float t, float r, float b, int color) {
            this.l = l; this.t = t; this.r = r; this.b = b;
            this.color = color;
        }
        boolean isBand(float lineHeight) {
            return Math.abs((b - t) - lineHeight) < 2f;
        }
    }

    private static final class PathEvent {
        final int color;
        PathEvent(int color) { this.color = color; }
    }

    /** Canvas d'enregistrement : capture rects et paths avec la
     *  couleur courante du paint à l'instant du draw. */
    private static final class RecordingCanvas extends Canvas {
        final List<RectEvent> rects = new ArrayList<>();
        final List<PathEvent> paths = new ArrayList<>();
        @Override
        public void drawRect(float left, float top, float right, float bottom, Paint paint) {
            rects.add(new RectEvent(left, top, right, bottom, paint.getColor()));
            super.drawRect(left, top, right, bottom, paint);
        }
        @Override
        public void drawPath(Path path, Paint paint) {
            paths.add(new PathEvent(paint.getColor()));
            super.drawPath(path, paint);
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

    /** Position écran de la poignée de DÉBUT de sélection. */
    private float[] startHandlePos(EditorView view) {
        Selection sel = view.getSession().getSelection();
        float[] pos = view.caretScreenPos(sel.start);
        float density = view.getResources().getDisplayMetrics().density;
        return new float[]{pos[0], pos[1] + view.metrics.getLineHeight()
                + view.HANDLE_RADIUS_DP * density * 0.6f};
    }

    /** Amorce un drag de la poignée de DÉBUT : DOWN puis MOVE — la
     *  bulle s'allume au premier MOVE. */
    private void dragStartHandle(EditorView view, int start, int end) {
        view.getSession().setSelection(Selection.range(start, end));
        view.handlesVisible = true;
        float[] handle = startHandlePos(view);
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, handle[0], handle[1]);
        assertEquals(1, view.handleDragMode);
        // MOVE au-delà du touch-slop (24 px) : la poignée ne bouge — et
        // la loupe ne s'allume — qu'après son franchissement.
        send(view, MotionEvent.ACTION_MOVE, down, down + 50, handle[0] + 40f, handle[1] + 12f);
        assertTrue("la loupe doit être active au premier MOVE",
                view.magnifierActive);
    }

    @Test
    public void drag_de_selection_bande_visible_dans_la_bullee() {
        EditorView view = newView();
        dragStartHandle(view, GREET, GREET + 5);

        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);

        int selColor = view.theme.selection;
        int bands = 0;
        for (RectEvent r : canvas.rects) {
            if (r.color == selColor && r.isBand(LINE_H)) bands++;
        }
        assertTrue("la bande de sélection doit être visible DANS la loupe"
                + " (bandes pleine ligne couleur sélection trouvées : " + bands
                + " — 1 = principale seule, état d'avant M1 ; 2 = principale"
                + " + bulle)",
                bands >= 2);
    }

    @Test
    public void squiggle_du_diagnostic_visible_dans_la_bullee() {
        EditorView view = newView();
        // Diagnostic d'erreur sur « greet » — même scène que le popup
        // D1 : l'ondulé rouge doit apparaître dans la bulle.
        view.getSession().setDiagnostics(Collections.singletonList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3,
                        "Cannot resolve method 'greet'")));
        dragStartHandle(view, GREET, GREET + 5);

        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);

        int errorColor = view.theme.error;
        int squiggles = 0;
        for (PathEvent p : canvas.paths) {
            if (p.color == errorColor) squiggles++;
        }
        assertTrue("le squiggle du diagnostic doit être visible DANS la"
                + " loupe (paths couleur erreur trouvés : " + squiggles
                + " — 1 = principal seul, état d'avant M1 ; 2 = principal"
                + " + bulle)",
                squiggles >= 2);
    }

    @Test
    public void sans_loupe_une_seule_bande() {
        // Contre-preuve : hors drag (pas de loupe), une seule bande —
        // le second comptage du test bande vient bien de la bulle.
        EditorView view = newView();
        view.getSession().setSelection(Selection.range(GREET, GREET + 5));
        view.handlesVisible = true;

        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);

        int selColor = view.theme.selection;
        int bands = 0;
        for (RectEvent r : canvas.rects) {
            if (r.color == selColor && r.isBand(LINE_H)) bands++;
        }
        assertEquals(1, bands);
    }
}
