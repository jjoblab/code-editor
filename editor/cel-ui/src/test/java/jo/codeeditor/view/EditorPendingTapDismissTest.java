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

import static org.junit.Assert.*;

/**
 * ★ v2.35 — verrouille le <b>pendingTapDismiss différé</b> (portage
 * {@code EditorInteraction.pendingTapDismiss} / {@code EditorInputModifier
 * onPress/onTap} de CodeAssist) :
 *
 * <ul>
 *   <li><b>Tap seul dans une sélection</b> — la sélection reste vivante
 *       pendant la fenêtre multi-tap (anti-flicker : pill + poignées
 *       visibles), puis est REFERMÉE au caret tapé une fois la fenêtre
 *       écoulée (c'est le correctif demandé : CodeIDE gardait la sélection
 *       pour toujours) ;</li>
 *   <li><b>Double-tap</b> — le second tap annule le pending et ÉTEND la
 *       sélection (selectWordAt) : après la fenêtre, rien ne se referme ;</li>
 *   <li><b>Swipe</b> — un déplacement au-delà du slop (scroll) annule le
 *       pending : la sélection survit au scroll ;</li>
 *   <li><b>Édition</b> — onTextChanged cancel le pending (le caret tapé ne
 *       serait plus valide après l'insertion) ;</li>
 *   <li><b>Fenêtre vivante</b> — avant l'échéance, idle() ne déclenche RIEN.</li>
 * </ul>
 *
 * <p>NOTE Robolectric : handleTap juge la fenêtre multi-tap sur
 * {@code System.currentTimeMillis()} (horloge réelle), tandis que le pending
 * est un postDelayed main-looper régi par l'horloge SHADOW — c'est
 * {@code SystemClock.sleep} qui l'avance ET exécute les tâches dues.</p>
 *
 * @author jo@Dev
 * @since v2.35
 */
@RunWith(RobolectricTestRunner.class)
public class EditorPendingTapDismissTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;   // lineHeight * 0.5
    private static final float PAD_LEFT = 5f;   // charWidth * 0.5
    private static final float GUTTER_W = 70f;  // charWidth * 7

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

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent downEvent = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(downEvent);
        downEvent.recycle();
        MotionEvent upEvent = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(upEvent);
        upEvent.recycle();
    }

    /** Screen position (center) of the given document offset. */
    private static float[] screenPosFor(int offset) {
        int line = 0, col = 0;
        for (int i = 0; i < offset; i++) {
            if (DOC.charAt(i) == '\n') { line++; col = 0; } else col++;
        }
        return new float[]{
            GUTTER_W + PAD_LEFT + col * CHAR_W + CHAR_W * 0.5f,
            PAD_TOP + line * LINE_H + LINE_H * 0.5f};
    }

    private static void idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Sélectionne « greet(name) » (mots « greet » à « name ») et arme le chrome tactile. */
    private static int[] armSelection(EditorView view) {
        int start = DOC.indexOf("greet");
        int end = DOC.indexOf("name") + "name".length();
        view.getSession().setSelection(Selection.range(start, end));
        view.handlesVisible = true;
        return new int[]{start, end};
    }

    @Test
    public void loneTapInsideSelection_keepsItAliveThenDismisses() throws Exception {
        EditorView view = newView();
        int[] sel = armSelection(view);
        float[] pos = screenPosFor(DOC.indexOf("name")); // au cœur de la sélection

        // Tap dans la sélection : elle reste VIVANTE (anti-flicker) et la
        // pill est (re)montrée — CodeAssist onPress.
        tap(view, pos[0], pos[1]);
        Selection after = view.getSession().getSelection();
        assertFalse("anti-flicker: selection alive inside the window",
                after.isCursor());
        assertEquals(sel[0], after.start);
        assertEquals(sel[1], after.end);
        assertTrue("pill visible during the window", view.selectionToolbarVisible);
        assertTrue(view.handlesVisible);

        // Avant l'échéance (280 ms), un idle ne déclenche RIEN.
        SystemClock.sleep(120);
        idleMainLooper();
        assertFalse(view.getSession().getSelection().isCursor());

        // Fenêtre écoulée → commit : la sélection se referme au caret tapé,
        // pill + poignées masquées (CodeAssist onTap).
        SystemClock.sleep(320);
        idleMainLooper();
        Selection committed = view.getSession().getSelection();
        assertTrue("lone tap must dismiss the selection", committed.isCursor());
        int tapped = view.offsetAt(pos[0], pos[1]);
        assertEquals("caret lands at the tapped offset", tapped, committed.start);
        assertFalse("pill hidden after the dismiss", view.selectionToolbarVisible);
        assertFalse("handles hidden after the dismiss", view.handlesVisible);
    }

    @Test
    public void doubleTapInsideSelection_expandsInsteadOfDismissing() throws Exception {
        EditorView view = newView();
        armSelection(view);
        float[] pos = screenPosFor(DOC.indexOf("name"));

        // 1er tap dans la sélection → pending armé.
        tap(view, pos[0], pos[1]);
        // 2e tap immédiat (même spot, dans la fenêtre) → double-tap :
        // le pending est ANNULÉ (DOWN suivant + handleTap entry) et la
        // sélection s'étend sur le mot (selectWordAt).
        tap(view, pos[0], pos[1]);
        Selection expanded = view.getSession().getSelection();
        assertFalse(expanded.isCursor());
        assertTrue("double-tap selects the word",
                DOC.substring(expanded.start, expanded.end).equals("name")
                        || DOC.substring(expanded.start, expanded.end).contains("name"));

        // Fenêtre écoulée : RIEN ne se referme (le pending est mort).
        SystemClock.sleep(320);
        idleMainLooper();
        assertFalse("double-tap result must survive the window",
                view.getSession().getSelection().isCursor());
        assertTrue(view.selectionToolbarVisible);
    }

    @Test
    public void swipeFromInsideSelection_cancelsPending() throws Exception {
        EditorView view = newView();
        armSelection(view);
        float[] pos = screenPosFor(DOC.indexOf("name"));

        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, pos[0], pos[1], 0);
        view.onTouchEvent(d);
        d.recycle();
        // Mouvement au-delà du slop (24px) → scroll, jamais un tap.
        MotionEvent m = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_MOVE,
                pos[0] + 120f, pos[1] + 200f, 0);
        view.onTouchEvent(m);
        m.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP,
                pos[0] + 120f, pos[1] + 200f, 0);
        view.onTouchEvent(u);
        u.recycle();

        // Fenêtre écoulée : le swipe a tué le pending — la sélection survit.
        SystemClock.sleep(320);
        idleMainLooper();
        Selection sel = view.getSession().getSelection();
        assertFalse("a swipe must NOT dismiss the selection", sel.isCursor());
    }

    @Test
    public void editAfterTap_cancelsPending() throws Exception {
        EditorView view = newView();
        armSelection(view);
        float[] pos = screenPosFor(DOC.indexOf("name"));
        int caretBefore = view.getSession().getSelection().end;

        tap(view, pos[0], pos[1]);
        // Insertion immédiate (dans la fenêtre) : onTextChanged cancel.
        EditorSession session = view.getSession();
        session.replaceRange(caretBefore, caretBefore, "X");
        view.notifyTextChanged();

        SystemClock.sleep(320);
        idleMainLooper();
        // Le pending est mort : le caret reste à la position d'édition, pas
        // déplacé vers l'offset tapé d'avant-édition.
        Selection sel = view.getSession().getSelection();
        assertTrue(sel.isCursor());
        assertEquals("caret must stay at the edit position (pending cancelled)",
                caretBefore + 1, sel.start);
    }

    @Test
    public void tapOutsideSelection_movesCaretImmediately() throws Exception {
        EditorView view = newView();
        armSelection(view);
        // Un tap HORS de la sélection : effondrement immédiat (pas différé).
        float[] other = screenPosFor(DOC.indexOf("public"));
        tap(view, other[0], other[1]);
        assertTrue("tap outside must collapse immediately",
                view.getSession().getSelection().isCursor());
        // …et la fenêtre ne change rien.
        SystemClock.sleep(320);
        idleMainLooper();
        assertTrue(view.getSession().getSelection().isCursor());
        assertFalse(view.selectionToolbarVisible);
    }
}
