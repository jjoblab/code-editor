# MISE À JOUR DE CodeIDE — brancher la version 3.68.0 de `code-editor`

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

## 0. Résumé express — deux lots + quatre retours terrain + lots B10-B14, un seul changement requis

| Lot | Versions | Thème | Ce que CodeIDE y gagne |
|---|---|---|---|
| Actions de code | 3.50.0 → 3.51.1 | Le pont client ne vide plus les réponses LSP | Quick-fixes réels au caret, ampoule dès publication, trigger « . », Unnecessary grisé |
| Tactile & contexte | 3.52.0 → 3.57.0 | Le dernier geste naturel est câblé — lot COMPLET | Tap sur le squiggle → popup ancré, pill extensible (SPI + actions socle, mouvements de lignes), sticky interactif + délégable au SPI langue, inlays typés + tooltips PAR PART + hints CLIQUABLES, loupe réglable, en-tête enrichi (code/source), libellés traduisibles |
| Retour terrain post-J6 | 3.58.0 | Le popup se lit à droite ; les boutons se rangent | UX-4 : le CONTENU du popup ancré suit le CADRE (bug corrigé automatiquement) ; B5 : `BreadcrumbBar` porte les actions d'éditeur en haut à droite (adoption optionnelle, §4.8) |
| Retour terrain n°2 | 3.59.0 | Plus de boutons invisibles en haut à droite | UX-5 : retrait des zones de tap fantômes de l'ancienne toolbar canvas (bug corrigé automatiquement) — un tap en haut à droite redevient un placement de caret |
| Refonte B6 (retour n°3) | 3.60.0 | Fil d'Ariane « intelligent, plus moderne » | Moteur d'icônes vectorielles ; `addEditorActions()` : les TROIS actions (annuler, rétablir, aperçu) câblées sans code hôte ; l'œil n'existe que pour un fichier prévisualisable et BASCULE ouvrir/fermer ; feuille d'aperçu carte flottante (adoption optionnelle, §4.9) |
| Retour terrain n°4 | 3.61.0 | Six irritants réels réglés à la racine | B7a : annuler/rétablir réactifs à la FRAPPE (chemin IME) ; B7b : auto-scroll du drag aussi HORIZONTAL (coin = diagonale) ; B7c : l'appui long ne vole plus le geste des popups ; B7d : aperçu « fit » (HTML brut, zoom verrouillé, coins clippés, scroll préservé) ; B7e : échantillon HTML ; B7f : fondus d'apparition/disparition des popups |
| Retour terrain n°5 | 3.62.0 | Trois irritants réglés à la racine | B8a : le modèle de wrap suit le document après `setSession()` (plus de lignes « empilées » en bas en changeant de fichier avec le retour à la ligne actif) ; B8b : l'aperçu plein se ré-ajuste au changement de taille et se zoome au PINCEMENT (démo) ; B8c : un tap HORS du cadre referme le popup (feuille d'aperçu, cartes glass, actions de code, aller-au-symbole, aide de signature) |
| Retour terrain n°6 | 3.63.0 | Quatre irritants réglés à la racine | B9a : plus de « secousse » des lignes pendant le drag de sélection (le recentrage du caret est suspendu pendant le geste — la dent de scie auto-scroll↔recentrage est morte) ; B9b : le caret reste FIXE au pincement (traînées du doigt restant englouties, point focal ancré en lecture seule) ; B9c : l'icône d'aperçu (œil) vit dans la GOUTTIÈRE sur la rangée du caret, façon Android Studio, tap = bascule ; B9d : analyse des écarts Android Studio ↔ code-editor n°6 (`docs/ANALYSE-GAPS-ANDROID-STUDIO.md`) |
| **Lot B10 (recherche)** | **3.64.0** | Le plus grand écart P0 de l'analyse est refermé | `EditorSearchBar` : recherche/remplacement DANS LE FICHIER (requête vivante, compte « 3/17 », navigation bouclante, remplacement courant avec groupes `$1`, TOUT en une seule annulation) — ouverture menu/`Ctrl+F`, amorçage depuis la sélection (adoption optionnelle, §4.10) ; 4e tap = PARAGRAPHE (cascade caret → mot → ligne → paragraphe, le 5e y reste) ; surlignage purgé au changement de session |
| **Lot B11 (navigation)** | **3.65.0** | La navigation dans le contenu est au niveau AS | Fast-scroller : le drag de gouttière devient un positionnement ABSOLU (pouce + bulle de ligne, tout le fichier d'un seul geste, parité Android Studio) ; bande d'erreurs au bord droit : marqueurs de diagnostics de TOUT le fichier (même hors écran) + rect du viewport, tap = centrage + fiche, drag = scroll absolu — GRATUIT à la montée (aucune adoption), bascule `setErrorStripeEnabled` si CodeIDE préfère l'éteindre |
| **Lot B12 (confort)** | **3.66.0** | Le zoom et les numéros se souviennent | P1-3 : zoom PERSISTANT par type de fichier — `setZoomMemoryKey(".java")` à l'ouverture, pincement/A± enregistrés, réouverture = réglage restauré, le zoom ne fuit pas entre types (adoption recommandée, §4.12 — trois lignes) ; P1-4 : numéros de gouttière RELATIFS/HYBRIDES façon AS (`LineNumberStyle`, optionnel) |
| **Lot B13 (parité riche)** | **3.67.0** | Glisser-déposer une sélection la DÉPLACE | P1-2 : mode « drag de bloc » armé par la pill ✥ Move — le geste suivant déplace le texte (loupe + caret fantôme au point de dépôt + carte flottante + auto-scroll de bord), un tap annule ; dépôt en UNE annulation, sélection ré-épinglée (adoption recommandée, §4.13 — trois lignes) ; `EditorSession.moveRange` public pour les barres d'outils |
| **Lot B14 (pliage)** | **3.68.0** | Replier au niveau, tout replier/déplier | P1-5 : « Replier au niveau N » (1 = classes, 2 = méthodes — profondeur EXACTE, sémantique VS Code) + « tout replier / tout déplier » (`collapseFoldsAtLevel`/`collapseAllFolds`/`expandAllFolds` — adoption recommandée, §4.14 : trois entrées de menu) ; régions `#region` (C#) et `//region` (IntelliJ) détectées + correctif du nom de région (la chip portait un « n » parasite) |

**Un seul changement est indispensable** : monter la version dans
`gradle/libs.versions.toml` (§2). Tout le reste est additif — les
surfaces que CodeIDE touche (`LspProject`, `addServerDefinition`,
`createEditor`, `setEditorView`, `setCurrentFilePath`, `connect`,
`disconnect`, `shutdown`, `setLogSink`, `sendDidChangeConfiguration`)
sont inchangées ; les ajouts sont des méthodes par défaut, des
surcharges ou des constructeurs étendus déléguant à l'existant.

---

## 1. Les lots 3.52.0 → 3.57.0 (complets) puis les retours terrain 3.58.0/3.59.0/3.60.0/3.61.0/3.62.0/3.63.0 et les lots B10-B14 (3.64.0→3.68.0)

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

### 1.8 Retour terrain n°5 (3.62.0) — trois irritants réglés à la racine

Cinquième retour d'appareil. Les symptômes et leurs causes racines :

- **« Quand le retour à la ligne est actif et que j'ouvre un autre
  fichier, les lignes en bas semblent empilées »** (B8a) —
  `EditorView.setSession()` purgeait le cache de rendu et le scroll
  mais JAMAIS le modèle de wrap : le modèle de l'ANCIEN document
  rendait le nouveau. `WrapModel.topRow()` clampe toute ligne au-delà
  de l'ancien compte de lignes sur le MÊME total → toutes les lignes
  de queue se dessinent au même Y (« empilées »), d'autant plus
  visibles que le fichier est long ; même compte de lignes ne
  sauvegardait pas (les rangées par ligne restent comptées sur les
  longueurs de l'ancien document). Le modèle est désormais reconstruit
  à chaque changement de session quand le wrap est actif (et purgé
  sans session). **Correctif automatique pour CodeIDE : c'est le bug
  du changement d'onglet en mode wrap — rien à faire.**
- **« La preview en full : le zoom est trop, pas fit, je ne peux pas
  zoomer ou dézoomer »** (B8b, démo) — deux causes côté hôte de démo :
  les contrôles de zoom intégrés du WebView étaient désactivés (le
  pincement ne faisait RIEN), et la bascule scindé → plein ne
  re-rendait jamais (le WebView gardait échelle et mise en page de
  l'ANCIENNE largeur). Correctifs : pincement actif (sans boutons
  +/−), rechargement du payload à chaque changement de taille
  (`onPreviewModeChanged` — LE point d'extension si CodeIDE écrit son
  propre hôte d'aperçu), WebView créée du contexte RÉEL de l'éditeur.
- **« Quand le popup est visible, le touché en dehors du cadre
  dismiss le popup »** (B8c) — audit complet de la fermeture
  extérieure : la feuille d'aperçu et les cartes glass
  (aller-à-la-ligne, renommage) portaient un verrou « ✕ uniquement »
  (recette Android complète désormais : `setOutsideTouchable` + fond
  non nul → `ACTION_OUTSIDE` → dismiss automatique, le tap traverse
  vers l'éditeur qui place son caret) ; les popups Canvas d'actions
  de code et l'aller-au-symbole se referment sur un tap hors bornes ;
  l'aide de signature (bannière passive) se referme sur tout tap.

Zéro rupture d'API : un correctif interne (`setSession`), une méthode
outil restreinte (`EditorPopupMotion.outsideTapDismisses`) et la
cascade de taps enrichie — aucune surface publique touchée.

### 1.9 Retour terrain n°6 (3.63.0) — quatre irritants réglés à la racine

Sixième retour d'appareil. Les symptômes et leurs causes racines :

- **« Pendant le drag de sélection, les lignes ont tendance à
  secouer »** (B9a) — chaque application de sélection du drag
  déclenchait `EditorImeBridge.onSelectionChanged →
  scrollCaretIntoView()`, qui TIRAIT le viewport vers l'ancre du drag
  pendant que l'auto-scroll UX-2 le POUSSAIT dans la direction du
  doigt : dès que le défilement dépassait le Y de l'ancre, le
  recentrage ramenait le viewport en arrière — oscillation en dent de
  scie au rythme des ticks, tout le texte tremblait (surtout visible
  ancre près du haut de l'écran, doigt tenu dans la zone de bord).
  Pendant qu'un drag de sélection est actif
  (`EditorView.isSelectionDragActive()`), le recentrage est SUSPENDU —
  le geste et son auto-scroll dirigent seuls le viewport. La loupe est
  en outre stabilisée : Y snappé au centre de la rangée réglée (le
  contenu de la bulle ne tremble plus verticalement), X amorti
  exponentiellement — le zoom ×2 n'amplifie plus le jitter du capteur.
  **Correctif automatique pour CodeIDE : rien à faire.**
- **« Quand je fais pince avec 2 doigts pour zoom/dézoomer, le caret
  ne reste pas à une position fixe »** (B9b) — trois causes
  superposées : (1) l'ancrage du caret fonctionnait PENDANT le
  pincement, mais au relevé d'un doigt le doigt RESTANT continuait
  d'envoyer des MOVE — le slop était déjà franchi au DOWN — chaque
  traînée déclenchait un SCROLL juste après le zoom (le viewport
  glissait, le caret partait avec) : les traînées d'un geste qui a
  pincé sont englouties (parité TextView) ; (2) les sessions en
  LECTURE SEULE n'avaient aucun ancrage (le contenu sautait sous le
  pincement) : l'ancre est désormais le POINT FOCAL du pincement
  (le haut de la ligne sous le milieu des deux doigts, colonne hors
  wrap) ; (3) l'ancrage caret (session éditable) est conservé tel
  quel. Un pincement qui démarre met en outre fin proprement à tout
  drag de sélection en cours. **Correctif automatique : rien à faire.**
- **« Assures-toi que l'icône pour preview dans le gutterview est
  bien là, comme Android Studio »** (B9c) — les icônes de gouttière
  d'Android Studio (run ▶, Compose `@Preview`) vivent DANS la
  gouttière, ancrées à la ligne qu'elles concernent : l'œil d'aperçu
  suit la même grammaire. Pill + œil sur la RANGÉE DU CARET, visible
  dès que le fichier est prévisualisable (`previewable` — md, html,
  htm…), allumé à l'accent quand la feuille est ouverte, tap =
  bascule SANS déplacer le caret. Rectangle déterministe partagé par
  le dessin et le hit-test (26 dp bornés par la ligne) ; aucune zone
  tactile fantôme (garde rangée à l'écran). L'œil du fil d'Ariane B6
  reste inchangé — les deux entrées cohabitent.
- **« Énième analyse Android Studio ↔ code-editor »** (B9d) — livrée
  dans `docs/ANALYSE-GAPS-ANDROID-STUDIO.md` (copie dans
  `download/`) : angle édition tactile, sol crédité, écarts
  priorisés P0/P1/P2 et feuille de route B10→B14 — le P0 n°1 est la
  recherche/remplacement dans le fichier (le moteur `FindReplace`
  existe déjà dans cel-core, il ne manque que l'interface).

Zéro rupture d'API : une méthode publique restreinte de plus
(`applyPinchScale(factor, focusX, focusY)` surcharge), un relais
d'état (`isSelectionDragActive`), l'icône de gouttière et son
hit-test — aucune surface existante modifiée.

---

### 1.10 Lot B10 (3.64.0) — recherche/remplacement dans le fichier + 4e tap paragraphe

Premier lot de la feuille de route de l'analyse des écarts n°6 : les
deux écarts P0. Le moteur de correspondance (`FindReplace` en cel-core)
et le surlignage du viewport (`setFindHighlights`) existaient depuis
longtemps — mais AUCUNE plomberie ne les reliait : c'était le seul écart
que le propriétaire toucherait à chaque session sur des fichiers longs.

- **`EditorSearchController`** (view/chrome) porte l'état et les actions
  sans une ligne d'UI : recherche vivante (recalcul différé à la frappe
  ET à chaque édition du document, listener ré-accroché quand la
  session change) ; navigation bouclante — l'occurrence courante est
  teintée `findCurrent`, le viewport l'amène, le caret va à son début
  SANS sélection (la pill et les poignées de l'utilisateur ne sont pas
  parasitées) ; remplacement de l'occurrence courante (splice ciblé ;
  mode regex : expansion des groupes `$1`/`$2` par matcher borné à la
  région de l'occurrence ; remplacement mal formé — « $ » orphelin —
  repli littéral, jamais de crash) ; remplacement de TOUT en UNE passe
  sur le texte d'origine : une seule entrée d'annulation (l'undo
  restaure le document entier), caret restauré borné. Après chaque
  remplacement la courante devient la première occurrence à partir de
  la fin du texte inséré — enchaîner « Remplacer » remplace bien tout,
  une par une.
- **`EditorSearchBar`** (view/chrome) est la vue, dans le registre
  visuel du fil d'Ariane (pill, accent des bascules actives, thème
  lié) : rangée requête + compte « 3/17 » + précédent/suivant +
  fermer ; rangée de bascules « Aa » casse / « ab » mot entier /
  « .* » regex + bascule remplacer ; rangée de remplacement
  (« Remplacer » / « Tout »). Enter = occurrence suivante, Échap
  (clavier physique) referme, l'ouverture amorce la requête depuis la
  sélection si elle tient sur une ligne, la fermeture garde la requête
  (réouverture sans re-frapper).
- **Deux entrées d'ouverture** : un article de menu de l'hôte, et le
  raccourci `Ctrl+F` qui existait déjà (il route vers le listener
  primaire de sélection — un objet implémentant À LA FOIS
  `OnSelectionChangedListener` et `OnFindRequestedListener` reçoit
  l'appel).
- **Purge du surlignage au changement de session** (`setSession`) :
  les offsets de recherche de l'ancien document sont périmés sur le
  nouveau — sans purge, des bandes restaient dessinées à des offsets
  hasardeux (bug corrigé d'office à la montée de version).
- **`selectParagraphAt` + 4e tap** : la cascade de taps gagne son
  quatrième cran — caret (1) → mot (2) → ligne (3) → PARAGRAPHE (4) :
  bloc maximal de lignes contiguës non vides autour du tap, borné par
  les lignes vides ou blanches, fin sans le saut (parité ligne). Le
  5e tap RESTE au paragraphe. Sur CodeIDE : gratuit à la montée, les
  taps successifs sélectionnent des blocs entiers (commenter/déplacer
  un paragraphe d'un coup).
- **Quatre icônes vectorielles** (loupe en cubiques — le parseur du
  moteur rejette les arcs —, chevrons haut/bas, double-flèche) et 14
  libellés canoniques `cel_find_*` traduisibles par l'hôte.

### 1.11 Lot B11 (3.65.0) — fast-scroller de gouttière + bande d'erreurs

Deuxième lot de la feuille de route de l'analyse des écarts n°6 : le
P0-3 (retour visuel du drag de gouttière) et le P1-1 (error stripe,
le plus rentable de la famille P1). Les deux partagent la même
conclusion : la navigation dans un fichier long au doigt exige de
VOIR où l'on est et d'ATTEINDRE n'importe où — les deux manquaient.

- **`EditorFastScroller` (P0-3)** — le drag dans la gouttière (zone
  des numéros) devient un positionnement ABSOLU, comme Android
  Studio : au-delà du touch-slop, un POUCE (pill 4 dp au bord
  droit, hauteur proportionnelle au viewport, plancher 32 dp)
  apparaît et suit la hauteur du doigt ; la position du document
  suit en proportion (`top / (viewH − thumbH) × maxV`, géométrie
  consciente du retour à la ligne ET des plis). Une BULLE à gauche
  du pouce affiche la ligne du milieu du viewport (le retour
  visuel du fast-scroller des listes Android). Relâchement : fondu
  de 250 ms, PAS de fling (le positionnement est absolu, un fling
  le casserait). Annulation immédiate au pincement, au CANCEL, à
  l'appui long et au changement de session. Un tap reste un tap :
  le pouce n'apparaît JAMAIS sous le slop — les points de
  diagnostic et l'icône d'aperçu de gouttière gardent leur
  résolution, et le jitter d'un simple appui dans les numéros ne
  défile plus (l'ancien chemin pixel-relatif défilait AU PREMIER
  pixel de mouvement, horizontal inclus).
- **`EditorErrorStripe` (P1-1)** — la bande d'erreurs d'Android
  Studio : 4 dp au bord droit (zone tactile 10 dp). Un MARQUEUR
  coloré par ligne diagnostiquée (erreur/avertissement/info,
  couleurs du thème, sévérité max par ligne — même source que les
  points de gouttière via `EditorDiagnosticsPusher`, compactée en
  paires sparse : le dessin reste O(diagnostics)) et le RECTANGLE
  DU VIEWPORT façon scrollbar. Sur un fichier long, les erreurs
  hors écran deviennent visibles d'un coup d'œil. TAP sur un
  marqueur : dépliage du pli englobant, centrage de la ligne
  (`scrollToLineCentered`) et ouverture de la fiche (popup de
  détail, ou liste groupée si la ligne porte plusieurs diagnostics
  — parité exacte avec le point de gouttière et la chip de fin de
  ligne). DRAG sur la bande : même positionnement absolu que le
  fast-scroller (pouce + bulle). Tap sans marqueur : geste consommé
  (ni caret ni fiche derrière — la bande est un chrome, pas du
  texte). La bande se tait quand un aperçu est ouvert
  (SPLIT/FULL/feuille) : le bord droit appartient alors au corps
  d'aperçu. Purge au changement de session (marqueurs de l'ancien
  document effacés, pouce masqué).
- **Bascule publique** : `EditorView.setErrorStripeEnabled(boolean)`
  / `isErrorStripeEnabled()` — défaut ACTIVÉ (parité Android
  Studio out of the box) ; `setMinimapEnabled` reste
  l'opt-in de la minimap (les deux cohabitent : la bande borde le
  flanc droit de la minimap).
- **Zéro adoption requise** : tout est gratuit à la montée de
  version. CodeIDE n'éteint la bande que si l'écran partagé le
  réclame (§4.11).

### 1.12 Lot B12 (3.66.0) — zoom persistant par type + colonnes relatives

Troisième lot de la feuille de route de l'analyse des écarts n°6 : le
P1-3 (zoom mémorisé par type de fichier, comme Android Studio) et le
P1-4 (numéros relatifs à la ligne du caret). Deux écarts de CONFORT —
le palier navigation (B10-B11) étant refermé, ce sont les irritants
quotidiens qui restent : régler le zoom du code à 130 % puis le
REFAIRE à chaque ouverture d'un `.java`, compter les lignes à la main
pour sauter « 12 lignes plus bas ».

- **B12a — `EditorZoomMemory` + `setZoomMemoryKey` (P1-3)** — mémoire
  PROCESS des échelles de police, par clé de type posée par l'HÔTE
  (extension, id de langage — CodeIDE choisit sa granularité).
  Chaque mutation d'échelle (pincement, A+/A− du fil d'Ariane,
  `setFontScale`) enregistre l'échelle bornée [0,6–2,6] sous la clé
  courante ; reposer la clé applique le réglage du type. Sémantique
  « UN ZOOM PAR TYPE » : le zoom du type précédent ne FUIT PAS sur le
  suivant (un `.md` jamais zoomé repart à 1, il n'hérite pas du 1,8
  du `.java` voisin). Clé nulle : mémoire désactivée, comportement
  historique EXACT. La rotation reste portée par l'état d'instance
  B17, PLUS PRÉCIS — la restauration s'exécute après le câblage de
  l'hôte, l'échelle exacte de CETTE vue l'emporte sur la mémoire du
  type. Portée : le process (survit aux onglets/activités recréés,
  pas au redémarrage de l'app — pour cela CodeIDE persisterait les
  clés lui-même dans ses Settings, la classe reste un cache
  d'appoint).
- **B12b — `LineNumberStyle` ABSOLUTE/RELATIVE/HYBRID (P1-4)** — le
  mode des numéros de gouttière, posé par
  `EditorView.setLineNumberStyle` (relais public, `null` retombe sur
  ABSOLUTE). ABSOLUTE (défaut, inchangé) : numéros de document ;
  RELATIVE : distance à la ligne du caret — 0 au caret, 1, 2, 3…
  autour (lecture directe des déplacements `5j`/`10k`, convention
  « relative line numbers » d'Android Studio) ; HYBRID : numéro
  RÉEL au caret + distances autour (le mode par défaut de VS Code).
  La surbrillance (gras + couleur texte) de la ligne du caret reste
  posée dans les trois modes ; un caret invalide retombe sur
  ABSOLUTE.
- **Zéro adoption requise** : sans clé posée et sans style touché,
  le comportement est l'historique EXACT. La clé de zoom est
  recommandée (§4.12 — trois lignes à l'ouverture du fichier) ; le
  style de numéros est un confort optionnel (réglage utilisateur).

### 1.13 Lot B13 (3.67.0) — drag-déposer de bloc : glisser une sélection la DÉPLACE

Quatrième lot de la feuille de route de l'analyse des écarts n°6 :
le P1-2 (parité TextView drag & drop). La convention Android veut
que glisser une sélection la DÉPLACE ; l'éditeur étendait la
sélection au drag (appui long) — déplacer exigeait copier-coller
via la pill. Un geste direct entrerait en conflit avec l'extension
de sélection : le mode « drag de bloc » est ARMÉ par la pill
(item ✥ Move), recommandation de l'analyse des écarts.

- **`EditorSession.moveRange(start, end, target)`** — moteur public
  du déplacement : suppression + réinsertion en UNE étape
  d'annulation (utilisable aussi depuis une barre d'outils hôte),
  sélection RÉ-ÉPINGLÉE sur le texte déplacé (aller-retour
  undo/redo stable). Sémantique CARACTÈRE exacte : `target` est
  l'offset du caret fantôme AVANT suppression (corrigé de la
  longueur retirée au-delà de la source) ; le trou de la source
  garde ses séparateurs. No-op propre en lecture-seule, plage vide
  ou dépôt dans la source — la pile d'annulation n'est jamais
  polluée.
- **`EditorView.armBlockDrag()` + `BuiltinSelectionActions.blockDrag`**
  — le tap sur l'item ✥ Move de la pill arme le mode ; le GESTE
  SUIVANT appartient au bloc (englouti — pas de scroll ni de caret
  derrière) : drag = déplacement, tap = annulation du mode,
  CANCEL/pincement/changement de session = retombée propre
  (document intact). Pendant le drag : loupe stabilisée (Y snappé
  à la rangée du point de dépôt, sémantique B9), caret FANTÔME
  3 dp au point de dépôt (dessiné aussi dans la loupe — la loupe
  montre ce qu'on règle), carte flottante du bloc SOUS le doigt
  (3 lignes, ellipse, bord accent), auto-scroll de bord partagé
  avec le drag-select et suspension du recentrage du caret
  (l'anti-secousse B9 s'applique).
- **Zéro adoption requise** : sans l'item de pill, rien ne change.
  L'installation est recommandée (§4.13 — trois lignes).

### 1.14 Lot B14 (3.68.0) — plis par niveau + régions, tout replier/déplier

Cinquième lot de la feuille de route de l'analyse des écarts n°6 :
le P1-5. Le toggle par ligne existait (chevrons de la bande de
pli + chips) ; manquaient le pliage sélectif par niveau de la
hiérarchie — « méthodes vs classes » — et la tolérance des
marqueurs de région à la syntaxe C#/IntelliJ.

- **`EditorSession.collapseFoldsAtLevel(n)`** — « Replier au niveau
  N » (sémantique VS Code Fold Level N) : replie toute région de
  profondeur d'imbrication EXACTE — 1 = classes, 2 = méthodes,
  3 = blocs internes. Les parents restent OUVERTS et chaque région
  porte son état : déplier la classe révèle les méthodes ENCORE
  repliées. `collapseAllFolds()` / `expandAllFolds()` pour « tout
  replier / tout déplier ». Retour compté (nb de régions touchées),
  révision de plis incrémentée — l'index de la vue se reconstruit
  sans re-pose de session.
- **Régions nommées : `#region … #endregion` (C#) et
  `//region … //endregion` (IntelliJ)** désormais détectées en plus
  des formes historiques espacées ; le NOM de la région est extrait
  des deux formes (chip).
- **Correctif inclus** : le nom des régions historiques portait un
  caractère parasite (off-by-one — « n Services » au lieu de
  « Services ») ; la chip porte désormais le VRAI nom.
- **Zéro adoption requise** : sans appel, rien ne change. Trois
  entrées de menu sont recommandées (§4.14).

## 2. Changement REQUIS — montée de version de la dépendance

```toml
# gradle/libs.versions.toml (CodeIDE) — ligne ~52
code-editor = "3.68.0"     # était "3.49.2" (audit) / "3.51.1" (guide 3.51.1) / "3.57.0" (guide J6) / "3.58.0"-"3.60.0" (guides retours terrain) / "3.61.0"-"3.63.0" (guides retours n°4-6) / "3.64.0" (lot B10) / "3.65.0" (lot B11) / "3.66.0" (lot B12) / "3.67.0" (lot B13) / "3.68.0" (lot B14)
```

Les deux modules consommateurs ne changent pas (ils suivent le
`version.ref`) :

- `lsp/host/build.gradle.kts:62` → `api(libs.codeeditor.lsp)`
- `feature/editor/build.gradle.kts:14` → `implementation(libs.codeeditor.ui)`

**Compatibilité API : vérifiée additive sur toute la plage
3.51.1 → 3.66.0** (aucune rupture binaire : nouveaux champs des
modèles diagnostic/inlay via constructeurs étendus, les
constructeurs historiques délèguent avec défaut vide ; les ajouts
3.58.0 sont `BreadcrumbBar.*` et `EditorView.setPreviewIconsVisible`,
purement additifs ; 3.59.0 ne fait que RETIRER un hit-test interne
`@RestrictTo(LIBRARY_GROUP)` — aucune surface publique touchée ; les
ajouts 3.60.0 sont `BreadcrumbBar.addIconAction`/`addEditorActions` et
le moteur `EditorIcons`, purement additifs — `addAction` texte et
`addPreviewAction` restent compatibles ; 3.61.0 est un lot de
correctifs internes + deux classes `@RestrictTo(LIBRARY_GROUP)`
(`EditorPopupFade`, `EditorPopupMotion`) ; 3.62.0-3.63.0 sont des correctifs
interne (`setSession` reconstruit le modèle de wrap) + une méthode
outil `@RestrictTo(LIBRARY_GROUP)`
(`EditorPopupMotion.outsideTapDismisses`) — aucune surface publique
touchée ; 3.64.0 ajoute DEUX classes publiques (`EditorSearchBar`,
`EditorSearchController`) et une méthode de sélection
(`EditorSession.selectParagraphAt`) sans rien retirer — le 4e tap
modifie volontairement la cascade multi-tap au-delà du triple tap
(ligne → paragraphe : c'est le but du cran)) ; 3.65.0 ajoute UN
setter public (`EditorView.setErrorStripeEnabled`/`isErrorStripeEnabled`)
 et DEUX collaborateurs internes `@RestrictTo(LIBRARY_GROUP)`
(`EditorFastScroller`, `EditorErrorStripe`) sans rien retirer — le
comportement du drag de gouttière change volontairement (scroll
relatif → positionnement absolu, c'est le but du P0-3)) ; 3.66.0
ajoute UNE classe publique (`EditorZoomMemory`), UN enum public
(`LineNumberStyle`) et TROIS méthodes de vue
(`setZoomMemoryKey`, `setLineNumberStyle`, `getLineNumberStyle`)
sans rien retirer ni changer aucun comportement par défaut — le
zoom mémoire et le style de numéros ne s'activent QUE si l'hôte
pose la clé/le style) ; 3.67.0 ajoute UNE méthode de session
(`EditorSession.moveRange`), QUATRE méthodes de vue
(`armBlockDrag`/`isBlockDragArmed`/`isBlockDragActive`/
`cancelBlockDrag`) et UNE action pill (`BuiltinSelectionActions.
blockDrag`) sans rien retirer ni changer aucun comportement par
défaut — le mode drag de bloc n'existe QUE si l'hôte installe
l'item de pill (ou arme explicitement)) ; 3.68.0 ajoute TROIS
méthodes de session (`collapseFoldsAtLevel`/`collapseAllFolds`/
`expandAllFolds`) sans rien retirer ni changer aucun comportement
par défaut — les commandes de pliage de masse n'existent que si
l'hôte les expose (le correctif du nom de région est automatique,
transparent pour l'API).

### Publication (2026-10-13, lot B14)

- `main` poussé (merge du lot B14, trois commits) ; tag
  **`v3.68.0`** posé sur le merge ; workflows CI + Publication
  GitHub Packages déclenchés par le poussage (vérifier l'état sur
  la page Actions du dépôt).
- **Consigne du propriétaire (2026-10-13, lots B13/B14)** :
  PUBLICATION JITPACK DEMANDÉE pour cette session (deux lots
  majeurs au sens de la politique de version — une nouvelle
  capacité d'édition B13 et la fermeture du palier de pliage B14).
  **Livrée sur la clé canonique `v3.68.0`** (la version CIBLE de
  la session, B13 + B14 cumulés) : build déclenché et VÉRIFIÉ —
  les QUATRE modules servent POM + AAR + sources
  (`com.github.jjoblab.code-editor:cel-ui:v3.68.0` et frères).
  La clé `v3.67.0` a buté sur un 429 TRANSITOIRE de Maven Central
  côté JitPack (incident externe connu) — elle se reconstruira à
  la demande ; monter DIRECTEMENT en `v3.68.0` (recommandé).
  Les 3.51.1+ restent saines, seules `3.50.0`/`3.51.0` demeurent
  interdites via JitPack (incident 429 historique, servies sur
  GitHub Packages uniquement).
- Coordonnées : `com.github.jjoblab.code-editor:cel-ui:v3.68.0` et
  modules frères (même version, dépôt `maven { url =
  uri("https://jitpack.io") }`).

### Publication (2026-10-13, lot B13)

- `main` poussé (merge du lot B13, trois commits) ; tag
  **`v3.67.0`** posé sur le merge ; workflows CI + Publication
  GitHub Packages déclenchés par le poussage (vérifier l'état sur
  la page Actions du dépôt).
- **Consigne du propriétaire (2026-10-13, session B13/B14)** :
  PUBLICATION JITPACK DEMANDÉE. Le build de `v3.67.0` a buté sur un
  **429 TRANSITOIRE de Maven Central côté JitPack** (résolution des
  dépendances buildscript — incident externe, pas un problème de
  code : le MÊME arbre construit et passé sous la clé `v3.68.0`,
  qui vérifie B13 + B14 cumulés) ; la clé se reconstruira à la
  demande. **Monter directement en `v3.68.0`** (recommandé — §2).
  Les 3.51.1+ restent saines, seules `3.50.0`/`3.51.0` demeurent
  interdites via JitPack (incident 429 historique, servies sur
  GitHub Packages uniquement).
- Coordonnées : `com.github.jjoblab.code-editor:cel-ui:v3.67.0`
  (à la reconstruction) et modules frères (dépôt `maven { url =
  uri("https://jitpack.io") }`).

### Publication (2026-10-13, lot B12)

- `main` poussé (merge du lot B12, trois commits) ; tag
  **`v3.66.0`** posé sur le merge ; workflows CI + Publication
  GitHub Packages déclenchés par le poussage (vérifier l'état sur
  la page Actions du dépôt).
- **Consigne du propriétaire (2026-10, reconduite)** : le build
  JitPack n'est PAS attendu ni vérifié pour ce lot (pas de
  changement majeur au sens de la politique de version — JitPack
  construira `3.66.0` à la demande à partir du tag ; les 3.51.1+
  sont saines, seules `3.50.0`/`3.51.0` restent interdites via
  JitPack — incident 429 historique, servies sur GitHub Packages
  uniquement).
- Coordonnées : `com.github.jjoblab.code-editor:cel-ui:3.66.0` et
  `com.github.jjoblab.code-editor:cel-lsp:3.66.0`.

### Publication (2026-10-13, lot B11)

- `main` poussé (merge du lot B11, trois commits — le lot B10
  avait été poussé dans la même session) ; tag **`v3.65.0`** posé
  sur le merge ; workflows CI + Publication GitHub Packages
  déclenchés par le poussage (vérifier l'état sur la page Actions
  du dépôt).
- **Consigne du propriétaire (2026-10)** : le build JitPack n'est
  PAS attendu ni vérifié pour ce lot (pas de changement majeur au
  sens de la politique de version — JitPack construira `3.65.0` à
  la demande à partir du tag ; les 3.51.1+ sont saines, seules
  `3.50.0`/`3.51.0` restent interdites via JitPack — incident 429
  historique, servies sur GitHub Packages uniquement).
- Coordonnées : `com.github.jjoblab.code-editor:cel-ui:3.65.0` et
  `com.github.jjoblab.code-editor:cel-lsp:3.65.0`.

- `main` @ `8bf590a` poussé (merge retour terrain n°6 + merge du
  correctif de course CI) ; tag **`v3.63.0`** posé sur le merge du
  correctif (le premier poussage du tag a échoué en CI — course sur
  un test de l'icône de gouttière sur runner rapide, corrigée par le
  merge `9924150` : les taps de gouttière ignorent la cascade
  multi-tap ; le tag a été REPOSÉ et republié).
- CI verte sur les deux workflows (merge + tag) — runs
  `38371245339` (CI) / `38371248397` (Publication GitHub Packages),
  conclusion `success` ; le run défaillant d'origine
  `38370670092` est documenté dans le CHANGELOG du commit de
  correctif.
- **JitPack `3.63.0` : « ok »** — 4 modules (cel-core, cel-lsp-api,
  cel-lsp, cel-ui), POM + `.module` vérifiés servis (8/8 HTTP 200) :
  `https://jitpack.io/com/github/jjoblab/code-editor/cel-ui/3.63.0/`
- Règle inchangée : **ne jamais consommer** `3.50.0`/`3.51.0` via
  JitPack (incident 429 historique — servies sur GitHub Packages
  uniquement) ; 3.51.1 et 3.52.0+ sont saines sur JitPack.

Coordonnées : `com.github.jjoblab.code-editor:cel-ui:3.63.0` et
`com.github.jjoblab.code-editor:cel-lsp:3.63.0`.

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

### 4.10 Adopter la barre de recherche/remplacement (B10, 3.64.0) — trois lignes

`EditorSearchBar` se pose dans la hiérarchie de l'Activity/Fragment,
entre l'en-tête et l'éditeur, et se câble en deux appels :

```java
EditorSearchBar searchBar = new EditorSearchBar(this);
searchBar.bind(editor);            // contrôleur + thème de l'éditeur
root.addView(searchBar, LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT); // GONE par défaut
```

Ouverture : `searchBar.open()` (amorce la requête depuis la sélection,
focus + clavier au champ). Fermeture : le ✕ de la barre, ou
`searchBar.close()`. Après un changement de fichier/onglet :
`searchBar.refresh()` (le contrôleur ré-accroche la session courante
et recalcule les occurrences — la purge du surlignage est déjà faite
par `setSession`).

Pour que `Ctrl+F` l'ouvre : le listener primaire de sélection doit
implémenter AUSSI `OnFindRequestedListener` (le raccourci cherche
cette interface dans le listener primaire) :

```java
editor.setOnSelectionChangedListener(new BothRoles());
// class BothRoles implements EditorView.OnSelectionChangedListener,
//         EditorView.OnFindRequestedListener { ... searchBar.open(); }
```

CodeIDE peut traduire les libellés en redéclarant les ressources
`cel_find_*` (préfixe du préfixe `cel_`, mécanisme T3 du §4.6).

### 4.11 Éteindre la bande d'erreurs si l'écran partagé le réclame (B11, 3.65.0) — UNE ligne, optionnelle

La bande d'erreurs et le fast-scroller sont GRATUITS à la montée
(aucune adoption). Si CodeIDE partage l'écran horizontalment avec un
autre panneau et juge les 4 dp trop coûteux :

```java
editor.setErrorStripeEnabled(false);   // default: true (parité AS)
```

Le fast-scroller (drag de gouttière) reste actif dans tous les cas —
il n'occupe l'écran que pendant le geste.

### 4.12 Poser la clé de zoom par type + offrir les numéros relatifs (B12, 3.66.0) — trois lignes, recommandé

La clé de zoom est l'adoption LA PLUS RENTABLE du lot : trois lignes
à l'endroit où CodeIDE ouvre un fichier (l'extension est la clé
naturelle — id de langage si CodeIDE préfère) :

```java
// À l'ouverture du fichier (feature/editor, là où la session est posée)
String key = extensionOf(currentFilePath);   // ".java", ".md", …
editor.setZoomMemoryKey(key);                // active la mémoire
```

À partir de là, TOUT est automatique : le pincement et les boutons
A+/A− s'enregistrent sous la clé, un fichier du même type ouvert
plus tard repart du réglage, un type jamais zoomé reste à 1. La
sémantique est « un zoom par TYPE » (pas par fichier) — le zoom du
`.md` ne fuit pas sur le `.java`. Rotation : rien à faire, l'état
d'instance B17 l'emporte (plus précis). Redémarrage de l'app : la
mémoire est un cache process — pour la survivre, persister les
paires clé/échelle dans les Settings CodeIDE et réinjecter via
`EditorZoomMemory.remember(key, scale)` au démarrage (les getters
publics `recall`/`isRemembered` sont fournis).

Les numéros relatifs sont un réglage UTILISATEUR (pas une adoption
structurelle) :

```java
editor.setLineNumberStyle(LineNumberStyle.RELATIVE);   // ou HYBRID
// import jo.codeeditor.view.chrome.LineNumberStyle;
```

CodeIDE peut l'exposer dans ses préférences d'éditeur (une
`SwitchPreference` ou un sélecteur à trois états) — le défaut
libre ABSOLUTE ne change rien pour les utilisateurs existants.

### 4.13 Installer l'item ✥ Move de la pill (B13, 3.67.0) — trois lignes, recommandé

L'installation de l'item suit le SPI habituel des actions socle
(pattern T2 — même appel que Duplicate/Comment, retirable par
identifiant `builtin.blockdrag`) :

```java
// Là où CodeIDE configure sa pill (feature/editor)
editor.addSelectionAction(
        BuiltinSelectionActions.blockDrag(editor));
// import jo.codeeditor.view.BuiltinSelectionActions;
```

À partir de là : l'utilisateur sélectionne, ouvre la pill (appui
long), tape ✥ Move, puis GLISSE — le bloc suit le doigt (loupe +
caret fantôme + carte), le relâchement DÉPOSE (une annulation), un
tap sans mouvement annule. L'item apparaît entre les actions
injectées existantes ; sur un viewport étroit, il bascule dans le
débordement ⋯ du menu unifié comme les autres (aucune largeur
garantie). Sans l'item, `view.armBlockDrag()` reste accessible
pour un bouton de barre d'outils dédié, et `session.moveRange`
pour un déplacement programmatique.

### 4.14 Offrir « Replier au niveau N » + tout replier/déplier (B14, 3.68.0) — trois entrées de menu, recommandé

Les commandes de pliage de masse se posent où CodeIDE a déjà son
menu de débordement (l'appel est direct sur la session, un simple
`invalidate()` rafraîchit) :

```java
// feature/editor — handlers de menu
editor.getSession().collapseFoldsAtLevel(2); // 1=classes, 2=méthodes…
editor.getSession().expandAllFolds();        // « tout déplier »
editor.invalidate();                          // rafraîchit la vue
```

Le retour compté permet un toast discret (« 12 méthodes repliées »).
Les régions `// region` / `#region` sont déjà détectées — aucun
réglage à faire pour elles ; le menu « Plis » de la démo
(niveaux 1-3 + tout replier + tout déplier) est le modèle exact.

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
17. **Retour n°5 (3.62.0, via la démo)** — trois vérifications ciblées :
    **a)** retour à la ligne COCHÉ (défaut de la démo) → ouvrir
    l'échantillon Markdown (long) → le rendu est PROPRE jusqu'en bas
    (aucune ligne empilée) ; changer d'échantillon (Java → Markdown →
    HTML) plusieurs fois → chaque rendu reste propre ; l'épreuve
    échoue SANS le correctif (lignes du bas superposées) ;
    **b)** ouvrir l'aperçu Markdown, basculer en PLEIN (bouton ⤢ de
    l'en-tête) → la page se RÉ-AJUSTE à la pleine largeur (plus de
    zoom hérité du mode scindé) ; PINCER la page → elle zoome et se
    dézoome au doigt (aucun bouton +/− n'apparaît) ;
    **c)** feuille d'aperçu ouverte en SCINDÉ → tap dans la ZONE
    ÉDITEUR (à gauche) → la feuille se referme ET le caret se pose là
    où l'on a tapé ; idem carte aller-à-la-ligne (menu clavier
    Ctrl+G) → tap hors carte → fermeture ; complétion visible → tap
    ailleurs → fermeture (comportement historique conservé).
18. **Retour n°6 (3.63.0, via la démo)** — quatre vérifications ciblées :
    **a)** appui long sur un mot près du HAUT de l'écran, glisser
    jusqu'au bord BAS et MAINTENIR (l'auto-scroll s'engage) → le
    texte défile vers le bas de façon FLUIDE et RÉGULIÈRE, les lignes
    ne « secouent » plus (l'épreuve échoue sans le correctif : le
    texte tremble verticalement en dent de scie) ; la loupe pendant
    le drag montre des lignes STABLES (le contenu de la bulle ne
    tremble pas) ;
    **b)** poser le caret quelque part de visible, PINCER pour zoomer
    puis dézoomer, relever UN doigt puis l'autre → le caret est
    RESTÉ à sa position écran (pendant le pincement ET après : le
    doigt restant ne fait pas défiler l'éditeur) ; répéter en sens
    inverse (dézoom puis zoom) → aucune dérive (retour au même
    endroit) ;
    **c)** ouvrir l'échantillon Markdown → la GOUTTIÈRE montre
    l'icône ŒIL sur la rangée du caret (à la place du numéro,
    façon Android Studio) → TAP dessus → la feuille d'aperçu s'ouvre
    et l'icône s'allume de l'accent → TAP à nouveau → la feuille se
    referme ; le caret n'a PAS bougé ; sur un échantillon Java
    (non prévisualisable) → aucune icône ;
    **d)** lire `download/ANALYSE-GAPS-ANDROID-STUDIO.md` — la
    sixième analyse (angle édition tactile, écarts P0/P1/P2, feuille
    de route B10+) pour décider du prochain lot.
19. **Lot B10 (3.64.0, via la démo)** — quatre vérifications ciblées :
    **a)** menu « ⋯ » → **Rechercher dans le fichier** → la barre
    apparaît entre le fil d'Ariane et l'éditeur, le clavier s'ouvre
    sur le champ → taper `class` (échantillon Java) → TOUTES les
    occurrences se surlignent, le compte tourne (ex. « 1/12 ») ; le
    viewport saute à la première occurrence à partir du caret ;
    **b)** taper Enter (action clavier « Rechercher ») puis les
    chevrons ▲▼ → la courante avance/recule et BOUCLE (dernière →
    première), le caret suit le début de la courante, la pill de
    sélection n'apparaît PAS ; basculer « Aa » (casse) et « .* »
    (regex) → le compte change en direct (ex. regex `Cl\w+`) ;
    **c)** bascule remplacer (⇄) → la rangée de remplacement apparaît
    → taper `Klass` → « Remplacer » : l'occurrence courante change,
    la courante AVANCE automatiquement ; « Tout » → tout change en
    une fois, UNE seule annulation (annuler via le bouton ↶ du fil
    d'Ariane restaure le document ENTIER) ;
    **d)** quatre taps RAPIDES au même endroit dans le texte → la
    cascade sélectionne mot → ligne → PARAGRAPHE (bloc de lignes
    non vides) ; un 5e tap reste au paragraphe ; fermer la barre
    (✕) → le surlignage disparaît, la requête survit (réouverture :
    la recherche retrouve ses occurrences sans re-frapper).

20. **Lot B11 (3.65.0, via la démo)** — quatre vérifications ciblées :
    **a)** échantillon Java diagnostiqué (celui du retour D2) → la
    bande d'erreurs apparaît au bord DROIT (marqueurs
    rouge/jaune/bleu + rectangle du viewport) ; un marqueur en BAS
    de l'écran (erreur hors viewport) → TAP dessus → la ligne se
    CENTRE et la fiche de diagnostic s'ouvre (parité point de
    gouttière) ;
    **b)** dans la GOUTTIÈRE (zone des numéros), poser le doigt et
    GLISSER vers le bas au-delà de quelques pixels → le POUCE
    apparaît au bord droit avec sa bulle de numéro de ligne, suit
    le doigt, et le document atteint N'IMPORTE QUELLE position en
    proportion (sur un fichier long, un seul geste traverse tout
    le document) ; relâcher → fondu de disparition, PAS de fling ;
    un simple TAP dans la gouttière (point de diagnostic) reste un
    tap — le pouce n'est jamais apparu ;
    **c)** drag VERTICAL sur la bande elle-même → même positionnement
    absolu (pouce + bulle) ; tap sur la bande là où il n'y a PAS de
    marqueur → rien ne se passe (ni caret ni fiche — le geste est
    consommé par la bande) ;
    **d)** ouvrir la feuille d'aperçu d'un Markdown (œil de
    gouttière) → la bande DISPARAÎT (le bord droit appartient à
    l'aperçu) ; la refermer → la bande revient.

21. **Lot B12 (3.66.0, via la démo)** — quatre vérifications ciblées :
    **a)** échantillon Java → PINCER pour régler le zoom (ex. 150 %)
    → changer d'échantillon (menu langage, ex. Markdown) → le zoom
    REPART DE 1 (le zoom de Java ne fuit pas sur le Markdown) ;
    revenir à Java → 150 % RESTAURÉ ;
    **b)** avec le Java à 150 %, A+/A− du fil d'Ariane → changer
    d'échantillon et revenir : le réglage est bien le DERNIER état
    (les boutons s'enregistrent comme le pincement) ; faire tourner
    l'appareil → le zoom de la vue est conservé EXACT (état
    d'instance, plus précis que la mémoire du type) ;
    **c)** menu « Numéros de ligne » → « Relatifs au caret » : le
    numéro de la ligne du caret devient 0, ceux autour affichent la
    distance (1, 2, 3…) — déplacer le caret de quelques lignes → les
    distances se recalculent à la volée ; « Hybrides » : le caret
    garde son NUMÉRO réel, les autres lignes gardent la distance ;
    « Absolus » : retour exact au comportement historique ;
    **d)** fermer/rouvrir la démo (process tué, vue NEUVE) → le zoom
    Java mémorisé au a) n'est PAS restauré (la mémoire est un cache
    process, pas une persistance disque — comportement documenté ;
    l'hôte qui veut la survivance la réinjecte depuis ses Settings,
    §4.12).

22. **Lot B13 (3.67.0, via la démo)** — quatre vérifications ciblées :
    **a)** échantillon Java → double-tap un mot (pill) → item
    « ✥ Move » → GLISSER le doigt : la loupe s'ouvre AU-DESSUS, la
    CARTE du bloc suit SOUS le doigt, le caret fantôme (barre
    accentuée épaisse) marque le point de dépôt — relâcher plus
    bas : le texte a DÉMÉNAGÉ et la sélection l'épingle
    (surlignage sur le bloc déplacé) ;
    **b)** immédiatement après le dépôt : UN SEUL appui sur
    annuler (fil d'Ariane) → le document revient EXACT (une seule
    étape d'annulation) ;
    **c)** armer ✥ Move puis TAPER simplement (sans glisser) → le
    mode s'annule, le document est intact, la sélection d'origine
    est toujours là ; armer puis déposer DANS la sélection
    d'origine → no-op (rien dans l'historique d'annulation) ;
    **d)** armer ✥ Move, glisser en maintenant le doigt dans le
    BORD BAS de l'écran → le document défile tout seul
    (auto-scroll), le caret fantôme suit le contenu défilé ;
    relâcher → le dépôt atterrit à l'endroit VISÉ (parité
    drag-select). Bonus : armer puis pincer à deux doigts → le
    mode retombe proprement (document intact, zoom appliqué).

23. **Lot B14 (3.68.0, via la démo)** — quatre vérifications ciblées :
    **a)** échantillon Java → menu « Plis » → « Replier au niveau 1
    (classes) » : la classe Calculator se replie (chip visible, le
    reste du document reste lisible) ; chevron de la bande → la
    classe se déplie ;
    **b)** « Replier au niveau 2 (méthodes) » : les MÉTHODES se
    replient (chips `add`, `withTax`…) mais la classe reste
    OUVERTE — déplier la classe (niveau 1 d'abord replié) révèle
    les méthodes ENCORE repliées (états indépendants) ;
    **c)** la RÉGION « Opérations » de l'échantillon Java : son
    chevron la replie et la chip porte le nom « Opérations » (pas
    de caractère parasite) ; « Tout replier » puis « Tout déplier »
    → retour intégral ;
    **d)** échantillon Python → le code `# region` non espacé
    (C#) et `//region` (IntelliJ) se replient aussi si présent —
    parité de marqueurs (vérifiable sur tout échantillon les
    contenant).

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
