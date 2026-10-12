# Rapport de vérification et durcissement — `jjoblab/code-editor`

> **Branche** : `agent/durcissement-3.49.2` (39 commits au-delà de `main`,
> base d'analyse `ac1ecaa`, version 3.49.2)
> **Méthode** : vérification de chaque constat dans le code avant correction,
> test rouge→vert par correctif, jalon `clean assembleDebug testDebugUnitTest
> lint --continue` + garde-fous à chaque lot.
> **Conventions** : les projets externes sont désignés par `[EXT-n]`
> (jamais nommés) ; les niveaux de preuve sont `[R]` reproduit par
> exécution, `[T]` démontré par test, `[L]` lu uniquement, `[S]` suspecté.

---

## J0 — Baseline (avant toute modification)

| Mesure | Valeur |
|---|---|
| Tests | 1 280 (cel-core 875 · cel-lsp-api 22 · cel-lsp 27 · cel-ui 356) — 0 échec |
| Lint | `abortOnError = false` partout ; 46 avertissements non bloquants (cel-core 3 · cel-lsp-api 2 · cel-lsp 10 · cel-ui 8 · démo 23) |
| Lignes (main) | cel-core 14 423 · cel-lsp-api 1 437 · cel-lsp 4 777 · cel-ui 22 500 · démo 1 719 |
| `@Test` | 1 232 |
| Historique | 140 commits (toutes refs) · 21 tags |

---

## Lot 9A — Purge de l'arbre de travail (juste après la baseline)

**Verdict** : d'accord (décision du propriétaire, appliquée intégralement).
**Niveau de preuve** : [T] (garde-fou vert, tests verts, inventaire hors dépôt).

**Constat vérifié** : 65 occurrences réelles des motifs (150 brutes, 85 faux
positifs `CursorAnchorInfo`/`cursorAfter` documentés) + 85 mentions de
produits tiers en comparaison ; 8 commits à motif dans les messages ;
`docs/AUDIT.md` (477 l.) entièrement consacré à la comparaison amont ;
mentions de projets tiers dans CHANGELOG/README/NOTICE/RAPPORT et ~40
fichiers de code.

**Solution retenue** : identique à la proposition, exécutée en vagues —
suppression d'AUDIT.md, README sans Crédits, NOTICE limité aux dépendances
réelles (LSP4J 0.22.0 EPL-2.0, Gson 2.10.1 Apache-2.0), reformulation des
commentaires en descriptions génériques autosuffisantes, CHANGELOG
réécrit en 5 étapes (266→247 Ko), section `AGENT.md` « Interdiction de
mentionner des projets externes » + garde-fou
`scripts/check-external-refs.sh` (heuristiques génériques + motifs
nominatifs lus dans `FORBIDDEN_REFS_EXTRA`, jamais committés) branché en
CI et en hook `commit-msg`.

**Résidus découverts et purgés en fin de session (au fil du Lot 9B)** :
`c4bec0cf7` cité au CHANGELOG, « [EXT-5] / [EXT-4] » dans `.gitignore`,
« [EXT-6] » au CHANGELOG (serveurs cités en exemple), « [EXT-7] »
(bibliothèques d'alors : moteur d'expressions régulières, codings,
thème) dans CHANGELOG/code, « module [EXT-7] » dans FEATURES.md.

**Tests ajoutés** : 8 tests du garde-fou (`check-external-refs-test.sh`)
— motif fictif injecté par variable d'environnement.
**Risques résiduels** : le contrôle nominatif exige le secret
`FORBIDDEN_REFS_EXTRA` en CI (absent : ignoré sans erreur, signalé).
**Commits** : `119b9f5` → `808acdb` (9 commits purge + garde-fou).

---

## Lot 5 — Honnêteté de la CI : lint bloquant `[P0]`

**Verdict** : d'accord.
**Niveau de preuve** : [R] (violation volontaire jetable → échec constaté).

**Constat vérifié** : `abortOnError = false` + `checkReleaseBuilds = false`
dans les 5 modules ; le job CI « lint » ne pouvait jamais échouer.

**Solution retenue** : identique. `abortOnError = true` +
`warningsAsErrors = true` partout, baselines figées par module (46
avertissements existants), publication du rapport lint en artefact CI.
`-Xlint:all` non retenu (bruit non maîtrisé — mesuré sur un essai) ;
la publication et `jitpack.yml` (qui saute `-x test -x lint`)
sont inchangés.

**Correction en cours de route** : le kind de surlignage était mis en
majuscules dépendamment de la locale (`LintBaseline` fragile) —
normalisé en minuscules.

**Correction finale (découverte au Lot 9B)** : l'entrée
`AndroidGradlePluginVersion` de la baseline cel-core pointait le
`gradle-wrapper.properties` du projet racine avec un chemin `$HOME`
dépendant du dossier de checkout — un rebuild dans un autre dossier
échouait (`LintBaselineFixed`). Vérification désactivée pour cel-core
(version Gradle épinglée volontairement, justification en commentaire),
entrée retirée : le build redevient reproductible quel que soit le
dossier.

**Critères d'acceptation** : une violation volontaire (non committée)
fait échouer `./gradlew lint` — constaté ; baselines committées ;
CI verte.
**Commits** : `808acdb`, `68460e6`, `b7785bd`.

---

## Lot 1 — Sécurité des écritures disque issues du serveur LSP `[P0]`

**Verdict** : d'accord (5/5 constats confirmés).
**Niveau de preuve** : [R] (tests reproduisant chaque échec avant correctif).

**Constats vérifiés** (`LspWorkspaceEditApplier` ≈ l. 110-141,
`LspRenameProvider` ≈ l. 95-135, `IoCompat` ≈ l. 82-98, 13 constructions
`file://` ad hoc, `ResourceOperation` ignorées en silence) : tous exacts.

**Solution retenue** : identique, étendue —
1. `UriUtils` : `File → URI` (forme `file:///…` encodée) et `URI → File`
   (décodage `%XX`, rejet des schémas ≠ `file` et autorités non vides) ;
   forme tolérante en entrée, stricte en sortie ;
2. `WorkspacePathGuard` : résolution canonique, frontière de dossier,
   liens symboliques sortants refusés — violation = refus journalisé ;
3. `IoCompat.writeUtf8Atomically` : temporaire même dossier + flush +
   `FileDescriptor.sync()` + renommage (API 26 protégée, repli API 24) ;
4. `WorkspaceEdit` tout-ou-rien : validation intégrale (plages + chemins)
   avant toute écriture ; `ResourceOperation` explicitement refusées ;
5. déduplication : `WorkspaceDiskEdits` (remplace les deux copies
   d'`applyEditsToDiskFile`) et `LspTextUtils` (positions UTF-16,
   surrogates, CRLF, clamp spec — remplace les deux conversions).

**Tests ajoutés** : 43 (Jupiter) — URI hors workspace jamais écrite,
aller-retour `%20`/`#`/non-ASCII, crash simulé → fichier intact,
3 fichiers dont 1 invalide → aucun modifié, 0 construction ad hoc hors
`UriUtils` (grep).
**Risques résiduels** : la comparaison tolérante d'
`EditorReferencesController` (cel-ui) compare des chemins sans les
construire — documenté, non modifié (hors périmètre disque).
**Commits** : `c52d978`, `7c6eccc`.

---

## Lot 2 — Intégrité de la synchronisation de document LSP `[P0]`

**Verdict** : d'accord.
**Niveau de preuve** : [R].

**Constats vérifiés** : `documentVersion` `int` non synchronisé, écrit
depuis 3 threads (debounce l. 84-96, `flushPendingChange` l. 131,
didOpen) ; 0 `getVersion()` dans `cel-lsp/src/main` ;
`applyTextEdits` remplaçait `[0, length]` en bloc ; le même risque
existait pour `additionalTextEdits` de complétion.

**Solution retenue** : identique — `VersionCounter` (AtomicInteger),
`LspTextUtils.isStaleEdit` (null = 3.13 sans contrôle, ≠ version =
périmé), `SessionEditGroup` (tri par offset décroissant, UNE étape
d'undo, sélection préservée, rejet de chevauchement) ; version
mémorisée au moment de la requête (code action, complétion) et
comparée à l'arrivée. **Décision documentée** : le rebase de positions
d'éditions périmées est refusé (non sûr en cas de chevauchement) ;
la rebase via `DiagnosticShift`/`EditSpan` reste possible pour les
diagnostics (déjà le cas).

**Tests ajoutés** : 12 (versions strictement croissantes sous charge
concurrente 8×250, édition périmée refusée, UN undo par correctif,
plis hors zone conservés).
**Commits** : `9728d75`.

---

## Lot 3 — Cycle de vie du serveur et débounce `[P1]`

**Verdict** : d'accord.
**Niveau de preuve** : [R].

**Constats vérifiés** : `getErrorStream` jamais lu (grep = 0), thread
créé par frappe + `interrupt()` du précédent, `didSave` sur thread non
daemon sans référence, `pendingText = text.toString()` à chaque frappe,
`stop()` = `destroy()` seul, échec de démarrage en `RuntimeException`.

**Solution retenue** : identique — pompe `stderr` daemon vers récepteur
borné (trace circulaire 64 lignes), `stop()` échelonné
`destroy() → waitFor() → destroyForcibly()` (API 26, repli API 24),
`LspStartException` typée avec queue `stderr` ; `LspDebouncer`
(ScheduledExecutorService mono-thread daemon, `cancel(false)` — jamais
d'interruption, CharSequence matérialisée à l'envoi), `didSave`
débouncé, `didClose` éteint tout.

**Tests ajoutés** : 9 (serveur écrivant 2 Mio sur `stderr` ne se bloque
pas ; arrêt borné et effectif ; 1 000 frappes → pas de thread par
frappe ; après extinction, aucun thread `lsp-*` vivant).
**Commits** : `09142cb`.

---

## Lot 4 — Performance du chemin de frappe `[P1]`

**Verdict** : d'accord pour l'étape 1 ; étape 2 écartée sur mesure.
**Niveau de preuve** : [R] (benchmark avant/après).

**Constats vérifiés** : `lineText` = `getText().substring(...)` (cache
invalidé à chaque révision → matérialisation complète par lecture de
ligne) ; `subText` existant non utilisé ; `replace()` recopie
l'index de lignes en O(lignes).

**Solution retenue** : étape 1 appliquée (`lineText` → `subText`,
sémantique strictement identique : sans `\n`, bornes clampées, `""`).
**Étape 2 ÉCARTÉE sur mesure** : le coût mesuré d'une édition est de
0,11 ms sur 50 000 lignes — très en deçà du budget de frappe de 2 ms ;
l'index sous-linéaire (comptage par nœud / arbre de Fenwick) n'apporterait
rien d'observable. `ARCHITECTURE.md` corrigé avec les chiffres réels
(l'affirmation « O(log N) par édition » reformulée : rope O(log N),
splice d'index O(lignes modifiées), 0,11 ms mesuré).
`Rope.toString` double copie : non modifié (`new String(char[])` copie
par conception — trivial et sûr à laisser).

**Benchmark** : `lineText` 2 881 → 413 ns/op (**×7**) ; édition +
splice 50 000 lignes : 0,11 ms.
**Tests ajoutés** : 4 (non-matérialisation instrumentée par réflexion
sur le mémo, sémantique de référence, bornes, salve de rendu simulée).
**Commits** : `9ac0ea8`.

---

## Lot 6 — Gestion des exceptions et journalisation `[P1]`

**Verdict** : d'accord.
**Niveau de preuve** : [R] (le test de frontière échouait avant le
correctif `installIfAbsent`, vert après).

**Constats vérifiés** : 35 `catch (Throwable` dans `editor/*/src/main`
(avale `OutOfMemoryError`/`StackOverflowError`), ≈ 20 catch vides, 2
`System.err` dans `RestyleEngine` (l. 352, 459).

**Solution retenue** : identique — interface `EditorLogger` (4 niveaux,
cause optionnelle) dans `cel-core`, `EditorLog` (AtomicReference, inerte
par défaut), `AndroidEditorLogger` (cel-ui, tag ≤ 23 caractères)
installé au premier chargement d'`EditorView` ; `catch (Exception)` ciblé
+ journalisation aux frontières de providers d'hôte ; **deux frontières
conservent `catch (Throwable)` volontairement, documentées en
commentaire** (`EditorPainterHost` : code de plugin arbitraire à chaque
frame — un painter fautif est retiré du registre plutôt qu'un crash en
boucle ; `ServiceConfigurationError` tolérée). Tous les catch vides
restants reçoivent un commentaire justificatif (rendu, best-effort
d'extinction, parseColor heuristique) ou une journalisation (providers).

**Correctif de bord découvert par le test** : le bloc statique
d'`EditorView` écrasait le logger installé par l'hôte →
`EditorLog.installIfAbsent` (compareAndSet depuis l'état inert) —
l'installation automatique ne remplace plus celle de l'hôte.

**Tests ajoutés** : 6 (provider qui lève une `RuntimeException` → vue
continue + journalisé [rouge avant : le Recording était écrasé par
l'installation automatique] ; `OutOfMemoryError` → se propage jamais
avalée ; hôte prioritaire sur l'installation automatique ; inert par
défaut ; installation/raccourcis ; appels concurrents).
**Risques résiduels** : le journal par défaut n'écrit rien tant que
l'hôte n'installe rien (choix assumé).
**Commits** : `aa9cd9b`, `1ed7308`, `d465b04`.

---

## Lot 7 — Exécution en arrière-plan et thread-safety du modèle `[P1]`

**Verdict** : d'accord en partie (le constat B sur l'immuabilité était
inexact — voir ci-dessous).
**Niveau de preuve** : [R] (rouge/vert sur la voie mono-thread simulée).

### Problème A (exécuteurs) — constats vérifiés

Exécuteur statique PUBLIC (`EditorPopupManager.FEATURE_EXECUTOR`,
mono-thread, processus entier, jamais fermé), `COMPLETION_EXECUTOR`
statique privé, 3 exécuteurs statiques en cel-lsp (folds, semantic,
disk-edits), 2 caches non bornés (`LspProject`,
`InProcessStreamConnectionProvider`).

### Solution retenue (améliorée par rapport à la proposition)

- **`EditorTaskLanes`** (cel-ui) : deux voies privées « features » et
  « complétion », chacune bornée à 2 threads daemon nommés
  (`cel-features-*`, `cel-completion-*`). La proposition d'un SPI
  `EditorTaskRunner` injectable a été jugée trop lourde pour le gain :
  les voies bornées + nommées satisfont les critères (un fournisseur
  bloqué n'occupe qu'un thread ; isolation complétion/features) sans
  nouvelle API à maintenir. **RUPTURE SIGNALÉE** : suppression de
  `FEATURE_EXECUTOR` (voir CHANGELOG et Décisions).
- **LspProject** : deux voies bornées fermées dans `shutdown()`
  (`cel-lsp` LSP4J, `cel-lsp-tasks` tirs/réécritures) ;
  `LspEditor.submitBackground` protégé contre l'extinction ; les 3
  exécuteurs statiques cel-lsp migrent sur la voie du projet ;
  `InProcess` : cache → pool de 2 fermé par `stop()`.
- `OpenTabDiagnosticsSweep` : voie statique privée daemon déjà conforme
  (décision : inchangée, documentée) ; `RestyleEngine` (par session,
  disposé) inchangé.

### Problème B (EditorDocument) — constat partiellement INFIRMÉ

Le document est **immuable par édition** (`replace()` renvoie une
nouvelle instance ; le commentaire de `RestyleEngine` était exact).
Les vrais défauts : champs non `final` (**publication non garantie**
vers les threads lecteurs — JMM §17.5) et mémo de `getText()` en deux
champs non volatiles (fenêtre « mémo chaud sans contenu »).

**Solution** : champs `final` + mémo `volatile` unique
(`cachedRevision` supprimé — la valeur recalculée est toujours
identique, race bénigne).

**Preuve rouge/vert** : le test `fournisseurBloque_neRetardePasUneAutreTache`
échoue sur une voie mono-thread (simulée temporairement) et passe sur
la voie à 2 threads.
**Tests ajoutés** : 6 (blocage toléré, isolation de la voie complétion,
threads daemon nommés ; voie LSP éteinte après `shutdown()` — aucun
thread `cel-lsp-*`/`lsp-*` restant ; `submitBackground` après extinction
silencieux ; `getText()` concurrent 8×500 — jamais null, jamais
divergent).
**Commits** : `670e7e9`, `7e06602`, `f2d2c54`, `1cd407b`.

---

## Lot 8 — Documentation et hygiène du dépôt `[P1]`

**Verdict** : d'accord.
**Niveau de preuve** : [T] (couverture des tags vérifiée par script ;
rouge/vert du contrôle de chiffres).

**Traité** :
- **CHANGELOG reconstitué** : 10 versions taguées sans entrée
  (v3.44.1 → v3.49.2) + note pour les balises internes v3.37.0.n/.n2 —
  couverture 21/21 tags vérifiée par script (sources : corps des
  commits de chaque plage).
- **Chiffres mesurés** : 1 370 tests (cel-core 887 · cel-lsp-api 22 ·
  cel-lsp 93 · cel-ui 361 · démo 7) ; EditorView 3 335 l. ·
  EditorSession 1 588 · EditorRenderer 748 · EditorInputHandler 700 ;
  « 934 tests » (base d'audit périmée) purgé de README/CONTRIBUTING/
  FEATURES/AGENT ; tableau de démantèlement remesuré.
- **`scripts/check-doc-figures.sh`** : échoue si le total annoncé
  dérive de ±5 % du total exécuté (lecture des XML) ; branché en CI
  (rouge/vert vérifié).
- **Modes** : 380 fichiers 100755 → 100644, seul `gradlew` reste
  exécutable ; `.gitattributes` + renormalisation (gradlew.bat stocké
  LF, recomposé CRLF au checkout).
- **Hygiène GitHub** : `SECURITY.md` (canal privé, délais,
  divulgation coordonnée), `.github/CODEOWNERS` (focus sécurité
  disque/URI + chaîne de publication), `.github/PULL_REQUEST_TEMPLATE.md`
  (définition de terminé d'AGENT.md), `.github/dependabot.yml`
  (gradle + github-actions, hebdo).
- **Actions épinglées par SHA** (checkout v4.4.0, setup-java v4.9.1,
  setup-gradle v4.4.4, upload-artifact v4.6.2 — SHA résolus via l'API
  GitHub, mise à jour par Dependabot) ; `validate-wrappers` conservé.
- **Anti double déclenchement** : le job « release » de ci.yml et son
  déclenchement sur tags sont supprimés — un tag `v*` n'actionne plus
  QUE publish.yml (qui exécute déjà tests + publication).

**Découverte documentée** : la « coquille `branches: ain, master]` »
vue dans ci.yml était un artefact d'affichage d'outil (le séquence
`[m` est interprétée comme code d'échappement en sortie) — vérifié par
octets : le fichier d'origine et le nouveau contiennent bien
`[main, master]`. Aucune correction n'était nécessaire.

**Commits** : `562ccb2` → `b7785bd` (13 commits).

---

## Phase 2 — Plans d'architecture (optionnelle, plans uniquement)

Huit plans détaillés dans `docs/plans/` (étapes par commit, fichiers,
risques, tests de non-régression, ordre de migration) :
01 décomposition d'`EditorView` (objets d'état, objectifs ≤ 1 200 l. /
≤ 60 méthodes / ≤ 40 champs) ; 02 décomposition d'`EditorSession` ;
03 inversion `cel-lsp → cel-ui` par `EditorHost` dans cel-lsp-api ;
04 build centralisé ; 05 accessibilité/i18n/RTL ; 06 minification R8
(mesure d'abord) ; 07 qualité des tests ; 08 stabilité d'API.

**Étape S livrée** : `scripts/check-class-budgets.sh` — plafonds
ratchet (EditorView 3 350 l./170 champs/255 méthodes publiques ;
EditorSession 1 600 l./15/100), branché en CI, rouge vérifié.
**Commit** : `0d41d6f`, `6e1c8b0`.

---

## Lot 9B — Réécriture de l'historique et des tags (en dernier)

**Verdict** : appliqué intégralement, sans push.
**Niveau de preuve** : [R] (les six commandes du 9.0 retournent 0
après `gc --prune=now --aggressive`).

### Méthode

1. **Sauvegarde hors dépôt** (obligatoire) :
   `code-editor-AVANT-PURGE-FINAL.bundle` (git bundle --all) + le
   dépôt complet conservé en `code-editor-AVANT-PURGE/`. Métriques
   avant : 146 commits (toutes refs) · 21 tags · aucun tag signé.
2. **Clone frais** (`git clone --no-local`) + `git-filter-repo` 2.47 :
   - **table explicite** de 21 messages de commit (ancien hash →
     nouveau message, mêmes préfixes/intentions, sans projet tiers) +
     filet générique regex sur tous les messages et tags ;
   - `--replace-text` : 81 règles ciblées ordonnées (FQN de paquets
     externes et collocations d'abord, puis mots) ;
   - `docs/AUDIT.md` retiré de tout l'historique
     (`--path … --invert-paths`) ;
   - auteurs, dates et contenu fonctionnel inchangés.
3. **Écart de décompte justifié** : 143 commits (146 − **3** commits
   `docs/AUDIT.md` devenus vides : création/suivi/suppression de
   l'audit — élagage automatique de git-filter-repo). Tags : **21/21**,
   mêmes noms, repointés. fsck --full : propre.

### Vérifications (sorties réelles après gc)

| Commande (motifs 9.0 étendus aux produits/hashs/URLs) | Résultat |
|---|---|
| `git grep -P` (arbre à chaque tag + HEAD) | **0** |
| `git log --all --grep` (messages) | **0** |
| `git log --all -G` (contenu ajouté/retiré) | **0** |
| `git for-each-ref` (noms + messages de branches/tags) | **0** |
| noms de fichiers historisés | **0** |
| `git cat-file --batch-all-objects --batch \| grep -i` (filet, binaires compris) | **0** |

Le motif officiel du 9.0 **sans frontières de mot** renvoie 202 lignes :
toutes sont les faux positifs documentés à l'inventaire (la séquence
`sorA` à l'intérieur des identifiants Android `CursorAnchorInfo`,
`cursorAfter`, `cursorAnchorMonitorMode` — l'API de la plateforme,
aucune mention de projet).

### Diffs par tag (ancien dépôt en lecture seule)

Pour chaque tag v3.35.0 → v3.49.2 : `git diff --stat ancien nouveau`
ne montre que des fichiers de documentation et des commentaires
(CHANGELOG, README, NOTICE, RAPPORT, USAGE, MULTI_CURSOR, .gitignore,
Javadoc). Exemple type (`v3.37.0.n`, `LineRenderCache.java`) :
une seule ligne de Javadoc reformulée. `docs/AUDIT.md` disparaît des
tags où il existait. **HEAD** : build + tests + lint verts dans le
dépôt purgé (1 370 tests, 0 échec), les trois garde-fous verts, l'arbre
est strictement identique à celui du dépôt de travail au moment de la
réécriture (diff vide).

Le dépôt de travail a été remplacé par le dépôt purgé après ces
vérifications ; l'ancien dépôt et les bundles restent hors dossier
jusqu'à validation du propriétaire.

---

## Avant / Après

| Mesure | Baseline J0 | Fin de mission |
|---|---|---|
| Tests exécutés | 1 280 (875/22/27/356) | **1 370** (887/22/93/361/7) — 0 échec |
| `@Test` | 1 232 | 1 315+ |
| Lint | non bloquant, 46 avertissements tolérés | **bloquant** (abortOnError + warningsAsErrors), existant figé en baselines, 0 nouvelle violation |
| `catch (Throwable` (main) | 35 | 2 (frontières documentées) |
| `System.err` (production) | 2 | 0 |
| Catch vides sans commentaire | ≈ 20 | 0 |
| Exécuteur public statique | 1 (+ caches non bornés ×2) | 0 (voies bornées fermables) |
| Threads par frappe | 1 | 0 (planificateur daemon) |
| `lineText` | 2 881 ns/op | **413 ns/op (×7)** |
| Édition + splice (50 k lignes) | — | 0,11 ms (budget 2 ms) |
| CHANGELOG | 10 versions taguées sans entrée | couverture 21/21 tags |
| Fichiers en mode 100755 | 379/379 | 1 (`gradlew`) |
| Écriture disque LSP | non confinée, tronquante, par bloc | confinée, atomique, tout-ou-rien, versionnée |
| Motifs externes (arbre) | 65 + 85 mentions produits | 0 |
| Motifs externes (historique complet) | 8 messages + 15 contenus + 1 tag (+ produits) | **0** (tous objets, après gc) |

## Jalons et commits (39 au total, branche `agent/durcissement-3.49.2`)

`119b9f5`→`808acdb` purge arbre + garde-fou · `68460e6` lint bloquant ·
`c52d978`/`7c6eccc` sécurité disque · `9728d75` versions LSP ·
`09142cb` cycle de vie serveur · `9ac0ea8` perf lineText ·
`aa9cd9b`/`1ed7308`/`d465b04` journal + frontières ·
`670e7e9`/`7e06602`/`f2d2c54` voies d'exécution + immuabilité ·
`1cd407b` CHANGELOG · `562ccb2`→`b7785bd` Lot 8 (13 commits) ·
`0d41d6f`/`6e1c8b0` plans + budgets · purge finale des résidus
(`3f4428b`, `5b33be6`, `78ca9a3`, `0619fa5`, `b7785bd`).

## Points non traités et pourquoi

- **Étape 2 du Lot 4** (index de lignes sous-linéaire) : écartée sur
  mesure (0,11 ms ≪ 2 ms) — décision documentée, `ARCHITECTURE.md`
  corrigé.
- **`Rope.toString` sans double copie** : non trivial/sûr — justifié.
- **`safeCall(...)` générique du Lot 6** : non centralisé — chaque
  frontière a son repli propre (visible localement) ; journalisation
  directe ciblée retenue (minimalisme).
- **SPI `EditorTaskRunner` complet du Lot 7** : remplacé par des voies
  bornées privées (suffisant pour les critères, sans nouvelle API).
- **Comparaison tolérante d'`EditorReferencesController`** (Lot 1,
  reste) : comparaison de chemins sans écriture — hors périmètre.
- **`-Xlint:all`/`-Werror`** (Lot 5) : bruit non maîtrisé — non retenu.
- **Éclipse/fondation** (« Eclipse Nexus », `org.eclipse.*` textuel,
  `repo.eclipse.org` dans d'anciens récits de CHANGELOG) : hors motifs
  du 9.0 et indissociable des dépendances réelles (LSP4J =
  `org.eclipse.lsp4j`, exception NOTICE) — laissé, listé pour décision.
- **`kotlin-lsp`** (chaîne de donnée d'un test) et noms de
  bibliothèques d'alors dans des tableaux de dépendances 2.x
  (codings, yaml, annotations de nullité) : hors motifs, listés pour
  décision.

## Ce qui n'a pas pu être exécuté ou vérifié

- **CI distante** : jamais poussée (interdit) — les workflows sont
  validés en YAML et par exécution locale des étapes équivalentes ;
  le premier push réel montrera les jobs GitHub.
- **Tests instrumentés** : aucun appareil — non requis par la
  définition de terminé.
- **`FORBIDDEN_REFS_EXTRA`** : secret absent en local — le contrôle
  nominatif n'a pas tourné avec les vrais motifs en CI ; il a été
  testé avec un motif fictif (8 tests verts).
- **Vérification 9.1 d'indépendance des passages (c)** : code externe
  non consultable — tous les passages concernés sont listés
  « non vérifiables » ci-dessous, conformément au protocole.

## Décisions à prendre par le propriétaire

1. **Rupture d'API (Lot 7)** : `EditorPopupManager.FEATURE_EXECUTOR`
   supprimé (rupture signalée au CHANGELOG avec migration). Valider
   ou demander un sursis avec dépréciation.
2. **Passages 9.1 « non vérifiables »** (mention purgée, origine à
   arbitrer) :
   `Rope.java:45` (visite feuille par feuille) ;
   `BracketPairs.java:63` (équilibre) ;
   `EditorSession.java:1027` (montée d'ancêtres de sélection) ;
   `ColorAttribute.java:9` + `EditorColorScheme.java:19` (schéma
   compact) ; `EditorBracketMatcher.java:44` (appariement) ;
   `EditorCommands.java:4` (keymap par données) ;
   `OpenTabDiagnosticsSweep.java:17` (balayage) ;
   `EditorPainterHost.java:13` (hôtes de painters) ;
   `EditorHighlightPainter.java:361-456` (guides) ;
   `StreamConnectionProvider.java:16` (streams de connexion) ;
   `PostfixCompletion.java:8` + `SurroundWith.java:8` (postfix) ;
   `CommentToggler.java:71,146` + `CommentSyntax.java:25` (commentaires) ;
   `DefaultLanguageClient.java:23-86` (configuration des serveurs).
3. **Mentions ambiguës hors motifs 9.0** : fondation Eclipse textuelle
   (voir « Points non traités »), `kotlin-lsp` en donnée de test,
   « inspirée du thème clair officiel Primer » (`EditorTheme.java:301`),
   dépendances d'alors listées dans NOTICE historique.
4. **Historique réécrit — publication (à exécuter par le propriétaire)** :
   ```
   git push --force --all  origin
   git push --force --tags origin
   ```
   Puis suppression des refs distantes devenues obsolètes éventuelles
   (aucune n'a été identifiée : mêmes branches, mêmes tags).
5. **Ce que Git ne peut pas faire** (actions hors dépôt) :
   - notes de release GitHub, issues, PR, discussions, wiki,
     description/topics du dépôt : édition manuelle ;
   - GitHub conserve les anciens commits accessibles par hash et dans
     les forks/clones existants (demande de purge au support GitHub) ;
     aucune garantie sur les copies faites par des tiers ;
   - hébergeur de paquets (build à la demande) : invalider les builds
     en cache des anciens tags (les sources des anciens tags contiennent
     l'ancien contenu) ; les artefacts déjà publiés ne se rappellent
     pas ;
   - projets consommateurs qui épinglent un ancien tag ou hash : à
     mettre à jour après publication ;
   - le secret `FORBIDDEN_REFS_EXTRA` reste à créer en CI (contient
     les motifs nominatifs, jamais committés).
6. **Sauvegardes à conserver jusqu'à validation** :
   `code-editor-AVANT-PURGE-FINAL.bundle`, `code-editor-AVANT-PURGE.bundle`
   et le dossier `code-editor-AVANT-PURGE/` (hors du dépôt purgé) — à
   détruire seulement après validation définitive du propriétaire.

---

## Suivi post-publication (après fusion par le propriétaire)

Publication effectuée par le propriétaire (poussée forcée des branches et
tags, puis fusion de la branche par pull request). Trois corrections
suivies, toutes vérifiées rouge→vert sur JDK 17 (celui de la CI) et
suite complète verte (1 371 tests, lint, garde-fous) :

1. **Auteurs des commits** — les 40 commits de l'agent portaient une
   identité de conteneur : réécriture de l'auteur et du valideur vers
   l'identité du propriétaire (dates et contenus inchangés, tags
   intacts, `git diff` vide avant/après).
2. **Échec CI intermittent — pompe stderr** — deux tests d'absence de
   threads résiduels échouaient sur la CI (JDK 17) selon l'ordonnancement :
   un petit-fils du serveur détient l'extrémité d'écriture du pipe
   stderr ; tuer le seul parent ne clôt pas le pipe et la pompe (lecture
   non interruptible) survivait — le thread fui faisait échouer les
   tests suivants. `stop()` liquide désormais les descendants AVANT le
   parent puis attend, borné, la mort de la pompe. Reproduction
   déterministe incluse (`stop_petitFilsDetenantStderr…`).
3. **Garde-fou inopérant au commit** — double défaut découvert en
   corrigeant 2 : le hook `commit-msg` était committé non exécutable
   (git l'ignore : jamais actif sur un clone frais), et appelé par
   chemin absolu, le garde-fou scannait son propre fichier d'exceptions
   (dont les motifs contiennent les formules) — tout commit aurait été
   refusé. Mode 755 versionné + exclusion par nom de base + régression
   (test 6 du harnais).

**Poussée des corrections** : effectuée avec l'accord exprès du
propriétaire (branches `main` et `agent/durcissement-3.49.2`, force).
La ref `refs/pull/1/head` conserve les anciens hashs (artefact GitHub
immuable) — couvert par la demande de purge au support déjà listée.
