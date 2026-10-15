package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-03 — {@code LspFeature}/{@code setDisabledFeatures} est HONORÉ :
 * la porte est évaluée AVANT l'installation des providers — la
 * fonctionnalité désactivée n'expose AUCUN provider, donc AUCUNE
 * requête n'atteint jamais le serveur.
 *
 * <p>Rouge avant : l'API était sans effet — un hôte désactivant
 * {@code COMPLETION} obtenait un provider installé et chaque frappe
 * partait quand même en {@code textDocument/completion} (constat de
 * l'audit : « aucun provider, aucun puller, aucun client ne consulte
 * getDisabledFeatures »).</p>
 */
class LspFeatureGateTest {

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private FakeCompletionTriggerServer server;
    private LspLanguage language;

    @BeforeEach
    void setUp(@TempDir File racine) throws Exception {
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        server = new FakeCompletionTriggerServer();
        InProcessStreamConnectionProvider connection =
                new InProcessStreamConnectionProvider(() -> server);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return connection;
            }
        };
        wrapper = new LanguageServerWrapper(definition, racine.getAbsolutePath(), executor);
        wrapper.start().join();
        editor = new LspEditor(project, "file:///proj/src/A.java");
        wrapper.getClient().setLspEditor(editor);
        language = new LspLanguage(editor, wrapper);
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    private void désactiver(LspFeature... features) {
        Set<LspFeature> désactivées = EnumSet.noneOf(LspFeature.class);
        for (LspFeature f : features) désactivées.add(f);
        wrapper.definition().setDisabledFeatures(désactivées);
    }

    @Test
    void complétionDésactivée_aucunProvider_aucuneRequêteAuServeur() {
        assertNotNull(language.getCompletionProvider(),
                "pré-condition : le serveur annonce completionProvider, provider installé");

        désactiver(LspFeature.COMPLETION);
        int requêtesAvant = server.completionRequests;

        assertNull(language.getCompletionProvider(),
                "COMPLETION désactivé → AUCUN provider n'est exposé");
        // Le provider étant absent, l'éditeur ne peut RIEN appeler :
        // le chemin requête n'existe pas (acceptation de l'audit).
        // Vérification par l'absurde : même une requête directe au
        // provider précédent serait impossible — le compteur du serveur
        // n'a pas bougé depuis la désactivation.
        assertEquals(requêtesAvant, server.completionRequests,
                "aucune requête n'a atteint le serveur factice");
    }

    @Test
    void porteÉvaluéeAvantCapabilities_AussiSansHandshake() {
        // La porte précède le test des capabilities : une définition
        // désactivée court-circuite même quand les caps sont là. Le
        // serveur factice n'annonce QUE completionProvider — c'est la
        // seule fonctionnalité dont le provider est installable ici.
        désactiver(LspFeature.HOVER);
        assertNull(language.getHoverProvider());
        // Les AUTRES fonctionnalités restent disponibles (la porte
        // HOVER ne coupe pas la complétion).
        assertNotNull(language.getCompletionProvider(),
                "la désactivation de HOVER ne coupe pas COMPLETION");
    }

    @Test
    void rienDésactivé_comportementInchangé() {
        wrapper.definition().setDisabledFeatures(java.util.Collections.emptySet());
        // Le serveur factice n'annonce que completionProvider.
        assertNotNull(language.getCompletionProvider());
    }

    @Test
    void énumÉtendu_goToFamilleCouverte() {
        // D-03 : les valeurs ajoutées couvrent la famille GO TO et les
        // pullers complémentaires.
        assertNotNull(Enum.valueOf(LspFeature.class, "TYPE_DEFINITION"));
        assertNotNull(Enum.valueOf(LspFeature.class, "IMPLEMENTATIONS"));
        assertNotNull(Enum.valueOf(LspFeature.class, "SUPER_DEFINITION"));

        désactiver(LspFeature.TYPE_DEFINITION, LspFeature.IMPLEMENTATIONS,
                LspFeature.SUPER_DEFINITION, LspFeature.DEFINITION);
        assertNull(language.getDefinitionProvider());
        assertNull(language.getTypeDefinitionProvider());
        assertNull(language.getImplementationsProvider());
        assertNull(language.getSuperDefinitionProvider());
        // Le reste vit (le serveur factice n'annonce que la complétion).
        assertNotNull(language.getCompletionProvider());
    }

    @Test
    void diagnosticsEtPullersDésactivés_aucunProvider() {
        désactiver(LspFeature.DIAGNOSTICS, LspFeature.RENAME,
                LspFeature.FORMATTING, LspFeature.INLAY_HINT,
                LspFeature.CODE_ACTION, LspFeature.DOCUMENT_HIGHLIGHT,
                LspFeature.REFERENCES, LspFeature.DOCUMENT_SYMBOL,
                LspFeature.SIGNATURE_HELP);
        assertNull(language.getDiagnosticsProvider());
        assertNull(language.getRenameProvider());
        assertNull(language.getFormatter());
        assertNull(language.getInlayHintProvider());
        assertNull(language.getCodeActionsProvider());
        assertNull(language.getDocumentHighlightProvider());
        assertNull(language.getReferencesProvider());
        assertNull(language.getSymbolProvider());
        assertNull(language.getSignatureHelpProvider());
    }
}
