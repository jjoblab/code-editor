package jo.codeeditor.document;

/**
 * Represents a text selection with start and end offsets.
 * <p>
 * When start == end, the selection is a cursor (caret) with no selected text.
 * Start is always &lt;= end (normalized).
 
 *
 * @since v1.0.0
*/
public final class Selection {
    public final int start;
    public final int end;

    public Selection(int start, int end) {
        if (start < 0 || end < 0) {
            throw new IllegalArgumentException("Negative offset: start=" + start + ", end=" + end);
        }
        this.start = Math.min(start, end);
        this.end = Math.max(start, end);
    }

    /** Creates a cursor (no selection) at the given offset. */
    public static Selection cursor(int offset) {
        return new Selection(offset, offset);
    }

    /** Creates a selection covering [start, end). */
    public static Selection range(int start, int end) {
        return new Selection(start, end);
    }

    /** Returns true if this is a cursor (no selected text). */
    public boolean isCursor() {
        return start == end;
    }

    /** Returns the length of the selection. */
    public int length() {
        return end - start;
    }

    /** Returns a new selection shifted by the given delta. */
    public Selection shift(int delta) {
        return new Selection(start + delta, end + delta);
    }

    /**
     * Adjusts this selection after a text replacement.
     *
     * @param editStart  where the edit began
     * @param removedLen how many chars were removed
     * @param insertedLen how many chars were inserted
     * @return adjusted selection
     */
    public Selection adjustForEdit(int editStart, int removedLen, int insertedLen) {
        int delta = insertedLen - removedLen;
        int newStart = adjustOffset(start, editStart, removedLen, insertedLen, delta);
        int newEnd = adjustOffset(end, editStart, removedLen, insertedLen, delta);
        return new Selection(newStart, newEnd);
    }

    private static int adjustOffset(int offset, int editStart, int removedLen, int insertedLen, int delta) {
        if (offset <= editStart) {
            return offset;
        }
        if (offset <= editStart + removedLen) {
            // Inside the removed range — collapse to edit point
            return editStart + insertedLen;
        }
        return offset + delta;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Selection)) return false;
        Selection s = (Selection) o;
        return start == s.start && end == s.end;
    }

    @Override
    public int hashCode() {
        return 31 * start + end;
    }

    @Override
    public String toString() {
        return "Selection(" + start + ", " + end + ")";
    }
}
