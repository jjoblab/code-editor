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
 * Tests des correctifs UI de l'éditeur :
 * <ul>
 *   <li><b>Inlay hints tissés</b> — {@code visualColFor}/{@code rawColFor}
 *       aller-retour (sémantique rawToVisual/visualToRaw) : le texte
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

    /** Injecte des métriques déterministes (les fontes legacy de Robolectric renvoient des zéros). */
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
        // Colonne AVANT le hint : identité (aucun inlay tissé avant lui).
        assertEquals(0, view.visualColFor(line, 0));
        assertEquals(col, view.visualColFor(line, col));
        // Lignes sans hints : identité.
        assertEquals(7, view.visualColFor(0, 7));
        // Une colonne APRÈS le hint est décalée à droite de la longueur du hint (5).
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
        // Aller-retour : la col visuelle de (col+1) remape vers la col brute col+1.
        int vis = view.visualColFor(line, col + 1);
        assertEquals(col + 1, view.rawColFor(line, vis));
        // Un tap DANS le hint (col visuelle ancre+2) s'aligne sur la col d'ancrage.
        assertEquals(col, view.rawColFor(line, col + 2));
        // Pas d'inlays sur les autres lignes → identité.
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
        // Tape au milieu du hint tissé (col visuelle ancre + 2).
        int offset = view.offsetAt(textAreaLeft + (col + 2) * charWidth + 1,
            view.metrics.getPadTop() + (line + 0.5f) * view.metrics.getLineHeight());
        assertEquals(anchor, offset);
        // Tape juste APRÈS le hint → la colonne brute de l'argument (col),
        // pas la visuelle — le caret et le code restent alignés sur le document.
        int after = view.offsetAt(textAreaLeft + (col + 5) * charWidth,
            view.metrics.getPadTop() + (line + 0.5f) * view.metrics.getLineHeight());
        assertEquals(anchor, after);
    }

    // ── Indent guides : sentinel des lignes vides ─────────────────────

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
        // Re-bascule — le rendu reste correct.
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
        // Panneau ancré en bas : [panelTop, getHeight()].
        assertTrue(m[0] < m[1]);
        assertEquals(view.getHeight(), m[1], 0.01f);
        // Le bouton fermer est dans l'en-tête, près du bord droit.
        assertTrue(m[4] > view.getWidth() * 0.8f);
        assertTrue(m[5] > m[0]);

        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        EditorRenderer renderer = new EditorRenderer(view);
        renderer.drawDiagnosticPopup(new Canvas(bmp)); // smoke — pas de crash

        view.dismissDiagnosticPopup();
        assertFalse(view.isDiagnosticPopupVisible());
        assertNull(view.diagnosticSheetMetrics());
    }

    @Test
    public void diagnosticChip_tapOnPillFindsTheDiagnostic() {
        EditorView view = viewWithDiagnostic(3);
        EditorDocument doc = view.getSession().getDocument();
        assertTrue(view.diagnosticChipsEnabled); // activé par défaut

        int line = doc.lineForOffset(
            view.getSession().getDiagnostics().get(0).start);
        // La pill se trouve après la fin de ligne — un tap sur sa boîte doit
        // la trouver.
        float[] m = view.diagnosticChipMetrics(
            view.getSession().getDiagnostics().get(0), line);
        assertNotNull("chip metrics must exist for a visible diagnostic", m);
        assertTrue(m[2] > 0); // largeur non vide
        assertTrue(m[3] > 0); // hauteur non vide

        DiagnosticShift.Diagnostic hit = view.findDiagnosticChipAt(
            m[0] + m[2] * 0.5f, m[1] + m[3] * 0.5f);
        assertNotNull("tap on the pill centre must hit the diagnostic", hit);
        assertEquals(3, hit.severity);

        // Un tap bien à gauche de la pill (sur le code lui-même) ne doit
        // PAS la toucher.
        assertNull(view.findDiagnosticChipAt(
            view.metrics.getGutterWidth() + 2, m[1] + m[3] * 0.5f));
    }

    @Test
    public void diagnosticChip_infoSeverityGetsNoChip() {
        EditorView view = viewWithDiagnostic(1); // Info → pas de chip
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
        view.draw(new Canvas(bmp)); // smoke du pipeline complet — pas de crash
        assertTrue(view.isDiagnosticPopupVisible());
    }

    @Test
    public void countWrappedLines_basic() {
        EditorView view = newView();
        view.textPaint.setTextSize(40f);
        // Message court sur une ligne assez large → 1 ligne.
        assertEquals(1, view.countWrappedLines("hello world", 10000f));
        // Très étroit → plus de lignes que de groupes de mots.
        assertTrue(view.countWrappedLines("aaa bbb ccc", 10f) >= 2);
        assertEquals(1, view.countWrappedLines("", 100f));
        assertEquals(1, view.countWrappedLines(null, 100f));
    }
}
