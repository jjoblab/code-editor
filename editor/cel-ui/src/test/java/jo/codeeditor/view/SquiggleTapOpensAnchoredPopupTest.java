package jo.codeeditor.view;

import jo.codeeditor.view.popup.EditorPopupAnchors;

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
 * D1 (P0) — verrouille l'ouverture du <b>popup ancré au tap sur le
 * squiggle</b> :
 *
 * <ul>
 *   <li><b>Hit au cœur</b> — un tap au cœur d'une plage diagnostiquée
 *       (offset strictement intérieur, bords ±1 exclus) ouvre le popup
 *       ancré SANS poser le caret (le premier tap arme, le second
 *       décide — motif pendingTapDismiss) ;</li>
 *   <li><b>Ancrage au-dessus de la ligne</b> — le popup se dessine
 *       au-dessus de la ligne du diagnostic (motif complétion :
 *       anchorY au-dessus, flip en dessous, clamp X) ;</li>
 *   <li><b>Non modal</b> — aucun scrim : un tap ailleurs referme le
 *       popup et place le caret normalement ;</li>
 *   <li><b>Diagnostics non squigglés exclus</b> — le voile gris du tag
 *       Unnecessary (pas de soulignement) ne déclenche PAS le popup (le
 *       tap y reste un simple placement de caret).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorPendingTapDismissTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SquiggleTapOpensAnchoredPopupTest {

    private static final String DOC =
        "public class Main {\n"          // 0 (20)
        + "    void run() {\n"            // 1 (37)
        + "        greet(name);\n"        // 2 — « greet » cols 8..12
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;   // lineHeight * 0.5
    private static final float PAD_LEFT = 5f;   // charWidth * 0.5
    private static final float GUTTER_W = 70f;  // charWidth * 7

    /** « greet » : [45, 50). */
    private static final int GREET = DOC.indexOf("greet");

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

    private void setErrorOnGreet(EditorView view) {
        view.getSession().setDiagnostics(Collections.singletonList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3,
                        "Cannot resolve method 'greet'")));
    }

    @Test
    public void tapAuCœurOuvreLePopupSansPoserLeCaret() {
        EditorView view = newView();
        setErrorOnGreet(view);

        // Cœur de « greet » : col 10 → offset 48, strictement intérieur à
        // [45, 50) avec la marge des bords ±1.
        float[] pos = charCenterOnLine2(10);
        tap(view, pos[0], pos[1]);

        assertTrue("le tap au cœur doit ouvrir le popup ancré",
                view.squigglePopupVisible);
        assertNotNull(view.squigglePopupItem);
        assertTrue("le popup détaillé n'est pas une mini-liste (1 seul diagnostic)",
                view.squigglePopupChoices.isEmpty());
        // Parité caret : le premier tap NE POSE PAS le caret (il arme).
        assertTrue("le caret ne doit pas bouger au premier tap",
                view.getSession().getSelection().isCursor());
        assertEquals(0, view.getSession().getSelection().start);
    }

    @Test
    public void popupAncréAuDessusDeLaLigneDuDiagnostic() {
        EditorView view = newView();
        setErrorOnGreet(view);

        float[] pos = charCenterOnLine2(10);
        tap(view, pos[0], pos[1]);

        EditorPopupAnchors.SquigglePopupMetrics m =
                EditorPopupAnchors.squigglePopupMetrics(view);
        assertNotNull("les métriques du popup doivent exister quand il est visible", m);
        assertEquals("l'item détaillé est le diagnostic tapé",
                view.squigglePopupItem, m.item);
        float line2Top = view.docLineToY(2) - view.vOffset;
        assertTrue("ancré AU-DESSUS de la ligne (bas du popup ≤ haut de la ligne + 0.5px)",
                m.y + m.h <= line2Top + 0.5f);
        assertTrue("largeur positive", m.w > 0);
        assertTrue("dans le viewport horizontal",
                m.x >= 0 && m.x + m.w <= view.getWidth() + 0.5f);
        // Clamp X : le popup reste dans la zone de texte (pas sous la gouttière).
        assertTrue(m.x >= view.metrics.getGutterWidth());
    }

    @Test
    public void nonModal_tapAilleursRefermeEtPlaceLeCaret() {
        EditorView view = newView();
        setErrorOnGreet(view);

        float[] heart = charCenterOnLine2(10);
        tap(view, heart[0], heart[1]);
        assertTrue(view.squigglePopupVisible);

        // Tap sur la ligne 4 (zone sans diagnostic) : fermeture + caret.
        float[] elsewhere = new float[]{
            GUTTER_W + PAD_LEFT + 2 * CHAR_W + CHAR_W * 0.5f,
            PAD_TOP + 4 * LINE_H + LINE_H * 0.5f};
        tap(view, elsewhere[0], elsewhere[1]);

        assertFalse("un tap hors du popup le referme (non modal)",
                view.squigglePopupVisible);
        assertTrue("le tap ailleurs place le caret normalement",
                view.getSession().getSelection().isCursor());
        int expected = view.offsetAt(elsewhere[0], elsewhere[1]);
        assertEquals(expected, view.getSession().getSelection().start);
    }

    @Test
    public void diagnosticUnnecessary_neDéclenchePasLePopup() {
        EditorView view = newView();
        // Voile gris (tag Unnecessary) : PAS de squiggle → pas de popup.
        view.getSession().setDiagnostics(Collections.singletonList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 2,
                        "unused", true)));

        float[] pos = charCenterOnLine2(10);
        tap(view, pos[0], pos[1]);

        assertFalse("le voile Unnecessary n'est pas un squiggle tappable",
                view.squigglePopupVisible);
        assertTrue("le tap reste un placement de caret",
                view.getSession().getSelection().isCursor());
        assertEquals(view.offsetAt(pos[0], pos[1]),
                view.getSession().getSelection().start);
    }
}
