package jo.codeeditor.lang;


/**
 * The top-level Language SPI (Service Provider Interface). A {@code Language}
 * instance is plugged into an {@link jo.codeeditor.view.EditorView} via
 * {@code setLanguage(Language)} and provides all the language-intelligence
 * features the editor needs: syntax highlighting, completion, hover,
 * signature help, diagnostics, code actions, etc.
 *
 * <p>Every provider method returns {@code null} by default — a language
 * plugin only implements the features it supports. The editor checks for
 * null before rendering the corresponding UI.
 *
 * <p><b>v2.0.0 breaking change</b>: this SPI replaces the v1.x per-feature
 * resolver interfaces ({@code CompletionProvider}, {@code SignatureHelpResolver},
 * etc.). The old resolvers are kept as deprecated wrappers that delegate
 * to a {@link Language} instance.
 *
 * @since v2.0.0
 */
public interface Language {

    /** Strong interruption: {@code Thread.interrupt()} + throw. */
    int INTERRUPTION_LEVEL_STRONG = 0;
    /** Slight interruption: throw only. */
    int INTERRUPTION_LEVEL_SLIGHT = 1;
    /** No interruption: throw from ContentReference only. */
    int INTERRUPTION_LEVEL_NONE = 2;

    /**
     * Returns the {@link Analyzer} that provides incremental syntax
     * highlighting, code blocks, and bracket matching. Must never be null —
     * a language without an analyzer is useless.
     */
    Analyzer getAnalyzer();

    /**
     * Returns the {@link CompletionProvider}, or {@code null} if this
     * language doesn't support auto-completion.
     */
    default CompletionProvider getCompletionProvider() { return null; }

    /**
     * Returns the {@link HoverProvider}, or {@code null} if hover/quick-doc
     * is not supported.
     */
    default HoverProvider getHoverProvider() { return null; }

    /**
     * Returns the {@link SignatureHelpProvider}, or {@code null} if
     * signature help is not supported.
     */
    default SignatureHelpProvider getSignatureHelpProvider() { return null; }

    /**
     * Returns the {@link DefinitionProvider}, or {@code null} if
     * go-to-definition is not supported.
     */
    default DefinitionProvider getDefinitionProvider() { return null; }

    /**
     * Returns the {@link TypeDefinitionProvider}, or {@code null} if
     * go-to-type-declaration (the type of the symbol at the caret) is not
     * supported.
     *
     * @since v2.36
     */
    default TypeDefinitionProvider getTypeDefinitionProvider() { return null; }

    /**
     * Returns the {@link ImplementationsProvider}, or {@code null} if
     * go-to-implementations (the direct inheritors of the type at the
     * caret) is not supported.
     *
     * @since v2.37
     */
    default ImplementationsProvider getImplementationsProvider() { return null; }

    /**
     * Returns the {@link SuperDefinitionProvider}, or {@code null} if
     * go-to-super (the overridden member / the supertypes of the type in
     * context) is not supported.
     *
     * @since v2.37
     */
    default SuperDefinitionProvider getSuperDefinitionProvider() { return null; }

    /**
     * Returns the {@link ReferencesProvider}, or {@code null} if
     * find-references is not supported.
     *
     * @since v3.33.10
     */
    default ReferencesProvider getReferencesProvider() { return null; }

    /**
     * Returns the {@link DiagnosticsProvider}, or {@code null} if
     * diagnostics (errors/warnings) are not supported.
     */
    default DiagnosticsProvider getDiagnosticsProvider() { return null; }

    /**
     * Returns the {@link CodeActionsProvider}, or {@code null} if code
     * actions (quick-fixes, refactors) are not supported.
     */
    default CodeActionsProvider getCodeActionsProvider() { return null; }

    /**
     * Returns the {@link DocumentHighlightProvider}, or {@code null} if
     * document highlight (occurrences of the symbol under the caret) is
     * not supported.
     */
    default DocumentHighlightProvider getDocumentHighlightProvider() { return null; }

    /**
     * Returns the {@link InlayHintProvider}, or {@code null} if inlay
     * hints (phantom type annotations) are not supported.
     */
    default InlayHintProvider getInlayHintProvider() { return null; }

    /**
     * Returns the {@link ViewZoneProvider}, or {@code null} if view zones
     * (inline UI gaps for refactors, inline type hints) are not supported.
     */
    default ViewZoneProvider getViewZoneProvider() { return null; }

    /**
     * Returns the {@link Formatter}, or {@code null} if code formatting
     * is not supported.
     */
    default Formatter getFormatter() { return null; }

    /**
     * Returns the {@link SymbolProvider}, or {@code null} if go-to-symbol
     * (document symbol outline) is not supported.
     */
    default SymbolProvider getSymbolProvider() { return null; }

    /**
     * Returns the {@link RenameProvider}, or {@code null} if rename is
     * not supported.
     */
    default RenameProvider getRenameProvider() { return null; }

    /**
     * Returns how aggressively the editor may cancel in-flight completion
     * requests. See {@link #INTERRUPTION_LEVEL_STRONG} etc.
     */
    int getInterruptionLevel();

    /**
     * Called when the language is detached from the editor (e.g. when
     * {@code setLanguage} is called with a different language). Release
     * any resources (parsers, threads, connections).
     */
    void destroy();
}
