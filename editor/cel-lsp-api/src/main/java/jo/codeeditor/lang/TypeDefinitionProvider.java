package jo.codeeditor.lang;

import java.util.List;

/**
 * Provides go-to-type-declaration targets: the declaration of the TYPE of the
 * symbol at a given offset ({@code Foo x = ...} → {@code class Foo}).
 *
 * <p>Nourrit la section GO TO du menu contextuel unifié de la toolbar de
 * sélection (portage du NavMenu de CodeAssist — « Type declaration »).</p>
 *
 * @since v2.36
 */
public interface TypeDefinitionProvider {

    /**
     * Returns the declaration targets of the type of the symbol at the given
     * offset. If there's a single target, the editor navigates directly. If
     * there are multiple, it shows a picker.
     *
     * @param text   the full document text
     * @param offset the symbol offset
     * @return the list of declaration targets (may be empty)
     */
    List<DefinitionLocation> typeDefinitions(CharSequence text, int offset);
}
