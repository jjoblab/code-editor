# Plan 05 — Accessibilité, i18n et RTL

> Phase 2 — plan d'implémentation, sans implémentation lourde.

## 1. Constat (mesuré)

- 1 seule référence d'accessibilité (`EditorPreviewSheet`,
  `importantForAccessibility`) ; aucun `AccessibilityNodeInfo`, aucun
  `contentDescription` sur les éléments interactifs (poignées de
  sélection, chevrons de pli, popups).
- Aucun `res/` dans les bibliothèques : toutes les chaînes UI sont en
  dur en anglais dans le code :
  `EditorContextMenuHandler` (≈ l. 48-55 : Cut/Copy/Paste/Select
  All…), `EditorPopupAnchors` (≈ l. 271-276), `EditorGoToLinePopup`
  (≈ l. 52), `EditorRenamePopup` (≈ l. 78), `EditorNavMenuPopup`
  (« Declaration », « Implementations », « Type declaration »,
  « Super »).
- 0 référence RTL/BiDi : le rendu, le hit-test et les poignées
  supposent LTR (maths absolues en x).

## 2. Cible

1. **i18n** : chaînes externalisées dans `res/values/strings.xml` +
   `res/values-fr/` ; injection depuis l'hôte possible
   (`EditorStrings` SPI dans `cel-lsp-api` — par défaut les
   ressources de la bibliothèque).
2. **Accessibilité** : `AccessibilityNodeInfo` sur l'éditeur (rôle
   texte éditable, offset du caret, sélection), events
   `TYPE_VIEW_TEXT_SELECTION_CHANGED` ; `contentDescription` sur
   poignées/chevrons ; navigation au clavier déjà couverte par
   `EditorKeymap`.
3. **RTL** : position documentée — v1 = détection + rendu miroir de
   la gouttière seule (pas le contenu du texte), avec test de
   non-régression sur les coordonnées LTR.

## 3. Étapes

1. `cel-ui/src/main/res/values/strings.xml` (+ `values-fr/`) ;
   remplacer les ~25 chaînes en dur par des ressources ; étape
   purement mécanique ; tests : Robolectric lit les ressources,
   `EditorContextMenuHandler` testé avec la locale EN puis FR.
2. `EditorView.onInitializeAccessibilityNodeInfo` : role, range
   textuelle (caret/sélection), nom de la langue si connue ;
   `EditorSelectionGestures` : `contentDescription` des poignées ;
   tests Robolectric : `AccessibilityNodeInfo` reçu, plage correcte
   après `setSelection`.
3. `sendAccessibilityEvent(TYPE_VIEW_TEXT_SELECTION_CHANGED)` dans le
   chemin de sélection (thread UI).
4. RTL : Javadoc + `ARCHITECTURE.md` « Position RTL » (rendu de
   contenu NON mirroir — le texte bidi est géré par le friseur
   Unicode d'Android ; seules gouttière/scrollbars se miroirent via
   `layoutDirection` natif). Test : `View.LAYOUT_DIRECTION_RTL`
   n'altère pas le hit-test du texte.
5. Démo : menu « Langue » pour vérifier i18n en un clic.

## 4. Risques

- Ajouter `res/` à un AAR de bibliothèque est standard mais le
  merge de ressources de l'hôte peut créer des collisions : préfixer
   toutes les clés (`cel_…`).
- L'accessibilité ne doit pas ralentir le chemin de frappe :
  n'émettre des events que sur changement de sélection
  (déjà notifié), jamais par frappe sans sélection.
- RTL complet (contenu) est explicitement HORS périmètre v1 —
  documenté pour ne pas laisser croire que c'est couvert.

## 5. Tests de non-régression

`EditorSelectionToolbarTest`, `EditorNavMenuTest`,
`EditorGoToSymbolPopup` tests (les libellés deviennent des
ressources : assertions sur les ids, pas sur le texte anglais) ;
suite complète verte.
