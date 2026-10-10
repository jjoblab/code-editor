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
        // Lot 1 (audit v3.41.0) : la virgule n'est plus un opérateur
        // « en suspension ». C'est un séparateur, pas un opérateur de
        // continuation. La classer comme dangling déclenchait +1 niveau
        // d'indentation après Entrée dans tous les contextes de
        // séparation : listes d'arguments multi-lignes, énumérations
        // Java, JSON « a »: 1,, paramètres Python, listes YAML —
        // comportement non désiré signalé par l'audit.
        return ch == '+' || ch == '-' || ch == '*' || ch == '/'
            || ch == '&' || ch == '|' || ch == '^'
            || ch == '=' || ch == '<' || ch == '>';
    }

    static boolean endsWithArrow(CharSequence text, int pos) {
        // Vérifier si le texte finit par "->" à pos
        return pos >= 1 && text.charAt(pos) == '>' && text.charAt(pos - 1) == '-';
    }

    /**
     * Indique si la position {@code pos} se trouve à l'intérieur d'un
     * commentaire de bloc {@code /* … *\/}. Le prédicat est conscient
     * des littéraux de chaîne/char et des commentaires de ligne :
     * un {@code /*} apparaissant dans une chaîne ({@code "/*"}), dans
     * un glob shell ({@code /tmp/*}), ou dans un commentaire de ligne
     * ({@code // /*}) n'ouvre pas un commentaire de bloc.
     *
     * <p><b>Lot 1 (audit v3.41.0)</b> : l'ancien code balayait depuis
     * l'offset 0 sans tenir compte des chaînes ni des commentaires de
     * ligne ; un seul {@code /*} dans une chaîne activait
     * {@code foundOpen = true} et marquait toutes les lignes suivantes
     * comme dans un commentaire de bloc — chaque Entrée insérait
     * alors {@code " * "} jusqu'à la fin du fichier. Le prédicat est
     * désormais calculé par la même machine à états que
     * {@link #isInString}, qui ignore correctement les chaînes et les
     * commentaires de ligne. Le coût reste O(pos) par appel (comme
     * avant), mais le résultat est correct.</p>
     */
    static boolean isInBlockComment(CharSequence text, int pos) {
        if (pos <= 0) return false;
        boolean inString = false;
        char quoteChar = '\0';
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < pos; i++) {
            char ch = text.charAt(i);
            if (inLineComment) {
                if (ch == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (ch == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++; // sauter le '/'
                }
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    i++; // sauter le caractère échappé
                } else if (ch == quoteChar) {
                    inString = false;
                }
                continue;
            }
            // Hors chaîne/commentaire : détecter les ouvertures.
            if (ch == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    inLineComment = true;
                    i++; // sauter le second '/'
                    continue;
                }
                if (next == '*') {
                    inBlockComment = true;
                    i++; // sauter le '*'
                    continue;
                }
            }
            if (ch == '"' || ch == '\'') {
                inString = true;
                quoteChar = ch;
            }
        }
        return inBlockComment;
    }

    /**
     * Indique si la position {@code pos} se trouve à l'intérieur d'un
     * commentaire de ligne {@code // …}. Conscient des littéraux de
     * chaîne/char et des commentaires de bloc pour ne pas confondre
     * un {@code //} dans une chaîne avec un commentaire de ligne.
     *
     * <p><b>Lot 1 (audit v3.41.0)</b> : ajouté pour le garde-fou
     * d'auto-close des crochets dans {@link SmartTyping} — sans lui,
     * un {@code (} tapé dans un commentaire de ligne insérait une
     * paire {@code ()} dans du texte mort.</p>
     */
    static boolean isInLineComment(CharSequence text, int pos) {
        if (pos <= 0) return false;
        boolean inString = false;
        char quoteChar = '\0';
        boolean inLineComment = false;
        boolean inBlockComment = false;
        for (int i = 0; i < pos; i++) {
            char ch = text.charAt(i);
            if (inLineComment) {
                if (ch == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (ch == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == quoteChar) {
                    inString = false;
                }
                continue;
            }
            if (ch == '/' && i + 1 < text.length()) {
                char next = text.charAt(i + 1);
                if (next == '/') {
                    inLineComment = true;
                    i++;
                    continue;
                }
                if (next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
            }
            if (ch == '"' || ch == '\'') {
                inString = true;
                quoteChar = ch;
            }
        }
        return inLineComment;
    }

    /**
     * Raccourci : la position est dans un littéral de chaîne, un
     * commentaire de ligne ou un commentaire de bloc. Utilisé par
     * les gardes-fous d'auto-close pour désactiver l'auto-close
     * dans tout contexte « non-code ».
     *
     * <p><b>Lot 1 (audit v3.41.0)</b> — ajouté pour
     * {@link SmartTyping}.</p>
     */
    static boolean isInStringOrComment(CharSequence text, int pos) {
        return isInString(text, pos)
                || isInBlockComment(text, pos)
                || isInLineComment(text, pos);
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
