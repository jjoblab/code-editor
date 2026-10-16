package jo.codeeditor.view.preview;

/**
 * Géométrie pure de la carte d'aperçu « goutte d'eau » (B27) —
 * l'algorithme de placement anti-recouvrement, SANS dépendance Android :
 * des floats en pixels écran entrent, un placement sort. Testable en
 * JUnit pur, partagé par le peintre (dessin) et le contrôleur
 * (hit-test) — la même formule au pixel, aucun état mutable.
 *
 * <h3>L'algorithme (maquette validée — 3 niveaux)</h3>
 * <ol>
 *   <li><b>Ancré</b> — la carte se pose pleine taille, goutte pointée
 *       sur l'œil de gouttière, SI le caret est loin (aucun
 *       recouvrement de la zone de frappe) ;</li>
 *   <li><b>Esquive entière</b> — sinon la carte glisse ENTIÈREMENT
 *       au-dessus ou en dessous du bloc de frappe (caret ± 1 ligne),
 *       du côté le plus dégagé, pleine taille ;</li>
 *   <li><b>Compactage</b> — si aucun côté n'est assez grand, la carte
 *       se réduit (échelle bornée) pour tenir dans le plus grand côté.
 *       Elle ne recouvre <b>jamais</b> la ligne où l'utilisateur tape.</li>
 * </ol>
 *
 * <p>La carte reste ensuite clampée dans l'éditeur (bords), la goutte
 * reste visible sur le bord gauche (bornée dans la hauteur visuelle),
 * et une tige fine relie l'œil à la goutte quand la carte s'est
 * esquivée loin.</p>
 *
 * <h3>Invariants (vérifiés par les tests sur balayage de positions)</h3>
 * <ul>
 *   <li>le rectangle VISUEL de la carte ne recouvre jamais la zone de
 *       frappe {@code [guardTop, guardBot]} (tant que la carte tient
 *       dans l'éditeur à l'échelle minimale) ;</li>
 *   <li>le rectangle visuel reste dans les bornes de l'éditeur
 *       (marge {@code edgePad}) ;</li>
 *   <li>l'échelle reste dans {@code [minScale, 1]} ;</li>
 *   <li>la goutte reste dans la hauteur visuelle de la carte.</li>
 * </ul>
 *
 * @author jjoblab
 */
public final class EditorPreviewDropGeometry {

    private EditorPreviewDropGeometry() {}

    /**
     * Un placement calculé — tout en pixels écran, cohérent frame par
     * frame. Les coordonnées LOCALES (carte non mise à l'échelle) sont
     * exposées pour le dessin ; les VISUELLES (après échelle, autour du
     * coin haut-gauche) pour le clamp et le hit-test.
     */
    public static final class Placement {

        /** Coin haut-gauche VISUEL de la carte (px écran). */
        public final float cardLeft, cardTop;
        /** Taille LOCALE de la carte (px, avant échelle — le dessin y vit). */
        public final float cardW, cardH;
        /** Facteur de compactage — 1 pleine taille, {@code minScale} au plancher. */
        public final float scale;
        /**
         * Centre Y de la goutte en coordonnées LOCALES (px) — le losange
         * qui pointe l'œil. Le centre Y écran vaut
         * {@code cardTop + dropLocalY * scale}.
         */
        public final float dropLocalY;
        /** Vrai quand la tige de liaison œil→goutte se dessine. */
        public final boolean stemVisible;
        /** Départ de la tige (bord droit de l'œil) — px écran. */
        public final float stemX0, stemY0;
        /** Arrivée de la tige (centre de la goutte) — px écran. */
        public final float stemX1, stemY1;
        /** Niveau appliqué : 1 ancré, 2 esquive entière, 3 compactage. */
        public final int level;

        Placement(float cardLeft, float cardTop, float cardW, float cardH,
                float scale, float dropLocalY, boolean stemVisible,
                float stemX0, float stemY0, float stemX1, float stemY1,
                int level) {
            this.cardLeft = cardLeft;
            this.cardTop = cardTop;
            this.cardW = cardW;
            this.cardH = cardH;
            this.scale = scale;
            this.dropLocalY = dropLocalY;
            this.stemVisible = stemVisible;
            this.stemX0 = stemX0;
            this.stemY0 = stemY0;
            this.stemX1 = stemX1;
            this.stemY1 = stemY1;
            this.level = level;
        }

        /** Largeur visuelle de la carte (px). */
        public float visualWidth() { return cardW * scale; }

        /** Hauteur visuelle de la carte (px). */
        public float visualHeight() { return cardH * scale; }

        /** Centre Y écran de la goutte (px). */
        public float dropY() { return cardTop + dropLocalY * scale; }

        /**
         * Vrai si le point écran (x, y) tombe dans le rectangle VISUEL
         * de la carte — hit-test d'englobement du contrôleur.
         */
        public boolean contains(float x, float y) {
            return x >= cardLeft && x <= cardLeft + visualWidth()
                    && y >= cardTop && y <= cardTop + visualHeight();
        }

        /**
         * Traduit une coordonnée écran en coordonnée LOCALE de carte
         * (inverse de l'échelle) — le hit-test des onglets/fermeture
         * partage ainsi les rectangles du dessin.
         */
        public float toLocalX(float screenX) { return (screenX - cardLeft) / scale; }

        /** Miroir vertical de {@link #toLocalX(float)}. */
        public float toLocalY(float screenY) { return (screenY - cardTop) / scale; }
    }

    /**
     * Calcule le placement de la carte pour l'état courant de l'éditeur.
     *
     * @param editorW  largeur de l'éditeur (px)
     * @param editorH  hauteur de l'éditeur (px)
     * @param cardW    largeur LOCALE de la carte (px)
     * @param cardH    hauteur LOCALE de la carte (px)
     * @param eyeX     centre X de la pill œil de gouttière (px écran)
     * @param eyeY     centre Y de la pill œil (px écran)
     * @param eyeR     demi-taille de la pill œil (px) — la carte part
     *                 juste après
     * @param guardTop haut de la zone de frappe protégée — caret ± 1
     *                 ligne (px écran)
     * @param guardBot bas de la zone de frappe (px écran)
     * @param edgePad  marge aux bords de l'éditeur (px)
     * @param anchorDropOffset décalage entre le haut de carte et la
     *                 goutte en ancré (px — la goutte pointe l'œil)
     * @param minScale plancher d'échelle du compactage (0,58 maquette)
     * @param stemThreshold écart œil↔goutte au-delà duquel la tige se
     *                 dessine (px)
     * @return le placement (jamais null)
     */
    public static Placement place(
            float editorW, float editorH,
            float cardW, float cardH,
            float eyeX, float eyeY, float eyeR,
            float guardTop, float guardBot,
            float edgePad, float anchorDropOffset,
            float minScale, float stemThreshold) {

        // ── Niveau 1 : position ancrée à l'œil, pleine taille. ──────
        // La goutte (à anchorDropOffset du haut de carte) pointe l'œil :
        // le haut de carte = œil − décalage.
        float top = eyeY - anchorDropOffset;
        float scale = 1f;
        int level = 1;
        boolean overlap = top < guardBot && top + cardH > guardTop;

        if (overlap && guardBot > guardTop) {
            float freeAbove = Math.max(0f, guardTop - edgePad);
            float freeBelow = Math.max(0f, editorH - guardBot - edgePad);
            if (Math.max(freeAbove, freeBelow) >= cardH) {
                // ── Niveau 2 : un côté entier est libre → glissement. ──
                if (freeBelow >= freeAbove) {
                    top = guardBot + edgePad;
                } else {
                    top = guardTop - cardH - edgePad;
                }
                level = 2;
            } else {
                // ── Niveau 3 : pas de place d'un côté entier →
                // compactage dans le plus grand côté. ──────────────────
                boolean below = freeBelow >= freeAbove;
                float free = Math.max(freeBelow, freeAbove);
                scale = Math.max(minScale, free / cardH);
                float visualH = cardH * scale;
                // Marge resserrée (la carte compactée se serre près du
                // bloc de frappe — le clamp vertical veille aux bornes).
                top = below
                        ? guardBot + edgePad
                        : Math.max(edgePad, guardTop - visualH - edgePad);
                level = 3;
            }
        }

        // ── Clamps : la carte reste dans l'éditeur. ──────────────────
        float visualH = cardH * scale;
        float maxTop = editorH - visualH - edgePad;
        if (maxTop < edgePad) maxTop = edgePad; // éditeur plus petit que la carte : plancher
        top = Math.max(edgePad, Math.min(top, maxTop));

        // ── Gauche : juste après l'œil, clampée à l'éditeur. ─────────
        float visualW = cardW * scale;
        float left = eyeX + eyeR + edgePad;
        if (left + visualW > editorW - edgePad) {
            left = Math.max(edgePad, editorW - visualW - edgePad);
        }
        // Œil hors de l'éditeur (gouttière masquée) : bord gauche simple.
        if (eyeX < 0f) left = edgePad;

        // ── Goutte : bord gauche, à la hauteur de l'œil, bornée dans
        // la hauteur LOCALE de la carte (le dessin pivote autour
        // d'elle, elle reste donc toujours visible). ──────────────────
        float dropLocalY = (eyeY - top) / scale;
        float dropMargin = anchorDropOffset; // même ordre de grandeur
        if (dropMargin * 2f > cardH) dropMargin = cardH * 0.5f;
        dropLocalY = Math.max(dropMargin, Math.min(dropLocalY, cardH - dropMargin));

        // ── Tige : l'œil et la goutte se sont écartés → on relie. ────
        float dropY = top + dropLocalY * scale;
        boolean eyeOnScreen = eyeY >= 0f && eyeY <= editorH;
        boolean stem = eyeOnScreen && Math.abs(eyeY - dropY) > stemThreshold;
        float stemX0 = eyeX + eyeR;
        float stemY0 = eyeY;
        float stemX1 = left;
        float stemY1 = dropY;

        return new Placement(left, top, cardW, cardH, scale, dropLocalY,
                stem, stemX0, stemY0, stemX1, stemY1, level);
    }
}
