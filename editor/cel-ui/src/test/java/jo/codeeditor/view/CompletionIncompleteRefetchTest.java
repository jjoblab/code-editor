package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * Fiabilité du popup sur les listes TRONQUÉES : un fournisseur qui
 * plafonne ses réponses signale la troncature (drapeau
 * {@link CompletionResult#isIncomplete}) ; le popup doit alors
 * re-requêter à chaque édition du préfixe — prolongation ET
 * raccourcissement — au lieu de filtrer localement une base amputée.
 *
 * <p>Rouge avant (constat d'audit) : la garde du chemin rapide
 * comparait l'OFFSET de début de token ; tant que le début du token ne
 * bougeait pas, toute édition à l'intérieur du token (prolongation,
 * insertion au milieu, retour arrière) était traitée par filtrage local
 * de la base en cache, la réponse en vol était jetée et le serveur
 * n'était plus interrogé. Un candidat écarté par le plafond du backend
 * ne réapparaissait jamais — ni en prolongeant le préfixe, ni en le
 * raccourcissant (monotonie des correspondances : filtrer davantage ne
 * recrée pas ce qui a été retiré avant le filtre).</p>
 *
 * <p>Contrats testés :</p>
 * <ol>
 *   <li>troncature : la frappe suivante re-requête, le candidat manquant
 *       finit par s'afficher ;</li>
 *   <li>raccourcissement (retour arrière) : la base tronquée est
 *       fausse pour le préfixe plus court — re-requête, les candidats
 *       du préfixe court réapparaissent ;</li>
 *   <li>coalescence : une rafale de frappes pendant un vol n'empile pas
 *       plus d'une requête par token ; le résultat arrivé est
 *       refiltré contre le préfixe courant et une re-requête (au plus)
 *       part si le préfixe a évolué pendant le vol ;</li>
 *   <li>liste complète : aucune requête supplémentaire — filtrage local
 *       pur, réactif (comportement historique préservé).</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionIncompleteRefetchTest {

    private static final String DOC = "obj b";

    // ── Montage ─────────────────────────────────────────────────────

    private EditorView viewWithProvider(Function<String, CompletionResult> script) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        session.setSelection(DOC.length());
        view.setSession(session);
        view.setCompletionProvider(new EditorView.CompletionProvider() {
            @Override
            public List<CompletionSession.Item> provide(
                    String text, int caret, int tokenStart, String prefix) {
                return script.apply(prefix).items;
            }

            @Override
            public CompletionResult provideResult(
                    String text, int caret, int tokenStart, String prefix,
                    Character triggerCharacter) {
                return script.apply(prefix);
            }
        });
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    private static CompletionResult result(boolean incomplete, String... labels) {
        java.util.List<CompletionSession.Item> items = new java.util.ArrayList<>();
        for (String label : labels) {
            items.add(new CompletionSession.Item(label, "", label, "m", 2, 1, false, false));
        }
        return new CompletionResult(items, incomplete);
    }

    /** Simule une frappe à la fin du token courant puis rafraîchit. */
    private static void type(EditorView view, String inserted) {
        int caret = view.session.getSelection().start;
        if (inserted.isEmpty()) {
            // Retour arrière d'un caractère.
            view.session.replaceRange(caret - 1, caret, "");
            view.session.setSelection(caret - 1);
        } else {
            view.session.replaceRange(caret, caret, inserted);
            view.session.setSelection(caret + inserted.length());
        }
        view.refreshCompletion();
    }

    /** Attend que le popup affiche le libellé donné. */
    private static void awaitDisplayed(EditorView view, String label)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!displayedLabels(view).contains(label)) {
            if (System.currentTimeMillis() > deadline) {
                fail("« " + label + " » n'est jamais apparu (affiché : "
                        + displayedLabels(view) + ")");
            }
            Thread.sleep(20);
        }
    }

    /** Attend que le nombre d'appels au fournisseur atteigne n. */
    private static void awaitCalls(AtomicInteger calls, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (calls.get() < n) {
            if (System.currentTimeMillis() > deadline) {
                fail("le fournisseur n'a été appelé que " + calls.get() + " fois, " + n + " attendus");
            }
            Thread.sleep(20);
        }
    }

    private static List<String> displayedLabels(EditorView view) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (CompletionSession.Item item : view.completion.items) {
            out.add(item.label);
        }
        return out;
    }

    // ── 1. Troncature : la frappe suivante re-requête ───────────────

    @Test
    public void troncature_leCandidatManquantFinitParApparaitre() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EditorView view = viewWithProvider(prefix -> {
            calls.incrementAndGet();
            // Première frappe « b » : liste plafonnée SANS banana ;
            // frappe suivante « ba » : liste fraîche AVEC banana.
            return prefix.equals("b")
                    ? result(true, "bar", "baz")
                    : result(true, "bar", "baz", "banana");
        });

        view.refreshCompletion();
        awaitDisplayed(view, "baz");
        assertFalse("le candidat plafonné n'est pas dans la première base",
                displayedLabels(view).contains("banana"));

        type(view, "a"); // « b » → « ba » : même token, la base est tronquée

        awaitDisplayed(view, "banana");
        assertTrue("le fournisseur a été re-interrogé",
                calls.get() >= 2);
    }

    // ── 2. Raccourcissement : le retour arrière re-requête ──────────

    @Test
    public void raccourcissement_retourArriereReinterrogeLeFournisseur() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // Document de départ « obj ba » : la base du premier vol est
        // plafonnée à bar ; baz et banana matchent pourtant le préfixe
        // plus court « b ».
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("obj ba"));
        session.setSelection("obj ba".length());
        view.setSession(session);
        view.setCompletionProvider(new EditorView.CompletionProvider() {
            @Override
            public List<CompletionSession.Item> provide(
                    String text, int caret, int tokenStart, String prefix) {
                return result(true, "bar").items;
            }

            @Override
            public CompletionResult provideResult(
                    String text, int caret, int tokenStart, String prefix,
                    Character triggerCharacter) {
                calls.incrementAndGet();
                return prefix.equals("ba")
                        ? result(true, "bar")
                        : result(true, "bar", "baz", "banana");
            }
        });
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);

        view.refreshCompletion();
        awaitDisplayed(view, "bar");
        assertEquals("base du premier vol", 1, calls.get());

        type(view, ""); // retour arrière : « ba » → « b »

        awaitDisplayed(view, "banana");
        awaitDisplayed(view, "baz");
        assertTrue("le fournisseur a été re-interrogé après le retour arrière",
                calls.get() >= 2);
    }

    // ── 3. Coalescence : pas d'empilement pendant un vol ────────────

    @Test
    public void coalescence_rafaaleSurServeurLent_uneSeuleRequeteEnVol() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        EditorView view = viewWithProvider(prefix -> {
            calls.incrementAndGet();
            if (calls.get() == 1) {
                // Serveur lent : la première réponse attend qu'on la
                // libère — la rafale de frappes tombe pendant le vol.
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return result(true, "bar", "baz");
            }
            return result(true, "bar", "baz", "banana");
        });

        view.refreshCompletion(); // requête 1 en vol (bloquée)
        awaitCalls(calls, 1);

        type(view, "a"); // « b » → « ba » pendant le vol
        type(view, "n"); // « ba » → « ban » pendant le vol
        Thread.sleep(150);
        assertEquals("la rafale n'empile pas : toujours une seule requête en vol",
                1, calls.get());

        release.countDown(); // la réponse 1 arrive enfin

        // La livraison refiltre contre « ban » (rien ne matche), puis
        // UNE re-requête part (préfixe ayant évolué pendant le vol) —
        // pas une par frappe de la rafale.
        awaitDisplayed(view, "banana");
        assertTrue("au plus une re-requête après le vol (reçu : " + calls.get() + ")",
                calls.get() <= 2);
    }

    // ── 4. Liste complète : aucune requête supplémentaire ───────────

    @Test
    public void listeComplete_leFiltrageLocalNeRerequetePas() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        EditorView view = viewWithProvider(prefix -> {
            calls.incrementAndGet();
            return result(false, "bar", "baz"); // liste COMPLETE
        });

        view.refreshCompletion();
        awaitDisplayed(view, "bar");
        assertEquals(1, calls.get());

        type(view, "a"); // « b » → « ba » : filtrage local pur

        Thread.sleep(300); // le temps de prouver qu'aucune requête part
        assertEquals("une base complète ne déclenche aucune requête supplémentaire",
                1, calls.get());
        assertEquals(java.util.Arrays.asList("bar", "baz"), displayedLabels(view));
    }
}
