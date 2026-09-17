package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Tables de mots-clés et de types intégrés du « parser maison », résolues
 * une fois pour toutes au chargement de la classe. Existe séparément des
 * tokéniseurs afin d'isoler la résolution des données de langage (dont la
 * source de vérité est le registre jo.codeeditor.languages) de la logique
 * de scan propre à chaque langage.
 */
public final class KeywordTables {

    // ═════════════════════════════════════════════════════════════════
    // Les tables de mots-clés vivent dans jo.codeeditor.languages
    // (BuiltinLanguages / LanguageProfile) afin que le registre soit
    // l'unique source de vérité des métadonnées de langage. Elles sont
    // aliasées ici car les tokéniseurs par langage (styleJson,
    // stylePython, styleLua, styleShell, styleSql, styleToml, styleSmali)
    // les référencent jeton par jeton.
    // ═════════════════════════════════════════════════════════════════

    /** Résout une table de mots-clés intégrée via le registre
     * (source de vérité unique : les tables vivent dans BuiltinLanguages,
     * exposées comme ensembles de mots-clés des profils).
     */
    private static Set<String> builtinKeywords(String tableId) {
        // Les identifiants de table de cette classe sont les noms canoniques
        // de langage, en majuscules, avec le suffixe _KEYWORDS.
        LanguageProfile p = LanguageRegistry.forName(
                tableId.replace("_KEYWORDS", "").toLowerCase(java.util.Locale.ROOT));
        return p != null ? p.keywords : java.util.Collections.emptySet();
    }

    public static final Set<String> JAVA_KEYWORDS = builtinKeywords("JAVA_KEYWORDS");

    public static final Set<String> JAVA_TYPES = new HashSet<>(Arrays.asList(
        "String", "Object", "Integer", "Long", "Double", "Float", "Boolean",
        "Byte", "Short", "Character", "Void", "Number", "Comparable",
        "Iterable", "Iterator", "Collection", "List", "Set", "Map", "Queue",
        "Deque", "ArrayList", "LinkedList", "HashMap", "TreeMap", "HashSet",
        "TreeSet", "Arrays", "Collections", "Stream", "Optional"
    ));

    public static final Set<String> KT_KEYWORDS = builtinKeywords("KT_KEYWORDS");

    public static final Set<String> XML_KEYWORDS = builtinKeywords("XML_KEYWORDS");

    // ── Mots-clés JSON (true, false, null) ──────────────
    public static final Set<String> JSON_KEYWORDS = builtinKeywords("JSON_KEYWORDS");

    // ── Mots-clés Python ────────────────────────────────
    public static final Set<String> PYTHON_KEYWORDS = builtinKeywords("PYTHON_KEYWORDS");

    // ── Mots-clés JavaScript / TypeScript ───────────────
    public static final Set<String> JS_KEYWORDS = builtinKeywords("JS_KEYWORDS");

    // ── Types intégrés Python ──────────────────────────
    public static final Set<String> PYTHON_TYPES = new HashSet<>(Arrays.asList(
        "int", "float", "str", "bool", "list", "dict", "set", "tuple",
        "bytes", "bytearray", "complex", "frozenset", "range", "type",
        "object", "Exception", "BaseException", "ValueError", "TypeError",
        "KeyError", "IndexError", "AttributeError", "RuntimeError",
        "StopIteration", "GeneratorExit", "Warning", "DeprecationWarning"
    ));

    // ── Mots-clés Lua ────────────────────────────────────
    public static final Set<String> LUA_KEYWORDS = builtinKeywords("LUA_KEYWORDS");

    // ── Fonctions intégrées Lua ─────────────────────────
    public static final Set<String> LUA_BUILTINS = new HashSet<>(Arrays.asList(
        "print", "pairs", "ipairs", "type", "tostring", "tonumber",
        "error", "assert", "pcall", "xpcall", "select", "rawget",
        "rawset", "rawequal", "rawlen", "setmetatable", "getmetatable",
        "require", "dofile", "loadfile", "load", "next", "unpack",
        "string", "table", "math", "io", "os", "coroutine"
    ));

    // ═══════════════════════════════════════════════════════════════════
    // PARSER MAISON : ensembles de mots-clés pour les langages
    // supplémentaires.
    //
    // Ils permettent aux consommateurs de code-editor d'omettre le module
    // optionnel :tm4e tout en conservant une coloration correcte pour tous
    // les langages courants. Les tokéniseurs de ces langages sont plus
    // simples que des grammaires TextMate complètes (pas d'héritage de
    // portées, pas d'injections) mais couvrent les cas courants :
    // mots-clés, types, commentaires, chaînes, nombres, annotations,
    // opérateurs.
    // ═══════════════════════════════════════════════════════════════════

    // ── Mots-clés C (C89 + C99 + C11) — table résidente BuiltinLanguages,
    // aliasée ci-dessous. ────────────
    public static final Set<String> C_KEYWORDS = builtinKeywords("C_KEYWORDS");

    // ── Mots-clés C++ (C++11 + C++14 + C++17 + C++20) ────────────
    public static final Set<String> CPP_KEYWORDS = builtinKeywords("CPP_KEYWORDS");

    // ── Mots-clés Go (Go 1.x) ───────────────────────────────────
    public static final Set<String> GO_KEYWORDS = builtinKeywords("GO_KEYWORDS");

    // ── Mots-clés Rust (Rust 2021) ───────────────────────────────
    public static final Set<String> RUST_KEYWORDS = builtinKeywords("RUST_KEYWORDS");

    // ── Mots-clés Ruby ───────────────────────────────────────────
    public static final Set<String> RUBY_KEYWORDS = builtinKeywords("RUBY_KEYWORDS");

    // ── Mots-clés PHP ───────────────────────────────────────────
    public static final Set<String> PHP_KEYWORDS = builtinKeywords("PHP_KEYWORDS");

    // ── Mots-clés Swift ─────────────────────────────────────────
    public static final Set<String> SWIFT_KEYWORDS = builtinKeywords("SWIFT_KEYWORDS");

    // ── Mots-clés Dart ───────────────────────────────────────────
    public static final Set<String> DART_KEYWORDS = builtinKeywords("DART_KEYWORDS");

    // ── Mots-clés Groovy (sur-ensemble Java + spécifiques Groovy) ──────
    public static final Set<String> GROOVY_KEYWORDS = builtinKeywords("GROOVY_KEYWORDS");

    // ── Mots-clés SQL (ANSI + extensions de BDD courantes) ──────────────
    public static final Set<String> SQL_KEYWORDS = builtinKeywords("SQL_KEYWORDS");

    // ── Mots-clés et intégrés Shell/Bash ─────────────────────────
    public static final Set<String> SHELL_KEYWORDS = builtinKeywords("SHELL_KEYWORDS");

    // ── Règles at CSS ───────────────────────────────────────────
    public static final Set<String> CSS_AT_RULES = new HashSet<>(Arrays.asList(
        "@media", "@import", "@charset", "@font-face", "@page", "@keyframes",
        "@supports", "@namespace", "@document", "@viewport", "@counter-style",
        "@font-feature-values", "@layer", "@scope", "@container", "@starting-style"
    ));

    // ── Propriétés CSS (sous-ensemble courant) ────────────────────
    public static final Set<String> CSS_PROPERTIES = new HashSet<>(Arrays.asList(
        // Modèle de boîte
        "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
        "padding", "padding-top", "padding-right", "padding-bottom", "padding-left",
        "border", "border-top", "border-right", "border-bottom", "border-left",
        "border-width", "border-style", "border-color", "border-radius",
        "border-collapse", "border-spacing", "border-image",
        // Typographie
        "color", "font", "font-family", "font-size", "font-style", "font-weight",
        "font-variant", "font-feature-settings", "line-height", "letter-spacing",
        "word-spacing", "text-align", "text-decoration", "text-transform",
        "text-indent", "text-shadow", "text-overflow", "text-rendering",
        "direction", "unicode-bidi", "white-space", "word-break", "word-wrap",
        "vertical-align",
        // Arrière-plan
        "background", "background-color", "background-image", "background-repeat",
        "background-position", "background-size", "background-origin",
        "background-clip", "background-attachment",
        // Dimensionnement
        "width", "height", "min-width", "max-width", "min-height", "max-height",
        "box-sizing", "aspect-ratio", "object-fit", "object-position",
        // Mise en page
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
        // Transitions et animations
        "transition", "transition-property", "transition-duration",
        "transition-timing-function", "transition-delay", "animation",
        "animation-name", "animation-duration", "animation-timing-function",
        "animation-delay", "animation-iteration-count", "animation-direction",
        "animation-fill-mode", "animation-play-state",
        // Transformation
        "transform", "transform-origin", "transform-style", "perspective",
        "perspective-origin", "backface-visibility",
        // Divers
        "content", "quotes", "counter-reset", "counter-increment", "cursor",
        "user-select", "pointer-events", "touch-action", "filter", "backdrop-filter",
        "mix-blend-mode", "isolation", "will-change", "list-style",
        "list-style-type", "list-style-position", "list-style-image",
        "table-layout", "caption-side", "empty-cells", "speak", "scroll-behavior"
    ));

    // ── Mots-clés Smali (bytecode dex Android) ──────────────────
    public static final Set<String> SMALI_KEYWORDS = builtinKeywords("SMALI_KEYWORDS");

    // ── Registres Smali (p/v) ─────────────────────────────
    public static final Set<String> SMALI_REGISTERS = new HashSet<>(Arrays.asList(
        "p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7", "p8", "p9",
        "v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9",
        "v10", "v11", "v12", "v13", "v14", "v15"
    ));

    // ── Mots-clés TOML (aucun, mais réservé) ──────────────────────
    public static final Set<String> TOML_KEYWORDS = builtinKeywords("TOML_KEYWORDS");

    // ── Mots-clés Properties (aucun, mais réservé) ───────────────
    public static final Set<String> PROPERTIES_KEYWORDS = builtinKeywords("PROPERTIES_KEYWORDS");

    private KeywordTables() {}
}
