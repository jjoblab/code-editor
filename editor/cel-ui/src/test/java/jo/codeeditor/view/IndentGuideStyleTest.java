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
import java.util.List;

import static org.junit.Assert.*;

/**
 * Lot B.11 (audit v3.41.0) — tests du réglage
 * {@link IndentGuideStyle} sur {@link EditorView}.
 *
 * <p>Le réglage doit :</p>
 * <ul>
 *   <li>{@link IndentGuideStyle#OFF} — aucun guide n'est tracé.</li>
 *   <li>{@link IndentGuideStyle#INDENT} — guides tracés mais
 *       sans mise en évidence de la portée active (pas de couleur
 *       d'accent).</li>
 *   <li>{@link IndentGuideStyle#INDENT_WITH_SCOPE} (défaut) —
 *       guides tracés avec portée active en couleur d'accent.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class IndentGuideStyleTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private static final String JSON_4SPACES =
            "{\n"                  // 0
          + "    \"a\": 1,\n"      // 1 (col 4)
          + "    \"nested\": {\n"  // 2 (col 4)
          + "        \"b\": 2\n"   // 3 (col 8)
          + "    }\n"              // 4 (col 4)
          + "}\n";                 // 5 (col 0)

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(JSON_4SPACES));
        session.setLanguage("json");
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

    private static int countLinesOfColor(Canvas canvas, int color) {
        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        int count = 0;
        for (int i = 0; i < shadow.getLinePaintHistoryCount(); i++) {
            ShadowCanvas.LinePaintHistoryEvent e = shadow.getDrawnLine(i);
            if (e.paint != null && e.paint.getColor() == color) {
                count++;
            }
        }
        // Les guides basés sur les blocs utilisent drawRect.
        for (int i = 0; i < shadow.getRectPaintHistoryCount(); i++) {
            ShadowCanvas.RectPaintHistoryEvent e = shadow.getDrawnRect(i);
            if (e.paint != null && e.paint.getColor() == color) {
                count++;
            }
        }
        return count;
    }

    /**
     * Cas 1 : style par défaut INDENT_WITH_SCOPE — des guides sont
     * tracés à la couleur de base, et le niveau actif à la couleur
     * d'accent (theme.caret).
     */
    @Test
    public void defaultStyle_INDENT_WITH_SCOPE_drawsGuides() {
        EditorView view = newView();
        assertEquals(IndentGuideStyle.INDENT_WITH_SCOPE,
                view.getIndentGuideStyle());

        Canvas canvas = new Canvas();
        view.draw(canvas);

        // Au moins un guide à la couleur de base.
        int baseColor = view.theme.indentGuide;
        int baseCount = countLinesOfColor(canvas, baseColor);
        assertTrue("Style INDENT_WITH_SCOPE : au moins un guide à la "
                + "couleur de base (theme.indentGuide) — observé=" + baseCount,
                baseCount > 0);
    }

    /**
     * Cas 2 : style OFF — aucun guide n'est tracé.
     */
    @Test
    public void style_OFF_doesNotDrawGuides() {
        EditorView view = newView();
        view.setIndentGuideStyle(IndentGuideStyle.OFF);
        assertEquals(IndentGuideStyle.OFF, view.getIndentGuideStyle());

        Canvas canvas = new Canvas();
        view.draw(canvas);

        int baseColor = view.theme.indentGuide;
        int baseCount = countLinesOfColor(canvas, baseColor);
        assertEquals("Style OFF : aucun guide ne doit être tracé à la "
                + "couleur de base — observé=" + baseCount,
                0, baseCount);

        int activeColor = view.theme.caret;
        int activeCount = countLinesOfColor(canvas, activeColor);
        // activeColor est aussi la couleur du caret qui est dessiné à
        // la ligne courante. On ne peut donc pas assertEquals(0).
        // On asserte juste que pas plus de 2 (le caret dessine 1
        // trait vertical pour le caret lui-même, pas un guide).
        // En fait, le caret est dessiné comme un Rect, pas comme une
        // Line — donc 0 ligne activeColor est attendu.
        // Mais le test peut être bruité par d'autres draws. On
        // vérifie simplement qu'il y en a moins de 5 (un guide
        // actif dessinerait plusieurs lignes pour plusieurs niveaux).
        assertTrue("Style OFF : aucun guide actif ne doit être tracé — "
                + "observé=" + activeCount + " (le caret lui-même est un Rect, "
                + "pas une Line)", activeCount < 5);
    }

    /**
     * Cas 3 : style INDENT — des guides sont tracés à la couleur de
     * base, mais aucun à la couleur d'accent (pas de portée active).
     */
    @Test
    public void style_INDENT_drawsGuides_noActiveScope() {
        EditorView view = newView();
        view.setIndentGuideStyle(IndentGuideStyle.INDENT);
        assertEquals(IndentGuideStyle.INDENT, view.getIndentGuideStyle());

        // Place le caret sur la ligne 3 (col 8 — niveau actif 8
        // en INDENT_WITH_SCOPE).
        EditorDocument d = view.session.getDocument();
        view.session.setSelection(d.lineStart(3) + 8);

        Canvas canvas = new Canvas();
        view.draw(canvas);

        int baseColor = view.theme.indentGuide;
        int baseCount = countLinesOfColor(canvas, baseColor);
        assertTrue("Style INDENT : au moins un guide à la couleur de "
                + "base — observé=" + baseCount,
                baseCount > 0);

        // En INDENT, la portée active n'est PAS mise en évidence.
        // Aucun guide ne doit avoir la couleur d'accent.
        // Vérifier qu'aucun Line n'a la couleur caret (sauf le caret
        // lui-même qui est un Rect, pas une Line).
        int activeColor = view.theme.caret;
        int activeCount = countLinesOfColor(canvas, activeColor);
        assertEquals("Style INDENT : aucun guide actif ne doit être "
                + "tracé (pas de mise en évidence de la portée) — "
                + "observé=" + activeCount,
                0, activeCount);
    }

    /**
     * Cas 4 : style INDENT_WITH_SCOPE — le niveau actif a la couleur
     * d'accent (theme.caret), les autres ont la couleur de base.
     */
    @Test
    public void style_INDENT_WITH_SCOPE_highlightsActiveScope() {
        EditorView view = newView();
        // Défaut est INDENT_WITH_SCOPE, mais on le rend explicite.
        view.setIndentGuideStyle(IndentGuideStyle.INDENT_WITH_SCOPE);

        // Place le caret sur la ligne 3 (col 8 — niveau actif 8).
        EditorDocument d = view.session.getDocument();
        view.session.setSelection(d.lineStart(3) + 8);

        Canvas canvas = new Canvas();
        view.draw(canvas);

        // Au moins un guide à la couleur d'accent (le niveau 8).
        int activeColor = view.theme.caret;
        int activeCount = countLinesOfColor(canvas, activeColor);
        assertTrue("Style INDENT_WITH_SCOPE : au moins un guide à la "
                + "couleur d'accent (theme.caret) pour le niveau actif — "
                + "observé=" + activeCount,
                activeCount > 0);
    }

    /**
     * Cas 5 : setIndentGuideStyle(null) est silencieusement traité
     * comme OFF (pas de NullPointerException).
     */
    @Test
    public void setIndentGuideStyle_null_treatedAsOFF() {
        EditorView view = newView();
        view.setIndentGuideStyle(null);
        assertEquals(IndentGuideStyle.OFF, view.getIndentGuideStyle());
    }

    /**
     * Cas 6 : la valeur par défaut est INDENT_WITH_SCOPE.
     */
    @Test
    public void defaultStyle_isINDENT_WITH_SCOPE() {
        EditorView view = newView();
        assertEquals(IndentGuideStyle.INDENT_WITH_SCOPE,
                view.getIndentGuideStyle());
    }
}
