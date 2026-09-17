package jo.codeeditor.view;

import android.content.Context;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Carte flottante « liquid glass » commune aux popups à champ texte
 * (aller-à-la-ligne, renommage) — portage RenamePopup/GoToLinePopup de
 * CodeAssist. Corps déplacés d'EditorPopupManager à l'identique,
 * constructeur statique paramétré par la vue.
 */
final class EditorGlassCards {

    private EditorGlassCards() {}

    /**
     * Carte flottante « liquid glass » (portage RenamePopup /
     * GoToLinePopup de CodeAssist) : 320dp de large, fond glassThick
     * (theme.glassBg) coins 18dp, bordure 1dp glassEdge, padding 16dp,
     * titre bodySmall semibold, champ fond surfaceContainerHigh coins 12dp,
     * hint labelSmall sous le champ. Construite en code (GradientDrawable)
     * aux couleurs du thème de l'éditeur (cohérence avec les popups Canvas).
     */
    static final class GlassCard {
        final LinearLayout container;
        final EditText field;
        GlassCard(LinearLayout container, EditText field) {
            this.container = container;
            this.field = field;
        }
    }

    /**
     * Construit la carte glass commune (rename / go-to-line).
     * L'appelant branche ses propres setOnEditorActionListener/
     * setOnKeyListener + crée le PopupWindow + showAtLocation.
     */
    static GlassCard build(EditorView view, Context ctx, String title,
                           String prefill, String hint, int inputType) {
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = view.dp(16);
        container.setPadding(pad, pad, pad, pad);
        container.setLayoutParams(new LinearLayout.LayoutParams(
                view.dp(320), LinearLayout.LayoutParams.WRAP_CONTENT));
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(view.dp(18));
        bg.setColor(view.theme.glassBg);
        bg.setStroke(view.dp(1), view.theme.glassBorder);
        container.setBackground(bg);

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextColor(view.theme.gutterText);
        titleView.setTextSize(12);
        titleView.setTypeface(titleView.getTypeface(),
                android.graphics.Typeface.BOLD);
        container.addView(titleView);
        ((LinearLayout.LayoutParams) titleView.getLayoutParams()).bottomMargin =
                view.dp(8);

        EditText field = new EditText(ctx);
        if (prefill != null) field.setText(prefill);
        if (hint != null) field.setHint(hint);
        field.setTextColor(view.theme.textColor);
        field.setHintTextColor(view.theme.gutterText);
        android.graphics.drawable.GradientDrawable fieldBg =
                new android.graphics.drawable.GradientDrawable();
        fieldBg.setCornerRadius(view.dp(12));
        fieldBg.setColor(view.theme.selection);
        fieldBg.setStroke(view.dp(1), view.theme.glassBorder);
        field.setBackground(fieldBg);
        int fpad = view.dp(12), fpadv = view.dp(10);
        field.setPadding(fpad, fpadv, fpad, fpadv);
        field.setTextSize(16);
        field.setInputType(inputType);
        field.setTypeface(view.metrics.getTypeface());
        container.addView(field);
        ((LinearLayout.LayoutParams) field.getLayoutParams()).bottomMargin =
                view.dp(6);

        if (hint != null) {
            TextView hintView = new TextView(ctx);
            hintView.setText(hint);
            hintView.setTextColor(view.theme.gutterText);
            hintView.setTextSize(11);
            container.addView(hintView);
        }
        return new GlassCard(container, field);
    }
}
