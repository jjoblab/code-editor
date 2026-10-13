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
 * I3 (P2, lot 2 jalon J6) — les <b>labelParts des inlay hints</b> LSP
 * sont conservées : un libellé structuré
 * ({@code label: InlayHintLabelPart[]}) traverse le pont SPI comme une
 * liste de parts (texte + tooltip propre), l'assemblage restant le
 * texte affiché au rendu.
 *
 * <ul>
 *   <li><b>Parts conservées</b> — chaque part porte son texte et SON
 *       tooltip (consultable par l'appui long I2 sur sa sous-plage) ;</li>
 *   <li><b>Padding = parts de blanc</b> — l'invariant
 *       {@code concat(parts.text) == text} tient AUSSI quand le serveur
 *       demande du padding left/right : le blanc est une part sans
 *       tooltip ;</li>
 *   <li><b>Libellé plat inchangé</b> — un label String reste un hint
 *       sans parts (null), zéro changement de comportement.</li>
 * </ul>
 */
class InlayLabelPartsTest {

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
        server.multiPartLabels = true;
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

    @Test
    void partsConservées_avecTooltipPropre() {
        List<InlayHint> hints = new LspInlayHintProvider(wrapper, editor, null)
                .inlayHints(DOC, 0, 2);
        assertEquals(2, hints.size());
        InlayHint multi = hints.get(1);
        // Hint #1 : libellé plat, PAS de parts (zéro rupture).
        assertNull(hints.get(0).parts);
        // Hint #2 : 4 parts = blanc de padding + "name" + ": str" + blanc.
        assertNotNull(multi.parts);
        assertEquals(4, multi.parts.size());
        assertEquals(" ", multi.parts.get(0).text);
        assertNull(multi.parts.get(0).tooltip,
                "le blanc de padding n'a pas de tooltip");
        assertEquals("name", multi.parts.get(1).text);
        assertEquals("Le nom du paramètre", multi.parts.get(1).tooltip);
        assertEquals(": str", multi.parts.get(2).text);
        assertEquals("Le type inféré du paramètre", multi.parts.get(2).tooltip);
        assertEquals(" ", multi.parts.get(3).text);
        assertNull(multi.parts.get(3).tooltip);
    }

    @Test
    void invariant_concatDesParts_égaleLeTexteTissé() {
        List<InlayHint> hints = new LspInlayHintProvider(wrapper, editor, null)
                .inlayHints(DOC, 0, 2);
        InlayHint multi = hints.get(1);
        assertNotNull(multi.parts);
        StringBuilder concat = new StringBuilder();
        for (InlayHint.Part part : multi.parts) concat.append(part.text);
        assertEquals(multi.text, concat.toString(),
                "l'assemblage des parts EST le texte affiché (padding compris)");
        assertEquals(" name: str ", multi.text);
    }

    @Test
    void commandDePart_devientRunnableExécutéAuTap() throws Exception {
        server.commandOnPart = true;
        List<InlayHint> hints = new LspInlayHintProvider(wrapper, editor, null)
                .inlayHints(DOC, 0, 2);
        InlayHint multi = hints.get(1);
        assertNotNull(multi.command, "la command de la part traverse le SPI");

        // AUCUNE requête à la conversion (le Runnable est paresseux).
        assertEquals(0, server.executeCommandRequests,
                "aucune requête à la conversion (le Runnable est paresseux)");

        // Le tap exécute le Runnable → workspace/executeCommand (ASYNCHRONE).
        multi.command.run();
        long fin = System.currentTimeMillis() + 5000;
        while (server.executeCommandRequests < 1
                && System.currentTimeMillis() < fin) {
            Thread.sleep(20);
        }
        assertEquals(1, server.executeCommandRequests,
                "exactement UNE requête executeCommand au tap");
        assertEquals("demo.expandHint", server.lastExecuteCommand,
                "la command LSP est transportée telle quelle");
    }

    @Test
    void sansCommandDePart_hintSansCommand() {
        server.commandOnPart = false;
        List<InlayHint> hints = new LspInlayHintProvider(wrapper, editor, null)
                .inlayHints(DOC, 0, 2);
        assertNull(hints.get(1).command,
                "pas de command sur les parts → hint passif");
        assertNull(hints.get(0).command, "hint plat : jamais de command");
    }

    @Test
    void libelléPlat_hintSansParts() {
        server.multiPartLabels = false;
        List<InlayHint> hints = new LspInlayHintProvider(wrapper, editor, null)
                .inlayHints(DOC, 0, 2);
        InlayHint plat = hints.get(1);
        assertNull(plat.parts, "un libellé String ne crée PAS de parts");
        assertEquals("name: ", plat.text);
    }
}
