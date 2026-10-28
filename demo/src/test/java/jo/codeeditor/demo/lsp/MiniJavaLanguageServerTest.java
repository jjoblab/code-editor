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

    // ════════════════════════════════════════════════════════════════
    // 4.12.0 — Complétion contextuelle + membres manquants
    // ════════════════════════════════════════════════════════════════

    @Test
    public void initialize_annonceDéclencheursEtActionsDeCode() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        InitializeResult result = server.initialize(new InitializeParams()).get();
        assertNotNull(result.getCapabilities().getCompletionProvider());
        assertTrue("les déclencheurs incluent le point",
                result.getCapabilities().getCompletionProvider()
                        .getTriggerCharacters().contains("."));
        assertTrue("… la parenthèse ouvrante",
                result.getCapabilities().getCompletionProvider()
                        .getTriggerCharacters().contains("("));
        assertNotNull("la capability codeActionProvider est annoncée",
                result.getCapabilities().getCodeActionProvider());
    }

    @Test
    public void completion_sertLesConstantesDEnumPuisLaBase() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        String code = "class Atelier {\n"
                + "    enum Couleur { ROUGE, VERT, BLEU }\n"
                + "    void t() { Couleur.";
        didOpen(server, code);
        List<org.eclipse.lsp4j.CompletionItem> items = server.getTextDocumentService()
                .completion(paramsComplétion(2, 23)).get().getRight().getItems();
        // Les constantes d'enum d'abord, kind EnumMember.
        org.eclipse.lsp4j.CompletionItem rouge = chercher(items, "ROUGE");
        assertNotNull("la constante ROUGE est servie", rouge);
        assertEquals(org.eclipse.lsp4j.CompletionItemKind.EnumMember,
                rouge.getKind());
        // La base historique suit TOUJOURS (le banc de la résolution
        // différée reste observable en toute position).
        assertNotNull(chercher(items, "toString"));
        assertNotNull(chercher(items, "add"));
        assertNotNull(chercher(items, "addAll"));
        assertTrue("les constantes précèdent la base",
                items.indexOf(rouge) < items.indexOf(chercher(items, "add")));
    }

    @Test
    public void completion_sertLesAttributsDAnnotation() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        String code = "class Atelier {\n"
                + "    @interface Trace {\n"
                + "        String nom();\n"
                + "        boolean verbose() default false;\n"
                + "    }\n"
                + "    @Trace(";
        didOpen(server, code);
        List<org.eclipse.lsp4j.CompletionItem> items = server.getTextDocumentService()
                .completion(paramsComplétion(5, 11)).get().getRight().getItems();
        org.eclipse.lsp4j.CompletionItem nom = chercher(items, "nom");
        assertNotNull(nom);
        assertEquals("l'insertion pose nom=", "nom=", nom.getInsertText());
        assertNotNull(chercher(items, "verbose"));
    }

    @Test
    public void completion_sertLesArgumentsNommés() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        String code = "class Atelier {\n"
                + "    double calculerTotal(double base, double taux) {\n"
                + "        return base * taux;\n"
                + "    }\n"
                + "    void t() { calculerTotal(";
        didOpen(server, code);
        List<org.eclipse.lsp4j.CompletionItem> items = server.getTextDocumentService()
                .completion(paramsComplétion(4, 29)).get().getRight().getItems();
        org.eclipse.lsp4j.CompletionItem base = chercher(items, "base");
        assertNotNull("l'argument nommé base= est servi", base);
        assertEquals("base=", base.getInsertText());
        assertEquals("le champ data transporte le raffinement parameter",
                "parameter", base.getData());
        // Le paramètre servi suit la POSITION (le même moteur que
        // l'aide de signature) : à l'ouverture, seul le premier.
        assertTrue("taux n'est pas servi à la position 0",
                chercher(items, "taux") == null);
    }

    @Test
    public void finDeDocument_laBaseResteSeule() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        String code = "class A {\n    void f() {\n        g();\n    }\n}\n";
        didOpen(server, code);
        List<org.eclipse.lsp4j.CompletionItem> items = server.getTextDocumentService()
                .completion(paramsComplétion(4, 1)).get().getRight().getItems();
        // Hors contexte : la base fixe, RIEN de plus.
        assertEquals(4, items.size());
    }

    @Test
    public void didOpen_avecMembresManquants_pousseLeDiagnosticSémantique() {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        String code = "interface Véhicule {\n"
                + "    void démarrer(String clé);\n"
                + "}\n"
                + "class Voiture implements Véhicule {\n"
                + "}\n";
        didOpen(server, code);
        org.eclipse.lsp4j.Diagnostic diag = chercherCode(client.published.get(0)
                .getDiagnostics(), "unimplemented-members");
        assertNotNull("le diagnostic sémantique est publié", diag);
        assertEquals("il vit sur le nom de la classe incomplète",
                "Voiture", code.substring(
                        MiniJavaLanguageServer.positionVersOffset(code,
                                diag.getRange().getStart()),
                        MiniJavaLanguageServer.positionVersOffset(code,
                                diag.getRange().getEnd())));
        assertTrue(diag.getMessage().contains("démarrer"));
    }

    @Test
    public void codeAction_surLeNomDeClasse_sertLeQuickfixPréféré() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        String code = "interface Véhicule {\n"
                + "    void démarrer(String clé);\n"
                + "}\n"
                + "class Voiture implements Véhicule {\n"
                + "}\n";
        didOpen(server, code);
        // La plage demandée couvre la ligne du nom « Voiture ».
        org.eclipse.lsp4j.CodeActionParams params =
                new org.eclipse.lsp4j.CodeActionParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(URI));
        params.setRange(new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(3, 0),
                new org.eclipse.lsp4j.Position(3, 33)));
        List<org.eclipse.lsp4j.jsonrpc.messages.Either<
                org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> actions =
                server.getTextDocumentService().codeAction(params).get();
        assertEquals("une action : le quickfix", 1, actions.size());
        org.eclipse.lsp4j.CodeAction action = actions.get(0).getRight();
        assertNotNull(action);
        assertTrue(action.getTitle().contains("Implémenter les membres manquants"));
        assertEquals(org.eclipse.lsp4j.CodeActionKind.QuickFix, action.getKind());
        assertEquals("l'action est PRÉFÉRÉE (tête du menu client)",
                Boolean.TRUE, action.getIsPreferred());
        assertNotNull(action.getEdit());
        List<org.eclipse.lsp4j.TextEdit> edits = action.getEdit().getChanges().get(URI);
        assertEquals(1, edits.size());
        assertTrue("le stub porte @Override",
                edits.get(0).getNewText().contains("@Override"));
        assertTrue("… et la signature du membre manquant",
                edits.get(0).getNewText().contains("démarrer(String clé)"));
    }

    @Test
    public void codeAction_aprèsLeQuickfix_leDiagnosticDisparaît() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        String code = "interface Véhicule {\n"
                + "    void démarrer(String clé);\n"
                + "}\n"
                + "class Voiture implements Véhicule {\n"
                + "}\n";
        didOpen(server, code);
        org.eclipse.lsp4j.CodeActionParams params =
                new org.eclipse.lsp4j.CodeActionParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(URI));
        params.setRange(new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(3, 0),
                new org.eclipse.lsp4j.Position(3, 33)));
        org.eclipse.lsp4j.TextEdit edit = server.getTextDocumentService()
                .codeAction(params).get().get(0).getRight().getEdit()
                .getChanges().get(URI).get(0);
        // Le client appliquerait l'édition : on SIMULE en insérant le
        // newText à la position de l'édit puis didChange du texte réparé.
        int at = MiniJavaLanguageServer.positionVersOffset(code,
                edit.getRange().getStart());
        String réparé = code.substring(0, at) + edit.getNewText()
                + code.substring(at);
        didChange(server, réparé);
        assertTrue("le diagnostic sémantique disparaît",
                chercherCode(client.published.get(1).getDiagnostics(),
                        "unimplemented-members") == null);
        // Et le quickfix ne se propose plus.
        List<org.eclipse.lsp4j.jsonrpc.messages.Either<
                org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> vide =
                server.getTextDocumentService().codeAction(params).get();
        assertTrue(vide.isEmpty());
    }

    @Test
    public void codeAction_horsNomDeClasse_aucuneAction() throws Exception {
        RecordingClient client = new RecordingClient();
        MiniJavaLanguageServer server = serveurConnecté(client);
        String code = "interface Véhicule {\n"
                + "    void démarrer(String clé);\n"
                + "}\n"
                + "class Voiture implements Véhicule {\n"
                + "}\n";
        didOpen(server, code);
        org.eclipse.lsp4j.CodeActionParams params =
                new org.eclipse.lsp4j.CodeActionParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(URI));
        // La plage vise l'INTERFACE, pas la classe incomplète.
        params.setRange(new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(0, 0),
                new org.eclipse.lsp4j.Position(0, 20)));
        assertTrue(server.getTextDocumentService().codeAction(params).get()
                .isEmpty());
    }

    private static org.eclipse.lsp4j.CompletionParams paramsComplétion(
            int ligne, int caractère) {
        org.eclipse.lsp4j.CompletionParams params =
                new org.eclipse.lsp4j.CompletionParams();
        params.setTextDocument(new org.eclipse.lsp4j.TextDocumentIdentifier(URI));
        params.setPosition(new org.eclipse.lsp4j.Position(ligne, caractère));
        return params;
    }

    private static org.eclipse.lsp4j.CompletionItem chercher(
            List<org.eclipse.lsp4j.CompletionItem> items, String label) {
        for (org.eclipse.lsp4j.CompletionItem i : items) {
            if (label.equals(i.getLabel())) return i;
        }
        return null;
    }

    private static org.eclipse.lsp4j.Diagnostic chercherCode(
            List<org.eclipse.lsp4j.Diagnostic> diags, String code) {
        for (org.eclipse.lsp4j.Diagnostic d : diags) {
            if (code.equals(d.getCode().getLeft())) return d;
        }
        return null;
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
