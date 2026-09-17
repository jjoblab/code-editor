package jo.codeeditor.lsp;

import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.shift.EditSpan;
import jo.codeeditor.view.EditorView;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;

import java.util.Collections;

/**
 * Synchronisation du document ouvert avec le serveur LSP pour un
 * {@link LspEditor} : notifications didOpen/didChange/didSave/didClose,
 * débounce des envois, sync incrémentale (diff préfixe/suffixe contre la
 * dernière base connue du serveur) et versionnement du document. Extrait de
 * {@link LspEditor} pour isoler cette responsabilité du reste du pont
 * éditeur↔serveur.
 */
class LspDocumentSynchronizer {

    private final LspEditor editor;

    LspDocumentSynchronizer(LspEditor editor) {
        this.editor = editor;
    }

    /**
     * ★ R1 : base textuelle KNOWN-SYNCED au serveur.
     * Chaque didOpen/didChange réussi met à jour cette copie ; le prochain
     * didChange calcule un diff préfixe/suffixe contre CETTE base (jamais
     * contre le buffer live qui peut avoir divergé par des éditions
     * programmatiques — auto-imports, quick-fixes) et n'envoie que le
     * Range modifié. null tant que non connecté → premier envoi full.
     */
    private volatile String lastSyncedText = null;

    private int documentVersion = 0;

    /** Le texte en attente d'envoi via didChange. */
    private volatile String pendingText = null;
    private volatile long lastDidChangeSchedule = 0;
    private static final long DID_CHANGE_DEBOUNCE_MS = 300;

    private volatile Thread didChangeThread;

    /**
     * Appelé quand le texte de l'éditeur change. Envoie une notification
     * didChange (sync complète ou incrémentale selon le serveur).
     *
     * <p>Débounce de 300 ms + envoi sur un thread de fond pour ne pas
     * bloquer le thread UI. Le didChange est envoyé via l'exécuteur LSP4J
     * (asynchrone) — le serveur le traite sur son propre thread et pousse
     * les diagnostics en retour via publishDiagnostics (asynchrone aussi).</p>
     */
    void onTextChanged(CharSequence text) {
        if (!editor.isConnected()) return;
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (wrapper == null || wrapper.getServer() == null) return;
        // Débounce — stocke le dernier texte et programme un didChange
        // après DID_CHANGE_DEBOUNCE_MS. Une frappe rapide (10+ touches/s)
        // ne spammera pas le serveur d'un didChange par frappe.
        pendingText = text.toString();
        scheduleDebouncedDidChange();
    }

    /**
     * Programme un didChange débouncé sur un thread de fond. Se
     * déclenche DID_CHANGE_DEBOUNCE_MS après le dernier appel à
     * onTextChanged.
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
                return; // supplanté par un didChange plus récent
            }
            // Ne tire que si aucun autre onTextChanged n'est survenu pendant
            // l'attente.
            if (scheduledAt != lastDidChangeSchedule) return;
            sendDidChange(textToSend);
        }, "lsp-didchange-debounce");
        didChangeThread.setDaemon(true);
        didChangeThread.start();
    }

    /**
     * Flush SYNCHRONE du didChange en attente.
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
    void flushPendingChange() {
        if (!editor.isConnected()) return;
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (wrapper == null || wrapper.getServer() == null) return;
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
        LanguageServerWrapper wrapper = editor.currentWrapper();
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
     * contrairement à {@link LspEditor#offsetToPosition(int)} qui lit le buffer LIVE
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
     * Envoie textDocument/didChange sur un thread de fond. Le launcher
     * LSP4J le distribue de manière asynchrone au serveur.
     *
     * <p>★ R1 : si le serveur annonce une sync
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
        id.setUri(editor.getFileUri());
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
                change.setText(text);  // repli universel texte complet
            }
        } else {
            change.setText(text);
        }
        params.setContentChanges(Collections.singletonList(change));
        try {
            editor.currentWrapper().getServer().getTextDocumentService().didChange(params);
            lastSyncedText = text;  // ★ R1 : la nouvelle base devient synced.
            android.util.Log.i("LspEditor", "didChange v" + documentVersion + " ("
                    + (change.getRange() != null ? "range " + editDeltaHint(baseline, text) : text.length() + " chars") + ")");
            final LspLogSink sink = editor.getProject().getLogSink();
            if (sink != null) sink.log("didChange v" + documentVersion + " (" + text.length() + " chars)");
        } catch (Exception e) {
            android.util.Log.e("LspEditor", "didChange FAILED: " + e.getMessage(), e);
            final LspLogSink sink = editor.getProject().getLogSink();
            if (sink != null) sink.log("didChange FAILED: " + e.getMessage());
        }
        // Ne programme un didSave débouncé que si le serveur a réellement
        // annoncé un SaveProvider dans ses capabilities. Un didSave
        // systématique après chaque didChange est nécessaire pour certains
        // serveurs (EmmyLua relance l'inspection sur didSave) mais une no-op
        // pour d'autres (le LSP Java republie déjà les diagnostics sur
        // didChange) — le gaspillage allait jusqu'à ~1 thread daemon orphelin
        // par salve de frappe.
        if (serverSupportsSave()) {
            scheduleDebouncedDidSave();
        }
    }

    /**
     * Retourne true si le serveur a annoncé la prise en charge de
     * {@code textDocument.save} dans ses capabilities. Certains serveurs
     * (EmmyLua) ne republient les diagnostics que sur didSave — pour eux,
     * un didSave débouncé est programmé après chaque didChange. D'autres
     * (LSP Java) publient directement sur didChange — pour eux, le didSave
     * est un aller-retour gaspillé.
     *
     * <p>En LSP, la capacité save est exposée via
     * {@code ServerCapabilities.textDocumentSync} quand celui-ci vaut une
     * structure {@link org.eclipse.lsp4j.TextDocumentSyncOptions} (plutôt
     * qu'un {@link org.eclipse.lsp4j.TextDocumentSyncKind} nu). Les serveurs
     * qui utilisent le kind nu (comme le LSP Java ici) se désinscrivent
     * implicitement des notifications save.</p>
     */
    private boolean serverSupportsSave() {
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (wrapper == null) return false;
        org.eclipse.lsp4j.ServerCapabilities caps = wrapper.getCapabilities();
        if (caps == null) return false;
        org.eclipse.lsp4j.jsonrpc.messages.Either<
            org.eclipse.lsp4j.TextDocumentSyncKind,
            org.eclipse.lsp4j.TextDocumentSyncOptions> sync = caps.getTextDocumentSync();
        if (sync == null || sync.isLeft()) {
            // TextDocumentSyncKind nu — le serveur ne s'est pas inscrit au
            // save.
            return false;
        }
        org.eclipse.lsp4j.TextDocumentSyncOptions opts = sync.getRight();
        return opts.getSave() != null;
    }

    /** didSave débouncé — se déclenche 800 ms après le dernier didChange. */
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
            // Ne tire que si aucun autre didChange n'est survenu pendant
            // l'attente.
            if (scheduledAt != lastDidSaveSchedule) return;
            sendDidSave();
        }, "lsp-didsave-debounce").start();
    }

    /**
     * Envoie textDocument/didSave au serveur. Le serveur EmmyLua-LS relance
     * sa passe d'inspection et publie des diagnostics frais à la réception
     * de cette notification (comme didOpen). C'est le SEUL moyen d'obtenir
     * des diagnostics vivants sur les changements de texte — didChange seul
     * ne déclenche pas la ré-inspection.
     */
    void sendDidSave() {
        if (!editor.isConnected()) return;
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (wrapper == null || wrapper.getServer() == null) return;
        try {
            org.eclipse.lsp4j.DidSaveTextDocumentParams params =
                new org.eclipse.lsp4j.DidSaveTextDocumentParams();
            params.setTextDocument(editor.getTextDocumentIdentifier());
            // Inclut le texte complet — certains serveurs l'exigent quand
            // textDocumentSync.save.includeText est true.
            if (editor.getEditorView() != null && editor.getEditorView().getSession() != null) {
                params.setText(editor.getEditorView().getSession().getText().toString());
            }
            wrapper.getServer().getTextDocumentService().didSave(params);
            android.util.Log.i("LspEditor", "didSave sent (triggers diagnostics re-inspection)");
            final LspLogSink sink = editor.getProject().getLogSink();
            if (sink != null) sink.log("didSave sent (triggers diagnostics re-inspection)");
        } catch (Exception e) {
            android.util.Log.e("LspEditor", "didSave FAILED: " + e.getMessage(), e);
            final LspLogSink sink = editor.getProject().getLogSink();
            if (sink != null) sink.log("didSave FAILED: " + e.getMessage());
        }
    }

    /** Envoie didOpen avec le texte courant. */
    void sendDidOpen() {
        EditorView editorView = editor.getEditorView();
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (editorView == null || wrapper == null || wrapper.getServer() == null) return;
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
        TextDocumentItem item = new TextDocumentItem();
        item.setUri(editor.getFileUri());
        item.setLanguageId(editor.extension());
        item.setVersion(documentVersion);
        item.setText(editorView.getSession().getText());
        params.setTextDocument(item);
        try {
            wrapper.getServer().getTextDocumentService().didOpen(params);
            // ★ R1 : enregistre la base synced pour les diffs incrémentaux.
            lastSyncedText = item.getText();
            android.util.Log.i("LspEditor", "didOpen sent: " + editor.getFileUri()
                + " (" + item.getText().length() + " chars, lang=" + editor.extension() + ")");
        } catch (Exception e) {
            android.util.Log.e("LspEditor", "didOpen FAILED: " + e.getMessage(), e);
        }
    }

    /**
     * Envoie didClose et annule le thread de debounce didChange en cours
     * (pour éviter qu'il n'écrive dans un pipe fermé → « Write end dead »).
     */
    void sendDidClose() {
        // Annule le thread de debounce didChange en cours pour éviter
        // qu'il n'écrive dans un pipe fermé → « Write end dead ».
        if (didChangeThread != null) {
            didChangeThread.interrupt();
            didChangeThread = null;
        }
        try {
            LanguageServerWrapper wrapper = editor.currentWrapper();
            if (wrapper != null && wrapper.getServer() != null) {
                org.eclipse.lsp4j.DidCloseTextDocumentParams params =
                    new org.eclipse.lsp4j.DidCloseTextDocumentParams();
                params.setTextDocument(new TextDocumentIdentifier(editor.getFileUri()));
                wrapper.getServer().getTextDocumentService().didClose(params);
            }
        } catch (Exception ignored) {}
    }

    /** La version courante du document (incrémentée à chaque didChange). */
    int getDocumentVersion() {
        return documentVersion;
    }
}
