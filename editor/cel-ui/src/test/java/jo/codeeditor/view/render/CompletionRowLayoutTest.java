package jo.codeeditor.view.render;

import org.junit.Test;

import jo.codeeditor.view.state.CompletionState;

import static org.junit.Assert.*;

/**
 * Géométrie de la rangée de complétion deux lignes : le libellé sur
 * la première ligne (runs de match en accent), l'identité qualifiée —
 * FQCN d'un type, signature d'un membre — SOUS le libellé, dans la
 * même colonne, sur toute la largeur restante. Le nom court et le nom
 * long ne se disputent plus la même ligne : « Button » écrasé par
 * « android.widget.Button » à l'horizontale, c'était le défaut.
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
    public void identiteSousLeLabel_memeColonne() {
        float[] m = layout(0f, false);
        // SOUS le libellé : la baseline de l'identité est strictement
        // plus basse que celle du libellé.
        assertTrue("l'identité doit être SOUS le libellé",
                m[EditorAssistPopupPainter.CR_DETAIL_BASE]
                        > m[EditorAssistPopupPainter.CR_LABEL_BASE]);
        // MÊME colonne : empilement vertical, jamais côte à côte.
        assertEquals("l'identité démarre sous le libellé, même X",
                m[EditorAssistPopupPainter.CR_LABEL_X],
                m[EditorAssistPopupPainter.CR_DETAIL_X], 0f);
    }

    @Test
    public void identiteTouteLaLargeur_memeAvecContainer() {
        // Le chip d'origine vit sur la ligne du LIBELLÉ : la seconde
        // ligne garde toute la largeur — un FQCN profond ne rivalise
        // plus avec le nom court.
        float[] m = layout(140f * DENSITY, false);
        assertEquals(WIDTH - PAD_X,
                m[EditorAssistPopupPainter.CR_DETAIL_MAX_RIGHT], 0f);
    }

    @Test
    public void containerSurLaLigneDuLibelle_aligneADroite() {
        float containerW = 60f * DENSITY;
        float[] m = layout(containerW, false);
        // Le chip partage la ligne du nom…
        assertEquals(m[EditorAssistPopupPainter.CR_LABEL_BASE],
                m[EditorAssistPopupPainter.CR_CONTAINER_BASE], 0f);
        // …calé à droite, et le libellé lui cède la place avec marge.
        assertEquals(WIDTH - PAD_X,
                m[EditorAssistPopupPainter.CR_CONTAINER_RIGHT], 0f);
        assertEquals(WIDTH - PAD_X - containerW,
                m[EditorAssistPopupPainter.CR_CONTAINER_LEFT], 0f);
        assertEquals(m[EditorAssistPopupPainter.CR_CONTAINER_LEFT] - 8f * DENSITY,
                m[EditorAssistPopupPainter.CR_LABEL_MAX_RIGHT], 0f);
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
    public void rangéeDeuxLignes() {
        // 40 dp : deux lignes de texte code sans serrer — les 28 dp
        // historiques ne logeaient qu'une seule ligne.
        assertEquals(40f, CompletionState.ROW_HEIGHT_DP, 0f);
    }
}
