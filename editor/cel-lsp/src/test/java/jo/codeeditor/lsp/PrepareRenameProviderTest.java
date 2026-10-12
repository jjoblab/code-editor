package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La pré-validation de renommage ({@code textDocument/prepareRename}) :
 * placeholder servi quand le serveur l'annonce ; null (refus) quand le
 * symbole n'est pas renommable ; null SANS requête quand le serveur ne
 * l'annonce pas — l'éditeur garde alors son heuristique locale.
 */
class PrepareRenameProviderTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    String s = foo;\n}\n";

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private FakeActionsCodeServer server;

    @BeforeEach
    void setUp(@TempDir File racine) throws Exception {
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        server = new FakeActionsCodeServer();
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> server);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        wrapper = new LanguageServerWrapper(definition, racine.getAbsolutePath(), executor);
        wrapper.start().join();
        editor = new LspEditor(project, URI);
        wrapper.getClient().setLspEditor(editor);
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    @Test
    void sansAnnonce_aucuneRequêteRetourNull() {
        server.renamePrepareProvider = false;
        // Les capabilities sont négociées à initialize — le faux courant
        // n'annonce PAS prepareProvider : le provider ne doit rien émettre.
        LspRenameProvider provider = new LspRenameProvider(wrapper, editor, null);
        assertNull(provider.prepareRename(DOC, DOC.indexOf("foo")));
        assertEquals(0, server.prepareRenameRequests,
                "sans annonce, aucune requête prepareRename n'est émise");
    }

    @Test
    void avecAnnonce_placeholderServi() {
        rebrancherAvecAnnonce("bar");
        LspRenameProvider provider = new LspRenameProvider(wrapper, editor, null);
        assertEquals("bar", provider.prepareRename(DOC, DOC.indexOf("foo")));
        assertEquals(1, server.prepareRenameRequests);
    }

    @Test
    void avecAnnonce_symboleNonRénombrable_retourNull() {
        rebrancherAvecAnnonce(null);
        LspRenameProvider provider = new LspRenameProvider(wrapper, editor, null);
        assertNull(provider.prepareRename(DOC, DOC.indexOf("foo")));
        assertEquals(1, server.prepareRenameRequests,
                "la requête part — c'est le serveur qui refuse");
    }

    /** Repart d'un wrapper branché sur le MÊME serveur, annonce activée. */
    private void rebrancherAvecAnnonce(String placeholder) {
        wrapper.shutdown();
        server.renamePrepareProvider = true;
        server.prepareRenamePlaceholder = placeholder;
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> server);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake2") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        wrapper = new LanguageServerWrapper(definition,
                project.getWorkspacePath(), executor);
        wrapper.start().join();
        editor = new LspEditor(project, URI);
        wrapper.getClient().setLspEditor(editor);
    }
}
