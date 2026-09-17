package jo.codeeditor.edit;

/**
 * Bornes et plages de mots dans un CharSequence. Porte les déplacements
 * mot-par-mot (wordBoundaryLeft/Right) et la sélection du mot sous le curseur
 * (wordRangeAt), utilisés par la navigation et la sélection de EditorSession.
 */
final class WordBounds {

    private WordBounds() {}

    /**
     * Trouve la borne de mot à gauche de pos.
     */
    static int wordBoundaryLeft(CharSequence text, int pos) {
        if (pos <= 0) return 0;
        int i = pos - 1;
        // Sauter les blancs
        while (i > 0 && Character.isWhitespace(text.charAt(i))) i--;
        if (i <= 0) return 0;
        // Si sur un caractère de mot, sauter le mot
        if (EditTextUtils.isIdentChar(text.charAt(i))) {
            while (i > 0 && EditTextUtils.isIdentChar(text.charAt(i - 1))) i--;
        } else {
            // Sauter les non-mots non-blancs (séquence de ponctuation)
            while (i > 0 && !Character.isWhitespace(text.charAt(i - 1)) && !EditTextUtils.isIdentChar(text.charAt(i - 1))) i--;
        }
        return i;
    }

    /**
     * Trouve la borne de mot à droite de pos.
     */
    static int wordBoundaryRight(CharSequence text, int pos) {
        int len = text.length();
        if (pos >= len) return len;
        int i = pos;
        // Sauter les blancs
        while (i < len && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= len) return len;
        // Si sur un caractère de mot, sauter le mot
        if (EditTextUtils.isIdentChar(text.charAt(i))) {
            while (i < len && EditTextUtils.isIdentChar(text.charAt(i))) i++;
        } else {
            // Sauter la séquence de ponctuation
            while (i < len && !Character.isWhitespace(text.charAt(i)) && !EditTextUtils.isIdentChar(text.charAt(i))) i++;
        }
        return i;
    }

    /**
     * Trouve la plage du mot à la position donnée.
     */
    static int[] wordRangeAt(CharSequence text, int pos) {
        int len = text.length();
        if (len == 0) return new int[]{0, 0};
        int p = Math.min(Math.max(0, pos), len - 1);

        if (!EditTextUtils.isIdentChar(text.charAt(p))) {
            // Si sur un blanc, trouver le mot le plus proche
            if (Character.isWhitespace(text.charAt(p))) {
                int left = wordBoundaryLeft(text, p);
                int right = wordBoundaryRight(text, p);
                return new int[]{left, right};
            }
            // Ponctuation : retourner simplement ce caractère
            return new int[]{p, p + 1};
        }

        int start = p;
        while (start > 0 && EditTextUtils.isIdentChar(text.charAt(start - 1))) start--;
        int end = p + 1;
        while (end < len && EditTextUtils.isIdentChar(text.charAt(end))) end++;
        return new int[]{start, end};
    }
}
