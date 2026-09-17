package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.DefinitionLocation;

import java.util.List;

/**
 * Fournit les cibles du go-to-definition pour le symbole à un offset
 * donné.
 *
 * <p>Contrat : {@link #definitions} est appelée sur un thread de travail
 * et peut bloquer le temps d'une requête au moteur de langage ; les
 * exceptions sont interceptées par l'éditeur. Cible unique dans le même
 * fichier → navigation directe ; cibles multiples ou inter-fichiers →
 * sélecteur (ou listener hôte).</p>
 */
public interface DefinitionProvider {

    /**
     * Retourne les cibles de définition pour le symbole à l'offset donné.
     * Si la liste ne contient qu'une seule cible, l'éditeur y navigue
     * directement ; sinon il affiche un sélecteur.
     *
     * @param text   le texte complet du document (instantané)
     * @param offset l'offset du symbole (indice de caractère, 0-based)
     * @return la liste des cibles (peut être vide)
     */
    List<DefinitionLocation> definitions(CharSequence text, int offset);
}
