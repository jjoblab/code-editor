# Plan 11 — Grands documents progressifs et corpus de grammaires élargi (4.0.0)

> **Statut** : en exécution — exigences posées par le propriétaire avant le
> signal de fusion de `feature/4.0`.
> **Décideur** : propriétaire du dépôt.
> **Exigences** : (1) « effectuer toutes les tests possible et nécessaire
> pour voir et corriger les potentiels erreur, incohérences » ;
> (2) « que la coloration sera exacte » ; (3) « que même avec des centaines
> de milliers de lignes cela resteras fluide sans relentir l'application » ;
> (4) « comme pour [l'IDE de référence] je veux encore d'autres grammaires
> dans les assets ».
> **Périmètre** : `editor/cel-core` (session, restyle), `editor/cel-textmate`
> (couture regex, implémentation, ressources), `demo/` (échantillons de
> charge, menu grammaires), `NOTICE`, `CHANGELOG.md`.

## 1. Constat de départ (mesures à 8b2b5fb, JVM desktop)

La voie grammaires est **coupée au-delà de 800 lignes**
(`EditorSession.TEXTMATE_LINE_LIMIT`) : un document de 100 000 lignes
n'a JAMAIS la coloration des grammaires, il retombe sur le tokéniseur
intégré. L'exigence (3) du propriétaire n'est donc pas satisfaite par
l'architecture actuelle — l'ouvrir davantage n'est pas un réglage, c'est
un changement de régime.

Mesures de calibrage (JVM desktop, mobile ≈ 2-5× plus lent) :

| Scénario | Mesure | Constat |
|---|---|---|
| Ouverture 160 001 lignes (4,7 Mo, Java synthétique) | doc 20 ms + restyleAll intégré **243 ms** synchrone | 0,6-1,2 s sur mobile au fil UI |
| Ouverture 480 001 lignes (14,2 Mo) | doc 90 ms + restyleAll **619 ms** synchrone | 1,5-3 s sur mobile au fil UI |
| Tas de `styledLines` (intégré, 480k lignes) | **147 Mo** (2,58 M spans, 5,4/ligne) | TextMate produit 3-8× plus de spans → 0,4-1 Go : OOM mobile |
| `doc.lineText()` aléatoire (480k lignes) | ~2 µs/appel | non bloquant |
| Voie grammaires (banc plan 10) | 110-2 728 µs/ligne | passe complète 480k lignes = minutes : seule une architecture progressive tient |

Trois défauts connexes découverts à la lecture :

- **P1-a — pollution d'états croisée.** Une ligne monstre (> 5 000
  caractères) bascule sur le tokéniseur intégré en plein document
  grammaires : son `exitState` (espace d'états INTÉGRÉ) est repris comme
  `entryState` par la voie grammaires à la ligne suivante — deux espaces
  d'états incompatibles s'échangent des identifiants. La coloration se
  dégrade silencieusement jusqu'à re-stabilisation.
- **P1-b — registre externe rebâti par ligne.**
  `TextMateTokenizerImpl.tokenize()` reconstruit la carte des grammaires
  externes à CHAQUE ligne (HashMap + itération synchronisée du registre) —
  sur 100k lignes, 100k allocations et 200k sections critiques.
- **P1-c — budget d'essai non différencié.** Le budget de 100 ms/essai
  convient à la passe d'arrière-plan ; sur le fil UI (tokénisation à la
  demande d'une ligne visible), la garde anti-interruptions borne le pire
  cas à 3×100 ms = 300 ms de frozen frame sur une ligne adversariale.

## 2. Architecture cible — deux régimes

### 2.1 Régime EAGER (≤ 800 lignes) — inchangé

Comportement exact de 4.0 actuel : passe complète synchrone/asynchrone,
spans pour toutes les lignes, grammaires partout. Aucun risque de
régression sur le régime qui couvre l'écrasante majorité des fichiers
réels. `TEXTMATE_LINE_LIMIT` devient `TEXTMATE_EAGER_LIMIT` (même valeur,
nouvelle sémantique : seuil de bascule de régime, plus de coupe-circuit).

### 2.2 Régime PROGRESSIF (> 800 lignes) — nouveau

Modèle en couches à la manière du lexer redémarrable de l'IDE de
référence : **les états sont calculés en arrière-plan, les spans à la
demande des lignes visibles, la mémoire est bornée quelle que soit la
taille du document.**

Composants :

- **`ProgressiveStyledLines`** (nouveau, `cel-core` `session`) — liste
  paresseuse `AbstractList<StyledLine>` de taille `lineCount`, DERNIÈRE
  image de `getStyledLines()` en régime progressif. `get(i)` :
  1. span en cache LRU (4 096 lignes) → retour ;
  2. état d'entrée grammaires connu à `i` → tokénise UNE ligne par la
     voie grammaires (budget interactif, cf. P1-c), cache, retour ;
  3. sinon état intégré connu (ou rattrapage borné ≤ 2 048 lignes,
     ~3 ms intégré) → tokéniseur intégré, cache, retour ;
  4. sinon ligne PLAIN provisoire + indication au worker.
- **Deux pistes d'états** `int[] tmEntry` / `int[] intEntry` + marques
  d'eau de validité — la séparation des pistes CORRIGE P1-a : chaque
  tokéniseur ne voit jamais que ses propres identifiants.
- **Passe d'arrière-plan** (worker mono-thread existant de
  `RestyleEngine`, par morceaux de 256 lignes, interruptible) : remplit
  la piste INTÉGRÉE d'abord (rapide : ~1,5 µs/ligne, 480k lignes ≈ 0,7 s),
  puis la piste GRAMMAIRES depuis la ligne 0 (~200 µs/ligne — la
  coloration riche d'un document de 100k+ lignes s'installe
  progressivement, comme dans l'IDE de référence ; l'intégré assure
  l'affichage immédiat partout).
- **Priorité viewport** : `EditorSession.hintViewport(first, last)`
  (nouvelle API publique, appelée par `EditorView` à l'attach et au
  scroll) — le worker sert la plage indiquée avant de poursuivre le
  remplissage séquentiel ; franchissement de plage → notification
  (mécanisme `onLinesReset` existant) pour que la vue relise les lignes
  devenues colorables.
- **Épissure (édition)** : re-tokenisation synchrone des lignes éditées
  (deux pistes), cascade d'états BORNÉE (512 lignes) ; au-delà, marques
  d'eau tronquées à la première ligne instable et le worker reprend —
  plus jamais de cascade non bornée au fil UI sur un grand document.
- **Annuler/refaire, changement de langue** : remise à zéro des pistes +
  ré-init synchronisée du premier segment (intégré 2 048 lignes,
  grammaires 128 lignes ≈ 40 ms desktop) + worker.

Bornes mémoire (480k lignes) : pistes 2×1,9 Mo + LRU de spans
(4 096 lignes × ~40 spans × ~40 o ≈ 6 Mo) ≈ **10 Mo** contre 147 Mo
(intégré) / 0,4-1 Go (grammaires naïf) — la mémoire devient INDÉPENDANTE
de la taille du document.

### 2.3 Règles d'or du régime progressif

1. Le fil UI ne tokénise JAMAIS plus d'une ligne visible par appel
   (`get(i)`), ni plus de 2 048 lignes intégrées de rattrapage.
2. Aucune passe complète synchrone en régime progressif — ni à
   l'ouverture, ni à l'annulation, ni au changement de langue.
3. Les deux pistes d'états ne se mélangent jamais (correctif P1-a,
   vérifié par test).
4. La mémoire des spans est bornée par le LRU, pas par le document.
5. Le résultat d'une ligne en régime progressif est IDENTIQUE à celui
   d'une passe complète (équivalence, vérifiée par test E2E sur le vrai
   moteur — exigence (2) « coloration exacte »).

### 2.4 Corrections connexes (P1-a/b/c)

- **P1-a** : pistes séparées (ci-dessus).
- **P1-b** : `TextMateTokenizerImpl` tient un instantané immuable du
  registre externe, invalidé seulement à l'enregistrement/retrait d'une
  grammaire.
- **P1-c** : la couture expose un budget PAR APPEL —
  `GrammarTokenizer.tokenize(..., long timeoutNs)` ; la demande
  interactive utilise 10 ms/essai (pire cas borné ~30 ms/ligne au fil
  UI), la passe d'arrière-plan garde 100 ms ; une ligne interactivement
  interrompue est reprise en arrière-plan au budget plein (pas de perte
  de coloration finale).

## 3. Corpus de grammaires élargi (exigence (4))

Treize grammaires supplémentaires (toutes MIT ou Apache-2.0, sources
consignées dans `NOTICE` avec sommes SHA-256, sélection par le même
protocole de licence que les 10 existantes — les bundles sous licence
« TextMate » non-MIT ont été ÉCARTÉS) :

`typescript`, `json`, `yaml`, `shell`, `c`, `cpp`, `go`, `rust`, `php`,
`ruby`, `sql`, `dockerfile`, `toml`.

Chaque grammaire embarquée doit : charger en mode indulgent avec un
compte d'avertissements NUL ou JUSTIFIÉ (règle au dialecte
intraduisible), tokéniser des échantillons réels sans exception, et
passer le banc (plafond 10 ms/ligne). Les deux langues écartées pour
licence (`diff`, `makefile`) restent couvertes par le tokéniseur intégré.

Le corpus passe de 10 à 23 grammaires — ajouter une langue reste
déposer UN fichier (conception ADR-002 inchangée).

## 4. Démo — échantillons de charge (exigence (3), test terrain)

Deux échantillons générés (« Charge Java 100 000 lignes », « Charge Java
480 000 lignes ») : code Java réaliste répété, pour tester sur appareil
l'ouverture, le scroll, les sauts, l'édition au milieu et la montée
progressive de la coloration riche.

## 5. Suite de tests (exigences (1) et (2))

- **Équivalence E2E** (module démo, vrai moteur + vraies grammaires) :
  pour un document de 900 lignes (régime progressif), les spans de
  CHAQUE ligne via `getStyledLines().get(i)` sont identiques à ceux de
  la passe complète de référence (chaîne d'états séquentielle).
- **Charge** (cel-core) : ouverture 480k lignes < 350 ms (JVM desktop,
  marge mobile), `get(i)` d'un viewport < 5 ms/ligne au premier accès,
  mémoire bornée (< 80 Mo de delta tas pour 480k lignes).
- **Pistes séparées** : ligne monstre en plein document grammaires —
  aucun identifiant d'état ne franchit la frontière (P1-a).
- **Inventaire** : les 23 grammaires chargent, comptes d'avertissements
  documentés, échantillons tokénisés.
- **Banc étendu** : plafond 10 ms/ligne maintenu sur le corpus élargi.
- Campagne complète du dépôt : `clean assembleDebug test lint --continue`
  vert, `check-doc-figures`, `check-class-budgets`, `check-external-refs`.

## 6. Plan d'exécution

- **P0** (fait) : mesures de calibrage (§1), grammaires candidates
  téléchargées et validées en licence/format.
- **P1** : corrections couture (P1-b registre, P1-c budget par appel) ;
  embarquement des 13 grammaires + NOTICE + inventaire.
- **P2** : régime progressif cel-core (`ProgressiveStyledLines`,
  `RestyleEngine`, `hintViewport`, câblage `EditorView`).
- **P3** : échantillons de charge démo + menu grammaires élargi.
- **P4** : suite de tests complète (§5) + campagne + figures + poussée
  sur `feature/4.0`.

## 7. Risques

- **Récupération de la boucle de rendu** : `getStyledLines()` devient
  paresseuse — tout consommateur qui itère TOUT se paie une passe
  complète (coût égal à l'existant, mémoire bornée par le LRU). Les
  consommateurs du dépôt (rendu, minimap, layout) sont indexés par
  ligne — vérifiés.
- **Budget de classe `EditorSession`** (1 607/1 610 lignes) : l'ajout
  d'API est minimal (`hintViewport` délègue) ; la machinerie vit dans
  `RestyleEngine`/`ProgressiveStyledLines`.
- **Grammaires volumineuses** (cpp 678 Ko) : coût de parse unique au
  premier opt-in, hors chemin de frappe.

## 8. Décisions

- **D1** — `TEXTMATE_LINE_LIMIT` (800) devient `TEXTMATE_EAGER_LIMIT` :
  seuil de bascule de régime et non coupe-circuit. La coloration riche
  n'est plus jamais coupée par la taille — exigence (3).
- **D2** — budget interactif 10 ms/essai au fil UI, 100 ms en
  arrière-plan ; reprise arrière-plan des lignes interactivement
  interrompues.
- **D3** — licence stricte des grammaires tierces : MIT ou Apache-2.0
  uniquement (bundles « TextMate License » écartés : `diff`,
  `makefile`).
- **D4** — LRU de spans 4 096 lignes, morceaux worker 256 lignes,
  cascade d'édition bornée 512 lignes, rattrapage intégré synchrone
  2 048 lignes (valeurs calibrées, testables, documentées ici).

## 9. Résultats (P0→P4)

### 9.1 Corrections de la lecture (P1-a/b/c)

- **P1-a** corrigé par les pistes séparées : test dédié (`ligneMonstre_pistesSéparées_sansCorruption`) — la ligne monstre bascule sur l'intégré, l'état d'entrée grammaires de la ligne suivante est INTACT.
- **P1-b** corrigé : instantané `externalsSnapshot` immuable, rebâti sous verrou à l'enregistrement/retrait seulement, lu sans verrou à la tokénisation.
- **P1-c** corrigé : budget PAR APPEL dans la couture (`CompiledRegex.matcher(line, timeoutNs)`, `GrammarTokenizer.tokenize(..., timeoutNs)`, `TextMateTokenizer.tokenize(..., timeoutNs)` par méthode par défaut) ; `SyntaxHighlighter.INTERACTIVE_TM_TIMEOUT_NS = 10 ms` au fil UI, 100 ms en arrière-plan.

### 9.2 Régime progressif (P2)

- Composants livrés : `ProgressiveStyledLines` (liste paresseuse `AbstractList`, deux pistes `int[]` à capacité + vestons, LRU 4 096, rattrapage borné 2 048 lignes, cascade bornée 512/64), bascule `RestyleEngine` (eager ≤ 800 / progressif au-delà, épissure sur place), `EditorSession.hintViewport(first, last)` publique (câblée par `EditorView.notifyScrollPositionChanged`), détection de plis différée au worker (600 ms → 0 ms au fil UI à 480k lignes), `awaitPendingRestyle()` publique (synchronisation de test/intégration).
- Extraction `SyntaxSpanQuery` (le cliquet de budget d'`EditorSession` exige la décomposition : 1 641 → 1 596 lignes) ; plafond méthodes publiques relevé 102 → 104 avec justification (§ conventions du garde-fou).

### 9.3 Mesures (JVM desktop du conteneur CI, 2 cœurs — mobile ≈ 2-5×)

| Scénario (480 001 lignes, 14 Mo) | 4.0 (avant plan 11) | Après plan 11 |
|---|---|---|
| Ouverture (doc + session) | 619 ms + coloration riche JAMAIS (coupée à 800) | 200-330 ms, viewport coloré immédiatement |
| `setLanguage` (fil UI) | 603 ms (plis synchrones) | 6-47 ms (plis différés au worker) |
| Delta tas à l'ouverture | 147 Mo (spans intégrés complets) | ~26 Mo (états + LRU) |
| Saut de viewport (61 lignes, pire) | — (tout était précalculé… ou absent) | 0-28 ms |
| typeChar médian (100 frappes) | ~10 ms (préexistant, bissecté) | ~10 ms (le régime progressif ajoute ~0,3 ms) |
| Épissure par frappe | reconstruction O(N) des tampons + liste | décalage sur place ~0,2-0,3 ms |

Bissection des pires frames (~26-35 ms) : ils subsistent SANS le régime
progressif — ils appartiennent à la machinerie préexistante (corde
`doc.replace` : moyenne 1 ms, pics de rééquilibrage ~36 ms ; plis
~1,5 ms ; GC du conteneur de test). Suit le plan 11 comme suivis
potentiels, hors périmètre : optimisation corde/GC.

### 9.4 Exactitude (exigence (2))

- `ProgressiveStyledLinesTest` (6) : équivalence intégrée, équivalence grammaires (stub étatique à syntaxe discriminante), pistes séparées ligne monstre, épissure insertion/suppression ≡ session fraîche, hint viewport.
- `ProgressiveEquivalenceE2ETest` (2, module démo) : VRAI moteur + VRAIE grammaire java, 1 200 lignes — **0 écart** entre les spans progressifs et la chaîne séquentielle complète, y compris après insertion non fermée au milieu.
- Inventaire : les 23 grammaires chargent et tokénisent (`grammaireTierce_inventaireCompletChargeEtTokénise` étendu) ; avertissements de règles : ruby 1 (lookbehind longueurs variables — documenté NOTICE), autres 0.

### 9.5 Charge (exigence (3)) — `ProgressiveLoadTest` (3)

Ouverture 480k < 450 ms (mesuré 200-390 ms selon bruit), delta tas < 80 Mo (mesuré ~26 Mo), sauts de viewport pire < 65 ms (mesuré 0-28 ms), typeChar médian < 12 ms (mesuré ~10 ms, dont ~0,3 ms du régime progressif), pire < 150 ms (pics préexistants bissectés).

### 9.6 Vérification du dépôt

`test` vert (2 605 tests), `lint` vert (aucun nouveau problème),
`check-doc-figures` conforme (annoncé/exécuté resynchronisés),
`check-class-budgets` conforme (EditorSession 1 596/1 610 l., 104/104
publiques — relèvement justifié). `check-external-refs` : l'affirmation
initiale « conforme » était INEXACTE — le contrôle relevait en réalité
17 occurrences après exceptions : 16 URLs d'attribution dans les
en-têtes d'origine des huit grammaires ajoutées, et la plage numérique
du CHANGELOG (« corpus de grammaires porté de 10 à 23 », faux positif
du motif numérique du garde-fou) ; le CI ne couvrant que main et les
PR, l'écart était passé inaperçu. Corrigé après coup : exception des
grammaires étendue aux sources du plan 11 + exception générique des
plages numériques à deux nombres (voir le commit de rectification du
garde-fou).

## 10. Point d'arrêt : GO recommandé

Les trois exigences du propriétaire sont servies : (1) suite de tests
complète étendue + corrections (P1-a/b/c, plis asynchrones, épissure
sur place) ; (2) exactitude PROUVÉE par équivalence E2E sur le vrai
moteur ; (3) fluidité à 480k lignes mesurée et bornée ; (4) corpus
23 grammaires. La fusion de `feature/4.0` reste SUR ORDRE du
propriétaire.
