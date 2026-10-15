# Suivi d'exécution du plan d'audit 3.69.0

> Journal d'exécution des lots L0→L10 (plan : [`03-plan-execution.md`](03-plan-execution.md) ; mandat et décisions : [`../adr/ADR-001-mandat-execution-audit.md`](../adr/ADR-001-mandat-execution-audit.md)).
> Chaque lot consigne : les commandes jouées, les mesures « rouge avant / vert après », les verdicts et les écarts. Mise à jour lot par lot.

## État global

| Lot | Contenu | État |
|---|---|---|
| L0 | Environnement + build de référence + mesures de base | ✅ terminé |
| L1 | D-01 complétion/snippets + timeouts LSP (L1-bis) | ✅ terminé |
| L2 | D-02 fuite de thread RestyleEngine | ✅ terminé |
| L2b | D-05 recherche regex sûre | ✅ terminé |
| L3 | D-03 LspFeature + D-04 EditOps | ✅ terminé |
| L4 | D-06 sockets + D-07 catch muets + D-08 logging | ✅ terminé |
| L5 | M-01 code mort certain | ✅ terminé |
| L6 | M-02/M-03 classes et API mortes | ✅ terminé |
| L7 | P-01…P-06 performance | ✅ terminé |
| L8 | D-09 documentation dérivée | ⏳ en cours |
| L9 | A-06 tests | ⬜ à faire |
| L10 | A-01…A-08 structure | ⬜ à faire |

> Exécution reprise en session 2 (environnement de travail réinitialisé
> entre-temps) : re-clone, toolchain JDK 17 + SDK Android reconstruite en
> espace utilisateur, build de référence revalidé sur la pointe de branche
> (1 817 tests / 0 échec à la reprise, avant les apports de L7), fusion du
> `main` 3.70.1 (réédition JitPack, sans lien avec le code). Chaque lot est
> poussé sur `agent/exec-audit-3.70.0` après validation complète.

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

---

## L1 — D-01 complétion/snippets + L1-bis timeouts (commits 1ead739, 41688c9, 36a3b27, 363508d)

Étendue du modèle `CompletionItem` (snippet, plage de remplacement normalisée *remplacement*, filterText, sortText) ; le pont transporte l'édition et les champs de tri ; `SnippetSession` CÂBLÉE à l'acceptation (expansion apurée, Tab/Shift+Tab en ordre LSP, miroirs, `OnSnippetEditListener` déclenché) ; la plage `textEdit` respectée (correctif « console.console.log ») ; timeouts par type de requête (`LspRequestTimeouts`, surchargeables par serveur, garde-fou 10 s sur `initialize`). Tests : serveur factice — chevauchement de préfixe, clamp de plage en fin de document, plage inversée, tri par sortText, filtrage par filterText, familles C-like et XML de la porte de contexte (N-2).

## L2 — D-02 fuite de thread RestyleEngine (commits f17324d, 8abbe37)

`allowCoreThreadTimeOut(true)` (30 s) sur l'exécuteur mono-thread du moteur : la fuite à vie sans `dispose()` est impossible (rouge avant : 201 threads vivants après 200 sessions, L0). Javadoc de `dispose()` et `USAGE.md` corrigés (la vue NE libère PAS pour l'hôte). Test de comptage d'expiration sous borne constante.

## L2b — D-05 recherche regex sûre (commit 516cb4b)

Vue `CharSequence` garde-fou interrompant le backtracking au-delà du budget (250 ms), plafond 10 000 correspondances avec signal « tronqué », états `tooCostly`/`truncated` exposés à la barre, `Pattern` centralisé (duplication supprimée), passes de remplacement avortant proprement. Le motif catastrophique `(a+)+b` retourne borné < 1 s (rouge avant : figeage du THREAD UI → ANR). La recherche reste sur le thread UI — la mise hors thread UI est différée conformément à la décision n° 6.

## L3 — D-03 LspFeature honoré + D-04 EditOps sans état statique (commits e80549c, 3d815e5)

La porte `getDisabledFeatures()` est évaluée AVANT l'installation des providers (aucune requête n'atteint le serveur pour une fonctionnalité désactivée — testé). `EditOps.tryPostfix` retourne un objet immuable PAR APPEL ; l'ancienne paire `tryPostfixCompletion`/`getPostfixInsertText` (champ statique partagé) est `@Deprecated` et inerte. Test de concurrence 2 threads × 10 000 itérations (rouge avant / vert après) ; plus aucun champ statique non final dans `edit/`.

## L4 — D-06/D-07/D-08 (commits 51249d1, e3d7498, 9e135b2)

`LspStartException` typée sur `SocketConnectionProvider` (message français, cause conservée, Javadoc transport en clair) ; les deux catch muets de `LspCodeActionsProvider` journalisent en warn (test par réponse malformée) ; les 70 `android.util.Log` de `cel-lsp` reroutés vers `EditorLog`, `Log.i` par frappe supprimé (grep vide sur `src/main` — test NoDirectAndroidLoggingTest).

## L5 — M-01 code mort certain (commits abb773a, cf59a11, fa764b0, 086ea85)

Retrait des six symboles à zéro référence (isBlockActive, bridgeBlank, isPreviewableFile, isXmlLayoutFile, lineStartOffset, countLeadingWhitespace) — un commit par fichier, compile + tests verts.

## L6 — M-02/M-03 classes et API mortes (commits 5c6f7d1, dd3dffc, 706df7e, 15032e6)

`SurroundWith` supprimée (103 l., zéro référence). Fusion de régions de plis : une seule implémentation (`FoldModel.mergeRegions` délègue, la copie divergente retirée). `NavigationMenu` réduit à ses types et filtres vivants (machinerie de picker jamais appelée retirée). M-03 : balayage complet — seules `BlockEditor` et `SymbolBarView` restent publiques sans test ; contrats écrits (5 + 3 tests). `LocalSocketConnectionProvider` (décision D-06) : testée au lot L4.

## L7 — P-01…P-06 performance (commits a40f250, e5074e8, 7e95bb8, b2ac10e, a2a3f5a, 710a7c6, 3041a68)

Rouges avant / verts après, mesures sur cet environnement (protocoles L0, document ~1 M caractères, 25 000 lignes) :

| Point | Rouge avant | Vert après | Correctif |
|---|---|---|---|
| P-01 | 3,02 ms/op (frappe 2 c. en fin de document) | 0,24 ms/op (÷12,5) | vue paresseuse `EditOps.simulatedInsert` — plus de copie du préfixe entier |
| P-02 | 2,16 ms/op par frappe (pont getText) + 8,5 ms par envoi (diff + vérification) | 0,029 ms/op (÷75,8) + envoi O(événements) | sync incrémentale pilotée par les éditions suivies (positions O(log n) + rope immuable) ; invariants O(1) (révisions consécutives, longueur) remplacent la reconstruction de vérification ; repli plein texte |
| P-03 | 3 découpages du document par `detect()` | 1 découpage partagé | cœurs `String[]` package-private, entrées publiques inchangées (test d'union exacte) |
| P-04 | recompilation regex par reconnaissance | Pattern statique final | `CodeContext.isCaseLabel` (contrat figé) |
| P-05 | 16 RectF alloués par draw et par frame | champ `scratchRect` par painter | set → draws séquentiels (mono-thread UI) |
| P-06 | getText() entier au long-press inlay (thread UI) | vue de rope immuable O(1) | l'interface prend déjà CharSequence |

Découvertes du lot : **fix(lsp) e5074e8** — `View.post()` sur une vue détachée ne détecte pas la non-attache (branche morte) : le pont LSP ne se posait jamais sur une vue jamais attachée (rouge/vert LspDetachedViewBridgeTest). Figures de tests mises à jour au fil du lot (README/AGENT : 1 776 → 1 865, contrôle d'écart conforme).