package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;
import jo.codeeditor.lang.model.CompletionItem;
import jo.codeeditor.lang.provider.CompletionPublisher;

import org.eclipse.lsp4j.CompletionTriggerKind;
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
 * Le trigger kind RÉEL de la complétion (P2-9) : la complétion
 * auto-déclenchée par la frappe d'un caractère déclencheur (ex. « . »)
 * part avec {@code triggerKind = TriggerCharacter} + le caractère dans
 * le {@code CompletionContext} ; toute invocation explicite (raccourci,
 * menu, ancienne signature) part en {@code Invoked} sans caractère —
 * le modèle Android Studio, transporté par le protocole.
 */
class CompletionTriggerContextTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    String s = foo.\n}\n";

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private FakeCompletionTriggerServer server;
    private LspCompletionProvider provider;

    /** Publisher collecteur no-op — le test n'inspecte que le contexte reçu. */
    private final CompletionPublisher publisher = new CompletionPublisher() {
        @Override
        public void addItem(CompletionItem item) {
        }

        @Override
        public void addItems(List<CompletionItem> items) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    @BeforeEach
    void setUp(@TempDir File racine) throws Exception {
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        server = new FakeCompletionTriggerServer();
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
        this.provider = new LspCompletionProvider(wrapper, editor, null);
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    @Test
    void frappeDuPoint_triggerCharacterTransporté() {
        provider.complete(DOC, DOC.indexOf("foo.") + 4, ".", publisher);
        assertEquals(1, server.completionRequests, "une requête par complétion");
        assertEquals(CompletionTriggerKind.TriggerCharacter,
                server.lastCompletion.getContext().getTriggerKind(),
                "auto-déclenché par « . » → TriggerCharacter");
        assertEquals(".", server.lastCompletion.getContext().getTriggerCharacter(),
                "le caractère déclencheur est transporté");
    }

    @Test
    void invocationExplicite_sansCaractère_estInvoked() {
        provider.complete(DOC, DOC.indexOf("foo") + 1, (String) null, publisher);
        assertEquals(CompletionTriggerKind.Invoked,
                server.lastCompletion.getContext().getTriggerKind(),
                "invocation explicite → Invoked");
        assertNull(server.lastCompletion.getContext().getTriggerCharacter(),
                "pas de caractère déclencheur sur une invocation explicite");
    }

    @Test
    void ancienneSignature_troisParamètres_estInvoked() {
        // Compatibilité : la méthode SPI d'origine délègue en Invoked.
        provider.complete(DOC, DOC.indexOf("foo") + 1, publisher);
        assertEquals(CompletionTriggerKind.Invoked,
                server.lastCompletion.getContext().getTriggerKind(),
                "l'ancienne signature reste Invoked (délégation)");
        assertNull(server.lastCompletion.getContext().getTriggerCharacter());
    }
}
