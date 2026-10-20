package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

import jo.codeeditor.AttenteBornee;

/**
 * Cycle de vie du langage LSP (3.74.0, L1) : la déconnexion doit FERMER
 * toutes les capacités LSP sur l'hôte — l'invariant du prompt :
 * <i>sans langage LSP connecté à la vue, TOUTES les capacités LSP sont
 * fermées (aucun résolveur actif, aucune requête envoyée)</i>.
 *
 * <p>Harnais : serveur factice IN-PROCESS (mêmes moyens que
 * {@link LspDetachedViewBridgeTest}) qui ENREGISTRE les requêtes reçues,
 * hôte factice qui trace les appels, document réel (cel-core).</p>
 *
 * <p>Rouge avant les correctifs 3.74.0 : disconnect() n'appelait pas
 * host.setLanguage(null) (C1), ne retirait pas l'écouteur d'éditions
 * (C1c), et la course connect/disconnect posait quand même le langage
 * (C1b) — les trois scénarios échouaient.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LspDisconnectLifecycleTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String TEXTE = "class A {\n    int x = 1;\n}\n";

    /**
     * Serveur factice : négociation + enregistrement de chaque requête
     * texte reçue (hover, formatting, rangeFormatting, didClose), avec
     * initialize OPTIONNELLEMENT bloqué par un loquet (test de course).
     */
    static final class ServeurFactice implements LanguageServer {
        final List<String> requêtes = Collections.synchronizedList(new ArrayList<>());
        final List<String> didClose = Collections.synchronizedList(new ArrayList<>());
        /** Loquet optionnel : initialize n'aboutit qu'à son ouverture. */
        volatile CountDownLatch blocageInitialize;
        /** Réponse formatting : liste d'éditions, ou null (pas de réponse). */
        volatile List<TextEdit> réponseFormatting;
        volatile boolean erreurFormatting;

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            CountDownLatch b = blocageInitialize;
            if (b != null) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        b.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new InitializeResult(capabilities());
                });
            }
            return CompletableFuture.completedFuture(new InitializeResult(capabilities()));
        }

        private ServerCapabilities capabilities() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setHoverProvider(Boolean.TRUE);
            caps.setDocumentFormattingProvider(Boolean.TRUE);
            caps.setDocumentRangeFormattingProvider(Boolean.TRUE);
            return caps;
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {}

        @Override
        public TextDocumentService getTextDocumentService() {
            return new TextDocumentService() {
                @Override
                public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
                    requêtes.add("didOpen");
                }
                @Override
                public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {
                    requêtes.add("didChange");
                }
                @Override
                public void didClose(DidCloseTextDocumentParams params) {
                    didClose.add(params.getTextDocument().getUri());
                    requêtes.add("didClose");
                }
                @Override
                public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {
                    requêtes.add("didSave");
                }
                @Override
                public CompletableFuture<Hover> hover(org.eclipse.lsp4j.HoverParams params) {
                    requêtes.add("hover");
                    return CompletableFuture.completedFuture(null);
                }
                @Override
                @SuppressWarnings("unchecked")
                public CompletableFuture<List<? extends TextEdit>> formatting(
                        org.eclipse.lsp4j.DocumentFormattingParams params) {
                    requêtes.add("formatting");
                    FormattingOptions o = params.getOptions();
                    if (o != null) {
                        requêtes.add("formatting:tabSize=" + o.getTabSize()
                                + ",insertSpaces=" + o.isInsertSpaces());
                    }
                    if (erreurFormatting) {
                        return CompletableFuture.failedFuture(
                                new RuntimeException("échec fabricé"));
                    }
                    return CompletableFuture.completedFuture((List<? extends TextEdit>) réponseFormatting);
                }
                @Override
                @SuppressWarnings("unchecked")
                public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
                        org.eclipse.lsp4j.DocumentRangeFormattingParams params) {
                    requêtes.add("rangeFormatting");
                    FormattingOptions o = params.getOptions();
                    if (o != null) {
                        requêtes.add("rangeFormatting:tabSize=" + o.getTabSize()
                                + ",insertSpaces=" + o.isInsertSpaces()
                                + ",range=" + params.getRange().getStart().getLine()
                                + ":" + params.getRange().getStart().getCharacter()
                                + "-" + params.getRange().getEnd().getLine()
                                + ":" + params.getRange().getEnd().getCharacter());
                    }
                    return CompletableFuture.completedFuture((List<? extends TextEdit>) réponseFormatting);
                }
            };
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return new WorkspaceService() {
                @Override
                public void didChangeConfiguration(
                        org.eclipse.lsp4j.DidChangeConfigurationParams params) {}
                @Override
                public void didChangeWatchedFiles(
                        org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}
            };
        }
    }

    /**
     * Hôte factice : session réelle + trace des langages posés + file
     * des tâches postées (hôte « attaché ») — les mêmes moyens que
     * {@link LspEditorHostBridgeTest}, étendus au cycle de vie.
     */
    static final class HoteFactice implements jo.codeeditor.lang.host.EditorHost {
        final jo.codeeditor.session.EditorSession session;
        boolean attache = false;
        boolean surThreadUi = false;
        final List<String> langagesPosés = new ArrayList<>();
        final List<Runnable> enAttente = new ArrayList<>();

        HoteFactice(String texte) {
            session = new jo.codeeditor.session.EditorSession(
                    jo.codeeditor.document.EditorDocument.of(texte));
        }

        @Override
        public jo.codeeditor.session.EditorSession session() { return session; }
        @Override
        public jo.codeeditor.document.EditorDocument document() {
            return session.getDocument();
        }
        @Override
        public void setLanguage(jo.codeeditor.lang.Language language) {
            langagesPosés.add(language != null
                    ? language.getClass().getSimpleName() : "null");
        }
        @Override
        public void notifyDiagnosticsChanged() {}
        @Override
        public void notifyTextChanged() {}
        @Override
        public void invalidate() {}
        @Override
        public boolean isAttachedToWindow() { return attache; }
        @Override
        public void post(Runnable task) { enAttente.add(task); }
        @Override
        public void postDelayed(Runnable task, long delayMillis) { enAttente.add(task); }
        // Nouvelle méthode 3.74.0 — SANS @Override pour que ce harnais
        // compile aussi contre le code d'avant correctif (démonstration
        // rouge/vert du cycle de vie).
        public boolean isOnUiThread() { return surThreadUi; }
    }

    private LspProject project;
    private LspEditor editor;
    private ServeurFactice serveur;
    private final List<String> journal = Collections.synchronizedList(new ArrayList<>());

    @Before
    public void setUp() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("lifecycle").toFile();
        project = new LspProject(racine.getAbsolutePath());
        project.setLogSink(journal::add);
        serveur = new ServeurFactice();
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> serveur);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "factice") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        project.addServerDefinition(definition);
        editor = project.createEditor(URI);
    }

    @After
    public void tearDown() {
        try {
            editor.disconnect();
        } catch (RuntimeException ignorée) {
        }
        project.shutdown();
    }

    /** Connexion complète sur un hôte détaché (pose en ligne). */
    private void connecter(HoteFactice host) throws Exception {
        editor.setHost(host);
        editor.connect().get(10, TimeUnit.SECONDS);
    }

    // ── C1 : disconnect() restaure EmptyLanguage ────────────────────

    @Test
    public void déconnexion_restaureEmptyLanguage_surHôteDétaché() throws Exception {
        HoteFactice host = new HoteFactice(TEXTE);
        connecter(host);
        assertTrue("précondition : langage posé à la connexion",
                host.langagesPosés.contains("LspLanguage"));

        editor.disconnect();

        assertTrue("disconnect() doit restaurer EmptyLanguage "
                        + "(host.setLanguage(null)) — dernier posé : "
                        + host.langagesPosés,
                host.langagesPosés.contains("null"));
        assertFalse(editor.isConnected());
        assertNull("language remis à null", editor.getLanguage());
        assertFalse(editor.isLanguageApplied());
    }

    @Test
    public void déconnexion_restaureEmptyLanguage_surHôteAttachéParPost() throws Exception {
        // Hôte attaché, appelant hors thread UI : la restauration part
        // par post() — sémantique FIFO avec les autres tâches postées.
        HoteFactice host = new HoteFactice(TEXTE);
        host.attache = true;
        connecter(host);
        // La pose du langage est capturée par post() : l'exécuter.
        assertEquals(1, host.enAttente.size());
        host.enAttente.remove(0).run();
        assertTrue(editor.isLanguageApplied());

        editor.disconnect();
        assertFalse("la restauration est postée (hôte attaché, hors UI), pas immédiate",
                host.langagesPosés.contains("null"));
        // Vide la file postée (purge des diagnostics PUIS restauration).
        for (Runnable r : new ArrayList<>(host.enAttente)) {
            r.run();
        }
        host.enAttente.clear();
        assertTrue("la tâche postée restaure EmptyLanguage",
                host.langagesPosés.contains("null"));
    }

    @Test
    public void déconnexionDouble_estIdempotente() throws Exception {
        HoteFactice host = new HoteFactice(TEXTE);
        connecter(host);
        editor.disconnect();
        // Le didClose part par le flux in-process : attendre sa réception
        // côté serveur avant de photographier l'état.
        assertTrue(AttenteBornee.jusquA(5000, () -> !serveur.didClose.isEmpty()));
        int aprèsPremière = host.langagesPosés.size();
        int didCloseAprèsPremière = serveur.didClose.size();

        editor.disconnect(); // ne doit rien refaire ni lever

        assertEquals("le second disconnect() ne re-pose rien",
                aprèsPremière, host.langagesPosés.size());
        assertEquals("le second disconnect() ne renvoie pas un second didClose",
                didCloseAprèsPremière, serveur.didClose.size());
        assertFalse(editor.isConnected());
    }

    // ── C1b : course connect/disconnect ────────────────────────────

    @Test
    public void déconnexionPendantLeDémarrage_laContinuationSAbandonne() throws Exception {
        HoteFactice host = new HoteFactice(TEXTE);
        editor.setHost(host);
        CountDownLatch blocage = new CountDownLatch(1);
        serveur.blocageInitialize = blocage;

        CompletableFuture<Void> futur = editor.connect();
        // Le changement d'onglet arrive PENDANT la poignée de main.
        editor.disconnect();
        blocage.countDown();
        futur.get(10, TimeUnit.SECONDS);

        assertFalse("la continuation périmée ne passe pas connected à true",
                editor.isConnected());
        assertFalse("la continuation périmée ne pose pas le langage",
                editor.isLanguageApplied());
        assertTrue("aucun langage posé sur l'hôte", host.langagesPosés.isEmpty());
        assertFalse("didOpen n'est pas parti (abandon avant l'envoi), aucun didClose",
                serveur.requêtes.contains("didOpen"));
    }

    @Test
    public void déconnexionAlorsQueLaPoseEstEnFile_laPoseAnnuleEtDidClose() throws Exception {
        // Hôte attaché : la pose du langage est capturée par post() ;
        // la déconnexion survient AVANT son exécution — didOpen est
        // déjà parti, la pose annulée ne doit rien poser.
        HoteFactice host = new HoteFactice(TEXTE);
        host.attache = true;
        editor.setHost(host);
        editor.connect().get(10, TimeUnit.SECONDS);
        assertTrue(editor.isConnected());
        assertEquals("la pose est en file, pas encore exécutée",
                0, host.langagesPosés.size());

        editor.disconnect();
        // Exécute la pose périmée capturée dans la file.
        assertFalse("la file de l'hôte porte la pose annulée",
                host.enAttente.isEmpty());
        host.enAttente.remove(0).run();

        assertFalse("la pose périmée ne pose PAS le langage",
                editor.isLanguageApplied());
        assertTrue("aucun langage posé au total", host.langagesPosés.isEmpty());
        // didOpen est parti → didClose envoyé (disconnect() l'a fait) —
        // le flux in-process est asynchrone, attente bornée de la
        // réception côté serveur.
        assertTrue("didOpen est parti → didClose envoyé : " + serveur.didClose,
                AttenteBornee.jusquA(5000, () -> serveur.didClose.contains(URI)));
    }

    // ── Séquence java → aucun serveur → java ────────────────────────

    @Test
    public void reconnexionAprèsDéconnexion_reposeLeLangage() throws Exception {
        HoteFactice host = new HoteFactice(TEXTE);
        connecter(host);
        editor.disconnect();
        assertTrue(host.langagesPosés.contains("null"));

        // Retour sur l'onglet .java : une NOUVELLE connexion repose le
        // langage (le scénario java → aucun serveur → java).
        connecter(host);
        assertTrue("la reconnexion repose le LspLanguage",
                host.langagesPosés.contains("LspLanguage"));
        assertTrue(editor.isConnected());
        assertTrue(editor.isLanguageApplied());
    }

    @Test
    public void reconnexion_puisÉdition_leDidChangeRepart() throws Exception {
        // Suite de la séquence java → onglet sans serveur → java : le
        // didClose a FERMÉ les débounceurs (arrêt des threads, test de
        // non-fuite) — la reconnexion doit les faire RENAÎTRE, sinon
        // toute édition suivante se perd (ou lève dans le pipeline de la
        // session hôte : l'exécuteur arrêté refuse la programmation).
        HoteFactice host = new HoteFactice(TEXTE);
        connecter(host);
        editor.disconnect();
        connecter(host);
        assertTrue(editor.isConnected());

        // Une édition de la session hôte (chemin du listener posé par
        // connect) : ne doit ni lever ni se perdre.
        host.session.replaceRange(0, 5, "CLASS");

        assertTrue("l'édition repart en didChange après reconnexion : "
                        + serveur.requêtes,
                AttenteBornee.jusquA(5000, () -> serveur.requêtes.contains("didChange")));
    }

    // ── C1c : l'écouteur d'édition est retiré ──────────────────────

    @Test
    public void déconnexion_retireLÉcouteurDÉditions() throws Exception {
        HoteFactice host = new HoteFactice(TEXTE);
        connecter(host);
        int avant = compterÉcouteurs(host.session);
        assertTrue("précondition : l'écouteur LSP est posé", avant >= 1);

        editor.disconnect();

        int après = compterÉcouteurs(host.session);
        assertEquals("l'écouteur d'éditions posé par connect() est retiré "
                        + "par disconnect() (C1c)",
                avant - 1, après);
    }

    /** Compte les écouteurs d'éditions de la session (réflexion). */
    private static int compterÉcouteurs(jo.codeeditor.session.EditorSession session)
            throws Exception {
        java.lang.reflect.Field f = jo.codeeditor.session.EditorSession.class
                .getDeclaredField("onTextEditListeners");
        f.setAccessible(true);
        return ((List<?>) f.get(session)).size();
    }

    // ── Défense en profondeur : providers muets une fois déconnecté ──

    
    @Test
    public void diagnosticsTardifsIgnorésAprèsDéconnexion() throws Exception {
        HoteFactice host = new HoteFactice(TEXTE);
        connecter(host);
        editor.disconnect();

        // Un serveur qui n'a pas encore traité le didClose pousse des
        // diagnostics : ils décrivent le fichier fermé, à ignorer.
        org.eclipse.lsp4j.PublishDiagnosticsParams params =
                new org.eclipse.lsp4j.PublishDiagnosticsParams();
        params.setUri(URI);
        params.setDiagnostics(Collections.singletonList(
                new org.eclipse.lsp4j.Diagnostic(
                        new org.eclipse.lsp4j.Range(
                                new org.eclipse.lsp4j.Position(0, 0),
                                new org.eclipse.lsp4j.Position(0, 5)),
                        "obsolète", org.eclipse.lsp4j.DiagnosticSeverity.Error, "test")));
        editor.publishDiagnostics(params);

        assertTrue("les diagnostics tardifs ne marquent pas la session",
                host.session.getDiagnostics().isEmpty());
        assertTrue("le cache est vidé", editor.getCachedDiagnostics().isEmpty());
    }

    // ── Accès de confort (TextDocumentIdentifier) ──────────────────

    @Test
    public void identifiantDocument_stable() {
        TextDocumentIdentifier id = editor.getTextDocumentIdentifier();
        assertEquals(URI, id.getUri());
    }
}
