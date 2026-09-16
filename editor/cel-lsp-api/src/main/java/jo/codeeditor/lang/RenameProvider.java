package jo.codeeditor.lang;


/**
 * Provides rename (rename all occurrences of the symbol at the caret).
 *
 * @since v2.0.0
 */
public interface RenameProvider {

    /**
     * Computes the rename edits for the symbol at the given offset.
     *
     * @param text      the full document text
     * @param offset    the symbol offset
     * @param newName   the new name
     * @return the rename result (list of edits), or {@code null} if rename
     *         is not available at this offset
     */
    RenameResult rename(CharSequence text, int offset, String newName);
}
