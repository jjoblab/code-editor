# MISE À JOUR DE CodeIDE — brancher la version 3.57.0 de `code-editor`

> **Destinataire** : agent IA chargé de la mise à jour du dépôt `CodeIDE`
> (jjoblab/CodeIDE, audité @ `04a65ea`, v0.105.0).
> **Objet** : tirer parti du lot « actions de code au point
> d'invocation » livré dans `code-editor` 3.50.0/3.50.1 et ses finitions
> 3.51.0/3.51.1, PUIS du lot « édition tactile et contexte visuel »
> livré en six jalons 3.52.0 → 3.57.0 (popup de diagnostic au tap,
> SPI d'actions de la pill, sticky interactif, inlays typés, loupe
> instrument, en-têtes enrichis, i18n) — **le lot est COMPLET en
> 3.57.0** : labelParts des inlays, hints cliquables, fenêtre
> viewport, sticky délégué au SPI langue, mouvements de lignes.
> **Ce document est la copie de référence archivée dans le dépôt** : il
> résume le comportement attendu et les changements côté CodeIDE. Les
> identifiants de commits sont ceux du dépôt du propriétaire, cités à
> titre de repères internes.

---

## 0. Résumé express — deux lots, un seul changement requis

| Lot | Versions | Thème | Ce que CodeIDE y gagne |
|---|---|---|---|
| Actions de code | 3.50.0 → 3.51.1 | Le pont client ne vide plus les réponses LSP | Quick-fixes réels au caret, ampoule dès publication, trigger « . », Unnecessary grisé |
| Tactile & contexte | 3.52.0 → 3.57.0 | Le dernier geste naturel est câblé — lot COMPLET | Tap sur le squiggle → popup ancré, pill extensible (SPI + actions socle, mouvements de lignes), sticky interactif + délégable au SPI langue, inlays typés + tooltips PAR PART + hints CLIQUABLES, loupe réglable, en-tête enrichi (code/source), libellés traduisibles |

**Un seul changement est indispensable** : monter la version dans
`gradle/libs.versions.toml` (§2). Tout le reste est additif — les
surfaces que CodeIDE touche (`LspProject`, `addServerDefinition`,
`createEditor`, `setEditorView`, `setCurrentFilePath`, `connect`,
`disconnect`, `shutdown`, `setLogSink`, `sendDidChangeConfiguration`)
sont inchangées ; les ajouts sont des méthodes par défaut, des
surcharges ou des constructeurs étendus déléguant à l'existant.

---

## 1. Le lot tactile 3.52.0 → 3.57.0 — ce qui change pour CodeIDE

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

---

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

## 2. Changement REQUIS — montée de version de la dépendance

```toml
# gradle/libs.versions.toml (CodeIDE) — ligne ~52
code-editor = "3.57.0"     # était "3.49.2" (audit) / "3.51.1" (guide 3.51.1)
```

Les deux modules consommateurs ne changent pas (ils suivent le
`version.ref`) :

- `lsp/host/build.gradle.kts:62` → `api(libs.codeeditor.lsp)`
- `feature/editor/build.gradle.kts:14` → `implementation(libs.codeeditor.ui)`

**Compatibilité API : vérifiée additive sur toute la plage
3.51.1 → 3.57.0** (aucune rupture binaire : nouveaux champs des
modèles diagnostic/inlay via constructeurs étendus, les
constructeurs historiques délèguent avec défaut vide).

### Publication vérifiée (2026-10-13)

- `main` @ `6f67e9f` poussé (merge J6 — lot complet) ; tag **`v3.57.0`**
  posé sur le commit de version `72dda7a`.
- CI verte sur les deux workflows (merge + tag) — runs
  `38331276328` / `38331276248`, conclusion `success`.
- **JitPack `v3.57.0` : « ok »** — 4 modules (cel-core, cel-lsp-api,
  cel-lsp, cel-ui), POM + `.module` vérifiés servis (HTTP 200) :
  `https://jitpack.io/com/github/jjoblab/code-editor/cel-ui/3.57.0/`
- Règle inchangée : **ne jamais consommer** `3.50.0`/`3.51.0` via
  JitPack (incident 429 historique — servies sur GitHub Packages
  uniquement) ; 3.51.1 et 3.52.0+ sont saines sur JitPack.

Coordonnées : `com.github.jjoblab.code-editor:cel-ui:3.57.0` et
`com.github.jjoblab.code-editor:cel-lsp:3.57.0`.

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
