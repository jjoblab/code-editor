package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import java.util.List;

/**
 * Popup d'actions de code : scan des lignes visibles (une requête LSP
 * par ligne, déporté sur la lane « features » avec garde de génération),
 * ouverture/fermeture et application de l'action sélectionnée. Corps
 * déplacés d'EditorPopupManager à l'identique (adaptation des accès
 * délégués).
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
     * Le scan exige UNE requête LSP PAR ligne visible (3 s de timeout
     * chacune), toutes les 800 ms — impensable sur le thread UI. Le travail
     * vit hors de l'UI ; seule la dernière demande (génération courante)
     * publie son résultat.
     */
    void refreshCodeActions(int firstVisible, int lastVisible) {
        if (view.codeActionsResolver == null || view.session == null) {
            view.codeActionsByLine.clear();
            return;
        }
        final int gen = ++codeActionsGeneration;
        final String text = view.session.getText();
        final int first = firstVisible;
        final int last = lastVisible;
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            java.util.Map<Integer, List<EditorView.CodeAction>> found =
                    new java.util.HashMap<>();
            for (int line = first; line <= last; line++) {
                List<EditorView.CodeAction> actions;
                try {
                    actions = view.codeActionsResolver.resolve(text, line);
                } catch (Throwable t) {
                    actions = null;
                }
                if (actions != null && !actions.isEmpty()) {
                    found.put(line, actions);
                }
            }
            android.os.Handler h = view.getHandler();
            Runnable apply = () -> {
                if (gen != codeActionsGeneration || view.session == null) return;
                view.codeActionsByLine.clear();
                view.codeActionsByLine.putAll(found);
            };
            if (h != null) h.post(apply); else apply.run();
        });
    }

    void showCodeActions(int line) {
        if (!view.codeActionsByLine.containsKey(line)) return;
        view.codeActionsPopupLine = line;
        view.codeActionsSelected = 0;
        view.codeActionsPopupVisible = true;
        view.invalidate();
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
