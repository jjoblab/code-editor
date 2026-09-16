# Rapport d'analyse — code-editor v3.33.5 → v3.34.0

**Date :** 16 septembre 2026
**Périmètre :** analyse approfondie du projet `code-editor` (4 modules, ~29 700 lignes Java), comparaison avec l'évolution du module editor de CodeAssist entre **v3.9.x et v3.20.0**, application des correctifs et améliorations, validation complète (build + tests + lint + publication Maven).
**Version livrée :** `3.34.0` (bump minor — nouvelles fonctionnalités + correctifs, aucun breaking change d'API publique).

---

## 1. Résumé exécutif

Le projet était dans un état **non publiable en l'état** malgré un socle architectural solide :

1. **Le build échouait dès la configuration** — `settings.gradle.kts` référençait un module `:cel-tm4e` inexistant et omettait le module `cel-lsp` présent sur disque ; le wrapper Gradle 8.5 était incompatible avec AGP 8.5.2 (minimum 8.7).
2. **13 erreurs lint `NewApi`** garantissaient des crashes `NoSuchMethodError` sur Android 7.x (API 24-25) : le module LSP utilisait `java.nio.file.Files.readAllBytes`, `File.toPath` (API 26+) et `Path.of` (API 34+) avec `minSdk 24`.
3. **Cinq problèmes de lifecycle/concurrence** : thread de restyle jamais libéré (fuite par session), résultat de restyle async droppé sans re-scheduling (coloration mixte permanente), `shutdown()` LSP bloquant sans timeout (ANR), race sur la création de serveurs LSP, callbacks Handler survivant au détachement de la vue.
4. **Deux hotspots de performance majeurs** dans le chemin de rendu : copies défensives de listes complètes **par ligne manquée au cache** à chaque scroll (O(lignes × tokens)), et ré-allocation de HashMaps à chaque saut de ligne — exactement les deux problèmes que CodeAssist a corrigés en v3.20 avec `LineOverlay<T>` (mesuré chez eux : 0,309 ms → 0,031 ms par frappe Entrée, allocations ÷100).

Après correction : **781 tests verts** (758 existants + 23 nouveaux), **0 erreur lint**, build debug **et** release OK sur les 4 modules, **publication Maven validée** (`publishToMavenLocal` produit AAR + sources + POM pour `jo.codeeditor:cel-*:3.34.0`).

---

## 2. État initial constaté (preuves)

### 2.1 Build

```
$ ./gradlew help
* What went wrong:
A problem occurred configuring project ':cel-core'.
> Failed to apply plugin 'com.android.internal.version-check'.
   > Minimum supported Gradle version is 8.7. Current version is 8.5.
```

Avant même cette erreur, la configuration échouait sur `settings.gradle.kts` :

```kotlin
include(":cel-tm4e")                      // ← répertoire inexistant
project(":cel-tm4e").projectDir = file("editor/cel-tm4e")
// editor/cel-lsp/ existe mais n'est PAS inclus → module mort (18 fichiers, 4 138 lignes)
```

### 2.2 Lint (13 erreurs)

| Module | Erreurs | Détail |
|--------|---------|--------|
| `cel-lsp` | 12 | `Files.readAllBytes` / `File.toPath` / `Files.write` (API 26), `Path.of` (API 34) — sites : `LspEditor.java:1003-1024`, `LspLanguage.java:912-913, 1100-1127, 1436-1437` |
| `cel-ui` | 1 | `new SurroundingText(...)` (API 31) dans `EditorImeBridge.getSurroundingTextCompat` |

Avec `minSdk = 24`, chaque appel de ces chemins sur Android 7.x levait `NoSuchMethodError` : les fonctionnalités **rename LSP multi-fichiers** et **apply-edits-to-disk** crashaient au lieu de dégrader proprement.

### 2.3 Tests (avant correctifs)

758 tests, 0 échec — la suite existante est saine ; le problème n'était pas la logique métier mais le build, la compatibilité API et le lifecycle.

---

## 3. Évolution de CodeAssist v3.9.9 → v3.20.0 (module editor)

Analyse du dépôt complet (914 Mo, 65 tags) : **228 commits** entre le 27/08 et le 15/09, **186 fichiers editor modifiés (+15 932 / −497 lignes)**. Point structurant : le repo a été **restructuré en profondeur** (`ide-ui` éclaté en `app/ide-ui-core` + `app/ide-ui-editor` + `app/ide-ui-api` + `app/ide-ui-components`), avec passage à Gradle 9.5.1 / AGP 9.x et un début de support iOS.

### 3.1 Les 10 chantiers majeurs

1. **Split modulaire du monorepo** — le core editor devient réutilisable sans la couche app, avec ses propres tests desktop.
2. **`LineOverlay<T>`** — refonte de `LineRenderCache` : les overlays par ligne (stamps inlays/sémantiques) passent de `HashMap` renumérotées par `mapKeys{}.filterKeys{}` à des **tableaux parallèles** (`values: Array<Any?>` + `stamps: IntArray`) splicés par `copyInto`. Gain mesuré sur device : **0,309 ms → 0,031 ms par Entrée maintenue**, GC ÷12, allocations 426 KB → 4,3 KB par newline.
3. **Cache de mesure contenu-adressé** — `rememberTextMeasurer(cacheSize = 64)` : ~25 % des lignes d'un fichier réel sont identiques (`}`, `    }`) ; façonner 12 lignes distinctes passe de 0,32 ms à 0,13 ms.
4. **Prefetch idle hors viewport** — `PREFETCH_IDLE_MS = 150`, chunks de 8 lignes avec pause de 4 ms, portée ±1 viewport, **bas d'abord, alterné** ; jamais pendant un fling.
5. **Gating grands fichiers** — `LARGE_FILE_CHAR_LIMIT = 2,5 M` / `LARGE_FILE_LINE_LIMIT = 50 000` : au-delà, l'analyse sémantique, le folding, les inlays et la complétion auto sont coupés ; la coloration lexicale par ligne et l'édition restent.
6. **SPI plugins editor** — `applyDecorations()`, `textDecorations`/`gutterMarks`/`pluginInlays`, `DecorationStyles`, `EditorPainterHost` (un painter qui throw est **retiré du registre** au lieu de crasher l'éditeur).
7. **Keymap data-driven rebindable** — `EditorKeymap` + `EDITOR_KEY_DEFAULTS` + ~27 commandes (`EditorCommands`), chords `Outcome.Pending`.
8. **Langages contribuables** — `CodeLanguage` passe d'un enum fermé à un profil (`EditorLanguageProfile`, `SyntaxFamily`) + registre observable ; les langages plugins réutilisent les scanners C-family paramétrés.
9. **Diagnostics groupés par ligne** — `diagnosticsByStartLine()` + chip avec badge de compte + sheet : un warning caché derrière une erreur sur la même ligne redevient accessible.
10. **Sweep diagnostics des onglets ouverts** — `OpenTabDiagnosticsSweep` (gap 40 ms entre onglets, saute focus/read-only/large).

**Correction de prémisse importante** : les fichiers `core/` (Rope, EditorDocument, EditOps, LineStyles, LineTokens, MonospaceText, LargeFile, ExtractedTextSnapshot) **existaient déjà à v3.9.9** — leur apparition sous `app/ide-ui-core/` est un déplacement, pas une création. La lib étant basée sur ~3.9.9, elle possédait déjà le socle ; les vrais deltas exploitables sont les chantiers 2, 3, 4, 6, 7, 8, 9, 10.

### 3.2 Corrections de bugs notables côté CodeAssist (à surveiller)

- Sauvegarde non inscriptible → le tab reste dirty au lieu de tuer l'app (`2840f13db`).
- Écriture derrière un buffer ouvert → drop de l'overlay à la fermeture (`314f63b7d`).
- `CompletionPrefixCrashTest` : `StringIndexOutOfBounds` quand le `replaceStart` debouncé survit à un caret repassé devant lui (top crash analytics).
- `CrashFixesTest` : clamp de `EditorDocument.lineStart` sur index stale ; plafond de copie presse-papier (Binder overflow).
- Vues machine en RTL forcé arabe → `LtrContent` généralisé à l'editor.

---

## 4. Analyse approfondie du code-editor (v3.33.5)

### 4.1 Points forts confirmés

- **`Rope`** (321 lignes) : arbre immuable à équilibre Fibonacci, spine-merge, `rebalance` — fidèle au design CodeAssist, correct.
- **`EditorDocument`** (226 lignes) : splice incrémental de `lineStarts[]`, `lineForOffset` binaire, `isLarge()` déjà présent (les seuils 2,5 M / 50 k de CodeAssist sont déjà là).
- **`EditorSession`** : mutation à point unique (`replaceRangeWithCaret`), clamps défensifs des offsets stale, IME bridge très soigné (auto-space SwiftKey/Gboard géré dans les deux formes, gravité left du composing region, `MAX_IPC_TEXT = 4096` Binder-safe).
- **Écriture défensive** : les commentaires documentent chaque bug corrigé avec son scénario — rare et précieux pour la maintenance.

### 4.2 Bugs et défauts identifiés (avec sévérité)

| # | Sévérité | Emplacement | Problème |
|---|----------|-------------|----------|
| B1 | **Bloquant** | `settings.gradle.kts` | `:cel-tm4e` inexistant + `:cel-lsp` omis → build impossible |
| B2 | **Bloquant** | `gradle-wrapper.properties` | Gradle 8.5 < 8.7 requis par AGP 8.5.2 |
| B3 | **Critique** | `LspEditor` / `LspLanguage` (6 sites) | `java.nio.file.*` API 26/34 avec minSdk 24 → crash Android 7.x |
| B4 | **Critique** | `EditorImeBridge:383` | `SurroundingText` (API 31) sans garde explicite |
| B5 | **Majeur** | `EditorSession.doAsyncRestyle` | Doc-changé pendant le gap async → résultat droppé **sans re-scheduling** : coloration mixte permanente (lignes éditées dans la nouvelle langue, le reste dans l'ancienne, jusqu'au prochain setLanguage/undo) |
| B6 | **Majeur** | `EditorSession` | `restyleExecutor` (1 thread/session) **jamais éteint** — pas de `dispose()` ; une app multi-onglets fuit un thread + le rope du document par session |
| B7 | **Majeur** | `EditorView.onDetachedFromWindow` | Aucun retrait des callbacks Handler en attente (tap-hold hover, multi-tap dismiss, code-actions debounce) ni du glide animator → vue détachée reste fortement atteignable et exécute du travail |
| B8 | **Majeur** | `LanguageServerWrapper.shutdown` | `server.shutdown().join()` **sans timeout** → ANR garanti si appelé depuis le main thread avec un serveur hung ; `capabilities` non `volatile` (écrit depuis le thread executor) |
| B9 | **Moyen** | `LspProject.getWrapper` | Race check-then-act : deux onglets d'extensions différentes d'une même def ouverts simultanément → 2 serveurs démarrés, le perdant fuie |
| B10 | **Moyen** | `LspProject.shutdown` | Wrapper enregistré sous N extensions → `shutdown()` appelé N fois |
| B11 | **Moyen** | `EditorSession.toggleLineComment/toggleBlockComment` | Syntaxe de commentaire **hardcodée** (`//`, `/* */`) — pas language-driven comme CodeAssist v3.20 (`profile.lineComment`) : faux comportement en XML/Python/Markdown |
| B12 | **Mineur** | 27 sites (lint `DefaultLocale`) | `toLowerCase()`/`toUpperCase()` sans `Locale` — bug turc (`İ`/`ı`) : filtre de complétion et recherche Find/Replace incorrects en locale `tr` |
| B13 | **Mineur** | `SyntaxHighlighter.setTextMateEnabled` | État global statique muté par `EditorSession.setLanguage` — deux sessions partagent le drapeau (design smell, sans effet tant que tm4e n'est pas réintroduit) |
| B14 | **Mineur** | zip livré | `gradlew` non exécutable (Permission denied au premier essai) |

### 4.3 Hotspots de performance

| # | Emplacement | Problème | Équivalent CodeAssist 3.20 |
|---|-------------|----------|---------------------------|
| P1 | `EditorSession.getDiagnostics/getSemanticTokens/getFoldRegions/getInlayHints` | Copie défensive `new ArrayList<>(...)` **à chaque appel** — or le draw path appelle ces getters **une fois par ligne manquée au cache** (`layoutForLine`) : un scroll sur 50 lignes avec 500 tokens = 100 copies de liste + 25 000 itérations de filtre | `LineOverlay.update()` mémoïse la liste source |
| P2 | `LineRenderCache.shiftKeys/invalidateFrom` | Ré-allocation de **3 HashMaps** (cache + 2 stamps) avec boxing Integer à **chaque** saut de ligne (chaque Entrée) | `LineOverlay<T>` — commit `62f7b7a00` : tableaux + `copyInto` |
| P3 | `EditorView.docLineToY` | `countHiddenLinesAbove` O(docLine) **par appel**, appelé par ligne visible au draw → O(viewport × docLine) avec folds repliés (le commentaire du code reconnaît le problème) | précalcul/prefix-sum |
| P4 | `EditorScrollManager.maxH` | Itère **toutes les lignes** du document à chaque appel (utilisé au clamping horizontal) | cache incrémental de la longueur max |
| P5 | Absent | Pas de prefetch hors viewport : atterrir sur du texte froid paie le layout complet dans le frame (CodeAssist a mesuré 3,6 ms + 1,2 MB dans UN frame) | `prefetchOrder` + idle 150 ms |

### 4.4 Qualité structurelle

- **`EditorView.java` : 4 454 lignes** — god class (vue + état + popups + scroll + métriques). L'extraction en managers (`EditorScrollManager`, `EditorPopupManager`, `EditorInputHandler`, `EditorImeBridge`, `CaretAnimator`, `EditorRenderer`) est bien engagée (v3.11+) mais inachevée ; le README décrivait encore l'ancienne structure `library/` + `app/` de la v1.0.x.
- **Documentation hors code excellente** (`ARCHITECTURE.md`, `CHANGELOG.md` de 210 Ko très détaillé) mais **désynchronisée** du code (README revendiquait 374 tests et une structure inexistante).
- **Aucune configuration de publication Maven** — `maven-publish` absent des 4 modules : JitPack n'aurait rien pu publier.

---

## 5. Correctifs appliqués (v3.34.0)

### 5.1 Build et compatibilité

- **B1** — `settings.gradle.kts` : `:cel-tm4e` → `:cel-lsp` (le module LSP4J rejoint le build ; ses 7 tests tournent désormais).
- **B2** — Wrapper Gradle 8.5 → **8.7** (minimum AGP 8.5.2 ; CodeAssist v3.20 est passé à Gradle 9.5/AGP 9, migration trop invasive pour cette itération — notée en roadmap).
- **B3** — Nouvelle classe **`jo.codeeditor.lsp.IoCompat`** : lecture/écriture UTF-8 en `java.io` pur (FileInputStream/FileOutputStream bufferisés), compatible tout niveau d'API. Les 7 sites `java.nio.file.*` de `LspEditor` et `LspLanguage` (y compris le site `references:` à la ligne 1436 non listé par le premier rapport lint) migrent vers `IoCompat.readUtf8/writeUtf8`. 8 tests JUnit couvrent round-trip, `file://`, null sur illisible, création des répertoires parents, sémantique d'écrasement, gros fichiers (~1 Mo multi-chunks).
- **B4** — Garde `SDK_INT < S → return null` explicite dans `getSurroundingTextCompat` (le garde structurel via `Api31InputConnection` existait ; le garde in-method le rend vérifiable par lint et sûr pour tout futur appelant).

### 5.2 Stabilité du moteur

- **B5** — `doAsyncRestyle` : sur détection `doc != docAtStart`, le restyle est désormais **re-soumis** pour le document courant au lieu d'être silencieusement droppé. La soumission `restyleAllAsync()` est passée en `synchronized(this)` (le re-scheduling vient du thread worker ; sans monitor, un `setLanguage` UI concurrent pouvait produire deux tâches de même génération). Test de non-régression : `editDuringAsyncRestyleGap_eventuallyRestylesWholeDocument` vérifie que la chaîne `entryState[i] == exitState[i-1]` tient sur tout le document après une édition pendant le gap.
- **B6** — **`EditorSession.dispose()`** : annule le restyle pending, `shutdownNow()` de l'executor, drapeau `disposed`. Après dispose, `setLanguage`/undo/redo retombent sur le chemin **synchrone** (correct, bloquant) au lieu de lever `RejectedExecutionException`. Tests : drapeau, annulation, fallback synchrone, édits toujours fonctionnels.
- **B7** — `EditorView.onDetachedFromWindow` retire désormais : la tâche de prefetch idle, le debounce code-actions, tous les callbacks de `EditorInputHandler` (nouvelle méthode `cancelPendingCallbacks()`), et annule le glide animator (`caretAnim.cancelGlide()`).

### 5.3 Lifecycle LSP

- **B8** — `LanguageServerWrapper.shutdown()` : `get(2, TimeUnit.SECONDS)` borné (au lieu de `join()` infini), `exit()` dans son propre try/catch, `connectionProvider.stop()` best-effort, méthode `synchronized` (alignée sur `start()`), `capabilities` devient `volatile`.
- **B9** — `LspProject.getWrapper` : la séquence check-then-act est sous monitor (`wrapperCreationLock`) avec double-check — un seul serveur par définition, même sous concurrence.
- **B10** — `LspProject.shutdown` : déduplication via `HashSet` des wrappers partagés entre extensions.

### 5.4 Performance (backports CodeAssist 3.20)

- **P1** — Suppression des copies défensives dans les 4 getters (`Collections.unmodifiableList(field)` — sûr car chaque setter/édit **remplace** la liste, jamais ne la mute) **+ index par ligne mémoïsé** : nouvelles API `EditorSession.getInlayHintsForLine(line)` et `getSemanticTokensForLine(line)` reconstruisent un bucket `Map<Integer, List<…>>` uniquement quand la référence de liste source change (portage direct de l'idée `LineOverlay.update()` — re-pusher la même liste est gratuit, une édition coûte UNE reconstruction O(H) au lieu d'un filtre complet par ligne manquée). `EditorView.layoutForLine` consomme les buckets : le coût par ligne manquée passe de O(hints + tokens + copies) à O(taille du bucket).
- **P2** — Portage **`LineOverlay`** dans `LineRenderCache` : les stamps de révision inlay/sem vivent dans un tableau `int[]` dense indexé par ligne, avec sentinelle `ABSENT` (préserve la sémantique `getOrDefault(line, -1)` : une ligne splice-ée en existence reste « absente » tant qu'elle n'est pas écrite). `splice(fromLine, delta)` = deux `System.arraycopy` + un `Arrays.fill` — zéro boxing, zéro HashMap. Le layout cache lui-même (borné 512) re-key dans une `LinkedHashMap` pré-dimensionnée en conservant l'ordre d'accès LRU. 7 tests pinnent la sémantique observable (absence, insertion, suppression, troncature, clear, shift des entrées de layout).
- **P5** — **Prefetch idle** dans `EditorView` (portage de la politique `prefetchOrder` de CodeAssist) : après **150 ms** de scroll calme (re-armé à chaque mutation de `vOffset`, donc jamais pendant un fling), pré-chauffe du cache de rendu sur **±1 viewport**, en **chunks de 8 lignes avec pause de 4 ms** (la continuation re-calcul depuis le viewport courant — les lignes déjà chaudes sont des hits O(1)), **bas d'abord puis alterné** below/above, lignes cachées par un fold sautées, bail-out si le viewport a bougé de plus d'un demi-viewport.

### 5.5 Hygiène

- **B12** — 25 sites `.toLowerCase()/.toUpperCase()` → `Locale.ROOT` (complétion, Find/Replace, navigation, highlight, popups).
- **Accessibility** — `performClick()` appelé dans les chemins de tap (`EditorInputHandler.handleTap` → `view.performClick()`, `BarButton`, `SymbolKey`, `CanvasBodyView` + override) : feedback TalkBack et warning lint résolus.
- **`DrawAllocation`** — `BreadcrumbBar.onDraw` : les 2 `Paint` alloués par frame deviennent des champs préalloués.
- **B13 (partiel)** — Documenté ; le retrait complet de l'état statique `textMateEnabled` est en roadmap (le drapeau est sans effet depuis le retrait de tm4e v2.55).
- **B14** — `gradlew` rendu exécutable avant le packaging du zip.
- **`gradle.properties`** — `org.gradle.parallel=true` (builds plus rapides, modules indépendants).

### 5.6 Publication

- Plugin **`maven-publish`** + `singleVariant("release") { withSourcesJar() }` dans les 4 modules ; `afterEvaluate { publishing { create<MavenPublication>("release") { from(components["release"]) } } }` avec `groupId = jo.codeeditor`, `artifactId = cel-*`, `version = 3.34.0` propagés depuis le root (`subprojects { group/version }`).
- **`jitpack.yml`** (JDK 17) pour que JitPack construise avec le bon toolchain.
- Validation : `./gradlew publishToMavenLocal` produit `cel-core/cel-lsp-api/cel-lsp/cel-ui` × (`aar` + `sources.jar` + `pom` + `module`) ; le POM de `cel-ui` expose bien `cel-core` et `cel-lsp-api` en scope compile (transitivité pour les consommateurs).

---

## 6. Tests et validation

| Contrôle | Avant | Après |
|----------|-------|-------|
| `./gradlew help` | **ÉCHEC** (B1+B2) | OK |
| `assembleDebug` (4 AAR) | impossible | **OK** |
| `assembleRelease` (4 AAR) | impossible | **OK** |
| Tests unitaires | 758 (mais `cel-lsp` exclu du build) | **781 / 0 échec** — cel-core 599 (+15), cel-lsp-api 22, cel-lsp 15 (+8), cel-ui 145 |
| Lint | **13 erreurs**, 37 warnings | **0 erreur** (13 → 0 ; warnings restants : 3 GradleDependency + DefaultLocale résiduels des tests) |
| Publication Maven | absente | `publishToMavenLocal` OK, POMs transitifs vérifiés |

**Nouveaux tests** (23) :
- `V3340RegressionTest` (cel-core, 9) : dispose (drapeau, annulation, fallback synchrone, édits post-dispose), buckets par ligne (bornes, rebuild après édition via shift, tokens multi-lignes présents dans chaque bucket traversé, clamp des tokens pathologiques), getters vue vivante sans copie, chaîne de tokenisation intacte après édition pendant le gap async.
- `V3340LineOverlayTest` (cel-core, 7) : sémantique ABSENT, put/get, splice insertion (lignes nouvelles absentes, décalage exact), splice suppression (collapse), troncature `invalidateFrom`, clear, contrat `shiftKeys` des entrées de layout préservé.
- `IoCompatTest` (cel-lsp, 8) : round-trip UTF-8 (dont CJK/emoji), strip `file://`, null sur absent/répertoire/null, création de répertoires parents, écrasement, gros fichiers multi-chunks.

---

## 7. Recommandations — roadmap post-v3.34.0

Classées par rapport impact/effort ; les items 1-4 sont les prochains gains réels identifiés chez CodeAssist v3.20 mais volontairement non intégrés dans cette itération (périmètre maîtrisé, risque de régression nul prioritaire).

| # | Amélioration | Impact | Effort | Notes |
|---|--------------|--------|--------|-------|
| 1 | **Commentaires language-driven** (B11) : `commentSyntaxFor(language)` → `lineComment/blockComment` par langage (XML `<!-- -->`, Python `#`…) | Fonctionnel (correctness multi-langage) | Faible | S'inspirer de `EditorLanguageProfile` CodeAssist ; `EditOps`/`toggleLineComment` à paramétrer |
| 2 | **Cache de layout contenu-adressé** (64 entrées) : LRU `lineText → layout` en plus du cache par ligne — ~25 % des lignes d'un fichier sont identiques | Perf scroll | Faible-moyen | Au niveau `EditorView` (les layouts StaticLayout du mode ligatures) |
| 3 | **Prefix-sum pour `countHiddenLinesAbove`** (P3) : recalcul par (foldRev) au lieu de O(docLine) par appel | Perf draw avec folds | Faible | `FoldModel` a déjà l'info |
| 4 | **`maxH()` incrémental** (P4) : maintenir la longueur max de ligne à l'édition au lieu d'un scan O(lignes) par appel | Perf scroll horizontal sur gros fichiers | Faible | Attention aux inlays |
| 5 | **Diagnostics groupés par ligne de début** : chips + badge + sheet groupée | UX | Moyen | Portage `diagnosticsByStartLine` |
| 6 | **Keymap data-driven** : table commandes → raccourcis rebindable (remplace la chaîne d'`if` de `EditorKeyHandler`) | Fonctionnel + testabilité | Moyen | Portage `EditorKeymap`/`EditorCommands` |
| 7 | **Registre de langages contribuables** : `LanguageProfile` (extensions, keywords, syntax family) + registre — remplace le dispatch par `String` | Extensibilité | Moyen-élevé | Portage `EditorLanguageRegistry` |
| 8 | **Sweep diagnostics des onglets ouverts** (gap 40 ms) + dots de statut | UX | Moyen | Nécessite un hôte multi-onglets |
| 9 | **SPI décorations plugins** (`applyDecorations`, painters retirés s'ils throw) | Extensibilité | Élevé | Portage `EditorPainterHost` |
| 10 | **Migration Gradle 9.x / AGP 9** (alignement CodeAssist v3.20) | Technique | Élevé | À planifier après stabilisation JitPack |
| 11 | **Poursuite du démantèlement d'`EditorView`** (4 454 lignes) : extraire metrics/scroll-state/popup-anchors vers les managers existants | Maintenabilité | Élevé | Continuer le mouvement v3.11+ |
| 12 | **CI GitHub Actions** : build + tests + lint sur push/PR, et build JitPack automatisé par tag | Process | Faible | Workflow simple avec JDK 17 + SDK 34 |

---

## 8. Publier sur JitPack (procédure)

1. Créer le dépôt GitHub (public) et pousser le projet **avec le zip décompressé tel quel** (le `.gitignore` exclut déjà `build/`, `local.properties`… — vérifier que `local.properties` n'est pas commité).
2. Tagger la version :
   ```bash
   git add -A && git commit -m "v3.34.0 — stability & perf release"
   git tag v3.34.0
   git push origin main --tags
   ```
3. Sur [jitpack.io](https://jitpack.io), entrer `com.github.<user>/code-editor` et cliquer **Get it** — le build utilise le `jitpack.yml` fourni (JDK 17). Vérifier le statut **green**.
4. Consommer :
   ```kotlin
   repositories { maven { url = uri("https://jitpack.io") } }
   dependencies {
       implementation("com.github.<user>.code-editor:cel-ui:v3.34.0")
       implementation("com.github.<user>.code-editor:cel-lsp:v3.34.0") // optionnel
   }
   ```
   Les coordonnées `jo.codeeditor:*` restent disponibles via `publishToMavenLocal` pour un usage interne.

**Stabilité requise avant publication** : les 781 tests verts + 0 erreur lint + POMs validés satisfont le critère « stable » fixé ; il reste recommandé de faire un smoke-test dans l'app hôte (CodeIDE) avant de tagger.

---

## 9. Conclusion

La librairie possède un **socle architecture de qualité** (Rope, splice incrémental, triple-stamp cache, IME bridge défensif) fidèle à CodeAssist v3.9.9. Ce qui la bloquait pour la publication était concentré sur trois fronts : **build/config**, **compatibilité API minSdk** et **lifecycle** — tous traités en v3.34.0, plus les deux backports de performance les plus rentables de CodeAssist v3.20 (`LineOverlay`, prefetch idle) et l'infrastructure de publication JitPack complète.

Le chemin vers une lib « publiable » est désormais court : smoke-test dans l'app hôte → tag → JitPack, puis attaquer la roadmap par priorité (commentaires language-driven, caches de layout, keymap) pour continuer à converger vers la qualité de l'editor de CodeAssist v3.20.
