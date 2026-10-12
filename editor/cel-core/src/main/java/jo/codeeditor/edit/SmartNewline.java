package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;

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
     *
     * <p>Lot 2/3 (audit v3.41.0) : indent triggers pour les langages
     * à indentation par mots-clés (Python, YAML, Shell, Lua). Quand
     * l'utilisateur appuie sur Entrée après une ligne se terminant
     * par {@code :} (Python, YAML) ou {@code then}/{@code do}
     * (Shell, Lua), la ligne suivante est indentée +1 niveau.
     * Voir {@link #indentTriggerMatch} pour le périmètre minimal.</p>
     */
    static RangeEdit smartEnter(CharSequence text, int pos, String language) {
        if (LanguageIds.isXml(language)) {
            return XmlNewline.xmlNewline(text, pos, language);
        }
        if ("kotlin".equals(language)) {
            return kotlinNewline(text, pos, language);
        }
        // Lot 2/3 : indent triggers pour Python/YAML/Shell/Lua.
        // Vérifié avant le dispatch Java/défaut car ces langages
        // ne sont pas XML ni Kotlin, et leur indentation après `:`/
        // `then`/`do` doit primer sur le comportement par défaut.
        String indentUnit = IndentDetection.detectIndentUnit(text);
        if (indentTriggerMatch(text, pos, language)) {
            int lineStart = EditTextUtils.lineStartForOffset(text, pos);
            String currentIndent = EditTextUtils.extractIndent(text, lineStart);
            String deeperIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + deeperIndent,
                    pos + 1 + deeperIndent.length());
        }
        // Java et défaut
        return javaNewline(text, pos, language);
    }

    /**
     * Lot 2/3 (audit v3.41.0) — détermine si la ligne avant le
     * caret se termine par un trigger d'indentation pour le
     * langage donné. Périmètre minimal (décision 4.4) :
     * <ul>
     *   <li><b>Python</b> ({@code python}, {@code py}) : ligne
     *       se terminant par {@code :} qui commence par un
     *       mot-clé de bloc ({@code def}, {@code class},
     *       {@code if}, {@code elif}, {@code for},
     *       {@code while}, {@code try}, {@code with},
     *       {@code except}, {@code else}, {@code finally}).
     *       Évite les faux positifs : dictionnaires
     *       {@code {1: 2}}, annotations, slices.</li>
     *   <li><b>YAML</b> ({@code yaml}, {@code yml}) : ligne
     *       se terminant par {@code :} SANS valeur après le
     *       deux-points (clé sans valeur = bloc imbriqué
     *       attendu sur la ligne suivante). {@code key: value}
     *       ne déclenche pas (la valeur est sur la même ligne).</li>
     *   <li><b>Shell</b> ({@code shell}, {@code bash},
     *       {@code sh}) : ligne se terminant par
     *       {@code then} ou {@code do}.</li>
     *   <li><b>Lua</b> ({@code lua}) : ligne se terminant par
     *       {@code then} ou {@code do}.</li>
     * </ul>
     *
     * <p><b>Risque de faux positifs évalué</b> : Java/Kotlin/
     * JS/TS sont C_LIKE et ne sont pas affectés (pas de
     * trigger {@code :} pour C_LIKE). JSON n'est ni PYTHON/
     * YAML/SHELL/LUA. Le ternaire Java {@code cond ? a : b} ne
     * déclenche pas car Java est C_LIKE.</p>
     */
    private static boolean indentTriggerMatch(CharSequence text, int pos, String language) {
        if (language == null) return false;
        String lang = language.trim().toLowerCase(java.util.Locale.ROOT);
        // Résoudre l'alias via le registre pour reconnaître py,
        // yml, bash, sh comme leurs formes longues.
        LanguageProfile profile = LanguageRegistry.forName(lang);
        String canonical = profile != null ? profile.name : lang;

        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String lineText = EditTextUtils.extractLineText(text, lineStart).trim();

        if (lineText.isEmpty()) return false;

        switch (canonical) {
            case "python":
                // Python : ligne se terminant par `:` qui commence
                // par un mot-clé de bloc. Évite les dictionnaires,
                // annotations, slices.
                if (!lineText.endsWith(":")) return false;
                String[] pyKeywords = {"def ", "class ", "if ", "elif ",
                    "for ", "while ", "try:", "try :", "with ", "except ",
                    "else:", "finally:"};
                for (String kw : pyKeywords) {
                    if (lineText.startsWith(kw)) return true;
                }
                return false;
            case "yaml":
                // YAML : ligne se terminant par `:` SANS valeur
                // après le deux-points. « key: value » ne déclenche
                // pas ; « key: » déclenche.
                if (!lineText.endsWith(":")) return false;
                // Vérifier qu'il n'y a rien après le `:` (juste le
                // deux-points final, pas de valeur).
                // lineText est déjà trimé, doncendsWith(":") suffit.
                return true;
            case "shell":
                // Shell : ligne se terminant par `then` ou `do`.
                return lineText.endsWith("then") || lineText.endsWith("do");
            case "lua":
                // Lua : ligne se terminant par `then` ou `do`.
                return lineText.endsWith("then") || lineText.endsWith("do");
            default:
                return false;
        }
    }

    /**
     * Gestionnaire de saut de ligne Java/Kotlin.
     */
    private static RangeEdit javaNewline(CharSequence text, int pos, String language) {
        boolean isKotlin = "kotlin".equals(language);
        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String currentIndent = EditTextUtils.extractIndent(text, lineStart);
        // Lot 1 (audit v3.41.0) : unité d'indentation détectée
        // (tabulation, 2 ou 4 ou 8 espaces) au lieu du littéral codé
        // dur "    ". Un fichier 2-espaces recevait 4 espaces de
        // continuation avant ce correctif.
        String indentUnit = IndentDetection.detectIndentUnit(text);

        // Vérifier si on est dans un littéral de chaîne — pas de saut de ligne intelligent
        if (!isKotlin && CodeContext.isInString(text, pos)) {
            return plainNewline(text, pos, currentIndent);
        }

        char charBefore = pos > 0 ? text.charAt(pos - 1) : '\0';
        char charAfter = pos < text.length() ? text.charAt(pos) : '\0';

        // Expansion de paire vide : {|} -> {<indent>\n<indent>curseur\n<indent>}
        if (BracketPairs.isOpener(charBefore) && BracketPairs.isCloser(charAfter) && BracketPairs.isPair(charBefore, charAfter)) {
            String deeperIndent = currentIndent + indentUnit;
            String result = "\n" + deeperIndent + "\n" + currentIndent;
            return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
        }

        // Indentation plus profonde après les ouvreurs : { ou ( ou [
        if (BracketPairs.isOpener(charBefore) && charBefore != '"' && charBefore != '\'') {
            String deeperIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Poursuite de l'indentation : saut de ligne avec la même indentation
        // Vérifier aussi l'indentation de continuation (opérateurs en suspension)
        if (CodeContext.isDanglingOperator(text, pos - 1)) {
            String continuationIndent = currentIndent + indentUnit;
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
        String trimmedLine = EditTextUtils.extractLineText(text, lineStart).trim();
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
            String deeperIndent = currentIndent + indentUnit;
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
        String indentUnit = IndentDetection.detectIndentUnit(text);

        char charBefore = pos > 0 ? text.charAt(pos - 1) : '\0';
        char charAfter = pos < text.length() ? text.charAt(pos) : '\0';

        // Expansion de paire vide
        if (BracketPairs.isOpener(charBefore) && BracketPairs.isCloser(charAfter) && BracketPairs.isPair(charBefore, charAfter)) {
            String deeperIndent = currentIndent + indentUnit;
            String result = "\n" + deeperIndent + "\n" + currentIndent;
            return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
        }

        // Plus profond après les ouvreurs
        if (BracketPairs.isOpener(charBefore) && charBefore != '"' && charBefore != '\'') {
            String deeperIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Indentation flèche (->) dans when/match
        if (CodeContext.endsWithArrow(text, pos - 1)) {
            String deeperIndent = currentIndent + indentUnit;
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Continuation d'opérateur en suspension
        if (CodeContext.isDanglingOperator(text, pos - 1)) {
            String continuationIndent = currentIndent + indentUnit;
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
                String deeperIndent = currentIndent + indentUnit;
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
        // Lot 1 (audit v3.41.0) : Kotlin n'utilise pas de
        // point-virgule de façon idiomatique. L'ancien code
        // insérait un ; final pour Java ET Kotlin (via le garde
        // isJavaOrKotlin). Désormais l'insertion du ; est réservée
        // à Java ; le bloc { } pour if/else/for/... reste inséré
        // pour les deux langages (utile dans les deux).
        LanguageProfile profile = LanguageRegistry.forName(language);
        boolean isKotlin = profile != null && "kotlin".equals(profile.name);

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

        // Si la ligne ne finit pas par ; } ou {, ajouter ; — mais
        // seulement pour Java (pas Kotlin).
        if (isKotlin) {
            return new RangeEdit(pos, pos, "", pos);
        }
        char lastChar = trimmed.isEmpty() ? '\0' : trimmed.charAt(trimmed.length() - 1);
        if (lastChar != ';' && lastChar != '{' && lastChar != '}' && lastChar != ',') {
            return new RangeEdit(pos, pos, ";", pos + 1);
        }

        return new RangeEdit(pos, pos, "", pos);
    }
}
