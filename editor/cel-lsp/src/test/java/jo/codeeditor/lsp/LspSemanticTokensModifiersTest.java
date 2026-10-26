package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;

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
 * Transport des modificateurs sémantiques (4.4.0) : le 5ᵉ élément du
 * quintuplet LSP — le bitmask de modificateurs — est résolu PAR NOM via
 * la légende {@code tokenModifiers} annoncée à l'initialisation, vers le
 * masque canonique de {@code DiagnosticShift.SemanticToken}.
 *
 * <ul>
 *   <li><b>Résolution par nom</b> — l'ordre de la légende est propre à
 *       chaque serveur : un bit ne signifie rien sans elle ; une légende
 *       réordonnée produit le MÊME masque canonique ;</li>
 *   <li><b>Modificateurs combinés</b> — abstrait + déprécié voyagent
 *       ensemble ;</li>
 *   <li><b>Sans légende</b> — aucun modificateur interprété (masque 0,
 *       le bitmask brut n'est JAMAIS deviné) ;</li>
 *   <li><b>Annonce cliente</b> — la capacité
 *       {@code textDocument.semanticTokens} fait partie de
 *       l'initialisation.</li>
 * </ul>
 */
class LspSemanticTokensModifiersTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    static int total = 1;\n}\n";

    /** Position (ligne 1, col 15), longueur 5 : « total ». */
    private static List<Integer> quintupletTotal(int rawModifiers) {
        return List.of(1, 15, 5, 6, rawModifiers);
    }

    private ExecutorService executor;
    private LspProject project;
    private LspEditor editor;
    private FakeSemanticTokensServer server;
    private LanguageServerWrapper wrapper;
    private HoteSémantique host;
    private File racine;

    @BeforeEach
    void setUp(@TempDir File racine) {
        this.racine = racine;
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        server = new FakeSemanticTokensServer();
    }

    /**
     * Connecte l'éditeur au faux serveur — APRÈS que le test a réglé la
     * légende : les capacités (et la légende qu'elles portent) sont
     * capturées à l'initialisation, jamais relues ensuite.
     */
    private void connecter() throws Exception {
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> server);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "factice") {
            @Override
            public jo.codeeditor.lsp.connection.StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        project.addServerDefinition(definition);
        editor = project.createEditor(URI);
        host = new HoteSémantique(DOC);
        editor.setHost(host);
        editor.connect().get(10, TimeUnit.SECONDS);
        wrapper = editor.currentWrapper();
        assertNotNull(wrapper, "précondition : l'éditeur est connecté au wrapper");
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

    /** Attend que les jetons sémantiques atteignent la session. */
    private void attendreJetons() throws InterruptedException {
        long fin = System.currentTimeMillis() + 5000;
        while (host.session.getSemanticTokens().isEmpty()) {
            if (System.currentTimeMillis() > fin) {
                fail("les jetons sémantiques n'arrivent jamais (requêtes serveur : "
                        + server.semanticTokensRequests + ")");
            }
            Thread.sleep(20);
        }
    }

    private void publierEtAttendre() throws InterruptedException {
        org.eclipse.lsp4j.PublishDiagnosticsParams params =
                new org.eclipse.lsp4j.PublishDiagnosticsParams();
        params.setUri(URI);
        params.setDiagnostics(List.of());
        // La publication de diagnostics est le signal naturel du tir
        // sémantique (R3) — le puller décode puis alimente la session.
        editor.publishDiagnostics(params);
        attendreJetons();
    }

    @Test
    void résolutionParNom_légendeRéordonnée_mêmeMasqueCanonique() throws Exception {
        // Légende volontairement NON canonique : « deprecated » au bit 1,
        // « static » au bit 2 (et non 0/1/2 comme le masque canonique).
        server.legendModifiers = List.of("readonly", "deprecated", "static", "abstract");
        server.data = quintupletTotal(0b110);    // bits 1 + 2
        connecter();
        publierEtAttendre();
        jo.codeeditor.shift.DiagnosticShift.SemanticToken t =
                host.session.getSemanticTokens().get(0);
        assertEquals(jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_DEPRECATED
                        | jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_STATIC,
                t.modifiers,
                "les bits sont résolus par NOM, pas par position");
    }

    @Test
    void modificateursCombinés_abstraitEtDéprécié() throws Exception {
        server.legendModifiers = List.of("abstract", "static", "deprecated");
        server.data = quintupletTotal(0b101);    // bits 0 + 2
        connecter();
        publierEtAttendre();
        jo.codeeditor.shift.DiagnosticShift.SemanticToken t =
                host.session.getSemanticTokens().get(0);
        assertEquals(jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_ABSTRACT
                        | jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_DEPRECATED,
                t.modifiers);
    }

    @Test
    void sansLégende_aucunModificateurInterprété() throws Exception {
        server.announceLegend = false;
        server.data = quintupletTotal(0b111);
        connecter();
        publierEtAttendre();
        jo.codeeditor.shift.DiagnosticShift.SemanticToken t =
                host.session.getSemanticTokens().get(0);
        assertEquals(0, t.modifiers,
                "sans légende, le bitmask brut n'est JAMAIS deviné");
    }

    @Test
    void résolutionDirecte_nomsInconnusEtMasque() {
        assertEquals(0, LspSemanticTokensPuller.canonicalBitForName(null));
        assertEquals(0, LspSemanticTokensPuller.canonicalBitForName("declaration"));
        assertEquals(jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_DEPRECATED,
                LspSemanticTokensPuller.canonicalBitForName("Deprecated"));
        // Masque : bits au-delà de la légende ignorés, table vide → 0.
        int[] table = {jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_DEPRECATED,
                0, jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_ABSTRACT};
        assertEquals(jo.codeeditor.shift.DiagnosticShift.SemanticToken.MOD_DEPRECATED,
                LspSemanticTokensPuller.canonicalMask(table, 0b001));
        assertEquals(0, LspSemanticTokensPuller.canonicalMask(new int[0], 0b111));
        assertEquals(0, LspSemanticTokensPuller.canonicalMask(table, 0));
    }

    /** Hôte factice minimal (session réelle, jamais attaché). */
    private static final class HoteSémantique implements jo.codeeditor.lang.host.EditorHost {
        final jo.codeeditor.session.EditorSession session;

        HoteSémantique(String texte) {
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
        @Override public void post(Runnable task) { task.run(); }
        @Override public void postDelayed(Runnable task, long d) { task.run(); }
    }
}
