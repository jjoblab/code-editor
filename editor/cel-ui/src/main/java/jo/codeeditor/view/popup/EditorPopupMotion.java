package jo.codeeditor.view.popup;

import androidx.annotation.RestrictTo;

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
}
