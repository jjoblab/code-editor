package jo.codeeditor.lsp;

/**
 * Fonctionnalités LSP pouvant être activées ou désactivées par serveur.
 * Utilisée par {@link LanguageServerDefinition#getDisabledFeatures()}.
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
