package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides go-to-definition targets for the symbol at a given offset.
 *
 * @since v2.0.0
 */
public interface DefinitionProvider {

    /**
     * Returns the definition targets for the symbol at the given offset.
     * If there's a single target, the editor navigates directly. If there
     * are multiple, it shows a picker.
     *
     * @param text   the full document text
     * @param offset the symbol offset
     * @return the list of definition targets (may be empty)
     */
    List<DefinitionLocation> definitions(CharSequence text, int offset);
}
