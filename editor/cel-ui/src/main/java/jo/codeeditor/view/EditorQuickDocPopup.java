package jo.codeeditor.view;

/**
 * Popup de doc rapide (hover) : résolution LSP déportée hors du thread
 * UI avec garde de génération — la popup n'apparaît que quand le
 * contenu est prêt, l'ancre suit l'offset. Corps déplacés
 * d'EditorPopupManager à l'identique (adaptation des accès délégués).
 */
class EditorQuickDocPopup {

    private final EditorView view;

    EditorQuickDocPopup(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Popup de doc rapide
    // ════════════════════════════════════════════════════════════════

    /** Génération du quick-doc (annulation des résolutions). */
    private volatile int quickDocGeneration = 0;

    /**
     * La résolution hover LSP (jusqu'à 10 s) quitte le thread UI —
     * déclenchée par appui long / survol, elle gèlerait sinon toute
     * saisie tant que le serveur ne répond pas. La popup n'est montrée que
     * lorsque le contenu est prêt ; une frappe/édition entre-temps invalide
     * la livraison (génération).
     */
    void showQuickDoc(int offset) {
        if (view.session == null || view.quickDocResolver == null) return;
        // Parité Sora : pas de hover quand la complétion est
        // ouverte (le tooltip ne vole pas le focus à la popup).
        if (view.completionVisible) return;
        final String text = view.session.getText();
        final int gen = ++quickDocGeneration;
        final int fOffset = offset;
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            String rawDoc = null;
            try {
                rawDoc = view.quickDocResolver.resolve(text, fOffset);
            } catch (Throwable ignored) {
            }
            final String raw = rawDoc;
            final jo.codeeditor.doc.QuickDoc.QuickDocContent content =
                    (raw == null || raw.isEmpty())
                            ? null
                            : jo.codeeditor.doc.QuickDoc.parseQuickDoc(raw, view.session.getLanguage());
            android.os.Handler h = view.getHandler();
            Runnable apply = () -> {
                if (gen != quickDocGeneration || view.session == null) return;
                if (content == null || content.isEmpty()) {
                    dismissQuickDoc();
                    return;
                }
                view.quickDocContent = content;
                // Le popup SUIT le texte : on ne stocke plus des
                // coordonnées écran figées mais l'OFFSET d'ancrage — le
                // renderer recalcule X/Y à chaque frame (wrap/fold-aware)
                // et referme le popup quand la ligne sort du viewport
                // (parité Sora HoverWindow : suit le contenu, dismiss quand
                // l'ancre sort de la vue).
                view.quickDocAnchorOffset = fOffset;
                view.quickDocScrollY = 0f;
                view.quickDocVisible = true;
                view.invalidate();
            };
            if (h != null) h.post(apply); else apply.run();
        });
    }

    void dismissQuickDoc() {
        quickDocGeneration++;
        view.quickDocVisible = false;
        view.quickDocContent = null;
        view.invalidate();
    }
}
