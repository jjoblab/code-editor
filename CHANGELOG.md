# Changelog

All notable changes to **code-editor-lib** are documented in this file.

Le format suit la convention [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Les sections antérieures à la période courante sont archivées dans
[`docs/archive/CHANGELOG-archive.md`](docs/archive/CHANGELOG-archive.md) (A-08, audit 3.69.0).

## [Unreleased]

## [3.73.0] — 2026-10-16 — Refonte de la démo : coquille IDE + mini serveur LSP Java (les cinq modules exercés) + passe UX (bottom sheet, tiroir filtrable, outils IDE)

### Added (maquette validée : carte d'aperçu « goutte d'eau » B27)

- **Carte d'aperçu « goutte d'eau » (B27)** — l'alternative mobile au split view, implémentée à l'identique de la maquette interactive validée (archivée dans `docs/maquettes/preview-drop-gouttiere.html` + capture) : l'aperçu vit dans un popup **non modal** ancré à l'**œil de gouttière** — la pill B9c qui suit le caret devient l'ancre, et **se GÈLE** sur sa rangée tant que la carte vit (une **tige fine** relie l'œil au losange-goutte du coin bas-gauche quand la carte s'esquive loin). Le **placement anti-recouvrement en 3 niveaux** est re-résolu à CHAQUE frame en coordonnées écran : (1) ancré pleine taille si le caret est loin ; (2) esquive ENTIÈRE au-dessus/dessous du bloc de frappe (caret ± 1 ligne) du côté le plus dégagé ; (3) compactage par échelle bornée (plancher 0,58, chip « fit · NN % » honnête) — la carte ne recouvre JAMAIS la ligne où l'utilisateur tape (prouvé par balayage de 18 positions, miroir JUnit de la QA de la maquette). La carte dessine son chrome complet sur le canvas (en-tête « APERÇU » + **onglets du hôte** en pilules + ✕, mini-téléphone bezel/écran clair/barre d'état à l'heure réelle + badge d'exécution ellipsé au milieu, pied à chips « rendu vivant » + échelle), avec fondu d'entrée/sortie 120 ms via `EditorPopupFade.Kind.PREVIEW_DROP`. Le rendu du mini-téléphone appartient au **hôte** : cinq nouvelles méthodes à défaut raisonnable sur `EditorPreviewHost` (`prefersDropPreview` — vrai = le tap d'aperçu ouvre la carte au lieu de la feuille, `previewDropTabs`, `onPreviewDropTabSelected`, `drawDropPreview` — canvas déjà translaté/échelonné/clippé, `previewDropBadge`) ; un hôte feuille WebView n'implémente rien de nouveau. Gestes câblés (zéro code hôte) : tap œil → ouvre (ancre = rangée du caret) / re-tap sur l'œil gelé → referme ; onglet → bascule + notification ; ✕ → ferme ; corps → englouti (caret immobile, appui long interdit derrière — `popupOwnsTouch`) ; tap ailleurs → cascade normale, la carte reste vivante et esquive. API publique : `openPreviewDropAt(ligne)`, `closePreviewDrop()`, `togglePreviewDrop()` (l'état ouvert se lit sur `getPreviewMode() == PreviewMode.DROP`). Fermetures de sûreté : changement de session, fichier non prévisualisable (fermeture COMPLÈTE — mode retombé à NONE, hôte notifié), détachement. Architecture en trois classes : `EditorPreviewDrop` (état vivant : ancre, onglets mesurés une fois et partagés dessin/hit-test), `EditorPreviewDropGeometry` (algorithme pur SANS Android — testable JUnit), `EditorPreviewDropPainter` (chrome). Couvert par `EditorPreviewDropGeometryTest` (10 : trois niveaux, clamps, tige, goutte bornée, balayage 18 positions, éditeur plus petit que la carte) + `PreviewDropCardTest` (12 : ouverture par l'œil et par openPreview routé, ancre gelée + hit-test sur l'œil dessiné, re-tap fermant, onglet basculé + hôte notifié, ✕, corps englouti sans déplacer le caret, tap hors carte = caret posé + invariant d'esquive, purges session/fichier, re-open propre, rendu smoke) + 3 tests du hôte de la démo (`DemoPreviewHostTest` : xml prévisualisable, préférence carte seulement pour les layouts, onglets/badges).
- **La démo exerce la carte** — l'échantillon « XML » porte désormais le nom de fichier `activity_main.xml` : l'œil de gouttière ouvre la carte avec ses onglets **XML / Compose** et son badge « activity_main.xml · gonflé » / « MainActivity.kt · @Composable » (l'onglet Compose simule l'écran en habillage M3 — la démo n'a pas de compilateur de composables, l'habillage honnête remplace le rendu réel) ; Markdown/HTML gardent la feuille WebView.

### Fixed (IME : R1 + R2, les deux écarts P1 du plan 09)

- **Purge de l'état IME au changement de session (R1, écart E1)** — le miroir du clavier décrivait l'ANCIEN document après une bascule d'onglet (texte extrait, sélection, composition en cours) : l'IME pouvait rejouer son miroir périmé ou réveiller une région fantôme. `EditorView.setSession` purge désormais via `EditorImeBridge.purgeForSessionChange()` : la GÉNÉRATION s'incrémente (toute InputConnection vivante devient muette — ses rapports asynchrones tombent à l'eau, miroir de `closeConnection`), le JETON D'EXTRACTION est invalidé (plus de `updateExtractedText` vers un moniteur mort), le MODE ANCRE est raz, puis `restartInput` redemande la connexion — le clavier reconstruit son miroir sur le NOUVEAU document. Garde : génération 0 (aucune connexion n'a jamais servi) → aucun restart fantôme vers un clavier jamais attaché. Couvert par `SetSessionImeRestartTest` (3 : purge complète + restart compté par shadow enveloppant ; pas d'appel fantôme sans connexion ; la vieille connexion qui se referme après la bascule ne ressuscite pas le jeton).
- **Sélection IME bornée au plafond Binder (R2, écart E2)** — `getSelectedText` renvoyait la sélection ENTIÈRE : la réponse traverse le binder (transaction plafonnée, ~1 Mo partagé) et une sélection de tout un gros fichier demandée par un clavier curieux (recherche, dictionnaire, réécriture) levait une `TransactionTooLargeException`. Nouvelle voie `EditorSession.imeSelectedText()` → `ImeBridge.selectedTextForIme()` : borne symétrique à `imeTextBeforeCursor` (MAX_IPC_TEXT = 4 096 caractères, les premiers de la sélection — le début est ce que l'IME voit autour du curseur) + garde document (une sélection dépassant la fin ne lève pas). La sélection INTERNE (`selectedText` — copier/couper) reste intacte. Couvert par `ImeSelectedTextBoundTest` (6 : 500 000 caractères bornés à 4 096, borne relative au début, sélection courte intégrale, caret vide, exactement au plafond, hors document sans exception).

### Added (IME : R3–R5, les services de plateforme « bibliothèque publiée » du plan 09)

- **Capitales réelles au point d'insertion (R3, écart E3)** — `initialCapsMode` valait toujours 0 et `getCursorCapsMode` n'était pas surchargée : l'IME ne savait pas activer la majuscule en début de phrase. Désormais `TextUtils.getCapsMode` s'applique sur une FENÊTRE bornée (1 000 caractères avant le caret — le scan ne regarde que vers l'arrière, le document entier n'est jamais matérialisé) ; `initialCapsMode` est alimenté en WORDS+SENTENCES uniquement (bits positionnels : CAP_MODE_CHARACTERS est un simple écho de la requête dans getCapsMode, l'inclure ferait croire à l'IME qu'un mode tout-en-majuscules est actif). Couvert par `CursorCapsModeTest` (7 : majuscule annoncée après « . », neutre au milieu d'un mot, début de texte, getCursorCapsMode SENTENCES/0, fenêtre bornée sur document de 175 000 caractères).
- **Instantané atomique `takeSnapshot` (R4, écart E4, API 33)** — le système sauvegarde l'état IME quand l'application est tuée en arrière-plan : la composition en cours survit désormais à une mort de processus. Nouvelle sous-classe `Api33InputConnection` (instanciée sur API 33+, héritée par `Api34InputConnection`) : le snapshot porte le texte environnant (2 048 caractères de chaque côté du caret, tranche rope sans copie complète), la sélection ABSOLUE (via l'offset du SurroundingText), la région de composition en offsets ABSOLUS (miroir des candidats d'updateSelection, −1/−1 sans composition) et les capitales au caret. Couvert par `ImeSnapshotTest` (3 : composition/sélection/capitales cohérentes, sans composition −1/−1, fenêtre bornée + offset exact sur document de 36 000 caractères).
- **Protocole d'écriture manuscrite au stylet (R5, écart E5, API 34)** — sans retour positif à `setImeConsumesInput`, la saisie au stylet sur la zone d'édition restait un simple geste. La sous-classe API 34 accepte désormais la requête et suit le contrat de la plateforme : le caret est CACHÉ pendant que l'IME consomme l'entrée, il revit au retour — à sa visibilité d'avant-consommation — et l'état IME est ré-invalidé par `InputMethodManager.invalidateInput` (le protocole léger API 33 appairé à `takeSnapshot` : le système re-capture l'état d'édition à la demande, sans restartInput complet). Couvert par `StylusImeConsumesInputTest` (4 : acceptation + caret caché, retour = caret revit + invalidation comptée par shadow, visibilité d'avant-consommation restaurée, idempotence sans invalidation en boucle).

### Fixed (IME : R6 + R7 + R8, confort et interopérabilité du plan 09)

- **Fenêtre d'extraction honnête (R6, écart E6)** — `buildExtractedText` fenêtrait toujours à 100 000 caractères alors que certains IME demandent exprès de petites fenêtres (`hintMaxChars` de la requête) et en payaient le prix à chaque mise à jour du moniteur. La fenêtre demandée est honorée (bornée au plafond maison), MÉMORISÉE — les `updateExtractedText` suivants gardent la taille demandée, le miroir de l'IME reste à sa géométrie — et purgée au changement de session (R1 : le moniteur meurt, la fenêtre demandée meurt avec lui). Couvert par `ExtractedTextHintMaxCharsTest` (5 : fenêtre 100 car. centrée sur le caret, sans hint = plafond maison, hint délirant borné, moniteur à taille constante après édition, purge).
- **Écart minimal de `setComposingText` (R7)** — la frappe CJK renvoie le mot de composition COMPLET à chaque caractère (« こ » → « こん » → …) : l'éditeur réécrivait toute la région à chaque frappe. Désormais le préfixe et le suffixe COMMUNS de l'ancien et du nouveau texte sont préservés, seule la différence médiane est épissée — le coût devient proportionnel à la VARIATION et non à la taille de la région, les styles du préfixe commun survivent au re-splice, une mise à jour REDONDANTE (l'IME renvoie le même texte) n'épisse plus rien ni ne crée d'étape d'annulation fantôme, et l'annulation d'un mot composé se fait en UNE passe (coalescence des écarts adjacents, exactement comme les frappes tapées). Garde de frontière : les paires de substitution ne sont JAMAIS coupées (recul d'un caractère le cas échéant). Garde-fou de performance : `ComposingSpliceBenchmark` (25 000 lignes ≈ 1 M caractères, mot CJK et phrase longue de 30 caractères, < 2 ms/op). Couvert par `ComposingMinimalDiffTest` (10 : croissance CJK = document/région/caret identiques à l'épissure intégrale, backspace de composition, remplacement du milieu, retrait + ré-ajout de suffixe, redondance sans étape d'undo, emoji adjacents sans paire coupée, suffixe commun emoji, composition vide, première composition remplaçant la sélection, annulation en une passe).
- **Politique du clavier logiciel unifiée (R8, écarts E7 + E8)** — deux frictions en une passe : (E7) aucun clavier matériel n'était détecté, le clavier logiciel surgissait au tap même avec un clavier physique branché ; (E8) `onCheckIsTextEditor` répondait vrai même en lecture seule — le tap montait un clavier dont les frappes étaient silencieusement ignorées. Toute la remontée passe désormais par une politique unique (`EditorImeBridge.showSoftInputIfEditable` — tap, appui long, bouton public `showSoftKeyboard()`, refocus) : en lecture seule la vue n'est plus un champ d'édition pour le système (`onCheckIsTextEditor` faux, le caret reste posé au tap — la lecture reste navigable) ; avec un clavier MATÉRIEL exposé (`Configuration.keyboard` + `hardKeyboardHidden`) le tap ne fait pas surgir le clavier logiciel, et l'exposition d'un clavier matériel en cours de frappe MASQUE le logiciel (`onConfigurationChanged`). Couvert par `HardwareKeyboardSoftInputTest` (7 : tap avec/sans clavier matériel, bouton public, masquage à l'exposition, onCheckIsTextEditor éditable/lecture seule, tap sans clavier muet, caret posé en lecture seule).

### Added (IME : R9, les deux hooks hôte)

- **Personnalisation de l'`EditorInfo` (R9, écart E9)** — `setOnEditorInfoListener` : l'écouteur est appelé à CHAQUE construction de connexion, APRÈS les valeurs par défaut de la bibliothèque (inputType verrou code, imeOptions, sélection initiale, capitales réelles) — l'hôte a le dernier mot (ajouter `IME_FLAG_NO_PERSONALIZED_LEARNING`, une action d'entrée `imeActionLabel/actionId`, un inputType dédié…) sans toucher la bibliothèque.
- **Commandes privées routées (R9, écart E10)** — `setOnImePrivateCommandListener` : `performPrivateCommand(action, data)` des IME propriétaires (extensions de claviers tiers) est routé vers l'écouteur (retour vrai si traité, faux sinon — la base ignorait tout). La démo exerce les deux hooks : `IME_FLAG_NO_PERSONALIZED_LEARNING` (constante compat androidx) posé au hook EditorInfo, commandes privées journalisées dans le panneau. Couvert par `EditorInfoHookTest` (4 : hook avec dernier mot, valeurs par défaut intactes sans hook, routage accepté/refusé, sans écouteur = faux) + `EditorInfoHookDemoTest` (1).

### Changed (documentation : maquette + connexion de l'application hôte)

- **README : section « Connecter l'application hôte — aperçu goutte d'eau »** — les trois familles d'aperçu (feuille WebView, panneau scindé, carte gouttière), le hôte minimal complet en exemple, et la **table de correspondance maquette → implémentation** (œil gelé, tige, placement 3 niveaux, onglets, badge, chips, esquive de frappe) — la maquette est archivée dans le dépôt (`docs/maquettes/`, HTML interactif + capture).
- **USAGE.md : section « Aperçu — feuille, panneau, carte goutte d'eau »** — remplace « Aperçu XML » : tableau des trois familles, guide de connexion du hôte carte (contrat des 5 méthodes avec défauts), contrat des gestes, API programme, et les points de conception notables de la maquette (gel de l'œil, re-résolution par frame, compactage par échelle, rectangles partagés dessin/hit-test).
- **ARCHITECTURE.md** : contrat `EditorPreviewHost` porté à 11 méthodes (6 historiques + 5 B27), flux type mis à jour (routage `prefersDropPreview`, frame DROP), décomposition des trois classes de la carte ; **plan 09** : R1/R2 marqués LIVRÉS avec leurs tests.

### Added (retour terrain : sélecteur de couleur au tap)

- **Sélecteur de couleur ancré (B26)** — un tap au cœur d'un code couleur hexadécimal (`#RGB`, `#RRGGBB`, `#AARRGGBB` — les pastilles inline du renderer marquent la zone tappable) ouvre une carte de verre non modale ancrée à la ligne du code : en-tête « origine → courant » (swatches + libellés hex, vivant pendant le drag), **champ saturation/valeur** (dégradés blanc→teinte et transparent→noir, markers à double cercle), **bande de teinte** (spectre 0–360°) et **palette de 16 teintes courantes**. Le pick **remplace le code tapé** en conservant son FORMAT d'origine (3/6/8 chiffres — la largeur du code ne change pas, le code environnant ne bouge pas) et son **alpha d'origine** (le sélecteur édite la teinte, pas la transparence) ; la mutation passe par le pipeline unique (`replaceRangeWithCaret` — annulable d'un undo) puis `onTextChanged` (contrat des actions d'édition). Contrat des gestes : press = saut immédiat dans le champ/la bande, drag = mise à jour vivante, release = application ; tap sur une teinte de la palette = application directe ; tap hors carte / édition / perte de focus = fermeture sans application ; les bords ±1 caractère du code restent des placements de caret (précédent squiggle D1). Désactivable : `setColorPickerEnabled(false)` (le tap retrouve le comportement historique). Nouvelle classe logique `EditorColorPickerPopup` (état sur `EditorView`, fermeture croisée), géométrie `EditorPopupAnchors.colorPickerMetrics` (carte ≈ 242 × 264 dp, ancre au début du code, flip dessus/dessous), painter dédié `EditorColorPickerPainter` (shaders de dégradé mis en cache, zéro allocation par frame), geste `colorPickerGesture` dans `EditorInputHandler`, fondu d'apparition/disparition via `EditorPopupFade.Kind.COLOR_PICKER`. Couvert par `ColorPickerTapTest` (11 : ouverture/plages/format, bords → caret, fermeture hors carte + caret, fermeture à l'édition, désactivation, pick palette au format 3 chiffres + undo, alpha conservé sur 8 chiffres, press/drag/release du champ SV avec saut immédiat et application, tap inerte dans l'en-tête, formatage pur 3/6/8).
- **`ColorPicker.format(argb, digits)` + `ColorMatch.digits()`** — formatage d'une couleur ARGB au format hex demandé (3 chiffres = quantification au nibble le plus proche, 6 = `#rrggbb`, 8 = `#aarrggbb`) : le remplacement d'un code couleur ne change jamais la largeur du texte d'origine.

### Fixed (retour terrain : débordement de la pill trop large + crash latent)

- **Le menu « ⋯ » suit la largeur de son contenu (B25)** — retour terrain : le débordement du toolbar de sélection occupait `min(320dp, max(240dp, 90 % de la vue))` — 320dp sur un écran de 360dp, la carte engloutissait presque tout l'éditeur pour lister huit actions courtes. La largeur suit désormais le CONTENU : le libellé le plus large (mesuré via la fabrique partagée `EditorNavMenuRows.labelOf` — source unique entre le painter qui dessine et la géométrie qui mesure) + le budget icône/gaps/paddings, borné `[168dp, 320dp]` et clampé à la vue moins les marges ; les libellés trop longs restent ellipsés au plafond. `NAV_MENU_MIN_WIDTH_DP` 240 → 168. Couvert par `NavMenuOverflowWidthTest` (3 : huit actions courtes → carte ≤ 240dp ; vue plus étroite que le plancher → clamp aux bords ; « Nothing found » garde le plancher). `:cel-ui` 708 → 722, total 2 425 → 2 439 (aussi `docs/plans/09-gestion-ime.md` — plan d'analyse et de correctifs de la couche IME, feuille de route R1–R9 priorisée, rédigé en termes autosuffisants).
- **`ColorPicker.findColorAt` : crash en fin de ligne (latent)** — le balayage arrière du « # » appelait `charAt(pos)` sans borne : `pos == length()` (tap juste après la fin d'une ligne) levait `IndexOutOfBoundsException` dans TOUTE la cascade de tap. Le rendu des pastilles ne le déclenchait jamais (il scanne ligne par ligne) ; le nouveau chemin de tap l'a exposé — le balayage est désormais borné aux indices valides (et la chaîne vide retourne null). Couvert par les 9 tests existants de tap qui échouaient avant le correctif (appui en fin de ligne).

### Added (passe UX : l'atelier devient un vrai IDE)

- **Panneaux outils en bottom sheet Material** — Diagnostics / Symboles / Journal LSP quittent leur bloc statique en bas d'écran : la feuille s'ancre au-dessus de l'éditeur dans un `CoordinatorLayout` (poignée de glissement, coins supérieurs arrondis, élévation, en-tête cliquable, état replié = en-tête seul, jamais masquable — un panneau d'IDE ne disparaît pas par accident). Changer de panneau déplie la feuille.
- **Tiroir « Fichiers » refondu** (`SampleDrawer`) : champ de filtrage instantané insensible à la casse et aux accents (pliage NFD — « minifie » trouve « Minifié (JS) »), sections par famille dotées d'une teinte (JVM/Web/Scripts/Systèmes/Données, filet « Autres »), badges par échantillon (« TM » : grammaire TextMate embarquée — la nouvelle sonde ; « LSP » : mini serveur), point cyan sur les fichiers déjà ouverts en onglets, en-tête typographié, effacement du filtre en un geste. Le `NavigationView` générique et ses ressources (`menu_drawer.xml`, `drawer_header.xml`) sont retirés.
- **Outils IDE dans la barre d'atelier** : **Annuler / Rétablir** (icônes permanentes, grisées selon les piles — `session.getUndoManager().canUndo()/canRedo()`), **Rechercher et remplacer** (dialogue : tout remplacer en ordre inverse — les offsets restent valides — ou occurrence suivante avec rebouclage et compte de restantes), **Aller à la ligne** (validation 1–N, saut au début de ligne), **taille de police** (A+ / A− / 100 % — le contrôleur de zoom de l'éditeur), **Lecture seule** (verrou de frappe de la session, pastille ambre dans la barre d'état), **Partager le fichier** (intent système), **Statistiques du document** (lignes, non vides, mots, caractères, sélection).
- **`TextMateTokenizerImpl.hasEmbeddedGrammar(language)`** (nouvelle sonde publique de `:cel-textmate`) : une grammaire embarquée (dépôt, tierce JSON ou tierce plist) existe-t-elle pour ce langage SANS l'activer ni la charger — les trois formats de ressource sont sondés sans toucher au registre. C'est la réponse au bug ci-dessous : un hôte qui expose une bascule « voie grammaires » a besoin de la CAPACITÉ, pas de l'état.
- **Transition accueil → atelier** : cadence « fade through » Material (l'accueil s'efface en reculant, l'atelier monte doucement), miroir exact rejoué au retour par `finish()` surchargé — quelle que soit la voie de sortie (système, dernier onglet fermé). Réglages : fondu croisé discret aux deux sens.
- **Tests de la démo : 51 → 61** (+ cel-textmate 79 → 81) — `AtelierToolsTest` (7 : bascule TextMate visible dans les DEUX sens, bottom sheet repliable, annuler/rétablir, remplacer tout, occurrence suivante avec rebouclage, lecture seule bloque la frappe, point d'arrêt du « ⋯ » qui bascule), `SampleDrawerTest` (3 : filtre sans casse ni accents, 5 familles + 30 échantillons filtrés en direct, remontée des sélections).

### Added (retour terrain : le débordement du toolbar de sélection)

- **Icônes propres dans le menu « ⋯ » (B16)** — la section « MORE » du menu contextuel unifié affichait l'ampoule générique pour TOUTES les actions débordantes (Partager, Déplacer, Dupliquer… toutes identiques — retour terrain). Le moteur `EditorIcons` gagne 7 icônes vectorielles (`DUPLICATE` deux feuilles superposées, `COMMENT` double barre oblique, `CASE` « Aa », `SHARE` trois nœuds reliés, `MOVE_UP`/`MOVE_DOWN` flèche + barre de destination, `MOVE_BLOCK` quatre chevrons divergents) et `SelectionAction` gagne le champ public `icon` (constructeur complet rétro-compatible — sans icône, l'ampoule reste le fallback documenté). Les 7 actions intégrées de `BuiltinSelectionActions` portent chacune la leur, dessinées par `EditorChromePainter` via le moteur (teinte du thème, nets à toute densité). Couvert par `SelectionActionIconsTest` (4 : icônes distinctes et non nulles, paths parsés et bornés au viewport 24×24, transport de l'icône jusqu'à la rangée de débordement, rétro-compatibilité des anciens constructeurs).
- **« Point d'arrêt » dans le « ⋯ » de la démo** — la 8e action injectée du toolbar de sélection pose/retire le point d'arrêt de gouttière (B15) sur la ligne du DÉBUT de la sélection, avec l'icône disque plein `BREAKPOINT` : le point d'arrêt devient atteignable AU DOIGT depuis la sélection, sans viser le menu de la barre. Couvert par `AtelierToolsTest.leToolbarDeSélectionPorteUneActionPointDArrêtQuiBascule`.

### Changed (retour terrain : poids des icônes)

- **Icônes allégées (B24)** — retour terrain : les icônes (débordement « ⋯ » de la pill, gouttière, fil d'Ariane, feuille d'aperçu) paraissaient TROP GRASSES. Le moteur `EditorIcons` passe sa famille STROKE d'un trait 2/24 (Material Weight 400) à **1,5/24** (poids léger ≈ Material Weight 300, parité codicon VS Code — plancher 1 px en basse densité conservé) ; la géométrie suit sur les deux formes les plus lourdes : les nœuds du `SHARE` s'élargissent (r 2,3 → 2,55) pour évider les donuts (le trou passe de ~43 % à ~65 % du diamètre, traits reliants recalculés sur le pourtour exact), et le disque `BREAKPOINT` passe de r 8 à **r 6,75** (54 % du viewport au lieu de 67 %) — lisible en gouttière sans dominer la rangée du « ⋯ ». Couvert par `EditorIconsTest` (2 nouveaux : le rapport trait/taille verrouillé à 1,5/24 à 24 et 48 px, géométrie du partage évidé et du disque affiné) + `GutterActionsTest` (bornes du BREAKPOINT recalées) — `:cel-ui` 706 → 708, total 2 423 → 2 425.

### Fixed (retour terrain : pill de sélection)

- **L'appui long n'affiche plus le survol (B17)** — le popup quick doc s'ouvrait en MÊME TEMPS que la pill à chaque appui long, doublonnant l'icône Docs ℹ du toolbar de sélection (et F1) : le survol devient un affichage VOLONTAIRE (icône Docs ℹ, F1, appui long sur un inlay hint — inchangés). L'appui long retrouve son contrat unique : sélection du mot + poignées + pill + drag-select armé ; l'opt-in `setTouchHoverEnabled(true)` (tap-and-hold 500 ms → survol seul) est conservé. Couvert par `LongPressNoQuickDocTest` (2 : appui long = sélection et pill SANS quick doc — le resolveur n'est même pas consulté ; contrôle positif — l'icône Docs ℹ ouvre bien le survol).

### Added

- **Refonte totale de l'application de démo : une coquille IDE professionnelle** — l'ancienne mono-activité (sélecteur de langage en dialogue + bande de bascules) devient un atelier complet :
  - **Écran d'accueil** (`HomeActivity`) : cartes de capacité — chaque carte ouvre l'atelier PRÉCONFIGURÉ (Diagnostics LSP Java, Atelier multi-langages, Grammaires TextMate, Aperçu XML/HTML, Stress bundle minifié, Cascade de thèmes), chiffres de tête (29 langages · 10 grammaires · 5 modules), entrée Réglages.
  - **Coquille IDE** (`EditorActivity`) : **tiroir** des 30 sélecteurs groupés par familles (JVM, Web, Scripts, Systèmes, Données), **onglets multi-fichiers** (jusqu'à 10 — session + analyseur par fichier, fermeture par « × », réutilisation à la réouverture), **panneaux outils repliables** (Diagnostics — clic = saut à la ligne, Symboles — outline du document, **Journal LSP** — activité JSON-RPC horodatée), **barre d'état** (Ln/Col du caret, langage, ● moteur maison / ● moteur LSP, voie TextMate, UTF-8). La barre de fil d'Ariane, la recherche/remplacement, les bascules grammaires et toutes les options de vue de l'ancienne démo sont conservées.
  - **Écran Réglages** (`SettingsActivity`) : les bascules de l'ancienne bande du bas deviennent des préférences persistées (`SharedPreferences`) relues par l'atelier à chaque reprise — apparence (thème clair, scheme), éditeur (retour à la ligne, minimap, ligatures, non-imprimables), moteurs (grammaires TextMate automatiques, LSP automatique sur Java).
- **Mini serveur de langage Java — la démo vit désormais le module `:cel-lsp` de bout en bout** : un VRAI serveur LSP (protocole JSON-RPC via LSP4J, poignée de main `initialize`, notifications `didOpen`/`didChange`/`didClose`, publication `textDocument/publishDiagnostics`) implémenté en ~200 lignes, tournant **in-process** derrière `InProcessStreamConnectionProvider` — le trafic traverse le cadre LSP de la bibliothèque exactement comme avec un serveur externe, seul le transport change. Quatre classes dans le paquet `demo/lsp/` :
  - `MiniJavaParser` — parseur conservateur pur JVM : accolades/parenthèses non fermées (signalées à leur OUVERTURE), fermantes orphelines, points-virgules manquants (instruction terminée + ligne suivante qui démarre une déclaration), branchements orphelins (`else`/`catch`/`finally` sans tête ADJACENTE — sémantique stricte : le branchement suit la fermante du bloc ou un corps sans accolades). Contrat explicite : **zéro faux positif** — les tests couvrent les déclencheurs ET les non-déclencheurs (modificateurs seuls, chaînes en continuation, annotations, accolades dans les littéraux, commentaires).
  - `MiniJavaLanguageServer` — le serveur lsp4j : capabilities `textDocumentSync = Full`, diagnostics uniquement (aucune autre capacité annoncée). Pur JVM, testable sans Android.
  - `MiniJavaServerDefinition` — l'unique point de branchement sur `:cel-lsp` : une `LanguageServerDefinition` dont `createConnectionProvider` fabrique le transport in-process, exactement comme une intégration réelle (sous-processus, socket TCP, LocalSocket).
  - `DemoLspController` — possède le `LspProject`, matérialise l'espace de travail (`filesDir/workspace/Atelier.java`), connecte/déconnecte l'éditeur, expose l'état (OFF/CONNECTING/CONNECTED) et relaie journal + diagnostics. L'intégration LSP de RÉFÉRENCE pour les hôtes.
  - **La bascule « Moteur LSP »** (menu, onglet Java) démontre les DEUX voies de `USAGE.md` sur le même document : moteur maison (popups synthétiques) contre serveur LSP réel — les diagnostics poussés à chaque édition sont soulignés dans l'éditeur, listés au panneau et le journal montre le trafic. La démo dépend donc désormais de `:cel-lsp` (+ LSP4J en direct : un hôte qui implémente un serveur programme contre LSP4J) : **les cinq modules publiables sont exercés**.
- **Tests de la démo : 31 → 51** — `MiniJavaParserTest` (20 : règles + non-déclencheurs), `MiniJavaLanguageServerTest` (5 : initialize, publications didOpen/didChange/didClose, code propre/cassé), `DemoLspControllerTest` (2 : la chaîne COMPLÈTE contrôleur → projet → transport in-process → serveur → diagnostics en session, déconnexion/purge), `EditorActivitySmokeTest` (3 : lancement, coquille multi-fichiers, extra LSP de la carte d'accueil), `HomeActivitySmokeTest` (1), `DemoPreviewHostTest` (20, inchangé).

### Changed

- **`DemoAnalyzer` : détachement propre pour la coquille multi-onglets** — `attach()`/`detach()` sont désormais idempotents et `detach()` RETIRE l'écouteur d'édition de la session (l'ancienne démo recréait un analyseur à chaque changement de langage ; la nouvelle attache/détache le MÊME analyseur à chaque sélection d'onglet — sans retrait, les écouteurs s'accumulaient). Nouvel accès `outline(text)` (même source que le popup « aller au symbole ») pour le panneau Symboles.

### Fixed (atelier)

- **La bascule « Grammaires TextMate » disparaissait après usage — retour impossible** : la visibilité de l'item de menu pilotait sur `isAvailable`, qui ne dit vrai qu'une fois la grammaire ENREGISTRÉE — au premier lancement (grammaire jamais chargée : préférence d'activation automatique désactivée par défaut) l'item était invisible, et après une désactivation (`unregisterGrammar`) il DISPARAISSAIT : impossible de revenir sur la voie grammaires. La visibilité pilote désormais sur la nouvelle sonde `hasEmbeddedGrammar` (capacité), la coche sur `isAvailable` (état) — la bascule est atteignable dans les deux sens, avant comme après chargement. Couvert par `AtelierToolsTest.laBasculeTextMateResteVisibleDansLesDeuxSens`.

### Fixed (documentation resynchronisée)

- **Statistiques recalées — la démo entre dans les comptes** — la table `FEATURES.md` comptait les sources sans la démo : fichiers source 265 → 281 (`:cel-core` 81 · `:cel-lsp-api` 39 · `:cel-lsp` 45 · `:cel-ui` 93 · `:cel-textmate` 7 · `demo` 16 — les modules bibliothèque sont INCHANGÉS, seul `demo` est désormais compté), fichiers de test 270 → 280, lignes de code ≈ 60 300 → ≈ 66 700 (bibliothèques ≈ 60 500 · démo ≈ 6 200), tests 2 385 → 2 425 (`demo` 31 → 61, `:cel-textmate` 79 → 81, `:cel-ui` 700 → 708 — B16/B17/B24). `demo/README.md` réécrit : « quatre des cinq modules » → **« les cinq modules publiables »**, structure de l'atelier, mini serveur LSP, comptes de tests par fichier, « Aucun serveur LSP n'est embarqué » (périmé) → « mini serveur LSP embarqué et in-process, démo hors-ligne ».
- **Benchmarks A/B robustes au bruit des runners CI** — `EditorSessionBenchmark` : les deux comparaisons ancien/nouveau chemin (P-02 `getText()` par frappe, et `lineText` contre `getText().substring`) mesuraient UN seul passage et assertaient l'ordre sans marge : le runner CI partagé (JIT, GC, contention CPU) a inversé les deux mesures sur le commit `05620a6` — sans aucun changement de code, pendant que le workflow de publication passait les mêmes tests sur le même commit. Chaque chemin est désormais mesuré sur 3 tours dont on garde le MINIMUM (le bruit ne fait qu'ajouter du temps — le minimum l'écarte), et l'assertion porte une marge explicite : gain ≥ 2× pour P-02 (écart structurel — l'ancien matérialise tout le document à chaque frappe), écart ≤ +25 % pour lineText (les deux chemins sont structurellement proches : 1 matérialisation + 999 lectures cachées contre 1000 `subText` — l'assertion protège d'une régression STRUCTURALE, pas de quelques pourcents de bruit résiduel).

## [3.72.0] — 2026-10-16 — Multi-langages (ADR-002 : chantiers C1→C3→C2) + correctifs retour terrain démo

### Fixed (retour terrain démo 3.72.0-dev : bascule grammaires)

- **Classes POSIX `[:alpha:]`/`[:alnum:]` traduites même sans autre écart dans la regex** — le chemin rapide de `RegexCompat.translate` ne déclenchait la traduction QUE si la regex portait une accolade, un `(?#` ou un `#` en mode étendu : les motifs n'en portant pas (`[_$[:alpha:]][_$[:alnum:]]*` du javascript réel, `([[:alpha:]_]\w*)` du python) restaient traduits par `java.util.regex` en union de littéraux `{\[, \:, a, l, p, h}` — le nom de fonction `ajouter` était COUPÉ (`a` seul en `entity.name.function`, le reste en texte brut), les décorateurs python et les paramètres typés ne matchaient pas. La détection du chemin rapide sonde désormais `[:`. Rouge avant : `GrammarTokenizerTest.classesPosix` (le nom couvert ENTIÈREMENT) + régression sur la grammaire javascript embarquée (`creerPanier` complet en FUNC).
- **Captures VIDE non gonflée, captures IMBRIQUÉES de même plage : la plus INTERNE l'emporte** — l'émission des spans de capture forçait tout match de groupe à un caractère minimum (`gEnd = max(ge, gs+1)`) : la capture optionnelle VIDE `(:*)` des pseudo-classes CSS posait un span `invalid.illegal` (ERREUR rouge) sur le caractère SUIVANT — le « r » de `:root` sortait en erreur ; les groupes optionnels non participants des paramètres javascript duplicquaient les séparateurs. Et les captures imbriquées couvrant la MÊME plage (`((@))` des décorateurs python, `((self)|(cls))` des paramètres spéciaux) émettaient DEUX spans superposés — le modèle plat de `TokenType` n'en retient plus qu'UNE, l'interne (l'externe est REMPLACÉE). Rouge avant : `GrammarTokenizerTest.captureVide` (aucune portée ERROR sur `:root`) et `capturesImbriquées` (un seul span sur le `@`) + régressions CSS/python sur les grammaires embarquées.
- **Porte de 5 000 caractères par ligne pour la délégation TextMate (échantillon « Minifié (JS) » de la démo)** — la délégation ne regardait que le NOMBRE de lignes du document (`TEXTMATE_LINE_LIMIT = 800`) : une ligne minifiée de 18 470 caractères partait sur le moteur de grammaires (~660 ms/ligne mesuré sur JVM de bureau, plusieurs secondes sur terminal) et l'étalait sur le restyle asynchrone. `SyntaxHighlighter.TEXTMATE_CHAR_LIMIT` (5 000) contourne la délégation ligne par ligne — la ligne monstre retombe sur le tokéniseur intégré au coût linéaire (~30 ms), les lignes normales du même document continuent de déléguer. Le contrat est documenté dans `TextMateTokenizer`, la garde alignée sur `MAX_INLAY_LINE_LEN` du résolveur de layouts. Rouge avant : `SyntaxHighlighterTest.textMateGate_ligneTropLongueContournée`.
- **Calibrage C4 : javadoc, primitifs, mots-clés de déclaration, import kotlin, décorateurs python convergent avec la voie intégrée** — `comment.block.javadoc` (java) → DOC_COMMENT (comme `comment.block.documentation` du csharp et le `/** */` intégré) ; `storage.type.primitive` (java) et `storage.type.function`/`storage.type.class` (javascript `function`, python `def`/`class`) → KEYWORD (mots-clés, pas TYPE) ; `keyword.control.new` → KEYWORD, `keyword.control.import` → KEYWORD (python), `keyword.control.export` → KEYWORD_MODIFIER (javascript) ; `storage.modifier.import` (kotlin, chemin d'import complet) → PROPERTY (convergence segment-après-point) au lieu d'un bloc KEYWORD_MODIFIER sur tout le chemin ; décorateurs python (`entity.name.function.decorator`, `punctuation.definition.decorator`) → ANNOTATION, construct équivalent des `@Nom` de la voie intégrée. Rouge avant : `ScopeMapperTest.calibrage_*` ; deux assertions de `TextMateTokenizerImplTest` (python `def`, markdown java `int`) recalées sur le comportement calibré.

### Added

- **C3-sexties : les trois options du propriétaire livrées — imbrication html→CSS/Kotlin réparée, grammaires CSS + Kotlin embarquées, bascule « Grammaires TextMate » dans la démo** — les options (a)/(b)/(c) du point d'arrêt C3-quater sont bouclées, et leur préparation a révélé deux écarts sémantiques du moteur qui tenaient l'imbrication des grammaires réelles :
  - **Idiome `\G` en tête de begin** — en `java.util.regex`, `\G` vaut le début de région après `reset()+region()` (la position courante TOUJOURS) : `\G` en tête de begin matchait PARTOUT et `(?!\G)` en tête ne matchait JAMAIS. La paire des wrappers (`<style>`/`<script>`/svg/math : tête de balise `{begin: \G}` + cadre du contenu `{begin: (?!\G)}`) était cassée : la tête se ré-empilait après son `>` et avalait le CONTENU du bloc en attributs, le cadre du contenu ne s'ouvrait jamais — l'imbrication html → grammaire invitée ne fonctionnait pas. Le préfixe est retiré au parse (règles marquées `beginG`/`beginNotG`), la garde est portée par le moteur : `Ctx.lastBeginEnd` = fin du DERNIER begin de la ligne (`-1` = aucun) — `\G` ne matche qu'à cette position, `(?!\G)` partout ailleurs (sémantique cohérente avec la garde `contentStart` des ends `(?!\G)` depuis C3-ter). Rouge avant : `GrammarTokenizerTest` sur une grammaire de travail reprenant la paire réelle (contenu coloré en attribut, fermante qui ne refermait pas toute la pile) ; garde d'intégration html→javascript.
  - **Listes de portées séparées par espaces** — une règle TextMate peut porter PLUSIEURS portées (« meta.property-name.css support.type.property-name.css », convention externe-d'abord/interne-en-dernier) : `ScopeMapper.tokenTypeFor` cherchait la chaîne ENTIÈRE dans la table — aucune correspondance, jeton PLAIN (noms de propriétés CSS, définitions de variables JS, 125 portées python). La liste est éclatée et la première portée NON PLAIN de l'INTERNE vers l'externe l'emporte. Rouge avant : `ScopeMapperTest` ; effet visible : `color` → TYPE, `red` → CONSTANT sur la grammaire CSS.
  - **Option (c) : grammaire CSS embarquée** — la grammaire CSS officielle de l'écosystème (`css.tmLanguage.json`, 81 Ko, copie VERBATIM, MIT, en-tête `information_for_contributors` d'origine conservé) rejoint l'inventaire : la grammaire html embarquée référence `source.css` (8 includes — blocs `<style>` et attributs `style=`), enregistrée elle se délègue ces zones — le CSS d'un `<style>` est coloré par la VRAIE grammaire CSS (sélecteur TYPE, propriété TYPE, valeur de couleur CONSTANT). `NOTICE` : inventaire sept → huit. Rouge avant : `GrammarException` sur `loadEmbeddedGrammar("css")` + les deux tests d'imbrication.
  - **Option (b) : grammaire Kotlin embarquée (plist XML)** — la provenance que le point d'arrêt laissait invérifiable est ÉTABLIE : l'UUID de grammaire `d9380650-5edc-447d-8dbd-98838c7d0adf` identifie le fichier original du dépôt d'origine (**Apache-2.0**, licence vérifiée), et le diff se réduit au PROLOG XML plist (DOCTYPE retiré : le parseur du module refuse les DOCTYPE, protection XXE — la copie est octet pour octet celle de la distribution tierce). Le chargeur essaie désormais `.tmg.json` (dépôt), `.tmLanguage.json` (tierce JSON) puis `.tmLanguage` (tierce plist, détection automatique C3-bis). `NOTICE` : section Apache-2.0 dédiée + exception motivée du garde-fou external-refs pour l'URL d'attribution. Rouge avant : `GrammarException` sur `loadEmbeddedGrammar("kotlin")`.
  - **Option (a) : bascule « Grammaires TextMate » dans l'app de démo** — septième bascule de la bande du bas : le langage COURANT passe du tokéniseur intégré aux VRAIES grammaires embarquées (java, python, kotlin, css, html, javascript, lua, markdown, xml — et csharp, grammaire-only, dont la bascule retombe sur le repli C_LIKE). La VÉRITÉ vit dans le registre du module (`isAvailable`) : la bascule altère le registre puis restyle (`setLanguage` → restyle complet asynchrone), et se RESYNCHRONISE sur le registre à chaque changement d'échantillon (garde anti-boucle). Cas html : la grammaire css est CO-ACTIVÉE (toast explicatif) — vitrine de l'imbrication `<style>` ; un langage sans grammaire garde la voie intégrée. Tests de fumée : activation/retour, survie au changement de langage, co-activation html+css, repli php. Texte « À propos » enrichi.

- **C3-quinquies : la clause `while` devient une garde de continuation de ligne — et les grammaires imbriquées travaillent vraiment** — deux correctifs sémantiques découverts en vérifiant que le bloc ```java d'un document Markdown est coloré par la grammaire java incluse. (1) **`while`** : le pliage C3-bis (while = fermante) oscillait sur les while en lookahead NÉGATIF (64 règles de la grammaire markdown tierce : blocs de code — la fermante matchait vide partout, pousser/dépiler en boucle, le contenu des blocs restait PLAIN) : la clause est désormais évaluée au DÉBUT de chaque ligne suivante — échec → le cadre ne s'applique pas à cette ligne (dépilement en cascade : la ligne de clôture ``` dépile le bloc embarqué puis la fermante du bloc parent la matche) ; succès non vide → le préfixe de continuation est consommé (le « > » d'une citation, portée de la règle — les grammaires réelles n'utilisent pas whileCaptures, simplification documentée). Une règle begin+while sans end est valide (le cadre n'a PAS de fermante en ligne). (2) **Grammaire propriétaire** (`Rule.owner`) : les `#ref` des règles INVITÉES se résolvent dans le REPOSITORY de l'invitée, pas de l'hôte qui l'inclut (markdown inclut source.java : sans owner, le walk mourait au premier `#code` introuvable — tout le contenu invité restait PLAIN) ; l'instance de grammaire est créée avant l'analyse et les règles installées SUR elle en seconde phase (une copie ferait pointer les owner vers un repository vide). Résultat vérifié : «```java» puis «int x = 1;» dans un document Markdown → `int` TYPE, `x` VARIABLE, `=` OPERATOR, `1` NUMBER, `;` PUNCT, la clôture referme le bloc. Tests : `while-test.tmg.json` (continuation avec consommation du préfixe, dépilement sur ligne sans préfixe, lookahead négatif sans oscillation), résolution des `#ref` chez l'invitée, intégration markdown+java embarquées. `GrammarTokenizerTest` 21 → 25, `TextMateTokenizerImplTest` 11 → 12. Perf consolidée : 732 µs/ligne java type (2 488 avant C3-ter, l'oscillation while éliminée paie encore).


- **C3-quater : 7 grammaires tierces TextMate embarquées (html, java, javascript, lua, markdown, python, xml — MIT, opt-in par langage)** — le module `:cel-textmate` embarque désormais les grammaires réelles de l'écosystème (copies VERBATIM, ~700 Ko de ressources) : un hôte active celle de son choix par `loadEmbeddedGrammar("java")` — le parse indulgent C3-bis les charge, le moteur C3-ter les tokénise correctement. **Contrat « opt-in par langage » préservé** : elles sont INACTIVES après `create()` (seule `csharp`, grammaire du dépôt, l'est) — un langage pourvu d'un tokéniseur intégré ne bascule PAS silencieusement sur la voie grammaires, le rendu d'un hôte qui croche le module sans rien demander ne change pas ; l'API lève une `GrammarException` listant l'inventaire pour un langage inconnu, est idempotente et normalise la clé. Discipline D3 tenue fichier par fichier : licences d'origine vérifiées (titulaires et dépôts consignés dans `NOTICE`, table fichier → source → titulaire ; lua accompagné de ses deux textes MIT locaux). La grammaire **kotlin** (plist) est alors écartée — ni en-tête d'attribution ni licence vérifiable, décision laissée au propriétaire. Bonus de l'imbrication : markdown + java activés ensemble → les blocs de code ```java des documents Markdown sont colorés par la grammaire java (includes externes résolus par le registre C3-bis). Tests : inactivité par défaut puis activation, tokénisation réelle java/python, erreur claire sur langage inconnu, inventaire complet chargé et tokénisé (7 langages, échantillons types). `TextMateTokenizerImplTest` 7 → 11 tests.
- **C3-ter : le moteur de grammaires parle le TextMate des grammaires modernes — et scanne 2,4× plus vite** — réécriture de la boucle de `GrammarTokenizer` en ancrage position-par-position avec bornes TRANSPARENTES (un `\b`/lookbehind à la position courante voit le VRAI caractère précédent — le begin lookahead de function-call python matchait au milieu des mots en bornes d'ancrage), **matchs de largeur nulle sémantiques** (une fermante lookahead `(?=\))` DÉPILE et RETENTE à la même position — l'ancienne garde anti-boucle sautait un caractère : le `)` échappait à la fermante du parent, le cadre function-call ne se refermait jamais, le `#` du commentaire python n'était pas coloré et le `}` du corps de classe java ne fermait rien ; une ouvrante lookahead EMPILE et retente : plus de trou entre les blancs de tête et le premier mot), **« \n » virtuel** en fin de ligne (les fermantes `\n`/`$` des règles de ligne matchent et referment leur cadre — l'état ne fuit plus sur l'aval, positions bornées à la ligne réelle) et **idiome `(?!\G)`** des wrappers « blancs de tête + lookahead » (java/html/lua réels) : en java.util.regex `\G` vaut toujours la position courante et l'idiome ne matcherait jamais — le préfixe est retiré du pattern compilé et la garde portée par le CADRE (fermante dès que le scan dépasse le début du contenu) ; le wrapper de commentaire java se referme, la ligne suivant un `//` est typée normalement. Garde anti-récursion par (règle, position) pour les poussées vides, plafond de pile 100, disjoncteur de 50 étapes vides consécutives. Performance : matchers réutilisés par ligne (IdentityHashMap), includes résolus mémoïsés, bornes posées une fois par matcher — ligne java type de 117 caractères : 2488 → 1025 µs (sonde honnête à rebouclage périodique). 7 régressions sur grammaire de travail reprenant les idiomes réels (`lookahead-test.tmg.json`) ; `GrammarTokenizerTest` 14 → 21 tests. Vérifié par harnais sur les 8 grammaires tierces : java (`public` typé, classe refermée sur `}`), python (commentaire coloré, docstring propagée puis fermée), lua (bloc `--[[` propagé, fermé, code suivant typé), kotlin, xml, html, javascript, markdown.
- **C3-bis : les 8 grammaires tierces chargent (compatibilité écosystème TextMate)** — `registerGrammar` accepte désormais le **plist XML** (nouveau `PlistParser` interne sur javax.xml, doctype interdit/XXE coupé — `Kotlin.tmLanguage` est un plist, pas du JSON ; détection automatique), le **dialecte Oniguruma** via `RegexCompat` conservateur (commentaires `(?#…)` retirés — python ; accolades orphelines échappées — java ; `#` de classe de caractères protégé en mode `(?x)` — python ; classes POSIX `[:alpha:]` traduites en `\p{Alpha}`), la **clause `while`** (blocs continus markdown : acceptée comme fermante, le bloc se referme sur la ligne de continuation et se rouvre par sa begin — simplification documentée), les **includes externes** (`html` → `source.css`, markdown → ses blocs de code, lua → `source.c` : la référence est résolue à la tokénisation par le REGISTRE des grammaires enregistrées — l'hôte qui embarque html ET css obtient l'imbrication ; absente, la règle ne matche rien, dégradation documentée avec avertissement agrégé, garde de profondeur 50) et un **mode indulgent** (`\g<n>` des appels de sous-expression Oniguruma, 8 règles markdown, est intraduisible : la règle devient muette avec avertissement, la grammaire charge — parse strict inchangé pour les grammaires du dépôt ; `resolveEnd` ne lève plus jamais en coloration). Tests : plist/while/externes/Oniguruma + résolution et dégradation au moteur. Vérifié par harnais : les 8 grammaires tierces (html, java, javascript, kotlin, lua, markdown, python, xml) chargent et tokénisent sans exception, propagation inter-lignes comprise.
- **C2 TERMINÉ : le cliquet de démantèlement est complet — les 14 tokéniseurs de la table ne sont plus que des délégations (ADR-002, C2-é7→é14)** — plus AUCUN corps de tokéniseur écrit à la main : chaque entrée (`Properties`, `TOML`, `Shell`, `YAML`, `JSON`, `CLike`, `Lua`, `Sql`, `Python`, `Smali`, `Css`, `Log`, `Xml`, `Markdown`) est réduite à sa signature et sa javadoc (41-64 lignes), toute la grammaire vit dans les templates déclaratifs de `TokenizerTemplate`. Le moteur gagne TROIS saveurs et huit migrations, chacune livrée au cliquet (caractérisation verte AVANT, tests rouges des capacités machine d'abord, suite inchangée après) :
  - **C2-é7 Lua** — les littéraux à délimiteurs sont évalués AVANT le commentaire de ligne en flux libre (un littéral peut partager son préfixe : `--[[` commence par `--`), chaînes longues `[[ ]]` réutilisant l'état inter-lignes du commentaire de bloc (la continuation est COMMENT — garde historique).
  - **C2-é8 SQL** — `plainStringType` (le type d'une chaîne « simple » est déclarable : l'identifiant quoté `"colonne"` devient PROPERTY, la sémantique échappements-avalés du Shell reste) et `numberLeadingDot` (`.5` ouvre le nombre, même collé à un mot — la frontière ne s'applique qu'à l'ouverture au chiffre).
  - **C2-é9 Python** — `stringPrefixes` (f/r/b : le préfixe fait partie du jeton de chaîne), `annotationWordChars` (`@app.route` en UN jeton), `annotationRequiresBoundary` (a@b n'est pas un décorateur) et `wordFuncAfterCase` (la casse est évaluée AVANT la parenthèse : `Foo(` est TYPE, pas FUNC — garde Python).
  - **C2-é10 Smali** — `annotationKeywords` (le JETON entier est raffiné par table : `.class` → KEYWORD, `.foo` → ANNOTATION — réutilisé par CSS pour `@media`), `typeDescriptor` (`L…;`/`[L…;` à fermante proche), `numberSignChars` (`-`/`+` ouvrent toujours un nombre, même seuls), `stringOrphanFallsThrough` (la chaîne orpheline n'est pas consommée — garde historique), `wordFuncContainsChars`/`wordFuncSuffixChars` (opcodes à tiret/slash).
  - **C2-é11 CSS** — `variableWordChars` (`$primary-color`), `numberUnitSuffix` (`12px`, `100%` en un jeton), `wordStartExtras` («&nbsp;&&nbsp;» ouvre un mot sans le continuer : a&b est DEUX mots — garde SCSS) et `interLineQuotes` (chaîne inter-lignes fermable par l'un des guillemets déclarés, «&nbsp;"&nbsp;» en premier — garde historique CSS).
  - **C2-é12 Log — TROISIÈME SAVEUR : le classifieur de ligne** — règles contient/commence/finit évaluées dans l'ordre de déclaration, premier match gagnant, ligne entière en une span, insensible à la casse par défaut (minuscule `Locale.ROOT` une seule fois — chemin rapide des consoles), dérogation sensible pour le chevron Gradle `&gt; `, repli PLAIN SANS span. `LogTokenizer.styleLog` délègue à `TokenizerTemplate.LOG` (26 règles dans l'ordre historique : ERROR avant SUCCESS avant INFO).
  - **C2-é13 XML — QUATRIÈME SAVEUR : le mode balisage** — balises ouvrantes/fermantes au nom QUALIFIÉ éclaté (`android:text` → NAMESPACE + PUNCT + PROPERTY), boucle d'attributs partagée entre le chemin en ligne et la continuation `XML_TAG` (les attributs multi-lignes restent PROPERTY), fermeture `>`/`/>`, entités `&…;` à fenêtre de 12 (l'orpheline est du texte), texte brut stoppé par `<` et `&`, et `BlockLiteral.carriesState` (la PI `<?…?>` ne porte pas d'état). Les QUATRE états inter-lignes historiques (`BLOCK_COMMENT`/`XML_CDATA`/`XML_STRING`/`XML_TAG`) sont repris octet pour octet.
  - **C2-é14 Markdown — CINQUIÈME SAVEUR : le balisage en ligne** — clôtures de bloc à BASCULE d'état (seule une ligne COMMENÇANT par ``` referme), titres en tête de ligne, gras `**` apparié, emphase `*`/`_` (astérisque intra-mot autorisé, souligné refusé — snake_case reste du texte ; fermante right-flank), code en ligne (règle des substitutions par paires réutilisée), liens `[t](u)`, marqueurs de liste, texte brut dont le run d'un caractère ÉTEND le span précédent (garde historique).
  Chaque migration apporte sa suite de caractérisation (`LuaTokenizerTest` 26, `SqlTokenizerTest` 22, `PythonTokenizerTest` 25, `SmaliTokenizerTest` 22, `CssTokenizerTest` 20, `LogTokenizerTest` 21, `XmlTokenizerTest` 19, `MarkdownTokenizerTest` 20 tests — verts AVANT la migration, inchangés après) et ses tests rouges machine (templates maison BLOC, REQUETE, PY, DEX, FEUILLE, CONSOLE — un format inventé par saveur).
- **C4 livré : le schéma de portées pour les thèmes (ADR-002, convergence des deux voies)** — `ScopeMapper` (voie grammaires) couvre désormais TOUTES les catégories standard de l'écosystème TextMate et les calibre pour converger avec la voie intégrée : interpolations (`meta.interpolation`, `meta.template.expression`, `punctuation.definition.template-expression` → VARIABLE, comme les `${…}` des template literals JS/TS côté intégré), propriétés d'objet (`variable.other.object.property` → PROPERTY, comme l'identifiant après point de la famille C), délimiteurs de définition colorés comme leur contenu (`punctuation.definition.comment`/`string` — plus de PUNCT gris pour les `//` et guillemets), `invalid.deprecated` → WARNING distinct d'`invalid.illegal` → ERROR, `support.module` → TYPE, et le markup des grammaires Markdown sur les MÊMES types que `MarkdownTokenizer` (titres KEYWORD, emphases EMPHASIS, code inline STRING, liens FUNC). Garde côté thème : `EditorThemeSyntaxPaletteTest` (cel-ui) verrouille la palette à **23 entrées `syntax.*`** (15 champs dédiés + 8 clés virtuelles), l'absence de TOUTE clé par langage (critère C4 : un thème colore un langage nouveau — csharp aujourd'hui, n'importe quel `.tmg.json` demain — sans clé dédiée), la coloration de chaque `TokenType` sur les neuf thèmes prédéfinis avec héritage parent par défaut, et la séparation des clés virtuelles à la demande (`EditorTheme.from(…).set("syntax.docComment", …)`). Javadoc d'`EditorTheme` et de `ScopeMapper` documente le schéma C4.
- **La famille C migre sur le tokéniseur générique (ADR-002, C2-é6 — le gros morceau du cliquet)** — le cœur historique du « parser maison » (`CLikeTokenizer`, partagé par java, kotlin, javascript, typescript, c, cpp, go, rust, ruby, php, swift, dart, groovy et tout langage C_LIKE enregistré par l'hôte) devient une délégation au template déclaratif `TokenizerTemplate.CLIKE` — `styleCLike` prend le langage en paramètre (mots-clés du REGISTRE par langage, repli mots-clés Java). Le moteur en mode flux libre gagne : les **littéraux à délimiteurs** (`TokenizerTemplate.BlockLiteral` : `/**` et `/*` à états `DOC_COMMENT`/`BLOCK_COMMENT`, chaîne brute `"""` réservée à « kotlin » exact — gardes historiques reprises octet pour octet : `/**/` se referme, `/*/` ne se referme pas), les **littéraux de caractère** (`charQuotes` → CHAR, orphelins sans état inter-lignes), les **template literals JS/TS** à interpolations `${…}` en VARIABLE et échappements en ESCAPE (`addTemplateLiteralSpans` porté à l'identique, accolades imbriquées comprises, portes javascript/typescript/js/ts), les **annotations** `@Nom` et **couleurs hexadécimales** `#RGB`/`#RRGGBB` en flux libre, et le **raffinement des mots** : contrôle/modificateur via les tables partagées, types intégrés (JAVA_TYPES), tout-en-majuscules en CONSTANT, identifiant après point en PROPERTY, initiale majuscule en TYPE, appel suivi de `(` en FUNC. `CLikeTokenizerTest` (nouveau, 30 tests de caractérisation écrits contre l'implémentation historique AVANT migration — portes par langage verrouillées : « kotlin » exact, ni java ni l'alias `kt` pour les chaînes brutes ; état XML_STRING défensif ; `/*/` orphelin) et les cliquets `SyntaxHighlighterTest`/`FineTokenDistinctionsTest` restent verts sans modification d'assertions ; `GenericTokenizerTest` démontre les capacités machine en rouge d'abord sur les templates maison PAGES (mini-langage C francisé) et REGISTRE (mots-clés du registre).
- **JSON migre sur le tokéniseur générique (ADR-002, C2-é5)** — la grammaire JSON descend dans le template déclaratif `TokenizerTemplate.JSON` (mode flux libre) : chaînes quotées à **échappements apparents** (ESCAPE interne, fermante trouvée en sautant les échappements, orpheline propagée par l'état inter-lignes XML_STRING), **clé requalifiée par lookahead** (une chaîne fermée suivie d'un deux-points — blancs sautés — reçoit PROPERTY, sinon STRING), **mots de lettres pures** (`wordConsumesDigits` : `abc123` → PLAIN + NUMBER, un chiffre collé ouvre un nombre sans frontière — garde historique JSON), **signe moins ancré à un chiffre** ouvrant un nombre (`-2.5e3`), et **opérateurs mono-caractère** jamais fusionnés (`:` et `,` restent deux spans distincts même adjacents). `JsonTokenizerTest` (nouveau, 19 tests de caractérisation — échappements unicode dans une clé, guillemet échappé, chaîne orpheline multi-lignes y compris le span vide historique sur ligne vide) et le cliquet `SyntaxHighlighterTest` restent verts sans modification d'assertions ; `GenericTokenizerTest` démontre les capacités machine sur le template maison FICHE (format de fiches balisées, état inter-lignes paramétrique). `JsonTokenizer.styleJson` délègue — signature inchangée.
- **TOML migre sur le tokéniseur générique (ADR-002, C2-é2 — sections, chaînes, nombres deviennent des règles)** — le moteur zoné gagne les en-têtes de section `[nom]`/`[[nom]]` (nom en TYPE, fermants en un jeton PUNCT de largeur calquée sur les ouvrants), les chaînes quotées en zone valeur (échappements internes en ESCAPE, guillemet orphelin en repli), la règle numérique à jeu de caractères étendu (`. - : T Z` — une date RFC 3339 reste UN jeton NUMBER), les mots-clés de valeur (booléens → CONSTANT, source registre D5) et les commentaires après le séparateur (`commentsAnywhere` : un `#` en fin de ligne TOML commente, jamais en Properties). `TomlTokenizer.styleToml` délègue à `GenericTokenizer.styleLine(TokenizerTemplate.TOML, …)`, comportement repris octet pour octet : `TomlTokenizerTest` (nouveau, 18 tests de caractérisation écrits contre l'implémentation historique AVANT la migration) et le cliquet `SyntaxHighlighterTest` restent verts sans modification de leurs assertions ; `GenericTokenizerTest` démontre le critère C2 sur un template maison à sections (format CONF, aucune ligne de moteur).
- **Shell/Bash migre sur le tokéniseur générique (ADR-002, C2-é3 — le flux libre entre au moteur, états inter-lignes)** — nouveau mode `freeForm` : la ligne est tokénisée en flux de jetons, sans zones clé/valeur. Le moteur gagne les **états inter-lignes paramétriques** (littéral non fermé → état déclaré du template, propagation et clôture à la ligne suivante — l'apostrophe multi-ligne de bash portée par `SHELL_SINGLE_QUOTE`), les préfixes de variable (`$var`, `${var}`, `$?`, `$1` — le `#` de `${…}` n'ouvre jamais un commentaire), les chaînes à guillemets doubles à échappements sautés (un seul jeton STRING), les substitutions par paires de backticks (ANNOTATION), la règle de mot (mots-clés du registre → KEYWORD, appel suivi de `(`/`[` → FUNC, initiale majuscule → CONSTANT, repli PLAIN) et les courses d'opérateurs (`|&`, `;;`). Commentaires ancrés : préfixe en début de ligne, après un blanc ou un `;` — `foo#bar` n'est pas un commentaire. `ShellTokenizer.styleShell` délègue à `TokenizerTemplate.SHELL` — `ShellTokenizerTest` (nouveau, 19 tests de caractérisation, y compris la propagation de l'état inter-lignes) et le cliquet restent verts sans modification d'assertions ; `GenericTokenizerTest` démontre le critère C2 sur un template maison de script (FLUX, état inter-lignes paramétrique).
- **YAML migre sur le tokéniseur générique (ADR-002, C2-é4 — clés par lookahead, marqueurs, ancres)** — le moteur zoné gagne les clés **confirmées par lookahead** de séparateur (sans `:` derrière, le candidat retombe en scalaire brut et la suite de la ligne devient zone valeur), les marqueurs de document (`---` → KEYWORD, n'importe où), les préfixes d'ancre/alias/tag (`&a`/`*a`/`!a` → ANNOTATION), les indicateurs de scalaire de bloc (`|`/`>` → KEYWORD en zone clé), les chaînes quotées reconnues dans les DEUX zones avec réouverture de zone clé après une chaîne fermée (réinitialisation `afterColon` du YAML historique, verrouillée par caractérisation), la règle de mot de valeur à jeux de départ/suite déclarés avec classification numérique (tête chiffre ou ±chiffre → NUMBER), la ponctuation de flux (`[ ] { }` → PUNCT) et le repli en scalaire brut (jusqu'à blanc/séparateur/`#`). Les constantes scalaires YAML (true/false/yes/no/null et variantes) restent une table LOCALE du tokéniseur : les profilifier activerait leur complétion intégrée (D5), un arbitrage de comportement à prendre avec le propriétaire. `YamlTokenizer.styleYaml` délègue à `TokenizerTemplate.YAML` — `YamlTokenizerTest` (nouveau, 23 tests de caractérisation, y compris la réouverture de zone clé après chaîne) et le cliquet restent verts sans modification d'assertions ; `GenericTokenizerTest` démontre le critère C2 sur un template maison d'inventaire (FICHIER). Constructeur de `TokenizerTemplate` simplifié (constructeur par builder — ~45 paramètres positionnels source d'erreurs).
- **`GenericTokenizer` + `TokenizerTemplate` : le tokéniseur générique piloté par la table (ADR-002, C2-é1 — fondation et première migration du cliquet)** — nouvelle machine paramétrique (`highlight/tokenizer/GenericTokenizer`) consommant une grammaire déclarative (`TokenizerTemplate` : préfixes de commentaire, séparateurs clé/valeur, types des trois zones, échappements en valeur). La famille lexicale devient le sélecteur de template, plus le lieu du code : **Properties est le premier tokéniseur migré** — `PropertiesTokenizer.styleProperties` délègue à `GenericTokenizer.styleLine(TokenizerTemplate.PROPERTIES, …)`, comportement repris octet pour octet (la suite `SyntaxHighlighterTest` reste verte sans modification de ses assertions — critère du cliquet de démantèlement). `GenericTokenizerTest` démontre le critère C2 sur un template MAISON (ini-like à points-virgules, types déclarés TYPE/RAW_STRING) : une grammaire clé=valeur complète sans une ligne de moteur. Les migrations suivantes (Toml, Yaml, Shell…) étendront le modèle tokéniseur par tokéniseur.
- **`:cel-textmate` — la voie grammaires entre au dépôt (ADR-002, C3, décision D1-a)** — nouveau module optionnel (`editor/cel-textmate`, `com.android.library`, dépendance `:cel-core` SEULE — aucun Gson, parseur JSON/JSONC interne) implémentant le crochet documenté `jo.codeeditor.highlight.TextMateTokenizer` : `TextMateTokenizerImpl.create()` charge paresseusement les grammaires embarquées du classpath (`textmate/*.tmg.json`), `registerGrammar(lang, source)` enregistre celles de l'hôte — supporter un langage de plus reste ajouter un FICHIER, pas du Java. Moteur `GrammarTokenizer` : leftmost-first, pile de contextes inter-lignes propagée par l'`exitState` opaque du contrat (piles internées → stabilité vérifiée : re-tokéniser une même ligne redonne le même état), portées de contenu (`contentName`), sous-règles actives dans les contextes, backreferences `\1` du `end` résolues avec les groupes du `begin`, fusion des spans adjacents. `TextMateGrammar` parse le format (match/captures, begin/end/beginCaptures/endCaptures, include `#ref`/`$self`/`$base`, repository) et échoue au CHARGEMENT sur regex incompilable ou include externe — jamais en coloration. `ScopeMapper` mappe les portées standard vers les `TokenType` existants, du segment le plus précis (`comment.block.documentation` → `DOC_COMMENT`, `keyword.control` → `KEYWORD_CONTROL`, `string.quoted.triple` → `RAW_STRING`, `entity.name.function` → `FUNC`…) — amorce du chantier C4 : un thème colore un langage grammaire-only sans clé par langage. Grammaire **C#** embarquée (fichier original du dépôt, Apache-2.0 — aucune attribution tierce, décision D3 documentée dans `NOTICE`) : **premier langage « grammaire-only »** (critère (ii) de l'ADR — aucun tokéniseur dédié ; le profil `csharp` de la table intégrée porte les mots-clés pour la complétion D5, le C_LIKE générique reste le repli hors-ligne). L'app de démo croche le module au démarrage et expose l'échantillon C#. 34 tests JVM (`JsonLiteTest`, `TextMateGrammarTest`, `GrammarTokenizerTest`, `ScopeMapperTest`, `TextMateTokenizerImplTest` — dont l'intégration au crochet `SyntaxHighlighter` : crochage → la tokénisation change, décrochage → retour octet pour octet au repli). Javadoc du contrat resynchronisée (`:cel-textmate`, classpath, API hôte), README/USAGE/BUILD/ARCHITECTURE/FEATURES/NOTICE documentent le module et ses coordonnées (`jo.codeeditor:cel-textmate` / `com.github.jjoblab.code-editor:cel-textmate`).
- **`stringQuotes` + `docComment` + `blockCommentContinuation` : le contexte lexical devient profilable (ADR-002, C1-é4)** — `LanguageProfile` porte les guillemets du scanner de chaînes (`stringQuotes`, défaut uniforme `"`/`'` — comportement historique), le délimiteur de commentaire de documentation (`docComment` — défaut `/**`/`*/` en C_LIKE) et le préfixe de continuation de bloc (`blockCommentContinuation` — défaut «&#160;*&#160;» pour les blocs C, null sinon). `CodeContext` (isInString / isInBlockComment / isInLineComment / isInStringOrComment) lit ces délimiteurs au lieu des littéraux C codés en dur : un commentaire `#` Python ou un bloc Lua `--[[ ]]` sont désormais vus — l'auto-close n'insère plus de paires dans du texte mort, la porte de complétion reste fermée dans un commentaire (variantes profilées dans `EditOps.isInStringOrComment(text, pos, language)` ; les variantes historiques sans profil conservent le repli C, compatibilité binaire). La continuation de commentaire au saut de ligne lit la table — et le motif historique « `/`+`*` → ` * ` », court-circuité depuis l'origine par le test d'opérateur en suspension (`*` est un opérateur dangling), produit enfin son effet documenté (« `/**` + Entrée » insère la continuation, plus une indentation d'opérateur). Priorité bloc-avant-ligne dans le scanner (Lua : `--[[` prime sur `--`). `CodeContextProfileTest` (JVM, 13 tests, rouges avant : backtick non vue, `#` non vu, continuation `///` impossible).
- **`NewlineStrategy` + `NewlineTrigger` : le saut de ligne intelligent devient une donnée du profil (ADR-002, C1-é3)** — `LanguageProfile` porte désormais la stratégie de newline (`NewlineStrategy.XML` structurelle / `KOTLIN` / `C_LIKE`, défaut calculé par famille, surchargeable) et ses déclencheurs d'indentation +1 (`NewlineTrigger.onSuffix(":")`, `("then")`, `("do")`… avec préfixes de mots-clés de bloc pour Python). Avant, `SmartNewline.smartEnter` dispatchait sur des égalités de chaînes éparses : `LanguageIds.isXml` (famille XML non surchargeable), `"kotlin".equals(language)` (nom en dur — les alias `kt`/casse mixte n'étaient pas honorés), et un `switch` par nom canonique pour les indent-triggers Python/YAML/Shell/Lua. La table intégrée déclare maintenant kotlin → `KOTLIN` et les triggers de python/yaml/shell/lua (comportements repris octet pour octet, y compris la garde anti-dictionnaire `{1: 2}`) ; un langage maison d'une famille existante reçoit la newline de son choix par simple entrée de table — critère C1 « zéro Java » `NewlineStrategyProfileTest` (JVM : 13 tests, rouges avant — surcharges impossibles).
- **Snippets de complétion intégrée dans le profil (ADR-002, C1 — D5 « source unique »)** — `LanguageProfile` porte `snippets` (liste immuable ordonnée, builder `snippets(...)`, vide par défaut) : la liste Markdown historique (« # », « ## », « - », clôtures de blocs de code…) descend de l'UI (`EditorCompletionPopup` la lisait en dur dans une branche « markdown ») vers la table intégrée — mêmes entrées, même ordre. Tout langage — intégré ou enregistré par l'hôte — peut désormais déclarer ses snippets à côté de ses mots-clés et la complétion intégrée les propose sans aucun code UI dédié. `LanguageProfileSnippetsTest` (JVM : ordre, immuabilité, table intégrée) + `ProfileSnippetCompletionsTest` (Robolectric, rouge avant : les snippets d'un langage maison n'étaient jamais proposés).
- **`CompletionGate` : la porte de complétion devient une donnée du profil (ADR-002, C1)** — nouvelle énumération `jo.codeeditor.languages.CompletionGate` (`OFF` / `ALWAYS` / `STANDARD`) portée par `LanguageProfile` (champ + builder `completionGate(...)`). Historiquement les exemptions vivaient dans l'UI : `EditorCompletionPopup.isInCodeContext` commutait sur la famille lexicale (LOG → jamais de complétion, XML → porte chaîne/commentaire écartée depuis le correctif 3.70.1). Ces politiques sont des données par langage : elles descendent dans le profil, avec les mêmes défauts par famille (LOG → `OFF`, XML → `ALWAYS`, toutes les autres → `STANDARD`), surchargeables par langage — un hôte peut offrir la complétion sur un format de log maison ou la couper sur un XML. Comportement des langages intégrés inchangé (tests XML de la 3.70.1 verts sans modification). `CompletionGateTest` (JVM, défauts/surcharges/langages intégrés) + `CompletionGateProfileTest` (Robolectric, rouge avant : les surcharges étaient ignorées par le switch famille).

### Fixed (documentation resynchronisée avant publication)

- **Comptes et inventaires recalés sur la réalité mesurée** — la table « Statistiques » de `FEATURES.md` datait de 3.69.0 : fichiers source 205 → 265 (dont `:cel-textmate` 7), fichiers de test 67 → 270, lignes de code ≈ 37 000 → ≈ 60 300, tests 1 370 → 2 385 avec le décompte par module, langages 27 → 29, ligne « grammaires embarquées : 10 » ajoutée. `ARCHITECTURE.md` décrivait encore « les 4 modules editor/ », « 27 langages », « 1 370 tests » et des grammaires « `*.tmg.json` … sans attribution tierce » (les 9 grammaires tierces et leurs attributions `NOTICE` existent depuis C3-quater/C3-sexties). `CONTRIBUTING.md` (1 370 tests, « AARs des 4 modules »), `BUILD.md` (2 mentions), `AGENT.md`, `USAGE.md` (« 27 langages intégrés » → 29, csharp et text ajoutés à la liste), `demo/README.md` (« quatre modules publiables » — la démo en exerce quatre des cinq, `:cel-lsp` excepté) et les en-têtes de `ci.yml`/`publish.yml` (« 4 AAR », « publish (4 modules AAR) ») recalés sur les **5 modules publiables**. Les relevés historiques (CHANGELOG ≤ 3.71.0, `docs/archive/`, `docs/plans/`) restent inchangés : ils décrivent leur époque.

## [3.71.0] — 2026-10-16 — Exécution du plan d'audit 3.69.0 (lots L1→L10) + décisions du propriétaire (Apache-2.0, plans 01/03, ADR-002)

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

- **CHANGELOG scindé** — 76 sections historiques déplacées vers `docs/archive/CHANGELOG-archive.md` (le fichier racine garde les 10 plus récentes : 354 Ko → 38 Ko lisibles en routine, historique inchangé) ; préambule restauré (perdu lors de la résolution du conflit de fusion 3.70.1) avec renvoi vers l'archive.
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

- **ADR-002 « stratégie multi-langages » acceptée** — le propriétaire arbitre les cinq décisions ouvertes dans le sens des recommandations : moteur de grammaires embarqué comme module optionnel `:cel-textmate` dépendant de `cel-core` seul (D1-a) ; palette de portées thèmes étendue (~25 clés sur les types fins existants, D2-b) ; grammaires embarquées sous réserve de vérification de licence fichier par fichier, attributions dans `NOTICE` (D3) ; ordre d'exécution **C1→C3→C2** (D4) ; mots-clés de complétion portés par la même table déclarative que la coloration (D5). L'exécution démarre sur la branche dédiée `agent/multi-lang-3.71.0`, critères d'acceptation (i)–(iv) de l'ADR en garde.
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
  (voir `docs/archive/MISE-A-JOUR-CODEIDE.md` §1.15). Nettoyage inclus :
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
  (`docs/archive/ANALYSE-GAPS-ANDROID-STUDIO.md`) — angle : l'édition tactile.
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
