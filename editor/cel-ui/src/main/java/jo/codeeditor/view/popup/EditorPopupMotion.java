package jo.codeeditor.view.popup;

import androidx.annotation.RestrictTo;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.PopupWindow;

/**
 * B7 (retour terrain n°4) — entrées/sorties animées des popups en
 * FENÊTRE (aller-à-la-ligne, renommage, feuille d'aperçu) : « les popup
 * améliorations, l'animation quand ils sont affichés et disparaissent
 * pour être plus fluides — je crois que le mécanisme existe déjà dans le
 * code ». Le mécanisme existe (l'animatorie du glissement du caret) —
 * cette classe le prête aux cartes flottantes.
 *
 * <ul>
 *   <li><b>Entrée (140 ms, décélération)</b> — la carte part de
 *       {@code driftY} px et de l'opacité 0 puis se pose ;</li>
 *   <li><b>Sortie (110 ms, accélération)</b> — la vitre s'efface et
 *       dérive, la fenêtre meurt à la fin de l'animation ;</li>
 *   <li><b>Garde d'environnement</b> — une carte jamais mesurée (harnais
 *       Robolectric sans Choreographer, fenêtre déjà morte) : dismiss
 *       immédiat, aucun appelant n'a à connaître la subtilité.</li>
 * </ul>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class EditorPopupMotion {

    private EditorPopupMotion() {}

    /**
     * Entrée animée d'une carte en fenêtre : la carte part de
     * {@code driftY} px (négatif : elle descend du haut ; positif : elle
     * monte du bas) et de l'opacité 0, puis se pose en 140 ms
     * (décélération).
     */
    public static void cardIn(View card, float driftY) {
        if (card == null) return;
        card.setAlpha(0f);
        card.setTranslationY(driftY);
        card.animate().alpha(1f).translationY(0f)
                .setDuration(140L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /**
     * Sortie animée d'une carte en fenêtre : la vitre s'efface et dérive
     * de {@code driftY} px en 110 ms (accélération), la fenêtre meurt à
     * la fin. Carte absente, jamais mesurée (Robolectric sans frame) ou
     * fenêtre déjà morte (Retour système, double dismiss) : dismiss
     * immédiat.
     */
    public static void cardOut(PopupWindow popup, float driftY) {
        if (popup == null) return;
        View card = popup.getContentView();
        if (card == null || card.getWidth() == 0 || !popup.isShowing()) {
            popup.dismiss();
            return;
        }
        card.animate().alpha(0f).translationY(driftY)
                .setDuration(110L)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(popup::dismiss)
                .start();
    }

    /**
     * B8 (retour terrain n°5) — « le touché en dehors du cadre dismiss le
     * popup » : rend une PopupWindow non-modale au toucher et notifiée des
     * touchers extérieurs ({@code ACTION_OUTSIDE}), ce que le
     * {@code PopupDecorView} d'Android traduit par un
     * {@code dismiss()} automatique. Le tap traverse vers la vue SOUS la
     * fenêtre (l'éditeur place son caret) ET referme la carte.
     *
     * <p>La recette exige les DEUX moitiés : {@code setOutsideTouchable(true)}
     * pose {@code FLAG_WATCH_OUTSIDE_TOUCH}, et un fond NON NULL (ici
     * transparent — l'apparence de la carte reste celle de son propre
     * GradientDrawable de contenu) rend la fenêtre non-modale au toucher.
     * Une seule moitié ne referme rien.</p>
     *
     * <p>Les entrées clavier restent captées (focusable inchangé) : la
     * saisie dans un champ de carte fonctionne toujours ; seul le tap
     * extérieur gagne le pouvoir de fermer.</p>
     */
    public static void outsideTapDismisses(PopupWindow popup) {
        if (popup == null) return;
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    }
}
