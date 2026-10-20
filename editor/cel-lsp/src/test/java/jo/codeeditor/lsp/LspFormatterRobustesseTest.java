package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
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
 * Robustesse du formateur (3.74.0, L5 — constat C4) :
 * <ul>
 *   <li>les échecs sont JOURNALISÉS via {@link LspLogSink} : délai
 *       dépassé, exception interceptée, résultat vide (≠ erreur) ;</li>
 *   <li>JAMAIS de contenu du document dans les journaux (test de
 *       non-fuite : le secret du document ne doit pas apparaître) ;</li>
 *   <li>l'application des {@link TextEdit} valide les bornes, trie de
 *       façon stable et IGNORE (avec journal, sans exception) les
 *       éditions invalides ou chevauchantes.</li>
 * </ul>
 *
 * <p>Rouge avant le correctif 3.74.0 : {@code LspFormatter} avalait
 * toute exception ({@code return text}) sans écrire la moindre ligne
 * de journal, et appliquait les éditions sans contrôle — une édition
 * hors bornes levait {@link StringIndexOutOfBoundsException}.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class LspFormatterRobustesseTest {

    private static final String URI = "file:///proj/src/A.java";

    /** Document porteur d'un secret : sa présence dans un journal est une FUITE. */
    private static final String TEXTE_SECRET =
            "class A {\n  void m() {\n    secretFuitPas();\n  }\n}\n";

    /** Récepteur de journaux en mémoire (thread-safe : rappels LSP hors thread UI). */
    static final class JournalMémoire implements LspLogSink {
        final List<String> lignes = Collections.synchronizedList(new ArrayList<>());
        @Override public void log(String message) { lignes.add(message); }
        boolean contient(String fragment) {
            synchronized (lignes) { return lignes.stream().anyMatch(l -> l.contains(fragment)); }
        }
    }

    /**
     * Serveur factice paramétrable : formateur de document annoncé,
     * comportement de la réponse programmable par test (délai, erreur,
     * éditions arbitraires).
     */
    static final class ServeurRobustesse implements LanguageServer {
        /** Que répondre à formatting : delai_ms / erreur / editions. */
        volatile long délaiMs = 0;
        volatile org.eclipse.lsp4j.jsonrpc.ResponseErrorException erreur;
        volatile List<TextEdit> éditions = Collections.emptyList();
        final List<String> requêtes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
            ServerCapabilities caps = new ServerCapabilities();
            caps.setDocumentFormattingProvider(Boolean.TRUE);
            return CompletableFuture.completedFuture(new InitializeResult(caps));
        }

        @Override public CompletableFuture<Object> shutdown() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void exit() {}

        @Override
        public TextDocumentService getTextDocumentService() {
            return new TextDocumentService() {
                @Override public void didOpen(org.eclipse.lsp4j.DidOpenTextDocumentParams p) {}
                @Override public void didChange(org.eclipse.lsp4j.DidChangeTextDocumentParams p) {}
                @Override public void didClose(org.eclipse.lsp4j.DidCloseTextDocumentParams p) {}
                @Override public void didSave(org.eclipse.lsp4j.DidSaveTextDocumentParams p) {}
                @Override
                @SuppressWarnings("unchecked")
                public CompletableFuture<List<? extends TextEdit>> formatting(
                        org.eclipse.lsp4j.DocumentFormattingParams p) {
                    requêtes.add("formatting");
                    if (erreur != null) {
                        return CompletableFuture.failedFuture(erreur);
                    }
                    if (délaiMs > 0) {
                        CompletableFuture<List<? extends TextEdit>> f = new CompletableFuture<>();
                        // N'aboutit JAMAIS : le délai du client doit expirer.
                        return f;
                    }
                    return CompletableFuture.completedFuture((List<? extends TextEdit>) éditions);
                }
            };
        }

        @Override
        public WorkspaceService getWorkspaceService() {
            return new WorkspaceService() {
                @Override public void didChangeConfiguration(
                        org.eclipse.lsp4j.DidChangeConfigurationParams p) {}
                @Override public void didChangeWatchedFiles(
                        org.eclipse.lsp4j.DidChangeWatchedFilesParams p) {}
            };
        }
    }

    /** Hôte factice minimal (session réelle, jamais attaché). */
    static final class HoteMinimal implements jo.codeeditor.lang.host.EditorHost {
        final jo.codeeditor.session.EditorSession session;
        HoteMinimal(String texte) {
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

    private static TextEdit édition(int sl, int sc, int el, int ec, String nouveau) {
        TextEdit te = new TextEdit();
        te.setRange(new Range(new Position(sl, sc), new Position(el, ec)));
        te.setNewText(nouveau);
        return te;
    }

    private LspProject project;
    private LspEditor editor;
    private ServeurRobustesse serveur;
    private JournalMémoire journal;

    @Before
    public void setUp() throws Exception {
        File racine = java.nio.file.Files.createTempDirectory("formatter-l5").toFile();
        project = new LspProject(racine.getAbsolutePath());
        serveur = new ServeurRobustesse();
        journal = new JournalMémoire();
        project.setLogSink(journal);
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
        editor.setHost(new HoteMinimal(TEXTE_SECRET));
        editor.connect().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull("précondition : formateur installé",
                editor.getLanguage().getFormatter());
    }

    @After
    public void tearDown() {
        try {
            editor.disconnect();
        } catch (RuntimeException ignorée) {
        }
        project.shutdown();
    }

    // ── Journal des échecs ───────────────────────────────────────────

    @Test
    public void exceptionServeur_journaliséeTexteIntactSansFuite() {
        // Erreur de requête au sens du protocole : contrairement à une
        // exception brute (masquée en « Internal error. » par la couche
        // transport), le message VOYAGE jusqu'au client.
        serveur.erreur = new org.eclipse.lsp4j.jsonrpc.ResponseErrorException(
                new org.eclipse.lsp4j.jsonrpc.messages.ResponseError(
                        org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode.InternalError,
                        "panne factice du serveur", null));

        CharSequence résultat = editor.getLanguage().getFormatter()
                .format(TEXTE_SECRET, 0, TEXTE_SECRET.length());

        assertEquals("échec silencieux : texte original retourné",
                TEXTE_SECRET, résultat.toString());
        assertTrue("l'échec est journalisé (message d'exception présent)",
                journal.contient("panne factice du serveur"));
        assertFalse("JAMAIS le contenu du document dans le journal",
                journal.contient("secretFuitPas"));
    }

    @Test
    public void délaiDépassé_journaliséTexteIntact() {
        // Futur jamais complété : l'attente bornée du formateur expire.
        serveur.délaiMs = Long.MAX_VALUE;
        // Délai formatting réduit pour un test rapide — la valeur par
        // défaut du projet (5 s) rendrait ce test cinq fois plus lent.
        // (requestTimeouts est package-private : accès direct depuis
        // ce test du même package.)
        try {
            java.lang.reflect.Field f = LspEditor.class.getDeclaredField("wrapper");
            f.setAccessible(true);
            LanguageServerWrapper w = (LanguageServerWrapper) f.get(editor);
            w.requestTimeouts().setFormatting(200);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("accès au wrapper pour le délai", e);
        }

        CharSequence résultat = editor.getLanguage().getFormatter()
                .format(TEXTE_SECRET, 0, TEXTE_SECRET.length());

        assertEquals("délai dépassé : texte original retourné",
                TEXTE_SECRET, résultat.toString());
        assertTrue("l'expiration du délai est journalisée",
                journal.contient("délai"));
        assertFalse("JAMAIS le contenu du document dans le journal",
                journal.contient("secretFuitPas"));
    }

    @Test
    public void résultatVide_journaliséCommeInformationDistincte() {
        serveur.éditions = Collections.emptyList();

        CharSequence résultat = editor.getLanguage().getFormatter()
                .format(TEXTE_SECRET, 0, TEXTE_SECRET.length());

        assertEquals("résultat vide : texte inchangé",
                TEXTE_SECRET, résultat.toString());
        // Un résultat vide n'est PAS une erreur : le journal le distingue
        // (aucun mot « erreur »/« échec » sur cette ligne).
        assertTrue("le résultat vide est journalisé comme information",
                journal.contient("aucune édition"));
        assertFalse("le résultat vide n'est PAS journalisé comme erreur",
                journal.contient("erreur: aucune édition"));
    }

    // ── Validation des éditions ──────────────────────────────────────

    @Test
    public void éditionsHorsBornes_ignoréesAvecJournalSansException() {
        // 3 éditions : une valide, une hors bornes (ligne 99), une
        // aux bornes inversées (fin avant début) — seules la valide passe.
        serveur.éditions = java.util.Arrays.asList(
                édition(0, 6, 0, 7, "B"),        // "A" -> "B" : valide
                édition(99, 0, 99, 4, "XXXX"),   // hors document
                édition(0, 20, 0, 10, "YYY"));   // bornes inversées

        CharSequence résultat = editor.getLanguage().getFormatter()
                .format(TEXTE_SECRET, 0, TEXTE_SECRET.length());

        assertEquals("seule l'édition valide est appliquée",
                TEXTE_SECRET.replaceFirst("class A", "class B"), résultat.toString());
        assertTrue("les éditions rejetées sont journalisées (2 rejets)",
                journal.contient("2 édition(s) ignorée(s)"));
        assertFalse("JAMAIS le contenu du document dans le journal",
                journal.contient("secretFuitPas"));
    }

    @Test
    public void éditionsChevauchantes_laDernièreNeCorromptPasLeDocument() {
        // Deux éditions valides individuellement mais chevauchantes.
        // L'application se fait en ordre de début DÉCROISSANT (convention
        // d'offsets stables) : l'édition au début le plus grand (1:8, la
        // petite) est appliquée la première ; la grande (1:2, qui la
        // recouvre) chevauche alors une zone déjà traitée et doit être
        // IGNORÉE proprement — appliquer ses offsets obsolètes détruirait
        // le texte fraîchement écrit.
        serveur.éditions = java.util.Arrays.asList(
                édition(1, 2, 1, 12, "void remplacé()"),   // couvre [1:2..1:12)
                édition(1, 8, 1, 11, "xyz"));               // chevauche la précédente

        CharSequence résultat = editor.getLanguage().getFormatter()
                .format(TEXTE_SECRET, 0, TEXTE_SECRET.length());

        // Seule la petite (start le plus grand) est appliquée : "() " -> "xyz".
        assertEquals("l'édition chevauchante est ignorée, pas entrelacée",
                TEXTE_SECRET.replace("m() {", "mxyz{"),
                résultat.toString());
        assertTrue("le chevauchement est journalisé",
                journal.contient("chevauchement"));
    }

    @Test
    public void éditionsTriStable_ordreDécroissantPréservé() {
        // Éditions fournies dans le désordre : le résultat doit être
        // identique à l'application en ordre décroissant d'offset.
        serveur.éditions = java.util.Arrays.asList(
                édition(0, 6, 0, 7, "Z"),        // après (offset plus grand)
                édition(0, 0, 0, 5, "struct"));  // avant

        CharSequence résultat = editor.getLanguage().getFormatter()
                .format(TEXTE_SECRET, 0, TEXTE_SECRET.length());

        assertEquals("les deux éditions s'appliquent, tri stable",
                "struct Z {\n  void m() {\n    secretFuitPas();\n  }\n}\n",
                résultat.toString());
    }
}
