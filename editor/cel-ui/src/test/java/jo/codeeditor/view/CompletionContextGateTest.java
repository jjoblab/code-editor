package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * Lot 3 (audit v3.41.0) — la complétion ne doit PAS s'ouvrir
 * quand le caret est à l'intérieur d'une chaîne, d'un commentaire
 * (ligne ou bloc) ou d'un fichier de log. Avant le correctif, le
 * popup s'ouvrait dans tous ces contextes — clignotement
 * intempestif pendant la frappe dans une chaîne, fausses
 * propositions dans un commentaire, popup parasite dans un log.
 *
 * <p>Stratégie de test : un faux provider compteur enregistre
 * l'appel à {@code provide()}. Si le latch tombe, le popup a
 * demandé une complétion ; s'il ne tombe pas, le context gate a
 * rejeté la demande. On teste quatre contextes non-code :</p>
 * <ul>
 *   <li>Caret dans une chaîne littérale : pas de complétion.</li>
 *   <li>Caret dans un commentaire de ligne {@code // …} : pas
 *       de complétion.</li>
 *   <li>Caret dans un commentaire de bloc {@code /* … *\/} : pas
 *       de complétion.</li>
 *   <li>Fichier de log (langage « log ») : pas de complétion.</li>
 * </ul>
 *
 * <p>Plus un cas positif : caret dans du code Java normal — la
 * complétion se déclenche (régression).</p>
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionContextGateTest {

    private EditorView newViewWithDoc(String doc, int caretOffset) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setSelection(caretOffset);
        session.setLanguage("java");
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    /**
     * Installe un provider compteur qui décompte le latch à chaque
     * appel à {@code provide()}.
     */
    private CountDownLatch installCountingProvider(EditorView view) {
        final CountDownLatch latch = new CountDownLatch(1);
        view.setCompletionProvider((text, caret, tokenStart, prefix) -> {
            latch.countDown();
            return Collections.emptyList();
        });
        return latch;
    }

    /**
     * Caret dans une chaîne littérale Java : pas de complétion.
     */
    @Test
    public void completion_notTriggeredInString() throws Exception {
        // "abc|def" — caret au milieu d'une chaîne non fermée.
        String doc = "String s = \"abc";
        // Position à l'intérieur de la chaîne (après "abc).
        int caret = doc.length();
        EditorView view = newViewWithDoc(doc, caret);
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        boolean triggered = latch.await(200, TimeUnit.MILLISECONDS);
        assertFalse(
            "La complétion ne doit PAS se déclencher dans une chaîne littérale.",
            triggered);
    }

    /**
     * Caret dans un commentaire de ligne Java : pas de complétion.
     */
    @Test
    public void completion_notTriggeredInLineComment() throws Exception {
        // // un comment|aire — caret dans un commentaire de ligne.
        String doc = "// un commentaire avec un mot";
        int caret = 15; // au milieu du commentaire
        EditorView view = newViewWithDoc(doc, caret);
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        boolean triggered = latch.await(200, TimeUnit.MILLISECONDS);
        assertFalse(
            "La complétion ne doit PAS se déclencher dans un commentaire de ligne.",
            triggered);
    }

    /**
     * Caret dans un commentaire de bloc Java : pas de complétion.
     */
    @Test
    public void completion_notTriggeredInBlockComment() throws Exception {
        // /* un comment|aire de bloc */
        String doc = "/* un commentaire de\n   bloc sur plusieurs lignes\n*/\ncode";
        int caret = 30; // au milieu du commentaire de bloc
        EditorView view = newViewWithDoc(doc, caret);
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        boolean triggered = latch.await(200, TimeUnit.MILLISECONDS);
        assertFalse(
            "La complétion ne doit PAS se déclencher dans un commentaire de bloc.",
            triggered);
    }

    /**
     * Fichier de log (langage « log ») : pas de complétion.
     */
    @Test
    public void completion_notTriggeredInLogFile() throws Exception {
        String doc = "2026-10-11 INFO Starting something\n";
        EditorView view = newViewWithDoc(doc, doc.length());
        view.getSession().setLanguage("log");
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        boolean triggered = latch.await(200, TimeUnit.MILLISECONDS);
        assertFalse(
            "La complétion ne doit PAS se déclencher dans un fichier de log.",
            triggered);
    }

    /**
     * Régression : caret dans du code Java normal — la complétion
     * se déclenche. La non-régression détaillée est couverte par
     * les tests existants (CompletionTriggerCharDebounceTest).
     * Ici on ne fait que vérifier que le context gate ne rejette
     * pas le code Java normal : le caret est hors chaîne et hors
     * commentaire, donc isInStringOrComment renvoie false, et le
     * gate laisse passer. On utilise un préfixe non-vide (sans
     * char déclencheur) pour éviter de dépendre des
     * triggerCharacters de la SPI Language.
     */
    @Test
    public void completion_triggeredInCode_regression() throws Exception {
        // Code Java sans chaîne ni commentaire autour du caret,
        // préfixe non vide "Buf" pour déclencher le chemin debouncé.
        String doc = "import java.io.Buf";
        EditorView view = newViewWithDoc(doc, doc.length());
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        // Le code Java normal doit déclencher la complétion. On
        // attend 2 s (couvre le debounce de 80 ms + thread).
        boolean triggered = latch.await(2, TimeUnit.SECONDS);
        assertTrue(
            "La complétion doit se déclencher dans du code Java normal "
            + "(caret hors chaîne/commentaire, langage java).",
            triggered);
    }
}
