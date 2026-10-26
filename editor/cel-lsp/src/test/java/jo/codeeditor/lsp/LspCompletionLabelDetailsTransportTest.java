package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;
import jo.codeeditor.lang.model.CompletionItem;
import jo.codeeditor.lang.provider.CompletionPublisher;

import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.MarkupContent;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transport bout-en-bout des champs riches de complétion (4.3.0) :
 * origine du symbole ({@code container}, depuis
 * {@code labelDetails.description} avec repli sur la partie qualifiée
 * du {@code detail}) et documentation (texte brut ou Markdown), plus
 * l'enrichissement d'un {@code detail} vide par {@code labelDetails.detail}.
 *
 * <p>Rouge avant : le pont ne lisait ni {@code labelDetails} ni
 * {@code documentation} — l'origine du symbole et sa documentation
 * étaient perdues à la frontière, le popup n'avait aucun moyen de les
 * afficher.</p>
 *
 * <p>Banc identique à {@link LspCompletionFieldsTransportTest} : faux
 * serveur in-process, publisher collecteur.</p>
 */
class LspCompletionLabelDetailsTransportTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "import java.util.List;\n";

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private LspCompletionProvider provider;

    /** Publisher collecteur — inspecte les items convertis. */
    private final List<CompletionItem> received = new ArrayList<>();
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
    };

    /** Items que le faux serveur servira à la prochaine requête. */
    private final List<org.eclipse.lsp4j.CompletionItem> serverItems = new ArrayList<>();

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
                        return CompletableFuture.completedFuture(
                                Either.forLeft(new ArrayList<>(serverItems)));
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
        serverItems.clear();
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    @Test
    void labelDetails_descriptionEtDetail_documentationMarkup() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("of");
        CompletionItemLabelDetails labelDetails = new CompletionItemLabelDetails();
        labelDetails.setDescription("java.util.List");
        labelDetails.setDetail("(index: Int): E");
        lsp.setLabelDetails(labelDetails);
        lsp.setDocumentation(Either.forRight(
                new MarkupContent("markdown", "Retourne l'élément à l'index donné.")));
        serverItems.add(lsp);

        provider.complete(DOC, 21, (String) null, publisher);

        assertEquals(1, received.size());
        CompletionItem out = received.get(0);
        assertEquals("java.util.List", out.container,
                "l'origine vient de labelDetails.description");
        assertEquals("(index: Int): E", out.detail,
                "labelDetails.detail enrichit le detail");
        assertEquals("Retourne l'élément à l'index donné.", out.documentation,
                "la documentation Markdown est transportée telle quelle");
    }

    @Test
    void detailQualifie_type_conteneurDuPaquet() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("ArrayList");
        lsp.setDetail("java.util.ArrayList");
        serverItems.add(lsp);

        provider.complete(DOC, 21, (String) null, publisher);

        assertEquals("java.util", received.get(0).container,
                "un type « paquet.Type » a pour origine son paquet");
    }

    @Test
    void detailQualifie_membre_classeDeclarante() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("bar");
        lsp.setDetail("com.example.Foo.bar");
        serverItems.add(lsp);

        provider.complete(DOC, 21, (String) null, publisher);

        assertEquals("com.example.Foo", received.get(0).container,
                "un membre « paquet.Type.membre » a pour origine sa classe déclarante");
    }

    @Test
    void detailGenerique_laPartieQualifieeSeule() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("List");
        lsp.setDetail("java.util.List<java.lang.String>");
        serverItems.add(lsp);

        provider.complete(DOC, 21, (String) null, publisher);

        assertEquals("java.util", received.get(0).container,
                "les arguments génériques sont ignorés pour l'origine");
    }

    @Test
    void detailNonQualifie_pasDeConteneur() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("length");
        lsp.setDetail("(x: Int): Unit");
        serverItems.add(lsp);

        provider.complete(DOC, 21, (String) null, publisher);

        assertNull(received.get(0).container,
                "une signature n'est pas une origine");
    }

    @Test
    void documentationTexteBrut() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("foo");
        lsp.setDocumentation(Either.forLeft("Ajoute foo au buffer."));
        serverItems.add(lsp);

        provider.complete(DOC, 21, (String) null, publisher);

        assertEquals("Ajoute foo au buffer.", received.get(0).documentation);
    }

    @Test
    void sansChampsRiches_comportementHistorique() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("bar");
        lsp.setDetail("String");
        serverItems.add(lsp);

        provider.complete(DOC, 21, (String) null, publisher);

        CompletionItem out = received.get(0);
        assertNull(out.container, "detail mono-segment : pas d'origine");
        assertNull(out.documentation);
        assertEquals("String", out.detail);
    }
}
