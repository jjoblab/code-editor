package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.lang.Analyzer;
import jo.codeeditor.lang.BracketMatch;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.model.CodeAction;
import jo.codeeditor.lang.model.CodeBlock;
import jo.codeeditor.lang.provider.CodeActionsProvider;
import jo.codeeditor.session.EditorSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * Les actions source sont sollicitées UNE FOIS PAR DOCUMENT avec le
 * filtre de famille transporté dans le contexte LSP ({@code only}) —
 * P2-10 : plus de sondage multi-lignes ni de filtre local
 * {@code kind.contains("source")}, la famille demandée part au serveur.
 */
@RunWith(RobolectricTestRunner.class)
public class EditorSourceActionsTest {

    private static final String DOC =
        "public class A {\n"
        + "    String s = foo;\n"
        + "}\n";

    /** Provider compteur : capture chaque appel et le {@code only} reçu. */
    private static final class Compteur implements CodeActionsProvider {
        final AtomicInteger appels = new AtomicInteger();
        final AtomicReference<List<String>> dernierOnly = new AtomicReference<>();
        final AtomicInteger appelsLigne = new AtomicInteger();
        volatile int réponseActions = 1;

        @Override
        public List<CodeAction> codeActions(CharSequence text, int line) {
            appelsLigne.incrementAndGet();
            return actions();
        }

        @Override
        public List<CodeAction> codeActions(CharSequence text, int caretOffset,
                int selectionStart, int selectionEnd, List<String> onlyKinds) {
            appels.incrementAndGet();
            dernierOnly.set(onlyKinds);
            return actions();
        }

        private List<CodeAction> actions() {
            List<CodeAction> out = new ArrayList<>();
            for (int i = 0; i < réponseActions; i++) {
                out.add(new CodeAction("Organiser les imports " + i,
                        "source.organizeImports", false, () -> { }));
            }
            return out;
        }
    }

    private EditorView newView(Compteur compteur) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(DOC));
        view.setSession(session);
        view.setLanguage(languageAvec(compteur));
        return view;
    }

    /** Langage factice dont le CodeActionsProvider est le compteur. */
    private static Language languageAvec(Compteur compteur) {
        return new Language() {
            @Override
            public Analyzer getAnalyzer() {
                return analyzer();
            }
            @Override
            public CodeActionsProvider getCodeActionsProvider() {
                return compteur;
            }
            @Override
            public int getInterruptionLevel() { return INTERRUPTION_LEVEL_NONE; }
            @Override
            public void destroy() {
            }
        };
    }

    /** Langage factice SANS provider d'actions. */
    private static Language languageSansProvider() {
        return new Language() {
            @Override
            public Analyzer getAnalyzer() {
                return analyzer();
            }
            @Override
            public int getInterruptionLevel() { return INTERRUPTION_LEVEL_NONE; }
            @Override
            public void destroy() {
            }
        };
    }

    private static Analyzer analyzer() {
        return new Analyzer() {
            @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver r) { }
            @Override public void onReplace(CharSequence t, int s, int e, CharSequence i) { }
            @Override public void reset(CharSequence t) { }
            @Override public StyledLine styledLine(int line) { return null; }
            @Override public List<CodeBlock> computeBlocks() {
                return Collections.emptyList();
            }
            @Override public BracketMatch computeBracketMatch(int o) { return null; }
            @Override public void destroy() { }
        };
    }

    /** Attend la fin de la résolution arrière-plan (voie « features »). */
    private static void attendreCompteur(Compteur compteur, int attendu) {
        long fin = System.currentTimeMillis() + 5000;
        while (compteur.appels.get() < attendu && System.currentTimeMillis() < fin) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Test
    public void organizeImports_uneSeuleRequêteAvecOnlyDeFamille() {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);
        view.performOrganizeImports(null);
        attendreCompteur(compteur, 1);
        assertEquals("UNE requête par document — pas de sondage multi-lignes",
                1, compteur.appels.get());
        assertEquals("la méthode ligne n'est plus sondée",
                0, compteur.appelsLigne.get());
        assertEquals("le filtre de famille part dans le contexte (only)",
                Collections.singletonList("source.organizeImports"),
                compteur.dernierOnly.get());
    }

    @Test
    public void fixAll_uneSeuleRequêteAvecOnlyFixAll() {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);
        view.performFixAll(null);
        attendreCompteur(compteur, 1);
        assertEquals("UNE requête par document", 1, compteur.appels.get());
        assertEquals("only = source.fixAll",
                Collections.singletonList("source.fixAll"),
                compteur.dernierOnly.get());
    }

    @Test
    public void sansProvider_leCallbackReçoitFalseSansRequête() {
        Compteur compteur = new Compteur();
        EditorView view = newView(compteur);
        view.setLanguage(languageSansProvider());
        final AtomicInteger réponses = new AtomicInteger();
        final AtomicReference<Boolean> résultat = new AtomicReference<>();
        view.performFixAll(b -> {
            réponses.incrementAndGet();
            résultat.set(b);
        });
        assertEquals("aucune requête sans provider", 0, compteur.appels.get());
        assertEquals("le callback est délivré immédiatement", 1, réponses.get());
        assertEquals(Boolean.FALSE, résultat.get());
    }
}
