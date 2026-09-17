package jo.codeeditor.edit;

/**
 * Détection statistique de l'unité d'indentation d'un document (tabulation ou
 * 2/4/8 espaces). L'unité détectée pilote le backspace par niveaux d'indentation
 * et l'indentation structurelle XML.
 */
final class IndentDetection {

    private IndentDetection() {}

    /**
     * Détecte l'unité d'indentation : "\t", "  ", "    " ou "        " (tabulation contre 2/4/8 espaces).
     */
    static String detectIndentUnit(CharSequence text) {
        int tabCount = 0;
        int sp2 = 0, sp4 = 0, sp8 = 0;
        int limit = Math.min(text.length(), 10_000);
        int lineStart = 0;

        for (int i = 0; i <= limit; i++) {
            if (i == limit || text.charAt(i) == '\n') {
                // Analyser la ligne
                if (lineStart < i) {
                    char first = text.charAt(lineStart);
                    if (first == '\t') {
                        tabCount++;
                    } else if (first == ' ') {
                        int spaces = 0;
                        for (int j = lineStart; j < i && text.charAt(j) == ' '; j++) spaces++;
                        if (spaces >= 8) sp8++;
                        else if (spaces >= 4) sp4++;
                        else if (spaces >= 2) sp2++;
                    }
                }
                lineStart = i + 1;
            }
        }

        if (tabCount > sp4 && tabCount > sp2) return "\t";
        if (sp4 >= sp2 && sp4 >= sp8) return "    ";
        if (sp2 > sp4) return "  ";
        return "    "; // défaut
    }

    /**
     * Retourne la taille de tabulation détectée (nombre d'espaces) pour le backspace.
     */
    static int detectTabSize(CharSequence text) {
        String unit = detectIndentUnit(text);
        return unit.length();
    }
}
