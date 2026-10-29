package jo.codeeditor.view.popup.kit;

import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Jetons de design partagés par TOUS les popups de l'éditeur : rayons,
 * espacements, échelle typographique et couleurs sémantiques. Un seul
 * endroit où changer l'apparence d'une carte flottante — avant le kit,
 * huit rayons coexistaient (6, 8, 12, 14, 16, 18 dp) et chaque popup
 * portait ses propres paddings et facteurs de taille de texte.
 *
 * <p>Toutes les valeurs sont en <b>dp</b> sauf mention contraire — les
 * consommateurs multiplient par la densité. L'échelle typographique est
 * exprimée en <b>facteur</b> de la taille de texte de l'éditeur
 * ({@code EditorMetrics.getTextSize()}). Les couleurs sémantiques sont
 * DÉRIVÉES du thème courant ({@link EditorTheme}) : aucune couleur en
 * dur ne vit dans les painters.</p>
 */
public final class PopupTokens {

    // ── Rayons (dp) ────────────────────────────────────────────────
    /** Rayon d'une carte de popup flottante (complétion, signature…). */
    public static final float RADIUS_CARD_DP = 14f;
    /** Rayon d'un fond de rangée sélectionnée / d'une zone pressée. */
    public static final float RADIUS_ROW_DP = 8f;
    /** Rayon d'un petit chip (badge de type, pill compacte). */
    public static final float RADIUS_CHIP_DP = 6f;
    /** Rayon d'une sheet ancrée en bas de vue. */
    public static final float RADIUS_SHEET_DP = 16f;

    // ── Espacements (dp) ───────────────────────────────────────────
    /** Marge horizontale intérieure d'une carte. */
    public static final float PAD_X_DP = 12f;
    /** Marge verticale intérieure d'une section. */
    public static final float PAD_Y_DP = 8f;
    /** Écart visuel entre deux blocs voisins (filet compris). */
    public static final float GAP_DP = 6f;

    // ── Échelle typographique (× taille de texte) ──────────────────
    /** Titre de carte / libellé de rangée principal. */
    public static final float TEXT_TITLE = 0.90f;
    /** Corps de texte (message, libellé de section). */
    public static final float TEXT_BODY = 0.85f;
    /** Méta (type d'un paramètre, container, détail). */
    public static final float TEXT_META = 0.75f;
    /** Micro-typographie (compteur, badge, légende de filet). */
    public static final float TEXT_MICRO = 0.70f;

    private PopupTokens() {}

    // ── Couleurs sémantiques (dérivées du thème) ───────────────────

    /** Surface d'une carte : fond verre du thème. */
    public static int surface(EditorTheme t) { return t.glassBg; }

    /** Bordure hairline d'une carte. */
    public static int border(EditorTheme t) { return t.glassBorder; }

    /** Fond teinté doux (bande signature, zone de documentation). */
    public static int softAccent(EditorTheme t) { return t.selection; }

    /**
     * Couleur d'une sévérité de diagnostic (3 = erreur, 2 = avertissement,
     * autre = information) — source unique du mapping sévérité → couleur.
     */
    public static int severity(EditorTheme t, int severity) {
        switch (severity) {
            case 3: return t.error;
            case 2: return t.warning;
            default: return t.info;
        }
    }
}
