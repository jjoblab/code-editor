package jo.codeeditor.view;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jo.codeeditor.completion.CompletionSession;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Les caractères déclencheurs de complétion posés par l'HÔTE
 * ({@code setCompletionTriggerCharacters}, 4.12.0) — la voie du moteur
 * maison sans langage LSP branché.
 *
 * <p>Le popup ne s'ouvre à PRÉFIXE VIDE qu'après un caractère
 * déclencheur ANNONCÉ : historiquement, seuls les déclencheurs des
 * capacités du serveur LSP ({@code language.getCompletionProvider()
 * .getTriggerCharacters()}) étaient consultés — le moteur maison
 * ({@code setCompletionProvider} direct, sans langage) ne pouvait pas
 * annoncer « . », « ( », « , ». Désormais la vue porte la liste de
 * l'hôte, consultée quand aucun langage n'est branché.</p>
 *
 * <p>Rouge avant : {@code Couleur.} (préfixe vide, point frappé) ne
 * déclenchait RIEN sans serveur. Vert après : le fournisseur de l'hôte
 * est interrogé immédiatement.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class HostTriggerCharactersTest {

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

    /** Provider compteur : chaque provide() est compté. */
    private AtomicInteger installCountingProvider(EditorView view) {
        final AtomicInteger calls = new AtomicInteger();
        view.setCompletionProvider((text, caret, tokenStart, prefix) -> {
            calls.incrementAndGet();
            List<CompletionSession.Item> out = new java.util.ArrayList<>();
            out.add(new CompletionSession.Item("ROUGE", "constante",
                    "ROUGE", "v", 20, 80, false, false));
            return out;
        });
        return calls;
    }

    /**
     * Sans déclencheurs posés (comportement historique) : « Couleur. »
     * à préfixe vide ne déclenche AUCUNE requête — le popup serait
     * rejeté avant d'interroger le fournisseur.
     */
    @Test
    public void sansDéclencheursHôte_lePointNeDéclenchePas() throws Exception {
        String doc = "class A { void t() { Couleur.";
        EditorView view = newViewWithDoc(doc, doc.length());
        AtomicInteger calls = installCountingProvider(view);

        view.refreshCompletion();

        // Fenêtre d'absence concluante (le chemin vert déclenche en
        // ~0 ms — immediate — et le debounce de repli est de 80 ms).
        Thread.sleep(400);
        assertEquals("Aucune requête sans déclencheur annoncé", 0, calls.get());
    }

    /**
     * Avec « . » posé par l'hôte : « Couleur. » à préfixe vide
     * interroge le fournisseur IMMÉDIATEMENT (voie immediate, sans
     * debounce) — la porte d'entrée des membres d'enum de la voie
     * maison.
     */
    @Test
    public void avecLePointPosé_laRequêtePartImmédiatement() throws Exception {
        String doc = "class A { void t() { Couleur.";
        EditorView view = newViewWithDoc(doc, doc.length());
        AtomicInteger calls = installCountingProvider(view);
        view.setCompletionTriggerCharacters(Arrays.asList(".", "(", ","));

        view.refreshCompletion();

        // La voie caractère déclencheur est IMMÉDIATE : la requête part
        // sans attendre le debounce — on avance le looper principal
        // (Robolectric en pause) pour laisser la livraison arriver.
        long fin = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < fin
                && (calls.get() == 0 || !view.completion.visible)) {
            org.robolectric.Shadows.shadowOf(
                    android.os.Looper.getMainLooper()).runToEndOfTasks();
            if (calls.get() == 0) {
                Thread.sleep(20);
            }
        }
        assertTrue("Le point de l'hôte doit déclencher la requête",
                calls.get() >= 1);
        // Le popup est ouvert avec le candidat servi.
        assertTrue("Le popup de complétion est visible",
                view.completion.visible);
        assertFalse("La liste n'est pas vide",
                view.completion.items.isEmpty());
    }

    /**
     * La parenthèse et la virgule aussi : « label( » et « label(a, » à
     * préfixe vide interrogent le fournisseur (arguments nommés,
     * attributs d'annotation de la voie maison).
     */
    @Test
    public void parenthèseEtVirgule_déclenchentAussi() throws Exception {
        String doc = "class A { void t() { label(";
        EditorView view = newViewWithDoc(doc, doc.length());
        AtomicInteger calls = installCountingProvider(view);
        view.setCompletionTriggerCharacters(Arrays.asList(".", "(", ","));

        view.refreshCompletion();
        long fin = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < fin && calls.get() == 0) {
            Thread.sleep(20);
        }
        assertTrue("La parenthèse ouvrante déclenche", calls.get() >= 1);

        // La virgule : caret juste après elle (pas d'espace).
        String doc2 = "class A { void t() { label(a,";
        EditorView view2 = newViewWithDoc(doc2, doc2.length());
        AtomicInteger calls2 = installCountingProvider(view2);
        view2.setCompletionTriggerCharacters(Arrays.asList(".", "(", ","));
        view2.refreshCompletion();
        long fin2 = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < fin2 && calls2.get() == 0) {
            Thread.sleep(20);
        }
        assertTrue("La virgule déclenche", calls2.get() >= 1);
    }

    /**
     * Un caractère qui n'est PAS dans la liste ne déclenche pas :
     * « ; » à préfixe vide reste silencieux.
     */
    @Test
    public void caractèreHorsListe_neDéclenchePas() throws Exception {
        String doc = "class A { void t() { f();";
        EditorView view = newViewWithDoc(doc, doc.length());
        AtomicInteger calls = installCountingProvider(view);
        view.setCompletionTriggerCharacters(Arrays.asList(".", "(", ","));

        view.refreshCompletion();
        Thread.sleep(400);
        assertEquals("Le point-virgule n'est pas un déclencheur", 0, calls.get());
    }

    /**
     * Le retrait de la liste (null) rétablit le silence — la voie hôte
     * est nettoyable au détachement de l'analyseur maison.
     */
    @Test
    public void retraitDesDéclencheurs_rétablitLeSilence() throws Exception {
        String doc = "class A { void t() { Couleur.";
        EditorView view = newViewWithDoc(doc, doc.length());
        AtomicInteger calls = installCountingProvider(view);
        view.setCompletionTriggerCharacters(Collections.singletonList("."));
        view.setCompletionTriggerCharacters(null);

        view.refreshCompletion();
        Thread.sleep(400);
        assertEquals("Plus de déclencheur : plus de requête", 0, calls.get());
        assertEquals("La liste hôte est vide", 0,
                view.hostCompletionTriggerCharacters().size());
    }
}
