package jo.codeeditor.view.preview;

/**
 * Géométrie pure de la carte d'aperçu « goutte d'eau » (B27) —
 * l'algorithme de placement anti-recouvrement, SANS dépendance Android :
 * des floats en pixels écran entrent, un placement sort. Testable en
 * JUnit pur, partagé par le peintre (dessin) et le contrôleur
 * (hit-test) — la même formule au pixel, aucun état mutable.
 *
 * <h3>L'algorithme (maquette validée + retour terrain B27)</h3>
 * <ol>
 *   <li><b>Ancré</b> — la carte se pose pleine taille, goutte pointée
 *       sur l'œil de gouttière, SI le caret est loin ET qu'aucun popup
 *       vivant ne se trouve sur sa trajectoire ;</li>
 *   <li><b>Esquive entière</b> — sinon la carte cherche un <b>creux
 *       libre</b> : l'espace vertical hors de la bande de frappe
 *       (caret ± 1 ligne) ET hors des rectangles des popups vivants
 *       (complétion, doc rapide… fournis par le contrôleur). Elle
 *       glisse entière dans le plus grand creux qui la tient ;</li>
 *   <li><b>Latéral</b> (grand écran) — si aucun creux ne tient la
 *       carte pleine taille mais qu'il reste de la place à DROITE de
 *       l'obstacle (complétion + carte côte à côte), la carte quitte
 *       sa colonne de gouttière et se pose à droite, entière — la
 *       tige la relie à l'œil ;</li>
 *   <li><b>Compactage</b> — sinon la carte se réduit (échelle bornée)
 *       pour tenir dans le plus grand creux. Elle ne recouvre
 *       <b>jamais</b> la ligne où l'utilisateur tape.</li>
 * </ol>
 *
 * <p>Si même le compactage ne peut éviter un obstacle (petit écran,
 * popups partout), le placement final porte le drapeau
 * {@link Placement#obstructed} et le ratio {@link
 * Placement#overlapRatio} : le contrôleur déclenche alors le
 * <b>voile d'eau</b> (transparence fluide de la carte, retour terrain
 * B27) — l'esquive a tout tenté, l'eau devient translucide.</p>
 *
 * <p>La carte reste clampée dans l'éditeur (bords), la goutte reste
 * visible sur le bord gauche (bornée dans la hauteur visuelle), et
 * une tige fine relie l'œil à la goutte quand la carte s'est esquivée
 * loin.</p>
 *
 * <h3>Invariants (vérifiés par les tests sur balayage de positions)</h3>
 * <ul>
 *   <li>le rectangle VISUEL de la carte ne recouvre jamais la zone de
 *       frappe {@code [guardTop, guardBot]} (tant que la carte tient
 *       dans l'éditeur à l'échelle minimale) ;</li>
 *   <li>idem pour tout obstacle, tant qu'un creux (même compacté) ou
 *       une place latérale existe — sinon {@code obstructed} est posé
 *       honnêtement et le voile prend le relais ;</li>
 *   <li>le rectangle visuel reste dans les bornes de l'éditeur
 *       (marge {@code edgePad}) ;</li>
 *   <li>l'échelle reste dans {@code [minScale, 1]} ;</li>
 *   <li>la goutte reste dans la hauteur visuelle de la carte.</li>
 * </ul>
 *
 * <h3>Compatibilité arrière</h3>
 * <p>Sans obstacle ({@code null} ou vide), l'algorithme se réduit
 * EXACTEMENT aux formules historiques validées par la QA maquette :
 * les spans bloquants se résument à la bande de frappe, le creux
 * au-dessus/dessous reproduit l'esquive entière, et le repli de
 * compactage garde l'ancien comportement (plancher d'échelle atteignable
 * ou non). L'ancienne signature 13 paramètres délègue.</p>
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
        /**
         * Niveau appliqué : 1 ancré, 2 esquive entière dans un creux,
         * 3 compactage, 4 latéral à droite d'un obstacle (grand écran).
         */
        public final int level;
        /**
         * Recouvrement restant du pire obstacle (fraction de l'AIRE de
         * l'obstacle couverte par la carte, 0..1) — l'esquive a tout
         * tenté, ce qui reste nourrit le voile d'eau du contrôleur.
         */
        public final float overlapRatio;
        /**
         * Vrai quand la carte recouvre encore un obstacle après tous les
         * niveaux — le contrôleur voile la carte (alpha fluide) au lieu
         * de la laisser cacher le popup.
         */
        public final boolean obstructed;

        Placement(float cardLeft, float cardTop, float cardW, float cardH,
                float scale, float dropLocalY, boolean stemVisible,
                float stemX0, float stemY0, float stemX1, float stemY1,
                int level, float overlapRatio, boolean obstructed) {
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
            this.overlapRatio = overlapRatio;
            this.obstructed = obstructed;
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
     * Calcule le placement de la carte pour l'état courant de l'éditeur
     * (variante historique SANS obstacles — délègue avec {@code null}).
     */
    public static Placement place(
            float editorW, float editorH,
            float cardW, float cardH,
            float eyeX, float eyeY, float eyeR,
            float guardTop, float guardBot,
            float edgePad, float anchorDropOffset,
            float minScale, float stemThreshold) {
        return place(editorW, editorH, cardW, cardH, eyeX, eyeY, eyeR,
                guardTop, guardBot, null,
                edgePad, anchorDropOffset, minScale, stemThreshold);
    }

    /**
     * Calcule le placement de la carte pour l'état courant de l'éditeur,
     * obstacles compris.
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
     * @param obstacles rectangles écran des popups vivants à esquiver,
     *                 tassés {@code [left, top, right, bottom]} × N
     *                 (px) ; {@code null} ou vide = aucun (comportement
     *                 historique exact)
     * @param edgePad  marge aux bords de l'éditeur (px) — aussi la marge
     *                 laissée entre la carte esquivée et ce qu'elle
     *                 esquive
     * @param anchorDropOffset décalage entre le haut de carte et la
     *                 goutte en ancré (px — la goutte pointe l'œil)
     * @param minScale plancher d'échelle du compactage (0,58 maquette)
     * @param stemThreshold écart œil↔goutte au-delà duquel la tige se
     *                 dessine (px)
     * @return le placement (jamais null) — avec {@code obstructed} et
     *         {@code overlapRatio} quand l'esquive a échoué partout
     */
    public static Placement place(
            float editorW, float editorH,
            float cardW, float cardH,
            float eyeX, float eyeY, float eyeR,
            float guardTop, float guardBot,
            float[] obstacles,
            float edgePad, float anchorDropOffset,
            float minScale, float stemThreshold) {

        // ── Colonne par défaut de la carte (pleine taille — le filtre
        //    des obstacles bloquants est conservateur : la carte
        //    compactée ne s'élargit jamais). ──────────────────────────
        float defaultLeft = eyeX + eyeR + edgePad;
        if (defaultLeft + cardW > editorW - edgePad) {
            defaultLeft = Math.max(edgePad, editorW - cardW - edgePad);
        }
        if (eyeX < 0f) defaultLeft = edgePad;

        // ── Niveau 1 : position ancrée à l'œil, pleine taille. ──────
        float anchoredTop = eyeY - anchorDropOffset;
        float scale = 1f;
        int level = 1;
        float left = defaultLeft;
        float top = anchoredTop;
        boolean anchoredOverlaps = (guardBot > guardTop
                && top < guardBot && top + cardH > guardTop)
                || overlapsAnyObstacle(left, top, cardW, cardH, obstacles, -1);

        if (anchoredOverlaps) {
            // ── Spans bloquants verticaux, inflés d'edgePad : bande de
            //    frappe + obstacles qui croisent la colonne par défaut.
            //    (Inflés : la carte esquivée laisse edgePad entre elle
            //    et ce qu'elle esquive — miroir des formules
            //    historiques du niveau 2/3 sans obstacle.) ────────────
            float[] spans = blockingSpans(guardTop, guardBot, obstacles,
                    defaultLeft, cardW, edgePad);

            // ── Niveau 2 : un creux libre tient la carte pleine ─────
            //    taille → glissement entier dans le plus grand creux
            //    (égalité → le plus bas, miroir du « côté le plus
            //    dégagé » historique qui préférait le dessous). ──────
            int nSpans = spans.length / 2;
            float gapTop = 0f, gapBot = -1f;      // meilleur creux ≥ cardH
            float bestFitH = -1f;
            float compTop = 0f, compBot = -1f;    // plus grand creux (compactage)
            float bestCompH = -1f;
            float cursor = edgePad;
            for (int i = 0; i <= nSpans; i++) {
                float sTop = i < nSpans ? spans[i * 2] : editorH - edgePad;
                float sBot = i < nSpans ? spans[i * 2 + 1] : editorH - edgePad;
                // Creux entre le curseur et le span (ou le bas pour le
                // dernier « span » sentinelle). Un span peut chevaucher
                // le curseur (fusion garantie, mais par prudence).
                float gTop = Math.max(cursor, edgePad);
                float gBot = Math.min(sTop, editorH - edgePad);
                if (gBot > gTop) {
                    float h = gBot - gTop;
                    if (h >= cardH && h >= bestFitH) {
                        bestFitH = h; gapTop = gTop; gapBot = gBot;
                    }
                    if (h >= bestCompH) {
                        bestCompH = h; compTop = gTop; compBot = gBot;
                    }
                }
                cursor = Math.max(cursor, sBot);
            }

            if (bestFitH >= 0f) {
                // ── Niveau 2 : glissement entier, huggé à ce qui est ─
                //    esquivé (la carte reste au plus près de son ─────
                //    ancrage que le creux le permet). ─────────────────
                top = Math.max(gapTop, Math.min(anchoredTop, gapBot - cardH));
                level = 2;
            } else {
                // ── Niveau 4 (grand écran) : latéral à DROITE de ─────
                // l'obstacle, pleine taille. La bande de frappe reste ─
                // intouchable et les autres obstacles doivent être ────
                // dégagés ; on garde le candidat le plus à GAUCHE ─────
                // (le plus proche de la colonne de gouttière). ────────
                float latTop = Math.max(edgePad, Math.min(anchoredTop,
                        Math.max(edgePad, editorH - cardH - edgePad)));
                float bestLeft = Float.MAX_VALUE;
                if (obstacles != null) {
                    for (int i = 0; i < obstacles.length; i += 4) {
                        float latLeft = obstacles[i + 2] + edgePad;
                        if (latLeft + cardW > editorW - edgePad) continue;
                        if (latLeft >= bestLeft) continue;
                        if (guardBot > guardTop
                                && latTop < guardBot && latTop + cardH > guardTop) continue;
                        if (overlapsAnyObstacle(latLeft, latTop, cardW, cardH,
                                obstacles, i)) continue;
                        bestLeft = latLeft;
                    }
                }
                if (bestLeft != Float.MAX_VALUE) {
                    left = bestLeft;
                    top = latTop;
                    level = 4;
                } else {
                    // ── Niveau 3 : compactage dans le plus grand ─────
                    // creux (comportement historique : l'échelle est ──
                    // bornée au plancher même si le creux est trop ────
                    // petit — éditeur minuscule, le clamp veille aux ──
                    // bornes). ────────────────────────────────────────
                    scale = Math.max(minScale, bestCompH < 0f ? 0f : bestCompH / cardH);
                    float visualH = cardH * scale;
                    if (bestCompH < 0f) {
                        // Aucun creux du tout : repli ancré, clampé.
                        top = anchoredTop;
                    } else {
                        top = Math.max(compTop, Math.min(anchoredTop, compBot - visualH));
                    }
                    level = 3;
                }
            }
        }

        // ── Clamps : la carte reste dans l'éditeur. ──────────────────
        float visualH = cardH * scale;
        float maxTop = editorH - visualH - edgePad;
        if (maxTop < edgePad) maxTop = edgePad; // éditeur plus petit que la carte : plancher
        top = Math.max(edgePad, Math.min(top, maxTop));

        // ── Gauche : juste après l'œil, clampée à l'éditeur (le latéral
        //    est déjà posé et validé pleine taille — la carte y reste). ─
        float visualW = cardW * scale;
        if (level != 4) {
            left = eyeX + eyeR + edgePad;
            if (left + visualW > editorW - edgePad) {
                left = Math.max(edgePad, editorW - visualW - edgePad);
            }
            // Œil hors de l'éditeur (gouttière masquée) : bord gauche simple.
            if (eyeX < 0f) left = edgePad;
        }

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

        // ── Recouvrement restant (voile d'eau) : l'esquive a tout ────
        //    tenté — ce qui reste du pire obstacle nourrit le voile. ─
        float overlapRatio = 0f;
        boolean obstructed = false;
        if (obstacles != null) {
            for (int i = 0; i < obstacles.length; i += 4) {
                float ow = obstacles[i + 2] - obstacles[i];
                float oh = obstacles[i + 3] - obstacles[i + 1];
                if (ow <= 0f || oh <= 0f) continue;
                float ix = Math.min(left + visualW, obstacles[i + 2])
                        - Math.max(left, obstacles[i]);
                float iy = Math.min(top + visualH, obstacles[i + 3])
                        - Math.max(top, obstacles[i + 1]);
                if (ix > 0f && iy > 0f) {
                    obstructed = true;
                    overlapRatio = Math.max(overlapRatio, (ix * iy) / (ow * oh));
                }
            }
        }

        return new Placement(left, top, cardW, cardH, scale, dropLocalY,
                stem, stemX0, stemY0, stemX1, stemY1, level,
                overlapRatio, obstructed);
    }

    // ── Aides pures (aucun état, aucun Android) ─────────────────────

    /**
     * Vrai si le rectangle (l, t, w, h) recouvre un obstacle (le index
     * {@code skip} est exclu — candidat latéral testé contre les
     * AUTRES).
     */
    private static boolean overlapsAnyObstacle(float l, float t,
            float w, float h, float[] obstacles, int skip) {
        if (obstacles == null) return false;
        for (int i = 0; i < obstacles.length; i += 4) {
            if (i == skip) continue;
            if (l < obstacles[i + 2] && l + w > obstacles[i]
                    && t < obstacles[i + 3] && t + h > obstacles[i + 1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Construit les spans verticaux bloquants, inflés d'{@code edgePad}
     * et fusionnés, triés par haut : la bande de frappe + les obstacles
     * qui croisent horizontalement la colonne par défaut de la carte
     * (un obstacle à côté de la colonne ne bloque rien). Retour tassé
     * {@code [top, bottom]} × N (jamais null, possiblement vide).
     */
    private static float[] blockingSpans(float guardTop, float guardBot,
            float[] obstacles, float defaultLeft, float cardW, float edgePad) {
        int max = 1 + (obstacles == null ? 0 : obstacles.length / 4);
        float[] out = new float[max * 2];
        int n = 0;
        if (guardBot > guardTop) {
            out[n++] = guardTop - edgePad;
            out[n++] = guardBot + edgePad;
        }
        if (obstacles != null) {
            for (int i = 0; i < obstacles.length; i += 4) {
                boolean xOverlap = defaultLeft < obstacles[i + 2]
                        && defaultLeft + cardW > obstacles[i];
                if (xOverlap) {
                    out[n++] = obstacles[i + 1] - edgePad;
                    out[n++] = obstacles[i + 3] + edgePad;
                }
            }
        }
        // Tri par haut (insertion — n ≤ 5, la lisibilité prime).
        for (int i = 2; i < n; i += 2) {
            float t0 = out[i], b0 = out[i + 1];
            int j = i - 2;
            while (j >= 0 && out[j] > t0) {
                out[j + 2] = out[j];
                out[j + 3] = out[j + 1];
                j -= 2;
            }
            out[j + 2] = t0;
            out[j + 3] = b0;
        }
        // Fusion des spans qui se chevauchent (ou se touchent).
        int merged = 0;
        for (int i = 0; i < n; i += 2) {
            if (merged > 0 && out[i] <= out[(merged - 1) * 2 + 1]) {
                out[(merged - 1) * 2 + 1] = Math.max(out[(merged - 1) * 2 + 1], out[i + 1]);
            } else {
                out[merged * 2] = out[i];
                out[merged * 2 + 1] = out[i + 1];
                merged++;
            }
        }
        float[] result = new float[merged * 2];
        System.arraycopy(out, 0, result, 0, merged * 2);
        return result;
    }
}
