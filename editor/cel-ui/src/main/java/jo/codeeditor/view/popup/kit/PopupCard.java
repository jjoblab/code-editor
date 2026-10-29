package jo.codeeditor.view.popup.kit;

import jo.codeeditor.view.EditorView;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

/**
 * Rendu partagé d'une carte de popup : surface verre + bordure
 * hairline, filets de séparation et bandes teintées. Unifie les ~8
 * copies du motif « fond glass + bordure » dispersées dans les
 * painters, aux rayons et couleurs disparates.
 *
 * <p>La carte emprunte les paints partagés de la vue
 * ({@code bgPaint}, {@code caretPaint}) avec la discipline du dépôt :
 * style et épaisseur du paint de bordure sont SAUVÉS puis RESTAURÉS —
 * aucune couche suivante ne subit d'état résiduel. Le rayon est passé
 * en px (les jetons dp de {@link PopupTokens} sont multipliés par la
 * densité par l'appelant) : la carte ne connaît ni densité ni thème,
 * elle peint.</p>
 */
public final class PopupCard {

    /** Kinds de section reconnus par {@link #drawBand}. */
    public static final int BAND_PLAIN = 0;
    /** Bande teintée douce (signature, meta). */
    public static final int BAND_TINTED = 1;

    private final EditorView view;

    // Cache du Typeface gras (P-allocations) : Typeface.create(base,
    // BOLD) était appelé à chaque segment et chaque frame — le couple
    // (base, gras) est mémoïsé, invalidé si la police de base change.
    private android.graphics.Typeface grasCache;
    private android.graphics.Typeface grasBase;

    public PopupCard(EditorView view) {
        this.view = view;
    }

    /**
     * Le Typeface gras de la police courante de l'éditeur — mémoïsé
     * (jamais recréé par segment ni par frame) ; invalidé quand la
     * police de base change.
     */
    public android.graphics.Typeface grasMémoïsé() {
        android.graphics.Typeface base = view.metrics.getTypeface();
        if (grasCache == null || grasBase != base) {
            grasBase = base;
            grasCache = android.graphics.Typeface.create(
                    base, android.graphics.Typeface.BOLD);
        }
        return grasCache;
    }

    /**
     * Fond verre + bordure hairline d'une carte arrondie. Reproduit
     * EXACTEMENT le motif historique (fond {@code glassBg}, bordure
     * 1 px {@code glassBorder}) — migration sans changement visuel.
     *
     * @param canvas le canvas de la frame
     * @param rect le rectangle de la carte (px écran)
     * @param radiusPx le rayon des coins (px)
     */
    public void drawSurface(Canvas canvas, RectF rect, float radiusPx) {
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radiusPx, radiusPx, view.bgPaint);
        Paint.Style styleSauvé = view.caretPaint.getStyle();
        float strokeSauvé = view.caretPaint.getStrokeWidth();
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radiusPx, radiusPx, view.caretPaint);
        view.caretPaint.setStrokeWidth(strokeSauvé);
        view.caretPaint.setStyle(styleSauvé);
    }

    /**
     * Filet horizontal de séparation entre deux sections (couleur de
     * bordure, 1 px).
     */
    public void drawDivider(Canvas canvas, float x1, float x2, float y) {
        Paint.Style styleSauvé = view.caretPaint.getStyle();
        float strokeSauvé = view.caretPaint.getStrokeWidth();
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawLine(x1, y, x2, y, view.caretPaint);
        view.caretPaint.setStrokeWidth(strokeSauvé);
        view.caretPaint.setStyle(styleSauvé);
    }

    /**
     * Bande de section : rectangle plein basique
     * ({@link #BAND_PLAIN}) ou teinté doux
     * ({@link #BAND_TINTED} — couleur de sélection du thème, motif
     * historique des bandes de documentation).
     */
    public void drawBand(Canvas canvas, RectF rect, int kind) {
        view.selPaint.setColor(kind == BAND_TINTED
                ? view.theme.selection : view.theme.glassBg);
        canvas.drawRect(rect, view.selPaint);
    }
}
