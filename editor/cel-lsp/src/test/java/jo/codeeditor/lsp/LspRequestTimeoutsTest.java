package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;
import jo.codeeditor.lang.model.CompletionItem;
import jo.codeeditor.lang.provider.CompletionPublisher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1-bis — timeouts par type de requête, surchargeables par définition
 * de serveur : un serveur dont la complétion ne répond JAMAIS rend la
 * main au bout du timeout surchargé (200 ms), pas du délai historique
 * (15 s) ni de l'infini.
 *
 * <p>Rouge avant : chaque provider codait son délai en dur — aucun
 * moyen pour un hôte de resserrer/relâcher le garde-fou d'un serveur
 * donné, et la poignée de main initialize pouvait attendre
 * indéfiniment.</p>
 */
class LspRequestTimeoutsTest {

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private LspCompletionProvider provider;

    private final List<CompletionItem> received = new ArrayList<>();
    private final CompletionPublisher publisher = new CompletionPublisher() {
        @Override public void addItem(CompletionItem item) { received.add(item); }
        @Override public void addItems(List<CompletionItem> items) { received.addAll(items); }
        @Override public void flush() {}
        @Override public void cancel() {}
        @Override public boolean isCancelled() { return false; }
    };

    @BeforeEach
    void setUp(@TempDir File racine) throws Exception {
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        // Serveur factice : initialize répond, la complétion NE RÉPOND
        // JAMAIS (futur jamais complété).
        org.eclipse.lsp4j.services.LanguageServer fake =
                new org.eclipse.lsp4j.services.LanguageServer() {
                    @Override
                    public CompletableFuture<org.eclipse.lsp4j.InitializeResult> initialize(
                            org.eclipse.lsp4j.InitializeParams params) {
                        org.eclipse.lsp4j.ServerCapabilities caps =
                                new org.eclipse.lsp4j.ServerCapabilities();
                        caps.setCompletionProvider(new org.eclipse.lsp4j.CompletionOptions());
                        return CompletableFuture.completedFuture(
                                new org.eclipse.lsp4j.InitializeResult(caps));
                    }

                    @Override
                    public CompletableFuture<Object> shutdown() {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void exit() {}

                    @Override
                    public org.eclipse.lsp4j.services.TextDocumentService getTextDocumentService() {
                        return new org.eclipse.lsp4j.services.TextDocumentService() {
                            @Override
                            public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {}

                            @Override
                            public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {}

                            @Override
                            public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams params) {}

                            @Override
                            public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {}

                            @Override
                            public CompletableFuture<
                                    org.eclipse.lsp4j.jsonrpc.messages.Either<
                                            List<org.eclipse.lsp4j.CompletionItem>,
                                            org.eclipse.lsp4j.CompletionList>> completion(
                                            org.eclipse.lsp4j.CompletionParams params) {
                                return new CompletableFuture<>(); // jamais complétée
                            }
                        };
                    }

                    @Override
                    public org.eclipse.lsp4j.services.WorkspaceService getWorkspaceService() {
                        return new org.eclipse.lsp4j.services.WorkspaceService() {
                            @Override
                            public void didChangeConfiguration(
                                    org.eclipse.lsp4j.DidChangeConfigurationParams params) {}

                            @Override
                            public void didChangeWatchedFiles(
                                    org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}
                        };
                    }
                };
        InProcessStreamConnectionProvider connection =
                new InProcessStreamConnectionProvider(() -> fake);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "hang") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return connection;
            }
        };
        // SURCHARGE : complétion bornée à 200 ms pour CE serveur.
        definition.setTimeouts(new LspRequestTimeouts().setCompletion(200));
        wrapper = new LanguageServerWrapper(definition, racine.getAbsolutePath(), executor);
        wrapper.start().join();
        editor = new LspEditor(project, "file:///proj/src/A.java");
        wrapper.getClient().setLspEditor(editor);
        provider = new LspCompletionProvider(wrapper, editor, null);
        received.clear();
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    @Test
    void complétionSuspendue_timeoutSurchargéLaMainEstRendueVite() {
        long t0 = System.nanoTime();
        provider.complete("int foo;\n", 8, (String) null, publisher);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        // Le timeout surchargé (200 ms) borne l'attente : nettement
        // moins d'une seconde, aucun item livré, aucune exception
        // remontée au caller (chemin TimeoutException journalisé).
        assertTrue(ms < 2_000, "la main doit être rendue vite (mesuré " + ms + " ms)");
        assertTrue(received.isEmpty(), "aucun item n'arrive d'un serveur muet");
    }

    @Test
    void valeursParDéfaut_délaisHistoriques() {
        LspRequestTimeouts def = new LspRequestTimeouts();
        assertEquals(15_000, def.completionMs(), "complétion : délai historique");
        assertEquals(2_000, def.hoverMs(), "hover : délai historique");
        assertEquals(10_000, def.signatureHelpMs(), "signature : délai historique");
        assertEquals(3_000, def.definitionMs(), "définition : délai historique");
        assertEquals(5_000, def.formattingMs(), "formatage : délai historique");
        assertEquals(10_000, def.initMs(), "poignée de main : 10 s par défaut");
    }

    @Test
    void surchargeFluenteEtNeutralisation() {
        LspRequestTimeouts t = new LspRequestTimeouts()
                .setCompletion(30_000)
                .setInit(0);
        assertEquals(30_000, t.completionMs());
        assertEquals(0, t.initMs(), "0 = garde-fou neutralisé (attente illimitée)");
        // Timeout nul : attente illimitée (comportement historique sur
        // demande explicite).
        assertEquals(0, new LspRequestTimeouts().setCompletion(-5).completionMs(),
                "un timeout négatif est neutralisé");
    }

    @Test
    void surchargePortéeParLaDéfinition_lueParLeWrapper() {
        LanguageServerDefinition def = new LanguageServerDefinition("x", "t") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return null;
            }
        };
        assertNull(def.getTimeouts(), "pas de surcharge par défaut");
        LspRequestTimeouts t = new LspRequestTimeouts().setHover(1_500);
        def.setTimeouts(t);
        assertEquals(t, def.getTimeouts(), "la surcharge vit sur la définition");
        assertEquals(1_500, def.getTimeouts().hoverMs());
    }

    @Test
    void poignéeDeMainSansRéponse_gardeFouInitBorné(@TempDir File racine) throws Exception {
        ExecutorService ex = Executors.newCachedThreadPool();
        LspProject projet = new LspProject(racine.getAbsolutePath());
        try {
            // Serveur dont initialize ne répond JAMAIS.
            org.eclipse.lsp4j.services.LanguageServer muet =
                    new org.eclipse.lsp4j.services.LanguageServer() {
                        @Override
                        public CompletableFuture<org.eclipse.lsp4j.InitializeResult> initialize(
                                org.eclipse.lsp4j.InitializeParams params) {
                            return new CompletableFuture<>();
                        }

                        @Override
                        public CompletableFuture<Object> shutdown() {
                            return CompletableFuture.completedFuture(null);
                        }

                        @Override
                        public void exit() {}

                        @Override
                        public org.eclipse.lsp4j.services.TextDocumentService getTextDocumentService() {
                            return new org.eclipse.lsp4j.services.TextDocumentService() {
                                @Override
                                public void didOpen(
                                        org.eclipse.lsp4j.DidOpenTextDocumentParams params) {}

                                @Override
                                public void didChange(
                                        org.eclipse.lsp4j.DidChangeTextDocumentParams params) {}

                                @Override
                                public void didClose(
                                        org.eclipse.lsp4j.DidCloseTextDocumentParams params) {}

                                @Override
                                public void didSave(
                                        org.eclipse.lsp4j.DidSaveTextDocumentParams params) {}
                            };
                        }

                        @Override
                        public org.eclipse.lsp4j.services.WorkspaceService getWorkspaceService() {
                            return new org.eclipse.lsp4j.services.WorkspaceService() {
                                @Override
                                public void didChangeConfiguration(
                                        org.eclipse.lsp4j.DidChangeConfigurationParams params) {}

                                @Override
                                public void didChangeWatchedFiles(
                                        org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {}
                            };
                        }
                    };
            InProcessStreamConnectionProvider connexion =
                    new InProcessStreamConnectionProvider(() -> muet);
            LanguageServerDefinition def = new LanguageServerDefinition("java", "init-muet") {
                @Override
                public StreamConnectionProvider createConnectionProvider(String workingDir) {
                    return connexion;
                }
            };
            def.setTimeouts(new LspRequestTimeouts().setInit(150));
            LanguageServerWrapper w = new LanguageServerWrapper(def,
                    racine.getAbsolutePath(), ex);
            long t0 = System.nanoTime();
            try {
                w.start().join();
                fail("le démarrage doit échouer sur le garde-fou initialize");
            } catch (java.util.concurrent.CompletionException e) {
                // La cause peut être enveloppée (« LSP initialize failed »)
                // — remonter la chaîne jusqu'au TimeoutException du garde-fou.
                Throwable c = e.getCause();
                boolean timeoutTrouvé = false;
                while (c != null) {
                    if (c instanceof java.util.concurrent.TimeoutException) {
                        timeoutTrouvé = true;
                        break;
                    }
                    c = c.getCause();
                }
                assertTrue(timeoutTrouvé,
                        "la chaîne de causes contient un TimeoutException (reçu : " + e + ")");
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(ms < 2_000, "le garde-fou borne le démarrage (mesuré " + ms + " ms)");
        } finally {
            projet.shutdown();
            ex.shutdownNow();
        }
    }
}
