package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides document symbols for go-to-symbol (outline view).
 *
 * @since v2.0.0
 */
public interface SymbolProvider {

    /**
     * Returns all symbols in the document.
     *
     * @param text the full document text
     * @return the list of symbols (may be empty)
     */
    List<Symbol> symbols(CharSequence text);
}
