package jo.codeeditor.demo.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.demo.R;

/**
 * Bande d'onglets multi-fichiers de l'atelier : un onglet par échantillon
 * ouvert, libellé + bouton de fermeture « × », soulignement d'accent sur
 * l'onglet actif. Défilement horizontal quand la place manque.
 *
 * <p>Le conteneur se masque tout seul quand un seul fichier est ouvert
 * (l'onglet unique n'apporte aucune information).</p>
 */
public final class FileTabs {

    /** Récepteur des interactions de la bande. */
    public interface Listener {
        /** L'onglet {@code index} a été sélectionné. */
        void onTabSelected(int index);

        /** L'onglet {@code index} demande sa fermeture. */
        void onTabClose(int index);
    }

    private static final int ZINC_950 = 0xFF09090B;
    private static final int ZINC_200 = 0xFFE4E4E7;
    private static final int ZINC_400 = 0xFFA1A1AA;
    private static final int SKY_400 = 0xFF38BDF8;

    private final HorizontalScrollView scroller;
    private final LinearLayout strip;
    private final Listener listener;
    private final List<String> labels = new ArrayList<>();
    private int selected = -1;

    public FileTabs(Context context, Listener listener) {
        this.listener = listener;
        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setBackgroundColor(ZINC_950);
        scroller.setFillViewport(true);
        strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        scroller.addView(strip, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** La vue racine à attacher au conteneur parent. */
    public HorizontalScrollView view() {
        return scroller;
    }

    /**
     * Reconstruit la bande : libellés des onglets + index sélectionné.
     * La bande disparaît avec un seul onglet.
     */
    public void setTabs(List<String> tabLabels, int selectedIndex) {
        labels.clear();
        labels.addAll(tabLabels);
        selected = selectedIndex;
        rebuild();
    }

    /** Sélectionne un onglet sans reconstruire (post-création). */
    public void setSelected(int index) {
        if (index == selected) return;
        selected = index;
        rebuild();
        if (index >= 0 && index < strip.getChildCount()) {
            strip.getChildAt(index).requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, 0, 0));
        }
    }

    private void rebuild() {
        strip.removeAllViews();
        if (labels.size() <= 1) {
            scroller.setVisibility(android.view.View.GONE);
            return;
        }
        scroller.setVisibility(android.view.View.VISIBLE);
        Context context = strip.getContext();
        for (int i = 0; i < labels.size(); i++) {
            strip.addView(tabView(context, i, labels.get(i)));
        }
    }

    private android.view.View tabView(Context context, final int index, String label) {
        LinearLayout tab = new LinearLayout(context);
        tab.setOrientation(LinearLayout.VERTICAL);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(context, 12);
        row.setPadding(pad, dp(context, 7), dp(context, 2), dp(context, 7));

        TextView title = new TextView(context);
        title.setText(label);
        title.setTextSize(13f);
        title.setTextColor(index == selected ? ZINC_200 : ZINC_400);
        title.setSingleLine(true);
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(context);
        close.setText(context.getString(R.string.tab_close));
        close.setTextSize(14f);
        close.setTextColor(ZINC_400);
        close.setPadding(dp(context, 8), 0, dp(context, 6), 0);
        close.setSingleLine(true);
        close.setContentDescription(context.getString(
                R.string.tab_close_description, label));
        row.addView(close);

        // Barre d'accent 2dp : l'onglet actif seulement.
        android.view.View accent = new android.view.View(context);
        accent.setBackgroundColor(index == selected ? SKY_400 : 0x00000000);

        tab.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        tab.addView(accent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 2)));

        row.setOnClickListener(v -> {
            if (listener != null) listener.onTabSelected(index);
        });
        close.setOnClickListener(v -> {
            if (listener != null) listener.onTabClose(index);
        });
        return tab;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
