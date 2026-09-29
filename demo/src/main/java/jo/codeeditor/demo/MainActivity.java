package jo.codeeditor.demo;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Terrain de jeu de la bibliothèque code-editor : éditeur plein écran,
 * sélecteur de langage (27 échantillons), bascules rapides et analyseur
 * factice (diagnostics, complétion, signatures, quick doc, code actions).
 */
public class MainActivity extends AppCompatActivity {

    private static final float ZOOM_STEP = 0.15f;

    private MaterialToolbar toolbar;
    private EditorView editor;
    EditorSession session; // package-private : accès par le test de fumée
    private DemoAnalyzer analyzer;

    private String languageId = "java";
    private String languageLabel = "Java";
    private boolean editorLight = false;
    private MenuItem languageItem;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadLanguage("Java", "java");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF09090B);

        toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.app_name);
        toolbar.setSubtitle(R.string.toolbar_subtitle);
        toolbar.setTitleTextColor(0xFFE4E4E7);
        toolbar.setSubtitleTextColor(0xFFA1A1AA);
        toolbar.setBackgroundColor(0xFF09090B);
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);
        languageItem = toolbar.getMenu().findItem(R.id.action_language);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        editor = new EditorView(this);
        editor.setTheme(DemoTheme.emeraldNight());
        root.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setBackgroundColor(0xFF09090B);
        strip.setFillViewport(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        strip.addView(bar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        MaterialButtonToggleGroup toggles = new MaterialButtonToggleGroup(this);
        toggles.setSingleSelection(false);
        toggles.setSelectionRequired(false);
        bar.addView(toggles, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialButton wrap = toggle(R.string.toggle_wrap, true);
        MaterialButton minimap = toggle(R.string.toggle_minimap, false);
        MaterialButton ligatures = toggle(R.string.toggle_ligatures, false);
        MaterialButton nonPrintable = toggle(R.string.toggle_non_printable, false);
        MaterialButton theme = toggle(R.string.toggle_theme, false);
        toggles.addView(wrap);
        toggles.addView(minimap);
        toggles.addView(ligatures);
        toggles.addView(nonPrintable);
        toggles.addView(theme);
        int[] wrapIds = {wrap.getId(), minimap.getId(), ligatures.getId(),
                nonPrintable.getId(), theme.getId()};
        for (int i = 0; i < wrapIds.length; i++) toggles.check(wrapIds[i]);

        editor.setWordWrap(true);

        toggles.addOnButtonCheckedListener((group, id, checked) -> {
            if (id == wrap.getId()) editor.setWordWrap(checked);
            else if (id == minimap.getId()) editor.setMinimapEnabled(checked);
            else if (id == ligatures.getId()) editor.setFontLigatures(checked);
            else if (id == nonPrintable.getId()) editor.setShowNonPrintable(checked);
            else if (id == theme.getId()) applyEditorTheme(checked);
        });

        action(bar, R.string.action_undo, v -> {
            if (session != null) session.undo();
        });
        action(bar, R.string.action_redo, v -> {
            if (session != null) session.redo();
        });
        action(bar, R.string.action_zoom_out, v -> zoom(-ZOOM_STEP));
        action(bar, R.string.action_zoom_in, v -> zoom(+ZOOM_STEP));

        root.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private MaterialButton toggle(int textRes, boolean checked) {
        MaterialButton button = new MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(textRes);
        button.setId(textRes);
        button.setChecked(checked);
        return button;
    }

    private void action(LinearLayout bar, int textRes, android.view.View.OnClickListener click) {
        // Style TextButton Material 3 via ContextThemeWrapper (pas d'attribut
        // theme dédié aux boutons texte dans material 1.12).
        MaterialButton button = new MaterialButton(new android.view.ContextThemeWrapper(
                this, com.google.android.material.R.style.Widget_Material3_Button_TextButton));
        button.setText(textRes);
        button.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        lp.gravity = android.view.Gravity.CENTER_VERTICAL;
        bar.addView(button, lp);
    }

    private void zoom(float delta) {
        float next = Math.max(0.6f, Math.min(2.6f, editor.getFontScale() + delta));
        editor.setFontScale(next);
    }

    private void applyEditorTheme(boolean light) {
        editorLight = light;
        editor.setTheme(light ? EditorTheme.light() : DemoTheme.emeraldNight());
    }

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_language) {
            showLanguagePicker();
            return true;
        }
        if (id == R.id.action_reset) {
            loadLanguage(languageLabel, languageId);
            return true;
        }
        if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        return false;
    }

    private void showLanguagePicker() {
        List<String> labels = new ArrayList<>(DemoSamples.LANGUAGES.keySet());
        List<String> ids = new ArrayList<>(DemoSamples.LANGUAGES.values());
        int checked = ids.indexOf(languageId);
        CharSequence[] items = labels.toArray(new CharSequence[0]);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.menu_language_title)
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    dialog.dismiss();
                    loadLanguage(labels.get(which), ids.get(which));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** (Re)charge l'échantillon du langage : nouvelle session + analyseur. */
    void loadLanguage(String label, String id) {
        languageLabel = label;
        languageId = id;
        if (languageItem != null) languageItem.setTitle(label);

        if (analyzer != null) analyzer.detach();
        if (session != null) session.dispose();

        String sample = DemoSamples.sampleFor(id);
        // L'échantillon « Minifié (JS) » partage la coloration et les
        // mots-clés JavaScript (id de langage distinct pour l'échantillon).
        String editorLanguageId = "minified-js".equals(id) ? "javascript" : id;
        session = new EditorSession(EditorDocument.of(sample));
        editor.setSession(session);
        session.setLanguage(editorLanguageId);
        analyzer = new DemoAnalyzer(editor, session, editorLanguageId);
        analyzer.attach();

        editor.setOnSelectionChangedListener((line, col, isCursor) ->
                toolbar.setSubtitle(String.format(java.util.Locale.FRANCE,
                        "Ln %d, Col %d — %s", line + 1, col + 1, label)));

        if (editorLight) editor.setTheme(EditorTheme.light());
        toolbar.setSubtitle(label);
    }

    @Override
    protected void onDestroy() {
        if (analyzer != null) analyzer.detach();
        if (session != null) session.dispose();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
