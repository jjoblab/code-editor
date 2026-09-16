package jo.codeeditor.lang;

/**
 * A single text edit (replace [start, end) with {@code newText}).
 * Used by {@link RenameResult} and other edit-producing operations.
 *
 * @since v2.0.0
 */
public final class TextEdit {

    /** Start offset (inclusive). */
    public final int start;
    /** End offset (exclusive). */
    public final int end;
    /** The text to insert. */
    public final String newText;

    public TextEdit(int start, int end, String newText) {
        this.start = start;
        this.end = end;
        this.newText = newText != null ? newText : "";
    }
}
