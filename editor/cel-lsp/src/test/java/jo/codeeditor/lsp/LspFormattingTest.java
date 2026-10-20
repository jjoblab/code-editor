package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;

/**
 * Options et formatage LSP (3.74.0, L3) : la requête
 * {@code textDocument/formatting} doit porter des
 * {@link org.eclipse.lsp4j.FormattingOptions} (tabSize, insertSpaces) —
 * le protocole les exige ; les serveurs tolérants les ignorent, un
 * serveur strict peut échouer.
 *
 * <p>Source par ordre de priorité : valeur posée par l'hôte
 * ({@code EditorHost.formattingTabSize()} /
 * {@code formattingInsertSpaces()}), sinon indentation détectée dans le
 * document (tabulation contre 2/4/8 espaces), sinon 4 espaces.</p>
 *
 * <p>Rouge avant le correctif 3.74.0 : {@code LspFormatter} construisait
 * {@code DocumentFormattingParams} SANS {@code setOptions(...)} — le
 * serveur factice n'enregistrait aucune ligne
 * {@code formatting:tabSize=…} et les tests échouaient.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LspFormattingTest {

    private static final String URI = "file:///proj/src/A.java";

    /** Document indenté aux 2 espaces : détection → tabSize=2, espaces. */
    private static final String TEXTE_DEUX_ESPACES =
            "class A {\n  void m() {\n    x();\n  }\n}\n";

    /** Document indenté aux tabulations : détection → tabulations conservées. */
    private static final String TEXTE_TABULATIONS =
            "class A {\n\tvoid m() {\n\t\tx();\n\t}\n}\n";

    /**
     * Serveur factice : négociation avec formateur de document (et plage
     * optionnelle), enregistre CHAQUE requête formatting/rangeFormatting
     * avec les options reçues (tabSize/insertSpaces/plage).
     */
    static final class ServeurFormatage implements LanguageServer {
        final List<String> requêtes = Collections.synchronizedList(new ArrayList<>());
        final List<String> didClose = Collections.synchronizedList(new ArrayList<>());
        /** Annoncer documentRangeFormattingProvider ? */
        volatile boolean plageAnnoncée = false;

        private ServerCapabilities capabilities() {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setDocumentFormattingProvider(Boolean.TRUE);
            caps.setDocumentRangeFormattingProvider(plageAnnoncée);
            return caps;
        }

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            return CompletableFuture.completedFuture(new InitializeResult(capabilities()));
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
                public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams params) {
                    requêtes.add("didOpen");
                }
                @Override
                public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams params) {
                    requêtes.add("didChange");
                }
                @Override
                public void didClose(DidCloseTextDocumentParams params) {
                    didClose.add(params.getTextDocument().getUri());
                    requêtes.add("didClose");
                }
                @Override
                public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams params) {
                    requêtes.add("didSave");
                }
                @Override
                @SuppressWarnings("unchecked")
                public CompletableFuture<List<? extends TextEdit>> formatting(
                        org.eclipse.lsp4j.DocumentFormattingParams params) {
                    requêtes.add("formatting");
                    org.eclipse.lsp4j.FormattingOptions o = params.getOptions();
                    if (o != null) {
                        requêtes.add("formatting:tabSize=" + o.getTabSize()
                                + ",insertSpaces=" + o.isInsertSpaces());
                    }
                    return CompletableFuture.completedFuture(
                            (List<? extends TextEdit>) Collections.<TextEdit>emptyList());
                }
                @Override
                @SuppressWarnings("unchecked")
                public CompletableFuture<List<? extends TextEdit>> rangeFormatting(
                        org.eclipse.lsp4j.DocumentRangeFormattingParams params) {
                    requêtes.add("rangeFormatting");
                    org.eclipse.lsp4j.FormattingOptions o = params.getOptions();
                    if (o != null) {
                        requêtes.add("rangeFormatting:tabSize=" + o.getTabSize()
                                + ",insertSpaces=" + o.isInsertSpaces()
                                + ",range=" + params.getRange().getStart().getLine()
                                + ":" + params.getRange().getStart().getCharacter()
                                + "-" + params.getRange().getEnd().getLine()
                                + ":" + params.getRange().getEnd().getCharacter());
                    }
                    return CompletableFuture.completedFuture(
                            (List<? extends TextEdit>) Collections.<TextEdit>emptyList());
                }
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

    /**
     * Hôte factice : session réelle + options de formatage posables (ce
     * que {@code EditorView.setFormattingOptions} relaie via l'adaptateur
     * de vue), null = rien de posé (détection dans le document).
     */
    static final class HoteFormatage implements jo.codeeditor.lang.host.EditorHost {
        final jo.codeeditor.session.EditorSession session;
        Integer tabSizePosé;
        Boolean insertSpacesPosé;

        HoteFormatage(String texte) {
            session = new jo.codeeditor.session.EditorSession(
                    jo.codeeditor.document.EditorDocument.of(texte));
        }

        @Override
        public jo.codeeditor.session.EditorSession session() { return session; }
        @Override
        public jo.codeeditor.document.EditorDocument document() {
            return session.getDocument();
        }
        @Override
        public void setLanguage(jo.codeeditor.lang.Language language) {}
        @Override
        public void notifyDiagnosticsChanged() {}
        @Override
        public void notifyTextChanged() {}
        @Override
        public void invalidate() {}
        @Override
        public boolean isAttachedToWindow() { return false; }
        @Override
        public void post(Runnable task) { task.run(); }
        @Override
        public void postDelayed(Runnable task, long delayMillis) { task.run(); }
        @Override
        public int formattingTabSize() {
            return tabSizePosé != null ? tabSizePosé : -1;
        }
        @Override
        public Boolean formattingInsertSpaces() {
            return insertSpacesPosé;
        }
    }

    private LspProject project;
    private LspEditor editor;
    private ServeurFormatage serveur;

    @Before
    public void setUp() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("formatting").toFile();
        project = new LspProject(racine.getAbsolutePath());
        serveur = new ServeurFormatage();
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> serveur);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "factice") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        project.addServerDefinition(definition);
        editor = project.createEditor(URI);
    }

    @After
    public void tearDown() {
        try {
            editor.disconnect();
        } catch (RuntimeException ignorée) {
        }
        project.shutdown();
    }

    /** Connecte l'hôte (détaché → pose en ligne) et retourne le formateur SPI. */
    private jo.codeeditor.lang.provider.Formatter connecter(HoteFormatage host) throws Exception {
        editor.setHost(host);
        editor.connect().get(10, java.util.concurrent.TimeUnit.SECONDS);
        jo.codeeditor.lang.provider.Formatter formatter = editor.getLanguage().getFormatter();
        assertNotNull("précondition : le serveur annonce documentFormattingProvider",
                formatter);
        return formatter;
    }

    // ── L3 : FormattingOptions présentes dans la requête ────────────

    @Test
    public void optionsDétectées_documentDeuxEspaces() throws Exception {
        HoteFormatage host = new HoteFormatage(TEXTE_DEUX_ESPACES);
        jo.codeeditor.lang.provider.Formatter formatter = connecter(host);

        formatter.format(TEXTE_DEUX_ESPACES, 0, TEXTE_DEUX_ESPACES.length());

        assertTrue("la requête porte les options détectées (2 espaces) : "
                        + serveur.requêtes,
                serveur.requêtes.contains("formatting:tabSize=2,insertSpaces=true"));
    }

    @Test
    public void optionsDétectées_documentTabulations() throws Exception {
        HoteFormatage host = new HoteFormatage(TEXTE_TABULATIONS);
        jo.codeeditor.lang.provider.Formatter formatter = connecter(host);

        formatter.format(TEXTE_TABULATIONS, 0, TEXTE_TABULATIONS.length());

        // Tabulations détectées → tabulations conservées ; largeur
        // visuelle non décelable, repli 4.
        assertTrue("tabulations détectées : insertSpaces=false, repli 4 : "
                        + serveur.requêtes,
                serveur.requêtes.contains("formatting:tabSize=4,insertSpaces=false"));
    }

    @Test
    public void optionsPoséesParLHôte_prennentLaPriorité() throws Exception {
        HoteFormatage host = new HoteFormatage(TEXTE_DEUX_ESPACES);
        host.tabSizePosé = 8;
        host.insertSpacesPosé = false;
        jo.codeeditor.lang.provider.Formatter formatter = connecter(host);

        formatter.format(TEXTE_DEUX_ESPACES, 0, TEXTE_DEUX_ESPACES.length());

        assertTrue("la valeur posée par l'hôte prime sur la détection : "
                        + serveur.requêtes,
                serveur.requêtes.contains("formatting:tabSize=8,insertSpaces=false"));
    }

    @Test
    public void optionsParDéfaut_documentSansIndentation() throws Exception {
        // Aucune ligne indentée : repli documenté → 4 espaces.
        String texte = "a\nb\nc\n";
        HoteFormatage host = new HoteFormatage(texte);
        jo.codeeditor.lang.provider.Formatter formatter = connecter(host);

        formatter.format(texte, 0, texte.length());

        assertTrue("sans indentation décelable : repli 4 espaces : "
                        + serveur.requêtes,
                serveur.requêtes.contains("formatting:tabSize=4,insertSpaces=true"));
    }
}
