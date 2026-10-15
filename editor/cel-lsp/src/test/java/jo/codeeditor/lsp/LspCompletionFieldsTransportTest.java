package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;
import jo.codeeditor.lang.model.CompletionItem;
import jo.codeeditor.lang.provider.CompletionPublisher;

import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextEdit;
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
 * L1/D-01 — transport bout-en-bout des champs étendus de complétion :
 * le serveur factice renvoie {@code insertTextFormat}, un
 * {@code textEdit}, {@code filterText} et {@code sortText} ; le
 * publisher doit recevoir les valeurs correspondantes du modèle SPI.
 *
 * <p>Rouge avant : le publisher ne recevait que label/detail/
 * insertText/kind — le format snippet était codé {@code false} en dur
 * et les trois autres champs étaient perdus.</p>
 */
class LspCompletionFieldsTransportTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "int foo;\nint bar;\n";

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
    void formatSnippet_etChampsTriFiltrage_transportésJusquAuPublisher() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("sout");
        lsp.setInsertText("System.out.println(${1:msg});$0");
        lsp.setInsertTextFormat(InsertTextFormat.Snippet);
        lsp.setFilterText("sout");
        lsp.setSortText("01");
        serverItems.add(lsp);

        provider.complete(DOC, 7, (String) null, publisher);

        assertEquals(1, received.size(), "un item reçu");
        CompletionItem out = received.get(0);
        assertTrue(out.isSnippet, "insertTextFormat Snippet transporté");
        assertEquals("System.out.println(${1:msg});$0", out.insertText);
        assertEquals("sout", out.filterText);
        assertEquals("01", out.sortText);
    }

    @Test
    void textEdit_transporté_newTextPrioritaire() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("foobar");
        lsp.setInsertText("IGNORE");
        // Position sans vue liée : conversion → offset 0 partout ; la
        // plage est alors [0, 0) — transportée telle quelle.
        lsp.setTextEdit(Either.forLeft(new TextEdit(
                new Range(new Position(0, 0), new Position(0, 0)), "foobar(")));
        serverItems.add(lsp);

        provider.complete(DOC, 7, (String) null, publisher);

        assertEquals(1, received.size());
        CompletionItem out = received.get(0);
        assertEquals("foobar(", out.insertText, "newText de l'édition prioritaire");
        assertEquals(0, out.replaceStart, "plage transportée (conversion sans vue → 0)");
        assertEquals(0, out.replaceEnd);
    }

    @Test
    void itemsSansChampsÉtendus_retourAuComportementHistorique() {
        org.eclipse.lsp4j.CompletionItem lsp = new org.eclipse.lsp4j.CompletionItem("bar");
        serverItems.add(lsp);

        provider.complete(DOC, 17, (String) null, publisher);

        assertEquals(1, received.size());
        CompletionItem out = received.get(0);
        assertFalse(out.isSnippet, "format absent → pas un snippet");
        assertNull(out.filterText);
        assertNull(out.sortText);
        assertEquals(-1, out.replaceStart);
        assertEquals(-1, out.replaceEnd);
        assertEquals("bar", out.insertText);
    }
}
