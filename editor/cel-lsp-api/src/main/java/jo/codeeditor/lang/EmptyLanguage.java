package jo.codeeditor.lang;

/**
 * A no-op {@link Language} used as the default when no real language is
 * set. All providers return null — the editor shows plain syntax
 * highlighting (from the lexical tokenizer) but no completion, hover,
 * signature help, diagnostics, etc.
 *
 * <p>This is the equivalent of Sora Editor's {@code EmptyLanguage} —
 * it ensures that switching from Java to XML doesn't leave stale Java
 * resolvers active on XML code.
 *
 * @since v3.2.0
 */
public class EmptyLanguage implements Language {

    private static final EmptyAnalyzer ANALYZER = new EmptyAnalyzer();

    @Override
    public Analyzer getAnalyzer() {
        return ANALYZER;
    }

    // All providers return null — no language intelligence.
    @Override public CompletionProvider getCompletionProvider() { return null; }
    @Override public HoverProvider getHoverProvider() { return null; }
    @Override public SignatureHelpProvider getSignatureHelpProvider() { return null; }
    @Override public DefinitionProvider getDefinitionProvider() { return null; }
    @Override public ReferencesProvider getReferencesProvider() { return null; }
    @Override public DiagnosticsProvider getDiagnosticsProvider() { return null; }
    @Override public CodeActionsProvider getCodeActionsProvider() { return null; }
    @Override public DocumentHighlightProvider getDocumentHighlightProvider() { return null; }
    @Override public InlayHintProvider getInlayHintProvider() { return null; }
    @Override public ViewZoneProvider getViewZoneProvider() { return null; }
    @Override public Formatter getFormatter() { return null; }
    @Override public SymbolProvider getSymbolProvider() { return null; }
    @Override public RenameProvider getRenameProvider() { return null; }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_NONE;
    }

    @Override
    public void destroy() {}

    /** A no-op analyzer — returns null for styledLine (editor uses its own). */
    private static class EmptyAnalyzer implements Analyzer {
        @Override public void setReceiver(StyleReceiver receiver) {}
        @Override public void onReplace(CharSequence text, int s, int e, CharSequence i) {}
        @Override public void reset(CharSequence text) {}
        @Override public jo.codeeditor.highlight.StyledLine styledLine(int line) { return null; }
        @Override public java.util.List<CodeBlock> computeBlocks() { return java.util.Collections.emptyList(); }
        @Override public BracketMatch computeBracketMatch(int offset) { return null; }
        @Override public void destroy() {}
    }
}
