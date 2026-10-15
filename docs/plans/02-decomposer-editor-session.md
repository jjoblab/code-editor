# Plan 02 — Décomposer `EditorSession`

> Phase 2 — plan d'implémentation, sans implémentation lourde.

## 1. Constat mesuré (base 3.49.2 durcie)

| Mesure | Valeur |
|---|---|
| Lignes | 1 588 |
| Méthodes publiques | ≈ 92 |
| Champs | 10 (déjà maigre — l'engraissement est en méthodes) |
| Collaborateurs déjà extraits | `UndoManager`/`UndoRecorder`, `ImeBridge`, `CommentToggler`, `RestyleEngine`, `SessionAnnotations`, `FoldRegions`, `DiagnosticShift`, `EditOps`, `SnippetSession`, `PostfixCompletion` (SurroundWith a été retiré au lot M-02 — audit 3.69.0) |

Le pattern « orchestrateur + collaborateurs » est déjà bien avancé ;
il reste trois familles de méthodes volumineuses dans la session :
les variantes de remplacement (`replaceText`, `replaceRange`,
`commitText`, `insertSnippet`, `applyCodeFolds`… ~350 lignes), la
plomberie undo/redo (relais vers `UndoManager`, ~200 lignes) et la
publication d'événements vers l'IME/la vue (listeners, ~150 lignes).

## 2. Objectifs mesurables

- `EditorSession` ≤ 900 lignes, ≤ 50 méthodes publiques.
- Un test JVM pur par collaborateur sans instancier la session
  complète.
- Zéro rupture d'API : les relais publics restent (l'éditeur et les
  hôtes les appellent).

## 3. Étapes

1. Extraire `session/EditPipeline.java` : normalisation des plages,
   clamp, application via `doc.replace`, notifications de plis
   (`DiagnosticShift`), étape undo groupée. La session garde des
   relais d'une ligne. Tests de non-régression :
   `EditorSessionTest`, `CaretClampAfterReplaceTest`,
   `FoldEditSafetyTest`, `UndoPipelineTest` — inchangés.
2. Extraire `session/SessionEvents.java` : listes de listeners,
   fan-out thread-safe (les diagnostics arrivent du thread LSP —
   voir `EditorDiagnosticsPusher`). Tests :
   `DiagnosticsByLineTest`, `SelectionMutatorsNotifyImeTest`.
3. Rabattre le garde-fou de budget (`check-class-budgets.sh`, plan 01)
   à 900 lignes pour la session à la fin.
4. Documenter dans `ARCHITECTURE.md` (tableau de démantèlement).

## 4. Risques

- `EditPipeline` touche le cœur de frappe : chaque étape doit être
  couverte par un test existant VERT SANS MODIFICATION — le pipeline
  est un déménagement, pas une réécriture.
- L'ordre notifications (plis → IME → vue) est subtil
  (`V105ImeAndFoldTest`, `V106ImeAndWrapTest` l'épinglent) : le
  fan-out doit conserver l'ordre exact.

## 5. Ordre de migration

EditPipeline (1) d'abord — plus gros lot et le mieux couvert ;
SessionEvents (2) ensuite. Deux PR indépendantes.
