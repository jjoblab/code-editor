package jo.codeeditor.edit;

/**
 * Gestionnaires de saut de ligne intelligents pour les langages style C :
 * dispatch par famille (XML délégué à XmlNewline), indentation Java (puces,
 * labels case, annotations, commentaires) et Kotlin (flèches), plus la
 * complétion d'instruction sur Entrée. Extrait d'EditOps pour isoler la
 * responsabilité « nouvelle ligne » de la saisie caractère par caractère.
 */
final class SmartNewline {

    private SmartNewline() {}

    /**
     * Smart Enter : poursuite de l'indentation, plus profonde après les ouvreurs, expansion de crochets, etc.
     */
    static RangeEdit smartEnter(CharSequence text, int pos, String language) {
        if (LanguageIds.isXml(language)) {
            return XmlNewline.xmlNewline(text, pos, language);
        }
        if ("kotlin".equals(language)) {
            return kotlinNewline(text, pos, language);
        }
        // Java et défaut
        return javaNewline(text, pos, language);
    }

    /**
     * Gestionnaire de saut de ligne Java/Kotlin.
     */
    private static RangeEdit javaNewline(CharSequence text, int pos, String language) {
        boolean isKotlin = "kotlin".equals(language);
        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String currentIndent = EditTextUtils.extractIndent(text, lineStart);

        // Vérifier si on est dans un littéral de chaîne — pas de saut de ligne intelligent
        if (!isKotlin && CodeContext.isInString(text, pos)) {
            return plainNewline(text, pos, currentIndent);
        }

        char charBefore = pos > 0 ? text.charAt(pos - 1) : '\0';
        char charAfter = pos < text.length() ? text.charAt(pos) : '\0';

        // Expansion de paire vide : {|} -> {<indent>\n<indent>curseur\n<indent>}
        if (BracketPairs.isOpener(charBefore) && BracketPairs.isCloser(charAfter) && BracketPairs.isPair(charBefore, charAfter)) {
            String deeperIndent = currentIndent + "    ";
            String result = "\n" + deeperIndent + "\n" + currentIndent;
            return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
        }

        // Indentation plus profonde après les ouvreurs : { ou ( ou [
        if (BracketPairs.isOpener(charBefore) && charBefore != '"' && charBefore != '\'') {
            String deeperIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Poursuite de l'indentation : saut de ligne avec la même indentation
        // Vérifier aussi l'indentation de continuation (opérateurs en suspension)
        if (CodeContext.isDanglingOperator(text, pos - 1)) {
            String continuationIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + continuationIndent, pos + 1 + continuationIndent.length());
        }

        // Continuation de commentaire doc/bloc : /** ... */ ou /* ... */
        if (charBefore == '*' && pos >= 2 && text.charAt(pos - 2) == '/' &&
            (charAfter == '*' || charAfter == ' ')) {
            // Commentaire doc : insérer *  avec indentation
            return new RangeEdit(pos, pos, "\n" + currentIndent + " * ", pos + 1 + currentIndent.length() + 2);
        }
        if (charBefore == '*' && pos >= 2 && text.charAt(pos - 2) == '/' ) {
            return new RangeEdit(pos, pos, "\n" + currentIndent + " * ", pos + 1 + currentIndent.length() + 2);
        }

        // Vérifier si dans un commentaire de bloc quand l'utilisateur appuie sur Entrée
        if (CodeContext.isInBlockComment(text, pos)) {
            // Chercher le motif * en début
            // Insérer *  avec une espace
            return new RangeEdit(pos, pos, "\n" + currentIndent + " * ", pos + 1 + currentIndent.length() + 2);
        }

        // Continuation de puce de liste (- ou * en début de ligne)
        String trimmedLine = EditTextUtils.extractLineText(text, lineStart);
        if (trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ")) {
            String bullet = trimmedLine.substring(0, 2);
            if (trimmedLine.length() > 2) {
                return new RangeEdit(pos, pos, "\n" + currentIndent + bullet, pos + 1 + currentIndent.length() + 2);
            } else {
                // Item de liste vide : le supprimer
                return new RangeEdit(lineStart, pos, "\n", lineStart + 1);
            }
        }

        // Indentation des labels case : case X: ou default:
        if (CodeContext.isCaseLabel(text, pos - 1)) {
            String deeperIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Ligne d'annotation seule : même indentation
        if (CodeContext.isAnnotationOnlyLine(text, lineStart)) {
            return new RangeEdit(pos, pos, "\n" + currentIndent, pos + 1 + currentIndent.length());
        }

        // Défaut : continuer avec la même indentation
        return new RangeEdit(pos, pos, "\n" + currentIndent, pos + 1 + currentIndent.length());
    }

    /**
     * Gestionnaire de saut de ligne Kotlin (pas de découpe de chaîne, pas de labels case, indentation flèche).
     */
    private static RangeEdit kotlinNewline(CharSequence text, int pos, String language) {
        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String currentIndent = EditTextUtils.extractIndent(text, lineStart);

        char charBefore = pos > 0 ? text.charAt(pos - 1) : '\0';
        char charAfter = pos < text.length() ? text.charAt(pos) : '\0';

        // Expansion de paire vide
        if (BracketPairs.isOpener(charBefore) && BracketPairs.isCloser(charAfter) && BracketPairs.isPair(charBefore, charAfter)) {
            String deeperIndent = currentIndent + "    ";
            String result = "\n" + deeperIndent + "\n" + currentIndent;
            return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
        }

        // Plus profond après les ouvreurs
        if (BracketPairs.isOpener(charBefore) && charBefore != '"' && charBefore != '\'') {
            String deeperIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Indentation flèche (->) dans when/match
        if (CodeContext.endsWithArrow(text, pos - 1)) {
            String deeperIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Continuation d'opérateur en suspension
        if (CodeContext.isDanglingOperator(text, pos - 1)) {
            String continuationIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + continuationIndent, pos + 1 + continuationIndent.length());
        }

        // Continuation de commentaire de bloc
        if (CodeContext.isInBlockComment(text, pos)) {
            return new RangeEdit(pos, pos, "\n" + currentIndent + " * ", pos + 1 + currentIndent.length() + 2);
        }

        // Expansion de paire vide avec ouvreurs
        if (BracketPairs.isOpener(charBefore)) {
            char closer = BracketPairs.closerFor(charBefore);
            if (closer != '\0') {
                String deeperIndent = currentIndent + "    ";
                String result = "\n" + deeperIndent + "\n" + currentIndent;
                return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
            }
        }

        // Défaut : continuer avec la même indentation
        return new RangeEdit(pos, pos, "\n" + currentIndent, pos + 1 + currentIndent.length());
    }

    /**
     * Saut de ligne simple avec poursuite de l'indentation.
     */
    private static RangeEdit plainNewline(CharSequence text, int pos, String indent) {
        return new RangeEdit(pos, pos, "\n" + indent, pos + 1 + indent.length());
    }

    // ── smartEnter (complétion d'instruction) ──────────────────

    /**
     * Complétion d'instruction : terminer la ligne par ; si besoin, ajouter un bloc { } pour le contrôle de flux.
     */
    static RangeEdit smartEnterComplete(CharSequence text, int pos, String language) {
        if (!LanguageIds.isJavaOrKotlin(language)) {
            return new RangeEdit(pos, pos, "", pos);
        }

        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String lineText = EditTextUtils.extractLineText(text, lineStart);
        String trimmed = lineText.trim();
        String currentIndent = EditTextUtils.extractIndent(text, lineStart);

        // En-têtes de contrôle de flux nécessitant un bloc { }
        String[] controlFlowKeywords = {"if", "else", "for", "while", "do", "try", "catch", "finally"};
        for (String kw : controlFlowKeywords) {
            if (trimmed.startsWith(kw) && (trimmed.endsWith("{") || trimmed.endsWith(")"))) {
                // A déjà un bloc ou des parenthèses - ajouter { }
                if (!trimmed.endsWith("{")) {
                    return new RangeEdit(pos, pos, " {\n" + currentIndent + "    \n" + currentIndent + "}",
                        pos + currentIndent.length() + 5);
                }
            }
        }

        // Si la ligne ne finit pas par ; } ou {, ajouter ;
        char lastChar = trimmed.isEmpty() ? '\0' : trimmed.charAt(trimmed.length() - 1);
        if (lastChar != ';' && lastChar != '{' && lastChar != '}' && lastChar != ',') {
            return new RangeEdit(pos, pos, ";", pos + 1);
        }

        return new RangeEdit(pos, pos, "", pos);
    }
}
