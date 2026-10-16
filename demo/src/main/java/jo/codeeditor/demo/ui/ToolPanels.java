package jo.codeeditor.demo.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;
import java.util.List;

import jo.codeeditor.demo.R;

/**
 * Panneaux outils du bas de l'atelier : <b>Diagnostics</b> (moteur
 * maison OU LSP selon la bascule), <b>Symboles</b> (outline du document
 * courant) et <b>Journal LSP</b> (activité JSON-RPC horodatée). Une
 * rangée de sélecteurs, un contenu, un bouton replier.
 *
 * <p>Les lignes de diagnostics et de symboles partagent le même modèle
 * {@link Row} : position cible (ligne 0-based), libellé principal,
 * détail, teinte. Un clic saute à la ligne via le récepteur.</p>
 */
public final class ToolPanels {

    /** Récepteur du saut de ligne demandé par un clic sur une ligne. */
    public interface Listener {
        /** Saute à la ligne donnée (0-based) dans le document courant. */
        void onJumpToLine(int line);
    }

    /** Une ligne de panneau : cible + libellé + détail + teinte. */
    public static final class Row {
        public final int line;
        public final String text;
        public final String detail;
        public final int color;

        public Row(int line, String text, String detail, int color) {
            this.line = line;
            this.text = text;
            this.detail = detail;
            this.color = color;
        }
    }

    private static final int ZINC_950 = 0xFF09090B;
    private static final int ZINC_900 = 0xFF18181B;
    private static final int ZINC_200 = 0xFFE4E4E7;
    private static final int ZINC_400 = 0xFFA1A1AA;
    private static final int ZINC_600 = 0xFF52525B;
    private static final int RED_400 = 0xFFF87171;
    private static final int AMBER_400 = 0xFFFBBF24;
    private static final int SKY_400 = 0xFF38BDF8;

    /** Identifiants des trois panneaux. */
    public static final int PANEL_DIAGNOSTICS = 0;
    public static final int PANEL_SYMBOLS = 1;
    public static final int PANEL_LOG = 2;

    private static final int MAX_LOG_LINES = 250;
    private static final int PANEL_HEIGHT_DP = 168;

    private final LinearLayout root;
    private final MaterialButton diagnosticsButton;
    private final MaterialButton symbolsButton;
    private final MaterialButton logButton;
    private final TextView collapse;
    private final FrameLayout content;
    private final ListView diagnosticsList;
    private final ListView symbolsList;
    private final LinearLayout logStack;
    private final ScrollView logScroll;
    private final RowAdapter diagnosticsAdapter;
    private final RowAdapter symbolsAdapter;
    private final Listener listener;

    private int diagnosticsCount;
    private int symbolsCount;
    private boolean expanded = true;

    public ToolPanels(Context context, Listener listener) {
        this.listener = listener;
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ZINC_900);

        // ── Rangée de sélecteurs ──────────────────────────────────
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(ZINC_950);
        header.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), 0);

        MaterialButtonToggleGroup selector = new MaterialButtonToggleGroup(context);
        selector.setSingleSelection(true);
        selector.setSelectionRequired(true);
        diagnosticsButton = compactButton(context, R.string.panel_diagnostics);
        symbolsButton = compactButton(context, R.string.panel_symbols);
        logButton = compactButton(context, R.string.panel_log);
        selector.addView(diagnosticsButton);
        selector.addView(symbolsButton);
        selector.addView(logButton);
        selector.check(diagnosticsButton.getId());
        selector.addOnButtonCheckedListener((group, id, checked) -> {
            if (!checked) return;
            if (id == diagnosticsButton.getId()) show(PANEL_DIAGNOSTICS);
            else if (id == symbolsButton.getId()) show(PANEL_SYMBOLS);
            else show(PANEL_LOG);
        });

        collapse = new TextView(context);
        collapse.setText("▾");
        collapse.setTextSize(16f);
        collapse.setTextColor(ZINC_400);
        collapse.setPadding(dp(context, 10), dp(context, 10), dp(context, 6), dp(context, 10));
        collapse.setContentDescription(context.getString(R.string.panel_collapse));
        collapse.setOnClickListener(v -> setExpanded(!expanded));

        header.addView(selector, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(collapse);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Contenu ───────────────────────────────────────────────
        content = new FrameLayout(context);
        content.setBackgroundColor(ZINC_900);

        diagnosticsAdapter = new RowAdapter();
        diagnosticsList = new ListView(context);
        diagnosticsList.setDivider(null);
        diagnosticsList.setAdapter(diagnosticsAdapter);
        diagnosticsList.setOnItemClickListener((parent, view, position, id) ->
                jump(diagnosticsAdapter, position));

        symbolsAdapter = new RowAdapter();
        symbolsList = new ListView(context);
        symbolsList.setDivider(null);
        symbolsList.setAdapter(symbolsAdapter);
        symbolsList.setOnItemClickListener((parent, view, position, id) ->
                jump(symbolsAdapter, position));

        logStack = new LinearLayout(context);
        logStack.setOrientation(LinearLayout.VERTICAL);
        int logPad = dp(context, 10);
        logStack.setPadding(logPad, dp(context, 6), logPad, dp(context, 6));
        logScroll = new ScrollView(context);
        logScroll.addView(logStack, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        content.addView(diagnosticsList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(symbolsList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(logScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, PANEL_HEIGHT_DP)));
        root.addView(content);

        show(PANEL_DIAGNOSTICS);
        refreshBadges();
    }

    /** La vue racine à attacher au conteneur parent. */
    public LinearLayout view() {
        return root;
    }

    /** Remplace le contenu du panneau Diagnostics. */
    public void setDiagnostics(List<Row> rows) {
        diagnosticsAdapter.setRows(rows);
        diagnosticsCount = rows.size();
        refreshBadges();
    }

    /** Remplace le contenu du panneau Symboles. */
    public void setSymbols(List<Row> rows) {
        symbolsAdapter.setRows(rows);
        symbolsCount = rows.size();
        refreshBadges();
    }

    /** Ajoute une ligne au journal LSP (auto-défilement, cap 250). */
    public void appendLog(String line) {
        Context context = root.getContext();
        TextView entry = new TextView(context);
        entry.setText(line);
        entry.setTextSize(10.5f);
        entry.setTextColor(ZINC_400);
        entry.setTypeface(android.graphics.Typeface.MONOSPACE);
        logStack.addView(entry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (logStack.getChildCount() > MAX_LOG_LINES) {
            logStack.removeViewAt(0);
        }
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Le panneau est-il déplié ? */
    public boolean isExpanded() {
        return expanded;
    }

    /** Déplie/replie la zone de contenu. */
    public void setExpanded(boolean value) {
        expanded = value;
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        collapse.setText(expanded ? "▾" : "▴");
    }

    private void show(int panel) {
        diagnosticsList.setVisibility(panel == PANEL_DIAGNOSTICS ? View.VISIBLE : View.GONE);
        symbolsList.setVisibility(panel == PANEL_SYMBOLS ? View.VISIBLE : View.GONE);
        logScroll.setVisibility(panel == PANEL_LOG ? View.VISIBLE : View.GONE);
    }

    private void jump(RowAdapter adapter, int position) {
        Row row = adapter.rows.get(position);
        if (listener != null && row != null) listener.onJumpToLine(row.line);
    }

    private void refreshBadges() {
        Context context = root.getContext();
        diagnosticsButton.setText(context.getString(
                R.string.panel_diagnostics_count, diagnosticsCount));
        symbolsButton.setText(context.getString(
                R.string.panel_symbols_count, symbolsCount));
    }

    private static MaterialButton compactButton(Context context, int textRes) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(textRes);
        button.setTextSize(11.5f);
        button.setId(textRes);
        button.setSingleLine(true);
        return button;
    }

    /** Adapteur commun des lignes Diagnostics / Symboles. */
    private static final class RowAdapter extends BaseAdapter {
        private final List<Row> rows = new ArrayList<>();

        void setRows(List<Row> newRows) {
            rows.clear();
            rows.addAll(newRows);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Context context = parent.getContext();
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7));

            Row item = rows.get(position);

            TextView dot = new TextView(context);
            dot.setText("●");
            dot.setTextSize(9f);
            dot.setTextColor(item.color == 0 ? ZINC_600 : item.color);
            row.addView(dot, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(context, 8), 0, 0, 0);

            TextView main = new TextView(context);
            main.setText(item.text);
            main.setTextSize(12.5f);
            main.setTextColor(ZINC_200);
            main.setSingleLine(true);
            main.setEllipsize(android.text.TextUtils.TruncateAt.END);

            TextView detail = new TextView(context);
            detail.setText(item.detail);
            detail.setTextSize(10.5f);
            detail.setTextColor(ZINC_600);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);

            texts.addView(main, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            texts.addView(detail, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
