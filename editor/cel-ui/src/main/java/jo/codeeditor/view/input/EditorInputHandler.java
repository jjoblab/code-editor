package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.EditorContextMenuHandler;
import jo.codeeditor.view.popup.EditorPopupAnchors;
import jo.codeeditor.view.popup.EditorPopupHitTester;

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
    // ── Drag-select (modèle sora) ──
    // Le drag-select au doigt est armé par l'APPUI LONG (via
    // EditorSelectionGestures.armDragSelect) — jamais par un tap. L'état
    // d'armement vit dans EditorSelectionGestures (isDragSelectArmed) ;
    // il est désarmé à toute terminaison de geste et à tout nouveau DOWN
    // (sora : finishDragSelect au DOWN). Un simple tap ne peut donc
    // JAMAIS créer de sélection, même avec le jitter naturel du doigt.
    /** Vrai dès qu'un drag de poignée a dépassé le touch-slop (parité
     *  sora selHandleMoving) — la poignée ne bouge pas avant. */
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

    // ── Tap gutter vs drag gutter ───────────────────────────
    // Un ACTION_DOWN dans la zone des numéros de ligne arme isScrolling
    // (pour qu'un DRAG VERTICAL sur le gutter fasse défiler le document),
    // mais ce même drapeau faisait qu'un TAP (sans mouvement) sautait
    // handleTap entièrement — le point de diagnostic ne pouvait jamais
    // ouvrir la fiche (« dot pas câblé »). Ce drapeau retient où le geste
    // a commencé pour que UP distingue un TAP gutter d'un drag gutter.
    private boolean downInLineNumberArea = false;

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
            wasPinching = true;
            isDragging = false;
            isScrolling = false;
            return true;
        }
        if (event.getPointerCount() > 1) {
            wasPinching = true;
            isDragging = false;
            isScrolling = false;
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
                if (view.completionVisible && hitTester.hitTestCompletionPopup(x, y) >= 0) {
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
                // HORS du popup le referme (parité Sora : tap ailleurs →
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
                // Tout nouveau DOWN désarme le drag-select (sora :
                // finishDragSelect) — l'état d'un geste précédent ne
                // peut jamais fuiter dans celui-ci.
                selectionGestures.disarmDragSelect();
                if (x < view.metrics.getGutterWidth()
                    && x < view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth()) {
                    isScrolling = true;
                    // Retient que le geste a commencé dans la zone des
                    // numéros de ligne — un relâchement SANS MOUVEMENT
                    // là-bas est un TAP (point de diagnostic → fiche), pas
                    // un scroll.
                    downInLineNumberArea = true;
                }
                scrollerCtl.abortAnimation();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (sheetGesture) return true; // scrim modal — englouti
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
                    float rowH = view.COMPLETION_ROW_HEIGHT_DP * density;
                    int rowDelta = -(int) (dy / rowH);
                    if (rowDelta != 0) {
                        // Bornes de scroll sur les rangées RÉELLEMENT
                        // visibles (réduites quand le viewport est petit).
                        int rowsVisible = view.popupManager.completionRowsVisible();
                        int newOffset = view.completionScrollOffset + rowDelta;
                        int maxOffset = Math.max(0, view.completionItems.size() - rowsVisible);
                        newOffset = Math.max(0, Math.min(newOffset, maxOffset));
                        if (newOffset != view.completionScrollOffset) {
                            view.completionScrollOffset = newOffset;
                            view.completionSelected = Math.max(0,
                                Math.min(view.completionSelected + rowDelta, view.completionItems.size() - 1));
                            view.invalidate();
                        }
                        lastTouchY = y;
                    }
                    return true;
                }
                if (view.handleDragMode > 0) {
                    // Parité sora (selHandleMoving) : la poignée ne bouge
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
                if (isScrolling) {
                    float dy = y - lastTouchY;
                    float dx = x - lastTouchX;
                    scrollerCtl.scrollByInternal(-dy, -dx);
                    lastTouchX = x;
                    lastTouchY = y;
                } else {
                    float totalDx = x - touchStartX;
                    float totalDy = y - touchStartY;
                    boolean beyondSlop =
                        totalDx * totalDx + totalDy * totalDy > TAP_SLOP_SQ;
                    if (beyondSlop
                            && (isDragging || selectionGestures.isDragSelectArmed())) {
                        // Drag-select au doigt, armé par l'APPUI LONG
                        // (modèle sora dragSelectAfterLongPress) : le
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
                    // séparation tap/drag passe par le slop (parité
                    // sora). C'est ce qui corrige le bug « j'ai
                    // seulement appuyé et plusieurs lignes/mots se sont
                    // sélectionnés » : le jitter naturel du doigt sous
                    // le seuil ne crée plus jamais de sélection.
                    lastTouchX = x;
                    lastTouchY = y;
                }
                return true;
            case MotionEvent.ACTION_UP:
                // Désactive la loupe au relâchement tactile.
                view.magnifierActive = false;
                // Annule tout timer de hover tap-and-hold en attente.
                // S'il a déjà déclenché (tapHoldHoverTriggered = true), le
                // quick doc est déjà ouvert — on laisse l'utilisateur le
                // fermer d'un tap séparé ailleurs.
                touchHover.cancel();
                view.invalidate();
                // Toute terminaison de geste désarme le drag-select
                // (sora finishDragSelect) : l'armement ne survit JAMAIS
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
                // drag-select (modèle sora : seul l'appui long arme).
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
                    } else {
                        scrollerCtl.flingFromTracker();
                    }
                }
                isScrolling = false;
                downInLineNumberArea = false;
                scrollerCtl.recycleTracker();
                view.performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                view.handleDragMode = 0;
                isScrolling = false;
                isDragging = false;
                handleMoving = false;
                downInLineNumberArea = false;
                selectionGestures.disarmDragSelect();
                sheetGesture = false; // annule aussi le geste modal
                toolbarGesture = false;
                view.selectionToolbarPressedIdx = -1;
                navMenuGesture = false;
                navMenuDragging = false;
                view.navMenuPressedIdx = -1;
                quickDocGesture = false;
                cancelPendingTapDismiss();
                touchHover.cancel();
                // Désactive la loupe.
                view.magnifierActive = false;
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
     * Marque l'appui long comme déclenché et arrête tout drag/scroll en
     * cours — état du dispatcher écrit par EditorSelectionGestures
     * (corps historique de handleLongPress, déplacé à l'identique).
     */
    void markLongPressTriggered() {
        longPressTriggered = true;
        isDragging = false;
        isScrolling = false;
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

    /** Bascule l'appui long du détecteur de gestes (exclusivité hover/sélection). */
    void setLongPressEnabled(boolean enabled) {
        gestureDetector.setIsLongpressEnabled(enabled);
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
            // ★ Pinch-zoom ancré sur le caret. L'ancienne impl
            // appliquait fontScale * scaleFactor SANS compenser les
            // offsets : le caret « sautait » à une nouvelle position écran
            // (les nouvelles metrics donnent un nouveau lineHeight/charWidth
            // → docLineToY(line) et visualCol * charWidth changent, vOffset
            // reste → le caret bouge visuellement).
            //
            // applyPinchScale capture la position caret AVANT le scale,
            // applique le scale, puis ajuste vOffset/hOffset pour ramener
            // le caret à sa position écran d'origine. Le caret reste fixe
            // visuellement et grandit/réduit avec le zoom.
            view.applyPinchScale(detector.getScaleFactor());
            return true;
        }
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public void onLongPress(MotionEvent e) {
            selectionGestures.handleLongPress(e.getX(), e.getY());
        }
    }
}
