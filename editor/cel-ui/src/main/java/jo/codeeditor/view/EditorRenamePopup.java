package jo.codeeditor.view;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.PopupWindow;

import jo.codeeditor.document.EditorDocument;

/**
 * Popup de renommage : PopupWindow Android + EditText sur carte « liquid
 * glass » (portage RenamePopup de CodeAssist) ; privilégie le resolver
 * LSP rename (WorkspaceEdit appliqué hors thread UI) avec retombée sur
 * l'heuristique locale par identifiant. Corps déplacés
 * d'EditorPopupManager à l'identique (adaptation des accès délégués) ;
 * l'état du popup (visibilité, texte, offsets, PopupWindow) lui
 * appartient depuis l'extraction correspondante d'EditorView.
 */
class EditorRenamePopup {

    private final EditorView view;

    // ── État du popup (déplacé d'EditorView — seul consommateur) ──
    boolean renameVisible = false;
    String renameText = "";
    int renameStartOffset = -1;
    int renameEndOffset = -1;
    android.widget.PopupWindow renamePopup;

    EditorRenamePopup(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Popup de renommage (utilise un PopupWindow Android + EditText)
    // ════════════════════════════════════════════════════════════════

    void showRename() {
        if (view.session == null) return;
        dismissRename();
        EditorDocument doc = view.session.getDocument();
        int caret = view.session.getSelection().start;
        int line = doc.lineForOffset(caret);
        int lineStart = doc.lineStart(line);
        String lineText = doc.lineText(line);
        int col = caret - lineStart;
        int startCol = col;
        while (startCol > 0) {
            char c = lineText.charAt(startCol - 1);
            if (Character.isLetterOrDigit(c) || c == '_') startCol--;
            else break;
        }
        int endCol = col;
        while (endCol < lineText.length()) {
            char c = lineText.charAt(endCol);
            if (Character.isLetterOrDigit(c) || c == '_') endCol++;
            else break;
        }
        renameStartOffset = lineStart + startCol;
        renameEndOffset = lineStart + endCol;
        renameText = lineText.substring(startCol, endCol);
        if (renameText.isEmpty()) return;
        renameVisible = true;
        Context ctx = view.getContext();
        // Carte glass CodeAssist (un LinearLayout brut theming
        // gutterBg serait trop pauvre). Titre « Rename \"x\" » ; hint
        // « Enter to rename 'x', Esc to cancel » (parité EditorOverlays.kt).
        EditorGlassCards.GlassCard card = EditorGlassCards.build(view, ctx,
                "Rename \"" + renameText + "\"",
                renameText,
                "Enter to rename '" + renameText + "', Esc to cancel",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        final EditText field = card.field;
        field.selectAll();
        field.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                acceptRenameInput(((EditText) v).getText().toString());
                return true;
            }
            return false;
        });
        field.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                acceptRenameInput(((EditText) v).getText().toString());
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dismissRename();
                return true;
            }
            return false;
        });
        renamePopup = new PopupWindow(card.container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true);
        renamePopup.setFocusable(true);
        renamePopup.setOnDismissListener(() -> {
            renameVisible = false;
            renamePopup = null;
        });
        renamePopup.showAtLocation(view, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, view.dp(48));
        field.requestFocus();
    }

    boolean acceptRenameInput(String newName) {
        if (view.session == null || renameStartOffset < 0 || renameEndOffset < 0
            || newName == null || newName.isEmpty()) {
            dismissRename();
            return false;
        }
        EditorDocument doc = view.session.getDocument();
        if (renameEndOffset > doc.length()) {
            dismissRename();
            return false;
        }
        String oldName = doc.getText().substring(renameStartOffset, renameEndOffset);
        if (oldName.isEmpty() || oldName.equals(newName)) {
            dismissRename();
            return false;
        }
        // Préfère le resolver LSP rename quand il est branché — il renvoie
        // le nouveau texte complet après application du WorkspaceEdit (qui
        // respecte la portée et le renommage conscient des types). Retombe
        // sur l'heuristique de correspondance de sous-chaînes quand aucun
        // resolver n'est défini (ex. EmptyLanguage ou un serveur sans
        // support du rename).
        //
        // La requête LSP rename (timeout 10 s) quitte le thread UI — le
        // dialogue se ferme immédiatement, l'application des edits arrive
        // sur l'UI quand le serveur répond ; en cas d'échec on retombe sur
        // l'heuristique locale.
        final String text = doc.getText();
        final jo.codeeditor.view.EditorView.RenameResolver resolver = view.renameResolver;
        final int caretOffset = renameStartOffset;
        final String fNewName = newName;
        final String fOldName = oldName;
        if (resolver != null) {
            dismissRename();
            EditorPopupManager.FEATURE_EXECUTOR.execute(() -> {
                String tmp = null;
                try {
                    tmp = resolver.rename(text, caretOffset, fNewName);
                } catch (Throwable ignored) {
                }
                final String newText = tmp;
                android.os.Handler h = view.getHandler();
                Runnable apply = () -> {
                    boolean replaced = false;
                    if (newText != null && !newText.equals(text)) {
                        try {
                            view.session.replaceRange(0,
                                view.session.getDocument().length(), newText);
                            view.notifyTextChanged();
                            replaced = true;
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!replaced) {
                        // Fallback : heuristique locale par identifiant.
                        String fallback = substringRename(text, fOldName, fNewName);
                        if (fallback != null) {
                            view.session.replaceRange(0,
                                view.session.getDocument().length(), fallback);
                            view.notifyTextChanged();
                        }
                    }
                };
                if (h != null) h.post(apply); else apply.run();
            });
            return true;
        }
        String fallback = substringRename(text, fOldName, fNewName);
        if (fallback != null) {
            view.session.replaceRange(0, doc.length(), fallback);
            view.notifyTextChanged();
        }
        dismissRename();
        return fallback != null;
    }

    /**
     * Fallback historique : remplacement par identifiant exact (bords de
     * mot respectés), sans conscience de portée. Retourne null si aucun
     * remplacement effectué.
     */
    private static String substringRename(String text, String oldName, String newName) {
        StringBuilder sb = new StringBuilder(text.length() + newName.length());
        int i = 0;
        int replaced = 0;
        while (i < text.length()) {
            if (i + oldName.length() <= text.length()
                && text.substring(i, i + oldName.length()).equals(oldName)
                && (i == 0 || (!Character.isLetterOrDigit(text.charAt(i - 1)) && text.charAt(i - 1) != '_'))
                && (i + oldName.length() == text.length()
                    || (!Character.isLetterOrDigit(text.charAt(i + oldName.length()))
                        && text.charAt(i + oldName.length()) != '_'))) {
                sb.append(newName);
                i += oldName.length();
                replaced++;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return replaced > 0 ? sb.toString() : null;
    }

    void dismissRename() {
        renameVisible = false;
        renameStartOffset = -1;
        renameEndOffset = -1;
        if (renamePopup != null) {
            renamePopup.dismiss();
            renamePopup = null;
        }
        view.invalidate();
    }

    boolean isRenameVisible() { return renameVisible; }

    @Deprecated
    void setRenameText(String text) {
        renameText = text == null ? "" : text;
        view.invalidate();
    }

    @Deprecated
    boolean acceptRename() {
        return acceptRenameInput(renameText);
    }
}
