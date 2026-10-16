package jo.codeeditor.view.popup;

import jo.codeeditor.view.ColorPicker;
import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.graphics.Color;

import jo.codeeditor.document.EditorDocument;

/**
 * Sélecteur de couleur ancré (B26) : un tap au cœur d'un code couleur
 * hexadécimal (<b>#RGB, #RRGGBB, #AARRGGBB</b> — le swatch inline est
 * déjà dessiné par le renderer) ouvre une carte <b>non modale</b> :
 * champ saturation/valeur, bande de teinte et palette de teintes
 * courantes. Le pick <b>remplace le code tapé</b> en conservant son
 * format (3/6/8 chiffres) et son alpha d'origine — le pipeline
 * d'édition habituel ({@code replaceRangeWithCaret}) rend le pick
 * annulable d'un undo.
 *
 * <p><b>Contrat des gestes</b> (miroir NavMenu/pill — la carte
 * engloutit son geste) :</p>
 * <ul>
 *   <li><b>DOWN</b> dans la carte ({@code EditorInputHandler}) :
 *       press du champ SV ou de la bande de teinte — le curseur y
 *       saute immédiatement, le drag suivant le porte ;</li>
 *   <li><b>MOVE</b> : mise à jour vivante de la couleur en cours
 *       (aperçu dans l'en-tête, markers recalculés) ;</li>
 *   <li><b>UP</b> : tap sur une teinte de la palette → application
 *       directe ; fin d'un press champ/bande → application de la
 *       couleur en cours ; tap ailleurs dans la carte → rien
 *       (englouti, le caret ne bouge pas) ;</li>
 *   <li><b>DOWN hors carte</b> ou toute édition du document →
 *       fermeture sans application (le tap derrière continue sa
 *       cascade — placement de caret).</li>
 * </ul>
 *
 * <p>Le tap qui OUvre ne pose pas le caret (précédent popup ancré de
 * squiggle D1 : bords ±1 caractère exclus — un tap juste avant/après
 * le code reste un placement de caret). L'état vit dans EditorView
 * ({@code colorPickerVisible}/{@code colorPickerStart/End},
 * {@code colorPickerOriginal/Current}, {@code colorPickerDigits}) ;
 * la géométrie partagée est
 * {@link EditorPopupAnchors#colorPickerMetrics(EditorView)}, le
 * dessin vit dans le painter dédié du pipeline de rendu.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorColorPickerPopup {

    private final EditorView view;
    /** Référence au gestionnaire pour la fermeture croisée des popups. */
    private final EditorPopupManager popups;

    /**
     * Teintes courantes de la palette (2 rangées × 8) — les teintes
     * usuelles du design mobile. Une teinte opaque remplace la TEINTE
     * du code tapé ; l'alpha d'ORIGINE est conservé (le sélecteur
     * n'édite pas l'alpha). Public pour le painter (package render).
     */
    public static final int[] PALETTE = {
            0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7,
            0xFF3F51B5, 0xFF2196F3, 0xFF009688, 0xFF4CAF50,
            0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFF795548,
            0xFF9E9E9E, 0xFF607D8B, 0xFF000000, 0xFFFFFFFF,
    };

    /** Nombre de colonnes de la palette (8 — largeur de carte ≈ 242dp). */
    static final int PALETTE_COLS = 8;

    /** HSV d'édition : [teinte 0..360, saturation 0..1, valeur 0..1]. */
    private final float[] hsv = new float[3];

    EditorColorPickerPopup(EditorView view, EditorPopupManager popups) {
        this.view = view;
        this.popups = popups;
    }

    /**
     * Résolution du tap : ouvre le sélecteur si {@code offset} tombe au
     * cœur d'un code couleur de la ligne (les pastilles inline du
     * renderer rendent la zone visible). Vrai si le sélecteur s'est
     * ouvert (le tap est alors consommé — pas de caret, pas de
     * clavier).
     */
    boolean colorPickerAt(int offset) {
        if (!view.colorPickerEnabled || view.session == null) return false;
        EditorDocument doc = view.session.getDocument();
        if (offset <= 0 || offset > doc.length()) return false;
        int line = doc.lineForOffset(offset);
        int lineStart = doc.lineStart(line);
        int lineEnd = doc.lineEnd(line);
        if (lineEnd - lineStart > 500) return false; // même garde que le rendu des swatches
        CharSequence lineText = doc.charSequence().subSequence(lineStart, lineEnd);
        int col = offset - lineStart;
        ColorPicker.ColorMatch m = ColorPicker.findColorAt(lineText, col);
        if (m == null) return false;
        // Intérieur STRICT (précédent squiggle D1) : les bords ±1
        // caractère restent des placements de caret — l'utilisateur
        // veut se positionner avant/après le code, pas l'éditer.
        if (col <= m.start || col >= m.end) return false;
        view.colorPickerStart = lineStart + m.start;
        view.colorPickerEnd = lineStart + m.end;
        view.colorPickerOriginal = m.color;
        view.colorPickerCurrent = m.color;
        view.colorPickerDigits = m.digits();
        Color.colorToHSV(m.color, hsv);
        view.colorPickerPressed = 0;
        view.colorPickerVisible = true;
        closeOthers();
        view.invalidate();
        return true;
    }

    /** Referme le sélecteur (sans rien appliquer). */
    void dismissColorPicker() {
        view.colorPickerVisible = false;
        view.colorPickerPressed = 0;
        view.colorPickerStart = -1;
        view.colorPickerEnd = -1;
        view.invalidate();
    }

    /**
     * DOWN dans la carte : engage le champ SV ou la bande de teinte
     * sous le doigt (saut immédiat, le drag suivant porte le curseur).
     * Un DOWN ailleurs dans la carte n'engage rien (le geste reste
     * englouti par la carte — parité pill/NavMenu).
     */
    void pressAt(float x, float y) {
        EditorPopupAnchors.ColorPickerMetrics m =
                EditorPopupAnchors.colorPickerMetrics(view);
        if (m == null) return;
        if (m.sv.contains(x, y)) {
            view.colorPickerPressed = 1;
            updateFromSv(m, x, y);
        } else if (m.hue.contains(x, y)) {
            view.colorPickerPressed = 2;
            updateFromHue(m, x, y);
        } else {
            view.colorPickerPressed = 0;
        }
    }

    /** MOVE du drag : mise à jour vivante de la couleur en cours. */
    void dragTo(float x, float y) {
        EditorPopupAnchors.ColorPickerMetrics m =
                EditorPopupAnchors.colorPickerMetrics(view);
        if (m == null) return;
        if (view.colorPickerPressed == 1) {
            updateFromSv(m, x, y);
        } else if (view.colorPickerPressed == 2) {
            updateFromHue(m, x, y);
        }
    }

    /**
     * UP : tap sur une teinte de la palette → application directe ;
     * fin d'un press champ/bande (tap ou drag) → application de la
     * couleur en cours ; tap ailleurs dans la carte → rien.
     */
    void releaseAt(float x, float y) {
        EditorPopupAnchors.ColorPickerMetrics m =
                EditorPopupAnchors.colorPickerMetrics(view);
        int pressed = view.colorPickerPressed;
        view.colorPickerPressed = 0;
        if (m == null) return;
        int cell = paletteCellAt(m, x, y);
        if (cell >= 0) {
            applyColor(PALETTE[cell]);
            return;
        }
        if (pressed == 1 || pressed == 2) {
            applyColor(view.colorPickerCurrent);
            return;
        }
        view.invalidate();
    }

    /**
     * Indice de la teinte de la palette sous (x, y), ou -1 : la
     * géométrie est déterministe (grille de {@link #PALETTE_COLS}
     * colonnes), un tap entre les cellules tombe sur la plus proche.
     */
    private static int paletteCellAt(
            EditorPopupAnchors.ColorPickerMetrics m, float x, float y) {
        if (!m.palette.contains(x, y)) return -1;
        int col = (int) ((x - m.palette.left) / m.cell);
        int row = (int) ((y - m.palette.top) / m.paletteRowH);
        col = Math.max(0, Math.min(m.paletteCols - 1, col));
        row = Math.max(0, Math.min(m.paletteRows - 1, row));
        int idx = row * m.paletteCols + col;
        return idx < PALETTE.length ? idx : -1;
    }

    /** Position du doigt → saturation/valeur (axes du champ). */
    private void updateFromSv(EditorPopupAnchors.ColorPickerMetrics m,
            float x, float y) {
        float s = EditorView.clamp(
                (x - m.sv.left) / Math.max(1f, m.sv.width()), 0f, 1f);
        float v = 1f - EditorView.clamp(
                (y - m.sv.top) / Math.max(1f, m.sv.height()), 0f, 1f);
        hsv[1] = s;
        hsv[2] = v;
        view.colorPickerCurrent = composeCurrent();
        view.invalidate();
    }

    /** Position du doigt → teinte (axe de la bande). */
    private void updateFromHue(EditorPopupAnchors.ColorPickerMetrics m,
            float x, float y) {
        hsv[0] = 360f * EditorView.clamp(
                (x - m.hue.left) / Math.max(1f, m.hue.width()), 0f, 1f);
        view.colorPickerCurrent = composeCurrent();
        view.invalidate();
    }

    /**
     * Compose l'ARGB en cours : RGB depuis l'HSV d'édition, ALPHA
     * d'origine conservé — le sélecteur édite la teinte, pas la
     * transparence.
     */
    private int composeCurrent() {
        int rgb = Color.HSVToColor(hsv) & 0x00FFFFFF;
        int alpha = (view.colorPickerOriginal >>> 24) & 0xFF;
        return (alpha << 24) | rgb;
    }

    /**
     * Applique une couleur au code tapé : conservation du FORMAT
     * d'origine (3/6/8 chiffres — la largeur du code ne change pas,
     * le code environnant ne bouge pas) et de l'alpha d'origine ;
     * mutation par le pipeline unique (annulable d'un undo), puis
     * rafraîchissement complet ({@code onTextChanged} — le contrat des
     * actions d'édition). La carte se referme AVANT l'application :
     * le rafraîchissement déclencherait la fermeture de toute façon,
     * et l'état de plage ne doit pas survivre à la mutation.
     */
    private void applyColor(int argb) {
        if (!view.colorPickerVisible || view.session == null) return;
        int start = view.colorPickerStart;
        int end = view.colorPickerEnd;
        if (start < 0 || end <= start || end > view.session.getDocument().length()) {
            // La plage n'est plus valide (document édité depuis) — on
            // referme sans rien écrire.
            dismissColorPicker();
            return;
        }
        int alpha = (view.colorPickerOriginal >>> 24) & 0xFF;
        int composed = (alpha << 24) | (argb & 0x00FFFFFF);
        String hex = ColorPicker.format(composed, view.colorPickerDigits);
        view.colorPickerVisible = false;
        view.colorPickerPressed = 0;
        view.colorPickerStart = -1;
        view.colorPickerEnd = -1;
        view.session.replaceRangeWithCaret(start, end, hex, start + hex.length());
        view.onTextChanged();
    }

    /** Fermeture croisée des popups exclusifs (la carte est légère). */
    private void closeOthers() {
        if (view.completion.visible) popups.dismissCompletion();
        if (view.quickDocVisible) popups.dismissQuickDoc();
        if (view.signatureHelpVisible) popups.dismissSignatureHelp();
        if (view.codeActionsPopupVisible) popups.dismissCodeActions();
        if (view.navMenuVisible) popups.dismissNavMenu();
        if (view.squigglePopupVisible) popups.dismissSquigglePopup();
        if (view.diagnosticPopupVisible) popups.dismissDiagnosticPopup();
        if (view.diagnosticListSheetLine >= 0) popups.dismissDiagnosticListSheet();
    }
}
