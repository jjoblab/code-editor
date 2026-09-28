package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Reproduction du bug : « la loupe fait perdre la couleur quand je
 * sélectionne puis je fais glisser ».
 *
 * <p>Cause (audit) : le cache de layouts ligatures signe ses entrées avec
 * la couleur COURANTE du paint partagé et cuit cette couleur dans le
 * layout servi. La loupe, appelée en fin de pipeline après les couches qui
 * mutent le paint (spans sémantiques, inlays, chrome), reconstruit donc
 * des layouts avec une couleur de base résiduelle fautive — le texte sans
 * span paraît décoloré, et le layout fautif reste en cache.</p>
 *
 * <p>Les deux premiers tests ÉCHOUENT sur le code actuel (reproduction) ;
 * le troisième verrouille l'invariant d'état du paint partagé.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class MagnifierColorLeakReproTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
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

    private static void send(EditorView view, int action, long down, long when,
                             float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, when, action, x, y, 0);
        view.onTouchEvent(e);
        e.recycle();
    }

    /** Position écran de la poignée de DÉBUT de sélection (hitTestHandle 1). */
    private float[] startHandlePos(EditorView view) {
        Selection sel = view.getSession().getSelection();
        float[] pos = view.caretScreenPos(sel.start);
        float density = view.getResources().getDisplayMetrics().density;
        return new float[]{pos[0], pos[1] + view.metrics.getLineHeight()
                + view.HANDLE_RADIUS_DP * density * 0.6f};
    }

    // ── Reproduction (échoue avant correctif) ────────────────────────

    @Test
    public void shapedLayout_baseColorMustBeThemeText_notResidualPaintColor() {
        EditorView view = newView();
        // Garantir que le styling initial a tourné (comme le rendu le fait).
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        bmp.recycle();

        List<StyledLine> styledLines = view.getSession().getStyledLines();
        assertTrue("le styling doit avoir produit des lignes", styledLines.size() > 2);
        String lineText = view.getSession().getDocument().lineText(2);
        StyledLine styled = styledLines.get(2);

        // 1. Rendu normal : couleur résiduelle = couleur de texte du thème.
        view.textPaint.setColor(view.theme.textColor);
        StaticLayout normal = view.shapedLayoutFor(lineText, styled, view.textPaint);
        assertNotNull(normal);
        assertEquals(view.theme.textColor, normal.getPaint().getColor());

        // 2. La loupe entre APRES les couches qui ont muté le paint partagé
        //    (spans sémantiques, inlays, chrome) : couleur résiduelle exotique.
        view.textPaint.setColor(0xFF123456);
        StaticLayout magnified = view.shapedLayoutFor(lineText, styled, view.textPaint);

        // (a) La couleur de base du layout servi ne doit PAS être la couleur
        // résiduelle du paint : c'est la couleur de texte du thème qui fait foi.
        assertEquals("la couleur de base du layout ne doit pas dépendre de la"
                + " couleur résiduelle du paint partagé",
                view.theme.textColor, magnified.getPaint().getColor());
    }

    @Test
    public void shapedLayout_noThrash_whenOnlyPaintColorChanges() {
        EditorView view = newView();
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp));
        bmp.recycle();

        List<StyledLine> styledLines = view.getSession().getStyledLines();
        String lineText = view.getSession().getDocument().lineText(2);
        StyledLine styled = styledLines.get(2);

        view.textPaint.setColor(view.theme.textColor);
        StaticLayout first = view.shapedLayoutFor(lineText, styled, view.textPaint);

        view.textPaint.setColor(0xFF123456);
        StaticLayout second = view.shapedLayoutFor(lineText, styled, view.textPaint);

        // (b) Le cache ne doit pas reconstruire le layout quand seule la
        // couleur du paint change (même texte, mêmes spans) : même instance.
        assertSame("le cache de layouts ne doit pas thrasher quand seule la"
                + " couleur du paint partagé change", first, second);
    }

    // ── Non-régression (invariant d'état du paint partagé) ────────────

    @Test
    public void fullDraw_withMagnifier_leavesTextPaintInSameStateAsWithout() {
        EditorView view = newView();
        int start = DOC.indexOf("greet");
        view.getSession().setSelection(Selection.range(start, start + 5));
        view.handlesVisible = true;
        // Neutraliser la couche qui passe APRÈS la loupe (minimap).
        view.minimapEnabled = false;

        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        try {
            // Référence : draw SANS loupe.
            view.magnifierActive = false;
            view.draw(canvas);
            int colorAfter = view.textPaint.getColor();
            float sizeAfter = view.textPaint.getTextSize();
            Typeface faceAfter = view.textPaint.getTypeface();

            // Même draw AVEC loupe active.
            view.magnifierActive = true;
            view.magnifierX = 300f;
            view.magnifierY = 400f;
            view.draw(canvas);

            assertEquals("la loupe ne doit pas laisser le paint partagé dans"
                    + " un état différent d'un draw sans loupe",
                    colorAfter, view.textPaint.getColor());
            assertEquals(sizeAfter, view.textPaint.getTextSize(), 0.01f);
            assertSame(faceAfter, view.textPaint.getTypeface());
        } finally {
            bmp.recycle();
        }
    }
}
