# MISE À JOUR DE CodeIDE — brancher la version 3.51.1 de `code-editor`

> **Destinataire** : agent IA chargé de la mise à jour du dépôt `CodeIDE`
> (jjoblab/CodeIDE, audité @ `04a65ea`, v0.105.0).
> **Objet** : tirer parti du lot « actions de code au point d'invocation »
> livré dans `code-editor` 3.50.0/3.50.1 et de ses finitions 3.51.0/3.51.1
> (branches `agent/actions-lsp-3.50.0` puis `agent/actions-lsp-fin-3.51.0`,
> 17 commits au total, 1 429 tests verts, garde-fous verts).
> **Ce document est la copie de référence archivée dans le dépôt** : il
> résume le comportement attendu et les changements côté CodeIDE. Les
> identifiants de commits sont ceux du dépôt du propriétaire, cités à
> titre de repères internes.

---

## 0. Ce que le lot 3.50.x → 3.51.x change pour le serveur LSP Java de CodeIDE

`code-editor` est le CLIENT ; `lsp/java` de CodeIDE est le SERVEUR. Le lot
corrige le pont client qui **vidait structurellement les réponses** du
serveur. Concrètement, après la montée de version :

| Côté serveur (CodeIDE, déjà en place, inchangé) | Côté client (code-editor 3.51.x, réparé) |
|---|---|
| `DiagnosticService`/`JavaTextDocumentService` publient des diagnostics avec `code` (« ecj:<ID> »), `data` (arguments du compilateur), `source` | Le contexte `textDocument/codeAction` retransmet les diagnostics TELS QUELS (code + data + source + tags + sévérité), filtrés par intersection avec la plage demandée |
| `CodeActionService.actionsFor` rend vide si `diagnostic.code() == null` (l. 79) | Le code arrive → les quick-fixes réels (import du bon FQN, try-catch, cast, création de méthode, point-virgule, suppression d'import inutilisé) arrivent enfin à l'écran |
| `codeAction` répond des `CodeAction` avec `WorkspaceEdit` complet | L'edit est capturé et appliqué à l'acceptation (voie confinée, tout-ou-rien, atomique — inchangée) |
| Le serveur ne pousse pas `workspace/applyEdit` ni de commandes | Ces voies sont désormais implémentées côté client (aucune action serveur requise — elles sont simplement prêtes) |
| `renameProvider = true` (booléen) | Le client n'appelle `prepareRename` QUE si le serveur l'annonce (voir §3 — amélioration recommandée) |
| `completionProvider` annonce ses caractères déclencheurs (« . ») | (3.51.x) Le `CompletionContext` transporte le triggerKind RÉEL : `TriggerCharacter` + le caractère quand la complétion part de la frappe d'un « . », `Invoked` sinon |
| L'action « Organiser les imports » répond à `only = ["source.organizeImports"]` pour toute plage | (3.51.x) UNE requête par document avec le `only` — entrée « Organize imports » dans le menu contextuel de l'éditeur |
| Diagnostics avec `tags = [Unnecessary]` (import mort) et/ou sans sévérité | (3.51.x) Le tag est transporté : la plage est peinte ESTOMPÉE (grisé Android Studio) au lieu du soulignement ; sévérité absente → Information (plus de jaune injustifié) |

Le modèle d'invocation change aussi côté éditeur : **une seule requête
`codeAction` au point d'invocation** (caret/sélection/mot), au lieu d'une
requête par ligne visible toutes les 800 ms — la charge sur le moteur ECJ
tombe d'environ ×60 à ×1 par demande d'utilisateur, et l'ampoule de
gouttière apparaît dès la publication des diagnostics (plus de fenêtre de
debounce), y compris après défilement.

---

## 1. Changement REQUIS — montée de version de la dépendance

**Un seul changement est indispensable pour bénéficier du lot :**

```toml
# gradle/libs.versions.toml (CodeIDE) — ligne ~52
code-editor = "3.51.1"     # était "3.49.2"
```

Les deux modules consommateurs ne changent pas (ils suivent le
`version.ref`) :

- `lsp/host/build.gradle.kts:62` → `api(libs.codeeditor.lsp)`
- `feature/editor/build.gradle.kts:14` → `implementation(libs.codeeditor.ui)`

**Compatibilité API : vérifiée additive** — les surfaces que CodeIDE
touche (`LspProject(root)`, `addServerDefinition`, `createEditor`,
`setEditorView`, `setCurrentFilePath`, `connect()`, `disconnect()`,
`shutdown()`, `setLogSink`, `sendDidChangeConfiguration`) sont inchangées.
Les ajouts du lot sont tous des méthodes par défaut, des surcharges ou
des constructeurs étendus déléguant à l'existant.

### Prérequis — le tag `v3.51.1` est publié et vert (2026-10-13)

> **Note de re-publication** : le build JitPack de `v3.51.0` a été frappé
> par le même incident d'infrastructure que `v3.50.0` (HTTP 429 Maven
> Central, côté JitPack — échec en résolution de dépendances, aucune
> erreur de code) et sa clé est restée en erreur sans artefact servi ;
> le contenu a été re-publié sous `3.51.1` (commit `7a1d9de` = `554ca26`
> + bump). **Consommer 3.51.1** — jamais `3.50.0`/`3.51.0` (JitPack) ;
> ces deux versions restent servies sur GitHub Packages uniquement.
> Historique : `3.50.0` (échec 429) re-publiée en `3.50.1` ; le lot
> complet de finitions P2-9/P2-10/P2-11 est identique en 3.51.0 et
> 3.51.1.

Publié et vérifié (rien n'est attendu côté code-editor) : `main` @
`7a1d9de` poussé ; CI verte sur les deux commits de la livraison ; GitHub
Packages `3.51.0` et `3.51.1` publiées par le workflow de tag ; **JitPack
`v3.51.1` : « ok »** — 4 modules (cel-core, cel-lsp-api, cel-lsp,
cel-ui), POM et AAR vérifiés servis (HTTP 200, 2026-10-13) :
`https://jitpack.io/com/github/jjoblab/code-editor/cel-lsp/v3.51.1/`

L'étape restante (côté CodeIDE) est unique : monter la version dans
`libs.versions.toml` et compiler.

Coordonnées complètes : `com.github.jjoblab.code-editor:cel-ui:3.51.1` et
`com.github.jjoblab.code-editor:cel-lsp:3.51.1`.

---

## 2. Ce qui n'exige RIEN côté CodeIDE (vérifié sur le code du serveur)

- **Quick-fixes** : `JavaTextDocumentService.codeAction` lit
  `lspDiag.getCode()` (l. 705) et `lspDiag.getData()` (l. 719) — le client
  les transporte ; `extractDiagnosticArgs` accepte déjà le `JsonElement`
  qu'ils deviennent après le round-trip JSON-RPC (l. 1917+).
- **`context.only = null`** (popup au caret) : `wantsQuickFix` et
  `wantsOrganizeImports` restent vrais (l. 643-652) ; « Organiser les
  imports » apparaît UNE fois dans le popup au caret.
- **`context.only = ["source.organizeImports"]`** (3.51.x, menu
  contextuel « Organize imports ») : le serveur répond l'action pour toute
  plage — UNE requête par document, plus de sondage multi-lignes.
- **Trigger kind de complétion** (3.51.0) : le serveur reçoit
  `TriggerCharacter` + « . » après la frappe d'un point (les propositions
  de membres n'ont plus à être devinées), `Invoked` sinon — si le serveur
  lit le contexte, il peut affiner ; sinon, aucun changement.
- **Sévérité/plage/tags** : le round-trip est identitaire (l'objet brut est
  retransmis) ; le tag `Unnecessary` devient peinture estompée côté client.
- **`workspace/applyEdit` / `workspace/executeCommand` / `codeAction/resolve`** :
  implémentés côté client, le serveur ne les utilise pas — aucun
  changement requis, aucune régression possible.
- **Diagnostics `data`-support / tags** : le client déclare
  `publishDiagnostics.dataSupport/tagSupport/codeDescriptionSupport` —
  purement déclaratif, le serveur continue d'envoyer ce qu'il envoyait.

---

## 3. Améliorations RECOMMANDÉES côté CodeIDE (optionnelles)

### 3.1 `prepareRename` côté serveur

Le popup de renommage de l'éditeur CONSOMME désormais
`textDocument/prepareRename` quand le serveur l'annonce : champ pré-rempli
par le nom proposé, refus propre quand le caret n'est pas sur un
identifiant renommable (plus d'attente de 10 s avant l'échec).

1. **Annoncer** — `lsp/java/src/main/java/jo/lspjava/lsp/ServerCapabilitiesProvider.java`
   (l. 94, remplacer le booléen) :

   ```java
   // Avant : caps.setRenameProvider(true);
   org.eclipse.lsp4j.RenameOptions renameOptions = new org.eclipse.lsp4j.RenameOptions();
   renameOptions.setPrepareProvider(true);
   caps.setRenameProvider(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(renameOptions));
   ```

2. **Implémenter** — `JavaTextDocumentService` : ajouter la méthode
   `prepareRename(PrepareRenameParams)` qui retourne
   `Either3.forSecond(new PrepareRenameResult(range, placeholder))` où
   `range`/`placeholder` viennent de `EcjRenameEngine` (le symbole sous le
   caret : identifiant ? plage + texte ; mot-clé/littéral → `null` pour
   refuser proprement). Borné (3 s), lane ANALYSIS du
   `EcjEngineScheduler` comme les autres tirs.

Sans ce changement, le renommage fonctionne exactement comme avant
(repli local sur le mot sous le caret) — aucun blocage.

### 3.2 Consommer les nouvelles API d'hôte (3.51.x)

- **`EditorView.performFixAll(callback)`** — exécute l'action
  `source.fixAll` du serveur (UNE requête par document, application
  locale). Utile le jour où `CodeActionService` propose un fix-all ;
  aujourd'hui le callback reçoit FALSE (aucun serveur ne la propose).
- **`setOnDiagnosticsPublishedListener`** — le listener est désormais
  notifié aussi pour les publications **LSP** (avant : voie locale
  seulement) : l'onglet Problèmes du lot h3 peut s'y brancher, les
  diagnostics arrivent à chaque publication du serveur, avec la
  conversion sévérité/tags déjà faite.

---

## 4. Protocole de vérification sur appareil (après montée de version)

Reprendre le protocole « LSP branché » existant, avec les épreuves
directes ajoutées :

1. Ouvrir un projet Java ; attendre la barre d'indexation verte.
2. **Ouvrir un fichier avec une erreur intentionnelle** (ex. référence non
   résolue `Foo bar;` sans import) : l'ampoule de gouttière apparaît
   DÈS la publication des diagnostics — y compris après un défilement ;
   le point de diagnostic de la gouttière est présent.
3. **Tap sur l'ampoule** (ou `Ctrl+.` / `Ctrl+Shift+L` au caret sur
   l'erreur) : le popup liste les quick-fixes RÉELS du serveur
   (« Importer Foo (java.util) », etc.) — corrections (engrenage) en
   tête, refactorings/source ensuite.
4. **Choisir un fix** : l'édition s'applique (import inséré), UN SEUL
   undo restaure l'état exact.
5. Journal : `codeAction: requesting for range …` UNE ligne par
   invocation (plus de rafale par ligne visible) ;
   `codeAction: got N actions`.
6. Menu « Actions ⋯ » de la toolbar de sélection : sections QUICK
   FIXES/INTENTIONS peuplées (résolution à la demande à l'ouverture).
7. **(3.51.0) Import inutilisé** : ouvrir un fichier avec un import mort
   — si le serveur l'envoie avec `tags: [1]`, la plage est GRISÉE
   (estompée), pas soulignée en jaune.
8. **(3.51.0) Menu contextuel** (clic secondaire / appui long selon
   l'appareil) : entrée « Organize imports » quand le serveur LSP est
   branché — un tap déclenche UNE requête `only=["source.organizeImports"]`
   et applique la réorganisation (journal : une seule ligne
   `codeAction: requesting`).
9. **(3.51.0) Complétion** : taper `foo.` — le journal client affiche
   `completion: requesting at caret=… trigger=.` (et non `invoked`) ;
   les propositions de membres arrivent.
10. Renommage (avant §3.1) : comportement inchangé ; (après §3.1) : champ
    pré-rempli + refus immédiat sur mot-clé.

## 5. Pistes suivantes (optionnelles, hors périmètre de cette mise à jour)

- **Onglet Problèmes** (feuille des diagnostics du document) : le pont
  `setOnDiagnosticsPublishedListener` est désormais vivant pour les
  publications LSP — lot h3 côté CodeIDE.
- **Navigation inter-fichiers côté hôte** (`setOnNavigateToFileListener`)
  et **source décompilée** — gestes d'activité, pas de vue.
- **Couverture de `CodeActionService`** : familles de problèmes non
  couvertes (`default ->` du dispatch) — moteur, pas client.
- **`codeAction/resolve` serveur** : inutile tant que les edits partent
  complets ; utile si un jour les candidats deviennent nombreux (l'éditeur
  résout à l'application quand `resolveProvider` est annoncé).
- **`source.fixAll` serveur** : l'API client `performFixAll` est prête —
  il suffit de répondre l'action quand `only=["source.fixAll"]` arrive.
