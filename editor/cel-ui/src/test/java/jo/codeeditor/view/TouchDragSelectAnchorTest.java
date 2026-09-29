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
import static org.junit.Assert.assertTrue;

/**
 * Drag-select au doigt — sémantique sora-editor (réécriture) : le
 * drag-select est armé par l'APPUI LONG (sora {@code beginDragSelect},
 * prop {@code dragSelectAfterLongPress}), pas par un tap.
 *
 * <p>L'appui long sélectionne le mot sous le doigt ; le glissement
 * ultérieur étend la sélection depuis la borne du mot OPPOSÉE au doigt
 * (sora : ancre = borne droite si le doigt est avant le point d'appui,
 * borne gauche sinon). La sélection contient donc toujours le mot
 * d'origine, se rétrécit en revenant en arrière et survit au relâchement
 * (l'UP d'un drag ne résout pas de tap).</p>
 *
 * <p>L'ancienne sémantique (« armé par le tap, glissement court SOUS le
 * slop ») était la cause du bug « j'ai seulement appuyé et plusieurs
 * lignes/mots se sont sélectionnés » — voir SingleTapNoSelectionTest.</p>
 *
 * @author jo@Dev
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
    /** L'appui long système par défaut est de 400 ms ; marge de 250 ms. */
    private static final long LONG_PRESS_WAIT_MS = 650;

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

    /** DOWN puis attente de l'appui long (horloge fantôme + idle looper —
     *  le GestureDetector poste son timer sur le looper principal). */
    private void longPress(EditorView view, float x, float y, long t0) {
        send(view, MotionEvent.ACTION_DOWN, t0, t0, x, y);
        SystemClock.sleep(LONG_PRESS_WAIT_MS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void longPressThenDrag_extendsFromWord_shrinksBackwards_survivesUp() {
        EditorView view = newView();
        int c = DOC.indexOf("name");
        int wordStart = c;
        int wordEnd = c + "name".length();  // borne exclusive

        // 1. Appui long sur « name » : le mot est sélectionné et le
        //    drag-select est armé (modèle sora).
        float[] pc = midCharPos(view, c);
        long t0 = SystemClock.uptimeMillis();
        longPress(view, pc[0], pc[1], t0);

        Selection word = view.getSession().getSelection();
        assertTrue("l'appui long doit sélectionner le mot",
                word.start < word.end);
        assertEquals(wordStart, word.start);
        assertEquals(wordEnd, word.end);

        // 2. Glissement au-delà du slop (40 px = 4 caractères) vers la
        //    DROITE du point d'appui : l'ancre est la borne GAUCHE du mot
        //    (règle sora) — la sélection s'étend du début du mot au doigt.
        int right = c + 4;
        float[] pr = midCharPos(view, right);
        send(view, MotionEvent.ACTION_MOVE, t0, SystemClock.uptimeMillis(),
                pr[0], pr[1]);
        Selection s1 = view.getSession().getSelection();
        assertTrue("le drag doit étendre la sélection [" + s1.start
                + "," + s1.end + "] depuis le mot", s1.start < s1.end);
        assertEquals("ancre = borne gauche du mot (doigt à droite de l'appui)",
                wordStart, s1.start);
        assertTrue("la borne mobile suit le doigt (≈" + right + ")",
                Math.abs(s1.end - right) <= 2);

        // 3. Retour en arrière qui FRANCHIT le point d'appui vers la
        //    GAUCHE : l'ancre bascule sur la borne DROITE du mot — la
        //    sélection va du doigt à la fin du mot (elle contient toujours
        //    le mot d'origine, sora anchorIndex).
        int left = c - 3;
        float[] pl = midCharPos(view, left);
        send(view, MotionEvent.ACTION_MOVE, t0, SystemClock.uptimeMillis(),
                pl[0], pl[1]);
        Selection s2 = view.getSession().getSelection();
        assertTrue("le franchissement doit donner une vraie plage [" + s2.start
                + "," + s2.end + "] contenant le mot",
                s2.start < s2.end);
        assertEquals("ancre = borne droite du mot (doigt à gauche de l'appui)",
                wordEnd, s2.end);
        assertTrue("la borne mobile suit le doigt (≈" + left + ")",
                Math.abs(s2.start - left) <= 2);

        // 4. UP : le geste a dragué — l'UP ne doit PAS résoudre un tap qui
        //    reposerait le caret et écraserait la sélection du drag.
        send(view, MotionEvent.ACTION_UP, t0, SystemClock.uptimeMillis(),
                pl[0], pl[1]);
        Selection s3 = view.getSession().getSelection();
        assertEquals("l'UP d'un drag ne doit pas écraser la sélection",
                s2, s3);
    }

    @Test
    public void longPress_releaseWithoutMove_keepsWordSelection_noTapResolution() {
        EditorView view = newView();
        int c = DOC.indexOf("name");

        // Appui long puis relâchement SANS mouvement : le mot reste
        // sélectionné (l'UP d'un appui long ne résout pas de tap).
        float[] pc = midCharPos(view, c);
        long t0 = SystemClock.uptimeMillis();
        longPress(view, pc[0], pc[1], t0);
        send(view, MotionEvent.ACTION_UP, t0, SystemClock.uptimeMillis(),
                pc[0], pc[1]);

        Selection after = view.getSession().getSelection();
        assertTrue("l'UP d'un appui long doit préserver la sélection du mot ["
                + after.start + "," + after.end + "]",
                after.start < after.end);
        assertEquals(c, after.start);
        assertEquals(c + "name".length(), after.end);
    }

    @Test
    public void nextGestureAfterDragSelectUp_isNotArmed() {
        EditorView view = newView();
        int c = DOC.indexOf("name");

        // Appui long + petit drag, puis UP → la sélection vit.
        float[] pc = midCharPos(view, c);
        long t0 = SystemClock.uptimeMillis();
        longPress(view, pc[0], pc[1], t0);
        float[] pr = midCharPos(view, c + 4);
        send(view, MotionEvent.ACTION_MOVE, t0, SystemClock.uptimeMillis(),
                pr[0], pr[1]);
        send(view, MotionEvent.ACTION_UP, t0, SystemClock.uptimeMillis(),
                pr[0], pr[1]);
        assertTrue(view.getSession().getSelection().start
                < view.getSession().getSelection().end);

        // Geste SUIVANT (après UP, l'armement est mort) : un jitter sous le
        // slop ne doit RIEN sélectionner — la sélection du drag précédent
        // n'est étendue par aucun état résiduel (sora finishDragSelect).
        float[] pj = midCharPos(view, c + 4 + 2);
        long t1 = t0 + 900;
        send(view, MotionEvent.ACTION_DOWN, t1, t1, pr[0], pr[1]);
        send(view, MotionEvent.ACTION_MOVE, t1, t1 + 30, pj[0], pj[1]);
        Selection mid = view.getSession().getSelection();
        assertEquals("aucun état d'armement ne doit survivre à l'UP",
                c, mid.start);
        send(view, MotionEvent.ACTION_UP, t1, t1 + 60, pj[0], pj[1]);

        // L'UP sans long-press résout un tap : caret posé au doigt.
        Selection after = view.getSession().getSelection();
        assertTrue(after.isCursor());
    }
}
