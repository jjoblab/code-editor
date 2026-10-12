package jo.codeeditor.view;

import jo.codeeditor.view.popup.EditorPopupAnchors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.MotionEvent;
import android.os.SystemClock;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * T2 (P1) — verrouille l'action intégrée <b>de conversion de
 * casse</b> de la pill de sélection (UPPER / lower / Toggle, livrée
 * via le SPI T1) :
 *
 * <ul>
 *   <li><b>UPPER / lower</b> — {@code Locale.ROOT} déterministe,
 *       document muté en une étape d'undo, pill refermée ;</li>
 *   <li><b>Toggle</b> — inversion caractère par caractère, les
 *       non-lettres sont inchangés ;</li>
 *   <li><b>Sélection ré-épinglée</b> — après conversion la sélection
 *       couvre exactement le texte transformé (aller-retour
 *       possible, pattern du commenteur de lignes) ;</li>
 *   <li><b>Moteur pur</b> — {@link BuiltinSelectionActions#convertCase}
 *       : null-safe, identité sur texte sans lettres.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class ConvertCaseActionTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        int MiXeD = 7;\n"      // 2 — « MiXeD »
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int MIXED = DOC.indexOf("MiXeD");

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
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

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
    }

    private static void armSelection(EditorView view, int start, int end) {
        view.getSession().setSelection(Selection.range(start, end));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;
    }

    /** Tap sur l'item injecté portant le libellé donné. */
    private static void tapLabeled(EditorView view, String label) {
        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        int idx = -1;
        for (int i = 0; i < m.count; i++) {
            if (label.equals(m.label[i])) { idx = i; break; }
        }
        assertTrue("l'item " + label + " doit être sur la pill", idx >= 0);
        tap(view, m.itemX[idx] + m.itemW[idx] * 0.5f, m.y + m.h * 0.5f);
    }

    @Test
    public void upper_etLower_convertissentEnUneÉtapeDundo() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.convertCase(
                view, BuiltinSelectionActions.CaseMode.UPPER));
        armSelection(view, MIXED, MIXED + 5);
        tapLabeled(view, "UPPER");

        String text = view.getSession().getDocument().getText().toString();
        assertTrue("MiXeD → MIXED", text.contains("MIXED"));
        assertFalse("la pill se referme", view.selectionToolbarVisible);
        Selection sel = view.getSession().getSelection();
        assertEquals("la sélection est ré-épinglée sur le texte converti",
                text.indexOf("MIXED"), sel.start);
        assertEquals(text.indexOf("MIXED") + 5, sel.end);

        view.getSession().undo();
        assertEquals("une seule étape d'undo",
                DOC, view.getSession().getDocument().getText().toString());
    }

    @Test
    public void toggle_inverseChaqueLettreSansToucherLesNonLettres() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.convertCase(
                view, BuiltinSelectionActions.CaseMode.TOGGLE));
        armSelection(view, MIXED, MIXED + 5);
        tapLabeled(view, "Toggle case");

        String text = view.getSession().getDocument().getText().toString();
        // M→m, i→I, X→x, e→E, D→d : chaque lettre est inversée.
        assertTrue("MiXeD → mIxEd", text.contains("mIxEd"));
    }

    @Test
    public void moteurPur_nullSafeEtIdentitéSurTexteSansLettres() {
        assertEquals("", BuiltinSelectionActions.convertCase((String) null,
                BuiltinSelectionActions.CaseMode.UPPER));
        assertEquals("123 = 456;", BuiltinSelectionActions.convertCase(
                "123 = 456;", BuiltinSelectionActions.CaseMode.TOGGLE));
        assertEquals("ABC", BuiltinSelectionActions.convertCase(
                "abc", BuiltinSelectionActions.CaseMode.UPPER));
        assertEquals("abc", BuiltinSelectionActions.convertCase(
                "ABC", BuiltinSelectionActions.CaseMode.LOWER));
        // Locale.ROOT : le i pointu turc ne fuit pas dans la conversion.
        assertEquals("I", BuiltinSelectionActions.convertCase(
                "i", BuiltinSelectionActions.CaseMode.UPPER));
    }
}
