package jo.codeeditor.lsp;

import jo.codeeditor.lang.host.EditorHost;
import jo.codeeditor.session.EditorSession;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pont LspEditor ↔ EditorHost en JVM PURE (plan 03 — inversion de
 * dépendance) : aucun Robolectric, aucune vue Android, aucun serveur
 * démarré. C'est le gain de testabilité visé par le plan : le client
 * LSP se vérifie contre un hôte FACTICE.
 *
 * <p>Couvrent : les conversions offset↔Position sur le document de
 * l'hôte, la dégradation propre sans hôte lié, et la publication des
 * diagnostics avec les DEUX régimes de marshalling (hôte détaché →
 * exécution en ligne ; hôte attaché → passage par post()).</p>
 */
public class LspEditorHostBridgeTest {

    private static final String URI = "file:///proj/src/A.js";
    private static final String TEXTE = "let a = 1;\nlet bb = 2;\nlet ccc = 3;\n";

    /** Hôte factice : session réelle (cel-core, JVM pur) + trace des appels. */
    private static final class HoteFactice implements EditorHost {
        final EditorSession session;
        boolean attache = false;
        final List<String> journal = new ArrayList<>();
        final List<Runnable> enAttente = new ArrayList<>();

        HoteFactice(String texte) {
            session = new EditorSession(
                    jo.codeeditor.document.EditorDocument.of(texte));
        }

        @Override public EditorSession session() {
            journal.add("session");
            return session;
        }

        @Override public jo.codeeditor.document.EditorDocument document() {
            return session != null ? session.getDocument() : null;
        }

        @Override public void setLanguage(jo.codeeditor.lang.Language language) {
            journal.add("setLanguage");
        }

        @Override public void notifyDiagnosticsChanged() {
            journal.add("notifyDiagnosticsChanged");
        }

        @Override public void notifyTextChanged() {
            journal.add("notifyTextChanged");
        }

        @Override public void invalidate() {
            journal.add("invalidate");
        }

        @Override public boolean isAttachedToWindow() {
            return attache;
        }

        @Override public void post(Runnable task) {
            // Hôte attaché : la tâche partirait sur le thread UI — le
            // factice la CAPTURE (le test décide quand la « file UI »
            // s'exécute).
            enAttente.add(task);
        }

        @Override public void postDelayed(Runnable task, long delayMillis) {
            enAttente.add(task);
        }
    }

    private LspProject project;
    private LspEditor editor;

    @Before
    public void setUp() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("host-bridge").toFile();
        project = new LspProject(racine.getAbsolutePath());
        editor = project.createEditor(URI);
    }

    @After
    public void tearDown() {
        editor.disconnect();
        project.shutdown();
    }

    // ── Conversions offset ↔ Position ─────────────────────────────

    @Test
    public void conversionOffsetsPositions_allerRetour() {
        editor.setHost(new HoteFactice(TEXTE));
        // Début de la 3e ligne (après "let " → col 4) : les lignes font
        // 11 + 12 + 13 caractères ('\n' inclus).
        int ligne3 = 11 + 12;          // début de la 3e ligne
        int offset = ligne3 + 4;       // "let ccc = 3;" → caret après "let "
        Position pos = editor.offsetToPosition(offset);
        assertEquals(2, pos.getLine());
        assertEquals(4, pos.getCharacter());
        assertEquals(offset, editor.positionToOffset(pos));
        assertEquals(TEXTE.length(), editor.documentLength());
    }

    @Test
    public void hôteNonLié_conversionsDégradées() {
        // Aucun hôte posé : dégradation docile, jamais d'exception.
        assertEquals(new Position(0, 0), editor.offsetToPosition(42));
        assertEquals(0, editor.positionToOffset(new Position(3, 7)));
        assertEquals(-1, editor.documentLength());
        assertNull(editor.getHost());
    }

    @Test
    public void hôteLié_documentExposé() {
        HoteFactice host = new HoteFactice(TEXTE);
        editor.setHost(host);
        assertNotNull(editor.getHost());
        assertEquals(TEXTE.length(), editor.getHost().document().length());
    }

    // ── Publication des diagnostics (via LspDiagnosticsManager) ────

    @Test
    public void publicationDiagnostics_hôteDétaché_pousséeEnLigne() {
        HoteFactice host = new HoteFactice(TEXTE);
        editor.setHost(host);   // détaché par défaut

        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri(URI);
        List<org.eclipse.lsp4j.Diagnostic> diags = new ArrayList<>();
        diags.add(diagnostic(0, 4, 0, 11, "variable non utilisée"));
        params.setDiagnostics(diags);

        editor.publishDiagnostics(params);

        // Hôte détaché → exécution EN LIGNE : la session porte déjà les
        // diagnostics et la notification unifiée est passée.
        assertTrue("la notification unifiée doit être émise en ligne",
                host.journal.contains("notifyDiagnosticsChanged"));
        Object poussés = host.session.getDiagnostics();
        assertNotNull(poussés);
        assertTrue(host.enAttente.isEmpty());
    }

    @Test
    public void publicationDiagnostics_hôteAttaché_pousséeParPost() {
        HoteFactice host = new HoteFactice(TEXTE);
        host.attache = true;
        editor.setHost(host);

        PublishDiagnosticsParams params = new PublishDiagnosticsParams();
        params.setUri(URI);
        List<org.eclipse.lsp4j.Diagnostic> diags = new ArrayList<>();
        diags.add(diagnostic(0, 0, 0, 3, "a est déclaré mais jamais lu"));
        params.setDiagnostics(diags);

        editor.publishDiagnostics(params);

        // Hôte attaché → la poussée part sur la « file UI » : rien n'est
        // encore appliqué tant que la file n'a pas tourné.
        assertEquals(1, host.enAttente.size());
        assertTrue("pas de notification avant exécution de la file",
                !host.journal.contains("notifyDiagnosticsChanged"));

        host.enAttente.get(0).run();
        assertTrue(host.journal.contains("notifyDiagnosticsChanged"));
        assertNotNull(host.session.getDiagnostics());
    }

    private static org.eclipse.lsp4j.Diagnostic diagnostic(
            int l1, int c1, int l2, int c2, String message) {
        return new org.eclipse.lsp4j.Diagnostic(
                new Range(new Position(l1, c1), new Position(l2, c2)),
                message, DiagnosticSeverity.Warning, "test");
    }
}
