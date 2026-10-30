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
├── settings.gradle.kts              — inclut les 5 modules editor/
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
    ├── cel-textmate/                — voie grammaires optionnelle (ADR-002 C3)
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
      │       api)      │        │   cel-lsp-api)   │
      │ LspEditorView-  │        │ + LSP4J, Gson    │
      │ Host (adaptateur│        └────────┬─────────┘
      │ du plan 03)     │                 │
      └────────┬────────┘                 │
               │         EditorHost       │ (au runtime,
      ┌────────▼────────┐◄───────────────┘  l'hôte apporte
      │  :cel-lsp-api   │   (inversion      les modules
      │  api(:cel-core) │    plan 03)       voulus)
      │ EditorHost      │
      └────────┬────────┘
               │
      ┌────────▼────────┐
      │   :cel-core     │  — aucune dépendance
      └─────────────────┘
```

Notes :

- `:cel-lsp` référence ses deux frères purs en `compileOnly` : le
  consommateur doit les ajouter lui-même (voir l'Installation du
  `README.md`). La dépendance `:cel-lsp → :cel-ui` est RETIRÉE
  (plan 03) : le client LSP programme contre l'interface `EditorHost`
  (`cel-lsp-api`), l'adaptateur de référence `LspEditorViewHost` vit
  côté `:cel-ui` — un hôte sans vue peut apporter sa propre
  implémentation.
- `:cel-core` et `:cel-lsp-api` sont **purs** (utilisables sur la JVM hôte,
  sans Android).
- `:cel-textmate` (ADR-002, C3) est **optionnel** : il implémente
  `jo.codeeditor.highlight.TextMateTokenizer` (crochet
  `SyntaxHighlighter.setTextMateTokenizer`) et ne dépend que de
  `:cel-core`. Grammaires embarquées en classpath
  (`textmate/` : `csharp.tmg.json` originale du dépôt + 9 grammaires
  tierces VERBATIM inactives par défaut — attributions dans `NOTICE`),
  mapping portées → `TokenType` (`ScopeMapper`, chantier C4). Sans le
  module, le tokéniseur intégré par famille
  reste le repli — chemin strictement additif.

## Organisation interne des modules

### `:cel-core` — moteur pur (15 packages par responsabilité)

```
jo.codeeditor/
├── rope/         — Rope : arbre binaire équilibré, O(log N) par édition ;
│                   l'index de lignes est re-splicé en O(lignes) par
│                   édition (0,11 ms mesuré sur 50 000 lignes, budget
│                   de frappe 2 ms — mesure : EditorSessionBenchmark)
├── document/     — EditorDocument (index de lignes), Selection
├── session/      — EditorSession (orchestrateur, ~1 590 l.) +
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
                    BuiltinLanguages (29 langages), LanguageProfile,
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
├── LspEditor.java (~740 l.)       — cycle de vie document ↔ serveur,
│                                     assisté par LspDocumentSynchronizer,
│                                     LspDiagnosticsManager,
│                                     LspSemanticTokensPuller,
│                                     LspFoldPuller, LspWorkspaceEditApplier ;
│                                     3.74.0 (ADR-003) : disconnect()
│                                     restaure EmptyLanguage, jeton de
│                                     génération, garde commune providers
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
├── view/         — EditorView (~3 960 l.) : orchestrateur = cycle de vie
│                   Android View + API publique + relais vers ~60
│                   collaborateurs. Collaborateurs directs : pont IME
│                   (EditorImeBridge — InputConnection par niveau d'API :
│                   Api31 SurroundingText, Api33 takeSnapshot, Api34
│                   replaceText + stylet ; plan 09 entièrement livré :
│                   purge de session, bornage Binder, capitales réelles,
│                   instantané atomique, politique clavier unifiée,
│                   hooks hôte EditorInfo/commandes privées), pont
│                   langage (EditorLanguageBridge),
│                   presse-papiers, plis (EditorFoldIndex), diagnostics
│                   (DiagnosticsPusher/Locator), géométrie (EditorMetrics,
│                   EditorWrapGeometry, EditorHitMapper,
│                   EditorLineLayoutResolver), commandes (EditorCommands)…
├── view/state/   — objets d'état extraits de la vue (plan 01) :
│                   CompletionState (popup de complétion — invariants
│                   sélection/fenêtre), FindState (surlignages de
│                   recherche — invariant de bornes de la courante),
│                   ScrollState (vOffset/hOffset — invariant [0, max]) ;
│                   mutabilité thread UI, lecture seule pendant onDraw
├── view/render/  — rendu Canvas : EditorRenderer (~750 l.) + 5 painters
│                   (EditorTextPainter, EditorHighlightPainter,
│                   EditorDiagnosticsPainter, EditorAssistPopupPainter,
│                   EditorChromePainter), caches (EditorShapedLayoutCache),
│                   animation du caret (CaretAnimator) et l'API plugins
│                   (EditorPainterHost, EditorPaintContext,
│                   EditorDecorationPainter, EditorDecorations)
├── view/input/   — entrées : EditorInputHandler (~700 l.) + famille
│                   tactile/clavier (EditorKeyHandler,
│                   EditorSelectionGestures, EditorTouchScroller,
│                   EditorTapResolver, EditorTouchHoverController,
│                   EditorScrollManager, EditorZoomController) +
│                   EditorKeymap (API publique de configuration)
├── view/popup/   — fenêtres surgissantes : EditorPopupManager (~240 l.) +
│                   classes Editor*Popup (complétion, signature, quick doc,
│                   code actions, go-to-symbol, go-to-line, rename,
│                   références, diagnostics) + ancres et hit-test
│                   (EditorPopupAnchors : bande du caret et X d'ancre
│                   PARTAGÉS des popups de frappe, EditorPopupHitTester) ;
│                   le coordinateur (registre unique + exclusions) vit
│                   dans le kit ci-dessous et remplace les closeOthers()
├── view/popup/kit/ — primitives PARTAGÉES des popups (chantier
│                   « popups unifiés ») : TextLayoutKit (wrap linéaire +
│                   ellipse TAIL/HEAD/MIDDLE dichotomique, mesure
│                   injectée — PURS), PopupTokens (rayons/espacements/
│                   échelle typo/couleurs sémantiques — le seul endroit
│                   où changer l'apparence), PopupCard (surface verre,
│                   filets, bandes, gras mémoïsé), DocRenderModel
│                   (documentation → blocs typés SIGNATURE/SUMMARY/
│                   PARAM/RETURNS/THROWS/SEE, langue de la session),
│                   SignatureDocSections (sections de la signature),
│                   PopupPlacement (règle des 4 placements coordonnés
│                   signature ↔ complétion : bande du caret jamais
│                   recouverte), PopupCoordinator (registre des popups
│                   ouverts + règles d'exclusion — frappe coexistante,
│                   tap exclusif)
├── view/preview/ — aperçu délégué au hôte : EditorPreviewController,
│                   EditorPreviewSheet, EditorPreviewHost (contrat hôte),
│                   EditorPreviewDrop/Geometry (carte « goutte d'eau » B27)
├── view/chrome/  — habillage : EditorTheme, GutterView, BreadcrumbBar,
│                   SymbolBarView
└── blocks/       — BlockEditor (édition par blocs : BlockNode, BlockType,
                   BlockParser, BlockRenderer, SlotCompletion imbriqués)
```

Le couplage interne entre l'orchestrateur et ses sous-packages passe par
des **scells `@RestrictTo(LIBRARY_GROUP)`** : les membres concernés sont
publics pour le compilateur mais contractuellement internes au groupe
`jo.codeeditor` (appliqué par lint, annotation `compileOnly` absente du
POM publié) — l'API publique d'`EditorView` reste strictement inchangée.

#### Couche `view.popup.kit` — popups unifiés

Tous les popups consomment les MÊMES primitives (un seul endroit où
changer l'apparence d'une carte flottante) :

- **Texte** : `TextLayoutKit` (pur, mesure injectée) — wrap mot à mot
  (coupe les mots plus larges que la ligne), ellipse dichotomique.
  Historique : 3 copies du wrap (mesure par caractère, O(n²)) et 6
  boucles d'ellipse à retrait de 2 caractères.
- **Design** : `PopupTokens` (rayons carte 14 / rangée 8 / chip 6 /
  sheet 16 dp, espacements, échelle typo ×0,90/0,85/0,75/0,70,
  couleurs sémantiques dérivées de l'`EditorTheme` — scrim, ampoule,
  sévérités). Historique : 8 rayons coexistants (6 à 18 dp).
- **Carte** : `PopupCard` — surface verre + bordure hairline (état du
  paint restauré), filets, bandes teintées, Typeface gras mémoïsé.
- **Documentation** : `DocRenderModel` — `QuickDocContent` → blocs
  typés, LANGUE DE LA SESSION (jamais d'identifiant en dur) ;
  consommé par la doc rapide, la bande doc de signature et le panneau
  de complétion (3 présentations, un modèle).
- **Placement coordonné** : `PopupPlacement` (pur) — pendant la frappe
  d'un appel, la signature est AU-DESSUS de la bande du caret, la
  complétion EN DESSOUS, la ligne du caret LIBRE au milieu ; règle en
  4 temps, clamp final sans jamais entrer dans la bande (la complétion
  réduit ses rangées avant de recouvrir), clavier logiciel couvert par
  la hauteur réelle de la vue. Entrée commune : la bande fold+wrap
  aware (`docLineToY` + `rowsForDocLine`) et le X du début du token.
- **Coordination** : `PopupCoordinator` — registre unique des popups
  ouverts + règles d'exclusion (complétion et signature coexistent,
  tout popup de tap referme le reste) ; remplace les `closeOthers()`
  dupliqués.

L'ancienne génération de « god classes » a été démantelée en collaborateurs
par composition (corps déplacés à l'identique, signatures package-privées,
relais conservés pour l'API publique et les tests) :

| Classe | Avant | Aujourd'hui (orchestrateur)* |
|--------|-------|----------------------|
| EditorView | 4 626 l. | 3 960 l. (états extraits : `EditorSavedState`, `CompletionState`, `FindState`, `ScrollState`) |
| EditorRenderer | 3 475 l. | 748 l. |
| SyntaxHighlighter | 2 545 l. | 155 l. |
| EditorSession | 2 188 l. | 1 588 l. |
| EditorPopupManager | 1 584 l. | 231 l. |
| LspLanguage | 1 576 l. | 277 l. |
| EditorInputHandler | 1 400 l. | 700 l. |
| LspEditor | 1 099 l. | 445 l. |
| EditOps | 1 051 l. | 178 l. |

*Remesuré à la version courante du dépôt (les orchestrateurs regonflent
légitimement quand des fonctionnalités s'ajoutent — la colonne « Avant »
garde la trace du point de départ du démantèlement).

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

## Aperçu — interface EditorPreviewHost

L'éditeur ne dépend d'aucun moteur d'aperçu : le rendu d'aperçu est délégué
à l'application hôte via une interface, `EditorPreviewHost` (six méthodes
historiques + six méthodes B27 à défaut raisonnable — un hôte feuille
n'implémente que les six premières) :

```java
package jo.codeeditor.view;

public interface EditorPreviewHost {
    /** L'hôte peut-il prévisualiser ce fichier ? (.xml, .md, .html) */
    boolean canPreview(String fileName);

    /** Le mode d'aperçu a changé (NONE/SPLIT/FULL/SHEET_*/DROP). */
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

    // ── B27 : la carte « goutte d'eau » (popup de gouttière) ──────
    /** Vrai = le tap d'aperçu ouvre la CARTE (hôtes canvas), faux
     *  (défaut) = feuille/panneau (hôtes Vue). */
    default boolean prefersDropPreview() { return false; }

    /** Libellés des onglets de la carte (défaut : aucun). */
    default List<String> previewDropTabs() { return List.of(); }

    /** L'utilisateur a basculé l'onglet de la carte. */
    default void onPreviewDropTabSelected(int index) { }

    /** Rend l'écran du mini-téléphone (canvas translaté/échelonné/clippé). */
    default void drawDropPreview(Canvas c, float x, float y,
            float w, float h, int tab) { }

    /** Libellé du badge d'exécution (défaut : aucun). */
    default String previewDropBadge(int tab) { return null; }

    /** Retour terrain n°3 : l'utilisateur a relâché la BARRE DE TAILLE
     *  du pied de la carte — facteur choisi [1, 1,5], une fois par geste
     *  (hook de persistance). */
    default void onPreviewDropScaleChanged(float scale) { }
}
```

Flux type :

```java
// 1. Au onCreate :
editorView.setPreviewHost(new XmlPreviewHost(this, editorView));

// 2. Quand l'utilisateur change de fichier :
editorView.setFileName("layout.xml");
// → EditorView appelle previewHost.canPreview("layout.xml")
// → Si true, l'œil d'aperçu vit dans la gouttière (rangée du caret)

// 3. L'utilisateur tape l'œil de gouttière (ou une icône d'aperçu) :
// → Hôte prefersDropPreview() ? carte DROP : feuille SHEET_*/panneau
// → previewHost.onPreviewModeChanged(mode, left, width)
// → previewHost.onPreviewContentChanged(text)
// → l'hôte notifie editorView.invalidate() quand son rendu est prêt

// 4. L'utilisateur édite :
// → EditorView.notifyTextChanged()
// → previewUpdateTask (debounce 200 ms)
// → previewHost.onPreviewContentChanged(text) → re-render → invalidate
//   (carte : re-résolution du placement à chaque frame, le hôte ne
//    s'occupe de RIEN de la géométrie — EditorPreviewDropGeometry)

// 5. À chaque frame :
// → EditorRenderer.draw()
// → Si isXmlPreviewActive() : host.drawPreview(canvas, previewLeft, 0)
// → Si DROP ouvert : EditorPreviewDropPainter.drawCard(canvas)
//   (tige + goutte + carte + en-tête/onglets + mini-téléphone avec
//    host.drawDropPreview clippé + badge + chips « rendu vivant / fit » ;
//    le painter dessine drawnPlacement() — le placement GLISSANT vers
//    la cible en trois couches/variante B, retour terrain n°5)
```

La carte « goutte d'eau » se décompose en trois classes :
`EditorPreviewDrop` (état vivant : ancre gelée, onglets, hit-test,
voile/fantôme, glissement fluide des re-placements — transitoire
180 ms, rigide au scroll, direct au geste de taille),
`EditorPreviewDropGeometry` (placement pur anti-recouvrement — testé en
JUnit sans Android) et `EditorPreviewDropPainter` (chrome du canvas).
La maquette de conception interactive est archivée dans
`docs/maquettes/`.

## Cycle de vie du langage LSP (3.74.0, ADR-003)

**Invariant : sans langage LSP connecté à la vue, TOUTES les capacités
LSP sont fermées** — aucun résolveur actif, aucune requête émise. Une
capacité ne s'ouvre que si le serveur du type de fichier courant
l'annonce ET qu'elle n'est pas désactivée (`LspFeature`).

Le cycle connect/disconnect est SYMÉTRIQUE :

```
connect()                                disconnect() (idempotent)
├─ (async) initialize + négociation      ├─ didClose
├─ didOpen                               ├─ EmptyLanguage restaurée sur
├─ LspLanguage posé sur l'hôte           │    l'hôte (setLanguage(null),
│   (setLanguage — même règle de         │    même règle de thread)
│    thread : attaché → post UI,         ├─ écouteur d'édition retiré
│    détaché → en ligne)                 ├─ langage remis à null
├─ écouteur d'édition posé               └─ jeton de génération invalidé
└─ jeton de génération pris
```

- **Jeton de génération** (C1b) : incrémenté à CHAQUE connect() et
  CHAQUE disconnect() — une continuation de connect() dont le jeton ne
  correspond plus s'abandonne (ni langage, ni écouteur, ni
  `connected = true`, `didClose` de rattrapage si besoin). Un changement
  d'onglet pendant la poignée de main asynchrone ne peut plus brancher
  le langage du fichier fermé sur la vue du nouvel onglet.
- **Garde commune** (`readyServer()`, C1 en défense en profondeur) :
  tout `Lsp*Provider` renvoie vide/`null` et n'émet AUCUNE requête une
  fois la session fermée — une seule implémentation, pas quatorze copies.
- **Annulation des tâches différées** : `setLanguage(null)` efface tous
  les résolveurs du pont ET annule leurs tâches différées (survol
  programmé, tirs clients throttés) — un tir tardif du langage précédent
  ne peut pas atterrir sur le nouveau document.
- HORS du périmètre LSP (jamais touchés par la déconnexion) : coloration
  lexicale, plis lexicaux, frappe intelligente, IME.
- Séquences couvertes par les tests (`LspDisconnectLifecycleTest`) :
  java → sans serveur → java ; java → xml ; connect puis disconnect
  immédiat ; disconnect appelé deux fois.

## Principes directeurs

1. **Pureté du moteur** — `:cel-core` et `:cel-lsp-api` ne dépendent
   d'aucun `android.*` ; toute la logique y est testée sur la JVM hôte.
2. **Orchestrateurs + collaborateurs** — chaque grande responsabilité a une
   classe pivot qui délègue à des collaborateurs à champ unique ; les
   collaborateurs vivent dans le sous-package de leur orchestrateur
   (render/, input/, popup/, preview/), reliés par des scells
   `@RestrictTo(LIBRARY_GROUP)` quand l'état partagé l'exige.
3. **Testabilité** — 2 610 tests unitaires JVM (JUnit 5, Robolectric pour le
   cycle de vie des vues) ; aucun test instrumenté requis.
4. **Défensif côté rendu** — tous les offsets sont clampés à
   `[0, doc.length()]` dans le chemin de dessin ; un painter de plugin qui
   lève est retiré, jamais un crash.
5. **Grands fichiers** — au-delà de 2,5 M chars / 50 k lignes, l'analyse,
   le folding et les inlays sont coupés ; le texte reste éditable
   (rope O(log N), splice d'index de lignes O(lignes) — mesuré).

## Voir aussi

- [`README.md`](README.md) — présentation, installation, build.
- [`FEATURES.md`](FEATURES.md) — matrice des fonctionnalités.
- [`USAGE.md`](USAGE.md) — guide d'intégration.
- [`CHANGELOG.md`](CHANGELOG.md) — historique des versions.
