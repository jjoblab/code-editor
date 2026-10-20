# cel-textmate — coloration par grammaires (module optionnel)

> 96 tests · JUnit 5 · `api(:cel-core)` + le moteur d'expressions
> rationnelles du dialecte TextMate (dépendance unique du module,
> consommée par la couture interne — plan 10, refonte 4.0) ; parseurs
> JSON et plist internes.

`cel-textmate` est la **voie grammaires** de l'ADR-002 (chantier C3) :
un moteur de grammaires au format TextMate (`.tmLanguage` plist XML,
`.tmLanguage.json`, et `.tmg.json` — le format JSON écrit pour ce
moteur), un mapping des portées vers les `TokenType` du moteur, et un
corpus de grammaires embarquées. Supporter un langage de plus par la
voie grammaires = **ajouter un fichier**, pas du Java.

## Position dans l'ensemble

```
:cel-textmate ──► :cel-core
   implémente TextMateTokenizer (crochet de SyntaxHighlighter)
```

- Il implémente l'interface `jo.codeeditor.highlight.TextMateTokenizer`
  **déjà définie dans cel-core** : le chemin est strictement additif —
  sans ce module, le tokéniseur intégré par famille reste en service.
- Il ne connaît **ni** `:cel-ui` **ni** `:cel-lsp` : la couleur produite
  est un `StyledLine` comme l'autre voie.
- `:demo` l'accroche au démarrage et offre la bascule « voie grammaires
  contre voie intégrée » (la vérité vit dans le registre :
  `isAvailable(langage)`).

## Activation

```java
// Une fois au démarrage, avant toute vue éditeur :
SyntaxHighlighter.setTextMateTokenizer(TextMateTokenizerImpl.create());
```

`create()` charge la grammaire du dépôt (`csharp.tmg.json`, langage
« grammaire-only » : aucun tokéniseur dédié). Les grammaires tierces
embarquées sont **inactives par défaut** (contrat « opt-in par
langage ») :

```java
TextMateTokenizerImpl impl = TextMateTokenizerImpl.create();
impl.loadEmbeddedGrammar("python");   // active la grammaire embarquée
impl.registerGrammar("mylang", source); // grammaire fournie par l'hôte
impl.unregisterGrammar("mylang");
impl.isAvailable("python");           // true si une grammaire est active
impl.hasEmbeddedGrammar("python");    // true SANS l'activer : la sonde de
                                      // visibilité d'une bascule hôte
```

`isAvailable` dit si une grammaire est **active** (enregistrée dans le
registre) ; `hasEmbeddedGrammar` dit si le langage **peut** passer sur
la voie grammaires (ressource embarquée présente, sans chargement ni
état). Un hôte qui expose une bascule « tokéniseur intégré ↔
grammaires » pilote la **visibilité** de sa bascule sur la sonde et sa
**coche** sur l'état — piloter la visibilité sur `isAvailable`
masquerait la bascule au premier lancement et après chaque
désactivation, rendant le retour impossible.

## Le moteur

| Classe | Rôle |
|---|---|
| `TextMateTokenizerImpl` | implémentation du crochet ; chargement/activation des grammaires |
| `TextMateGrammar` | chargement des trois formats (détection automatique), règles d'un langage |
| `GrammarTokenizer` | exécution des règles : `begin`/`end`/`while`/`patterns`, `captures`, imbrications, injections |
| `ScopeMapper` | listes de portées (séparées par espaces) → `TokenType` ; la première portée non neutre de l'interne vers l'externe l'emporte — amorce du chantier C4 (schéma de portées commun aux deux voies) |
| `internal/JsonLite` · `internal/PlistParser` | parseurs internes (inchangés) ; plist **refuse les DOCTYPE** (protection XXE, jamais affaiblie) |
| `regex/` (couture interne — plan 10) | `DialectNormalizer` (écarts du port Java du dialecte), `EncodedLine` (ligne UTF-8 + index char↔octet), `CompiledRegex`/`LineMatcher` (essai ancré, budget par essai), `MatchBudget` (100 ms, journal throttlé), `RegexCache` (LRU 512, clé = source normalisée) |

Sémantique couverte (chacune gardée par des tests) : largeur nulle
sémantique, `\n` virtuel en fin de ligne, idiome `\G`/`(?!\G)` en tête
de `begin` (fin du dernier `begin` de la ligne), clause `while`,
références arrière dans `end`, grammaires **imbriquées** — les
includes `source.xxx` se résolvent vers les autres grammaires
enregistrées : `markdown` embarque `java` ; un hôte qui active `html`
**et** `css` obtient les blocs `<style>` colorés par la grammaire CSS
(la démo fait cette co-activation ; une référence absente dégrade en
règle muette).

## Grammaires embarquées

| Fichier | Langage | Origine | Licence |
|---|---|---|---|
| `csharp.tmg.json` | `csharp` | ce dépôt | Apache-2.0 |
| `css.tmLanguage.json` | `css` | tierce (MIT) | MIT |
| `html.tmLanguage.json` | `html` | tierce (MIT) | MIT |
| `java.tmLanguage.json` | `java` | tierce (MIT) | MIT |
| `javascript.tmLanguage.json` | `javascript` | tierce (MIT) | MIT |
| `lua.tmLanguage.json` | `lua` | tierce (MIT) | MIT |
| `markdown.tmLanguage.json` | `markdown` | tierce (MIT) | MIT |
| `python.tmLanguage.json` | `python` | tierce (MIT) | MIT |
| `xml.tmLanguage.json` | `xml` | tierce (MIT) | MIT |
| `kotlin.tmLanguage` | `kotlin` | tierce (plist XML) | Apache-2.0 |

Les copies tierces sont **verbatim** (en-têtes d'origine conservés),
embarquées après vérification fichier par fichier (décision D3 de
l'ADR-002) ; la table d'attribution détaillée (source amont,
titulaire du copyright) vit dans [`NOTICE`](../../NOTICE) —
obligation de licence. Les grammaires tierces sont opt-in ;
`csharp` est chargée par `create()`.

## Limites connues (honnêteté du module)

- **Deux portes de garde-fou** — le coût de restyle par grammaire est
  supérieur au tokéniseur intégré : au-delà de `TEXTMATE_LINE_LIMIT`
  (800 lignes par document, décision ADR-002 maintenue) OU de
  `TEXTMATE_CHAR_LIMIT` (5 000 caractères sur UNE ligne — fichiers
  minifiés HTML/JS/CSS, la décision vivant côté crochet
  `SyntaxHighlighter`), le moteur **retombe** sur le tokéniseur
  intégré au coût linéaire.
- **Plus riche, pas identique** — la bascule démo « voie intégrée /
  voie grammaires » ne produit PAS un rendu pixel-identique : le
  calibrage C4 aligne les constructions que les deux voies typent
  (commentaires, javadoc, mots-clés, primitifs, chaînes, nombres,
  annotations), mais les grammaires colorent des catégories que la
  voie intégrée laisse en texte brut (paramètres, variables,
  récepteurs d'appels) et l'inverse existe ponctuellement (constantes
  tout-en-majuscules, marquées côté intégré, pas par les grammaires
  tierces). Les délimiteurs de javadoc java portent la portée
  `punctuation.definition.comment` de la grammaire tierce (deux gris
  autour d'un corps jade) là où la voie intégrée colore tout en
  DOC_COMMENT.
- **Fidélité TextMate, pas perfection** — sémantique « état remis à
  zéro en fin de ligne » : en `kotlin`, un commentaire posé sur la
  même ligne qu'une déclaration `fun` fermée n'est pas coloré
  (comportement conforme au modèle de grammaire, documenté par test).
- **Dialecte natif** (refonte 4.0, plan 10) — les motifs du dialecte
  TextMate sont exécutés nativement (récursion `\g<nom>`, possessifs,
  `\k<nom>`, `(?#…)`, `\h` hexadécimal, Unicode conforme à la
  référence) ; chaque essai est budgété (100 ms) et se replie en
  non-correspondance, une grammaire défaillante ne plante jamais le
  rendu. Les écarts du port Java du dialecte sont normalisés
  (`\w`→`\p{Word}`, `\d`→`\p{Nd}`, `\s`→ table d'espaces de la
  référence) et gardés par la suite de conformité permanente
  (`RegexConformityTest`, valeurs établies sur la référence C).
- **Registre d'états du moteur** — les piles inter-lignes sont
  internées dans une carte qui vit pour la durée du processus
  (partagée entre documents par grammaire) : croissance bornée par
  la diversité des piles rencontrées, jamais purgée — un hôte très
  longévif peut observer une occupation mémoire lente.
- Le chantier C4 (palette de thèmes par catégories de portées, ~25
  clés) est amorcé par `ScopeMapper` : les types fins existent dans
  `TokenType`, toutes les entrées de palette ne sont pas encore
  câblées.

## Tests

```bash
./gradlew :cel-textmate:test     # 96 tests
```

Grammaires de travail dédiées (`src/test/resources/textmate/`) pour
chaque construction du moteur (ancres `\G`, `while`, captures,
backrefs, lookahead), fumée sur les grammaires réelles embarquées,
tests du mapping de portées.

## Voir aussi

- [`docs/adr/ADR-002-strategie-multi-langages.md`](../../docs/adr/ADR-002-strategie-multi-langages.md) —
  la décision complète (D1–D5, chantiers C1→C3→C2).
- [`editor/cel-core/README.md`](../cel-core/README.md) — le crochet
  `TextMateTokenizer` et la porte de 800 lignes.
- [`demo/README.md`](../../demo/README.md) — la bascule
  « Grammaires TextMate » en action.
- [`NOTICE`](../../NOTICE) — attributions des grammaires tierces.
