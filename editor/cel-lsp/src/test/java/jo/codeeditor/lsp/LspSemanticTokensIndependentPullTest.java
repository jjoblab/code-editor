package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

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
 * Tir sémantique indépendant de la publication de diagnostics (4.9.0,
 * écart n°2 de l'analyse comparative) : un serveur qui annonce
 * {@code semanticTokensProvider} mais ne publie JAMAIS de diagnostics
 * doit quand même colorer le document sémantiquement.
 *
 * <ul>
 *   <li><b>Premier tir à la connexion</b> — la pose du langage
 *       (didOpen parti, SPI posé) tire immédiatement, sans attendre un
 *       publishDiagnostics qui n'arrivera jamais ;</li>
 *   <li><b>Tir après édition</b> — chaque didChange envoyé re-tire : la
 *       requête semanticTokens/full est enfilée APRÈS le didChange dans
 *       la file LSP4J, le serveur répond avec le texte frais ;</li>
 *   <li><b>Purge à la déconnexion</b> — les jetons du fichier fermé ne
 *       survivent pas à la fenêtre EmptyLanguage (symétrie avec la
 *       purge des diagnostics 3.74.0).</li>
 * </ul>
 *
 * <p>Le faux serveur ne publie JAMAIS de diagnostics : la moindre
 * couleur en session prouve que le déclencheur n'est plus
 * publishDiagnostics seul.</p>
 */
class LspSemanticTokensIndependentPullTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    static int total = 1;\n}\n";

    /** « class » ligne 0, col 7, longueur 5, type 2, sans modificateur. */
    private static final List<Integer> JETON_CLASS = List.of(0, 7, 5, 2, 0);

    private ExecutorService executor;
    private LspProject project;
    private LspEditor editor;
    private FakeSemanticTokensServer server;
    private HoteSilencieux host;
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
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        project.addServerDefinition(definition);
        editor = project.createEditor(URI);
        host = new HoteSilencieux(DOC);
        editor.setHost(host);
        editor.connect().get(10, TimeUnit.SECONDS);
        assertNotNull(editor.currentWrapper());
        assertTrue(editor.isLanguageApplied(),
                "précondition : le langage est posé (host non attaché → pose inline)");
    }

    @Test
    void connexionSansPublication_premierTirSémantique() throws Exception {
        server.data = JETON_CLASS;
        connecter();
        // AUCUNE publication de diagnostics n'est faite : si les jetons
        // arrivent, c'est le déclencheur de CONNEXION qui les a tirés.
        assertTrue(jo.codeeditor.AttenteBornee.jusquA(5000, 10L,
                        () -> !host.session.getSemanticTokens().isEmpty()),
                "le premier tir à la connexion doit colorer sans publishDiagnostics"
                        + " (requêtes serveur : " + server.semanticTokensRequests + ")");
        assertEquals(1, host.session.getSemanticTokens().size());
        assertTrue(server.semanticTokensRequests >= 1);
    }

    @Test
    void éditionSansPublication_tirAprèsDidChange() throws Exception {
        // Premier tir à la connexion : data VIDE → rien en session
        // (l'apply vide est un no-op, pas un échec).
        server.data = List.of();
        connecter();
        assertTrue(jo.codeeditor.AttenteBornee.jusquA(3000, 10L,
                        () -> server.semanticTokensRequests >= 1),
                "précondition : le tir de connexion est parti");
        assertTrue(host.session.getSemanticTokens().isEmpty(),
                "data vide à la connexion → aucun jeton en session");
        // Le compteur est stable ici : entre la connexion et l'édition,
        // aucun autre déclencheur ne peut tirer (aucune publication,
        // aucune édition).
        int requêtesAprèsConnexion = server.semanticTokensRequests;

        // Le serveur « se réveille » (nouvelle analyse) et l'utilisateur
        // édite : le didChange (débounce 300 ms) doit déclencher le tir
        // SANS qu'aucun diagnostic ne soit publié.
        server.data = JETON_CLASS;
        host.session.replaceRange(0, 6, "Public");

        assertTrue(jo.codeeditor.AttenteBornee.jusquA(5000, 10L,
                        () -> !host.session.getSemanticTokens().isEmpty()),
                "le didChange doit re-tirer les jetons sans publishDiagnostics"
                        + " (requêtes serveur : " + server.semanticTokensRequests + ")");
        assertTrue(server.semanticTokensRequests >= requêtesAprèsConnexion + 1,
                "le tir post-édition est bien un NOUVEAU tir ("
                        + server.semanticTokensRequests + " requêtes vs "
                        + requêtesAprèsConnexion + " avant édition)");
        assertEquals(1, host.session.getSemanticTokens().size());
    }

    @Test
    void déconnexion_jetonsPurgés() throws Exception {
        server.data = JETON_CLASS;
        connecter();
        assertTrue(jo.codeeditor.AttenteBornee.jusquA(5000, 10L,
                        () -> !host.session.getSemanticTokens().isEmpty()),
                "précondition : les jetons sont en session avant la déconnexion");

        editor.disconnect();

        assertTrue(host.session.getSemanticTokens().isEmpty(),
                "les jetons du fichier fermé ne survivent pas à la déconnexion");
    }

    /**
     * Hôte factice jamais attaché (pose inline à la connexion, purge
     * inline à la déconnexion). La session est RÉELLE : les éditions
     * passent par replaceRange et déclenchent le pont didChange.
     */
    private static final class HoteSilencieux implements jo.codeeditor.lang.host.EditorHost {
        final jo.codeeditor.session.EditorSession session;

        HoteSilencieux(String texte) {
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
        @Override public boolean isDocumentLarge() { return false; }
        @Override public void post(Runnable task) { task.run(); }
        @Override public void postDelayed(Runnable task, long d) { task.run(); }
    }
}
