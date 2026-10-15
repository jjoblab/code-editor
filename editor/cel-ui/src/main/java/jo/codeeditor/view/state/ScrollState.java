package jo.codeeditor.view.state;

import androidx.annotation.RestrictTo;

/**
 * État de défilement de l'éditeur — objet d'état extrait d'EditorView
 * (plan 01 « Décomposer EditorView », étape 5).
 *
 * <p>Détient la position de la fenêtre de visualisation dans le
 * contenu : {@code vOffset} (vertical, en pixels de contenu) et
 * {@code hOffset} (horizontal). Les painters et les couches
 * d'ancrage lisent ces valeurs PENDANT le rendu.</p>
 *
 * <p><b>Invariant possédé</b> : les offsets restent dans
 * {@code [0, max]} — maintenu par {@link #clampTo(float, float)} après
 * toute mutation non bornée (drag, fling, zoom, changement de taille,
 * restauration d'état). Les bornes {@code maxV()}/{@code maxH()} sont
 * calculées par la géométrie de la vue (hauteur/largeur de contenu,
 * fold- et wrap-aware) : l'état les reçoit en paramètre, il ne les
 * connaît pas.</p>
 *
 * <p><b>Thread-safety</b> : mutabilité confinée au thread UI
 * (pipeline de scroll : gestionnaire de défilement, scroller tactile,
 * contrôleur de zoom, restauration d'état) ; lecture seule pendant
 * {@code onDraw}.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class ScrollState {

    /** Position verticale de la fenêtre (pixels de contenu). */
    public float vOffset = 0;

    /** Position horizontale de la fenêtre (pixels de contenu). */
    public float hOffset = 0;

    /** Remise à l'origine (changement de document : un offset de
     *  l'ancien document ne veut plus rien dire). */
    public void reset() {
        vOffset = 0;
        hOffset = 0;
    }

    /**
     * Ramène les deux offsets dans leurs bornes. À appeler après toute
     * mutation non bornée ET aux changements de géométrie (le max
     * bouge : taille de vue, zoom, plis, wrap).
     */
    public void clampTo(float maxV, float maxH) {
        vOffset = Math.max(0, Math.min(vOffset, maxV));
        hOffset = Math.max(0, Math.min(hOffset, maxH));
    }

    /**
     * Restauration bornée (état d'instance sauvé) — les valeurs
     * historiques sont ramenées dans la géométrie COURANTE.
     */
    public void restore(float v, float h, float maxV, float maxH) {
        vOffset = v;
        hOffset = h;
        clampTo(maxV, maxH);
    }

    /**
     * Restauration brute (aucune session posée : maxV()/maxH() valent
     * 0, les valeurs seraient écrasées) — le bornage surviendra au
     * premier usage (scroll, setSession, changement de taille).
     */
    public void restoreUnclamped(float v, float h) {
        vOffset = v;
        hOffset = h;
    }
}
