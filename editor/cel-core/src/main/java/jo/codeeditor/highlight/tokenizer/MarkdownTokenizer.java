package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur Markdown — <b>migré sur le moteur générique</b> (ADR-002,
 * chantier C2, étape 14) : la grammaire (clôtures de bloc de code à
 * bascule d'état, titres, gras/emphase appariés avec règles de flanc,
 * code en ligne, liens, marqueurs de liste, fusion des runs de texte)
 * vit désormais dans le template déclaratif
 * {@link TokenizerTemplate#MARKDOWN}, exécuté par
 * {@link GenericTokenizer} en <b>mode balisage en ligne</b> — la
 * cinquième saveur du moteur. Le dispatcher
 * ({@code SyntaxHighlighter}) continue d'appeler cette entrée —
 * signature inchangée, comportement repris octet pour octet de
 * l'implémentation historique écrite à la main (verrouillé par
 * {@code MarkdownTokenizerTest}).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites existantes restent vertes sans
 * modification de leurs assertions.</p>
 */
public final class MarkdownTokenizer {

    /**
     * Colorie une ligne Markdown — délégation au moteur générique avec
     * le template {@link TokenizerTemplate#MARKDOWN}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>blocs de code clôturés {@code ```} (bascule inter-lignes du
     *       corps en COMMENT)</li>
     *   <li>titres {@code #}/{@code ##} en tête de ligne</li>
     *   <li>gras {@code **…**} et emphase {@code *…*}/{@code _…_}
     *       (astérisque intra-mot autorisé, souligné refusé) →
     *       EMPHASIS</li>
     *   <li>code en ligne {@code `…`} → STRING</li>
     *   <li>liens {@code [texte](url)} → FUNC</li>
     *   <li>marqueurs de liste {@code -}/{@code *} en tête de ligne →
     *       PUNCT</li>
     *   <li>texte brut avec fusion des runs d'un caractère</li>
     * </ul></p>
     */
    public static StyledLine styleMarkdown(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.MARKDOWN, line,
                entryState);
    }

    private MarkdownTokenizer() {}
}
