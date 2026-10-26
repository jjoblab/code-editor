package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;
import jo.codeeditor.lang.model.CompletionItem;
import jo.codeeditor.lang.provider.CompletionPublisher;

import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transport bout-en-bout du drapeau « liste tronquée » : un serveur qui
 * renvoie une {@code CompletionList} plafonnée marquée
 * {@code isIncomplete = true} doit être signalé au publisher via
 * {@link CompletionPublisher#setIncomplete(boolean)} — le client saura
 * que sa base est partielle et qu'une re-requête peut faire réapparaître
 * des candidats écartés par le plafond.
 *
 * <p>Rouge avant : le champ {@code isIncomplete} de la
 * {@code CompletionList} était lu nulle part — seule la liste d'items
 * traversait le pont, le client filtrait localement sur une base
 * tronquée sans le savoir.</p>
 *
 * <p>Banc identique à {@link LspCompletionFieldsTransportTest} : le faux
 * serveur in-process sert la réponse injectée par le test ; le publisher
 * collecteur enregistre le dernier drapeau reçu.</p>
 */
class LspCompletionIncompleteTransportTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "int foo;\nint bar;\n";

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private LspCompletionProvider provider;

    /** Publisher collecteur — enregistre le dernier drapeau reçu. */
    private final List<CompletionItem> received = new ArrayList<>();
    private final AtomicInteger incompleteCalls = new AtomicInteger();
    private volatile boolean lastIncomplete = false;
    private final CompletionPublisher publisher = new CompletionPublisher() {
        @Override
        public void addItem(CompletionItem item) { received.add(item); }

        @Override
        public void addItems(List<CompletionItem> items) { received.addAll(items); }

        @Override
        public void flush() {}

        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() { return false; }

        @Override
        public void setIncomplete(boolean incomplete) {
            incompleteCalls.incrementAndGet();
            lastIncomplete = incomplete;
        }
    };

    /** Réponse que le faux serveur servira (injectée par chaque test). */
    private volatile Either<List<org.eclipse.lsp4j.CompletionItem>, CompletionList> serverResponse;

    @BeforeEach
    void setUp(@TempDir File racine) throws Exception {
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        org.eclipse.lsp4j.services.TextDocumentService tds =
                new org.eclipse.lsp4j.services.TextDocumentService() {
                    @Override
                    public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {}

                    @Override
                    public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {}

                    @Override
                    public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {}

                    @Override
                    public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {}

                    @Override
                    public CompletableFuture<Either<List<org.eclipse.lsp4j.CompletionItem>,
                            org.eclipse.lsp4j.CompletionList>> completion(
                            org.eclipse.lsp4j.CompletionParams params) {
                        return CompletableFuture.completedFuture(serverResponse);
                    }
                };
        org.eclipse.lsp4j.services.LanguageServer fake =
                new org.eclipse.lsp4j.services.LanguageServer() {
                    @Override
                    public CompletableFuture<org.eclipse.lsp4j.InitializeResult> initialize(
                            org.eclipse.lsp4j.InitializeParams params) {
                        org.eclipse.lsp4j.ServerCapabilities caps =
                                new org.eclipse.lsp4j.ServerCapabilities();
                        org.eclipse.lsp4j.CompletionOptions options =
                                new org.eclipse.lsp4j.CompletionOptions();
                        options.setTriggerCharacters(List.of("."));
                        caps.setCompletionProvider(options);
                        return CompletableFuture.completedFuture(
                                new org.eclipse.lsp4j.InitializeResult(caps));
                    }

                    @Override
                    public CompletableFuture<Object> shutdown() {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void exit() {}

                    @Override
                    public org.eclipse.lsp4j.services.TextDocumentService getTextDocumentService() {
                        return tds;
                    }

                    @Override
                    public org.eclipse.lsp4j.services.WorkspaceService getWorkspaceService() {
                        return new org.eclipse.lsp4j.services.WorkspaceService() {
                            @Override
                            public void didChangeConfiguration(
                                    org.eclipse.lsp4j.DidChangeConfigurationParams params) {}

                            @Override
                            public void didChangeWatchedFiles(
                                    org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}
                        };
                    }
                };
        InProcessStreamConnectionProvider connection =
                new InProcessStreamConnectionProvider(() -> fake);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return connection;
            }
        };
        wrapper = new LanguageServerWrapper(definition, racine.getAbsolutePath(), executor);
        wrapper.start().join();
        editor = new LspEditor(project, URI);
        wrapper.getClient().setLspEditor(editor);
        provider = new LspCompletionProvider(wrapper, editor, null);
        received.clear();
        incompleteCalls.set(0);
        lastIncomplete = false;
        serverResponse = null;
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    @Test
    void listeIncomplete_drapeauTransporteAuPublisher() {
        // Première frappe : liste plafonnée (le bon candidat manque),
        // marquée isIncomplete = true.
        org.eclipse.lsp4j.CompletionItem a = new org.eclipse.lsp4j.CompletionItem("bar");
        org.eclipse.lsp4j.CompletionItem b = new org.eclipse.lsp4j.CompletionItem("baz");
        serverResponse = Either.forRight(new CompletionList(true, List.of(a, b)));

        provider.complete(DOC, 7, (String) null, publisher);

        assertEquals(2, received.size(), "les items plafonnés traversent le pont");
        assertTrue(lastIncomplete,
                "le drapeau « liste tronquée » doit être transmis au publisher");
        assertEquals(1, incompleteCalls.intValue(),
                "le drapeau est posé une fois, avant le flush");
    }

    @Test
    void listeComplete_drapeauFauxTransporte() {
        org.eclipse.lsp4j.CompletionItem a = new org.eclipse.lsp4j.CompletionItem("bar");
        serverResponse = Either.forRight(new CompletionList(false, List.of(a)));

        provider.complete(DOC, 7, (String) null, publisher);

        assertEquals(1, received.size());
        assertFalse(lastIncomplete,
                "une liste complète doit être signalée non tronquée");
    }

    @Test
    void listeNue_drapeauFauxTransporte() {
        // Variante Either.left (liste sans enveloppe) : jamais tronquée.
        org.eclipse.lsp4j.CompletionItem a = new org.eclipse.lsp4j.CompletionItem("bar");
        serverResponse = Either.forLeft(List.of(a));

        provider.complete(DOC, 7, (String) null, publisher);

        assertEquals(1, received.size());
        assertFalse(lastIncomplete,
                "une liste nue (sans enveloppe) est réputée complète");
    }

    @Test
    void frappesSuccessives_listeFraicheAvecLeCandidatManquant() {
        // Scénario de troncature complet côté pont : à la première
        // frappe le serveur plafonne sans « banana » ; à la frappe
        // suivante il renvoie une liste fraîche qui le contient — les
        // deux réponses doivent traverser le pont avec leurs items.
        org.eclipse.lsp4j.CompletionItem bar = new org.eclipse.lsp4j.CompletionItem("bar");
        org.eclipse.lsp4j.CompletionItem baz = new org.eclipse.lsp4j.CompletionItem("baz");
        serverResponse = Either.forRight(new CompletionList(true, List.of(bar, baz)));
        provider.complete(DOC, 7, (String) null, publisher);
        assertTrue(lastIncomplete);

        org.eclipse.lsp4j.CompletionItem banana = new org.eclipse.lsp4j.CompletionItem("banana");
        serverResponse = Either.forRight(new CompletionList(true, List.of(bar, baz, banana)));
        received.clear();
        provider.complete(DOC, 8, (String) null, publisher);

        assertEquals(3, received.size(), "la liste fraîche traverse le pont en entier");
        assertEquals("banana", received.get(2).label,
                "le candidat absent du plafond initial est bien servi à la re-requête");
    }
}
