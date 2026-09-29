package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Régression du bug « je vois la bordure du rectangle mais pas la
 * couleur de sélection » pendant/après le drag d'une poignée.
 *
 * <p>Cause racine : la loupe et la minimap dessinent leurs contours
 * (anneau, rect de viewport) avec le paint PARTAGÉ {@code selPaint} en
 * {@code Style.STROKE} sans le remettre à {@code FILL}. La loupe est le
 * dernier peintre de la frame et s'active précisément à l'amorce du drag
 * de poignée : la frame suivante (et toutes celles du drag) dessinait la
 * bande de sélection en CONTOUR — le « rectangle sans couleur » vu par
 * l'utilisateur. La minimap laisse le même état derrière elle.</p>
 *
 * <p>Protocole (contrats d'état — le canvas shadow Robolectric ne
 * distingue pas FILL de STROKE à la rasterisation, donc les assertions
 * portent sur l'état du paint partagé que le rendu NATIF du device
 * applique réellement) :</p>
 * <ol>
 *   <li>une frame de loupe/minimap doit laisser {@code selPaint} en FILL ;</li>
 *   <li>le peintre de bande doit rétablir FILL même si on lui donne un
 *       paint corrompu en STROKE (défense en profondeur) ;</li>
 *   <li>la bande de sélection et les occurrences de recherche restent
 *       visibles (pixels de couleur) après ces frames.</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class SelectionColorAfterMagnifierTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int W = 1080;
    private static final int H = 1920;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(W, H);
        view.layout(0, 0, W, H);
        injectMetrics(view, LINE_H, CHAR_W);
        Bitmap warm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(warm));
        warm.recycle();
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

    /** Rect écran de la bande de sélection du mot « name » (ligne 2). */
    private static int[] bandRectOf(EditorView view) {
        Selection sel = view.getSession().getSelection();
        int s = Math.min(sel.start, sel.end);
        int e = Math.max(sel.start, sel.end);
        float[] pStart = view.caretScreenPos(s);
        float[] pEnd = view.caretScreenPos(e);
        int x1 = (int) Math.min(pStart[0], pEnd[0]);
        int x2 = (int) Math.max(pStart[0], pEnd[0]);
        int y1 = (int) pStart[1];
        int y2 = (int) (pStart[1] + view.metrics.getLineHeight());
        return new int[]{x1, y1, x2, y2};
    }

    /** Pose programmatiquement la sélection du mot « name ». */
    private static void selectName(EditorView view) {
        EditorDocument doc = view.getSession().getDocument();
        int lineStart = doc.lineStart(2);
        int col = doc.lineText(2).indexOf("name");
        view.getSession().setSelection(
                Selection.range(lineStart + col, lineStart + col + "name".length()));
    }

    /** Dessine une frame et compte les pixels de couleur de sélection dans la bande. */
    private static int frame(EditorView view) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        int[] r = bandRectOf(view);
        int n = 0;
        for (int y = Math.max(0, r[1]); y < Math.min(bmp.getHeight(), r[3]); y++) {
            for (int x = Math.max(0, r[0]); x < Math.min(bmp.getWidth(), r[2]); x++) {
                if (bmp.getPixel(x, y) == view.theme.selection) n++;
            }
        }
        bmp.recycle();
        return n;
    }

    // ── Contrat racine : les peintres de contour restaurent FILL ─────

    @Test
    public void magnifierFrame_leavesSelPaintFill() {
        EditorView view = newView();
        selectName(view);
        frame(view);

        view.magnifierActive = true;
        frame(view);
        assertEquals("après une frame de loupe, selPaint doit être revenu en FILL"
                        + " (sinon la frame suivante dessine la sélection en contour)",
                Paint.Style.FILL, view.selPaint.getStyle());
    }

    @Test
    public void magnifierFrames_repeat_leavesSelPaintFill() {
        EditorView view = newView();
        selectName(view);

        // Pendant tout le drag la loupe reste active : CHAQUE frame doit
        // se terminer avec le paint partagé en FILL.
        view.magnifierActive = true;
        for (int i = 0; i < 5; i++) {
            frame(view);
            assertEquals("frame loupe n°" + (i + 1) + " : selPaint doit être en FILL",
                    Paint.Style.FILL, view.selPaint.getStyle());
        }
    }

    @Test
    public void minimapFrame_leavesSelPaintFill() {
        EditorView view = newView();
        selectName(view);
        frame(view);

        view.minimapEnabled = true;
        frame(view);
        assertEquals("après une frame de minimap, selPaint doit être revenu en FILL",
                Paint.Style.FILL, view.selPaint.getStyle());
    }

    // ── Défense en profondeur : le peintre de bande force FILL ───────

    @Test
    public void bandPainter_restoresFill_evenWithCorruptedPaint() throws Exception {
        EditorView view = newView();
        // Sélectionner un mot SANS parenthèses adjacentes et vider la paire
        // de brackets : sinon drawBracketMatchBoxes rétablirait FILL en fin
        // de frame et masquerait l'objet du test (le peintre de bande).
        EditorDocument doc = view.getSession().getDocument();
        int lineStart = doc.lineStart(1);
        int col = doc.lineText(1).indexOf("void");
        view.getSession().setSelection(
                Selection.range(lineStart + col, lineStart + col + "void".length()));
        Field bp = EditorView.class.getDeclaredField("bracketPair");
        bp.setAccessible(true);
        bp.set(view, null);

        // Simuler un peintre fautif : paint partagé laissé en STROKE.
        view.selPaint.setStyle(Paint.Style.STROKE);
        view.selPaint.setStrokeWidth(3f);

        int n = frame(view);
        assertEquals("après une frame contenant une bande de sélection, selPaint"
                        + " doit être en FILL (le peintre de bande rétablit l'état)",
                Paint.Style.FILL, view.selPaint.getStyle());
        assertTrue("la bande doit rester peinte même avec un paint corrompu"
                + " (pixels=" + n + ")", n > 0);
    }

    // ── Visibilité : la bande reste peinte autour des frames loupe ───

    @Test
    public void selectionBandPainted_afterMagnifierFrames() {
        EditorView view = newView();
        selectName(view);
        int before = frame(view);
        assertTrue("la bande doit être peinte avant la loupe (pixels=" + before + ")",
                before > 0);

        view.magnifierActive = true;
        frame(view);
        frame(view);
        view.magnifierActive = false;
        int after = frame(view);
        assertTrue("après des frames de loupe, la bande de sélection doit rester"
                        + " peinte (pixels=" + after + ")",
                after > 0);
        assertEquals("et le paint partagé doit être en FILL",
                Paint.Style.FILL, view.selPaint.getStyle());
    }

    @Test
    public void findHighlightsPainted_afterMagnifierFrame() {
        EditorView view = newView();
        selectName(view);
        EditorDocument doc = view.getSession().getDocument();
        int wordStart = doc.lineStart(2) + doc.lineText(2).indexOf("name");
        view.findHighlights.add(new jo.codeeditor.find.Match(wordStart, wordStart + 4));

        // Référence : l'occurrence est visible sans loupe.
        Bitmap ref = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(ref));
        int[] r = bandRectOf(view);
        int reference = 0;
        for (int y = Math.max(0, r[1]); y < Math.min(ref.getHeight(), r[3]); y++) {
            for (int x = Math.max(0, r[0]); x < Math.min(ref.getWidth(), r[2]); x++) {
                int px = ref.getPixel(x, y);
                if (px == view.theme.findMatch || px == view.theme.findCurrent) reference++;
            }
        }
        ref.recycle();
        assertTrue("l'occurrence de recherche doit être peinte (pixels=" + reference + ")",
                reference > 0);

        view.magnifierActive = true;
        frame(view);
        view.magnifierActive = false;

        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        int n = 0;
        for (int y = Math.max(0, r[1]); y < Math.min(bmp.getHeight(), r[3]); y++) {
            for (int x = Math.max(0, r[0]); x < Math.min(bmp.getWidth(), r[2]); x++) {
                int px = bmp.getPixel(x, y);
                if (px == view.theme.findMatch || px == view.theme.findCurrent) n++;
            }
        }
        bmp.recycle();
        assertTrue("les occurrences de recherche doivent rester peintes après une"
                        + " frame de loupe (pixels=" + n + ", référence=" + reference + ")",
                n > 0);
        assertEquals("paint partagé en FILL après la frame de loupe",
                Paint.Style.FILL, view.selPaint.getStyle());
    }
}
