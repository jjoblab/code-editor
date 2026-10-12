package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.HoverContent;

/**
 * Fournit le contenu de hover/quick-doc pour le symbole à un offset donné.
 *
 * <p>Contrat : l'éditeur appelle {@link #hover} sur un thread de travail
 * (jamais sur le thread UI, pour éviter les ANR) ; l'appel peut bloquer le
 * temps d'une requête au moteur de langage. Retourner {@code null} (ou un
 * contenu vide) si aucun hover n'est disponible — le popup existant est
 * alors refermé.</p>
 */
public interface HoverProvider {

    /**
     * Retourne le contenu de hover pour le symbole à l'offset donné, ou
     * {@code null} si aucun hover n'est disponible.
     *
     * @param text   le texte complet du document (instantané)
     * @param offset l'offset du symbole (indice de caractère, 0-based)
     * @return le contenu de hover, ou {@code null}
     */
    HoverContent hover(CharSequence text, int offset);
}
