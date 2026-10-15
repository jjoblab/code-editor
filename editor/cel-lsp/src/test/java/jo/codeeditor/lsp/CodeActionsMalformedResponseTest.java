package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;
import jo.codeeditor.util.EditorLog;
import jo.codeeditor.util.EditorLogger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * D-07 — plus de catch muets dans le provider d'actions de code : une
 * réponse de serveur malformée est journalisée (warn, EditorLog) puis
 * repli null — l'application de l'action ne plante pas, le défaut du
 * serveur est OBSERVABLE.
 *
 * <p>Rouge avant : les deux sites
 * ({@code resolveEdit}, {@code asWorkspaceEdit}) avalaient toute
 * exception sans un mot — un serveur bogué était indétectable.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CodeActionsMalformedResponseTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    String s = foo;\n}\n";

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private FakeActionsCodeServer server;
    private jo.codeeditor.view.EditorView view;
    private jo.codeeditor.session.EditorSession session;

    /** Logger collecteur : compte les warn émis pendant le test. */
    private final List<String> warns = new ArrayList<>();
    private final AtomicInteger warnsAvecCause = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("d07").toFile();
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
        editor.setHost(new jo.codeeditor.view.LspEditorViewHost(view));

        EditorLog.install(new EditorLogger() {
            @Override public void info(String tag, String message) {}
            @Override public void warn(String tag, String message) {
                warns.add(tag + ": " + message);
            }
            @Override public void warn(String tag, String message, Throwable cause) {
                warns.add(tag + ": " + message);
                warnsAvecCause.incrementAndGet();
            }
            @Override public void error(String tag, String message, Throwable cause) {}
        });
        warns.clear();
    }

    @After
    public void tearDown() {
        EditorLog.install(EditorLog.NOOP);
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    @Test
    public void réponseDeCommandeMalformée_warnJournalisé_repliSansÉdition() {
        // La commande répond un objet JSON qui PORTE le champ changes
        // mais avec une valeur ininterprétable → gson.fromJson lève.
        com.google.gson.JsonObject empoisonné = new com.google.gson.JsonObject();
        empoisonné.addProperty("changes", "ceci-n-est-pas-une-map");
        server.answerOverride = List.of(Either.forLeft(
                new Command("Action empoisonnée", "magic.poison", List.of("Foo"))));
        server.commandResult = empoisonné;

        List<jo.codeeditor.lang.model.CodeAction> actions =
                new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 1);
        assertEquals(1, actions.size());
        assertNotNull(actions.get(0).apply);

        actions.get(0).apply.run();

        assertEquals(1, server.executeCommandRequests.size());
        assertTrue("le document n'est PAS muté par une réponse ininterprétable",
                session.getText().toString().equals(DOC));
        assertTrue("un warn a été journalisé (le catch muet est mort) : " + warns,
                warns.stream().anyMatch(w -> w.contains("LspCodeAction")));
        assertTrue("la cause est conservée dans le journal",
                warnsAvecCause.get() >= 1);
    }

    @Test
    public void résolutionDAffichageÉchoue_repliSansÉdition_sansÉchec() {
        // Action non résolue + serveur qui lève sur resolveCodeAction :
        // repli null, aucun crash — le warn est émis (D-07), l'éditeur
        // reste intact.
        org.eclipse.lsp4j.CodeAction nonRésolue = new org.eclipse.lsp4j.CodeAction();
        nonRésolue.setTitle("Organiser les imports");
        nonRésolue.setKind("source.organizeImports");
        server.answerOverride = List.of(Either.forRight(nonRésolue));
        server.resolveProvider = true;
        server.resolveFail = true;

        List<jo.codeeditor.lang.model.CodeAction> actions =
                new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 1);
        assertEquals(1, actions.size());
        // L'apply existe (action non résolue) : l'exécuter ne doit pas
        // planter malgré la résolution qui échoue.
        actions.get(0).apply.run();
        assertEquals(DOC, session.getText().toString());
    }

    @Test
    public void réponseSaine_comportementInchangé_aucunWarn() {
        // Non-régression : une commande saine applique son édition et
        // ne journalise AUCUN warn.
        server.answerOverride = List.of(Either.forLeft(
                new Command("Nettoyer", "magic.clean", List.of("Foo"))));
        WorkspaceEdit edit = new WorkspaceEdit();
        Map<String, List<TextEdit>> changes = new LinkedHashMap<>();
        changes.put(URI, List.of(new TextEdit(
                new Range(new Position(0, 0), new Position(0, 0)),
                "// nettoyé\n")));
        edit.setChanges(changes);
        server.commandResult = edit;

        List<jo.codeeditor.lang.model.CodeAction> actions =
                new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 1);
        actions.get(0).apply.run();

        assertTrue(session.getText().toString().startsWith("// nettoyé\n"));
        assertEquals(0, warns.size());
        assertNull("aucun warn avec cause sur le chemin sain",
                warns.isEmpty() ? null : warns.get(0));
    }
}
