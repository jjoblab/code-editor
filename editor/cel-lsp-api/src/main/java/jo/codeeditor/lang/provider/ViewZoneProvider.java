package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.ViewZone;

import java.util.List;

/**
 * Fournit des zones de vue — des espacements UI insérés entre les lignes.
 * Prévu pour les refactorings en ligne, les indications de type inline et
 * les quick-fixes « ampoule » qui nécessitent plus qu'un popup.
 *
 * <p>Slot SPI actuellement sans consommateur : ni cel-lsp ni cel-ui
 * n'appellent {@link #viewZones} à ce jour.</p>
 */
public interface ViewZoneProvider {

    /**
     * Retourne les zones de vue pour la plage de lignes donnée.
     *
     * @param text      le texte complet du document (instantané)
     * @param startLine la première ligne (0-based, incluse)
     * @param endLine   la dernière ligne (0-based, incluse)
     * @return la liste des zones de vue (peut être vide)
     */
    List<ViewZone> viewZones(CharSequence text, int startLine, int endLine);
}
