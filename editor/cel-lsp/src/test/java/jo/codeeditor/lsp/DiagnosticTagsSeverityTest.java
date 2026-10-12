package jo.codeeditor.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
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
 * La conversion fidèle des diagnostics publiés : la sévérité ABSENTE
 * devient Information (pas Warning — la spec 3.17 laisse le choix au
 * client, un diagnostic sans sévérité n'a pas prouvé son urgence) et le
 * tag LSP {@code Unnecessary} (code inutilisé) est transporté jusqu'à la
 * vue pour la peinture estompée de la plage (grisé Android Studio des
 * imports morts) au lieu du soulignement de sévérité.
 */
class DiagnosticTagsSeverityTest {

    private static final String URI = "file:///proj/src/A.java";

    private ExecutorService executor;
    private LspProject project;
    private LspEditor editor;

    @BeforeEach
    void setUp(@TempDir File racine) throws Exception {
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        // L'éditeur sans serveur suffit : publishDiagnostics est le point
        // d'entrée public testé (le client LSP l'appelle à la réception).
        editor = new LspEditor(project, URI);
    }

    @AfterEach
    void tearDown() {
        project.shutdown();
        executor.shutdownNow();
    }

    private static org.eclipse.lsp4j.Diagnostic lspDiag(
            DiagnosticSeverity severity, DiagnosticTag tag) {
        org.eclipse.lsp4j.Diagnostic d = new org.eclipse.lsp4j.Diagnostic();
        d.setRange(new Range(new Position(1, 4), new Position(1, 7)));
        d.setMessage("foo is unused");
        if (severity != null) d.setSeverity(severity);
        if (tag != null) d.setTags(List.of(tag));
        return d;
    }

    private void publie(org.eclipse.lsp4j.Diagnostic... diags) {
        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri(URI);
        params.setDiagnostics(List.of(diags));
        editor.publishDiagnostics(params);
    }

    @Test
    void sévéritéAbsente_devientInformation() {
        publie(lspDiag(null, null));
        List<jo.codeeditor.lang.model.Diagnostic> diags = editor.getCachedDiagnostics();
        assertEquals(1, diags.size());
        assertEquals(1, diags.get(0).severity,
                "sévérité absente → Information (1), pas Avertissement (2)");
    }

    @Test
    void sévéritéPrésente_estConservee() {
        publie(lspDiag(DiagnosticSeverity.Warning, null));
        List<jo.codeeditor.lang.model.Diagnostic> diags = editor.getCachedDiagnostics();
        assertEquals(2, diags.get(0).severity, "Warning LSP → avertissement (2)");
        publie(lspDiag(DiagnosticSeverity.Error, null));
        assertEquals(3, editor.getCachedDiagnostics().get(0).severity,
                "Error LSP → erreur (3)");
    }

    @Test
    void tagUnnecessary_transporté() {
        publie(lspDiag(DiagnosticSeverity.Warning, DiagnosticTag.Unnecessary));
        List<jo.codeeditor.lang.model.Diagnostic> diags = editor.getCachedDiagnostics();
        assertTrue(diags.get(0).unnecessary,
                "le tag Unnecessary voyage jusqu'à la vue (peinture estompée)");
    }

    @Test
    void sansTag_unnecessaryFaux() {
        publie(lspDiag(DiagnosticSeverity.Hint, null));
        List<jo.codeeditor.lang.model.Diagnostic> diags = editor.getCachedDiagnostics();
        assertFalse(diags.get(0).unnecessary, "sans tag → pas de peinture estompée");
    }
}
