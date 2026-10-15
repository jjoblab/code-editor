package jo.codeeditor.view.render;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.view.EditorView;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * UX-4 (retour terrain post-J6) — preuve au CANVAS que le contenu du
 * popup ancré de squiggle vit DANS la carte : le painter seul dessine
 * sur un Canvas vierge (pas de pipeline), puis l'historique de dessin
 * du shadow canvas ({@code ShadowCanvas.getTextHistory}) est passé au
 * crible.
 *
 * <p>Avant le correctif : le cadre était ancré au mot (à droite de
 * l'écran) mais chaque drawText partait au padX ABSOLU — x ≈ 12, 24,
 * 36… à gauche du cadre. Après : tout texte part de
 * {@code contentLeft} (la métrique, {@code x + padX}) — chaque
 * événement de texte commence DANS la carte [x, x + w].</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SquigglePopupRightAnchorRenderTest {

    private static final String DOC =
        "public class Main {\n"
        + "    void run() {\n"
        + "        String veryLongVariablePrefixJustToPushTheWordToTheRightSide = greet(target);\n"
        + "    }\n"
        + "}\n";

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int W = 1080;
    private static final int H = 1920;

    private static final int GREET = DOC.indexOf("greet");

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(W, H);
        view.layout(0, 0, W, H);
        injectMetrics(view, LINE_H, CHAR_W);
        session.setSelection(0);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        jo.codeeditor.view.TestMetrics.injecter(view, lineHeight, charWidth);
    }

    /** Vérifie que TOUT drawText du popup démarre dans la carte. */
    private void assertToutLeTexteDansLaCarte(EditorView view,
            jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m) {
        Canvas canvas = new Canvas(Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888));
        EditorDiagnosticsPainter painter = new EditorDiagnosticsPainter(view);
        painter.drawSquigglePopup(canvas);

        org.robolectric.shadows.ShadowCanvas shadow = Shadows.shadowOf(canvas);
        int n = shadow.getTextHistoryCount();
        assertTrue("des textes sont dessinés (détail)", n > 0);
        for (int i = 0; i < n; i++) {
            org.robolectric.shadows.ShadowCanvas.TextHistoryEvent e =
                    shadow.getDrawnTextEvent(i);
            assertTrue("UX-4 : « " + e.text + " » démarre à gauche du cadre (x="
                            + e.x + " < cadre x=" + m.x + ")",
                    e.x >= m.x);
            assertTrue("UX-4 : « " + e.text + " » démarre hors carte à droite",
                    e.x <= m.x + m.w);
        }
        // Le premier texte (en-tête) part bien de contentLeft + 12.
        float firstX = shadow.getDrawnTextEvent(0).x;
        assertEquals("l'en-tête part de contentLeft + 12 dp",
                m.contentLeft + 12f, firstX, 0.5f);
        // La carte est dessinée (round rect) à la position de la métrique.
        assertTrue("la carte est dessinée", shadow.getRoundRectPaintHistoryCount() > 0);
        assertEquals("le round rect suit la métrique (bord gauche)",
                m.x, shadow.getDrawnRoundRect(0).rect.left, 0.5f);
    }

    @Test
    public void detailADroite_toutLeTexteDansLaCarte() {
        EditorView view = newView();
        view.squigglePopupVisible = true;
        view.squigglePopupItem = new DiagnosticShift.Diagnostic(
                GREET, GREET + 5, 3,
                "Un message volontairement long pour forcer la pleine largeur "
                + "de la carte et ancrer le cadre a droite du viewport");
        view.squigglePopupChoices = Collections.emptyList();

        jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m =
                jo.codeeditor.view.popup.EditorPopupAnchors.squigglePopupMetrics(view);
        assertNotNull(m);
        assertTrue("précondition : le cadre est ancré à droite",
                m.x > W * 0.5f);
        assertToutLeTexteDansLaCarte(view, m);
    }

    @Test
    public void miniListeADroite_toutLeTexteDansLaCarte() {
        EditorView view = newView();
        view.squigglePopupVisible = true;
        view.squigglePopupItem = null;
        java.util.ArrayList<DiagnosticShift.Diagnostic> choices =
                new java.util.ArrayList<>();
        choices.add(new DiagnosticShift.Diagnostic(GREET, GREET + 5, 2,
                "Premier diagnostic superpose"));
        choices.add(new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3,
                "Second diagnostic superpose"));
        view.squigglePopupChoices = choices;

        jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m =
                jo.codeeditor.view.popup.EditorPopupAnchors.squigglePopupMetrics(view);
        assertNotNull(m);
        assertTrue(m.x > W * 0.5f);
        assertToutLeTexteDansLaCarte(view, m);
    }
}
