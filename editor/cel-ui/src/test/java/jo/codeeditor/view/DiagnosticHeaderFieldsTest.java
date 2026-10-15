package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.view.popup.EditorPopupAnchors;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * D4 (P2) — verrouille l'<b>en-tête enrichi</b> des diagnostics (popup
 * ancré + fiche basse) :
 *
 * <ul>
 *   <li><b>champs transportés</b> — le code et la source du serveur
 *       arrivent jusqu'au libellé (SPI → DiagnosticShift → rendu) ;</li>
 *   <li><b>ligne/colonne 1-based</b> — convention IDE, calculée du
 *       début de plage ;</li>
 *   <li><b>badge de comptage</b> — « ×N » quand d'autres diagnostics
 *       partagent EXACTEMENT la même plage ;</li>
 *   <li><b>en-tête sobre</b> — un diagnostic sans code ni source ne
 *       multiplie pas les séparateurs vides ;</li>
 *   <li><b>rendu réel</b> — le popup ancré PEINT le libellé enrichi
 *       (capture drawText d'une frame).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code EditorPendingTapDismissTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class DiagnosticHeaderFieldsTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1 (début ligne 2 : 37)
        + "        greet(name);\n"        // 2 — « greet » 45..50 (col 8)
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;
    private static final float PAD_LEFT = 5f;
    private static final float GUTTER_W = 70f;

    private static final int GREET = DOC.indexOf("greet");

    /** Canvas d'enregistrement : capture chaque drawText. */
    private static final class RecordingCanvas extends Canvas {
        final List<String> texts = new ArrayList<>();
        @Override
        public void drawText(String text, float x, float y, Paint paint) {
            texts.add(text);
            super.drawText(text, x, y, paint);
        }
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        session.setSelection(0);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    @Test
    public void libelle_codeSourceLigneColonne() {
        EditorView view = newView();
        view.getSession().setDiagnostics(Arrays.asList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3,
                        "Cannot resolve method 'greet'", false,
                        "ecj:67", "java")));
        DiagnosticShift.Diagnostic d = view.getSession().getDiagnostics().get(0);

        // Ligne 3 (1-based), colonne 9 (1-based) : offset 45 = ligne 2
        // col 8 en 0-based.
        assertEquals("Error · ecj:67 · java · 3:9",
                EditorPopupAnchors.diagnosticHeaderLabel(view, d));
    }

    @Test
    public void badge_deComptage_memePlageExacte() {
        EditorView view = newView();
        DiagnosticShift.Diagnostic a = new DiagnosticShift.Diagnostic(
                GREET, GREET + 5, 3, "first", false, "ecj:1", "java");
        DiagnosticShift.Diagnostic b = new DiagnosticShift.Diagnostic(
                GREET, GREET + 5, 2, "second");
        DiagnosticShift.Diagnostic elsewhere = new DiagnosticShift.Diagnostic(
                GREET, GREET + 6, 1, "plage différente");
        view.getSession().setDiagnostics(Arrays.asList(a, b, elsewhere));

        String label = EditorPopupAnchors.diagnosticHeaderLabel(view, a);
        assertTrue("le badge ×2 doit suivre les deux diagnostics de la "
                + "plage EXACTE [" + GREET + "," + (GREET + 5) + ") — label="
                + label, label.endsWith("×2"));
    }

    @Test
    public void diagnosticNu_enTeteSobre() {
        EditorView view = newView();
        view.getSession().setDiagnostics(Arrays.asList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3, "err")));
        DiagnosticShift.Diagnostic d = view.getSession().getDiagnostics().get(0);

        // Pas de séparateurs vides : sévérité + position seulement.
        assertEquals("Error · 3:9",
                EditorPopupAnchors.diagnosticHeaderLabel(view, d));
    }

    @Test
    public void popupAncre_peintLEnTeteEnrichi() {
        EditorView view = newView();
        view.getSession().setDiagnostics(Arrays.asList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3,
                        "Cannot resolve method 'greet'", false,
                        "ecj:67", "java")));

        // Tap au cœur de « greet » (col 10) → popup ancré ouvert (D1).
        float[] pos = {GUTTER_W + PAD_LEFT + 10 * CHAR_W + CHAR_W * 0.5f,
                PAD_TOP + 2 * LINE_H + LINE_H * 0.5f};
        tap(view, pos[0], pos[1]);
        EditorPopupAnchors.SquigglePopupMetrics m =
                EditorPopupAnchors.squigglePopupMetrics(view);
        assertNotNull("le popup ancré doit être ouvert", m);

        RecordingCanvas canvas = new RecordingCanvas();
        view.draw(canvas);
        boolean headerDrawn = false;
        for (String t : canvas.texts) {
            if (t.contains("ecj:67") && t.contains("java")) {
                headerDrawn = true;
                break;
            }
        }
        assertTrue("l'en-tête enrichi doit être PEINT dans le popup (code "
                + "ecj:67 + source java) — textes dessinés : "
                + canvas.texts, headerDrawn);
    }
}
