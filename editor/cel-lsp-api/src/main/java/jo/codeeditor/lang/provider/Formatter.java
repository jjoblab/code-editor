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

    /**
     * Le formateur prend-il en charge le formatage d'une PLAGE partielle
     * ({@code startOffset}/{@code endOffset} ≠ document entier) ?
     *
     * <p>Quand un formateur ne gère PAS les plages, la demande de plage
     * est REFUSÉE proprement (texte inchangé) — le document entier n'est
     * JAMAIS formaté à la place de la plage demandée : ce serait une
     * surprise destructive pour l'utilisateur.</p>
     *
     * @return vrai si {@link #format} traite les plages partielles
     * @since 3.74.0
     */
    default boolean supportsRange() {
        return false;
    }
}
