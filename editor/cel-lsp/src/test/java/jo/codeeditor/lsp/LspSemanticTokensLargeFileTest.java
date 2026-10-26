package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Régime « gros fichiers » et tir sémantique (4.5.0) : quand l'hôte
 * signale {@code isDocumentLarge()} — document au-delà des seuils ou
 * forçage posé par l'application — le tir
 * {@code textDocument/semanticTokens/full} est coupé : aucune requête
 * ne part, la coloration lexicale (régime progressif) et la frappe
 * continuent seules.
 */
class LspSemanticTokensLargeFileTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    static int total = 1;\n}\n";

    private ExecutorService executor;
    private LspProject project;
    private LspEditor editor;
    private FakeSemanticTokensServer server;
    private HoteGrosFichier host;
    private File racine;

    @BeforeEach
    void setUp(@TempDir File racine) {
        this.racine = racine;
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        server = new FakeSemanticTokensServer();
    }

    @AfterEach
    void tearDown() {
        try {
            editor.disconnect();
        } catch (RuntimeException ignorée) {
        }
        project.shutdown();
        executor.shutdownNow();
    }

    private void connecter() throws Exception {
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> server);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "factice") {
            @Override
            public jo.codeeditor.lsp.connection.StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        project.addServerDefinition(definition);
        editor = project.createEditor(URI);
        host = new HoteGrosFichier(DOC);
        editor.setHost(host);
        editor.connect().get(10, TimeUnit.SECONDS);
        assertNotNull(editor.currentWrapper());
    }

    @Test
    void hôtePetitDocument_tirNormal() throws Exception {
        server.data = List.of(1, 15, 5, 6, 0);
        connecter();
        host.large = false;
        publier();
        long fin = System.currentTimeMillis() + 5000;
        while (host.session.getSemanticTokens().isEmpty()) {
            if (System.currentTimeMillis() > fin) {
                fail("précondition : les jetons arrivent en régime normal");
            }
            Thread.sleep(20);
        }
        assertTrue(server.semanticTokensRequests > 0);
    }

    @Test
    void hôteGrosFichier_aucuneRequêteSémantique() throws Exception {
        server.data = List.of(1, 15, 5, 6, 0);
        connecter();
        // Le régime est lu À CHAQUE tir (pas à l'initialisation) — le
        // forçage posé après connexion coupe le tir suivant.
        host.large = true;
        publier();
        // Laisse le pipeline s'exprimer — AUCUNE requête ne doit partir.
        Thread.sleep(700);
        assertEquals(0, server.semanticTokensRequests);
        assertTrue(host.session.getSemanticTokens().isEmpty());
    }

    private void publier() {
        org.eclipse.lsp4j.PublishDiagnosticsParams params =
                new org.eclipse.lsp4j.PublishDiagnosticsParams();
        params.setUri(URI);
        params.setDiagnostics(List.of());
        editor.publishDiagnostics(params);
    }

    /** Hôte factice à régime gros fichiers pilotable. */
    private static final class HoteGrosFichier implements jo.codeeditor.lang.host.EditorHost {
        final jo.codeeditor.session.EditorSession session;
        volatile boolean large = false;

        HoteGrosFichier(String texte) {
            session = new jo.codeeditor.session.EditorSession(
                    jo.codeeditor.document.EditorDocument.of(texte));
        }

        @Override public jo.codeeditor.session.EditorSession session() { return session; }
        @Override public jo.codeeditor.document.EditorDocument document() {
            return session.getDocument();
        }
        @Override public void setLanguage(jo.codeeditor.lang.Language l) {}
        @Override public void notifyDiagnosticsChanged() {}
        @Override public void notifyTextChanged() {}
        @Override public void invalidate() {}
        @Override public boolean isAttachedToWindow() { return false; }
        @Override public boolean isDocumentLarge() { return large; }
        @Override public void post(Runnable task) { task.run(); }
        @Override public void postDelayed(Runnable task, long d) { task.run(); }
    }
}
