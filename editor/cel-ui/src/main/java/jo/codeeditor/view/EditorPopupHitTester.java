package jo.codeeditor.view;

import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Tests de touche géométriques des popups d'EditorView, extraits
 * d'EditorInputHandler : traduisent une position tactile en rangée de popup
 * (complétion, actions de code, aller-au-symbole, fiche de diagnostic) à
 * partir des métriques partagées d'EditorView, et résolvent le choix d'une
 * rangée du menu contextuel unifié (NavMenu).
 */
class EditorPopupHitTester {

    private final EditorView view;

    EditorPopupHitTester(EditorView view) {
        this.view = view;
    }

    int hitTestCompletionPopup(float x, float y) {
        if (!view.completionVisible || view.completionItems.isEmpty()) return -1;
        float[] anchor = view.completionPopupAnchor();
        float anchorX = anchor[0], anchorY = anchor[1];
        float width = anchor[2], popupH = anchor[3], rowH = anchor[4];
        if (x < anchorX || x > anchorX + width || y < anchorY || y > anchorY + popupH) {
            return -1;
        }
        int rowInPopup = (int) ((y - anchorY) / rowH);
        // L'ancre réduit les rangées quand le viewport est petit —
        // le hit-test suit la hauteur réelle du popup.
        int rowsToShow = Math.max(1, Math.round(popupH / rowH));
        if (rowInPopup < 0 || rowInPopup >= rowsToShow) return -1;
        return view.completionScrollOffset + rowInPopup;
    }

    /**
     * L'index de la rangée du menu contextuel unifié à (x, y), ou -1.
     * Seules les rangées ACTIONNABLES (option / action / target) comptent —
     * les headers, « Nothing found » et les positions hors carte rendent -1.
     * Prend en compte le scroll du contenu (navMenuScrollY).
     */
    int navMenuRowIndexOf(float x, float y) {
        if (!view.navMenuVisible) return -1;
        float[] m = view.navMenuMetrics();
        if (m == null) return -1;
        if (x < m[0] || x > m[0] + m[2] || y < m[1] || y > m[1] + m[3]) return -1;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.NAV_MENU_ROW_HEIGHT_DP * density;
        float headerH = view.NAV_MENU_HEADER_HEIGHT_DP * density;
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        float rowTop = m[1] - view.navMenuScrollY;
        for (int i = 0; i < rows.size(); i++) {
            EditorView.NavMenuRow row = rows.get(i);
            float rh = row.type == EditorView.NavMenuRow.TYPE_HEADER
                    ? headerH : rowH;
            if (y >= rowTop && y < rowTop + rh) {
                boolean actionable = row.type == EditorView.NavMenuRow.TYPE_OPTION
                        || row.type == EditorView.NavMenuRow.TYPE_ACTION
                        || row.type == EditorView.NavMenuRow.TYPE_TARGET;
                return actionable ? i : -1;
            }
            rowTop += rh;
        }
        return -1;
    }

    /**
     * Résout un tap sur la rangée {@code rowIdx} du menu contextuel
     * unifié (parité NavMenu onOption/onAction/onPick). Les press feedback et
     * dismiss sont gérés par les appelants.
     */
    void handleNavMenuTap(float x, float y, int rowIdx) {
        if (!view.navMenuVisible || rowIdx < 0) return;
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        if (rowIdx >= rows.size()) return;
        EditorView.NavMenuRow row = rows.get(rowIdx);
        switch (row.type) {
            case EditorView.NavMenuRow.TYPE_OPTION:
                if (row.ref instanceof NavigationMenu.NavOption) {
                    view.popupManager.navMenuPickOption((NavigationMenu.NavOption) row.ref);
                }
                break;
            case EditorView.NavMenuRow.TYPE_ACTION: {
                EditorView.CodeAction action = view.navMenuActionAt(row);
                if (action != null) view.popupManager.navMenuPickAction(action);
                break;
            }
            case EditorView.NavMenuRow.TYPE_TARGET:
                if (row.ref instanceof NavigationMenu.NavTarget) {
                    view.popupManager.navMenuPickTarget((NavigationMenu.NavTarget) row.ref);
                }
                break;
            default:
                break;
        }
    }

    int hitTestCodeActionsPopup(float x, float y) {
        if (!view.codeActionsPopupVisible || view.codeActionsPopupLine < 0) return -1;
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(view.codeActionsPopupLine);
        if (actions == null || actions.isEmpty()) return -1;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.CODE_ACTIONS_ROW_HEIGHT_DP * density;
        float width = view.CODE_ACTIONS_POPUP_WIDTH_DP * density;
        int rowsToShow = Math.min(view.CODE_ACTIONS_MAX_ROWS, actions.size());
        float popupH = rowH * rowsToShow;
        float anchorX = view.metrics.getGutterWidth() + 4 * density;
        // Utilise un Y fold-aware (et non le brut padTop + line * lineHeight).
        float anchorY = view.docLineToY(view.codeActionsPopupLine) - view.vOffset;
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, anchorY - popupH + view.metrics.getLineHeight());
        }
        if (x < anchorX || x > anchorX + width || y < anchorY || y > anchorY + popupH) {
            return -1;
        }
        int rowInPopup = (int) ((y - anchorY) / rowH);
        if (rowInPopup < 0 || rowInPopup >= rowsToShow) return -1;
        return rowInPopup;
    }

    int hitTestGoToSymbolPopup(float x, float y) {
        if (!view.goToSymbolVisible || view.goToSymbolFiltered.isEmpty()) return -1;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.GO_TO_SYMBOL_ROW_HEIGHT_DP * density;
        float width = view.GO_TO_SYMBOL_WIDTH_DP * density;
        float filterH = rowH;
        int rowsToShow = Math.min(view.GO_TO_SYMBOL_MAX_ROWS, view.goToSymbolFiltered.size());
        float popupH = filterH + rowH * rowsToShow;
        float anchorX = (view.getWidth() - width) * 0.5f;
        float anchorY = 8 * density;
        float listTop = anchorY + filterH;
        float listBottom = anchorY + popupH;
        if (x < anchorX || x > anchorX + width || y < listTop || y > listBottom) {
            return -1;
        }
        int rowInList = (int) ((y - listTop) / rowH);
        if (rowInList < 0 || rowInList >= rowsToShow) return -1;
        return view.goToSymbolScrollOffset + rowInList;
    }

    int hitTestDiagnosticPopup(float x, float y) {
        if (!view.diagnosticPopupVisible || view.diagnosticPopupItem == null || view.session == null) return -1;
        DiagnosticShift.Diagnostic d = view.diagnosticPopupItem;
        int line = view.session.getDocument().lineForOffset(d.start);
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(line);
        if (actions == null || actions.isEmpty()) return -1;
        // Géométrie partagée avec la passe de dessin (source unique de vérité).
        float[] m = view.diagnosticSheetMetrics();
        if (m == null) return -1;
        float panelTop = m[0], panelBottom = m[1], actionStartY = m[2], actionRowH = m[3];
        // Hors du panneau entièrement → tap sur le scrim (fermeture ; géré par l'appelant).
        if (y < panelTop || y > panelBottom) return -1;
        if (y < actionStartY || y > actionStartY + actions.size() * actionRowH) return -1;
        int row = (int) ((y - actionStartY) / actionRowH);
        if (row < 0 || row >= actions.size()) return -1;
        return row;
    }

    /**
     * Vrai quand (x, y) touche le bouton FERMER de la fiche de diagnostic —
     * un tap là ferme la fiche (× du DiagnosticSheet de CodeAssist).
     */
    boolean hitTestDiagnosticSheetClose(float x, float y) {
        if (!view.diagnosticPopupVisible) return false;
        float[] m = view.diagnosticSheetMetrics();
        if (m == null) return false;
        float closeCx = m[4], closeCy = m[5], closeR = m[6];
        float dx = x - closeCx, dy = y - closeCy;
        return dx * dx + dy * dy <= closeR * closeR * 4f; // rayon tactile généreux de 2×
    }
}
