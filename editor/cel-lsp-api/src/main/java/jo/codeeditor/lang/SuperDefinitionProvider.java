package jo.codeeditor.lang;

import java.util.List;

/**
 * Provides go-to-super targets: for an overriding member at a given offset,
 * the same-named member in each supertype; otherwise the direct supertypes
 * of the type in context.
 *
 * <p>Nourrit la section GO TO du menu contextuel unifié de la toolbar de
 * sélection (portage du NavMenu de CodeAssist — « Super », le
 * {@code CaIcons.pin} de {@code superTargets}).</p>
 *
 * @since v2.37
 */
public interface SuperDefinitionProvider {

    /**
     * Returns the super targets at the given offset. If there's a single
     * target, the editor navigates directly. If there are multiple, it
     * shows a picker.
     *
     * @param text   the full document text
     * @param offset the caret offset
     * @return the list of super targets (may be empty)
     */
    List<DefinitionLocation> superTargets(CharSequence text, int offset);
}
