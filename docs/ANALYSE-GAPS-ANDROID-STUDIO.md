# Analyse des écarts Android Studio ↔ code-editor — n°6

**Référentiel** : éditeur d'Android Studio (IntelliJ IDEA platform editor, y compris
les conventions d'édition tactile mobile du platform Android : `TextView`,
`Magnifier`, `InputMethodManager`) — la sixième analyse de la série, à la
demande du propriétaire : « je te laisse [choisir] sur quoi porte une énième
analyse entre Android Studio et code-editor pour les gaps et améliorations de
certaines fonctionnalités ».

**Cible évaluée** : `code-editor` v3.63.0 (cel-ui / cel-core / cel-lsp) — chaque
affirmation sur l'état du code cite le composant réel lu dans le dépôt. Les
retours terrain n°1 à n°6 (fils d'Ariane, aperçu, popups, wrap multi-fichiers,
secousse du drag, caret au pincement, icône de gouttière) sont traités en amont
de cette analyse : le présent document porte sur ce qui RESTE.

---

## 0. Périmètre et méthode

Cette analyse choisit un angle délibéré : **l'expérience d'édition tactile**
— le domaine de tous les retours terrain du propriétaire. Le lecteur
mobile d'Android Studio (et l'éditeur IntelliJ en général) définit le
comportement de référence sur sept axes : la gouttière et son chrome, les
gestes de sélection, le zoom et la navigation spatiale, la recherche dans
le fichier, la rejouabilité (undo), le retour d'erreur visuel, et le
pilotage clavier matériel. Chaque axe est comparé point par point, puis
les écarts sont priorisés P0/P1/P2 selon la fréquence d'usage attendue
sur mobile (une fonctionnalité utilisée à chaque session passe avant une
fonctionnalité de power-user).

La comparaison est asymétrique par nature : Android Studio est un IDE
desktop complet embarquant inspection de code, débogueur, refactoring
lourd ; code-editor est une bibliothèque d'éditeur embarquable. Les
écarts retenus sont donc ceux qui touchent **le composant éditeur
lui-même** — pas les services d'IDE (compilation, exécution, indexation),
qui relèvent de l'application hôte (CodeIDE).

---

## 1. État des lieux crédité — ce que code-editor fait déjà (et bien)

Avant de lister les manques, le sol existant, vérifié dans le code :

| Fonctionnalité | Android Studio | code-editor v3.63.0 | Localisation |
|---|---|---|---|
| Numéros de ligne + gras ligne courante | ✔ | ✔ (+ alignment wrap/plis par géométrie hôte) | `GutterView` |
| Points de diagnostic au gutter | ✔ | ✔ (erreurs/avertissements, 3dp, bord gauche) | `GutterView.drawLineNumber` |
| Marques de gutter de plugins | ✔ (blame VCS) | ✔ (barres VCS-blame, bord droit) | `GutterView.setPluginMarks` |
| Chevron de repli + bande dédiée | ✔ | ✔ (hit-test + chips `{...}` composites) | `EditorChromePainter.drawFoldChevrons`, `EditorTapResolver` |
| Icône d'action de gouttière (run/preview) | ✔ | ✔ **B9c** — œil d'aperçu sur la rangée du caret, bascule | `GutterView.previewActionRect` |
| Lignes collantes (sticky lines) | ✔ (2023.1+) | ✔ — détection, rendu, tap-navigation | `StickyLineDetector`, `EditorRenderer` |
| Sélection double-tap mot / triple-tap ligne | ✔ | ✔ — cascade 1/2/3+ taps (caret/mot/ligne) | `EditorTapResolver.handleTap` |
| Poignées de sélection + loupe | ✔ | ✔ — loupe **stabilisée B9** (rangée snappée, X amorti) | `EditorSelectionGestures`, `EditorChromePainter.drawMagnifier` |
| Auto-scroll de bord pendant le drag | ✔ | ✔ — deux axes, coin diagonal, **sans secousse B9** | `autoScrollTick` |
| Pincement zoom ancré | n/a desktop | ✔ — ancre caret / point focal, **traînées englouties B9** | `EditorZoomController` |
| Undo/redo câblés + historique | ✔ | ✔ — y compris boutons de barre vivants (B7a) | `UndoManager`, `BreadcrumbBar.addEditorActions` |
| Complétion / quick-doc / code actions | ✔ | ✔ — popups canvas avec fondus et rejet extérieur | `EditorCompletionPopup`, B7/B8 |
| Inlay hints tissés + tooltips | ✔ | ✔ — hover, long-press, parts cliquables | `EditorLineLayoutResolver` |
| Aide à la signature | ✔ | ✔ — bannière passive, dismiss au tap | `EditorSignaturePopup` |
| Guides d'indentation | ✔ | ✔ — trois styles (OFF/INDENT/SCOPE) | `IndentGuideStyle` |
| Caractères non imprimables | ✔ | ✔ — points médians, flèches tab | `EditorTextPainter` |
| Surlignage d'occurrences du symbole au caret | ✔ | ✔ — document highlights débouncés | `scheduleDocumentHighlights` |
| Aperçu intégré (md/html) | plugin | ✔ — feuille flottante, split/full, pinch | `EditorPreviewSheet`, B8b |
| Rejet des popups au toucher extérieur | ✔ | ✔ — fenêtres + canvas, recette complète | `EditorPopupMotion.outsideTapDismisses` |

Ce sol est solide : la bibliothèque couvre le cœur de l'édition tactile
d'IDE, y compris des raffinements qu'Android Studio n'a que récemment
adoptés (sticky lines, inlay hints interactifs). Les écarts ci-dessous
sont donc des **prochaines marches**, pas des fondations manquantes.

---

## 2. Écarts priorisés

### P0 — friction quotidienne

**P0-1 · Recherche / remplacement dans le fichier — ✅ LIVRÉ en B10
(v3.64.0)** : `EditorSearchBar` + `EditorSearchController`
(`view/chrome/`), requête vivante (frappe + édition + session
ré-accrochée), compte n/m, navigation bouclante caret-suivante,
remplacement courant (groupes `$1`, repli littéral) et TOUT en une
seule entrée d'annulation ; entrées menu + `Ctrl+F` ; purge du
surlignage au changement de session. Historique de l'écart (v3.63.0) :
Android Studio :
`Ctrl+F` / `Ctrl+R`, surlignage de toutes les occurrences, navigation
précédent/suivant, remplacement unitaire ou global. code-editor : le
moteur EXISTE (`jo.codeeditor.find.FindReplace.findMatches`,
`FindOptions` casse/mot entier, `matchIndexFrom` — cel-core) mais **aucune
entrée d'interface** : ni barre de recherche dans cel-ui, ni item de menu
dans la démo, ni raccourci. Le propriétaire teste des fichiers longs
(« jusqu'à remplir l'écran ») : chercher un mot dedans exige aujourd'hui
de défiler à la main. **Recommandation** : une `EditorSearchBar` (carte
fenêtrée façon `EditorGoToLinePopup`) posée sur le même pipeline
`EditorPopupMotion` — champ de requête, compteur n/m, occurrences
surlignées via `EditorHighlightPainter` (même mécanique que les document
highlights), boutons ‹ › et replace/replace-all ; câblage démo par item
de menu + raccourci `Ctrl+F` dans `EditorKeyHandler`. Effort : lot
moyen (~1 version).

**P0-2 · Sélection par paragraphe (4e tap) — ✅ LIVRÉ en B10 (v3.64.0)** :
`EditorSession.selectParagraphAt` (bloc de lignes contiguës non vides,
borné par les vides/blanches, fin sans le saut) + cran 4 de la cascade
(le 5e tap y reste). Historique de l'écart (v3.63.0) : la cascade
s'arrête à
mot→ligne ; Android Studio étend jusqu'au paragraphe. Sur mobile, la
sélection de blocs longs au doigt est pénible — le 4e tap est le
raccourci naturel du « je veux tout ce paragraphe ». `EditorSession`
possède déjà `selectWordAt`/`selectLineAt` : ajouter `selectParagraphAt`
(étendre jusqu'aux lignes vides encadrantes) et un `case 4` dans la
cascade. Effort : petit.

**P0-3 · Défilement par la gouttière (drag de numéros) — ✅ LIVRÉ
en B11 (v3.65.0)** : le drag de gouttière est devenu un
positionnement ABSOLU (`EditorFastScroller`) — pouce 4 dp au bord
droit + bulle de numéro de ligne, armé au-delà du touch-slop (un tap
reste un tap), relâchement en fondu 250 ms sans fling, annulation au
pincement/CANCEL/session. Historique de l'écart (v3.64.0) : le drag
dans la gouttière défilait déjà en pixel-relatif mais SANS retour
visuel du pouce (et n'offrait qu'un écran par geste).

### P1 — parité riche

**P1-1 · Bande d'erreurs à droite (error stripe) — ✅ LIVRÉ en B11
(v3.65.0)** : `EditorErrorStripe` — marqueurs colorés par ligne
diagnostiquée (même source que les points de gouttière via le pusher,
compactée en paires sparse), rectangle du viewport, tap marqueur =
dépliage + centrage + fiche, drag = défilement absolu (pouce + bulle
partagés avec le fast-scroller), silence en aperçu, bascule publique
`setErrorStripeEnabled` (défaut actif). Historique de l'écart
(v3.64.0) : les diagnostics n'existaient que dans la gouttière
(points) et le texte (squiggles) — sur un fichier long, les erreurs
hors écran étaient invisibles.

**P1-2 · Déplacer du texte par glisser-déposer de sélection.** Android
Studio (desktop) : `Ctrl+D`-like et drag de texte ; la convention
Android tactile : glisser une sélection la DÉPLACE (TextView drag &
drop). code-editor : le drag étend la sélection (armé long-press) —
déplacer exigeait copier-coller via la pill. **✅ LIVRÉ en B13
(v3.67.0)** : mode « drag de bloc » armé par la pill (item ✥ Move,
`BuiltinSelectionActions.blockDrag` + `EditorView.armBlockDrag`) —
le geste suivant appartient au bloc (englouti : pas de scroll ni de
caret derrière) : drag = déplacement (loupe stabilisée + caret
fantôme au point de dépôt, dessiné aussi dans la loupe + carte
flottante sous le doigt + auto-scroll UX-2 partagé + suspension du
recentrage B9), tap = annulation, CANCEL/pincement/changement de
session = retombée propre ; le dépôt s'exécute par
`EditorSession.moveRange` (UNE étape d'annulation, sélection
ré-épinglée, sémantique caractère exacte, no-op sans pollution
d'undo dans la source). Historique de l'écart (v3.66.0) :
recommandation « un mode armé par la pill plutôt qu'un geste direct
(conflit avec l'extension de sélection) ; le drop insère au caret
fantôme sous le doigt avec la loupe pour la précision » — mise en
œuvre intégrale.

**P1-3 · Zoom de police persistant par fichier/type — ✅ LIVRÉ en
B12 (v3.66.0)** : `EditorZoomMemory` + `EditorView.setZoomMemoryKey`
— mémoire process par clé de type posée par l'hôte, chaque mutation
d'échelle (pincement, A±, `setFontScale`) s'enregistre, la repose
applique le réglage du type (le zoom ne fuit pas entre types), clé
nulle = comportement historique, rotation B17 toujours plus précise.
Historique de l'écart (v3.65.0) : le pincement B9 était ancré mais
l'échelle (0.6–2.6) ne vivait qu'en mémoire de vue — à la
réouverture d'un fichier, tout revenait à 1. Android Studio mémorise
le zoom par éditeur persistant (la portée process couvre les vues
recréées ; la survivance au redémarrage est laissée aux Settings de
l'hôte, qui réinjecte via `EditorZoomMemory.remember`).

**P1-4 · Colonnes relatives (numéros) — ✅ LIVRÉ en B12 (v3.66.0)** :
enum public `LineNumberStyle` (ABSOLUTE/RELATIVE/HYBRID) dans
`GutterView`, posé par `EditorView.setLineNumberStyle` — RELATIVE :
distance au caret (0 au caret) ; HYBRID : numéro réel au caret,
distances autour ; défaut ABSOLUTE inchangé. Historique de l'écart
(v3.65.0) : numéros absolus uniquement — Android Studio propose les
numéros relatifs pour la navigation `5j/10k`.

**P1-5 · Plis : « tout replier/déplier » et plis par région.** Le toggle
par ligne existe (bande + chips). Manquent : le pli sélectif par niveau
(méthodes vs classes — `EditorFoldIndex` a déjà la hiérarchie), et les
régions de commentaires `// region … // endregion` (AS les plie).
**Recommandation** : action NavMenu « Replier au niveau N » + extension
du scanner de plis aux régions de commentaire. Effort : moyen.

### P2 — différenciateurs à plus long terme

**P2-1 · Multi-caret / sélection en colonne.** AS : `Alt+Shift+Clic`.
Sur tactile l'interaction naturelle serait « tap multiple avec pill
Ajouter un caret ». Le modèle `Selection` actuel est unicaret — c'est
une refonte de session (génération API suivante), à ne pas engager à la
légère.

**P2-2 · Annotations de ligne riches dans la gouttière.** B9c a posé
l'infrastructure (icône ancrée à une ligne, rect déterministe, tap
résolu) — la généraliser en API `GutterAction { line, icon, active,
perform }` ouvrirait : points d'arrêt (CodeIDE débogueur), actions
« exécuter ce test », flèches de navigation de diff. C'est le
différenciateur le plus rentable de la liste : l'hôte CodeIDE peut
injecter ses propres icônes de gouttière sans toucher au renderer.

**P2-3 · Bande minimap.** Absente d'Android Studio (VS Code). Écart
volontaire : sur mobile, la largeur est rare et le rendu double le
coût. À ne faire que sur demande explicite.

---

## 3. Feuille de route suggérée (lots B10+)

| Lot | Contenu | Priorité | Taille |
|---|---|---|---|
| B10 | P0-1 recherche/remplacement (`EditorSearchBar` + surlignage + raccourci) — **livré v3.64.0** | P0 | moyenne |
| B10 | P0-2 `selectParagraphAt` + 4e tap — **livré v3.64.0** | P0 | petite |
| B11 | P0-3 fast-scroller de gouttière + P1-1 error stripe — **livré v3.65.0** | P0/P1 | moyenne |
| B12 | P1-3 persistance du zoom + P1-4 colonnes relatives — **livré v3.66.0** | P1 | petite |
| B12 | P2-2 API `GutterAction` généralisée (fondée sur B9c) | P2 | moyenne |
| B13 | P1-2 drag-déposer de bloc (mode pill ✥) — **livré v3.67.0** | P1 | moyenne |
| B14 | P1-5 plis par niveau + régions de commentaire | P1 | moyenne |
| — | P2-1 multi-caret : génération API suivante (refonte Selection) | P2 | grande |

Ordre recommandé : **B10 d'abord** — la recherche est le seul écart de
la liste que le propriétaire touchera à CHAQUE session de test sur des
fichiers longs ; tout le reste est augmentatif.

---

## 4. Conclusion

La trajectoire des retours terrain n°1→n°6 (popups, wrap, aperçu,
secousse, pincement, gouttière) a refermé les irritants de base de
l'édition tactile : le composant éditeur est désormais au niveau des
conventions plateforme sur son cœur. B10 (recherche) puis B11 (stripe
d'erreurs, fast-scroll) referment le palier suivant — la **navigation
dans le contenu** : les trois écarts P0/P1-1 partageaient la même
infrastructure de surlignage et de fenêtrage, désormais livrée. B12
referme le palier CONFORM (P1-3 zoom persistant par type + P1-4
colonnes relatives) — le zoom de l'utilisateur survit aux
réouvertures et la gouttière parle « distance au caret » à la
demande. Restent la parité riche d'édition (P1-2 drag de bloc,
P1-5 plis par niveau) — B13/B14 de la feuille de route. L'API
`GutterAction` (P2-2) reste le pari stratégique : B9c en a posé les
fondations, et CodeIDE en est le premier consommateur naturel.
