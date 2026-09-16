package jo.codeeditor.session;

/**
 * A single reversible edit operation.
 * Records the range that was replaced and what it was replaced with.
 
 *
 * @since v1.0.0
*/
public final class EditOp {
    public final int start;
    public final String removed;
    public final String inserted;

    public EditOp(int start, String removed, String inserted) {
        this.start = start;
        this.removed = removed != null ? removed : "";
        this.inserted = inserted != null ? inserted : "";
    }

    /** Returns the end offset of the removed range. */
    public int removedEnd() {
        return start + removed.length();
    }

    /** Returns the inverse operation (swap removed ↔ inserted). */
    public EditOp inverse() {
        return new EditOp(start, inserted, removed);
    }

    @Override
    public String toString() {
        return "EditOp(start=" + start + ", removed=\"" + removed + "\", inserted=\"" + inserted + "\")";
    }
}
