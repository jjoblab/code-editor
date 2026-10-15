package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur .properties (et .conf) — <b>migré sur le moteur
 * générique</b> (ADR-002, chantier C2, étape 1) : la grammaire
 * (commentaires {@code #}/{@code !}, séparateurs {@code =}/{@code :},
 * clé en PROPERTY, valeur en STRING, échappements) vit désormais
 * dans le template déclaratif {@link TokenizerTemplate#PROPERTIES},
 * exécuté par {@link GenericTokenizer}. Le dispatcher
 * ({@code SyntaxHighlighter}) continue d'appeler cette entrée —
 * signature inchangée, comportement repris octet pour octet.
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2)
 * remplace le corps par la table — la suite
 * {@code SyntaxHighlighterTest} reste verte sans modification de ses
 * assertions.</p>
 */
public final class PropertiesTokenizer {

    /**
     * Tokénise une ligne de fichier Java Properties — délégation au
     * moteur générique avec le template
     * {@link TokenizerTemplate#PROPERTIES}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>commentaires {@code #} et {@code !} → COMMENT</li>
     *   <li>{@code clé=valeur} ou {@code clé:valeur} — clé en PROPERTY,
     *       valeur en STRING</li>
     *   <li>séquences d'échappement (backslash-n, backslash-t, backslash-uXXXX) → ESCAPE</li>
     * </ul></p>
     */
    public static StyledLine styleProperties(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.PROPERTIES, line, entryState);
    }

    private PropertiesTokenizer() {}
}
