package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import android.content.Context;
import android.os.Looper;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.lang.Analyzer;
import jo.codeeditor.lang.Language;
import jo.codeeditor.lang.model.CodeBlock;
import jo.codeeditor.lang.provider.InlayHintProvider;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * I5 (P2, lot 2 jalon J6) — la <b>plage visible + re-requête au scroll
 * idle</b> pour les providers exigeants (serveurs externes) :
 *
 * <ul>
 *   <li><b>Drapeau à vrai</b> — la requête porte la fenêtre viewport
 *       ± 4 lignes (PAS le document entier) ; après un scroll, ~200 ms
 *       de calme déclenchent une RE-REQUÊTE portant la nouvelle
 *       fenêtre (latest-wins par génération) ;</li>
 *   <li><b>Drapeau à faux (défaut)</b> — document ENTIER, aucun
 *       changement de comportement, le scroll ne re-requête PAS ;</li>
 *   <li><b>Vue non mesurée</b> — repli pleine plage (getHeight()==0 :
 *       la fenêtre serait fausse et jamais corrigée sans scroll).</li>
 * </ul>
 *
 * <p>NOTE Robolectric : fontes legacy → FontMetrics nuls ; métriques
 * déterministes injectées par réflexion (pattern
 * {@code InlayTooltipLongPressTest}). La voie « features » est un vrai
 * pool — le test attend la livraison par sondage borné.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class InlayViewportRangeTest {

    /** 60 lignes de 20 caractères — bien plus qu'un viewport (48 l.). */
    private static String doc60() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("line ").append(i).append(" padding\n");
        }
        return sb.toString();
    }

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;

    /** Provider factice : enregistre chaque plage demandée. */
    private static final class Recorder implements InlayHintProvider {
        final AtomicInteger calls = new AtomicInteger();
        final List<int[]> ranges = Collections.synchronizedList(new ArrayList<>());
        final boolean viewport;

        Recorder(boolean viewport) {
            this.viewport = viewport;
        }

        @Override
        public List<jo.codeeditor.lang.model.InlayHint> inlayHints(
                CharSequence text, int startLine, int endLine) {
            calls.incrementAndGet();
            ranges.add(new int[]{startLine, endLine});
            return Collections.emptyList();
        }

        @Override
        public boolean wantsViewportRange() {
            return viewport;
        }
    }

    private EditorView newView(Recorder provider) {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc60()));
        view.setSession(session);
        // Langage factice : analyzer vide + le provider enregistré.
        view.setLanguage(new Language() {
            @Override
            public Analyzer getAnalyzer() {
                return analyzer();
            }
            @Override
            public InlayHintProvider getInlayHintProvider() {
                return provider;
            }
            @Override
            public int getInterruptionLevel() {
                return INTERRUPTION_LEVEL_NONE;
            }
            @Override
            public void destroy() {
            }
        });
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view, LINE_H, CHAR_W);
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        TestMetrics.injecter(view, lineHeight, charWidth);
    }

    private static Analyzer analyzer() {
        return new Analyzer() {
            @Override public void setReceiver(jo.codeeditor.lang.StyleReceiver r) { }
            @Override public void onReplace(CharSequence t, int s, int e, CharSequence i) { }
            @Override public void reset(CharSequence t) { }
            @Override public jo.codeeditor.highlight.StyledLine styledLine(int line) { return null; }
            @Override public List<CodeBlock> computeBlocks() {
                return Collections.emptyList();
            }
            @Override public jo.codeeditor.lang.BracketMatch computeBracketMatch(int o) { return null; }
            @Override public void destroy() { }
        };
    }

    /** Fait tourner le looper principal jusqu'à écouler le debounce (800 ms). */
    private static void écroulerDebounce() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(900, TimeUnit.MILLISECONDS);
    }

    /** Fait tourner le looper principal jusqu'à écouler le repos scroll (200 ms). */
    private static void écroulerScrollIdle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS);
    }

    /** Attend (sondage borné) que le provider ait été appelé n fois. */
    private static void attendre(Recorder provider, int attendu) {
        // A-06 : attente bornée, le debounce écroulé entre passes.
        jo.codeeditor.AttenteBornee.jusquA(5000, 20L, () -> provider.calls.get() >= attendu,
                InlayViewportRangeTest::écroulerDebounce);
    }

    @Test
    public void drapeauVrai_fenêtreViewportAvecMarge4() {
        Recorder provider = new Recorder(true);
        EditorView view = newView(provider);
        attendre(provider, 1);
        assertEquals("UNE requête initiale", 1, provider.calls.get());
        int[] plage = provider.ranges.get(0);
        // Viewport 1920 px / 40 px : visibleLast = floor(1920/40) = 48,
        // + marge 4 → 52. Le document fait 61 lignes (60 + queue vide) :
        // la plage n'est PAS le document entier.
        assertEquals(0, plage[0]);
        assertEquals("fenêtre bornée, marge 4 dessous", 52, plage[1]);
        assertTrue("la plage n'est PAS le document entier", plage[1] < 60);
    }

    @Test
    public void drapeauVrai_scrollPuisRepos_reRequêteAvecLaNouvelleFenêtre() {
        Recorder provider = new Recorder(true);
        EditorView view = newView(provider);
        attendre(provider, 1);

        // Scroll de 300 px (< maxV ~560, pas de clamp) : vOffset → 300.
        view.scrollBy(300f);
        assertEquals("sanity : le scroll a bien eu lieu", 300f,
                view.vOffset, 0.01f);
        // Le scroll a ré-armé la tâche de repos — 200 ms de calme
        // déclenchent la re-requête.
        écroulerScrollIdle();
        attendre(provider, 2);
        assertTrue("une RE-REQUÊTE part après le repos du scroll",
                provider.calls.get() >= 2);
        int[] plage = provider.ranges.get(provider.ranges.size() - 1);
        // Nouvelle fenêtre : visibleFirst = floor(300/40) = 7, - marge 4
        // → 3 ; visibleLast = floor(2220/40) = 55, + 4 → 59.
        assertEquals("la fenêtre a suivi le scroll (première ligne - 4)",
                3, plage[0]);
        assertEquals(59, plage[1]);
    }

    @Test
    public void drapeauFaux_documentEntier_scrollNeReDemandePas() {
        Recorder provider = new Recorder(false);
        EditorView view = newView(provider);
        attendre(provider, 1);
        int[] plage = provider.ranges.get(0);
        assertEquals("document ENTIER par défaut (démon interne)", 0, plage[0]);
        assertEquals(60, plage[1]);

        view.scrollBy(300f);
        écroulerScrollIdle();
        attendre(provider, 2);
        assertEquals("aucune re-requête au scroll sans le drapeau", 1,
                provider.calls.get());
    }
}
