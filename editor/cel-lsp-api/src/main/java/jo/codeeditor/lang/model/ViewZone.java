package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.ViewZoneProvider;

/**
 * Une zone de vue — un espacement UI inséré entre deux lignes. Retournée
 * par {@link ViewZoneProvider}.
 *
 * <p>{@code heightPx} est la hauteur en pixels de l'espace. L'éditeur
 * réserve cet espace entre {@code afterLine} et la ligne suivante, et y
 * rend le {@code content} (une {@link android.view.View} Android).</p>
 */
public final class ViewZone {

    /** La ligne après laquelle l'espace est inséré (0-based). */
    public final int afterLine;
    /** La hauteur de l'espace en pixels. */
    public final int heightPx;
    /** La View personnalisée rendue dans l'espace. Peut être null pour un espace vide. */
    public final android.view.View content;
    /** Un identifiant unique de la zone (utilisé pour mise à jour/suppression). */
    public final int id;

    public ViewZone(int afterLine, int heightPx, android.view.View content, int id) {
        this.afterLine = afterLine;
        this.heightPx = heightPx;
        this.content = content;
        this.id = id;
    }
}
