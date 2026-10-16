# Rapport — Quick actions et intelligence LSP de `code-editor`
## Référence : comment Android Studio (fond IntelliJ) les gère

> **ÉTAT DE LIVRAISON (2026-10-13)** : les 11 chantiers du plan (P0-1 →
> P2-11) sont livrés — P0-1 à P2-8 en `3.50.0`/`3.50.1` (branche
> `agent/actions-lsp-3.50.0`), P2-9 à P2-11 en `3.51.0`/`3.51.1`
> (branche `agent/actions-lsp-fin-3.51.0` ; la 3.51.0, frappée par
> l'incident JitPack 429, est re-publiée à l'identique en 3.51.1).
> Détail par version : voir le `CHANGELOG.md` de la racine. La montée côté consommateur (CodeIDE) est
> documentée dans `docs/MISE-A-JOUR-CODEIDE.md`. Résidus assumés :
> `semanticTokens` non annoncé dans les capacités clientes (aucun
> consommateur dans l'éditeur — règle « ne pas mentir », cf. P2-7) ;
> l'entrée `source.fixAll` de menu n'est pas exposée par défaut (aucun
> serveur Java actuel ne la propose — l'API `performFixAll` est prête).

> **Demande** : rechercher comment Android Studio gère les quick actions et les
> actions liées au LSP ; constat initial : « pas contexte aware, pas
> intelligent, beaucoup de fonctionnalités manquantes ». CodeIDE (dépôt du
> même propriétaire, seul consommateur) sert de référence d'usage ; le serveur
> LSP Java y vit dans le module vendored `lsp/java` (moteur ECJ in-process).
> **Livrable** : les modifications et améliorations à apporter **au dépôt
> `code-editor`** pour combler les manques.
>
> **Versions auditées** : `code-editor` branche `main` @ `32df5e3` (3.49.2
> publié sur JitPack) ; `CodeIDE` `main` @ `04a65ea` (v0.105.0).
> **Contraintes** (choisies par le propriétaire) : Java pur, minSdk 24, pas de
> rupture d'API publique, garde-fous verts, un commit par changement logique.
> Ce rapport vit **hors dépôt** (il nomme Android Studio, IntelliJ, LSP4J —
> interdits dans l'arbre par `AGENT.md`).

---

## 1. Résumé exécutif

**Verdict : le déficit d'intelligence n'est ni dans le moteur, ni côté
serveur — il est dans le pont `cel-lsp`/`cel-ui` de code-editor.** Le serveur
LSP Java de CodeIDE propose déjà des quick-fixes RÉELS et exacts (imports
candidats depuis l'index, enveloppe try-catch, insertion de `throws`, cast,
création de méthode — `CodeActionService.java`, 916 lignes, dispatch
ID-FIRST « jamais du message localisé »). Les actions arrivent jusqu'au
protocole… et **meurent dans code-editor** pour trois causes racines :

1. **R1 — Perte d'identité des diagnostics (la cause des quick-fixes
   absents)** : en reconstruisant le `CodeActionContext`, code-editor
   retransmet plage, message et sévérité mais **jette `code` et `data`** —
   précisément les deux champs sur lesquels le serveur de CodeIDE (et tout
   serveur de la famille Eclipse/JDT) keyed ses quick-fixes. Sans `code`, la
   réponse est **structurellement vide** : aucune ampoule, aucune correction,
   quelles que soient les qualités du serveur.
2. **R2 — Modèle de collecte « une requête LSP par ligne visible »** au lieu
   de la demande ponctuelle au caret (modèle Alt+Entrée d'IntelliJ/Android
   Studio) : coût O(lignes visibles) toutes les 800 ms, résultats périmés,
   actions rattachées à des lignes et non au caret/sélection, ampoules
   manquantes après défilement (le rafraîchissement n'est câblé que sur
   `onTextChanged`).
3. **R3 — Capacités client minimales** : pas de `codeAction` (littéral,
   `resolve`, `isPreferred`, `disabled`), pas de `workspace/applyEdit`
   (l'implémentation lsp4j par défaut **lève une exception**), pas de
   `workspace/executeCommand`, `prepareRename` absent, `triggerKind` toujours
   `Invoked`.

Le plan priorisé (§6) : **2 chantiers P0** (identité des diagnostics ;
remplacement du scan par ligne par une demande au caret — quick fixes et
refactoring en tête, conformément aux douleurs déclarées), **4 chantiers
P1**, **5 chantiers P2**. Tout est additif — l'API que CodeIDE consomme
(`LspProject.createEditor` / `setHost` / `connect` / `shutdown`) reste
strictement compatible.

---

## 2. Méthode et sources

L'audit a croisé quatre sources : (a) la **documentation officielle** de la
plateforme IntelliJ et de son intégration LSP (open-sourcée en 2026.1.4/2026.2
— l'API est « stable et éprouvée au combat » selon JetBrains) ; (b) la
**spécification LSP 3.17** (contrat exact de `textDocument/codeAction`,
`codeAction/resolve`, `workspace/applyEdit`, capacités clientes) ; (c) les
**sources publiques** vérifiées (lsp4j `LanguageClient` — comportement par
défaut de `applyEdit` confirmé par lecture du source) ; (d) **la lecture du
code** des deux dépôt au commit cité, chaque constat étant appuyé par
fichier:ligne. Le serveur LSP Java de CodeIDE a servi de **vérificateur
empirique** : sa réponse `codeAction` a été lue ligne par ligne pour
comprendre ce qui la vide.

| Source | Rôle |
|---|---|
| Blog JetBrains, « Open-Sourcing the LSP Client API in IntelliJ IDEA 2026.2 » (26/06/2026) | Statut de l'API LSP d'IntelliJ/Android Studio, renommage `LspServer`→`LspClient` |
| SDK Docs IntelliJ, « Language Server Protocol » (modifiée 24/07/2026) | **Mapping officiel LSP → concepts IDE** (quick-fixes, intentions, applyEdit…) |
| SDK Docs IntelliJ, « Code Inspections » | Architecture inspections/quick-fixes « on-the-fly » |
| Spécification LSP 3.17 (microsoft.github.io) | Contrat codeAction, kinds, data/resolve, isPreferred/disabled |
| Sources lsp4j (GitHub) | `LanguageClient.applyEdit` par défaut = `UnsupportedOperationException` |
| `code-editor` @ `32df5e3`, `CodeIDE` @ `04a65ea` + `docs/LSP_JAVA.md` | Constats fichier:ligne, câblage réel |

---

## 3. Le modèle de référence — comment Android Studio gère les quick actions

Android Studio est un IDE **fondé sur la plateforme IntelliJ** ; ses « quick
actions » suivent le modèle IntelliJ, que l'intégration LSP open-source
**réutilise à l'identique** au lieu d'inventer une UI parallèle.

### 3.1 Le triptyque inspections / intentions / Alt+Entrée

Le modèle IntelliJ sépare nettement trois choses que code-editor fusionne :

1. **La détection des problèmes (les inspections).** Des `LocalInspectionTool`
   parcourent le modèle sémantique (PSI) **pendant la frappe**, en tâche de
   fond (le *daemon* d'analyse par passes). Chaque problème devient un
   `ProblemDescriptor` : une **plage de texte** + une sévérité + un
   `LocalQuickFix` **attaché au problème**. La documentation officielle
   résume : une inspection fournit « un visiteur pour parcourir l'arbre,
   une classe `LocalQuickFix` pour corriger un problème identifié
   (optionnel) ». Le fix est invoqué « sur le `PsiElement` (ou la
   `TextRange`) mis en évidence par l'inspection ».
2. **Les intentions (actions sans diagnostic).** Des `IntentionAction`
   déclarées au niveau plateforme/plugin, évaluées **au caret** :
   `isAvailable(project, editor, file)` décide en fonction du contexte
   sémantique (symbole sous le curseur, type de construction…). « Les
   corrections d'inspection sont affichées à l'utilisateur **comme des
   intentions** » — même menu, même raccourci.
3. **La collecte à la demande (Alt+Entrée).** Une seule action,
   `ShowIntentionActionsAction`, déclenchée par l'utilisateur : elle
   **fusionne** à la position du caret (a) les fixes des problèmes dont la
   plage couvre le caret, (b) les intentions disponibles, (c) les assists ;
   elle classe (fix du problème le plus sévère en tête, action *préférée*
   d'abord, actions secondaires regroupées sous « plus d'actions ») et ouvre
   le popup.

**Conséquences structurelles de ce modèle** (c'est là que code-editor
diverge) :

- **Aucune requête n'est émise pour dessiner les indicateurs** : la
  visibilité de l'ampoule découle des diagnostics (les passes du daemon), le
  *contenu* du popup n'est calculé qu'**au moment de l'invocation**, en une
  seule passe, au caret.
- Les fixes sont **bornés à une plage** (celle du problème), pas à une ligne
  entière ; un diagnostic multi-lignes reste rattaché au caret tant que le
  caret est dans sa plage.
- Les refactorings « extract » travaillent sur la **sélection** quand elle
  est non vide.

### 3.2 Le mapping LSP officiel de la plateforme IntelliJ

L'intégration LSP de la plateforme (API publique `com.intellij.modules.lsp`,
2023.2+, **open-sourcée** en 2026.1.4/2026.2 et donc désormais disponible
pour Android Studio — JetBrains « travaille avec l'équipe Google » pour que
ce soit livré avant 2026.2) **mappe le protocole sur ces mêmes concepts**,
progressivement :

| LSP | Concept IntelliJ/Android Studio | Depuis |
|---|---|---|
| `textDocument/publishDiagnostics` | Surlignage erreurs/avertissements + marqueurs de gouttière | 2023.2 |
| `textDocument/codeAction` | **Quick-fixes pour erreurs et avertissements** | 2023.2 |
| `textDocument/codeAction` | **Intention actions** (mêmes actions, entrée « intention » du menu Alt+Entrée) | 2023.3 |
| `workspace/applyEdit` | Application d'un `WorkspaceEdit` poussé par le serveur | 2023.3 |
| `textDocument/codeAction` + `source.organizeImports` | « Optimize Imports » | récent (IJPL-198789) |
| `textDocument/rename` | Renommage refactor (avec filets d'annonce) | 2026.1.1 |
| `textDocument/diagnostic` (pull) | Diagnostics tirés à la demande | 2025.1+ |
| `codeAction/resolve`, `completionItem/resolve` | Résolution différée | 2024.x+ |

Deux enseignements directs pour code-editor : (a) **une seule requête
`codeAction` alimente DEUX concepts UI** (fixes + intentions) ; (b) la
plateforme **implémente `workspace/applyEdit` côté client** — c'est un
prérequis, pas une option, pour les serveurs qui appliquent leurs
refactorings en deux temps (commande → edit poussé).

### 3.3 Le contrat LSP que tout client « intelligent » respecte (spec 3.17)

- `CodeActionContext.diagnostics` : « la liste des diagnostics dont le client
  a connaissance **pour la plage demandée** » — le serveur s'appuie sur
  l'**identité** de ces diagnostics (`code`, `data`, plage, sévérité) pour
  proposer les fixes ; la spec prévoit explicitement `data` « préservé entre
  un `textDocument/codeAction` et un `codeAction/resolve` ».
- `CodeActionKind` : hiérarchie ouverte (`quickfix.*`, `refactor.extract`,
  `refactor.inline`, `refactor.rewrite`, `source`, `source.organizeImports`,
  `source.fixAll`) ; le client **annonce les kinds qu'il supporte à
  l'initialisation** et peut filtrer via `context.only`.
- Client capabilities `codeAction.codeActionLiteralSupport`,
  `dataSupport`, `resolveSupport.properties`, `isPreferredSupport`,
  `disabledSupport` ; serveur capability `codeActionProvider` avec
  `resolveProvider`.
- `CodeAction.isPreferred` (le fix que le client met en tête / applique en
  premier), `CodeAction.disabled.reason` (action affichée mais expliquée),
  `Command` (exécution via `workspace/executeCommand`).

VS Code — le client LSP de référence — matérialise ce contrat : ampoule au
caret, une requête au caret (ou à la sélection), `isPreferred` → première
entrée, actions `disabled` affichées grisées avec leur raison, `data`
transporté.

---

## 4. Diagnostic de code-editor — constats vérifiés (fichier:ligne)

### 4.1 Ce qui existe déjà et fonctionne (à préserver)

- **Traduction SPI↔LSP quasi complète côté requêtes** : complétion (avec
  flush du didChange et capture de fraîcheur `versionAtRequest`),
  hover/quick-doc, signature help, définition/typeDefinition/implémentation/
  super, références, documentHighlight (débouncé 400 ms), symboles, folding,
  semantic tokens (tirés à chaque publication de diagnostics), inlay hints,
  formateur, rename **multi-fichiers** avec réécriture disque confinée,
  tout-ou-rien et écriture atomique (`LspRenameProvider` + `WorkspaceDiskEdits`
  — Lots 1-2 durcis).
- **Déclencheurs clavier** : `Ctrl+.` (`CODE_ACTIONS`, n'ouvre que si la
  ligne du caret a des actions), `Ctrl+Shift+L`
  (`CODE_ACTIONS_AT_CARET`, ouvre toujours) — `EditorKeyHandler.java:287-310` ;
  tap sur la bande de gouttière d'une ligne à diagnostic (`EditorTapResolver`
  .java:241-244) ; popup d'actions dessiné dans le canevas avec navigation
  clavier (`EditorAssistPopupPainter`), icônes **engrenage (quick fix) vs
  ampoule (intention)** déjà distinguées dans le chrome.
- **Verrouillage des lignes non diagnostiquées** : l'ampoule est réservée
  aux lignes portant un diagnostic Error/Warning (`EditorDiagnosticsLocator`
  .java:21-22, 104-105) — la bonne intuition d'IntelliJ.
- **Garde de fraîcheur** des éditions appliquées (`applyWorkspaceEdit(edit,
  versionAtRequest)`) — refus propre si l'utilisateur a frappé entre-temps.

### 4.2 Les causes racines

**R1 (bloquante) — identité des diagnostics jetée en route.**
`LspCodeActionsProvider.codeActions()` (l. 53-73) reconstruit chaque
diagnostic du contexte avec `setRange`, `setMessage`, `setSeverity` — et
**rien d'autre** : ni `setCode`, ni `setData`, ni `setTags`, ni `setSource`.
Or côté serveur CodeIDE : `JavaTextDocumentService.codeAction` lit
`lspDiag.getCode()` (l. 705) et `lspDiag.getData()` (l. 719) —
`extractDiagnosticArgs(data)` récupère les **arguments structurés du
compilateur** (`IProblem.getArguments()`, transportés dans `data` par
`toLspDiagnostic` l. 997) — puis `CodeActionService.actionsFor` **retourne
immédiatement une liste vide si `diagnostic.code() == null`** (l. 79) et
dérive ses fixes exacts (FQN d'import, type du cast) des arguments. Le
modèle interne `Diagnostic` de cel-lsp-api transporte déjà `code`
(`LspDiagnosticsManager` l. 44-45 le capture) mais **pas `data`** : la perte
est donc double — `code` capturé puis non retransmis, `data` jamais capturé.
*Résultat observable dans CodeIDE : zéro quick-fix, pour tout fichier, en
permanence — « Organiser les imports » (déclenché par kinds, sans besoin
d'identité) restant la seule action visible.*

**R2 — collecte par ligne au lieu du caret.**
`EditorCodeActionsPopup.refreshCodeActions` (l. 38-71) exécute **une requête
LSP `codeAction` par ligne visible** (« Le scan exige UNE requête LSP PAR
ligne visible (3 s de timeout chacune) »), débouncée 800 ms
(`EditorLanguageBridge.scheduleCodeActionsRefresh`). Une fois et demi à deux
écrans de texte = 60-120 requêtes par cycle — contre **une** requête au
caret dans le modèle IntelliJ/VS Code. Conséquences : charge serveur
(typiquement 60× la demande réelle), latence d'affichage (jusqu'à 800 ms +
3 s), actions **rattachées à la ligne** (`params.setRange` = ligne entière,
l. 42-44) et non au mot/sélection — les refactorings `refactor.extract` ne
peuvent pas fonctionner puisqu'ils requièrent la **sélection** ; et enfin le
rafraîchissement n'est câblé **que sur `onTextChanged`**
(`EditorView.java:2312`, unique site d'appel) : après un défilement, les
ampoules des lignes nouvellement visibles sont absentes ou périmées (le
commentaire de `EditorLanguageBridge.java:490` évoque scroll/sélection, mais
aucun appel ne le réalise).

**R3 — capacités clientes minimales + requêtes serveur→client non
implémentées.** `LanguageServerWrapper` (l. 93-129) déclare workspace +
complétion + hover + signatureHelp + publishDiagnostics.relatedInformation
uniquement : **pas de `codeAction` capabilities** (littéral, `data`,
`resolve`, `isPreferred`, `disabled`), pas de `rename.prepareSupport`, pas
de semanticTokens/foldingRange/inlayHint côté client. Et
`DefaultLanguageClient` **n'implémente pas `applyEdit`** — le source lsp4j
est formel : l'implémentation par défaut **lève
`UnsupportedOperationException`** ; tout serveur appliquant ses éditions via
`workspace/applyEdit` reçoit une erreur JSON-RPC.

**R4 — `Command` sans edit = no-op silencieux.**
`LspCodeActionsProvider` (l. 110-124) : une action `Either.forLeft(Command)`
capture `editToApply = null` ; l'`apply` journalise « has no edit — nothing
to apply ». Aucun `workspace/executeCommand` n'est jamais émis — tous les
serveurs à commandes (vastitude de l'écosystème LSP) voient leurs actions
affichées puis inertes.

**R5 — `codeAction/resolve`, `isPreferred`, `disabled` ignorés.** Le
`WorkspaceEdit` est lu immédiatement (l. 117) ; une action non résolue
(édit différé via `data` + `codeAction/resolve`) est donc **muette**. Pas de
tri par `isPreferred`, pas d'affichage des actions désactivées avec leur
raison — alors que le modèle `CodeAction` du SPI (`cel-lsp-api`) transporte
déjà un champ booléen et que l'UI sait dessiner une entrée grise.

**R6 — filtrage des diagnostics par ligne de départ.**
`LspCodeActionsProvider` l. 57-58 : `if (diagPos.getLine() == line)` — un
diagnostic **commençant sur la ligne N-2** et couvrant le caret (expression
multi-lignes, méthode non gérée) n'est jamais transmis ; un diagnostic ne
saisit que par sa ligne de **début**.

**R7 — pré-renommage absent.** Pas de `textDocument/prepareRename` (ni
provider ni capability) : le popup de rename s'ouvre sans savoir si le caret
est sur un identifiant renommable, sans placeholder ; l'échec n'apparaît
qu'après la requête (timeout 10 s au pire).

**R8 — complétion : contexte de déclenchement figé.**
`LspCompletionProvider` l. 62 : `triggerKind = Invoked` en permanence — le
caractère tapé n'est jamais passé en `TriggerCharacter`, alors que
`getTriggerCharacters()` (l. 148) connaît la liste serveur et que
`EditorCompletionPopup` (l. 276) la consulte pour l'auto-popup. Les serveurs
qui adaptent leur filtrage au caractère déclencheur sont dégradés.

**R9 — tags des diagnostics non exploités.** `Unnecessary`/`Deprecated` (le
« grisé » des éléments inutilisés d'Android Studio) ne sont ni capturés ni
rendus.

*(Mineurs, notés pour mémoire : sévérité LSP absente mappée sur
« Warning » par défaut dans `LspDiagnosticsManager` l. 40-41 ; double
publication des diagnostics par `EditorDiagnosticsPusher` (600 ms) qui
relit le cache déjà poussé par `LspDiagnosticsManager` — redondance
inoffensive mais source de confusion.)*

### 4.3 Le point CodeIDE (contrainte de compatibilité)

CodeIDE consomme exactement : `LspProject(root).addServerDefinition(…)`,
`createEditor("file://…")`, `setHost(hôte)`, `vue.setCurrentFilePath`,
`connect()/disconnect()`, `shutdown()` (`CoordonnateurLsp.kt`,
`SessionLsp.kt`). Toute l'intelligence passe par l'UI de code-editor. Les
propositions ci-dessous sont donc **additives** : nouvelles méthodes avec
implémentation par défaut déléguant à l'existant, champs de modèle ajoutés
par constructeurs de compatibilité — aucun appel actuel de CodeIDE ne peut
casser (vérifié : `cel-lsp`/`cel-core` inchangés entre 3.41.0 et 3.49.2
selon l'audit de montée de version de CodeIDE, §16.4).

---

## 5. Tableau d'écart récapitulatif

| Concept Android Studio / IntelliJ | Attente LSP correspondante | code-editor aujourd'hui | Écart |
|---|---|---|---|
| Détection des problèmes (inspections, daemon) | `publishDiagnostics` push | Push reçu, converti, soulignés + gouttière | ✔ (code/data jetés en route) |
| Ampoule visible = f(diagnostics) | dérivation locale | réservée aux lignes à diagnostic ✔, **mais** alimentée par un scan `codeAction` par ligne | ✖ modèle inversé |
| Contenu du menu calculé **à l'invocation, au caret** | 1 requête `codeAction` plage caret/sélection | 1 requête **par ligne visible**, plage = ligne entière, 800 ms | ✖✖ |
| Fixes attachés à la **plage du problème** | diagnostics intersectant la plage | filtre « ligne de départ du diagnostic » | ✖ |
| Refactor extract sur **sélection** | `range` = sélection non vide | jamais la sélection | ✖ |
| Alt+Entrée unique fusionnant fixes + intentions | `codeAction` (kinds quickfix+refactor+source) | Ctrl+. / Ctrl+Shift+L + tap gouttière (clavier présent) | ◐ |
| Identité des problèmes transportée | `code` + `data` dans le contexte | **jetés** | ✖✖ (R1) |
| Action *préférée* en tête, actions désactivées expliquées | `isPreferred`, `disabled.reason` | ignorés | ✖ |
| Résolution différée des actions | `codeAction/resolve` (+ capability) | jamais appelé | ✖ |
| Éditions poussées par le serveur | `workspace/applyEdit` | non implémenté (lsp4j : exception) | ✖ |
| Actions à commande | `workspace/executeCommand` | no-op silencieuse | ✖ |
| Renommage validé au caret | `prepareRename` | absent | ✖ |
| Complétion contextuelle par caractère | `triggerKind = TriggerCharacter` | toujours `Invoked` | ◐ |
| Optimize Imports | kind `source.organizeImports` | reçu (seule action visible aujourd'hui) mais pollué par ligne | ◐ |
| Grisé des éléments inutilisés | tags `Unnecessary` | non capturés | ✖ (cosmétique) |

---

## 6. Plan de modifications priorisées pour `code-editor`

> Conventions respectées : Java pur, minSdk 24 (`IoCompat` pour toute E/S),
> **aucune rupture d'API publique** (ajouts + surcharges avec implémentation
> par défaut), un commit par changement logique (`fix(lsp)` / `feat(ui)` /
> `test(...)`), CHANGELOG en français, garde-fous
> `check-external-refs.sh` et budgets de classes laissés verts (les
> chantiers ci-dessous ajoutent ≤ 300 lignes à `EditorView` : sous le plafond
> ratchet 3 350 ; `LspCodeActionsProvider` et `DefaultLanguageClient` ne
> sont pas plafonnés).
> Chaque chantier inclut son test **rouge avant / vert après** (Jupiter JVM
> pour `cel-lsp` — le harnais de faux serveur du Lot 2/3 se réutilise ;
> Robolectric pour `cel-ui`).

### P0-1 — Transporter l'identité des diagnostics (code, data, tags)
**Priorité : maximale — débloque tous les quick-fixes de CodeIDE.**
**Effort : S (2 commits). Dépendances : aucune.**

- `cel-lsp-api` `Diagnostic` : ajouter les champs `Object data` (payload
  opaque — jamais interprété côté client), `List<String> tags`,
  `String source` **via de nouveaux constructeurs** ; l'ancien constructeur
  4-arguments est conservé et délègue (compat binaire).
- `LspDiagnosticsManager` (l. 37-46) : capturer `getCode()` (déjà lu),
  `getData()`, `getTags()`, `getSource()` du diagnostic LSP poussé.
- `LspCodeActionsProvider` (l. 59-71) : reconstruire le diagnostic LSP du
  contexte **fidèlement** : `setCode`, `setData`, `setSource`, `setTags`,
  `setSeverity` (le round-trip sévérité est déjà correct).
- **Test rouge→vert** (JVM, faux serveur) : un faux `LanguageServer` dont
  `codeAction` ne propose un fix **que si** le diagnostic reçu porte
  `code = "famille:1"` et `data = ["Foo", "bar"]` — avant : 0 action ;
  après : 1 action avec son edit. Test jumeau côté modèle : la publication
  `publishDiagnostics` conserve code/data/tags.
- **Risque** : nul — le payload est opaque pour le client, les serveurs qui
  l'ignorent l'ignorent toujours.

### P0-2 — La demande au caret (modèle Alt+Entrée) à la place du scan par ligne
**Priorité : maximale — c'est « context aware » au sens d'Android Studio.**
**Effort : M (3-4 commits : SPI, pont LSP, UI). Dépendances : aucune (se
combine idéalement avec P1-5).**

1. **SPI** (`cel-lsp-api` `CodeActionsProvider`) : nouvelle méthode
   `List<CodeAction> codeActions(CharSequence text, int caretOffset,
   int selStart, int selEnd, List<String> onlyKinds)` avec implémentation
   par défaut déléguant à l'ancienne `codeActions(text, line)` — aucune
   rupture.
2. **Pont** (`LspCodeActionsProvider`) : plage = **sélection non vide**, sinon
   **mot sous le caret**, sinon ligne du caret ; `context.diagnostics` =
   diagnostics **intersectant la plage** (utiliser le modèle enrichi P0-1) ;
   `only = null` (tous les kinds) pour l'invocation Alt+Entrée, ou les kinds
   demandés ; une **seule** requête, `triggerKind` d'invocation.
3. **UI** (`EditorView`/`EditorCodeActionsPopup`/`EditorKeyHandler`) :
   - `Ctrl+.` et `Ctrl+Shift+L` → résolution au caret (asynchrone, lane
     « features », génération) puis popup — le contenu devient
     **fraîchement calculé à l'invocation**, comme Alt+Entrée ;
   - l'ampoule de gouttière reste pilotée par les **diagnostics**
     (`lineHasDiagnostic`, déjà en place) ; le **tap sur l'ampoule** ouvre le
     popup via la même résolution au caret de la ligne ;
   - `refreshCodeActions(first,last)` (le scan par ligne) est **retiré** du
     chemin d'édition — remplacé par l'invalidation du cache à la
     publication de diagnostics ; `scheduleCodeActionsRefresh` supprimé ou
     réduit à l'invalidation.
   - le popup classe : actions `quickfix.*` d'abord (icône engrenage),
     `refactor.*`/`source.*` ensuite (icône ampoule) — les peintres
     existants distinguent déjà les deux icônes.
- **Tests** : rouge — l'ancien comportement émet N requêtes pour N lignes
  visibles (compteur sur le faux serveur : le test échoue à ≥ 2 requêtes) ;
  vert — exactement 1 requête par invocation, avec la plage attendue
  (sélection puis mot puis ligne) ; diagnostic multi-lignes couvrant le
  caret → transmis au contexte.
- **Gains mesurables** : requêtes `codeAction` ×(lignes visibles) → 1 ;
  ampoules immédiates à l'arrivée des diagnostics (plus de fenêtre 800 ms) ;
  ampoules correctes après scroll (elles ne dépendent plus du scan) ; le
  terrain `refactor.extract` (sélection) devient protocolairement possible.
- **Risque** : changement de comportement UX (actions demandées, plus
  pré-calculées) — c'est le comportement cible ; garder `Ctrl+Shift+L` «
  ouvre toujours » comme filet.

### P1-3 — `workspace/applyEdit` + `workspace/executeCommand`
**Effort : M (2 commits). Dépendances : P0-1 utile, pas requise.**

- `DefaultLanguageClient` : implémenter `applyEdit(ApplyWorkspaceEditParams)`
  → router vers `LspWorkspaceEditApplier`/`WorkspaceDiskEdits` (la voie
  confinée du Lot 1 : validation, frontière de workspace, écriture
  atomique) ; répondre `ApplyWorkspaceEditResponse(applied=false,
  failureReason=…)` en cas de refus — jamais d'exception.
- `LanguageServerWrapper` : `WorkspaceClientCapabilities.setApplyEdit(TRUE)`
  (+ `setExecuteCommand(TRUE)` si branché).
- `LspCodeActionsProvider` : quand l'action est un `Command`, `apply` émet
  `workspace/executeCommand` (nouvelle méthode de service à ajouter au pont
  — `workspaceService().executeCommand(params)`) ; si la réponse porte un
  `WorkspaceEdit` (soit en valeur, soit via le futur `applyEdit`), il est
  appliqué par la voie commune ; sinon journal + rejet propre.
- **Tests** : faux serveur renvoyant une action `Command` → avant : no-op
  (test rouge : aucune requête executeCommand reçue) ; après : requête reçue
  et edit appliqué ; `applyEdit` serveur→client : avant exception JSON-RPC,
  après edit confiné appliqué ou refus motivé.

### P1-4 — `codeAction/resolve`, `isPreferred`, `disabled`
**Effort : M. Valeur écosystème (CodeIDE : `resolveProvider=false` — sans
effet immédiat). Dépendances : P0-1 (transport du `data`).**

- `LanguageServerWrapper` : déclarer `CodeActionCapabilities`
  (`codeActionLiteralSupport` + valueSet de kinds, `dataSupport`,
  `resolveSupport: ["edit"]`, `isPreferredSupport`, `disabledSupport`).
- `LspCodeActionsProvider` : si la capability serveur `resolveProvider` est
  vraie et que l'action n'a pas d'edit : conserver `data`, **résoudre à
  l'application** (`codeAction/resolve`) puis appliquer l'edit obtenu.
- Modèle `CodeAction` (cel-lsp-api) : champs `preferred` (bool) et
  `disabledReason` (String) ajoutés par constructeur de compat ; UI : tri
  `isPreferred` en tête ; entrée désactivée dessinée grisée (le peintre
  gère déjà des états).

### P1-5 — Plage intelligente & diagnostics intersectants
**Effort : S. (Absorbé dans P0-2 si mené ensemble.)**
Réutiliser l'utilitaire de mot sous le caret du documentHighlight ; le
filtre `diagPos.getLine() == line` (R6) devient **intersection de plages**
(`d.start <= rangeEnd && d.end >= rangeStart`). Test : diagnostic couvrant
l. 10-14, caret l. 12 → transmis.

### P1-6 — Rafraîchir l'état « actions » au bon moment
**Effort : S. Dépendances : P0-2.**
Avec le scan supprimé, il reste à : invalider le cache d'ampoules à chaque
`publishDiagnostics` (les lignes diagnostiquées changent) ; reconstruire la
gouttière après un défilement arrêté (le hook « grands changements de
scroll » existe déjà pour le wrap/rendu). Test Robolectric : publication →
ampoule visible à la frame suivante sans frappe.

### P2-7 — Annoncer les capacités clientes complètes
**Effort : S (déclaratif pur). Aucun comportement.**
`LanguageServerWrapper.initialize` : compléter `TextDocumentClientCapabilities`
— `rename.prepareSupport`, `publishDiagnostics.tagSupport` +
`codeDescriptionSupport` + `dataSupport`, `semanticTokens`, `foldingRange`,
`inlayHint`, `signatureHelp.contextSupport`, `completion.completionItem`
(`snippetSupport` = **false** à moins d'ajouter un moteur de snippets — ne
pas mentir), `documentSymbol.hierarchicalDocumentSymbolSupport`… Test :
l'InitializeParams envoyé contient chaque déclaration (le faux serveur
l'inspecte).

### P2-8 — `prepareRename` avant le popup
**Effort : M. Coût coordonné : CodeIDE n'annonce pas `prepareSupport` non
plus — amélioration des deux côtés, à acter.**
Nouveau `LspPrepareRenameProvider` (capability `renameProvider` soit bool
soit options) + SPI `RenameProvider.prepareRename(text, offset)` retournant
placeholder/validité ; `EditorRenamePopup` : placeholder proposé, refus
propre (« pas un identifiant renommable ») sans attendre 10 s. Test :
caret sur un littéral chaîne → popup refusé immédiatement.

### P2-9 — `triggerKind` réel pour la complétion
**Effort : S.**
`EditorCompletionPopup` → `LspCompletionProvider` : passer le caractère
déclencheur ; `CompletionContext` = `TriggerCharacter` + caractère quand le
popup est auto-déclenché par celui-ci, `Invoked` sinon. Test : faux serveur
assertion sur le contexte reçu.

### P2-10 — Actions « source » dédiées (organize imports, fix all)
**Effort : S-M.**
Une action d'hôte (menu contextuel / menu nav) sollicitant
`codeActions(…, only=["source.organizeImports"])` **une fois par document**
(plus une entrée `source.fixAll` si le serveur l'annonce) — retire la
pollution « Organiser les imports » répétée par ligne que le scan actuel
produit. Test : requête émise avec le bon `only` ; une seule fois.

### P2-11 — Peaufinages cosmétiques alignés sur Android Studio
**Effort : S chacun, optionnels.**
Tags `Unnecessary` → peinture estompée de la plage (comme le grisé AS) ;
sévérité absente → `Information` (pas `Warning`) ; fusion
`EditorDiagnosticsPusher`/`LspDiagnosticsManager` (une seule voie de
publication).

### Ordre de livraison suggéré (jalons)

| Jalon | Contenu | Commits |
|---|---|---|
| Q1 | P0-1 | `feat(api)`, `fix(lsp)` + tests |
| Q2 | P0-2 (+P1-5) | `feat(api)`, `fix(lsp)`, `refactor(ui)` + tests |
| Q3 | P1-3, P1-6 | `fix(lsp)`, `fix(ui)` |
| Q4 | P1-4, P2-7 | `feat(lsp)`, `fix(lsp)` |
| Q5 | P2-8 → P2-11 | au fil |

À chaque jalon : `./gradlew clean assembleDebug testDebugUnitTest lint` +
garde-fous (conforme au § 7 du prompt initial), CHANGELOG enrichi, et
validation sur appareil via CodeIDE (protocole « LSP branché » existant :
barre d'indexation verte + journal, puis épreuve directe — *ampoule sur une
ligne en erreur, tap, quick-fix appliqué*).

---

## 7. Ce qui reste hors de `code-editor` (périmètre CodeIDE / moteur)

Pour être honnête avec le constat « beaucoup de fonctionnalités sont
manquantes », trois familles ne relèvent **pas** du dépôt éditeur : (a)
l'**onglet Problèmes** (la feuille des diagnostics du document — lot h3
explicitement reporté côté CodeIDE, le pont `setOnDiagnosticsPublishedListener`
existant côté cel-lsp) ; (b) la **navigation inter-fichiers** côté hôte
(`setOnNavigateToFileListener` — le saut vers un autre fichier est un geste
d'activité, pas de vue) et la **source décompilée** ; (c) les quick-fixes
eux-mêmes côté moteur (la couverture du `CodeActionService` de CodeIDE :
famille de problèmes traitées). Le présent rapport garantit que tout ce que
le serveur propose **arrive désormais à l'écran et s'applique**.

---

## 8. Conclusion

L'écart ressenti (« pas contexte aware, pas intelligent ») se décompose
précisément : **une perte de données unique (R1) tue les quick-fixes ; un
modèle de collecte unique (R2) les déracine du caret et les coûte 60× trop
cher ; un jeu de capacités client incomplet (R3-R9) ferme les voies
protocole standard**. Aucun de ces trois chantiers ne demande de refonte
architecturale : le pont cel-lsp est déjà solide (fraîcheur des versions,
confinement disque, atomique), l'UI possède déjà les primitives visuelles
(ampoule/engrenage, popup, tri) et le serveur de CodeIDE propose déjà des
fixes exacts. **Deux chantiers P0, mesurables, livrés en une poignée de
commits conventionnés, transforment le ressenti** — et posent le socle
standard (applyEdit, resolve, prepareRename) sur lequel tout serveur LSP
future — y compris les montées du moteur CodeIDE — se branchera sans
réinventer le client.
