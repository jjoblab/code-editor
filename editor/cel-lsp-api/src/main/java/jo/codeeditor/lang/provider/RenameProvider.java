package jo.codeeditor.lang.provider;

import jo.codeeditor.lang.model.RenameResult;

/**
 * Fournit le renommage (renommer toutes les occurrences du symbole sous
 * le caret).
 *
 * <p>Contrat : {@link #rename} est appelée sur un thread de travail et
 * peut bloquer le temps d'une requête au moteur de langage ; les
 * exceptions sont interceptées par l'éditeur. Un retour {@code null} fait
 * retomber l'éditeur sur son heuristique locale de renommage par
 * identifiant.</p>
 */
public interface RenameProvider {

    /**
     * Valide, AVANT l'ouverture du popup de renommage, que le symbole à
     * l'offset est renommable, et retourne le nom proposé (placeholder
     * pré-rempli du champ).
     *
     * <p>Implémentation par défaut : {@code null} (non supporté —
     * l'éditeur garde son heuristique locale sans requête).</p>
     *
     * @param text   le texte complet du document (instantané)
     * @param offset l'offset du symbole (indice de caractère, 0-based)
     * @return le nom proposé, ou {@code null} si le renommage n'est pas
     *         disponible à cet offset (l'éditeur refuse proprement
     *         l'ouverture au lieu d'échouer plus tard)
     */
    default String prepareRename(CharSequence text, int offset) {
        return null;
    }

    /**
     * Calcule les éditions de renommage du symbole à l'offset donné.
     *
     * @param text    le texte complet du document (instantané)
     * @param offset  l'offset du symbole (indice de caractère, 0-based)
     * @param newName le nouveau nom
     * @return le résultat du renommage (liste d'éditions), ou {@code null}
     *         si le renommage n'est pas disponible à cet offset
     */
    RenameResult rename(CharSequence text, int offset, String newName);
}
