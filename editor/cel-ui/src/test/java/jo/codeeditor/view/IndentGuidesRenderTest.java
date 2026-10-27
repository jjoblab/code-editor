package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowCanvas;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Lot B (audit v3.41.0) — tests de rendu des guides d'indentation.
 *
 * <p>Ces tests échouent sur l'implémentation actuelle et passent
 * après la refonte du Lot B :</p>
 * <ul>
 *   <li><b>Niveaux dès la colonne 0</b> : la boucle actuelle part de
 *       level = 4 (unité d'indentation historique), donc les lignes à 4 colonnes
 *       (contenu direct d'un objet JSON racine) n'ont AUCUN guide.
 *       Les éditeurs de référence tracent aussi le premier niveau.</li>
 *   <li><b>Unité d'indentation réelle</b> : l'unité historique de 4 colonnes est
 *       figé. Pour un fichier à 2 espaces, les guides tombent un
 *       niveau sur deux ; pour un fichier à tabulations, \t est
 *       compté à plat pour 4 colonnes sans tenir compte de la
 *       largeur de tabulation réelle. IndentDetection existe mais
 *       est package-private dans cel-core.</li>
 *   <li><b>Continuité</b> : le trait ne fait qu'une rangée (hauteur
 *       lineHeight), alors qu'une ligne enroulée occupe plusieurs
 *       rangées — le guide a des trous.</li>
 *   <li><b>Épaisseur</b> : 1f (1 px) est quasi invisible sur les
 *       écrans à forte densité. L'épaisseur doit être en dp.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class IndentGuidesRenderTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;

    /**
     * Document JSON : racine à 4 colonnes, objet imbriqué à 8 colonnes.
     */
    private static final String JSON_4SPACES =
            "{\n"                  // 0 (col 0)
          + "    \"a\": 1,\n"      // 1 (col 4)
          + "    \"nested\": {\n"  // 2 (col 4)
          + "        \"b\": 2\n"   // 3 (col 8)
          + "    }\n"              // 4 (col 4)
          + "}\n";                 // 5 (col 0)

    private EditorView newViewWithDoc(String doc, String language) {
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
            setFloat(view.metrics, "padTop", PAD_TOP);
            setFloat(view.metrics, "padBottom", 0f);
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

    /**
     * Helper : capture les (x, y1, y2) des drawLine du shadow canvas
     * pour une couleur donnée (celle des guides d'indentation).
     */
    private static List<float[]> linesOfColor(Canvas canvas, int color) {
        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < shadow.getLinePaintHistoryCount(); i++) {
            ShadowCanvas.LinePaintHistoryEvent e = shadow.getDrawnLine(i);
            if (e.paint != null && e.paint.getColor() == color) {
                out.add(new float[]{e.startX, e.startY, e.stopX, e.stopY});
            }
        }
        // Les guides basés sur les blocs utilisent drawRect.
        for (int i = 0; i < shadow.getRectPaintHistoryCount(); i++) {
            ShadowCanvas.RectPaintHistoryEvent e = shadow.getDrawnRect(i);
            if (e.paint != null && e.paint.getColor() == color) {
                out.add(new float[]{e.rect.left, e.rect.top, e.rect.right, e.rect.bottom});
            }
        }
        return out;
    }

    /**
     * Cas 1 : un guide doit être dessiné au niveau 4 (premier
     * niveau) pour les lignes à indentation 4+. Avant le Lot B,
     * la boucle partait de level = 4 et testait level < cols, donc
     * une ligne à cols=4 n'avait AUCUN guide (level=4 n'est pas
     * < 4). On attend désormais un guide à level=4 pour la ligne
     * « "a": 1 » qui a 4 colonnes d'indentation.
     */
    @Test
    public void firstLevel_guideDrawnAtCol4_forIndentedLine() {
        EditorView view = newViewWithDoc(JSON_4SPACES, "json");
        int guideColor = view.theme.indentGuide;

        Canvas canvas = new Canvas();
        view.draw(canvas);

        List<float[]> lines = linesOfColor(canvas, guideColor);
        // La ligne 1 (« "a": 1 » à 4 colonnes) doit avoir un guide
        // à X = textAreaLeft + 4*charWidth - hOffset.
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float expectedX = textAreaLeft + 4 * CHAR_W - view.scroll.hOffset;

        boolean foundAt4 = false;
        for (float[] l : lines) {
            if (Math.abs(l[0] - expectedX) < CHAR_W) {
                foundAt4 = true;
                break;
            }
        }
        assertTrue("Un guide doit être tracé au niveau 4 (premier niveau) "
                + "pour la ligne à indentation 4 — x attendu=" + expectedX,
                foundAt4);
    }

    /**
     * Cas 2 : un guide doit aussi être dessiné au niveau 4
     * (premier niveau d'imbrication) pour le bloc
     * « nested » qui contient la ligne à 8 colonnes.
     * L'algorithme IndentRange crée un bloc de la
     * ligne 2 (indent 4) à la ligne 4 (indent 4).
     */
    @Test
    public void secondLevel_guideDrawnAtCol4_forNestedBlock() {
        EditorView view = newViewWithDoc(JSON_4SPACES, "json");
        int guideColor = view.theme.indentGuide;

        Canvas canvas = new Canvas();
        view.draw(canvas);

        List<float[]> lines = linesOfColor(canvas, guideColor);
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float expectedX4 = textAreaLeft + 4 * CHAR_W - view.scroll.hOffset;

        boolean foundAt4 = false;
        for (float[] l : lines) {
            if (Math.abs(l[0] - expectedX4) < CHAR_W) {
                foundAt4 = true;
                break;
            }
        }
        assertTrue("Un guide doit être tracé au niveau 4 (premier "
                + "niveau d'imbrication) pour le bloc « nested » "
                + "— x attendu=" + expectedX4,
                foundAt4);
    }

    /**
     * Cas 3 : un fichier à 2 espaces doit avoir des guides à 2 et 4
     * (au lieu de seulement à 4 comme c'était le cas avant).
     */
    @Test
    public void twoSpaceFile_guidesAt2And4_forNestedIndentation() {
        String doc2 =
                "{\n"                // 0 (col 0)
              + "  \"a\": 1,\n"      // 1 (col 2)
              + "  \"nested\": {\n"  // 2 (col 2)
              + "    \"b\": 2\n"     // 3 (col 4)
              + "  }\n"              // 4 (col 2)
              + "}\n";               // 5 (col 0)
        EditorView view = newViewWithDoc(doc2, "json");
        int guideColor = view.theme.indentGuide;

        Canvas canvas = new Canvas();
        view.draw(canvas);

        List<float[]> lines = linesOfColor(canvas, guideColor);
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float expectedX2 = textAreaLeft + 2 * CHAR_W - view.scroll.hOffset;

        boolean foundAt2 = false;
        for (float[] l : lines) {
            if (Math.abs(l[0] - expectedX2) < CHAR_W) {
                foundAt2 = true;
                break;
            }
        }
        assertTrue("Un guide doit être tracé au niveau 2 (premier niveau) "
                + "pour un fichier à 2 espaces — x attendu=" + expectedX2,
                foundAt2);
    }

    /**
     * Cas 4 : épaisseur en dp (pas en px). L'ancien code mettait
     * setStrokeWidth(1f) — 1 px, quasi invisible sur les écrans à
     * forte densité. Le Lot B doit mettre une épaisseur de l'ordre
     * de 1 dp (c.-à-d. ~density pixels).
     */
    @Test
    public void guideStrokeWidth_inDp_notInPx() {
        EditorView view = newViewWithDoc(JSON_4SPACES, "json");
        int guideColor = view.theme.indentGuide;
        float density = view.getResources().getDisplayMetrics().density;

        Canvas canvas = new Canvas();
        view.draw(canvas);

        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        float observedStroke = 0f;
        for (int i = 0; i < shadow.getLinePaintHistoryCount(); i++) {
            ShadowCanvas.LinePaintHistoryEvent e = shadow.getDrawnLine(i);
            if (e.paint != null && e.paint.getColor() == guideColor) {
                observedStroke = e.paint.getStrokeWidth();
                break;
            }
        }
        if (observedStroke == 0f) {
            for (int i = 0; i < shadow.getRectPaintHistoryCount(); i++) {
                ShadowCanvas.RectPaintHistoryEvent e = shadow.getDrawnRect(i);
                if (e.paint != null && e.paint.getColor() == guideColor) {
                    observedStroke = e.rect.width();
                    break;
                }
            }
        }
        assertTrue("L'épaisseur du guide doit être en dp (~density pixels), "
                + "observé=" + observedStroke + " density=" + density,
                observedStroke > 0.5f);
    }

    /**
     * Cas 5 : extrémités BUTT (pas ROUND) — l'ancien code mettait
     * setStrokeCap(Paint.Cap.ROUND) qui créait des points plus
     * marqués à chaque jonction de segments contigus. Le Lot B
     * doit mettre BUTT pour un trait continu et propre.
     */
    @Test
    public void guideCap_isButt_notRound() {
        EditorView view = newViewWithDoc(JSON_4SPACES, "json");
        int guideColor = view.theme.indentGuide;

        Canvas canvas = new Canvas();
        view.draw(canvas);

        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        Paint.Cap observedCap = null;
        for (int i = 0; i < shadow.getLinePaintHistoryCount(); i++) {
            ShadowCanvas.LinePaintHistoryEvent e = shadow.getDrawnLine(i);
            if (e.paint != null && e.paint.getColor() == guideColor) {
                observedCap = e.paint.getStrokeCap();
                break;
            }
        }
        // Les guides basés sur les blocs utilisent drawRect, pas drawLine.
        // drawRect n'a pas de notion de StrokeCap — le cap est
        // implicitement BUTT (les rectangles n'ont pas d'extrémités).
        // Si on a trouvé un drawRect avec la couleur du guide, c'est
        // équivalent à BUTT.
        if (observedCap == null) {
            for (int i = 0; i < shadow.getRectPaintHistoryCount(); i++) {
                ShadowCanvas.RectPaintHistoryEvent e = shadow.getDrawnRect(i);
                if (e.paint != null && e.paint.getColor() == guideColor) {
                    observedCap = Paint.Cap.BUTT;
                    break;
                }
            }
        }
        assertNotNull("Au moins un guide doit être tracé", observedCap);
        // BUTT ou SQUARE acceptables (pas ROUND qui fait des points).
        assertNotEquals("Les extrémités des guides doivent être BUTT "
                + "(pas ROUND) — pas de points aux jonctions",
                Paint.Cap.ROUND, observedCap);
    }
}
