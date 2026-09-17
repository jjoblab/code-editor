package jo.codeeditor.view;

import jo.codeeditor.view.popup.EditorPopupManager;

import jo.codeeditor.document.EditorDocument;

import java.util.List;

/**
 * Actions de document asynchrones — go-to-definition, formatage et
 * « organize imports ».
 *
 * <p>Responsabilités déplacées à l'identique depuis {@link EditorView} :</p>
 * <ul>
 *   <li><b>jumpToDefinition</b> — résolution hors du thread UI sur
 *       {@code EditorPopupManager.FEATURE_EXECUTOR} ; cible unique dans le
 *       MÊME fichier → saut direct, sinon cibles multiples/inter-fichiers
 *       confiées au {@code OnDefinitionRequestedListener} de l'hôte ;</li>
 *   <li><b>formatDocument</b> — requête LSP formatting hors du thread UI,
 *       résultat appliqué en un seul step d'undo quand il arrive ;</li>
 *   <li><b>performOrganizeImports</b> — sonde le provider d'actions de code
 *       autour du caret pour une action {@code source.*}, puis l'applique
 *       localement.</li>
 * </ul>
 *
 * <p>Le pattern commun « résoudre en arrière-plan, rappliquer via le
 * handler de la vue » est préservé à l'identique (repli direct
 * {@code run()} quand la vue n'a pas encore de handler). EditorView ne
 * conserve que les wrappers publics de délégation ; les résolveurs restent
 * des champs de la vue (lus aussi par {@link EditorNavMenuPopup}).</p>
 */
class EditorDocumentActions {

    private final EditorView view;

    EditorDocumentActions(EditorView view) {
        this.view = view;
    }

    /**
     * Déclenche le go-to-definition au caret courant. Si le resolver
     * retourne une cible unique dans le MÊME fichier, y navigue directement.
     * Si cibles multiples ou inter-fichiers, retourne la liste via le
     * {@link EditorView.OnDefinitionRequestedListener} (l'hôte décide de
     * l'affichage).
     *
     * <p>La résolution LSP quitte le thread UI — l'hôte peut afficher un
     * feedback « recherche… » immédiat et le résultat est appliqué dès
     * qu'il arrive.</p>
     */
    void jumpToDefinition() {
        if (view.definitionResolver == null || view.session == null) return;
        final jo.codeeditor.document.Selection sel = view.session.getSelection();
        final String text = view.session.getText().toString();
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            List<jo.codeeditor.lang.model.DefinitionLocation> targets = null;
            try {
                targets = view.definitionResolver.resolve(text, sel.start);
            } catch (Exception ignored) {
            }
            final List<jo.codeeditor.lang.model.DefinitionLocation> resolved = targets;
            Runnable apply = () -> {
                if (view.session == null) return;
                if (resolved == null || resolved.isEmpty()) return;
                if (resolved.size() == 1) {
                    jo.codeeditor.lang.model.DefinitionLocation loc = resolved.get(0);
                    if (loc.path == null || loc.path.isEmpty()
                        || loc.path.equals(view.currentFilePath)
                        || loc.path.equals("file:///" + view.currentFilePath)) {
                        // Même fichier — saut direct.
                        view.session.setSelection(loc.offset);
                        view.scrollManager.scrollCaretIntoView();
                        view.invalidate();
                    } else if (view.definitionListener != null) {
                        view.definitionListener.onDefinitionRequested(resolved);
                    }
                } else if (view.definitionListener != null) {
                    view.definitionListener.onDefinitionRequested(resolved);
                }
            };
            android.os.Handler h = view.getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /**
     * Formate tout le document via le resolver de formatage branché.
     * La requête LSP formatting quitte le thread UI ; le résultat est
     * appliqué en un seul step d'undo quand il arrive.
     *
     * @param callback exécuté sur le thread UI après application :
     *                 {@code Boolean.TRUE} si le texte a changé, FALSE sinon,
     *                 null si aucun formatteur. Peut être null.
     */
    void formatDocument(java.util.function.Consumer<Boolean> callback) {
        if (view.formatterResolver == null || view.session == null) {
            if (callback != null) callback.accept(null);
            return;
        }
        final String current = view.session.getText().toString();
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            String formatted = null;
            try {
                formatted = view.formatterResolver.format(current);
            } catch (Exception e) {
            }
            final String result = formatted;
            Runnable apply = () -> {
                boolean changed = false;
                if (result != null && !result.equals(current) && view.session != null) {
                    try {
                        int caret = view.session.getSelection().start;
                        view.session.replaceRange(0, view.session.getDocument().length(), result);
                        int newCaret = Math.min(caret, result.length());
                        view.session.setSelection(newCaret);
                        view.invalidate();
                        changed = true;
                    } catch (Exception ignored) {
                    }
                }
                if (callback != null) callback.accept(changed);
            };
            android.os.Handler h = view.getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /**
     * Exécute l'action « source.organizeImports » via le fournisseur
     * d'actions LSP (l'action est reconstruite par le serveur à la demande
     * et applique son WorkspaceEdit localement). Résout en arrière-plan —
     * le thread UI n'attend jamais.
     *
     * @param callback thread UI : TRUE si une action a été trouvée+exécutée,
     *                 FALSE sinon (aucun provider / action absente).
     */
    void performOrganizeImports(java.util.function.Consumer<Boolean> callback) {
        jo.codeeditor.lang.provider.CodeActionsProvider provider =
                view.language != null ? view.language.getCodeActionsProvider() : null;
        if (provider == null || view.session == null) {
            if (callback != null) callback.accept(false);
            return;
        }
        final int caretLine;
        try {
            caretLine = view.session.getDocument().lineForOffset(view.session.getSelection().start);
        } catch (Throwable t) {
            if (callback != null) callback.accept(false);
            return;
        }
        final String text = view.session.getText();
        final jo.codeeditor.lang.provider.CodeActionsProvider fProvider = provider;
        EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
            // Le serveur propose « Organiser les imports » quelle que soit
            // la ligne demandée ; on sonde autour du curseur par sécurité.
            EditorDocument doc = view.session != null ? view.session.getDocument() : null;
            int max = doc != null ? doc.lineCount() - 1 : caretLine;
            List<jo.codeeditor.lang.model.CodeAction> found = new java.util.ArrayList<>();
            int[] probes = {caretLine, 0};
            for (int probe : probes) {
                if (!found.isEmpty()) break;
                if (probe < 0 || probe > max) continue;
                try {
                    for (jo.codeeditor.lang.model.CodeAction a : fProvider.codeActions(text, probe)) {
                        String kind = a.kind == null ? "" : a.kind;
                        if (kind.contains("source") && !found.contains(a)) found.add(a);
                    }
                } catch (Throwable ignored) {
                }
            }
            final boolean anySourceAction = !found.isEmpty();
            Runnable apply = () -> {
                boolean applied = false;
                if (anySourceAction && view.session != null) {
                    try {
                        for (jo.codeeditor.lang.model.CodeAction a : found) {
                            a.apply.run();
                            applied = true;
                            break; // une seule action source suffit
                        }
                    } catch (Throwable ignored) {
                        applied = false;
                    }
                }
                if (callback != null) callback.accept(applied);
            };
            android.os.Handler h = view.getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    }
}
