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
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * D1 (P0) — verrouille la <b>mini-liste multi-diagnostics</b> du popup
 * ancré au tap squiggle :
 *
 * <ul>
 *   <li><b>Superposition</b> — plusieurs diagnostics couvrant l'offset
 *       tapé (ex. une erreur et un warning superposés) → mini-liste
 *       ancrée (une rangée par diagnostic : sévérité + première ligne du
 *       message) au lieu du popup de détail ;</li>
 *   <li><b>Choix de rangée</b> — un tap sur une rangée ouvre le popup
 *       ancré de CE diagnostic (détail + quick-fixes résolus) ;</li>
 *   <li><b>Tri</b> — les rangées sont ordonnées par sévérité
 *       décroissante (le plus grave d'abord), la fiche groupée pleine
 *       page reste accessible par la chip.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SquiggleTapMultiDiagnosticTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2 — « greet » [45, 50)
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;
    private static final float PAD_LEFT = 5f;
    private static final float GUTTER_W = 70f;

    private static final int GREET = DOC.indexOf("greet");

    /** 3 diagnostics superposés sur « greet » : info, error, warning. */
    private static List<DiagnosticShift.Diagnostic> threeOnGreet() {
        return Arrays.asList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 1, "info note"),
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3, "error bang"),
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 2, "warning heads up"));
    }

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
        TestMetrics.injecter(view, lineHeight, charWidth);
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

    @Test
    public void troisDiagnosticsSuperposés_miniListeAncréeDeTroisRangées() {
        EditorView view = newView();
        view.getSession().setDiagnostics(threeOnGreet());

        float[] heart = charCenterOnLine2(10);
        tap(view, heart[0], heart[1]);

        assertTrue(view.squigglePopupVisible);
        assertEquals("mode MINI-LISTE : les 3 choix sont mémorisés",
                3, view.squigglePopupChoices.size());

        EditorPopupAnchors.SquigglePopupMetrics m =
                EditorPopupAnchors.squigglePopupMetrics(view);
        assertNotNull(m);
        assertTrue("mode liste", m.isListMode());
        assertEquals("3 rangées (une par diagnostic)", 3, m.rows);
        // Tri : sévérité décroissante — l'error (3) d'abord.
        assertEquals(3, m.choices.get(0).severity);
        assertEquals(2, m.choices.get(1).severity);
        assertEquals(1, m.choices.get(2).severity);
        // Ancrée à la ligne : au-dessus si la place suffit (motif complétion
        // — flip en dessous sinon), jamais par-dessus le texte de la ligne
        // elle-même. 3 rangées × 34dp ne tiennent pas au-dessus de la ligne
        // 2 (haut à y=100) → la mini-liste est basculée SOUS la ligne.
        float line2Top = view.docLineToY(2) - view.scroll.vOffset;
        boolean above = m.y + m.h <= line2Top + 0.5f;
        boolean below = m.y >= line2Top + view.metrics.getLineHeight() - 0.5f;
        assertTrue("ancrée au-dessus OU sous la ligne (flip), pas par-dessus",
                above || below);
        assertTrue("flip : 3 rangées ne tiennent pas au-dessus de la ligne 2", below);
        // Le caret n'est pas posé (premier tap = armement).
        assertTrue(view.getSession().getSelection().isCursor());
        assertEquals(0, view.getSession().getSelection().start);
    }

    @Test
    public void tapSurUneRangée_passeAuDétailDeCeDiagnostic() {
        EditorView view = newView();
        view.getSession().setDiagnostics(threeOnGreet());

        float[] heart = charCenterOnLine2(10);
        tap(view, heart[0], heart[1]);
        assertTrue(view.squigglePopupVisible);
        assertEquals(3, view.squigglePopupChoices.size());

        // Tap sur la rangée 3 (sévérité 1 — l'info).
        EditorPopupAnchors.SquigglePopupMetrics m =
                EditorPopupAnchors.squigglePopupMetrics(view);
        float rowY = m.rowTop + 2 * m.rowH + m.rowH * 0.5f;
        tap(view, m.x + m.w * 0.5f, rowY);

        assertTrue("le popup reste ouvert (bascule liste → détail)",
                view.squigglePopupVisible);
        assertNotNull("le détail montre le diagnostic CHOISI",
                view.squigglePopupItem);
        assertEquals(1, view.squigglePopupItem.severity);
        assertEquals("info note", view.squigglePopupItem.message);
        assertTrue("les choix sont consommés (mode détail)",
                view.squigglePopupChoices.isEmpty());
    }

    @Test
    public void ordreStable_quandUneSeulePlage_nePasBasculerSurLeGroupe() {
        EditorView view = newView();
        // Un diagnostic unique : popup de DÉTAIL direct (pas de mini-liste).
        view.getSession().setDiagnostics(Arrays.asList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 2, "solo warning")));

        float[] heart = charCenterOnLine2(10);
        tap(view, heart[0], heart[1]);

        assertTrue(view.squigglePopupVisible);
        assertTrue("un seul diagnostic → détail direct, pas de liste",
                view.squigglePopupChoices.isEmpty());
        assertNotNull(view.squigglePopupItem);
        assertEquals("solo warning", view.squigglePopupItem.message);
    }
}
