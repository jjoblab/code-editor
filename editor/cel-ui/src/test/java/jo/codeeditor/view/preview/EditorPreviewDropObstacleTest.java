package jo.codeeditor.view.preview;

import org.junit.Test;

import jo.codeeditor.view.preview.EditorPreviewDropGeometry.Placement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B27 (retour terrain) — verrouille la géométrie d'esquive de la carte
 * « goutte d'eau » face aux OBSTACLES (popups vivants : complétion, doc
 * rapide) : creux libre vertical, placement LATÉRAL à droite sur grand
 * écran (suggestion du propriétaire : « se positionner à droite sans
 * cacher le popup »), compactage dans le plus grand creux, et drapeau
 * honnête {@code obstructed} + ratio de recouvrement quand RIEN ne tient
 * (le voile d'eau du contrôleur prend alors le relais).
 *
 * <p>JUnit pur, sans Android — même discipline que la QA maquette : les
 * invariants sont prouvés en floats. La compatibilité arrière sans
 * obstacle (formules historiques exactes) est verrouillée par le test
 * jumeau {@code EditorPreviewDropGeometryTest} et par le test de
 * délégation ci-dessous.</p>
 *
 * @author jo@Dev
 */
public class EditorPreviewDropObstacleTest {

    // Éditeur 1080×1920, carte 232×342 dp × densité 2 (miroir maquette).
    private static final float EDITOR_W = 1080f;
    private static final float EDITOR_H = 1920f;
    private static final float CARD_W = 232f * 2f;
    private static final float CARD_H = 342f * 2f;
    private static final float EYE_X = 25f * 2f;
    private static final float EYE_R = 13f * 2f;
    private static final float EDGE_PAD = 6f * 2f;
    private static final float ANCHOR_OFFSET = 22f * 2f;
    private static final float MIN_SCALE = 0.58f;
    private static final float STEM_THRESHOLD = 8f * 2f;
    private static final float LINE_H = 40f * 2f;

    /** place sans obstacles (miroir du contrôleur historique). */
    private static Placement place(float eyeY, float caretY) {
        return EditorPreviewDropGeometry.place(
                EDITOR_W, EDITOR_H, CARD_W, CARD_H,
                EYE_X, eyeY, EYE_R,
                caretY - LINE_H, caretY + 2f * LINE_H,
                EDGE_PAD, ANCHOR_OFFSET, MIN_SCALE, STEM_THRESHOLD);
    }

    /** place avec obstacles tassés [l, t, r, b] × N. */
    private static Placement place(float eyeY, float caretY, float[] obstacles) {
        return EditorPreviewDropGeometry.place(
                EDITOR_W, EDITOR_H, CARD_W, CARD_H,
                EYE_X, eyeY, EYE_R,
                caretY - LINE_H, caretY + 2f * LINE_H,
                obstacles,
                EDGE_PAD, ANCHOR_OFFSET, MIN_SCALE, STEM_THRESHOLD);
    }

    /** Vrai si la carte recouvre le rectangle (l, t, r, b). */
    private static boolean overlaps(Placement p, float l, float t, float r, float b) {
        return p.cardLeft < r && p.cardLeft + p.visualWidth() > l
                && p.cardTop < b && p.cardTop + p.visualHeight() > t;
    }

    // ── Niveau 2 : creux libre sous bande + obstacle fusionnés ─────

    @Test
    public void complétionSousLeCaret_esquiveVerticaleDansLeCreux() {
        // Œil en haut, caret juste dessous, complétion ancrée sous la
        // bande : les deux spans fusionnent, la carte glisse ENTIÈRE
        // sous le bloc (creux de 1248 px), jamais sur l'obstacle.
        // C'est le scénario « parfois » du retour terrain : la carte
        // atterrissait pile sur la complétion.
        Placement p = place(200f, 240f, new float[]{164f, 424f, 435f, 648f});
        assertEquals("esquive entière dans le creux", 2, p.level);
        assertEquals(1f, p.scale, 0.001f);
        assertFalse("la carte ne recouvre jamais la complétion",
                overlaps(p, 164f, 424f, 435f, 648f));
        assertFalse("la zone de frappe reste intouchable",
                overlaps(p, 0f, 160f, EDITOR_W, 400f));
        assertFalse(p.obstructed);
        assertEquals(0f, p.overlapRatio, 0.0001f);
        assertTrue("la carte commence sous le bloc fusionné",
                p.cardTop >= 648f);
    }

    // ── Niveau 4 : latéral à droite (suggestion du propriétaire) ───

    @Test
    public void écranLarge_carteLatéraleÀDroiteDeLaComplétion() {
        // Aucun creux vertical (complétion haute + bande de frappe basse
        // remplissent la hauteur) mais l'éditeur est large : la carte se
        // pose ENTIÈRE à droite de l'obstacle, pleine taille — la tige
        // la relie à l'œil. La bande de frappe reste intouchable.
        float[] obstacle = {164f, 200f, 480f, 1720f};
        Placement p = place(600f, 1800f, obstacle);
        assertEquals("placement latéral à droite de la complétion", 4, p.level);
        assertEquals(1f, p.scale, 0.001f);
        assertTrue("la carte démarre juste après l'obstacle",
                p.cardLeft >= 480f + EDGE_PAD);
        assertFalse("aucun recouvrement de la complétion",
                overlaps(p, 164f, 200f, 480f, 1720f));
        assertFalse("aucun recouvrement de la zone de frappe",
                overlaps(p, 0f, 1720f, EDITOR_W, 1960f));
        assertFalse(p.obstructed);
    }

    @Test
    public void latéral_rejetéSiLaBandeDeFrappeEstTouchée() {
        // Candidat latéral valide en X mais qui recouvrirait la bande de
        // frappe : REJETÉ — la surface de frappe prime toujours, même
        // pour le latéral. Compactage (niveau 3) à la place.
        float[] obstacle = {164f, 500f, 435f, 1700f};
        Placement p = place(200f, 240f, obstacle);
        assertEquals("latéral rejeté → compactage", 3, p.level);
        assertFalse("la zone de frappe n'est jamais sacrifiée au latéral",
                overlaps(p, 0f, 160f, EDITOR_W, 400f));
        assertTrue("l'obstacle reste recouvert (rien ne tient) → voile",
                p.obstructed);
        assertTrue("ratio de recouvrement honnête et substantiel",
                p.overlapRatio > 0.05f && p.overlapRatio < 0.5f);
    }

    @Test
    public void latéral_gardeLeCandidatLePlusÀGauche() {
        // Deux obstacles latéraux possibles : la carte se pose à droite
        // du PLUS PROCHE (le plus à gauche) — elle reste près de sa
        // colonne de gouttière. Le second obstacle, hors colonne et
        // hors de la zone latérale du premier, ne bloque rien.
        float[] obstacles = {164f, 500f, 435f, 1700f, 1000f, 200f, 1060f, 1720f};
        Placement p = place(600f, 1800f, obstacles);
        assertEquals(4, p.level);
        assertEquals("le candidat retenu est l'obstacle le plus proche",
                435f + EDGE_PAD, p.cardLeft, 0.01f);
    }

    // ── Niveau 3 : compactage entre obstacles ──────────────────────

    @Test
    public void pasDePlaceLatérale_compactageDansLeCreux() {
        // Éditeur étroit (téléphone) : le latéral ne tient pas, la carte
        // se compacte dans le plus grand creux — sans jamais toucher
        // l'obstacle ni la bande de frappe.
        Placement p = EditorPreviewDropGeometry.place(
                360f, 1000f, CARD_W, CARD_H,
                EYE_X, 400f, EYE_R,
                500f, 660f,
                new float[]{80f, 664f, 340f, 888f},
                EDGE_PAD, ANCHOR_OFFSET, MIN_SCALE, STEM_THRESHOLD);
        assertEquals("compactage (pas de latéral possible)", 3, p.level);
        assertTrue(p.scale >= MIN_SCALE - 0.001f && p.scale <= 1f);
        assertFalse("aucun recouvrement de l'obstacle",
                overlaps(p, 80f, 664f, 340f, 888f));
        assertFalse("aucun recouvrement de la zone de frappe",
                overlaps(p, 0f, 500f, 360f, 660f));
        assertFalse(p.obstructed);
    }

    // ── Obstruction honnête : le voile d'eau prend le relais ───────

    @Test
    public void rienNeTient_carteObstruéeAvecRatio() {
        // Petit écran, complétion au milieu, rien ne tient : le placement
        // porte le drapeau obstructed + le ratio de recouvrement (le
        // contrôleur voile la carte, il ne la laisse plus cacher).
        Placement p = EditorPreviewDropGeometry.place(
                640f, 385f, CARD_W, CARD_H,
                EYE_X, 60f, EYE_R,
                60f, 240f,
                new float[]{117f, 94f, 397f, 318f},
                EDGE_PAD, ANCHOR_OFFSET, MIN_SCALE, STEM_THRESHOLD);
        assertTrue("rien ne tient → obstructed honnête", p.obstructed);
        assertTrue("le ratio mesure la part de complétion couverte",
                p.overlapRatio >= 0.5f);
        assertEquals(3, p.level);
    }

    // ── Obstacles hors colonne : ignorés ───────────────────────────

    @Test
    public void obstacleHorsDeLaColonne_ignoré() {
        // Une complétion ancrée loin à droite (grande colonne du caret)
        // ne croise pas la colonne de la carte : elle ne bloque rien,
        // la carte reste ancrée (niveau 1), identique au sans-obstacle.
        float[] farRight = {900f, 300f, 1060f, 600f};
        Placement with = place(200f, 1600f, farRight);
        Placement without = place(200f, 1600f);
        assertEquals(1, with.level);
        assertEquals(0f, with.overlapRatio, 0.0001f);
        assertFalse(with.obstructed);
        assertEquals("position identique au sans-obstacle",
                without.cardTop, with.cardTop, 0.01f);
        assertEquals(without.cardLeft, with.cardLeft, 0.01f);
        assertEquals(without.scale, with.scale, 0.001f);
    }

    // ── Compatibilité arrière : délégation exacte ──────────────────

    @Test
    public void sansObstacles_nouvelleSignature_délègueÀLHistorique() {
        // null et vide = comportement historique exact (les formules
        // validées par la QA maquette ne bougent pas d'un pixel).
        for (int i = 0; i < 6; i++) {
            float eyeY = 220f + i * 260f;
            float caretY = eyeY + 40f;
            Placement legacy = place(eyeY, caretY);
            Placement withNull = place(eyeY, caretY, null);
            Placement withEmpty = place(eyeY, caretY, new float[0]);
            String at = "eyeY=" + eyeY;
            assertEquals(at, legacy.level, withNull.level);
            assertEquals(at, legacy.cardTop, withNull.cardTop, 0.01f);
            assertEquals(at, legacy.cardLeft, withNull.cardLeft, 0.01f);
            assertEquals(at, legacy.scale, withNull.scale, 0.001f);
            assertEquals(at, legacy.cardTop, withEmpty.cardTop, 0.01f);
            assertEquals(at, legacy.cardLeft, withEmpty.cardLeft, 0.01f);
        }
    }

    // ── Balayage : invariants avec obstacle, 18 positions ──────────

    @Test
    public void balayage18Positions_complétionJamaisCachéeEnSilence() {
        // Miroir de la QA maquette, obstacle compris : 18 positions de
        // caret balayées, complétion vivante sous la bande. Tant que la
        // carte tient dans l'éditeur : jamais de recouvrement de la
        // zone de frappe, jamais de recouvrement de l'obstacle (creux
        // ou latéral), jamais de débordement, échelle dans les bornes.
        float[] obstacle = {164f, 500f, 435f, 724f};
        boolean fitsAtMin = CARD_H * MIN_SCALE
                <= EDITOR_H - 2f * EDGE_PAD - 3f * LINE_H - 224f;
        for (int i = 0; i < 18; i++) {
            float eyeY = 220f + i * 90f;
            float caretY = eyeY + 40f;
            Placement p = place(eyeY, caretY, obstacle);
            String at = "position " + i + " (eyeY=" + eyeY + ")";
            assertTrue("échelle dans les bornes — " + at,
                    p.scale >= MIN_SCALE - 0.001f && p.scale <= 1f);
            assertTrue("dans l'éditeur — " + at,
                    p.cardLeft >= EDGE_PAD - 0.01f
                            && p.cardTop >= EDGE_PAD - 0.01f
                            && p.cardLeft + p.visualWidth() <= EDITOR_W - EDGE_PAD + 0.01f
                            && p.cardTop + p.visualHeight() <= EDITOR_H - EDGE_PAD + 0.01f);
            assertTrue("goutte dans la hauteur visuelle — " + at,
                    p.dropY() >= p.cardTop - 0.01f
                            && p.dropY() <= p.cardTop + p.visualHeight() + 0.01f);
            if (fitsAtMin) {
                assertFalse("aucun recouvrement de la zone de frappe — " + at,
                        overlaps(p, 0f, caretY - LINE_H, EDITOR_W, caretY + 2f * LINE_H));
                assertFalse("la complétion n'est jamais cachée en silence — " + at,
                        p.obstructed);
                assertFalse("aucun recouvrement de l'obstacle — " + at,
                        overlaps(p, 164f, 500f, 435f, 724f));
            }
        }
    }
}
