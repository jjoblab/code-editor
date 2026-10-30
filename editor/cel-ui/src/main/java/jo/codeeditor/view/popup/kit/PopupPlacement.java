package jo.codeeditor.view.popup.kit;

/**
 * Placement COORDONNÉ des popups de frappe (phase 5) : pendant la
 * frappe d'un appel, la signature et la complétion se partagent la
 * bande du caret — la signature AU-DESSUS, la complétion EN DESSOUS,
 * la ligne du caret LIBRE au milieu. Classe PURE (aucune dépendance
 * Android) : la règle se teste sans Canvas.
 *
 * <p>Notations : {@code A} = place disponible AU-DESSUS de la bande,
 * {@code B} = place EN DESSOUS, {@code g} = écart visuel. Règles, dans
 * cet ordre :</p>
 * <ol>
 *   <li>{@code hSig ≤ A} et {@code hCmp ≤ B} → signature au-dessus,
 *       complétion en dessous ;</li>
 *   <li>sinon {@code hCmp ≤ A} et {@code hSig ≤ B} → complétion
 *       au-dessus, signature en dessous ;</li>
 *   <li>sinon {@code hSig + hCmp + g ≤ A} → empilés AU-DESSUS, la
 *       complétion près du caret ;</li>
 *   <li>sinon empilés EN DESSOUS, la signature près du caret — le
 *       clamp final N'ENTRE JAMAIS dans la bande : la complétion est
 *       ROGNÉE (hauteur disponible retournée) avant de recouvrir.</li>
 * </ol>
 *
 * <p>Le clavier logiciel ouvert (adjustResize) réduit {@code B} :
 * l'appelant passe la hauteur RÉELLE de la vue, la règle s'adapte
 * d'elle-même.</p>
 */
public final class PopupPlacement {

    /** Règle 1 : signature au-dessus, complétion en dessous. */
    public static final int RULE_SIG_ABOVE = 1;
    /** Règle 2 : complétion au-dessus, signature en dessous. */
    public static final int RULE_CMP_ABOVE = 2;
    /** Règle 3 : empilés au-dessus, complétion près du caret. */
    public static final int RULE_STACKED_ABOVE = 3;
    /** Règle 4 : empilés en dessous, signature près du caret. */
    public static final int RULE_STACKED_BELOW = 4;

    /**
     * Le placement des deux popups : Y du haut de chacun (le popup
     * absent a un Y à {@link Float#NaN}), la règle appliquée et — règle 4
     * seulement — la hauteur DONT la complétion dispose (rognée : le
     * clamp final ne recouvre jamais la bande du caret).
     */
    public static final class Placements {
        /** Haut de la signature (px), NaN si absente. */
        public final float sigTop;
        /** Haut de la complétion (px), NaN si absente. */
        public final float cmpTop;
        /** Hauteur disponible de la complétion (px) — vaut hCmp hors
         *  règle 4, la hauteur rognée en règle 4 (peut être 0). */
        public final float cmpHeight;
        /** La règle appliquée (RULE_*). */
        public final int rule;

        Placements(float sigTop, float cmpTop, float cmpHeight, int rule) {
            this.sigTop = sigTop;
            this.cmpTop = cmpTop;
            this.cmpHeight = cmpHeight;
            this.rule = rule;
        }

        /** La signature est-elle présente ? */
        public boolean aSignature() { return !Float.isNaN(sigTop); }

        /** La complétion est-elle présente ? */
        public boolean aComplétion() { return !Float.isNaN(cmpTop); }
    }

    private PopupPlacement() {}

    /**
     * Applique la règle de placement coordonné.
     *
     * @param viewH la hauteur RÉELLE de la vue (clavier compris —
     *              adjustResize la réduit, la règle s'adapte)
     * @param bandTop le haut ÉCRAN de la bande du caret (fold+wrap aware)
     * @param bandBottom le bas ÉCRAN de la bande du caret
     * @param hSig la hauteur du popup signature (0 = absent)
     * @param hCmp la hauteur du popup complétion (0 = absent)
     * @param gap l'écart visuel entre popup et bande (g)
     * @param margin la marge verticale minimale aux bords de la vue
     * @return le placement (jamais null)
     */
    public static Placements place(float viewH, float bandTop, float bandBottom,
            float hSig, float hCmp, float gap, float margin) {
        boolean sig = hSig > 0f;
        boolean cmp = hCmp > 0f;
        float a = Math.max(0f, bandTop - gap);            // place au-dessus
        float b = Math.max(0f, viewH - margin - (bandBottom + gap)); // place en dessous

        // ── Règle 1 : signature au-dessus, complétion en dessous.
        if ((!sig || hSig <= a) && (!cmp || hCmp <= b)) {
            return new Placements(
                    sig ? bandTop - gap - hSig : Float.NaN,
                    cmp ? bandBottom + gap : Float.NaN,
                    hCmp, RULE_SIG_ABOVE);
        }
        // ── Règle 2 : complétion au-dessus, signature en dessous.
        if ((!cmp || hCmp <= a) && (!sig || hSig <= b)) {
            return new Placements(
                    sig ? bandBottom + gap : Float.NaN,
                    cmp ? bandTop - gap - hCmp : Float.NaN,
                    hCmp, RULE_CMP_ABOVE);
        }
        // ── Règle 3 : empilés AU-DESSUS, complétion près du caret.
        if ((!sig && !cmp) || (hSig + hCmp + gap <= a)) {
            float cmpTop = bandTop - gap - hCmp;
            float sigTop = cmpTop - gap - hSig;
            return new Placements(sig ? sigTop : Float.NaN,
                    cmp ? cmpTop : Float.NaN, hCmp, RULE_STACKED_ABOVE);
        }
        // ── Règle 4 : empilés EN DESSOUS, signature près du caret.
        // Le clamp final ne recouvre JAMAIS la bande : la complétion
        // est ROGNÉE à la place qui reste sous la signature.
        float sigTop = bandBottom + gap;
        if (sig && sigTop + hSig > viewH - margin) {
            // La signature elle-même déborde : remontée au maximum SANS
            // entrer dans la bande (la complétion avait déjà cédé).
            sigTop = Math.max(margin, Math.min(sigTop, viewH - margin - hSig));
            if (sigTop < bandBottom + gap && sigTop + hSig > bandTop) {
                // Dernier recours : la signature se colle SOUS la bande.
                sigTop = bandBottom + gap;
            }
        }
        float cmpTop = sig ? sigTop + hSig + gap : bandBottom + gap;
        float dispo = Math.max(0f, viewH - margin - cmpTop);
        return new Placements(sig ? sigTop : Float.NaN,
                cmp ? cmpTop : Float.NaN, dispo, RULE_STACKED_BELOW);
    }
}
