package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

/**
 * Popup de doc rapide (hover) : résolution LSP déportée hors du thread
 * UI avec garde de génération — la popup n'apparaît que quand le
 * contenu est prêt, l'ancre suit l'offset. Corps déplacés
 * d'EditorPopupManager à l'identique (adaptation des accès délégués).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorQuickDocPopup {

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
        // Pas de hover quand la complétion est
        // ouverte (le tooltip ne vole pas le focus à la popup).
        if (view.completion.visible) return;
        final String text = view.session.getText();
        final int gen = ++quickDocGeneration;
        final int fOffset = offset;
        jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
            String rawDoc = null;
            try {
                rawDoc = view.quickDocResolver.resolve(text, fOffset);
            } catch (Exception e) {
                jo.codeeditor.util.EditorLog.warn("EditorQuickDoc",
                    "résolution de la documentation échouée", e);
            }
            final String raw = rawDoc;
            applyQuickDoc(fOffset, gen, raw);
        });
    }

    /**
     * Affiche le quick doc avec un texte brut FOURNI (I2, lot 2 J3) —
     * le tooltip d'un inlay hint, résolu par l'appelant (tooltip direct
     * du serveur ou {@code resolveHint} paresseux). Réutilise TOUT le
     * pipeline (parsing, ancrage par offset, suivi du texte,
     * fermetures) : zéro nouveau chrome. Thread-safe (la livraison se
     * poste sur le handler UI si la vue est attachée) ; une nouvelle
     * demande invalide toute livraison en cours (génération).
     */
    void showQuickDocRaw(int offset, String raw) {
        final int gen = ++quickDocGeneration;
        applyQuickDoc(offset, gen, raw);
    }

    /**
     * Applique un texte de quick doc déjà résolu : parsing, garde de
     * génération, ancrage par offset. Appelé depuis la voie « features »
     * (résolveur de quick doc OU tooltip d'inlay hint).
     */
    void applyQuickDoc(int offset, int gen, String raw) {
        if (view.session == null) return;
        if (view.completion.visible) return;
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
            // (le popup suit le contenu et se referme quand
            // l'ancre sort de la vue).
            view.quickDocAnchorOffset = offset;
            view.quickDocScrollY = 0f;
            view.quickDocVisible = true;
            view.invalidate();
        };
        if (h != null) h.post(apply); else apply.run();
    }

    void dismissQuickDoc() {
        quickDocGeneration++;
        // B7 : photographie le popup AVANT de vider son contenu — le
        // fantôme s'efface en 110 ms (fondus d'apparition/disparition).
        view.popupFade.snuff(jo.codeeditor.view.popup.EditorPopupFade.Kind.QUICK_DOC);
        view.quickDocVisible = false;
        view.quickDocContent = null;
        view.invalidate();
    }
}
