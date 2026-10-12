# Plan 07 — Qualité des tests (JaCoCo, LSP JVM pur, unification JUnit)

> Phase 2 — plan d'implémentation, sans implémentation lourde.

## 1. Constat (mesuré)

- 1 370 tests exécutés (cel-core 887 · cel-lsp-api 22 · cel-lsp 93 ·
  cel-ui 361 · demo 7) ; 1 315 annotations `@Test` ; 0 couverture
  mesurée (JaCoCo absent).
- `cel-lsp` teste déjà en JVM pure pour la moitié (UriUtils,
  LspTextUtils, VersionCounter, SessionEditGroup, WorkspaceDiskEdits…)
  mais les ponts `LspEditor`/synchroniseur exigent Robolectric (car
  dépendance `EditorView` — cf. plan 03).
- Deux frameworks : JUnit 5 (cel-core, cel-lsp via moteur vintage
  pour les tests Jupiter) et JUnit 4 + Robolectric (cel-ui, démo).
- Tests de rendu : Robolectric avec snapshots logiciels
  (MagnifierPaintIsolation, FoldAwareRenderPasses) — aucun test
  instrumenté sur vrai GPU.

## 2. Cible

1. **JaCoCo par module** : rapport XML en CI + artefact, seuil de
   couverture NON bloquant v1 (information), puis ratchet à la
   hausse par module une fois la base mesurée.
2. **Client LSP 100 % JVM pur** : rendu possible après le plan 03
   (inversion de dépendance) — supprimer Robolectric de cel-lsp.
3. **Unification JUnit 5** dans cel-ui (Robolectric 4.13 supporte
   Jupiter via `RobolectricTestRunner` JUnit5 ? non — via extension
   `@ExtendWith(RobolectricExtension.class)` expérimentale ; en
   attendant : moteur vintage côté cel-core déjà en place, garder
   JUnit 4 pour les tests Robolectric, migrer les tests purs).
4. **3-5 tests instrumentés** (connectedAndroidTest, optionnel,
   parc d'appareils requis) : fumée de rendu (une frame d'un
   document 1 000 lignes < 16 ms), IME réelle, magnifier matérielle.

## 3. Étapes

1. JaCoCo : plugin `jacoco` appliqué par module (ou via le plugin de
   convention du plan 04) + tâche `testDebugUnitTest.finalizedBy
   jacocoTestReport` ; CI : téléversement des XML + badge local
   (pas de service tiers).
2. Figer la couverture courante par module dans
   `docs/plans/07-couverture-base.md` (tableau de départ).
3. Plan 03 étapes 2-5 achevées → `cel-lsp/build.gradle.kts` : retrait
   de Robolectric ; les tests LspDocumentSynchronizer/LspEditor
   passent sur `EditorHost` factice (Jupiter).
4. Unification partielle : les nouveaux tests cel-ui non-Robolectric
   en Jupiter (le runner JUnit4 reste pour les tests de vues) ;
   documenter la règle dans CONTRIBUTING.
5. Tests instrumentés : module `demo/src/androidTest` —
   `RenderSmokeInstrumentedTest` — exécutés en local par le
   propriétaire (pas en CI : pas de parc), garde `@Ignore` + procédure.

## 4. Risques

- JaCoCo + AGP 9 : utiliser la tache `testDebugUnitTest` (variante
  debug) — la couverture du code Java pur est fiable ; les rapports
  de cel-ui incluent du code appelé seulement par Android (frise)
  — le seuil doit en tenir compte (ratchet bas au départ).
- Robolectric JUnit5 : ne pas bloquer le plan dessus — l'unification
  complète est un objectif, pas un prérequis.

## 5. Non-régression

Le nombre total de tests ne peut que croître : le garde-fou
`check-doc-figures.sh` (±5 %) absorbe les migrations sans dériver.
