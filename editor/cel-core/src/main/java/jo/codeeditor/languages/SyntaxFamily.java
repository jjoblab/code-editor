package jo.codeeditor.languages;

/**
 * Famille lexicale d'un langage (portage du {@code SyntaxFamily}
 * de CodeAssist).
 *
 * <p>La famille décide vers quel tokenizer intégré
 * {@code SyntaxHighlighter.styleLine} route : tout langage de la
 * famille XML partage le tokenizer XML, tout alias shell partage le
 * tokenizer shell, et ainsi de suite. Le routage est un simple switch
 * d'enum : la table d'alias vit dans {@link LanguageProfile}.</p>
 *
 * <p>Conséquence pour les alias : les ids courts ({@code "py"},
 * {@code "md"}, {@code "svg"}, {@code "htm"}, {@code "ini"}) routent
 * vers leur tokenizer propre — un correctif, pas une régression.</p>
 */
public enum SyntaxFamily {
    /** Java, Kotlin, C/C++, Go, Rust, PHP, Swift, Dart, Groovy, JS/TS, Scala. */
    C_LIKE,
    /** Python (et son alias {@code py}). */
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
    /** Fichiers clé-valeur .properties / .ini. */
    PROPERTIES,
    /** TOML. */
    TOML,
    /** Smali (bytecode dex Android). */
    SMALI,
    /** Markdown (et son alias {@code md}). */
    MARKDOWN,
    /** Fichiers de log (horodatages + niveaux). */
    LOG;
}
