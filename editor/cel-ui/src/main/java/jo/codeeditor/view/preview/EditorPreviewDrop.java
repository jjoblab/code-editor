package jo.codeeditor.view.preview;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.EditorMetrics;

import androidx.annotation.RestrictTo;

import android.animation.ValueAnimator;
import android.graphics.RectF;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;

import java.util.Arrays;
import java.util.List;

/**
 * Contrôleur de la carte d'aperçu « goutte d'eau » (B27) — l'état vivant
 * du popup ancré à l'œil de gouttière : ouverture/fermeture, ligne
 * d'ancre GELÉE (l'œil ne suit plus le caret une fois la carte ouverte),
 * onglet sélectionné (fourni par le hôte), hit-tests des zones tactiles
 * (onglets, fermeture, corps, barre de taille du pied) et calcul du
 * placement frame par frame.
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
 *       notifié, il re-rend) ; ✕ → fermeture ; <b>barre de taille du
 *       pied</b> → press = saut immédiat, drag vivant, relâchement →
 *       hôte notifié (persistance, retour terrain n°3) ; corps →
 *       englouti (le caret ne bouge pas) ;</li>
 *   <li>tap <b>ailleurs dans l'éditeur</b> → cascade normale (le caret
 *       se pose) — la carte reste ouverte et ESQUIVE la nouvelle zone
 *       de frappe (non modale, miroir de la maquette) ;</li>
 *   <li>fermetures de sûreté : changement de session, fichier non
 *       prévisualisable, détachement de la fenêtre.</li>
 * </ul>
 *
 * <p>La carte vit en coordonnées écran (viewport) : l'ancre et le caret
 * sont re-résolus à CHAQUE frame — la carte suit l'œil au scroll et
 * esquive le caret à la frappe, et depuis le retour terrain n°5 les
 * RE-PLACEMENTS GLISSENT (transitoire 180 ms en trois couches et en
 * variante B — le fondu d'entrée/sortie du pipeline et le glissement
 * composent la même motion).</p>
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
    /** Marge autour du mini-téléphone (dp) — resserrée au retour terrain
     *  (10 → 4 : « les marges sont un peu trop grosses »), la bande
     *  restante est TROUÉE au rendu (marge transparente). */
    public static final float PHONE_MARGIN_DP = 4f;
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
    /** Durée du glissement de re-placement (ms) — miroir du retour du
     *  voile (retour terrain n°5 : « ça manque de fluidité », les
     *  re-placements d'esquive sautaient frame à frame). */
    static final long GLIDE_MS = 180L;
    /** Mouvement en dessous de ce seuil (dp) : suivi rigide (scroll),
     *  pas de glissement — n'anime pas le bruit sub-pixel. */
    static final float GLIDE_EPSILON_DP = 2f;
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
    /** Vrai quand un popup de frappe recouvre la carte (variante B :
     *  fantôme DYNAMIQUE — la goutte ne devient eau à 10–15 % QUE
     *  pendant le chevauchement, retour terrain n°4). */
    private boolean ghosted;

    // ── Glissement fluide (retour terrain n°5 : « ça manque de ─────
    // fluidité », les re-placements d'esquive sautaient frame à ─────
    // frame) ───────────────────────────────────────────────────────

    /** Œil courant (px écran) — posé par chaque calcul de placement()
     *  (référence du mouvement RIGIDE : scroll). */
    private float eyeX, eyeY;
    /** Vrai quand une frame a déjà fixé la référence (acquisition :
     *  la première frame pose la position exacte). */
    private boolean glideAdopted;
    /** Vrai quand un transitoire de glissement vit. */
    private boolean glideActive;
    /** Origine du temps du transitoire courant (horloge d'animation). */
    private long glideStartMs;
    /** Amplitude du transitoire au départ (px / échelle additive) :
     *  l'offset dessiné vaut amplitude × (1 − décélération(t)). */
    private float glideAmpX, glideAmpY, glideAmpS;
    /** Cible et œil de la frame PRÉCÉDENTE (prédiction rigide). */
    private float refLeft, refTop, refScale, refEyeX, refEyeY;

    // ── Options propriétaire (B27) ────────────────────────────────

    /** Politique de cohabitation avec les popups de frappe — ORIGINAL par défaut. */
    private EditorView.PreviewDropPolicy policy = EditorView.PreviewDropPolicy.ORIGINAL;
    /** Bornes du facteur d'agrandissement de la carte (option taille). */
    public static final float MIN_CARD_SCALE = 1f;
    /** Miroir de {@link #MIN_CARD_SCALE}. */
    public static final float MAX_CARD_SCALE = 1.5f;
    /** Facteur d'agrandissement appliqué à toute la carte (1 = maquette). */
    private float cardScale = 1f;

    // ── Barre de taille (retour terrain n°3) ──────────────────────

    /** Largeur de la piste de la barre de taille (dp — pied de carte). */
    public static final float SCALE_TRACK_W_DP = 48f;
    /** Hauteur visuelle de la piste (dp). */
    public static final float SCALE_TRACK_H_DP = 4f;
    /** Diamètre du curseur rond de la piste (dp). */
    public static final float SCALE_THUMB_D_DP = 11f;
    /** Tolérance horizontale du hit-test autour de la piste (dp). */
    public static final float SCALE_HIT_PAD_X_DP = 12f;
    /** Tolérance verticale du hit-test autour de la piste (dp). */
    public static final float SCALE_HIT_PAD_Y_DP = 13f;
    /** Alpha « fantôme » de la variante B — la goutte devient de l'eau
     *  à 10–15 % d'opacité UNIQUEMENT pendant qu'un popup de frappe la
     *  recouvre (retour terrain n°4 : « la goutte ne doit pas être
     *  transparente dès le départ — seulement pendant que l'autre
     *  popup la chevauche ; dès qu'il ferme, elle redevient
     *  normale »). */
    static final float VARIANT_B_GHOST_ALPHA = 0.12f;

    /** Rectangle LOCAL de la piste de taille (px — partagé dessin/
     *  hit-test, posé à l'ouverture à côté de la puce « fit »). */
    private final RectF scaleSliderRect = new RectF();
    /** Repère de piste GELÉ au début du geste (px écran) — bord gauche.
     *  Toute la progression du geste se calcule dans CE repère : la
     *  carte peut grandir sous le doigt, la progression reste linéaire
     *  et STABLE (le re-mappage sur le placement vivant crée une boucle
     *  divergente — le doigt immobile ferait osciller la valeur). */
    private float sliderBaseLeft;
    /** Largeur de piste au repère gelé (px — 0 = pas de geste en cours). */
    private float sliderBaseW;

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

    // ── Options propriétaire (B27) ───────────────────────────────

    /**
     * Politique de cohabitation de la carte avec les popups de frappe
     * — ORIGINAL par défaut (voir {@link EditorView.PreviewDropPolicy}).
     * Le changement réinitialise le voile : une carte ouverte repart
     * opaque, la nouvelle politique s'applique au frame suivant.
     */
    public void setPolicy(EditorView.PreviewDropPolicy p) {
        if (p == null) p = EditorView.PreviewDropPolicy.ORIGINAL;
        if (p == policy) return;
        this.policy = p;
        resetVeil();
        resetGlide(); // la nouvelle politique repart de la position exacte
        view.invalidate();
    }

    /** Politique courante (ORIGINALE par défaut). */
    public EditorView.PreviewDropPolicy policy() { return policy; }

    /**
     * Facteur d'agrandissement de la carte, borné dans
     * [{@link #MIN_CARD_SCALE}, {@link #MAX_CARD_SCALE}] — 1 = taille
     * de la maquette. Appliqué à toute la carte (chrome + téléphone) ;
     * le placement (clamps, esquive, compactage) se recalcule sur la
     * carte agrandie au frame suivant.
     */
    public void setCardScale(float scale) {
        float clamped = Math.max(MIN_CARD_SCALE, Math.min(MAX_CARD_SCALE, scale));
        if (clamped == cardScale) return;
        this.cardScale = clamped;
        view.invalidate();
    }

    /** Facteur d'agrandissement courant (1 par défaut). */
    public float cardScale() { return cardScale; }

    /**
     * Vrai quand la carte se dessine SOUS les popups de frappe
     * (variante B : z-order inversé — la complétion la recouvre).
     * Le renderer consulte ce drapeau pour choisir son étape de dessin.
     */
    public boolean drawsUnderTypingPopups() {
        return policy == EditorView.PreviewDropPolicy.VARIANT_B;
    }

    /**
     * Alpha global de la goutte (1 opaque) — la variante B la rend
     * « fantôme » à 10–15 % d'opacité UNIQUEMENT pendant qu'un popup
     * de frappe la recouvre (retour terrain n°4). Le painter enveloppe
     * losange + carte dans une couche alpha pilotée par
     * min(veilAlpha, ghostAlpha) : l'apparition du fantôme est
     * instantanée (le popup qui survient AU-DESSUS masque le saut),
     * le RETOUR à la normale est fluide (fondu de l'alpha animé,
     * miroir du voile — la tige reste opaque, l'ancre œil↔goutte
     * reste lisible).
     */
    public float ghostAlpha() {
        return policy == EditorView.PreviewDropPolicy.VARIANT_B && ghosted
                ? VARIANT_B_GHOST_ALPHA : 1f;
    }

    /** Vrai quand un popup de frappe recouvre la carte (variante B).
     *  État SYNCHRONE — bascule au frame où le recouvrement franchit
     *  les plateaux d'hystérésis, l'alpha animé vit sur appareil réel
     *  (même constat Robolectric que le voile). */
    public boolean isGhosted() { return ghosted; }

    /**
     * Machine d'états du fantôme (variante B) — miroir exact de
     * l'hystérésis du voile, côté protégé inversé : le ratio est la
     * fraction de l'AIRE DE LA CARTE recouverte par un popup (le voile
     * protège les rangées de complétion, le fantôme protège la carte).
     * Fantôme au-delà de {@link #VEIL_ON_RATIO}, retour à la normale
     * en dessous de {@link #VEIL_OFF_RATIO}, STABLE entre les deux
     * (la complétion change de hauteur à chaque frappe, l'état ne
     * doit pas vaciller). Appelé par {@link #updateVeil} à chaque
     * frame avec le ratio réel.
     *
     * <p>Composition du painter : couche = min(veilAlpha,
     * ghostAlpha). L'engagement du fantôme est VISUELLEMENT instantané
     * (la cible 0,12 clampé l'alpha interne qui descend vers elle —
     * et le popup qui apparaît AU-DESSUS masque le changement). Le
     * RETOUR est fluide : l'alpha interne repart du plancher fantôme
     * (clampé en pleine descente — jamais au-dessus, aucun saut
     * visible) et remonte vers 1 par le fondu de 180 ms.</p>
     */
    void updateGhostRatio(float ratio) {
        if (!ghosted && ratio >= VEIL_ON_RATIO) {
            ghosted = true;
            animateVeil(VARIANT_B_GHOST_ALPHA, VEIL_IN_MS);
        } else if (ghosted && ratio <= VEIL_OFF_RATIO) {
            ghosted = false;
            // Clamp de mi-descente (appareil réel uniquement — le
            // harnais gelé à 1 saute ce clamp) : la couche visible
            // était CLAMPÉ au plancher fantôme, l'alpha interne repart
            // d'exactement là — le retour démarre sans saut.
            if (veilAlpha < 1f && veilAlpha > VARIANT_B_GHOST_ALPHA) {
                veilAlpha = VARIANT_B_GHOST_ALPHA;
            }
            animateVeil(1f, VEIL_OUT_MS);
        }
    }

    /**
     * Hit-test de la barre de taille du pied (retour terrain n°3) —
     * écran → local avec tolérance ({@link #SCALE_HIT_PAD_X_DP} ×
     * {@link #SCALE_HIT_PAD_Y_DP}) : le doigt n'a pas besoin de viser
     * une piste de 4 dp. Le point doit rester DANS la carte (le reste
     * du pied reste englouti par le corps).
     */
    public boolean hitTestSlider(float x, float y) {
        if (!open || scaleSliderRect.isEmpty()) return false;
        EditorPreviewDropGeometry.Placement p = placement();
        if (p == null || !p.contains(x, y)) return false;
        float density = view.getResources().getDisplayMetrics().density;
        float padX = SCALE_HIT_PAD_X_DP * density;
        float padY = SCALE_HIT_PAD_Y_DP * density;
        float lx = p.toLocalX(x);
        float ly = p.toLocalY(y);
        return lx >= scaleSliderRect.left - padX
                && lx <= scaleSliderRect.right + padX
                && ly >= scaleSliderRect.top - padY
                && ly <= scaleSliderRect.bottom + padY;
    }

    /**
     * Presse sur la piste (DOWN) : gèle le repère écran de la piste
     * pour TOUT le geste puis saute le curseur à la position du doigt
     * (press = saut immédiat, miroir du sélecteur de couleur B26).
     * Le gel garantit la stabilité : la progression reste une fonction
     * linéaire de la position du doigt, même quand la carte se
     * redimensionne sous lui au fil du drag.
     */
    public void sliderPress(float x, float y) {
        if (!open || scaleSliderRect.isEmpty()) return;
        EditorPreviewDropGeometry.Placement p = placement();
        if (p == null) return;
        sliderBaseLeft = p.cardLeft + scaleSliderRect.left * p.scale;
        sliderBaseW = scaleSliderRect.width() * p.scale;
        sliderDragTo(x, y);
    }

    /**
     * Drag sur la piste (MOVE) : traduit la position écran en facteur
     * de taille (progression [0,1] → [1, 1,5]) dans le repère GELÉ au
     * press — vivant au frame suivant (la carte se redimensionne sous
     * le doigt), la progression reste exactement celle du doigt.
     */
    public void sliderDragTo(float x, float y) {
        if (!open || sliderBaseW <= 0f) return;
        float progress = (x - sliderBaseLeft) / sliderBaseW;
        setCardScale(scaleFromProgress(progress));
    }

    /**
     * Relâchement de la piste (UP) : dégèle le repère et notifie le
     * hôte du facteur choisi UNE fois par geste (persistance côté
     * application — {@link EditorPreviewHost#onPreviewDropScaleChanged(float)}).
     */
    public void sliderRelease() {
        sliderBaseLeft = 0f;
        sliderBaseW = 0f;
        if (!open) return;
        EditorPreviewHost host = preview.getHost();
        if (host != null) host.onPreviewDropScaleChanged(cardScale());
    }

    /** Rectangle LOCAL de la piste de taille (px — lecture painter). */
    public RectF scaleSliderRect() {
        return scaleSliderRect;
    }

    /**
     * Traduit une progression de piste [0,1] en facteur de taille
     * (clampé — la piste ne peut pas sortir de ses bornes).
     */
    public static float scaleFromProgress(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        return MIN_CARD_SCALE + p * (MAX_CARD_SCALE - MIN_CARD_SCALE);
    }

    /** Miroir de {@link #scaleFromProgress} — facteur → progression
     *  [0,1] (clampé — pour dessiner le curseur). */
    public static float progressFromScale(float scale) {
        float p = (scale - MIN_CARD_SCALE) / (MAX_CARD_SCALE - MIN_CARD_SCALE);
        return Math.max(0f, Math.min(1f, p));
    }

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
        this.scaleSliderRect.setEmpty();
        this.sliderBaseLeft = 0f;
        this.sliderBaseW = 0f;
        resetVeil();
        resetGlide(); // une ré-ouverture repart de la position exacte
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
        // Barre de taille (retour terrain n°3) : piste dans le pied, à
        // gauche de la puce « fit » LA PLUS LARGE possible — même
        // formule que le painter (texte 9 dp + padding 16 dp) : le
        // rect est partagé dessin/hit-test, aucun chevauchement
        // possible quelle que soit la valeur affichée.
        android.graphics.Paint p9 = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        p9.setTextSize(9f * density);
        float fitMaxW = p9.measureText("fit · 150 %") + 16f * density;
        float footerCy = CARD_H_DP * density - FOOTER_H_DP * density * 0.5f;
        float trackRight = cardW - 12f * density - fitMaxW - 8f * density;
        scaleSliderRect.set(trackRight - SCALE_TRACK_W_DP * density,
                footerCy - SCALE_TRACK_H_DP * 0.5f * density,
                trackRight, footerCy + SCALE_TRACK_H_DP * 0.5f * density);
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
     * des obstacles vivants (popups de frappe — TROIS couches
     * uniquement : l'ORIGINALE et la variante B sont aveugles aux
     * popups, miroir des formules historiques). L'option taille
     * ({@link #cardScale}) multiplie les dimensions FED à la
     * géométrie, puis le placement est RE-BASÉ en coordonnées
     * maquette : l'échelle portée devient compactage × agrandissement,
     * le dessin et le hit-test (coordonnées locales) restent en
     * repère maquette sans aucune division spéciale. Retourne null si
     * fermée ou sans session.
     */
    public EditorPreviewDropGeometry.Placement placement() {
        if (!open || view.session == null || view.session.getDocument() == null) {
            return null;
        }
        float density = view.getResources().getDisplayMetrics().density;
        EditorMetrics m = view.metrics;
        float cardW = CARD_W_DP * density * cardScale;
        float cardH = CARD_H_DP * density * cardScale;
        float edgePad = EDGE_PAD_DP * density;
        float lineHeight = m.getLineHeight();
        // Œil : pill B9c centrée dans la zone des numéros de la rangée
        // d'ancre (écran : docLineToY inclut padTop, on retire vOffset).
        float numberArea = m.getGutterWidth() - m.getFoldStripWidth();
        float eyeX = numberArea * 0.5f;
        float rowTop = view.docLineToY(anchorLine) - view.scroll.vOffset;
        float eyeY = rowTop + lineHeight * 0.5f;
        // Référence du mouvement rigide (scroll) — partagée avec
        // drawnPlacement (même formule, zéro divergence).
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        // Zone de frappe : caret ± 1 ligne (le bloc que l'utilisateur
        // regarde en tapant).
        int caretOffset = view.session.getSelection().start;
        int caretLine = view.session.getDocument().lineForOffset(caretOffset);
        float caretY = view.docLineToY(caretLine) - view.scroll.vOffset;
        float guardTop = caretY - lineHeight;
        float guardBot = caretY + 2f * lineHeight;
        // Obstacles : TROIS couches seulement (esquive par popups).
        float[] obstacles = policy == EditorView.PreviewDropPolicy.THREE_LAYERS
                ? collectObstacles() : null;
        EditorPreviewDropGeometry.Placement p = EditorPreviewDropGeometry.place(
                view.getWidth(), view.getHeight(),
                cardW, cardH,
                eyeX, eyeY, EYE_RADIUS_DP * density,
                guardTop, guardBot,
                obstacles,
                edgePad, ANCHOR_DROP_OFFSET_DP * density,
                MIN_SCALE, STEM_THRESHOLD_DP * density);
        if (cardScale == 1f) return p;
        // Re-base maquette : échelle totale = compactage × agrandissement,
        // coordonnées locales divisées (l'écran ne bouge PAS d'un pixel).
        return new EditorPreviewDropGeometry.Placement(
                p.cardLeft, p.cardTop,
                CARD_W_DP * density, CARD_H_DP * density,
                p.scale * cardScale,
                p.dropLocalY / cardScale,
                p.stemVisible, p.stemX0, p.stemY0, p.stemX1, p.stemY1,
                p.level, p.overlapRatio, p.obstructed);
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
     * seul passage garanti quand la carte est dessinée). TROIS couches
     * : hystérésis du voile (la carte au premier plan se voile quand
     * elle recouvre la complétion). VARIANTE B : fantôme DYNAMIQUE —
     * la carte se dessine SOUS les popups, elle devient eau à 10–15 %
     * PENDANT le chevauchement et redevient normale dès que le popup
     * referme (retour terrain n°4). L'ORIGINALE ne se voile JAMAIS
     * (la maquette est opaque). Hystérésis à deux plateaux : voile
     * au-delà de {@link #VEIL_ON_RATIO}, dévoile en dessous de
     * {@link #VEIL_OFF_RATIO}, STABLE entre les deux (la complétion
     * filtre à chaque frappe, l'état ne doit pas trembler).
     * Idempotent par frame : seul un CHANGEMENT de cible anime.
     */
    public void updateVeil(EditorPreviewDropGeometry.Placement p) {
        if (p == null) return;
        if (policy == EditorView.PreviewDropPolicy.THREE_LAYERS) {
            float ratio = p.overlapRatio;
            if (!veiled && ratio >= VEIL_ON_RATIO) {
                veiled = true;
                animateVeil(VEILED_ALPHA, VEIL_IN_MS);
            } else if (veiled && ratio <= VEIL_OFF_RATIO) {
                veiled = false;
                animateVeil(1f, VEIL_OUT_MS);
            }
        } else if (policy == EditorView.PreviewDropPolicy.VARIANT_B) {
            updateGhostRatio(popupCoverageOfCard(p));
        }
    }

    /**
     * Fraction de l'AIRE DE LA CARTE recouverte par un popup de frappe
     * vivant (complétion, doc rapide — miroir du voile : la géométrie
     * d'esquive protège le POPUP, le fantôme protège la CARTE).
     * Maximum sur les obstacles — 0 si aucun popup vivant.
     */
    private float popupCoverageOfCard(EditorPreviewDropGeometry.Placement p) {
        float[] obstacles = collectObstacles();
        if (obstacles == null) return 0f;
        float cardArea = p.visualWidth() * p.visualHeight();
        if (cardArea <= 0f) return 0f;
        float best = 0f;
        for (int i = 0; i + 3 < obstacles.length; i += 4) {
            float w = Math.min(p.cardLeft + p.visualWidth(), obstacles[i + 2])
                    - Math.max(p.cardLeft, obstacles[i]);
            float h = Math.min(p.cardTop + p.visualHeight(), obstacles[i + 3])
                    - Math.max(p.cardTop, obstacles[i + 1]);
            if (w > 0f && h > 0f) {
                best = Math.max(best, (w * h) / cardArea);
            }
        }
        return best;
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

    /** Réinitialise le voile ET le fantôme (fermeture/ré-ouverture,
     * changement de politique : opaque, aucun animateur). */
    private void resetVeil() {
        if (veilAnimator != null) {
            veilAnimator.cancel();
            veilAnimator = null;
        }
        veiled = false;
        ghosted = false;
        veilAlpha = 1f;
    }

    /**
     * Placement DESSINÉ de la carte (retour terrain n°5 : « ça manque
     * de fluidité » — les re-placements d'esquive sautaient frame à
     * frame) — glissement translatoire animé VERS le placement cible
     * en TROIS couches et en variante B : la carte ne téléporte plus,
     * elle GLISSE. L'ORIGINALE reste la maquette exacte (jamais de
     * glissement), le geste de la barre de taille reste DIRECT (la
     * carte suit le doigt sans inertie), et le suivi de l'œil au scroll
     * reste RIGIDE (aucun retard sur le défilement).
     *
     * <p>Le glissement est purement VISUEL, miroir des fondus B7 et du
     * voile d'eau : l'interception des gestes, les bornes du fondu de
     * sortie et le voile vivent sur le placement CIBLE (le transitoire
     * dure {@link #GLIDE_MS} ms). Appelé par le painter à chaque frame
     * avec la cible qu'il vient de calculer.</p>
     */
    public EditorPreviewDropGeometry.Placement drawnPlacement(
            EditorPreviewDropGeometry.Placement target) {
        if (target == null) return null;
        // ORIGINALE : la maquette exacte, JAMAIS de glissement. Geste de
        // la barre de taille : manipulation DIRECTE — la carte suit le
        // doigt sans inertie (retour terrain n°3 « drag vivant »).
        if (policy == EditorView.PreviewDropPolicy.ORIGINAL || sliderBaseW > 0f) {
            resetGlide();
            adopt(target);
            return target;
        }
        // Première frame (ouverture, ré-ouverture, changement de
        // politique) : le fondu d'entrée B7 couvre l'apparition — la
        // position exacte est posée d'emblée.
        if (!glideAdopted) {
            adopt(target);
            return target;
        }
        // Prédiction rigide : la part du mouvement de la cible expliquée
        // par l'œil (le SCROLL translate œil, garde et carte ENSEMBLE).
        // Le RESTE est un re-placement (esquive, latéral, compactage,
        // garde de frappe) → la carte GLISSE au lieu de téléporter.
        float jumpX = (target.cardLeft - refLeft) - (eyeX - refEyeX);
        float jumpY = (target.cardTop - refTop) - (eyeY - refEyeY);
        float jumpS = target.scale - refScale;
        float eps = GLIDE_EPSILON_DP * view.getResources().getDisplayMetrics().density;
        if (Math.abs(jumpX) > eps || Math.abs(jumpY) > eps
                || Math.abs(jumpS) > 0.01f) {
            // Continuité : le transitoire repart de la position DESSINÉE
            // courante (offset vif de l'ancien transitoire) MOINS le
            // saut — même en rafale de frappe (la cible bouge en plein
            // vol), la carte ne téléporte jamais.
            glideAmpX = glideOffset(glideAmpX) - jumpX;
            glideAmpY = glideOffset(glideAmpY) - jumpY;
            glideAmpS = glideOffset(glideAmpS) - jumpS;
            glideStartMs = AnimationUtils.currentAnimationTimeMillis();
            glideActive = true;
        }
        // Fin de transitoire : l'état retombe au repos (offset nul).
        if (glideActive && AnimationUtils.currentAnimationTimeMillis()
                - glideStartMs >= GLIDE_MS) {
            glideActive = false;
            glideAmpX = glideAmpY = glideAmpS = 0f;
        }
        float offX = glideOffset(glideAmpX);
        float offY = glideOffset(glideAmpY);
        float offS = glideOffset(glideAmpS);
        adopt(target);
        if (!glideActive) return target;
        // Le transitoire pulse le frame suivant (il vit GLIDE_MS ms).
        view.postInvalidateOnAnimation();
        return new EditorPreviewDropGeometry.Placement(
                target.cardLeft + offX, target.cardTop + offY,
                target.cardW, target.cardH,
                target.scale + offS, target.dropLocalY,
                target.stemVisible,
                target.stemX0, target.stemY0, // œil : FIXE, hors transitoire
                target.stemX1 + offX, target.stemY1 + offY, // goutte : suit la carte
                target.level, target.overlapRatio, target.obstructed);
    }

    /**
     * Adopte la cible courante comme référence du frame suivant
     * (position + œil — la prédiction rigide du scroll).
     */
    private void adopt(EditorPreviewDropGeometry.Placement target) {
        glideAdopted = true;
        refLeft = target.cardLeft;
        refTop = target.cardTop;
        refScale = target.scale;
        refEyeX = eyeX;
        refEyeY = eyeY;
    }

    /**
     * Offset courant d'une amplitude du transitoire (0 au repos) —
     * décélération quadratique : rapide au départ, calme à l'arrivée
     * (même allure que le retour du voile, sans ValueAnimator : la
     * valeur se calcule à la frame, l'horloge d'animation reste la
     * source de temps — déterministe en test comme sur appareil).
     */
    private float glideOffset(float amp) {
        if (!glideActive || amp == 0f) return 0f;
        float t = (AnimationUtils.currentAnimationTimeMillis() - glideStartMs)
                / (float) GLIDE_MS;
        if (t <= 0f) return amp;
        if (t >= 1f) return 0f;
        float eased = 1f - (1f - t) * (1f - t);
        return amp * (1f - eased);
    }

    /**
     * Réinitialise le glissement (fermeture, changement de politique :
     * une nouvelle politique repart de la position exacte, miroir du
     * voile).
     */
    private void resetGlide() {
        glideAdopted = false;
        glideActive = false;
        glideAmpX = glideAmpY = glideAmpS = 0f;
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
