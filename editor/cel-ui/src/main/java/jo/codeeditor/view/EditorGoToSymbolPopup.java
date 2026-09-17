package jo.codeeditor.view;

import jo.codeeditor.navigation.NavigationMenu;

import java.util.ArrayList;

/**
 * Popup aller-au-symbole : résolution documentSymbol déportée sur la
 * lane « features » avec garde de génération, filtre incrémental et
 * navigation à l'acceptation. Corps déplacés d'EditorPopupManager à
 * l'identique (adaptation des accès délégués).
 */
class EditorGoToSymbolPopup {

    private final EditorView view;

    EditorGoToSymbolPopup(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Popup aller-au-symbole
    // ════════════════════════════════════════════════════════════════

    /** Génération du documentSymbol (annulation). */
    private volatile int symbolGeneration = 0;

    /**
     * La résolution documentSymbol (3 s LSP) quitte le thread UI — la
     * popup s'ouvre sur le résultat quand il arrive.
     */
    void showGoToSymbol() {
        if (view.session == null || view.symbolResolver == null) return;
        final int gen = ++symbolGeneration;
        final String text = view.session.getText();
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            java.util.List<NavigationMenu.Symbol> symbols = null;
            try {
                symbols = view.symbolResolver.resolve(text);
            } catch (Throwable ignored) {
            }
            final java.util.List<NavigationMenu.Symbol> all =
                    symbols != null ? symbols : new ArrayList<>();
            android.os.Handler h = view.getHandler();
            Runnable apply = () -> {
                if (gen != symbolGeneration || view.session == null) return;
                view.goToSymbolAll = all;
                view.goToSymbolFilter = "";
                view.goToSymbolFiltered = new ArrayList<>(view.goToSymbolAll);
                view.goToSymbolSelected = 0;
                view.goToSymbolScrollOffset = 0;
                view.goToSymbolVisible = !view.goToSymbolFiltered.isEmpty();
                view.invalidate();
            };
            if (h != null) h.post(apply); else apply.run();
        });
    }

    void dismissGoToSymbol() {
        view.goToSymbolVisible = false;
        view.invalidate();
    }

    void setGoToSymbolFilter(String filter) {
        view.goToSymbolFilter = filter == null ? "" : filter;
        view.goToSymbolFiltered = NavigationMenu.filter(view.goToSymbolAll, view.goToSymbolFilter);
        view.goToSymbolSelected = 0;
        view.goToSymbolScrollOffset = 0;
        view.invalidate();
    }

    boolean goToSymbolSelect(int delta) {
        if (!view.goToSymbolVisible || view.goToSymbolFiltered.isEmpty()) return false;
        view.goToSymbolSelected = Math.max(0, Math.min(view.goToSymbolFiltered.size() - 1,
            view.goToSymbolSelected + delta));
        if (view.goToSymbolSelected < view.goToSymbolScrollOffset) {
            view.goToSymbolScrollOffset = view.goToSymbolSelected;
        } else if (view.goToSymbolSelected >= view.goToSymbolScrollOffset + view.GO_TO_SYMBOL_MAX_ROWS) {
            view.goToSymbolScrollOffset = view.goToSymbolSelected - view.GO_TO_SYMBOL_MAX_ROWS + 1;
        }
        view.invalidate();
        return true;
    }

    boolean goToSymbolAccept() {
        if (!view.goToSymbolVisible || view.goToSymbolSelected < 0
            || view.goToSymbolSelected >= view.goToSymbolFiltered.size()) {
            return false;
        }
        NavigationMenu.Symbol s = view.goToSymbolFiltered.get(view.goToSymbolSelected);
        int offset = EditorView.clamp(s.offset, 0, view.session.getDocument().length());
        view.session.expandFoldAt(offset);
        view.session.setSelection(offset);
        view.scrollToOffset(offset);
        dismissGoToSymbol();
        return true;
    }
}
