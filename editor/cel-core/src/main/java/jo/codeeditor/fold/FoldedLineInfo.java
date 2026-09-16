package jo.codeeditor.fold;

/**
 * Information about a folded line's visual rendering.
 * When a fold is collapsed, multiple document lines are rendered as one visual line:
 * prefix + placeholder + suffix.
 
 *
 * @since v1.0.0
*/
public final class FoldedLineInfo {
    /** The visual line start in the document. */
    public final int startLine;
    /** The last document line that is part of this fold. */
    public final int endLine;
    /** Column where the placeholder begins (prefix ends here). */
    public final int prefixEnd;
    /** Column where the suffix begins (placeholder ends here). */
    public final int suffixStart;
    /** The placeholder text shown for the collapsed fold. */
    public final String placeholder;

    public FoldedLineInfo(int startLine, int endLine, int prefixEnd, int suffixStart, String placeholder) {
        this.startLine = startLine;
        this.endLine = endLine;
        this.prefixEnd = prefixEnd;
        this.suffixStart = suffixStart;
        this.placeholder = placeholder;
    }

    @Override
    public String toString() {
        return "FoldedLineInfo(line " + startLine + "-" + endLine + ", prefix=" + prefixEnd
            + ", suffix=" + suffixStart + ", ph=\"" + placeholder + "\")";
    }
}
