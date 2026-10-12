package jo.codeeditor.lang.provider;

import jo.codeeditor.lang.model.DefinitionLocation;

import java.util.List;

/**
 * Fournit les cibles du go-to-type-declaration : la déclaration du TYPE du
 * symbole à un offset donné ({@code Foo x = ...} → {@code class Foo}).
 *
 * <p>Alimente la section GO TO du menu contextuel unifié de la barre de
 * sélection. Même contrat de thread que {@link DefinitionProvider} : appel
 * sur un thread de travail, exceptions interceptées par l'éditeur.</p>
 */
public interface TypeDefinitionProvider {

    /**
     * Retourne les cibles de déclaration du type du symbole à l'offset
     * donné. Cible unique → navigation directe ; plusieurs → sélecteur.
     *
     * @param text   le texte complet du document (instantané)
     * @param offset l'offset du symbole (indice de caractère, 0-based)
     * @return la liste des cibles (peut être vide)
     */
    List<DefinitionLocation> typeDefinitions(CharSequence text, int offset);
}
