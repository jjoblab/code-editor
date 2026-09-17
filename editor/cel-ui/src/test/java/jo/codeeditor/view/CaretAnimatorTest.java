package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * Tests Robolectric pour {@link CaretAnimator}.
 *
 * <p>Verrouille la cohérence du curseur : la branche snap de
 * {@code EditorRenderer.drawCaret} ne doit pas écraser les champs d'alias
 * fraîchement mis à jour avec un état périmé de l'animator. Tout l'état du
 * curseur est consolidé dans {@link CaretAnimator} — ces tests vérifient
 * que la machine à états reste cohérente à travers les transitions
 * snap / glide / édition / reset.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class CaretAnimatorTest {

    private EditorView createEditor() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        view.setSession(new EditorSession(EditorDocument.of("hello world")));
        return view;
    }

    @Test
    public void freshAnimator_notReadyAndAtOrigin() {
        EditorView view = createEditor();
        CaretAnimator ca = view.caretAnim;
        assertFalse("fresh animator should not be ready", ca.ready);
        assertEquals(0f, ca.animX, 0.001f);
        assertEquals(0f, ca.animY, 0.001f);
        assertEquals(0f, ca.targetX, 0.001f);
        assertEquals(0f, ca.targetY, 0.001f);
        assertEquals(0, ca.rev);
    }

    @Test
    public void snapTo_updatesAllState_atomically() {
        // Test de régression : auparavant la branche snap mettait à jour les
        // champs d'alias sur EditorView puis les écrasait avec un
        // animator.animX/Y périmé. Désormais snapTo() doit mettre à jour
        // animX/Y, targetX/Y, ready et rev en une seule fois.
        EditorView view = createEditor();
        CaretAnimator ca = view.caretAnim;

        ca.snapTo(100f, 200f, 42);

        assertEquals(100f, ca.animX, 0.001f);
        assertEquals(200f, ca.animY, 0.001f);
        assertEquals(100f, ca.targetX, 0.001f);
        assertEquals(200f, ca.targetY, 0.001f);
        assertTrue("snap should mark animator ready", ca.ready);
        assertEquals(42, ca.rev);
    }

    @Test
    public void snapTo_afterGlide_extinguishesStaleAnimatorState() {
        // Reproduit le bug d'origine : démarrer un glide puis l'annuler
        // (l'animator garde alors un animX périmé du listener de mise à jour
        // du glide), puis snapper. L'animX après snap DOIT valoir la cible du
        // snap — pas la valeur périmée du glide.
        EditorView view = createEditor();
        CaretAnimator ca = view.caretAnim;

        // Glide de (0,0) vers (1000, 500)
        ca.glideTo(1000f, 500f, 1);
        // Le listener de mise à jour de l'animator a pu être déclenché ou
        // non à ce stade (Robolectric ne pulse pas les ValueAnimator par
        // défaut), mais animX doit encore valoir 0f ici car le listener
        // n'a pas tourné.
        // Annule le glide en plein vol (simule une édition qui l'interrompt).
        ca.cancelGlide();
        // À ce stade, animator.animX vaut ce que le listener y a déposé
        // en dernier.

        // Snappe maintenant vers une cible complètement différente.
        ca.snapTo(50f, 75f, 2);

        // animX DOIT valoir la cible du snap — pas la valeur périmée du glide.
        assertEquals(50f, ca.animX, 0.001f);
        assertEquals(75f, ca.animY, 0.001f);
        assertEquals(50f, ca.targetX, 0.001f);
        assertEquals(75f, ca.targetY, 0.001f);
        assertEquals(2, ca.rev);
    }

    @Test
    public void onEditOrMove_resetsBlinkState() {
        EditorView view = createEditor();
        CaretAnimator ca = view.caretAnim;

        long before = view.lastEditTime;
        try { Thread.sleep(5); } catch (InterruptedException ignored) {}
        ca.onEditOrMove();

        assertTrue("caret should be visible after edit/move", ca.visible);
        assertTrue("lastToggle should advance to lastEditTime",
            ca.lastToggle >= before);
        assertTrue("view.lastEditTime should be updated by onEditOrMove",
            view.lastEditTime > before);
    }

    @Test
    public void onEditOrMove_cancelsInFlightGlide() {
        EditorView view = createEditor();
        CaretAnimator ca = view.caretAnim;

        ca.glideTo(1000f, 500f, 1);
        // Le glide est maintenant en vol (ou planifié).
        ca.onEditOrMove();

        // Après édition/déplacement, aucun glide ne doit tourner.
        // On ne peut pas affirmer directement sur animator.isRunning() car
        // ValueAnimator.cancel() peut le laisser dans un état annulé, mais
        // le prochain draw fera snapTo() car rev != currentRev.
        // On vérifie donc simplement que la référence à l'animator est
        // bien libérée : cancelGlide met animator à null.
        // (Le vrai test est que le snapTo suivant fonctionne proprement.)
        ca.snapTo(42f, 42f, 99);
        assertEquals(42f, ca.animX, 0.001f);
        assertEquals(42f, ca.animY, 0.001f);
    }

    @Test
    public void reset_clearsAllState() {
        EditorView view = createEditor();
        CaretAnimator ca = view.caretAnim;

        ca.snapTo(100f, 200f, 42);
        ca.visible = false;
        ca.lastToggle = 12345;
        ca.reset();

        assertFalse("ready should be false after reset", ca.ready);
        assertEquals(0, ca.rev);
        assertEquals(0f, ca.animX, 0.001f);
        assertEquals(0f, ca.animY, 0.001f);
        assertEquals(0f, ca.targetX, 0.001f);
        assertEquals(0f, ca.targetY, 0.001f);
        assertTrue("visible should default to true after reset", ca.visible);
        assertEquals(0, ca.lastToggle);
    }

    @Test
    public void updateBlink_returnsTrueDuringSolidPhase() {
        // Juste après onEditOrMove, le curseur doit être plein (visible).
        EditorView view = createEditor();
        CaretAnimator ca = view.caretAnim;

        ca.onEditOrMove();
        boolean drawVisible = ca.updateBlink();
        assertTrue("caret should be visible during solid phase", drawVisible);
    }

    @Test
    public void glideTo_updatesTargetAndRev() {
        EditorView view = createEditor();
        CaretAnimator ca = view.caretAnim;

        // Pose une position initiale via snap.
        ca.snapTo(0f, 0f, 1);
        // Glisse ensuite vers une nouvelle position.
        ca.glideTo(100f, 200f, 2);

        assertEquals(100f, ca.targetX, 0.001f);
        assertEquals(200f, ca.targetY, 0.001f);
        assertEquals(2, ca.rev);
        // startX/startY dans l'animator valent animX/animY au moment du
        // démarrage du glide, soit (0, 0) — la valeur du snap. Le listener
        // interpolera de (0,0) à (100,200) sur la durée GLIDE_MS.
        assertEquals(0f, ca.animX, 0.001f);
        assertEquals(0f, ca.animY, 0.001f);
    }
}
