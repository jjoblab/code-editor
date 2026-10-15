package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur YAML — <b>migré sur le moteur générique</b> (ADR-002,
 * chantier C2, étape 4) : la grammaire (clés confirmées par lookahead
 * de {@code :}, marqueur de document {@code ---}, ancres/alias/tags,
 * indicateurs de scalaire de bloc, chaînes quotées dans les deux zones,
 * mots de valeur classés nombre/constant/scalaire, scalaires bruts,
 * ponctuation de flux) vit désormais dans le template déclaratif
 * {@link TokenizerTemplate#YAML}, exécuté par {@link GenericTokenizer}.
 * Le dispatcher ({@code SyntaxHighlighter}) continue d'appeler cette
 * entrée — signature inchangée, comportement repris octet pour octet
 * de l'implémentation historique écrite à la main (verrouillé par
 * {@code YamlTokenizerTest}, y compris la réouverture de zone clé
 * après une chaîne quotée).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites {@code SyntaxHighlighterTest} et
 * {@code YamlTokenizerTest} restent vertes sans modification de leurs
 * assertions.</p>
 */
public final class YamlTokenizer {

    /**
     * Tokénise une ligne YAML — délégation au moteur générique avec le
     * template {@link TokenizerTemplate#YAML}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>{@code key:} (lookahead) → PROPERTY, le ':' → OPERATOR</li>
     *   <li>commentaires {@code #} (n'importe où) → COMMENT</li>
     *   <li>séparateur de document {@code ---} → KEYWORD</li>
     *   <li>{@code &ancre}, {@code *alias}, {@code !tag} → ANNOTATION</li>
     *   <li>chaînes quotées (les deux zones) → STRING</li>
     *   <li>mots de valeur : tête numérique → NUMBER, constantes
     *       (true/false/yes/no/null) → CONSTANT, sinon STRING</li>
     *   <li>scalaires bruts → STRING, flux {@code []}{@code {}} → PUNCT</li>
     * </ul></p>
     */
    public static StyledLine styleYaml(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.YAML, line, entryState);
    }

    private YamlTokenizer() {}
}
