package jo.codeeditor.highlight;

/**
 * Lexer state constants for cross-line parsing.
 
 *
 * @since v1.0.0
*/
public final class LexState {
    /** Normal code. */
    public static final int NORMAL = 0;
    /** Inside a block comment (\/\* ... \*\/). */
    public static final int BLOCK_COMMENT = 1;
    /** Inside an XML/HTML string that spans lines. */
    public static final int XML_STRING = 2;
    /** Inside a Kotlin raw string (triple-quote). */
    public static final int KT_RAW_STRING = 3;
    /** v3.3.3: Inside an XML tag (between {@code <} and {@code >}), so that
     *  multi-line tags (one attribute per line) get proper attribute coloring. */
    public static final int XML_TAG = 4;
    // ── v2.46 additions — parser maison (more languages) ─────────────
    /** Inside a CSS block comment ({@code /* ... *\/}). */
    public static final int CSS_COMMENT = 5;
    /** Inside a CSS string that spans lines (rare but possible). */
    public static final int CSS_STRING = 6;
    /** Inside a Python triple-quoted string ( reused for bash heredoc). */
    public static final int BASH_HEREDOC = 7;
    /** Inside an HTML &lt;script&gt; body (rendered as JS). */
    public static final int HTML_SCRIPT = 8;
    /** Inside an HTML &lt;style&gt; body (rendered as CSS). */
    public static final int HTML_STYLE = 9;
    /** Inside a YAML block scalar (| or >). */
    public static final int YAML_BLOCK_SCALAR = 10;
    /** Inside a C triple-quote (unused in C but reserved for future). */
    public static final int C_RAW_STRING = 11;
    /** Inside a Shell single-quoted literal ('...' spanning lines). */
    public static final int SHELL_SINGLE_QUOTE = 12;
    /** Inside a PHP heredoc ({@code <<<EOT ... EOT;}). */
    public static final int PHP_HEREDOC = 13;

    private LexState() {}
}
