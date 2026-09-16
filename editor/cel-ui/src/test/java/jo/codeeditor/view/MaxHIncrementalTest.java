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
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * v3.35.0 (roadmap item 4 / hotspot P4) — tests of the memoized maxH():
 * the longest-line scan now runs once per (session, document revision,
 * inlay revision) instead of on EVERY call (maxH is called several times
 * per frame by scroll clamping / fling / caret-into-view).
 *
 * <p>Pins:</p>
 * <ul>
 *   <li>same VALUES as the pre-v3.35.0 scan (longest line, inlay visual
 *       overflow, chip extent — the EditorWrapChipsTest suite already
 *       covers inlay/chip growth, these focus on the memo lifecycle);</li>
 *   <li>the memo rebuilds on edits (doc replaced), on setInlayHints
 *       (inlay rev bumped), and resets on session swap;</li>
 *   <li>font-scale changes do NOT need a rebuild (cached value is in
 *       columns — the pixel conversion stays out of the memo), but the
 *       returned maxH still scales with charWidth;</li>
 *   <li>repeated calls in the same state are cheap and stable.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class MaxHIncrementalTest {

    private static final String DOC =
            "short\n"
            + "this line is clearly the longest one in the doc\n"
            + "tiny\n";

    /** Longest line of DOC — "this line is clearly the longest one in the doc" (47 chars). */
    private static final int LONGEST = 47;

    private EditorView newView(String doc) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(doc)));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    private static float injectMetrics(EditorView view, float lineHeight,
            float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
            setFloat(view.metrics, "gutterWidth", charWidth * 7f);
            setFloat(view.metrics, "padRight", charWidth);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return charWidth;
    }

    private static void setFloat(Object target, String field, float value)
            throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    /** Valeur attendue par l'ANCIENNE formule (scan par appel). */
    private static float expectedMaxH(EditorView view, int maxCols) {
        float contentW = view.metrics.getPadLeft()
                + (maxCols + 1) * view.metrics.getCharWidth();
        if (view.chipExtentContentX > contentW) contentW = view.chipExtentContentX;
        contentW += view.metrics.getPadRight();
        float textAreaW = view.getWidth() - view.metrics.getGutterWidth();
        return Math.max(0, contentW - textAreaW);
    }

    // ── Values match the legacy scan ────────────────────────────────

    @Test
    public void maxH_matchesLegacyFormula() {
        EditorView view = newView(DOC);
        injectMetrics(view, 40f, 25f);
        float expected = expectedMaxH(view, LONGEST);
        assertEquals(expected, view.maxH(), 0.001f);
        assertTrue(view.maxH() > 0);
    }

    @Test
    public void maxH_zeroWhenEverythingFits() {
        EditorView view = newView("ab\ncd\n");
        injectMetrics(view, 40f, 4f);
        // 2 chars + 1 (marge) + pads (0.5 + 1) = ~4.5 chars * 4 = 18px,
        // textArea = 1080 - 7*4 = 1052 → tout tient → 0.
        assertEquals(0f, view.maxH(), 0.001f);
    }

    @Test
    public void maxH_repeatedCallsAreStable() {
        EditorView view = newView(DOC);
        injectMetrics(view, 40f, 25f);
        float first = view.maxH();
        for (int i = 0; i < 50; i++) {
            assertEquals(first, view.maxH(), 0.0001f);
        }
    }

    // ── Memo invalidation ───────────────────────────────────────────

    @Test
    public void editRebuildsMemo() {
        EditorView view = newView(DOC);
        injectMetrics(view, 40f, 25f);
        float before = view.maxH();
        assertTrue(before > 0);

        // Raccourcit LA ligne la plus longue → maxH doit rétrécir.
        EditorSession s = view.getSession();
        EditorDocument doc = s.getDocument();
        int line1Start = doc.lineStart(1);
        s.setSelection(line1Start);
        s.replaceRange(line1Start, doc.lineEnd(1), "ok");

        float after = view.maxH();
        // Nouvelle ligne la plus longue = "short" (5) ou "tiny" (4) → 5.
        assertEquals(expectedMaxH(view, 5), after, 0.001f);
        assertTrue(after < before);
    }

    @Test
    public void extendingALineBeyondTheMaxGrowsMaxH() {
        EditorView view = newView(DOC);
        injectMetrics(view, 40f, 25f);
        float before = view.maxH();

        EditorSession s = view.getSession();
        s.setSelection(0);
        // Insère une ligne de 80 chars en tête.
        s.replaceRange(0, 0, "x".repeat(80) + "\n");
        float after = view.maxH();
        assertEquals(expectedMaxH(view, 80), after, 0.001f);
        assertTrue(after > before);
    }

    @Test
    public void setInlayHintsRebuildsMemo() {
        EditorView view = newView(DOC);
        injectMetrics(view, 40f, 25f);
        float withoutHints = view.maxH();

        // Un hint tissé au-delà de la fin de la ligne 1 (47 chars) :
        // longueur visuelle = 47 + 30 = 77 → nouveau max.
        EditorSession s = view.getSession();
        EditorDocument doc = s.getDocument();
        int anchor = doc.lineEnd(1);   // fin de la ligne la plus longue
        List<DiagnosticShift.InlayHint> hints = Collections.singletonList(
                new DiagnosticShift.InlayHint(anchor, "x".repeat(30), true));
        s.setInlayHints(hints);

        assertEquals(expectedMaxH(view, 77), view.maxH(), 0.001f);
        assertTrue(view.maxH() > withoutHints);
    }

    @Test
    public void sessionSwapResetsMemo() {
        EditorView view = newView(DOC);
        injectMetrics(view, 40f, 25f);
        assertTrue(view.maxH() > 0);

        // Nouvelle session avec un document court : le memo (clé = doc) doit
        // se recalculer, pas servir l'ancien max.
        EditorSession fresh = new EditorSession(EditorDocument.of("ab"));
        view.setSession(fresh);
        // NOTE: injectMetrics touche la MÊME instance metrics (le champ est
        // final dans EditorView) — inchangé par setSession.
        float after = view.maxH();
        assertEquals(expectedMaxH(view, 2), after, 0.001f);
    }

    // ── Font scale interplay ────────────────────────────────────────

    @Test
    public void fontScaleChangeScalesMaxHWithoutRebuildingColumns() {
        EditorView view = newView(DOC);
        injectMetrics(view, 40f, 25f);
        float at10px = view.maxH();

        // Double la largeur de caractère (simule un font-scale ×2 sur des
        // metrics injectées — le maxCols mémoïsé est en COLONNES, donc la
        // conversion pixel doit suivre sans invalider le memo).
        injectMetrics(view, 80f, 50f);
        float at20px = view.maxH();
        assertEquals(expectedMaxH(view, LONGEST), at20px, 0.001f);
        assertTrue(at20px > at10px);
    }

    // ── Word wrap guard ─────────────────────────────────────────────

    @Test
    public void wordWrapReturnsZero() {
        EditorView view = newView(DOC);
        injectMetrics(view, 40f, 25f);
        view.setWordWrap(true);
        assertEquals(0f, view.maxH(), 0.001f);
        view.setWordWrap(false);
        assertTrue(view.maxH() > 0);
    }
}
