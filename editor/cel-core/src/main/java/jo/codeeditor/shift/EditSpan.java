package jo.codeeditor.shift;

/**
 * Represents an edit span for diagnostic/token shifting.
 * Describes a text edit: removed [start, start+removed) and inserted added chars.
 
 *
 * @since v1.0.0
*/
public final class EditSpan {
    public final int start;
    public final int removed;
    public final int added;

    public EditSpan(int start, int removed, int added) {
        this.start = start;
        this.removed = removed;
        this.added = added;
    }

    /** Net change in document length. */
    public int delta() {
        return added - removed;
    }

    /** End of the removed range. */
    public int end() {
        return start + removed;
    }

    @Override
    public String toString() {
        return "EditSpan(start=" + start + ", removed=" + removed + ", added=" + added + ")";
    }
}
