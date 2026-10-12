package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.DefinitionLocation;

import java.util.List;

/**
 * Fournit les cibles de la recherche de références pour le symbole à un
 * offset donné.
 *
 * <p>Une « référence » est tout usage du symbole — la déclaration du
 * symbole lui-même n'est PAS incluse (c'est le rôle du
 * {@link DefinitionProvider}). L'éditeur affiche les résultats dans une
 * liste à sélectionner, éventuellement inter-fichiers quand le serveur de
 * langage les rapporte.</p>
 *
 * <p>Même contrat de thread que {@link DefinitionProvider} : appel sur un
 * thread de travail, exceptions interceptées par l'éditeur.</p>
 */
public interface ReferencesProvider {

    /**
     * Retourne les références du symbole à l'offset donné.
     *
     * @param text   le texte complet du document (instantané)
     * @param offset l'offset du symbole (indice de caractère, 0-based)
     * @return la liste des localisations de référence (peut être vide)
     */
    List<DefinitionLocation> references(CharSequence text, int offset);
}
