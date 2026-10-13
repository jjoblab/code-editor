package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * UX-4 (retour terrain post-J6) — verrouille le correctif « le contenu
 * du popup ancré suit la carte » : le tap sur un squiggle situé À DROITE
 * de l'écran plaçait correctement le CADRE (ancrage au mot, clamp X) mais
 * dessinait le CONTENU au bord GAUCHE de la vue (padX absolu) — le cadre
 * à droite, le message à gauche.
 *
 * <p>Le contrat désormais : la métrique partagée expose
 * {@code contentLeft} (bord gauche du contenu = {@code x + padX}), la
 * passe de dessin n'a plus AUCUN X calculé seule. Vérifications :</p>
 * <ul>
 *   <li><b>ancre à droite</b> — contentLeft vit dans la moitié DROITE de
 *       la vue (il suivait le cadre), exactement {@code x + 12} ;</li>
 *   <li><b>ancre à gauche</b> — même contrat : le contenu reste DANS la
 *       carte (contentLeft = x + 12, pas au bord de la vue) ;</li>
 *   <li><b>mini-liste</b> — le mode multi-diagnostics suit le même
 *       contrat ;</li>
 *   <li><b>hit-test d'accord</b> — un tap au contentLeft sur la rangée
 *       « See all » renvoie la sentinelle (source unique de vérité).</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SquigglePopupContentFollowsCardTest {

    /** Ligne longue : « greet » atterrit vers la colonne 89 → ancre à DROITE. */
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
    private static final int GREET_LEFT = DOC.indexOf("run");

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

    private static void armDetail(EditorView view, int start, int end, String message) {
        view.squigglePopupVisible = true;
        view.squigglePopupItem = new DiagnosticShift.Diagnostic(
                start, end, 3, message);
        view.squigglePopupChoices = Collections.emptyList();
    }

    private static void armList(EditorView view, int start, int end) {
        view.squigglePopupVisible = true;
        view.squigglePopupItem = null;
        java.util.ArrayList<DiagnosticShift.Diagnostic> choices =
                new java.util.ArrayList<>();
        choices.add(new DiagnosticShift.Diagnostic(start, end, 2,
                "Premier diagnostic superposé"));
        choices.add(new DiagnosticShift.Diagnostic(start, end, 3,
                "Second diagnostic superposé"));
        view.squigglePopupChoices = choices;
    }

    private static jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics metrics(
            EditorView view) {
        jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m =
                jo.codeeditor.view.popup.EditorPopupAnchors.squigglePopupMetrics(view);
        assertNotNull(m);
        return m;
    }

    @Test
    public void ancreADroite_leContenuVitDansLaMoitieDroite() {
        EditorView view = newView();
        // Message long → pleine largeur (320 dp) → clamp X à droite.
        armDetail(view, GREET, GREET + 5,
                "Un message volontairement long pour forcer la pleine largeur "
                + "de la carte et l'ancrage clame a droite du viewport");
        jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m = metrics(view);

        assertTrue("le cadre doit etre ancre a droite (clamp X)",
                m.x + m.w > W * 0.75f);
        assertTrue("le cadre est a droite de la moitie de la vue",
                m.x > W * 0.5f);
        // Le contenu suit : dans la moitie DROITE, exactement x + padX.
        assertTrue("UX-4 : le contenu suit la carte a droite",
                m.contentLeft > W * 0.5f);
        assertEquals("contentLeft = x + 12 dp (Robolectric density 1)",
                m.x + 12f, m.contentLeft, 0.5f);
        assertTrue("le contenu reste DANS la carte",
                m.contentLeft >= m.x && m.contentLeft < m.x + m.w);
    }

    @Test
    public void ancreAGauche_leContenuResteDansLaCarte() {
        EditorView view = newView();
        armDetail(view, GREET_LEFT, GREET_LEFT + 3, "Erreur courte");
        jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m = metrics(view);

        // Carte a gauche (borne gouttiere + 4) : le contenu n'est PAS au
        // bord de la vue, il est DANS la carte.
        assertEquals("UX-4 : contentLeft = x + 12 dp aussi a gauche",
                m.x + 12f, m.contentLeft, 0.5f);
        assertTrue(m.x < W * 0.5f);
    }

    @Test
    public void miniListe_leContenuSuitLaCarte() {
        EditorView view = newView();
        armList(view, GREET, GREET + 5);
        jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m = metrics(view);

        assertTrue("la mini-liste s'ancre a droite du mot a droite",
                m.x > W * 0.5f);
        assertEquals("UX-4 : le contenu de la mini-liste suit la carte",
                m.x + 12f, m.contentLeft, 0.5f);
    }

    @Test
    public void hitTest_dAccordAvecLeContenu() {
        EditorView view = newView();
        armDetail(view, GREET, GREET + 5, "Erreur courte");
        jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m = metrics(view);

        // Un tap au CONTENT-LEFT sur la rangee « See all » la declenche :
        // le hit-test et le dessin parlent la meme geometrie.
        float px = m.contentLeft + 12f;
        float py = m.seeAllY + m.rowH * 0.5f;
        assertEquals("le tap au contentLeft touche la rangee See all",
                m.rows, m.hit(px, py));
        // Et hors carte a gauche du cadre : rien.
        assertEquals("rien a gauche du cadre",
                -1, m.hit(2f, m.seeAllY + m.rowH * 0.5f));
    }
}
