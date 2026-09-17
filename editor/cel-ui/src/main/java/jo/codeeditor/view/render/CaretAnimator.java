package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.animation.ValueAnimator;
import android.view.animation.LinearInterpolator;

/**
 * Gère le clignotement et l'animation de glisse (glide) du curseur pour
 * {@link EditorView}.
 *
 * <p>Le curseur a deux modes d'animation :</p>
 * <ul>
 *   <li><b>Clignotement</b> — bascule la visibilité toutes les
 *       {@link #BLINK_MS} ms au repos. Passe en plein (solide) à chaque
 *       édition / déplacement du curseur puis reprend le clignotement après
 *       {@link #SOLID_AFTER_EDIT_MS} ms d'inactivité.</li>
 *   <li><b>Glide</b> — interpolation fluide entre deux positions écran quand
 *       le curseur se déplace dans le viewport (100 ms, sans dépassement).
 *       Placement immédiat (snap, pas de glide) pour les sauts hors viewport
 *       / éditions / déplacements dus au retour à la ligne.</li>
 * </ul>
 *
 * <p><b>Source unique de vérité :</b> tout l'état du curseur vit ici.
 * {@link EditorView} et {@link EditorRenderer} DOIVENT lire/écrire via les
 * méthodes de cette classe — ils ne doivent PAS conserver de champs alias
 * dupliqués. Sinon, la branche snap de {@code drawCaret} écraserait l'alias
 * fraîchement mis à jour avec une valeur {@code animX} périmée d'un glide
 * annulé, produisant un retard visuel d'une frame à chaque frappe.</p>
 *
 * <p>La classe n'est pas thread-safe — tout accès doit se faire sur le
 * thread UI.</p>
 *
 * @author jo@Dev
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class CaretAnimator {

    static final long BLINK_MS = 530;
    static final long SOLID_AFTER_EDIT_MS = 530;
    private static final long GLIDE_MS = 100;

    // ── État du clignotement ─────────────────────────────────────
    /** Vrai tant que le curseur doit être dessiné (solide ou phase allumée du clignotement). */
    boolean visible = true;
    /** Horodatage de la dernière activité utilisateur (édition ou déplacement) — maintient le curseur solide. */
    long lastEditTime = 0;
    /** Horodatage de la dernière bascule de clignotement (aligné sur lastEditTime à l'édition/déplacement). */
    long lastToggle = 0;

    // ── État du glide ─────────────────────────────────────────────
    /** Position animée courante — celle que le renderer doit dessiner. */
    float animX = 0f;
    float animY = 0f;
    /** Position cible vers laquelle le glide interpole. */
    float targetX = 0f;
    float targetY = 0f;
    /** Faux tant que le premier snap n'a pas placé le curseur — le premier dessin doit snapper. */
    boolean ready = false;
    /**
     * Révision du document capturée au dernier snap/glide. Si le prochain
     * dessin voit une révision différente, le document a été édité — snap
     * (pas de glide).
     */
    int rev = 0;

    private ValueAnimator animator;
    private final EditorView view;

    public CaretAnimator(EditorView view) {
        this.view = view;
    }

    /**
     * Appelé à chaque édition / déplacement du curseur. Rend le curseur
     * plein (visible), annule tout glide en cours et réinitialise le
     * minuteur de bascule afin que le clignotement reprenne
     * {@link #SOLID_AFTER_EDIT_MS} ms après la dernière édition.
     *
     * <p>Point d'entrée UNIQUE pour « l'utilisateur a touché l'éditeur ».
     * EditorView.onTextChanged, EditorImeBridge et EditorInputHandler
     * délèguent tous ici — ils ne doivent PAS muter l'état du clignotement
     * directement.</p>
     */
    public void onEditOrMove() {
        long now = System.currentTimeMillis();
        lastEditTime = now;
        visible = true;
        lastToggle = now;
        cancelGlide();
    }

    /**
     * Place le curseur directement en {@code (targetX, targetY)} — sans
     * glide. Cas d'usage : premier placement, éditions du document, sauts
     * hors viewport, déplacements dus au retour à la ligne. Met à jour TOUT
     * l'état (animX/Y, targetX/Y, ready, rev) afin que les frames suivantes
     * voient un instantané cohérent.
     *
     * @param targetX nouveau X du curseur (coordonnées écran)
     * @param targetY nouveau Y du curseur (coordonnées écran)
     * @param docRev  révision courante du document
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
     * Démarre un glide depuis la position animée courante vers
     * {@code (targetX, targetY)}. Interpolateur linéaire (AUCUN dépassement)
     * et durée courte de 100 ms — rapide et fluide, à la IntelliJ.
     *
     * <p>Met à jour {@code targetX/Y} et {@code rev} afin que la décision
     * snap-vs-glide de la frame suivante voie un état cohérent.</p>
     *
     * @param targetX nouveau X du curseur (coordonnées écran)
     * @param targetY nouveau Y du curseur (coordonnées écran)
     * @param docRev  révision courante du document
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

    /** Annule tout glide en cours. Ne touche PAS à animX/Y/target/rev. */
    public void cancelGlide() {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        animator = null;
    }

    /**
     * Appelé depuis le chemin de dessin. Bascule le clignotement si
     * suffisamment de temps s'est écoulé.
     *
     * @return vrai si le curseur doit être dessiné (visible), faux sinon.
     */
    boolean updateBlink() {
        long now = System.currentTimeMillis();
        long lastEdit = lastEditTime;
        // Plein après édition — pas de clignotement pendant SOLID_AFTER_EDIT_MS ms.
        if (now - lastEdit < SOLID_AFTER_EDIT_MS) {
            visible = true;
            return true;
        }
        // Période de clignotement.
        if (now - lastToggle >= BLINK_MS) {
            visible = !visible;
            lastToggle = now;
        }
        return visible;
    }

    /** Réinitialise TOUT l'état — à appeler lors d'un changement de session. */
    public void reset() {
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
