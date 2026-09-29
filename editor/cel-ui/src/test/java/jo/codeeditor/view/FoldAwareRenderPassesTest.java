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
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * B9 (audit) : les passes de rendu non sensibles aux plis (sélection,
 * recherche, document highlights, soulignés, guides, chevrons, chips)
 * reçoivent des LIGNES DOCUMENT, pas des rangées visuelles.
 *
 * <p>Avant : {@code firstVisible/lastVisible} (rangées visuelles) étaient
 * passées à des passes qui itèrent des lignes document et positionnent
 * via {@code docLineToY}. Avec un pli replié AU-DESSUS du viewport, la
 * plage était trop étroite : la bande de sélection et les surlignages
 * s'arrêtaient avant le bas du viewport (non dessiné).</p>
 *
 * <p>Cas de test : 100 lignes, pli replié des lignes 10 à 90, sélection
 * sur les lignes 95-96. Après repli, la ligne 95 occupe la rangée
 * visuelle ~14 (visible à l'écran), mais son INDEX de ligne document
 * (95) dépasse lastVisible (≈49) — la bande de sélection doit pourtant
 * être dessinée.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class FoldAwareRenderPassesTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private static String hundredLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("line ").append(i).append("\n");
        }
        return sb.toString();
    }

    private EditorView newView() {
        String doc = hundredLines();
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);

        // Pli replié couvrant les lignes 10..90 — les lignes sous le pli
        // remontent de 80 rangées.
        EditorDocument d = session.getDocument();
        int foldStart = d.lineStart(10) + "line ".length();  // après le préfixe
        int foldEnd = d.lineStart(90);
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(foldStart, foldEnd,
                "{…}", "indent", true));
        session.setFoldRegions(folds);

        // Sélection des lignes 95 à 96 — SOUS le pli, visible à l'écran
        // (rangée visuelle ~14) mais au-delà de la rangée visuelle 49
        // que le code fauteur utilisait comme borne.
        int selStart = d.lineStart(95) + 2;
        int selEnd = d.lineStart(96) + 2;
        session.setSelection(jo.codeeditor.document.Selection.range(selStart, selEnd));
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

    @Test
    public void selectionBandBelowViewportRows_drawnWhenFoldAbove() {
        EditorView view = newView();

        // Sanity : la ligne 95 est bien visible à l'écran (rangée ~14).
        float y95 = view.docLineToY(95) - view.vOffset;
        assertTrue("la ligne 95 doit être à l'écran (y=" + y95 + ")",
                y95 >= 0 && y95 < 1920);

        Canvas canvas = new Canvas();
        view.draw(canvas);
        ShadowCanvas shadow = Shadows.shadowOf(canvas);

        // La bande de sélection (rect couleur theme.selection) doit être
        // dessinée pour les lignes 95/96 : au moins 2 rects de sélection.
        int selColor = view.theme.selection;
        int selRects = 0;
        for (int i = 0; i < shadow.getRectPaintHistoryCount(); i++) {
            ShadowCanvas.RectPaintHistoryEvent e = shadow.getDrawnRect(i);
            if (e.paint != null && e.paint.getColor() == selColor
                    && e.rect.height() <= LINE_H + 1f) {
                selRects++;
            }
        }
        assertTrue("la bande de sélection des lignes sous le pli doit être"
                + " dessinée (rects trouvés : " + selRects + ") — les passes"
                + " non sensibles aux plis recevaient des rangées visuelles"
                + " au lieu de lignes document (B9)",
                selRects >= 2);
    }
}
