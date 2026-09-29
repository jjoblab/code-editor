package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import org.robolectric.Shadows;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Régression du bug signalé : « quand j'appuie sur l'éditeur ça bug,
 * plusieurs lignes ou mots sont sélectionnés alors que j'ai seulement
 * appuyé ».
 *
 * <p>Cause racine (lecture croisée, confirmée par le modèle sora-editor) :
 * chaque tap simple armait le drag-select ({@code armDragSelect()} au
 * cas 1 de {@code EditorTapResolver.handleTap}) et l'armement survivait
 * au geste suivant ; la branche MOVE engageait alors le drag-select dès
 * le PREMIER pixel de jitter — sous le touch-slop de 24 px, seuil que le
 * doigt franchit quasi systématiquement à chaque tap réel. Résultat : le
 * tap N+1 sélectionnait la plage entre le caret du tap N et le doigt
 * (plusieurs mots sur la même ligne, plusieurs lignes en dessous).</p>
 *
 * <p>Modèle sora ({@code EditorTouchEventHandler}) : le tap pose le caret
 * — point final ; le drag-select au doigt est armé par l'APPUI LONG
 * ({@code dragSelectAfterLongPress}) ; le touch-slop sépare strictement
 * tap et drag ; tout DOWN désarme ({@code finishDragSelect}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SingleTapNoSelectionTest {

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
     *  donné (offsetAt arrondit : viser le centre donnerait le caractère
     *  suivant). */
    private float[] midCharPos(EditorView view, int offset) {
        float[] pos = view.caretScreenPos(offset);
        return new float[]{pos[0] + CHAR_W * 0.25f, pos[1] + LINE_H / 2f};
    }

    /**
     * LE bug signalé : tap, puis re-tap AVEC jitter sous le slop — le
     * second tap doit poser le caret, jamais sélectionner depuis le caret
     * du tap précédent.
     */
    @Test
    public void tapAfterTap_withSubSlopJitter_placesCaretOnly() throws Exception {
        EditorView view = newView();
        int c = DOC.indexOf("name");

        // 1. Premier tap : pose le caret sur « name ».
        float[] pc = midCharPos(view, c);
        long t0 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, t0, t0, pc[0], pc[1]);
        send(view, MotionEvent.ACTION_UP, t0, t0 + 40, pc[0], pc[1]);
        assertTrue(view.getSession().getSelection().isCursor());
        assertEquals(c, view.getSession().getSelection().start);

        // 2. Second tap PLUS DE 280 ms plus tard (fenêtre multi-tap réelle
        //    écoulée — même espacement que EditorSelectionToolbarTest), avec
        //    le jitter naturel du doigt : +2 caractères = 20 px < slop 24 px.
        int c2 = c + 2;
        float[] pc2 = midCharPos(view, c2);
        long t1 = t0 + 400;
        Thread.sleep(320); // horloge réelle du comptage multi-tap
        send(view, MotionEvent.ACTION_DOWN, t1, t1, pc[0], pc[1]);
        send(view, MotionEvent.ACTION_MOVE, t1, t1 + 30, pc2[0], pc2[1]);
        Selection mid = view.getSession().getSelection();
        assertTrue("le jitter sous le slop ne doit PAS créer de sélection",
                mid.isCursor());
        send(view, MotionEvent.ACTION_UP, t1, t1 + 60, pc2[0], pc2[1]);

        Selection after = view.getSession().getSelection();
        assertTrue("le second tap doit poser un caret, pas une sélection ["
                + after.start + "," + after.end + "]",
                after.isCursor());
        assertEquals("le caret doit suivre le doigt du second tap",
                c2, after.start);
    }

    /**
     * Variante « plusieurs lignes » : tap sur une ligne, puis tap avec
     * jitter sur une AUTRE ligne — aucune sélection multi-lignes.
     */
    @Test
    public void tapOnOtherLine_withJitter_neverSelectsAcrossLines() throws Exception {
        EditorView view = newView();
        int greet = DOC.indexOf("greet");
        int closing = DOC.indexOf("}", DOC.indexOf("run()"));  // « } » ligne 4

        // Tap 1 sur « greet » (ligne 2).
        float[] pg = midCharPos(view, greet);
        long t0 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, t0, t0, pg[0], pg[1]);
        send(view, MotionEvent.ACTION_UP, t0, t0 + 40, pg[0], pg[1]);

        // Tap 2 sur le « } » de la ligne 4 (> 48 px plus bas : hors slop
        // multi-tap), avec jitter de 2 caractères SOUS le slop du doigt.
        // Fenêtre multi-tap réelle écoulée (Thread.sleep, parité avec les
        // tests de toolbar).
        float[] pc = midCharPos(view, closing);
        long t1 = t0 + 400;
        Thread.sleep(320);
        send(view, MotionEvent.ACTION_DOWN, t1, t1, pc[0], pc[1]);
        send(view, MotionEvent.ACTION_MOVE, t1, t1 + 30,
                pc[0] + 2 * CHAR_W, pc[1] + 3f);
        send(view, MotionEvent.ACTION_UP, t1, t1 + 60,
                pc[0] + 2 * CHAR_W, pc[1] + 3f);

        Selection after = view.getSession().getSelection();
        assertTrue("un simple appui ne doit JAMAIS sélectionner plusieurs lignes ["
                + after.start + "," + after.end + "]",
                after.isCursor());
        // Le caret doit rester sur la ligne tapée (le « } » de la ligne 3,
        // clippé à la fin de ligne par offsetAt) — pas de sélection.
        assertTrue("le caret doit être posé sur la ligne du « } » tapé (got "
                + after.start + ")",
                after.start >= closing && after.start <= closing + 1);
    }

    /**
     * Le tap ne laisse AUCUN armement derrière lui : un geste complet
     * DOWN → jitter → UP ne doit laisser qu'un caret, et un geste suivant
     * au-delà du slop doit SCROLLER (pas créer de sélection).
     */
    @Test
    public void noLingeringArmamentAfterTap_beyondSlopMovesScroll() {
        EditorView view = newView();
        int c = DOC.indexOf("name");
        float[] pc = midCharPos(view, c);

        long t0 = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, t0, t0, pc[0], pc[1]);
        send(view, MotionEvent.ACTION_UP, t0, t0 + 40, pc[0], pc[1]);
        assertTrue(view.getSession().getSelection().isCursor());

        // Geste suivant : glissement FRANC au-delà du slop (80 px) — doit
        // être interprété comme un scroll, pas comme un drag-select : la
        // sélection reste le caret posé par le tap.
        long t1 = t0 + 400;
        send(view, MotionEvent.ACTION_DOWN, t1, t1, pc[0], pc[1]);
        send(view, MotionEvent.ACTION_MOVE, t1, t1 + 30, pc[0], pc[1] + 80f);
        send(view, MotionEvent.ACTION_UP, t1, t1 + 80, pc[0], pc[1] + 80f);

        Selection after = view.getSession().getSelection();
        assertTrue("un swipe après un tap doit défiler, pas sélectionner ["
                + after.start + "," + after.end + "]",
                after.isCursor());
        // Le looper principal n'a rien de dû — pure hygiène.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
