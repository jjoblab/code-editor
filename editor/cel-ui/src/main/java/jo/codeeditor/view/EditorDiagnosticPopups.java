package jo.codeeditor.view;

import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Popups de diagnostic : fiche détaillée (sheet), popup de détail ancré
 * à l'offset et fiche groupée par ligne (portage
 * diagnosticsByStartLine de CodeAssist). Ouverture exclusive : referme
 * les autres popups via le gestionnaire. Corps déplacés
 * d'EditorPopupManager à l'identique (adaptation des accès délégués).
 */
class EditorDiagnosticPopups {

    private final EditorView view;
    /** Référence au gestionnaire pour la fermeture croisée des popups. */
    private final EditorPopupManager popups;

    EditorDiagnosticPopups(EditorView view, EditorPopupManager popups) {
        this.view = view;
        this.popups = popups;
    }

    // ════════════════════════════════════════════════════════════════
    // Popup + fiche de diagnostic
    // ════════════════════════════════════════════════════════════════

    void showDiagnosticSheet() {
        view.diagnosticSheetVisible = true;
        view.diagnosticSheetScroll = 0;
        view.invalidate();
    }

    void dismissDiagnosticSheet() {
        view.diagnosticSheetVisible = false;
        view.invalidate();
    }

    boolean isDiagnosticSheetVisible() { return view.diagnosticSheetVisible; }

    void showDiagnosticPopup(DiagnosticShift.Diagnostic diag, int offset) {
        view.diagnosticPopupItem = diag;
        view.diagnosticPopupOffset = offset;
        view.diagnosticPopupVisible = true;
        // Ouvrir le popup de détail depuis la fiche de liste groupée ferme
        // cette fiche (chaîne chip → liste → détail).
        view.diagnosticListSheetLine = -1;
        if (view.completionVisible) popups.dismissCompletion();
        if (view.quickDocVisible) popups.dismissQuickDoc();
        if (view.signatureHelpVisible) popups.dismissSignatureHelp();
        if (view.codeActionsPopupVisible) popups.dismissCodeActions();
        // Le menu contextuel unifié ferme aussi (popup exclusif).
        if (view.navMenuVisible) popups.dismissNavMenu();
        view.invalidate();
    }

    void dismissDiagnosticPopup() {
        view.diagnosticPopupVisible = false;
        view.diagnosticPopupItem = null;
        view.diagnosticPopupOffset = -1;
        view.invalidate();
    }

    // ── Fiche de liste des diagnostics groupés ────

    /**
     * Ouvre la fiche groupée listant chaque diagnostic dont le début se
     * situe sur {@code line} (portage diagnosticsByStartLine de CodeAssist).
     * Une ligne avec un seul diagnostic ouvre directement le popup de
     * détail à la place.
     */
    void showDiagnosticListSheet(int line) {
        if (view.session == null) return;
        if (line < 0 || line >= view.session.getDocument().lineCount()) return;
        List<DiagnosticShift.Diagnostic> group =
                view.session.getDiagnosticsForLine(line);
        if (group.isEmpty()) return;
        if (group.size() == 1) {
            // Chemin rapide : un seul diagnostic — on saute la liste, ouverture du détail.
            showDiagnosticPopup(group.get(0), group.get(0).start);
            return;
        }
        view.diagnosticListSheetLine = line;
        if (view.completionVisible) popups.dismissCompletion();
        if (view.quickDocVisible) popups.dismissQuickDoc();
        if (view.signatureHelpVisible) popups.dismissSignatureHelp();
        if (view.codeActionsPopupVisible) popups.dismissCodeActions();
        if (view.navMenuVisible) popups.dismissNavMenu();
        view.invalidate();
    }

    void dismissDiagnosticListSheet() {
        view.diagnosticListSheetLine = -1;
        view.invalidate();
    }
}
