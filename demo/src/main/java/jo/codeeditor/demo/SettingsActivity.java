package jo.codeeditor.demo;

import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * Écran Réglages de la démo — source d'écriture des préférences que
 * l'atelier relit ({@link EditorPrefs}). Chaque bascule porte sa
 * description : ce que le réglage change VRAIMENT dans l'atelier.
 */
public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EditorPrefs prefs = new EditorPrefs(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF09090B);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(24), pad, dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextSize(22f);
        title.setTextColor(0xFFE4E4E7);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title, sectionParams(0, 10));

        root.addView(section(R.string.settings_section_appearance));
        root.addView(switchRow(prefs, R.string.settings_light_theme,
                R.string.settings_light_theme_hint, EditorPrefs.keyLight(),
                prefs.lightTheme()));
        root.addView(switchRow(prefs, R.string.settings_scheme,
                R.string.settings_scheme_hint, EditorPrefs.keyScheme(),
                prefs.scheme()));

        root.addView(section(R.string.settings_section_editor));
        root.addView(switchRow(prefs, R.string.settings_wrap,
                R.string.settings_wrap_hint, EditorPrefs.keyWrap(),
                prefs.wordWrap()));
        root.addView(switchRow(prefs, R.string.settings_minimap,
                R.string.settings_minimap_hint, EditorPrefs.keyMinimap(),
                prefs.minimap()));
        root.addView(switchRow(prefs, R.string.settings_ligatures,
                R.string.settings_ligatures_hint, EditorPrefs.keyLigatures(),
                prefs.ligatures()));
        root.addView(switchRow(prefs, R.string.settings_non_printable,
                R.string.settings_non_printable_hint, EditorPrefs.keyNonPrintable(),
                prefs.nonPrintable()));

        root.addView(section(R.string.settings_section_engine));
        root.addView(switchRow(prefs, R.string.settings_grammars,
                R.string.settings_grammars_hint, EditorPrefs.keyGrammars(),
                prefs.grammarsAuto()));
        root.addView(switchRow(prefs, R.string.settings_lsp_auto,
                R.string.settings_lsp_auto_hint, EditorPrefs.keyLspAuto(),
                prefs.lspAuto()));

        TextView footnote = new TextView(this);
        footnote.setText(R.string.settings_footnote);
        footnote.setTextSize(11.5f);
        footnote.setTextColor(0xFF71717A);
        LinearLayout.LayoutParams footParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footParams.topMargin = dp(18);
        root.addView(footnote, footParams);

        setContentView(scroll);
    }

    private TextView section(int titleRes) {
        TextView section = new TextView(this);
        section.setText(titleRes);
        section.setTextSize(12.5f);
        section.setTextColor(0xFF38BDF8);
        section.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        section.setAllCaps(true);
        section.setLayoutParams(sectionParams(26, 8));
        return section;
    }

    private LinearLayout switchRow(EditorPrefs prefs, int titleRes, int hintRes,
            String key, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundColor(0xFF18181B);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        row.setLayoutParams(rowParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(titleRes);
        label.setTextSize(15f);
        label.setTextColor(0xFFE4E4E7);
        header.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        MaterialSwitch toggle = new MaterialSwitch(this);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, value) ->
                prefs.set(key, value));
        header.addView(toggle);

        TextView hint = new TextView(this);
        hint.setText(hintRes);
        hint.setTextSize(11.5f);
        hint.setTextColor(0xFFA1A1AA);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(3);
        hint.setPadding(0, 0, dp(58), 0);

        row.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(hint, hintParams);
        return row;
    }

    private LinearLayout.LayoutParams sectionParams(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMargin);
        params.bottomMargin = dp(bottomMargin);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
