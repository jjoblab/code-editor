package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.EditorPopupAnchors;

import androidx.annotation.RestrictTo;

import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

import jo.codeeditor.document.Selection;
import jo.codeeditor.shift.DiagnosticShift;

/**
 * Gestes de sélection d'EditorView, extraits d'EditorInputHandler : appui
 * long (sélection de mot + toolbar + quick doc), drag-select au doigt,
 * traînée des poignées de sélection avec loupe, test de touche des poignées
 * et résolution des actions de la toolbar flottante
 * (Copy/Cut/Paste/Select all/Docs/Actions).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorSelectionGestures {

    private final EditorView view;
    private final EditorInputHandler input;

    /** Ancre du drag de poignée, FIGÉE au DOWN : la borne opposée à la
     *  poignée attrapée. Relire la sélection à chaque MOVE faisait sauter
     *  l'ancre dès que le doigt franchissait l'autre poignée (la sélection
     *  se renormalisait puis se réduisait à une mini-plage suivant le
     *  doigt au MOVE suivant). */
    private int handleDragAnchor = -1;

    /** T4 (lot 2, J5) — borne MOBILE du drag-select au doigt (celle du
     *  doigt, mémorisée à chaque application de la sélection) : l'ancre
     *  de la pill suit le geste au lieu de rester figée sur la borne
     *  droite. Remise à NO_DRAG_SELECT par {@link #disarmDragSelect()}. */
    private int dragSelectMobileEdge = NO_DRAG_SELECT;

    // ── Drag-select au doigt, armé par l'APPUI LONG ──
    // Modèle d'ancrage (beginDragSelect/handleDragSelect, armé
    // après l'appui long) :
    // le drag-select n'est PLUS armé par un simple tap — le jitter
    // naturel du doigt sous le seuil du touch-slop déclenchait une
    // sélection depuis le caret posé par le tap précédent (« plusieurs
    // lignes ou mots sélectionnés alors que j'ai seulement appuyé »).
    // Il est armé par l'appui long, qui a déjà sélectionné le mot : le
    // glissement étend alors la sélection depuis la borne du mot
    // OPPOSÉE au doigt (ancre = borne droite si doigt < point
    // d'appui, left sinon). Toute terminaison de geste (UP/CANCEL) et
    // tout nouveau DOWN désarment (désarmement explicite au DOWN).
    private static final int NO_DRAG_SELECT = -1;
    /** Offset du point d'appui long (l'offset du caractère sous le doigt). */
    private int dragSelectPressOffset = NO_DRAG_SELECT;
    /** Borne GAUCHE de la sélection au moment de l'appui long. */
    private int dragSelectLeft = NO_DRAG_SELECT;
    /** Borne DROITE de la sélection au moment de l'appui long. */
    private int dragSelectRight = NO_DRAG_SELECT;

    // ── UX-2 (lot 2, J3) : auto-scroll directionnel du drag ──
    // Retour terrain : pendant un drag de sélection (poignée ou
    // drag-select au doigt), franchir le bord du viewport ne déclenche
    // AUCUN défilement — il faut lâcher, reprendre, recommencer. Le
    // standard mobile (TextView, Android Studio) : le doigt tenu dans
    // une zone de bord fait défiler le document dans la direction de
    // la pénétration, la sélection suit le contenu sous le doigt.
    /** Hauteur de la zone de bord déclenchant l'auto-scroll (dp). */
    private static final float EDGE_ZONE_DP = 56f;
    /** Vitesse d'auto-scroll minimale (dp par tick ~16 ms). */
    private static final float EDGE_MIN_SPEED_DP = 2f;
    /** Vitesse d'auto-scroll maximale (dp par tick, pénétration totale). */
    private static final float EDGE_MAX_SPEED_DP = 12f;
    /** Période du tick d'auto-scroll (ms, ~une frame). */
    private static final int AUTO_SCROLL_TICK_MS = 16;
    /** Dernière position du doigt pendant un drag (memo du tick). */
    private float lastDragX, lastDragY;
    /** Vrai dès que le drag-select au doigt est ENGAGÉ (au-delà du slop) —
     *  l'auto-scroll ne démarre jamais tant que le doigt n'a pas bougé. */
    private boolean dragSelectEngaged = false;
    /** Vrai pendant que le tick d'auto-scroll est posté (anti-doublon). */
    private boolean autoScrollRunning = false;
    /** Tick d'auto-scroll : défile selon la pénétration du bord et
     *  ré-applique la sélection sous le doigt, puis se RE-poste tant
     *  que le doigt reste dans la zone et que le document peut défiler.
     *  B7 (retour terrain n°4) : la pénétration se mesure sur les DEUX
     *  axes — bords haut/bas (vertical) ET gauche/droite (horizontal,
     *  sans retour à la ligne uniquement — en wrap il n'y a rien à
     *  défiler horizontalement). Le doigt peut tenir un COIN : les deux
     *  axes défilent ensemble. */
    private final Runnable autoScrollTick = new Runnable() {
        @Override
        public void run() {
            autoScrollRunning = false;
            if (!isAutoScrollDragActive()) return;
            float density = view.getResources().getDisplayMetrics().density;
            float edge = EDGE_ZONE_DP * density;
            float viewW = view.getWidth();
            float viewH = view.getHeight();
            if (viewH <= 0 || viewW <= 0) return;

            // ── Pénétration verticale (bords haut/bas). ──
            float penY = 0f;
            if (lastDragY < edge) {
                penY = (edge - lastDragY) / edge;
            } else if (lastDragY > viewH - edge) {
                penY = (lastDragY - (viewH - edge)) / edge;
            }
            if (penY > 1f) penY = 1f;

            // ── Pénétration horizontale (bords gauche/droite) —
            //    uniquement hors wrap : en mode retour à la ligne, le
            //    document n'a AUCUN défilement horizontal (maxH() = 0,
            //    scrollHorizontallyBy serait un no-op chronométré). ──
            float penX = 0f;
            if (!view.wordWrap) {
                if (lastDragX < edge) {
                    penX = (edge - lastDragX) / edge;
                } else if (lastDragX > viewW - edge) {
                    penX = (lastDragX - (viewW - edge)) / edge;
                }
                if (penX > 1f) penX = 1f;
            }

            if (penY <= 0f && penX <= 0f) return; // hors zone — le prochain MOVE ré-armera

            // Vitesse de chaque axe selon SA pénétration (le coin
            // défile en diagonale, chaque axe à son rythme).
            float speedY = (EDGE_MIN_SPEED_DP
                    + (EDGE_MAX_SPEED_DP - EDGE_MIN_SPEED_DP) * penY) * density;
            float speedX = (EDGE_MIN_SPEED_DP
                    + (EDGE_MAX_SPEED_DP - EDGE_MIN_SPEED_DP) * penX) * density;

            float beforeV = view.vOffset;
            float beforeH = view.hOffset;
            if (penY > 0f) {
                view.scrollManager.scrollBy(lastDragY < edge ? -speedY : speedY);
            }
            if (penX > 0f) {
                view.scrollManager.scrollHorizontallyBy(lastDragX < edge ? -speedX : speedX);
            }
            if (view.vOffset == beforeV && view.hOffset == beforeH) {
                return; // borné sur les DEUX axes (début/fin/gauche/droite) — stop
            }
            // Le contenu a défilé sous le doigt : ré-applique la
            // sélection à la même position écran (offsetAt rend la
            // nouvelle position sous le doigt).
            if (view.handleDragMode > 0) {
                dragHandle(lastDragX, lastDragY);
            } else if (view.blockDragCtl.isActive()) {
                // ★ B13 : le drag de bloc possède le geste — le point de
                // dépôt suit le contenu défilé (caret fantôme + loupe).
                view.blockDragCtl.dragTo(lastDragX, lastDragY);
            } else {
                applyDragSelect(lastDragX, lastDragY);
            }
            postAutoScrollTick();
        }
    };

    EditorSelectionGestures(EditorView view, EditorInputHandler input) {
        this.view = view;
        this.input = input;
    }

    /** À appeler au DOWN sur une poignée (juste après hitTestHandle) :
     *  fige l'ancre du drag pour tous les MOVE à venir du geste. */
    void beginHandleDrag(int handleMode) {
        if (view.session == null) { handleDragAnchor = -1; return; }
        Selection sel = view.session.getSelection();
        handleDragAnchor = handleMode == 1 ? sel.end : sel.start;
    }

    void handleLongPress(float x, float y) {
        // ★ B13 (P1-2) : le drag-déposer de bloc est ARMÉ — le geste
        // appartient au bloc (son doigt peut rester immobile 500 ms
        // avant de glisser) : ni sélection de mot, ni pill, ni
        // clavier. L'appui long redevient vivant dès que le mode
        // retombe (dépôt, tap d'annulation, cancel).
        if (view.blockDragCtl.isArmed()) {
            return;
        }
        // B7 (retour terrain n°4) : un popup qui possède le point (région
        // propre ou scrim modal) garde son appui long — l'appui long
        // « sélection pour drag » ne démarre PAS derrière un popup
        // affiché. Le garde doit venir AVANT tout effet (marquage,
        // sélection du mot, clavier) : au relâchement, le tap du popup
        // suit son chemin habituel (choix de rangée, dismiss…).
        if (input.popupOwnsTouch(x, y)) {
            return;
        }
        // État du dispatcher (longPressTriggered, fin de drag/scroll) via
        // l'orchestrateur — corps historique déplacé à l'identique.
        input.markLongPressTriggered();
        // I2 (lot 2, J3) : un appui long sur un hint tissé ouvre son
        // tooltip (quick doc) AVANT la sélection du mot — le hint est du
        // texte fantôme, pas du code ; l'appui long « documentation »
        // (survol des hints, parité IntelliJ) a la priorité sur l'appui
        // long « sélection ». Hors hint (ou wrap, où le tissage n'est pas
        // cartographié) : comportement historique inchangé.
        if (handleInlayHintLongPress(x, y)) return;
        // D3 (lot 2, J4) : un appui long sur un squiggle COPIE le
        // message du diagnostic au presse-papiers (retour terrain :
        // coller le message exact ailleurs — rapport de bug, ticket).
        // Même sémantique de hit que le popup D1 (cœur de plage, bords
        // ±1 exclus, voile Unnecessary non tappable) ; priorité sur
        // l'appui long « sélection » sur la plage soulignée. Zéro
        // chrome : le message part silencieusement dans le clipboard.
        if (handleSquiggleLongPressCopy(x, y)) return;
        int offset = view.offsetAt(x, y);
        view.session.selectWordAt(offset);
        view.handlesVisible = true;
        // ★ Arme le drag-select (armé par l'appui long) :
        // le mot vient d'être sélectionné — un glissement ultérieur du
        // doigt étendra la sélection depuis la borne du mot opposée au
        // doigt. C'est l'APPUI LONG qui arme, jamais le tap.
        armDragSelect(offset);
        showSelectionToolbar();
        if (view.quickDocResolver != null) {
            view.showQuickDoc(offset);
        }
        if (x >= view.metrics.getGutterWidth()) {
            view.wantsKeyboard = true;
            view.requestFocus();
            InputMethodManager imm = view.imm();
            if (imm != null) imm.showSoftInput(view, 0);
        }
        view.invalidate();
    }

    /** À appeler à l'armement du drag-select (appui long, juste après
     *  selectWordAt) : fige les trois indices du drag-select — offset du
     *  point d'appui et bornes gauche/droite de la sélection courante
     *  (le mot sélectionné par l'appui long). */
    void armDragSelect(int pressOffset) {
        if (view.session == null) {
            disarmDragSelect();
            return;
        }
        Selection sel = view.session.getSelection();
        dragSelectPressOffset = pressOffset;
        dragSelectLeft = Math.min(sel.start, sel.end);
        dragSelectRight = Math.max(sel.start, sel.end);
    }

    /** Désarme le drag-select : toute terminaison
     *  de geste (UP/CANCEL), tout nouveau DOWN et tout changement de
     *  session l'appellent — le drag-select ne survit JAMAIS à un geste.
     *  UX-2 : désarme aussi l'engagement du drag (le tick d'auto-scroll
     *  s'arrête de lui-même au prochain tour). */
    void disarmDragSelect() {
        dragSelectPressOffset = NO_DRAG_SELECT;
        dragSelectLeft = NO_DRAG_SELECT;
        dragSelectRight = NO_DRAG_SELECT;
        dragSelectEngaged = false;
        dragSelectMobileEdge = NO_DRAG_SELECT;
    }

    /** Vrai si le drag-select est armé (appui long déclenché, pas encore
     *  consommé). Lu par l'orchestrateur pour décider si un MOVE au-delà
     *  du slop engage le drag-select ou le scroll. */
    boolean isDragSelectArmed() {
        return dragSelectPressOffset != NO_DRAG_SELECT;
    }

    /**
     * Glissement de drag-select (armé par l'appui long). Règle d'ancre
     * du drag-select : la borne du MOT opposée au doigt reste l'ancre — doigt à
     * gauche du point d'appui → ancre = borne droite, doigt à droite →
     * ancre = borne gauche. La sélection contient toujours le mot
     * d'origine et suit le doigt. La loupe s'affiche pendant le
     * glissement.
     */
    void handleTouchDrag(MotionEvent event) {
        // UX-2 : mémorise la position et démarre l'auto-scroll de bord.
        lastDragX = event.getX();
        lastDragY = event.getY();
        dragSelectEngaged = true;
        ensureAutoScrollTick();
        applyDragSelect(event.getX(), event.getY());
    }

    /**
     * B9 (retour terrain n°6) — alimente la loupe d'une position
     * STABILISÉE : Y snappé au centre de la RANGÉE sous le doigt (la
     * rangée portant l'offset que le doigt règle — son contenu ne
     * tremble JAMAIS verticalement, quel que soit le jitter du capteur
     * dans la rangée ; changer de rangée fait un PAS net d'une ligne,
     * pas une oscillation), X amorti exponentiellement (α = 0,55 par
     * frame : le tremblement haute fréquence du doigt est filtré, le
     * suivi reste réactif — constante de temps ≈ 2 frames à 60 Hz).
     *
     * <p>C'est la sémantique des loupes plateforme (android.widget.
     * Magnifier aligne son contenu sur la grille de lignes du texte) :
     * la bulle agrandit ce qu'on RÈGLE, pas le tremblement du doigt.
     * Avant, la bulle suivait le doigt BRUT — zoom ×2, chaque pixel de
     * jitter devenait 2 px de « secousse » des lignes dans la bulle.</p>
     *
     * <p>Y via le couple offsetAt→caretScreenPos : la rangée EXACTTE
     * (consciente du wrap : la sous-rangée, pas la ligne document) qui
     * porte l'offset sous le doigt.</p>
     */
    private void updateMagnifier(float x, float y) {
        float targetY = y;
        if (view.session != null) {
            int offset = view.offsetAt(x, y);
            float[] row = view.caretScreenPos(offset);
            targetY = row[1] + view.metrics.getLineHeight() * 0.5f;
        }
        view.magnifierX = view.magnifierActive
                ? view.magnifierX + (x - view.magnifierX) * 0.55f
                : x;
        view.magnifierY = targetY;
        view.magnifierActive = true;
    }

    /** Corps du drag-select (partagé avec le tick d'auto-scroll UX-2 :
     *  ré-appliquer la sélection après un défilement, sans MOVE). Règle
     *  d'ancre : la borne du MOT opposée au doigt reste l'ancre — doigt à
     *  gauche du point d'appui → ancre = borne droite, doigt à droite →
     *  ancre = borne gauche. La sélection contient toujours le mot
     *  d'origine et suit le doigt. */
    private void applyDragSelect(float x, float y) {
        int offset = view.offsetAt(x, y);
        int anchor;
        if (dragSelectPressOffset != NO_DRAG_SELECT
                && dragSelectLeft != NO_DRAG_SELECT
                && dragSelectRight != NO_DRAG_SELECT) {
            anchor = offset <= dragSelectPressOffset
                    ? dragSelectRight : dragSelectLeft;
        } else {
            // Chemin défensif (armement manquant) : ancre = borne courante
            // la plus éloignée, jamais une relecture par MOVE.
            Selection sel = view.session.getSelection();
            anchor = offset <= (sel.start + sel.end) / 2
                    ? Math.max(sel.start, sel.end) : Math.min(sel.start, sel.end);
        }
        view.session.setSelection(Selection.range(
            Math.min(anchor, offset), Math.max(anchor, offset)));
        // T4 : mémorise la borne du DOIGT pour l'ancrage de la pill.
        dragSelectMobileEdge = offset < anchor
                ? Math.min(anchor, offset) : Math.max(anchor, offset);
        updateMagnifier(x, y);
        view.invalidate();
    }

    void dragHandle(float x, float y) {
        // La loupe suit le doigt pendant qu'une poignée de sélection est
        // traînée (le pipeline drawMagnifier dormant ré-armé). Activation
        // au premier MOVE — pas au DOWN — pour qu'un TAP rapide sur la
        // poignée ne fasse jamais flasher la bulle ; UP/CANCEL la
        // désactivent déjà. L'ancre est FIGÉE au DOWN (beginHandleDrag) :
        // chaque MOVE étend ou rétrécit la sélection entre l'ancre et le
        // doigt, y compris quand le doigt franchit l'autre poignée.
        // UX-2 : mémorise la position et démarre l'auto-scroll de bord.
        lastDragX = x;
        lastDragY = y;
        ensureAutoScrollTick();
        // B9 : la loupe suit la position STABILISÉE (Y snappé au centre
        // de la rangée réglée, X amorti) — cf. updateMagnifier.
        updateMagnifier(x, y);
        int offset = view.offsetAt(x, y);
        switch (view.handleDragMode) {
            case 1: {
                int anchor = handleAnchorFor(1);
                view.session.setSelection(Selection.range(
                    Math.min(anchor, offset), Math.max(anchor, offset)));
                break;
            }
            case 2: {
                int anchor = handleAnchorFor(2);
                view.session.setSelection(Selection.range(
                    Math.min(anchor, offset), Math.max(anchor, offset)));
                break;
            }
            case 3:
                view.session.setSelection(offset);
                break;
        }
        view.invalidate();
    }

    /** Ancre du drag de poignée, avec défense : si le DOWN n'a jamais figé
     *  d'ancre (chemin inattendu), fige-la maintenant depuis la sélection
     * courante plutôt que de relire min(start,end) à chaque MOVE. */
    private int handleAnchorFor(int handleMode) {
        if (handleDragAnchor < 0) {
            Selection sel = view.session.getSelection();
            handleDragAnchor = handleMode == 1 ? sel.end : sel.start;
        }
        return handleDragAnchor;
    }

    /**
     * T4 (lot 2, J5) — offset de la borne d'ancrage de la pill de
     * sélection : la borne <b>en déplacement</b> pendant un drag, la
     * borne droite au repos.
     *
     * <p>Avant T4, la pill suivait TOUJOURS {@code sel.end} (la borne
     * droite — la sélection est normalisée) : tirer la poignée GAUCHE
     * envoyait la pill à l'opposé du doigt (« je tire la poignée
     * gauche, la pill part à droite »). Règle depuis T4 :</p>
     * <ul>
     *   <li>drag de poignée (mode 1/2) : la borne MOBILE est celle qui
     *       n'est PAS l'ancre figée du DOWN — elle suit le doigt, y
     *       compris après renormalisation quand le doigt franchit
     *       l'autre poignée ;</li>
     *   <li>poignée de caret repliée (mode 3) : le caret lui-même ;</li>
     *   <li>drag-select au doigt : la borne du doigt
     *       ({@code dragSelectMobileEdge}, mémorisée à l'application) ;</li>
     *   <li>au repos : {@code sel.end} (comportement historique).</li>
     * </ul>
     *
     * @return l'offset d'ancrage, ou -1 (repli sur {@code sel.end})
     */
    int pillAnchorOffset() {
        if (view.session == null) return -1;
        Selection sel = view.session.getSelection();
        if (view.handleDragMode == 1 || view.handleDragMode == 2) {
            return handleDragAnchor == sel.start ? sel.end : sel.start;
        }
        if (view.handleDragMode == 3) return sel.end; // caret replié
        if (dragSelectMobileEdge != NO_DRAG_SELECT) {
            // Défensif : borne mémorisée toujours dans [start, end].
            return Math.max(Math.min(dragSelectMobileEdge, sel.end), sel.start);
        }
        return sel.end;
    }

    int hitTestHandle(float x, float y) {
        if (!view.handlesVisible) return 0;
        Selection sel = view.session.getSelection();
        float density = view.getResources().getDisplayMetrics().density;
        float tapR = view.HANDLE_TAP_RADIUS_DP * density;
        if (sel.isCursor()) {
            float[] pos = view.caretScreenPos(sel.start);
            float cx = pos[0];
            float cy = pos[1] + view.metrics.getLineHeight() + view.HANDLE_RADIUS_DP * density * 0.6f;
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) < tapR * tapR) return 3;
        } else {
            float[] posA = view.caretScreenPos(sel.start);
            float cax = posA[0];
            float cay = posA[1] + view.metrics.getLineHeight() + view.HANDLE_RADIUS_DP * density * 0.6f;
            if ((x - cax) * (x - cax) + (y - cay) * (y - cay) < tapR * tapR) return 1;
            float[] posB = view.caretScreenPos(sel.end);
            float cbx = posB[0];
            float cby = posB[1] + view.metrics.getLineHeight() + view.HANDLE_RADIUS_DP * density * 0.6f;
            if ((x - cbx) * (x - cbx) + (y - cby) * (y - cby) < tapR * tapR) return 2;
        }
        return 0;
    }

    void showSelectionToolbar() {
        view.selectionToolbarVisible = true;
        // Horodate le show — l'animation d'entrée (entrancePop +
        // cascade par item dans drawSelectionToolbar) la consomme. Sans
        // ça, l'animation ne jouait jamais (shownAt restait à 0).
        view.selectionToolbarShownAt = android.os.SystemClock.uptimeMillis();
        view.selectionToolbarPressedIdx = -1;
        view.invalidate();
    }

    void dismissSelectionToolbar() {
        // B7 : photographie la pill AVANT de nettoyer son état — le
        // fantôme s'efface en 110 ms (fondus d'apparition/disparition).
        view.popupFade.snuff(jo.codeeditor.view.popup.EditorPopupFade.Kind.SELECTION_TOOLBAR);
        view.selectionToolbarVisible = false;
        view.selectionToolbarPressedIdx = -1;
        view.invalidate();
    }

    /**
     * Résout le tap sur la pill via les MÉTRIQUES PARTAGÉES
     * ({@link EditorView#selectionToolbarMetrics()}), plus aucune
     * duplication du layout. Les 6 actions canoniques sont câblées :
     * Copy/Cut/Paste/Select all + Docs ℹ / Actions ⋯ (le hit-test
     * recalculait auparavant un layout 4-boutons obsolète — les icônes
     * étaient inatteignables et la pill collapsed rejetait tout tap).
     *
     * <p>Comportement de la toolbar de sélection : Copy/Cut/Paste
     * referment la pill (et masquent les poignées), Select all la laisse
     * ouverte (Copy/Cut deviennent disponibles), Docs ouvre le quick-doc,
     * Actions « ⋯ » ouvre le menu contextuel unifié (GO TO / QUICK FIXES /
     * INTENTIONS + section MORE des actions injectées non affichées).</p>
     *
     * <p>T1 (lot 2) : les items INJECTÉS par l'hôte exécutent leur
     * {@code perform} puis referment le chrome (contrat Copy/Cut/Paste)
     * ; une action désactivée REJETTE le tap sans refermer.</p>
     */
    boolean handleSelectionToolbarTap(float x, float y) {
        if (!view.selectionToolbarVisible || view.session == null) return false;
        EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        if (m == null) return false;
        int idx = m.itemIndexAt(x, y);
        if (idx < 0) return false;
        int act = m.action[idx];

        Selection sel = view.session.getSelection();
        switch (act) {
            case EditorView.SEL_ACT_COPY:
                view.copy();
                hideSelectionChrome();
                break;
            case EditorView.SEL_ACT_CUT:
                view.cut();
                hideSelectionChrome();
                break;
            case EditorView.SEL_ACT_PASTE:
                view.paste();
                hideSelectionChrome();
                break;
            case EditorView.SEL_ACT_SELECT_ALL:
                // La pill RESTE ouverte après Select all —
                // Copy/Cut deviennent disponibles sur la sélection totale.
                view.session.selectAll();
                view.invalidate();
                break;
            case EditorView.SEL_ACT_DOCS:
                hideSelectionChrome();
                // ★ showQuickDoc() passe selection.START
                // (CodeEditor.kt l.306-312) — le mot sous le début de la
                // sélection se résout plus sûrement que sous sa fin.
                view.showQuickDoc(sel.start);
                break;
            case EditorView.SEL_ACT_ACTIONS:
                // UX-1 (lot 2, J3 — retour terrain) : l'icône « ⋯ » en fin
                // de pill est l'UNIQUE trois-points (l'item de
                // débordement T1 est retiré — deux « ... » se
                // ressemblaient trop). Elle ouvre le menu UNIFIÉ, qui
                // liste AUSSI la section « MORE » (actions injectées non
                // affichées — la métrique vient d'être rafraîchie
                // ci-dessus, source unique). Le menu s'ouvre AVANT le
                // refermeture du chrome pour que showNavMenu voie la
                // pill encore visible (garde anti-périmé).
                view.showNavMenu(view.session.getDocument()
                        .lineForOffset(sel.start), sel.start);
                hideSelectionChrome();
                break;
            case jo.codeeditor.view.EditorSelectionActions.SEL_ACT_INJECTED: {
                // T1 : action injectée par l'hôte — désactivée = tap
                // rejeté SANS refermer la pill.
                jo.codeeditor.view.SelectionAction sa = m.injected[idx];
                if (sa == null || !sa.isEnabled()) return true;
                hideSelectionChrome();
                if (sa.perform != null) sa.perform.run();
                break;
            }
            case jo.codeeditor.view.EditorSelectionActions.SEL_ACT_OVERFLOW:
                // UX-1 : l'item ⋯ n'existe plus sur la pill — branche
                // conservée par contrat (ouvre le mode MORE seul).
                hideSelectionChrome();
                view.popupManager.showSelectionOverflow(
                        view.session.getDocument().lineForOffset(sel.start), sel.start);
                break;
            default:
                return false;
        }
        return true;
    }

    /** Referme la pill + masque les poignées. */
    private void hideSelectionChrome() {
        view.handlesVisible = false;
        dismissSelectionToolbar();
    }

    // ── UX-2 (J3) : plomberie du tick d'auto-scroll ─────────────────

    /**
     * B9 (retour terrain n°6) — vrai pendant qu'un drag de sélection
     * (poignée traînée, drag-select au doigt OU drag de bloc B13)
     * possède le viewport. Consulté par l'IME bridge pour SUSPENDRE le
     * recentrage du caret pendant le geste : chaque application de
     * sélection déclenchait {@code scrollCaretIntoView}, qui combattait
     * l'auto-scroll UX-2 du drag — l'un déflait, l'autre recentrait, au
     * rythme des MOVE — c'était la cause racine des lignes qui
     * « secouaient » pendant le drag de sélection.
     *
     * <p>Le drag et SON auto-scroll dirigent seuls le viewport ; le
     * recentrage reprend au geste suivant (frappe, déplacement clavier,
     * tap) via les chemins habituels.</p>
     */
    boolean isAutoScrollDragActive() {
        return view.session != null
                && (view.handleDragMode > 0
                    || view.blockDragCtl.isActive()
                    || (dragSelectEngaged && isDragSelectArmed()));
    }

    /**
     * B13 (P1-2) — MOVE du drag de bloc : mémorise la position du doigt,
     * démarre l'auto-scroll de bord (mécanique UX-2 PARTAGÉE avec le
     * drag-select : mêmes zones de bord, mêmes vitesses, les deux axes)
     * puis pousse la position au contrôleur (caret fantôme + loupe
     * stabilisée).
     */
    void noteBlockDrag(float x, float y) {
        lastDragX = x;
        lastDragY = y;
        ensureAutoScrollTick();
        view.blockDragCtl.dragTo(x, y);
    }

    /** Poste le tick d'auto-scroll (une seule instance en vol) sur le
     *  handler du looper principal — même paresse que le tap-dismiss
     *  différé : resté testable sous l'horloge fantôme de Robolectric
     *  (view.getHandler() est null tant que la vue n'est pas attachée). */
    private void ensureAutoScrollTick() {
        if (autoScrollRunning) return;
        if (input.tapHandler == null) {
            input.tapHandler = new android.os.Handler(
                    android.os.Looper.getMainLooper());
        }
        autoScrollRunning = true;
        input.tapHandler.postDelayed(autoScrollTick, AUTO_SCROLL_TICK_MS);
    }

    /** Re-post du tick par le tick lui-même (en vol). */
    private void postAutoScrollTick() {
        if (autoScrollRunning) return;
        autoScrollRunning = true;
        input.tapHandler.postDelayed(autoScrollTick, AUTO_SCROLL_TICK_MS);
    }

    // ── I2 (lot 2, J3) : tooltip des inlay hints au long-press ──────

    /**
     * Appui long sur un hint tissé : ouvre le quick doc portant le
     * tooltip du hint — direct si le serveur l'a servi avec le hint,
     * sinon résolu paresseusement via {@code view.inlayTooltipResolver}
     * (voie « features », une seule requête). Retourne true si le geste
     * est CONSOMMÉ (le hint a été touché) ; false → appui long de
     * sélection classique.
     *
     * <p>Hit-test : la plage tissée du hint est connue du layout de
     * ligne ({@code layoutForLine} → rawToVisual + pièce) — colonne
     * visuelle du doigt DANS [début du hint, début + longueur du
     * texte). En mode wrap le tissage n'est pas cartographié par
     * colonnes : repli sur l'appui long classique.</p>
     */
    private boolean handleInlayHintLongPress(float x, float y) {
        InlayHit hit = inlayHitAt(x, y);
        if (hit == null) return false;
        DiagnosticShift.InlayHint hint = hit.hint;
        // I3 (J6) : tooltip de la PART sous le doigt en priorité — un
        // hint structuré peut lier « le nom du paramètre » et « son
        // type » à deux explications distinctes.
        String partTip = tooltipForPartUnder(hint, hit.withinHint());
        if (partTip != null && !partTip.isEmpty()) {
            view.popupManager.showQuickDocRaw(hint.offset, partTip);
            return true;
        }
        if (hint.tooltip != null && !hint.tooltip.isEmpty()) {
            // Tooltip direct : quick doc immédiat (zéro requête).
            view.popupManager.showQuickDocRaw(hint.offset, hint.tooltip);
            return true;
        }
        if (view.inlayTooltipResolver == null) return false;
        // Résolution paresseuse sur la voie « features » — une seule
        // requête par appui, livraison latest-wins (génération via
        // showQuickDocRaw à l'arrivée).
        final int fOffset = hint.offset;
        final String fText = hint.text;
        final String docText = view.session.getText();
        jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
            String tooltip = null;
            try {
                tooltip = view.inlayTooltipResolver.resolveInlayTooltip(
                        docText, fOffset, fText);
            } catch (Exception e) {
                jo.codeeditor.util.EditorLog.warn("EditorInlay",
                    "résolution du tooltip d'inlay échouée", e);
            }
            if (tooltip != null && !tooltip.isEmpty()) {
                view.popupManager.showQuickDocRaw(fOffset, tooltip);
            }
        });
        return true;
    }

    /**
     * Hit-test partagé des inlay hints tissés (I2/I3/I4) : le hint de
     * session ancré sous le point, avec sa plage visuelle tissée.
     * Retourne null si aucun hint n'est touché (comportement classique
     * du geste appelant). En mode wrap le tissage n'est pas cartographié
     * par colonnes : repli systématique (null).
     */
    InlayHit inlayHitAt(float x, float y) {
        if (view.session == null || view.wordWrap) return null;
        jo.codeeditor.document.EditorDocument doc = view.session.getDocument();
        int line = jo.codeeditor.view.EditorView.clamp(
                view.docLineForScreenY(y), 0, doc.lineCount() - 1);
        jo.codeeditor.cache.LineRenderCache.LineCacheEntry entry =
                view.layoutForLine(line, doc.lineText(line));
        if (entry == null || entry.inlays == null || entry.inlays.isEmpty()
                || entry.rawToVisual == null) {
            return null;
        }
        // Colonne VISUELLE du doigt (même formule que offsetAt hors wrap).
        float charWidth = view.metrics.getCharWidth();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        int visCol = (int) ((x - (textAreaLeft - view.hOffset)) / charWidth + 0.5f);
        int lineStart = doc.lineStart(line);
        for (jo.codeeditor.cache.LineRenderCache.InlayPiece p : entry.inlays) {
            int hintStart = entry.rawToVisual[Math.min(p.col,
                    entry.rawToVisual.length - 1)];
            if (visCol < hintStart || visCol >= hintStart + p.text.length()) continue;
            // Hint touché — retrouve le hint de session (tooltip + offset).
            DiagnosticShift.InlayHint hint = hintAtCol(line, lineStart, p.col);
            if (hint == null) return null;
            return new InlayHit(hint, hintStart, p.text.length(), visCol);
        }
        return null;
    }

    /**
     * Tooltip de la part du libellé sous la position {@code withinHint}
     * (0-based dans le texte tissé du hint) — I3 : la concat des parts
     * est ÉGALE au texte du hint (invariant du pont) ; une position hors
     * de toute part (parts incohérentes) rend null, la consultation
     * retombe sur le tooltip du hint entier.
     */
    private static String tooltipForPartUnder(DiagnosticShift.InlayHint hint,
            int withinHint) {
        if (hint.parts == null || withinHint < 0) return null;
        int cursor = 0;
        for (DiagnosticShift.InlayHintPart part : hint.parts) {
            int len = part.text.length();
            if (withinHint >= cursor && withinHint < cursor + len) {
                return part.tooltip;
            }
            cursor += len;
        }
        return null;
    }

    /** Un hint tissé touché : le hint de session + sa plage visuelle +
     *  la colonne visuelle du doigt (I3/I4 partagent ce hit-test). */
    static final class InlayHit {
        final DiagnosticShift.InlayHint hint;
        /** Colonne visuelle où le hint tissé COMMENCE. */
        final int hintStart;
        /** Longueur du texte tissé du hint (caractères). */
        final int hintLen;
        /** Colonne visuelle du doigt. */
        final int visCol;

        InlayHit(DiagnosticShift.InlayHint hint, int hintStart, int hintLen,
                int visCol) {
            this.hint = hint;
            this.hintStart = hintStart;
            this.hintLen = hintLen;
            this.visCol = visCol;
        }

        /** Position 0-based du doigt DANS le texte tissé du hint. */
        int withinHint() {
            return visCol - hintStart;
        }
    }

    /**
     * D3 (lot 2, J4) — appui long sur un squiggle : copie le message
     * du diagnostic le plus sévère couvrant strictement l'offset
     * (cœur de plage, bords ±1 exclus — même sémantique que le popup
     * ancré D1 ; le voile Unnecessary n'est pas tappable).
     *
     * <p>Vrai si le message a été copié (l'appui long est CONSOMMÉ :
     * ni sélection de mot, ni pill, ni clavier — le geste est
     * « copier », pas « sélectionner »). Faux → comportement
     * historique de l'appui long.</p>
     */
    private boolean handleSquiggleLongPressCopy(float x, float y) {
        if (view.session == null) return false;
        if (x < view.metrics.getGutterWidth()) return false;
        int offset = view.offsetAt(x, y);
        DiagnosticShift.Diagnostic best = null;
        for (DiagnosticShift.Diagnostic d : view.session.getDiagnostics()) {
            if (d == null || d.unnecessary) continue;
            if (d.start + 1 < offset && offset < d.end) {
                if (best == null || d.severity > best.severity) best = d;
            }
        }
        if (best == null || best.message == null || best.message.isEmpty()) {
            return false;
        }
        view.copyToClipboard(best.message);
        return true;
    }

    /** Le hint de SESSION ancré à la colonne brute {@code col} de la ligne
     *  (porte le tooltip) — null si absent (pièce de cache orpheline). */
    private DiagnosticShift.InlayHint hintAtCol(int line, int lineStart, int col) {
        for (DiagnosticShift.InlayHint h : view.session.getInlayHintsForLine(line)) {
            if (h.offset - lineStart == col) return h;
        }
        return null;
    }
}
