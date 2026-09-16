package jo.codeeditor.lang;


/**
 * Provides code formatting for the whole document or a range.
 *
 * @since v2.0.0
 */
public interface Formatter {

    /**
     * Formats the given text and returns the formatted result.
     *
     * @param text        the full document text
     * @param startOffset the format range start (0 for whole doc)
     * @param endOffset   the format range end (text.length() for whole doc)
     * @return the formatted text
     */
    CharSequence format(CharSequence text, int startOffset, int endOffset);
}
