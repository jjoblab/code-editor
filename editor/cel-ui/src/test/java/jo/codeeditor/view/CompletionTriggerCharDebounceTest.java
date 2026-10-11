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
import java.util.concurrent.atomic.AtomicInteger;

import jo.codeeditor.lang.Analyzer;
import jo.codeeditor.lang.BracketMatch;
import jo.codeeditor.lang.model.CodeBlock;
import jo.codeeditor.lang.provider.CompletionPublisher;
import jo.codeeditor.lang.provider.CompletionProvider;
import jo.codeeditor.lang.Language;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import static org.junit.Assert.*;

/**
 * Vérifie que la saisie d'un CARACTÈRE DÉCLENCHEUR (ex : ".") déclenche
 * la requête moteur IMMÉDIATEMENT (immediate=true dans
 * {@link EditorPopupManager#scheduleAsyncFetch}), SANS attendre le
 * debounce de 80 ms.
 *
 * <p>Le chemin « préfixe vide + char déclencheur » ne doit surtout pas
 * passer par le debounce : combiné au {@code flushPendingChange()} SYNCHRONE,
 * chaque "." subirait sinon un retard perceptible.</p>
 *
 * <p>Stratégie de test : un faux {@link CompletionProvider} compte ses
 * appels via un {@link CountDownLatch}. On déclenche
 * {@link EditorView#refreshCompletion()} (package-private) sur un document
 * dont le caret suit un "." (préfixe vide + char déclencheur).</p>
 *
 * <ol>
 *   <li>Test 1 (trigger char) : le latch tombe en moins de 50 ms SANS
 *       avancer le main looper — preuve que {@code immediate=true} a été
 *       pris.</li>
 *   <li>Test 2 (non-trigger char, base cache vide) : le latch finit par
 *       tomber via le chemin debouncé dans un délai raisonnable.</li>
 * </ol>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class CompletionTriggerCharDebounceTest {

    /** Document : "import android." — caret juste après le "." (préfixe vide). */
    private static final String DOC_TRIGGER = "import android.";

    /** Document : "import android.a" — caret après le "a" (préfixe non vide
     * ET base cache vide → chemin debouncé). */
    private static final String DOC_NON_TRIGGER = "import android.a";

    private EditorView newViewWithLanguage(String doc, int caretOffset) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc));
        session.setSelection(caretOffset);
        view.setSession(session);
        // Installe un vrai Language dont le CompletionProvider SPI a "."
        // comme char déclencheur. L'appel à setLanguage() installe
        // automatiquement l'adaptateur view.completionProvider qui délègue
        // au provider SPI — le pipeline de complétion existant
        // (EditorPopupManager) consomme view.completionProvider de façon
        // transparente.
        view.setLanguage(new Language() {
            @Override
            public Analyzer getAnalyzer() {
                return new Analyzer() {
                    @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver r) {}
                    @Override public void onReplace(CharSequence t, int s, int e, CharSequence i) {}
                    @Override public void reset(CharSequence t) {}
                    @Override public StyledLine styledLine(int line) { return null; }
                    @Override public List<CodeBlock> computeBlocks() {
                        return Collections.emptyList();
                    }
                    @Override public BracketMatch computeBracketMatch(int o) { return null; }
                    @Override public void destroy() {}
                };
            }
            @Override
            public CompletionProvider getCompletionProvider() {
                return new CompletionProvider() {
                    @Override
                    public void complete(CharSequence text, int caret,
                                          CompletionPublisher publisher) {
                        // No-op pour le test du char déclencheur — l'adaptateur
                        // SPI renverra une liste vide. La vraie assertion est
                        // faite via le provider compteur installé par le test
                        // ci-dessous (voir installCountingProvider).
                    }
                    @Override
                    public List<String> getTriggerCharacters() {
                        return Collections.singletonList(".");
                    }
                };
            }
            @Override public int getInterruptionLevel() { return INTERRUPTION_LEVEL_NONE; }
            @Override public void destroy() {}
        });
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        return view;
    }

    /**
     * Installe un provider de complétion compteur sur la vue. Renvoie le
     * latch qui sera décompté à l'appel de {@code provide()}.
     *
     * <p>On remplace {@code view.completionProvider} (installé
     * automatiquement par setLanguage) par notre propre compteur — le
     * launchFetch d'EditorPopupManager passe par
     * {@code view.completionProvider.provide(...)}, pas directement par
     * l'adaptateur SPI.</p>
     */
    private CountDownLatch installCountingProvider(EditorView view) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger callCount = new AtomicInteger(0);
        view.setCompletionProvider((text, caret, tokenStart, prefix) -> {
            callCount.incrementAndGet();
            latch.countDown();
            return Collections.emptyList();
        });
        return latch;
    }

    @Test
    public void triggerChar_firesImmediatelyWithoutDebounceAdvance() throws Exception {
        EditorView view = newViewWithLanguage(DOC_TRIGGER, DOC_TRIGGER.length());
        // Utilise un latch temporel pour vérifier que provide() est appelé
        // AVANT que la fenêtre de debounce (80 ms) n'ait pu se déclencher.
        // Robolectric auto-idle le main looper en mode LEGACY, donc une
        // assertion stricte « avant tout advance » n'est pas fiable — mais
        // le chemin du char déclencheur prend la branche immediate=true :
        // launchFetch est invoqué SYNCHRONEMENT par scheduleAsyncFetch, et
        // COMPLETION_EXECUTOR (un vrai thread d'arrière-plan) le prend en
        // charge en quelques microsecondes. provide() sera donc appelé
        // en < 50 ms (bien sous le debounce de 80 ms).
        CountDownLatch latch = installCountingProvider(view);

        // ── ACT : refreshCompletion() avec préfixe VIDE + prev char "." ──
        view.refreshCompletion();

        // ── ASSERT : le latch doit tomber en < 50 ms (très inférieur
        // au debounce de 80 ms) — preuve que immediate=true a été pris.
        boolean countedDown = latch.await(50, TimeUnit.MILLISECONDS);
        assertTrue(
            "Le char déclencheur '.' doit déclencher provide() en < 50 ms "
            + "(immediate=true dans scheduleAsyncFetch). Le debounce normal "
            + "de 80 ms aurait pris AU MOINS 80 ms via postDelayed.",
            countedDown);
    }

    @Test
    public void nonTriggerChar_eventuallyFiresViaDebounce() throws Exception {
        // Robolectric auto-idle le main looper, donc on ne peut pas
        // affirmer strictement « pas appelé avant l'avance du debounce ».
        // On vérifie donc le cas positif : le provide() d'un char non
        // déclencheur finit par être appelé (via le chemin debouncé) dans
        // un délai raisonnable. Le test du char déclencheur ci-dessus
        // prouve que immediate=true est pris ; celui-ci confirme simplement
        // que le chemin non-déclencheur atteint aussi provide().
        EditorView view = newViewWithLanguage(DOC_NON_TRIGGER, DOC_NON_TRIGGER.length());
        CountDownLatch latch = installCountingProvider(view);

        view.refreshCompletion();

        // Les 2 s couvrent le debounce de 80 ms + la répartition vers le
        // thread COMPLETION_EXECUTOR + l'exécution de provide().
        boolean countedDown = latch.await(2, TimeUnit.SECONDS);
        assertTrue(
            "Le préfixe non-vide 'a' (sans char déclencheur précédent) doit "
            + "EVENTUELLEMENT déclencher provide() via le debounce de 80 ms.",
            countedDown);
    }
}
