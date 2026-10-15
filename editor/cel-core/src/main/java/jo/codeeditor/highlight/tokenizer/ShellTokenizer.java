package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur Shell/Bash — <b>migré sur le moteur générique</b>
 * (ADR-002, chantier C2, étape 3) : la grammaire (commentaires ancrés,
 * variables {@code $var}/{@code ${var}}/{@code $?}/{@code $1}, chaînes
 * doubles à échappements sautés, littéraux à état inter-lignes,
 * substitution par backticks, mots-clés et intégrés du registre,
 * appels de fonction, courses d'opérateurs) vit désormais dans le
 * template déclaratif {@link TokenizerTemplate#SHELL}, exécuté par
 * {@link GenericTokenizer} en mode flux libre. Le dispatcher
 * ({@code SyntaxHighlighter}) continue d'appeler cette entrée —
 * signature inchangée, comportement repris octet pour octet de
 * l'implémentation historique écrite à la main (verrouillé par
 * {@code ShellTokenizerTest}, y compris la propagation de l'état
 * inter-lignes des apostrophes non fermées).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites {@code SyntaxHighlighterTest} et
 * {@code ShellTokenizerTest} restent vertes sans modification de leurs
 * assertions.</p>
 */
public final class ShellTokenizer {

    /**
     * Tokénise une ligne Shell/Bash — délégation au moteur générique
     * avec le template {@link TokenizerTemplate#SHELL}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>commentaires de ligne {@code #} ancrés (début/blanc/{@code ;}) → COMMENT</li>
     *   <li>références de variables {@code $var} et {@code ${var}} → VARIABLE</li>
     *   <li>chaînes à guillemets doubles et littérales entre apostrophes → STRING</li>
     *   <li>apostrophe non fermée : l'état inter-lignes porte le littéral</li>
     *   <li>backticks ({@code `cmd`}) → ANNOTATION (substitution de commande)</li>
     *   <li>mots-clés et intégrés (registre) → KEYWORD</li>
     *   <li>appel suivi de {@code (} → FUNC, initiale majuscule → CONSTANT</li>
     *   <li>nombres → NUMBER, courses d'opérateurs → OPERATOR</li>
     * </ul></p>
     */
    public static StyledLine styleShell(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.SHELL, line, entryState);
    }

    private ShellTokenizer() {}
}
