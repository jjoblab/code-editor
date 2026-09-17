package jo.codeeditor.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/**
 * Presse-papiers de l'éditeur : copie / coupe / colle au travers du
 * {@link ClipboardManager} Android, avec un plafond de 200 000 caractères
 * sûr pour le Binder (prévient la TransactionTooLargeException ~1 Mo tout
 * en préservant la partie utile d'une longue sélection).
 *
 * <p>Extrait d'EditorView : {@code copy()}/{@code cut()}/{@code paste()}
 * restent des relais publics sur la vue (API hôte), le corps vit ici.</p>
 */
final class EditorClipboard {

    private final EditorView view;

    /**
     * Cap presse-papiers : 200 000 caractères en gardant la FIN
     * ({@code clipForClipboard} — prévient la TransactionTooLargeException
     * du binder ~1 Mo tout en préservant la partie utile d'une longue
     * sélection, la fin).
     */
    private static final int MAX_CLIPBOARD_CHARS = 200_000;

    EditorClipboard(EditorView view) {
        this.view = view;
    }

    /** Copie la sélection courante (ne fait rien en mode curseur). */
    void copy() {
        String sel = view.session.selectedText();
        if (sel == null || sel.isEmpty()) return;
        setClipboard(sel);
    }

    /** Coupe la sélection courante vers le presse-papiers (ne fait rien en mode curseur). */
    void cut() {
        String sel = view.session.selectedText();
        if (sel == null || sel.isEmpty()) return;
        setClipboard(sel);
        // Supprime la sélection.
        int start = Math.min(view.session.getSelection().start, view.session.getSelection().end);
        int end = Math.max(view.session.getSelection().start, view.session.getSelection().end);
        view.session.replaceRange(start, end, "");
        view.onTextChanged();
    }

    /** Colle le presse-papiers au caret (en remplaçant la sélection). */
    void paste() {
        ClipboardManager cm = (ClipboardManager) view.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return;
        ClipData.Item item = cm.getPrimaryClip() == null ? null : cm.getPrimaryClip().getItemAt(0);
        if (item == null) return;
        CharSequence text = item.getText();
        if (text == null) return;
        String s = text.toString();
        if (s.length() > MAX_CLIPBOARD_CHARS) s = s.substring(0, MAX_CLIPBOARD_CHARS);
        view.session.commitText(s);
        view.onTextChanged();
    }

    private void setClipboard(String text) {
        // Conserve la FIN (CodeAssist clipForClipboard) — la partie utile
        // d'une sélection surdimensionnée est sa fin (logs, code généré).
        if (text.length() > MAX_CLIPBOARD_CHARS) {
            text = text.substring(text.length() - MAX_CLIPBOARD_CHARS);
        }
        ClipboardManager cm = (ClipboardManager) view.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Code Editor", text));
        }
    }
}
