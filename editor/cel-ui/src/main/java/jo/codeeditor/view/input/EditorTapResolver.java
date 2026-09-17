package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.EditorPopupAnchors;
import jo.codeeditor.view.popup.EditorPopupHitTester;

import androidx.annotation.RestrictTo;

import android.view.inputmethod.InputMethodManager;

import jo.codeeditor.document.Selection;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Cascade de résolution des taps d'EditorView, extraite d'EditorInputHandler :
 * comptage mono/double/triple tap, placement du caret, extension de la
 * sélection, routage vers les popups (icônes d'aperçu/toolbar, complétion,
 * fiches de diagnostic, chips de fold, NavMenu, actions de code,
 * aller-au-symbole) et armement du tap-dismiss différé dans la fenêtre
 * multi-tap (portage EditorInputModifier de CodeAssist).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorTapResolver {

    private final EditorView view;
    private final EditorInputHandler input;
    private final EditorPopupHitTester hitTester;
    private final EditorSelectionGestures selectionGestures;

    // ── État multi-tap ─────────────────────────────────────────────
    private long lastTapTime = 0;
    private float lastTapX = 0;
    private float lastTapY = 0;
    private int tapCount = 0;
    private static final long MULTI_TAP_TIMEOUT_MS = 280;
    private static final float MULTI_TAP_SLOP_PX = 48f;

    // ── pendingTapDismiss (portage EditorInputModifier de CodeAssist) ──
    // Un tap est tombé dans une sélection existante : l'offset où placer
    // le caret SI cela s'avère être un tap ISOLÉ, décidé une fois la
    // fenêtre multi-tap échue. Entre le tap et la décision la sélection
    // reste vivante (pas de scintillement) pour qu'un double-tap suivant
    // puisse l'étendre (cas 2 ci-dessous) et que la toolbar continue de
    // s'afficher. -1 = aucun.
    // CodeAssist s'appuie sur le onTap de Compose qui se déclenche après
    // le timeout système de double-tap ; ici la même fenêtre est
    // MULTI_TAP_TIMEOUT_MS et le commit est un postDelayed sur le looper
    // principal.
    private int pendingTapDismiss = -1;
    private final Runnable pendingTapDismissTask = new Runnable() {
        @Override
        public void run() {
            int target = pendingTapDismiss;
            pendingTapDismiss = -1;
            if (target < 0 || view.session == null) return;
            if (target > view.session.getDocument().length()) return;
            // Tap isolé dans la sélection → on la replie à l'offset tapé
            // (CodeAssist : session.setCaret(target) + handlesVisible
            // = false, ce qui masque les poignées ET la pill).
            view.session.setSelection(target);
            view.handlesVisible = false;
            selectionGestures.dismissSelectionToolbar();
            view.caretAnim.onEditOrMove();
            view.invalidate();
        }
    };

    EditorTapResolver(EditorView view, EditorInputHandler input,
            EditorPopupHitTester hitTester, EditorSelectionGestures selectionGestures) {
        this.view = view;
        this.input = input;
        this.hitTester = hitTester;
        this.selectionGestures = selectionGestures;
    }

    void handleTap(float x, float y) {
        long now = System.currentTimeMillis();
        // Annonce le tap pour l'accessibilité — le override performClick()
        // d'EditorView n'était jamais invoqué depuis le chemin tactile, donc
        // les utilisateurs TalkBack n'avaient aucun feedback de clic et lint
        // signalait ClickableViewAccessibility sur chaque onTouchEvent
        // personnalisé.
        view.performClick();
        // Tout nouveau tap résout le tap-dismiss différé en attente — la
        // cascade propre du tap prend le relais (déplacer le caret,
        // étendre, basculer…).
        cancelPendingTapDismiss();
        boolean withinTimeout = (now - lastTapTime) < MULTI_TAP_TIMEOUT_MS;
        boolean withinSlop = (x - lastTapX) * (x - lastTapX)
            + (y - lastTapY) * (y - lastTapY) < MULTI_TAP_SLOP_PX * MULTI_TAP_SLOP_PX;
        if (withinTimeout && withinSlop) {
            tapCount++;
        } else {
            tapCount = 1;
        }
        lastTapTime = now;
        lastTapX = x;
        lastTapY = y;

        // Tap sur l'icône d'aperçu — contrôlé avant tout le reste.
        // Dispatch via view.openPreview() pour que les fichiers .md/.html
        // ouvrent la feuille popup overlay (SHEET_SPLIT/SHEET_FULL) tandis
        // que les layouts .xml gardent le comportement inline historique
        // SPLIT/FULL.
        if (tapCount == 1) {
            int iconHit = view.hitTestPreviewIcons(x, y);
            if (iconHit == 1) {
                view.openPreview(false);
                return;
            } else if (iconHit == 2) {
                view.openPreview(true);
                return;
            }
        }
        // Tap sur les icônes de toolbar (A+, A-, ¶, lig).
        if (tapCount == 1) {
            int tbHit = EditorPopupAnchors.hitTestToolbarIcons(view, x, y);
            if (tbHit == 1) { view.increaseFontSize(); return; }
            if (tbHit == 2) { view.decreaseFontSize(); return; }
            if (tbHit == 3) { view.setShowNonPrintable(!view.showNonPrintable); return; }
            if (tbHit == 4) { view.setFontLigatures(!view.fontLigatures); return; }
        }

        // Test de touche du popup de complétion.
        if (view.completionVisible) {
            int hitRow = hitTester.hitTestCompletionPopup(x, y);
            if (hitRow >= 0) {
                view.completionSelected = hitRow;
                view.invalidate();
                view.completionAccept();
                return;
            }
            view.dismissCompletion();
        }

        // Fiche de liste des diagnostics groupés — un tap sur une rangée
        // ouvre le popup de détail de ce diagnostic (pour qu'un warning
        // caché derrière une erreur sur la même ligne devienne
        // accessible) ; le bouton fermer et le scrim ferment.
        if (view.diagnosticListSheetLine >= 0) {
            float[] m = view.diagnosticListSheetMetrics();
            if (m != null) {
                float closeCx = m[6], closeCy = m[7], closeR = m[8];
                float dxClose = x - closeCx, dyClose = y - closeCy;
                if (dxClose * dxClose + dyClose * dyClose <= closeR * closeR) {
                    view.dismissDiagnosticListSheet();
                    return;
                }
                if (y >= m[0] + m[2] && y <= m[1]) {
                    int row = (int) ((y - (m[0] + m[2])) / m[3]);
                    int rows = (int) m[4];
                    if (row >= 0 && row < rows && view.session != null) {
                        List<DiagnosticShift.Diagnostic> all =
                                view.session.getDiagnosticsForLine(view.diagnosticListSheetLine);
                        if (row < all.size()) {
                            DiagnosticShift.Diagnostic d = all.get(row);
                            view.dismissDiagnosticListSheet();
                            view.showDiagnosticPopup(d, d.start);
                            return;
                        }
                    }
                }
            }
            // Tout autre tap (scrim inclus) ferme la fiche modale.
            view.dismissDiagnosticListSheet();
            return;
        }

        // Test de touche du popup de diagnostic (fiche modale style CodeAssist).
        if (view.diagnosticPopupVisible) {
            if (hitTester.hitTestDiagnosticSheetClose(x, y)) {
                view.dismissDiagnosticPopup();
                return;
            }
            int hitAction = hitTester.hitTestDiagnosticPopup(x, y);
            if (hitAction >= 0) {
                if (view.diagnosticPopupItem != null && view.session != null) {
                    int line = view.session.getDocument().lineForOffset(view.diagnosticPopupItem.start);
                    List<EditorView.CodeAction> actions = view.codeActionsByLine.get(line);
                    if (actions != null && hitAction < actions.size()) {
                        EditorView.CodeAction a = actions.get(hitAction);
                        if (a.apply != null) {
                            a.apply.run();
                            view.onTextChanged();
                        }
                    }
                }
                view.dismissDiagnosticPopup();
                return;
            }
            // Tout autre tap (scrim inclus) ferme la fiche modale.
            view.dismissDiagnosticPopup();
            return;
        }

        // Test de touche de la toolbar de sélection.
        if (view.selectionToolbarVisible && selectionGestures.handleSelectionToolbarTap(x, y)) {
            return;
        }

        // Test de touche du menu contextuel — un tap sur une rangée la
        // choisit ; un tap ailleurs ferme (Popup onDismissRequest de
        // CodeAssist).
        if (view.navMenuVisible) {
            int navRow = hitTester.navMenuRowIndexOf(x, y);
            if (navRow >= 0) {
                hitTester.handleNavMenuTap(x, y, navRow);
            } else {
                view.dismissNavMenu();
            }
            return;
        }

        // Test de touche du popup d'actions de code.
        if (view.codeActionsPopupVisible) {
            int hitRow = hitTester.hitTestCodeActionsPopup(x, y);
            if (hitRow >= 0) {
                view.codeActionsSelected = hitRow;
                view.invalidate();
                view.applySelectedCodeAction();
                return;
            }
        }

        // Test de touche du popup aller-au-symbole.
        if (view.goToSymbolVisible) {
            int hitRow = hitTester.hitTestGoToSymbolPopup(x, y);
            if (hitRow >= 0) {
                view.goToSymbolSelected = hitRow;
                view.goToSymbolAccept();
                return;
            }
        }

        // Tap sur la bande de fold.
        if (x < view.metrics.getGutterWidth()
            && x >= view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth()) {
            int line = view.docLineForScreenY(y);
            if (view.codeActionsByLine.containsKey(line) && view.lineHasDiagnostic(line)) {
                view.showCodeActions(line);
                return;
            }
            if (view.session.toggleFoldAtLine(line)) {
                view.invalidate();
                return;
            }
            DiagnosticShift.Diagnostic lineDiag = view.findDiagnosticAtLine(line);
            if (lineDiag != null) {
                view.showDiagnosticPopup(lineDiag, lineDiag.start);
                return;
            }
            return;
        }

        // Tap sur le point de diagnostic du gutter.
        if (x < view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth()) {
            int line = view.docLineForScreenY(y);
            DiagnosticShift.Diagnostic lineDiag = view.findDiagnosticAtLine(line);
            if (lineDiag != null) {
                view.showDiagnosticPopup(lineDiag, lineDiag.start);
                return;
            }
        }

        int offset = view.offsetAt(x, y);

        // Si le tap est tombé sur la chip placeholder ({...}) d'une ligne
        // de début de fold, étend le fold au lieu de placer le caret.
        // Détecté en vérifiant que la ligne est une ligne de début de fold
        // et que le X du tap est dans la plage horizontale de la chip
        // placeholder.
        if (x >= view.metrics.getGutterWidth() && tapCount == 1) {
            int tappedLine = view.session.getDocument().lineForOffset(offset);
            DiagnosticShift.FoldRegion fold = view.collapsedFoldStartingAtLine(tappedLine);
            if (fold != null) {
                // Calcule la plage X de la chip placeholder.
                float charWidth = view.metrics.getCharWidth();
                float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
                int startLine = view.session.getDocument().lineForOffset(fold.start);
                int prefixEndCol = fold.start - view.session.getDocument().lineStart(startLine);
                float prefixW = prefixEndCol * charWidth;
                float placeW = fold.placeholder.length() * charWidth;
                float chipX1 = textAreaLeft - view.hOffset + prefixW;
                float chipX2 = chipX1 + placeW;
                if (x >= chipX1 && x <= chipX2) {
                    // Tap sur la chip {...} — étend le fold.
                    view.session.toggleFoldAtLine(tappedLine);
                    view.invalidate();
                    return;
                }
            }
        }

        // La fiche de diagnostic ne s'ouvre désormais QUE depuis (1) la
        // chip de diagnostic après la fin de ligne et (2) le point de
        // diagnostic du gutter — exactement les deux points d'entrée de
        // CodeAssist (DiagnosticChip.onClick → openSheet, tap sur glyphe de
        // gutter → openSheet). Le squiggle lui-même N'EST PAS tappable :
        // un tap sur la plage ondulée place simplement le caret (branche
        // else de CodeAssist → session.setCaret), pour que l'utilisateur
        // puisse encore positionner le curseur avant/après la plage de
        // diagnostic. Le code de hit sur squiggle a été retiré pour cette
        // parité.
        if (x >= view.metrics.getGutterWidth() && tapCount == 1) {
            // Une ligne avec PLUSIEURS diagnostics ouvre d'abord la fiche
            // groupée ; une seule ouvre directement le popup de détail
            // (parité diagnosticsByStartLine de CodeAssist).
            EditorView.DiagnosticChipHit chipHit = view.findDiagnosticChipHitAt(x, y);
            if (chipHit != null) {
                if (chipHit.diagnostics.size() > 1) {
                    view.showDiagnosticListSheet(chipHit.line);
                } else {
                    view.showDiagnosticPopup(chipHit.primary(), chipHit.primary().start);
                }
                return;
            }
        }

        // Arme le clavier au tap dans la zone de texte.
        if (x >= view.metrics.getGutterWidth()) {
            view.wantsKeyboard = true;
            view.requestFocus();
            InputMethodManager imm = view.imm();
            if (imm != null) imm.showSoftInput(view, 0);
        }

        switch (tapCount) {
            case 1:
                Selection prev = view.session.getSelection();
                if (!prev.isCursor() && offset >= prev.start && offset <= prev.end) {
                    // Tap DANS la sélection (pendingTapDismiss, port
                    // CodeAssist onPress/onTap) : on la garde vivante
                    // (anti-flicker), poignées + pill ré-armées, et la décision
                    // est DIFFÉRÉE de la fenêtre multi-tap : un double-tap qui
                    // suit l'étend (case 2), un tap seul la referme au caret
                    // tapé une fois la fenêtre écoulée. C'est aussi ce qui
                    // permet de refermer une sélection couvrant tout le
                    // fichier — là, chaque tap est « dedans ».
                    view.handlesVisible = true;
                    selectionGestures.showSelectionToolbar();
                    armPendingTapDismiss(offset);
                } else {
                    // Re-tap CodeAssist : un second tap au MÊME
                    // endroit que le caret collapsed BASCULE la pill
                    // Paste/Select all (le re-tap sur le caret
                    // « interaction.handlesVisible = reTap && !handlesVisible »).
                    // Un tap ailleurs la referme.
                    boolean reTap = prev.isCursor() && prev.start == offset;
                    view.session.setSelection(offset);
                    if (reTap && !view.selectionToolbarVisible) {
                        view.handlesVisible = true;
                        selectionGestures.showSelectionToolbar();
                    } else {
                        view.handlesVisible = false;
                        selectionGestures.dismissSelectionToolbar();
                    }
                    input.armDragSelect(); // arme le drag-select (UX CodeIDE)
                }
                break;
            case 2:
                view.session.selectWordAt(offset);
                view.handlesVisible = true;
                selectionGestures.showSelectionToolbar();
                break;
            default:
                view.session.selectLineAt(offset);
                view.handlesVisible = true;
                selectionGestures.showSelectionToolbar();
                break;
        }

        // Réinitialise le clignotement du caret au tap.
        // CaretAnimator.onEditOrMove() est le point d'entrée unique des
        // mises à jour d'état du caret — met à jour l'horodatage de
        // dernière activité, rend le caret visible, réinitialise la bascule
        // de clignotement, annule tout glissement en cours.
        view.caretAnim.onEditOrMove();

        view.invalidate();
    }

    // ── pendingTapDismiss ──

    /**
     * Arme le tap-dismiss différé : l'offset tapé est commité — la
     * sélection y est repliée, poignées + pill masquées — si aucun second
     * tap n'arrive dans {@link #MULTI_TAP_TIMEOUT_MS}. Réarme proprement
     * si un pending précédent était encore vivant.
     */
    private void armPendingTapDismiss(int offset) {
        cancelPendingTapDismiss();
        pendingTapDismiss = offset;
        if (input.tapHandler == null) {
            // Handler du looper principal — les événements tactiles arrivent
            // toujours sur le thread UI, et ceci reste testable sous
            // l'horloge fantôme de Robolectric (view.getHandler() est null
            // tant que la vue n'est pas attachée).
            input.tapHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        input.tapHandler.postDelayed(pendingTapDismissTask, MULTI_TAP_TIMEOUT_MS);
    }

    /**
     * Annule le tap-dismiss différé en attente, s'il existe. Appelée à
     * chaque nouveau geste (DOWN), chaque nouveau tap (entrée de
     * handleTap), swipe-au-delà-du-slop, annulation de geste et toute
     * édition de texte (via EditorView.onTextChanged).
     */
    void cancelPendingTapDismiss() {
        pendingTapDismiss = -1;
        if (input.tapHandler != null) input.tapHandler.removeCallbacks(pendingTapDismissTask);
    }
}
