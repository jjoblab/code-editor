package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Canvas;

import jo.codeeditor.session.EditorSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.view.chrome.EditorTheme;
import jo.codeeditor.view.chrome.GutterView;
import jo.codeeditor.view.chrome.LineNumberStyle;

import org.robolectric.shadows.ShadowCanvas;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * B12 (P1-4) — rendu des trois modes de numéros de gouttière sur shadow
 * canvas : ABSOLUTE (le historique, inchangé), RELATIVE (distance au
 * caret), HYBRID (numéro réel au caret, distances autour).
 *
 * <p>Modèle : 10 lignes, caret ligne 4 (0-based). Le drawText du shadow
 * capture chaque libellé DANS L'ORDRE des lignes (chemin historique sans
 * géométrie — itération séquentielle ligne 0 → 9, la gouttière ne
 * dessine AUCUN autre texte).</p>
 *
 * <p>Parité des invariants : le défaut d'une gouttière neuve reste
 * ABSOLUTE (zéro changement pour un hôte qui ne touche pas au style), la
 * ligne du caret reste surbrillée dans les trois modes (le gras est posé
 * par le même {@code lineIdx == currentLine} — non testé ici, le label
 * suffit), un caret invalide (négatif) retombe sur ABSOLUTE.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class LineNumberStyleTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float VIEW_H = 480f;

    /** Gouttière standalone + métriques injectées (pattern
     *  GutterWrapAlignmentTest — les shadows n'ont pas de vraie police). */
    private GutterView newGutter() {
        EditorMetrics metrics = new EditorMetrics();
        try {
            setFloat(metrics, "lineHeight", LINE_H);
            setFloat(metrics, "charWidth", CHAR_W);
            setFloat(metrics, "padTop", 0f);
            setFloat(metrics, "padBottom", 0f);
            setFloat(metrics, "gutterWidth", CHAR_W * 7f);
            setFloat(metrics, "foldStripWidth", CHAR_W * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new GutterView(metrics, EditorTheme.dark());
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    /** Libellés dessinés, dans l'ordre des lignes (10 lignes → 10 textes). */
    private List<String> labels(GutterView gutter, int currentLine) {
        Canvas canvas = new Canvas();
        gutter.draw(canvas, 0f, VIEW_H, 10, currentLine);
        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < shadow.getTextHistoryCount(); i++) {
            texts.add(shadow.getDrawnTextEvent(i).text);
        }
        return texts;
    }

    private static void assertLabel(String attendu, String obtenu, String contexte) {
        if (!attendu.equals(obtenu)) {
            fail(contexte + " : attendu « " + attendu + " », obtenu « " + obtenu + " »");
        }
    }

    @Test
    public void défaut_ABSOLUTE() {
        GutterView gutter = newGutter();
        List<String> texts = labels(gutter, 4);
        assertEquals(10, texts.size());
        for (int i = 0; i < 10; i++) {
            assertLabel(String.valueOf(i + 1), texts.get(i),
                    "ABSOLUTE ligne " + i + " (défaut)");
        }
    }

    @Test
    public void RELATIVE_distancesAuCaret() {
        GutterView gutter = newGutter();
        gutter.setLineNumberStyle(LineNumberStyle.RELATIVE);
        List<String> texts = labels(gutter, 4);
        assertEquals(10, texts.size());
        for (int i = 0; i < 10; i++) {
            assertLabel(String.valueOf(Math.abs(i - 4)), texts.get(i),
                    "RELATIVE ligne " + i);
        }
        // Le repère se lit sans calcul : « 0 » au caret, 4 au-dessus
        // (ligne 0), 5 en-dessous (ligne 9).
        assertLabel("0", texts.get(4), "RELATIVE caret");
        assertLabel("4", texts.get(0), "RELATIVE premier");
        assertLabel("5", texts.get(9), "RELATIVE dernier");
    }

    @Test
    public void HYBRIDE_numéroRéelAuCaret_distancesAilleurs() {
        GutterView gutter = newGutter();
        gutter.setLineNumberStyle(LineNumberStyle.HYBRID);
        List<String> texts = labels(gutter, 4);
        assertEquals(10, texts.size());
        // Ligne du caret : le NUMÉRO RÉEL (5), pas 0.
        assertLabel("5", texts.get(4), "HYBRID caret");
        // Ailleurs : la distance.
        assertLabel("4", texts.get(0), "HYBRID ligne 0");
        assertLabel("1", texts.get(3), "HYBRID ligne 3");
        assertLabel("1", texts.get(5), "HYBRID ligne 5");
        assertLabel("5", texts.get(9), "HYBRID ligne 9");
    }

    @Test
    public void caretInvalide_retombeSurABSOLUTE() {
        GutterView gutter = newGutter();
        gutter.setLineNumberStyle(LineNumberStyle.RELATIVE);
        List<String> texts = labels(gutter, -1);
        for (int i = 0; i < 10; i++) {
            assertLabel(String.valueOf(i + 1), texts.get(i),
                    "caret invalide ligne " + i);
        }
    }

    @Test
    public void null_retombeSurABSOLUTE_etLeRelaisDeVue() {
        GutterView gutter = newGutter();
        gutter.setLineNumberStyle(LineNumberStyle.RELATIVE);
        gutter.setLineNumberStyle(null);
        assertEquals(LineNumberStyle.ABSOLUTE, gutter.getLineNumberStyle());

        // Relais public d'EditorView : l'hôte ne touche pas aux
        // collaborateurs internes.
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(
                EditorDocument.of("a\nb\nc\n")));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        assertEquals(LineNumberStyle.ABSOLUTE, view.getLineNumberStyle());
        view.setLineNumberStyle(LineNumberStyle.HYBRID);
        assertEquals(LineNumberStyle.HYBRID, view.getLineNumberStyle());
        view.setLineNumberStyle(null);
        assertEquals(LineNumberStyle.ABSOLUTE, view.getLineNumberStyle());
    }
}
