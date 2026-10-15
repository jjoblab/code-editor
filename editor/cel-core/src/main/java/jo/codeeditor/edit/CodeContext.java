package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;

/**
 * Détection du contexte lexical à une position du document : dans un littéral
 * de chaîne, dans un commentaire de bloc, sur un opérateur en suspension, sur
 * un label {@code case}, sur une ligne d'annotation seule. Les gestionnaires de
 * saut de ligne s'appuient sur ces prédicats pour choisir l'indentation correcte.
 *
 * <p><b>ADR-002 (C1-é4)</b> : les délimiteurs ne sont plus codés en dur
 * ({@code //}, {@code /* *&#47;}, {@code "} {@code '}) mais lus du
 * {@link LanguageProfile} : commentaire de ligne/bloc via
 * {@code commentSyntax}, guillemets via {@code stringQuotes}. Les variantes
 * sans profil ({@link #isInString(CharSequence, int)}…) conservent les
 * délimiteurs C historiques — repli des appelants qui ne connaissent pas
 * le langage (compatibilité binaire et comportement octet pour octet).</p>
 */
final class CodeContext {

    /**
     * Lot 3 (audit v3.41.0) — borne du scan O(n) en arrière. Avant
     * ce correctif, les prédicats {@link #isInString},
     * {@link #isInBlockComment}, {@link #isInLineComment} et
     * {@link #isInStringOrComment} parcouraient le document depuis
     * l'offset 0 jusqu'à la position du caret, sans borne. Sur un
     * document de 100 000+ caractères, chaque frappe déclenchait
     * 100 000 itérations — pression GC et latence perceptible sur
     * le thread UI.
     *
     * <p>La borne de 10 000 caractères est identique à celle de
     * {@link IndentDetection#detectIndentUnit} — un compromis
     * raisonnable : une chaîne ou un commentaire de plus de 10 000
     * caractères est extrême (e.g. minified JS), et l'utilisateur
     * l'édite justement en partie — les autres régions du document
     * n'ont pas besoin du prédicat pendant l'édition. Le faux
     * négatif potentiel est acceptable.</p>
     */
    static final int SCAN_LIMIT = 10_000;

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

    // ── ADR-002 (C1-é4) : délimiteurs du profil ou repli C ─────────

    /** Préfixe de commentaire de ligne du profil (ou {@code //} en repli). */
    private static String lineCommentOf(LanguageProfile profile) {
        return profile != null ? profile.commentSyntax.lineComment : "//";
    }

    /** Ouvreur de commentaire de bloc du profil (ou {@code /*} en repli). */
    private static String blockStartOf(LanguageProfile profile) {
        return profile != null ? profile.commentSyntax.blockStart : "/*";
    }

    /** Fermeur de commentaire de bloc du profil (ou {@code *&#47;} en repli). */
    private static String blockEndOf(LanguageProfile profile) {
        return profile != null ? profile.commentSyntax.blockEnd : "*/";
    }

    /** Vrai si c est un guillemet de chaîne du profil (ou {@code "}/{@code '} en repli). */
    private static boolean isQuote(char c, LanguageProfile profile) {
        return profile != null ? profile.stringQuotes.contains(c)
                : (c == '"' || c == '\'');
    }

    /**
     * Indique si la position {@code pos} se trouve à l'intérieur d'un
     * commentaire de bloc selon les délimiteurs du profil (repli C :
     * {@code /* … *&#47;}). Le prédicat est conscient des littéraux de
     * chaîne/char et des commentaires de ligne : un ouvreur de bloc
     * apparaissant dans une chaîne ou un commentaire de ligne
     * n'ouvre pas un commentaire de bloc.
     *
     * <p><b>Lot 1 (audit v3.41.0)</b> : l'ancien code balayait depuis
     * l'offset 0 sans tenir compte des chaînes ni des commentaires
     * de ligne ; un seul ouvreur de bloc dans une chaîne activait
     * {@code foundOpen = true} et marquait toutes les lignes suivantes
     * comme dans un commentaire de bloc — chaque Entrée insérait
     * alors {@code " * "} jusqu'à la fin du fichier. Le prédicat est
     * désormais calculé par la même machine à états que
     * {@link #isInString}, qui ignore correctement les chaînes et les
     * commentaires de ligne. Le coût reste O(pos) par appel (comme
     * avant), mais le résultat est correct.</p>
     */
    static boolean isInBlockComment(CharSequence text, int pos) {
        return isInBlockComment(text, pos, null);
    }

    /** Variante profilée (ADR-002, C1-é4) — délimiteurs du profil. */
    static boolean isInBlockComment(CharSequence text, int pos,
            LanguageProfile profile) {
        if (pos <= 0) return false;
        String lineComment = lineCommentOf(profile);
        String blockStart = blockStartOf(profile);
        String blockEnd = blockEndOf(profile);
        if (blockStart == null || blockEnd == null) return false;
        boolean inString = false;
        char quoteChar = '\0';
        boolean inLineComment = false;
        boolean inBlockComment = false;
        // Lot 3 : borne du scan à SCAN_LIMIT caractères en arrière.
        // Le faux négatif potentiel (commentaire de bloc ouvert il y
        // a plus de SCAN_LIMIT caractères) est acceptable — un
        // commentaire de 10K+ chars est extrême.
        int start = Math.max(0, pos - SCAN_LIMIT);
        // Si on a tronqué le début, on suppose qu'on n'est PAS dans
        // un bloc ouvert avant ce point. C'est une approximation.
        for (int i = start; i < pos; i++) {
            char ch = text.charAt(i);
            if (inLineComment) {
                if (ch == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (startsWithAt(text, blockEnd, i)) {
                    inBlockComment = false;
                    i += blockEnd.length() - 1;
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
            // Bloc AVANT ligne : le préfixe le plus long gagne (Lua :
            // « --[[ » est un bloc, pas un commentaire de ligne « -- »).
            if (startsWithAt(text, blockStart, i)) {
                inBlockComment = true;
                i += blockStart.length() - 1;
                continue;
            }
            if (lineComment != null && startsWithAt(text, lineComment, i)) {
                inLineComment = true;
                i += lineComment.length() - 1;
                continue;
            }
            if (isQuote(ch, profile)) {
                inString = true;
                quoteChar = ch;
            }
        }
        return inBlockComment;
    }

    /**
     * Indique si la position {@code pos} se trouve à l'intérieur d'un
     * commentaire de ligne selon le profil (repli C : {@code // …}).
     * Conscient des littéraux de chaîne/char et des commentaires de
     * bloc pour ne pas confondre un préfixe de ligne dans une chaîne
     * avec un commentaire de ligne.
     *
     * <p><b>Lot 1 (audit v3.41.0)</b> : ajouté pour le garde-fou
     * d'auto-close des crochets dans {@link SmartTyping} — sans lui,
     * un crochet tapé dans un commentaire de ligne insérait une
     * paire dans du texte mort.</p>
     */
    static boolean isInLineComment(CharSequence text, int pos) {
        return isInLineComment(text, pos, null);
    }

    /** Variante profilée (ADR-002, C1-é4) — délimiteurs du profil. */
    static boolean isInLineComment(CharSequence text, int pos,
            LanguageProfile profile) {
        if (pos <= 0) return false;
        String lineComment = lineCommentOf(profile);
        if (lineComment == null) return false;
        String blockStart = blockStartOf(profile);
        String blockEnd = blockEndOf(profile);
        boolean inString = false;
        char quoteChar = '\0';
        boolean inLineComment = false;
        boolean inBlockComment = false;
        // Lot 3 : borne du scan à SCAN_LIMIT caractères en arrière.
        int start = Math.max(0, pos - SCAN_LIMIT);
        for (int i = start; i < pos; i++) {
            char ch = text.charAt(i);
            if (inLineComment) {
                if (ch == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (blockEnd != null && startsWithAt(text, blockEnd, i)) {
                    inBlockComment = false;
                    i += blockEnd.length() - 1;
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
            if (blockStart != null && startsWithAt(text, blockStart, i)) {
                inBlockComment = true;
                i += blockStart.length() - 1;
                continue;
            }
            if (startsWithAt(text, lineComment, i)) {
                inLineComment = true;
                i += lineComment.length() - 1;
                continue;
            }
            if (isQuote(ch, profile)) {
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
        return isInStringOrComment(text, pos, null);
    }

    /** Variante profilée (ADR-002, C1-é4) — délimiteurs du profil. */
    static boolean isInStringOrComment(CharSequence text, int pos,
            LanguageProfile profile) {
        return isInString(text, pos, profile)
                || isInBlockComment(text, pos, profile)
                || isInLineComment(text, pos, profile);
    }

    static boolean isInString(CharSequence text, int pos) {
        return isInString(text, pos, null);
    }

    /** Variante profilée (ADR-002, C1-é4) — guillemets du profil. */
    static boolean isInString(CharSequence text, int pos,
            LanguageProfile profile) {
        String lineComment = lineCommentOf(profile);
        String blockStart = blockStartOf(profile);
        String blockEnd = blockEndOf(profile);
        boolean inString = false;
        char quoteChar = '\0';
        // Lot 3 : borne du scan à SCAN_LIMIT caractères en arrière.
        // Le faux négatif potentiel (chaîne ouverte il y a plus de
        // SCAN_LIMIT caractères) est acceptable — une chaîne de 10K+
        // est extrême (e.g. minified JS).
        int start = Math.max(0, pos - SCAN_LIMIT);
        for (int i = start; i < pos; i++) {
            char ch = text.charAt(i);
            if (!inString) {
                // Bloc AVANT ligne (préfixe le plus long — Lua --[[
                // prime sur --), même priorité que isInBlockComment.
                if (blockStart != null && blockEnd != null
                        && startsWithAt(text, blockStart, i)) {
                    int close = EditTextUtils.indexOf(text, blockEnd, i + blockStart.length());
                    if (close < 0 || close >= pos) return false; // bloc non fermé avant pos → pas dans une chaîne
                    i = close + blockEnd.length() - 1; // passer le fermeur ; le i++ de la boucle avance encore d'un
                    continue;
                }
                // Commentaire de ligne : sauter jusqu'à la fin de ligne, jamais dans une chaîne à cet endroit.
                if (lineComment != null && startsWithAt(text, lineComment, i)) {
                    // avancer jusqu'à la fin de ligne
                    while (i < pos && text.charAt(i) != '\n') i++;
                    // le i++ de la boucle passera le saut de ligne (ou pos)
                    continue;
                }
                if (isQuote(ch, profile)) {
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

    /** {@code text.startsWith(needle, at)} tolérant sur les bornes. */
    private static boolean startsWithAt(CharSequence text, String needle, int at) {
        if (needle == null || at < 0 || at + needle.length() > text.length()) {
            return false;
        }
        for (int k = 0; k < needle.length(); k++) {
            if (text.charAt(at + k) != needle.charAt(k)) return false;
        }
        return true;
    }

    /**
     * Motif de label de branchement (P-04, audit 3.69.0) — PRÉCOMPILÉ :
     * {@code String.matches} recompilait l'expression à CHAQUE
     * reconnaissance, sur le chemin de la touche Entrée (saut de ligne
     * intelligent après un label case).
     */
    private static final java.util.regex.Pattern CASE_LABEL =
            java.util.regex.Pattern.compile("case\\b.*:");

    static boolean isCaseLabel(CharSequence text, int pos) {
        // Chercher en remontant depuis pos "case " ou "default"
        int lineStart = EditTextUtils.lineStartForOffset(text, pos);
        String line = text.subSequence(lineStart, pos + 1).toString().trim();
        return CASE_LABEL.matcher(line).matches() || line.equals("default:");
    }

    static boolean isAnnotationOnlyLine(CharSequence text, int lineStart) {
        String line = EditTextUtils.extractLineText(text, lineStart).trim();
        return line.startsWith("@") && !line.contains("(") && !line.contains(" ");
    }
}
