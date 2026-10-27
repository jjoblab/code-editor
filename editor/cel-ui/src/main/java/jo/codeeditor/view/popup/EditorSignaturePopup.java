package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import jo.codeeditor.ui.R;

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
                // 4.8.0 — le repli synthétique est BORNÉ aux langages de
                // code (drapeau callSyntax du profil). Avant, « foo(bar »
                // dans n'importe quel fichier — XML, Markdown, prose —
                // affichait un « name(…) param N » générique. Un
                // RÉSOLVEUR branché (hôte ou LSP) reste maître : la
                // porte ne borne que la synthèse de la bibliothèque.
                jo.codeeditor.languages.LanguageProfile profile =
                    jo.codeeditor.languages.LanguageRegistry.forName(
                        view.session.getLanguage());
                if (profile == null || !profile.callSyntax) {
                    view.signatureHelpVisible = false;
                    view.signatureHelpData = null;
                } else {
                    int paramIdx = jo.codeeditor.completion.SignatureHelpController
                        .activeParameterIndex(liveText, callOpen, caret);
                    String name = extractFunctionName(liveText, callOpen);
                    // 4.8.0 — l'appel VIVANT remplace le « name(…) »
                    // muet : l'utilisateur voit ses propres arguments,
                    // celui du paramètre actif ressort en surbrillance
                    // (le peintre surligne la plage du paramètre actif
                    // du libellé — mêmes règles que les signatures
                    // servies par un résolveur). Un paramètre par
                    // argument FRAPPÉ : la bande de documentation du
                    // peintre indexe parameters[activeParameter].
                    String args = liveArgsSnippet(liveText, callOpen);
                    String label = name + "(" + args + ")";
                    java.util.List<jo.codeeditor.completion.SignatureHelpController.Parameter> params =
                        splitLiveArgs(args, paramIdx, view);
                    java.util.List<jo.codeeditor.completion.SignatureHelpController.Signature> sigs =
                        java.util.Collections.singletonList(
                            new jo.codeeditor.completion.SignatureHelpController.Signature(
                                label, "", params, Math.min(paramIdx, params.size() - 1)));
                    view.signatureHelpData = new jo.codeeditor.completion.SignatureHelpController.SignatureHelp(
                        sigs, 0, Math.min(paramIdx, params.size() - 1));
                    view.signatureHelpVisible = true;
                }
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

    /**
     * Extrait les arguments de l'appel vivant — de la parenthèse
     * ouvrante à sa FERMANTE APPARIÉE (l'appel complet du buffer,
     * pas seulement ce qui précède le caret) — pour le libellé du
     * repli synthétique : espaces aplatis, plafonné. Un appel qui
     * court sur plusieurs lignes ne fabrique pas un libellé
     * interminable (le peintre ellipse de toute façon, la borne garde
     * la synthèse elle-même honnête).
     */
    private static String liveArgsSnippet(CharSequence text, int callOpen) {
        int end = callOpen + 1;
        int depth = 1; // la parenthèse ouvrante de l'appel compte
        int limit = Math.min(text.length(), callOpen + 1 + 80);
        while (end < limit) {
            char c = text.charAt(end);
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c == ' ' && end > callOpen + 1 && text.charAt(end - 1) == ' ') {
                end++; // espaces aplatis
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) break; // fermente de l'appel — exclusive
            }
            end++;
        }
        StringBuilder sb = new StringBuilder();
        boolean lastWs = true;
        for (int i = callOpen + 1; i < end; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') c = ' ';
            if (c == ' ' && lastWs) continue;
            sb.append(c);
            lastWs = (c == ' ');
            if (sb.length() >= 60) break;
        }
        String s = sb.toString().trim();
        if (end >= limit && s.length() >= 60) return s + "…";
        return s;
    }

    /**
     * Découpe les arguments de l'appel vivant en paramètres (un par
     * argument, virgules de profondeur 0 — les génériques
     * {@code Map<K, V>} ne coupent pas). La documentation du paramètre
     * ACTIF porte son numéro (chaîne cel_signature_active_param,
     * 1-based) ; les autres restent muets.
     */
    private static java.util.List<jo.codeeditor.completion.SignatureHelpController.Parameter> splitLiveArgs(
            String args, int activeIdx, EditorView view) {
        java.util.List<jo.codeeditor.completion.SignatureHelpController.Parameter> out =
            new java.util.ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || c == '>') depth--;
            else if (c == ',' && depth == 0) {
                out.add(liveArgParameter(args, start, i, out.size()));
                start = i + 1;
            }
        }
        out.add(liveArgParameter(args, start, args.length(), out.size()));
        // La documentation porte sur le paramètre ACTIF — borné au
        // dernier (un caret au-delà du dernier argument frappé, cas
        // d'un extrait plafonné, documente ce dernier).
        int docIdx = Math.max(0, Math.min(activeIdx, out.size() - 1));
        jo.codeeditor.completion.SignatureHelpController.Parameter actif = out.get(docIdx);
        out.set(docIdx, new jo.codeeditor.completion.SignatureHelpController.Parameter(
                actif.label,
                view.getResources().getString(
                        R.string.cel_signature_active_param, docIdx + 1)));
        return out;
    }

    /** Un paramètre de l'appel vivant : libellé = l'argument tel que
     *  frappé (son texte, ou « arg N » si vide), sans documentation —
     *  la doc est posée ensuite sur le seul paramètre actif. */
    private static jo.codeeditor.completion.SignatureHelpController.Parameter liveArgParameter(
            String args, int start, int end, int index) {
        String label = args.substring(start, end).trim();
        if (label.isEmpty()) label = "arg " + (index + 1);
        return new jo.codeeditor.completion.SignatureHelpController.Parameter(label, "");
    }
}
