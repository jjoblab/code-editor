# ADR-002 — Stratégie multi-langages : généraliser l'interne, embarquer la voie grammaires

- **Statut** : proposé — décisions 🧑‍⚖️ ouvertes, à arbitrer par le propriétaire
- **Date** : 2026-10-16
- **Décideur** : propriétaire du dépôt

## Contexte (chiffres mesurés à 3.71.0-dev)

La couche langage actuelle repose sur trois voies superposées :

| Voie | État mesuré | Rôle |
|---|---|---|
| Intégrée | **28 profils** déclarés (`BuiltinLanguages`) routés par **14 familles** (`SyntaxFamily`) vers **14 tokéniseurs Java** écrits à la main (`highlight/tokenizer/`) | coloration hors-ligne, zéro dépendance |
| Grammaires | interface `TextMateTokenizer` + crochet `SyntaxHighlighter.setTextMateTokenizer` présents dans `cel-core` (porte : documents ≤ 800 lignes, `TEXTMATE_LINE_LIMIT`) ; l'implémentation documentée (module optionnel, 17 grammaires) vit **hors de ce dépôt** | coloration d'excellence (portées, injections) |
| LSP | `LspSemanticTokensPuller` applique les jetons sémantiques du serveur | couche sémantique par-dessus, déjà générique |

Le problème n'est pas l'absence de générique — la table déclarative (`LanguageProfile`/`LanguageRegistry`) existe et fonctionne. Le problème est que **ce qui est générique reste codé** :

- les ensembles de mots-clés sont des `Set<String>` en dur (`BuiltinLanguages`) ;
- chaque famille a SON tokéniseur Java : les mêmes règles (chaînes, commentaires, nombres, échappements) sont réimplémentées 14 fois ;
- des comportements par langage vivent en dehors de la table : `CommentSyntax`, `FoldStrategy` (par famille), paires d'auto-complément de `SmartTyping`, `XmlNewline`, exemptions de porte de complétion (familles XML/LOG), mots-clés intégrés de complétion ;
- les thèmes (`EditorTheme`) exposent une palette syntaxique (16 champs colorés + types fins héritant du champ parent) et un mécanisme de surcharge par clés `syntax.*` — mais aucun **schéma de portées** ne relie les grammaires à ces clés.

Conséquence vécue : ajouter un langage aujourd'hui = toucher 3 à 5 endroits dont du Java ; les correctifs de tokénisation se dupliquent famille par famille.

## Décision proposée — quatre chantiers

### C1 — Ce qui est donnée devient déclaratif

`LanguageProfile` s'enrichit des données par langage aujourd'hui dispersées : délimiteurs de chaîne/commentaire (mono-ligne, bloc, documentation), paires de crochets, marqueurs de plis, mots-clés (coloration ET complétion intégrée). `SmartTyping`, `FoldStrategy`, `CommentSyntax` et la complétion intégrée **lisent le profil** au lieu de switcher. Critère : ajouter un langage d'une famille existante = **une entrée de table, zéro Java**.

### C2 — Tokéniseur générique piloté par la table

Un moteur paramétrique (règles par famille-template : délimiteurs, nombres, identifiants) remplace progressivement les 14 tokéniseurs. La famille reste le sélecteur de template, plus le lieu du code. Migration tokéniseur par tokéniseur, chacune gardée verte par sa suite existante (cliquet de démantèlement, comme le plan 01). Critère : les suites de tokénisation actuelles restent vertes **sans modification de leurs assertions**.

### C3 — La voie grammaires rentre au dépôt (module optionnel)

L'implémentation `TextMateTokenizer` (moteur de grammaires, mapping portées→`TokenType`, grammaires embarquées) entre comme module **optionnel** `:cel-textmate`, dépendant de `:cel-core` seul — reproduisant le contrat déjà documenté par l'interface. Supporter N nouveaux langages = ajouter des **fichiers** de grammaire, pas du Java. La voie intégrée (C2) reste le repli hors-ligne (l'interface le prévoit déjà : sans implémentation enregistrée, le switch-case intégré prend le relais). La porte des 800 lignes est conservée (coût du restyle grammaire mesuré supérieur au tokéniseur intégré).

### C4 — Schéma de portées pour les thèmes

Les catégories de portées standard des grammaires (mot-clé/contrôle/modificateur, chaîne/brute/caractère, commentaire/documentation, constante/numérique, entité, variable, support, namespace…) sont mappées sur les clés `syntax.*` existantes et les types fins déjà présents dans `TokenType` (`DOC_COMMENT`, `KEYWORD_CONTROL`, `NAMESPACE`, `RAW_STRING`… — tous déjà héritants, prêts à être colorés). Critère : **un thème colore un langage nouveau sans clé par langage** ; les deux voies (intégrée et grammaires) convergent vers le même schéma.

## Décisions ouvertes 🧑‍⚖️

| # | Décision | Options | Recommandation |
|---|---|---|---|
| D1 | Où vit le moteur de grammaires ? | (a) dans ce dépôt, module optionnel ; (b) fourni par l'hôte (statu quo documenté) | **(a)** — sinon la qualité de coloration reste inégale selon l'hôte, et le dépôt ne peut pas livrer de langages nouveaux « grammaire-only » |
| D2 | Ampleur du schéma de portées thèmes | (a) 16 catégories actuelles ; (b) palette étendue (~25) exploitant les types fins existants | **(b)** — les types existent déjà, seules les entrées de palette manquent |
| D3 | Corpus de grammaires embarquées | quelles licences accepter ? | vérification **fichier par fichier** avant tout embarquement ; les attributions passent dans `NOTICE` (même discipline que LSP4J/Gson/Material Symbols) |
| D4 | Ordre des chantiers | C1→C2→C3 (interne d'abord) ; C1→C3→C2 (langages d'abord) | **C1→C3→C2** — la voie grammaires apporte le plus de langages le plus vite ; le démantèlement des tokéniseurs intégrés suit au rythme du cliquet |
| D5 | Complétion intégrée multi-langages | les mots-clés de complétion suivent-ils la table C1 ? | **oui** — même table que la coloration (source unique) |

## Conséquences

- **Positives** : langage nouveau = donnée (entrée de table, éventuellement une grammaire) ; la duplication inter-familles cesse de croître ; les thèmes deviennent indépendants des langages ; la voie LSP conserve la couche sémantique par-dessus les deux voies syntaxiques ; l'API publique (`LanguageProfile.builder()…register()`) n'est pas rompue — elle s'enrichit.
- **Négatives / risques** : C3 ajoute un module et des fichiers embarqués (taille d'artefact, obligations de licence documentées) ; le mapping de portées est un travail de calibration thème par thème ; le moteur de grammaires a un coût d'exécution supérieur (porte 800 lignes maintenue) ; C2 touche du code sensible (14 suites de tokénisation à garder vertes).
- **Critères d'acceptation mesurables** : (i) un langage d'une famille existante ajouté sans Java ; (ii) un langage « grammaire-only » (aucun tokéniseur dédié) ; (iii) un thème colorant les deux voies sans clé par langage ; (iv) à chaque étape, suite complète verte et garde-fous conformes.
