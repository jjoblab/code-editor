package jo.codeeditor.view;

import jo.codeeditor.rope.Rope;

/**
 * Appariement de parenthèses — balayage de profondeur naïf, borné.
 *
 * <p>Responsabilité déplacée à l'identique depuis {@link EditorView} :</p>
 * <ul>
 *   <li><b>matchingBracket</b> — trouve la parenthèse appariée pour celle
 *       immédiatement AVANT ou SUR {@code caret}, comme
 *       {@code [openOffset, closeOffset]}, ou null. Sonde d'abord
 *       {@code caret-1} (curseur juste après une parenthèse fermante
 *       surligne l'ouvrante correspondante), puis {@code caret} (curseur
 *       SUR une ouvrante surligne la fermante) ;</li>
 *   <li><b>closeOf / openOf</b> — tables ouvrante↔fermante pour
 *       {@code () [] {}}.</li>
 * </ul>
 *
 * <p>Le balayage ignore chaînes/commentaires (suffisant pour un
 * surlignage) et est plafonné par {@link EditorView#BRACKET_SCAN_LIMIT}
 * pour qu'une parenthèse non appariée dans un énorme fichier ne coûte pas
 * O(N) par frappe. EditorView conserve l'état {@code bracketPair} (lu par
 * le painter de surlignage et les tests), le recalcul
 * {@code updateBracketPair()} et le relais statique
 * {@code EditorView.matchingBracket} (appelé par les tests).</p>
 */
final class EditorBracketMatcher {

    private EditorBracketMatcher() {
        // Utilitaire statique — pas d'instance.
    }

    /**
     * Trouve la parenthèse appariée pour celle immédiatement AVANT ou SUR
     * {@code caret}, comme {@code [openOffset, closeOffset]}, ou null.
     *
     * <p>Sonde d'abord {@code caret-1} (curseur juste après une
     * parenthèse fermante comme {@code }|} ou {@code )|} surligne
     * l'ouvrante correspondante), puis {@code caret} (curseur SUR une
     * parenthèse ouvrante surligne la fermante). Balayage de profondeur
     * naïf ignorant chaînes/commentaires — suffisant pour un surlignage.</p>
     *
     * <p>Port amont {@code c4bec0cf7} : quand le texte est une {@link Rope},
     * les scans AVANT et ARRIÈRE visitent les feuilles couvrant la fenêtre
     * bornée (accès direct au String de chaque feuille, O(feuilles + fenêtre))
     * au lieu de {@code charAt(i)} qui redescend l'arbre à chaque caractère
     * (O(fenêtre·log n)). Résultat STRICTEMENT identique — le visiteur clippe
     * sa fenêtre aux positions globales couvertes par chaque feuille, le
     * premier appariement gagnant interrompt la visite.</p>
     */
    static int[] matchingBracket(CharSequence text, int caret) {
        if (text == null) return null;
        for (int probe : new int[]{caret - 1, caret}) {
            if (probe < 0 || probe >= text.length()) continue;
            char ch = text.charAt(probe);
            char close = closeOf(ch);
            if (close != 0) {
                int depth = 0;
                int i = probe;
                int limit = Math.min(text.length(), probe + EditorView.BRACKET_SCAN_LIMIT);
                if (text instanceof Rope) {
                    // Scan AVANT feuille par feuille : chaque feuille est
                    // scannée en accès direct — arrêt au 1er appariement.
                    int[] depthBox = {0};
                    int[] found = {-1};
                    int lo = probe, hi = limit;
                    ((Rope) text).visitLeaves(lo, hi, (data, start) -> {
                        int from = Math.max(0, lo - start);
                        int to = Math.min(data.length(), hi - start);
                        for (int k = from; k < to; k++) {
                            char c = data.charAt(k);
                            if (c == ch) depthBox[0]++;
                            else if (c == close) {
                                depthBox[0]--;
                                if (depthBox[0] == 0) {
                                    found[0] = start + k;
                                    return false; // premier appariement gagnant
                                }
                            }
                        }
                        return true;
                    });
                    if (found[0] >= 0) return new int[]{probe, found[0]};
                } else {
                    while (i < limit) {
                        char c = text.charAt(i);
                        if (c == ch) depth++;
                        else if (c == close) {
                            depth--;
                            if (depth == 0) return new int[]{probe, i};
                        }
                        i++;
                    }
                }
            } else {
                char open = openOf(ch);
                if (open == 0) continue;
                int depth = 0;
                int i = probe;
                int limit = Math.max(0, probe - EditorView.BRACKET_SCAN_LIMIT);
                if (text instanceof Rope) {
                    // Scan ARRIÈRE feuille par feuille : les feuilles
                    // couvrant [limit, probe+1) sont visitées en ordre
                    // DÉCROISSANT, chacune scannée de sa fin vers son début.
                    int[] depthBox = {0};
                    int[] found = {-1};
                    int lo = limit, hi = probe + 1;
                    ((Rope) text).visitLeavesBackward(lo, hi, (data, start) -> {
                        int to = Math.min(data.length(), hi - start); // exclusif
                        int from = Math.max(0, lo - start);
                        for (int k = to - 1; k >= from; k--) {
                            char c = data.charAt(k);
                            if (c == ch) depthBox[0]++;
                            else if (c == open) {
                                depthBox[0]--;
                                if (depthBox[0] == 0) {
                                    found[0] = start + k;
                                    return false; // 1er appariement en arrière
                                }
                            }
                        }
                        return true;
                    });
                    if (found[0] >= 0) return new int[]{found[0], probe};
                } else {
                    while (i >= limit) {
                        char c = text.charAt(i);
                        if (c == ch) depth++;
                        else if (c == open) {
                            depth--;
                            if (depth == 0) return new int[]{i, probe};
                        }
                        i--;
                    }
                }
            }
        }
        return null;
    }

    /** Retourne la parenthèse fermante pour une ouvrante, ou 0 si ce n'est pas une parenthèse. */
    private static char closeOf(char c) {
        switch (c) {
            case '(': return ')';
            case '[': return ']';
            case '{': return '}';
            default: return 0;
        }
    }

    /** Retourne la parenthèse ouvrante pour une fermante, ou 0 si ce n'est pas une parenthèse. */
    private static char openOf(char c) {
        switch (c) {
            case ')': return '(';
            case ']': return '[';
            case '}': return '{';
            default: return 0;
        }
    }
}
