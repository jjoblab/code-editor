package jo.codeeditor.view.preview;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.EditorMetrics;

import androidx.annotation.RestrictTo;

import android.animation.ValueAnimator;
import android.graphics.RectF;
import android.view.animation.DecelerateInterpolator;

import java.util.Arrays;
import java.util.List;

/**
 * Contrôleur de la carte d'aperçu « goutte d'eau » (B27) — l'état vivant
 * du popup ancré à l'œil de gouttière : ouverture/fermeture, ligne
 * d'ancre GELÉE (l'œil ne suit plus le caret une fois la carte ouverte),
 * onglet sélectionné (fourni par le hôte), hit-tests des zones tactiles
 * (onglets, fermeture, corps) et calcul du placement frame par frame.
 *
 * <p>Le dessin vit dans le painter dédié du pipeline de rendu
 * ({@code EditorPreviewDropPainter}) ; la géométrie pure est
 * {@link EditorPreviewDropGeometry} — le contrôleur et le peintre
 * partagent la même formule, aucun état mutable entre les deux. L'état
 * d'ouverture est porté par {@link EditorView.PreviewMode#DROP} (la vue
 * le miroir) ; ce contrôleur est la source de vérité de l'ancre et des
 * onglets.</p>
 *
 * <h3>Contrat des gestes</h3>
 * <ul>
 *   <li>tap sur l'<b>œil</b> de gouttière → ouverture (ancre = rangée
 *       du caret) / re-tap → fermeture ;</li>
 *   <li>tap <b>dans la carte</b> : onglet → bascule (le hôte est
 *       notifié, il re-rend) ; ✕ → fermeture ; corps → englouti (le
 *       caret ne bouge pas) ;</li>
 *   <li>tap <b>ailleurs dans l'éditeur</b> → cascade normale (le caret
 *       se pose) — la carte reste ouverte et ESQUIVE la nouvelle zone
 *       de frappe (non modale, miroir de la maquette) ;</li>
 *   <li>fermetures de sûreté : changement de session, fichier non
 *       prévisualisable, détachement de la fenêtre.</li>
 * </ul>
 *
 * <p>La carte vit en coordonnées écran (viewport) : l'ancre et le caret
 * sont re-résolus à CHAQUE frame — la carte suit l'œil au scroll et
 * esquive le caret à la frappe, sans animation dédiée (seul le fondu
 * d'entrée/sortie du pipeline anime la carte).</p>
 *
 * <h3>Retour terrain B27 : cohabitation avec les popups de frappe</h3>
 * <p>La carte esquive AUSSI les popups vivants (complétion, doc rapide) :
 * la géométrie reçoit leurs rectangles comme obstacles (creux libre,
 * placement latéral à droite sur grand écran, compactage). Quand rien
 * ne tient (petit écran), la carte ne CACHE plus : elle se <b>voile
 * d'eau</b> — transparence fluide animée (hystérésis à deux plateaux,
 * jamais de vacillement pendant que la complétion filtre à chaque
 * frappe), pilotée par le ratio de recouvrement du placement.</p>
 *
 * @author jjoblab
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class EditorPreviewDrop {

    // ── Dimensions de la carte (dp — miroir de la maquette validée) ──

    /** Largeur de la carte (dp). */
    public static final float CARD_W_DP = 232f;
    /** En-tête : titre + onglets + fermeture (dp). */
    public static final float HEADER_H_DP = 40f;
    /** Marge autour du mini-téléphone (dp). */
    public static final float PHONE_MARGIN_DP = 10f;
    /** Pied : chips « rendu vivant » + échelle (dp). */
    public static final float FOOTER_H_DP = 32f;
    /** Hauteur du mini-téléphone (dp). */
    public static final float PHONE_H_DP = 250f;
    /** Hauteur totale de la carte (dp). */
    public static final float CARD_H_DP = HEADER_H_DP + PHONE_MARGIN_DP
            + PHONE_H_DP + PHONE_MARGIN_DP + FOOTER_H_DP;
    /** Plancher d'échelle du compactage (maquette : 0,58). */
    public static final float MIN_SCALE = 0.58f;
    /** Marge aux bords de l'éditeur (dp). */
    public static final float EDGE_PAD_DP = 6f;
    /** Décalage haut-de-carte → goutte en position ancrée (dp). */
    public static final float ANCHOR_DROP_OFFSET_DP = 22f;
    /** Écart œil↔goutte au-delà duquel la tige se dessine (dp). */
    public static final float STEM_THRESHOLD_DP = 8f;
    /** Demi-taille du losange-goutte (dp). */
    public static final float DROP_HALF_DP = 11f;
    /** Cible tactile de la fermeture ✕ (dp, carré). */
    public static final float CLOSE_TARGET_DP = 32f;
    /** Rayon de la pill œil dans la gouttière (dp — parité B9c : 13). */
    public static final float EYE_RADIUS_DP = 13f;

    private final EditorView view;
    private final EditorPreviewController preview;

    /** Vrai quand la carte est ouverte (miroir : previewMode == DROP). */
    boolean open;

    /**
     * Ligne d'ancre — la rangée de l'œil GELÉE à l'ouverture. Tant que
     * la carte vit, l'œil de gouttière ne suit plus le caret : il reste
     * posé sur cette ligne (la carte pointe vers elle, la tige la
     * relie), miroir de la maquette.
     */
    int anchorLine = -1;

    /** Onglet sélectionné (index dans les onglets du HÔTE). */
    int selectedTab = 0;

    /** Libellés des onglets (cache du dernier calcul — posés à l'ouverture). */
    private List<String> tabLabels = List.of();
    /** Rectangles LOCAUX des onglets (partagés dessin/hit-test, dp→px à l'ouverture). */
    private RectF[] tabRects = new RectF[0];
    /** Rectangle LOCAL de la fermeture ✕ (dp→px). */
    private final RectF closeRect = new RectF();

    /** Rects de hit réutilisés (aucune allocation par tap). */
    private final RectF hitCard = new RectF();

    // ── Voile d'eau (retour terrain B27) ───────────────────────────

    /** Alpha de la carte voilée — l'eau devient translucide. */
    static final float VEILED_ALPHA = 0.25f;
    /** Durée du voilage (ms). */
    private static final long VEIL_IN_MS = 140L;
    /** Durée du dévoilage (ms) — plus lente, plus calme. */
    private static final long VEIL_OUT_MS = 180L;
    /**
     * Recouvrement d'un popup au-delà duquel la carte se voile
     * (fraction de l'AIRE du popup couverte — au-delà de 10 %, cacher
     * des rangées de complétion n'est plus acceptable).
     */
    static final float VEIL_ON_RATIO = 0.10f;
    /**
     * Recouvrement en dessous duquel elle se dévoile — la bande
     * [OFF, ON] est la marge d'hystérésis : la complétion change de
     * hauteur à chaque frappe, l'état ne doit PAS vaciller dedans.
     */
    static final float VEIL_OFF_RATIO = 0.04f;

    /** Vrai quand la carte est voilée (cible d'alpha = voilé). */
    private boolean veiled;
    /** Alpha courant de la carte (1 opaque — animé vers/depuis {@link #VEILED_ALPHA}). */
    private float veilAlpha = 1f;
    /** Animateur du voile (null au repos). */
    private ValueAnimator veilAnimator;

    EditorPreviewDrop(EditorView view, EditorPreviewController preview) {
        this.view = view;
        this.preview = preview;
    }

    /** Vrai quand la carte est ouverte. */
    public boolean isOpen() { return open; }

    /** Ligne d'ancre gelée (−1 si fermée). */
    public int getAnchorLine() { return anchorLine; }

    /** Onglet sélectionné courant. */
    public int getSelectedTab() { return selectedTab; }

    /**
     * Ouvre la carte ancrée à la ligne donnée (typiquement la rangée du
     * caret au moment du tap sur l'œil). Pose les onglets du hôte
     * (mesure des libellés), réinitialise la sélection d'onglet, pousse
     * le contenu initial. Idempotent : une carte déjà ouverte change
     * d'ancre (re-tap sur un autre œil impossible — l'œil est gelé —
     * mais l'API publique reste honnête).
     */
    void openAt(int line) {
        if (view.session == null || view.session.getDocument() == null) return;
        int lineCount = view.session.getDocument().lineCount();
        this.anchorLine = Math.max(0, Math.min(line, lineCount - 1));
        this.selectedTab = 0;
        this.open = true;
        measureTabs();
        view.invalidate();
    }

    /** Referme la carte (l'état logique meurt — le fondu de sortie
     *  photographie le reste). Le voile se réinitialise : une
     *  ré-ouverture repart opaque. */
    void close() {
        if (!open) return;
        this.open = false;
        this.anchorLine = -1;
        this.tabLabels = List.of();
        this.tabRects = new RectF[0];
        resetVeil();
        view.invalidate();
    }

    /**
     * Changement de session : l'ancre décrit l'ancien document — la
     * carte se referme (miroir des purges de setSession : loupe, drag,
     * wrap, recherches).
     */
    void onSessionChanged() {
        close();
    }

    /**
     * Le fichier courant n'est plus prévisualisable (setFileName) — la
     * carte n'a plus de sujet.
     */
    void onPreviewableChanged(boolean previewable) {
        if (!previewable) close();
    }

    /**
     * Recalcule les rectangles d'onglets depuis les libellés du hôte
     * (mesure Paint, droite→gauche dans l'en-tête comme la maquette).
     * Appelé à l'ouverture — les libellés du hôte sont stables pendant
     * qu'une carte vit (le hôte qui les change referme/ré-ouvre).
     */
    private void measureTabs() {
        EditorPreviewHost host = preview.getHost();
        tabLabels = host != null ? host.previewDropTabs() : List.of();
        if (tabLabels == null) tabLabels = List.of();
        float density = view.getResources().getDisplayMetrics().density;
        android.graphics.Paint p = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(10.5f * density);
        int n = tabLabels.size();
        tabRects = new RectF[n];
        // Onglets posés depuis la droite : [✕][tab n]…[tab 1][titre ←]
        float right = CARD_W_DP * density - CLOSE_TARGET_DP * density - 2f * density;
        for (int i = n - 1; i >= 0; i--) {
            String label = tabLabels.get(i);
            float w = p.measureText(label) + 18f * density;
            float left = right - w;
            tabRects[i] = new RectF(left, 6f * density, right, 28f * density);
            right = left - 4f * density;
        }
        float cardW = CARD_W_DP * density;
        closeRect.set(cardW - CLOSE_TARGET_DP * density, 2f * density,
                cardW - 2f * density, 2f * density + CLOSE_TARGET_DP * density);
    }

    /** Libellés d'onglets courants (cache d'ouverture — lecture painter). */
    public List<String> tabLabels() { return tabLabels; }

    /**
     * Rectangles LOCAUX d'onglets (px) — partagés par le dessin du
     * painter et le hit-test : la même mesure, zéro divergence.
     */
    public RectF[] tabRects() { return tabRects; }

    /** Rectangle LOCAL de la fermeture ✕ (px). */
    public RectF closeRect() { return closeRect; }

    /**
     * Placement courant de la carte — recalculé à chaque appel depuis
     * l'état vivant de la vue (ancre, caret, viewport, métriques) et
     * des obstacles vivants (popups de frappe). Retourne null si
     * fermée ou sans session.
     */
    public EditorPreviewDropGeometry.Placement placement() {
        if (!open || view.session == null || view.session.getDocument() == null) {
            return null;
        }
        float density = view.getResources().getDisplayMetrics().density;
        EditorMetrics m = view.metrics;
        float cardW = CARD_W_DP * density;
        float cardH = CARD_H_DP * density;
        float edgePad = EDGE_PAD_DP * density;
        float lineHeight = m.getLineHeight();
        // Œil : pill B9c centrée dans la zone des numéros de la rangée
        // d'ancre (écran : docLineToY inclut padTop, on retire vOffset).
        float numberArea = m.getGutterWidth() - m.getFoldStripWidth();
        float eyeX = numberArea * 0.5f;
        float rowTop = view.docLineToY(anchorLine) - view.scroll.vOffset;
        float eyeY = rowTop + lineHeight * 0.5f;
        // Zone de frappe : caret ± 1 ligne (le bloc que l'utilisateur
        // regarde en tapant).
        int caretOffset = view.session.getSelection().start;
        int caretLine = view.session.getDocument().lineForOffset(caretOffset);
        float caretY = view.docLineToY(caretLine) - view.scroll.vOffset;
        float guardTop = caretY - lineHeight;
        float guardBot = caretY + 2f * lineHeight;
        return EditorPreviewDropGeometry.place(
                view.getWidth(), view.getHeight(),
                cardW, cardH,
                eyeX, eyeY, EYE_RADIUS_DP * density,
                guardTop, guardBot,
                collectObstacles(),
                edgePad, ANCHOR_DROP_OFFSET_DP * density,
                MIN_SCALE, STEM_THRESHOLD_DP * density);
    }

    /**
     * Rectangles écran des popups vivants que la carte doit esquiver
     * (retour terrain B27 : la complétion sous la carte). Complétion +
     * doc rapide — les seuls popups à bornes mesurables publiques ;
     * l'aide de signature est une bannière passive sans métriques
     * exposées (liste extensible, documentée dans USAGE). Tassé
     * {@code [l, t, r, b]} × N, null si aucun.
     */
    private float[] collectObstacles() {
        float[] out = new float[8];
        int n = 0;
        if (view.completion.visible && !view.completion.items.isEmpty()) {
            float[] a = view.completionPopupAnchor();
            if (a != null && a.length >= 4 && a[2] > 1f && a[3] > 1f) {
                out[n++] = a[0];
                out[n++] = a[1];
                out[n++] = a[0] + a[2];
                out[n++] = a[1] + a[3];
            }
        }
        if (view.quickDocVisible) {
            float[] mq = view.quickDocMetrics();
            if (mq != null && mq.length >= 4 && mq[2] > 1f && mq[3] > 1f) {
                out[n++] = mq[0];
                out[n++] = mq[1];
                out[n++] = mq[0] + mq[2];
                out[n++] = mq[1] + mq[3];
            }
        }
        return n == 0 ? null : Arrays.copyOf(out, n);
    }

    // ── Voile d'eau (retour terrain B27) ───────────────────────────

    /** Vrai quand la carte est voilée (alpha cible bas). */
    public boolean isVeiled() { return veiled; }

    /** Alpha courant de la carte (1 opaque — le painter l'applique). */
    public float veilAlpha() { return veilAlpha; }

    /**
     * Met à jour la cible du voile depuis le recouvrement restant du
     * placement courant — appelé par le painter à chaque frame (le
     * seul passage garanti quand la carte est dessinée). Hystérésis à
     * deux plateaux : voile au-delà de {@link #VEIL_ON_RATIO}, dévoile
     * en dessous de {@link #VEIL_OFF_RATIO}, STABLE entre les deux (la
     * complétion filtre à chaque frappe, l'état ne doit pas trembler).
     * Idempotent par frame : seul un CHANGEMENT de cible anime.
     */
    public void updateVeil(EditorPreviewDropGeometry.Placement p) {
        if (p == null) return;
        float ratio = p.overlapRatio;
        if (!veiled && ratio >= VEIL_ON_RATIO) {
            veiled = true;
            animateVeil(VEILED_ALPHA, VEIL_IN_MS);
        } else if (veiled && ratio <= VEIL_OFF_RATIO) {
            veiled = false;
            animateVeil(1f, VEIL_OUT_MS);
        }
    }

    /** Anime l'alpha du voile vers la cible (ValueAnimator + invalidation
     *  par frame — même patron que les fondus de popups B7). */
    private void animateVeil(float target, long durationMs) {
        if (veilAnimator != null) {
            veilAnimator.cancel();
            veilAnimator = null;
        }
        ValueAnimator a = ValueAnimator.ofFloat(veilAlpha, target);
        a.setDuration(durationMs);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(anim -> {
            veilAlpha = (Float) anim.getAnimatedValue();
            view.postInvalidateOnAnimation();
        });
        veilAnimator = a;
        a.start();
        view.postInvalidateOnAnimation();
    }

    /** Réinitialise le voile (fermeture/ré-ouverture : opaque, aucun animateur). */
    private void resetVeil() {
        if (veilAnimator != null) {
            veilAnimator.cancel();
            veilAnimator = null;
        }
        veiled = false;
        veilAlpha = 1f;
    }

    /**
     * Hit-test du tap : retourne vrai si la carte L'ENGLOUTIT (le tap
     * ne descend pas dans la cascade texte). Onglets et ✕ agissent ;
     * le corps consomme sans agir. Les coordonnées sont traduites en
     * locales via le placement courant (le compactage est inverse).
     */
    public boolean hitTest(float x, float y) {
        EditorPreviewDropGeometry.Placement p = placement();
        if (p == null) return false;
        if (!p.contains(x, y)) return false;
        float lx = p.toLocalX(x);
        float ly = p.toLocalY(y);
        // Fermeture ✕.
        if (closeRect.contains(lx, ly)) {
            preview.closePreviewDrop();
            return true;
        }
        // Onglets → bascule + notification du hôte.
        for (int i = 0; i < tabRects.length; i++) {
            if (tabRects[i].contains(lx, ly)) {
                if (i != selectedTab) {
                    selectedTab = i;
                    EditorPreviewHost host = preview.getHost();
                    if (host != null) host.onPreviewDropTabSelected(i);
                    view.invalidate();
                }
                return true;
            }
        }
        // Corps de la carte : englouti (le caret ne bouge pas — la
        // surface d'aperçu n'est pas une zone d'édition).
        return true;
    }

    /**
     * Bornes écran de la carte (pour le fondu de sortie —
     * {@code EditorPopupFade.PopupPainter.bounds()}).
     */
    public RectF bounds() {
        EditorPreviewDropGeometry.Placement p = placement();
        if (p == null) return null;
        hitCard.set(p.cardLeft, p.cardTop,
                p.cardLeft + p.visualWidth(), p.cardTop + p.visualHeight());
        return hitCard;
    }
}
