package jo.codeeditor.lsp;

import jo.codeeditor.lsp.connection.InProcessStreamConnectionProvider;
import jo.codeeditor.lsp.connection.StreamConnectionProvider;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La demande d'actions de code AU POINT D'INVOCATION : UNE requête par
 * invocation, avec la plage visée par l'utilisateur (sélection, sinon
 * mot sous le caret, sinon ligne) — le modèle « à la demande » qui
 * remplace le balayage d'une requête par ligne visible.
 */
class CodeActionCaretRequestTest {

    private static final String URI = "file:///proj/src/A.java";
    // "    String s = foo(bar);" sur la ligne 1 ; foo commence col 17.
    private static final String DOC = "public class A {\n    String s = foo(bar);\n}\n";

    private ExecutorService executor;
    private LanguageServerWrapper wrapper;
    private LspProject project;
    private LspEditor editor;
    private FakeActionsCodeServer server;

    @BeforeEach
    void setUp(@TempDir File racine) throws Exception {
        executor = Executors.newCachedThreadPool();
        project = new LspProject(racine.getAbsolutePath());
        server = new FakeActionsCodeServer();
        InProcessStreamConnectionProvider provider =
                new InProcessStreamConnectionProvider(() -> server);
        LanguageServerDefinition definition = new LanguageServerDefinition("java", "fake") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return provider;
            }
        };
        wrapper = new LanguageServerWrapper(definition, racine.getAbsolutePath(), executor);
        wrapper.start().join();
        editor = new LspEditor(project, URI);
        wrapper.getClient().setLspEditor(editor);
        server.publishIdentityDiagnostics(URI);
        attendreCacheBrut();
    }

    @AfterEach
    void tearDown() {
        wrapper.shutdown();
        project.shutdown();
        executor.shutdownNow();
    }

    private List<Diagnostic> attendreCacheBrut() {
        // A-06 : attente bornée de la publication asynchrone côté pont.
        jo.codeeditor.AttenteBornee.jusquA(5000, () -> !editor.cachedLspDiagnostics().isEmpty());
        return editor.cachedLspDiagnostics();
    }

    /** L'offset d'un texte dans DOC. */
    private static int at(String needle) {
        return DOC.indexOf(needle);
    }

    @Test
    void uneInvocation_uneSeuleRequête() {
        LspCodeActionsProvider provider = new LspCodeActionsProvider(wrapper, editor, null);
        int avant = server.codeActionRequests;
        provider.codeActions(DOC, at("foo"), at("foo"), at("foo"), null);
        assertEquals(1, server.codeActionRequests - avant,
                "une invocation au caret = exactement une requête serveur");
    }

    @Test
    void sélectionNonVide_laPlageEstLaSélection() {
        new LspCodeActionsProvider(wrapper, editor, null)
                .codeActions(DOC, at("foo"), at("foo"), at("bar") + 3, null);
        Range reçue = server.lastParams.getRange();
        // Ligne 1 : « foo » col 15, « bar » finit col 22.
        assertEquals(1, reçue.getStart().getLine());
        assertEquals(15, reçue.getStart().getCharacter());
        assertEquals(1, reçue.getEnd().getLine());
        assertEquals(22, reçue.getEnd().getCharacter());
    }

    @Test
    void caretSurUnMot_laPlageEstLeMot() {
        int foo = at("foo");
        new LspCodeActionsProvider(wrapper, editor, null)
                .codeActions(DOC, foo + 2, foo, foo, null);
        Range reçue = server.lastParams.getRange();
        assertEquals(15, reçue.getStart().getCharacter());
        assertEquals(18, reçue.getEnd().getCharacter());
    }

    @Test
    void caretJusteAprèsLeMot_laPlageEstLeMotPrécédent() {
        int foo = at("foo");
        new LspCodeActionsProvider(wrapper, editor, null)
                .codeActions(DOC, foo + 3, foo + 3, foo + 3, null);
        Range reçue = server.lastParams.getRange();
        assertEquals(15, reçue.getStart().getCharacter());
        assertEquals(18, reçue.getEnd().getCharacter());
    }

    @Test
    void caretSansMotAutour_laPlageEstLaLigne() {
        // Caret posé sur le saut de ligne 0 (aucun mot autour) → repli
        // sur la ligne entière.
        int nl = DOC.indexOf('\n');
        new LspCodeActionsProvider(wrapper, editor, null)
                .codeActions(DOC, nl, nl, nl, null);
        Range reçue = server.lastParams.getRange();
        assertEquals(0, reçue.getStart().getLine());
        assertEquals(0, reçue.getStart().getCharacter());
        assertEquals(0, reçue.getEnd().getLine());
        assertEquals(Integer.MAX_VALUE, reçue.getEnd().getCharacter());
    }

    @Test
    void diagnosticMultiLignes_couvrantLeCaret_transmis() {
        // Un diagnostic qui COMMENCE ligne 0 et couvre jusqu'à la ligne 2 :
        // l'ancien filtre « ligne de début == ligne demandée » le perdait.
        Diagnostic multi = new Diagnostic();
        multi.setRange(new Range(new Position(0, 0), new Position(2, 1)));
        multi.setMessage("méthode non gérée");
        multi.setSeverity(DiagnosticSeverity.Warning);
        multi.setCode("famille:9");
        server.publishDiagnostics(URI, List.of(multi));
        attendreCacheBrut();

        // Caret ligne 1 (couvert par la plage multi-lignes).
        int caret = at("foo");
        new LspCodeActionsProvider(wrapper, editor, null)
                .codeActions(DOC, caret, caret, caret, null);
        assertEquals(1, server.lastParams.getContext().getDiagnostics().size(),
                "le diagnostic multi-lignes couvrant le caret doit être transmis");
    }

    @Test
    void onlyKinds_transmisAuContexte() {
        int foo = at("foo");
        new LspCodeActionsProvider(wrapper, editor, null)
                .codeActions(DOC, foo, foo, foo, List.of("source.organizeImports"));
        List<String> only = server.lastParams.getContext().getOnly();
        assertEquals(List.of("source.organizeImports"), only);
    }

    @Test
    void classement_quickfixDabordPréféréEnTête() throws Exception {
        // Serveur à réponse multi-kinds, ordre volontairement
        // défavorable : source, refactor, quickfix non préféré, quickfix
        // préféré — via un SECOND pont dédié.
        class ServeurTrié extends FakeActionsCodeServer {
            @Override
            protected List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> answer(
                    org.eclipse.lsp4j.CodeActionParams params) {
                List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>> out =
                        new ArrayList<>();
                org.eclipse.lsp4j.CodeAction source =
                        new org.eclipse.lsp4j.CodeAction("Organiser");
                source.setKind(org.eclipse.lsp4j.CodeActionKind.SourceOrganizeImports);
                out.add(Either.forRight(source));
                org.eclipse.lsp4j.CodeAction refactor =
                        new org.eclipse.lsp4j.CodeAction("Extraire");
                refactor.setKind(org.eclipse.lsp4j.CodeActionKind.RefactorExtract);
                out.add(Either.forRight(refactor));
                org.eclipse.lsp4j.CodeAction fix2 = new org.eclipse.lsp4j.CodeAction("Cast");
                fix2.setKind(org.eclipse.lsp4j.CodeActionKind.QuickFix);
                out.add(Either.forRight(fix2));
                org.eclipse.lsp4j.CodeAction fix1 = new org.eclipse.lsp4j.CodeAction("Importer");
                fix1.setKind(org.eclipse.lsp4j.CodeActionKind.QuickFix);
                fix1.setIsPreferred(Boolean.TRUE);
                out.add(Either.forRight(fix1));
                return out;
            }
        }
        ServeurTrié trié = new ServeurTrié();
        InProcessStreamConnectionProvider pont =
                new InProcessStreamConnectionProvider(() -> trié);
        LanguageServerDefinition def = new LanguageServerDefinition("java", "trie") {
            @Override
            public StreamConnectionProvider createConnectionProvider(String workingDir) {
                return pont;
            }
        };
        LanguageServerWrapper wrapperTrié =
                new LanguageServerWrapper(def, project.getWorkspacePath(), executor);
        try {
            wrapperTrié.start().join();
            LspEditor éditeurTrié = new LspEditor(project, URI);
            wrapperTrié.getClient().setLspEditor(éditeurTrié);

            List<jo.codeeditor.lang.model.CodeAction> actions =
                    new LspCodeActionsProvider(wrapperTrié, éditeurTrié, null)
                            .codeActions(DOC, 0, 0, 0, null);
            assertEquals(4, actions.size());
            assertEquals("Importer", actions.get(0).title, "quickfix préféré en tête");
            assertTrue(actions.get(0).isPreferred);
            assertEquals("Cast", actions.get(1).title);
            assertEquals("Extraire", actions.get(2).title);
            assertEquals("Organiser", actions.get(3).title);
        } finally {
            wrapperTrié.shutdown();
        }
    }
}
