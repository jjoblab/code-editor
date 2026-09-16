package jo.codeeditor.edit;

import java.util.Locale;

/**
 * v3.35.0 — Language-driven comment syntax (roadmap item 1, bug B11).
 *
 * <p>Before v3.35.0, {@code EditorSession.toggleLineComment()} hardcoded
 * {@code "//"} and {@code toggleBlockComment()} hardcoded the C-style pair,
 * which produced WRONG comment prefixes for XML, Python, Markdown, Lua, SQL,
 * shell… (e.g. {@code // def foo():} in a Python file). This class is the
 * port of CodeAssist v3.20's {@code EditorLanguageProfile} comment fields:
 * a small immutable profile resolved from the session's language id.</p>
 *
 * <p>Resolution rules (aligned with {@code SyntaxHighlighter}'s language
 * table, including short aliases like {@code "py"}, {@code "js"}, {@code "ts"},
 * {@code "rs"}, {@code "rb"}, {@code "sh"}):</p>
 * <ul>
 *   <li>C-family languages → {@code //} + {@code /* *&#47;} (unchanged default);</li>
 *   <li>hash languages (Python, Ruby, shell, TOML, properties, smali, YAML)
 *       → {@code #} and (usually) no block comment;</li>
 *   <li>XML/HTML/Markdown → no line comment, block {@code <!-- -->} —
 *       {@code toggleLineComment} then falls back to wrapping each line in
 *       the block pair (VS Code behavior for XML);</li>
 *   <li>Lua → {@code --} + {@code --[[ ]]}; SQL → {@code --} + {@code /* *&#47;};</li>
 *   <li>JSON → no comment syntax at all: both toggles are safe no-ops.</li>
 * </ul>
 *
 * <p>Unknown or {@code null} language ids resolve to the C-family default
 * so existing callers (Java-first) keep the pre-v3.35.0 behavior byte for
 * byte.</p>
 *
 * <p>Hosts with exotic languages can bypass language resolution entirely via
 * {@code EditorSession.setCommentSyntax(CommentSyntax)}.</p>
 *
 * @since v3.35.0
 */
public final class CommentSyntax {

    /** C-family default: {@code //} + {@code /* *&#47;}. */
    public static final CommentSyntax C_STYLE =
            new CommentSyntax("//", "/*", "*/");

    /** JSON (and any format with no comment syntax): all toggles are no-ops. */
    public static final CommentSyntax NONE =
            new CommentSyntax(null, null, null);

    /** Line comment prefix, or null when the language has none. */
    public final String lineComment;
    /** Block comment opener, or null when the language has none. */
    public final String blockStart;
    /** Block comment closer, or null when the language has none. */
    public final String blockEnd;

    public CommentSyntax(String lineComment, String blockStart, String blockEnd) {
        this.lineComment = lineComment;
        this.blockStart = blockStart;
        this.blockEnd = blockEnd;
    }

    /** True when {@link #toggleLineComment} can insert something. */
    public boolean hasLine() {
        return lineComment != null;
    }

    /** True when a block comment pair exists. */
    public boolean hasBlock() {
        return blockStart != null && blockEnd != null;
    }

    /**
     * Resolves the comment syntax for the given language id.
     *
     * <p>The id is normalized (trimmed, lowercased with {@code Locale.ROOT});
     * unknown ids fall back to {@link #C_STYLE} so the Java-first history of
     * this editor is preserved.</p>
     */
    public static CommentSyntax forLanguage(String language) {
        if (language == null) return C_STYLE;
        String lang = language.trim().toLowerCase(Locale.ROOT);
        if (lang.isEmpty()) return C_STYLE;
        switch (lang) {
            // ── Hash languages ─────────────────────────────────────
            case "python": case "py":
                return new CommentSyntax("#", null, null);
            case "ruby": case "rb":
                return new CommentSyntax("#", null, null);
            case "shell": case "bash": case "sh":
                return new CommentSyntax("#", null, null);
            case "toml":
                return new CommentSyntax("#", null, null);
            case "properties": case "ini":
                return new CommentSyntax("#", null, null);
            case "smali":
                return new CommentSyntax("#", null, null);
            case "yaml": case "yml":
                return new CommentSyntax("#", null, null);

            // ── XML family: block only (VS Code-style line fallback) ──
            case "xml": case "html": case "htm": case "svg":
                return new CommentSyntax(null, "<!--", "-->");
            case "markdown": case "md":
                return new CommentSyntax(null, "<!--", "-->");

            // ── No comments at all ──────────────────────────────────
            case "json":
                return NONE;

            // ── Lua ─────────────────────────────────────────────────
            case "lua":
                return new CommentSyntax("--", "--[[", "]]");

            // ── SQL: dash line + C-style block ─────────────────────
            case "sql":
                return new CommentSyntax("--", "/*", "*/");

            // ── C-family (default) ─────────────────────────────────
            case "java": case "kotlin": case "kt":
            case "c": case "h":
            case "cpp": case "cc": case "hpp": case "cxx":
            case "go": case "rust": case "rs":
            case "javascript": case "js":
            case "typescript": case "ts":
            case "php": case "swift": case "dart":
            case "groovy": case "gradle":
            case "scala": case "css": case "scss":
            default:
                return C_STYLE;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CommentSyntax)) return false;
        CommentSyntax that = (CommentSyntax) o;
        return eq(lineComment, that.lineComment)
                && eq(blockStart, that.blockStart)
                && eq(blockEnd, that.blockEnd);
    }

    @Override
    public int hashCode() {
        int h = 7;
        h = 31 * h + (lineComment == null ? 0 : lineComment.hashCode());
        h = 31 * h + (blockStart == null ? 0 : blockStart.hashCode());
        h = 31 * h + (blockEnd == null ? 0 : blockEnd.hashCode());
        return h;
    }

    @Override
    public String toString() {
        return "CommentSyntax(line=" + lineComment
                + ", block=" + blockStart + "…" + blockEnd + ")";
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
