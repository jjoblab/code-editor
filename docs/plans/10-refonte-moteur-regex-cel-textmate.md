# Plan 10 — Refonte du moteur de regex de `cel-textmate` (4.0.0)

> **Statut** : accepté — arbitrée par le propriétaire le 2026-10-20.
> **Décideur** : propriétaire du dépôt.
> **Décision** : « pas de nouveau module ; une refonte complète de
> `cel-textmate` est acceptée ; solution la plus propre, professionnelle,
> moderne et définitive. »
> **Périmètre** : `editor/cel-textmate` (`internal/RegexCompat`,
> `TextMateGrammar`, `GrammarTokenizer`, `TextMateTokenizerImpl`), build,
> `NOTICE`, `demo/`. La version qui porte la refonte est **4.0.0**.

## 1. Les trois constats d'audit (mesures à 3.73.0)

La voie grammaires exécute les motifs des grammaires TextMate après
traduction du **dialecte Oniguruma** (le dialecte regex historique des
grammaires TextMate) vers `java.util.regex` — la couche
`internal/RegexCompat`. Trois constats d'audit en résultent :

- **C8 — règles muettes.** Sur les ~2 070 traductions que maintient
  `RegexCompat` pour le corpus embarqué, **8 règles sont intraduisibles**
  et sont ignorées au chargement en mode indulgent (règle muette) :
  7 règles de la grammaire markdown tierce (récursion `\g<square>` :
  gras, italique, 2 images, 3 liens — mesuré dans le fichier embarqué :
  8 occurrences de `\g<` sur 7 règles) et 1 règle de la grammaire python
  tierce (lookbehind avec référence arrière `(?<=\1)`, fin de
  `docstring-statement`). Le gras, l'italique, les liens et les images à
  crochets imbriqués ne sont simplement pas colorés.
- **C9 — classes ASCII.** `Pattern.compile` sans drapeau donne des `\w`,
  `\b`, `\d`, `\s` **ASCII par défaut** : `var année = 3;` ne colore que
  `ann` ; `def énorme(x)` reste `PLAIN` ; `\bété\b` ne correspond nulle
  part. La traduction `[:alpha:]` → `\p{Alpha}` (ASCII) et
  `[:word:]` → `a-zA-Z0-9_` (ASCII) aggravent le constat : le corpus
  embarqué utilise `[:alpha:]` **340 fois** (dont 315 dans la seule
  grammaire javascript) et `\w` **247 fois**. Le drapeau
  `UNICODE_CHARACTER_CLASS` corrigerait `java.util.regex` au prix mesuré
  de +10 à +25 %.
- **C2 — aucun budget.** La tokenisation n'a **aucun budget de
  backtracking** : un motif catastrophique (forme `(a+)+$`) dans une
  grammaire enregistrée par l'hôte peut bloquer le thread de restyle.
  L'atténuation existante (800 lignes / 5 000 caractères) ne protège pas
  d'un motif exponentiel sur ligne courte.

Mesures complémentaires du corpus embarqué (9 grammaires tierces + celle
du dépôt) : `\s` 2 226 ; `\b` 696 ; lookbehinds ~890 ; `\G` 307 ;
`\d` 69 ; `[:digit:]` 20 ; `\k<nom>` 13 ; possessifs `++` 2 ;
`\A` 4 ; `\W` 2 ; `[:word:]` 1 ; `\h` 0 (jamais dans le corpus —
l'écart de sémantique `\h` du dialecte restait pourtant à traduire).

## 2. La racine unique

C2, C8 et C9 sont les symptômes d'une même racine : **l'émulation du
dialecte par traduction**, au lieu de son exécution. Tant que la
bibliothèque émule, chaque correctif est un patch local — et il en
faudra indéfiniment (nouvelles grammaires, nouvelles constructions,
nouveaux caractères). La refonte supprime la racine :

1. **Moteur unique.** `cel-textmate` dépend d'un moteur d'expressions
   rationnelles du dialecte Oniguruma, en **dépendance Maven directe
   unique** (coordonnées, versions et licences dans le build et
   `NOTICE` — obligation de licence). Pas de fork, pas de code copié,
   pas de second moteur, pas de module séparé.
2. **Traducteur supprimé.** `RegexCompat` et sa table disparaissent ;
   les motifs sont exécutés nativement, sans transformation de dialecte.
3. **Couture interne confinée.** Un unique package
   `jo.codeeditor.textmate.regex` est le seul code du dépôt qui importe
   les classes du moteur. Il n'expose que des types `cel-textmate` ;
   le confinement est vérifié par un test dédié. **Ce n'est pas un point
   d'extension** : interface interne, moteur non injectable, aucun type
   de moteur dans l'API publique.
4. **Sémantique = dialecte, pas Java.** La référence de comportement
   est celle du dialecte (le moteur de référence C, binaire de
   développement hors distribution). Les écarts connus du port Java du
   dialecte sont corrigés par une **table de normalisation**
   (`DialectNormalizer`) pilotée par une **suite de conformité
   permanente** ; premières entrées : `\w` → `\p{Word}`,
   `\W` → `\p{^Word}` (vérifiées hors et dans classe).
5. **Dégradation contrôlée.** Échec de compilation → règle muette +
   `EditorLog` ; budget écoulé en correspondance → non-correspondance +
   `EditorLog`. Une grammaire défaillante ne fait jamais planter le
   rendu.
6. **Budget sur toute correspondance.** Chaque exécution de motif porte
   son budget (valeur D4) ; la garde anti-boucle du tokenizer et les
   limites de ligne existantes sont conservées.
7. **Règle python `docstring-statement`.** Variante locale de cette
   seule règle (lookbehind à alternatives de longueurs fixes,
   sémantiquement équivalent), consignée dans `NOTICE` avec l'écart
   correspondant du port Java du dialecte.
8. **Version 4.0.0.** Rupture assumée, unique, sans période de double
   moteur livré. L'API publique n'expose plus aucun type de moteur.

## 3. Architecture cible

### 3.1 Avant / après (3.73.0 → 4.0.0)

```
3.73.0 :
TextMateGrammar ──> RegexCompat (traduction dialecte -> java.util.regex, ~2 070 traductions)
                          │  8 règles muettes (C8) ; classes ASCII (C9) ; aucun budget (C2)
                          ▼
                    GrammarTokenizer ──> TextMateTokenizerImpl

4.0.0 :
TextMateGrammar ──> jo.codeeditor.textmate.regex   (couture unique — seul importateur du moteur)
                        ├─ DialectNormalizer : écarts du port Java (\w -> \p{Word}, \W -> \p{^Word})
                        ├─ EncodedLine       : ligne + « \n » virtuel, UTF-8, index char <-> octet
                        ├─ CompiledRegex     : motif compilé (une variante par source normalisée)
                        ├─ LineMatcher       : correspondance ancrée par position, budget par essai
                        ├─ MatchBudget       : constantes D4 + journal des interruptions
                        └─ RegexCache        : LRU des motifs compilés (clé = source normalisée)
                          ▼
GrammarTokenizer (garde anti-boucle + annulation coopérative conservées) ──> TextMateTokenizerImpl
```

### 3.2 Composants de la couture

| Composant | Responsabilité | Points de conception |
|---|---|---|
| `DialectNormalizer` | Réécrire les formes du port Java qui divergent de la référence du dialecte | Scanner gauche→droite conscient des échappements et de la profondeur de classes ; table initiale : `\w` → `\p{Word}`, `\W` → `\p{^Word}` ; extensible **uniquement** sur divergence prouvée par la suite de conformité |
| `EncodedLine` | Encapsuler la ligne de travail (« \n » virtuel compris) en UTF-8 avec correspondance char↔octet | Encodage unique par passe ; index de préfixes : `byteOffset(charIndex)` en O(1), `charIndex(byteOffset)` en O(log n) ; réponse à l'impôt `byte[]` du modèle moteur |
| `CompiledRegex` | Motif compilé immuable + compilation avec repli contrôlé | Source normalisée ; échec de compilation → exception du module (règle muette en amont) + `EditorLog` une fois par grammaire |
| `LineMatcher` | Correspondance ANCRÉE à une position char, budget par essai | `matchAt(pos)` ; résultats en offsets char (type `cel-textmate` exclusivement) ; `INTERRUPTED` → non-correspondance + `EditorLog` throttlé ; exception en correspondance → non-correspondance + journal |
| `MatchBudget` | Détenir les constantes D4 et le throttling du journal | Timeout par essai (proposition 100 ms — D4, calé au banc en P3) |
| `RegexCache` | Cacher les motifs compilés | LRU maison (aucune dépendance supplémentaire), clé = texte du motif **après normalisation** (couvre les fermantes résolues avec backrefs du `begin`) |

`GrammarTokenizer` conserve sa sémantique (leftmost-first, largeur
nulle, pile inter-lignes internée, `\n` virtuel, idiomes `\G`) et ses
gardes existantes ; `TextMateTokenizerImpl` est inchangé ;
`TextMateGrammar` consomme la couture (plist et sélecteurs inchangés).

### 3.3 Règles d'or (vérifiées par le build)

1. **Confinement** : les classes du moteur n'apparaissent dans aucun
   fichier hors `jo.codeeditor.textmate.regex` — test dédié (scan des
   imports) qui rend le build rouge sinon.
2. **Aucun point d'extension public** : le moteur n'est pas injectable ;
   l'API publique n'expose aucun type de moteur.
3. **Sémantique du dialecte** : la référence C du dialecte fait foi ;
   toute divergence devient une entrée de normalisation + un cas de la
   suite de conformité.
4. **Dégradation contrôlée** : une grammaire défaillante ne fait jamais
   planter le rendu.
5. **Budget sur toute correspondance** : aucune exécution de motif sans
   budget.

### 3.4 Sort de chaque élément du périmètre

| Élément (3.73.0) | Sort en 4.0.0 |
|---|---|
| `internal/RegexCompat` (traducteur) | **Supprimé**, avec les tests de traduction |
| `TextMateGrammar` | Conservé ; consomme la couture ; formats plist/JSON inchangés |
| `GrammarTokenizer` | Conservé ; sémantique et gardes inchangées ; types moteur remplacés par les types de la couture |
| `TextMateTokenizerImpl` | Conservé ; journalisation migrée vers `EditorLog` |
| `demo/` | Conservé ; + échantillon markdown à crochets imbriqués |
| build / `NOTICE` | + dépendance moteur (licences MIT), épinglage des sommes, variante python |

## 4. Base de preuve (vérifications empiriques)

Protocole : motifs **verbatim** du corpus embarqué et formes
synthétiques du dialecte, exécutés sur `java.util.regex` (JDK 21), le
moteur embarqué retenu (options `CAPTURE_GROUP` + encodage UTF-8 ;
version dans le build) et le **moteur de référence C du dialecte**
(binaire de développement compilé pour l'étude, hors distribution).
Encodage UTF-8 partout.

| Constructeur (règle réelle) | `java.util.regex` | Moteur embarqué | Référence C du dialecte |
|---|---|---|---|
| `\g<square>` — begin gras markdown (1 126 car., possessifs, `\k<nom>`, lookbehinds) | Échec de compilation (au `\g`) | **Compile et correspond** — `**gras**`, `**café**`, `**[lien](http://x)**` | Idem ✓ |
| `(?<=\1)` — fin `docstring-statement` python | Échec | **Échec** (« invalid pattern in look-behind ») — écart du port Java | Compile et correspond ✓ |
| `(?<=ab*)` — lookbehind à longueur variable (synthétique) | Accepté (JDK 9+) | Refusé | Accepté |
| `\w+` sur « année » | « ann » (ASCII) | « ann » (ASCII) — **écart du port** | « année » entier |
| `\bété\b` sur « l'été » | Aucune correspondance | **Correspond** ✓ | Correspond ✓ |
| `\p{Word}+` / `[[:word:]]+` sur « année » | — | **« année » entier** ✓ | « année » entier |
| `\W` sur « été » | — | `é` (complément **ASCII**) — écart du port ; **contourné** : `\p{^Word}` et `[[:^word:]]` donnent le complément Unicode exact (vérifié hors classe, dans classe, en union) | Aucune correspondance (tout est mot) |
| Possessifs `a++b`, backrefs nommées `\k<nom>`, groupes nommés | OK | OK | OK |
| `(a+)+$` catastrophique avec budget | Aucun mécanisme (blocage) | **`INTERRUPTED` en 200 ms** → repli non-correspondance | Aucun mécanisme |

Lectures :

- Le moteur embarqué résout **C8-markdown** (7 règles, vérifié sur le
  pattern réel) et apporte le garde-fou natif de **C2** (interruption
  par essai) ; `\b` natif est déjà conforme (**C9**).
- `\w`/`\W` divergent dans le port Java (complément ASCII) : la
  normalisation `\w` → `\p{Word}`, `\W` → `\p{^Word}` est **vérifiée
  dans tous les contextes** et restitue la sémantique de la référence.
- `\h` est nativement conforme dans le moteur embarqué (chiffre
  hexadécimal, comme la référence — là où `java.util.regex` en fait un
  espace horizontal) ; la traduction spécifique disparaît avec le
  traducteur. Le corpus embarqué ne l'utilise jamais (0 occurrence).
- La règle python `(?<=\1)` reste en échec (écart du port Java) :
  variante locale documentée (§2, point 7).
- Performance indicative (non contractuelle) : motif identifiant
  trivial, ~+25 % par correspondance sur `java.util.regex` sans drapeau
  — du même ordre que le surcoût du drapeau C9 du statu quo. Le banc du
  dépôt tranche (P3).

## 5. Mapping C2 / C8 / C9 (état livré 3.73 → état livré 4.0)

| Constat | Élément | 3.73.0 (livré) | 4.0.0 (décision) |
|---|---|---|---|
| C8 | 7 règles markdown `\g<square>` | muettes (rejetées à la traduction) | **Natives** (vérifié sur le pattern réel) |
| C8 | 1 règle python `(?<=\1)` | muette | **Variante locale + `NOTICE`** |
| C8 | `\h` (hexadécimal ≠ espace horizontal) | traduction spécifique dans `RegexCompat` | **Natif conforme** |
| C9 | `\b`, `\B` | ASCII | **Natifs Unicode** (vérifié `\bété\b`) |
| C9 | `\w`, `\W` | ASCII (`ann` sur « année ») | **Normalisation vérifiée** (`\p{Word}` / `\p{^Word}`) |
| C9 | `[:alpha:]`, `[:word:]` | traduits en classes ASCII (`\p{Alpha}`, `a-zA-Z0-9_`) | **Natifs Unicode** |
| C9 | `\d`, `\s` | ASCII | **Normalisés en P1** (divergences mesurées par la suite : chiffres arabo-indiens, NBSP) : `\d` → `\p{Nd}` ; `\s` → table d'espaces de la référence (tabulations, {\p Z}, NEL, ZWSP) |
| C2 | budget par correspondance | aucun | **Natif** (budget par essai ; interruption → non-correspondance + `EditorLog`) |
| C2 | garde anti-boucle | disjoncteur 50 étapes vides | Conservé ; valeur calée en P3 (D4) |
| C2 | limite de ligne | 800 lignes / 5 000 caractères | Conservée |
| C2 | échec de compilation | règle muette silencieuse (stderr) | Règle muette **+ `EditorLog`**, une fois par grammaire |

## 6. Inventaire P0-1 : API publique et points d'appel (3.73.0)

### 6.1 API publique du module (consommée par l'hôte)

`TextMateTokenizerImpl` (crochet `cel-core`) : `create()`,
`loadEmbeddedGrammar`, `registerGrammar`, `unregisterGrammar`,
`isAvailable`, `hasEmbeddedGrammar`, `tokenize`. **Aucun type de moteur
n'y paraît** — rien à rompre côté hôte.

### 6.2 Exposition de types moteur dans l'API interne du module

- `TextMateGrammar.Rule` (publique, champs finaux) : `match` et `begin`
  sont typés `java.util.regex.Pattern` — **seule exposition de type
  moteur de tout le module**. En 4.0 : typés `CompiledRegex` (type
  `cel-textmate`). Rupture interne couverte par le palier 4.0.0
  (l'hôte programme contre `TextMateTokenizerImpl`).
- `GrammarTokenizer.Frame` (privée) : `endPattern`, `whilePattern` en
  `java.util.regex.Pattern` → `CompiledRegex`.
- `internal/RegexCompat` (publique du package `internal`) : supprimé.

### 6.3 Points d'appel de la couche de traduction (3.73.0)

| Site | Rôle | Sort en 4.0 |
|---|---|---|
| `TextMateGrammar.RuleParser.compile` | `RegexCompat.translate` avant `Pattern.compile` (match/begin) | Compile via la couture (normalisation + moteur) |
| `GrammarTokenizer.resolveEnd` | `RegexCompat.translate` avant `Pattern.compile` des fermantes/gardes `while` résolues (backrefs substituées) | Compile via la couture + `RegexCache` |
| `GrammarTokenizer.NEVER` | pattern ne correspondant jamais | `CompiledRegex` équivalent dans la couture |
| `GrammarTokenizer.Ctx` | `java.util.regex.Matcher` par `Pattern` et par ligne (région, bornes transparentes, `lookingAt`) | `LineMatcher` par `CompiledRegex` et par ligne (ancrage natif par position, lookbehind sur le contexte gauche) |

## 7. Suite de conformité permanente (P0-3, dev puis P1)

Modèle : suite de classes abstraites du plan exécutée contre la
référence. Les valeurs attendues sont **établies sur le moteur de
référence C du dialecte** (binaire de développement, reconstruit hors
distribution à chaque besoin — annexe A) puis embarquées comme
constantes du test ; la suite s'exécute en CI contre le moteur embarqué
et garde **pour toute la vie de la bibliothèque** l'équivalence
port ↔ référence sur :

- correspondances et groupes (numérotés, imbriqués, vides,
  non participants) ;
- ancres `^` `$` `\A` `\z` `\G` (y compris l'idiome `(?!\G)` des
  wrappers) ;
- Unicode : `\w` `\W` `\b` `\B` `\d` `\s` sur des entrées accentuées,
  cyrilliques et CJK (`année`, `été`, `функция`, `変数`) ;
- classes POSIX (`[[:alpha:]]`, `[[:word:]]`, `[[:digit:]]`) et
  compléments ;
- constructions du dialecte : possessifs, `\k<nom>`, `\g<nom>`,
  lookbehinds (longueur fixe, alternatives de longueurs fixes),
  `(?#…)`, accolades seules, mode étendu `(?x)` ;
- budget : forme `(a+)+$` sur entrée adversariale — l'essai doit
  s'interrompre et se replier en non-correspondance (jamais bloquer) ;
- bornes : positions char ↔ octets sur des lignes multi-octets
  (accents, CJK, emoji) — le résultat doit être exprimé en offsets
  **char** de la ligne.

Toute divergence constatée (montée de version du moteur, nouveau
constructeur) devient une entrée de `DialectNormalizer` + un cas de la
suite — règle d'or 3.

## 8. Plan d'exécution (P0 → P3)

Conforme aux règles du dépôt (`AGENT.md`, `CONTRIBUTING.md`) : un
commit par changement, commentaires et commits en français, test
défaillant avant / vert après, `./gradlew test lint` par lot, entrée
`CHANGELOG.md` par palier. La refonte se déroule sur une branche
`feature/4.0` fusionnée **uniquement au GO** (P3) — jamais dans un état
intermédiaire.

**P0 — Cartographie et filet** (critère de sortie : le comportement
3.73 est verrouillé, la conformité est spécifiée)

1. Ce document (décision, architecture, inventaire, mapping).
2. Corpus de l'audit + **golden tests de caractérisation du comportement
   3.73** — y compris ses défauts connus (règles muettes C8, classes
   ASCII C9) pour distinguer en P1 les corrections voulues des
   régressions accidentelles. Ressource de test dédiée
   (`unicode-audit.tmg.json`) : la même ressource caractérise 3.73 puis
   4.0 sur `\w`, `\b`, `[[:alpha:]]`, `\h` et entrées accentuées.
3. Valeurs attendues de la suite de conformité établies sur la
   référence C (annexe A) — la suite exécutable arrive avec la couture
   (P1) et devient permanente.

**P1 — Couture et bascule** (critère de sortie : conformité verte sur
le moteur embarqué ; golden tests conformes aux attendus 4.0)

4. Package `jo.codeeditor.textmate.regex` (composants §3.2) ; dépendance
   moteur dans le build ; branchement de tous les points d'appel
   (§6.3) ; `RegexCompat` encore présent mais mort (supprimé en P2).
5. `EditorLog` (C1) intégré à la couture et au chargeur ; publication
   des avertissements une fois par grammaire (C7).
6. `GrammarTokenizer` : garde anti-boucle constante nommée + budget
   par essai câblé sur toute correspondance. Tests d'acceptation :
   markdown gras/italique/liens/images à crochets imbriqués
   (défaillants sur 3.73), python docstring, `(a+)+$` borné,
   identifiants accentués.
7. Variante locale `docstring-statement` + entrée `NOTICE` (D2).
8. Golden tests mis à jour vers les attendus 4.0 (chaque mise à jour
   justifiée : correction voulue, référence au présent plan).

**P2 — Déletion et durcissement** (critère de sortie : plus aucune
référence au traducteur ; CI verte)

9. Suppression de `RegexCompat` et des tests de traduction ; test de
   confinement (règle d'or 1) ajouté au build ; baselines lint et
   budgets inchangés (le périmètre monitoré ne couvre pas
   `cel-textmate` — vérifié).
10. `NOTICE` complété : dépendance moteur (licences MIT), épinglage des
    sommes, variante python. `check-external-refs` : coordonnées
    d'artefacts uniquement (règle CI inchangée).

**P3 — Mesures et clôture** (critère de sortie : GO/NO-GO franchi)

11. Banc complet : corpus réel + cas adversariaux ; critère D4.
12. Valeurs D4 calées ; `CHANGELOG` 4.0.0 ; README/`BUILD.md` ; démo
    complétée (échantillon markdown à crochets imbriqués) ; version
    passée à 4.0.0.
13. Proposition de contribution amont (lookbehind avec backref) — hors
    dépôt.

**Points d'arrêt** : GO = banc conforme (D4). Sinon re-planification —
la 3.73 reste publiée telle quelle jusqu'au GO ; la 4.0 ne sort jamais
en demi-teinte.

## 9. Risques

| # | Risque | Gravité | Mitigation |
|---|---|---|---|
| 1 | Empreinte de la dépendance payée par tous les consommateurs du module | moyen | Arbitrage assumé par la décision (plus d'opt-in) ; licences consignées ; le module reste optionnel côté hôte |
| 2 | Performance du chemin chaud (~+25 % indicatif sur motif trivial ; coût char↔octet par ligne) | moyen | `EncodedLine` (index de préfixes) + `RegexCache` ; banc obligatoire en P3 (D4) |
| 3 | Portée de la refonte (cœur du tokenizer d'un module publié) | élevé | Golden tests **avant** toute modification (P0) ; un commit par changement ; phases à critères de sortie ; branche fusionnée uniquement au GO |
| 4 | Règle python `(?<=\1)` (écart du port Java) | faible | Variante locale + `NOTICE` ; contribution amont proposée |
| 5 | Divergences résiduelles du port (lookbehinds : longueurs variables et groupes alternatifs imbriqués refusés ; `\S` en classe sans équivalent membre) | moyen | **P1** : deux idiomes du corpus couverts par la table (aplatissement `(^|[^\:])\s`, distribution de l'échappement hexa borné) ; `\d`/`\s` normalisés ; le reste dégrade en règle muette + `EditorLog` (C7) — suite de conformité permanente (règle d'or 3) |
| 6 | Dépendance tierce (maintenance du moteur) | faible | Dépendance unique, licences MIT, épinglage des sommes ; veille de versions par la suite de conformité |
| 7 | Rupture de l'API interne (types moteur dans `Rule`) | moyen | Inventaire §6 ; palier 4.0.0 ; `CHANGELOG` « Changed » |

## 10. Décisions

| # | Décision | Statut |
|---|---|---|
| D1 | Moteur et architecture | **Tranché — propriétaire, 2026-10-20** : moteur unique en dépendance directe, traducteur supprimé, couture confinée, pas de point d'extension public |
| D2 | Règle python `docstring-statement` | **Éclairci en P1** : la grammaire embarquée n'a BESOIN d'aucune variante — la fermante est résolue au runtime avec substitution de la backref, et la forme substituée (lookbehind de longueur fixe) compile dans le port comme dans la référence (test de non-régression). La forme BRUTE `(?<=\1)` reste un écart du port (motifs hôtes sans backref substituable) — consigné pour `NOTICE` en P2 |
| D3 | Coordonnées Maven et `NOTICE` | **Reconduit** : dépendance déclarée dans le catalogue de versions du build, licences et épinglage des sommes dans `NOTICE` |
| D4 | Valeurs de garde-fous | **Propositions à caler au banc (P3)** : budget 100 ms par essai, garde anti-boucle existante, limites de ligne conservées. Calibrage sans nouveau plan |
| D5 | Ordre d'exécution | **Tranché par implication** : la refonte est le véhicule — C9 subsumé par le moteur, C1/C7 en P1 (étape 5) |

## Annexe A — Méthodologie de vérification (reproductibilité)

- **Référence du dialecte** : le moteur de référence C est reconstruit
  en développement depuis ses sources publiques (binaire hors
  distribution, jamais committé) ; le harnais compare motif par motif
  le port embarqué à cette référence (encodage UTF-8, syntaxe par
  défaut du dialecte) et produit la table des valeurs attendues de la
  suite de conformité. Les scripts de l'étude restent dans
  l'environnement de développement.
- **Corpus** : les 9 grammaires tierces embarquées (VERBATIM, voir
  `NOTICE`) + celle du dépôt ; les occurrences mesurées (§1) proviennent
  de ces fichiers.
- **Moteurs comparés pendant l'étude** : `java.util.regex` du JDK 21 ;
  le moteur embarqué retenu (options `CAPTURE_GROUP`, encodage UTF-8) ;
  la référence C.
- **Limite** : pas de SDK Android pendant l'étude (l'empreinte dex/AAR
  sera mesurée à la livraison) ; la sémantique est vérifiée sur JVM.


## 11. Résultats P1 (bascule effectuée)

- **Couture livrée** : `jo.codeeditor.textmate.regex` (six composants
  du §3.2). L'enveloppe d'ancre `\G(?:…)` rend chaque essai ancré ET
  budgété — la recherche est la seule voie du moteur qui arme
  réellement le chronomètre du budget par essai (vérifié : sain lourd
  6,8 ms ; pathologique interrompu à 150,0 ms pour un budget de
  150 ms ; réarmement par appel).
- **Correctif d'enveloppe** : un motif `(?x)` peut se terminer par une
  ligne de commentaire — la fermante de l'enveloppe est avalée ; une
  seconde tentative avec saut de ligne avant `)` la sauve (un motif
  réellement déséquilibré échoue aux deux).
- **Table de normalisation complétée** (mesures de la suite) :
  `\w`/`\W` (étude), `\d`/`\D` (chiffres arabo-indiens), `\s`
  hors et dans classe (table d'espaces de la référence : tabulations,
  séparateurs {\p Z}, NEL U+0085, ZWSP U+200B), aplatissement de
  `(^|[^\:])\s` et distribution de `\\(?:[hex]{1,6}|.)` en
  lookbehind (idiomes du corpus CSS : les règles selector et
  shared-names compilent et colorent désormais nativement).
- **C8 mesuré sur le corpus embarqué** : markdown 7/7 règles natives
  (zéro avertissement), python docstring non-régression (fermante
  substituée), CSS 0 règle muette (8 règles ressuscitées par la table
  + le correctif d'enveloppe).
- **C7 mesuré** : `grammar.warnings` est désormais peuplé (la liste
  était copiée AVANT le parse — vide à jamais) ; publication une fois
  par grammaire via `EditorLog`.
- **Conformité** : 28 cas verts contre la référence C (ancres, Unicode,
  POSIX, possessifs, `\k`, `\g`, lookbehinds, `(?#)`, `(?x)`,
  bornes char↔octet dont paires de substitution) + budget + cache.


## 12. Résultats P3 (mesures et clôture)

### 12.1 Banc (étape 11) — TextMateBenchTest

Corpus réel (moyenne sur 20 essais par ligne, une ligne représentative
par grammaire embarquée) :

| Grammaire | µs/ligne | | Grammaire | µs/ligne |
|---|---|---|---|---|
| xml | 110 | | kotlin (plist) | 1 340 |
| lua | 180 | | javascript | 1 302 |
| markdown | 271 | | python | 1 690 |
| csharp | 291 | | html | 2 654 |
| java | 486 | | css | 2 728 |

Pire grammaire : 2,7 ms/ligne — plafond D4 (10 ms/ligne) respecté
avec ×3,7 de marge. Les grammaires lourdes (css, html) sont celles au
plus grand nombre de règles essayées par position ; aucune dérive.

Cas adversarial (grammaire hôte portant `(a+)+$` en règle match,
essayée à chaque position d'une ligne de 41 caractères) : 3 lignes
successives en 909 ms — soit ~303 ms/ligne. Sans garde, la ligne
consommerait « positions × budget » (41 × 100 ms ≈ 4,1 s ; 12,3 s
pour les trois). **La garde anti-interruptions borne à ~3 × budget
par ligne** : au troisième essai interrompu d'un même motif, il est
sauté pour le reste de la ligne (il échouait de toute façon) et le
rendu dégrade proprement en PLAIN.

### 12.2 Valeurs D4 calées (étape 12 — sans nouveau plan)

| Garde-fou | Valeur | Source |
|---|---|---|
| Budget par essai | 100 ms | MatchBudget, mesuré (interruption exacte au budget) |
| Garde anti-interruptions | 3 par motif et par ligne, puis saut | GrammarTokenizer (nouvelle, §12.1) |
| Garde anti-boucle (largeur nulle) | 50 étapes, constante nommée MAX_ZERO_WIDTH_STEPS | existente, nommée en P3 |
| Limites de ligne | 800 lignes / 5 000 caractères | conservées (crochet cel-core) |
| Cache LRU | 512 motifs | RegexCache |

### 12.3 Clôture

- Version **4.0.0** (versionCode démo 15) ; `CHANGELOG` [4.0.0] ;
  `NOTICE` complété (dépendance moteur, licences MIT, sommes épinglées,
  écart du port brut consigné).
- Démo : échantillon markdown enrichi — section « crochets imbriqués »
  (gras, italique, image, lien imbriqué dans du gras) exerçant la
  récursion native.
- Contribution amont au port (lookbehind avec backref, groupes
  variables en lookbehind) : proposée hors dépôt — la forme du texte
  est prête, elle attend l'envoi au propriétaire.

### 12.4 Point d'arrêt : GO

Banc conforme (D4) ✓ — les critères de sortie P3 du §8 sont franchis.
**GO recommandé pour la fusion de `feature/4.0`** — la décision
d'appartient au propriétaire (fusion et publication sur son ordre,
comme convenu pour toute la session). L'empreinte Android (dex/AAR
après R8) reste à mesurer sur le poste de publication : l'étude n'a
pas de SDK Android de build d'AAR ; les jars font 2,0 Mo avant
shrinking ( licences consignées, module optionnel côté hôte).
