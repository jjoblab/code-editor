# Rapport — Édition tactile et contexte visuel : `code-editor` face à Android Studio (lot 2)
## Référence : sticky scrolling, inlay hints, barre de sélection, loupe, popup de diagnostic

> **Demande** : nouvelle analyse d'écart entre `code-editor` et Android Studio,
> portant sur cinq familles **non couvertes par le rapport « quick actions »
> (lot 1, livré en 3.50.0 → 3.51.1)** : le sticky scrolling (en-têtes collés en
> haut pendant le scroll), l'amélioration des inlay hints, les options de la
> barre flottante qui apparaît quand l'utilisateur sélectionne, la loupe de
> zoom pendant les glissements, et — demande explicite — **un popup qui
> affiche les détails d'un diagnostic avec des options quand l'utilisateur
> tape sur le mot ou le caractère souligné (squiggle)**, en complément de la
> fiche diagnostic existante.
> **Livrable** : les modifications à apporter **au dépôt `code-editor`** pour
> combler les manques, priorisées et mesurables.
>
> **Versions auditées** : `code-editor` branche `main` @ `8860c01` (3.51.1
> publié sur JitPack, lot 1 complet) ; application de démonstration
> embarquant l'analyseur factice `DemoAnalyzer`.
> **Contraintes héritées** : Java pur, minSdk 24, pas de rupture d'API
> publique (le SPI et la surface `EditorView` consommés par CodeIDE restent
> compatibles binairement), garde-fous verts (figures de tests, budgets de
> lignes, baseline lint), un commit conventionné par changement logique.
> **Statut** : **jalons J1 à J6 LIVRÉS (lot complet, 3.52.0 → 3.57.0),
> PLUS les retours terrain post-J6 (3.58.0, 3.59.0, 3.61.0) ET la
> refonte B6 (3.60.0)** — B6 (3.60.0) : la barre de fil d'Ariane ne porte plus
> QUE les trois actions d'IDE via `addEditorActions()` (annuler/
> rétablir câblés sur la session, aperçu qui n'EXISTE que pour un
> fichier prévisualisable et BASCULE ouvrir/fermer), icônes
> vectorielles dessinées au Canvas (`EditorIcons` — plus de
> glyphes texte/emoji dont le rendu varie par appareil), feuille
> d'aperçu « carte flottante » (élévation 12 dp, coins arrondis,
> en-tête œil + nom, segmented control scindé/plein), miroirs
> automatiques (setFileName/setPreviewMode rafraîchissent la barre
> sœur), moteur Markdown v2 dans la démo (liens, tables, tâches,
> barré) ; 1 618 tests verts, EditorView/EditorSession inchangés.
> **Retour terrain n°4 (3.61.0)** : six irritants réglés à la racine —
> B7a les boutons annuler/rétablir de la barre réagissent enfin à la
> FRAPPE (le rafraîchissement vivait sur le hook hôte, pas sur le
> chemin IME — « ils ne font rien quand j'appuie ») ; B7b l'auto-scroll
> du drag de sélection devient aussi HORIZONTAL (bords gauche/droite,
> coin = diagonale, inerte en retour à la ligne) ; B7c l'appui long ne
> vole plus le geste des popups (garde `popupOwnsTouch` — maintien dans
> la complétion ou sous le scrim modal ne démarre plus de sélection
> pour drag) ; B7d l'aperçu est « fit » (HTML rendu BRUT — la démo
> l'échappait via le Markdown, zoom de police système verrouillé,
> coins de la carte clippés, position de lecture préservée à la frappe)
> ; B7e échantillon HTML dédié dans la démo (« XML / HTML » redevient
> « XML ») ; B7f fondus fluides des popups (`EditorPopupFade` Canvas +
> `EditorPopupMotion` fenêtres — le mécanisme animatorie du caret
> généralisé) ; 1 640 tests verts (+22).
> UX-4
> (3.58.0) : le CONTENU du popup ancré de diagnostic suit la carte
> (bug d'appareil : cadre à droite, contenu resté au bord gauche ; la
> métrique porte désormais `contentLeft`, source unique pour le dessin
> du détail ET de la mini-liste) ; B5 (3.58.0) : la barre de fil
> d'Ariane porte la zone d'actions d'éditeur en haut à droite
> (`BreadcrumbBar.addAction` / `addPreviewAction` +
> `EditorView.setPreviewIconsVisible` — relocalisation des icônes
> d'aperçu), la démo prouve le motif complet
> (annuler/rétablir/étendre/A+/A−/œil + hôte Markdown WebView) ;
> UX-5 (3.59.0) : retrait des zones de tap FANTÔMES de l'ancienne
> toolbar canvas — un tap en haut à droite déclenchait zoom/dézoom ou
> bascule ¶/ligatures sans aucun bouton visible (hit-test survivant,
> orphelin de rendu depuis l'extraction d'EditorBarTools) ; le tap y
> redevient un placement de caret, la méthode morte est supprimée,
> test de régression 5/5. J1 a
> implémenté les deux P0 : **D1** (popup ancré au tap sur le
> squiggle : bords ±1 exclus, parité caret par re-tap, mini-liste
> multi-diagnostics, quick-fixes résolus à l'ouverture, « See all »
> vers la fiche basse) et **T1** (SPI
> `addSelectionAction`/`removeSelectionAction`, métrique dynamique,
> débordement « ⋯ » → section « MORE » du NavMenu). J2 « la lieue
> visuelle » (3.53.0) a livré **S1** (cache de détection sticky par
> révision — zéro scan et zéro allocation par frame de scroll,
> invalidation automatique à l'édition et au changement de langue),
> **S2** (lignes collées rendues stylées par le pipeline de la ligne
> visible, inlays exclus, ellipse « … » au-delà du viewport) et
> **M1** (la loupe montre la bande de sélection, le squiggle du
> diagnostic et la barre de caret — peintres du pipeline principal,
> mêmes géométries wrap/inlay/plis). J3 « les inlays parlent, la
> main défile » (3.54.0) a livré **I1** (le kind du hint traverse le
> pont — paramètres en faux italique plus effacés, types droits,
> parité IntelliJ) et **I2** (tooltip : SPI `resolveHint` paresseux,
> `inlayHint/resolve` si annoncé, appui long sur la plage tissée →
> quick doc du tooltip, zéro nouveau chrome), PLUS trois correctifs
> du retour terrain du propriétaire sur l'APK démo 3.53.0 : **UX-1**
> (un seul « ⋯ » sur la pill — l'icône Actions ouvre le menu unifié
> qui inclut la section MORE ; l'item de débordement T1 est retiré),
> **UX-2** (auto-scroll directionnel du drag de sélection — zone de
> bord 56 dp, vitesse proportionnelle à la pénétration, la sélection
> est ré-appliquée sous le doigt à chaque tick) et **UX-3** (le
> popup D1 se resserre sur son contenu, bornes [132, 320] dp — il
> était toujours à la largeur maximale). 58 tests dédiés sur les
> trois jalons (SquiggleTap\*, SquigglePopupWidth,
> SelectionToolbar\*, SelectionDragAutoScroll, StickyDetectCached,
> StickyStyledRender, MagnifierShows\*, InlayKindStyling,
> InlayTooltipLongPress), J4 « la pill agit, la bande navigue »
> (3.55.0) a livré **T2** (actions intégrées socle de la pill via le
> SPI T1 : Duplicate, Comment par syntaxe de langage, Convert case
> UPPER/lower/Toggle, Share opt-in) et les P2 à consommateur immédiat
> : **S3** (tap sur une rangée collée → la ligne d'origine est
> centrée dans le viewport, via la source unique `activeStickyLines`
> partagée rendu + hit-test), **S4** (`setStickyMaxLines` 1-5, défaut
> 2, refus propre hors plage) et **D3** (appui long sur un squiggle →
> message du diagnostic copié au presse-papiers, geste consommé sans
> sélection). 79 tests dédiés sur les quatre jalons (+
> DuplicateAction, ToggleCommentAction, ConvertCaseAction,
> ShareAction, StickyTapNavigates, StickyMaxLines,
> SquiggleLongPressCopy), 1 508 tests verts ; aucun relèvement de
> plafond en J4 (la marge J3 a suffi : 3 456 l. / 172 champs / 260
> méthodes publiques sous 4 100/190/280). J5 « la loupe est un
> instrument, l'en-tête parle » (3.56.0) a livré **T4** (la pill suit
> la borne EN DÉPLACEMENT pendant le drag — poignée ou drag-select au
> doigt — `sel.end` au repos), **M2** (`setMagnifierZoom` ×1.5–×3,
> clamp documenté, défaut ×2), **M3** (loupe native opt-in API 28+
> (`setNativeMagnifierEnabled`), repli custom en dessous, bulle
> refermée aux cinq sites d'extinction), **D2** (géométrie ancre +
> flip + clamp factorisée dans `EditorPopupAnchors.
> anchorAboveBelow`, consommée par complétion/NavMenu/popup squiggle —
> reproduction exacte), **D4** (en-tête enrichi : code et source LSP
> transportés jusqu'au rendu et préservés par le décalage, ligne:col
> 1-based, badge ×N sur plage exacte) et **T3** (premier
> `res/values` de cel-ui — 20 libellés canoniques en ressources,
> défauts anglais identiques, l'hôte traduit en redéclarant les mêmes
> noms). 102 tests dédiés sur les cinq jalons (+ ToolbarFollows
> DraggedEdge, MagnifierZoomBounds, MagnifierNativeToggle,
> MagnifierNativeDecision, DiagnosticHeaderFields, CanonicalLabels
> Resources, +2 DiagnosticShift), 1 531 tests verts ; aucun
> relèvement de plafond en J5 (3 562 l. / 174 champs / 266 méthodes
> publiques sous 4 100/190/280). J6 « le lot est complet » (3.57.0) a
> livré le RESTE des P2 : **I3** (labelParts conservées — tooltip PAR
> PART à l'appui long, invariant concat == texte tissé, padding en
> parts de blanc), **I4** (inlays cliquables — `command` au tap AVANT
> le caret, command LSP de la part → `workspace/executeCommand`
> asynchrone), **I5** (fenêtre viewport ± 4 lignes + re-requête au
> scroll idle ~200 ms par drapeau `wantsViewportRange`, défaut
> document entier — plus le correctif du rafraîchissement initial
> avant attachement), **S5** (détection sticky déléguée au SPI langue
> `isBlockHeaderLine`/`stickyHeaderTest`, tables par défaut
> inchangées) et les **mouvements de lignes** (P2 — `moveSelectedLines`
> bloc multi-lignes IntelliJ/VS Code, sélection ré-épinglée, la queue
> vide ne compte pas ; actions pill `moveLinesUp/Down` via le SPI T1 ;
> implémentations extraites dans `LineOperations` pour tenir le
> plafond EditorSession SANS relèvement — 1 546 l. / 1600). 36 tests
> dédiés (StickySpiDelegate, InlayLabelParts, InlayPartTooltip,
> InlayClickCommand, InlayViewportRange, MoveSelectedLines,
> MoveLinesAction), 1 578 tests verts ; aucun relèvement de plafond.
> LE LOT 2 EST COMPLET — tous les chantiers du plan sont livrés, ce
> document reste la référence du livré.
> Conformément à la convention établie avec le rapport du lot 1, ce document
> est archivé dans `docs/` et remis en copie à l'extérieur du dépôt ; la
> règle d'interdiction des noms externes (`AGENT.md` §93) s'applique au code,
> aux commits et aux messages — les rapports d'audit de cette série citent
> les références par leur nom (précédent accepté : `docs/` lot 1, CI verte).

---

## 1. Résumé exécutif

**Verdict : le lot 1 traitait une perte de données protocole (les quick-fixes
qui mouraient dans le pont LSP) ; le lot 2 traite une dernière lieue
tactile.** Les cinq familles existent déjà, certaines à un niveau
remarquable — la loupe est isolée, testée et proche du comportement
plateforme ; le sticky scrolling a un détecteur livré ; la barre de
sélection a six actions câblées avec animation d'entrée ; les inlay hints
ont un SPI complet avec tissage dans la ligne ; les diagnostics ont fiche,
liste groupée et chips. Ce qui manque est précis, et correspond presque
mot pour mot aux demandes formulées :

1. **D1 — Le squiggle n'est pas tappable** (retrait volontaire, documenté
   dans `EditorTapResolver` l.298-307 : « un tap sur la plage ondulée place
   simplement le caret »). Le geste le plus naturel du mobile — *je tape sur
   le mot souligné rouge* — ne déclenche rien de spécial. Et le « popup de
   détail » qui existe n'en est pas un : `diagnosticSheetMetrics`
   (`EditorPopupAnchors` l.105) docke le panneau en **bas de l'écran**
   (`panelTop = height − sheetH`), en fenêtre modale avec scrim — là où la
   référence montre un **tooltip ancré au-dessus de la plage en erreur**,
   non modal, avec message et liens de correction.
2. **T1 — La barre de sélection est un pilier fermé de six actions**
   (Copy/Cut/Paste/Select all/Docs ℹ/Actions ⋯ — `SEL_ACT_*`
   l.876-886, capacité figée à 8 slots, un seul divider, libellés anglais
   codés en dur l.271-278). **Aucun point d'extension hôte**, aucune des
   actions d'édition de code attendues d'un éditeur sérieux (commenter,
   dupliquer, changer la casse, déplacer des lignes) — alors que le moteur
   possède déjà certaines primitives (folds, surround with v3.4x).
3. **M1 — La loupe montre le texte mais pas ce qu'on règle** : le redessin
   ×2 (±3 lignes, texte stylé, inlays tissés — `drawMagnifier`
   l.346-364) n'inclut **ni la bande de sélection, ni le caret, ni le
   squiggle**. La borne exacte que l'utilisateur positionne en glissant la
   poignée est invisible dans la bulle : il règle à l'aveugle.
4. **S1 — Sticky scrolling : deux dettes structurelles.** (a) La détection
   **rescanne le document entier à chaque frame de rendu**
   (`EditorRenderer` l.411-413 appelle `view.detectStickyLines(doc)` dans
   la passe de dessin ; `StickyLineDetector.detect` boucle sur toutes les
   lignes avec `subSequence` + `trim` + `startsWith` par ligne — au scroll
   à 60 fps, un fichier de 5 000 lignes = 300 000 scans/seconde +
   allocations). (b) Le rendu est du **texte brut monochrome**
   (`drawStickyLineText` l.565-589, `guidePaint`, cap 120 caractères, pas
   de coloration syntaxique, pas de retour à la ligne) — la référence
   affiche la *vraie* ligne.
5. **I1 — Inlay hints : le kind meurt au pont.** Le modèle SPI transporte
   un `kind` (« type » / « parameter »), mais la conversion du pont
   (`EditorLanguageBridge` l.417-423) le **jette** en recopiant dans le
   modèle interne à deux champs (`offset`, `text`). Rendu uniforme
   monochrome pour tous les genres ; pas d'`inlayHint/resolve` (tooltip),
   labelParts aplaties en une chaîne, aucune interaction, requête portant
   sur le document entier.

Le plan priorisé (§8) : **2 chantiers P0** (popup ancré au tap squiggle —
la demande explicite ; SPI d'extension de la barre de sélection — la
seconde demande explicite), **5 chantiers P1** (bande de sélection dans la
loupe ; cache du sticky ; rendu stylé du sticky ; kind des inlays jusqu'au
rendu ; resolve/tooltip des inlays), **9 chantiers P2**. Tout est additif —
aucune signature publique existante ne change ; la fiche bas d'écran et la
liste groupée restent les vues « tout voir ».

---

## 2. Méthode et sources

**Code audité** (branche `main` @ `8860c01`, module `cel-ui` sauf mention) :

| Famille | Fichiers clés |
|---|---|
| Sticky | `view/StickyLineDetector.java` (133 l.), `view/render/EditorRenderer.java` l.409-589, `view/EditorView.java` l.1655-1679 |
| Inlays | `cel-lsp-api/.../lang/model/InlayHint.java`, `cel-lsp-api/.../lang/provider/InlayHintProvider.java`, `cel-lsp/.../LspInlayHintProvider.java` (121 l.), `view/EditorLanguageBridge.java` l.379-435, `cel-core/.../session/SessionAnnotations.java`, `view/render/EditorTextPainter.java` l.422-470 |
| Barre de sélection | `view/input/EditorSelectionGestures.java` (303 l.), `view/popup/EditorPopupAnchors.java` l.248-392, `view/EditorView.java` l.876-886, `view/input/EditorTapResolver.java` l.331-378 |
| Loupe | `view/render/EditorChromePainter.java` l.288-409, `view/input/EditorSelectionGestures.java` l.130-183 |
| Diagnostics | `view/input/EditorTapResolver.java` (cascade entière), `view/popup/EditorDiagnosticPopups.java` (109 l.), `view/popup/EditorPopupAnchors.java` l.77-160, `view/popup/EditorPopupHitTester.java` l.150-176, `view/render/EditorDiagnosticsPainter.java` |

**Tests existants inspectés** (le existant est déjà bien gardé — le lot 2
s'inscrit dedans) : `EditorSelectionToolbarTest`, `EditorMagnifierHandleDragTest`,
`MagnifierBoundsTest`, `MagnifierPaintIsolationTest`, `MagnifierStateFallbackTest`,
`MagnifierColorLeakReproTest`, `SelectionColorAfterMagnifierTest`,
`EditorDiagnosticsInlaysTest`, `EditorBracketSheetTapTest`,
`EditorPendingTapDismissTest`, `SingleTapNoSelectionTest`.

**Références de comportement** : Android Studio (fond IntelliJ) pour les
comportements *desktop* (sticky lines 2023.1+, inlay hints cliquables et
configurables, floating toolbar sur sélection, hover diagnostic) ;
plateforme Android pour les conventions *tactiles* (pill système de
sélection Cut/Copy/Paste/Select all/Share ; widget `Magnifier` API 28+).
La règle d'effort est celle du lot 1 : **S** (≤ ~150 lignes), **M**
(150-400 lignes), tout commit avec tests rouge→vert, `check-external-refs`
vert, figures de tests et budgets de lignes recalés.

---

## 3. Sticky scrolling — l'existant, la référence, l'écart

### 3.1 État réel (livré en v3.44, inchangé depuis)

**Détection — `StickyLineDetector.java`.** Une ligne est sticky si elle
ouvre un bloc *et* commence par un mot-clé d'en-tête : accolade ouvrante
plus préfixe parmi ~35 mots-clés pour la famille C_LIKE
(`class`, `fun`, `void`, `override`, `suspend`…), deux-points plus préfixe
parmi 11 mots-clés pour Python (`def`, `class`, `if`, `async def`…).
Les lignes de plus de 200 caractères sont ignorées, les familles autres
que C_LIKE/PYTHON n'ont pas de sticky du tout, et la limite est de
**2 lignes simultanées** (classe + méthode) par décision mobile assumée
(javadoc l.36-38). C'est une heuristique de *préfixe*, pas une lecture de
l'arbre : une signature Kotlin multi-lignes ne colle que sa première
ligne, un `else {` C_LIKE (pas dans la table) ne colle jamais, et un
commentaire commençant par `public` serait accepté.

**Rendu — `EditorRenderer.drawStickyLines` (l.501-558).** Les en-têtes
scrollés au-dessus du viewport sont repeints en haut, empilés, fond opaque
couvrant tout le viewport, séparateur inférieur de 1 px, numéro de ligne
redessiné dans le gouttière (bien), texte **brut** via `guidePaint`
(l.565-589) : couleur unique du texte, cap à 120 caractères sans ellipse,
pas de coloration syntaxique, pas de retour à la ligne, pas de gestion du
zoom horizontal au-delà du `hOffset` déjà appliqué. Les lignes repliées
sont correctement exclues (`isLineFoldedCached` l.523).

**Deux problèmes concrets.** (1) `EditorRenderer` l.411-413 appelle
`view.detectStickyLines(doc)` **dans la passe de dessin, à chaque frame** :
`detect` n'a aucun cache (le champ `stickyLineDetector` est un simple
détecteur sans état), donc chaque frame de scroll paie un scan intégral du
document avec allocation d'une `String` par ligne (`subSequence().toString()`
l.77) — c'est le genre de coût invisible en démo (300 lignes) et
rédhibitoire en fichier réel (5 000+ lignes). (2) Le rendu monochrome casse
la promesse visuelle : dans la référence, la ligne collée est *la même
ligne*, coloration comprise — ici elle est reconnaissable mais
graphiquement appauvrie, et une longue signature est tronquée à 120
caractères sans aucun marqueur.

**Activation** : `stickyLinesEnabled = true` par défaut, setter public
`setStickyLinesEnabled` (l.1667) — aucune entrée ne l'expose dans la démo
(les toggles de `MainActivity` couvrent wrap/minimap/ligatures/¶/thème,
pas le sticky).

### 3.2 La référence (Android Studio / IntelliJ 2023.1+)

Les sticky lines d'IntelliJ sont calculées sur **l'arbre syntaxique** (toute
langue ayant des folds en profite, sans table de mots-clés), rendues comme
les vraies lignes (**coloration syntaxique, numéros réels, retour à la
ligne**), **cliquables** (un clic défile jusqu'à la ligne d'origine et la
place au premier plan), avec un **maximum configurable** (défaut 5,
réglable par éditeur), une légère transparence/voile plutôt qu'un
recouvrement opaque, et une **emphase de la portée portant le caret** (la
ligne sticky qui contient le scope courant est mise en avant par rapport
aux ancêtres plus lointains).

### 3.3 Écarts et chantiers

**S1 (P1, S) — Cache de détection.** Mémoïser `[révision du document,
lineCount] → liste sticky` (le document expose déjà une révision par
édition ; le pliage est déjà lu à part). Invalider sur édition, pas sur
scroll — le scroll ne change *jamais* la liste. Test :
`StickyDetectCachedTest` — un même `detect` rendu N frames ne scanne
qu'une fois (compteur d'appels ou instrumentation du détecteur) ; une
édition invalide. Effet mesurable : zéro allocation par frame au scroll
sur fichier long.

**S2 (P1, S) — Rendu stylé de la ligne collée.** Réutiliser le pipeline
existant de la ligne visible (`getStyledLines` + `drawStyledLine`, déjà
utilisé par la loupe l.353-359 !) au lieu du `guidePaint` brut : coloration
syntaxique, inlays exclus (une ligne collée n'est pas le lieu des hints),
ellipse « … » au-delà de la largeur, retour à la ligne désactivé (tronqué,
cohérent mobile). Test : `StickyStyledRenderTest` — un token de la ligne
collée est peint avec sa couleur de syntaxe (assertion sur le paint),
l'ellipse apparaît pour une ligne plus large que le viewport.

**S3 (P2, S) — Tap sur ligne sticky → navigation.** Hit-test dans la bande
sticky (hauteur = nb lignes × lineHeight depuis `paddingTop`) dans la
cascade du `EditorTapResolver`, AVANT le placement du caret : tap →
`scrollToLine` (centrer la ligne d'origine). Cohérent avec le comportement
référence ; l'anti-faute-de-frappe est la hauteur limitée (≤ 2 lignes).
Test : `StickyTapNavigatesTest` — tap sur la bande avec 1 ligne sticky
active → `vOffset` cible la ligne ; sans sticky actif, le tap reste un
placement de caret.

**S4 (P2, S) — Maximum configurable.** `setStickyMaxLines(int)` (1-5,
défaut inchangé = 2 pour ne pas régresser la décision mobile) + getter.
Documenter que 3-5 est tenable sur tablette. Test : borne appliquée au
détecteur/render, refus propre hors plage.

**S5 (P2, M) — Délégation de la détection au SPI langue.** Ajouter
`LanguageProfile.isBlockHeaderLine(CharSequence line)` (défaut : tables
C_LIKE/PYTHON actuelles déplacées) pour que chaque langue décide de ses
en-têtes sans toucher au détecteur. Additif et binairement compatible
(méthode d'interface avec default). Test : une langue factice déclarant
ses propres en-têtes les voit coller ; C_LIKE inchangé.

---

## 4. Inlay hints — l'existant, la référence, l'écart

### 4.1 État réel (SPI complet, rendu monochrome, pas d'interaction)

**Chaîne complète existante.** SPI `InlayHintProvider.inlayHints(text,
startLine, endLine)` appelé sur thread de travail en débouncé après édition ;
modèle `InlayHint{offset, text, kind}` ; traduction LSP
`LspInlayHintProvider` (plage convertie, `paddingLeft/Right` honorés
l.91-96 — bon détail, libellés multi-parts assemblés, timeout 5 s,
journalisation) ; stockage session avec **décalage incrémental sur édition**
(`DiagnosticShift.shiftInlayHints`) et index par ligne mémoïsé
(`SessionAnnotations.inlayHintsForLine`) ; rendu **tissé dans la ligne**
(`drawCachedInlays` l.422-441 + épissage `rawToVisual` l.443-470 : le code
après un hint est décalé à droite de sa largeur — le vrai comportement
« texte fantôme » d'un IDE, pas une superposition) ; taille 0.85×, couleur
`gutterText` à 90 % d'alpha, faux italique (skew −0.25). Le cache de rendu
par ligne invalide proprement par révision (`inlayHintsRev`).

**Ce qui manque, précisément.** (1) **Le `kind` est jeté au pont** :
`EditorLanguageBridge` l.417-423 recopie `offset`+`text` dans le modèle
interne `DiagnosticShift.InlayHint(offset, text, enabled)` — le rendu ne
peut donc pas distinguer un nom de paramètre d'un type de variable, alors
que le SPI et le traducteur LSP le transportent déjà. (2) **Pas de
`inlayHint/resolve`** : le protocole prévoit la résolution paresseuse du
tooltip et des data — jamais appelée. (3) **labelParts aplaties** : les
parts ont chacune un tooltip/une commande potentiels, assemblés ici en
une simple chaîne (l.72-78). (4) **Aucune interaction** : pas de tap, pas
de tooltip, pas de commande. (5) **Requête plein document** : le pont
demande la plage entière (l.387-398, rationale documenté : le démon interne
calcule le fichier entier et rien ne re-requête au scroll — le choix est
assumé mais pénalisant pour un vrai serveur LSP qui, lui, honore la plage).

### 4.2 La référence (Android Studio / IntelliJ)

Inlay hints par genre avec **styles distincts** (noms de paramètres en
italique gris pâle, types de variables gris droit, hints de chaînage
discrets), **tooltips au survol** (documentation du symbole), hints
**cliquables** (Contrôle+clic pour naviguer vers le type ; les hints « … »
de chaînage ou de fold one-line s'étendent au clic), **réglages par langue
et par genre** (activer les types mais pas les paramètres), et côté
protocole : `inlayHint/resolve` + labelParts riches. La lisibilité prime :
le hint ne doit jamais pouvoir être confondu avec du code réel — c'est
déjà le cas ici (alpha, taille), c'est le principal acquis.

### 4.3 Écarts et chantiers

**I1 (P1, S) — Porter le kind jusqu'au rendu.** Étendre le modèle interne
(`DiagnosticShift.InlayHint` : champ `kind` additionnel avec valeur par
défaut « type » — constructeur existant conservé, pas de rupture), le
recopier au pont, puis styler par genre dans `drawCachedInlays` : paramètre
→ italique (skew conservé) + alpha légèrement plus faible ; type → droit +
alpha courant. Test : `InlayKindStylingTest` — deux hints de genres
différents sur une même ligne rendent avec deux styles mesurables
(skew/alpha) ; régression nulle sur les tests existants d'inlays.

**I2 (P1, M) — Tooltip des inlays (resolve + consultation).** (a) Modèle
SPI : `InlayHint.tooltip` (champ additionnel, `null` par défaut — pas de
rupture). (b) `LspInlayHintProvider` : si le serveur annonce
`resolveProvider`, appeler `inlayHint/resolve` **paresseusement** (au
premier tap, jamais au rendu) et rapporter le tooltip. (c) UI : un
**long-press sur un hint** (hit-test : la plage tissée est déjà connue du
layout de ligne) ouvre la fiche quick-doc existante avec ce tooltip —
réutilisation du pipeline `showQuickDoc`, zéro nouveau chrome. Test :
faux serveur répondant au resolve → tap-long sur le hint → quick doc
visible avec le tooltip ; sans resolve supporté → pas de requête émise
(assertion sur le faux serveur).

**I3 (P2, M) — Conserver les labelParts.** Étendre le modèle pour une
liste de parts (texte + tooltip), assemblage du texte inchangé au rendu,
tooltip de part utilisé par I2 si présent. Additif ; sans consommateur
immédiat côté démo, la valeur apparaît avec les serveurs riches.

**I4 (P2, M-L) — Inlays cliquables.** SPI : `InlayHint.command`/callback
optionnel ; tap simple sur la plage du hint → exécution (le geste doit
être résolu AVANT le placement du caret dans la cascade, comme le tap de
complétion — précédent direct dans `EditorTapResolver`). Usage type :
hints « … » d'expansion. Ce chantier n'a de sens qu'avec un serveur qui
propose des commands — le garder P2 est une décision de franchise
(démo : `DemoAnalyzer` peut brancher un hint « … » expansible).

**I5 (P2, M) — Plage visible + re-requête au scroll.** Remplacer la plage
plein document par viewport ± 4 lignes **et** re-requêter sur scroll idle
(scroll stoppé ~200 ms) via la génération latest-wins existante
(`inlayGeneration`). Attention au rationale documenté l.387-398 : le
démon interne calcule le fichier entier, la pleine plage ne le coûte pas
plus — donc ce chantier ne se justifie que pour les serveurs externes
exigeants ; le garder optionnel (par drapeau provider) est acceptable.

---

## 5. Barre de sélection — l'existant, la référence, l'écart

### 5.1 État réel (pill solide, mais fermée)

**Comportement livré.** La pill apparaît au double-tap (mot), triple-tap
(ligne), tap dans une sélection existante, re-tap sur le caret, et appui
long (`EditorTapResolver` l.331-378, `handleLongPress` l.66-89), avec une
animation d'entrée horodatée (`selectionToolbarShownAt`). Six actions :
**Copy / Cut** (si sélection non vide), **Paste**, **Select all**, puis
un divider et deux icônes **Docs ℹ** (quick-doc au début de la sélection)
et **Actions ⋯** (menu contextuel unifié GO TO / QUICK FIXES /
INTENTIONS — l'héritage du lot 1). La métrique est une source de vérité
unique partagée rendu + hit-test (`SelectionToolbarMetrics`,
capacité figée à 8 items, 1 divider), ancre `caretScreenPos(sel.end)`
(l.255), position au-dessus de la ligne sinon dessous, clamp horizontal.
Le comportement de fermeture est soigné : Copy/Cut/Paste referment la
pill et masquent les poignées, Select all la laisse ouverte
(`EditorSelectionGestures` l.243-293).

**Ce qui manque, précisément.** (1) **Aucun point d'extension** : les
actions sont un enum fermé (`SEL_ACT_*` l.876-886), l'hôte (CodeIDE, la
démo) ne peut ni en ajouter, ni en retirer, ni en griser — pour une
*bibliothèque*, c'est le vrai défaut structurel. (2) **Aucune action
d'édition de code** : pas de commenter/décommenter, pas de dupliquer, pas
de changer la casse, pas de déplacer des lignes, pas de partage — les
gestes attendus d'un éditeur mobile moderne, dont certains existent déjà
comme primitives moteur (surround with v3.4x, folds). (3) **Libellés
anglais codés en dur** (« Copy », « Select all » l.271-276) — pas
d'internationalisation alors que la pill système Android est localisée.
(4) Détail : le commentaire l.253-254 dit « la pill suit la borne ACTIVE
de la sélection » mais le code ancre **toujours** `sel.end` (l.255) —
pendant le drag de la poignée gauche, la pill reste à l'autre bout.

### 5.2 La référence

Deux registres. **Plateforme Android** (le registre tactile) : la pill
système affiche Cut / Copy / Paste / Select all / **Share** (plus les
extras OEM) — l'utilisateur mobile s'attend à ce socle. **Android Studio
desktop** (le registre éditeur) : la *floating toolbar* apparaissant sur
sélection est **configurable** (groupe d'actions paramétrable), et le menu
contextuel complet offre l'écosystème d'édition : commenter
(Ctrl+/), **changer la casse** (Ctrl+Shift+U), **dupliquer** (Ctrl+D),
**déplacer les lignes** (Ctrl+Shift+↑/↓), surround with (Ctrl+Alt+T),
formater, refactorer, chercher les usages. La leçon à retenir n'est pas
« tout mettre » — c'est que le socle est court et que le reste doit être
**injectable par l'hôte** selon la langue et le contexte.

### 5.3 Écarts et chantiers

**T1 (P0, M) — SPI d'extension de la pill.** Nouvelle API additive
`EditorView.SelectionAction { id, label, icon (optionnel), enabled
(Supplier<Boolean>), perform(Runnable) }` + `addSelectionAction` /
`removeSelectionAction` / ordre stable. Règles : les actions injectées se
placent **après** les canoniques d'édition (Copy/Cut/Paste/Select all) et
**avant** les icônes Docs/Actions ; la métrique passe de tableaux fixes à
une liste dynamique (la capacité 8 devient un plafond d'affichage) avec
**débordement « ⋯ »** — au-delà de ce qui tient, les actions suivantes
basculent dans le menu ⋯ (le NavMenu unifié existant sert d'hôte
d'overflow, zéro nouveau chrome). Test :
`SelectionToolbarSpiInjectTest` — 3 actions injectées apparaissent au
rendu, sont tappables, l'une désactivée grise et rejette le tap ;
`SelectionToolbarOverflowTest` — 10 actions → pill tronquée + entrées
débordantes dans le menu.

**T2 (P1, S-M) — Actions intégrées socle.** Livrées via T1 (l'hôte peut
les retirer) : **Dupliquer** (duplique la sélection ou la ligne si caret
— S) ; **Commenter/Décommenter** (tokens de commentaire par
`LanguageProfile` — S-M, dépend de l'exposition des tokens) ; **Casse**
(upper/lower/toggle — S, pur moteur) ; **Share** (Intent système sur le
texte sélectionné — S, opt-in car une bibliothèque ne doit pas forcer une
dépendance d'interaction système). Chaque action : un test Robolectric
(état du document après action, pill refermée). Les mouvements de lignes
(P2) attendent la décision multi-lignes du propriétaire.

**T3 (P2, S) — Internationalisation.** Migrer les libellés canoniques
vers les ressources de `cel-ui` (bibliothèque Android — les ressources
sont légitimes), valeurs par défaut identiques en anglais pour ne pas
changer les tests visuels existants.

**T4 (P2, S) — La pill suit la borne mobile.** Ancre = extrémité **en
cours de drag** pendant `dragHandle` (borne opposée à l'ancre figée),
`sel.end` au repos. Supprime le paradoxe « je tire la poignée gauche, la
pill part à droite ».

---

## 6. Loupe — l'existant, la référence, l'écart

### 6.1 État réel (le chantier le plus solide des cinq)

**Fonctionnement livré — `EditorChromePainter.drawMagnifier`
(l.300-388).** Bulle circulaire de rayon 60 dp, **zoom ×2**, centre
clampé horizontalement puis résolu verticalement **au-dessus du doigt,
sinon en dessous, sinon borné** (l.392-409 — la bulle n'est jamais coupée
par un bord), contenu = **±3 lignes autour du point de contact redessinées
avec le texte STYLÉ** (coloration syntaxique réelle via `drawStyledLine`,
inlays tissés l.353-359, lignes repliées exclues), paint **dédié et
isolé** (`magnifierPaint`, jamais le paint partagé — trois bugs de
contamination documentés et fixés), restauration explicite du style du
`selPaint` partagé en fin de passe (l.377-387). Activation : drag des
poignées de sélection **et** de la poignée de caret (`dragHandle`),
drag-select après appui long (`handleTouchDrag`), au premier MOVE (pas au
DOWN — pas de flash au tap). Suite de tests dédiée : Bounds, PaintIsolation,
StateFallback, ColorLeakRepro, HandleDrag, SelectionColorAfterMagnifier.
C'est le niveau plateforme, en mieux testé que la moyenne.

**Ce qui manque, précisément.** Le contenu redessiné est **le texte seul** :
ni la **bande de sélection**, ni le **caret**, ni les **squiggles**, ni la
**ligne courante**. Or la loupe sert *précisément* à régler la borne d'une
sélection ou la position du caret entre deux caractères — l'objet de la
manœuvre est invisible dans l'instrument de visée. C'est le seul écart
réellement fonctionnel ; le reste est du raffinement.

### 6.2 La référence

Android Studio (desktop) n'a pas de loupe — le registre est celui de la
**plateforme Android** : depuis l'API 28, le widget `Magnifier` copie le
**vrai rendu de la vue** depuis la surface (la sélection, les poignées, les
overlays apparaissent dedans), avec zoom réglable ; avant l'API 28, aucune
loupe système n'existe — une implémentation custom est la seule option,
ce que ce dépôt (minSdk 24) a fait et bien fait. La « décision
propriétaire » notée dans le suivi du lot 1 (magnifier natif API 28+)
reste ouverte : le natif rend *tout* l'écran mais retire la maîtrise du
contenu et du style ; le custom rend *exactement ce qu'on décide* et
supporte l'API 24. **Recommandation : garder le custom par défaut**
(cohérence visuelle, contenu maîtrisé, tests), exposer le natif en
opt-in (M3).

### 6.3 Écarts et chantiers

**M1 (P1, S-M) — Montrer ce qu'on règle dans la bulle.** Dans le redessin
de la loupe, ajouter par ligne : la **bande de sélection** (rect de la
plage sélectionnée sur la ligne, sous le texte), le **caret** (barre, si
la ligne le porte), et le **squiggle** du diagnostic couvrant la ligne
(sous le texte, même peinture que `EditorDiagnosticsPainter`). Les ordres
de peinture respectent le pipeline principal. Test :
`MagnifierShowsCaretTest` — drag de la poignée de caret : la frame de
loupe contient un rect/une barre aux coordonnées zoomées attendues
(assertion sur le canvas fantôme) ; idem bande pour un drag de sélection.
C'est le chantier qui transforme « joli zoom » en « instrument de réglage ».

**M2 (P2, S) — Zoom réglable.** `setMagnifierZoom(float)` (plage
×1.5–×3, défaut ×2 inchangé), appliqué au `canvas.scale` et documenté
pour la lecture (grands caractères). Test : borne + valeur appliquée.

**M3 (P2, M) — Loupe native en opt-in (API 28+).** Drapeau
`setNativeMagnifierEnabled(true)` : sur API ≥ 28, utiliser le widget
plateforme (rendu réel complet, y compris overlays) ; en dessous et par
défaut, la loupe custom actuelle. Documenter le compromis (style non
maîtrisé, la bulle suit les règles système) dans la javadoc. Test :
Robolectric ne peut pas instancier le Magnifier natif — tester la
**bascule** (API < 28 → custom actif ; drapeau à faux → custom actif).

---

## 7. Popup de diagnostic au tap sur le squiggle — l'existant, la référence, l'écart

### 7.1 État réel (riche, mais indirect : tout sauf le geste naturel)

**La cascade actuelle (`EditorTapResolver`).** Cinq points d'entrée mènent
au détail d'un diagnostic : (1) la **chip de fin de ligne** (pill après le
dernier caractère, badge de comptage — `findDiagnosticChipHitAt` : ligne à
plusieurs diagnostics → **fiche groupée**, sinon popup direct l.308-321) ;
(2) le **point de diagnostic du gouttière** (l.260-267) ; (3) la **bande
de fold** sur une ligne diagnostiquée → actions de code (l.243-245) ou
fiche (l.251-255) ; (4) une **rangée de la fiche groupée** (l.142-169) ;
(5) l'**ampoule** (actions de code de la ligne). La fiche de détail
elle-même est complète : message replié borné, bloc **QUICK FIXES résolu à
l'ouverture** (`resolveCodeActionsAt` — l'héritage direct du lot 1, les
fixes arrivent désormais réels), boutons d'action applicables, fermeture
propre, exclusion des autres popups (`EditorDiagnosticPopups` l.47-67).

**Les deux défauts.** (1) **Le squiggle lui-même n'est pas tappable** :
le hit-code a été *volontairement retiré* (commentaire l.298-307) « pour
que l'utilisateur puisse encore positionner le curseur avant/après la
plage » — la parité caret a été achetée au prix du geste le plus naturel.
(2) Le « popup » de détail est en réalité une **feuille dockée en bas de
l'écran** (`diagnosticSheetMetrics` l.105 : `panelTop = height − sheetH`),
modale (scrim, « tout autre tap ferme » l.193-195) — sur un mot du milieu
de l'écran, le détail apparaît à l'autre bout, en coupant le contexte.
Le mot « popup » de la demande est donc doublement justifié : il manque
à la fois le **geste** (tap sur le souligné) et la **forme** (ancrage au
mot, non-modal).

### 7.2 La référence

Sur desktop, le survol d'un squiggle ouvre un **tooltip ancré juste
au-dessus de la plage en erreur** : icône de sévérité, message, et liens
de quick-fix (« More actions… / Alt+Enter ») ; non modal, disparaît au
déplacement du pointeur ; le clic sur le squiggle fixe par ailleurs le
caret. Sur mobile, le geste équivalent au survol est le **tap sur le mot
souligné** — les éditeurs tactiles de référence ouvrent le tooltip ancré
au mot. La transposition exige une règle de cohabitation avec le caret,
que ce dépôt a déjà résolue une fois (le **pendingTapDismiss** différé,
l.40-68 : le premier tap arme, le second décide) — le même motif
s'applique ici.

### 7.3 Écarts et chantiers

**D1 (P0, M) — Tap sur le squiggle → popup ancré au mot.** Le chantier
central du lot 2.
- **Hit** : après le placement du caret au tap simple, si l'offset tapé
  est couvert par un diagnostic (recherche dans `session` par ligne, plage
  `[start, end)`), **ouvrir le popup ancré** au lieu de poser le caret ;
  la parité caret est gardée par le motif différé existant : re-tap sur la
  même plage après fermeture → caret (le pendingTapDismiss est déjà armé
  pour le tap « dans la sélection », même mécanique) ; tap sur les
  bords ±1 caractère → caret directement.
- **Forme** : popup canvas **ancré au-dessus de la ligne du diagnostic,
  sinon en dessous** (clamp horizontal, le motif exact de la popup de
  complétion l.517-536 : `anchorY` au-dessus, flip en dessous, clamp X),
  **non modal** (pas de scrim : tap ailleurs = fermeture), hauteur bornée
  (message ≤ 3 lignes repliées + « … »).
- **Contenu** : en-tête sévérité (icône/couleur existantes) + source si
  disponible ; message replié ; **boutons des quick-fixes** (déjà résolus
  à l'ouverture par `resolveCodeActionsAt` — réutiliser le même appel ;
  un tap applique et referme, le comportement de la fiche actuelle) ;
  bouton **« Tout voir »** qui bascule vers la **fiche bas d'écran
  existante** (elle reste la vue complète : message intégral, toutes les
  actions) — rien n'est supprimé, la demande « en plus du sheet » est
  exactement cette architecture à deux niveaux.
- **Multi-diagnostics** sur l'offset tapé : **mini-liste ancrée** (une
  rangée par diagnostic : sévérité + première ligne du message), tap sur
  une rangée → popup ancré de ce diagnostic. La fiche groupée pleine page
  reste accessible par la chip.
- Test : `SquiggleTapOpensAnchoredPopupTest` (tap au cœur d'une plage
  diagnostiquée → popup visible, ancré au-dessus de la ligne, non modal) ;
  `SquiggleTapCaretParityTest` (re-tap après fermeture → caret ; tap bord
  ±1 → caret direct) ; `SquiggleTapQuickFixTest` (faux resolveur → bouton
  présent, tap → edit appliqué + fermeture) ;
  `SquiggleTapMultiDiagnosticTest` (3 diagnostics superposés → mini-liste
  ancrée, 3 rangées).

**D2 (P1, M) — Factoriser le pattern « popup ancré ».** La complétion
(l.517-536), le NavMenu (l.399-427), et le futur popup D1 dupliquent le
même calcul ancre + flip + clamp. Extraire une méthode de géométrie
partagée dans `EditorPopupAnchors` (`anchorAboveBelow(anchorX, anchorY,
width, height, view)`) consommée par les trois — une source de vérité de
plus, et D1 devient S. Test : les tests existants des trois popups
restent verts (aucun déplacement de comportement).

**D3 (P2, S) — Long-press sur squiggle → copier le message.** Geste
d'appoint (rapport de bug : l'utilisateur veut coller le message exact
ailleurs). Test : clipboard contient le message après long-press.

**D4 (P2, S) — En-tête enrichi.** Dans le popup et la fiche : code/source
du diagnostic (« java », ligne/colonne), badge de comptage si d'autres
diagnostics partagent la plage.

---

## 8. Plan priorisé et jalons de livraison

| # | Chantier | Priorité | Effort | Type de commit | Tests d'acceptation clés |
|---|---|---|---|---|---|
| D1 | Popup ancré au tap sur le squiggle (+ mini-liste multi, bouton « Tout voir ») | **P0** | M | `feat(ui)` | SquiggleTapOpensAnchoredPopup, SquiggleTapCaretParity, SquiggleTapQuickFix, SquiggleTapMultiDiagnostic |
| T1 | SPI d'injection d'actions de la pill (+ overflow ⋯ dans le NavMenu) | **P0** | M | `feat(api)`+`feat(ui)` | SelectionToolbarSpiInject, SelectionToolbarOverflow |
| M1 | Bande de sélection + caret + squiggle dans la loupe | P1 | S-M | `fix(ui)` | MagnifierShowsCaret, MagnifierShowsSelectionBand |
| S1 | Cache de détection sticky (invalidation par révision) | P1 | S | `perf(ui)` | StickyDetectCached |
| S2 | Rendu stylé des lignes collées (drawStyledLine + ellipse) | P1 | S | `fix(ui)` | StickyStyledRender |
| I1 | Kind des inlays porté jusqu'au rendu + styles par genre | P1 | S | `feat(ui)` | InlayKindStyling |
| I2 | Tooltip des inlays (resolve paresseux + tap-long → quick doc) | P1 | M | `feat(api)`+`feat(lsp)` | InlayResolveLazy, InlayLongPressShowsQuickDoc |
| T2 | Actions intégrées socle (Dupliquer, Commenter, Casse, Share opt-in) | P1 | S-M | `feat(ui)` | DuplicateAction, ToggleCommentAction, ConvertCaseAction |
| D2 | Factorisation du pattern « popup ancré » | P1 | M | `refactor(ui)` | tests existants verts (complétion/NavMenu/popup) |
| S3 | Tap sticky → navigation | P2 | S | `feat(ui)` | StickyTapNavigates |
| S4 | Nombre max de sticky configurable | P2 | S | `feat(ui)` | StickyMaxLines |
| S5 | Détection sticky déléguée au SPI langue | P2 | M | `feat(api)` | StickySpiDelegate |
| I3 | labelParts conservées (tooltip par part) | P2 | M | `feat(lsp)` | InlayLabelParts |
| I4 | Inlays cliquables (command SPI) | P2 | M-L | `feat(api)`+`feat(ui)` | InlayClickCommand |
| I5 | Plage visible + re-requête scroll idle (optionnel, serveurs externes) | P2 | M | `perf(lsp)` | InlayViewportRange |
| T3 | Internationalisation des libellés canoniques | P2 | S | `chore(ui)` | ressources présentes, défauts inchangés |
| T4 | La pill suit la borne en cours de drag | P2 | S | `fix(ui)` | ToolbarFollowsDraggedEdge |
| M2 | Zoom de loupe réglable | P2 | S | `feat(ui)` | MagnifierZoomBounds |
| M3 | Loupe native opt-in API 28+ | P2 | M | `feat(ui)` | MagnifierNativeToggle |
| D3 | Long-press squiggle → copier le message | P2 | S | `feat(ui)` | SquiggleLongPressCopy |
| D4 | En-tête enrichi (code/source, badge) | P2 | S | `feat(ui)` | DiagnosticHeaderFields |

**Jalons suggérés.** **J1 = les deux P0** (D1 puis T1 — la demande
explicite, livrée en premier, ~2 commits + tests) ; **J2 = la lieue
visuelle** (S1, S2, M1 — le sticky devient correct ET bon marché, la loupe
devient un instrument) ; **J3 = les inlays** (I1, I2) ; **J4 = T2 puis les
P2 au fil**, en commençant par ceux qui ont un consommateur immédiat en
démo (S3, S4, D3). À chaque jalon : `./gradlew clean assembleDebug
testDebugUnitTest lint` + garde-fous (figures de tests, budgets de lignes,
baseline lint figée), `CHANGELOG.md` enrichi, et **démonstration sur
l'APK démo** — la démo doit au passage exposer les toggles des nouvelles
options (sticky on/off existe déjà côté API mais pas côté UI démo ;
max sticky ; zoom loupe ; actions de pill injectées par `DemoAnalyzer`
pour prouver le SPI).

**Protocoles de validation démo (épreuve directe, sur l'APK debug 3.51.1
puis sur le lot livré)** :
1. *Popup squiggle* : taper au cœur du mot souligné rouge d'un diagnostic
   d'erreur → popup au-dessus du mot, message + bouton de fix, tap
   extérieur ferme, re-tap pose le caret.
2. *Pill extensible* : sélectionner un mot → la pill démo propose les
   actions injectées (Dupliquer, Commenter, Casse) ; en désactiver une
   côté hôte → elle disparaît de la pill au prochain affichage.
3. *Loupe qui montre la borne* : tirer la poignée de sélection lentement
   → la barre de caret/bande est visible dans la bulle, au caractère près.
4. *Sticky stylé et fluide* : scroller un long fichier Kotlin dans la démo
   → lignes collées colorées, plus de creux de fluidité (Mesure : frames
   sans allocation de scan — systrace ou simple constat de fluidité sur
   un fichier ≥ 5 000 lignes).
5. *Inlays* : les paramètres en italique pâle, les types droits ; appui
   long sur un hint → quick doc du tooltip.

---

## 9. Ce qui reste hors de `code-editor` (périmètre / franchise)

- **Les réglages globaux des inlay hints** (écran de configuration par
  langue/genre) : la bibliothèque fournit le SPI de genre et le style par
  défaut ; l'écran de réglages est un geste d'application hôte.
- **La richesse des serveurs** : tooltip/resolve/commands d'inlays ne
  servent que si un serveur les fournit — le serveur interne de CodeIDE
  et `DemoAnalyzer` en décident ; le lot se contente de ne plus *fermer*
  les voies.
- **Le sticky par arbre syntaxique complet** (PSI-like) : hors de portée
  sans analyseur par langue ; S5 (délégation SPI) est la version honnête
  et extensible.
- **La loupe native par défaut** : décision propriétaire notée au lot 1 ;
  ce rapport recommande custom par défaut + natif en opt-in (M3), le
  natif étant inférieur en maîtrise mais supérieur en exhaustivité de
  rendu.
- **L'onglet Problèmes / la feuille de diagnostics du projet** : déjà
  couverts par les lots précédents (`setOnDiagnosticsPublishedListener`
  côté hôte) — ce lot ne les rouvre pas.

---

## 10. Conclusion

Les cinq familles auditées racontent la même histoire que le lot 1, à un
étage différent : **les fondations existent et sont souvent solides**
(la loupe est le meilleur code du lot ; le tissage des inlays dans la
ligne est un vrai comportement d'IDE ; la cascade de tap est déjà riche et
ordonnée), mais **le dernier geste — celui que l'utilisateur tente
naturellement — n'est pas câblé** : le mot souligné ne répond pas, la
barre de sélection n'enrichit pas, la loupe ne montre pas la borne qu'on
règle, la ligne collée est un résumé gris et coûteux, le hint est muet.
Les deux chantiers P0 (popup ancré au squiggle, SPI de pill) sont
exactement les deux demandes explicites et sont mesurables en une
épreuve chacune sur l'APK de démonstration. Le reste du plan est
additif, réversible, et sans rupture de la surface publique consommée
par CodeIDE.
