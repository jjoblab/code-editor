package jo.codeeditor.lang;

import java.util.List;

/**
 * Provides go-to-implementations targets: the direct inheritors (subtypes)
 * of the type at a given offset.
 *
 * <p>Nourrit la section GO TO du menu contextuel unifié de la toolbar de
 * sélection (portage du NavMenu de CodeAssist — « Implementations », le
 * {@code CaIcons.layers} de {@code implementationTargets}).</p>
 *
 * @since v2.37
 */
public interface ImplementationsProvider {

    /**
     * Returns the direct-inheritor targets of the type in context at the
     * given offset (a type reference, or the caret's enclosing type). If
     * there's a single target, the editor navigates directly. If there are
     * multiple, it shows a picker.
     *
     * @param text   the full document text
     * @param offset the caret offset
     * @return the list of inheritor targets (may be empty)
     */
    List<DefinitionLocation> implementations(CharSequence text, int offset);
}
