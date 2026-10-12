package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.view.MotionEvent;

/**
 * Menu contextuel matériel d'EditorView, extrait d'EditorInputHandler :
 * route les événements de mouvement génériques (clic secondaire souris) et
 * montre le PopupMenu système Copier/Couper/Coller/Select all/Undo/Redo
 * ancré sur le pointeur. EditorInputHandler délègue
 * {@code onGenericMotionEvent} et {@code showEditorContextMenu}.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorContextMenuHandler {

    private final EditorView view;

    public EditorContextMenuHandler(EditorView view) {
        this.view = view;
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        if (view.session == null) return false;
        if (event.getAction() == MotionEvent.ACTION_BUTTON_PRESS
            && event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
            int offset = view.offsetAt(event.getX(), event.getY());
            view.session.setSelection(offset);
            showEditorContextMenu(event.getX(), event.getY());
            return true;
        }
        return false;
    }

    public void showEditorContextMenu(float anchorX, float anchorY) {
        if (view.session == null) return;
        android.widget.PopupMenu popup = new android.widget.PopupMenu(view.getContext(), view);
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == android.R.id.copy) { view.copy(); return true; }
            if (id == android.R.id.cut) { view.cut(); return true; }
            if (id == android.R.id.paste) { view.paste(); return true; }
            if (id == android.R.id.selectAll) { view.session.selectAll(); view.invalidate(); return true; }
            return false;
        });
        popup.getMenu().add(0, android.R.id.copy, 0, "Copy");
        popup.getMenu().add(0, android.R.id.cut, 0, "Cut");
        popup.getMenu().add(0, android.R.id.paste, 0, "Paste");
        popup.getMenu().add(0, android.R.id.selectAll, 0, "Select all");
        popup.getMenu().add(0, 1001, 0, "Undo").setOnMenuItemClickListener(i -> {
            view.session.undo(); view.notifyTextChanged(); return true;
        });
        popup.getMenu().add(0, 1002, 0, "Redo").setOnMenuItemClickListener(i -> {
            view.session.redo(); view.notifyTextChanged(); return true;
        });
        // P2-10 : entrée d'action source « Organiser les imports » — UNE
        // requête par document (only transporté dans le contexte LSP),
        // visible seulement quand un fournisseur d'actions est branché
        // (pas d'entrée morte sans langage serveur). L'entrée fixAll
        // n'est pas exposée par défaut : les serveurs Java actuels ne la
        // proposent pas — l'hôte dispose de performFixAll() pour la
        // brancher quand ils l'ajouteront.
        if (hasCodeActionsProvider()) {
            popup.getMenu().add(0, 1003, 0, "Organize imports")
                    .setOnMenuItemClickListener(i -> {
                        view.performOrganizeImports(null);
                        return true;
                    });
        }
        popup.show();
    }

    /** True si un fournisseur d'actions de code est branché (langage LSP). */
    private boolean hasCodeActionsProvider() {
        return view.language != null
                && view.language.getCodeActionsProvider() != null;
    }
}
