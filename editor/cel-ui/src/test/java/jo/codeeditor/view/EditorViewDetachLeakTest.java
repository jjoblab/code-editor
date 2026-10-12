package jo.codeeditor.view;

import static org.junit.Assert.assertNull;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.ref.WeakReference;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

/**
 * B21h — cycle de vie session→vue après détachement.
 *
 * <p>Une session qui SURVIT au détachement de sa vue (hôte recyclant les
 * sessions entre onglets, rétention hors écran) ne doit pas retenir la
 * vue fortement : sinon la vue et tout son graphe (popups, caches de
 * rendu, painter, loupe) fuient jusqu'à la mort de la session.</p>
 *
 * <p>Avant correctif, {@code onDetachedFromWindow()} purgeait popups et
 * callbacks Handler mais laissait les listeners session→vue posés
 * (imeBridge, cacheShiftListener) : le test échouait (vue toujours
 * accessible depuis la session survivante).</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class, sdk = 34)
public class EditorViewDetachLeakTest {

    @Test
    public void laVueDetacheeNEstPlusRetenueParLaSessionSurvivante() {
        Context context = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(context);
        EditorSession session = new EditorSession(
                EditorDocument.of("class Leak { void f() { int x = 1; } }"));
        view.setSession(session);
        view.onAttachedToWindow(); // cycle de vie manuel (Robolectric)

        // La session SURVIT (retenue par l'hôte) ; seule la vue est lâchée.
        WeakReference<EditorView> ref = new WeakReference<>(view);

        // Le détachement NE passe PAS par setSession(null) — l'hôte garde
        // la session posée dans la vue démontée (recyclage d'onglet). C'est
        // exactement le cas B21h : la session vivante ne doit plus retenir
        // la vue après onDetachedFromWindow.
        view.onDetachedFromWindow();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        view = null;
        forceGc();
        assertNull("La vue détachée ne doit pas être retenue par la session survivante",
                ref.get());
    }

    private static void forceGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            System.runFinalization();
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
