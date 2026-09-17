# Code Editor (cel)

Une bibliothèque Java **standalone pour Android** qui fournit un éditeur de code
complet — moteur d'édition pur, coloration syntaxique incrémentale, couche
Android Canvas et intégration LSP — construite from scratch (pas Sora Editor).

> L'historique complet des versions figure dans [`CHANGELOG.md`](CHANGELOG.md).
> Les fonctionnalités sont détaillées dans [`FEATURES.md`](FEATURES.md), le
> guide d'intégration dans [`USAGE.md`](USAGE.md).

## Modules

| Module | Rôle | Dépend de |
|--------|------|-----------|
| `:cel-core` | Moteur pur (sans Android UI) : Rope, EditorDocument, EditorSession, EditOps, FindReplace, DiagnosticShift, SnippetSession, FoldModel, WrapModel, LineRenderCache, SyntaxHighlighter, complétion, registre de langages | — |
| `:cel-lsp-api` | SPI langage : `Language`, 17 interfaces de providers (completion, hover, diagnostics, definition, rename, format, inlay hints…) et 15 classes de données | `:cel-core` |
| `:cel-lsp` | Intégration LSP4J : `LspProject`, `LspEditor`, serveurs in-process/socket/process | `:cel-core`, `:cel-lsp-api`, `:cel-ui` (compileOnly) |
| `:cel-ui` | Vues Android : `EditorView` (Canvas), renderer et painters, gutter, popups, IME bridge, bar tools, breadcrumb, minimap | `:cel-core`, `:cel-lsp-api` |

## Installation

### Via GitHub Packages (méthode principale)

Les artefacts sont publiés sur `maven.pkg.github.com` avec les coordonnées
`jo.codeeditor:<module>:<version>` (version courante : `3.37.0`).

1. Créez un jeton d'accès personnel GitHub avec le droit `read:packages`.
2. Renseignez vos identifiants dans `~/.gradle/gradle.properties` :

```properties
gpr.user=<votre-utilisateur-github>
gpr.key=<votre-jeton-d-acces>
```

3. Ajoutez le dépôt et les dépendances dans l'application consommatrice :

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
    // Ses dépendances vers cel-core/cel-lsp-api/cel-ui sont compileOnly :
    // gardez la ligne cel-ui ci-dessus.
    implementation("jo.codeeditor:cel-lsp:3.37.0")
}
```

### Via JitPack (alternative de secours)

Si GitHub Packages n'est pas accessible, JitPack construit la bibliothèque à
la demande depuis un tag Git (le fichier `jitpack.yml` épingle le JDK 17) :

```kotlin
// settings.gradle.kts — ajouter le dépôt JitPack
maven { url = uri("https://jitpack.io") }
```

```kotlin
dependencies {
    implementation("com.github.jjoblab:cel-ui:v3.37.0")
    implementation("com.github.jjoblab:cel-lsp:v3.37.0") // optionnel
}
```

### Via les modules locaux (inclusion des sources)

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

## Architecture

L'éditeur repose sur une architecture en couches séparées, où chaque composant est **pur** (sans dépendance UI) et **testable unitairement** :

```
┌─────────────────────────────────────────────────┐
│                  EditorView                      │  ← Rendu Android Canvas
│  (gutter, caret, sélection, squiggles, touch)    │
├─────────────────────────────────────────────────┤
│               EditorSession                      │  ← Moteur d'édition (undo/redo, IME)
│  ┌──────────┐ ┌──────────┐ ┌──────────────────┐ │
│  │ EditOps  │ │ FindRepl │ │ DiagnosticShift  │ │
│  │ (smart)  │ │ (search) │ │ (offset mapping) │ │
│  └──────────┘ └──────────┘ └──────────────────┘ │
├─────────────────────────────────────────────────┤
│ SyntaxHighlighter │ FoldModel │ WrapModel       │  ← Analyse par ligne
│ LineRenderCache   │ SnippetSession              │
├─────────────────────────────────────────────────┤
│            EditorDocument                        │  ← Modèle de texte indexé par ligne
│  ┌──────────────────────────────────────────┐   │
│  │              Rope (arbre équilibré)       │   │  ← O(log N) par édition
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

Voir [`ARCHITECTURE.md`](ARCHITECTURE.md) pour l'organisation détaillée des
packages de chaque module.

## Utilisation rapide

### Éditeur basique (pur Java, testable)

```java
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

EditorDocument doc = EditorDocument.of("public class Hello {\n    \n}");
EditorSession session = new EditorSession(doc);
session.setLanguage("java");

session.setSelection(30);
session.typeChar('(');   // auto-close : Sys(|)
session.undo();          // annule

// Important : quand la session n'est plus utilisée (onDestroy de l'hôte),
// libérez son thread de restyle :
session.dispose();
```

### EditorView (Android)

```java
EditorView editorView = new EditorView(context);
editorView.setSession(session);
editorView.setTheme(EditorTheme.dark());
```

### Extensibilité

```java
// 1. Enregistrer son propre langage — coloration + commentaires pris en
//    charge immédiatement (registre observable) :
LanguageRegistry.register(LanguageProfile.builder("mylang")
        .family(SyntaxFamily.C_LIKE)
        .alias("ml").extension("ml")
        .keywords("if", "else", "repeat", "until")
        .commentSyntax(new CommentSyntax("#", null, null))
        .build());
session.setLanguage("mylang");

// 2. Rebind un raccourci clavier :
editorView.setKeymap(EditorKeymap.defaults()
        .bind(EditorCommands.REDO, KeyEvent.KEYCODE_Z, true, true)); // Ctrl+Shift+Z

// 2b. Ou lie une séquence à deux touches (chord) — la première touche
//     arme un pending de 2 s, Escape annule :
editorView.setKeymap(EditorKeymap.defaults()
        .bindChord(EditorCommands.TOGGLE_LINE_COMMENT,
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_K, true, false),
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_C, true, false))
        .bindChord(EditorCommands.TOGGLE_BLOCK_COMMENT,
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_K, true, false),
                EditorKeymap.KeyStroke.of(KeyEvent.KEYCODE_U, true, false)));
// Ctrl+K Ctrl+C = commenter, Ctrl+K Ctrl+U = décommenter (style IntelliJ)

// 3. Décorer l'éditeur depuis un plugin (un painter qui throw est retiré,
//    jamais un crash) :
editorView.getPainterHost().register(new TodoPainter());

// 4. Rafraîchir les diagnostics de tous les onglets ouverts (gap 40 ms,
//    saute focus/read-only/gros fichiers) :
OpenTabDiagnosticsSweep.start(openEditorViews);
```

### LSP (Language Server Protocol)

```java
LspProject project = new LspProject("/path/to/workspace");
project.addServerDefinition(myServerDefinition); // ex. jdtls, EmmyLua…
LspEditor editor = project.createEditor("file:///path/to/Foo.java");
editor.setEditorView(editorView);
editor.connect();
// …
project.shutdown(); // borné à 2 s par serveur
```

Le guide complet (thèmes, diagnostics, résolveurs, raccourcis, aperçu XML)
figure dans [`USAGE.md`](USAGE.md).

## Tests

```bash
./gradlew test
# 934 tests, 0 failure :
#   cel-core 655 · cel-lsp-api 22 · cel-lsp 15 · cel-ui 230
```

## Performances

| Opération | Complexité | Détail |
|-----------|-----------|--------|
| Insertion 1 char | O(log N + leafSize) | Rope replace, feuille max 512 chars |
| lineForOffset | O(log L) | Recherche binaire sur lineStarts |
| Undo | O(texte édité) | Coalescence, pas de copie complète |
| Coloration syntaxique | O(ligne éditée) | Incrémentale, état de sortie + cascade stop-rule |
| Cache rendu par ligne | O(1) | Triple-stamp (texte + inlay + sem), LRU 512 |
| Filtre inlays/sem par ligne | O(bucket) | Index par ligne mémoïsé |
| Splice des stamps de révision | O(région) | Tableaux int[] + System.arraycopy |
| Prefetch idle | — | ±1 viewport après 150 ms de calme, chunks de 8 |
| Folds cachés au-dessus d'une ligne | O(log folds) | Index mémoïsé fusionné + prefix-sums |
| Ligne pour un Y écran | O(log n × log folds) | Recherche binaire sur visibleIndex = l − hiddenAbove(l) |
| maxH (scroll horizontal) | O(1) par appel | maxCols mémoïsé, scan une fois par édition |
| Layouts ligatures | O(1) sur hit | Cache contenu-adressé LRU 64, clé = texte de ligne |
| Diagnostics par ligne de début | O(bucket) | Buckets mémoïsés, tri sévérité-desc |
| Chips diagnostics | O(lignes visibles) | Itération des buckets groupés, badge de compte |
| Grands fichiers | gating | > 2,5 M chars ou > 50 k lignes : analyse/folding/inlays coupés, édition conservée |

## Build

```bash
# Prérequis : JDK 17+, Android SDK (platform 34)
./gradlew assembleDebug          # AARs debug des 4 modules
./gradlew assembleRelease        # AARs release
./gradlew test                   # tests (AGP 9 : la variante debug porte les tests unitaires)
./gradlew lint                   # lint des 4 modules
./gradlew publishToMavenLocal    # publication Maven locale (jo.codeeditor:*)
```

Gradle wrapper **9.5.1** · AGP **9.0.0** · `compileSdk 34` · `minSdk 24` ·
Java **17 minimum** (21 validé) · encodage source **UTF-8**.

Les instructions complètes (installation de la toolchain, publication Maven)
figurent dans [`BUILD.md`](BUILD.md).

## CI

Le workflow GitHub Actions (`.github/workflows/ci.yml`) exécute sur chaque
push (main) et chaque PR : `assembleDebug` + `testDebugUnitTest` + `lint` sur
JDK 17 Temurin, avec cache Gradle et validation des wrapper JARs
(`gradle/actions/setup-gradle`). Sur chaque tag `v*`, un job release ajoute
`assembleRelease` + `publishToMavenLocal` (validation de la publication) et
uploade les AAR/POM en artefacts. La publication sur GitHub Packages est
manuelle (`./gradlew publish`, voir [`BUILD.md`](BUILD.md)) : aucun workflow
automatisé de publication n'existe à ce jour.

## Crédits

L'architecture s'inspire du projet [CodeAssist](https://github.com/tyron12233/CodeAssist)
par tyron12233 — implémentation indépendante en Java pour Android, sans code
commun (voir `NOTICE`).

## Licence

MIT
