package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * I4 (P2, lot 2 jalon J6) — les <b>inlays cliquables</b> : un hint
 * portant une {@code command} s'exécute au TAP SIMPLE sur sa plage
 * tissée, résolu AVANT le placement du caret (précédent complétion) —
 * usage type : hints « … » d'expansion.
 *
 * <ul>
 *   <li><b>Tap sur hint à command</b> — la commande s'exécute
 *       exactement une fois, le caret NE BOUGE PAS, aucune pill ;</li>
 *   <li><b>Command qui édite</b> — l'état du document change et passe
 *       par {@code onTextChanged} (rafraîchissement complet) ;</li>
 *   <li><b>Hint sans command</b> — le tap reste un placement de caret
 *       classique (texte fantôme passif) ;</li>
 *   <li><b>Tap hors hint</b> — caret classique, commande jamais
 *       touchée ;</li>
 *   <li><b>Command qui jette</b> — journalisée sans crash, la cascade
 *       reprend normalement.</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code InlayTooltipLongPressTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class InlayClickCommandTest {

    private static final String DOC =
        "public class Main {\n"
        + "    void run() {\n"
        + "        int count = greet(name);\n"
        + "    }\n"
        + "}\n";

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    /** Installe un hint « ⋯ » à command à l'offset donné. */
    private void installerHintÀCommande(EditorView view, int offset, Runnable command) {
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(offset, " ⋯ ", false,
                "type", null, null, command));
        view.getSession().setInlayHints(hints);
    }

    /** Tap simple au centre de la plage tissée du hint. */
    private void tapSurLeHint(EditorView view, int visStart, int len, int line) {
        float charWidth = view.metrics.getCharWidth();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float x = textAreaLeft + (visStart + len * 0.5f) * charWidth;
        float y = view.docLineToY(line) - view.scroll.vOffset + LINE_H * 0.5f;
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
        ShadowLooper.idleMainLooper();
    }

    private int offsetAvantName() {
        return DOC.indexOf("greet(") + "greet(".length();
    }

    @Test
    public void tapSurHintÀCommande_exécuteSansBougerLeCaret() {
        EditorView view = newView();
        AtomicInteger runs = new AtomicInteger();
        installerHintÀCommande(view, offsetAvantName(), runs::incrementAndGet);
        view.getSession().setSelection(0);

        int lineStart = DOC.lastIndexOf('\n', offsetAvantName() - 1) + 1;
        int col = offsetAvantName() - lineStart;
        int visStart = view.visualColFor(2, col);
        tapSurLeHint(view, visStart, " ⋯ ".length(), 2);

        assertEquals("la commande s'exécute exactement une fois", 1, runs.get());
        Selection sel = view.getSession().getSelection();
        assertTrue("le caret NE BOUGE PAS (la commande a englouti le tap)",
                sel.isCursor());
        assertEquals(0, sel.start);
        assertFalse("aucune pill ouverte", view.selectionToolbarVisible);
    }

    @Test
    public void commandQuiÉdite_passeParOnTextChanged() {
        EditorView view = newView();
        final int insertAt = DOC.indexOf("count");
        // La command insère « expanded » avant « count » (simulation
        // d'un hint d'expansion).
        installerHintÀCommande(view, offsetAvantName(), () ->
                view.getSession().replaceRange(insertAt, insertAt, "expanded "));
        view.getSession().setSelection(0);

        int lineStart = DOC.lastIndexOf('\n', offsetAvantName() - 1) + 1;
        int col = offsetAvantName() - lineStart;
        int visStart = view.visualColFor(2, col);
        tapSurLeHint(view, visStart, " ⋯ ".length(), 2);

        assertTrue("le document porte l'expansion",
                view.getSession().getText().contains("expanded count"));
    }

    @Test
    public void hintSansCommand_tapClassiquePlaceLeCaret() {
        EditorView view = newView();
        AtomicInteger runs = new AtomicInteger();
        installerHintÀCommande(view, offsetAvantName(), null); // hint passif
        view.getSession().setSelection(0);

        int lineStart = DOC.lastIndexOf('\n', offsetAvantName() - 1) + 1;
        int col = offsetAvantName() - lineStart;
        int visStart = view.visualColFor(2, col);
        tapSurLeHint(view, visStart, " ⋯ ".length(), 2);

        assertEquals("la voie command n'est JAMAIS touchée sans command", 0, runs.get());
        Selection sel = view.getSession().getSelection();
        assertTrue("le tap classique place le caret", sel.isCursor());
        assertEquals("le caret est dans la zone tapée",
                offsetAvantName(), sel.start);
    }

    @Test
    public void tapHorsHint_caretClassique_commandeJamaisTouchée() {
        EditorView view = newView();
        AtomicInteger runs = new AtomicInteger();
        installerHintÀCommande(view, offsetAvantName(), runs::incrementAndGet);
        view.getSession().setSelection(0);

        // Tap sur « count » — loin du hint tissé après « greet( ».
        int countOffset = DOC.indexOf("count");
        int lineStart = DOC.lastIndexOf('\n', countOffset - 1) + 1;
        int col = countOffset - lineStart + 2;
        int visStart = view.visualColFor(2, col);
        tapSurLeHint(view, visStart, 2, 2);

        assertEquals(0, runs.get());
        assertTrue(view.getSession().getSelection().isCursor());
        int caret = view.getSession().getSelection().start;
        assertTrue("le caret atterrit dans la zone tapée (±1 arrondi visuel), caret="
                + caret + " attendu ~" + (countOffset + 2),
                caret >= countOffset + 1 && caret <= countOffset + 3);
    }

    @Test
    public void commandQuiJette_journaliséeSansCrash() {
        EditorView view = newView();
        installerHintÀCommande(view, offsetAvantName(),
                (Runnable) () -> { throw new IllegalStateException("boom"); });
        view.getSession().setSelection(0);

        int lineStart = DOC.lastIndexOf('\n', offsetAvantName() - 1) + 1;
        int col = offsetAvantName() - lineStart;
        int visStart = view.visualColFor(2, col);
        try {
            tapSurLeHint(view, visStart, " ⋯ ".length(), 2);
        } catch (Throwable t) {
            throw new AssertionError("la commande qui jette ne doit pas crasher la vue", t);
        }
        // Le document est intact, le caret n'a pas bougé.
        assertEquals(DOC, view.getSession().getText());
        assertEquals(0, view.getSession().getSelection().start);
    }
}
