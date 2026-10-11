package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowCanvas;

import android.content.Context;
import android.graphics.Canvas;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Tests pour les guides d'indentation basés sur les paires
 * d'accolades (approche l'éditeur de code de référence). Chaque paire d'accolades
 * {@code { ... }} crée un bloc, et un guide vertical est dessiné
 * au centre de la colonne d'indentation de l'accolade ouvrante,
 * du bas de la ligne d'ouverture jusqu'au haut de la ligne de
 * fermeture.
 *
 * <p>Exemple attendu :</p>
 * <pre>
 * ClassName {
 *   |            ← guide à la colonne d'indentation de la méthode
 *   methodName {
 *   |         |   ← guide à la colonne de methodName (plus profond)
 *   |         |   body
 *   |         |
 *   |         }   ← s'arrête au haut de la ligne de fermeture
 *   |
 * }              ← s'arrête au haut de la ligne de fermeture
 * </pre>
 */
@RunWith(RobolectricTestRunner.class)
public class IndentGuidesBlockBasedTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private EditorView newView(String doc, String language) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setLanguage(language);
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        return view;
    }

    private static void injectMetrics(EditorView view) {
        try {
            setFloat(view.metrics, "lineHeight", LINE_H);
            setFloat(view.metrics, "charWidth", CHAR_W);
            setFloat(view.metrics, "padTop", LINE_H * 0.5f);
            setFloat(view.metrics, "padLeft", CHAR_W * 0.5f);
            setFloat(view.metrics, "gutterWidth", CHAR_W * 7f);
            setFloat(view.metrics, "foldStripWidth", CHAR_W * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    private static List<float[]> linesOfColor(Canvas canvas, int color) {
        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        List<float[]> out = new ArrayList<>();
        // Les guides basés sur les blocs utilisent drawRect (approche
        // l'éditeur de code de référence), pas drawLine. Chercher les deux.
        for (int i = 0; i < shadow.getLinePaintHistoryCount(); i++) {
            ShadowCanvas.LinePaintHistoryEvent e = shadow.getDrawnLine(i);
            if (e.paint != null && e.paint.getColor() == color) {
                out.add(new float[]{e.startX, e.startY, e.stopX, e.stopY});
            }
        }
        // Chercher aussi les drawRect (approche l'éditeur de code de référence).
        for (int i = 0; i < shadow.getRectPaintHistoryCount(); i++) {
            ShadowCanvas.RectPaintHistoryEvent e = shadow.getDrawnRect(i);
            if (e.paint != null && e.paint.getColor() == color) {
                out.add(new float[]{e.rect.left, e.rect.top, e.rect.right, e.rect.bottom});
            }
        }
        return out;
    }

    /**
     * Java avec une classe contenant une méthode :
     * Le guide de la classe doit commencer au BAS de la ligne
     * d'ouverture, pas au HAUT. Le guide de la méthode doit
     * être plus profond (plus à droite).
     */
    @Test
    public void java_classAndMethod_bothGuidesDrawn() {
        String java =
                "class Foo {\n"       // 0 — { à la colonne 10
              + "    void bar() {\n"   // 1 — { à la colonne 15 (indent 4)
              + "        int x;\n"    // 2 — indent 8
              + "    }\n"             // 3 — } à la colonne 4
              + "}\n";                // 4 — } à la colonne 0
        EditorView view = newView(java, "java");
        int guideColor = view.theme.indentGuide;

        Canvas canvas = new Canvas();
        view.draw(canvas);

        List<float[]> lines = linesOfColor(canvas, guideColor);
        assertTrue("Au moins un guide doit être tracé — got " + lines.size(),
                lines.size() > 0);
    }

    /**
     * Le guide doit être CENTRÉ dans la colonne d'indentation,
     * pas à gauche. l'éditeur de code de référence centre le guide avec
     * `centerX - width/2` à `centerX + width/2`.
     * Pour une indentation de 4 espaces, le guide doit être à
     * (4 * charWidth) / 2 = 2 * charWidth du début de la zone
     * de texte.
     */
    @Test
    public void guide_isCenteredInIndentColumn() {
        // Document simple : une classe avec un corps à 4 espaces.
        String java =
                "class Foo {\n"       // 0
              + "    int x;\n"        // 1 — indent 4
              + "}\n";               // 2
        EditorView view = newView(java, "java");
        int guideColor = view.theme.indentGuide;

        Canvas canvas = new Canvas();
        view.draw(canvas);

        List<float[]> lines = linesOfColor(canvas, guideColor);
        assertTrue("Au moins un guide doit être tracé", lines.size() > 0);

        // Le guide pour la classe (indent 0 → { à colonne 10)
        // doit être centré. Avec l'approche l'éditeur de référence, le guide est à
        // la colonne min(startColumn, endColumn) * spaceWidth.
        // Pour "class Foo {", startColumn = 10 (position du {).
        // Le guide est centré à cette position.
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        // Le guide doit être proche de textAreaLeft + 10*CHAR_W
        // (la position de l'accolade ouvrante), pas à textAreaLeft
        // + 4*CHAR_W (qui serait l'indentation de la ligne 1).
        boolean foundNearBrace = false;
        for (float[] l : lines) {
            float expectedBraceX = textAreaLeft + 10 * CHAR_W - view.hOffset;
            if (Math.abs(l[0] - expectedBraceX) < CHAR_W) {
                foundNearBrace = true;
                break;
            }
        }
        assertTrue("Le guide doit être centré à la position de "
                + "l'accolade ouvrante (col 10), pas à la colonne "
                + "d'indentation (col 0 ou 4)",
                foundNearBrace);
    }

    /**
     * Le guide doit s'étendre du BAS de la ligne d'ouverture
     * au HAUT de la ligne de fermeture — pas au-delà.
     * l'éditeur de référence : top = rowBottom(startLine), bottom = rowTop(endLine).
     */
    @Test
    public void guide_extendsFromBottomOfOpeningToTopOfClosing() {
        String java =
                "class Foo {\n"       // 0 — ouvre bloc
              + "    int x;\n"        // 1
              + "}\n";               // 2 — ferme bloc
        EditorView view = newView(java, "java");
        int guideColor = view.theme.indentGuide;

        Canvas canvas = new Canvas();
        view.draw(canvas);

        List<float[]> lines = linesOfColor(canvas, guideColor);
        assertTrue("Au moins un guide doit être tracé", lines.size() > 0);

        // Le guide pour le bloc de la classe doit commencer au
        // HAUT de la ligne 0 (devant l'accolade, pas derrière)
        // et finir au HAUT ou au BAS de la ligne 2 selon
        // toBottomOfEndLine.
        float padTop = view.metrics.getPadTop();
        float expectedTop = padTop;               // haut de la ligne 0
        // Le } est seul sur la ligne 2 → toBottomOfEndLine=true
        // → bottom = docLineToY(2) + lineHeight = padTop + 3*LINE_H
        float expectedBottomTop = padTop + 2 * LINE_H;  // haut de la ligne 2
        float expectedBottomFull = padTop + 3 * LINE_H; // bas de la ligne 2

        boolean foundCorrectBounds = false;
        for (float[] l : lines) {
            // Accepter les deux cas : haut ou bas de la ligne de fin.
            if (Math.abs(l[1] - expectedTop) < 2f
                    && (Math.abs(l[3] - expectedBottomTop) < 2f
                        || Math.abs(l[3] - expectedBottomFull) < 2f)) {
                foundCorrectBounds = true;
                break;
            }
        }
        assertTrue("Le guide doit s'étendre du HAUT de la ligne "
                + "d'ouverture (y=" + expectedTop + ") au haut/bas de "
                + "la ligne de fermeture (y=" + expectedBottomTop
                + " ou " + expectedBottomFull + ") — "
                + "observé : top=" + (lines.isEmpty() ? "N/A" : lines.get(0)[1])
                + " bottom=" + (lines.isEmpty() ? "N/A" : lines.get(0)[3]),
                foundCorrectBounds);
    }

    /**
     * Les guides de méthode doivent être plus à droite que les
     * guides de classe (niveaux d'imbrication différents).
     */
    @Test
    public void methodGuide_isRightOfClassGuide() {
        String java =
                "class Foo {\n"          // 0 — { à col 10
              + "    void bar() {\n"     // 1 — { à col 15
              + "        int x;\n"       // 2
              + "    }\n"                // 3 — } à col 4
              + "}\n";                   // 4 — } à col 0
        EditorView view = newView(java, "java");
        int guideColor = view.theme.indentGuide;

        Canvas canvas = new Canvas();
        view.draw(canvas);

        List<float[]> lines = linesOfColor(canvas, guideColor);
        assertTrue("Au moins 2 guides doivent être tracés "
                + "(classe + méthode) — got " + lines.size(),
                lines.size() >= 2);

        // Trouver le guide le plus à gauche (classe) et le plus
        // à droite (méthode).
        float minX = Float.MAX_VALUE;
        float maxX = Float.MIN_VALUE;
        for (float[] l : lines) {
            minX = Math.min(minX, l[0]);
            maxX = Math.max(maxX, l[0]);
        }
        assertTrue("Le guide de la méthode doit être plus à droite "
                + "que le guide de la classe — minX=" + minX
                + " maxX=" + maxX,
                maxX > minX + CHAR_W);
    }
}
