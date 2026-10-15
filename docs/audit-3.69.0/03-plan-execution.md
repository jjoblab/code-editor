# 03 — Plan d'exécution consolidé (vérification + comparaison intégrées)

> **Objet** : plan de travail prêt à l'exécution, héritier du §7 de l'audit d'origine (lots L1→L10), amendé par (a) la contre-vérification du document `01-verification-audit.md` (tout confirmé, D-05 aggravé) et (b) les enseignements de l'étude comparative `02-etude-comparative.md`.
> **Statut** : ce document est une **proposition d'ordre et de contenu** ; le propriétaire décide. Les points 🧑‍⚖️ exigent sa décision explicite avant exécution.
> **Base de départ** : re-partir de `main` **courant** (à la rédaction : `44f7a7f`, un commit après la base d'audit — fix complétion XML ; voir N-2 du document 01), pas de `4f84308`, pour ne pas régresser ce correctif.

---

## 0. Prérequis d'environnement (bloquants, à préparer avant L1)

L'audit d'origine et ma contre-vérification ont tous deux été **empêchés d'exécuter** faute d'environnement. Avant tout lot :

1. **JDK 17+ avec `javac`** (un JRE headless fait échouer AGP — documenté dans `AGENT.md` ; mon environnement de vérification n'avait qu'un JRE 21 sans `javac`).
2. **SDK Android** (`ANDROID_HOME` + `local.properties` → `sdk.dir`), `compileSdk 34`, `minSdk 24` — aucun build, même « pur Java », n'est possible sans SDK (les 4 modules sont des `com.android.library`).
3. Vérification de santé initiale à consigner : `./gradlew clean assembleDebug test lint --continue` vert sur `main` courant (base de référence : 1 772 tests verts, lint vert) + `scripts/check-doc-figures.sh`, `scripts/check-class-budgets.sh`, `scripts/check-external-refs.sh` verts. Toute divergence consignée dans le worklog avant de toucher au code.

---

## 1. Tableau de synthèse des lots (ordre proposé révisé)

| Lot | Contenu | Vérif. | Décision 🧑‍⚖️ | Changement vs plan d'origine |
|---|---|---|---|---|
| **L0** *(nouveau)* | Environnement + build de référence + mesures de base (P-01/P-02) | — | — | **ajouté** : débloque tous les `[S]` restants |
| **L1** | D-01 complétion/snippets (+ timeouts LSP par requête, enseignement comparaison) | ✅ | portée étapes 1-5 | **étendu** : cas limites textEdit + timeouts |
| **L2** | D-02 thread par session | ✅ | — | **étendu** : critère callbacks post-`dispose()` |
| **L2b** *(nouveau)* | D-05 recherche regex — **remonté en urgence** | ✅ aggravé | — | **remonté** : thread UI → ANR (voir §2) |
| **L3** | D-03 `LspFeature`, D-04 état statique `EditOps` | ✅ | honorer ou retirer | inchangé |
| **L4** | D-06 sockets, D-07 catch muets, D-08 logging | ✅ | — | inchangé |
| **L5** | M-01 code mort certain (6 méthodes) | ✅ 6/6 | — | inchangé, exécutable tel quel |
| **L6** | M-02, M-03 classes/API mortes | partiel* | **oui**, chaque élément | inchangé |
| **L7** | P-01…P-06 | ✅ | — | mesures de base prises en L0 |
| **L8** | D-09 docs + `check-doc-figures.sh` + `SECURITY.md` | ✅ | — | inchangé |
| **L9** | A-06 tests (flakiness, réflexion) | ✅ | — | cadré comme « protection de l'avantage n°1 » |
| **L10** | A-01…A-05, A-07, A-08 | ✅ | RTL, archive, plafonds | inchangé |

\* M-03 : les ~20 symboles listés n'ont pas été re-vérifiés un à un ; le balayage complet se fait au démarrage de L6 (le script de l'annexe §9 point 4 suffit).

**Justification du remontage de D-05** : le point `[S]` « sur quel thread ? » est levé — la recherche s'exécute sur le **thread principal** (`EditorSearchController` : `Handler(Looper.getMainLooper())`, debounce 160 ms ; `findMatches` appelé depuis les chemins de frappe et de remplacement). Une regex catastrophique (`.*.*.*x` sur texte adverse) fige l'UI → **ANR** (5 s → dialogue système). Le plan d'origine plaçait D-05 en L4 (4e vague) ; je recommande de le traiter juste après D-02, en lot dédié court, ou en tête de L4 si le propriétaire préfère l'ordre d'origine. Argument de coût : le correctif est localisé (un `CharSequence` garde-fou + un plafond + une centralisation du `Pattern`), sans dépendance aux autres lots.

---

## 2. Détail des lots

### L0 — Environnement et mesures de base (½ journée estimée)

- Installer/vérifier JDK 17+ (`javac`) et SDK Android ; `local.properties` (jamais commité).
- `./gradlew clean assembleDebug test lint --continue` sur `main` courant → consigner : nombre de tests verts (attendu ≈ 1 772), lint, budgets.
- **Mesures de base P-01/P-02** (prérequis explicite du lot L7 du plan d'origine) : allocation et temps de `commitText` sur un gros document ; profil `didChange` sur un document de 1 M de caractères. Consigner les chiffres bruts dans le worklog — ils servent de « rouge avant » pour L7.
- **Mesure D-02** `[S]` : à partir de quelles actions le thread `EditorSession-restyle` apparaît (création de session vs premier restyle asynchrone) ; compte de threads vivants après 200 sessions sans `dispose()`.
- **Vérification P-06** `[S]` : tracer si `EditorSelectionGestures.java:663` est sur le chemin d'un `MOVE`.

### L1 — Complétion LSP : snippets, textEdit, filterText (D-01)

Exécution conforme à l'action de l'audit (étapes 1-4, la 5 optionnelle en second temps), avec les **extensions issues de la comparaison** :

- Étendre `jo.codeeditor.lang.model.CompletionItem` de façon rétrocompatible : format snippet réel, plage de remplacement optionnelle (documenter la règle insertion/remplacement retenue), `filterText`, `sortText`.
- Renseigner dans `LspCompletionProvider` ; **tests du serveur factice à couvrir en plus des cas nominaux** : chevauchement de préfixe (pas de `console.console.log`), clamp de plage à la fin de document, plage inversée (serveur bogué), tri par `sortText`, filtrage par `filterText`.
- Acceptation : si snippet → expansion via `SnippetSession`, caret sur le premier stop, navigation Tab/Shift+Tab, câblage de `OnSnippetEditListener`, aucun `$` résiduel ; plage `textEdit` plus longue que le token → plage respectée ; `FEATURES.md` corrigé en conséquence.
- **Extension recommandée (L1-bis, un commit séparé)** : timeouts par type de requête LSP (complétion, hover, formatage, init), surchargeables par définition de serveur — absence constatée de tout timeout dans `cel-lsp` lors de la vérification.
- ⚠️ Risque identifié (N-2 du document 01) : la complétion vient d'être corrigée pour la famille XML (`44f7a7f`) — les tests nouveaux doivent couvrir **les familles C-like et XML** de la porte de contexte pour ne pas régresser le retour utilisateur.
- 🧑‍⚖️ DÉCISION : portée (1-4 d'abord, 5 en second temps).

### L2 — Thread par session (D-02)

- Rendre la fuite impossible sans `dispose()` : `allowCoreThreadTimeOut(true)` (inactivité ≈ 30 s) sur l'exécuteur de `RestyleEngine` **ou** voie partagée bornée type `EditorTaskLanes` — en conservant le jeton de génération et la logique annuler-puis-remplacer.
- Corriger la Javadoc (l.1561-1590) et `USAGE.md` : ne plus promettre l'appel automatique par la vue.
- **Critère d'acceptation additionnel (enseignement comparaison)** : tout restyle soumis **après** `dispose()` est rejeté proprement (pas d'exécution, pas de crash) — vérifier le comportement du jeton de génération à l'exécution.
- Acceptation d'origine : 200 sessions, restyles asynchrones sans `dispose()`, threads `EditorSession-restyle*` vivants sous borne constante après expiration ; tests de restyle existants inchangés et verts.

### L2b — Recherche regex sûre (D-05) — remonté

- Envelopper le `CharSequence` dans une vue qui lève une exception interne au-delà d'un délai (ou à l'interruption), plafonner les résultats (p. ex. 10 000) avec signal « résultats tronqués » à l'UI, centraliser la construction du `Pattern` au seul endroit de `FindReplace` (suppression du `compilePattern` dupliqué l.455-459).
- **Considérer en plus** (si le propriétaire l'approuve) le passage de la recherche hors thread UI — plus gros chantier, peut être différé en L7 avec P-06 ; le garde-fou délai protège déjà contre l'ANR dans les deux cas.
- Acceptation : motif catastrophique sur texte adverse → retour borné < 1 s, état « expression trop coûteuse » exposé ; test du plafond ; rouge avant, vert après.

### L3 — `LspFeature` + `EditOps` (D-03, D-04)

- D-03 🧑‍⚖️ : **honorer** (recommandé, conforté par la comparaison : évaluer la porte **avant l'installation** des providers dans `LspEditor` et les pullers) **ou retirer** avec rupture signalée. Acceptation : `COMPLETION` désactivé → aucune requête n'atteint le serveur factice.
- D-04 : objet résultat immuable retourné par une nouvelle méthode ; `EditorSession` l'utilise ; anciennes méthodes `@Deprecated` sans état partagé, retrait au palier suivant (CHANGELOG *Changed*). Acceptation : test de concurrence 2 threads × 10 000 itérations rouge avant / vert après ; plus aucun champ `static` non final dans `edit/` (vérifié : `lastPostfixResult` est le seul aujourd'hui).

### L4 — Sockets, catch muets, logging (D-06, D-07, D-08)

- D-06 : `LspStartException` avec message français et cause conservée dans `SocketConnectionProvider` ; Javadoc « transport en clair — boucle locale/réseau de confiance » ; test par provider (serveur factice). Décision implicite 🧑‍⚖️ sur `LocalSocketConnectionProvider` (rejoint M-03/L6) : tester ou déprécier.
- D-07 : journalisation `warn` via `EditorLog` aux deux sites (l.208, 284), puis `return null` ; test d'injection de réponse malformée.
- D-08 : reroutage des 70 `android.util.Log` de `cel-lsp` vers `EditorLog` ; suppression du `Log.i` par frappe (`LspDocumentSynchronizer` l.258) ; vérification qu'aucun message ne transporte de contenu de document. Acceptation : grep vide sur `editor/cel-lsp/src/main`.

### L5 — Code mort certain (M-01) — exécutable tel quel

Six suppressions vérifiées (0 référence chacune) : `EditorHighlightPainter` l.632 (`isBlockActive`) et l.708 (`bridgeBlank`) ; `EditorPreviewController` l.112 (`isPreviewableFile`) et l.122 (`isXmlLayoutFile`) ; `LspTextUtils` l.125 (`lineStartOffset`) ; `EditorView` l.3174 (`countLeadingWhitespace`). Un commit `refactor(scope)` par fichier ; compile + tests verts suffisent.

### L6 — Classes et API mortes (M-02, M-03) — décisions propriétaire

Re-balayer avec le script (annexe §9-4) puis traiter élément par élément (chaque décision est 🧑‍⚖️) :
`SurroundWith` (câbler ou supprimer) · `FoldModel` (faire déléguer `EditorFoldIndex` — suppression de la duplication — ou supprimer) · `SnippetSession` (câblée par L1 : traiter L6 **après** L1) · `NavigationMenu` (6 méthodes à supprimer, types `Symbol`/`NavOption` à extraire si coquille) · `OnSnippetEditListener` (câblé par L1) · M-03 : appliquer la politique proposée « API publique non testée → au moins un test, sinon `@Deprecated` ».

### L7 — Performance (P-01…P-06)

Avec les mesures de base de L0 comme « rouge avant » : P-01 (borner `tryPostfixCompletion` à la ligne courante — attention : le préfixe simulé sert aussi à `realEnd`/`simulatedPos`, borner sans changer le comportement, test de non-régression + mesure d'allocation) · P-02 (changement incrémental depuis les éditions suivies par la session, repli plein texte, retrait de la reconstruction de vérification ; mesure avant/après sur 1 M de caractères) · P-03 (un seul découpage partagé des 6 sites de `FoldDetector`) · P-04 (`Pattern` statique précompilé) · P-05 (champ `RectF` réutilisé par painter sur les 15 sites) · P-06 (selon verdict L0 ; extraction par plage plutôt que `getText()`).

### L8 — Documentation dérivée (D-09)

Corriger README/USAGE (`3.49.2`→courante), BUILD (`3.54.0`→courante), SECURITY.md (versions supportées), AGENT.md (graphe réel : `cel-lsp-api` → `api(:cel-core)` ; `cel-lsp` → `compileOnly` de `cel-ui`), FEATURES.md (tab stops tant que L1 n'est pas livré). Étendre `check-doc-figures.sh` : comparer les versions documentées à `build.gradle.kts` (test en injectant une fausse version). Corriger aussi `docs/plans/01` (collaborateur supprimé).

### L9 — Tests (A-06)

Helper d'attente bornée (latch/scrutation à délai maximal) pour remplacer les 41 `Thread.sleep` ; points d'accès de test plutôt que réflexion pour les champs visés par plusieurs des 97 `setAccessible` ; renommer `V3340RegressionTest` par sujet ; couverture en CI en mode rapport d'abord ; tests de contrat `cel-lsp-api` ; premiers tests pour `BlockEditor`/`SymbolBarView` (rejoint M-03). Acceptation : `Thread.sleep` au moins divisé par deux ; suite 5× sans échec intermittent.

### L10 — Structure (A-01…A-05, A-07, A-08)

Extraction d'`EditorView` par petits commits **en abaissant** le plafond (cliquet, jamais en le relevant) ; plan 03 pour `cel-lsp`/`jo.codeeditor.view` (ou au minimum aligner `AGENT.md`, fait en L8) ; A-03 conventions de build + catalogue de versions + revoir les versions masquées par la baseline (18 entrées) ; A-04 vérification minifiée avant d'écrire les règles consommateur ; A-05 strings.xml + nœud d'accessibilité minimal + retrait des 2 entrées baseline + **décision RTL** ; A-07 annotations nullité (`cel-core`, `cel-lsp-api` d'abord — plus grand écart vs projet mature) + politique de dépréciation dans `CONTRIBUTING.md` + contrôle de compatibilité binaire en CI (avertissement puis bloquant) ; A-08 archivage des rapports de session + scission du CHANGELOG + retrait des exceptions `prebuilt/` mortes.

---

## 3. Règles de conduite pendant l'exécution (rappel AGENT.md, non négociables)

1. Commentaires et messages de commit **en français** ; **un commit par changement** (`fix(scope): …`, `perf(scope): …`, `refactor(scope): …`, `chore(scope): …`) ; **un test qui échoue avant et passe après** par correctif de bug ; entrée `CHANGELOG.md` par version ; pas de reformatage massif ni renommage gratuit.
2. **`scripts/check-external-refs.sh` avant chaque commit** ; interdiction absolue de mentionner un projet externe où que ce soit dans le dépôt — les enseignements de la comparaison se traduisent en comportements génériques, implémentés depuis la spécification LSP et des tests propres. Ne jamais copier/adapater du code externe ; en cas de doute d'origine : s'arrêter et demander.
3. « Terminé » = `./gradlew clean assembleDebug test lint --continue` vert, aucun nouvel avertissement lint, `check-doc-figures.sh` + `check-class-budgets.sh` verts, `git status` propre. **Ne jamais régénérer les `lint-baseline.xml`** pour faire passer un problème.
4. Décisions 🧑‍⚖️ : s'arrêter et demander ; ne jamais « régler » un doute en supprimant du code.
5. Pas de rupture d'API sans signalement CHANGELOG (*Changed*) ; préférer `@Deprecated` + délégation, retrait au palier suivant.
6. Publication : branche de travail fusionnée dans `main` puis supprimée ; poussée systématique après validation.
7. Ne pas toucher à `local.properties`, secrets, keystores ; ne pas committer `build/`, `.gradle/`, `.idea/`, `*.iml`, `*.hprof`.

---

## 4. Décisions propriétaire à recueillir avant le démarrage

| # | Décision | Lots concernés | Options |
|---|---|---|---|
| 1 | Portée D-01 | L1 | étapes 1-4 d'abord (recommandé) / tout y compris résolution paresseuse |
| 2 | Ordre D-05 | L2b | remonté en lot dédié (recommandé) / maintenu en L4 |
| 3 | `LspFeature` | L3 | honorer (recommandé) / retirer avec rupture |
| 4 | `SurroundWith`, `FoldModel`, `SnippetSession`, `NavigationMenu`, `OnSnippetEditListener` | L6 | câbler ou supprimer, élément par élément |
| 5 | Politique M-03 (API publique non testée) | L6, L9 | tester / `@Deprecated` / retirer |
| 6 | Hors thread UI pour la recherche | L2b/L7 | différer (recommandé) / inclure |
| 7 | Niveau RTL | L10 | non supporté documenté / plan d'implémentation |
| 8 | Historique des rapports dans le dépôt | L10 (A-08) | archiver sous `docs/archive/` / conserver tel quel |
| 9 | Timeouts LSP par requête (L1-bis) | L1 | inclure maintenant / différer |

---

## 5. Séquence de démarrage recommandée

**L0** (environnement + mesures) → **L1** (D-01, le défaut 🔴 le plus visible pour les utilisateurs) → **L2** (D-02, fuite) → **L2b** (D-05, ANR) → L3 → L4 → L5 → (décisions L6) → L7 → L8 → L9 → L10. Chaque lot : une branche, fusion dans `main`, tag/version + CHANGELOG selon la cadence du dépôt, puis lot suivant.

Le worklog d'exécution (un fichier unique, append-only) recevra : le Task ID, les commandes jouées, les chiffres mesurés, les verdicts des tests rouge-avant/vert-après, et les écarts éventuels par rapport au présent plan.
