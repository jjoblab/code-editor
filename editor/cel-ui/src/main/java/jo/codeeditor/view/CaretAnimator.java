package jo.codeeditor.view;

import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

/**
 * Manages caret blink + glide animation for {@link EditorView}.
 *
 * <p>Extracted from EditorView (v3.33.6) to reduce its size. The caret has
 * two animation modes:</p>
 * <ul>
 *   <li><b>Blink</b> — toggles visibility every {@link #BLINK_MS} ms when
 *       idle. Goes solid on every edit / caret move and resumes blinking
 *       after {@link #SOLID_AFTER_EDIT_MS} ms of inactivity.</li>
 *   <li><b>Glide</b> — smooth interpolation between two screen positions
 *       when the caret moves within the viewport (100ms, no overshoot).
 *       Snaps (no glide) for out-of-viewport jumps / edits / wrap moves.</li>
 * </ul>
 *
 * <p><b>Single source of truth</b> (v3.33.10 fix): all caret state lives
 * here. {@link EditorView} and {@link EditorRenderer} MUST read/write through
 * the methods on this class — they MUST NOT keep duplicate alias fields. The
 * previous design (alias fields on EditorView + state on CaretAnimator)
 * caused the snap branch in {@code drawCaret} to overwrite the just-updated
 * alias with a stale {@code animX} value from a cancelled glide, producing
 * a one-frame visual lag on every keystroke.</p>
 *
 * <p>The class is not thread-safe — all access must be on the UI thread.</p>
 *
 * @author jo@Dev
 * @since v3.33.6
 */
final class CaretAnimator {

    static final long BLINK_MS = 530;
    static final long SOLID_AFTER_EDIT_MS = 530;
    private static final long GLIDE_MS = 100;

    // ── Blink state ───────────────────────────────────────────────
    /** True while the caret should be drawn (solid or blink-on phase). */
    boolean visible = true;
    /** Last blink toggle timestamp (set to lastEditTime on edit/move). */
    long lastToggle = 0;

    // ── Glide state ───────────────────────────────────────────────
    /** Current animated position — what the renderer should draw. */
    float animX = 0f;
    float animY = 0f;
    /** Target position the glide is interpolating toward. */
    float targetX = 0f;
    float targetY = 0f;
    /** False until the first snap places the caret — first draw must snap. */
    boolean ready = false;
    /**
     * Document revision captured at the last snap/glide. If the next draw
     * sees a different revision, the document was edited — snap (no glide).
     */
    int rev = 0;

    private ValueAnimator animator;
    private final EditorView view;

    CaretAnimator(EditorView view) {
        this.view = view;
    }

    /**
     * Called on every edit / caret move. Makes the caret solid (visible),
     * cancels any in-flight glide, and resets the blink toggle timer so
     * blinking resumes {@link #SOLID_AFTER_EDIT_MS} ms after the last edit.
     *
     * <p>This is the SINGLE entry point for "user touched the editor".
     * EditorView.onTextChanged, EditorImeBridge, EditorInputHandler all
     * delegate here — they MUST NOT mutate the blink state directly.</p>
     */
    void onEditOrMove() {
        long now = System.currentTimeMillis();
        view.lastEditTime = now;
        visible = true;
        lastToggle = now;
        cancelGlide();
    }

    /**
     * Snaps the caret directly to {@code (targetX, targetY)} — no glide.
     * Used for: first placement, document edits, out-of-viewport jumps,
     * word-wrap moves. Updates ALL state (animX/Y, targetX/Y, ready, rev)
     * so subsequent frames see a consistent snapshot.
     *
     * @param targetX the new caret X (screen coords)
     * @param targetY the new caret Y (screen coords)
     * @param docRev  the current document revision
     */
    void snapTo(float targetX, float targetY, int docRev) {
        cancelGlide();
        animX = targetX;
        animY = targetY;
        this.targetX = targetX;
        this.targetY = targetY;
        ready = true;
        rev = docRev;
    }

    /**
     * Starts a glide from the current animated position to
     * {@code (targetX, targetY)}. Uses a linear interpolator (NO overshoot)
     * with a short 100ms duration — fast and smooth, like IntelliJ.
     *
     * <p>Updates {@code targetX/Y} and {@code rev} so the next frame's
     * snap-vs-glide decision sees consistent state.</p>
     *
     * @param targetX the new caret X (screen coords)
     * @param targetY the new caret Y (screen coords)
     * @param docRev  the current document revision
     */
    void glideTo(float targetX, float targetY, int docRev) {
        cancelGlide();
        final float startX = animX;
        final float startY = animY;
        this.targetX = targetX;
        this.targetY = targetY;
        this.rev = docRev;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(GLIDE_MS);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            float t = (Float) animation.getAnimatedValue();
            animX = startX + (targetX - startX) * t;
            animY = startY + (targetY - startY) * t;
            view.postInvalidateOnAnimation();
        });
        animator.start();
        view.postInvalidateOnAnimation();
    }

    /** Cancels any in-flight glide. Does NOT touch animX/Y/target/rev. */
    void cancelGlide() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        animator = null;
    }

    /**
     * Called from the draw path. Toggles blink if enough time has elapsed.
     *
     * @return true if the caret should be drawn (visible), false if not.
     */
    boolean updateBlink() {
        long now = System.currentTimeMillis();
        long lastEdit = view.lastEditTime;
        // Solid after edit — don't blink for SOLID_AFTER_EDIT_MS ms.
        if (now - lastEdit < SOLID_AFTER_EDIT_MS) {
            visible = true;
            return true;
        }
        // Blink period.
        if (now - lastToggle >= BLINK_MS) {
            visible = !visible;
            lastToggle = now;
        }
        return visible;
    }

    /** Resets ALL state — call when switching sessions. */
    void reset() {
        ready = false;
        rev = 0;
        animX = 0f;
        animY = 0f;
        targetX = 0f;
        targetY = 0f;
        visible = true;
        lastToggle = 0;
        cancelGlide();
    }
}
