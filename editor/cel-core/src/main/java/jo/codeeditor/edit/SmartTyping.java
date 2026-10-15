package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;

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

        // Texte brut : simple insertion (gardé pour compatibilité ;
        // autoCloseBrackets est vide pour text/log/properties de
        // toute façon).
        if (LanguageIds.isPlainText(language)) {
            return new RangeEdit(pos, pos, String.valueOf(ch), pos + 1);
        }

        // Lot 2 (audit v3.41.0) : auto-close des crochets et des
        // guillemets piloté par les traits d'édition du profil de
        // langage (autoCloseBrackets / autoCloseQuotes). L'ancien
        // code gardait via LanguageIds.isJavaOrKotlin pour les
        // guillemets (limitant l'auto-close à Java/Kotlin — C, Go,
        // Rust, JS, TS, Python, Lua, Shell, SQL, JSON ne bénéficiaient
        // pas) et via !isPlainText pour les crochets (appliquant
        // ()/[] à XML, HTML, CSS, Markdown, YAML — sans sens dans
        // ces langages).
        LanguageProfile profile = LanguageRegistry.forName(language);

        // Auto-close des crochets — seulement si le profil déclare
        // ce crochet dans autoCloseBrackets.
        boolean bracketsEnabled = profile != null
                && profile.autoCloseBrackets.contains(ch);
        if (bracketsEnabled && ch != '"' && ch != '\''
                && BracketPairs.isOpener(ch)) {
            char closer = BracketPairs.closerFor(ch);
            // Lot 1 : gardes nextIsIdent et inStringOrComment
            // (préservés — pas d'auto-close devant un identifiant
            // ou dans une chaîne/commentaire).
            boolean nextIsIdent = pos < text.length()
                    && EditTextUtils.isIdentChar(text.charAt(pos));
            boolean inStringOrComment = CodeContext.isInStringOrComment(text, pos, profile);
            int balance = BracketPairs.docBalance(text, ch, closer);
            if (!nextIsIdent && !inStringOrComment
                    && balance < BracketPairs.BALANCE_SCAN_LIMIT) {
                return new RangeEdit(pos, pos, "" + ch + closer, pos + 1);
            }
        }

        // Auto-close des guillemets — seulement si le profil déclare
        // ce guillemet dans autoCloseQuotes. Lot 2 : élargi à tous les
        // C_LIKE + Python + Lua + SQL + JSON + Shell + XML/CSS/Markdown
        // (le profil porte la décision, plus LanguageIds). La garde
        // isInStringOrComment (Lot 1) empêche l'auto-close dans une
        // chaîne ou un commentaire — par exemple \" dans // foo n'est
        // pas un ouvrant de chaîne mais la fermeture d'une chaîne
        // déjà ouverte ; ou \" dans /* … */ n'est pas du tout du
        // code.
        boolean quotesEnabled = profile != null
                && profile.autoCloseQuotes.contains(ch);
        if (quotesEnabled && (ch == '"' || ch == '\'')) {
            boolean afterIdent = pos > 0 && EditTextUtils.isIdentChar(text.charAt(pos - 1));
            // ADR-002 (C1-é4) : garde profilée — le scanner de contexte
            // lit les délimiteurs du profil (commentaire #, bloc Lua,
            // guillemets déclarés).
            boolean inStringOrComment = CodeContext.isInStringOrComment(text, pos, profile);
            if (!afterIdent && !inStringOrComment) {
                char closer = BracketPairs.closerFor(ch);
                return new RangeEdit(pos, pos, "" + ch + closer, pos + 1);
            }
        }

        // Smart Enter : déléguer au gestionnaire de saut de ligne
        if (ch == '\n') {
            return SmartNewline.smartEnter(text, pos, language);
        }

        // Lot 2/3 (audit v3.41.0) : fermeture de balise sur >
        // pour les langages de famille XML (xml, html, htm, svg).
        // Quand l'utilisateur tape > après un nom de balise ouvrant
        // non auto-fermant, la balise fermante est insérée
        // automatiquement. Exemple : <div| → > → <div></div>
        if (ch == '>' && LanguageIds.isXml(language)) {
            String autoClose = xmlTagAutoClose(text, pos);
            if (autoClose != null) {
                return new RangeEdit(pos, pos, autoClose, pos + 1);
            }
        }

        // Lot 2/3 (audit v3.41.0) : dédentation électrique pour
        // les langages C_LIKE. Quand l'utilisateur tape `}` sur
        // une ligne qui ne contient que de l'indentation avant le
        // caret, déplacer le caret au début de l'indentation AVANT
        // l'insertion du `}`. Cela évite d'avoir une ligne comme
        // «    } » quand l'utilisateur veut « } ».
        if (ch == '}' && isCLikeLanguage(language)) {
            int lineStart = EditTextUtils.lineStartForOffset(text, pos);
            String indent = EditTextUtils.extractIndent(text, lineStart);
            if (pos == lineStart + indent.length() && !indent.isEmpty()) {
                // Le caret est à la fin de l'indentation — dédenter
                // avant d'insérer le `}`.
                return new RangeEdit(lineStart, pos, "}", lineStart + 1);
            }
        }

        // Défaut : insertion simple
        return new RangeEdit(pos, pos, String.valueOf(ch), pos + 1);
    }

    /**
     * Lot 2/3 : génère le texte à insérer pour la fermeture
     * automatique d'une balise XML/HTML/SVG. Retourne
     * {@code null} si pas de balise à fermer (balise
     * auto-fermante, balise fermante, ou pas de balise
     * ouvrante trouvée).
     *
     * @param text le texte du document
     * @param pos  la position où {@code >} va être inséré
     * @return le texte à insérer (e.g. {@code ">"} ou
     *         {@code "></div>"}), ou {@code null}
     */
    private static String xmlTagAutoClose(CharSequence text, int pos) {
        // Chercher en arrière le dernier '<' avant le caret.
        int lt = -1;
        for (int i = pos - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '<') { lt = i; break; }
            if (c == '>') return null; // une autre balise avant — pas une balise ouvrante
        }
        if (lt < 0) return null;
        // Vérifier que ce n'est pas une balise fermante (</)
        if (lt + 1 < text.length() && text.charAt(lt + 1) == '/') return null;
        // Extraire le contenu de la balise entre '<' et pos.
        // Le nom de la balise est le premier mot avant un espace ou '/'.
        StringBuilder tagName = new StringBuilder();
        for (int i = lt + 1; i < pos; i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '/') break;
            if (!isXmlNameChar(c)) return null;
            tagName.append(c);
        }
        if (tagName.length() == 0) return null;
        // Vérifier que la balise n'est pas auto-fermante (se
        // termine par '/' juste avant le caret).
        if (pos > 0 && text.charAt(pos - 1) == '/') return null;
        // Insérer > puis </tag>.
        return ">" + "</" + tagName + ">";
    }

    private static boolean isXmlNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ':' || c == '.';
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

    /**
     * Lot 2/3 : vrai si le langage est de famille C_LIKE
     * (Java, Kotlin, C/C++, Go, Rust, PHP, Swift, Dart,
     * Groovy, JS/TS, Scala). Résolu via le registre.
     */
    private static boolean isCLikeLanguage(String language) {
        if (language == null || language.isEmpty()) return false;
        jo.codeeditor.languages.LanguageProfile profile =
                jo.codeeditor.languages.LanguageRegistry.forName(language);
        return profile != null
                && profile.family == jo.codeeditor.languages.SyntaxFamily.C_LIKE;
    }
}
