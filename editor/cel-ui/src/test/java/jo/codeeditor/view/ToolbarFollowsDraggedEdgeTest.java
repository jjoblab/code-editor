package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.document.Selection;
import jo.codeeditor.view.popup.EditorPopupAnchors;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T4 (P2) — verrouille <b>la pill suit la borne en cours de drag</b> :
 *
 * <p>Avant T4, l'ancre de la toolbar de sélection était TOUJOURS
 * {@code sel.end} (la borne droite) — y compris pendant le drag de la
 * poignée GAUCHE : « je tire la poignée gauche, la pill part à droite ».
 * L'ancre devient la borne <b>en déplacement</b> pendant le geste
 * (poignée : celle qui n'est pas l'ancre figée ; drag-select au doigt :
 * celle du doigt), et reste {@code sel.end} au repos.</p>
 *
 * <ul>
 *   <li><b>Poignée gauche</b> — drag vers la gauche : la pill se centre
 *       sur la borne mobile (gauche), pas sur la borne fixe ;</li>
 *   <li><b>Repos</b> — sans geste, la pill reste ancrée sur la fin
 *       (comportement inchangé, garde-fou anti-régression) ;</li>
 *   <li><b>Drag-select au doigt</b> — glissement après appui long : la
 *       pill suit la borne du doigt.</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorPendingTapDismissTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class ToolbarFollowsDraggedEdgeTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1 (début ligne 2 : 37)
        + "        greet(name);\n"        // 2 — « greet » 45..50, « name » 51..55
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;   // lineHeight * 0.5
    private static final float PAD_LEFT = 5f;   // charWidth * 0.5
    private static final float GUTTER_W = 70f;  // charWidth * 7

    private static final int GREET = DOC.indexOf("greet");
    private static final int NAME = DOC.indexOf("name");

    /** Attente de l'appui long (le GestureDetector poste son timer sur
     *  le looper principal — 400 ms système + marge, pattern
     *  {@code TouchDragSelectAnchorTest}). */
    private static final long LONG_PRESS_WAIT_MS = 650L;

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

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    /** Cible d'événement pour l'offset {@code offset} : x au quart de
     *  caractère (offsetAt arrondit — viser le centre donnerait le
     *  caractère suivant), y au milieu de la ligne (pattern
     * {@code TouchDragSelectAnchorTest}). */
    private float[] targetPos(EditorView view, int offset) {
        float[] pos = view.caretScreenPos(offset);
        return new float[]{pos[0] + CHAR_W * 0.25f, pos[1] + LINE_H / 2f};
    }

    /** Centre X écran de la colonne {@code col} de la ligne 2. */
    private static float colX(int col) {
        return GUTTER_W + PAD_LEFT + col * CHAR_W + CHAR_W * 0.5f;
    }

    /** Milieu vertical de la ligne 2. */
    private static final float LINE2_CY = PAD_TOP + 2 * LINE_H + LINE_H * 0.5f;

    /** Centre X de la pill (ancre centrée). */
    private static float pillCenterX(EditorView view) {
        EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        assertNotNull("la pill doit avoir des métriques pendant le geste", m);
        return m.x + m.w * 0.5f;
    }

    /** Position écran de la poignée d'une borne (sous la ligne). */
    private static float[] handlePos(EditorView view, int offset) {
        float[] pos = view.caretScreenPos(offset);
        float density = view.getResources().getDisplayMetrics().density;
        return new float[]{pos[0],
                pos[1] + view.metrics.getLineHeight()
                        + EditorView.HANDLE_RADIUS_DP * density * 0.6f};
    }

    @Test
    public void dragPoigneeGauche_laPillSuitLaBorneMobile() {
        EditorView view = newView();
        view.getSession().setSelection(Selection.range(GREET, GREET + 5));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;

        // DOWN sur la poignée GAUCHE (borne 45), MOVE vers l'offset 40.
        float[] hp = handlePos(view, GREET);
        long down = SystemClock.uptimeMillis();
        send(view, MotionEvent.ACTION_DOWN, down, down, hp[0], hp[1]);
        assertEquals(1, view.handleDragMode);
        float[] tp = targetPos(view, 40);
        send(view, MotionEvent.ACTION_MOVE, down, down + 40, tp[0], tp[1]);

        // Sélection étendue à gauche depuis l'ancre figée 50 : borne
        // MOBILE = start (≈40), borne FIGÉE = end (50).
        Selection sel = view.getSession().getSelection();
        assertTrue("la sélection doit s'étendre à gauche (start=" + sel.start
                + ")", sel.start < GREET);
        assertEquals(50, sel.end);

        float center = pillCenterX(view);
        float startX = view.caretScreenPos(sel.start)[0];
        float endX = view.caretScreenPos(sel.end)[0];
        assertTrue("la pill doit se centrer sur la borne MOBILE (gauche, ~"
                + startX + ") pendant le drag de la poignée gauche — centre="
                + center + ", borne fixe (droite)=" + endX,
                Math.abs(center - startX) < Math.abs(center - endX));
    }

    @Test
    public void auRepos_laPillResteAncreSurLaFin() {
        EditorView view = newView();
        view.getSession().setSelection(Selection.range(GREET, GREET + 5));
        view.selectionToolbarVisible = true;

        // Aucun geste : ancre = sel.end (comportement historique).
        float center = pillCenterX(view);
        float endX = view.caretScreenPos(GREET + 5)[0];
        float startX = view.caretScreenPos(GREET)[0];
        assertTrue("au repos, la pill doit rester ancrée sur la FIN (~" + endX
                        + ") — centre=" + center,
                Math.abs(center - endX) < Math.abs(center - startX));
    }

    @Test
    public void dragSelectAuDoigt_laPillSuitLaBorneDuDoigt() {
        EditorView view = newView();

        // Appui long sur « name » (sélection du mot + pill + armement du
        // drag-select), puis glissement vers l'offset 40.
        long t0 = SystemClock.uptimeMillis();
        float[] np = targetPos(view, NAME);
        send(view, MotionEvent.ACTION_DOWN, t0, t0, np[0], np[1]);
        SystemClock.sleep(LONG_PRESS_WAIT_MS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        Selection word = view.getSession().getSelection();
        assertEquals("l'appui long doit sélectionner « name »", NAME,
                word.start);
        assertEquals(NAME + 4, word.end);

        float[] tp = targetPos(view, 40);
        send(view, MotionEvent.ACTION_MOVE, t0, t0 + LONG_PRESS_WAIT_MS + 40,
                tp[0], tp[1]);

        // Sélection étendue à gauche depuis le mot : ancre = borne droite
        // du mot (55), borne du doigt = start (≈40).
        Selection sel = view.getSession().getSelection();
        assertTrue("la sélection doit s'étendre à gauche (start=" + sel.start
                + ")", sel.start < NAME);
        assertEquals(55, sel.end);
        assertTrue("la loupe doit être active pendant le drag-select",
                view.magnifierActive);

        float center = pillCenterX(view);
        float startX = view.caretScreenPos(sel.start)[0];
        float endX = view.caretScreenPos(sel.end)[0];
        assertTrue("la pill doit suivre la borne du DOIGT (gauche, ~"
                + startX + ") pendant le drag-select — centre=" + center
                + ", borne fixe (droite)=" + endX,
                Math.abs(center - startX) < Math.abs(center - endX));
    }
}
