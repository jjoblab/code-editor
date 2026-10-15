# 02 — Étude comparative avec un éditeur Android mature (`[EXT-1]`)

> **Objet** : éclairer la priorisation du plan de correction de `code-editor` 3.69.0 par l'étude d'un projet Android de la même catégorie, plus mature et largement utilisé en production (ci-après « le projet mature » / `[EXT-1]`).
> **Base analysée** : clone du projet `[EXT-1]` (branche `main`, révision à la date de l'audit), analyse statique complète — modules, code, tests, build, CI.
> **Conformité (`AGENT.md`)** : l'étude n'a servi qu'à orienter la priorisation et les critères d'acceptation. Les implémentations partent des **spécifications publiques (LSP, Unicode…), du comportement attendu et de tests écrits dans ce dépôt** — jamais du code de `[EXT-1]`. Ce document est une trace d'audit archivée dans le dépôt ; conformément à la règle absolue d'`AGENT.md`, le projet externe y est désigné exclusivement par l'identifiant neutre `[EXT-1]`, sans nom, sans lien, sans hash.

---

## 1. Cadre et métriques générales

| Dimension | `code-editor` 3.69.0 | Projet mature (`[EXT-1]`) | Lecture |
|---|---|---|---|
| Taille main (lib) | ~53 700 l. Java (4 modules) | ~89 800 l. (66 % Java / 34 % Kotlin, 9 modules + BOM + app démo) | mature ≈ 1,7× ; scope plus large (3 moteurs de coloration) |
| Langue | Java exclusif, Canvas | Java + Kotlin (cœur éditeur encore 84 % Java), Canvas | équivalent sur le principe |
| Modules | `cel-core`, `cel-lsp-api`, `cel-lsp`, `cel-ui` | 9 modules : un module éditeur principal, un module client LSP, un module par moteur de coloration (grammaires TextMate, coloration déclarative, analyse syntaxique incrémentale), un module de langage dédié, une couche native d'expressions régulières (JNI C++), un BOM et une application de démonstration | découpage comparable ; mature externalise chaque langue |
| **Tests (lignes)** | **~40 800 l.** (ratio 0,25–0,82 par module) | **~1 240 l.** (ratio ≈ 1,4 %) | **avantage décisif `code-editor`** |
| Tests (@Test significatifs) | ~1 772 (annoncés, contrôlés en CI) | 43 dont boilerplate `assertEquals(4, 2+2)` et un test sans assertion | idem |
| CI | test + lint **bloquant** + baseline 24 entrées + budgets de classes + contrôle des chiffres doc + contrôle refs externes + actions épinglées SHA + validation wrapper | `assembleDebug` + publication snapshot — **aucun test, aucun lint exécutés** | **avantage décisif `code-editor`** |
| CHANGELOG | 84 sections, tenu par version | inexistant (releases de la plateforme d'hébergement) | avantage `code-editor` |
| Version | 3.69.0 | 0.24.6 | mature en 0.x malgré ~10 ans d'âge |
| Dépendance LSP | LSP4J (bibliothèque) | LSP4J | identique |

**Conclusion d'étape** : la hiérarchie « mature = mieux » est fausse en bloc. Le projet mature est supérieur sur la **richesse fonctionnelle** (moteurs de coloration multiples, IME avancé, minimap, sticky scroll, système d'événements) et sur l'**infrastructure de build** (catalogue de versions, plugins de convention, BOM, robot de montées de version). Il est **inférieur sur l'assurance qualité** (tests quasi absents, CI sans test ni lint), ce qui est précisément le domaine où `code-editor` a investi depuis le début. L'étude oriente donc le plan vers : *câbler et fiabiliser ce qui existe déjà* plutôt que *ajouter de la surface fonctionnelle*.

---

## 2. Axe par axe de l'audit : comment le mature traite le même problème

### 2.1 D-01 (complétion LSP / snippets) — le mature montre la cible

C'est l'axe où l'écart fonctionnel est le plus net, et le plus instructif pour le lot L1 :

- **`textEdit`** : le mature lit la variante complète du protocole (`Either<TextEdit, InsertReplaceEdit>`), convertit `InsertReplaceEdit` en `TextEdit`, puis **corrige les cas limites** : chevauchement de préfixe (évite `console.console.log`), clamp de la plage à la fin du document, plage inversée (bug connu des serveurs). → Enseignement pour D-01 : l'action « étape 2 » de l'audit doit prévoir ces trois cas limites **dès les tests du serveur factice**, pas seulement le cas nominal.
- **`insertTextFormat`** : `Snippet` → parse du `newText` puis démarrage du contrôleur de session de snippet ; sinon insertion en texte brut. Le **même parseur de snippets** sert les snippets locaux et LSP — pas de double moteur.
- **`filterText` / `sortText`** : exploités par un moteur de score fuzzy (score > `sortText` > label > kind, `filterText` obligatoirement apparié au mot tapé). → Pour D-01, l'audit demande de *renseigner* les champs ; le mature montre qu'ils servent à **filtrer et trier**, pas seulement à être transportés : les tests d'acceptation devraient couvrir le tri par `sortText` et le filtrage par `filterText`.
- **Timeouts par type de requête** (complétion 3 s, hover 2 s, formatage 5 s, init 10 s), surchargeables globalement et par serveur. → point **absent de l'audit d'origine** : je recommande de l'ajouter au périmètre du lot L1 ou L4 (voir `03-plan-execution.md`, tâche L1-bis).
- Point d'attention : le mature n'a **pas** branché `resolveCompletionItem` côté UI — l'étape 5 « optionnelle » de D-01 reste donc vraiment optionnelle, l'audit a raison de la différer.

### 2.2 D-02 (fuite de thread par session) — le mature a le même genre de trou, et un comportement à retenir

Le contrat du mature est **explicite et symétrique** du nôtre : la libération est à la charge de l'hôte ; `onDetachedFromWindow()` ne nettoie presque rien. Deux enseignements :

1. **Personne n'a résolu « la fuite impossible même si l'hôte oublie »** — le mature a lui-même un pool statique (découpage de lignes) jamais éteint. L'action de l'audit (`allowCoreThreadTimeOut(true)` ≈ 30 s, ou voie partagée bornée type `EditorTaskLanes`) reste donc la bonne et irait **plus loin** que ce que fait le mature.
2. **Comportement à retenir** : après la libération, le mature **neutralise tout runnable postérieur** (les callbacks tombent dans un puits silencieux). Si le lot L2 ajoute l'expiration du thread, il devrait aussi garantir que les restyles soumis après `dispose()` sont rejetés proprement (le jeton de génération existant y ressemble déjà — à vérifier à l'exécution).

### 2.3 D-03 (`LspFeature` sans effet) — le mature raisonne en « capacités du serveur »

Le mature conditionne l'envoi des requêtes aux `serverCapabilities` annoncées à l'initialisation. L'enseignement pour D-03 : la porte « fonctionnalité désactivée par l'hôte » doit être évaluée **avant l'installation du provider** (et pas au milieu de la chaîne de requête) — c'est l'option « ne pas installer le provider » de l'audit, que l'étude confirme comme la plus propre. Aucun signal de risque particulier.

### 2.4 D-05 (recherche regex) — le mature protège l'UI mais pas le backtracking

La recherche du mature tourne sur **un thread dédié par recherche**, la précédente étant interrompue ; l'interruption est vérifiée **entre chaque `matcher.find()`** ; les résultats sont publiés par événement, et la recherche est relancée automatiquement à chaque changement de contenu. Points d'enseignement :

- **Le plus urgent est confirmé** : sortir la recherche du **thread UI** (où elle est aujourd'hui chez nous — cf. `01-verification-audit.md` §2-D-05, point aggravé). Le mature ne recherche jamais sur le thread principal ; nous le faisons à chaque frappe (debounce 160 ms).
- **Le garde-fou délai/plafond demandé par l'audit irait plus loin que le mature** : son interruption entre les `find()` ne protège pas d'un **unique** `find()` en backtracking exponentiel. L'action D-05 (CharSequence garde-fou avec délai + plafond de résultats + signal « trop coûteux ») est donc un **différenciateur**, pas un simple rattrapage.
- Détail utile : le mature délègue l'**UI de recherche** à l'app hôte ; `code-editor` l'intègre (`EditorSearchController`) — la valeur est réelle, à condition de rendre l'exécution sûre.

### 2.5 D-08 (journalisation) — avantage structurel déjà acquis

Le mature a une façade `Logger` qui délègue à `android.util.Log`, **sans niveau configurable, sans sink, sans extinction possible** ; l'usage direct de `android.util.Log` y reste majoritaire. Notre `EditorLog`/`EditorLogger` (façade installable, `installIfAbsent`) est déjà **supérieur** : le lot D-08 est un simple reroutage interne + suppression du `Log.i` par frappe. Le point de comparaison à retenir : chez le mature, un `Log` par frappe comme le nôtre existerait sans remède — notre architecture de logging est un atout à exploiter pendant L4, pas à reconstruire.

### 2.6 P-02 (sync incrémentale) — le mature évite le diff plein-texte

Le côté mature maintient la correspondance contenu ↔ index d'événements de changement (le modèle écoute ses propres deltas), donc l'incrémental LSP découle des **éditions déjà suivies** — exactement l'action « construire le changement incrémental à partir des éditions déjà suivies par la session » proposée par l'audit pour P-02. L'étude **valide l'approche** et le garde-fou « garder le diff plein texte en repli » est aussi une prudence que le mature n'a pas (il n'a pas de repli vérifié par reconstruction — notre triple concaténation de vérification est coûteuse mais sûre ; l'action proposée la retire au profit de la source de vérité unique des éditions, en gardant le repli).

### 2.7 A-01 (décomposition des monolithes) — même maladie, discipline supérieure chez nous

Le fichier principal de vue du mature fait **5 527 lignes** (dont 2 699 l. de rendu) sans plafond ni garde-fou ; l'extraction s'y fait par composants. Chez nous : `EditorView` 3 850 l. **avec plafond cliquet** (relevé à 4 100 — le seul vrai manquement, A-01). Enseignement : le mécanisme de budgets de classes est une discipline **que le mature n'a pas** — l'action « extraire par petits commits en abaissant le plafond (cliquet) » est la bonne ; ne jamais le supprimer pour « accélérer ».

### 2.8 A-03 (build) — le seul domaine où reprendre la structure du mature est directement rentable

Le mature a : catalogue de versions TOML unique, plugins de convention (`build-logic/`), BOM pour aligner les artefacts, robot d'automatisation des montées de version, publication Maven Central signée en CI. L'audit A-03 demande exactement ça (blocs `android {}` ×4 → convention + catalogue). L'étude montre en outre le coût de ne pas l'avoir : 18 des 24 entrées de notre baseline lint sont des versions en retard **masquées** — chez le mature, l'équivalent est géré automatiquement par son robot de montées de version. C'est le lot A-03 le plus « mécaniquement » bénéfique.

### 2.9 A-05 (accessibilité / i18n / RTL) — le mature est en avance sur un seul sous-axe

- **i18n** : le mature n'a que 2 langues (EN/ZH) avec un mécanisme de remapping pour l'hôte. Notre migration des libellés vers `strings.xml` (préfixe `cel_`) demandée par l'audit reste la base ; pas de leçon supplémentaire.
- **RTL** : le mature a un **support bidi du contenu** réel (résolution de direction par ligne, option d'affichage des lignes RTL à droite) — c'est le seul sous-axe où l'écart est structurel. La décision 🧑‍⚖️ demandée par A-05 (« non pris en charge, ou plan d'implémentation ») doit savoir qu'un support complet est un investissement significatif (modèle de direction par ligne, pas un flag manifeste).
- **Accessibilité** : le mature expose un nœud d'accessibilité avec actions COPY/CUT/PASTE/SET_TEXT/scroll. C'est un gabarit de comportement utile pour l'action A-05 (2) « exposer le minimum accessible ».

### 2.10 A-06 (tests) et A-07 (API stable) — les renversements de l'étude

- **Tests** : c'est le renversement le plus important de toute l'étude. Le mature : 43 tests dont du boilerplate, **CI sans test**. Nous : 1 772 tests, CI bloquante, chiffres contrôlés. Le « risque flakiness » de nos 41 `Thread.sleep` est réel (A-06), mais il se corrige ; l'absence de tests du mature ne se corrige pas rétroactivement. **Conclusion : le lot A-06 doit être traité comme la protection de notre avantage concurrentiel n°1, pas comme une corvée d'hygiène.**
- **API** : sur la nullité, le mature est **largement en avance** (531 `@NonNull` + 184 `@Nullable` sur le seul module cœur, null-safety Kotlin ailleurs) contre **4 fichiers** annotés chez nous. C'est l'écart le plus défavorable de tout le tableau. L'action A-07 (annoter d'abord `cel-core` et `cel-lsp-api`) est validée et priorisée par l'étude. En revanche, nos 14 `@Deprecated` sans politique contrastent avec les 10 `@Deprecated("")` **sans message** du mature : aucun des deux projets ne gère bien les dépréciations — notre politique documentée à rédiger (A-07) nous mettrait devant.

---

## 3. Acquis de `code-editor` par rapport au projet mature (à préserver)

Côté fragile de l'étude — tout ce qui suit doit être traité comme des acquis à ne jamais sacrifier pendant l'exécution des lots :

1. **L'assurance qualité complète** : 1 772 tests exécutés en CI, lint bloquant, baseline petite et contrôlée, budgets de classes, contrôle des chiffres documentés, contrôle des références externes, actions épinglées par SHA, validation du wrapper Gradle. Le mature n'a **aucun** de ces garde-fous en CI.
2. **La discipline d'historique** : CHANGELOG par version en français, un commit par changement, convention `fix(scope)`… Le mature suit ses releases par la plateforme d'hébergement sans changelog normé.
3. **La sécurité des écritures disque pilotées par le serveur** (`WorkspacePathGuard`, frontière canonique racine + séparateur) : rien d'équivalent visible côté mature (application directe des éditions d'workspace).
4. **Le plis (folding) réel** : détection multi-langages + régions fusionnables + plis repliables. Le mature n'a **pas** de repliage réel (seulement des indent-guides ; l'API de région de pli existe mais le repliement n'est pas rendu).
5. **La complétion postfix** (D-04/P-01 le montrent fragile côté concurrence et allocation, mais la fonctionnalité est là) et le modèle rope maison — le mature utilise une liste de lignes avec shallow-copy par thread d'analyse.
6. **Un moteur de snippets testé mais non câblé** (`SnippetSession`, D-01/M-02) : le mature a câblé le sien ; le nôtre existe déjà, testé — le lot L1 est un **câblage**, pas une construction.

---

## 4. Traduction en enseignements prioritaires pour le plan

| Enseignement de l'étude | Impact sur le plan d'exécution |
|---|---|
| D-05 : recherche sur thread UI (confirmé/aggravé) + aucun projet ne protège le backtracking | **Monter D-05 en priorité haute** ; l'action garde-fou devient un différenciateur |
| D-01 : le mature câble textEdit/snippets/fuzzy/timeout ; les cas limites (chevauchement, clamp, plage inversée) sont la moitié du travail | Étendre les critères d'acceptation du lot L1 ; ajouter les timeouts LSP par requête (L1-bis) |
| D-02 : comportement « neutraliser les callbacks post-libération » + expiration du thread | Ajouter au lot L2 un critère d'acceptation sur les callbacks post-`dispose()` |
| D-03 : évaluer la porte avant l'installation des providers | Confirme l'option « honorer » du lot L3 |
| P-02 : incrémental depuis les éditions suivies, repli plein texte | Confirme l'action P-02 telle quelle |
| A-07 : écart le plus défavorable (4 fichiers annotés vs 531) | Prévoir un effort soutenu et progressif d'annotations, `cel-core`/`cel-lsp-api` en tête |
| A-03 : catalogue + conventions = bénéfice mécanique ; l'équivalent (robot de montées de version) à évaluer | Confirme le lot A-03 ; les 18 entrées « versions en retard » de la baseline deviennent visibles |
| A-06 : notre CI est l'avantage n°1 | L6/A-06 = protection de l'avantage, pas hygiène ; remplacer les `sleep` par attentes bornées |
| Folding / postfix / snippets : nos fondations sont bonnes, non câblées | Renforce L1 (câblage snippets) et la décision M-02 (ne pas supprimer `FoldModel` : la fusion de régions existe déjà en double) |
| AGENT.md : interdiction absolue de citer/copier `[EXT-1]` dans le dépôt | Tous les lots : formulations génériques dans le dépôt, implémentation depuis la spec LSP et tests propres ; ma clause de conformité est rappelée dans `03-plan-execution.md` |

---

## 5. Encadré de conformité pour l'exécution (à relire avant chaque lot)

1. **Aucune mention du projet mature dans le dépôt**, sous aucune forme (nom, formule d'emprunt, lien, hash, comparaison nominative) — `AGENT.md` l'interdit où que ce soit, y compris messages de commit et CHANGELOG. Dans les documents internes du dépôt, le désigner par `[EXT-1]` si nécessaire ; de préférence, décrire les comportements de façon autosuffisante.
2. **Aucun code copié/adapté/traduit** du projet mature : les enseignements ci-dessus décrivent des **comportements** (formats LSP, cas limites, sémantiques de tri) qui relèvent de la spécification LSP publique — implémentation à partir de la spec et de tests écrits dans le dépôt.
3. Les critères d'acceptation de l'audit d'origine restent la référence ; les présents enseignements ne font qu'**étendre** les tests d'acceptation (cas limites D-01, callbacks post-dispose D-02).
