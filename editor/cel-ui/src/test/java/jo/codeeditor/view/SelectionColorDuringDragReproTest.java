package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
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
 * Reproduction du bug signalé : « dès que le drag commence, les
 * caractères ou mots sélectionnés n'ont plus de couleur ».
 *
 * <p>Protocole : simuler l'appui long (sélection de mot + poignées),
 * dessiner une frame et compter les pixels de COULEUR DE SÉLECTION dans
 * la bande du mot — puis engager le drag (drag-select au doigt ET drag
 * de poignée), redessiner et recompter. La bande de sélection doit rester
 * peinte PENDANT le drag, pas seulement après le relâchement.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class SelectionColorDuringDragReproTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int W = 1080;
    private static final int H = 1920;
    /** L'appui long système par défaut est de 400 ms ; marge de 250 ms. */
    private static final long LONG_PRESS_WAIT_MS = 650;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(W, H);
        view.layout(0, 0, W, H);
        injectMetrics(view, LINE_H, CHAR_W);
        // Premier dessin de chauffe (styling async → styledLines prêtes).
        Bitmap warm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(warm));
        warm.recycle();
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

    private void longPress(EditorView view, float x, float y, long t0) {
        send(view, MotionEvent.ACTION_DOWN, t0, t0, x, y);
        SystemClock.sleep(LONG_PRESS_WAIT_MS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Coordonnées écran visant le QUART GAUCHE du caractère à l'offset. */
    private float[] midCharPos(EditorView view, int offset) {
        float[] pos = view.caretScreenPos(offset);
        return new float[]{pos[0] + CHAR_W * 0.25f, pos[1] + LINE_H / 2f};
    }

    /** Compte les pixels exactement à la couleur de sélection dans le rect. */
    private static int countSelectionPixels(Bitmap bmp, int x1, int y1, int x2, int y2,
                                            int selColor) {
        int count = 0;
        for (int y = Math.max(0, y1); y < Math.min(bmp.getHeight(), y2); y++) {
            for (int x = Math.max(0, x1); x < Math.min(bmp.getWidth(), x2); x++) {
                if (bmp.getPixel(x, y) == selColor) count++;
            }
        }
        return count;
    }

    /** Rect écran de la bande de sélection (zone du mot « name »). */
    private int[] selectionBandRect(EditorView view) {
        Selection sel = view.getSession().getSelection();
        int s = Math.min(sel.start, sel.end);
        int e = Math.max(sel.start, sel.end);
        float[] pStart = view.caretScreenPos(s);
        float[] pEnd = view.caretScreenPos(e);
        int x1 = (int) Math.min(pStart[0], pEnd[0]) - 2;
        int x2 = (int) Math.max(pStart[0], pEnd[0]) + 2;
        int y1 = (int) pStart[1] - 2;
        int y2 = (int) (pStart[1] + view.metrics.getLineHeight()) + 2;
        return new int[]{x1, y1, x2, y2};
    }

    private int countBandPixels(EditorView view, Bitmap bmp) {
        int[] r = selectionBandRect(view);
        return countSelectionPixels(bmp, r[0], r[1], r[2], r[3], view.theme.selection);
    }

    // ── Reproduction ─────────────────────────────────────────────────

    @Test
    public void longPress_wordBandIsPainted() {
        EditorView view = newView();
        int c = DOC.indexOf("name");
        float[] pc = midCharPos(view, c);
        long t0 = SystemClock.uptimeMillis();
        longPress(view, pc[0], pc[1], t0);

        Selection sel = view.getSession().getSelection();
        assertTrue("l'appui long doit sélectionner le mot", sel.start < sel.end);

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        int n = countBandPixels(view, bmp);
        bmp.recycle();
        assertTrue("après appui long la bande de sélection doit être peinte"
                + " (pixels trouvés=" + n + ")", n > 0);
    }

    @Test
    public void dragSelect_whileDragging_bandStaysPainted() {
        EditorView view = newView();
        int c = DOC.indexOf("name");
        float[] pc = midCharPos(view, c);
        long t0 = SystemClock.uptimeMillis();
        longPress(view, pc[0], pc[1], t0);
        assertTrue(view.getSession().getSelection().start
                < view.getSession().getSelection().end);

        // Drag au-delà du slop vers la droite.
        float[] pr = midCharPos(view, c + 4);
        send(view, MotionEvent.ACTION_MOVE, t0, SystemClock.uptimeMillis(), pr[0], pr[1]);
        Selection s1 = view.getSession().getSelection();
        assertTrue("le drag doit maintenir une sélection [" + s1.start + ","
                + s1.end + "]", s1.start < s1.end);

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        int n = countBandPixels(view, bmp);
        bmp.recycle();
        assertTrue("PENDANT le drag-select la bande de sélection doit rester"
                + " peinte (pixels trouvés=" + n + ")", n > 0);
    }

    @Test
    public void handleDrag_whileDragging_bandStaysPainted() {
        EditorView view = newView();
        int c = DOC.indexOf("name");
        float[] pc = midCharPos(view, c);
        long t0 = SystemClock.uptimeMillis();
        longPress(view, pc[0], pc[1], t0);
        Selection word = view.getSession().getSelection();
        assertTrue(word.start < word.end);

        // UP puis DOWN sur la poignée de fin (mode 2) puis drag.
        send(view, MotionEvent.ACTION_UP, t0, SystemClock.uptimeMillis(), pc[0], pc[1]);
        Selection afterUp = view.getSession().getSelection();
        assertEquals("l'UP d'un appui long garde la sélection du mot", word, afterUp);

        float density = view.getResources().getDisplayMetrics().density;
        float[] hp = view.caretScreenPos(afterUp.end);
        float hx = hp[0];
        float hy = hp[1] + view.metrics.getLineHeight()
                + view.HANDLE_RADIUS_DP * density * 0.6f;
        long t1 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, t1, t1, hx, hy);
        assertEquals("le DOWN doit attraper la poignée de fin", 2, view.handleDragMode);

        float[] target = midCharPos(view, c + 6);
        send(view, MotionEvent.ACTION_MOVE, t1, SystemClock.uptimeMillis(),
                target[0] + 40, target[1]);
        Selection s1 = view.getSession().getSelection();
        assertTrue("le drag de poignée doit maintenir une sélection ["
                + s1.start + "," + s1.end + "]", s1.start < s1.end);

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        int n = countBandPixels(view, bmp);
        bmp.recycle();
        assertTrue("PENDANT le drag de poignée la bande de sélection doit"
                + " rester peinte (pixels trouvés=" + n + ")", n > 0);
    }

    // ── Bug signalé en mode wrap (config du demo : setWordWrap(true)) ──

    /** Document d'une longue ligne unique — comme l'échantillon
     *  « Minifié (JS) » du module demo. */
    private static final String LONG_LINE_DOC =
        "var alpha=" + repeat("x", 120) + " beta " + repeat("y", 120)
        + " gamma " + repeat("z", 60) + ";\n";

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(n * s.length());
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    /** Vue en mode wrap (1080 px de large, ~100 colonnes/rangée). */
    private EditorView newWrappedView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(LONG_LINE_DOC));
        view.setSession(session);
        view.setWordWrap(true);
        view.measure(W, H);
        view.layout(0, 0, W, H);
        injectMetrics(view, LINE_H, CHAR_W);
        // Piège documenté (AGENT.md) : l'injection de métriques par
        // réflexion invalide le modèle de wrap construit au layout avec
        // les métriques réelles — le reconstruire avec les métriques de test.
        view.rebuildWrapModel();
        Bitmap warm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(warm));
        warm.recycle();
        return view;
    }

    @Test
    public void wrap_wordOnSecondRow_selectionBandVisibleAtLongPress() {
        EditorView view = newWrappedView();
        // « beta » est à la colonne ~131 — rangée 2 (cap ≈ 100 colonnes).
        String line = view.getSession().getDocument().lineText(0);
        int col = line.indexOf("beta");
        assertTrue("le mot de test doit exister", col >= 0);
        int off = view.getSession().getDocument().lineStart(0) + col;

        // Le caret/handles SONT wrap-aware : le mot se dessine sur la
        // rangée 2, dans le viewport.
        float[] pos = view.caretScreenPos(off);
        assertTrue("le mot doit être sur une rangée visible du viewport"
                + " (y=" + pos[1] + ")", pos[1] >= 0 && pos[1] < H);

        // Appui long au CENTRE du mot (offsetAt arrondit : viser le milieu
        // du 2e caractère garantit de tomber dans le mot).
        float[] pc = new float[]{pos[0] + CHAR_W * 1.25f, pos[1] + LINE_H / 2f};
        long t0 = SystemClock.uptimeMillis();
        longPress(view, pc[0], pc[1], t0);
        Selection sel = view.getSession().getSelection();
        assertTrue("l'appui long doit sélectionner « beta » (sel=" + sel.start
                + ".." + sel.end + ")",
                sel.start <= off && sel.end >= off + "beta".length());

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        // La bande doit couvrir le mot À SA PLACE wrap-aware (rangée 2).
        int x1 = (int) pos[0] - 2;
        int y1 = (int) pos[1] - 2;
        int y2 = (int) (pos[1] + LINE_H) + 2;
        int n = countSelectionPixels(bmp, x1, y1, x1 + (int) ("beta".length() * CHAR_W) + 4,
                y2, view.theme.selection);
        bmp.recycle();
        assertTrue("en mode wrap, la bande de sélection du mot (rangée 2)"
                + " doit être peinte à la position VISIBLE du mot"
                + " (pixels trouvés=" + n + ")", n > 0);
    }

    @Test
    public void wrap_dragAcrossRows_everyRowShowsBand() {
        EditorView view = newWrappedView();
        String line = view.getSession().getDocument().lineText(0);
        int colA = line.indexOf("beta");
        int colB = line.indexOf("gamma");
        assertTrue(colA >= 0 && colB > colA);
        EditorDocument doc = view.getSession().getDocument();
        int offA = doc.lineStart(0) + colA;
        int offB = doc.lineStart(0) + colB + "gamma".length();

        // Sélection multi-rangées posée programmatiquement (état final du
        // drag), puis dessin : chaque rangée couverte doit montrer la bande.
        view.getSession().setSelection(Selection.range(offA, offB));

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));

        // Sur CHAQUE rangée couverte par la sélection, il doit y avoir des
        // pixels de la couleur de sélection.
        int rows = view.rowsForDocLine(0);
        int totalRowsWithBand = 0;
        for (int r = 0; r < rows; r++) {
            float rowY = view.docLineToY(0) - view.vOffset + r * LINE_H;
            if (rowY < 0 || rowY + LINE_H > H) continue;
            int n = countSelectionPixels(bmp, 0, (int) rowY, W,
                    (int) (rowY + LINE_H), view.theme.selection);
            if (n > 0) totalRowsWithBand++;
        }
        bmp.recycle();
        assertTrue("la sélection couvre des colonnes des rangées 1.." + (rows - 1)
                + " — chaque rangée couverte doit être peinte (rangées peintes="
                + totalRowsWithBand + ")", totalRowsWithBand >= rows - 1);
    }
}
