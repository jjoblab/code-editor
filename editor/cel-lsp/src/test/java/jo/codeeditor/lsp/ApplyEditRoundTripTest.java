package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * Round-trip JSON-RPC de la requête serveur→client
 * {@code workspace/applyEdit} : le serveur POUSSE une édition et attend
 * une réponse STRUCTURÉE (appliqué / motif de refus).
 *
 * <p>Avant le correctif, la méthode n'était pas implémentée côté client :
 * l'implémentation par défaut du protocole lève une exception — le
 * serveur recevait une erreur JSON-RPC à chaque édition poussée (les
 * refactorings en deux temps, commande → edit, échouaient en silence).
 * Deux volets :</p>
 * <ul>
 *   <li>sans éditeur lié : refus PROPRE (applied=false + motif),
 *       jamais d'exception ;</li>
 *   <li>avec éditeur lié (Robolectric) : l'édition du fichier courant
 *       est réellement appliquée à la session, réponse applied=true.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class ApplyEditRoundTripTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    String s = foo;\n}\n";

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private FakeActionsCodeServer server;
    private File racine;

    @Before
    public void setUp() throws Exception {
        racine = java.nio.file.Files.createTempDirectory("apply-edit").toFile();
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

    @After
    public void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    /** L'édition poussée : insertion d'un import en tête du fichier. */
    private static WorkspaceEdit insertionImport(String uri) {
        WorkspaceEdit edit = new WorkspaceEdit();
        Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
        changes.put(uri, List.of(new TextEdit(
                new Range(new Position(0, 0), new Position(0, 0)),
                "import java.util.Foo;\n")));
        edit.setChanges(changes);
        return edit;
    }

    @Test
    public void sansÉditeur_refusProprePasDException() throws Exception {
        org.eclipse.lsp4j.ApplyWorkspaceEditResponse réponse =
                server.client.applyEdit(
                        new org.eclipse.lsp4j.ApplyWorkspaceEditParams(
                                insertionImport(URI), "import auto")).get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(réponse);
        assertFalse(réponse.isApplied());
        assertNotNull(réponse.getFailureReason());
        assertTrue(réponse.getFailureReason().contains("éditeur"));
    }

    @Test
    public void avecÉditeur_lÉditionEstAppliquée() throws Exception {
        // Vue réelle non attachée : sans handler, l'application passe en
        // ligne — le futur de la réponse est complété (une requête
        // serveur ne reste jamais pendante).
        android.content.Context app = RuntimeEnvironment.getApplication();
        jo.codeeditor.view.EditorView view = new jo.codeeditor.view.EditorView(app);
        jo.codeeditor.session.EditorSession session =
                new jo.codeeditor.session.EditorSession(
                        jo.codeeditor.document.EditorDocument.of(DOC));
        view.setSession(session);
        editor.setHost(new jo.codeeditor.view.LspEditorViewHost(view));

        org.eclipse.lsp4j.ApplyWorkspaceEditResponse réponse =
                server.client.applyEdit(
                        new org.eclipse.lsp4j.ApplyWorkspaceEditParams(
                                insertionImport(URI), "import auto"))
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(réponse);
        assertTrue("l'édition poussée doit être appliquée à la session", réponse.isApplied());
        assertTrue(session.getText().toString().startsWith("import java.util.Foo;\n"));
        // Le reste du document est intact.
        assertTrue(session.getText().toString().contains("public class A {"));
    }

    @Test
    public void requêteSansÉdition_refusPropre() throws Exception {
        org.eclipse.lsp4j.ApplyWorkspaceEditResponse réponse =
                server.client.applyEdit(new org.eclipse.lsp4j.ApplyWorkspaceEditParams())
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(réponse);
        assertFalse(réponse.isApplied());
        assertEquals("requête sans édition", réponse.getFailureReason());
    }
}
