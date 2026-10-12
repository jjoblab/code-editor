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
 * Tests de la sheet diagnostic groupée par ligne.
 *
 * <p>Sans groupement, une ligne portant une erreur ET un warning
 * n'affichait que la plus sévère : le warning restait inatteignable depuis
 * la chip. Vérifie : le groupe Error/Warning par ligne (chip group + badge
 * count), le hit-test qui porte le groupe, l'ouverture de la sheet groupée
 * (multi) vs le popup détail (mono), la géométrie partagée (rows + close),
 * le routage du tap sur une rangée → popup détail, et le smoke render
 * complet (chips avec badge + sheet) sans crash.</p>
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

    /** Une vue dont la ligne 2 porte une erreur + un warning + une info. */
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

    /** Simule un tap complet (DOWN + UP au même endroit) — passe par le
     * vrai pipeline de gestes (sheetGesture modal inclus). */
    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    // ── Groupe chip + badge ────────────────────────────────────────

    @Test
    public void chipDiagnosticsForLine_errorWarningOnlySeveritySorted() {
        EditorView view = viewWithGroup();
        // Le groupe de chips de la ligne 2 = Error + Warning (l'info
        // reste juste soulignée, sans chip).
        List<DiagnosticShift.Diagnostic> group = view.chipDiagnosticsForLine(2);
        assertEquals(2, group.size());
        assertEquals(3, group.get(0).severity);
        assertEquals(2, group.get(1).severity);
        // La ligne 1 n'a rien.
        assertTrue(view.chipDiagnosticsForLine(1).isEmpty());
        // Accesseur historique : le diagnostic le plus sévère.
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
        assertEquals(0f, without[7], 0f); // badgeD = 0 sans badge
        assertTrue(with[7] > 0f);         // badgeD > 0 avec badge
        // La surcharge historique à 2 arguments se comporte comme badgeCount=0.
        float[] legacy = view.diagnosticChipMetrics(err, 2);
        assertEquals(without[2], legacy[2], 0.01f);
    }

    @Test
    public void findDiagnosticChipHitAt_carriesTheWholeGroup() {
        EditorView view = viewWithGroup();
        // Calcule le rectangle de la chip depuis les métriques partagées…
        List<DiagnosticShift.Diagnostic> group = view.chipDiagnosticsForLine(2);
        float[] m = view.diagnosticChipMetrics(group.get(0), 2, group.size());
        assertNotNull(m);
        float cx = m[0] + m[2] * 0.5f;
        float cy = m[1] + m[3] * 0.5f;
        // …puis le teste au hit-test.
        EditorView.DiagnosticChipHit hit = view.findDiagnosticChipHitAt(cx, cy);
        assertNotNull(hit);
        assertEquals(2, hit.line);
        assertEquals(2, hit.diagnostics.size());
        assertEquals(3, hit.primary().severity);
        // L'accesseur historique mono-diagnostic fonctionne toujours.
        assertEquals(view.chipDiagnosticForLine(2), view.findDiagnosticChipAt(cx, cy));
        // Un point loin de la chip rate.
        assertNull(view.findDiagnosticChipHitAt(m[0] + m[2] + 500f, cy));
    }

    // ── Sheet : ouverture / fermeture / routage ────────────────────

    @Test
    public void showDiagnosticListSheet_multiOpensGroupedSheet_singleOpensDetail() {
        EditorView view = viewWithGroup();
        // Multi → sheet groupée.
        view.showDiagnosticListSheet(2);
        assertTrue(view.isDiagnosticListSheetVisible());
        assertFalse(view.diagnosticPopupVisible);
        float[] m = view.diagnosticListSheetMetrics();
        assertNotNull(m);
        assertEquals(3f, m[4], 0f); // rowCount = 3 (error + warning + info)
                                    // la sheet liste TOUS les diagnostics
                                    // de la ligne — info incluse (chaque
                                    // diagnostic reste atteignable)
        assertEquals(0f, m[5], 0f); // pas de rangée de troncature
        assertTrue(m[0] < m[1]);    // panelTop < panelBottom

        // Fermeture (API publique).
        view.dismissDiagnosticListSheet();
        assertFalse(view.isDiagnosticListSheetVisible());
        assertNull(view.diagnosticListSheetMetrics());

        // Diagnostic unique sur la ligne 0 → directement le popup détail.
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

        // Ligne hors bornes : no-op.
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
        assertEquals(1f, m[5], 0f); // rangée de troncature affichée
    }

    @Test
    public void tapRow_opensDetailPopupForThatDiagnostic() {
        EditorView view = viewWithGroup();
        view.showDiagnosticListSheet(2);
        float[] m = view.diagnosticListSheetMetrics();
        assertNotNull(m);
        // Tape la deuxième rangée (le warning — auparavant inatteignable).
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
        // Tape bien au-dessus du panneau (dans la zone de scrim).
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
        // Avec la sheet groupée ouverte.
        view.showDiagnosticListSheet(2);
        view.draw(canvas);
        // Avec le popup détail ouvert.
        view.dismissDiagnosticListSheet();
        view.showDiagnosticPopup(view.chipDiagnosticForLine(2),
                view.chipDiagnosticForLine(2).start);
        view.draw(canvas);
        bmp.recycle();
    }
}
