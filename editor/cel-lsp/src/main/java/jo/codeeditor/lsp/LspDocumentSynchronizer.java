package jo.codeeditor.lsp;

import jo.codeeditor.lang.host.EditorHost;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Synchronisation du document ouvert avec le serveur LSP pour un
 * {@link LspEditor} : notifications didOpen/didChange/didSave/didClose,
 * débounce des envois, sync incrémentale et versionnement du document.
 * Extrait de {@link LspEditor} pour isoler cette responsabilité du reste
 * du pont éditeur↔serveur.
 *
 * <p><b>P-02 (audit 3.69.0) — sync incrémentale pilotée par les éditions
 * suivies.</b> L'ancien chemin matérialisait le texte complet à CHAQUE
 * frappe côté éditeur (le pont LSP appelait {@code getText()} dans le
 * listener d'édition), puis au moment de l'envoi recalculait un diff
 * préfixe/suffixe sur les textes entiers (O(n) bornes) et RECONSTRUISAIT
 * le document en trois concaténations pour VÉRIFIER le diff (trois copies
 * O(n) de plus, par envoi). Le nouveau chemin ne copie plus rien sur le
 * chemin de frappe : chaque édition suivie par la session est enregistrée
 * avec ses positions LSP calculées au moment de l'édition (O(log n) via
 * l'index de lignes du document) et une vue immuable de la rope après
 * l'édition (capture O(1)). À l'envoi, les événements sont émis TELS
 * QUELS en événements de contenu séquentiels (sémantique LSP : chaque
 * plage se rapporte à l'état après les événements précédents —
 * exactement les coordonnées dans lesquelles ils ont été enregistrés).
 * Deux invariants O(1) remplacent la reconstruction de vérification :
 * consécutivité des révisions du document (toute mutation non suivie,
 * même à longueur constante, rompt la chaîne) et longueur finale attendue.
 * Tout écart, tout serveur sans sync incrémentale, toute anomalie →
 * repli plein texte (universellement accepté).</p>
 */
class LspDocumentSynchronizer {

    private final LspEditor editor;

    LspDocumentSynchronizer(LspEditor editor) {
        this.editor = editor;
    }

    /**
     * ★ P-02 : une édition suivie, en attente d'envoi. Les positions LSP
     * sont FIGÉES au moment de l'enregistrement, dans les coordonnées du
     * document après les éditions précédentes — soit exactement l'état
     * que le serveur reconstruit en appliquant les événements précédents
     * dans l'ordre. {@code revision} est la révision du document APRÈS
     * cette édition (chaque épissure incrémente la révision d'exactement
     * un : la consécutivité des révisions prouve qu'aucune mutation non
     * suivie ne s'est glissée entre deux événements).
     */
    static final class TrackedEdit {
        final int startLine;
        final int startChar;
        final int endLine;
        final int endChar;
        final String text;
        /** Variation nette de la longueur du document (inséré − retiré). */
        final int delta;
        /** Révision du document APRÈS l'édition. */
        final int revision;

        TrackedEdit(int startLine, int startChar, int endLine, int endChar,
                String text, int delta, int revision) {
            this.startLine = startLine;
            this.startChar = startChar;
            this.endLine = endLine;
            this.endChar = endChar;
            this.text = text;
            this.delta = delta;
            this.revision = revision;
        }
    }

    /**
     * ★ P-02 : état du document CÔTÉ SERVEUR — vue immuable (rope d'une
     * révision), jamais copiée. Anciennement une copie {@code String}
     * complète retenue par document. La vue partage les feuilles de la
     * rope : sa longueur (invariant de cohérence) se lit en O(1).
     */
    private volatile CharSequence lastSynced;

    /** Révision du document au moment où {@link #lastSynced} a été synchronisé. */
    private volatile int lastSyncedRevision = -1;

    /**
     * Éditions suivies en attente d'envoi. Accès synchronisé sur la
     * liste : enregistrement sur le thread de la session (frappe),
     * captation sur le thread du débounceur ou du flush — jamais
     * simultanément.
     */
    private final List<TrackedEdit> pending = new ArrayList<>();

    /** Dernière vue du document après la dernière édition suivie (pour le repli). */
    private volatile CharSequence lastTracked;

    /**
     * Version du document LSP — strictement croissante, thread-safe :
     * les envois viennent du thread de debounce, de
     * {@code flushPendingChange} (exécuteur de complétion) et de
     * didOpen ; un simple int produisait des versions dupliquées sous
     * charge (la spécification LSP exige une croissance stricte).
     */
    private final VersionCounter documentVersion = new VersionCounter();

    private static final long DID_CHANGE_DEBOUNCE_MS = 300;

    private static final long DID_SAVE_DEBOUNCE_MS = 800;

    /** Débounce didChange/didSave : exécuteur mono-thread daemon.
     *  ★ 3.74.0 : non final — la reconnexion RENAÎT l'instance fermée
     *  par le didClose précédent (un exécuteur arrêté ne redémarre
     *  pas : sans renaissance, toute édition suivant une reconnexion
     *  serait perdue — ou pire, lèverait dans le pipeline de la
     *  session hôte). */
    private volatile LspDebouncer didChangeDebouncer =
            new LspDebouncer("didchange-debounce", DID_CHANGE_DEBOUNCE_MS);
    private volatile LspDebouncer didSaveDebouncer =
            new LspDebouncer("didsave-debounce", DID_SAVE_DEBOUNCE_MS);

    /**
     * ★ P-02 : enregistre une édition suivie par la session (le pont
     * LspEditor relaie le listener 4-arg de {@code OnTextEditListener}).
     * Coût par frappe : deux recherches binaires O(log n) pour les
     * positions, une capture O(1) de la rope, un petit objet — PLUS
     * AUCUNE matérialisation du document.
     *
     * <p>Position de DÉBUT : lisible directement sur le document
     * post-édition — le préfixe [0, start) est inchangé par l'édition,
     * donc ligne/caractère y sont identiques. Position de FIN : bornes
     * pré-édition (l'état que le serveur doit remplacer), reconstruites
     * depuis le début + la structure du texte retiré (comptage des
     * sauts de ligne) — le document ayant déjà muté, l'arrière-garde
     * n'y est plus lisible directement.</p>
     */
    void onTextEdit(jo.codeeditor.session.EditorSession session,
            int start, int end, String inserted, String removed) {
        if (!editor.isConnected()) return; // didOpen resynchronisera à la reconnexion.
        if (start == end && (inserted == null || inserted.isEmpty())) {
            return; // no-op de caret pur — rien à suivre, aucune révision consommée.
        }
        if (inserted == null) inserted = "";
        jo.codeeditor.document.EditorDocument doc = session.getDocument();
        int revision = doc.getRevision();
        int startLine = doc.lineForOffset(start);
        int startChar = start - doc.lineStart(startLine);
        int endLine, endChar;
        if (removed == null || removed.isEmpty()) {
            endLine = startLine;
            endChar = startChar;
        } else {
            int newlines = 0;
            int lastNewline = -1;
            for (int i = 0; i < removed.length(); i++) {
                if (removed.charAt(i) == '\n') { newlines++; lastNewline = i; }
            }
            endLine = startLine + newlines;
            endChar = (newlines == 0)
                    ? startChar + removed.length()
                    : removed.length() - lastNewline - 1;
        }
        synchronized (pending) {
            pending.add(new TrackedEdit(startLine, startChar, endLine, endChar,
                    inserted, inserted.length() - (end - start), revision));
            lastTracked = doc.charSequence();
        }
        scheduleDebouncedDidChange();
    }

    /**
     * Programme un didChange débouncé. Se déclenche
     * DID_CHANGE_DEBOUNCE_MS après le dernier appel à
     * {@link #onTextEdit} — reprogrammation par {@link LspDebouncer}
     * (un seul exécuteur daemon, jamais de thread par frappe, jamais
     * d'interruption d'un envoi en cours). La tâche CAPTE les événements
     * en attente à l'exécution : la salve la plus récente gagne toujours.
     */
    private void scheduleDebouncedDidChange() {
        didChangeDebouncer.debounce(this::drainAndSend);
    }

    /**
     * Flush SYNCHRONE du didChange en attente.
     *
     * <p>Doit être appelé AVANT toute requête LSP qui dépend du contenu
     * live du document (completion, signatureHelp, hover, definition,
     * codeAction, references, rename…), pour garantir que le serveur
     * voit la version la plus récente du texte.</p>
     *
     * <p><b>Contexte du bug historique</b> : le debounce didChange
     * ({@link #DID_CHANGE_DEBOUNCE_MS} = 300 ms) est PLUS LONG que le
     * debounce completion ({@code COMPLETION_DEBOUNCE_MS} = 120 ms dans
     * popup de complétion). Sans ce flush, une frappe déclenche
     * une requête completion à T+120 ms sur un serveur qui n'a PAS
     * encore reçu le didChange (prévu à T+300 ms) → analyse de contexte
     * périmée côté serveur. La notification didChange est SÉRIALISÉE et
     * mise dans la file LSP4J AVANT le retour de cette méthode : la
     * requête completion qui suit est enfilée APRÈS le didChange dans
     * la même file FIFO.</p>
     *
     * <p>Idempotent : plus rien en attente → ne fait rien.</p>
     */
    void flushPendingChange() {
        if (!editor.isConnected()) return;
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (wrapper == null || wrapper.getServer() == null) return;
        // ★ Annule le debounce différé : son envoi serait en double du
        // nôtre. cancel(false) n'interrompt JAMAIS une tâche démarrée :
        // celle-ci trouvera la file d'événements vide et rendra la main.
        didChangeDebouncer.cancel();
        drainAndSend();
    }

    /** Capte la salve d'événements en attente et l'envoie. */
    private void drainAndSend() {
        List<TrackedEdit> batch;
        CharSequence docState;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            batch = new ArrayList<>(pending);
            pending.clear();
            docState = lastTracked;
        }
        sendTrackedDidChange(batch, docState);
    }

    /**
     * ★ P-02 : invariant de cohérence de la salve, en O(1) par événement
     * — le remplaçant ÉCONOMIQUE de l'ancienne reconstruction de
     * vérification (trois copies O(n) + comparaison O(n) par envoi).
     *
     * <p>Renvoie la révision finale si la salve est une continuation
     * EXACTE de l'état synchronisé (chaque événement consomme
     * exactement une révision depuis la base — toute mutation non
     * suivie, même à longueur constante, rompt la chaîne — et la
     * longueur reconstruite correspond à l'état capturé), sinon
     * {@code null} → repli plein texte.</p>
     */
    static Integer coherentRevision(List<TrackedEdit> batch,
            int baseRevision, long baseLength, int docStateLength) {
        long length = baseLength;
        int revision = baseRevision;
        for (TrackedEdit e : batch) {
            if (e.revision != revision + 1) return null;
            revision = e.revision;
            length += e.delta;
        }
        if (length != docStateLength) return null;
        return revision;
    }

    /**
     * ★ P-02 : envoie le didChange construit depuis la salve d'événements
     * suivis. Serveur incrémental + salve cohérente → événements de
     * contenu séquentiels PORTeurs de plage (charge utile O(édition), ne
     * touche plus au document entier). Sinon → repli plein texte sur la
     * dernière vue captée (correct pour tout serveur).
     */
    private void sendTrackedDidChange(List<TrackedEdit> batch, CharSequence docState) {
        DidChangeTextDocumentParams params = new DidChangeTextDocumentParams();
        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier();
        id.setUri(editor.getFileUri());
        int v = documentVersion.next();
        id.setVersion(v);
        params.setTextDocument(id);
        List<TextDocumentContentChangeEvent> changes = new ArrayList<>(batch.size());
        CharSequence base = lastSynced;
        Integer finalRevision = (base != null && serverSupportsIncremental())
                ? coherentRevision(batch, lastSyncedRevision, base.length(), docState.length())
                : null;
        if (finalRevision != null) {
            for (TrackedEdit e : batch) {
                TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
                change.setRange(new org.eclipse.lsp4j.Range(
                        new org.eclipse.lsp4j.Position(e.startLine, e.startChar),
                        new org.eclipse.lsp4j.Position(e.endLine, e.endChar)));
                change.setText(e.text);
                changes.add(change);
            }
        } else {
            // Repli plein texte : événement manquant, serveur non
            // incrémental, ou base inconnue — que tout serveur accepte.
            TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
            change.setText(docState.toString());
            changes.add(change);
            // La salve ne reprend pas depuis la base : la révision finale
            // est celle du dernier événement suivi de la salve.
            finalRevision = batch.get(batch.size() - 1).revision;
        }
        params.setContentChanges(changes);
        try {
            editor.currentWrapper().getServer().getTextDocumentService().didChange(params);
            // ★ P-02 : la nouvelle base devient la vue captée (aucune copie).
            lastSynced = docState;
            lastSyncedRevision = finalRevision;
            // D-08 : pas de journal par frappe — le récepteur de journal
            // (panneau de la démo) reste informé, lui, sur demande
            // explicite de l'utilisateur.
            final LspLogSink sink = editor.getProject().getLogSink();
            if (sink != null) {
                sink.log("didChange v" + v + " (" + changes.size()
                        + (finalRevision != null && !changes.isEmpty()
                                ? " événement(s), " : " plein, ")
                        + docState.length() + " ch)");
            }
        } catch (Exception e) {
            jo.codeeditor.util.EditorLog.error("LspEditor", "didChange FAILED: " + e.getMessage(), e);
            final LspLogSink sink = editor.getProject().getLogSink();
            if (sink != null) sink.log("didChange FAILED: " + e.getMessage());
        }
        // Ne programme un didSave débouncé que si le serveur a réellement
        // annoncé un SaveProvider dans ses capabilities (sinon aller-retour
        // gaspillé).
        if (serverSupportsSave()) {
            scheduleDebouncedDidSave();
        }
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
     * Retourne true si le serveur a annoncé la prise en charge de
     * {@code textDocument.save} dans ses capabilities. Certains serveurs
     * ne republient les diagnostics que sur didSave — pour eux,
     * un didSave débouncé est programmé après chaque didChange. D'autres
     * publient directement sur didChange — pour eux, le didSave
     * est un aller-retour gaspillé.
     *
     * <p>En LSP, la capacité save est exposée via
     * {@code ServerCapabilities.textDocumentSync} quand celui-ci vaut une
     * structure {@link org.eclipse.lsp4j.TextDocumentSyncOptions} (plutôt
     * qu'un {@link org.eclipse.lsp4j.TextDocumentSyncKind} nu). Les serveurs
     * qui utilisent le kind nu se désinscrivent implicitement des
     * notifications save.</p>
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
    private void scheduleDebouncedDidSave() {
        didSaveDebouncer.debounce(this::sendDidSave);
    }

    /**
     * Envoie textDocument/didSave au serveur. Certains serveurs relancent
     * sa passe d'inspection et publie des diagnostics frais à la réception
     * de cette notification (comme didOpen).
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
            if (editor.getHost() != null && editor.getHost().session() != null) {
                params.setText(editor.getHost().session().getText().toString());
            }
            wrapper.getServer().getTextDocumentService().didSave(params);
            jo.codeeditor.util.EditorLog.info("LspEditor", "didSave sent (triggers diagnostics re-inspection)");
            final LspLogSink sink = editor.getProject().getLogSink();
            if (sink != null) sink.log("didSave sent (triggers diagnostics re-inspection)");
        } catch (Exception e) {
            jo.codeeditor.util.EditorLog.error("LspEditor", "didSave FAILED: " + e.getMessage(), e);
            final LspLogSink sink = editor.getProject().getLogSink();
            if (sink != null) sink.log("didSave FAILED: " + e.getMessage());
        }
    }

    /**
     * ★ P-02 : envoie didOpen et ANCRE la traçabilité : la base devient la
     * vue du document envoyé (capture cohérente : le texte PART du même
     * instant que l'ancrage), la révision de base celle du document. Les
     * éditions antérieures à l'installation du listener (fenêtre entre
     * didOpen et pose du pont, sur vue attachée) rompent la consécutivité
     * au premier événement → repli plein texte → convergence garantie.
     */
    void sendDidOpen() {
        EditorHost host = editor.getHost();
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (host == null || wrapper == null || wrapper.getServer() == null) return;
        // ★ 3.74.0 : les débounceurs ont pu être fermés par le didClose
        // d'une déconnexion précédente (séquence java → onglet sans
        // serveur → java) — la reconnexion les RENAÎT ici, AVANT toute
        // édition suivie.
        if (didChangeDebouncer.isShutdown()) {
            didChangeDebouncer = new LspDebouncer("didchange-debounce", DID_CHANGE_DEBOUNCE_MS);
        }
        if (didSaveDebouncer.isShutdown()) {
            didSaveDebouncer = new LspDebouncer("didsave-debounce", DID_SAVE_DEBOUNCE_MS);
        }
        jo.codeeditor.document.EditorDocument doc = host.session().getDocument();
        CharSequence rope = doc.charSequence();
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
        TextDocumentItem item = new TextDocumentItem();
        item.setUri(editor.getFileUri());
        item.setLanguageId(editor.extension());
        item.setVersion(documentVersion.get());
        item.setText(rope.toString());
        params.setTextDocument(item);
        try {
            wrapper.getServer().getTextDocumentService().didOpen(params);
            // ★ P-02 : ancre la base de traçabilité sur l'état envoyé.
            lastSynced = rope;
            lastSyncedRevision = doc.getRevision();
            synchronized (pending) {
                pending.clear();
            }
            jo.codeeditor.util.EditorLog.info("LspEditor", "didOpen sent: " + editor.getFileUri()
                + " (" + item.getText().length() + " chars, lang=" + editor.extension() + ")");
        } catch (Exception e) {
            jo.codeeditor.util.EditorLog.error("LspEditor", "didOpen FAILED: " + e.getMessage(), e);
        }
    }

    /**
     * Envoie didClose et annule le thread de debounce didChange en cours
     * (pour éviter qu'il n'écrive dans un pipe fermé → « Write end dead »).
     */
    void sendDidClose() {
        // Arrête les débounceurs : plus AUCUN thread « lsp-* » vivant
        // après la déconnexion (test de non-fuite), plus d'écriture dans
        // un pipe fermé.
        didChangeDebouncer.shutdown();
        didSaveDebouncer.shutdown();
        synchronized (pending) {
            pending.clear();
        }
        lastSynced = null;
        lastSyncedRevision = -1;
        try {
            LanguageServerWrapper wrapper = editor.currentWrapper();
            if (wrapper != null && wrapper.getServer() != null) {
                org.eclipse.lsp4j.DidCloseTextDocumentParams params =
                    new org.eclipse.lsp4j.DidCloseTextDocumentParams();
                params.setTextDocument(new TextDocumentIdentifier(editor.getFileUri()));
                wrapper.getServer().getTextDocumentService().didClose(params);
            }
        } catch (Exception ignored) {
            // didClose best-effort à l'extinction : le serveur peut être
            // déjà parti — la connexion tombera avec le wrapper.
        }
    }

    /**
     * Resynchronisation PLEIN TEXTE forcée avec le texte fourni (didChange
     * sans plage) — ancre l'état serveur dessus. Le suivi automatique des
     * éditions (P-02) rend ce pousser de texte complet inutile en usage
     * normal ; le point d'entrée hérité qui l'appelait a été retiré
     * (4.6.0, politique de dépréciation).
     */
    void resyncFull(CharSequence text) {
        if (!editor.isConnected()) return;
        LanguageServerWrapper wrapper = editor.currentWrapper();
        if (wrapper == null || wrapper.getServer() == null) return;
        DidChangeTextDocumentParams params = new DidChangeTextDocumentParams();
        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier();
        id.setUri(editor.getFileUri());
        int v = documentVersion.next();
        id.setVersion(v);
        params.setTextDocument(id);
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent();
        change.setText(text.toString());
        params.setContentChanges(Collections.singletonList(change));
        try {
            wrapper.getServer().getTextDocumentService().didChange(params);
            lastSynced = text;
            // Ancrage de révision au mieux : si le texte fourni décrit le
            // document courant (même longueur), la traçabilité reprend ;
            // sinon la consécutivité rompra au prochain événement suivi →
            // repli plein texte (convergence garantie).
            lastSyncedRevision = revisionOfCurrentDocIfLengthMatches(text.length());
            final LspLogSink sink = editor.getProject().getLogSink();
            if (sink != null) sink.log("didChange v" + v + " (resync plein, " + text.length() + " ch)");
        } catch (Exception e) {
            jo.codeeditor.util.EditorLog.error("LspEditor", "didChange FAILED: " + e.getMessage(), e);
        }
    }

    /** Révision du document courant si sa longueur correspond, sinon sentinelle. */
    private int revisionOfCurrentDocIfLengthMatches(int length) {
        if (editor.getHost() != null && editor.getHost().session() != null) {
            jo.codeeditor.document.EditorDocument doc =
                    editor.getHost().session().getDocument();
            if (doc.length() == length) return doc.getRevision();
        }
        return Integer.MIN_VALUE;
    }

    /**
     * La version courante du document (incrémentée à chaque didChange).
     */
    int getDocumentVersion() {
        return documentVersion.get();
    }
}
