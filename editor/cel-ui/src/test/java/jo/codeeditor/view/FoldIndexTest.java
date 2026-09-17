package jo.codeeditor.view;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests de l'index mémoïsé de sommes préfixes des folds derrière
 * {@code EditorView.countHiddenLinesAbove}, {@code docLineToY},
 * {@code docLineForScreenY} et le {@code isLineFoldedCached} du renderer.
 *
 * <p>Layout du document DOC ("l0\n…\nl9", lignes de 2 chars) : lineStarts
 * = [0,3,6,9,12,15,18,21,24,27]. Le fold de référence couvre les offsets
 * [6, 18] → startLine=l2, endLine=l6 → les lignes cachées sont
 * (startLine, endLine] = {l3, l4, l5, l6} (4 lignes).</p>
 *
 * <p>Verrouille :</p>
 * <ul>
 *   <li>hiddenAbove / isHidden contre une référence calculée à la main
 *       (folds au-dessus, à cheval, en dessous ; folds multiples ;
 *       régions chevauchantes fusionnées en UNION au lieu d'un double
 *       comptage) ;</li>
 *   <li>invalidation : rebuild sur toggle (mutation EN PLACE de la liste —
 *       la raison d'un compteur foldRev), sur édition (shift des offsets),
 *       sur setFoldRegions, sur swap de session ;</li>
 *   <li>docLineToY fold-aware ; docLineForScreenY l'inverse EXACTEMENT
 *       pour chaque rangée visible, y compris les rangées limites du fold.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class FoldIndexTest {

    private static final String DOC =
            "l0\nl1\nl2\nl3\nl4\nl5\nl6\nl7\nl8\nl9";

    private static EditorSession sessionWithFold(int startOffset, int endOffset,
                                                 boolean collapsed) {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(startOffset, endOffset,
                "{…}", "indent", collapsed));
        s.setFoldRegions(folds);
        return s;
    }

    private static EditorView viewFor(EditorSession s) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(s);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        // Robolectric n'a pas de vraies font metrics — injecte des valeurs
        // réalistes (pattern EditorWrapChipsTest).
        injectMetrics(view, 40f, 10f);
        return view;
    }

    /** Fold [6,18] : cache l3..l6 (startLine=l2, endLine=l6). */
    private static EditorView viewWithCollapsedFold() {
        return viewFor(sessionWithFold(6, 18, true));
    }

    private static void injectMetrics(EditorView view, float lineHeight,
            float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
            setFloat(view.metrics, "gutterWidth", charWidth * 7f);
            setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
            setFloat(view.metrics, "padRight", charWidth);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value)
            throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    // ── Sémantique de countHiddenLinesAbove ──────────────────────

    @Test
    public void hiddenAbove_noFoldsIsZeroEverywhere() {
        EditorView view = viewFor(new EditorSession(EditorDocument.of(DOC)));
        for (int l = 0; l <= 12; l++) {
            assertEquals(0, view.countHiddenLinesAbove(l));
        }
    }

    @Test
    public void hiddenAbove_matchesHandReference() {
        // Cachées = {l3,l4,l5,l6} : hiddenAbove(l) = |{cachées < l}|.
        EditorView view = viewWithCollapsedFold();
        int[] expected = {0, 0, 0, 0, 1, 2, 3, 4, 4, 4, 4};
        for (int l = 0; l <= 10; l++) {
            assertEquals("ligne " + l, expected[l], view.countHiddenLinesAbove(l));
        }
        // Ligne stale au-delà du document : formules toujours cohérentes.
        assertEquals(4, view.countHiddenLinesAbove(999));
    }

    @Test
    public void hiddenAbove_multipleFoldsAccumulate() {
        // [3,9] → (l1,l3] = {l2,l3} ; [18,27] → (l6,l9] = {l7,l8,l9}.
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(3, 9, "{…}", "indent", true));
        folds.add(new DiagnosticShift.FoldRegion(18, 27, "{…}", "indent", true));
        s.setFoldRegions(folds);
        EditorView view = viewFor(s);
        assertEquals(0, view.countHiddenLinesAbove(2));
        assertEquals(1, view.countHiddenLinesAbove(3));   // l2 cachée
        assertEquals(2, view.countHiddenLinesAbove(6));   // l2,l3
        assertEquals(2, view.countHiddenLinesAbove(7));   // l7 cachée mais pas < 7
        assertEquals(4, view.countHiddenLinesAbove(9));   // + l7,l8
        assertEquals(5, view.countHiddenLinesAbove(12));  // + l9
    }

    @Test
    public void hiddenAbove_overlappingFoldsMergeAsUnion() {
        // [6,18] → (l2,l6] ; [12,27] → (l4,l9] → union {l3..l9} (7 lignes).
        // Sommer les deux contributions serait un double comptage —
        // l'union est la sémantique correcte (même choix que
        // FoldModel.mergeRegions).
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(6, 18, "{…}", "indent", true));
        folds.add(new DiagnosticShift.FoldRegion(12, 27, "{…}", "indent", true));
        s.setFoldRegions(folds);
        EditorView view = viewFor(s);
        assertEquals(0, view.countHiddenLinesAbove(3));
        assertEquals(1, view.countHiddenLinesAbove(4));
        assertEquals(4, view.countHiddenLinesAbove(7));
        assertEquals(5, view.countHiddenLinesAbove(8));
        assertEquals(7, view.countHiddenLinesAbove(10));
    }

    @Test
    public void expandedFoldsDontHide() {
        EditorView view = viewFor(sessionWithFold(6, 18, false));
        for (int l = 0; l <= 10; l++) {
            assertEquals(0, view.countHiddenLinesAbove(l));
        }
    }

    // ── Sémantique de isHidden (chemin de draw) ────────────────────

    @Test
    public void isHidden_matchesFoldInterior() {
        EditorView view = viewWithCollapsedFold();
        // Lignes cachées = (l2, l6] = l3, l4, l5, l6.
        assertFalse(view.isLineFoldedCached(0));
        assertFalse(view.isLineFoldedCached(1));
        assertFalse(view.isLineFoldedCached(2));   // début de fold : VISIBLE
        assertTrue(view.isLineFoldedCached(3));
        assertTrue(view.isLineFoldedCached(4));
        assertTrue(view.isLineFoldedCached(5));
        assertTrue(view.isLineFoldedCached(6));    // fin de fold : CACHÉE
        assertFalse(view.isLineFoldedCached(7));   // première visible après
        assertFalse(view.isLineFoldedCached(9));
    }

    // ── Invalidation ────────────────────────────────────────────────

    @Test
    public void toggleFoldRebuildsIndex() {
        EditorSession s = sessionWithFold(6, 18, true);
        EditorView view = viewFor(s);
        assertEquals(4, view.countHiddenLinesAbove(7));

        // toggleFoldAtLine mute la liste EN PLACE (set(i, …)) — c'est le cas
        // qui imposait un compteur foldRev (une comparaison de référence de
        // getFoldRegions() ne détecterait rien : un wrapper neuf par appel).
        assertTrue(s.toggleFoldAtLine(2));
        assertEquals(0, view.countHiddenLinesAbove(7));
        assertFalse(view.isLineFoldedCached(4));

        // Re-plie → l'index se reconstruit encore.
        assertTrue(s.toggleFoldAtLine(2));
        assertEquals(4, view.countHiddenLinesAbove(7));
    }

    @Test
    public void editShiftsFoldAndIndexRebuilds() {
        EditorSession s = sessionWithFold(6, 18, true);
        EditorView view = viewFor(s);
        assertEquals(4, view.countHiddenLinesAbove(7));

        // Insère "NEW\n" (4 chars) AVANT le fold → offsets shiftés [10,22]
        // → startLine=l3, endLine=l7 → cachées {l4..l7}.
        s.setSelection(0);
        s.replaceRange(0, 0, "NEW\n");
        assertEquals(0, view.countHiddenLinesAbove(4));
        assertEquals(1, view.countHiddenLinesAbove(5));
        assertEquals(3, view.countHiddenLinesAbove(7));
        assertEquals(4, view.countHiddenLinesAbove(8));
    }

    @Test
    public void setFoldRegionsRebuildsIndex() {
        EditorSession s = new EditorSession(EditorDocument.of(DOC));
        EditorView view = viewFor(s);
        assertEquals(0, view.countHiddenLinesAbove(7));

        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        folds.add(new DiagnosticShift.FoldRegion(6, 18, "{…}", "indent", true));
        s.setFoldRegions(folds);
        assertEquals(4, view.countHiddenLinesAbove(7));
    }

    @Test
    public void sessionSwapRebuildsIndex() {
        EditorView view = viewWithCollapsedFold();
        assertEquals(4, view.countHiddenLinesAbove(7));
        // Nouvelle session SANS folds — un foldRev numériquement égal de la
        // nouvelle session ne doit PAS réutiliser l'index de l'ancienne :
        // la clé de mémoïsation intègre l'identité de session.
        EditorSession fresh = new EditorSession(EditorDocument.of(DOC));
        view.setSession(fresh);
        assertEquals(0, view.countHiddenLinesAbove(7));
        assertFalse(view.isLineFoldedCached(4));
    }

    // ── docLineToY / docLineForScreenY : aller-retour ─────────────

    @Test
    public void docLineToY_isFoldAware() {
        EditorView view = viewWithCollapsedFold();
        float lh = view.metrics.getLineHeight();
        float padTop = view.metrics.getPadTop();
        assertEquals(padTop + 0 * lh, view.docLineToY(0), 0.001f);
        assertEquals(padTop + 2 * lh, view.docLineToY(2), 0.001f);
        // l6 (fin de fold, cachée) se projette sur la rangée du fold start.
        assertEquals(padTop + 3 * lh, view.docLineToY(6), 0.001f);
        // l7 est la 4e ligne VISIBLE (0,1,2,7,…) → rangée visuelle 3.
        assertEquals(padTop + 3 * lh, view.docLineToY(7), 0.001f);
        assertEquals(padTop + 4 * lh, view.docLineToY(8), 0.001f);
    }

    @Test
    public void docLineForScreenY_invertsDocLineToYForEveryVisibleLine() {
        EditorView view = viewWithCollapsedFold();
        int[] visibleLines = {0, 1, 2, 7, 8, 9};
        for (int line : visibleLines) {
            float contentY = view.docLineToY(line);   // haut de la ligne
            // Un point au MILIEU de la ligne, en coordonnée écran
            // (vOffset = 0 ici).
            float screenY = contentY + view.metrics.getLineHeight() / 2f
                    - view.metrics.getPadTop() + view.vOffset;
            assertEquals("ligne visible " + line, line,
                    view.docLineForScreenY(screenY));
        }
    }

    @Test
    public void docLineForScreenY_tapInsideFoldLandsOnFoldStart() {
        EditorView view = viewWithCollapsedFold();
        float lh = view.metrics.getLineHeight();
        // Rangée visuelle 2 = ligne de fold start (l2).
        float screenY = view.metrics.getPadTop() + 2 * lh + lh / 2f;
        assertEquals(2, view.docLineForScreenY(screenY));
        // Rangée visuelle 3 = l7 (l3..l6 cachées).
        screenY = view.metrics.getPadTop() + 3 * lh + lh / 2f;
        assertEquals(7, view.docLineForScreenY(screenY));
    }

    @Test
    public void docLineForScreenY_noFoldsIsIdentity() {
        EditorView view = viewFor(new EditorSession(EditorDocument.of(DOC)));
        float lh = view.metrics.getLineHeight();
        for (int row = 0; row < 10; row++) {
            float screenY = view.metrics.getPadTop() + row * lh + lh / 2f;
            assertEquals(row, view.docLineForScreenY(screenY));
        }
    }

    @Test
    public void docLineForScreenY_belowLastLineClampsToLastLine() {
        EditorView view = viewWithCollapsedFold();
        float screenY = view.metrics.getPadTop() + 500 * view.metrics.getLineHeight();
        assertEquals(9, view.docLineForScreenY(screenY));
    }

    @Test
    public void gutterHiddenLineCheckerUsesIndex() {
        // Le checker du gutter délègue à isLineFoldedCached : ligne 0
        // toujours visible, intérieur du fold caché, fin de fold cachée.
        EditorView view = viewWithCollapsedFold();
        assertFalse(view.isLineFoldedCached(0));
        assertTrue(view.isLineFoldedCached(4));
        assertTrue(view.isLineFoldedCached(6));
    }
}
