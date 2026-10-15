package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;
import android.view.MotionEvent;
import android.os.SystemClock;

import jo.codeeditor.view.popup.EditorPopupAnchors;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * P2 « mouvements de lignes » (lot 2 jalon J6) — les actions intégrées
 * <b>« Move up » / « Move down »</b> de la pill, livrées via le MÊME
 * SPI que les actions d'hôte (T1) : le bloc de lignes de la sélection
 * bouge, la sélection est ré-épinglée, la pill se referme (contrat
 * d'action), l'action est estompée au bord du document dans la
 * direction où plus rien ne bouge.
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code DuplicateActionTest}).</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class MoveLinesActionTest {

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
    public void tapSurMoveDown_bougeLaLigneEtRefermeLaPill() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.moveLinesDown(view));
        view.addSelectionAction(BuiltinSelectionActions.moveLinesUp(view));
        armSelection(view);

        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        // Les items injectés suivent les 4 canoniques d'édition.
        assertEquals("Move down", m.label[4]);
        assertEquals("Move up", m.label[5]);
        assertFalse("activée : la ligne peut descendre", m.itemDisabled[4]);
        assertFalse("activée : la ligne peut monter", m.itemDisabled[5]);

        float[] pos = itemCenter(view, 4); // Move down
        tap(view, pos[0], pos[1]);

        assertEquals("la ligne 2 a été échangée avec la ligne 3",
                DOC.replace("        greet(name);\n    }\n",
                        "    }\n        greet(name);\n"),
                view.getSession().getDocument().getText().toString());
        assertFalse("la pill se referme (contrat d'action)",
                view.selectionToolbarVisible);
        assertFalse("les poignées se masquent", view.handlesVisible);
    }

    @Test
    public void tapSurMoveUp_laSélectionResteSurLeBlocDéplacé() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.moveLinesUp(view));
        armSelection(view);

        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertEquals("Move up", m.label[4]);

        float[] pos = itemCenter(view, 4);
        tap(view, pos[0], pos[1]);

        // Ligne 2 au-dessus de la ligne 1 : « greet(name); » précède
        // « void run() { » — la sélection suit le bloc déplacé.
        String text = view.getSession().getDocument().getText().toString();
        int nameNow = text.indexOf("name");
        assertEquals("la sélection est ré-épinglée sur « name »",
                Selection.range(nameNow, nameNow + 4),
                view.getSession().getSelection());
        assertTrue("la ligne déplacée précède l'ancienne ligne 1",
                text.indexOf("greet(name)") < text.indexOf("void run()"));
    }

    @Test
    public void estompéeAuBordDansLaDirectionImPossible() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.moveLinesUp(view));
        view.addSelectionAction(BuiltinSelectionActions.moveLinesDown(view));
        // Sélection sur la PREMIÈRE ligne : monter est impossible.
        view.getSession().setSelection(Selection.range(0, 6)); // « publi »
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;

        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertTrue("Move up estompé en première ligne", m.itemDisabled[4]);
        assertFalse("Move down reste activée", m.itemDisabled[5]);
    }

    @Test
    public void unDéplacement_uneÉtapeDundo() {
        EditorView view = newView();
        view.addSelectionAction(BuiltinSelectionActions.moveLinesDown(view));
        armSelection(view);

        float[] pos = itemCenter(view, 4);
        tap(view, pos[0], pos[1]);

        view.getSession().undo();
        assertEquals("un seul undo restaure le document original",
                DOC, view.getSession().getDocument().getText().toString());
    }
}
