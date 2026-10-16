# Audit indépendant de contre-vérification — `jjoblab/code-editor` 3.69.0

> Documents archivés (A-08) : audit de contre-vérification réalisé sur la base `4f84308` (v3.69.0), **avant** exécution du plan. Le suivi de l'exécution en cours vit dans [`SUIVI-EXECUTION.md`](SUIVI-EXECUTION.md) ; les décisions de pilotage dans [`../../adr/ADR-001-mandat-execution-audit.md`](../../adr/ADR-001-mandat-execution-audit.md).

Re-vérification complète de l'audit `AUDIT-code-editor-3.69.0.md`, étude comparative avec un éditeur Android mature (désigné `[EXT-1]`, conformément à `AGENT.md`), et plan d'exécution consolidé.

## Documents

| Fichier | Contenu |
|---|---|
| [`01-verification-audit.md`](01-verification-audit.md) | Vérification constat par constat (D-01→D-09, P-01→P-06, M-01→M-02, A-01→A-08, §8, métriques) avec preuves re-exécutées. **Verdict : audit exact.** D-05 aggravé (recherche sur le thread UI → ANR). 3 constats nouveaux (N-1 à N-3). |
| [`02-etude-comparative.md`](02-etude-comparative.md) | Étude comparative avec un éditeur Android mature désigné `[EXT-1]` (structure, threads, complétion LSP/snippets, recherche, logging, tests, build, API, a11y) ; enseignements par axe + encadré de conformité `AGENT.md` (interdiction de citation/copie dans le dépôt). |
| [`03-plan-execution.md`](03-plan-execution.md) | Plan d'exécution consolidé : lot L0 (environnement/mesures) ajouté, D-05 remonté (L2b), L1 étendu (cas limites textEdit + timeouts LSP), décisions propriétaire listées, règles AGENT.md rappelées. |
| [`SUIVI-EXECUTION.md`](SUIVI-EXECUTION.md) | Journal d'exécution du plan : état par lot, mesures « rouge avant / vert après », écarts constatés. Mis à jour au fil de l'exécution. |

## Faits saillants

- **33 constats sur 34 confirmés intégralement** (lignes exactes, chiffres exacts : 70 `Log`, 41 `Thread.sleep`, 97 `setAccessible`, 24 entrées lint, 278 commits…) ; 1 confirmé avec nuance (D-06).
- **Point `[S]` levé et aggravé** : la recherche regex s'exécute sur le **thread principal** (`Handler(mainLooper)`, debounce 160 ms) → regex catastrophique = ANR. Recommandation : remonter D-05 en priorité haute.
- **Étude comparative** : le projet mature est en avance sur la complétion LSP/snippets, la nullité d'API et l'infra de build ; il est **en retard** sur les tests (ratio 1,4 % vs 0,25–0,82, CI sans test ni lint) — l'avantage n°1 de `code-editor` à préserver (L9).
- **Prérequis d'exécution** : JDK 17+ avec `javac` et SDK Android indispensables avant tout lot (aucun build possible sans, y compris les tests « pur Java »).

## Bases

- Dépôt vérifié : `jjoblab/code-editor`, détaché sur `4f84308` (v3.69.0, base de l'audit d'origine) ; `main` a avancé à `44f7a7f` (fix complétion XML — voir N-2 du document 01). L'exécution part de `44f7a7f`.
- Référence de comparaison : projet externe désigné `[EXT-1]` (conformément à `AGENT.md`, aucun nom, lien ni hash n'apparaît dans ces documents).
- Limite commune aux deux audits : vérification statique uniquement (pas de SDK Android dans l'environnement de vérification) — levée par le lot L0 avant exécution.
