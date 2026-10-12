#!/bin/sh
# check-external-refs-test.sh — tests du garde-fou avec un motif FICTIF.
# Le motif injecté n'est le nom d'aucun projet réel ; il démontre le
# fonctionnement du contrôle nominatif sans jamais l'écrire dans le dépôt
# (la variable d'environnement vit le temps du test).
set -u
SCRIPT="$(dirname "$0")/check-external-refs.sh"
WORK=$(mktemp -d) || exit 2
trap 'rm -rf "$WORK" "$WORK/fic" "$WORK/ok"' EXIT
rc=0

check() {  # $1 = attendu (0/1), $2 = description, le reste = commande
    want=$1; desc=$2; shift 2
    "$@" >/dev/null 2>&1
    got=$?
    [ "$got" = "$want" ] && echo "PASS: $desc" || { echo "FAIL: $desc (exit $got, attendu $want)"; rc=1; }
}

# Fichier factice contenant le motif fictif.
printf 'ligne avec motif zqxjkwv pour le test\n' > "$WORK/fic"
# Fichier propre.
printf 'rien de suspect ici\n' > "$WORK/ok"

echo "== 1. arbre propre =="
sh "$SCRIPT" >/dev/null 2>&1; r=$?
echo "arbre: exit $r (0 attendu — sinon ajuster les exceptions)"

echo "== 2. contrôle nominatif via FORBIDDEN_REFS_EXTRA =="
FORBIDDEN_REFS_EXTRA='zqxjkwv' check 1 "fichier avec motif fictif détecté" \
    env FORBIDDEN_REFS_EXTRA='zqxjkwv' sh "$SCRIPT" --check-file "$WORK/fic"
FORBIDDEN_REFS_EXTRA='zqxjkwv' check 0 "fichier propre accepté" \
    env FORBIDDEN_REFS_EXTRA='zqxjkwv' sh "$SCRIPT" --check-file "$WORK/ok"
check 0 "sans variable: pas d'échec du contrôle nominatif" \
    env -u FORBIDDEN_REFS_EXTRA sh "$SCRIPT" --check-file "$WORK/fic"

echo "== 3. URL de dépôt externe =="
printf 'voir https://github.com/un-autre-owner/un-repo\n' > "$WORK/fic"
check 1 "URL github owner non autorisé détectée" \
    sh "$SCRIPT" --check-file "$WORK/fic"
printf 'voir https://github.com/jjoblab/code-editor\n' > "$WORK/fic"
check 0 "URL du dépôt du propriétaire acceptée" \
    sh "$SCRIPT" --check-file "$WORK/fic"

echo "== 4. formule d'inspiration =="
printf ' // cette astuce est inspirée de X\n' > "$WORK/fic"
check 1 "formule « inspirée de » détectée" \
    sh "$SCRIPT" --check-file "$WORK/fic"
printf ' // dessine le viewport de la minimap\n' > "$WORK/fic"
check 0 "« viewport de » non signalé (frontière de mot)" \
    sh "$SCRIPT" --check-file "$WORK/fic"

echo "== 5. message de commit =="
printf 'fix(x): correctif inspiré de la doc\n' > "$WORK/msg"
check 1 "message avec formule détecté" \
    sh "$SCRIPT" --message "$WORK/msg" --check-file "$WORK/ok"

[ "$rc" = "0" ] && echo "TOUT PASSER" || echo "ÉCHECS PRÉSENTS"
exit "$rc"
