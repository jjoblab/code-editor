package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

/**
 * Hover tap-and-hold d'EditorView, extrait d'EditorInputHandler : montre le
 * popup quick doc après un appui long SANS mouvement, sans déclencher la
 * sélection. État et temporalité du geste (armement au DOWN, annulation au
 * relèvement/mouvement) ; l'accès à l'état du dispatcher passe par
 * EditorInputHandler.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorTouchHoverController {

    private final EditorView view;
    private final EditorInputHandler input;

    // ── Hover tap-and-hold (distinct de la sélection par appui long) ──
    // Parité « alwaysShowOnTouchHover » de Sora : sur appareils tactiles,
    // un temps d'appui PLUS LONG (TAP_HOLD_HOVER_TIMEOUT_MS) sans
    // mouvement/relèvement montre le popup quick doc à l'offset touché
    // SANS déclencher la sélection (pas de poignées, pas de toolbar).
    // L'appui long classique de 400 ms reste le geste de sélection — les
    // deux sont mutuellement exclusifs :
    //
    //   - touchHoverEnabled = false (défaut) : seulement l'appui long
    //     classique (400 ms → sélection + poignées + toolbar + quick doc,
    //     le comportement historique fusionné).
    //   - touchHoverEnabled = true : l'appui long est supprimé et remplacé
    //     par un tap-and-hold de 500 ms → quick doc seul. La sélection
    //     fonctionne toujours via double-tap et drag-select.
    //
    // Le drapeau se règle via EditorView.setTouchHoverEnabled(boolean).
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
            // Re-contrôle que le doigt n'a pas bougé au-delà du slop depuis DOWN.
            float dx = input.currentTouchX() - tapHoldHoverStartX;
            float dy = input.currentTouchY() - tapHoldHoverStartY;
            if (dx * dx + dy * dy > TAP_HOLD_HOVER_SLOP_PX * TAP_HOLD_HOVER_SLOP_PX) {
                return;  // bougé → c'est un drag, pas un hover
            }
            // Re-contrôle qu'aucun scroll/drag n'a commencé.
            if (input.isScrollingOrDragging()) return;
            // Déclenche le hover — montre le quick doc à l'offset touché.
            int offset = view.offsetAt(tapHoldHoverStartX, tapHoldHoverStartY);
            if (offset >= 0 && view.quickDocResolver != null) {
                tapHoldHoverTriggered = true;
                view.showQuickDoc(offset);
            }
        }
    };

    EditorTouchHoverController(EditorView view, EditorInputHandler input) {
        this.view = view;
        this.input = input;
    }

    void cancel() {
        if (input.tapHandler != null) {
            input.tapHandler.removeCallbacks(tapHoldHoverTask);
        }
        tapHoldHoverTriggered = false;
    }

    void schedule(float x, float y) {
        if (!view.touchHoverEnabled) return;
        if (view.quickDocResolver == null) return;
        tapHoldHoverStartX = x;
        tapHoldHoverStartY = y;
        tapHoldHoverTriggered = false;
        // Quand le hover tactile est armé, supprime l'appui long classique
        // (sélection) pour que les deux gestes ne se déclenchent pas
        // ensemble à 400 ms vs 500 ms. Le drapeau est restauré sur
        // ACTION_UP/CANCEL/MOVE-au-delà-du-slop.
        input.setLongPressEnabled(false);
        if (input.tapHandler == null) {
            input.tapHandler = view.getHandler();
            if (input.tapHandler == null) {
                input.tapHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            }
        }
        input.tapHandler.postDelayed(tapHoldHoverTask, TAP_HOLD_HOVER_TIMEOUT_MS);
    }
}
