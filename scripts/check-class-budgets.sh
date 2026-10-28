#!/bin/sh
# Garde-fou de budget de classes (Plan 01, étape S) : la décomposition
# des orchestrateurs ne doit jamais régresser en silence.
#
# Chaque plafond est la valeur mesurée au moment de l'étape S — il se
# rabat (ratchet) à chaque étape du plan 01/02 franchie, jamais vers
# le haut sans décision explicite (commit docs qui justifie).
#
# Usage (CI, après le build) : sh scripts/check-class-budgets.sh
# POSIX sh, sans dépendance. Les décomptes champs/méthodes sont des
# approximations déterministes (lignes de déclaration) : seule la
# STABILITÉ de la mesure importe pour un garde-fou de budget.
set -eu

cd "$(dirname "$0")/.."

echec=0

# budget <fichier> <plafond_lignes> <plafond_champs> <plafond_methodes_publiques>
budget() {
    fichier="$1"; plafond_lignes="$2"; plafond_champs="$3"; plafond_pub="$4"

    lignes=$(wc -l < "$fichier" | tr -d ' ')
    # Champs ≈ lignes de déclaration avec type + nom + (; ou =) et sans
    # parenthèse (exclut les méthodes et classes).
    champs=$(grep -cE '^[[:space:]]*(public|private|protected)[[:space:]][^;()]*[;=]' "$fichier" || true)
    # Méthodes publiques ≈ lignes publiques avec parenthèse, hors
    # class/interface/enum et constructeurs de la classe.
    pub=$(grep -E '^[[:space:]]*public[[:space:]][^(]*\(' "$fichier" \
        | grep -vcE 'class |interface |enum ' || true)

    nom=$(basename "$fichier" .java)
    ok=1
    [ "$lignes" -gt "$plafond_lignes" ] && ok=0
    [ "${champs:-0}" -gt "$plafond_champs" ] && ok=0
    [ "${pub:-0}" -gt "$plafond_pub" ] && ok=0

    if [ "$ok" -eq 1 ]; then
        echo "check-class-budgets: $nom OK — $lignes l. / $champs champs / $pub méthodes publiques (plafonds $plafond_lignes / $plafond_champs / $plafond_pub)"
    else
        echo "check-class-budgets: $nom DÉPASSE — $lignes l. / $champs champs / $pub méthodes publiques (plafonds $plafond_lignes / $plafond_champs / $plafond_pub)" >&2
        echo "  → décomposez (docs/plans/01, 02) ou rabaissez le plafond avec justification." >&2
        echec=1
    fi
}

# Plan 11 (2026-10, exigences du propriétaire avant fusion 4.0.0) : le
# plafond MÉTHODES PUBLIQUES d'EditorSession est relevé de 102 à 104 —
# deux API nouvelles du régime progressif des grands documents :
# hintViewport(first, last) (câblée par la vue, priorise la passe
# d'arrière-plan) et awaitPendingRestyle() rendue publique (les tests
# E2E d'équivalence du module démo l'exigent, hors package). Le
# décompte LIGNES baisse par ailleurs (extraction SyntaxSpanQuery).
# ── Plafonds = valeurs mesurées à l'étape S (Phase 2) ───────────────
# Écart au ratchet : plafond LIGNES d'EditorView relevé de 3350 à 4100
# par décision explicite du propriétaire (2026-10, session J2) — marge
# pour les jalons du lot 2.
# A-01 (audit 3.69.0) : le cliquet DESCEND — extraction de l'état
# Parcelable (EditorSavedState) : 3840 → 3799 lignes mesurées, plafond
# abaissé à 3810. Les relèvements futurs exigent la décision du
# propriétaire ; chaque extraction doit accompagner son abaissement.
# Plan 01, étape 2 (post-plan) : CompletionState extrait (popup de
# complétion : liste, sélection, fenêtre de scroll, cache par token +
# invariants possédés) — 3799 → 3779 lignes, 180 → 170 champs,
# 276 → 274 méthodes publiques ; plafonds abaissés à 3790 lignes et
# 180 champs (marge 11 lignes, style maison).
# Plan 01, étape 3 (post-plan) : FindState extrait (surlignages de
# recherche : occurrences + courante, invariant de bornes possédé) —
# 3779 → 3777 lignes, 170 → 169 champs ; plafond abaissé à 3788 lignes
# (marge 11) et 179 champs.
# Plan 01, étape 5 (post-plan) : ScrollState extrait (vOffset/hOffset,
# invariant [0, max] possédé, restauration bornée/brute) —
# 3777 → 3775 lignes, 169 → 168 champs ; plafond abaissé à 3786 lignes
# et 178 champs. (Étape 4 FoldViewState : constat vide — l'état de pli
# était déjà intégralement déménagé vers cel-core/FoldRegions et
# l'EditorFoldIndex lors des lots B14 : rien à extraire.)
# Écart au ratchet : plafond CHAMPS (170→190) et MÉTHODES PUBLIQUES
# (255→280) d'EditorView relevés par décision explicite du propriétaire
# (2026-10, session J3 — « Go pour J3, tu peux aussi augmenter le
# plafond et champ pour EditorView si nécessaire ») : I2 exige un
# resolver de tooltip d'inlay hints sur la vue (+1 champ, +1 setter),
# marge conservée pour J4 (T2 actions socle) ; les 4100 lignes restent
# le plafond de décomposition — le relèvement ne dispense PAS de
# décomposer via les collaborateurs existants.
# Écart au ratchet : plafond LIGNES relevé 3786 → 3850 (B26, retour
# terrain « sélecteur de couleur au tap ») : l'état du popup vit sur la
# vue (même convention que squigglePopup*/navMenu*/quickDoc — champs
# @RestrictTo lus par EditorColorPickerPopup/le painter/l'input),
# +2 relais d'API publique (setColorPickerEnabled/dismiss) et les
# fermetures onTextChanged/perte de focus ; la LOGIQUE est hors vue
# (popup, anchors, painter dédiés). Marge 12 lignes, style maison ;
# champs 176/178 et méthodes 276/280 inchangés — le relèvement ne
# dispense pas de la décomposition du plan 01.
# Écart au ratchet : plafond LIGNES relevé 3850 → 3920 (B27, carte
# d'aperçu « goutte d'eau » — maquette validée) : +3 relais d'API
# publique (openPreviewDropAt/closePreviewDrop/togglePreviewDrop —
# l'état ouvert se lit sur getPreviewMode() == DROP, aucun relais
# de confort), l'œil GELÉ au hit-test de gouttière (B9c étendu),
# la purge IME/setSession (R1 du plan 09) et le mode DROP de l'enum ;
# la LOGIQUE est hors vue (EditorPreviewDrop, Geometry pure testable,
# Painter dédiés — miroir des extractions du plan 01). Marge 12 lignes,
# style maison ; champs 177/178 et méthodes 280/280 inchangés — le
# relèvement ne dispense pas de la décomposition du plan 01.
# Écart au ratchet : plafonds LIGNES 3920 → 3965 et MÉTHODES 280 → 284
# (R8/R9 du plan 09, passe IME) : +2 relais d'API publique
# (setOnEditorInfoListener/setOnImePrivateCommandListener — les deux
# hooks hôte, écouteurs possédés par EditorImeBridge), les interfaces
# publiques imbriquées OnEditorInfoListener/OnImePrivateCommandListener
# (façade — l'état vit dans le pont), la politique clavier logiciel
# unifiée déléguée au pont (lecture seule E8 + clavier matériel E7,
# onCheckIsTextEditor/onConfigurationChanged/onFocusChanged) et le champ
# extractedTextMaxChars (R6, fenêtre hintMaxChars du moniteur) ; la
# LOGIQUE est hors vue (EditorImeBridge : politique, instantané,
# capitales, stylet). Marge 5 lignes, style maison ; champs 177/178
# inchangés — le relèvement ne dispense pas de la décomposition du
# plan 01.
# Écart au ratchet : plafonds LIGNES 3965 → 4045 et MÉTHODES 284 → 290
# (B27, options propriétaire — retour terrain n°2) : +4 relais d'API
# publique (setPreviewDropPolicy/getPreviewDropPolicy/setPreviewDropScale/
# getPreviewDropScale) et +1 enum public imbriqué PreviewDropPolicy
# (ORIGINALE défaut / TROIS couches / VARIANTE B — choix explicite du
# propriétaire en session), +2 lignes de plomberie applyPrefs ; l'ÉTAT
# vit dans EditorPreviewDrop (policy + cardScale, logique et clamp là),
# champs 177/178 INCHANGÉS ; la LOGIQUE est hors vue (géométrie pure,
# painter, résolveur) — miroir des extractions du plan 01. Marge 14
# lignes / 3 méthodes, style maison ; le relèvement ne dispense pas de
# la décomposition du plan 01.
# Écart au ratchet : plafonds LIGNES 4045 → 4165 et MÉTHODES 290 → 292
# (3.74.0, cycle de vie LSP et formatage) : +5 méthodes publiques exigées
# par la spécification 3.74.0 (canFormat/canFormatSelection —
# disponibilité du formatage pour piloter l'action « Formater » de
# l'hôte ; formatSelection ; setFormattingOptions — options posées par
# l'hôte ; +1 méthode default dans l'interface imbriquée
# FormatterResolver) et leurs javadoc @since ; l'ÉTAT ajouté se limite
# à 2 champs scalaires (options de formatage posées, déléguées au
# pont LSP), la LOGIQUE est hors vue (EditorDocumentActions porte le
# formatage, le SPI Formatter porte les plages) — miroir des
# extractions du plan 01. Marge ~10 lignes / 1 méthode, style maison ;
# le relèvement ne dispense pas de la décomposition du plan 01.
# Écart au ratchet : plafond LIGNES d'EditorView relevé 4165 → 4180
# (fusion 4.0.0 : combinaison des croissances légitimes des deux
# branches — main 3.74.0 : commandes de formatage, EditorView porté à
# 4153 l. (plafond 4165) ; feature/4.0 plan 11 : câblage
# hintViewport(first,last) au scroll pour le régime progressif des
# grands documents, +14 l. (plafond 4045 sur sa branche). La fusion
# des deux apports (4153 + 14 = 4167 mesurées) dépasse chaque plafond
# de branche pris seul — relevé au total combiné, marge 13 lignes,
# champs 177/180 et méthodes 291/292 INCHANGÉS. Le relèvement ne
# dispense pas de la décomposition du plan 01.
# Écart au ratchet (4.2.0, B28 — yeux ancrés aux lignes @Preview) : les
# plafonds LIGNES 4180 → 4225 et MÉTHODES 292 → 295 sont relevés par
# décision explicite : le contrat B28 EXIGE ses QUATRE relais publics
# sur la vue (setPreviewAnchors/getPreviewAnchors/hitTestGutterPreview
# Anchor/selectPreviewDropTab — l'API hôte de cel-ui 4.2.0, consommée
# par CodeIDE v0.177.0), miroir des 16 relais preview existants. L'ÉTAT
# (le champ, le tri/dédupliqué, le clamp) et la LOGIQUE (le hit-test
# déterministe) vivent dans EditorPreviewController — le détenteur de
# l'état d'aperçu créé pour ça (plan 01) ; EditorView ne conserve que
# les relais. Mesuré : 4213 l. / 177 champs / 295 méthodes (marges 12 /
# 3 / 0). Champs SOUS le plafond (177/180). Le relèvement ne dispense
# pas de la décomposition du plan 01.
# Écart au ratchet (4.3.0, complétion — fiabiliser le popup) : les
# plafonds LIGNES 4225 → 4270 et MÉTHODES 295 → 299 sont relevés par
# décision explicite : l'honorabilité du drapeau « liste tronquée » et
# le réglage du débounce de complétion EXIGENT leurs QUATRE relais
# publics sur la vue (setHonorIncompleteCompletion/
# isHonorIncompleteCompletion/setCompletionDebounceMs/
# getCompletionDebounceMs — réglages hôtes de cel-ui 4.3.0) ; la
# POLITIQUE (coalescence, re-requête, génération) vit dans
# EditorCompletionPopup, le résultat dans CompletionResult (classe
# dédiée) — EditorView ne conserve que les relais. L'interface interne
# CompletionProvider gagne sa variante résultat (provideResult, à
# défaut). Mesuré : 4270 l. / 177 champs / 299 méthodes (marges 0 / 3 /
# 0). Champs SOUS le plafond (177/180). Le relèvement ne dispense pas
# de la décomposition du plan 01.
# Écart au ratchet (4.4.0, occurrences du mot sous le caret) : les
# plafonds LIGNES 4270 → 4330 et MÉTHODES 299 → 301 sont relevés par
# décision explicite : la bascule publique EXIGE ses DEUX relais sur la
# vue (setWordOccurrencesEnabled/isWordOccurrencesEnabled — réglage
# hôte de cel-ui 4.4.0) ; l'ÉTAT (drapeau) et la LOGIQUE (débounce,
# cédage de la voie, coupure isLarge, règle « au moins deux ») vivent
# dans EditorWordOccurrences — collaborateur dédié miroir du contrôleur
# de recherche ; le calcul pur (mot, frontières, collecte) vit dans
# cel-core WordOccurrences. EditorView ne conserve que le champ
# package-private, le relais scheduleWordOccurrences(), les purgeurs
# setSession/onDetachedFromWindow et les DEUX relais publics. Mesuré :
# 4322 l. / 177 champs / 301 méthodes (marges 8 / 3 / 0). Champs SOUS
# le plafond (177/180). Le relèvement ne dispense pas de la
# décomposition du plan 01.
# Écart au ratchet (4.5.0, paliers P2+P3 du plan de corrections) : les
# plafonds LIGNES 4330 → 4480 et MÉTHODES 301 → 307 sont relevés par
# décision explicite : les SIX relais publics nouveaux sont des
# réglages hôtes exigés — apprentissage du classement
# (setCompletionRankingLearningEnabled/isCompletionRankingLearningEnabled/
# clearCompletionRankingHistory) et mode gros fichiers explicite
# (setLargeFileMode/getLargeFileMode/isLargeFileModeActive, l'énuméré
# imbriqué LargeFileMode ne compte pas). L'ÉTAT et la LOGIQUE vivent
# dans les collaborateurs : l'historique d'acceptation dans
# CompletionAcceptanceHistory (cel-core, capacité bornée + éviction),
# le régime gros fichiers dans l'énuméré + la consultation ponctuelle
# des coupures (EditorWordOccurrences, EditorCompletionPopup,
# EditorLanguageBridge, LspSemanticTokensPuller via EditorHost).
# La compensation du viewport vit dans compenserDecalageViewport
# (privée) branchée au listener existant. Mesuré : 4473 l. / 179
# champs / 307 méthodes (marges 7 / 1 / 0). Champs SOUS le plafond
# (179/180). Le relèvement ne dispense pas de la décomposition du
# plan 01.
# Écart au ratchet (4.6.0, bascule du renommage lié XML) : plafonds
# LIGNES 4480 → 4500 et MÉTHODES 307 → 309 relevés par décision
# explicite : le délégué setLinkedTagEditsEnabled/
# isLinkedTagEditsEnabled (javadoc + 2 relais d'une ligne vers la
# session, qui possède le chemin d'édition) ferme l'écart avec les
# autres réglages comportementaux de la vue (setLargeFileMode,
# setColorPickerEnabled, setCompletionRankingLearningEnabled…). Le
# retrait des dépréciations 4.3.0 dans la MÊME version a rendu le solde
# net NÉGATIF : les 4 relais dépréciés (setGoToLineText/acceptGoToLine/
# setRenameText/acceptRename) sont partis (-24 l., -4 méthodes
# publiques) — le plafond est RABATTU en conséquence (4500 → 4480 l.,
# 309 → 307 méthodes ; le délégué du renommage lié reste, mesuré
# 4474 l. / 179 champs / 305 méthodes, marges 6 / 1 / 2). Le cliquet
# continue de descendre avec la décomposition du plan 01.
# Écart au ratchet (4.8.0, badge de surcharges de l'aide de signature) :
# plafond LIGNES 4480 → 4520 relevé pour le relais public
# signatureHelpBadgeMetrics (+1 méthode, 306/307) — géométrie partagée
# peintre/hit-test sur le modèle quickDocMetrics ; le layout du popup
# et les ellipses vivent dans EditorAssistPopupPainter. Mesuré :
# 4487 l. / 179 champs / 306 méthodes.
# Écart au ratchet (4.8.0, plancher local de complétion) : plafonds
# LIGNES 4520 → 4550 et MÉTHODES 307 → 310 relevés pour la bascule
# publique setMergeBuiltinCompletionsEnabled/isMerge… (+2 méthodes,
# 308/310) — la fusion vit dans EditorCompletionPopup
# (mergeBuiltinFloor), la vue ne garde que le drapeau et les 2
# accesseurs. Mesuré : 4522 l. / 180 champs / 308 méthodes.
# Écart au ratchet (4.10.0, popup de signature une-surcharge-à-la-fois) :
# plafond MÉTHODES 310 → 315 relevé pour les relais publics du stepper
# ‹ i/n › (signatureHelpStepperMetrics remplace signatureHelpBadgeMetrics,
# solde +1) et du rectangle du popup (signatureHelpPopupMetrics, +1 —
# partagé rendu/geste pour l'appui long « peek »). Le layout, le
# fenêtrage et le peek vivent dans EditorAssistPopupPainter, l'état
# peek dans SignatureHelpController (cel-core, non plafonné). Mesuré :
# 4533 l. / 180 champs / 309 méthodes.
budget editor/cel-ui/src/main/java/jo/codeeditor/view/EditorView.java 4550 180 315

# Écart au ratchet (4.11.0, signature en trois paliers) : le peintre
# porte le rendu TROIS PALIERS des paramètres (déjà frappés en accent /
# actif en gras + accent / à venir en atténué — la frappe allume la
# signature branche par branche) : drawSignatureLine choisit le rendu
# complet quand la structure paramétrable tient, drawSignatureTiers /
# drawSignatureSegment dessinent les paliers, le dégradé pré/actif/post
# garde l'actif visible en pire cas. Relèvement LIGNES 1620 → 1660
# décidé par le propriétaire (session 4.11.0 — « augmenter le budget de
# certains fichiers si nécessaire »). La couture reste PURE :
# findParameterRangesInLabel est statique et testée sans Canvas, comme
# computeCompletionRowLayout et windowSignature. Mesuré : 1639 l. /
# 9 champs / 2 méthodes publiques.
budget editor/cel-ui/src/main/java/jo/codeeditor/view/render/EditorAssistPopupPainter.java 1660 10 3

# Écart au ratchet (4.10.0, repli local sur réponse vide) : NOUVEAU
# budget pour le contrôleur du popup d'aide de signature — le repli
# synthétique (appliquerRepliSynthetique) est isolé pour servir DEUX
# chemins (sans résolveur, et résolveur branché qui répond VIDE). Mesuré :
# 302 l. / 1 champ / 3 méthodes publiques.
budget editor/cel-ui/src/main/java/jo/codeeditor/view/popup/EditorSignaturePopup.java 340 3 5
# Écart au ratchet : plafonds LIGNES 1600 → 1610 et MÉTHODES 100 → 102
# (R2 du plan 09, IME) : +1 méthode publique (imeSelectedText — la voie
# IME bornée au plafond Binder, distincte de selectedText interne) et
# +7 lignes de javadoc/impl ; le reste de la session est inchangé.
# Écart au ratchet (4.5.0, renommage lié XML) : plafond LIGNES 1610 →
# 1660 relevé par décision explicite : les crochets de renommage lié
# dans typeChar/backspace (deux éditions en un lot d'annulation,
# offsets décroissants) et la garde de famille isXmlLinkedEditLanguage
# (privée) ; le SCANNER pur vit dans cel-core XmlLinkedTagEdits,
# exposé par la façade EditOps — la session ne garde que le lot et le
# caret. AUCUNE nouvelle méthode publique (104/104 inchangé), champs
# 10/15. Mesuré : 1651 l. Le relèvement ne dispense pas de la
# décomposition du plan 01.
# Écart au ratchet (4.6.0, bascule du renommage lié XML) : plafonds
# LIGNES 1660 → 1690 et MÉTHODES 104 → 106 relevés par décision
# explicite : la bascule publique setLinkedTagEditsEnabled/
# isLinkedTagEditsEnabled vit dans la SESSION car c'est elle qui
# possède le chemin d'édition (typeChar/backspace) où vit le miroir
# — un drapeau de vue ne pourrait pas l'atteindre. Le scanner pur
# reste dans XmlLinkedTagEdits (cel-core) ; la session ne garde que
# le drapeau, les 2 accesseurs et le garde-fou fermé dans
# isXmlLinkedEditLanguage (privée). Mesuré : 1689 l. / 11 champs /
# 106 méthodes. Le relèvement ne dispense pas de la décomposition
# du plan 01.
# Écart au ratchet (4.8.0, session vierge neutre) : plafond LIGNES
# 1690 → 1700 relevé pour le commentaire documentant le défaut
# « text » (l'ancien « java » silencieux envoyait mots-clés Java et
# postfix sur n'importe quel fichier). Aucune méthode ajoutée —
# mesuré 1694 l. / 11 champs / 106 méthodes.
budget editor/cel-core/src/main/java/jo/codeeditor/session/EditorSession.java 1700 15 106

# ── Règle de paquet (plan 03, §5) : le client LSP ne référence jamais
# la vue. cel-lsp programme contre EditorHost (cel-lsp-api) ;
# l'adaptateur de référence (LspEditorViewHost) vit côté cel-ui. La
# dépendance cel-lsp → cel-ui est RETIRÉE : toute régression (import,
# FQN, ou compileOnly) échoue le garde-fou.
if grep -rn "jo\.codeeditor\.view" editor/cel-lsp/src/main editor/cel-lsp-api/src/main 2>/dev/null; then
    echo "check-class-budgets: VIOLATION plan 03 — cel-lsp/cel-lsp-api référence la vue :" >&2
    echo "  → programmez contre EditorHost (cel-lsp-api) ; l'adaptateur vit dans cel-ui." >&2
    echec=1
fi
if grep -n 'compileOnly(project(":cel-ui"))' editor/cel-lsp/build.gradle.kts 2>/dev/null; then
    echo "check-class-budgets: VIOLATION plan 03 — cel-lsp dépend à nouveau de cel-ui (compileOnly)." >&2
    echec=1
fi

if [ "$echec" -ne 0 ]; then
    exit 1
fi
echo "check-class-budgets: conforme."
