package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.InitializeParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La carte des capacités clientes envoyée à initialize est COMPLÈTE sur
 * les fonctionnalités réellement consommées : les serveurs qui adaptent
 * leur comportement à la négociation (édition poussée, résolution
 * différée, actions à commande, tags de diagnostics, pré-renommage)
 * trouvent leur réponse au lieu de replier sur le minimum.
 */
class ClientCapabilitiesNegotiationTest {

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
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
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    private ClientCapabilities caps() {
        InitializeParams reçus = server.lastInitialize;
        assertNotNull(reçus);
        assertNotNull(reçus.getCapabilities());
        return reçus.getCapabilities();
    }

    @Test
    void workspace_applyEditEtExecuteCommandAnnoncés() {
        ClientCapabilities caps = caps();
        assertNotNull(caps.getWorkspace());
        assertEquals(Boolean.TRUE, caps.getWorkspace().getApplyEdit(),
                "les éditions poussées sont implémentées");
        assertNotNull(caps.getWorkspace().getExecuteCommand(),
                "les commandes sont exécutables");
        assertEquals(Boolean.TRUE, caps.getWorkspace().getWorkspaceFolders());
    }

    @Test
    void codeAction_littéralDataResolvePreferredDisabled() {
        org.eclipse.lsp4j.CodeActionCapabilities ca = caps().getTextDocument().getCodeAction();
        assertNotNull(ca);
        assertNotNull(ca.getCodeActionLiteralSupport(), "littéral supporté");
        assertNotNull(ca.getCodeActionLiteralSupport().getCodeActionKind().getValueSet());
        assertTrue(ca.getCodeActionLiteralSupport().getCodeActionKind().getValueSet()
                .contains("quickfix"));
        assertTrue(ca.getCodeActionLiteralSupport().getCodeActionKind().getValueSet()
                .contains("source.organizeImports"));
        assertEquals(Boolean.TRUE, ca.getDataSupport());
        assertNotNull(ca.getResolveSupport());
        assertEquals(java.util.List.of("edit"), ca.getResolveSupport().getProperties());
        assertEquals(Boolean.TRUE, ca.getIsPreferredSupport());
        assertEquals(Boolean.TRUE, ca.getDisabledSupport());
    }

    @Test
    void renommage_préparationAnnoncée() {
        org.eclipse.lsp4j.RenameCapabilities ren = caps().getTextDocument().getRename();
        assertNotNull(ren);
        assertEquals(Boolean.TRUE, ren.getPrepareSupport());
    }

    @Test
    void diagnostics_tagsCodeDescriptionDataAnnoncés() {
        org.eclipse.lsp4j.PublishDiagnosticsCapabilities pd =
                caps().getTextDocument().getPublishDiagnostics();
        assertNotNull(pd);
        assertEquals(Boolean.TRUE, pd.getRelatedInformation());
        assertNotNull(pd.getTagSupport());
        assertTrue(pd.getTagSupport().isRight()
                ? pd.getTagSupport().getRight().getValueSet()
                        .contains(org.eclipse.lsp4j.DiagnosticTag.Unnecessary)
                : Boolean.TRUE.equals(pd.getTagSupport().getLeft()));
        assertEquals(Boolean.TRUE, pd.getCodeDescriptionSupport());
        assertEquals(Boolean.TRUE, pd.getDataSupport());
    }

    @Test
    void complétionEtSignature_contexteAnnoncé() {
        assertEquals(Boolean.TRUE, caps().getTextDocument().getCompletion().getContextSupport());
        assertEquals(Boolean.TRUE, caps().getTextDocument().getSignatureHelp().getContextSupport());
    }

    @Test
    void plisEtIndications_tirésCôtéClientAnnoncés() {
        assertNotNull(caps().getTextDocument().getFoldingRange());
        assertNotNull(caps().getTextDocument().getInlayHint());
        assertEquals(Boolean.TRUE, caps().getTextDocument().getDocumentSymbol()
                .getHierarchicalDocumentSymbolSupport());
    }
}
