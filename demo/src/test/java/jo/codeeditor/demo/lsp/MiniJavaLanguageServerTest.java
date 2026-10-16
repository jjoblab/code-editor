package jo.codeeditor.demo.lsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageClient;
import org.junit.Test;

/**
 * Le mini serveur LSP Java isolé : poignée de main initialize (sync
 * Full), publication de diagnostics à didOpen/didChange, purge à
 * didClose — le tout vérifié SANS Robolectric (pur JVM, le client
 * d'enregistrement reçoit les publications en direct).
 */
public class MiniJavaLanguageServerTest {

    /** URI factice du document de test. */
    private static final String URI = "file:///ws/Atelier.java";

    /** Client d'enregistrement : ne garde que les publications. */
    private static final class RecordingClient implements LanguageClient {
        final List<PublishDiagnosticsParams> published =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams params) {
            published.add(params);
        }

        @Override
        public void showMessage(MessageParams messageParams) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(
                ShowMessageRequestParams showMessageRequestParams) {
            return CompletableFuture.completedFuture(new MessageActionItem());
        }

        @Override
        public void logMessage(MessageParams messageParams) {
        }
    }

    private static MiniJavaLanguageServer serveurConnecté(RecordingClient client) {
        MiniJavaLanguageServer server = new MiniJavaLanguageServer();
        server.connect(client);
        return server;
    }

    private static void didOpen(MiniJavaLanguageServer server, String text) {
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams();
        TextDocumentItem item = new TextDocumentItem();
        item.setUri(URI);
        item.setLanguageId("java");
        item.setVersion(1);
        item.setText(text);
        params.setTextDocument(item);
        server.getTextDocumentService().didOpen(params);
    }

    private static void didChange(MiniJavaLanguageServer server, String text) {
        DidChangeTextDocumentParams params = new DidChangeTextDocumentParams();
        params.setTextDocument(new VersionedTextDocumentIdentifier(URI, 2));
        org.eclipse.lsp4j.TextDocumentContentChangeEvent change =
                new org.eclipse.lsp4j.TextDocumentContentChangeEvent(text);
        params.setContentChanges(List.of(change));
        server.getTextDocumentService().didChange(params);
    }

    private static void didClose(MiniJavaLanguageServer server) {
        DidCloseTextDocumentParams params = new DidCloseTextDocumentParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(URI));
        server.getTextDocumentService().didClose(params);
    }

    @Test
    public void initialize_annonceSyncFull() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        InitializeResult result = server.initialize(new InitializeParams()).get();
        assertNotNull(result);
        assertNotNull(result.getCapabilities().getTextDocumentSync());
        assertEquals(org.eclipse.lsp4j.TextDocumentSyncKind.Full,
                result.getCapabilities().getTextDocumentSync().getLeft());
    }

    @Test
    public void didOpen_avecCodeCasse_pousseDiagnostics() {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        didOpen(server, "class A {\n    int x = 5\n}\n");
        assertEquals("Une publication doit arriver", 1, client.published.size());
        PublishDiagnosticsParams params = client.published.get(0);
        assertEquals(URI, params.getUri());
        assertEquals("Le code cassé produit AU MOINS un diagnostic (unclosed "
                + "ou missing-semicolon)", true, params.getDiagnostics().size() >= 1);
        org.eclipse.lsp4j.Diagnostic first = params.getDiagnostics().get(0);
        assertEquals(DiagnosticSeverity.Error, first.getSeverity());
        assertEquals("mini-java", first.getSource());
        assertNotNull("Chaque diagnostic porte un code de règle", first.getCode());
        assertTrue("Le message est en français", first.getMessage().contains("«"));
    }

    @Test
    public void didChange_avecCodeRepare_videLesDiagnostics() {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        didOpen(server, "class A {\n    int x = 5\n}\n");
        didChange(server, "class A {\n    int x = 5;\n}\n");
        assertEquals(2, client.published.size());
        assertTrue("Le code réparé vide les diagnostics",
                client.published.get(1).getDiagnostics().isEmpty());
    }

    @Test
    public void didClose_videLesDiagnostics() {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        didOpen(server, "class A {\n}\n");
        didClose(server);
        assertEquals("Deux publications : didOpen puis didClose",
                2, client.published.size());
        assertTrue(client.published.get(1).getDiagnostics().isEmpty());
    }

    @Test
    public void codePropre_aucunDiagnostic() {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        didOpen(server, "class A {\n    void f() {\n        g();\n    }\n}\n");
        assertEquals(1, client.published.size());
        assertTrue(client.published.get(0).getDiagnostics().isEmpty());
    }
}
