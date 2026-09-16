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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests v2.33 de la géométrie word-wrap CONTINUATION-AWARE et du placement
 * des chips diagnostics multi-rangées :
 * <ul>
 *   <li>{@link EditorView.WrapRows} — comptage des rangées avec indent de
 *       continuation (la queue de la ligne n'est plus perdue), inversions
 *       rowForCol / rowStartCol ;</li>
 *   <li>chip placée après la FIN de la DERNIÈRE rangée (pattern CodeAssist
 *       {@code lastSub}), pas à la longueur non repliée ;</li>
 *   <li>caret : la rangée vient de wrapRowsFor (source unique) ;</li>
 *   <li>maxH : étendue inlay-aware + chipExtent (un débordement → scroll
 *       horizontal atteignable).</li>
 * </ul>
 *
 * <p>Métriques injectées par réflexion (fontes Robolectric legacy nulles).</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorWrapChipsTest {

    private static final String DOC =
        "class A {\n"
        + "    void run() {\n"
        + "        doSomething(longArgumentName, otherArgument, third);\n"
        + "    }\n"
        + "}\n";

    /** Ligne 2 de DOC : 60 chars. */
    private static final int LONGEST = 60;

    private EditorView newView(int widthPx) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of(DOC)));
        view.measure(widthPx, 1920);
        view.layout(0, 0, widthPx, 1920);
        return view;
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
            setFloat(view.metrics, "textSize", lineHeight * 0.75f);
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

    // ── WrapRows : comptage + inversions ─────────────────────────────

    @Test
    public void wrapRows_continuationRowsAreNarrower() {
        EditorView view = newView(1080);
        injectMetrics(view, 40f, 10f);
        view.setWordWrap(true);
        // wrapWidthPx = 1080 - gutter(70) - padLeft(5) - padRight(10) = 995
        // → maxColsPerRow = 99.
        EditorDocument doc = view.getSession().getDocument();
        int line2Len = doc.lineEnd(2) - doc.lineStart(2);
        EditorView.WrapRows wr = view.wrapRowsFor(2, line2Len);
        assertEquals(8, wr.wrapIndentCols);
        assertEquals(wr.maxColsPerRow - 8, wr.colsPerCont);
        assertEquals(99, wr.maxColsPerRow);
        assertEquals(1, wr.rows);
    }

    @Test
    public void wrapRows_indentedTailNoLongerLost() {
        // Ligne indentée de 8 + 191 chars = 199 chars.
        //   AVANT : rows = ceil(199/99) = 3 mais la rangée 2 était découpée
        //           à [99+91, 99+2*91)=190 → les 9 derniers chars PERDUS.
        //   MAINTENANT : rows = 1 + ceil((199-99)/91) = 3 et la rangée 2
        //           couvre [190, 199) — la queue est dessinée.
        EditorView view = newView(1080);
        injectMetrics(view, 40f, 10f);
        view.setSession(new EditorSession(EditorDocument.of(
                "        " + "x".repeat(191) + "\nsecond\n")));
        view.setWordWrap(true);
        EditorView.WrapRows wr = view.wrapRowsFor(0, 199);
        assertEquals(8, wr.wrapIndentCols);
        assertEquals(91, wr.colsPerCont);
        assertEquals(3, wr.rows);
        assertEquals(0, wr.rowStartCol(0));
        assertEquals(99, wr.rowStartCol(1));
        assertEquals(190, wr.rowStartCol(2));
        assertEquals(199, wr.rowEndCol(2, 199));
    }

    @Test
    public void wrapRows_rowForColInverseOfRowStartCol() {
        EditorView view = newView(1080);
        injectMetrics(view, 40f, 10f);
        view.setSession(new EditorSession(EditorDocument.of(
                "    " + "x".repeat(195) + "\nsecond\n")));
        view.setWordWrap(true);
        EditorView.WrapRows wr = view.wrapRowsFor(0, 199);
        for (int col = 0; col <= 199; col += 7) {
            int row = wr.rowForCol(col);
            assertTrue("row hors bornes pour col " + col, row < wr.rows);
            assertTrue("col " + col + " avant le début de sa rangée " + row,
                    col >= wr.rowStartCol(row));
        }
        assertEquals(0, wr.rowForCol(98));
        assertEquals(1, wr.rowForCol(99));
        assertEquals(2, wr.rowForCol(199));
    }

    // ── Chips word-wrap ──────────────────────────────────────────────

    @Test
    public void diagnosticChip_singleRowLineAfterLineEnd() {
        EditorView view = newView(1080);
        injectMetrics(view, 40f, 10f);
        view.setWordWrap(true);
        EditorDocument doc = view.getSession().getDocument();
        int line = 2; // 60 chars < 99 → une rangée
        DiagnosticShift.Diagnostic d = new DiagnosticShift.Diagnostic(
                doc.lineStart(line) + 10, doc.lineStart(line) + 20,
                3, "semicolon expected");
        view.getSession().setDiagnostics(List.of(d));
        float[] m = view.diagnosticChipMetrics(d, line);
        float charWidth = view.metrics.getCharWidth();
        float textAreaLeft = view.metrics.getGutterWidth()
                + view.metrics.getPadLeft();
        int lineLen = doc.lineEnd(line) - doc.lineStart(line);
        int visualLen = view.visualColFor(line, lineLen);
        assertEquals("chip X après la fin de ligne",
                textAreaLeft + (visualLen + EditorView.DIAG_CHIP_GAP_CHARS)
                        * charWidth, m[0], 0.5f);
        // Y : pill centrée dans la (seule) rangée.
        float pillH = view.metrics.getTextSize() * 1.25f;
        assertEquals("chip Y = 1re (et seule) rangée",
                view.docLineToY(line)
                        + (view.metrics.getLineHeight() - pillH) * 0.5f,
                m[1], 0.5f);
    }

    @Test
    public void diagnosticChip_multiRowLinePlacedAfterLastRow() {
        // Ligne indentée de 8 + 191 chars = 199 → 3 rangées (99 + 91 + 9).
        EditorView view = newView(1080);
        injectMetrics(view, 40f, 10f);
        EditorSession session = new EditorSession(EditorDocument.of(
                "        " + "x".repeat(191) + "\nsecond line\n"));
        view.setSession(session);
        view.setWordWrap(true);
        DiagnosticShift.Diagnostic d = new DiagnosticShift.Diagnostic(
                10, 20, 3, "long diagnostic message for the chip");
        session.setDiagnostics(List.of(d));

        EditorView.WrapRows wr = view.wrapRowsFor(0, 199);
        assertEquals(3, wr.rows);
        float[] m = view.diagnosticChipMetrics(d, 0);
        // Y : pill centrée dans la DERNIÈRE rangée (2), PAS la première —
        // l'ancienne approximation centrait la chip sur la première.
        float pillH = view.metrics.getTextSize() * 1.25f;
        assertEquals("chip Y sur la DERNIÈRE rangée",
                view.docLineToY(0) + 2 * view.metrics.getLineHeight()
                        + (view.metrics.getLineHeight() - pillH) * 0.5f,
                m[1], 0.5f);
        // X : fin du contenu de la rangée 2 (9 chars) + indent + gap —
        // PAS à la longueur brute (199) qui dépasserait le viewport.
        float charWidth = view.metrics.getCharWidth();
        float textAreaLeft = view.metrics.getGutterWidth()
                + view.metrics.getPadLeft();
        float expectedX = textAreaLeft + wr.wrapIndentCols * charWidth
                + (199 - wr.rowStartCol(2)) * charWidth
                + EditorView.DIAG_CHIP_GAP_CHARS * charWidth;
        assertEquals("chip X après la fin de la DERNIÈRE rangée",
                expectedX, m[0], 0.5f);
        assertTrue("chip visible dans le viewport", m[0] < view.getWidth());
    }

    // ── Caret : source unique caretScreenPos ─────────────────────────

    @Test
    public void caretScreenPos_wrapRowFromSharedGeometry() {
        EditorView view = newView(1080);
        injectMetrics(view, 40f, 10f);
        EditorSession session = new EditorSession(EditorDocument.of(
                "        " + "x".repeat(191) + "\nsecond\n"));
        view.setSession(session);
        view.setWordWrap(true);
        EditorView.WrapRows wr = view.wrapRowsFor(0, 199);
        int col = 150; // rangée 1 : [99, 190)
        int expectedRow = wr.rowForCol(col);
        assertEquals(1, expectedRow);
        float[] pos = view.caretScreenPos(col);
        assertEquals("caret Y sur SA rangée (géométrie partagée)",
                view.docLineToY(0) + expectedRow
                        * view.metrics.getLineHeight(), pos[1], 0.5f);
        float charWidth = view.metrics.getCharWidth();
        float expectedX = view.metrics.getGutterWidth()
                + view.metrics.getPadLeft()
                + (expectedRow > 0 ? wr.wrapIndentCols * charWidth : 0)
                + (col - wr.rowStartCol(expectedRow)) * charWidth;
        assertEquals(expectedX, pos[0], 0.5f);
    }

    // ── maxH : inlays + chips étendent le scroll ─────────────────────

    @Test
    public void maxH_growsWithInlayHintOverflow() {
        // Viewport ÉTROIT (360px) pour que la ligne la plus longue
        // déborde déjà : maxH > 0 avant comme après.
        EditorView view = newView(360);
        injectMetrics(view, 40f, 10f);
        EditorDocument doc = view.getSession().getDocument();
        float before = view.maxH();
        assertTrue("la ligne de 60 chars déborde d'un viewport de 290px",
                before > 0);
        // Un hint de 30 chars sur la ligne la plus longue → longueur
        // visuelle 90 → maxH croît d'exactement 30 chars.
        view.getSession().setInlayHints(List.of(
                new DiagnosticShift.InlayHint(doc.lineStart(2), "x".repeat(30), true)));
        float after = view.maxH();
        assertEquals("maxH doit croître de la largeur du hint",
                before + 30 * view.metrics.getCharWidth(), after, 0.5f);
    }

    @Test
    public void maxH_growsWithChipExtent() {
        EditorView view = newView(360);
        injectMetrics(view, 40f, 10f);
        float before = view.maxH();
        // Le draw pass publie chipExtentContentX (coords contenu hors
        // gutter) ; une chip 500px au-delà du texte étend maxH d'autant.
        view.chipExtentContentX = view.metrics.getPadLeft()
                + (LONGEST + 1) * view.metrics.getCharWidth() + 500f;
        float after = view.maxH();
        assertTrue("maxH doit croître avec le débordement de chip : "
                        + before + " → " + after,
                after > before + 400f);
        view.chipExtentContentX = 0f;
    }
}
