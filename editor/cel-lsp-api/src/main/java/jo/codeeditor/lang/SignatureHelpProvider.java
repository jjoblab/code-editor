package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides signature help (parameter info) when the caret is inside a
 * function call.
 *
 * @since v2.0.0
 */
public interface SignatureHelpProvider {

    /**
     * Returns signature help for the given caret position, or {@code null}
     * if the caret is not inside a function call.
     *
     * @param text   the full document text
     * @param caret  the caret offset
     * @return the signature help, or {@code null}
     */
    SignatureHelp signatureHelp(CharSequence text, int caret);

    /**
     * Returns the characters that trigger signature help (e.g. {@code "("},
     * {@code ","}).
     */
    List<String> getTriggerCharacters();
}
