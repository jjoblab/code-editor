package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur CSS/SCSS/LESS — <b>migré sur le moteur générique</b>
 * (ADR-002, chantier C2, étape 11) : la grammaire (règles at raffinées
 * par table, variables SCSS, couleurs hexadécimales, nombres à unités,
 * commentaires de bloc et de ligne SCSS, chaînes orphelines à état
 * inter-lignes fermable par l'un des deux guillemets, propriétés en
 * PROPERTY, « & » ouvrant un mot sans le continuer) vit désormais dans
 * le template déclaratif {@link TokenizerTemplate#CSS}, exécuté par
 * {@link GenericTokenizer} en mode flux libre. Le dispatcher
 * ({@code SyntaxHighlighter}) continue d'appeler cette entrée —
 * signature inchangée, comportement repris octet pour octet de
 * l'implémentation historique écrite à la main (verrouillé par
 * {@code CssTokenizerTest}).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites existantes restent vertes sans
 * modification de leurs assertions.</p>
 */
public final class CssTokenizer {

    /**
     * Tokénise une ligne CSS (ou SCSS/LESS) — délégation au moteur
     * générique avec le template {@link TokenizerTemplate#CSS}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>règles at ({@code @media}…) → KEYWORD, inconnues →
     *       VARIABLE (interpolation)</li>
     *   <li>variables SCSS ({@code $primary-color}) → VARIABLE</li>
     *   <li>couleurs hexadécimales ({@code #fff}) → NUMBER</li>
     *   <li>nombres avec unités ({@code 12px}, {@code 100%}) →
     *       NUMBER</li>
     *   <li>commentaires de bloc (état inter-lignes) et de ligne
     *       {@code //} (SCSS)</li>
     *   <li>chaînes à échappements apparents, orphelines à état
     *       CSS_STRING</li>
     *   <li>propriétés ({@code color}…) → PROPERTY, majuscule
     *       initiale → TYPE (sélecteur)</li>
     * </ul></p>
     */
    public static StyledLine styleCss(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.CSS, line, entryState);
    }

    private CssTokenizer() {}
}
