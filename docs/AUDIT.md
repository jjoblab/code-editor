# Audit de l'éditeur — Phase 1 (aucune modification de code)

> Période : 2026-09-28 · Dépôt : `jjoblab/code-editor` branche `main`, v3.37.0 au départ de l'audit, arbre propre.
> Référence amont comparée : tags `v3.20.0` → `v3.22.1` (+ `main`). L'identification du
> dépôt de référence et le crédit associé vivent dans le README (section Crédits) — ce
> document n'a pas besoin de les répéter.
>
> **Méthode** : chaque affirmation est étiquetée :
> - **[L] confirmé par lecture** — le défaut/le changement a été lu directement dans le code cité (`fichier:ligne`) ;
> - **[A] signalé par audit automatisé** — fichier et ligne cités, à valider par test lors de la phase 2 (chaque correctif sera accompagné d'un test qui échoue avant et passe après, ce qui valide ou infirme au passage) ;
> - **[S] suspecté** — dépend d'un comportement d'exécution non encore démontré.
>
> Ce document a été produit sans aucune modification du code du dépôt. Le test de
> reproduction du bug de loupe (§1B-B1) a été ajouté dans l'arbre de travail **non
> committé** uniquement pour être exécuté ; il sera committé en phase 2 avec son correctif.

---

## 1A. Amont v3.20.0 → v3.22.1 : ce qui a changé côté éditeur

### Cadre vérifié

- 161 commits au total entre les deux tags ; **38 touchent les modules éditeur** (`app/ide-ui-core`, `app/ide-ui-editor`, `app/ide-core`) — liste intégrale ci-dessous.
- **[L]** `EditorInteraction.kt` et `EditorInputModifier.kt` (sélection/poignées amont) sont **inchangés** entre v3.20.0 et v3.22.1 (`git diff --stat v3.20.0 v3.22.1 -- '*EditorInteraction.kt' '*EditorInputModifier.kt'` → vide).
- **[L]** Il n'existe **aucune loupe** (« magnifier ») dans le code de l'application amont à v3.22.1 (grep : seule correspondance `android.jar`, binaire plateforme). Les bugs de sélection/loupe sont donc bien **à corriger chez nous** — rien à récupérer de l'amont sur ce sujet.
- **[L]** Après v3.22.1, `main` n'apporte **rien** pour l'éditeur de texte : 3 commits seulement (2 tests de déplacement d'écriture + 1 correctif de dexer build).
- **[L]** Le hash `5e239fb5b` cité dans le brief existe (entre v3.21.0 et v3.22.0) : il optimise le *chrome* d'écran en Compose (`derivedStateOf`) — mécanisme non transposable ; seul le principe (n'observer qu'un booléen dérivé, jamais le document entier) est retenu.

### Tableau — commit amont → verdict → équivalent chez moi → risque → effort

Verdicts : **À porter** / **Déjà présent** / **Non applicable** (Compose, KMP, analyse Kotlin/PSI, plugins, host) / **À évaluer**.
Risque/effort : S (faible) / M (moyen) / L (fort).

| Commit amont | Sujet (synthèse) | Verdict | Équivalent chez moi | Risque | Effort |
|---|---|---|---|---|---|
| `ab3e7d5d0` | Couleurs de l'éditeur = scheme possédé par l'utilisateur (attributs clés/parent/groupes, scheme clairsemé 2 variantes, résolution par chaîne de fallback, presets, export/import JSON) | **À porter** (feature majeure) | `EditorTheme.java` (couleurs finales figées), `colorForToken()` | M | L |
| `b8cd18824` | Colorer les distinctions déjà faites par les scanners : TokenType 9→22 (contrôle vs modificateurs par table partagée, DOC_COMMENT, CHAR/RAW_STRING, OPERATOR/BRACKET/SEPARATOR, XML TAG_DELIMITER/NAMESPACE/ENTITY/PROLOG/CDATA, EMPHASIS markdown) | **Partiellement présent — à compléter** (mes 19 types couvrent OPERATOR/ESCAPE/LABEL/VARIABLE/CONSTANT/REGEXP ; manquent contrôle/modificateurs, DOC_COMMENT, CHAR/RAW_STRING, fines XML, EMPHASIS) | `highlight/TokenType.java`, `highlight/tokenizer/*.java` | L | M |
| `5ac66c9f2` | Réutiliser overlays/fold models quand une édition ne bouge rien (listes shiftées de même instance si identité, `sameProjection`/`sameAs` des plis, cache de composites validé par révisions de lignes) | **À porter** | `shift/DiagnosticShift.java`, `fold/FoldModel.java`, `wrap/WrapModel.java` | M | M |
| `c7a68e1cd` | Garder la révision de ligne suivante quand l'état du lexer est inchangé + ligne « ripple » aux spans identiques = révision conservée | **À évaluer** (risque moyen — cœur du styling incrémental ; gros multiplicateur du cache de rendu) | `session/RestyleEngine.java`, `highlight/SyntaxHighlighter.java` | M | M |
| `dae7e9a08` | Mise à jour **partielle** du texte extrait IME quand un batch reste contigu (span couvrant unique en coordonnées pré-batch) | **À évaluer** | `session/ImeBridge.java`, `view/EditorImeBridge.java` | M | M |
| `8737e423e` | Caches par fichier bornés (LRU, 8/4 en low-memory, éviction en cascade) + libération sous pression mémoire + parse paresseux | **Partiellement présent** (LineRenderCache borné 512, ShapedLayoutCache borné 64) ; la partie pression mémoire/onTrimMemory est **à évaluer** | `cache/LineRenderCache.java`, `view/EditorViewportPrefetcher.java` | L | S/M |
| `777f1edab` | Début de token de complétion calculé sur la rope (vue CharSequence) au lieu du texte matérialisé — lu à chaque frappe avant le debounce | **À porter** | `completion/CompletionSession.java` + appelant popup | L | S |
| `f454ede2b` | Filtre de complétion mémoïsé (même instance tant que session/préfixe ne changent pas) + tri en une passe par seaux + `fuzzyMatches` sans allocation + popup dans la même frame | **À porter** | `completion/CompletionSession.java`, `view/popup/EditorCompletionPopup.java` | L | M |
| `c4bec0cf7` | Parcours de la rope **feuille par feuille** pour l'équilibre/l'appariement de crochets (visitor stoppant, `leafAt`) au lieu d'une traversée d'arbre par caractère | **À porter** | `rope/Rope.java`, `edit/BracketPairs.java`, `view/EditorBracketMatcher.java` | L | S |
| `a8c892ee0` | La complétion coupe file : token d'annulation lié à l'appelant, une passe superseded s'arrête au prochain poll, une passe coupée propage au lieu de renvoyer vide | **À évaluer** (mon architecture threads/Handler diffère ; retenir les garde-fous : jamais de résultat vide écrasant l'éditeur, abandon avant démarrage) | `view/EditorDiagnosticsPusher.java`, `view/EditorLanguageBridge.java` | H | M |
| `71eea9425` | Occurrences posées 300 ms après stabilité du caret, hors thread UI, invalidées par identité de document ; chips de diagnostic composées par fenêtre (paquets de 32 lignes) ; dessin des matchs par double recherche binaire sur la plage visible | **À porter** (3 idées indépendantes) | occurrences/documentHighlights : `render/EditorHighlightPainter.java` ; chips : `render/EditorDiagnosticsPainter.java` | M | M |
| `4c45c93ae` | Overlays sémantiques/inlay re-binés **par ligne éditée** (plage couvrante tenue à travers les éditions, génération séparée passe-fraîche vs édition, `setLine` ne stamp que si la valeur change) | **À porter** | `cache/LineRenderCache.java`, `view/EditorLineLayoutResolver.java` | M | M |
| `6dbf5cc50` | Inlay hints demandés pour les lignes visibles ± un écran, re-lance de la seule passe INLAY au scroll au-delà de la couverture | **À évaluer** | `lsp/LspInlayHintProvider.java`, rendu inlays `EditorTextPainter` | M | M |
| `5e239fb5b` | Chrome d'écran ne s'abonne qu'à des booléens dérivés (mécanisme Compose) | **Non applicable** (principe noté pour mes observers) | — | — | — |
| `ff9319ae4` | Traces IME construites seulement si le tag est loggable | **À porter** (trivial) | `view/EditorImeBridge.java` | L | S |
| `94cd8e9bb` | Expand selection : montée d'ancêtres AST — premier ancêtre strictement plus large (l'algorithme fait 12 lignes ; côté amont c'est un déplacement vers la couche commune) | **À porter** (version basée spans lexicaux + paires de crochets, sans AST complet) | absent de mon dépôt ; ajout naturel dans `view/EditorCommands.java` / `EditorSelectionGestures` | L | S/M |
| `35f251ef7` | Analyseurs de projet lancés depuis l'éditeur (debounce 800 ms après analyse posée, comparaison des findings visibles pour éviter les cascades) | **À évaluer** (dépend du pipeline projet hôte) | `lsp/LspProject.java`, `view/EditorLanguageBridge.java` | M | M |
| `4877ca3ac` | Aperçu d'un layout dans un dossier qualifié (config de rendu portant ses propres qualificants) | **Non applicable** (preview XML Android, hors éditeur de texte) | — | — | — |
| `cf57bde12` | Collage : préférer le clip plain-text (débarrasser le HTML) + copier-tout Problems/Log | **À évaluer** (moitié éditeur uniquement : plain-text) | `view/EditorClipboard.java` | L | S |
| `9585c19e0` | Moteur de complétion : parse paresseux du buffer pour les contributeurs | **Non applicable** (moteur amont lié aux contributeurs Kotlin/Java) | — | — | — |
| `9b976d9d9` | Benchmark de frappe sous contention moteur | **Non applicable** (benchmark moteur) | — | — | — |
| `57cbb7dc3`, `c263e0bb1`, `b8cd5ad51` | Perf/fix analyse Kotlin (passes par frappe, hash KtElement, diagnostic suspend) | **Non applicable** (PSI/light tree Kotlin) | — | — | — |
| `99aefcedd`, `17a29aa0d`, `5d6483b4f`, `d8d1c6535`, `482e2bd52`, `0fbeae7a8`, `d9361823f` | KMP/plateforme/build/settings/agent CI | **Non applicable** (l'idée DeviceMemory/onTrimMemory de `17a29aa0d` est notée pour mes seuils de caches) | — | — | — |
| `3e19b4018`, `8d3185f74`, `2eb86a7bc` | UI Compose (police arabe, icônes RTL miroir, clés LazyColumn) | **Non applicable** (principes RTL/unicité notés) | — | — | — |
| `8030a176d`, `a2b1645f4` | Vocabulaires DOM / breadcrumb de télémétrie | **Non applicable** | — | — | — |
| `5b46180fe`, `41dfd2884`, `64769d17c` | Fusions (agrègent les commits ci-dessus) | couvert par les lignes individuelles | — | — | — |

**Pistes du brief vérifiées** : tous les hashes cités existent dans la plage v3.20.0..v3.22.1 sauf `5e239fb5b` (entre v3.21.0 et v3.22.0, module écran — voir ci-dessus). La correspondance des modules est exacte : `cel-core` ↔ `app/ide-ui-core/.../editor/core/`, `cel-ui` ↔ `app/ide-ui-editor/.../editor/`.

### Approches techniques amont retenues (résumés de lecture, pour réimplémentation)

- **Scheme de couleurs (`ab3e7d5d0`)** : registre d'attributs (clé, titre, groupe, parent, défauts sombre/clair) ; scheme = deux maps clairsemées (une clé absente = « pas d'opinion ») ; résolution **eager** en cascade : override du scheme → valeur thème live (chrome seulement) → défaut de l'attribut → chaîne identique pour le parent → foreground de `text` en plancher ; presets en code ; persistance plate ; export JSON des **seuls overrides** (`{schema:1, id, name, basedOn, dark:{key:style}, light:{...}}`). Écran de réglages généré depuis le registre.
- **Token types fins (`b8cd18824`)** : distinction contrôle/modificateurs via **une table de mots partagée** appliquée à tout mot déjà déclaré mot-clé (un langage contribué obtient la distinction gratuitement) ; nouveaux états lexer DOC_COMMENT (l'état porté se souvient si `/**` ou `/*` a ouvert) et XML_CDATA ; nom qualifié XML éclaté en NAMESPACE + `:` + nom local ; entités `&…;` dans une fenêtre de 12 caractères ; emphase markdown avec règle intra-mot `_` vs `*`. Les ~25 nouveaux attributs n'ont **aucune couleur propre** (héritent du parent) — rien ne change visuellement tant qu'un scheme ne les sépare pas.
- **Révisions stables (`c7a68e1cd`)** : avant la re-lexure, mémoriser l'état d'entrée avec lequel la première ligne intacte avait été lexée ; si l'état courant est identique → **arrêt immédiat du ripple** ; une ligne re-lexée aux spans identiques conserve sa révision (seule une ligne directement éditée prend une révision fraîche).
- **Update IME partielle (`dae7e9a08`)** : fusionner chaque édition d'un batch dans un **span couvrant unique** tant qu'elles restent contiguës (bornes hautes re-converties en coordonnées pré-batch) ; à la fermeture : si contigu → pousser le span (mise à jour partielle), sinon refresh complet.
- **Scans de rope (`c4bec0cf7`)** : `leafAt(index)` en descente O(profondeur) + `scanForward/scanBackward` qui bouclent dans la String de la feuille (visitor reçoit l'index global, retourne true pour stopper).
- **Filtre complétion (`f454ede2b` + `777f1edab`)** : mémo par (session, préfixe) — la même instance de liste tant que rien ne change ; tri par 10 seaux (2 groupes × 5 tiers) remplis en une itération ; préfixe insensible à la casse sinon sous-séquence, positions calculées seulement pour les lignes affichées ; lecture du début de token sur la vue CharSequence de la rope.
- **Occurrences/chips/matchs (`71eea9425`)** : scan whole-word seulement après 300 ms de stabilité (caret+texte), hors thread UI, résultat affiché seulement si le document courant est la même instance ; fenêtre de chips alignée sur des **paquets de 32 lignes** ; largeur de chip estimée (colonnes × avance monospace) hors fenêtre ; dessin des matchs par **double recherche binaire** sur la plage visible.
- **Re-bin par ligne (`4c45c93ae`)** : plage couvrante `overlayDirtyFirst/End` repliée à travers les éditions (translation des bornes selon le delta) ; `overlayGeneration` incrémentée seulement quand une liste est remplacée en bloc ; `setLine(line, value)` ne bump le stamp **que si la valeur diffère**.
- **Inlay fenêtrés (`6dbf5cc50`)** : l'éditeur publie `viewportLines` (hors du chemin de dessin) ; la passe INLAY demande visible ± un écran et mémorise sa couverture ; un scroll au-delà relance **uniquement** cette passe, à révision constante.
- **Préemption (`a8c892ee0`)** : un token d'annulation suit l'appelant ; une frappe qui supersède une passe la stoppe à son prochain poll ; une requête en attente derrière une complétion **abandonne avant de démarrer** ; une passe préemptée **propage** l'annulation au lieu de renvoyer un résultat vide qui nettoyait l'éditeur.

---

## 1B. Audit des bugs de mon dépôt

### B1 — BUG SIGNALÉ : « la loupe fait perdre la couleur quand je sélectionne puis je fais glisser » — **[L] cause racine établie par lecture croisée**

Chaîne causale complète (tous les fichiers lus directement) :

1. `EditorSelectionGestures.dragHandle()` active `magnifierActive` au premier MOVE
   (`editor/cel-ui/src/main/java/jo/codeeditor/view/input/EditorSelectionGestures.java:67`).
2. `EditorRenderer.draw()` appelle `chrome.drawMagnifier(canvas)` **en dernier**
   (`.../render/EditorRenderer.java:372`), après toutes les couches qui **mutent le
   `textPaint` partagé** : `drawCachedSemSpans` (`.../render/EditorTextPainter.java:327`
   — `paint.setColor(s.color)`), `drawCachedInlays` (`:351`), minimap
   (`.../render/EditorChromePainter.java:61`), navmenu/preview/chips
   (`EditorChromePainter.java:465-523`, `:740-804`).
3. `drawMagnifier()` remet typeface/textSize (`EditorChromePainter.java:318-319`) mais
   **jamais la couleur de base**, puis appelle
   `text.drawStyledLine(..., view.textPaint, ...)` (`:331`).
4. `drawStyledLine` chemin ligatures (le défaut) appelle
   `view.shapedLayoutFor(lineText, styled, paint)` **sans poser la couleur de base**
   (`.../render/EditorTextPainter.java:437-445`).
5. `EditorShapedLayoutCache.layoutFor()` signe l'entrée de cache avec
   `paint.getColor()` (`.../render/EditorShapedLayoutCache.java:126-127`) et construit
   le layout avec `new TextPaint(paint)` (`:116`) → **la couleur de base du layout est la
   couleur résiduelle du paint au moment de l'appel** (le commentaire `:122-125` en
   témoigne : « mutée au moment du dessin — ex. le chemin loupe »).

**Conséquences pendant le drag** : la couleur résiduelle à l'entrée de la loupe ≠
couleur du rendu normal → signature différente → *miss* de cache → layout reconstruit
avec une couleur de base fautive (couleur du dernier span sémantique, couleur de
gouttière, etc.) → les portions de ligne **sans span** (code de base) apparaissent dans
une couleur erronée dans la bulle, et le cache *thrash* (reconstruction des 7 layouts de
la loupe puis des layouts visibles à chaque frame, allocations + façonnage massifs).
Le layout fautif est de plus **mis en cache** : si une frame du rendu normal retombe sur
la même couleur résiduelle, le texte de base reste faux **après** le relâchement.

**Verdict sur les hypothèses du brief** :
- Hypothèse A (couleur mutée → reconstruction + couleur figée) : **confirmée** [L].
- Hypothèse B (la loupe ne dessine ni `semSpans`, ni sélection, ni curseur, ni
  non-imprimables, ni pli composite, ni word-wrap) : **confirmée** [L] — la boucle
  `EditorChromePainter.java:320-338` saute les lignes pliées sans dessiner le composite,
  n'appelle ni `drawCachedSemSpans` ni `drawWrappedLine` ; le contenu zoomé paraît
  « sans couleur » dès qu'une ligne n'a pas de `StyledLine` (branche `:334-336` qui
  dessine du texte brut monochrome).
- Hypothèse C (fuite d'état du Paint vers la frame suivante) : **confirmée en partie** [L] —
  typeface/textSize sont re-posés à chaque frame par `EditorRenderer.java:215-216`, mais
  la **couleur** n'est jamais réinitialisée en début de frame ; c'est le vecteur de A.

**Correctif proposé (le plus simple et robuste, en 2 volets)**
1. Le cache ne doit plus dépendre d'un état mutable : couleur de base du layout =
   `view.theme.textColor` (le thème est déjà critère d'invalidation), signature = spans
   uniquement → plus de thrash ni de couleur figée.
2. La loupe ne doit **jamais muter le `Paint` partagé** : paint dédié avec couleur posée
   explicitement, ou sauvegarde/restauration systématique.
3. Option recommandée (décision propriétaire) : adopter `android.widget.Magnifier`
   (API 28+), qui capture le **vrai rendu** (sélection, curseur, spans sémantiques,
   wrap, plis composites inclus) sans rien redessiner, avec repli sur la loupe maison
   corrigée pour API 24-27. Corriger de toute façon la loupe maison (repli).

**Test de reproduction** : `editor/cel-ui/src/test/java/jo/codeeditor/view/MagnifierColorLeakReproTest.java`
(ajouté non-committé en phase 1, committé en phase 2 avec le correctif) :
(a) layout servi avec une couleur de base indépendante de la couleur résiduelle du
paint ; (b) même instance de layout (pas de thrash) quand seule la couleur du paint
change ; (c) un draw complet avec loupe ne laisse pas `textPaint` dans un état différent
d'un draw sans loupe. Résultat de l'exécution : §1C/fin de §1B.

### B2 — Ancre de sélection perdue en glissant une poignée — **[L] confirmé par lecture**

`EditorSelectionGestures.java:71-80` : les modes 1 et 2 de `dragHandle()` relisent
`sel.end` / `sel.start` **à chaque MOVE** pour déterminer l'ancre. Quand la poignée
franchit l'autre borne, la sélection est renormalisée (start/end inversés) : au MOVE
suivant, l'ancre devient la position précédente du doigt et la sélection se réduit à une
petite plage qui suit le doigt. L'ancre doit être **figée au DOWN** (au moment du
`hitTestHandle`). Gravité : majeure (UX cœur de la sélection tactile).

### B3 — Même défaut dans `handleTouchDrag()` — **[L] confirmé par lecture**

`EditorSelectionGestures.java:51-56` : `selStart = min(start, end)` recalculé à chaque
MOVE → impossible de rétrécir la sélection en revenant en arrière ; le sens
(direction) de la sélection est perdu. L'ancre doit être figée au DOWN du drag-select.

### B4 — Loupe : bornage et hygiène — **[L] confirmé par lecture**

- `magCx` non borné horizontalement (`EditorChromePainter.java:286`) : bulle coupée aux
  bords de la vue quand le doigt approche du bord.
- Bornage vertical en **coordonnées vue** (`:288-290`) : ignore la position de la vue
  dans l'écran (une vue décalée sous une toolbar laisse la bulle sortir de l'écran).
- `new Path()` à chaque frame dans `drawMagnifier` (`:295`) alors qu'un `scratchPath`
  réutilisable existe (`:40`).
- Commentaire périmé « la loupe si active (actuellement désactivée) »
  (`EditorRenderer.java:371`) — la loupe est ré-armée (B1).

### B5 — `magnifierActive` ne retombe pas dans tous les chemins — **[L] confirmé par lecture**

Grep sur tout `cel-ui` : remise à `false` uniquement sur UP
(`.../input/EditorInputHandler.java:380`) et CANCEL (`:511`). Manquent : **perte de
focus** (`onFocusChanged`), **changement de session/onglet** (`setSession`),
`onDetachedFromWindow`. La bulle peut rester affichée après un vol de focus pendant le
drag. À corriger avec B1/B4.

### B6 — Undo/redo contourne le pipeline d'invariants — **[L] confirmé par lecture (partie LSP à valider par test)**

`.../session/EditorSession.java:1056-1098` : `undo()`/`redo()` mutent `doc` directement
(`doc = doc.replace(...)`, `:1062`, `:1087`) au lieu de passer par le point d'entrée
commun (`doReplaceRange`). Conséquences lisibles :
- `annotations.shift(...)` / `folds.shift(...)` (exécutés dans le chemin normal) sautés
  → diagnostics, tokens sémantiques, inlays et régions de pliage gardent des offsets
  périmés après Ctrl+Z (les plis repliés peuvent pointer hors bornes) ;
- garde `readOnly` contournée (le chemin normal la respecte) → l'undo modifie une
  console en lecture seule ;
- les `onTextEditListeners` (sur lesquels le pont LSP s'accroche) ne sont pas notifiés —
  seul le listener IME reçoit un `EditSpan(0,0,0)` factice (`:1076-1079`). Le serveur LSP
  reste potentiellement sur une version périmée jusqu'à la prochaine frappe
  ([A] pour l'effet exact côté LSP — validé par test en phase 2).
Gravité : **critique**.

### B7 — La coalescence des frappes undo est du code mort — **[L] confirmé par lecture**

`.../session/UndoManager.java` : `pushStep` remet `lastEditEnd = -1` (`:43`) or
`tryCoalesce` exige `edit.start == lastEditEnd` (`:65`) — la condition ne peut jamais
être vraie (l'état n'est positif qu'à l'intérieur d'une coalescence réussie, `:74`).
Chaque caractère tapé = une étape d'annulation distincte, contrairement à la javadoc
(`:8-10`). Gravité : majeure (UX undo).

### B8 — Reconstruction du modèle de wrap en O(n²), à chaque édition — **[L] confirmé par lecture**

`.../wrap/WrapModel.java:50-54` : `setRows()` appelle `rebuildPrefixSum()` (O(n) + une
allocation `long[n+1]`, `:107-113`) à **chaque** setter ;
`.../view/EditorWrapGeometry.java:176-179` : `rebuildWrapModel()` boucle sur **toutes**
les lignes en appelant `setRows` → O(n²) temps et ~O(n²) octets alloués au total, par
reconstruction. Sur 10 000 lignes avec word-wrap actif, chaque frappe déclenche ~10⁸
itérations. Gravité : **critique** (perfs gros fichiers). Correctif évident : passe en
deux temps (setters sans rebuild + un seul `rebuildPrefixSum()` final).

### B9 — Passes de rendu non sensibles aux plis reçoivent des rangées visuelles — **[L] confirmé par lecture**

`.../render/EditorRenderer.java:87-100` calcule `firstVisible/lastVisible` comme des
**rangées visuelles** — le commentaire du code lui-même (`:87-96`) documente le piège —
et les passe pourtant aux passes non sensibles aux plis : guides d'indentation
(`:143`), sélection (`:155`), surlignages de recherche (`:165`), document highlights
(`:175`), soulignés (`:283`), encadrés de crochets (`:289`). Avec un pli replié au-dessus
du viewport, la bande de sélection et les surlignages s'arrêtent trop tôt (bas du
viewport non dessiné). Seule la boucle de texte (`:217-218`) a été corrigée
(`firstDocVisible/lastDocVisible`). Gravité : majeure.

### B10–B20 — Défauts issus de l'audit systématique — **[A] fichier:ligne cités, à valider par test en phase 2**

Classés par gravité estimée. Chaque correctif en phase 2 embarquera un test qui échoue
avant / passe après, ce qui confirme ou infirme définitivement.

| # | Zone | Problème (scénario) | Fichier:ligne | Gravité |
|---|---|---|---|---|
| B10 | Scroll/plis | `maxV()` = `lineCount × lineHeight` sans soustraire les lignes cachées → on peut scroller ~n×lineHeight dans le vide sous la dernière ligne visible | `view/input/EditorScrollManager.java:140-144` | majeure |
| B11 | Gutter/wrap | la gouttière compte 1 rangée par ligne sans consulter le modèle de wrap → numéros désalignés dès la première ligne wrappée | `view/chrome/GutterView.java:112-163` | majeure |
| B12 | Wrap/plis | `docLineToY` mode wrap ignore les lignes cachées par plis (contrairement au chemin non-wrap) → trou vide + tap sur ligne cachée | `view/EditorWrapGeometry.java:84` | majeure |
| B13 | Wrap/zoom | pinch-zoom change `charWidth` sans reconstruire le modèle de wrap → rangées fausses, débordement à droite | `view/input/EditorZoomController.java:42-50, 70-71` | majeure |
| B14 | Thème | `setTheme` vide le cache de layouts ligatures mais pas le `LineRenderCache` dont les `SemSpan` ont des couleurs cuites → jusqu'à 512 lignes avec les couleurs sémantiques de l'ancien thème | `view/EditorView.java:1078-1086`, `view/EditorLineLayoutResolver.java:154` | majeure |
| B15 | Popups | PopupWindow go-to-line / rename jamais fermés dans `onDetachedFromWindow` → `WindowLeaked`/fuite d'Activity à la rotation | `view/EditorView.java:1246-1271` | majeure |
| B16 | Popups | `dismissReferences()` n'incrémente pas la génération → une résolution LSP en vol rouvre le popup après fermeture utilisateur | `view/EditorReferencesController.java:111-114` | majeure |
| B17 | Rotation | aucune sauvegarde/restauration d'état (`onSaveInstanceState` absent) → zoom, scroll, wrap perdus à la rotation | `view/EditorView.java` (absence) | majeure |
| B18 | IME | clamp du caret après `replaceText` utilise `doc.length() + insertion.length()` sans soustraire la longueur remplacée → caret hors bornes | `session/EditorSession.java:548`, `session/ImeBridge.java:283-293` | majeure |
| B19 | Perf | `getText()` (matérialisation O(n) du document) à chaque frappe/backspace + texte extrait IME complet par frappe | `session/EditorSession.java:558, 658, 691`, `view/EditorImeBridge.java:91` | majeure (perf) |
| B20 | Perf | chemin gouttière « sensible aux replis » parcourt toutes les lignes du document par frame | `view/chrome/GutterView.java:144-154` | majeure (perf) |

### B21 — Mineurs (confirmés ou suspectés, traitement au cas par cas en phase 2)

| # | Problème | Fichier:ligne | Statut |
|---|---|---|---|
| B21a | l'« auto-espace » IME avale une vraie espace tapée après ponctuation (offset de lot jamais réinitialisé) | `session/ImeBridge.java:141-148` | [A] |
| B21b | acceptation de complétion sans garde `caret >= completionTokenStart` → insertion avant le mot si le caret a reculé | `view/popup/EditorCompletionPopup.java:200` | [A] |
| B21c | `FoldModel.compositeText` : colonne de suffixe toujours 0 (suffixe calculé jamais utilisé) | `fold/FoldModel.java:215` | [A] |
| B21d | allocations par frame dans le dessin : 3 `new Paint()`/frame gouttière, `substring` par span hors ligatures, `getCollapsedFolds()` frais par frame | `view/chrome/GutterView.java:123-135, 207, 219`, `render/EditorTextPainter.java:457`, `render/EditorRenderer.java:97` | [A] |
| B21e | `setSession(null)` lève une NPE | `view/EditorView.java:1062-1063` | [A] |
| B21f | mutateurs directs de sélection (`selectAll`, flèches…) ne notifient pas l'IME (`updateSelection` jamais poussé) | `session/EditorSession.java:840-926` | [A] |
| B21g | diagnostics exécutés synchrone si la vue n'a pas encore de Handler | `view/EditorDiagnosticsPusher.java:91-94` | [A] |
| B21h | la session garde une référence forte vers la vue (imeListener) après détachement | `view/EditorView.java:1032-1033, 1246-1271` | [S] |
| B21i | rename fallback : `substring` par position (O(n) allocations) | `view/popup/EditorRenamePopup.java:201` | [A] |
| B21j | tâches async 10-15 s capturant la vue sans garde d'attachement | `view/EditorReferencesController.java:80-107` | [S] |

### Zones auditées saines (lecture)

Scroll/fling propre (clamps par frame, `VelocityTracker` recyclé sur tous les chemins),
`LineRenderCache` borné (512, LRU), `EditorViewportPrefetcher` sain, find/replace
sain en soi (regex invalide → liste vide, wrap-around correct — API non branchée à une
UI de remplacement), ancrage des popups clippé, fermetures croisées des popups de
diagnostic soignées, `EditorFoldIndex` sain et performant.

---

## 1C. Compilation de référence (dépôt tel quel)

*(Résultat consigné à la fin du build de référence — voir §1C-résultats en fin de document.)*

## 1D. Plan de travail ordonné (phase 2)

Ordre imposé par le brief : (1) bugs de sélection/loupe, (2) autres bugs confirmés par
gravité, (3) ports de performance à faible risque, (4) ports fonctionnels. Un commit par
correctif/port (`fix|perf|feat(scope): …`), un test qui échoue avant / passe après pour
chaque bug, compilation avant chaque commit.

### Lot 1 — Sélection et loupe (le bug signalé d'abord)

| # | Commit prévu | Contenu | Bugs couverts | Effort |
|---|---|---|---|---|
| 1 | `fix(render)` | le cache de layouts ne dépend plus de la couleur du paint partagé : couleur de base = couleur de texte du thème, signature = spans uniquement ; test repro B1 (échoue avant) | B1 | S |
| 2 | `fix(render)` | la loupe ne mute plus jamais le `Paint` partagé : paint dédié (clone) avec couleur de base posée ; restauration de l'état ; tests (pas de fuite d'état) | B1 | S |
| 3 | `fix(selection)` | ancre figée au DOWN pour le drag de poignée (modes 1/2) ; test : franchissement de l'autre poignée garde l'ancre | B2 | S |
| 4 | `fix(selection)` | ancre figée au DOWN du drag-select (`handleTouchDrag`) ; test : rétrécissement en revenant en arrière | B3 | S |
| 5 | `fix(loupe)` | `magCx` borné + bornage vertical conscient de la position écran ; `Path` réutilisé (`scratchPath`) ; commentaire périmé corrigé ; `magnifierActive` retombé sur perte de focus / `setSession` / detach ; tests | B4, B5 | S |
| 6 | `feat(loupe)` *(décision propriétaire — recommandé)* | `android.widget.Magnifier` natif (API 28+) qui capture le vrai rendu (sélection, curseur, spans sémantiques, wrap, plis), repli maison corrigé API 24-27 | B1-B | M |

### Lot 2 — Autres bugs confirmés, par gravité

| # | Commit prévu | Contenu | Bugs | Effort |
|---|---|---|---|---|
| 7 | `fix(session)` | undo/redo passe par le point d'entrée commun : shift annotations/folds, garde lecture-seule, notification des listeners texte (LSP) ; tests | B6 | M |
| 8 | `fix(session)` | coalescence des frappes réellement déclenchée (amorçage de `lastEditEnd`) ; test : 400 frappes → une étape annulable | B7 | S |
| 9 | `perf(wrap)` | reconstruction du modèle de wrap en O(n) (setters batch + un seul prefix-sum) ; test de non-régression perf | B8 | S |
| 10 | `fix(render)` | plages de **lignes document** pour toutes les passes non sensibles aux plis (sélection, find, highlights, guides, soulignés, crochets) | B9 | S/M |
| 11 | `fix(scroll)` | `maxV()` conscient des lignes cachées | B10 | S |
| 12 | `fix(gutter)` | conscience du wrap + départ de boucle O(visible) | B11, B20 | M |
| 13 | `fix(wrap)` | `docLineToY`/`docLineForScreenY` mode wrap conscients des plis | B12 | M |
| 14 | `fix(zoom)` | reconstruction du wrap sur changement de taille de police | B13 | S |
| 15 | `fix(theme)` | `setTheme` vide aussi le `LineRenderCache` (couleurs cuites) | B14 | S |
| 16 | `fix(popups)` | fermeture go-to-line/rename au detach + génération références bumpée | B15, B16 | S |
| 17 | `fix(ime)` | clamp du caret après `replaceText` (longueur remplacée soustraite) | B18 | S |
| 18 | `feat(view)` | `onSaveInstanceState` minimal (scroll, zoom, wrap) | B17 | S/M |
| 19 | `fix(ime)` + `fix(view)` | mineurs B21a/b/e/f/g selon validation par test | B21 | S | 

### Lot 3 — Ports de performance à faible risque (réimplémentation du comportement amont)

| # | Commit prévu | Contenu | Origine amont | Effort |
|---|---|---|---|---|
| 20 | `perf(rope)` | scans feuille par feuille (`leafAt` + visitor stoppant) pour l'équilibre et l'appariement de crochets | `c4bec0cf7` | S |
| 21 | `perf(completion)` | début de token sur la rope (plus de matérialisation par frappe) + filtre mémoïsé | `777f1edab`, `f454ede2b` | S/M |
| 22 | `perf(ime)` | traces construites seulement si loggables | `ff9319ae4` | S |
| 23 | `perf(session)` | casser les matérialisations `getText()` par frappe (B19) — lecture rope/substring ciblée | `dae7e9a08`/`8737e423e` (esprit) | M |
| 24 | `perf(render)` | occurrences posées (300 ms) + matchs dessinés par double recherche binaire sur la plage visible | `71eea9425` | M |

### Lot 4 — Ports fonctionnels

| # | Commit prévu | Contenu | Origine amont | Effort |
|---|---|---|---|---|
| 25 | `feat(theme)` | **scheme de couleurs modifiable par l'utilisateur** (version Java compacte : registre d'attributs + presets + persistance JSON + résolution par chaîne de fallback) | `ab3e7d5d0` | L |
| 26 | `feat(highlight)` | distinctions fines : contrôle/modificateurs (table partagée), DOC_COMMENT, CHAR/RAW_STRING, XML fin (namespace/entités/prolog/CDATA), EMPHASIS | `b8cd18824` | M |
| 27 | `feat(selection)` | expand selection (mot → syntagme via spans/crochets → ligne) | `94cd8e9bb` (esprit) | S/M |

### Non retenus pour cette itération (proposés au propriétaire pour plus tard)

- `c7a68e1cd` (révisions de lignes stables du lexer — gros gain mais cœur sensible du
  styling incrémental ; à faire avec une couverture fuzz dédiée) ;
- `4c45c93ae` (re-bin des overlays par ligne éditée — dépend du précédent pour être rentable) ;
- `6dbf5cc50` (inlay hints fenêtrés), `5ac66c9f2` (réutilisation des fold models),
  `dae7e9a08` (update IME partielle de batch contigu — à couvrir par un fuzz test),
  `35f251ef7` (analyseurs projet), `cf57bde12` (collage plain-text), `a8c892ee0`
  (discipline de préemption complète).

**Estimation globale** : 19 correctifs avec tests (lots 1-2), 5 ports perf (lot 3),
2-3 ports fonctionnels dont 1 gros (lot 4). Les lots 1-3 sont sans rupture d'API ; le
port 25 (scheme de couleurs) ajoutera une API publique nouvelle sans casser l'existante
(`EditorTheme` reste la façade).

---

## 1C-résultats. Compilation de référence — résultats

**Environnement d'exécution** (établi pour cette session, à reporter dans `AGENT.md`) :
Linux x64 · **JDK 17.0.20.1** (Temurin, installé sous `/home/z/my-project/jdk17` — le
système ne fournit qu'un JRE 21 **headless sans `javac`**, ce qui fait échouer AGP avec
`does not provide the required capabilities: [JAVA_COMPILER]`) · Gradle 9.5.1 (wrapper) ·
SDK Android installé (cmdline-tools + `platforms;android-34` + `platform-tools`) ·
`local.properties` créé (ignoré par git, vérifié).

| Étape | Commande | Résultat | Détail |
|---|---|---|---|
| Nettoyage + AARs | `./gradlew clean assembleDebug --continue` | **BUILD SUCCESSFUL** (57 s) | 4 AAR debug produits |
| Tests | `./gradlew test --continue` | **BUILD SUCCESSFUL** (49 s) | **934 tests, 0 échec, 0 erreur, 0 ignoré** (cel-core 655, cel-lsp-api 22, cel-lsp 27, cel-ui 230) |
| Lint | `./gradlew lint --continue` | **BUILD SUCCESSFUL** (45 s) | 0 erreur, 0 avertissement sur les 4 modules (rapports XML vides) |

Notes d'exécution : le build complet a été exécuté en 3 invocations séquentielles
(contrainte de l'environnement : les processus longs en tâche de fond y sont tués —
les relancer en avant-plan reprend sur le cache Gradle). Aucune erreur, aucun
avertissement préexistant : **toute régression future sera le fait de nos changements.**

### Test de reproduction du bug signalé (B1) — exécuté sur le dépôt tel quel

`MagnifierColorLeakReproTest` (ajouté non-committé, committé en phase 2 avec le correctif) :

| Test | Résultat sur code non corrigé | Signification |
|---|---|---|
| `shapedLayout_baseColorMustBeThemeText_notResidualPaintColor` | **ÉCHEC** (`MagnifierColorLeakReproTest.java:127`) | le layout servi porte la couleur résiduelle du paint (0xFF123456) au lieu de la couleur de texte du thème → **bug démontré** |
| `shapedLayout_noThrash_whenOnlyPaintColorChanges` | **ÉCHEC** (`:151`) | le cache reconstruit le layout quand seule la couleur du paint change → **thrash démontré** |
| `fullDraw_withMagnifier_leavesTextPaintInSameStateAsWithout` | PASSE | la couleur finale du paint n'est pas le vecteur du bug ; le vecteur est la couleur **cuite dans le layout servi** (conforme au diagnostic B1 : hypothèse A) |

**Conclusion de phase 1** : la cause racine du bug signalé est établie par lecture
croisée **et** démontrée par test exécuté. Le plan 1D peut être appliqué.

---

## Suivi d'exécution de la phase 2 (ajouté en fin de mission)

### Appliqué (13 correctifs + docs, tous avec tests verts avant commit)

| Bugs | Correctif (commit) |
|---|---|
| B1 | `fix(render)` — cache de layouts indépendant de la couleur du paint partagé (tests repro (a)+(b) : échouaient avant) |
| B1 (hygiène) | `fix(render)` — la loupe n'écrit plus jamais dans le textPaint partagé (paint dédié) |
| B2 | `fix(selection)` — ancre du drag de poignée figée au DOWN (franchissement testé dans les 2 sens) |
| B3 + découvertes | `fix(selection)` — drag-select au doigt réparé : armement qui survit au DOWN (il était désarmé à l'UP du tap ET au DOWN suivant), ancre figée à l'armement, l'UP d'un drag ne résout plus de tap |
| B4 + B5 | `fix(render)` — bornage de la loupe (au-dessus/sous le doigt, jamais coupée), chemin de clip réutilisé, commentaire périmé corrigé, retombées sur perte de focus / setSession / detach |
| B6 | `fix(session)` — undo/redo par le pipeline commun (décalage annotations/plis/composition, garde lecture-seule, notification des listeners LSP) |
| B7 | `fix(session)` — coalescence des frappes réellement déclenchée (amorçage de la chaîne) |
| B8 | `perf(wrap)` — reconstruction du modèle de wrap en O(n) (mode en masse) |
| B10 + B13 | `fix(scroll, zoom)` — maxV conscient des plis, wrap reconstruit au zoom |
| B14 | `fix(theme)` — setTheme purge aussi le LineRenderCache (SemSpan aux couleurs cuites) |
| B18 | `fix(ime)` — caret borné à la longueur FINALE du document (doReplaceRange + replaceText IME) |
| — | `chore(credits)` — phase 3 : plus aucune mention dans editor/ (grep = 0) |
| — | `feat(release)` — v3.38.0 : publish.yml automatique sur tag + garde-fou + tests avant publication, AGENT.md, docs |

### Appliqué — session 2026-09-29 (bugs signalés + consultation sora-editor)

| Bugs | Correctif (commit) |
|---|---|
| signalé (tap) | `fix(input)` — le simple appui ne sélectionne plus plusieurs lignes/mots : drag-select armé par l'APPUI LONG (modèle sora `dragSelectAfterLongPress`, ancre à trois indices), sous le slop = RIEN, poignées gate par slop, DOWN/UP/CANCEL désarment |
| signalé (minifier) | `perf(render)` — dessin fenêtré aux colonnes visibles (parité sora `TextRow.draw`) : tranche du StaticLayout ligatures, spans clippés, non-imprimables bornés, wrap par rangées visibles + pointeur de spans, plus d'inlays > 5000 car/ligne |
| B9 | `fix(render)` — les 9 passes non sensibles aux plis reçoivent des plages de lignes document (firstDocVisible/lastDocVisible) |
| B12 | `fix(wrap)` — docLineToY/docLineForScreenY/maxV wrap conscients des plis (rangées cachées par sommes préfixe, recherche binaire « ligne contenant la rangée ») |
| B11 + B20 | `fix(gutter)` — alignement sur la géométrie hôte (wrap + plis) et itération O(visible) via GutterView.HostGeometry |
| B15 + B16 + B21e | `fix(popups,session)` — popups go-to-line/rename/références fermés au détachement, dismissReferences bump la génération, setSession(null) sans NPE |

### Restant (priorisé, chaque item a son plan au §1D)

- **B17** (onSaveInstanceState minimal : scroll/zoom/wrap) ;
- **B19** (matérialisations getText() par frappe) et les ports de
  performance du lot 3 (scans de rope feuille par feuille, début de token
  de complétion sur la rope, filtre mémoïsé, traces IME conditionnelles,
  occurrences posées) ;
- **Lot 4 fonctionnel** : scheme de couleurs modifiable par l'utilisateur
  (l'approche complète est décrite au §1A — `ab3e7d5d0`), distinctions de
  tokens fines (`b8cd18824`), expand selection (`94cd8e9bb`), Magnifier
  natif API 28+ avec repli (recommandé, décision propriétaire) ;
- **B21 mineurs** (a, b, c, d, e, f, g, i, j) selon validation par test.

Raison de l'arrêt : budget de la session consacré en priorité aux bugs du
brief (sélection/loupe), aux correctifs critiques/majeurs démontrés, aux
phases 3-4 contractuelles (nettoyage, publication, archive). Le plan 1D
reste le contrat d'exécution pour la suite.

### Appliqué — session 2026-09-30 (bugs restants + signalés + mineurs)

| Bugs | Correctif (commit) |
|---|---|
| signalé (wrap) | `fix(render)` — bandes de sélection/surlignages wrap-aware (drawColRangeBand : une rect par rangée visuelle) — l'ancienne rect pleine ligne partait HORS ÉCRAN dès la 2e rangée wrappée (config demo : wrap actif, « Minifié (JS) »). Reproduit au pixel avant (0 pixel de couleur de sélection), corrigé après ; les 3 passes (sélection, recherche, occurrences LSP) partagent le helper |
| B17 | `feat(view)` — onSaveInstanceState/onRestoreInstanceState (SavedState : vOffset/hOffset/fontScale/wordWrap, ordre zoom→wrap→scroll, offsets bruts sans session fiable) |
| B21a | `fix(session)` — auto-espace IME : validation caret/symbole avant d'avaler + reset du suivi sur toutes les autres mutations IME |
| B21b | `fix(popup)` — garde caret < completionTokenStart à l'acceptation |
| B21c | `fix(fold)` — FoldedLineInfo porte lastLineFoldEndCol (colonne calculée puis jetée ; formule de substitution toujours 0) |
| B21d | `perf(view)` — zéro allocation par frame : 4 Paint hoistés (GutterView), hasCollapsedFolds() sans allocation (renderer + géométrie wrap), substring par span remplacé par test charAt (path non-ligatures) |
| B21f | `fix(session)` — tous les mutateurs de sélection notifient l'IME (via setSelection) |
| B21g | `fix(view)` — schedule de diagnostics différé à l'attachement (plus d'exécution synchrone sans Handler) |
| B21i | `perf(popup)` — rename fallback : comparaison en place (charAt), plus de substring par position |
| B21j | `fix(view)` — résultat de résolution jeté si la vue est détachée au retour |
| B19 + lot 3 | `perf(session,ui)` — EditorDocument.subText()/charSequence() (lectures ciblées sur la rope) ; frappe intelligente, appariement de crochets, complétion (début de token + fraîcheur de livraison, filtre mémoïsé déjà présent), tranches de sélection, extraction IME (ExtractedText fenêtré, SurroundingText, composition, code points) migrés — plus de copie du document entier par frappe |

### Restant après la session 2026-09-30 (pour une itération future)

- **Lot 4 fonctionnel** : scheme de couleurs modifiable
  (`ab3e7d5d0`), distinctions fines de tokens (`b8cd18824`),
  expand selection (`94cd8e9bb`), Magnifier natif API 28+ avec repli ;
- **Lot 3 restant** : scans de rope feuille par feuille pour
  l'équilibre (`c4bec0cf7` — partiellement couvert par les lectures B19),
  occurrences posées + dessin par double recherche binaire
  (`71eea9425`), traces IME conditionnelles (sans objet : aucune trace
  de ce type dans ce dépôt) ;
- **B21h** (référence forte session→vue après détachement) — [S], à
  démontrer par test de cycle de vie.
