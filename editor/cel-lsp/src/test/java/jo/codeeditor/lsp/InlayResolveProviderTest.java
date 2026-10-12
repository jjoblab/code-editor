package jo.codeeditor.lsp;

import jo.codeeditor.lang.model.InlayHint;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * I2 (lot 2, J3) — la résolution paresseuse des inlay hints
 * ({@code textDocument/inlayHint/resolve}) : le tooltip est résolu AU
 * PREMIER appui long (appel de {@code resolveHint}), jamais au rendu, et
 * uniquement si le serveur annonce {@code resolveProvider}.
 *
 * <ul>
 *   <li><b>Tooltip direct</b> — un tooltip servi par
 *       {@code textDocument/inlayHint} est transporté tel quel (zéro
 *       requête de résolution) ;</li>
 *   <li><b>Avec annonce</b> — {@code resolveHint} émet UNE requête
 *       {@code inlayHint/resolve} et rapporte le tooltip résolu ;</li>
 *   <li><b>Sans annonce</b> — AUCUNE requête n'est émise (le hint est
 *       retourné inchangé, tooltip null) ;</li>
 *   <li><b>Hint périmé</b> — un hint inconnu du dernier lot servi
 *       (reconstruit par l'appelant après une édition) est ignoré sans
 *       requête.</li>
 * </ul>
 */
class InlayResolveProviderTest {

    private static final String DOC = "public class A {\n    var s = greet(name);\n}\n";

    private ExecutorService executor;
    private LspProject project;
    private LspEditor editor;
    private FakeInlayResolveServer server;
    private LanguageServerWrapper wrapper;
    private File racine;

    @BeforeEach
    void setUp(@TempDir File racine) throws Exception {
        this.racine = racine;
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        server = new FakeInlayResolveServer();
        wrapper = startWrapper();
        editor = new LspEditor(project, "file:///proj/src/A.java");
        wrapper.getClient().setLspEditor(editor);
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    private LanguageServerWrapper startWrapper() throws Exception {
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> server);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        LanguageServerWrapper w = new LanguageServerWrapper(
                definition, racine.getAbsolutePath(), executor);
        w.start().join();
        return w;
    }

    /** Rebranche un wrapper sur le MÊME serveur avec resolveProvider annoncé. */
    private void rebrancherAvecAnnonce() throws Exception {
        wrapper.shutdown();
        server.announceResolve = true;
        wrapper = startWrapper();
    }

    @Test
    void tooltipDirect_transportéSansRésolution() {
        List<InlayHint> hints = new LspInlayHintProvider(wrapper, editor, null)
                .inlayHints(DOC, 0, 2);
        assertEquals(2, hints.size());
        assertEquals(1, server.inlayHintRequests);
        assertEquals(0, server.resolveRequests,
                "un tooltip direct ne doit déclencher AUCUNE résolution");
        InlayHint direct = hints.get(0);
        assertEquals(": String", direct.text);
        assertEquals("type", direct.kind);
        assertEquals("Le type inféré de la variable", direct.tooltip);
    }

    @Test
    void sansAnnonce_aucuneRequête_hintInchangé() {
        List<InlayHint> hints = new LspInlayHintProvider(wrapper, editor, null)
                .inlayHints(DOC, 0, 2);
        InlayHint lazy = hints.get(1);
        assertNull(lazy.tooltip);

        // L'appelant reconstruit le hint (le SPI interne ne garde que
        // offset+texte) : sans annonce, resolveHint est un no-op total.
        InlayHint rebuilt = new InlayHint(lazy.offset, lazy.text, lazy.kind);
        InlayHint resolved = new LspInlayHintProvider(wrapper, editor, null)
                .resolveHint(DOC, rebuilt);
        assertEquals(0, server.resolveRequests,
                "sans resolveProvider annoncé, aucune requête n'est émise");
        assertNull(resolved.tooltip);
        assertEquals(lazy.text, resolved.text);
    }

    @Test
    void avecAnnonce_résolutionAuPremierAppel_seulement() throws Exception {
        rebrancherAvecAnnonce();
        LspInlayHintProvider provider = new LspInlayHintProvider(wrapper, editor, null);
        List<InlayHint> hints = provider.inlayHints(DOC, 0, 2);
        InlayHint lazy = hints.get(1);
        assertNull(lazy.tooltip);
        assertEquals(0, server.resolveRequests,
                "le rendu ne résout JAMAIS — la requête part au premier tap");

        InlayHint rebuilt = new InlayHint(lazy.offset, lazy.text, lazy.kind);
        InlayHint resolved = provider.resolveHint(DOC, rebuilt);
        assertEquals(1, server.resolveRequests,
                "exactement UNE requête inlayHint/resolve au premier appel");
        assertEquals("java.lang.String — le type de la variable", resolved.tooltip);
        assertEquals(lazy.text, resolved.text);
        assertEquals(lazy.kind, resolved.kind);
    }

    @Test
    void avecAnnonce_hintPérimé_ignoréSansRequête() throws Exception {
        rebrancherAvecAnnonce();
        LspInlayHintProvider provider = new LspInlayHintProvider(wrapper, editor, null);
        provider.inlayHints(DOC, 0, 2);

        // Hint reconstruit sur un offset inconnu du dernier lot servi.
        InlayHint stale = new InlayHint(999, "phantom: ", "parameter");
        InlayHint resolved = provider.resolveHint(DOC, stale);
        assertEquals(0, server.resolveRequests,
                "un hint périmé n'émet aucune requête (retourné inchangé)");
        assertNull(resolved.tooltip);
    }

    @Test
    void avecAnnonce_serveurSansTooltip_hintInchangé() throws Exception {
        rebrancherAvecAnnonce();
        server.resolveTooltip = null;
        LspInlayHintProvider provider = new LspInlayHintProvider(wrapper, editor, null);
        List<InlayHint> hints = provider.inlayHints(DOC, 0, 2);
        InlayHint lazy = hints.get(1);
        InlayHint rebuilt = new InlayHint(lazy.offset, lazy.text, lazy.kind);
        InlayHint resolved = provider.resolveHint(DOC, rebuilt);
        assertEquals(1, server.resolveRequests,
                "la requête part — c'est le serveur qui n'a rien à dire");
        assertNull(resolved.tooltip);
        assertEquals(lazy.text, resolved.text);
    }
}
