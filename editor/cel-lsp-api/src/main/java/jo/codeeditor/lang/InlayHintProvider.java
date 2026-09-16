package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides inlay hints (phantom type annotations, parameter names).
 *
 * @since v2.0.0
 */
public interface InlayHintProvider {

    /**
     * Returns the inlay hints for the given line range.
     *
     * @param text       the full document text
     * @param startLine  the first line (0-based, inclusive)
     * @param endLine    the last line (0-based, inclusive)
     * @return the list of inlay hints (may be empty)
     */
    List<InlayHint> inlayHints(CharSequence text, int startLine, int endLine);
}
