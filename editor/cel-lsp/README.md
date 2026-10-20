# cel-lsp — intégration Language Server Protocol

> 209 tests · JUnit 5 (+ moteur vintage) · LSP4J 0.22.0 et Gson 2.14.0
> embarquées (voir [`NOTICE`](../../NOTICE)) · `compileOnly` sur
> `:cel-core` et `:cel-lsp-api`.

`cel-lsp` transforme un serveur de langage externe (process, socket,
socket locale ou in-process) en `Language` du SPI : complétion,
diagnostics, hover, définitions, références, renommage, actions de code,
formatage, inlay hints, symboles — tout ce que le serveur annonce et que
le SPI sait consommer.

## Position dans l'ensemble

```
┌────────── Application hôte ──────────┐
│  :cel-ui ───► EditorHost (SPI)  ─────┼──► :cel-lsp-api
│  adaptateur LspEditorViewHost        │        ▲
└──────────────────┬───────────────────┘        │ compileOnly
                   │                            │
             :cel-lsp ──────────────────────────┘
             (LSP4J + Gson embarquées)
```

- Il **programme contre `:cel-lsp-api`** (`EditorHost`, providers,
  modèles) — jamais contre `EditorView` (inversion du plan 03 : la
  dépendance `:cel-lsp → :cel-ui` a été retirée).
- Ses dépendances vers `:cel-core` et `:cel-lsp-api` sont
  **`compileOnly`** : le consommateur doit les ajouter lui-même
  (typiquement via `cel-ui`, qui les apporte transitivement) — aucune
  duplication d'artefacts.
- LSP4J (EPL-2.0) et Gson (Apache-2.0) sont `implementation` :
  embarquées dans l'AAR, documentées dans `NOTICE`.

## Fonctionnalités

### Cycle de vie projet / serveur

- **`LspProject`** — workspace multi-serveurs : définitions par langage
  (`LanguageServerDefinition`), démarrage paresseux, `shutdown()`
  **borné à 2 s par serveur**.
- **`LanguageServerWrapper`** — encapsule un serveur : initialisation
  avec capacités clientes (négociation honnête — on n'annonce que ce
  qu'on consomme), redémarrage, arrêt.
- **`LspEditor`** — cycle de vie d'un **document** ouvert : connexion,
  synchronisation, requêtes, déconnexion assistée par
  `LspDocumentSynchronizer` (sync incrémentale versionnée),
  `LspDiagnosticsManager`, `LspSemanticTokensPuller` (jetons sémantiques
  → styles), `LspFoldPuller`, `LspWorkspaceEditApplier`.
- **Déconnexion complète (3.74.0, ADR-003)** — `disconnect()` est
  idempotent et restaure `EmptyLanguage` sur l'hôte (toutes les
  capacités LSP fermées, aucune requête), retire l'écouteur d'édition,
  invalide le jeton de génération (une continuation de `connect()` dont
  le jeton est périmé s'abandonne : impossible de brancher le langage
  du fichier fermé pendant un changement d'onglet). Garde commune : tout
  provider est muet une fois la session fermée. Les débounceurs
  didChange/didSave renaissent à chaque reconnexion.

### Connexions (`connection/`)

Cinq providers interchangeables : `StreamConnectionProvider` (flux
bruts), `InProcessStreamConnectionProvider` (serveur dans le même
process — les tests tournent ainsi), `ProcessBuilderConnectionProvider`
(process externe), `SocketConnectionProvider` (TCP),
`LocalSocketConnectionProvider` (socket locale Unix/Android).

### Capacités (14 `Lsp*Provider`)

Complétion (avec contexte de déclenchement et `TextEdit`),
diagnostics (publication serveur → `Diagnostic` du SPI, sévérités et
tags), hover, définition / définition de type / implémentations /
super-définition / références, renommage (préparation + application),
actions de code (avec résolution et application des éditions),
symboles document, aide de signature, inlay hints (avec résolution),
surlignage de document. Le formateur vit dans `LspFormatter` :
formatage de document ET de plage (`rangeFormatting` quand le serveur
l'annonce, refus propre sinon — le document entier n'est JAMAIS formaté
à la place d'une plage), options `FormattingOptions` (hôte → détection
d'indentation → 4 espaces), éditions serveur validées (bornes,
chevauchements, tri stable) et échecs journalisés sans jamais écrire le
contenu du document.

### Robustesse (constats d'audit, testés)

- **`LspRequestTimeouts`** — chaque requête est bornée (pas de wait
  infini) ; **`LspDebouncer`** — groupement des didChange ;
- réponses malformées tolérées (les tests injectent des serveurs faux
  qui répondent n'importe quoi — `CodeActionsMalformedResponseTest`,
  `FakeActionsCodeServer`…) ;
- `WorkspacePathGuard` / `WorkspaceDiskEdits` — les éditions d'espace
  de travail restent dans les limites autorisées du workspace ;
- `IoCompat` — compat API 24 (implémentations manquantes) ;
- `SessionEditGroup`, `AtomicFileWriter` — écritures de session sûres.

## Utilisation

```java
LspProject project = new LspProject("/path/to/workspace");
project.addServerDefinition(myServerDefinition); // ex. un serveur Java
LspEditor editor = project.createEditor("file:///path/to/Foo.java");
editor.setHost(new jo.codeeditor.view.LspEditorViewHost(editorView));
editor.connect();
// … édition, complétion, diagnostics vivent ensuite à travers la vue
project.shutdown(); // borné à 2 s par serveur
```

Le consommateur type ajoute `cel-ui` **et** `cel-lsp` : l'adaptateur
`LspEditorViewHost` (côté vue) implémente `EditorHost` pour le client
LSP. Un hôte **sans vue** fournit sa propre implémentation de
`EditorHost` (c'est le sens de l'inversion).

## Limites connues

- **Le thread de requête ne doit pas être le thread UI** : le
  garde-fou délai + plafond protège contre l'ANR mais le déport complet
  de la recherche hors thread UI a été explicitement différé (décision
  n°6 de l'ADR-001).
- **`semanticTokens` n'est demandé que si le serveur l'annonce** —
  aucune surclaration de capacités (règle « ne pas mentir », P2-7).
- Android minSdk 24 : certaines API LSP4J récentes sont shuntées par
  `IoCompat`.

## Tests

```bash
./gradlew :cel-lsp:test     # 209 tests
```

Les tests branchent de **vrais serveurs faux in-process**
(`FakeActionsCodeServer`, `FakeCompletionTriggerServer`,
`FakeInlayResolveServer`) sur `InProcessStreamConnectionProvider` :
round-trips complets requête → réponse, réponses malformées, timeouts,
annulations, sync incrémentale — aucun serveur externe requis.

## Voir aussi

- [`editor/cel-lsp-api/README.md`](../cel-lsp-api/README.md) — les
  contrats que ce module implémente.
- [`editor/cel-ui/README.md`](../cel-ui/README.md) — l'adaptateur
  `LspEditorViewHost`.
- [`USAGE.md`](../../USAGE.md) — § LSP (Language Server Protocol).
- [`NOTICE`](../../NOTICE) — obligations de licence LSP4J/Gson.
