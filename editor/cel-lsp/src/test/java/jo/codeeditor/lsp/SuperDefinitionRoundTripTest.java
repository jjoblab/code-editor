package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * Round-trip JSON-RPC de la méthode LSP PERSONNALISÉE
 * {@code textDocument/superDefinition} (GO TO Super du menu contextuel
 * unifié — le serveur :lspjava l'expose via
 * {@code jo.lspjava.lsp.NavTextDocumentService}).
 *
 * <p>Valide le MÉCANISME de transport complet, en-process, avec les vraies
 * classes production : un faux serveur répliquant EXACTEMENT le pattern du
 * vrai ({@code @JsonRequest("textDocument/superDefinition")} sur une
 * interface qui ÉTEND TextDocumentService, + un LanguageServer dont le
 * {@code getTextDocumentService()} covariant est annoté
 * {@code @JsonDelegate} — sinon la carte de désérialisation DES PARAMÈTRES
 * côté serveur ne connaît pas la méthode custom et ceux-ci arrivent en
 * LinkedTreeMap brut : « argument type mismatch ») est connecté au client
 * par {@link InProcessStreamConnectionProvider} + le
 * {@link LanguageServerWrapper} production. Le test vérifie que :</p>
 * <ol>
 *   <li>le launcher CLIENT démarre SANS « Duplicate RPC method » — le piège
 *       du delegate covariant : {@code LanguageServer.getTextDocumentService()}
 *       est DÉJÀ annoté {@code @JsonDelegate} dans lsp4j, le redéclarer
 *       covariant casserait TOUS les serveurs ; la méthode custom vit donc
 *       au NIVEAU SUPÉRIEUR de {@link CodeIdeLanguageServer} ;</li>
 *   <li>LSP4J découvre le handler custom côté serveur (dispatch + carte de
 *       désérialisation des paramètres) ;</li>
 *   <li>le client l'appelle TYPÉ via {@code ext.superDefinition(params)} —
 *       le chemin production de {@code LspSuperDefinitionProvider} ;</li>
 *   <li>la réponse (JSON) se désérialise en
 *       {@link CodeIdeLanguageServer.SuperNavTarget} (champs publics
 *       path/start/end/label).</li>
 * </ol>
 *
 * @author jo@Dev
 */
public class SuperDefinitionRoundTripTest {

    /** L'interface custom du faux serveur — mêmes annotations que
     *  {@code jo.lspjava.lsp.NavTextDocumentService}. */
    public interface FakeNavTextDocumentService extends TextDocumentService {
        @JsonRequest("textDocument/superDefinition")
        CompletableFuture<List<Map<String, Object>>> superDefinition(
                TextDocumentPositionParams params);
    }

    /** Le LanguageServer étendu — retour COVARIANT annoté @JsonDelegate,
     *  comme {@code jo.lspjava.lsp.NavLanguageServer} (pattern du service
     *  LOCAL serveur — LÉGITIME ici, l'objet n'est jamais proxyfié). */
    public interface FakeNavLanguageServer extends LanguageServer {
        @Override
        @JsonDelegate
        FakeNavTextDocumentService getTextDocumentService();
    }

    /** Un TextDocumentService à base de stubs + la méthode custom. */
    static class FakeTextDocumentService implements FakeNavTextDocumentService {
        @Override
        public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
        }

        @Override
        public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {
        }

        @Override
        public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {
        }

        @Override
        public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {
        }

        @Override
        public CompletableFuture<List<Map<String, Object>>> superDefinition(
                TextDocumentPositionParams params) {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("path", "/project/src/demo/Base.java");
            hit.put("start", 42);
            hit.put("end", 46);
            hit.put("label", "run  ·  Base");
            return CompletableFuture.completedFuture(List.of(hit));
        }
    }

    /** Le faux serveur : initialize + delegates. */
    static class FakeServer implements FakeNavLanguageServer {
        final FakeNavTextDocumentService tds = new FakeTextDocumentService();

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setExperimental(Map.of("superDefinitionProvider", true));
            return CompletableFuture.completedFuture(new InitializeResult(caps));
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {
        }

        @Override
        public FakeNavTextDocumentService getTextDocumentService() {
            return tds;
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return new WorkspaceService() {
                @Override
                public void didChangeConfiguration(
                        org.eclipse.lsp4j.DidChangeConfigurationParams params) {
                }

                @Override
                public void didChangeWatchedFiles(
                        org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {
                }
            };
        }
    }

    @Test
    public void customSuperDefinitionMethod_roundTripsThroughJsonRpc() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            // Connexion en-process avec les classes production.
            InProcessStreamConnectionProvider provider =
                    new InProcessStreamConnectionProvider(FakeServer::new);
            LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake") {
                @Override
                public StreamConnectionProvider createConnectionProvider(String workingDir) {
                    return provider;
                }
            };
            LanguageServerWrapper wrapper = new LanguageServerWrapper(
                    definition, "/tmp", executor);
            wrapper.start().join();
            try {
                assertNotNull("le RemoteEndpoint du launcher est exposé",
                        wrapper.getRemoteEndpoint());

                // La capability experimental est transportée — vérifiée par
                // la MÉTHODE PRODUCTION de LspLanguage (Map OU JsonObject).
                assertTrue("experimental.superDefinitionProvider annoncé",
                        LspLanguage.superDefinitionSupported(
                                wrapper.getCapabilities()));

                // L'appel TYPÉ — le chemin PRODUCTION de LspSuperDefinitionProvider
                // (proxy CodeIdeLanguageServer → superDefinition(params), méthode
                // top-level : le delegate covariant est interdit côté client).
                // LSP4J désérialise la réponse en SuperNavTarget parce que la
                // méthode fait partie de la carte du launcher.
                TextDocumentPositionParams params = new TextDocumentPositionParams(
                        new TextDocumentIdentifier("file:///project/src/demo/ImplA.java"),
                        new Position(3, 10));
                CodeIdeLanguageServer ext = wrapper.getExtendedServer();
                assertNotNull("le proxy étendu est exposé", ext);
                List<CodeIdeLanguageServer.SuperNavTarget> hits = ext
                        .superDefinition(params)
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(hits);
                assertEquals(1, hits.size());
                CodeIdeLanguageServer.SuperNavTarget hit = hits.get(0);
                assertEquals("/project/src/demo/Base.java", hit.path);
                assertEquals(42, hit.start);
                assertEquals(46, hit.end);
                assertEquals("run  ·  Base", hit.label);
            } finally {
                wrapper.shutdown();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    // ── Ce test ne nécessite PAS Robolectric : le wrapper ne log Android
    //    que sur ses chemins d'erreur (et le module active
    //    unitTests.isReturnDefaultValues). Garde-fou : si un futur
    //    changement ajoute un log inconditionnel, ce test échouera avec
    //    « android.util.Log not mocked » et devra passer sous Robolectric.
}
