package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.InlayHint;

import java.util.List;

/**
 * Fournit les inlay hints (annotations de type fantômes, noms de
 * paramètres).
 *
 * <p>Contrat : {@link #inlayHints} est appelée sur un thread de travail,
 * avec un debounce après chaque édition ; l'éditeur demande actuellement la
 * plage du document ENTIER (première à dernière ligne). Les exceptions sont
 * interceptées ; une réponse vide ou {@code null} efface les hints
 * existants.</p>
 */
public interface InlayHintProvider {

    /**
     * Retourne les inlay hints pour la plage de lignes donnée.
     *
     * @param text      le texte complet du document (instantané)
     * @param startLine la première ligne (0-based, incluse)
     * @param endLine   la dernière ligne (0-based, incluse)
     * @return la liste des inlay hints (peut être vide)
     */
    List<InlayHint> inlayHints(CharSequence text, int startLine, int endLine);
}
