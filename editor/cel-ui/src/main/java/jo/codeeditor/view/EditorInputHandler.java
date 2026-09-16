package jo.codeeditor.view;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.VelocityTracker;
import android.view.inputmethod.InputMethodManager;
import android.widget.OverScroller;

import jo.codeeditor.document.Selection;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * v3.9.0: Extracted from EditorView — handles all touch input, gesture
 * detection, tap/long-press/drag dispatch, hit-testing, and scroll/fling
 * mechanics.
 *
 * <p>EditorView delegates {@code onTouchEvent}, {@code computeScroll},
 * {@code performClick}, and {@code onGenericMotionEvent} to this class.
 * The touch state fields (isDragging, isScrolling, lastTouchX/Y, tapCount,
 * etc.) live here; all other editor state (session, metrics, theme, popup
 * flags) stays in EditorView and is accessed via the {@code view} reference.
 *
 * <p>The semantic actions triggered by touch (handleTap → caret/popup,
 * handleLongPress → word select, drag → selection) also live here but call
 * back into EditorView for popup management (showCompletion, showDiagnosticPopup,
 * etc.) and session manipulation (setSelection, selectWordAt).
 *
 * @since v3.9.0
 */
class EditorInputHandler {

    private final EditorView view;

    // ── Detectors + scroller ───────────────────────────────────────
    private final OverScroller scroller;
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private VelocityTracker velocityTracker;
    private static final int FLING_VELOCITY_UNITS = 1000;

    // ── Touch state ────────────────────────────────────────────────
    private boolean isDragging = false;
    private boolean isScrolling = false;
    private float lastTouchX = 0;
    private float lastTouchY = 0;
    private float touchStartX = 0;
    private float touchStartY = 0;
    private static final float TAP_SLOP_SQ = 24f * 24f;
    // v1.0.9 bugfix (Bug A): set true while a pinch is in progress so the
    // final ACTION_UP doesn't fall through to handleTap (which would move
    // the caret). Reset on the next fresh ACTION_DOWN (new gesture).
    private boolean wasPinching = false;
    // v3.3.5: True while the user is drag-scrolling the completion popup.
    private boolean completionScrolling = false;

    // ── Multi-tap state ────────────────────────────────────────────
    private long lastTapTime = 0;
    private float lastTapX = 0;
    private float lastTapY = 0;
    private int tapCount = 0;
    private static final long MULTI_TAP_TIMEOUT_MS = 280;
    private static final float MULTI_TAP_SLOP_PX = 48f;

    // ── Long-press flag ────────────────────────────────────────────
    private boolean longPressTriggered = false;

    // ── v2.31: modal gesture for the diagnostic sheet ─────────────
    // While the CodeAssist-style diagnostic sheet is up, its scrim consumes
    // the whole gesture (no scroll/drag behind the modal); the UP tap is
    // routed to handleTap which applies a quick-fix or dismisses.
    private boolean sheetGesture = false;

    // ── v2.34: selection toolbar gesture ───────────────────────────
    // While the floating selection toolbar is up, a DOWN inside its pill
    // swallows the gesture (CodeAssist's Popup does the same): press
    // feedback on the touched item, no scroll/caret behind the popup, and
    // the UP resolves via handleTap → handleSelectionToolbarTap.
    private boolean toolbarGesture = false;

    // ── v2.36: unified nav menu gesture ────────────────────────────
    // While the NavMenu (Actions ⋯) is up, a DOWN inside its card swallows
    // the gesture: press feedback on the touched row, DRAG scrolls the
    // content (CodeAssist verticalScroll/LazyColumn), and the UP resolves a
    // tap (row pick) — or nothing on headers/gaps. A DOWN OUTSIDE dismisses
    // (CodeAssist Popup onDismissRequest).
    private boolean navMenuGesture = false;
    private boolean navMenuDragging = false;
    /** v2.38 — le geste est englouti par le popup quick doc (drag = scroll du corps). */
    private boolean quickDocGesture = false;

    // ── v2.32: gutter tap vs gutter drag ───────────────────────────
    // ACTION_DOWN in the line-number area arms isScrolling (so a VERTICAL
    // DRAG on the gutter scrolls the document), but that same flag made a
    // TAP (no movement) skip handleTap entirely — the diagnostic dot could
    // never open the sheet ("dot pas câblé"). This flag remembers where the
    // gesture started so UP can distinguish a gutter TAP from a gutter drag.
    private boolean downInLineNumberArea = false;

    // ── v2.35: pendingTapDismiss (CodeAssist EditorInputModifier port) ──
    // A tap landed inside an existing selection: the offset to place the
    // caret at IF this turns out to be a LONE tap, decided once the
    // multi-tap window lapses. Between the tap and the decision the
    // selection stays alive (no flicker) so a following double-tap can
    // expand it (case 2 below) and the toolbar keeps showing. -1 = none.
    // CodeAssist relies on Compose's onTap firing after the system
    // double-tap timeout; here the same window is MULTI_TAP_TIMEOUT_MS and
    // the commit is a postDelayed on the main looper.
    private int pendingTapDismiss = -1;
    private android.os.Handler tapHandler;
    private final Runnable pendingTapDismissTask = new Runnable() {
        @Override
        public void run() {
            int target = pendingTapDismiss;
            pendingTapDismiss = -1;
            if (target < 0 || view.session == null) return;
            if (target > view.session.getDocument().length()) return;
            // Lone tap inside the selection → collapse it at the tapped
            // offset (CodeAssist: session.setCaret(target) + handlesVisible
            // = false, which hides the handles AND the pill).
            view.session.setSelection(target);
            view.handlesVisible = false;
            dismissSelectionToolbar();
            view.caretAnim.onEditOrMove();
            view.invalidate();
        }
    };

    // ── v2.39: tap-and-hold hover (distinct from long-press selection) ──
    // Sora's "alwaysShowOnTouchHover" parity: on touch devices, a LONGER
    // dwell (TAP_HOLD_HOVER_TIMEOUT_MS) without movement/lift shows the
    // quick doc popup at the touched offset WITHOUT triggering selection
    // (no handles, no toolbar). The classic 400ms long-press remains the
    // selection gesture — the two are mutually exclusive:
    //
    //   - touchHoverEnabled = false (default): only classic long-press
    //     (400ms → selection + handles + toolbar + quick doc, the legacy
    //     conflated behavior — what v2.31 implemented and what v2.38
    //     quick doc anchored to).
    //   - touchHoverEnabled = true: long-press is suppressed and replaced
    //     by a 500ms tap-and-hold → quick doc only. Selection still works
    //     via double-tap and drag-select.
    //
    // The flag is toggled via EditorView.setTouchHoverEnabled(boolean).
    private static final long TAP_HOLD_HOVER_TIMEOUT_MS = 500;
    private static final float TAP_HOLD_HOVER_SLOP_PX = 20f;
    private float tapHoldHoverStartX = 0f;
    private float tapHoldHoverStartY = 0f;
    private boolean tapHoldHoverTriggered = false;
    private final Runnable tapHoldHoverTask = new Runnable() {
        @Override
        public void run() {
            if (view.session == null) return;
            if (tapHoldHoverTriggered) return;
            // Re-check that the finger hasn't moved beyond slop since DOWN.
            float dx = lastTouchX - tapHoldHoverStartX;
            float dy = lastTouchY - tapHoldHoverStartY;
            if (dx * dx + dy * dy > TAP_HOLD_HOVER_SLOP_PX * TAP_HOLD_HOVER_SLOP_PX) {
                return;  // moved → this is a drag, not a hover
            }
            // Re-check that no scrolling/dragging started.
            if (isScrolling || isDragging) return;
            // Trigger the hover — show quick doc at the touched offset.
            int offset = view.offsetAt(tapHoldHoverStartX, tapHoldHoverStartY);
            if (offset >= 0 && view.quickDocResolver != null) {
                tapHoldHoverTriggered = true;
                view.showQuickDoc(offset);
            }
        }
    };

    void cancelTapHoldHover() {
        if (tapHandler != null) {
            tapHandler.removeCallbacks(tapHoldHoverTask);
        }
        tapHoldHoverTriggered = false;
    }

    /**
     * v3.34.0 — Cancels EVERY pending handler callback owned by this input
     * handler (tap-hold hover, multi-tap dismiss). Called from
     * {@code EditorView.onDetachedFromWindow} so a detached view stops
     * running touch-driven runnables — before this, each pending callback
     * kept the (detached) view strongly reachable until it fired and could
     * touch state the host had already torn down.
     */
    void cancelPendingCallbacks() {
        cancelTapHoldHover();
        cancelPendingTapDismiss();
    }

    private void scheduleTapHoldHover(float x, float y) {
        if (!view.touchHoverEnabled) return;
        if (view.quickDocResolver == null) return;
        tapHoldHoverStartX = x;
        tapHoldHoverStartY = y;
        tapHoldHoverTriggered = false;
        // v2.39: when touch hover is armed, suppress the classic long-press
        // (selection) so the two gestures don't fire together at 400ms vs 500ms.
        // The flag is restored on ACTION_UP/CANCEL/MOVE-beyond-slop.
        this.gestureDetector.setIsLongpressEnabled(false);
        if (tapHandler == null) {
            tapHandler = view.getHandler();
            if (tapHandler == null) {
                tapHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            }
        }
        tapHandler.postDelayed(tapHoldHoverTask, TAP_HOLD_HOVER_TIMEOUT_MS);
    }

    EditorInputHandler(EditorView view) {
        this.view = view;
        Context context = view.getContext();
        this.scroller = new OverScroller(context);
        this.scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        this.gestureDetector = new GestureDetector(context, new GestureListener());
        // v2.39: when touchHover is enabled, the long-press (selection)
        // gesture is mutually exclusive with the tap-and-hold (hover).
        // The gestureDetector's long-press is toggled dynamically in
        // scheduleTapHoldHover / cancelTapHoldHover based on the flag.
        this.gestureDetector.setIsLongpressEnabled(true);
    }

    // ════════════════════════════════════════════════════════════════
    // Touch event dispatch
    // ════════════════════════════════════════════════════════════════

    boolean onTouchEvent(MotionEvent event) {
        if (view.session == null) return false;

        // Route multi-finger events through the scale detector first.
        scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            wasPinching = true;
            isDragging = false;
            isScrolling = false;
            return true;
        }
        if (event.getPointerCount() > 1) {
            wasPinching = true;
            isDragging = false;
            isScrolling = false;
            return true;
        }

        // Route single-finger events through the gesture detector for
        // long-press detection.
        gestureDetector.onTouchEvent(event);

        // Velocity tracking for fling.
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        float x = event.getX();
        float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                touchStartX = x;
                touchStartY = y;
                isScrolling = false;
                isDragging = false;
                longPressTriggered = false;
                wasPinching = false;
                // v2.39: arm the tap-and-hold hover timer (no-op if the
                // feature is disabled or no quickDocResolver is set).
                cancelTapHoldHover();
                scheduleTapHoldHover(x, y);
                // v2.35: any new gesture resolves the pending deferred
                // tap-dismiss (CodeAssist cancels in onPress) — a double-tap
                // expands the selection instead of dismissing it.
                cancelPendingTapDismiss();
                // v2.31: the diagnostic sheet is MODAL — consume the gesture so
                // the editor neither scrolls nor places the caret under the
                // scrim; the UP event resolves via handleTap.
                if (view.diagnosticPopupVisible) {
                    sheetGesture = true;
                    if (!scroller.isFinished()) scroller.abortAnimation();
                    return true;
                }
                if (view.completionVisible && hitTestCompletionPopup(x, y) >= 0) {
                    completionScrolling = true;
                    return true;
                }
                // v2.34: the selection toolbar swallows its gesture (CodeAssist
                // Popup parity) — press feedback + action on release.
                if (view.selectionToolbarVisible) {
                    EditorView.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
                    if (m != null && m.contains(x, y)) {
                        toolbarGesture = true;
                        view.selectionToolbarPressedIdx = m.itemIndexAt(x, y);
                        view.invalidate();
                        return true;
                    }
                }
                // v2.36: the unified nav menu swallows its gesture too — press
                // feedback + drag-scroll + row pick on release.
                if (view.navMenuVisible) {
                    float[] nm = view.navMenuMetrics();
                    if (nm != null && x >= nm[0] && x <= nm[0] + nm[2]
                            && y >= nm[1] && y <= nm[1] + nm[3]) {
                        navMenuGesture = true;
                        navMenuDragging = false;
                        view.navMenuPressedIdx = navMenuRowIndexOf(x, y);
                        view.invalidate();
                        return true;
                    }
                }
                // v2.38 — quick doc : un DOWN DANS le popup l'engloutit
                // (drag = scroll du corps, parité NavMenu) ; un DOWN
                // HORS du popup le referme (parité Sora : tap ailleurs →
                // dismiss) sans engloutir le geste — le tap continue de
                // placer le caret derrière.
                if (view.quickDocVisible) {
                    float[] qd = view.quickDocMetrics();
                    if (qd != null && x >= qd[0] && x <= qd[0] + qd[2]
                            && y >= qd[1] && y <= qd[1] + qd[3]) {
                        quickDocGesture = true;
                        if (!scroller.isFinished()) scroller.abortAnimation();
                        return true;
                    }
                    view.dismissQuickDoc();
                }
                int handleHit = hitTestHandle(x, y);
                if (handleHit > 0) {
                    view.handleDragMode = handleHit;
                    isScrolling = false;
                    isDragging = false;
                    return true;
                }
                view.handleDragMode = 0;
                if (x < view.metrics.getGutterWidth()
                    && x < view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth()) {
                    isScrolling = true;
                    // v2.32: remember the gesture began in the line-number
                    // area — a NO-MOVEMENT release there is a TAP (diagnostic
                    // dot → sheet), not a scroll.
                    downInLineNumberArea = true;
                }
                if (!scroller.isFinished()) scroller.abortAnimation();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (sheetGesture) return true; // modal scrim — swallow
                // v2.38 — drag sur le quick doc : scroll du corps (clampé).
                if (quickDocGesture) {
                    float dy = y - lastTouchY;
                    if (dy != 0) {
                        float[] qd = view.quickDocMetrics();
                        if (qd != null && qd[4] > qd[3]) {
                            view.quickDocScrollY = Math.max(0,
                                    Math.min(view.quickDocScrollY - dy,
                                            qd[4] - qd[3]));
                            view.invalidate();
                        }
                    }
                    lastTouchY = y;
                    lastTouchX = x;
                    return true;
                }
                if (toolbarGesture) {
                    // Mouvement au-delà du slop → annule le press ; le geste
                    // reste englouti par la pill (pas de scroll derrière).
                    float tdx = x - touchStartX;
                    float tdy = y - touchStartY;
                    if (tdx * tdx + tdy * tdy > TAP_SLOP_SQ
                            && view.selectionToolbarPressedIdx >= 0) {
                        view.selectionToolbarPressedIdx = -1;
                        view.invalidate();
                    }
                    return true;
                }
                if (navMenuGesture) {
                    float dy = y - lastTouchY;
                    float tdy = y - touchStartY;
                    if (navMenuDragging || tdy * tdy
                            + (x - touchStartX) * (x - touchStartX) > TAP_SLOP_SQ) {
                        navMenuDragging = true;
                        view.navMenuPressedIdx = -1;
                        float contentH = view.navMenuContentHeight();
                        float[] nm = view.navMenuMetrics();
                        float maxScroll = nm != null
                                ? Math.max(0, contentH - nm[3]) : 0;
                        view.navMenuScrollY = EditorView.clamp(
                                view.navMenuScrollY - dy, 0, maxScroll);
                        view.invalidate();
                        lastTouchY = y;
                    }
                    return true;
                }
                if (completionScrolling) {
                    float dy = y - lastTouchY;
                    float density = view.getResources().getDisplayMetrics().density;
                    float rowH = view.COMPLETION_ROW_HEIGHT_DP * density;
                    int rowDelta = -(int) (dy / rowH);
                    if (rowDelta != 0) {
                        // v2.38 : bornes de scroll sur les rangées RÉELLEMENT
                        // visibles (réduites quand le viewport est petit).
                        int rowsVisible = view.popupManager.completionRowsVisible();
                        int newOffset = view.completionScrollOffset + rowDelta;
                        int maxOffset = Math.max(0, view.completionItems.size() - rowsVisible);
                        newOffset = Math.max(0, Math.min(newOffset, maxOffset));
                        if (newOffset != view.completionScrollOffset) {
                            view.completionScrollOffset = newOffset;
                            view.completionSelected = Math.max(0,
                                Math.min(view.completionSelected + rowDelta, view.completionItems.size() - 1));
                            view.invalidate();
                        }
                        lastTouchY = y;
                    }
                    return true;
                }
                if (view.handleDragMode > 0) {
                    dragHandle(x, y);
                    return true;
                }
                if (isScrolling) {
                    float dy = y - lastTouchY;
                    float dx = x - lastTouchX;
                    scrollByInternal(-dy, -dx);
                    lastTouchX = x;
                    lastTouchY = y;
                } else {
                    float totalDx = x - touchStartX;
                    float totalDy = y - touchStartY;
                    if (totalDx * totalDx + totalDy * totalDy > TAP_SLOP_SQ) {
                        isScrolling = true;
                        isDragging = false;
                        // v2.35: a swipe that traveled past touch-slop is a
                        // scroll, never a tap — the pending deferred
                        // tap-dismiss dies with it (CodeAssist: !touchScrolled).
                        cancelPendingTapDismiss();
                        // v2.39: also cancel the tap-and-hold hover — the
                        // user has started scrolling, not hovering.
                        cancelTapHoldHover();
                    } else if (isDragging) {
                        handleTouchDrag(event);
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                }
                return true;
            case MotionEvent.ACTION_UP:
                // v3.18.0: Deactivate magnifier on touch release.
                view.magnifierActive = false;
                // v2.39: cancel any pending tap-and-hold hover timer.
                // If it already fired (tapHoldHoverTriggered = true), the
                // quick doc is already up — let the user dismiss it with
                // a separate tap elsewhere.
                cancelTapHoldHover();
                view.invalidate();
                if (sheetGesture) {
                    // v2.31: modal diagnostic sheet — resolve the tap (apply a
                    // quick-fix, hit ×, or dismiss on the scrim).
                    sheetGesture = false;
                    boolean wasTap = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    if (wasTap) handleTap(x, y);
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    view.performClick();
                    return true;
                }
                if (completionScrolling) {
                    boolean wasTap = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    completionScrolling = false;
                    if (wasTap) {
                        handleTap(x, y);
                    }
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    view.performClick();
                    return true;
                }
                if (toolbarGesture) {
                    // v2.34: résolution du geste toolbar — tap → action.
                    toolbarGesture = false;
                    boolean wasTap = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    view.selectionToolbarPressedIdx = -1;
                    // Parité Popup CodeAssist : la pill engloutit SON geste —
                    // un tap sur un gap/divider ne fait rien (le Popup ne
                    // propage pas), le caret ne bouge pas et le clavier ne
                    // se lève pas. On appelle donc le resolver DIRECTEMENT
                    // au lieu de re-entrer dans la cascade handleTap.
                    if (wasTap) handleSelectionToolbarTap(x, y);
                    view.invalidate();
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    view.performClick();
                    return true;
                }
                if (navMenuGesture) {
                    // v2.36 : résolution du geste menu — tap → pick de la
                    // rangée ; drag → fin du scroll (rien d'autre). Le geste
                    // est englouti par la carte (Popup CodeAssist).
                    navMenuGesture = false;
                    boolean wasNavTap = !navMenuDragging
                            && ((x == touchStartX && y == touchStartY)
                                || ((x - touchStartX) * (x - touchStartX)
                                    + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ));
                    int pressed = view.navMenuPressedIdx;
                    view.navMenuPressedIdx = -1;
                    if (wasNavTap) handleNavMenuTap(x, y, pressed);
                    view.invalidate();
                    navMenuDragging = false;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    view.performClick();
                    return true;
                }
                if (quickDocGesture) {
                    // v2.38 : fin du geste quick doc — le tap simple ne fait
                    // rien (le popup reste ; drag = scroll du corps), le
                    // geste est englouti par la carte.
                    quickDocGesture = false;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    view.performClick();
                    return true;
                }
                if (view.handleDragMode > 0) {
                    view.handleDragMode = 0;
                    isScrolling = false;
                    isDragging = false;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    view.performClick();
                    return true;
                }
                if (wasPinching) {
                    wasPinching = false;
                    isScrolling = false;
                    isDragging = false;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    view.performClick();
                    return true;
                }
                if (!isScrolling && !longPressTriggered) {
                    handleTap(x, y);
                } else if (isScrolling) {
                    // v2.32: a no-movement release in the line-number gutter
                    // is a TAP, not a scroll — route it to handleTap so the
                    // diagnostic dot opens the sheet (CodeAssist parity:
                    // gutter glyph tap → openSheet). A real drag (movement
                    // beyond the slop) still scrolls/flings as before.
                    boolean tapLike = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    if (downInLineNumberArea && tapLike && !longPressTriggered) {
                        handleTap(x, y);
                    } else {
                        velocityTracker.computeCurrentVelocity(FLING_VELOCITY_UNITS);
                        float vy = velocityTracker.getYVelocity();
                        float vx = velocityTracker.getXVelocity();
                        if (Math.abs(vy) > 50 || Math.abs(vx) > 50) {
                            startFling(-vy, -vx);
                        }
                    }
                }
                isScrolling = false;
                isDragging = false;
                downInLineNumberArea = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                view.performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                view.handleDragMode = 0;
                isScrolling = false;
                isDragging = false;
                downInLineNumberArea = false; // v2.32
                sheetGesture = false; // v2.31: cancel the modal gesture too
                toolbarGesture = false; // v2.34
                view.selectionToolbarPressedIdx = -1; // v2.34
                navMenuGesture = false; // v2.36
                navMenuDragging = false; // v2.36
                view.navMenuPressedIdx = -1; // v2.36
                quickDocGesture = false; // v2.38
                cancelPendingTapDismiss(); // v2.35
                cancelTapHoldHover(); // v2.39
                // v3.18.0: Deactivate magnifier.
                view.magnifierActive = false;
                view.invalidate();
                if (!scroller.isFinished()) scroller.abortAnimation();
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
                return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // Scroll / fling
    // ════════════════════════════════════════════════════════════════

    void computeScroll() {
        if (scroller.computeScrollOffset()) {
            int ny = scroller.getCurrY();
            int nx = scroller.getCurrX();
            if (ny != (int) view.vOffset || nx != (int) view.hOffset) {
                view.vOffset = EditorView.clamp(ny, 0, view.maxV());
                view.hOffset = EditorView.clamp(nx, 0, view.maxH());
                // ★ v0.1.0.49-v2.20 — Notifie le sticky-bottom des consoles.
                view.notifyScrollPositionChanged();
                view.postInvalidateOnAnimation();
            } else {
                view.postInvalidateOnAnimation();
            }
        }
    }

    boolean performClick() {
        view.performClick();
        return true;
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

    private void scrollByInternal(float dy, float dx) {
        view.vOffset = EditorView.clamp(view.vOffset + dy, 0, view.maxV());
        view.hOffset = EditorView.clamp(view.hOffset + dx, 0, view.maxH());
        // ★ v0.1.0.49-v2.20 — Notifie le sticky-bottom des consoles.
        view.notifyScrollPositionChanged();
        view.invalidate();
    }

    // ════════════════════════════════════════════════════════════════
    // Generic motion (right-click → context menu)
    // ════════════════════════════════════════════════════════════════

    boolean onGenericMotionEvent(MotionEvent event) {
        if (view.session == null) return false;
        if (event.getAction() == MotionEvent.ACTION_BUTTON_PRESS
            && event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
            int offset = view.offsetAt(event.getX(), event.getY());
            view.session.setSelection(offset);
            showEditorContextMenu(event.getX(), event.getY());
            return true;
        }
        return false;
    }

    void showEditorContextMenu(float anchorX, float anchorY) {
        if (view.session == null) return;
        android.widget.PopupMenu popup = new android.widget.PopupMenu(view.getContext(), view);
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == android.R.id.copy) { view.copy(); return true; }
            if (id == android.R.id.cut) { view.cut(); return true; }
            if (id == android.R.id.paste) { view.paste(); return true; }
            if (id == android.R.id.selectAll) { view.session.selectAll(); view.invalidate(); return true; }
            return false;
        });
        popup.getMenu().add(0, android.R.id.copy, 0, "Copy");
        popup.getMenu().add(0, android.R.id.cut, 0, "Cut");
        popup.getMenu().add(0, android.R.id.paste, 0, "Paste");
        popup.getMenu().add(0, android.R.id.selectAll, 0, "Select all");
        popup.getMenu().add(0, 1001, 0, "Undo").setOnMenuItemClickListener(i -> {
            view.session.undo(); view.notifyTextChanged(); return true;
        });
        popup.getMenu().add(0, 1002, 0, "Redo").setOnMenuItemClickListener(i -> {
            view.session.redo(); view.notifyTextChanged(); return true;
        });
        popup.show();
    }

    // ════════════════════════════════════════════════════════════════
    // Tap / long-press / drag handlers
    // ════════════════════════════════════════════════════════════════

    private void handleTap(float x, float y) {
        long now = System.currentTimeMillis();
        // v3.34.0: announce the tap for accessibility — EditorView's
        // performClick() override (v1.0.9) was never invoked from the touch
        // path, so TalkBack users got no click feedback and lint flagged
        // ClickableViewAccessibility on every custom onTouchEvent.
        view.performClick();
        // v2.35: any new tap resolves the pending deferred tap-dismiss — the
        // tap's own cascade takes over (move the caret, expand, toggle…).
        cancelPendingTapDismiss();
        boolean withinTimeout = (now - lastTapTime) < MULTI_TAP_TIMEOUT_MS;
        boolean withinSlop = (x - lastTapX) * (x - lastTapX)
            + (y - lastTapY) * (y - lastTapY) < MULTI_TAP_SLOP_PX * MULTI_TAP_SLOP_PX;
        if (withinTimeout && withinSlop) {
            tapCount++;
        } else {
            tapCount = 1;
        }
        lastTapTime = now;
        lastTapX = x;
        lastTapY = y;

        // v3.17.0: Preview icon tap — check before everything else.
        // v2.39: dispatch through view.openPreview() so .md/.html files
        // open the popup sheet overlay (SHEET_SPLIT/SHEET_FULL) while
        // .xml layouts keep the legacy inline SPLIT/FULL behavior.
        if (tapCount == 1) {
            int iconHit = view.hitTestPreviewIcons(x, y);
            if (iconHit == 1) {
                view.openPreview(false);
                return;
            } else if (iconHit == 2) {
                view.openPreview(true);
                return;
            }
        }
        // v3.18.0: Toolbar icons tap (A+, A-, ¶, lig).
        if (tapCount == 1) {
            int tbHit = view.hitTestToolbarIcons(x, y);
            if (tbHit == 1) { view.increaseFontSize(); return; }
            if (tbHit == 2) { view.decreaseFontSize(); return; }
            if (tbHit == 3) { view.setShowNonPrintable(!view.showNonPrintable); return; }
            if (tbHit == 4) { view.setFontLigatures(!view.fontLigatures); return; }
        }

        // Completion popup hit-test.
        if (view.completionVisible) {
            int hitRow = hitTestCompletionPopup(x, y);
            if (hitRow >= 0) {
                view.completionSelected = hitRow;
                view.invalidate();
                view.completionAccept();
                return;
            }
            view.dismissCompletion();
        }

        // Diagnostic popup hit-test (v2.31: CodeAssist-style modal sheet).
        if (view.diagnosticPopupVisible) {
            if (hitTestDiagnosticSheetClose(x, y)) {
                view.dismissDiagnosticPopup();
                return;
            }
            int hitAction = hitTestDiagnosticPopup(x, y);
            if (hitAction >= 0) {
                if (view.diagnosticPopupItem != null && view.session != null) {
                    int line = view.session.getDocument().lineForOffset(view.diagnosticPopupItem.start);
                    List<EditorView.CodeAction> actions = view.codeActionsByLine.get(line);
                    if (actions != null && hitAction < actions.size()) {
                        EditorView.CodeAction a = actions.get(hitAction);
                        if (a.apply != null) {
                            a.apply.run();
                            view.onTextChanged();
                        }
                    }
                }
                view.dismissDiagnosticPopup();
                return;
            }
            // Any other tap (scrim included) dismisses the modal sheet.
            view.dismissDiagnosticPopup();
            return;
        }

        // Selection toolbar hit-test.
        if (view.selectionToolbarVisible && handleSelectionToolbarTap(x, y)) {
            return;
        }

        // v2.36: nav menu hit-test — a tap on a row picks it; a tap anywhere
        // else dismisses (CodeAssist Popup onDismissRequest).
        if (view.navMenuVisible) {
            int navRow = navMenuRowIndexOf(x, y);
            if (navRow >= 0) {
                handleNavMenuTap(x, y, navRow);
            } else {
                view.dismissNavMenu();
            }
            return;
        }

        // Code actions popup hit-test.
        if (view.codeActionsPopupVisible) {
            int hitRow = hitTestCodeActionsPopup(x, y);
            if (hitRow >= 0) {
                view.codeActionsSelected = hitRow;
                view.invalidate();
                view.applySelectedCodeAction();
                return;
            }
        }

        // Go-to-symbol popup hit-test.
        if (view.goToSymbolVisible) {
            int hitRow = hitTestGoToSymbolPopup(x, y);
            if (hitRow >= 0) {
                view.goToSymbolSelected = hitRow;
                view.goToSymbolAccept();
                return;
            }
        }

        // Fold strip tap.
        if (x < view.metrics.getGutterWidth()
            && x >= view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth()) {
            int line = view.docLineForScreenY(y);
            if (view.codeActionsByLine.containsKey(line) && view.lineHasDiagnostic(line)) {
                view.showCodeActions(line);
                return;
            }
            if (view.session.toggleFoldAtLine(line)) {
                view.invalidate();
                return;
            }
            DiagnosticShift.Diagnostic lineDiag = view.findDiagnosticAtLine(line);
            if (lineDiag != null) {
                view.showDiagnosticPopup(lineDiag, lineDiag.start);
                return;
            }
            return;
        }

        // Gutter diagnostic dot tap.
        if (x < view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth()) {
            int line = view.docLineForScreenY(y);
            DiagnosticShift.Diagnostic lineDiag = view.findDiagnosticAtLine(line);
            if (lineDiag != null) {
                view.showDiagnosticPopup(lineDiag, lineDiag.start);
                return;
            }
        }

        int offset = view.offsetAt(x, y);

        // v3.14.0 Bug 11: If the tap landed on a fold-start line's placeholder
        // chip ({...}), expand the fold instead of placing the caret. Detect
        // this by checking if the line is a fold-start line and the tap X is
        // within the placeholder chip's horizontal range.
        if (x >= view.metrics.getGutterWidth() && tapCount == 1) {
            int tappedLine = view.session.getDocument().lineForOffset(offset);
            DiagnosticShift.FoldRegion fold = view.collapsedFoldStartingAtLine(tappedLine);
            if (fold != null) {
                // Compute the placeholder chip's X range.
                float charWidth = view.metrics.getCharWidth();
                float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
                int startLine = view.session.getDocument().lineForOffset(fold.start);
                int prefixEndCol = fold.start - view.session.getDocument().lineStart(startLine);
                float prefixW = prefixEndCol * charWidth;
                float placeW = fold.placeholder.length() * charWidth;
                float chipX1 = textAreaLeft - view.hOffset + prefixW;
                float chipX2 = chipX1 + placeW;
                if (x >= chipX1 && x <= chipX2) {
                    // Tap on the {...} chip — expand the fold.
                    view.session.toggleFoldAtLine(tappedLine);
                    view.invalidate();
                    return;
                }
            }
        }

        // v2.32: the diagnostic SHEET now opens ONLY from (1) the diagnostic
        // chip after the line end and (2) the gutter diagnostic dot — exactly
        // CodeAssist's two entry points (DiagnosticChip.onClick → openSheet,
        // gutter glyph tap → openSheet). The squiggle itself is NOT tappable:
        // a tap on the wavy range just places the caret (CodeAssist's else
        // branch → session.setCaret), so the user can still position the
        // cursor before/after the diagnostic range. The v3.4.0/v3.14.0
        // squiggle-hit code was removed for that parity.
        if (x >= view.metrics.getGutterWidth() && tapCount == 1) {
            DiagnosticShift.Diagnostic chipDiag = view.findDiagnosticChipAt(x, y);
            if (chipDiag != null) {
                view.showDiagnosticPopup(chipDiag, chipDiag.start);
                return;
            }
        }

        // Arm keyboard on tap in text area.
        if (x >= view.metrics.getGutterWidth()) {
            view.wantsKeyboard = true;
            view.requestFocus();
            InputMethodManager imm = view.imm();
            if (imm != null) imm.showSoftInput(view, 0);
        }

        switch (tapCount) {
            case 1:
                Selection prev = view.session.getSelection();
                if (!prev.isCursor() && offset >= prev.start && offset <= prev.end) {
                    // Tap DANS la sélection (v2.35 — pendingTapDismiss, port
                    // CodeAssist onPress/onTap) : on la garde vivante
                    // (anti-flicker), poignées + pill ré-armées, et la décision
                    // est DIFFÉRÉE de la fenêtre multi-tap : un double-tap qui
                    // suit l'étend (case 2), un tap seul la referme au caret
                    // tapé une fois la fenêtre écoulée. C'est aussi ce qui
                    // permet de refermer une sélection couvrant tout le
                    // fichier — là, chaque tap est « dedans ».
                    view.handlesVisible = true;
                    showSelectionToolbar();
                    armPendingTapDismiss(offset);
                } else {
                    // v2.34 — re-tap CodeAssist : un second tap au MÊME
                    // endroit que le caret collapsed BASCULE la pill
                    // Paste/Select all (le re-tap sur le caret
                    // « interaction.handlesVisible = reTap && !handlesVisible »).
                    // Un tap ailleurs la referme.
                    boolean reTap = prev.isCursor() && prev.start == offset;
                    view.session.setSelection(offset);
                    if (reTap && !view.selectionToolbarVisible) {
                        view.handlesVisible = true;
                        showSelectionToolbar();
                    } else {
                        view.handlesVisible = false;
                        dismissSelectionToolbar();
                    }
                    isDragging = true; // arme le drag-select (UX CodeIDE)
                }
                break;
            case 2:
                view.session.selectWordAt(offset);
                view.handlesVisible = true;
                showSelectionToolbar();
                break;
            default:
                view.session.selectLineAt(offset);
                view.handlesVisible = true;
                showSelectionToolbar();
                break;
        }

        // Reset caret blink on tap.
        // v3.33.10: CaretAnimator.onEditOrMove() is the single entry point
        // for caret-state updates — sets lastEditTime, makes caret visible,
        // resets blink toggle, cancels any in-flight glide.
        view.caretAnim.onEditOrMove();

        view.invalidate();
    }

    private void handleLongPress(float x, float y) {
        longPressTriggered = true;
        isDragging = false;
        isScrolling = false;
        int offset = view.offsetAt(x, y);
        view.session.selectWordAt(offset);
        view.handlesVisible = true;
        showSelectionToolbar();
        if (view.quickDocResolver != null) {
            view.showQuickDoc(offset);
        }
        if (x >= view.metrics.getGutterWidth()) {
            view.wantsKeyboard = true;
            view.requestFocus();
            InputMethodManager imm = view.imm();
            if (imm != null) imm.showSoftInput(view, 0);
        }
        view.invalidate();
    }

    private void handleTouchDrag(MotionEvent event) {
        int offset = view.offsetAt(event.getX(), event.getY());
        int selStart = Math.min(view.session.getSelection().start, view.session.getSelection().end);
        view.session.setSelection(Selection.range(Math.min(selStart, offset), Math.max(selStart, offset)));
        view.invalidate();
    }

    // ════════════════════════════════════════════════════════════════
    // Handle drag
    // ════════════════════════════════════════════════════════════════

    private void dragHandle(float x, float y) {
        // v2.35: magnifier follows the finger while a selection handle is
        // being dragged (the dormant v3.18.0 drawMagnifier pipeline re-armed).
        // Activating on the first MOVE — not DOWN — so a quick handle TAP
        // never flashes the bubble; UP/CANCEL already deactivate it. This is
        // the precision-placement use case the v3.18.0 code was written for;
        // "interferes with selection" only ever applied to scroll/drag-select,
        // which never reach this path (handle gestures are consumed at DOWN).
        view.magnifierActive = true;
        view.magnifierX = x;
        view.magnifierY = y;
        int offset = view.offsetAt(x, y);
        Selection sel = view.session.getSelection();
        switch (view.handleDragMode) {
            case 1:
                view.session.setSelection(Selection.range(
                    Math.min(sel.end, offset), Math.max(sel.end, offset)));
                break;
            case 2:
                view.session.setSelection(Selection.range(
                    Math.min(sel.start, offset), Math.max(sel.start, offset)));
                break;
            case 3:
                view.session.setSelection(offset);
                break;
        }
        view.invalidate();
    }

    // ════════════════════════════════════════════════════════════════
    // Hit-test methods
    // ════════════════════════════════════════════════════════════════

    private int hitTestHandle(float x, float y) {
        if (!view.handlesVisible) return 0;
        Selection sel = view.session.getSelection();
        float density = view.getResources().getDisplayMetrics().density;
        float tapR = view.HANDLE_TAP_RADIUS_DP * density;
        if (sel.isCursor()) {
            float[] pos = view.caretScreenPos(sel.start);
            float cx = pos[0];
            float cy = pos[1] + view.metrics.getLineHeight() + view.HANDLE_RADIUS_DP * density * 0.6f;
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < tapR * tapR) return 3;
        } else {
            float[] posA = view.caretScreenPos(sel.start);
            float cax = posA[0];
            float cay = posA[1] + view.metrics.getLineHeight() + view.HANDLE_RADIUS_DP * density * 0.6f;
            if ((x - cax) * (x - cax) + (y - cay) * (y - cay) < tapR * tapR) return 1;
            float[] posB = view.caretScreenPos(sel.end);
            float cbx = posB[0];
            float cby = posB[1] + view.metrics.getLineHeight() + view.HANDLE_RADIUS_DP * density * 0.6f;
            if ((x - cbx) * (x - cbx) + (y - cby) * (y - cby) < tapR * tapR) return 2;
        }
        return 0;
    }

    int hitTestCompletionPopup(float x, float y) {
        if (!view.completionVisible || view.completionItems.isEmpty()) return -1;
        float[] anchor = view.completionPopupAnchor();
        float anchorX = anchor[0], anchorY = anchor[1];
        float width = anchor[2], popupH = anchor[3], rowH = anchor[4];
        if (x < anchorX || x > anchorX + width || y < anchorY || y > anchorY + popupH) {
            return -1;
        }
        int rowInPopup = (int) ((y - anchorY) / rowH);
        // v2.38 : l'ancre réduit les rangées quand le viewport est petit —
        // le hit-test suit la hauteur réelle du popup.
        int rowsToShow = Math.max(1, Math.round(popupH / rowH));
        if (rowInPopup < 0 || rowInPopup >= rowsToShow) return -1;
        return view.completionScrollOffset + rowInPopup;
    }

    /**
     * v2.36 — l'index de la rangée du menu contextuel unifié à (x, y), ou -1.
     * Seules les rangées ACTIONNABLES (option / action / target) comptent —
     * les headers, « Nothing found » et les positions hors carte rendent -1.
     * Prend en compte le scroll du contenu (navMenuScrollY).
     */
    int navMenuRowIndexOf(float x, float y) {
        if (!view.navMenuVisible) return -1;
        float[] m = view.navMenuMetrics();
        if (m == null) return -1;
        if (x < m[0] || x > m[0] + m[2] || y < m[1] || y > m[1] + m[3]) return -1;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.NAV_MENU_ROW_HEIGHT_DP * density;
        float headerH = view.NAV_MENU_HEADER_HEIGHT_DP * density;
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        float rowTop = m[1] - view.navMenuScrollY;
        for (int i = 0; i < rows.size(); i++) {
            EditorView.NavMenuRow row = rows.get(i);
            float rh = row.type == EditorView.NavMenuRow.TYPE_HEADER
                    ? headerH : rowH;
            if (y >= rowTop && y < rowTop + rh) {
                boolean actionable = row.type == EditorView.NavMenuRow.TYPE_OPTION
                        || row.type == EditorView.NavMenuRow.TYPE_ACTION
                        || row.type == EditorView.NavMenuRow.TYPE_TARGET;
                return actionable ? i : -1;
            }
            rowTop += rh;
        }
        return -1;
    }

    /**
     * v2.36 — résout un tap sur la rangée {@code rowIdx} du menu contextuel
     * unifié (parité NavMenu onOption/onAction/onPick). Les press feedback et
     * dismiss sont gérés par les appelants.
     */
    void handleNavMenuTap(float x, float y, int rowIdx) {
        if (!view.navMenuVisible || rowIdx < 0) return;
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        if (rowIdx >= rows.size()) return;
        EditorView.NavMenuRow row = rows.get(rowIdx);
        switch (row.type) {
            case EditorView.NavMenuRow.TYPE_OPTION:
                if (row.ref instanceof NavigationMenu.NavOption) {
                    view.popupManager.navMenuPickOption((NavigationMenu.NavOption) row.ref);
                }
                break;
            case EditorView.NavMenuRow.TYPE_ACTION: {
                EditorView.CodeAction action = view.navMenuActionAt(row);
                if (action != null) view.popupManager.navMenuPickAction(action);
                break;
            }
            case EditorView.NavMenuRow.TYPE_TARGET:
                if (row.ref instanceof NavigationMenu.NavTarget) {
                    view.popupManager.navMenuPickTarget((NavigationMenu.NavTarget) row.ref);
                }
                break;
            default:
                break;
        }
    }

    int hitTestCodeActionsPopup(float x, float y) {
        if (!view.codeActionsPopupVisible || view.codeActionsPopupLine < 0) return -1;
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(view.codeActionsPopupLine);
        if (actions == null || actions.isEmpty()) return -1;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.CODE_ACTIONS_ROW_HEIGHT_DP * density;
        float width = view.CODE_ACTIONS_POPUP_WIDTH_DP * density;
        int rowsToShow = Math.min(view.CODE_ACTIONS_MAX_ROWS, actions.size());
        float popupH = rowH * rowsToShow;
        float anchorX = view.metrics.getGutterWidth() + 4 * density;
        // v3.15.0: use fold-aware Y (was raw padTop + line * lineHeight).
        float anchorY = view.docLineToY(view.codeActionsPopupLine) - view.vOffset;
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, anchorY - popupH + view.metrics.getLineHeight());
        }
        if (x < anchorX || x > anchorX + width || y < anchorY || y > anchorY + popupH) {
            return -1;
        }
        int rowInPopup = (int) ((y - anchorY) / rowH);
        if (rowInPopup < 0 || rowInPopup >= rowsToShow) return -1;
        return rowInPopup;
    }

    int hitTestGoToSymbolPopup(float x, float y) {
        if (!view.goToSymbolVisible || view.goToSymbolFiltered.isEmpty()) return -1;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.GO_TO_SYMBOL_ROW_HEIGHT_DP * density;
        float width = view.GO_TO_SYMBOL_WIDTH_DP * density;
        float filterH = rowH;
        int rowsToShow = Math.min(view.GO_TO_SYMBOL_MAX_ROWS, view.goToSymbolFiltered.size());
        float popupH = filterH + rowH * rowsToShow;
        float anchorX = (view.getWidth() - width) * 0.5f;
        float anchorY = 8 * density;
        float listTop = anchorY + filterH;
        float listBottom = anchorY + popupH;
        if (x < anchorX || x > anchorX + width || y < listTop || y > listBottom) {
            return -1;
        }
        int rowInList = (int) ((y - listTop) / rowH);
        if (rowInList < 0 || rowInList >= rowsToShow) return -1;
        return view.goToSymbolScrollOffset + rowInList;
    }

    int hitTestDiagnosticPopup(float x, float y) {
        if (!view.diagnosticPopupVisible || view.diagnosticPopupItem == null || view.session == null) return -1;
        DiagnosticShift.Diagnostic d = view.diagnosticPopupItem;
        int line = view.session.getDocument().lineForOffset(d.start);
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(line);
        if (actions == null || actions.isEmpty()) return -1;
        // v2.31: shared geometry with the draw pass (single source of truth).
        float[] m = view.diagnosticSheetMetrics();
        if (m == null) return -1;
        float panelTop = m[0], panelBottom = m[1], actionStartY = m[2], actionRowH = m[3];
        // Outside the panel entirely → scrim tap (dismiss; handled by caller).
        if (y < panelTop || y > panelBottom) return -1;
        if (y < actionStartY || y > actionStartY + actions.size() * actionRowH) return -1;
        int row = (int) ((y - actionStartY) / actionRowH);
        if (row < 0 || row >= actions.size()) return -1;
        return row;
    }

    /**
     * v2.31: True when (x, y) hits the diagnostic sheet's CLOSE button —
     * a tap there dismisses the sheet (CodeAssist DiagnosticSheet ×).
     */
    private boolean hitTestDiagnosticSheetClose(float x, float y) {
        if (!view.diagnosticPopupVisible) return false;
        float[] m = view.diagnosticSheetMetrics();
        if (m == null) return false;
        float closeCx = m[4], closeCy = m[5], closeR = m[6];
        float dx = x - closeCx, dy = y - closeCy;
        return dx * dx + dy * dy <= closeR * closeR * 4f; // generous 2× touch radius
    }

    // ════════════════════════════════════════════════════════════════
    // Selection toolbar
    // ════════════════════════════════════════════════════════════════

    void showSelectionToolbar() {
        view.selectionToolbarVisible = true;
        // v2.34 : horodate le show — l'animation d'entrée (entrancePop +
        // cascade par item dans drawSelectionToolbar) la consomme. Sans
        // ça, l'animation ne jouait jamais (shownAt restait à 0).
        view.selectionToolbarShownAt = android.os.SystemClock.uptimeMillis();
        view.selectionToolbarPressedIdx = -1;
        view.invalidate();
    }

    void dismissSelectionToolbar() {
        view.selectionToolbarVisible = false;
        view.selectionToolbarPressedIdx = -1;
        view.invalidate();
    }

    // ── v2.35: pendingTapDismiss (CodeAssist EditorInputModifier port) ──

    /**
     * Arms the deferred tap-dismiss: the tapped offset is committed — the
     * selection collapsed there, handles + pill hidden — if no second tap
     * arrives within {@link #MULTI_TAP_TIMEOUT_MS}. Re-arms cleanly if a
     * previous pending was still alive.
     */
    private void armPendingTapDismiss(int offset) {
        cancelPendingTapDismiss();
        pendingTapDismiss = offset;
        if (tapHandler == null) {
            // Main-looper handler — touch events always arrive on the UI
            // thread, and this stays testable under Robolectric's shadow
            // clock (view.getHandler() is null until the view is attached).
            tapHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        tapHandler.postDelayed(pendingTapDismissTask, MULTI_TAP_TIMEOUT_MS);
    }

    /**
     * Cancels the pending deferred tap-dismiss, if any. Called on every new
     * gesture (DOWN), every new tap (handleTap entry), swipe-beyond-slop,
     * gesture cancel and any text edit (via EditorView.onTextChanged).
     */
    void cancelPendingTapDismiss() {
        pendingTapDismiss = -1;
        if (tapHandler != null) tapHandler.removeCallbacks(pendingTapDismissTask);
    }

    /**
     * v2.34 — résout le tap sur la pill via les MÉTRIQUES PARTAGÉES
     * ({@link EditorView#selectionToolbarMetrics()}), plus aucune
     * duplication du layout. Les 6 actions CodeAssist sont câblées :
     * Copy/Cut/Paste/Select all + Docs ℹ / Actions ⋯ (avant, le hit-test
     * recalculait un layout 4-boutons obsolète — les icônes étaient
     * inatteignables et la pill collapsed rejetait tout tap).
     *
     * <p>Parité CodeAssist {@code SelectionToolbarLayer} : Copy/Cut/Paste
     * referment la pill (et masquent les poignées), Select all la laisse
     * ouverte (Copy/Cut deviennent disponibles), Docs ouvre le quick-doc,
     * Actions ouvre le popup de quick-fixes de la ligne.</p>
     */
    boolean handleSelectionToolbarTap(float x, float y) {
        if (!view.selectionToolbarVisible || view.session == null) return false;
        EditorView.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        if (m == null) return false;
        int act = m.actionAt(x, y);
        if (act < 0) return false;

        Selection sel = view.session.getSelection();
        switch (act) {
            case EditorView.SEL_ACT_COPY:
                view.copy();
                hideSelectionChrome();
                break;
            case EditorView.SEL_ACT_CUT:
                view.cut();
                hideSelectionChrome();
                break;
            case EditorView.SEL_ACT_PASTE:
                view.paste();
                hideSelectionChrome();
                break;
            case EditorView.SEL_ACT_SELECT_ALL:
                // CodeAssist : la pill RESTE ouverte après Select all —
                // Copy/Cut deviennent disponibles sur la sélection totale.
                view.session.selectAll();
                view.invalidate();
                break;
            case EditorView.SEL_ACT_DOCS:
                hideSelectionChrome();
                // ★ v2.36 : CodeAssist showQuickDoc() passe selection.START
                // (CodeEditor.kt l.306-312) — le mot sous le début de la
                // sélection se résout plus sûrement que sous sa fin.
                view.showQuickDoc(sel.start);
                break;
            case EditorView.SEL_ACT_ACTIONS:
                hideSelectionChrome();
                // ★ v2.36 : ouvre le MENU CONTEXTUEL UNIFIÉ (portage
                // NavMenu de CodeAssist — openNavMenu) : sections GO TO /
                // QUICK FIXES / INTENTIONS, au lieu de la simple liste de
                // quick-fixes.
                view.showNavMenu(view.session.getDocument()
                        .lineForOffset(sel.start), sel.start);
                break;
            default:
                return false;
        }
        return true;
    }

    /** CodeAssist {@code interaction.handlesVisible = false} : referme la pill + masque les poignées. */
    private void hideSelectionChrome() {
        view.handlesVisible = false;
        dismissSelectionToolbar();
    }

    // ════════════════════════════════════════════════════════════════
    // Inner classes — gesture + scale listeners
    // ════════════════════════════════════════════════════════════════

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // ★ v2.58 — pinch-zoom ancré sur le caret. L'ancienne impl
            // appliquait fontScale * scaleFactor SANS compenser les
            // offsets : le caret "sautait" à une nouvelle position écran
            // (les nouvelles metrics donnent un nouveau lineHeight/charWidth
            // → docLineToY(line) et visualCol * charWidth changent, vOffset
            // reste → le caret bouge visuellement).
            //
            // applyPinchScale capture la position caret AVANT le scale,
            // applique le scale, puis ajuste vOffset/hOffset pour ramener
            // le caret à sa position écran d'origine. Le caret reste fixe
            // visuellement et grandit/réduit avec le zoom.
            view.applyPinchScale(detector.getScaleFactor());
            return true;
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public void onLongPress(MotionEvent e) {
            handleLongPress(e.getX(), e.getY());
        }
    }
}
