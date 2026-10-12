package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.Language;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.util.EditorLogger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Frontières d'extension et gestion des erreurs (Lot 6) :
 *
 * <ul>
 *   <li><b>RuntimeException d'un provider</b> — avalée, journalisée via
 *       {@code EditorLog}, l'éditeur continue : la vue ne doit jamais
 *       planter à cause d'un fournisseur de langage défaillant ;</li>
 *   <li><b>Error</b> (mémoire saturée, pile débordée) — <b>jamais
 *       avalée</b> : l'ancien {@code catch (Throwable)} masquait les
 *      OutOfMemoryError/StackOverflowError ; les catch ciblés
 *       ({@code Exception}) les laissent remonter à l'hôte.</li>
 * </ul>
 *
 * <p>Sujet d'essai : {@code EditorCompletionPopup.triggerCharacters()} —
 * frontière synchrone appelant un provider SPI tiers.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class EditorErrorBoundaryTest {

    /** Journal témoin. */
    private static final class Recording implements EditorLogger {
        final List<String> events = new ArrayList<>();
        @Override public void info(String tag, String m) { }
        @Override public void warn(String tag, String m) {
            events.add("warn:" + tag);
        }
        @Override public void warn(String tag, String m, Throwable c) {
            events.add("warn:" + tag + ":" + c.getClass().getSimpleName());
        }
        @Override public void error(String tag, String m, Throwable c) {
            events.add("error:" + tag);
        }
    }

    private static RuntimeException runtime(String m) { return new IllegalStateException(m); }
    private static OutOfMemoryError oom(String m) { return new OutOfMemoryError(m); }

    /** Analyzer minimal (non utilisé par le chemin testé). */
    private static final jo.codeeditor.lang.Analyzer ANALYseur_FICTIF =
            new jo.codeeditor.lang.Analyzer() {
                @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver r) { }
                @Override public void onReplace(CharSequence t, int s, int e, CharSequence i) { }
                @Override public void reset(CharSequence t) { }
                @Override public jo.codeeditor.highlight.StyledLine styledLine(int line) { return null; }
                @Override public List<jo.codeeditor.lang.model.CodeBlock> computeBlocks() {
                    return java.util.Collections.emptyList();
                }
                @Override public jo.codeeditor.lang.BracketMatch computeBracketMatch(int offset) {
                    return null;
                }
                @Override public void destroy() { }
            };

    private EditorView newView(Language lang) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of("int x = 1;\n"));
        view.setSession(session);
        view.setLanguage(lang);
        return view;
    }

    private EditorCompletionPopup popup(EditorView view) {
        // Constructeur package-private : le test vit dans le package.
        return new EditorCompletionPopup(view, view.popupManager);
    }

    private List<String> triggerCharacters(EditorCompletionPopup p) throws Exception {
        Method m = EditorCompletionPopup.class.getDeclaredMethod("triggerCharacters");
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> r = (List<String>) m.invoke(p);
        return r;
    }

    @Test
    public void providerQuiLeveRuntime_vueContinueEtJournalise() throws Exception {
        Recording log = new Recording();
        jo.codeeditor.util.EditorLog.install(log);
        try {
            // Language dont le provider lève IllegalStateException :
            Language lang = new Language() {
                @Override public jo.codeeditor.lang.Analyzer getAnalyzer() { return ANALYseur_FICTIF; }
                @Override public void destroy() { }
                @Override public int getInterruptionLevel() { return 0; }
                @Override public jo.codeeditor.lang.provider.CompletionProvider getCompletionProvider() {
                    return new jo.codeeditor.lang.provider.CompletionProvider() {
                        @Override public void complete(CharSequence t, int c,
                                jo.codeeditor.lang.provider.CompletionPublisher p) { }
                        @Override public List<String> getTriggerCharacters() {
                            throw runtime("provider défaillant");
                        }
                    };
                }
            };
            EditorView view = newView(lang);
            List<String> chars = triggerCharacters(popup(view));
            // L'éditeur continue : repli sur « aucun caractère déclencheur ».
            assertTrue(chars.isEmpty());
            // L'anomalie est JOURNALISÉE, plus avalée en silence.
            assertFalse(log.events.isEmpty());
            assertTrue(log.events.get(0).contains("IllegalStateException"));
        } finally {
            jo.codeeditor.util.EditorLog.install(null);
        }
    }

    @Test
    public void providerQuiLeveOOM_errorNonAvalee() throws Exception {
        jo.codeeditor.util.EditorLog.install(new Recording());
        try {
            Language lang = new Language() {
                @Override public jo.codeeditor.lang.Analyzer getAnalyzer() { return ANALYseur_FICTIF; }
                @Override public void destroy() { }
                @Override public int getInterruptionLevel() { return 0; }
                @Override public jo.codeeditor.lang.provider.CompletionProvider getCompletionProvider() {
                    return new jo.codeeditor.lang.provider.CompletionProvider() {
                        @Override public void complete(CharSequence t, int c,
                                jo.codeeditor.lang.provider.CompletionPublisher p) { }
                        @Override public List<String> getTriggerCharacters() {
                            throw oom("saturation simulée");
                        }
                    };
                }
            };
            EditorView view = newView(lang);
            try {
                // L'OutOfMemoryError doit SE PROPAGER — jamais être avalée
                // par un catch (Throwable) : l'hôte doit la voir.
                triggerCharacters(popup(view));
                fail("OutOfMemoryError attendue en propagation");
            } catch (InvocationTargetException e) {
                assertTrue("la cause doit être l'OOM, pas autre chose : " + e.getCause(),
                        e.getCause() instanceof OutOfMemoryError);
            }
        } finally {
            jo.codeeditor.util.EditorLog.install(null);
        }
    }
}
