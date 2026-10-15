# AGENT.md — guide pour les agents IA et contributeurs

Document court et factuel : uniquement ce qui a été constaté dans le dépôt.
Pour le reste : [`README.md`](README.md) (présentation, installation),
[`BUILD.md`](BUILD.md) (toolchain, publication), [`ARCHITECTURE.md`](ARCHITECTURE.md)
(design), [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Vue d'ensemble

Bibliothèque Android « éditeur de code » écrite en **Java** (aucun Kotlin),
rendu **Canvas** (aucun Compose). Quatre modules (tous
`com.android.library`, donc **tous** exigent le SDK Android — aucun build
n'est possible sans SDK, pas même les tests « purs Java ») :

| Module | Rôle | Dépend de |
|---|---|---|
| `editor/cel-core` | moteur : rope, session, undo, styling, plis, wrap, complétion | — |
| `editor/cel-lsp-api` | SPI langage (providers, modèles de données) | `cel-core` (api) |
| `editor/cel-lsp` | client LSP4J | `cel-core`, `cel-lsp-api` (compileOnly) — programme contre `EditorHost` (`cel-lsp-api`), ne connaît plus la vue |
| `editor/cel-ui` | vues Android (rendu, gestes, popups) | `cel-core`, `cel-lsp-api` |

Toolchain vérifiée : **JDK 17+ avec `javac`** (un JRE headless fait échouer
AGP avec `does not provide the required capabilities: [JAVA_COMPILER]`) ·
Gradle 9.5.1 (wrapper) · AGP 9.0.0 · `compileSdk 34`, `minSdk 24`, Java 17.
Tests : JUnit 5 sur `cel-core` ; JUnit 4 + **Robolectric** sur `cel-ui` ;
`cel-lsp` sur plateforme JUnit 5 avec moteur vintage.

## Carte des fichiers clés

| Zone | Fichiers |
|---|---|
| Sélection et poignées | `cel-ui/…/view/input/EditorSelectionGestures.java` (ancres, toolbar), `EditorInputHandler.java` (dispatch tactile, drag-select armé, loupe ON/OFF), `EditorTapResolver.java` (tap/double/triple) |
| Rendu | `render/EditorRenderer.java` (pipeline complet, ordre des couches), `EditorTextPainter.java` (texte, spans, wrap), `EditorChromePainter.java` (loupe, minimap, chevrons, navmenu), `EditorShapedLayoutCache.java` (layouts ligatures par contenu), `cache/LineRenderCache.java` (rendu par ligne, révisions) |
| Session | `cel-core/…/session/EditorSession.java` (point d'entrée des mutations : `replaceRangeWithCaret`/`doReplaceRange`, undo/redo), `UndoManager.java`/`UndoRecorder.java` (coalescence des frappes) |
| Rope | `cel-core/…/rope/Rope.java` (arbre équilibré par poids) |
| IME | `cel-core/…/session/ImeBridge.java` (composition, replaceText), `cel-ui/…/view/EditorImeBridge.java` (InputConnection, texte extrait) |
| Géométrie | `cel-ui/…/view/EditorWrapGeometry.java` (wrap/plis ↔ Y écran), `view/EditorView.java` (métriques, thème, caches) |
| LSP | `cel-lsp/…/lsp/LanguageServerWrapper.java`, `LspEditor.java` (pont listeners → didChange) |

## Commandes (vérifiées)

```bash
export JAVA_HOME=<jdk-17-avec-javac>
export ANDROID_HOME=<sdk>          # + local.properties : sdk.dir=<sdk>
./gradlew assembleDebug            # 4 AAR debug
./gradlew test                     # tous les tests unitaires
./gradlew :cel-core:test           # un module
./gradlew :cel-ui:testDebugUnitTest --tests "jo.codeeditor.view.EditorMagnifierHandleDragTest"   # un seul test
./gradlew lint                     # 0 erreur / 0 avertissement attendu
./gradlew publishToMavenLocal      # publication locale (~/.m2)
```

Base de référence constatée (3.72.0-dev, multi-langages) : **2 066 tests verts, lint
bloquant vert** — toute régression est la vôtre. Le nombre annoncé est contrôlé en
CI (`scripts/check-doc-figures.sh`, échoue si l'écart dépasse 5 %).

## Conventions

- **Commentaires en français**, dans le code et les messages de commit.
- Commits : `fix(scope): …` / `perf(scope): …` / `feat(scope): …` /
  `chore(scope): …` — **un commit par changement**, sujet + corps explicatif.
- Chaque correctif de bug est livré avec **un test qui échoue avant et
  passe après** (Robolectric pour `cel-ui`, Jupiter pour `cel-core`).
- `CHANGELOG.md` : nouvelle section par version (Added / Changed / Fixed /
  Performance), en français.
- Pas de reformatage massif, pas de renommage gratuit, pas de rupture
  d'API publique sans la signaler.

## Pièges connus (constatés lors de l'audit v3.38.0)

- **`view.textPaint` est partagé** par tout le pipeline de rendu et muté
  en cours de frame (spans sémantiques, inlays, chrome). Toute couche qui
  dessine du texte doit soit poser la couleur de base explicitement, soit
  utiliser un paint dédié — jamais compter sur la couleur résiduelle.
- **La clé d'un cache ne doit jamais dépendre d'un état mutable** d'un
  objet partagé (le cache de layouts signait avec `paint.getColor()` :
  voir le bug de la loupe corrigé dans `EditorShapedLayoutCache`).
- **L'ancre d'un drag doit être figée au DOWN** (poignées comme
  drag-select) — la relire à chaque MOVE la fait sauter quand le doigt
  franchit l'autre borne.
- **`firstVisible`/`lastVisible` du renderer sont des RANGÉES VISUELLES**,
  pas des lignes document (piège documenté dans `EditorRenderer.draw()` ;
  utiliser `docLineForScreenY` pour les plages sensibles aux plis).
- `EditorMetrics.setTextSize()` recalcule `charWidth`/`lineHeight` : les
  tests Robolectric qui injectent des métriques par réflexion doivent
  ré-injecter après tout changement de taille.
- Les tests Robolectric : `offsetAt` **arrondit** — viser le quart gauche
  d'un caractère, pas son centre.
- Undo/redo doit passer par `doReplaceRange(…, recordUndo=false)` (le
  pipeline commun décale annotations/plis/composition) — jamais muter
  `doc` directement.

## Interdiction de mentionner des projets externes

**Règle absolue.** Aucun projet, bibliothèque, éditeur, IDE ou dépôt qui
n'appartient pas au propriétaire de ce dépôt ne doit être nommé, cité,
décrit, comparé ou référencé, **où que ce soit**.

**Portée (aucune exception de lieu).** Code source, commentaires, Javadoc,
noms de classes, méthodes, variables, constantes, fichiers et tests,
chaînes et ressources, jeux de test, `CHANGELOG`, `README`, `NOTICE`
(sauf exception 1), `docs/`, plans, rapports, scripts, workflows CI,
messages de commit, descriptions de PR, noms de branches et de tags,
notes de release, issues.

**Formulations interdites** (liste non exhaustive) : « inspiré de X »,
« comme X », « mieux que X », « équivalent de X », « port de X »,
« comportement de X », lien ou URL vers le dépôt d'un autre projet, hash
de commit d'un autre dépôt, résumé de lecture du code d'autrui, benchmark
ou comparaison nommant un autre projet.

**À la place**, décris le comportement ou l'algorithme en termes
génériques et autosuffisants (« le guide d'indentation suit l'imbrication
des blocs », « le curseur reste visible pendant le défilement »).

**Exceptions, strictement limitées.**
1. Les **dépendances réellement déclarées** dans le build et l'outillage
   CI réellement utilisé : nom, version et licence, uniquement dans les
   fichiers de build et dans `NOTICE` (obligation de licence). Leur usage
   technique (imports, appels d'API) va de soi ; les citer dans un
   commentaire ou un document comme source d'inspiration ou terme de
   comparaison reste interdit.
2. Les **standards et plateformes** nécessaires à la description
   technique (Android, Java, Gradle, Language Server Protocol, JSON-RPC,
   Unicode…), cités comme normes et jamais comme implémentation de
   référence ou concurrente.

**Provenance du code.** Ne jamais copier, traduire ni adapter le code
d'un autre projet. Implémente à partir du comportement attendu, des
spécifications publiques et de tests écrits ici. En cas de doute sur
l'origine d'un passage : arrête-toi et demande au propriétaire ; ne
« règle » pas le doute en supprimant une mention.

**Contrôle.** `scripts/check-external-refs.sh` (heuristiques génériques
versionnées + motifs nominatifs lus dans la variable d'environnement
`FORBIDDEN_REFS_EXTRA`, jamais committés) s'exécute en CI, en hook
`commit-msg` et avant toute livraison. Aucun nom interdit n'est écrit
dans le dépôt, pas même dans cette section.

**Agents IA.** Avant chaque commit, exécute le contrôle. Dans tes
comptes rendus, désigne tout projet externe par un identifiant neutre
(`[EXT-1]`…), jamais par son nom.

- Ne pas modifier `LICENSE`.

## Définition de « terminé »

`./gradlew clean assembleDebug test lint --continue` vert · pas de nouveau
avertissement lint ni de test en échec · `CHANGELOG.md` à jour ·
`scripts/check-external-refs.sh` vert · `git status` propre. L'agent
**pousse systématiquement** le résultat validé (branche intégrée, `main`,
tag de version) — décision du propriétaire (2026-10) qui remplace
l'ancienne règle « ne pousse jamais ». Une seule branche est maintenue :
`main` (les branches de travail sont fusionnées puis supprimées).

## À ne pas faire

- Toucher à `local.properties`, aux secrets, aux keystores.
- Committer `build/`, `.gradle/`, `.idea/`, `*.iml`, `*.hprof`.
- Laisser des branches autres que `main` après livraison (fusionner,
  pousser, supprimer la branche de travail).
