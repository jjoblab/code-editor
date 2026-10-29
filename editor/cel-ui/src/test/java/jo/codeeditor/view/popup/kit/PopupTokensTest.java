package jo.codeeditor.view.popup.kit;

import org.junit.Test;

import jo.codeeditor.view.chrome.EditorTheme;

import static org.junit.Assert.assertEquals;

/**
 * Jetons de design des popups : contrats de rayon, d'espacement,
 * d'échelle typographique et de couleurs sémantiques dérivées du
 * thème. Les valeurs sont le socle de l'harmonisation (un seul rayon
 * de carte, un rythme de rangée, une échelle) — leur dérive casserait
 * l'unification, d'où ce verrou.
 */
public class PopupTokensTest {

    @Test
    public void rayons_unParFamille() {
        assertEquals(14f, PopupTokens.RADIUS_CARD_DP, 0f);
        assertEquals(8f, PopupTokens.RADIUS_ROW_DP, 0f);
        assertEquals(6f, PopupTokens.RADIUS_CHIP_DP, 0f);
        assertEquals(16f, PopupTokens.RADIUS_SHEET_DP, 0f);
    }

    @Test
    public void espacements_etÉchelleTypo() {
        assertEquals(12f, PopupTokens.PAD_X_DP, 0f);
        assertEquals(8f, PopupTokens.PAD_Y_DP, 0f);
        assertEquals(6f, PopupTokens.GAP_DP, 0f);
        assertEquals(0.90f, PopupTokens.TEXT_TITLE, 0f);
        assertEquals(0.85f, PopupTokens.TEXT_BODY, 0f);
        assertEquals(0.75f, PopupTokens.TEXT_META, 0f);
        assertEquals(0.70f, PopupTokens.TEXT_MICRO, 0f);
    }

    @Test
    public void couleursSémantiques_dérivéesDuThème() {
        EditorTheme t = EditorTheme.dark();
        assertEquals(t.glassBg, PopupTokens.surface(t));
        assertEquals(t.glassBorder, PopupTokens.border(t));
        assertEquals(t.selection, PopupTokens.softAccent(t));
        assertEquals(t.error, PopupTokens.severity(t, 3));
        assertEquals(t.warning, PopupTokens.severity(t, 2));
        assertEquals(t.info, PopupTokens.severity(t, 1));
        assertEquals(t.info, PopupTokens.severity(t, 0));
        // Clarity : même sur un autre thème, le mapping tient.
        EditorTheme l = EditorTheme.light();
        assertEquals(l.error, PopupTokens.severity(l, 3));
        assertEquals(l.warning, PopupTokens.severity(l, 2));
    }
}
