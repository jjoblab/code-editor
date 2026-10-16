package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.session.EditorSession;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * B17 (retour terrain) — <b>l'appui long ne montre PLUS le survol
 * (quick doc)</b> : le popup s'ouvrait en même temps que le toolbar de
 * sélection, doublonnant l'icône Docs ℹ de la pill — l'unique chemin
 * d'affichage VOLONTAIRE (avec F1 et l'appui long sur un inlay hint).
 *
 * <ul>
 *   <li><b>Appui long</b> — sélection du mot + poignées + pill OUI ;
 *       resolveur de quick doc JAMAIS consulté, popup jamais visible ;</li>
 *   <li><b>Contrôle positif</b> — l'icône Docs ℹ de la pill ouvre bien
 *       le quick doc (le resolveur répond, la popup apparaît) : le
 *       retrait ne casse pas le chemin volontaire ;</li>
 *   <li><b>Contrat de l'appui long</b> — le mot sous le doigt reste
 *       sélectionné (le drag-select et la cascade de taps vivent).</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class LongPressNoQuickDocTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        int count = 0;\n"      // 2
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Consulté par le resolveur : doit rester FALSE à l'appui long. */
    private final AtomicBoolean resolverConsulté = new AtomicBoolean(false);

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        session.setLanguage("java");
        // Le resolveur répond un doc NON VIDE : si l'appui long
        // déclenchait encore showQuickDoc, la popup apparaîtrait.
        view.setQuickDocResolver((text, offset) -> {
            resolverConsulté.set(true);
            return "**run**() — méthode de démonstration du quick doc.";
        });
        view.measure(480, 1920);
        view.layout(0, 0, 480, 1920);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        return view;
    }

    private static void longPress(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down,
                MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        SystemClock.sleep(600);
        idle();
    }

    private static void tap(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down,
                MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        MotionEvent u = MotionEvent.obtain(down, down + 40,
                MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(u);
        u.recycle();
        idle();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void appuiLong_sélectionEtPill_sansQuickDoc() {
        EditorView view = newView();

        // Position écran du mot « count » (ligne 2).
        int offset = DOC.indexOf("count");
        float[] pos = view.caretScreenPos(offset);
        longPress(view, pos[0] + CHAR_W, pos[1] + LINE_H * 0.5f);

        // Le contrat de l'appui long reste INTACT.
        assertTrue("le mot est sélectionné", view.handlesVisible);
        assertTrue("la pill de sélection s'affiche", view.selectionToolbarVisible);
        assertFalse("la sélection n'est pas réduite au caret",
                view.getSession().getSelection().isCursor());

        // B17 : le quick doc ne se déclenche PLUS — le resolveur n'est
        // même pas consulté (borne : 800 ms pour tout livreur tardif).
        boolean résolu = jo.codeeditor.AttenteBornee.jusquA(
                800, 20, resolverConsulté::get,
                LongPressNoQuickDocTest::idle);
        assertFalse("le resolveur de quick doc ne doit PAS être consulté"
                + " à l'appui long (B17)", résolu);
        assertFalse("aucun popup survol à l'appui long (B17)",
                view.quickDocVisible);
    }

    @Test
    public void icôneDocsDeLaPill_ouvreToujoursLeQuickDoc() {
        EditorView view = newView();

        // Sélection armée + pill visible (état post-appui-long, sans
        // passer par le geste — l'appui long est déjà couvert ci-dessus).
        int start = DOC.indexOf("count");
        view.getSession().setSelection(Selection.range(start, start + 5));
        view.handlesVisible = true;
        view.selectionToolbarVisible = true;

        jo.codeeditor.view.popup.EditorPopupAnchors.SelectionToolbarMetrics m =
                view.selectionToolbarMetrics();
        assertNotNull(m);
        int docsIdx = -1;
        for (int i = 0; i < m.count; i++) {
            if (m.action[i] == EditorView.SEL_ACT_DOCS) {
                docsIdx = i;
                break;
            }
        }
        assertTrue("l'icône Docs ℹ existe sur la pill", docsIdx >= 0);

        tap(view, m.itemX[docsIdx] + m.itemW[docsIdx] * 0.5f,
                m.y + m.h * 0.5f);

        // Contrôle positif : le chemin VOLONTAIRE ouvre le survol.
        assertTrue("le quick doc s'ouvre depuis l'icône Docs ℹ",
                jo.codeeditor.AttenteBornee.jusquA(
                        5000, 20, () -> view.quickDocVisible,
                        LongPressNoQuickDocTest::idle));
        assertTrue("le resolveur a bien été consulté (chemin volontaire)",
                resolverConsulté.get());
    }
}
