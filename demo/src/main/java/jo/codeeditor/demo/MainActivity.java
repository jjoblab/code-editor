package jo.codeeditor.demo;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.SyntaxHighlighter;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.textmate.TextMateTokenizerImpl;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.GutterAction;
import jo.codeeditor.view.chrome.BreadcrumbBar;
import jo.codeeditor.view.chrome.EditorSearchBar;
import jo.codeeditor.view.chrome.EditorTheme;
import jo.codeeditor.view.chrome.LineNumberStyle;
import jo.codeeditor.view.icons.EditorIcons;

/**
 * Terrain de jeu de la bibliothèque code-editor : éditeur plein écran,
 * sélecteur de langage (27 échantillons), bascules rapides et analyseur
 * factice (diagnostics, complétion, signatures, quick doc, code actions).
 */
public class MainActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    EditorView editor; // package-private : accès par le test de fumée
    EditorSession session; // package-private : accès par le test de fumée
    private BreadcrumbBar breadcrumb;
    private EditorSearchBar searchBar;
    private DemoAnalyzer analyzer;

    private String languageId = "java";
    private String languageLabel = "Java";
    private boolean editorLight = false;
    private boolean schemeOn = false;
    private MenuItem languageItem;
    // B15 (P2-2) : état des points d'arrêt de démo — le fournisseur
    // actif des GutterAction le relit à chaque rendu (armé = accent).
    private final Set<Integer> armedBreakpoints = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ADR-002 (C3) : voie grammaires — crochage du module optionnel
        // :cel-textmate (une seule fois, avant toute vue éditeur) : les
        // langages couverts par une grammaire embarquée (C#) passent au
        // moteur de grammaires, les autres gardent le tokéniseur intégré.
        SyntaxHighlighter.setTextMateTokenizer(TextMateTokenizerImpl.create());
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
        // Pose le thème de référence du scheme (lot 4 #25) — sans scheme
        // actif, ça revient à setTheme(base) : même purge de caches.
        editor.setSchemeBaseTheme(DemoTheme.emeraldNight());
        // B5 : hôte d'aperçu Markdown — l'action « œil » de la barre de
        // fil d'Ariane ouvre la feuille d'aperçu sur l'échantillon
        // Markdown (canPreview = .md/.html).
        editor.setPreviewHost(new DemoPreviewHost());

        // B5 (retour terrain post-J6) : barre de fil d'Ariane AU-DESSUS de
        // l'éditeur — segments à gauche, ACTIONS d'éditeur en haut à
        // droite.
        //
        // B6 (refonte moderne) : SEULEMENT les trois actions d'IDE —
        // annuler, rétablir, aperçu — via UN appel. Les boutons sont des
        // icônes vectorielles (moteur EditorIcons), undo/redo sont CÂBLÉS
        // sur la session (grisés sans historique, rafraîchis à chaque
        // édition), et l'aperçu n'EXISTE que pour un fichier
        // prévisualisable (caché sinon) — il bascule ouvrir/fermer et
        // s'allume quand la feuille est ouverte. Le zoom reste au
        // pincement sur l'éditeur ; « étendre la sélection » est retiré
        // (retour terrain B6 : une barre, trois actions, rien de plus).
        breadcrumb = new BreadcrumbBar(this);
        breadcrumb.bind(editor);
        breadcrumb.addEditorActions();
        root.addView(breadcrumb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // B10 : barre de recherche/remplacement DANS LE FICHIER — cachée
        // par défaut (GONE interne), au-dessus de l'éditeur. Trois
        // entrées d'ouverture : menu « Rechercher », Ctrl+F (le listener
        // de sélection ci-dessous implémente OnFindRequestedListener)
        // et rien d'autre — l'éditeur garde SA barre de fil d'Ariane à
        // trois actions (retour terrain B6 : une barre, trois actions).
        searchBar = new EditorSearchBar(this);
        searchBar.bind(editor);
        root.addView(searchBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

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
        MaterialButton scheme = toggle(R.string.toggle_scheme, false);
        toggles.addView(wrap);
        toggles.addView(minimap);
        toggles.addView(ligatures);
        toggles.addView(nonPrintable);
        toggles.addView(theme);
        toggles.addView(scheme);
        int[] wrapIds = {wrap.getId(), minimap.getId(), ligatures.getId(),
                nonPrintable.getId(), theme.getId(), scheme.getId()};
        for (int i = 0; i < wrapIds.length; i++) toggles.check(wrapIds[i]);

        editor.setWordWrap(true);

        toggles.addOnButtonCheckedListener((group, id, checked) -> {
            if (id == wrap.getId()) editor.setWordWrap(checked);
            else if (id == minimap.getId()) editor.setMinimapEnabled(checked);
            else if (id == ligatures.getId()) editor.setFontLigatures(checked);
            else if (id == nonPrintable.getId()) editor.setShowNonPrintable(checked);
            else if (id == theme.getId()) applyEditorTheme(checked);
            else if (id == scheme.getId()) applyColorScheme(checked);
        });

        // B5 : les boutons d'action montent dans la barre de fil d'Ariane
        // (voir buildUi) — la bande du bas ne garde que les bascules.

        root.addView(strip, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    // B6 : les helpers action() (B5) et zoom/canUndo/canRedo sont retirés —
    // les trois actions d'éditeur sont câblées par
    // BreadcrumbBar.addEditorActions() sur la session liée.

    private MaterialButton toggle(int textRes, boolean checked) {
        MaterialButton button = new MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(textRes);
        button.setId(textRes);
        button.setChecked(checked);
        return button;
    }

    /** B14 (P1-5) : « Replier au niveau N » — profondeur exacte (1=classes, 2=méthodes, 3=blocs). */
    private void replierAuNiveau(int level) {
        if (session != null) session.collapseFoldsAtLevel(level);
        editor.invalidate();
    }

    /**
     * B15 (P2-2) : pose/retire un point d'arrêt de démo sur la ligne du
     * caret — l'usage canonique de {@link EditorView#gutterActions} :
     * la pill remplace le numéro (façon Android Studio), le tap exécute
     * perform() (armé/désarmé) et le fournisseur actif est relu au
     * rendu (le toggle n'a PAS besoin de ré-enregistrer l'action).
     */
    private void toggleBreakpoint() {
        if (session == null || session.getDocument() == null) return;
        int caretOffset = session.getSelection().start;
        int line = session.getDocument().lineForOffset(caretOffset);
        GutterAction existing = editor.gutterActions.actionForLine(line);
        if (existing != null) {
            editor.gutterActions.remove(existing.id);
            armedBreakpoints.remove(line);
            Toast.makeText(this, getString(R.string.breakpoint_removed, line + 1),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        armedBreakpoints.add(line);
        editor.gutterActions.add(new GutterAction("demo-bp-" + line, line,
                EditorIcons.Icon.BREAKPOINT,
                () -> {
                    // Tap sur la pill : arme/désarme — le fournisseur
                    // actif ci-dessous relit l'état au prochain rendu.
                    if (armedBreakpoints.remove(line)) {
                        Toast.makeText(this,
                                getString(R.string.breakpoint_disarmed, line + 1),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        armedBreakpoints.add(line);
                        Toast.makeText(this,
                                getString(R.string.breakpoint_armed, line + 1),
                                Toast.LENGTH_SHORT).show();
                    }
                    editor.invalidate();
                },
                () -> armedBreakpoints.contains(line)));
        Toast.makeText(this, getString(R.string.breakpoint_set, line + 1),
                Toast.LENGTH_SHORT).show();
    }

    /** Bascule thème jour/nuit — la base du scheme suit (lot 4 #25). */
    void applyEditorTheme(boolean light) {
        editorLight = light;
        // Base demo jour/nuit posée comme référence du scheme (lot 4 #25) :
        // sans scheme actif ça résout à setTheme(base) — purge de caches
        // incluse ; avec un scheme actif, la cascade repart de cette base
        // et le mode du scheme suit le thème.
        editor.setSchemeBaseTheme(light ? DemoTheme.emeraldDay()
                                        : DemoTheme.emeraldNight());
        if (schemeOn) editor.setSchemeDarkMode(!light);
    }

    /** Bascule le scheme de couleurs (lot 4 #25) — null = thème brut. */
    void applyColorScheme(boolean on) {
        schemeOn = on;
        editor.setColorScheme(on ? DemoTheme.duskScheme() : null);
    }

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_language) {
            showLanguagePicker();
            return true;
        }
        if (id == R.id.line_numbers_absolute) {
            editor.setLineNumberStyle(LineNumberStyle.ABSOLUTE);
            return true;
        }
        if (id == R.id.line_numbers_relative) {
            editor.setLineNumberStyle(LineNumberStyle.RELATIVE);
            return true;
        }
        if (id == R.id.line_numbers_hybrid) {
            editor.setLineNumberStyle(LineNumberStyle.HYBRID);
            return true;
        }
        // B14 (P1-5) : plis par niveau + tout replier/déplier — le pli
        // sélectif « méthodes vs classes » façon Android Studio, les
        // régions nommées étant déjà détectées (// region, #region).
        if (id == R.id.folds_level_1) {
            replierAuNiveau(1);
            return true;
        }
        if (id == R.id.folds_level_2) {
            replierAuNiveau(2);
            return true;
        }
        if (id == R.id.folds_level_3) {
            replierAuNiveau(3);
            return true;
        }
        if (id == R.id.folds_all) {
            if (session != null) session.collapseAllFolds();
            editor.invalidate();
            return true;
        }
        if (id == R.id.folds_none) {
            if (session != null) session.expandAllFolds();
            editor.invalidate();
            return true;
        }
        if (id == R.id.action_find) {
            searchBar.open();
            return true;
        }
        // B15 (P2-2) : démo de l'API GutterAction — pose/retire un point
        // d'arrêt sur la ligne du caret ; le tap sur la pill l'arme/le
        // désarme (fournisseur actif relu au rendu).
        if (id == R.id.action_breakpoint) {
            toggleBreakpoint();
            return true;
        }
        if (id == R.id.indent_guides_scope) {
            editor.setIndentGuideStyle(jo.codeeditor.view.IndentGuideStyle.INDENT_WITH_SCOPE);
            return true;
        }
        if (id == R.id.indent_guides_indent) {
            editor.setIndentGuideStyle(jo.codeeditor.view.IndentGuideStyle.INDENT);
            return true;
        }
        if (id == R.id.indent_guides_off) {
            editor.setIndentGuideStyle(jo.codeeditor.view.IndentGuideStyle.OFF);
            return true;
        }
        if (id == R.id.sticky_on) {
            editor.setStickyLinesEnabled(true);
            return true;
        }
        if (id == R.id.sticky_off) {
            editor.setStickyLinesEnabled(false);
            return true;
        }
        if (id == R.id.sticky_max_1) {
            editor.setStickyMaxLines(1);
            return true;
        }
        if (id == R.id.sticky_max_2) {
            editor.setStickyMaxLines(2);
            return true;
        }
        if (id == R.id.sticky_max_3) {
            editor.setStickyMaxLines(3);
            return true;
        }
        if (id == R.id.sticky_max_4) {
            editor.setStickyMaxLines(4);
            return true;
        }
        if (id == R.id.sticky_max_5) {
            editor.setStickyMaxLines(5);
            return true;
        }
        if (id == R.id.magnifier_zoom_15) {
            editor.setMagnifierZoom(1.5f);
            return true;
        }
        if (id == R.id.magnifier_zoom_20) {
            editor.setMagnifierZoom(2f);
            return true;
        }
        if (id == R.id.magnifier_zoom_25) {
            editor.setMagnifierZoom(2.5f);
            return true;
        }
        if (id == R.id.magnifier_zoom_30) {
            editor.setMagnifierZoom(3f);
            return true;
        }
        if (id == R.id.magnifier_native_on) {
            // M3 : widget plateforme (rendu de la VRAIE frame, style
            // système) — silencieusement ignoré sous API 28 (repli custom).
            editor.setNativeMagnifierEnabled(true);
            return true;
        }
        if (id == R.id.magnifier_native_off) {
            editor.setNativeMagnifierEnabled(false);
            return true;
        }
        if (id == R.id.swatch_on) {
            editor.setColorSwatchesEnabled(true);
            return true;
        }
        if (id == R.id.swatch_off) {
            editor.setColorSwatchesEnabled(false);
            return true;
        }
        if (id == R.id.var_colors_on) {
            editor.setVariableColorsEnabled(true);
            return true;
        }
        if (id == R.id.var_colors_off) {
            editor.setVariableColorsEnabled(false);
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
        // B6 : nom de fichier fictif — déclenche canPreview du hôte
        // d'aperçu (l'œil N'APPARAÎT que pour Markdown / HTML, disparaît
        // pour les autres échantillons — le changement rafraîchit la barre
        // sœur SANS code ici : miroir setFileName). B7 : l'échantillon
        // HTML a SA page (rendu BRUT par la feuille) et XML ne porte
        // plus un faux nom .html (l'aperçu markdown-échappait le layout).
        editor.setFileName("markdown".equals(id) ? "exemple.md"
                : "html".equals(id) ? "page.html" : null);
        // B12 (P1-3) : mémoire de zoom par type — l'échantillon courant
        // devient la clé : pincer Java à 1,8 puis passer au Markdown
        // applique le zoom Markdown (1 par défaut), revenir à Java
        // RESTAURE 1,8 (et une vue recréée — rotation mise à part, B17
        // la couvre précisément — repart du réglage mémorisé).
        editor.setZoomMemoryKey(editorLanguageId);
        // B15 (P2-2) : les points d'arrêt de démo appartiennent à
        // l'ÉCHANTILLON précédent — l'hôte vide son registre au
        // changement de fichier (les actions de gouttière sont des
        // données d'hôte : elles survivent à setSession par conception,
        // c'est à l'hôte de décider de leur pertinence).
        editor.gutterActions.clear();
        armedBreakpoints.clear();
        breadcrumb.refreshActions();
        // B10 : la recherche suit le changement d'échantillon — le
        // contrôleur ré-accroche la session et recalcule (purge du
        // surlignage déjà faite par EditorView.setSession).
        if (searchBar != null) searchBar.refresh();
        // T2 (lot 2, J4) : preuve des actions socle sur la pill — les
        // INTÉGRÉES (Duplicate / Comment / Toggle case / Share /
        // Move up / Move down) sont injectées via le MÊME SPI que les
        // actions d'hôte (l'hôte peut les retirer par identifiant).
        // Retirées à chaque changement d'échantillon puis ré-injectées
        // (la liste vit sur la session de la vue). La pill démo atteint
        // ainsi SA CAPACITÉ (8) — l'overflow ⋯ du menu unifié prend le
        // relais sur les viewports étroits.
        for (String builtinId : new String[]{"builtin.duplicate", "builtin.comment",
                "builtin.case.toggle", "builtin.share",
                "builtin.move.up", "builtin.move.down",
                "builtin.blockdrag"}) {
            editor.removeSelectionAction(builtinId);
        }
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.duplicate(editor));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.toggleComment(editor));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.convertCase(
                editor, jo.codeeditor.view.BuiltinSelectionActions.CaseMode.TOGGLE));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.share(editor));
        // P2 (J6) : mouvements de lignes — le bloc de la sélection bouge,
        // la sélection suit.
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.moveLinesUp(editor));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.moveLinesDown(editor));
        // B13 (P1-2) : drag-déposer de bloc — l'item ✥ Move de la pill
        // ARME le mode ; le geste suivant déplace la sélection (loupe +
        // caret fantôme + carte flottante), un tap annule.
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.blockDrag(editor));

        // B10 : le listener primaire porte les DEUX rôles — la barre
        // d'état (sélection) ET l'ouverture de la recherche : le
        // raccourci Ctrl+F de l'éditeur (EditorKeyHandler →
        // EditorCommands.FIND) cherche le OnFindRequestedListener dans
        // le listener primaire, donc l'objet doit implémenter les deux
        // interfaces (classe interne FindableSelectionListener).
        editor.setOnSelectionChangedListener(
                new FindableSelectionListener());

        if (editorLight) editor.setTheme(EditorTheme.light());
        toolbar.setSubtitle(label);
    }

    /**
     * B10 : le listener primaire de l'éditeur, portant les DEUX rôles —
     * la barre d'état (sélection) et l'ouverture de la recherche
     * (Ctrl+F → EditorCommands.FIND cherche le OnFindRequestedListener
     * dans le listener primaire).
     */
    private final class FindableSelectionListener
            implements EditorView.OnSelectionChangedListener,
            EditorView.OnFindRequestedListener {
        @Override
        public void onSelectionChanged(int line, int col, boolean isCursor) {
            toolbar.setSubtitle(String.format(java.util.Locale.FRANCE,
                    "Ln %d, Col %d — %s", line + 1, col + 1, languageLabel));
        }

        @Override
        public void onFindRequested() {
            searchBar.open();
        }
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
