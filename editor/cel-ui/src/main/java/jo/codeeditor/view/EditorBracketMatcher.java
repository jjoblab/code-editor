package jo.codeeditor.view;

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
     * naïf ignorant chaînes/commentaires — suffisant pour un surlignage.
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
                while (i < limit) {
                    char c = text.charAt(i);
                    if (c == ch) depth++;
                    else if (c == close) {
                        depth--;
                        if (depth == 0) return new int[]{probe, i};
                    }
                    i++;
                }
            } else {
                char open = openOf(ch);
                if (open == 0) continue;
                int depth = 0;
                int i = probe;
                int limit = Math.max(0, probe - EditorView.BRACKET_SCAN_LIMIT);
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
