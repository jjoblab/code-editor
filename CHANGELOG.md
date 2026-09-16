# Changelog

All notable changes to **code-editor-lib** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [v3.37.0] — 2026-09-17 — Chords, hygiène B13 & CI (fin de roadmap)

Quatrième itération post-rapport : les trois points restants de la
roadmap v3.34.0 — l'item 12 (CI GitHub Actions, précédemment reporté),
le retrait complet de l'état statique `textMateEnabled` (B13, noté
« sans effet tant que tm4e n'est pas réintroduit ») et les chords
keymap (`Outcome.Pending` chez CodeAssist, future work de l'item 6).
La roadmap v3.34.0 est maintenant traitée à 12/12.

### Added

- **Chords keymap — séquences à deux touches (complément roadmap item
  6, portage `Outcome.Pending` de CodeAssist v3.20)** —
  `EditorKeymap.bindChord(command, first, second)` lie une séquence
  style IntelliJ `Ctrl+K Ctrl+C` à une commande. Nouvelles classes de
  valeur : `EditorKeymap.KeyStroke` (keyCode + ctrl/shift, factory
  `KeyStroke.of(KEYCODE_K, true, false)`) et `EditorKeymap.ChordBinding`.
  Résolution : `resolveChordStart(kc, ctrl, shift)` détecte si une
  touche démarre un chord (mêmes 4 passes de modificateurs que
  `resolve`), `resolveChord(first, kc, ctrl, shift)` complète la
  séquence (4 passes sur le second segment). Dans `EditorKeyHandler`,
  la première touche arme un état **pending** ; la seconde exécute le
  chord ou est traitée comme une frappe fraîche (elle peut elle-même
  ré-armer un chord) ; **Escape annule** le pending ; le pending
  **expire après 2 s** — vérifié par timestamp sur une horloge
  injectable (`LongSupplier clock`, zéro Handler, zéro fuite au
  detach). Priorité : un binding single-key résout TOUJOURS en premier
  — les chords ne capturent que les touches qui tomberaient sinon dans
  le fall-through, donc la table par défaut (sans chord) est 100 %
  compatible v3.36.0.
- **Commandes `TOGGLE_LINE_COMMENT` / `TOGGLE_BLOCK_COMMENT`** — les
  toggles de commentaires language-driven (v3.35.0, B11) deviennent des
  commandes keymap exécutables au clavier, seules ou en chord (le duo
  naturel : `Ctrl+K Ctrl+C` / `Ctrl+K Ctrl+U` à la IntelliJ).
- **CI GitHub Actions (roadmap item 12)** — `.github/workflows/ci.yml` :
  job **build** (chaque push main + chaque PR) = checkout + JDK 17
  Temurin + `gradle/actions/setup-gradle` (cache + validation des
  wrapper JARs) + `assembleDebug` + `testDebugUnitTest` + `lint`, upload
  des rapports en artefact sur échec ; job **release** (tags `v*`) =
  `assembleRelease` + `publishToMavenLocal` (dry-run de la publication
  JitPack : AAR + sources + POM pour les 4 modules) + upload des
  artefacts. Concurrency cancel-in-progress sur les PR ; PRs en lecture
  de cache seule (main reste l'unique écrivain).

### Changed

- **B13 — retrait de l'état statique
  `SyntaxHighlighter.textMateEnabled`** — le toggle global introduit en
  v2.44 était muté par `EditorSession.setLanguage` : deux sessions
  partageaient le drapeau, si bien qu'un grand document coupait la
  délégation TextMate de tous les autres onglets. Le circuit breaker
  grands documents devient **local à chaque passe de restyle** :
  nouvelle surcharge `styleLine(line, entryState, language,
  allowTextMate)` ; les 4 sites d'appel d'`EditorSession`
  (`spliceStyles` ×2, `restyleAll`, `doAsyncRestyle`) calculent
  `allowTextMate = lineCount <= TEXTMATE_LINE_LIMIT` (800 lignes,
  constante qui remplace la `MAX_LINES_FOR_TEXTMATE` v2.44 dépréciée)
  sur LEUR PROPRE taille de document. L'ancienne signature 3-arg
  délègue avec le gate ouvert (contrat inchangé pour les appelants
  externes). En production tm4e est absent (tokenizer null) : aucun
  changement observable ; si un hôte réintroduit un tokenizer, il
  retrouve la sémantique v2.44 saine — petits documents délèguent,
  gros documents non, **par session**.

### Removed

- `SyntaxHighlighter.setTextMateEnabled(boolean)` et
  `SyntaxHighlighter.isTextMateEnabled()` — sans effet depuis le retrait
  de tm4e (v2.55) ; le seul appelant était `EditorSession.setLanguage`
  lui-même. `setTextMateTokenizer(TextMateTokenizer)` (injection du
  tokenizer) est conservé.
- `EditorSession.MAX_LINES_FOR_TEXTMATE` (dépréciée v2.55) — remplacée
  par `TEXTMATE_LINE_LIMIT`.

### Validation

- `assembleDebug` + `assembleRelease` : BUILD SUCCESSFUL (4 modules,
  Gradle 9.5.1 / AGP 9.0.0).
- `testDebugUnitTest` : **922 tests / 0 échec** (cel-core 655,
  cel-lsp-api 22, cel-lsp 15, cel-ui 230) — 907 en v3.36.0, +15 :
  `EditorKeymapTest` 11 → 20 (+9 chords : bind/resolve, 4 passes sur
  start et second, rebind, unbind, isBound/chordBindingFor, value
  classes KeyStroke/ChordBinding, defaults sans chord, priorité
  single-key), nouveau `EditorChordKeyHandlerTest` (6 : séquence
  complète avec round-trip toggle, annulation Escape, expiration 2 s
  sur horloge figée, repli single-key, ré-armement, défaut inchangé) ;
  tests B13 réécrits (même compte) : `textMateGate_disables/delegates`
  + 3 tests session (petit doc délègue, gros doc skip, **une session
  grosse n'affecte pas une session petite** — le test du design smell).
- Lint : **0 erreur** (warnings informationnels préexistants :
  GradleDependency/NewerVersionAvailable, ClickableViewAccessibility,
  DefaultLocale — aucun dans les nouveaux fichiers).
- `publishToMavenLocal` : `jo.codeeditor:cel-{core,lsp-api,lsp,ui}:3.37.0`
  (aar + sources.jar + pom + module), POM cel-ui transitif vérifié.
- CI : workflow YAML validé (syntaxe + actions v4 : checkout, setup-java
  Temurin 17, gradle/actions/setup-gradle avec wrapper validation).

## [v3.36.0] — 2026-09-17 — Extensibilité & UX (roadmap items 5-11)

Troisième itération post-rapport : les items 5 à 11 de la roadmap v3.34.0
(le 12 — CI GitHub Actions — est volontairement reporté). Au menu : les
diagnostics groupés par ligne, la keymap rebindable, le registre de
langages contribuables, le sweep des onglets ouverts, le SPI de
décorations plugins, la migration Gradle 9 / AGP 9 et la suite du
démantèlement d'`EditorView` — toujours sans breaking change d'API
publique (nouveautés additives ; les signatures existantes sont
déléguées).

### Added

- **Diagnostics groupés par ligne de début (roadmap item 5, portage
  `diagnosticsByStartLine()` de CodeAssist v3.20)** — une ligne portant
  une erreur ET un warning ne surfait que la plus sévère : le warning
  était inatteignable depuis la chip. Désormais :
  `EditorSession.getDiagnosticsForLine(line)` expose le groupe par ligne
  de début (buckets mémoïsés sur la référence de liste — même pattern que
  les buckets inlays/sem v3.34.0, tri sévérité-décroissante puis
  offset-croissant) ; la **chip** porte un **badge de compte** (cercle
  plein couleur sévérité + compte blanc) quand la ligne a plusieurs
  Error/Warning (la largeur du badge est incluse dans la géométrie
  partagée draw/hit-test) ; le tap ouvre la **sheet groupée** (scrim +
  panel docké bas, une rangée par diagnostic — info incluse, dot de
  sévérité + message tronqué, plafond 8 rangées + rangée « …et N de
  plus ») ; le tap d'une rangée ouvre le popup détail existant (message
  complet + quick fixes). Une ligne à diagnostic unique ouvre toujours
  directement le popup détail. La sheet est modale (geste englouti
  comme le popup détail), Esc la referme — Esc referme désormais AUSSI
  le popup détail (petit gain UX au passage).
- **Keymap data-driven rebindable (roadmap item 6, portage
  `EditorKeymap`/`EditorCommands` de CodeAssist v3.20)** — les cascades
  Ctrl+shortcuts et mouvement/édition d'`EditorKeyHandler` sont
  remplacées par une table de bindings résolue par event :
  `EditorCommands` (~45 ids : undo/redo, presse-papiers, déclencheurs
  LSP, navigation, zoom, édition, mouvement + variantes EXTEND_*),
  `EditorKeymap.defaults()` (portage verbatim des raccourcis v3.35.0),
  `view.setKeymap()` / `view.getKeymap()` pour rebind runtime
  (`km.bind(EditorCommands.REDO, KEYCODE_Z, true, true)` = Ctrl+Shift+Z
  redo à la IntelliJ). La résolution se fait en 4 passes (exact → sans
  ctrl → sans shift → sans aucun) pour préserver les habitudes héritées
  des cascades : Ctrl+flèches déplacent toujours le caret, Ctrl+Tab
  indente, Ctrl+Shift+A sélectionne tout, Shift+Enter insère un saut de
  ligne. Les intercepteurs de popups (complétion, signature help, code
  actions, go-to-symbol) gardent la priorité sur la keymap. Les chords
  (séquences à deux touches, `Outcome.Pending` chez CodeAssist) restent
  en future work.
- **Registre de langages contribuables (roadmap item 7, portage
  `EditorLanguageRegistry`/`EditorLanguageProfile` de CodeAssist
  v3.20)** — nouveau package `jo.codeeditor.languages` (cel-core) :
  `LanguageProfile` (nom canonique, alias, extensions, mots-clés,
  `SyntaxFamily`, `CommentSyntax` — builder fluide, immuable),
  `LanguageRegistry` (lookups insensibles à la casse par nom/alias ET par
  extension de fichier, register/unregister/override de built-ins,
  **listeners observables** notifiés à chaque mutation,
  `resetToBuiltins()` pour les tests), `SyntaxFamily` (14 familles
  lexicales), `BuiltinLanguages` (les 27 langages intégrés — tables de
  mots-clés déplacées verbatim depuis `SyntaxHighlighter`). Le highlighter
  route désormais par famille (switch sur le profil) et
  `CommentSyntax.forLanguage` délègue au registre : **un hôte peut
  enregistrer son propre langage** (`LanguageRegistry.register(profile)`)
  et coloration + toggles de commentaires le prennent en charge
  immédiatement. `EditorSession.getLanguageProfile()` expose le profil
  actif.
- **Sweep diagnostics des onglets ouverts (roadmap item 8, portage
  `OpenTabDiagnosticsSweep` de CodeAssist v3.20)** — l'éditeur ne
  rafraîchissait les diagnostics que de l'onglet focus (debounce) : les
  onglets arrière-plan gardaient leurs points rouges périmés. Le sweep
  (`OpenTabDiagnosticsSweep.start(List<EditorView>)`) parcourt les
  onglets ouverts, recalcule chaque provider HORS main thread, applique
  sur le main thread avec un **gap de 40 ms entre onglets** (CodeAssist),
  et saute : l'onglet focus (son debounce le possède), les sessions
  read-only, les gros documents (`isLarge()` — même gating que l'analyse
  sémantique), les onglets sans provider et les vues détachées. Un
  résultat dont la session a changé pendant le vol est jeté (garde
  d'identité), `cancel()` arrête la marche, un provider qui throw est
  compté sauté sans tuer le sweep.
- **SPI décorations plugins (roadmap item 9, portage
  `EditorPainterHost` de CodeAssist v3.20)** — les hôtes peuvent
  décorer l'éditeur sans toucher à ses couches :
  `EditorDecorationPainter` (SPI : `paint(EditorPaintContext)` une fois
  par frame), `EditorPaintContext` (fenêtre visible, métriques, et
  `addTextDecoration` / `addGutterMark` / `addPluginInlay`),
  `EditorDecorations` + `DecorationStyles` (UNDERLINE / BOX /
  STRIKE_THROUGH). Le rendu : soulignés/encadrés/barrés par range
  (wrap-aware, au-dessus des squiggles), **barres de gutter** façon
  VCS-blame (bord droit de la zone numéros, n'entrent jamais en
  collision avec les dots de diagnostics), **inlays fantômes** après la
  fin de ligne (85 %, se placent après la chip diagnostic le cas
  échéant). **Un painter qui throw est retiré du registre au lieu de
  crasher l'éditeur** (politique CodeAssist), avec listeners
  `onPainterRemoved` pour télémétrie. `loadFromClasspath()` charge les
  painters déclarés en `META-INF/services/…EditorDecorationPainter`
  (ServiceLoader). Accès : `view.getPainterHost()`.
- **`GutterView.setPluginMarks(Map<Integer,Integer>)`** — l'API des
  barres de gutter plugins (consommée par le renderer depuis la frame du
  painter host).

### Fixed

- **Routage des alias de langages (item 7, conséquence assumée)** — les
  ids courts qui tombaient à travers les chaînes de strings du
  highlighter vers le tokenizer C générique (avec les mots-clés Java !)
  routent désormais vers leur vrai tokenizer : `py` → Python (`#`
  commenté), `md` → Markdown, `svg`/`htm` → XML, `ini` → properties,
  `kt` → mots-clés Kotlin, `rs` → mots-clés Rust. Le comportement des
  noms canoniques est inchangé octet pour octet (tests v3.35.0
  inchangés).

### Changed

- **Migration Gradle 9.5.1 / AGP 9.0.0 (roadmap item 10)** — wrapper
  8.7 → 9.5.1, plugin 8.5.2 → 9.0.0 (génération de CodeAssist v3.20).
  Build, tests, lint et publication validés sur le nouveau toolchain.
  Note AGP 9 : la tâche `testReleaseUnitTest` n'existe plus (les tests
  unitaires tournent sur la variante debug ; la release est validée par
  `assembleRelease` + lint). `jitpack.yml` inchangé (JDK 17).
- **Démantèlement d'`EditorView` (roadmap item 11) : 5 066 → 4 626
  lignes (−440)** — deux extractions vers le pattern managers :
  `EditorPopupAnchors` (toute la géométrie des popup-anchors partagée
  draw/hit-test : sheet détail, sheet groupée, chips + badge, toolbar de
  sélection + sa classe `SelectionToolbarMetrics`, NavMenu, compteur de
  word-wrap ; EditorView garde des wrappers déléguants aux signatures
  historiques — renderer, input handler et tests inchangés) et
  `EditorZoomController` (l'état `fontScale` + `setFontScale`,
  `applyPinchScale` ancré caret v2.58, `increaseFontSize`/
  `decreaseFontSize`, bornes [0.6, 2.6] ; delegates publics préservés).
- **`EditorRenderer.drawDiagnosticChips`** — itère les buckets par ligne
  du groupe (mémoïsés) au lieu de reconstruire une HashMap de toute la
  liste à chaque frame ; le draw reste O(lignes visibles).
- **`chipDiagnosticForLine`/`findDiagnosticChipAt`** — réimplémentés sur
  les buckets (le second délègue au nouveau hit groupé
  `findDiagnosticChipHitAt` qui porte la ligne + le groupe complet).

### Validation

- `assembleDebug` + `assembleRelease` : OK sur les 4 modules (Gradle
  9.5.1 / AGP 9.0.0).
- Tests : **907 / 0 échec** (cel-core 655, cel-lsp-api 22, cel-lsp 15,
  cel-ui 215) — 855 en v3.35.0, **+52 nouveaux** :
  `LanguageRegistryTest` 14 (lookups, familles, mots-clés, parité
  commentaires v3.35.0, register/unregister/override, listeners,
  langage custom → highlighter + commentaires), `DiagnosticsByLineTest`
  4 (buckets par ligne de début, tri, mémoïsation, offsets
  pathologiques), `EditorKeymapTest` 10 (table par défaut, 4 passes de
  résolution, rebind/unbind, instances neuves), `DiagnosticGroupedSheetTest`
  8 (groupe chip + badge, hit groupé, sheet multi vs popup mono, cap 8
  rangées, tap rangée → détail, scrim, smoke render),
  `EditorPainterHostTest` 6 (collecte 3 types, painter qui throw retiré +
  listener, dédup par id, clamps hostiles, smoke render),
  `OpenTabDiagnosticsSweepTest` 10 (application onglet arrière-plan,
  éligibilité focus/read-only/large/sans provider/détaché, provider qui
  throw, stale-guard, cancel, liste vide).
- Lint : **0 erreur** (22 warnings informationnels — versions de deps +
  ClickableViewAccessibility préexistants ; aucun dans les nouveaux
  fichiers).
- `publishToMavenLocal` : `jo.codeeditor:cel-{core,lsp-api,lsp,ui}:3.36.0`
  (aar + sources + pom + module), POM de `cel-ui` transitif vers `cel-core`
  et `cel-lsp-api` (scope compile).

## [v3.35.0] — 2026-09-16 — Commentaires language-driven & caches de rendu (roadmap items 1-4)

Deuxième itération post-rapport : les quatre premiers items de la roadmap
v3.34.0 (les « prochains gains réels identifiés chez CodeAssist v3.20 »),
sans breaking change d'API publique.

### Fixed

- **Commentaires faussement C-style hors Java (bug B11, roadmap item 1)** —
  `toggleLineComment()` hardcodait `//` et `toggleBlockComment()` la paire
  C-style : un fichier Python recevait `// def foo():`, un XML `// <node>`,
  un JSON des commentaires qu'il ne peut pas avoir. La syntaxe est désormais
  résolue par langage via `CommentSyntax.forLanguage(language)` (portage de
  l'idée `EditorLanguageProfile` de CodeAssist v3.20) :
  `#` (Python, Ruby, shell, TOML, properties, smali, YAML), `--` (Lua, SQL),
  `--[[ ]]` (Lua), `<!-- -->` (XML/HTML/Markdown), rien du tout (JSON →
  no-op documenté). Les langages inconnus retombent sur le défaut C-family —
  le comportement Java est préservé octet pour octet (tests existants
  inchangés).
- **Round-trip commentaire impossible (sélection effondlée)** —
  `replaceRange()` réduit la sélection à un caret : après avoir commenté N
  lignes, le toggle suivant ne voyait que la ligne du caret et ne
  décommentait qu'elle. Les trois toggles restaurent désormais une sélection
  couvrant le bloc transformé (préfixe) ou le contenu (paire de blocs) —
  commenter puis re-toggler décommente, comme dans VS Code.
- **Unwrap de bloc laissait les espaces de lisibilité** — le wrap insère
  `/* … */` avec espaces, mais l'unwrap ne retirait que les délimiteurs :
  `hello` → `/* hello */` → `␣␣hello␣␣`. L'unwrap retire désormais
  l'espace optionnel collé à chaque délimiteur (le cas sans espaces de la
  suite de régression reste intact).
- **`countHiddenLinesAbove` O(folds × log lignes) par appel (hotspot P3)** —
  appelé une fois par ligne visible au draw via `docLineToY`. Remplacé par
  un **index de folds mémoïsé** (`FoldIndex`) : régions collapsed fusionnées
  (union, comme `FoldModel.mergeRegions` — l'ancien code double-comptait les
  folds chevauchants), triées, avec prefix-sums ; `hiddenAbove(line)` et
  `isHidden(line)` en O(log folds). Mémoïsé sur
  `(session, foldRev, doc)` — un compteur `foldRev` a été ajouté à
  `EditorSession` car `getFoldRegions()` retourne un wrapper neuf à chaque
  appel et `toggleFoldAtLine()` mute la liste en place : une comparaison de
  référence ne pouvait pas détecter les changements.
- **`docLineForScreenY` O(docLineCount × folds) par tap** — la boucle qui
  marchait toutes les lignes du document en sautant les cachées est
  remplacée par une recherche binaire sur `visibleIndex(l) = l -
  hiddenAbove(l)` (non-décroissant) + skip-forward dans le fold
  éventuellement chevauchant. Même sémantique, fallback dernière ligne
  compris.
- **`maxH()` scan O(lignes + hints) à CHAQUE appel (hotspot P4)** — appelé
  plusieurs fois par frame (clamping scroll/fling/caret-into-view). Le scan
  (ligne la plus longue + débordement visuel des inlays) est mémoïsé sur
  `(session, doc, inlayRev)` : une fois par édition au lieu de plusieurs
  fois par frame. La valeur mémoïsée est en colonnes — les changements de
  taille de police n'invalident pas le cache (la conversion pixel reste
  hors memo), et le `chipExtentContentX` mesuré au draw reste lu à chaque
  appel.

### Added

- **`jo.codeeditor.edit.CommentSyntax`** — profil de commentaires immuable
  par langage (`lineComment`/`blockStart`/`blockEnd`, aliases courts
  `py`/`js`/`ts`/`rs`/`rb`/`sh`/`kt`/`md`/`yml`, normalisation
  `Locale.ROOT`).
- **`EditorSession.getCommentSyntax()` / `setCommentSyntax(CommentSyntax)`** —
  résolution language-driven, avec override explicite pour les langages que
  la table ne connaît pas (hôtes embarquant des DSL).
- **`EditorSession.getFoldRevision()`** — révision monotone du jeu de folds
  (bumpée par chaque mutation), consommée par l'index de la vue.
- **`EditorMetrics.getFontRevision()`** — génération de police (bumpée par
  `setTextSize`/`setTypeface`), consommée par le cache de layouts.
- **Cache de layouts façonnés contenu-adressé (roadmap item 2)** — portage
  du `rememberTextMeasurer(cacheSize = 64)` de CodeAssist v3.20 : en mode
  ligatures, le draw path reconstruisait un `SpannableStringBuilder` + un
  `StaticLayout` (façonnage natif) pour CHAQUE ligne à CHAQUE frame (scroll,
  clignotement caret, sélection). ~25 % des lignes d'un fichier réel étant
  identiques (`}`, `    }`…), elles partagent désormais un seul layout : LRU
  64 entrées, clé = texte de ligne, validité = signature des spans
  (début/fin/type) + couleur de base du paint + génération de police +
  identité du thème. Invalidation totale sur `setTheme` et changement de
  police ; l'adressage par contenu ignore volontairement la session (même
  texte + mêmes spans + même police + même thème = mêmes pixels).
- **74 tests unitaires** (855 au total, 0 échec) :
  `CommentSyntaxTest` (12 — familles, aliases, fallback, Locale.ROOT),
  `LanguageCommentToggleTest` (26 — comportement par langage, no-op JSON,
  fallback XML, override, undo), `FoldIndexTest` (17 — sémantique
  hiddenAbove/isHidden, fusion d'unions, invalidations foldRev/édition/
  session, round-trip docLineToY↔docLineForScreenY),
  `ShapedLayoutCacheTest` (11 — partage par contenu, signatures, bornes LRU
  64, invalidations thème/police, partage inter-sessions),
  `MaxHIncrementalTest` (9 — valeurs conformes à l'ancienne formule,
  invalidations édition/inlays/session, scaling police, garde word-wrap).

### Changed

- **`EditorRenderer` (11 sites) + `GutterView` + prefetch + hit-test chips** —
  `session.isLineFolded()` (O(folds) par ligne) remplacé par
  `view.isLineFoldedCached()` (O(log folds) via l'index mémoïsé) : le draw
  avec folds repliés passe de O(viewport × folds) à O(viewport × log folds).
- **`EditorRenderer.drawStyledLine`** — le path ligatures délègue le
  façonnage à `EditorView.shapedLayoutFor()` (le code de construction des
  spans y est déplacé à l'identique : ForegroundColorSpan + StyleSpan
  italic/gras pour COMMENT/KEYWORD/ANNOTATION).

### Validation

- `assembleDebug` + `assembleRelease` : OK sur les 4 modules.
- Tests : **855 / 0 échec** (cel-core 637, cel-lsp-api 22, cel-lsp 15,
  cel-ui 181) — 781 en v3.34.0.
- Lint : **0 erreur** (warnings résiduels préexistants uniquement).
- `publishToMavenLocal` : `jo.codeeditor:cel-{core,lsp-api,lsp,ui}:3.35.0`
  (aar + sources + pom + module), POM de `cel-ui` transitif vers `cel-core`
  et `cel-lsp-api` (scope compile).

## [v3.34.0] — 2026-09-16 — Stabilité & performance (alignement CodeAssist v3.20)

Release consacrée à la **stabilité pré-publication** (objectif JitPack) :
analyse complète contre CodeAssist v3.9.9 → v3.20.0, correction de tous les
bloqueants (build, compatibilité API 24, lifecycle), et backport des deux
optimisations editor les plus rentables de CodeAssist v3.20.

### Fixed

- **Build cassé** — `settings.gradle.kts` référençait `:cel-tm4e` (inexistant)
  et omettait `:cel-lsp` (présent sur disque) ; wrapper Gradle 8.5 incompatible
  AGP 8.5.2 → **Gradle 8.7**.
- **Crash Android 7.x (13 erreurs lint NewApi)** — `java.nio.file.Files.readAllBytes`
  / `File.toPath` (API 26+), `Path.of` (API 34+) et `SurroundingText` (API 31)
  avec `minSdk 24`. Nouvelle classe `jo.codeeditor.lsp.IoCompat` (java.io pur,
  tout niveau d'API) ; garde `SDK_INT` explicite dans `EditorImeBridge`.
  **Lint : 0 erreur** sur les 4 modules.
- **Coloration mixte permanente** — une édition pendant le gap du restyle async
  était détectée mais le résultat était droppé SANS re-scheduling : le document
  gardait l'ancien language pour toutes les lignes non éditées. Le restyle est
  désormais re-soumis pour le document courant (soumission synchronisée).
- **Fuite de thread par session** — `EditorSession` n'avait pas de `dispose()` :
  l'executor de restyle (1 thread/session) vivait aussi longtemps que le
  processus. `dispose()` annule le pending, éteint l'executor, et les restyles
  ultérieurs retombent sur le chemin synchrone.
- **Callbacks survivant au détachement** — `EditorView.onDetachedFromWindow`
  retire désormais prefetch idle, debounce code-actions, callbacks
  `EditorInputHandler` et annule le glide animator.
- **ANR LSP** — `LanguageServerWrapper.shutdown()` : `join()` infini →
  `get(2, SECONDS)` borné + `exit()`/`stop()` best-effort ; méthode
  synchronisée ; `capabilities` volatile.
- **Race de création de serveurs LSP** — `LspProject.getWrapper` sous monitor
  (un seul serveur par définition sous concurrence) ; `shutdown()` déduplique
  les wrappers partagés entre extensions.
- **Locale turque** — 25 sites `toLowerCase()/toUpperCase()` → `Locale.ROOT`
  (complétion, Find/Replace, navigation, highlight).

### Added

- **`EditorSession.dispose()` / `isDisposed()`** — lifecycle public.
- **Buckets par ligne** — `getInlayHintsForLine(line)` /
  `getSemanticTokensForLine(line)` : index mémoïsé (reconstruit seulement
  quand la liste source change — portage de `LineOverlay.update()` de
  CodeAssist v3.20). Le draw path ne filtre plus les listes complètes par
  ligne manquée au cache : O(bucket) au lieu de O(total).
- **Prefetch idle** (portage `prefetchOrder` CodeAssist v3.20) — après 150 ms
  de scroll calme, pré-chauffe du cache de rendu sur ±1 viewport par chunks
  de 8 (pause 4 ms), bas d'abord puis alterné, jamais pendant un fling,
  lignes foldées sautées.
- **Publication Maven/JitPack** — plugin `maven-publish` + `singleVariant
  ("release")` (+ sources jar) sur les 4 modules ; `jitpack.yml` (JDK 17) ;
  `subprojects` propagent group/version. `./gradlew publishToMavenLocal`
  produit `jo.codeeditor:cel-{core,lsp-api,lsp,ui}:3.34.0` avec POMs
  transitifs.
- **23 tests unitaires** de non-régression (dispose, buckets, LineOverlay,
  IoCompat, gap async) — **781 tests verts** au total.
- **`RAPPORT_ANALYSE_V3.34.0.md`** — rapport technique complet de l'analyse
  CodeAssist v3.9→v3.20 et des correctifs.
- **README.md réécrit** — structure réelle (cel-*), installation JitPack,
  781 tests, guide de publication.

### Changed

- **`LineRenderCache`** — portage `LineOverlay<T>` (CodeAssist v3.20,
  commit 62f7b7a00) : les stamps de révision inlay/sem vivent dans un
  `int[]` dense (sentinelle ABSENT = sémantique `-1` préservée) splicé par
  `System.arraycopy` — zéro boxing, zéro réallocation HashMap par Entrée.
- **Getters diagnostics/tokens/folds/inlays** — vue `unmodifiableList` sans
  copie défensive (les listes sont remplacées, jamais mutées — la copie par
  appel était O(liste) × lignes manquées au cache).
- **Accessibilité** — `performClick()` câblé dans les chemins de tap
  (EditorView, BarButton, SymbolKey, CanvasBodyView).
- **`BreadcrumbBar`** — Paints préalloués (2 allocations/frame supprimées,
  lint DrawAllocation).
- **`org.gradle.parallel=true`**.

### Compatibilité

Aucun breaking change d'API publique (nouvelles méthodes uniquement ;
`getInlayHints()` etc. retournent une vue non-modifiable au lieu d'une copie
non-modifiable — comportement identique en lecture).

### v0.1.0.74-v2.58 — Pinch-zoom caret anchored + completion trigger-char immediate

Deux corrections demandées par l'utilisateur après test mobile v2.57.

#### Fixed

**Complétion — caractère déclencheur immédiat**

Avant v2.58, taper un caractère déclencheur (`.`, `@`, etc.) déclenchait
la requête moteur avec un délai de 120 ms (debounce générique). Combiné au
`flushPendingChange()` SYNCHRONE introduit en v2.57 (pour garantir que le
serveur voie le texte à jour avant chaque requête LSP), le retard perçu
après chaque `.` était sensible (~120 + ~50 + serveur ≈ 250 ms).

Correction : le chemin « préfixe vide + char déclencheur » dans
`EditorPopupManager.refreshCompletion()` passe maintenant
`immediate=true` à `scheduleAsyncFetch` — la requête moteur part
SYNCHRONEMENT sans attendre le debounce. Le debounce reste utile pour
la frappe rapide d'identifiants (10+ touches/sec), mais pour les
déclencheurs (où l'utilisateur marque explicitement une pause), le
debounce n'apportait rien.

Bonus : `COMPLETION_DEBOUNCE_MS` réduit de 120 ms à 80 ms (toujours
suffisant pour grouper la frappe rapide, mais un peu plus réactif).

**Cohérence LSP — flushPendingChange sur format + inlayHints**

Audit des 9 providers LSP (`complete`, `hover`, `signatureHelp`,
`definition`, `rename`, `codeActions`, `references`, `format`,
`inlayHints`). Les 7 premiers avaient déjà leur `flushPendingChange()` en
v2.57. Les deux derniers manquaient :
- `format` — formatter LSP calcule des éditions sur le texte serveur. Si
  le texte était STALE (didChange debouncé à 300 ms pas encore envoyé),
  les éditions seraient calculées sur une version obsolète et les offsets
  seraient hors-sync.
- `inlayHints` — inférence de type sur le texte source. Si le texte
  serveur était STALE, les hints seraient calculés sur une version
  obsolète.

Maintenant, les 9 providers appellent `flushPendingChange()` avant leur
requête LSP — cohérence totale.

**Pinch-zoom — caret ancré à sa position**

Avant v2.58, le `ScaleListener` d'`EditorInputHandler` appliquait
`fontScale * scaleFactor` sans compenser les offsets. Le caret « sautait »
à une nouvelle position écran (les nouvelles metrics donnent un nouveau
`lineHeight`/`charWidth` → `docLineToY(line)` et `visualCol * charWidth`
changent, mais `vOffset`/`hOffset` restaient inchangés → le caret bouge
visuellement).

Correction : nouvelle méthode `EditorView.applyPinchScale(scaleFactor)`
qui :
1. Capture `(cx, cy)` = `caretScreenPos(caretOffset)` AVANT le scale
   (avec les anciennes metrics).
2. Applique le nouveau scale (`fontScale = clamp(scale * factor)`,
   `metrics.setTextSize(spToPx(14) * fontScale)` — lineHeight/charWidth
   suivent le nouveau textSize).
3. Recalcule `caretScreenPos(caretOffset)` avec les NOUVELLES metrics et
   les ANCIENS offsets → retourne `(newPos_x, newPos_y)` = position où
   le caret atterrirait si on ne touchait pas les offsets.
4. Ajuste : `hOffset += newPos_x - cx; vOffset += newPos_y - cy`
   (signe intuitif : « combien le caret a bougé à cause du scale → on
   compense ce mouvement »).
5. Clamp `hOffset`/`vOffset` à `[0, maxH()]`/`[0, maxV()]` (post-scale).
6. Annule tout glide en cours via `caretAnim.onEditOrMove()` (sinon le
   glide repositionnerait le caret après l'ancrage).

Le caret reste visuellement FIXE pendant le pinch — sa hauteur/largeur
suivent le `lineHeight`/`charWidth` qui dérivent de `metrics` (qui suit
le textSize). Effet attendu par l'utilisateur : « le caret devrait
rester fixé à sa position et agrandir ou réduire en même temps que le
pinch zoom ».

#### Tests

- **`CompletionTriggerCharDebounceTest`** (Robolectric, 2 @Test) :
  - `triggerChar_firesImmediatelyWithoutDebounceAdvance` — le latch
    tombe en < 50 ms (très inférieur au debounce de 80 ms), preuve que
    `immediate=true` est pris.
  - `nonTriggerChar_eventuallyFiresViaDebounce` — le latch tombe
    eventuellement (debounce + COMPLETION_EXECUTOR + provide).
- **`EditorPinchZoomCaretAnchorTest`** (Robolectric, 7 @Test) :
  - `pinchZoomIn_keepsCaretAnchoredVertically` — 100 lignes × 80 chars,
    caret ligne 50, pinch IN ×1.5 → cy préservé à 0.5 px près.
  - `pinchZoomIn_keepsCaretAnchoredHorizontally` — non-wrap, hOffset=100,
    cx préservé.
  - `pinchZoomOut_keepsCaretAnchored` — 500 lignes × 200 chars, fontScale
    initial 1.5, vOffset/hOffset pré-scrollés, pinch OUT ×0.667 → cx/cy
    préservés.
  - `pinchZoom_changesFontScale` — sanity check que applyPinchScale
    augmente le fontScale (sinon les tests ci-dessus passeraient
    trivialement).
  - `caretHeight_scalesWithFont_afterPinchZoomIn` — `lineHeight` (utilisé
    par `drawCaret`) croît avec le pinch IN.
  - `pinchZoom_clampedAtMaxFontScale_stillKeepsCaretAnchored` — pinch IN
    past MAX (2.6) → clamp, no-op, caret préservé.
  - `pinchZoom_clampedAtMinFontScale_stillKeepsCaretAnchored` — pinch OUT
    past MIN (0.6) → clamp, no-op, caret préservé.

Total : +9 @Test v2.58, 0 régression sur la suite :ui (98 tests passent).

### v0.1.0.62-v2.46 — Module isolation + parser maison enrichi

Préparation publication code-editor (Maven Central ou GitHub Packages). Deux axes majeurs :

#### Track A — Module isolation :tm4e

`:tm4e` est maintenant un **module optionnel autonome**. Avant v2.46, le bridge `TextMateTokenizerImpl` vivait dans `:app/jo/codeide/ui/editor/` et les assets `textmate/` (grammars + darcula.json + languages.json) vivaient dans `:app/src/main/assets/textmate/`. C'était OK tant que code-editor n'était pas publié, mais pour la publication, il faut que les consumers puissent omettre TextMate.

Changements :
- `TextMateTokenizerImpl.java` déplacé de `:app/jo/codeide/ui/editor/` vers `:tm4e/jo/codeeditor/tm4e/` (nouveau package `jo.codeeditor.tm4e`).
- `TextMateTokenizerImplTest.java` déplacé de `:app/src/test/` vers `:tm4e/src/test/jo/codeeditor/tm4e/`.
- Assets `textmate/` déplacés de `:app/src/main/assets/textmate/` vers `:tm4e/src/main/assets/textmate/`.
- `:tm4e/build.gradle.kts` : namespace `jo.codeeditor.tm4e` (au lieu de `jo.codeide.tm4e`) + ajout `api(project(":core"))` pour le SPI `TextMateTokenizer` + `sourceSets` déclarant `assets.srcDirs("src/main/assets")` (les grammars sont embarqués dans l'AAR de `:tm4e`, accessibles au runtime via `context.getAssets().open("textmate/...")` depuis n'importe quel consumer).
- `ProjectActivity.java` (in `:app`) : `new TextMateTokenizerImpl(this)` → `new jo.codeeditor.tm4e.TextMateTokenizerImpl(this)` (référence FQN inline).
- `:app/build.gradle.kts` : commentaire du block `:tm4e` dependency réécrit pour expliquer l'optionalité.

Architecture finale :

```
┌─────────────────────────────────────────────────────────────┐
│ :core (jo.codeeditor.core)                                  │
│  - SyntaxHighlighter (parser maison, 26 langages built-in)  │
│  - TextMateTokenizer (SPI interface, opt-in)                │
│  - EditorSession, EditorDocument, EditorView deps          │
│  - AUCUNE dépendance vers :tm4e                              │
└──────────────────────────────┬──────────────────────────────┘
                                │ api(project(":core"))
                                ▼
┌─────────────────────────────────────────────────────────────┐
│ :tm4e (jo.codeeditor.tm4e) — OPTIONAL                      │
│  - vendored tm4e source (org.eclipse.tm4e.* — 102 fichiers) │
│  - libs/joni, jcodings, snakeyaml-engine, jdt-annotation    │
│  - TextMateTokenizerImpl (bridge impl, jo.codeeditor.tm4e)  │
│  - assets/textmate/grammars/* + themes/darcula.json +       │
│    languages.json (embarqués dans l'AAR du module)          │
└─────────────────────────────────────────────────────────────┘
```

Consumers de code-editor (Maven/GitHub) peuvent :
- **Inclure :tm4e** : richer highlighting via 17 TextMate grammars (scope inheritance, injections, multi-line constructs).
- **Omettre :tm4e** : le `SyntaxHighlighter` built-in de `:core` gère tous les langages communs (Java, Kotlin, JS, TS, C, C++, Go, Rust, Ruby, PHP, Swift, Dart, Groovy, XML/HTML, CSS/SCSS/LESS, Shell/Bash, YAML, SQL, Properties, TOML, Smali, Markdown, Python, JSON, Lua, log).

#### Track B — Parser maison enrichi (SyntaxHighlighter.java in :core)

`SyntaxHighlighter` gère maintenant **26 langages** out of the box, sans dépendance vers `:tm4e`. C'est le "parser maison" par défaut pour les consumers qui veulent publier code-editor sans TextMate.

**14 nouveaux keyword sets** :
- `C_KEYWORDS` (C89/C99/C11 — 35 mots-clés incluant `_Bool`, `_Atomic`, etc.)
- `CPP_KEYWORDS` (C++11..C++20 — 80+ mots-clés incluant `concept`, `co_await`, etc.)
- `CPP_TYPES` (stdlib types — `std::string`, `vector`, `map`, `shared_ptr`, etc.)
- `GO_KEYWORDS` (Go 1.x — 28 mots-clés incluant `iota`)
- `GO_BUILTINS` (`append`, `make`, `int32`, etc.)
- `RUST_KEYWORDS` (Rust 2021 — 36 mots-clés incluant `Self`, `async`, `await`)
- `RUST_TYPES` (`i8`..`i128`, `String`, `Vec`, `Option`, `Result`, `Box`, etc.)
- `RUBY_KEYWORDS` (`BEGIN`, `END`, `defined?`, `__FILE__`, `__LINE__`, etc.)
- `PHP_KEYWORDS` (PHP 8.x — `abstract`, `enum`, `match`, `readonly`, etc.)
- `SWIFT_KEYWORDS` (Swift 5.x — `actor`, `async`, `await`, `Self`, etc.)
- `DART_KEYWORDS` (Dart 3.x — `extension`, `factory`, `mixin`, etc.)
- `GROOVY_KEYWORDS` (Java superset + `def`, `trait`, `it`, `Closure`, etc.)
- `SQL_KEYWORDS` (ANSI + SQLite + lowercase variants — 200+ entries)
- `SHELL_KEYWORDS` (`if`, `then`, `else`, `fi`, `case`, `esac`, `for`, `while`, `do`, `done`, etc.)
- `CSS_AT_RULES` (`@media`, `@import`, `@keyframes`, `@layer`, `@container`, etc.)
- `CSS_PROPERTIES` (`margin`, `padding`, `border`, `color`, `font`, `display`, `flex`, `grid`, `transition`, `animation`, `transform`, etc. — ~120 properties)
- `SMALI_KEYWORDS` (`.class`, `.super`, `.method`, `.field`, `.registers`, etc.)
- `SMALI_REGISTERS` (`p0`-`p9`, `v0`-`v15`)
- `TOML_KEYWORDS` (`true`, `false`, `inf`, `nan`)
- `PROPERTIES_KEYWORDS` (`true`, `false`)

**9 nouveaux `LexState` constants** (5..13) :
`CSS_COMMENT`, `CSS_STRING`, `BASH_HEREDOC`, `HTML_SCRIPT`, `HTML_STYLE`, `YAML_BLOCK_SCALAR`, `C_RAW_STRING`, `SHELL_SINGLE_QUOTE`, `PHP_HEREDOC`.

**7 nouveaux tokenizers spécialisés** (en bas de `SyntaxHighlighter.java`) :
- `styleCss(line, entryState)` — at-rules, properties, hex colors, numbers with units (`12px`, `1.5em`, `100%`), strings, block comments (cross-line), SCSS `$variables`, `@interpolations`.
- `styleShell(line, entryState)` — `#` comments, `$var`/`${var}` references, double-quoted strings (with `$var` interpolation), single-quoted literals (cross-line allowed), backticks command substitution, keywords + builtins.
- `styleYaml(line, entryState)` — `key:value` (key=PROPERTY), `#` comments, `---` document separator (KEYWORD), `&anchor`/`*alias`/`!tag` (ANNOTATION), quoted strings, numbers/booleans/null (NUMBER/CONSTANT), block scalars (`|` or `>`), flow indicators `[]{}`.
- `styleSql(line, entryState)` — `--` line comments, `/* */` block comments (cross-line), `'single-quoted strings'` (STRING), `"double-quoted identifiers"` (PROPERTY), keywords (KEYWORD), numbers, function calls.
- `styleProperties(line, entryState)` — `#`/`!` comments, `key=value` (key=PROPERTY, value=STRING), escape sequences (`\n`, `\t`, `\uXXXX` → ESCAPE).
- `styleToml(line, entryState)` — `#` comments, `[section]` headers (TYPE), `key=value` (key=PROPERTY), basic + literal strings, numbers, booleans (CONSTANT).
- `styleSmali(line, entryState)` — directives (`.class`, `.method`, etc. → KEYWORD), registers (`p0`-`p9`, `v0`-`v15` → VARIABLE), `#` comments, `"strings"` (STRING), type descriptors (`Ljava/lang/String;` → TYPE), hex literals (`0x1A` → NUMBER), opcodes (`invoke-*`, `move-*` → FUNC).

**`styleCLike()` extrait de `styleLine()`** : le body original inline dans `styleLine()` est maintenant dans une méthode privée `styleCLike(line, entryState, language)`. Le dispatcher `styleLine()` route vers les tokenizers spécialisés (CSS, Shell, YAML, SQL, Properties, TOML, Smali + les 9 existants) puis tombe sur `styleCLike()` pour les 13 langages C-like (Java, Kotlin, JS, TS, C, C++, Go, Rust, Ruby, PHP, Swift, Dart, Groovy).

**`getKeywords()` étendu** : ajoute cases pour `c`/`h`, `cpp`/`cc`/`hpp`/`cxx`, `go`, `rust`/`rs`, `ruby`/`rb`, `php`, `swift`, `dart`, `groovy`/`gradle`, `sql`, `shell`/`bash`/`sh`, `smali`, `toml`, `properties`.

**Class-level Javadoc réécrit** : documente la liste complète des 26 langages supportés (13 C-like + 13 specialized), explique l'optionalité de `:tm4e` pour le richer highlighting (scope inheritance, injections, multi-line constructs).

### v0.1.0.61-v2.45 — TextMate binary tokenizer + async restyle

Suite recommandée de v2.44 (le bandage perf) — trois axes qui s'attaquent aux causes racine du crash HTML + de la lenteur d'ouverture de fichier :

#### Track A — Switch à `tokenizeLine2` (forme binaire int[])

Le v2.43/v2.44 utilisait `grammar.tokenizeLine(line, prevState, timeLimit)` (forme objet). Cette forme alloue, par token, par ligne :
- un `IToken` (objet avec 4 champs)
- une `List<String>` de scopes (généralement 2–4 entrées)

Sur un fichier HTML de 500 lignes × ~50 tokens/ligne = 25 000 `IToken` + 25 000 `List<String>` ≈ ~250 000 allocations String wrapper — source directe du GC churn visible dans les logs crash v2.44 (3 jeunes GC en 4 s libérant 67 MB).

v2.45 bascule sur `grammar.tokenizeLine2(line, prevState, timeLimit)` (forme binaire). Le résultat est un `int[]` de longueur `2*N` où :
- `tokens[2*i]` = startIndex du token i
- `tokens[2*i+1]` = metadata encodé (languageId, tokenType, fontStyle, foreground, background, balancedBrackets — cf. `EncodedTokenAttributes`)

Plus aucune allocation par token — un seul `int[]` alloué par ligne.

Mirrors sora-editor's `TextMateAnalyzer.tokenizeLine` qui utilise exclusivement `tokenizeLine2`.

#### Track C — Consommation réelle de `darcula.json` via table précomputée `foregroundId→TokenType`

La forme binaire ne donne pas accès aux strings de scopes — seulement au metadata encodé. Pour récupérer le mapping scope→TokenType que v2.43 faisait via `mapScopesToTokenType(List<String>)`, v2.45 construit une table `Map<Integer colorId, TokenType>` au démarrage :

1. Après `registry.setTheme(themeSource)`, on récupère `colorMap = registry.getColorMap()` (une `List<String>` d'hex, indexée par id — l'id encodé dans le metadata).
2. On parse `darcula.json` directement. Pour chaque règle `{scope, settings.foreground}` :
   - On splitte `scope` par virgule (ou `JSONArray` si c'est un array).
   - On mappe chaque scope → TokenType via `mapSingleScope` (le helper v2.43, gardé intact).
   - On prend la première règle dont un scope mappe à non-PLAIN.
   - On normalise l'hex foreground → `#RRGGBB` majuscule.
   - On cherche l'id correspondant dans `colorMap`.
   - On stocke `(colorId, TokenType)` — first wins.

Au runtime, `mapMetadataToTokenType(metadata, foregroundToTokenType)` :
- `StandardTokenType.String` → STRING (basic 4-form mapping, zero lookup)
- `StandardTokenType.Comment` → COMMENT
- `StandardTokenType.RegEx` → REGEXP
- `StandardTokenType.Other` → lookup dans `foregroundToTokenType.get(foreground)` → TokenType ou PLAIN fallback

Le basic 4-form mapping prend le pas sur la lookup foreground (une String avec un foreground qui ressemblerait à KEYWORD reste STRING — la grammaire est la source de vérité pour le type sémantique, le thème pour la couleur).

Mirrors sora-editor's `theme.getColor(foreground)` path — le thème est la source de vérité.

#### Track B — `EditorSession.restyleAllAsync()` (async tokenization)

La cause racine de la lenteur d'ouverture de fichier : `EditorSession.restyleAll()` était synchrone sur l'UI thread (appelant de `setLanguage`). Sur un fichier de 500 lignes avec TextMate activé, 500 × ~5 ms = 2.5 s de blocage UI. Au-dessus de 800 lignes, le circuit breaker v2.44 désactive TextMate — mais sous ce seuil, l'utilisateur voyait quand même un freeze de 1–4 s à l'ouverture de chaque fichier.

v2.45 introduit `restyleAllAsync()` appelé depuis `setLanguage` quand TextMate est activé (doc ≤ 800 lignes) :

1. Snapshot `doc.getText()` + `doc.lineCount()` + `language` au démarrage.
2. Cancel any pending async restyle (`pendingRestyle.getAndSet(null).cancel(true)`).
3. Soumet une tâche à `restyleExecutor` (single-thread, daemon, priorité `NORM-1`).
4. Worker :
   - Boucle sur les lignes du snapshot, appelle `highlighter.styleLine` (qui délègue à TextMateTokenizerImpl — donc `tokenizeLine2` maintenant).
   - Vérifie `Thread.interrupted()` à chaque itération — abort si une nouvelle restyle est soumise.
   - Vérifie `restyleGeneration` (monotonic counter) — si différent, abort (stale result).
   - Vérifie `doc == docAtStart` — si faux (replaceRange/undo/redo entre temps), abort.
   - Swap atomique `styledLines = newStyled` (volatile field).
   - Reset `lineTextRevisions`.
   - Appelle `linesShiftListener.onLinesReset()` — l'`EditorView.cacheShiftListener` poste le travail à l'UI thread via `getHandler().post(...)` (mirrors `notifyDiagnosticsChanged`).
5. `EditorView.cacheShiftListener.onLinesReset()` met à jour `renderCache.clear()` + `invalidate()` (le `invalidate()` manquait dans le code v2.44 — il était appelé ailleurs, mais pour la path async il faut l'appeler explicitement).

L'UI thread n'est plus bloqué. Le user peut scroller/éditer immédiatement après ouverture de fichier. Pendant le gap async (~200 ms pour 200 lignes), le user voit les **stale styledLines** (Java colors sur un HTML file par ex.) — choix délibéré : garder les anciens tokens visibles pendant le gap plutôt que de clearer styledLines (qui casserait `spliceStyles` le chemin incrémental d'edit).

Tests v2.45 :
- `TextMateTokenizerImplTest` : 30 → 38 tests (+8) — `mapMetadataToTokenType` (4 standard types + Other avec/empty/unknown foreground + String precedence + multi-types sanity).
- `EditorSessionTest` : 20 → 24 tests (+4) — `setLanguage_kicksOffAsyncRestyle`, `asyncRestyle_populatesStyledLinesWithCorrectCount`, `asyncRestyle_doubleSetLanguage_cancelsFirstRestyle`, `largeDocument_usesSyncRestyle_populatesImmediately`.
- `TextMateEngineSmokeTest` (tm4e module) : 7 → 8 tests (+1) — `allGrammars_loadAndTokenizeSampleLine` (19 grammars non-Java testés end-to-end en object + binary form).
- Total : 716 tests verts (502 core + 8 tm4e + 206 app), +13 vs v2.44.

BUILD VALIDATION :
- `:core:compileDebugJavaWithJavac` OK (EditorSession + EditorView wiring modifiés).
- `:core:testDebugUnitTest` OK — 502 tests (498 + 4 nouveaux).
- `:tm4e:compileDebugJavaWithJavac` OK (inchangé).
- `:tm4e:testDebugUnitTest` OK — 8 tests (7 + 1 nouveau).
- `:app:compileDebugJavaWithJavac` OK (TextMateTokenizerImpl réécrit — passe en `internal` package pour `EncodedTokenAttributes` / `StandardTokenType`).
- `:app:testDebugUnitTest` OK — 206 tests (198 + 8 nouveaux).
- `:app:assembleDebug` OK — APK 30 MB (vs 30 MB v2.44, inchangé car le code source tm4e est déjà vendored ; la binary form réduit les runtime allocations, pas la taille du dex).
- aapt2 dump badging : `versionCode='61' versionName='0.1.0.61-v2.45-debug'`.
- Vérification APK : `EncodedTokenAttributes` présent dans classes5.dex, `TextMateTokenizerImpl` présent dans classes21.dex, 17 grammar files + darcula.json dans assets/textmate/.

Bugs historiques NON régressés : pool LRU ZipFile (v0.1.0.53), golden rules subpackage (lspjava 228), badges kinds v2.30, inlay weaving v2.31, sheet 2 entrées + bracket matching v2.32, folding/quick-fixes v2.33, popup v2.34, pendingTapDismiss/magnifier v2.35, NavMenu/sheet v2.36, GO TO Implementations/Super + Duplicate RPC method v2.37, hover Sora + signature help documentée + design popups CodeAssist + complétion corrigée v2.38, preview badge md/html + signature Up/Down + décompilateur foundation + tap-and-hold v2.39, câblage LSP du décompilateur + preview sheet WebView v2.40, vrai convertisseur Markdown→HTML v2.41 (MarkdownRendererTest 24/24 + AppEditorPreviewHostTest 10/10 inchangés), crash EditorPreviewSheet + CodeHighlighter pure-Java CSS pour Markdown v2.42 (EditorPreviewSheetTest 15/15 Robolectric + CodeHighlighterTest 38/38), TextMate integration FOR REAL via sora-editor vendored tm4e v2.43 (TextMateEngineSmokeTest 8/8 + TextMateTokenizerImplTest 38/38), TextMate perf & HTML crash fix v2.44 (time limit 2 s/ligne + MAX_LINE_LENGTH 5 000 + LRU state map 5 000 + circuit breaker MAX_LINES_FOR_TEXTMATE 800 + HTML fallback vers XML tokenizer).

Reste à faire (inventaire v2.45) :
- (1) **Tirer parti de l'async pour relever `MAX_LINES_FOR_TEXTMATE`** — actuellement 800 (circuit breaker v2.44). Avec l'async, on pourrait le passer à 5000 ou plus (le thread worker n'ANR pas). Mais le coût memoire du state map (`MAX_STATE_ENTRIES=5000`) pourrait être un facteur limitant. À tuner après retours utilisateurs.
- (2) **Magnifier pendant le drag-select** après long-press.
- (3) **Index ressources incrémental** (TTL 5 s).
- (4) **Hints Kotlin it/receiver** (vrai serveur Kotlin).
- (5) **Preview canvas XML layout** (path `drawPreview` en place mais `canPreview=false` pour .xml).
- (6) **`Theme.match(scope)` pour scopes spécifiques** — v2.45 utilise le `foreground` du metadata pour dériver le TokenType. Une v2.46 pourrait enrichir en mappant des scopes spécifiques (ex. `entity.name.function` → FUNC) via une lecture directe du scope stack — mais la forme binaire ne l'expose pas. Faute de bascule vers la forme objet pour les cas spéciaux, ou d'étendre le metadata avec un scope-id, c'est difficile.
- (7) **Brancher TextMate pour la preview WebView** — `CodeHighlighter` pure-Java v2.42 pourrait être remplacé par un wrapper qui appelle `TextMateTokenizerImpl` + génère du HTML avec les scopes.
- (8) **Profiling async restyle sur de gros fichiers** — vérifier que le thread worker n'a pas de starvation sous édit continu, et que le snapshot/drop pattern tient la charge.

### v0.1.0.60-v2.44 — TextMate perf & HTML crash fix

Diagnosis du crash HTML (log utilisateur : `setLanguageForExtension(html, ...) → Grammar text.html.basic contains the following injections → Background concurrent copying GC (×3) → signal 3 + tombstoned (ANR) → Late-enabling -Xcheck:jni (restart)`) :

1. **`EditorSession.restyleAll()` est synchrone sur le thread appelant** (UI thread pour `setText`/`setLanguage`) — ouvrir un fichier HTML de 500+ lignes tokenise les 500 lignes d'un coup sur l'UI thread. À ~10 ms/ligne en moyenne pour la grammar HTML (regex d'attribut HTML5 géante), 500 lignes = 5 s = ANR.
2. **Pas de time limit sur `tokenizeLine`** — joni peut spinner indéfiniment sur les regex pathologiques (notamment l'attribut HTML5 qui a une alternation de centaines de branches).
3. **Allocation lourde par token** — chaque `tokenizeLine` (forme objet) alloue `IToken[]` + `List<String> scopes` par token → GC pressure visible dans les logs (3 GC en 4 s libérant 67 MB).
4. **State map non bornée** — la `Map<Integer, IStateStack>` par langage grossit sans limite (fine pour <10k lignes, mais aucune borne).

Étude de sora-editor (`/tmp/sora-editor/language-textmate/` — la source d'origine du tm4e vendored en v2.43) :
- **`TextMateAnalyzer.tokenizeLine`** passe `Duration.ofSeconds(2)` à `grammar.tokenizeLine2(line, state, timeLimit)` — limite de 2 s par ligne.
- Sora utilise **`tokenizeLine2` (forme binaire)** — `int[]` au lieu de `IToken[]`, pas d'allocation de `IToken` ni de `List<String>` scopes. Metadata encodé en un int (languageId, tokenType, fontStyle, foreground, background).
- Sora tourne sur **`AsyncIncrementalAnalyzeManager.LooperThread`** — thread dédié, les spans sont envoyés au UI thread via `sendNewStyles()`. Pas de blocage UI.
- Sora utilise directement `theme.getColor(foreground)` comme couleur du Span — pas de mapping scope → TokenType → couleur. Le thème est la source de vérité.

Appliqué à CodeIDE (approche minimal-risk, sans refactoring de `EditorSession` en async — laissé pour v2.45) :

- **`TextMateTokenizerImpl.tokenize`** — passage de `Duration.ofSeconds(2)` à `grammar.tokenizeLine(line, prevState, timeLimit)`. Mirrors sora. Prévient les hangs de regex pathologiques (notamment attribut HTML5).
- **`TextMateTokenizerImpl.MAX_LINE_LENGTH = 5000`** — guard statique `isLineAdmissibleForTextMate(line)`. Les lignes plus longues que 5 000 caractères (HTML minifié, JS minifié, CSS minifié) skip TextMate et retombent sur le tokenizer built-in.
- **`TextMateTokenizerImpl.MAX_STATE_ENTRIES = 5000`** — la state map passe de `HashMap` à `LinkedHashMap` avec `removeEldestEntry` (LRU). Bounds memory growth sur très gros fichiers ou fichiers très édités.
- **`SyntaxHighlighter.setTextMateEnabled(boolean)` + `isTextMateEnabled()`** — circuit breaker global. `EditorSession.setLanguage` le positionne selon `doc.lineCount() <= MAX_LINES_FOR_TEXTMATE` (800).
- **`EditorSession.MAX_LINES_FOR_TEXTMATE = 800`** — au-dessus de ce seuil, `setLanguage` désactive TextMate avant `restyleAll` et retombe sur le tokenizer built-in. Threshold tuné pour 800 lignes × ~5 ms/ligne ≈ 4 s (sous le seuil ANR de 5 s).
- **`SyntaxHighlighter.styleLine`** — HTML retombe sur `styleXml` quand TextMate est désactivé ou indisponible (HTML est structurellement du XML — tags, attributs, comments, CDATA, entities). Le tokenizer XML built-in gère les longues lignes sans souci.

Tests :
- **`TextMateTokenizerImplTest`** (24 → 30 tests, +6) — `isLineAdmissibleForTextMate` (null, short line, at limit, over limit) + sanity bounds sur `MAX_LINE_LENGTH` et `MAX_STATE_ENTRIES`.
- **`SyntaxHighlighterTest`** (17 → 20 tests, +3) — HTML fallback vers XML tokenizer + toggle `textMateEnabled` (disabled bypasses delegation, enabled delegates).
- **`EditorSessionTest`** (17 → 20 tests, +3) — `setLanguage` small doc garde TextMate activé, large doc le désactive, switch large → small le ré-active.
- **`TextMateEngineSmokeTest`** (7 tests) — inchangés (valident le pipeline tm4e + joni end-to-end sur Java grammar, sans toucher aux perf guards).

Différés (v2.45+) :
- **Switch à `tokenizeLine2` (forme binaire)** — éliminerait l'allocation `IToken[]` + `List<String>` scopes par token. Mais perd l'info scope → TokenType → couleur ; faudrait wirer le thème `Theme.getColor(foreground)` directement (comme sora). Changement plus large.
- **Async tokenization** — `EditorSession.restyleAll` sur thread dédié, envoi différé des spans au UI thread. Changement architectural majeur (touch EditorSession + EditorView + LineRenderCache).
- **Consumption réelle de `darcula.json`** — `Theme.match(scope)` pour couleurs par scope (plus riche que le mapping generic scope → TokenType). Déjà chargé en mémoire mais non consommé.


### tm4e — TextMate integration FOR REAL via sora-editor vendored source (v0.1.0.59-v2.43)

Cette version réalise l'intégration TextMate **pour de vrai** — l'obstacle "TM4E bloqué offline" documenté en v2.42 était basé sur l'hypothèse qu'il fallait télécharger tm4e depuis Eclipse Nexus. L'utilisateur a fourni la solution alternative : **sora-editor a vendored tm4e en source** (`/tmp/sora-editor/language-textmate/` — 120 fichiers `org.eclipse.tm4e.*`). Il suffit de vendor ce source tree + 4 jars téléchargeables depuis Maven Central.

#### 1. NOUVEAU MODULE `:tm4e` (Gradle library, namespace `jo.codeide.tm4e`)

- **102 fichiers vendored** depuis `sora-editor/language-textmate/src/main/java/org/eclipse/tm4e/*` : `core/` (registry, grammar, oniguruma/impl/joni, parser, theme, rule, matcher, utils). Setup script idempotent `scripts/tm4e_setup.py`.
- **DROPPED** : 15 fichiers `languageconfiguration/*` (besoin de stubs sora CharPosition/Content/IntPair — features de brackets/comment dont CodeIDE a déjà ses propres implémentations) + 5 fichiers `NativeOnig*` (besoin de NDK C++ — non disponible offline ; tm4e a un backend alternatif joni pure Java qui marche out-of-the-box).
- **Patchs de compatibilité** :
  - `Oniguruma.java` réécrit pour forcer joni (pure Java) — supprime imports `NativeOnig*`, hardcoded `nativeAvailable=false`, `useJoni=true`. Méthodes `setUseNativeOniguruma`/`isUseNativeOniguruma` gardées comme no-ops pour préserver l'API publique.
  - `TMParserJSON.java` patché pour gson 2.10.1 (le cache n'a pas `Strictness` enum, ajouté en gson 2.11+). `setStrictness(Strictness.LENIENT)` → `setLenient()` avec `@SuppressWarnings("deprecation")`.
- **Stubs CodeIDE** :
  - `jo.codeide.tm4e.stubs.Logger` — wrapper pur-Java sur `java.util.logging.Logger` (marche en JUnit SANS Robolectric ET sur Android). Remplace `io.github.rosemoe.sora.util.Logger`.
  - `jo.codeide.tm4e.stubs.IntPair` — pack/unpack de 2 ints dans un long. Remplace `io.github.rosemoe.sora.util.IntPair`.
- **`MatcherUtils` vendored** (53 LOC, package renommé `jo.codeide.tm4e.utils`) depuis sora — utilisé par tm4e `RegExpSource`.

#### 2. Dépendances (4 jars en `tm4e/libs/` — vendored, pas de résolution Maven au build)

Téléchargés depuis Maven Central (test réseau : `curl https://repo1.maven.org/maven2/` → 200 OK) :

| Jar | Version | Taille | Rôle |
|-----|---------|--------|------|
| `joni` | 2.2.7 | 228 KB | Moteur regex Oniguruma pure Java (alternative à native libonig) |
| `jcodings` | 1.0.64 | 1.8 MB | Encodings support pour joni |
| `snakeyaml-engine` | 3.0.1 | 293 KB | Parser YAML pour .tmLanguage au format YAML plist |
| `org.eclipse.jdt.annotation` | 2.4.100 | 33 KB | Annotations `@NonNull` / `@Nullable` utilisées par tm4e core |

Dépendance standard : `com.google.code.gson:gson:2.10.1` (déjà en cache, requis par `TMParserJSON`).

#### 3. ASSETS — 17 language packs / 20 grammars + darcula theme + registry

Bundle script `scripts/tm4e_bundle_assets.py` :

- **8 essentiels** : `java`, `kotlin`, `xml`, `json`, `groovy` (build.gradle), `toml` (libs.versions.toml), `properties` (gradle.properties), `markdown`
- **9 utiles** : `html`, `css`, `javascript` (JS + JSX), `typescript` (TS + TSX), `shellscript`, `python`, `c`, `cpp`, `yaml`
- **1 spécial** : `smali` (pour `DecompiledSource` v2.37 — visualisation bytecode décompilé)
- **17 droppés** (économise ~500 KB) : `asm`, `bat`, `cmake`, `coq`, `dart`, `diff`, `go`, `htmx`, `ignore`, `ini`, `latex`, `less`, `lisp`, `log`, `lua`, `nim`, `pascal`, `php`, `powershell`, `ruby`, `rust`, `scss`, `sql`, `swift`, `text`, `zig`
- **`Kotlin.tmLanguage`** (XML plist) converti en `.json` via Python `plistlib` — uniformisation du format (tm4e supporterait les deux, mais JSON est plus standard et debuggable).
- **`darcula.json`** theme bundlé (background `#1C1B20`, scope → couleur) — non consommé pour v1 (l'adapter mappe scopes → TokenType, EditorTheme fournit les couleurs). v2.44 pourrait consommer `Theme.match(scope)` pour récupérer les couleurs specifics par scope.
- **`languages.json`** registry CodeIDE-specific (18 entries mapping extensions → grammar path + scope name).

Total assets : 2.4 MB.

#### 4. ADAPTER `TextMateTokenizerImpl` (jo.codeide.ui.editor, ~310 LOC)

- **Interface** `TextMateTokenizer` (jo.codeeditor.highlight, `:core` — pur Java) : `isAvailable(language)` + `tokenize(line, entryState, language) → StyledLine`. Architecture : interface dans `:core` (pur Java), impl dans `:app` (a besoin d'Android Context pour AssetManager).
- **`SyntaxHighlighter.styleLine`** modifié : ajoute un hook au top — `if textMateTokenizer != null && isAvailable(language) → delegate, sinon fallback switch-case built-in`. Méthode statique `setTextMateTokenizer(TextMateTokenizer)`. Les 1262 LOC existantes du switch-case restent intactes — TextMate est strictement opt-in par langue.
- **`TextMateTokenizerImpl`** : init lazy + synchronized + idempotent. Charge `languages.json`, pour chaque entry ouvre le grammar depuis assets via `IGrammarSource.fromInputStream(stream, fileName, UTF_8)`, appelle `registry.addGrammar(source)`. Charge `darcula.json` via `IThemeSource.fromInputStream` + `registry.setTheme` (non-fatal si échec).
- **State management** : tm4e utilise `IStateStack` (opaque object), CodeIDE utilise `int`. Bridge : `Map<String, Map<Integer, IStateStack>>` per language. `exitState = hash stable de (entryState, lineContent)` — garanti différent pour inputs différents, identique pour mêmes inputs (l'éditeur arrête de re-tokenizer downstream une fois stable). Garde `0` comme sentinelle "fresh state".
- **Scope → TokenType mapping** : 18 prefixes matchés (`keyword.*`, `storage.*`, `string.*`, `comment.*`, `constant.numeric.*`, `constant.language.*`, `constant.character.escape.*`, `string.regexp.*`, `entity.name.function.*`, `entity.name.class.*`, `entity.name.tag.*`, `entity.name.attribute.*` + `entity.other.attribute-name.*` avec tiret, `variable.language.*` → KEYWORD, `variable.parameter.*` → PROPERTY, `variable.*` → VARIABLE, `support.function.*` → FUNC, `support.type/class.*` → TYPE, `punctuation.*` → PUNCT, `annotation.*` → ANNOTATION, `meta.*` → PLAIN).
- **Defensive** : `tokenizeLine` peut throw sur des regex exotiques Oniguruma → `catch RuntimeException`, fallback à single PLAIN span (jamais crash l'éditeur). Token indices clampés (tm4e peut émettre `end > line.length()` sur le dernier token).
- **`ProjectActivity.setupEditor`** étendu : `try { SyntaxHighlighter.setTextMateTokenizer(new TextMateTokenizerImpl(this)); } catch RuntimeException → Log.w non-fatal`. L'init lazy du tokenizer ne throw pas au setup, seulement à l'usage.

#### 5. TESTS (31 nouveaux — 0 échec)

- **`TextMateEngineSmokeTest`** (`:tm4e`, 7 tests JUnit pur SANS Mockito SANS Robolectric). Charge le vrai `java.tmLanguage.json` depuis test resources, exécute le pipeline tm4e + joni end-to-end sur des samples Java réels. Valide : (1) grammar loads with scope `source.java`, (2) class declaration → keyword/storage token, (3) `//comment` → `comment.*` scope, (4) `"string"` → `string.*` scope, (5) `int x = 42` → `constant.numeric.*` scope, (6) multi-line `/* ... */` block comment — state continuity via `IStateStack.getRuleStack()` à travers 3 lignes, (7) token indices valides `[0, line.length()]`.
- **`TextMateTokenizerImplTest`** (`:app`, 24 tests JUnit pur SANS Mockito SANS Robolectric). Tests statiques (pas besoin Android Context) : `mapScopesToTokenType` pour 18 cas (null, empty, keyword, storage, string, regexp, comment, numeric, language, function, class, tag, attribute-name avec tiret, variable.language `this`, variable.parameter, variable, punctuation, meta, source-only, unknown, most-specific-wins) + `computeExitState` (stabilité, divergence, garde non-zéro). `mapScopesToTokenType` et `computeExitState` rendus package-private pour test direct.

#### 6. Build validation

- `:tm4e:compileDebugJavaWithJavac` OK (102 fichiers + 4 jars + stubs + MatcherUtils).
- `:tm4e:testDebugUnitTest` OK (7/7 TextMateEngineSmokeTest — tm4e + joni fonctionnent end-to-end sur Java grammar).
- `:app:compileDebugJavaWithJavac` OK (TextMateTokenizerImpl + ProjectActivity wiring).
- `:app:testDebugUnitTest` OK (192 tests — 168 existants + 24 nouveaux TextMateTokenizerImplTest, 0 échec).
- `:app:assembleDebug` OK — APK 33 MB (vs 28 MB v2.42 = +5 MB pour tm4e + 4 jars + 17 grammars + darcula).
- Vérification APK : 36 dex files contiennent bien les classes tm4e (classes11, 13, 18, 34, 35, 36) + les 4 jars via les `Transcoder_*_ByteArray.bin` tables (jcodings) + `joni/StackEntry` weak refs + stubs `Logger`/`IntPair` + `MatcherUtils` dans `classes8.dex` + 20 grammar files + `languages.json` + `darcula.json` dans `assets/textmate/`.

### app / ui — coloration syntaxique CSS des fenced code blocks + fix crash EditorPreviewSheet (v0.1.0.59-v2.42)

Cette version (1) corrige le crash user-reported `IllegalStateException: child already has a parent` sur `EditorPreviewSheet.show:102`, (2) ajoute un tokenizer pure-Java `CodeHighlighter` qui colorise les fenced code blocks du preview sheet Markdown via CSS thémée Light+Dark, (3) documente honnêtement la tentative d'intégration TextMate/TM4E (bloquée offline). Aucun changement aux modules `:cel-lsp`, `:lspjava`, `:core`, `:lsp-api` — le `SyntaxHighlighter` interne de `:core` reste inchangé.

#### 1. Fix crash `EditorPreviewSheet.show:102`

- **Cause** : `AppEditorPreviewHost.onCreatePreviewView` cache une WebView (pattern légitime). Au second open, la WebView retient encore le bodyFrame du sheet disparu → `bodyFrame.addView(bodyView)` throw `IllegalStateException`.
- **Fix** : détachement défensif dans `EditorPreviewSheet.show()` avant `addView` — `if (bodyView.getParent() instanceof ViewGroup) ((ViewGroup) bodyView.getParent()).removeView(bodyView);`. Le host n'a pas à savoir gérer le parent.
- **Test** : nouveau `EditorPreviewSheetTest.openPreviewSheet_cachedBodyViewAcrossOpen_reusesWithoutCrash` (Robolectric, +1 test → ui 15).

#### 2. Tentative TM4E bloquée — documentation honnête

Le user demandait de lancer le prompt `textmate-implementation-prompt.md` pour remplacer le `SyntaxHighlighter` hardcoded par un moteur générique basé sur Eclipse TM4E. **Audit offline révèle 4 bloqueurs** :
1. TM4E n'est PAS sur Maven Central. L'artifact réel est `org.eclipse:org.eclipse.tm4e.core` sur `repo.eclipse.org/content/repositories/tm4e-snapshots/` — SNAPSHOTS uniquement (dernière `0.17.3-SNAPSHOT`). La coord `org.eclipse.tm4e:org.eclipse.tm4e.core:0.6.1` du prompt n'existe pas.
2. TM4E 0.17.3-SNAPSHOT tire 6 deps transitives (assertj-core 3.27.6, byte-buddy 1.18.1, gson 2.13.2, commons-io 2.21.0, commons-logging 1.2, snakeyaml-engine 2.10). assertj et snakeyaml-engine absents du cache offline ; byte-buddy/gson/commons-io en versions différentes.
3. Aucun fichier `.tmLanguage.json` dans le projet — `app/src/main/assets/textmate/` n'existe pas.
4. Aucun `languages.json` manifest — à créer from scratch.

Décision : implémenter une alternative pure-Java qui fonctionne offline (volet 3). Le prompt TM4E reste backlog pour environment networked.

#### 3. `CodeHighlighter` — pure-Java CSS tokenizer

Nouveau fichier `app/src/main/java/jo/codeide/ui/editor/CodeHighlighter.java` (~1100 LOC). Tokenizer regex/state-machine SANS dépendance externe.

- **API publique** : `CodeHighlighter.highlight(language, code) -> String` (HTML avec `<span class="token X">...</span>`).
- **30+ langages** : Java, Kotlin, JS, TS, Go, Rust, C, C++, Swift, Dart, PHP, Groovy (famille C-like) ; Python (triple-quoted) ; XML/HTML ; JSON ; CSS ; YAML ; SQL ; Shell/Bash ; Properties.
- **Classes alignées sur Prism** (`.token.keyword`, `.token.string`, etc.) — swap-in futur Prism/highlight.js sans toucher au HTML.
- **Sécurité** : `escapeHtml` échappe `<`, `>`, `&` (PAS `"` ni `'` — la sortie va dans du contenu d'élément `<pre><code>`, pas dans un attribut).
- **Tests** : 38 `CodeHighlighterTest` (JUnit pur SANS Mockito SANS Robolectric) — couvre tous les langages + fallback + sécurité + intégration MarkdownRenderer.

#### 4. Intégration `MarkdownRenderer` + CSS thémée

- `MarkdownRenderer.renderBody` post-process le HTML commonmark : regex matche `<pre><code class="language-XXX">escaped-code</code></pre>`, dé-échappe le contenu, le re-tokenize via `CodeHighlighter.highlight(lang, rawCode)`, remplace le contenu. Le wrapper `<pre><code class="language-XXX">` est préservé.
- **Inline code** (single backticks) sciemment épargné (pas de `<pre>` prefix → regex ne matche pas).
- **CSS thémée** : 18 classes `.token.X` en light (palette Material — `keyword #A1882F`, `string #0B6E3D`, `number #B71C1C`, `comment #757575`, `function #1565C0`, etc.) + 18 classes en dark (palette ajustée pour `#2A2B2E` — `keyword #FFB300`, `string #7CB342`, etc. via `@media (prefers-color-scheme: dark)`).

#### 5. Suites complètes — 0 échec

- **:app 142 tests** (94 existants + 24 MarkdownRendererTest v2.41 + 24 nouveaux CodeHighlighterTest v2.42) ; **:ui 15 tests** (14 + 1 nouveau crash fix test) ; `assembleDebug` OK (APK 28 Mo).
- Tous les tests historiques NON RÉGRESSÉS.

#### Découvertes clés

- **TM4E snapshots-only** sur `repo.eclipse.org/content/repositories/tm4e-snapshots/` — la coord réelle est `org.eclipse:org.eclipse.tm4e.core:0.17.3-SNAPSHOT` (group `org.eclipse`, PAS `org.eclipse.tm4e`). Le prompt avait l'ancienne coord `0.6.1` qui n'existe pas.
- **TM4E tire 6 deps transitives** dont assertj-core et snakeyaml-engine en scope compile (anormal pour une lib runtime — probable bug packaging TM4E).
- **`escapeHtml` pour contenu d'élément** n'échappe que `< > &` — `"` et `'` sont valides tels quels dans `<pre><code>`. Les échapper casse les assertions de strings lisibles.
- **Détachement défensif `ViewGroup.addView`** : pattern `if (child.getParent() instanceof ViewGroup) ((ViewGroup) child.getParent()).removeView(child);` avant `addView` — le host peut cacher légitimement un View sans gérer le parent.
- **Classes CSS alignées sur Prism** pour swap-in futur sans casser le HTML ni le host. Le `class="language-XXX"` produit par commonmark est aussi compatible Prism.
- **Inline code vs fenced code** : la regex `highlightCodeBlocks` ne matche QUE `<pre><code class="language-X">...</code></pre>` (fenced blocks). Inline code sciemment épargné.

#### Reste à faire

- **TM4E complet (Option A du prompt)** — bloqué offline : (1) ajouter `repo.eclipse.org/content/repositories/tm4e-snapshots/` à `settings.gradle.kts`, (2) résoudre assertj + snakeyaml-engine depuis le réseau, (3) télécharger ~50 grammars `.tmLanguage.json` depuis vscode-grammars, (4) créer le `languages.json` manifest. Une fois networked : le scaffolding (TextMateGrammarLoader, TextMateTokenizer, TextMateAnalyzer, TextMateLanguage) peut suivre les étapes 3-7 du prompt. `CodeHighlighter` reste un fallback viable.
- Magnifier pendant le drag-select après long-press ; index ressources incrémental (TTL 5 s) ; hints Kotlin `it`/receiver (vrai serveur Kotlin) ; preview canvas XML layout (path `drawPreview` en place mais `canPreview=false` pour `.xml`).

### app — vrai convertisseur Markdown→HTML pour le preview sheet (v0.1.0.59-v2.41)

Cette version remplace le wrap `<pre>` de v2.40 (escapeHtml brut) par un **vrai convertisseur CommonMark** dans `AppEditorPreviewHost`. Le contrat `EditorPreviewHost` (hook `onCreatePreviewView`, `canPreview`, etc.) est strictement inchangé — seul le renderer interne a été substitué. Aucune modification aux modules `:code-editor`, `:cel-lsp`, `:lspjava`, `:ui`, `:core`, `:lsp-api`.

#### 1. Nouveau helper `MarkdownRenderer` (`:app/jo.codeide.ui.editor`)

- **Fichier** : `app/src/main/java/jo/codeide/ui/editor/MarkdownRenderer.java` — utilitaire statique pure-Java.
- **Dépendance** : `com.atlassian.commonmark:commonmark:0.13.0` (pure Java) + `com.atlassian.commonmark:commonmark-ext-gfm-strikethrough:0.13.0` — ajoutées à `app/build.gradle.kts`. Tables et autolink non embarqués (assets absents du cache Gradle hors-ligne, non requis).
- **API publique** : `renderToHtmlDocument(markdown)` → document HTML5 complet (shell + CSS inline + `<div class="md-body">` + fragment commonmark).
- **API package-private** : `renderBody(markdown)` → fragment seul — utilisé par les tests unitaires pour valider le rendu sans la shell.
- **Sécurité** : `HtmlRenderer.escapeHtml(true)` — un `<script>` dans un `.md` devient du texte échappé (pas interprété par la WebView). Aligné avec `setJavaScriptEnabled(false)` + `setAllowFileAccess(false)` + `setAllowContentAccess(false)` déjà en place côté host.
- **Thème CSS inline** : typography mobile, accents Material, code blocks thémés, dark mode via `@media (prefers-color-scheme: dark)`.
- **Séparation helper/host** : choix d'architecture — le helper est 100% testable en JUnit pur SANS Mockito SANS Robolectric.

#### 2. Refactor `AppEditorPreviewHost.renderToHtml` (`:app`)

- La signature privée statique est **inchangée** depuis v2.40 — parité d'interface.
- Body refactoré :
  - `.html`/`.htm` : texte brut inchangé (fidélité maximale).
  - `.md`/`.markdown` : délègue à `MarkdownRenderer.renderToHtmlDocument(text)` à la place du wrap `<pre>` + `escapeHtml` de v2.40. L'ancien helper `escapeHtml` est supprimé (commonmark échappe lui-même).

#### 3. Tests — 24 nouveaux + 10 inchangés (`:app`)

- **`MarkdownRendererTest`** (24 tests, JUnit pur) : null/empty, structure de document, viewport meta mobile, dark mode media query, titres h1-h6, gras `<strong>`, italique `<em>`, strikethrough `<del>`, code inline `<code>`, fenced code block `<pre><code class="language-XXX">`, `language-kotlin` (verrou du class attribute), listes ul/ol, nested lists, blockquotes, hr, liens avec `href="..."`, échappement HTML (sécurité), paragraphe unique et multiples, ordre cohérent, fragment `renderBody` sans shell.
- **`AppEditorPreviewHostTest`** (10 tests, inchangés) : `canPreview` inchangé — la refactor ne touche pas au path contractuel.
- **Bug rencontré** : `render_fencedCodeBlock_yieldsPreCode` échouait en 1re itération car commonmark ajoute `class="language-XXX"` à `<code>` → la balise ouvrante est `<code class="...">`. Fix : assertion cherche `<code` (sans `>`), + test verrou `language-kotlin`.

#### 4. Suites complètes — 0 échec

- **:app 118 tests** (94 existants + 24 nouveaux MarkdownRendererTest) ; autres modules inchangés ; `assembleDebug` OK (APK 29 Mo).
- Tous les tests historiques NON RÉGRESSÉS.

#### Découvertes clés

- **commonmark-java 0.13.0** utilise encore le group `com.atlassian.commonmark` (la migration vers `org.commonmark` est postérieure à la 0.14). Le package Java est déjà `org.commonmark.*` — l'API est stable entre 0.13 et les versions plus récentes.
- **`HtmlRenderer.escapeHtml(true)`** est le mode SÉCURITÉ PAR DÉFAUT — empêche tout `<script>` dans un `.md` de devenir interprétable par la WebView.
- **`class="language-XXX"` sur les fenced code blocks** est ajouté automatiquement par commonmark — utile pour brancher un futur syntax highlighter CSS (highlight.js ou Prism) sans toucher au host.
- **`prefers-color-scheme: dark`** dans un `@media` query est respecté par Android WebView 76+ (Chromium) — automatique quand l'app suit le système.

#### Reste à faire

- Magnifier pendant le drag-select après long-press ; index ressources incrémental (TTL 5 s) ; hints Kotlin `it`/receiver (vrai serveur Kotlin) ; preview canvas XML layout (le path `drawPreview` canvas est en place mais non implémenté côté app — `canPreview` retourne `false` pour `.xml`) ; syntax highlighter CSS optionnel pour fenced code blocks (brancher highlight.js ou Prism via `assets/` — le `class="language-XXX"` est déjà en place).

### ui / cel-lsp / lspjava / app — câblage LSP du décompilateur + preview sheet WebView pour .md/.html (v0.1.0.59-v2.40)

Cette version CÂBLE DE BOUT EN BOUT la fonctionnalité « GO TO Definition sur cible binaire » dont la foundation API avait été posée en v2.39. Le fallback binaire est désormais RÉELLEMENT utilisable : GO TO sur `extends Thread` ou sur `HashMap.put(...)` ouvre un buffer read-only avec la source JDK décompilée. Le preview sheet v2.39 est enfin branché côté app via une WebView pour `.md`/`.html`.

#### 1. Serveur `:lspjava` — fallback binaire Location `jdt://decompiled/`

- `JavaTextDocumentService.definition` / `typeDefinition` : après le chemin JDT-first standard (intra-fichier), un fallback binaire `binaryDefinitionLocation(filePath, source, offset, targetType)` synthétise une `Location` à URI `jdt://decompiled/<fqcn-path>.java` (plage vide 0:0-0:0) — déclenché si `fqcnAt(...)` retourne un FQN non-nul ET `isBinaryType(fqcn)` retourne `true` ;
- `JdtCompilerEngine.fqcnAt(filePath, source, offset, targetType)` — nouvelle méthode **publique** qui résout le FQN du binding au caret (`targetType=false` : classe déclarante ; `targetType=true` : type du symbole) ;
- helpers privés `declaringTypeBindingOf(Binding)` + `stripGenericsFromFqn(TypeBinding)` — strippe le suffixe `<K,V>` de `readableName()` (sinon `isBinaryType("java.util.HashMap<K,V>")` retournerait faux car l'index ne connaît que le FQN nu) ;
- `ServerCapabilitiesProvider` — la capability `experimental` gagne la clé `decompiledSourceProvider=true` à côté de `superDefinitionProvider` (pattern jdtls) ;
- nouveaux DTOs `jo.lspjava.api.DecompiledSourceParams` (champ `fqcn`) + `DecompiledSourceResult` (champ `source`) ;
- `NavTextDocumentService` étendu : `@JsonRequest("textDocument/decompiledSource")` mirror du `superDefinition` (pattern v2.37) ;
- `JavaTextDocumentService.decompiledSource(DecompiledSourceParams)` — implémente le handler (appelle `environment.jdt().decompiledSource(fqcn)` — source attachée depuis src.zip du JDK / -sources.jar / caches Gradle ; sinon stub décompilé depuis ClassFileReader) ;
- mise à jour du test existant `TypeDefinitionFacadeTest.typeDeclaration_binaryTypeYieldsNoTarget` — asserte désormais qu'un caret sur `String s` renvoie une `Location` à URI `jdt://decompiled/java/lang/String.java` (verrou du nouveau câblage).

#### 2. Client `:cel-lsp` — miroir `textDocument/decompiledSource`

- `CodeIdeLanguageServer` étendu : nouvelle méthode **top-level** `@JsonRequest("textDocument/decompiledSource") CompletableFuture<DecompiledSourceResult> decompiledSource(DecompiledSourceParams params)` + deux DTOs miroirs (champs publics — désérialisation Gson un à un). Les DTOs vivent AU NIVEAU SUPÉRIEUR de l'interface (PAS dans `getTextDocumentService()`) — même précaution que pour `superDefinition` (leçon P0 v2.37 — Duplicate RPC method) ;
- `LspProject.decompiledSource(String ext, String fqcn)` — façade publique qui appelle `getWrapper(ext).getExtendedServer().decompiledSource(params).get(5, TimeUnit.SECONDS)` et retourne le champ `source`, ou `null` en cas d'échec (serveur non démarré, FQCN introuvable, timeout).

#### 3. App `:app` — interception URI + buffer read-only + preview host

- `ProjectActivity.openFileAt` : intercepte le schéma `jdt://decompiled/` EN PREMIER (avant le traitement `file://`) et délègue à `openDecompiledBuffer(jdtUri, offset)` ;
- `ProjectActivity.openDecompiledBuffer` — nouvelle helper : (1) parse le FQN depuis l'URI ; (2) sur background thread, appelle `lspProject.decompiledSource("java", fqcn)` — si null, Snackbar ; (3) materialise la source dans un fichier scratch `getCacheDir()/decompiled/<fqcn-path>.java` (VirtualFile ne peut pas wrapper une URI jdt:// directement) ; (4) marque le prochain onglet via `pendingDecompiledFile` puis `onFileOpen(target)` ;
- `ProjectActivity.loadFile` étendu : applique `editor.getSession().setReadOnly(true)` pour les buffers décompilés (pattern ConsoleLogView — l'API `setEditable` est un no-op), skip la `connectJavaLsp` (sinon le LSP publierait des erreurs sur le stub), appelle `editor.setFileName(currentFile.getName())` (active les badges preview — sans cet appel, `setPreviewHost` reste inerte) ;
- `ProjectActivity.setupEditor` étendu : `editor.setPreviewHost(new AppEditorPreviewHost(this))` — branche l'host app-side qui fournit la WebView pour `.md`/`.html` ;
- **nouveau fichier** `AppEditorPreviewHost.java` (`:app`) — implémentation app-side de `EditorPreviewHost` :
  - `canPreview` retourne `true` pour `.md`/`.markdown`/`.html`/`.htm` (case-insensitive), `false` pour tout le reste (le path canvas XML layout preview est intentionnellement déféré en v2.40) ;
  - `onCreatePreviewView` : retourne une WebView cachée avec `setJavaScriptEnabled(false)`, `setAllowFileAccess(false)`, `setAllowContentAccess(false)` (défense : pas de scripts, pas d'accès fichier) ;
  - `onPreviewContentChanged` : recharge la WebView via `loadDataWithBaseURL(null, html, "text/html", "utf-8", null)` ;
  - `renderToHtml` : v2.40 minimaliste — HTML passé brut (fidélité maximale) ; Markdown wrap dans `<pre>` avec échappement HTML + styles inline (font sans-serif, padding 14 px, dark mode via `prefers-color-scheme`). Un vrai convertisseur MD→HTML (titres, listes, gras, inline code, fences colorées) peut être branché plus tard sans toucher au host.

#### 4. Tests

- **+7 tests `BinaryDefinitionFallbackTest`** (lspjava) — `fqcnAt_methodCallOnJdkType_returnsDeclaringClassFqn`, `_variableOfTypeJdk_returnsTypeFqn`, `_superclassReference_returnsTypeFqn`, `_sourceType_isNotBinary`, `_caretInWhitespace_returnsNull`, `_emptySource_returnsNull`, `_baseType_returnsNull`. Tests activés par `@EnabledIf` quand un JDK avec `lib/src.zip` est détecté ;
- **+2 tests `DecompiledSourceRoundTripTest`** (cel-lsp) — `customDecompiledSourceMethod_roundTripsThroughJsonRpc` (launcher sans Duplicate RPC method, capability `experimental.decompiledSourceProvider` transportée, appel typé via `ext.decompiledSource(params)`, réponse désérialisée en `DecompiledSourceResult`), `lspProjectFacade_returnsSourceFromServer` (façade `LspProject.decompiledSource("java", fqcn)` retourne la source après connect) ;
- **+10 tests `AppEditorPreviewHostTest`** (app) — `canPreview` pour `.md`/`.markdown`/`.html`/`.htm`/`.xml`/`.java`/`.txt`/null/vide/no-extension/double-extension. Mockito pour le Context (pas de Robolectric dans `:app` — la WebView elle-même est déjà couverte par Robolectric dans `:code-editor` via `EditorPreviewSheetTest`) ;
- mise à jour du test existant `TypeDefinitionFacadeTest.typeDeclaration_binaryTypeYieldsNoTarget` — asserte désormais qu'un caret sur `String s` renvoie une `Location` à URI `jdt://decompiled/java/lang/String.java` avec plage vide (0:0-0:0) ;
- suites complètes VERTES : lspjava 228 (1 skip environnemental), cel-lsp 7, ui 129, core 485, lsp-api 22, app 84+10 = ~950 exécutions 0 échec. `assembleDebug` OK (APK 29 Mo).

### ui / cel-lsp / lspjava / app — preview badge markdown/html + signature help Up/Down + décompilateur binaire + hover tactile (v0.1.0.59-v2.39)

Quatre fonctionnalités livrées dans cette version : la première est nouvelle (preview badge + popup sheet), les trois autres clôturent l'inventaire « reste à faire » de v2.38.

#### 1. Preview badge Markdown/HTML + popup sheet (dans code-editor, pas dans app)

- nouveaux modes `PreviewMode.SHEET_SPLIT` et `SHEET_FULL` à l'énuméré existant (`NONE`/`SPLIT`/`FULL`) — la méthode `isSheet()` discrimine « popup overlay » vs « inline » ;
- `EditorView.openPreview(boolean full)` — point d'entrée public : pour `.md`/`.html` ouvre `SHEET_*` (l'éditeur garde sa largeur de texte pleine, le sheet flotte par-dessus), pour `.xml` ouvre l'inline `SPLIT`/`FULL` (rendu canvas classique) ;
- nouvelle classe `EditorPreviewSheet` (`code-editor/editor/ui`) — feuille popup dockée à droite ou plein écran, chrome : header glass avec nom du fichier + boutons split / full / close X (glyphs dessinés en code — pas de drawable resource à tirer), divider, body `FrameLayout` qui héberge soit le View fourni par le host (`onCreatePreviewView`) soit un `CanvasBodyView` qui délègue `onDraw` à `host.drawPreview(canvas, 0, 0)` ;
- `EditorPreviewHost` étendu : nouvelle méthode `default View onCreatePreviewView(Context, EditorView, PreviewMode)` (retourne `null` par défaut → fallback canvas). Les hôtes implémentent typiquement une WebView pour Markdown/HTML — l'éditeur ne sait RIEN de la nature du View ;
- `EditorView.closePreviewSheet()` / `getPreviewSheet()` — API publique de fermeture/inspection programmatique ;
- `onDetachedFromWindow` referme le sheet (pas de popup qui pointe vers un editor détaché) ;
- hit-test inchangé — l'input handler dispatche via `view.openPreview(...)` plutôt que `setPreviewMode(...)` directement, ce qui permet à l'extension de fichier de choisir le bon mode.

#### 2. Signature help — navigation entre surcharges au clavier (Up/Down)

- `SignatureHelpController` gagne `userOverrideActiveSignature` + `getEffectiveActiveSignature()` + `cycleActiveSignature(int direction)` + `setUserActiveSignature(int idx)` — l'override **persiste** à travers les `resolve()` dans la même call (l'utilisateur peut continuer à taper des args sans que le popup ne « saute » à la signature préférée du serveur) et **se réinitialise** quand le caret quitte la call courante ou entre dans une nouvelle call ;
- `EditorView.cycleSignatureHelp(int direction)` + `getEffectiveActiveSignature()` — façade publique qui délègue au controller et `invalidate()` le popup ;
- `EditorKeyHandler` — Up/Down consommés quand `signatureHelpVisible && signatures.size() > 1` (sinon Up/Down tombent sur le caret move — l'utilisateur peut toujours naviguer dans les args) ;
- `EditorRenderer.drawSignatureHelpPopup` lit `view.getEffectiveActiveSignature()` au lieu de `signatureHelpData.activeSignature` — le highlight de la rangée active suit l'override, pas le serveur.

#### 3. Cibles binaires des GO TO — décompilateur (foundation posée)

- `JdtBinarySourceNames.readSourceAttachment(String fqn)` — nouvelle méthode package-private qui retourne le `.java` source attaché d'un FQN (src.zip du JDK → `-sources.jar` du classpath → caches Gradle). Réutilise le mécanisme existant `findSourceEntryPath`/`readEntryOnce` (zéro fd persistant, cache LRU 96 entrées — invariant v0.1.0.53) ;
- `JdtCompilerEngine.decompiledSource(String fqcn)` — nouvelle méthode **publique** : (1) essaie `readSourceAttachment` (fidelity max — paramètres nommés, javadoc préservée) ; (2) sinon, lit `binaryType(fqcn)` (un `ClassFileReader`) et génère un stub décompilé via `ClassFileStubBuilder` ;
- nouvelle classe `ClassFileStubBuilder` (`jo.lspjava.jdt`) — squelette Java synthétique à partir d'un `ClassFileReader` binaire : en-tête `// Decompiled from <fqcn>.class`, déclaration `package` + `class/interface/enum/@interface` avec superclasse + interfaces, champs (modificateurs + type + nom, skip synthétiques), méthodes (modificateurs + type de retour + nom + params `p0, p1, ...` + clause `throws` + corps `throw new RuntimeException("Decompiled stub")`). Skip `<clinit>`, méthodes synthetic/bridge. Parseur de descripteurs JVM complet (primitives, tableaux, objets `Lfqcn;`, variables de type `T...;` → `Object`, wildcards). Pas de recompilation fidèle (CFR/Procyon sur-dimensionnés pour un besoin de navigation) ;
- `JdtCompilerEngine.isBinaryType(String fqcn)` — heuristique en deux étapes : (1) `binaryType()` (index classpath projet) ; (2) fallback `readSourceAttachment()` (catch JDK classes sans classpath mais avec src.zip attaché) ;
- **limitation assumée** : la couche LSP dispatch (`JavaTextDocumentService.definition`) ne renvoie PAS ENCORE de `Location` à URI synthétique `jdt://decompiled/<fqcn>.java` — l'éditeur n'a pas encore de fournisseur de document virtuel pour ce schéma. C'est le chantier v2.40 : wirer le fallback dans `definition`/`typeDefinition` + coter `:cel-lsp` d'un client mirror `textDocument/decompiledSource(fqcn)` + intercepter le schéma d'URI côté `:app` (ProjectActivity.openFile) pour ouvrir un buffer read-only avec la source décompilée. La foundation API + le stub builder + les tests sont en place.

#### 4. Hover tactile « tap-and-hold » distinct du long-press

- `EditorView.setTouchHoverEnabled(boolean)` / `isTouchHoverEnabled()` — opt-in (default `false` = comportement legacy v2.31-v2.38 où le long-press 400 ms déclenche à la fois sélection + toolbar + quick doc) ;
- quand activé : à `ACTION_DOWN`, un timer 500 ms est armé (slop 20 px, `TAP_HOLD_HOVER_TIMEOUT_MS`). Si le doigt reste posé sans mouvement au-delà de 500 ms, le quick doc s'affiche au point touché via `view.offsetAt(x, y)` + `showQuickDoc(offset)`, **SANS** déclencher la sélection (pas de handles, pas de toolbar, pas de caret move) ;
- suppression mutuellement exclusive du long-press : pendant le timer armé, `gestureDetector.setIsLongpressEnabled(false)` — le path classique de sélection long-press ne peut pas se déclencher en parallèle (sinon à 400 ms il ferait sélection + 100 ms plus tard hover, double popup) ;
- cancellation : `ACTION_UP`, `ACTION_CANCEL`, ou movement au-delà du slop (`ACTION_MOVE` > `TAP_SLOP_SQ`) annulent le timer. Si l'hover a déjà déclenché (`tapHoldHoverTriggered = true`), le quick doc reste affiché jusqu'au tap-ailleurs dismiss (parité Sora `FEATURE_SCROLL_AS_CONTENT`).

#### Validation

- +13 tests `EditorPreviewSheetTest` (ui) ;
- +7 tests `SignatureHelpControllerTest` (core) ;
- +7 tests `DecompiledSourceTest` (lspjava) — activés par `@EnabledIf` quand un JDK avec `lib/src.zip` est détecté ;
- suites complètes : ui (13 nouveaux), lspjava (7 nouveaux), core (7 nouveaux), app, lsp-api, cel-lsp — 0 échec. `assembleDebug` OK (APK 29 Mo). Aucune régression des verrous v2.29→v2.38.

## [v2.38] — ui / cel-lsp / lspjava / app — signature help + quick doc + hover Sora + design popups CodeAssist + fix popup complétion (v0.1.0.59-v2.38)

- **Popup de COMPLÉTION — correctif de couverture de ligne + coins
  arrondis** (le user a signalé que le popup couvrait parfois la ligne
  où le caret tape) :
  - ancre Y désormais **fold- ET wrap-aware** (`docLineToY` +
    `rowsForDocLine` — avant : formule brute `padTop + (line+1) ×
    lineHeight` qui plaçait l'ancre au-dessus de la rangée visuelle
    réelle dès qu'une ligne précédente était wrappée ou pliée) ;
  - le popup ne recouvre **JAMAIS** la ligne du caret : s'il ne tient pas
    en dessous il passe au-dessus (avant : `Math.max(0, …)` laissait le
    popup démarrer à Y=0 et couvrir la frappe quand le clavier logiciel
    réduisait le viewport) ;
  - réduction dynamique de `completionRowsVisible()` (min 1) — le
    hit-test, le drag-scroll et le clamp `selected` suivent la source
    unique ;
  - coins **TOP-LEFT/TOP-RIGHT seuls** arrondis (12dp, Path addRoundRect
    avec radii par coin — les coins BOTTOM restent droits, motif
    bottom-docked inversé), clip au path (les rangées ne débordent plus
    sur les coins droits).
- **HOVER à la Sora Editor** (le user : « vérifie comment Sora Editor
  le gère pour faire pareil ») :
  - résolution sous le **POINTEUR** (pas le caret !) via `offsetAt(x, y)`
    — avant, le callback résolvait à `selection.start` → survoler un
    autre symbole sans bouger le caret affichait le MAUVAIS doc ;
  - **slop 20 px** avant de re-résoudre (parité Sora
    `HOVER_TAP_SLOP`) — un tremblement sous le seuil ne redémarre PAS le
    dwell, chaque déplacement au-delà redémarre le timer de 500 ms ;
  - `HOVER_EXIT` / `ACTION_CANCEL` annule le pending ;
  - **timeout LSP hover 10 s → 2 s** (parité Sora
    `Timeouts.HOVER = 2000`) ;
  - le popup **SUIT le texte au scroll** (offset d'ancrage stocké, X/Y
    recalculés chaque frame) — avant : coordonnées écran figées, le
    popup restait en place pendant le scroll ;
  - le popup est **dismiss quand la ligne d'ancre sort du viewport**
    (parité Sora `FEATURE_SCROLL_AS_CONTENT`) ;
  - **dismiss à la perte de focus** (parité Sora
    `EditorFocusChangeEvent`) ;
  - exclusion mutuelle hover ↔ complétion : la complétion qui s'ouvre
    referme le hover (et le refuse tant qu'elle est ouverte) ;
  - ancrage **centré sur le caractère** (parité Sora
    `updateWindowPosition`), **au-dessus de la ligne** par défaut, en
    dessous sinon, largeur max 80 % de l'éditeur (cap 320dp).
- **QUICK DOC — rendu markdown minimal + scroll du corps** :
  - extraction des **fences de code markdown** (le serveur hover LSP envoie la
    signature exacte dans un fence `java`) → rendu en **en-tête
    signature sur fond teinté** (motif QuickDocPopup de CodeAssist) +
    **divider** sous la bande (avant : markdown aplati en texte plat) ;
  - le retrait des fences AVANT la détection des tags corrige au passage
    une section parasite `@Override` quand la signature d'une méthode
    contenait une annotation ;
  - **inline code** `...` rendu en runs colorés (couleur `func`/accent,
    parité parseur CodeAssist `{@code}`) ;
  - **scroll du corps** au drag sur le popup (champ `quickDocScrollY` +
    scrollbar, parité NavMenu) — avant : « just clips for now »
    (contenu coupé au-delà de 300dp) ;
  - tap **ailleurs** referme (parité Sora `onDismissRequest`), tap
    **dans** le popup = scroll du corps (geste englouti) ;
  - rayon 12dp (avant 6dp uniformes).
- **SIGNATURE HELP — documentation de la signature active** :
  - le serveur `:lspjava` extrait la **JAVADOC de la déclaration source**
    de la méthode active (cache `docCache` par FQN, scan AST du fichier
    déclarant — `md.javadoc.sourceStart/sourceEnd` sur l'AST ecj),
    transportée par `SignatureInformation.documentation` (Either Left
    String) ; le client `:cel-lsp` lit les deux côtés (Left OU Right) ;
  - rendu en **bande documentation** sous la liste des surcharges
    (parsée par QuickDoc : description + sections `@param`/`@return`/…,
    cap 6 lignes), divider + fond teinté, sections en couleur `keyword`
    (motif IntelliJ / CodeAssist) — avant : `sig.documentation` jamais
    rendu ;
  - clamp bas du popup (ne sort plus de l'éditeur), rangées clippées au
    rect, rayon 12dp.
- **DESIGN CodeAssist des popups** (le user : « le même design popup
  pour find/replace de CodeAssist, fait pareil pour rename et go to
  line ») :
  - **find/replace** : barre dockée en HAUT, flush au bord supérieur
    (coins arrondis **BAS** 14dp, parité `RoundedCornerShape(bottomStart,
    bottomEnd)` de CodeAssist), fond glass (light #F8F7F4@88% / dark
    #18191C@86%), bordure 1dp ; **2 rangées** (find + replace repliable)
    au lieu de 3 ; chevron toggle (droite replié / bas déplié) ; champ
    FieldBox (surfaceContainerHigh, coins 12dp, bordure 1dp, padding
    10/7) ; compteur `n/total` (VIDE si query vide, **rouge** à 0/0,
    min-width 34dp) ; chips carrées 30dp `Aa` / `W` / `.*` (fond
    primaryContainer si actif) ; boutons icône 30dp (chevrons, close) ;
    boutons pill « Replace » / « All » (coins 9dp) ; **Enter = next,
    Shift+Enter = prev, Esc = close** (interception clavier DANS le
    champ, parité `onPreviewKeyEvent`) ; **regex invalide** → bordure du
    champ en colorError + 0/0 (sans crash) ;
  - **rename + go-to-line** : cartes jumelles flottantes TopCenter à
    48dp du haut, largeur 320dp, fond glass coins 18dp, bordure 1dp
    glassEdge, padding 16dp, titre bodySmall semibold, champ fond
    surfaceContainerHigh coins 12dp padding 12/10 texte 16sp mono, hint
    labelSmall sous le champ, validation clavier Enter/Esc (helper commun
    `buildGlassCard` → GradientDrawable aux couleurs du thème de
    l'éditeur, cohérent avec les popups Canvas) ; **unification** : le
    bouton toolbar / palette / overflow de l'app ouvrent maintenant la
    MÊME carte que Ctrl+G (avant : MaterialAlertDialog séparé, ligne
    seule sans `line:col`, dupliqué).
- **Tests** : +1 `QuickDocTest` (fences extraites en signature,
  sections @Override parasites évitées). Suites complètes : ui 116×2,
  lspjava 214 (1 skip environnemental), app 84×2, core 482×2, lsp-api
  22×2, cel-lsp 5×2 — **1632 exécutions, 0 échec**. `assembleDebug` OK
  (APK 29 Mo). Aucune régression des verrous v2.29→v2.37.

### lspjava / cel-lsp / lsp-api / ui — GO TO Implementations + Super : le menu unifié complète sa section GO TO (v0.1.0.59-v2.37)

- **GO TO — Implementations** (portage des `implementationTargets` du
  `KotlinSourceAnalyzer` de CodeAssist / `Inheritors.kt`, handler LSP
  standard `textDocument/implementation`) : les héritiers DIRECTS du
  type en contexte (référence de type au caret, sinon la classe
  englobante — `contextTypeFqn` de CodeAssist). Le moteur
  `JdtNavigationEngine.findImplementations` fait un **balayage live
  multi-fichiers borné** (pattern `renameProject` : fichier courant +
  tous les `.java` des `sourceRoots`, triés et dédupliqués, pré-filtre
  textuel `contains(simpleName)` avant résolution ecj par candidat,
  overlay des tampons ouverts consulté AVANT le disque) — bornes 400
  fichiers / 8 s, résultat partiel rendu au-delà ; FQN comparés via
  `original()` des types paramétrés ; classe imbriquée cross-fichier
  localisée par remontée des segments. Positions converties avec le
  texte du fichier CIBLE (pas du fichier source).
- **GO TO — Super** (méthode LSP **PERSONNALISÉE**
  `textDocument/superDefinition` — aucune requête standard n'existe ;
  portage des `superTargets` de CodeAssist) : (1) le caret sur une
  méthode qui outrepasse → le même-nommé dans chaque supertype transitif
  (libellé `name  ·  Super`, dédup par (fichier, offset)) ; (2) sinon
  les supertypes DIRECTS du type en contexte (libellé
  `Simple  ·  pkg`). La réponse transporte l'**offset EXACT** du nom
  dans le fichier déclarant + le libellé picker (aucun round-trip
  Position↔offset à risquer côté client). Annoncée par la capability
  `experimental.superDefinitionProvider` (pattern jdtls) — un serveur
  tiers qui ne l'annonce pas → l'option n'apparaît pas, proprement.
- **Pattern LSP4J de la méthode custom — les DEUX côtés** :
  - **serveur** (`:lspjava`) : `NavTextDocumentService` (interface qui
    étend `TextDocumentService` + `@JsonRequest`) exposée par le
    delegate COVARIANT `@JsonDelegate` de `NavLanguageServer` —
    nécessaire pour que la carte de désérialisation des PARAMÈTRES
    connaisse la méthode (sinon « argument type mismatch ») ;
  - **client** (`:cel-lsp`) : `CodeIdeLanguageServer` déclare
    `superDefinition(...)` au **NIVEAU SUPÉRIEUR** de l'interface
    (méthode RPC directe du proxy). ⚠ Le piège du delegate covariant :
    `LanguageServer.getTextDocumentService()` est DÉJÀ annoté
    `@JsonDelegate` dans lsp4j — le redéclarer covariant crée deux
    segments delegate homonymes et le launcher lève
    `IllegalStateException: Duplicate RPC method` pour TOUS les
    serveurs (toute l'intégration LSP morte). Sans interface cliente,
    la réponse était au contraire silencieusement JETÉE (le type de
    retour est absent de la carte de désérialisation du launcher).
- **Intégration NavMenu + SPI** : les 4 options GO TO dans l'ordre des
  `NavKind` CodeAssist — **Declaration → Implementations → Type
  declaration → Super** — chaque option n'apparaît que si ≥ 1 cible
  (parité `navigationOptions`). Slots SPI
  `Language.getImplementationsProvider()` /
  `getSuperDefinitionProvider()` (default null, rétro-compatible) ;
  `LspLanguage` les expose gated sur les capabilities
  (`implementationProvider` / `experimental.superDefinitionProvider`,
  Map OU JsonObject). Libellés picker : « Simple  ·  pkg » /
  « name  ·  Super » transportés via `displayName` ; un displayName
  qui est un chemin/URI retombe sur le nom COURT du fichier. Icônes
  canvas : layers (Implementations), pin (Super).
- **Divergences assumées vs CodeAssist** : cibles binaires omises
  (CodeAssist ouvre une vue library décompilée — CodeIDE n'a pas de
  décompilateur : un `extends Thread` ne propose pas la classe binaire
  `Thread`, mais le dossier `java/lang` du SDK n'est pas source non
  plus) ; CodeAssist maintient un `SubtypeIndex` persistant
  projet-wide, CodeIDE un balayage borné par requête (plus simple, pas
  d'index à invalider).
- Tests : **+10 `NavigationFacadeTest`** (façade LSP réelle, fixtures
  multi-fichiers `@TempDir`) — sous-types cross-fichiers aux positions
  EXACTES (ligne+colonne du nom), contexte classe englobante, absence →
  vide, implémenteurs d'interface, sous-classe imbriquée même fichier,
  auto-référence d'interface ; Super : override → méthode de base +
  libellé, contexte type, supertype binaire → vide, invocation d'une
  héritée → vide. **+1 `SuperDefinitionRoundTripTest`** (transport
  JSON-RPC complet en-process avec le vrai `LanguageServerWrapper`) :
  launcher sans « Duplicate RPC method », capability experimental
  transportée, appel typé, désérialisation `SuperNavTarget`. **+5
  `EditorNavMenuTest`** — ordre NavKind des 4 options + smoke render
  icônes layers/pin, options vides omises, pick Implementations
  multi-cibles avec libellés transportés, pick Super cross-file →
  listener (offset exact), fallback nom court de fichier. **+1
  `LanguageSPITest`** — slots SPI navigation default null.
- Suites complètes : ui 116×2, lspjava 214 (1 skip environnemental),
  app 84×2, core 482×2, lsp-api 22×2, cel-lsp 5×2 — **1632 exécutions,
  0 échec**. `assembleDebug` OK (APK 29 Mo). Aucune régression des
  verrous v2.29→v2.36.

### ui / lspjava / cel-lsp / lsp-api — toolbar de sélection : toutes les options CodeAssist + menu contextuel unifié (v0.1.0.59-v2.36)

- **Pill de sélection COMPLÈTE en permanence** (parité
  `SelectionToolbar`/`CodeEditor.kt` de CodeAssist) : ℹ Docs et ⋯ Actions
  sont désormais TOUJOURS affichés avec le divider — CodeAssist fournit
  `onDocs`/`onMenu` non-null en permanence ; avant, les icônes étaient
  masquées sans quick-fixes sur la ligne et l'utilisateur perdait l'accès
  à Docs et au menu GO TO. Mode sélection : `Copy | Cut | Paste | Select
  all | ┊ | ℹ | ⋯` ; mode collapsed (re-tap) : `Paste | Select all | ┊ | ℹ | ⋯`.
- **Menu contextuel unifié** (portage `NavMenu`/`NavMenuLayer` de
  CodeAssist) — le bouton ⋯ n'ouvre plus la popup plate de quick-fixes :
  - sections **GO TO** / **QUICK FIXES** / **INTENTIONS** en majuscules,
    affichées seulement si non-vides ; « Nothing found in source. » quand
    tout est vide (parité `NavigationMenu.kt`) ;
  - split par kind : `quickfix*` → QUICK FIXES, le reste (refactor,
    source) → INTENTIONS (parité `UiActionKind.QUICK_FIX`) ;
  - **GO TO — Declaration** : via le resolver `textDocument/definition`
    existant ; **GO TO — Type declaration** : nouveau
    `textDocument/typeDefinition` (le type du symbole au caret —
    `Foo x = …` → `class Foo`) ;
  - pick mono-cible même fichier → saut du caret + scroll ; multi-cibles
    → mode **RESULTS** (picker de cibles, parité `NavMenuState.Results`) ;
    cross-file → `definitionListener` (l'hôte ouvre le fichier) ;
  - carte glass ancrée SOUS la ligne du caret (caretX clampé au gutter,
    bas de ligne + 6dp, marge 8dp, bascule AU-DESSUS si débordement),
    rangées 40dp icône 16dp + label (ellipsisé), headers labelSmall
    semibold, press feedback teinté accent, contenu scrollable au drag
    (clamp 360dp) ; icônes canvas équivalentes des CaIcons : chevrons
    (Declaration), hexagone (Type declaration), gear (quick fixes),
    lightbulb (intentions), dot (cibles) ;
  - gestes : DOWN dans la carte l'engloutit (press feedback + drag-scroll
    + pick au relâchement — parité Popup CodeAssist) ; tap ailleurs
    referme (`onDismissRequest`) ; édition referme ; popup exclusif avec
    la diagnostic sheet.
- **`textDocument/typeDefinition` serveur** (portage
  `typeDeclarationTargets` du `KotlinSourceAnalyzer` de CodeAssist,
  restreint au même fichier) : `JdtCompilerEngine.findTypeDefinition` —
  binding au caret (référence de type, variable/paramètre/champ → son
  type, appel → son type de retour), élément feuille des tableaux, types
  paramétrés ramenés à l'original ; types de base (`int`), variables de
  type (`T`) et types binaires (classpath) → aucune cible. Capability
  `typeDefinitionProvider: true` ; client `LspTypeDefinitionProvider`
  (cel-lsp) câblé via le nouveau slot SPI `TypeDefinitionProvider`
  (lsp-api) + `EditorView.setTypeDefinitionResolver`.
- **Correctif d'offsets `SourceFocuser` (bug latent go-to-definition)** :
  `findDefinition` rendait ses plages en coordonnées de la source RÉDUITE
  (corps hors-portée remplacés par `{}`) que le service LSP convertissait
  avec la source COMPLÈTE — sur un fichier > 2000 chars, sauter à une
  méthode déclarée APRÈS la méthode du caret atterrissait à un offset
  décalé vers la gauche. `Focused.mapBack()` traduit désormais chaque
  position réduite vers l'original (recherche binaire sur les points
  d'insertion, position dans un jeton inséré → position de reprise).
- **Docs/Actions à `selection.start`** (parité exacte
  `quickDocAt(path, text, caret)` / `openNavMenu()` de CodeAssist — tous
  deux passent `selection.start`, pas l'extrémité active).
- Tests : **+13 `EditorNavMenuTest`** (Robolectric) — sections dans
  l'ordre + headers, split quickfix/intention par kind, section vide
  omise, nothing-found + smoke render, pick mono-cible → caret,
  multi-cibles → RESULTS + pick cible, cross-file → listener, pick action
  → apply + fermeture, tap rangée via hit-test, tap hors carte → dismiss,
  drag-scroll clampé, ancrage sous la ligne, édition → dismiss ;
  **+4 mapBack `SourceFocuserTest`** ; **+5 `TypeDefinitionFacadeTest`**
  (façade LSP réelle) — type d'une var locale (classe interne), référence
  de type → elle-même, type binaire (String) → rien, littéral int → rien,
  définition APRÈS la méthode du caret à la position EXACTE (verrou du
  correctif focuser) ; 6 tests `EditorSelectionToolbarTest` mis à jour
  vers le nouveau contrat (icônes toujours présentes, Actions → menu
  unifié).

### ui / app — hints XML/Kotlin + pendingTapDismiss différé + magnifier (v0.1.0.59-v2.35)

- **Inlay hints XML — valeur résolue des références de ressources locales**
  (portage `XmlInlayHintService` de CodeAssist) :
  - `XmlResourceIndex` (app, `jo.codeide.lang`) — équivalent de l'index
    `AndroidResourceIndex` : scan borné des `res/values` et `values-…`
    (64 rép. res / 384 fichiers / 1 Mo par fichier, `build/` `.gradle/`
    ignorés), précédence base `values/` > variantes qualifiées, sanitize
    des noms (`.`/`-` → `_`), littéral capé 60 chars, types porteurs de
    valeur seulement (string, color, dimen, bool, integer, fraction) ;
    cache statique par projet + TTL 5 s, construit sur le thread
    background de l'éditeur (jamais l'UI thread) ;
  - `XmlResourceHints` — scan tolérant du document : refs locales
    `@(\w+)/([\w.]+)` uniquement (framework `@android:…`, déclarations
    `@+id/…` et ressources-fichiers exclus par construction), commentaires
    et CDATA sautés, guillemets simples supportés, preview capé 30 + « … »,
    ancre juste après le guillemet fermant (`value.range.end + 1`),
    espace de tête = paddingLeft ;
  - `XmlLanguage` (SPI `Language`, câblé dans
    `CodeEditorView.buildLanguage` pour `xml` avec racine de projet) —
    la coloration lexicale / complétion statique / xmlNewline sont
    inchangés ; sans racine de projet, pas de provider (parité
    « resolver null ⇒ no inlay hints »).
- **Inlay hints Kotlin lexicaux** (sous-ensemble inférable du
  `KotlinInlayHintService` de CodeAssist — sans résolveur complet) :
  types des `val`/`var` à initialiseur **certain** — littéraux purs
  (String/Int/Long/Double/Float/Boolean/Char + arithmétique simple) et
  constructeurs PascalCase (`val p = Person(…)` → `: Person`) ; jamais
  de guess sur un appel de fonction (`listOf(…)` → silence), un range
  (`1..5` → silence), un type explicite, une déstructuration ou un
  initialiseur multi-lignes ; scanner conscient du langage (commentaires
  imbriqués, raw strings, templates) ; ancre fin d'identifiant, libellé
  `: Type` collé (`typeHint()` CodeAssist). Câblé via `KotlinLanguage`
  (SPI) pour `.kt`/`.kts`.
- **pendingTapDismiss — tap différé dans une sélection** (portage
  `EditorInteraction`/`EditorInputModifier` de CodeAssist) : un tap dans
  une sélection la garde vivante pendant la fenêtre multi-tap (280 ms,
  anti-flicker : pill + poignées visibles) puis, si aucun second tap
  n'arrive, la referme (caret à l'offset tapé, pill + poignées masquées)
  — c'est aussi ce qui permet de refermer une sélection couvrant tout le
  fichier. Annulations : nouveau geste (DOWN), nouveau tap (`handleTap`),
  swipe au-delà du slop, édition (`onTextChanged`), cancel système.
- **Magnifier ré-armé pendant le drag des poignées** (code dormant
  v3.18.0, « interferes with selection ») : la bulle (60 dp, zoom 2×,
  ±3 lignes, rendu inlay-aware) s'allume au PREMIER MOVE d'un drag de
  poignée (start/end/caret — un tap sur la poignée ne la fait pas
  flasher), suit le doigt, s'éteint sur UP/CANCEL ; jamais pendant un
  scroll/drag-select ordinaire.

### ui / lspjava — popup de sélection complet + hints CodeAssist manquants (v0.1.0.59-v2.34)

- **Popup de sélection (barre de sélection) — câblage réparé de bout en
  bout** (le portage du renderer v2.34 précédent était inopérant) :
  - **animation d'entrée vivante** — `showSelectionToolbar()` horodate le
    show (`selectionToolbarShownAt`) ; le champ n'était JAMAIS assigné, donc
    l'`entrancePop` (scale 0.96→1 + fade) et la cascade par item (24 ms/item)
    ne jouaient pas. Tous les affichages passent désormais par
    `showSelectionToolbar()` (double-tap, triple-tap, long-press, re-tap,
    tap-dans-sélection) ;
  - **hit-test sur les métriques partagées** — `handleSelectionToolbarTap`
    recalculait un layout 4-boutons dupliqué et obsolète : les boutons
    **Docs ℹ** et **Actions ⋯** étaient INATTEIGNABLES et le mode collapsed
    rejetait tout tap (`if (sel.isCursor()) return false`). Il consomme
    maintenant `selectionToolbarMetrics().actionAt()` — les 6 actions sont
    câblées : Copy/Cut/Paste/Select all + Docs (quick-doc à l'extrémité
    active) + Actions (popup quick-fixes de la ligne de sélection) ;
  - **re-tap collapsed (portage CodeAssist)** — un second tap au MÊME
    endroit que le caret BASCULE la pill Paste/Select all (le toggle
    `handlesVisible = reTap && !handlesVisible` de
    `EditorInputModifier.kt`) ; un tap ailleurs la referme ;
  - **parité comportementale CodeAssist** — Copy/Cut/Paste referment la
    pill et masquent les poignées, **Select all la laisse ouverte**
    (Copy/Cut deviennent disponibles sur la sélection totale), un tap DANS
    la sélection la re-affiche, un tap sur un gap/divider de la pill est
    englouti sans bouger le caret (le Popup CodeAssist ne propage pas) ;
  - `onTextChanged` referme aussi les poignées (« typing puts the touch
    chrome away », convention Android de CodeAssist).
- **Chaining hints multi-lignes** (portage `chainingHint` du
  `JdtInlayHintService` de CodeAssist) : sur un chaînage fluent dont le
  récepteur est lui-même un appel posé sur une AUTRE ligne, le type du
  récepteur s'affiche à la fin de SA ligne — `new StringBuilder("a")\n
  .append("a")` reçoit « StringBuilder » (nom court, sans deux-points,
  padding gauche). Récepteur constructeur/field ⇒ pas de hint (parité) ;
  même ligne ⇒ pas de hint (« otherwise it's just noise »). Types par
  bindings ecj (`resolvedType`, repli `binding.returnType`), lignes par
  recherche binaire sur les débuts de ligne.
- **Hints de type des paramètres lambda implicites** (portage
  `visit(LambdaExpression)`) : chaque paramètre lambda au type implicite
  (`x -> …`) reçoit « : Type » à la fin de son nom — `(a, b) ->` sur un
  `BiConsumer<String, Integer>` reçoit « : String » et « : Integer ». Les
  paramètres typés explicitement (`(String x) ->`) restent ignorés
  (ecj : `Argument.type != null`).
- **Padding LSP honoré par le client** (parité `buildInlayAnnotated` de
  CodeAssist : les espaces de padding font partie du texte fantôme tissé) :
  avant, les flags étaient ignorés — « name: » collait à l'argument et le
  type de chaînage collait au dernier caractère de l'appel. Les var-hints
  perdent leur `paddingLeft` parasite (rendu collé « s: String », comme
  CodeAssist) ; les paramètres rendent « name: … » et les chaînages
  « … StringBuilder ».
- +15 tests Robolectric `EditorSelectionToolbarTest` (animation, métriques
  collapsed/complètes, icônes conditionnelles, résolution des 6 actions,
  gaps non actionnables, comportements Copy/Cut/Paste/Select all,
  re-tap toggle, tap-dans-sélection) + 5 tests serveur
  `JdtChainedLambdaHintsTest` (chaînage multi-ligne, même ligne, lambda
  implicite/typé, deux paramètres).

### lspjava / ui / core — l'inventaire « reste à faire » livré (v0.1.0.59-v2.33)

- **foldingRange (serveur LSP)** : nouveau `JdtCodeFolder` (portage
  `JdtCodeFolder.kt` de CodeAssist) — groupe d'imports replié PAR DÉFAUT
  (`kind="imports"`), intérieur des accolades (les `{`/`}` restent visibles,
  `kind="block"`), commentaires de bloc/Javadoc multi-lignes
  (`kind="comment"`) ; scan lexical unique aware chaînes/caractères/
  échappements/text-blocks. Handler `textDocument/foldingRange` (lane
  ANALYSIS) + capability, précision CARACTÈRE + `collapsedText`.
- **foldingRange (client cel-lsp)** : tir throttlé après chaque publication
  de diagnostics (pattern semanticTokens — génération anti-obsolescence),
  conversion ligne/char → offsets, application via le nouveau
  `EditorSession.applyCodeFolds` (portage CodeAssist : état replié
  utilisateur préservé, `collapsedByDefault` appliqué une fois par document,
  reset par `setLanguage` ; `FoldRegion` gagne `collapsedByDefault`).
  Garde `languageApplied` contre la course didOpen→setLanguage.
- **Quick-fix « Créer la méthode »** (portage
  `CreateMethodFromUsageQuickFixProvider`) : famille `UNDEFINED_METHOD`
  détachée de `UNRESOLVED_REFERENCE` ; type/méthode englobants par AST ecj
  (positions exactes), `private [static]`, retour void/Object selon le
  contexte d'appel, paramètres des args STRUCTURÉS ecj raccourcis + imports
  collectés (FQN en cas de collision), corps
  `UnsupportedOperationException("TODO: …")`, insertion après la méthode
  englobante.
- **Hints de type `var` par bindings ecj** (`JdtInlayHints.typeHintsFor`) :
  le type RÉEL de l'initialiseur (appels de méthode, ternaires,
  enhanced-for, génériques `List<String>`), ancré à la fin du nom —
  remplace le scanner littéraux-seuls (qui matchait aussi `var ` dans les
  chaînes) ; le scanner reste en repli moteur-indisponible.
- **Word-wrap : géométrie unifiée (`WrapRows`/`wrapRowsFor`)** — comptage,
  découpage, caret, tap, scroll et chips consomment LA MÊME source.
  Corrige : queue de ligne indentée jamais dessinée (comptage/découpage
  divergents dès que l'indent > 0), caret une rangée trop haut
  (`col/maxColsPerRow`), caret dessiné trop bas (double addition du décalage
  de rangée dans `drawCaret`), tap décalé sur les rangées indentées.
- **Chips diagnostics multi-rangées** : placées après la FIN de la DERNIÈRE
  rangée repliée (pattern `lastSub` de CodeAssist) — l'ancienne approximation
  les posait au-delà du bord droit (invisibles) et les centrait sur la
  première rangée.
- **Scroll horizontal étendu** (pattern `contentWidth()`/`chipExtent` de
  CodeAssist) : `maxH` compte la longueur VISUELLE des lignes (inlay hints
  tissés, O(hints)) et le débordement des chips
  (`chipExtentContentX`, publié par le draw pass) — un hint/chip au-delà de
  la ligne la plus longue est désormais atteignable.

### ui — câblage de l'éditeur (v0.1.0.59-v2.32)

- **Diagnostic sheet : deux entrées exactement** — le tap sur le range
  squiggle ne l'ouvre plus (le caret se place normalement, parité
  CodeAssist `session.setCaret`) ; seuls le **chip** de fin de ligne et le
  **dot du gutter** l'ouvrent. Au passage, le dot du gutter était MORT
  depuis v3.7.x : `ACTION_DOWN` dans la zone numéros de ligne armait
  `isScrolling=true` → `ACTION_UP` ne passait jamais par `handleTap`. Un
  flag `downInLineNumberArea` route un tap sans mouvement vers
  `handleTap` (sheet) et laisse le drag scroller/fling comme avant.
- **Bracket matching** (portage `EditorEdits.matchingBracket`) : le
  curseur juste après `}`/`)` (ou posé sur `{`/`(`/`[`) highlighte la
  paire entière — rectangle contour 1 px couleur caret à 45 % d'alpha sur
  les DEUX crochets (rendu CodeAssist exact). Scan de profondeur borné
  (50 000 chars), recalcul synchrone à chaque déplacement de caret et à
  chaque édition, géométrie inlay-aware (`visualColFor`) et fold-aware.
- **Inlay hints : câblage réparé de bout en bout** :
  - la requête couvre désormais TOUT le document (parité CodeAssist
    `hintsAt(path, text, 0, text.length)`) — l'ancienne requête
    viewport (lignes visibles ±4, `getHeight()` = 0 au boot) ne servait
    que ~5 lignes et ne se re-déclenchait jamais au scroll ;
  - les pièces inlay sont **triées par colonne** avant
    `buildColumnMaps` (qui suppose une entrée triée) — le serveur émet
    les hints paramètres puis var, une liste désordonnée droppait
    silencieusement les hints ancrés avant l'index courant.

### ui — correctifs UI de l'éditeur (v0.1.0.59-v2.31)

- **Inlay hints tissés (position réparée)** : le hint était dessiné
  PAR-DESSUS le texte à sa colonne d'ancrage ; il est désormais woven dans
  la ligne (`drawRawRange` — équivalent canvas du `buildInlayAnnotated` de
  CodeAssist) : le texte après le hint est décalé de la largeur du hint.
  Style CodeAssist : texte italique atténué SANS fond (`textTertiary +
  Italic`). Toute la géométrie consomme les colonnes visuelles :
  `visualColFor`/`rawColFor` (nouvelles, sémantique `rawToVisual`/
  `visualToRaw` de CodeAssist — caret ancré AVANT le hint, tap DANS le
  hint → colonne d'ancrage) utilisés par le texte, l'overlay sémantique,
  la sélection, les squiggles, les highlights, les popups (complétion,
  signature help, barre de sélection) et `offsetAt`.
- **Diagnostic sheet modal** (portage `DiagnosticSheet` de CodeAssist) :
  scrim sur l'éditeur (tap = fermer, geste bloqué), panneau docké à coins
  supérieurs arrondis, en-tête sévérité + bouton ×, message word-wrap ≤ 6
  lignes, « QUICK FIXES » + rangées de 44 dp (tap = applique). Géométrie
  partagée rendu/hit-test via `diagnosticSheetMetrics()`. Ouvert par le
  dot du gutter, le squiggle, ou la chip.
- **Diagnostic chips de ligne** (portage `DiagnosticChip`) : UNE pilule par
  ligne (plus sévère Error/Warning) après la fin de ligne + 3 chars — fond
  sévérité 16 % alpha, pastille + message semi-gras, 1 ligne avec ellipse,
  clip à droite du gutter, tap → sheet. Activées par défaut
  (`setDiagnosticChipsEnabled` reste disponible).
- **Indent guides modernisés** (parité « bracket lines » CodeAssist) :
  pontage des lignes vides (héritage de l'indent la plus faible des
  voisins non vides — guide CONTINU à travers un bloc), niveaux stricts
  (`level < cols`), caps arrondis. Nouveau `leadingIndentOrBlank` avec
  sentinel -1 pour ligne vide.
- **Lecture-seule** : `drawCaret` ne dessine plus rien quand
  `session.isReadOnly()` (console, vues décompilées).
- **Gutter plus discret** : ampoule quick-fix 7 dp → 5,5 dp (elle débordait
  de la bande de fold), dot de diagnostic 3,5 dp → 3 dp.
- Barre de sélection : ancrage via `caretScreenPos` (inlay-aware) aligné
  sur le hit-test.
- +10 tests Robolectric (`EditorDiagnosticsInlaysTest`).

### lspjava / ui — badge de type dans la complétion + quick-fixes réels (v0.1.0.59-v2.30)

- **Badge de type par suggestion** (portage du `KindBadge` de CodeAssist) :
  carré arrondi teinté + glyphe devant chaque ligne du popup — « K »
  mot-clé, « C » classe, « I » interface, « E » enum, « @ » annotation,
  « M » méthode, « F » champ, « v » variable, « p » package, « {} »
  snippet, « T » paramètre de type, « # » constante d'enum, « R » record.
  Palette CodeAssist assombrie automatiquement sur thème clair ; le label
  met en valeur les caractères matchant le préfixe (accent + gras) et le
  detail passe aligné à droite (nouveau `CompletionKindBadge` dans `:core`).
- **Chaîne du kind réparée de bout en bout** : le kind LSP NUMÉRIQUE
  (`kindCode`) voyage maintenant serveur → pont client (`LspLanguage`) →
  SPI (`CompletionItem`) → `CompletionSession.Item` → renderer. Trois bugs
  corrigés en chemin : `CompletionPublisherAdapter` passait `sortPriority`
  dans le champ `kind` et `isKeyword = !isSnippet` ; le pont réduisait le
  kind à 5 lettres ; côté serveur `ANNOTATION` tombait en `Text` (il
  voyage désormais en `Class` + `data="annotation"` — badge « @ »).
- **Vrai kind des types de l'index** : `typeCompletions` résout chaque FQN
  contre la portée du marqueur pour classer annotation/interface/enum
  (avant : CLASS uniforme) ; `kindOfType` teste `isAnnotationType()` AVANT
  `isInterface()` (un type annotation EST une interface pour ecj).
- **Quick-fixes à édits RÉELS** (réécriture du `CodeActionService`,
  portage `JavaActions.kt`/`JavaCompilerFixes.kt` de CodeAssist) :
  suppression de ligne d'import inutilisé, suppression d'instruction non
  utilisée (multi-fragments et paramètres écartés), try-catch au type
  EXACT de l'argument structuré, `throws` créé après la liste de
  paramètres ou étendu à la clause existante (méthode englobante trouvée à
  travers les blocs de contrôle), cast vers le type attendu (args[1]),
  point-virgule en fin de ligne, et « Importer X (fqn) » ×N candidats de
  l'index de noms de classes (`fqnsForSimpleTypeName`). Contrat : une
  action sans édits n'est pas proposée (les coquilles vides
  `newText = ""` — try-catch/throws/cast/suppressions — sont éradiquées ;
  le « Ajouter un point-virgule » n'insère plus au début de la plage).
- Mots-clés builtin : kind `12` (Value) → `14` (Keyword, LSP moderne).
- `importInsertOffset` unifiée (`JavaTextDocumentService` délègue au
  `CodeActionService`).
- Tests : +4 `CompletionKindMappingTest` (kinds LSP bout-en-bout :
  Method/Field/Module/Class+data annotation), +15 `CodeActionServiceTest`
  (édits réels appliqués au texte), +12 `CompletionKindBadgeTest` (:core),
  +5 `CompletionPopupBadgeRenderTest` (:ui, Robolectric) ; chemins
  android.jar des tests réalignés (golden rules v2.29 de nouveau actives).

### lspjava — alignement du moteur de complétion sur CodeAssist (v0.1.0.59-v2.29)

- **Filtre subpackage déterministe** : nouvelle requête d'index EXACTE
  `directTypesInPackage(q)` (portage `exactAll(PACKAGE_TYPES, q)` de
  CodeAssist) — l'ancienne requête `startsWith(q.)` coupée à 300 résultats
  dans l'ordre du HashMap éjectait arbitrairement les types directs
  (`android.Manifest`/`android.R` : 2 452 classes sous `android.**`
  concouraient pour 300 places). `import android.` rend désormais
  exactement les 47 sous-paquets directs + les 2 types directs, à chaque
  requête, sur toute JVM/ART.
- **`CompletionPrefixMatcher`** (portage `PrefixMatcher.kt` de CodeAssist) :
  matching gradué exact / préfixe / préfixe-insensible / camel-hump /
  substring (≥ 3 caractères), appliqué à toutes les sources de candidats.
- **Scoring unifié** (portage 1:1 `CompletionRanker.kt`) : bonus de frappe,
  proximités LOCAL…UNIMPORTED_TYPE, bonus assignable, déprécié, tri total
  pertinence → longueur → nom (encodé dans le `sortText` LSP).
- **ContextAnalyzer enrichi** : pile de types attendus poussée aussi par
  les initialisateurs de déclaration locale et les `return` (type de
  retour de la méthode englobante) — plus seulement le LHS d'affectation.
- **Démotion statique corrigée** : statique-via-INSTANCE pénalisé (l'ancien
  code pénalisait le cas inverse, via TYPE).
- **Auto-import sûr** : collision de simple name avec un import explicite →
  insertion FQN sans import ; imports on-demand et java.lang/paquet courant
  suppriment l'auto-import ; filtre `taken` contre les doublons in-scope.
- **Membres de `java.lang.Object` rétrogradés** (OBJECT_MEMBER), types
  imbriqués privés cachés, paramètres distingués (kind PARAMETER).
- **jrt** : packages non exportés (`jdk.internal.*`, `sun.*`, `com.sun.*`)
  exclus de l'index (parité `publicBytecodeKind`).
- Tests : +9 `SubpackageGoldenRulesTest` (les 6 règles d'or, déterminisme
  sur 20 rounds), +10 `SubpackageDiagnosticTest` ; 147 tests verts.

## [3.33.0] — 2026-08-24

### Architecture LSP modulaire — `:cel-lsp`, `:cel-lsp-java`, `:lspjava`

Refonte de l'intégration LSP pour supporter à la fois les serveurs LSP externes
(XML, Kotlin, Python, etc.) et un moteur Java in-process. Trois nouveaux
modules indépendants sont ajoutés à la librairie code-editor :

#### Nouveaux modules

- **`:lspjava`** (pure Java, INDÉPENDANT) — moteur d'analyse JavaParser +
  symbol solver (`jo.lspjava.*`). Aucune dépendance vers l'éditeur ou Android.
  Peut être publié sur Maven Central sous `jo.lspjava:lspjava:1.0.0` sans
  modification.
- **`:cel-lsp`** (Android library) — façade LSP générique pour serveurs LSP
  externes via LSP4J. Implémente `Language` SPI via `LspLanguage` qui parle le
  protocole LSP sur stdin/stdout (`ProcessBuilderConnectionProvider`),
  TCP (`SocketConnectionProvider`), ou LocalSocket Android
  (`LocalSocketConnectionProvider`). Le host enregistre un
  `LanguageServerDefinition` par serveur.
- **`:cel-lsp-java`** (Android library) — adaptateur Java in-process qui
  implémente `Language` SPI en utilisant le moteur `:lspjava`. C'est le seul
  module qui connaît à la fois `:lspjava` et `:lsp-api`. Pour les fichiers
  `.java`, l'app hôte utilise cet adaptateur (in-process, pas de subprocess,
  pas de cold start).

#### Architecture

```
CodeIDE app (:app)
    │
    ├── implementation(project(":ui"))         — éditeur (EditorView, EditorSession)
    ├── implementation(project(":cel-lsp"))    — façade LSP externe (XML, Kotlin, …)
    └── implementation(project(":cel-lsp-java")) — adaptateur Java in-process

code-editor library (entité indépendante)
    │
    ├── :core      — moteur pur Java (Rope, EditorSession, …)
    ├── :lsp-api   — Language SPI (Language, CompletionProvider, …)
    ├── :ui        — Android View layer (EditorView, EditorRenderer, …)
    ├── :lspjava   — moteur JavaParser pure-Java (INDÉPENDANT, publiable Maven)
    ├── :cel-lsp   — façade LSP externe (LSP4J, server subprocess/socket)
    └── :cel-lsp-java — adaptateur Java in-process (utilise :lspjava)
```

#### Public API

- `jo.codeeditor.lsp.java.JavaLanguage` (depuis `:cel-lsp-java`) — implémente
  `Language` SPI pour Java. Constructeurs :
  - `new JavaLanguage()` — mode basic (pas de projectRoot, pas de filePath)
  - `new JavaLanguage(filePath, projectRoot)` — mode complet (résout les
    symboles cross-file du projet)
- `jo.codeeditor.lsp.LspProject` (depuis `:cel-lsp`) — workspace LSP pour
  serveurs externes. Le host enregistre des `LanguageServerDefinition` et
  crée des `LspEditor` par fichier.

#### Providers implémentés par `:cel-lsp-java` (via le moteur `:lspjava`)

- `getCompletionProvider()` → `CompletionEngine.complete(...)` (technique du
  dummy identifier, scope symbols + member access via symbol solver)
- `getHoverProvider()` → `HoverService.hoverAt(...)` (signature + Javadoc)
- `getDiagnosticsProvider()` → `DiagnosticService.diagnose(...)` (erreurs de
  résolution + return-type mismatch)
- `getCodeActionsProvider()` → `CodeActionService.actionsFor(...)` (quick-fixes
  pour UNRESOLVED_*, RETURN_VALUE_IN_VOID, etc.)
- `getAnalyzer()` → analyzer no-op (l'éditeur a son propre SyntaxHighlighter)
- Les autres providers retournent `null` (pas encore supportés par `:lspjava`)

#### Providers implémentés par `:cel-lsp` (via LSP4J)

- Tous les providers sont implémentés dynamiquement selon les capabilities du
  serveur LSP : `completion`, `hover`, `signatureHelp`, `definition`, `rename`,
  `codeAction`, `documentHighlight`, `documentSymbol`, `formatting`,
  `diagnostics` (push via `publishDiagnostics`).

#### Limitations connues du moteur `:lspjava`

Documentées dans `lspjava-analysis.md` :
- `findNodeAt` est cassé (utilise `node.toString().length()` au lieu du range
  réel) — hover/completion peuvent retourner le symbole voisin.
- `findDefinition` retourne un range stub `[0, nameLength]` — go-to-definition
  désactivé (`getDefinitionProvider()` retourne `null`).
- `CodeActionService` retourne des `CodeAction` dont le `newText` est vide
  pour `quickfix.import` — l'action ne fait rien.
- Aucun support pour rename, formatting, signature help, documentSymbol,
  documentHighlight — ces providers retournent `null`.

#### Rollback v0.1.0.4

Suppression des modules `:lsp-java` et `:lsp-java-engine` (ajoutés en
v3.32.0 / app v0.1.0.4) — ils sont remplacés par la nouvelle architecture
modulaire `:cel-lsp` + `:cel-lsp-java` + `:lspjava`.

## [3.32.0] — 2026-08-24

### Façade LSP — module `:lsp-java`

Ajout d'un nouveau sous-module `:lsp-java` à la librairie code-editor qui sert
de façade LSP pour le langage Java. C'est le point d'entrée unique pour les
apps hôtes qui veulent brancher un backend LSP à l'éditeur.

#### Nouveaux modules

- **`:lsp-java-engine`** — module pure-Java (plugin `java-library`, sans
  Android) qui embarque le moteur d'analyse JavaParser + symbol solver
  (`jo.lspjava.*`). Considéré comme une entité indépendante de la librairie
  éditeur — il n'a aucune dépendance vers `:core`, `:lsp-api` ou `:ui`.
- **`:lsp-java`** — module Android library (`com.android.library`) qui
  implémente le `Language` SPI de l'éditeur en adaptant les types
  `jo.lspjava.api.*` vers `jo.codeeditor.lang.*`. C'est la seule couche qui
  connaît à la fois l'éditeur et le moteur Java.

#### Nouvelle API publique

- `jo.codeeditor.lsp.LspLanguageFacade.create(extension, filePath, projectRoot)`
  — factory statique qui route selon l'extension vers le bon backend LSP.
  Actuellement seul `"java"` est supporté (retourne un `JavaLanguage`) ; toutes
  les autres extensions retournent un `EmptyLanguage`.

#### Implémentation

- `jo.codeeditor.lsp.JavaLanguage implements Language` — adapte le moteur
  `jo.lspjava.engine.AnalysisEngine` au SPI éditeur. Providers implémentés :
  - `getCompletionProvider()` → `CompletionEngine.complete(...)`
  - `getHoverProvider()` → `HoverService.hoverAt(...)`
  - `getDiagnosticsProvider()` → `DiagnosticService.diagnose(...)`
  - `getCodeActionsProvider()` → `CodeActionService.actionsFor(...)`
  - `getDefinitionProvider()` → retourne `null` (le `findDefinition` du moteur
    lspjava est stubbé — retourne un range `[0, nameLength]` qui n'est pas
    utile)
  - `getAnalyzer()` → analyzer no-op (l'éditeur a son propre
    `SyntaxHighlighter` interne qui fait la coloration lexicale)

#### Conversion offset ↔ LSP

Contrairement à l'approche v0.1.0.3 qui utilisait des types LSP (line/col),
le moteur `lspjava` utilise des `TextRange` offset-based — exactement comme
le SPI éditeur. La conversion est donc directe (champ pour champ) sans
besoin de calculer les offsets depuis les positions LSP.

#### Limitations connues du moteur `lspjava`

Documentées dans `lspjava-analysis.md` :
- `findNodeAt` est cassé (utilise `node.toString().length()` au lieu du
  range réel) — hover peut retourner le symbole voisin.
- `findDefinition` retourne un range stub `[0, nameLength]` — go-to-definition
  désactivé.
- `CodeActionService` retourne des `CodeAction` dont le `newText` est vide
  pour `quickfix.import` — l'action ne fait rien.
- Aucun support pour rename, formatting, signature help, documentSymbol,
  documentHighlight — ces providers retournent `null`.

## [3.31.0] — 2026-08-10

### Découplage éditeur / preview XML

L'utilisateur a demandé que les modules éditeur et les modules de preview XML
soient deux entités distinctes. La librairie éditeur (`:ui`) ne doit plus
dépendre des modules de preview.

#### Réorganisation du projet

- **`editor/`** : tous les modules éditeur (`core`, `lsp-api`, `ui`, `cel-lsp`,
  `cel-lsp-java`, `cel-lsp-kotlin`, `cel-lsp-lua`, `app`)
- **`preview/`** : tous les modules de preview XML (`preview-api`,
  `preview-android`, `preview-panels`)
- **`layout-editor-app/`** : nouvelle application autonome LayoutEditor
- **Supprimés** : `preview-impl/` (inutilisé), `library/` (déprécié),
  `prebuilt/` (obsolète)
- `settings.gradle.kts` mis à jour avec les nouveaux chemins via
  `project(":name").projectDir = file("editor/name")`

#### Découplage :ui

- Retrait des dépendances `api(project(":preview-*"))` de `ui/build.gradle.kts`
- Création de l'interface `EditorPreviewHost` dans
  `editor/ui/.../view/EditorPreviewHost.java` — contrat entre l'éditeur et l'hôte
- Refactor de `EditorView.java` :
  - Nouvelle API `setPreviewHost(EditorPreviewHost)` / `getPreviewHost()`
  - `setFileName()` délègue à `previewHost.canPreview()`
  - `setPreviewMode()` notifie le host via `onPreviewModeChanged()` et
    `onPreviewContentChanged()`
  - Suppression du champ `xmlPreviewRenderer` (lié à l'ancien couplage)
- Refactor de `EditorRenderer.drawXmlPreview()` pour utiliser
  `host.drawPreview(canvas, offsetX, offsetY)`
- Suppression de `editor/ui/.../view/preview/` (XmlPreviewRenderer.java,
  AndroidRCanvas.java) — plus nécessaires

#### Bug fix — preview XML ne s'affichait pas

**Symptôme** : quand l'utilisateur ouvre un fichier `.xml` et active la
preview, rien ne s'affiche (écran vide).

**Cause racine** : `NativeXmlPreviewRenderer.updatePreviewAsync(xml, w, h, null)`
était appelée avec un callback `null`. L'inflation du XML se faisait sur un
thread en arrière-plan, mais à la fin de l'inflation, aucun
`View.invalidate()` n'était déclenché sur l'`EditorView`. L'`EditorView` avait
déjà terminé son cycle de dessin au moment où la View inflatée était prête.
Résultat : la preview restait invisible indéfiniment.

**Bug secondaire** : `MainActivity.isPreviewable()` n'incluait pas `.xml`
dans la liste des extensions previewable. Les icônes de preview dans la
Material Toolbar n'étaient donc pas affichées pour les fichiers XML.

**Fix** :
- Création de `XmlPreviewHost` (dans `editor/app/.../preview/`) qui implémente
  `EditorPreviewHost`
- Dans `onPreviewContentChanged()`, appelle
  `nativeRenderer.updatePreviewAsync(xml, w, h, () -> editorView.invalidate())`
  — le callback d'achèvement poste `invalidate()` sur le thread principal,
  forçant l'`EditorView` à se redessiner après chaque inflation
- Ajout de logs de debug (Tag: `XmlPreviewHost`) pour vérifier via logcat
- Ajout de `.xml` dans `MainActivity.isPreviewable()`
- Ajout de la méthode helper `isXmlFile()` pour distinguer XML (Canvas preview)
  de Markdown/HTML (WebView preview)

#### Nouvelle application LayoutEditor

Application mobile autonome — éditeur de layout visuel inspiré d'Android
Studio. Dépend UNIQUEMENT de `:preview-api`, `:preview-android`,
`:preview-panels` (PAS de `:ui`).

- **Module** : `layout-editor-app/`
- **MainActivity** : activité principale avec 3 panneaux + tree
- **UI** :
  - Gauche : `WidgetPaletteView` (palette de widgets drag-and-drop)
  - Centre : `EditText` pour éditer le XML + `PreviewSurfaceView` pour la preview
  - Droite : `PropertyInspectorView` (édition des propriétés)
  - Bas : `ComponentTreeView` (hiérarchie des composants)
- **Fonctionnalités** :
  - Édition XML en temps réel
  - Preview native via `NativeXmlPreviewRenderer` (LayoutInflater réel)
  - Drag-and-drop de widgets depuis la palette
  - Sélection et édition de propriétés
  - Vue arborescente des composants
  - Toggle Blueprint/Wireframe mode
  - Debounce 200ms sur les changements de texte
  - Callback d'achèvement d'inflation → `invalidate()` (même fix que l'app
    demo)

#### Phases 2-8 du LAYOUT_EDITOR_PLAN.md

Toutes les phases étaient déjà implémentées dans `:preview-panels` à v3.30.0.
La nouvelle app LayoutEditor les intègre :

- Phase 2 : `WidgetPaletteView` (palette drag-and-drop)
- Phase 3 : `PropertyInspectorView` (inspector de propriétés)
- Phase 4 : `ComponentTreeView` + `XmlTreeBuilder` (component tree)
- Phase 5 : `BlueprintColors` + `NativeXmlPreviewRenderer.setBlueprint()`
  (wireframe mode)
- Phase 6 : `ResourceResolver` (résolution @color/, @string/, @dimen/)
- Phase 7 : `DeviceProfile` (multi-screen preview)
- Phase 8 : `VisualEditHelper` (undo/redo pour modifications visuelles)

#### Tests

- 1042 tests passent, 0 échec
- Build des 2 APKs debug réussi :
  - `apk/app-debug.apk` (8.5 Mo) — app demo editor
  - `apk/layout-editor-app-debug.apk` (5.5 Mo) — app LayoutEditor

#### Fichiers modifiés

- `settings.gradle.kts` — nouveaux chemins modules + :layout-editor-app
- `build.gradle.kts` — version 3.30.0 → 3.31.0
- `editor/ui/build.gradle.kts` — retrait dépendances preview
- `editor/ui/.../view/EditorView.java` — refactor pour EditorPreviewHost
- `editor/ui/.../view/EditorRenderer.java` — utilise host.drawPreview()
- `editor/ui/.../view/EditorPreviewHost.java` — NOUVEAU (interface)
- `editor/app/build.gradle.kts` — ajout dépendances preview directes
- `editor/app/.../MainActivity.java` — enregistre XmlPreviewHost + fix .xml
- `editor/app/.../preview/XmlPreviewHost.java` — NOUVEAU (implémentation)
- `layout-editor-app/` — NOUVEAU module complet

#### Supprimés

- `preview-impl/` — module inutilisé (renderers Java custom)
- `library/` — module placeholder déprécié
- `prebuilt/` — anciens artefacts v1.0.6
- `editor/ui/.../view/preview/` — bridge vers preview-android (remplacé par
  EditorPreviewHost)

## [3.14.0] — 2026-08-09

### Bug 11 — Taper sur `{...}` doit déplier le fold

Le rapport de l'utilisateur disait : « quand c'est replié le `{...}` doit permettre de déplier quand on appuie dessus ».

- **Root cause** — Taper sur le placeholder chip `{...}` dans la zone de texte passait directement au placement du caret. Il n'y avait pas de détection de tap sur le chip pour déplier le fold. Seul le chevron dans le fold strip (gutter) dépliait le fold.

- **Fix** — `EditorInputHandler.handleTap()` détecte maintenant si le tap atterrit sur le placeholder chip d'une fold-start line. Si oui, `session.toggleFoldAtLine(line)` est appelé pour déplier/replier. La détection calcule la position X du chip (`prefixW` à `prefixW + placeW`) et vérifie si `x` est dans cet intervalle.

### Bug 12 — Caret non dessiné après un diagnostic ou après `{...}`

Le rapport de l'utilisateur disait : « une ligne qui a un diagnostic d'erreur quand j'appuie devant dans la ligne ça va le curseur se positionne normalement, mais après l'erreur sur la même ligne le curseur n'est pas positionné/dessiné, je constate le même problème quand j'appuie après le `{...}` ».

- **Root cause** — En v3.7.1 (Bug 6b), le tap target du popup diagnostic avait été élargi à TOUTE la ligne via `findDiagnosticAtLine()` en fallback. Si `findDiagnosticAt(offset)` ne trouvait pas de squiggle direct (tap avant/après le squiggle mais sur la même ligne), le fallback trouvait le diagnostic de la ligne et ouvrait le popup — empêchant le placement du caret. Résultat : sur une ligne avec diagnostic, taper AVANT le squiggle → caret placé ✓, taper APRÈS le squiggle → popup ouvert, caret non placé ✗.

- **Fix** — Le fallback `findDiagnosticAtLine()` est SUPPRIMÉ. Maintenant, seul un DIRECT squiggle hit (`findDiagnosticAt(offset)` retourne non-null, i.e. `offset ∈ [d.start, d.end]`) ouvre le popup. Taper ailleurs sur la ligne (avant/après le squiggle) tombe through au placement du caret normal. Le tap sur le dot diagnostic dans le gutter ouvre toujours le popup (ce path utilise `findDiagnosticAtLine` séparément).

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest` — **493 tests, 0 échec, 0 erreur**.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur (8.4 MB).

---

## [3.13.0] — 2026-08-09

### Bug 8 — Dot diagnostic agrandi

Le rapport de l'utilisateur disait : « Dans le gutterview agrandi un tout petit peu le dot pour diagnostics ».

- **Fix** — `GutterView.drawLineNumber()` : `radius` passé de `2.5dp` à `3.5dp`. Le dot reste solide filled circle, sans halo, pinned au bord gauche du gutter (centre à `5dp + dotR`).

### Bug 9 — GutterView glass/transparent

Le rapport de l'utilisateur disait : « Lorsque l'éditeurview scroll sous le gutterview, le gutterview doit devenir un petit peu transparent (glass..) pour entrevoir le contenu de l'éditeurview ».

- **Fix** — `GutterView.draw()` : le background du gutter passe de `theme.gutterBg` (opaque) à `applyAlpha(theme.gutterBg, 0.88f)` (88% alpha). Le texte qui défile derrière le gutter est maintenant faiblement visible — effet "frosted glass" similaire à VS Code. Les numéros de ligne restent parfaitement lisibles.

### Bug 10 — Squiggles diagnostic passent au-dessus du gutter

Le rapport de l'utilisateur disait : « Corrige aussi le squiggles diagnostics qui lorsque l'éditeur passe sous le gutterview on dirait que le squiggles passe au-dessus du gutterview au lieu des en-dessous ».

- **Root cause** — Dans `EditorRenderer.draw()`, les squiggles étaient dessinées à l'étape 8 (après `canvas.restore()`) SANS clipRect. Quand `hOffset > 0` (texte scrollé vers la droite), un squiggle à la colonne 0 avait `x1 = textAreaLeft - hOffset` qui pouvait devenir négatif, étendant le squiggle dans la zone du gutter — dessiné PAR-DESSUS le gutter au lieu d'être clippé derrière.

- **Fix** — `drawSquiggles()` est maintenant appelée À L'INTÉRIEUR du bloc `canvas.save() / canvas.clipRect(gutterWidth + 1, 0, effectiveWidth, height)` (le même clip que le texte). Les squiggles ne peuvent plus s'étendre dans la zone du gutter — elles sont proprement clippées derrière lui.

### Feature — Preview in-editor (Option B)

Le rapport de l'utilisateur disait : « Pour la preview si l'option B est la meilleure, alors commencer ». L'option B = EditorView + WebView overlay pour la preview Markdown/HTML directement dans l'éditeur (pas dans une Activity séparée).

- **EditorView API** — ajout de `PreviewMode` enum (`NONE`, `SPLIT`, `FULL`), `setPreviewMode()`, `getPreviewMode()`, `getPreviewLeft()`, `getPreviewWidth()`, `getEffectiveTextWidth()`, `OnPreviewModeChangedListener`. En mode SPLIT, l'éditeur réserve la moitié droite pour la preview et clippe son texte à la moitié gauche. En mode FULL, l'éditeur ne dessine que le background + gutter.

- **EditorRenderer** — le clipRect du texte utilise maintenant `effectiveWidth` (réduit en mode SPLIT) au lieu de `width`. Une ligne de séparation est dessinée à `x = width/2` en mode SPLIT.

- **MainActivity** — la preview est maintenant gérée in-editor : `launchPreview(mode)` appelle `editorView.setPreviewMode(SPLIT/FULL)` au lieu de lancer une Activity séparée. Un `WebView` overlay est créé comme sibling de l'EditorView, positionné aux bounds de la preview via `OnPreviewModeChangedListener`. Le contenu est rafraîchi via `refreshPreviewContent()` qui convertit le Markdown en HTML (via `MarkdownToHtml`) ou passe le HTML tel quel.

- **Avantages** :
  - La preview est dans l'éditeur, pas dans une Activity séparée.
  - L'éditeur clippe automatiquement son texte et dessine le divider.
  - Le host n'a qu'à créer un WebView et écouter le listener.
  - Le contenu est live — éditer le texte et re-toggler la preview met à jour le rendu.

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest` — **493 tests, 0 échec, 0 erreur**.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur (8.4 MB).

---

## [3.12.0] — 2026-08-09

### Refactorisation — Étape 5 (finale) : extraction de EditorKeyHandler

Dernière étape de la refactorisation d'EditorView. Cette version extrait **tout le handling des touches physiques** (hardware keys) dans une nouvelle classe `EditorKeyHandler`.

- **Avant** : `EditorView.java` = 2245 lignes (après étape 4).
- **Après** : `EditorView.java` = 2045 lignes (réduction de **200 lignes**, ~9%), `EditorKeyHandler.java` = 243 lignes (nouvelle classe).

#### EditorKeyHandler (243 lignes)

Extrait la méthode `onKeyDown` complète (~200 lignes) avec sa cascade de switches :
- **Popup navigation** — Up/Down/Enter/Tab/Escape pour completion, code actions, go-to-symbol, signature help, quick doc
- **Ctrl+shortcuts** — Z (undo), Y (redo), A (select all), C/X/V (clipboard), D (duplicate), F (find), S (save), Space (completion), P (signature help), . (code actions), O (go-to-symbol), +/-/0 (zoom)
- **Movement/editing keys** — Del, ForwardDel, Enter, arrows, Home/End, PageUp/Down, Tab, Space, F1 (quick doc), F2 (rename), Ctrl+G (go-to-line)
- **Printable character fall-through** — respecte Shift/AltGr via `unicodeChar`

EditorView.onKeyDown() ne fait maintenant que déléguer :
```java
@Override
public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (session == null) return super.onKeyDown(keyCode, event);
    if (keyHandler.onKeyDown(keyCode, event)) return true;
    return super.onKeyDown(keyCode, event);
}
```

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest` — **493 tests, 0 échec, 0 erreur**.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur (8.4 MB).
- Aucun changement de comportement.

### Récapitulatif complet de la refactorisation (étapes 1-5)

| Version | `EditorView.java` | Nouvelle classe | Réduction |
|---|---|---|---|
| v3.7.2 (original) | **5878 lignes** | — | — |
| v3.8.0 (étape 1) | 4282 | `EditorRenderer` (1789) | -1596 |
| v3.9.0 (étape 2) | 3483 | `EditorInputHandler` (753) | -799 |
| v3.10.0 (étape 3) | 2859 | `EditorPopupManager` (812) | -624 |
| v3.11.0 (étape 4) | 2245 | `EditorImeBridge` (492) + `EditorScrollManager` (151) | -614 |
| v3.12.0 (étape 5) | **2045** | `EditorKeyHandler` (243) | -200 |
| **Total** | **-3833 lignes (-65%)** | | |

### Architecture finale (7 classes)

```
EditorView (2045 lignes)
├── View lifecycle (constructors, onMeasure, onSizeChanged, onFocusChanged)
├── Public API (setSession, setLanguage, setTheme, setWordWrap, setFontScale, ...)
├── Session bridge (setLanguage wiring, diagnostics, find highlights)
├── Clipboard (copy, cut, paste, setClipboard)
├── Helpers (clamp, imm, spToPx, clampSelection, clampFontScale)
├── onDraw() → renderer.draw()
├── onTouchEvent() → inputHandler.onTouchEvent()
├── onCreateInputConnection() → imeBridge.onCreateInputConnection()
├── onKeyDown() → keyHandler.onKeyDown()
├── Popup API → popupManager.show*/dismiss*
└── Scroll API → scrollManager.scrollTo*/scrollBy/maxV/maxH

EditorRenderer (1789)         — étape 1 — all Canvas drawing (29 draw methods)
EditorInputHandler (753)      — étape 2 — touch/gesture/hit-test (17 methods)
EditorPopupManager (812)      — étape 3 — popup state + logic (44 methods)
EditorImeBridge (492)         — étape 4 — IME integration (InputConnection + ImeListener)
EditorScrollManager (151)     — étape 4 — scroll mechanics (7 methods)
EditorKeyHandler (243)        — étape 5 — hardware keys (onKeyDown)
```

EditorView est maintenant **65% plus petit** que l'original (5878 → 2045 lignes), avec une séparation claire des responsabilités en 7 classes. Les **493 tests passent sans aucune régression** — la refactorisation est purement mécanique, aucun changement de comportement.

---

## [3.11.0] — 2026-08-09

### Refactorisation — Étape 4 : extraction de EditorImeBridge + EditorScrollManager

Suite de la refactorisation d'EditorView. Cette version extrait **toute l'intégration IME** et **toute la mécanique de scroll** dans deux nouvelles classes.

- **Avant** : `EditorView.java` = 2859 lignes (après étape 3).
- **Après** : `EditorView.java` = 2245 lignes (réduction de **614 lignes**, ~21%), `EditorImeBridge.java` = 492 lignes + `EditorScrollManager.java` = 151 lignes.

#### EditorImeBridge (492 lignes)

Extrait toute l'intégration IME :
- `EditorInputConnection` (classe statique) — `BaseInputConnection` qui route chaque opération IME vers `EditorSession` (commitText, setComposingText, deleteSurroundingText, etc.)
- `Api34InputConnection` / `Api31InputConnection` — sous-classes API-spécifiques (replaceText API 34+, getSurroundingText API 31+)
- `EditorImeBridge` (classe top-level) — implémente `EditorSession.ImeListener`, pousse les callbacks session vers `InputMethodManager` (updateExtractedText, updateSelection, updateCursorAnchorInfo)
- `buildExtractedText()` — snapshot windowed pour l'IME
- `buildCursorAnchorInfo()` / `pushCursorAnchorInfo()` — position caret pour IMEs japonais/chinois
- `onCreateInputConnection(EditorInfo)` — configure l'EditorInfo et retourne la bonne sous-classe InputConnection

#### EditorScrollManager (151 lignes)

Extrait toute la mécanique de scroll :
- `scrollCaretIntoView()` — scroll vertical + horizontal pour garder le caret visible
- `scrollToLine(int)` / `scrollToOffset(int)` — navigation programmatique
- `scrollBy(float)` / `scrollHorizontallyBy(float)` — scroll programmatique
- `maxV()` / `maxH()` — bornes de scroll (content height - viewport height)

#### Architecture finale

EditorView délègue maintenant à 5 helpers :

```java
private final EditorRenderer renderer;          // étape 1 — drawing
private final EditorInputHandler inputHandler;   // étape 2 — touch/gesture
private final EditorPopupManager popupManager;   // étape 3 — popups
final EditorImeBridge imeBridge;                  // étape 4 — IME
final EditorScrollManager scrollManager;          // étape 4 — scroll
```

#### Champs rendus package-private

- `connectionGeneration`, `extractedTextMonitorToken`, `cursorAnchorMonitorMode`
- `MAX_EXTRACT_CHARS` (constante)
- `selectionListener`, `extraSelectionListeners`

#### Imports supprimés

8 imports IME supprimés d'EditorView (déplacés vers EditorImeBridge) : `Build`, `InputType`, `BaseInputConnection`, `EditorInfo`, `ExtractedText`, `ExtractedTextRequest`, `InputConnection`, `EditSpan`.

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest` — **493 tests, 0 échec, 0 erreur**.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur (8.4 MB).
- Aucun changement de comportement.

### Récapitulatif de la refactorisation (étapes 1-4)

| Version | `EditorView.java` | Nouvelle classe | Réduction |
|---|---|---|---|
| v3.7.2 (original) | **5878 lignes** | — | — |
| v3.8.0 (étape 1) | 4282 | `EditorRenderer` (1789) | -1596 |
| v3.9.0 (étape 2) | 3483 | `EditorInputHandler` (753) | -799 |
| v3.10.0 (étape 3) | 2859 | `EditorPopupManager` (812) | -624 |
| v3.11.0 (étape 4) | **2245** | `EditorImeBridge` (492) + `EditorScrollManager` (151) | -614 |
| **Total** | **-3633 lignes (-62%)** | | |

EditorView est maintenant **62% plus petit** que l'original, avec une séparation claire en 6 classes.

---

## [3.10.0] — 2026-08-09

### Refactorisation — Étape 3 : extraction de EditorPopupManager

Suite de la refactorisation d'EditorView. Cette version extrait **toute la gestion des popups** (completion, signature help, quick doc, code actions, go-to-symbol, go-to-line, rename, diagnostic popup, diagnostic sheet) dans une nouvelle classe `EditorPopupManager`.

- **Avant** : `EditorView.java` = 3483 lignes (après étape 2).
- **Après** : `EditorView.java` = 2859 lignes (réduction de **624 lignes**, ~18%), `EditorPopupManager.java` = 812 lignes (nouvelle classe).

#### Architecture

`EditorPopupManager` est une classe package-private qui détient une référence à `EditorView`. Elle possède :
- Toutes les méthodes `show*()` / `dismiss*()` / `hitTest*()` / `accept*()` pour les 7 popups
- La logique de filtrage completion (`filterCompletionItems`, `fuzzyMatches`, `builtinKeywordCompletions`)
- La logique signature help (`refreshSignatureHelp`, `triggerSignatureHelp`, `extractFunctionName`)
- La logique code actions (`refreshCodeActions`, `applySelectedCodeAction`)
- La logique go-to-symbol (`setGoToSymbolFilter`, `goToSymbolSelect`, `goToSymbolAccept`)
- Les popups go-to-line et rename (qui utilisent `android.widget.PopupWindow` + `EditText`)

L'état des popups (visibility flags, selected index, scroll offset, items lists) reste dans EditorView (package-private) — EditorPopupManager y accède via `view.fieldName`. Le drawing des popups reste dans `EditorRenderer` et lit le même état.

EditorView délègue maintenant toute son API popup :

```java
public void showGoToSymbol() { popupManager.showGoToSymbol(); }
public void dismissGoToSymbol() { popupManager.dismissGoToSymbol(); }
public boolean goToSymbolAccept() { return popupManager.goToSymbolAccept(); }
// ... 40+ méthodes déléguées
```

#### Méthodes déplacées (45 méthodes)

| Catégorie | Méthodes | Count |
|---|---|---|
| Completion | `setCompletionItems`, `isCompletionVisible`, `dismissCompletion`, `completionSelectUp/Down`, `completionAccept`, `refreshCompletion`, `refreshCompletionFromHost`, `completionPopupAnchor`, `filterCompletionItems` (deleted), `fuzzyMatches` (deleted), `builtinKeywordCompletions` (deleted) | 11 |
| Signature help | `refreshSignatureHelpFromHost`, `dismissSignatureHelp`, `refreshSignatureHelp`, `triggerSignatureHelp`, `extractFunctionName` (deleted) | 5 |
| Quick doc | `showQuickDoc`, `dismissQuickDoc` | 2 |
| Code actions | `refreshCodeActions`, `showCodeActions`, `dismissCodeActions`, `applySelectedCodeAction` | 4 |
| Go-to-symbol | `showGoToSymbol`, `dismissGoToSymbol`, `setGoToSymbolFilter`, `goToSymbolSelect`, `goToSymbolAccept` | 5 |
| Go-to-line | `showGoToLine`, `acceptGoToLineInput`, `dismissGoToLine`, `isGoToLineVisible`, `setGoToLineText` (@Deprecated), `acceptGoToLine` (@Deprecated) | 6 |
| Rename | `showRename`, `acceptRenameInput`, `dismissRename`, `isRenameVisible`, `setRenameText` (@Deprecated), `acceptRename` (@Deprecated) | 6 |
| Diagnostic | `showDiagnosticSheet`, `dismissDiagnosticSheet`, `isDiagnosticSheetVisible`, `showDiagnosticPopup`, `dismissDiagnosticPopup` | 5 |
| **Total** | | **44** |

#### Champs rendus package-private

- `completionProvider`, `signatureHelpResolver`, `codeActionsResolver`, `symbolResolver`
- `goToLineVisible`, `goToLineText`, `goToLinePopup`
- `renameVisible`, `renameText`, `renameStartOffset`, `renameEndOffset`, `renamePopup`
- `imeBridge` (field + inner class `EditorImeBridge`)

#### Méthodes rendues package-private

- `refreshCompletion()`, `refreshSignatureHelp()`, `triggerSignatureHelp()` (appelées par l'IME bridge et onKeyDown)
- `acceptGoToLineInput()`, `acceptRenameInput()` (appelées par les lambdas PopupWindow)

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest` — **493 tests, 0 échec, 0 erreur**.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur (8.4 MB).
- Aucun changement de comportement — la refactorisation est purement mécanique.

### Récapitulatif de la refactorisation (étapes 1-3)

| Version | `EditorView.java` | Nouvelle classe | Réduction |
|---|---|---|---|
| v3.7.2 (original) | **5878 lignes** | — | — |
| v3.8.0 (étape 1) | 4282 lignes | `EditorRenderer` (1789) | -1596 lignes |
| v3.9.0 (étape 2) | 3483 lignes | `EditorInputHandler` (753) | -799 lignes |
| v3.10.0 (étape 3) | **2859 lignes** | `EditorPopupManager` (812) | -624 lignes |
| **Total** | **-3019 lignes (-51%)** | | |

EditorView est maintenant **51% plus petit** que l'original, avec une séparation claire :
- `EditorView` — state + public API + IME + keys + scroll + session bridge
- `EditorRenderer` — all Canvas drawing (étape 1)
- `EditorInputHandler` — all touch/gesture/hit-test (étape 2)
- `EditorPopupManager` — all popup state + logic (étape 3)

---

## [3.9.0] — 2026-08-09

### Refactorisation — Étape 2 : extraction de EditorInputHandler

Suite de la refactorisation d'EditorView. Cette version extrait **tout le handling d'input tactile** (touch events, gesture detection, tap/long-press/drag dispatch, hit-testing, scroll/fling mechanics) dans une nouvelle classe `EditorInputHandler`.

- **Avant** : `EditorView.java` = 4282 lignes (après étape 1).
- **Après** : `EditorView.java` = 3483 lignes (réduction de **799 lignes**, ~19%), `EditorInputHandler.java` = 753 lignes (nouvelle classe).

#### Architecture

`EditorInputHandler` est une classe package-private qui détient une référence à `EditorView`. Elle possède :
- Les **détecteurs** : `OverScroller`, `ScaleGestureDetector`, `GestureDetector`, `VelocityTracker`
- L'**état tactile** : `isDragging`, `isScrolling`, `lastTouchX/Y`, `touchStartX/Y`, `wasPinching`, `completionScrolling`, `longPressTriggered`, `lastTapTime/X/Y`, `tapCount`
- Les **listener internes** : `ScaleListener` (pinch zoom), `GestureListener` (long-press)

EditorView délègue maintenant ses overrides View à l'input handler :

```java
@Override
public boolean onTouchEvent(MotionEvent event) {
    return inputHandler.onTouchEvent(event);
}

@Override
public void computeScroll() {
    inputHandler.computeScroll();
}

@Override
public boolean onGenericMotionEvent(MotionEvent event) {
    if (inputHandler.onGenericMotionEvent(event)) return true;
    return super.onGenericMotionEvent(event);
}
```

#### Méthodes déplacées (17 méthodes + 2 inner classes)

| Méthode | Rôle |
|---|---|
| `onTouchEvent(MotionEvent)` | Dispatch principal (ACTION_DOWN/MOVE/UP/CANCEL) |
| `computeScroll()` | Animation fling via OverScroller |
| `performClick()` | Accessibility |
| `onGenericMotionEvent(MotionEvent)` | Right-click → context menu |
| `startFling(float, float)` | Démarre le fling |
| `scrollByInternal(float, float)` | Scroll avec clamping |
| `handleTap(float, float)` | Tap → caret / popup / fold toggle |
| `handleLongPress(float, float)` | Long-press → word select + quick doc |
| `handleTouchDrag(MotionEvent)` | Drag → extension de sélection |
| `dragHandle(float, float)` | Drag de poignée de sélection |
| `hitTestHandle(float, float)` | Hit-test poignées start/end/caret |
| `hitTestCompletionPopup(float, float)` | Hit-test popup complétion |
| `hitTestCodeActionsPopup(float, float)` | Hit-test popup code actions |
| `hitTestGoToSymbolPopup(float, float)` | Hit-test popup go-to-symbol |
| `hitTestDiagnosticPopup(float, float)` | Hit-test popup diagnostic |
| `showSelectionToolbar()` / `dismissSelectionToolbar()` | Toolbar sélection |
| `handleSelectionToolbarTap(float, float)` | Tap Copy/Cut/Paste/All |
| `showEditorContextMenu(float, float)` | Menu contextuel (right-click) |
| `ScaleListener` (inner class) | Pinch zoom |
| `GestureListener` (inner class) | Long-press detection |

#### Champs déplacés vers EditorInputHandler

~20 champs d'état tactile : `isDragging`, `isScrolling`, `lastTouchX/Y`, `touchStartX/Y`, `wasPinching`, `completionScrolling`, `longPressTriggered`, `lastTapTime/X/Y`, `tapCount`, `scroller`, `scaleDetector`, `gestureDetector`, `velocityTracker`, + les constantes `TAP_SLOP_SQ`, `MULTI_TAP_TIMEOUT_MS`, `MULTI_TAP_SLOP_PX`, `FLING_VELOCITY_UNITS`.

#### Champs/méthodes rendus package-private dans EditorView

Pour que EditorInputHandler puisse y accéder via `view.fieldName` :
- **Champs** : `wantsKeyboard`, `quickDocResolver`, `fontScale`, `BASE_TEXT_SIZE_SP`, `HANDLE_TAP_RADIUS_DP`
- **Méthodes** : `onTextChanged()`, `maxV()`, `maxH()`, `clampFontScale()`, `spToPx()`, `lineHasDiagnostic()`, `findDiagnosticAt()`, `findDiagnosticAtLine()`, `showDiagnosticPopup()`, `imm()`

#### Ce qui reste dans EditorView

- View lifecycle (constructors, onMeasure, onSizeChanged, onDetachedFromWindow)
- Public API (setSession, setLanguage, setTheme, etc.)
- **IME integration** (onCreateInputConnection, EditorInputConnection) — étape 4
- **Hardware keys** (onKeyDown, onKeyShortcut) — étape 5
- **Scroll/zoom** (scrollCaretIntoView, maxV, maxH, setFontScale) — étape 4
- **Popup state management** (show/dismiss pour completion, quickDoc, codeActions, goToSymbol, diagnostic) — étape 3
- **Session/document bridge** — reste
- **Find/replace** — étape 5

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest` — **493 tests, 0 échec, 0 erreur**.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur (8.4 MB).
- Aucun changement de comportement — la refactorisation est purement mécanique.

### Récapitulatif de la refactorisation (étapes 1-2)

| Version | Classe | Avant | Après | Delta |
|---|---|---|---|---|
| v3.7.2 | EditorView.java | 5878 lignes | — | — |
| v3.8.0 | EditorView.java | 5878 | 4282 | -1596 (étape 1: EditorRenderer) |
| v3.9.0 | EditorView.java | 4282 | 3483 | -799 (étape 2: EditorInputHandler) |
| **Total** | | | | **-2395 lignes (-41%)** |

| Version | Nouvelle classe | Lignes |
|---|---|---|
| v3.8.0 | EditorRenderer.java | 1789 |
| v3.9.0 | EditorInputHandler.java | 753 |

---

## [3.8.0] — 2026-08-09

### Refactorisation — Étape 1 : extraction de EditorRenderer

`EditorView.java` était devenu trop gros (~5878 lignes) et mélangeait une dizaine de responsabilités. Cette version lance la refactorisation en extrayant **tout le pipeline de dessin** dans une nouvelle classe `EditorRenderer`.

- **Avant** : `EditorView.java` = 5878 lignes, ~29 méthodes `draw*` + `onDraw` orchestrator + input handling + IME + popups + scroll + session bridge + public API — tout dans un seul fichier.
- **Après** : `EditorView.java` = 4282 lignes (réduction de **1596 lignes**, ~27%), `EditorRenderer.java` = 1789 lignes (nouvelle classe).

#### Architecture

`EditorRenderer` est une classe package-private (`jo.codeeditor.view.EditorRenderer`) qui détient une référence à `EditorView`. Toutes les méthodes `draw*` ont été déplacées de EditorView vers EditorRenderer. `EditorView.onDraw()` ne fait maintenant qu'une seule chose :

```java
@Override
protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    renderer.draw(canvas);  // v3.8.0: all drawing delegated
}
```

#### Méthodes déplacées (29 méthodes)

| Méthode | Rôle |
|---|---|
| `draw(Canvas)` | Orchestrateur principal (était `onDraw`) |
| `drawCompositeFoldLine` | Ligne repliée (prefix + placeholder + suffix avec coloration syntaxique) |
| `drawFoldChevrons` | Chevrons de fold (style CodeAssist, filled triangle, r=3.2dp, alpha 0.8) |
| `drawInlayHints` | Indices en ligne (phantom text) |
| `drawSemanticTokens` | Overlay de tokens sémantiques (LSP) |
| `drawCachedSemSpans` | Spans sémantiques cachés par ligne |
| `drawCachedInlays` | Inlays cachés par ligne |
| `drawFindHighlights` | Surlignage des résultats de recherche |
| `drawStyledLine` | Ligne avec coloration syntaxique |
| `drawWrappedLine` | Ligne word-wrap (multi-rangs) |
| `drawSelection` | Bande de sélection |
| `drawCaret` | Curseur clignotant |
| `drawSquiggles` | Soulignages de diagnostics |
| `drawIndentGuides` | Guides d'indentation verticaux |
| `drawSelectionHandles` | Poignées de sélection (mobile) |
| `drawQuickDocPopup` | Popup de documentation rapide |
| `drawCodeActionsBulbs` | Ampoules de code actions |
| `drawCodeActionsPopup` | Popup de code actions |
| `drawGoToSymbolPopup` | Popup go-to-symbol |
| `drawCompletionPopup` | Popup de complétion |
| `drawDiagnosticChips` | Chips de diagnostics |
| `drawSelectionToolbar` | Barre d'outils de sélection |
| `drawDiagnosticPopup` | Popup sheet de diagnostic |
| `drawDiagnosticSheet` | Feuille de liste de diagnostics |
| `drawSignatureHelpPopup` | Popup d'aide signature |
| `getSquiggleColor` | Couleur par severity |
| `applyAlpha` | Helper alpha (utilisé par drawFoldChevrons) |
| `parseColorLiteral` | Helper color preview (#RRGGBB) |
| `getContrastColor` | Helper contraste texte/bg |
| `dp(int)` | Helper dp→px |

#### Champs rendus package-private

~40 champs de EditorView ont été rendus package-private (suppression du mot-clé `private`) pour que EditorRenderer puisse y accéder directement. Cela inclut : `session`, `metrics`, `theme`, `gutterView`, `vOffset`, `hOffset`, `wordWrap`, `wrapModel`, `renderCache`, `findHighlights`, `completionItems`, `signatureHelpData`, `codeActionsByLine`, `diagnosticPopupItem`, `caretAnimX/Y`, `bgPaint`, `textPaint`, `selPaint`, `caretPaint`, `squigglePaint`, `guidePaint`, etc.

#### Méthodes helper rendues package-private

Les méthodes utilitaires appelées à la fois par le code de dessin et par le code non-dessin ont été rendues package-private : `docLineToY`, `docLineForScreenY`, `countHiddenLinesAbove`, `rowsForDocLine`, `wrappedColFor`, `layoutForLine`, `collapsedFoldStartingAtLine`, `caretScreenPos`, `clamp`, `clampSelection`, `semanticTypeToTokenType`.

#### Ce qui reste dans EditorView

- View lifecycle (constructors, onMeasure, onSizeChanged, onDetachedFromWindow)
- Public API (setSession, setLanguage, setTheme, setWordWrap, setFontScale, etc.)
- Input handling (onTouchEvent, handleTap, handleLongPress, handleTouchDrag)
- IME integration (onCreateInputConnection, EditorInputConnection)
- Hardware keys (onKeyDown, onKeyShortcut)
- Scroll/zoom (scrollCaretIntoView, OverScroller, scale listener)
- Popup state management (show/dismiss/hitTest pour toutes les popups)
- Session/document bridge (setSession, onTextChanged, scheduleDiagnostics)
- Find/replace integration

Ces responsabilités seront extraites dans les étapes suivantes (EditorInputHandler, EditorImeBridge, EditorScrollManager, EditorPopupManager, etc.).

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest` — **493 tests, 0 échec, 0 erreur**.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur (8.4 MB).
- Aucun changement de comportement — la refactorisation est purement mécanique (déplacement de méthodes + accès aux champs via package-private).

---

## [3.7.2] — 2026-08-09

### Fold — Bug 7 : chevron trop gros + dot trop proche du line number (style CodeAssist)

Le rapport de l'utilisateur disait : « l'icône pour fold est trop gros et le dot est trop près de line number count, faite qu'il ressemble à celui de CodeAssist ».

- **Audit CodeAssist** — clonage du repo `tyron12233/CodeAssist` et lecture du source pour extraire les valeurs exactes :
  - Chevron : triangle **rempli** (pas stroked), `r = 3.2dp`, `alpha = 0.8f` sur `gutterCurrent`/`textSecondary`. Shape `▸` collapsed = `(cx-r*0.6, cy-r) → (cx+r*0.7, cy) → (cx-r*0.6, cy+r)`. Shape `▾` expanded = `(cx-r, cy-r*0.6) → (cx+r, cy-r*0.6) → (cx, cy+r*0.7)`. File: `EditorRendering.kt:531-543`.
  - Dot : cercle rempli, `radius = 2.5dp` (5dp diameter), center à `5dp + dotR` = **7.5dp du bord gauche du gutter** (PAS à côté du line number). Seulement errors (3) et warnings (2) — info n'a pas de dot. File: `EditorRendering.kt:430,445-451`.
  - Line-number right edge à `gutterWidth - foldStripWidth - 4dp` (à l'opposé du dot).

- **Fix EditorView.drawFoldChevrons()** — remplaçé le stroked triangle (1.4f stroke, r2 = foldStripWidth * 0.25 ≈ 3.5dp+) par un **filled** triangle avec `r = 3.2dp` et `applyAlpha(gutterText, 0.8f)`. Nouvelle méthode utilitaire `applyAlpha(int color, float alpha)` pour multiplier l'alpha d'un ARGB color.

- **Fix GutterView.drawLineNumber()** — le dot est maintenant **piné au bord gauche du gutter** (centre à `5dp + dotR` du bord gauche), PAS à côté du line number. `radius = 2.5dp` (au lieu de `0.35*charWidth` ≈ 3.5dp). Solid filled circle, **sans inner highlight halo** (CodeAssist dessine un plain circle). Seulement errors (3) et warnings (2) — info skip (matches CodeAssist).

- **Fix GutterView** — ajout d'un champ `density` (via `setDensity(float)`) car GutterView n'a pas accès au Context. EditorView appelle `gutterView.setDensity(getResources().getDisplayMetrics().density)` dans son constructeur.

### Feature — Preview pour fichiers Markdown (.md) et HTML (.html)

Le rapport de l'utilisateur disait : « Je veux ajouter 2 fonctionnalités additionnel, preview pour les fichiers markdown et html, si le fichier ouvert dans l'éditeur est un .md ou .html une petite icône devient visible et quand on appuie affiché la preview (dessine des icônes preview split vertical et full) ».

- **2 nouvelles icônes** dans la toolbar — `ic_preview_split.xml` (deux panneaux côte à côte avec divider) et `ic_preview_full.xml` (un œil). Visibles uniquement quand le fichier courant est `.md`, `.markdown`, `.html`, ou `.htm`. Masquées par défaut (`android:visible="false"` dans `toolbar_menu.xml`), toggled at runtime via `updatePreviewIconVisibility()` dans MainActivity.

- **PreviewActivity.java** — Activity dédiée qui héberge une `WebView` pour la preview. Layout : header (close button + filename + toggle mode button) + panneau source (split mode only) + WebView. Reçoit le contenu + filename + isMarkdown + mode via Intent extras. Toggle button permet de switch entre split et full mode à la volée.

- **MarkdownToHtml.java** — convertisseur Markdown → HTML pur Java, **sans dépendance externe** (pas de markwon, pas de commonmark-java). Support : ATX headings (`#`..`######`), paragraphs, bold (`**text**` / `__text__`), italic (`*text*` / `_text_`), inline code (`` `code` ``), fenced code blocks (``` ``` ``` ou `~~~`), block quotes (`> text`), bullet lists (`- item` / `* item`), ordered lists (`1. item`), horizontal rules (`---` / `***` / `___`), links (`[text](url)`), images (`![alt](url)`), HTML passthrough. CSS qui s'adapte au dark/light theme via `@media (prefers-color-scheme)`.

- **MainActivity** — `loadSample()` appelle maintenant `updatePreviewIconVisibility()` qui show/hide les 2 icônes preview selon l'extension du fichier. Tap sur une icône → `launchPreview(mode)` qui lance `PreviewActivity` avec le contenu actuel de l'éditeur + le mode (split ou full). Le contenu est récupéré live depuis `session.getText()` — donc la preview reflète les dernières éditions.

- **HTML sample** — ajout d'un sample `index.html` dans `SampleSnippets.java` (page avec CSS inline, card, button) + entrée `nav_html` dans le drawer menu. Démontre la preview HTML en passthrough (le HTML est rendu tel quel par la WebView).

- **AndroidManifest** — `PreviewActivity` enregistrée avec `android:exported="false"`.

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest` — **493 tests, 0 échec, 0 erreur**.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur (8.4 MB).
- Manual test recommandé :
  - Fold chevron : plus petit, filled, doux (alpha 0.8) — ressemble à CodeAssist.
  - Diagnostic dot : tout à gauche du gutter (pas à côté du line number), 5dp diameter, seulement errors/warnings.
  - Preview : ouvrez `README.md` ou `index.html` depuis le drawer → 2 icônes apparaissent dans la toolbar → tap → preview s'ouvre.
  - Toggle mode : dans la preview, tap sur l'icône toggle → switch entre split (source + preview) et full (preview seule).

---

## [3.7.1] — 2026-08-09

### Fold — Bug 4 : curseur ne peut pas aller après `{...}` sur la ligne repliée

Le rapport de l'utilisateur disait : « une fois replié par ex: `public int add(int a, int b) {...}` le curseur ne veut pas aller après `{...}` sur la ligne ». Taper après le marqueur `{...}` plaçait le caret à une position cachée (colonne à l'intérieur du corps du fold, invisible à l'écran) — le caret disparaissait visuellement.

- **Root cause** — `EditorView.offsetAt(x, y)` clamait `col` à la longueur TOTALE de la ligne doc (`doc.lineEnd - doc.lineStart`), ignorant que la ligne est repliée. Pour `public int add(int a, int b) { // body... }` (40 chars), le composite visible ne fait que `public int add(int a, int b) {...}` (~31 chars). Taper au-delà de la colonne 31 plaçait le caret sur les colonnes 32-40 qui sont cachées par le placeholder chip.

- **Fix** — `EditorView.offsetAt()` détecte maintenant si la ligne tapée est une fold-START line (`collapsedFoldStartingAtLine(line) != null`). Si oui :
  1. Calcule la longueur composite visible = `prefixEndCol + placeholder.length() + suffixLen`.
  2. Clare `col` à cette longueur composite (pas à la longueur doc).
  3. Remappe la colonne visible en offset doc :
     - `col ∈ [0, prefixEndCol]` → position dans le préfixe (offset normal).
     - `col ∈ [prefixEndCol, prefixEndCol + placeholder.length()]` → tapé sur le chip `{...}`, snap à `prefixEndCol` (juste avant le chip).
     - `col > prefixEndCol + placeholder.length()` → tapé après le chip, mappe à la END line (`fold.end + colInSuffix`), ce qui place le caret sur le `}` de fermeture ou dans le suffixe.

- **Fix complémentaire** — `EditorView.caretScreenPos()` (utilisé par `drawCaret`, les poignées de sélection, et `scrollCaretIntoView`) a été rendu fold-aware : le X du caret est maintenant calculé en fonction de la position composite visible (prefix + placeholder + suffix) plutôt que `col * charWidth` brut. Résultat : le caret se positionne correctement à l'écran, que le caret soit dans le préfixe, sur le chip, ou dans le suffixe.

- **`drawCaret`** utilise maintenant `caretScreenPos()` au lieu du calcul inline `col * charWidth` — le caret hérite automatiquement du comportement fold-aware.

- **`scrollCaretIntoView`** utilise aussi `caretScreenPos()` pour le scroll horizontal — quand le caret est sur une fold line, le scroll suit la position visible (pas la position doc brute).

### Fold — Bug 5 : texte en blanc quand replié (perte de la coloration syntaxique)

Le rapport de l'utilisateur disait : « une fois replié il n'y a pas de coloration le text est en blanc. Consulte le projet CodeAssist ils ont bien géré la coloration pendant le repli ». Le préfixe visible (`public int add(int a, int b)`) perdait tous ses colors de keywords/types quand la ligne était repliée.

- **Root cause** — `EditorView.drawCompositeFoldLine()` dessinait les trois parties (préfixe, placeholder, suffixe) avec un seul `theme.textColor`. Aucune coloration syntaxique n'était appliquée à la partie visible.

- **Fix** — `drawCompositeFoldLine()` applique maintenant la coloration syntaxique au préfixe ET au suffixe :
  - **Préfixe** : regarde le `StyledLine` de la `startLine` via `layoutForLine()`, walk ses `LineSpan` en clippant chaque span à `[0, prefixEndCol]`. Chaque span est dessiné avec `theme.colorForToken(span.type)`. Le support color-preview (`#RRGGBB`) est aussi porté pour parité avec `drawStyledLine`.
  - **Suffixe** : regarde le `StyledLine` de la `endLine`, walk ses spans en clippant à `[suffixStartCol, lastLine.length())`. Le X est décalé de `prefixW + placeW` pour s'aligner après le chip.
  - **Placeholder** : le chip background reste `theme.findMatch` mais le texte du placeholder (`...`) est maintenant en `theme.annotation` (plus dim) pour bien lire comme un chip.

- **Résultat** : `public int add(int a, int b) {...}` garde ses colors quand replié — `public`/`int` en keyword color, `add` en func color, `a`/`b` en param color, etc.

### Diagnostics — Bug 6a : GutterView n'affichait pas le dot rouge/jaune

Le rapport de l'utilisateur disait : « le gutterview est censé afficher un petit dot rouge à la ligne de l'erreur ou jaune si warning devant le line number count ». Le code était présent dans `GutterView.drawLineNumber()` mais `gutterView.setDiagnostics(int[])` n'était JAMAIS appelé — l'array restait vide, le dot n'était jamais dessiné.

- **Fix (EditorView)** — ajout de `pushDiagnosticsToGutter()` qui construit un array `int[lineCount]` de severity par ligne (max severity wins si plusieurs diags sur la même ligne) et appelle `gutterView.setDiagnostics(...)`. Appelé depuis :
  - Le `diagnosticsTask` lambda (après `session.setDiagnostics(legacy)`).
  - La branche else (quand le language n'a pas de DiagnosticsProvider — clear).
  - Une nouvelle méthode publique `notifyDiagnosticsChanged()` que les producteurs externes (LspEditor) appellent après `session.setDiagnostics(...)`.

- **Fix (LspEditor)** — `LspEditor.publishDiagnostics()` appelle maintenant `editorView.notifyDiagnosticsChanged()` au lieu de juste `editorView.invalidate()`, pour que le gutter reçoive l'array severity à chaque publication de diagnostics par le LSP server.

- **Fix (GutterView)** — `drawLineNumber()` dessine maintenant le dot **juste devant le numéro de ligne** (à sa gauche), pas dans la marge far-left. Le dot a un radius de `0.35*charWidth` avec un inner highlight blanc semi-transparent pour mieux "pop" visuellement sur les thèmes dark. Si le numéro de ligne est trop large pour laisser de la place, fallback à la position far-left (legacy).

### Diagnostics — Bug 6b : tap sur la ligne de diagnostic n'ouvrait pas le popup sheet

Le rapport de l'utilisateur disait : « aussi un petit popup sur la ligne de l'erreur, warning et quand on appuie dessus affiche le popup sheet avec les détails comme le fait bien CodeAssist ». Avant, il fallait taper EXACTEMENT sur le squiggle (`offset ∈ [d.start, d.end]`) pour ouvrir le popup — taper à côté sur la même ligne plaçait le caret.

- **Fix** — `EditorView.handleTap()` élargit la zone de tap :
  1. **Tap sur le dot dans le gutter** (left of line number, NOT in fold strip) → ouvre le popup sheet pour cette ligne.
  2. **Tap dans le fold strip** sur une ligne avec diagnostic (mais sans code actions) → ouvre le popup sheet.
  3. **Tap dans la text area** sur une ligne avec diagnostic (pas juste sur le squiggle) → ouvre le popup sheet. Le diagnostic précis est d'abord cherché via `findDiagnosticAt(offset)` (squiggle direct hit), puis via `findDiagnosticAtLine(line)` (fallback au premier diagnostic de la ligne, errors prioritaires).

- **Nouvelle méthode** `findDiagnosticAtLine(int line)` : retourne le diagnostic (severity max) qui START sur la ligne donnée. Errors > warnings > info.

- Le popup sheet lui-même était déjà implémenté (`drawDiagnosticPopup`) : header avec severity icon + label, message word-wrapped sur 4 lignes, quick-fix rows. Aucun changement nécessaire au popup — juste le trigger a été élargi.

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest` — 493 tests, 0 échec, 0 erreur.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur.
- Manual test recommandé :
  - Fold : tapez après `{...}` → le caret va sur le `}` de fermeture (visible).
  - Fold coloration : repliez `public int add(int a, int b) {...}` → le préfixe garde ses colors (keyword, type, func).
  - Gutter dot : lignes avec erreur affichent un dot rouge devant le numéro, warnings en jaune.
  - Tap popup : tapez n'importe où sur une ligne avec diagnostic → le popup sheet s'ouvre avec le message + quick-fixes.

---

## [3.7.0] — 2026-08-09

### Fold — Bug 1 (CRITIQUE) : texte inséré au mauvais offset sous un fold plié

Le rapport de l'utilisateur disait : « lignes 3-9 repliées, prochaine ligne
normalement c'est 10 → tape sur "ligne 10" → texte invisible → déplie → le texte
apparaît entre les lignes 3-9, plus précisément à la ligne 4 ». Le caret était
placé à l'intérieur du fold (sur une ligne cachée) et le texte inséré là devenait
invisible jusqu'au dépliage.

- **Root cause** — `EditorView.offsetAt(x, y)` est la fonction qui convertit un
  point tapé à l'écran en offset document. Elle a deux branches : une pour le
  mode word-wrap (qui utilisait déjà `docLineForScreenY(y)`, fold-aware) et une
  pour le mode non-wrap. La branche non-wrap calculait la ligne avec la formule
  brute `(contentY - padTop) / lineHeight` — qui **ignore les lignes cachées par
  les folds**. Quand des folds au-dessus du tap étaient pliés, la ligne
  retournée était décalée vers le haut du nombre de lignes cachées. Le caret
  atterrissait sur une ligne cachée (doc line 4 au lieu de doc line 10 avec
  lignes 3-9 pliées), le texte inséré était invisible jusqu'au dépliage.

- **Fix** — la branche non-wrap utilise maintenant `docLineForScreenY(y)` (qui
  marche pour le mode wrap ET non-wrap, et qui est fold-aware). C'est le même
  mapper que celui déjà utilisé par la branche word-wrap, par `handleTap()` dans
  le path fold-strip, et par les calculs de scroll. Toutes les conversions
  Y-écran → ligne-doc passent maintenant par une seule fonction fold-aware.

- **Test de régression** : avec des folds pliés, taper sur une ligne visible
  sous le fold insère le texte à la bonne ligne (visible immédiatement, pas
  dans le fold). La sélection par drag fonctionne aussi correctement autour
  des folds (les méthodes `handleTap`, `handleLongPress`, `handleTouchDrag`
  utilisent toutes `offsetAt()`).

### LSP — Bug 2 : tous les diagnostics en warning, aucun en erreur

Le rapport de l'utilisateur disait : « 15 diagnostics reçus (le pipeline LSP
fonctionne !) mais TOUS sont en severity 2 (warning), aucun en severity 3
(error) ». La cause : dans `MainActivity.connectLuaLsp()`, le push
`workspace/didChangeConfiguration` mettait TOUTES les inspections EmmyLua à
`"Warning"` — donc le serveur ne pouvait jamais publier de severity 1 (error).

- **Fix** — `MainActivity.java` différencie maintenant les severities selon le
  type d'inspection :

  | Inspection | Level | Pourquoi |
  |---|---|---|
  | `undeclaredVariable` | `"Error"` | variable non déclarée — bug bloquant |
  | `parameterValidation` | `"Error"` | arity/type mismatch — bug bloquant |
  | `assignValidation` | `"Error"` | assignation invalide — bug bloquant |
  | `fieldValidation` | `"Warning"` | mismatch de champ — non bloquant |
  | `deprecated` | `true` (boolean) | warning, mais activé (v3.5.0 déjà fix) |

  Le serveur EmmyLua accepte trois niveaux : `"None"`, `"Warning"`, `"Error"`
  (case-sensitive, voir `DiagnosticsOptions.kt`). Cette différenciation est
  volontairement dans l'app layer (`:app`), pas dans `:cel-lsp`, parce que la
  sémantique de sévérité est une décision EmmyLua-spécifique, pas une décision
  LSP-générique.

### LSP — Bug 3 : faux positifs sur les builtins Lua (`print`, `table`, `ipairs`, `setmetatable`...)

Le rapport de l'utilisateur disait : « Les diagnostics rapportent `Undeclared
variable 'setmetatable'`, `'table'`, `'ipairs'`, `'print'` — ce sont les
builtins Lua standard qui ne devraient PAS être flagués ».

- **Root cause** — le serveur EmmyLua ne connaissait pas les builtins Lua
  parce que les fichiers std library (`std/Lua53/*.lua` extraits des assets
  vers `getCacheDir()/std/Lua53/`) n'étaient pas référencés comme source root.
  Le `.emmyrc.json` avait `"source": ["."]` (workspace root uniquement) — les
  fichiers std étaient bien sur le disque mais hors de l'indexer du serveur.

- **Fix** — `MainActivity.connectLuaLsp()` écrit maintenant un `.emmyrc.json`
  avec `source` au format array-of-objects `{dir, exclude}` (forward-compatible
  avec de futures règles d'exclusion) listant à la fois le workspace root ET
  `std/Lua53` :

  ```json
  {
    "luaVersion": "lua5.3",
    "source": [
      {"dir": "."},
      {"dir": "std/Lua53"}
    ],
    "editor": {"completionCaseSensitive": false}
  }
  ```

  Après ce fix, l'indexer d'EmmyLua parcourt les fichiers std et enregistre
  les builtins (`print`, `pairs`, `ipairs`, `table.insert`, `string.format`,
  `math.floor`, `setmetatable`, `select`, …) comme symboles globaux
  disponibles — fini les faux positifs.

### LSP — vérification de l'agnosticisme de `:cel-lsp`

Le module `:cel-lsp` doit rester 100% générique — aucun code spécifique à un
LSP server particulier. Audit complet effectué :

- **Pas de hardcodage de server** — un grep `emmy|jdtls|pylsp|gopls|rust.analyzer`
  sur tout le module `:cel-lsp` ne trouve des références que dans des
  commentaires/javadoc (exemples d'usage), jamais dans du code qui branche
  sur le type de server. ✅

- **`DefaultLanguageClient.configuration()`** — gère le cas général
  (navigation dotted-path dans un JsonObject, ajouté en v3.5.0). Aucun
  format de settings spécifique hardcodé. ✅

- **`LspEditor.onTextChanged()`** — adapte maintenant le payload
  `textDocument/didChange` au `TextDocumentSyncKind` annoncé par le server :
  - `None` → didChange skippé.
  - `Full` (EmmyLua, beaucoup de servers légers) → texte complet sans range
    (comportement inchangé depuis v3.3.4).
  - `Incremental` (jdtls, kotlin-ls, pylsp, gopls, rust-analyzer) →
    `TextDocumentContentChangeEvent` avec un `Range` (full-doc range par
    défaut, puisque l'éditor ne track pas les per-keystroke ranges).
  La méthode ne ment jamais au server sur le type de sync.

- **`LspEditor.sendDidSave()`** — vérifie maintenant
  `capabilities.getTextDocumentSync().getSave()` avant d'envoyer didSave.
  Servers qui ne supportent pas didSave (pylsp older builds,
  cmake-language-server, …) → skipped proprement au lieu d'errored.

- **`LanguageServerWrapper.start()` — ClientCapabilities enrichies** — avant
  v3.7.0, seules `completion`/`hover`/`signatureHelp`/`publishDiagnostics`
  étaient annoncées. Ajouté en v3.7.0 :
  - **Workspace** : `applyEdit`, `workspaceEdit` (avec `documentChanges` +
    `resourceOperations` Create/Rename/Delete + `failureHandling`
    TextOnlyTransactional), `didChangeWatchedFiles`, `symbol`
    (workspace symbol).
  - **TextDocument** : `definition` (+`linkSupport`), `typeDefinition`,
    `implementation`, `declaration` (+`linkSupport`), `references`,
    `documentSymbol` (+`hierarchicalDocumentSymbolSupport` + `symbolKind`
    tous les kinds), `codeAction` (+`codeActionLiteralSupport` avec tous
    les `CodeActionKind` + `resolveProvider`), `documentHighlight`,
    `formatting`, `rangeFormatting`, `rename` (+`prepareSupport`),
    `foldingRange`, `selectionRange`, `callHierarchy`, `inlayHint`,
    `synchronization` (`didSave=true`, `willSave=false`,
    `willSaveWaitUntil=false`).
  - **Completion** : `completionItem` avec `snippetSupport=true` et
    `documentationFormat` markdown/plaintext.
  - **SignatureHelp** : `signatureInformation` avec `documentationFormat`.
  - **PublishDiagnostics** : + `versionSupport=true`.
  - **General** : `markup` markdown/plaintext.

  Toutes ces capabilities sont pure advertisement (aucune n'implique du
  code qui branche sur le server). Servers comme jdtls / kotlin-ls / pylsp /
  gopls / rust-analyzer qui implémentent ces features vont maintenant les
  activer pour nous — auparavant ils les skipaient silencieusement car on
  ne les avait pas annoncées.

- **`LanguageServerDefinition.getInitializationOptions()`** — reste
  override-friendly. Chaque server a son propre format (EmmyLua utilise
  `configFiles: [{uri, workspace}]`, jdtls utilise `bundles` + `settings`,
  pylsp utilise `configurationSources`, …). Le pattern (override anonyme
  côté app) est correct et inchangé. ✅

### Tests de régression

- `./gradlew :core:testDebugUnitTest :lsp-api:testDebugUnitTest :cel-lsp:testDebugUnitTest`
  — 493 tests attendus, 0 échec.
- `./gradlew :app:assembleDebug` — APK debug généré sans erreur.
- Manual test recommandé (sur device via `adb install app-debug.apk`) :
  - Fold : plie/déplie, tape sous un fold → le texte va au bon endroit.
  - Diagnostics : erreurs en rouge (severity 3), warnings en jaune
    (severity 2), pas de faux positifs sur `print`/`table`/`ipairs`/
    `setmetatable`/`pairs`/`select`/`string.format`/`math.floor`.

### Notes de migration

- Si vous étiez un consommateur de la librairie (`:ui` ou `:cel-lsp`) et que
  vous branchiez votre propre LSP server, vous n'avez **rien à changer** —
  l'API publique est inchangée. Les améliorations de capabilities et de sync
  kind sont automatiques.
- Si vous vouliez tirer parti du sync `Incremental` pour de gros fichiers,
  c'est désormais automatique : votre server annonce `Incremental` dans ses
  capabilities → l'éditor envoie des change events avec un range. Pour un
  vrai per-keystroke incremental (range granulaire), il faudrait étendre
  l'éditor pour tracker les diffs au niveau du `EditorSession` — pas fait
  en v3.7.0, prévu pour v4.0.0.

---

## [3.6.0] — 2026-08-09

### Fold — correction des phantoms visuels (le bug rapporté par l'utilisateur)

Le rapport de l'utilisateur disait : « lorsque j'appuie dessus l'éditeur n'est
pas nettoyé bien la partie du code est replié public...{....} il reste des
phantom du code replié ». Cinq bugs de rendu ont été trouvés et corrigés :

- **Bug 1 — `firstVisible`/`lastVisible` étaient des visual rows utilisés
  comme doc-line indices.** Après un pliage, la boucle de dessin n'itérait
  pas assez de lignes doc (les lignes visibles en bas du viewport n'étaient
  pas redessinées). **Fix** : quand des folds pliés existent, calculer la
  plage doc-line via `docLineForScreenY` (fold-aware), comme pour le
  word-wrap.

- **Bug 2 — `drawSelection` utilisait `paddingTop + i * lineHeight` (non
  fold-aware) au lieu de `docLineToY(i)`.** Les rects de sélection étaient
  dessinés à l'ancienne position Y des lignes cachées, laissant des bandes
  fantômes dans le gap du fold. **Fix** : remplacé par `docLineToY(i)` + skip
  des lignes cachées.

- **Bug 3 — `drawIndentGuides` même problème.** Les guides d'indentation
  verticaux étaient dessinés pour les lignes cachées à l'ancienne position.
  **Fix** : `docLineToY(i)` + skip.

- **Bug 4 — `drawFindHighlights`, `drawSquiggles`, `drawDiagnosticChips`,
  `drawInlayHints` même problème.** Tous utilisaient le Y non-fold-aware et
  ne skipaient pas les lignes cachées. **Fix** : `docLineToY(line)` + skip
  pour chacun.

- **Bug 5 — caret, ampoule code-actions, ancre popup code-actions
  utilaient le Y non-fold-aware.** **Fix** : `docLineToY(line)` partout.

Au total, **8 méthodes de dessin** ont été corrigées pour être fold-aware.
Aucune méthode de dessin n'utilise maintenant `paddingTop + X * lineHeight`
directement — tout passe par `docLineToY()` qui soustrait les lignes cachées.

### LSP — diagnostics Lua enfin fonctionnels (le bug rapporté par l'utilisateur)

Le rapport de l'utilisateur disait : « le problème des diagnostics à mon avis
c'est le module cel-lsp qui manque quelque chose parce pour les serveurs in
process tels que java les diagnostics apparaissent ». Une recherche
approfondie (lecture du code source d'EmmyLua-LS Java + comparaison avec
Sora Editor, VS Code, Neovim, Kate, Eclipse Theia) a révélé **quatre bugs**
distincts :

- **Bug 1 (root cause) — Race condition `setLspEditor` vs `sendDidOpen`.**
  Dans `LspEditor.connect()`, `sendDidOpen()` était appelé AVANT
  `setLspEditor()`. Le serveur EmmyLua publie les diagnostics
  **synchroniquement** dans son handler `didOpen` — la notification
  `publishDiagnostics` arrivait sur le socket (très rapide en LocalSocket)
  avant que `lspEditor` soit câblé dans `DefaultLanguageClient`, et était
  **silencieusement dropée** (`if (lspEditor != null)` → false). **Fix** :
  `setLspEditor()` est maintenant appelé AVANT `sendDidOpen()`.

- **Bug 2 — `deprecated` passé comme string au lieu de boolean.** Le serveur
  EmmyLua fait `path("emmylua.inspections.deprecated")?.asBoolean` — une
  string `"Warning"` retourne null pour `asBoolean`, laissant l'inspection
  `deprecated` désactivée. **Fix** : `deprecated` est maintenant un boolean
  `true` (le serveur le mappe à `Warning` level en interne).

- **Bug 3 — `didChange` ne déclenche PAS de diagnostics.** Le serveur Java
  EmmyLua-LS publie les diagnostics **uniquement** sur `didOpen` et
  `didSave` — PAS sur `didChange`. Sans `didSave`, taper une erreur de
  syntaxe ne produisait jamais de squiggle (le serveur ne ré-inspectait
  jamais le fichier après la frappe). **Fix** : `LspEditor.onTextChanged()`
  planifie maintenant un `didSave` debounced (800ms après la dernière
  édition). Le serveur ré-inspecte le fichier et publie des diagnostics
  frais.

- **Bug 4 — Aucun log de diagnostic avant le null check.**
  `DefaultLanguageClient.publishDiagnostics()` ne loggait rien si
  `lspEditor` était null, rendant le diagnostic de la race condition
  impossible. **Fix** : log ajouté AVANT le null check — chaque notification
  `publishDiagnostics` (même avec 0 diagnostics) est maintenant loggée dans
  le panneau LSP.

### Architecture LSP — recherche comparative (Sora, CodeAssist, VS Code, Neovim, Kate, Theia)

L'utilisateur a demandé de ne pas se limiter à Sora Editor et CodeAssist.
Voici les findings clés de la recherche :

- **Le serveur Java EmmyLua-LS est DÉPRÉCIÉ** (dernière release juillet
  2023, le README dit "Use EmmyLuaAnalyzer Please"). La version Rust
  (`emmylua-analyzer-rust`) est recommandée — 40+ règles de diagnostic
  activées par défaut, config via `.emmyrc.json` uniquement (pas de
  `didChangeConfiguration` push nécessaire). Recommandation v4.0.0+ :
  migrer vers la version Rust (cross-compile pour Android aarch64).

- **Le `.emmyrc.json` de la version Java NE contrôle PAS les diagnostics.**
  Il ne configure que `luaVersion`, `source`, `editor.completionCaseSensitive`.
  Les inspections sont contrôlées EXCLUSIVEMENT via `workspace/didChangeConfiguration`
  push avec des settings nested sous `emmylua.inspections.*`.

- **Toutes les inspections default à `None`** dans le code source Java
  (`DiagnosticsOptions.kt`). Sans push explicite, le serveur ne publie
  que les erreurs de syntaxe (`PsiErrorElement`) — pas les inspections
  sémantiques (undeclared variable, parameter validation, etc.).

- **Le serveur N'utilise PAS le modèle PULL** (`workspace/configuration`).
  Il utilise uniquement le modèle PUSH (`didChangeConfiguration`). Le fix
  v3.5.0 de `DefaultLanguageClient.configuration()` (navigation dotted-path)
  reste correct pour d'autres serveurs (gopls, rust-analyzer) mais n'est pas
  requis pour EmmyLua Java.

- **Sora Editor reçoit bien `publishDiagnostics` d'EmmyLua** sur Android —
  le mécanisme LocalSocket + LSP4J fonctionne. Notre bug était purement la
  race condition `setLspEditor` + l'absence de `didSave`, pas l'approche
  remote elle-même.

- **VS Code, Neovim, Kate, Theia** utilisent tous stdio (subprocess) par
  défaut. Le pattern socket est un fallback pour le remote/debug. Pour
  Android, le LocalSocket reste l'approche recommandée (pas de stdio
  disponible sans process fork).

### Build

- **versionCode** 30 → 31, **versionName** 3.5.0 → 3.6.0
- Toolchain : Temurin JDK 17.0.20+8, Gradle 8.5, AGP 8.2.0, Android 34.

## [3.5.0] — 2026-08-08

### Fold — correction complète du pliage (le bug rapporté par l'utilisateur)

Le rapport de l'utilisateur disait : « fold ne fonctionne pas, l'icône est
bien créé sauf que j'appuie dessus rien ne se passe ». Cinq bugs ont été
trouvés et corrigés dans la chaîne de pliage :

- **Bug 1 — le tap sur le chevron ne déclenchait jamais le toggle.**
  `onTouchEvent(ACTION_DOWN)` mettait `isScrolling = true` pour TOUT le
  gutter (numéros de ligne + fold strip). Sur `ACTION_UP`, `handleTap`
  n'était appelé que si `!isScrolling` — donc le chevron de fold, qui vit
  dans le fold strip du gutter, ne recevait jamais le tap. Le
  `toggleFoldAtLine(line)` dans `handleTap` était du code mort.
  **Fix** : `ACTION_DOWN` ne met `isScrolling = true` que pour la zone des
  numéros de ligne (hors fold strip). Un tap dans le fold strip arrive
  maintenant à `handleTap`, qui hit-teste l'ampoule code-actions puis le
  chevron de fold. Un drag qui démarre dans le fold strip flotte toujours
  vers le scroll via le test de slop du `ACTION_MOVE`.

- **Bug 2 — `docLineToY` ignorait les lignes cachées, laissant un trou
  visuel.** Quand un fold était plié, les lignes après le fold étaient
  toujours dessinées à leur Y original (basé sur le numéro de ligne doc),
  laissant un grand espace vide là où les lignes cachées auraient dû être.
  **Fix** : `docLineToY(line)` soustrait maintenant le nombre de lignes
  cachées au-dessus de `line` (méthode `countHiddenLinesAbove`). Les lignes
  après un fold plié remontent pour combler le trou.

- **Bug 3 — `docLineForScreenY` (tap → ligne) ignorait les folds.** Taper
  sous un fold plié sélectionnait la mauvaise ligne (le Y écran était
  converti en numéro de ligne doc sans tenir compte des lignes cachées).
  **Fix** : marche les lignes doc en sautant les cachées jusqu'à consommer
  le bon nombre de lignes visibles.

- **Bug 4 — `GutterView` n'était pas fold-aware.** Les numéros de ligne
  dans la gouttière restaient alignés sur les numéros doc, pas sur les
  lignes visibles — après un pliage les numéros étaient décalés par rapport
  au texte. **Fix** : `GutterView.setHiddenLineChecker(IntPredicate)` +
  chemin de rendu fold-aware qui saute les lignes cachées et compacte les
  numéros visibles. Câblé à `session.isLineFolded(line)` dans `EditorView`.

- **Bug 5 — un tap dans le fold strip sans fold/ampoule placait le caret.**
  `handleTap` tombait dans la logique de placement de caret après l'échec
  du toggle. **Fix** : `return` explicite après la section fold strip pour
  qu'un tap vide dans le fold strip ne fasse rien (pas de caret, pas de
  clavier).

### LSP — diagnostics enfin fonctionnels (le bug rapporté par l'utilisateur)

L'utilisateur disait : « la complétion et signature helper fonctionne mais
pas les diagnostics ». La cause racine : `DefaultLanguageClient.configuration()`
retournait `Collections.emptyList()` au `workspace/configuration` PULL.
EmmyLua utilise le modèle PULL (il demande ses settings au client au lieu
de juste écouter le push `didChangeConfiguration`) — sans réponse réelle,
toutes les inspections default à "None" → zéro diagnostic publié. La
complétion et le signature help marchaient parce qu'ils ne dépendent pas
de la config d'inspection.

- **`DefaultLanguageClient.configuration()`** — navigue maintenant le
  `section` demandé (chemin dotted, ex. `emmylua.inspections.undeclaredVariable`)
  dans l'objet settings stocké et retourne la valeur à ce chemin. Retourne
  l'objet settings complet si la section est null/vide, null si le chemin
  n'existe pas (le serveur doit tolérer null per spec LSP).

- **`DefaultLanguageClient.setSettings(Object)`** — nouveau setter pour
  stocker les settings poussés via `didChangeConfiguration`.

- **`LspEditor.sendDidChangeConfiguration(settings)`** — appelle maintenant
  `client.setSettings(settings)` en plus du push `didChangeConfiguration`,
  pour que les settings soient servies au pull suivant.

### Architecture LSP — note sur l'approche Sora (remote) vs CodeAssist (in-process)

L'utilisateur a demandé de vérifier si l'approche remote de Sora Editor
(LocalSocket vers un Service séparé) était la bonne, vs l'approche in-process
de CodeAssist. Analyse :

- **CodeAssist** (tyron12233) charge les LSP servers dans le même process
  JVM (même classloader) via `Launcher.createLauncher` avec des streams
  piped en mémoire. Pas de socket, pas de Service séparé, pas d'IPC.
- **Sora Editor** (et notre éditeur actuel) lance le LSP server dans un
  Service Android séparé et communique via `LocalSocket` (IPC kernel).
- **Le bug des diagnostics n'est PAS dû à l'approche remote** — les deux
  approches auraient eu le même bug de `configuration()` vide. L'approche
  remote ajoute cependant de la complexité (lifecycle du Service, timing
  du socket, leak si on oublie `disconnect()`) qui peut causer d'autres
  bugs.
- **Recommandation** : pour v3.6.0+, envisager de basculer Lua LSP en
  in-process (comme CodeAssist) — charger `LuaLanguageServer` directement
  dans le process de l'éditeur via LSP4J `Launcher.createLauncher` avec
  `PipedInputStream`/`PipedOutputStream`. Ça élimine le Service, le
  socket, et la fenêtre de timing "Step 2: Waiting 1s for service to open
  socket". Le LSP4J launcher + les providers SPI restent identiques — seul
  le `StreamConnectionProvider` change (piped streams au lieu de
  `LocalSocket`). Le `ProcessBuilderConnectionProvider` (conservé) reste
  pour les serveurs externes type jdtls.

### Code mort — suppression des 13 classes confirmées mortes

L'audit v3.3.10 avait listé 23 classes comme « entièrement mortes ».
Une vérification classe-par-classe a montré que **10 sur 23 ne devaient
PAS être supprimées** (types SPI ou APIs publiques documentées) — l'audit
était trop agressif. Les 13 classes réellement mortes ont été supprimées :

**Supprimées (core)** :
- `actions/CodeActionsController` (+ son test) — EditorView a son propre `codeActionsByLine`
- `completion/CompletionController` — EditorView a sa propre implémentation de popup
- `completion/CompletionPopup` — EditorView dessine le popup lui-même
- `find/FindReplaceController` — EditorView appelle `FindReplace` directement
- `engine/EditorEngineDaemon` — jamais instancié
- `highlight/SemanticHighlightStyles` — EditorView calcule les couleurs inline
- `util/EditorTextUtil` — toutes les méthodes publiques inutilisées
- `xml/XmlEditing` — jamais appelé

**Supprimées (ui)** :
- `view/EditorGeometry` — jamais importé
- `view/EditorInteraction` — EditorView inline ses propres champs
- `view/EditorRenderState` — EditorView utilise `LineRenderCache` directement
- `view/EditorOverlayLayers` — 8 classes internes jamais instanciées

**Supprimées (app)** :
- `demo/samples/DiagnosticsFactory` — jamais référencé

**Conservées (à tort flaggées par l'audit)** :
- `fold/FoldModel`, `fold/FoldedLineInfo`, `fold/FoldRegion` — API publique
  documentée dans README + FEATURES.md + `FoldModelTest`. Le view utilise
  `DiagnosticShift.FoldRegion` en interne, mais le package `fold.*` est une
  API publique séparée pour les consommateurs de la lib.
- `snippet/SnippetSession` — API publique documentée + `SnippetSessionTest`
- `lang/CodeBlock`, `lang/BracketMatch`, `lang/StyleReceiver` — types de
  retour/paramètres de l'interface `Analyzer` (SPI Language)
- `lsp/LspFeature` — type du champ public `LanguageServerDefinition.disabledFeatures`
- `lsp/ProcessBuilderConnectionProvider` — `StreamConnectionProvider` public
  pour spawn de LSP servers externes (jdtls, kotlin-language-server)
- `view/EditorLayoutManager` — instancié + `clear()` appelé par EditorView

### Build

- **versionCode** 29 → 30, **versionName** 3.4.0 → 3.5.0
- Toolchain vérifiée : Temurin JDK 17.0.20+8, Gradle 8.5, AGP 8.2.0,
  Android platform 34, build-tools 34.0.0.

## [3.4.0] — 2026-08-08

### Audit complet et corrections majeures

Un audit approfondi par 4 sous-agents spécialisés a identifié **~3,500 lignes
de code mort**, **6 providers SPI jamais câblés**, et le **popup diagnostic
manquant**. Toutes les corrections ont été appliquées dans cette session.

### Fixed — Popup diagnostic (le bug de l'utilisateur)
- **Tap sur squiggle → popup diagnostic** — `handleTap()` hit-teste maintenant
  les diagnostics. Si le tap atterrit sur un squiggle, un popup en bas de
  l'écran affiche le **message complet** + les quick-fixes (pattern CodeAssist
  `DiagnosticSheet`). Tap sur un quick-fix → applique l'action. Tap ailleurs →
  ferme le popup.
- **`findDiagnosticAt(offset)`** — cherche le diagnostic à l'offset donné
- **`showDiagnosticPopup(diag, offset)`** — ouvre le popup avec message + actions
- **`drawDiagnosticPopup(canvas)`** — dessine le popup (severity, message
  word-wrapped, quick-fixes)
- **`hitTestDiagnosticPopup(x, y)`** — hit-teste les rows de quick-fixes

### Fixed — BreadcrumbBar écrase le selection listener
- **`addOnSelectionChangedListener()`** — nouveau méthode dans EditorView qui
  ajoute un listener SANS remplacer le primaire. BreadcrumbBar utilise
  maintenant `add` au lieu de `set` → le status bar `Ln/Col` n'est plus gelé.

### Fixed — Clear-log button
- Le bouton Clear vide maintenant `lspLogBuilder` et `lspLogText` (avant:
  seul le panel était caché, les logs persistaient).

### Added — 5 providers LSP manquants dans LspLanguage
- **DefinitionProvider** — `server.definition()` → `List<DefinitionLocation>`
- **RenameProvider** — `server.rename()` → `RenameResult` avec TextEdits
- **CodeActionsProvider** — `server.codeAction()` → `List<CodeAction>`
- **DocumentHighlightProvider** — `server.documentHighlight()` → highlights
- **Formatter** — `server.formatting()` → formatted text

### Added — Capabilities logging
- Les 5 nouveaux providers vérifient les capabilities du serveur avant de
  se retourner (non-null si le serveur supporte la feature).

## [3.3.10] — 2026-08-08

### Fixed — Diagnostics never refreshed (the REAL rendering bug)
- **Root cause**: `EditorView.setLanguage()` ran the diagnostics provider
  ONCE (500ms after language was set) and NEVER again. `onTextChanged()`
  didn't call any diagnostics refresh — so typing a syntax error didn't
  show a squiggle. This affected BOTH Java (JavaParser, in-process) and
  Lua (EmmyLua LSP) — the problem was in the rendering layer
  (EditorView), not in the server communication.
- **Fix**: Stored the `DiagnosticsProvider` and a reusable
  `diagnosticsTask` as fields. Added `scheduleDiagnostics()` which
  cancels any pending run and re-posts after 600ms debounce.
  `onTextChanged()` now calls `scheduleDiagnostics()` so squiggles
  update as the user types.
- **Also**: when no diagnostics provider is available, `setLanguage`
  now clears the session's diagnostics (was: left stale diagnostics
  from the previous language).

### Impact
- **Java**: syntax errors from JavaParser now show red squiggles in
  real-time as you type (after 600ms debounce).
- **Lua**: EmmyLua diagnostics (if the server publishes them) now
  appear and update on every text change.
- **Both**: unused import warnings (Java) appear immediately.

## [3.3.9] — 2026-08-08

### Fixed — Diagnostics root cause: NESTED JSON, not flat dotted keys
- **THE BUG**: EmmyLua's `VSCodeSettings.update()` uses a `path()` method
  that splits keys by `.` and navigates the NESTED JSON tree. The settings
  JSON must be:
  ```json
  { "emmylua": { "inspections": { "undeclaredVariable": "Warning" } } }
  ```
  NOT flat dotted keys:
  ```json
  { "emmylua.inspections.undeclaredVariable": "Warning" }
  ```
  Using `addProperty("emmylua.inspections.undeclaredVariable", "Warning")`
  creates a SINGLE key with dots in the name, which `path()` can't navigate
  to — it tries `settings.get("emmylua")` and gets null. This is why
  diagnostics were never published despite sending `didChangeConfiguration`.
- **Discovered by**: decompiling `VSCodeSettings.path()` bytecode — it
  splits the key string by `"."` and iterates the parts, calling
  `JsonObject.get(part)` at each level.
- **Fix**: the app now builds a properly NESTED `JsonObject`:
  `settings → emmylua → inspections → undeclaredVariable → "Warning"`.

### Added — Server PULL request handlers
- **`workspaceFolders()` override** — EmmyLua may PULL workspace folders
  via `workspace/workspaceFolders` request. Now returns the project's
  workspace path. Same pattern as Sora Editor's `DefaultLanguageClient`.
- **`configuration()` override** — EmmyLua may PULL config via
  `workspace/configuration` request. Returns empty list by default
  (same as Sora Editor). Logs the request so we can see if the server
  tries to pull config.

## [3.3.8] — 2026-08-08

### Fixed — Architecture (server-agnostic library)
- **Removed `sendEmmyLuaDiagnosticsConfig()` from cel-lsp** — the library
  module (`cel-lsp`) must be server-agnostic. It should NOT know which
  language server is connected. Replaced with two generic methods:
  - `sendDidChangeConfiguration(Object settings)` — forwards any settings
    object via `workspace/didChangeConfiguration`
  - `sendDidChangeWorkspaceFolders(DidChangeWorkspaceFoldersParams)` —
    forwards workspace folder changes
- **EmmyLua-specific config moved to app layer** — the demo app's
  `MainActivity` now builds the `emmylua.inspections.*` JsonObject and
  calls `lspEditor.sendDidChangeConfiguration(settings)` after connect.
  This is where server-specific code belongs.

### Added — Lua std library (same as Sora Editor)
- **Shipped `std/Lua53/*.lua` in assets** — copied from Sora Editor's
  test project. These annotated Lua files tell EmmyLua about Lua's
  built-in functions (`print`, `pairs`, `table.insert`, `math`, `string`,
  etc.). Without them, EmmyLua can't resolve standard library calls.
- **Assets extracted to workspace on connect** — same pattern as Sora
  Editor's `unAssets()`: the std library files are copied from the APK
  assets to the workspace cache dir before connecting.

### Added — Workspace folders (same as Sora Editor)
- **`didChangeWorkspaceFolders` sent after connect** — same pattern as
  Sora Editor's `LspTestActivity`: after `CONNECTED`, the app sends
  `workspace/didChangeWorkspaceFolders` with the project path. The server
  uses this to register the workspace root and index source files.

## [3.3.7] — 2026-08-08

### Fixed — Diagnostics finally work! (root cause found)
- **Root cause: EmmyLua reads diagnostics from didChangeConfiguration,
  NOT .emmyrc.json** — reverse-engineered the EmmyLua JAR bytecode
  (com.tang.vscode.LuaLanguageServer) and discovered that the
  `DiagnosticsOptions` fields (`undeclaredVariable`, `fieldValidation`,
  etc.) are set by `LuaWorkspaceService.didChangeConfiguration` →
  `VSCodeSettings.update` → field setters. The settings use flat dotted
  keys like `emmylua.inspections.undeclaredVariable` with values
  `"None"` / `"Warning"` / `"Error"`. Without this notification, ALL
  inspection levels default to `None` and NO diagnostics are published.
- **Fix: `sendEmmyLuaDiagnosticsConfig()`** — called after `connect()`
  succeeds. Sends `workspace/didChangeConfiguration` with a JsonObject
  containing all `emmylua.inspections.*` keys set to `"Warning"`.
  Also sets `emmylua.typeSafety.*`, `emmylua.completion.*`,
  `emmylua.codeLens`, and `emmylua.hint.*`.
- **`.emmyrc.json` simplified** — removed the bogus `diagnostics`
  section (it was silently ignored by this EmmyLua build). Now contains
  only `luaVersion`, `source`, and `editor.completionCaseSensitive` —
  the only keys this server actually reads from the config file.

### Added — Workspace + capabilities
- **`workspaceFolders`** in initialize params — EmmyLua uses this to
  register the workspace root and resolve source files.
- **Workspace client capabilities** — `didChangeConfiguration`,
  `workspaceFolders`, `configuration` all set to true.
- **`publishDiagnostics` capability** — tells the server we support
  `relatedInformation`.

### Added — Hover on touch devices
- **Long-press triggers hover/quick-doc** — on touch devices there's no
  mouse hover, so long-press is the only way to see documentation. The
  long-press handler now calls `showQuickDoc(offset)` if a hover
  resolver is plugged in. Still selects the word + shows handles.

## [3.3.6] — 2026-08-08

### Fixed — Diagnostics not published by EmmyLua
- **Root cause: No config file** — EmmyLua requires a `.emmyrc.json`
  configuration file in the workspace to enable diagnostics. Without it,
  all inspection levels (`undeclaredVariable`, `fieldValidation`,
  `parameterValidation`, etc.) default to `None` and NO diagnostics are
  published. The demo now writes a `.emmyrc.json` to the cache dir before
  connecting, with all inspection levels set to `Warning`.
- **Config file passed via initializationOptions** — the `.emmyrc.json`
  URI is passed to EmmyLua's `ConfigurationManager` via the
  `configFiles` field of the `initializationOptions` JsonObject in the
  LSP `initialize` request. This is the mechanism EmmyLua uses to
  discover config files (discovered by analyzing the EmmyLua JAR bytecode).
- **LSP4J version mismatch** — the app was using LSP4J 0.21.0 while
  `cel-lsp` used 0.22.0. Aligned both to 0.22.0.

### Added — Server message logging
- **`window/logMessage` and `window/showMessage`** — the
  `DefaultLanguageClient` now logs these server-initiated messages to
  the in-app log panel (was: silently discarded). EmmyLua sends progress
  and diagnostic info via these channels.

## [3.3.5] — 2026-08-08

### Fixed — Completion popup UX
- **Completion filtering** — suggestions are now filtered client-side by the
  typed prefix, using case-insensitive prefix match + fuzzy subsequence
  (camel-hump). Re-ranks: exact match > case-insensitive exact > prefix >
  fuzzy. Ported from CodeAssist's `CompletionSession.filtered()`. Typing
  `end` now correctly shows `end` first, not `and`.
- **Enter accepts the wrong item** — the soft-keyboard Enter was bypassing
  the completion popup's selection logic. Both `commitText("\n")` and
  `performEditorAction` now check if the completion popup is visible and
  accept the SELECTED item (not the first) before inserting a newline.
- **Completion popup doesn't scroll** — the popup now supports drag-scroll.
  Touch-down inside the popup claims the gesture; drag moves scroll the
  list one row per row-height of movement. Tap (no movement) still
  selects + accepts the tapped row.
- **Popup stays open when tapping elsewhere** — tapping outside the popup
  now dismisses it (was: popup stayed visible until the user typed a
  non-identifier character or pressed Esc).

### Added — Completion caching
- **Client-side completion cache** — when the provider returns items for
  a token, the full set is cached. On subsequent keystrokes that extend
  the same token, the cached set is filtered locally — NO provider
  round-trip. This is the CodeAssist pattern: keeps the popup responsive
  on every keystroke even with a slow LSP server. The cache is
  invalidated when the caret moves to a different token, the prefix
  becomes empty, or the user accepts/dismisses the popup.

### Diagnostics
- **Lua sample now includes an intentional error** — `print(undefinedVariable)`
  at the end of the sample. This lets the user verify that EmmyLua
  publishes diagnostics and that the squiggle rendering works. The log
  panel will show `publishDiagnostics: 1 diagnostics received` and the
  editor will draw a red squiggle under `undefinedVariable`.

## [3.3.4] — 2026-08-08

### Fixed — LSP completion "Internal error"
- **Root cause #1: Invalid didChange range** — `onTextChanged` was sending
  `Range(Position(0,0), Position(Integer.MAX_VALUE, 0))` for Full sync.
  `Integer.MAX_VALUE` as a line number is invalid — the EmmyLua server
  tried to convert it to an offset, crashed internally, and returned
  "Internal error" on every subsequent completion request. `documentSymbol`
  survived because it re-parses from scratch, but `completion` needs the
  server's incremental state to be consistent. Fix: for Full sync, do NOT
  set a range at all — just send the full text as a single change event.
- **Root cause #2: Missing `initialized` notification** — the LSP spec
  requires the client to send `initialized` after the initialize handshake.
  Without it, many servers refuse to serve certain requests. Added
  `server.initialized(InitializedParams)` in `LanguageServerWrapper.start()`.
- **Root cause #3: Missing client capabilities** — the initialize request
  had no `capabilities` field, so the server didn't know what the client
  supports. Added `ClientCapabilities` with `CompletionCapabilities`,
  `HoverCapabilities` (markdown + plaintext), and `SignatureHelpCapabilities`.
- **Root cause #4: File not on disk** — EmmyLua (based on IntelliJ PSI)
  needs the file to exist on disk for its virtual file system indexing.
  Without it, `documentSymbol` works (uses didOpen text) but completion
  crashes. The demo now writes `calculator.lua` to the cache dir before
  connecting.

### Added
- **CompletionContext** — completion requests now include
  `CompletionContext` with `TriggerKind=Invoked`, so the server knows
  the user explicitly requested completion (vs. a trigger character).
- **Full error cause chain** — when a completion/hover/sigHelp request
  fails, the log now shows the full `caused by` chain, not just the
  top-level exception message. This makes it possible to see the actual
  server error message (e.g. `ResponseErrorException: Internal error`)
  instead of just `ExecutionException`.
- **didChange logging** — `didChange` notifications now appear in the
  LSP log panel (version + char count), so the user can verify text
  changes are being sent.

## [3.3.3] — 2026-08-08

### Fixed
- **XML multi-line tag highlighting** — `<LinearLayout` (with no `>` on the
  first line) now enters an `XML_TAG` lexer state, so attribute names on
  continuation lines (`xmlns:android=`, `android:layout_width=`) are
  properly colored as `PROPERTY` instead of falling back to `PLAIN`. Same
  fix applies to attribute values that span lines. Extracted
  `parseXmlAttributes` helper so the inline and cross-line paths share
  the same logic.
- **XML Enter indentation** — rewrote `EditOps.xmlNewline` using the
  CodeAssist `XmlNewlineHandler` approach (forward-scan stack of
  opening-line indents). Fixes three bugs:
  1. Self-closing tags (`<View/>|`) no longer trigger "deeper indent
     after `>`" — the next line stays at the same level.
  2. Closing tags (`</LinearLayout>|`) no longer trigger deeper indent.
  3. Wrapped attribute alignment now adds `currentIndent` so the cursor
     lands under the first attribute (was 4-8 columns too far left).
- **LSP setLanguage on UI thread** — `LspEditor.connect()` now calls
  `editorView.setLanguage()` via `editorView.post(...)`. Previously it
  ran on the LSP4J executor thread, racing with the UI thread's reads
  of the resolver fields.
- **LSP text-edit listener chaining** — added
  `EditorSession.addOnTextEditListener()` so the LSP didChange forwarder
  no longer replaces the demo's `updateStats` listener. `setOnTextEditListener`
  now clears + adds (preserves old single-listener semantics).

### Added
- **LSP log sink** — new `LspLogSink` interface + `LspProject.setLogSink()`.
  All LSP activity now flows to the demo's in-app log panel:
  - `didOpen` / `didChange` notifications (with version + char count)
  - Server capabilities (all 9 flags: completion, hover, signatureHelp,
    documentSymbol, definition, rename, codeAction, formatting, sync kind)
  - Each `completion` / `hover` / `signatureHelp` / `documentSymbol`
    request: "requesting...", "got N items", or "ERROR: ..." / "TIMEOUT"
  - `publishDiagnostics` events with severity + message per diagnostic
  - Connect / disconnect lifecycle with cause chain on failure
- **Copy Logs button** — the LSP log panel header now has a save icon
  (next to the close X) that copies the entire log to the clipboard.
  Essential for filing bug reports — the user can paste the full LSP
  trace instead of typing it from a screenshot.

## [1.0.8] — 2026-08-08

### Added
- **Fuzz tests** — `RopeFuzzTest` (40 cases) and `EditorSessionFuzzTest`
  (28 cases) verify random insert/delete sequences keep the engine's
  invariants consistent. Covers empty docs, single-char docs, surrogate
  pairs (emoji), 10K-char lines, 1000 newlines, batch-edit undo grouping.
- **Performance benchmarks** — `LineRenderCacheBenchmark` (6 cases) and
  `EditorSessionBenchmark` (7 cases) measure cache hit/miss latency, LRU
  eviction cost, `shiftKeys` rebuild, `buildColumnMaps`, triple-stamp
  validation overhead, `typeChar` on a 1000-line doc, undo/redo on a
  5000-line doc, `lineForOffset` binary search, `restyleAll`. All print
  timing to stdout.
- **Regression tests** — `V108RegressionTest` (9 cases) locks in each of
  the 6 bug fixes below so they can't silently come back.
- **`@since` Javadoc tags** on all 46 public classes, documenting which
  version each class was introduced in.
- **`CONTRIBUTING.md`** — dev setup, code conventions, PR process.
- **`LICENSE`** (MIT) + **`NOTICE`** (CodeAssist / OpenJDK / Android SDK
  attributions).
- **`USAGE.md`** — complete integration guide: Gradle setup, EditorView in
  a layout, themes, font size, word wrap, diagnostics, fold regions, inlay
  hints, semantic tokens, all 5 resolvers (completion, signature help,
  quick doc, code actions, go-to-symbol), keyboard shortcuts, ProGuard
  rules, troubleshooting.
- **`CHANGELOG.md`** (this file).
- **GitHub Actions CI** — `.github/workflows/ci.yml` runs the full test
  suite + APK build on every push and PR.
- **Demo app: Samples menu** — each v1.0.7/v1.0.8 feature has a dedicated
  sample button in the toolbar so consumers can see how to wire every
  resolver.

### Fixed
- **Bug 1: Completion accept did nothing on tap.** The completion popup
  had no hit-test — taps fell through to the text area. Added
  `hitTestCompletionPopup(x, y)` and route taps to `completionAccept()`
  when the popup is visible. Same fix applied to the code-actions and
  go-to-symbol popups.
- **Bug 2: Cursor invisible after undo/redo.** `EditorSession.undo()` and
  `redo()` didn't notify the `ImeListener`, so the View never restarted
  the caret blink or scrolled the caret into view. Both methods now fire
  `onTextChanged` + `onSelectionChanged`. Also added a
  `postInvalidateDelayed` heartbeat in `drawCaret` so the blink keeps
  ticking when the editor is otherwise idle.
- **Bug 3: Tap position off by ~5 chars.** `offsetAt` delegated to
  `metrics.xToCol` after already subtracting `padLeft + gutterWidth`,
  double-counting them. Rewrote the column math to compute directly from
  the text-area-relative X.
- **Bug 4: Caret "jumps" on every keystroke.** `scrollCaretIntoView`
  double-counted `padLeft` in the horizontal branch, making the
  right-margin scroll trigger too early. Rewrote the caret-screen-X
  computation to match the draw path exactly.
- **Bug 5: Selection toolbar (Copy/Cut/Paste/All) invisible.** The
  toolbar was only shown on explicit `showSelectionToolbar()` calls. Now
  auto-shown on double-tap (word select), triple-tap (line select), and
  long-press. Auto-dismissed on tap outside the selection or on edit.
- **Bug 6: Fold chevron overlapped the line number.** The gutter width
  didn't include the fold strip, so the chevron was drawn on top of the
  last digit. Restructured the gutter: `gutterWidth = lineNumberArea +
  foldStripWidth`, with line numbers right-aligned at the end of the
  line-number area and the chevron centered in its own dedicated column.

### Changed
- `EditorMetrics.gutterWidth` now includes the fold strip (was:
  line-number area only). The fold strip is a dedicated 2-char-wide
  column to the right of the line numbers.
- `EditorMetrics.foldStripWidth` increased from `1.5 * charWidth` to
  `2 * charWidth` for better chevron visibility.

### Tests
- **Total: 464 tests** (374 from v1.0.7 + 9 regression + 68 fuzz + 13
  benchmarks). 0 failures.

## [1.0.7] — 2026-08-07

### Added
- **Gap 1: Caret glide animation** — `ValueAnimator` + `OvershootInterpolator`
  for in-viewport caret moves (120ms). Snap for out-of-viewport jumps,
  edits, and word-wrap.
- **Gap 2: Signature help popup** — `SignatureHelpController` wired into
  `EditorView`. Anchored above the caret, active parameter in bold +
  `theme.func`. `Ctrl+P` = trigger. Fallback synthetic
  `"functionName(…) param N"` without a resolver. 18 tests.
- **Gap 3: Per-line render cache** — `LineRenderCache` triple-stamp
  validation (text rev + inlay rev + sem rev), LRU at 512 entries,
  `shiftKeys` via `OnLinesShiftedListener`. 11 tests.
- **Gap 4: Quick doc popup** — `setQuickDocResolver` + `showQuickDoc`.
  Hover desktop (API 24+) triggers after 500ms. 15 tests. Fixed
  `QuickDoc.inlineMarkup` off-by-one on `{@link target}`.
- **Gap 5: Code actions lightbulb** — yellow bulb in the gutter, popup
  anchored on the bulb. `Ctrl+.` = trigger. 14 tests.
- **Gap 6: Go-to-symbol** — `NavigationMenu.filter` + camel-hump
  subsequence. `Ctrl+Shift+O` = trigger. 16 tests.
- **Gap 7: Block editor API** — `setBlockMode/isBlockMode/getBlockEditor`.
  Full block rendering deferred to v1.0.8+.
- **Gap 8: EditorOverlayLayers** — 5 overlays: diagnostic chips, selection
  toolbar (Copy/Cut/Paste/All), go-to-line (`Ctrl+G`), rename (`F2`),
  diagnostic sheet.
- **Gap 9a-g: Bonus gotchas** — per-line revision stamps (Gap 9b),
  composing region cleared on completion accept (Gap 9e), mouse hover
  support (Gap 9g). G9h-j (right-click, cursor anchor info, surrounding
  text API 31+) deferred.

### Fixed
- `SignatureHelpController.resolve` — `dismissed` check was before
  call-change detection, preventing the dismissed flag from resetting
  when the caret moved to a different call.
- `QuickDoc.inlineMarkup` — off-by-one on `{@link target}` (used `i+5`
  instead of `i+6`, causing `StringIndexOutOfBoundsException`).

### Tests
- **Total: 374 tests** (300 from v1.0.6 + 74 new). 0 failures.

## [1.0.6] — 2026-08-06

### Added
- Word wrap mode (`setWordWrap`, `WrapModel` prefix-sum, wrap-aware
  `offsetAt` / `scrollCaretIntoView` / `maxV` / `maxH`).
- Selection handles (mobile — 2 draggable circles, tap-inside-selection
  deferred dismissal).
- SwiftKey auto-space handling (bundled + split shapes).
- Composing region left-gravity (`mapStart` for both ends).
- 11 new tests.

## [1.0.5] — 2026-08-05

### Added
- Fling scroll (`VelocityTracker` + `OverScroller.fling()` + `computeScroll`).
- Fold model wired (`toggleFoldAtLine`, chevrons ▾/▶, composite text,
  `expandFoldAt`).
- Completion popup (overlay Canvas, `CompletionProvider`, builtin keywords).
- Find highlights in viewport (`setFindHighlights`).
- Inlay hints rendering.
- Semantic tokens overlay.
- 23 new tests.

## [1.0.4] — 2026-08-04

### Fixed
- **A1**: Keyboard invisible on tap — `wantsKeyboard` flag armed only by
  user tap.
- **A2**: Pinch zoom broken — real `ScaleGestureDetector`, clamp
  `[0.6, 2.6]`, `requestLayout()` after `setTextSize`.
- **B1-B29**: 29 gaps from the CodeAssist comparative analysis (composing
  region replace-not-append, `setComposingText` refuses newlines,
  `restartInput` after smart-edit divergence, `replaceText` API 34,
  `deleteSurroundingTextInCodePoints`, `closeConnection` generation-guarded,
  `getExtractedText` monitor, `updateSelection` push, lineHeight from
  FontMetrics, clamp scroll on zoom, auto-scroll caret, long-press word
  select, double/triple tap, caret blink solid on edit, `runCatching` stale
  offsets, squiggle rendering, indent guides, hardware keys, extractedText
  windowing, `beginBatchEdit`/`endBatchEdit`, `performEditorAction`,
  `performContextMenuAction`, `EditorInfo.initialSelStart/End`,
  `setComposingRegion` honored, clipboard, `imeCaret` clamp).

## [1.0.3] — 2026-08-03

### Added
- Multi-module Gradle build (`:library` AAR + `:app` demo APK).
- `SyntaxHighlighter` (Java, Kotlin, XML, Markdown).
- `FindReplace` + `FindReplaceController`.
- `DiagnosticShift` (offset re-mapping).
- `SnippetSession` (tab stops, placeholders).
- `EditorSession` (undo/redo, IME bridge, smart edits).
- `Rope` (balanced binary tree, O(log N) per edit).
- `EditorDocument` (line-indexed text model).
- 246 initial tests.

[Unreleased]: https://github.com/jo/codeeditor-lib/compare/v1.0.8...HEAD
[1.0.8]: https://github.com/jo/codeeditor-lib/releases/tag/v1.0.8
[1.0.7]: https://github.com/jo/codeeditor-lib/releases/tag/v1.0.7
[1.0.6]: https://github.com/jo/codeeditor-lib/releases/tag/v1.0.6
[1.0.5]: https://github.com/jo/codeeditor-lib/releases/tag/v1.0.5
[1.0.4]: https://github.com/jo/codeeditor-lib/releases/tag/v1.0.4
[1.0.3]: https://github.com/jo/codeeditor-lib/releases/tag/v1.0.3

## [2.0.0] — 2026-08-08

### ⚠️ Breaking changes
- **New `Language` SPI** — replaces the v1.x per-feature resolver interfaces
  (`CompletionProvider`, `SignatureHelpResolver`, `QuickDocResolver`,
  `CodeActionsResolver`, `SymbolResolver`). The old resolvers are kept as
  deprecated wrappers. Migrate by implementing `Language` and calling
  `editorView.setLanguage(language)` instead of the individual setters.
- **Module structure** — prepares for `cel-lsp` (v2.2.0) and `cel-lsp-java`
  (v2.1.0) optional modules. The core `code-editor-lib` stays MIT-licensed
  and LSP-free.

### Added
- **`jo.codeeditor.lang` package** — the full Language SPI with 13 interfaces:
  `Language`, `Analyzer`, `StyleReceiver`, `CompletionProvider`,
  `CompletionPublisher`, `CompletionItem`, `HoverProvider`, `HoverContent`,
  `SignatureHelpProvider`, `SignatureHelp`, `Signature`, `Parameter`,
  `DefinitionProvider`, `DefinitionLocation`, `DiagnosticsProvider`,
  `Diagnostic`, `CodeActionsProvider`, `CodeAction`,
  `DocumentHighlightProvider`, `DocumentHighlight`, `InlayHintProvider`,
  `InlayHint`, `ViewZoneProvider`, `ViewZone`, `Formatter`, `SymbolProvider`,
  `Symbol`, `RenameProvider`, `RenameResult`, `TextEdit`, `CodeBlock`,
  `BracketMatch`.
- **`PopupCoordinator`** — manages z-order and dismissal of all editor popups
  (completion < signature help < hover < code actions < go-to-symbol <
  go-to-line < rename). Fixes Sora Editor's popup overlap bug (#725).
- **`ViewZone` API** — inline UI gaps between lines for inline refactors,
  inline type hints, and lightbulb quick-fixes. Sora Editor's most-requested
  missing feature (#787) — we add it from day one.
- **`EditorView.setLanguage(Language)`** — the new entry point for language
  intelligence. When a `Language` is set, the editor uses its providers for
  all features.
- **`EditorView.getPopupCoordinator()`** — access to the popup coordinator
  for custom popup management.
- 19 new tests (`PopupCoordinatorTest`, `LanguageSPITest`).

### Design decisions (vs Sora Editor)
- **`Language` SPI has all provider hooks in the core** — Sora's `Language`
  only has `getAnalyzer` + `requireAutoComplete`; hover/signature/code-actions
  are bolted on outside the SPI. Our SPI has optional methods for every
  feature, so the editor core can render them natively.
- **`PopupCoordinator` built-in** — Sora has no z-order coordination.
- **`ViewZone` from day one** — Sora is missing this (#787).
- **Rope document model** (vs Sora's `Content` line-array) — O(log N) edits,
  streaming search, no OOM on large files.
- **Per-line render cache** with triple-stamp validation — Sora uses
  `RenderNode` per line (we may adopt that in a future version for even
  better perf).

### Migration guide (v1.x → v2.0.0)

**Before (v1.x):**
```java
editorView.setCompletionProvider(myProvider);
editorView.setSignatureHelpResolver(mySigResolver);
editorView.setQuickDocResolver(myDocResolver);
editorView.setCodeActionsResolver(myActionsResolver);
editorView.setSymbolResolver(mySymbolResolver);
```

**After (v2.0.0):**
```java
Language myLanguage = new MyLanguage(); // implements all providers
editorView.setLanguage(myLanguage);
```

The old setters still work (deprecated) — they create an internal adapter
`Language` that delegates to the individual resolvers.

### Tests
- **Total: 483** (464 from v1.0.9 + 19 new). 0 failures.

### Roadmap
- **v2.1.0** — `cel-lsp-java` module: embedded Java analyzer via JavaParser
  (~600 KB). Completion contextuelle, signature help, go-to-symbol, quick
  doc, diagnostics basiques.
- **v2.2.0** — `cel-lsp` module: LSP4J integration + `cel-lsp-java` LSP
  adapter for remote jdtls via TCP.
- **v2.3.0** — `cel-lsp-kotlin` module: Kotlin support.

[2.0.0]: https://github.com/jo/codeeditor-lib/releases/tag/v2.0.0

## [2.1.0] — 2026-08-08

### Added
- **cel-lsp-java module** — embedded Java language intelligence via JavaParser.
  Completion (keywords + class members), hover (Javadoc), signature help,
  go-to-symbol, diagnostics (syntax errors + unused imports).
- 21 new tests.
- Total: 504 tests.

## [2.2.0] — 2026-08-08

### Added
- **cel-lsp module** — LSP4J-based LSP client with 3 connection providers
  (ProcessBuilder, TCP Socket, Android LocalSocket).
- `LspProject`, `LspEditor`, `LspLanguage`, `LanguageServerWrapper`.
- 9 new tests. Total: 513 tests.

## [2.3.0] — 2026-08-08

### Added
- **cel-lsp-kotlin module** — Kotlin embedded language support + LSP definition
  for kotlin-language-server.
- 20 new tests. Total: 533 tests.

## [3.0.0] — 2026-08-08

### ⚠️ Breaking changes
- Package rename: `com.codeeditor` → `jo.codeeditor`
- Module split: `:library` → `:core` + `:lsp-api` + `:ui`
- 533 tests, 0 failures.

## [3.1.0] — 2026-08-08

### Added
- **TokenType** expanded from 9 to 16 types (+OPERATOR, ESCAPE, LABEL,
  PROPERTY, VARIABLE, CONSTANT, REGEXP).
- **6 themes**: VS Code Dark+, VS Code Light+, Dracula, Atom One Dark,
  Monokai Pro, Solarized Dark.
- **SyntaxHighlighter** improved: escape sequences, operators, ALL_CAPS
  constants, properties, +3 new languages (JSON, Python, JavaScript).
- **Material Design 3 demo app** with navigation drawer, theme switcher,
  7 language samples, bottom status bar, FAB.

### Fixed
- SPI bridge: `setLanguage()` now connects ALL providers to the UI.
- Kotlin mode overwritten by Java in MainActivity.
- JavaAnalyzer.computeBlocks offset bug (column-only → lineStarts).

## [3.2.0] — 2026-08-08

### Added
- **Lua language support** — SyntaxHighlighter (keywords, builtins, comments,
  strings), cel-lsp-lua module (embedded completion, hover, signature help,
  symbols, diagnostics).
- **EmmyLua LSP server** bundled in app/libs/ — runs in-process via
  LocalServerSocket (same approach as Sora Editor).
- **Virtual keys** (SymbolBarView) — 38dp bar above keyboard with Tab,
  //, move line up/down, duplicate, + 28 code symbols.
- **Glass popup backgrounds** — translucent fills (alpha 0.86) + glass borders.
- **EmptyLanguage** — clears all stale resolvers when switching languages.

### Fixed
- Fold chevron visibility (CodeAssist pattern: collapsed always, open on caret line only).
- Caret glide: OvershootInterpolator → LinearInterpolator (no overshoot, 100ms).
- EditorView XML constructor `(Context, AttributeSet)` added.
- Handler null guard in `setLanguage()` diagnostics.

## [3.3.0] — 2026-08-08

### Added
- **FoldDetector** in `:core` — multi-language fold detection (brace, Lua,
  Python, XML, Markdown). Auto-called by `EditorSession.setLanguage()`.
- **Color preview** — `#RRGGBB` color codes display a colored swatch.
- **FoldDetector tests** — 15 tests covering all 5 language detectors.

### Fixed
- **CRITICAL**: `detectFolds()` bug — was modifying `foldRegions` instead of
  `folds` list, causing `indexOf` to return -1 and silently failing.
  Folds now work correctly: auto-detected on load, toggle on tap.
- LSP Lua JAR minimal strip — keeps `org/intellij/` PSI classes.
- `EditorSession.detectFolds()` preserves collapsed state across re-detection.

[2.1.0]: https://github.com/jo/codeeditor-lib/releases/tag/v2.1.0
[2.2.0]: https://github.com/jo/codeeditor-lib/releases/tag/v2.2.0
[2.3.0]: https://github.com/jo/codeeditor-lib/releases/tag/v2.3.0
[3.0.0]: https://github.com/jo/codeeditor-lib/releases/tag/v3.0.0
[3.1.0]: https://github.com/jo/codeeditor-lib/releases/tag/v3.1.0
[3.2.0]: https://github.com/jo/codeeditor-lib/releases/tag/v3.2.0
[3.3.0]: https://github.com/jo/codeeditor-lib/releases/tag/v3.3.0
