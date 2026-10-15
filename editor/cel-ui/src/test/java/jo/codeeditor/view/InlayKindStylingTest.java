package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * I1 (P1, lot 2 jalon J3) — verrouille le <b>portage du kind des inlay
 * hints jusqu'au rendu</b> : le modèle SPI et le traducteur LSP
 * transportent déjà le genre (type vs parameter), mais le pont le jetait
 * — tous les hints rendaient identiques (italique uniforme).
 *
 * <ul>
 *   <li><b>Paramètre</b> → faux italique conservé (skew −0.25) + alpha
 *       légèrement plus faible : le nom de paramètre est plus « fantôme »
 *       que le type ;</li>
 *   <li><b>Type</b> → droit (skew 0) + alpha courant : parité Android
 *       Studio (noms de paramètres en italique gris pâle, types de
 *       variables gris droits) ;</li>
 *   <li><b>Modèle interne</b> — le champ {@code kind} traverse le pont
 *       (constructeur 4-arg de {@code DiagnosticShift.InlayHint}) sans
 *       rompre l'existant (3-arg = « type » par défaut).</li>
 * </ul>
 *
 * <p>Mise en scène : deux hints ancrés sur une ligne VISIBLE — un type
 * « : String » après une variable, un paramètre « name: » avant un
 * argument. Le rendu complet capture chaque drawText avec le skew et la
 * couleur du paint À L'INSTANT du draw (canvas d'enregistrement —
 * pattern StickyStyledRenderTest : le paint est muté au fil des pièces,
 * une référence capturerait l'état final).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class InlayKindStylingTest {

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

    /** Événement texte d'un rendu : texte + skew + couleur du paint à
     *  l'instant du draw (copie des valeurs, pas référence du paint). */
    private static final class TextEvent {
        final String text;
        final float skewX;
        final int color;
        TextEvent(String text, float skewX, int color) {
            this.text = text;
            this.skewX = skewX;
            this.color = color;
        }
    }

    /** Canvas d'enregistrement : capture chaque drawText avec le skew et
     *  la couleur courants du paint (le paint est muté au fil des
     *  pièces — une référence capturerait l'état du DERNIER draw). */
    private static final class RecordingCanvas extends Canvas {
        final List<TextEvent> events = new ArrayList<>();
        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            events.add(new TextEvent(text, paint.getTextSkewX(), paint.getColor()));
            super.drawText(text, x, y, paint);
        }
        @Override
        public void drawText(String text, int start, int end,
                float x, float y, Paint paint) {
            events.add(new TextEvent(
                    text.substring(start, end), paint.getTextSkewX(), paint.getColor()));
            super.drawText(text, start, end, x, y, paint);
        }
        @Override
        public void drawText(CharSequence text, int start, int end,
                float x, float y, Paint paint) {
            events.add(new TextEvent(
                    text.subSequence(start, end).toString(),
                    paint.getTextSkewX(), paint.getColor()));
            super.drawText(text, start, end, x, y, paint);
        }
    }

    private static List<TextEvent> drawnTexts(EditorView view) {
        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);
        return canvas.events;
    }

    private static TextEvent find(List<TextEvent> events, String needle) {
        for (TextEvent e : events) {
            if (e.text.contains(needle)) return e;
        }
        return null;
    }

    @Test
    public void parametre_italique_type_droit_deux_styles_mesurables() {
        EditorView view = newView();
        // Ligne 2 visible : « int count = greet(name); »
        // — hint TYPE « : int » ancré après « count » ;
        // — hint PARAMÈTRE « name: » ancré avant « name ».
        int line2 = DOC.indexOf("int count");
        int afterCount = line2 + "int count".length();
        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(afterCount, ": int", false, "type"));
        hints.add(new DiagnosticShift.InlayHint(beforeName, "name: ", false, "parameter"));
        view.getSession().setInlayHints(hints);

        List<TextEvent> events = drawnTexts(view);
        TextEvent typeEvent = find(events, ": int");
        TextEvent paramEvent = find(events, "name:");
        assertNotNull("le hint type « : int » doit être rendu (tissé dans la ligne)",
                typeEvent);
        assertNotNull("le hint paramètre « name: » doit être rendu (tissé dans la ligne)",
                paramEvent);

        // Le PARAMÈTRE garde le faux italique (skew −0.25)…
        assertTrue("le hint paramètre doit être incliné (skew "
                + paramEvent.skewX + " ≈ −0.25, parité IntelliJ : noms de"
                + " paramètres en italique)",
                paramEvent.skewX < -0.15f);
        // …le TYPE est DROIT.
        assertTrue("le hint type doit être droit (skew "
                + typeEvent.skewX + " = 0, parité IntelliJ : types de"
                + " variables gris droits)",
                Math.abs(typeEvent.skewX) < 0.01f);
        // Deux styles distincts mesurables sur la même ligne.
        assertTrue("les deux genres doivent rendre avec deux skews distincts",
                Math.abs(paramEvent.skewX - typeEvent.skewX) > 0.1f);
    }

    @Test
    public void parametre_plus_efface_que_le_type() {
        EditorView view = newView();
        int line2 = DOC.indexOf("int count");
        int afterCount = line2 + "int count".length();
        int beforeName = DOC.indexOf("greet(") + "greet(".length();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(afterCount, ": int", false, "type"));
        hints.add(new DiagnosticShift.InlayHint(beforeName, "name: ", false, "parameter"));
        view.getSession().setInlayHints(hints);

        // L'alpha du PARAMÈTRE (0.75) est plus faible que celle du TYPE
        // (0.9) — la couleur est du gutterText atténué, seule la
        // VALEUR d'alpha distingue (comparaison des canaux alpha).
        List<TextEvent> events = drawnTexts(view);
        TextEvent typeEvent = find(events, ": int");
        TextEvent paramEvent = find(events, "name:");
        assertNotNull(typeEvent);
        assertNotNull(paramEvent);
        int alphaType = (typeEvent.color >>> 24) & 0xFF;
        int alphaParam = (paramEvent.color >>> 24) & 0xFF;
        assertTrue("le hint paramètre doit être PLUS effacé que le hint type"
                + " (alpha " + alphaParam + " < " + alphaType + ")",
                alphaParam < alphaType);
    }

    @Test
    public void constructeur_herite_type_par_defaut_sans_rupture() {
        // Le constructeur 3-arg historique reste « type » — zéro rupture
        // pour les appelants existants (le kind absent = droit).
        EditorView view = newView();
        int line2 = DOC.indexOf("int count");
        int afterCount = line2 + "int count".length();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(afterCount, ": int", false));
        view.getSession().setInlayHints(hints);

        List<TextEvent> events = drawnTexts(view);
        TextEvent legacy = find(events, ": int");
        assertNotNull(legacy);
        assertTrue("le hint sans kind (3-arg) rend DROIT comme un type",
                Math.abs(legacy.skewX) < 0.01f);
    }
}
