package jo.codeeditor.highlight;

/**
 * Types de jetons pour la coloration syntaxique.
 *
 * <p>L'ensemble couvre la palette de SyntaxColors de CodeAssist et permet
 * une coloration plus fine :
 * <ul>
 *   <li>{@link #OPERATOR} — +, -, *, /, =, !, ?, :, etc.</li>
 *   <li>{@link #ESCAPE} — séquences d'échappement dans les chaînes (n, t, u-XXXX)</li>
 *   <li>{@link #LABEL} — étiquettes goto, étiquettes case</li>
 *   <li>{@link #PROPERTY} — propriétés d'objet (foo.bar — la partie « bar »)</li>
 *   <li>{@link #VARIABLE} — identifiants de variables (distincts des mots-clés/types)</li>
 *   <li>{@link #CONSTANT} — constantes ALL_CAPS, valeurs d'enum</li>
 *   <li>{@link #REGEXP} — littéraux d'expressions régulières (usage futur)</li>
 * </ul>
 */
public enum TokenType {
    KEYWORD,
    STRING,
    COMMENT,
    NUMBER,
    ANNOTATION,
    FUNC,
    TYPE,
    PUNCT,
    PLAIN,
    // ── Types additionnels ──────────────────────────────────────
    /** Opérateurs : +, -, *, /, =, !, ?, :, <, >, &, |, ^, ~. */
    OPERATOR,
    /** Séquences d'échappement dans les chaînes : n, t, u-XXXX, etc. */
    ESCAPE,
    /** Étiquettes goto, étiquettes case. */
    LABEL,
    /** Propriétés d'objet après un point : foo.{bar} — la partie « bar ». */
    PROPERTY,
    /** Identifiants de variables (distincts des mots-clés/types). */
    VARIABLE,
    /** Constantes ALL_CAPS, valeurs d'enum. */
    CONSTANT,
    /** Littéraux d'expressions régulières. */
    REGEXP,
    // ── Coloration console/log ─────
    // Utilisés par le styleur « log » (LogTokenizer.styleLog) pour la
    // coloration sémantique des consoles Gradle/JVM embarquées dans l'app
    // CodeIDE (ConsoleLogView). Chaque thème EditorTheme mappe ces types
    // via colorForToken() — cf. champs error/warning/info + logSuccess.
    /** Ligne d'erreur de log (ERROR:, BUILD FAILED, SEVERE, exception…). */
    ERROR,
    /** Ligne d'avertissement de log (WARNING:, …). */
    WARNING,
    /** Ligne d'information structurée ([Tooling], [Sync], [JVM]…). */
    INFO,
    /** Ligne de succès (BUILD SUCCESSFUL, synchronisation terminée…). */
    SUCCESS
}
