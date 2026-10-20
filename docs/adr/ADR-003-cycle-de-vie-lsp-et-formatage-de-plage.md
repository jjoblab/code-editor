# ADR-003 — Cycle de vie du langage LSP et formatage de plage

- **Statut** : accepté
- **Date** : 2026-10-20
- **Décideur** : propriétaire du dépôt (mission 3.74.0)

## Contexte

Les applications hôtes réutilisent UNE `EditorView` pour tous leurs
onglets (vue unique, documents interchangeables). Le constat d'audit
3.74.0 a vérifié dans le code un défaut de cycle de vie et trois lacunes
de formatage :

| # | Constat | Effet vécu |
|---|---------|------------|
| C1 | `disconnect()` ne retire pas le langage de la vue (`setLanguage` n'est appelé que par `connect()`) | onglet `.java` connecté → onglet `.txt` sans serveur : le langage LSP du fichier Java RESTE branché — complétion, survol, signatures, renommage, formateur, indices en ligne, occurrences, actions de code repartent avec l'URI du fichier fermé mais le texte du nouvel onglet |
| C1b | `disconnect()` retourne immédiatement si non connecté, `connect()` est asynchrone | changement d'onglet PENDANT la poignée de main : la continuation branche quand même le langage du fichier fermé sur la vue du nouvel onglet |
| C1c | l'écouteur d'édition posé par `connect()` n'est jamais retiré | fuite d'écouteurs à chaque cycle connect/disconnect |
| C3 | `format(text, start, end)` ignore la plage ; le pont passe toujours `0..length` | `textDocument/rangeFormatting` jamais émis alors que le serveur l'annonce ; formater une sélection formate le document ENTIER |
| C5 | `formatterResolver` inaccessible à l'hôte | l'application hôte ne peut ni masquer ni griser son action « Formater » — elle appique et espère |

L'invariant voulu (et déjà documenté par le commentaire de
`EmptyLanguage`, mais appliqué nulle part à la déconnexion) :
**sans langage LSP connecté à la vue, TOUTES les capacités LSP sont
fermées** — aucun résolveur actif, aucune requête émise. Une capacité ne
s'ouvre que si le serveur du type de fichier courant l'annonce ET qu'elle
n'est pas désactivée (`LspFeature`).

## Options

**O1 — Contournement dans chaque application hôte.** Le coordinateur LSP
de l'hôte appellerait `setLanguage(null)` lui-même à chaque changement
d'onglet, maintiendrait son propre drapeau de connexion, désactiverait
l'action « Formater » en interrogeant son propre état.
- Pour : aucun changement de bibliothèque.
- Contre : chaque hôte duplique le même code fragile (l'ordre
  disconnect-then-setLanguage est une connaissance interne de la
  bibliothèque) ; la course C1b reste (le contournement s'exécute aussi
  sur le thread UI pendant que la continuation pend) ; le défaut C1c
  (écouteur) reste ; un hôte qui oublie UN chemin (rotation, onglet
  fermé par glissement) rouvre la fuite. La démo du dépôt elle-même
  aurait eu besoin de ce contournement.

**O2 — Correction dans la bibliothèque.** `disconnect()` devient le
miroir complet de `connect()` : restaure `EmptyLanguage` via
`host.setLanguage(null)` (même règle de thread), retire l'écouteur,
invalide un jeton de génération pour neutraliser les continuations
pendantes ; un garde commun rend tout `Lsp*Provider` muet une fois la
session fermée ; le SPI du formateur gagne la capacité de plage
(`supportsRange()`), la vue l'expose (`canFormat()` /
`canFormatSelection()`, `formatSelection()`) et le pont transmet la
sélection réelle.
- Pour : l'invariant est garanti PAR CONSTRUCTION pour tout hôte, y
  compris ceux qui ne connaissent pas le défaut ; chaque hôte supprime
  son contournement au lieu d'en écrire un ; l'API reste additive
  (méthodes `default`, nouvelles méthodes — aucune rupture).
- Contre : plus de surface de code dans la bibliothèque ; la règle de
  thread de la restauration (hôte attaché → poster sur le thread UI,
  hôte détaché → exécuter en ligne) doit réutiliser exactement celle de
  `connect()` pour ne pas créer de nouvel ordre de course.

## Décision

**O2 — correction dans la bibliothèque**, retenue par le propriétaire
(mission 3.74.0). Les trois volets :

1. **Cycle de vie (C1/C1b/C1c).** `LspEditor.disconnect()` est
   idempotent et referme TOUT : `didClose`, restauration
   `EmptyLanguage` (`host.setLanguage(null)`), retrait de l'écouteur
   d'édition, remise à `null` du langage. Un **jeton de génération**
   incrémenté à chaque `connect()` ET chaque `disconnect()` : une
   continuation de `connect()` dont le jeton ne correspond plus s'abandonne
   (ni langage, ni écouteur, ni `connected = true`, `didClose` de
   rattrapage si `didOpen` était parti). Un **garde commun**
   (`readyServer()`) rend tous les `Lsp*Provider` muets une fois la
   session fermée — défense en profondeur, une seule implémentation.
   La coloration lexicale, les plis lexicaux, la frappe intelligente et
   l'IME ne sont PAS touchés : ils ne dépendent pas de LSP.
2. **Formatage de plage (C3).** Le SPI `Formatter` gagne
   `default boolean supportsRange()` (faux par défaut) ;
   `LspFormatter` l'annonce quand le serveur négocie
   `documentRangeFormattingProvider`. Plage partielle + serveur capable
   → `textDocument/rangeFormatting` ; serveur INCAPABLE → **refus
   propre** (texte inchangé, aucune requête : formater le document
   entier à la place serait une surprise destructive). **La porte
   `FORMATTING` de `LspFeature` reste inchangée** : aucune nouvelle
   valeur d'énumération, la parité avec les énumérations des
   applications hôtes doit rester intacte.
3. **Disponibilité (C5).** `EditorView.canFormat()` /
   `canFormatSelection()` exposent l'état réel du résolveur à l'hôte
   (griser l'action), `formatSelection(Consumer<Boolean>)` applique le
   résultat en un seul pas d'undo avec conservation du caret.

## Conséquences

- **Positives** : l'invariant « sans langage connecté, toutes les
  capacités LSP fermées » est garanti par construction et testé
  (java → sans serveur → java ; java → xml ; connect puis disconnect
  immédiat ; double disconnect) ; les hôtes à vue unique changent
  d'onglet sans contournement ; le formatage de sélection distingue
  refus de plage et absence de formateur dans le rappel
  (`FALSE` vs `null`) ; les échecs du formateur sont journalisés
  (délai, exception, résultat vide) sans jamais fuiter le contenu du
  document, et les éditions serveur sont validées (bornes,
  chevauchements) avant application.
- **Négatives / acceptées** : `disconnect()` devient plus qu'une
  notification réseau (coût : le changement de langage déclenche la
  détection lexicale du document suivant — mesuré comme négligeable,
  c'est le même coût qu'un changement de langage explicite) ; un hôte
  qui appelait déjà `setLanguage(null)` de son côté reste compatible
  (l'appel devient redondant mais inoffensif — idempotent) ; un serveur
  sans formateur de plage rend `canFormatSelection()` faux — c'est le
  comportement demandé (ex. serveur sans formateur : l'action de
  sélection se grise, l'action de document reste gouvernée par
  `canFormat()`).
- **Suivi** : la note de passage 3.74.0 (docs/MISE-A-JOUR-CODEIDE.md)
  documente pour l'application hôte la migration — notamment le retrait
  de tout contournement `setLanguage(null)` local.
