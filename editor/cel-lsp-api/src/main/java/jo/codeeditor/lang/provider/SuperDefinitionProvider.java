package jo.codeeditor.lang.provider;

import jo.codeeditor.lang.model.DefinitionLocation;

import java.util.List;

/**
 * Fournit les cibles du go-to-super : pour un membre redéfini à un offset
 * donné, le membre de même nom dans chaque supertype ; sinon les
 * supertypes directs du type en contexte.
 *
 * <p>Alimente la section GO TO du menu contextuel unifié de la barre de
 * sélection. Même contrat de thread que {@link DefinitionProvider} : appel
 * sur un thread de travail, exceptions interceptées par l'éditeur.</p>
 */
public interface SuperDefinitionProvider {

    /**
     * Retourne les cibles super à l'offset donné. Cible unique →
     * navigation directe ; plusieurs → sélecteur.
     *
     * @param text   le texte complet du document (instantané)
     * @param offset l'offset du caret (indice de caractère, 0-based)
     * @return la liste des cibles (peut être vide)
     */
    List<DefinitionLocation> superTargets(CharSequence text, int offset);
}
