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

    // ════════════════════════════════════════════════════════════════
    // 4.11.0 — Aide de signature réelle
    // ════════════════════════════════════════════════════════════════

    @Test
    public void initialize_annonceLaideDeSignature() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        InitializeResult result = server.initialize(new InitializeParams()).get();
        assertNotNull(result.getCapabilities().getSignatureHelpProvider());
        assertTrue("déclencheurs ( et ,",
                result.getCapabilities().getSignatureHelpProvider()
                        .getTriggerCharacters().contains("("));
        assertTrue(result.getCapabilities().getSignatureHelpProvider()
                        .getTriggerCharacters().contains(","));
    }

    @Test
    public void signatureHelp_sertLesVraiesSurchargesAvecParamètreActif()
            throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        // Ligne 1 (base 0), le caret vit après la virgule frappée :
        // System.out.println("salut", → paramètre actif = 1.
        String code = "class A {\n    void t() {\n        System.out.println(\"salut\",\n    }\n}\n";
        didOpen(server, code);
        org.eclipse.lsp4j.SignatureHelp help = server.getTextDocumentService()
                .signatureHelp(paramsSignature(2, 35)).get();
        assertNotNull(help);
        assertTrue("les vraies surcharges d'impression",
                help.getSignatures().size() >= 7);
        assertEquals("println()", help.getSignatures().get(0).getLabel());
        assertEquals("println(String x)", help.getSignatures().get(1).getLabel());
        // Le paramètre actif suit la virgule : global ET par signature.
        assertEquals(1, help.getActiveParameter().intValue());
        assertEquals(1, help.getSignatures().get(1).getActiveParameter().intValue());
        // La documentation du paramètre alimente la bande doc du popup.
        org.eclipse.lsp4j.ParameterInformation p = help.getSignatures().get(1)
                .getParameters().get(0);
        assertEquals("String x", p.getLabel().getLeft());
        assertEquals("le texte à imprimer", p.getDocumentation().getLeft());
    }

    @Test
    public void signatureHelp_méthodeDéclaréeDansLeBuffer() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        String code = "class Atelier {\n"
                + "    double calculerTotal(double base, double taux) {\n"
                + "        return base * taux;\n"
                + "    }\n"
                + "    void t() { calculerTotal(\n"
                + "}\n";
        didOpen(server, code);
        org.eclipse.lsp4j.SignatureHelp help = server.getTextDocumentService()
                .signatureHelp(paramsSignature(4, 29)).get();
        assertNotNull(help);
        assertEquals(1, help.getSignatures().size());
        assertEquals("calculerTotal(double base, double taux)",
                help.getSignatures().get(0).getLabel());
        assertEquals(0, help.getActiveParameter().intValue());
    }

    @Test
    public void signatureHelp_horsAppel_réponseVide() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        didOpen(server, "class A {\n    int x = 5;\n}\n");
        org.eclipse.lsp4j.SignatureHelp help = server.getTextDocumentService()
                .signatureHelp(paramsSignature(1, 4)).get();
        assertNotNull(help);
        assertTrue("hors appel : aucune signature (le client garde son repli)",
                help.getSignatures().isEmpty());
    }

    @Test
    public void positionVersOffset_réciproqueExacte() {
        String code = "class A {\n    void t() {\n        g(1, 2);\n    }\n}";
        // Toutes les positions (ligne, colonne) de toutes les lignes
        // bornées à la fin de ligne → l'offset retombe sur ligne+décalage.
        int offset = 0;
        String[] lignes = code.split("\n", -1);
        for (int l = 0; l < lignes.length; l++) {
            for (int c = 0; c <= lignes[l].length(); c++) {
                assertEquals("ligne " + l + " colonne " + c,
                        offset + c,
                        MiniJavaLanguageServer.positionVersOffset(
                                code, new org.eclipse.lsp4j.Position(l, c)));
            }
            offset += lignes[l].length() + 1;
        }
        // Position au-delà de la dernière ligne : bornée à la fin du
        // texte, jamais d'explosion.
        assertEquals(code.length(),
                MiniJavaLanguageServer.positionVersOffset(
                        code, new org.eclipse.lsp4j.Position(99, 99)));
    }

    private static org.eclipse.lsp4j.SignatureHelpParams paramsSignature(
            int ligne, int caractère) {
        org.eclipse.lsp4j.SignatureHelpParams params =
                new org.eclipse.lsp4j.SignatureHelpParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(URI));
        params.setPosition(new org.eclipse.lsp4j.Position(ligne, caractère));
        return params;
    }
}
