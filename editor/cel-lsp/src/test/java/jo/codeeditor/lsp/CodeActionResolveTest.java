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
 * La résolution différée des actions ({@code codeAction/resolve}) et le
 * transport des actions DÉSACTIVÉES :
 *
 * <ul>
 *   <li>une action sans edit mais avec données, quand le serveur annonce
 *       resolveProvider, est résolue à L'APPLICATION — l'édition obtenue
 *       est appliquée par la voie commune ;</li>
 *   <li>une action désactivée (disabled.reason) reste listée, informe le
 *       motif, et son application est un no-op expliqué ;</li>
 *   <li>sans resolveProvider, une action sans edit reste muette (aucune
 *       requête de résolution émise).</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class CodeActionResolveTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    String s = foo;\n}\n";

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private FakeActionsCodeServer server;
    private jo.codeeditor.session.EditorSession session;

    @Before
    public void setUp() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("resolve").toFile();
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
        jo.codeeditor.view.EditorView view =
                new jo.codeeditor.view.EditorView(RuntimeEnvironment.getApplication());
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

    /** L'action résolue : édition d'import. */
    private static org.eclipse.lsp4j.CodeAction actionRésolue() {
        org.eclipse.lsp4j.CodeAction resolved = new org.eclipse.lsp4j.CodeAction("Extraire variable");
        WorkspaceEdit edit = new WorkspaceEdit();
        Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
        changes.put(URI, List.of(new TextEdit(
                new Range(new Position(0, 0), new Position(0, 0)),
                "import java.util.Foo;\n")));
        edit.setChanges(changes);
        resolved.setEdit(edit);
        return resolved;
    }

    @Test
    public void actionNonRésolue_résolueÀLApplication() {
        server.resolveProvider = true;
        // Recâble un wrapper sur le serveur avec resolveProvider annoncé
        // (les capabilities sont figées à initialize).
        server.answerOverride = List.of(Either.forRight(actionNonRésolue()));
        server.resolveByTitle.put("Extraire variable", actionRésolue());
        rebrancherServeurAvecResolve();

        List<jo.codeeditor.lang.model.CodeAction> actions =
                new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 1);
        assertEquals(1, actions.size());
        assertEquals("aucune résolution tant que l'utilisateur n'applique pas", 0, server.resolveRequests);

        actions.get(0).apply.run();

        assertEquals("la résolution part à l'application", 1, server.resolveRequests);
        assertTrue(session.getText().toString().startsWith("import java.util.Foo;\n"));
        assertTrue(session.getText().toString().contains("public class A {"));
    }

    @Test
    public void actionDésactivée_listéeAvecMotif_applicationNoOp() {
        org.eclipse.lsp4j.CodeAction disabled = new org.eclipse.lsp4j.CodeAction("Correction indisponible");
        org.eclipse.lsp4j.CodeActionDisabled raison =
                new org.eclipse.lsp4j.CodeActionDisabled();
        raison.setReason("le fichier est en lecture seule");
        disabled.setDisabled(raison);
        server.answerOverride = List.of(Either.forRight(disabled));

        List<jo.codeeditor.lang.model.CodeAction> actions =
                new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 1);
        assertEquals(1, actions.size());
        assertEquals("le fichier est en lecture seule", actions.get(0).disabledReason);
        // Application = no-op expliqué, sans exception, document intact.
        actions.get(0).apply.run();
        assertEquals(DOC, session.getText().toString());
    }

    @Test
    public void sansResolveProvider_aucuneRequêteDeRésolution() {
        server.answerOverride = List.of(Either.forRight(actionNonRésolue()));
        server.resolveByTitle.put("Extraire variable", actionRésolue());
        // resolveProvider = false par défaut.

        List<jo.codeeditor.lang.model.CodeAction> actions =
                new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 1);
        assertEquals(1, actions.size());
        actions.get(0).apply.run();
        assertEquals("sans resolveProvider annoncé, aucune résolution n'est tentée",
                0, server.resolveRequests);
        assertEquals(DOC, session.getText().toString());
    }

    private static org.eclipse.lsp4j.CodeAction actionNonRésolue() {
        org.eclipse.lsp4j.CodeAction unresolved = new org.eclipse.lsp4j.CodeAction("Extraire variable");
        unresolved.setKind(org.eclipse.lsp4j.CodeActionKind.RefactorExtract);
        unresolved.setData(java.util.List.of("opaque"));
        return unresolved;
    }

    /**
     * Les capabilities sont négociées à initialize : le test de résolution
     * repart d'un wrapper NEUF branché sur le MÊME serveur (resolveProvider
     * maintenant vrai).
     */
    private void rebrancherServeurAvecResolve() {
        wrapper.shutdown();
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
        jo.codeeditor.view.EditorView view =
                new jo.codeeditor.view.EditorView(RuntimeEnvironment.getApplication());
        session = new jo.codeeditor.session.EditorSession(
                jo.codeeditor.document.EditorDocument.of(DOC));
        view.setSession(session);
        editor.setEditorView(view);
    }
}
