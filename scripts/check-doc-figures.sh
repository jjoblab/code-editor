#!/bin/sh
# Contrôle des chiffres documentés (Lot 8) : le nombre de tests annoncé
# dans README.md doit coller (±5 %) au nombre de tests réellement
# exécutés, lu dans les rapports XML de testDebugUnitTest.
#
# Usage (CI, APRÈS l'étape de tests) :
#   ./gradlew testDebugUnitTest
#   scripts/check-doc-figures.sh
#
# POSIX sh, sans dépendance hors git/grep/sed/awk.
set -eu

cd "$(dirname "$0")/.."

# ── 1. Total réellement exécuté (somme des tests= des XML) ──────────
if ! ls editor/*/build/test-results/testDebugUnitTest/TEST-*.xml >/dev/null 2>&1 \
   && ! ls demo/build/test-results/testDebugUnitTest/TEST-*.xml >/dev/null 2>&1; then
    echo "check-doc-figures: AUCUN rapport de test trouvé — lancez testDebugUnitTest d'abord." >&2
    exit 1
fi

total=0
for rapport in editor/*/build/test-results/testDebugUnitTest/TEST-*.xml \
               demo/build/test-results/testDebugUnitTest/TEST-*.xml; do
    [ -f "$rapport" ] || continue
    n=$(sed -n 's/.*<testsuite[^>]* tests="\([0-9]\+\)".*/\1/p' "$rapport" | head -1)
    [ -n "$n" ] || n=0
    total=$((total + n))
done

if [ "$total" -eq 0 ]; then
    echo "check-doc-figures: rapports présents mais aucun test compté." >&2
    exit 1
fi

# ── 2. Nombre annoncé dans README.md ────────────────────────────────
# Le format français sépare les milliers par une espace (« 1 370 ») :
# on capture la séquence de chiffres/espaces précédant « tests ».
annonce=$(grep -m1 -o '[0-9][0-9 ]*tests' README.md | tr -d ' ' | grep -o '[0-9]\+' || true)
if [ -z "$annonce" ]; then
    echo "check-doc-figures: README.md n'annonce aucun total de tests (motif « N tests »)." >&2
    exit 1
fi

# ── 3. Écart toléré : 5 % ───────────────────────────────────────────
# (tolérance pour absorber les tests paramétrés/exécutés en double sans
# faire échouer la CI au moindre ajout — l'écart se resynchronise à
# chaque mise à jour du README.)
ecart_max=$((annonce / 20))  # 5 %
ecart=$((total - annonce))
[ "$ecart" -lt 0 ] && ecart=$((-ecart))

echo "check-doc-figures: annoncé=$annonce exécuté=$total écart=$ecart (toléré $ecart_max)"

if [ "$ecart" -gt "$ecart_max" ]; then
    echo "check-doc-figures: ÉCART EXCESSIF — mettez à jour le total annoncé dans" >&2
    echo "  README.md (et FEATURES.md / CONTRIBUTING.md / AGENT.md si listés)." >&2
    exit 1
fi

echo "check-doc-figures: conforme."
