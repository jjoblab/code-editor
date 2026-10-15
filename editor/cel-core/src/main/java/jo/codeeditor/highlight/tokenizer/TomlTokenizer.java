package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur TOML — <b>migré sur le moteur générique</b> (ADR-002,
 * chantier C2, étape 2) : la grammaire (commentaires {@code #} partout,
 * en-têtes {@code [table]}/{@code [[table]]}, paires {@code clé = valeur},
 * chaînes basiques et littérales, nombres/dates, booléens) vit désormais
 * dans le template déclaratif {@link TokenizerTemplate#TOML}, exécuté par
 * {@link GenericTokenizer}. Le dispatcher ({@code SyntaxHighlighter})
 * continue d'appeler cette entrée — signature inchangée, comportement
 * repris octet pour octet de l'implémentation historique écrite à la
 * main (verrouillé par {@code TomlTokenizerTest}).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites {@code SyntaxHighlighterTest} et
 * {@code TomlTokenizerTest} restent vertes sans modification de leurs
 * assertions.</p>
 */
public final class TomlTokenizer {

    /**
     * Tokénise une ligne TOML — délégation au moteur générique avec le
     * template {@link TokenizerTemplate#TOML}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>commentaires {@code #} (y compris après la valeur) → COMMENT</li>
     *   <li>en-têtes {@code [section]} et {@code [[tableau-de-tableaux]]} → TYPE</li>
     *   <li>{@code clé = valeur} — clé en PROPERTY, valeur tokénisée
     *       (chaînes STRING, nombres/dates NUMBER, booléens CONSTANT)</li>
     *   <li>séquences d'échappement dans les chaînes → ESCAPE</li>
     * </ul></p>
     */
    public static StyledLine styleToml(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.TOML, line, entryState);
    }

    private TomlTokenizer() {}
}
