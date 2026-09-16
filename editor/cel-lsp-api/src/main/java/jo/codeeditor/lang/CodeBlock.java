package jo.codeeditor.lang;

/**
 * A folding region (code block). Returned by {@link Analyzer#computeBlocks()}.
 *
 * @since v2.0.0
 */
public final class CodeBlock {

    /** Start offset of the block (inclusive). */
    public final int start;
    /** End offset of the block (exclusive). */
    public final int end;
    /** Placeholder text shown when the block is collapsed (e.g. "{…}"). */
    public final String placeholder;
    /** Block kind: "block", "comment", "imports", "region", etc. */
    public final String kind;
    /** Whether the block is currently collapsed. */
    public boolean collapsed;

    public CodeBlock(int start, int end, String placeholder, String kind, boolean collapsed) {
        this.start = start;
        this.end = end;
        this.placeholder = placeholder != null ? placeholder : "…";
        this.kind = kind != null ? kind : "block";
        this.collapsed = collapsed;
    }

    public CodeBlock(int start, int end) {
        this(start, end, "…", "block", false);
    }
}
