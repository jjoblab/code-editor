package jo.codeeditor.languages;

import jo.codeeditor.edit.CommentSyntax;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Table des langages intégrés.
 *
 * <p>Chaque ensemble de mots-clés ci-dessous est repris <b>verbatim</b>
 * de {@code SyntaxHighlighter} afin de préserver au byte près le
 * comportement de coloration de chaque id connu. Ce qui change ici est
 * le <i>routage</i> : ids, alias, extensions, familles et syntaxes de
 * commentaire vivent dans une table déclarative unique consommée par
 * {@link LanguageRegistry}, au lieu d'être dispersés dans les chaînes
 * de caractères du highlighter et le switch de
 * {@code CommentSyntax.forLanguage}.</p>
 *
 * <p>Routage des alias garanti par la table : {@code py} → tokenizer
 * Python, {@code md} → tokenizer Markdown, {@code htm}/{@code svg} →
 * tokenizer XML, {@code ini} → tokenizer properties, {@code kt} →
 * mots-clés Kotlin, {@code rs} → mots-clés Rust.</p>
 */
final class BuiltinLanguages {

    private BuiltinLanguages() {}

    // ═════════════════════════════════════════════════════════════
    // Ensembles de mots-clés — repris verbatim de SyntaxHighlighter.
    // ═════════════════════════════════════════════════════════════

    static final Set<String> JAVA_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "var", "yield", "record",
        "sealed", "permits", "non-sealed"
    )));

    static final Set<String> KT_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "as", "break", "class", "continue", "do", "else", "false", "for",
        "fun", "if", "in", "interface", "is", "null", "object", "package",
        "return", "super", "this", "throw", "true", "try", "typealias",
        "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
        "delegate", "dynamic", "field", "file", "finally", "get", "import",
        "init", "param", "property", "receiver", "set", "setparam", "where",
        "abstract", "actual", "annotation", "companion", "const", "crossinline",
        "data", "enum", "expect", "external", "final", "infix", "inline",
        "inner", "internal", "lateinit", "noinline", "open", "operator", "out",
        "override", "private", "protected", "public", "reified", "sealed",
        "suspend", "tailrec", "vararg"
    )));

    static final Set<String> XML_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "xmlns", "android", "app", "tools", "schema", "layout"
    )));

    static final Set<String> JSON_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "true", "false", "null"
    )));

    static final Set<String> PYTHON_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "False", "None", "True", "and", "as", "assert", "async", "await",
        "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
        "while", "with", "yield", "self", "cls"
    )));

    static final Set<String> JS_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "finally",
        "for", "function", "if", "import", "in", "instanceof", "let", "new",
        "of", "return", "super", "switch", "this", "throw", "try", "typeof",
        "var", "void", "while", "with", "yield", "async", "await", "static",
        "get", "set", "public", "private", "protected", "readonly", "abstract",
        "as", "interface", "enum", "type", "namespace", "module", "declare",
        "from", "undefined", "null", "true", "false", "NaN", "Infinity"
    )));

    static final Set<String> LUA_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "goto", "if", "in", "local", "nil", "not", "or",
        "repeat", "return", "then", "true", "until", "while", "continue"
    )));

    static final Set<String> C_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "auto", "break", "case", "char", "const", "continue", "default",
        "do", "double", "else", "enum", "extern", "float", "for", "goto",
        "if", "inline", "int", "long", "register", "restrict", "return",
        "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
        "union", "unsigned", "void", "volatile", "while", "_Bool", "_Complex",
        "_Imaginary", "_Atomic", "_Alignas", "_Alignof", "_Noreturn",
        "_Static_assert", "_Thread_local", "_Generic"
    )));

    static final Set<String> CPP_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand",
        "bitor", "bool", "break", "case", "catch", "char", "char8_t",
        "char16_t", "char32_t", "class", "compl", "concept", "const",
        "consteval", "constexpr", "constinit", "const_cast", "continue",
        "co_await", "co_return", "co_yield", "decltype", "default", "delete",
        "do", "double", "dynamic_cast", "else", "enum", "explicit", "export",
        "extern", "false", "float", "for", "friend", "goto", "if", "inline",
        "int", "long", "mutable", "namespace", "new", "noexcept", "nullptr",
        "operator", "or", "or_eq", "private", "protected", "public",
        "register", "reinterpret_cast", "requires", "return", "short",
        "signed", "sizeof", "static", "static_assert", "static_cast",
        "struct", "switch", "template", "this", "thread_local", "throw",
        "true", "try", "typedef", "typeid", "typename", "union", "unsigned",
        "using", "virtual", "void", "volatile", "wchar_t", "while", "xor",
        "xor_eq", "final", "override"
    )));

    static final Set<String> GO_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "break", "case", "chan", "const", "continue", "default", "defer",
        "else", "fallthrough", "for", "func", "go", "goto", "if", "import",
        "interface", "map", "package", "range", "return", "select", "struct",
        "switch", "type", "var", "nil", "true", "false", "iota"
    )));

    static final Set<String> RUST_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "as", "async", "await", "break", "const", "continue", "crate",
        "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl",
        "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref",
        "return", "self", "Self", "static", "struct", "super", "trait",
        "true", "try", "type", "unsafe", "use", "where", "while"
    )));

    static final Set<String> RUBY_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "BEGIN", "END", "alias", "and", "begin", "break", "case", "class",
        "def", "defined?", "do", "else", "elsif", "end", "ensure", "false",
        "for", "if", "in", "module", "next", "nil", "not", "or", "redo",
        "rescue", "retry", "return", "self", "super", "then", "true",
        "undef", "unless", "until", "when", "while", "yield", "__FILE__",
        "__LINE__", "__ENCODING__"
    )));

    static final Set<String> PHP_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "abstract", "and", "array", "as", "break", "callable", "case",
        "catch", "class", "clone", "const", "continue", "declare", "default",
        "die", "do", "echo", "else", "elseif", "empty", "enddeclare",
        "endfor", "endforeach", "endif", "endswitch", "endwhile", "enum",
        "eval", "exit", "extends", "final", "finally", "fn", "for", "foreach",
        "function", "global", "goto", "if", "implements", "include",
        "include_once", "instanceof", "insteadof", "interface", "isset",
        "list", "match", "namespace", "new", "or", "print", "private",
        "protected", "public", "readonly", "require", "require_once",
        "return", "static", "switch", "throw", "trait", "try", "unset",
        "use", "var", "while", "xor", "yield", "true", "false", "null",
        "int", "float", "bool", "string", "object", "mixed", "void",
        "never", "array", "iterable", "callable", "self", "parent", "static"
    )));

    static final Set<String> SWIFT_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate",
        "func", "import", "init", "inout", "internal", "let", "open",
        "operator", "private", "protocol", "public", "static", "struct",
        "subscript", "typealias", "var", "break", "case", "continue",
        "default", "defer", "do", "else", "fallthrough", "for", "guard",
        "if", "in", "repeat", "return", "switch", "where", "while", "as",
        "Any", "catch", "false", "is", "nil", "rethrows", "super", "self",
        "Self", "throw", "throws", "true", "try", "async", "await", "yield",
        "actor", "unsafe"
    )));

    static final Set<String> DART_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "abstract", "as", "assert", "async", "await", "break", "case", "catch",
        "class", "const", "continue", "covariant", "default", "deferred",
        "do", "dynamic", "else", "enum", "export", "extends", "extension",
        "external", "factory", "false", "final", "finally", "for", "Function",
        "get", "hide", "if", "implements", "import", "in", "interface", "is",
        "library", "mixin", "new", "null", "on", "operator", "part", "rethrow",
        "return", "set", "show", "static", "super", "switch", "sync", "this",
        "throw", "true", "try", "typedef", "var", "void", "while", "with",
        "yield"
    )));

    static final Set<String> GROOVY_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "abstract", "as", "assert", "break", "case", "catch", "class",
        "const", "continue", "def", "default", "do", "else", "enum",
        "extends", "false", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "in", "instanceof", "int", "interface",
        "long", "native", "new", "null", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch",
        "synchronized", "this", "throw", "throws", "trait", "transient",
        "true", "try", "void", "volatile", "while", "it", "Closure"
    )));

    static final Set<String> SQL_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "ABORT", "ACTION", "ADD", "AFTER", "ALL", "ALTER", "ANALYZE", "AND",
        "AS", "ASC", "ATTACH", "AUTOINCREMENT", "BEFORE", "BEGIN", "BETWEEN",
        "BY", "CASCADE", "CASE", "CAST", "CHECK", "COLLATE", "COMMIT",
        "CONFLICT", "CONSTRAINT", "CREATE", "CROSS", "CURRENT_DATE",
        "CURRENT_TIME", "CURRENT_TIMESTAMP", "DATABASE", "DEFAULT", "DEFERRABLE",
        "DEFERRED", "DELETE", "DETACH", "DISTINCT", "DROP", "EACH", "ELSE",
        "END", "ESCAPE", "EXCEPT", "EXCLUSIVE", "EXISTS", "EXPLAIN", "FOR",
        "FOREIGN", "FROM", "FULL", "GLOB", "GROUP", "HAVING", "IF", "IGNORE",
        "IMMEDIATE", "IN", "INDEX", "INNER", "INSERT", "INSTEAD", "INTERSECT",
        "INTO", "IS", "ISNULL", "JOIN", "KEY", "LEFT", "LIKE", "LIMIT",
        "MATCH", "NATURAL", "NO", "NOT", "NOTNULL", "NULL", "OF", "OFFSET",
        "ON", "OR", "ORDER", "OUTER", "PLAN", "PRAGMA", "PRIMARY", "QUERY",
        "REFERENCES", "REGEXP", "REINDEX", "RELEASE", "RENAME", "REPLACE",
        "RESTRICT", "RIGHT", "ROLLBACK", "ROW", "SAVEPOINT", "SELECT", "SET",
        "TABLE", "TEMP", "TEMPORARY", "THEN", "TO", "TRANSACTION", "TRIGGER",
        "UNION", "UNIQUE", "UPDATE", "USING", "VACUUM", "VALUES", "VIEW",
        "VIRTUAL", "WHEN", "WHERE", "WITH", "WITHOUT",
        // Variantes minuscules (certaines requêtes mélangent)
        "abort", "action", "add", "after", "all", "alter", "analyze", "and",
        "as", "asc", "attach", "autoincrement", "before", "begin", "between",
        "by", "cascade", "case", "cast", "check", "collate", "commit",
        "conflict", "constraint", "create", "cross", "database", "default",
        "deferrable", "deferred", "delete", "detach", "distinct", "drop",
        "each", "else", "end", "escape", "except", "exclusive", "exists",
        "explain", "for", "foreign", "from", "full", "glob", "group",
        "having", "if", "ignore", "immediate", "in", "index", "inner",
        "insert", "instead", "intersect", "into", "is", "isnull", "join",
        "key", "left", "like", "limit", "match", "natural", "no", "not",
        "notnull", "null", "of", "offset", "on", "or", "order", "outer",
        "plan", "pragma", "primary", "query", "references", "regexp",
        "reindex", "release", "rename", "replace", "restrict", "right",
        "rollback", "row", "savepoint", "select", "set", "table", "temp",
        "temporary", "then", "to", "transaction", "trigger", "union",
        "unique", "update", "using", "vacuum", "values", "view", "virtual",
        "when", "where", "with", "without",
        // Types
        "INTEGER", "TEXT", "REAL", "BLOB", "NUMERIC", "BOOLEAN", "VARCHAR",
        "CHAR", "DATE", "DATETIME", "TIMESTAMP", "DECIMAL", "FLOAT", "DOUBLE",
        "INT", "BIGINT", "SMALLINT", "TINYINT"
    )));

    static final Set<String> SHELL_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "while",
        "until", "do", "done", "in", "function", "select", "time", "coproc",
        "export", "readonly", "local", "declare", "typeset", "unset",
        "shift", "source", "return", "exit", "trap", "set", "unset", "alias",
        "unalias", "echo", "printf", "read", "test", "true", "false",
        "cd", "pwd", "pushd", "popd", "dirs", "bg", "fg", "jobs", "kill",
        "wait", "nohup", "exec", "eval", "let", "break", "continue",
        "getopts", "hash", "history", "suspend", "ulimit", "umask"
    )));

    static final Set<String> SMALI_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        ".class", ".super", ".source", ".implements", ".field", ".method",
        ".end method", ".registers", ".locals", ".prologue", ".line", ".param",
        ".parameter", ".annotation", ".end annotation", ".enum",
        ".array-data", ".end array-data", ".packed-switch", ".end packed-switch",
        ".sparse-switch", ".end sparse-switch", ".subannotation",
        ".catch", ".catchall", ".annotation"
    )));

    static final Set<String> TOML_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "true", "false", "inf", "nan"
    )));

    static final Set<String> PROPERTIES_KEYWORDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "true", "false"
    )));

    // ═════════════════════════════════════════════════════════════
    // Raccourcis de syntaxes de commentaire (mêmes instances que le
    // switch forLanguage de CommentSyntax).
    // ═════════════════════════════════════════════════════════════

    private static final CommentSyntax HASH =
            new CommentSyntax("#", null, null);
    private static final CommentSyntax XML_BLOCK =
            new CommentSyntax(null, "<!--", "-->");
    private static final CommentSyntax LUA_STYLE =
            new CommentSyntax("--", "--[[", "]]");
    private static final CommentSyntax SQL_STYLE =
            new CommentSyntax("--", "/*", "*/");

    // ═════════════════════════════════════════════════════════════
    // Table des profils.
    // ═════════════════════════════════════════════════════════════

    static void install() {
        // ── Tokenizers spécialisés ────────────────────────────
        LanguageRegistry.put(LanguageProfile.builder("python")
                .family(SyntaxFamily.PYTHON).alias("py")
                .extension("py").keywordSet(PYTHON_KEYWORDS)
                .commentSyntax(HASH).build());
        LanguageRegistry.put(LanguageProfile.builder("markdown")
                .family(SyntaxFamily.MARKDOWN).alias("md")
                .extension("md", "markdown")
                .commentSyntax(XML_BLOCK).build());
        LanguageRegistry.put(LanguageProfile.builder("json")
                .family(SyntaxFamily.JSON).extension("json")
                .keywordSet(JSON_KEYWORDS)
                .commentSyntax(CommentSyntax.NONE).build());
        LanguageRegistry.put(LanguageProfile.builder("lua")
                .family(SyntaxFamily.LUA).extension("lua")
                .keywordSet(LUA_KEYWORDS)
                .commentSyntax(LUA_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("xml")
                .family(SyntaxFamily.XML).alias("html", "htm", "svg")
                .extension("xml", "html", "htm", "svg")
                .keywordSet(XML_KEYWORDS)
                .commentSyntax(XML_BLOCK).build());
        LanguageRegistry.put(LanguageProfile.builder("css")
                .family(SyntaxFamily.CSS).alias("scss", "less")
                .extension("css", "scss", "less")
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("shell")
                .family(SyntaxFamily.SHELL).alias("bash", "sh")
                .extension("sh", "bash")
                .keywordSet(SHELL_KEYWORDS)
                .commentSyntax(HASH).build());
        LanguageRegistry.put(LanguageProfile.builder("yaml")
                .family(SyntaxFamily.YAML).alias("yml")
                .extension("yaml", "yml")
                .commentSyntax(HASH).build());
        LanguageRegistry.put(LanguageProfile.builder("sql")
                .family(SyntaxFamily.SQL).extension("sql")
                .keywordSet(SQL_KEYWORDS)
                .commentSyntax(SQL_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("properties")
                .family(SyntaxFamily.PROPERTIES).alias("ini")
                .extension("properties", "props", "ini")
                .keywordSet(PROPERTIES_KEYWORDS)
                .commentSyntax(HASH).build());
        LanguageRegistry.put(LanguageProfile.builder("toml")
                .family(SyntaxFamily.TOML).extension("toml")
                .keywordSet(TOML_KEYWORDS)
                .commentSyntax(HASH).build());
        LanguageRegistry.put(LanguageProfile.builder("smali")
                .family(SyntaxFamily.SMALI).extension("smali")
                .keywordSet(SMALI_KEYWORDS)
                .commentSyntax(HASH).build());
        LanguageRegistry.put(LanguageProfile.builder("log")
                .family(SyntaxFamily.LOG).extension("log")
                .commentSyntax(CommentSyntax.C_STYLE).build());

        // ── Famille C-like ─────────────────────────────────────
        LanguageRegistry.put(LanguageProfile.builder("java")
                .family(SyntaxFamily.C_LIKE).extension("java")
                .keywordSet(JAVA_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("kotlin")
                .family(SyntaxFamily.C_LIKE).alias("kt")
                .extension("kt", "kts").keywordSet(KT_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("javascript")
                .family(SyntaxFamily.C_LIKE).alias("js")
                .extension("js", "mjs", "jsx").keywordSet(JS_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("typescript")
                .family(SyntaxFamily.C_LIKE).alias("ts")
                .extension("ts", "tsx").keywordSet(JS_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("c")
                .family(SyntaxFamily.C_LIKE).alias("h")
                .extension("c", "h").keywordSet(C_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("cpp")
                .family(SyntaxFamily.C_LIKE).alias("cc", "hpp", "cxx")
                .extension("cpp", "cc", "hpp", "cxx", "c++")
                .keywordSet(CPP_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("go")
                .family(SyntaxFamily.C_LIKE).extension("go")
                .keywordSet(GO_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("rust")
                .family(SyntaxFamily.C_LIKE).alias("rs")
                .extension("rs").keywordSet(RUST_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("ruby")
                .family(SyntaxFamily.C_LIKE).alias("rb")
                .extension("rb").keywordSet(RUBY_KEYWORDS)
                .commentSyntax(HASH).build());
        LanguageRegistry.put(LanguageProfile.builder("php")
                .family(SyntaxFamily.C_LIKE).extension("php")
                .keywordSet(PHP_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("swift")
                .family(SyntaxFamily.C_LIKE).extension("swift")
                .keywordSet(SWIFT_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("dart")
                .family(SyntaxFamily.C_LIKE).extension("dart")
                .keywordSet(DART_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        LanguageRegistry.put(LanguageProfile.builder("groovy")
                .family(SyntaxFamily.C_LIKE).alias("gradle")
                .extension("groovy", "gradle").keywordSet(GROOVY_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
        // Scala : retombe sur l’ensemble de mots-clés Java, le profil
        // réutilise donc JAVA_KEYWORDS.
        LanguageRegistry.put(LanguageProfile.builder("scala")
                .family(SyntaxFamily.C_LIKE).extension("scala")
                .keywordSet(JAVA_KEYWORDS)
                .commentSyntax(CommentSyntax.C_STYLE).build());
    }
}
