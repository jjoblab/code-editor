package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.util.List;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * 4.8.0 — le plancher local de complétion survit au serveur.
 *
 * <p>Avant : brancher un fournisseur (hôte ou LSP) REMPLAÇAIT la base
 * intégrée — les mots-clés et snippets du profil disparaissaient des
 * candidats, et un serveur pauvre faisait régresser la complétion par
 * rapport au mode sans serveur. Désormais, une réponse serveur NON
 * VIDE est FUSIONNÉE avec la base intégrée du langage (mots-clés +
 * snippets du profil), dédupliquée par libellé — le candidat serveur,
 * plus riche (documentation, détail, container), gagne le conflit.
 * Une réponse serveur VIDE est RESPECTÉE : pas de plancher sur un
 * « rien ici » délibéré (après un point, une arobase, un import) —
 * le bruit y serait pire que le vide.</p>
 *
 * <p>Bascule publique setMergeBuiltinCompletionsEnabled (défaut VRAI)
 * pour retrouver le comportement historique exclusif.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionMergeBuiltinTest {

    // ── Montage — session JAVA (le plancher = mots-clés du profil) ──

    private EditorView viewWithProvider(Function<String, CompletionResult> script) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("obj fo"));
        session.setLanguage("java");
        session.setSelection("obj ".length());
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

    private static CompletionResult result(String... labels) {
        List<CompletionSession.Item> items = new java.util.ArrayList<>();
        for (String label : labels) {
            items.add(new CompletionSession.Item(label, "", label, "m", 2, 1, false, false));
        }
        return new CompletionResult(items, false);
    }

    private static void type(EditorView view, String inserted) {
        int caret = view.session.getSelection().start;
        view.session.replaceRange(caret, caret, inserted);
        view.session.setSelection(caret + inserted.length());
        view.refreshCompletion();
    }

    private static List<String> displayedLabels(EditorView view) {
        List<String> out = new java.util.ArrayList<>();
        for (CompletionSession.Item item : view.completion.items) {
            out.add(item.label);
        }
        return out;
    }

    private static void awaitCondition(long ms, java.util.function.BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + ms;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("condition jamais atteinte (affiché : "
                        + displayedLabels(lastView) + ")");
            }
            Thread.sleep(20);
        }
    }

    private static EditorView lastView;

    // ── 1. Le plancher survit au serveur ────────────────────────────

    @Test
    public void lePlancherLocalSurvitAuServeur() throws Exception {
        EditorView view = viewWithProvider(prefix ->
                result("fooBar"));
        lastView = view;
        type(view, "fo");
        awaitCondition(5000, () -> displayedLabels(view).contains("fooBar"));
        // Le mot-clé « for » du profil java DOIT côtoyer le candidat
        // serveur — c'est le plancher jamais perdu.
        assertTrue("le mot-clé du profil survit au fournisseur (affiché : "
                        + displayedLabels(view) + ")",
                displayedLabels(view).contains("for"));
    }

    // ── 2. Déduplication par libellé — le serveur gagne ────────────

    @Test
    public void deduplicationParLibelle_leServeurGagne() throws Exception {
        AtomicInteger serverServed = new AtomicInteger();
        EditorView view = viewWithProvider(prefix -> {
            serverServed.incrementAndGet();
            // Le serveur sert « public » (aussi un mot-clé java).
            return result("public", "publicApi");
        });
        lastView = view;
        type(view, "pu");
        awaitCondition(5000, () -> displayedLabels(view).contains("publicApi"));
        int count = 0;
        for (String label : displayedLabels(view)) {
            if ("public".equals(label)) count++;
        }
        assertEquals("« public » apparaît UNE fois (le candidat serveur,"
                + " plus riche, gagne le conflit)", 1, count);
    }

    // ── 3. Serveur vide = silence respecté ─────────────────────────

    @Test
    public void serveurVide_reserveLeSilence() throws Exception {
        EditorView view = viewWithProvider(prefix -> result());
        lastView = view;
        type(view, "fo");
        // Le fournisseur a délibérément répondu « rien ici » : pas de
        // plancher — le popup ne gonfle pas de mots-clés hors contexte.
        Thread.sleep(300);
        assertFalse("un serveur vide est respecté (popup fermé)",
                view.completion.visible);
        assertTrue(displayedLabels(view).isEmpty());
    }

    // ── 4. Bascule off : comportement historique exclusif ──────────

    @Test
    public void basculeOff_retrouveLeComportementHistorique() throws Exception {
        EditorView view = viewWithProvider(prefix -> result("fooBar"));
        view.setMergeBuiltinCompletionsEnabled(false);
        lastView = view;
        type(view, "fo");
        awaitCondition(5000, () -> displayedLabels(view).contains("fooBar"));
        assertFalse("bascule fermée : le mot-clé du profil ne revient pas"
                        + " (affiché : " + displayedLabels(view) + ")",
                displayedLabels(view).contains("for"));
    }
}
