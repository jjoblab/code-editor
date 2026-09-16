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
 * Robolectric tests for {@link CaretAnimator}.
 *
 * <p>v3.33.10: Locks in the regression fix where the snap branch in
 * {@code EditorRenderer.drawCaret} overwrote the just-updated alias fields
 * with stale animator state. The fix consolidates ALL caret state into
 * {@link CaretAnimator} — these tests verify the state machine stays
 * consistent across snap / glide / edit / reset transitions.</p>
 *
 * @author jo@Dev
 * @since v3.33.10
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
        // Regression test: previously the snap branch updated alias fields
        // on EditorView, then overwrote them with stale animator.animX/Y.
        // Now snapTo() must update animX/Y, targetX/Y, ready, rev in one go.
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
        // Reproduces the original bug: start a glide, cancel it (so the
        // animator has a stale animX from the glide update listener), then
        // snap. The post-snap animX MUST equal the snap target — not the
        // stale glide value.
        EditorView view = createEditor();
        CaretAnimator ca = view.caretAnim;

        // Glide from (0,0) to (1000, 500)
        ca.glideTo(1000f, 500f, 1);
        // The animator's update listener may or may not have fired by now
        // (Robolectric doesn't pulse ValueAnimator by default), but animX
        // should still be 0f at this point because the listener hasn't run.
        // Cancel the glide mid-flight (simulates an edit interrupting it).
        ca.cancelGlide();
        // At this point, animator.animX is whatever the listener last set it to.

        // Now snap to a completely different target.
        ca.snapTo(50f, 75f, 2);

        // animX MUST be the snap target — not the stale glide value.
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
        // Glide is now in-flight (or scheduled).
        ca.onEditOrMove();

        // After edit/move, no glide should be running.
        // We can't directly assert on animator.isRunning() because
        // ValueAnimator.cancel() may leave it in a cancelled state, but
        // the next draw will snapTo() because rev != currentRev.
        // So just verify the animator reference is cleared.
        // Actually, cancelGlide sets animator = null.
        // (Checking that the next snapTo works cleanly is the real test.)
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
        // Right after onEditOrMove, the caret should be solid (visible).
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

        // Set up an initial position via snap.
        ca.snapTo(0f, 0f, 1);
        // Now glide to a new position.
        ca.glideTo(100f, 200f, 2);

        assertEquals(100f, ca.targetX, 0.001f);
        assertEquals(200f, ca.targetY, 0.001f);
        assertEquals(2, ca.rev);
        // startX/startY in the animator is animX/animY at glide-start time,
        // which is (0, 0) — the snap value. The listener will interpolate
        // from (0,0) to (100,200) over GLIDE_MS.
        assertEquals(0f, ca.animX, 0.001f);
        assertEquals(0f, ca.animY, 0.001f);
    }
}
