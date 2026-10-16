# Contribuer à code-editor

Merci de votre intérêt ! Ce guide couvre la mise en place de
l'environnement de développement, les conventions de code et le processus
de pull request.

## Mise en place de l'environnement

### Prérequis

- **JDK 17 minimum** (JDK 21 validé) — requis par AGP 9.
- **Android SDK** avec `platform-tools`, `platforms;android-34` et les
  build-tools (téléchargés automatiquement par AGP).
- **Gradle 9.5.1** — le wrapper `./gradlew` le télécharge automatiquement au
  premier lancement.

### Installation de la toolchain (Linux x86_64)

```bash
mkdir -p $TOOLS_DIR
cd $TOOLS_DIR

# JDK 17 Temurin
curl -sL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" -o temurin17.tar.gz
tar -xzf temurin17.tar.gz

# Android cmdline-tools
wget -q "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -O cmdline-tools.zip
mkdir -p android-sdk/cmdline-tools
cd android-sdk/cmdline-tools
unzip -q $TOOLS_DIR/cmdline-tools.zip
mv cmdline-tools latest

export JAVA_HOME=$TOOLS_DIR/jdk-17.0.13+11
export ANDROID_HOME=$TOOLS_DIR/android-sdk
export ANDROID_SDK_ROOT=$TOOLS_DIR/android-sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-34"

# Gradle 9.5.1 (optionnel — le wrapper le télécharge si vous sautez cette étape)
wget -q "https://services.gradle.org/distributions/gradle-9.5.1-bin.zip" -O gradle-9.5.1.zip
unzip -q gradle-9.5.1.zip
```

### Créer `local.properties`

À la racine du projet (fichier ignoré par Git) :

```
sdk.dir=/path/to/your/android-sdk
```

### Compiler et tester

```bash
# Suite de tests complète (2 516 tests, contrôle d'écart en CI).
./gradlew test

# AARs des 5 modules.
./gradlew assembleDebug

# Le contrôle canonique « tout passe » :
./gradlew clean assembleDebug test lint
```

## Conventions de code

- **Java 17** source/cible (`sourceCompatibility = VERSION_17`), encodage
  **UTF-8**.
- **Indentation 4 espaces**, pas de tabulation.
- **Commentaires et javadoc en français** — c'est la langue du dépôt.
- **Imports** : `java.*` d'abord, puis `javax.*`, puis `jo.codeeditor.*`,
  puis `android.*`. Pas d'import wildcard.
- **Pas de `new Paint()` dans le chemin de dessin** — réutilisez des champs
  `Paint` pour éviter la pression GC pendant `onDraw` (voir les painters
  de `EditorRenderer`).
- **Tous les offsets clampés** à `[0, doc.length()]` dans le chemin de
  rendu — un offset périmé d'une frame ne doit jamais crasher la vue.
  Utilisez le helper `clamp(int, lo, hi)`.
- **Tests** : JUnit 5 (Jupiter) sur la JVM hôte, sans `androidTest`
  instrumenté (Robolectric couvre les besoins Android). Exceptions :
  `:cel-ui` tourne en JUnit 4 (Robolectric ne supporte pas encore JUnit 5)
  et `:cel-lsp` combine les deux générations sur la plateforme JUnit 5
  (moteur vintage pour ses tests Robolectric). Si un comportement n'est
  pas testable sans Android, documentez-le dans la PR.
- **Pas d'emoji dans le code source** (sauf chaînes affichées à
  l'utilisateur si déjà présentes).
- **Aucune référence à un projet externe** (voir la section dédiée de
  [`AGENT.md`](AGENT.md)) : ni nom, ni comparaison, ni URL de dépôt tiers.
  Le garde-fou `scripts/check-external-refs.sh` tourne en CI ; pour
  l'activer localement sur chaque commit :

  ```bash
  git config core.hooksPath .githooks
  ```

  Les motifs nominatifs interdits sont fournis par la variable
  d'environnement `FORBIDDEN_REFS_EXTRA` (en CI : secret du même nom ;
  absente, le contrôle nominatif est ignoré sans erreur).

## Ajouter une fonctionnalité

1. **Écrivez le test d'abord** (ou en même temps). Tout nouveau
   comportement doit avoir un test dans
   `editor/<module>/src/test/java/jo/codeeditor/…`. Pour un correctif de
   bug, ajoutez un test de régression dédié.
2. **Respectez l'architecture en couches** :
   - Logique moteur pure → `:cel-core` (`session/`, `document/`, `rope/`,
     `edit/`, `find/`, `shift/`, `highlight/`, `fold/`, `wrap/`,
     `snippet/`, `cache/`, `completion/`, `doc/`, `navigation/`,
     `languages/`).
   - Contrats de langage → `:cel-lsp-api` (`lang/`, `lang/model/`,
     `lang/provider/`).
   - Intégration LSP → `:cel-lsp` (`lsp/`, `lsp/connection/`).
   - Rendu Android → `:cel-ui` (`view/`, `view/chrome/`, `blocks/`).
   - Le moteur ne doit PAS dépendre de `android.*` — seule la couche View
     le peut.
3. **Une classe = une responsabilité.** Les classes pivots
   (`EditorView`, `EditorSession`, `EditorRenderer`…) sont des
   orchestrateurs : la nouvelle logique va dans un collaborateur dédié du
   package adapté, pas dans l'orchestrateur.
4. **Ajoutez une javadoc en français** sur toute nouvelle classe ou méthode
   publique.
5. **Mettez à jour `CHANGELOG.md`** sous la section `[Unreleased]`.
6. **Lancez la suite complète** — `./gradlew clean assembleDebug test lint`
   doit passer sans échec.

## Processus de pull request

1. Forkez le dépôt et créez une branche :
   `git checkout -b feature/ma-fonctionnalite`.
2. Commitez avec un message clair :
   `feat(completion): filtrage flou` ou
   `fix(ime): nettoie la région composing à l'acceptation`.
3. Poussez et ouvrez la PR. Dans la description :
   - Lien vers l'éventuel issue adressé.
   - Liste des tests ajoutés.
   - Breaking changes éventuels signalés (aucun ne doit passer sans bump
     de version majeure).
4. La CI exécute `./gradlew assembleDebug testDebugUnitTest lint` — tous
   les tests doivent passer.
5. Un mainteneur relit la PR sous quelques jours.

## Politique de dépréciation et compatibilité

Les ruptures d'API publique coûtent cher aux hôtes — elles suivent un
palier obligatoire (A-07, audit 3.69.0) :

1. **`@Deprecated` d'abord** : la javadoc de l'annotation dit POURQUOI
   (le défaut, le risque) et PAR QUOI remplacer ; le CHANGELOG porte
   l'entrée *Deprecated* dès la version qui déprécie.
2. **La version dépréciée reste fonctionnelle** pendant au moins un
   palier mineur (pas de comportement supprimé : le corps délègue au
   remplaçant quand c'est possible).
3. **Le retrait** se fait au palier suivant (majeur, ou mineur si la
   rupture avait été annoncée) : entrée *Removed* du CHANGELOG et
   message d'erreur/d'avertissement explicite.
4. **Nullité du contrat** : les champs/paramètres publics portent des
   annotations `@NonNull`/`@Nullable` (androidx.annotation, rétention
   CLASS — invisibles au runtime, nul impact consommateur). Null
   signifie « absent », jamais « erreur » : les constructeurs
   normalisent (`null` → valeur neutre documentée) quand le modèle le
   permet.
5. **Compatibilité binaire** : le contrôle en CI viendra en deux
   temps — avertissement d'abord (journal), bloquant ensuite. Tant
   qu'il n'est pas en place, un mainteneur relit les signatures
   publiques de chaque PR touchant l'API (diff des méthodes publiques).

## Signaler un bug

Ouvrez un issue avec :

- **Version de la bibliothèque** (coordonnées Maven `jo.codeeditor:*`).
- **Version Android + appareil** (ou « JVM hôte » pour les bugs Java pur).
- **Étapes de reproduction** — le plus petit extrait de code qui déclenche
  le bug.
- **Comportement attendu vs observé**.
- **Sortie logcat** si le bug est un crash.

## Licence

En contribuant, vous acceptez que vos contributions soient licenciées sous
licence MIT (voir `LICENSE`).
