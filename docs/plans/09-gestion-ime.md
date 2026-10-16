# Plan 09 — Gestion IME : analyse et correctifs

> Analyse comparative de la gestion de la méthode de saisie (IME) de la
> bibliothèque, confrontée au contrat de plateforme Android et aux
> attentes d'une bibliothèque publiée. Ce plan est le pivot des
> correctifs identifiés : chaque écart est décrit avec son scénario
> d'échec reproductible, sa priorité et son remède chiffré en effort.
> Base : 3.73.0 (post-refonte de l'atelier de démonstration).

## 1. Périmètre et méthode

L'analyse a couvert les trois couches qui portent l'IME :

| Couche | Fichiers | Rôle |
|---|---|---|
| Vue | `cel-ui/…/view/EditorImeBridge.java` | fabrique l'`InputConnection`, implémente chaque opération, pousse les rappels vers l'`InputMethodManager` |
| Session | `cel-core/…/session/ImeBridge.java`, `EditorSession.java` | traduit les opérations en mutations du pipeline unique (`replaceRangeWithCaret`), possède la région de composition |
| Document | `cel-core/…/rope/Rope.java` | épissure effective |

Chaque méthode du contrat `InputConnection` a été passée en revue
(25 points de comparaison), du commit de texte à la demande de position
du curseur, en confrontant systématiquement : que fait le contrat
Android, que fait la bibliothèque, et quel scénario utilisateur concret
en découle. Le verdict d'ensemble est favorable : l'architecture est
rigoureusement structurée (séparation session/vue, composition possédée
par le modèle et non par la connexion, bornage IPC explicite) et elle
résout des problèmes réels que le contrat ne impose pas mais que la
frappe de code exige (suppression en points de code, anti auto-espace,
extraction paresseuse des très gros fichiers). Deux écarts de priorité 1
méritent toutefois une correction rapide.

## 2. Les fondations solides (à préserver)

- **Suppression en points de code** correcte (paires de substitution) —
  les IME sûres du terrain retombent sur le chemin en caractères quand
  l'éditeur répond faux.
- **Anti auto-espace** après ponctuation fermante, avec validation du
  lot (B21a) : les deux formes d'insertion des claviers (groupée et
  séparée) sont avalées, une vraie espace tapée délibérément survit.
- **État de composition possédé par le modèle** : il survit à la
  recréation de la connexion (rotation, restart), là où une composition
  logée dans la connexion meurt avec elle.
- **Fenêtre d'extraction paresseuse** (B19) : la longueur est lue sur
  la rope, un document d'un million de caractères ne coûte une copie
  que si l'IME demande vraiment la fenêtre (plafond 100 000).
- **Économie de restarts raisonnée** : `restartInput` n'est demandé que
  quand le miroir de l'IME diverge structurellement (composition
  traversée, annulation en composition, auto-espace avalée, divergence
  des règles de frappe) — jamais en rafale.

## 3. Écarts constatés

### E1 — Priorité 1 : pas de restartInput au changement de session

`EditorView.setSession` détache l'ancienne session, purge loupe,
glissement, retour à la ligne, recherches et marqueurs — tout est
resynchronisé sauf l'IME. Le miroir du clavier décrit encore l'ancien
fichier : texte extrait, sélection, éventuellement une composition en
cours. Scénario : l'utilisateur compose un mot, bascule d'onglet par le
tiroir, revient frapper — l'IME peut rejouer son miroir périmé ou
réveiller une région fantôme.

**Remède** : à la fin de `setSession`, si une connexion vivante existe,
incrémenter la génération, purger le jeton d'extraction et le mode
ancre, puis demander `imm().restartInput(this)`. Test Robolectric :
bascule d'onglet avec composition armée, le restart est demandé et le
jeton d'extraction est invalidé.

### E2 — Priorité 1 : sélection exposée sans borne

`getSelectedText` renvoie la sélection entière. Le retour d'une
`InputConnection` traverse le binder, dont la transaction est plafonnée
(environ un mégaoctet partagé) ; une sélection de tout un gros fichier
sollicitée par un clavier curieux — recherche, dictionnaire, réécriture
— lève une `TransactionTooLargeException`, au mieux un avertissement, au
pire un gel de l'interface.

**Remède** : reprendre le plafond maison `MAX_IPC_TEXT` (4 096) et la
même borne symétrique que `imeTextBeforeCursor`. Test : une fausse IME
demande la sélection d'un document de 500 000 caractères, la réponse est
bornée.

### E3 à E5 — Priorité 2 : services de plateforme attendus d'une bibliothèque publiée

- **E3 — capitales.** `initialCapsMode` vaut 0 et `getCursorCapsMode`
  n'est pas surchargée : l'IME ne saura pas activer la majuscule en début
  de phrase. Impact faible (les suggestions sont volontairement coupées)
  mais l'information coûte trois lignes via `TextUtils.getCapsMode` sur
  une sous-chaîne bornée (ne pas matérialiser le document entier).
- **E4 — instantanés (API 33).** `takeSnapshot` permet au système de
  sauvegarder l'état IME (texte environnant, composition, capitales)
  quand l'application est tuée en arrière-plan. Retourner l'implémentation
  par défaut (null) : après une mort de processus, la composition en
  cours est perdue. Construire l'instantané sur 2 048 caractères de
  contexte.
- **E5 — stylet (API 34).** `setImeConsumesInput` participe au protocole
  d'écriture manuscrite directe : sans retour positif, la saisie au
  stylet sur la zone d'édition reste un simple geste. L'implémenter et
  invalider la connexion en retour.

### E6 à E10 — Priorité 3 : confort et interopérabilité

- **E6 — indications d'extraction ignorées.** `buildExtractedText`
  fenêtre toujours à 100 000 caractères ; certains IME demandent exprès
  de petites fenêtres (`hintMaxChars`) et paient le prix.
- **E7 — clavier matériel.** Aucune détection : le clavier logiciel
  surgit au tap même un clavier physique branché.
- **E8 — lecture seule.** `onCheckIsTextEditor` répond vrai même en
  lecture seule : le tap fait monter un clavier dont les frappes seront
  silencieusement ignorées.
- **E9 — EditorInfo figé.** Aucun hook pour l'hôte : impossible d'ajouter
  `NO_PERSONALIZED_LEARNING`, une action d'entrée personnalisée, ou un
  type dédié sans toucher la bibliothèque.
- **E10 — commandes privées.** `performPrivateCommand` n'est pas routé :
  les extensions propriétaires de certains claviers n'ont pas de canal.

## 4. Feuille de route (R1 à R9)

Ordonnée par retour sur effort. R1 et R2 ferment les deux seuls
scénarios reproductibles en atelier multi-onglets ; R3 à R5 constituent
le « niveau bibliothèque publiée » sur les API 33/34 ; R6 à R9 peuvent
attendre un retour terrain.

**État d'avancement** : ✅ **R1 et R2 sont LIVRÉS** (3.73.0, passe
B27/IME) — tests rouges avant / verts après
(`SetSessionImeRestartTest` : purge de génération/jeton/mode ancre +
restartInput compté par shadow, sans appel fantôme vers un clavier
jamais attaché, la vieille connexion ne ressuscite pas le jeton ;
`ImeSelectedTextBoundTest` : sélection de 500 000 caractères bornée à
4 096, borne relative au début, garde document). R3 à R9 restent à
venir.

| N° | Recommandation | Fichiers touchés | Priorité | Effort |
|---|---|---|---|---|
| R1 | ✅ LIVRÉ — `restartInput` + purge de l'état IME dans `setSession` (génération, jeton d'extraction, mode ancre) + test multi-onglets | `EditorView.setSession`, `EditorImeBridge.purgeForSessionChange` | P1 | 0,5 JH |
| R2 | ✅ LIVRÉ — `getSelectedText` borné à `MAX_IPC_TEXT` (borne symétrique + garde document) via `EditorSession.imeSelectedText` | `EditorImeBridge.EditorInputConnection`, `ImeBridge` (core) | P1 | 0,25 JH |
| R3 | `getCursorCapsMode` réel sur sous-chaîne bornée ; `initialCapsMode` alimenté | `EditorImeBridge` | P2 | 0,5 JH |
| R4 | `takeSnapshot` (API 33) : texte environnant + région de composition + capitales | sous-classe API 33 | P2 | 1 JH |
| R5 | `setImeConsumesInput` (API 34) + invalidation de connexion | sous-classe API 34 | P2 | 0,5 JH |
| R6 | Honorer `hintMaxChars` de la requête d'extraction | `EditorImeBridge.buildExtractedText` | P3 | 0,25 JH |
| R7 | Écart minimal de `setComposingText` (mesurer d'abord le coût d'épissure de la rope) | `ImeBridge` (core) | P3 | 1 à 2 JH |
| R8 | Détection du clavier matériel (masquage du clavier logiciel) + `onCheckIsTextEditor` dynamique (lecture seule) | `EditorView` | P3 | 0,5 JH |
| R9 | Hook de personnalisation de `EditorInfo` (écouteur facultatif) + `performPrivateCommand` routé | `EditorImeBridge`, nouvel événement | P3 | 0,5 JH |

## 5. Ordre de mise en œuvre

1. **R1 puis R2** en priorité absolue : tous deux touchent des chemins
   de code stables, sont couverts par des tests Robolectric faciles à
   écrire, et ferment les deux seuls écarts reproductibles en atelier.
2. **R3 à R5** ensuite : un utilisateur de `cel-ui` sur un appareil
   Android 14+ s'attend à ce que le stylet écrive et que la composition
   survive à une mort de processus.
3. **R6 à R9** au fil du retour terrain : rien ne casse sans eux, mais
   chacun supprime une friction mesurable.

Chaque correctif suit la convention du dépôt : un test qui échoue avant
et passe après, un commit par changement, la documentation resynchronisée.

## 6. Invariants à garder verts

Aucun de ces correctifs ne doit affaiblir les fondations du § 2 : la
composition reste possédée par le modèle, les mutations passent par le
pipeline unique (undo, diagnostics, plis), et l'économie des restarts
re fondée sur la divergence réelle du miroir — R1 ajoute un déclencheur
de plus (le changement de document entier), pas une politique de restart
en rafale. Les tests existants de composition (B21a, lots IME de
`EditorSession`) doivent rester verts à l'identique.
