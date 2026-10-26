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
 * Précision du CLASSEMENT LOCAL du popup de complétion : paliers de
 * correspondance (l'identifiant de tête, pas le libellé entier avec sa
 * signature), groupes (un symbole résolu ne passe jamais derrière un
 * mot-clé/snippet de même palier) et camel humps (les initiales de mots
 * remontent avant les sous-séquences quelconques).
 *
 * <p>Stratégie : un fournisseur de complétion factice renvoie une liste
 * d'items dans un ordre SERVEUR donné (le pire : le bon candidat en
 * dernier) ; le document contient le préfixe déjà tapé ; on déclenche
 * {@link EditorView#refreshCompletion()} et on lit l'ordre AFFICHÉ
 * ({@code view.completion.items}). Le filtrage local du popup doit
 * re-classer, pas seulement filtrer.</p>
 *
 * <p>Rouge avant (constats d'audit) : les paliers comparaient le libellé
 * ENTIER (un libellé de fonction « Text() » n'était jamais « exact » pour
 * « Text » tapé), aucun groupe ne démotions les mots-clés à palier égal,
 * et le palier flou mélangeait camel humps et sous-séquences
 * quelconques.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionRankingTiersTest {

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
    private static CompletionSession.Item method(String label, int score) {
        return new CompletionSession.Item(label, "", label, "m", 2, score, false, false);
    }

    /** Item de type résolu (classe, kind LSP 7). */
    private static CompletionSession.Item type(String label, int score) {
        return new CompletionSession.Item(label, "", label, "c", 7, score, false, false);
    }

    /** Item mot-clé (kind LSP 14, isKeyword). */
    private static CompletionSession.Item keyword(String label, int score) {
        return new CompletionSession.Item(label, "keyword", label, "k", 14, score, true, false);
    }

    /** Item snippet (kind LSP 15). */
    private static CompletionSession.Item snippet(String label, int score) {
        return new CompletionSession.Item(label, "snippet", label, "s", 15, score, false, false);
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
        // Laisse passer une éventuelle livraison concurrente de la lane.
        Thread.sleep(60);
    }

    private static List<String> displayedLabels(EditorView view) {
        List<String> out = new ArrayList<>();
        for (CompletionSession.Item item : view.completion.items) {
            out.add(item.label);
        }
        return out;
    }

    private static void assertFirst(EditorView view, String expected) {
        List<String> labels = displayedLabels(view);
        assertTrue("le candidat « " + expected + " » doit être affiché (reçu : " + labels + ")",
                labels.contains(expected));
        assertEquals("« " + expected + " » doit être EN TÊTE (reçu : " + labels + ")",
                expected, labels.get(0));
    }

    // ── Paliers : l'identifiant de tête, pas le libellé entier ──────

    @Test
    public void palier_prefixeText_fonctionTextEnTete() throws Exception {
        // Ordre serveur volontairement défavorable : le bon candidat en
        // dernier. « Text() » (identifiant de tête « Text ») doit passer
        // devant « TextField() » et « TextView » : pour « Text » tapé,
        // l'identifiant EST le préfixe — exact —, les autres ne sont que
        // des préfixes.
        EditorView view = viewWithDocAndProvider("Text", Arrays.asList(
                method("TextField()", 3),
                type("TextView", 2),
                method("Text()", 1)));
        view.refreshCompletion();
        awaitPopup(view);
        assertFirst(view, "Text()");
    }

    @Test
    public void palier_prefixeFoo_fonctionFooEnTetePuisFooInsensibleCasse() throws Exception {
        // « foo() » (exact sur l'identifiant) avant « Foo » (exact à la
        // casse près) avant « fooBar() » (simple préfixe).
        EditorView view = viewWithDocAndProvider("foo", Arrays.asList(
                method("fooBar()", 3),
                type("Foo", 2),
                method("foo()", 1)));
        view.refreshCompletion();
        awaitPopup(view);
        List<String> labels = displayedLabels(view);
        assertEquals(Arrays.asList("foo()", "Foo", "fooBar()"), labels);
    }

    // ── Groupes : symbole résolu avant mot-clé/snippet ──────────────

    @Test
    public void groupe_symboleResoluAvantMotCleAMemePalier() throws Exception {
        // « finish() » (méthode résolue) et « final » (mot-clé) sont tous
        // deux des préfixes de « fin » — même palier. Le mot-clé arrive
        // EN PREMIER côté serveur : il ne doit jamais passer devant le
        // symbole résolu.
        EditorView view = viewWithDocAndProvider("fin", Arrays.asList(
                keyword("final", 3),
                method("finish()", 2)));
        view.refreshCompletion();
        awaitPopup(view);
        assertFirst(view, "finish()");
    }

    @Test
    public void groupe_snippetDerriereSymboleResoluAMemePalier() throws Exception {
        // « formatTable » (snippet) et « format() » (méthode) : même
        // palier préfixe pour « format » — la méthode d'abord.
        EditorView view = viewWithDocAndProvider("format", Arrays.asList(
                snippet("formatTable", 3),
                method("format()", 2)));
        view.refreshCompletion();
        awaitPopup(view);
        assertFirst(view, "format()");
    }

    @Test
    public void groupe_motCleTapeEnEntierResteEnTeteDeSonPalier() throws Exception {
        // « if » tapé en entier : le mot-clé « if » est exact (palier le
        // plus haut) — il reste devant « iface() » (simple préfixe) et
        // devant le snippet « if (…) » (exact lui aussi sur
        // l'identifiant de tête, mais non résolu).
        EditorView view = viewWithDocAndProvider("if", Arrays.asList(
                method("iface()", 3),
                snippet("if (…)", 2),
                keyword("if", 1)));
        view.refreshCompletion();
        awaitPopup(view);
        assertFirst(view, "if");
    }

    // ── Camel humps avant sous-séquence quelconque ──────────────────

    @Test
    public void camelHumps_initialesAvantSousSequenceQuelconque() throws Exception {
        // « LLM » : « LazyListModifier » (initiales de mots — camel
        // humps) doit remonter devant « parallelStream » (l, l, m en
        // sous-séquence quelconque, aucune initiale). Ordre serveur
        // inversé pour prouver le re-classement.
        EditorView view = viewWithDocAndProvider("LLM", Arrays.asList(
                type("parallelStream", 3),
                type("LazyListModifier", 2)));
        view.refreshCompletion();
        awaitPopup(view);
        assertFirst(view, "LazyListModifier");
    }

    @Test
    public void camelHumps_runContiguEgalAuxInitiales() throws Exception {
        // « gS » sur « getString » : camel hump pur. « gS » sur
        // « gameStart » aussi. Les deux sont des humps — l'ordre serveur
        // est conservé (tri stable à l'intérieur du palier).
        EditorView view = viewWithDocAndProvider("gS", Arrays.asList(
                method("gameStart()", 2),
                method("getString()", 1)));
        view.refreshCompletion();
        awaitPopup(view);
        List<String> labels = displayedLabels(view);
        assertEquals("à palier égal (camel humps), l'ordre serveur est conservé",
                Arrays.asList("gameStart()", "getString()"), labels);
    }
}
