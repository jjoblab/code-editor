package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.EditorContextMenuHandler;
import jo.codeeditor.view.popup.EditorPopupAnchors;
import jo.codeeditor.view.popup.EditorPopupHitTester;
import jo.codeeditor.view.preview.EditorPreviewHost;
import jo.codeeditor.view.preview.EditorPreviewDropGeometry;
import jo.codeeditor.view.state.CompletionState;

import androidx.annotation.RestrictTo;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

/**
 * Orchestrateur de l'entrée tactile d'EditorView — distribue les
 * événements tactiles (onTouchEvent), conserve l'état du geste en cours
 * (drag/scroll, gestes engloutis par les popups, appui long, pincement)
 * et route chaque geste vers ses collaborateurs.
 *
 * <p>EditorView délègue {@code onTouchEvent}, {@code computeScroll},
 * {@code performClick} et {@code onGenericMotionEvent} à cette classe.
 * L'état du geste en cours (isDragging, isScrolling, lastTouchX/Y,
 * drapeaux des gestes engloutis par les popups…) vit ici ; tout le reste
 * de l'état de l'éditeur (session, metrics, theme, drapeaux de popup)
 * reste dans EditorView et est accédé via la référence {@code view}.
 *
 * <p>Collaborateurs (composition, package-privés) : {@link EditorTouchScroller}
 * (mécanique scroll/fling + suivi de vitesse), {@link EditorTapResolver}
 * (cascade de résolution des taps + tap-dismiss différé),
 * {@link EditorSelectionGestures} (appui long, drag-select, poignées +
 * loupe, toolbar de sélection), {@link EditorPopupHitTester} (tests de
 * touche des popups), {@link EditorTouchHoverController} (hover
 * tap-and-hold) et {@link EditorContextMenuHandler} (menu contextuel
 * matériel).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorInputHandler {

    private final EditorView view;

    // ── Détecteurs + collaborateurs ──────────────────────────
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    // Mécanique scroll/fling tactile (OverScroller + suivi de vitesse).
    private final EditorTouchScroller scrollerCtl;
    // Menu contextuel matériel (clic secondaire → PopupMenu système).
    private final EditorContextMenuHandler contextMenu;
    // Tests de touche géométriques des popups + résolution des taps NavMenu.
    private final EditorPopupHitTester hitTester;
    // Gestes de sélection (appui long, drag-select, poignées + loupe, toolbar).
    private final EditorSelectionGestures selectionGestures;
    // Cascade de résolution des taps (multi-tap, popups, tap-dismiss différé).
    private final EditorTapResolver tapResolver;
    // Hover tap-and-hold (quick doc sans sélection).
    private final EditorTouchHoverController touchHover;

    // ── État tactile ────────────────────────────────────────────────
    private boolean isDragging = false;
    private boolean isScrolling = false;
    // ── Drag-select ──
    // Le drag-select au doigt est armé par l'APPUI LONG (via
    // EditorSelectionGestures.armDragSelect) — jamais par un tap. L'état
    // d'armement vit dans EditorSelectionGestures (isDragSelectArmed) ;
    // il est désarmé à toute terminaison de geste et à tout nouveau DOWN
    // (désarmement au DOWN). Un simple tap ne peut donc
    // JAMAIS créer de sélection, même avec le jitter naturel du doigt.
    /** Vrai dès qu'un drag de poignée a dépassé le touch-slop
     *  (la poignée ne bouge pas avant). */
    private boolean handleMoving = false;
    private float lastTouchX = 0;
    private float lastTouchY = 0;
    private float touchStartX = 0;
    private float touchStartY = 0;
    private static final float TAP_SLOP_SQ = 24f * 24f;
    // Vrai pendant qu'un pincement est en cours, pour que le ACTION_UP
    // final ne retombe pas sur handleTap (ce qui déplacerait le caret).
    // Réinitialisé au prochain ACTION_DOWN frais (nouveau geste).
    private boolean wasPinching = false;
    // Vrai pendant que l'utilisateur fait défiler le popup de complétion
    // par glissement.
    private boolean completionScrolling = false;

    // ── Drapeau d'appui long ───────────────────────────────────────
    private boolean longPressTriggered = false;

    // ── Geste modal de la fiche de diagnostic ──────────────
    // Pendant que la fiche de diagnostic est ouverte,
    // son scrim consomme tout le geste (pas de scroll/drag derrière la
    // modale) ; le tap UP est routé vers handleTap qui applique une
    // quick-fix ou ferme.
    private boolean sheetGesture = false;

    // ── Geste de la toolbar de sélection ───────────────────────────
    // Pendant que la toolbar flottante de sélection est ouverte, un DOWN
    // dans sa pill engloutit le geste (le Popup fait
    // pareil) : feedback de pression sur l'item touché, pas de scroll/
    // caret derrière le popup, et le UP se résout via handleTap →
    // handleSelectionToolbarTap.
    private boolean toolbarGesture = false;

    // ── Geste du menu contextuel unifié ────────────────────────────
    // Pendant que le NavMenu (Actions ⋯) est ouvert, un DOWN dans sa carte
    // engloutit le geste : feedback de pression sur la rangée touchée, le
    // DRAG fait défiler le contenu (verticalScroll/LazyColumn de
    //), et le UP résout un tap (choix de rangée) — ou rien sur
    // les en-têtes/gaps. Un DOWN HORS de la carte ferme (Popup
    // onDismissRequest).
    private boolean navMenuGesture = false;
    private boolean navMenuDragging = false;
    /** Le geste est englouti par le popup quick doc (drag = scroll du corps). */
    private boolean quickDocGesture = false;

    // ── Geste du sélecteur de couleur (B26) ──────────────
    // Un DOWN dans la carte du sélecteur engloutit le geste (parité
    // pill/NavMenu) : press du champ saturation/valeur ou de la bande
    // de teinte (saut immédiat), DRAG = mise à jour vivante, UP =
    // application (palette ou fin de drag). Un DOWN HORS de la carte
    // la referme sans engloutir le geste (le tap derrière continue sa
    // cascade — placement de caret).
    private boolean colorPickerGesture = false;
    // ★ B27 : geste englouti par la carte d'aperçu « goutte d'eau » —
    // un DOWN DANS la carte lui appartient (onglet/✕ au tap via la
    // cascade handleTap, corps inerte) : pas de scroll, pas de caret,
    // pas d'appui long derrière elle.
    private boolean dropCardGesture = false;
    /** ★ B27 (retour terrain n°3) : geste de la BARRE DE TAILLE de la
     *  goutte (piste du pied) — press = saut, drag vivant, release →
     *  hôte notifié. */
    private boolean dropSliderGesture = false;
    // ★ B27-C3 : geste du CORPS INTERACTIF de la goutte — le hôte
    // interactif (wantsInteractiveDropPreview()) reçoit chaque touche de
    // l'écran du mini-téléphone dans le repère de drawDropPreview ;
    // l'appui long annule le geste par CANCEL puis notifie
    // (inspection). Un hôte non-interactif ne quitte JAMAIS ce chemin :
    // ses corps restent engloutis sans agir, au bit près.
    private boolean dropBodyGesture = false;
    /** Hôte interactif capturé au DOWN (le geste lui appartient jusqu'au bout). */
    private EditorPreviewHost dropBodyHost;
    /** Dernier point transféré au hôte (coords écran, repère drawDropPreview). */
    private float dropBodySx = 0f;
    private float dropBodySy = 0f;
    /** Réceptacle réutilisé du hit-test du corps (aucune allocation par touche). */
    private final float[] dropBodyPoint = new float[2];
    /** Appui long du corps en attente (armé au DOWN, retiré au slop/UP/CANCEL). */
    private Runnable dropBodyLongPress;
    /** Handler de l'appui long du corps — looper principal (même patron
     *  que tapHandler : testable sous l'horloge fantôme, view.getHandler()
     *  est null tant que la vue n'est pas attachée). */
    private android.os.Handler corpsHandler;

    // ── Tap gutter vs drag gutter ───────────────────────────
    // Un ACTION_DOWN dans la zone des numéros de ligne arme isScrolling
    // (pour qu'un DRAG VERTICAL sur le gutter fasse défiler le document),
    // mais ce même drapeau faisait qu'un TAP (sans mouvement) sautait
    // handleTap entièrement — le point de diagnostic ne pouvait jamais
    // ouvrir la fiche (« dot pas câblé »). Ce drapeau retient où le geste
    // a commencé pour que UP distingue un TAP gutter d'un drag gutter.
    private boolean downInLineNumberArea = false;

    // ── B11 (P1-1) : geste de la bande d'erreurs ───────────────
    // Un DOWN dans la zone tactile du bord droit (bande d'erreurs)
    // engloutit le geste : tap sur un marqueur → centrer la ligne +
    // fiche de diagnostic (résolu au UP via handleTap) ; drag →
    // défilement ABSOLU du document (pouce + bulle du fast-scroller,
    // même mécanique que le drag de gouttière B11/P0-3).
    private boolean stripeGesture = false;

    // ── B13 (P1-2) : geste du drag-déposer de bloc ─────────
    // Le mode est ARMÉ par la pill (item ✥ Move) — le geste SUIVANT
    // appartient au bloc : drag = déplacement (loupe + caret fantôme
    // + carte flottante + auto-scroll de bord), tap = annulation. Le
    // geste est englouti comme celui de la pill/du menu (pas de
    // scroll ni de caret derrière).
    private boolean blockDragGesture = false;

    // ── Handler partagé des décisions de tap différées ──────────────
    // Possédé par l'orchestrateur ; utilisé par EditorTapResolver
    // (tap-dismiss multi-tap) et EditorTouchHoverController (hover
    // tap-and-hold). Champ historique commun aux deux mécanismes,
    // conservé partagé pour préserver la sémantique d'obtention et de
    // réutilisation (view.getHandler() d'abord, repli looper principal).
    android.os.Handler tapHandler;

    /**
     * Annule TOUT rappel de handler en attente possédé par ce gestionnaire
     * d'entrée (hover tap-and-hold, dismiss multi-tap). Appelée depuis
     * {@code EditorView.onDetachedFromWindow} pour qu'une vue détachée
     * cesse d'exécuter des runnables pilotés par le toucher — sinon chaque
     * rappel en attente gardait la vue (détachée) fortement accessible
     * jusqu'à son déclenchement et pouvait toucher un état que l'hôte
     * avait déjà démonté.
     */
    public void cancelPendingCallbacks() {
        touchHover.cancel();
        cancelPendingTapDismiss();
    }

    public EditorInputHandler(EditorView view) {
        this.view = view;
        Context context = view.getContext();
        this.scrollerCtl = new EditorTouchScroller(view);
        this.contextMenu = new EditorContextMenuHandler(view);
        this.hitTester = new EditorPopupHitTester(view);
        this.selectionGestures = new EditorSelectionGestures(view, this);
        this.tapResolver = new EditorTapResolver(view, this, hitTester, selectionGestures);
        this.touchHover = new EditorTouchHoverController(view, this);
        this.scaleDetector = new ScaleGestureDetector(context, new ScaleListener());
        this.gestureDetector = new GestureDetector(context, new GestureListener());
        // Quand touchHover est activé, le geste d'appui long (sélection)
        // est mutuellement exclusif avec le tap-and-hold (hover).
        // L'appui long du gestureDetector est basculé dynamiquement dans
        // EditorTouchHoverController.schedule/cancel selon le drapeau.
        this.gestureDetector.setIsLongpressEnabled(true);
    }

    // ════════════════════════════════════════════════════════════════
    // Distribution des événements tactiles
    // ════════════════════════════════════════════════════════════════

    public boolean onTouchEvent(MotionEvent event) {
        if (view.session == null) return false;

        // Route d'abord les événements multi-doigts via le détecteur de scale.
        scaleDetector.onTouchEvent(event);
        if (scaleDetector.isInProgress()) {
            if (!wasPinching) {
                // B9 (retour terrain n°6) : le pincement qui démarre prend
                // possession du viewport — tout drag de sélection en cours
                // (poignée ou drag-select au doigt) s'arrête PROPREMENT :
                // le tick d'auto-scroll meurt (dragSelectEngaged=false),
                // la poignée relâchée, la loupe refermée. Sans cela, le
                // tick continuait de défiler avec la dernière direction
                // pendant le pincement.
                selectionGestures.disarmDragSelect();
                view.handleDragMode = 0;
                view.dismissMagnifier();
                // ★ B13 (P1-2) : pincement pendant le drag de bloc — le
                // zoom prend possession du viewport, le bloc retombe
                // PROPREMENT (document intact, sélection d'origine).
                view.blockDragCtl.cancel();
                blockDragGesture = false;
            }
            wasPinching = true;
            isDragging = false;
            isScrolling = false;
            // ★ B11 : le pincement prend possession du viewport — le
            // fast-scroller de gouttière/bande en cours tombe aussi
            // (pouce masqué immédiatement).
            view.fastScroller.cancel();
            stripeGesture = false;
            return true;
        }
        if (event.getPointerCount() > 1) {
            if (!wasPinching) {
                // Multi-doigts hors pincement actif (amorce, doigts posés
                // trop près) : même propriété — le geste n'est plus un drag.
                selectionGestures.disarmDragSelect();
                view.handleDragMode = 0;
                view.dismissMagnifier();
                view.blockDragCtl.cancel();
                blockDragGesture = false;
            }
            wasPinching = true;
            isDragging = false;
            isScrolling = false;
            view.fastScroller.cancel();
            stripeGesture = false;
            return true;
        }

        // Route les événements mono-doigt via le détecteur de gestes pour
        // la détection d'appui long.
        gestureDetector.onTouchEvent(event);

        // Suivi de vitesse pour le fling.
        scrollerCtl.trackMovement(event);

        float x = event.getX();
        float y = event.getY();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                touchStartX = x;
                touchStartY = y;
                isScrolling = false;
                isDragging = false;
                longPressTriggered = false;
                wasPinching = false;
                // Arme le timer de hover tap-and-hold (no-op si la
                // fonctionnalité est désactivée ou si aucun quickDocResolver
                // n'est défini).
                touchHover.cancel();
                touchHover.schedule(x, y);
                // Tout nouveau geste résout le tap-dismiss différé en
                // attente — un double-tap
                // étend la sélection au lieu de la fermer.
                cancelPendingTapDismiss();
                // La fiche de diagnostic est MODALE — consomme le geste
                // pour que l'éditeur ne fasse ni défiler ni placer le caret
                // sous le scrim ; l'événement UP se résout via handleTap.
                // La fiche de liste groupée est modale de la même façon.
                if (view.diagnosticPopupVisible || view.diagnosticListSheetLine >= 0) {
                    sheetGesture = true;
                    scrollerCtl.abortAnimation();
                    return true;
                }
                if (view.completion.visible && hitTester.hitTestCompletionPopup(x, y) >= 0) {
                    completionScrolling = true;
                    return true;
                }
                // La toolbar de sélection engloutit son geste (parité
                //) — feedback de pression + action au
                // relâchement.
                if (view.selectionToolbarVisible) {
                    EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
                    if (m != null && m.contains(x, y)) {
                        toolbarGesture = true;
                        view.selectionToolbarPressedIdx = m.itemIndexAt(x, y);
                        view.invalidate();
                        return true;
                    }
                }
                // Le menu contextuel unifié engloutit aussi son geste —
                // feedback de pression + drag-scroll + choix de rangée au
                // relâchement.
                if (view.navMenuVisible) {
                    float[] nm = view.navMenuMetrics();
                    if (nm != null && x >= nm[0] && x <= nm[0] + nm[2]
                            && y >= nm[1] && y <= nm[1] + nm[3]) {
                        navMenuGesture = true;
                        navMenuDragging = false;
                        view.navMenuPressedIdx = navMenuRowIndexOf(x, y);
                        view.invalidate();
                        return true;
                    }
                }
                // Quick doc : un DOWN DANS le popup l'engloutit
                // (drag = scroll du corps, parité NavMenu) ; un DOWN
                // HORS du popup le referme (tap ailleurs →
                // dismiss) sans engloutir le geste — le tap continue de
                // placer le caret derrière.
                if (view.quickDocVisible) {
                    float[] qd = view.quickDocMetrics();
                    if (qd != null && x >= qd[0] && x <= qd[0] + qd[2]
                            && y >= qd[1] && y <= qd[1] + qd[3]) {
                        quickDocGesture = true;
                        scrollerCtl.abortAnimation();
                        return true;
                    }
                    view.dismissQuickDoc();
                }
                // Sélecteur de couleur (B26) : un DOWN DANS la carte
                // l'engloutit (press + drag vivant, application au UP) ;
                // un DOWN HORS de la carte la referme sans engloutir le
                // geste (le tap derrière place le caret).
                if (view.colorPickerVisible) {
                    EditorPopupAnchors.ColorPickerMetrics cm =
                            EditorPopupAnchors.colorPickerMetrics(view);
                    if (cm != null && cm.contains(x, y)) {
                        colorPickerGesture = true;
                        scrollerCtl.abortAnimation();
                        view.popupManager.colorPickerPressAt(x, y);
                        return true;
                    }
                    view.popupManager.dismissColorPicker();
                }
                // ★ B27 (retour terrain n°3) : barre de TAILLE de la goutte —
                // le DOWN dans la piste (pied de carte, à côté de la puce
                // « fit ») saute le curseur immédiatement (press = saut,
                // miroir du sélecteur de couleur B26) ; le drag suit
                // vivant, le relâchement notifie le hôte (persistance).
                // Contrôlé AVANT la carte entière : la piste possède SON
                // geste, le reste du pied reste englouti par le corps.
                if (view.preview.hitTestDropSlider(x, y)) {
                    dropSliderGesture = true;
                    view.preview.dropSliderPress(x, y);
                    scrollerCtl.abortAnimation();
                    touchHover.cancel();
                    return true;
                }
                // ★ B27-C3 : CORPS INTERACTIF de la goutte — l'écran du
                // mini-téléphone appartient au HÔTE interactif : le DOWN
                // (et son geste entier) part au hôte dans le repère de
                // drawDropPreview, l'appui long arme l'inspection (au
                // déclenchement : CANCEL transféré PUIS callback). Un
                // hôte qui DÉCLINE le DOWN (aucun contenu à ce moment)
                // retombe sur le corps inerte historique POUR CE GESTE.
                EditorPreviewHost hoteCorps = hoteInteractifGoutte();
                if (hoteCorps != null
                        && view.preview.drop().bodyTouchAt(x, y, dropBodyPoint)) {
                    dropBodyHost = hoteCorps;
                    dropBodySx = dropBodyPoint[0];
                    dropBodySy = dropBodyPoint[1];
                    if (hoteCorps.onPreviewDropTouch(MotionEvent.ACTION_DOWN, dropBodySx, dropBodySy)) {
                        dropBodyGesture = true;
                        armerAppuiLongCorps();
                        scrollerCtl.abortAnimation();
                        touchHover.cancel();
                        return true;
                    }
                    dropBodyHost = null;
                }
                // ★ B27 : carte d'aperçu « goutte d'eau » — un DOWN DANS
                // la carte l'engloutit (le tap se résout au UP via
                // handleTap : onglet bascule, ✕ ferme, corps inerte) ;
                // un DOWN HORS de la carte la laisse VIVRE (non
                // modale : le geste continue sa cascade, la carte
                // esquivant la nouvelle zone de frappe au frame
                // suivant). L'appui long tap-and-hold est annulé : le
                // doigt appartient à la carte, pas au texte dessous.
                if (view.preview.hitTestDrop(x, y)) {
                    dropCardGesture = true;
                    scrollerCtl.abortAnimation();
                    touchHover.cancel();
                    return true;
                }
                int handleHit = selectionGestures.hitTestHandle(x, y);
                if (handleHit > 0) {
                    view.handleDragMode = handleHit;
                    // Fige l'ancre du drag au DOWN : la borne opposée à la
                    // poignée attrapée. La relire à chaque MOVE faisait
                    // perdre l'ancre quand le doigt franchissait l'autre
                    // poignée.
                    selectionGestures.beginHandleDrag(handleHit);
                    isScrolling = false;
                    isDragging = false;
                    handleMoving = false;
                    return true;
                }
                view.handleDragMode = 0;
                // Tout nouveau DOWN désarme le drag-select
                // (finishDragSelect) — l'état d'un geste précédent ne
                // peut jamais fuiter dans celui-ci.
                selectionGestures.disarmDragSelect();
                // ★ B13 (P1-2) : drag-déposer de bloc ARMÉ — le geste
                // appartient au bloc (englouti comme celui de la pill :
                // pas de scroll, pas de caret, pas de gouttière). Drag →
                // déplacement, tap → annulation du mode. Le hover
                // tap-and-hold est annulé (le doigt va porter le bloc).
                if (view.blockDragCtl.isArmed()) {
                    blockDragGesture = true;
                    view.blockDragCtl.beginGesture(x, y);
                    touchHover.cancel();
                    scrollerCtl.abortAnimation();
                    return true;
                }
                if (x < view.metrics.getGutterWidth()
                    && x < view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth()) {
                    isScrolling = true;
                    // Retient que le geste a commencé dans la zone des
                    // numéros de ligne — un relâchement SANS MOUVEMENT
                    // là-bas est un TAP (point de diagnostic → fiche), pas
                    // un scroll.
                    downInLineNumberArea = true;
                    // ★ B11 (P0-3) : arme le fast-scroller — le drag de
                    // gouttière devient un positionnement ABSOLU (pouce +
                    // bulle de ligne) ; un tap (sous le slop) reste un tap.
                    view.fastScroller.arm();
                }
                // ★ B11 (P1-1) : la bande d'erreurs du bord droit possède
                // SA zone tactile — le geste lui appartient (tap marqueur
                // au UP, drag = scroll absolu au MOVE). Contrôlé APRÈS la
                // gouttière (zones disjointes) et APRÈS les popups (une
                // complétion ouverte au bord droit garde la priorité).
                stripeGesture = view.errorStripe.hit(x, y);
                if (stripeGesture) {
                    view.fastScroller.arm();
                    scrollerCtl.abortAnimation();
                    return true;
                }
                scrollerCtl.abortAnimation();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (sheetGesture) return true; // scrim modal — englouti
                // ★ B27 : drag vivant de la barre de taille — la carte
                // se redimensionne au frame suivant et la piste suit le
                // doigt (le placement et la piste se recalculent dans le
                // même repère : le curseur reste sous le doigt).
                if (dropSliderGesture) {
                    view.preview.dropSliderDragTo(x, y);
                    lastTouchX = x;
                    lastTouchY = y;
                    return true;
                }
                // ★ B27-C3 : MOVE du CORPS interactif — transféré au hôte
                // capturé au DOWN, dans le repère de drawDropPreview ; le
                // premier franchissement du slop retire l'appui long (le
                // geste est un DRAG, plus une inspection). Le doigt sorti de
                // l'écran reste CLAMPÉ au rectangle ; une carte refermée en
                // vol de geste reçoit un CANCEL (le hôte n'attend jamais un
                // UP qui ne viendra jamais).
                if (dropBodyGesture) {
                    if (view.preview.drop().bodyTouchAt(x, y, dropBodyPoint)) {
                        dropBodySx = dropBodyPoint[0];
                        dropBodySy = dropBodyPoint[1];
                    } else {
                        clamperPointCorpsGoutte(x, y);
                    }
                    if (dropBodyGesture) {
                        if ((x - touchStartX) * (x - touchStartX)
                                + (y - touchStartY) * (y - touchStartY) > TAP_SLOP_SQ) {
                            retirerAppuiLongCorps();
                        }
                        transfererCorpsGoutte(MotionEvent.ACTION_MOVE);
                    }
                    lastTouchX = x;
                    lastTouchY = y;
                    return true;
                }
                // ★ B27 : la carte « goutte d'eau » engloutit son geste —
                // un drag commencé dans la carte ne scrolle ni ne
                // sélectionne le texte derrière elle (elle n'a pas de
                // geste de drag : le tap seul agit).
                if (dropCardGesture) return true;
                // B9 (retour terrain n°6) : après un pincement DANS CE
                // GESTE, les MOVE du doigt restant sont engloutis. Le
                // pincement écarte/rapproche les deux doigts de dizaines
                // de pixels : au relevé d'un doigt, le doigt restant est
                // loin du DOWN d'origine → le slop était déjà dépassé →
                // chaque MOVE résiduel déclenchait un SCROLL (et la
                // sélection d'un éventuel drag) — le viewport glissait
                // juste après le zoom et le caret « ne restait pas à une
                // position fixe » alors même que l'ancrage du pincement
                // avait fonctionné. Le geste est un pincement : ses
                // traînées ne sont pas des scrolls (parité TextView).
                if (wasPinching) return true;
                // Drag sur le quick doc : scroll du corps (clampé).
                if (quickDocGesture) {
                    float dy = y - lastTouchY;
                    if (dy != 0) {
                        float[] qd = view.quickDocMetrics();
                        if (qd != null && qd[4] > qd[3]) {
                            view.quickDocScrollY = Math.max(0,
                                    Math.min(view.quickDocScrollY - dy,
                                            qd[4] - qd[3]));
                            view.invalidate();
                        }
                    }
                    lastTouchY = y;
                    lastTouchX = x;
                    return true;
                }
                // Drag du sélecteur de couleur (B26) : mise à jour
                // vivante du champ/bande pressé au DOWN.
                if (colorPickerGesture) {
                    view.popupManager.colorPickerDragTo(x, y);
                    lastTouchY = y;
                    lastTouchX = x;
                    return true;
                }
                if (toolbarGesture) {
                    // Mouvement au-delà du slop → annule le press ; le geste
                    // reste englouti par la pill (pas de scroll derrière).
                    float tdx = x - touchStartX;
                    float tdy = y - touchStartY;
                    if (tdx * tdx + tdy * tdy > TAP_SLOP_SQ
                            && view.selectionToolbarPressedIdx >= 0) {
                        view.selectionToolbarPressedIdx = -1;
                        view.invalidate();
                    }
                    return true;
                }
                if (navMenuGesture) {
                    float dy = y - lastTouchY;
                    float tdy = y - touchStartY;
                    if (navMenuDragging || tdy * tdy
                            + (x - touchStartX) * (x - touchStartX) > TAP_SLOP_SQ) {
                        navMenuDragging = true;
                        view.navMenuPressedIdx = -1;
                        float contentH = view.navMenuContentHeight();
                        float[] nm = view.navMenuMetrics();
                        float maxScroll = nm != null
                                ? Math.max(0, contentH - nm[3]) : 0;
                        view.navMenuScrollY = EditorView.clamp(
                                view.navMenuScrollY - dy, 0, maxScroll);
                        view.invalidate();
                        lastTouchY = y;
                    }
                    return true;
                }
                if (completionScrolling) {
                    float dy = y - lastTouchY;
                    float density = view.getResources().getDisplayMetrics().density;
                    float rowH = CompletionState.ROW_HEIGHT_DP * density;
                    int rowDelta = -(int) (dy / rowH);
                    if (rowDelta != 0) {
                        // Bornes de scroll sur les rangées RÉELLEMENT
                        // visibles (réduites quand le viewport est petit) —
                        // invariant possédé par CompletionState.scrollBy.
                        if (view.completion.scrollBy(rowDelta,
                                view.popupManager.completionRowsVisible())) {
                            view.invalidate();
                        }
                        lastTouchY = y;
                    }
                    return true;
                }
                // ★ B13 (P1-2) : MOVE du drag de bloc — au-delà du
                // touch-slop le déplacement s'engage (loupe + caret
                // fantôme + carte) et l'auto-scroll de bord démarre
                // (mécanique UX-2 partagée avec le drag-select). Le
                // geste reste englouti par le bloc.
                if (blockDragGesture) {
                    float bdx = x - touchStartX;
                    float bdy = y - touchStartY;
                    if (view.blockDragCtl.isActive()
                            || bdx * bdx + bdy * bdy > TAP_SLOP_SQ) {
                        selectionGestures.noteBlockDrag(x, y);
                    }
                    return true;
                }
                if (view.handleDragMode > 0) {
                    // La poignée ne bouge
                    // qu'après dépassement du touch-slop depuis le DOWN —
                    // le jitter d'un appui sur la poignée ne déplace ni
                    // la sélection ni la loupe.
                    float hdx = x - touchStartX;
                    float hdy = y - touchStartY;
                    if (handleMoving
                            || hdx * hdx + hdy * hdy > TAP_SLOP_SQ) {
                        handleMoving = true;
                        selectionGestures.dragHandle(x, y);
                    }
                    return true;
                }
                // ★ B11 (P1-1) : drag sur la bande d'erreurs —
                // défilement ABSOLU (le pouce suit le doigt). Le geste est
                // englouti par la bande : pas de sélection de texte derrière.
                // Sous le slop : rien (le tap du marqueur se résout au UP).
                if (stripeGesture) {
                    float sdx = x - touchStartX;
                    float sdy = y - touchStartY;
                    if (view.fastScroller.isActive()
                            || sdx * sdx + sdy * sdy > TAP_SLOP_SQ) {
                        view.fastScroller.dragTo(y);
                    }
                    return true;
                }
                if (isScrolling) {
                    if (view.fastScroller.isArmed()) {
                        // ★ B11 (P0-3) : drag de gouttière = fast-scroller
                        // ABSOLU — le pouce (bord droit) suit la hauteur du
                        // doigt et le document suit en proportion. Le
                        // positionnement absolu remplace l'ancien scroll
                        // pixel-relatif (un écran par geste) : tout le
                        // document devient atteignable d'un seul geste,
                        // comme le drag de gouttière d'Android Studio.
                        // Activation au-delà du slop — un jitter de tap ne
                        // doit ni flasher le pouce ni défiler.
                        float tdx = x - touchStartX;
                        float tdy = y - touchStartY;
                        if (view.fastScroller.isActive()
                                || tdx * tdx + tdy * tdy > TAP_SLOP_SQ) {
                            view.fastScroller.dragTo(y);
                        }
                    } else {
                        // Drag hors gouttière : scroll relatif classique
                        // (fling au relâchement).
                        float dy = y - lastTouchY;
                        float dx = x - lastTouchX;
                        scrollerCtl.scrollByInternal(-dy, -dx);
                        lastTouchX = x;
                        lastTouchY = y;
                    }
                } else {
                    float totalDx = x - touchStartX;
                    float totalDy = y - touchStartY;
                    boolean beyondSlop =
                        totalDx * totalDx + totalDy * totalDy > TAP_SLOP_SQ;
                    if (beyondSlop
                            && (isDragging || selectionGestures.isDragSelectArmed())) {
                        // Drag-select au doigt, armé par l'APPUI LONG
                        // (armé par l'appui long) : le
                        // glissement au-delà du slop étend la sélection
                        // depuis le mot sélectionné par l'appui long. Le
                        // premier MOVE engage le geste, ce qui neutralise
                        // aussi la résolution du tap au UP (l'UP d'un
                        // drag ne doit pas écraser la sélection).
                        isDragging = true;
                        isScrolling = false;
                        selectionGestures.handleTouchDrag(event);
                    } else if (beyondSlop) {
                        isScrolling = true;
                        isDragging = false;
                        // Un swipe qui a dépassé le touch-slop est un
                        // scroll, jamais un tap — le tap-dismiss différé
                        // en attente meurt avec lui (
                        // !touchScrolled).
                        cancelPendingTapDismiss();
                        // Annule aussi le hover tap-and-hold —
                        // l'utilisateur a commencé à défiler, pas à
                        // survoler.
                        touchHover.cancel();
                    }
                    // ★ SOUS le touch-slop : RIEN. Ni tap ni drag — la
                    // séparation tap/drag passe par le slop.
                    // C'est ce qui corrige le bug « j'ai
                    // seulement appuyé et plusieurs lignes/mots se sont
                    // sélectionnés » : le jitter naturel du doigt sous
                    // le seuil ne crée plus jamais de sélection.
                    lastTouchX = x;
                    lastTouchY = y;
                }
                return true;
            case MotionEvent.ACTION_UP:
                // Désactive la loupe au relâchement tactile (M3 : la
                // native est refermée explicitement — fenêtrage).
                view.dismissMagnifier();
                // Annule tout timer de hover tap-and-hold en attente.
                // S'il a déjà déclenché (tapHoldHoverTriggered = true), le
                // quick doc est déjà ouvert — on laisse l'utilisateur le
                // fermer d'un tap séparé ailleurs.
                touchHover.cancel();
                view.invalidate();
                // Toute terminaison de geste désarme le drag-select
                // : l'armement ne survit JAMAIS
                // à un geste — seul un nouvel appui long le réarme.
                selectionGestures.disarmDragSelect();
                if (sheetGesture) {
                    // Fiche de diagnostic modale — résout le tap (applique
                    // une quick-fix, tape ×, ou ferme sur le scrim).
                    sheetGesture = false;
                    boolean wasTap = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    if (wasTap) tapResolver.handleTap(x, y);
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                // ★ B27 (retour terrain n°3) : fin du geste de la barre de
                // taille — le hôte est notifié du facteur choisi UNE fois
                // (persistance), le geste est englouti par la carte.
                if (dropSliderGesture) {
                    dropSliderGesture = false;
                    view.preview.dropSliderRelease();
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                // ★ B27-C3 : fin du geste du CORPS interactif — l'UP part
                // au hôte (le clic/ripple vit côté contenu), l'appui long
                // éventuel est retiré, le geste est consommé (aucun tap
                // résolu par la cascade : le corps n'est plus inerte).
                if (dropBodyGesture) {
                    dropBodyGesture = false;
                    retirerAppuiLongCorps();
                    transfererCorpsGoutte(MotionEvent.ACTION_UP);
                    dropBodyHost = null;
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                // ★ B27 : la carte « goutte d'eau » résout SON tap — la
                // cascade handleTap dispatche (onglet/✕/corps englouti) ;
                // un drag au-delà du slop ne fait rien (la carte se
                // replace seule à chaque frame, aucun geste à finir).
                if (dropCardGesture) {
                    dropCardGesture = false;
                    boolean wasDropTap = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    if (wasDropTap) tapResolver.handleTap(x, y);
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                if (completionScrolling) {
                    boolean wasTap = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    completionScrolling = false;
                    if (wasTap) {
                        tapResolver.handleTap(x, y);
                    }
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                if (toolbarGesture) {
                    // Résolution du geste toolbar — tap → action.
                    toolbarGesture = false;
                    boolean wasTap = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    view.selectionToolbarPressedIdx = -1;
                    // La pill engloutit SON geste —
                    // un tap sur un gap/divider ne fait rien (le Popup ne
                    // propage pas), le caret ne bouge pas et le clavier ne
                    // se lève pas. On appelle donc le resolver DIRECTEMENT
                    // au lieu de re-entrer dans la cascade handleTap.
                    if (wasTap) handleSelectionToolbarTap(x, y);
                    view.invalidate();
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                if (navMenuGesture) {
                    // Résolution du geste menu — tap → pick de la
                    // rangée ; drag → fin du scroll (rien d'autre). Le geste
                    // est englouti par la carte (Popup).
                    navMenuGesture = false;
                    boolean wasNavTap = !navMenuDragging
                            && ((x == touchStartX && y == touchStartY)
                                || ((x - touchStartX) * (x - touchStartX)
                                    + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ));
                    int pressed = view.navMenuPressedIdx;
                    view.navMenuPressedIdx = -1;
                    if (wasNavTap) hitTester.handleNavMenuTap(x, y, pressed);
                    view.invalidate();
                    navMenuDragging = false;
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                if (quickDocGesture) {
                    // Fin du geste quick doc — le tap simple ne fait
                    // rien (le popup reste ; drag = scroll du corps), le
                    // geste est englouti par la carte.
                    quickDocGesture = false;
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                // Fin du geste du sélecteur (B26) : tap palette →
                // application directe ; fin d'un press champ/bande →
                // application de la couleur en cours ; tap ailleurs
                // dans la carte → rien (englouti).
                if (colorPickerGesture) {
                    colorPickerGesture = false;
                    view.popupManager.colorPickerReleaseAt(x, y);
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                // ★ B11 (P1-1) : fin du geste de la bande d'erreurs —
                // tap-like → résolution du marqueur (centrer + fiche)
                // par la cascade handleTap ; drag → fin du pouce en
                // fondu (pas de fling : positionnement absolu). Le geste
                // est englouti par la bande, comme par la pill/le menu.
                // ★ B13 (P1-2) : UP du drag de bloc — dépose (une
                // étape d'undo, sélection ré-épinglée) si le drag était
                // engagé ; un TAP annule le mode (le document ne bouge
                // pas). Le geste est englouti : pas de tap/caret derrière.
                if (blockDragGesture) {
                    blockDragGesture = false;
                    view.blockDragCtl.drop();
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                if (stripeGesture) {
                    stripeGesture = false;
                    boolean wasStripeTap = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    view.fastScroller.end();
                    if (wasStripeTap) {
                        tapResolver.handleTap(x, y);
                    }
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                if (view.handleDragMode > 0) {
                    view.handleDragMode = 0;
                    handleMoving = false;
                    isScrolling = false;
                    isDragging = false;
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                if (wasPinching) {
                    wasPinching = false;
                    isScrolling = false;
                    isDragging = false;
                    scrollerCtl.recycleTracker();
                    view.performClick();
                    return true;
                }
                // wasDragging garde la trace d'un drag-select ENGAGÉ dans
                // CE geste : son UP ne doit pas résoudre un tap (qui
                // reposerait le caret et écraserait la sélection du
                // drag). Le tap lui-même ne nuit plus aucun état de
                // drag-select (seul l'appui long arme).
                boolean wasDragging = isDragging;
                isDragging = false;
                handleMoving = false;
                if (!isScrolling && !longPressTriggered && !wasDragging) {
                    tapResolver.handleTap(x, y);
                } else if (isScrolling) {
                    // Un relâchement sans mouvement dans le gutter des
                    // numéros de ligne est un TAP, pas un scroll — on le
                    // route vers handleTap pour que le point de diagnostic
                    // ouvre la fiche (tap sur glyphe de
                    // gutter → openSheet). Un vrai drag (mouvement au-delà
                    // du slop) continue de défiler/flinger comme avant.
                    boolean tapLike = (x == touchStartX && y == touchStartY)
                        || ((x - touchStartX) * (x - touchStartX)
                            + (y - touchStartY) * (y - touchStartY) < TAP_SLOP_SQ);
                    if (downInLineNumberArea && tapLike && !longPressTriggered) {
                        tapResolver.handleTap(x, y);
                    } else if (view.fastScroller.isActive()) {
                        // ★ B11 (P0-3) : fast-scroll relâché — le pouce
                        // disparaît en fondu ; PAS de fling (le geste est
                        // un positionnement absolu, un fling le casserait).
                        view.fastScroller.end();
                    } else {
                        scrollerCtl.flingFromTracker();
                    }
                }
                // ★ B11 (P0-3) : désarme le fast-scroller du geste de
                // gouttière (tap → jamais actif → aucune trace ; drag →
                // le fondu a déjà été amorcé ci-dessus).
                view.fastScroller.end();
                isScrolling = false;
                downInLineNumberArea = false;
                scrollerCtl.recycleTracker();
                view.performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                // ★ B27-C3 : le système annule AUSSI le geste du corps
                // interactif — le hôte reçoit son CANCEL (jamais d'UP
                // fantôme sur une annulation système).
                if (dropBodyGesture) {
                    dropBodyGesture = false;
                    retirerAppuiLongCorps();
                    transfererCorpsGoutte(MotionEvent.ACTION_CANCEL);
                    dropBodyHost = null;
                }
                view.handleDragMode = 0;
                isScrolling = false;
                isDragging = false;
                handleMoving = false;
                downInLineNumberArea = false;
                stripeGesture = false;
                // ★ B13 (P1-2) : le système annule le geste — le bloc
                // retombe proprement (document intact).
                view.blockDragCtl.cancel();
                blockDragGesture = false;
                // ★ B11 : annulation — le pouce du fast-scroller disparaît
                // immédiatement, sans fondu.
                view.fastScroller.cancel();
                selectionGestures.disarmDragSelect();
                sheetGesture = false; // annule aussi le geste modal
                toolbarGesture = false;
                view.selectionToolbarPressedIdx = -1;
                navMenuGesture = false;
                navMenuDragging = false;
                view.navMenuPressedIdx = -1;
                quickDocGesture = false;
                // B26 : le press du sélecteur est annulé (sans application).
                colorPickerGesture = false;
                view.colorPickerPressed = 0;
                cancelPendingTapDismiss();
                touchHover.cancel();
                // Désactive la loupe (M3 : native refermée explicitement).
                view.dismissMagnifier();
                view.invalidate();
                scrollerCtl.abortAnimation();
                scrollerCtl.recycleTracker();
                return true;
        }
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    // Scroll / fling
    // ════════════════════════════════════════════════════════════════

    public void computeScroll() {
        scrollerCtl.computeScroll();
    }

    boolean performClick() {
        view.performClick();
        return true;
    }

    // ════════════════════════════════════════════════════════════════
    // Mouvement générique (clic droit → menu contextuel)
    // ════════════════════════════════════════════════════════════════

    public boolean onGenericMotionEvent(MotionEvent event) {
        return contextMenu.onGenericMotionEvent(event);
    }

    public void showEditorContextMenu(float anchorX, float anchorY) {
        contextMenu.showEditorContextMenu(anchorX, anchorY);
    }

    // ════════════════════════════════════════════════════════════════
    // Relais vers les collaborateurs (appelés par EditorView et les tests)
    // ════════════════════════════════════════════════════════════════

    /** Relais du hit-test NavMenu (utilisé par EditorNavMenuTest). */
    public int navMenuRowIndexOf(float x, float y) {
        return hitTester.navMenuRowIndexOf(x, y);
    }

    public void showSelectionToolbar() {
        selectionGestures.showSelectionToolbar();
    }

    public void dismissSelectionToolbar() {
        selectionGestures.dismissSelectionToolbar();
    }

    public boolean handleSelectionToolbarTap(float x, float y) {
        return selectionGestures.handleSelectionToolbarTap(x, y);
    }

    // ── Accès d'état du dispatcher pour les collaborateurs ──

    /**
     * T4 (lot 2, J5) — offset d'ancrage de la pill de sélection : la
     * borne MOBILE pendant un drag (poignée ou drag-select au doigt),
     * {@code sel.end} au repos. Délègue à l'état du geste
     * ({@code EditorSelectionGestures.pillAnchorOffset}) — consommé par
     * {@code EditorPopupAnchors.selectionToolbarMetrics}.
     *
     * @return l'offset d'ancrage, ou -1 (repli sur {@code sel.end})
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public int pillAnchorOffset() {
        return selectionGestures.pillAnchorOffset();
    }

    /**
     * Marque l'appui long comme déclenché et arrête tout drag/scroll en
     * cours — état du dispatcher écrit par EditorSelectionGestures
     * (corps historique de handleLongPress, déplacé à l'identique).
     */
    void markLongPressTriggered() {
        longPressTriggered = true;
        isDragging = false;
        isScrolling = false;
        // ★ B11 : un appui long ne peut pas être un fast-scroll — le
        // pouce ne doit pas apparaître si le doigt glisse ensuite.
        view.fastScroller.cancel();
        stripeGesture = false;
    }

    /**
     * B7 (retour terrain n°4) — vrai si un popup visible <b>possède</b> le
     * point tactile : l'appui long doit alors s'abstenir de sélectionner
     * le mot derrière lui.
     *
     * <p>Pourquoi ce garde : le {@code GestureDetector} est alimenté AVANT
     * la cascade de hit-test des popups (il faut le flux complet pour la
     * vélocité du fling) — son minuteur d'appui long se déclenche donc
     * MÊME quand le geste appartient à un popup qui l'engloutit. Sans ce
     * garde, un appui long maintenu DANS une complétion (ou sous le scrim
     * modal d'une fiche de diagnostic) démarrait une sélection pour
     * drag, ouvrait le clavier et volait le focus pendant que le popup
     * croyait posséder le geste.</p>
     *
     * <p>Possession :</p>
     * <ul>
     *   <li>popups <b>modaux</b> (fiche de diagnostic, sheet de liste) :
     *       le scrim couvre l'écran — tout point leur appartient ;</li>
     *   <li>popups à région (complétion, quick doc, menu contextuel,
     *       toolbar de sélection, popup d'actions de code, popup de
     *       squiggle, aller-au-symbole) : le point DANS leurs bornes ;</li>
     *   <li>un point HORS des popups reste à l'éditeur — l'appui long y
     *       garde son comportement normal (sélection du mot, refermeture
     *       du popup au relâchement par le chemin du tap).</li>
     * </ul>
     */
    boolean popupOwnsTouch(float x, float y) {
        // Modaux : le scrim couvre tout l'éditeur.
        if (view.diagnosticPopupVisible || view.diagnosticListSheetLine >= 0) {
            return true;
        }
        // ★ B27 : la carte « goutte d'eau » possède les points qu'elle
        // recouvre — l'appui long derrière elle ne sélectionne pas le
        // mot caché (miroir des popups à hit-test déterministe).
        if (view.preview.hitTestDrop(x, y)) {
            return true;
        }
        if (view.completion.visible && (hitTester.hitTestCompletionPopup(x, y) >= 0
                // 4.4.0 : le panneau de documentation possède aussi ses
                // points — l'appui long derrière lui ne sélectionne pas.
                || hitTester.hitCompletionDocPanel(x, y))) {
            return true;
        }
        if (view.quickDocVisible) {
            float[] qd = view.quickDocMetrics();
            if (qd != null && x >= qd[0] && x <= qd[0] + qd[2]
                    && y >= qd[1] && y <= qd[1] + qd[3]) {
                return true;
            }
        }
        if (view.navMenuVisible) {
            float[] nm = view.navMenuMetrics();
            if (nm != null && x >= nm[0] && x <= nm[0] + nm[2]
                    && y >= nm[1] && y <= nm[1] + nm[3]) {
                return true;
            }
        }
        if (view.selectionToolbarVisible) {
            EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
            if (m != null && m.contains(x, y)) {
                return true;
            }
        }
        if (view.squigglePopupVisible && hitTester.hitSquigglePopup(x, y) >= 0) {
            return true;
        }
        if (view.codeActionsPopupVisible
                && hitTester.hitTestCodeActionsPopup(x, y) >= 0) {
            return true;
        }
        if (view.goToSymbolVisible && hitTester.hitTestGoToSymbolPopup(x, y) >= 0) {
            return true;
        }
        return false;
    }

    /** Position tactile courante du dispatcher (lue au vol par EditorTouchHoverController). */
    float currentTouchX() {
        return lastTouchX;
    }

    float currentTouchY() {
        return lastTouchY;
    }

    /** Vrai si un scroll ou un drag est en cours (lu par EditorTouchHoverController). */
    boolean isScrollingOrDragging() {
        return isScrolling || isDragging;
    }

    /**
     * B9 (retour terrain n°6) — vrai pendant qu'un drag de sélection
     * (poignée ou drag-select au doigt) possède le viewport : l'IME
     * bridge suspend alors le recentrage du caret (le combat
     * recentrage↔auto-scroll faisait « secouer » les lignes pendant le
     * drag). Relais vers {@code EditorSelectionGestures}.
     */
    public boolean isSelectionDragActive() {
        return selectionGestures.isAutoScrollDragActive();
    }

    /** Bascule l'appui long du détecteur de gestes (exclusivité hover/sélection). */
    void setLongPressEnabled(boolean enabled) {
        gestureDetector.setIsLongpressEnabled(enabled);
    }

    // ── B13 (P1-2) : relais du drag-déposer de bloc ──────────

    /** Arme le mode drag-déposer pour la sélection courante (le perform de la pill ✥). */
    public boolean armBlockDrag() {
        return view.blockDragCtl.arm();
    }

    /** Vrai si le mode drag-déposer est armé (le prochain geste appartient au bloc). */
    public boolean isBlockDragArmed() {
        return view.blockDragCtl.isArmed();
    }

    /** Vrai si un déplacement de bloc est ENGAGÉ (doigt au-delà du slop). */
    public boolean isBlockDragActive() {
        return view.blockDragCtl.isActive();
    }

    /** Annule le mode (hôte, détachement, changement de session). */
    public void cancelBlockDrag() {
        view.blockDragCtl.cancel();
    }

    // ── pendingTapDismiss ──

    /** Relais du tap-dismiss différé (utilisé par EditorView.onTextChanged). */
    public void cancelPendingTapDismiss() {
        tapResolver.cancelPendingTapDismiss();
    }

    // ════════════════════════════════════════════════════════════════
    // Classes internes — listeners de geste + scale
    // ════════════════════════════════════════════════════════════════

    private final class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            // ★ Pinch-zoom ancré. L'ancienne impl appliquait fontScale *
            // scaleFactor SANS compenser les offsets : le caret « sautait »
            // à une nouvelle position écran (les nouvelles metrics donnent
            // un nouveau lineHeight/charWidth → docLineToY(line) et
            // visualCol * charWidth changent, vOffset reste → le caret
            // bouge visuellement).
            //
            // applyPinchScale capture la position d'ANCRE AVANT le scale
            // (le caret s'il est visible — il reste visuellement fixe et
            // grandit/réduit avec le zoom ; sinon le POINT FOCAL du
            // pincement, le contenu sous le milieu des deux doigts),
            // applique le scale, puis ajuste vOffset/hOffset pour ramener
            // l'ancre à sa position écran d'origine.
            //
            // B9 (retour terrain n°6) : le point focal est passé au
            // contrôleur — sans lui, un caret hors écran (ou une session
            // en lecture seule) n'avait AUCUN ancrage et le contenu
            // sautait sous le pincement.
            view.applyPinchScale(detector.getScaleFactor(),
                    detector.getFocusX(), detector.getFocusY());
            return true;
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public void onLongPress(MotionEvent e) {
            selectionGestures.handleLongPress(e.getX(), e.getY());
        }
    }

    // ════════════════════════════════════════════════════════════════
    // B27-C3 : le corps interactif de la goutte d'eau
    // ════════════════════════════════════════════════════════════════

    /**
     * Le hôte courant S'IL est interactif ({@code null} sinon) — demandé
     * au DOWN seulement : le geste capture ensuite SA référence (un
     * changement d'hôte en vol de geste ne détourne pas les touches).
     */
    private EditorPreviewHost hoteInteractifGoutte() {
        EditorPreviewHost host = view.preview.getHost();
        if (host != null && host.wantsInteractiveDropPreview()) return host;
        return null;
    }

    /**
     * Transfère UNE action du geste du corps au hôte capturé (coords
     * écran du dernier point — repère {@code drawDropPreview}). Sans
     * hôte capturé, le transfert est un no-op honnête (le hôte a été
     * détaché : le geste meurt au prochain UP/CANCEL).
     */
    private void transfererCorpsGoutte(int action) {
        EditorPreviewHost host = dropBodyHost;
        if (host != null) host.onPreviewDropTouch(action, dropBodySx, dropBodySy);
    }

    /**
     * CLAMPE un point sorti de l'écran au rectangle écran (contact de
     * bord honnête, jamais un point hors cadre) ; carte refermée en vol
     * de geste : le hôte reçoit son CANCEL et le geste meurt (aucun UP
     * n'arrivera jamais — le hôte n'attend rien).
     */
    private void clamperPointCorpsGoutte(float x, float y) {
        EditorPreviewDropGeometry.Placement p = view.preview.drop().placement();
        if (p == null) {
            dropBodyGesture = false;
            retirerAppuiLongCorps();
            transfererCorpsGoutte(MotionEvent.ACTION_CANCEL);
            dropBodyHost = null;
            return;
        }
        android.graphics.RectF ecran = view.preview.drop().screenRect();
        float lx = Math.max(ecran.left, Math.min(ecran.right, p.toLocalX(x)));
        float ly = Math.max(ecran.top, Math.min(ecran.bottom, p.toLocalY(y)));
        dropBodySx = lx - ecran.left;
        dropBodySy = ly - ecran.top;
    }

    /**
     * Arme l'appui long du corps (délai système) : au déclenchement, le
     * geste est ANNULÉ côté hôte (CANCEL transféré) puis l'inspection
     * part au hôte — le hôte ne reçoit JAMAIS l'UP d'un geste devenu
     * long (le clic ne se déclenche pas derrière une inspection).
     */
    private void armerAppuiLongCorps() {
        retirerAppuiLongCorps();
        final float sx = dropBodySx;
        final float sy = dropBodySy;
        dropBodyLongPress = () -> {
            dropBodyLongPress = null;
            if (!dropBodyGesture) return;
            dropBodyGesture = false;
            transfererCorpsGoutte(MotionEvent.ACTION_CANCEL);
            EditorPreviewHost host = dropBodyHost;
            dropBodyHost = null;
            if (host != null) host.onPreviewDropLongPress(sx, sy);
        };
        if (corpsHandler == null) {
            corpsHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        corpsHandler.postDelayed(dropBodyLongPress, android.view.ViewConfiguration.getLongPressTimeout());
    }

    /** Retire l'appui long en attente (idempotent — UP/slop/CANCEL/nettoyage). */
    private void retirerAppuiLongCorps() {
        if (dropBodyLongPress != null) {
            if (corpsHandler == null) return;
            corpsHandler.removeCallbacks(dropBodyLongPress);
            dropBodyLongPress = null;
        }
    }
}
