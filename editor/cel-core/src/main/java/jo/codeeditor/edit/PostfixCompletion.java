package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;

/**
 * Complétion postfix — inspirée d'les IDE de bureau.
 *
 * <p>L'utilisateur tape {@code obj.if} puis Entrée, et l'éditeur
 * génère {@code if (obj) {\n    \n}}. Les postfixes supportés :</p>
 * <ul>
 *   <li>{@code .if} → {@code if (expr) { ... }}</li>
 *   <li>{@code .for} → {@code for (item : expr) { ... }}</li>
 *   <li>{@code .notnull} → {@code if (expr != null) { ... }}</li>
 *   <li>{@code .return} → {@code return expr;}</li>
 *   <li>{@code .var} → {@code var name = expr;}</li>
 *   <li>{@code .while} → {@code while (expr) { ... }}</li>
 *   <li>{@code .try} → {@code try { expr } catch (Exception e) { ... }}</li>
 *   <li>{@code .else} → {@code if (!expr) { ... }}</li>
 * </ul>
 *
 * <p><b>Périmètre</b> : Java, Kotlin, JavaScript, TypeScript, Go,
 * Rust, C, C++, PHP, Swift, Dart, Groovy, Scala (C_LIKE) +
 * Python. Les autres langages retombent sur null.</p>
 *
 * @author jjoblab
 */
final class PostfixCompletion {

    private PostfixCompletion() {}

    /** Résultat d'une complétion postfix. */
    static final class Result {
        final int start;
        final int end;
        final String insertText;
        final int caret;

        Result(int start, int end, String insertText, int caret) {
            this.start = start;
            this.end = end;
            this.insertText = insertText;
            this.caret = caret;
        }
    }

    private static final String[] POSTFIXES = {
        ".if", ".for", ".notnull", ".return", ".var",
        ".while", ".try", ".else"
    };

    /**
     * Tente de déclencher une complétion postfix à la position
     * donnée. Retourne null si pas de postfix détecté.
     *
     * @param text le texte complet du document
     * @param pos la position du caret (fin du postfix)
     * @param language l'identifiant de langage
     * @return le résultat (remplacement + nouveau caret), ou null
     */
    static Result tryComplete(CharSequence text, int pos, String language) {
        if (language == null || pos < 4) return null;

        // Vérifier le langage.
        LanguageProfile profile = LanguageRegistry.forName(language);
        if (profile == null) return null;
        boolean isCLike = profile.family == SyntaxFamily.C_LIKE;
        boolean isPython = profile.family == SyntaxFamily.PYTHON;
        if (!isCLike && !isPython) return null;

        // Détecter le postfix à la position du caret.
        String postfix = null;
        int postfixStart = -1;
        for (String p : POSTFIXES) {
            if (pos >= p.length() && regionEquals(text, pos - p.length(), p)) {
                postfix = p;
                postfixStart = pos - p.length();
                break;
            }
        }
        if (postfix == null) return null;

        // Extraire l'expression avant le postfix.
        int exprStart = findExpressionStart(text, postfixStart);
        if (exprStart < 0 || exprStart >= postfixStart) return null;
        String expr = text.subSequence(exprStart, postfixStart).toString().trim();
        if (expr.isEmpty()) return null;

        // Générer le code postfix.
        String indent = getLineIndent(text, exprStart);
        String generated = generate(postfix, expr, indent, isPython);
        if (generated == null) return null;

        // Le remplacement : supprimer le postfix et l'expression,
        // insérer le code généré.
        int newCaret = exprStart + generated.indexOf('\n') + 1;
        if (newCaret < exprStart) newCaret = exprStart + generated.length();
        return new Result(exprStart, pos, generated, newCaret);
    }

    private static String generate(String postfix, String expr,
            String indent, boolean isPython) {
        String inner = indent + (isPython ? "    " : "    ");
        switch (postfix) {
            case ".if":
                if (isPython) {
                    return "if " + expr + ":\n" + inner;
                }
                return "if (" + expr + ") {\n" + inner + "\n" + indent + "}";
            case ".for":
                if (isPython) {
                    return "for item in " + expr + ":\n" + inner;
                }
                return "for (var item : " + expr + ") {\n" + inner + "\n" + indent + "}";
            case ".notnull":
                if (isPython) {
                    return "if " + expr + " is not None:\n" + inner;
                }
                return "if (" + expr + " != null) {\n" + inner + "\n" + indent + "}";
            case ".return":
                return "return " + expr + ";";
            case ".var":
                return "var v = " + expr + ";";
            case ".while":
                if (isPython) {
                    return "while " + expr + ":\n" + inner;
                }
                return "while (" + expr + ") {\n" + inner + "\n" + indent + "}";
            case ".try":
                return "try {\n" + inner + expr + "\n" + indent + "} catch (Exception e) {\n" + inner + "\n" + indent + "}";
            case ".else":
                if (isPython) {
                    return "if not " + expr + ":\n" + inner;
                }
                return "if (!(" + expr + ")) {\n" + inner + "\n" + indent + "}";
            default:
                return null;
        }
    }

    private static int findExpressionStart(CharSequence text, int end) {
        int i = end - 1;
        // Sauter les espaces avant le point.
        while (i >= 0 && text.charAt(i) == ' ') i--;
        if (i < 0) return -1;
        // Remonter jusqu'à un séparateur (espace, opérateur,
        // début de ligne, point-virgule, accolade).
        int depth = 0;
        while (i >= 0) {
            char c = text.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') { if (depth == 0) { i++; break; } depth--; }
            else if (c == ']') depth++;
            else if (c == '[') { if (depth == 0) { i++; break; } depth--; }
            else if (depth == 0 && (c == ' ' || c == '\n' || c == ';'
                    || c == '{' || c == '}' || c == '=' || c == ',')) {
                i++;
                break;
            }
            i--;
        }
        return Math.max(0, i);
    }

    private static String getLineIndent(CharSequence text, int pos) {
        int lineStart = pos;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\n') lineStart--;
        int end = lineStart;
        while (end < text.length()
                && (text.charAt(end) == ' ' || text.charAt(end) == '\t')) {
            end++;
        }
        return text.subSequence(lineStart, end).toString();
    }

    private static boolean regionEquals(CharSequence text, int start, String s) {
        if (start < 0 || start + s.length() > text.length()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (text.charAt(start + i) != s.charAt(i)) return false;
        }
        return true;
    }
}
