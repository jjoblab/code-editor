package jo.codeeditor.edit;

/**
 * Détection du contexte lexical à une position du document : dans un littéral
 * de chaîne, dans un commentaire de bloc, sur un opérateur en suspension, sur
 * un label {@code case}, sur une ligne d'annotation seule. Les gestionnaires de
 * saut de ligne s'appuient sur ces prédicats pour choisir l'indentation correcte.
 */
final class CodeContext {

    private CodeContext() {}

    static boolean isDanglingOperator(CharSequence text, int pos) {
        if (pos < 0 || pos >= text.length()) return false;
        char ch = text.charAt(pos);
        return ch == '+' || ch == '-' || ch == '*' || ch == '/'
            || ch == '&' || ch == '|' || ch == '^'
            || ch == '=' || ch == '<' || ch == '>'
            || ch == ',';
    }

    static boolean endsWithArrow(CharSequence text, int pos) {
        // Vérifier si le texte finit par "->" à pos
        return pos >= 1 && text.charAt(pos) == '>' && text.charAt(pos - 1) == '-';
    }

    static boolean isInBlockComment(CharSequence text, int pos) {
        // Balayer à rebours pour trouver /* sans */ intermédiaire
        boolean foundOpen = false;
        for (int i = 0; i < pos - 1; i++) {
            if (text.charAt(i) == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                foundOpen = true;
            } else if (text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                foundOpen = false;
            }
        }
        return foundOpen;
    }

    static boolean isInString(CharSequence text, int pos) {
        boolean inString = false;
        char quoteChar = '\0';
        for (int i = 0; i < pos; i++) {
            char ch = text.charAt(i);
            if (!inString) {
                // Commentaire de ligne : sauter jusqu'à la fin de ligne, jamais dans une chaîne à cet endroit.
                if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                    // avancer jusqu'à la fin de ligne
                    while (i < pos && text.charAt(i) != '\n') i++;
                    // le i++ de la boucle passera le saut de ligne (ou pos)
                    continue;
                }
                // Ouverture de commentaire de bloc : sauter jusqu'au */ correspondant
                if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    int close = EditTextUtils.indexOf(text, "*/", i + 2);
                    if (close < 0) return false; // commentaire de bloc non terminé → pas dans une chaîne
                    i = close + 1; // passer "*/" ; le i++ de la boucle avance encore d'un
                    continue;
                }
                if (ch == '"' || ch == '\'') {
                    inString = true;
                    quoteChar = ch;
                }
            } else {
                if (ch == '\\') {
                    i++; // sauter le caractère échappé
                } else if (ch == quoteChar) {
                    inString = false;
                }
            }
        }
        return inString;
    }

    static boolean isCaseLabel(CharSequence text, int pos) {
        // Chercher en remontant depuis pos "case " ou "default"
        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String line = text.subSequence(lineStart, pos + 1).toString().trim();
        return line.matches("case\\b.*:") || line.equals("default:");
    }

    static boolean isAnnotationOnlyLine(CharSequence text, int lineStart) {
        String line = EditTextUtils.extractLineText(text, lineStart).trim();
        return line.startsWith("@") && !line.contains("(") && !line.contains(" ");
    }
}
