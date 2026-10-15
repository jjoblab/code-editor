package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Le pont LSP se pose sur une vue NON ATTACHÉE.
 *
 * <p>Rouge avant : {@code connect()} confiait la pose du langage et du
 * pont d'éditions à {@code View.post()} et interprétait son retour
 * comme « vue attachée ». Or {@code View.post()} ne retourne JAMAIS
 * false pour une vue détachée — il met l'action EN FILE D'ATTENTE
 * jusqu'à l'attachement. Une vue jamais attachée (usage headless,
 * tests, widget non encore mis en layout) ne posait NI le langage NI
 * le forwarder d'éditions : le LSP restait silencieusement mort alors
 * que {@code connect()} avait annoncé SUCCESS.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LspDetachedViewBridgeTest {

    private static final String URI = "file:///proj/src/A.java";

    private ExecutorService executor;
    private LspProject project;
    private LspEditor editor;

    @Before
    public void setUp() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("bridge").toFile();
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(MuetServer::new);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "muet") {
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
        editor.disconnect();
        project.shutdown();
        executor.shutdownNow();
    }

    @Test
    public void vueDétachée_lePontSePoseÀLaConnexion() throws Exception {
        // Vue construite mais JAMAIS attachée à une fenêtre.
        jo.codeeditor.view.EditorView view =
                new jo.codeeditor.view.EditorView(RuntimeEnvironment.getApplication());
        jo.codeeditor.session.EditorSession session =
                new jo.codeeditor.session.EditorSession(
                        jo.codeeditor.document.EditorDocument.of("class A {}\n"));
        view.setSession(session);
        editor.setHost(new jo.codeeditor.view.LspEditorViewHost(view));
        assertFalse("précondition : la vue n'est pas attachée",
                view.isAttachedToWindow());

        editor.connect().get(10, java.util.concurrent.TimeUnit.SECONDS);

        // Le pont doit être posé dès la connexion, sans attendre un
        // attachement qui n'arrivera peut-être jamais.
        assertTrue("le langage SPI doit être posé sur une vue détachée "
                + "(languageApplied reste false tant que le pont n'est pas posé)",
                editor.isLanguageApplied());
    }

    /** Serveur factice minimal — seule la négociation compte ici. */
    static final class MuetServer implements LanguageServer {
        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult(new ServerCapabilities()));
        }
        @Override public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void exit() {}
        @Override public TextDocumentService getTextDocumentService() {
            return new TextDocumentService() {
                @Override public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {}
                @Override public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {}
                @Override public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {}
                @Override public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {}
            };
        }
        @Override public WorkspaceService getWorkspaceService() {
            return new WorkspaceService() {
                @Override public void didChangeConfiguration(
                        org.eclipse.lsp4j.DidChangeConfigurationParams params) {}
                @Override public void didChangeWatchedFiles(
                        org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}
            };
        }
    }
}
