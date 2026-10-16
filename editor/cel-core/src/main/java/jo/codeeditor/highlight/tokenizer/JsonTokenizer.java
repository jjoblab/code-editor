package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur JSON — <b>migré sur le moteur générique</b> (ADR-002,
 * chantier C2, étape 5) : la grammaire (chaînes quotées à
 * échappements apparents, clés requalifiées par lookahead de
 * deux-points, nombres signés, booléens/null, opérateurs
 * mono-caractère, chaîne orpheline à état inter-lignes) vit désormais
 * dans le template déclaratif {@link TokenizerTemplate#JSON}, exécuté
 * par {@link GenericTokenizer} en mode flux libre. Le dispatcher
 * ({@code SyntaxHighlighter}) continue d'appeler cette entrée —
 * signature inchangée, comportement repris octet pour octet de
 * l'implémentation historique écrite à la main (verrouillé par
 * {@code JsonTokenizerTest}, y compris la propagation de l'état
 * inter-lignes des chaînes non fermées).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites existantes restent vertes sans
 * modification de leurs assertions.</p>
 */
public final class JsonTokenizer {

    /**
     * Tokénise une ligne JSON — délégation au moteur générique avec le
     * template {@link TokenizerTemplate#JSON}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>chaîne suivie d'un deux-points → PROPERTY (la clé),
     *       sinon STRING (la valeur)</li>
     *   <li>séquences d'échappement internes → ESCAPE</li>
     *   <li>nombres (signe, décimales, exposants) → NUMBER</li>
     *   <li>true/false/null → KEYWORD, tout autre mot → PLAIN</li>
     *   <li>deux-points et virgules → OPERATOR mono-caractère</li>
     *   <li>crochets/accolades → PUNCT</li>
     *   <li>chaîne non fermée : l'état inter-lignes porte le littéral</li>
     * </ul></p>
     */
    public static StyledLine styleJson(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.JSON, line, entryState);
    }

    private JsonTokenizer() {}
}
