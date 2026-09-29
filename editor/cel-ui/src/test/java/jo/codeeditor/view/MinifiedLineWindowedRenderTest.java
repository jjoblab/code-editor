package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Canvas;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Régression « minifier » : le rendu d'une ligne minifiée (des centaines
 * de Ko sur une seule ligne) doit être FENÊTRÉ — parité sora-editor
 * ({@code TextRow.draw(canvas, beginOffset, endOffset)} borne le dessin à
 * la fenêtre horizontale visible).
 *
 * <p>Avant le correctif : le chemin ligatures construisait un
 * {@code StaticLayout} de la ligne ENTIÈRE (secondes de façonnage +
 * dizaines de Mo par frame), le chemin drawText shapait chaque span
 * géant, {@code drawNonPrintableChars} itérait chaque caractère (jusqu'à
 * 500 000 drawText par frame) et {@code drawWrappedLine} dessinait
 * toutes les rangées wrap même hors écran.</p>
 *
 * <p>Assertion transverse : AUCUN drawText reçu par le Canvas ne dépasse
 * la largeur de la fenêtre visible (viewport 1080 px / charWidth 10 px
 * + marges → ≤ ~200 colonnes), quel que soit le chemin de rendu.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class MinifiedLineWindowedRenderTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    /** Viewport 1080 px / 10 px par colonne = 108 colonnes visibles ;
     *  fenêtre = 108 + 2×16 de marge = 140. Marge de test x3. */
    private static final int MAX_DRAWN_TEXT_LEN = 512;

    private static String minifiedLine(int cols) {
        // Gauche à la façon d'un bundle JS minifié : un seul « token »
        // géant ponctué de points-virgules, sans espaces ni retours.
        StringBuilder sb = new StringBuilder(cols + 16);
        sb.append("var a=1;");
        while (sb.length() < cols) {
            sb.append("f(a<<2|3).x++;");
        }
        return sb.toString();
    }

    private EditorView newView(String doc) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
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

    /** Rend la vue et renvoie le texte de chaque drawText enregistré par
     *  le shadow canvas (Robolectric 4.13 : getTextHistoryCount() +
     *  getDrawnTextEvent(i)). */
    private static List<String> drawnTexts(EditorView view) {
        Canvas canvas = new Canvas();
        view.draw(canvas);
        org.robolectric.shadows.ShadowCanvas shadow = Shadows.shadowOf(canvas);
        List<String> out = new java.util.ArrayList<>(shadow.getTextHistoryCount());
        for (int i = 0; i < shadow.getTextHistoryCount(); i++) {
            String t = shadow.getDrawnTextEvent(i).text;
            if (t != null) out.add(t);
        }
        return out;
    }

    private static void assertAllWindowed(List<String> texts, String what) {
        assertTrue(what + " : rien n'a été dessiné", !texts.isEmpty());
        int worst = 0;
        for (String t : texts) {
            worst = Math.max(worst, t.length());
        }
        assertTrue(what + " : un drawText de " + worst
                + " caractères dépasse la fenêtre visible (max "
                + MAX_DRAWN_TEXT_LEN + ") — la ligne minifiée est façonnée"
                + " en entier au lieu d'être fenêtrée",
                worst <= MAX_DRAWN_TEXT_LEN);
    }

    @Test
    public void styledLigaturePath_giantLine_drawsOnlyVisibleWindow() {
        // Une seule ligne minifiée de ~120 Ko, coloration syntaxique
        // active (javascript → tokenizer intégré, TextMate skippé
        // au-delà de 5000 caractères) + ligatures (chemin StaticLayout).
        EditorView view = newView(minifiedLine(120_000) + "\n");
        view.getSession().setLanguage("javascript");
        assertAllWindowed(drawnTexts(view),
                "chemin ligatures (StaticLayout fenêtré)");
    }

    @Test
    public void plainFallbackPath_giantLine_drawsOnlyVisibleWindow() {
        // Sans langage : StyledLine absent → repli monochrome
        // (drawPlainLine), lui aussi fenêtré.
        EditorView view = newView(minifiedLine(120_000) + "\n");
        assertAllWindowed(drawnTexts(view),
                "repli monochrome (drawPlainLine fenêtré)");
    }

    @Test
    public void nonPrintablePath_giantLine_boundedByWindow() {
        // Caractères non imprimables activés : l'itération doit être
        // bornée par la fenêtre — pas un drawText par caractère de la
        // ligne de 120 Ko (jusqu'à 500 000 par frame avant le correctif).
        EditorView view = newView(minifiedLine(120_000) + "\n");
        view.setShowNonPrintable(true);
        List<String> texts = drawnTexts(view);
        assertTrue("aucun texte dessiné", !texts.isEmpty());
        long dots = texts.stream().filter("·"::equals).count();
        assertTrue("les non-imprimables dessinés (" + dots
                + ") dépassent la fenêtre visible (max "
                + MAX_DRAWN_TEXT_LEN + ")",
                dots <= MAX_DRAWN_TEXT_LEN);
    }

    @Test
    public void wrapPath_giantLine_drawsOnlyVisibleRows() {
        // Word-wrap actif sur une ligne minifiée de ~100 Ko : la ligne
        // s'étale sur ~1000 rangées — seules les ~48 visibles doivent
        // être dessinées (avant : toutes, avec un drawText par span par
        // rangée).
        EditorView view = newView(minifiedLine(100_000) + "\n");
        view.getSession().setLanguage("javascript");
        view.setWordWrap(true);
        List<String> texts = drawnTexts(view);
        assertTrue("aucun texte dessiné", !texts.isEmpty());
        assertTrue(texts.stream().allMatch(t -> t.length() <= MAX_DRAWN_TEXT_LEN));
        // 48 rangées visibles × ~90 spans minifiés par rangée ≈ 4 300
        // drawText ; la version non fenêtrée dessinait ~1000 rangées ×
        // 90 ≈ 90 000 drawText. Séparation nette d'un facteur 20.
        assertTrue("trop de drawText pour un viewport de 48 rangées ("
                + texts.size() + ") — le wrap dessine des rangées hors écran",
                texts.size() <= 10_000);
    }
}
