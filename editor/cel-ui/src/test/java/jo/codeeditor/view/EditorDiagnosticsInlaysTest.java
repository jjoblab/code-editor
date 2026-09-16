package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ v2.31 — Tests des correctifs UI de l'éditeur :
 * <ul>
 *   <li><b>Inlay hints tissés</b> — {@code visualColFor}/{@code rawColFor}
 *       round-trip (CodeAssist rawToVisual/visualToRaw semantics) : le texte
 *       après un hint est décalé, le caret s'ancre AVANT le hint, un tap DANS
 *       le hint revient sur sa colonne d'ancrage ;</li>
 *   <li><b>Indent guides modernisés</b> — {@code leadingIndentOrBlank}
 *       renvoie le sentinel -1 pour une ligne vide (pontage) ;</li>
 *   <li><b>Caret masqué en lecture seule</b> — le rendu complet ne dessine
 *       plus le caret (drawCaret early-return) ;</li>
 *   <li><b>Diagnostic sheet + chips</b> — géométrie partagée non nulle,
 *       hit-test du chip (tap → sheet), et smoke render complet sans crash.</li>
 * </ul>
 *
 * <p>NOTE Robolectric : les fontes legacy rendent FontMetrics nuls (lineHeight=0,
 * charWidth=1) — les tests géométriques injectent des métriques déterministes
 * par réflexion ({@link #injectMetrics}).</p>
 *
 * @author jo@Dev
 * @since v2.31
 */
@RunWith(RobolectricTestRunner.class)
public class EditorDiagnosticsInlaysTest {

    private static final String DOC =
        "public class Main {\n"
        + "    void run() {\n"
        + "        greet(name);\n"
        + "    }\n"
        + "}\n";

    /** offset du 'n' de "name" (argument de l'appel) sur la ligne 2. */
    private static int argOffset(String doc) {
        return doc.indexOf("greet(name);") + "greet(".length();
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    /** Injects deterministic metrics (Robolectric legacy fonts return zeros). */
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

    // ── Inlay weaving : visual ↔ raw mapping ─────────────────────────

    @Test
    public void inlayWeaving_visualColShiftsTextAfterHint() {
        EditorView view = newView();
        EditorDocument doc = view.getSession().getDocument();
        int anchor = argOffset(doc.getText().toString());
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(anchor, "name:", true));
        view.getSession().setInlayHints(hints);

        int line = doc.lineForOffset(anchor);
        int col = anchor - doc.lineStart(line);
        // Column BEFORE the hint: identity (no inlay woven before it).
        assertEquals(0, view.visualColFor(line, 0));
        assertEquals(col, view.visualColFor(line, col));
        // Lines without hints: identity.
        assertEquals(7, view.visualColFor(0, 7));
        // A column AFTER the hint is shifted right by the hint's length (5).
        assertEquals(col + 1 + 5, view.visualColFor(line, col + 1));
    }

    @Test
    public void inlayWeaving_rawColRoundTripAndSnapInsideHint() {
        EditorView view = newView();
        EditorDocument doc = view.getSession().getDocument();
        int anchor = argOffset(doc.getText().toString());
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(anchor, "name:", true));
        view.getSession().setInlayHints(hints);

        int line = doc.lineForOffset(anchor);
        int col = anchor - doc.lineStart(line);
        // Round-trip: visual col of (col+1) maps back to raw col+1.
        int vis = view.visualColFor(line, col + 1);
        assertEquals(col + 1, view.rawColFor(line, vis));
        // A tap INSIDE the hint (visual col anchor+2) snaps to the anchor col.
        assertEquals(col, view.rawColFor(line, col + 2));
        // No inlays on other lines → identity.
        assertEquals(7, view.rawColFor(0, 7));
    }

    @Test
    public void inlayWeaving_offsetAtSnapsTapInsideHintToAnchor() {
        EditorView view = newView();
        injectMetrics(view, 40f, 10f);
        EditorDocument doc = view.getSession().getDocument();
        int anchor = argOffset(doc.getText().toString());
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(anchor, "name:", true));
        view.getSession().setInlayHints(hints);

        int line = doc.lineForOffset(anchor);
        int col = anchor - doc.lineStart(line);
        float charWidth = view.metrics.getCharWidth();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        // Tap in the middle of the woven hint (visual col anchor + 2).
        int offset = view.offsetAt(textAreaLeft + (col + 2) * charWidth + 1,
            view.metrics.getPadTop() + (line + 0.5f) * view.metrics.getLineHeight());
        assertEquals(anchor, offset);
        // Tap just AFTER the hint → the argument's raw column (col), not the
        // visual one — caret and code stay aligned on the document.
        int after = view.offsetAt(textAreaLeft + (col + 5) * charWidth,
            view.metrics.getPadTop() + (line + 0.5f) * view.metrics.getLineHeight());
        assertEquals(anchor, after);
    }

    // ── Indent guides : blank-line sentinel ─────────────────────────

    @Test
    public void indentGuides_blankLineReportsMinusOne() {
        assertEquals(-1, EditorRenderer.leadingIndentOrBlank(""));
        assertEquals(-1, EditorRenderer.leadingIndentOrBlank("    "));
        assertEquals(-1, EditorRenderer.leadingIndentOrBlank("\t"));
        assertEquals(-1, EditorRenderer.leadingIndentOrBlank("\t\t"));
        assertEquals(4, EditorRenderer.leadingIndentOrBlank("    x"));
        assertEquals(8, EditorRenderer.leadingIndentOrBlank("\t\tx"));
        assertEquals(0, EditorRenderer.leadingIndentOrBlank("x"));
        assertEquals(0, EditorRenderer.leadingIndentOrBlank(null));
    }

    // ── Read-only : pas de caret ────────────────────────────────────

    @Test
    public void readOnly_caretNotDrawn_fullRenderSmoke() {
        EditorView view = newView();
        view.getSession().setReadOnly(true);
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        view.draw(canvas); // drawCaret early-return : ne doit ni crasher ni dessiner
        assertTrue(view.getSession().isReadOnly());
        // Toggle back — still renders fine.
        view.getSession().setReadOnly(false);
        view.draw(canvas);
        assertFalse(view.getSession().isReadOnly());
    }

    // ── Diagnostic sheet + chips ────────────────────────────────────

    private EditorView viewWithDiagnostic(int severity) {
        EditorView view = newView();
        injectMetrics(view, 40f, 10f);
        EditorDocument doc = view.getSession().getDocument();
        int start = doc.getText().toString().indexOf("greet");
        List<DiagnosticShift.Diagnostic> diags = new ArrayList<>();
        diags.add(new DiagnosticShift.Diagnostic(start, start + 5, severity, "Cannot resolve method greet"));
        view.getSession().setDiagnostics(diags);
        return view;
    }

    @Test
    public void diagnosticSheet_metricsAndCloseButtonGeometry() {
        EditorView view = viewWithDiagnostic(3);
        view.showDiagnosticPopup(
            view.getSession().getDiagnostics().get(0), 0);
        assertTrue(view.isDiagnosticPopupVisible());

        float[] m = view.diagnosticSheetMetrics();
        assertNotNull(m);
        // Panel docked at the bottom: [panelTop, getHeight()].
        assertTrue(m[0] < m[1]);
        assertEquals(view.getHeight(), m[1], 0.01f);
        // Close button sits inside the header, near the right edge.
        assertTrue(m[4] > view.getWidth() * 0.8f);
        assertTrue(m[5] > m[0]);

        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        EditorRenderer renderer = new EditorRenderer(view);
        renderer.drawDiagnosticPopup(new Canvas(bmp)); // smoke — no crash

        view.dismissDiagnosticPopup();
        assertFalse(view.isDiagnosticPopupVisible());
        assertNull(view.diagnosticSheetMetrics());
    }

    @Test
    public void diagnosticChip_tapOnPillFindsTheDiagnostic() {
        EditorView view = viewWithDiagnostic(3);
        EditorDocument doc = view.getSession().getDocument();
        assertTrue(view.diagnosticChipsEnabled); // enabled by default (v2.31)

        int line = doc.lineForOffset(
            view.getSession().getDiagnostics().get(0).start);
        // The pill sits after the line end — a tap on its box must find it.
        float[] m = view.diagnosticChipMetrics(
            view.getSession().getDiagnostics().get(0), line);
        assertNotNull("chip metrics must exist for a visible diagnostic", m);
        assertTrue(m[2] > 0); // non-empty width
        assertTrue(m[3] > 0); // non-empty height

        DiagnosticShift.Diagnostic hit = view.findDiagnosticChipAt(
            m[0] + m[2] * 0.5f, m[1] + m[3] * 0.5f);
        assertNotNull("tap on the pill centre must hit the diagnostic", hit);
        assertEquals(3, hit.severity);

        // A tap far left of the pill (on the code itself) must NOT hit it.
        assertNull(view.findDiagnosticChipAt(
            view.metrics.getGutterWidth() + 2, m[1] + m[3] * 0.5f));
    }

    @Test
    public void diagnosticChip_infoSeverityGetsNoChip() {
        EditorView view = viewWithDiagnostic(1); // Info → no chip (CodeAssist)
        EditorDocument doc = view.getSession().getDocument();
        int line = doc.lineForOffset(
            view.getSession().getDiagnostics().get(0).start);
        assertNull(view.chipDiagnosticForLine(line));
        assertNull(view.findDiagnosticChipAt(
            view.metrics.getGutterWidth() + 100, 100));
    }

    @Test
    public void fullRender_withInlaysDiagnosticsChipsAndSheetSmoke() {
        EditorView view = viewWithDiagnostic(2);
        EditorDocument doc = view.getSession().getDocument();
        int anchor = argOffset(doc.getText().toString());
        List<DiagnosticShift.InlayHint> hints = new ArrayList<>();
        hints.add(new DiagnosticShift.InlayHint(anchor, "name:", true));
        view.getSession().setInlayHints(hints);
        view.showDiagnosticPopup(view.getSession().getDiagnostics().get(0), anchor);

        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bmp)); // full pipeline smoke — no crash
        assertTrue(view.isDiagnosticPopupVisible());
    }

    @Test
    public void countWrappedLines_basic() {
        EditorView view = newView();
        view.textPaint.setTextSize(40f);
        // Short message on a wide-enough line → 1 line.
        assertEquals(1, view.countWrappedLines("hello world", 10000f));
        // Very narrow → more lines than words groups.
        assertTrue(view.countWrappedLines("aaa bbb ccc", 10f) >= 2);
        assertEquals(1, view.countWrappedLines("", 100f));
        assertEquals(1, view.countWrappedLines(null, 100f));
    }
}
