# cel-core — moteur d'édition pur Java

> 1 470 tests · JUnit 5 (JVM hôte) · aucune dépendance Android en sortie.

`cel-core` est le socle de la bibliothèque : modèle de texte, moteur
d'édition, coloration syntaxique intégrée, plis, retour à la ligne,
recherche/remplacement, complétion et registre de langages. Tout y est du
**Java pur** — aucun import `android.*` — exécutable et testable sur la
JVM hôte, sans SDK Android au runtime (le SDK reste requis par le build,
tous les modules étant des `com.android.library`).

## Position dans l'ensemble

```
:cel-textmate ─┐
:cel-lsp-api ──┼─► :cel-core   (aucune dépendance sortante)
:cel-lsp ──────┤     (compileOnly)
:cel-ui ───────┘
```

- **Tout le monde dépend de cel-core** ; cel-core ne dépend de personne.
- `:cel-lsp-api` et `:cel-textmate` l'étendent sans le modifier
  (crochets `Language`, `TextMateTokenizer`).
- `:cel-ui` rend ce moteur à l'écran ; `:demo` l'utilise via `:cel-ui`.

Le contrat moteur → amont est volontairement réduit : les entrées sont
le texte, le langage déclaré et les événements de mutation ; les sorties
sont des styles par ligne (`StyledLine`), des annotations (diagnostics,
plis, inlays) et des événements. Rien dans cel-core ne connaît une vue.

## Fonctionnalités

### Structure de texte (`rope/`, `document/`)

- **Rope** — arbre binaire équilibré par poids, immuable : insertion,
  remplacement et sous-séquence en O(log N), partage structurel.
- **EditorDocument** — index de lignes `lineStarts[]` re-splicé
  incrémentalement à chaque édition (O(lignes) — 0,11 ms mesuré sur
  50 000 lignes, budget de frappe 2 ms) ; `lineForOffset` en
  O(log L) par recherche binaire ; matérialisation du texte paresseuse,
  cachée par révision.
- **Grands fichiers** — seuils `isLarge` (2,5 M chars / 50 k lignes) :
  analyse, plis et inlays coupés au-delà, l'édition reste intégralement
  fonctionnelle.
- **Selection** — caret et plage normalisés (une seule sélection par
  session ; l'édition multi-curseur n'est pas implémentée — voir
  `FEATURES.md` § Perspectives).

### Moteur d'édition (`session/`, `edit/`)

- **EditorSession** — point d'entrée des mutations ; **une seule porte
  d'édition** (`replaceRangeWithCaret`/`doReplaceRange`) qui décale
  annotations, plis et composition IME puis notifie les listeners.
- **Undo/redo** — coalescence des frappes continues (`UndoManager`,
  `UndoRecorder`) : une frappe continue = un undo ; pas de copie
  complète du texte.
- **Édition assistée** (`edit/SmartTyping`, `SmartNewline`,
  `XmlNewline`) — auto-close des paires, suppression pair-aware,
  indentation électrique (dedent sur `}`, `return`, `case`…), nouvelle
  ligne intelligente pilotée par le profil de langage.
- **Commentaires** (`CommentToggler`) — bascule ligne/bloc selon la
  syntaxe déclarée du langage.
- **IME** (`ImeBridge`) — composition et `replaceText` avec conservation
  du caret en cours de composition.
- **Batch** — `beginBatch`/`endBatch` pour les opérations multi-édit.

### Analyse et services

- **Coloration intégrée** (`highlight/`) — `SyntaxHighlighter`
  (orchestrateur) + **14 tokéniseurs Java par famille**
  (`highlight/tokenizer/`) ; coloration **incrémentale par ligne** avec
  état lexical de sortie et cascade stop-rule, exécutée sur un thread
  de restyle dédié (`RestyleEngine`).
- **Crochet grammaires** — interface `TextMateTokenizer` +
  `SyntaxHighlighter.setTextMateTokenizer(...)` : sans implémentation
  enregistrée, le tokéniseur intégré prend le relais ; avec
  `:cel-textmate`, la coloration passe par les grammaires (documents
  ≤ 800 lignes, `TEXTMATE_LINE_LIMIT` ; lignes ≤ 5 000 caractères,
  `TEXTMATE_CHAR_LIMIT` — les fichiers minifiés restent à la voie
  intégrée, au coût linéaire).
- **Plis** (`fold/FoldModel`, `highlight/FoldDetector`) — régions,
  niveaux, fusion ; **retour à la ligne** (`wrap/WrapModel`, prefix
  sums) ; **recherche/remplacement** (`find/FindReplace`) avec options
  et remplacements sûrs ; **snippets** (`snippet/SnippetSession` —
  tab stops, placeholders, mirroring) ; **complétion intégrée**
  (`completion/CompletionSession`, signatures, badges par type) ;
  **quick doc** (`doc/QuickDoc`, parsing Javadoc/KDoc) ;
  **navigation** (`NavigationMenu`) ; **re-mapping d'offsets**
  (`shift/DiagnosticShift`) des diagnostics/inlays/jetons sémantiques
  après édition.
- **Cache de rendu par ligne** (`cache/LineRenderCache`) — triple-stamp
  (texte + inlay + sémantique), LRU 512, prefetch idle (±1 viewport
  après 150 ms de calme).

### Registre de langages (`languages/`)

- `LanguageRegistry` — registre observable (id, alias, extension) ;
  **29 profils intégrés** (`BuiltinLanguages`) : python, markdown,
  json, lua, xml, css, shell, yaml, sql, properties, toml, smali, log,
  java, kotlin, javascript, typescript, c, cpp, go, rust, ruby, php,
  swift, dart, groovy, scala, csharp, text — routés par **14 familles**
  (`SyntaxFamily`).
- `LanguageProfile.builder(...)` — délimiteurs de commentaires, paires
  de crochets, mots-clés (coloration **et** complétion), déclencheurs
  de nouvelle ligne : enregistrer un langage d'une famille existante =
  une entrée de table, zéro Java.

## Organisation des packages

```
jo.codeeditor/
├── rope/         — Rope (arbre équilibré)
├── document/     — EditorDocument, Selection
├── session/      — EditorSession, RestyleEngine, ImeBridge,
│                   UndoManager/UndoRecorder, CommentToggler,
│                   SessionAnnotations, FoldRegions
├── edit/         — EditOps, SmartTyping, SmartNewline, XmlNewline,
│                   CodeContext, BracketPairs, CommentSyntax, WordBounds…
├── find/         — FindReplace, FindOptions, Match
├── shift/        — DiagnosticShift, EditSpan
├── snippet/      — SnippetSession
├── fold/         — FoldModel, FoldRegion
├── wrap/         — WrapModel
├── cache/        — LineRenderCache
├── completion/   — CompletionSession, SignatureHelpController
├── doc/          — QuickDoc
├── navigation/   — NavigationMenu
├── highlight/    — SyntaxHighlighter, StyledLine, TokenType, LexState,
│   └── tokenizer/  — 14 tokéniseurs par famille + KeywordTables
└── languages/    — LanguageRegistry, BuiltinLanguages, LanguageProfile,
                   SyntaxFamily
```

## Utilisation minimale

```java
EditorDocument doc = EditorDocument.of("public class Hello {\n    \n}");
EditorSession session = new EditorSession(doc);
session.setLanguage("java");

session.setSelection(30);
session.typeChar('(');   // auto-close
session.undo();

session.dispose();       // libère le thread de restyle (onDestroy de l'hôte)
```

```java
// Enregistrer son propre langage (une entrée de table, zéro Java) :
LanguageRegistry.register(LanguageProfile.builder("mylang")
        .family(SyntaxFamily.C_LIKE)
        .alias("ml").extension("ml")
        .keywords("if", "else", "repeat", "until")
        .commentSyntax(new CommentSyntax("#", null, null))
        .newlineTrigger(NewlineTrigger.onSuffix(":"))
        .build());
session.setLanguage("mylang");
```

## Limites connues

- **Une seule sélection par session** — le multi-curseur est une
  piste d'évolution documentée (`FEATURES.md` § Perspectives), pas une
  fonctionnalité livrée.
- **Grands fichiers** — au-delà des seuils, l'analyse (coloration,
  plis, inlays) est coupée : le texte reste éditable mais non coloré.
- **Mots-clés en dur** — l'ensemble des mots-clés des 29 profils vit
  dans `BuiltinLanguages` (données Java) ; le chantier C2 de l'ADR-002
  (tokéniseur générique piloté par la table) est en cours sur la
  branche multi-langages.

## Tests

```bash
./gradlew :cel-core:test     # 1 470 tests JUnit 5, JVM hôte
```

Suites notables : `EditorSessionFuzzTest`, `RopeFuzzTest` (robustesse),
`EditorSessionBenchmark`, `LineRenderCacheBenchmark` (performances —
mesures citées dans le `README.md` racine), `*TokenizerTest` (14
familles), `SmartTyping*`, `UndoPipelineTest`.

## Voir aussi

- [`README.md`](../../README.md) — présentation, installation, chiffres.
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — cycle d'édition détaillé.
- [`FEATURES.md`](../../FEATURES.md) — matrice factuelle par fonctionnalité.
- [`editor/cel-textmate/README.md`](../cel-textmate/README.md) — la voie
  grammaires qui branche le crochet `TextMateTokenizer`.
- [`editor/cel-lsp-api/README.md`](../cel-lsp-api/README.md) — le SPI
  qui enrichit le moteur côté langage.
