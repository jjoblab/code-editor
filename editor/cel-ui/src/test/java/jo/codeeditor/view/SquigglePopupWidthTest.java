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
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * UX-3 (retour terrain J3) — verrouille la <b>largeur ajustée au
 * contenu</b> du popup ancré de diagnostic (D1) : elle était TOUJOURS le
 * maximum (320 dp) — un message court ouvrait une carte immense. La
 * carte se resserre désormais sur sa plus large pièce, bornée
 * [132 dp, 320 dp].
 *
 * <ul>
 *   <li><b>Message court</b> → largeur minimale (132 dp) ;</li>
 *   <li><b>Message moyen</b> → largeur du message + paddings (entre les
 *       bornes) ;</li>
 *   <li><b>Message long (replié)</b> → pleine largeur (il a besoin de
 *       la place pour se replier — msgLines calculé pour elle) ;</li>
 *   <li><b>Mini-liste</b> → la plus large 1re ligne gouverne ; une
 *       rangée longue garde la pleine largeur ;</li>
 *   <li><b>Cohérence</b> → la carte resserrée reste dans le viewport et
 *       le hit-test suit la NOUVELLE largeur (source unique).</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SquigglePopupWidthTest {

    private static final String DOC =
        "public class Main {\n"
        + "    void run() {\n"
        + "        greet(name);\n"
        + "    }\n"
        + "}\n";

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Robolectric : density = 1 → MIN = 132 px, MAX = min(320, 1068). */
    private static final float MIN_W = 132f;
    private static final float MAX_W = 320f;

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

    /** Arme l'état du popup DÉTAILLÉ (sans geste — la métrique est testée
     *  en direct, la cascade tap est couverte par SquiggleTap*Test). */
    private static void armDetail(EditorView view, String message) {
        view.squigglePopupVisible = true;
        view.squigglePopupItem = new DiagnosticShift.Diagnostic(
                GREET, GREET + 5, 3, message);
        view.squigglePopupChoices = Collections.emptyList();
    }

    private static void armList(EditorView view, String... messages) {
        view.squigglePopupVisible = true;
        view.squigglePopupItem = null;
        java.util.ArrayList<DiagnosticShift.Diagnostic> choices =
                new java.util.ArrayList<>();
        int off = GREET;
        for (String msg : messages) {
            choices.add(new DiagnosticShift.Diagnostic(off, off + 5, 2, msg));
            off += 6;
        }
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
    public void messageCourt_largeurMinimale() {
        EditorView view = newView();
        armDetail(view, "Missing ';'");
        // measureText Robolectric ≈ 1 px/caractère : 11 px + paddings ≪ MIN.
        float w = metrics(view).w;
        assertEquals("un message court resserre la carte au minimum",
                MIN_W, w, 2f);
    }

    @Test
    public void messageMoyen_largeurDuMessage() {
        EditorView view = newView();
        // 160 caractères ≈ 160 px + 2×12 padding + 4 sécurité = 188 px
        // — strictement entre les bornes.
        StringBuilder sb = new StringBuilder(
                "Expected ';' but found expression end ");
        while (sb.length() < 160) sb.append('x');
        armDetail(view, sb.toString());
        float w = metrics(view).w;
        assertTrue("un message moyen dimensionne la carte sur son contenu"
                + " (w=" + w + ")",
                w > MIN_W + 1f && w < MAX_W - 1f);
        assertEquals(188f, w, 4f);
    }

    @Test
    public void messageLong_pleineLargeur() {
        EditorView view = newView();
        // 350 caractères ≈ 350 px > 320-24 : le message se REPLIE — il
        // a besoin de la pleine largeur (msgLines calculé pour elle).
        StringBuilder sb = new StringBuilder(
                "Cannot resolve method 'greet' in class Main: ");
        while (sb.length() < 350) sb.append("argument x ");
        armDetail(view, sb.toString());
        float w = metrics(view).w;
        assertEquals("un message replié garde la pleine largeur",
                MAX_W, w, 0.5f);
    }

    @Test
    public void miniListe_plusLargePremièreLigneGouverne() {
        EditorView view = newView();
        armList(view, "Unused variable", "Missing return");
        float w = metrics(view).w;
        assertEquals("deux 1res lignes courtes → carte minimale", MIN_W, w, 2f);

        // Une rangée LONGUE (≥ 300 px) → pleine largeur.
        armList(view, "Unused variable",
                "x".repeat(320) + " longue rangée de diagnostic");
        w = metrics(view).w;
        assertEquals("une rangée longue garde la pleine largeur", MAX_W, w, 0.5f);
    }

    @Test
    public void carteResserrée_dansLeViewport_hitTestCohérent() {
        EditorView view = newView();
        armDetail(view, "Missing ';'");
        jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m =
                metrics(view);
        assertTrue("la carte reste dans le viewport",
                m.x >= 0 && m.x + m.w <= view.getWidth() + 0.5f);
        assertTrue("clampée hors de la gouttière",
                m.x >= view.metrics.getGutterWidth() - 1f);
        // Le hit-test suit la NOUVELLE largeur (source unique) : un point
        // juste au-delà du bord droit resserré est HORS panneau.
        assertEquals("au-delà du bord droit resserré → hors panneau",
                -1, m.hit(m.x + m.w + 20f, m.y + m.h * 0.5f));
        // Et la rangée « See all » reste actionnable à l'intérieur.
        assertTrue("la rangée See all reste tappable",
                m.hit(m.x + m.w * 0.5f, m.seeAllY + m.rowH * 0.5f) == m.rows);
    }
}
