package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowInputMethodManager;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * R8 (plan 09, E7 + E8) — la politique d'affichage du clavier logiciel :
 * <ul>
 *   <li><b>E7</b> — avec un clavier MATÉRIEL exposé, le tap ne fait pas
 *       surgir le clavier logiciel (l'utilisateur tape dessus) ;</li>
 *   <li><b>E8</b> — en LECTURE SEULE, {@code onCheckIsTextEditor} répond
 *       faux et le tap ne fait plus monter un clavier dont les frappes
 *       seraient silencieusement ignorées.</li>
 * </ul>
 *
 * <p>Les compteurs de show/hide viennent d'un shadow maison qui enveloppe
 * celui de Robolectric.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class HardwareKeyboardSoftInputTest {

    /** Compteur statique posé par le shadow (remis à zéro par test). */
    public static int showCount = 0;
    public static int hideCount = 0;

    /** Shadow comptant showSoftInput / hideSoftInputFromWindow. */
    @org.robolectric.annotation.Implements(InputMethodManager.class)
    public static class CountingImmShadow extends ShadowInputMethodManager {
        @Override
        protected boolean showSoftInput(View view, int flags) {
            showCount++;
            return true;
        }

        @Override
        protected boolean hideSoftInputFromWindow(android.os.IBinder windowToken, int flags) {
            hideCount++;
            return true;
        }
    }

    private static final String DOC = "public class Main {\n    void run() {\n        greet();\n    }\n}\n";
    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    private EditorView newView(Context ctx) {
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        TestMetrics.injecter(view, LINE_H, CHAR_W);
        return view;
    }

    /** Configure la ressources de l'application : clavier matériel exposé ou non. */
    private static void setHardwareKeyboard(Context ctx, boolean exposed) {
        Configuration cfg = ctx.getResources().getConfiguration();
        if (exposed) {
            cfg.keyboard = Configuration.KEYBOARD_QWERTY;
            cfg.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        } else {
            cfg.keyboard = Configuration.KEYBOARD_NOKEYS;
            cfg.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_UNDEFINED;
        }
    }

    private static void sendTap(EditorView view, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        view.onTouchEvent(down);
        down.recycle();
        MotionEvent up = MotionEvent.obtain(t, t + 40, MotionEvent.ACTION_UP, x, y, 0);
        view.onTouchEvent(up);
        up.recycle();
    }

    // ── E7 : clavier matériel exposé → pas de clavier logiciel ───────

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void tapSansClavierMateriel_leClavierLogicielMonte() {
        Context ctx = RuntimeEnvironment.getApplication();
        setHardwareKeyboard(ctx, false);
        EditorView view = newView(ctx);
        showCount = 0;

        sendTap(view, 200f, 60f);

        assertEquals("sans clavier matériel : le tap montre le clavier logiciel",
                1, showCount);
        assertTrue("la demande de clavier est armée", view.wantsKeyboard);
    }

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void tapAvecClavierMateriel_pasDeClavierLogiciel() {
        Context ctx = RuntimeEnvironment.getApplication();
        setHardwareKeyboard(ctx, true);
        EditorView view = newView(ctx);
        showCount = 0;

        sendTap(view, 200f, 60f);

        assertEquals("clavier matériel exposé : le tap ne fait pas surgir le clavier logiciel",
                0, showCount);
        assertFalse("la demande de clavier n'est pas armée", view.wantsKeyboard);
    }

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void boutonPublicAvecClavierMateriel_pasDeClavierLogiciel() {
        Context ctx = RuntimeEnvironment.getApplication();
        setHardwareKeyboard(ctx, true);
        EditorView view = newView(ctx);
        showCount = 0;

        view.showSoftKeyboard();

        assertEquals("showSoftKeyboard() respecte la politique (l'utilisateur tape sur le matériel)",
                0, showCount);
    }

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void clavierMaterielConnecte_leClavierLogicielSeMasque() {
        Context ctx = RuntimeEnvironment.getApplication();
        setHardwareKeyboard(ctx, false);
        EditorView view = newView(ctx);
        view.wantsKeyboard = true; // l'utilisateur frappait au logiciel
        hideCount = 0;

        // Un clavier physique vient d'être branché/exposé → configuration.
        Configuration newCfg = new Configuration(view.getResources().getConfiguration());
        newCfg.keyboard = Configuration.KEYBOARD_QWERTY;
        newCfg.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        view.onConfigurationChanged(newCfg);

        assertEquals("le clavier logiciel est masqué à l'exposition du clavier matériel",
                1, hideCount);
        assertFalse("la demande de remontée est désarmée", view.wantsKeyboard);
    }

    // ── E8 : lecture seule → pas d'éditeur texte pour le système ─────

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void lectureSeule_onCheckIsTextEditorRepondFaux() {
        Context ctx = RuntimeEnvironment.getApplication();
        setHardwareKeyboard(ctx, false);
        EditorView view = newView(ctx);

        assertTrue("éditable : la vue est un champ d'édition pour le système",
                view.onCheckIsTextEditor());

        view.getSession().setReadOnly(true);
        assertFalse("lecture seule : la vue n'est plus un champ d'édition (E8)",
                view.onCheckIsTextEditor());
    }

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void lectureSeule_leTapNeMontePasLeClavier() {
        Context ctx = RuntimeEnvironment.getApplication();
        setHardwareKeyboard(ctx, false);
        EditorView view = newView(ctx);
        view.getSession().setReadOnly(true);
        showCount = 0;

        sendTap(view, 200f, 60f);

        assertEquals("lecture seule : le tap ne monte pas un clavier muet (E8)",
                0, showCount);
        assertFalse(view.wantsKeyboard);
    }

    @Test
    @Config(shadows = CountingImmShadow.class)
    public void lectureSeule_leCaretEstToujoursPlace() {
        Context ctx = RuntimeEnvironment.getApplication();
        setHardwareKeyboard(ctx, false);
        EditorView view = newView(ctx);
        view.getSession().setReadOnly(true);

        // Le tap place quand même le caret : la lecture reste navigable,
        // seule la SAISIE est interdite.
        sendTap(view, 200f, 60f);

        assertTrue("le tap place le caret (navigation de lecture)",
                view.getSession().getSelection().isCursor());
    }
}
