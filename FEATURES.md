# Code Editor — Matrice des fonctionnalités

Inventaire factuel des fonctionnalités de la bibliothèque, telles
qu'implémentées dans le code. Les chemins sont relatifs à
`editor/<module>/src/main/java/jo/codeeditor/`.

## Légende

| Symbole | Signification |
|---------|---------------|
| ✅ | Implémenté |
| 🧭 | Non implémenté — piste d'évolution (voir la section Perspectives) |

---

## 1. Cœur — Structure de données (`:cel-core`, `rope/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| Rope (arbre binaire équilibré) | ✅ | `rope/Rope.java` | Arbre immuable, O(log N) par édition |
| Rope.concat | ✅ | `rope/Rope.java` | Jonction avec coalescence des petites feuilles |
| Rope.replace | ✅ | `rope/Rope.java` | Remplacement O(log N + leafSize) |
| Rope.sub | ✅ | `rope/Rope.java` | Sous-séquence avec partage structurel immuable |
| Interface CharSequence | ✅ | `rope/Rope.java` | Accès O(log N) par index |

---

## 2. Document — Modèle de texte (`:cel-core`, `document/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| EditorDocument (index de lignes) | ✅ | `document/EditorDocument.java` | `lineStarts[]` splicé incrémentalement |
| replace() incrémental | ✅ | `document/EditorDocument.java` | Splice Rope + index en O(log N + lignes) |
| lineForOffset (recherche binaire) | ✅ | `document/EditorDocument.java` | O(log L) |
| text (matérialisation paresseuse) | ✅ | `document/EditorDocument.java` | Cachée par révision |
| isLarge (seuils) | ✅ | `document/EditorDocument.java` | 2,5 M chars / 50 k lignes |
| Selection (caret/plage) | ✅ | `document/Selection.java` | Curseur et plage avec normalisation |

---

## 3. Session — Moteur d'édition (`:cel-core`, `session/`)

`EditorSession` est le point d'entrée du moteur ; il délègue à des
collaborateurs spécialisés (`RestyleEngine`, `ImeBridge`, `CommentToggler`,
`UndoRecorder`, `SessionAnnotations`, `FoldRegions`, `UndoManager`).

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| replaceRange (point de mutation unique) | ✅ | `session/EditorSession.java` | Toute édition passe par ici |
| Batch editing (beginBatch/endBatch) | ✅ | `session/EditorSession.java` | Opérations multi-édit groupées |
| Undo/redo avec coalescence | ✅ | `session/UndoManager.java`, `session/UndoRecorder.java` | Frappe continue = 1 undo |
| commitText | ✅ | `session/EditorSession.java` | Insertion avec smart edits |
| typeChar (auto-close) | ✅ | `session/EditorSession.java` | Parenthèses, brackets, quotes |
| backspace (pair-aware) | ✅ | `session/EditorSession.java` | Supprime les deux brackets d'une paire vide |
| deleteForward (pair-aware) | ✅ | `session/EditorSession.java` | Suppression avant consciente des paires |
| indent/dedent | ✅ | `session/EditorSession.java` | Multi-lignes |
| toggleLineComment / toggleBlockComment | ✅ | `session/CommentToggler.java` | Commentaires pilotés par le langage |
| selectAll / selectWordAt / selectLineAt | ✅ | `session/EditorSession.java` | Sélections rapides |
| moveHorizontal/Vertical (Shift = étendre) | ✅ | `session/EditorSession.java` | Navigation caret |
| moveLineStart/End | ✅ | `session/EditorSession.java` | Smart Home/End |
| moveDocBoundary | ✅ | `session/EditorSession.java` | Ctrl+Home/End |
| duplicateSelection / duplicateLine | ✅ | `session/EditorSession.java` | Duplication |
| moveLines | ✅ | `session/EditorSession.java` | Déplacement haut/bas |
| deleteLines / joinLines | ✅ | `session/EditorSession.java` | Suppression / fusion |
| cutSelection | ✅ | `session/EditorSession.java` | Coupe vers presse-papiers |
| goToDiagnostic | ✅ | `session/EditorSession.java` | F8/Shift-F8 |
| Pont IME (commitText, composing, deleteSurrounding) | ✅ | `session/ImeBridge.java` | Intégration InputMethodManager |
| Listeners (onTextEdit, onSnippetEdit…) | ✅ | `session/EditorSession.java` | Hooks de modification |
| dispose() | ✅ | `session/EditorSession.java` | Libère le thread de restyle |
| Annotations par ligne (diagnostics, inlays, jetons sémantiques, plis) | ✅ | `session/SessionAnnotations.java` | Buckets mémoïsés par ligne de début |
| Régions de pli côté session | ✅ | `session/FoldRegions.java` | Index O(log folds) des lignes cachées |
| Restyle asynchrone incrémental | ✅ | `session/RestyleEngine.java` | Coloration hors thread UI, cascade stop-rule |

---

## 4. Édition intelligente (`:cel-core`, `edit/`)

`EditOps` est une façade statique ; la logique vit dans des collaborateurs
par responsabilité.

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| smartInsert (auto-close + balance) | ✅ | `edit/SmartTyping.java` (façade `edit/EditOps.java`) | Fermeture automatique des paires |
| smartBackspace (effondrement ligne vide) | ✅ | `edit/SmartTyping.java` | Paire vide, sensible à l'indent |
| smartDeleteForward | ✅ | `edit/SmartTyping.java` | Suppression avant intelligente |
| Smart Enter (Java) | ✅ | `edit/SmartNewline.java` | Case labels, découpage de chaînes, raw strings |
| Smart Enter (XML) | ✅ | `edit/XmlNewline.java` | Indentation structurelle, expansion de paire de tags |
| Smart Enter (défaut) | ✅ | `edit/SmartNewline.java` | Indentation de base + expansion de bracket |
| smartEnterCompleteStatement | ✅ | `edit/EditOps.java` | Ajoute `;` ou `{ }` si nécessaire |
| Paires de brackets | ✅ | `edit/BracketPairs.java` | Table ouvrant/fermant |
| Word boundaries (left, right, rangeAt) | ✅ | `edit/WordBounds.java` | Bornes de mot |
| detectIndentUnit / detectTabSize | ✅ | `edit/IndentDetection.java` | Tab vs 2/4/8 espaces |
| Contexte de langage | ✅ | `edit/CodeContext.java`, `edit/LanguageIds.java` | Familles de langage pour les smart edits |
| Utilitaires texte | ✅ | `edit/EditTextUtils.java` | Analyse de contexte d'appel, extra word chars |
| Syntaxe de commentaires | ✅ | `edit/CommentSyntax.java` | Ligne/bloc par langage |

---

## 5. Complétion et intelligence (`:cel-core`, `completion/`, `doc/`, `navigation/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| CompletionSession (cache + filtre) | ✅ | `completion/CompletionSession.java` | Camel-hump, ranking, filtre local |
| Badges de type de complétion | ✅ | `completion/CompletionKindBadge.java` | Pastilles keyword/function/variable… |
| SignatureHelpController | ✅ | `completion/SignatureHelpController.java` | Param info, Ctrl+P |
| QuickDoc (Javadoc/KDoc) | ✅ | `doc/QuickDoc.java` | Parsing markup, sections |
| NavigationMenu (go-to) | ✅ | `navigation/NavigationMenu.java` | Declaration, impl, type, super |

---

## 6. Recherche (`:cel-core`, `find/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| FindReplace (matching) | ✅ | `find/FindReplace.java` | Case, whole-word, regex |
| FindOptions / Match | ✅ | `find/FindOptions.java`, `find/Match.java` | Classes de données |
| matchIndexFrom (wrap) | ✅ | `find/FindReplace.java` | Premier match au caret |

---

## 7. Re-mapping de diagnostics (`:cel-core`, `shift/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| EditSpan (édition minimale) | ✅ | `shift/EditSpan.java` | Préfixe/suffixe commun |
| mapStart/mapEnd (gravité) | ✅ | `shift/DiagnosticShift.java` | Droite pour starts, gauche pour ends |
| shiftDiagnostics / shiftSemanticTokens | ✅ | `shift/DiagnosticShift.java` | Re-map après édition |
| shiftFoldRegions / shiftInlayHints | ✅ | `shift/DiagnosticShift.java` | Re-map après édition |

---

## 8. Snippets — Templates (`:cel-core`, `snippet/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| Tab stops (`$1`, `$2`, `$0`) | ✅ | `snippet/SnippetSession.java` | Navigation next/prev |
| Placeholders (`${1:défaut}`) | ✅ | `snippet/SnippetSession.java` | Texte par défaut |
| Stops liés (mirroring) | ✅ | `snippet/SnippetSession.java` | Édition synchronisée |
| Ré-ancrage sur édition | ✅ | `snippet/SnippetSession.java` | Survie aux modifications |
| parse() | ✅ | `snippet/SnippetSession.java` | Parse `$1`, `${1:placeholder}` |

---

## 9. Pliage de code (`:cel-core`, `fold/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| FoldRegion / FoldedLineInfo | ✅ | `fold/FoldRegion.java`, `fold/FoldedLineInfo.java` | Région pliable + info de rendu |
| FoldModel.build | ✅ | `fold/FoldModel.java` | Fusion d'intervalles |
| isHidden / foldStartingAt | ✅ | `fold/FoldModel.java` | Ligne cachée ? info de pli |
| docLineForVisual / visualForDocLine | ✅ | `fold/FoldModel.java` | Mapping document ↔ visuel |
| compositeText | ✅ | `fold/FoldModel.java` | Préfixe + placeholder + suffixe |
| Détection de plis par langage | ✅ | `highlight/FoldDetector.java` | Indentation, brackets, XML |

---

## 10. Retour à la ligne (`:cel-core`, `wrap/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| WrapModel (lignes par ligne) | ✅ | `wrap/WrapModel.java` | Compte de lignes wrappées |
| Prefix sum | ✅ | `wrap/WrapModel.java` | O(1) ligne → rangée |
| topRow / rowsOf / docLineForRow | ✅ | `wrap/WrapModel.java` | Mapping fold-aware |
| resize / setRows | ✅ | `wrap/WrapModel.java` | Mise à jour incrémentale |

---

## 11. Cache de rendu par ligne (`:cel-core`, `cache/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| InlayPiece (texte fantôme) | ✅ | `cache/LineRenderCache.java` | Inlays intercalés dans la ligne |
| SemSpan (sémantique) | ✅ | `cache/LineRenderCache.java` | Surbrillance sémantique |
| Entrée de cache (triple-stamp) | ✅ | `cache/LineRenderCache.java` | Invalidation par révision texte/inlay/sem, LRU 512 |
| shiftKeys | ✅ | `cache/LineRenderCache.java` | Miroir du splice de lignes |
| Prefetch idle hors viewport | ✅ | `cache/LineRenderCache.java` | ±1 viewport après 150 ms de calme, chunks de 8 |

---

## 12. Coloration syntaxique (`:cel-core`, `highlight/`)

`SyntaxHighlighter` est un orchestrateur léger ; la tokénisation vit dans
`highlight/tokenizer/`.

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| Coloration incrémentale par ligne | ✅ | `highlight/SyntaxHighlighter.java` | État de sortie + cascade stop-rule |
| Tokéniseurs intégrés (14) | ✅ | `highlight/tokenizer/` | CLike, Xml, Json, Python, Lua, Css, Shell, Yaml, Sql, Properties, Toml, Smali, Log, Markdown |
| Utilitaires de tokénisation | ✅ | `highlight/tokenizer/SpanUtils.java`, `highlight/tokenizer/KeywordTables.java` | Découpage de spans, tables de mots-clés |
| Interface TextMate optionnelle | ✅ | `highlight/TextMateTokenizer.java` | Point d'extension pour un module tm4e ; repli sur les tokéniseurs intégrés |
| Styles sémantiques | ✅ | `highlight/StyledLine.java`, `highlight/TokenType.java` | Jeton → type → couleur (thème) |

---

## 13. Langages (`:cel-core`, `languages/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| Registre de langages observable | ✅ | `languages/LanguageRegistry.java` | register/unregister/names/profiles |
| 27 langages intégrés | ✅ | `languages/BuiltinLanguages.java` | java, kotlin, javascript, typescript, c, cpp, go, rust, swift, dart, php, ruby, scala, groovy, python, lua, xml/html, css, json, yaml, sql, shell, properties, toml, smali, log, markdown |
| Profils de langage | ✅ | `languages/LanguageProfile.java` | family, alias, extensions, keywords, commentSyntax |
| Familles syntaxiques | ✅ | `languages/SyntaxFamily.java` | C_LIKE, XML, JSON, PYTHON, LUA, CSS, SHELL, YAML, SQL, PROPERTIES, TOML, SMALI, LOG, MARKDOWN |

---

## 14. Vue Android (`:cel-ui`, `view/` + sous-packages, `blocks/`)

`EditorView` (Android View) orchestre le cycle de vie et délègue à ~60
collaborateurs : `EditorRenderer` + 5 painters, `EditorInputHandler` + la
famille input, `EditorPopupManager` + les popups, géométrie, caches,
contrôleurs. Le rendu vit dans `view/render/`, les entrées dans
`view/input/`, les popups dans `view/popup/`, l'aperçu dans `view/preview/`
et les barres d'habillage dans `view/chrome/`.

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| EditorView (Canvas) | ✅ | `view/EditorView.java` | Orchestrateur : cycle de vie, listeners, relais |
| Renderer en couches | ✅ | `view/render/EditorRenderer.java` + `EditorTextPainter`, `EditorHighlightPainter`, `EditorDiagnosticsPainter`, `EditorAssistPopupPainter`, `EditorChromePainter` | 16 couches de dessin |
| Painter SPI (plugins de décorations) | ✅ | `view/render/EditorPainterHost.java`, `view/render/EditorDecorationPainter.java` | Un painter qui throw est retiré, pas de crash |
| Gutter (numéros + dots diagnostics) | ✅ | `view/chrome/GutterView.java` | Lignes + chevrons de pli + strip dédié |
| Bande de ligne courante, caret animé, sélection | ✅ | `view/render/CaretAnimator.java`, painters | Clignotement, poignées + loupe |
| Squiggles / chips / sheets de diagnostics | ✅ | `view/render/EditorDiagnosticsPainter.java` | Groupés par ligne, badge de compte |
| Popups (complétion, signature, quick doc, code actions, go-to-symbol, go-to-line, rename, références, NavMenu) | ✅ | `view/popup/EditorPopupManager.java` + classes `Editor*Popup` | Popups verre ancrés au caret |
| Entrée tactile | ✅ | `view/input/EditorInputHandler.java` + `EditorTouchScroller`, `EditorSelectionGestures`, `EditorTapResolver`, `EditorTouchHoverController` + `view/popup/EditorPopupHitTester`, `EditorContextMenuHandler` | Tap, multi-tap, drag-select, loupe, toolbar, hover |
| Clavier + keymap rebindable + chords | ✅ | `view/input/EditorKeyHandler.java`, `view/input/EditorKeymap.java` | Bind simple et séquences à deux touches |
| Pont IME | ✅ | `view/EditorImeBridge.java` | Composing, SurroundingText (compat API 24) |
| Scroll/fling/pinch-zoom | ✅ | `view/input/EditorScrollManager.java`, `view/input/EditorZoomController.java` | Suivi de vitesse, clamp de police |
| Minimap | ✅ | `view/render/EditorChromePainter.java` | Bande latérale, bascule publique |
| Aperçu XML | ✅ | `view/preview/EditorPreviewController.java`, `view/preview/EditorPreviewHost.java` | Split/full via hôte |
| Breadcrumb | ✅ | `view/chrome/BreadcrumbBar.java` | Barre de fil d'Ariane |
| Barre de symboles | ✅ | `view/chrome/SymbolBarView.java` | Symboles du document |
| Retour à la ligne / ligatures / non-imprimables | ✅ | `view/EditorView.java` | Bascules publiques |
| Thèmes | ✅ | `view/chrome/EditorTheme.java` | dark()/light() + 30+ couleurs custom |
| Métriques et géométrie | ✅ | `view/EditorMetrics.java`, `view/render/EditorPaintContext.java`, `view/EditorWrapGeometry.java`, `view/EditorHitMapper.java`… | lineHeight, charWidth, mapping coords |
| Cache de layouts (ligatures, contenu-adressé) | ✅ | `view/render/EditorShapedLayoutCache.java` | LRU 64, clé = texte de ligne |
| Édition par blocs | ✅ | `blocks/BlockEditor.java` | BlockNode, BlockType, BlockParser, BlockRenderer, SlotCompletion (classes imbriquées) |

---

## 15. LSP (`:cel-lsp-api`, `:cel-lsp`)

### SPI langage (`:cel-lsp-api`, `lang/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| Contrat Language | ✅ | `lang/Language.java` | SPI de langage |
| Analyzer (analyse asynchrone) | ✅ | `lang/Analyzer.java` | Moteur d'analyse injecté |
| Language vide / matching de brackets | ✅ | `lang/EmptyLanguage.java`, `lang/BracketMatch.java` | Valeurs par défaut |
| Coordination des popups / réception des styles | ✅ | `lang/PopupCoordinator.java`, `lang/StyleReceiver.java` | Contrats UI-agnostiques |
| Modèles de données (15) | ✅ | `lang/model/` | Diagnostic, CompletionItem, HoverContent, SignatureHelp, CodeAction, Symbol, InlayHint, TextEdit, RenameResult, DefinitionLocation, DocumentHighlight, ViewZone, CodeBlock, Parameter, Signature |
| Providers (17 interfaces) | ✅ | `lang/provider/` | Completion, Diagnostics, Hover, Definition, TypeDefinition, Implementations, SuperDefinition, References, Rename, Formatter, CodeActions, Symbol, SignatureHelp, InlayHint, DocumentHighlight, ViewZone, CompletionPublisher |

### Intégration LSP4J (`:cel-lsp`, `lsp/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| LspLanguage (adaptateur Language) | ✅ | `lsp/LspLanguage.java` | Orchestration des providers |
| Fournisseurs LSP (14) | ✅ | `lsp/Lsp*Provider.java` | Completion, Diagnostics, Hover, Definition, TypeDefinition, Implementations, SuperDefinition, References, Rename, CodeActions, Symbol, SignatureHelp, InlayHint, DocumentHighlight |
| LspEditor | ✅ | `lsp/LspEditor.java` + `LspDocumentSynchronizer`, `LspDiagnosticsManager`, `LspSemanticTokensPuller`, `LspFoldPuller`, `LspWorkspaceEditApplier` | Cycle de vie document ↔ serveur |
| LspProject | ✅ | `lsp/LspProject.java`, `lsp/LanguageServerWrapper.java`, `lsp/LanguageServerDefinition.java` | Gestion multi-serveurs, shutdown borné 2 s |
| Formateur / analyzer no-op | ✅ | `lsp/LspFormatter.java`, `lsp/LspNoopAnalyzer.java` | textDocument/formatting |
| Providers de connexion (5) | ✅ | `lsp/connection/` | Stream, InProcess, LocalSocket, ProcessBuilder, Socket |
| Client par défaut / logs | ✅ | `lsp/DefaultLanguageClient.java`, `lsp/LspLogSink.java`, `lsp/IoCompat.java` | Pont client→éditeur, compat fichiers API 24 |

---

## 16. Extensibilité

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| Langages contribuables | ✅ | `languages/LanguageRegistry.java` | `register(LanguageProfile)` — coloration immédiate |
| Keymap rebindable + chords | ✅ | `view/input/EditorKeymap.java` | `bind()` / `bindChord()` |
| Plugins de décoration | ✅ | `view/render/EditorPainterHost.java` | `register(EditorDecorationPainter)`, chargement classpath |
| Aperçu hôte | ✅ | `view/preview/EditorPreviewHost.java` | Contrat canPreview/drawPreview/hitTest |
| Sweep des onglets ouverts | ✅ | `view/OpenTabDiagnosticsSweep.java` | Rafraîchissement différé des diagnostics |

---

## Perspectives — édition multi-curseur 🧭

**Non implémentée.** Le plan de conception suivant a été étudié et conservé
comme piste d'évolution ; l'état actuel du code gère une seule `Selection`
par session.

### Vue d'ensemble

L'édition multi-curseur permet à l'utilisateur d'avoir plusieurs curseurs
actifs simultanément ; chaque frappe est appliquée à tous les curseurs en
parallèle.

### Bénéfices escomptés

- **Sur mobile** : éditer N occurrences en une seule frappe (gain important
  sur clavier virtuel).
- **Sur tablette/DeX** : Cmd+Click pour ajouter un curseur (expérience
  desktop-like).
- **Refactoring express** : changer `private` → `public` sur 10 champs d'un coup.

### Architecture envisagée

1. **`EditorSession`** — remplacer `Selection` par une `List<Selection>` +
   un index de sélection primaire ; toutes les opérations d'édition sont
   re-ancrées sur la collection.
2. **Fan-out dans la couche édition** — appliquer les `replaceRange` en tri
   descendant d'offset (pour ne pas décaler les curseurs suivants), le tout
   dans un `beginBatch/endBatch` pour un seul undo.
3. **Entrée tactile** — tap simple : remplace les sélections ; appui
   long + drag : ajoute une sélection.
4. **Rendu** — `drawCarets()` itère sur toutes les sélections ; chaque caret
   a son état de blink décalé, le caret primaire se distingue visuellement.
5. **Pont IME** — l'IME ne voit que la sélection primaire ; les autres
   curseurs sont mis à jour silencieusement.
6. **API publique** — `addCursor(offset)`, `removeCursor(index)`,
   `clearCursors()`, `getSelections()`, `getCursorCount()`.

### Prérequis

- Stabiliser l'intégration LSP.
- La refactorisation des anciennes god classes (`EditorView`,
  `EditorRenderer`, `EditorSession`…) en collaborateurs — désormais
  effective — facilite l'intégration de ce plan.

### Références

- VS Code : Cmd+Click (desktop), Alt+Click (alternative)
- IntelliJ IDEA : Cmd+G (ajouter une sélection à l'occurrence suivante)
- Sublime Text : clic milieu + drag (sélection colonne)

---

## Statistiques

| Indicateur | Valeur |
|------------|--------|
| Fichiers source | 205 (`:cel-core` 68 · `:cel-lsp-api` 38 · `:cel-lsp` 36 · `:cel-ui` 63) |
| Fichiers de test | 67 |
| Lignes de code (main) | ≈ 37 000 |
| Tests unitaires | 934 (`:cel-core` 655 · `:cel-lsp-api` 22 · `:cel-lsp` 27 · `:cel-ui` 230) |
| Langages colorés intégrés | 27 |
| Tokéniseurs intégrés | 14 (+ utilitaires SpanUtils, KeywordTables) |
| Interfaces de providers LSP | 17 |
| Providers de connexion LSP | 5 |
