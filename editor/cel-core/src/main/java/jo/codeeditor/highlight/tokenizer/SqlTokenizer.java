package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur SQL — <b>migré sur le moteur générique</b> (ADR-002,
 * chantier C2, étape 8) : la grammaire (commentaires de ligne
 * {@code --} et de bloc {@code /* *&#47;}, chaînes entre apostrophes à
 * échappements apparents, identifiants entre guillemets doubles en
 * PROPERTY — chaîne « simple », échappements avalés —, nombres ouverts
 * par un point, mots-clés du registre, appels, majuscule initiale en
 * CONSTANT) vit désormais dans le template déclaratif
 * {@link TokenizerTemplate#SQL}, exécuté par {@link GenericTokenizer}
 * en mode flux libre. Le dispatcher ({@code SyntaxHighlighter})
 * continue d'appeler cette entrée — signature inchangée, comportement
 * repris octet pour octet de l'implémentation historique écrite à la
 * main (verrouillé par {@code SqlTokenizerTest}).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites existantes restent vertes sans
 * modification de leurs assertions.</p>
 */
public final class SqlTokenizer {

    /**
     * Tokénise une ligne SQL — délégation au moteur générique avec le
     * template {@link TokenizerTemplate#SQL}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>commentaires de ligne {@code --} et de bloc
     *       {@code /* ... *&#47;} (état inter-lignes)</li>
     *   <li>chaînes entre apostrophes ({@code 'littéral'}) → STRING,
     *       échappements apparents</li>
     *   <li>identifiants entre guillemets doubles ({@code "nom"}) →
     *       PROPERTY, un seul jeton</li>
     *   <li>mots-clés (SELECT, FROM, WHERE… majuscules et minuscules)
     *       → KEYWORD</li>
     *   <li>nombres, ouverts par un point ({@code .5}) → NUMBER</li>
     *   <li>appels ({@code COUNT(}) → FUNC, majuscule initiale →
     *       CONSTANT</li>
     * </ul></p>
     */
    public static StyledLine styleSql(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.SQL, line, entryState);
    }

    private SqlTokenizer() {}
}
