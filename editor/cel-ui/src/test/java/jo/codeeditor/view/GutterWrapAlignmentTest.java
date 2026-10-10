package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import android.content.Context;
import android.graphics.Canvas;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import org.robolectric.shadows.ShadowCanvas;

import java.lang.reflect.Field;

import static org.junit.Assert.assertTrue;

/**
 * B11 + B20 (audit) : la gouttière doit s'aligner sur la géométrie
 * réelle du texte (consciente du word-wrap) et démarrer son itération à
 * la première ligne VISIBLE (O(visible)) au lieu de compter une rangée
 * par ligne depuis la ligne 0.
 *
 * <p>Avant : les numéros étaient désalignés dès la première ligne
 * wrappée (la gouttière comptait 1 rangée par ligne, le texte
 * rowsOf(ligne) rangées) et le chemin sensible aux replis parcourait
 * toutes les lignes du document à chaque frame.</p>
 *
 * <p>Modèle : 100 lignes de 150 caractères = 2 rangées wrap chacune à
 * ~100 colonnes par rangée. La ligne 5 commence à la rangée 10 (et NON
 * à la rangée 5) ; son numéro « 6 » doit être dessiné au Y réel de la
 * ligne. Avec vOffset calé sur la rangée 40 (ligne 20), la gouttière ne
 * doit dessiner AUCUN numéro de ligne au-dessus de ~19.</p>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class GutterWrapAlignmentTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final float PAD_TOP = 20f;

    private static String hundredWrappedLines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            for (int c = 0; c < 150; c++) sb.append((char) ('a' + (i % 26)));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void injectMetrics(EditorView view) {
        try {
            setFloat(view.metrics, "lineHeight", LINE_H);
            setFloat(view.metrics, "charWidth", CHAR_W);
            setFloat(view.metrics, "padTop", PAD_TOP);
            setFloat(view.metrics, "padBottom", 0f);
            setFloat(view.metrics, "padLeft", CHAR_W * 0.5f);
            setFloat(view.metrics, "gutterWidth", CHAR_W * 7f);
            setFloat(view.metrics, "foldStripWidth", CHAR_W * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    /** Rend la vue et collecte (texte, y) de chaque drawText du shadow. */
    private static java.util.List<float[]> numberYs(EditorView view,
            java.util.List<String> texts) {
        Canvas canvas = new Canvas();
        view.draw(canvas);
        ShadowCanvas shadow = Shadows.shadowOf(canvas);
        java.util.List<float[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < shadow.getTextHistoryCount(); i++) {
            ShadowCanvas.TextHistoryEvent e = shadow.getDrawnTextEvent(i);
            texts.add(e.text);
            out.add(new float[]{e.x, e.y});
        }
        return out;
    }

    @Test
    public void gutterNumbers_followWrapGeometry() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of(hundredWrappedLines()));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        view.setWordWrap(true);

        // La ligne 5 occupe les rangées 10-11 : son numéro « 6 » doit
        // être dessiné au Y réel (padTop + 10*lineHeight + 0.75*lineH),
        // pas au Y « 1 rangée par ligne » (padTop + 5*lineHeight + ...).
        float expectedY = view.docLineToY(5) + LINE_H * 0.75f;
        float wrongY = PAD_TOP + 5 * LINE_H + LINE_H * 0.75f;
        // Les deux Y diffèrent bien (précondition du test).
        assertTrue(Math.abs(expectedY - wrongY) > LINE_H);

        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.List<float[]> pos = numberYs(view, texts);
        boolean found = false;
        for (int i = 0; i < texts.size(); i++) {
            if ("6".equals(texts.get(i))) {
                assertTrue("le numéro 6 doit être au Y de la géométrie wrap ("
                        + pos.get(i)[1] + " ≠ attendu " + expectedY + ")",
                        Math.abs(pos.get(i)[1] - expectedY) < 1f);
                found = true;
            }
        }
        assertTrue("le numéro de la ligne 5 (« 6 ») doit être dessiné", found);
    }

    @Test
    public void gutterIteration_startsAtFirstVisibleLine() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(
                EditorDocument.of(hundredWrappedLines()));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        view.setWordWrap(true);

        // Scroll vertical : rangée 40 = milieu de la ligne 20. La
        // gouttière ne doit dessiner AUCUN numéro au-dessus de la ligne
        // ~19 (l'ancien chemin itérait depuis la ligne 0 en comptant
        // toutes les lignes au-dessus du viewport).
        view.vOffset = PAD_TOP + 40 * LINE_H;
        view.invalidate();

        java.util.List<String> texts = new java.util.ArrayList<>();
        numberYs(view, texts);
        int minLine = Integer.MAX_VALUE;
        int maxLine = Integer.MIN_VALUE;
        int count = 0;
        for (String t : texts) {
            if (t.matches("\\d{1,3}")) {
                int n = Integer.parseInt(t);
                if (t.length() <= 2 && (n >= 1 && n <= 100)) {
                    minLine = Math.min(minLine, n);
                    maxLine = Math.max(maxLine, n);
                    count++;
                }
            }
        }
        assertTrue("aucun numéro dessiné", count > 0);
        assertTrue("la gouttière dessine des numéros AU-DESSUS du viewport"
                + " (min = " + minLine + ", viewport commence à la ligne ~20)",
                minLine >= 19);
        // Lot 0 (audit v3.41.0) : assertion de COMPLÉTUDE. L'ancien
        // test n'assertait que {@code minLine >= 19} — or le bug du
        // double vOffset faisait que {@code first} dépassait la ligne
        // 40 (en mode wrap 2 rangées/ligne), donc {@code minLine = 40}
        // — l'assertion large passait quand même. On borne désormais
        // la première ligne dessinée à l'intervalle attendu
        // (≈ 20 ± 2) : si le bug réapparaît, minLine saute à ~40 et
        // l'assertion échoue.
        assertTrue("la gouttière saute les numéros du haut du viewport"
                + " (min = " + minLine + " > 22) — bug du double vOffset :"
                + " first est calculé avec scrollTop au lieu de 0, ce qui"
                + " décale la première ligne dessinée bien au-delà du haut"
                + " du viewport",
                minLine <= 22);
        // Viewport 1920/40 = 48 rangées → au plus ~25 lignes visibles
        // (2 rangées par ligne) + marges — jamais 100 numéros.
        assertTrue("trop de numéros dessinés (" + count
                + ") — l'itération doit être bornée au viewport",
                count <= 40);
    }

    /**
     * Lot 0 (audit v3.41.0) : quand l'éditeur est scrollé verticalement,
     * les numéros de ligne doivent couvrir SANS TROU l'intervalle des
     * lignes visibles. Avant le correctif, la gouttière passait
     * {@code scrollTop} (= vOffset) à {@code docLineForScreenY}, qui
     * ajoutait déjà {@code vOffset} en interne — double décalage : à
     * mi-document, plus aucun numéro n'était dessiné.
     *
     * <p>Document non wrappé, 200 lignes courtes. vOffset calé sur 16
     * lignes (16×40 = 640 px). La première ligne partiellement visible
     * est la ligne 16 (0-indexée), la dernière est ~63 (viewport 1920 px
     * / 40 px = 48 rangées). L'assertion : les numéros dessinés doivent
     * former une suite <b>contiguë</b> qui couvre l'intégralité de cet
     * intervalle.</p>
     */
    @Test
    public void gutterNumbers_coverVisibleRangeContiguously_noGaps() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        // 200 lignes courtes (pas de wrap) — isole le défaut de
        // double-vOffset du chemin canonique geometry. On n'utilise
        // PAS de chiffres dans le texte pour que les seuls chiffres
        // collectés via ShadowCanvas soient les numéros de ligne du
        // gutter (et non des fragments de texte dessinés par le
        // textPainter).
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("aaaa\n");
        EditorSession session = new EditorSession(EditorDocument.of(sb.toString()));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        view.setWordWrap(false);

        // Scroll de 16 lignes : vOffset = 16 * lineHeight = 640.
        // La première ligne visible (en haut du viewport) est la ligne
        // 0-indexée 16 (numéro « 17 »).
        view.vOffset = 16 * LINE_H;
        view.invalidate();

        java.util.List<String> texts = new java.util.ArrayList<>();
        numberYs(view, texts);

        // Numéros de ligne réellement dessinés (entiers 1..200).
        java.util.List<Integer> drawn = new java.util.ArrayList<>();
        for (String t : texts) {
            if (t.matches("\\d{1,3}")) {
                int n = Integer.parseInt(t);
                if (n >= 1 && n <= 200) drawn.add(n);
            }
        }
        assertTrue("aucun numéro dessiné — la goutrière ne rend rien",
                !drawn.isEmpty());

        // Tri pour faciliter l'analyse de contiguïté.
        java.util.Collections.sort(drawn);

        int minDrawn = drawn.get(0);
        int maxDrawn = drawn.get(drawn.size() - 1);

        // Le premier numéro dessiné doit correspondre au début du
        // viewport (ligne 16 → numéro « 17 »). Tolérance ±1 ligne
        // pour la ligne partiellement visible.
        assertTrue("le premier numéro dessiné (" + minDrawn
                + ") est trop loin du haut du viewport (attendu ~17)"
                + " — les numéros du haut du viewport sont absents (bug"
                + " du double vOffset)",
                minDrawn >= 16 && minDrawn <= 18);

        // Contiguïté : il ne doit PAS y avoir de trou dans la suite
        // dessinée. Le bug saute 15 numéros en haut (de 17 à 31 par ex.).
        for (int i = 1; i < drawn.size(); i++) {
            int prev = drawn.get(i - 1);
            int cur = drawn.get(i);
            assertTrue("trou dans la numérotation dessinée : "
                    + prev + " puis " + cur
                    + " — le bug du double vOffset saute des numéros"
                    + " en haut du viewport",
                    cur == prev + 1);
        }

        // Couverture : le dernier numéro doit atteindre au moins la
        // dernière ligne visible (16 + 1920/40 = 64 → numéro « 64 »).
        // Tolérance d'une ligne pour la ligne partiellement visible en bas.
        assertTrue("le dernier numéro dessiné (" + maxDrawn
                + ") est trop loin de la fin du viewport (attendu >= ~63)"
                + " — couverture incomplète du viewport",
                maxDrawn >= 63);

        // Cohérence : le nombre de numéros dessinés doit couvrir
        // intégralement l'intervalle [minDrawn, maxDrawn] (puisqu'on a
        // vérifié la contiguïté, c'est juste max-min+1).
        int expectedCount = maxDrawn - minDrawn + 1;
        assertTrue("le compte (" + drawn.size() + ") ne couvre pas"
                + " l'intervalle [" + minDrawn + ", " + maxDrawn
                + "] (attendu " + expectedCount + ")",
                drawn.size() == expectedCount);
    }

    /**
     * Lot 0 — Variante : à mi-document, le bug du double vOffset
     * faisait que {@code first} dépassait {@code totalLines} et la
     * gouttière ne dessinait plus AUCUN numéro. Document court de 50
     * lignes, vOffset calé sur 40 lignes (= 1600 px) — la moitié du
     * document. Sans correctif, {@code docLineForScreenY(1600)}
     * renvoie ~80 (>= 50), la boucle ne s'exécute pas.
     */
    @Test
    public void gutterNumbers_drawnAtMidDocument_noDisappearance() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("x\n");
        EditorSession session = new EditorSession(EditorDocument.of(sb.toString()));
        view.setSession(session);
        view.measure(1080, 1920);
        view.layout(0, 0, 1080, 1920);
        injectMetrics(view);
        view.setWordWrap(false);

        // Mi-document : vOffset = 40 * lineHeight = 1600 px.
        view.vOffset = 40 * LINE_H;
        view.invalidate();

        java.util.List<String> texts = new java.util.ArrayList<>();
        numberYs(view, texts);

        int count = 0;
        for (String t : texts) {
            if (t.matches("\\d{1,3}")) {
                int n = Integer.parseInt(t);
                // Le document ne contient que 50 lignes ; au moins
                // les numéros des dernières lignes (41 à 50) doivent
                // être dessinés.
                if (n >= 41 && n <= 50) count++;
            }
        }
        assertTrue("à mi-document, la gouttière ne dessine plus aucun"
                + " numéro de ligne (count=" + count + ") — le bug du"
                + " double vOffset fait dépasser first >= totalLines",
                count >= 5);
    }
}
