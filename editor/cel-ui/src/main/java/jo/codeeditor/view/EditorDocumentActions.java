package jo.codeeditor.view;

import jo.codeeditor.view.popup.EditorPopupManager;

import java.util.List;

/**
 * Actions de document asynchrones — go-to-definition, formatage et
 * « organize imports ».
 *
 * <p>Responsabilités déplacées à l'identique depuis {@link EditorView} :</p>
 * <ul>
 *   <li><b>jumpToDefinition</b> — résolution hors du thread UI sur
 *       la voie « features » ({@code EditorTaskLanes}) ; cible unique dans le
 *       MÊME fichier → saut direct, sinon cibles multiples/inter-fichiers
 *       confiées au {@code OnDefinitionRequestedListener} de l'hôte ;</li>
 *   <li><b>formatDocument</b> — requête LSP formatting hors du thread UI,
 *       résultat appliqué en un seul step d'undo quand il arrive ;</li>
 *   <li><b>performOrganizeImports</b> — sollicite le provider d'actions de
 *       code avec {@code only = ["source.organizeImports"]} : UNE requête
 *       par document (les actions source ne dépendent pas de la ligne),
 *       puis l'applique localement ;</li>
 *   <li><b>performFixAll</b> — symétrique avec
 *       {@code only = ["source.fixAll"]} (visible pour l'hôte ; les serveurs
 *       actuels ne le proposent pas tous — le callback reçoit FALSE sans
 *       action trouvée).</li>
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
        jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
            List<jo.codeeditor.lang.model.DefinitionLocation> targets = null;
            try {
                targets = view.definitionResolver.resolve(text, sel.start);
            } catch (Exception e) {
                // Résolveur de définitions d'hôte défaillant : journalisé,
                // repli sur « introuvable ».
                jo.codeeditor.util.EditorLog.warn("EditorActions",
                    "résolution de la définition échouée", e);
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
        jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
            String formatted = null;
            try {
                formatted = view.formatterResolver.format(current);
            } catch (Exception e) {
                // Formateur d'hôte défaillant : journalisé, le document
                // reste inchangé (résultat null).
                jo.codeeditor.util.EditorLog.warn("EditorActions",
                    "formatage du document échoué", e);
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
                        // Application du format reformaté : si elle échoue,
                        // repli sur « non modifié » signalé au callback.
                    }
                }
                if (callback != null) callback.accept(changed);
            };
            android.os.Handler h = view.getHandler();
            if (h != null) h.post(apply); else apply.run();
        });
    }

    /**
     * ★ 3.74.0 — formate la SÉLECTION courante via le resolver de
     * formatage. La requête LSP rangeFormatting quitte le thread UI ; le
     * résultat est appliqué en un seul step d'undo quand il arrive.
     *
     * <p>Refus propre sans capacité de plage (le document entier n'est
     * JAMAIS formaté à la place) ; sélection vide = no-op.</p>
     *
     * @param callback exécuté sur le thread UI après application :
     *                 {@code Boolean.TRUE} si le texte a changé, FALSE
     *                 sinon (refus/sélection vide/aucune édition), null
     *                 si aucun formatteur. Peut être null.
     */
    void formatSelection(java.util.function.Consumer<Boolean> callback) {
        if (view.formatterResolver == null || view.session == null) {
            if (callback != null) callback.accept(null);
            return;
        }
        if (!view.formatterResolver.supportsRange()) {
            // Refus propre : sans capacité de plage, ne rien modifier —
            // formater le document entier à la place serait une surprise
            // destructive.
            if (callback != null) callback.accept(Boolean.FALSE);
            return;
        }
        jo.codeeditor.document.Selection sel = view.session.getSelection();
        if (sel == null || sel.start >= sel.end) {
            // Sélection vide (caret simple) : no-op documenté.
            if (callback != null) callback.accept(Boolean.FALSE);
            return;
        }
        final String current = view.session.getText().toString();
        // Bornage défensif de la plage sélectionnée.
        final int start = Math.max(0, Math.min(sel.start, current.length()));
        final int end = Math.max(start, Math.min(sel.end, current.length()));
        // Sélection (bornes d'origine) conservée après application.
        final int selStart = start;
        final int selEnd = end;
        jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
            String formatted = null;
            try {
                formatted = view.formatterResolver.formatRange(current, start, end);
            } catch (Exception e) {
                // Formateur d'hôte défaillant : journalisé, le document
                // reste inchangé (résultat null).
                jo.codeeditor.util.EditorLog.warn("EditorActions",
                    "formatage de la sélection échoué", e);
            }
            final String result = formatted;
            Runnable apply = () -> {
                boolean changed = false;
                if (result != null && !result.equals(current) && view.session != null) {
                    try {
                        // UN seul pas d'undo : une seule mutation remplace
                        // le document (plis/diagnostics décalés par la voie
                        // commune, EditorSession.replaceRange).
                        view.session.replaceRange(0, view.session.getDocument().length(), result);
                        // Sélection et caret conservés, bornés au nouveau texte.
                        view.session.setSelection(new jo.codeeditor.document.Selection(
                                Math.min(selStart, result.length()),
                                Math.min(selEnd, result.length())));
                        view.invalidate();
                        changed = true;
                    } catch (Exception ignored) {
                        // Application du format reformaté : si elle échoue,
                        // repli sur « non modifié » signalé au callback.
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
     * <p>P2-10 : UNE requête par document avec
     * {@code only = ["source.organizeImports"]} — les actions source ne
     * dépendent pas de la ligne visée (le serveur CodeIDE les propose pour
     * toute plage), le filtre de famille voyage dans le contexte LSP au
     * lieu d'être appliqué localement sur un sondage multi-lignes.</p>
     *
     * @param callback thread UI : TRUE si une action a été trouvée+exécutée,
     *                 FALSE sinon (aucun provider / action absente).
     */
    void performOrganizeImports(java.util.function.Consumer<Boolean> callback) {
        performSourceAction("source.organizeImports", callback);
    }

    /**
     * Exécute l'action « source.fixAll » (toutes les corrections
     * automatiques du document) — même contrat que
     * {@link #performOrganizeImports} : UNE requête par document avec
     * {@code only = ["source.fixAll"]}, résolution en arrière-plan,
     * application locale de l'édition retournée.
     *
     * <p>Réservée à l'hôte (menu, activité) : les serveurs Java actuels
     * (CodeIDE inclus) ne proposent pas encore {@code source.fixAll} —
     * l'API est prête pour quand ils l'ajouteront, sans exiger d'entrée
     * de menu morte entre-temps.</p>
     *
     * @param callback thread UI : TRUE si une action a été trouvée+exécutée,
     *                 FALSE sinon.
     * @since 3.51.0
     */
    void performFixAll(java.util.function.Consumer<Boolean> callback) {
        performSourceAction("source.fixAll", callback);
    }

    /**
     * Cœur commun des actions source : UNE requête au document avec la
     * famille demandée en {@code only} (la plage — début de document — est
     * indifférente pour les actions source), puis application locale de
     * la première action retournée.
     */
    private void performSourceAction(String onlyKind,
            java.util.function.Consumer<Boolean> callback) {
        jo.codeeditor.lang.provider.CodeActionsProvider provider =
                view.language != null ? view.language.getCodeActionsProvider() : null;
        if (provider == null || view.session == null) {
            if (callback != null) callback.accept(false);
            return;
        }
        final String text = view.session.getText();
        final jo.codeeditor.lang.provider.CodeActionsProvider fProvider = provider;
        jo.codeeditor.view.EditorTaskLanes.submitFeature(() -> {
            List<jo.codeeditor.lang.model.CodeAction> found = new java.util.ArrayList<>();
            try {
                // UNE requête par document : la plage (début de document)
                // est indifférente aux actions source ; le filtre de
                // famille voyage dans le contexte LSP (only).
                for (jo.codeeditor.lang.model.CodeAction a : fProvider.codeActions(
                        text, 0, 0, 0, List.of(onlyKind))) {
                    if (!found.contains(a)) found.add(a);
                }
            } catch (Exception e) {
                jo.codeeditor.util.EditorLog.warn("EditorActions",
                    "énumération des actions source échouée", e);
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
                    } catch (Exception e) {
                        jo.codeeditor.util.EditorLog.warn("EditorActions",
                            "application d'une action source échouée", e);
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
