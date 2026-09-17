# Architecture

## Vision

La bibliothèque est organisée en **couches strictement séparées** : le moteur
d'édition est du Java pur (aucune dépendance `android.*`), testable sur la
JVM hôte, et seule la couche View dépend d'Android. Chaque module a une
responsabilité unique et les grandes classes sont des **orchestrateurs**
entourés de collaborateurs spécialisés par responsabilité.

## Structure du projet

```
code-editor/
├── settings.gradle.kts              — inclut les 4 modules editor/
├── build.gradle.kts                 — AGP 9.0.0, group/version Maven
├── gradle/wrapper/                  — Gradle 9.5.1
├── gradle.properties                — jvmargs, parallélisme, cache
├── jitpack.yml                      — JDK 17 pour les builds JitPack
├── .github/workflows/ci.yml         — CI (build + tests + lint)
│
└── editor/
    ├── cel-core/                    — moteur pur Java
    ├── cel-lsp-api/                 — SPI langage
    ├── cel-lsp/                     — client LSP4J
    └── cel-ui/                      — vues Android
```

## Dépendances entre modules

```
┌──────────────────────────────────────────────┐
│  Application hôte (ou consommateur Maven)    │
└──────────────┬───────────────────────────────┘
               │
      ┌────────▼────────┐        ┌──────────────────┐
      │    :cel-ui      │        │     :cel-lsp     │
      │  api(:cel-core) │        │ compileOnly(:    │
      │  api(:cel-lsp-  │        │   cel-core,      │
      │       api)      │        │   cel-lsp-api,   │
      └────────┬────────┘        │   cel-ui)        │
               │                 │ + LSP4J, Gson    │
      ┌────────▼────────┐        └────────┬─────────┘
      │  :cel-lsp-api   │                 │ (au runtime,
      │  api(:cel-core) │◄────────────────┘  l'hôte apporte
      └────────┬────────┘                   les 3 modules)
               │
      ┌────────▼────────┐
      │   :cel-core     │  — aucune dépendance
      └─────────────────┘
```

Notes :

- `:cel-lsp` référence ses trois frères en `compileOnly` : le consommateur
  doit les ajouter lui-même (voir l'Installation du `README.md`).
- `:cel-core` et `:cel-lsp-api` sont **purs** (utilisables sur la JVM hôte,
  sans Android).

## Organisation interne des modules

### `:cel-core` — moteur pur (15 packages par responsabilité)

```
jo.codeeditor/
├── rope/         — Rope : arbre binaire équilibré, O(log N) par édition
├── document/     — EditorDocument (index de lignes), Selection
├── session/      — EditorSession (orchestrateur, ~1 250 l.) +
│                   RestyleEngine (restyle asynchrone incrémental),
│                   ImeBridge, CommentToggler, UndoRecorder,
│                   SessionAnnotations, FoldRegions, UndoManager
├── edit/         — EditOps (façade, ~130 l.) + SmartTyping, SmartNewline,
│                   XmlNewline, CodeContext, EditTextUtils, WordBounds,
│                   BracketPairs, IndentDetection, LanguageIds,
│                   CommentSyntax, RangeEdit
├── find/         — FindReplace, FindOptions, Match
├── shift/        — DiagnosticShift (re-mapping offsets), EditSpan
├── snippet/      — SnippetSession (tab stops, placeholders, mirroring)
├── fold/         — FoldModel, FoldRegion, FoldedLineInfo
├── wrap/         — WrapModel (retour à la ligne, prefix sums)
├── cache/        — LineRenderCache (triple-stamp texte/inlay/sem, LRU)
├── completion/   — CompletionSession, SignatureHelpController,
│                   CompletionKindBadge
├── doc/          — QuickDoc (parsing Javadoc/KDoc)
├── navigation/   — NavigationMenu (go-to declaration/impl/type/super)
├── highlight/    — SyntaxHighlighter (orchestrateur, ~155 l.) +
│                   FoldDetector, TextMateTokenizer (interface optionnelle),
│                   StyledLine, TokenType, LexState, LineSpan
│   └── tokenizer/ — 16 fichiers : 14 tokéniseurs par langage
│                    (CLike, Xml, Json, Python, Lua, Css, Shell, Yaml,
│                    Sql, Properties, Toml, Smali, Log, Markdown)
│                    + SpanUtils + KeywordTables
└── languages/    — LanguageRegistry (registre observable),
                    BuiltinLanguages (27 langages), LanguageProfile,
                    SyntaxFamily
```

### `:cel-lsp-api` — SPI langage

```
jo.codeeditor.lang/
├── Language.java, Analyzer.java, EmptyLanguage.java, BracketMatch.java,
│   PopupCoordinator.java, StyleReceiver.java     — contrats racine
├── model/     — 15 classes de données : Diagnostic, CompletionItem,
│                HoverContent, SignatureHelp, CodeAction, Symbol,
│                InlayHint, TextEdit, RenameResult, DefinitionLocation,
│                DocumentHighlight, ViewZone, CodeBlock, Parameter, Signature
└── provider/  — 17 interfaces : CompletionProvider, DiagnosticsProvider,
                 HoverProvider, DefinitionProvider, TypeDefinitionProvider,
                 ImplementationsProvider, SuperDefinitionProvider,
                 ReferencesProvider, RenameProvider, Formatter,
                 CodeActionsProvider, SymbolProvider,
                 SignatureHelpProvider, InlayHintProvider,
                 DocumentHighlightProvider, ViewZoneProvider,
                 CompletionPublisher
```

Un `Language` fournit des providers pour chaque capacité ; `EmptyLanguage`
sert de valeur par défaut neutre.

### `:cel-lsp` — intégration LSP4J

```
jo.codeeditor.lsp/
├── LspLanguage.java (~275 l.)     — adaptateur Language : orchestration
│                                     des providers LSP
├── Lsp*Provider.java (14)         — completion, diagnostics, hover,
│                                     definition, typeDefinition,
│                                     implementations, superDefinition,
│                                     references, rename, codeActions,
│                                     symbol, signatureHelp, inlayHint,
│                                     documentHighlight
├── LspEditor.java (~405 l.)       — cycle de vie document ↔ serveur,
│                                     assisté par LspDocumentSynchronizer,
│                                     LspDiagnosticsManager,
│                                     LspSemanticTokensPuller,
│                                     LspFoldPuller, LspWorkspaceEditApplier
├── LspProject.java, LanguageServerWrapper.java,
│   LanguageServerDefinition.java  — gestion multi-serveurs, shutdown
│                                     borné à 2 s
├── LspFormatter.java, LspNoopAnalyzer.java, DefaultLanguageClient.java,
│   CodeIdeLanguageServer.java, LspFeature.java, LspLogSink.java,
│   IoCompat.java                  — adaptation LSP4J + compat API 24
└── connection/                    — 5 providers de connexion : Stream,
                                     InProcess, LocalSocket,
                                     ProcessBuilder, Socket
```

### `:cel-ui` — vues Android

```
jo.codeeditor/
├── view/        — EditorView (~2 720 l.) : orchestrateur = cycle de vie
│                  Android View + API publique + relais vers ~60
│                  collaborateurs :
│                  • rendu : EditorRenderer (~530 l.) + 5 painters
│                    (EditorTextPainter, EditorHighlightPainter,
│                     EditorDiagnosticsPainter, EditorAssistPopupPainter,
│                     EditorChromePainter) + EditorDecorationPainter
│                    (plugins, EditorPainterHost)
│                  • popups : EditorPopupManager (~240 l.) + les classes
│                    Editor*Popup (complétion, signature, quick doc, code
│                    actions, go-to-symbol, go-to-line, rename,
│                    références, diagnostics)
│                  • entrée : EditorInputHandler (~635 l.) + famille input
│                    (EditorTouchScroller, EditorSelectionGestures,
│                     EditorTapResolver, EditorPopupHitTester,
│                     EditorTouchHoverController, EditorContextMenuHandler,
│                     EditorKeyHandler, EditorKeymap, EditorImeBridge)
│                  • géométrie et caches : EditorMetrics,
│                    EditorPaintContext, EditorWrapGeometry,
│                    EditorHitMapper, EditorShapedLayoutCache,
│                    EditorFoldIndex, EditorLineLayoutResolver…
│                  • contrôleurs : EditorScrollManager, EditorZoomController,
│                    EditorPreviewController, EditorPreviewSheet,
│                    EditorReferencesController, EditorCommands…
│                  • chrome dessiné : BreadcrumbBar (listener côté vue),
│                    SymbolBarView, GutterView, EditorTheme
└── blocks/      — BlockEditor (édition par blocs : BlockNode, BlockType,
                   BlockParser, BlockRenderer, SlotCompletion imbriqués)
```

L'ancienne génération de « god classes » a été démantelée en collaborateurs
par composition (corps déplacés à l'identique, signatures package-privées,
relais conservés pour l'API publique et les tests) :

| Classe | Avant | Après (orchestrateur) |
|--------|-------|----------------------|
| EditorView | 4 626 l. | 2 720 l. |
| EditorRenderer | 3 475 l. | 533 l. |
| SyntaxHighlighter | 2 545 l. | 155 l. |
| EditorSession | 2 188 l. | 1 247 l. |
| EditorPopupManager | 1 584 l. | 239 l. |
| LspLanguage | 1 576 l. | 276 l. |
| EditorInputHandler | 1 400 l. | 635 l. |
| LspEditor | 1 099 l. | 406 l. |
| EditOps | 1 051 l. | 127 l. |

## Cycle d'édition

```
IME / touche / tactile
        │
        ▼
EditorInputHandler / EditorKeyHandler / EditorImeBridge   (:cel-ui)
        │  relais
        ▼
EditorSession.replaceRange()                               (:cel-core)
        │  point de mutation unique
        ├──► UndoRecorder / UndoManager     — undo groupé (coalescence)
        ├──► EditorDocument.replace()       — Rope + lineStarts splice
        ├──► DiagnosticShift                — re-map diagnostics/inlays/
        │                                      sem tokens/folds
        └──► listeners (onTextEdit…)
        │
        ▼
RestyleEngine (thread dédié)                               (:cel-core)
        │  coloration incrémentale par ligne (SyntaxHighlighter +
        │  tokéniseurs, cascade stop-rule) → StyledLine
        ▼
LineRenderCache (triple-stamp, LRU, prefetch idle)         (:cel-core)
        │
        ▼
EditorView.onDraw → EditorRenderer (16 couches)            (:cel-ui)
   texte stylé, gutter, caret, sélection, squiggles, chips,
   popups, minimap, aperçu XML, décorations de plugins
```

## Aperçu XML — interface EditorPreviewHost

L'éditeur ne dépend d'aucun moteur d'aperçu : le rendu d'aperçu est délégué
à l'application hôte via une interface, `EditorPreviewHost` :

```java
package jo.codeeditor.view;

public interface EditorPreviewHost {
    /** L'hôte peut-il prévisualiser ce fichier ? (.xml, .md, .html) */
    boolean canPreview(String fileName);

    /** Le mode d'aperçu a changé (NONE/SPLIT/FULL). */
    void onPreviewModeChanged(EditorView.PreviewMode mode,
                              int previewLeft, int previewWidth);

    /** Le texte de l'éditeur a changé (debounce 200 ms). */
    void onPreviewContentChanged(CharSequence text);

    /** Dessine l'aperçu sur le Canvas (appelé par le renderer). */
    void drawPreview(Canvas canvas, float offsetX, float offsetY);

    /** L'aperçu a-t-il du contenu prêt à dessiner ? */
    boolean hasPreviewContent();

    /** Hit-teste un tap dans l'aperçu (sélection de vue). */
    boolean hitTestPreview(float x, float y);
}
```

Flux type :

```java
// 1. Au onCreate :
editorView.setPreviewHost(new XmlPreviewHost(this, editorView));

// 2. Quand l'utilisateur change de fichier :
editorView.setFileName("layout.xml");
// → EditorView appelle previewHost.canPreview("layout.xml")
// → Si true, dessine les icônes d'aperçu dans le coin haut-droit

// 3. L'utilisateur tape une icône d'aperçu :
// → EditorView.setPreviewMode(SPLIT ou FULL)
// → previewHost.onPreviewModeChanged(mode, left, width)
// → previewHost.onPreviewContentChanged(text)
// → l'hôte notifie editorView.invalidate() quand son rendu est prêt

// 4. L'utilisateur édite le XML :
// → EditorView.notifyTextChanged()
// → previewUpdateTask (debounce 200 ms)
// → previewHost.onPreviewContentChanged(text) → re-render → invalidate

// 5. À chaque frame :
// → EditorRenderer.draw()
// → Si isXmlPreviewActive() : host.drawPreview(canvas, previewLeft, 0)
```

## Principes directeurs

1. **Pureté du moteur** — `:cel-core` et `:cel-lsp-api` ne dépendent
   d'aucun `android.*` ; toute la logique y est testée sur la JVM hôte.
2. **Orchestrateurs + collaborateurs** — chaque grande responsabilité a une
   classe pivot qui délègue à des collaborateurs à champ unique ; les
   collaborateurs restent dans le package de leur orchestrateur avec des
   signatures package-privées.
3. **Testabilité** — 922 tests unitaires JVM (JUnit 5, Robolectric pour le
   cycle de vie des vues) ; aucun test instrumenté requis.
4. **Défensif côté rendu** — tous les offsets sont clampés à
   `[0, doc.length()]` dans le chemin de dessin ; un painter de plugin qui
   lève est retiré, jamais un crash.
5. **Grands fichiers** — au-delà de 2,5 M chars / 50 k lignes, l'analyse,
   le folding et les inlays sont coupés ; l'édition reste O(log N).

## Voir aussi

- [`README.md`](README.md) — présentation, installation, build.
- [`FEATURES.md`](FEATURES.md) — matrice des fonctionnalités.
- [`USAGE.md`](USAGE.md) — guide d'intégration.
- [`CHANGELOG.md`](CHANGELOG.md) — historique des versions.
