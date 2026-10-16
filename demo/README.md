# demo — atelier de la bibliothèque

> 51 tests · Robolectric (fumée : lancement réel des activités,
> chaîne LSP complète) + JUnit pur (parseur, serveur) · application
> `com.android.application`, **non publiée sur Maven** — c'est une
> vitrine et un banc d'essai, pas un artefact.

L'application de démonstration exerce en situation réelle **les cinq
modules publiables** : elle branche `EditorView` sur des échantillons
multi-fichiers, un analyseur synthétique (moteur maison), l'aperçu
XML/HTML, le crochet `:cel-textmate` — et depuis 3.73.0, **le module
`:cel-lsp` de bout en bout** via un mini serveur de langage Java
in-process. Tout ce qui s'y voit est censé fonctionner — c'est le
contrat d'une démo.

## Position dans l'ensemble

```
:demo ──► :cel-ui (api → :cel-core, :cel-lsp-api)
      ──► :cel-textmate (crochet grammaires, optionnel)
      ──► :cel-lsp (mini serveur Java, transport in-process)
```

- Elle dépend des **sources locales** (`project(...)`) — jamais des
  artefacts publiés : ce qui est testé est l'état courant du dépôt.
- `DemoAnalyzer` implémente le SPI **sans serveur LSP** : diagnostics,
  complétion, signatures, quick doc et code actions synthétiques —
  la vitrine du « moteur de langage maison » de `USAGE.md`.
- `DemoLspController` + `MiniJavaLanguageServer` (paquet `lsp/`)
  incarnent **l'intégration LSP de référence** : un `LspProject`, une
  `LanguageServerDefinition` avec transport
  `InProcessStreamConnectionProvider`, un serveur lsp4j (diagnostics
  uniquement) et la bascule moteur maison ↔ moteur LSP.
- `DemoPreviewHost` implémente `EditorPreviewHost` (aperçu XML/HTML
  rendu par la démo elle-même, mode SPLIT/FULL).
- `DemoTheme`/schemes : vitrine de la cascade thème → scheme.

## Ce qu'on y trouve

### Écran d'accueil (`HomeActivity`)

Cartes de capacité — chaque carte ouvre l'atelier préconfiguré pour
la fonctionnalité : **Diagnostics LSP Java** (moteur LSP connecté),
Atelier multi-langages, Grammaires TextMate (C#), Aperçu XML/HTML,
Stress bundle minifié, Cascade de thèmes. Les chiffres de tête
(29 langages · 10 grammaires · 5 modules) et l'entrée Réglages.

### Coquille IDE (`EditorActivity`)

- **Tiroir** des 30 sélecteurs de langage, groupés par familles
  (JVM, Web, Scripts, Systèmes, Données) + Réglages/À propos —
  la même liste d'échantillons que l'ancienne démo :
  Java, Kotlin, **C#** (langage « grammaire-only »), Scala, Groovy,
  Python, JavaScript, **Minifié (JS)** (bundle de ~14 Ko sur une
  ligne), TypeScript, PHP, Ruby, Lua, Shell, C, C++, Go, Rust, Swift,
  Dart, XML, HTML (page autonome), JSON, YAML, TOML, Properties, CSS,
  Markdown, SQL, Smali, Log — **29 langages + un échantillon de
  stress**.
- **Onglets multi-fichiers** (jusqu'à 10) : session + analyseur par
  fichier, fermeture par « × », réutilisation à la réouverture.
- **Panneaux outils** repliables : Diagnostics (moteur actif —
  maison ou LSP, clic = saut à la ligne), Symboles (outline du
  document), **Journal LSP** (activité JSON-RPC horodatée).
- **Barre d'état** : Ln/Col du caret, langage, moteur de langage
  (● maison / ● LSP cyan), voie de coloration TextMate, UTF-8.
- Barre de fil d'Ariane (annuler/rétablir/aperçu) et
  recherche/remplacement intégrées, bascules **Moteur LSP** et
  **Grammaires TextMate** au menu, options de vue (numéros, plis,
  guides, collantes, loupe, swatches, couleurs de variables, point
  d'arrêt) au sous-menu.

### Écran Réglages (`SettingsActivity`)

Préférences persistées (`SharedPreferences`) relues par l'atelier à
chaque reprise : apparence (thème clair, scheme), éditeur (retour à
la ligne, minimap, ligatures, non-imprimables), moteurs (grammaires
TextMate automatiques, LSP automatique sur Java). L'ancienne « bande
de bascules » vit ici désormais.

### Mini serveur LSP Java (paquet `lsp/`)

- `MiniJavaParser` — parseur conservateur pur JVM : accolades non
  fermées, fermantes orphelines, points-virgules manquants,
  branchements orphelins (`else`/`catch`/`finally` sans tête
  adjacente). Contrat : **zéro faux positif** — les tests couvrent
  les déclencheurs ET les non-déclencheurs.
- `MiniJavaLanguageServer` — serveur lsp4j (~200 lignes) :
  capabilities `textDocumentSync = Full`, didOpen/didChange/didClose,
  publication `publishDiagnostics`. Pur JVM, testable sans Android.
- `MiniJavaServerDefinition` — le seul point de branchement sur
  `:cel-lsp` : fabrique le transport in-process, exactement comme
  une intégration réelle (sous-processus, socket, LocalSocket).
- `DemoLspController` — possède le `LspProject`, écrit l'espace de
  travail (`filesDir/workspace/Atelier.java`), connecte/déconnecte
  l'éditeur, expose l'état (OFF/CONNECTING/CONNECTED), relaie le
  journal et les diagnostics.

La bascule « Moteur LSP » (menu, onglet Java uniquement) démontre
les DEUX voies de `USAGE.md` sur le même document : moteur maison
(popups synthétiques) contre serveur LSP réel (diagnostics poussés à
chaque édition, soulignés dans l'éditeur et listés au panneau).

## Build et installation

```bash
./gradlew :demo:assembleDebug
# APK : demo/build/outputs/apk/debug/demo-debug.apk
adb install demo/build/outputs/apk/debug/demo-debug.apk
```

Le mini serveur LSP est **embarqué et in-process** : la démo reste
**hors-ligne** — aucun serveur externe, aucune permission.

## Tests

```bash
./gradlew :demo:testDebugUnitTest     # 51 tests
```

- `MiniJavaParserTest` (20) — chaque règle, ses déclencheurs ET ses
  non-déclencheurs (modificateurs seuls, chaînes en continuation,
  annotations, masquage chaînes/commentaires, profondeur extrême).
- `MiniJavaLanguageServerTest` (5) — initialize (sync Full),
  publications didOpen/didChange/didClose, code propre/cassé.
- `DemoLspControllerTest` (2) — la chaîne COMPLÈTE contrôleur →
  projet → transport in-process → serveur → diagnostics en session,
  déconnexion et purge.
- `EditorActivitySmokeTest` (3) — lancement réel, coquille
  multi-fichiers (ouvrir/sélectionner/fermer), extra LSP de la carte
  d'accueil.
- `HomeActivitySmokeTest` (1) — lancement réel, cartes rendues.
- `DemoPreviewHostTest` (20) — inchangé : comportement de l'hôte
  d'aperçu.

## Voir aussi

- [`editor/cel-ui/README.md`](../editor/cel-ui/README.md) — la vue
  branchée ici.
- [`editor/cel-lsp/README.md`](../editor/cel-lsp/README.md) — le
  cadre que le mini serveur traverse.
- [`editor/cel-textmate/README.md`](../editor/cel-textmate/README.md) —
  la bascule « Grammaires TextMate ».
- [`USAGE.md`](../USAGE.md) — ce que la démo illustre, documenté pour
  l'hôte réel.
