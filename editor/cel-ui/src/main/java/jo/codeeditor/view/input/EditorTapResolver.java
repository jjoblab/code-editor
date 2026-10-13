package jo.codeeditor.view.input;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.EditorPopupHitTester;

import androidx.annotation.RestrictTo;

import android.view.inputmethod.InputMethodManager;

import jo.codeeditor.document.Selection;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Cascade de résolution des taps d'EditorView, extraite d'EditorInputHandler :
 * comptage mono/double/triple tap, placement du caret, extension de la
 * sélection, routage vers les popups (icônes d'aperçu, complétion,
 * fiches de diagnostic, chips de fold, NavMenu, actions de code,
 * aller-au-symbole) et armement du tap-dismiss différé dans la fenêtre
 * multi-tap.
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

    // ── pendingTapDismiss (armé par le tap, résolu au geste suivant) ──
    // Un tap est tombé dans une sélection existante : l'offset où placer
    // le caret SI cela s'avère être un tap ISOLÉ, décidé une fois la
    // fenêtre multi-tap échue. Entre le tap et la décision la sélection
    // reste vivante (pas de scintillement) pour qu'un double-tap suivant
    // puisse l'étendre (cas 2 ci-dessous) et que la toolbar continue de
    // s'afficher. -1 = aucun.
    // Le onTap de Compose se déclenche après
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
            // (pose le caret + affiche les poignées
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

        // ── B9c (retour terrain n°6) : tap sur l'icône d'aperçu de la
        // GOUTTIÈRE (œil sur la rangée du caret, façon Android Studio) —
        // BASCULE : feuille scindée si fermée, fermeture si ouverte
        // (parité exacte avec l'œil du fil d'Ariane B6). Contrôlé AVANT
        // les branches gouttière (fold/diagnostics) : l'icône vit dans
        // la zone des numéros et possède SA rangée. Le caret n'est PAS
        // déplacé — le geste appartient à l'icône.
        // Pas de garde tapCount (précédent de la bande de fold ci-dessous) :
        // les taps de GOUTTIÈRE appartiennent à la gouttière — un
        // double-tap rapide sur l'icône bascule deux fois (ouvrir puis
        // refermer), il ne tombe JAMAIS dans la cascade multi-tap du
        // texte (mot/ligne) derrière.
        if (view.hitTestGutterPreviewAction(x, y)) {
            if (view.previewMode.isSheet()) {
                view.closePreviewSheet();
            } else {
                view.openPreview(false);
            }
            return;
        }
        // UX-5 : les zones de tap de l'ancienne toolbar canvas (A+, A−,
        // ¶, ligatures) sont retirées — les icônes ne sont plus dessinées
        // depuis l'extraction d'EditorBarTools et les boutons vivent dans
        // les barres d'hôte (BreadcrumbBar.addAction / EditorBarTools).
        // Un tap en haut à droite ne doit plus déclencher d'action
        // invisible : il retombe dans la cascade normale (caret).

        // ★ B8 (retour terrain n°5) : l'aide de signature est une bannière
        // PASSIVE (aucune rangée interactive — la navigation des
        // surcharges est clavier) flottant au-dessus de la ligne du
        // caret. Un tap quelconque la referme : le geste appartient au
        // texte derrière (placement du caret), la cascade continue.
        // Elle revient à la prochaine édition si le caret est encore
        // dans un appel (refreshSignatureHelp, chemin onTextChanged).
        if (view.signatureHelpVisible) {
            view.dismissSignatureHelp();
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

        // Test de touche du popup de diagnostic (fiche modale).
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

        // L'offset du tap, résolu tôt : la cascade squiggle en a besoin
        // AVANT les branches gouttière/chip (les branches intermédiaires
        // ne l'utilisent pas).
        int offset = view.offsetAt(x, y);

        // Test de touche du popup ancré de squiggle (D1, non modal) :
        // hit de rangée (quick-fix / choix de mini-liste / « See all ») ;
        // un tap HORS du chrome le referme. Un re-tap sur la MÊME plage
        // armée tombe dans la branche caret (parité, motif
        // pendingTapDismiss : le premier tap arme, le second décide).
        boolean squiggleReTap = false;
        if (view.squigglePopupVisible) {
            int sqHit = hitTester.hitSquigglePopup(x, y);
            if (sqHit >= 0) {
                view.popupManager.handleSquiggleHit(sqHit);
                return;
            }
            int rStart = view.squigglePopupItem != null
                    ? view.squigglePopupItem.start : -1;
            int rEnd = view.squigglePopupItem != null
                    ? view.squigglePopupItem.end : -1;
            view.popupManager.dismissSquigglePopup();
            squiggleReTap = rStart >= 0 && rStart + 1 < offset && offset < rEnd;
        }

        // Test de touche de la toolbar de sélection.
        if (view.selectionToolbarVisible && selectionGestures.handleSelectionToolbarTap(x, y)) {
            return;
        }

        // Test de touche du menu contextuel — un tap sur une rangée la
        // choisit ; un tap ailleurs ferme (Popup onDismissRequest de
        // standard).
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
            // ★ B8 (retour terrain n°5) : tap HORS du cadre → le popup
            // se referme et la cascade CONTINUE (le tap visait le texte
            // derrière — placement du caret), même motif que le popup
            // ancré de squiggle juste en dessous.
            view.dismissCodeActions();
        }

        // Test de touche du popup aller-au-symbole.
        if (view.goToSymbolVisible) {
            int hitRow = hitTester.hitTestGoToSymbolPopup(x, y);
            if (hitRow >= 0) {
                view.goToSymbolSelected = hitRow;
                view.goToSymbolAccept();
                return;
            }
            // ★ B8 (retour terrain n°5) : tap HORS du cadre → fermeture,
            // cascade continue (parité actions de code / squiggle).
            view.dismissGoToSymbol();
        }

        // ── S3 (lot 2, J4) : tap sur une ligne sticky → navigation ──
        // La bande sticky (≤ stickyMaxLines × lineHeight depuis
        // paddingTop) est dessinée PAR-DESSUS le gutter ET le texte :
        // un tap dedans centre la ligne d'ORIGINE de la rangée touchée
        // (scrollToLineCentered), AVANT le placement du caret et avant
        // les branches gouttière/fold que la bande recouvre visuellement.
        // Anti-faute-de-frappe : la hauteur de bande est bornée
        // (défaut 2 lignes) ; sans sticky actif, le tap reste un
        // placement de caret classique.
        if (tapCount == 1) {
            float lineHeight = view.metrics.getLineHeight();
            java.util.List<int[]> active = view.activeStickyLines();
            if (!active.isEmpty()) {
                float bandTop = view.metrics.getPadTop();
                if (y >= bandTop && y < bandTop + active.size() * lineHeight) {
                    int row = (int) ((y - bandTop) / lineHeight);
                    if (row >= 0 && row < active.size()) {
                        view.scrollManager.scrollToLineCentered(
                                active.get(row)[0]);
                        return;
                    }
                }
            }
        }

        // Tap sur la bande de fold.
        if (x < view.metrics.getGutterWidth()
            && x >= view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth()) {
            int line = view.docLineForScreenY(y);
            // Ligne de diagnostic → actions résolues à la demande au début
            // du diagnostic (l'ampoule est pilotée par les diagnostics).
            if (view.lineHasDiagnostic(line)) {
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

        // ── B11 (P1-1) : tap sur un MARQUEUR de la bande d'erreurs ──
        // (bord droit — tout le fichier, même hors écran). Déplie le pli
        // englobant, CENTRE la ligne dans le viewport puis ouvre la
        // fiche : popup de détail si la ligne porte un seul diagnostic,
        // liste groupée sinon (parité exacte avec le tap sur la chip de
        // fin de ligne / le point de gouttière). Un tap dans la bande
        // SANS marqueur consomme le geste (le caret ne se place pas
        // derrière — la bande est un chrome, pas du texte).
        if (view.errorStripe.hit(x, y)) {
            int line = view.errorStripe.markerLineAt(y);
            if (line >= 0 && view.session != null) {
                List<DiagnosticShift.Diagnostic> lineDiags =
                        view.session.getDiagnosticsForLine(line);
                DiagnosticShift.Diagnostic primary =
                        view.findDiagnosticAtLine(line);
                if (primary != null) {
                    // Le marqueur peut vivre sous un pli replié — sans
                    // dépliage, on centrerait du vide.
                    view.session.expandFoldAt(primary.start);
                    view.scrollManager.scrollToLineCentered(line);
                    if (lineDiags != null && lineDiags.size() > 1) {
                        view.showDiagnosticListSheet(line);
                    } else {
                        view.showDiagnosticPopup(primary, primary.start);
                    }
                }
            }
            return;
        }

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
        // diagnostic du gutter — les deux points d'entrée historiques
        // (chip tapée → fiche, tap sur glyphe de gutter → openSheet).
        if (x >= view.metrics.getGutterWidth() && tapCount == 1) {
            // Une ligne avec PLUSIEURS diagnostics ouvre d'abord la fiche
            // groupée ; une seule ouvre directement le popup de détail
            // (groupés par ligne de départ).
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

        // ── I4 (lot 2, J6) : tap sur un hint CLIQUABLE → command ──
        // Résolu AVANT le placement du caret (précédent complétion) et
        // AVANT le squiggle D1 : un hint portant une command est un
        // élément interactif tissé dans la ligne (ex. « … »
        // d'expansion) — le tap l'exécute. Un hint SANS command reste
        // du texte fantôme : la cascade continue (caret, squiggle…).
        // Le long-press reste la voie du tooltip (I2) — le tap est la
        // voie de l'action.
        if (tapCount == 1 && x >= view.metrics.getGutterWidth()) {
            EditorSelectionGestures.InlayHit hit =
                    selectionGestures.inlayHitAt(x, y);
            if (hit != null && hit.hint.command != null) {
                try {
                    hit.hint.command.run();
                } catch (Exception e) {
                    jo.codeeditor.util.EditorLog.warn("EditorInlay",
                            "commande d'inlay échouée", e);
                }
                // Contrat des actions d'édition : rafraîchissement
                // complet après exécution (diagnostics, hints,
                // popups différés — point d'entrée unique).
                view.onTextChanged();
                view.invalidate();
                return;
            }
        }

        // ── D1 : tap au CŒUR d'une plage squigglée → popup ancré ──
        // Le squiggle EST tappable (niveau 1 de lecture) : bords ±1
        // caractère exclus (le caret reste plaçable avant/après la
        // plage), re-tap sur la plage armée → caret (décision différée
        // ci-dessus — le premier tap arme, le second décide).
        if (!squiggleReTap && x >= view.metrics.getGutterWidth() && tapCount == 1) {
            if (view.popupManager.squigglePopupAt(offset, x)) {
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
                    //) : on la garde vivante
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
                    // Re-tap : un second tap au MÊME
                    // endroit que le caret collapsed BASCULE la pill
                    // Paste/Select all (le re-tap sur le caret
                    // « interaction.handlesVisible = reTap && !handlesVisible »).
                    // Un tap ailleurs la referme.
                    //
                    // ★ Le tap ne NUIT PLUS le drag-select :
                    // l'armement par tap + jitter sous le slop créait une
                    // sélection parasite (« j'ai seulement appuyé »).
                    // Le drag-select s'arme désormais par l'APPUI LONG
                    // (EditorSelectionGestures.handleLongPress).
                    boolean reTap = prev.isCursor() && prev.start == offset;
                    view.session.setSelection(offset);
                    if (reTap && !view.selectionToolbarVisible) {
                        view.handlesVisible = true;
                        selectionGestures.showSelectionToolbar();
                    } else {
                        view.handlesVisible = false;
                        selectionGestures.dismissSelectionToolbar();
                    }
                }
                break;
            case 2:
                view.session.selectWordAt(offset);
                view.handlesVisible = true;
                selectionGestures.showSelectionToolbar();
                break;
            case 3:
                view.session.selectLineAt(offset);
                view.handlesVisible = true;
                selectionGestures.showSelectionToolbar();
                break;
            default:
                // B10 : 4e tap (et suivants) — le PARAGRAPHE : bloc de
                // lignes contiguës non vides autour du tap, borné par les
                // lignes vides. La cascade complète devient
                // caret → mot → ligne → paragraphe ; au-delà, on reste au
                // paragraphe (le 5e tap ne repart pas au caret).
                view.session.selectParagraphAt(offset);
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
