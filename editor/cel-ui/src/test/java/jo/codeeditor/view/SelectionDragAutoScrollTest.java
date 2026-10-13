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
 * UX-2 (retour terrain J3) — verrouille l'<b>auto-scroll directionnel
 * pendant le drag de sélection</b> : le doigt tenu dans une zone de bord
 * (56 dp) fait défiler le document dans la direction de la pénétration,
 * et la sélection SUIT le contenu qui défile sous le doigt — les deux
 * chemins de drag (drag-select au doigt après appui long, traînée d'une
 * poignée de sélection).
 *
 * <ul>
 *   <li><b>Bord bas</b> — drag-select tenu près du bas → vOffset croît,
 *       la sélection s'étend sur des lignes de plus en plus basse ;</li>
 *   <li><b>Bord haut</b> — drag-select tenu près du haut avec le
 *       document déjà défilé → vOffset décroît (direction opposée) ;</li>
 *   <li><b>Poignée</b> — traînée de la poignée de fin tenue près du bas
 *       → vOffset croît, la borne de sélection suit ;</li>
 *   <li><b>Hors zone</b> — doigt au centre du viewport : AUCUN
 *       défilement automatique.</li>
 * </ul>
 *
 * <p>Horloge : le tick d'auto-scroll vit sur le handler du looper
 * principal (postDelayed ~16 ms, pattern tap-dismiss) — chaque itération
 * avance l'horloge fantôme puis vide la file.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SelectionDragAutoScrollTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;
    /** 80 lignes × 40 px = 3200 px de contenu > 1920 px de viewport. */
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

    /** Y écran du CENTRE de la ligne doc donnée. */
    private static float lineCenterY(EditorView view, int line) {
        return view.docLineToY(line) - view.vOffset + LINE_H * 0.5f;
    }

    /** DOWN + attente de l'appui long (le GestureDetector le déclenche
     *  après ~500 ms d'horloge fantôme). */
    private static void longPressDown(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        SystemClock.sleep(600);
        idle();
    }

    /** Un MOVE du geste en cours. */
    private static void move(EditorView view, float x, float y, int dt) {
        long now = SystemClock.uptimeMillis();
        MotionEvent m = MotionEvent.obtain(now - dt, now, MotionEvent.ACTION_MOVE, x, y, 0);
        view.onTouchEvent(m);
        m.recycle();
    }

    private static void up(EditorView view, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent u = MotionEvent.obtain(now - 60, now, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    /** Fait tourner le tick d'auto-scroll : avance l'horloge puis vide la
     *  file du looper (chaque tick se re-poste à +16 ms). */
    private static void runAutoScrollFrames(EditorView view, int frames) {
        for (int i = 0; i < frames; i++) {
            SystemClock.sleep(33);
            idle();
        }
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
    public void dragSelect_tenuAuBordBas_défileEtÉtendLaSélectionVersLeBas() {
        EditorView view = newView();
        String text = doc();
        float x = view.metrics.getGutterWidth() + view.metrics.getPadLeft() + 12 * CHAR_W;

        // Appui long sur « count » (ligne 2) → mot sélectionné, drag armé.
        longPressDown(view, x, lineCenterY(view, 2));
        assertEquals("le mot est sélectionné par l'appui long",
                "count", text.substring(view.getSession().getSelection().start,
                        view.getSession().getSelection().end));

        // Glissement au-delà du slop vers le BAS, doigt tenu à 20 px du
        // bord (dans la zone 56 px).
        float edgeY = VIEW_H - 20f;
        for (int i = 1; i <= 6; i++) {
            float y = lineCenterY(view, 2) + (edgeY - lineCenterY(view, 2)) * i / 6f;
            move(view, x, y, 20);
            SystemClock.sleep(20);
        }
        assertEquals(0f, view.vOffset, 0f);
        runAutoScrollFrames(view, 30);

        assertTrue("l'auto-scroll a défilé le document vers le bas (vOffset="
                + view.vOffset + ")",
                view.vOffset > 0f);
        Selection sel = view.getSession().getSelection();
        int endLine = lineOfOffset(text, Math.max(sel.start, sel.end));
        assertTrue("la sélection s'est étendue bien au-delà de la ligne 2"
                + " (fin ligne " + endLine + ")",
                endLine > 20);
        up(view, x, edgeY);
    }

    @Test
    public void dragSelect_tenuAuBordHaut_défileVersLeHaut() {
        EditorView view = newView();
        float x = view.metrics.getGutterWidth() + view.metrics.getPadLeft() + 12 * CHAR_W;

        // Document déjà défilé (l'appui long se fait sur une ligne visible
        // après ce défilement).
        view.vOffset = 800f;
        longPressDown(view, x, lineCenterY(view, 30));

        // Doigt tenu à 10 px du bord HAUT.
        float edgeY = 10f;
        float from = lineCenterY(view, 30);
        for (int i = 1; i <= 6; i++) {
            float y = from + (edgeY - from) * i / 6f;
            move(view, x, y, 20);
            SystemClock.sleep(20);
        }
        float before = view.vOffset;
        runAutoScrollFrames(view, 30);

        assertTrue("l'auto-scroll remonte le document (vOffset " + before
                + " → " + view.vOffset + ")",
                view.vOffset < before);
        up(view, x, edgeY);
    }

    @Test
    public void dragSelect_auCentre_aucunDéfilement() {
        EditorView view = newView();
        float x = view.metrics.getGutterWidth() + view.metrics.getPadLeft() + 12 * CHAR_W;
        longPressDown(view, x, lineCenterY(view, 2));

        // Doigt au CENTRE du viewport (hors des zones de bord).
        float midY = VIEW_H * 0.5f;
        for (int i = 1; i <= 6; i++) {
            float y = lineCenterY(view, 2) + (midY - lineCenterY(view, 2)) * i / 6f;
            move(view, x, y, 20);
            SystemClock.sleep(20);
        }
        runAutoScrollFrames(view, 20);

        assertEquals("aucun auto-scroll hors zone de bord", 0f, view.vOffset, 0f);
        up(view, x, midY);
    }

    @Test
    public void poignée_tenueAuBordBas_défileEtÉtendLaSélection() {
        EditorView view = newView();
        String text = doc();
        int start = text.indexOf("count");
        view.getSession().setSelection(Selection.range(start, start + 5));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;

        // Position de la POIGNÉE DE FIN (même géométrie que hitTestHandle).
        float[] pos = view.caretScreenPos(start + 5);
        float density = view.getResources().getDisplayMetrics().density;
        float hx = pos[0];
        float hy = pos[1] + view.metrics.getLineHeight()
                + view.HANDLE_RADIUS_DP * density * 0.6f;

        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, hx, hy, 0);
        view.onTouchEvent(d);
        d.recycle();
        assertEquals("la poignée de fin est attrapée", 2, view.handleDragMode);

        // Traînée au-delà du slop vers le bord bas, doigt tenu à 20 px.
        float edgeY = VIEW_H - 20f;
        for (int i = 1; i <= 8; i++) {
            float y = hy + (edgeY - hy) * i / 8f;
            move(view, hx, y, 20);
            SystemClock.sleep(20);
        }
        runAutoScrollFrames(view, 30);

        assertTrue("l'auto-scroll a défilé pendant la traînée de poignée"
                + " (vOffset=" + view.vOffset + ")",
                view.vOffset > 0f);
        Selection sel = view.getSession().getSelection();
        int endLine = lineOfOffset(text, Math.max(sel.start, sel.end));
        assertTrue("la borne de sélection suit le défilement (fin ligne "
                + endLine + ")",
                endLine > 20);
        up(view, hx, edgeY);
        assertEquals(0, view.handleDragMode);
    }

    // ════════════════════════════════════════════════════════════════
    // B7 (retour terrain n°4) — auto-scroll HORIZONTAL du drag : bords
    // gauche/droite, sélection qui suit, coin en diagonale, wrap mort.
    // ════════════════════════════════════════════════════════════════

    /** Ligne 2 longue (~267 colonnes) : « count » visible au départ, puis
     *  du remplissage bien au-delà de la fenêtre (~100 colonnes). Le
     *  document garde aussi assez de lignes pour défiler VERTICALEMENT
     *  (83 lignes × 40 px > viewport). */
    private static String docLongLine() {
        StringBuilder sb = new StringBuilder();
        sb.append("public class Main {\n")             // 0
          .append("    void run() {\n");               // 1
        sb.append("        int count = 0; // ");
        for (int i = 0; i < 30; i++) sb.append("abcdefgh");
        sb.append("\n");                               // 2 — longue
        for (int i = 0; i < 78; i++) {
            sb.append("        int filler").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("    }\n}\n");
        return sb.toString();
    }

    private EditorView newLongLineView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(docLongLine()));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    @Test
    public void dragSelect_tenuAuBordDroit_défileHorizontalementEtÉtend() {
        EditorView view = newLongLineView();
        String text = docLongLine();
        int line2Start = text.indexOf('\n', text.indexOf('\n') + 1) + 1;
        float y = lineCenterY(view, 2); // hors des zones de bord VERTICALES
        float x = view.metrics.getGutterWidth() + view.metrics.getPadLeft() + 12 * CHAR_W;

        // Appui long sur « count » (visible à hOffset = 0) → drag armé.
        longPressDown(view, x, y);
        assertEquals("le mot est sélectionné par l'appui long",
                "count", text.substring(view.getSession().getSelection().start,
                        view.getSession().getSelection().end));

        // Glissement au-delà du slop vers le bord DROIT, doigt tenu à
        // 20 px du bord (dans la zone 56 px).
        float edgeX = VIEW_W - 20f;
        for (int i = 1; i <= 6; i++) {
            float mx = x + (edgeX - x) * i / 6f;
            move(view, mx, y, 20);
            SystemClock.sleep(20);
        }
        assertEquals(0f, view.hOffset, 0f);
        runAutoScrollFrames(view, 30);

        assertTrue("l'auto-scroll horizontal a défilé vers la droite"
                + " (hOffset=" + view.hOffset + ")",
                view.hOffset > 0f);
        assertEquals("le doigt est verticalement au centre : AUCUN défilement vertical",
                0f, view.vOffset, 0f);
        Selection sel = view.getSession().getSelection();
        int selEndCol = Math.max(sel.start, sel.end) - line2Start;
        assertTrue("la sélection s'étend au-delà des colonnes visibles"
                + " (colonne fin " + selEndCol + ")",
                selEndCol > 100);
        up(view, edgeX, y);
    }

    @Test
    public void dragSelect_tenuAuBordGauche_reviensVersLaGauche() {
        EditorView view = newLongLineView();
        // Document déjà défilé à droite : seules les colonnes ≥ 60 sont
        // visibles — l'appui long se fait sur le remplissage visible.
        view.hOffset = 600f;
        float y = lineCenterY(view, 2);
        float x = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
                + (70 - 60) * CHAR_W; // colonne 70 à l'écran

        longPressDown(view, x, y);

        // Doigt tenu à 10 px du bord GAUCHE.
        for (int i = 1; i <= 6; i++) {
            float mx = x + (10f - x) * i / 6f;
            move(view, mx, y, 20);
            SystemClock.sleep(20);
        }
        float before = view.hOffset;
        runAutoScrollFrames(view, 30);

        assertTrue("l'auto-scroll ramène vers la gauche (hOffset " + before
                + " → " + view.hOffset + ")",
                view.hOffset < before);
        up(view, 10f, y);
    }

    @Test
    public void dragSelect_tenuAuCoinBasDroit_défileEnDiagonale() {
        EditorView view = newLongLineView();
        String text = docLongLine();
        int line2Start = text.indexOf('\n', text.indexOf('\n') + 1) + 1;
        float x = view.metrics.getGutterWidth() + view.metrics.getPadLeft() + 12 * CHAR_W;

        longPressDown(view, x, lineCenterY(view, 2));

        // Doigt tenu dans le COIN bas-droit : les DEUX zones de bord.
        float edgeX = VIEW_W - 20f;
        float edgeY = VIEW_H - 20f;
        for (int i = 1; i <= 8; i++) {
            float mx = x + (edgeX - x) * i / 8f;
            float my = lineCenterY(view, 2) + (edgeY - lineCenterY(view, 2)) * i / 8f;
            move(view, mx, my, 20);
            SystemClock.sleep(20);
        }
        runAutoScrollFrames(view, 30);

        assertTrue("le coin défile horizontalement (hOffset=" + view.hOffset + ")",
                view.hOffset > 0f);
        assertTrue("le coin défile verticalement (vOffset=" + view.vOffset + ")",
                view.vOffset > 0f);
        Selection sel = view.getSession().getSelection();
        int selEndCol = Math.max(sel.start, sel.end) - line2Start;
        assertTrue("la sélection suit la diagonale (colonne fin " + selEndCol + ")",
                selEndCol > 100);
        up(view, edgeX, edgeY);
    }

    @Test
    public void dragSelect_enModeWrap_aucunDéfilementHorizontal() {
        EditorView view = newLongLineView();
        view.setWordWrap(true);
        float y = lineCenterY(view, 2);
        float x = view.metrics.getGutterWidth() + view.metrics.getPadLeft() + 12 * CHAR_W;

        longPressDown(view, x, y);

        float edgeX = VIEW_W - 20f;
        for (int i = 1; i <= 6; i++) {
            float mx = x + (edgeX - x) * i / 6f;
            move(view, mx, y, 20);
            SystemClock.sleep(20);
        }
        runAutoScrollFrames(view, 20);

        assertEquals("en retour à la ligne, rien ne défile horizontalement",
                0f, view.hOffset, 0f);
        up(view, edgeX, y);
    }
}
