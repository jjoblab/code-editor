package jo.codeeditor.demo.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import jo.codeeditor.demo.R;
import jo.codeeditor.demo.lsp.DemoLspController;

/**
 * Barre d'état d'atelier (bas d'écran, façon VS Code) : position du
 * caret, langage du fichier, moteur de langage actif (maison ou LSP),
 * voie de coloration (grammaires TextMate) et encodage.
 *
 * <p>100 % programmatique — cohérent avec le style de la démo (aucun
 * layout XML pour le chrome dynamique). Les libellés viennent des
 * ressources : rien n'est codé en dur.</p>
 */
public final class StatusBar {

    private static final int ZINC_950 = 0xFF09090B;
    private static final int ZINC_400 = 0xFFA1A1AA;
    private static final int ZINC_200 = 0xFFE4E4E7;
    private static final int SKY_400 = 0xFF38BDF8;
    private static final int AMBER_400 = 0xFFFBBF24;

    private final TextView position;
    private final TextView language;
    private final TextView engine;
    private final TextView grammars;
    private final TextView encoding;
    private final LinearLayout root;
    private boolean readOnly;

    public StatusBar(Context context) {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(ZINC_950);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(context, 10);
        root.setPadding(pad, dp(context, 3), pad, dp(context, 3));

        position = cell(context, 13f, ZINC_200, 1f);
        language = cell(context, 12f, ZINC_400, 0f);
        engine = cell(context, 12f, ZINC_400, 1f);
        grammars = cell(context, 12f, ZINC_400, 0f);
        encoding = cell(context, 12f, ZINC_400, 0f);
        encoding.setGravity(Gravity.END);
        refreshEncoding();

        root.addView(position, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f));
        root.addView(separator(context));
        root.addView(language, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f));
        root.addView(separator(context));
        root.addView(engine, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f));
        root.addView(separator(context));
        root.addView(grammars, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f));
        root.addView(encoding, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.6f));
    }

    /** La vue racine à attacher au conteneur parent. */
    public LinearLayout view() {
        return root;
    }

    /** Met à jour la position du caret (0-based en entrée, +1 affiché). */
    public void setPosition(int line, int col) {
        position.setText(root.getResources().getString(
                R.string.status_position, line + 1, col + 1));
    }

    /** Met à jour le libellé du langage courant. */
    public void setLanguage(String label) {
        language.setText(label);
    }

    /**
     * Met à jour l'indicateur du moteur de langage : point grise
     * (maison), ambre en cours de connexion, cyan connecté.
     */
    public void setEngine(DemoLspController.State state) {
        switch (state) {
            case CONNECTING:
                engine.setText(R.string.status_engine_connecting);
                engine.setTextColor(AMBER_400);
                break;
            case CONNECTED:
                engine.setText(R.string.status_engine_lsp);
                engine.setTextColor(SKY_400);
                break;
            default:
                engine.setText(R.string.status_engine_house);
                engine.setTextColor(ZINC_400);
                break;
        }
    }

    /**
     * Met à jour la voie de coloration : grisé quand la voie intégrée,
     * teinté quand une grammaire TextMate est active, absent quand le
     * langage n'a pas de grammaire embarquée.
     */
    public void setGrammars(boolean active, boolean available) {
        if (!available) {
            grammars.setText("");
            return;
        }
        grammars.setText(root.getResources()
                .getString(R.string.status_textmate));
        grammars.setTextColor(active ? SKY_400 : ZINC_400);
    }

    /**
     * Marque la session en lecture seule (pastille « Lecture seule »
     * ambre) ou rétablit l'indicateur d'encodage.
     */
    public void setReadOnly(boolean value) {
        readOnly = value;
        refreshEncoding();
    }

    private void refreshEncoding() {
        if (readOnly) {
            encoding.setText(R.string.status_readonly);
            encoding.setTextColor(AMBER_400);
        } else {
            encoding.setText(R.string.status_encoding);
            encoding.setTextColor(ZINC_400);
        }
    }

    private static TextView cell(Context context, float sizeSp, int color, float weightUnused) {
        TextView tv = new TextView(context);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setPadding(0, 0, dp(context, 4), 0);
        return tv;
    }

    private static TextView separator(Context context) {
        TextView dot = new TextView(context);
        dot.setText("·");
        dot.setTextSize(12f);
        dot.setTextColor(0xFF52525B);
        dot.setPadding(dp(context, 3), 0, dp(context, 3), 0);
        dot.setSingleLine(true);
        return dot;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
