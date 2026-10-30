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
    /** Popup ancré de squiggle (D1) — niveau 1 de la lecture d'un diagnostic. */
    private final EditorSquigglePopups squiggle;
    /** Sélecteur de couleur (B26) — carte ancrée au code hex tapé. */
    private final EditorColorPickerPopup colorPicker;
    /** Variables de choix d'un snippet (4.5.0) — carte glass au champ courant. */
    final SnippetChoicePopup snippetChoices;
    /** Actions de sélection injectées par l'hôte (T1) + leur débordement. */
    public final jo.codeeditor.view.EditorSelectionActions selActions =
            new jo.codeeditor.view.EditorSelectionActions();

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
        this.squiggle = new EditorSquigglePopups(view, this);
        this.colorPicker = new EditorColorPickerPopup(view, this);
        this.snippetChoices = new SnippetChoicePopup(view);
    }

    // ══════════════════════════════════════════════════════════
    // Variables de choix d'un snippet (4.5.0)
    // ══════════════════════════════════════════════════════════

    /**
     * Ouvre la carte des options si le champ courant de la session de
     * snippet active porte des choix (« ${1|a,b,c|} ») ; sans effet
     * sinon. Appelé à l'application d'un gabarit et à chaque entrée
     * de navigation (Tab/Shift+Tab) sur un champ à choix.
     */
    public void showSnippetChoicesIfNeeded() {
        snippetChoices.showIfNeeded();
    }

    /** Referme la carte des choix de snippet. */
    public void dismissSnippetChoices() {
        snippetChoices.dismiss();
    }

    /** Referme la carte si la session de snippet n'est plus active. */
    public void dismissSnippetChoicesIfSessionDead() {
        snippetChoices.dismissIfSessionDead();
    }


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

    /**
     * 4.7.0 — programme la résolution de la documentation du candidat
     * sélectionné après séjour (dwell) — appelé par le pipeline popup
     * et par le drag de liste de l'input handler.
     */
    public void scheduleCompletionDocResolve() {
        completion.scheduleCompletionDocResolve();
    }

    public float[] completionPopupAnchor() { return completion.completionPopupAnchor(); }

    /** (Phase 4) Géométrie du bottom sheet de documentation (vue étroite). */
    public float[] completionDocSheetMetrics() {
        return completion.completionDocSheetMetrics();
    }

    /** Réglage du popup : honorer le drapeau « liste tronquée ». */
    public void setHonorIncompleteCompletion(boolean honor) {
        completion.setHonorIncompleteCompletion(honor);
    }

    public boolean isHonorIncompleteCompletion() {
        return completion.isHonorIncompleteCompletion();
    }

    /** Réglage du popup : débounce (ms) avant requête de complétion. */
    public void setCompletionDebounceMs(int debounceMs) {
        completion.setCompletionDebounceMs(debounceMs);
    }

    public int getCompletionDebounceMs() {
        return completion.getCompletionDebounceMs();
    }

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

    /** Quick doc avec un texte brut fourni (tooltip d'inlay hint — I2, J3). */
    public void showQuickDocRaw(int offset, String raw) {
        quickDoc.showQuickDocRaw(offset, raw);
    }

    public void dismissQuickDoc() { quickDoc.dismissQuickDoc(); }

    // ════════════════════════════════════════════════════════════════
    // Popup d'actions de code — corps délégués à EditorCodeActionsPopup
    // ════════════════════════════════════════════════════════════════

    public void resolveCodeActionsAt(int anchorOffset, int selectionStart,
            int selectionEnd, boolean openPopup) {
        codeActions.resolveCodeActionsAt(anchorOffset, selectionStart, selectionEnd, openPopup);
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

    // ════════════════════════════════════════════════════════════════
    // Popup de renommage — corps délégués à EditorRenamePopup
    // ════════════════════════════════════════════════════════════════

    public void showRename() { rename.showRename(); }

    public boolean acceptRenameInput(String newName) { return rename.acceptRenameInput(newName); }

    public void dismissRename() { rename.dismissRename(); }

    public boolean isRenameVisible() { return rename.isRenameVisible(); }

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

    // ════════════════════════════════════════════════════════════════
    // Popup ancré de squiggle (D1) — corps délégués à EditorSquigglePopups
    // ════════════════════════════════════════════════════════════════

    /** Ouvre le popup ancré si des squiggles couvrent l'offset (vrai si ouvert). */
    public boolean squigglePopupAt(int offset, float tapX) {
        return squiggle.squigglePopupAt(offset, tapX);
    }

    /** Résout un hit du popup (rangée quick-fix / choix / « See all »). */
    public void handleSquiggleHit(int hit) { squiggle.handleSquiggleHit(hit); }

    public void dismissSquigglePopup() { squiggle.dismissSquigglePopup(); }

    /**
     * Ouvre le NavMenu en mode débordement de la pill (T1) : liste
     * SYNCHRONE des actions injectées non affichées (section « MORE »),
     * sans résolution GO TO/quick-fixes — zéro nouveau chrome.
     */
    public void showSelectionOverflow(int line, int offset) {
        navMenu.showSelectionOverflow(line, offset);
    }

    // ════════════════════════════════════════════════════════════════
    // Sélecteur de couleur (B26) — corps délégués à EditorColorPickerPopup
    // ════════════════════════════════════════════════════════════════

    /**
     * Ouvre le sélecteur si l'offset tombe au cœur d'un code couleur
     * hex (vrai si ouvert — le tap est alors consommé).
     */
    public boolean colorPickerAt(int offset) {
        return colorPicker.colorPickerAt(offset);
    }

    /** DOWN dans la carte : press du champ SV / bande de teinte. */
    public void colorPickerPressAt(float x, float y) {
        colorPicker.pressAt(x, y);
    }

    /** MOVE du drag : mise à jour vivante. */
    public void colorPickerDragTo(float x, float y) {
        colorPicker.dragTo(x, y);
    }

    /** UP : application (palette / fin de drag) ou geste englouti. */
    public void colorPickerReleaseAt(float x, float y) {
        colorPicker.releaseAt(x, y);
    }

    public void dismissColorPicker() { colorPicker.dismissColorPicker(); }

}
