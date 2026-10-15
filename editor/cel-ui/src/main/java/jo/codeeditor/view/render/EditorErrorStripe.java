package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * B11 (P1-1, feuille de route « écarts Android Studio ») — bande
 * d'erreurs du bord droit (« error stripe ») : les diagnostics du
 * fichier entier deviennent visibles d'un coup d'œil, même hors écran,
 * et navigables au tap.
 *
 * <p>Parité Android Studio : la zone scrollbar verticale porte des
 * marqueurs colorés (erreur / avertissement / info) et le rectangle du
 * viewport. Ici :</p>
 * <ul>
 *   <li>marqueurs — un tiret de {@link #STRIPE_WIDTH_DP} dp par ligne
 *       diagnostiquée, posé au ratio {@code docLineToY(line) / contentH}
 *       (géométrie consciente du retour à la ligne ET des plis, en
 *       cohérence avec {@code maxV()}). Sévérité max par ligne (3 erreur
 *       &gt; 2 avertissement &gt; 1 info), couleurs du thème ;</li>
 *   <li>rectangle du viewport — la portion visible du fichier (fill +
 *       contour, même style que la minimap) — la bande sert aussi de
 *       scrollbar visuelle ;</li>
 *   <li>tap sur un marqueur — déplie le pli englobant, CENTRE la ligne
 *       (scrollToLineCentered) et ouvre la fiche (popup de détail, ou
 *       liste groupée quand la ligne porte plusieurs diagnostics) ;</li>
 *   <li>drag sur la bande — défilement ABSOLU du document (même
 *       mécanique que le fast-scroller de gouttière, pouce + bulle via
 *       {@code EditorFastScroller}).</li>
 * </ul>
 *
 * <p>Alimentation : {@code EditorDiagnosticsPusher} pousse le même
 * tableau {@code int[] sévérité par ligne} que la gouttière (les points
 * devant les numéros) — la bande et la gouttière partagent LA source.
 * Le tableau est compacté à la réception (liste sparse ligne/sevérité)
 * pour que le dessin reste O(diagnostics) et non O(lignes du document)
 * sur les gros fichiers.</p>
 *
 * <p>La bande se tait quand un aperçu est ouvert (SPLIT/FULL/feuille) :
 * la moitié ou tout l'écran appartient alors au corps d'aperçu — hit et
 * dessin refusent tous deux.</p>
 *
 * @author jo@Dev
 * @since 3.65.0
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorErrorStripe {

    /** Largeur DESSINÉE de la bande (dp). */
    static final float STRIPE_WIDTH_DP = 4f;
    /** Largeur de la zone TACTILE autour du bord droit (dp) — plus
     *  généreuse que la bande dessinée : un marqueur de 4 dp serait
     *  intouchable au doigt. */
    static final float STRIPE_TOUCH_DP = 10f;
    /** Hauteur d'un marqueur (dp). */
    static final float MARKER_HEIGHT_DP = 3f;
    /** Rayon de capture vertical autour d'un marqueur pour le tap (dp). */
    static final float MARKER_SLOP_DP = 8f;

    private final EditorView view;

    /** Bande activée (défaut : vrai — parité AS out of the box). */
    private boolean enabled = true;

    // ── Données compactées (setDiagnostics) ─────────────────────
    // Paires (ligne, sévérité) des SEULES lignes diagnostiquées — le
    // tableau complet par ligne de la gouttière resterait O(N) à
    // parcourir par frame sur un fichier de 50 000 lignes.
    private int[] markerLines = new int[0];
    private int[] markerSevs = new int[0];

    // ── Paints hoistés (zéro allocation par frame) ──────────────
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public EditorErrorStripe(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════
    // État
    // ════════════════════════════════════════════════════════════

    /** Active/désactive la bande (le setter public vit dans EditorView). */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Reçoit le tableau sévérité-par-ligne (même source que la gouttière)
     * et le compacte en paires sparse. {@code null} ou vide efface.
     */
    public void setDiagnostics(int[] severityPerLine) {
        if (severityPerLine == null || severityPerLine.length == 0) {
            markerLines = new int[0];
            markerSevs = new int[0];
            return;
        }
        int n = 0;
        for (int sev : severityPerLine) {
            if (sev > 0) n++;
        }
        int[] lines = new int[n];
        int[] sevs = new int[n];
        int k = 0;
        for (int line = 0; line < severityPerLine.length; line++) {
            if (severityPerLine[line] > 0) {
                lines[k] = line;
                sevs[k] = severityPerLine[line];
                k++;
            }
        }
        markerLines = lines;
        markerSevs = sevs;
    }

    /** Nombre de marqueurs portés ( exposé pour les tests et l'hôte). */
    public int markerCount() {
        return markerLines.length;
    }

    // ════════════════════════════════════════════════════════════
    // Hit-test (déterministe — même géométrie que le dessin)
    // ════════════════════════════════════════════════════════════

    /**
     * Vrai si le point tombe dans la zone tactile de la bande : bord
     * droit ({@link #STRIPE_TOUCH_DP} dp), bande activée, session vivante,
     * AUCUN aperçu ouvert (la zone appartient sinon au corps d'aperçu).
     */
    public boolean hit(float x, float y) {
        return enabled
                && view.session != null
                && view.previewMode == EditorView.PreviewMode.NONE
                && x >= view.getWidth() - STRIPE_TOUCH_DP * density();
    }

    /**
     * Ligne du marqueur le plus proche du Y écran donné (dans le rayon
     * {@link #MARKER_SLOP_DP} dp), ou -1. Le Y est converti par la MÊME
     * formule que le dessin (docLineToY / contentH).
     */
    public int markerLineAt(float y) {
        if (markerLines.length == 0 || view.session == null) return -1;
        float viewH = view.getHeight();
        float contentH = view.maxV() + viewH;
        if (contentH <= 0f) return -1;
        float slop = MARKER_SLOP_DP * density();
        float markerH = MARKER_HEIGHT_DP * density();
        int best = -1;
        float bestDist = Float.MAX_VALUE;
        for (int i = 0; i < markerLines.length; i++) {
            float my = markerYFor(markerLines[i], contentH, viewH, markerH);
            float dist = Math.abs(y - my);
            if (dist < bestDist && dist <= slop) {
                bestDist = dist;
                best = markerLines[i];
            }
        }
        return best;
    }

    // ════════════════════════════════════════════════════════════
    // Dessin
    // ════════════════════════════════════════════════════════════

    /**
     * Dessine la bande : marqueurs de diagnostics + rectangle du
     * viewport. Rien si désactivée, sans session, sans hauteur, ou quand
     * un aperçu est ouvert.
     */
    public void draw(Canvas canvas) {
        if (!enabled || view.session == null) return;
        if (view.previewMode != EditorView.PreviewMode.NONE) return;
        float viewH = view.getHeight();
        if (viewH <= 0f) return;

        float d = density();
        float stripeW = STRIPE_WIDTH_DP * d;
        float left = view.getWidth() - stripeW;
        float maxV = view.maxV();
        float contentH = maxV + viewH;

        // Rectangle du viewport (façon scrollbar) — seulement si le
        // contenu dépasse l'écran : à hauteur égale, la bande ne porte
        // que les marqueurs.
        if (maxV > 0f) {
            float rectH = Math.min(viewH, viewH * viewH / contentH);
            float rectTop = (viewH - rectH) * (view.scroll.vOffset / maxV);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(EditorView.applyAlphaToColor(view.theme.selection, 0.22f));
            canvas.drawRect(left, rectTop, view.getWidth(), rectTop + rectH, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(EditorView.applyAlphaToColor(view.theme.selection, 0.55f));
            canvas.drawRect(left, rectTop, view.getWidth(), rectTop + rectH, paint);
            // Discipline du paint partagé : aucun état ne fuit vers la
            // frame suivante (le même paint sert aux marqueurs en FILL).
            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(0f);
        }

        // Marqueurs de diagnostics (ordre des lignes = ordre de dessin ;
        // un marqueur plus bas peut recouvrir un voisin — la sévérité max
        // par ligne est déjà garantie par la source).
        if (markerLines.length == 0) return;
        float markerH = MARKER_HEIGHT_DP * d;
        for (int i = 0; i < markerLines.length; i++) {
            float y = markerYFor(markerLines[i], contentH, viewH, markerH);
            if (y < -markerH || y > viewH) continue;
            paint.setColor(colorFor(markerSevs[i]));
            canvas.drawRect(left, y, view.getWidth(), y + markerH, paint);
        }
    }

    // ════════════════════════════════════════════════════════════
    // Géométrie partagée dessin/hit-test
    // ════════════════════════════════════════════════════════════

    /**
     * Y écran (HAUT du marqueur) de la ligne donnée : ratio de la
     * position de la ligne dans le contenu, borné au viewport. MÊME
     * formule au dessin et au hit-test — rect déterministe façon B9c.
     */
    private float markerYFor(int line, float contentH, float viewH, float markerH) {
        float y = view.docLineToY(line) / contentH * viewH;
        return Math.max(0f, Math.min(viewH - markerH, y - markerH * 0.5f));
    }

    /** Couleur de marqueur pour une sévérité (3/2/1 → error/warning/info). */
    private int colorFor(int severity) {
        if (severity >= 3) return view.theme.error;
        if (severity == 2) return view.theme.warning;
        return view.theme.info;
    }

    private float density() {
        return view.getResources().getDisplayMetrics().density;
    }
}
