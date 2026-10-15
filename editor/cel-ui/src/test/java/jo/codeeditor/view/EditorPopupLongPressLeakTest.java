package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowSystemClock;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B7 (retour terrain n°4) — les popups ne fuient plus l'appui long.
 *
 * <p>Symptôme du terrain : « pendant l'affichage d'un popup j'ai fait un
 * long click, ça a fait le long click sélection pour draguer ». Cause :
 * le {@code GestureDetector} est alimenté AVANT la cascade de hit-test
 * des popups — son minuteur d'appui long se déclenchait donc MÊME quand
 * le geste appartenait à un popup (complétion, scrim modal…), démarrait
 * une sélection de mot pour drag derrière lui et volait le focus.</p>
 *
 * <ul>
 *   <li><b>DANS un popup</b> (complétion) : l'appui long ne sélectionne
 *       RIEN — pas de mot, pas de pill, pas de poignées, pas de drag
 *       armé ; le relâchement suivra le chemin du tap du popup ;</li>
 *   <li><b>HORS du popup</b> : l'appui long garde son comportement
 *       normal (sélection du mot) — le garde ne sur-bloque pas ;</li>
 *   <li><b>Scrim modal</b> (fiche de diagnostic) : tout appui long est
 *       mort, même loin de la fiche.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class EditorPopupLongPressLeakTest {

    private static final String DOC =
        "public class Main {\n"          // 0
        + "    void run() {\n"            // 1
        + "        greet(name);\n"        // 2 — « greet » cols 8..12
        + "    }\n"                       // 3
        + "}\n";                          // 4

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;
    private static final float PAD_LEFT = 5f;
    private static final float GUTTER_W = 70f;

    /** « greet » : [45, 50). */
    private static final int GREET = DOC.indexOf("greet");

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        session.setSelection(0);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    /** Position écran (centre) du caractère de colonne {@code col} sur la ligne {@code line}. */
    private static float[] charCenter(int line, int col) {
        return new float[]{
            GUTTER_W + PAD_LEFT + col * CHAR_W + CHAR_W * 0.5f,
            PAD_TOP + line * LINE_H + LINE_H * 0.5f};
    }

    /** Appui long (DOWN + ~600 ms d'horloge fantôme) sans bouger —
     *  le vrai chemin GestureDetector.onLongPress. */
    private static void longPress(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        SystemClock.sleep(600);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Montre la complétion sur « greet » (caret à la fin du token). */
    private void showCompletion(EditorView view) {
        view.getSession().setSelection(GREET + 5);
        view.setCompletionItems(Collections.singletonList(
                new jo.codeeditor.completion.CompletionSession.Item(
                        "greet", "void greet(String)", "greet", "m", 0, 0, false, false)),
                GREET, "gre");
        assertTrue("pré-condition : complétion visible",
                view.isCompletionVisible());
    }

    @Test
    public void appuiLongDANSComplétion_aucuneSélectionPourDrag() {
        EditorView view = newView();
        showCompletion(view);
        // Centre du popup de complétion — le point appartient au popup.
        float[] a = view.completionPopupAnchor();
        float cx = a[0] + a[2] * 0.5f;
        float cy = a[1] + a[3] * 0.5f;

        longPress(view, cx, cy);

        // Le popup possède le geste : le mot SOUS le doigt n'est PAS
        // sélectionné, la pill et les poignées n'apparaissent pas.
        assertTrue("aucune sélection de mot derrière le popup",
                view.getSession().getSelection().isCursor());
        assertFalse("pas de pill de sélection", view.selectionToolbarVisible);
        assertFalse("pas de poignées", view.handlesVisible);
        // Le popup reste ouvert — son tap garde son chemin.
        assertTrue("la complétion reste visible", view.isCompletionVisible());
    }

    @Test
    public void appuiLongHORSComplétion_sélectionNormaleConservée() {
        EditorView view = newView();
        showCompletion(view);
        // « public » ligne 0 col 2 — loin de l'ancre de complétion.
        float[] pos = charCenter(0, 2);

        longPress(view, pos[0], pos[1]);

        // Le garde ne sur-bloque pas : l'appui long hors popup sélectionne
        // toujours le mot (comportement historique inchangé).
        assertFalse("le mot est sélectionné (comportement normal conservé)",
                view.getSession().getSelection().isCursor());
        assertTrue("la pill apparaît", view.selectionToolbarVisible);
    }

    @Test
    public void appuiLongSousScrimModal_deDiagnostic_mortPartout() {
        EditorView view = newView();
        // La fiche de diagnostic est MODALE : son scrim couvre l'éditeur.
        view.diagnosticPopupVisible = true;
        // « public » ligne 0 col 2 — loin de la fiche, SOUS le scrim.
        float[] pos = charCenter(0, 2);

        longPress(view, pos[0], pos[1]);

        assertTrue("aucune sélection sous le scrim modal",
                view.getSession().getSelection().isCursor());
        assertFalse("pas de pill sous le scrim", view.selectionToolbarVisible);
    }
}
