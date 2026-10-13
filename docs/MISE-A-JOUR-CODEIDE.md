# MISE À JOUR DE CodeIDE — brancher la version 3.61.0 de `code-editor`

> **Destinataire** : agent IA chargé de la mise à jour du dépôt `CodeIDE`
> (jjoblab/CodeIDE, audité @ `04a65ea`, v0.105.0).
> **Objet** : tirer parti du lot « actions de code au point
> d'invocation » livré dans `code-editor` 3.50.0/3.50.1 et ses finitions
> 3.51.0/3.51.1, PUIS du lot « édition tactile et contexte visuel »
> livré en six jalons 3.52.0 → 3.57.0 (popup de diagnostic au tap,
> SPI d'actions de la pill, sticky interactif, inlays typés, loupe
> instrument, en-têtes enrichis, i18n) — **le lot est COMPLET en
> 3.57.0** : labelParts des inlays, hints cliquables, fenêtre
> viewport, sticky délégué au SPI langue, mouvements de lignes — ET du
> **premier retour d'appareil post-lot (3.58.0)** : le contenu du popup
> de diagnostic suit enfin sa carte à droite de l'écran (UX-4, bug),
> et la barre de fil d'Ariane porte les actions d'éditeur en haut à
> droite (B5) — ET du **deuxième retour d'appareil (3.59.0)** : les
> zones de tap fantômes de l'ancienne toolbar canvas disparaissent
> (UX-5, bug — un tap en haut à droite ne zoomait plus/dezoomait plus
> l'éditeur sans aucun bouton visible).
> **Ce document est la copie de référence archivée dans le dépôt** : il
> résume le comportement attendu et les changements côté CodeIDE. Les
> identifiants de commits sont ceux du dépôt du propriétaire, cités à
> titre de repères internes.

---

## 0. Résumé express — deux lots + trois retours terrain, un seul changement requis

| Lot | Versions | Thème | Ce que CodeIDE y gagne |
|---|---|---|---|
| Actions de code | 3.50.0 → 3.51.1 | Le pont client ne vide plus les réponses LSP | Quick-fixes réels au caret, ampoule dès publication, trigger « . », Unnecessary grisé |
| Tactile & contexte | 3.52.0 → 3.57.0 | Le dernier geste naturel est câblé — lot COMPLET | Tap sur le squiggle → popup ancré, pill extensible (SPI + actions socle, mouvements de lignes), sticky interactif + délégable au SPI langue, inlays typés + tooltips PAR PART + hints CLIQUABLES, loupe réglable, en-tête enrichi (code/source), libellés traduisibles |
| Retour terrain post-J6 | 3.58.0 | Le popup se lit à droite ; les boutons se rangent | UX-4 : le CONTENU du popup ancré suit le CADRE (bug corrigé automatiquement) ; B5 : `BreadcrumbBar` porte les actions d'éditeur en haut à droite (adoption optionnelle, §4.8) |
| Retour terrain n°2 | 3.59.0 | Plus de boutons invisibles en haut à droite | UX-5 : retrait des zones de tap fantômes de l'ancienne toolbar canvas (bug corrigé automatiquement) — un tap en haut à droite redevient un placement de caret |
| Refonte B6 (retour n°3) | 3.60.0 | Fil d'Ariane « intelligent, plus moderne » | Moteur d'icônes vectorielles ; `addEditorActions()` : les TROIS actions (annuler, rétablir, aperçu) câblées sans code hôte ; l'œil n'existe que pour un fichier prévisualisable et BASCULE ouvrir/fermer ; feuille d'aperçu carte flottante (adoption optionnelle, §4.9) |
| Retour terrain n°4 | 3.61.0 | Six irritants réels réglés à la racine | B7a : annuler/rétablir réactifs à la FRAPPE (chemin IME) ; B7b : auto-scroll du drag aussi HORIZONTAL (coin = diagonale) ; B7c : l'appui long ne vole plus le geste des popups ; B7d : aperçu « fit » (HTML brut, zoom verrouillé, coins clippés, scroll préservé) ; B7e : échantillon HTML ; B7f : fondus d'apparition/disparition des popups |

**Un seul changement est indispensable** : monter la version dans
`gradle/libs.versions.toml` (§2). Tout le reste est additif — les
surfaces que CodeIDE touche (`LspProject`, `addServerDefinition`,
`createEditor`, `setEditorView`, `setCurrentFilePath`, `connect`,
`disconnect`, `shutdown`, `setLogSink`, `sendDidChangeConfiguration`)
sont inchangées ; les ajouts sont des méthodes par défaut, des
surcharges ou des constructeurs étendus déléguant à l'existant.

---

## 1. Les lots 3.52.0 → 3.57.0 (complets) puis les retours terrain 3.58.0/3.59.0/3.60.0/3.61.0

`code-editor` est le CLIENT ; `lsp/java` de CodeIDE est le SERVEUR. Le
lot tactile travaille presque entièrement côté client (rendu, gestes,
chrome) ; **un seul point touche le contrat serveur : le champ
`source` des diagnostics (§1.1)**.

### 1.1 D4 (3.56.0) — l'en-tête des diagnostics s'enrichit : `code` et `source` enfin affichés

Avant 3.56.0, le `code` (« ecj:ID ») publié par `DiagnosticService`/
`JavaTextDocumentService` **mourait au pont client** (non transporté au
modèle interne), et le `source` LSP n'était même pas lu. Depuis :

| Côté serveur (CodeIDE) | Côté client (code-editor 3.56.0, réparé) |
|---|---|
| `code` : déjà envoyé (l. 705+) | Transporté jusqu'au rendu ET conservé par le décalage incrémental après édition |
| `source` : champ LSP optionnel — **non rempli par CodeIDE aujourd'hui** | Désormais lu (`lspDiag.getSource()`) et affiché dans l'en-tête |

L'en-tête du popup ancré (tap sur le squiggle) et de la fiche basse
affiche : `Error · ecj:67 · java · 3:9 · ×2` — sévérité, code, source,
ligne/colonne 1-based, badge de comptage des diagnostics partageant la
plage.

**Recommandé côté serveur (§4.2)** : renseigner `source` (ex. `"java"`)
dans les diagnostics publiés — sans cela, l'en-tête reste correct mais
amputé du nom du moteur. Le `code` existe déjà : il s'affichera seul,
sans rien faire.

### 1.2 Ce qui arrive automatiquement après la montée de version (zéro action)

- **D1 (3.52.0)** — tap au cœur d'un squiggle → **popup ancré au mot**,
  non modal, quick-fixes résolus à l'ouverture, « See all » vers la
  fiche basse existante ; re-tap = caret (parité), bords ±1 = caret
  direct ; multi-diagnostics sur l'offset → mini-liste ancrée.
- **UX-1/2/3 (3.54.0)** — un seul « ⋯ » sur la pill (menu unifié) ;
  **auto-scroll directionnel pendant le drag de sélection** (le doigt
  tenu dans la zone de bord fait défiler le document) ; popup de
  diagnostic resserré sur son contenu.
- **S1/S2 (3.53.0)** — lignes sticky stylées (pipeline complet, plus de
  résumé gris) et détection mémoïsée par révision (plus de creux de
  fluidité sur fichier long).
- **M1 (3.53.0)** — la loupe de drag montre la **bande de sélection, le
  squiggle et le caret** (instrument de réglage au caractère près).
- **I1/I2 (3.54.0)** — inlays typés au rendu (paramètres italiques
  pâles, types droits — `kind` du hint porté jusqu'au rendu) ;
  **appui long sur un hint → tooltip** (quick doc, resolve paresseux
  au premier appui).
- **S3/S4 (3.55.0)** — tap sur une rangée collée → la ligne d'origine
  est centrée ; plafond sticky configurable (§4.4).
- **T2/D3 (3.55.0)** — actions socle disponibles (§4.3) ; appui long
  sur un squiggle → message copié au presse-papiers.
- **T4 (3.56.0)** — la pill suit la borne **en cours de drag** (tirer
  la poignée gauche n'envoie plus la pill à l'opposé du doigt).
- **M2 (3.56.0)** — `setMagnifierZoom(float)` ×1.5–×3 (§4.5).
- **D2, T3 (3.56.0)** — interne (factorisation de géométrie) et
  i18n (§4.6).

### 1.3 J6 (3.57.0) — inlays cliquables, labelParts, fenêtre viewport : le CONTRAT SERVEUR s'ouvre

Le sixième jalon complète le lot sans rien exiger, mais il OUVRE des
voies côté serveur CodeIDE :

- **I4 — hints cliquables** : un hint LSP dont une `labelPart` porte
  une `command` (spec 3.17 — `InlayHintLabelPart.command`) devient
  TAPPABLE dans l'éditeur : le tap déclenche
  `workspace/executeCommand` (asynchrone, jamais bloquant). **Si
  CodeIDE veut des hints « … » d'expansion, son serveur doit (1)
  poser la command sur la part et (2) implémenter le handler
  `workspace/executeCommand`** — sans ça, le hint reste passif (aucun
  crash, aucun appel).
- **I3 — labelParts** : les tooltips servis PAR PART
  (`InlayHintLabelPart.tooltip`) sont consultés à l'appui long sur la
  sous-plage de la part. Un serveur mono-tooltip continue de
  fonctionner à l'identique (le tooltip du hint entier reste la
  voie de repli).
- **I5 — fenêtre viewport** : le provider LSP intégré de la
  bibliothèque reste en « document entier » (défaut) — CodeIDE n'a
  RIEN à faire. La fenêtre `viewport ± 4 lignes + re-requête au
  scroll idle` est un drapeau SPI (`wantsViewportRange`) pour des
  providers CUSTOM — inutile de l'activer, mais si un jour CodeIDE
  expose son propre provider, l'option existe. Le serveur doit
  simplement continuer d'honorer la `range` reçue (il le fait déjà).
- **S5 — sticky délégué** : si CodeIDE enregistre des
  `LanguageProfile` custom, `Builder.stickyHeaderTest(...)` laisse la
  langue décider de ses en-têtes collés (sinon : tables C_LIKE/Python
  par défaut, inchangées).
- **Mouvements de lignes (P2)** : `moveLinesUp/moveLinesDown` sont des
  actions de pill INTÉGRÉES optionnelles — même SPI que Duplicate
  (`addSelectionAction`), CodeIDE décide de les installer ou non
  (le bloc de lignes de la sélection bouge, la sélection suit).

### 1.4 Retour terrain post-J6 (3.58.0) — UX-4 réparé d'office, B5 en option

- **UX-4 (bug, automatique)** — tap sur un squiggle à DROITE de
  l'écran : le CADRE du popup était bien ancré au mot mais le CONTENU
  restait dessiné au bord GAUCHE de la vue (padX absolu — à gauche le
  bug passait inaperçu, la gouttière masquait l'écart). Corrigé : la
  métrique partagée porte `contentLeft` et la passe de dessin ne
  calcule plus aucun X seule. **CodeIDE n'a RIEN à faire** : la montée
  de version répare le rendu des deux modes (détail + mini-liste).
- **B5 (optionnel, §4.8)** — `BreadcrumbBar` devient la rangée unifiée
  au-dessus de l'éditeur : segments de portées à gauche, zone
  d'ACTIONS en haut à droite (`addAction`, `addPreviewAction`) +
  `EditorView.setPreviewIconsVisible(boolean)` pour relocaliser les
  icônes d'aperçu du coin de l'éditeur dans la barre. La démo
  (3.58.0) montre le motif complet — annuler, rétablir, étendre,
  A+/A−, œil d'aperçu — et un hôte d'aperçu Markdown WebView.

### 1.5 Retour terrain n°2 (3.59.0) — UX-5 réparé d'office

- **UX-5 (bug, automatique)** — un tap en haut à droite de l'éditeur
  déclenchait zoom/dézoom ou bascule ¶/ligatures SANS aucun bouton
  visible : le hit-test de l'ancienne toolbar canvas (A+, A−, ¶, lig)
  survivait dans le résolveur de taps alors que ses icônes ne sont
  plus dessinées depuis l'extraction d'EditorBarTools — 4 zones
  tactiles fantômes de 22 dp au-dessus de la zone de texte. Corrigé :
  le routage est retiré (un tap y retombe dans la cascade normale,
  placement du caret) et la méthode morte supprimée. **CodeIDE n'a
  RIEN à faire** : la montée de version suffit. Les boutons RÉELS
  restent `BreadcrumbBar.addAction` (§4.8) / `EditorBarTools` /
  l'API publique (`increaseFontSize`, `setShowNonPrintable`…).

### 1.6 Refonte B6 (3.60.0) — le fil d'Ariane devient « intelligent »

Troisième retour d'appareil, direction demandée : « plus intelligent,
plus moderne », avec une contrainte explicite — SEULEMENT les trois
options (annuler, rétablir, aperçu) dans la barre. Ce que la montée
apporte d'office :

- **Moteur d'icônes vectorielles** (`view/icons/EditorIcons.java`) —
  les boutons troquent les glyphes texte/emoji (« ↶ », « 👁 ») contre
  des paths dessinés au Canvas (famille FILL : annuler, rétablir,
  œil ; STROKE : scinder, étendre, fermer), net à toute densité,
  teintés du thème. Fini l'œil qui sort en emoji couleur selon
  l'appareil.
- **`BreadcrumbBar.addEditorActions()`** — UN appel pose les trois
  actions CÂBLÉES : annuler/rétablir suivent la session (grisés sans
  historique, rafraîchis à chaque édition, la session est RELUE à
  chaque évaluation — changement d'onglet couvert) ; l'aperçu
  n'EXISTE que pour un fichier prévisualisable (`GONE` sinon) et
  BASCULE (tap = ouvrir la feuille scindée, second tap = fermer).
- **Miroirs d'aperçu automatiques** — `setFileName` /
  `setPreviewMode` / `closePreviewSheet` rafraîchissent la barre
  sœur sans code hôte : l'œil apparaît/disparaît au changement de
  fichier, s'allume tant que la feuille est ouverte.
- **Feuille d'aperçu « carte flottante »** — élévation réelle 12 dp,
  coins arrondis du côté flottant, en-tête œil + nom de fichier,
  segmented control scindé/plein (compartiment actif voilé
  d'accent), bouton fermer en icône du moteur.
- **Barre 40 dp, registre pill** — cible tactile confortable ; fond
  arrondi à l'appui et en état actif. `addAction` texte (B5) reste
  disponible pour les libellés courts.
- **Icônes d'aperçu du canvas alignées** — dessin et hit-test
  partagent enfin les mêmes bornes (22 dp) ; les hôtes qui n'ont pas
  de barre y gagnent la même famille visuelle.

Zéro rupture d'API : tout est additif (EditorView et EditorSession
inchangés ligne à ligne — aucun budget déplacé).

### 1.7 Retour terrain n°4 (3.61.0) — six irritants réglés à la racine

Quatrième retour d'appareil. Les symptômes et leurs causes racines :

- **« Tu n'as pas connecté les icônes undo redo, ils ne font rien
  quand j'appuie »** (B7a) — le câblage B6 existait mais son
  rafraîchissement vivait sur `notifyTextChanged()` (hook hôte)
  SEULEMENT ; la frappe réelle passe par le chemin IME
  (`commitText` → `onTextChanged()`), qui ne rafraîchissait JAMAIS la
  barre : les boutons restaient grisés après une édition clavier, et un
  undo au bouton ne réactivait pas redo. Le point de rafraîchissement
  est descendu dans `onTextChanged()` (les deux chemins partagent) et
  les clics passent par `notifyTextChanged()` après l'opération.
- **« L'auto scrolling doit aussi être horizontal, quand je sélectionne
  pour draguer »** (B7b) — le tick d'auto-scroll ne mesurait la
  pénétration que sur les bords haut/bas. Mesure maintenant sur les
  DEUX axes (horizontal hors retour à la ligne), chaque axe à son
  rythme ; le doigt tenu dans un COIN défile en diagonale.
- **« Vérifier que les popups n'interfèrent pas avec le long click »**
  (B7c) — le `GestureDetector` est alimenté avant la cascade de
  hit-test des popups : l'appui long se déclenchait même quand le geste
  appartenait à un popup (maintien dans la complétion, scrim modal d'une
  fiche de diagnostic) — sélection pour drag + clavier + focus volés.
  Nouveau garde `popupOwnsTouch(x, y)` consulté avant tout effet.
- **« Le rendu n'est pas assez fit, que ce soit en split ou en plein »**
  (B7d) — trois causes additionnées : la DÉMO échappait le HTML via la
  conversion Markdown (l'échantillon XML mappé au faux nom
  `index.html` rendait n'importe quoi) ; le WebView suivait l'échelle
  de police SYSTÈME ; le rechargement débouncé remontait la feuille en
  haut toutes les 200 ms et le corps dépassait les coins de la carte.
  Corrigé : `.html`/`.htm` rendus BRUTS, zoom de texte verrouillé à 100,
  overview mode, scroll préservé, `setClipToOutline(true)`, CSS durci.
- **Échantillon HTML (B7e)** — page autonome (cartes, table, footer)
  rendue brute par la feuille ; « XML / HTML » redevient « XML ».
- **« Les popup : animations d'affichage/disparition plus fluides »**
  (B7f) — le mécanisme animatorie du caret glissé, généralisé :
  `EditorPopupFade` (popups Canvas : entrée 120 ms par front montant,
  sortie 110 ms par photographie au dismiss) et `EditorPopupMotion`
  (popups en fenêtre : cartes posées 140 ms, effacées 110 ms).

Zéro rupture d'API : correctifs et additions (un appel mort interne
retiré, un champ restreint ajouté à EditorView — aucune surface
publique touchée).

---

## 2. Changement REQUIS — montée de version de la dépendance

```toml
# gradle/libs.versions.toml (CodeIDE) — ligne ~52
code-editor = "3.61.0"     # était "3.49.2" (audit) / "3.51.1" (guide 3.51.1) / "3.57.0" (guide J6) / "3.58.0"-"3.60.0" (guides retours terrain)
```

Les deux modules consommateurs ne changent pas (ils suivent le
`version.ref`) :

- `lsp/host/build.gradle.kts:62` → `api(libs.codeeditor.lsp)`
- `feature/editor/build.gradle.kts:14` → `implementation(libs.codeeditor.ui)`

**Compatibilité API : vérifiée additive sur toute la plage
3.51.1 → 3.61.0** (aucune rupture binaire : nouveaux champs des
modèles diagnostic/inlay via constructeurs étendus, les
constructeurs historiques délèguent avec défaut vide ; les ajouts
3.58.0 sont `BreadcrumbBar.*` et `EditorView.setPreviewIconsVisible`,
purement additifs ; 3.59.0 ne fait que RETIRER un hit-test interne
`@RestrictTo(LIBRARY_GROUP)` — aucune surface publique touchée ; les
ajouts 3.60.0 sont `BreadcrumbBar.addIconAction`/`addEditorActions` et
le moteur `EditorIcons`, purement additifs — `addAction` texte et
`addPreviewAction` restent compatibles ; 3.61.0 est un lot de
correctifs internes + deux classes `@RestrictTo(LIBRARY_GROUP)`
(`EditorPopupFade`, `EditorPopupMotion`) — aucune surface publique
touchée).

### Publication vérifiée (2026-10-13)

- `main` @ `1514207` poussé (merge refonte B6) ; tag
  **`v3.60.0`** posé sur le commit de version `6605110`.
- CI verte sur les deux workflows (merge + tag) — runs
  `38345163324` (CI) / `38345163243` (Publication GitHub Packages),
  conclusion `success`.
- **JitPack `3.60.0` : « ok »** — 4 modules (cel-core, cel-lsp-api,
  cel-lsp, cel-ui), POM + `.module` vérifiés servis (HTTP 200) :
  `https://jitpack.io/com/github/jjoblab/code-editor/cel-ui/3.60.0/`
- Règle inchangée : **ne jamais consommer** `3.50.0`/`3.51.0` via
  JitPack (incident 429 historique — servies sur GitHub Packages
  uniquement) ; 3.51.1 et 3.52.0+ sont saines sur JitPack.

Coordonnées : `com.github.jjoblab.code-editor:cel-ui:3.60.0` et
`com.github.jjoblab.code-editor:cel-lsp:3.60.0`.

---

## 3. Ce qui n'exige RIEN côté CodeIDE (vérifié sur le code du serveur)

- **Quick-fixes / codeAction / trigger kind / tags** : tout le §2 du
  guide précédent reste vrai (pont réparé en 3.51.x, inchangé depuis).
- **Tooltips d'inlays (I2)** : le resolve est paresseux côté client ;
  le serveur n'est sollicité que si le provider le demande — les hints
  internes de CodeIDE fonctionnent sans changement.
- **Sticky lines** : la détection est interne au client (tables par
  langage) ; `isBlockHeaderLine` (S5, délégation SPI langue) n'est PAS
  encore livré — rien à prévoir.
- **Loupe native / zoom** : réglages clients purs (§4.5), aucun appel
  serveur.
- **En-tête enrichi (D4)** : le `code` déjà publié s'affiche seul ; le
  badge ×N et la ligne/colonne sont calculés côté client.

---

## 4. Améliorations RECOMMANDÉES côté CodeIDE (optionnelles)

### 4.1 `prepareRename` côté serveur (inchangé depuis le guide 3.51.1)

Le popup de renommage consomme `textDocument/prepareRename` quand le
serveur l'annonce : champ pré-rempli, refus propre hors identifiant.
Annoncer via `RenameOptions.setPrepareProvider(true)` dans
`ServerCapabilitiesProvider` (l. 94) et implémenter
`prepareRename(PrepareRenameParams)` borné 3 s sur la lane ANALYSIS.
Sans cela : repli local sur le mot sous le caret — aucun blocage.

### 4.2 D4 — renseigner `source` dans les diagnostics publiés

`DiagnosticService`/`JavaTextDocumentService` : ajouter
`diag.setSource("java")` (nom du moteur/inspection) à la publication.
Cinq lignes ; l'en-tête du popup et de la fiche affichera alors
`Error · ecj:67 · java · 3:9` au lieu de `Error · ecj:67 · 3:9`.
Le `code` (« ecj:ID ») existe déjà côté CodeIDE — il s'affiche dès la
montée de version, sans rien faire.

### 4.3 Consommer les actions socle de la pill (T2, 3.55.0)

Quatre lignes dans l'éditeur CodeIDE donnent à la pill
Duplicate / Comment / Share + conversion de casse — livrées via le
MÊME SPI que les actions d'hôte :

```java
editorView.addSelectionAction(BuiltinSelectionActions.duplicate(editorView));
editorView.addSelectionAction(BuiltinSelectionActions.toggleComment(editorView));
editorView.addSelectionAction(BuiltinSelectionActions.convertCase(
        editorView, BuiltinSelectionActions.CaseMode.TOGGLE));
// Share est OPT-IN (ACTION_SEND) — à n'installer que si CodeIDE le veut :
// editorView.addSelectionAction(BuiltinSelectionActions.share(editorView));
```

`Comment` utilise `CommentSyntax.forLanguage` (JSON estompé) ; chaque
action est retirable par identifiant (`removeSelectionAction`). CodeIDE
peut AUSSI injecter ses propres actions (recherche projet, actions
Git…) via `addSelectionAction(new SelectionAction(id, label, run,
enabledSupplier))` — le débordement alimente la section « MORE » du
menu unifié.

### 4.4 Sticky : plafond selon la classe d'appareil (S4, 3.55.0)

`setStickyMaxLines(int)` (1-5, défaut 2 = classe + méthode) : 3-4 est
tenable sur tablette — CodeIDE peut lire la densité/sw600dp et
positionner la valeur à la création de l'éditeur.

### 4.5 Loupe : zoom de lecture + opt-in natif (M2/M3, 3.56.0)

- `setMagnifierZoom(float)` (×1.5–×3, défaut ×2) — accessibilité
  lecture, la bulle grossit au facteur choisi.
- `setNativeMagnifierEnabled(true)` (API 28+) — la bulle devient le
  widget plateforme : rendu de la VRAIE frame (fidélité maximale,
  style système non maîtrisé). Décision propriétaire notée lot 1 :
  custom par défaut — l'opt-in reste un choix explicite de CodeIDE.

### 4.6 Traduire l'éditeur (T3, 3.56.0)

Les 20 libellés canoniques de cel-ui vivent désormais en ressources
(préfixe `cel_` : `cel_sel_copy`, `cel_diag_error`,
`cel_diag_quick_fixes`, `cel_nav_goto`…, défauts anglais identiques).
**CodeIDE est francophone** : redéclarer les mêmes noms dans
`app/src/main/res/values-fr/strings.xml` traduit la pill, le menu
contextuel, le NavMenu et les diagnostics de l'éditeur — la résolution
Android priorise la ressource de l'application. Liste complète :
`editor/cel-ui/src/main/res/values/strings.xml` du dépôt code-editor
v3.56.0.

---

### 4.7 Inlays cliquables — implémenter `workspace/executeCommand` (I4, 3.57.0)

Le SPI est ouvert mais muet sans serveur : pour des hints d'expansion
(expander un import, révéler le reste d'un appel…), le serveur CodeIDE
doit répondre à `workspace/executeCommand` — la command LSP posée sur
une `labelPart` part telle quelle (command id + arguments), l'éditeur
l'appelle au tap et journalise l'échec sans crash. Tant que le handler
n'existe pas, les hints éventuels restent du texte fantôme passif.

### 4.8 Adopter la barre de fil d'Ariane à actions (B5, 3.58.0)

L'ADR 0056 (v0.32.5) avait retiré l'ANCIENNE barre de fil d'Ariane —
segments seuls, sans design affirmé. La B5 est un OBJET DIFFÉRENT :
une rangée unifiée qui regroupe les actions d'éditeur en haut à
droite, à côté des segments de portées. Pour l'adopter dans
`activity_editor.xml` (au-dessus de `conteneur_editeur`) :

```kotlin
val bar = findViewById<BreadcrumbBar>(R.id.barre_fil_ariane) // ou binding
bar.bind(vueEditeur)                       // segments suivent le caret
bar.addAction("A+", "Agrandir la police", null) { vueEditeur.setFontScale(...) }
bar.addAction("A−", "Réduire la police", null) { ... }
bar.addPreviewAction()                     // œil + relocation des icônes canvas
```

Points de vigilance :

- **État grisé** : passer un fournisseur (`() -> session.undoManager.
  canUndo()`) pour Annuler/Rétablir — `EditorView.notifyTextChanged`
  rafraîchit les actions de la barre sœur automatiquement ; appeler
  `bar.refreshActions()` au changement d'onglet/fichier (l'état
  d'aperçu y est relu).
- **Aperçu** : `addPreviewAction()` exige un hôte
  (`vueEditeur.setPreviewHost(...)`) et un nom de fichier
  (`setFileName(...)`) — l'œil reste grisé sinon. CodeIDE publiera
  déjà le chemin courant via `setCurrentFilePath` : le brancher sur
  `setFileName` suffit pour `.md`/`.html`.
- **Thème** : les couleurs suivent l'éditeur lié (`bind`) — rien à
  styler, la barre s'aligne sur la gouttière (mode sombre/clair
  compris).

### 4.9 Adopter les trois actions d'IDE (B6, 3.60.0) — un seul appel

La B6 simplifie radicalement l'adoption de la §4.8 : plus besoin de
câbler annuler/rétablir à la main ni de gérer l'état de l'œil —

```kotlin
val bar = findViewById<BreadcrumbBar>(R.id.barre_fil_ariane) // ou binding
bar.bind(vueEditeur)
bar.addEditorActions()   // annuler + rétablir + aperçu, câblés d'office
```

Ce que l'appel fait pour CodeIDE :

- **Annuler / rétablir** suivent la session courante (relue à chaque
  évaluation — le changement d'onglet est couvert) : grisés sans
  historique, un tap dépile, l'état se rafraîchit à chaque édition.
- **Aperçu** : le bouton n'EXISTE que quand `setFileName` désigne un
  fichier prévisualisable au sens du hôte (`canPreview` — `.md`,
  `.html`…) ; sinon il est `GONE` (aucune rangée squattée). Un tap
  ouvre la feuille scindée, un second tap la referme ; l'icône reste
  allumée tant que la feuille est ouverte. `setCurrentFilePath` se
  branche sur `setFileName` comme en §4.8.
- Les boutons du bas de CodeIDE qui doublonnaient annuler/rétablir
  peuvent être retirés — ou conservés : les deux chemins coexistent
  sans conflit.

Pour un bouton d'icône isolé (action maison), préférer
`addIconAction(EditorIcons.Icon.UNDO, "…", enabledSupplier) { … }`
au `addAction("↶", …)` texte — même registre pill, rendu identique
sur tous les appareils.

---

## 5. Protocole de vérification sur appareil (après montée de version)

Reprendre le protocole « LSP branché » (guide 3.51.1, §4, inchangé) et
ajouter les épreuves du lot tactile :

1. **Popup au tap** : ouvrir un fichier avec une erreur intentionnelle
   (`Foo bar;` sans import) → **tap au cœur du mot souligné rouge** :
   popup ancré AU-DESSUS du mot, non modal (tap ailleurs ferme), en-tête
   `Error · ecj:<ID> · 3:9` (le code apparaît sans rien faire côté
   serveur ; `java` s'y ajoute après §4.2), bouton de quick-fix, « See
   all » → fiche basse. Re-tap sur la même plage → le caret se pose.
2. **Pill qui suit le geste (T4)** : double-tap un mot, attraper la
   poignée GAUCHE et tirer vers la gauche → la pill suit la borne du
   doigt (plus de paradoxe « pill part à droite »).
3. **Auto-scroll (UX-2)** : drag de sélection maintenu dans le bord
   bas/haut → le document défile dans la direction de la pénétration.
4. **Loupe instrument (M1) + zoom (M2)** : tirer lentement la poignée
   de caret → la bulle montre bande/squiggle/caret ; régler le zoom
   (menu démo : ×1,5 → ×3) et vérifier le grossissement.
5. **Sticky interactif (S3/S4)** : scroller un long fichier Java →
   en-têtes colorés collés ; tap sur une rangée collée → centrage de la
   ligne d'origine.
6. **Inlays typés (I1) + tooltip (I2)** : paramètres en italique pâle,
   types droits ; appui long sur un hint → tooltip/quick doc.
7. **Appui long sur squiggle (D3)** : le message part au
   presse-papiers (coller ailleurs pour vérifier).
8. **Journal** : `codeAction: requesting for range …` UNE ligne par
   invocation (inchangé depuis 3.51.x).
9. **Mouvements de lignes (J6)** : sélectionner une ligne entière (ou
   plusieurs), tap sur la pill → « Move down » : le BLOC descend d'une
   ligne et la sélection SUIT ; l'item est estompé en bord de document.
10. **Hint expansible (I4, via la démo)** : sur l'échantillon Python de
    l'APK démo 3.57.0, la ligne `# TODO: …` porte un « ⋯ » tissé en fin
    de ligne → appui long = tooltip, TAP = l'indice se développe
    (TODO devient INDICE + ligne d'indice). Côté CodeIDE branché, la
    même épreuve prouve `workspace/executeCommand` dès que le serveur
    pose une command sur une labelPart.
11. **UX-4 (3.58.0)** : placer un `// TODO` (ou une erreur) sur une
    ligne LONGUE, près du bord DROIT de l'écran → tap au cœur du
    squiggle : le CADRE s'ancre au mot ET le CONTENU (en-tête, message,
    rangées) vit DANS la carte — plus de texte resté au bord gauche.
    Même épreuve sur une erreur de la mini-liste multi-diagnostics.
12. **Barre à actions (B5, via la démo 3.58.0)** : l'APK démo montre la
    rangée fil d'Ariane + `↶ ↷ ⇔ A+ A− 👁` en haut à droite ; sur
    l'échantillon **Markdown**, l'œil ouvre la feuille d'aperçu
    (WebView), le texte tapé se reflète en ~200 ms. Côté CodeIDE,
    l'adoption passe par §4.8.
13. **UX-5 (3.59.0)** : taper (toucher) en haut à droite de l'éditeur,
    aux emplacements des anciennes icônes de toolbar — RIEN ne doit se
    passer : ni zoom, ni dézoom, ni bascule ¶/ligatures ; le caret se
    pose normalement. La taille de police ne change que via les
    boutons VISIBLES (barre de fil d'Ariane, §4.8) ou l'API.
14. **Barre B6 (3.60.0, via la démo)** : sur l'APK démo 3.60.0, la
    rangée ne montre PLUS QUE trois boutons-icônes (annuler, rétablir,
    aperçu) — plus de « ⇔ A+ A− » ; l'œil n'apparaît QUE sur
    Markdown / XML-HTML (changer d'échantillon le fait disparaître/
    réapparaître SANS autre action) ; l'œil s'allume (accent) pendant
    que la feuille est ouverte ; un second tap sur l'œil referme la
    feuille. Vérifier aussi : annuler grisé au départ, actif après une
    frappe, le tap annule réellement.
15. **Feuille carte flottante (3.60.0)** : ouvrir l'aperçu Markdown →
    la feuille flotte avec une ombre franche (élévation), coins
    arrondis côté gauche en scindé ; l'en-tête montre l'œil + le nom
    `exemple.md` ; la pilule scindé/plein bascule le mode (compartiment
    actif voilé d'accent) ; le rendu affiche liens, tables, cases de
    tâches, barré ; la frappe se reflète en ~200 ms ; fermer par ✕ ou
    par le bouton Retour.
16. **Retour n°4 (3.61.0, via la démo)** — six vérifications ciblées :
    **a)** taper du texte au clavier → le bouton ANNULER de la barre
    s'allume IMMÉDIATEMENT (plus besoin d'autre action) ; le tap
    annule, RÉTABLIR s'allume, le tap rétablit ;
    **b)** désactiver le retour à la ligne, ouvrir un échantillon à
    lignes longues (XML), double-taper un mot puis le traîner au bord
    DROIT de l'écran et maintenir → le document défile
    HORIZONTALEMENT, la sélection s'étend ; maintenir dans le COIN
    bas-droit → défilement en diagonale ;
    **c)** taper un identifiant → complétion visible → maintenir un
    appui long DANS le popup → RIEN ne se sélectionne derrière (pas de
    pill, pas de poignées) ; idem pendant la fiche de diagnostic
    (scrim modal) ;
    **d)** échantillon **HTML** → l'œil apparaît → la feuille rend la
    page BRUTE (en-tête, cartes, table — pas de balises visibles en
    texte), le contenu tient dans la carte (coins clippés), scroller
    puis taper → la position de lecture est conservée ; même épreuve
    sur Markdown (rendu converti) ; changer d'échantillon (Java) →
    l'œil disparaît ;
    **e)** les popups (complétion, quick doc, menu, pill, go-to-line,
    rename, feuille d'aperçu) apparaissent et disparaissent en FONDU
    (~120/110 ms), jamais en claquement sec ;
    **f)** rappel APK : les boutons morts de 3.60.0 venaient d'un
    correctif interne — installer l'APK démo **3.61.0** (l'ancien APK
    garde le comportement ancien).

---

## 6. Pistes suivantes (optionnelles, hors périmètre de cette mise à jour)

- **Onglet Problèmes** : `setOnDiagnosticsPublishedListener` est vivant
  pour les publications LSP — lot h3 côté CodeIDE (inchangé).
- **S5 (détection sticky par SPI langue)** : non livré — permettra à
  CodeIDE de déclarer ses propres en-têtes collés quand il sortira.
- **I3-I5 (labelParts, inlays cliquables, plage visible)** : non
  livrés — serviront les serveurs riches.
- **`source.fixAll` serveur** : l'API client `performFixAll` est prête
  (depuis 3.51.x) — répondre l'action quand `only=["source.fixAll"]`
  arrive.
