package jo.codeeditor.lang.provider;


/**
 * Fournit le formatage de code pour le document entier ou une plage.
 *
 * <p>Contrat : {@link #format} est appelée sur un thread de travail et non
 * sur le thread UI ; le résultat remplace le document en un seul pas
 * d'undo.</p>
 */
public interface Formatter {

    /**
     * Met en forme le texte donné et retourne le résultat formaté.
     *
     * @param text        le texte complet du document (instantané)
     * @param startOffset début de la plage à formater (0 pour tout le document)
     * @param endOffset   fin de la plage à formater (text.length() pour tout le document)
     * @return le texte formaté
     */
    CharSequence format(CharSequence text, int startOffset, int endOffset);
}
