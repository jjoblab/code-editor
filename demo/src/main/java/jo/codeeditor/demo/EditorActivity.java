package jo.codeeditor.demo;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.demo.lsp.DemoLspController;
import jo.codeeditor.demo.ui.SampleDrawer;
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
 * L'atelier — coquille IDE de la démo : tiroir « Fichiers » filtrable
 * par familles de langages (badges grammaire TextMate / LSP, fichiers
 * ouverts), onglets multi-fichiers, barre de fil d'Ariane et recherche
 * intégrées, panneaux outils en bottom sheet (diagnostics / symboles /
 * journal LSP), barre d'état, bascule « moteur maison ↔ moteur LSP »
 * et bascule « grammaires TextMate ↔ voie intégrée » — la démo vit
 * les modules :cel-core, :cel-ui, :cel-lsp et :cel-textmate de bout
 * en bout.
 *
 * <p>Le contrat d'entrée reste celui de l'ancienne démo : tout ce qui
 * s'y voit fonctionne. Les extras d'intention permettent à l'écran
 * d'accueil d'ouvrir l'atelier préconfiguré
 * ({@code sample_id}, {@code sample_label}, {@code lsp}, {@code scheme}) ;
 * le retour vers l'accueil rejoue la transition inverse.</p>
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
    ToolPanels panels;

    private MaterialToolbar toolbar;
    private BreadcrumbBar breadcrumb;
    private EditorSearchBar searchBar;
    private FileTabs tabs;
    private StatusBar statusBar;
    private DrawerLayout drawer;
    private SampleDrawer sampleDrawer;
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
        // R9 (plan 09) : l'hôte a le dernier mot sur l'EditorInfo — la démo
        // coupe l'apprentissage personnalisé du clavier (frappe de code
        // privé, constante compat API < 26) et journalise les commandes
        // privées des IME propriétaires.
        editor.setOnEditorInfoListener(info -> {
            info.imeOptions |= androidx.core.view.inputmethod.EditorInfoCompat
                    .IME_FLAG_NO_PERSONALIZED_LEARNING;
        });
        editor.setOnImePrivateCommandListener((action, data) -> {
            panels.appendLog("commande privée IME : " + action);
            return true;
        });

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

        // Scène : l'éditeur occupe l'espace, les panneaux outils y
        // ancrent leur bottom sheet par-dessus (glissables).
        CoordinatorLayout stage = new CoordinatorLayout(this);
        stage.addView(editor, new CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        panels = new ToolPanels(this, this::jumpToLine);
        CoordinatorLayout.LayoutParams sheetParams = new CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetParams.gravity = Gravity.BOTTOM;
        sheetParams.setBehavior(new BottomSheetBehavior<>());
        stage.addView(panels.view(), sheetParams);
        panels.bindBehavior();
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        statusBar = new StatusBar(this);
        root.addView(statusBar.view(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        drawer.addView(root, new DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Tiroir « Fichiers » : familles filtrables, badges moteur,
        // indicateurs d'onglets ouverts — rafraîchis à chaque ouverture.
        sampleDrawer = new SampleDrawer(this, new SampleDrawer.Listener() {
            @Override
            public void onSampleSelected(String label, String sampleId) {
                drawer.closeDrawer(GravityCompat.START);
                openSample(label, sampleId);
            }

            @Override
            public void onSettings() {
                drawer.closeDrawer(GravityCompat.START);
                openSettings();
            }

            @Override
            public void onAbout() {
                drawer.closeDrawer(GravityCompat.START);
                showAbout();
            }
        }, id -> textMate != null && textMate.hasEmbeddedGrammar(id),
                this::isSampleOpen);
        drawer.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                sampleDrawer.refresh();
            }
        });
        drawer.addView(sampleDrawer.view(), new DrawerLayout.LayoutParams(
                Math.round(300 * getResources().getDisplayMetrics().density),
                ViewGroup.LayoutParams.MATCH_PARENT, GravityCompat.START));

        setContentView(drawer);
    }

    /** L'échantillon a-t-il un onglet ouvert ? (indicateur du tiroir) */
    boolean isSampleOpen(String sampleId) {
        for (OpenFile file : files) {
            if (file.sampleId.equals(sampleId)) return true;
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
        // B27 : l'échantillon XML porte un nom de layout — la carte
        // d'aperçu « goutte d'eau » (œil de gouttière) s'y ouvre avec
        // ses onglets XML/Compose et son badge « gonflé ».
        editor.setFileName("markdown".equals(file.sampleId) ? "exemple.md"
                : "html".equals(file.sampleId) ? "page.html"
                : "xml".equals(file.sampleId) ? "activity_main.xml" : null);
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
        statusBar.setGrammars(grammarActive(file.editorLanguageId),
                engineCapable(file.editorLanguageId));
        statusBar.setReadOnly(file.session.isReadOnly());
        updateLspMenuItem();
        updateGrammarMenuItem();
        updateUndoRedoMenu();
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
                "builtin.move.down", "builtin.blockdrag", "demo.breakpoint"}) {
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
        // B16 : point d'arrêt dans le débordement « ⋯ » du toolbar de
        // sélection — pose/retire le point d'arrêt de gouttière sur la
        // ligne du DÉBUT de la sélection (toggleBreakpoint lit
        // selection.start), icône disque plein du moteur EditorIcons.
        editor.addSelectionAction(new jo.codeeditor.view.SelectionAction(
                "demo.breakpoint", getString(R.string.sel_breakpoint),
                this::toggleBreakpoint,
                () -> session != null && session.getDocument() != null,
                jo.codeeditor.view.icons.EditorIcons.Icon.BREAKPOINT));
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
     * Une grammaire EMBARQUÉE existe-t-elle pour ce langage — SANS
     * qu'elle soit active ? C'est la sonde de VISIBILITÉ de la
     * bascule : {@code isAvailable} ne dit vrai qu'une fois la
     * grammaire chargée — une bascule pilotée par lui disparaîtrait au
     * premier lancement et après chaque désactivation, et il
     * deviendrait impossible de revenir sur la voie grammaires (bug
     * corrigé par la sonde {@code hasEmbeddedGrammar} du module).
     */
    private boolean engineCapable(String editorLanguageId) {
        return textMate != null && textMate.hasEmbeddedGrammar(editorLanguageId);
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
        statusBar.setGrammars(grammarActive(id), engineCapable(id));
    }

    private void updateGrammarMenuItem() {
        MenuItem item = toolbar.getMenu().findItem(R.id.action_grammars);
        if (item == null) return;
        OpenFile file = currentFile();
        // Visibilité = CAPACITÉ (une grammaire embarquée existe), pas
        // état actif : la bascule reste atteignable des deux côtés,
        // y compris avant tout chargement.
        boolean capable = file != null && engineCapable(file.editorLanguageId);
        item.setVisible(capable);
        item.setEnabled(capable);
        item.setChecked(capable && grammarActive(file.editorLanguageId));
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
        OpenFile file = currentFile();
        statusBar.setGrammars(
                file != null && grammarActive(file.editorLanguageId),
                file != null && engineCapable(file.editorLanguageId));
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
        updateUndoRedoMenu();
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
        if (id == R.id.action_undo) {
            doUndo();
            return true;
        }
        if (id == R.id.action_redo) {
            doRedo();
            return true;
        }
        if (id == R.id.action_replace) {
            showReplaceDialog();
            return true;
        }
        if (id == R.id.action_goto) {
            showGotoDialog();
            return true;
        }
        if (id == R.id.zoom_in) {
            editor.zoom.increaseFontSize();
            toastZoom();
            return true;
        }
        if (id == R.id.zoom_out) {
            editor.zoom.decreaseFontSize();
            toastZoom();
            return true;
        }
        if (id == R.id.zoom_reset) {
            editor.zoom.setFontScale(1f);
            toastZoom();
            return true;
        }
        if (id == R.id.action_read_only) {
            toggleReadOnly(item.isChecked());
            return true;
        }
        if (id == R.id.action_share) {
            shareFile();
            return true;
        }
        if (id == R.id.action_stats) {
            showStats();
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
            openSettings();
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

    /** Ouvre les Réglages avec un fondu croisé discret. */
    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
        overridePendingTransition(R.anim.screen_fade_in, R.anim.screen_fade_out);
    }

    private void showAbout() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ── Outils du fichier courant (annuler, remplacer, aller à…) ────

    /** Annule la dernière édition du document courant (pile d'annulation). */
    void doUndo() {
        if (session == null) return;
        if (session.undo()) {
            editor.invalidate();
            updateUndoRedoMenu();
            schedulePanelRefresh();
        } else {
            Toast.makeText(this, R.string.undo_empty, Toast.LENGTH_SHORT).show();
        }
    }

    /** Rétablit la dernière édition annulée. */
    void doRedo() {
        if (session == null) return;
        if (session.redo()) {
            editor.invalidate();
            updateUndoRedoMenu();
            schedulePanelRefresh();
        } else {
            Toast.makeText(this, R.string.redo_empty, Toast.LENGTH_SHORT).show();
        }
    }

    /** (Dés)active l'état verrouillé de la frappe (lecture seule). */
    void toggleReadOnly(boolean checked) {
        if (session == null) return;
        session.setReadOnly(checked);
        statusBar.setReadOnly(checked);
        Toast.makeText(this, checked ? R.string.readonly_on : R.string.readonly_off,
                Toast.LENGTH_SHORT).show();
    }

    /** Partage le texte intégral du fichier courant (intent système). */
    void shareFile() {
        if (session == null) return;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, session.getText());
        OpenFile file = currentFile();
        if (file != null) share.putExtra(Intent.EXTRA_TITLE, file.label);
        try {
            startActivity(Intent.createChooser(share, getString(R.string.share_via)));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.share_via, Toast.LENGTH_SHORT).show();
        }
    }

    /** Ajuste la taille de police et la confirme par un toast. */
    private void toastZoom() {
        Toast.makeText(this,
                getString(R.string.zoom_scale,
                        Math.round(editor.zoom.fontScale * 100f)),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * Remplace TOUTES les occurrences (remplacement en ordre inverse :
     * les offsets restent valides). Retour : nombre de remplacements —
     * exposé pour les tests.
     */
    int replaceAll(String find, String replacement) {
        if (session == null || find.isEmpty()) return 0;
        List<int[]> matches = indexOfAll(session.getText(), find);
        if (matches.isEmpty()) {
            Toast.makeText(this, getString(R.string.replace_none_found, find),
                    Toast.LENGTH_SHORT).show();
            return 0;
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            int[] match = matches.get(i);
            session.replaceRange(match[0], match[1], replacement);
        }
        editor.invalidate();
        updateUndoRedoMenu();
        schedulePanelRefresh();
        Toast.makeText(this, getString(R.string.replace_all_done, matches.size()),
                Toast.LENGTH_SHORT).show();
        return matches.size();
    }

    /**
     * Remplace l'occurrence suivante (à partir du caret, avec
     * rebouclage). Retour : succès — exposé pour les tests.
     */
    boolean replaceNext(String find, String replacement) {
        if (session == null || find.isEmpty()) return false;
        String text = session.getText();
        int caret = session.getSelection().start;
        int index = text.indexOf(find, caret);
        if (index < 0) {
            index = text.indexOf(find, 0);
        }
        if (index < 0) {
            Toast.makeText(this, getString(R.string.replace_none_found, find),
                    Toast.LENGTH_SHORT).show();
            return false;
        }
        session.replaceRangeWithCaret(index, index + find.length(),
                replacement, index + replacement.length());
        editor.invalidate();
        updateUndoRedoMenu();
        schedulePanelRefresh();
        int remaining = indexOfAll(session.getText(), find).size();
        Toast.makeText(this, getString(R.string.replace_next_done, remaining),
                Toast.LENGTH_SHORT).show();
        return true;
    }

    /** Toutes les occurrences (intervalles fermé/ouvert) d'un texte. */
    private static List<int[]> indexOfAll(String text, String find) {
        List<int[]> out = new ArrayList<>();
        int index = text.indexOf(find, 0);
        while (index >= 0) {
            out.add(new int[]{index, index + find.length()});
            index = text.indexOf(find, index + Math.max(1, find.length()));
        }
        return out;
    }

    /** Dialogue « Rechercher et remplacer » du document courant. */
    private void showReplaceDialog() {
        if (session == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText find = field(R.string.replace_find_hint);
        final EditText with = field(R.string.replace_with_hint);
        box.addView(find);
        box.addView(with);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.replace_title)
                .setView(box)
                .setPositiveButton(R.string.replace_all, (d, w) ->
                        replaceAll(find.getText().toString(), with.getText().toString()))
                .setNeutralButton(R.string.replace_next, (d, w) ->
                        replaceNext(find.getText().toString(), with.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Dialogue « Aller à la ligne » (1-based en entrée). */
    private void showGotoDialog() {
        if (session == null || session.getDocument() == null) return;
        int lineCount = session.getDocument().lineCount();
        final EditText input = field(R.string.goto_hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input);

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.goto_title, lineCount))
                .setView(box)
                .setPositiveButton(R.string.goto_ok, (d, w) -> {
                    try {
                        int line = Integer.parseInt(input.getText().toString().trim());
                        jumpToLine(line - 1);
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, R.string.goto_invalid,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Statistiques du document courant (lignes, mots, caractères). */
    private void showStats() {
        if (session == null) return;
        String text = session.getText();
        int lines = session.getDocument() != null
                ? session.getDocument().lineCount() : 0;
        int nonEmpty = 0;
        int words = 0;
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) nonEmpty++;
            for (String word : line.trim().split("\\s+")) {
                if (!word.isEmpty()) words++;
            }
        }
        jo.codeeditor.document.Selection selection = session.getSelection();
        int selectionChars = Math.abs(selection.end - selection.start);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.stats_title)
                .setMessage(getString(R.string.stats_lines, lines) + "\n"
                        + getString(R.string.stats_non_empty, nonEmpty) + "\n"
                        + getString(R.string.stats_words, words) + "\n"
                        + getString(R.string.stats_chars, text.length()) + "\n"
                        + getString(R.string.stats_selection, selectionChars))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Champ de saisie stylé pour les dialogues outils. */
    private EditText field(int hintRes) {
        EditText input = new EditText(this);
        input.setHint(hintRes);
        input.setSingleLine(true);
        input.setTextSize(14f);
        return input;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Grise/dégrise Annuler et Rétablir selon les piles de la session. */
    void updateUndoRedoMenu() {
        Menu menu = toolbar.getMenu();
        MenuItem undo = menu.findItem(R.id.action_undo);
        MenuItem redo = menu.findItem(R.id.action_redo);
        if (undo != null) undo.setEnabled(session != null
                && session.getUndoManager().canUndo());
        if (redo != null) redo.setEnabled(session != null
                && session.getUndoManager().canRedo());
    }

    /** La bascule « Grammaires TextMate » est-elle VISIBLE ? (test fumée) */
    boolean grammarToggleVisible() {
        MenuItem item = toolbar.getMenu().findItem(R.id.action_grammars);
        return item != null && item.isVisible();
    }

    /** La bascule « Grammaires TextMate » est-elle COCHÉE ? (test fumée) */
    boolean grammarToggleChecked() {
        MenuItem item = toolbar.getMenu().findItem(R.id.action_grammars);
        return item != null && item.isChecked();
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

    /**
     * Retour vers l'accueil : rejoue la transition inverse de
     * l'entrée (remontée + fondu de l'accueil, redescente de
     * l'atelier) — miroir exact de l'aller, quelle que soit la voie
     * de sortie (système, dernier onglet fermé, fin d'activité).
     */
    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.home_reenter, R.anim.atelier_exit);
    }

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
