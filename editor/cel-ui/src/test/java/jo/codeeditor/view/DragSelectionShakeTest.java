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
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * B9 (retour terrain n°6) — verrouille la MORT de la « secousse » des
 * lignes pendant un drag de sélection.
 *
 * <p><b>Cause racine reproduite</b> : chaque application de sélection du
 * drag déclenchait {@code EditorImeBridge.onSelectionChanged →
 * scrollCaretIntoView()} — qui TIRAIT le viewport vers sel.start (l'ancre
 * du drag, une ligne du HAUT de l'écran) — pendant que l'auto-scroll UX-2
 * du drag le POUSSAIT vers le bas. Dès que vOffset dépassait le Y de
 * l'ancre, le recentrage le ramenait EN ARRIÈRE ({@code vOffset = anchorY
 * - lineHeight}) : le viewport oscillait en DENT DE SCIE entre
 * {@code anchorY - lh} et {@code anchorY}, au rythme des ticks — tout le
 * texte « secouait » verticalement.</p>
 *
 * <p><b>Contrat</b> : pendant qu'un drag de sélection est actif
 * ({@code EditorView.isSelectionDragActive()}), le recentrage du caret
 * est SUSPENDU — le geste et son auto-scroll dirigent seuls le viewport,
 * vOffset progresse de façon strictement MONOTONE. Au repos du geste, le
 * recentrage reprend (une sélection programmatique recentre à nouveau).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class DragSelectionShakeTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;
    private static final int N_LINES = 80;

    private static String doc() {
        StringBuilder sb = new StringBuilder();
        sb.append("public class Main {\n")     // 0
          .append("    void run() {\n")         // 1
          .append("        int count = 0;\n"); // 2
        for (int i = 0; i < N_LINES - 5; i++) {
            sb.append("        int filler").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("    }\n").append("}\n");
        return sb.toString();
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc()));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    private static float lineCenterY(EditorView view, int line) {
        return view.docLineToY(line) - view.scroll.vOffset + LINE_H * 0.5f;
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

    private static int lineOfOffset(String doc, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < doc.length(); i++) {
            if (doc.charAt(i) == '\n') line++;
        }
        return line;
    }

    @Test
    public void dragSelect_auBordBas_vOffsetMonotonePasDeDentDeScie() {
        EditorView view = newView();
        String text = doc();
        float x = view.metrics.getGutterWidth() + view.metrics.getPadLeft() + 12 * CHAR_W;

        // Appui long sur « count » (ligne 2, ANCRE près du HAUT de
        // l'écran) — la configuration exacte de la secousse rapportée :
        // l'ancre du drag monte hors du viewport dès que l'auto-scroll
        // défile, et l'ancien recentrage la ramenait en arrière.
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, x, lineCenterY(view, 2));
        SystemClock.sleep(600);
        idle();
        assertEquals("count", text.substring(view.getSession().getSelection().start,
                view.getSession().getSelection().end));

        // Glissement au-delà du slop vers le BAS, doigt tenu à 20 px du
        // bord (zone 56 px) : l'auto-scroll UX-2 s'engage.
        float edgeY = VIEW_H - 20f;
        long now = SystemClock.uptimeMillis();
        for (int i = 1; i <= 6; i++) {
            float y = lineCenterY(view, 2) + (edgeY - lineCenterY(view, 2)) * i / 6f;
            send(view, MotionEvent.ACTION_MOVE, down, now + i * 20, x, y);
            SystemClock.sleep(20);
            idle();
        }

        assertTrue("le drag-select est actif (gate du recentrage fermée)",
                view.isSelectionDragActive());

        // 30 ticks d'auto-scroll, vOffset échantillonné à CHAQUE tick :
        // il doit croître de façon STRICTEMENT monotone. Sans le correctif
        // B9, le recentrage tirait vOffset en arrière dès qu'il dépassait
        // le Y de l'ancre (≈ ligne 2, Y contenu 100) → dent de scie
        // 100 → 60 → 68 → … → 100 → 60 : la secousse.
        float prev = view.scroll.vOffset;
        int regressions = 0;
        for (int i = 0; i < 30; i++) {
            SystemClock.sleep(33);
            idle();
            if (view.scroll.vOffset < prev - 0.001f) {
                regressions++;
            }
            prev = view.scroll.vOffset;
        }
        assertEquals("vOffset doit être monotone pendant le drag (le recentrage "
                + "est suspendu — " + regressions + " retours en arrière observés, "
                + "vOffset final " + view.scroll.vOffset + ")",
                0, regressions);
        assertTrue("l'auto-scroll a bien défilé (vOffset=" + view.scroll.vOffset + ")",
                view.scroll.vOffset > 0f);

        // La sélection s'est étendue vers le bas pendant ce temps.
        Selection sel = view.getSession().getSelection();
        assertTrue("la sélection s'étend au-delà de la ligne 2",
                lineOfOffset(text, Math.max(sel.start, sel.end)) > 20);

        // Fin du geste.
        send(view, MotionEvent.ACTION_UP, down, SystemClock.uptimeMillis(), x, edgeY);
        assertEquals("fin de geste : la gate du recentrage se rouvre",
                false, view.isSelectionDragActive());
    }

    @Test
    public void aprèsLeGeste_leRecentrageReprend() {
        EditorView view = newView();
        String text = doc();
        int start = text.indexOf("count");

        // Sélection programmatique d'une ligne SOUS le viewport (ligne
        // 60+ → Y contenu 2400+ > 1920) : le recentrage (via
        // onSelectionChanged) doit tirer le viewport vers elle — la gate
        // ne vit QUE pendant les drags.
        int low = text.indexOf("filler60");
        view.getSession().setSelection(Selection.range(low, low + 8));
        assertTrue("une sélection programmatique sous le viewport recentre "
                + "(vOffset=" + view.scroll.vOffset + ", attendu > 0)",
                view.scroll.vOffset > 0f);
        assertTrue(start >= 0); // sanity du document
    }
}
