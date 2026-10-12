package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * D3 (P2) — verrouille <b>l'appui long sur un squiggle → copie du
 * message</b> (retour terrain : coller le message exact ailleurs).
 *
 * <ul>
 *   <li><b>Appui long au cœur</b> — le message du diagnostic part au
 *       presse-papiers, SANS sélection de mot, SANS pill ni poignées
 *       (le geste est « copier », pas « sélectionner ») ;</li>
 *   <li><b>Multi-sévérité</b> — un warning et une erreur couvrent
 *       l'offset : c'est le message de la plus haute sévérité qui est
 *       copié ;</li>
 *   <li><b>Hors squiggle</b> — l'appui long classique sélectionne le
 *       mot (comportement historique), le presse-papiers ne change
 *       pas ;</li>
 *   <li><b>Voile Unnecessary</b> — pas tappable (pas de soulignement)
 *       : l'appui long reste la sélection de mot, rien n'est copié.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class SquiggleLongPressCopyTest {

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

    /** Position écran (centre) du caractère de colonne {@code col} sur la ligne 2. */
    private static float[] charCenterOnLine2(int col) {
        return new float[]{
            GUTTER_W + PAD_LEFT + col * CHAR_W + CHAR_W * 0.5f,
            PAD_TOP + 2 * LINE_H + LINE_H * 0.5f};
    }

    /** Appui long (DOWN + ~600 ms d'horloge fantôme) sans bouger. */
    private static void longPress(EditorView view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(d);
        d.recycle();
        SystemClock.sleep(600);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static String clipboardText(EditorView view) {
        ClipboardManager cm = (ClipboardManager) view.getContext()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            return null;
        }
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        CharSequence text = item != null ? item.getText() : null;
        return text != null ? text.toString() : null;
    }

    @Test
    public void appuiLongAuCœurDuSquiggle_copieLeMessageSansSélectionner() {
        EditorView view = newView();
        view.getSession().setDiagnostics(Collections.singletonList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3,
                        "Cannot resolve method 'greet'")));

        // Cœur de « greet » : col 10 → offset 48, strictement intérieur.
        float[] pos = charCenterOnLine2(10);
        longPress(view, pos[0], pos[1]);

        assertEquals("le presse-papiers contient le message exact",
                "Cannot resolve method 'greet'", clipboardText(view));
        assertTrue("aucune sélection de mot (le geste est « copier »)",
                view.getSession().getSelection().isCursor());
        assertFalse("pas de pill", view.selectionToolbarVisible);
        assertFalse("pas de poignées", view.handlesVisible);
    }

    @Test
    public void multiSévérités_leMessageLePlusSévèreEstCopié() {
        EditorView view = newView();
        view.getSession().setDiagnostics(Arrays.asList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 1,
                        "'greet' is deprecated"),
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3,
                        "Cannot resolve method 'greet'")));

        float[] pos = charCenterOnLine2(10);
        longPress(view, pos[0], pos[1]);

        assertEquals("l'ERREUR gagne sur le warning",
                "Cannot resolve method 'greet'", clipboardText(view));
    }

    @Test
    public void horsSquiggle_lAppuiLongClassiqueEstInchangé() {
        EditorView view = newView();
        view.getSession().setDiagnostics(Collections.singletonList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 3,
                        "Cannot resolve method 'greet'")));
        // Marqueur : clipboard vide avant le geste.
        assertNull(clipboardText(view));

        // « name » (col 15) : hors de la plage squigglée [45, 50).
        float[] pos = charCenterOnLine2(15);
        longPress(view, pos[0], pos[1]);

        assertNull("rien n'a été copié hors squiggle", clipboardText(view));
        assertEquals("le mot est sélectionné (comportement historique)",
                "name", view.getSession().selectedText());
        assertTrue("la pill est armée", view.selectionToolbarVisible);
    }

    @Test
    public void voileUnnecessary_pasTappableRienNEstCopié() {
        EditorView view = newView();
        view.getSession().setDiagnostics(Collections.singletonList(
                new DiagnosticShift.Diagnostic(GREET, GREET + 5, 4,
                        "'greet' is unused", true)));

        float[] pos = charCenterOnLine2(10);
        longPress(view, pos[0], pos[1]);

        assertNull("le voile gris n'est pas copiable", clipboardText(view));
        assertEquals("l'appui long reste la sélection de mot",
                "greet", view.getSession().selectedText());
    }
}
