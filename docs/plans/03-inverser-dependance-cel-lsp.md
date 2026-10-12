# Plan 03 — Inverser la dépendance `cel-lsp → cel-ui`

> Phase 2 — plan d'implémentation, sans implémentation lourde.

## 1. Constat mesuré

`cel-lsp` déclare `compileOnly(project(":cel-ui"))` et importe
`jo.codeeditor.view` dans **6 fichiers** :
`LspEditor` (+ `view.popup` ×2), `LspDocumentSynchronizer`
(+ `view.popup`), `LspFoldPuller`, `LspSemanticTokensPuller`,
`LspWorkspaceEditApplier`, `LspDiagnosticsManager`.

Symboles réellement utilisés : `EditorView` (champs `@RestrictTo` :
session, langue, diagnostics, génération), et deux références à
`view.popup` (voie d'exécution historique).

Conséquences : le client LSP n'est pas testable en JVM pure (il tire
la vue Android) ; l'hôte doit déclarer 4 dépendances pour utiliser le
LSP ; la couche client est couplée au rendu.

## 2. Cible

```
cel-lsp  →  cel-lsp-api (api(...))  →  cel-core
```

`cel-lsp` ne dépend plus que de `cel-lsp-api` + `cel-core` ;
l'implémentation de l'interface vit côté `cel-ui` (ou côté hôte).

## 3. Interface `EditorHost` (dans `cel-lsp-api`, package `jo.codeeditor.lang.host`)

```java
public interface EditorHost {
    /** Le document édité, versionné — voir VersionCounter/Lot 2. */
    EditorDocument document();
    /** Numéro de version courant de la synchronisation. */
    int documentVersion();
    /** Applique des éditions au document (thread UI, session). */
    void applyEdits(List<jo.codeeditor.lang.model.TextEdit> edits);
    /** Notification de diagnostics (thread quelconque). */
    void publishDiagnostics(List<Diagnostic> diagnostics);
    /** Soumission sur la voie de complétion (thread UI attendu). */
    void submitBackground(Runnable task);
    /** Observateur d'extinction de l'hôte (dispose). */
    void onClosed(Runnable r);
}
```

(Ajuster au fil de la migration : commencer par les 4 méthodes ci-dessus,
qui couvrent les 6 fichiers.)

## 4. Étapes

1. Créer `EditorHost` dans `cel-lsp-api` + une implémentation
   `LspEditorViewHost` dans `cel-ui` (adaptateur mince autour de
   `EditorView`, 40 lignes). Aucun comportement changé.
2. Migrer `LspDiagnosticsManager` et `LspDocumentSynchronizer` (les
   plus simples : lecture session + notifications) — injecter
   `EditorHost` dans `LspEditor`, supprimer les imports `view.*`
   de ces deux fichiers. Tests : extraire `LspDocumentSynchronizerTest`
   en JVM pure avec un `EditorHost` factice.
3. Migrer `LspFoldPuller` / `LspSemanticTokensPuller`
   (lecture document + application de plis via `applyEdits`-like :
   ajouter `void applyCodeFolds(...)` à `EditorHost`).
4. Migrer `LspWorkspaceEditApplier` (écriture disque — n'utilise la
   vue que pour la session courante : `document()` suffit).
5. Supprimer `compileOnly(project(":cel-ui"))` de `cel-lsp` ;
   `LspEditor` ne construit plus sur `EditorView` mais sur
   `EditorHost`. `cel-ui` (ou la démo) câble
   `new LspEditorViewHost(view)`.
6. CHANGELOG : *Changed* — l'installation côté hôte passe de 4
   dépendances à 3 ; l'API publique `LspEditor(EditorView)` devient
   `LspEditor(EditorHost)` (rupture signalée, adaptation fournie).

## 5. Risques et tests

- Les champs `@RestrictTo(LIBRARY_GROUP)` de la vue ne doivent PAS
  fuiter dans l'interface — l'adaptateur seul y accède.
- Tests de non-régression : toute la suite `cel-lsp` actuelle
  (91+ tests) doit rester verte via l'adaptateur ; les tests LSP
  migrés deviennent JVM purs (plus de Robolectric) — c'est le gain.
- Risque de cycle : `cel-lsp-api` ne doit JAMAIS importer
  `jo.codeeditor.view` — à contrôler par une règle de paquet dans le
  garde-fou CI (grep simple dans `check-class-budgets.sh` ou dédié).

## 6. Ordre

Étape 1 (interface + adaptateur) fusionnable seule ; puis 2 → 3 → 4 →
5, chaque PR supprime définitivement des imports `view.*`.
