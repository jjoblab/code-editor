# Code Editor (cel)

Une bibliothèque **Java** pour Android qui fournit un éditeur de code
complet : moteur d'édition pur, coloration syntaxique incrémentale
(voie intégrée et voie grammaires), couche de rendu **Canvas** et
intégration Language Server Protocol.

Ce que c'est — et ce que ce n'est pas :

- **Java, pas de Kotlin** ; **Canvas, ni Compose ni TextView par
  ligne** — une seule vue dessine tout.
- **Le moteur est écrit intégralement dans ce dépôt** ; les dépendances
  réellement embarquées (LSP4J, Gson) et les grammaires tierces du
  module optionnel sont attribuées dans [`NOTICE`](NOTICE).
- **Publication** : coordonnées Maven `jo.codeeditor:<module>` —
  version courante : `4.5.0` (les paliers P2 et P3 du plan de rattrapage :
  gabarits riches — choix et macros —, apprentissage du classement,
  barre de touches de code, mode gros fichiers, renommage XML lié,
  postfix par profil, compensation du viewport ; sur la
  4.3.0 du popup qui honore les listes tronquées, la 4.2.0 des yeux
  d'aperçu ancrés aux lignes des `@Preview`, la 4.1.0 du corps de
  carte tactile et la refonte 4.0.0 du moteur de regex de
  `cel-textmate` — voir
  [`CHANGELOG.md`](CHANGELOG.md)).

## Modules

Cinq modules publiables et une application de démonstration — chacun a
son [`README.md`](#documentation) détaillé :

| Module | Rôle | Dépend de | Détail |
|--------|------|-----------|--------|
| `:cel-core` | Moteur pur (sans Android UI) : rope, session, undo, coloration incrémentale, plis, wrap, complétion, snippets, recherche, registre de 29 langages | — | [`editor/cel-core`](editor/cel-core/README.md) |
| `:cel-lsp-api` | SPI langage : `Language`, 17 interfaces de providers, 15 modèles de données, contrat hôte `EditorHost` | `:cel-core` | [`editor/cel-lsp-api`](editor/cel-lsp-api/README.md) |
| `:cel-lsp` | Intégration LSP4J : projet multi-serveurs, synchronisation document, requêtes bornées | `:cel-core`, `:cel-lsp-api` (compileOnly) | [`editor/cel-lsp`](editor/cel-lsp/README.md) |
| `:cel-textmate` | **Optionnel** — voie grammaires (ADR-002 C3) : moteur de grammaires, mapping portées → `TokenType`, grammaires embarquées | `:cel-core` seul | [`editor/cel-textmate`](editor/cel-textmate/README.md) |
| `:cel-ui` | Vues Android : `EditorView` (Canvas), renderer, gestes, popups, gutter, recherche, fil d'Ariane, minimap | `:cel-core`, `:cel-lsp-api` | [`editor/cel-ui`](editor/cel-ui/README.md) |
| `:demo` | Terrain de jeu (bascule voie intégrée/grammaires) — **non publiée** | `:cel-ui`, `:cel-textmate` | [`demo`](demo/README.md) |

L'application hôte type ne dépend que de `cel-ui` (qui apporte
transitivement le moteur et le SPI) ; `cel-lsp` et `cel-textmate` sont
optionnels et s'ajoutent selon les besoins.

## Documentation

| Document | Contenu |
|----------|---------|
| [`FEATURES.md`](FEATURES.md) | Matrice factuelle des fonctionnalités (implémenté ✅ / piste 🧭), par module et fichier |
| [`USAGE.md`](USAGE.md) | Guide d'intégration : thèmes, diagnostics, résolveurs, raccourcis, aperçu XML, R8, dépannage |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Couches, cycle d'édition, organisation interne des packages, interface `EditorPreviewHost` |
| [`BUILD.md`](BUILD.md) | Toolchain, commandes, publication Maven (GitHub Packages, JitPack) |
| [`CHANGELOG.md`](CHANGELOG.md) | Historique des versions (archives : [`docs/archive/CHANGELOG-archive.md`](docs/archive/CHANGELOG-archive.md)) |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Mise en place de l'environnement, conventions, processus de PR |
| [`AGENT.md`](AGENT.md) | Guide des contributeurs agents : commandes vérifiées, pièges connus, règles du dépôt |
| [`SECURITY.md`](SECURITY.md) | Versions prises en charge, signalement des vulnérabilités |
| `editor/<module>/README.md`, `demo/README.md` | Par module : rôle, fonctionnalités, liaisons, limites |
| [`docs/adr/`](docs/adr/) | Décisions d'architecture enregistrées (ADR-001 audit 3.69.0, ADR-002 multi-langages) |
| [`docs/plans/`](docs/plans/) | Plans de travail en cours (décomposition, stabilité API, minification…) |
| [`docs/archive/`](docs/archive/) | Rapports historiques figés (audits, livraisons passées) — index et politique |

## Installation

### Via GitHub Packages (méthode principale)

Les artefacts sont publiés sur `maven.pkg.github.com` avec les
coordonnées `jo.codeeditor:<module>:<version>` (version courante :
`4.4.0`).

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
    implementation("jo.codeeditor:cel-ui:4.5.0")

    // Optionnel — intégration Language Server Protocol (LSP4J).
    // Ses dépendances vers cel-core/cel-lsp-api sont compileOnly :
    // gardez la ligne cel-ui ci-dessus (l'adaptateur LspEditorViewHost
    // du pont LSP y vit — plan 03 : le client LSP ne dépend plus de la vue).
    implementation("jo.codeeditor:cel-lsp:4.5.0")

    // Optionnel — voie grammaires (ADR-002 C3) : coloration par
    // grammaires TextMate, grammaires embarquées + registration
    // hôte (supporter un langage de plus = un FICHIER, pas du Java).
    implementation("jo.codeeditor:cel-textmate:4.5.0")
    // … puis UNE fois au démarrage, avant toute vue éditeur :
    // SyntaxHighlighter.setTextMateTokenizer(TextMateTokenizerImpl.create());
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
    implementation("com.github.jjoblab.code-editor:cel-ui:v4.5.0")
    implementation("com.github.jjoblab.code-editor:cel-lsp:v4.5.0") // optionnel
    implementation("com.github.jjoblab.code-editor:cel-textmate:v4.5.0") // optionnel (voie grammaires)
}
```

### Via les modules locaux (inclusion des sources)

```kotlin
// settings.gradle.kts
include(":cel-core", ":cel-lsp-api", ":cel-lsp", ":cel-ui", ":cel-textmate")
project(":cel-core").projectDir = file("code-editor/editor/cel-core")
project(":cel-lsp-api").projectDir = file("code-editor/editor/cel-lsp-api")
project(":cel-lsp").projectDir = file("code-editor/editor/cel-lsp")
project(":cel-ui").projectDir = file("code-editor/editor/cel-ui")
project(":cel-textmate").projectDir = file("code-editor/editor/cel-textmate")

// app/build.gradle.kts
dependencies {
    implementation(project(":cel-ui"))
}
```

### Essayer avant d'intégrer

L'application de démonstration installe un terrain de jeu complet
(30 échantillons, bascules, menu) :

```bash
./gradlew :demo:assembleDebug
adb install demo/build/outputs/apk/debug/demo-debug.apk
```

## Démarrage rapide

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

### Enregistrer son propre langage (une entrée de table, zéro Java)

```java
LanguageRegistry.register(LanguageProfile.builder("mylang")
        .family(SyntaxFamily.C_LIKE)
        .alias("ml").extension("ml")
        .keywords("if", "else", "repeat", "until")
        .commentSyntax(new CommentSyntax("#", null, null))
        .newlineTrigger(NewlineTrigger.onSuffix(":")) // +1 après « clé: »
        .build());
session.setLanguage("mylang");
```

### LSP (Language Server Protocol)

```java
LspProject project = new LspProject("/path/to/workspace");
project.addServerDefinition(myServerDefinition); // ex. un serveur Java, un serveur Lua…
LspEditor editor = project.createEditor("file:///path/to/Foo.java");
editor.setHost(new jo.codeeditor.view.LspEditorViewHost(editorView));
editor.connect();
// …
project.shutdown(); // borné à 2 s par serveur
```

### Connecter l'application hôte — aperçu « goutte d'eau » (B27)

La bibliothèque ne sait PAS prévisualiser : c'est l'application hôte qui
fournit le rendu via l'interface `EditorPreviewHost`. Trois familles
d'aperçu sont câblées : la **feuille flottante** (Vue Android, typiquement
un WebView — Markdown/HTML), le **panneau scindé** inline (canvas), et la
**carte « goutte d'eau »** — un popup non modal ancré à l'œil de la
gouttière, pensé pour le mobile (il esquive le bloc de frappe, se
compacte si l'écran est petit, et suit l'édition en direct).

```java
EditorView editorView = new EditorView(context);
editorView.setSession(session);

// 1) Poser le hôte (rendu + décision d'aperçu).
editorView.setPreviewHost(new MyPreviewHost());

// 2) Dire quel fichier est ouvert — le hôte décide s'il est
//    prévisualisable (l'œil apparaît alors dans la gouttière).
editorView.setFileName("activity_main.xml");

// 3) Rien d'autre : le tap sur l'œil de gouttière ouvre/ferme la carte.
//    (L'API programme : openPreviewDropAt(ligne) / togglePreviewDrop().)
```

Le hôte minimal pour la carte « goutte d'eau » :

```java
public class MyPreviewHost implements EditorPreviewHost {
    @Override public boolean canPreview(String fileName) {
        return fileName != null && fileName.endsWith(".xml");
    }
    // Les hôtes CANVAS préfèrent la carte ; les hôtes VUE (WebView)
    // laissent ce défaut à faux et gardent la feuille flottante.
    @Override public boolean prefersDropPreview() { return true; }
    @Override public List<String> previewDropTabs() {
        return List.of("XML", "Compose");          // pilules d'en-tête
    }
    @Override public void drawDropPreview(Canvas c, float x, float y,
            float w, float h, int tab) {
        // Dessiner l'écran du mini-téléphone (déjà translaté + clippé).
    }
    @Override public String previewDropBadge(int tab) {
        return "activity_main.xml · gonflé";       // badge d'exécution
    }
    @Override public void onPreviewDropTabSelected(int index) { /* re-rendu */ }
}
```

**Correspondance maquette → implémentation.** La carte a été conçue sur
une maquette HTML interactive (anti-recouvrement prouvé par QA
automatisée sur 18 positions de caret), archivée dans
[`docs/maquettes/preview-drop-gouttiere.html`](docs/maquettes/preview-drop-gouttiere.html) :

| Élément de maquette | Implémentation |
|---|---|
| Œil en gouttière (rangée du caret) | `GutterView` pill B9c ; **gelé** sur la rangée d'ancre quand la carte vit |
| Tap œil → ouverture / re-tap → fermeture | `EditorTapResolver` → `EditorPreviewController.togglePreviewDrop()` |
| Popup « goutte d'eau » (coin asymétrique + goutte pivotée) | `EditorPreviewDropPainter` (Path à coins 18/18/18/4 + losange pivoté 45°) |
| Placement anti-recouvrement 3 niveaux (ancré → esquive entière → compactage 0,58) | `EditorPreviewDropGeometry.place()` — testé par balayage de 18 positions |
| Tige de liaison œil↔goutte | `Placement.stemVisible` (au-delà de 8 dp d'écart) |
| Onglets XML / Compose + ✕ | `EditorPreviewHost.previewDropTabs()` + hit-test partagé dessin/tap |
| Badge « activity_main.xml · gonflé » | `EditorPreviewHost.previewDropBadge(tab)` |
| Chip « rendu vivant » + « fit · NN % » | poussée de contenu débouncée 200 ms + échelle de compactage réelle |
| Frappe : la carte esquive, jamais sur la ligne du caret | zone de garde caret ± 1 ligne, re-résolue à chaque frame |

La maquette complète (contrat des gestes, invariants, algorithme) et le
guide pas-à-pas figurent dans [`USAGE.md`](USAGE.md) (section
« Aperçu — feuille, panneau, carte goutte d'eau »).

Le guide complet (thèmes, diagnostics, résolveurs, raccourcis, chords,
aperçu XML, grammaires opt-in) figure dans [`USAGE.md`](USAGE.md).

## Architecture

L'éditeur repose sur une architecture en couches séparées, où chaque
composant est **pur** (sans dépendance UI) et **testable unitairement** :

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

Le détail (diagramme des dépendances entre modules, cycle d'édition,
interface `EditorPreviewHost`) figure dans
[`ARCHITECTURE.md`](ARCHITECTURE.md).

## Tests

```bash
./gradlew test
# 2 860 tests, 0 failure :
#   cel-core 1 470 · cel-lsp-api 32 · cel-lsp 226 · cel-ui 950 · cel-textmate 96 · demo 86
# (contrôle d'écart : scripts/check-doc-figures.sh, exécuté en CI)
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

## Limites connues

Écarts assumés et documentés (l'inventaire exhaustif vit dans
[`FEATURES.md`](FEATURES.md) § Perspectives) :

- **Édition multi-curseur : non implémentée** — une seule sélection par
  session (plan de conception conservé comme piste).
- **Droite-à-gauche : non supporté** — décision explicite de l'ADR-001
  (n°7), avant-projet dans `docs/plans/05-accessibilite-i18n-rtl.md`.
- **Grands fichiers** — coloration, plis et inlays coupés au-delà de
  2,5 M chars / 50 k lignes (l'édition reste fonctionnelle).
- **Voie grammaires : portes de 800 lignes et de 5 000 caractères** —
  au-delà (en nombre de lignes du document, ou en longueur d'UNE
  ligne : fichiers minifiés), repli sur le tokéniseur intégré (coût
  de restyle mesuré supérieur — ADR-002).
- **Chantier multi-langages (ADR-002) : C1/C2/C3/C4 livrés** en
  3.72.0 — registre de langages, tokéniseur générique piloté par la
  table, voie grammaires avec bascule en démo, calibrage des portées
  (voir [`CHANGELOG.md`](CHANGELOG.md)).

## Build

```bash
# Prérequis : JDK 17+, Android SDK (platform 34)
./gradlew assembleDebug          # AARs debug des 5 modules + APK démo
./gradlew assembleRelease        # AARs release
./gradlew test                   # tests (AGP 9 : la variante debug porte les tests unitaires)
./gradlew lint                   # lint des 5 modules
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
`assembleRelease` + `publishToMavenLocal` (répétition générale de la
publication) et uploade les AAR/POM en artefacts. La publication sur GitHub
Packages est automatique : pousser un tag de version strict `vX.Y.Z`
déclenche `.github/workflows/publish.yml` (garde-fou tag = version du build,
tests avant publication — voir [`BUILD.md`](BUILD.md)).

## Licence

Apache License 2.0 — texte complet dans [`LICENSE`](LICENSE),
attributions embarquées dans [`NOTICE`](NOTICE).
