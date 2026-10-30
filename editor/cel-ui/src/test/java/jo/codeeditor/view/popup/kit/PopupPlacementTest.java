package jo.codeeditor.view.popup.kit;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Placement COORDONNÉ des popups de frappe (phase 5) — la règle en
 * quatre temps, la bande du caret JAMAIS recouverte, aucun
 * chevauchement entre signature et complétion.
 */
public class PopupPlacementTest {

    private static final float G = 4f;
    private static final float M = 0f;

    // ── Règle 1 : la configuration de frappe idéale ────────────────

    @Test
    public void règle1_signatureAuDessus_complétionEnDessous() {
        // Bande au milieu, place des deux côtés : signature au-dessus,
        // complétion en dessous, bande libre entre les deux.
        PopupPlacement.Placements p = PopupPlacement.place(
                1000f, 500f, 540f, 120f, 300f, G, M);
        assertEquals(PopupPlacement.RULE_SIG_ABOVE, p.rule);
        assertEquals(500f - G - 120f, p.sigTop, 0f);
        assertEquals(540f + G, p.cmpTop, 0f);
        // Aucun chevauchement, bande libre.
        assertTrue(p.sigTop + 120f <= 500f);
        assertTrue(p.cmpTop >= 540f);
        assertEquals(300f, p.cmpHeight, 0f);
    }

    @Test
    public void règle1_lesXNeComptentPas_seuleLaBandeCompte() {
        // La règle est purement verticale — l'alignement X (début du
        // token) est posé par l'appelant, jamais ici.
        PopupPlacement.Placements p = PopupPlacement.place(
                1000f, 500f, 540f, 120f, 300f, G, M);
        assertTrue(p.aSignature() && p.aComplétion());
    }

    // ── Règle 2 : plus de place en bas → complétion au-dessus ───────

    @Test
    public void règle2_peuDePlaceDessous_complétionAuDessus() {
        // Vue réduite (clavier) : la complétion ne tient pas EN DESSOUS
        // mais tient AU-DESSUS, et la signature tient dessous → la
        // complétion passe au-dessus, la signature en dessous.
        PopupPlacement.Placements p = PopupPlacement.place(
                700f, 500f, 540f, 120f, 300f, G, M);
        assertEquals(PopupPlacement.RULE_CMP_ABOVE, p.rule);
        assertEquals("la complétion au-dessus de la bande",
                500f - G - 300f, p.cmpTop, 0f);
        assertEquals("la signature en dessous", 540f + G, p.sigTop, 0f);
        assertTrue("bande libre", p.cmpTop + 300f <= 500f && p.sigTop >= 540f);
        assertTrue("aucun chevauchement", p.sigTop >= p.cmpTop + 300f);
    }

    // ── Règle 3 : empilés au-dessus ─────────────────────────────────

    @Test
    public void règle3_empilésAuDessus_complétionPrèsDuCaret() {
        // Bande BASSE (peu de place dessous : la signature n'y tient
        // pas → règle 2 impossible) et assez de place AU-DESSUS pour
        // les deux empilés : complétion près du caret, signature
        // empilée au-dessus d'elle.
        float hSig = 120f, hCmp = 560f;
        PopupPlacement.Placements p = PopupPlacement.place(
                800f, 700f, 740f, hSig, hCmp, G, M);
        assertEquals(PopupPlacement.RULE_STACKED_ABOVE, p.rule);
        assertEquals("la complétion est près du caret",
                700f - G - 560f, p.cmpTop, 0f);
        assertEquals("la signature empilée au-dessus",
                700f - G - 560f - G - 120f, p.sigTop, 0f);
        assertTrue("aucun chevauchement", p.sigTop + hSig + G <= p.cmpTop);
        assertTrue("bande libre", p.cmpTop + hCmp <= 700f);
    }

    // ── Règle 4 : empilés en dessous, complétion ROGNÉE ─────────────

    @Test
    public void règle4_empilésDessous_signaturePrèsDuCaret() {
        // Bande BASSE (peu de place au-dessus) et vue courte : empilés
        // en dessous, signature près du caret, complétion rognée à la
        // place restante — la bande n'est JAMAIS recouverte.
        PopupPlacement.Placements p = PopupPlacement.place(
                700f, 80f, 120f, 150f, 400f, G, M);
        assertEquals(PopupPlacement.RULE_STACKED_BELOW, p.rule);
        assertEquals("la signature est près du caret", 120f + G, p.sigTop, 0f);
        assertEquals(120f + G + 150f + G, p.cmpTop, 0f);
        assertEquals("la complétion est rognée à la place restante",
                700f - (120f + G + 150f + G), p.cmpHeight, 0f);
        assertTrue("la hauteur rognée ne déborde pas",
                p.cmpTop + p.cmpHeight <= 700f);
        assertTrue("bande libre", p.sigTop >= 120f);
    }

    @Test
    public void règle4_clampNeRecouvreJamaisLaBande() {
        // Configuration extrême : la complétion rognée tombe à zéro
        // plutôt que de recouvrir la bande du caret.
        PopupPlacement.Placements p = PopupPlacement.place(
                300f, 80f, 120f, 150f, 400f, G, M);
        assertEquals(PopupPlacement.RULE_STACKED_BELOW, p.rule);
        assertEquals(120f + G, p.sigTop, 0f);
        float cmpTop = 120f + G + 150f + G;
        assertEquals(Math.max(0f, 300f - cmpTop), p.cmpHeight, 0f);
        assertTrue("la bande n'est jamais recouverte",
                p.sigTop >= 120f && (Float.isNaN(p.sigTop) || p.sigTop >= 120f));
    }

    // ── Popups seuls : la règle se dégrade proprement ───────────────

    @Test
    public void signatureSeule_auDessusParDéfaut() {
        PopupPlacement.Placements p = PopupPlacement.place(
                1000f, 500f, 540f, 120f, 0f, G, M);
        assertEquals(PopupPlacement.RULE_SIG_ABOVE, p.rule);
        assertTrue(p.aSignature());
        assertFalse(p.aComplétion());
        assertEquals(500f - G - 120f, p.sigTop, 0f);
    }

    @Test
    public void complétionSeule_enDessousParDéfaut() {
        // Historique conservé : la complétion prefère EN DESSOUS.
        PopupPlacement.Placements p = PopupPlacement.place(
                1000f, 500f, 540f, 0f, 300f, G, M);
        assertEquals(PopupPlacement.RULE_SIG_ABOVE, p.rule);
        assertEquals(540f + G, p.cmpTop, 0f);
    }

    @Test
    public void complétionSeule_sansPlaceDessous_basculeAuDessus() {
        // Peu de place en dessous : la complétion passe au-dessus
        // (pattern historique anchorAboveBelow).
        PopupPlacement.Placements p = PopupPlacement.place(
                700f, 500f, 540f, 0f, 300f, G, M);
        // b = 700-544 = 156 < 300 ; a = 496 ≥ 300 → règle 2.
        assertEquals(PopupPlacement.RULE_CMP_ABOVE, p.rule);
        assertEquals(500f - G - 300f, p.cmpTop, 0f);
    }

    // ── Clavier logiciel (adjustResize) : la hauteur réelle règne ──

    @Test
    public void clavierOuvert_laVueRéduiteChangeLaRègle() {
        // Même popup, vue réduite par le clavier (adjustResize) : la
        // complétion ne tient plus en dessous → bascule au-dessus.
        PopupPlacement.Placements plein = PopupPlacement.place(
                1000f, 500f, 540f, 0f, 300f, G, M);
        assertEquals(PopupPlacement.RULE_SIG_ABOVE, plein.rule);
        PopupPlacement.Placements clavier = PopupPlacement.place(
                700f, 500f, 540f, 0f, 300f, G, M);
        assertEquals("la hauteur RÉELLE de la vue commande",
                PopupPlacement.RULE_CMP_ABOVE, clavier.rule);
        assertTrue("le popup reste dans la vue réduite",
                clavier.cmpTop + 300f <= 500f);
    }

    // ── Propriété générale : jamais de chevauchement ni de bande
    //    recouverte, sur une matrice de positions de caret ──────────

    @Test
    public void matrice_caretHautMilieuBas_aucunChevauchement() {
        // Pour chaque position de caret (haut, milieu, bas), 3 hauteurs
        // de vue (dont une « clavier ouvert ») : la complétion RÉDUIT
        // ses rangées (le flux réel — 40 px par rangée, minimum 1)
        // AVANT de recouvrir quoi que ce soit, puis : aucun
        // chevauchement entre les popups, bande du caret jamais
        // recouverte, les deux popups dans la vue.
        float[] hauteursVue = {1000f, 700f, 460f};   // 3 hauteurs de vue
        float[] positionsBande = {0.08f, 0.5f, 0.9f}; // haut / milieu / bas
        float hSig = 120f, rowH = 40f, hCmpMax = 300f;
        for (float viewH : hauteursVue) {
            for (float frac : positionsBande) {
                float bandTop = viewH * frac;
                float bandBottom = bandTop + 40f;
                // Le flux réel : essai max, réduction si la règle 4
                // rogne, re-placement (comme coordonner()).
                float hCmp = hCmpMax;
                PopupPlacement.Placements p = PopupPlacement.place(
                        viewH, bandTop, bandBottom, hSig, hCmp, G, M);
                if (p.rule == PopupPlacement.RULE_STACKED_BELOW
                        && p.cmpHeight + 0.5f < hCmp) {
                    hCmp = Math.max(rowH,
                            (float) Math.floor(p.cmpHeight / rowH) * rowH);
                    p = PopupPlacement.place(
                            viewH, bandTop, bandBottom, hSig, hCmp, G, M);
                }
                String ctx = "vue " + viewH + " bande " + bandTop;
                assertTrue(ctx + " : signature dans la vue",
                        p.sigTop >= -0.001f && p.sigTop + hSig <= viewH + 0.001f);
                assertTrue(ctx + " : la signature ne recouvre pas la bande",
                        p.sigTop + hSig <= bandTop + 0.001f
                                || p.sigTop >= bandBottom - 0.001f);
                assertTrue(ctx + " : complétion dans la vue",
                        p.cmpTop >= -0.001f
                                && p.cmpTop + hCmp <= viewH + 0.001f);
                assertTrue(ctx + " : la complétion ne recouvre pas la bande",
                        p.cmpTop + hCmp <= bandTop + 0.001f
                                || p.cmpTop >= bandBottom - 0.001f);
                assertTrue(ctx + " : pas de chevauchement sig/cmp",
                        p.sigTop + hSig <= p.cmpTop + 0.001f
                                || p.cmpTop + hCmp <= p.sigTop + 0.001f);
            }
        }
    }

    @Test
    public void ligneWrappée_ouPliée_laBandeEstPlusHaute_laRègleTient() {
        // Une ligne WRAPPÉE (ou repliée) a une bande PLUS HAUTE que
        // lineHeight : la règle reçoit la bande réelle et tient toujours.
        // Bande de 3 lignes visuelles (wrap) :
        PopupPlacement.Placements p = PopupPlacement.place(
                1000f, 400f, 520f, 120f, 300f, G, M);
        assertTrue("bande haute jamais recouverte par la signature",
                p.sigTop + 120f <= 400f);
        assertTrue("bande haute jamais recouverte par la complétion",
                p.cmpTop >= 520f);
        assertEquals(PopupPlacement.RULE_SIG_ABOVE, p.rule);
    }
}
