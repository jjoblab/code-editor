package jo.codeeditor.lang.provider;

import jo.codeeditor.lang.model.CodeAction;

import java.util.List;

/**
 * Fournit les actions de code (quick-fixes, refactorings, intentions)
 * pour un point du document.
 *
 * <p>Contrat : {@link #codeActions} est appelée sur un thread de travail,
 * au moment où l'utilisateur DEMANDE les actions (raccourci clavier,
 * ampoule, menu contextuel) — une seule résolution par invocation, sur
 * la plage que l'utilisateur vise (sélection active, sinon mot sous le
 * caret, sinon ligne). Le runnable {@link CodeAction#apply} d'une action
 * retenue est exécuté plus tard sur le thread UI : il peut donc muter
 * le document.</p>
 *
 * <p>La méthode historique {@link #codeActions(CharSequence, int)} reste
 * le point d'entrée des implémentations existantes : la variante au
 * caret délègue à celle-ci par défaut, et une implémentation qui ne
 * surcharge que la méthode ligne ne peut pas casser.</p>
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

    /**
     * Retourne les actions de code disponibles au point d'invocation.
     *
     * <p>La plage visée se déduit des arguments : sélection non vide →
     * la sélection (terrain des refactorings d'extraction) ; sinon le
     * mot sous le caret ; sinon la ligne du caret. Les implémentations
     * riches s'appuient sur cette plage et sur les diagnostics qui
     * l'intersectent pour proposer des actions exactes.</p>
     *
     * @param text           le texte complet du document (instantané)
     * @param caretOffset    l'offset du caret (0-based)
     * @param selectionStart l'offset de début de la sélection (0-based)
     * @param selectionEnd   l'offset de fin de la sélection (exclu)
     * @param onlyKinds      les familles d'actions demandées
     *                       (ex. {@code quickfix}, {@code source.organizeImports}),
     *                       ou {@code null} pour toutes les familles
     * @return la liste des actions de code (peut être vide)
     */
    default List<CodeAction> codeActions(CharSequence text, int caretOffset,
            int selectionStart, int selectionEnd, List<String> onlyKinds) {
        // Repli : la ligne contenant le caret.
        int borne = text == null ? 0 : Math.max(0, Math.min(caretOffset, text.length()));
        int line = 0;
        for (int i = 0; i < borne; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return codeActions(text, line);
    }
}
