package jo.codeeditor.lsp;

import jo.codeeditor.lang.Analyzer;
import jo.codeeditor.lang.CodeAction;
import jo.codeeditor.lang.CodeActionsProvider;
import jo.codeeditor.lang.CompletionItem;
import jo.codeeditor.lang.CompletionProvider;
import jo.codeeditor.lang.CompletionPublisher;
import jo.codeeditor.lang.DefinitionLocation;
import jo.codeeditor.lang.DefinitionProvider;
import jo.codeeditor.lang.TypeDefinitionProvider;
import jo.codeeditor.lang.ImplementationsProvider;
import jo.codeeditor.lang.SuperDefinitionProvider;
import jo.codeeditor.lang.DocumentHighlight;
import jo.codeeditor.lang.DocumentHighlightProvider;
import jo.codeeditor.lang.Formatter;
import jo.codeeditor.lang.HoverContent;
import jo.codeeditor.lang.HoverProvider;
import jo.codeeditor.lang.InlayHint;
import jo.codeeditor.lang.InlayHintProvider;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.RenameProvider;
import jo.codeeditor.lang.ReferencesProvider;
import jo.codeeditor.lang.RenameResult;
import jo.codeeditor.lang.SignatureHelp;
import jo.codeeditor.lang.SignatureHelpProvider;
import jo.codeeditor.lang.SymbolProvider;
import jo.codeeditor.lang.DiagnosticsProvider;
import jo.codeeditor.lang.Diagnostic;

import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.SymbolKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link Language} implementation that bridges to an LSP server via LSP4J.
 *
 * <p>Created by {@link LspEditor} when the server connects. Implements the
 * providers that the server supports (completion, hover, signature help,
 * symbols, diagnostics) and returns null for unsupported ones.
 *
 * <p>Text document sync: uses {@link TextDocumentSyncKind#Full} for simplicity
 * (sends the whole text on each change). Incremental sync is planned for v2.3.0.
 *
 * @since v2.2.0
 */
public class LspLanguage implements Language {

    private final LanguageServerWrapper wrapper;
    private final LspEditor editor;
    private final LspCompletionProvider completionProvider;
    private final LspHoverProvider hoverProvider;
    private final LspSignatureHelpProvider signatureHelpProvider;
    private final LspSymbolProvider symbolProvider;
    private final LspDiagnosticsProvider diagnosticsProvider;
    /** v3.3.3: Log sink for surfacing LSP activity in the demo's log panel. */
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
        // v3.3.3: Log the server's advertised capabilities so the user
        // can see what features the server actually supports.
        logCapabilities();
    }

    /** v3.3.3: Logs the server's capabilities for diagnostics. */
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
        // The LSP server doesn't provide syntax highlighting (that's the
        // lexical tokenizer's job). Return a no-op analyzer — the EditorView
        // uses its own styledLines from EditorSession.
        return new LspNoopAnalyzer();
    }

    @Override
    public CompletionProvider getCompletionProvider() {
        if (wrapper.getCapabilities() == null) return null;
        // CompletionProvider is Either<Boolean, CompletionOptions> — non-null means supported.
        return wrapper.getCapabilities().getCompletionProvider() != null
            ? completionProvider : null;
    }

    @Override
    public HoverProvider getHoverProvider() {
        if (wrapper.getCapabilities() == null) return null;
        // HoverProvider is Either<Boolean, HoverOptions> — non-null means supported.
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
        // DocumentSymbolProvider is Either<Boolean, ...> — non-null means supported.
        return wrapper.getCapabilities().getDocumentSymbolProvider() != null
            ? symbolProvider : null;
    }

    @Override
    public DiagnosticsProvider getDiagnosticsProvider() {
        // Diagnostics are pushed by the server, not pulled — but we expose
        // a provider so the editor can query the current state.
        return diagnosticsProvider;
    }

    // ── v3.4.0: Missing providers ──────────────────────────────

    @Override
    public DefinitionProvider getDefinitionProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getDefinitionProvider() != null
            ? new LspDefinitionProvider(wrapper, editor, logSink) : null;
    }

    /**
     * v2.36 : {@code textDocument/typeDefinition} — la déclaration du TYPE du
     * symbole au caret (section GO TO du menu contextuel unifié de la toolbar
     * de sélection, portage du NavMenu de CodeAssist).
     */
    @Override
    public TypeDefinitionProvider getTypeDefinitionProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getTypeDefinitionProvider() != null
            ? new LspTypeDefinitionProvider(wrapper, editor, logSink) : null;
    }

    /**
     * v2.37 : {@code textDocument/implementation} — les héritiers DIRECTS du
     * type en contexte au caret (section GO TO « Implementations » du menu
     * contextuel unifié, portage des {@code implementationTargets} de
     * CodeAssist). Méthode LSP STANDARD — tout serveur qui l'annonce
     * (jdtls, kotlin-language-server…) fonctionne.
     */
    @Override
    public ImplementationsProvider getImplementationsProvider() {
        if (wrapper.getCapabilities() == null) return null;
        return wrapper.getCapabilities().getImplementationProvider() != null
            ? new LspImplementationsProvider(wrapper, editor, logSink) : null;
    }

    /**
     * v2.37 : {@code textDocument/superDefinition} — méthode LSP
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
     * v3.33.10 (I-7 fix): wires the LSP {@code textDocument/references}
     * capability to the editor SPI. Previously the Java server declared
     * references support and implemented the handler, but the SPI had no
     * {@code getReferencesProvider()} slot — so the server-side code was
     * dead. Now end-to-end reachable.
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
     * v3.33.10 (I-1 fix): wires the LSP {@code textDocument/inlayHint}
     * capability to the editor SPI. The editor renders hints as faded gray
     * text inline with the code (e.g. ": String" after a {@code var}
     * declaration).
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
        // Cleanup is handled by LspEditor.disconnect()
    }

    // ── LSP-backed completion provider ───────────────────────────

    private static class LspCompletionProvider implements CompletionProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspCompletionProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public void complete(CharSequence text, int caret, CompletionPublisher publisher) {
            LanguageServer server = wrapper.getServer();
            if (server == null) {
                log("completion: server is null — not connected?", null);
                return;
            }
            // ★ v2.55 — Flush SYNCHRONE du didChange en attente AVANT la
            // requête completion. Sans ceci, le debounce didChange (300 ms)
            // dépasse le debounce completion (120 ms) → le serveur lit
            // openDocuments STALE → la détection de contexte
            // IMPORT_REFERENCE échoue (l'instruction « import android. »
            // n'est pas encore visible côté serveur) → repli sur
            // NAME_REFERENCE → mots-clés à la place des sous-paquets
            // (repro user : « import android. → keywords »).
            // Le flush est SYNCHRONE : la notification didChange est enfilée
            // AVANT la requête completion dans la même file LSP4J FIFO →
            // le serveur traite didChange PUIS completion, avec le texte
            // à jour.
            editor.flushPendingChange();
            CompletionParams params = new CompletionParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            params.setPosition(editor.offsetToPosition(caret));
            // v3.3.4: Set CompletionContext — some servers reject requests
            // without it. Use Invoked trigger kind (user explicitly asked).
            org.eclipse.lsp4j.CompletionContext ctx = new org.eclipse.lsp4j.CompletionContext();
            ctx.setTriggerKind(org.eclipse.lsp4j.CompletionTriggerKind.Invoked);
            params.setContext(ctx);
            log("completion: requesting at caret=" + caret, null);
            try {
                CompletableFuture<org.eclipse.lsp4j.jsonrpc.messages.Either<List<org.eclipse.lsp4j.CompletionItem>, CompletionList>> future =
                    server.getTextDocumentService().completion(params);
                org.eclipse.lsp4j.jsonrpc.messages.Either<List<org.eclipse.lsp4j.CompletionItem>, CompletionList> result =
                    future.get(15, TimeUnit.SECONDS);
                List<org.eclipse.lsp4j.CompletionItem> items = result.isLeft()
                    ? result.getLeft() : result.getRight().getItems();
                int count = items != null ? items.size() : 0;
                log("completion: got " + count + " items", null);
                if (items != null) {
                    // v0.1.0.50: préserve l'ordre classement du serveur.
                    // Le client regroupe par qualité de match (exact /
                    // préfixe / flou) mais garde l'ordre d'insertion DANS
                    // chaque groupe — un rank décroissant par position fait
                    // donc remonter les meilleures propositions du serveur
                    // au lieu du relevé uniforme "50" qui aplatissait le
                    // tri (ordre résiduel quasi alphabétique).
                    final LspEditor editorRef = editor;
                    int rank = items.size();
                    for (org.eclipse.lsp4j.CompletionItem lspItem : items) {
                        String label = lspItem.getLabel();
                        String detail = lspItem.getDetail() != null ? lspItem.getDetail() : "";
                        String insertText = lspItem.getInsertText() != null ? lspItem.getInsertText() : label;
                        String kind = lspKindToString(lspItem.getKind());
                        // ★ v2.30 — badge de type (portage KindBadge de
                        // CodeAssist) : le kind LSP NUMÉRIQUE voyage
                        // jusqu'au renderer ; le champ data transporte le
                        // raffinement ("annotation", "package"…) que le
                        // protocole ne distingue pas.
                        int kindCode = lspItem.getKind() != null
                                ? lspItem.getKind().getValue() : 0;
                        String kindTag = lspKindTag(lspItem.getData());
                        jo.codeeditor.lang.CompletionItem out =
                            new jo.codeeditor.lang.CompletionItem(
                                label, detail, insertText, kind,
                                kindCode, kindTag,
                                // Plus haut = meilleur ; la 1re proposition
                                // du serveur obtient le score maximal.
                                Math.max(1, rank--), false);
                        // v0.1.0.50: auto-import — transporte les
                        // additionalTextEdits (insertion de l'instruction
                        // import) vers l'éditeur via un hook post-accept.
                        List<org.eclipse.lsp4j.TextEdit> extra = lspItem.getAdditionalTextEdits();
                        if (extra != null && !extra.isEmpty()) {
                            final List<org.eclipse.lsp4j.TextEdit> extraEdits =
                                new ArrayList<>(extra);
                            final String itemLabel = label;
                            out.postApplyAction = () -> {
                                try {
                                    editorRef.applyAdditionalEdits(extraEdits);
                                    android.util.Log.i("LspCompletion",
                                        "auto-import appliqué pour " + itemLabel);
                                } catch (Throwable t) {
                                    android.util.Log.w("LspCompletion",
                                        "auto-import échoué pour "
                                            + itemLabel + ": " + t.getMessage());
                                }
                            };
                        }
                        publisher.addItem(out);
                    }
                }
            } catch (TimeoutException e) {
                log("completion: TIMEOUT (5s) — server too slow or hung", e);
            } catch (InterruptedException | ExecutionException e) {
                // v3.3.4: Log the full cause chain — ResponseErrorException
                // wraps the server's error message.
                String msg = "completion: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
                Throwable cause = e.getCause();
                while (cause != null) {
                    msg += "\n  caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    cause = cause.getCause();
                }
                log(msg, e);
            }
            publisher.flush();
        }

        @Override
        public List<String> getTriggerCharacters() {
            if (wrapper.getCapabilities() != null
                && wrapper.getCapabilities().getCompletionProvider() != null
                && wrapper.getCapabilities().getCompletionProvider().getTriggerCharacters() != null) {
                return wrapper.getCapabilities().getCompletionProvider().getTriggerCharacters();
            }
            return Collections.emptyList();
        }

        private void log(String message, Throwable t) {
            android.util.Log.i("LspCompletion", message);
            if (t != null) android.util.Log.e("LspCompletion", message, t);
            if (logSink != null) logSink.log(message);
        }

        private static String lspKindToString(org.eclipse.lsp4j.CompletionItemKind kind) {
            if (kind == null) return "v";
            switch (kind) {
                case Method: case Function: case Constructor: return "m";
                case Field: case Variable: case Property: return "f";
                case Class: case Interface: case Struct: case Enum: return "c";
                case Keyword: return "k";
                default: return "v";
            }
        }

        /**
         * ★ v2.30 — Raffinement de kind transporté dans le champ LSP
         * {@code data} (string côté serveur lspjava ; après le round-trip
         * JSON-RPC il peut arriver en String, JsonPrimitive ou autre —
         * on accepte les formes plausibles, null sinon).
         *
         * <p>Usage : une ANNOTATION Java voyage en CompletionItemKind.Class
         * (le protocole LSP n'a pas de kind dédié) — le serveur la tague
         * {@code "annotation"} pour que le badge affiche « @ » et non « C ».</p>
         */
        private static String lspKindTag(Object data) {
            if (data == null) return null;
            String s = null;
            if (data instanceof String) {
                s = (String) data;
            } else if (data instanceof com.google.gson.JsonElement) {
                com.google.gson.JsonElement je = (com.google.gson.JsonElement) data;
                if (je.isJsonPrimitive()) {
                    try {
                        s = je.getAsString();
                    } catch (Throwable ignored) {
                        return null;
                    }
                }
            }
            if (s == null) return null;
            switch (s) {
                case "annotation": case "package": case "record":
                case "parameter":
                    return s;
                default:
                    return null;
            }
        }
    }

    // ── LSP-backed hover provider ────────────────────────────────

    private static class LspHoverProvider implements HoverProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspHoverProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public HoverContent hover(CharSequence text, int offset) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return null;
            // ★ v2.55 — flush didChange en attente (cf. complete). Le hover
            // déclenché par frappe/live doit voir le texte serveur à jour.
            editor.flushPendingChange();
            org.eclipse.lsp4j.HoverParams params = new org.eclipse.lsp4j.HoverParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            params.setPosition(editor.offsetToPosition(offset));
            log("hover: requesting at offset=" + offset);
            try {
                CompletableFuture<Hover> future = server.getTextDocumentService().hover(params);
                // v2.38 : 2 s (parité Sora Timeouts.HOVER = 2000 ms) — un
                // tooltip de survol ne doit JAMAIS bloquer 10 s.
                Hover hover = future.get(2, TimeUnit.SECONDS);
                if (hover == null) {
                    log("hover: server returned null");
                    return null;
                }
                String markdown = "";
                if (hover.getContents().isRight()) {
                    MarkupContent mc = hover.getContents().getRight();
                    markdown = mc.getValue();
                } else if (hover.getContents().isLeft()) {
                    markdown = hover.getContents().getLeft().stream()
                        .map(e -> e.isLeft() ? e.getLeft() : e.getRight().getValue())
                        .reduce("", (a, b) -> a + "\n" + b);
                }
                log("hover: got " + markdown.length() + " chars");
                return new HoverContent("", "", markdown);
            } catch (Exception e) {
                log("hover: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return null;
            }
        }

        private void log(String message) {
            android.util.Log.i("LspHover", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── LSP-backed signature help provider ───────────────────────

    private static class LspSignatureHelpProvider implements SignatureHelpProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspSignatureHelpProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public SignatureHelp signatureHelp(CharSequence text, int caret) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return null;
            // ★ v2.55 — flush didChange en attente (cf. complete). La
            // signature-help déclenchée par frappe/live doit voir le texte
            // serveur à jour, sinon le call-site analysé peut être décalé.
            editor.flushPendingChange();
            SignatureHelpParams params = new SignatureHelpParams(
                editor.getTextDocumentIdentifier(), editor.offsetToPosition(caret));
            log("signatureHelp: requesting at caret=" + caret);
            try {
                CompletableFuture<org.eclipse.lsp4j.SignatureHelp> future =
                    server.getTextDocumentService().signatureHelp(params);
                org.eclipse.lsp4j.SignatureHelp help = future.get(10, TimeUnit.SECONDS);
                if (help == null || help.getSignatures().isEmpty()) {
                    log("signatureHelp: no signatures returned");
                    return null;
                }
                log("signatureHelp: got " + help.getSignatures().size() + " signatures");
                List<jo.codeeditor.lang.Signature> sigs = new ArrayList<>();
                for (SignatureInformation info : help.getSignatures()) {
                    List<jo.codeeditor.lang.Parameter> params2 = new ArrayList<>();
                    if (info.getParameters() != null) {
                        info.getParameters().forEach(p -> {
                            String label = p.getLabel().getLeft() != null ? p.getLabel().getLeft() : "";
                            params2.add(new jo.codeeditor.lang.Parameter(label, ""));
                        });
                    }
                    // v2.38 : la documentation arrive en String (Left) OU en
                    // MarkupContent (Right) selon le serveur — les deux côtés
                    // sont lus (le serveur :lspjava envoie du plain text).
                    String doc = "";
                    if (info.getDocumentation() != null) {
                        if (info.getDocumentation().getRight() != null) {
                            doc = info.getDocumentation().getRight().getValue();
                        } else if (info.getDocumentation().getLeft() != null) {
                            doc = info.getDocumentation().getLeft();
                        }
                    }
                    sigs.add(new jo.codeeditor.lang.Signature(info.getLabel(), doc, params2,
                        help.getActiveParameter() != null ? help.getActiveParameter() : 0));
                }
                return new SignatureHelp(sigs,
                    help.getActiveSignature() != null ? help.getActiveSignature() : 0,
                    help.getActiveParameter() != null ? help.getActiveParameter() : 0);
            } catch (Exception e) {
                log("signatureHelp: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return null;
            }
        }

        @Override
        public List<String> getTriggerCharacters() {
            if (wrapper.getCapabilities() != null
                && wrapper.getCapabilities().getSignatureHelpProvider() != null
                && wrapper.getCapabilities().getSignatureHelpProvider().getTriggerCharacters() != null) {
                return wrapper.getCapabilities().getSignatureHelpProvider().getTriggerCharacters();
            }
            return Collections.singletonList("(");
        }

        private void log(String message) {
            android.util.Log.i("LspSig", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── LSP-backed symbol provider ───────────────────────────────

    private static class LspSymbolProvider implements SymbolProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspSymbolProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public List<jo.codeeditor.lang.Symbol> symbols(CharSequence text) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return Collections.emptyList();
            DocumentSymbolParams params = new DocumentSymbolParams(editor.getTextDocumentIdentifier());
            log("documentSymbol: requesting");
            try {
                CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>>> future =
                    server.getTextDocumentService().documentSymbol(params);
                List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>> result =
                    future.get(3, TimeUnit.SECONDS);
                if (result == null) {
                    log("documentSymbol: null result");
                    return Collections.emptyList();
                }
                log("documentSymbol: got " + result.size() + " symbols");
                List<jo.codeeditor.lang.Symbol> symbols = new ArrayList<>();
                for (org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol> e : result) {
                    if (e.isRight()) {
                        DocumentSymbol ds = e.getRight();
                        int offset = editor.positionToOffset(ds.getRange().getStart());
                        symbols.add(new jo.codeeditor.lang.Symbol(
                            ds.getName(), offset, symbolKindToString(ds.getKind()), ""));
                    }
                }
                return symbols;
            } catch (Exception e) {
                log("documentSymbol: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return Collections.emptyList();
            }
        }

        private void log(String message) {
            android.util.Log.i("LspSymbol", message);
            if (logSink != null) logSink.log(message);
        }

        private static String symbolKindToString(SymbolKind kind) {
            if (kind == null) return "field";
            switch (kind) {
                case Class: return "class";
                case Interface: return "interface";
                case Enum: return "enum";
                case Method: case Constructor: return "method";
                case Field: case Property: return "field";
                default: return "field";
            }
        }
    }

    // ── LSP-backed diagnostics provider ──────────────────────────

    private static class LspDiagnosticsProvider implements DiagnosticsProvider {
        private final LspEditor editor;


        LspDiagnosticsProvider(LspEditor editor) {
            this.editor = editor;
        }

        @Override
        public List<Diagnostic> computeDiagnostics(CharSequence text) {
            // Diagnostics are pushed by the server via publishDiagnostics,
            // not pulled. We return the cached list from the editor.
            return editor.getCachedDiagnostics();
        }
    }

    /** No-op analyzer — the EditorView uses its own lexical tokenizer. */
    private static class LspNoopAnalyzer implements Analyzer {
        @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver receiver) {}
        @Override public void onReplace(CharSequence text, int s, int e, CharSequence i) {}
        @Override public void reset(CharSequence text) {}
        @Override public jo.codeeditor.highlight.StyledLine styledLine(int line) { return null; }
        @Override public List<jo.codeeditor.lang.CodeBlock> computeBlocks() { return Collections.emptyList(); }
        @Override public jo.codeeditor.lang.BracketMatch computeBracketMatch(int offset) { return null; }
        @Override public void destroy() {}
    }

    // ── v3.4.0: LSP-backed definition provider ──────────────────

    private static class LspDefinitionProvider implements DefinitionProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspDefinitionProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public List<DefinitionLocation> definitions(CharSequence text, int offset) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return Collections.emptyList();
            // ★ v2.55 — flush didChange en attente (cf. complete).
            editor.flushPendingChange();
            org.eclipse.lsp4j.DefinitionParams params = new org.eclipse.lsp4j.DefinitionParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            params.setPosition(editor.offsetToPosition(offset));
            log("definition: requesting at offset=" + offset);
            try {
                org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> defResult =
                    server.getTextDocumentService().definition(params).get(3, TimeUnit.SECONDS);
                List<org.eclipse.lsp4j.Location> locations;
                if (defResult == null) {
                    locations = Collections.emptyList();
                } else if (defResult.isLeft()) {
                    locations = new ArrayList<>(defResult.getLeft());
                } else {
                    locations = new ArrayList<>();
                    for (org.eclipse.lsp4j.LocationLink link : defResult.getRight()) {
                        org.eclipse.lsp4j.Location loc = new org.eclipse.lsp4j.Location();
                        loc.setUri(link.getTargetUri());
                        loc.setRange(link.getTargetSelectionRange());
                        locations.add(loc);
                    }
                }
                if (locations == null || locations.isEmpty()) {
                    log("definition: no results");
                    return Collections.emptyList();
                }
                log("definition: got " + locations.size() + " locations");
                List<DefinitionLocation> out = new ArrayList<>();
                for (org.eclipse.lsp4j.Location loc : locations) {
                    int targetOffset = editor.positionToOffset(loc.getRange().getStart());
                    out.add(new DefinitionLocation(loc.getUri(), targetOffset, loc.getUri()));
                }
                return out;
            } catch (Exception e) {
                log("definition: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return Collections.emptyList();
            }
        }

        private void log(String message) {
            android.util.Log.i("LspDef", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── v2.36: LSP-backed type-definition provider (NavMenu GO TO) ──

    private static class LspTypeDefinitionProvider implements TypeDefinitionProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspTypeDefinitionProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public List<DefinitionLocation> typeDefinitions(CharSequence text, int offset) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return Collections.emptyList();
            org.eclipse.lsp4j.TypeDefinitionParams params = new org.eclipse.lsp4j.TypeDefinitionParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            params.setPosition(editor.offsetToPosition(offset));
            log("typeDefinition: requesting at offset=" + offset);
            try {
                org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> defResult =
                    server.getTextDocumentService().typeDefinition(params).get(3, TimeUnit.SECONDS);
                List<org.eclipse.lsp4j.Location> locations;
                if (defResult == null) {
                    locations = Collections.emptyList();
                } else if (defResult.isLeft()) {
                    locations = new ArrayList<>(defResult.getLeft());
                } else {
                    locations = new ArrayList<>();
                    for (org.eclipse.lsp4j.LocationLink link : defResult.getRight()) {
                        org.eclipse.lsp4j.Location loc = new org.eclipse.lsp4j.Location();
                        loc.setUri(link.getTargetUri());
                        loc.setRange(link.getTargetSelectionRange());
                        locations.add(loc);
                    }
                }
                if (locations == null || locations.isEmpty()) {
                    log("typeDefinition: no results");
                    return Collections.emptyList();
                }
                log("typeDefinition: got " + locations.size() + " locations");
                List<DefinitionLocation> out = new ArrayList<>();
                for (org.eclipse.lsp4j.Location loc : locations) {
                    int targetOffset = editor.positionToOffset(loc.getRange().getStart());
                    out.add(new DefinitionLocation(loc.getUri(), targetOffset, loc.getUri()));
                }
                return out;
            } catch (Exception e) {
                log("typeDefinition: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return Collections.emptyList();
            }
        }

        private void log(String message) {
            android.util.Log.i("LspTypeDef", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── v2.37: LSP-backed implementations provider (NavMenu GO TO) ──

    /**
     * v2.37 — {@code textDocument/implementation} : les héritiers DIRECTS du
     * type en contexte. MULTI-FICHIERS : chaque Location vit dans le fichier
     * DECLARANT — la conversion Position→offset se fait sur le CONTENU de ce
     * fichier (pattern du provider references), jamais sur le document
     * courant. Libellé picker : « NomFichier.java ».
     */
    private static class LspImplementationsProvider implements ImplementationsProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspImplementationsProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public List<DefinitionLocation> implementations(CharSequence text, int offset) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return Collections.emptyList();
            org.eclipse.lsp4j.ImplementationParams params = new org.eclipse.lsp4j.ImplementationParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            params.setPosition(editor.offsetToPosition(offset));
            log("implementation: requesting at offset=" + offset);
            try {
                org.eclipse.lsp4j.jsonrpc.messages.Either<List<? extends org.eclipse.lsp4j.Location>, List<? extends org.eclipse.lsp4j.LocationLink>> result =
                    server.getTextDocumentService().implementation(params).get(8, TimeUnit.SECONDS);
                List<org.eclipse.lsp4j.Location> locations;
                if (result == null) {
                    locations = Collections.emptyList();
                } else if (result.isLeft()) {
                    locations = new ArrayList<>(result.getLeft());
                } else {
                    locations = new ArrayList<>();
                    for (org.eclipse.lsp4j.LocationLink link : result.getRight()) {
                        org.eclipse.lsp4j.Location loc = new org.eclipse.lsp4j.Location();
                        loc.setUri(link.getTargetUri());
                        loc.setRange(link.getTargetSelectionRange());
                        locations.add(loc);
                    }
                }
                if (locations == null || locations.isEmpty()) {
                    log("implementation: no results");
                    return Collections.emptyList();
                }
                log("implementation: got " + locations.size() + " locations");
                List<DefinitionLocation> out = new ArrayList<>();
                for (org.eclipse.lsp4j.Location loc : locations) {
                    String uri = loc.getUri();
                    org.eclipse.lsp4j.Position p = loc.getRange().getStart();
                    boolean current = editor.getFileUri() != null
                            && editor.getFileUri().equals(uri);
                    int targetOffset;
                    String label;
                    if (current) {
                        targetOffset = editor.positionToOffset(p);
                    } else {
                        // Conversion sur le CONTENU du fichier cible (le
                        // positionToOffset de l'éditeur courant serait faux).
                        // v3.34.0: API-24-safe read (was Path.of + Files.readAllBytes —
                        // API 34+/26+, crashed on Android < 8). IoCompat strips
                        // the file:// scheme itself.
                        String content = IoCompat.readUtf8(uri);
                        targetOffset = content != null
                                ? LspRenameProvider.lspPositionToOffset(content, p) : 0;
                        if (targetOffset < 0) targetOffset = 0;
                    }
                    // Libellé : le nom COURT du fichier (une classe publique
                    // Java vit dans le fichier de son nom — « ImplA.java »).
                    String path = uri.startsWith("file://")
                            ? uri.substring("file://".length()) : uri;
                    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                    label = slash >= 0 ? path.substring(slash + 1) : path;
                    out.add(new DefinitionLocation(uri, targetOffset, label));
                }
                return out;
            } catch (Exception e) {
                log("implementation: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return Collections.emptyList();
            }
        }

        private void log(String message) {
            android.util.Log.i("LspImpl", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── v2.37: LSP-backed super provider (custom method) ─────────

    /**
     * v2.37 — {@code textDocument/superDefinition} (méthode LSP
     * PERSONNALISÉE, cf. NavTextDocumentService côté :lspjava) : les cibles
     * GO TO « Super ». Appel TYPÉ via le proxy étendu
     * ({@link CodeIdeLanguageServer#superDefinition}) — la réponse est
     * désérialisée par LSP4J en {@code SuperNavTarget} (la méthode fait
     * partie de la carte de désérialisation du launcher, sans quoi le result
     * serait silencieusement jeté). Un serveur tiers qui ne connaît pas la
     * méthode répond « method not found » → vide (l'option n'apparaît pas).
     * La réponse transporte l'OFFSET EXACT du nom dans le fichier déclarant +
     * le libellé picker (« Simple  ·  pkg » / « name  ·  Super »).
     */
    private static class LspSuperDefinitionProvider implements SuperDefinitionProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspSuperDefinitionProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public List<DefinitionLocation> superTargets(CharSequence text, int offset) {
            CodeIdeLanguageServer ext = wrapper.getExtendedServer();
            if (ext == null) {
                return Collections.emptyList();
            }
            org.eclipse.lsp4j.TextDocumentPositionParams params =
                    new org.eclipse.lsp4j.TextDocumentPositionParams(
                            editor.getTextDocumentIdentifier(), editor.offsetToPosition(offset));
            log("superDefinition: requesting at offset=" + offset);
            try {
                List<CodeIdeLanguageServer.SuperNavTarget> hits = ext
                        .superDefinition(params)
                        .get(8, TimeUnit.SECONDS);
                if (hits == null || hits.isEmpty()) {
                    log("superDefinition: no results");
                    return Collections.emptyList();
                }
                log("superDefinition: got " + hits.size() + " targets");
                List<DefinitionLocation> out = new ArrayList<>();
                for (CodeIdeLanguageServer.SuperNavTarget hit : hits) {
                    if (hit == null || hit.path == null) continue;
                    // Le serveur envoie un chemin ABSOLU ; l'éditeur attend
                    // une URI (ou un chemin nu — openFileAt accepte les deux).
                    String uri = hit.path.startsWith("file://")
                            ? hit.path : "file://" + hit.path;
                    String label = hit.label != null && !hit.label.isEmpty()
                            ? hit.label : uri;
                    out.add(new DefinitionLocation(uri, hit.start, label));
                }
                return out;
            } catch (Exception e) {
                log("superDefinition: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return Collections.emptyList();
            }
        }

        private void log(String message) {
            android.util.Log.i("LspSuper", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── v3.4.0: LSP-backed rename provider ──────────────────────

    private static class LspRenameProvider implements RenameProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspRenameProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public RenameResult rename(CharSequence text, int offset, String newName) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return null;
            // ★ v2.55 — flush didChange en attente (cf. complete). Un rename
            // sur un identifiant qui vient d'être tapé doit voir le texte à jour.
            editor.flushPendingChange();
            org.eclipse.lsp4j.RenameParams params = new org.eclipse.lsp4j.RenameParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            params.setPosition(editor.offsetToPosition(offset));
            params.setNewName(newName);
            log("rename: requesting at offset=" + offset + " → '" + newName + "'");
            try {
                CompletableFuture<org.eclipse.lsp4j.WorkspaceEdit> future =
                    server.getTextDocumentService().rename(params);
                org.eclipse.lsp4j.WorkspaceEdit edit = future.get(10, TimeUnit.SECONDS);
                if (edit == null || edit.getChanges() == null || edit.getChanges().isEmpty()) {
                    log("rename: no edits");
                    return null;
                }

                // ★ v2 (multi-fichiers) : le WorkspaceEdit peut couvrir PLUSIEURS
                // fichiers (renommage ecJ projet-wide). Le fichier COURANT est
                // retourné comme RenameResult (éditeur, annulable) ; les AUTRES
                // fichiers sont réécrits sur disque — éditions triées en offset
                // DÉCROISSANT pour rester valides pendant l'application.
                String currentUri = editor.getFileUri();
                List<jo.codeeditor.lang.TextEdit> currentEdits = new ArrayList<>();
                int otherFiles = 0;
                int otherEdits = 0;
                for (java.util.Map.Entry<String, List<org.eclipse.lsp4j.TextEdit>> entry
                        : edit.getChanges().entrySet()) {
                    String uri = entry.getKey();
                    if (uri.equals(currentUri)) {
                        for (org.eclipse.lsp4j.TextEdit te : entry.getValue()) {
                            int start = editor.positionToOffset(te.getRange().getStart());
                            int end = editor.positionToOffset(te.getRange().getEnd());
                            currentEdits.add(new jo.codeeditor.lang.TextEdit(
                                    start, end, te.getNewText()));
                        }
                    } else {
                        int applied = applyEditsToDiskFile(uri, entry.getValue());
                        if (applied > 0) {
                            otherFiles++;
                            otherEdits += applied;
                        }
                    }
                }
                log("rename: " + currentEdits.size() + " éditions (fichier courant)"
                        + (otherFiles > 0
                                ? " + " + otherEdits + " dans " + otherFiles + " autre(s) fichier(s) (disque)"
                                : ""));
                if (currentEdits.isEmpty() && otherFiles == 0) return null;
                return new RenameResult(currentEdits);
            } catch (Exception e) {
                log("rename: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return null;
            }
        }

        /**
         * Applique des éditions LSP à un fichier NON ouvert : lecture disque,
         * application en ordre décroissant d'offset, réécriture. Retourne le
         * nombre d'éditions appliquées (0 = fichier introuvable/illisible).
         */
        private static int applyEditsToDiskFile(String uri,
                List<org.eclipse.lsp4j.TextEdit> edits) {
            if (uri == null || edits == null || edits.isEmpty()) return 0;
            String path = uri.startsWith("file://")
                    ? uri.substring("file://".length()) : uri;
            java.io.File file = new java.io.File(path);
            if (!file.isFile()) {
                android.util.Log.w("LspRename", "applyEditsToDiskFile: introuvable " + path);
                return 0;
            }
            try {
                // v3.34.0: API-24-safe read (was Files.readAllBytes — API 26+).
                String content = IoCompat.readUtf8(file);
                if (content == null) return 0;
                // Position LSP (ligne, colonne) → offset, sur CE contenu.
                List<int[]> offsets = new ArrayList<>(edits.size());
                List<String> texts = new ArrayList<>(edits.size());
                for (org.eclipse.lsp4j.TextEdit te : edits) {
                    int start = lspPositionToOffset(content, te.getRange().getStart());
                    int end = lspPositionToOffset(content, te.getRange().getEnd());
                    if (start < 0 || end < start || end > content.length()) {
                        android.util.Log.w("LspRename",
                                "applyEditsToDiskFile: plage invalide dans " + path);
                        return 0; // édition incohérente → ABANDONNE le fichier entier
                    }
                    offsets.add(new int[]{start, end});
                    texts.add(te.getNewText() == null ? "" : te.getNewText());
                }
                // Ordre décroissant de début.
                Integer[] order = new Integer[offsets.size()];
                for (int i = 0; i < order.length; i++) order[i] = i;
                java.util.Arrays.sort(order, (a, b) ->
                        Integer.compare(offsets.get(b)[0], offsets.get(a)[0]));
                String result = content;
                for (int idx : order) {
                    int[] r = offsets.get(idx);
                    result = result.substring(0, r[0]) + texts.get(idx)
                            + result.substring(r[1]);
                }
                // v3.34.0: API-24-safe write (was Files.write — API 26+).
                if (!IoCompat.writeUtf8(file, result)) return 0;
                return edits.size();
            } catch (Exception e) {
                android.util.Log.w("LspRename",
                        "applyEditsToDiskFile: échec " + path + " — " + e);
                return 0;
            }
        }

        /** Convertit une Position LSP (ligne, colonne) en offset du texte. */
        static int lspPositionToOffset(String text,
                org.eclipse.lsp4j.Position pos) {
            if (pos == null) return -1;
            int line = Math.max(0, pos.getLine());
            int lineStart = 0;
            for (int i = 0; i < line && lineStart < text.length(); i++) {
                int nl = text.indexOf('\n', lineStart);
                if (nl < 0) return -1;
                lineStart = nl + 1;
            }
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            int offset = lineStart + Math.max(0, pos.getCharacter());
            return Math.min(offset, lineEnd);
        }

        private void log(String message) {
            android.util.Log.i("LspRename", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── v3.4.0: LSP-backed code actions provider ────────────────

    private static class LspCodeActionsProvider implements CodeActionsProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspCodeActionsProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public List<CodeAction> codeActions(CharSequence text, int line) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return Collections.emptyList();
            // ★ v2.55 — flush didChange en attente (cf. complete). Les
            // quick-fixes doivent s'appuyer sur le texte live.
            editor.flushPendingChange();
            org.eclipse.lsp4j.CodeActionParams params = new org.eclipse.lsp4j.CodeActionParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            // Convert line to range — use the whole line.
            org.eclipse.lsp4j.Range range = new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(line, 0),
                new org.eclipse.lsp4j.Position(line, Integer.MAX_VALUE));
            params.setRange(range);
            // v3.33.12 FIX: Build a proper CodeActionContext with:
            //   1. The list of diagnostics that intersect this range —
            //      without them, the server's codeAction() returns empty
            //      immediately (JavaTextDocumentService.codeAction L155).
            //   2. The CodeActionKinds we support (quickfix + refactor).
            // Previously the context was null → server always returned 0
            // actions → no lightbulb ever shown.
            List<org.eclipse.lsp4j.Diagnostic> intersecting = new ArrayList<>();
            for (Diagnostic d : editor.getCachedDiagnostics()) {
                // Convert editor offset back to LSP Position to get the line.
                org.eclipse.lsp4j.Position diagPos = editor.offsetToPosition(d.start);
                if (diagPos.getLine() == line) {
                    org.eclipse.lsp4j.Diagnostic lspDiag = new org.eclipse.lsp4j.Diagnostic();
                    org.eclipse.lsp4j.Position diagStart = editor.offsetToPosition(d.start);
                    org.eclipse.lsp4j.Position diagEnd = editor.offsetToPosition(d.end);
                    lspDiag.setRange(new org.eclipse.lsp4j.Range(diagStart, diagEnd));
                    lspDiag.setMessage(d.message);
                    org.eclipse.lsp4j.DiagnosticSeverity sev;
                    switch (d.severity) {
                        case 3: sev = org.eclipse.lsp4j.DiagnosticSeverity.Error; break;
                        case 2: sev = org.eclipse.lsp4j.DiagnosticSeverity.Warning; break;
                        default: sev = org.eclipse.lsp4j.DiagnosticSeverity.Information; break;
                    }
                    lspDiag.setSeverity(sev);
                    intersecting.add(lspDiag);
                }
            }
            org.eclipse.lsp4j.CodeActionContext ctx = new org.eclipse.lsp4j.CodeActionContext(
                intersecting,
                java.util.List.of(
                    org.eclipse.lsp4j.CodeActionKind.QuickFix,
                    org.eclipse.lsp4j.CodeActionKind.Refactor,
                    org.eclipse.lsp4j.CodeActionKind.RefactorExtract,
                    org.eclipse.lsp4j.CodeActionKind.RefactorInline,
                    org.eclipse.lsp4j.CodeActionKind.Source,
                    org.eclipse.lsp4j.CodeActionKind.SourceOrganizeImports));
            params.setContext(ctx);
            log("codeAction: requesting for line=" + line
                + " (context: " + intersecting.size() + " diagnostics)");
            try {
                CompletableFuture<List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>> future =
                    server.getTextDocumentService().codeAction(params);
                List<org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> result =
                    future.get(3, TimeUnit.SECONDS);
                if (result == null || result.isEmpty()) {
                    log("codeAction: no results");
                    return Collections.emptyList();
                }
                log("codeAction: got " + result.size() + " actions");
                List<CodeAction> out = new ArrayList<>();
                for (org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction> e : result) {
                    String title;
                    String kind = "";
                    // v3.33.12: capture the WorkspaceEdit so the apply
                    // Runnable can apply it when the user taps the action.
                    // Without this, the action's apply was null → tapping
                    // the action did nothing visible.
                    final org.eclipse.lsp4j.WorkspaceEdit editToApply;
                    if (e.isLeft()) {
                        title = e.getLeft().getTitle();
                        editToApply = null;
                    } else {
                        org.eclipse.lsp4j.CodeAction ca = e.getRight();
                        title = ca.getTitle();
                        kind = ca.getKind() != null ? ca.getKind() : "";
                        editToApply = ca.getEdit();
                    }
                    final String actionTitle = title;
                    final LspEditor editorRef = editor;
                    Runnable apply = () -> {
                        if (editToApply == null) {
                            log("codeAction: '" + actionTitle + "' has no edit — nothing to apply");
                            return;
                        }
                        try {
                            editorRef.applyWorkspaceEdit(editToApply);
                            log("codeAction: applied '" + actionTitle + "'");
                        } catch (Throwable t) {
                            log("codeAction: FAILED to apply '" + actionTitle + "': " + t.getMessage());
                        }
                    };
                    out.add(new CodeAction(title, kind, false, apply));
                }
                return out;
            } catch (Exception e) {
                log("codeAction: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return Collections.emptyList();
            }
        }

        private void log(String message) {
            android.util.Log.i("LspCodeAction", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── v3.4.0: LSP-backed document highlight provider ──────────

    private static class LspDocumentHighlightProvider implements DocumentHighlightProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspDocumentHighlightProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public List<DocumentHighlight> highlights(CharSequence text, int offset) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return Collections.emptyList();
            org.eclipse.lsp4j.DocumentHighlightParams params = new org.eclipse.lsp4j.DocumentHighlightParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            params.setPosition(editor.offsetToPosition(offset));
            log("documentHighlight: requesting at offset=" + offset);
            try {
                CompletableFuture<List<org.eclipse.lsp4j.DocumentHighlight>> future =
                    (CompletableFuture<List<org.eclipse.lsp4j.DocumentHighlight>>) (CompletableFuture<?>)
                    server.getTextDocumentService().documentHighlight(params);
                List<org.eclipse.lsp4j.DocumentHighlight> result = future.get(10, TimeUnit.SECONDS);
                if (result == null || result.isEmpty()) {
                    log("documentHighlight: no results");
                    return Collections.emptyList();
                }
                log("documentHighlight: got " + result.size() + " occurrences");
                List<DocumentHighlight> out = new ArrayList<>();
                for (org.eclipse.lsp4j.DocumentHighlight dh : result) {
                    int start = editor.positionToOffset(dh.getRange().getStart());
                    int end = editor.positionToOffset(dh.getRange().getEnd());
                    String kind = dh.getKind() != null ? dh.getKind().name().toLowerCase() : "text";
                    out.add(new DocumentHighlight(start, end, kind));
                }
                return out;
            } catch (Exception e) {
                log("documentHighlight: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return Collections.emptyList();
            }
        }

        private void log(String message) {
            android.util.Log.i("LspDocHighlight", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── v3.4.0: LSP-backed formatter ────────────────────────────

    private static class LspFormatter implements Formatter {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspFormatter(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public CharSequence format(CharSequence text, int startOffset, int endOffset) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return text;
            // ★ v2.58 — flush didChange en attente (cf. complete / inlayHints).
            // Le formatter LSP calcule des éditions sur le texte serveur — si
            // le texte est STALE (didChange debouncé à 300 ms pas encore envoyé),
            // les éditions seront calculées sur une version obsolète et
            // appliqueront des offsets hors-sync.
            editor.flushPendingChange();
            try {
                org.eclipse.lsp4j.DocumentFormattingParams params =
                    new org.eclipse.lsp4j.DocumentFormattingParams();
                params.setTextDocument(editor.getTextDocumentIdentifier());
                CompletableFuture<List<org.eclipse.lsp4j.TextEdit>> future =
                    (CompletableFuture<List<org.eclipse.lsp4j.TextEdit>>) (CompletableFuture<?>)
                    server.getTextDocumentService().formatting(params);
                List<org.eclipse.lsp4j.TextEdit> edits = future.get(5, TimeUnit.SECONDS);
                if (edits == null || edits.isEmpty()) return text;
                // Apply edits in reverse order (descending by offset).
                StringBuilder sb = new StringBuilder(text);
                edits.sort((a, b) -> Integer.compare(
                    editor.positionToOffset(b.getRange().getStart()),
                    editor.positionToOffset(a.getRange().getStart())));
                for (org.eclipse.lsp4j.TextEdit te : edits) {
                    int s = editor.positionToOffset(te.getRange().getStart());
                    int e = editor.positionToOffset(te.getRange().getEnd());
                    sb.replace(s, e, te.getNewText());
                }
                return sb.toString();
            } catch (Exception e) {
                return text;
            }
        }
    }

    // ── v3.33.10: LSP-backed references provider (I-7 fix) ────────

    private static class LspReferencesProvider implements ReferencesProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspReferencesProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public List<DefinitionLocation> references(CharSequence text, int offset) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return Collections.emptyList();
            // ★ v2.55 — flush didChange en attente (cf. complete).
            editor.flushPendingChange();
            org.eclipse.lsp4j.ReferenceParams params = new org.eclipse.lsp4j.ReferenceParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            params.setPosition(editor.offsetToPosition(offset));
            // includeDeclaration=false — the symbol's own declaration is the
            // job of getDefinitionProvider(). Find-references should return
            // ONLY the usages.
            params.setContext(new org.eclipse.lsp4j.ReferenceContext(false));
            log("references: requesting at offset=" + offset);
            try {
                CompletableFuture<List<? extends org.eclipse.lsp4j.Location>> future =
                    server.getTextDocumentService().references(params);
                // ★ 15 s : les références multi-fichiers balayent les racines
                // sources côté serveur (chacune résolue par ecj).
                List<? extends org.eclipse.lsp4j.Location> locations = future.get(15, TimeUnit.SECONDS);
                if (locations == null || locations.isEmpty()) {
                    log("references: no results");
                    return Collections.emptyList();
                }
                log("references: got " + locations.size() + " locations");
                List<DefinitionLocation> out = new ArrayList<>();
                for (org.eclipse.lsp4j.Location loc : locations) {
                    String uri = loc.getUri();
                    org.eclipse.lsp4j.Position p = loc.getRange().getStart();
                    boolean current = editor.getFileUri() != null
                            && editor.getFileUri().equals(uri);
                    int targetOffset;
                    String label;
                    if (current) {
                        // Fichier courant : conversion via l'éditeur.
                        targetOffset = editor.positionToOffset(p);
                        label = "L" + (p.getLine() + 1) + ":C" + (p.getCharacter() + 1);
                    } else {
                        // ★ Autre fichier : conversion sur SON contenu (le
                        // positionToOffset de l'éditeur courant serait faux)
                        // + libellé lisible « NomFichier.java:L12 ».
                        // v3.34.0: API-24-safe read (was Path.of +
                        // Files.readAllBytes — API 34+/26+, crashed on
                        // Android < 8). IoCompat strips file:// itself.
                        String path = uri.startsWith("file://")
                                ? uri.substring("file://".length()) : uri;
                        String content = IoCompat.readUtf8(uri);
                        if (content == null) {
                            log("references: fichier illisible " + path);
                            targetOffset = 0;
                        } else {
                            targetOffset = LspRenameProvider.lspPositionToOffset(content, p);
                            if (targetOffset < 0) targetOffset = 0;
                        }
                        String fileName = path.substring(path.lastIndexOf('/') + 1);
                        label = fileName + ":L" + (p.getLine() + 1);
                    }
                    out.add(new DefinitionLocation(uri, targetOffset, label));
                }
                return out;
            } catch (TimeoutException e) {
                log("references: TIMEOUT (15s) — server too slow or hung");
                return Collections.emptyList();
            } catch (InterruptedException | ExecutionException e) {
                String msg = "references: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
                Throwable cause = e.getCause();
                while (cause != null) {
                    msg += "\n  caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    cause = cause.getCause();
                }
                log(msg);
                return Collections.emptyList();
            }
        }

        private void log(String message) {
            android.util.Log.i("LspRef", message);
            if (logSink != null) logSink.log(message);
        }
    }

    // ── v3.33.10: LSP-backed inlay hint provider (I-1 fix) ────────

    private static class LspInlayHintProvider implements InlayHintProvider {
        private final LanguageServerWrapper wrapper;
        private final LspEditor editor;
        private final LspLogSink logSink;

        LspInlayHintProvider(LanguageServerWrapper wrapper, LspEditor editor, LspLogSink logSink) {
            this.wrapper = wrapper;
            this.editor = editor;
            this.logSink = logSink;
        }

        @Override
        public List<InlayHint> inlayHints(CharSequence text, int startLine, int endLine) {
            LanguageServer server = wrapper.getServer();
            if (server == null) return Collections.emptyList();
            // ★ v2.58 — flush didChange en attente (cf. complete).
            // Les inlay hints sont déduits par inférence de type sur le
            // texte source — si le texte serveur est STALE (didChange
            // debouncé à 300 ms pas encore envoyé), les hints seront
            // calculés sur une version obsolète et les offsets retournés
            // ne correspondront pas aux positions du texte live.
            editor.flushPendingChange();
            org.eclipse.lsp4j.InlayHintParams params = new org.eclipse.lsp4j.InlayHintParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            // Convert (startLine, endLine) to an LSP Range. Use column 0
            // for the start and Integer.MAX_VALUE for the end so the entire
            // line range is covered.
            jo.codeeditor.document.EditorDocument doc = editor.getEditorView() != null
                ? editor.getEditorView().getSession().getDocument() : null;
            int safeStartLine = doc != null ? Math.max(0, Math.min(startLine, doc.lineCount() - 1)) : Math.max(0, startLine);
            int safeEndLine = doc != null ? Math.max(safeStartLine, Math.min(endLine, doc.lineCount() - 1)) : Math.max(safeStartLine, endLine);
            org.eclipse.lsp4j.Range range = new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(safeStartLine, 0),
                new org.eclipse.lsp4j.Position(safeEndLine, Integer.MAX_VALUE));
            params.setRange(range);
            log("inlayHint: requesting lines " + safeStartLine + "-" + safeEndLine);
            try {
                CompletableFuture<List<org.eclipse.lsp4j.InlayHint>> future =
                    server.getTextDocumentService().inlayHint(params);
                List<org.eclipse.lsp4j.InlayHint> hints = future.get(5, TimeUnit.SECONDS);
                if (hints == null || hints.isEmpty()) {
                    log("inlayHint: no results");
                    return Collections.emptyList();
                }
                List<InlayHint> out = new ArrayList<>();
                for (org.eclipse.lsp4j.InlayHint h : hints) {
                    int offset = editor.positionToOffset(h.getPosition());
                    String label;
                    if (h.getLabel().isLeft()) {
                        label = h.getLabel().getLeft();
                    } else if (h.getLabel().isRight()) {
                        // Stitch label parts together (some servers use parts).
                        StringBuilder sb = new StringBuilder();
                        for (org.eclipse.lsp4j.InlayHintLabelPart part : h.getLabel().getRight()) {
                            sb.append(part.getValue());
                        }
                        label = sb.toString();
                    } else {
                        label = "";
                    }
                    String kind = "type";
                    if (h.getKind() == org.eclipse.lsp4j.InlayHintKind.Parameter) {
                        kind = "parameter";
                    }
                    // v2.34 — padding LSP honoré (parité buildInlayAnnotated
                    // de CodeAssist : « txt = (if paddingLeft " ") + text +
                    // (if paddingRight " ") » — les espaces de padding font
                    // partie du TEXTE FANTÔME tissé dans la ligne). Avant,
                    // les flags étaient ignorés : « name: » collait à
                    // l'argument et le type de chaînage collait au dernier
                    // caractère de l'appel.
                    if (Boolean.TRUE.equals(h.getPaddingLeft())) {
                        label = " " + label;
                    }
                    if (Boolean.TRUE.equals(h.getPaddingRight())) {
                        label = label + " ";
                    }
                    out.add(new InlayHint(offset, label, kind));
                }
                log("inlayHint: got " + out.size() + " hints");
                return out;
            } catch (TimeoutException e) {
                log("inlayHint: TIMEOUT (5s) — server too slow or hung");
                return Collections.emptyList();
            } catch (InterruptedException | ExecutionException e) {
                String msg = "inlayHint: ERROR " + e.getClass().getSimpleName() + ": " + e.getMessage();
                Throwable cause = e.getCause();
                while (cause != null) {
                    msg += "\n  caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    cause = cause.getCause();
                }
                log(msg);
                return Collections.emptyList();
            }
        }

        private void log(String message) {
            android.util.Log.i("LspInlay", message);
            if (logSink != null) logSink.log(message);
        }
    }

}
