# cel-lsp-api — SPI langage (contrats et modèles)

> 32 tests · JUnit 5 (JVM hôte) · `api(:cel-core)`, rien d'autre.

`cel-lsp-api` définit **le contrat entre le moteur et les moteurs de
langage** : ce qu'un langage peut fournir (complétion, diagnostics,
documentation, actions…) et ce que l'hôte doit fournir en retour
(`EditorHost`). Il ne contient aucune implémentation de langage —
seulement des interfaces, des modèles de données et deux valeurs neutres
(`EmptyLanguage`, `LspNoopAnalyzer` vit côté `:cel-lsp`).

## Position dans l'ensemble

```
:cel-ui ────────► :cel-lsp-api ────────► :cel-core
:cel-lsp ────────► (compileOnly)
                   ▲          ▲
       implémente │          │ programme contre
                   │          │
        :cel-lsp ──┘          └── l'hôte (app) apporte ses providers
```

- **`api(:cel-core)`** — il étend les types du moteur (`StyledLine`,
  `TextMateTokenizer` côté crochet, etc.).
- **`:cel-lsp` programme contre ce module en `compileOnly`** — la
  couche LSP ne connaît ni la vue ni le moteur autrement que par ces
  contrats (inversion de dépendance du plan 03 : `:cel-lsp` ne dépend
  plus de `:cel-ui`).
- **`:cel-ui` l'expose en `api`** — l'application hôte qui dépend de
  `cel-ui` voit le SPI sans dépendance supplémentaire.

## Contenu

### Contrats racine (`jo.codeeditor.lang`)

| Type | Rôle |
|---|---|
| `Language` | façade d'un langage : fournit chaque provider demandé (ou `null` → capacité absente) |
| `Analyzer` | boucle d'analyse asynchrone d'un document ouvert |
| `EmptyLanguage` | valeur par défaut neutre (aucun provider) |
| `BracketMatch` | appariement des crochets pour le surlignage |
| `PopupCoordinator` | coordination des popups (complétion contre signature) |
| `StyleReceiver` | récepteur des styles calculés (thread de restyle → vue) |

### `host/` — le contrat hôte

`EditorHost` est **l'inversion du plan 03** : ce que le client LSP
demande à l'éditeur hôte (obtenir le texte, appliquer une édition,
signaler un diagnostic, déplacer le caret…) — sans connaître `EditorView`.
L'adaptateur de référence, `LspEditorViewHost`, vit côté `:cel-ui` ;
un hôte sans vue fournit sa propre implémentation.

### `model/` — 15 classes de données

`Diagnostic`, `CompletionItem`, `HoverContent`, `SignatureHelp`,
`CodeAction`, `Symbol`, `InlayHint`, `TextEdit`, `RenameResult`,
`DefinitionLocation`, `DocumentHighlight`, `ViewZone`, `CodeBlock`,
`Parameter`, `Signature` — modèles immuables partagés par toutes les
implémentations (intégrée, LSP, maison).

### `provider/` — 17 interfaces de capacité

`CompletionProvider`, `CompletionPublisher`, `DiagnosticsProvider`,
`HoverProvider`, `DefinitionProvider`, `TypeDefinitionProvider`,
`ImplementationsProvider`, `SuperDefinitionProvider`,
`ReferencesProvider`, `RenameProvider`, `Formatter`,
`CodeActionsProvider`, `SymbolProvider`, `SignatureHelpProvider`,
`InlayHintProvider`, `DocumentHighlightProvider`, `ViewZoneProvider`.

Toutes suivent le même esprit : appel asynchrone, annulable, résultat
rendu au récepteur — jamais de blocage du thread UI.

`CompletionPublisher` sait depuis 4.3.0 transporter le drapeau
**« liste tronquée »** (`setIncomplete(boolean)`, à défaut no-op) :
un fournisseur dont le backend plafonne ses réponses le pose avant
`flush()` ; le client qui l'honore sait que sa base est partielle et
re-requête à chaque édition du préfixe au lieu de filtrer localement
sur une liste amputée. Un fournisseur qui ne l'appelle pas publie une
liste réputée complète — aucun changement pour les fournisseurs
existants.

## Pourquoi un module séparé ?

Historiquement, la couche LSP et la vue se référençaient mutuellement ;
le plan 03 (voir `docs/plans/03-inverser-dependance-cel-lsp.md`) a
retourné la dépendance : **`cel-lsp-api` est le seul point de contact**.
Conséquences concrètes :

- `:cel-lsp` se publie sans dépendre de `:cel-ui` (le consommateur
  choisit sa vue — la nôtre ou la sienne) ;
- les annotations de nullité du contrat sont `compileOnly`
  (rétention CLASS : invisibles au runtime, absentes du POM publié) ;
- un moteur de langage maison n'a besoin que de ce module + `cel-core`.

## Utilisation (implémenter une capacité maison)

```java
public final class MonLangage extends EmptyLanguage {
    @Override
    public CompletionProvider getCompletionProvider() {
        return (text, caret, publisher) ->
                publisher.addItem(new CompletionItem("TODO", "mot-clé"));
    }
}
```

Puis côté vue : `editorView.setLanguage(...)` (voir `USAGE.md`,
§ Résolveurs — brancher son propre moteur de langage).

## Tests

```bash
./gradlew :cel-lsp-api:test     # 32 tests JUnit 5
```

Tests de contrat sur les modèles (invariants d'égalité, fabriques) et
sur les valeurs neutres.

## Voir aussi

- [`editor/cel-lsp/README.md`](../cel-lsp/README.md) — l'implémentation
  LSP de ces contrats.
- [`editor/cel-ui/README.md`](../cel-ui/README.md) — l'adaptateur
  `LspEditorViewHost` du contrat `EditorHost`.
- [`USAGE.md`](../../USAGE.md) — guide des résolveurs.
- [`docs/adr/ADR-001-mandat-execution-audit.md`](../../docs/adr/ADR-001-mandat-execution-audit.md) —
  contexte de l'inversion (plan 03).
