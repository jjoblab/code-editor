<!--
Modèle de pull request — reprend la « définition de terminé » d'AGENT.md.
Cochez chaque critère avant de demander la revue.
-->

## Objet

<!-- Une phrase : ce que la PR change et pourquoi. -->

## Type de changement

- [ ] `fix` — correctif de bug
- [ ] `feat` — nouvelle fonctionnalité
- [ ] `perf` — performance
- [ ] `refactor` / `chore` / `docs` / `test`

## Définition de « terminé » (copiée d'AGENT.md)

- [ ] `./gradlew clean assembleDebug testDebugUnitTest lint --continue`
      **vert** — lint bloquant, aucune régression par rapport à la base.
- [ ] Chaque correctif de bug est livré avec un test qui échoue **avant**
      et réussit **après** (rouge → vert visible dans la PR).
- [ ] Aucun test supprimé ni affaibli pour « faire passer ».
- [ ] `CHANGELOG.md` à jour (français, sans nom de projet tiers).
- [ ] `scripts/check-external-refs.sh` vert.
- [ ] Chiffres de la documentation mis à jour si les décomptes ont
      changé (`scripts/check-doc-figures.sh` vert en CI).
- [ ] `git status` propre — aucun fichier de build committé.

## Notes pour la revue

<!-- Points sensibles, ruptures d'API signalées au CHANGELOG, captures
     ou sorties de tests utiles au relecteur. -->
