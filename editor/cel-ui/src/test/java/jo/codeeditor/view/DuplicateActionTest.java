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
 * T2 (P1) — verrouille l'action intégrée <b>« Duplicate »</b> de la
 * pill de sélection (livrée via le SPI T1) :
 *
 * <ul>
 *   <li><b>Tap</b> — un tap sur l'item de la pill duplique la
 *       sélection juste après elle-même (état du document), referme
 *       la pill et masque les poignées (contrat Copy/Cut/Paste) ;</li>
 *   <li><b>Undo</b> — la duplication est UNE seule étape d'undo ;</li>
 *   <li><b>Repli caret</b> — l'action n'apparaît pas sans vraie
 *       sélection (la pill collapsed n'expose que Paste/Select all
 *       + icônes).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code SelectionToolbarSpiInjectTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class DuplicateActionTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2 — « name »
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int NAME = DOC.indexOf("name");

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

    /** Sélectionne « name » et affiche la pill (chrome tactile armé). */
    private static void armSelection(EditorView view) {
        view.getSession().setSelection(Selection.range(NAME, NAME + 4));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;
    }

    /** Coordonnées du CENTRE de l'item injecté d'indice donné. */
    private static float[] itemCenter(EditorView view, int idx) {
        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        return new float[]{m.itemX[idx] + m.itemW[idx] * 0.5f, m.y + m.h * 0.5f};
    }

    @Test
    public void tapSurDuplicate_dupliqueLaSélectionEtRefermeLaPill() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.duplicate(view));
        armSelection(view);

        // L'item injecté suit les 4 canoniques d'édition.
        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertEquals("Duplicate", m.label[4]);
        assertFalse("activée avec une session vivante", m.itemDisabled[4]);

        float[] pos = itemCenter(view, 4);
        tap(view, pos[0], pos[1]);

        assertEquals("le texte sélectionné est dupliqué juste après lui-même",
                "greet(namename);",
                view.getSession().getDocument()
                        .subText(DOC.indexOf("greet"), DOC.indexOf("greet") + 16)
                        .toString());
        assertFalse("la pill se referme (contrat Copy/Cut/Paste)",
                view.selectionToolbarVisible);
        assertFalse("les poignées se masquent", view.handlesVisible);
    }

    @Test
    public void laDuplicationEstUneSeuleÉtapeDundo() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.duplicate(view));
        armSelection(view);

        float[] pos = itemCenter(view, 4);
        tap(view, pos[0], pos[1]);

        view.getSession().undo();
        assertEquals("un seul undo restaure le document original",
                DOC, view.getSession().getDocument().getText().toString());
    }

    @Test
    public void sansVraieSélection_lActionNEstPasOfferte() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.duplicate(view));
        // Re-tap caret → pill COLLAPSED (Paste/Select all + icônes).
        view.getSession().setSelection(DOC.indexOf("greet"));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;

        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        for (int i = 0; i < m.count; i++) {
            assertNull("aucune action intégrée en mode collapsed à " + i,
                    m.injected[i]);
        }
    }
}
