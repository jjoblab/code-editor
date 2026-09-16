package jo.codeeditor.lang;

/**
 * An inlay hint (phantom text inserted at a column). Returned by
 * {@link InlayHintProvider}.
 *
 * @since v2.0.0
 */
public final class InlayHint {

    /** The offset where the hint is inserted. */
    public final int offset;
    /** The hint text (e.g. ": String"). */
    public final String text;
    /** The hint kind: "type", "parameter", "decorator". */
    public final String kind;

    public InlayHint(int offset, String text, String kind) {
        this.offset = offset;
        this.text = text != null ? text : "";
        this.kind = kind != null ? kind : "type";
    }
}
