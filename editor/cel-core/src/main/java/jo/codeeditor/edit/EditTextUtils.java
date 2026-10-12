package jo.codeeditor.edit;

/**
 * Utilitaires de balayage de texte partagés par les collaborateurs d'EditOps.
 * Regroupe les lectures de bas niveau sur un {@link CharSequence} (bornes de
 * ligne, indentation, recherche) afin que chaque classe d'édition intelligente
 * n'ait pas à redéfinir ces primitives.
 */
final class EditTextUtils {

    private EditTextUtils() {}

    /** Offset du début de la ligne contenant {@code offset} (ou 0 pour la première ligne). */
    static int lineStartForOffset(CharSequence text, int offset) {
        if (offset <= 0) return 0;
        offset = Math.min(offset, text.length());
        for (int i = offset - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') return i + 1;
        }
        return 0;
    }

    /** Préfixe d'espaces/tabulations de la ligne commençant à {@code lineStart}. */
    static String extractIndent(CharSequence text, int lineStart) {
        StringBuilder sb = new StringBuilder();
        for (int i = lineStart; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\t') {
                sb.append(ch);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    /** Texte de la ligne commençant à {@code lineStart}, sans le saut de ligne final. */
    static String extractLineText(CharSequence text, int lineStart) {
        int end = indexOf(text, '\n', lineStart);
        if (end < 0) end = text.length();
        return text.subSequence(lineStart, end).toString();
    }

    /** Index de {@code ch} dans {@code text} à partir de {@code from}, ou -1. */
    static int indexOf(CharSequence text, char ch, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == ch) return i;
        }
        return -1;
    }

    /** Index de {@code search} dans {@code text} à partir de {@code from}, ou -1. */
    static int indexOf(CharSequence text, String search, int from) {
        int slen = search.length();
        int max = text.length() - slen;
        for (int i = from; i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < slen; j++) {
                if (text.charAt(i + j) != search.charAt(j)) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }

    /**
     * Retourne vrai si le caractère est un caractère d'identifiant (lettre, chiffre, underscore, $).
     */
    static boolean isIdentChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    /** Retourne vrai si {@code text} commence par {@code prefix} à {@code offset}. */
    static boolean startsWith(CharSequence text, String prefix, int offset) {
        if (offset + prefix.length() > text.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (text.charAt(offset + i) != prefix.charAt(i)) return false;
        }
        return true;
    }
}
