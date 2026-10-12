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
 * Tests du maxH() mémoïsé : le scan de la ligne la plus longue ne tourne
 * qu'une fois par (session, révision de document, révision d'inlay) au
 * lieu de CHAQUE appel (maxH est appelé plusieurs fois par frame par le
 * clamp de scroll / le fling / le caret-into-view).
 *
 * <p>Verrouille :</p>
 * <ul>
 *   <li>les MÊMES VALEURS que le scan par appel (ligne la plus longue,
 *       débordement visuel des inlays, étendue de chip — la suite
 *       EditorWrapChipsTest couvre déjà la croissance inlay/chip, ces
 *       tests ciblent le cycle de vie du memo) ;</li>
 *   <li>le memo se reconstruit sur édition (doc remplacé), sur
 *       setInlayHints (révision inlay incrémentée), et se réinitialise
 *       sur swap de session ;</li>
 *   <li>les changements de font-scale ne nécessitent PAS de reconstruction
 *       (la valeur cachée est en colonnes — la conversion pixel reste hors
 *       du memo), mais le maxH renvoyé suit toujours charWidth ;</li>
 *   <li>les appels répétés dans le même état sont bon marché et stables.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class MaxHIncrementalTest {

    private static final String DOC =
            "short\n"
            + "this line is clearly the longest one in the doc\n"
            + "tiny\n";

    /** Ligne la plus longue de DOC — « this line is clearly the longest one in the doc » (47 chars). */
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

    /** Valeur attendue par la formule de référence (scan par appel). */
    private static float expectedMaxH(EditorView view, int maxCols) {
        float contentW = view.metrics.getPadLeft()
                + (maxCols + 1) * view.metrics.getCharWidth();
        if (view.chipExtentContentX > contentW) contentW = view.chipExtentContentX;
        contentW += view.metrics.getPadRight();
        float textAreaW = view.getWidth() - view.metrics.getGutterWidth();
        return Math.max(0, contentW - textAreaW);
    }

    // ── Valeurs conformes au scan de référence ────────────────────

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

    // ── Invalidation du memo ───────────────────────────────────────

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

    // ── Interaction avec le font scale ─────────────────────────────

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

    // ── Garde-fou word wrap ────────────────────────────────────────

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
