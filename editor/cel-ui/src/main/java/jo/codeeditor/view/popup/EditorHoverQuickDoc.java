package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.view.MotionEvent;

/**
 * Survol souris → quick doc : sur desktop / ChromeOS / DeX, un survol
 * immobile de 500 ms au-delà d'un slop de 20 px déclenche le popup quick
 * doc du symbole sous le POINTEUR (pas sous le caret).
 *
 * <p>Extrait d'EditorView : enregistre le listener de survol sur la vue
 * ({@code attach()}) et possède l'état de dwell (position du pointeur,
 * action différée). Le callback de survol est un no-op sur les appareils
 * purement tactiles (HoverEvent n'y est pas distribué).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class EditorHoverQuickDoc {

    private final EditorView view;

    private static final long QUICK_DOC_HOVER_DELAY_MS = 500;
    /** Slop souris (px) avant de re-résoudre le symbole survolé (HOVER_TAP_SLOP = 20px) — un tremblement sous le seuil ne redémarre PAS le dwell. */
    private static final float HOVER_SLOP_PX = 20f;
    // Position du POINTEUR (pas du caret !) sous laquelle résoudre le
    // hover : résoudre à selection.start afficherait le MAUVAIS doc quand
    // on survole un autre symbole sans bouger le caret.
    private float hoverX, hoverY;
    private boolean hoverPositionValid = false;
    private final Runnable quickDocHoverAction;

    public EditorHoverQuickDoc(EditorView view) {
        this.view = view;
        this.quickDocHoverAction = () -> {
            if (view.session != null && hoverPositionValid) {
                // Résout sous le POINTEUR (la CharPosition sous la
                // souris, jamais le caret).
                int offset = view.offsetAt(hoverX, hoverY);
                if (offset >= 0) view.showQuickDoc(offset);
            }
        };
    }

    /**
     * Enregistre le listener de survol sur la vue. Appelé une fois depuis
     * le constructeur d'EditorView.
     */
    public void attach() {
        // Le survol souris déclenche le quick doc après 500ms
        // (desktop / ChromeOS / DeX). Le callback de survol est un no-op
        // sur les appareils tactiles uniquement (HoverEvent n'est pas
        // distribué pour les touchers). Le dwell est redémarré à chaque
        // déplacement au-delà du slop (20px) et résout le symbole sous le
        // POINTEUR (plus sous le caret).
        view.setOnHoverListener((v, event) -> {
            if (view.getHandler() == null) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE: {
                    float nx = event.getX(), ny = event.getY();
                    float dx = nx - hoverX, dy = ny - hoverY;
                    boolean movedBeyondSlop = !hoverPositionValid
                            || dx * dx + dy * dy > HOVER_SLOP_PX * HOVER_SLOP_PX;
                    if (movedBeyondSlop) {
                        hoverX = nx;
                        hoverY = ny;
                        hoverPositionValid = true;
                        view.getHandler().removeCallbacks(quickDocHoverAction);
                        view.getHandler().postDelayed(quickDocHoverAction,
                                QUICK_DOC_HOVER_DELAY_MS);
                    }
                    break;
                }
                case MotionEvent.ACTION_HOVER_EXIT:
                case MotionEvent.ACTION_CANCEL:
                    hoverPositionValid = false;
                    view.getHandler().removeCallbacks(quickDocHoverAction);
                    break;
            }
            return false; // ne consomme pas — laisse le framework continuer à distribuer.
        });
    }
}
