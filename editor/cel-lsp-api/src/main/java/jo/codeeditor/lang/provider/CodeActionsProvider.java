package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.CodeAction;

import java.util.List;

/**
 * Fournit les actions de code (quick-fixes, refactorings) pour une ligne
 * donnée.
 *
 * <p>Contrat : {@link #codeActions} est appelée sur un thread de travail,
 * en boucle pour chaque ligne visible (avec un debounce) ainsi qu'à la
 * demande pour les actions « source » (ex. organisation des imports). Le
 * runnable {@link CodeAction#apply} d'une action retenue est exécuté plus
 * tard sur le thread UI : il peut donc muter le document.</p>
 */
public interface CodeActionsProvider {

    /**
     * Retourne les actions de code disponibles pour la ligne donnée.
     *
     * @param text le texte complet du document (instantané)
     * @param line l'index de ligne (0-based)
     * @return la liste des actions de code (peut être vide)
     */
    List<CodeAction> codeActions(CharSequence text, int line);
}
