package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.widget.OverScroller;

/**
 * Mécanique de défilement tactile d'EditorView, extraite d'EditorInputHandler :
 * possède l'{@link OverScroller} (scroll + fling inertiels) et le suivi de
 * vitesse du doigt ({@link VelocityTracker}). EditorInputHandler alimente le
 * tracker à chaque événement tactile et délègue ici {@code computeScroll} ;
 * les offsets {@code vOffset}/{@code hOffset} restent dans EditorView
 * (package-privés) et sont bornés par ce collaborateur.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorTouchScroller {

    private final EditorView view;
    private final OverScroller scroller;
    private VelocityTracker velocityTracker;
    private static final int FLING_VELOCITY_UNITS = 1000;

    EditorTouchScroller(EditorView view) {
        this.view = view;
        this.scroller = new OverScroller(view.getContext());
    }

    void computeScroll() {
        if (scroller.computeScrollOffset()) {
            int ny = scroller.getCurrY();
            int nx = scroller.getCurrX();
            if (ny != (int) view.vOffset || nx != (int) view.hOffset) {
                view.vOffset = EditorView.clamp(ny, 0, view.maxV());
                view.hOffset = EditorView.clamp(nx, 0, view.maxH());
                // ★ Notifie le sticky-bottom des consoles.
                view.notifyScrollPositionChanged();
                view.postInvalidateOnAnimation();
            } else {
                view.postInvalidateOnAnimation();
            }
        }
    }

    private void startFling(float vy, float vx) {
        int maxVInt = (int) view.maxV();
        int maxHInt = (int) view.maxH();
        scroller.fling(
            (int) view.hOffset, (int) view.vOffset,
            (int) vx, (int) vy,
            0, maxHInt,
            0, maxVInt,
            view.getWidth() / 4, view.getHeight() / 4
        );
        view.postInvalidateOnAnimation();
    }

    void scrollByInternal(float dy, float dx) {
        view.vOffset = EditorView.clamp(view.vOffset + dy, 0, view.maxV());
        view.hOffset = EditorView.clamp(view.hOffset + dx, 0, view.maxH());
        // ★ Notifie le sticky-bottom des consoles.
        view.notifyScrollPositionChanged();
        view.invalidate();
    }

    /** Alimente le suivi de vitesse avec un événement tactile (obtention paresseuse). */
    void trackMovement(MotionEvent event) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
    }

    /** Recycle le tracker de vitesse en fin de geste (puis le remet à null). */
    void recycleTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    /**
     * Déclenche le fling depuis la vitesse accumulée du tracker — corps
     * déplacé tel quel de la branche ACTION_UP d'EditorInputHandler.
     */
    void flingFromTracker() {
        velocityTracker.computeCurrentVelocity(FLING_VELOCITY_UNITS);
        float vy = velocityTracker.getYVelocity();
        float vx = velocityTracker.getXVelocity();
        if (Math.abs(vy) > 50 || Math.abs(vx) > 50) {
            startFling(-vy, -vx);
        }
    }

    /** Interrompt le fling en cours s'il y en a un (garde historique conservée). */
    void abortAnimation() {
        if (!scroller.isFinished()) scroller.abortAnimation();
    }
}
