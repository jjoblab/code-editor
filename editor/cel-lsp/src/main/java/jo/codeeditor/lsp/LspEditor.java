package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.Diagnostic;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.host.EditorHost;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.PublishDiagnosticsParams;

import java.util.List;

/**
 * Pont entre un fichier unique (document) et son serveur LSP. Créé par
 * {@link LspProject#createEditor(String)}.
 *
 * <p>Responsabilités :
 * <ul>
 *   <li>Orchestrer la connexion (envoi du {@code textDocument/didOpen}, création
 *       et mise en cache du {@link LspLanguage} qui implémente le
 *       SPI {@link Language}, pose du language sur l'{@link EditorHost}) et la
 *       déconnexion (didClose)</li>
 *   <li>Convertir entre {@link Position} LSP (ligne/colonne) et offset éditeur
 *       sur le document vivant</li>
 *   <li>Déléguer le reste à des collaborateurs dédiés :
 *       {@link LspDocumentSynchronizer} (didChange/didSave, sync complète ou
 *       incrémentale selon le serveur), {@link LspDiagnosticsManager}
 *       (publishDiagnostics), {@link LspSemanticTokensPuller} et
 *       {@link LspFoldPuller} (tirs clients semanticTokens/foldingRange),
 *       {@link LspWorkspaceEditApplier} (application des
 *       TextEdits/WorkspaceEdit)</li>
 * </ul>
 */
public class LspEditor {

    private final LspProject project;
    private final String fileUri;
    private final String ext;
    private LanguageServerWrapper wrapper;
    private volatile LspLanguage language;
    private EditorHost host;
    private volatile boolean connected = false;

    /**
     * ★ 3.74.0 — vrai quand la session document↔serveur est FERMÉE :
     * un didClose est parti (ou la connexion a échoué) sans didOpen
     * vivant depuis. Les publications de diagnostics tardives (serveur
     * qui n'a pas encore traité le didClose) sont ignorées : elles
     * décrivent le fichier fermé et ne doivent pas marquer le document
     * du nouvel onglet. Faux à la naissance et pendant une connexion
     * vivante — un éditeur jamais connecté reste « ouvert » : les
     * harnais qui câblent un serveur factice directement sur les
     * providers (sans le cycle de vie complet) gardent le comportement
     * historique.
     */
    private volatile boolean closed = false;

    /**
     * ★ 3.74.0 (C1c) — l'écouteur d'éditions posé sur la session hôte
     * par la connexion courante. Conservé pour être RETIRÉ à la
     * déconnexion (il ne doit pas survivre au fichier qu'il suit :
     * sinon il continue d'alimenter le serveur avec le texte du nouvel
     * onglet sous l'URI du fichier fermé).
     */
    private volatile jo.codeeditor.session.EditorSession.OnTextEditListener editListener;

    /**
     * ★ 3.74.0 (C1b) — jeton de génération de la connexion. Incrémenté à
     * CHAQUE tentative de connect() et à CHAQUE disconnect() : une
     * continuation de connect() dont le jeton ne correspond plus au
     * jeton courant s'abandonne (ni langage, ni écouteur, ni
     * {@code connected = true}) — le changement d'onglet pendant la
     * poignée de main asynchrone ne peut plus brancher le langage du
     * fichier fermé sur la vue du nouveau fichier.
     */
    private final java.util.concurrent.atomic.AtomicInteger connectionGeneration =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Le language SPI est-il posé sur l'hôte ? Les tirs
     * foldingRange arrivés AVANT (didOpen publie les diagnostics avant le
     * setLanguage posté) restent en attente : appliquer des folds serveur
     * avant setLanguage serait VAINCU par le detectFolds lexical (et
     * consumerait l'unique application de collapsedByDefault).
     */
    private volatile boolean languageApplied = false;

    /** Collaborateur : tir client du surlignage sémantique (semanticTokens). */
    private final LspSemanticTokensPuller semanticTokensPuller =
            new LspSemanticTokensPuller(this);

    /** Collaborateur : tir client des plis de code (foldingRange). */
    private final LspFoldPuller foldPuller = new LspFoldPuller(this);

    /** Collaborateur : réception/diffusion des diagnostics serveur. */
    private final LspDiagnosticsManager diagnostics = new LspDiagnosticsManager(this);

    /** Collaborateur : synchronisation du document (didOpen/didChange/didSave/didClose). */
    private final LspDocumentSynchronizer synchronizer = new LspDocumentSynchronizer(this);

    /** Collaborateur : application des éditions LSP (WorkspaceEdit/TextEdits). */
    private final LspWorkspaceEditApplier workspaceEdits = new LspWorkspaceEditApplier(this);

    /**
     * Soumet une tâche de fond sur la voie partagée du projet (tirs
     * clients, réécritures disque). Silencieux pendant l'extinction du
     * projet : la tâche est abandonnée — les tirs sont de toute façon
     * gardés par {@code isConnected()}.
     */
    void submitBackground(Runnable task) {
        try {
            project.taskExecutor().execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // Extinction en cours — abandon sans bruit.
        }
    }

    LspEditor(LspProject project, String fileUri) {
        this.project = project;
        this.fileUri = fileUri;
        // Extrait l'extension de l'URI.
        int dotIdx = fileUri.lastIndexOf('.');
        this.ext = dotIdx >= 0 ? fileUri.substring(dotIdx + 1) : "";
    }

    /**
     * Lie cet éditeur à un hôte d'édition (contrat {@link EditorHost},
     * plan 03 — inversion de dépendance : le client LSP ne connaît plus
     * la vue Android). Appeler avant {@link #connect()}. Implémentation
     * de référence : {@code LspEditorViewHost} (module {@code cel-ui})
     * autour d'une {@code EditorView}.
     */
    public void setHost(EditorHost host) {
        this.host = host;
    }

    /**
     * Se connecte au serveur LSP, envoie didOpen et crée le
     * {@link LspLanguage}. Retourne un futur complété à la connexion.
     *
     * <p>★ 3.74.0 (C1b) : la continuation vérifie son jeton de
     * génération avant CHAQUE effet de bord (didOpen, pose du langage,
     * écouteur, {@code connected = true}) — un disconnect() survenu
     * pendant la poignée de main asynchrone invalide le jeton et la
     * continuation s'abandonne proprement (didClose si didOpen est
     * déjà parti).</p>
     */
    public synchronized java.util.concurrent.CompletableFuture<Void> connect() {
        if (connected) return java.util.concurrent.CompletableFuture.completedFuture(null);
        wrapper = project.getWrapper(ext);
        // Jeton de CETTE tentative : toute déconnexion (ou reconnexion)
        // ultérieure l'incrémentera et périmera cette continuation.
        final int generation = connectionGeneration.incrementAndGet();
        // La session document↔serveur s'ouvre : les publications de
        // diagnostics redeviennent acceptables (cf. closed).
        closed = false;
        final LspLogSink logSink = project.getLogSink();
        log(logSink, "connect: starting wrapper...");
        return wrapper.start().thenRun(() -> {
            if (generation != connectionGeneration.get()) {
                // Déconnecté pendant le démarrage : la connexion a été
                // annulée — didOpen n'est pas parti, rien à défaire.
                log(logSink, "connect: abandonnée (déconnexion pendant le démarrage)");
                return;
            }
            // Câble le pont client→éditeur AVANT sendDidOpen. Le gestionnaire
            // didOpen du serveur appelle publishDiagnostics de façon
            // synchrone — si setLspEditor n'a pas encore été exécuté quand
            // cette notification arrive (via le LocalSocket rapide), les
            // diagnostics sont silencieusement perdus (lspEditor == null dans
            // DefaultLanguageClient.publishDiagnostics). Cause racine du
            // « aucun diagnostic jamais publié » avec le LSP Lua.
            if (wrapper.getClient() != null) {
                wrapper.getClient().setLspEditor(LspEditor.this);
                log(logSink, "connect: client→editor bridge wired (before didOpen)");
            }
            log(logSink, "connect: wrapper started, sending didOpen...");
            // Envoie didOpen AVANT setLanguage pour que le serveur connaisse
            // le document avant toute requête completion/hover.
            synchronizer.sendDidOpen();
            log(logSink, "connect: didOpen sent");
            language = new LspLanguage(LspEditor.this, wrapper);
            if (host != null) {
                // Exécute setLanguage sur le thread UI — l'hôte expose une
                // vue : modifier ses resolvers depuis un thread de fond
                // concurrence les lectures du thread UI.
                final LspLanguage lang = language;
                final LspEditor self = LspEditor.this;
                final EditorHost h = host;
                Runnable applyLanguage = () -> {
                    if (generation != connectionGeneration.get()) {
                        // Déconnecté pendant que la pose était en file :
                        // didOpen est déjà parti → didClose pour refermer
                        // le document côté serveur, SAUF si disconnect()
                        // l'a déjà fait (langage alors remis à null — il
                        // ne restait rien à refermer) ou si une nouvelle
                        // connexion a repris la main (closed repassé à
                        // false : son didOpen doit vivre). NI le langage
                        // NI l'écouteur ne sont posés.
                        if (closed && language != null) {
                            synchronizer.sendDidClose();
                        }
                        log(logSink, "connect: pose abandonnée (déconnexion) — didClose envoyé");
                        return;
                    }
                    h.setLanguage(lang);
                    // Utilise addOnTextEditListener (et non set) pour ne pas
                    // remplacer l'écouteur existant de l'application.
                    // ★ P-02 : la variante 4-arg porte le texte RETIRÉ —
                    // le synchroniseur calcule les positions LSP au moment
                    // de l'édition (O(log n)) et capture la rope (O(1)) :
                    // plus AUCUNE matérialisation du document par frappe
                    // (l'ancien pont appelait getText() à chaque édition).
                    // ★ 3.74.0 (C1c) : l'écouteur est conservé pour être
                    // retiré à la déconnexion.
                    if (h.session() != null) {
                        jo.codeeditor.session.EditorSession.OnTextEditListener listener =
                                new jo.codeeditor.session.EditorSession.OnTextEditListener() {
                            @Override
                            public void onTextEdit(int start, int end, String inserted) {
                                // Variante héritée — le pont surcharge la 4-arg.
                            }
                            @Override
                            public void onTextEdit(int start, int end,
                                    String inserted, String removed) {
                                self.onTextEdit(h.session(),
                                        start, end, inserted, removed);
                            }
                        };
                        h.session().addOnTextEditListener(listener);
                        editListener = listener;
                    }
                    // Le SPI est posé — les folds serveur peuvent désormais
                    // s'appliquer sans être écrasés par le detectFolds lexical
                    // de setLanguage. Premier tir dès maintenant (les tirs
                    // arrivés avant sont restés en attente — garde
                    // languageApplied).
                    languageApplied = true;
                    scheduleFoldPull();
                    log(logSink, "connect: setLanguage done, didChange forwarder wired");
                };
                runOnHostThread(h, applyLanguage,
                        "connect: setLanguage done inline (host not attached)");
            }
            if (generation == connectionGeneration.get()) {
                connected = true;
                log(logSink, "connect: SUCCESS — server ready, version=" + synchronizer.getDocumentVersion());
            } else {
                log(logSink, "connect: complétée après déconnexion — connexion ignorée");
            }
        }).exceptionally(throwable -> {
            closed = true;
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

    /**
     * ★ 3.74.0 — règle de thread commune à connect()/disconnect() pour
     * les mutations de l'hôte (poser/retirer le langage) : hôte non
     * attaché → exécution en ligne ; hôte attaché → sur le thread UI —
     * immédiatement si l'appelant y est déjà ({@link EditorHost#isOnUiThread()},
     * l'ordre vis-à-vis des poses suivantes de l'application est alors
     * garanti), sinon via {@link EditorHost#post(Runnable)} (sémantique
     * FIFO avec les autres tâches postées).
     */
    private void runOnHostThread(EditorHost h, Runnable task, String journalInline) {
        if (!h.isAttachedToWindow()) {
            // Hôte NON attaché — exécution en ligne. Un post() sur un
            // hôte détaché n'échoue PAS : il met l'action en file
            // JUSQU'À L'ATTACHEMENT. Un hôte jamais attaché (usage
            // headless, tests) n'aurait donc NI le langage NI le pont
            // d'éditions suivies — le LSP resterait silencieusement
            // mort alors que connect() avait annoncé SUCCESS. Un hôte
            // détaché n'a pas de thread de rendu qui le lise : la pose
            // en ligne est sûre.
            task.run();
            if (journalInline != null) log(project.getLogSink(), journalInline);
        } else if (h.isOnUiThread()) {
            // Hôte attaché et appelant DÉJÀ sur le thread UI : exécuter
            // maintenant (même effet que post, sans le délai). Retarder
            // la pose/retrait derrière la file ferait passer la
            // restauration APRÈS les poses du nouvel onglet faites par
            // l'application — elle les effacerait.
            task.run();
        } else {
            h.post(task);
        }
    }

    /** Journalise vers le récepteur de journal du projet (panneau de la démo) + logcat. */
    private void log(LspLogSink sink, String message) {
        jo.codeeditor.util.EditorLog.info("LspEditor", message);
        if (sink != null) sink.log(message);
    }

    /** Le wrapper serveur courant, ou null avant connect() (accès package). */
    LanguageServerWrapper currentWrapper() { return wrapper; }

    /** L'extension du fichier (languageId LSP), accès package. */
    String extension() { return ext; }

    /** Le language SPI est-il posé sur l'hôte (accès package). */
    boolean isLanguageApplied() { return languageApplied; }

    /** Programme un tir semanticTokens throttlé (délégué au collaborateur). */
    void scheduleSemanticTokensPull() {
        semanticTokensPuller.scheduleSemanticTokensPull();
    }

    /** Programme un tir foldingRange throttlé (délégué au collaborateur). */
    void scheduleFoldPull() {
        foldPuller.scheduleFoldPull();
    }

    /**
     * ★ 3.74.0 (C1) — se déconnecte du serveur de façon idempotente et
     * referme TOUTES les capacités LSP sur l'hôte :
     * <ol>
     *   <li>invalide le jeton de génération (les continuations de
     *       {@link #connect()} en vol s'abandonnent — course C1b) ;</li>
     *   <li>envoie {@code didClose} au serveur ;</li>
     *   <li>retire l'écouteur d'éditions de la session hôte (C1c) ;</li>
     *   <li><b>restaure {@code EmptyLanguage}</b> sur l'hôte
     *       ({@code host.setLanguage(null)}) avec la même règle de thread
     *       que {@code connect()} — sans langage LSP connecté à la vue,
     *       TOUTES les capacités LSP sont fermées (aucun résolveur actif,
     *       aucune requête envoyée) ;</li>
     *   <li>vide le cache de diagnostics et les diagnostics de la session
     *       hôte (aucune marque du fichier fermé ne survit au changement
     *       d'onglet), remet {@code language} à {@code null}.</li>
     * </ol>
     * Un second appel ne fait rien (idempotent).
     */
    public synchronized void disconnect() {
        // Invalide TOUTE connexion en cours d'établissement (course C1b) :
        // une continuation de connect() qui s'exécuterait après ce point
        // voit un jeton périmé et s'abandonne.
        connectionGeneration.incrementAndGet();
        if (!connected && language == null) {
            // Jamais connecté, ou déjà déconnecté (idempotent) : seule
            // l'invalidation du jeton était nécessaire.
            closed = true;
            return;
        }
        connected = false;
        closed = true;
        synchronizer.sendDidClose();
        // Retire l'écouteur d'éditions (C1c) : sans cela, il continue
        // d'envoyer didChange avec le texte du NOUVEL onglet sous l'URI
        // du fichier fermé.
        EditorHost h = host;
        jo.codeeditor.session.EditorSession.OnTextEditListener listener = editListener;
        editListener = null;
        if (h != null && listener != null && h.session() != null) {
            h.session().removeOnTextEditListener(listener);
        }
        // Purge les diagnostics : le cache ne doit plus rien exposer et
        // la session ne doit plus porter les marques du fichier fermé.
        diagnostics.clear();
        if (h != null && h.session() != null) {
            Runnable purge = () -> {
                if (closed && h.session() != null) {
                    h.session().setDiagnostics(new java.util.ArrayList<>());
                    h.notifyDiagnosticsChanged();
                }
            };
            runOnHostThread(h, purge, null);
        }
        // Restaure EmptyLanguage — UNIQUEMENT si un langage avait été posé
        // (sinon la restauration effacerait un langage posé par
        // l'application entre-temps). Même règle de thread que connect().
        if (h != null && languageApplied) {
            Runnable restore = () -> h.setLanguage(null);
            runOnHostThread(h, restore, null);
        }
        language = null;
        languageApplied = false;
        log(project.getLogSink(), "disconnect: didClose envoyé, EmptyLanguage restauré, écouteur retiré");
    }

    /**
     * ★ P-02 : relaie une édition suivie de la session au synchroniseur —
     * positions LSP et vue de rope capturées au moment de l'édition,
     * aucune copie du document. Accès package (le pont interne).
     */
    void onTextEdit(jo.codeeditor.session.EditorSession session,
            int start, int end, String inserted, String removed) {
        synchronizer.onTextEdit(session, start, end, inserted, removed);
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
     * ({@code DID_CHANGE_DEBOUNCE_MS} = 300 ms) est PLUS LONG que le
     * debounce completion (120 ms dans le popup de complétion). Sans
     * ce flush, une frappe déclenche
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
     * {@code sendDidChange(String)} SYNCHRONEMENT sur le thread
     * appelant (typiquement la voie complétion de l'hôte). La notification
     * didChange est SÉRIALISÉE et mise dans la file LSP4J AVANT le
     * retour de cette méthode. La requête completion qui suit est mise
     * dans la même file APRÈS le didChange — le serveur traite donc
     * didChange PUIS completion, et voit le texte à jour.</p>
     *
     * <p>Idempotent : si le texte en attente est null (rien en attente,
     * ou déjà flushed), ne fait rien.</p>
     */
    public void flushPendingChange() {
        synchronizer.flushPendingChange();
    }

    /**
     * Envoie textDocument/didSave au serveur. Certains serveurs relancent
     * leur passe d'inspection et publient des diagnostics frais à la réception
     * de cette notification (comme didOpen). C'est le SEUL moyen d'obtenir
     * des diagnostics vivants sur les changements de texte — didChange seul
     * ne déclenche pas la ré-inspection.
     */
    public void sendDidSave() {
        synchronizer.sendDidSave();
    }

    /**
     * Envoie workspace/didChangeConfiguration au serveur.
     * <p>Méthode générique, indépendante du serveur — l'objet
     * {@code settings} est spécifique au serveur (ex. des clés plates en
     * points comme {@code "inspections.undeclaredVariable"} ou des
     * clés préfixées {@code "java.*"}). L'appelant (couche application) est
     * responsable de construire l'objet de réglages correct pour le serveur
     * connecté.
     * <p>Cette méthode ne sait PAS quel serveur est connecté — elle se
     * contente de transmettre les réglages via le service workspace LSP.
     *
     * @param settings l'objet de réglages spécifique au serveur (ex. un
     *                 {@link com.google.gson.JsonObject} avec les clés
     *                 appropriées pour le serveur)
     */
    public void sendDidChangeConfiguration(Object settings) {
        if (wrapper == null || wrapper.getServer() == null) return;
        // Stocke les réglages sur le client pour que le serveur puisse les
        // tirer via workspace/configuration. De nombreux serveurs IGNORENT
        // le push didChangeConfiguration et tirent au contraire les réglages
        // à la demande — sans cela, ces serveurs ne voient jamais leur
        // configuration d'inspection et publient zéro diagnostic.
        if (wrapper.getClient() != null) {
            wrapper.getClient().setSettings(settings);
        }
        try {
            org.eclipse.lsp4j.DidChangeConfigurationParams params =
                new org.eclipse.lsp4j.DidChangeConfigurationParams(settings);
            wrapper.getServer().getWorkspaceService().didChangeConfiguration(params);
            jo.codeeditor.util.EditorLog.info("LspEditor", "didChangeConfiguration sent (settings cached for pull)");
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChangeConfiguration sent (settings cached for pull)");
        } catch (Exception e) {
            jo.codeeditor.util.EditorLog.error("LspEditor", "didChangeConfiguration FAILED: " + e.getMessage(), e);
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChangeConfiguration FAILED: " + e.getMessage());
        }
    }

    /**
     * Envoie workspace/didChangeWorkspaceFolders au serveur.
     * <p>Méthode générique — l'appelant construit les
     * {@link org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams} avec les
     * dossiers d'espace de travail appropriés.
     */
    public void sendDidChangeWorkspaceFolders(
            org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams params) {
        if (wrapper == null || wrapper.getServer() == null) return;
        try {
            wrapper.getServer().getWorkspaceService().didChangeWorkspaceFolders(params);
            jo.codeeditor.util.EditorLog.info("LspEditor", "didChangeWorkspaceFolders sent");
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChangeWorkspaceFolders sent");
        } catch (Exception e) {
            jo.codeeditor.util.EditorLog.error("LspEditor", "didChangeWorkspaceFolders FAILED: " + e.getMessage(), e);
            final LspLogSink sink = project.getLogSink();
            if (sink != null) sink.log("didChangeWorkspaceFolders FAILED: " + e.getMessage());
        }
    }

    /**
     * Reçoit les diagnostics du serveur (appelée par {@link DefaultLanguageClient}
     * ou un client personnalisé). Met à jour la liste en cache et invalide
     * la vue.
     */
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        diagnostics.publishDiagnostics(params);
    }

    /** Retourne les diagnostics en cache venant du serveur. */
    public List<Diagnostic> getCachedDiagnostics() {
        return diagnostics.getCachedDiagnostics();
    }

    /**
     * Les diagnostics LSP bruts en cache (identité complète : code,
     * données structurées, source, tags) — base du contexte fidèle des
     * requêtes d'actions de code. Accès package.
     */
    List<org.eclipse.lsp4j.Diagnostic> cachedLspDiagnostics() {
        return diagnostics.getCachedLspDiagnostics();
    }

    /** Retourne l'identifiant de document texte LSP pour ce fichier. */
    public TextDocumentIdentifier getTextDocumentIdentifier() {
        return new TextDocumentIdentifier(fileUri);
    }

    /**
     * Convertit un offset éditeur en {@link Position} LSP (ligne, caractère).
     */
    public Position offsetToPosition(int offset) {
        if (host == null) return new Position(0, 0);
        jo.codeeditor.document.EditorDocument doc = host.document();
        if (doc == null) return new Position(0, 0);
        offset = Math.max(0, Math.min(offset, doc.length()));
        int line = doc.lineForOffset(offset);
        int col = offset - doc.lineStart(line);
        return new Position(line, col);
    }

    /**
     * Convertit une {@link Position} LSP en offset éditeur.
     */
    public int positionToOffset(Position pos) {
        if (host == null) return 0;
        jo.codeeditor.document.EditorDocument doc = host.document();
        if (doc == null) return 0;
        int line = Math.max(0, Math.min(pos.getLine(), doc.lineCount() - 1));
        int lineStart = doc.lineStart(line);
        int lineEnd = doc.lineEnd(line);
        int col = Math.max(0, Math.min(pos.getCharacter(), lineEnd - lineStart));
        return lineStart + col;
    }

    /**
     * Longueur du document vivant — sert au bornage des plages
     * {@code textEdit} de complétion avant transport vers le SPI.
     * Retourne -1 quand l'hôte n'est pas lié (longueur inconnue).
     */
    public int documentLength() {
        if (host == null) return -1;
        jo.codeeditor.document.EditorDocument doc = host.document();
        return doc != null ? doc.length() : -1;
    }

    /** Retourne l'URI du fichier. */
    public String getFileUri() { return fileUri; }

    /**
     * Retourne l'{@link EditorHost} lié, ou null si pas encore lié.
     * Utilisée par le fournisseur d'inlay hints pour convertir
     * ligne/colonne→offset sur le document.
     */
    public EditorHost getHost() { return host; }

    /** Retourne le projet parent (accès au récepteur de journal). */
    public LspProject getProject() { return project; }

    /** Retourne true si connecté au serveur. */
    public boolean isConnected() { return connected; }

    /**
     * ★ 3.74.0 — la session document↔serveur est-elle fermée (didClose
     * parti sans didOpen vivant depuis : après {@link #disconnect()} ou
     * un échec de connexion) ? Accès package : garde des publications de
     * diagnostics tardives et des providers.
     */
    boolean isClosed() { return closed; }

    /**
     * ★ 3.74.0 — garde commun de tous les providers LSP (défense en
     * profondeur, C1) : le serveur prêt à servir une requête, ou
     * {@code null} quand la session est fermée (déconnexion) ou que le
     * serveur n'est pas joignable. Chaque provider remplace son accès
     * direct au wrapper par CETTE méthode : après une déconnexion, il
     * renvoie son vide documenté (null / liste vide) et n'envoie
     * AUCUNE requête — même si un résolveur restait branché par un
     * hôte qui ne respecte pas le cycle de vie. Le wrapper du provider
     * sert de repli quand l'éditeur n'est pas passé par connect()
     * (câblage unitaire direct d'un serveur factice).
     */
    org.eclipse.lsp4j.services.LanguageServer readyServer(LanguageServerWrapper providerWrapper) {
        if (closed) return null;
        // Le wrapper de l'éditeur (connexion courante) prime ; le
        // wrapper du provider sert de repli aux câblages directs qui
        // n'instantient pas l'éditeur par connect() (harnais unitaires).
        LanguageServerWrapper actif = wrapper != null ? wrapper : providerWrapper;
        return actif != null ? actif.getServer() : null;
    }

    /** Retourne le {@link LspLanguage} (null tant que non connecté). */
    public LspLanguage getLanguage() { return language; }

    /**
     * Applique un {@link org.eclipse.lsp4j.WorkspaceEdit} LSP à l'éditeur
     * lié. Appelée quand l'utilisateur accepte une action de code. Parcourt
     * les changements de l'édition (par URI) et les applique à la session
     * de l'éditeur — pour l'URI du fichier courant, applique les TextEdits ;
     * pour les autres URI, réécrit les éditions sur disque en arrière-plan.
     *
     * <p>Doit être appelée sur le thread UI (elle mute la session).</p>
     */
    public void applyWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit) {
        workspaceEdits.applyWorkspaceEdit(edit, null);
    }

    /**
     * Variante avec contrôle de fraîcheur : {@code versionAtRequest} est
     * la version du document CAPTURÉE au moment où la requête est partie
     * (code action, complétion). Si le document a changé depuis, les
     * positions renvoyées par le serveur sont périmées — l'édition est
     * refusée plutôt que d'appliquer des positions fausses.
     */
    public void applyWorkspaceEdit(org.eclipse.lsp4j.WorkspaceEdit edit, Integer versionAtRequest) {
        workspaceEdits.applyWorkspaceEdit(edit, versionAtRequest);
    }

    /**
     * Applique une édition POUSSÉE PAR LE SERVEUR (requête
     * {@code workspace/applyEdit}, cf. DefaultLanguageClient) par la voie
     * commune — confinement au workspace, validation tout-ou-rien,
     * écriture atomique — sur le thread UI, et répond de façon
     * asynchrone : le futur porte le compte rendu (jamais d'exception —
     * le protocole attend une réponse structurée, appliqué ou motif de
     * refus).
     */
    java.util.concurrent.CompletableFuture<WorkspaceDiskEdits.Result> applyServerEdit(
            org.eclipse.lsp4j.WorkspaceEdit edit) {
        java.util.concurrent.CompletableFuture<WorkspaceDiskEdits.Result> future =
                new java.util.concurrent.CompletableFuture<>();
        final EditorHost h = getHost();
        if (h == null) {
            future.complete(new WorkspaceDiskEdits.Result(false,
                    java.util.Collections.singletonList("aucun éditeur lié"), 0, 0));
            return future;
        }
        Runnable task = () -> {
            try {
                future.complete(workspaceEdits.applyWorkspaceEdit(edit, null));
            } catch (Exception t) {
                future.complete(new WorkspaceDiskEdits.Result(false,
                        java.util.Collections.singletonList(
                                "erreur d'application : " + t.getMessage()), 0, 0));
            }
        };
        // Thread UI si l'hôte est attaché ; sinon exécution en ligne (au
        // mieux) : la réponse serveur ne doit JAMAIS rester pendante sur
        // un hôte détaché — la mutation reste servie.
        if (h.isAttachedToWindow()) {
            h.post(task);
        } else {
            task.run();
        }
        return future;
    }

    /** Version courante du document LSP (strictement croissante). */
    int getDocumentVersion() {
        return synchronizer.getDocumentVersion();
    }

    /**
     * Applique des éditions LSP ADDITIONNELLES (ex:
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
        workspaceEdits.applyAdditionalEdits(edits, null);
    }

    /**
     * Variante avec contrôle de fraîcheur : {@code versionAtRequest} est
     * la version capturée au moment de la requête de complétion — si
     * l'utilisateur a tapé depuis, les additionalTextEdits (auto-import)
     * sont refusés au lieu de corrompre le texte.
     */
    public void applyAdditionalEdits(java.util.List<org.eclipse.lsp4j.TextEdit> edits, Integer versionAtRequest) {
        workspaceEdits.applyAdditionalEdits(edits, versionAtRequest);
    }
}
