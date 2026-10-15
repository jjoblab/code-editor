# Plan 01 — Décomposer `EditorView`

> Phase 2 — plan d'implémentation, sans implémentation lourde.
> Étape S livrée avec ce plan : `scripts/check-class-budgets.sh` (garde-fou
> de budget, branché en CI).

## 1. Constat mesuré (base 3.49.2 durcie)

| Mesure | Valeur |
|---|---|
| Lignes | 3 335 |
| Méthodes publiques | ≈ 223 |
| Champs | ≈ 165 (dont la majorité `@RestrictTo(LIBRARY_GROUP)`, partagés avec les collaborateurs `popup`, `render`, `input`) |
| Collaborateurs déjà extraits (EditorBarTools a été retiré au lot B15 — 3.69.0) | `EditorPopupManager` (et ses 9 popups), `EditorLanguageBridge`, `EditorDocumentActions`, `EditorDiagnosticsPusher`, `EditorReferencesController`, `EditorCommands`, `preview/`, `render/`, `input/` |

Le schéma actuel : les collaborateurs lisent/écrivent directement les
champs de la vue (ex. `EditorCompletionPopup` manipule
`view.completionItems`, `view.completionTokenStart`,
`view.completionPrefix` ; `EditorLanguageBridge` écrit
`view.findHighlights`, `view.documentHighlights`). L'orchestrateur est
donc un « état partagé géant » : cohérent mais impossible à tester
isolément, et chaque nouvelle fonctionnalité l'engraisse.

## 2. Objectifs mesurables

- `EditorView` ≤ 1 200 lignes, ≤ 60 méthodes publiques, ≤ 40 champs.
- Chaque contrôleur possède et teste son état (tests JVM sans vue).
- Zéro rupture d'API publique : les relais actuels
  (`setXxx`/`isXxx`) restent, délégations d'une ligne.

## 3. Découpage en objets d'état par fonctionnalité

| Objet d'état (nouveau, `view/state/`) | Possédé par | Champs déplacés (extrait actuel) |
|---|---|---|
| `CompletionState` | `EditorCompletionPopup` | `completionItems`, `completionTokenStart`, `completionPrefix`, `fetchInFlight`, `completionGeneration` |
| `SelectionState` | `EditorView` (délégation à `EditorSession` déjà existante — supprimer les caches redondants de la vue) | miroirs de sélection de la vue |
| `ScrollState` | `input/EditorScrollManager` | `scrollX/Y`, `maxV/maxH`, `prefetcher` flags |
| `FoldViewState` | `EditorFoldIndex` | `collapsedByDefault`, `foldRequests`, compteurs |
| `GuideState` | `render/` (accès lecture seule via `EditorPaintContext`) | `indentGuideStyle`, `variableColorsEnabled`, flags de rendu |
| `StickyState` | `render/` | `stickyLinesEnabled` + cache du détecteur |
| `FindState` | `EditorView` → propre contrôleur `FindStateController` | `findHighlights`, `findCurrentIndex` |
| `DiagnosticState` | `EditorDiagnosticsPusher` | tables par ligne déjà là — finaliser le déménagement |

## 4. Étapes (une par commit, chacune vert + relais conservés)

1. **Étape S (livrée ici)** : `scripts/check-class-budgets.sh` — le CI
   échoue si `EditorView` dépasse les plafonds courants (3 350 lignes /
   170 champs / 255 méthodes publiques — décompte du script incluant
   les constructeurs). La décomposition ne pourra
   jamais régresser en silence ; les plafonds se rabattent à chaque
   étape franchie.
2. Créer `view/state/CompletionState` (record-like, mutabilité confinée
   au popup) + déplacer les 5 champs ; `EditorView` garde des relais
   dépréciés `@RestrictTo` le temps de la migration des appelants ;
   tests : `EditorCompletionPopup` devient testable sans `EditorView`
   complète (Robolectric allégé).
3. `view/state/FindState` + `FindStateController` : les popups de
   recherche écrivent via le contrôleur ; test : cycle
   trouver/remplacer sans monter une vue.
4. `FoldViewState` : le pli est déjà à moitié déménagé
   (`EditorFoldIndex`) — terminer : `view.collapsedByDefault` et
   `foldRequests` migrent ; test de non-régression :
   `FoldAwareRenderPassesTest` doit rester vert sans modification.
5. `ScrollState` dans `EditorScrollManager` : déplacer
   `scrollX/scrollY/maxV/maxH` + le préchargeur ; tests :
   `ScrollMaxVAndZoomWrapTest`, `MaxHIncrementalTest` inchangés.
6. Supprimer les miroirs de sélection de la vue (lecture directe
   `session.getSelection()`) — grep des lectures de
   `view.selection*`.
7. Rabattre les plafonds du garde-fou à chaque étape ; à la fin,
   documenter les nouveaux chiffres dans `ARCHITECTURE.md`
   (le tableau de démantèlement s'allonge d'une ligne
   « EditorView → 1 2xx l. »).

## 5. Risques et parades

- **Vue de compatibilité** : tant que les relais existent, double
  source de vérité possible (champ + état) — parade : les relais ne
  font QUE déléguer (une ligne), jamais de cache local ; le
  grep des écritures directes de champs fait partie du critère.
- **Ordre des frames de rendu** : les painters lisent l'état pendant
  `onDraw` — les objets d'état doivent être « lecture seule pendant le
  rendu » (publication sur le thread UI uniquement) ; documenter dans
  la Javadoc de `view/state/`.
- **Robolectric** : chaque étape déplacée doit SUPPRIMER une
  dépendance de test à la vue complète, jamais en ajouter.

## 6. Tests de non-régression (existants, à garder verts à l'identique)

`FoldAwareRenderPassesTest`, `ScrollMaxVAndZoomWrapTest`,
`EditorViewLifecycleTest`, `EditorInstanceStateTest` (sauvegarde de
l'état — l'état sauvegardé doit rester sérialisable via les mêmes
clés), `EditorPendingTapDismissTest`, `CompletionContextGateTest`.

## 7. Ordre de migration

Étape 2 (complétion) d'abord — plus grand gain de testabilité ;
puis 3 (find), 4 (plis), 5 (scroll), 6 (sélection). Les rendu/
input ne bougent pas (déjà extraits). Estimation : 5 PR de taille
moyenne, indépendantes, chacune fusionnable seule.
