package jo.codeeditor.view.preview;

import org.junit.Test;

import jo.codeeditor.view.preview.EditorPreviewDropGeometry.Placement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * B27 — verrouille la géométrie pure de la carte d'aperçu « goutte
 * d'eau » : l'algorithme de placement anti-recouvrement en 3 niveaux
 * (ancré pleine taille → esquive entière au-dessus/dessous du bloc de
 * frappe ± 1 ligne → compactage par échelle bornée), les clamps aux
 * bords de l'éditeur, la goutte bornée dans la hauteur de la carte et
 * la tige de liaison œil→goutte.
 *
 * <p>Miroir JUnit de la QA automatisée de la maquette (18 positions de
 * caret balayées une à une — zéro recouvrement) : les invariants sont
 * prouvés ICI en float purs, sans Android — le contrôleur et le peintre
 * partagent la même formule en production.</p>
 *
 * @author jo@Dev
 */
public class EditorPreviewDropGeometryTest {

    // Éditeur 1080×1920, carte 232×342 dp ×densité 2 (miroir maquette).
    private static final float EDITOR_W = 1080f;
    private static final float EDITOR_H = 1920f;
    private static final float CARD_W = 232f * 2f;
    private static final float CARD_H = 342f * 2f;
    private static final float EYE_X = 25f * 2f;   // centre zone numéros
    private static final float EYE_R = 13f * 2f;
    private static final float EDGE_PAD = 6f * 2f;
    private static final float ANCHOR_OFFSET = 22f * 2f;
    private static final float MIN_SCALE = 0.58f;
    private static final float STEM_THRESHOLD = 8f * 2f;
    private static final float LINE_H = 40f * 2f;  // pour une zone de frappe réaliste

    private static Placement place(float eyeY, float caretY) {
        // Zone de frappe : caret ± 1 ligne (le bloc que l'utilisateur
        // regarde en tapant — miroir du contrôleur).
        return EditorPreviewDropGeometry.place(
                EDITOR_W, EDITOR_H, CARD_W, CARD_H,
                EYE_X, eyeY, EYE_R,
                caretY - LINE_H, caretY + 2f * LINE_H,
                EDGE_PAD, ANCHOR_OFFSET, MIN_SCALE, STEM_THRESHOLD);
    }

    private static boolean overlapsGuard(Placement p, float guardTop, float guardBot) {
        return p.cardTop < guardBot
                && p.cardTop + p.visualHeight() > guardTop;
    }

    private static boolean withinEditor(Placement p) {
        return p.cardLeft >= EDGE_PAD - 0.01f
                && p.cardTop >= EDGE_PAD - 0.01f
                && p.cardLeft + p.visualWidth() <= EDITOR_W - EDGE_PAD + 0.01f
                && p.cardTop + p.visualHeight() <= EDITOR_H - EDGE_PAD + 0.01f;
    }

    // ── Niveau 1 : ancré, pleine taille ────────────────────────────

    @Test
    public void niveau1_caretLoin_carteAncréePleineTaille() {
        // Œil vers le haut, caret très loin en dessous → aucun
        // recouvrement possible : la carte reste ancrée à l'œil.
        Placement p = place(200f, 1600f);
        assertEquals(1, p.level);
        assertEquals(1f, p.scale, 0.001f);
        assertFalse(overlapsGuard(p, 1600f - LINE_H, 1600f + 2f * LINE_H));
        // Goutte pointée sur l'œil : centre Y écran ≈ œil.
        assertEquals(200f, p.dropY(), 2f);
        assertTrue(withinEditor(p));
    }

    // ── Niveau 2 : esquive entière ─────────────────────────────────

    @Test
    public void niveau2_caretProche_esquiveEntièreAuDessous() {
        // Œil et caret en haut, énorme place en dessous → la carte
        // glisse ENTIÈREMENT sous le bloc de frappe, pleine taille.
        Placement p = place(300f, 340f);
        assertEquals(2, p.level);
        assertEquals(1f, p.scale, 0.001f);
        float guardTop = 340f - LINE_H;
        float guardBot = 340f + 2f * LINE_H;
        assertFalse("la carte esquivée ne recouvre jamais la zone de frappe",
                overlapsGuard(p, guardTop, guardBot));
        assertTrue("la carte commence sous le bloc de frappe",
                p.cardTop >= guardBot);
    }

    @Test
    public void niveau2_caretEnBas_esquiveAuDessus() {
        // Œil et caret près du bas → plus de place au-dessus : la carte
        // glisse au-dessus du bloc de frappe.
        Placement p = place(1600f, 1640f);
        assertEquals(2, p.level);
        float guardTop = 1640f - LINE_H;
        assertFalse(overlapsGuard(p, guardTop, 1640f + 2f * LINE_H));
        assertTrue("la carte finit au-dessus du bloc de frappe",
                p.cardTop + p.visualHeight() <= guardTop + 0.01f);
    }

    // ── Niveau 3 : compactage ──────────────────────────────────────

    @Test
    public void niveau3_aucunCôtéAssezGrand_compactageBorné() {
        // Éditeur réduit : la carte pleine taille ne tient d'aucun côté
        // du caret au milieu → compactage, échelle bornée au plancher.
        float editorH = 600f;
        float caretY = 320f;
        Placement p = EditorPreviewDropGeometry.place(
                EDITOR_W, editorH, CARD_W, CARD_H,
                EYE_X, 300f, EYE_R,
                caretY - LINE_H, caretY + 2f * LINE_H,
                EDGE_PAD, ANCHOR_OFFSET, MIN_SCALE, STEM_THRESHOLD);
        assertEquals(3, p.level);
        assertTrue("l'échelle compactée respecte le plancher",
                p.scale >= MIN_SCALE - 0.001f);
        assertTrue("l'échelle compactée reste ≤ 1",
                p.scale <= 1f);
        // Si la carte tient à l'échelle minimale, elle ne recouvre pas
        // la zone de frappe (le plancher peut être inatteignable dans un
        // éditeur minuscule — l'invariant de balayage couvre le général).
        if (p.cardH * MIN_SCALE <= editorH - 2f * EDGE_PAD - 3f * LINE_H) {
            assertFalse(overlapsGuard(p, caretY - LINE_H, caretY + 2f * LINE_H));
        }
    }

    // ── Balayage : les invariants tiennent pour 18 positions ───────

    @Test
    public void balayage18Positions_jamaisDeRecouvrementNiDébordement() {
        // Miroir exact de la QA de la maquette : 18 positions de caret
        // de haut en bas de l'éditeur, une à une. Tant que la carte
        // tient dans l'éditeur à l'échelle minimale : zéro recouvrement
        // de la zone de frappe, zéro débordement, échelle dans les
        // bornes, goutte visible dans la hauteur.
        boolean fitsAtMin = CARD_H * MIN_SCALE
                <= EDITOR_H - 2f * EDGE_PAD - 3f * LINE_H;
        for (int i = 0; i < 18; i++) {
            float eyeY = 220f + i * 90f;
            float caretY = eyeY + 40f; // caret proche : force l'esquive
            Placement p = place(eyeY, caretY);
            float guardTop = caretY - LINE_H;
            float guardBot = caretY + 2f * LINE_H;
            String at = "position " + i + " (eyeY=" + eyeY + ")";
            assertTrue("échelle dans les bornes — " + at,
                    p.scale >= MIN_SCALE - 0.001f && p.scale <= 1f);
            assertTrue("dans l'éditeur — " + at, withinEditor(p));
            assertTrue("goutte dans la hauteur visuelle — " + at,
                    p.dropY() >= p.cardTop - 0.01f
                            && p.dropY() <= p.cardTop + p.visualHeight() + 0.01f);
            if (fitsAtMin) {
                assertFalse("aucun recouvrement de la zone de frappe — " + at,
                        overlapsGuard(p, guardTop, guardBot));
            }
            // contains/toLocal sont l'inverse exact l'un de l'autre.
            if (p.contains(p.cardLeft + 1f, p.cardTop + 1f)) {
                assertEquals(1f, p.toLocalX(p.cardLeft + 1f), 0.01f);
                assertEquals(1f, p.toLocalY(p.cardTop + 1f), 0.01f);
            }
        }
    }

    // ── Clamps et tige ─────────────────────────────────────────────

    @Test
    public void clampGauche_côtéDroitJamaisDébordé() {
        // Œil à droite (gouttière fictive large) : la carte se clamp au
        // bord droit de l'éditeur.
        Placement p = EditorPreviewDropGeometry.place(
                EDITOR_W, EDITOR_H, CARD_W, CARD_H,
                900f, 300f, EYE_R,
                1600f, 1700f, // garde loin
                EDGE_PAD, ANCHOR_OFFSET, MIN_SCALE, STEM_THRESHOLD);
        assertTrue("clampée au bord droit",
                p.cardLeft + p.visualWidth() <= EDITOR_W - EDGE_PAD + 0.01f);
        assertTrue(p.cardLeft >= EDGE_PAD - 0.01f);
    }

    @Test
    public void œilHorsÉcran_bordGaucheSimple() {
        // Œil hors écran (gouttière masquée par le scroll) : bord
        // gauche simple, pas de position fantôme.
        Placement p = EditorPreviewDropGeometry.place(
                EDITOR_W, EDITOR_H, CARD_W, CARD_H,
                -50f, -50f, EYE_R,
                1600f, 1700f,
                EDGE_PAD, ANCHOR_OFFSET, MIN_SCALE, STEM_THRESHOLD);
        assertEquals(EDGE_PAD, p.cardLeft, 0.01f);
        assertFalse("pas de tige vers un œil invisible", p.stemVisible);
    }

    @Test
    public void tige_seDessineQuandLaGoutteSécarteDeLOeil() {
        // Esquive au-dessus : la goutte (clampée dans la carte) s'écarte
        // de l'œil resté en bas → la tige relie.
        Placement p = place(1700f, 1740f);
        assertTrue("la tige se dessine quand la carte s'est esquivée loin",
                p.stemVisible);
        // Départ = bord droit de l'œil, arrivée = la goutte.
        assertEquals(EYE_X + EYE_R, p.stemX0, 0.01f);
        assertEquals(1700f, p.stemY0, 0.01f);
        assertEquals(p.cardLeft, p.stemX1, 0.01f);
        assertEquals(p.dropY(), p.stemY1, 0.01f);
    }

    @Test
    public void tige_invisibleQuandLaGouttePointeLOeil() {
        // Niveau 1 ancré : goutte ≈ œil → pas de tige.
        Placement p = place(200f, 1600f);
        assertFalse(p.stemVisible);
    }

    @Test
    public void éditeurPlusPetitQueLaCarte_plancherHaut() {
        // Éditeur minuscule : le clamp vertical pose le plancher
        // d'EDGE_PAD, aucune valeur négative ou hors bornes.
        Placement p = EditorPreviewDropGeometry.place(
                300f, 260f, CARD_W, CARD_H,
                25f, 100f, EYE_R,
                90f, 130f,
                EDGE_PAD, ANCHOR_OFFSET, MIN_SCALE, STEM_THRESHOLD);
        assertTrue(p.cardTop >= EDGE_PAD - 0.01f);
        assertTrue(p.cardLeft >= EDGE_PAD - 0.01f);
    }
}
