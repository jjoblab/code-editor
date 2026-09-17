package jo.codeeditor.lsp;

import jo.codeeditor.lang.Analyzer;
import jo.codeeditor.lang.provider.CodeActionsProvider;
import jo.codeeditor.lang.provider.CompletionProvider;
import jo.codeeditor.lang.provider.DefinitionProvider;
import jo.codeeditor.lang.provider.TypeDefinitionProvider;
import jo.codeeditor.lang.provider.ImplementationsProvider;
import jo.codeeditor.lang.provider.SuperDefinitionProvider;
import jo.codeeditor.lang.provider.DocumentHighlightProvider;
import jo.codeeditor.lang.provider.Formatter;
import jo.codeeditor.lang.provider.HoverProvider;
import jo.codeeditor.lang.provider.InlayHintProvider;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.provider.RenameProvider;
import jo.codeeditor.lang.provider.ReferencesProvider;
import jo.codeeditor.lang.provider.SignatureHelpProvider;
import jo.codeeditor.lang.provider.SymbolProvider;
import jo.codeeditor.lang.provider.DiagnosticsProvider;

import org.eclipse.lsp4j.TextDocumentSyncKind;

/**
 * Implémentation de {@link Language} qui fait le pont vers un serveur LSP
 * via LSP4J.
 *
 * <p>Créée par {@link LspEditor} à la connexion du serveur. Orchestration
 * pure : chaque provider concret vit dans sa classe collaborative dédiée
 * du package ({@link LspCompletionProvider}, {@link LspHoverProvider},
 * {@link LspRenameProvider}…) et n'est exposé que si le serveur annonce la
 * capability correspondante — null sinon.
 *
 * <p>Sync de document texte : texte complet par défaut ; diff incrémental
 * quand le serveur annonce {@link TextDocumentSyncKind#Incremental}
 * (cf. LspEditor).
 */
public class LspLanguage implements Language {

    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspCompletionProvider completionProvider;
    private final LspHoverProvider hoverProvider;
    private final LspSignatureHelpProvider signatureHelpProvider;
    private final LspSymbolProvider symbolProvider;
    private final LspDiagnosticsProvider diagnosticsProvider;
    /** Récepteur de journal pour exposer l'activité LSP dans le panneau de journal de la démo. */
    private final LspLogSink logSink;

    LspLanguage(LspEditor editor, LanguageServerWrapper wrapper) {
        this.editor = editor;
        this.wrapper = wrapper;
        this.logSink = editor.getProject() != null ? editor.getProject().getLogSink() : null;
        this.completionProvider = new LspCompletionProvider(wrapper, editor, logSink);
        this.hoverProvider = new LspHoverProvider(wrapper, editor, logSink);
        this.signatureHelpProvider = new LspSignatureHelpProvider(wrapper, editor, logSink);
        this.symbolProvider = new LspSymbolProvider(wrapper, editor, logSink);
        this.diagnosticsProvider = new LspDiagnosticsProvider(editor);
        // Journalise les capacités annoncées par le serveur pour que
        // l'utilisateur voie les fonctionnalités réellement prises en charge.
        logCapabilities();
    }

    /** Journalise les capacités du serveur à des fins de diagnostic. */
    private void logCapabilities() {
        org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
        if (caps == null) {
            log("capabilities: NULL — server didn't return any");
            return;
        }
        log("capabilities: completionProvider=" + (caps.getCompletionProvider() != null ? "YES" : "no"));
        log("capabilities: hoverProvider=" + (caps.getHoverProvider() != null ? "YES" : "no"));
        log("capabilities: signatureHelpProvider=" + (caps.getSignatureHelpProvider() != null ? "YES" : "no"));
        log("capabilities: documentSymbolProvider=" + (caps.getDocumentSymbolProvider() != null ? "YES" : "no"));
        log("capabilities: definitionProvider=" + (caps.getDefinitionProvider() != null ? "YES" : "no"));
        log("capabilities: typeDefinitionProvider=" + (caps.getTypeDefinitionProvider() != null ? "YES" : "no"));
        log("capabilities: implementationProvider=" + (caps.getImplementationProvider() != null ? "YES" : "no"));
        log("capabilities: superDefinitionProvider=" + (superDefinitionSupported(caps) ? "YES" : "no"));
        log("capabilities: referencesProvider=" + (caps.getReferencesProvider() != null ? "YES" : "no"));
        log("capabilities: renameProvider=" + (caps.getRenameProvider() != null ? "YES" : "no"));
        log("capabilities: codeActionProvider=" + (caps.getCodeActionProvider() != null ? "YES" : "no"));
        log("capabilities: documentFormattingProvider=" + (caps.getDocumentFormattingProvider() != null ? "YES" : "no"));
        log("capabilities: inlayHintProvider=" + (caps.getInlayHintProvider() != null ? "YES" : "no"));
        log("capabilities: textDocumentSync=" + caps.getTextDocumentSync());
    }

    private void log(String message) {
        android.util.Log.i("LspLang", message);
        if (logSink != null) logSink.log(message);
    }

    @Override
    public Analyzer getAnalyzer() {
        // Le serveur LSP ne fournit pas la coloration syntaxique (c'est le
        // rôle du tokéniseur lexical). Retourne un analyseur no-op —
        // l'EditorView utilise ses propres styledLines depuis EditorSession.
        return new LspNoopAnalyzer();
    }

    @Override
    public CompletionProvider getCompletionProvider() {
        if (wrapper.getCapabilities() == null) return null;
        // CompletionProvider est un Either<Boolean, CompletionOptions> —
        // non-null signifie pris en charge.
        return wrapper.getCapabilities().getCompletionProvider() != null
            ? completionProvider : null;
    }

    @Override
    public HoverProvider getHoverProvider() {
        if (wrapper.getCapabilities() == null) return null;
        // HoverProvider est un Either<Boolean, HoverOptions> — non-null
        // signifie pris en charge.
        return wrapper.getCapabilities().getHoverProvider() != null
            ? hoverProvider : null;
    }

    @Override
    public SignatureHelpProvider getSignatureHelpProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getSignatureHelpProvider() != null
            ? signatureHelpProvider : null;
    }

    @Override
    public SymbolProvider getSymbolProvider() {
        if (wrapper.getCapabilities() == null) return null;
        // DocumentSymbolProvider est un Either<Boolean, ...> — non-null
        // signifie pris en charge.
        return wrapper.getCapabilities().getDocumentSymbolProvider() != null
            ? symbolProvider : null;
    }

    @Override
    public DiagnosticsProvider getDiagnosticsProvider() {
        // Les diagnostics sont poussés par le serveur, pas tirés — mais un
        // provider est exposé pour que l'éditeur puisse interroger l'état
        // courant.
        return diagnosticsProvider;
    }

    // ── Providers complémentaires ───────────────────────────────────

    @Override
    public DefinitionProvider getDefinitionProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getDefinitionProvider() != null
            ? new LspDefinitionProvider(wrapper, editor, logSink) : null;
    }

    /**
     * {@code textDocument/typeDefinition} — la déclaration du TYPE du
     * symbole au caret (section GO TO du menu contextuel unifié de la
     * toolbar de sélection).
     */
    @Override
    public TypeDefinitionProvider getTypeDefinitionProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getTypeDefinitionProvider() != null
            ? new LspTypeDefinitionProvider(wrapper, editor, logSink) : null;
    }

    /**
     * {@code textDocument/implementation} — les héritiers DIRECTS du
     * type en contexte au caret (section GO TO « Implementations » du menu
     * contextuel unifié). Méthode LSP STANDARD — tout serveur qui l'annonce
     * (jdtls, kotlin-language-server…) fonctionne.
     */
    @Override
    public ImplementationsProvider getImplementationsProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getImplementationProvider() != null
            ? new LspImplementationsProvider(wrapper, editor, logSink) : null;
    }

    /**
     * {@code textDocument/superDefinition} — méthode LSP
     * PERSONNALISÉE (aucun équivalent standard pour le GO TO Super) annoncée
     * via la capability {@code experimental.superDefinitionProvider}
     * (pattern jdtls). Seul le serveur :lspjava de CodeIDE l'implémente ;
     * les serveurs tiers ne l'annoncent pas → l'option n'apparaît pas.
     */
    @Override
    public SuperDefinitionProvider getSuperDefinitionProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return superDefinitionSupported(wrapper.getCapabilities())
            ? new LspSuperDefinitionProvider(wrapper, editor, logSink) : null;
    }

    /**
     * True si le serveur annonce la méthode personnalisée superDefinition
     * dans sa capability experimental. La valeur arrive selon le contexte
     * Gson en {@code Map} (LinkedTreeMap) OU en {@code JsonObject} — les
     * deux formes sont acceptées.
     */
    static boolean superDefinitionSupported(
            org.eclipse.lsp4j.ServerCapabilities caps) {
        try {
            Object exp = caps.getExperimental();
            if (exp instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) exp;
                return Boolean.TRUE.equals(m.get("superDefinitionProvider"));
            }
            if (exp instanceof com.google.gson.JsonObject) {
                com.google.gson.JsonObject o = (com.google.gson.JsonObject) exp;
                return o.has("superDefinitionProvider")
                        && o.get("superDefinitionProvider").getAsBoolean();
            }
        } catch (Throwable ignored) {
            // Capability expérimentale absente/mal formée.
        }
        return false;
    }

    /**
     * Câble la capacité LSP {@code textDocument/references} au SPI de
     * l'éditeur : le serveur Java déclare la prise en charge de references
     * et implémente le gestionnaire ; ce slot du SPI rend le chemin joignable
     * de bout en bout.
     */
    @Override
    public ReferencesProvider getReferencesProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getReferencesProvider() != null
            ? new LspReferencesProvider(wrapper, editor, logSink) : null;
    }

    @Override
    public RenameProvider getRenameProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getRenameProvider() != null
            ? new LspRenameProvider(wrapper, editor, logSink) : null;
    }

    @Override
    public CodeActionsProvider getCodeActionsProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getCodeActionProvider() != null
            ? new LspCodeActionsProvider(wrapper, editor, logSink) : null;
    }

    @Override
    public DocumentHighlightProvider getDocumentHighlightProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getDocumentHighlightProvider() != null
            ? new LspDocumentHighlightProvider(wrapper, editor, logSink) : null;
    }

    @Override
    public Formatter getFormatter() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getDocumentFormattingProvider() != null
            ? new LspFormatter(wrapper, editor, logSink) : null;
    }

    /**
     * Câble la capacité LSP {@code textDocument/inlayHint} au SPI de
     * l'éditeur. L'éditeur rend les hints en texte gris estompé en ligne
     * avec le code (ex. « : String » après une déclaration {@code var}).
     */
    @Override
    public InlayHintProvider getInlayHintProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getInlayHintProvider() != null
            ? new LspInlayHintProvider(wrapper, editor, logSink) : null;
    }

    @Override
    public int getInterruptionLevel() {
        return INTERRUPTION_LEVEL_STRONG;
    }

    @Override
    public void destroy() {
        // Le nettoyage est pris en charge par LspEditor.disconnect()
    }
}
