package jo.codeeditor.languages;

/**
 * v3.36.0 — Lexical family of a language (roadmap item 7, port of
 * CodeAssist v3.20's {@code SyntaxFamily}).
 *
 * <p>The family decides which built-in tokenizer
 * {@code SyntaxHighlighter.styleLine} routes to: every language of the
 * XML family shares the XML tokenizer, every shell alias shares the shell
 * tokenizer, and so on. Before v3.36.0 this routing was a chain of
 * string comparisons listing every alias explicitly ({@code "yaml".equals(l)
 * || "yml".equals(l)}); with the registry the alias table lives in
 * {@link LanguageProfile} and the dispatch is a single enum switch.</p>
 *
 * <p>Consequence for aliases: short ids that previously fell through the
 * string chains to the generic C tokenizer ({@code "py"}, {@code "md"},
 * {@code "svg"}, {@code "htm"}, {@code "ini"}) now route to their proper
 * tokenizer — a fix, not a regression.</p>
 *
 * @since v3.36.0
 */
public enum SyntaxFamily {
    /** Java, Kotlin, C/C++, Go, Rust, PHP, Swift, Dart, Groovy, JS/TS, Scala. */
    C_LIKE,
    /** Python (and its {@code py} alias). */
    PYTHON,
    /** Lua. */
    LUA,
    /** XML, HTML, HTM, SVG. */
    XML,
    /** JSON. */
    JSON,
    /** CSS, SCSS, LESS. */
    CSS,
    /** Shell, Bash, sh. */
    SHELL,
    /** YAML, yml. */
    YAML,
    /** SQL. */
    SQL,
    /** .properties / .ini key-value files. */
    PROPERTIES,
    /** TOML. */
    TOML,
    /** Smali (Android dex bytecode). */
    SMALI,
    /** Markdown (and its {@code md} alias). */
    MARKDOWN,
    /** Log files (timestamps + levels). */
    LOG;
}
