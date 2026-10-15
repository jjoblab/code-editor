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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * D1 (P0) — verrouille les <b>boutons de quick-fixes</b> du popup ancré
 * au tap squiggle :
 *
 * <ul>
 *   <li><b>Résolution à l'ouverture</b> — le popup ouvre avec la
 *       résolution des actions au début du diagnostic (réutilise
 *       {@code resolveCodeActionsAt}, la voie du lot 1) ; les rangées
 *       apparaissent quand la résolution publie ;</li>
 *   <li><b>Tap sur une rangée</b> — applique l'action puis referme le
 *       popup (le comportement de la fiche actuelle, en ancré) ;</li>
 *   <li><b>« See all »</b> — le bouton bascule vers la fiche bas d'écran
 *       existante (vue complète : message intégral, toutes les actions)
 *       — l'architecture à deux niveaux, rien n'est supprimé.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SquiggleTapQuickFixTest {

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

    /** Compteur d'invocations du résolveur (faux serveur synchrone). */
    private static final class Compteur implements EditorView.CodeActionsResolver {
        int appels;
        int dernierAnchor = -1;
        final List<EditorView.CodeAction> actions = new ArrayList<>();
        final List<String> appliquées = new ArrayList<>();

        Compteur() {
            actions.add(new EditorView.CodeAction(
                    "Create method 'greet'", "quickfix", () -> appliquées.add("create")));
            actions.add(new EditorView.CodeAction(
                    "Rename to 'great'", "quickfix", () -> appliquées.add("rename")));
        }

        @Override
        public List<EditorView.CodeAction> resolve(String text, int line) {
            appels++;
            return actions;
        }

        @Override
        public List<EditorView.CodeAction> resolveAtCaret(
                String text, int caretOffset, int selectionStart, int selectionEnd) {
            appels++;
            dernierAnchor = caretOffset;
            return actions;
        }
    }

    private EditorView newView(Compteur compteur) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        session.setSelection(0);
        view.setCodeActionsResolver(compteur);
        session.setDiagnostics(Collections.singletonList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3, "unknown greet")));
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

    /** Attend la publication des quick-fixes (voie « features » async). */
    /** A-06 : attente bornée des quick-fixes résolus. */
    private static boolean awaitActions(EditorView view, long timeoutMs) {
        return jo.codeeditor.AttenteBornee.jusquA(timeoutMs, () -> !view.codeActionsByLine.isEmpty());
    }

    @Test
    public void ouvertureRésoutLesQuickFixesAuDiagnostic() throws Exception {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);

        float[] heart = charCenterOnLine2(10);
        tap(view, heart[0], heart[1]);

        assertTrue(view.squigglePopupVisible);
        assertTrue("les quick-fixes doivent être résolus à l'ouverture",
                awaitActions(view, 5000));
        assertEquals("l'ancre de la résolution est le DÉBUT du diagnostic",
                GREET, compteur.dernierAnchor);
        assertEquals(2, view.codeActionsByLine.get(2).size());
    }

    @Test
    public void tapSurUneRangéeAppliqueEtReferme() throws Exception {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);

        float[] heart = charCenterOnLine2(10);
        tap(view, heart[0], heart[1]);
        assertTrue(awaitActions(view, 5000));

        EditorPopupAnchors.SquigglePopupMetrics m =
                EditorPopupAnchors.squigglePopupMetrics(view);
        assertNotNull(m);
        assertTrue("deux rangées de quick-fixes", m.rows >= 2);
        assertTrue("la rangée « See all » est présente", m.seeAll);

        // Tap au centre de la rangée 1 (« Rename to 'great' »).
        float rowY = m.rowTop + 1 * m.rowH + m.rowH * 0.5f;
        float rowX = m.x + m.w * 0.5f;
        tap(view, rowX, rowY);

        assertEquals("l'action de la rangée est APPLIQUÉE",
                Collections.singletonList("rename"), compteur.appliquées);
        assertFalse("le popup se referme après application",
                view.squigglePopupVisible);
    }

    @Test
    public void seeAllBasculeVersLaFicheBasseExistante() throws Exception {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);

        float[] heart = charCenterOnLine2(10);
        tap(view, heart[0], heart[1]);
        assertTrue(awaitActions(view, 5000));

        EditorPopupAnchors.SquigglePopupMetrics m =
                EditorPopupAnchors.squigglePopupMetrics(view);
        assertNotNull(m);
        assertTrue(m.seeAll);

        // Tap sur la rangée « See all » (sentinelle = m.rows).
        float seeAllY = m.seeAllY + m.rowH * 0.5f;
        tap(view, m.x + m.w * 0.5f, seeAllY);

        assertFalse("le popup ancré se referme", view.squigglePopupVisible);
        assertTrue("la FICHE BASSE existante prend le relais (vue complète)",
                view.diagnosticPopupVisible);
        assertNotNull(view.diagnosticPopupItem);
        assertEquals(GREET, view.diagnosticPopupItem.start);
    }
}
