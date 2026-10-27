package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.PopupWindow;

/**
 * Popup aller-à-la-ligne : PopupWindow Android + EditText sur carte
 * « liquid glass », saisie
 * « ligne » ou « ligne:colonne », variantes dépréciées à champ texte
 * interne. Corps déplacés d'EditorPopupManager à l'identique
 * (adaptation des accès délégués) ; l'état du popup (visibilité,
 * texte, PopupWindow) lui appartient depuis l'extraction
 * correspondante d'EditorView.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorGoToLinePopup {

    private final EditorView view;

    // ── État du popup (déplacé d'EditorView — seul consommateur) ──
    boolean goToLineVisible = false;
    android.widget.PopupWindow goToLinePopup;

    EditorGoToLinePopup(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Popup aller-à-la-ligne (utilise un PopupWindow Android + EditText)
    // ════════════════════════════════════════════════════════════════

    void showGoToLine() {
        if (view.session == null) return;
        dismissGoToLine();
        goToLineVisible = true;
        int lineCount = view.session.getDocument().lineCount();
        Context ctx = view.getContext();
        // Carte glass (un LinearLayout brut theming gutterBg
        // serait sans arrondi ni séparation titre/champ/hint).
        EditorGlassCards.GlassCard card = EditorGlassCards.build(view, ctx,
                ctx.getString(jo.codeeditor.ui.R.string.cel_goto_line_title),
                null,
                "Line 1–" + lineCount + "  (line or line:column)",
                InputType.TYPE_CLASS_NUMBER);
        final EditText field = card.field;
        field.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                acceptGoToLineInput(((EditText) v).getText().toString());
                return true;
            }
            return false;
        });
        field.setOnKeyListener((v, keyCode, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER) {
                acceptGoToLineInput(((EditText) v).getText().toString());
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dismissGoToLine();
                return true;
            }
            return false;
        });
        goToLinePopup = new PopupWindow(card.container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, true);
        goToLinePopup.setFocusable(true);
        // B8 (retour terrain n°5) : un tap hors de la carte la referme (le
        // tap traverse vers l'éditeur — placement du caret) ; la saisie
        // clavier du champ reste captée par la carte.
        EditorPopupMotion.outsideTapDismisses(goToLinePopup);
        goToLinePopup.setOnDismissListener(() -> {
            goToLineVisible = false;
            goToLinePopup = null;
        });
        goToLinePopup.showAtLocation(view, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, view.dp(48));
        // B7 : la carte descend du haut et se pose (140 ms).
        EditorPopupMotion.cardIn(card.container, -view.dp(8));
        field.requestFocus();
    }

    void acceptGoToLineInput(String input) {
        if (view.session == null || input == null) {
            dismissGoToLine();
            return;
        }
        input = input.trim();
        int line, col = 1;
        int colonIdx = input.indexOf(':');
        try {
            if (colonIdx >= 0) {
                line = Integer.parseInt(input.substring(0, colonIdx).trim());
                col = Integer.parseInt(input.substring(colonIdx + 1).trim());
            } else {
                line = Integer.parseInt(input);
            }
        } catch (NumberFormatException e) {
            dismissGoToLine();
            return;
        }
        line = EditorView.clamp(line - 1, 0, view.session.getDocument().lineCount() - 1);
        col = Math.max(1, col);
        int lineStart = view.session.getDocument().lineStart(line);
        int lineEnd = view.session.getDocument().lineEnd(line);
        int targetOffset = Math.min(lineStart + col - 1, lineEnd);
        view.session.expandFoldAt(targetOffset);
        view.session.setSelection(targetOffset);
        view.scrollToLine(line);
        dismissGoToLine();
    }

    void dismissGoToLine() {
        goToLineVisible = false;
        if (goToLinePopup != null) {
            // B7 : la vitre s'efface vers le haut (110 ms) — la référence
            // meurt maintenant, la fenêtre à la fin de l'animation (Retour
            // système ou double dismiss : dismiss immédiat, garde interne).
            EditorPopupMotion.cardOut(goToLinePopup, -view.dp(8));
            goToLinePopup = null;
        }
        view.invalidate();
    }

    boolean isGoToLineVisible() { return goToLineVisible; }
}
