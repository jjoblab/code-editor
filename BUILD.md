# Compiler le projet

Projet Android multi-modules Gradle standard. Ce document décrit la
toolchain, les commandes de build et la publication Maven.

## Prérequis

- **JDK 17 minimum** (JDK 21 validé) — requis par AGP 9.
- **Android SDK** avec :
  - `cmdline-tools;latest`
  - `platform-tools`
  - `platforms;android-34`
  - build-tools (téléchargés automatiquement par AGP si besoin)
- **Gradle 9.5.1** — le wrapper est inclus (`./gradlew`), aucune installation
  explicite n'est nécessaire.

## Mise en place initiale

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
```

Créez `local.properties` à la racine du projet (ignoré par Git) :

```
sdk.dir=/path/to/android-sdk
```

(AGP lit ce fichier pour localiser `android.jar`.)

## Commandes de build

```bash
# AARs debug des 4 modules (editor/<module>/build/outputs/aar/)
./gradlew assembleDebug

# AARs release
./gradlew assembleRelease

# Tests unitaires (JVM hôte) — AGP 9 : la variante debug porte les tests
./gradlew test

# Lint des 4 modules
./gradlew lint

# Le tout
./gradlew assembleDebug test lint
```

## Structure du projet

```
code-editor/
├── settings.gradle.kts        — inclut les 4 modules editor/
├── build.gradle.kts           — AGP 9.0.0, group/version Maven (jo.codeeditor:3.37.0)
├── gradle/wrapper/            — Gradle 9.5.1
├── gradle.properties          — jvmargs, parallélisme, cache
├── jitpack.yml                — JDK 17 pour les builds JitPack
│
├── editor/
│   ├── cel-core/              — moteur pur Java (com.android.library)
│   ├── cel-lsp-api/           — SPI langage (com.android.library)
│   ├── cel-lsp/               — client LSP4J (com.android.library)
│   └── cel-ui/                — vues Android (com.android.library)
│
└── .github/workflows/ci.yml   — CI GitHub Actions
```

Chaque module `editor/<module>/` suit la disposition standard
`src/main/java/jo/codeeditor/…` + `src/test/java/jo/codeeditor/…`.

## Toolchain

| Outil | Version |
|-------|---------|
| JDK | 17 minimum (21 validé) |
| Gradle | 9.5.1 (wrapper inclus) |
| AGP | 9.0.0 |
| Android | `compileSdk 34`, `minSdk 24` |
| Java source/cible | 17 |
| Encodage | UTF-8 |
| Tests | JUnit 5 (Jupiter) ; `:cel-ui` en JUnit 4 (Robolectric) ; `:cel-lsp` sur la plateforme JUnit 5 avec moteur vintage (tests Jupiter + JUnit 4/Robolectric) |

## Artefacts

Après `./gradlew assembleDebug` :

- `editor/cel-core/build/outputs/aar/cel-core-debug.aar`
- `editor/cel-lsp-api/build/outputs/aar/cel-lsp-api-debug.aar`
- `editor/cel-lsp/build/outputs/aar/cel-lsp-debug.aar`
- `editor/cel-ui/build/outputs/aar/cel-ui-debug.aar`

## Publication Maven

### Coordonnées

Chaque module embarque `maven-publish` et publie la variante release avec
un jar de sources :

| Module | Coordonnées |
|--------|-------------|
| `:cel-core` | `jo.codeeditor:cel-core:3.37.0` |
| `:cel-lsp-api` | `jo.codeeditor:cel-lsp-api:3.37.0` |
| `:cel-lsp` | `jo.codeeditor:cel-lsp:3.37.0` |
| `:cel-ui` | `jo.codeeditor:cel-ui:3.37.0` |

### Publication locale (validation)

```bash
./gradlew publishToMavenLocal
# → ~/.m2/repository/jo/codeeditor/<module>/3.37.0/ (AAR + sources + POM)
```

### Publication sur GitHub Packages (méthode principale)

Deux chemins, au choix :

**A. Workflow GitHub Actions** — `.github/workflows/publish.yml`, à
déclencher manuellement (onglet Actions > « Publication GitHub Packages » >
Run workflow). Il publie les 4 modules avec le `GITHUB_TOKEN` du runner
(injecté dans les propriétés `gpr.user`/`gpr.key`) ; aucune configuration
locale n'est nécessaire.

**B. En local**, avec un jeton d'accès personnel :

1. Créez un jeton d'accès personnel GitHub avec le droit `write:packages`.
2. Renseignez vos identifiants dans `~/.gradle/gradle.properties` :

```properties
gpr.user=<votre-utilisateur-github>
gpr.key=<votre-jeton-d-acces>
```

3. Publiez :

```bash
./gradlew publish
```

Le dépôt `GitHubPackages` est déjà configuré dans le bloc `publishing` des
4 modules — il s'active uniquement quand les propriétés `gpr.user`/`gpr.key`
(ou les variables d'environnement `GPR_USERNAME`/`GPR_TOKEN`) sont
renseignées ; `publishToMavenLocal` reste utilisable sans identifiants.

> **Note** : GitHub Packages refuse le remplacement d'une version déjà
> publiée — incrémentez `version` dans `build.gradle.kts` (racine) avant
> chaque publication.

Les consommateurs ajoutent alors le dépôt `maven.pkg.github.com/jjoblab/code-editor`
avec un jeton `read:packages` — voir la section Installation du
[`README.md`](README.md).

### JitPack (alternative de secours)

JitPack construit la bibliothèque à la demande depuis un tag Git
(`com.github.jjoblab:<module>:<tag>`) ; le fichier `jitpack.yml` épingle le
JDK 17 côté JitPack. Poussez simplement un tag `v*` :

```bash
git tag v3.37.0 && git push origin v3.37.0
```

La CI valide sur ce tag `assembleRelease` + `publishToMavenLocal` avant que
JitPack ne construise.
