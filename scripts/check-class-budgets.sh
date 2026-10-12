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

# ── Plafonds = valeurs mesurées à l'étape S (Phase 2) ───────────────
# Écart au ratchet : plafond LIGNES d'EditorView relevé de 3350 à 4100
# par décision explicite du propriétaire (2026-10, session J2) — marge
# pour les jalons du lot 2 ; les plafonds champs/méthodes restent aux
# valeurs mesurées pour préserver la discipline de décomposition.
budget editor/cel-ui/src/main/java/jo/codeeditor/view/EditorView.java 4100 170 255
budget editor/cel-core/src/main/java/jo/codeeditor/session/EditorSession.java 1600 15 100

if [ "$echec" -ne 0 ]; then
    exit 1
fi
echo "check-class-budgets: conforme."
