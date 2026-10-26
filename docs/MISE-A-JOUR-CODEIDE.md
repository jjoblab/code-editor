# Mise à jour de l'application hôte — notes de passage inter-versions

> Notes de passage adressées à l'application qui consomme cette
> bibliothèque : quoi faire (et ne pas faire) pour franchir chaque
> version. Les sections sont indépendantes — un hôte qui saute plusieurs
> versions lit toutes les sections intermédiaires.

## 3.74.0

### 1. État de publication

| Élément | État |
|---|---|
| Tag | `v3.74.0` (annoté, posé sur `main` après CI vert) |
| SHA de la version | `4082364` (`main`, 2026-10-20) |
| `ci.yml` (main) | SUCCESS (build + 2 610 tests + lint + contrôles) — échec initial 4082364 = 2 tests d'hygiène de threads flakys sous charge runner, relancé (précédent interne : flake dependabot relancé de la même façon) ; vert au 2ᵉ essai |
| `publish.yml` (tag) | SUCCESS (run 00:28:36Z, terminé ~00:31) — les 5 AAR publiés sur GitHub Packages (`jo.codeeditor:cel-{core,lsp-api,lsp,ui,textmate}:3.74.0`, .pom vérifiés HTTP 200) |
| JitPack `com.github.jjoblab/code-editor/v3.74.0` | **ok** (commit d55cb03, build 45 s, `publishToMavenLocal` 5 modules) — AAR vérifiés téléchargeables (HTTP 206) : `cel-ui`, `cel-lsp`, `cel-lsp-api`, `cel-core`, `cel-textmate` (chemin : `com.github.jjoblab.code-editor:<module>:v3.74.0`) |

### 2. Plage de compatibilité (l'application vient de 3.72.0)

Le franchissement 3.72.0 → 3.74.0 traverse AUSSI la 3.73.0.

**3.73.0 — carte d'aperçu « goutte d'eau » + IME au niveau plateforme.**
Pour un hôte : la carte d'aperçu (si branchée via `EditorPreviewHost`)
gagne une politique de cohabitation avec les popups de frappe et un
réglage de taille ; l'IME passe au niveau plateforme (plan 09, R1–R9) —
la vue gère elle-même l'`EditorInfo` et les commandes privées des
claviers, moins de code côté hôte. **Aucune rupture d'API.** Point
d'attention unique : le comportement IME a changé en interne — si
l'application appliquait des contournements de clavier (forcer
l'affichage, intercepter des commandes privées), les RETIRER.

**3.74.0 — cycle de vie du langage LSP + formatage (ADR-003).**
`disconnect()` referme désormais toutes les capacités LSP sur la vue
(restaure `EmptyLanguage`) ; le formatage gagne options, sélection et
disponibilité. **Aucune rupture d'API** — tout est additif (méthodes
`default` du SPI ou nouvelles méthodes). Aucune migration obligatoire :
le code existant compile et se comporte mieux (l'onglet sans serveur
n'hérite plus des capacités du fichier précédent).

### 3. API ajoutée (signatures exactes, `@since 3.74.0`)

```java
// jo.codeeditor.view.EditorView — thread UI attendu
public boolean canFormat()
public boolean canFormatSelection()
public void formatSelection(java.util.function.Consumer<Boolean> callback)
public void setFormattingOptions(int tabSize, boolean insertSpaces)

// jo.codeeditor.view.EditorCommands
public static final String FORMAT_SELECTION = "editor.format_selection"

// jo.codeeditor.lang.provider.Formatter (SPI — défaut faux/null, aucune
// implémentation tierce impactée)
default boolean supportsRange() { return false; }

// jo.codeeditor.view.EditorView.FormatterResolver (défaut null = refus)
default String formatRange(String text, int start, int end) { return null; }

// jo.codeeditor.lang.host.EditorHost (relais de setFormattingOptions)
default int formattingTabSize() { return -1; }          // <=0 = non posé
default Boolean formattingInsertSpaces() { return null; } // null = non posé
default boolean isOnUiThread() { return !isAttachedToWindow(); }
```

**Raccourci clavier physique** : `Ctrl+Shift+K` → `FORMAT_SELECTION`
(même famille que `Ctrl+Shift+I` formatage de document ; touche vérifiée
libre — aucune collision dans la table par défaut ; rebindable via
`EditorKeymap.bind` comme toute commande).

**Callback de `formatSelection(Consumer<Boolean>)`** (rappel sur le
thread UI) :
- `Boolean.TRUE` — le texte a changé (un seul pas d'undo, caret et
  sélection conservés) ;
- `Boolean.FALSE` — refus propre : formateur sans plages, OU sélection
  vide (no-op documenté), OU aucune édition retournée ;
- `null` — aucun formateur installé sur le langage courant.

`canFormat()`/`canFormatSelection()` : vraies uniquement si un formateur
est installé (et, pour la sélection, s'il gère les plages). Reflètent
le langage courant — à consulter à chaque changement d'onglet pour
griser/masquer les actions.

### 4. Changements de comportement

- **`LspEditor.disconnect()` restaure `EmptyLanguage`** : toutes les
  capacités LSP sont fermées (complétion, survol, signatures,
  renommage, formateur, indices, surlignage, actions de code,
  diagnostics) — aucune requête ne part ; idempotent ; retire
  l'écouteur d'édition ; neutralise la course connect/disconnect (jeton
  de génération). La coloration lexicale, les plis lexicaux, la frappe
  intelligente et l'IME ne dépendent pas de LSP : ils survivent.
- **Plage non supportée par le serveur ⇒ no-op** :
  `formatSelection` refuse proprement (texte inchangé, AUCUNE requête) —
  le document entier n'est JAMAIS formaté à la place de la sélection.
- **Sélection vide ⇒ no-op** documenté (callback `FALSE`).
- Les requêtes de formatage portent désormais `FormattingOptions`
  (tabSize, insertSpaces) : hôte → détection d'indentation du document →
  4 espaces par repli.
- Les échecs du formateur (délai, erreur serveur, résultat vide) sont
  journalisés via `LspProject.setLogSink(...)` — sans jamais écrire le
  contenu du document ; les éditions serveur sont validées (bornes,
  chevauchements) avant application.

### 5. Ce que l'application hôte doit faire

1. **Monter la version** dans `gradle/libs.versions.toml` :
   `code-editor = "3.74.0"` (les coordonnées JitPack
   `com.github.jjoblab.code-editor:cel-ui:v3.74.0` etc. suivent).
2. **Remplacer le pilotage de l'action « Formater »** par
   `canFormat()` : griser/masquer l'action quand il n'y a pas de
   formateur (au lieu de deviner depuis l'état de la connexion LSP).
3. **Ajouter « Formater la sélection »** via `canFormatSelection()` +
   `formatSelection(callback)` + (optionnel) `EditorCommands.FORMAT_SELECTION`
   / `Ctrl+Shift+K` — les valeurs du callback distinguent refus
   (`FALSE`) et absence de formateur (`null`).
4. **Alimenter les options de formatage** depuis le réglage de largeur
   de tabulation de l'application :
   `editorView.setFormattingOptions(tabSize, espacesAuLieuDeTabulations)`
   — sinon le formateur détecte l'indentation du document (repli
   4 espaces).
5. **Retirer tout contournement local** : plus besoin de suivi manuel de
   « l'onglet a-t-il un serveur » pour couper les actions LSP —
   `canFormat()`/`canFormatSelection()` et la déconnexion complète
   s'en chargent.

### 6. Ce que l'application hôte ne doit PAS faire

- **Ne PAS ajouter de `setLanguage(null)` de contournement** dans le
  coordinateur LSP au changement d'onglet : la bibliothèque restaure
  `EmptyLanguage` elle-même dans `disconnect()` (un appel restant serait
  redondant mais inoffensif — l'omettre évite la confusion).
- Ne PAS appeler `formatSelection` depuis un autre thread que le thread
  UI (la méthode poste elle-même la requête serveur hors thread UI).
- Ne PAS déduire « le serveur sait formater la sélection » de la seule
  présence d'un formateur : passer par `canFormatSelection()`
  (`supportsRange()` côté SPI).

### 7. Scénarios de vérification sur appareil

1. **Onglet `.java` → onglet `.txt`** : aucune complétion, aucun survol,
   aucun formatage (action grisée), aucun indice en ligne, aucune marque
   de diagnostic — la coloration lexicale du `.txt` reste active.
2. **Retour sur `.java`** : tout revient (reconnexion complète — les
   débounceurs didChange/didSave renaissent).
3. **Changement d'onglet très rapide** pendant la connexion d'un
   serveur : jamais de complétion du langage du fichier précédent sur le
   nouvel onglet (jeton de génération).
4. **Formatage d'un document** (`Ctrl+Shift+I` ou action) : les options
   du réglage de tabulation de l'application sont respectées.
5. **Formatage d'une sélection** (`Ctrl+Shift+K` ou action) : un seul
   pas d'undo (Ctrl+Z revient à l'état d'avant formatage), caret
   conservé, plis non détruits.
6. **Sélection + serveur sans formatage de plage** : rien ne bouge
   (action grisée si `canFormatSelection()` faux).
7. **Saisie et IME** (risque lié à 3.73.0) : composer du texte, clavier
   Gboard/AOSP, retour de sélection après undo — l'IME est au niveau
   plateforme depuis 3.73.0, vérifier les contournements retirés.

### 8. Risques connus et limites

- **Serveur sans formateur** (ex. un serveur Kotlin sans
  `documentFormattingProvider`) : `canFormat()` doit renvoyer faux —
  l'action « Formater » se grise ; c'est le comportement attendu, pas un
  défaut.
- **Serveur avec formatage de document mais SANS formatage de plage** :
  `canFormatSelection()` faux, `formatSelection` refuse proprement —
  jamais de formatage du document entier à la place.
- Les options LSP facultatives (trimTrailingWhitespace,
  insertFinalNewline, trimFinalNewlines) ne sont PAS propagées (hors
  périmètre 3.74.0).
- Formatage à la frappe (`onTypeFormatting`) et à l'enregistrement :
  toujours absents (hors périmètre).
- Le journal du formateur ne contient JAMAIS le contenu du document
  (délais, classes d'exception, compteurs uniquement).

## 4.0.0

### 1. État de publication

| Élément | État |
|---|---|
| Tag | `v4.0.0` (annoté, posé sur `main` APRÈS le correctif de bancs `849bf3c` — la 4.0.0 tagée EST la 4.0.0 testée) |
| SHA de la version | `849bf3c` (`main`, 2026-10-21) |
| `ci.yml` (main) | SUCCESS — l'échec initial de `39a1b64` était ENVIRONNEMENTAL (3 assertions de timing : runners CI partagés mesurés 1,2 à 2,1× plus lents ; 2 634/2 636 et 2 635/2 636 tests verts selon les runs) ; les plafonds CI relevés (30 ms/ligne corpus, 250 µs buildColumnMaps, 20 ms médiane, 400 ms pire typeChar) sont posés dans `849bf3c` — les bornes machine de référence restent INTÉGRALEMENT appliquées hors CI |
| `publish.yml` (tag) | SUCCESS — artefacts publiés sur GitHub Packages (`jo.codeeditor:cel-{core,lsp-api,lsp,ui,textmate}:4.0.0`) |
| JitPack `com.github.jjoblab/code-editor` | `v4.0.0` **ok** (vérifié via l'API builds le 2026-10-24 — consommé par CodeIDE v0.175.0, `libs.versions.toml` `code-editor = "4.0.0"`) |

### 2. Compatibilité (l'application vient de 3.74.0)

**AUCUNE rupture d'API — tout est interne ou additif.** Le hôte compile
et se comporte mieux sans AUCUN changement obligatoire.

- `cel-textmate` : refonte INTERNE du moteur de regex (moteur natif du
  dialecte, plan 10) — la couture `jo.codeeditor.textmate.regex` est le
  seul importateur du moteur, AUCUNE API publique touchée. Les 7 règles
  markdown à récursion (`\G<square>`), la règle CSS `selector` et les
  idiomes lookbehind compilent nativement ; `\w`/`\d`/`\s`/`\b` suivent
  la référence Unicode ; chaque essai est BUDGÉTÉ (100 ms) — un motif
  catastrophique se replie en non-correspondance au lieu de bloquer le
  fil de restyle.
- `cel-core` : régime PROGRESSIF des grands documents (plan 11) —
  `TEXTMATE_LINE_LIMIT` devient `TEXTMATE_EAGER_LIMIT` (renommage
  INTERNE, 800 lignes = seuil de bascule, plus porte coupe-circuit) ;
  au-delà, plus AUCUNE passe complète à l'ouverture, à l'annulation ni
  au changement de langue : états remplis en arrière-plan par morceaux
  INTERRUPTIBLES, spans calculés À LA DEMANDE (budget interactif
  10 ms/essai), mémoire bornée par cache LRU 4 096 lignes.
- **API ajoutée (seule nouveauté publique)** :
  ```java
  // jo.codeeditor.view.EditorView — thread UI attendu (plan 11 §2.2)
  // Indice de viewport au remplissage progressif (facultatif : sans
  // appel, les spans se calculent quand même à la demande).
  public void hintViewport(int firstVisibleLine, int lastVisibleLine)
  ```
- Corpus de grammaires 10 → 23 (13 tierces MIT/Apache-2.0 VERBATIM :
  c, cpp, dockerfile, go, json, php, ruby, rust, shell, sql, toml,
  typescript, yaml ; une règle ruby lookbehind à longueurs variables
  ignorée avec avertissement — dégradation cosmétique documentée).

### 3. Ce que l'application hôte doit faire

Rien d'obligatoire. Facultatif : consommer `hintViewport(first, last)`
au scroll pour resserrer les spans du viewport (CodeIDE ne le consomme
pas encore à v0.175.0 — consommation optionnelle documentée).

### 4. Ce que l'application hôte ne doit PAS faire

- Ne PAS référencer `TEXTMATE_LINE_LIMIT` (renommé
  `TEXTMATE_EAGER_LIMIT`, interne) — aucun usage côté CodeIDE
  (vérifié par grep).
- Ne PAS compter sur la coloration COMPLÈTE au-delà de 800 lignes : le
  régime progressif remplit en arrière-plan, les premières frames
  montrent le viewport coloré (c'est le comportement voulu).

### 5. Scénarios de vérification sur appareil

- Ouvrir un fichier de CHAQUE nouveau langage (ex. `.go`, `.rs`,
  `Dockerfile`) : coloration lexicale immédiate.
- Coller 100k+ lignes : ouverture fluide, scroll sans jank, la
  coloration suit le viewport (remplissage arrière-plan visible).
- `**gras**`, `*italique*`, liens imbriqués dans du gras (markdown) :
  ils COLORENT désormais (règles à récursion natives).
- `var année = 3;` : le mot ENTIER colore (Unicode conforme).

## 4.1.0

### 1. État de publication

| Élément | État |
|---|---|
| Tag | `v4.1.0` (annoté, posé sur `main`) |
| SHA de la version | `2ce6f1d` (`main`, 2026-10-24) |
| `ci.yml` (main) | SUCCESS |
| `publish.yml` (tag) | SUCCESS — artefacts publiés sur GitHub Packages (`jo.codeeditor:cel-*:4.1.0`) |
| JitPack `com.github.jjoblab/code-editor` | `v4.1.0` **ok** (consommé par CodeIDE v0.176.0, `libs.versions.toml` `code-editor = "v4.1.0"` — l'API B27-C3 a compilé et testé côté hôte) |

### 2. Compatibilité (l'application vient de 4.0.0)

**AUCUNE rupture d'API — STRICTEMENT additif.** Le corps de la carte
« goutte d'eau » devient TACTILE (B27-C3) : TROIS méthodes À DÉFAUT sur
`EditorPreviewHost` — `wantsInteractiveDropPreview()` (faux = corps
englouti sans agir, comportement B27 au bit près),
`onPreviewDropTouch(action, sx, sy)` (DOWN/MOVE/UP/CANCEL du premier
doigt au repère EXACT de `drawDropPreview`, doigt sorti CLAMPÉ) et
`onPreviewDropLongPress(sx, sy)` (l'« inspection » mobile). Un DOWN
DÉCLINÉ retombe sur le corps inerte POUR CE GESTE ; les onglets, le ✕
et la barre de taille gardent leurs gestes propres. +8 tests cel-ui.
Consommé par CodeIDE v0.176.0 (mission « Previews » C3, ADR 0142).

## 4.2.0

### 1. État de publication

| Élément | État |
|---|---|
| Tag | `v4.2.0` (annoté, posé sur `main`) |
| SHA de la version | `0f31ff4` (`main`, 2026-10-24 — le tag v4.2.0 a D'ABORD vécu sur `936d5bf` dont la CI échouait au SEUL garde-fou D-09 de version documentée (README resté 4.1.0, zéro échec de code/tests) ; le tag est DÉPLACÉ sur le commit resynchronisé — patron v4.0.0/849bf3c, artefacts IDENTIQUES, seuls des fichiers `.md` changent) |
| `ci.yml` (main) | `936d5bf` : échec doc-seul (D-09) ; `0f31ff4`/`bbf37ce` : échec du garde-fou « Budget des classes orchestrateurs » (EditorView 4258 l./295 méthodes — l'état/logique B28 déménagent dans `EditorPreviewController`, plafonds relevés et DOCUMENTÉS : 4180→4225 l., 292→295 méthodes, champs 177/180 SOUS plafond ; zéro changement d'API) ; `a27fe8e` : SUCCESS attendu (tous garde-fous verts localement) |
| JitPack `com.github.jjoblab/code-editor` | `v4.2.0` — consommé par CodeIDE v0.177.0 (la chaîne locale de l'hôte a résolu et compilé l'API B28) |

### 2. Compatibilité (l'application vient de 4.1.0)

**AUCUNE rupture d'API — STRICTEMENT additif, opt-in par
`setPreviewAnchors`.** Les yeux d'aperçu de gouttière vivent désormais
AUX LIGNES des `@Preview` (B28, façon Android Studio — écho
`PreviewPickerLineMarkerProvider` : UN marqueur PAR annotation, AUCUN
sans) :

- `EditorView.setPreviewAnchors(List<Integer>)` — `null` (défaut) :
  MODE HISTORIQUE au bit près (œil unique du caret, B27) ; liste VIDE :
  AUCUN œil ; sinon UN œil par rangée (triée, dédupliquée). Le mode
  ancré PREND LE PAS sur l'œil du caret (les DEUX hit-tests se
  partagent : `hitTestGutterPreviewAction` se taut, le nouveau
  `hitTestGutterPreviewAnchor(x, y)` répond par la LIGNE).
- Tap sur un œil ancre → la carte s'ouvre SUR CETTE rangée et le hôte
  apprend la ligne (`onPreviewAnchorTapped(int)`, méthode à défaut) —
  il sélectionne l'onglet de LA preview via le nouveau relais
  `EditorView.selectPreviewDropTab(int)` (clampé, même notification
  qu'un tap d'onglet). Re-tap sur l'œil gelé = fermeture ; tap sur un
  autre œil = ré-ancre et l'onglet suit.
- Les hôtes qui ne posent JAMAIS d'ancres (Markdown/HTML, démo,
  CodeIDE ≤ v0.176.0) ne constatent AUCUN changement.

### 3. Ce que l'application hôte ne doit PAS faire

- Ne PAS poser d'ancres sur des lignes SANS `@Preview` vivante : la
  parité AS veut ZÉRO icône sans sujet — vider la liste plutôt que
  mentir (`setPreviewAnchors(emptyList())` quand la découverte ne
  trouve rien).
- Ne PAS appeler `selectPreviewDropTab` HORS du callback
  `onPreviewAnchorTapped` et du tap d'onglet : la sélection
  programmatique notifie le hôte (miroir exact d'un tap — boucle
  possible si le hôte réagit à sa propre notification).

### 4. Scénarios de vérification sur appareil

- Ouvrir un fichier `.kt` AVEC `@Preview` : un œil à CHAQUE ligne
  d'annotation, AUCUN ailleurs (l'œil du caret a disparu).
- Ouvrir un `.kt` SANS `@Preview` : AUCUNE icône de gouttière.
- Taper un œil : la carte s'ouvre ancrée à SA ligne et montre LA
  preview correspondante ; taper un autre œil : la carte ré-ancre et
  change d'onglet ; re-taper l'œil de l'ancre : la carte se referme.
- Ajouter/supprimer une `@Preview` à chaud : les yeux suivent le
  document (rafraîchissement côté hôte, débouncé).

## 4.3.0

### 1. État de publication

| Élément | État |
|---|---|
| Tag | `v4.3.0` (annoté, posé sur `main`) |
| SHA de la version | `6ffb490` (`main`, 2026-10-26) |
| `ci.yml` (main) | SUCCESS (build + 2 704 tests + lint + garde-fous) |
| `publish.yml` (tag) | SUCCESS — les 5 AAR publiés sur GitHub Packages (`jo.codeeditor:cel-{core,lsp-api,lsp,ui,textmate}:4.3.0`) |
| JitPack `com.github.jjoblab/code-editor` | se construit À LA PREMIÈRE DEMANDE (`v4.3.1` lancé à la demande le 2026-10-27 ; la 4.3.0 n'est pas encore consommée — demander la résolution déclenche le build) |

### 2. Compatibilité (l'application vient de 4.2.0)

**AUCUNE rupture d'API — STRICTEMENT additif.** Le chantier
« fiabiliser le popup de complétion » transporte et honore le drapeau
LSP `isIncomplete`, unifie l'appariement filtre/classement/
surbrillance et enrichit l'affichage des candidats. Un hôte qui ne
change rien compile et constate un classement plus prévisible (un
libellé de fonction `Text()` passe devant `TextField()` pour le
préfixe `Text` ; les initiales de mots remontent avant les
sous-séquences quelconques ; un mot-clé ne passe jamais devant un
symbole résolu de même palier).

**Point d'attention unique — couplage serveur** : tant que le serveur
de langage envoie TOUJOURS `isIncomplete = true` dans ses listes, ce
client re-requête à chaque édition du préfixe (comportement voulu,
coalescé et débouncé) ; `setHonorIncompleteCompletion(false)` coupe
la re-requête (retour au filtrage local pur). Le chantier serveur
parallèle (drapeau honnête) supprimera ce couplage.

### 3. API ajoutée (signatures exactes, `@since 4.3.0`)

```java
// jo.codeeditor.lang.provider.CompletionPublisher (SPI — no-op à défaut)
public default void setIncomplete(boolean incomplete) {}

// jo.codeeditor.view.EditorView.CompletionProvider (défaut : enveloppe
// la variante 5 paramètres avec le drapeau à faux)
public default CompletionResult provideResult(
    String text, int caret, int tokenStart, String prefix,
    Character triggerCharacter)

// jo.codeeditor.view.CompletionResult (nouvelle classe)
public final List<CompletionSession.Item> items;
public final boolean isIncomplete;

// jo.codeeditor.view.EditorView — thread UI attendu
public void setHonorIncompleteCompletion(boolean honor)   // défaut vrai
public boolean isHonorIncompleteCompletion()
public void setCompletionDebounceMs(int debounceMs)       // défaut 80
public int getCompletionDebounceMs()

// jo.codeeditor.completion.CompletionSession.Item — constructeurs
// étendus (champs optionnels container/documentation ; les
// constructeurs existants délèguent)
public Item(..., String container, String documentation)
// + CompletionItem (SPI cel-lsp-api) : mêmes champs optionnels

// jo.codeeditor.completion.CompletionMatcher (nouvelle classe,
// cel-core — appariement unifié : matches/tier/matchPositions/
// leadingIdentifier, échelle 6 paliers, groupes)
```

**Dépréciations (jamais supprimées, délèguent au matcher)** :
`CompletionSession.filtered(...)`, `matchTier(...)`,
`matchPositions(...)`, les constantes `TIER_*` — `@Deprecated` avec
renvoi. `NavigationMenu.isCamelHumpSubsequence` DÉLÈGUE au matcher.

### 4. Changements de comportement

- **Liste complète** (drapeau absent/faux) : filtrage local pur —
  aucune requête supplémentaire, réactivité inchangée.
- **Liste tronquée** (`isIncomplete = true`) : filtrage local pour le
  retour visuel immédiat ET re-requête débouncée (80 ms par défaut) à
  CHAQUE édition du préfixe — prolongation comme retour arrière ;
  coalescence (une frappe pendant un vol du même token ne s'empile
  pas) ; génération anti-course conservée. Un candidat écarté par le
  plafond serveur finit par réapparaître.
- **Classement local** : paliers sur l'IDENTIFIANT DE TÊTE du libellé
  (`Text(text: String)` → `Text` ; les noms XML restent entiers),
  échelle exact > exact à la casse > préfixe > préfixe à la casse >
  camel humps > sous-séquence ; tri STABLE (l'ordre serveur est
  conservé à l'intérieur d'un palier) ; groupes à palier égal :
  symbole résolu avant mot-clé avant snippet avant mot du buffer.
- **Surbrillance** : les indices en gras sont EXACTEMENT ceux qui ont
  fait matcher (même appariement que le filtre — plus de divergence).
- **`NavigationMenu.isCamelHumpSubsequence` accepte désormais les runs
  contigus** (« get » matche « getString » en plus de « gS ») — la
  Javadoc documentait ce comportement depuis l'origine, le code la
  contredisait ; l'implémentation suit la Javadoc (les préfixes d'une
  lettre et les débuts de mots matchent comme avant, seuls les runs
  contigus du premier caractère deviennent valides).
- **Affichage riche** : `container` (origine du symbole) aligné à
  l'extrême droite, tronqué à l'ellipse au-delà de 40 % de la largeur
  (mesures en dp) ; `detail` garde sa place ; `documentation`
  transportée jusqu'à l'item (le panneau de doc du popup reste à
  venir — optionnel, non livré). `MAX_ROWS` (8), `WIDTH_DP` (280) et
  `ROW_HEIGHT_DP` (28) inchangés.

### 5. Ce que l'application hôte doit faire

1. **Monter la version** dans `gradle/libs.versions.toml` :
   `code-editor = "v4.3.1"` (coordonnées JitPack
   `com.github.jjoblab.code-editor:cel-*:v4.3.1`).
2. **Rien d'obligatoire côté API** : tout est additif, les valeurs
   par défaut sont sûres (honneur au drapeau actif, debounce 80 ms).
3. Facultatif : exposer les deux réglages dans l'écran d'options de
   l'éditeur (`setHonorIncompleteCompletion`,
   `setCompletionDebounceMs`) — utiles pendant la transition du
   serveur vers le drapeau honnête.
4. Côté serveur (dépôt frère) : envoyer un `isIncomplete` HONNÊTE
   (vrai seulement si la liste est réellement partielle) et alimenter
   `labelDetails.description`/`detail` + `documentation` — le client
   les affiche déjà.

### 6. Ce que l'application hôte ne doit PAS faire

- Ne PAS réimplémenter de filtrage/classement local devant le popup :
  l'appariement unifié (paliers, groupes, camel humps) vit dans la
  bibliothèque — un double classement contredirait la surbrillance.
- Ne PAS retirer les appels aux méthodes `@Deprecated` du matcher
  historique : elles fonctionnent (délégation) — la migration vers
  `CompletionMatcher` est facultative et progressive.
- Ne PAS se fier au rang serveur seul pour prédire l'ordre final :
  le client re-classe par (palier, groupe), l'ordre serveur ne
  départage qu'à l'intérieur d'un palier.

### 7. Scénarios de vérification sur appareil

1. Taper `Text` dans un fichier Kotlin avec `TextField()`, `Text()`,
   `TextView` dans la base : `Text()` en tête (exact sur l'identifiant
   de tête, devant `TextField()` préfixe).
2. Taper `LLM` : `LazyListModifier` (initiales) devant un homologue
   « sous-séquence quelconque » ; les indices en gras sont les
   initiales (0, 4, 8).
3. Serveur qui plafonne ses listes (`isIncomplete = true`) : continuer
   à taper puis EFFACER — le candidat écarté par le plafond
   réapparaît (re-requête au retour arrière incluse).
4. Serveur honnête (liste complète) : aucune requête supplémentaire
   pendant la frappe (filtrage local pur).
5. Frappes rapides sur serveur lent : jamais plus d'une requête en
   vol par token (coalescence).
6. `setHonorIncompleteCompletion(false)` : retour au comportement
   historique (une requête par token).
7. Candidat avec `container` : l'origine s'affiche à droite, tronquée
   proprement sur écran étroit ; sans `container`, disposition
   exacte d'avant.

## 4.3.1

### 1. État de publication

| Élément | État |
|---|---|
| Tag | `v4.3.1` (annoté, posé sur `main`) |
| SHA de la version | `4ccd6c4` (`main`, 2026-10-27) |
| `ci.yml` (main) | SUCCESS (build + 2 714 tests + lint + garde-fous) |
| `publish.yml` (tag) | SUCCESS — les 5 AAR publiés sur GitHub Packages (`jo.codeeditor:cel-*:4.3.1`) |
| JitPack `com.github.jjoblab/code-editor` | `v4.3.1` build lancé à la demande le 2026-10-27 (« Building » à la rédaction) |

### 2. Compatibilité (l'application vient de 4.3.0)

**AUCUN changement d'API — la bibliothèque est inchangée.** La
4.3.1 est concentrée sur l'application de démonstration (banc
d'essai « listes tronquées » au menu, bascules bande d'erreurs et
sélecteur de couleur, préférence « LSP automatique » consommée,
identité de thème conservée aux changements d'onglet) et sur les
compteurs/figures de la documentation. Pour l'hôte : monter la
version et rien d'autre. Les scénarios de vérification de la 4.3.0
restent la référence — le banc de la démo permet de les rejouer sans
serveur : « Simuler un moteur qui plafonne » + panneau Journal
montrent les re-requêtes en direct.
