package jo.codeeditor.lang;

/**
 * A document highlight range. Returned by {@link DocumentHighlightProvider}.
 *
 * @since v2.0.0
 */
public final class DocumentHighlight {

    /** Start offset (inclusive). */
    public final int start;
    /** End offset (exclusive). */
    public final int end;
    /** Kind: "read", "write", "text". */
    public final String kind;

    public DocumentHighlight(int start, int end, String kind) {
        this.start = start;
        this.end = end;
        this.kind = kind != null ? kind : "text";
    }
}
