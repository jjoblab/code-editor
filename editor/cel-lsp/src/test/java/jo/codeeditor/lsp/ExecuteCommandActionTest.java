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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * Les actions à COMMANDE ne sont plus des no-op silencieuses : leur
 * application émet {@code workspace/executeCommand} ; si la réponse du
 * serveur porte un WorkspaceEdit (retour en valeur — la voie poussée
 * workspace/applyEdit est couverte par ApplyEditRoundTripTest), l'édition
 * est appliquée à la session par la voie commune.
 */
@RunWith(RobolectricTestRunner.class)
public class ExecuteCommandActionTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    String s = foo;\n}\n";

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private FakeActionsCodeServer server;
    private jo.codeeditor.view.EditorView view;
    private jo.codeeditor.session.EditorSession session;

    @Before
    public void setUp() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("exec-cmd").toFile();
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
        view = new jo.codeeditor.view.EditorView(RuntimeEnvironment.getApplication());
        session = new jo.codeeditor.session.EditorSession(
                jo.codeeditor.document.EditorDocument.of(DOC));
        view.setSession(session);
        editor.setEditorView(view);
    }

    @After
    public void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    @Test
    public void actionCommande_exécuteEtAppliqueLEditRetourné() {
        // Le serveur répond une action COMMANDE ; la commande retourne
        // un WorkspaceEdit (sérialisé en arbre JSON par le transport).
        server.answerOverride = List.of(Either.forLeft(
                new org.eclipse.lsp4j.Command("Nettoyer les imports", "magic.clean",
                        List.of("Foo"))));
        WorkspaceEdit edit = new WorkspaceEdit();
        Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
        changes.put(URI, List.of(new TextEdit(
                new Range(new Position(0, 0), new Position(0, 0)),
                "import java.util.Foo;\n")));
        edit.setChanges(changes);
        server.commandResult = edit;

        List<jo.codeeditor.lang.model.CodeAction> actions =
                new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 1);
        assertEquals(1, actions.size());
        assertEquals("Nettoyer les imports", actions.get(0).title);
        assertNotNull("apply doit exister (commande exécutable)", actions.get(0).apply);

        actions.get(0).apply.run();

        assertEquals("l'application doit émettre workspace/executeCommand",
                1, server.executeCommandRequests.size());
        org.eclipse.lsp4j.ExecuteCommandParams req = server.executeCommandRequests.get(0);
        assertEquals("magic.clean", req.getCommand());
        assertEquals(1, req.getArguments().size());
        assertTrue(String.valueOf(req.getArguments().get(0)).contains("Foo"));
        // L'édition retournée par la commande est APPLIQUÉE à la session.
        assertTrue(session.getText().toString().startsWith("import java.util.Foo;\n"));
        assertTrue(session.getText().toString().contains("public class A {"));
    }

    @Test
    public void commandeSansRetour_compteRenduJournaliséSansÉchec() {
        server.answerOverride = List.of(Either.forLeft(
                new org.eclipse.lsp4j.Command("Commande muette", "magic.silent", null)));
        server.commandResult = null;

        List<jo.codeeditor.lang.model.CodeAction> actions =
                new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 1);
        assertEquals(1, actions.size());
        // Ne doit pas lever : la commande s'exécute, rien à appliquer.
        actions.get(0).apply.run();
        assertEquals(1, server.executeCommandRequests.size());
        // Le document est inchangé.
        assertEquals(DOC, session.getText().toString());
    }
}
