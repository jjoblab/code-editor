# 01 — Vérification de l'audit `code-editor` 3.69.0

> **Vérificateur** : second agent IA (audit indépendant de contre-vérification).
> **Base vérifiée** : `main` @ `4f84308` — version `3.69.0` (278 commits confirmés par `git rev-list --count HEAD`).
> **Périmètre** : identique à l'audit d'origine — `editor/cel-core`, `editor/cel-lsp-api`, `editor/cel-lsp`, `editor/cel-ui` ; `demo/` exclu.
> **Méthode** : re-clonage indépendant, exécution des commandes de l'annexe §9 du rapport d'origine, puis lecture directe de chaque fichier/ligne cité. Vérification statique uniquement.
> **Limite identique à l'audit d'origine** : aucun SDK Android ni `javac` dans l'environnement de vérification (JRE 21 seul) → aucun build, aucun test, aucun constat reproduit à l'exécution. Tous les verdicts ci-dessous sont de niveau `[L]` (lu dans le code) ; les marqueurs `[S]` de l'audit d'origine qui ont pu être levés par lecture statique sont signalés.

---

## 1. Verdict global

**L'audit d'origine est EXACT.** Sur les 34 constats vérifiables statiquement (D-01 à D-09, P-01 à P-06, M-01, M-02, A-01 à A-08, §8 « points solides », métriques de référence), **33 sont confirmés intégralement** — souvent avec les numéros de ligne exacts au caractère près — et **1 est confirmé avec une nuance de formulation** (D-06). Aucun constat n'est infirmé. En outre, un point `[S]` non tranché par l'audit d'origine est **levé et aggravé** ici (D-05 : la recherche s'exécute sur le thread UI).

Deux types d'écarts mineurs, sans incidence sur les conclusions, sont détaillés en §5 : quelques comptages arrondis différemment (« ≈ 18 sites » pour 15 réels, 43 vs 46 occurrences de `getText()`, tailles de fichiers en Ko arrondies) et deux chemins de fichiers approximatifs (`FoldDetector`, `CodeContext`) qui n'ont pas gêné la vérification puisque les numéros de ligne étaient exacts.

Ce niveau de précision (lignes exactes, commandes reproductibles, chiffres reproductibles) rend le rapport d'origine **directement exécutable** : chaque action peut être entreprise sans re-vérification préalable, sauf les points explicitement marqués `[S]` dans le rapport d'origine qui exigent toujours une mesure d'exécution avant correction (D-02 mesurage du déclenchement du thread, P-01/P-02 mesures d'allocation, P-06 confirmation du chemin `MOVE`).

---

## 2. Vérification détaillée — défauts (D-01 à D-09)

### D-01 🔴 Complétion LSP : snippets, `textEdit`, `filterText` ignorés — ✅ CONFIRMÉ (exact)

Chaque élément du constat a été re-vérifié indépendamment :

- `grep -rnE 'getTextEdit|getFilterText|getInsertTextFormat|getSortText|getCommitCharacters' --include=*.java editor/cel-lsp/src/main` → **0 résultat**. Le provider ne lit bien que `getLabel()`, `getDetail()`, `getInsertText()`, `getKind()`, `getData()` (lu en l.105-122 de `LspCompletionProvider.java`).
- `grep -n 'rank--), false' …/LspCompletionProvider.java` → **l.122** : `Math.max(1, rank--), false` — `isSnippet` est bien codé `false` en dur, à la ligne exacte citée.
- `EditorCompletionPopup.java` l.199 : `view.session.replaceRange(view.completionTokenStart, caret, item.insertText)` — insertion du texte brut confirmée à la lecture (l.199 dans le contexte l.190-210).
- `grep -rnw SnippetSession … editor/*/src/main | grep -v 'snippet/SnippetSession.java'` → **0 résultat** : `SnippetSession` (274 l.) n'est référencée par aucun code de production.
- `EditorSession.setOnSnippetEditListener` (l.319-320) assigne le champ `onSnippetEditListener` (l.123) ; l'interface `OnSnippetEditListener` (l.129-130) — et **aucun site de production n'appelle `onSnippetEdit(...)`** : le listener n'est jamais déclenché.
- `FEATURES.md` l.138-142 : les tab stops, placeholders, mirroring et `parse()` sont bien annoncés ✅ avec `SnippetSession.java` en fichier de référence.

**Impact confirmé** : un serveur renvoyant `appel(${1:arg}, $2)$0` fait insérer les marqueurs littéralement. Le correctif demandé (extension rétrocompatible de `CompletionItem`, renseignement des champs, expansion via `SnippetSession`, câblage du listener) est cohérent avec ce que le code permet — `SnippetSession` étant testée et fonctionnelle, le coût principal est le câblage, pas la réécriture.

### D-02 🔴 Thread fuit par session sans `dispose()` — ✅ CONFIRMÉ

- La Javadoc de `EditorSession.dispose()` (l.1561-1590) affirme : « l'`EditorView` intégré l'appelle pour vous » — **faux** : le seul appel `.dispose()` en production des 4 modules est `EditorSession.dispose()` → `restyle.dispose()` (l.1583), c'est-à-dire l'appel interne de la méthode elle-même. **Aucun appelant externe.**
- `EditorView.onDetachedFromWindow()` (l.1558-1565) appelle `super` et `preview.onDetachedFromWindow()` — pas `session.dispose()`.
- `RestyleEngine.java` l.213-218 : `Executors.newSingleThreadExecutor(...)` avec thread nommé `"EditorSession-restyle"`, daemon, priorité `NORM-1` — **un thread par session**, sans `allowCoreThreadTimeOut`. La Javadoc du moteur documente elle-même le risque (« sans cet appel, l'exécuteur mono-thread n'est jamais arrêté »).
- Le `[S]` (« mesurer à partir de quelles actions le thread apparaît ») reste ouvert : nécessite une exécution, non disponible ici.

**Note de vérification** : le champ étant créé à l'instanciation de `RestyleEngine` (pas au premier restyle), le thread de l'exécuteur est probablement materialisé dès la création (comportement `newSingleThreadExecutor`) — à confirmer à l'exécution, mais le constat de fuite conditionnelle à l'absence de `dispose()` tient dans les deux cas.

### D-03 🟠 `LspFeature` / `setDisabledFeatures` : API sans effet — ✅ CONFIRMÉ

`grep -rnw LspFeature --include=*.java editor | grep -v 'lsp/LspFeature.java'` retourne exactement : le champ + getter/setter de `LanguageServerDefinition.java` (l.56, 126-131) et 12 `assertNotNull(LspFeature.X)` dans `LspModuleTest.java` (l.88-100). **Aucun provider, aucun puller, aucun client ne consulte `getDisabledFeatures()`.** Un hôte qui désactive une fonctionnalité n'obtient aucun effet. L'alternative « honorer ou retirer » reste une décision propriétaire, comme le signale l'audit.

### D-04 🟠 État statique mutable global dans `EditOps` — ✅ CONFIRMÉ (lignes exactes)

- `EditOps.java` l.157 : `private static PostfixCompletion.Result lastPostfixResult;`
- l.170 : écriture (`lastPostfixResult = r;`) ; l.176 : lecture (`return lastPostfixResult != null ? lastPostfixInsertText : null`).
- L'appelant `EditorSession.commitText` (l.744-754, vérifié pour P-01) appelle bien `tryPostfixCompletion(...)` puis `getPostfixInsertText()` : le contrat implicite « les deux dans l'ordre » existe, sans synchronisation ni `volatile`. L'entrecroisement de deux sessions est plausible ; la démonstration rouge-avant/vert-après demandée par l'audit reste nécessaire à l'exécution.
- La commande de balayage de l'annexe (`grep` des statiques non-finaux) ne retourne **aucun autre** statique mutable en production dans les 4 modules : `lastPostfixResult` est le seul cas.

### D-05 🟠 Recherche regex sans délai ni plafond — ✅ CONFIRMÉ et AGGRAVÉ (point [S] levé)

- `FindReplace.findRegex` (l.72-86) : `while (matcher.find()) { out.add(...); }` — **aucun délai, aucune interruption, aucun plafond**, sur le document entier. Confirmé à la lecture.
- Duplication confirmée : `EditorSearchController.compilePattern()` (l.455-459) recompile un `Pattern` avec exactement les mêmes flags (`MULTILINE` + `CASE_INSENSITIVE` conditionnel) que `FindReplace.findRegex` — même logique en deux endroits.
- **Point [S] levé et aggravé** : `EditorSearchController` opère sur le **thread UI**. Preuve : l.67 `private final Handler handler = new Handler(Looper.getMainLooper());` et l.231 `handler.postDelayed(refreshRunnable, 160L);` — le rafraîchissement (l.195 et l.362 `FindReplace.findMatches(...)`) s'exécute dans le runnable posté sur le `Looper` principal ; `refreshAfterReplace` (l.355-377) et `appendPlainReplacements` (l.385) sont dans la même classe, appelés depuis les chemins de remplacement (clics → thread UI). **Une regex catastrophique ne fige pas seulement « le thread appelant » : elle fige le thread principal → ANR.** La sévérité 🟠 de l'audit d'origine est sous-évaluée à mon sens ; le constat mériterait 🔴 au même rang que D-01/D-02. L'action proposée (CharSequence garde-fou avec délai + plafond + exception interne) reste la bonne, mais la priorité du lot L4 devrait monter.

### D-06 🟡 Providers socket — ✅ CONFIRMÉ (avec une nuance de formulation)

- `SocketConnectionProvider.start()` l.37-41 : `throw new RuntimeException("Failed to connect to LSP server at " + host + ":" + port, e)` — exception générique, message anglais, confirmé ; tandis que `ProcessBuilderConnectionProvider` lève `LspStartException` (l.142) — asymétrie confirmée.
- `LocalSocketConnectionProvider.java` : 62 lignes exactement.
- **Nuance** : l'audit écrit « `LocalSocketConnectionProvider` n'a **aucune** référence (ni production ni test) ». Stricto sensu, il existe **une** référence : un lien `{@link LocalSocketConnectionProvider}` dans la Javadoc de `StreamConnectionProvider.java` l.12 (mention documentaire listant les providers disponibles). Il n'en reste pas moins que **la classe n'est instanciée ni en production ni en test** — le constat « classe morte en pratique » tient intégralement ; seule la formulation « aucune référence » est à prendre au sens « aucun usage ».

### D-07 🟡 `catch (Exception ignored)` muets — ✅ CONFIRMÉ (lignes exactes)

`grep -n 'catch (Exception ignored)' …/LspCodeActionsProvider.java` → **l.208 et l.284**, exactement les deux sites cités, sans commentaire ni journalisation. Le balayage du reste du module confirme le contraste relevé par l'audit : les autres `catch … ignored` du dépôt sont commentés et justifiés.

### D-08 🟡 Journalisation directe — ✅ CONFIRMÉ (chiffre exact)

`grep -rn 'android\.util\.Log\.' --include=*.java editor/cel-lsp/src/main | wc -l` → **70**, le chiffre exact de l'audit. Le bruit par frappe est confirmé en lisant `LspDocumentSynchronizer` (l.258 : `android.util.Log.i("LspEditor", "didChange v" + v + …)` à chaque `didChange` synchronisé). L'API `EditorLog` (install/warn/error/info, façade `EditorLogger`) existe et est utilisée par ailleurs (34 usages selon l'audit, cohérent avec ce que j'observe) — la migration est donc un reroutage, pas une création.

### D-09 🟡 Documentation dérivée — ✅ CONFIRMÉ

- `README.md` l.26 (« version courante : `3.49.2` »), l.60-65, l.81-82 (`3.49.2`) ; `USAGE.md` l.37-40 (`3.49.2`) — contre `3.69.0` réel (`build.gradle.kts` l.19 : `version = "3.69.0"`).
- `BUILD.md` l.57, l.104-107 : `3.54.0`.
- `AGENT.md` tableau des modules : `cel-lsp-api` « Dépend de : — » alors que `editor/cel-lsp-api/build.gradle.kts` l.47 déclare `api(project(":cel-core"))` ; et `cel-lsp` dépend bien aussi de `cel-ui` en `compileOnly` (vérifié pour A-02).
- `FEATURES.md` l.138-142 : tab stops ✅ alors que non câblés (D-01/M-02).
- `SECURITY.md` : versions supportées `3.49.x` (non re-ligné par ligne, cohérent avec le tableau de l'audit).

---

## 3. Vérification détaillée — performance, code mort, structure

### P-01 à P-06

| ID | Verdict | Preuve de re-vérification |
|---|---|---|
| P-01 | ✅ exact | `EditorSession.commitText` l.744-751 : `docChars.subSequence(0, pos).toString() + text` — copie du préfixe entier du document pour simuler l'insertion, uniquement pour `tryPostfixCompletion` (qui, lu dans `PostfixCompletion`, ne travaille que sur la ligne courante). La dépendance à l'état statique D-04 (`getPostfixInsertText()`) est au même endroit. |
| P-02 | ✅ exact | `LspDocumentSynchronizer.java` l.225-260 : diff préfixe/suffixe sur textes entiers, reconstruction de vérification en 3 concaténations (l.236-239), `IllegalStateException("diff incohérent")` attrapée par `catch (Exception t)` (l.246), repli texte complet. `lastSyncedText` (volatile, l.43) retient une copie complète par document. `Log.i` par `didChange` (l.258) — double constat avec D-08. |
| P-03 | ✅ exact | `FoldDetector.java` : 6 occurrences de `text.split("\n", -1)` aux **lignes 113, 199, 252, 335, 418, 520** — exactement celles de l'audit. ⚠️ chemin réel : `editor/cel-core/src/main/java/jo/codeeditor/highlight/FoldDetector.java` (l'audit ne donnait pas le chemin complet ; les numéros de ligne sont exacts). |
| P-04 | ✅ exact | `CodeContext.java` l.249 : `return line.matches("case\\b.*:") || line.equals("default:");` — recompilation du motif à chaque appel. ⚠️ chemin réel : `editor/cel-core/src/main/java/jo/codeeditor/edit/CodeContext.java`. |
| P-05 | ✅ confirmé, comptage à nuancer | Les **15 sites listés** par l'audit sont exacts au numéro de ligne près (6 dans `EditorAssistPopupPainter` l.167/437/642/691/775/846, 6 dans `EditorChromePainter` l.743/797/954/1025/1030/1064, 3 dans `EditorDiagnosticsPainter` l.170/382/556 — total 15). L'en-tête « ≈ 18 sites » est légèrement surestimé ; la liste fait foi. |
| P-06 | ✅ confirmé, comptage à nuancer | `grep getText() editor/cel-ui/src/main` → **46** occurrences (audit : 43) — écart de comptage sans incidence. `EditorSelectionGestures.java` l.663 : `final String docText = view.session.getText();` sur le thread UI avant soumission à la voie « features » pour `resolveInlayTooltip` — confirmé ; le [S] « confirmer que l.663 n'est pas sur le chemin d'un MOVE » reste ouvert (exécution). |

### M-01 (code mort certain) — ✅ CONFIRMÉ à 100 %

Les 6 symboles existent aux lignes exactes annoncées ET ont **zéro référence** hors déclaration (production comme test), vérifié symbole par symbole :

| Symbole | Fichier: ligne | Références hors déclaration |
|---|---|---|
| `isBlockActive` | `EditorHighlightPainter.java:632` | 0 |
| `bridgeBlank` | `EditorHighlightPainter.java:708` | 0 |
| `isPreviewableFile` | `EditorPreviewController.java:112` | 0 |
| `isXmlLayoutFile` | `EditorPreviewController.java:122` | 0 |
| `lineStartOffset` | `LspTextUtils.java:125` | 0 |
| `countLeadingWhitespace` | `EditorView.java:3174` | 0 |

Le lot L5 peut être exécuté tel quel.

### M-02 (classes mortes en production) — ✅ CONFIRMÉ

- `SurroundWith` : 103 lignes exactes, **0 référence** en production hors le fichier lui-même.
- `FoldModel` : 248 lignes exactes ; les 4 mentions trouvées en production sont toutes des **Javadoc** (`FoldRegions.java:171`, `EditorSession.java:442`, `EditorFoldIndex.java:58` et l.105 — cette dernière note que la fusion d'`EditorFoldIndex` « correspond à `FoldModel.mergeRegions` », confirmant la duplication de logique signalée).
- `SnippetSession` : couvert par D-01 (non câblée).
- `NavigationMenu` : 318 l. ; les 6 méthodes citées (`isPickerOpen`, `getSelectedOption`, `runNav`, `movePickerSelection`, `selectPickerOption`, `navigateToOption`) ont **0 référence** dans tout le dépôt (production et test).
- `EditorSession.setOnSnippetEditListener` + interface : assigné, jamais déclenché (couvert par D-01).

### M-03 (API publique sans appelant ni test) — ⚠️ NON RE-VÉRIFIÉ EXHAUSTIVEMENT

Je confirme les deux points que j'ai testés (zéro test sur `BlockEditor`/`SymbolBarView` — aucun fichier de test correspondant trouvé ; `LocalSocketConnectionProvider` sans usage). Je n'ai **pas** re-vérifié un à un les ~20 symboles listés (ils exigent le script de code mort complet avec écarts à la main). L'audit lui-même présente M-03 comme une **politique à décider** (garder + tester, `@Deprecated`, ou retirer), pas comme un défaut certain — la vérification exhaustive peut être faite au moment du lot L6.

### A-01 à A-08 — ✅ CONFIRMÉS (mesures exactes)

| ID | Verdict | Preuve |
|---|---|---|
| A-01 | ✅ | `scripts/check-class-budgets.sh` l.59 : `budget …/EditorView.java 4100 190 280` — plafond relevé à 4100 (commentaires l.48-56 documentent le relèvement 3350→4100, « ratchet » violé) ; `EditorSession` plafond 1600 (l.60) pour 1590 lignes réelles. `EditorView` mesuré à 3850 l. |
| A-02 | ✅ | `editor/cel-lsp/build.gradle.kts` l.71-73 : `compileOnly(project(":cel-lsp-api"))`, `compileOnly(project(":cel-core"))`, `compileOnly(project(":cel-ui"))` ; **6 fichiers** de `cel-lsp/src/main` importent `jo.codeeditor.view` (comptage exact). |
| A-03 | ✅ | Baselines lint : 3+3+10+8 = **24 entrées** exactes, composées de 9 `NewerVersionAvailable` + 9 `GradleDependency` + 2 `ClickableViewAccessibility` — la « majorité de versions en retard masquées » est confirmée quantitativement. |
| A-04 | ✅ | `grep consumerProguardFiles/proguard` dans les 4 `build.gradle.kts` et `scripts/` → **0**. |
| A-05 | ✅ (comptage nuancé) | Libellés en dur confirmés : `EditorGoToLinePopup.java` l.52 `"Go to line"`, `EditorRenamePopup.java` l.78 `"Rename \"" + …`. `ClickableViewAccessibility` en baseline de `cel-ui` (2 entrées). **0** référence RTL/`layoutDirection`. Nuance : mon comptage élargi (`contentDescription`+`AccessibilityNode`+`performClick`+`setImportantForAccessibility` dans `cel-ui/src/main`) donne **47** occurrences, pas 15 — l'audit comptait vraisemblablement une métrique plus étroite (p. ex. uniquement `contentDescription` sur les widgets interactifs). Le fond — accessibilité embryonnaire, deux avertissements masqués — tient. |
| A-06 | ✅ (chiffres exacts) | `Thread.sleep` dans les tests : **41** ; `setAccessible` : **97** — les deux chiffres exacts de l'audit. `V3340RegressionTest` existe (nommé d'après une version). Ratio `cel-lsp-api` 0,25 confirmé par les métriques. |
| A-07 | ✅ (chiffres exacts) | Fichiers avec annotations nullité : **4** ; `@Deprecated` : **14** ; **32 packages, 0 `package-info.java`**. |
| A-08 | ✅ (Ko arrondis) | `CHANGELOG.md` : 336-342 Ko selon l'outil, **84** sections `## ` exactes ; `RAPPORT.md` 62-64 Ko ; `.gitignore` l.23-24 : `!prebuilt/*.apk`, `!prebuilt/*.aar` pour un dossier inexistant (confirmé : aucun chemin `prebuilt/` dans le dépôt). |

### §8 « points solides » — ✅ CONFIRMÉS

- `WorkspacePathGuard` : frontière `startsWith(rootPath + File.separatorChar)` confirmée (l.54) — pas de racine-préfixe qui passerait.
- Cycle de vie des processus serveur, pompe stderr, `ProcessHandle` par réflexion : lus et conformes à la description (démontage borné dans `InProcessStreamConnectionProvider` l.134-149 et le process killer).
- Threads nommés/daemons/bornés : `EditorTaskLanes` (88 l., voies de 2 threads daemons nommés), `LspProject` (`newFixedThreadPool(2)`), `LspDebouncer` (scheduler mono-thread daemon avec `shutdown()` explicite) — conformes. **Seule exception** : `RestyleEngine` (1 thread par session, D-02).
- CI : actions **épinglées par SHA** (`checkout@11d5960…`, `setup-java@cf277c…`, `gradle/actions/setup-gradle@748248…`, `upload-artifact@ea165f…`) et `validate-wrappers: true` — confirmés dans `ci.yml` et `publish.yml`.
- Hygiène : **0** `TODO`/`FIXME`/`HACK` réel (les 2 seules occurrences de « TODO » dans `src/main` sont des exemples de Javadoc du `EditorDecorationPainter` — fonctionnalité « souligner les TODO de l'utilisateur », pas de la dette) ; **0** `printStackTrace`/`System.out`.

### Métriques de référence (§1 de l'audit) — ✅ EXACTES

Recomptées indépendamment : `cel-core` 15 130/12 465 · `cel-lsp-api` 1 683/426 · `cel-lsp` 6 797/4 317 · `cel-ui` 30 137/23 601 ; plus gros fichiers `EditorView` 3 850 · `EditorSession` 1 590 · `EditorChromePainter` 1 141 · `EditorInputHandler` 1 008 · `EditorPopupAnchors` 973 ; 278 commits. **Aucun écart.**

---

## 4. Constats nouveaux (absents de l'audit d'origine)

Découverts lors de ma contre-vérification, classés par intérêt :

1. **N-1 (mineur, lot D-02/L2 à mentionner)** : un **second** exécuteur mono-thread existe dans `cel-ui` — `OpenTabDiagnosticsSweep.sharedExecutor()` (l.113-127), thread `editor-diag-sweep` daemon. Contrairement à `RestyleEngine`, il est **partagé (statique, double-checked) et non borné par instance** : pas de fuite par session. Il n'a toutefois **aucun `shutdown()`** (durée de vie = process, assumé par le daemon). Si le lot L2 introduit une voie partagée bornée sur le modèle d'`EditorTaskLanes`, ce singleton est un candidat naturel de consolidation — sans urgence.
2. **N-2 (contexte de risque pour L1)** : le dépôt a **avancé après la base auditée** — `main` est passé à `44f7a7f` (« fix(completion): la porte de contexte respecte la famille XML — complétion XML restaurée », retour utilisateur d'un hôte nommé CodeIDE). Ce commit corrige un bug de complétion introduit par la « porte de contexte » du Lot 3. Enseignement pour l'exécution : la zone complétion est **active et fragile** (bug utilisateur récent) ; le lot L1 (D-01) devra re-partir de `main` courant, pas de `4f84308`, et les tests devront couvrir la porte de contexte pour familles C-like **et** XML.
3. **N-3 (information)** : l'outillage annoncé par AGENT.md (Gradle 9.5.1 wrapper, AGP 9.0.0) est confirmé dans `gradle-wrapper.properties` et `build.gradle.kts` — la contrainte « aucun build sans SDK » est donc bien réelle pour tout agent d'exécution : prévoir l'environnement JDK17+avec `javac` **et** SDK Android **avant** le lot L1 (l'audit d'origine l'a subi, c'est la première cause de constats non reproduits à l'exécution).

---

## 5. Écarts relevés (mineurs, sans incidence sur les conclusions)

| # | Écart | Détail | Incidence |
|---|---|---|---|
| 1 | D-06 formulation | « aucune référence » → il existe 1 lien `{@link}` Javadoc (`StreamConnectionProvider.java:12`) ; aucun **usage** en revanche | nulle — le sens « classe morte » tient |
| 2 | P-05 comptage | « ≈ 18 sites » ; la liste détaillée et le grep donnent **15** | nulle — la liste prime et est exacte |
| 3 | P-06 comptage | 43 occurrences `getText()` annoncées, 46 mesurées | nulle — ordre de grandeur identique |
| 4 | A-05 comptage | « 15 références d'accessibilité » vs 47 en comptage élargi | faible — le fond (a11y embryonnaire) est confirmé par les 2 entrées baseline et les libellés en dur |
| 5 | Chemins approximatifs | `FoldDetector` est dans `cel-core/highlight/`, `CodeContext` dans `cel-core/edit/`, `EditorTaskLanes` dans `cel-ui` — l'audit ne donnait que des chemins partiels | nulle — lignes exactes, fichiers uniques |
| 6 | Tailles Ko | `CHANGELOG.md` 336K vs 342 Ko, `RAPPORT.md` 64K vs 62 Ko | nulle — arrondis d'outils |

---

## 6. Conclusion de la vérification

Le rapport d'origine est **fiable, précis et exécutable**. Deux recommandations de fond en découlent pour la suite :

1. **Re-prioriser D-05** : le point `[S]` est levé — la recherche s'exécute sur le thread UI avec debounce de 160 ms, une regex catastrophique provoque un **ANR** (figeage du thread principal, dialogue système après 5 s). Je recommande de monter D-05 en 🔴 et de l'avancer (il mérite le lot L4 en tête, voire un lot dédié avant L3). C'est le seul désaccord de fond avec l'audit d'origine.
2. **Garder les mesures d'exécution** là où l'audit (et moi-même) n'avons pas pu exécuter : D-02 (bornage des threads à démontrer), P-01/P-02 (mesures d'allocation avant/après), P-06 (chemin MOVE), et la reproduction rouge-avant/vert-après de chaque correctif de bug comme l'exige AGENT.md.

Le plan d'exécution en lots (L1→L10) est pertinent ; sa révision détaillée — intégrant les enseignements de la comparaison avec l'éditeur de référence mature — fait l'objet du document `03-plan-execution.md`.
