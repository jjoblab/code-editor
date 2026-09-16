package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * v3.36.0 — Tests de la sheet diagnostic groupée par ligne (roadmap item
 * 5, portage {@code diagnosticsByStartLine} de CodeAssist v3.20).
 *
 * <p>Avant v3.36.0, une ligne portant une erreur ET un warning ne
 * surface que la plus sévère : le warning était inatteignable depuis la
 * chip. Vérifie : le groupe Error/Warning par ligne (chip group + badge
 * count), le hit-test qui porte le groupe, l'ouverture de la sheet
 * groupée (multi) vs le popup détail (mono), la géométrie partagée
 * (rows + close), le routage du tap sur une rangée → popup détail, et le
 * smoke render complet (chips avec badge + sheet) sans crash.</p>
 *
 * @since v3.36.0
 */
@RunWith(RobolectricTestRunner.class)
public class DiagnosticGroupedSheetTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
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

    /** A view whose line 2 carries one error + one warning + one info. */
    private EditorView viewWithGroup() {
        EditorView view = newView();
        injectMetrics(view, 40f, 10f);
        EditorDocument doc = view.getSession().getDocument();
        String text = doc.getText().toString();
        int g = text.indexOf("greet");
        int n = text.indexOf("name");
        int i = text.indexOf("greet") + 1;
        List<DiagnosticShift.Diagnostic> diags = new ArrayList<>();
        diags.add(new DiagnosticShift.Diagnostic(g, g + 5, 3, "Cannot resolve method greet"));
        diags.add(new DiagnosticShift.Diagnostic(n, n + 4, 2, "Unused argument 'name'"));
        diags.add(new DiagnosticShift.Diagnostic(i, i + 1, 1, "Info-level note"));
        view.getSession().setDiagnostics(diags);
        return view;
    }

    /** Simulates a full tap (DOWN + UP at the same spot) — routes through
     * the real gesture pipeline (modal sheetGesture included). */
    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    // ── Chip group + badge ────────────────────────────────────────

    @Test
    public void chipDiagnosticsForLine_errorWarningOnlySeveritySorted() {
        EditorView view = viewWithGroup();
        // Line 2's chip group = Error + Warning (info stays squiggle-only).
        List<DiagnosticShift.Diagnostic> group = view.chipDiagnosticsForLine(2);
        assertEquals(2, group.size());
        assertEquals(3, group.get(0).severity);
        assertEquals(2, group.get(1).severity);
        // Line 1 has nothing.
        assertTrue(view.chipDiagnosticsForLine(1).isEmpty());
        // Legacy accessor: the most severe diagnostic (v2.31 contract).
        DiagnosticShift.Diagnostic primary = view.chipDiagnosticForLine(2);
        assertNotNull(primary);
        assertEquals(3, primary.severity);
    }

    @Test
    public void chipMetrics_badgeCountWidensThePill() {
        EditorView view = viewWithGroup();
        DiagnosticShift.Diagnostic err = view.chipDiagnosticForLine(2);
        float[] without = view.diagnosticChipMetrics(err, 2, 0);
        float[] with = view.diagnosticChipMetrics(err, 2, 2);
        assertNotNull(without);
        assertNotNull(with);
        assertTrue("badge must widen the pill", with[2] > without[2]);
        assertEquals(0f, without[7], 0f); // badgeD = 0 without badge
        assertTrue(with[7] > 0f);         // badgeD > 0 with badge
        // 2-arg legacy overload behaves like badgeCount=0.
        float[] legacy = view.diagnosticChipMetrics(err, 2);
        assertEquals(without[2], legacy[2], 0.01f);
    }

    @Test
    public void findDiagnosticChipHitAt_carriesTheWholeGroup() {
        EditorView view = viewWithGroup();
        // Compute the chip's rectangle from the shared metrics…
        List<DiagnosticShift.Diagnostic> group = view.chipDiagnosticsForLine(2);
        float[] m = view.diagnosticChipMetrics(group.get(0), 2, group.size());
        assertNotNull(m);
        float cx = m[0] + m[2] * 0.5f;
        float cy = m[1] + m[3] * 0.5f;
        // …and hit-test it.
        EditorView.DiagnosticChipHit hit = view.findDiagnosticChipHitAt(cx, cy);
        assertNotNull(hit);
        assertEquals(2, hit.line);
        assertEquals(2, hit.diagnostics.size());
        assertEquals(3, hit.primary().severity);
        // The legacy single-diagnostic accessor still works.
        assertEquals(view.chipDiagnosticForLine(2), view.findDiagnosticChipAt(cx, cy));
        // A point far from the chip misses.
        assertNull(view.findDiagnosticChipHitAt(m[0] + m[2] + 500f, cy));
    }

    // ── Sheet show / dismiss / routing ────────────────────────────

    @Test
    public void showDiagnosticListSheet_multiOpensGroupedSheet_singleOpensDetail() {
        EditorView view = viewWithGroup();
        // Multi → grouped sheet.
        view.showDiagnosticListSheet(2);
        assertTrue(view.isDiagnosticListSheetVisible());
        assertFalse(view.diagnosticPopupVisible);
        float[] m = view.diagnosticListSheetMetrics();
        assertNotNull(m);
        assertEquals(3f, m[4], 0f); // rowCount = 3 (error + warning + info)
                                    // the sheet lists ALL diagnostics of
                                    // the line — info included (CodeAssist
                                    // parity: every diagnostic reachable)
        assertEquals(0f, m[5], 0f); // no truncation row
        assertTrue(m[0] < m[1]);    // panelTop < panelBottom

        // Dismiss (public API).
        view.dismissDiagnosticListSheet();
        assertFalse(view.isDiagnosticListSheetVisible());
        assertNull(view.diagnosticListSheetMetrics());

        // Single diagnostic on line 0 → straight to the detail popup.
        EditorDocument doc = view.getSession().getDocument();
        int p = doc.getText().toString().indexOf("public");
        List<DiagnosticShift.Diagnostic> one = new ArrayList<>();
        one.add(new DiagnosticShift.Diagnostic(p, p + 6, 3, "solo error"));
        view.getSession().setDiagnostics(one);
        view.showDiagnosticListSheet(0);
        assertFalse("single diagnostic opens the DETAIL popup directly",
                view.isDiagnosticListSheetVisible());
        assertTrue(view.diagnosticPopupVisible);
        assertNotNull(view.diagnosticPopupItem);
        assertEquals("solo error", view.diagnosticPopupItem.message);

        // Out-of-range line is a no-op.
        view.dismissDiagnosticPopup();
        view.showDiagnosticListSheet(99);
        assertFalse(view.isDiagnosticListSheetVisible());
        assertFalse(view.diagnosticPopupVisible);
    }

    @Test
    public void listSheetMetrics_capRowsAndReportTruncation() {
        EditorView view = newView();
        injectMetrics(view, 40f, 10f);
        EditorDocument doc = view.getSession().getDocument();
        int g = doc.getText().toString().indexOf("greet");
        List<DiagnosticShift.Diagnostic> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new DiagnosticShift.Diagnostic(g, g + 5, i % 2 == 0 ? 3 : 2,
                    "problem " + i));
        }
        view.getSession().setDiagnostics(many);
        view.showDiagnosticListSheet(2);
        float[] m = view.diagnosticListSheetMetrics();
        assertNotNull(m);
        assertEquals(EditorView.DIAG_LIST_MAX_ROWS, (int) m[4]);
        assertEquals(1f, m[5], 0f); // truncation row shown
    }

    @Test
    public void tapRow_opensDetailPopupForThatDiagnostic() {
        EditorView view = viewWithGroup();
        view.showDiagnosticListSheet(2);
        float[] m = view.diagnosticListSheetMetrics();
        assertNotNull(m);
        // Tap the second row (the warning — previously unreachable).
        float rowY = m[0] + m[2] + 1.5f * m[3];
        float x = view.getWidth() * 0.5f;
        tap(view, x, rowY);
        assertFalse("row tap closes the grouped sheet", view.isDiagnosticListSheetVisible());
        assertTrue("row tap opens the detail popup", view.diagnosticPopupVisible);
        assertNotNull(view.diagnosticPopupItem);
        assertEquals("Unused argument 'name'", view.diagnosticPopupItem.message);
    }

    @Test
    public void tapScrim_dismissesGroupedSheet() {
        EditorView view = viewWithGroup();
        view.showDiagnosticListSheet(2);
        // Tap well above the panel (in the scrim area).
        tap(view, view.getWidth() * 0.5f, 10f);
        assertFalse(view.isDiagnosticListSheetVisible());
    }

    // ── Smoke render ──────────────────────────────────────────────

    @Test
    public void smokeRender_chipsWithBadgeAndGroupedSheet_noCrash() {
        EditorView view = viewWithGroup();
        Bitmap bmp = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        view.draw(canvas);
        // With the grouped sheet up.
        view.showDiagnosticListSheet(2);
        view.draw(canvas);
        // With the detail popup up.
        view.dismissDiagnosticListSheet();
        view.showDiagnosticPopup(view.chipDiagnosticForLine(2),
                view.chipDiagnosticForLine(2).start);
        view.draw(canvas);
        bmp.recycle();
    }
}
