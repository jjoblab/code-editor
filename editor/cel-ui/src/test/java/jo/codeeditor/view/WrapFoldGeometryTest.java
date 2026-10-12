package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * B12 (audit) : en mode word-wrap, les mappages Y (docLineToY,
 * docLineForScreenY) et la borne de scroll (maxV) ignorent les lignes
 * cachées par les plis repliés — contrairement au chemin non-wrap.
 *
 * <p>Symptômes avant correctif : trou vide sous un pli replié (les
 * lignes sous le pli gardaient leur Y non-wrap-caché), taps « dans le
 * vide » sur des lignes invisibles, et scroll possible sous la dernière
 * ligne visible (~n×lineHeight dans le vide).</p>
 *
 * <p>Modèle : 30 lignes de 150 caractères (2 rangées wrap chacune à
 * ~100 colonnes par rangée), pli repliés lignes 5..25. Rangées
 * visibles attendues : lignes 0-4 (10 rangées) + en-tête du pli
 * (1 rangée composite, au lieu de 2) + lignes 26-29 (8 rangées) —
 * la ligne 26 commence donc à la rangée visible 11.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class WrapFoldGeometryTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;

    private static String thirtyWrappedLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            for (int c = 0; c < 150; c++) sb.append((char) ('a' + (i % 26)));
            sb.append('\n');
        }
        return sb.toString();
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

    private static EditorView newView(boolean withFold) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of(thirtyWrappedLines()));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        view.setWordWrap(true);
        if (withFold) {
            EditorDocument d = session.getDocument();
            List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
            folds.add(new DiagnosticShift.FoldRegion(
                    d.lineStart(5) + 1, d.lineStart(25), "{…}", "indent", true));
            session.setFoldRegions(folds);
        }
        return view;
    }

    @Test
    public void docLineToY_wrap_subtractsHiddenFoldRows() {
        EditorView view = newView(true);

        // Lignes 0-4 : 2 rangées wrap chacune → la ligne 5 (en-tête du
        // pli) commence à la rangée 10, UNE seule rangée composite.
        assertEquals(PAD_TOP + 10 * LINE_H, view.docLineToY(5), 0.01f);

        // Ligne 26 (première sous le pli) : 10 rangées (lignes 0-4)
        // + 1 rangée d'en-tête = rangée visible 11 — et NON la rangée
        // wrap naturelle 52 (26 lignes × 2) qui laissait un trou vide.
        assertEquals(PAD_TOP + 11 * LINE_H, view.docLineToY(26), 0.01f);
        // La ligne 27 (2 rangées wrap) commence à la rangée 13.
        assertEquals(PAD_TOP + 13 * LINE_H, view.docLineToY(27), 0.01f);
    }

    @Test
    public void docLineForScreenY_wrap_mapsHeaderAndLinesBelowFold() {
        EditorView view = newView(true);

        // L'en-tête du pli (rangée visible 10) se mappe sur la ligne 5.
        assertEquals(5, view.docLineForScreenY(PAD_TOP + 10 * LINE_H + 1f));
        // La première ligne sous le pli (rangée visible 11) → ligne 26,
        // pas une ligne cachée du pli.
        assertEquals(26, view.docLineForScreenY(PAD_TOP + 11 * LINE_H + 1f));
        // La rangée 12 est la 2e rangée wrap de la ligne 26 → ligne 26.
        assertEquals(26, view.docLineForScreenY(PAD_TOP + 12 * LINE_H + 1f));
    }

    @Test
    public void maxV_wrap_accountsForCollapsedFolds() {
        EditorView noFold = newView(false);
        EditorView folded = newView(true);

        // Sans pli : 30 lignes × 2 rangées + la ligne vide finale (le
        // document se termine par \n) = 61 rangées × 40 + 20 de padding
        // = 2460 > 1920 → scrollable de 540 px.
        assertEquals(540f, noFold.maxV(), 1f);

        // Avec le pli : 19 rangées visibles × 40 + 20 = 780 < 1920 →
        // PLUS de scroll vertical du tout (avant : ~41×40 = 1640 px de
        // scroll dans le vide sous la dernière ligne visible).
        assertEquals(0f, folded.maxV(), 1f);
        assertTrue("le contenu replié doit tenir dans le viewport",
                folded.maxV() < noFold.maxV());
    }

    @Test
    public void roundTrip_docLineToY_docLineForScreenY_belowFold() {
        EditorView view = newView(true);
        for (int line = 26; line < 30; line++) {
            float y = view.docLineToY(line) + 1f;
            assertEquals("aller-retour Y→ligne pour la ligne " + line,
                    line, view.docLineForScreenY(y));
        }
    }
}
