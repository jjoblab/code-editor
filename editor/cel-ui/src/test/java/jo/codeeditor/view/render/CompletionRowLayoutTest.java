package jo.codeeditor.view.render;

import org.junit.Test;

import jo.codeeditor.view.state.CompletionState;

import static org.junit.Assert.*;

/**
 * Géométrie de la rangée de complétion deux lignes (4.10.0 — la ligne
 * du MILIEU gagne) : le libellé occupe SEUL la première ligne ; la
 * seconde porte le CONTAINER (l'origine du symbole — paquet ou classe
 * déclarante) À GAUCHE et le DÉTAIL (FQCN d'un type, type de
 * retour/signature d'un membre) ALIGNÉ À DROITE — trois axes de
 * lecture : « qui / d'où ça vient / ce que c'est ».
 *
 * <p>Historique : « Button » écrasé par « android.widget.Button » à
 * l'horizontale (4.8.x), puis l'identité sous le libellé avec le chip
 * d'origine en compétition sur la ligne du nom (4.9.1) — le container
 * descend désormais SUR la ligne de l'identité, à gauche, et le détail
 * prend la droite.</p>
 *
 * <p>La couture {@code computeCompletionRowLayout} est PURE — aucune
 * mesure de paint n'y vit, les largeurs mesurées (container) entrent
 * en paramètre — : positions et baselines se vérifient sans Canvas.</p>
 */
public class CompletionRowLayoutTest {

    /** Densité xxhdpi — arbitraire, la géométrie dérive de rowH/width. */
    private static final float DENSITY = 3f;
    private static final float ROW_H = CompletionState.ROW_HEIGHT_DP * DENSITY;
    private static final float WIDTH = CompletionState.WIDTH_DP * DENSITY;
    private static final float PAD_X = 8f * DENSITY;

    private static float[] layout(float containerW, boolean docGlyph) {
        float[] m = new float[15];
        EditorAssistPopupPainter.computeCompletionRowLayout(
                m, 0f, 0f, WIDTH, ROW_H, PAD_X, DENSITY, containerW, docGlyph);
        return m;
    }

    @Test
    public void containerEtDetail_sousLeLibelle_memeColonne() {
        float[] m = layout(140f * DENSITY, false);
        // SOUS le libellé : la baseline du container est strictement
        // plus basse que celle du libellé.
        assertTrue("le container doit être SOUS le libellé",
                m[EditorAssistPopupPainter.CR_CONTAINER_BASE]
                        > m[EditorAssistPopupPainter.CR_LABEL_BASE]);
        // MÊME colonne que le libellé : empilement vertical.
        assertEquals("le container démarre sous le libellé, même X",
                m[EditorAssistPopupPainter.CR_LABEL_X],
                m[EditorAssistPopupPainter.CR_CONTAINER_LEFT], 0f);
        // Le détail partage la ligne du container, aligné à DROITE.
        assertEquals("container et détail partagent la seconde ligne",
                m[EditorAssistPopupPainter.CR_CONTAINER_BASE],
                m[EditorAssistPopupPainter.CR_DETAIL_BASE], 0f);
        assertEquals("le détail est calé au bord droit",
                WIDTH - PAD_X,
                m[EditorAssistPopupPainter.CR_DETAIL_MAX_RIGHT], 0f);
    }

    @Test
    public void libelleSeulSurSaLigne_touteLaLargeur() {
        // La première ligne appartient au LIBELLÉ seul : plus de chip
        // d'origine à sa droite — le nom court ne cède plus rien.
        float[] m = layout(140f * DENSITY, false);
        assertEquals("le libellé va jusqu'au bord sans container en face",
                WIDTH - PAD_X,
                m[EditorAssistPopupPainter.CR_LABEL_MAX_RIGHT], 0f);
        // Le glyphe absent : la réserve ne s'applique pas.
        float[] g = layout(140f * DENSITY, true);
        assertTrue("avec glyphe, le libellé s'arrête avant sa réserve",
                g[EditorAssistPopupPainter.CR_LABEL_MAX_RIGHT]
                        < m[EditorAssistPopupPainter.CR_LABEL_MAX_RIGHT]);
    }

    @Test
    public void badgePlafonneEtCentreSurLaRangée() {
        float[] m = layout(0f, false);
        // 20 dp au plus, centré sur la rangée DEUX lignes.
        assertTrue(m[EditorAssistPopupPainter.CR_BADGE_SIZE]
                <= 20f * DENSITY + 0.01f);
        assertEquals(ROW_H * 0.5f,
                m[EditorAssistPopupPainter.CR_BADGE_TOP]
                        + m[EditorAssistPopupPainter.CR_BADGE_SIZE] * 0.5f,
                0.01f);
    }

    @Test
    public void glypheDocReserveSaPlace_surLaLigneDuLibellé() {
        float[] m = layout(0f, true);
        // Le libellé s'arrête AVANT la réserve du glyphe…
        assertEquals(m[EditorAssistPopupPainter.CR_CONTAINER_RIGHT] - 18f * DENSITY,
                m[EditorAssistPopupPainter.CR_LABEL_MAX_RIGHT], 0f);
        // …et le glyphe vit dans sa réserve, centré sur la ligne du nom.
        assertTrue(m[EditorAssistPopupPainter.CR_GLYPH_CX]
                > m[EditorAssistPopupPainter.CR_LABEL_MAX_RIGHT]);
        assertTrue(m[EditorAssistPopupPainter.CR_GLYPH_CY] < ROW_H * 0.5f);
    }

    @Test
    public void sansContainerNiGlyphe_libelléJusquAuBord() {
        float[] m = layout(0f, false);
        assertEquals(m[EditorAssistPopupPainter.CR_CONTAINER_RIGHT],
                m[EditorAssistPopupPainter.CR_LABEL_MAX_RIGHT], 0f);
    }

    @Test
    public void rayonDuBadge_sixDp() {
        // 4.10.0 : le badge de type passe de 4 à 6 dp — carré plus doux.
        assertEquals(6f,
                EditorAssistPopupPainter.COMPLETION_BADGE_RADIUS_DP, 0f);
    }

    @Test
    public void coinsDuPopup_quatorzeDp_tous() {
        // 4.10.0 : les QUATRE coins du popup sont arrondis (la base
        // carrée des « coins hauts seuls » tranchait avec la carte de
        // verre flottante).
        assertEquals(14f,
                EditorAssistPopupPainter.COMPLETION_BORDER_RADIUS_DP, 0f);
    }

    @Test
    public void rangéeDeuxLignes() {
        // 40 dp : deux lignes de texte code sans serrer — les 28 dp
        // historiques ne logeaient qu'une seule ligne.
        assertEquals(40f, CompletionState.ROW_HEIGHT_DP, 0f);
    }
}
