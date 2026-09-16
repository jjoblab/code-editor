package jo.codeeditor.lsp;

import jo.codeeditor.lang.Diagnostic;
import jo.codeeditor.lang.Language;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.shift.EditSpan;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.PublishDiagnosticsParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bridges a single file (document) to its LSP server. Created by
 * {@link LspProject#createEditor(String)}.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Send {@code textDocument/didOpen} on connect</li>
 *   <li>Send {@code textDocument/didChange} on text edits (full sync)</li>
 *   <li>Receive {@code textDocument/publishDiagnostics} and cache them</li>
 *   <li>Convert between LSP {@link Position} (line/col) and editor offset</li>
 *   <li>Create and cache the {@link LspLanguage} that implements the
 *       {@link Language} SPI</li>
 * </ul>
 *
 * @since v2.2.0
 */
public class LspEditor {

    private final LspProject project;
    private final String fileUri;
    private final String ext;
    private LanguageServerWrapper wrapper;
    private LspLanguage language;
    private EditorView editorView;
    private volatile List<Diagnostic> cachedDiagnostics = new CopyOnWriteArrayList<>();
    private int documentVersion = 0;
    private volatile boolean connected = false;

    /**
     * ★ R1 (v0.1.0.51-v2.22) : base textuelle KNOWN-SYNCED au serveur.
     * Chaque didOpen/didChange réussi met à jour cette copie ; le prochain
     * didChange calcule un diff préfixe/suffixe contre CETTE base (jamais
     * contre le buffer live qui peut avoir divergé par des éditions
     * programmatiques — auto-imports, quick-fixes) et n'envoie que le
     * Range modifié. null tant que non connecté → premier envoi full.
     */
    private volatile String lastSyncedText = null;

    /**
     * ★ v2.33 — le language SPI est-il posé sur l'EditorView ? Les tirs
     * foldingRange arrivés AVANT (didOpen publie les diagnostics avant le
     * setLanguage posté) restent en attente : appliquer des folds serveur
     * avant setLanguage serait VAINCU par le detectFolds lexical (et
     * consumerait l'unique application de collapsedByDefault).
     */
    private volatile boolean languageApplied = false;

    LspEditor(LspProject project, String fileUri) {
        this.project = project;
        this.fileUri = fileUri;
        // Extract extension from URI.
        int dotIdx = fileUri.lastIndexOf('.');
        this.ext = dotIdx >= 0 ? fileUri.substring(dotIdx + 1) : "";
    }

    /**
     * Binds this editor to an {@link EditorView}. Call before {@link #connect()}.
     */
    public void setEditorView(EditorView view) {
        this.editorView = view;
    }

    /**
     * Connects to the LSP server, sends didOpen, and creates the
     * {@link LspLanguage}. Returns a future that completes when connected.
     */
    public synchronized java.util.concurrent.CompletableFuture<Void> connect() {
        if (connected) return java.util.concurrent.CompletableFuture.completedFuture(null);
        wrapper = project.getWrapper(ext);
        final LspLogSink logSink = project.getLogSink();
        log(logSink, "connect: starting wrapper...");
        return wrapper.start().thenRun(() -> {
            // v3.5.0: wire the client→editor bridge BEFORE sendDidOpen.
            // The server's didOpen handler calls publishDiagnostics
            // synchronously — if setLspEditor hasn't run yet when that
            // notification arrives (over the fast LocalSocket), the
            // diagnostics are silently dropped (lspEditor == null in
            // DefaultLanguageClient.publishDiagnostics). This was the root
            // cause of "no diagnostics ever published" for the Lua LSP.
            if (wrapper.getClient() != null) {
                wrapper.getClient().setLspEditor(LspEditor.this);
                log(logSink, "connect: client→editor bridge wired (before didOpen)");
            }
            log(logSink, "connect: wrapper started, sending didOpen...");
            // v3.3.2 fix: send didOpen BEFORE setLanguage so the server
            // knows about the document before any completion/hover requests.
            sendDidOpen();
            log(logSink, "connect: didOpen sent");
            language = new LspLanguage(LspEditor.this, wrapper);
            if (editorView != null) {
                // v3.3.3 fix: run setLanguage on the UI thread — EditorView
                // is a View and modifying its resolvers from a background
                // thread races with the UI thread's reads.
                final LspLanguage lang = language;
                final LspEditor self = LspEditor.this;
                Runnable applyLanguage = () -> {
                    editorView.setLanguage(lang);
                    // v3.3.3 fix: use addOnTextEditListener (not set) so we
                    // don't replace the demo app's existing listener.
                    editorView.getSession().addOnTextEditListener((s, e, t) -> {
                        self.onTextChanged(editorView.getSession().getText());
                    });
                    // ★ v2.33 : le SPI est posé — les folds serveur peuvent
                    // désormais s'appliquer sans être écrasés par le
                    // detectFolds lexical de setLanguage. Premier tir dès
                    // maintenant (les tirs arrivés avant sont restés en
                    // attente — garde languageApplied).
                    languageApplied = true;
                    scheduleFoldPull();
                    log(logSink, "connect: setLanguage done, didChange forwarder wired");
                };
                if (editorView.post(applyLanguage)) {
                    // post succeeded — view is attached, will run on UI thread.
                } else {
                    // View not attached — run inline (best-effort).
                    applyLanguage.run();
                    log(logSink, "connect: setLanguage done inline (view not attached)");
                }
            }
            connected = true;
            log(logSink, "connect: SUCCESS — server ready, version=" + documentVersion);
        }).exceptionally(throwable -> {
            log(logSink, "connect: FAILED — " + throwable.getClass().getSimpleName()
                + ": " + throwable.getMessage());
            Throwable cause = throwable.getCause();
            while (cause != null) {
                log(logSink, "connect:   caused by " + cause.getClass().getSimpleName()
                    + ": " + cause.getMessage());
                cause = cause.getCause();
            }
            return null;
        });
    }

    /** v3.3.3: Logs to the project's log sink (the demo's log panel) + logcat. */
    private void log(LspLogSink sink, String message) {
        android.util.Log.i("LspEditor", message);
        if (sink != null) sink.log(message);
    }

    /** Disconnects from the server (sends didClose). */
    public synchronized void disconnect() {
        if (!connected) return;
        // v3.33.6: Annule le thread de debounce didChange en cours pour éviter
        // qu'il n'écrive dans un pipe fermé → "Write end dead".
        if (didChangeThread != null) {
            didChangeThread.interrupt();
            didChangeThread = null;
        }
        try {
            if (wrapper != null && wrapper.getServer() != null) {
                org.eclipse.lsp4j.DidCloseTextDocumentParams params =
                    new org.eclipse.lsp4j.DidCloseTextDocumentParams();
                params.setTextDocument(getTextDocumentIdentifier());
                wrapper.getServer().getTextDocumentService().didClose(params);
            }
        } catch (Exception ignored) {}
        connected = false;
    }

    /**
     * Called when the editor's text changes. Sends a didChange notification
     * (full sync — sends the whole text).
     *
     * <p>v3.33.4: Debounced by 300ms + dispatched on a background thread to
     * avoid blocking the UI thread. The didChange is sent via the LSP4J
     * executor (async) — the server processes it on its own thread and
     * pushes diagnostics back via publishDiagnostics (also async).</p>
     */
    public void onTextChanged(CharSequence text) {
        if (!connected || wrapper == null || wrapper.getServer() == null) return;
        // v3.33.4: Debounce — store the latest text and schedule a didChange
        // after DID_CHANGE_DEBOUNCE_MS. Rapid typing (10+ keys/sec) won't
        // spam the server with a didChange per keystroke.
        pendingText = text.toString();
        scheduleDebouncedDidChange();
    }

    /** v3.33.4: The text waiting to be sent via didChange. */
    private volatile String pendingText = null;
    private volatile long lastDidChangeSchedule = 0;
    private static final long DID_CHANGE_DEBOUNCE_MS = 300;

    /**
     * v3.33.4: Schedules a debounced didChange on a background thread.
     * Fires DID_CHANGE_DEBOUNCE_MS after the last onTextChanged call.
     */
    private void scheduleDebouncedDidChange() {
        lastDidChangeSchedule = System.currentTimeMillis();
        final long scheduledAt = lastDidChangeSchedule;
        final String textToSend = pendingText;
        if (didChangeThread != null) didChangeThread.interrupt();
        didChangeThread = new Thread(() -> {
            try {
                Thread.sleep(DID_CHANGE_DEBOUNCE_MS);
            } catch (InterruptedException e) {
                return; // superseded by a newer didChange
            }
            // Only fire if no further onTextChanged happened during the wait.
            if (scheduledAt != lastDidChangeSchedule) return;
            sendDidChange(textToSend);
        }, "lsp-didchange-debounce");
        didChangeThread.setDaemon(true);
        didChangeThread.start();
    }

    private volatile Thread didChangeThread;

    /**
     * ★ v2.55 — Flush SYNCHRONE du didChange en attente.
     *
     * <p>Doit être appelé AVANT toute requête LSP qui dépend du contenu
     * live du document (completion, signatureHelp, hover, definition,
     * codeAction, references, rename…), pour garantir que le serveur
     * voit la version la plus récente du texte.</p>
     *
     * <p><b>Contexte du bug</b> : le debounce didChange
     * ({@link #DID_CHANGE_DEBOUNCE_MS} = 300 ms) est PLUS LONG que le
     * debounce completion ({@code COMPLETION_DEBOUNCE_MS} = 120 ms dans
     * {@code EditorPopupManager}). Sans ce flush, une frappe déclenche
     * une requête completion à T+120 ms sur un serveur qui n'a PAS
     * encore reçu le didChange (prévu à T+300 ms). Le serveur lit
     * {@code openDocuments} STALE → {@code analyzeContext} ne détecte
     * pas le contexte {@code IMPORT_REFERENCE} (l'instruction import
     * n'est pas encore dans le texte serveur) → repli sur
     * {@code NAME_REFERENCE} → retourne des MOTS-CLÉS à la place des
     * sous-paquets (repro user : « import android. → keywords »).</p>
     *
     * <p><b>Mécanisme</b> : interrompt le thread debounce en cours (le
     * didChange différé ne sera PAS envoyé en double), puis appelle
     * {@link #sendDidChange(String)} SYNCHRONEMENT sur le thread
     * appelant (typiquement {@code COMPLETION_EXECUTOR}). La notification
     * didChange est SÉRIALISÉE et mise dans la file LSP4J AVANT le
     * retour de cette méthode. La requête completion qui suit est mise
     * dans la même file APRÈS le didChange — le serveur traite donc
     * didChange PUIS completion, et voit le texte à jour.</p>
     *
     * <p>Idempotent : si {@link #pendingText} est null (rien en attente,
     * ou déjà flushed), ne fait rien.</p>
     */
    public void flushPendingChange() {
        if (!connected || wrapper == null || wrapper.getServer() == null) return;
        // ★ Annule le debounce différé : son envoi serait en double du nôtre.
        if (didChangeThread != null) {
            didChangeThread.interrupt();
            didChangeThread = null;
        }
        String pending = pendingText;
        if (pending == null) return;
        pendingText = null;
        // ★ Envoi SYNCHRONE sur le thread appelant : la notification
        // didChange est SERIELISEE dans la file LSP4J avant le retour.
        // La requête completion qui suit est enfilée APRÈS le didChange
        // dans la même file FIFO → le serveur voit le texte à jour.
        sendDidChange(pending);
    }

    /**
     * ★ R1 : true si les capabilities serveur annoncent une sync
     * INCRÉMENTALE — soit la forme bare {@link org.eclipse.lsp4j.TextDocumentSyncKind}
     * (notre serveur Java), soit {@code TextDocumentSyncOptions.change}.
     * Capabilities absentes (serveur pas encore initialisé / serveur tiers)
     * → false → envoi FULL universel, aucun risque d'incompatibilité.
     */
    private boolean serverSupportsIncremental() {
        if (wrapper == null) return false;
        org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
        if (caps == null || caps.getTextDocumentSync() == null) return false;
        var sync = caps.getTextDocumentSync();
        if (sync.isLeft()) {
            return sync.getLeft() == org.eclipse.lsp4j.TextDocumentSyncKind.Incremental;
        }
        var opts = sync.getRight();
        return opts != null
                && opts.getChange() == org.eclipse.lsp4j.TextDocumentSyncKind.Incremental;
    }

    /**
     * ★ R1 : convertit un offset en Position LSP SUR LE TEXTE DONNÉ —
     * contrairement à {@link #offsetToPosition(int)} qui lit le buffer LIVE
     * de l'éditeur, cette version travaille sur la BASE synced figée utilisée
     * pour calculer le diff (les deux peuvent avoir divergé entre-temps).
     */
    private static Position positionOfIn(String text, int offset) {
        int line = 0, character = 0;
        int end = Math.min(offset, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') { line++; character = 0; }
            else { character++; }
        }
        return new Position(line, character);
    }

    /** ★ R1 : hint de log (taille édition vs taille fichier). */
    private static String editDeltaHint(String baseline, String text) {
        if (baseline == null) return "full";
        EditSpan e = DiagnosticShift.diffEdit(baseline, text);
        return (e.removed + e.added) + " ch / " + text.length();
    }

    /**
     * v3.33.4: Sends textDocument/didChange on a background thread.
     * The LSP4J launcher dispatches this asynchronously to the server.
     *
     * <p>★ R1 (v0.1.0.51-v2.22) : si le serveur annonce une sync
     * INCRÉMENTALE (capabilities.textDocumentSync), on envoie un unique
     * ContentChangeEvent PORTEUR DE RANGE = {préfixe commun .. suffixe
     * commun} calculé entre la DERNIÈRE BASE synced ({@link #lastSyncedText})
     * et le texte courant — payload réduit de O(fichier) à O(édition).
     * Toute anomalie (base inconnue, bornes incohérentes) retombe sur un
     * event complet SANS RANGE, que tout serveur LSP accepte.
     */
    private void sendDidChange(String text) {
        DidChangeTextDocumentParams params = new DidChangeTextDocumentParams();
        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier();
        id.setUri(fileUri);
        id.setVersion(++documentVersion);
        params.setTextDocument(id);
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
        String baseline = lastSyncedText;
        if (baseline != null && serverSupportsIncremental()) {
            try {
                EditSpan edit = DiagnosticShift.diffEdit(baseline, text);
                // Invariant fondamental du diff préfixe/suffixe : reconstruire
                // la base [0,start)+insertion+[end..)= le texte courant. En cas
                // d'écart (textes mutés entre-temps), repli FULL sans range.
                String insertion = text.substring(edit.start,
                        Math.min(edit.start + edit.added, text.length()));
                String rebuilt = baseline.substring(0, edit.start)
                        + insertion
                        + baseline.substring(edit.end());
                if (!rebuilt.equals(text)) throw new IllegalStateException("diff incohérent");
                change.setRange(new Range(positionOfIn(baseline, edit.start),
                        positionOfIn(baseline, edit.end())));
                change.setText(insertion);
            } catch (Throwable t) {
                change.setRange(null);
                change.setText(text);  // fallback universel full-text
            }
        } else {
            change.setText(text);
        }
        params.setContentChanges(Collections.singletonList(change));
        try {
            wrapper.getServer().getTextDocumentService().didChange(params);
            lastSyncedText = text;  // ★ R1 : la nouvelle base devient synced.
            android.util.Log.i("LspEditor", "didChange v" + documentVersion + " ("
                    + (change.getRange() != null ? "range " + editDeltaHint(baseline, text) : text.length() + " chars") + ")");
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChange v" + documentVersion + " (" + text.length() + " chars)");
        } catch (Exception e) {
            android.util.Log.e("LspEditor", "didChange FAILED: " + e.getMessage(), e);
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChange FAILED: " + e.getMessage());
        }
        // v3.33.10 (I-5 fix): Only schedule a debounced didSave if the
        // server actually advertised a SaveProvider in its capabilities.
        // Previously the didSave was always scheduled after every didChange,
        // which is needed for some servers (EmmyLua re-runs inspection on
        // didSave) but a no-op for others (Java LSP already re-publishes
        // diagnostics on didChange). The waste was up to ~1 orphaned daemon
        // thread per keystroke burst.
        if (serverSupportsSave()) {
            scheduleDebouncedDidSave();
        }
    }

    /**
     * v3.33.10 (I-5 fix): Returns true if the server advertised
     * {@code textDocument.save} support in its capabilities. Some servers
     * (EmmyLua) only re-publish diagnostics on didSave — for those we
     * schedule a debounced didSave after every didChange. Others (Java LSP)
     * publish on didChange directly — for those, the didSave is a wasted
     * roundtrip.
     *
     * <p>In LSP, the save capability is exposed via
     * {@code ServerCapabilities.textDocumentSync} when it's set to a
     * {@link org.eclipse.lsp4j.TextDocumentSyncOptions} struct (rather
     * than a bare {@link org.eclipse.lsp4j.TextDocumentSyncKind}). Servers
     * that use the bare kind (like the Java LSP here) implicitly opt out
     * of save notifications.</p>
     */
    private boolean serverSupportsSave() {
        if (wrapper == null) return false;
        org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
        if (caps == null) return false;
        org.eclipse.lsp4j.jsonrpc.messages.Either<
            org.eclipse.lsp4j.TextDocumentSyncKind,
            org.eclipse.lsp4j.TextDocumentSyncOptions> sync = caps.getTextDocumentSync();
        if (sync == null || sync.isLeft()) {
            // Bare TextDocumentSyncKind — server didn't opt into save.
            return false;
        }
        org.eclipse.lsp4j.TextDocumentSyncOptions opts = sync.getRight();
        return opts.getSave() != null;
    }

    /** v3.5.0: Debounced didSave — fires 800ms after the last didChange. */
    private long lastDidSaveSchedule = 0;
    private static final long DID_SAVE_DEBOUNCE_MS = 800;
    private void scheduleDebouncedDidSave() {
        lastDidSaveSchedule = System.currentTimeMillis();
        final long scheduledAt = lastDidSaveSchedule;
        new Thread(() -> {
            try {
                Thread.sleep(DID_SAVE_DEBOUNCE_MS);
            } catch (InterruptedException e) {
                return;
            }
            // Only fire if no further didChange happened during the wait.
            if (scheduledAt != lastDidSaveSchedule) return;
            sendDidSave();
        }, "lsp-didsave-debounce").start();
    }

    /**
     * v3.5.0: Sends textDocument/didSave to the server. The Java EmmyLua-LS
     * re-runs its inspection pass and publishes fresh diagnostics when it
     * receives this notification (same as didOpen). This is the ONLY way
     * to get live diagnostics on text changes — didChange alone doesn't
     * trigger re-inspection.
     */
    public void sendDidSave() {
        if (!connected || wrapper == null || wrapper.getServer() == null) return;
        try {
            org.eclipse.lsp4j.DidSaveTextDocumentParams params =
                new org.eclipse.lsp4j.DidSaveTextDocumentParams();
            params.setTextDocument(getTextDocumentIdentifier());
            // Include the full text — some servers need it when
            // textDocumentSync.save.includeText is true.
            if (editorView != null && editorView.getSession() != null) {
                params.setText(editorView.getSession().getText().toString());
            }
            wrapper.getServer().getTextDocumentService().didSave(params);
            android.util.Log.i("LspEditor", "didSave sent (triggers diagnostics re-inspection)");
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didSave sent (triggers diagnostics re-inspection)");
        } catch (Exception e) {
            android.util.Log.e("LspEditor", "didSave FAILED: " + e.getMessage(), e);
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didSave FAILED: " + e.getMessage());
        }
    }

    /** Sends didOpen with the current text. */
    private void sendDidOpen() {
        if (editorView == null || wrapper == null || wrapper.getServer() == null) return;
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
        TextDocumentItem item = new TextDocumentItem();
        item.setUri(fileUri);
        item.setLanguageId(ext);
        item.setVersion(documentVersion);
        item.setText(editorView.getSession().getText());
        params.setTextDocument(item);
        try {
            wrapper.getServer().getTextDocumentService().didOpen(params);
            // ★ R1 : enregistre la base synced pour les diffs incrémentaux.
            lastSyncedText = item.getText();
            android.util.Log.i("LspEditor", "didOpen sent: " + fileUri
                + " (" + item.getText().length() + " chars, lang=" + ext + ")");
        } catch (Exception e) {
            android.util.Log.e("LspEditor", "didOpen FAILED: " + e.getMessage(), e);
        }
    }

    /**
     * v3.3.8: Sends workspace/didChangeConfiguration to the server.
     * <p>This is a generic, server-agnostic method — the {@code settings}
     * object is server-specific (e.g. EmmyLua uses flat dotted keys like
     * {@code "emmylua.inspections.undeclaredVariable"}, jdtls uses
     * {@code "java.*"} keys). The caller (app layer) is responsible for
     * building the correct settings object for the connected server.
     * <p>This method does NOT know which server is connected — it just
     * forwards the settings via the LSP workspace service.
     *
     * @param settings the server-specific settings object (e.g. a
     *                 {@link com.google.gson.JsonObject} with the
     *                 appropriate keys for the server)
     */
    public void sendDidChangeConfiguration(Object settings) {
        if (wrapper == null || wrapper.getServer() == null) return;
        // v3.5.0: store the settings on the client so the server can pull
        // them via workspace/configuration. Many servers (EmmyLua, gopls,
        // rust-analyzer) IGNORE the didChangeConfiguration push and instead
        // pull settings on demand — without this, EmmyLua never sees its
        // inspection config and publishes zero diagnostics.
        if (wrapper.getClient() != null) {
            wrapper.getClient().setSettings(settings);
        }
        try {
            org.eclipse.lsp4j.DidChangeConfigurationParams params =
                new org.eclipse.lsp4j.DidChangeConfigurationParams(settings);
            wrapper.getServer().getWorkspaceService().didChangeConfiguration(params);
            android.util.Log.i("LspEditor", "didChangeConfiguration sent (settings cached for pull)");
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChangeConfiguration sent (settings cached for pull)");
        } catch (Exception e) {
            android.util.Log.e("LspEditor", "didChangeConfiguration FAILED: " + e.getMessage(), e);
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChangeConfiguration FAILED: " + e.getMessage());
        }
    }

    /**
     * v3.3.8: Sends workspace/didChangeWorkspaceFolders to the server.
     * <p>Generic method — the caller builds the
     * {@link org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams} with the
     * appropriate workspace folders.
     */
    public void sendDidChangeWorkspaceFolders(
            org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams params) {
        if (wrapper == null || wrapper.getServer() == null) return;
        try {
            wrapper.getServer().getWorkspaceService().didChangeWorkspaceFolders(params);
            android.util.Log.i("LspEditor", "didChangeWorkspaceFolders sent");
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChangeWorkspaceFolders sent");
        } catch (Exception e) {
            android.util.Log.e("LspEditor", "didChangeWorkspaceFolders FAILED: " + e.getMessage(), e);
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChangeWorkspaceFolders FAILED: " + e.getMessage());
        }
    }

    /**
     * Receives diagnostics from the server (called by {@link DefaultLanguageClient}
     * or a custom client). Updates the cached list and invalidates the view.
     */
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        List<Diagnostic> diags = new ArrayList<>();
        if (params.getDiagnostics() != null) {
            for (org.eclipse.lsp4j.Diagnostic lspDiag : params.getDiagnostics()) {
                int start = positionToOffset(lspDiag.getRange().getStart());
                int end = positionToOffset(lspDiag.getRange().getEnd());
                int severity = lspDiag.getSeverity() != null
                    ? lspDiag.getSeverity().ordinal() + 1 : 2; // LSP severity: 1=Error,2=Warning,3=Info,4=Hint
                // Map LSP severity (1=Error,2=Warning,3=Info,4=Hint) to ours (3=error,2=warning,1=info)
                int ourSeverity = severity == 1 ? 3 : severity == 2 ? 2 : 1;
                diags.add(new Diagnostic(start, end, ourSeverity,
                    lspDiag.getMessage(), lspDiag.getCode() != null ? lspDiag.getCode().getLeft() : ""));
            }
        }
        cachedDiagnostics = new CopyOnWriteArrayList<>(diags);
        android.util.Log.i("LspEditor", "publishDiagnostics: " + diags.size() + " diagnostics received");
        final LspLogSink sink = project.getLogSink();
        if (sink != null) {
            sink.log("publishDiagnostics: " + diags.size() + " diagnostics received");
            for (Diagnostic d : diags) {
                sink.log("  - [" + d.severity + "] " + d.message);
            }
        }
        if (editorView != null) {
            // v3.33.9 (I-4 fix): Post setDiagnostics on the UI thread to
            // avoid racing with the draw path. The LSP4J executor calls
            // publishDiagnostics on a background thread — EditorSession
            // fields are not synchronized for mutation.
            final List<Diagnostic> diagsFinal = diags;
            editorView.post(() -> {
                if (editorView != null && editorView.getSession() != null) {
                    editorView.getSession().setDiagnostics(
                        convertToLegacyDiagnostics(diagsFinal));
                    editorView.invalidate();
                }
            });
            // ★ R3 (v0.1.0.51-v2.22) : chaque publication de diagnostics est
            // le signal naturel pour rafraîchir les couleurs sémantiques —
            // un seul déclencheur, déjà cadencé par le debounce didChange,
            // capacité vérifiée (les serveurs sans provider sont ignorés).
            scheduleSemanticTokensPull();
            // ★ v2.33 : même signal pour le FOLDING — tir
            // textDocument/foldingRange throttlé, appliqué via
            // session.applyCodeFolds (état utilisateur préservé).
            scheduleFoldPull();
        }
    }

    /** Converts our Diagnostic list to the legacy DiagnosticShift.Diagnostic format. */
    private List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> convertToLegacyDiagnostics(List<Diagnostic> diags) {
        List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> out = new ArrayList<>();
        for (Diagnostic d : diags) {
            out.add(new jo.codeeditor.shift.DiagnosticShift.Diagnostic(
                d.start, d.end, d.severity, d.message));
        }
        return out;
    }

    /** Returns the cached diagnostics from the server. */
    public List<Diagnostic> getCachedDiagnostics() {
        return new ArrayList<>(cachedDiagnostics);
    }

    // ══ ★ R3 (v0.1.0.51-v2.22) : surlignage sémantique (tir client) ══

    /** Exécuteur dédié aux tirs textDocument/semanticTokens/full (daemon). */
    private static final java.util.concurrent.ExecutorService SEMANTIC_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cel-lsp-semantic");
                t.setDaemon(true);
                return t;
            });

    /** Génération anti-obsolescence : toute nouvelle demande invalide la précédente. */
    private final java.util.concurrent.atomic.AtomicInteger semanticGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Interval minimal entre deux tirs complets (-pattern SEMANTIC pass daemon CodeAssist). */
    private static final long SEMANTIC_MIN_INTERVAL_MS = 700;

    private volatile long lastSemanticPullMs = 0;

    /**
     * Programme un tir {@code textDocument/semanticTokens/full} throttlé.
     * No-op total si le serveur connecté n'annonce PAS de provider
     * (capabilities nulles pendant l'init, serveurs tiers sans support).
     */
    private void scheduleSemanticTokensPull() {
        if (!connected || wrapper == null || wrapper.getServer() == null) return;
        if (editorView == null || editorView.getSession() == null) return;
        try {
            org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
            if (caps == null || caps.getSemanticTokensProvider() == null) return;
        } catch (Throwable t) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lastSemanticPullMs;
        if (elapsed < SEMANTIC_MIN_INTERVAL_MS) {
            // Un seul re-tir en fin de fenêtre — jamais de rafale sous la frappe.
            final int genAtSchedule = semanticGeneration.get();
            final long delay = SEMANTIC_MIN_INTERVAL_MS - elapsed;
            editorView.postDelayed(() -> {
                if (connected && semanticGeneration.get() == genAtSchedule) {
                    doPullSemanticTokens();
                }
            }, delay);
            return;
        }
        doPullSemanticTokens();
    }

    /** Tir async — ne bloque AUCUN thread UI ; réponse la plus récente gagne. */
    private void doPullSemanticTokens() {
        lastSemanticPullMs = System.currentTimeMillis();
        final int gen = semanticGeneration.incrementAndGet();
        SEMANTIC_EXECUTOR.execute(() -> {
            try {
                if (!connected || wrapper == null || wrapper.getServer() == null) return;
                org.eclipse.lsp4j.SemanticTokensParams params =
                        new org.eclipse.lsp4j.SemanticTokensParams(
                                getTextDocumentIdentifier());
                var future = wrapper.getServer().getTextDocumentService()
                        .semanticTokensFull(params);
                org.eclipse.lsp4j.SemanticTokens result;
                try {
                    result = future.get(12, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                    return;
                } catch (java.util.concurrent.ExecutionException ee) {
                    return;
                }
                if (gen != semanticGeneration.get()) return; // obsolète
                if (result == null || result.getData() == null
                        || result.getData().isEmpty()) return;
                final List<Integer> flat = result.getData();
                // Conversion offset → appliquée sur le THREAD UI (session).
                if (editorView != null) {
                    editorView.post(() -> applySemanticTokens(gen, flat));
                }
            } catch (Throwable t) {
                android.util.Log.w("LspEditor", "semanticTokens failed: " + t);
            }
        });
    }

    /**
     * Décode les deltas LSP (line/char cumulés) vers des offsets éditeur sur
     * le document VIVANT et alimente {@code EditorSession.setSemanticTokens}
     * — le branchement qui manquait au rendu drawCachedSemSpans existant.
     * Appelé EXCLUSIVEMENT sur le thread UI.
     */
    private void applySemanticTokens(int gen, List<Integer> flat) {
        if (!connected || editorView == null || editorView.getSession() == null) return;
        if (gen != semanticGeneration.get()) return;
        List<jo.codeeditor.shift.DiagnosticShift.SemanticToken> tokens =
                new ArrayList<>(Math.max(8, flat.size() / 5));
        try {
            int line = 0, col = 0;
            for (int i = 0; i + 4 < flat.size(); i += 5) {
                int deltaLine = flat.get(i);
                int deltaChar = flat.get(i + 1);
                int length = Math.max(1, flat.get(i + 2));
                int type = flat.get(i + 3);
                line += deltaLine;
                col = deltaLine == 0 ? col + deltaChar : deltaChar;
                if (line < 0 || col < 0) continue;
                int start = positionToOffset(new Position(line, col));
                tokens.add(new jo.codeeditor.shift.DiagnosticShift.SemanticToken(
                        start, length, type));
            }
        } catch (Throwable ignored) {
            // Positions hors bornes (buffer divergé) — token partiel gardé.
        }
        try {
            editorView.getSession().setSemanticTokens(tokens);
            editorView.invalidate();
            android.util.Log.i("LspEditor", "semanticTokens applied: " + tokens.size());
        } catch (Throwable t) {
            android.util.Log.w("LspEditor", "setSemanticTokens failed: " + t);
        }
    }

    /** Returns the LSP text document identifier for this file. */
    public TextDocumentIdentifier getTextDocumentIdentifier() {
        return new TextDocumentIdentifier(fileUri);
    }

    // ══ ★ v2.33 : folding serveur (tir client, pattern semanticTokens) ══

    /** Exécuteur dédié aux tirs textDocument/foldingRange (daemon). */
    private static final java.util.concurrent.ExecutorService FOLD_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cel-lsp-folds");
                t.setDaemon(true);
                return t;
            });

    /** Génération anti-obsolescence : toute nouvelle demande invalide la précédente. */
    private final java.util.concurrent.atomic.AtomicInteger foldGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Interval minimal entre deux tirs complets. */
    private static final long FOLD_MIN_INTERVAL_MS = 900;

    private volatile long lastFoldPullMs = 0;

    /**
     * Programme un tir {@code textDocument/foldingRange} throttlé. No-op si
     * le serveur n'annonce PAS le provider, ou tant que le language SPI
     * n'est pas posé (garde anti-course avec le detectFolds lexical de
     * setLanguage).
     */
    private void scheduleFoldPull() {
        if (!connected || wrapper == null || wrapper.getServer() == null) return;
        if (editorView == null || editorView.getSession() == null) return;
        if (!languageApplied) return; // setLanguage pas encore appliqué
        try {
            org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
            if (caps == null || caps.getFoldingRangeProvider() == null) return;
        } catch (Throwable t) {
            return;
        }
        long now = System.currentTimeMillis();
        long elapsed = now - lastFoldPullMs;
        if (elapsed < FOLD_MIN_INTERVAL_MS) {
            final int genAtSchedule = foldGeneration.get();
            final long delay = FOLD_MIN_INTERVAL_MS - elapsed;
            editorView.postDelayed(() -> {
                if (connected && languageApplied
                        && foldGeneration.get() == genAtSchedule) {
                    doPullFolds();
                }
            }, delay);
            return;
        }
        doPullFolds();
    }

    /** Tir async — ne bloque AUCUN thread UI ; réponse la plus récente gagne. */
    private void doPullFolds() {
        lastFoldPullMs = System.currentTimeMillis();
        final int gen = foldGeneration.incrementAndGet();
        FOLD_EXECUTOR.execute(() -> {
            try {
                if (!connected || wrapper == null || wrapper.getServer() == null) return;
                org.eclipse.lsp4j.FoldingRangeRequestParams params =
                        new org.eclipse.lsp4j.FoldingRangeRequestParams(
                                getTextDocumentIdentifier());
                var future = wrapper.getServer().getTextDocumentService()
                        .foldingRange(params);
                List<org.eclipse.lsp4j.FoldingRange> result;
                try {
                    result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException
                        | java.util.concurrent.ExecutionException te) {
                    return;
                }
                if (gen != foldGeneration.get()) return; // obsolète
                if (result == null) return;
                if (editorView != null) {
                    editorView.post(() -> applyFolds(gen, result));
                }
            } catch (Throwable t) {
                android.util.Log.w("LspEditor", "foldingRange failed: " + t);
            }
        });
    }

    /**
     * Convertit les FoldingRange (ligne/caractère) en FoldRegion offsets sur
     * le document VIVANT et alimente {@code session.applyCodeFolds} —
     * l'état utilisateur (régions repliées) est préservé, et le groupe
     * d'imports ({@code kind="imports"}) se replie PAR DÉFAUT la première
     * fois (CodeAssist applyCodeFolds). Appelé EXCLUSIVEMENT sur le thread UI.
     */
    private void applyFolds(int gen, List<org.eclipse.lsp4j.FoldingRange> ranges) {
        if (!connected || editorView == null || editorView.getSession() == null) return;
        if (gen != foldGeneration.get()) return;
        if (!languageApplied) return;
        try {
            List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> regions =
                    new ArrayList<>(ranges.size());
            for (org.eclipse.lsp4j.FoldingRange r : ranges) {
                if (r == null) continue;
                org.eclipse.lsp4j.Position start = new org.eclipse.lsp4j.Position(
                        r.getStartLine(),
                        r.getStartCharacter() != null ? r.getStartCharacter()
                                : Integer.valueOf(0));
                org.eclipse.lsp4j.Position end = new org.eclipse.lsp4j.Position(
                        r.getEndLine(),
                        r.getEndCharacter() != null ? r.getEndCharacter()
                                : Integer.valueOf(0));
                int startOffset = positionToOffset(start);
                int endOffset = positionToOffset(end);
                if (endOffset <= startOffset) continue;
                String placeholder = r.getCollapsedText() != null
                        ? r.getCollapsedText() : "…";
                boolean collapsedByDefault = "imports".equals(r.getKind());
                regions.add(new jo.codeeditor.shift.DiagnosticShift.FoldRegion(
                        startOffset, endOffset, placeholder,
                        r.getKind() != null ? r.getKind() : "block",
                        false, collapsedByDefault));
            }
            editorView.getSession().applyCodeFolds(regions);
            editorView.invalidate();
            android.util.Log.i("LspEditor", "folds applied: " + regions.size());
        } catch (Throwable t) {
            android.util.Log.w("LspEditor", "applyFolds failed: " + t);
        }
    }

    /**
     * Converts an editor offset to an LSP {@link Position} (line, character).
     */
    public Position offsetToPosition(int offset) {
        if (editorView == null) return new Position(0, 0);
        jo.codeeditor.document.EditorDocument doc = editorView.getSession().getDocument();
        offset = Math.max(0, Math.min(offset, doc.length()));
        int line = doc.lineForOffset(offset);
        int col = offset - doc.lineStart(line);
        return new Position(line, col);
    }

    /**
     * Converts an LSP {@link Position} to an editor offset.
     */
    public int positionToOffset(Position pos) {
        if (editorView == null) return 0;
        jo.codeeditor.document.EditorDocument doc = editorView.getSession().getDocument();
        if (doc == null) return 0;
        int line = Math.max(0, Math.min(pos.getLine(), doc.lineCount() - 1));
        int lineStart = doc.lineStart(line);
        int lineEnd = doc.lineEnd(line);
        int col = Math.max(0, Math.min(pos.getCharacter(), lineEnd - lineStart));
        return lineStart + col;
    }

    /** Returns the file URI. */
    public String getFileUri() { return fileUri; }

    /**
     * v3.33.10: Returns the bound {@link EditorView}, or null if not yet
     * bound. Used by inlay hint provider to convert line/col→offset on
     * the document.
     */
    public EditorView getEditorView() { return editorView; }

    /** v3.3.3: Returns the parent project (for log sink access). */
    public LspProject getProject() { return project; }

    /** Returns true if connected to the server. */
    public boolean isConnected() { return connected; }

    /** Returns the {@link LspLanguage} (null until connected). */
    public LspLanguage getLanguage() { return language; }

    /**
     * v3.33.12: Applies an LSP {@link org.eclipse.lsp4j.WorkspaceEdit} to the
     * bound editor. Called when the user accepts a code action. Walks the
     * edit's changes (per-URI) and applies them to the editor's session —
     * for the current file URI, applies the TextEdits; for other URIs,
     * logs and ignores (cross-file edits require opening the target file,
     * which is a follow-up).
     *
     * <p>Must be called on the UI thread (mutates the session).</p>
     */
    public void applyWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit) {
        if (editorView == null || editorView.getSession() == null) {
            android.util.Log.w("LspEditor", "applyWorkspaceEdit: no editor bound");
            return;
        }
        if (edit == null) return;
        // v3.33.12: prefer documentChanges (LSP 3.16+) which carry
        // OptionalVersionedTextDocumentIdentifier; fall back to changes
        // (LSP 3.13) keyed by URI.
        //
        // ★ v0.1.0.50 : les fichiers TIERS ne sont plus ignorés — leurs
        // éditions sont réécrites SUR DISQUE (même sémantique que le
        // renommage multi-fichiers : offsets triés décroissants), en
        // tâche de fond pour éviter tout IO sur le thread UI.
        java.util.List<org.eclipse.lsp4j.jsonrpc.messages.Either<
            org.eclipse.lsp4j.TextDocumentEdit,
            org.eclipse.lsp4j.ResourceOperation>> docChanges = edit.getDocumentChanges();
        if (docChanges != null && !docChanges.isEmpty()) {
            for (org.eclipse.lsp4j.jsonrpc.messages.Either<
                org.eclipse.lsp4j.TextDocumentEdit,
                org.eclipse.lsp4j.ResourceOperation> either : docChanges) {
                if (either == null || !either.isLeft()) continue;
                org.eclipse.lsp4j.TextDocumentEdit tde = either.getLeft();
                if (tde == null) continue;
                String uri = tde.getTextDocument().getUri();
                if (!fileUri.equals(uri)) {
                    applyOtherFileEditsAsync(uri, tde.getEdits());
                    continue;
                }
                applyTextEdits(tde.getEdits());
            }
            return;
        }
        java.util.Map<String, java.util.List<org.eclipse.lsp4j.TextEdit>> changes = edit.getChanges();
        if (changes == null) return;
        for (java.util.Map.Entry<String, java.util.List<org.eclipse.lsp4j.TextEdit>> entry
                : changes.entrySet()) {
            if (!fileUri.equals(entry.getKey())) {
                applyOtherFileEditsAsync(entry.getKey(), entry.getValue());
            } else {
                applyTextEdits(entry.getValue());
            }
        }
    }

    /**
     * ★ v0.1.0.50 : applique les éditions d'un fichier NON ouvert en
     * réécrivant le fichier sur disque (thread de fond). Logging best-effort ;
     * un échec n'affecte pas les autres fichiers du WorkspaceEdit.
     */
    private void applyOtherFileEditsAsync(String uri,
            java.util.List<org.eclipse.lsp4j.TextEdit> edits) {
        if (uri == null || edits == null || edits.isEmpty()) return;
        final String fUri = uri;
        final java.util.List<org.eclipse.lsp4j.TextEdit> fEdits =
                new java.util.ArrayList<>(edits);
        DISK_EDITS_EXECUTOR.execute(() -> {
            int applied = applyEditsToDiskFile(fUri, fEdits);
            android.util.Log.i("LspEditor",
                "applyWorkspaceEdit: " + applied + "/" + fEdits.size()
                    + " éditions appliquées sur disque pour " + fUri);
        });
    }

    /** Exécuteur dédié aux réécritures disque des fichiers tiers (daemon). */
    private static final java.util.concurrent.ExecutorService DISK_EDITS_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "cel-lsp-disk-edits");
                t.setDaemon(true);
                return t;
            });

    /**
     * Applique des éditions LSP à un fichier NON ouvert : lecture disque,
     * application en ordre décroissant d'offset, réécriture. Retourne le
     * nombre d'éditions appliquées.
     */
    private static int applyEditsToDiskFile(String uri,
            java.util.List<org.eclipse.lsp4j.TextEdit> edits) {
        if (uri == null || edits == null || edits.isEmpty()) return 0;
        String path = uri.startsWith("file://") ? uri.substring("file://".length()) : uri;
        java.io.File file = new java.io.File(path);
        if (!file.isFile()) return 0;
        try {
            // v3.34.0: API-24-safe read (was Files.readAllBytes — API 26+, crashed on Android 7.x).
            String content = IoCompat.readUtf8(file);
            if (content == null) return 0;
            List<int[]> offsets = new java.util.ArrayList<>(edits.size());
            List<String> texts = new java.util.ArrayList<>(edits.size());
            for (org.eclipse.lsp4j.TextEdit te : edits) {
                int start = positionToOffsetFor(content, te.getRange().getStart());
                int end = positionToOffsetFor(content, te.getRange().getEnd());
                if (start < 0 || end < start || end > content.length()) return 0;
                offsets.add(new int[]{start, end});
                texts.add(te.getNewText() == null ? "" : te.getNewText());
            }
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
            android.util.Log.w("LspEditor", "applyEditsToDiskFile failed: " + e.getMessage());
            return 0;
        }
    }

    /** Position LSP → offset sur un texte donné (fichier tiers). */
    private static int positionToOffsetFor(String text, org.eclipse.lsp4j.Position pos) {
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

    /**
     * Applies a list of LSP TextEdits to the editor's session. Sorts them
     * by descending start offset so earlier edits don't shift later
     * offsets.
     */
    private void applyTextEdits(java.util.List<org.eclipse.lsp4j.TextEdit> edits) {
        if (editorView == null || editorView.getSession() == null) return;
        // Sort descending by start offset so we can apply each edit without
        // invalidating later offsets.
        java.util.List<org.eclipse.lsp4j.TextEdit> sorted = new java.util.ArrayList<>(edits);
        sorted.sort((a, b) -> Integer.compare(
            positionToOffset(b.getRange().getStart()),
            positionToOffset(a.getRange().getStart())));
        jo.codeeditor.session.EditorSession session = editorView.getSession();
        jo.codeeditor.document.EditorDocument doc = session.getDocument();
        StringBuilder sb = new StringBuilder(session.getText());
        for (org.eclipse.lsp4j.TextEdit te : sorted) {
            int start = positionToOffset(te.getRange().getStart());
            int end = positionToOffset(te.getRange().getEnd());
            if (start < 0 || end > sb.length() || start > end) continue;
            sb.replace(start, end, te.getNewText() != null ? te.getNewText() : "");
        }
        String newText = sb.toString();
        if (!newText.equals(session.getText().toString())) {
            session.replaceRange(0, doc.length(), newText);
            editorView.notifyTextChanged();
            android.util.Log.i("LspEditor", "applyWorkspaceEdit: applied " + sorted.size() + " edits");
        }
    }

    /**
     * v0.1.0.50: Applique des éditions LSP ADDITIONNELLES (ex:
     * {@code additionalTextEdits} d'un candidat de complétion — insertion
     * auto-import) sur la session courante.
     *
     * <p>Contrairement à {@link #applyWorkspaceEdit} ce chemin accepte les
     * éditions quel que soit leur URI : elles proviennent du document
     * courant (le serveur calcule les positions pour la version qui a servi
     * la complétion). L'insertion de l'identifiant à l'acceptation ne change
     * pas le nombre de lignes, donc les positions d'import (toujours placé
     * AVANT le curseur) restent valides.</p>
     *
     * <p>Public : appelé par le Runnable post-accept transporté sur
     * {@code CompletionSession.Item.attachment}.</p>
     */
    public void applyAdditionalEdits(java.util.List<org.eclipse.lsp4j.TextEdit> edits) {
        if (edits == null || edits.isEmpty()) return;
        applyTextEdits(edits);
    }
}
