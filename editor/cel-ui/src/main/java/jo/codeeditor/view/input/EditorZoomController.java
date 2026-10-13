package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

/**
 * État de zoom extrait d'EditorView (volet métriques/zoom de la
 * délégation vers les gestionnaires dédiés).
 *
 * <p>Possède l'échelle de police et tous les moyens de la modifier :
 * {@link #setFontScale(float)} direct, pincement ancré
 * {@link #applyPinchScale(float, float, float)} (l'ancre prioritaire est
 * le CARET — il reste visuellement fixe pendant que le viewport défile
 * virtuellement sous lui, retour terrain : « le caret ne reste pas à
 * une position fixe » ; sans caret exploitable — fichier en lecture
 * seule, caret hors viewport — l'ancre devient le POINT FOCAL du
 * pincement, le contenu sous le milieu des deux doigts), et les aides
 * {@code increaseFontSize}/{@code decreaseFontSize}. Les corps
 * proviennent tels quels d'EditorView, qui conserve des délégués avec
 * les signatures publiques historiques.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class EditorZoomController {

    static final float MIN_FONT_SCALE = 0.6f;
    static final float MAX_FONT_SCALE = 2.6f;

    private final EditorView view;

    /** Échelle de police courante (1 = base). */
    public float fontScale = 1.0f;

    public EditorZoomController(EditorView view) {
        this.view = view;
    }

    /** Borne l'échelle à [0.6, 2.6]. */
    public static float clampFontScale(float s) {
        if (s < MIN_FONT_SCALE) return MIN_FONT_SCALE;
        if (s > MAX_FONT_SCALE) return MAX_FONT_SCALE;
        return s;
    }

    /** Définit directement l'échelle de police (bornée à [0.6, 2.6]). */
    public void setFontScale(float scale) {
        fontScale = clampFontScale(scale);
        view.metrics.setTextSize(view.spToPx(EditorView.BASE_TEXT_SIZE_SP) * fontScale);
        // La largeur de colonne a changé : reconstruire le modèle de wrap,
        // sinon les rangées restent calculées pour l'ancienne taille
        // (lignes débordant à droite, Y des lignes suivants faux).
        if (view.wordWrap) view.rebuildWrapModel();
        // Re-borne les offsets de défilement — la taille du contenu a changé.
        view.vOffset = EditorView.clamp(view.vOffset, 0, view.maxV());
        view.hOffset = EditorView.clamp(view.hOffset, 0, view.maxH());
        view.requestLayout();
        view.invalidate();
    }

    /**
     * Pincement SANS point focal connu — délègue avec le centre du
     * viewport comme ancre de repli (compatibilité API historique ;
     * le chemin gesture passe par
     * {@link #applyPinchScale(float, float, float)}).
     */
    public void applyPinchScale(float scaleFactor) {
        applyPinchScale(scaleFactor,
                view.getWidth() * 0.5f, view.getHeight() * 0.5f);
    }

    /**
     * B9 (retour terrain n°6) — applique un facteur de pincement en
     * gardant UN point d'ancrage visuellement fixe.
     *
     * <p>Ancre prioritaire : le CARET (offset exact — compensation par
     * offsetAt, la plus précise, consciente du wrap et des plis) quand
     * la session est éditable ET que le caret est dans le viewport.
     * Ancre de repli : le POINT FOCAL du pincement (contenu sous le
     * milieu des deux doigts, granularité ligne/colonne) — couvre les
     * fichiers en lecture seule et le caret hors écran, qui avant
     * n'avaient AUCUN ancrage (le contenu sautait aléatoirement sous le
     * pincement).</p>
     *
     * <p>Ancrage focal LIGNE : le HAUT de la ligne sous le point focal
     * reste à sa position écran (docLineToY avant/après le changement
     * d'échelle — fold-aware et wrap-aware par construction). Ancrage
     * focal COLONNE (hors wrap uniquement) : la colonne visuelle sous
     * le point focal reste à sa position écran (charWidth avant/après).</p>
     *
     * <p>Les offsets finaux restent bornés à [0, max] — au bord
     * amont/aval du document le contenu ne peut pas défiler au-delà :
     * l'ancre glisse alors du minimum inévitable (comportement de tout
     * éditeur : zoomer en haut du document rapproche le début du
     * contenu du bord).</p>
     *
     * @param scaleFactor facteur incrémental du détecteur (pas le facteur
     *                    total du geste)
     * @param focusX      X écran du point focal du pincement
     * @param focusY      Y écran du point focal du pincement
     */
    public void applyPinchScale(float scaleFactor, float focusX, float focusY) {
        float newScale = clampFontScale(fontScale * scaleFactor);
        if (newScale == fontScale) return; // no-op (borne saturée)

        // (1) Choisit l'ancre : le CARET dès que la session est éditable
        // (qu'il soit visible OU défilé hors écran — l'ancrage d'un point
        // hors écran est mathématiquement sain et stable, il reste hors
        // écran à la même place) ; sinon le point focal du pincement
        // (lecture seule / sans session — avant B9, ces cas n'avaient
        // AUCUN ancrage et le contenu sautait sous le pincement).
        boolean anchorCaret = false;
        int caretOffset = -1;
        if (view.session != null && !view.session.isReadOnly()) {
            caretOffset = view.session.getSelection().start;
            anchorCaret = true;
        }

        // (2) Capture la position ÉCRAN de l'ancre (anciennes métriques).
        float ax = focusX, ay = focusY;
        int anchorLine = -1;
        int anchorCol = -1;
        if (anchorCaret) {
            float[] pos = view.caretScreenPos(caretOffset);
            ax = pos[0];
            ay = pos[1];
        } else {
            // Ancre focale LIGNE (toujours) : le haut de la ligne sous le
            // point focal. Document nul → viewport tel quel (pas de
            // contenu à ancrer, l'échelle seule s'applique).
            if (view.session != null && view.session.getDocument() != null
                    && view.session.getDocument().lineCount() > 0) {
                anchorLine = view.docLineForScreenY(focusY);
                ay = view.docLineToY(anchorLine) - view.vOffset;
            }
            // Ancre focale COLONNE (hors wrap) : la colonne visuelle sous
            // le point focal. En wrap il n'y a aucun défilement
            // horizontal — hOffset reste 0, pas d'ancrage X nécessaire.
            if (!view.wordWrap) {
                float charWidth = view.metrics.getCharWidth();
                if (charWidth > 0f) {
                    float textAreaLeft = view.metrics.getGutterWidth()
                            + view.metrics.getPadLeft();
                    anchorCol = Math.max(0,
                            Math.round((focusX - textAreaLeft + view.hOffset)
                                    / charWidth));
                    ax = textAreaLeft + anchorCol * charWidth - view.hOffset;
                }
            }
        }

        // (3) Applique la nouvelle échelle (reflète le corps de
        // setFontScale mais sans requestLayout — le pincement se
        // déclenche en continu, requestLayout martèlerait le framework.
        // Le onMeasure d'EditorView sera de toute façon relancé par le
        // prochain invalidate).
        fontScale = newScale;
        view.metrics.setTextSize(view.spToPx(EditorView.BASE_TEXT_SIZE_SP) * fontScale);
        // Largeur de colonne changée : reconstruire le modèle de wrap pour
        // que les rangées reflètent la nouvelle taille (comme setFontScale).
        if (view.wordWrap) view.rebuildWrapModel();

        // (4) Compense les offsets pour ramener l'ancre à (ax, ay).
        if (anchorCaret) {
            // Offset exact : caretScreenPos avec les NOUVELLES métriques
            // et les ANCIENS offsets — renvoie là où le caret ATTEINDRAIT
            // si on ne touchait pas les offsets ; on compense ce mouvement.
            //
            // Math : pos = anchor(line, col, metrics) - offset, donc
            //   vOffset_new = vOffset_old + (newPos_y - ay)
            // (et symétriquement hOffset_new = hOffset_old + (newPos_x - ax))
            // — "combien l'ancre a bougé à cause du scale → compenser".
            float[] newPos = view.caretScreenPos(caretOffset);
            view.hOffset += newPos[0] - ax;
            view.vOffset += newPos[1] - ay;
        } else if (anchorLine >= 0) {
            // Ligne focale : le HAUT de la ligne ancrée revient à ay.
            view.vOffset += (view.docLineToY(anchorLine) - view.vOffset) - ay;
        }
        if (!anchorCaret && anchorCol >= 0 && !view.wordWrap) {
            // Colonne focale : la colonne ancrée revient à ax.
            float textAreaLeft = view.metrics.getGutterWidth()
                    + view.metrics.getPadLeft();
            view.hOffset += (textAreaLeft + anchorCol * view.metrics.getCharWidth()
                    - view.hOffset) - ax;
        }

        // (5) Borne à la plage de défilement valide (après changement
        // d'échelle). Au bord du document l'ancre glisse du minimum
        // inévitable (pas de contenu au-dessus de la ligne 0).
        view.vOffset = EditorView.clamp(view.vOffset, 0, view.maxV());
        view.hOffset = EditorView.clamp(view.hOffset, 0, view.maxH());

        // (6) Annule tout glissement de caret en cours — l'ancre est
        // maintenue par notre ajustement d'offset, un glissement le
        // surchargerait.
        if (view.caretAnim != null) {
            view.caretAnim.onEditOrMove();
        }

        view.invalidate();
    }

    // Méthodes de commodité pour la taille de police +/- — appelées par
    // les barres d'hôte (BreadcrumbBar A+ / A−) et l'API publique ; les
    // icônes canvas historiques ont été retirées avec l'extraction
    // d'EditorBarTools (plus aucune zone de tap fantôme, UX-5).
    public void increaseFontSize() {
        setFontScale(clampFontScale(fontScale * 1.15f));
    }

    public void decreaseFontSize() {
        setFontScale(clampFontScale(fontScale / 1.15f));
    }
}
