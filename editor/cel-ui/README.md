# cel-ui — la couche Android : rendu, gestes, habillage

> 700 tests · JUnit 4 + Robolectric (cycle de vie réel des vues sur
> JVM) · `api(:cel-core)` + `api(:cel-lsp-api)`.

`cel-ui` met le moteur à l'écran : une vue **Canvas** unique
(`EditorView`) — pas de hiérarchie de TextViews, pas de Compose —
entourée de ses chrome (gutter, fil d'Ariane, recherche, minimap) et
de ses popups. Toute la logique éditoriale vit dans `:cel-core` ;
ce module traduit, dessine et fait vivre les gestes.

## Position dans l'ensemble

```
Application hôte
   │ setSession(session), setTheme(...)
   ▼
:cel-ui ──► :cel-core          (le moteur rend/décide)
   │      ──► :cel-lsp-api     (adaptateur LspEditorViewHost pour :cel-lsp)
   │
   └── plugins : EditorPainterHost (peintres maison, un painter qui
       lève est retiré, jamais un crash)
```

- `api(:cel-core)` et `api(:cel-lsp-api)` : l'hôte qui dépend de
  `cel-ui` obtient le moteur et le SPI transitivement — ajouter
  `:cel-lsp` ou `:cel-textmate` reste optionnel.
- L'adaptateur `LspEditorViewHost` implémente `EditorHost` (côté vue)
  pour le client LSP — c'est le seul point de contact `cel-ui ↔ LSP`.

## Fonctionnalités

### Rendu (`view/render/`)

- `EditorRenderer` — pipeline complet en couches ordonnées : texte
  stylé, gutter, caret (animation `CaretAnimator`), sélection,
  squiggles de diagnostics, chips, guides d'indentation, lignes sticky,
  bande d'erreurs, minimap, popups ancrés, décorations de plugins.
- Cinq painters spécialisés (`EditorTextPainter` — texte, spans,
  wrap ; `EditorHighlightPainter` — sélection/recherche ;
  `EditorDiagnosticsPainter` — squiggles/inlays diagnostics ;
  `EditorAssistPopupPainter` ; `EditorChromePainter` — loupe, minimap,
  chevrons de plis).
- `EditorShapedLayoutCache` — layouts ligatures par contenu (clé =
  texte de ligne, jamais un état mutable partagé — leçon d'audit) ;
  rendu **fenêtré** des lignes minifiées (colonnes visibles seules).
- API plugins : `EditorPainterHost.register(...)` + `EditorDecorations`.

### Entrées (`view/input/`)

- `EditorInputHandler` — dispatch tactile/clavier ; drag de sélection
  avec ancre figée au DOWN, auto-scroll des bords.
- `EditorTapResolver` — tap simple/double/triple/quadruple (mot,
  ligne, paragraphe) ; `EditorSelectionGestures` — poignées,
  toolbar flottante contextuelle ; `EditorTouchScroller` (fling,
  momentum) ; `EditorZoomController` (pinch, mémoire du zoom) ;
  **loupe** instrument pendant les glissements (natif quand
  disponible, dessinée sinon) ; `EditorTouchHoverController` (stylet).
- `EditorKeyHandler` + `EditorKeymap` — raccourcis rebindables,
  **accords de touches** (chords deux touches, pending 2 s).

### Popups (`view/popup/`)

`EditorPopupManager` + famille : complétion (badges de type, fenêtre
de scroll solidaire), aide de signature, quick doc, actions de code,
aller-au-symbole, aller-à-la-ligne, renommage, références, fiche et
popups de diagnostics (tap sur squiggle). Ancres, hit-test, fades et
motions dédiés ; glass cards.

### Chrome et habillage (`view/chrome/`, `theme/`)

- `GutterView` (numéros absolus/relatifs/hybrides, actions de gouttière,
  points d'arrêt, aperçu), `BreadcrumbBar`, `SymbolBarView`,
  `EditorSearchBar`/`EditorSearchController` (recherche dans le
  fichier), `EditorFastScroller`, `LineNumberStyle`.
- `EditorTheme` (thème sombre/clair) + `EditorColorScheme` (palette
  syntaxique, surcharge par clés `syntax.*`) — la cascade de résolution
  applique le scheme par-dessus le thème et purge les caches.

### Ponts

- `EditorImeBridge` — InputConnection, texte extrait, composition.
- `EditorLanguageBridge` — session ↔ SPI (providers, diagnostics).
- `view/preview/` — **aperçu XML délégué** : l'éditeur ne embarque
  aucun moteur d'aperçu ; l'hôte implémente `EditorPreviewHost`
  (canPreview, drawPreview, hitTest…), le mode NONE/SPLIT/FULL vit
  côté vue.
- `view/state/` — états extraits (plan 01) : `CompletionState`,
  `FindState`, `ScrollState` — mutabilité thread UI, lecture seule
  pendant `onDraw` (invariants possédés, testés).
- `blocks/` — `BlockEditor` : édition par blocs (slots imbriqués).

## Conception (règles vérifiées)

- **Orchestrateurs + collaborateurs** — `EditorView` (~3 775 lignes)
  délègue à ~60 collaborateurs par responsabilité ; les couples
  internes sont scellés `@RestrictTo(LIBRARY_GROUP)` : l'API publique
  reste stable pendant que l'intérieur se décompose (plafonds en
  cliquet, `scripts/check-class-budgets.sh` en CI).
- **Défensif au dessin** — offsets clampés à `[0, doc.length()]`,
  painter défaillant retiré sans crash, `textPaint` partagé toujours
  repositionné.
- **Cache par ligne** — les rendus par ligne passent par
  `LineRenderCache` (triple-stamp, LRU) : un changement de thème
  invalide, une édition ne re-rend que la fenêtre touchée.

## Utilisation

```java
EditorView editorView = new EditorView(context);
editorView.setSession(session);          // le moteur (cel-core)
editorView.setTheme(EditorTheme.dark());
editorView.setKeymap(EditorKeymap.defaults()
        .bind(EditorCommands.REDO, KeyEvent.KEYCODE_Z, true, true));
```

Le guide complet (thèmes, diagnostics, résolveurs, raccourcis, aperçu
XML, ProGuard/R8) vit dans [`USAGE.md`](../../USAGE.md).

## Limites connues

- **Droite-à-gauche non supporté** — décision explicite de l'ADR-001
  (n°7 : « non supporté documenté », pas de plan bidi dans ce cycle) ;
  un avant-projet existe (`docs/plans/05-accessibilite-i18n-rtl.md`).
- **Une sélection à la fois** (moteur) — pas de multi-curseur.
- **Grands fichiers** — coloration/plis/inlays coupés au-delà des
  seuils du moteur ; le rendu reste fonctionnel.

## Tests

```bash
./gradlew :cel-ui:testDebugUnitTest     # 700 tests Robolectric
```

Couverture sensible : cycle de vie (detach/leaks), gestes (drag,
pinch, tap résolu), loupe (bornes, isolation du paint), popups
(ancrage, fade, hit-test), sticky, wrap+folds (géométrie),
keymap/chords, IME, état restauré (instance state).

## Voir aussi

- [`editor/cel-core/README.md`](../cel-core/README.md) — le moteur
  rendu par cette couche.
- [`editor/cel-lsp-api/README.md`](../cel-lsp-api/README.md) — le
  contrat `EditorHost` qu'implémente `LspEditorViewHost`.
- [`USAGE.md`](../../USAGE.md) — le guide d'intégration complet.
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — cycle d'édition et
  organisation détaillée des packages.
