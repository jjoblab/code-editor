package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides find-references targets for the symbol at a given offset.
 *
 * <p>A "reference" is any usage of the symbol — the symbol's own declaration
 * is NOT included (that's the job of {@link DefinitionProvider}). The
 * editor typically shows the results as a pickable list, optionally
 * cross-file if the language server reports them.</p>
 *
 * <p>SPI slot added in v3.33.10 to wire the LSP {@code textDocument/references}
 * capability that was previously declared by the server but unreachable from
 * the editor (see lsp-provider-verification.md issue I-7).</p>
 *
 * @since v3.33.10
 */
public interface ReferencesProvider {

    /**
     * Returns the references for the symbol at the given offset.
     *
     * @param text   the full document text
     * @param offset the symbol offset
     * @return the list of reference locations (may be empty)
     */
    List<DefinitionLocation> references(CharSequence text, int offset);
}
