package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.model.DefinitionLocation;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * B15 + B16 + B21e (audit) :
 * <ul>
 *   <li><b>B15</b> — les PopupWindow go-to-line / rename (et le popup de
 *       références) doivent être fermés au détachement de la vue :
 *       sinon WindowLeaked + fuite de l'Activity à la rotation ;</li>
 *   <li><b>B16</b> — dismissReferences() bump la génération : une
 *       résolution LSP en vol (jusqu'à 15 s) ne doit pas RE-OUVRIR le
 *       popup que l'utilisateur vient de fermer ;</li>
 *   <li><b>B21e</b> — setSession(null) ne lève plus de NPE et une
 *       session fraîche se rattache ensuite normalement.</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class PopupDetachAndSessionNullTest {

    private static EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of("hello\nworld\n")));
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    @Test
    public void detach_closesGoToLineAndRenamePopups() {
        EditorView view = newView();

        // Ouvre les deux popups à PopupWindow.
        view.showGoToLine();
        view.showRename();
        assertTrue("go-to-line ouvert", view.isGoToLineVisible());
        assertTrue("rename ouvert", view.isRenameVisible());

        // Détachement (rotation / destruction du conteneur) — appel
        // direct du hook protégé (même package).
        view.onDetachedFromWindow();

        assertFalse("go-to-line doit être fermé au détachement (B15)",
                view.isGoToLineVisible());
        assertFalse("rename doit être fermé au détachement (B15)",
                view.isRenameVisible());
        assertFalse("références doivent être fermées au détachement (B15)",
                view.isReferencesVisible());
    }

    @Test
    public void dismissReferences_bumpsGeneration_inFlightResolutionDoesNotReopen()
            throws Exception {
        EditorView view = newView();

        // Résolveur qui BLOQUE : la résolution est « en vol » quand
        // l'utilisateur ferme le popup.
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean resolveDone = new AtomicBoolean(false);
        view.setReferencesResolver((text, caret) -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            resolveDone.set(true);
            List<DefinitionLocation> out = new ArrayList<>();
            out.add(new DefinitionLocation("src/Main.java", 0, "Main"));
            return out;
        });

        // 1. show() — la résolution démarre sur l'exécuteur de features
        //    et bloque dans le resolver.
        view.showReferences();
        Thread.sleep(150);  // l'exécuteur est garanti DANS resolve()

        // 2. L'utilisateur ferme le popup AVANT la fin de la résolution.
        view.dismissReferences();
        assertFalse(view.isReferencesVisible());

        // 3. La résolution se termine APRÈS la fermeture — son apply ne
        //    doit PAS rouvrir le popup (génération bumpée par dismiss).
        release.countDown();
        long deadline = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < deadline) {
            assertFalse("la résolution en vol ne doit pas RE-OUVRIR le popup"
                    + " fermé par l'utilisateur (B16)", view.isReferencesVisible());
            if (resolveDone.get()) break;
            Thread.sleep(25);
        }
        // Marge après la fin de resolve : l'apply suit immédiatement sur
        // le même fil d'exécution.
        Thread.sleep(250);
        assertFalse("le popup reste fermé après la résolution en vol (B16)",
                view.isReferencesVisible());
        assertTrue("la résolution doit avoir eu lieu (précondition)",
                resolveDone.get());
    }

    @Test
    public void setSessionNull_detachesWithoutNPE_andReattaches() {
        EditorView view = newView();
        assertTrue(view.getSession() != null);

        // B21e : avant correctif, NPE sur this.session.setImeListener.
        view.setSession(null);
        assertNull("la session doit être détachée", view.getSession());

        // Le dessin et le toucher survivent à la session nulle.
        view.draw(new android.graphics.Canvas());
        android.view.MotionEvent e = android.view.MotionEvent.obtain(
                0, 0, android.view.MotionEvent.ACTION_DOWN, 50f, 50f, 0);
        view.onTouchEvent(e);
        e.recycle();

        // Une session fraîche se rattache normalement.
        EditorSession fresh = new EditorSession(
                EditorDocument.of("nouveau\ndocument\n"));
        view.setSession(fresh);
        assertTrue(view.getSession() == fresh);
        fresh.setSelection(3);
        assertTrue(view.getSession().getSelection().isCursor());
    }
}
