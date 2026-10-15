package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trip JSON-RPC de l'IDENTITÉ des diagnostics dans le contexte
 * {@code textDocument/codeAction}.
 *
 * <p>Le faux serveur ({@link FakeActionsCodeServer} — top-level public,
 * car le pont in-process découvre {@code connect} par réflexion) réplique
 * le contrat du serveur réel : il ne propose un quick-fix QUE si le
 * diagnostic reçu dans le contexte porte un {@code code} ET des
 * {@code data} structurées — c'est de cette identité, pas du message
 * localisé, qu'un serveur dérive des corrections exactes. Avant le
 * correctif, le contexte reconstruit à la main ne portait ni code ni
 * data : zéro action, pour tout fichier, en permanence. Après : l'identité
 * fait le chemin complet serveur → JSON-RPC → cache brut → contexte.</p>
 */
class CodeActionIdentityRoundTripTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    String s = foo(bar);\n}\n";

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
        // Câble client→éditeur (même chemin que LspEditor.connect).
        wrapper.getClient().setLspEditor(editor);
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    @Test
    void identitéComplète_proposeLeQuickFix() {
        // Publication RÉELLE : serveur → JSON-RPC → client → cache brut.
        server.publishIdentityDiagnostics(URI);
        attendreCacheBrut();

        LspCodeActionsProvider provider =
                new LspCodeActionsProvider(wrapper, editor, null);
        List<jo.codeeditor.lang.model.CodeAction> actions = provider.codeActions(DOC, 1);

        assertEquals(1, actions.size(), "l'identité transportée doit débloquer le quick-fix");
        assertEquals("Importer Foo (java.util)", actions.get(0).title);
        assertEquals("quickfix", actions.get(0).kind);
        assertEquals(1, server.codeActionRequests);
    }

    @Test
    void contexteReçu_identitéIntacte() {
        server.publishIdentityDiagnostics(URI);
        attendreCacheBrut();
        new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 1);

        CodeActionParams reçus = server.lastParams;
        assertNotNull(reçus);
        assertNotNull(reçus.getContext());
        assertEquals(1, reçus.getContext().getDiagnostics().size());
        Diagnostic d = reçus.getContext().getDiagnostics().get(0);
        assertEquals("famille:1", d.getCode().getLeft());
        assertEquals("compilateur", d.getSource());
        assertEquals(DiagnosticSeverity.Error, d.getSeverity());
        // Le data a traversé le JSON-RPC (List → JsonElement) et reste
        // exploitable côté serveur.
        assertNotNull(d.getData());
        assertTrue(d.getData().toString().contains("Foo"));
        // triggerKind d'invocation explicite.
        assertEquals(org.eclipse.lsp4j.CodeActionTriggerKind.Invoked,
                reçus.getContext().getTriggerKind());
    }

    @Test
    void cacheBrut_conserveLIdentitéAprèsPublication() {
        server.publishIdentityDiagnostics(URI);
        List<Diagnostic> bruts = attendreCacheBrut();
        assertEquals(1, bruts.size());
        Diagnostic d = bruts.get(0);
        assertEquals("famille:1", d.getCode().getLeft());
        assertNotNull(d.getData());
        assertEquals("compilateur", d.getSource());
    }

    @Test
    void diagnosticHorsPlage_excluDuContexte() {
        server.publishIdentityDiagnostics(URI);
        attendreCacheBrut();
        // Ligne 0 : le diagnostic (ligne 1) n'intersecte pas → contexte
        // vide → aucune action même si l'identité est intacte.
        List<jo.codeeditor.lang.model.CodeAction> actions =
                new LspCodeActionsProvider(wrapper, editor, null).codeActions(DOC, 0);
        assertTrue(actions.isEmpty());
        assertNotNull(server.lastParams);
        assertTrue(server.lastParams.getContext().getDiagnostics().isEmpty());
    }

    /** La publication est asynchrone côté pont — borne l'attente du cache. */
    private List<Diagnostic> attendreCacheBrut() {
        // A-06 : attente bornée de la publication asynchrone côté pont.
        jo.codeeditor.AttenteBornee.jusquA(5000, () -> !editor.cachedLspDiagnostics().isEmpty());
        return editor.cachedLspDiagnostics();
    }
}
