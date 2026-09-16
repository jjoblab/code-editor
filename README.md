# Code Editor Lib (cel)

Une bibliothèque Java **standalone pour Android** qui reproduit l'architecture de l'éditeur de code de [CodeAssist](https://github.com/tyron12233/CodeAssist) — un IDE Android construit from scratch avec un éditeur custom (pas Sora Editor).

> **v3.35.0** — alignée sur l'analyse des évolutions editor de CodeAssist v3.9 → v3.20
> (portage `LineOverlay`, prefetch idle, buckets par ligne, commentaires language-driven,
> cache de layouts contenu-adressé, fold index O(log folds), correctifs lifecycle & API 24).
> Voir `RAPPORT_ANALYSE_V3.34.0.md` et `CHANGELOG.md` pour le détail.

## Modules

| Module | Rôle | Dépend de |
|--------|------|-----------|
| `:cel-core` | Moteur pur (sans Android UI) : Rope, EditorDocument, EditorSession, EditOps, FindReplace, DiagnosticShift, SnippetSession, FoldModel, WrapModel, LineRenderCache, SyntaxHighlighter, complétion | — |
| `:cel-lsp-api` | SPI langage : `Language`, providers (completion, hover, diagnostics, definition, rename, format, inlay hints…) | `:cel-core` |
| `:cel-lsp` | Intégration LSP4J : `LspProject`, `LspEditor`, serveurs in-process/socket/process | `:cel-core`, `:cel-lsp-api`, `:cel-ui` (compileOnly) |
| `:cel-ui` | Vues Android : `EditorView` (Canvas), renderer, gutter, popups, IME bridge, bar tools, breadcrumb | `:cel-core`, `:cel-lsp-api` |

## Installation

### Via JitPack (recommandé)

1. Poussez ce dépôt sur GitHub, puis créez un tag : `git tag v3.35.0 && git push origin v3.35.0`
2. Ajoutez le dépôt JitPack dans le `settings.gradle.kts` de l'app consommatrice :

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

3. Ajoutez les dépendances (JitPack remplace le groupId par `com.github.<votre-user>`) :

```kotlin
dependencies {
    // Le module UI embarque transitivement cel-core et cel-lsp-api.
    implementation("com.github.<votre-user>.code-editor:cel-ui:v3.35.0")

    // Optionnel — intégration Language Server Protocol (LSP4J).
    implementation("com.github.<votre-user>.code-editor:cel-lsp:v3.35.0")
}
```

### Via le module local

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
│                  EditorView                      │  ← Android Canvas rendering
│  (gutter, caret, selection, squiggles, touch)    │
├─────────────────────────────────────────────────┤
│               EditorSession                      │  ← Edit engine (undo/redo, IME)
│  ┌──────────┐ ┌──────────┐ ┌──────────────────┐ │
│  │ EditOps  │ │ FindRepl │ │ DiagnosticShift  │ │
│  │ (smart)  │ │ (search) │ │ (offset mapping) │ │
│  └──────────┘ └──────────┘ └──────────────────┘ │
├─────────────────────────────────────────────────┤
│ SyntaxHighlighter │ FoldModel │ WrapModel       │  ← Per-line analysis
│ LineRenderCache   │ SnippetSession              │
├─────────────────────────────────────────────────┤
│            EditorDocument                        │  ← Line-indexed text model
│  ┌──────────────────────────────────────────┐   │
│  │              Rope (balanced tree)         │   │  ← O(log N) per edit
│  └──────────────────────────────────────────┘   │
└─────────────────────────────────────────────────┘
```

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
session.dispose();       // v3.34.0
```

### EditorView (Android)

```java
EditorView editorView = new EditorView(context);
editorView.setSession(session);
editorView.setTheme(EditorTheme.DARK);
// Le prefetch idle hors viewport est actif par défaut (v3.34.0) : les
// lignes autour du viewport sont pré-chauffées après 150 ms de scroll calme.
```

### LSP ( Language Server Protocol)

```java
LspProject project = new LspProject("/path/to/workspace");
project.addServerDefinition(myServerDefinition); // ex. jdtls, EmmyLua…
LspEditor editor = project.createEditor("file:///path/to/Foo.java");
editor.setEditorView(editorView);
editor.connect();
// …
project.shutdown(); // borné à 2 s par serveur (v3.34.0)
```

## Tests

```bash
./gradlew testDebugUnitTest
# 855 tests (v3.35.0), 0 failures :
#   cel-core 637 · cel-lsp-api 22 · cel-lsp 15 · cel-ui 181
```

## Performances

| Opération | Complexité | Détail |
|-----------|-----------|--------|
| Insertion 1 char | O(log N + leafSize) | Rope replace, leaf max 512 chars |
| lineForOffset | O(log L) | Recherche binaire sur lineStarts |
| Undo | O(edited text) | Coalescence, pas de copie complète |
| Syntax highlighting | O(edited line) | Incrémental, état de sortie + cascade stop-rule |
| Cache rendu par ligne | O(1) | Triple-stamp (text + inlay + sem), LRU 512 |
| Filtre inlays/sem par ligne | O(bucket) | Index par ligne mémoïsé (v3.34.0, portage LineOverlay de CodeAssist 3.20) |
| Splice des stamps de révision | O(région) | Tableaux int[] + System.arraycopy (v3.34.0) |
| Prefetch idle | — | ±1 viewport après 150 ms de calme, chunks de 8 (v3.34.0) |
| Folds cachés au-dessus d'une ligne | O(log folds) | Index mémoïsé fusionné + prefix-sums, clé (session, foldRev, doc) (v3.35.0) |
| Ligne pour un Y écran | O(log n × log folds) | Recherche binaire sur visibleIndex = l − hiddenAbove(l) (v3.35.0) |
| maxH (scroll horizontal) | O(1) par appel | maxCols mémoïsé par (session, doc, inlayRev), scan une fois par édition (v3.35.0) |
| Layouts ligatures | O(1) sur hit | Cache contenu-adressé LRU 64, clé = texte de ligne (v3.35.0) |
| Grands fichiers | gating | > 2,5 M chars ou > 50 k lignes : analyse/folding/inlays coupés, édition conservée |

## Build

```bash
# Prérequis : JDK 17, Android SDK (platform 34, build-tools 34.0.0)
./gradlew assembleDebug          # AARs debug des 4 modules
./gradlew assembleRelease        # AARs release
./gradlew testDebugUnitTest      # tests
./gradlew lintDebug              # 0 erreur (v3.34.0)
./gradlew publishToMavenLocal    # publication Maven locale (jo.codeeditor:*)
```

Gradle wrapper **8.7** · AGP **8.5.2** · `compileSdk 34` · `minSdk 24` · Java **17**.

## Crédits

Architecture basée sur l'analyse du projet [CodeAssist](https://github.com/tyron12233/CodeAssist) par tyron12233 (évolutions v3.9 → v3.20 intégrées en v3.34.0). Implémentation en Java pur pour Android.

## Licence

MIT
