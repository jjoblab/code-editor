package jo.codeeditor.lang.provider;

import jo.codeeditor.lang.model.DefinitionLocation;

import java.util.List;

/**
 * Fournit les cibles du go-to-implementations : les héritiers directs
 * (sous-types) du type à un offset donné.
 *
 * <p>Alimente la section GO TO du menu contextuel unifié de la barre de
 * sélection. Même contrat de thread que {@link DefinitionProvider} : appel
 * sur un thread de travail, exceptions interceptées par l'éditeur.</p>
 */
public interface ImplementationsProvider {

    /**
     * Retourne les cibles des héritiers directs du type en contexte à
     * l'offset donné (une référence de type, ou le type englobant du
     * caret). Cible unique → navigation directe ; plusieurs → sélecteur.
     *
     * @param text   le texte complet du document (instantané)
     * @param offset l'offset du caret (indice de caractère, 0-based)
     * @return la liste des cibles (peut être vide)
     */
    List<DefinitionLocation> implementations(CharSequence text, int offset);
}
