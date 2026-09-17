package jo.codeeditor.view;

import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Gestionnaire de popups, extrait d'EditorView — coordonne l'ensemble des
 * popups de l'éditeur : complétion, aide de signature, doc rapide, actions
 * de code, menu contextuel unifié, aller-au-symbole, aller-à-la-ligne,
 * renommage, popup de diagnostic, fiche de diagnostic.
 *
 * <p>Depuis la refactorisation par composition, chaque popup vit dans son
 * propre collaborateur package-privé du même package (regroupement pensé
 * pour un futur sous-package {@code view/popup/}) :
 * {@link EditorCompletionPopup}, {@link EditorSignaturePopup},
 * {@link EditorQuickDocPopup}, {@link EditorCodeActionsPopup},
 * {@link EditorNavMenuPopup}, {@link EditorGoToSymbolPopup},
 * {@link EditorGoToLinePopup}, {@link EditorRenamePopup},
 * {@link EditorDiagnosticPopups} — plus la carte à champ texte partagée
 * {@link EditorGlassCards}. Cette classe reste le point d'entrée unique :
 * les corps ont été déplacés à l'identique, elle délègue et centralise la
 * fermeture croisée des popups (les collaborateurs rappellent le
 * gestionnaire via leur référence {@code popups}).</p>
 *
 * <p>Chaque popup possède une méthode {@code show()}, {@code dismiss()},
 * et (le cas échéant) {@code hitTest()} et {@code accept()}. Les champs
 * d'état des popups (drapeaux de visibilité, index sélectionné, offset de
 * scroll, etc.) vivent dans EditorView ; le dessin Canvas proprement dit
 * reste dans {@link EditorRenderer} et lit l'état via la référence
 * {@code view}.</p>
 *
 * <p>EditorView délègue son API publique de popups à cette classe :
 * <pre>
 *   public void showGoToSymbol() { popupManager.showGoToSymbol(); }
 *   public void dismissGoToSymbol() { popupManager.dismissGoToSymbol(); }
 *   ...
 * </pre>
 */
class EditorPopupManager {

    /** Complétion — collaborateur extrait (corps déplacés à l'identique). */
    private final EditorCompletionPopup completion;
    /** Aide de signature — collaborateur extrait (corps déplacés à l'identique). */
    private final EditorSignaturePopup signature;
    /** Doc rapide — collaborateur extrait (corps déplacés à l'identique). */
    private final EditorQuickDocPopup quickDoc;
    /** Actions de code — collaborateur extrait (corps déplacés à l'identique). */
    private final EditorCodeActionsPopup codeActions;
    /** Menu contextuel unifié — collaborateur extrait (corps déplacés à l'identique). */
    private final EditorNavMenuPopup navMenu;
    /** Aller-au-symbole — collaborateur extrait (corps déplacés à l'identique). */
    private final EditorGoToSymbolPopup goToSymbol;
    /** Aller-à-la-ligne — collaborateur extrait (corps déplacés à l'identique). */
    private final EditorGoToLinePopup goToLine;
    /** Renommage — collaborateur extrait (corps déplacés à l'identique). */
    private final EditorRenamePopup rename;
    /** Popups de diagnostic — collaborateur extrait (corps déplacés à l'identique). */
    private final EditorDiagnosticPopups diagnostics;

    EditorPopupManager(EditorView view) {
        this.completion = new EditorCompletionPopup(view, this);
        this.signature = new EditorSignaturePopup(view);
        this.quickDoc = new EditorQuickDocPopup(view);
        this.codeActions = new EditorCodeActionsPopup(view);
        this.navMenu = new EditorNavMenuPopup(view);
        this.goToSymbol = new EditorGoToSymbolPopup(view);
        this.goToLine = new EditorGoToLinePopup(view);
        this.rename = new EditorRenamePopup(view);
        this.diagnostics = new EditorDiagnosticPopups(view, this);
    }

    // ── Exécuteurs dédiés (modèle CodeAssist) ─────────────────────

    /**
     * Lane « features » : signature help, quick doc… Isolée de la lane
     * complétion. Les tâches planifiées par EditorView (highlights, inlays,
     * code actions) y sont aussi soumises.
     */
    static final java.util.concurrent.ExecutorService FEATURE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "code-editor-features");
                t.setDaemon(true);
                return t;
            });

    // ════════════════════════════════════════════════════════════════
    // Completion popup — corps délégués à EditorCompletionPopup
    // ════════════════════════════════════════════════════════════════

    void setCompletionItems(List<jo.codeeditor.completion.CompletionSession.Item> items,
                            int tokenStart, String prefix) {
        completion.setCompletionItems(items, tokenStart, prefix);
    }

    boolean isCompletionVisible() { return completion.isCompletionVisible(); }

    void dismissCompletion() { completion.dismissCompletion(); }

    boolean completionSelectUp() { return completion.completionSelectUp(); }

    boolean completionSelectDown() { return completion.completionSelectDown(); }

    boolean completionAccept() { return completion.completionAccept(); }

    void refreshCompletion() { completion.refreshCompletion(); }

    void refreshCompletionFromHost() { completion.refreshCompletionFromHost(); }

    int completionRowsVisible() { return completion.completionRowsVisible(); }

    float[] completionPopupAnchor() { return completion.completionPopupAnchor(); }

    // ════════════════════════════════════════════════════════════════
    // Popup d'aide de signature — corps délégués à EditorSignaturePopup
    // ════════════════════════════════════════════════════════════════

    void refreshSignatureHelpFromHost() { signature.refreshSignatureHelpFromHost(); }

    void dismissSignatureHelp() { signature.dismissSignatureHelp(); }

    void refreshSignatureHelp() { signature.refreshSignatureHelp(); }

    void triggerSignatureHelp() { signature.triggerSignatureHelp(); }

    // ════════════════════════════════════════════════════════════════
    // Popup de doc rapide — corps délégués à EditorQuickDocPopup
    // ════════════════════════════════════════════════════════════════

    void showQuickDoc(int offset) { quickDoc.showQuickDoc(offset); }

    void dismissQuickDoc() { quickDoc.dismissQuickDoc(); }

    // ════════════════════════════════════════════════════════════════
    // Popup d'actions de code — corps délégués à EditorCodeActionsPopup
    // ════════════════════════════════════════════════════════════════

    void refreshCodeActions(int firstVisible, int lastVisible) {
        codeActions.refreshCodeActions(firstVisible, lastVisible);
    }

    void showCodeActions(int line) { codeActions.showCodeActions(line); }

    void dismissCodeActions() { codeActions.dismissCodeActions(); }

    boolean applySelectedCodeAction() { return codeActions.applySelectedCodeAction(); }

    // ════════════════════════════════════════════════════════════════
    // Menu contextuel unifié — corps délégués à EditorNavMenuPopup
    // ════════════════════════════════════════════════════════════════

    void showNavMenu(int line, int offset) { navMenu.showNavMenu(line, offset); }

    void dismissNavMenu() { navMenu.dismissNavMenu(); }

    void navMenuPickOption(NavigationMenu.NavOption option) {
        navMenu.navMenuPickOption(option);
    }

    void navMenuPickAction(EditorView.CodeAction action) {
        navMenu.navMenuPickAction(action);
    }

    void navMenuPickTarget(NavigationMenu.NavTarget target) {
        navMenu.navMenuPickTarget(target);
    }

    // ════════════════════════════════════════════════════════════════
    // Popup aller-au-symbole — corps délégués à EditorGoToSymbolPopup
    // ════════════════════════════════════════════════════════════════

    void showGoToSymbol() { goToSymbol.showGoToSymbol(); }

    void dismissGoToSymbol() { goToSymbol.dismissGoToSymbol(); }

    void setGoToSymbolFilter(String filter) { goToSymbol.setGoToSymbolFilter(filter); }

    boolean goToSymbolSelect(int delta) { return goToSymbol.goToSymbolSelect(delta); }

    boolean goToSymbolAccept() { return goToSymbol.goToSymbolAccept(); }

    // ════════════════════════════════════════════════════════════════
    // Popup aller-à-la-ligne — corps délégués à EditorGoToLinePopup
    // ════════════════════════════════════════════════════════════════

    void showGoToLine() { goToLine.showGoToLine(); }

    void acceptGoToLineInput(String input) { goToLine.acceptGoToLineInput(input); }

    void dismissGoToLine() { goToLine.dismissGoToLine(); }

    boolean isGoToLineVisible() { return goToLine.isGoToLineVisible(); }

    @Deprecated
    void setGoToLineText(String text) { goToLine.setGoToLineText(text); }

    @Deprecated
    boolean acceptGoToLine() { return goToLine.acceptGoToLine(); }

    // ════════════════════════════════════════════════════════════════
    // Popup de renommage — corps délégués à EditorRenamePopup
    // ════════════════════════════════════════════════════════════════

    void showRename() { rename.showRename(); }

    boolean acceptRenameInput(String newName) { return rename.acceptRenameInput(newName); }

    void dismissRename() { rename.dismissRename(); }

    boolean isRenameVisible() { return rename.isRenameVisible(); }

    @Deprecated
    void setRenameText(String text) { rename.setRenameText(text); }

    @Deprecated
    boolean acceptRename() { return rename.acceptRename(); }

    // ════════════════════════════════════════════════════════════════
    // Popup + fiche de diagnostic — corps délégués à EditorDiagnosticPopups
    // ════════════════════════════════════════════════════════════════

    void showDiagnosticSheet() { diagnostics.showDiagnosticSheet(); }

    void dismissDiagnosticSheet() { diagnostics.dismissDiagnosticSheet(); }

    boolean isDiagnosticSheetVisible() { return diagnostics.isDiagnosticSheetVisible(); }

    void showDiagnosticPopup(DiagnosticShift.Diagnostic diag, int offset) {
        diagnostics.showDiagnosticPopup(diag, offset);
    }

    void dismissDiagnosticPopup() { diagnostics.dismissDiagnosticPopup(); }

    void showDiagnosticListSheet(int line) { diagnostics.showDiagnosticListSheet(line); }

    void dismissDiagnosticListSheet() { diagnostics.dismissDiagnosticListSheet(); }

}
