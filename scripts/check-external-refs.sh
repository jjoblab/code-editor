#!/bin/sh
# check-external-refs.sh — garde-fou anti-références à des projets externes.
#
# Sort avec un code NON NUL si l'arbre de travail, les messages de commit
# à pousser, les noms et messages de tags contiennent :
#   (i)   une URL vers un dépôt qui n'appartient pas au propriétaire
#         (github.com/<owner>/… avec owner hors liste d'autorisation) ;
#   (ii)  une formule d'inspiration ou de comparaison (liste versionnée
#         ci-dessous, affinable via scripts/check-external-refs.exceptions) ;
#   (iii) un motif nominatif lu dans la variable d'environnement
#         FORBIDDEN_REFS_EXTRA (jamais committée dans ce dépôt).
#
# Aucun nom de projet externe n'apparaît dans ce script : le contrôle
# nominatif repose exclusivement sur FORBIDDEN_REFS_EXTRA, fournie par
# l'environnement (secret CI ou variable locale).
#
# Usage :
#   scripts/check-external-refs.sh                 # arbre + commits à pousser + tags
#   scripts/check-external-refs.sh --all           # + tous les messages de commit
#   scripts/check-external-refs.sh --message F     # + message de commit du fichier F
#   scripts/check-external-refs.sh --check-file F  # vérifie un seul fichier (tests)
#
# Codes de sortie : 0 = conforme ; 1 = violations ; 2 = erreur d'usage.
# Dépendances : git, grep -E, sed, awk, mktemp. Interpréteur POSIX sh.

# ── Liste d'autorisation des propriétaires GitHub ───────────────────────
# jjoblab : le propriétaire de ce dépôt (repo, packages, artefacts).
# gradle  : le wrapper Gradle officiel (fichier `gradlew` généré).
# actions / gradle-actions : les actions GitHub réellement utilisées par
# les workflows CI de ce dépôt (épinglées par SHA).
# La documentation officielle (docs.gradle.org, developer.android.com,
# dl.google.com, keepachangelog.com, semver.org…) n'est pas hébergée sur
# github.com et ne peut pas déclenquer (i).
ALLOWED_OWNERS='^(jjoblab|gradle|actions|gradle-actions)$'

# ── Formules d'inspiration / comparaison (génériques, versionnées) ──────
# Frontières de mots (\b) pour éviter « viewport de », « support de »…
# Ajuster avec parcimonie ; le bruit résiduel justifié se déclare dans
# scripts/check-external-refs.exceptions (une regex ERE par ligne).
FORMULAS='\binspir[ée]e?s? d(e|\x27)\b|\bmieux que\b|\béquivalent de\b|\bà la manière de\b|\bport de\b|\bporté de\b|\bported from\b|\bbased on\b|\bsame pattern as\b|\bsame approach as\b|\bsame as\b|\bapproche amont\b|\bport amont\b|\bupstream\b|\brésumé de lecture\b'

# ── Fichier d'exceptions (regex ERE, une par ligne) ─────────────────────
# Une ligne signalée est ignorée si elle matche une de ces expressions ;
# chaque exception doit être justifiée par un commentaire adjacent.
EXCEPTIONS_FILE="$(dirname "$0")/check-external-refs.exceptions"

FORBIDDEN_EXTRA="${FORBIDDEN_REFS_EXTRA:-}"

usage() {
    echo "usage: $0 [--all] [--message <fichier>] [--check-file <fichier>]" >&2
    exit 2
}

# ── Arguments ────────────────────────────────────────────────────────────
CHECK_ALL=0
MESSAGE_FILE=""
CHECK_FILE=""
while [ $# -gt 0 ]; do
    case "$1" in
        --all) CHECK_ALL=1 ;;
        --message) [ $# -ge 2 ] || usage; MESSAGE_FILE="$2"; shift ;;
        --check-file) [ $# -ge 2 ] || usage; CHECK_FILE="$2"; shift ;;
        *) usage ;;
    esac
    shift
done

TMP=$(mktemp) || { echo "mktemp indisponible" >&2; exit 2; }
trap 'rm -f "$TMP" "$TMP.f" 2>/dev/null' EXIT

IN_REPO=0
git rev-parse --is-inside-work-tree >/dev/null 2>&1 && IN_REPO=1

# ── 1. Arbre de travail (fichiers suivis, texte uniquement) ─────────────
if [ -n "$CHECK_FILE" ]; then
    FILES="$CHECK_FILE"
elif [ "$IN_REPO" = "1" ]; then
    FILES=$(git ls-files 2>/dev/null)
else
    FILES=""
fi
for f in $FILES; do
    [ -f "$f" ] || continue
    # Le fichier d'exceptions ne doit jamais être scanné : ses propres
    # motifs contiennent les formules à détecter. $0 peut être absolu
    # (hook commit-msg) ou relatif (CI) — on neutralise la forme du
    # chemin : le nom de base est unique dans le dépôt.
    case "/$f" in
        */check-external-refs.exceptions) continue ;;
    esac
    # (i) URLs GitHub : ligne gardée si un owner non autorisé y apparaît.
    grep -nE 'github\.com/[A-Za-z0-9_.-]+/' "$f" 2>/dev/null \
        | while IFS= read -r line; do
            owner=$(printf '%s\n' "$line" | sed -n 's/.*github\.com\/\([A-Za-z0-9_.-]\+\).*/\1/p' | head -n 1)
            if ! printf '%s\n' "$owner" | grep -Eq "$ALLOWED_OWNERS"; then
                printf '%s:URL github.com/%s\n' "$f" "$owner"
            fi
          done >> "$TMP"
    # (ii) formules.
    grep -inE "$FORMULAS" "$f" 2>/dev/null | sed "s|^|$f:|" >> "$TMP"
done

# ── 2. Messages de commit ───────────────────────────────────────────────
if [ "$IN_REPO" = "1" ]; then
    RANGE=""
    UPSTREAM=$(git rev-parse --abbrev-ref '@{upstream}' 2>/dev/null) || UPSTREAM=""
    if [ -n "$UPSTREAM" ] && [ "$UPSTREAM" != "no upstream" ]; then
        RANGE="$UPSTREAM..HEAD"
    fi
    if [ "$CHECK_ALL" = "1" ]; then
        RANGE="--all"
        [ -n "$UPSTREAM" ] && [ "$UPSTREAM" != "no upstream" ] && RANGE="$RANGE"
    fi
    if [ -n "$RANGE" ]; then
        git log $RANGE --format='COMMIT %h %s' 2>/dev/null | grep -iE "$FORMULAS" >> "$TMP"
        git log $RANGE --format='COMMIT %h %s' 2>/dev/null \
            | while IFS= read -r line; do
                owner=$(printf '%s\n' "$line" | sed -n 's/.*github\.com\/\([A-Za-z0-9_.-]\+\).*/\1/p' | head -n 1)
                if [ -n "$owner" ] && ! printf '%s\n' "$owner" | grep -Eq "$ALLOWED_OWNERS"; then
                    printf '%s:URL github.com/%s\n' "$line" "$owner"
                fi
              done >> "$TMP"
    fi
    # ── 3. Noms et messages de tags ─────────────────────────────────────
    for t in $(git tag -l 2>/dev/null); do
        printf 'TAG %s\n' "$t" | grep -iE "$FORMULAS" >> "$TMP"
        git tag -l --format='%(contents)' "$t" 2>/dev/null \
            | grep -iE "$FORMULAS" | sed "s/^/TAG $t: /" >> "$TMP"
        git tag -l --format='%(contents)' "$t" 2>/dev/null \
            | while IFS= read -r line; do
                owner=$(printf '%s\n' "$line" | sed -n 's/.*github\.com\/\([A-Za-z0-9_.-]\+\).*/\1/p' | head -n 1)
                if [ -n "$owner" ] && ! printf '%s\n' "$owner" | grep -Eq "$ALLOWED_OWNERS"; then
                    printf 'TAG %s:URL github.com/%s\n' "$t" "$owner"
                fi
              done >> "$TMP"
    done
fi

# ── 4. Message de commit fourni (hook commit-msg) ───────────────────────
if [ -n "$MESSAGE_FILE" ] && [ -f "$MESSAGE_FILE" ]; then
    grep -inE "$FORMULAS" "$MESSAGE_FILE" | sed 's/^/MESSAGE:/' >> "$TMP"
    grep -nE 'github\.com/[A-Za-z0-9_.-]+/' "$MESSAGE_FILE" | sed 's/^/MESSAGE:/' >> "$TMP"
fi

# ── 5. Filtrage par exceptions ───────────────────────────────────────────
if [ -f "$EXCEPTIONS_FILE" ]; then
    awk -v exfile="$EXCEPTIONS_FILE" '
        BEGIN { n = 0; while ((getline line < exfile) > 0) {
            sub(/\r$/, "", line);
            if (line == "" || line ~ /^#/) continue;
            n++; pats[n] = line;
        } }
        {
            keep = 1
            for (i = 1; i <= n; i++) if ($0 ~ pats[i]) { keep = 0; break }
            if (keep) print $0
        }' < "$TMP" > "$TMP.f"
    mv "$TMP.f" "$TMP"
fi

# ── 6. Contrôle nominatif (FORBIDDEN_REFS_EXTRA) ────────────────────────
if [ -n "$FORBIDDEN_EXTRA" ]; then
    while IFS= read -r line; do
        if printf '%s\n' "$line" | grep -iEq "$FORBIDDEN_EXTRA"; then
            echo "VIOLATION [nominatif] $line" >&2
            echo "violation nominative" >> "$TMP"
        fi
    done < "$TMP"
    if [ -n "$CHECK_FILE" ] && grep -iEq "$FORBIDDEN_EXTRA" "$CHECK_FILE"; then
        echo "VIOLATION [nominatif] $CHECK_FILE" >&2
        echo "violation nominative" >> "$TMP"
    fi
else
    echo "note: FORBIDDEN_REFS_EXTRA absente — contrôle nominatif ignoré (à fournir en CI via secret)." >&2
fi

# ── 7. Verdict ───────────────────────────────────────────────────────────
VIOLATIONS=$(wc -l < "$TMP" 2>/dev/null || printf '0')
if [ "$VIOLATIONS" -gt 0 ]; then
    echo "check-external-refs: $VIOLATIONS occurrence(s) après exceptions :" >&2
    cat "$TMP" >&2
    exit 1
fi
echo "check-external-refs: conforme (arbre, commits, tags vérifiés)." >&2
exit 0
