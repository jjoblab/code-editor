package jo.codeeditor.highlight;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Incremental per-line syntax tokenizer (parser maison).
 * <p>
 * Performs a single-pass scan per line, producing LineSpans for each token.
 * Supports cross-line states for block comments, XML strings, Kotlin raw
 * strings, CSS comments/strings, Shell single-quotes, and Markdown fenced
 * code blocks.
 *
 * <p><b>v2.46 — Parser maison (built-in, no :tm4e required):</b>
 * The following languages have first-class support out of the box:
 * <ul>
 *   <li><b>C-like</b> (shared state machine in {@link #styleCLike}):
 *       Java, Kotlin, JavaScript, TypeScript, C, C++, Go, Rust, Ruby,
 *       PHP, Swift, Dart, Groovy</li>
 *   <li><b>Specialized</b>: XML/HTML, CSS/SCSS/LESS, Markdown, Python,
 *       JSON, Lua, Shell/Bash, YAML, SQL, Properties, TOML, Smali, log</li>
 * </ul>
 *
 * <p>For richer highlighting (scope inheritance, injections, multi-line
 * constructs), the optional {@code :tm4e} module can be added — it
 * implements {@link TextMateTokenizer} and registers itself via
 * {@link #setTextMateTokenizer(TextMateTokenizer)}. When TextMate reports
 * {@link TextMateTokenizer#isAvailable(String)} for a language, the
 * built-in tokenizer is bypassed for that language.
 *
 * @since v1.0.0 (v2.46: parser maison — 26 languages built-in)
 */
public class SyntaxHighlighter {

    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "var", "yield", "record",
        "sealed", "permits", "non-sealed"
    ));

    private static final Set<String> JAVA_TYPES = new HashSet<>(Arrays.asList(
        "String", "Object", "Integer", "Long", "Double", "Float", "Boolean",
        "Byte", "Short", "Character", "Void", "Number", "Comparable",
        "Iterable", "Iterator", "Collection", "List", "Set", "Map", "Queue",
        "Deque", "ArrayList", "LinkedList", "HashMap", "TreeMap", "HashSet",
        "TreeSet", "Arrays", "Collections", "Stream", "Optional"
    ));

    private static final Set<String> KT_KEYWORDS = new HashSet<>(Arrays.asList(
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
    ));

    private static final Set<String> XML_KEYWORDS = new HashSet<>(Arrays.asList(
        "xmlns", "android", "app", "tools", "schema", "layout"
    ));

    // ── v3.1.0: JSON keywords (true, false, null) ──────────────
    private static final Set<String> JSON_KEYWORDS = new HashSet<>(Arrays.asList(
        "true", "false", "null"
    ));

    // ── v3.1.0: Python keywords ────────────────────────────────
    private static final Set<String> PYTHON_KEYWORDS = new HashSet<>(Arrays.asList(
        "False", "None", "True", "and", "as", "assert", "async", "await",
        "break", "class", "continue", "def", "del", "elif", "else", "except",
        "finally", "for", "from", "global", "if", "import", "in", "is",
        "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try",
        "while", "with", "yield", "self", "cls"
    ));

    // ── v3.1.0: JavaScript / TypeScript keywords ───────────────
    private static final Set<String> JS_KEYWORDS = new HashSet<>(Arrays.asList(
        "break", "case", "catch", "class", "const", "continue", "debugger",
        "default", "delete", "do", "else", "export", "extends", "finally",
        "for", "function", "if", "import", "in", "instanceof", "let", "new",
        "of", "return", "super", "switch", "this", "throw", "try", "typeof",
        "var", "void", "while", "with", "yield", "async", "await", "static",
        "get", "set", "public", "private", "protected", "readonly", "abstract",
        "as", "interface", "enum", "type", "namespace", "module", "declare",
        "from", "undefined", "null", "true", "false", "NaN", "Infinity"
    ));

    // ── v3.1.0: Python built-in types ──────────────────────────
    private static final Set<String> PYTHON_TYPES = new HashSet<>(Arrays.asList(
        "int", "float", "str", "bool", "list", "dict", "set", "tuple",
        "bytes", "bytearray", "complex", "frozenset", "range", "type",
        "object", "Exception", "BaseException", "ValueError", "TypeError",
        "KeyError", "IndexError", "AttributeError", "RuntimeError",
        "StopIteration", "GeneratorExit", "Warning", "DeprecationWarning"
    ));

    // ── v3.1.0: JS/TS built-in types ───────────────────────────
    private static final Set<String> JS_TYPES = new HashSet<>(Arrays.asList(
        "String", "Number", "Boolean", "Array", "Object", "Function",
        "Symbol", "BigInt", "Promise", "Map", "Set", "WeakMap", "WeakSet",
        "Date", "RegExp", "Error", "TypeError", "RangeError", "JSON",
        "Math", "console", "window", "document", "HTMLElement", "Event",
        "Record", "Partial", "Readonly", "Pick", "Omit", "ArrayLike"
    ));

    /** v3.1.0: operators that get their own token type. */
    private static final String OPERATORS = "+-*/%=!<>&|^~?:";

    // ── v3.2.0: Lua keywords ────────────────────────────────────
    private static final Set<String> LUA_KEYWORDS = new HashSet<>(Arrays.asList(
        "and", "break", "do", "else", "elseif", "end", "false", "for",
        "function", "goto", "if", "in", "local", "nil", "not", "or",
        "repeat", "return", "then", "true", "until", "while", "continue"
    ));

    // ── v3.2.0: Lua built-in functions ─────────────────────────
    private static final Set<String> LUA_BUILTINS = new HashSet<>(Arrays.asList(
        "print", "pairs", "ipairs", "type", "tostring", "tonumber",
        "error", "assert", "pcall", "xpcall", "select", "rawget",
        "rawset", "rawequal", "rawlen", "setmetatable", "getmetatable",
        "require", "dofile", "loadfile", "load", "next", "unpack",
        "string", "table", "math", "io", "os", "coroutine"
    ));

    // ═══════════════════════════════════════════════════════════════════
    // v2.46 — PARSER MAISON: keyword sets for additional languages.
    //
    // These were added so that consumers of code-editor can omit the
    // optional :tm4e module and still get decent highlighting for all
    // common languages. The tokenizers for these languages are simpler
    // than full TextMate grammars (no scope inheritance, no injections)
    // but cover the common cases: keywords, types, comments, strings,
    // numbers, annotations, operators.
    // ═══════════════════════════════════════════════════════════════════

    // ── C keywords (C89 + C99 + C11) ────────────────────────────
    private static final Set<String> C_KEYWORDS = new HashSet<>(Arrays.asList(
        "auto", "break", "case", "char", "const", "continue", "default",
        "do", "double", "else", "enum", "extern", "float", "for", "goto",
        "if", "inline", "int", "long", "register", "restrict", "return",
        "short", "signed", "sizeof", "static", "struct", "switch", "typedef",
        "union", "unsigned", "void", "volatile", "while", "_Bool", "_Complex",
        "_Imaginary", "_Atomic", "_Alignas", "_Alignof", "_Noreturn",
        "_Static_assert", "_Thread_local", "_Generic"
    ));

    // ── C++ keywords (C++11 + C++14 + C++17 + C++20) ────────────
    private static final Set<String> CPP_KEYWORDS = new HashSet<>(Arrays.asList(
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
    ));

    // ── C/C++ standard library types (subset, most common) ─────
    private static final Set<String> CPP_TYPES = new HashSet<>(Arrays.asList(
        "std", "string", "vector", "map", "unordered_map", "set", "unordered_set",
        "pair", "tuple", "array", "deque", "list", "forward_list", "stack",
        "queue", "priority_queue", "shared_ptr", "unique_ptr", "weak_ptr",
        "function", "bind", "optional", "variant", "any", "size_t", "ssize_t",
        "int8_t", "int16_t", "int32_t", "int64_t", "uint8_t", "uint16_t",
        "uint32_t", "uint64_t", "FILE", "size_type", "iterator", "const_iterator"
    ));

    // ── Go keywords (Go 1.x) ───────────────────────────────────
    private static final Set<String> GO_KEYWORDS = new HashSet<>(Arrays.asList(
        "break", "case", "chan", "const", "continue", "default", "defer",
        "else", "fallthrough", "for", "func", "go", "goto", "if", "import",
        "interface", "map", "package", "range", "return", "select", "struct",
        "switch", "type", "var", "nil", "true", "false", "iota"
    ));

    // ── Go built-in functions ───────────────────────────────────
    private static final Set<String> GO_BUILTINS = new HashSet<>(Arrays.asList(
        "append", "cap", "close", "complex", "copy", "delete", "imag",
        "len", "make", "new", "panic", "print", "println", "real", "recover",
        "error", "bool", "byte", "rune", "string", "int", "int8", "int16",
        "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64",
        "uintptr", "float32", "float64", "complex64", "complex128"
    ));

    // ── Rust keywords (Rust 2021) ───────────────────────────────
    private static final Set<String> RUST_KEYWORDS = new HashSet<>(Arrays.asList(
        "as", "async", "await", "break", "const", "continue", "crate",
        "dyn", "else", "enum", "extern", "false", "fn", "for", "if", "impl",
        "in", "let", "loop", "match", "mod", "move", "mut", "pub", "ref",
        "return", "self", "Self", "static", "struct", "super", "trait",
        "true", "try", "type", "unsafe", "use", "where", "while"
    ));

    // ── Rust built-in types ─────────────────────────────────────
    private static final Set<String> RUST_TYPES = new HashSet<>(Arrays.asList(
        "i8", "i16", "i32", "i64", "i128", "isize", "u8", "u16", "u32",
        "u64", "u128", "usize", "f32", "f64", "bool", "char", "str",
        "String", "Vec", "Option", "Result", "Box", "Rc", "Arc", "RefCell",
        "Cell", "HashMap", "HashSet", "BTreeMap", "BTreeSet", "VecDeque"
    ));

    // ── Ruby keywords ───────────────────────────────────────────
    private static final Set<String> RUBY_KEYWORDS = new HashSet<>(Arrays.asList(
        "BEGIN", "END", "alias", "and", "begin", "break", "case", "class",
        "def", "defined?", "do", "else", "elsif", "end", "ensure", "false",
        "for", "if", "in", "module", "next", "nil", "not", "or", "redo",
        "rescue", "retry", "return", "self", "super", "then", "true",
        "undef", "unless", "until", "when", "while", "yield", "__FILE__",
        "__LINE__", "__ENCODING__"
    ));

    // ── PHP keywords ───────────────────────────────────────────
    private static final Set<String> PHP_KEYWORDS = new HashSet<>(Arrays.asList(
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
    ));

    // ── Swift keywords ─────────────────────────────────────────
    private static final Set<String> SWIFT_KEYWORDS = new HashSet<>(Arrays.asList(
        "associatedtype", "class", "deinit", "enum", "extension", "fileprivate",
        "func", "import", "init", "inout", "internal", "let", "open",
        "operator", "private", "protocol", "public", "static", "struct",
        "subscript", "typealias", "var", "break", "case", "continue",
        "default", "defer", "do", "else", "fallthrough", "for", "guard",
        "if", "in", "repeat", "return", "switch", "where", "while", "as",
        "Any", "catch", "false", "is", "nil", "rethrows", "super", "self",
        "Self", "throw", "throws", "true", "try", "async", "await", "yield",
        "actor", "unsafe"
    ));

    // ── Dart keywords ───────────────────────────────────────────
    private static final Set<String> DART_KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "as", "assert", "async", "await", "break", "case", "catch",
        "class", "const", "continue", "covariant", "default", "deferred",
        "do", "dynamic", "else", "enum", "export", "extends", "extension",
        "external", "factory", "false", "final", "finally", "for", "Function",
        "get", "hide", "if", "implements", "import", "in", "interface", "is",
        "library", "mixin", "new", "null", "on", "operator", "part", "rethrow",
        "return", "set", "show", "static", "super", "switch", "sync", "this",
        "throw", "true", "try", "typedef", "var", "void", "while", "with",
        "yield"
    ));

    // ── Groovy keywords (Java superset + Groovy-specific) ──────
    private static final Set<String> GROOVY_KEYWORDS = new HashSet<>(Arrays.asList(
        "abstract", "as", "assert", "break", "case", "catch", "class",
        "const", "continue", "def", "default", "do", "else", "enum",
        "extends", "false", "final", "finally", "float", "for", "goto",
        "if", "implements", "import", "in", "instanceof", "int", "interface",
        "long", "native", "new", "null", "package", "private", "protected",
        "public", "return", "short", "static", "strictfp", "super", "switch",
        "synchronized", "this", "throw", "throws", "trait", "transient",
        "true", "try", "void", "volatile", "while", "it", "Closure"
    ));

    // ── SQL keywords (ANSI + common DB extensions) ──────────────
    private static final Set<String> SQL_KEYWORDS = new HashSet<>(Arrays.asList(
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
        // Lowercase variants (some queries mix)
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
    ));

    // ── Shell/Bash keywords & builtins ─────────────────────────
    private static final Set<String> SHELL_KEYWORDS = new HashSet<>(Arrays.asList(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "while",
        "until", "do", "done", "in", "function", "select", "time", "coproc",
        "export", "readonly", "local", "declare", "typeset", "unset",
        "shift", "source", "return", "exit", "trap", "set", "unset", "alias",
        "unalias", "echo", "printf", "read", "test", "true", "false",
        "cd", "pwd", "pushd", "popd", "dirs", "bg", "fg", "jobs", "kill",
        "wait", "nohup", "exec", "eval", "let", "break", "continue",
        "getopts", "hash", "history", "suspend", "ulimit", "umask"
    ));

    // ── CSS at-rules ───────────────────────────────────────────
    private static final Set<String> CSS_AT_RULES = new HashSet<>(Arrays.asList(
        "@media", "@import", "@charset", "@font-face", "@page", "@keyframes",
        "@supports", "@namespace", "@document", "@viewport", "@counter-style",
        "@font-feature-values", "@layer", "@scope", "@container", "@starting-style"
    ));

    // ── CSS properties (subset, most common) ────────────────────
    private static final Set<String> CSS_PROPERTIES = new HashSet<>(Arrays.asList(
        // Box model
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "border", "border-top", "border-right", "border-bottom", "border-left",
        "border-width", "border-style", "border-color", "border-radius",
        "border-collapse", "border-spacing", "border-image",
        // Typography
        "color", "font", "font-family", "font-size", "font-style", "font-weight",
        "font-variant", "font-feature-settings", "line-height", "letter-spacing",
        "word-spacing", "text-align", "text-decoration", "text-transform",
        "text-indent", "text-shadow", "text-overflow", "text-rendering",
        "direction", "unicode-bidi", "white-space", "word-break", "word-wrap",
        "vertical-align",
        // Background
        "background", "background-color", "background-image", "background-repeat",
        "background-position", "background-size", "background-origin",
        "background-clip", "background-attachment",
        // Sizing
        "width", "height", "min-width", "max-width", "min-height", "max-height",
        "box-sizing", "aspect-ratio", "object-fit", "object-position",
        // Layout
        "display", "position", "top", "right", "bottom", "left", "z-index",
        "float", "clear", "overflow", "overflow-x", "overflow-y", "clip",
        "visibility", "opacity", "resize", "outline",
        // Flex
        "flex", "flex-direction", "flex-wrap", "flex-flow", "justify-content",
        "align-items", "align-content", "align-self", "order", "flex-grow",
        "flex-shrink", "flex-basis", "gap", "row-gap", "column-gap",
        // Grid
        "grid", "grid-template-columns", "grid-template-rows", "grid-template-areas",
        "grid-auto-columns", "grid-auto-rows", "grid-auto-flow", "grid-area",
        "grid-column", "grid-row", "grid-column-start", "grid-column-end",
        "grid-row-start", "grid-row-end", "grid-gap",
        // Transitions & animations
        "transition", "transition-property", "transition-duration",
        "transition-timing-function", "transition-delay", "animation",
        "animation-name", "animation-duration", "animation-timing-function",
        "animation-delay", "animation-iteration-count", "animation-direction",
        "animation-fill-mode", "animation-play-state",
        // Transform
        "transform", "transform-origin", "transform-style", "perspective",
        "perspective-origin", "backface-visibility",
        // Misc
        "content", "quotes", "counter-reset", "counter-increment", "cursor",
        "user-select", "pointer-events", "touch-action", "filter", "backdrop-filter",
        "mix-blend-mode", "isolation", "will-change", "list-style",
        "list-style-type", "list-style-position", "list-style-image",
        "table-layout", "caption-side", "empty-cells", "speak", "scroll-behavior"
    ));

    // ── Smali keywords (Android dex bytecode) ──────────────────
    private static final Set<String> SMALI_KEYWORDS = new HashSet<>(Arrays.asList(
        ".class", ".super", ".source", ".implements", ".field", ".method",
        ".end method", ".registers", ".locals", ".prologue", ".line", ".param",
        ".parameter", ".annotation", ".end annotation", ".enum",
        ".array-data", ".end array-data", ".packed-switch", ".end packed-switch",
        ".sparse-switch", ".end sparse-switch", ".subannotation",
        ".catch", ".catchall", ".annotation"
    ));

    // ── Smali register/vim keywords ─────────────────────────────
    private static final Set<String> SMALI_REGISTERS = new HashSet<>(Arrays.asList(
        "p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9",
        "v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9",
        "v10", "v11", "v12", "v13", "v14", "v15"
    ));

    // ── TOML keywords (none, but reserved) ──────────────────────
    private static final Set<String> TOML_KEYWORDS = new HashSet<>(Arrays.asList(
        "true", "false", "inf", "nan"
    ));

    // ── Properties keywords (none, but reserved) ───────────────
    private static final Set<String> PROPERTIES_KEYWORDS = new HashSet<>(Arrays.asList(
        "true", "false"
    ));

    /**
     * Optional TextMate tokenizer (v2.43). If non-null and reports
     * {@link TextMateTokenizer#isAvailable(String)} for a language,
     * {@link #styleLine} delegates to it instead of the built-in switch-case.
     *
     * <p>Set via {@link #setTextMateTokenizer(TextMateTokenizer)} from the
     * {@code :app} module's {@code ProjectActivity} (or similar startup hook).
     * Remains {@code null} in pure-JVM tests so the built-in tokenizer is used.
     */
    private static volatile TextMateTokenizer textMateTokenizer;

    /**
     * v2.44 — Global enable/disable toggle for TextMate tokenization.
     * Allows {@link jo.codeeditor.session.EditorSession} to disable TextMate
     * for very large documents (where the per-line cost × line count would
     * risk an ANR) while still benefiting from TextMate on small files.
     *
     * <p>Default {@code true} — TextMate is opt-in per language via
     * {@link TextMateTokenizer#isAvailable(String)}; this toggle is a
     * finer-grained circuit breaker on top.
     */
    private static volatile boolean textMateEnabled = true;

    /**
     * Registers a TextMate tokenizer. Call this once at app startup, before
     * any editor view is created. Pass {@code null} to disable.
     */
    public static void setTextMateTokenizer(TextMateTokenizer t) {
        textMateTokenizer = t;
    }

    /**
     * v2.44 — Globally enables/disables TextMate delegation. When
     * {@code false}, {@link #styleLine} skips the TextMate path and falls
     * through to the built-in tokenizer for all languages.
     *
     * <p>Use case: {@link jo.codeeditor.session.EditorSession#setLanguage}
     * checks the document line count and disables TextMate if it exceeds
     * a safe threshold (e.g. 1000 lines) to avoid ANR risk from the
     * synchronous {@code restyleAll()}.
     *
     * @since v2.44
     */
    public static void setTextMateEnabled(boolean enabled) {
        textMateEnabled = enabled;
    }

    /**
     * v2.44 — Returns {@code true} if TextMate delegation is currently
     * enabled. Used by tests + future diagnostics.
     *
     * @since v2.44
     */
    public static boolean isTextMateEnabled() {
        return textMateEnabled;
    }

    /**
     * Tokenizes a single line, returning styled spans and the exit state.
     *
     * @param line       the line text (without trailing newline)
     * @param entryState the lexer state entering this line
     * @param language   "java", "kotlin", "xml", or "markdown"
     * @return StyledLine with spans and exit state
     */
    public StyledLine styleLine(String line, int entryState, String language) {
        // v2.43 — TextMate delegation (opt-in per language).
        // v2.44 — guarded by the global textMateEnabled toggle so that
        // EditorSession can disable TextMate for very large documents.
        TextMateTokenizer tm = textMateTokenizer;
        if (textMateEnabled && tm != null && tm.isAvailable(language)) {
            return tm.tokenize(line, entryState, language);
        }
        // v2.46 — Parser maison: language dispatch.
        // Languages with specialized tokenizers (own state machines):
        if ("log".equals(language)) {
            return styleLog(line);
        }
        if ("markdown".equals(language)) {
            return styleMarkdown(line, entryState);
        }
        if ("python".equals(language)) {
            return stylePython(line, entryState);
        }
        if ("json".equals(language)) {
            return styleJson(line, entryState);
        }
        if ("lua".equals(language)) {
            return styleLua(line, entryState);
        }
        if ("xml".equals(language) || "html".equals(language)) {
            // v2.44 — HTML falls through to the XML tokenizer when TextMate
            // is disabled (large files) or unavailable. The XML tokenizer
            // handles HTML tags, attributes, comments, CDATA, and entities.
            return styleXml(line, entryState);
        }
        // v2.46 — New specialized tokenizers (parser maison):
        if ("css".equals(language) || "scss".equals(language) || "less".equals(language)) {
            return styleCss(line, entryState);
        }
        if ("shell".equals(language) || "bash".equals(language) || "sh".equals(language)) {
            return styleShell(line, entryState);
        }
        if ("yaml".equals(language) || "yml".equals(language)) {
            return styleYaml(line, entryState);
        }
        if ("sql".equals(language)) {
            return styleSql(line, entryState);
        }
        if ("properties".equals(language)) {
            return styleProperties(line, entryState);
        }
        if ("toml".equals(language)) {
            return styleToml(line, entryState);
        }
        if ("smali".equals(language)) {
            return styleSmali(line, entryState);
        }
        // C-like languages share the generic Java path but get their own
        // keyword set via getKeywords(). Includes: java, kotlin, javascript,
        // typescript, c, cpp, go, rust, ruby, php, swift, dart, groovy.
        return styleCLike(line, entryState, language);
    }

    /**
     * v2.46 — Generic C-like tokenizer (the "parser maison" core).
     *
     * <p>Handles all C-family languages that share a common lexical
     * structure: line comments (slash-slash), block comments
     * (slash-asterisk ... asterisk-slash), double/single-quoted strings,
     * char literals, annotations (at-sign), numbers (decimal, hex,
     * octal, binary, with suffixes), color literals (hash-RRGGBB),
     * operators, punctuation, and identifier-with-paren function-call
     * detection.
     *
     * <p>Language-specific differences are handled via {@link #getKeywords}
     * (returns the right keyword set per language) and via inline checks
     * on the {@code language} parameter (e.g., Kotlin raw strings).
     *
     * <p>Languages routed here: java, kotlin, javascript, typescript,
     * c, cpp, go, rust, ruby, php, swift, dart, groovy.
     */
    private StyledLine styleCLike(String line, int entryState, String language) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;
        Set<String> keywords = getKeywords(language);

        while (pos < line.length()) {
            // Handle cross-line states
            if (state == LexState.BLOCK_COMMENT) {
                int end = line.indexOf("*/", pos);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                    pos = end + 2;
                    state = LexState.NORMAL;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                }
                continue;
            }

            if (state == LexState.XML_STRING) {
                int end = findXmlStringEnd(line, pos);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                    pos = end + 1;
                    state = LexState.NORMAL;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            if (state == LexState.KT_RAW_STRING) {
                int end = line.indexOf("\"\"\"", pos);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 3, TokenType.STRING));
                    pos = end + 3;
                    state = LexState.NORMAL;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            char ch = line.charAt(pos);

            // Whitespace — skip
            if (Character.isWhitespace(ch)) {
                pos++;
                continue;
            }

            // Line comment
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '/') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Block comment start
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '*') {
                int end = line.indexOf("*/", pos + 2);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT;
                }
                continue;
            }

            // String literal
            if (ch == '"') {
                if ("kotlin".equals(language) && pos + 2 < line.length()
                    && line.charAt(pos + 1) == '"' && line.charAt(pos + 2) == '"') {
                    // Kotlin raw string
                    int end = line.indexOf("\"\"\"", pos + 3);
                    if (end >= 0) {
                        spans.add(new LineSpan(pos, end + 3, TokenType.STRING));
                        pos = end + 3;
                    } else {
                        spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                        pos = line.length();
                        state = LexState.KT_RAW_STRING;
                    }
                } else {
                    int end = findStringEnd(line, pos + 1, '"');
                    if (end >= 0) {
                        spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                        pos = end + 1;
                    } else {
                        spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                        pos = line.length();
                    }
                }
                continue;
            }

            // Char literal
            if (ch == '\'') {
                int end = findStringEnd(line, pos + 1, '\'');
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            // ★ v2.55 — JS/TS template literals : `text ${expr} more`.
            // Avant v2.55, les backticks tombaient dans PUNCT — les
            // template literals les plus courants en JS moderne
            // (React, ESLint configs, Jest) étaient rendus en punctuation
            // grise — illisibles. Maintenant on reconnaît le délimiteur
            // backtick et on découpe les interpolations ${...} en
            // VARIABLE pour les distinguer du texte littéral.
            //
            // Limitation connue : pas de support multi-lignes (template
            // literals peuvent spanner plusieurs lignes en JS). On
            // colorie la première ligne en STRING si pas fermée, et le
            // reste tombe dans le path normal — acceptable car le cas
            // multi-lignes est rare en code UI et la mise en évidence
            // du délimiteur ` est déjà un gain net.
            if (ch == '`'
                    && ("javascript".equals(language) || "typescript".equals(language)
                        || "js".equals(language) || "ts".equals(language))) {
                int close = findStringEnd(line, pos + 1, '`');
                if (close >= 0) {
                    addTemplateLiteralSpans(spans, line, pos, close + 1);
                    pos = close + 1;
                    continue;
                }
                // Pas fermé sur cette ligne — span STRING jusqu'à EOF,
                // pas d'état multi-ligne (voir commentaire ci-dessus).
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
                continue;
            }

            // Annotation
            if (ch == '@') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, TokenType.ANNOTATION));
                pos = end;
                continue;
            }

            // Number
            if (Character.isDigit(ch)) {
                int end = pos + 1;
                while (end < line.length()) {
                    char c = line.charAt(end);
                    if (Character.isDigit(c) || c == '.' || c == '_' || c == 'x' || c == 'X'
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                        || c == 'L' || c == 'l' || c == 'f' || c == 'd') {
                        end++;
                    } else {
                        break;
                    }
                }
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }


            // v3.3.0: Color literal detection (#RGB, #RRGGBB, #RRGGBBAA, #AARRGGBB)
            if (ch == '#' && pos + 1 < line.length()) {
                int colorEnd = pos + 1;
                while (colorEnd < line.length() && isHexChar(line.charAt(colorEnd))) colorEnd++;
                int colorLen = colorEnd - pos - 1;
                if (colorLen == 3 || colorLen == 4 || colorLen == 6 || colorLen == 8) {
                    spans.add(new LineSpan(pos, colorEnd, TokenType.NUMBER));
                    pos = colorEnd;
                    continue;
                }
            }

            // Identifier / keyword
            if (Character.isLetter(ch) || ch == '_') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) {
                    end++;
                }
                String word = line.substring(pos, end);
                TokenType type;
                if (keywords.contains(word)) {
                    type = TokenType.KEYWORD;
                } else if (JAVA_TYPES.contains(word)) {
                    type = TokenType.TYPE;
                } else if (isAllCaps(word) && word.length() > 1) {
                    // v3.1.0: ALL_CAPS identifiers → CONSTANT
                    type = TokenType.CONSTANT;
                } else if (end < line.length() && line.charAt(end) == '(') {
                    type = TokenType.FUNC;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    type = TokenType.TYPE;
                } else if (pos > 0 && line.charAt(pos - 1) == '.') {
                    // v3.1.0: identifier after a dot → PROPERTY
                    type = TokenType.PROPERTY;
                } else {
                    type = TokenType.PLAIN;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // v3.1.0: Operator detection (split from PUNCT)
            if (OPERATORS.indexOf(ch) >= 0) {
                // Consume multi-char operators (==, !=, <=, >=, ->, ::, etc.)
                int end = pos + 1;
                while (end < line.length() && OPERATORS.indexOf(line.charAt(end)) >= 0) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, TokenType.OPERATOR));
                pos = end;
                continue;
            }

            // Punctuation (brackets, commas, semicolons, etc.)
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }

        return new StyledLine(spans, entryState, state);
    }

    /**
     * ★ v0.1.0.49-v2.20 — Styleur de logs (console Gradle/JVM embarquée).
     *
     * <p>Utilisé par le langage « log » ({@code session.setLanguage("log")}) —
     * consommé par {@code ConsoleLogView} côté app CodeIDE pour la console de
     * build et l'onglet Diagnostic JVM.</p>
     *
     * <p>Coloration par ligne (une seule span par ligne — les consoles
     * reçoivent des milliers de lignes, la détection doit rester O(len)) :</p>
     * <ul>
     *   <li>{@link TokenType#ERROR} — "error", "failed", "build failed",
     *       "failure", "severe", "exception", "échec", "erreur",
     *       préfixe Kotlin/javac « e: »…</li>
     *   <li>{@link TokenType#WARNING} — "warning:…", préfixe Kotlin « w: »…</li>
     *   <li>{@link TokenType#SUCCESS} — "BUILD SUCCESSFUL", "succeeded",
     *       "succès", "prêt", et les statuts de tâche sans travail
     *       «&nbsp;UP-TO-DATE / FROM-CACHE / SKIPPED / NO-SOURCE&nbsp;»…</li>
     *   <li>{@link TokenType#INFO} — lignes structurées préfixées "[Tooling]",
     *       "[Sync]", "[JVM]", "[JVM-out]", "[Cancel]"…</li>
     *   <li>{@link TokenType#TYPE} — en-têtes de section Gradle «&nbsp;&gt;&nbsp;…&nbsp;»
     *       (« &gt; Task :… », « &gt; Configure… », « &gt; Build :… », « &gt; Sync… »).</li>
     *   <li>{@link TokenType#PLAIN} — sortie standard Gradle.</li>
     * </ul>
     *
     * <p>★ v2.61 — Toutes les couleurs proviennent du thème natif via
     * {@code EditorTheme.colorForToken(TokenType)} : le styleur n'introduit
     * AUCUNE couleur custom — la console affiche exactement les couleurs
     * natives de l'EditorView (thème de l'éditeur partagé).</p>
     *
     * <p>L'état d'entrée est ignoré : un log n'a pas d'états lexicaux
     * multi-lignes, l'exit state est toujours {@code LexState.NORMAL}.</p>
     */
    private StyledLine styleLog(String line) {
        TokenType type = logTokenTypeFor(line);
        if (type == TokenType.PLAIN) {
            // Pas de span du tout → rendu texte brut (chemin le plus rapide,
            // cache-friendly dans EditorRenderer).
            return new StyledLine(java.util.Collections.emptyList(),
                LexState.NORMAL, LexState.NORMAL);
        }
        List<LineSpan> spans = new ArrayList<>(1);
        spans.add(new LineSpan(0, line.length(), type));
        return new StyledLine(spans, LexState.NORMAL, LexState.NORMAL);
    }

    /**
     * Détermine le type de token d'une ligne de log (règles présentées dans
     * l'ordre d'évaluation — important : ERROR avant SUCCESS pour que
     * « BUILD FAILED » gagne sur une éventuelle co-occurrence, SUCCESS avant
     * INFO pour que « [Tooling] … terminé avec succès » soit vert).
     *
     * <p>★ v2.61 — Couverture étendue aux lignes réellement émises par Gradle
     * via le Tooling API : statuts de tâche sur ligne séparée
     * («&nbsp; UP-TO-DATE&nbsp;»), en-têtes de section génériques
     * («&nbsp;&gt;&nbsp;…&nbsp;»), diagnostics Kotlin «&nbsp;e:&nbsp;»/«&nbsp;w:&nbsp;»,
     * échec de tâche («&nbsp;FAILED&nbsp;»). Couleurs 100% natives du thème.</p>
     */
    private static TokenType logTokenTypeFor(String line) {
        String l = line.toLowerCase(java.util.Locale.ROOT);
        // 1. Erreurs — messages d'échec javac/Gradle/JVM, stacktraces.
        //    ★ v2.61 — "failed" générique ("Task :x FAILED", "1 failed") et
        //    préfixe Kotlin « e: » ajoutés.
        if (l.contains("error")
                || l.contains("failed")
                || l.contains("failure")
                || l.contains("severe")
                || l.contains("exception")
                || l.startsWith("e:")
                || l.startsWith("erreur")
                || l.contains("échec")
                || l.contains("what went wrong")) {
            return TokenType.ERROR;
        }
        // 2. Avertissements. ★ v2.61 — préfixe Kotlin « w: » ajouté.
        if (l.startsWith("warning:")
                || l.contains("warning:")
                || l.startsWith("> warning")
                || l.startsWith("w:")
                || l.contains("[warn]")) {
            return TokenType.WARNING;
        }
        // 3. Succès — résumés de build/sync réussis, état tooling prêt, et
        //    ★ v2.61 statuts de tâche « sans travail » que Gradle émet sur
        //    leur PROPRE ligne (sortie chunked du Tooling API) : rendus en
        //    SUCCESS (vert natif), comme dans les consoles pro.
        if (l.contains("build successful")
                || l.contains("sync successful")
                || l.contains("succeeded")
                || l.contains("succès")
                || l.endsWith("prêt")
                || l.contains("operation completed successfully")
                || l.startsWith(" up-to-date")
                || l.startsWith(" from-cache")
                || l.startsWith(" skipped")
                || l.startsWith(" no-source")) {
            return TokenType.SUCCESS;
        }
        // 4. En-têtes de section Gradle — ★ v2.61 généralisés à TOUTES les
        //    lignes « > … » : « > Task :app:x » (Gradle), « > Configure… »
        //    (Gradle), « > Build: … » / « > Sync Gradle… » (cadrage synthétisé
        //    par l'app). Un seul token TYPE = la couleur native des types
        //    du thème éditeur.
        if (line.startsWith("> ")) {
            return TokenType.TYPE;
        }
        // 5. Lignes bracketées simples ([Tooling] / [Sync] / [JVM] /
        //    [JVM-out] / [Cancel]) → INFO.
        if (l.startsWith("[tooling]") || l.startsWith("[sync]")
                || l.startsWith("[jvm]") || l.startsWith("[jvm-out]")
                || l.startsWith("[cancel]")) {
            return TokenType.INFO;
        }
        // 6. Sortie Gradle standard — texte brut.
        return TokenType.PLAIN;
    }

    /**
     * Markdown line styling with fenced code block support.
     */
    private StyledLine styleMarkdown(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int state = entryState;

        // Fenced code block: ``` at start of line
        if (trimStart(line).startsWith("```")) {
            // Toggle fenced code state
            if (state == LexState.NORMAL) {
                state = LexState.BLOCK_COMMENT; // reuse BLOCK_COMMENT for fenced code
            } else {
                state = LexState.NORMAL;
            }
            spans.add(new LineSpan(0, line.length(), TokenType.KEYWORD));
            return new StyledLine(spans, entryState, state);
        }

        // Inside fenced code block: render as comment (monospace)
        if (state == LexState.BLOCK_COMMENT) {
            spans.add(new LineSpan(0, line.length(), TokenType.COMMENT));
            return new StyledLine(spans, entryState, state);
        }

        // Normal markdown
        int pos = 0;
        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Heading: # at start
            if (pos == 0 && ch == '#') {
                int end = pos;
                while (end < line.length() && line.charAt(end) == '#') end++;
                spans.add(new LineSpan(pos, end, TokenType.KEYWORD));
                pos = end;
                continue;
            }

            // Bold: **text**
            if (ch == '*' && pos + 1 < line.length() && line.charAt(pos + 1) == '*') {
                int close = line.indexOf("**", pos + 2);
                if (close >= 0) {
                    spans.add(new LineSpan(pos, close + 2, TokenType.ANNOTATION));
                    pos = close + 2;
                    continue;
                }
            }

            // Inline code: `text`
            if (ch == '`') {
                int close = line.indexOf('`', pos + 1);
                if (close >= 0) {
                    spans.add(new LineSpan(pos, close + 1, TokenType.STRING));
                    pos = close + 1;
                    continue;
                }
            }

            // Link: [text](url)
            if (ch == '[') {
                int closeBracket = line.indexOf(']', pos + 1);
                if (closeBracket >= 0 && closeBracket + 1 < line.length() && line.charAt(closeBracket + 1) == '(') {
                    int closeParen = line.indexOf(')', closeBracket + 2);
                    if (closeParen >= 0) {
                        spans.add(new LineSpan(pos, closeParen + 1, TokenType.FUNC));
                        pos = closeParen + 1;
                        continue;
                    }
                }
            }

            // Unordered list: - or * at start of line (after optional whitespace)
            if (pos == 0 && (ch == '-' || ch == '*')) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
                pos++;
                continue;
            }

            // Plain text
            int end = pos + 1;
            while (end < line.length()) {
                char c = line.charAt(end);
                if (c == '*' || c == '`' || c == '[' || c == '#') break;
                end++;
            }
            if (end > pos + 1 || spans.isEmpty() || spans.get(spans.size() - 1).type != TokenType.PLAIN) {
                spans.add(new LineSpan(pos, end, TokenType.PLAIN));
            } else {
                // Extend last PLAIN span
                LineSpan last = spans.get(spans.size() - 1);
                spans.set(spans.size() - 1, new LineSpan(last.startCol, end, TokenType.PLAIN));
            }
            pos = end;
        }

        return new StyledLine(spans, entryState, state);
    }

    // ── v3.3.2: XML tokenizer ────────────────────────────────────

    /**
     * Tokenizes an XML line. Supports:
     * <ul>
     *   <li>Tags: {@code <tag>}, {@code </tag>}, {@code <tag attr="val">}</li>
     *   <li>Attributes: {@code android:text="hello"}</li>
     *   <li>Comments: {@code <!-- ... -->} (cross-line)</li>
     *   <li>Processing instructions: {@code <?xml ... ?>}</li>
     *   <li>CDATA: {@code <![CDATA[ ... ]]>} (cross-line)</li>
     *   <li>String values with escape highlighting</li>
     * </ul>
     * <p>Tag names get {@link TokenType#TYPE}, attribute names get
     * {@link TokenType#PROPERTY}, string values get {@link TokenType#STRING}.
     */
    private StyledLine styleXml(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Handle cross-line XML comment.
        if (state == LexState.BLOCK_COMMENT) {
            int end = line.indexOf("-->", pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 3, TokenType.COMMENT));
                pos = end + 3;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
            }
        }

        // Handle cross-line CDATA.
        if (state == LexState.KT_RAW_STRING) { // reuse for CDATA
            int end = line.indexOf("]]>", pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 3, TokenType.STRING));
                pos = end + 3;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        // Handle cross-line attribute string.
        if (state == LexState.XML_STRING) {
            int end = findXmlStringEnd(line, pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                pos = end + 1;
                state = LexState.XML_TAG; // back inside the tag's attribute list
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        // v3.3.3: Handle cross-line XML_TAG state — we're inside a tag's
        // attribute list (the previous line had `<tag` without `>`).
        // Continue parsing attributes until we hit `>` or `/>`.
        if (state == LexState.XML_TAG) {
            int[] nextState = new int[]{ LexState.XML_TAG };
            pos = parseXmlAttributes(line, pos, spans, nextState);
            state = nextState[0];
            // Check whether the tag was closed on this line.
            if (pos < line.length()) {
                char c = line.charAt(pos);
                if (c == '>') {
                    spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
                    pos++;
                    state = LexState.NORMAL;
                } else if (c == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '>') {
                    spans.add(new LineSpan(pos, pos + 2, TokenType.PUNCT));
                    pos += 2;
                    state = LexState.NORMAL;
                }
            }
            // If state is NORMAL now, fall through to parse text/next tag.
            // Otherwise, we've consumed the whole line.
            if (state != LexState.NORMAL) {
                return new StyledLine(spans, entryState, state);
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Whitespace.
            if (Character.isWhitespace(ch)) { pos++; continue; }

            // XML comment <!-- ... -->
            if (ch == '<' && pos + 3 < line.length() && line.charAt(pos + 1) == '!'
                && line.charAt(pos + 2) == '-' && line.charAt(pos + 3) == '-') {
                int end = line.indexOf("-->", pos + 4);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 3, TokenType.COMMENT));
                    pos = end + 3;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT;
                }
                continue;
            }

            // CDATA <![CDATA[ ... ]]>
            if (ch == '<' && pos + 8 < line.length() && line.startsWith("![CDATA[", pos + 1)) {
                int end = line.indexOf("]]>", pos + 9);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 3, TokenType.STRING));
                    pos = end + 3;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.KT_RAW_STRING;
                }
                continue;
            }

            // Processing instruction <?xml ... ?>
            if (ch == '<' && pos + 1 < line.length() && line.charAt(pos + 1) == '?') {
                int end = line.indexOf("?>", pos + 2);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.ANNOTATION));
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.ANNOTATION));
                    pos = line.length();
                }
                continue;
            }

            // Closing tag </tag>
            if (ch == '<' && pos + 1 < line.length() && line.charAt(pos + 1) == '/') {
                int end = pos + 2;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == ':' || line.charAt(end) == '-' || line.charAt(end) == '.')) end++;
                spans.add(new LineSpan(pos, pos + 2, TokenType.PUNCT)); // </
                spans.add(new LineSpan(pos + 2, end, TokenType.TYPE)); // tag name
                if (end < line.length() && line.charAt(end) == '>') {
                    spans.add(new LineSpan(end, end + 1, TokenType.PUNCT)); // >
                    end++;
                }
                pos = end;
                continue;
            }

            // Opening tag <tag ...>
            if (ch == '<') {
                int end = pos + 1;
                // Read tag name.
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == ':' || line.charAt(end) == '-' || line.charAt(end) == '.')) end++;
                spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT)); // <
                if (end > pos + 1) {
                    spans.add(new LineSpan(pos + 1, end, TokenType.TYPE)); // tag name
                }
                pos = end;

                // Parse attributes until > or />
                int[] nextState = new int[]{ LexState.XML_TAG };
                pos = parseXmlAttributes(line, pos, spans, nextState);
                // Check whether the tag was closed on this line.
                if (pos < line.length()) {
                    char c2 = line.charAt(pos);
                    if (c2 == '>') {
                        spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
                        pos++;
                        state = LexState.NORMAL;
                    } else if (c2 == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '>') {
                        spans.add(new LineSpan(pos, pos + 2, TokenType.PUNCT)); // />
                        pos += 2;
                        state = LexState.NORMAL;
                    } else {
                        // Unrecognized — leave XML_TAG state set so the next
                        // line continues parsing attributes.
                        state = nextState[0];
                    }
                } else {
                    // Reached end of line without closing `>`.
                    // nextState[0] is XML_STRING if a string spans the next
                    // line, or XML_TAG if we just ran out of attributes.
                    state = nextState[0];
                }
                continue;
            }

            // Text content between tags.
            if (ch == '&') {
                // XML entity: &amp; &lt; etc.
                int end = line.indexOf(';', pos);
                if (end >= 0 && end - pos < 10) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.ESCAPE));
                    pos = end + 1;
                    continue;
                }
            }

            // Plain text content.
            int textStart = pos;
            while (pos < line.length() && line.charAt(pos) != '<') pos++;
            if (pos > textStart) {
                spans.add(new LineSpan(textStart, pos, TokenType.PLAIN));
            }
        }
        return new StyledLine(spans, entryState, state);
    }

    /**
     * v3.3.3: Parses XML attributes (name="value" pairs) inside a tag,
     * starting at {@code pos}. Stops at — and does NOT consume — the closing
     * {@code >} or {@code />}. Returns the new position.
     * <p>If an attribute's string value spans into the next line, writes
     * {@link LexState#XML_STRING} into {@code nextState[0]}; otherwise
     * leaves it unchanged.
     * <p>Extracted from {@link #styleXml} so both the cross-line XML_TAG
     * continuation path and the inline opening-tag path share the exact
     * same attribute-parsing logic — fixes the multi-line tag bug where
     * attribute names on continuation lines were rendered as PLAIN.
     */
    private int parseXmlAttributes(String line, int pos, List<LineSpan> spans,
                                    int[] nextState) {
        while (pos < line.length()) {
            char c2 = line.charAt(pos);
            if (Character.isWhitespace(c2)) { pos++; continue; }
            if (c2 == '>' || c2 == '/') {
                // Let the caller handle > and />.
                break;
            }
            // Attribute name.
            int attrStart = pos;
            while (pos < line.length() && line.charAt(pos) != '=' && line.charAt(pos) != '>'
                && line.charAt(pos) != '/' && !Character.isWhitespace(line.charAt(pos))) {
                pos++;
            }
            if (pos > attrStart) {
                spans.add(new LineSpan(attrStart, pos, TokenType.PROPERTY)); // attribute name
            }
            // Skip whitespace before =.
            while (pos < line.length() && Character.isWhitespace(line.charAt(pos))) pos++;
            // = sign.
            if (pos < line.length() && line.charAt(pos) == '=') {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                // Skip whitespace after =.
                while (pos < line.length() && Character.isWhitespace(line.charAt(pos))) pos++;
                // String value.
                if (pos < line.length() && (line.charAt(pos) == '"' || line.charAt(pos) == '\'')) {
                    int strEnd = findXmlStringEnd(line, pos + 1);
                    if (strEnd >= 0) {
                        addStringSpans(spans, line, pos, strEnd + 1, TokenType.STRING);
                        pos = strEnd + 1;
                    } else {
                        // String value spans into the next line.
                        spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                        pos = line.length();
                        nextState[0] = LexState.XML_STRING;
                        return pos;
                    }
                }
            }
        }
        return pos;
    }

    private Set<String> getKeywords(String language) {
        if (language == null) return JAVA_KEYWORDS;
        switch (language.toLowerCase(java.util.Locale.ROOT)) {
            case "kotlin": return KT_KEYWORDS;
            case "xml": return XML_KEYWORDS;
            case "json": return JSON_KEYWORDS;
            case "python": case "py": return PYTHON_KEYWORDS;
            case "javascript": case "js": case "typescript": case "ts":
                return JS_KEYWORDS;
            case "lua": return LUA_KEYWORDS;
            // ── v2.46 — parser maison: additional C-like languages ──
            case "c": case "h": return C_KEYWORDS;
            case "cpp": case "cc": case "hpp": case "cxx": return CPP_KEYWORDS;
            case "go": return GO_KEYWORDS;
            case "rust": case "rs": return RUST_KEYWORDS;
            case "ruby": case "rb": return RUBY_KEYWORDS;
            case "php": return PHP_KEYWORDS;
            case "swift": return SWIFT_KEYWORDS;
            case "dart": return DART_KEYWORDS;
            case "groovy": case "gradle": return GROOVY_KEYWORDS;
            case "sql": return SQL_KEYWORDS;
            case "shell": case "bash": case "sh": return SHELL_KEYWORDS;
            case "smali": return SMALI_KEYWORDS;
            case "toml": return TOML_KEYWORDS;
            case "properties": return PROPERTIES_KEYWORDS;
            default: return JAVA_KEYWORDS;
        }
    }

    /** v3.1.0: Returns true if the word is ALL_CAPS (at least 2 chars, all uppercase + digits + _). */
    private static boolean isAllCaps(String word) {
        if (word.length() < 2) return false;
        boolean hasLetter = false;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (!Character.isUpperCase(c)) return false;
            } else if (c != '_' && !Character.isDigit(c)) {
                return false;
            }
        }
        return hasLetter;
    }

    // ── v3.1.0: JSON tokenizer ──────────────────────────────────

    /**
     * Tokenizes a JSON line. JSON keys (strings before colons) get
     * {@link TokenType#PROPERTY}, values get {@link TokenType#STRING}.
     * Booleans/null get {@link TokenType#KEYWORD}.
     */
    private StyledLine styleJson(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Handle cross-line strings (multi-line not standard JSON but be safe).
        if (state == LexState.XML_STRING) {
            int end = findXmlStringEnd(line, pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                pos = end + 1;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);
            if (Character.isWhitespace(ch)) { pos++; continue; }

            // String (could be a key or a value).
            if (ch == '"') {
                int end = findStringEnd(line, pos + 1, '"');
                if (end >= 0) {
                    // Check if this is a key (followed by a colon).
                    int after = end + 1;
                    while (after < line.length() && Character.isWhitespace(line.charAt(after))) after++;
                    TokenType type = (after < line.length() && line.charAt(after) == ':')
                        ? TokenType.PROPERTY : TokenType.STRING;
                    // Highlight escape sequences within strings.
                    addStringSpans(spans, line, pos, end + 1, type);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.XML_STRING;
                }
                continue;
            }

            // Number
            if (Character.isDigit(ch) || (ch == '-' && pos + 1 < line.length() && Character.isDigit(line.charAt(pos + 1)))) {
                int end = pos + 1;
                while (end < line.length()) {
                    char c = line.charAt(end);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') end++;
                    else break;
                }
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Keyword (true, false, null)
            if (Character.isLetter(ch)) {
                int end = pos + 1;
                while (end < line.length() && Character.isLetter(line.charAt(end))) end++;
                String word = line.substring(pos, end);
                if (JSON_KEYWORDS.contains(word)) {
                    spans.add(new LineSpan(pos, end, TokenType.KEYWORD));
                } else {
                    spans.add(new LineSpan(pos, end, TokenType.PLAIN));
                }
                pos = end;
                continue;
            }

            // Operator (colon, comma)
            if (ch == ':' || ch == ',') {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                continue;
            }

            // Punctuation (brackets)
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    // ── v3.1.0: Python tokenizer ────────────────────────────────

    /**
     * Tokenizes a Python line. Supports # comments, triple-quoted strings,
     * f-strings, decorators, and keywords.
     */
    private StyledLine stylePython(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Handle cross-line triple-quoted strings.
        if (state == LexState.BLOCK_COMMENT) {
            int end = findTripleQuoteEnd(line, pos, state);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end, TokenType.STRING));
                pos = end;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);
            if (Character.isWhitespace(ch)) { pos++; continue; }

            // # comment
            if (ch == '#') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Decorator
            if (ch == '@' && (pos == 0 || Character.isWhitespace(line.charAt(pos - 1)))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '.')) end++;
                spans.add(new LineSpan(pos, end, TokenType.ANNOTATION));
                pos = end;
                continue;
            }

            // Triple-quoted string
            if (ch == '"' && pos + 2 < line.length() && line.charAt(pos + 1) == '"' && line.charAt(pos + 2) == '"') {
                int end = findTripleQuoteEnd(line, pos + 3, LexState.BLOCK_COMMENT);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end, TokenType.STRING));
                    pos = end;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT;
                }
                continue;
            }

            // f-string prefix
            if ((ch == 'f' || ch == 'F' || ch == 'r' || ch == 'R' || ch == 'b' || ch == 'B')
                && pos + 1 < line.length() && (line.charAt(pos + 1) == '"' || line.charAt(pos + 1) == '\'')) {
                char quote = line.charAt(pos + 1);
                int end = findStringEnd(line, pos + 2, quote);
                if (end >= 0) {
                    addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            // Regular string
            if (ch == '"' || ch == '\'') {
                int end = findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            // Number
            if (Character.isDigit(ch)) {
                int end = pos + 1;
                while (end < line.length()) {
                    char c = line.charAt(end);
                    if (Character.isDigit(c) || c == '.' || c == '_' || c == 'x' || c == 'X'
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == 'j' || c == 'J') end++;
                    else break;
                }
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Identifier / keyword
            if (Character.isLetter(ch) || ch == '_') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (PYTHON_KEYWORDS.contains(word)) type = TokenType.KEYWORD;
                else if (PYTHON_TYPES.contains(word)) type = TokenType.TYPE;
                else if (isAllCaps(word)) type = TokenType.CONSTANT;
                else if (Character.isUpperCase(word.charAt(0))) type = TokenType.TYPE;
                else if (end < line.length() && line.charAt(end) == '(') type = TokenType.FUNC;
                else type = TokenType.PLAIN;
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Operator
            if (OPERATORS.indexOf(ch) >= 0) {
                int end = pos + 1;
                while (end < line.length() && OPERATORS.indexOf(line.charAt(end)) >= 0) end++;
                spans.add(new LineSpan(pos, end, TokenType.OPERATOR));
                pos = end;
                continue;
            }

            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    // ── v3.2.0: Lua tokenizer ───────────────────────────────────

    /**
     * Tokenizes a Lua line. Supports:
     * <ul>
     *   <li>{@code --} line comments and {@code --[[ }]] block comments</li>
     *   <li>Single/double quoted strings with escape sequences</li>
     *   <li>Long strings {@code [[ ... ]]}</li>
     *   <li>Keywords, built-in functions, numbers, operators</li>
     * </ul>
     */
    private StyledLine styleLua(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Handle cross-line block comment (--[[ ]]) and long string ([[ ]])
        if (state == LexState.BLOCK_COMMENT) {
            int end = line.indexOf("]]", pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                pos = end + 2;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);
            if (Character.isWhitespace(ch)) { pos++; continue; }

            // -- line comment
            if (ch == '-' && pos + 1 < line.length() && line.charAt(pos + 1) == '-') {
                // Check for --[[ block comment
                if (pos + 3 < line.length() && line.charAt(pos + 2) == '[' && line.charAt(pos + 3) == '[') {
                    int end = line.indexOf("]]", pos + 4);
                    if (end >= 0) {
                        spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                        pos = end + 2;
                    } else {
                        spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                        pos = line.length();
                        state = LexState.BLOCK_COMMENT;
                    }
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                }
                continue;
            }

            // [[ long string ]]
            if (ch == '[' && pos + 1 < line.length() && line.charAt(pos + 1) == '[') {
                int end = line.indexOf("]]", pos + 2);
                if (end >= 0) {
                    addStringSpans(spans, line, pos, end + 2, TokenType.STRING);
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT; // reuse for long strings
                }
                continue;
            }

            // String literals
            if (ch == '"' || ch == '\'') {
                int end = findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            // Number
            if (Character.isDigit(ch)) {
                int end = pos + 1;
                while (end < line.length()) {
                    char c = line.charAt(end);
                    if (Character.isDigit(c) || c == '.' || c == 'x' || c == 'X'
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) end++;
                    else break;
                }
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Identifier / keyword
            if (Character.isLetter(ch) || ch == '_') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (LUA_KEYWORDS.contains(word)) type = TokenType.KEYWORD;
                else if (LUA_BUILTINS.contains(word)) type = TokenType.TYPE;
                else if (isAllCaps(word)) type = TokenType.CONSTANT;
                else if (end < line.length() && line.charAt(end) == '(') type = TokenType.FUNC;
                else type = TokenType.PLAIN;
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Operator
            if (OPERATORS.indexOf(ch) >= 0) {
                int end = pos + 1;
                while (end < line.length() && OPERATORS.indexOf(line.charAt(end)) >= 0) end++;
                spans.add(new LineSpan(pos, end, TokenType.OPERATOR));
                pos = end;
                continue;
            }

            // Punctuation
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    /** Finds the end of a triple-quoted string (""" or '''). */
    private static int findTripleQuoteEnd(String line, int start, int state) {
        for (int i = start; i < line.length() - 2; i++) {
            if (line.charAt(i) == '"' && line.charAt(i + 1) == '"' && line.charAt(i + 2) == '"') {
                return i + 3;
            }
        }
        return -1;
    }

    /**
     * v3.1.0: Adds string spans with escape sequence highlighting.
     * The overall string gets {@code stringType}, but escape sequences
     * (n, t, uXXXX) inside get {@link TokenType#ESCAPE}.
     */
    private void addStringSpans(List<LineSpan> spans, String line, int start, int end, TokenType stringType) {
        int pos = start;
        while (pos < end) {
            if (line.charAt(pos) == '\\' && pos + 1 < end) {
                // Emit the string text before the escape.
                if (pos > start) {
                    spans.add(new LineSpan(start, pos, stringType));
                }
                // Emit the escape sequence (2-6 chars: n, t, uXXXX, x-NN).
                int escEnd = pos + 2;
                if (pos + 1 < end) {
                    char next = line.charAt(pos + 1);
                    if (next == 'u' && pos + 5 < end) escEnd = pos + 6; // uXXXX
                    else if (next == 'x' && pos + 3 < end) escEnd = pos + 4; // x-NN
                }
                spans.add(new LineSpan(pos, Math.min(escEnd, end), TokenType.ESCAPE));
                pos = escEnd;
                start = pos;
            } else {
                pos++;
            }
        }
        // Emit remaining string text.
        if (start < end) {
            spans.add(new LineSpan(start, end, stringType));
        }
    }

    /**
     * ★ v2.55 — Découpe un JS/TS template literal en spans.
     *
     * <p>Un template literal ressemble à :
     * <pre>`Hello ${name}, you are ${age} years old`</pre>
     *
     * <p>On veut colorier :
     * <ul>
     *   <li>Le tout premier backtick `` ` `` en {@link TokenType#STRING}</li>
     *   <li>Les segments de texte littéral en {@link TokenType#STRING}</li>
     *   <li>Les séquences d'échappement (e.g. {@code \n}, {@code \t}) en
     *       {@link TokenType#ESCAPE}</li>
     *   <li>Les interpolations {@code ${expr}} en {@link TokenType#VARIABLE}
     *       (pour les distinguer visuellement du texte — pratique pour
     *       debug une template React qui rate)</li>
     *   <li>Le backtick fermant en {@link TokenType#STRING}</li>
     * </ul>
     *
     * <p>L'algorithme marche en single pass : on scanne entre start et end,
     * on cherche les marqueurs `${`, on split. À l'intérieur d'une
     * interpolation on continue jusqu'au `}` fermant (en comptant les
     * accolades imbriquées pour gérer les objets `${ {a: 1}.a }`).
     *
     * <p>Note : on ne RE-tokenize pas l'intérieur de l'interpolation
     * (pas d'analyse lexicale récursive). C'est volontaire — l'effet
     * visuel "VARIABLE bleuté" suffit pour distinguer l'interpolation
     * du texte littéral, et la complexité d'une tokenization récursive
     * juste pour les template literals n'est pas justifiée.
     *
     * @since v2.55
     */
    private void addTemplateLiteralSpans(List<LineSpan> spans, String line, int start, int end) {
        int segStart = start;
        int pos = start + 1;  // skip the opening backtick
        while (pos < end - 1) {  // end-1 = backtick fermant
            char c = line.charAt(pos);
            // Escape sequence
            if (c == '\\' && pos + 1 < end - 1) {
                if (pos > segStart) {
                    spans.add(new LineSpan(segStart, pos, TokenType.STRING));
                }
                int escEnd = pos + 2;
                if (pos + 1 < end - 1) {
                    char next = line.charAt(pos + 1);
                    if (next == 'u' && pos + 5 < end - 1) escEnd = pos + 6;
                    else if (next == 'x' && pos + 3 < end - 1) escEnd = pos + 4;
                }
                spans.add(new LineSpan(pos, Math.min(escEnd, end), TokenType.ESCAPE));
                pos = escEnd;
                segStart = pos;
                continue;
            }
            // Interpolation start: ${
            if (c == '$' && pos + 1 < end - 1 && line.charAt(pos + 1) == '{') {
                // Flush the string segment before.
                if (pos > segStart) {
                    spans.add(new LineSpan(segStart, pos, TokenType.STRING));
                }
                // Find the matching } (handles nesting).
                int depth = 1;
                int interpEnd = pos + 2;
                while (interpEnd < end - 1 && depth > 0) {
                    char ic = line.charAt(interpEnd);
                    if (ic == '{') depth++;
                    else if (ic == '}') depth--;
                    if (depth == 0) break;
                    interpEnd++;
                }
                // Emit the interpolation ${...} as VARIABLE.
                spans.add(new LineSpan(pos, interpEnd + 1, TokenType.VARIABLE));
                pos = interpEnd + 1;
                segStart = pos;
                continue;
            }
            pos++;
        }
        // Final string segment (between last interpolation and closing backtick).
        if (segStart < end) {
            spans.add(new LineSpan(segStart, end, TokenType.STRING));
        }
    }

    /**
     * Finds the end of a string literal, handling escape sequences.
     */
    private static int findStringEnd(String line, int start, char quote) {
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++; // skip escaped char
            } else if (c == quote) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Finds the end of an XML attribute string (handles &amp; entities).
     */
    private static int findXmlStringEnd(String line, int start) {
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') return i;
        }
        return -1;
    }

    /**
     * Trims leading whitespace from a string.
     */
    private static String trimStart(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    /** v3.3.0: Returns true if the character is a hex digit (0-9, a-f, A-F). */
    private static boolean isHexChar(char c) {
        return Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    // ═══════════════════════════════════════════════════════════════════
    // v2.46 — PARSER MAISON: specialized tokenizers for non-C-like languages.
    //
    // Each tokenizer is a single-pass scanner per line with optional
    // cross-line state. Quality is intentionally lower than full TextMate
    // grammars (no scope inheritance, no nested injections), but covers
    // the common syntax constructs (keywords, comments, strings, numbers).
    // ═══════════════════════════════════════════════════════════════════

    // ─── CSS / SCSS / LESS ───────────────────────────────────────────────

    /**
     * v2.46 — Tokenizes a CSS (or SCSS/LESS) line.
     *
     * <p>Recognizes:
     * <ul>
     *   <li>At-rules ({@code @media}, {@code @import}, {@code @keyframes}, …)
     *       → KEYWORD</li>
     *   <li>Property names ({@code color}, {@code background}, …) → PROPERTY</li>
     *   <li>String values ({@code "…" / '…'}) → STRING</li>
     *   <li>Numbers with units ({@code 12px}, {@code 1.5em}, {@code 100%}) → NUMBER</li>
     *   <li>Hex colors ({@code #fff}, {@code #aabbcc}) → NUMBER</li>
     *   <li>Comments (slash-asterisk ... asterisk-slash, can span lines) → COMMENT</li>
     *   <li>Selectors (start of line, before a {@code {}) → TYPE</li>
     *   <li>Variable interpolations ({@code $var}, {@code @var}) → VARIABLE</li>
     * </ul>
     *
     * <p>The state machine is intentionally simple — we don't try to fully
     * parse selectors vs declarations. The heuristic: at-line-start
     * identifier-like tokens that aren't a known property are treated as
     * a selector (TYPE). Inside a declaration (after a property-colon),
     * everything until the next semicolon is a value (STRING/NUMBER/PLAIN).
     */
    private StyledLine styleCss(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Handle cross-line block comment.
        if (state == LexState.CSS_COMMENT) {
            int end = line.indexOf("*/", pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                pos = end + 2;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
            }
        }

        // Handle cross-line string (rare but valid).
        if (state == LexState.CSS_STRING) {
            int end = line.indexOf('"', pos);
            if (end < 0) end = line.indexOf('\'', pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                pos = end + 1;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Block comment
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '*') {
                int end = line.indexOf("*/", pos + 2);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                    state = LexState.CSS_COMMENT;
                }
                continue;
            }

            // Line comment (// — only valid in SCSS/LESS, but harmless in CSS)
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '/') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // At-rule (@media, @import, @keyframes, etc.)
            if (ch == '@') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '-' || line.charAt(end) == '_')) {
                    end++;
                }
                String word = line.substring(pos, end);
                if (CSS_AT_RULES.contains(word)) {
                    spans.add(new LineSpan(pos, end, TokenType.KEYWORD));
                } else {
                    // Could be @var interpolation — VARIABLE
                    spans.add(new LineSpan(pos, end, TokenType.VARIABLE));
                }
                pos = end;
                continue;
            }

            // SCSS variable ($var)
            if (ch == '$') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '-')) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, TokenType.VARIABLE));
                pos = end;
                continue;
            }

            // Hex color (#fff, #aabbcc, #aabbccff)
            if (ch == '#' && pos + 1 < line.length() && isHexChar(line.charAt(pos + 1))) {
                int end = pos + 1;
                while (end < line.length() && isHexChar(line.charAt(end))) end++;
                int len = end - pos - 1;
                if (len == 3 || len == 4 || len == 6 || len == 8) {
                    spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                    pos = end;
                    continue;
                }
            }

            // String literal
            if (ch == '"' || ch == '\'') {
                int end = findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.CSS_STRING;
                }
                continue;
            }

            // Number with optional unit
            if (Character.isDigit(ch) || (ch == '.' && pos + 1 < line.length() && Character.isDigit(line.charAt(pos + 1)))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) end++;
                // Unit suffix (px, em, rem, %, vh, vw, deg, s, ms, etc.)
                while (end < line.length() && (Character.isLetter(line.charAt(end)) || line.charAt(end) == '%')) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Identifier — could be a property name, selector, or value
            if (Character.isLetter(ch) || ch == '_' || ch == '-' || ch == '.' || ch == '&') {
                int end = pos + 1;
                while (end < line.length()
                        && (Character.isLetterOrDigit(line.charAt(end))
                            || line.charAt(end) == '_' || line.charAt(end) == '-'
                            || line.charAt(end) == '.')) {
                    end++;
                }
                String word = line.substring(pos, end);
                TokenType type;
                if (CSS_PROPERTIES.contains(word)) {
                    type = TokenType.PROPERTY;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    // Selector class name or constant value
                    type = TokenType.TYPE;
                } else {
                    // Plain — could be a value keyword (inherit, auto, etc.)
                    type = TokenType.PLAIN;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Operators (=, :, ;)
            if (ch == ':' || ch == ';' || ch == '{' || ch == '}' || ch == ',' || ch == '!' || ch == '>' || ch == '+' || ch == '~' || ch == '*' || ch == '=') {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                continue;
            }

            // Punctuation
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    // ─── Shell / Bash / sh ─────────────────────────────────────────────

    /**
     * v2.46 — Tokenizes a Shell/Bash line.
     *
     * <p>Recognizes:
     * <ul>
     *   <li>{@code #} line comments → COMMENT</li>
     *   <li>{@code $var} and {@code ${var}} variable references → VARIABLE</li>
     *   <li>Double-quoted strings ({@code "…$var…"}) — content STRING,
     *       embedded {@code $var} as VARIABLE</li>
     *   <li>Single-quoted strings ({@code 'literal'}) — STRING</li>
     *   <li>Backticks ({@code `cmd`}) → ANNOTATION (command substitution)</li>
     *   <li>Keywords (if, then, else, fi, for, while, do, done, …) → KEYWORD</li>
     *   <li>Builtins (echo, cd, export, …) → TYPE</li>
     *   <li>Numbers → NUMBER</li>
     * </ul>
     */
    private StyledLine styleShell(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Handle cross-line single-quoted string (bash allows multiline).
        if (state == LexState.SHELL_SINGLE_QUOTE) {
            int end = line.indexOf('\'', pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                pos = end + 1;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // # comment (but not in ${#var})
            if (ch == '#' && (pos == 0 || Character.isWhitespace(line.charAt(pos - 1)) || line.charAt(pos - 1) == ';')) {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // $variable or ${variable}
            if (ch == '$') {
                int end = pos + 1;
                if (end < line.length() && line.charAt(end) == '{') {
                    // ${var} form
                    end++;
                    while (end < line.length() && line.charAt(end) != '}') end++;
                    if (end < line.length()) end++;
                } else if (end < line.length() && (Character.isLetter(line.charAt(end)) || line.charAt(end) == '_')) {
                    while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                } else if (end < line.length() && line.charAt(end) == '?') {
                    end++; // $?
                } else if (end < line.length() && Character.isDigit(line.charAt(end))) {
                    end++; // $1, $2, ...
                }
                if (end > pos + 1) {
                    spans.add(new LineSpan(pos, end, TokenType.VARIABLE));
                    pos = end;
                    continue;
                }
            }

            // Double-quoted string with $var interpolation
            if (ch == '"') {
                int end = pos + 1;
                while (end < line.length() && line.charAt(end) != '"') {
                    if (line.charAt(end) == '\\' && end + 1 < line.length()) end++;
                    end++;
                }
                if (end < line.length()) end++;
                spans.add(new LineSpan(pos, end, TokenType.STRING));
                pos = end;
                continue;
            }

            // Single-quoted string (literal, no interpolation)
            if (ch == '\'') {
                int end = line.indexOf('\'', pos + 1);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.SHELL_SINGLE_QUOTE;
                }
                continue;
            }

            // Backtick command substitution
            if (ch == '`') {
                int end = line.indexOf('`', pos + 1);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.ANNOTATION));
                    pos = end + 1;
                    continue;
                }
            }

            // Number
            if (Character.isDigit(ch) && (pos == 0 || !Character.isLetterOrDigit(line.charAt(pos - 1)))) {
                int end = pos + 1;
                while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Identifier / keyword
            if (Character.isLetter(ch) || ch == '_' || ch == '-') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '-')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (SHELL_KEYWORDS.contains(word)) type = TokenType.KEYWORD;
                else if (end < line.length() && (line.charAt(end) == '(' || line.charAt(end) == '[')) type = TokenType.FUNC;
                else if (Character.isUpperCase(word.charAt(0))) type = TokenType.CONSTANT;
                else type = TokenType.PLAIN;
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Operators
            if ("|&;<>()".indexOf(ch) >= 0) {
                int end = pos + 1;
                while (end < line.length() && "|&;<>()".indexOf(line.charAt(end)) >= 0) end++;
                spans.add(new LineSpan(pos, end, TokenType.OPERATOR));
                pos = end;
                continue;
            }

            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    // ─── YAML ──────────────────────────────────────────────────────────

    /**
     * v2.46 — Tokenizes a YAML line.
     *
     * <p>Recognizes:
     * <ul>
     *   <li>{@code key:} (with optional space) → PROPERTY</li>
     *   <li>{@code #} comments → COMMENT</li>
     *   <li>{@code ---} document separator → KEYWORD</li>
     *   <li>{@code &anchor}, {@code *alias}, {@code !tag} → ANNOTATION</li>
     *   <li>Strings (quoted or plain) → STRING</li>
     *   <li>Numbers / booleans / null → NUMBER/CONSTANT</li>
     *   <li>{@code [a, b, c]} flow sequences and {@code {k: v}} flow mappings → PUNCT</li>
     * </ul>
     */
    private StyledLine styleYaml(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        boolean afterColon = false;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // # comment
            if (ch == '#') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Document separator
            if (ch == '-' && pos + 2 < line.length() && line.charAt(pos + 1) == '-' && line.charAt(pos + 2) == '-') {
                spans.add(new LineSpan(pos, pos + 3, TokenType.KEYWORD));
                pos += 3;
                continue;
            }

            // Anchor/alias/tag
            if (ch == '&' || ch == '*' || ch == '!') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '-')) end++;
                spans.add(new LineSpan(pos, end, TokenType.ANNOTATION));
                pos = end;
                continue;
            }

            // String literal (quoted)
            if (ch == '"' || ch == '\'') {
                int end = findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                    afterColon = false;
                    continue;
                }
            }

            // Number / boolean / null (only as value, not as key)
            if (afterColon && (Character.isDigit(ch) || ch == '-' || ch == '.' || Character.isLetter(ch))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '.' || line.charAt(end) == '-' || line.charAt(end) == '_' || line.charAt(end) == ':' || line.charAt(end) == '/')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (Character.isDigit(word.charAt(0)) || (word.length() > 1 && (word.charAt(0) == '-' || word.charAt(0) == '+') && Character.isDigit(word.charAt(1)))) {
                    type = TokenType.NUMBER;
                } else if ("true".equals(word) || "false".equals(word) || "True".equals(word) || "False".equals(word)
                        || "yes".equals(word) || "no".equals(word) || "Yes".equals(word) || "No".equals(word)
                        || "null".equals(word) || "Null".equals(word) || "~".equals(word)) {
                    type = TokenType.CONSTANT;
                } else {
                    type = TokenType.STRING;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Block scalar indicator (| or >) — emit as KEYWORD
            if (!afterColon && (ch == '|' || ch == '>')) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.KEYWORD));
                pos++;
                continue;
            }

            // Key — identifier followed by ':' (or end-of-line)
            if (!afterColon && (Character.isLetter(ch) || ch == '_' || ch == '"' || ch == '\'' || ch == '-' || ch == '.' || ch == '/')) {
                int end = pos + 1;
                while (end < line.length()
                        && line.charAt(end) != ':'
                        && line.charAt(end) != ' '
                        && line.charAt(end) != '\t'
                        && line.charAt(end) != '#') {
                    end++;
                }
                // Check if followed by ':'
                int lookAhead = end;
                while (lookAhead < line.length() && Character.isWhitespace(line.charAt(lookAhead))) lookAhead++;
                if (lookAhead < line.length() && line.charAt(lookAhead) == ':') {
                    spans.add(new LineSpan(pos, end, TokenType.PROPERTY));
                    pos = end;
                    continue;
                }
                // Otherwise it's a plain scalar value
                spans.add(new LineSpan(pos, end, TokenType.STRING));
                pos = end;
                afterColon = true;
                continue;
            }

            // Colon (after key)
            if (ch == ':') {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                afterColon = true;
                continue;
            }

            // List item indicator
            if (ch == '-') {
                int next = pos + 1;
                if (next >= line.length() || Character.isWhitespace(line.charAt(next))) {
                    spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                    pos++;
                    continue;
                }
            }

            // Flow indicators
            if ("[]{}".indexOf(ch) >= 0) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
                pos++;
                continue;
            }

            // Plain scalar value (continuation)
            int end = pos + 1;
            while (end < line.length() && line.charAt(end) != '#' && line.charAt(end) != ':' && !Character.isWhitespace(line.charAt(end))) end++;
            spans.add(new LineSpan(pos, end, TokenType.STRING));
            pos = end;
            afterColon = true;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }

    // ─── SQL ───────────────────────────────────────────────────────────

    /**
     * v2.46 — Tokenizes a SQL line.
     *
     * <p>Recognizes:
     * <ul>
     *   <li>{@code --} line comments → COMMENT</li>
     *   <li>Block comments (slash-asterisk ... asterisk-slash, can span lines) → COMMENT</li>
     *   <li>Single-quoted strings ({@code 'literal'}) → STRING</li>
     *   <li>Double-quoted identifiers ({@code "name"}) → PROPERTY</li>
     *   <li>Keywords (SELECT, FROM, WHERE, …) → KEYWORD</li>
     *   <li>Numbers → NUMBER</li>
     * </ul>
     */
    private StyledLine styleSql(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Handle cross-line block comment.
        if (state == LexState.BLOCK_COMMENT) {
            int end = line.indexOf("*/", pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                pos = end + 2;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Line comment
            if (ch == '-' && pos + 1 < line.length() && line.charAt(pos + 1) == '-') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Block comment
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '*') {
                int end = line.indexOf("*/", pos + 2);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT;
                }
                continue;
            }

            // Single-quoted string
            if (ch == '\'') {
                int end = findStringEnd(line, pos + 1, '\'');
                if (end >= 0) {
                    addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            // Double-quoted identifier
            if (ch == '"') {
                int end = findStringEnd(line, pos + 1, '"');
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.PROPERTY));
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.PROPERTY));
                    pos = line.length();
                }
                continue;
            }

            // Number
            if (Character.isDigit(ch) || (ch == '.' && pos + 1 < line.length() && Character.isDigit(line.charAt(pos + 1)))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Identifier / keyword
            if (Character.isLetter(ch) || ch == '_') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (SQL_KEYWORDS.contains(word)) type = TokenType.KEYWORD;
                else if (end < line.length() && line.charAt(end) == '(') type = TokenType.FUNC;
                else if (Character.isUpperCase(word.charAt(0))) type = TokenType.CONSTANT;
                else type = TokenType.PLAIN;
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Operators
            if (OPERATORS.indexOf(ch) >= 0) {
                int end = pos + 1;
                while (end < line.length() && OPERATORS.indexOf(line.charAt(end)) >= 0) end++;
                spans.add(new LineSpan(pos, end, TokenType.OPERATOR));
                pos = end;
                continue;
            }

            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    // ─── Properties (.properties / .conf) ──────────────────────────────

    /**
     * v2.46 — Tokenizes a Java Properties file line.
     *
     * <p>Recognizes:
     * <ul>
     *   <li>{@code #} and {@code !} comments → COMMENT</li>
     *   <li>{@code key=value} or {@code key:value} — key is PROPERTY,
     *       value is STRING</li>
     *   <li>Escape sequences (backslash-n, backslash-t, backslash-uXXXX) → ESCAPE</li>
     * </ul>
     */
    private StyledLine styleProperties(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        boolean seenEquals = false;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Comment (# or ! at start of line, possibly after whitespace)
            if (!seenEquals && (ch == '#' || ch == '!')) {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            if (Character.isWhitespace(ch)) { pos++; continue; }

            if (!seenEquals && (ch == '=' || ch == ':')) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                seenEquals = true;
                continue;
            }

            if (!seenEquals) {
                // Key
                int end = pos + 1;
                while (end < line.length() && line.charAt(end) != '=' && line.charAt(end) != ':'
                        && !Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, TokenType.PROPERTY));
                pos = end;
                continue;
            }

            // Value — scan until end of line, handle escapes
            if (ch == '\\') {
                int end = pos + 2;
                if (pos + 1 < line.length()) {
                    char next = line.charAt(pos + 1);
                    if (next == 'u' && pos + 5 < line.length()) end = pos + 6;
                }
                spans.add(new LineSpan(pos, Math.min(end, line.length()), TokenType.ESCAPE));
                pos = end;
                continue;
            }

            // String value (rest of line)
            int end = pos + 1;
            while (end < line.length() && line.charAt(end) != '\\') end++;
            spans.add(new LineSpan(pos, end, TokenType.STRING));
            pos = end;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }

    // ─── TOML ──────────────────────────────────────────────────────────

    /**
     * v2.46 — Tokenizes a TOML line.
     *
     * <p>Recognizes:
     * <ul>
     *   <li>{@code #} comments → COMMENT</li>
     *   <li>{@code [section]} and {@code [[array-of-tables]]} headers → TYPE</li>
     *   <li>{@code key = value} — key is PROPERTY, value is STRING/NUMBER/CONSTANT</li>
     *   <li>Strings (basic, literal, multi-line basic, multi-line literal) → STRING</li>
     *   <li>Numbers, booleans, dates → NUMBER/CONSTANT</li>
     * </ul>
     */
    private StyledLine styleToml(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        boolean seenEquals = false;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Comment
            if (ch == '#') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Section header [name] or [[name]]
            if (!seenEquals && ch == '[') {
                int closeCount = 0;
                while (pos < line.length() && line.charAt(pos) == '[') { closeCount++; pos++; }
                int end = pos;
                while (end < line.length() && line.charAt(end) != ']') end++;
                if (end < line.length()) {
                    spans.add(new LineSpan(pos, end, TokenType.TYPE));
                    spans.add(new LineSpan(end, end + closeCount, TokenType.PUNCT));
                    pos = end + closeCount;
                    continue;
                }
            }

            // Equals
            if (ch == '=' && !seenEquals) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                seenEquals = true;
                continue;
            }

            if (!seenEquals) {
                // Key
                int end = pos + 1;
                while (end < line.length() && line.charAt(end) != '=' && !Character.isWhitespace(line.charAt(end))) end++;
                spans.add(new LineSpan(pos, end, TokenType.PROPERTY));
                pos = end;
                continue;
            }

            // Value
            if (ch == '"' || ch == '\'') {
                int end = findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                    continue;
                }
            }

            if (Character.isDigit(ch) || (ch == '-' && pos + 1 < line.length() && Character.isDigit(line.charAt(pos + 1)))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.' || line.charAt(end) == '-' || line.charAt(end) == ':' || line.charAt(end) == 'T' || line.charAt(end) == 'Z')) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            if (Character.isLetter(ch)) {
                int end = pos + 1;
                while (end < line.length() && Character.isLetter(line.charAt(end))) end++;
                String word = line.substring(pos, end);
                TokenType type = TOML_KEYWORDS.contains(word) ? TokenType.CONSTANT : TokenType.STRING;
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }

    // ─── Smali (Android dex bytecode) ──────────────────────────────────

    /**
     * v2.46 — Tokenizes a Smali line.
     *
     * <p>Recognizes:
     * <ul>
     *   <li>Directives ({@code .class}, {@code .method}, {@code .field}, …) → KEYWORD</li>
     *   <li>Registers ({@code p0}-{@code p9}, {@code v0}-{@code v15}) → VARIABLE</li>
     *   <li>Comments ({@code #}) → COMMENT</li>
     *   <li>Strings ({@code "…"}) → STRING</li>
     *   <li>Type descriptors ({@code Ljava/lang/String;}) → TYPE</li>
     *   <li>Hex literals ({@code 0x1A}) → NUMBER</li>
     *   <li>Opcodes (invoke-*, move*, etc.) → FUNC</li>
     * </ul>
     */
    private StyledLine styleSmali(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Comment
            if (ch == '#') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Directive (.class, .method, etc.)
            if (ch == '.') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '-' || line.charAt(end) == '_')) end++;
                String word = line.substring(pos, end);
                if (SMALI_KEYWORDS.contains(word)) {
                    spans.add(new LineSpan(pos, end, TokenType.KEYWORD));
                } else {
                    spans.add(new LineSpan(pos, end, TokenType.ANNOTATION));
                }
                pos = end;
                continue;
            }

            // String literal
            if (ch == '"') {
                int end = findStringEnd(line, pos + 1, '"');
                if (end >= 0) {
                    addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                    continue;
                }
            }

            // Type descriptor (L...; or [L...;)
            if (ch == 'L' || (ch == '[' && pos + 1 < line.length() && line.charAt(pos + 1) == 'L')) {
                int end = line.indexOf(';', pos);
                if (end >= 0 && end - pos < 200) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.TYPE));
                    pos = end + 1;
                    continue;
                }
            }

            // Hex literal (0x...)
            if (ch == '0' && pos + 1 < line.length() && (line.charAt(pos + 1) == 'x' || line.charAt(pos + 1) == 'X')) {
                int end = pos + 2;
                while (end < line.length() && isHexChar(line.charAt(end))) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Number
            if (Character.isDigit(ch) || ch == '-' || ch == '+') {
                int end = pos + 1;
                while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == 't' || line.charAt(end) == 'L' || line.charAt(end) == 'l')) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Register (p0-p9, v0-v15) or opcode
            if (Character.isLetter(ch) || ch == '_') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '-' || line.charAt(end) == '/')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (SMALI_REGISTERS.contains(word)) {
                    type = TokenType.VARIABLE;
                } else if (word.contains("-") || word.endsWith("/")) {
                    // Opcode-like (invoke-direct, move-result-object, etc.)
                    type = TokenType.FUNC;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    type = TokenType.TYPE;
                } else {
                    type = TokenType.PLAIN;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Operators / punctuation
            if (OPERATORS.indexOf(ch) >= 0) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                continue;
            }

            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }
}