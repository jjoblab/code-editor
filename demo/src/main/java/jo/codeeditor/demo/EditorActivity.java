package jo.codeeditor.demo;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.demo.lsp.DemoLspController;
import jo.codeeditor.demo.ui.FileTabs;
import jo.codeeditor.demo.ui.StatusBar;
import jo.codeeditor.demo.ui.ToolPanels;
import jo.codeeditor.highlight.SyntaxHighlighter;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.shift.DiagnosticShift;
import jo.codeeditor.textmate.TextMateGrammar;
import jo.codeeditor.textmate.TextMateTokenizerImpl;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.GutterAction;
import jo.codeeditor.view.chrome.BreadcrumbBar;
import jo.codeeditor.view.chrome.EditorSearchBar;
import jo.codeeditor.view.chrome.LineNumberStyle;
import jo.codeeditor.view.icons.EditorIcons;

/**
 * L'atelier — coquille IDE de la démo : tiroir de fichiers par famille
 * de langages, onglets multi-fichiers, barre de fil d'Ariane et
 * recherche intégrées, panneaux outils (diagnostics / symboles /
 * journal LSP), barre d'état, et la bascule « moteur maison ↔ moteur
 * LSP » qui vit le module {@code :cel-lsp} de bout en bout.
 *
 * <p>Le contrat d'entrée reste celui de l'ancienne démo : tout ce qui
 * s'y voit fonctionne. Les extras d'intention permettent à l'écran
 * d'accueil d'ouvrir l'atelier préconfiguré
 * ({@code sample_id}, {@code sample_label}, {@code lsp}, {@code scheme}).</p>
 */
public class EditorActivity extends AppCompatActivity {

    /** Extra d'intention : identifiant d'échantillon à ouvrir. */
    public static final String EXTRA_SAMPLE_ID = "sample_id";
    /** Extra d'intention : libellé d'échantillon à ouvrir. */
    public static final String EXTRA_SAMPLE_LABEL = "sample_label";
    /** Extra d'intention : connecter le moteur LSP (Java seulement). */
    public static final String EXTRA_LSP = "lsp";
    /** Extra d'intention : activer le scheme de couleurs. */
    public static final String EXTRA_SCHEME = "scheme";

    /** Nombre maximal d'onglets ouverts simultanément. */
    private static final int MAX_TABS = 10;

    /** Familles du tiroir — l'appartenance se juge sur le libellé. */
    private static final String[][] DRAWER_GROUPS = {
            {"JVM", "Java", "Kotlin", "C#", "Scala", "Groovy"},
            {"Web", "JavaScript", "Minifié (JS)", "TypeScript", "PHP", "HTML", "CSS", "XML"},
            {"Scripts", "Python", "Ruby", "Lua", "Shell", "SQL", "Markdown"},
            {"Systèmes", "C", "C++", "Go", "Rust", "Swift", "Dart", "Smali"},
            {"Données", "JSON", "YAML", "TOML", "Properties", "Log"},
    };

    /** Base des identifiants de menu du tiroir (langages dynamiques). */
    private static final int LANG_MENU_BASE = 0x10000;

    /** Un fichier ouvert de l'atelier. */
    static final class OpenFile {
        final String label;
        final String sampleId;
        final String editorLanguageId;
        final EditorSession session;
        final DemoAnalyzer analyzer;

        OpenFile(String label, String sampleId, String editorLanguageId,
                EditorSession session, DemoAnalyzer analyzer) {
            this.label = label;
            this.sampleId = sampleId;
            this.editorLanguageId = editorLanguageId;
            this.session = session;
            this.analyzer = analyzer;
        }
    }

    // Package-private : accès par les tests de fumée.
    EditorView editor;
    EditorSession session;
    DemoLspController lsp;

    private MaterialToolbar toolbar;
    private BreadcrumbBar breadcrumb;
    private EditorSearchBar searchBar;
    private FileTabs tabs;
    private ToolPanels panels;
    private StatusBar statusBar;
    private DrawerLayout drawer;
    private TextMateTokenizerImpl textMate;

    // Package-private : accès par le test de fumée (coquille
    // multi-fichiers).
    final List<OpenFile> files = new ArrayList<>();
    int current = -1;
    private boolean editorLight;
    private boolean schemeOn;
    private boolean lspIntent;
    private final Set<Integer> armedBreakpoints = new HashSet<>();
    private EditorPrefs prefs;
    private final Runnable panelRefresher = this::refreshPanels;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ADR-002 (C3) : crochage du module optionnel :cel-textmate —
        // une seule fois, avant toute vue éditeur. L'instance est gardée
        // pour la bascule « Grammaires TextMate » du menu.
        textMate = TextMateTokenizerImpl.create();
        SyntaxHighlighter.setTextMateTokenizer(textMate);

        prefs = new EditorPrefs(this);
        lsp = new DemoLspController(this);
        lsp.setListener(new DemoLspController.Listener() {
            @Override
            public void onEngineStateChanged(DemoLspController.State state) {
                statusBar.setEngine(state);
                updateLspMenuItem();
                refreshPanels();
            }

            @Override
            public void onLogLine(String line) {
                panels.appendLog(line);
            }

            @Override
            public void onDiagnosticsChanged() {
                refreshPanels();
            }
        });

        buildUi();

        String sampleId = getIntent().getStringExtra(EXTRA_SAMPLE_ID);
        String sampleLabel = getIntent().getStringExtra(EXTRA_SAMPLE_LABEL);
        boolean schemeExtra = getIntent().getBooleanExtra(EXTRA_SCHEME, false);
        lspIntent = getIntent().getBooleanExtra(EXTRA_LSP, false);

        if (schemeExtra && !prefs.scheme()) {
            prefs.set(EditorPrefs.keyScheme(), true);
        }
        applyPrefsToEditor();
        if (sampleId != null && sampleLabel != null) {
            openSample(sampleLabel, sampleId);
        } else {
            openSample("Java", "java");
        }
    }

    // ── Construction de la coquille ─────────────────────────────────

    private void buildUi() {
        drawer = new DrawerLayout(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF09090B);

        toolbar = new MaterialToolbar(this);
        toolbar.setTitle(R.string.app_name);
        toolbar.setTitleTextColor(0xFFE4E4E7);
        toolbar.setSubtitleTextColor(0xFFA1A1AA);
        toolbar.setBackgroundColor(0xFF09090B);
        toolbar.inflateMenu(R.menu.menu_main);
        toolbar.setOnMenuItemClickListener(this::onMenuItem);
        toolbar.setNavigationIcon(R.drawable.ic_drawer);
        toolbar.setNavigationContentDescription(getString(R.string.drawer_open));
        toolbar.setNavigationOnClickListener(
                v -> drawer.openDrawer(GravityCompat.START));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        tabs = new FileTabs(this, new FileTabs.Listener() {
            @Override
            public void onTabSelected(int index) {
                selectFile(index);
            }

            @Override
            public void onTabClose(int index) {
                closeFile(index);
            }
        });
        root.addView(tabs.view(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        editor = new EditorView(this);
        editor.setSchemeBaseTheme(DemoTheme.emeraldNight());
        editor.setPreviewHost(new DemoPreviewHost());

        breadcrumb = new BreadcrumbBar(this);
        breadcrumb.bind(editor);
        breadcrumb.addEditorActions();
        root.addView(breadcrumb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        searchBar = new EditorSearchBar(this);
        searchBar.bind(editor);
        root.addView(searchBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        panels = new ToolPanels(this, this::jumpToLine);
        root.addView(panels.view(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        statusBar = new StatusBar(this);
        root.addView(statusBar.view(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        drawer.addView(root, new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        NavigationView navigation = new NavigationView(this);
        navigation.setBackgroundColor(0xFF18181B);
        navigation.inflateHeaderView(R.layout.drawer_header);
        navigation.inflateMenu(R.menu.menu_drawer);
        buildLanguageMenu(navigation.getMenu());
        navigation.setNavigationItemSelectedListener(this::onDrawerItemSelected);
        drawer.addView(navigation, new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT, GravityCompat.START));

        setContentView(drawer);
    }

    /**
     * Construit le menu des langages du tiroir : familles dépliables,
     * chaque entrée cochée si le fichier correspondant est ouvert.
     */
    private void buildLanguageMenu(Menu menu) {
        List<String> labels = new ArrayList<>(DemoSamples.LANGUAGES.keySet());
        Set<String> grouped = new HashSet<>();
        int order = 0;
        for (String[] group : DRAWER_GROUPS) {
            Collections.addAll(grouped, group);
            android.view.SubMenu sub = menu.addSubMenu(Menu.NONE, Menu.NONE, order++, group[0]);
            for (int i = 1; i < group.length; i++) {
                int index = labels.indexOf(group[i]);
                if (index >= 0) {
                    sub.add(Menu.NONE, LANG_MENU_BASE + index, order++, group[i]);
                }
            }
        }
        // Filet de sécurité : tout libellé absent des familles connues
        // atterrit dans « Autres » (robuste aux ajouts futurs).
        android.view.SubMenu other = null;
        for (int i = 0; i < labels.size(); i++) {
            if (!grouped.contains(labels.get(i))) {
                if (other == null) {
                    other = menu.addSubMenu(Menu.NONE, Menu.NONE, order++, "Autres");
                }
                other.add(Menu.NONE, LANG_MENU_BASE + i, order++, labels.get(i));
            }
        }
    }

    private boolean onDrawerItemSelected(MenuItem item) {
        drawer.closeDrawer(GravityCompat.START);
        int id = item.getItemId();
        if (id == R.id.drawer_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.drawer_about) {
            showAbout();
            return true;
        }
        if (id >= LANG_MENU_BASE) {
            int index = id - LANG_MENU_BASE;
            List<String> labels = new ArrayList<>(DemoSamples.LANGUAGES.keySet());
            List<String> ids = new ArrayList<>(DemoSamples.LANGUAGES.values());
            if (index < labels.size()) {
                openSample(labels.get(index), ids.get(index));
            }
            return true;
        }
        return false;
    }

    // ── Gestion des fichiers ouverts ────────────────────────────────

    /** Ouvre l'échantillon (nouvel onglet ou sélection de l'existant). */
    void openSample(String label, String id) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).sampleId.equals(id)) {
                selectFile(i);
                return;
            }
        }
        if (files.size() >= MAX_TABS) {
            closeFile(oldestNonCurrentIndex());
        }
        String sample = DemoSamples.sampleFor(id);
        String editorLanguageId = "minified-js".equals(id) ? "javascript" : id;
        EditorSession newSession = new EditorSession(EditorDocument.of(sample));
        // Rafraîchissement différé des panneaux après édition (les deux
        // moteurs débouncent leur recalcul — 700 ms couvre l'analyzer
        // maison à 500 ms et le didChange LSP à 300 ms).
        newSession.addOnTextEditListener((start, end, inserted) ->
                schedulePanelRefresh());
        DemoAnalyzer analyzer = new DemoAnalyzer(editor, newSession, editorLanguageId);
        files.add(new OpenFile(label, id, editorLanguageId, newSession, analyzer));
        selectFile(files.size() - 1);
    }

    /** Sélectionne l'onglet : bascule la session, l'analyseur, le LSP. */
    void selectFile(int index) {
        if (index < 0 || index >= files.size()) return;
        // La bascule multi-fichiers déconnecte le LSP : le mini serveur
        // vit sur UN document (Atelier.java) — le journal l'explique.
        if (lsp.isLspActive()) {
            lsp.disconnect(session);
            panels.appendLog(getString(R.string.lsp_detached_on_switch));
        }
        if (current >= 0 && current != index) {
            files.get(current).analyzer.detach();
        }
        current = index;
        OpenFile file = files.get(index);
        session = file.session;

        editor.setSession(session);
        session.setLanguage(file.editorLanguageId);
        analyzerAttach(file);
        editor.setFileName("markdown".equals(file.sampleId) ? "exemple.md"
                : "html".equals(file.sampleId) ? "page.html" : null);
        editor.setZoomMemoryKey(file.editorLanguageId);
        editor.gutterActions.clear();
        armedBreakpoints.clear();
        breadcrumb.refreshActions();
        if (searchBar != null) searchBar.refresh();
        rebindSelectionActions();

        editor.setOnSelectionChangedListener(new FindableSelectionListener());

        if (editorLight) editor.setTheme(jo.codeeditor.view.chrome.EditorTheme.light());

        toolbar.setSubtitle(file.label);
        tabs.setTabs(labels(), index);
        statusBar.setLanguage(file.label);
        statusBar.setGrammars(textMate != null && textMate.isAvailable(file.editorLanguageId)
                        && grammarActive(file.editorLanguageId),
                textMate != null && textMate.isAvailable(file.editorLanguageId));
        updateLspMenuItem();
        updateGrammarMenuItem();
        refreshPanels();
        // Reconnexion collante : l'utilisateur avait demandé le moteur
        // LSP (bascule ou carte d'accueil) — retour sur un fichier Java
        // après un détour : on rebranche sans lui redemander.
        if (lspIntent && "java".equals(file.editorLanguageId)
                && !lsp.isLspActive()) {
            toggleLsp(true);
        }
    }

    /** Ferme l'onglet (session disposée ; l'onglet voisin sélectionné). */
    void closeFile(int index) {
        if (index < 0 || index >= files.size()) return;
        OpenFile file = files.get(index);
        file.analyzer.detach();
        file.session.dispose();
        files.remove(index);
        if (files.isEmpty()) {
            finish();
            return;
        }
        if (index < current) {
            current--;
            tabs.setTabs(labels(), current);
        } else {
            selectFile(Math.min(current, files.size() - 1));
        }
    }

    private int oldestNonCurrentIndex() {
        return current == 0 ? 1 : 0;
    }

    private List<String> labels() {
        List<String> out = new ArrayList<>();
        for (OpenFile f : files) out.add(f.label);
        return out;
    }

    private void rebindSelectionActions() {
        for (String builtinId : new String[]{"builtin.duplicate", "builtin.comment",
                "builtin.case.toggle", "builtin.share", "builtin.move.up",
                "builtin.move.down", "builtin.blockdrag"}) {
            editor.removeSelectionAction(builtinId);
        }
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.duplicate(editor));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.toggleComment(editor));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.convertCase(
                editor, jo.codeeditor.view.BuiltinSelectionActions.CaseMode.TOGGLE));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.share(editor));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.moveLinesUp(editor));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.moveLinesDown(editor));
        editor.addSelectionAction(jo.codeeditor.view.BuiltinSelectionActions.blockDrag(editor));
    }

    private void analyzerAttach(OpenFile file) {
        boolean javaWithLsp = "java".equals(file.editorLanguageId) && lsp.isLspActive();
        if (!javaWithLsp) {
            file.analyzer.attach();
        }
    }

    // ── Moteur LSP : bascule ────────────────────────────────────────

    /**
     * Bascule le moteur de langage du fichier Java courant : LSP réel
     * (diagnostics poussés par le mini serveur) contre moteur maison
     * (popups synthétiques). Le LSP ne vit que sur le fichier Java —
     * le menu se masque ailleurs.
     */
    void toggleLsp(boolean on) {
        OpenFile file = currentFile();
        if (file == null || !"java".equals(file.editorLanguageId)) {
            lspIntent = false;
            updateLspMenuItem();
            return;
        }
        lspIntent = on;
        if (on) {
            if (lsp.isLspActive()) return;
            file.analyzer.detach();
            file.session.setDiagnostics(Collections.emptyList());
            boolean launched = lsp.connect(editor, file.session);
            if (!launched) {
                lspIntent = false;
                Toast.makeText(this, R.string.lsp_unavailable,
                        Toast.LENGTH_SHORT).show();
                file.analyzer.attach();
            }
        } else {
            lsp.disconnect(file.session);
            file.analyzer.attach();
        }
        updateLspMenuItem();
        refreshPanels();
    }

    private void updateLspMenuItem() {
        MenuItem item = toolbar.getMenu().findItem(R.id.action_lsp);
        if (item == null) return;
        OpenFile file = currentFile();
        boolean javaFile = file != null && "java".equals(file.editorLanguageId);
        item.setVisible(javaFile);
        item.setEnabled(javaFile);
        item.setChecked(lsp.isLspActive());
    }

    // ── Grammaires TextMate ─────────────────────────────────────────

    private boolean grammarActive(String editorLanguageId) {
        return textMate != null && textMate.isAvailable(editorLanguageId);
    }

    /**
     * Bascule la voie de coloration du langage COURANT (grammaire
     * TextMate embarquée contre tokéniseur intégré) — même logique que
     * l'ancienne bande de bascules : la vérité vit dans le registre du
     * module, la bascule altère le registre puis restyle.
     */
    void applyGrammarMode(boolean on) {
        OpenFile file = currentFile();
        if (textMate == null || file == null) return;
        String id = file.editorLanguageId;
        if (on) {
            try {
                textMate.loadEmbeddedGrammar(id);
                if ("html".equals(id)) {
                    textMate.loadEmbeddedGrammar("css");
                    Toast.makeText(this, R.string.grammars_html_css,
                            Toast.LENGTH_SHORT).show();
                }
            } catch (TextMateGrammar.GrammarException e) {
                Toast.makeText(this,
                        getString(R.string.grammars_none, file.label),
                        Toast.LENGTH_SHORT).show();
                updateGrammarMenuItem();
                return;
            }
        } else {
            textMate.unregisterGrammar(id);
        }
        file.session.setLanguage(id);
        updateGrammarMenuItem();
        statusBar.setGrammars(grammarActive(id), true);
    }

    private void updateGrammarMenuItem() {
        MenuItem item = toolbar.getMenu().findItem(R.id.action_grammars);
        if (item == null) return;
        OpenFile file = currentFile();
        boolean available = file != null && textMate != null
                && textMate.isAvailable(file.editorLanguageId);
        item.setVisible(available);
        item.setChecked(available && grammarActive(file.editorLanguageId));
    }

    // ── Réglages persistés ──────────────────────────────────────────

    /** Applique les préférences (idempotent) à l'éditeur courant. */
    private void applyPrefsToEditor() {
        editor.setWordWrap(prefs.wordWrap());
        editor.setMinimapEnabled(prefs.minimap());
        editor.setFontLigatures(prefs.ligatures());
        editor.setShowNonPrintable(prefs.nonPrintable());
        editorLight = prefs.lightTheme();
        editor.setSchemeBaseTheme(editorLight ? DemoTheme.emeraldDay()
                : DemoTheme.emeraldNight());
        applyColorScheme(prefs.scheme());
        // Grammaires : activation automatique au chargement si la
        // préférence est posée et qu'une grammaire existe pour le
        // langage courant.
        OpenFile file = currentFile();
        if (file != null && prefs.grammarsAuto()
                && !grammarActive(file.editorLanguageId)) {
            try {
                textMate.loadEmbeddedGrammar(file.editorLanguageId);
                if ("html".equals(file.editorLanguageId)) {
                    textMate.loadEmbeddedGrammar("css");
                }
                file.session.setLanguage(file.editorLanguageId);
            } catch (TextMateGrammar.GrammarException ignored) {
                // Pas de grammaire pour ce langage : voie intégrée.
            }
        }
    }

    private void applyColorScheme(boolean on) {
        schemeOn = on;
        editor.setColorScheme(on ? DemoTheme.duskScheme() : null);
        if (on) editor.setSchemeDarkMode(!editorLight);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyPrefsToEditor();
        updateGrammarMenuItem();
        statusBar.setGrammars(
                currentFile() != null && grammarActive(currentFile().editorLanguageId),
                currentFile() != null && textMate.isAvailable(currentFile().editorLanguageId));
        refreshPanels();
    }

    // ── Panneaux outils ─────────────────────────────────────────────

    private void schedulePanelRefresh() {
        editor.removeCallbacks(panelRefresher);
        editor.postDelayed(panelRefresher, 700L);
    }

    /** Reconstruit les panneaux Diagnostics et Symboles. */
    void refreshPanels() {
        refreshDiagnosticsPanel();
        refreshSymbolsPanel();
    }

    private void refreshDiagnosticsPanel() {
        List<ToolPanels.Row> rows = new ArrayList<>();
        OpenFile file = currentFile();
        if (file == null) {
            panels.setDiagnostics(rows);
            return;
        }
        if (lsp.isLspActive() && "java".equals(file.editorLanguageId)) {
            for (jo.codeeditor.lang.model.Diagnostic d : lsp.diagnostics()) {
                rows.add(rowFor(d.start, d.message, d.severity));
            }
        } else {
            for (DiagnosticShift.Diagnostic d : file.session.getDiagnostics()) {
                rows.add(rowFor(d.start, d.message, d.severity));
            }
        }
        panels.setDiagnostics(rows);
    }

    private ToolPanels.Row rowFor(int start, String message, int severity) {
        int line = session != null && session.getDocument() != null
                ? session.getDocument().lineForOffset(start) : 0;
        int color = severity == 1 ? 0xFFF87171 : severity == 2 ? 0xFFFBBF24 : 0xFF71717A;
        return new ToolPanels.Row(line, message,
                getString(R.string.panel_row_line, line + 1), color);
    }

    private void refreshSymbolsPanel() {
        List<ToolPanels.Row> rows = new ArrayList<>();
        OpenFile file = currentFile();
        if (file != null) {
            for (NavigationMenu.Symbol symbol : file.analyzer.outline(file.session.getText())) {
                int line = file.session.getDocument() != null
                        ? file.session.getDocument().lineForOffset(symbol.offset) : 0;
                String detail = symbol.container.isEmpty()
                        ? symbol.kind
                        : symbol.kind + " · " + symbol.container;
                int color = symbol.kind.contains("class") || symbol.kind.contains("Class") ? 0xFF38BDF8
                        : symbol.kind.contains("method") || symbol.kind.contains("Method")
                        || symbol.kind.contains("fonction") ? 0xFF34D399 : 0xFF71717A;
                rows.add(new ToolPanels.Row(line, symbol.name,
                        getString(R.string.panel_row_line, line + 1) + " · " + detail, color));
            }
        }
        panels.setSymbols(rows);
    }

    /** Saute le caret au début de la ligne donnée (0-based). */
    private void jumpToLine(int line) {
        if (session == null || session.getDocument() == null) return;
        int bounded = Math.max(0, Math.min(line,
                session.getDocument().lineCount() - 1));
        session.setSelection(session.getDocument().lineStart(bounded));
        editor.invalidate();
    }

    // ── Menu ─────────────────────────────────────────────────────────

    private boolean onMenuItem(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_find) {
            searchBar.open();
            return true;
        }
        if (id == R.id.action_lsp) {
            toggleLsp(!item.isChecked());
            return true;
        }
        if (id == R.id.action_grammars) {
            applyGrammarMode(!grammarActive(currentFile() != null
                    ? currentFile().editorLanguageId : ""));
            return true;
        }
        if (id == R.id.action_breakpoint) {
            toggleBreakpoint();
            return true;
        }
        if (id == R.id.action_reset) {
            OpenFile file = currentFile();
            if (file != null) {
                closeCurrentAndReopen(file);
            }
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_about) {
            showAbout();
            return true;
        }
        if (handleViewOptionMenus(id)) return true;
        return false;
    }

    /** Réouvre l'échantillon courant (réinitialisation du contenu). */
    private void closeCurrentAndReopen(OpenFile file) {
        file.analyzer.detach();
        file.session.dispose();
        int index = files.indexOf(file);
        files.remove(index);
        openSample(file.label, file.sampleId);
    }

    /** Sous-menus « vue » : numéros de ligne, plis, guides, collantes… */
    private boolean handleViewOptionMenus(int id) {
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
        if (id == R.id.folds_level_1 || id == R.id.folds_level_2 || id == R.id.folds_level_3) {
            int level = id == R.id.folds_level_1 ? 1 : id == R.id.folds_level_2 ? 2 : 3;
            if (session != null) session.collapseFoldsAtLevel(level);
            editor.invalidate();
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
        return false;
    }

    /** Point d'arrêt de démo sur la ligne du caret (API GutterAction). */
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

    private void showAbout() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private OpenFile currentFile() {
        return current >= 0 && current < files.size() ? files.get(current) : null;
    }

    // ── Sélection + recherche ────────────────────────────────────────

    /**
     * Listener primaire : barre d'état (position) + ouverture de la
     * recherche (Ctrl+F cherche le OnFindRequestedListener dans le
     * listener primaire).
     */
    private final class FindableSelectionListener
            implements EditorView.OnSelectionChangedListener,
            EditorView.OnFindRequestedListener {
        @Override
        public void onSelectionChanged(int line, int col, boolean isCursor) {
            statusBar.setPosition(line, col);
        }

        @Override
        public void onFindRequested() {
            searchBar.open();
        }
    }

    // ── Cycle de vie ─────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        editor.removeCallbacks(panelRefresher);
        lsp.shutdown();
        for (OpenFile file : files) {
            file.analyzer.detach();
            file.session.dispose();
        }
        files.clear();
        session = null;
        super.onDestroy();
    }
}
