package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides code actions (quick-fixes, refactors) for the given line.
 *
 * @since v2.0.0
 */
public interface CodeActionsProvider {

    /**
     * Returns the code actions available for the given line.
     *
     * @param text the full document text
     * @param line the 0-based line index
     * @return the list of code actions (may be empty)
     */
    List<CodeAction> codeActions(CharSequence text, int line);
}
