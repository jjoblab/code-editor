package jo.codeeditor.view;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import android.content.Context;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.lang.reflect.Field;

import static org.junit.Assert.*;

/**
 * S4 (P2) — verrouille le <b>nombre maximal de lignes sticky
 * configurable</b> : {@code setStickyMaxLines(int)} (1 à 5, défaut
 * inchangé = 2 pour ne pas régresser la décision mobile).
 *
 * <ul>
 *   <li><b>Défaut</b> — 2 lignes collées maximum, getter = 2 ;</li>
 *   <li><b>Plafond appliqué</b> — {@code setStickyMaxLines(4)} sur un
 *       document à 4+ en-têtes au-dessus du viewport → la liste
 *       active compte exactement 4 rangées (la bande et le hit-test
 *       S3 suivent la MÊME source de vérité) ;</li>
 *   <li><b>Refus propre</b> — hors plage [1, 5] : valeur courante
 *       conservée (0, -1, 6, 99 ne changent rien, pas de crash).</li>
 * </ul>
 *
 * @author jo@Dev
 */
@RunWith(RobolectricTestRunner.class)
public class StickyMaxLinesTest {

    private static final float LINE_H = 40f;
    private static final float CHAR_W = 10f;
    private static final int VIEW_W = 1080;
    private static final int VIEW_H = 1920;

    /** Quatre en-têtes C_LIKE imbriqués, chacun démarrant par un mot-clé
     *  de la table du détecteur : classe (0), classe interne (1), deux
     *  méthodes (2, 3). */
    private static String doc() {
        StringBuilder sb = new StringBuilder();
        sb.append("public class Main {\n")              // 0
          .append("    static class Inner {\n")          // 1
          .append("        void run() {\n")               // 2
          .append("            int compute(int x) {\n");   // 3
        for (int i = 0; i < 60; i++) {
            sb.append("        int filler").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("            }\n").append("        }\n")
          .append("    }\n").append("}\n");
        return sb.toString();
    }

    private EditorView newView() {
        Context ctx = RuntimeEnvironment.getApplication();
        EditorView view = new EditorView(ctx);
        EditorSession session = new EditorSession(EditorDocument.of(doc()));
        view.setSession(session);
        session.setLanguage("java");
        view.measure(VIEW_W, VIEW_H);
        view.layout(0, 0, VIEW_W, VIEW_H);
        injectMetrics(view, LINE_H, CHAR_W);
        // Scroller profondément : les 4 en-têtes (0-3) passent au-dessus.
        view.vOffset = 2200f;
        return view;
    }

    private static void injectMetrics(EditorView view, float lineHeight, float charWidth) {
        try {
            setFloat(view.metrics, "lineHeight", lineHeight);
            setFloat(view.metrics, "charWidth", charWidth);
            setFloat(view.metrics, "padTop", lineHeight * 0.5f);
            setFloat(view.metrics, "padLeft", charWidth * 0.5f);
            setFloat(view.metrics, "gutterWidth", charWidth * 7f);
            setFloat(view.metrics, "foldStripWidth", charWidth * 2f);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setFloat(Object target, String field, float value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.setFloat(target, value);
    }

    @Test
    public void défautDeuxLignesColléesMaximum() {
        EditorView view = newView();
        assertEquals("défaut mobile inchangé (classe + méthode)",
                2, view.getStickyMaxLines());
        assertEquals("la liste active est plafonnée au défaut",
                2, view.activeStickyLines().size());
    }

    @Test
    public void plafondAppliquéAuDétecteurEtAuHitTest() {
        EditorView view = newView();
        view.setStickyMaxLines(4);
        assertEquals(4, view.getStickyMaxLines());

        java.util.List<int[]> active = view.activeStickyLines();
        assertEquals("exactement 4 rangées collées (les en-têtes 0-3)",
                4, active.size());
        // Ordre d'empilement : extérieure → intérieure.
        assertEquals(0, active.get(0)[0]);
        assertEquals(3, active.get(3)[0]);

        // Le minimum extrême marche aussi : 1 seule ligne.
        view.setStickyMaxLines(1);
        assertEquals(1, view.activeStickyLines().size());
    }

    @Test
    public void horsPlage_laValeurCouranteEstConservée() {
        EditorView view = newView();
        view.setStickyMaxLines(3);
        for (int bad : new int[]{0, -1, 6, 99}) {
            view.setStickyMaxLines(bad);
            assertEquals("refus propre hors [1, 5] : " + bad,
                    3, view.getStickyMaxLines());
        }
        assertEquals("la liste active suit toujours le plafond conservé",
                3, view.activeStickyLines().size());
    }
}
