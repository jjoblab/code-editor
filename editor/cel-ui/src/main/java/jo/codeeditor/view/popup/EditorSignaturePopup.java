package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

/**
 * Popup d'aide de signature : rafraîchissement (résolution LSP déportée
 * sur la lane « features » avec garde de génération), déclenchement
 * explicite et point d'entrée hôte. Corps déplacés d'EditorPopupManager
 * à l'identique (adaptation des accès délégués).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorSignaturePopup {

    private final EditorView view;

    EditorSignaturePopup(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Popup d'aide de signature
    // ════════════════════════════════════════════════════════════════

    void refreshSignatureHelpFromHost() {
        triggerSignatureHelp();
    }

    void dismissSignatureHelp() {
        sigHelpGeneration++;
        view.signatureHelpController.dismiss();
        view.signatureHelpVisible = false;
        view.signatureHelpData = null;
        view.invalidate();
    }

    /** Génération de la résolution signature help (annulation). */
    private volatile int sigHelpGeneration = 0;

    /**
     * Résolution DÉPORTÉE hors du thread UI. Un appel LSP (timeout 10 s !)
     * à CHAQUE édition dès que le curseur est entre parenthèses gèlerait
     * le clavier sur un serveur lent. La machine à états du contrôleur
     * (gate findCallOpen, dismissed, reset au changement d'appel)
     * s'exécute sur la lane « features » ; seule l'application visuelle
     * revient sur l'UI, garde de génération en main.
     */
    void refreshSignatureHelp() {
        if (view.session == null) return;
        int caret = view.session.getSelection().start;
        // Lot 1+3 (audit v3.41.0) : getText() AVANT le gate
        // faisait une copie complète du document sur le thread UI à
        // chaque rafraîchissement, même quand le caret n'est pas
        // dans un appel (le cas commun). Désormais on utilise la
        // vue live doc.charSequence() (rope, pas de copie) pour le
        // gate findCallOpen ; la copie String pour resolve() n'a
        // lieu que si le gate passe (autrement dit, seulement quand
        // on a réellement du travail à faire).
        CharSequence liveText = view.session.getDocument().charSequence();
        if (view.signatureHelpResolver != null) {
            // Gate locale O(1) : hors appel ou dismissed → rien à demander.
            int callOpen = jo.codeeditor.completion.SignatureHelpController.findCallOpen(liveText, caret);
            if (callOpen < 0 || view.signatureHelpController.isDismissed()) {
                if (view.signatureHelpVisible || view.signatureHelpData != null) {
                    view.signatureHelpVisible = false;
                    view.signatureHelpData = null;
                    view.invalidate();
                }
                return;
            }
            // Gate passé → on snapshot maintenant pour la résolution
            // asynchrone (resolve() a besoin d'une chaîne stable).
            final int gen = ++sigHelpGeneration;
            final String fText = view.session.getText();
            final int fCaret = caret;
            jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
                try {
                    // Le contrôleur invoque lui-même le resolver LSP (via
                    // son listener) — c'est CET appel bloquant qui vit
                    // maintenant en dehors du thread UI.
                    view.signatureHelpController.resolve(fText, fCaret);
                } catch (Exception e) {
                    jo.codeeditor.util.EditorLog.warn("EditorSignature",
                        "résolution de l'aide de signature échouée", e);
                }
                final jo.codeeditor.completion.SignatureHelpController.SignatureHelp help =
                        view.signatureHelpController.getHelp();
                final boolean hasSig = help != null && help.signatures != null
                        && !help.signatures.isEmpty();
                android.os.Handler h = view.getHandler();
                Runnable apply = () -> {
                    if (gen != sigHelpGeneration || view.session == null) return;
                    view.signatureHelpData = help;
                    view.signatureHelpVisible = hasSig;
                    view.invalidate();
                };
                if (h != null) h.post(apply); else apply.run();
            });
        } else {
            int callOpen = jo.codeeditor.completion.SignatureHelpController.findCallOpen(liveText, caret);
            if (callOpen < 0 || view.signatureHelpController.isDismissed()) {
                view.signatureHelpVisible = false;
                view.signatureHelpData = null;
            } else {
                // Pas de resolver : on peut faire la synthèse directement
                // sur la vue live (pas de thread parallèle, pas de copie).
                int paramIdx = jo.codeeditor.completion.SignatureHelpController
                    .activeParameterIndex(liveText, callOpen, caret);
                String name = extractFunctionName(liveText, callOpen);
                String label = name + "(…)";
                java.util.List<jo.codeeditor.completion.SignatureHelpController.Parameter> params =
                    java.util.Collections.singletonList(
                        new jo.codeeditor.completion.SignatureHelpController.Parameter(
                            "param " + paramIdx, ""));
                java.util.List<jo.codeeditor.completion.SignatureHelpController.Signature> sigs =
                    java.util.Collections.singletonList(
                        new jo.codeeditor.completion.SignatureHelpController.Signature(
                            label, "Active parameter: " + paramIdx, params, paramIdx));
                view.signatureHelpData = new jo.codeeditor.completion.SignatureHelpController.SignatureHelp(
                    sigs, 0, paramIdx);
                view.signatureHelpVisible = true;
            }
        }
        view.invalidate();
    }

    void triggerSignatureHelp() {
        if (view.session == null) return;
        view.signatureHelpController.triggerExplicit(view.session.getText(), view.session.getSelection().start);
        refreshSignatureHelp();
    }

    private static String extractFunctionName(CharSequence text, int callOpen) {
        if (callOpen <= 0) return "function";
        int end = callOpen;
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
                start--;
            } else {
                break;
            }
        }
        if (start >= end) return "function";
        return text.subSequence(start, end).toString();
    }
}
