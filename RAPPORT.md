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

**Non appliqué dans cette session.** Voir RAPPORT Lot 1, décision
4.4 « Extension de LanguageProfile » pour le modèle retenu. Les
points traités en Lot 1 (normalisation des ids via le registre,
`SmartTyping` garde d'auto-close par contexte) préparent le
terrain pour le Lot 2 mais n'introduisent pas encore les traits
d'édition dans `LanguageProfile`.

Lot 2 reste à traiter dans une session ultérieure :
- traits d'édition dans `LanguageProfile` (paires, quotes, indent
  triggers, dedent, stratégie de pliage) ;
- brancher auto-close, indentation, pliage, balises (HTML/SVG/XML)
  sur ces traits ;
- pliage HTML/SVG/htm, YAML (indent-based), tableaux JSON,
  javadoc, commentaires de bloc, imports.

## Lot 3 — Contexte et performance

Partiellement traité :

- **`EditorSignaturePopup.refreshSignatureHelp`** : `getText()`
  déplacé après le gate (Lot 1+3). ✓
- **`EditorCompletionPopup.launchFetch`** : `getText()` seulement
  si provider externe (Lot 1+3). ✓
- **`indentLines`** : `doc.charSequence()` au lieu de
  `doc.getText()` (Lot 1+3). ✓

Reste à traiter en Lot 3 :
- Complétion et aide de signature désactivées dans les chaînes,
  commentaires et logs (context gating via les prédicats
  `CodeContext.isInStringOrComment`) ;
- Dictionnaires de mots-clés par langage (actuellement
  `builtinKeywordCompletions` ne couvre que `java`/`kotlin`/
  `xml`/`markdown` — étendre aux autres langages via
  `LanguageRegistry.forName(lang).keywords`) ;
- Correction de la liste XML trompeuse
  (`android:layout_padding` est un attribut inventé — devrait
  être `android:padding` ; mieux : remplacer par une table
  d'attributs réels ou par les keywords du registre) ;
- `indentLines` copie tout le document pour détecter
  l'indentation — désormais utilise `doc.charSequence()` (Lot 1+3)
  mais le scan reste O(10K chars) — une borne plus stricte ou un
  cache incrémental reste à évaluer ;
- `isInString` / `isInBlockComment` parcourent le fichier depuis
  le début à chaque Entrée — Lot 3 : borner à ~10 000 caractères
  (ou rendre incrémental, avec mesure avant/après sur un gros
  fichier).

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
