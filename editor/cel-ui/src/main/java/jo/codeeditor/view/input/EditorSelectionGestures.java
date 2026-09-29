package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.EditorPopupAnchors;

import androidx.annotation.RestrictTo;

import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

import jo.codeeditor.document.Selection;

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

    // ── Drag-select au doigt, armé par l'APPUI LONG (modèle sora) ──
    // Portage de l'approche sora-editor (EditorTouchEventHandler :
    // beginDragSelect/handleDragSelect + prop dragSelectAfterLongPress) :
    // le drag-select n'est PLUS armé par un simple tap — le jitter
    // naturel du doigt sous le seuil du touch-slop déclenchait une
    // sélection depuis le caret posé par le tap précédent (« plusieurs
    // lignes ou mots sélectionnés alors que j'ai seulement appuyé »).
    // Il est armé par l'appui long, qui a déjà sélectionné le mot : le
    // glissement étend alors la sélection depuis la borne du mot
    // OPPOSÉE au doigt (sora : anchor = right si doigt < point
    // d'appui, left sinon). Toute terminaison de geste (UP/CANCEL) et
    // tout nouveau DOWN désarment (sora : finishDragSelect au DOWN).
    private static final int NO_DRAG_SELECT = -1;
    /** Offset du point d'appui long (le « character index » de sora). */
    private int dragSelectPressOffset = NO_DRAG_SELECT;
    /** Borne GAUCHE de la sélection au moment de l'appui long. */
    private int dragSelectLeft = NO_DRAG_SELECT;
    /** Borne DROITE de la sélection au moment de l'appui long. */
    private int dragSelectRight = NO_DRAG_SELECT;

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
        // État du dispatcher (longPressTriggered, fin de drag/scroll) via
        // l'orchestrateur — corps historique déplacé à l'identique.
        input.markLongPressTriggered();
        int offset = view.offsetAt(x, y);
        view.session.selectWordAt(offset);
        view.handlesVisible = true;
        // ★ Arme le drag-select (modèle sora dragSelectAfterLongPress) :
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
     *  selectWordAt) : fige les trois indices du modèle sora — offset du
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

    /** Désarme le drag-select (sora finishDragSelect) : toute terminaison
     *  de geste (UP/CANCEL), tout nouveau DOWN et tout changement de
     *  session l'appellent — le drag-select ne survit JAMAIS à un geste. */
    void disarmDragSelect() {
        dragSelectPressOffset = NO_DRAG_SELECT;
        dragSelectLeft = NO_DRAG_SELECT;
        dragSelectRight = NO_DRAG_SELECT;
    }

    /** Vrai si le drag-select est armé (appui long déclenché, pas encore
     *  consommé). Lu par l'orchestrateur pour décider si un MOVE au-delà
     *  du slop engage le drag-select ou le scroll. */
    boolean isDragSelectArmed() {
        return dragSelectPressOffset != NO_DRAG_SELECT;
    }

    /**
     * Glissement de drag-select (armé par l'appui long). Règle d'ancre
     * sora : la borne du MOT opposée au doigt reste l'ancre — doigt à
     * gauche du point d'appui → ancre = borne droite, doigt à droite →
     * ancre = borne gauche. La sélection contient toujours le mot
     * d'origine et suit le doigt. La loupe s'affiche pendant le
     * glissement (sora updateDragSelectMagnifier).
     */
    void handleTouchDrag(MotionEvent event) {
        int offset = view.offsetAt(event.getX(), event.getY());
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
        view.magnifierActive = true;
        view.magnifierX = event.getX();
        view.magnifierY = event.getY();
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
        view.magnifierActive = true;
        view.magnifierX = x;
        view.magnifierY = y;
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
        view.selectionToolbarVisible = false;
        view.selectionToolbarPressedIdx = -1;
        view.invalidate();
    }

    /**
     * Résout le tap sur la pill via les MÉTRIQUES PARTAGÉES
     * ({@link EditorView#selectionToolbarMetrics()}), plus aucune
     * duplication du layout. Les 6 actions sont câblées :
     * Copy/Cut/Paste/Select all + Docs ℹ / Actions ⋯ (le hit-test
     * recalculait auparavant un layout 4-boutons obsolète — les icônes
     * étaient inatteignables et la pill collapsed rejetait tout tap).
     *
     * <p>Comportement de la toolbar de sélection : Copy/Cut/Paste
     * referment la pill (et masquent les poignées), Select all la laisse
     * ouverte (Copy/Cut deviennent disponibles), Docs ouvre le quick-doc,
     * Actions ouvre le popup de quick-fixes de la ligne.</p>
     */
    boolean handleSelectionToolbarTap(float x, float y) {
        if (!view.selectionToolbarVisible || view.session == null) return false;
        EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        if (m == null) return false;
        int act = m.actionAt(x, y);
        if (act < 0) return false;

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
                hideSelectionChrome();
                // ★ Ouvre le MENU CONTEXTUEL UNIFIÉ (portage
                // menu contextuel unifié) : sections GO TO /
                // QUICK FIXES / INTENTIONS, au lieu de la simple liste de
                // quick-fixes.
                view.showNavMenu(view.session.getDocument()
                        .lineForOffset(sel.start), sel.start);
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
}
