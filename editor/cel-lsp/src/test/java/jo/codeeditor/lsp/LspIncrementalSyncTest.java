package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * P-02 (audit 3.69.0) — sync incrémentale PILOTÉE PAR LES ÉDITIONS SUIVIES
 * de la session.
 *
 * <p>Rouge avant : le synchroniseur recalculait un diff préfixe/suffixe sur
 * les textes entiers puis RECONSTRUISAIT le document en trois concaténations
 * pour vérifier son propre diff — trois copies O(n) par envoi, plus une
 * copie complète du document à CHAQUE frappe côté pont (getText() dans le
 * listener). Le nouveau chemin émet les événements ENREGISTRÉS au moment de
 * chaque édition, avec leurs plages exactes ; ce contrat est ce que ces
 * tests figent.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LspIncrementalSyncTest {

    private static final String URI = "file:///proj/src/A.java";
    // Lignes : L0 "public class A {" · L1 "    int x = 1;" · L2 "}"
    // Offsets : L0 = 0-15, \n 16 · L1 = 17-30, \n 31 · L2 = 32, \n 33.
    private static final String DOC = "public class A {\n    int x = 1;\n}\n";

    private ExecutorService executor;
    private LspProject project;
    private LspEditor editor;
    private FakeSyncServer server;
    private jo.codeeditor.view.EditorView view;
    private jo.codeeditor.session.EditorSession session;

    /** Serveur factice à sync incrémentale annoncée (ou non), qui enregistre tout. */
    static final class FakeSyncServer implements LanguageServer {
        final boolean announceIncremental;
        String didOpenText;
        final List<List<TextDocumentContentChangeEvent>> batches = new ArrayList<>();
        int didChangeCount;

        FakeSyncServer(boolean announceIncremental) {
            this.announceIncremental = announceIncremental;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            ServerCapabilities caps = new ServerCapabilities();
            if (announceIncremental) {
                caps.setTextDocumentSync(TextDocumentSyncKind.Incremental);
            }
            return CompletableFuture.completedFuture(new InitializeResult(caps));
        }

        @Override
        public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void exit() {}

        @Override
        public TextDocumentService getTextDocumentService() {
            return new TextDocumentService() {
                @Override
                public void didOpen(DidOpenTextDocumentParams params) {
                    didOpenText = params.getTextDocument().getText();
                }

                @Override
                public void didChange(DidChangeTextDocumentParams params) {
                    batches.add(params.getContentChanges());
                    didChangeCount++;
                }

                @Override
                public void didClose(DidCloseTextDocumentParams params) {}

                @Override
                public void didSave(DidSaveTextDocumentParams params) {}
            };
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return new WorkspaceService() {
                @Override
                public void didChangeConfiguration(
                        org.eclipse.lsp4j.DidChangeConfigurationParams params) {}

                @Override
                public void didChangeWatchedFiles(
                        org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}
            };
        }
    }

    @Before
    public void setUp() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("p02").toFile();
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        server = new FakeSyncServer(true);
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> server);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        project.addServerDefinition(definition);
        view = new jo.codeeditor.view.EditorView(RuntimeEnvironment.getApplication());
        session = new jo.codeeditor.session.EditorSession(
                jo.codeeditor.document.EditorDocument.of(DOC));
        view.setSession(session);
        editor = project.createEditor(URI);
        editor.setHost(new jo.codeeditor.view.LspEditorViewHost(view));
        editor.connect().get(10, java.util.concurrent.TimeUnit.SECONDS);
        // Exécute le applyLanguage posté sur le looper principal (pose du
        // listener d'éditions suivies).
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    @After
    public void tearDown() {
        editor.disconnect();
        project.shutdown();
        executor.shutdownNow();
    }

    /**
     * Trois éditions suivies (insertion en fin, remplacement in-line,
     * insertion de saut de ligne) → UN didChange portant TROIS événements
     * séquentiels aux plages exactes ; la reconstruction côté serveur
     * (application des plages dans l'ordre) redonne le texte final.
     */
    @Test
    public void éditionsSuivies_événementsSéquentielsPlagesExactes() {
        jo.codeeditor.AttenteBornee.jusquA(5000, 10L, () -> server.didOpenText != null);
        assertEquals(DOC, server.didOpenText);

        // a) insertion " // fin" juste avant le \n final (ligne 2, col 1).
        session.replaceRange(33, 33, " // fin");
        // b) remplacement "int" (ligne 1, cols 4-7) par "long".
        session.replaceRange(21, 24, "long");
        // c) insertion d'un \n en tête de ligne 1.
        session.replaceRange(17, 17, "\n");

        editor.flushPendingChange();
        jo.codeeditor.AttenteBornee.jusquA(5000, 10L, () -> server.didChangeCount >= 1);

        assertEquals(1, server.didChangeCount);
        List<TextDocumentContentChangeEvent> events = server.batches.get(0);
        assertEquals(3, events.size());

        // Plages exactes, dans l'ordre d'application.
        assertRange(events.get(0), 2, 1, 2, 1);
        assertEquals(" // fin", events.get(0).getText());
        assertRange(events.get(1), 1, 4, 1, 7);
        assertEquals("long", events.get(1).getText());
        assertRange(events.get(2), 1, 0, 1, 0);
        assertEquals("\n", events.get(2).getText());

        // Reconstruction côté serveur = texte final de la session.
        assertEquals(session.getText().toString(),
                applyEvents(server.didOpenText, events));
    }

    /**
     * Un remplacement multi-lignes (suppression du \n de fin de ligne 1
     * jusqu'au milieu de la ligne 2) : les bornes de FIN de plage sont des
     * positions PRÉ-édition — pas les positions du document déjà muté.
     */
    @Test
    public void suppressionMultiLignes_bornesPréÉdition() {
        jo.codeeditor.AttenteBornee.jusquA(5000, 10L, () -> server.didOpenText != null);
        // Supprime de la fin de L1 à la fin de L2 : [27, 33) = "= 1;\n}" remplacé par "!".
        session.replaceRange(27, 33, "!");
        editor.flushPendingChange();
        jo.codeeditor.AttenteBornee.jusquA(5000, 10L, () -> server.didChangeCount >= 1);

        List<TextDocumentContentChangeEvent> events = server.batches.get(0);
        assertEquals(1, events.size());
        // Début : offset 27 → ligne 1, col 27-17 = 10.
        // Fin : offset 33 → ligne 2, col 33-32 = 1 (PRÉ-état : « = 1;\n} »
        // contient un saut de ligne → fin ligne 2, col 1).
        assertRange(events.get(0), 1, 10, 2, 1);
        assertEquals("!", events.get(0).getText());
        assertEquals(session.getText().toString(),
                applyEvents(server.didOpenText, events));
    }

    /**
     * L'undo remonte par le même chemin : l'événement inverse porte la
     * plage et le texte exacts — le serveur reconstruit le texte d'origine.
     */
    @Test
    public void undo_événementInverseExact() {
        jo.codeeditor.AttenteBornee.jusquA(5000, 10L, () -> server.didOpenText != null);
        session.replaceRange(21, 24, "long");
        editor.flushPendingChange();
        jo.codeeditor.AttenteBornee.jusquA(5000, 10L, () -> server.didChangeCount >= 1);
        assertEquals(1, server.didChangeCount);

        assertTrue(session.undo());
        editor.flushPendingChange();
        jo.codeeditor.AttenteBornee.jusquA(5000, 10L, () -> server.didChangeCount >= 2);

        List<TextDocumentContentChangeEvent> undoEvents = server.batches.get(1);
        assertEquals(1, undoEvents.size());
        // Inverse : remplacer [21, 25) (post-insertion, ligne 1 cols 4-8)
        // par "int".
        assertRange(undoEvents.get(0), 1, 4, 1, 8);
        assertEquals("int", undoEvents.get(0).getText());

        // Reconstruction enchaînée : didOpen → edit → undo = DOC.
        String state = applyEvents(server.didOpenText, server.batches.get(0));
        assertEquals(DOC, applyEvents(state, undoEvents));
        assertEquals(DOC, session.getText().toString());
    }

    /**
     * Serveur SANS sync incrémentale annoncée → repli plein texte (un seul
     * événement sans plage), universellement accepté.
     */
    @Test
    public void serveurSansIncremental_repliPleinTexte() throws Exception {
        // Second serveur sans TextDocumentSync — extension DISTINCTE (kt) :
        // getWrapper("java") réutiliserait le wrapper déjà démarré.
        FakeSyncServer plein = new FakeSyncServer(false);
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> plein);
        LanguageServerDefinition definition = new LanguageServerDefinition("kt", "plein") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        project.addServerDefinition(definition);
        jo.codeeditor.view.EditorView vue2 =
                new jo.codeeditor.view.EditorView(RuntimeEnvironment.getApplication());
        vue2.setSession(session);
        // Vue détachée → applyLanguage s'exécute en ligne (correctif dédié).
        LspEditor editeur2 = project.createEditor("file:///proj/src/A.kt");
        editeur2.setHost(new jo.codeeditor.view.LspEditorViewHost(vue2));
        editeur2.connect().get(10, java.util.concurrent.TimeUnit.SECONDS);
        try {
            jo.codeeditor.AttenteBornee.jusquA(5000, 10L, () -> plein.didOpenText != null);
            assertEquals(DOC, plein.didOpenText);
            session.replaceRange(21, 24, "long");
            editeur2.flushPendingChange();
            jo.codeeditor.AttenteBornee.jusquA(5000, 10L, () -> plein.didChangeCount >= 1);
            List<TextDocumentContentChangeEvent> events = plein.batches.get(0);
            assertEquals(1, events.size());
            assertNull("pas de plage en mode plein texte", events.get(0).getRange());
            assertEquals(session.getText().toString(), events.get(0).getText());
        } finally {
            editeur2.disconnect();
        }
    }

    /**
     * Invariant de cohérence (le remplaçant O(1) de l'ancienne
     * reconstruction de vérification) : consécutivité des révisions et
     * longueur finale attendue. Test unitaire direct.
     */
    @Test
    public void cohérence_salves() {
        LspDocumentSynchronizer.TrackedEdit e1 =
                new LspDocumentSynchronizer.TrackedEdit(0, 0, 0, 0, "ab", 2, 1);
        LspDocumentSynchronizer.TrackedEdit e2 =
                new LspDocumentSynchronizer.TrackedEdit(0, 2, 0, 3, "x", -1, 2);
        // Chaîne continue 0 → 1 → 2, longueur 10 + 2 - 1 = 11.
        Integer r = LspDocumentSynchronizer.coherentRevision(
                java.util.List.of(e1, e2), 0, 10, 11);
        assertNotNull(r);
        assertEquals(2, r.intValue());
        // Longueur finale incohérente → repli.
        assertNull(LspDocumentSynchronizer.coherentRevision(
                java.util.List.of(e1, e2), 0, 10, 12));
        // Révision manquante (mutation non suivie, même à longueur
        // constante) → repli.
        LspDocumentSynchronizer.TrackedEdit e3 =
                new LspDocumentSynchronizer.TrackedEdit(0, 0, 0, 0, "z", 0, 4);
        assertNull(LspDocumentSynchronizer.coherentRevision(
                java.util.List.of(e2, e3), 0, 10, 10));
    }

    /** Vérifie la plage d'un événement de contenu. */
    private static void assertRange(TextDocumentContentChangeEvent e,
            int sl, int sc, int el, int ec) {
        assertNotNull(e.getRange());
        assertEquals(sl, e.getRange().getStart().getLine());
        assertEquals(sc, e.getRange().getStart().getCharacter());
        assertEquals(el, e.getRange().getEnd().getLine());
        assertEquals(ec, e.getRange().getEnd().getCharacter());
    }

    /**
     * Applique des événements de contenu LSP séquentiels (chaque plage se
     * rapporte à l'état après les précédents) — la reconstruction qu'un
     * serveur incrémental fait.
     */
    private static String applyEvents(String text,
            List<TextDocumentContentChangeEvent> events) {
        String state = text;
        for (TextDocumentContentChangeEvent e : events) {
            if (e.getRange() == null) {
                state = e.getText();
                continue;
            }
            int start = lineCharToOffset(state,
                    e.getRange().getStart().getLine(), e.getRange().getStart().getCharacter());
            int end = lineCharToOffset(state,
                    e.getRange().getEnd().getLine(), e.getRange().getEnd().getCharacter());
            state = state.substring(0, start) + e.getText() + state.substring(end);
        }
        return state;
    }

    /** Offset d'une position ligne/caractère dans le texte donné. */
    private static int lineCharToOffset(String text, int line, int character) {
        int currentLine = 0;
        int i = 0;
        while (currentLine < line && i < text.length()) {
            if (text.charAt(i) == '\n') currentLine++;
            i++;
        }
        return i + character;
    }
}
