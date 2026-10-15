package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.StyledLine;

/**
 * Tokéniseur Python — <b>migré sur le moteur générique</b> (ADR-002,
 * chantier C2, étape 9) : la grammaire (commentaires {@code #}, chaînes
 * à triple guillemet — seuls les GUILLEMETS, la garde historique
 * {@code '''} reste —, chaînes à préfixe f/r/b, décorateurs bornés au
 * mot avec chemins pointés, nombres hexadécimaux/imaginaires, casse
 * évaluée avant l'appel) vit désormais dans le template déclaratif
 * {@link TokenizerTemplate#PYTHON}, exécuté par
 * {@link GenericTokenizer} en mode flux libre. Le dispatcher
 * ({@code SyntaxHighlighter}) continue d'appeler cette entrée —
 * signature inchangée, comportement repris octet pour octet de
 * l'implémentation historique écrite à la main (verrouillé par
 * {@code PythonTokenizerTest}).
 *
 * <p>Historique : tokéniseur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — les suites existantes restent vertes sans
 * modification de leurs assertions.</p>
 */
public final class PythonTokenizer {

    /**
     * Tokénise une ligne Python — délégation au moteur générique avec
     * le template {@link TokenizerTemplate#PYTHON}.
     *
     * <p>Reconnaît (comportement historique inchangé) :
     * <ul>
     *   <li>commentaires {@code #}</li>
     *   <li>chaînes à triple guillemet {@code """…"""} (état
     *       inter-lignes)</li>
     *   <li>chaînes à préfixe {@code f"…"}, {@code r'…'}, {@code b"…"} —
     *       le préfixe fait partie du jeton</li>
     *   <li>chaînes quotées à échappements apparents</li>
     *   <li>décorateurs {@code @app.route} (bornés au mot, points
     *       inclus)</li>
     *   <li>mots-clés, types intégrés, CONSTANT majuscules, majuscule
     *       initiale AVANT la parenthèse d'appel</li>
     *   <li>nombres hexadécimaux, imaginaires ({@code 3j}), séparés par
     *       soulignés ({@code 1_000})</li>
     * </ul></p>
     */
    public static StyledLine stylePython(String line, int entryState) {
        return GenericTokenizer.styleLine(TokenizerTemplate.PYTHON, line, entryState);
    }

    private PythonTokenizer() {}
}
