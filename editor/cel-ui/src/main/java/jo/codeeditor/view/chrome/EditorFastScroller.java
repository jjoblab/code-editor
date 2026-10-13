package jo.codeeditor.view.chrome;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;

/**
 * B11 (P0-3, feuille de route « écarts Android Studio ») — fast-scroller
 * de gouttière : le retour visuel du pouce pendant qu'un drag dans la
 * gouttière (ou sur la bande d'erreurs) fait défiler le document en
 * position ABSOLUE.
 *
 * <p>Comportement (parité IntelliJ/AS « drag de la gouttière ») :</p>
 * <ul>
 *   <li>{@code arm()} au DOWN dans la zone des numéros (ou la bande) —
 *       rien ne s'affiche encore : un tap doit rester un tap (point de
 *       diagnostic, icône d'aperçu…), le pouce n'apparaît qu'au-delà du
 *       touch-slop ;</li>
 *   <li>{@code dragTo(y)} — le pouce suit le doigt en HAUTEUR (le centre
 *       du pouce s'aligne sur le Y tactile) et la position du document
 *       suit en proportion : {@code vOffset = top / (viewH − thumbH) ×
 *       maxV}. Sur un fichier long, tout le document devient atteignable
 *       d'un seul geste — l'ancien scroll pixel-relatif n'offrait qu'un
 *       écran par geste ;</li>
 *   <li>{@code end()} au relâchement — le pouce et la bulle disparaissent
 *       en fondu (250 ms) ; pas de fling : le positionnement est absolu ;</li>
 *   <li>{@code cancel()} à l'annulation (pincement qui prend la main,
 *       CANCEL système, changement de session) — disparition immédiate,
 *       sans fondu.</li>
 * </ul>
 *
 * <p>Rendu : une pill verticale (4 dp) au bord DROIT — elle recouvre la
 * bande d'erreurs pendant le geste (le marqueur agrandi, façon scrollbar
 * Android Studio) — plus une bulle « numéro de ligne » à sa gauche qui
 * donne la ligne du MILIEU du viewport (retour visuel du fast-scroller
 * des listes Android). Dessiné en DERNIER par le renderer, au-dessus de
 * tout (sticky lines comprises) : c'est le retour d'un geste en cours.</p>
 *
 * <p>Thread-safety : état tactile mué uniquement depuis le thread UI
 * (EditorInputHandler) ; {@link #draw(Canvas)} lu sur le même thread.</p>
 *
 * @author jo@Dev
 * @since 3.65.0
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorFastScroller {

    /** Largeur de la pill du pouce (dp). */
    static final float THUMB_WIDTH_DP = 4f;
    /** Hauteur MINIMALE du pouce (dp) — saisissable sur les documents longs. */
    static final float THUMB_MIN_HEIGHT_DP = 32f;
    /** Hauteur de la bulle de numéro de ligne (dp). */
    static final float BUBBLE_HEIGHT_DP = 34f;
    /** Marge horizontale entre la pill et la bulle (dp). */
    static final float BUBBLE_GAP_DP = 6f;
    /** Marge de calage vertical de la bulle (dp). */
    static final float BUBBLE_MARGIN_DP = 4f;
    /** Durée du fondu de disparition après {@link #end()} (ms). */
    static final long FADE_MS = 250L;

    private final EditorView view;

    // ── État du geste ───────────────────────────────────────────
    // armed : un DOWN dans la gouttière/la bande a été vu — le fast-scroll
    // est CANDIDAT mais invisible tant que le doigt n'a pas dépassé le
    // slop (un tap ne doit JAMAIS flasher le pouce).
    // active : le pouce est montré plein (drag en cours).
    // endAtMs : uptime du relâchement — pilote le fondu (0 = rien à fondre).
    private boolean armed = false;
    private boolean active = false;
    private long endAtMs = 0L;

    // ── Paints hoistés (discipline B21d : zéro allocation par frame) ──
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubbleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Rects réutilisés (pas d'allocation par frame). */
    private final RectF thumbRect = new RectF();
    private final RectF bubbleRect = new RectF();

    public EditorFastScroller(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════
    // Entrées du gestionnaire d'entrée
    // ════════════════════════════════════════════════════════════

    /** Arme le fast-scroll (DOWN vu dans la gouttière ou la bande). */
    public void arm() {
        armed = true;
    }

    /** Vrai si un DOWN de gouttière/bande a armé ce geste. */
    public boolean isArmed() {
        return armed;
    }

    /** Vrai pendant le drag (pouce + bulle visibles, plein alpha). */
    public boolean isActive() {
        return active;
    }

    /**
     * Positionne le viewport depuis la hauteur du doigt : le CENTRE du
     * pouce s'aligne sur {@code y} et le document suit en proportion.
     * Active le pouce (premier appel au-delà du slop). No-op sur un
     * document plus court que le viewport.
     */
    public void dragTo(float y) {
        float viewH = view.getHeight();
        float maxV = view.maxV();
        if (maxV <= 0f || viewH <= 0f) {
            return;
        }
        active = true;
        float thumbH = thumbHeight();
        float span = viewH - thumbH;
        if (span <= 0f) {
            view.vOffset = 0f;
        } else {
            float top = EditorView.clamp(y - thumbH * 0.5f, 0f, span);
            view.vOffset = EditorView.clamp(top / span * maxV, 0f, maxV);
        }
        view.notifyScrollPositionChanged();
        view.invalidate();
    }

    /**
     * Relâchement : désarme et, si le pouce était actif, amorce le fondu
     * de disparition. Un simple tap (jamais actif) ne laisse AUCUNE trace.
     */
    public void end() {
        if (active) {
            endAtMs = SystemClock.uptimeMillis();
            view.postInvalidateOnAnimation();
        }
        active = false;
        armed = false;
    }

    /** Annulation : disparition immédiate, sans fondu. */
    public void cancel() {
        armed = false;
        active = false;
        endAtMs = 0L;
    }

    // ════════════════════════════════════════════════════════════
    // Géométrie (pure — testable)
    // ════════════════════════════════════════════════════════════

    /** Hauteur totale du contenu défilable (viewport inclus). */
    float contentHeight() {
        return view.maxV() + view.getHeight();
    }

    /** Hauteur du pouce courant (plancher 32 dp). */
    float thumbHeight() {
        return thumbHeightFor(view.getHeight(), contentHeight(), density());
    }

    /**
     * Hauteur du pouce pour un viewport/contentH donnés (formule pure) :
     * proportion du viewport dans le contenu, plancher {@link
     * #THUMB_MIN_HEIGHT_DP} pour rester saisissable sur les longs
     * documents.
     */
    public static float thumbHeightFor(float viewH, float contentH, float density) {
        if (contentH <= 0f || viewH <= 0f) return viewH;
        return Math.max(THUMB_MIN_HEIGHT_DP * density, viewH * viewH / contentH);
    }

    /** Remplit {@code out} avec le rect écran du pouce courant. */
    void thumbRect(RectF out) {
        float viewH = view.getHeight();
        float maxV = view.maxV();
        float thumbH = thumbHeight();
        float top = maxV > 0f && viewH > thumbH
                ? (viewH - thumbH) * (view.vOffset / maxV) : 0f;
        float w = THUMB_WIDTH_DP * density();
        out.set(view.getWidth() - w, top, view.getWidth(), top + thumbH);
    }

    private float density() {
        return view.getResources().getDisplayMetrics().density;
    }

    // ════════════════════════════════════════════════════════════
    // Dessin
    // ════════════════════════════════════════════════════════════

    /**
     * Dessine le pouce + la bulle de ligne. Rien au repos ; plein pendant
     * le drag ; fondu linéaire de 250 ms après le relâchement (la frame
     * suivante est redemandée via {@code postInvalidateOnAnimation} tant
     * que le fondu n'est pas terminé).
     */
    public void draw(Canvas canvas) {
        if (view.session == null) return;
        float alpha;
        if (active) {
            alpha = 1f;
        } else if (endAtMs > 0L) {
            float t = (SystemClock.uptimeMillis() - endAtMs) / (float) FADE_MS;
            if (t >= 1f) {
                endAtMs = 0L;
                return;
            }
            alpha = 1f - t;
            // Continue le fondu jusqu'à épuisement.
            view.postInvalidateOnAnimation();
        } else {
            return;
        }
        float maxV = view.maxV();
        if (maxV <= 0f) return;

        float d = density();
        thumbRect(thumbRect);

        // Pill du pouce — couleur du caret (accent), alpha du geste.
        thumbPaint.setColor(applyAlpha(view.theme.caret, 0.92f * alpha));
        thumbPaint.setStyle(Paint.Style.FILL);
        float r = thumbRect.width() * 0.5f;
        canvas.drawRoundRect(thumbRect, r, r, thumbPaint);

        // Bulle « ligne du milieu du viewport » à gauche de la pill.
        int midLine = view.docLineForScreenY(thumbRect.centerY());
        String label = String.valueOf(midLine + 1);
        bubbleTextPaint.set(view.metrics.getGutterPaint());
        bubbleTextPaint.setColor(applyAlpha(view.theme.textColor, alpha));
        float textW = bubbleTextPaint.measureText(label);
        float bubbleH = BUBBLE_HEIGHT_DP * d;
        float bubbleW = Math.max(textW + 12f * d, bubbleH);
        float bubbleRight = thumbRect.left - BUBBLE_GAP_DP * d;
        float bubbleLeft = Math.max(BUBBLE_MARGIN_DP * d, bubbleRight - bubbleW);
        float cy = thumbRect.centerY();
        float top = EditorView.clamp(cy - bubbleH * 0.5f,
                BUBBLE_MARGIN_DP * d, view.getHeight() - bubbleH - BUBBLE_MARGIN_DP * d);
        bubbleRect.set(bubbleLeft, top, bubbleLeft + bubbleW, top + bubbleH);

        bubbleBgPaint.setColor(applyAlpha(view.theme.gutterBg, 0.96f * alpha));
        bubbleBorderPaint.setColor(applyAlpha(view.theme.gutterBorder, alpha));
        bubbleBorderPaint.setStyle(Paint.Style.STROKE);
        bubbleBorderPaint.setStrokeWidth(1f);
        float br = bubbleH * 0.5f;
        canvas.drawRoundRect(bubbleRect, br, br, bubbleBgPaint);
        canvas.drawRoundRect(bubbleRect, br, br, bubbleBorderPaint);
        // Restaure le style du paint prêté (discipline minimap : aucun
        // état ne doit fuir vers la frame suivante).
        bubbleBorderPaint.setStyle(Paint.Style.FILL);
        bubbleBorderPaint.setStrokeWidth(0f);

        // Texte centré : baseline calée par la métrique du paint gouttière.
        float baseline = top + bubbleH * 0.5f
                - (bubbleTextPaint.ascent() + bubbleTextPaint.descent()) * 0.5f;
        canvas.drawText(label, bubbleRect.centerX() - textW * 0.5f, baseline,
                bubbleTextPaint);
    }

    private static int applyAlpha(int color, float alpha) {
        int a = (int) (((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, alpha)));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
