package jo.codeeditor.lsp;

import java.util.concurrent.TimeUnit;

/**
 * Timeouts par type de requête LSP (L1-bis/D-01) — surchargeables par
 * définition de serveur via
 * {@link LanguageServerDefinition#setTimeouts(LspRequestTimeouts)}.
 *
 * <p>Les valeurs par défaut reprennent EXACTEMENT les délais codés en
 * dur historiquement dans chaque provider (aucun changement de
 * comportement sans surcharge) ; seule la poignée de main
 * {@code initialize} gagne un garde-fou (10 s) là où elle pouvait
 * attendre indéfiniment.</p>
 *
 * <p>Instance mutable par fluent setters — posée UNE fois sur la
 * définition avant le démarrage du serveur ; les lectures des
 * providers se font ensuite sans verrou (champs volatils).</p>
 */
public final class LspRequestTimeouts {

    private volatile long initMs = 10_000;
    private volatile long completionMs = 15_000;
    private volatile long hoverMs = 2_000;
    private volatile long signatureHelpMs = 10_000;
    private volatile long definitionMs = 3_000;
    private volatile long typeDefinitionMs = 3_000;
    private volatile long implementationsMs = 8_000;
    private volatile long superDefinitionMs = 8_000;
    private volatile long referencesMs = 15_000;
    private volatile long prepareRenameMs = 3_000;
    private volatile long renameMs = 10_000;
    private volatile long inlayHintsMs = 5_000;
    private volatile long inlayResolveMs = 3_000;
    private volatile long completionResolveMs = 3_000;
    private volatile long documentHighlightMs = 10_000;
    private volatile long symbolMs = 3_000;
    private volatile long formattingMs = 5_000;

    /** Poignée de main initialize (nouveau garde-fou — 10 s par défaut). */
    public long initMs() { return initMs; }
    /** textDocument/completion. */
    public long completionMs() { return completionMs; }
    /** textDocument/hover. */
    public long hoverMs() { return hoverMs; }
    /** textDocument/signatureHelp. */
    public long signatureHelpMs() { return signatureHelpMs; }
    /** textDocument/definition. */
    public long definitionMs() { return definitionMs; }
    /** textDocument/typeDefinition. */
    public long typeDefinitionMs() { return typeDefinitionMs; }
    /** textDocument/implementation. */
    public long implementationsMs() { return implementationsMs; }
    /** textDocument/superDefinition (extension maison). */
    public long superDefinitionMs() { return superDefinitionMs; }
    /** textDocument/references. */
    public long referencesMs() { return referencesMs; }
    /** textDocument/prepareRename. */
    public long prepareRenameMs() { return prepareRenameMs; }
    /** textDocument/rename. */
    public long renameMs() { return renameMs; }
    /** textDocument/inlayHint. */
    public long inlayHintsMs() { return inlayHintsMs; }
    /** inlayHint/resolve. */
    public long inlayResolveMs() { return inlayResolveMs; }
    /** completionItem/resolve (résolution paresseuse de la documentation). */
    public long completionResolveMs() { return completionResolveMs; }
    /** textDocument/documentHighlight. */
    public long documentHighlightMs() { return documentHighlightMs; }
    /** textDocument/documentSymbol. */
    public long symbolMs() { return symbolMs; }
    /** textDocument/formatting. */
    public long formattingMs() { return formattingMs; }

    public LspRequestTimeouts setInit(long ms) { this.initMs = saneMs(ms); return this; }
    public LspRequestTimeouts setCompletion(long ms) { this.completionMs = saneMs(ms); return this; }
    public LspRequestTimeouts setHover(long ms) { this.hoverMs = saneMs(ms); return this; }
    public LspRequestTimeouts setSignatureHelp(long ms) { this.signatureHelpMs = saneMs(ms); return this; }
    public LspRequestTimeouts setDefinition(long ms) { this.definitionMs = saneMs(ms); return this; }
    public LspRequestTimeouts setTypeDefinition(long ms) { this.typeDefinitionMs = saneMs(ms); return this; }
    public LspRequestTimeouts setImplementations(long ms) { this.implementationsMs = saneMs(ms); return this; }
    public LspRequestTimeouts setSuperDefinition(long ms) { this.superDefinitionMs = saneMs(ms); return this; }
    public LspRequestTimeouts setReferences(long ms) { this.referencesMs = saneMs(ms); return this; }
    public LspRequestTimeouts setPrepareRename(long ms) { this.prepareRenameMs = saneMs(ms); return this; }
    public LspRequestTimeouts setRename(long ms) { this.renameMs = saneMs(ms); return this; }
    public LspRequestTimeouts setInlayHints(long ms) { this.inlayHintsMs = saneMs(ms); return this; }
    public LspRequestTimeouts setInlayResolve(long ms) { this.inlayResolveMs = saneMs(ms); return this; }
    public LspRequestTimeouts setCompletionResolve(long ms) { this.completionResolveMs = saneMs(ms); return this; }
    public LspRequestTimeouts setDocumentHighlight(long ms) { this.documentHighlightMs = saneMs(ms); return this; }
    public LspRequestTimeouts setSymbol(long ms) { this.symbolMs = saneMs(ms); return this; }
    public LspRequestTimeouts setFormatting(long ms) { this.formattingMs = saneMs(ms); return this; }

    /** Un timeout nul ou négatif neutralise le garde-fou (attente illimitée). */
    private static long saneMs(long ms) {
        return ms > 0 ? ms : 0L;
    }

    /**
     * Attente bloquante bornée d'un futur — helper des providers :
     * {@code ms > 0} → {@code get(ms, MILLISECONDS)} ; {@code 0} =
     * attente illimitée (comportement historique sur demande explicite).
     */
    static <T> T await(java.util.concurrent.CompletableFuture<T> future, long ms)
            throws java.util.concurrent.ExecutionException,
                   InterruptedException,
                   java.util.concurrent.TimeoutException {
        return ms > 0 ? future.get(ms, TimeUnit.MILLISECONDS) : future.get();
    }
}
