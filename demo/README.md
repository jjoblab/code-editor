# demo — atelier de la bibliothèque

> 82 tests · Robolectric (fumée : lancement réel des activités,
> chaîne LSP complète) + JUnit pur (parseur, serveur) · application
> `com.android.application`, **non publiée sur Maven** — c'est une
> vitrine et un banc d'essai, pas un artefact.

L'application de démonstration exerce en situation réelle **les cinq
modules publiables** : elle branche `EditorView` sur des échantillons
multi-fichiers, un analyseur synthétique (moteur maison), l'aperçu
XML/HTML, le crochet `:cel-textmate` — et depuis 3.73.0, **le module
`:cel-lsp` de bout en bout** via un mini serveur de langage Java
in-process. Tout ce qui s'y voit est censé fonctionner — c'est le
contrat d'une démo.

## Position dans l'ensemble

```
:demo ──► :cel-ui (api → :cel-core, :cel-lsp-api)
      ──► :cel-textmate (crochet grammaires, optionnel)
      ──► :cel-lsp (mini serveur Java, transport in-process)
```

- Elle dépend des **sources locales** (`project(...)`) — jamais des
  artefacts publiés : ce qui est testé est l'état courant du dépôt.
- `DemoAnalyzer` implémente le SPI **sans serveur LSP** : diagnostics,
  complétion, signatures, quick doc et code actions synthétiques —
  la vitrine du « moteur de langage maison » de `USAGE.md`.
- `DemoLspController` + `MiniJavaLanguageServer` (paquet `lsp/`)
  incarnent **l'intégration LSP de référence** : un `LspProject`, une
  `LanguageServerDefinition` avec transport
  `InProcessStreamConnectionProvider`, un serveur lsp4j (diagnostics
  uniquement) et la bascule moteur maison ↔ moteur LSP.
- `DemoPreviewHost` implémente `EditorPreviewHost` (aperçu XML/HTML
  rendu par la démo elle-même, mode SPLIT/FULL) et, depuis B27, la
  **carte « goutte d'eau »** pour l'échantillon XML (`activity_main.xml` :
  œil de gouttière → onglets XML/Compose, badge « gonflé », mini-
  téléphone simulé sur canvas).
- `DemoTheme`/schemes : vitrine de la cascade thème → scheme.

## Ce qu'on y trouve

### Écran d'accueil (`HomeActivity`)

Cartes de capacité — chaque carte ouvre l'atelier préconfiguré pour
la fonctionnalité : **Diagnostics LSP Java** (moteur LSP connecté),
Atelier multi-langages, Grammaires TextMate (C#), Aperçu XML/HTML,
Stress bundle minifié, Cascade de thèmes. Les chiffres de tête
(29 langages · 23 grammaires · 5 modules) et l'entrée Réglages.

### Coquille IDE (`EditorActivity`)

- **Tiroir « Fichiers »** (`SampleDrawer`) : champ de **filtrage
  instantané** insensible à la casse et aux accents, sections par
  familles colorées (JVM, Web, Scripts, Systèmes, Données + filet
  « Autres »), badges par échantillon (**TM** = grammaire TextMate
  embarquée, **LSP** = mini serveur), point cyan sur les fichiers
  déjà ouverts — la même liste d'échantillons que l'ancienne démo :
  Java, Kotlin, **C#** (langage « grammaire-only »), Scala, Groovy,
  Python, JavaScript, **Minifié (JS)** (bundle de ~14 Ko sur une
  ligne), TypeScript, PHP, Ruby, Lua, Shell, C, C++, Go, Rust, Swift,
  Dart, XML, HTML (page autonome), JSON, YAML, TOML, Properties, CSS,
  Markdown, SQL, Smali, Log, Dockerfile — **30 langages (dont
  Minifié, échantillon de stress) + 2 échantillons de charge Java**
  (100k et 480k lignes).
- **Onglets multi-fichiers** (jusqu'à 10) : session + analyseur par
  fichier, fermeture par « × », réutilisation à la réouverture.
- **Panneaux outils en bottom sheet Material** : la feuille s'ancre
  au-dessus de l'éditeur (poignée de glissement, en-tête cliquable,
  replié = en-tête seul, jamais masquable) — **Diagnostics** (moteur
  actif — maison ou LSP, clic = saut à la ligne), **Symboles** (outline
  du document), **Journal LSP** (activité JSON-RPC horodatée).
- **Outils IDE au menu** : annuler/rétablir (grisés selon les piles),
  rechercher et remplacer (tout / occurrence suivante), aller à la
  ligne, taille de police (A+ / A− / 100 %), lecture seule (verrou de
  frappe, pastille dans la barre d'état), partage du fichier,
  statistiques du document.
- **Barre d'état** : Ln/Col du caret, langage, moteur de langage
  (● maison / ● LSP cyan), voie de coloration TextMate, UTF-8 /
  lecture seule.
- Barre de fil d'Ariane et recherche/remplacement intégrées, bascules
  **Moteur LSP** et **Grammaires TextMate** au menu (visible dès le
  premier lancement sur les langages à grammaire embarquée — sonde
  `hasEmbeddedGrammar`), options de vue (numéros, plis, guides,
  collantes, loupe, swatches, couleurs de variables, bande
  d'erreurs, sélecteur de couleur, point d'arrêt) et banc d'essai
  **« Complétion (listes tronquées) »** (4.3.1) au sous-menu.
- **Transition accueil → atelier** en « fade through » Material,
  miroir exact au retour.

### Écran Réglages (`SettingsActivity`)

Préférences persistées (`SharedPreferences`) relues par l'atelier à
chaque reprise : apparence (thème clair, scheme), éditeur (retour à
la ligne, minimap, ligatures, non-imprimables), aperçu (politique de
cohabitation goutte d'eau / popups — la TAILLE de la goutte se règle
directement dans la carte, barre du pied à côté de « fit », retour
terrain n°3), moteurs (grammaires TextMate automatiques, LSP
automatique sur Java). L'ancienne « bande de bascules » vit ici
désormais.

### Mini serveur LSP Java (paquet `lsp/`)

- `MiniJavaParser` — parseur conservateur pur JVM : accolades non
  fermées, fermantes orphelines, points-virgules manquants,
  branchements orphelins (`else`/`catch`/`finally` sans tête
  adjacente). Contrat : **zéro faux positif** — les tests couvrent
  les déclencheurs ET les non-déclencheurs.
- `MiniJavaLanguageServer` — serveur lsp4j (~200 lignes) :
  capabilities `textDocumentSync = Full`, didOpen/didChange/didClose,
  publication `publishDiagnostics`. Pur JVM, testable sans Android.
- `MiniJavaServerDefinition` — le seul point de branchement sur
  `:cel-lsp` : fabrique le transport in-process, exactement comme
  une intégration réelle (sous-processus, socket, LocalSocket).
- `DemoLspController` — possède le `LspProject`, écrit l'espace de
  travail (`filesDir/workspace/Atelier.java`), connecte/déconnecte
  l'éditeur, expose l'état (OFF/CONNECTING/CONNECTED), relaie le
  journal et les diagnostics.

La bascule « Moteur LSP » (menu, onglet Java uniquement) démontre
les DEUX voies de `USAGE.md` sur le même document : moteur maison
(popups synthétiques) contre serveur LSP réel (diagnostics poussés à
chaque édition, soulignés dans l'éditeur et listés au panneau).

## Banc d'essai « listes tronquées » (4.3.1)

Le menu **Complétion (listes tronquées)** transforme le moteur maison
en serveur qui plafonne sa réponse — la vitrine du chantier complétion
4.3.0 sans serveur :

1. **« Simuler un moteur qui plafonne »** : la base livrée au popup est
   coupée à 8 candidats et marquée incomplète
   (`provideResult`/`CompletionResult`).
2. **Ouvrir le panneau Journal** puis taper un identifiant caractère
   par caractère : chaque frappe re-requête le moteur (une ligne par
   requête : préfixe, déclencheur, plafond) — le candidat écarté par
   le plafond finit par réapparaître. Retour arrière inclus.
3. **« Filtrage local seul »** coupe la re-requête
   (`setHonorIncompleteCompletion(false)`) : le filtrage local travaille
   sur la base amputée — le comportement historique, candidats
   manquants possibles.
4. **Anti-rebond 80/200/500 ms** (`setCompletionDebounceMs`) : à
   500 ms, la coalescence des frappes rapides devient visible à l'œil
   nu.

La bascule porte sur tous les onglets ouverts et les suivants ; sur
l'onglet Java avec moteur LSP actif, le fournisseur est celui du
serveur (le banc s'applique au moteur maison).

## Bancs d'essai 4.4.0 — occurrences, modificateurs, documentation

Trois chantiers du palier P1 se vérifient dans l'atelier, sans serveur :

1. **Occurrences du mot courant** (menu du même nom) : parker le caret
   sur un identifiant répété — les occurrences entières s'allument au
   repos de frappe (~300 ms), celle du caret en teinte forte ; ouvrir la
   recherche pour voir la surbrillance lui CÉDER la place, la refermer
   et déplacer le caret pour la voir revenir. La bascule du sous-menu
   pilote `setWordOccurrencesEnabled`.
2. **Banc sémantique (modificateurs)** : « Marquer les identifiants
   fréquents » injecte sur chaque onglet des jetons sémantiques — les
   trois identifiants les plus fréquents du fichier sont marqués
   déprécié (barré), statique (gras), abstrait (italique). Les marques
   suivent les éditions ; « Retirer les marques » nettoie tout. La
   même voie de rendu que le client LSP (les teintes sont réglables
   dans le scheme : `semantic.modifier.*`).
3. **Documentation de complétion** : taper `pri` puis sélectionner
   `println` dans le popup — sa documentation s'affiche dans le panneau
   sous la liste (les candidats `println`, `format`, `substring` du
   moteur maison en portent). Sur vue étroite, le panneau cède la
   place au glyphe ⓘ de rangée.

## Bancs d'essai 4.5.0 — apprentissage, gabarits, postfix, gros fichiers, touches

Les paliers P2 et P3 se vérifient dans l'atelier, sans serveur :

1. **Apprentissage du classement** (menu du même nom) : accepter
   plusieurs fois le même candidat (ex. `print` pour le préfixe `pri`)
   — il remonte À PALIER ÉGAL dans les listes suivantes, devant les
   candidats jamais acceptés ; « Effacer l'historique » ramène le
   classement du moteur, la bascule coupe l'apprentissage.
2. **Banc gabarits (choix + macros)** : armer le banc puis taper `log`
   et accepter — le champ de choix ouvre sa carte d'options
   (msg/err/debug), toucher une option remplace le champ et avance ;
   `entete` insère une ligne d'en-tête où `$FILE$` prend le nom de
   l'onglet ; `ifnull` pose des champs navigables (Tab/Shift+Tab).
3. **Postfix par profil** : sur un onglet Java, taper `msg.sout` puis
   Entrée — `System.out.println(msg);` se matérialise (`.if`, `.for`,
   `.notnull`… restent de la partie ; `.sout` sur un onglet Kotlin
   donne la forme `println(msg)`).
4. **Mode gros fichiers** (menu du même nom) : forcer les coupures et
   ouvrir un échantillon « Charge 100k lignes » — la frappe reste
   nerveuse, la complétion auto ne déclenche plus ; le mode AUTO
   retrouve la politique aux seuils, OFF assument le coût.
5. **Barre de touches de code** : « Afficher au-dessus du clavier »
   pose la rangée Tab / Commenter / ↑ / ↓ / Dupliquer au bas de
   l'écran (l'adjustResize la remonte avec l'IME) — les touches agissent
   sur l'onglet courant sans fermer le clavier.
6. **Renommage XML lié** : sur l'échantillon XML, éditer le nom d'une
   balise ouvrante — la fermante suit caractère par caractère, une
   seule annulation retire les deux éditions.
7. **Compensation du viewport** : avec le mini serveur LSP (banc
   auto-import), accepter un candidat importé en tête de fichier — le
   texte sous les yeux ne saute plus d'autant de lignes.

## Build et installation

```bash
./gradlew :demo:assembleDebug
# APK : demo/build/outputs/apk/debug/demo-debug.apk
adb install demo/build/outputs/apk/debug/demo-debug.apk
```

Le mini serveur LSP est **embarqué et in-process** : la démo reste
**hors-ligne** — aucun serveur externe, aucune permission.

## Tests

```bash
./gradlew :demo:testDebugUnitTest     # 82 tests
```

- `MiniJavaParserTest` (20) — chaque règle, ses déclencheurs ET ses
  non-déclencheurs (modificateurs seuls, chaînes en continuation,
  annotations, masquage chaînes/commentaires, profondeur extrême).
- `MiniJavaLanguageServerTest` (5) — initialize (sync Full),
  publications didOpen/didChange/didClose, code propre/cassé.
- `DemoLspControllerTest` (2) — la chaîne COMPLÈTE contrôleur →
  projet → transport in-process → serveur → diagnostics en session,
  déconnexion et purge.
- `EditorActivitySmokeTest` (3) — lancement réel, coquille
  multi-fichiers (ouvrir/sélectionner/fermer), extra LSP de la carte
  d'accueil.
- `HomeActivitySmokeTest` (1) — lancement réel, cartes rendues.
- `AtelierToolsTest` (7) — bascule TextMate visible dans les DEUX
  sens, bottom sheet repliable, annuler/rétablir, remplacer tout /
  occurrence suivante, lecture seule bloque la frappe, point d'arrêt
  du « ⋯ » du toolbar de sélection (icône + bascule pose/retrait).
- `SampleDrawerTest` (3) — filtre sans casse ni accents, familles +
  échantillons filtrés en direct, remontée des sélections.
- `DemoPreviewHostTest` (24) — comportement de l'hôte d'aperçu,
  y compris la voie carte « goutte d'eau » B27 (xml prévisualisable,
  préférence carte seulement pour les layouts, onglets/badges).
- `ProgressiveEquivalenceE2ETest` (2) — équivalence régime progressif
  vs chaîne séquentielle (grammaire java réelle, 1 200 lignes).
- `EditorInfoHookDemoTest` (1) — hook `EditorInfo` : l'hôte pose
  `IME_FLAG_NO_PERSONALIZED_LEARNING` sans patcher la bibliothèque.
- `CompletionTruncatedDemoTest` (4) — le banc 4.3.1 : plafonnement +
  drapeau tronqué, compteur/journal des requêtes, relais des
  réglages hôtes, portée tous onglets.
- `EditorTogglesDemoTest` (2) — bascules bande d'erreurs et
  sélecteur de couleur relayées à la vue.
- `LspAutoPreferenceTest` (2) — la préférence « LSP automatique »
  branche le moteur sans extra ; l'extinction manuelle garde la main.
- `ThemeIdentityDemoTest` (2) — la base émeraude survit aux
  changements d'onglet (cas clair et sombre).

## Voir aussi

- [`editor/cel-ui/README.md`](../editor/cel-ui/README.md) — la vue
  branchée ici.
- [`editor/cel-lsp/README.md`](../editor/cel-lsp/README.md) — le
  cadre que le mini serveur traverse.
- [`editor/cel-textmate/README.md`](../editor/cel-textmate/README.md) —
  la bascule « Grammaires TextMate ».
- [`USAGE.md`](../USAGE.md) — ce que la démo illustre, documenté pour
  l'hôte réel.
