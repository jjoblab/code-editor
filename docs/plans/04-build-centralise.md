# Plan 04 — Build centralisé (convention plugin + catalogue de versions)

> Phase 2 — plan d'implémentation, sans implémentation lourde.

## 1. Constat

- Bloc `android {}` dupiqué ×4 (`cel-core`, `cel-lsp-api`, `cel-lsp`,
  `cel-ui`) + démo : `compileSdk 34`, `minSdk 24`, namespace, lint
  (abortOnError/baselines), Java 17 — toute correction se rejoue ×4.
- Publication Maven dupiquée ×4 (bloques `publishing` quasi
  identiques, ne diffèrent que par l'artefact).
- `versionName = "3.49.2"` codé en dur dans `demo/build.gradle.kts`
  (en plus de `version` racine) — deux sources de vérité.
- Pas de catalogue de versions : AGP 9.0.0 et les dépendances
  (LSP4J 0.22.0, Gson 2.10.1, JUnit, Robolectric) sont éparpillées.

## 2. Cible

```
build-logic/            ← plugin de convention « cel-library »
  src/main/kotlin/jo.codeeditor.cel-library.gradle.kts
gradle/libs.versions.toml
```

- `build-logic/convention` : plugin précompiled (`gradle plugin
  management includeBuild`) appliquant android-library + namespace +
  compileSdk/minSdk + lint bloquant + baseline + Java 17 + publication
  Maven (paramétrée par le nom d'artefact).
- `libs.versions.toml` : `agp`, `lsp4j`, `gson`, `junit5`, `junit4`,
  `robolectric`, `androidx-core`… les modules référencent
  `libs.lsp4j` etc.
- La démo lit la version racine :
  `versionName = rootProject.version.toString()` (suppression du doublon).

## 3. Étapes

1. Créer `gradle/libs.versions.toml` + migrer les 5 `build.gradle.kts`
   vers les alias — build strictement identique (aucune version
   changée), vérifié par `assembleDebug` + comparaison des
   dépendances résolues (`dependencies` avant/après).
2. Créer `build-logic` avec le plugin `cel-library` : y déplacer le
   bloc android + lint + publishing de `cel-core` uniquement ;
   `cel-core/build.gradle.kts` se réduit à ~15 lignes. Vérifier :
   `publishToMavenLocal` produit le même POM (diff `~/.m2`).
3. Migrer `cel-lsp-api`, `cel-lsp`, `cel-ui` au plugin. La démo
   utilise un second plugin `cel-app` (application + versionName de
   la racine).
4. `scripts/check-class-budgets.sh` et la CI ne changent pas — le
   `validate-wrappers` de setup-gradle couvre le nouveau `build-logic`
   (includeBuild) automatiquement.

## 4. Risques

- Le plugin précompilé ajoute une étape de compilation au build
  (convention plugin : ~1-2 s, précompiled script plugin suffit si
  pas de logique lourde).
- La publication Maven via convention plugin doit produire des POM
  strictement identiques (groupId/artifactId/dépendances) — sinon
  rupture silencieuse pour les consommateurs : comparer les POM
  publiés en local avant/après (étape 2).
- `jitpack.yml` : vérifier que l'includeBuild fonctionne chez
  l'hébergeur de paquets (build de répétition générale local).

## 5. Tests de non-régression

`./gradlew clean assembleDebug testDebugUnitTest lint` vert ×5
modules ; `publishToMavenLocal` + diff POM ; la CI reste verte
(les chemins de rapports lint ne changent pas si le plugin conserve
les mêmes noms de variantes).
