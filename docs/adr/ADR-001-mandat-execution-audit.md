# ADR-001 — Mandat d'exécution du plan d'audit 3.69.0

- **Statut** : accepté
- **Date** : 2026-10-15
- **Décideur** : propriétaire du dépôt (mandat explicite)

## Contexte

L'audit de contre-vérification de la v3.69.0 (archivé dans [`../audit-3.69.0/`](../audit-3.69.0/README.md)) a confirmé 33/34 constats de l'audit d'origine et produit un plan d'exécution en lots L0→L10. Le plan §4 liste neuf décisions 🧑‍⚖️ qui exigent normalement l'arbitrage explicite du propriétaire avant exécution. Le propriétaire a mandaté l'**exécution intégrale** du plan (« toutes les étapes ») avec les modalités suivantes :

- travail sur une **branche dédiée unique** (`agent/exec-audit-3.70.0`), maintenue pendant toute la durée de l'exécution ;
- **poussée après chaque lot terminé** (validation comprise) ;
- **pas de publication d'artefacts** pendant l'exécution (la publication reste à la main du propriétaire une fois tout terminé) ;
- rapports d'audit et décisions archivés dans le dépôt (présents ADR + `docs/audit-3.69.0/`).

Ce mandat tranche les neuf décisions 🧑‍⚖️ en retenant l'option recommandée de chacune.

## Décisions tranchées

| # | Décision (plan §4) | Lot | Option retenue |
|---|---|---|---|
| 1 | Portée D-01 (complétion/snippets) | L1 | **Étapes 1-4 d'abord** (résolution paresseuse différée — conformément à l'étude comparative, qui montre qu'elle n'est pas branchée non plus ailleurs) |
| 2 | Ordre D-05 (recherche regex sûre) | L2b | **Lot dédié remonté** juste après L2 (ANR sur le thread UI) |
| 3 | `LspFeature` / `setDisabledFeatures` | L3 | **Honorer** : porte évaluée avant l'installation des providers (aucune requête n'atteint le serveur quand la fonctionnalité est désactivée) |
| 4 | Classes mortes M-02 | L6 | `SnippetSession` et `OnSnippetEditListener` : **câbler** (livré par L1) ; `SurroundWith` : **supprimer** (0 référence, aucun appelant) ; `FoldModel` : **supprimer la duplication** (une seule implémentation de fusion de régions ; l'API publique est conservée si elle sert l'hôte) ; `NavigationMenu` : **supprimer les 6 méthodes mortes**, extraire les types si coquille |
| 5 | Politique M-03 (API publique non testée) | L6/L9 | **« au moins un test »** : chaque API publique sans test reçoit un test de contrat ; à défaut, `@Deprecated` (retrait au palier suivant) |
| 6 | Recherche hors thread UI | L2b/L7 | **Différer** : le garde-fou délai + plafond protège déjà contre l'ANR ; le déport complet est un chantier plus gros |
| 7 | Niveau RTL | L10 | **Non supporté documenté** (pas de plan d'implémentation bidi dans ce cycle) |
| 8 | Historique des rapports dans le dépôt | A-08 | **Archiver sous `docs/audit-3.69.0/`** (présent) avec suivi d'exécution dédié |
| 9 | Timeouts LSP par requête (L1-bis) | L1 | **Inclure maintenant** (un commit séparé après les étapes 1-4) |

## Conséquences

- La branche `agent/exec-audit-3.70.0` cumule les lots L0→L10 ; chaque lot est poussé après validation (`./gradlew clean assembleDebug test lint --continue` vert, scripts de contrôle verts, `git status` propre — définition « terminé » d'`AGENT.md`).
- Les règles d'`AGENT.md` restent intégralement applicables pendant l'exécution : commits français `fix(scope): …` un par changement, test rouge-avant/vert-après par correctif de bug, contrôle des références externes avant chaque commit, jamais de régénération des baselines lint, plafonds de classes abaissés en cliquet.
- La version cible de l'ensemble est **3.70.0** (bump unique en fin d'exécution, entrées de CHANGELOG ajoutées lot par lot dans la section 3.70.0).
- Aucune fusion vers `main`, aucun tag, aucune publication pendant l'exécution — le propriétaire reprend la main à la fin.
- Les écarts éventuels par rapport au plan (impossibilité technique, effet de bord découvert) sont consignés dans [`../audit-3.69.0/SUIVI-EXECUTION.md`](../audit-3.69.0/SUIVI-EXECUTION.md) et motivés ; en cas de doute d'origine de code : arrêt et demande, conformément à `AGENT.md`.
