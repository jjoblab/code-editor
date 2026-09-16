package jo.codeeditor.lsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonDelegate;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * ★ v2.40 — Round-trip JSON-RPC de la méthode LSP PERSONNALISÉE
 * {@code textDocument/decompiledSource} (câble de bout en bout la
 * fonctionnalité « GO TO Definition sur cible binaire » — le serveur
 * :lspjava l'expose via {@code jo.lspjava.lsp.NavTextDocumentService}).
 *
 * <p>Valide le MÉCANISME de transport complet, en-process, avec les vraies
 * classes production : un faux serveur répliquant EXACTEMENT le pattern du
 * vrai ({@code @JsonRequest("textDocument/decompiledSource")} sur une
 * interface qui ÉTEND TextDocumentService, + un LanguageServer dont le
 * {@code getTextDocumentService()} covariant est annoté
 * {@code @JsonDelegate} — sinon la carte de désérialisation des PARAMÈTRES
 * côté serveur ne connaît pas la méthode custom et ceux-ci arrivent en
 * LinkedTreeMap brut : « argument type mismatch »). Connecté au client
 * par {@link InProcessStreamConnectionProvider} + le
 * {@link LanguageServerWrapper} production, le test vérifie que :</p>
 * <ol>
 *   <li>le launcher CLIENT démarre SANS « Duplicate RPC method » — le piège
 *       du delegate covariant : {@code LanguageServer.getTextDocumentService()}
 *       est DÉJÀ annoté {@code @JsonDelegate} dans lsp4j, le redéclarer
 *       covariant casserait TOUS les serveurs ; la méthode custom vit donc
 *       au NIVEAU SUPÉRIEUR de {@link CodeIdeLanguageServer} ;</li>
 *   <li>LSP4J découvre le handler custom côté serveur (dispatch + carte de
 *       désérialisation des paramètres) ;</li>
 *   <li>le client l'appelle TYPÉ via {@code ext.decompiledSource(params)} ;</li>
 *   <li>la réponse (JSON) se désérialise en
 *       {@link CodeIdeLanguageServer.DecompiledSourceResult} (champ public
 *       {@code source}).</li>
 * </ol>
 *
 * @author jo@Dev
 * @since v2.40
 */
public class DecompiledSourceRoundTripTest {

    /** L'interface custom du faux serveur — mêmes annotations que
     *  {@code jo.lspjava.lsp.NavTextDocumentService}. */
    public interface FakeDecompiledTextDocumentService extends TextDocumentService {
        @JsonRequest("textDocument/decompiledSource")
        CompletableFuture<Map<String, Object>> decompiledSource(
                Map<String, Object> params);
    }

    /** Le LanguageServer étendu — retour COVARIANT annoté @JsonDelegate,
     *  comme {@code jo.lspjava.lsp.NavLanguageServer} (pattern du service
     *  LOCAL serveur — LÉGITIME ici, l'objet n'est jamais proxyfié). */
    public interface FakeDecompiledLanguageServer extends LanguageServer {
        @Override
        @JsonDelegate
        FakeDecompiledTextDocumentService getTextDocumentService();
    }

    /** Un TextDocumentService à base de stubs + la méthode custom. */
    static class FakeTextDocumentService implements FakeDecompiledTextDocumentService {
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
        public CompletableFuture<Map<String, Object>> decompiledSource(
                Map<String, Object> params) {
            Map<String, Object> result = new LinkedHashMap<>();
            // Echo the FQCN back as the source — verifier le round-trip
            // du FQCN params.source → result.source.
            String fqcn = params.get("fqcn") == null ? "" : params.get("fqcn").toString();
            result.put("source", "// Decompiled from " + fqcn + ".class\n"
                    + "package " + fqcn.substring(0, Math.max(0, fqcn.lastIndexOf('.'))) + ";\n"
                    + "public class " + fqcn.substring(Math.max(0, fqcn.lastIndexOf('.') + 1))
                    + " {}");
            return CompletableFuture.completedFuture(result);
        }
    }

    /** Le faux serveur : initialize + delegates. */
    static class FakeServer implements FakeDecompiledLanguageServer {
        final FakeDecompiledTextDocumentService tds = new FakeTextDocumentService();

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            ServerCapabilities caps = new ServerCapabilities();
            Map<String, Object> experimental = new LinkedHashMap<>();
            experimental.put("superDefinitionProvider", true);
            experimental.put("decompiledSourceProvider", true);
            caps.setExperimental(experimental);
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
        public FakeDecompiledTextDocumentService getTextDocumentService() {
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
    public void customDecompiledSourceMethod_roundTripsThroughJsonRpc() throws Exception {
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

                // La capability experimental.decompiledSourceProvider est
                // transportée — vérifiée en production par lecture du Map OU
                // du JsonObject (LSP4J peut désérialiser dans l'un OU l'autre
                // selon le contexte Gson — cf. LspLanguage.superDefinitionSupported).
                Object experimental = wrapper.getCapabilities().getExperimental();
                assertNotNull("experimental annoncé", experimental);
                boolean isMap = experimental instanceof java.util.Map;
                boolean isJson = experimental instanceof com.google.gson.JsonObject;
                assertTrue("experimental est un Map ou un JsonObject",
                        isMap || isJson);
                boolean decompiledCap;
                if (isMap) {
                    Object v = ((java.util.Map<?, ?>) experimental).get("decompiledSourceProvider");
                    decompiledCap = Boolean.TRUE.equals(v);
                } else {
                    com.google.gson.JsonObject o = (com.google.gson.JsonObject) experimental;
                    decompiledCap = o.has("decompiledSourceProvider")
                            && o.get("decompiledSourceProvider").getAsBoolean();
                }
                assertTrue("experimental.decompiledSourceProvider annoncé",
                        decompiledCap);

                // L'appel TYPÉ — le chemin PRODUCTION de LspProject.decompiledSource
                // (proxy CodeIdeLanguageServer → decompiledSource(params), méthode
                // top-level : le delegate covariant est interdit côté client).
                // LSP4J désérialise la réponse en DecompiledSourceResult parce
                // que la méthode fait partie de la carte du launcher.
                CodeIdeLanguageServer.DecompiledSourceParams params =
                        new CodeIdeLanguageServer.DecompiledSourceParams(
                                "java.util.HashMap");
                CodeIdeLanguageServer ext = wrapper.getExtendedServer();
                assertNotNull("le proxy étendu est exposé", ext);
                CodeIdeLanguageServer.DecompiledSourceResult r = ext
                        .decompiledSource(params)
                        .get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(r);
                assertNotNull("la source est non-null", r.source);
                assertTrue("la source contient le FQCN",
                        r.source.contains("HashMap"));
                assertTrue("la source contient la signature de classe",
                        r.source.contains("public class"));
            } finally {
                wrapper.shutdown();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void lspProjectFacade_returnsSourceFromServer() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            InProcessStreamConnectionProvider provider =
                    new InProcessStreamConnectionProvider(FakeServer::new);
            LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake") {
                @Override
                public StreamConnectionProvider createConnectionProvider(String workingDir) {
                    return provider;
                }
            };
            LspProject project = new LspProject("/tmp");
            project.addServerDefinition(definition);
            // Force le démarrage du serveur en ouvrant un editor.
            LspEditor editor = project.createEditor("file:///tmp/Foo.java");
            editor.connect().get(10, java.util.concurrent.TimeUnit.SECONDS);
            try {
                // La façade LspProject.decompiledSource appelle le proxy
                // CodeIdeLanguageServer.decompiledSource et retourne le
                // champ source, ou null en cas d'échec.
                String src = project.decompiledSource("java", "java.lang.String");
                assertNotNull("la façade retourne la source", src);
                assertTrue("la source contient le nom court String",
                        src.contains("String"));
            } finally {
                editor.disconnect();
                project.shutdown();
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
