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
 * T2 (P1) — verrouille l'action intégrée <b>« Comment »</b> de la
 * pill de sélection (bascule des commentaires de ligne, livrée via le
 * SPI T1) :
 *
 * <ul>
 *   <li><b>Commenter</b> — un tap préfixe les lignes de la sélection
 *       avec la syntaxe du langage courant ({@code //} en Java) et
 *       referme la pill ;</li>
 *   <li><b>Décommenter</b> — un second passage (re-tap après
 *       re-sélection) retire les préfixes : la bascule est un
 *       aller-retour, la sélection ré-épinglée couvre le bloc ;</li>
 *   <li><b>JSON</b> — langage sans syntaxe de commentaire : l'item
 *       est estompé (drapeau itemDisabled) et son tap rejeté sans
 *       refermer la pill.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class ToggleCommentActionTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** La ligne 2 entière : [start, fin). */
    private static final int LINE2 = DOC.indexOf("        greet");

    private EditorView newView(String language) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        session.setLanguage(language);
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

    /** Sélectionne la ligne 2 et affiche la pill. */
    private static void armLine2(EditorView view) {
        view.getSession().setSelection(Selection.range(LINE2, LINE2 + 17));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;
    }

    private static void tapComment(EditorView view) {
        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        int idx = -1;
        for (int i = 0; i < m.count; i++) {
            if ("Comment".equals(m.label[i])) { idx = i; break; }
        }
        assertTrue("l'item Comment doit être sur la pill", idx >= 0);
        float x = m.itemX[idx] + m.itemW[idx] * 0.5f;
        float y = m.y + m.h * 0.5f;
        tap(view, x, y);
    }

    @Test
    public void tapSurComment_préfixeLesLignesAvecLaSyntaxeDuLangage() {
        EditorView view = newView("java");
        view.addSelectionAction(BuiltinSelectionActions.toggleComment(view));
        armLine2(view);
        tapComment(view);

        String text = view.getSession().getDocument().getText().toString();
        assertEquals("la ligne 2 reçoit le préfixe // du Java",
                "//         greet(name);",
                text.substring(
                        text.indexOf("//"),
                        text.indexOf("//") + "//         greet(name);".length()));
        assertFalse("la pill se referme", view.selectionToolbarVisible);
        assertFalse("les poignées se masquent", view.handlesVisible);
    }

    @Test
    public void secondPassage_décommenteEtRestaureLeDocument() {
        EditorView view = newView("java");
        view.addSelectionAction(BuiltinSelectionActions.toggleComment(view));
        armLine2(view);
        tapComment(view);

        // La sélection est ré-épinglée sur le bloc commenté → un
        // second passage retire les préfixes (aller-retour).
        assertTrue("la sélection survit sur le bloc commenté",
                !view.getSession().getSelection().isCursor());
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;
        tapComment(view);

        assertEquals("l'aller-retour restaure le document original",
                DOC, view.getSession().getDocument().getText().toString());
    }

    @Test
    public void jsonSansCommentaire_itemEstompéTapRejeté() {
        EditorView view = newView("json");
        view.addSelectionAction(BuiltinSelectionActions.toggleComment(view));
        armLine2(view);

        EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        int idx = -1;
        for (int i = 0; i < m.count; i++) {
            if ("Comment".equals(m.label[i])) { idx = i; break; }
        }
        assertTrue(idx >= 0);
        assertTrue("l'item est estompé pour JSON (aucune syntaxe)",
                m.itemDisabled[idx]);

        float x = m.itemX[idx] + m.itemW[idx] * 0.5f;
        float y = m.y + m.h * 0.5f;
        tap(view, x, y);

        assertEquals("le document est inchangé",
                DOC, view.getSession().getDocument().getText().toString());
        assertTrue("la pill reste ouverte (tap rejeté)",
                view.selectionToolbarVisible);
    }
}
