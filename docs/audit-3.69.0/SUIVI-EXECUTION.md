# Suivi d'exécution du plan d'audit 3.69.0

> Journal d'exécution des lots L0→L10 (plan : [`03-plan-execution.md`](03-plan-execution.md) ; mandat et décisions : [`../adr/ADR-001-mandat-execution-audit.md`](../adr/ADR-001-mandat-execution-audit.md)).
> Chaque lot consigne : les commandes jouées, les mesures « rouge avant / vert après », les verdicts et les écarts. Mise à jour lot par lot.

## État global

| Lot | Contenu | État |
|---|---|---|
| L0 | Environnement + build de référence + mesures de base | ✅ terminé |
| L1 | D-01 complétion/snippets + timeouts LSP (L1-bis) | ⏳ en cours |
| L2 | D-02 fuite de thread RestyleEngine | ⬜ à faire |
| L2b | D-05 recherche regex sûre | ⬜ à faire |
| L3 | D-03 LspFeature + D-04 EditOps | ⬜ à faire |
| L4 | D-06 sockets + D-07 catch muets + D-08 logging | ⬜ à faire |
| L5 | M-01 code mort certain | ⬜ à faire |
| L6 | M-02/M-03 classes et API mortes | ⬜ à faire |
| L7 | P-01…P-06 performance | ⬜ à faire |
| L8 | D-09 documentation dérivée | ⬜ à faire |
| L9 | A-06 tests | ⬜ à faire |
| L10 | A-01…A-08 structure | ⬜ à faire |

---

## L0 — Environnement, build de référence, mesures de base

**Environnement** (prérequis §0 du plan) : JDK 17 (avec `javac`) + SDK Android (platform 34, build-tools) installés ; `local.properties` créé hors versionnement. Gradle 9.5.1 / AGP 9.0.0 conformes à `AGENT.md`.

**Build de référence sur `main` `44f7a7f`** (base de départ du plan, N-2) :

- `./gradlew assembleDebug` — **vert**.
- `./gradlew test` — **vert : 1 776 tests, 0 échec** (cel-core 926 · cel-lsp-api 28 · cel-lsp 142 · cel-ui 653 · demo 27).
- `./gradlew lint` — **vert** (0 erreur / 0 avertissement, baseline inchangée).
- `scripts/check-doc-figures.sh` — **conforme** (annoncé=1776, exécuté=1776, écart=0).
- `scripts/check-class-budgets.sh` — **conforme** (EditorView 3 850 l. / 181 champs / 279 méthodes publiques ; EditorSession 1 590 l. / 10 champs / 100 méthodes).
- `scripts/check-external-refs.sh` — **conforme**.

**Mesures de base** (harnais jetable exécuté puis retiré ; chiffres = « rouge avant » des lots concernés) :

| Mesure | Protocole | Résultat |
|---|---|---|
| P-01 | `commitText` de 2 caractères × 200, document ~1 M caractères (25 000 lignes), caret en fin | **6,38 ms/op** — au-dessus du budget de frappe perceptible ; la simulation postfix matérialise le préfixe entier du document à chaque frappe multi-caractères |
| D-02 | 200 sessions créées + restyle asynchrone, **sans** `dispose()` ; compte de threads vivants nommés `EditorSession-restyle` | **201 threads vivants** — fuite confirmée (1 thread par session, jamais expiré) |
| D-02 (protocole) | comptage avant/après création, `setLanguage`, `restyleAsync` | le thread apparaît **au premier restyle asynchrone** (`setLanguage` le déclenche), pas à la seule création |
| P-06 | lecture du site `EditorSelectionGestures.java:663` (`getText()` avant résolution de tooltip d'inlay) | la ligne est sur le chemin **appui long** (`handleInlayHintLongPress`), **pas** sur un `MOVE` — pas de hot-path ; l'action P-06 se concentre sur l'extraction par plage |
| P-02 | mesure différée au lot L7 | — (nécessite le synchroniseur LSP en contexte test) |

**Écarts par rapport au plan** : aucun. La contrainte mémoire de l'environnement d'exécution (daemon Gradle borné à 1024 Mo, tests séquentiels par module) n'affecte pas les verdicts.
