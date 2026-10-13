package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * B10 — le 4e tap de la cascade : caret (1) → mot (2) → ligne (3) →
 * PARAGRAPHE (4) : le bloc de lignes contiguës non vides. Le 5e tap
 * RESTEME au paragraphe (la cascade ne repart pas au caret).
 */
@RunWith(RobolectricTestRunner.class)
public class FourthTapParagraphTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float GUTTER_W = 70f;
    private static final float PAD_LEFT = 5f;
    private static final float PAD_TOP = 20f;

    private static final String DOC =
        "alpha beta\n"      // 0 ┐ paragraphe 1 (lignes 0-1)
        + "gamma delta\n"   // 1 ┘
        + "\n"              // 2 borne vide
        + "epsilon\n"       // 3 paragraphe 2 (ligne 3)
        + "\n"              // 4 borne vide
        + "zeta\n";         // 5 paragraphe 3 (ligne 5)

    private EditorView view;
    private EditorSession session;

    @Before
    public void setUp() {
        Context ctx = RuntimeEnvironment.getApplication();
        view = new EditorView(ctx);
        session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
    }

    private void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", PAD_TOP);
            setFloat(view.metrics, "padLeft", PAD_LEFT);
            setFloat(view.metrics, "gutterWidth", GUTTER_W);
            setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value)
            throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    /** Position écran (quart gauche du glyphe) de la ligne donnée. */
    private static float screenX(int col) {
        // offsetAt ARRONDIT : viser le quart gauche du caractère (piège
        // Robolectric documenté dans AGENT.md).
        return GUTTER_W + PAD_LEFT + col * CHAR_W + CHAR_W * 0.25f;
    }

    private static float screenY(int line) {
        return PAD_TOP + line * LINE_H + LINE_H * 0.5f;
    }

    private void tap(float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
        idle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void cascadeComplète_caretMotLigneParagraphe() {
        // 4 taps au même endroit : dans « gamma » (ligne 1, colonne 0).
        float x = screenX(0);
        float y = screenY(1);

        tap(x, y);
        assertTrue("tap 1 : caret", session.getSelection().isCursor());

        tap(x, y);
        assertEquals("tap 2 : mot « gamma »", DOC.indexOf("gamma"),
                session.getSelection().start);

        tap(x, y);
        assertEquals("tap 3 : ligne 1 seule", "alpha beta\n".length(),
                session.getSelection().start);
        assertEquals("fin de ligne 1 sans le saut",
                "alpha beta\ngamma delta".length(), session.getSelection().end);

        tap(x, y);
        assertEquals("tap 4 : PARAGRAPHE lignes 0-1, début au début du doc",
                0, session.getSelection().start);
        assertEquals("fin du paragraphe sans le saut final",
                "alpha beta\ngamma delta".length(), session.getSelection().end);
    }

    @Test
    public void leCinquièmeTapResteAuParagraphe() {
        float x = screenX(0);
        float y = screenY(3); // « epsilon », paragraphe = ligne 3 seule
        for (int i = 0; i < 4; i++) tap(x, y);
        int fifthStart = "alpha beta\ngamma delta\n\n".length();
        tap(x, y);
        assertEquals("le 5e tap ne repart PAS au caret : paragraphe",
                fifthStart, session.getSelection().start);
        assertEquals(fifthStart + "epsilon".length(),
                session.getSelection().end);
    }
}
