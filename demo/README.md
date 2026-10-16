# demo — terrain de jeu de la bibliothèque

> 31 tests · Robolectric (fumée : lancement réel de `MainActivity`) ·
> application `com.android.application`, **non publiée sur Maven** —
> c'est une vitrine et un banc d'essai, pas un artefact.

L'application de démonstration exerce les quatre modules publiables en
situation réelle : elle branche `EditorView` sur un échantillon, un
analyseur synthétique et l'aperçu XML, et croche le module optionnel
`:cel-textmate` au démarrage. Tout ce qui s'y voit est censé
fonctionner — c'est le contrat d'une démo.

## Position dans l'ensemble

```
:demo ──► :cel-ui (api → :cel-core, :cel-lsp-api)
      ──► :cel-textmate (crochet grammaires, optionnel)
```

- Elle dépend des **sources locales** (`project(...)`) — jamais des
  artefacts publiés : ce qui est testé est l'état courant du dépôt.
- `DemoAnalyzer` implémente le SPI **sans serveur LSP** : diagnostics,
  complétion, signatures, quick doc et code actions synthétiques —
  la vitrine du « moteur de langage maison » de `USAGE.md`.
- `DemoPreviewHost` implémente `EditorPreviewHost` (aperçu XML/HTML
  rendu par la démo elle-même, mode SPLIT/FULL).
- `DemoTheme`/schemes : vitrine de la cascade thème → scheme.

## Ce qu'on y trouve

### Échantillons (`DemoSamples`)

30 sélecteurs de langage : Java, Kotlin, **C#** (langage
« grammaire-only » : coloré par `:cel-textmate`, repli C_LIKE sans le
module), Scala, Groovy, Python, JavaScript, **Minifié (JS)** (bundle
de ~14 Ko sur une seule ligne — rendu fenêtré et stress de frappe),
TypeScript, PHP, Ruby, Lua, Shell, C, C++, Go, Rust, Swift, Dart, XML,
HTML (page autonome — vitrine complète de l'aperçu et des blocs
`<style>`), JSON, YAML, TOML, Properties, CSS, Markdown, SQL, Smali,
Log — soit **29 langages + un échantillon de stress**.

### Bande de bascules

Retour à la ligne · Minimap · Ligatures · Non-imprimables · Thème
clair · Scheme (surcharge de palette) · **Grammaires TextMate** — le
langage courant bascule entre voie intégrée et voie grammaires quand
une grammaire existe (java, python, kotlin, css, html, javascript,
lua, markdown, xml, C#), repli silencieux + toast sinon. Sur
l'échantillon HTML, la bascule co-active la grammaire CSS : les blocs
`<style>` passent à la vraie grammaire.

### Menu

Langage (sélecteur) · Guides d'indentation (off/indentation/portée
active) · Lignes sticky (on/off + profondeur 1–5) · Loupe (zoom
15/20/25/30, native on/off) · Carrés de couleur · Couleurs par
variable · Numéros de ligne (absolus/relatifs/hybrides) · Plis
(niveaux 1–3, tous, aucun) · Point d'arrêt (gouttière, ligne du
caret) · Rechercher dans le fichier · Réinitialiser l'échantillon ·
À propos.

## Build et installation

```bash
./gradlew :demo:assembleDebug
# APK : demo/build/outputs/apk/debug/demo-debug.apk
adb install demo/build/outputs/apk/debug/demo-debug.apk
```

Aucun serveur LSP n'est embarqué : la démo est **hors-ligne** (l'intégration
LSP vit dans `:cel-lsp` et ses tests, avec serveurs faux in-process).

## Tests

```bash
./gradlew :demo:testDebugUnitTest     # 31 tests Robolectric
```

Fumée : lancement réel de `MainActivity`, bascules d'état, survie au
changement de langage, comportement de l'hôte d'aperçu.

## Voir aussi

- [`editor/cel-ui/README.md`](../editor/cel-ui/README.md) — la vue
  branchée ici.
- [`editor/cel-textmate/README.md`](../editor/cel-textmate/README.md) —
  la bascule « Grammaires TextMate ».
- [`USAGE.md`](../USAGE.md) — ce que la démo illustre, documenté pour
  l'hôte réel.
