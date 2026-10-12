package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import java.util.List;

/**
 * Popup d'actions de code : résolution À LA DEMANDE au point
 * d'invocation (UNE requête par demande, déportée sur la lane
 * « features » avec garde de génération), ouverture/fermeture et
 * application de l'action sélectionnée. Le balayage d'une requête par
 * ligne visible a disparu : l'ampoule de gouttière est pilotée par les
 * diagnostics, le contenu du popup est calculé quand l'utilisateur
 * demande les actions (raccourci, tap, menu).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorCodeActionsPopup {

    private final EditorView view;

    EditorCodeActionsPopup(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Popup d'actions de code
    // ════════════════════════════════════════════════════════════════

    /** Génération du scan d'actions de code (annulation). */
    private volatile int codeActionsGeneration = 0;

    /**
     * Résout les actions au point d'ancrage donné (typiquement le caret,
     * ou le début du diagnostic visé) — UNE requête par appel, hors du
     * thread UI ; seule la dernière demande (génération courante)
     * publie son résultat dans le cache mono-ligne et, si
     * {@code openPopup}, ouvre le popup.
     */
    void resolveCodeActionsAt(int anchorOffset, int selectionStart, int selectionEnd,
            boolean openPopup) {
        if (view.codeActionsResolver == null || view.session == null) {
            view.codeActionsByLine.clear();
            if (openPopup) view.codeActionsPopupVisible = false;
            return;
        }
        final int gen = ++codeActionsGeneration;
        final String text = view.session.getText().toString();
        final int line = EditorView.clamp(
                view.session.getDocument().lineForOffset(anchorOffset),
                0, view.session.getDocument().lineCount() - 1);
        jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
            List<EditorView.CodeAction> actions = null;
            try {
                actions = view.codeActionsResolver.resolveAtCaret(
                        text, anchorOffset, selectionStart, selectionEnd);
            } catch (Exception t) {
                jo.codeeditor.util.EditorLog.warn("EditorCodeActions",
                        "résolution des actions de code échouée", t);
            }
            final List<EditorView.CodeAction> found = actions;
            android.os.Handler h = view.getHandler();
            Runnable apply = () -> {
                if (gen != codeActionsGeneration || view.session == null) return;
                // Cache mono-ligne : remplacé à CHAQUE résolution — pas
                // de restes d'une invocation précédente.
                view.codeActionsByLine.clear();
                boolean hasActions = found != null && !found.isEmpty();
                if (hasActions) view.codeActionsByLine.put(line, found);
                if (openPopup) {
                    if (hasActions) {
                        view.codeActionsPopupLine = line;
                        view.codeActionsSelected = 0;
                        view.codeActionsPopupVisible = true;
                    } else {
                        view.codeActionsPopupVisible = false;
                    }
                }
                view.invalidate();
            };
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /**
     * Ouvre le popup d'actions pour la ligne demandée : l'ancre est le
     * début du diagnostic le plus sévère de la ligne (la plage visée est
     * son mot), sinon le début de ligne. Le contenu est résolu à la
     * demande — le popup n'apparaît que si des actions existent.
     */
    void showCodeActions(int line) {
        if (view.session == null) return;
        jo.codeeditor.document.EditorDocument doc = view.session.getDocument();
        int borne = EditorView.clamp(line, 0, doc.lineCount() - 1);
        int anchor = doc.lineStart(borne);
        jo.codeeditor.shift.DiagnosticShift.Diagnostic d = view.findDiagnosticAtLine(borne);
        if (d != null) anchor = d.start;
        resolveCodeActionsAt(anchor, anchor, anchor, true);
    }

    void dismissCodeActions() {
        view.codeActionsPopupVisible = false;
        view.codeActionsPopupLine = -1;
        view.invalidate();
    }

    boolean applySelectedCodeAction() {
        if (!view.codeActionsPopupVisible || view.codeActionsPopupLine < 0) return false;
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(view.codeActionsPopupLine);
        if (actions == null || view.codeActionsSelected < 0 || view.codeActionsSelected >= actions.size()) {
            return false;
        }
        EditorView.CodeAction a = actions.get(view.codeActionsSelected);
        dismissCodeActions();
        if (a.apply != null) a.apply.run();
        return true;
    }
}
