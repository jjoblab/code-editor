package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

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
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorPopupManager {

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

    public EditorPopupManager(EditorView view) {
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
    public static final java.util.concurrent.ExecutorService FEATURE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "code-editor-features");
                t.setDaemon(true);
                return t;
            });

    // ════════════════════════════════════════════════════════════════
    // Completion popup — corps délégués à EditorCompletionPopup
    // ════════════════════════════════════════════════════════════════

    public void setCompletionItems(List<jo.codeeditor.completion.CompletionSession.Item> items,
                            int tokenStart, String prefix) {
        completion.setCompletionItems(items, tokenStart, prefix);
    }

    public boolean isCompletionVisible() { return completion.isCompletionVisible(); }

    public void dismissCompletion() { completion.dismissCompletion(); }

    public boolean completionSelectUp() { return completion.completionSelectUp(); }

    public boolean completionSelectDown() { return completion.completionSelectDown(); }

    public boolean completionAccept() { return completion.completionAccept(); }

    public void refreshCompletion() { completion.refreshCompletion(); }

    public void refreshCompletionFromHost() { completion.refreshCompletionFromHost(); }

    public int completionRowsVisible() { return completion.completionRowsVisible(); }

    public float[] completionPopupAnchor() { return completion.completionPopupAnchor(); }

    // ════════════════════════════════════════════════════════════════
    // Popup d'aide de signature — corps délégués à EditorSignaturePopup
    // ════════════════════════════════════════════════════════════════

    public void refreshSignatureHelpFromHost() { signature.refreshSignatureHelpFromHost(); }

    public void dismissSignatureHelp() { signature.dismissSignatureHelp(); }

    public void refreshSignatureHelp() { signature.refreshSignatureHelp(); }

    public void triggerSignatureHelp() { signature.triggerSignatureHelp(); }

    // ════════════════════════════════════════════════════════════════
    // Popup de doc rapide — corps délégués à EditorQuickDocPopup
    // ════════════════════════════════════════════════════════════════

    public void showQuickDoc(int offset) { quickDoc.showQuickDoc(offset); }

    public void dismissQuickDoc() { quickDoc.dismissQuickDoc(); }

    // ════════════════════════════════════════════════════════════════
    // Popup d'actions de code — corps délégués à EditorCodeActionsPopup
    // ════════════════════════════════════════════════════════════════

    public void refreshCodeActions(int firstVisible, int lastVisible) {
        codeActions.refreshCodeActions(firstVisible, lastVisible);
    }

    public void showCodeActions(int line) { codeActions.showCodeActions(line); }

    public void dismissCodeActions() { codeActions.dismissCodeActions(); }

    public boolean applySelectedCodeAction() { return codeActions.applySelectedCodeAction(); }

    // ════════════════════════════════════════════════════════════════
    // Menu contextuel unifié — corps délégués à EditorNavMenuPopup
    // ════════════════════════════════════════════════════════════════

    public void showNavMenu(int line, int offset) { navMenu.showNavMenu(line, offset); }

    public void dismissNavMenu() { navMenu.dismissNavMenu(); }

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

    public void showGoToSymbol() { goToSymbol.showGoToSymbol(); }

    public void dismissGoToSymbol() { goToSymbol.dismissGoToSymbol(); }

    public void setGoToSymbolFilter(String filter) { goToSymbol.setGoToSymbolFilter(filter); }

    public boolean goToSymbolSelect(int delta) { return goToSymbol.goToSymbolSelect(delta); }

    public boolean goToSymbolAccept() { return goToSymbol.goToSymbolAccept(); }

    // ════════════════════════════════════════════════════════════════
    // Popup aller-à-la-ligne — corps délégués à EditorGoToLinePopup
    // ════════════════════════════════════════════════════════════════

    public void showGoToLine() { goToLine.showGoToLine(); }

    public void acceptGoToLineInput(String input) { goToLine.acceptGoToLineInput(input); }

    public void dismissGoToLine() { goToLine.dismissGoToLine(); }

    public boolean isGoToLineVisible() { return goToLine.isGoToLineVisible(); }

    @Deprecated
    public void setGoToLineText(String text) { goToLine.setGoToLineText(text); }

    @Deprecated
    public boolean acceptGoToLine() { return goToLine.acceptGoToLine(); }

    // ════════════════════════════════════════════════════════════════
    // Popup de renommage — corps délégués à EditorRenamePopup
    // ════════════════════════════════════════════════════════════════

    public void showRename() { rename.showRename(); }

    public boolean acceptRenameInput(String newName) { return rename.acceptRenameInput(newName); }

    public void dismissRename() { rename.dismissRename(); }

    public boolean isRenameVisible() { return rename.isRenameVisible(); }

    @Deprecated
    public void setRenameText(String text) { rename.setRenameText(text); }

    @Deprecated
    public boolean acceptRename() { return rename.acceptRename(); }

    // ════════════════════════════════════════════════════════════════
    // Popup + fiche de diagnostic — corps délégués à EditorDiagnosticPopups
    // ════════════════════════════════════════════════════════════════

    public void showDiagnosticSheet() { diagnostics.showDiagnosticSheet(); }

    public void dismissDiagnosticSheet() { diagnostics.dismissDiagnosticSheet(); }

    public boolean isDiagnosticSheetVisible() { return diagnostics.isDiagnosticSheetVisible(); }

    public void showDiagnosticPopup(DiagnosticShift.Diagnostic diag, int offset) {
        diagnostics.showDiagnosticPopup(diag, offset);
    }

    public void dismissDiagnosticPopup() { diagnostics.dismissDiagnosticPopup(); }

    public void showDiagnosticListSheet(int line) { diagnostics.showDiagnosticListSheet(line); }

    public void dismissDiagnosticListSheet() { diagnostics.dismissDiagnosticListSheet(); }

}
