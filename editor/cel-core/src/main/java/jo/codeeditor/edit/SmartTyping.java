package jo.codeeditor.edit;

/**
 * Saisie intelligente caractère par caractère : insertion avec skip-over et
 * auto-close (smartInsert), effacement arrière avec suppression de paire vide,
 * collapse de lignes vides et désindentation (smartBackspace), suppression
 * avant sensible aux paires (smartDeleteForward). Extrait d'EditOps pour
 * isoler la responsabilité « frappe » des sauts de ligne et de la navigation.
 */
final class SmartTyping {

    private SmartTyping() {}

    /**
     * Insertion intelligente de caractère avec skip-over, auto-close, désindentation.
     *
     * @param text     texte complet du document
     * @param selStart début de la sélection
     * @param selEnd   fin de la sélection
     * @param ch       caractère frappé
     * @param language identifiant de langage
     * @return RangeEdit décrivant l'édition
     */
    static RangeEdit smartInsert(CharSequence text, int selStart, int selEnd, char ch, String language) {
        // S'il y a une sélection, la remplacer
        if (selStart != selEnd) {
            return new RangeEdit(selStart, selEnd, String.valueOf(ch), selStart + 1);
        }

        int pos = selStart;

        // Skip-over : si le caractère à pos est le même fermeur, simplement passer outre
        if (pos < text.length() && text.charAt(pos) == ch && BracketPairs.isCloser(ch)) {
            return new RangeEdit(pos, pos, "", pos + 1);
        }

        // Texte brut : simple insertion
        if (LanguageIds.isPlainText(language)) {
            return new RangeEdit(pos, pos, String.valueOf(ch), pos + 1);
        }

        // Auto-close des crochets
        if (BracketPairs.isOpener(ch) && ch != '"' && ch != '\'') {
            char closer = BracketPairs.closerFor(ch);
            // Vérification d'équilibre
            int balance = BracketPairs.docBalance(text, ch, closer);
            if (balance < BracketPairs.BALANCE_SCAN_LIMIT) {
                return new RangeEdit(pos, pos, "" + ch + closer, pos + 1);
            }
        }

        // Auto-close des guillemets (pas après un caractère d'identifiant, pas en texte brut)
        if ((ch == '"' || ch == '\'') && LanguageIds.isJavaOrKotlin(language)) {
            boolean afterIdent = pos > 0 && EditTextUtils.isIdentChar(text.charAt(pos - 1));
            if (!afterIdent) {
                char closer = BracketPairs.closerFor(ch);
                return new RangeEdit(pos, pos, "" + ch + closer, pos + 1);
            }
        }

        // Smart Enter : déléguer au gestionnaire de saut de ligne
        if (ch == '\n') {
            return SmartNewline.smartEnter(text, pos, language);
        }

        // Défaut : insertion simple
        return new RangeEdit(pos, pos, String.valueOf(ch), pos + 1);
    }

    /**
     * Backspace intelligent avec suppression de paire vide, collapse de lignes vides, indentation intelligente.
     */
    static RangeEdit smartBackspace(CharSequence text, int selStart, int selEnd, String language) {
        // Supprimer d'abord la sélection
        if (selStart != selEnd) {
            return new RangeEdit(selStart, selEnd, "", selStart);
        }

        int pos = selStart;
        if (pos <= 0) return new RangeEdit(0, 0, "", 0);

        char before = text.charAt(pos - 1);

        // Suppression de paire vide : supprimer les deux crochets/guillemets
        if (pos < text.length()) {
            char after = text.charAt(pos);
            if (BracketPairs.isPair(before, after)) {
                return new RangeEdit(pos - 1, pos + 1, "", pos - 1);
            }
        }

        // Collapse de lignes vides : si on est au début d'une ligne vide, supprimer le saut de ligne précédent
        if (before == '\n' && isBlankLineBefore(text, pos)) {
            // Trouver le début de la séquence de lignes vides précédente
            int delStart = findBlankLineCollapseStart(text, pos);
            return new RangeEdit(delStart, pos, "", delStart);
        }

        // Indentation intelligente du fermeur : aligner sur l'indentation de l'ouvreur
        if (BracketPairs.isCloser(before) && LanguageIds.isJavaOrKotlin(language)) {
            int openerPos = findMatchingOpener(text, pos - 1, before);
            if (openerPos >= 0) {
                int openerLineStart = EditTextUtils.lineStartForOffset(text, openerPos);
                int closerLineStart = EditTextUtils.lineStartForOffset(text, pos - 1);
                if (openerLineStart != closerLineStart) {
                    String openerIndent = EditTextUtils.extractIndent(text, openerLineStart);
                    String currentIndent = EditTextUtils.extractIndent(text, closerLineStart);
                    if (!openerIndent.equals(currentIndent) && currentIndent.length() > openerIndent.length()) {
                        // Remplacer l'indentation de la ligne courante par celle de l'ouvreur
                        return new RangeEdit(closerLineStart, closerLineStart + currentIndent.length(),
                            openerIndent, closerLineStart + openerIndent.length());
                    }
                }
            }
        }

        // Backspace d'indentation intelligente : supprimer un niveau entier d'indentation
        if (before == ' ' || before == '\t') {
            int lineStart = EditTextUtils.lineStartForOffset(text, pos);
            String indent = EditTextUtils.extractIndent(text, lineStart);
            if (pos == lineStart + indent.length()) {
                // Le curseur est à la fin de l'indentation
                int indentSize = indent.length();
                int tabSize = IndentDetection.detectTabSize(text);
                int removeCount = indentSize > 0 ? ((indentSize - 1) / tabSize + 1) : 0;
                removeCount = Math.min(removeCount, indentSize);
                if (removeCount > 0) {
                    return new RangeEdit(pos - removeCount, pos, "", pos - removeCount);
                }
            }
        }

        // Défaut : supprimer un caractère
        return new RangeEdit(pos - 1, pos, "", pos - 1);
    }

    // ── deleteForward (sensible aux paires) ───────────────────────

    /**
     * Suppression avant intelligente : sensible aux paires.
     */
    static RangeEdit smartDeleteForward(CharSequence text, int selStart, int selEnd, String language) {
        if (selStart != selEnd) {
            return new RangeEdit(selStart, selEnd, "", selStart);
        }
        int pos = selStart;
        if (pos >= text.length()) return new RangeEdit(pos, pos, "", pos);

        // Sensible aux paires : si le curseur est entre () {} [] "", supprimer les deux
        if (pos + 1 < text.length()) {
            char at = text.charAt(pos);
            char after = text.charAt(pos + 1);
            if (BracketPairs.isPair(at, after)) {
                return new RangeEdit(pos, pos + 2, "", pos);
            }
        }

        return new RangeEdit(pos, pos + 1, "", pos);
    }

    // ── Méthodes utilitaires ───────────────────────────────────

    private static boolean isBlankLineBefore(CharSequence text, int pos) {
        // Vérifier si la ligne avant pos (terminée à pos-1 par \n) est vide
        int lineStart = EditTextUtils.lineStartForOffset(text, pos - 1);
        for (int i = lineStart; i < pos - 1; i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

    private static int findBlankLineCollapseStart(CharSequence text, int pos) {
        // Remonter à travers les lignes vides consécutives
        int p = pos - 1; // sauter le \n
        while (p > 0) {
            int ls = EditTextUtils.lineStartForOffset(text, p);
            boolean blank = true;
            for (int i = ls; i <= p; i++) {
                char ch = text.charAt(i);
                if (ch != '\n' && !Character.isWhitespace(ch)) {
                    blank = false;
                    break;
                }
            }
            if (!blank) break;
            p = ls - 1; // passer à la fin de la ligne précédente
        }
        return Math.max(0, p + 1);
    }

    private static int findMatchingOpener(CharSequence text, int closerPos, char closer) {
        char opener = BracketPairs.openerFor(closer);
        if (opener == '\0') return -1;
        int depth = 0;
        for (int i = closerPos; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == closer) depth++;
            else if (ch == opener) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }
}
