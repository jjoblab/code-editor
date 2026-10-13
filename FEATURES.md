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
| Surlignage des occurrences | ✅ | `view/EditorView.java` (`setFindHighlights`), `view/render/EditorHighlightPainter.java` | Bandes wrap-aware, teintes `findMatch`/`findCurrent` ; B10 : purgées au changement de session (offsets périmés) |
| Barre de recherche/remplacement | ✅ | `view/chrome/EditorSearchBar.java` + `EditorSearchController.java` | B10 : requête vivante (frappe + édition + session ré-accrochée), compte « 3/17 », navigation bouclante caret-suivante, remplacement courant (groupes `$1` en regex, repli littéral) et TOUT en une passe (une entrée d'annulation) ; ouverture menu/`Ctrl+F`, amorçage depuis la sélection, requête survivante à la fermeture |

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
| Détection de plis par langage | ✅ | `highlight/FoldDetector.java` | Indentation, brackets, XML ; B14 : régions nommées avec marqueur ESPACÉ ou NON (`//region`, `#region` C#) + correctif de l'off-by-one du nom de région (la chip porte le VRAI nom) |
| Plis par niveau + tout replier/déplier (B14) | ✅ | `session/FoldRegions.java` (collapseAtLevel/collapseAll/expandAll), `session/EditorSession.java` | P1-5 : « Replier au niveau N » — profondeur d'imbrication EXACTE (1 = classes, 2 = méthodes, 3 = blocs ; les parents restent ouverts, chaque région porte son état — sémantique VS Code Fold Level N) ; « tout replier » / « tout déplier » avec retour compté ; foldRev++ → l'index mémoïsé de la vue se reconstruit sans re-pose de session |

---

## 10. Retour à la ligne (`:cel-core`, `wrap/`)

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| WrapModel (lignes par ligne) | ✅ | `wrap/WrapModel.java` | Compte de lignes wrappées |
| Prefix sum | ✅ | `wrap/WrapModel.java` | O(1) ligne → rangée |
| topRow / rowsOf / docLineForRow | ✅ | `wrap/WrapModel.java` | Mapping fold-aware ; B8 : clamp de queue — toute ligne au-delà du compte renvoie le total (le modèle doit décrire le document COURANT, `setSession` le reconstruit) |
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
| Distinctions fines de tokens (lot 4 #26) | ✅ | `highlight/TokenType.java`, `tokenizer/CLikeTokenizer.java`, `tokenizer/XmlTokenizer.java`, `tokenizer/MarkdownTokenizer.java` | DOC_COMMENT (état porté), KEYWORD_CONTROL/KEYWORD_MODIFIER (tables partagées), CHAR, RAW_STRING, NAMESPACE, ENTITY (fenêtre 12), EMPHASIS (règle intra-mot `_` vs `*`) — héritent du parent tant qu'un scheme ne les sépare |
| Interface TextMate optionnelle | ✅ | `highlight/TextMateTokenizer.java` | Point d'extension pour un module TextMate ; repli sur les tokéniseurs intégrés |
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

## 13bis. Scheme de couleurs modifiable (`:cel-ui`, `theme/`) — lot 4 #25

| Fonctionnalité | État | Fichier | Description |
|----------------|------|---------|-------------|
| Registre d'attributs de couleur | ✅ | `theme/ColorAttribute.java`, `theme/ColorAttributes.java` | 39 attributs (clé stable, libellé, groupe, parent d'héritage), parents avant enfants, accesseurs sans réflexion |
| Scheme épars sombre/clair | ✅ | `theme/EditorColorScheme.java` | Clé absente = « pas d'opinion » ; résolution cascade override → parent si héritage visuel → base → plancher texte |
| Persistance + partage | ✅ | `theme/EditorColorScheme.java` | JSON plat des seuls overrides `{schema,id,name,basedOn,dark,light}` ; tolérant aux clés inconnues |
| Presets en code | ✅ | `theme/EditorColorScheme.java` | `emeraldAccents()`… |
| Intégration vue | ✅ | `view/EditorView.java` | `setColorScheme` / `setSchemeDarkMode` / `setSchemeBaseTheme` — purge des caches via `setTheme` |
| Expand selection | ✅ | `session/EditorSession.java`, `view/EditorView.java` | Lot 4 #27 : curseur → mot → syntagme (span ou crochets) → ligne(s) → `
` final |

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
| Gutter (numéros + dots diagnostics) | ✅ | `view/chrome/GutterView.java` | Lignes + chevrons de pli + strip dédié ; B9c : icône d'aperçu (œil) posée sur la rangée du caret, façon Android Studio — visible pour un fichier prévisualisable, accent quand la feuille est ouverte, tap = bascule, rect déterministe partagé dessin/hit-test (`previewActionRect`), aucune zone fantôme (garde rangée à l'écran) ; B12 : mode des numéros par `LineNumberStyle` (voir ligne dédiée) ; **B15 (P2-2) : actions de gouttière de l'HÔTE** — API publique `GutterAction` (`view/GutterAction.java`) + registre `EditorView.gutterActions` (`view/EditorGutterActions.java`) : l'hôte injecte ses icônes ancrées à des lignes (points d'arrêt, « exécuter ce test », flèches de diff) ; pill B9c sur SA ligne (priorité sur l'œil au caret), `active` relu au rendu (`BooleanSupplier`), tap = `perform()` sans caret ni cascade multi-tap, UNE action par ligne (add remplace la concurrente), lignes hors document ni dessinées ni touchables, données d'hôte survivant à `setSession` ; icône `EditorIcons.Icon.BREAKPOINT` incluse |
| Bande de ligne courante, caret animé, sélection | ✅ | `view/render/CaretAnimator.java`, painters | Clignotement, poignées + loupe instrument de réglage : bande, squiggle et caret dans la bulle (M1, lot 2) |
| Squiggles / chips / sheets de diagnostics | ✅ | `view/render/EditorDiagnosticsPainter.java` | Groupés par ligne, badge de compte ; tap au cœur du squiggle → popup ancré (D1, lot 2), largeur ajustée au contenu (UX-3, J3) ; appui long sur la plage → message copié au presse-papiers (D3, J4) |
| Popups (complétion, signature, quick doc, code actions, go-to-symbol, go-to-line, rename, références, NavMenu) | ✅ | `view/popup/EditorPopupManager.java` + classes `Editor*Popup` | Popups verre ancrés au caret ; débordement pill → section MORE du menu unifié |
| Entrée tactile | ✅ | `view/input/EditorInputHandler.java` + `EditorTouchScroller`, `EditorSelectionGestures`, `EditorTapResolver`, `EditorTouchHoverController` + `view/popup/EditorPopupHitTester`, `EditorContextMenuHandler` | Tap, multi-tap, drag-select, loupe, toolbar, hover ; B7 : auto-scroll du drag sur les DEUX axes (coin = diagonale), appui long neutralisé dans les popups (`popupOwnsTouch`) ; B9 : le recentrage du caret est suspendu pendant un drag de sélection (`isSelectionDragActive` — fin de la dent de scie auto-scroll↔recentrage qui « secouait » les lignes), un pincement qui démarre désarme le drag en cours ; B10 : la cascade de taps gagne le 4e cran — caret → mot → ligne → PARAGRAPHE (`selectParagraphAt`, bloc de lignes non vides bornées par les vides/blanches), le 5e tap y reste ; B11 : le drag de gouttière devient un positionnement ABSOLU (fast-scroller, voir ligne suivante) armé au-delà du slop — un tap reste un tap, un jitter ne défile plus |
| Toolbar de sélection extensible (T1, lot 2) | ✅ | `view/SelectionAction.java` + `view/EditorSelectionActions.java` | Actions d'hôte injectées, ordre stable, désactivée estompée ; UN seul « ⋯ » (icône Actions) → menu unifié avec section MORE (UX-1, J3) |
| Actions intégrées socle de la pill (T2, lot 2) | ✅ | `view/BuiltinSelectionActions.java` | Duplicate / Comment (syntaxe par langage) / Convert case (UPPER, lower, Toggle) / Share opt-in (ACTION_SEND) / ✥ Move (B13 : arme le drag-déposer de bloc, voir ligne dédiée) — injectées via le SPI T1, retirables |
| Inlay hints par genre + tooltip (I1 + I2, lot 2) | ✅ | `lang/model/InlayHint.java`, `lsp/LspInlayHintProvider.java`, `view/render/EditorTextPainter.java` | Paramètres italiques effacés vs types droits ; appui long sur un hint → quick doc du tooltip (resolve LSP paresseux) |
| Inlays riches : labelParts + command + fenêtre viewport (I3 + I4 + I5, lot 2) | ✅ | `lang/model/InlayHint.java`, `view/input/EditorSelectionGestures.java`, `view/input/EditorTapResolver.java`, `view/EditorLanguageBridge.java` | Libellés multi-parts avec tooltip PAR PART (appui long) ; hint cliquable (command au tap, avant le caret, `workspace/executeCommand` asynchrone côté LSP) ; provider exigeant → viewport ± 4 lignes + re-requête au scroll idle (`wantsViewportRange`) |
| Auto-scroll du drag de sélection (UX-2, lot 2) | ✅ | `view/input/EditorSelectionGestures.java` | Doigt tenu en zone de bord (56 dp) → défilement directionnel, la sélection suit (poignées + drag-select) |
| Sticky scrolling (S1 + S2, lot 2) | ✅ | `view/StickyLineDetector.java`, `view/render/EditorRenderer.java` | En-têtes collés en haut au scroll — coloration syntaxique + ellipse « … », cache par révision (zéro scan par frame) |
| Sticky délégué au SPI langue (S5, lot 2) | ✅ | `languages/LanguageProfile.java` | `isBlockHeaderLine` + `Builder.stickyHeaderTest` : chaque langue décide de ses en-têtes (tables par défaut C_LIKE/Python inchangées, autres familles via le SPI) |
| Pill qui suit le geste (T4, lot 2) | ✅ | `view/input/EditorSelectionGestures.java` (`pillAnchorOffset`), `view/popup/EditorPopupAnchors.java` | L'ancre de la pill suit la borne EN DÉPLACEMENT pendant le drag (poignée ou doigt) ; `sel.end` au repos |
| Loupe réglable + native (M2 + M3, lot 2) | ✅ | `view/EditorView.java` (`setMagnifierZoom` ×1.5–×3, `setNativeMagnifierEnabled` API 28+), `view/render/EditorChromePainter.java` | Zoom de la bulle custom réglable ; opt-in pour le widget plateforme (rendu de la vraie frame, refermé aux 5 sites d'extinction) ; B9 : position STABILISÉE — Y snappé au centre de la rangée réglée, X amorti (α=0,55/frame), le contenu de la bulle ne tremble plus |
| En-tête de diagnostic enrichi (D4, lot 2) | ✅ | `view/popup/EditorPopupAnchors.java` (`diagnosticHeaderLabel`), `shift/DiagnosticShift.java`, `lsp/LspDiagnosticsManager.java` | Sévérité · code · source · ligne:colonne 1-based · badge ×N — code/source LSP transportés jusqu'au rendu, préservés par le décalage |
| Libellés i18n (T3, lot 2) | ✅ | `cel-ui/src/main/res/values/strings.xml` (préfixe `cel_`) | 20 libellés canoniques en ressources, défauts anglais identiques ; l'hôte traduit en redéclarant les mêmes noms |
| Sticky interactif (S3 + S4, lot 2) | ✅ | `view/EditorView.java` (`activeStickyLines`), `view/input/EditorTapResolver.java` | Tap sur une rangée collée → la ligne d'origine est centrée ; plafond configurable 1-5 lignes (`setStickyMaxLines`), défaut 2 |
| Clavier + keymap rebindable + chords | ✅ | `view/input/EditorKeyHandler.java`, `view/input/EditorKeymap.java` | Bind simple et séquences à deux touches |
| Pont IME | ✅ | `view/EditorImeBridge.java` | Composing, SurroundingText (compat API 24) |
| Scroll/fling/pinch-zoom | ✅ | `view/input/EditorScrollManager.java`, `view/input/EditorZoomController.java` | Suivi de vitesse, clamp de police ; B9b : ancre du pincement à trois niveaux (caret exact / point focal lecture seule / centre du viewport via l'API historique) et traînées du doigt restant englouties après un pincement (le geste n'est plus un scroll) ; B12 : chaque mutation d'échelle s'enregistre dans la mémoire par type (voir ligne dédiée) |
| Fast-scroller de gouttière + bande d'erreurs (B11) | ✅ | `view/chrome/EditorFastScroller.java`, `view/render/EditorErrorStripe.java`, `view/input/EditorInputHandler.java`, `view/input/EditorTapResolver.java` | P0-3 : drag de gouttière/bande = positionnement ABSOLU du document (pouce 4 dp + bulle de ligne au bord droit, plancher 32 dp, fondu 250 ms, pas de fling, annulation au pincement/CANCEL) ; P1-1 : error stripe AS — marqueurs de diagnostics de TOUT le fichier au bord droit + rect du viewport, tap marqueur = dépliage + centrage + fiche, alimentation partagée avec les points de gouttière, silence en aperçu, bascule `setErrorStripeEnabled` (défaut actif) |
| Zoom persistant par type + colonnes relatives (B12) | ✅ | `view/EditorZoomMemory.java`, `view/chrome/LineNumberStyle.java`, `view/EditorView.java`, `view/chrome/GutterView.java`, `view/input/EditorZoomController.java` | P1-3 : `setZoomMemoryKey(clé)` — mémoire process des échelles par type de fichier (pincement/A±/setFontScale s'enregistrent, reposer la clé applique le réglage du type, le zoom ne fuit pas entre types, clé nulle = comportement historique, rotation B17 reste plus précise) ; P1-4 : `LineNumberStyle` ABSOLUTE (défaut) / RELATIVE (distance au caret, 0 au caret) / HYBRID (numéro réel au caret + distances autour) — surbrillance du caret inchangée, caret invalide retombe sur ABSOLUTE |
| Drag-déposer de bloc (B13) | ✅ | `view/input/EditorBlockDragController.java`, `session/LineOperations.java` (moveRange), `view/render/EditorChromePainter.java`, `view/render/EditorRenderer.java` | P1-2 : `armBlockDrag()` — le mode est ARMÉ par la pill ✥ Move (le drag au doigt étend déjà la sélection, les deux ne cohabitent pas) ; le geste suivant appartient au bloc : drag = déplacement (loupe stabilisée + caret fantôme 3 dp au point de dépôt, aussi dans la loupe + carte flottante sous le doigt + auto-scroll UX-2 partagé), tap = annulation, CANCEL/pincement/changement de session = retombée propre ; dépôt en UNE étape d'undo (`EditorSession.moveRange`), sélection ré-épinglée sur le texte déplacé, sémantique caractère exacte, no-op sans pollution d'undo dans la source |
| Minimap | ✅ | `view/render/EditorChromePainter.java` | Bande latérale, bascule publique |
| Aperçu XML | ✅ | `view/preview/EditorPreviewController.java`, `view/preview/EditorPreviewHost.java` | Split/full via hôte |
| Breadcrumb + actions (B5/B6/B7) | ✅ | `view/chrome/BreadcrumbBar.java` | Barre de fil d'Ariane + zone d'actions d'éditeur en haut à droite ; B6 : `addEditorActions()` (undo/redo câblés session + aperçu), boutons-icônes pill 40 dp, aperçu `GONE` si fichier non prévisualisable, bascule ouvrir/fermer, `addAction` texte (compat B5) ; B7 : états rafraîchis par le chemin IME (frappe/clique), plus de boutons morts |
| Moteur d'icônes vectorielles (B6) | ✅ | `view/icons/EditorIcons.java` | UNDO/REDO/EYE (FILL) + SPLIT/EXPAND/CLOSE (STROKE), paths Material 24×24 parsés une fois puis cachés, teinte du thème, `draw(Canvas, Icon, cx, cy, size, paint)` |
| Feuille d'aperçu carte flottante (B6/B7/B8) | ✅ | `view/preview/EditorPreviewSheet.java` | Élévation 12 dp, coins arrondis 16 dp côté flottant, en-tête œil+nom, segmented control scindé/plein ; B7 : corps clippé aux coins de la carte, entrée/sortie animées ; B8 : un tap hors du cadre referme la feuille (le tap traverse vers l'éditeur) |
| Fondus de popups (B7) + rejet extérieur (B8) | ✅ | `view/popup/EditorPopupFade.java`, `view/popup/EditorPopupMotion.java` | Popups Canvas : apparition 120 ms (front montant dans le pipeline), disparition 110 ms par photographie au dismiss (complétion, quick doc, menu, pill) ; popups en fenêtre : cartes posées 140 ms / effacées 110 ms ; B8 : `outsideTapDismisses` — la recette Android complète (outside touchable + fond non nul) qui traduit un tap hors cadre en dismiss automatique |
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

### Références de comportement

- Aller à la définition : Cmd+Click (desktop), Alt+Click (alternative)
- Ajouter une sélection à l'occurrence suivante : Cmd+G
- Sélection en colonne : clic milieu + drag

---

## Statistiques

| Indicateur | Valeur |
|------------|--------|
| Fichiers source | 205 (`:cel-core` 68 · `:cel-lsp-api` 38 · `:cel-lsp` 36 · `:cel-ui` 63) |
| Fichiers de test | 67 |
| Lignes de code (main) | ≈ 37 000 |
| Tests unitaires | 1 370 (`:cel-core` 887 · `:cel-lsp-api` 22 · `:cel-lsp` 93 · `:cel-ui` 361 · `demo` 7) |
| Langages colorés intégrés | 27 |
| Tokéniseurs intégrés | 14 (+ utilitaires SpanUtils, KeywordTables) |
| Interfaces de providers LSP | 17 |
| Providers de connexion LSP | 5 |
