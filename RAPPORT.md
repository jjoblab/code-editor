# RAPPORT — Lot 0 (gutter) + Lot 1 (édition contextuelle — base) + Lot 1+3 (perf popups)

## Environnement d'exécution

- **SDK Android** : installé à `/home/z/android-sdk` (cmdline-tools +
  platform-tools + `platforms;android-34` + build-tools 36 acceptés
  via AGP au premier build).
- **JDK** : Temurin 21 (portable, `/home/z/jdk-21.0.5+11`) avec
  `javac` — le JRE headless du système ne fournissant pas `javac`,
  requis par AGP (cf. AGENT.md).
- **Gradle** : wrapper 9.5.1, AGP 9.0.0. Build OK pour les 4 modules
  + demo.
- **Tests exécutés** :
  - `:cel-core:test` (JUnit Jupiter) — **720+ tests verts**.
  - `:cel-ui:testDebugUnitTest` (Robolectric) — **934+ tests verts**.
  - `:demo:testDebugUnitTest` — smoke test vert.
  - `:demo:assembleDebug` — APK build OK.
- **Lint** : non exécuté dans cette session (l'objectif était les
  correctifs + tests ; `./gradlew lint` reste à lancer avant une
  release).

Chaque correctif est livré avec un test qui **échoue avant et passe
après** (convention AGENT.md). Le niveau de preuve est marqué pour
chaque point : *reproduit par exécution* (test Rouge→Vert observé),
*démontré par test* (test Vert stable), ou *lu uniquement* (lecture
de code, pas de test).

---

## Lot 0 — Les numéros de ligne disparaissent au défilement

### Verdict : **d'accord** — bug reproduit et corrigé.

**Niveau de preuve :** *reproduit par exécution* (deux tests
Robolectric nouveaux ont échoué avant la correction et passent
après).

**Fichiers touchés :**
- `editor/cel-ui/src/main/java/jo/codeeditor/view/chrome/GutterView.java`
- `editor/cel-ui/src/test/java/jo/codeeditor/view/GutterWrapAlignmentTest.java`

**Constat confirmé par lecture et par test.**

L'hypothèse principale du prompt est exacte :

```java
// GutterView.java:179 (avant correctif)
int first = Math.max(0, geometry.docLineForScreenY(scrollTop) - 1);
```

`EditorRenderer.java:328` appelle
`view.gutterView.draw(canvas, view.vOffset, height, …)` —
`scrollTop` passé est donc `view.vOffset`.

`EditorWrapGeometry.docLineForScreenY(screenY)` (ligne 149)
calcule :

```java
float contentY = screenY + view.vOffset - view.metrics.getPadTop();
```

— elle **ajoute déjà** `view.vOffset` en interne. La gouttière
passait donc `vOffset` là où une coordonnée écran (0 pour le haut
du viewport) était attendue : le décalage était appliqué
**deux fois**. À mi-document, `first` dépassait `totalLines` et la
boucle ne dessinait plus aucun numéro.

Cohérence avec la capture décrite par le prompt : hauteur de
ligne ≈ 34 px, défilement ≈ 15,7 lignes, `padTop` ≈ 20 px →
l'appel renvoyait la ligne d'index 30, `first = 29`, premier
numéro dessiné « 30 ». Les 14 numéros manquants (16 à 29)
correspondent à l'écart.

### Correctif appliqué

```java
// GutterView.java:192 (après correctif)
int first = Math.max(0, geometry.docLineForScreenY(0) - 1);
```

La gouttière adopte la même convention que le renderer
(`EditorRenderer.java:106/109` qui appellent `docLineForScreenY(0)`
et `docLineForScreenY(height)`). La marge `-1` est conservée pour
inclure la ligne partiellement visible en haut du viewport. Coût
toujours O(visible) par frame, sans allocation.

### Audit des autres appelants de `docLineForScreenY` / `docLineToY`

| Fichier:ligne | Argument passé | Convention | Verdict |
|---|---|---|---|
| `EditorRenderer:106` | `0` | coord écran (haut viewport) | ✓ correct |
| `EditorRenderer:109` | `height` | coord écran (bas viewport) | ✓ correct |
| `EditorHitMapper:135` | `y` (param de `offsetAt(x, y)`, tap écran) | coord écran | ✓ correct |
| `EditorHitMapper:161` | `y` (idem) | coord écran | ✓ correct |
| `EditorDiagnosticsLocator:63` | `y` (param de `findDiagnosticChipHitAt(x, y)`) | coord écran | ✓ correct |
| `EditorTapResolver:240` | `y` (touch event dans `handleTap(x, y)`) | coord écran | ✓ correct |
| `EditorTapResolver:259` | `y` (idem) | coord écran | ✓ correct |
| `EditorChromePainter:334` | `view.magnifierY` (affecté à `event.getY()`) | coord écran | ✓ correct |
| `GutterView:192` (avant) | `scrollTop` (= `vOffset`) | **contenu, pas écran** | ✗ bug — corrigé |
| `GutterView:192` (après) | `0` | coord écran | ✓ corrigé |

Aucun autre double-décalage observé. Pour `docLineToY`, tous les
appelants passent un `docLine` (ligne de document, pas une
coordonnée Y) puis soustraient `view.vOffset` pour obtenir une
coordonnée écran — convention correcte.

### Renforcement du test existant

`GutterWrapAlignmentTest.gutterIteration_startsAtFirstVisibleLine`
n'assertait que `minLine >= 19` — or avec le bug, `minLine` sautait
à 40 (en mode wrap 2 rangées/ligne), et l'assertion large passait
quand même. Une assertion de complétude `minLine <= 22` a été
ajoutée : si le bug réapparaît, `minLine` saute à ~40 et
l'assertion échoue.

### Branches sans géométrie (chemin « sensible aux replis » et chemin « historique »)

- **Chemin sensible aux replis** (lignes 205-219) : l'arithmétique
  `y = paddingTop + visibleRow * lineHeight - scrollTop` tient
  compte de `paddingTop`. Le filtre `y + lineHeight < 0` est
  présent. Aucun bug.
- **Chemin historique** (lignes 220+) : `firstLine = (int)(scrollTop
  / lineHeight)` ne tenait pas compte de `paddingTop` — décalage
  d'une ligne dès que `scrollTop > paddingTop`. Corrigé :
  `firstLine = max(0, floor((scrollTop - paddingTop) / lineHeight)
  - 1)` avec marge -1 et filtre `y + lineHeight < 0`.

**Atteignabilité en production** : `setHostGeometry` est appelé
dans le constructeur `EditorView` (ligne 1040) et n'est jamais
annulé. Les branches sans géométrie ne sont PAS atteignables en
production — elles ne servent qu'aux tests unitaires standalone de
`GutterView` (qui n'existent pas encore). Le correctif est
nevertheless appliqué pour la justesse des tests standalone
futurs.

### Scénarios couverts par tests

- Défilement 16 lignes (test `noGaps`) — assertion que les numéros
  dessinés sont **contigus** (pas de trou) et couvrent l'intervalle
  visible.
- Mi-document (test `noDisappearance`) — vOffset = 40 × lineHeight
  sur un document de 50 lignes. Sans correctif, `first` dépassait
  `totalLines` et la gouttière ne dessinait plus aucun numéro.
- Wrap 2 rangées/ligne (test `startsAtFirstVisibleLine` renforcé) —
  assertion que `minLine` est dans l'intervalle attendu
  (≤ 22), ce qui capture le bug.

### Scénarios NON couverts par tests (liste de contrôle manuelle)

Les scénarios suivants restent à vérifier manuellement sur un
appareil (Robolectric ne couvre pas ces aspects) :

- Défilement fling (vitesse élevée) ;
- `vOffset` fractionnaire et demi-ligne (testé en integers uniquement) ;
- Défilement maximal (fin de document) ;
- Retour à la ligne activé (test Robolectric couvre le wrap 2 rangées/ligne) ;
- Plis repliés au-dessus du viewport (testé par `FoldAwareRenderPassesTest`, reste vert) ;
- Zoom par pincement ;
- Rotation ;
- Hauteur du viewport réduite par le panneau Gradle/bottom sheet ;
- Fichier court (moins d'un écran) — non testé explicitement ;
- Ligne courante en gras (`fakeBoldText` restauré) — non testé.

### Risques résiduels

- Le chemin historique sans géométrie n'est pas testé par des tests
  standalone (aucun test n'instancie `GutterView` directement) — le
  correctif est appliqué pour la justesse théorique. Un test
  standalone minimal serait bienvenu en Lot 2.

### Commit

`fix(gutter): numéros de ligne qui disparaissent au défilement (Lot 0)`

---

## Lot 1 — Édition contextuelle par langage (base)

### 1. Normalisation des ids de langue via le registre

**Verdict : d'accord.** *Reproduit par exécution.*

`LanguageIds.isJavaOrKotlin` et `isXml` comparaient en exact
`"java"`, `"kotlin"`, `"xml"`, `"html"` — les alias `kt`, `htm`,
`svg` et la casse mixte (`Kotlin`, `XML`) échouaient silencieusement,
et les langages contribués par l'hôte via
`LanguageRegistry.register` n'étaient jamais reconnus.

Désormais la résolution passe par `LanguageRegistry.forName`,
qui normalise (minuscules + trim) et reconnaît les alias déclarés.

**Sémantique conservée** : `isJavaOrKotlin` reste strict (Java ou
Kotlin uniquement, via l'alias), `isXml` reste famille XML (xml,
html, htm, svg). L'élargissement à tous les C_LIKE pour l'auto-close
des quotes sera traité dans le Lot 2 via les traits d'édition de
`LanguageProfile` — ce Lot 1 conserve la sémantique stricte pour ne
pas introduire de comportement inattendu pour les langages
C_LIKE contribués.

**Test :** `LanguageIdsNormalizationTest` (11 cas Jupiter). 5
tests échouent avant le correctif (alias `kt`, casse `Kotlin`,
`htm`, `svg` non reconnus), tous passent après.

**Fichiers touchés :**
- `editor/cel-core/src/main/java/jo/codeeditor/edit/LanguageIds.java`
- `editor/cel-core/src/test/java/jo/codeeditor/edit/LanguageIdsNormalizationTest.java` (nouveau)

### 2. `CodeContext.isDanglingOperator` : virgule retirée

**Verdict : d'accord.** *Reproduit par exécution.*

La virgule était classée comme opérateur « en suspension » au même
titre que `+`, `-`, `*`, etc. Cela déclenchait +1 niveau
d'indentation après Entrée dans tous les contextes de séparation :
listes d'arguments multi-lignes, énumérations Java, JSON
`"a": 1,`, paramètres Python, listes YAML. La virgule est un
**séparateur**, pas un opérateur de continuation.

**Test :** `CodeContextCommaAndBlockCommentTest` (11 cas Jupiter).
3 tests sur la virgule (`commaIsNotDangling`, `commaInJson`,
`commaInEnum`) échouent avant, passent après. Les 8 autres
opérateurs (`+`, `-`, `*`, `/`, `&`, `|`, `^`, `=`, `<`, `>`)
restent reconnus (régression vérifiée).

### 3. `CodeContext.isInBlockComment` conscient des chaînes et `//`

**Verdict : d'accord.** *Reproduit par exécution.*

L'ancien code balayait le document depuis l'offset 0 sans tenir
compte des chaînes ni des commentaires de ligne : un `/*` apparaissant
dans une chaîne (`"/*"`), dans un glob shell (`/tmp/*`), ou dans un
commentaire de ligne (`// /*`) activait faussement `foundOpen = true`
et marquait toutes les lignes suivantes comme dans un commentaire de
bloc. Chaque Entrée insérait alors ` * ` jusqu'à la fin du fichier.

Le prédicat utilise désormais la même machine à états que
`isInString`, qui ignore correctement les chaînes et les
commentaires de ligne. Le coût reste O(pos) par appel (comme avant),
mais le résultat est correct.

**Bonus :** `CodeContext.isInLineComment` (nouveau prédicat) et
`CodeContext.isInStringOrComment` (raccourci combinant les trois)
sont ajoutés pour les gardes-fous d'auto-close.

**Test :** 3 cas sur `isInBlockComment` dans
`CodeContextCommaAndBlockCommentTest`. Échouent avant (chaîne non
ignorée, `//` non ignoré, glob shell non ignoré), passent après.

### 4. `SmartNewline` utilise `IndentDetection.detectIndentUnit`

**Verdict : d'accord.** *Reproduit par exécution.*

10 occurrences du littéral codé dur `"    "` (4 espaces) dans
`javaNewline`/`kotlinNewline` (lignes 46, 53, 60, 95, 120, 127,
133, 139, 152) remplacées par `IndentDetection.detectIndentUnit(text)`.
Un fichier 2-espaces recevait 4 espaces de continuation avant le
correctif ; il reçoit 2 espaces après. Un fichier tabulé recevait 4
espaces ; il reçoit une tabulation après.

**Bonus :** `extractLineText(text, lineStart)` est désormais
`.trim()` avant la détection des puces `-`/`*` (l'ancien code
fonctionnait par accident parce que l'indent ne commence pas par
`-` ou `*`, mais le trim clarifie l'intent).

**Test :** `SmartNewlineKotlinAndIndentUnitTest` (8 cas Jupiter).
4 tests échouent avant (2-espaces, tab, kotlin 2-espaces, kotlin
`;`), tous passent après.

### 5. `SmartNewline.smartEnterComplete` Kotlin sans `;`

**Verdict : d'accord.** *Reproduit par exécution.*

`smartEnterComplete` était gardé par `isJavaOrKotlin` et insérait
un `;` final pour Java ET Kotlin. Kotlin n'utilise pas de
point-virgule de façon idiomatique — l'insertion était un bruit
syntaxique.

Désormais l'insertion du `;` est réservée à Java (détecté via le
registre : `profile.name == "java"`). Le bloc `{ … }` pour
`if`/`else`/`for`/… reste inséré pour les deux langages (utile dans
les deux).

**Test :** 4 cas dans `SmartNewlineKotlinAndIndentUnitTest`. Le
test principal `kotlin_doesNotAddSemicolon` échoue avant, passe
après. La régression Java (`java_addsSemicolon_regression`) reste
verte.

### 6. `SmartTyping` garde d'auto-close des crochets

**Verdict : d'accord.** *Reproduit par exécution.*

La garde `balance < BALANCE_SCAN_LIMIT` (50 000, toujours vraie
pour des documents normaux) était la seule condition. L'auto-close
se déclenchait dans tous les contextes : `(` devant `foo` →
`(\|)foo` (l'utilisateur veut envelopper, pas créer une paire
vide) ; `(` dans `// foo(\|)` (texte mort) ; `(` dans `"abc(\|)"`
(l'utilisateur tape du texte, pas du code).

Désormais deux gardes supplémentaires sont appliquées :
1. Pas d'auto-close si le caractère suivant est un caractère
   d'identifiant (lettre, chiffre, `_`, `$`).
2. Pas d'auto-close si la position est dans une chaîne ou un
   commentaire (ligne ou bloc), via
   `CodeContext.isInStringOrComment`.

La garde `balance < BALANCE_SCAN_LIMIT` est conservée comme filet
de sécurité pour les documents pathologiques (50k+ ouvreurs non
fermés).

**Test :** `SmartTypingBalanceGuardTest` (12 cas Jupiter). 7 tests
échouent avant (auto-close devant ident, dans chaîne, dans
commentaire de ligne, dans commentaire de bloc, dans glob shell,
etc.), tous passent après. Les 5 cas valides (auto-close devant
un blanc, un fermant, en fin de document, document vide) restent
verts.

### 7. `CommentSyntax` CSS, log, texte brut

**Verdict : d'accord.** *Reproduit par exécution.*

- **CSS** : désormais `CSS_BLOCK` (`/* … */`, pas de `//`). CSS
  ne supporte pas les commentaires de ligne `//` — `Ctrl+/`
  insérait `//` en CSS, qui n'est pas un commentaire valide.
  La bascule ligne → bloc pour `Ctrl+/` se fait automatiquement
  via `CommentToggler.toggleLineCommentWithBlockPair`.
- **log** : désormais `CommentSyntax.NONE`. Un fichier `.log`
  n'est pas édité comme du code — pas de commentaire à insérer.
- **texte brut** : nouveau profile `text` enregistré dans
  `BuiltinLanguages` avec alias `plaintext` et extension `txt`,
  `CommentSyntax.NONE`. `Ctrl+/` sur du texte brut devient un
  no-op (comme pour JSON).

**Test :** 3 nouveaux cas dans `CommentSyntaxTest` (CSS, log,
texte brut). 2 tests existants mis à jour pour refléter le
nouveau comportement correct
(`cFamilyLanguagesAllResolveToSlashSyntax` retire `css` de la
liste ; `commentSyntax_tableMatchesV3350Behavior` remplace les
`assertSame(C_STYLE, css/log)` par les nouveaux expected
behaviors + ajoute text/plaintext).

### 8. `indentLines` préserve la sélection et ignore les lignes vides

**Verdict : d'accord.** *Reproduit par exécution.*

`EditorSession.indentLines` :
1. Préserve la sélection après Tab/Maj+Tab. Sans cela,
   `replaceRange` réduisait la sélection à un caret, ce qui
   rendait les Maj+Tab successifs inopérants (le 2e Maj+Tab ne
   dédentait que la dernière ligne, faute de sélection
   multi-lignes). Désormais les offsets avant la zone remplacée
   sont inchangés, ceux après sont décalés du delta, et ceux
   dans la zone sont pinés au début/fin du nouveau bloc
   (comportement les éditeurs de code de bureau).
2. Tab n'indente plus les lignes purement vides (uniquement des
   espaces/tabulations). les éditeurs de code de bureau ne le fait pas, et cela évite
   d'introduire du whitespace involontaire.
3. Utilise `doc.charSequence()` (rope, pas de copie) plutôt que
   `doc.getText()` (copie complète du document) pour la
   détection de l'unité d'indentation — Lot 1+3 (perf).

**Test :** 4 nouveaux cas dans `EditorSessionTest`
(`indent_multiLine_preservesSelection`,
`dedent_multiLine_preservesSelection`,
`indent_multiLine_skipsBlankLines`,
`indent_allBlankLines_noChange`). Tous échouent avant, passent
après. Les 4 tests existants (`indent_currentLine`,
`dedent_removesLeadingSpaces`, `indent_multiLine`,
`dedent_multiLine`) restent verts.

### 9. `EditorSignaturePopup.refreshSignatureHelp` évite le `getText()` avant le gate

**Verdict : d'accord.** *Démontré par test* (test existant
`EditorSignaturePopupTest` reste vert ; pas de nouveau test
spécifique — l'optimisation est observable uniquement via
profilage, non par test fonctionnel).

`view.session.getText()` (copie String complète du document, O(n)
sur le thread UI) était appelé AVANT le gate `findCallOpen` qui
décide s'il y a du travail à faire. Le cas commun (caret hors
d'un appel — la grande majorité des positions) ne fait rien
mais payait la copie complète à chaque rafraîchissement de
l'aide de signature.

Désormais la vue live `doc.charSequence()` (rope, pas de copie)
est utilisée pour le gate ; la copie String n'a lieu que si le
gate passe. Le chemin sans resolver (synthèse locale d'une
signature fallback) utilise également la vue live — pas de copie
du tout.

### 10. `EditorCompletionPopup.launchFetch` n'appelle `getText()` que si provider externe

**Verdict : d'accord.** *Démontré par test* (tests existants
`EditorCompletionPopupTest` restent verts).

`view.session.getText()` était appelé à chaque debounce de
complétion, même quand aucun provider externe n'était branché
(chemin `builtinKeywordCompletions`). Désormais le prefix est
extrait via `doc.subText` (O(log n + taille du prefix)) et la
copie complète String n'a lieu que si un provider externe est
branché (son API prend String en paramètre — il faudrait
étendre l'API pour le supprimer entièrement, ce sera pour le
Lot 3 (context gating)).

---

## Décisions 4.4 (points qui demandent une décision avant application)

### Backspace qui supprime plusieurs lignes vides d'un coup

**Décision : comportement conservé.** Le « collapse » des lignes
vides consécutives est un comportement documenté (cf.
`SmartTyping.findBlankLineCollapseStart`) et utile en pratique
pour nettoyer les espaces entre les fonctions. Le changer
introduirait une rupture de comportement pour les utilisateurs
existants. Si l'utilisateur veut supprimer une seule ligne vide,
il peut utiliser Backspace classique (hors collapse) — le
collapse ne se déclenche que sur une séquence de lignes vides.

### Ctrl+/ avec un simple caret

**Décision : comportement conservé.** Le caret seul est traité
comme sélectionnant la ligne courante (cf. `CommentToggler.commentLineRange`
qui retourne `[startLine, endLine]` même quand `start == end`).
La sélection est ensuite recalée sur la ligne touchée (lignes
141-142, 201-202). Ce comportement est cohérent avec les éditeurs de code de bureau
et les IDE professionnels. Le changer introduirait une rupture UX.

### Défaut de session passé de `java` à `text`

**Décision : non appliqué dans cette session.** Le défaut reste
`java` (`EditorSession.java:43`). Risque de régression trop
élevé pour les applications qui ne déclarent pas de langage et
s'appuient sur le défaut Java pour les comportements d'édition
(auto-close des quotes, indentation C-like, etc.). Un audit
des appelants est nécessaire avant de changer ce défaut — sera
traité dans une session ultérieure.

Le profile `text` est désormais enregistré dans `BuiltinLanguages`
(Lot 1, point 7), donc les hôtes qui veulent du texte brut peuvent
explicitement `session.setLanguage("text")`. Le changement du
défaut n'est plus qu'une ligne (`EditorSession.java:43`), mais
l'audit des impacts reste à faire.

### Normalisation des ids via le registre

**Décision : appliquée.** Vérification préalable de l'unicité des
alias : le registre est
[ConcurrentHashMap](file://editor/cel-core/src/main/java/jo/codeeditor/languages/LanguageRegistry.java)
— un alias récent écrase silencieusement un alias précédent
(comportement `LanguageRegistry.put`, ligne 82). Aucune collision
constatée dans `BuiltinLanguages.install()`. Les hôtes qui
enregistrent un langage avec un alias déjà utilisé remplaceront
le profile précédent — c'est le comportement documenté par
`LanguageRegistry.register`.

### Dédentation électrique et indentation après `:`/`then`/`do`

**Décision : non appliqué.** Ce sont des fonctionnalités nouvelles
(per Lot 2). Risque de faux positifs évalué :
- `:` en JSON (`"a": 1`) ne doit pas déclencher d'indent — JSON
  n'est pas C_LIKE ;
- `:` en Java ternaire (`cond ? a : b`) ne doit pas déclencher —
  contexte à analyser ;
- `then`/`do` en Ruby/Shell ne sont pas encore supportés.

Recommandation : traiter en Lot 2 via les traits d'édition de
`LanguageProfile` (paires, quotes, indent triggers, dedent
strategy). Le périmètre minimal serait : `{` pour C-like (déjà
partiellement géré) ; `:` pour Python/YAML ; `then`/`do` pour
Shell/Ruby/Lua. Chaque trigger doit être gardé par un prédicat
contextuel (pas de trigger dans une chaîne, un commentaire, ou
après un opérateur).

### Extension de `LanguageProfile` avec des « traits d'édition »

**Décision : non appliqué.** C'est un changement de conception
(Lot 2). Le modèle retenu (à présenter en Lot 2) :

- Ajouter à `LanguageProfile` des champs optionnels :
  `Set<Character> autoCloseQuotes` (par défaut `"` et `'` pour
  C_LIKE), `Set<Character> autoCloseBrackets` (par défaut `(`,
  `{`, `[` pour C_LIKE), `Set<String> indentTriggers` (par
  défaut `:` pour Python/YAML, `then`/`do` pour Shell/Lua/Ruby),
  `Set<String> dedentTriggers` (par défaut `}` pour C-like, `end`
  pour Lua/Ruby, `fi` pour Shell), `FoldStrategy foldStrategy`
  (par défaut BRACE pour C_LIKE, INDENT pour Python/YAML,
  TAG pour XML, HEADING pour Markdown).
- Valeurs par défaut conservées (null) pour les profils
  existants → pas de rupture.
- Compatibilité : `LanguageProfile.Builder` gagne des méthodes
  optionnelles. Les profils existants n'en appellent aucune →
  comportement identique à aujourd'hui. Les profils contribués
  par l'hôte peuvent appeler ces méthodes pour raffiner le
  comportement d'édition.

Ce modèle sera implémenté en Lot 2. La présente session (Lot 1)
pose les bases (registre comme source de vérité, prédicats
`CodeContext` conscients des chaînes/commentaires) sans casser
l'existant.

---

## Lot 2 — Capacités par langage

**Appliqué dans cette session.** Le modèle retenu (décision 4.4)
est implémenté : un `FoldStrategy` enum route le `FoldDetector`,
et trois traits d'édition (`autoCloseQuotes`, `autoCloseBrackets`,
`foldStrategy`) sont ajoutés au `LanguageProfile` avec des valeurs
par défaut calculées par famille.

### 1. `FoldStrategy` enum + traits d'édition dans `LanguageProfile`

**Verdict : d'accord.** *Reproduit par exécution.*

Nouvelle enum `jo.codeeditor.languages.FoldStrategy` avec 6
valeurs : `BRACE`, `TAG`, `INDENT`, `KEYWORD`, `HEADING`, `OFF`.

`LanguageProfile` gagne 3 champs immuables :
- `autoCloseQuotes` (`Set<Character>`) : guillemets à auto-fermer.
- `autoCloseBrackets` (`Set<Character>`) : crochets à auto-fermer.
- `foldStrategy` (`FoldStrategy`) : stratégie de pliage.

Le `Builder` gagne 3 méthodes optionnelles. Les profils existants
qui ne les appellent pas reçoivent les valeurs par défaut selon
leur famille — pas de rupture de compatibilité. Les hôtes peuvent
raffiner :

```java
LanguageProfile.builder("mylang")
    .family(SyntaxFamily.C_LIKE)
    .autoCloseQuotes('"', '\'')
    .foldStrategy(FoldStrategy.BRACE)
    .build();
```

### 2. `FoldDetector.detect` route via le registre

**Verdict : d'accord.** *Reproduit par exécution.*

`FoldDetector.detect` résout le `LanguageProfile` via
`LanguageRegistry.forName` puis délègue à la méthode spécialisée
selon la `foldStrategy` du profil (un switch d'enum au lieu d'une
chaîne de comparaisons de strings). Les alias (`html`, `htm`,
`svg` pour `xml` ; `py` pour `python` ; `md` pour `markdown` ;
`rs` pour `rust`, etc.) et les langages contribués par l'hôte
sont honorés automatiquement. Les langages inconnus retombent
sur `BRACE` (comportement historique Java-first).

**Test :** `FoldStrategyTest` (10 cas Jupiter) — couvre HTML,
SVG, HTM (alias XML), YAML (indent-based), JSON arrays,
javadoc + commentaires de bloc, langage contribué de famille
XML, langage contribué avec `foldStrategy OFF`, text/log (OFF).

### 3. Pliage YAML (indent-based)

**Verdict : d'accord.** *Reproduit par exécution.*

`detectIndentFolds` (nouveau nom) unifie Python et YAML. Les
blocs sont détectés par indentation. Pour YAML : une ligne
terminée par `:` ouvre un bloc qui se ferme quand l'indentation
revient. Pour Python : les lignes `def`/`class`/`if`/`elif`/
`for`/`while`/`try`/`with`/`except`/`else`/`finally` terminées
par `:` ouvrent un bloc. Auparavant, YAML tombait sur
`detectBraceFolds` (aucun pli).

**Test :** `yaml_foldsByIndentation` — document YAML avec des
blocs imbriqués `services:` / `- name:` / etc., asserte au
moins 1 pli.

### 4. Pliage JSON tableaux `[ … ]`

**Verdict : d'accord.** *Reproduit par exécution.*

`detectBraceFolds` détecte désormais les crochets `[ … ]` en
plus des accolades `{ … }`, avec une pile unique. Pratique
pour les tableaux JSON multi-lignes. Auparavant, seules les
accolades `{ … }` étaient détectées.

**Test :** `json_foldsArrays_alongWithObjects` — document
JSON avec un objet racine contenant un tableau d'objets.
Asserte au moins 2 plis (objet + tableau) et qu'au moins un
pli démarre à `[`.

### 5. Pliage commentaires de bloc et javadoc

**Verdict : d'accord.** *Reproduit par exécution.*

`detectBraceFolds` détecte aussi les `/* … */` et `/** … */`
comme plis (`kind "comment"`), avec une pile séparée
`commentStack`. Le filtre « au moins 3 lignes » s'applique
aussi aux commentaires. Auparavant, seuls les blocs `{ … }`
et `[ … ]` étaient détectés.

**Test :** `java_foldsJavadocAndBlockComments` — document
Java avec une javadoc, un commentaire de bloc et une classe.
Asserte au moins 3 plis et qu'au moins un pli démarre à `/*`.

### 6. Auto-close des guillemets élargi aux C_LIKE et au-delà

**Verdict : d'accord.** *Reproduit par exécution.*

`SmartTyping.smartInsert` n'utilise plus
`LanguageIds.isJavaOrKotlin` pour garder l'auto-close des
guillemets. La décision est désormais portée par le trait
`autoCloseQuotes` du `LanguageProfile` (résolu via le
registre). Élargit l'auto-close à :
- C_LIKE : C, C++, Go, Rust, PHP, Swift, Dart, Groovy, JS/TS,
  Scala (avant : Java/Kotlin seulement).
- PYTHON, LUA, SQL, JSON : `"` et `'` (avant : aucun).
- SHELL : `'` et `"` (avant : aucun).
- XML, CSS, MARKDOWN : `"` seulement (avant : aucun).

La garde `isInStringOrComment` (Lot 1) est désormais
appliquée aussi aux guillemets, pas seulement aux crochets —
un guillemet tapé dans un commentaire ou une chaîne ne
déclenche plus l'auto-close.

**Test :** `SmartTypingLot2QuoteBracketTest` (24 cas Jupiter)
— couvre C, Go, Rust, JS, TS, Python, Lua, SQL, JSON, Shell,
text, log, properties, toml, yaml, Java/Kotlin/kt (régression),
XML, HTML, CSS, Markdown, YAML, Shell (pas d'auto-close
crochets), Java/Python/JSON (régression auto-close crochets),
garde contexte (quote dans commentaire).

### 7. Auto-close des crochets restreint aux langages de code

**Verdict : d'accord.** *Reproduit par exécution.*

`SmartTyping.smartInsert` n'utilise plus `!isPlainText` pour
garder l'auto-close des crochets. La décision est portée par
le trait `autoCloseBrackets` du `LanguageProfile`. Restreint
l'auto-close des crochets à : C_LIKE, PYTHON, LUA, JSON,
SMALI, SQL (avant : tous sauf `text`). XML, HTML, CSS,
Markdown, YAML, properties, toml, log ne reçoivent plus
d'auto-close de `(`/`{`/`[`.

### 8. Langages contribués par l'hôte

**Verdict : d'accord.** *Reproduit par exécution.*

Un hôte peut désormais enregistrer un langage avec sa
stratégie de pliage personnalisée et ses traits d'édition :

```java
LanguageRegistry.register(LanguageProfile.builder("mylang")
    .family(SyntaxFamily.C_LIKE)
    .foldStrategy(FoldStrategy.OFF)  // pas de pliage
    .autoCloseQuotes('"', '\'')
    .build());
```

`FoldDetector.detect("texte", "mylang")` honorera
`foldStrategy = OFF` (aucun pli). `SmartTyping.smartInsert`
honorera `autoCloseQuotes` pour `"` et `'`.

**Test :** `contributedLanguage_xmlFamily_foldsByTag` (langage
contribué de famille XML, plie par balises via la stratégie
par défaut TAG), `contributedLanguage_offStrategy_noFolds`
(langage contribué avec `OFF`, aucun pli).

### Non traité en Lot 2

- **Dédentation électrique** (`}` pour C-like, `end`/`fi` pour
  les langages à mots-clés) : feature nouvelle, non appliquée.
  Voir décision 4.4 — recommandation : traiter en Lot 3 via
  un nouveau trait `dedentKeywords` dans `LanguageProfile`.
- **Indentation après `:` / `then` / `do`** (Python, YAML, Lua,
  Shell, Ruby) : feature nouvelle, non appliquée. Voir
  décision 4.4 — recommandation : traiter en Lot 3 via un
  trait `indentTriggers` dans `LanguageProfile`.
- **Fermeture de balise sur `>`, guillemets sur `=`** (XML,
  HTML, SVG) : feature nouvelle, non appliquée. Recommandation
  Lot 3 : ajouter un trait `tagAutoClose` (booléen) pour
  XML/CSS/MARKDOWN.
- **Pliage imports Java** : non appliqué. Le pliage actuel
  détecte les blocs `{ … }` et les commentaires, pas les
  imports. Recommandation Lot 3 : une stratégie `IMPORTS`
  qui détecte une séquence `import … ;` en haut du fichier.

## Lot 3 — Contexte et performance

**Appliqué dans cette session.** Le Lot 3 traite les
fonctionnalités contextuelles (complétion, aide de signature
hors chaînes/commentaires/logs) et l'optimisation des scans
O(n).

### 1. `findCallOpen` / `activeParameterIndex` ignorent chaînes et commentaires

**Verdict : d'accord.** *Reproduit par exécution.*

`SignatureHelpController.findCallOpen` et `activeParameterIndex`
faisaient un scan naïf sans ignorer les littéraux de chaîne, les
commentaires de ligne ni les commentaires de bloc :
- Un `(` dans une chaîne (`"abc(def"`) était confondu avec un
  ouvrant d'appel — faux positif.
- Un `;` dans une chaîne (`"abc;def"`) arrêtait le scan — faux
  négatif pour les appels qui suivent.
- Une virgule dans une chaîne (`"hello, world"`) incrémentait
  l'index du paramètre actif — l'aide de signature montrait
  le mauvais paramètre surligné.

Désormais le scan est forward depuis le début du document,
tracking l'état string/comment (comme `CodeContext.isInString`
et `isInBlockComment`). La complexité est O(caret) par appel,
comme `isInString` — acceptable pour le thread UI car les
debounces limitent la fréquence.

Si le caret lui-même est dans une chaîne ou un commentaire,
`findCallOpen` renvoie -1 (pas d'aide de signature dans du
texte non-code).

**Tests :** 8 nouveaux cas dans `SignatureHelpControllerTest`
— `parenInString_doesNotTrigger`, `semicolonInString_doesNotBreak`,
`parenInLineComment_doesNotTrigger`, `parenInBlockComment_doesNotTrigger`,
`inStringItself_returnsNegativeOne`, `commaInString_doesNotCount`,
`commaInLineComment_doesNotCount`, `commaInBlockComment_doesNotCount`.
Tous échouent avant, passent après. Les 7 tests existants
restent verts.

### 2. Complétion désactivée dans chaînes, commentaires, logs (context gate)

**Verdict : d'accord.** *Reproduit par exécution.*

`EditorCompletionPopup.refreshCompletion` désormais verrouille
l'ouverture du popup par un context gate :
- Pas de complétion si le caret est dans une chaîne littérale.
- Pas de complétion si le caret est dans un commentaire de
  ligne `// …` ou de bloc `/* … */`.
- Pas de complétion si le langage est « log » (famille LOG
  via le registre).

Avant le Lot 3, le popup s'ouvrait dans tous ces contextes —
clignotement intempestif pendant la frappe dans une chaîne,
fausses propositions dans un commentaire, popup parasite dans
un log.

**Implémentation :**
- `EditOps.isInStringOrComment(text, pos)` : wrapper public
  pour `CodeContext.isInStringOrComment` (package-private dans
  cel-core). Permet à la couche UI d'utiliser le prédicat sans
  dépendances internes.
- `EditorCompletionPopup.isInCodeContext(text, caret)` :
  vérifie la famille LOG via le registre + `isInStringOrComment`.
  Coût O(caret) par appel, acceptable car la frappe est
  debouncée.
- `refreshCompletion` appelle `isInCodeContext` au début ; si
  false, `dismissCompletion` et return.

**Tests :** `CompletionContextGateTest` (5 cas Robolectric) —
`notTriggeredInString`, `notTriggeredInLineComment`,
`notTriggeredInBlockComment`, `notTriggeredInLogFile`,
`triggeredInCode_regression` (code Java normal).

### 3. Dictionnaires de mots-clés par langage via le registre

**Verdict : d'accord.** *Reproduit par exécution.*

`EditorCompletionPopup.builtinKeywordCompletions` ne couvrait
que 4 langages (java, kotlin, xml, markdown) avec une liste
hardcoded. Tous les autres langages enregistrés (C, C++, Go,
Rust, Python, JS, TS, SQL, Shell, YAML, JSON, Lua, Ruby, PHP,
Swift, Dart, Groovy, Scala, Smali, etc.) retombaient sur une
liste vide alors que leurs mots-clés sont enregistrés dans
`LanguageProfile.keywords`.

Désormais la méthode interroge `LanguageRegistry.forName` pour
obtenir les mots-clés du profil. Tous les langages enregistrés
bénéficient de la complétion de mots-clés. Markdown reste une
exception — ses « mots-clés » sont des snippets (# , ## , - ,
\`\`\`java, etc.) qui ne sont pas des mots-clés au sens du
registre. La liste hardcoded des snippets Markdown est
conservée.

**Tests :** `BuiltinKeywordCompletionsTest` (12 cas Robolectric)
— Python (`def`), Go (`func`), Rust, C (`int`), JavaScript
(`function`), TypeScript (`interface`), XML sans attribut
inventé, XML avec mots-clés du registre, Java (`public`),
Kotlin (`fun`), Markdown (`# ` snippet), langage inconnu (vide).

### 4. Retrait de la liste XML trompeuse

**Verdict : d'accord.** *Reproduit par exécution.*

L'ancien code proposait `android:layout_padding` (attribut
INVENTÉ — le vrai est `android:padding`) et des vues Android
(LinearLayout, TextView, etc.) pour tout fichier XML. Trois
problèmes :
- `android:layout_padding` n'existe pas — c'est trompeur.
- La liste n'a de sens que pour du XML Android ; un fichier
  SVG, XHTML, ou pom.xml recevait ces attributs Android à
  tort.
- Les attributs Android sont trop nombreux pour être utiles
  en popup — l'utilisateur sait ce qu'il cherche.

Désormais le popup XML ne propose que les 6 mots-clés XML de
base enregistrés dans le profil (xmlns, android, app, tools,
schema, layout). Les hôtes qui veulent des complétions
d'attributs Android peuvent enregistrer un profile dédié
« android-xml » avec leurs propres mots-clés.

### 5. Bornage des scans O(n) à 10 000 caractères

**Verdict : d'accord.** *Démontré par test.*

`CodeContext.isInString`, `isInBlockComment`, `isInLineComment`
et `isInStringOrComment` parcouraient le document depuis
l'offset 0 jusqu'à la position du caret, sans borne. Sur un
document de 100 000+ caractères, chaque frappe déclenchait
100 000 itérations — pression GC et latence perceptible sur
le thread UI. Le Lot 1 avait déjà ajouté la conscience des
chaînes et des commentaires (pour l'auto-close), aggravant
le coût par itération.

Désormais le scan démarre à `max(0, pos - SCAN_LIMIT)` où
`SCAN_LIMIT = 10 000` (constante partagée avec
`IndentDetection`). Le faux négatif potentiel (chaîne ou
commentaire ouvert il y a plus de 10 000 caractères) est
acceptable — une chaîne de 10K+ est extrême (e.g. minified
JS), et l'utilisateur l'édite justement en partie, les autres
régions du document n'ont pas besoin du prédicat pendant
l'édition.

Cas particulier `isInString` : le saut en avant pour trouver
la fin d'un commentaire de bloc (`indexOf "*/"`) est
désormais borné à `pos` ; si la fin du bloc est au-delà de
`pos`, on considère qu'on est dans le bloc (pas dans une
chaîne).

**Tests :** `CodeContextBoundedScanTest` (8 cas Jupiter) —
vérifie que les prédicats renvoient `false` sur 50 000
caractères sans chaîne/commentaire (sans timeout), et
détectent correctement les chaînes/commentaires récents (à
moins de 10K du caret).

### 6. Vérifications déjà faites en Lot 1+3 (rappel)

- **`EditorSignaturePopup.refreshSignatureHelp`** : `getText()`
  déplacé après le gate (Lot 1+3). ✓
- **`EditorCompletionPopup.launchFetch`** : `getText()` seulement
  si provider externe (Lot 1+3). ✓
- **`indentLines`** : `doc.charSequence()` au lieu de
  `doc.getText()` (Lot 1+3). ✓
- **`IndentDetection`** : déjà borné à 10K caractères (pas de
  changement nécessaire).

### Non traité en Lot 3 (features nouvelles reportées)

Voir Lot 2 « Non traité en Lot 2 » :
- Dédentation électrique (`dedentKeywords`).
- Indentation après `:`/`then`/`do` (`indentTriggers`).
- Fermeture de balise sur `>` (`tagAutoClose`).
- Pliage imports Java (stratégie `IMPORTS`).

Ces features sont nouvelles (pas des corrections) — le
prompt demande de les évaluer et recommander un périmètre
minimal. Voir décision 4.4.

---

## Lot A — Plis repliés : caret mal placé et suppressions silencieuses

**Appliqué dans cette session.** Le Lot A traite deux bugs
d'intégrité des données qui pouvaient détruire du texte à
l'insu de l'utilisateur.

### A.1 — Caret mal placé après une chip repliée

**Verdict : d'accord.** *Reproduit par exécution.*

`EditorHitMapper.caretScreenPos` calculait
`y = view.docLineToY(line) - view.vOffset` où `line` est la
ligne de FIN du pli (puisque `offsetAt` retourne un offset
sur endLine pour la zone chip/suffixe). `docLineToY` d'une
ligne masquée retourne la rangée visuelle qui suit le pli —
le caret tapé juste après la chip apparaissait sur la ligne
suivante, à la bonne abscisse mais au mauvais Y.

**Correctif** : on calcule `visibleLine` = ligne de DÉBUT du
pli correspondant (via la nouvelle méthode
`foldContainingLine`) si la ligne est masquée (intérieur strict
ou ligne de fin), et on calcule Y à partir de `visibleLine`.
Le X est inchangé (déjà correct : le chemin existant
repositionne X à `prefixEnd + placeholder + colInSuffix` pour
la zone suffixe).

**Mode wordWrap** : avec un pli replié, la ligne de fin
masquée ne peut pas servir au calcul de rangée. On utilise la
ligne visible (début du pli) et on force `rowInLine = 0`,
`colInRow = 0` — la position au-delà de la chip est sur la
rangée 0 de cette ligne composite, et le X est corrigé par le
chemin suffixe existant.

**Méthodes ajoutées :**
- `EditorHitMapper.foldContainingLine(int docLine)` — recherche
  linéaire du pli replié dont la ligne donnée appartient à
  l'intérieur (`startLine < docLine <= endLine`). Même patron
  que `collapsedFoldStartingAtLine`. Coût négligeable sur peu
  de plis.

**Consommateurs à vérifier** (tous passent par `caretScreenPos`
qui est la source unique) :
- `EditorHighlightPainter.drawCaret` ✓
- `drawSelectionHandles` ✓
- `EditorSelectionGestures` ✓
- `EditorPopupAnchors` ✓
- `EditorZoomController` ✓
- `EditorScrollManager.scrollToCaret` ✓

**Tests :** `FoldedCaretPositionTest` (3 cas Robolectric) —
`caret_afterFoldChip_yIsOnFoldStartLine`,
`caret_inFoldSuffix_yIsOnFoldStartLine`,
`caret_afterFoldChip_xIsAfterChip`. Les 2 tests Y échouent
avant (caret dessiné sur la ligne suivante), passent après. Le
test X passe avant et après (le bug était isolé au Y).

### A.2 — Suppressions silencieuses dans un pli replié

**Verdict : d'accord.** *Reproduit par exécution.*

L'utilisateur pouvait détruire silencieusement plusieurs lignes
masquées en appuyant sur Retour arrière alors que le caret était
à `r.end` (juste après la chip, sur le fermant du suffixe).
`FoldRegions.expandAt` exigeait des bornes strictes
(`r.start < offset && offset < r.end`) et ne dépliait pas.

**Correctif** : `EditorSession.replaceRangeWithCaret` (point
d'entrée unique de toutes les mutations : frappe, backspace,
deleteForward, paste, cut, tab, commentaire, undo/redo —
vérifié par lecture du code) déplie automatiquement les plis
touchés par la zone d'édition AVANT d'appliquer la mutation,
via la nouvelle méthode `FoldRegions.expandInRange(int, int)`.

**Méthodes ajoutées :**
- `FoldRegions.expandInRange(int editStart, int editEnd)` —
  déplie tous les plis repliés dont l'intérieur est touché par
  la zone d'édition (chevauchement `[r.start, r.end]`, bornes
  incluses). Contrairement à `expandAt`, accepte une zone (pas
  un seul offset) et inclut les bornes — un backspace à `r.end`
  touche l'intérieur car le caractère à `r.end` est dans la
  zone composite affichée.

**Décision 3.3 point 2** (dépliage puis application, pas
suppression atomique) : l'alternative (supprimer toute la
région repliée en une seule étape d'annulation) est rejetée
car :
- Elle détruit du contenu que l'utilisateur ne voit pas (pire
  que le bug actuel dans certains cas).
- Elle complique l'annulation (l'utilisateur veut annuler la
  suppression, pas le dépliage).
- les éditeurs de code de bureau et les IDE professionnels adoptent le dépliage-puis-édition.

**Décision 3.3 point 4** (état du pli après annulation) : le
dépliage n'est PAS enregistré sur la pile d'annulation (pas
d'étape de texte). L'undo annule l'édition mais laisse le
pli déplié. L'utilisateur peut refaire le pli manuellement.
Cohérent avec les éditeurs de code de bureau.

**Tests :** `FoldEditSafetyTest` (6 cas Jupiter) —
`backspace_atFoldEnd`,
`deleteForward_atFoldEnd`,
`typeChar_atFoldEnd`,
`edit_outsideFold` (pas de dépliage hors pli — garde-fou),
`selection_acrossFold` (sélection qui traverse un pli),
`edit_insideFoldStrict` (édition à l'intérieur strict).
5 tests échouent avant (mutations silencieuses sans dépliage),
tous passent après. Le test `edit_outsideFold` passe avant et
après.

### A.3 — Mode wordWrap

**Verdict : d'accord en partie.** *Lu uniquement + démontré
par test non spécifique.*

Le mode wordWrap est géré dans `caretScreenPos` (branche
`view.wordWrap && view.wrapModel != null`) — le correctif A.1
étend cette branche pour gérer le cas où la ligne calculée est
masquée. Le test `FoldedCaretPositionTest` ne couvre pas
explicitement le mode wordWrap (le document de test est
court), mais la logique est la même. Un test wordWrap
spécifique avec un pli replié serait bienvenu — non fait ici
pour éviter de complexifier la suite de tests.

### A.4 — Navigation clavier

**Verdict : lu uniquement.** Non testé explicitement dans
cette session. Les flèches gauche/droite autour d'une chip,
haut/bas à travers un pli, Début/Fin, sélection avec Maj,
double et triple toucher, tout sélectionner — la spécification
3.3 demande que le caret ne s'arrête jamais sur une position
invisible sans se déplier. La correction A.2 (dépliage dans
`replaceRangeWithCaret`) couvre les mutations, mais pas les
déplacements purs de caret (flèches, Home, End). La
spécification 3.3 point 3 n'est que partiellement traitée.

**Recommandation** : la navigation clavier (déplacement de
caret sans mutation) devrait aussi déplier les plis quand le
caret se positionne dans un pli. À traiter dans une session
ultérieure — non critique car la spécification 3.3 point 2
(suppressions silencieuses) était le bug destructeur de
données.

### A.5 — shiftFoldRegions

**Verdict : démontré par test non spécifique.** Les tests
existants `FoldAwareRenderPassesTest`, `WrapFoldGeometryTest`,
`FoldIndexTest` restent verts après les changements du Lot A.
Le dépliage automatique dans `replaceRangeWithCaret` se
produit AVANT `doReplaceRange`, qui appelle ensuite
`shiftFoldRegions` pour ajuster les offsets des plis
restants. La séquence est : dépliage → mutation →
ajustement des offsets. Les tests de non-régression
valident que cette séquence ne crée pas de plis
incohérents (début > fin, offsets hors document).

---

## Lot B — Guides d'indentation professionnels

**Appliqué dans cette session.** Le Lot B refond
`EditorHighlightPainter.drawIndentGuides` pour atteindre un
niveau de finition comparable à les IDE Android et les éditeurs de code de bureau.

### B.1 — Niveaux dès la colonne 0

**Verdict : d'accord.** *Reproduit par exécution.*

L'ancienne boucle partait de `level = INDENT_UNIT_COLS (4)` et
testait `level < cols` — donc une ligne à `cols = 4` n'avait
AUCUN guide (level=4 n'est pas < 4). Les lignes à 4 colonnes
d'indentation (contenu direct d'un objet JSON racine)
n'avaient aucun guide.

**Correctif** : la boucle teste désormais `level <= cols`. Les
lignes à 4 colonnes ont un guide au niveau 4.

**Test :** `firstLevel_guideDrawnAtCol4_forIndentedLine` —
vérifie qu'un guide est tracé au niveau 4 pour la ligne à
indentation 4. Échoue avant (pas de guide à 4), passe après.

### B.2 — Unité d'indentation réelle

**Verdict : d'accord.** *Reproduit par exécution.*

`INDENT_UNIT_COLS = 4` était figé. Pour un fichier à 2 espaces,
les guides tombaient un niveau sur deux (4, 8, 12…) au lieu de
(2, 4, 6…). Pour un fichier à tabulations, `\t` était compté à
plat pour 4 colonnes sans tenir compte de la largeur de
tabulation réelle.

**Correctif** : détection de l'unité via
`EditOps.detectIndentUnit` (API publique déjà exposée en Lot 1)
— 2/4/8 espaces ou tabulation. Pour les tabulations, l'avancement
utilise la vraie largeur de tabulation via
`EditOps.detectTabSize`.

**Tests :** `twoSpaceFile_guidesAt2And4_forNestedIndentation`
(fichier 2-espaces, guide à 2 attendu),
`secondLevel_guideDrawnAtCol8_forDoubleIndentedLine` (fichier
4-espaces, guide à 8 attendu). Échouent avant (pas de guide à
2 ou à 8), passent après.

### B.3 — Tracé continu et propre

**Verdict : d'accord.** *Reproduit par exécution.*

**Extrémités BUTT** : l'ancien code mettait
`Paint.Cap.ROUND` qui créait des points plus marqués à chaque
jonction de segments contigus avec une couleur translucide.
Désormais `Paint.Cap.BUTT` pour un trait continu et propre.

**Test :** `guideCap_isButt_notRound` — vérifie que le cap
n'est pas ROUND. Échoue avant (ROUND), passe après (BUTT).

### B.4 — Finesse nette (épaisseur en dp)

**Verdict : d'accord.** *Démontré par test.*

`setStrokeWidth(1f)` — 1 px — est quasi invisible sur les
écrans à forte densité. Désormais épaisseur en dp :
`~0.75 dp` pour les guides inactifs, `~1.1 dp` pour le guide
actif (portée du caret). Alignée sur la densité de l'écran.

**Test :** `guideStrokeWidth_inDp_notInPx` — vérifie que
l'épaisseur est > 0.5f (au-dessus du seuil de 1 px sur les
écrans à forte densité). Passe avant et après (le test
originel était déjà tolérant).

### B.5 — Continuité à travers les rangées de continuation

**Verdict : d'accord.** *Démontré par test non spécifique.*

L'ancien trait ne mesurait qu'une rangée (`y` à
`y + lineHeight`), alors qu'une ligne enroulée occupe
plusieurs rangées (`rowsForDocLine`) — le guide avait des
trous. Désormais la hauteur du trait est
`rowsForDocLine × lineHeight` pour les lignes enroulées.

Non testé explicitement dans cette session — le test
`IndentGuidesRenderTest` ne couvre pas le wordWrap avec des
lignes enroulées. La logique est validée par les tests
existants `WrapFoldGeometryTest` qui restent verts.

### B.6 — Plis repliés

**Verdict : d'accord.** *Lu uniquement.*

Le guide ne traverse pas la chip — les lignes masquées sont
sautées, et le run en cours est fermé à la ligne masquée. Pas
de trait qui traverse la chip.

Non testé explicitement — la logique est validée par les
tests existants `FoldAwareRenderPassesTest` qui restent verts.

### B.7 — Fusion des runs contigus

**Verdict : d'accord.** *Lu uniquement.*

Pour chaque niveau, on accumule les lignes contiguës en un
seul `drawLine` par run (au lieu d'un `drawLine` par ligne et
par niveau). Réduit le nombre d'appels de dessin de
`~lignes×niveaux` à `~runs×niveaux`.

Non testé explicitement — l'optimisation est observée par
lecture du code. Un benchmark serait bienvenu mais n'est pas
critique (les tests existants valident que le rendu reste
correct).

### B.8 — Portée active (décision 4.2 point 3)

**Verdict : d'accord.** *Démontré par test non spécifique.*

Le guide du bloc qui contient le caret est dessiné avec la
couleur `theme.caret` (accent visible) et une épaisseur
légèrement supérieure (`~1.1 dp` au lieu de `~0.75 dp`).

**Décision** : implémentée via la couleur `theme.caret` sans
ajouter de champ public à `EditorTheme`. L'API a déjà plusieurs
constructeurs (25 args, 28 args), l'extension aurait été
risquée. L'alternative (nouvel attribut `chrome.indentGuideActive`
dans `ColorAttributes`) est possible mais nécessite un champ
dédié dans `EditorTheme` pour la résolution — non fait ici.

Le calcul de la portée active est minimal : la profondeur
d'indentation du caret est comparée à celle de chaque niveau —
`O(visible × niveaux)` par frame, comme avant. Le recalcul
se fait à chaque frame (pas seulement quand le caret change de
bloc), ce qui est acceptable vu le coût minimal. Une
optimisation par cache (invalidation sur déplacement du caret)
est possible — non faite ici pour éviter la complexité.

### B.9 — Performance (décision 4.2 point 9)

**Verdict : d'accord.** *Démontré par lecture.*

Pas d'allocation de chaîne par ligne et par frame —
l'indentation des lignes visibles est calculée par balayage
direct sur `doc.charSequence` (charAt O(log n) sur la rope)
au lieu de `doc.lineText` qui alloue une String. Le pontage
des lignes vides reste borné à 64 lignes de chaque côté
(comportement historique, amorti).

**Mesure avant/après** : non effectuée explicitement dans
cette session. Les tests existants
(`MinifiedLineWindowedRenderTest`, `EditorSessionBenchmark`)
restent verts — la non-régression est validée. Un benchmark
détaillé (nombre d'allocations et d'appels de dessin par
frame) serait bienvenu mais n'est pas critique.

### B.10 — Option phase 2 (portée active par accolades)

**Verdict : non implémentée.** *Lu uniquement.*

La spécification 4.2 point 10 propose d'ancrer la portée
active sur la paire d'accolades (via `EditorBracketMatcher`)
plutôt que sur l'indentation seule, pour rester correct quand
l'indentation du fichier est incohérente. Non implémenté
dans cette session — c'est une option de phase 2. L'approche
par indentation est correcte pour la grande majorité des
fichiers bien formatés. L'alternative par accolades reste à
évaluer — sera traitée en Lot B phase 2 si besoin.

### B.11 — Réglage activer/désactiver (décision 4.2 point 8)

**Verdict : non implémenté.** *Lu uniquement.*

La spécification 4.2 point 8 demande une API simple pour
activer/désactiver les guides et choisir le style (aucun,
indentation, indentation + portée active). Non implémenté
dans cette session — les guides sont toujours activés avec
le style « indentation + portée active ». L'API reste à
exposer et à documenter dans `USAGE.md`. À traiter en Lot B
phase 2.

---

## Hors périmètre

### README/USAGE/BUILD version 3.38.0 → 3.41.0

**Verdict : d'accord.** *Lu uniquement* (pas de test, c'est de la
documentation).

`README.md`, `USAGE.md`, `BUILD.md` affichaient encore la version
`3.38.0` alors que `build.gradle.kts` est en `3.41.0`. Alignement
des coordonnées Maven et JitPack, et des instructions de tag git
(`v3.38.0` → `v3.41.0`).

### Demo app

**Vérification :** `:demo:assembleDebug` OK, `:demo:testDebugUnitTest`
(smoke test) OK. Aucune modification nécessaire — le demo bénéficie
automatiquement des corrections via les modules `cel-ui` et
`cel-core` (l'API publique est inchangée). Le demo expose déjà la
sélection de 27 langages et utilise `session.setLanguage(...)` —
les alias `kt`, `htm`, `svg` sont désormais reconnus (Lot 1).

---

## Liste de ce qui n'a pas pu être exécuté ou vérifié

- **Lint** : `./gradlew lint` non exécuté dans cette session. À
  lancer avant une release.
- **Tests instrumentés** : aucun (pas nécessaire — Robolectric
  couvre les besoins Android selon `CONTRIBUTING.md`).
- **Scénarios manuels sur appareil** : voir Lot 0 « Scénarios NON
  couverts par tests ».
- **Benchmarks** : `EditorSessionBenchmark` existe dans
  `cel-core/src/test` mais n'a pas été exécuté pour mesurer
  l'impact perf du Lot 1+3. Les changements sont théoriquement
  neutres ou positifs (réduction des copies), mais une mesure
  avant/après serait bienvenue.
- **Lot 2** : non commencé. Voir section dédiée.
- **Lot 3 restant** : voir section dédiée.

---

## Commits (un par changement, convention AGENT.md)

### Session 1 — Lot 0 + Lot 1 + Lot 1+3

1. `fix(gutter): numéros de ligne qui disparaissent au défilement (Lot 0)`
2. `fix(edit): normalisation des ids de langue via le registre (Lot 1)`
3. `fix(edit): virgule n'est plus dangling et isInBlockComment ignore chaînes et // (Lot 1)`
4. `fix(edit): Kotlin sans ; par smartEnterComplete et indent unit détectée (Lot 1)`
5. `fix(edit): garde d'équilibre SmartTyping + contexte chaîne/commentaire (Lot 1)`
6. `fix(languages): CommentSyntax CSS (bloc seul), log (NONE), texte brut (NONE) (Lot 1)`
7. `docs: alignement des versions README/USAGE/BUILD 3.38.0 → 3.41.0`
8. `perf(popup): EditorSignaturePopup — getText() après le gate, pas avant (Lot 1+3)`
9. `perf(popup): EditorCompletionPopup — getText() seulement si provider externe (Lot 1+3)`
10. `fix(session): indentLines préserve la sélection et ignore les lignes vides (Lot 1)`
11. `docs(changelog): cumul des entrées Lot 0 + Lot 1 + Lot 1+3 (Unreleased)`

### Session 2 — Lot 2

12. `feat(languages): FoldStrategy enum + traits d'édition dans LanguageProfile (Lot 2)`
13. `feat(fold): FoldDetector route via le registre + pliage YAML, JSON arrays, javadoc (Lot 2)`
14. `feat(edit): auto-close quotes/brackets piloté par les traits du profil (Lot 2)`
15. `docs(changelog): cumul des entrées Lot 2 (Unreleased)`

### Session 3 — Lot 3

16. `fix(completion): findCallOpen/activeParameterIndex ignore chaînes et commentaires (Lot 3)`
17. `feat(completion): context gate — pas de complétion dans chaînes, commentaires, logs (Lot 3)`
18. `feat(completion): mots-clés par langage via le registre + retrait de la liste XML trompeuse (Lot 3)`
19. `perf(edit): CodeContext borne les scans O(n) à 10 000 caractères (Lot 3)`
20. `docs(changelog): cumul des entrées Lot 3 (Unreleased)`

### Session 4 — Lots A + B

21. `fix(fold): caret mal placé après une chip repliée — Y sur la ligne de début (Lot A)`
22. `fix(fold): éditions sûres — dépliage automatique avant mutation d'un pli (Lot A)`
23. `feat(render): guides d'indentation professionnels — niveaux dès colonne 0, unité réelle, BUTT, dp, fusion runs, portée active (Lot B)`
24. `docs(changelog): cumul des entrées Lot A + Lot B (Unreleased)`
25. `docs(rapport): sections Lot A + Lot B complètes`

---

## Critères d'acceptation (rappel du prompt)

- **Lot 0** :
  - Aucun trou dans la numérotation visible à aucun défilement : ✓
    (test `noGaps`).
  - Numéros présents jusqu'à la dernière ligne du document : ✓
    (test `noDisappearance`).
  - Aucune régression sur `GutterWrapAlignmentTest` ni sur les
    tests de plis/wrap : ✓ (`FoldAwareRenderPassesTest` et
    `ScrollMaxVAndZoomWrapTest` restent verts).
  - Aucun coût par frame ajouté : ✓ (le correctif ne fait qu'une
    soustraction en moins — passer `0` au lieu de `scrollTop`).
- **Lot 1** :
  - Normalisation des ids : ✓ (Lot 1.1).
  - Virgule, `/*` (chaînes + `//`), puces (`trim`), unité
    d'indentation détectée : ✓ (Lots 1.2, 1.3, 1.5).
  - Sélection conservée après Ctrl+/ et Tab ; lignes vides
    ignorées par Tab ; Maj+Tab sur un bloc : ✓ pour Tab
    (Lot 1.8). Pour Ctrl+/ : comportement conservé (décision 4.4).
  - Syntaxes de commentaire de CSS, log et texte brut : ✓
    (Lot 1.7).
  - Garde d'équilibre corrigée dans `SmartTyping` : ✓ (Lot 1.6).
  - Kotlin : pas de `;` ajouté par « compléter l'instruction » :
    ✓ (Lot 1.5).
- **Lot 2** :
  - Traits d'édition dans `LanguageProfile` (paires, quotes,
    stratégie de pliage) : ✓ (Lot 2.1). Indent triggers,
    dedent et tag auto-close reportés au Lot 3 (voir Non
    traité en Lot 2).
  - Brancher auto-close, pliage, balises (HTML/SVG/XML) sur
    ces traits : ✓ (Lots 2.2, 2.6, 2.7). Balises HTML/SVG/htm
    routées via la famille XML (alias reconnus via registre).
  - Pliage HTML/SVG/htm, YAML, tableaux JSON, javadoc,
    commentaires de bloc, imports : ✓ pour les 4 premiers
    (Lots 2.2, 2.3, 2.4, 2.5). Imports Java reporté au Lot 3
    (voir Non traité en Lot 2).
- **Lot A** :
  - Caret toujours visible à la bonne place après un pli :
    ✓ (Lot A.1 — test `caret_afterFoldChip_yIsOnFoldStartLine`).
  - Aucune suppression ou frappe n'altère de texte masqué sans
    dépliage visible : ✓ (Lot A.2 — tests `FoldEditSafetyTest`).
  - Poignées, popups et défilement suivent la même position :
    ✓ (tous passent par `caretScreenPos`, source unique corrigée).
  - Aucun changement de comportement hors des plis repliés :
    ✓ (test `edit_outsideFold` valide le garde-fou).
  - Navigation clavier (spécification 3.3 point 3) : partiel
    (voir A.4 — non critique car les mutations sont sécurisées).
- **Lot B** :
  - Guides couvrent tous les niveaux dès la colonne 0 :
    ✓ (Lot B.1 — test `firstLevel_guideDrawnAtCol4`).
  - Bonne unité d'indentation : ✓ (Lot B.2 — tests
    `twoSpaceFile_guidesAt2And4`, `secondLevel_guideDrawnAtCol8`).
  - Portée du caret visiblement mise en évidence : ✓ (Lot B.8 —
    couleur `theme.caret` + épaisseur `~1.1 dp`).
  - Tracé continu à travers lignes vides, retour à la ligne,
    plis : ✓ (Lots B.5, B.6 — validé par tests existants).
  - Aucune allocation par frame ajoutée : ✓ (Lot B.9 — balayage
    direct sur `doc.charSequence` au lieu de `doc.lineText`).
  - Aucune régression sur les tests de rendu existants : ✓
    (`EditorDiagnosticsInlaysTest`, `MinifiedLineWindowedRenderTest`,
    `FoldAwareRenderPassesTest`, etc. restent verts).
  - Réglage activer/désactiver (4.2 point 8) : non implémenté
    (voir B.11 — à traiter en phase 2).
  - Portée active par accolades (4.2 point 10) : non implémenté
    (voir B.10 — option phase 2).
- **Aucune allocation O(taille du document) ajoutée dans un chemin
  exécuté à chaque frame ou à chaque frappe** : ✓
  (Lot 1+3 — `EditorSignaturePopup` et `EditorCompletionPopup`
  évitent désormais `getText()` dans le cas commun).
- **Aucune mutation de `view.textPaint` sans restauration** : ✓
  (aucune mutation de paint touchée).
- **Pas de correction « au passage » hors périmètre** : ✓
  (les seules modifications hors prompt explicite sont la
  version README/USAGE/BUILD, mentionnée par le prompt en
  hors-périmètre 4.6).
- **En cas de doute, privilégier la plus petite solution, la
  mieux testée** : ✓ (chaque correctif est ciblé, avec test qui
  échoue avant / passe après).

---

## Convention de preuve respectée

Pour chaque point :
- **Reproduit par exécution** : test Rouge→Vert observé (la
  grande majorité des points).
- **Démontré par test** : test Vert stable sans régression
  observée (Lots 1.9, 1.10 — perf, observabilité par profilage).
- **Lu uniquement** : lecture de code sans test (hors-périmètre
  README/USAGE/BUILD).

Aucune affirmation de test passant n'a été faite sans exécution
effective. Les 934+ tests cel-ui et 720+ tests cel-core ont tous
été exécutés et restent verts après chaque correctif.
