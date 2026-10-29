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

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 4.14.1 — verrouille la géométrie du texte fantôme des inlay hints :
 * plus d'espaces fantômes, plus de hint flottant, plus de chevauchement
 * en mode ligatures. La référence est le rendu bureau (consulté chez
 * JetBrains : {@code PresentationFactory.smallText} dessine ses hints à
 * la taille éditeur −1 sur une largeur MESURÉE avec insets 1-3 px ;
 * VS Code, éditeur à grille monospace comme le nôtre, les dessine à la
 * TAILLE DU CODE — une cellule par caractère). Sur notre grille, la
 * police réduite à 85 % d'avant réservait des cellules pleines que son
 * encre ne remplissait pas : « String » tissait 7 cellules (6 lettres +
 * le padding LSP) mais n'en encrait que ~5,2 — ~1,8 cellule de vide
 * invisible, LISIBLE comme des espaces tapés par un fantôme.
 *
 * <ul>
 *   <li><b>Taille pleine</b> — le hint est encré à la taille du code :
 *       un caractère fantôme avance EXACTEMENT sa cellule, le padding
 *       LSP devient exactement UN espace propre (sémantique VS Code,
 *       fidèle à l'intention de la spec LSP) ;</li>
 *   <li><b>Baseline commune</b> — le hint partage la baseline du code
 *       (0,78 × hauteur de ligne, celle de drawRawRange) : avant, il
 *       flottait à 0,75, 3 % de ligne AU-DESSUS du texte qu'il habille ;</li>
 *   <li><b>Ancrage exact</b> — le hint commence à SA colonne visuelle,
 *       sans le nudge magique « +0,08 cellule » d'avant ; le code qui
 *       suit commence à hintX + len × charWidth : NI trou NI
 *       chevauchement à la frontière ;</li>
 *   <li><b>Ligatures cèdent au tissage</b> — le chemin StaticLayout
 *       façonne le texte brut aux colonnes brutes ; une ligne à hint
 *       retombe sur le chemin tissé (drawRawRange) au lieu de dessiner
 *       le hint PAR-DESSUS le code non décalé.</li>
 * </ul>
 *
 * <p>Mise en scène : la ligne «        int count = greet(name); » avec
 * un hint type « : int » (col 17, après « count ») et un hint lambda
 * « String  » (col 26, avant « name », padding droit comme le pont LSP
 * paddingLeft/paddingRight les tisse). Canvas d'enregistrement enrichi
 * (x, y, taille) — pattern InlayKindStylingTest.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class InlayPhantomSpacingTest {

    private static final String DOC =
        "public class Main {\n"
        + "    void run() {\n"
        + "        int count = greet(name);\n"
        + "    }\n"
        + "}\n";

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    /** gouttière (7 × CHAR_W) + padLeft (CHAR_W/2) — cf. TestMetrics. */
    private static final float TEXT_LEFT = CHAR_W * 7f + CHAR_W * 0.5f;
    /** Colonne brute de « name » dans la ligne 2. */
    private static final int COL_NAME = 26;
    /** Colonne brute juste après « count » (8 espaces + « int count »). */
    private static final int COL_AFTER_COUNT = 17;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        return view;
    }

    /** Événement texte d'un rendu : texte + position + taille du paint
     *  à l'instant du draw (copie des valeurs, pas référence). */
    private static final class TextEvent {
        final String text;
        final float x;
        final float y;
        final float textSize;
        TextEvent(String text, float x, float y, float textSize) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.textSize = textSize;
        }
    }

    /** Canvas d'enregistrement : capture chaque drawText avec x, y et
     *  la taille du paint courants (le paint partagé est muté au fil des
     *  couches — une référence capturerait l'état du DERNIER draw). */
    private static final class RecordingCanvas extends Canvas {
        final List<TextEvent> events = new ArrayList<>();
        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            events.add(new TextEvent(text, x, y, paint.getTextSize()));
            super.drawText(text, x, y, paint);
        }
        @Override
        public void drawText(String text, int start, int end,
                float x, float y, Paint paint) {
            events.add(new TextEvent(text.substring(start, end),
                    x, y, paint.getTextSize()));
            super.drawText(text, start, end, x, y, paint);
        }
        @Override
        public void drawText(CharSequence text, int start, int end,
                float x, float y, Paint paint) {
            events.add(new TextEvent(text.subSequence(start, end).toString(),
                    x, y, paint.getTextSize()));
            super.drawText(text, start, end, x, y, paint);
        }
    }

    private static List<TextEvent> drawnTexts(EditorView view) {
        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);
        return canvas.events;
    }

    private static TextEvent findExact(List<TextEvent> events, String needle) {
        for (TextEvent e : events) {
            if (e.text.equals(needle)) return e;
        }
        return null;
    }

    private static TextEvent findContaining(List<TextEvent> events, String needle) {
        for (TextEvent e : events) {
            if (e.text.contains(needle)) return e;
        }
        return null;
    }

    /** Un événement de CODE (pas un hint : texte différent, taille
     *  pleine) commençant exactement à la colonne visuelle donnée. */
    private static TextEvent codeEventAtCol(List<TextEvent> events,
            String hintText, int visualCol) {
        float expectedX = TEXT_LEFT + visualCol * CHAR_W;
        for (TextEvent e : events) {
            if (e.text.equals(hintText)) continue;
            if (Math.abs(e.x - expectedX) < 0.01f) return e;
        }
        return null;
    }

    @Test
    public void hint_plein_gabarit_sur_la_baseline_du_code() {
        EditorView view = newView();
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(
                DOC.indexOf("int count") + "int count".length(),
                ": int", false, "type"));
        hints.add(new DiagnosticShift.InlayHint(
                DOC.indexOf("greet(") + "greet(".length(),
                "name: ", false, "parameter"));
        view.getSession().setInlayHints(hints);

        List<TextEvent> events = drawnTexts(view);
        TextEvent hint = findContaining(events, ": int");
        TextEvent keyword = findExact(events, "int");
        assertNotNull("le hint « : int » doit être tissé dans la ligne", hint);
        assertNotNull("sanité : le mot-clé « int » doit être dessiné", keyword);

        // Taille PLEINE — plus de 85 % : l'encre du fantôme remplit ses
        // cellules, la taille ne distingue plus le hint du code (c'est
        // le travail de l'alpha et de l'italique).
        assertEquals("le hint doit être encré à la taille du code (plus de"
                + " 85 % — sur une grille monospace, une police réduite"
                + " réserve des cellules que son encre ne remplit pas :"
                + " les espaces fantômes)",
                view.metrics.getTextSize(), hint.textSize, 0.01f);

        // Baseline COMMUNE — 0,78 comme drawRawRange : le hint pose ses
        // lettres sur la même ligne de pied que le code qu'il habille
        // (avant : 0,75 — le fantôme flottait au-dessus du texte).
        float baselineAttendue = view.docLineToY(2) - view.scroll.vOffset
                + LINE_H * 0.78f;
        assertEquals("le hint doit partager la baseline du code",
                baselineAttendue, hint.y, 0.01f);
        assertEquals("sanité : le code est bien dessiné à cette baseline",
                baselineAttendue, keyword.y, 0.01f);
    }

    @Test
    public void padding_lsp_un_espace_net_ni_trou_ni_chevauchement() {
        EditorView view = newView();
        // « : int » (5 car.) tissé à la col 17, « String  » (padding
        // droit du pont LSP, 7 car. dont l'espace final) à la col 26.
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(
                DOC.indexOf("int count") + "int count".length(),
                ": int", false, "type"));
        int lineStart = DOC.indexOf("int count") - 8;
        hints.add(new DiagnosticShift.InlayHint(
                lineStart + COL_NAME, "String ", false, "type"));
        view.getSession().setInlayHints(hints);

        List<TextEvent> events = drawnTexts(view);
        // Le hint « String  » s'ancre à rawToVisual[26] = 26 + 5 (« : int »
        // tissé avant lui) — SANS le nudge « +0,08 cellule » d'avant.
        int visAnchor = COL_NAME + ": int".length();
        TextEvent hint = null;
        for (TextEvent e : events) {
            if (e.text.equals("String ")) {
                hint = e;
                break;
            }
        }
        assertNotNull("le hint « String  » (padding droit) doit être tissé",
                hint);
        assertEquals("le hint s'ancre à SA colonne visuelle, sans nudge",
                TEXT_LEFT + visAnchor * CHAR_W, hint.x, 0.01f);

        // La frontière honnête : le code APRÈS le hint commence à
        // hintX + len × charWidth. À taille pleine monospace, l'encre de
        // « String  » avance exactement ses 7 cellules (6 lettres + le
        // padding) — le « name » qui suit n'est NI respacé (le trou
        // fantôme d'avant : ~1,8 cellule de vide) NI recouvert.
        int afterHint = visAnchor + "String ".length();
        TextEvent code = codeEventAtCol(events, "String ", afterHint);
        assertNotNull("le code qui suit le hint doit commencer exactement"
                + " à une cellule de sa fin (col visuelle " + afterHint
                + ") — ni trou fantôme ni chevauchement", code);

        // Sanité du repère : « int » (col 8, avant tout hint) reste à sa
        // colonne — le système de coordonnées n'a pas glissé.
        TextEvent keyword = findExact(events, "int");
        assertNotNull(keyword);
        assertEquals(TEXT_LEFT + 8 * CHAR_W, keyword.x, 0.01f);
    }

    @Test
    public void ligatures_cedent_au_tissage_pas_de_chevauchement() {
        EditorView view = newView();
        view.setFontLigatures(true);
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        int lineStart = DOC.indexOf("int count") - 8;
        hints.add(new DiagnosticShift.InlayHint(
                lineStart + COL_NAME, "String ", false, "type"));
        view.getSession().setInlayHints(hints);

        List<TextEvent> events = drawnTexts(view);
        TextEvent hint = null;
        for (TextEvent e : events) {
            if (e.text.equals("String ")) {
                hint = e;
                break;
            }
        }
        assertNotNull("le hint doit être tissé même en mode ligatures", hint);
        assertEquals("le hint s'ancre à sa colonne (26, premier hint de la"
                + " ligne)",
                TEXT_LEFT + COL_NAME * CHAR_W, hint.x, 0.01f);

        // Avant 4.14.1, le chemin StaticLayout dessinait la ligne ENTIÈRE
        // aux colonnes BRUTES : « name » restait à la col 26 et le hint
        // l'écrivait PAR-DESSUS. Le tissage doit rester actif : le code
        // après le hint est décalé d'exactement sa longueur.
        TextEvent code = codeEventAtCol(events, "String ",
                COL_NAME + "String ".length());
        assertNotNull("en mode ligatures, le code qui suit le hint doit"
                + " être décalé à la col visuelle "
                + (COL_NAME + "String ".length())
                + " — le StaticLayout brut aurait laissé « name » sous le"
                + " hint (chevauchement)", code);
        assertTrue("sanité : un vrai événement de code a été trouvé",
                code.text.length() > 0);
    }
}
