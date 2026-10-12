package jo.codeeditor.lsp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;

import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

/**
 * La publication LSP emprunte la voie UNIQUE de sortie (P2-11c) : après
 * {@code publishDiagnostics}, l'hôte est notifié
 * ({@code OnDiagnosticsPublishedListener} — resté muet côté LSP avant) et
 * la gouttière est reconstruite (les points de sévérité par ligne
 * restaient périmés) — le même chemin que la voie locale
 * {@code notifyDiagnosticsChanged()}.
 */
@RunWith(RobolectricTestRunner.class)
public class LspDiagnosticsHostNotificationTest {

    private static final String URI = "file:///proj/src/A.java";
    private static final String DOC = "public class A {\n    String s = foo;\n}\n";

    @Test
    public void publicationLsp_notifieLHôte() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("lsp-diag").toFile();
        LspProject project = new LspProject(racine.getAbsolutePath());
        LspEditor editor = new LspEditor(project, URI);

        android.content.Context app = RuntimeEnvironment.getApplication();
        jo.codeeditor.view.EditorView view = new jo.codeeditor.view.EditorView(app);
        jo.codeeditor.session.EditorSession session =
                new jo.codeeditor.session.EditorSession(
                        jo.codeeditor.document.EditorDocument.of(DOC));
        view.setSession(session);
        editor.setEditorView(view);

        final List<jo.codeeditor.shift.DiagnosticShift.Diagnostic> reçus =
                new CopyOnWriteArrayList<>();
        view.setOnDiagnosticsPublishedListener(reçus::addAll);

        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri(URI);
        org.eclipse.lsp4j.Diagnostic d = new org.eclipse.lsp4j.Diagnostic();
        d.setRange(new Range(new Position(1, 11), new Position(1, 14)));
        d.setMessage("foo cannot be resolved to a type");
        d.setSeverity(DiagnosticSeverity.Error);
        params.setDiagnostics(List.of(d));
        // Vue non attachée : pas de handler → la publication s'exécute en
        // ligne (pattern applyServerEdit — une publication serveur ne
        // reste jamais pendante).
        editor.publishDiagnostics(params);

        assertEquals("l'hôte est notifié de la publication LSP", 1, reçus.size());
        assertEquals("le diagnostic converti voyage (erreur=3)",
                3, reçus.get(0).severity);
        assertTrue(reçus.get(0).message.contains("cannot be resolved"));
    }

    @Test
    public void sansVue_laPublicationResteSilencieuse() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("lsp-diag2").toFile();
        LspProject project = new LspProject(racine.getAbsolutePath());
        LspEditor editor = new LspEditor(project, URI);

        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri(URI);
        params.setDiagnostics(List.of());
        // Sans vue branchée : pas d'exception, cache mis à jour.
        editor.publishDiagnostics(params);
        assertTrue(editor.getCachedDiagnostics().isEmpty());
        project.shutdown();
    }
}
