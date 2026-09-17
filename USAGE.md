# Guide d'utilisation

Guide complet d'intégration de la bibliothèque dans une application Android.

## Installation

### Via GitHub Packages (méthode principale)

```properties
# ~/.gradle/gradle.properties
gpr.user=<votre-utilisateur-github>
gpr.key=<votre-jeton-d-acces>   # droit read:packages
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/jjoblab/code-editor")
            credentials {
                username = providers.gradleProperty("gpr.user").get()
                password = providers.gradleProperty("gpr.key").get()
            }
        }
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    // Le module UI embarque transitivement cel-core et cel-lsp-api.
    implementation("jo.codeeditor:cel-ui:3.37.0")

    // Optionnel — intégration Language Server Protocol (LSP4J).
    implementation("jo.codeeditor:cel-lsp:3.37.0")
}
```

### Via JitPack (alternative de secours)

```kotlin
// settings.gradle.kts — dépôt supplémentaire
maven { url = uri("https://jitpack.io") }

// app/build.gradle.kts
dependencies {
    implementation("com.github.jjoblab:cel-ui:v3.37.0")
}
```

### Via les modules locaux

```kotlin
// settings.gradle.kts
include(":cel-core", ":cel-lsp-api", ":cel-lsp", ":cel-ui")
project(":cel-core").projectDir = file("code-editor/editor/cel-core")
project(":cel-lsp-api").projectDir = file("code-editor/editor/cel-lsp-api")
project(":cel-lsp").projectDir = file("code-editor/editor/cel-lsp")
project(":cel-ui").projectDir = file("code-editor/editor/cel-ui")

// app/build.gradle.kts
dependencies {
    implementation(project(":cel-ui"))
}
```

## Prérequis

- **minSdk 24** (Android 7.0)
- **compileSdk 34**
- **JDK 17 minimum** pour la toolchain de build (AGP 9)

## Démarrage rapide

### 1. Ajouter l'EditorView

```java
import jo.codeeditor.view.EditorView;
import jo.codeeditor.session.EditorSession;
import jo.codeeditor.document.EditorDocument;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EditorView editorView = new EditorView(this);
        setContentView(editorView);

        // Créer une session avec le texte initial et la brancher sur la vue.
        EditorSession session = new EditorSession(EditorDocument.of("Hello, world!"));
        editorView.setSession(session);
    }
}
```

C'est tout — l'éditeur est fonctionnel : tap pour le focus, frappe IME,
scroll, pinch-zoom, undo/redo (Ctrl+Z / Ctrl+Y), etc.

### 2. Définir le langage

Le colorateur syntaxique reconnaît 27 langages intégrés (java, kotlin,
javascript, typescript, c, cpp, go, rust, swift, dart, php, ruby, scala,
groovy, python, lua, xml/html, css, json, yaml, sql, shell, properties,
toml, smali, log, markdown) :

```java
session.setLanguage("java");
```

### 3. Lire le texte

```java
String text = session.getText();
```

### 4. Écouter les changements de sélection

```java
editorView.setOnSelectionChangedListener((line, col, isCursor) -> {
    Log.d("Editor", "Caret ligne " + line + ", col " + col);
});
```

### 5. Libérer la session

```java
@Override
protected void onDestroy() {
    super.onDestroy();
    session.dispose(); // libère le thread de restyle asynchrone
}
```

## Configuration

### Thèmes

```java
import jo.codeeditor.view.chrome.EditorTheme;

// Thème sombre intégré (VS Code Dark+)
editorView.setTheme(EditorTheme.dark());

// Thème clair intégré
editorView.setTheme(EditorTheme.light());

// Thème personnalisé (31 couleurs ; les couleurs « verre » des popups et
// le vert de log SUCCESS sont dérivées automatiquement du fond)
EditorTheme custom = new EditorTheme(
    /* editorBg = */ 0xFF1E1E1E,
    /* gutterBg = */ 0xFF1E1E1E,
    /* gutterText = */ 0xFF808080,
    /* gutterBorder = */ 0xFF323232,
    /* caret = */ 0xFFDCDCDC,
    /* selection = */ 0xFF264F78,
    /* currentLine = */ 0xFF262626,
    /* error = */ 0xFFFF0000,
    /* warning = */ 0xFFFFCC00,
    /* info = */ 0xFF0078D7,
    /* keyword = */ 0xFF569CD6,
    /* string = */ 0xFFCE9178,
    /* comment = */ 0xFF6A9955,
    /* number = */ 0xFFB5CEA8,
    /* annotation = */ 0xFFD7BA7D,
    /* func = */ 0xFFDCDCAA,
    /* type = */ 0xFF4EC9B0,
    /* punct = */ 0xFFD4D4D4,
    /* operator = */ 0xFFD4D4D4,
    /* escape = */ 0xFFCE9178,
    /* label = */ 0xFFC8C8C8,
    /* property = */ 0xFF9CDCFE,
    /* variable = */ 0xFF9CDCFE,
    /* constant = */ 0xFF4FC1FF,
    /* regexp = */ 0xFFD16969,
    /* findMatch = */ 0xFF613315,
    /* findCurrent = */ 0xFF6B6B2A,
    /* occurrence = */ 0xFF57572C,
    /* indentGuide = */ 0xFF404040,
    /* composing = */ 0xFF555555,
    /* textColor = */ 0xFFD4D4D4
);
editorView.setTheme(custom);
```

### Taille de police / zoom

```java
// Définir l'échelle de police (1.0 = 14sp par défaut, bornée à [0.6, 2.6]).
editorView.setFontScale(1.5f);

// Ou Ctrl+Plus / Ctrl+Minus / Ctrl+0 (raccourcis intégrés).
```

### Retour à la ligne, minimap et autres bascules

```java
editorView.setWordWrap(true);              // retour à la ligne (défaut : false)
editorView.setMinimapEnabled(true);        // bande minimap latérale
editorView.setShowNonPrintable(true);      // caractères non imprimables
editorView.setFontLigatures(true);         // ligatures de police
editorView.setTouchHoverEnabled(true);     // hover par appui long (500 ms)
editorView.setDiagnosticChipsEnabled(true);// chips de diagnostics dans le gutter
```

## Diagnostics et décorations

### Diagnostics (erreurs / avertissements)

```java
import jo.codeeditor.shift.DiagnosticShift;
import java.util.Arrays;
import java.util.List;

List<DiagnosticShift.Diagnostic> diags = Arrays.asList(
    new DiagnosticShift.Diagnostic(5, 10, 3, "Syntax error"),   // sévérité 3 = erreur
    new DiagnosticShift.Diagnostic(20, 25, 2, "Unused variable") // sévérité 2 = warning
);
session.setDiagnostics(diags);
editorView.invalidate(); // déclenche un redraw pour afficher les squiggles
```

### Régions de pli

```java
List<DiagnosticShift.FoldRegion> folds = Arrays.asList(
    // start, end, placeholder, kind, collapsed
    new DiagnosticShift.FoldRegion(10, 50, "{...}", "block", false)
);
session.setFoldRegions(folds);
editorView.invalidate();
```

### Inlay hints

```java
List<DiagnosticShift.InlayHint> hints = Arrays.asList(
    // offset, texte, avant le caret ?
    new DiagnosticShift.InlayHint(15, ": String", false)
);
session.setInlayHints(hints);
editorView.invalidate();
```

### Jetons sémantiques (façon LSP)

```java
List<DiagnosticShift.SemanticToken> tokens = Arrays.asList(
    new DiagnosticShift.SemanticToken(5, 10, 9) // start, longueur, type (9 = method)
);
session.setSemanticTokens(tokens);
editorView.invalidate();
```

Types de jetons : 0=namespace, 1=type, 2=class, 3=enum, 4=interface,
5=struct, 6=parameter, 7=variable, 8=property, 9=method, 10=function,
11=keyword, 12=number, 13=string, 14=comment.

### Surlignages de recherche

```java
import jo.codeeditor.find.FindReplace;
import jo.codeeditor.find.FindOptions;
import jo.codeeditor.find.Match;

List<Match> matches = FindReplace.findMatches(
        session.getText(), "query",
        new FindOptions(/* caseSensitive = */ true, /* wholeWord = */ false, /* regex = */ false));
editorView.setFindHighlights(matches, 0); // 0 = index du match courant
```

## Résolveurs — brancher son propre moteur de langage

Tous les résolveurs sont optionnels : sans eux, l'éditeur retombe sur son
comportement intégré (complétion par mots-clés, hint de signature synthétique,
pas de quick doc…).

### Complétion

```java
editorView.setCompletionProvider((text, caret, tokenStart, prefix) -> {
    // Interrogez votre language server ici.
    List<CompletionSession.Item> items = myLanguageServer.getCompletions(text, caret);
    return items;
});
```

### Aide de signature (Ctrl+P)

```java
editorView.setSignatureHelpResolver((text, caret) -> {
    // Retourne l'aide de signature au caret, ou null.
    return myLanguageServer.getSignatureHelp(text, caret);
});
```

### Quick doc (F1 / hover)

```java
editorView.setQuickDocResolver((text, offset) -> {
    // Retourne le texte Javadoc/KDoc brut du symbole à offset, ou null.
    return myLanguageServer.getDocComment(text, offset);
});
```

### Code actions (Ctrl+.)

```java
editorView.setCodeActionsResolver((text, line) -> {
    List<EditorView.CodeAction> actions = new ArrayList<>();
    actions.add(new EditorView.CodeAction(
        "Add missing import", "quickfix",
        () -> session.commitText("import foo.Bar;\n")
    ));
    return actions;
});
```

### Aller-au-symbole (Ctrl+Shift+O)

```java
editorView.setSymbolResolver((text) -> {
    // Retourne TOUS les symboles du document — la vue filtre à chaque frappe.
    return myLanguageServer.getDocumentSymbols(text);
});
```

### Autres résolveurs

`setDefinitionResolver`, `setTypeDefinitionResolver`,
`setImplementationsResolver`, `setSuperResolver`, `setReferencesResolver`,
`setRenameResolver`, `setFormatterResolver`,
`setDocumentHighlightResolver` — même principe : la vue appelle le résolveur
à la commande clavier correspondante et affiche le résultat dans le popup
adapté.

## Raccourcis clavier

| Raccourci | Action |
|----------|--------|
| `Ctrl+Z` / `Ctrl+Y` | Annuler / rétablir |
| `Ctrl+A` | Tout sélectionner |
| `Ctrl+C` / `Ctrl+X` / `Ctrl+V` | Copier / couper / coller |
| `Ctrl+D` | Dupliquer la sélection |
| `Ctrl+F` | Ouvrir la barre de recherche (l'hôte doit implémenter `OnFindRequestedListener`) |
| `Ctrl+S` | Enregistrer (l'hôte doit implémenter `OnSaveRequestedListener`) |
| `Ctrl+Space` | Déclencher la complétion |
| `Ctrl+P` | Aide de signature |
| `Ctrl+.` | Code actions |
| `Ctrl+Shift+O` | Aller au symbole |
| `Ctrl+Shift+I` | Formater le document |
| `Ctrl+Shift+L` | Code actions au caret |
| `F1` | Quick doc |
| `F2` | Renommer |
| `F12` / `Shift+F12` | Aller à la définition / trouver les références |
| `Ctrl+G` | Aller à la ligne |
| `Ctrl+Plus` / `Ctrl+Minus` / `Ctrl+0` | Zoom avant / arrière / reset |
| `Échap` | Fermer le popup / annuler un chord en cours |

### Rebind et chords

```java
// Rebind simple :
editorView.setKeymap(EditorKeymap.defaults()
        .bind(EditorCommands.REDO, KeyEvent.KEYCODE_Z, true, true)); // Ctrl+Shift+Z

// Séquence à deux touches (chord) — pending de 2 s, Échap annule :
editorView.setKeymap(EditorKeymap.defaults()
        .bindChord(EditorCommands.TOGGLE_LINE_COMMENT,
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_K, true, false),
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_C, true, false)));
// Ctrl+K Ctrl+C = commenter (style IntelliJ)
```

## Aperçu XML

L'éditeur délègue le rendu d'aperçu à l'hôte via `EditorPreviewHost` :

```java
editorView.setPreviewHost(new XmlPreviewHost(this, editorView));
editorView.setFileName("layout.xml");
// → EditorView interroge previewHost.canPreview("layout.xml")
// → l'utilisateur tape une icône d'aperçu : setPreviewMode(SPLIT ou FULL)
// → l'hôte dessine via drawPreview(canvas, offsetX, offsetY)
```

Le contrat complet (6 méthodes : `canPreview`, `onPreviewModeChanged`,
`onPreviewContentChanged`, `drawPreview`, `hasPreviewContent`,
`hitTestPreview`) est décrit dans
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## LSP (Language Server Protocol)

Le module `:cel-lsp` connecte l'éditeur à des serveurs LSP réels via LSP4J :

```java
import jo.codeeditor.lsp.LspProject;
import jo.codeeditor.lsp.LspEditor;

LspProject project = new LspProject("/path/to/workspace");
project.addServerDefinition(myServerDefinition); // ex. jdtls, EmmyLua…
LspEditor editor = project.createEditor("file:///path/to/Foo.java");
editor.setEditorView(editorView);
editor.connect();   // CompletableFuture — didOpen envoyé à la connexion
// …
project.shutdown(); // borné à 2 s par serveur
```

Cinq providers de connexion sont disponibles (`lsp/connection/`) : flux
directs, in-process, socket local Unix, `ProcessBuilder`, socket TCP.

## ProGuard / R8

La bibliothèque est consommable en debug sans minification R8. Si vous
activez R8 en release, ajoutez ces règles à `proguard-rules.pro` :

```proguard
# code-editor — conserver la surface d'API publique.
-keep public class jo.codeeditor.view.EditorView { *; }
-keep public class jo.codeeditor.view.chrome.EditorTheme { *; }
-keep public class jo.codeeditor.view.EditorMetrics { *; }
-keep public class jo.codeeditor.view.EditorKeymap { *; }
-keep public class jo.codeeditor.session.EditorSession { *; }
-keep public class jo.codeeditor.document.EditorDocument { *; }
-keep public class jo.codeeditor.document.Selection { *; }
-keep public class jo.codeeditor.shift.DiagnosticShift$* { *; }
-keep public class jo.codeeditor.completion.CompletionSession$* { *; }
-keep public class jo.codeeditor.navigation.NavigationMenu$* { *; }
-keep public class jo.codeeditor.cache.LineRenderCache$* { *; }

# Le rendu Canvas n'utilise pas la réflexion — aucune règle keep
# supplémentaire n'est nécessaire pour la couche View.
```

## Dépannage

### Le clavier n'apparaît pas au tap

Le clavier n'apparaît qu'après un **tap explicite** dans la zone de texte
(pas sur le focus seul). C'est voulu — ouvrir un fichier ou changer d'onglet
ne doit pas faire popper l'IME. Pour l'afficher programmatiquement :

```java
editorView.showSoftKeyboard();
```

### Le curseur disparaît après un undo

`EditorSession.undo()`/`redo()` notifient l'`ImeListener`, qui relance le
clignotement du caret. Si vous appelez `session.undo()` depuis du code
personnalisé, appelez aussi `editorView.onTextChanged()` (ou utilisez le
raccourci Ctrl+Z qui le fait pour vous).

### Un tap sur une suggestion de complétion ne fait rien

Le hit-test du popup de complétion route les taps vers
`completionAccept()`. Vérifiez que vous n'interceptez pas les événements
tactiles avant l'EditorView (overlay plein écran, etc.).

### Le chevron de pli chevauche le numéro de ligne

La largeur du gutter inclut une colonne dédiée au strip de pli. Si vous
surchargez `EditorMetrics`, laissez cette marge intacte.

## Pour aller plus loin

- [`FEATURES.md`](FEATURES.md) — matrice complète des fonctionnalités.
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — organisation des modules et des packages.
- [`CHANGELOG.md`](CHANGELOG.md) — historique des versions.
