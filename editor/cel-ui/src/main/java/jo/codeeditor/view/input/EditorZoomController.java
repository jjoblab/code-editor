package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

/**
 * État de zoom extrait d'EditorView (volet métriques/zoom de la
 * délégation vers les gestionnaires dédiés).
 *
 * <p>Possède l'échelle de police et tous les moyens de la modifier :
 * {@link #setFontScale(float)} direct, pincement ancré sur le caret
 * {@link #applyPinchScale(float)} (le caret reste visuellement fixe
 * pendant que le viewport défile virtuellement sous lui), et les aides
 * {@code increaseFontSize}/{@code decreaseFontSize}. Les corps proviennent
 * tels quels d'EditorView, qui conserve des délégués avec les signatures
 * publiques historiques.</p>
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
        // Re-borne les offsets de défilement — la taille du contenu a changé.
        view.vOffset = EditorView.clamp(view.vOffset, 0, view.maxV());
        view.hOffset = EditorView.clamp(view.hOffset, 0, view.maxH());
        view.requestLayout();
        view.invalidate();
    }

public void applyPinchScale(float scaleFactor) {
        float newScale = clampFontScale(fontScale * scaleFactor);
        if (newScale == fontScale) return; // no-op (borne saturée)

        // (1) Capture la position ÉCRAN courante du caret (anciennes métriques).
        float cx = 0f, cy = 0f;
        boolean hasCaret = (view.session != null && !view.session.isReadOnly());
        if (hasCaret) {
            int caretOffset = view.session.getSelection().start;
            float[] pos = view.caretScreenPos(caretOffset);
            cx = pos[0];
            cy = pos[1];
        }

        // (2) Applique la nouvelle échelle (reflète le corps de setFontScale
        // mais sans requestLayout — le pincement se déclenche en continu,
        // requestLayout martèlerait le framework. Le onMeasure d'EditorView
        // sera de toute façon relancé par le prochain invalidate).
        fontScale = newScale;
        view.metrics.setTextSize(view.spToPx(EditorView.BASE_TEXT_SIZE_SP) * fontScale);

        // (3) S'il y avait un caret, ajuste les offsets pour le maintenir ancré.
        if (hasCaret) {
            // Recalcule la position du caret avec les NOUVELLES métriques et
            // les ANCIENS offsets — caretScreenPos lit vOffset/hOffset en
            // direct, ceci renvoie donc là où le caret ATTEINDRAIT si on ne
            // touchait pas les offsets.
            //
            // Math : on veut newPos_after == (cx, cy) (caret ancré).
            //   pos = anchor(line, col, metrics) - offset
            //   où anchor = padTop + line * lh (vertical), et
            //   gutterW + padLeft + col * charWidth (horizontal).
            //
            //   Avant le scale  : cy  = anchor_old - vOffset_old
            //   Après (métriques nouvelles, offsets inchangés) :
            //     newPos_y = anchor_new - vOffset_old
            //   On veut : anchor_new - vOffset_new = cy
            //     → vOffset_new = anchor_new - cy
            //                = (newPos_y + vOffset_old) - cy
            //                = vOffset_old + (newPos_y - cy)
            //
            //   Donc : vOffset += (newPos_y - cy)
            //   (et symétriquement hOffset += (newPos_x - cx))
            //
            // NOTE : le signe est (+) car on veut AMENER le caret à cy,
            // pas l'éloigner. C'est la position NOUVELLE (newPos) moins
            // l'ANCIENNE (cx, cy), ce qui est intuitif : "combien le caret
            // a bougé à cause du scale → compenser ce mouvement".
            float[] newPos = view.caretScreenPos(view.session.getSelection().start);
            float dx = newPos[0] - cx;
            float dy = newPos[1] - cy;
            view.hOffset += dx;
            view.vOffset += dy;
        }

        // (4) Borne à la plage de défilement valide (après changement d'échelle).
        view.vOffset = EditorView.clamp(view.vOffset, 0, view.maxV());
        view.hOffset = EditorView.clamp(view.hOffset, 0, view.maxH());

        // (5) Annule tout glissement de caret en cours — le caret est ancré
        // par notre ajustement d'offset, un glissement le surchargerait.
        if (view.caretAnim != null) {
            view.caretAnim.onEditOrMove();
        }

        view.invalidate();
    }

    // Méthodes de commodité pour la taille de police +/- depuis les icônes Canvas.
    public void increaseFontSize() {
        setFontScale(clampFontScale(fontScale * 1.15f));
    }

    public void decreaseFontSize() {
        setFontScale(clampFontScale(fontScale / 1.15f));
    }
}
