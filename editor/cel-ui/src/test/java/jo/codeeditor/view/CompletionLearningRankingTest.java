package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * APPRENTISSAGE DU CLASSEMENT (4.5.0) : les candidats déjà acceptés
 * remontent à PALIER ET GROUPE ÉGAUX (3ᵉ départage du tri stable,
 * après le palier d'appariement et le groupe), sans jamais franchir
 * ces barrières ; la bascule coupe le boost, la purge le réinitialise,
 * les langages sont étanches.
 *
 * <p>Stratégie : un fournisseur factice renvoie toujours le même ordre
 * serveur (le candidat « chouchou » en dernier) ; on accepte une fois,
 * on redéclenche, on lit l'ordre affiché.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionLearningRankingTest {

    // ── Montage ─────────────────────────────────────────────────────

    /** Monte une vue sur un document, caret en fin, provider factice. */
    private EditorView viewWithDocAndProvider(String doc, List<CompletionSession.Item> serverOrder) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setSelection(doc.length());
        view.setSession(session);
        final List<CompletionSession.Item> items = Collections.unmodifiableList(
                new ArrayList<>(serverOrder));
        view.setCompletionProvider((text, caret, tokenStart, prefix) -> items);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    /** Item de symbole résolu (méthode, kind LSP 2). */
    private static CompletionSession.Item method(String label) {
        return new CompletionSession.Item(label, "", label, "m", 2, 0, false, false);
    }

    /** Item mot-clé (kind LSP 14, isKeyword). */
    private static CompletionSession.Item keyword(String label) {
        return new CompletionSession.Item(label, "keyword", label, "k", 14, 0, true, false);
    }

    /** Attend que le pipeline async ait livré et filtré (provider + lane). */
    private static void awaitPopup(EditorView view) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (view.completion.items.isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                fail("le popup n'a reçu aucun item dans le délai imparti");
            }
            Thread.sleep(20);
        }
        Thread.sleep(60);
    }

    private static List<String> displayedLabels(EditorView view) {
        List<String> out = new ArrayList<>();
        for (CompletionSession.Item item : view.completion.items) {
            out.add(item.label);
        }
        return out;
    }

    /** Ré-arme le document au préfixe donné (le token repart de zéro). */
    private static void retype(EditorView view, String prefix) {
        view.session.replaceRange(0, view.session.getText().length(), prefix);
        view.session.setSelection(prefix.length());
        view.refreshCompletion();
    }

    // ── Le 3ᵉ départage : à palier et groupe égaux ─────────────────

    @Test
    public void candidatAccepte_passeDevantAMemePalier() throws Exception {
        // Ordre serveur défavorable : Text() EN DERNIER. Pour « Te »,
        // TextField() et Text() sont tous deux des préfixes sensibles à
        // la casse (même palier, même groupe résolu) → l'ordre serveur
        // s'applique… jusqu'à ce que Text() soit accepté une fois.
        List<CompletionSession.Item> serverOrder = Arrays.asList(
                method("TextField()"), method("Text()"));
        EditorView view = viewWithDocAndProvider("Te", serverOrder);
        view.refreshCompletion();
        awaitPopup(view);
        assertEquals(Arrays.asList("TextField()", "Text()"), displayedLabels(view));

        // Acceptation du second candidat (le chouchou).
        view.completion.selected = 1;
        assertTrue(view.completionAccept());

        // Re-frappe du même préfixe : le candidat appris passe devant,
        // à palier et groupe égaux.
        retype(view, "Te");
        awaitPopup(view);
        assertEquals(Arrays.asList("Text()", "TextField()"), displayedLabels(view));
    }

    @Test
    public void barriereDePalier_jamaisFranchie() throws Exception {
        // « for » tapé : le mot-clé « for » est EXACT (palier 0),
        // « format() » n'est qu'un préfixe (palier 2). On accepte
        // format() TROIS fois : l'apprentissage ne doit jamais le faire
        // passer devant l'exact.
        List<CompletionSession.Item> serverOrder = Arrays.asList(
                keyword("for"), method("format()"));
        EditorView view = viewWithDocAndProvider("for", serverOrder);
        for (int i = 0; i < 3; i++) {
            view.refreshCompletion();
            awaitPopup(view);
            view.completion.selected = 1;
            assertTrue(view.completionAccept());
            retype(view, "for");
        }
        awaitPopup(view);
        assertEquals("l'exact (mot-clé for) reste devant le préfixe appris",
                Arrays.asList("for", "format()"), displayedLabels(view));
    }

    @Test
    public void basculeFermee_boostCoupe_ordreServeurRestaure() throws Exception {
        List<CompletionSession.Item> serverOrder = Arrays.asList(
                method("TextField()"), method("Text()"));
        EditorView view = viewWithDocAndProvider("Te", serverOrder);
        view.refreshCompletion();
        awaitPopup(view);
        view.completion.selected = 1;
        assertTrue(view.completionAccept());

        // Bascule fermée AVANT la re-frappe : plus de boost.
        view.setCompletionRankingLearningEnabled(false);
        assertFalse(view.isCompletionRankingLearningEnabled());
        retype(view, "Te");
        awaitPopup(view);
        assertEquals(Arrays.asList("TextField()", "Text()"), displayedLabels(view));
    }

    @Test
    public void purge_reinitialiseLeClassement() throws Exception {
        List<CompletionSession.Item> serverOrder = Arrays.asList(
                method("TextField()"), method("Text()"));
        EditorView view = viewWithDocAndProvider("Te", serverOrder);
        view.refreshCompletion();
        awaitPopup(view);
        view.completion.selected = 1;
        assertTrue(view.completionAccept());

        view.clearCompletionRankingHistory();
        retype(view, "Te");
        awaitPopup(view);
        assertEquals(Arrays.asList("TextField()", "Text()"), displayedLabels(view));
    }

    @Test
    public void langagesEtanches() throws Exception {
        List<CompletionSession.Item> serverOrder = Arrays.asList(
                method("TextField()"), method("Text()"));
        EditorView view = viewWithDocAndProvider("Te", serverOrder);
        view.session.setLanguage("java");
        view.refreshCompletion();
        awaitPopup(view);
        view.completion.selected = 1;
        assertTrue(view.completionAccept());

        // Même préfixe, autre langage : l'apprentissage ne suit pas.
        view.session.setLanguage("xml");
        retype(view, "Te");
        awaitPopup(view);
        assertEquals(Arrays.asList("TextField()", "Text()"), displayedLabels(view));

        // Retour au langage d'apprentissage : le boost est toujours là.
        view.session.setLanguage("java");
        retype(view, "Te");
        awaitPopup(view);
        assertEquals(Arrays.asList("Text()", "TextField()"), displayedLabels(view));
    }
}
