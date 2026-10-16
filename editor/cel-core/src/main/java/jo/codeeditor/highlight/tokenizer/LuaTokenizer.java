package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur Lua — <b>migré sur le moteur générique</b> (ADR-002,
 * chantier C2, étape 7) : la grammaire (commentaires {@code --} et
 * {@code --[[ ]]}, chaînes longues {@code [[ ]]} réutilisant l'état
 * inter-lignes du commentaire de bloc, chaînes quotées à échappements
 * apparents, nombres hexadécimaux, mots-clés et builtins, CONSTANT
 * majuscules, appels de fonction) vit désormais dans le template
 * déclaratif {@link TokenizerTemplate#LUA}, exécuté par
 * {@link GenericTokenizer} en mode flux libre. Le dispatcher
 * ({@code SyntaxHighlighter}) continue d'appeler cette entrée —
 * signature inchangée, comportement repris octet pour octet de
 * l'implémentation historique écrite à la main (verrouillé par
 * {@code LuaTokenizerTest}, y compris la continuation COMMENT des
 * chaînes longues ouvertes — garde historique).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites existantes restent vertes sans
 * modification de leurs assertions.</p>
 */
public final class LuaTokenizer {

    /**
     * Tokénise une ligne Lua — délégation au moteur générique avec le
     * template {@link TokenizerTemplate#LUA}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>commentaires de ligne {@code --} et de bloc
     *       {@code --[[ }]]} (état inter-lignes)</li>
     *   <li>chaînes longues {@code [[ ... ]]} — non fermées, elles
     *       portent le même état que le commentaire de bloc et leurs
     *       lignes de continuation sont colorées COMMENT (garde
     *       historique)</li>
     *   <li>chaînes quotées avec échappements apparents, orphelines
     *       sans état inter-lignes</li>
     *   <li>mots-clés, builtins (TYPE), CONSTANT majuscules, appels
     *       ({@code foo(})</li>
     *   <li>nombres décimaux et hexadécimaux</li>
     * </ul></p>
     */
    public static StyledLine styleLua(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.LUA, line, entryState);
    }

    private LuaTokenizer() {}
}
