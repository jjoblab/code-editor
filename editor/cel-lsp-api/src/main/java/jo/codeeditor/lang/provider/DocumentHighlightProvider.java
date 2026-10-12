package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.DocumentHighlight;

import java.util.List;

/**
 * Fournit le surlignage d'occurrences du document (usages du symbole sous
 * le caret).
 *
 * <p>Contrat : {@link #highlights} est appelée sur un thread de travail,
 * avec un debounce, quand le caret se déplace (sélection simple
 * uniquement) ; les exceptions sont interceptées par l'éditeur.</p>
 */
public interface DocumentHighlightProvider {

    /**
     * Retourne les plages à surligner pour le symbole à l'offset donné.
     *
     * @param text   le texte complet du document (instantané)
     * @param offset l'offset du symbole (indice de caractère, 0-based)
     * @return la liste des plages (préférer une liste vide à {@code null})
     */
    List<DocumentHighlight> highlights(CharSequence text, int offset);
}
