package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.StyledLine;

/**
 * Styleur de lignes de log — <b>migré sur le moteur générique</b>
 * (ADR-002, chantier C2, étape 12) : la grammaire (règles
 * contient/commence/finit, ordre d'évaluation ERROR → WARNING →
 * SUCCESS → TYPE → INFO, chevron sensible à la casse, repli PLAIN sans
 * span) vit désormais dans le template déclaratif
 * {@link TokenizerTemplate#LOG}, exécuté par {@link GenericTokenizer}
 * en <b>mode classifieur de ligne</b> — la troisième saveur du moteur
 * (aucune tokénisation par caractère : la ligne entière reçoit le type
 * de la première règle qui matche, ou AUCUNE span pour le PLAIN).
 *
 * <p>Consommé par {@code ConsoleLogView} côté app CodeIDE (console de
 * build, onglet Diagnostic JVM). Toutes les couleurs proviennent du
 * thème natif via {@code EditorTheme.colorForToken(TokenType)} — aucune
 * couleur custom. L'état d'entrée est ignoré : un log n'a pas d'états
 * lexicaux multi-lignes, l'exit state est toujours NORMAL.</p>
 *
 * <p>Historique : styleur écrit à la main extrait de
 * SyntaxHighlighter (v3.4x) ; le cliquet de démantèlement (C2) remplace
 * le corps par la table — la suite de caractérisation
 * {@code LogTokenizerTest} reste verte sans modification de ses
 * assertions.</p>
 */
public final class LogTokenizer {

    /**
     * Style une ligne de log — délégation au moteur générique avec le
     * template {@link TokenizerTemplate#LOG}.
     *
     * <p>Classification (ordre historique inchangé) :
     * <ul>
     *   <li>{@link jo.codeeditor.highlight.TokenType#ERROR} — « error »,
     *       « failed », « exception », préfixes « e: »/« erreur »,
     *       « échec »…</li>
     *   <li>{@link jo.codeeditor.highlight.TokenType#WARNING} —
     *       « warning:… », préfixe « w: », [warn]…</li>
     *   <li>{@link jo.codeeditor.highlight.TokenType#SUCCESS} — « BUILD
     *       SUCCESSFUL », statuts «  UP-TO-DATE »…, « prêt »</li>
     *   <li>{@link jo.codeeditor.highlight.TokenType#TYPE} — en-têtes
     *       « &gt; … » (sensibles à la casse)</li>
     *   <li>{@link jo.codeeditor.highlight.TokenType#INFO} —
     *       [Tooling]/[Sync]/[JVM]…</li>
     *   <li>PLAIN — aucune span (chemin rapide, rendu texte brut)</li>
     * </ul></p>
     */
    public static StyledLine styleLog(String line) {
        return GenericTokenizer.styleLine(TokenizerTemplate.LOG, line,
                LexState.NORMAL);
    }

    private LogTokenizer() {}
}
