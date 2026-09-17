package jo.codeeditor.lang;

import jo.codeeditor.lang.provider.CodeActionsProvider;
import jo.codeeditor.lang.provider.CompletionProvider;
import jo.codeeditor.lang.provider.DefinitionProvider;
import jo.codeeditor.lang.provider.DiagnosticsProvider;
import jo.codeeditor.lang.provider.DocumentHighlightProvider;
import jo.codeeditor.lang.provider.Formatter;
import jo.codeeditor.lang.provider.HoverProvider;
import jo.codeeditor.lang.provider.ImplementationsProvider;
import jo.codeeditor.lang.provider.InlayHintProvider;
import jo.codeeditor.lang.provider.ReferencesProvider;
import jo.codeeditor.lang.provider.RenameProvider;
import jo.codeeditor.lang.provider.SignatureHelpProvider;
import jo.codeeditor.lang.provider.SuperDefinitionProvider;
import jo.codeeditor.lang.provider.SymbolProvider;
import jo.codeeditor.lang.provider.TypeDefinitionProvider;
import jo.codeeditor.lang.provider.ViewZoneProvider;
import jo.codeeditor.lang.model.CodeBlock;

/**
 * {@link Language} sans effet, utilisée par défaut quand aucun vrai langage
 * n'est installé. Tous les providers retournent {@code null} — l'éditeur
 * affiche la coloration lexicale de son propre tokenizer, mais aucune
 * complétion, hover, aide de signature, diagnostic, etc.
 *
 * <p>Garantit qu'un changement de langage ne laisse pas actifs des
 * resolveurs obsolètes du langage précédent (ex. passer de Java à XML
 * sans que les resolveurs Java restent branchés sur le XML).</p>
 */
public class EmptyLanguage implements Language {

    private static final EmptyAnalyzer ANALYZER = new EmptyAnalyzer();

    @Override
    public Analyzer getAnalyzer() {
        return ANALYZER;
    }

    // Tous les providers retournent null — aucune intelligence de langage.
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

    /** Analyseur sans effet — styledLine retourne null (l'éditeur utilise son propre tokenizer). */
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
