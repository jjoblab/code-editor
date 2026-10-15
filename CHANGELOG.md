# Changelog

All notable changes to **code-editor-lib** are documented in this file.

Le format suit la convention [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Les sections antérieures à la période courante sont archivées dans
[`docs/CHANGELOG-archive.md`](docs/CHANGELOG-archive.md) (A-08, audit 3.69.0).

## [3.71.0] — non publié — Exécution du plan d'audit 3.69.0 (lots L1→L10)

### Added

- **Complétion : snippets, `textEdit`, `filterText`, `sortText` (D-01)** — `CompletionItem` (SPI) transporte le format snippet, la plage de remplacement (sémantique *remplacement*, normalisée), les champs de filtrage et de tri ; `SnippetSession` est CÂBLÉE : expansion à l'acceptation (texte apuré, aucun marqueur `$` résiduel), navigation Tab/Shift+Tab en ordre LSP ($1 → $2 → $0), miroirs synchronisés, `OnSnippetEditListener` enfin déclenché ; la plage `textEdit` du serveur est appliquée exactement (correctif des « console.console.log »).
- **Timeouts LSP par requête (L1-bis)** — `LspRequestTimeouts`, surchargeables par définition de serveur ; garde-fou sur la poignée de main `initialize` (10 s) via fil démon interrompu à la complétion.
- **États de recherche sûre (D-05)** — `FindReplace.SearchResult` (`truncated` / `tooCostly`), budget temps 250 ms, plafond 10 000 correspondances ; `EditorSearchController.isTooCostly()` / `isTruncated()` ; la barre affiche « expression trop coûteuse » et « N/10000+ ».

### Fixed

### Changed (L9 — A-06 tests)

- **Attente bornée partagée (`AttenteBornee`)** — les scrutations à la main des tests (boucle while + `Thread.sleep` + try/catch, dupliquées fichier par fichier) sont remplacées par un helper par module : les `Thread.sleep` des tests passent de 44 à 19 (÷2,3) ; les temporisations restantes sont des sujets de test (fenêtres multi-tap, marges d'absence de doublon) documentés sur place.
- **Réflexion de métriques centralisée (`TestMetrics`)** — 50 fichiers de test dupliquaient chacun sa copie privée `injectMetrics` + `setFloat` (getDeclaredField/setAccessible éparpillés) : le point d'accès de test unique concentre la réflexion (setAccessible 97 → 48, ÷2) sans ajouter la moindre méthode publique au code de production (cliquet A-01 : `EditorView` est à 279/280).
- **`V3340RegressionTest` renommé par sujet** — `RestyleLifecycleRegressionTest` (cycle de vie/disposal, buckets, re-planification du restyle) : le nom ne date plus le test d'une version, il dit ce qu'il protège.
- **Couverture CI en mode rapport** — `enableUnitTestCoverage` sur les 4 modules + étape CI `createDebugUnitTestCoverageReport` et téléversement des rapports : JAMAIS bloquante (aucun seuil) — la couverture informe d'abord, le passage bloquant se discutera sur des chiffres réels.
- **Suite exécutée 5× consécutives sans échec intermittent** (validation de l'acceptance A-06 sur cet environnement).

### Changed (L10 — A-08)

- **CHANGELOG scindé** — 76 sections historiques déplacées vers `docs/CHANGELOG-archive.md` (le fichier racine garde les 10 plus récentes : 354 Ko → 38 Ko lisibles en routine, historique inchangé) ; préambule restauré (perdu lors de la résolution du conflit de fusion 3.70.1) avec renvoi vers l'archive.
- **Exceptions `prebuilt/` retirées du `.gitignore`** — négations pour un répertoire inexistant (aucun chemin `prebuilt/` dans le dépôt).

### Changed (L10 — A-05)

- **Accessibilité : la barre de symboles déclenche ses actions depuis `performClick`** — le dispatch de l'action vit désormais dans la surcharge (`SymbolKey.performClick`), appelée par le chemin tactile ET par les services d'accessibilité (TalkBack déclenche la même action) ; chaque touche porte un `contentDescription` (nœud minimal). L'entrée `ClickableViewAccessibility` de `SymbolBarView` est retirée de la baseline — problème réellement corrigé, pas masqué (l'entrée `EditorView` reste : `performClick` est bien appelé à la résolution du tap, dans le résolveur — l'analyse intra-procédurale de lint ne le voit pas).
- **Derniers libellés en dur externalisés** — « Go to line » et « Rename "x" » deviennent `cel_goto_line_title` / `cel_rename_title` (l'hôte peut les traduire ou les surcharger).
- **RTL : non-support documenté** — décision du propriétaire (ADR-001 n° 7) consignée dans `USAGE.md`, avec renvoi au plan d'implémentation existant si le besoin surgit.

### Changed (L10 — A-07)

- **Politique de dépréciation et compatibilité** — CONTRIBUTING.md fixe le palier obligatoire : `@Deprecated` (avec pourquoi/remplacement + CHANGELOG) → un palier mineur fonctionnel → retrait au palier suivant ; le contrat nullité (`@NonNull`/`@Nullable`, rétention CLASS, nul impact consommateur) ; contrôle de compatibilité binaire en CI en deux temps (avertissement puis bloquant — en attendant, revue humaine des signatures publiques par PR).
- **Nullité du contrat SPI annotée (le plus grand écart de l'audit)** — les six modèles de requêtes/réponses les plus échangés de `cel-lsp-api` portent désormais leurs annotations : `CompletionItem` (7 champs), `Diagnostic`, `HoverContent`, `Symbol`, `TextEdit` — champs ET paramètres constructeurs. Début de l'adoption : `cel-core` suivra progressivement (même motif, `androidx.annotation` en `compileOnly` — absent du POM consommateur).

### Changed (L10 — A-03, A-04)

- **Catalogue de versions centralisé (`gradle/libs.versions.toml`)** — les versions en dur éparpillées dans les cinq fichiers de build masquaient leur retard à la CI : le catalogue les EXPOSE désormais (les retards deviennent des erreurs lint visibles, plus des entrées baseline anonymes). Sept dépendances passent au courant : `androidx.annotation` 1.11.0, `androidx.test:core` 1.7.0, `androidx.test.ext:junit` 1.3.0, `gson` 2.14.0, JUnit Jupiter/Vintage 6.1.3, `robolectric` 4.17 — suite complète verte.
- **Retiens délibérées, documentées dans le catalogue ET la baseline** : `lsp4j` 0.22.0 (la 1.0.0 rompt le contrat — `WorkspaceEdit` porte des `Either<TextEdit, SnippetTextEdit>`, `Hover` simplifie sa somme : l'adoption est un lot de fonctionnalité « éditions snippet », pas une mise à jour) ; `appcompat`/`material` (les versions courantes exigent compileSdk 35+) ; `compileSdk` 34 (décision de toolchain du propriétaire). 18 entrées baseline périmées retirées ; chaque retenue porte son pourquoi.
- **Vérification minifiée (A-04)** — les quatre modules assemblés en release R8 (`proguard-android-optimize` par défaut) : **BUILD VERT, zéro erreur, zéro classe manquante** — l'écriture des règles consommateur peut partir de faits, pas de suppositions ; la minification reste désactivée par défaut (décision de publication = propriétaire).

### Changed (L10 — A-01)

- **Clivet A-01 : le plafond DESCEND** — première extraction de la décomposition d'`EditorView` : l'état Parcelable (`SavedState`, 60 lignes imbriquées) devient `EditorSavedState`, classe de package dédiée ; la vue conserve les points d'attache View. `EditorView` : 3840 → 3799 lignes (276/280 méthodes publiques) ; **plafond abaissé de 4100 à 3810** — tout relèvement futur exige la décision du propriétaire, chaque extraction accompagne désormais son abaissement. Le test d'état d'instance (parcellisation, restauration) couvre l'extraction.

### Changed (post-plan — décisions du propriétaire)

- **Licence du projet : MIT → Apache 2.0** — `LICENSE` porte désormais le texte canonique Apache License 2.0 ; la mention de copyright du projet (2024-2026, code-editor-lib contributors) vit dans `NOTICE`, qui documente en sus le régime des œuvres combinées avec l'EPL-2.0 de la dépendance LSP embarquée par `cel-lsp` ; les POM des quatre modules publiés consignent la licence (`Apache-2.0`) pour les consommateurs et les scans de conformité ; le README est aligné.

### Changed (plan 01 — décomposition d'EditorView, étapes 2, 3 et 5)

- **`CompletionState` : l'état du popup de complétion quitte la vue** — liste affichée, sélection, fenêtre de scroll, token/préfixe, visibilité et cache de filtrage par token vivent désormais dans `view/state/CompletionState`, qui POSSÈDE les invariants (fenêtre solidaire de la sélection dans les deux sens, survie de l'item sélectionné par libellé au refiltrage, bornes du drag, cohérence visibilité/contenu). Le popup, l'input handler, le hit-tester et les painters consomment l'objet d'état ; les invariants sont testés en JVM pure (`CompletionStateTest`, zéro Robolectric — le gain de testabilité visé par le plan). `EditorView` : 3799 → 3779 lignes, 180 → 170 champs ; **plafonds abaissés 3810 → 3790 lignes et 190 → 180 champs** (cliquet, marge 11 lignes, style maison). API publique inchangée : `setCompletionItems` délègue à l'état.
- **`FindState` : l'état de rendu des surlignages de recherche quitte la vue** — occurrences teintées et occurrence courante vivent dans `view/state/FindState`, qui possède l'invariant « index courant toujours dans les bornes » (un index hors bornes venant d'un hôte est ramené à la dernière occurrence, jamais de teinte courante fantôme) et copie défensivement la liste poussée (le contrôleur peut muter sa copie sans corrompre le rendu). La purge au changement de document (B10) passe par l'état ; le painter de surlignage et le renderer lisent `view.find`. Tests JVM purs (`FindStateTest`). `EditorView` : 3779 → 3777 lignes, 170 → 169 champs ; **plafonds abaissés 3790 → 3788 lignes et 180 → 179 champs**. API publique inchangée : `setFindHighlights` délègue à l'état.
- **`ScrollState` : la position de défilement quitte la vue** — `vOffset`/`hOffset` (225 accès dispersés dans 28 fichiers : pipeline de scroll, zoom, tactile, popups, painters, gouttière, fast-scroller) vivent dans `view/state/ScrollState`, qui possède l'invariant « offsets dans [0, max] » (`clampTo` aux changements de géométrie et après toute mutation non bornée) et les deux chemins de restauration d'état d'instance (bornée quand la géométrie est connue, brute sinon — le bornage survient au premier usage). `EditorView` : 3777 → 3775 lignes, 169 → 168 champs ; **plafonds abaissés 3788 → 3786 lignes et 179 → 178 champs**. API publique inchangée : `getVOffset`/`getHOffset` délèguent. Tests JVM purs (`ScrollStateTest`) + tests de non-régression du plan (`ScrollMaxVAndZoomWrapTest`, `MaxHIncrementalTest`, `EditorInstanceStateTest`) inchangés et verts.
- **Étape 4 (FoldViewState) : constat vide, rien à extraire** — l'état de pli (`collapsedByDefault`, `foldRequests`) avait déjà intégralement migré vers `cel-core` (`FoldRegions`, session) et le collaborateur `EditorFoldIndex` lors des lots B14 antérieurs ; le plan 01 le décrivait sur la base 3.49.2. `FoldAwareRenderPassesTest` vert sans modification, comme l'exigeait le critère d'acceptance.
- **Étape 6 (miroirs de sélection) : constat vide, rien à supprimer** — la vue ne cache aucune copie de la sélection : les 51 lectures passent déjà directement par `session.getSelection()` (déménagement antérieur, lots M) ; ne subsistent que l'état de la TOOLBAR de sélection (champ UI, pas un miroir). Critère du plan (« grep des lectures de view.selection* ») satisfait d'office.
- **Clôture du plan 01** — les extractions d'état prescrites restantes (2, 3, 5) sont livrées, les étapes 4 et 6 étaient déjà accomplies par les lots antérieurs ; `ARCHITECTURE.md` documente le nouveau package `view/state/` et remesure l'orchestrateur (3 775 l.). La trajectoire complète (≤ 1 200 l.) reste le chantier long terme du plan — chaque nouvelle extraction accompagnera son abaissement de plafond, cliquet en main.

### Changed (plan 03 — inversion de dépendance cel-lsp → cel-ui)

- **`EditorHost` : le client LSP ne dépend plus de la vue** — nouvelle interface `jo.codeeditor.lang.host.EditorHost` (`cel-lsp-api`) : session/document liés (nullité normale du cycle de vie), pose du SPI langage, notifications diagnostics/texte, invalidation et marshalling thread UI (sémantique `View.post`/`postDelayed` documentée) ; le contrat n'expose QUE de l'API publique, aucun scell `@RestrictTo` ne peut fuiter. `LspEditorViewHost` (`cel-ui`) : adaptateur de référence, sans état, neuf délégations d'une ligne — le SEUL point où la couche LSP rencontre la vue Android. **`compileOnly(project(":cel-ui"))` retiré de `cel-lsp`** ; la règle de paquet exigée par le plan vit dans `check-class-budgets.sh` (toute référence `jo.codeeditor.view` dans `cel-lsp`/`cel-lsp-api`, ou retour du `compileOnly`, échoue la CI). Les six fichiers du plan migrés (`LspEditor`, `LspDiagnosticsManager`, `LspDocumentSynchronizer`, `LspFoldPuller`, `LspSemanticTokensPuller`, `LspWorkspaceEditApplier` — plus `LspInlayHintProvider` qui chaînait `getEditorView()`), les Javadoc `{@link}` vers la vue remplacées par de la prose.
- **RUPTURE SIGNALÉE — `LspEditor.setEditorView(EditorView)` → `setHost(EditorHost)`** (de même `getEditorView()` → `getHost()`) : l'adaptation fournie tient en une ligne — `editor.setHost(new LspEditorViewHost(editorView))` (extraits README/USAGE et rapports d'intégration mis à jour). Un hôte sans vue peut implémenter `EditorHost` lui-même et n'a plus besoin de `cel-ui` pour parler LSP. Le pont se teste désormais en JVM PURE : `LspEditorHostBridgeTest` (conversions offset↔Position, dégradation docile sans hôte, publication des diagnostics dans les deux régimes de marshalling — détaché = en ligne, attaché = file UI — sans Robolectric). Graphe de modules (`ARCHITECTURE.md`), tables (`README.md`, `AGENT.md`) et guide d'installation resynchronisés.

### Fixed (D-09)

- **Documentation dérivée resynchronisée** — README/USAGE annonçaient `3.49.2`, BUILD `3.54.0`, SECURITY `3.49.x` (réalité : `3.70.1`) ; le graphe des modules d'AGENT.md omettait `cel-lsp-api → cel-core (api)` et `cel-lsp → cel-ui (compileOnly)` ; les plans 01/02 citaient les collaborateurs `EditorBarTools` (retiré en B15) et `SurroundWith` (retiré au lot M-02). `check-doc-figures.sh` verrouille désormais l'égalité version README/USAGE/BUILD ↔ `build.gradle.kts` (rouge/vert testé par injection d'une version divergente).

- **Fuite de thread par session (D-02)** — le thread `EditorSession-restyle` expire après 30 s d'inactivité (`allowCoreThreadTimeOut`) : la fuite à vie quand l'hôte oublie `dispose()` est impossible ; Javadoc de `dispose()` corrigée (l'`EditorView` NE l'appelle pas pour vous — la libération est à la charge de l'hôte).
- **Recherche regex sûre (D-05)** — une regex catastrophique (`(a+)+b` sur texte adverse) figeait le THREAD UI (ANR) : la vue garde-fou interrompt le backtracking au budget ; les passes de remplacement avortent proprement (document jamais muté à moitié) ; construction du `Pattern` centralisée (duplication supprimée).
- **Ordre de navigation des snippets** — `SnippetSession` visitait $2 avant $1 (tri décroissant, invisible tant que la classe n'était pas câblée) : ordre LSP correct.

### Changed

- **`LspFeature` honoré (D-03)** — `setDisabledFeatures` a désormais un effet : la porte est évaluée AVANT l'installation des providers (aucune requête n'atteint le serveur pour une fonctionnalité désactivée) ; trois valeurs couvrant la famille GO TO (`TYPE_DEFINITION`, `IMPLEMENTATIONS`, `SUPER_DEFINITION`).
- **`EditOps.tryPostfix` (D-04)** — le résultat de complétion postfix est un objet immuable retourné PAR APPEL (l'ancien champ statique partagé — source d'insertion erronée entre sessions concurrentes — est retiré).

### Deprecated

- `EditOps.tryPostfixCompletion` / `EditOps.getPostfixInsertText` (D-04) : la paire reposait sur un état global ; `getPostfixInsertText` est inerte (null). Retrait au palier suivant.

### Removed

- **M-02** : `SurroundWith` (103 l., zéro référence en production). `NavigationMenu` perd sa machinerie de picker d'instance jamais appelée (`runNav`, `movePickerSelection`, `selectPickerOption`, `navigateToOption`, `isPickerOpen`, `getSelectedOption`, constructeurs, `setResolver`/`setListener`, `closePicker`/`reset`, interfaces `NavigationResolver`/`NavigationListener`) — la classe devient le porte-type des éléments réellement consommés (`NavKind`, `NavTarget`, `NavOption`, `Symbol`, `filter`, `isCamelHumpSubsequence`).

### Changed (M-02)

- **Une seule implémentation de la fusion de régions de plis** : `FoldModel.mergeRegions` (publique, testée) et l'index de plis de la vue délègue — la copie interne divergente est retirée.

### Performance (L7)

- **Frappe multi-caractères : plus de copie du préfixe du document (P-01)** — la complétion postfix simule l'insertion via une vue `CharSequence` paresseuse (`EditOps.simulatedInsert`) : l'ancien chemin matérialisait `subSequence(0, pos) + texte` — le PRÉFIXE ENTIER du document, ~1 Mo copié par frappe sur un fichier d'un million de caractères (mesure de base : 3,02 ms/op sur 25 000 lignes, caret en fin). La vue sert les lectures de l'analyse (postfix ≤ 8 caractères, expression, retrait de ligne) via la rope sans copie : 0,24 ms/op après correctif (÷12,5), benchmark de non-régression borné à 2,5 ms/op.

- **Tooltip d'inlay : plus de document entier matérialisé (P-06)** — l'appui long sur un inlay hint capturait `getText()` (copie complète du document, thread UI) pour la résolution différée du tooltip ; il capture désormais la vue de rope immuable (zéro copie, instantané cohérent pour le thread « features » — l'interface `InlayTooltipResolver` prend déjà `CharSequence`).
- **Plus de `RectF` alloué par draw dans les painters (P-05)** — les 16 sites d'allocation (`EditorAssistPopupPainter` popups/culots/badges, `EditorChromePainter` cartes glass/rangées pressées/ampoule/ombre-pilule, `EditorDiagnosticsPainter` pilules/feuille/carte) réutilisent un champ `scratchRect` par painter, sur le modèle du `scratchPath` existant : chaque site fait `set` → draws immédiats séquentiels (mono-thread UI), zéro allocation par frame.
- **Motif de label `case` précompilé (P-04)** — `CodeContext.isCaseLabel` recompilait son expression régulière (`String.matches`) à CHAQUE reconnaissance, sur le chemin de la touche Entrée : le motif est désormais un `Pattern` statique final ; contrat du prédicat figé par test (labels case/default, refus sans deux-points).
- **Détection de plis : un seul découpage du document (P-03)** — `FoldDetector.detect` découpait le texte en lignes dans CHAQUE détecteur composé (imports + accolades + régions nommées : jusqu'à trois découpages du document entier par appel, trois tableaux et trois fois toutes les lignes alloués). Le découpage est désormais unique et partagé entre les détecteurs d'un même appel (cœurs package-private sur `String[]`, entrées publiques mono-détecteur inchangées) ; test de cohérence garantissant l'union exacte.
- **Sync LSP incrémentale pilotée par les éditions suivies (P-02)** — plus AUCUNE copie du document sur le chemin de frappe : `OnTextEditListener` gagne une variante 4-arg (défaut, rétrocompatible) portant le texte retiré ; le pont enregistre chaque édition avec ses positions LSP calculées au moment de l'édition (O(log n) par l'index de lignes) et une vue immuable de la rope (capture O(1)) — l'ancien pont appelait `getText()` à CHAQUE frappe (2,16 ms/op sur un document d'un million de caractères, désormais 0,029 ms — ÷75,8). À l'envoi, les événements partent TELS QUELS en événements de contenu séquentiels (sémantique LSP native) ; l'ancien diff préfixe/suffixe sur les textes entiers + sa RECONSTRUCTION DE VÉRIFICATION (trois copies O(n) + comparaison, 8,5 ms par envoi sur un million de caractères) sont remplacés par deux invariants O(1) : consécutivité des révisions du document (toute mutation non suivie, même à longueur constante, rompt la chaîne) et longueur finale attendue — tout écart → repli plein texte. La base « état serveur » devient une vue de rope (partage les feuilles) et non plus une copie `String` retenue par document. `LspEditor.onTextChanged` déprécié (resync plein texte forcé à la demande).
- **Pont LSP posé sur vue non attachée (découvert en équipant P-02)** — `connect()` confiait la pose du langage et du forwarder à `View.post()` dont le retour ne détecte pas la non-attache (l'action part en file d'attente jusqu'à l'attachement) : une vue jamais attachée laissait le LSP silencieusement mort alors que `connect()` annonçait SUCCESS. La décision se prend désormais sur `isAttachedToWindow()`.

### Added (M-03)

- **Contrats des API publiques sans test** : `BlockEditor` (arbre de blocs : parse, structure, localisation par offset, cycle setSource/getRoot) et `SymbolBarView` (construction, écouteur, cheminement du toucher) reçoivent leur premier test — politique M-03 « au moins un test ».

## [3.70.1] — 2026-10-15 — Correctif complétion XML : la porte de contexte respecte la famille XML

(Réédition de 3.70.0 : la construction JitPack de ce tag avait échoué sur un
rate-limit transitoire de Maven Central — HTTP 429 — sans lien avec le code ;
même contenu, seul le numéro change pour forcer une construction fraîche.)

Retour utilisateur CodeIDE : « le LSP XML ne propose plus de complétion ».
L'enquête remonte à la porte de contexte du Lot 3 (audit v3.41.0) : son
prédicat `isInStringOrComment` (machine à états calibrée pour les
littéraux C/Java) se trompe systématiquement sur XML :

- le curseur entre les guillemets d'une **valeur d'attribut en frappe**
  (`android:orientation="|`) — LA zone légitime des propositions de
  valeurs (énumérations, références `@…`) — était classée « dans une
  chaîne littérale » → `dismissCompletion()` systématique ;
- une **apostrophe du texte français** (`Nom d'utilisateur` — hors de
  tout littéral pour XML) ouvrait un « littéral char » jamais refermé :
  tout le fichier en dessous était classé « dans une chaîne » → plus
  AUCUNE complétion (balises ni attributs) sous cette ligne.

### Fixed

- **`EditorCompletionPopup.isInCodeContext`** : pour la famille
  `SyntaxFamily.XML` (xml, html, htm, svg), la porte
  chaîne/commentaire est ÉCARTÉE — le SERVEUR LSP tranche déjà
  lui-même les zones muettes (texte, fermantes, commentaires `<!--`,
  PI `<?`) ; la porte LOG reste active, et les familles C-like
  conservent le comportement du Lot 3 inchangé.

### Tests

- +4 (1 776 verts) : `CompletionContextGateXmlTest` ×4 (cel-ui) —
  valeur d'attribut (char déclencheur `"`), apostrophe française
  au-dessus du curseur, préfixe de balise (non-régression), littéral
  Java toujours bloqué (non-régression). Rouges sans le correctif,
  verts avec.

## [3.69.0] — 2026-10-14 — Lot B15 : API GutterAction (P2-2) + suppression d'EditorBarTools

Sixième lot de la feuille de route issue de l'analyse des écarts n°6 :
premier pas dans les P2 « différenciateurs » — P2-2, l'API
`GutterAction` généralisée fondée sur les fondations B9c. L'hôte
injecte ses propres icônes de gouttière (points d'arrêt du débogueur
CodeIDE, « exécuter ce test », flèches de navigation de diff) sans
toucher au renderer. Ce lot porte AUSSI la première rupture d'API
assumée de la bibliothèque : la suppression d'EditorBarTools, ancienne
barre pré-décomposition orpheline depuis B5 — décision explicite du
propriétaire (l'audit avait proposé une dépréciation ; la suppression
a été préférée).

### Added

- **B15 — API `GutterAction`** (classe publique, `view`) : `{ id,
  line, icon, perform, active }` façon `SelectionAction` — `active`
  est un `BooleanSupplier` RELU AU RENDU (l'hôte arme/désarme puis
  `invalidate()`, sans ré-enregistrement), `perform` exécuté au tap,
  `null` = sobre / geste quand même consommé.
- **B15 — registre `EditorView.gutterActions`** (champ public final,
  précédent `gutterView`) : `add` (retire la concurrente de même ligne
  OU de même id — UNE pill par ligne, la dernière inscription gagne),
  `remove(id)`, `clear()`, `size()`, `actionForLine(line)`. Données
  d'hôte : survivent à `setSession` (parité avec les actions de pill),
  l'hôte vide le registre quand il change de fichier.
- **B15 — rendu + tap déterministes** : la pill de l'action vit dans
  la zone des numéros de SA ligne (géométrie B9c — `hostActionRect`
  délègue à `previewActionRect`, source unique), l'action de l'hôte
  passe AVANT l'œil d'aperçu sur la rangée du caret ; le tap exécute
  `perform()` sans déplacer le caret et hors cascade multi-tap
  (précédent B9c / bande de fold). Ligne au-delà du document : ni
  dessinée ni touchable.
- **B15 — icône `EditorIcons.Icon.BREAKPOINT`** : disque plein
  (Material « fiber_manual_record », 4 cubiques — le parseur rejette
  les arcs).
- Démo : menu « Point d'arrêt (ligne du caret) » — pose/retire via
  `gutterActions`, tap sur la pill = armé/désarmé (fournisseur actif
  relu au rendu), registre vidé au changement d'échantillon (bonne
  pratique hôte documentée au guide).

### Removed

- **RUPTURE D'API — `EditorBarTools` supprimé** (décision du
  propriétaire). L'ancienne barre d'outils pré-décomposition
  (annuler/rétablir/A+/A−/¶/ligatures) était orpheline depuis B5
  (v3.58.0) : zéro instanciation dans code-editor, la démo ou CodeIDE
  — les boutons vivent dans `BreadcrumbBar`. Migration :
  `BreadcrumbBar.addAction(label, contentDescription, active, click)`
  (voir MISE-A-JOUR-CODEIDE.md §1.15). Nettoyage inclus :
  commentaires résiduels, javadoc `invalidateActionsIn`, entrée du
  lint-baseline.

### Tests

- +14 (1 772 verts) : `GutterActionsTest` ×14 (cel-ui) — rect
  déterministe partagé, registre (une action par ligne, id déplacé,
  remove/clear), hit-test (dans la pill / hors pill / hors gouttière
  / rangée hors écran), tap (perform sans caret, double-tap hors
  cascade, sans perform consommé), rendu (priorité sur l'œil,
  fournisseur actif relu au dessin, ligne hors document), icône
  BREAKPOINT (bornes + FILL + peinture préservée). Preuves rouges ×3
  par sed inverse sur fichiers commités : hit-test du resolver → 3
  tests de tap ; consultation du rendu → 2 tests de dessin ;
  remplacement par ligne → 1 test de registre.

## [3.68.0] — 2026-10-13 — Lot B14 : plis par niveau + régions (P1-5)

Cinquième lot de la feuille de route issue de l'analyse des écarts n°6 :
P1-5 — le pliage sélectif par niveau (« méthodes vs classes », les
régions de commentaire étant déjà détectées) et « tout
replier/déplier ». Le toggle par ligne existait (bande + chips) ;
manquaient la commande de masse par niveau de la hiérarchie (la
profondeur d'imbrication, déjà portée par les plis) et la tolérance
des marqueurs de région à la syntaxe C#/IntelliJ. Zéro rupture d'API
(trois méthodes de session, rien retiré, aucun comportement par
défaut changé).

### Added

- **B14 — `EditorSession.collapseFoldsAtLevel(n)` / `collapseAllFolds()`
  / `expandAllFolds()`** : « Replier au niveau N » à la VS Code Fold
  Level N — replie toute région de profondeur d'imbrication EXACTE
  (1 = classes, 2 = méthodes, 3 = blocs internes) ; les parents
  restent OUVERTS et chaque région porte son état (déplier la classe
  révèle les méthodes ENCORE repliées). « Tout replier » / « tout
  déplier » tous niveaux confondus. Retour compté, `foldRev`
  incrémenté → l'index mémoïsé de la vue (`isLineFoldedCached`) se
  reconstruit sans re-pose de session.
- **B14 — régions nommées : marqueur sans espace** — `#region …
  #endregion` (C#) et `//region … //endregion` (IntelliJ) en plus des
  formes historiques espacées ; le NOM de la région (chip) est extrait
  des deux formes.
- Démo : menu « Plis » (replier niveau 1/2/3, tout replier, tout
  déplier — libellés en ressources `@string`) + région « Opérations »
  tissée dans l'échantillon Java.

### Fixed

- **Off-by-one PRÉEXISTANT dans le nom des régions** :
  `extractRegionName` coupait le marqueur à la mauvaise longueur sur
  les DEUX formes historiques (« // region » → un « n » parasite en
  tête du nom affiché sur la chip : « n Services » au lieu de
  « Services » ; « # region » pareil). Découvert par les nouveaux
  tests ; la chip porte désormais le VRAI nom.

### Tests

- +12 (1 758 verts) : `CollapseFoldLevelsTest` ×9 (cel-core : niveaux
  1/2/3 exacts — la classe reste ouverte au niveau 2, cohabitation
  des états par niveau, tout replier/déplier + idempotence, no-op
  niveau invalide, régions C#/IntelliJ non espacées, régression des
  formes historiques, régions imbriquées et profondeur) ;
  `FoldLevelsViewTest` ×3 (Robolectric : l'index de vue suit la
  révision de plis sans re-pose de session — repli niveau 1, niveau
  2, tout déplier).

## [3.67.0] — 2026-10-13 — Lot B13 : drag-déposer de bloc (P1-2)

Quatrième lot de la feuille de route issue de l'analyse des écarts n°6 :
P1-2 — déplacer du texte par glisser-déposer, parité TextView drag &
drop. La convention Android veut que glisser une sélection la
DÉPLACE ; l'éditeur étendait la sélection au drag (appui long), le
déplacement exigeait copier-coller via la pill. Le mode « drag de
bloc » est armé par la pill (item ✥ Move) — recommandation de
l'analyse : un geste direct entrerait en conflit avec l'extension de
sélection. Zéro rupture d'API (une méthode de session + quatre
méthodes de vue + une action pill, rien retiré, aucun comportement
par défaut changé).

### Added

- **B13 — `EditorSession.moveRange(start, end, target)`** : moteur du
  déplacement — suppression + réinsertion en UNE étape d'annulation
  (lot d'éditions), sélection ré-épinglée sur le texte déplacé
  (aller-retour undo/redo et re-drag stables). Sémantique CARACTÈRE
  exacte (parité TextView) : `target` est l'offset du caret fantôme
  AVANT suppression, corrigé de la longueur retirée au-delà de la
  source ; le trou de la source garde ses séparateurs. No-op propre
  (false) en lecture-seule, plage vide (caret), bornes inversées ou
  dépôt dans la source — la pile d'annulation n'est jamais polluée.
- **B13 — `EditorBlockDragController` + `EditorView.armBlockDrag()`** :
  le mode armé par la pill ✥ Move (`BuiltinSelectionActions.blockDrag`,
  même SPI que les actions d'hôte). Le geste SUIVANT appartient au
  bloc (englouti comme celui de la pill — pas de scroll ni de caret
  derrière) : drag = déplacement, tap = annulation du mode (document
  intact, sélection d'origine conservée),
  CANCEL/pincement/multi-doigts/changement de session = retombée
  propre. Pendant le drag : loupe stabilisée (Y snappé à la rangée du
  point de dépôt, X amorti — sémantique B9), caret FANTÔME 3 dp au
  point de dépôt (wrap-aware, dessiné aussi dans la fenêtre de la
  loupe — M1 : la loupe montre ce qu'on règle), carte flottante du
  bloc SOUS le doigt (3 lignes max, ellipse, ombre + bord accent — la
  loupe reste au-dessus), auto-scroll de bord UX-2 PARTAGÉ avec le
  drag-select (mêmes zones, mêmes vitesses, les deux axes) et
  suspension du recentrage du caret (`isSelectionDragActive` — le
  B9 anti-secousse s'applique au drag de bloc).
- Démo : item ✥ Move installé sur la pill (retiré/ré-injecté à chaque
  changement d'échantillon, pattern T2).

### Tests

- +17 (1 746 verts) : `MoveRangeTest` ×9 (cel-core : sémantique
  caractère vers le bas/le haut, multi-lignes, dépôt dans la source,
  lecture-seule, bornes hors document, undo UNE étape) ;
  `BlockDragTest` ×8 (Robolectric : armement exige une vraie
  sélection, dépôt au point exact + undo, tap = annulation sans
  toucher au document, CANCEL, dépôt dans la source sans pollution
  d'undo, purge au changement de session, perform de la pill, geste
  englouti — le caret ne suit pas le tap d'annulation).

## [3.66.0] — 2026-10-13 — Lot B12 : zoom persistant par type + colonnes relatives

Troisième lot de la feuille de route issue de l'analyse des écarts n°6 :
P1-3 (le zoom mémorisé par type de fichier, comme Android Studio) et
P1-4 (numéros de ligne relatifs à la ligne du caret). Deux écarts de
confort refermés sans rupture d'API (une classe publique + un enum
public + trois méthodes de vue, rien retiré) — un hôte qui ne pose pas
de clé de zoom ni ne touche au style de numéros ne voit AUCUN
changement de comportement.

### Added

- **B12a — `EditorZoomMemory` + `EditorView.setZoomMemoryKey` (P1-3)** :
  mémoire process des échelles de police, par clé de type de fichier
  posée par l'hôte (extension, id de langage — la granularité lui
  appartient). Chaque mutation d'échelle (pincement, A+/A−,
  `setFontScale`) enregistre l'échelle bornée sous la clé courante ;
  reposer la clé applique le réglage du type — sémantique « un zoom
  par type » : le zoom du type précédent ne fuit pas sur le suivant,
  un type jamais zoomé repart à l'échelle de base. Une clé nulle
  désactive la mémoire (comportement historique exact). La rotation
  reste portée par l'état d'instance B17, PLUS PRÉCIS : la
  restauration s'exécute après le câblage de l'hôte, l'échelle exacte
  de la vue l'emporte sur la mémoire du type.
- **B12b — `LineNumberStyle` (ABSOLUTE/RELATIVE/HYBRID) (P1-4)** : mode
  des numéros de gouttière, posé par `EditorView.setLineNumberStyle`
  (relais public, `null` retombe sur ABSOLUTE). ABSOLUTE (défaut,
  inchangé) : numéros de document ; RELATIVE : distance à la ligne du
  caret (0 au caret — lecture directe pour les déplacements du type
  `5j`/`10k`) ; HYBRID : numéro réel au caret, distances autour (le
  mode VS Code). La surbrillance de la ligne du caret reste posée dans
  les trois modes ; un caret invalide retombe sur ABSOLUTE.
- Démo : `setZoomMemoryKey(id de langage)` à chaque changement
  d'échantillon (pincer Java à 1,8 puis passer au Markdown → zoom
  Markdown, revenir → 1,8 restauré) + menu « Numéros de ligne »
  (absolus / relatifs / hybrides).

### Tests

- +19 (1 729 verts) : `EditorZoomMemoryTest` ×6 (roundtrip, bornage,
  défaut, oubli, purge, null) ; `ZoomMemoryKeyTest` ×8 (enregistrement
  par mutation, fuite inter-types, bascule enregistre avant de
  partir, clé nulle, même clé no-op, vue recréée, rotation >
  mémoire) ; `LineNumberStyleTest` ×5 (rendu shadow canvas des trois
  modes, caret invalide, relais de vue + null).

## [3.65.0] — 2026-10-13 — Lot B11 : fast-scroller de gouttière + bande d'erreurs

Deuxième lot de la feuille de route issue de l'analyse des écarts n°6 :
P0-3 (retour visuel du drag de gouttière) et P1-1 (error stripe façon
Android Studio). Le drag de gouttière devient un positionnement ABSOLU
du document — tout le fichier devient atteignable d'un seul geste —
avec pouce + bulle de ligne en retour visuel ; la bande d'erreurs du
bord droit rend les diagnostics de TOUT le fichier visibles et
navigables au tap, y compris hors écran. Zéro rupture d'API (deux
collaborateurs internes + un setter public, rien retiré).

### Added

- **B11a — `EditorFastScroller` (P0-3)** : le drag dans la zone des
  numéros (et sur la bande d'erreurs) arme un fast-scroller au-delà du
  touch-slop — le pouce (pill 4 dp au bord droit, plancher 32 dp)
  suit la hauteur du doigt et la position du document suit en
  proportion (`top / (viewH − thumbH) × maxV`, géométrie consciente
  wrap + plis). Une bulle « numéro de ligne » à gauche du pouce donne
  la ligne du milieu du viewport (retour visuel des listes Android) ;
  relâchement en fondu 250 ms, PAS de fling (le positionnement est
  absolu) ; annulation immédiate au pincement/CANCEL/appui long/
  changement de session. Un tap reste un tap (le pouce n'apparaît
  jamais sous le slop : les points de diagnostic et l'icône d'aperçu
  de gouttière gardent leur résolution).
- **B11b — `EditorErrorStripe` (P1-1)** : bande de 4 dp au bord
  droit (zone tactile 10 dp) — un marqueur coloré par ligne
  diagnostiquée (3 erreur / 2 avertissement / 1 info, couleurs du
  thème, sévérité max par ligne) posé au ratio `docLineToY /
  contentH` + le rectangle du viewport façon scrollbar. Tap sur un
  marqueur : dépliage du pli englobant, centrage de la ligne
  (`scrollToLineCentered`) et ouverture de la fiche (popup de détail,
  ou liste groupée si la ligne porte plusieurs diagnostics) — parité
  avec le point de gouttière et la chip de fin de ligne. Drag sur la
  bande : défilement absolu (pouce + bulle du fast-scroller). Tap
  sans marqueur : geste consommé (ni caret ni fiche derrière — la
  bande est un chrome). Silence complet quand un aperçu est ouvert
  (SPLIT/FULL/feuille). Alimentée par `EditorDiagnosticsPusher` (LA
  MÊME source que les points de gouttière, compactée en paires
  sparse : dessin O(diagnostics) et non O(lignes)).
- **`EditorView.setErrorStripeEnabled(boolean)` /
  `isErrorStripeEnabled()`** : bascule publique de la bande (défaut
  activé, parité Android Studio out of the box).

### Changed

- Le drag de gouttière passe du scroll pixel-relatif (un écran par
  geste, horizontal inclus) au positionnement ABSOLU vertical
  d'Android Studio : l'activation au-delà du touch-slop supprime AUSSI
  le défilement au jitter d'un simple appui dans les numéros.

### Tests

- 1 710 tests verts (+11) : `FastScrollGutterDragTest` ×5 (mapping
  absolu, slop, tap pur, relâchement sans fling, annulation) et
  `ErrorStripeTest` ×6 (alimentation du pusher, tap marqueur →
  centrage + fiche, tap sans marqueur consommé, drag absolu, setter,
  silence en aperçu).

## [3.64.0] — 2026-10-13 — Lot B10 : recherche/remplacement dans le fichier + 4e tap paragraphe

Premier lot de la feuille de route issue de l'analyse des écarts n°6 :
les deux écarts P0. La recherche/remplacement dans le fichier — le seul
écart que le propriétaire touchera à CHAQUE session sur des fichiers
longs — s'appuie sur le moteur existant (`FindReplace` en cel-core :
insensible à la casse, mot entier, regex) et le surlignage existant
(`setFindHighlights`, teintes `findMatch`/`findCurrent`), jusqu'ici
jamais reliés à une UI. Zéro rupture d'API (une classe publique, un
 contrôleur, une purge interne).

### Added

- **B10a — `EditorSearchBar` + `EditorSearchController`** : la barre
  (registre visuel du fil d'Ariane : pill, accent des bascules
  actives, thème lié) porte trois rangées — requête + compte
  « 3/17 » + précédent/suivant + fermer ; bascules « Aa » casse /
  « ab » mot entier / « .* » regex + mode remplacer ; champ de
  remplacement + « Remplacer » / « Tout ». Le contrôleur tient l'état
  et les actions : recherche vivante (recalcul différé à la frappe ET
  à chaque édition du document, listener ré-accroché au changement de
  session), navigation bouclante (le caret suit le début de
  l'occurrence courante, SANS sélection — pill et poignées
  n'interfèrent pas), remplacement de l'occurrence courante (splice
  ciblé ; regex : expansion des groupes `$1` par matcher borné à la
  région ; remplacement mal formé = repli littéral, jamais de crash)
  et remplacement de TOUT en UNE passe (une seule entrée
  d'annulation, caret restauré). Entrées d'ouverture : menu de l'hôte
  et le raccourci `Ctrl+F` existant (`OnFindRequestedListener` du
  listener primaire de sélection). L'ouverture amorce la requête
  depuis la sélection si elle tient sur une ligne ; la fermeture garde
  la requête (réouverture sans re-frapper).
- **B10b — `selectParagraphAt` + 4e tap** : la cascade de taps gagne
  son quatrième cran — caret → mot → ligne → PARAGRAPHE (bloc de
  lignes contiguës non vides, borné par les lignes vides ou blanches,
  fin sans le saut — parité `selectLineAt`). Le 5e tap reste au
  paragraphe. Deux icônes (loupe en cubiques — le parseur du moteur
  d'icônes rejette les arcs — chevrons, double-flèche) et 14 libellés
  canoniques `cel_find_*` complètent le jeu.

### Fixed

- **Purge du surlignage au changement de session** — les offsets de
  recherche décrivent l'ANCIEN document : `setSession` vide
  désormais `findHighlights`/`findCurrentIndex` (preuve rouge : le
  test de ré-accrochage échoue sans la purge). Sans elle, des bandes
  restaient dessinées aux offsets hasardeux du fichier précédent.

### Tests

- 1 699 tests verts (+35) : `SelectParagraphAtTest` ×8 (bornes
  paragraphe, ligne vide dégénérée, lignes blanches, cascade
  ligne≠paragraphe, clamps), `FourthTapParagraphTest` ×2 (cascade
  complète en taps réels, 5e tap au paragraphe — preuve rouge sans
  le cran), `EditorSearchControllerTest` ×17 (occurrences, options,
  navigation bouclante + caret, remplacements plain/regex/groupes/
  repli, un-seul-undo, édition vivante, session échangée),
  `EditorSearchBarTest` ×9 (cycle, amorçage, frappe vivante,
  réouverture, bascules, rangée remplacer, remplacements, refresh
  session), `EditorIconsTest` inchangé vert sur les 4 nouvelles
  icônes (bounds du viewport, cache partagé).

## [3.63.0] — 2026-10-13 — Retour n°6 : fin de la secousse du drag, caret fixe au pincement, œil d'aperçu dans la gouttière

Sixième retour d'appareil, quatre irritants réglés : pendant un drag de
sélection les lignes « secouaient » (le recentrage du caret se battait avec
l'auto-scroll du drag en dent de scie) ; au pincement zoom/dézoom le caret
ne restait pas fixe (les traînées du doigt restant faisaient défiler le
viewport juste après le zoom, et la lecture seule n'avait aucun ancrage) ;
l'icône d'aperçu manquait à la gouttière (façon Android Studio) ; et une
sixième analyse des écarts Android Studio ↔ code-editor est livrée. Zéro
rupture d'API.

### Fixed

- **B9a — la « secousse » des lignes pendant le drag de sélection** —
  chaque application de sélection du drag déclenchait
  `EditorImeBridge.onSelectionChanged → scrollCaretIntoView()`, qui
  TIRAIT le viewport vers l'ancre du drag pendant que l'auto-scroll
  UX-2 le POUSSAIT dans la direction du doigt : dès que `vOffset`
  dépassait le Y de l'ancre, le recentrage le ramenait en arrière —
  le viewport oscillait en dent de scie `anchorY − lh ↔ anchorY` au
  rythme des ticks, tout le texte tremblait (retour terrain : « les
  lignes ont tendance à secouer », surtout visible ancre près du haut
  de l'écran). Correctif : `EditorView.isSelectionDragActive()` —
  pendant qu'un drag de sélection (poignée ou drag-select au doigt)
  possède le viewport, le recentrage est SUSPENDU ; il reprend au geste
  suivant. Régression prouvée : `DragSelectionShakeTest` détecte la
  dent de scie (non-monotonie de `vOffset`) sans la garde. La loupe est
  en outre STABILISÉE : Y snappé au centre de la rangée réglée (le
  contenu de la bulle ne tremble plus verticalement, wrap-aware) et X
  amorti exponentiellement — le zoom ×2 n'amplifie plus le jitter du
  capteur ; un pincement qui démarre met fin proprement à tout drag en
  cours (tick d'auto-scroll, poignée, loupe).
- **B9b — le caret reste fixe au pincement** — trois causes : (1) au
  relevé d'un doigt, le doigt RESTANT continue d'envoyer des MOVE —
  loin du DOWN d'origine, le slop était déjà franchi — chaque traînée
  résiduelle déclenchait un SCROLL juste après le zoom : le viewport
  glissait et le caret « ne restait pas fixe » alors même que
  l'ancrage avait fonctionné ; les traînées d'un geste qui a pincé sont
  désormais englouties (parité TextView). (2) les sessions en lecture
  seule (et sans caret exploitable) n'avaient AUCUN ancrage — le
  contenu sautait sous le pincement ; l'ancre est désormais le POINT
  FOCAL (le haut de la ligne sous le milieu des deux doigts, colonne
  hors wrap), `applyPinchScale(factor, focusX, focusY)`, l'API
  historique délégue sur le centre du viewport. (3) l'ancrage caret
  (session éditable) est inchangé et reste vérifié par
  `EditorPinchZoomCaretAnchorTest`. Régression prouvée : le test des
  traînées échoue sans l'avalement.

### Added

- **B9c — l'icône d'aperçu vit dans la gouttière, comme Android
  Studio** — les icônes de gouttière d'Android Studio (run ▶, Compose
  `@Preview`) vivent dans la gouttière, ancrées à la ligne qu'elles
  concernent : l'œil d'aperçu suit la même grammaire. Pill + œil
  (`EditorIcons.EYE`) posés sur la RANGÉE DU CARET, visibles dès que le
  fichier est prévisualisable, état ACTIF allumé à l'accent quand la
  feuille est ouverte (parité avec l'œil du fil d'Ariane B6) ; l'icône
  remplace le numéro de la rangée annotée. Rectangle DÉTERMINISTE
  (`GutterView.previewActionRect`, 26 dp bornés par la ligne) partagé
  par le dessin et le hit-test ; le tap BASCULE la feuille sans
  déplacer le caret (`EditorView.hitTestGutterPreviewAction`, garde
  rangée à l'écran — aucune zone tactile fantôme).
- **B9d — analyse des écarts Android Studio ↔ code-editor (n°6)**
  (`docs/ANALYSE-GAPS-ANDROID-STUDIO.md`) — angle : l'édition tactile.
  Sol crédité (20+ fonctionnalités vérifiées dans le code), écarts
  priorisés P0/P1/P2 : recherche/remplacement dans le fichier (le
  moteur `FindReplace` existe en cel-core, aucune entrée UI), 4e tap
  paragraphe, fast-scroller de gouttière, bande d'erreurs à droite,
  drag-déposer de bloc, persistance du zoom, plis par niveau, API
  `GutterAction` généralisée (fondée sur B9c). Feuille de route
  B10→B14.

### Tests

- 1 664 tests verts (+12) : `DragSelectionShakeTest` ×2 (monotonie de
  `vOffset` pendant le drag — la dent de scie sans garde ; recentrage
  rouvert au repos), `PinchResidualAndFocusAnchorTest` ×4 (traînées
  post-pincement n'écrivent pas les offsets ; pincement qui démarre tue
  le drag-select ; lecture seule — ligne sous le point focal fixe ; API
  historique centrée), `GutterPreviewActionTest` ×6 (rect déterministe,
  hit dans la pill uniquement, aucune zone fantôme, bascule
  SHEET_SPLIT↔NONE sans déplacer le caret, draw sans crash) ;
  `EditorMagnifierHandleDragTest` passé au contrat stabilisé (Y constant
  dans la rangée, X amorti, activation brute).

## [3.62.0] — 2026-10-13 — Retour n°5 : wrap multi-fichiers, aperçu plein ajusté, popups rejetés au toucher extérieur

Cinquième retour d'appareil, trois irritants réglés à la racine : avec le
retour à la ligne actif, ouvrir un autre fichier empilait les lignes du
bas (le modèle de wrap de l'ANCIEN document survit au changement de
session, et son clamp de queue renvoie toutes les lignes au-delà de
l'ancien compte au MÊME Y) ; la preview en plein restait zoomée sans
pouvoir se corriger au pincement ; aucun popup ne se refermait d'un tap
hors de son cadre. Correctifs et additif, zéro rupture d'API.

### Fixed

- **B8a — le modèle de wrap suit le document après `setSession()`** —
  `EditorView.setSession()` purgeait le cache de rendu et le scroll mais
  JAMAIS le modèle de wrap : avec le retour à la ligne actif, le modèle
  de l'ancien fichier rendait le nouveau (ses `topRow()` au-delà de
  l'ancien compte de lignes clampe sur le total → toutes les lignes de
  queue au même Y, « empilées » en bas, d'autant plus visibles que le
  fichier est long ; même compte de lignes ≠ sauvetage, les rangées sont
  comptées sur les longueurs de l'ancien document). Correctif :
  reconstruction à chaque changement de session quand le wrap est actif,
  purge du modèle sans session (parité `setWordWrap(false)`).
  Régression prouvée : `WrapSessionSwapTest` échoue 4/4 sans le fix.
- **B8b — l'aperçu plein se ré-ajuste et se zoome au doigt (démo)** —
  le pincement ne faisait RIEN (contrôles de zoom intégrés désactivés
  par défaut), et la bascule scindé → plein gardait l'échelle et la
  mise en page de l'ANCIENNE largeur (le re-rendre ne repassait jamais
  par overview mode). Correctifs dans `DemoPreviewHost` : contrôles de
  zoom intégrés activés SANS les boutons +/− ; rechargement du payload
  à chaque changement de taille (`onPreviewModeChanged`, la position de
  lecture est préservée) ; WebView créée du contexte RÉEL de l'éditeur
  (le contexte applicatif peut porter des DisplayMetrics étrangères à
  l'affichage courant → échelle initiale fausse).
- **B8c — un tap hors du cadre referme le popup** — audit complet de la
  fermeture extérieure. Fenêtres : la feuille d'aperçu et les cartes
  glass (aller-à-la-ligne, renommage) portaient un verrou « ✕
  uniquement » ; elles reçoivent la recette Android complète
  (`EditorPopupMotion.outsideTapDismisses` : `setOutsideTouchable` +
  fond non nul → `ACTION_OUTSIDE` → dismiss automatique, le tap
  traverse vers l'éditeur qui place son caret, la saisie clavier reste
  captée). Canvas : le popup d'actions de code et l'aller-au-symbole se
  referment sur un tap hors bornes (la cascade continue derrière, même
  motif que le popup ancré de squiggle) ; l'aide de signature —
  bannière passive sans rangée interactive — se referme sur tout tap.

### Tests

- 1 652 tests verts (+12) : `WrapSessionSwapTest` ×4 (modèle reconstruit
  au changement de session, lignes de queue dés-empilées, rangées
  recomptées à compte égal, purge sans session), `PopupOutsideTapDismissTest`
  ×5 (signature, actions de code, aller-au-symbole, recette complète
  feuille + carte), `DemoPreviewHostTest` +3 (pincement actif sans
  boutons, rechargement au changement de taille, contenu retenu avant
  l'ouverture).
