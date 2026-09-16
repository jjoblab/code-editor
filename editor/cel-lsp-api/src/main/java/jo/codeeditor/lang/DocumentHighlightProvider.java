package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides document highlights (occurrences of the symbol under the caret).
 *
 * @since v2.0.0
 */
public interface DocumentHighlightProvider {

    /**
     * Returns the highlight ranges for the symbol at the given offset.
     *
     * @param text   the full document text
     * @param offset the symbol offset
     * @return the list of highlight ranges (may be empty)
     */
    List<DocumentHighlight> highlights(CharSequence text, int offset);
}
