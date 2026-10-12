package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.MotionEvent;
import android.os.SystemClock;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * D1 (P0) — verrouille la <b>parité caret</b> du popup ancré au tap
 * squiggle (motif différé pendingTapDismiss : le premier tap arme, le
 * second décide) :
 *
 * <ul>
 *   <li><b>Re-tap sur la même plage</b> — un second tap au même endroit
 *       que le tap qui a ouvert le popup le REFERME et POSE le caret à
 *       l'offset tapé (l'utilisateur peut éditer au cœur de la plage) ;</li>
 *   <li><b>Bords ±1 caractère</b> — un tap sur le premier ou le dernier
 *       caractère de la plage diagnostiquée ne JAMAIS ouvrir le popup :
 *       caret directement (l'utilisateur veut se positionner avant /
 *       après / sur le bord de la plage) ;</li>
 *   <li><b>Tap sur un autre squiggle</b> — le popup bascule sur le
 *       nouveau diagnostic au lieu de poser le caret.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SquiggleTapCaretParityTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(nam2);\n"        // 2 — « greet » [45,50) et « nam2 » [51,56)
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;
    private static final float PAD_LEFT = 5f;
    private static final float GUTTER_W = 70f;

    private static final int GREET = DOC.indexOf("greet");
    private static final int NAM2 = DOC.indexOf("nam2");

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        session.setSelection(0);
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

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    /** Position écran (centre) du caractère de colonne {@code col} sur la ligne 2. */
    private static float[] charCenterOnLine2(int col) {
        return new float[]{
            GUTTER_W + PAD_LEFT + col * CHAR_W + CHAR_W * 0.5f,
            PAD_TOP + 2 * LINE_H + LINE_H * 0.5f};
    }

    private void setErrors(EditorView view) {
        view.getSession().setDiagnostics(java.util.Arrays.asList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3, "unknown greet"),
                new DiagnosticShift.Diagnostic(NAM2, NAM2 + 4, 2, "unknown nam2")));
    }

    @Test
    public void reTapSurLaMêmePlage_refermeEtPoseLeCaret() throws Exception {
        EditorView view = newView();
        setErrors(view);

        // 1er tap au cœur (col 10 → offset 48) : ouvre le popup, caret intact.
        float[] heart = charCenterOnLine2(10);
        tap(view, heart[0], heart[1]);
        assertTrue(view.squigglePopupVisible);

        // Gap > fenêtre multi-tap (280 ms, horloge RÉELLE) : le second tap
        // est un tap ISOLÉ, pas un double-tap.
        Thread.sleep(320);

        // 2e tap au MÊME endroit : fermeture + caret à l'offset tapé.
        tap(view, heart[0], heart[1]);
        assertFalse("le re-tap sur la même plage referme le popup",
                view.squigglePopupVisible);
        assertTrue("le re-tap pose le caret (parité caret)",
                view.getSession().getSelection().isCursor());
        assertEquals("le caret est à l'offset tapé",
                view.offsetAt(heart[0], heart[1]),
                view.getSession().getSelection().start);
    }

    @Test
    public void tapSurLePremierCaractère_caretDirectSansPopup() {
        EditorView view = newView();
        setErrors(view);

        // Premier caractère de « greet » (col 8 → offset 46 = start + 1 :
        // bord) — jamais de popup, caret direct.
        float[] pos = charCenterOnLine2(8);
        tap(view, pos[0], pos[1]);

        assertFalse("le bord gauche (±1 caractère) n'ouvre pas le popup",
                view.squigglePopupVisible);
        assertTrue(view.getSession().getSelection().isCursor());
        assertEquals(view.offsetAt(pos[0], pos[1]),
                view.getSession().getSelection().start);
    }

    @Test
    public void tapSurLeDernierCaractère_caretDirectSansPopup() {
        EditorView view = newView();
        setErrors(view);

        // Dernier caractère de « greet » (col 12 → offset 50 = end : bord).
        float[] pos = charCenterOnLine2(12);
        tap(view, pos[0], pos[1]);

        assertFalse("le bord droit (±1 caractère) n'ouvre pas le popup",
                view.squigglePopupVisible);
        assertTrue(view.getSession().getSelection().isCursor());
        assertEquals(view.offsetAt(pos[0], pos[1]),
                view.getSession().getSelection().start);
    }

    @Test
    public void tapSurUnAutreSquiggle_basculeLePopup() throws Exception {
        EditorView view = newView();
        setErrors(view);

        // Ouvre le popup de « greet »…
        float[] greetHeart = charCenterOnLine2(10);
        tap(view, greetHeart[0], greetHeart[1]);
        assertTrue(view.squigglePopupVisible);
        assertEquals(GREET, view.squigglePopupItem.start);

        // Gap > fenêtre multi-tap : tap isolé.
        Thread.sleep(320);

        // …puis tap au cœur de « nam2 » (col 15 → offset 53, intérieur à
        // [51, 55)) : le popup BASCULE, le caret n'est pas posé.
        float[] nam2Heart = charCenterOnLine2(15);
        tap(view, nam2Heart[0], nam2Heart[1]);

        assertTrue("le popup reste ouvert (bascule, pas fermeture)",
                view.squigglePopupVisible);
        assertEquals("le popup montre le NOUVEAU diagnostic",
                NAM2, view.squigglePopupItem.start);
        assertTrue("le caret n'est toujours pas posé",
                view.getSession().getSelection().isCursor());
        assertEquals(0, view.getSession().getSelection().start);
    }

    @Test
    public void tripleTapSélectionneLeMot_aprèsOuverture() {
        EditorView view = newView();
        setErrors(view);

        // Le double/triple tap doit rester fonctionnel sur une ligne
        // diagnostiquée : 1er tap → popup ; 2e+3e taps (fenêtre
        // multi-tap) → sélection du mot (la cascade multi-tap n'est pas
        // court-circuitée par le popup).
        float[] heart = charCenterOnLine2(10);
        tap(view, heart[0], heart[1]);
        tap(view, heart[0], heart[1]);
        tap(view, heart[0], heart[1]);

        // Triple-tap → sélection de la LIGNE.
        assertFalse(view.squigglePopupVisible);
        jo.codeeditor.document.Selection sel = view.getSession().getSelection();
        assertFalse("triple-tap sélectionne la ligne (pas un caret)",
                sel.isCursor());
        int line2 = DOC.indexOf("        greet");
        assertTrue(sel.start >= line2 && sel.end <= line2 + 20);
    }
}
