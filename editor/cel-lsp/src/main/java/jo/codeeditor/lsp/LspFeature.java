package jo.codeeditor.lsp;

/**
 * Enumeration of LSP features that can be enabled or disabled per server.
 * Used by {@link LanguageServerDefinition#getDisabledFeatures()}.
 *
 * @since v2.2.0
 */
public enum LspFeature {
    COMPLETION,
    HOVER,
    SIGNATURE_HELP,
    DEFINITION,
    DIAGNOSTICS,
    CODE_ACTION,
    DOCUMENT_HIGHLIGHT,
    INLAY_HINT,
    DOCUMENT_SYMBOL,
    RENAME,
    FORMATTING,
    REFERENCES,
    WORKSPACE_SYMBOL
}
