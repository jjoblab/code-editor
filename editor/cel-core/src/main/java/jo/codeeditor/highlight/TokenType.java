package jo.codeeditor.highlight;

/**
 * Token types for syntax highlighting.
 *
 * <p>v3.1.0: expanded from 9 to 16 types, matching CodeAssist's SyntaxColors
 * coverage. The new types allow finer-grained coloring:
 * <ul>
 *   <li>{@link #OPERATOR} — +, -, *, /, =, !, ?, :, etc. (was PUNCT)</li>
 *   <li>{@link #ESCAPE} — escape sequences inside strings (n, t, u-XXXX)</li>
 *   <li>{@link #LABEL} — goto labels, case labels</li>
 *   <li>{@link #PROPERTY} — object properties (foo.bar — the "bar" part)</li>
 *   <li>{@link #VARIABLE} — variable identifiers (distinct from keywords/types)</li>
 *   <li>{@link #CONSTANT} — ALL_CAPS constants, enum values</li>
 *   <li>{@link #REGEXP} — regular expression literals (future use)</li>
 * </ul>
 *
 * @since v1.0.0 (expanded v3.1.0)
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
    // ── v3.1.0 additions ──────────────────────────────────────
    /** Operators: +, -, *, /, =, !, ?, :, <, >, &, |, ^, ~. */
    OPERATOR,
    /** Escape sequences inside strings: n, t, u-XXXX, etc. */
    ESCAPE,
    /** Goto labels, case labels. */
    LABEL,
    /** Object properties after a dot: foo.{bar} — the "bar" part. */
    PROPERTY,
    /** Variable identifiers (distinct from keywords/types). */
    VARIABLE,
    /** ALL_CAPS constants, enum values. */
    CONSTANT,
    /** Regular expression literals. */
    REGEXP,
    // ── v0.1.0.49-v2.20 additions (console/log coloration) ─────
    // Utilisés par le styleur « log » (SyntaxHighlighter.styleLog) pour la
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
