package jo.codeeditor.lang.provider;


import jo.codeeditor.lang.model.SignatureHelp;

import java.util.List;

/**
 * Fournit l'aide de signature (paramètre courant) quand le caret est à
 * l'intérieur d'un appel de fonction.
 *
 * <p>Contrat : l'éditeur n'appelle {@link #signatureHelp} que si le caret
 * est dans un appel et que le popup n'a pas été rejeté ; l'appel a lieu
 * sur un thread de travail et peut bloquer le temps d'une requête au
 * moteur de langage.</p>
 */
public interface SignatureHelpProvider {

    /**
     * Retourne l'aide de signature pour la position du caret, ou
     * {@code null} si le caret n'est pas dans un appel de fonction.
     *
     * @param text  le texte complet du document (instantané)
     * @param caret l'offset du caret (indice de caractère, 0-based)
     * @return l'aide de signature, ou {@code null}
     */
    SignatureHelp signatureHelp(CharSequence text, int caret);

    /**
     * Retourne les caractères qui déclenchent l'aide de signature
     * (ex. {@code "("}, {@code ","}).
     */
    List<String> getTriggerCharacters();
}
