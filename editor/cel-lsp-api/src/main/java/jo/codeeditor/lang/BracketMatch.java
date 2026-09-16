package jo.codeeditor.lang;

/**
 * A bracket match result. Returned by {@link Analyzer#computeBracketMatch(int)}.
 *
 * @since v2.0.0
 */
public final class BracketMatch {

    /** The offset of the bracket under the caret (inclusive). */
    public final int bracketOffset;
    /** The offset of the matching bracket (inclusive). */
    public final int matchOffset;
    /** True if the bracket under the caret is an opening bracket. */
    public final boolean isOpening;

    public BracketMatch(int bracketOffset, int matchOffset, boolean isOpening) {
        this.bracketOffset = bracketOffset;
        this.matchOffset = matchOffset;
        this.isOpening = isOpening;
    }
}
