package jo.codeeditor.demo.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import jo.codeeditor.demo.DemoSamples;
import jo.codeeditor.demo.R;

/**
 * Tiroir « Fichiers » de l\u2019atelier — refonte du NavigationView
 * générique : en-tête typographié, champ de filtrage instantané
 * (insensible à la casse et aux accents), sections par famille de
 * langages dotées chacune d\u2019une teinte, et badges par échantillon
 * (« TM » : grammaire TextMate embarquée, « LSP » : mini serveur
 * disponible). Le point cyan signale les fichiers déjà ouverts en
 * onglets.
 *
 * <p>Le tiroir reconstruit ses sections à chaque frappe du filtre et
 * à chaque ouverture ({@link #refresh()}) — 29 langages, le coût est
 * négligeable et l\u2019état reste toujours cohérent avec l\u2019atelier.</p>
 */
public final class SampleDrawer {

    /** Récepteur des actions du tiroir. */
    public interface Listener {
        /** Un échantillon est demandé (libellé affiché + identifiant). */
        void onSampleSelected(String label, String sampleId);

        /** Réglages demandés. */
        void onSettings();

        /** À propos demandé. */
        void onAbout();
    }

    /** Familles du tiroir — nom, teinte, libellés membres (l\u2019appartenance se juge sur le libellé). */
    static final Object[][] FAMILIES = {
            {"JVM", 0xFF34D399, new String[]{"Java", "Kotlin", "C#", "Scala", "Groovy"}},
            {"Web", 0xFF38BDF8, new String[]{"JavaScript", "Minifié (JS)", "TypeScript", "PHP", "HTML", "CSS", "XML"}},
            {"Scripts", 0xFFFBBF24, new String[]{"Python", "Ruby", "Lua", "Shell", "SQL", "Markdown"}},
            {"Systèmes", 0xFFF87171, new String[]{"C", "C++", "Go", "Rust", "Swift", "Dart", "Smali"}},
            {"Données", 0xFFA78BFA, new String[]{"JSON", "YAML", "TOML", "Properties", "Log"}},
    };

    private static final int ZINC_950 = 0xFF09090B;
    private static final int ZINC_900 = 0xFF18181B;
    private static final int ZINC_850 = 0xFF101014;
    private static final int ZINC_800 = 0xFF27272A;
    private static final int ZINC_200 = 0xFFE4E4E7;
    private static final int ZINC_400 = 0xFFA1A1AA;
    private static final int ZINC_500 = 0xFF71717A;
    private static final int ZINC_600 = 0xFF52525B;
    private static final int SKY_400 = 0xFF38BDF8;
    private static final int AMBER_400 = 0xFFFBBF24;
    private static final int EMERALD_400 = 0xFF34D399;

    private final Context context;
    private final Listener listener;
    private final Predicate<String> grammarProbe;
    private final Predicate<String> openProbe;

    private final LinearLayout root;
    private final LinearLayout sections;
    private EditText filter;
    private TextView filterClear;
    private final List<String> labels = new ArrayList<>();
    private final List<String> ids = new ArrayList<>();
    private String filterText = "";

    public SampleDrawer(Context context, Listener listener,
            Predicate<String> grammarProbe, Predicate<String> openProbe) {
        this.context = context;
        this.listener = listener;
        this.grammarProbe = grammarProbe;
        this.openProbe = openProbe;
        for (Map.Entry<String, String> e : DemoSamples.LANGUAGES.entrySet()) {
            labels.add(e.getKey());
            ids.add(e.getValue());
        }

        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(roundedRight(context));

        root.addView(header());
        root.addView(divider(context));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        sections = new LinearLayout(context);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setPadding(0, dp(4), 0, dp(10));
        scroll.addView(sections, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(footer());
        rebuild();
    }

    /** La vue racine à attacher au DrawerLayout. */
    public View view() {
        return root;
    }

    /** Rafraîchit les indicateurs « fichier ouvert » (à l\u2019ouverture du tiroir). */
    public void refresh() {
        rebuild();
    }

    /** Le filtre courant (exposé pour les tests). */
    String filterText() {
        return filterText;
    }

    /** Accès test : le conteneur des sections reconstruites. */
    LinearLayout sectionsForTest() {
        return sections;
    }

    /** Accès test : le champ de filtrage. */
    EditText filterForTest() {
        return filter;
    }

    // ── Construction ────────────────────────────────────────────────

    private LinearLayout header() {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        header.setPadding(pad, dp(20), pad, dp(14));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView badge = new TextView(context);
        badge.setText("{ }");
        badge.setTextSize(13f);
        badge.setTextColor(ZINC_950);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(oval(EMERALD_400));
        titleRow.addView(badge, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout titles = new LinearLayout(context);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(12);

        TextView title = new TextView(context);
        title.setText(R.string.drawer_title);
        title.setTextSize(18f);
        title.setTextColor(ZINC_200);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);

        TextView subtitle = new TextView(context);
        subtitle.setText(R.string.drawer_header_subtitle);
        subtitle.setTextSize(11f);
        subtitle.setTextColor(ZINC_500);
        subtitle.setSingleLine(true);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = dp(2);

        titles.addView(title);
        titles.addView(subtitle, subParams);
        titleRow.addView(titles, titleParams);
        header.addView(titleRow);

        // ── Champ de filtrage ──────────────────────────────────────
        FrameLayout filterBox = new FrameLayout(context);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.topMargin = dp(14);
        filterBox.setLayoutParams(boxParams);

        filter = new EditText(context);
        filter.setHint(R.string.drawer_filter_hint);
        filter.setTextSize(13.5f);
        filter.setTextColor(ZINC_200);
        filter.setHintTextColor(ZINC_500);
        filter.setSingleLine(true);
        filter.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        filter.setBackground(roundedField(context));
        int fPadH = dp(12);
        int fPadV = dp(9);
        filter.setPadding(fPadH, fPadV, dp(34), fPadV);
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                filterText = s.toString();
                filterClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.INVISIBLE);
                rebuild();
            }
        });
        filterBox.addView(filter, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        filterClear = new TextView(context);
        filterClear.setText(R.string.tab_close);
        filterClear.setTextSize(15f);
        filterClear.setTextColor(ZINC_500);
        filterClear.setGravity(Gravity.CENTER);
        filterClear.setContentDescription(context.getString(R.string.drawer_filter_clear));
        filterClear.setBackground(ripple(context));
        filterClear.setVisibility(View.INVISIBLE);
        filterClear.setOnClickListener(v -> filter.setText(""));
        FrameLayout.LayoutParams clearParams = new FrameLayout.LayoutParams(
                dp(30), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL);
        filterBox.addView(filterClear, clearParams);

        header.addView(filterBox);
        return header;
    }

    private LinearLayout footer() {
        LinearLayout footer = new LinearLayout(context);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setBackgroundColor(ZINC_950);
        footer.addView(footerRow(R.drawable.ic_settings, R.string.drawer_settings,
                () -> listener.onSettings()));
        footer.addView(footerRow(R.drawable.ic_info, R.string.drawer_about,
                () -> listener.onAbout()));
        return footer;
    }

    private LinearLayout footerRow(int iconRes, int textRes, Runnable action) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(12), dp(20), dp(12));
        row.setBackground(ripple(context));
        row.setOnClickListener(v -> action.run());

        TextView icon = new TextView(context);
        icon.setBackgroundResource(iconRes);
        icon.setContentDescription(context.getString(textRes));
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView label = new TextView(context);
        label.setText(textRes);
        label.setTextSize(13.5f);
        label.setTextColor(ZINC_400);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(14);
        row.addView(label, params);
        return row;
    }

    // ── Reconstruction des sections ─────────────────────────────────

    private void rebuild() {
        sections.removeAllViews();
        boolean any = false;
        Map<String, List<Integer>> byFamily = new LinkedHashMap<>();
        List<Integer> others = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            if (!matches(filterText, labels.get(i))) continue;
            String family = familyOf(labels.get(i));
            if (family == null) {
                others.add(i);
            } else {
                byFamily.computeIfAbsent(family, k -> new ArrayList<>()).add(i);
            }
        }
        for (Object[] family : FAMILIES) {
            String name = (String) family[0];
            int color = (Integer) family[1];
            List<Integer> members = byFamily.get(name);
            if (members == null || members.isEmpty()) continue;
            any = true;
            sections.addView(familyHeader(name, color, members.size()));
            for (int index : members) {
                sections.addView(sampleRow(index, color));
            }
        }
        if (!others.isEmpty()) {
            any = true;
            sections.addView(familyHeader("Autres", ZINC_400, others.size()));
            for (int index : others) {
                sections.addView(sampleRow(index, ZINC_400));
            }
        }
        if (!any) {
            TextView empty = new TextView(context);
            empty.setText(context.getString(R.string.drawer_no_result, filterText));
            empty.setTextSize(13f);
            empty.setTextColor(ZINC_500);
            empty.setPadding(dp(20), dp(18), dp(20), dp(18));
            sections.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private String familyOf(String label) {
        for (Object[] family : FAMILIES) {
            String[] members = (String[]) family[2];
            for (String member : members) {
                if (member.equals(label)) return (String) family[0];
            }
        }
        return null;
    }

    private View familyHeader(String name, int color, int count) {
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(8), dp(12), dp(6));
        header.setTag("family");

        View dot = new View(context);
        dot.setBackground(oval(color));
        header.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));

        TextView title = new TextView(context);
        title.setText(name);
        title.setTextSize(11f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(color);
        title.setAllCaps(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(10);
        header.addView(title, params);

        TextView total = new TextView(context);
        total.setText(String.valueOf(count));
        total.setTextSize(11f);
        total.setTextColor(ZINC_600);
        header.addView(total);
        return header;
    }

    private View sampleRow(int index, int familyColor) {
        String label = labels.get(index);
        String sampleId = ids.get(index);
        boolean open = openProbe.test(sampleId);
        boolean grammar = grammarProbe.test(sampleId);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(10), dp(12), dp(10));
        row.setBackground(ripple(context));
        row.setTag("sample");
        row.setContentDescription(context.getString(open
                ? R.string.drawer_row_open : R.string.drawer_row_closed, label));
        row.setOnClickListener(v -> listener.onSampleSelected(label, sampleId));

        TextView dot = new TextView(context);
        dot.setText("●");
        dot.setTextSize(9f);
        dot.setTextColor(open ? SKY_400 : 0x00000000);
        row.addView(dot, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView name = new TextView(context);
        name.setText(label);
        name.setTextSize(14f);
        name.setTextColor(open ? ZINC_200 : familyColor);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dp(10);
        row.addView(name, nameParams);

        if (grammar) {
            row.addView(chip("TM", AMBER_400, R.string.drawer_badge_textmate));
        }
        if ("java".equals(sampleId)) {
            row.addView(chip("LSP", SKY_400, R.string.drawer_badge_lsp));
        }
        return row;
    }

    private TextView chip(String text, int color, int descriptionRes) {
        TextView chip = new TextView(context);
        chip.setText(text);
        chip.setTextSize(9f);
        chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        chip.setTextColor(color);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setContentDescription(context.getString(descriptionRes));
        chip.setBackground(chipBorder(context));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(26), dp(16));
        params.leftMargin = dp(6);
        chip.setLayoutParams(params);
        return chip;
    }

    // ── Filtrage ────────────────────────────────────────────────────

    /**
     * Le filtre correspond-il au libellé ? Insensible à la casse et aux
     * accents (NFD + suppression des marques) — « minifie » trouve
     * « Minifié (JS) ». Filtre vide/blanc : tout correspond.
     */
    static boolean matches(String filter, String label) {
        if (filter == null || filter.isBlank()) return true;
        return fold(label).contains(fold(filter.trim()));
    }

    private static String fold(String s) {
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    // ── Dessins d'arrière-plan ──────────────────────────────────────

    private static android.graphics.drawable.GradientDrawable roundedRight(Context context) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(ZINC_850);
        int r = dp(context, 18);
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        return bg;
    }

    private static android.graphics.drawable.GradientDrawable roundedField(Context context) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(ZINC_950);
        bg.setCornerRadius(dp(context, 12));
        bg.setStroke(dp(context, 1), ZINC_800);
        return bg;
    }

    private static android.graphics.drawable.GradientDrawable oval(int color) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(color);
        return bg;
    }

    private static android.graphics.drawable.GradientDrawable chipBorder(Context context) {
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(context, 5));
        bg.setColor(0x00000000);
        bg.setStroke(1, ZINC_600);
        return bg;
    }

    /** Fond d'effet tactile (ondulation) pour les rangées cliquables. */
    private static android.graphics.drawable.Drawable ripple(Context context) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        return androidx.core.content.res.ResourcesCompat.getDrawable(
                context.getResources(), value.resourceId, context.getTheme());
    }

    private static View divider(Context context) {
        View line = new View(context);
        line.setBackgroundColor(ZINC_800);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        line.setLayoutParams(params);
        return line;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Conversion dp → px sur le contexte du tiroir (méthodes d'instance). */
    private int dp(int value) {
        return dp(context, value);
    }
}
