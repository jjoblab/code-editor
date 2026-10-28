package jo.codeeditor.view.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * 4.11.0 — le rendu TROIS PALIERS de la ligne de signature : les
 * plages de paramètres extraites du libellé (virgules de profondeur
 * 0 entre les parenthèses) décident du palier de chaque paramètre —
 * déjà frappé en accent, actif en gras + accent, à venir en atténué.
 * La couture {@code findParameterRangesInLabel} est PURE : testée
 * sans Canvas, comme {@code computeCompletionRowLayout} et
 * {@code windowSignature}.
 */
public class SignatureTiersRenderTest {

    /** Le texte de la plage [start, end) du libellé. */
    private static String extrait(String label, int[] range) {
        return label.substring(range[0], range[1]);
    }

    @Test
    public void deuxParamètres_deuxPlagesExactes() {
        String label = "println(String x, int i)";
        int[][] ranges = EditorAssistPopupPainter.findParameterRangesInLabel(label);
        assertEquals(2, ranges.length);
        assertEquals("String x", extrait(label, ranges[0]));
        assertEquals("int i", extrait(label, ranges[1]));
    }

    @Test
    public void sansParamètre_plageUniqueVide() {
        // println() : AUCUNE plage (la parenthèse ouvrante touche la
        // fermante) — le rendu complet n'a rien à allumer, la ligne
        // reste en couleur de texte.
        String label = "println()";
        int[][] ranges = EditorAssistPopupPainter.findParameterRangesInLabel(label);
        assertEquals(0, ranges.length);
    }

    @Test
    public void paramètreUnique_unePlage() {
        String label = "calculerTotal(double base, double taux)";
        int[][] ranges = EditorAssistPopupPainter.findParameterRangesInLabel(label);
        assertEquals(2, ranges.length);
        assertEquals("double base", extrait(label, ranges[0]));
        assertEquals("double taux", extrait(label, ranges[1]));
    }

    @Test
    public void virgulesImbriquées_neCoupentPas() {
        // Les virgules des parenthèses IMBRIQUÉES ne créent pas de
        // paramètre : pow(max(1, 2), 3) a DEUX paramètres.
        String label = "pow(max(1, 2), 3)";
        int[][] ranges = EditorAssistPopupPainter.findParameterRangesInLabel(label);
        assertEquals(2, ranges.length);
        assertEquals("max(1, 2)", extrait(label, ranges[0]));
        assertEquals("3", extrait(label, ranges[1]));
    }

    @Test
    public void labelFenêtré_lesPointsDeSuspensionRestentDeLaStructure() {
        // Le fenêtrage (…) réécrit le libellé : les plages suivent.
        int[] actif = new int[1];
        String fenêtré = EditorAssistPopupPainter.windowSignature(
                "layout(int l, int t, int r, int b, int w, int h, int f)", 5, actif);
        assertEquals("layout(…, int w, int h, int f)", fenêtré);
        assertEquals("l'actif (h) vit à l'index 2 du libellé fenêtré", 2, actif[0]);
        int[][] ranges =
                EditorAssistPopupPainter.findParameterRangesInLabel(fenêtré);
        // Le « … » de tête est une PSEUDO-plage : il REMPLACE les
        // paramètres déjà frappés tronqués — palier accent, comme eux.
        assertEquals(4, ranges.length);
        assertEquals("…", extrait(fenêtré, ranges[0]));
        assertEquals("int w", extrait(fenêtré, ranges[1]));
        assertEquals("int h", extrait(fenêtré, ranges[2]));
        assertEquals("int f", extrait(fenêtré, ranges[3]));
    }

    @Test
    public void lignesSansParenthèses_null() {
        // Les lignes wrappées de la vue peek sans couple de
        // parenthèses : pas de paliers, rendu dégradé pré/actif/post.
        assertNull(EditorAssistPopupPainter.findParameterRangesInLabel(
                "double base, double taux"));
        assertNull(EditorAssistPopupPainter.findParameterRangesInLabel(null));
        assertNull(EditorAssistPopupPainter.findParameterRangesInLabel(""));
        assertNull("parenthèse ouvrante seule : pas de couple",
                EditorAssistPopupPainter.findParameterRangesInLabel("println("));
    }

    @Test
    public void paliers_leLibelléSeRecomposeEntier() {
        // Contrat du rendu : la concaténation structure + plages
        // recompose EXACTEMENT le libellé — aucun caractère perdu ou
        // dupliqué entre les segments dessinés.
        String label = "max(int a, int b)";
        int[][] ranges = EditorAssistPopupPainter.findParameterRangesInLabel(label);
        StringBuilder recomposé = new StringBuilder();
        int cursor = 0;
        for (int[] r : ranges) {
            recomposé.append(label, cursor, r[0]);
            recomposé.append(label, r[0], r[1]);
            cursor = r[1];
        }
        recomposé.append(label.substring(cursor));
        assertEquals(label, recomposé.toString());
    }
}
