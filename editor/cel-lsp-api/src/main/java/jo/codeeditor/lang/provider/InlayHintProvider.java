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

    /**
     * Résout paresseusement le tooltip d'un hint — appelé au PREMIER
     * appui long sur le hint, jamais au rendu (I2, lot 2 J3). Le hint
     * retourné porte le tooltip résolu (null si le serveur n'en a pas).
     *
     * <p>Implémentation par défaut : le hint est retourné inchangé (pas
     * de résolution) — un provider sans support de résolution (le démon
     * interne, les serveurs sans {@code resolveProvider}) n'émet AUCUNE
     * requête. Le hint passé référence un hint renvoyé précédemment par
     * {@link #inlayHints} (même offset, même texte) ; un hint inconnu
     * (périmé par une édition) est retourné inchangé.</p>
     *
     * @param text le texte complet du document (instantané)
     * @param hint le hint à résoudre
     * @return le hint portant son tooltip (jamais null)
     */
    default InlayHint resolveHint(CharSequence text, InlayHint hint) {
        return hint;
    }
}
