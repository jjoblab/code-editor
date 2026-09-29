package jo.codeeditor.edit;

import java.util.HashMap;
import java.util.Map;

import jo.codeeditor.rope.Rope;

/**
 * Connaissance des paires de crochets et de guillemets utilisées par l'édition
 * intelligente. Centralise les tables ouvreurs↔fermeurs (dont dépendent
 * l'auto-close, le skip-over et l'expansion de paire vide) ainsi que le comptage
 * d'équilibre du document qui garde l'auto-close de smartInsert.
 */
final class BracketPairs {

    private BracketPairs() {}

    private static final Map<Character, Character> OPENER_TO_CLOSER = new HashMap<>();
    private static final Map<Character, Character> CLOSER_TO_OPENER = new HashMap<>();

    static {
        OPENER_TO_CLOSER.put('(' , ')');
        OPENER_TO_CLOSER.put('{' , '}');
        OPENER_TO_CLOSER.put('[' , ']');
        OPENER_TO_CLOSER.put('"' , '"');
        OPENER_TO_CLOSER.put('\'', '\'');
        for (var e : OPENER_TO_CLOSER.entrySet()) {
            CLOSER_TO_OPENER.put(e.getValue(), e.getKey());
        }
    }

    static char closerFor(char opener) {
        Character c = OPENER_TO_CLOSER.get(opener);
        return c != null ? c : '\0';
    }

    static char openerFor(char closer) {
        Character c = CLOSER_TO_OPENER.get(closer);
        return c != null ? c : '\0';
    }

    static boolean isOpener(char ch) {
        return OPENER_TO_CLOSER.containsKey(ch);
    }

    static boolean isCloser(char ch) {
        return CLOSER_TO_OPENER.containsKey(ch);
    }

    static boolean isPair(char open, char close) {
        Character c = OPENER_TO_CLOSER.get(open);
        return c != null && c == close;
    }

    // ── Comptage d'équilibre du document ────────────────────────

    /** Plafond de balayage du comptage d'équilibre (aussi consulté par le garde-fou de smartInsert). */
    static final int BALANCE_SCAN_LIMIT = 50_000;

    /**
     * Compte les ouvreurs moins les fermeurs dans le texte du document (jusqu'à BALANCE_SCAN_LIMIT caractères).
     *
     * <p>Port amont {@code c4bec0cf7} : quand le texte est une {@link Rope},
     * le scan passe <b>feuille par feuille</b> (accès direct au String de
     * chaque feuille, O(n) total) au lieu de {@code charAt(i)} qui redescend
     * l'arbre à chaque caractère (O(n·log n)). Le résultat est STRICTEMENT
     * identique — seules les feuilles couvrant la fenêtre bornée sont
     * visitées, dans l'ordre du document.</p>
     */
    static int docBalance(CharSequence text, char opener, char closer) {
        int limit = Math.min(text.length(), BALANCE_SCAN_LIMIT);
        if (text instanceof Rope) {
            int[] count = {0};
            int[] remaining = {limit};
            ((Rope) text).visitLeaves(0, limit, (data, start) -> {
                int end = Math.min(data.length(), remaining[0]);
                for (int i = 0; i < end; i++) {
                    char ch = data.charAt(i);
                    if (ch == opener) count[0]++;
                    else if (ch == closer) count[0]--;
                }
                remaining[0] -= end;
                return remaining[0] > 0;
            });
            return count[0];
        }
        int count = 0;
        for (int i = 0; i < limit; i++) {
            char ch = text.charAt(i);
            if (ch == opener) count++;
            else if (ch == closer) count--;
        }
        return count;
    }
}
