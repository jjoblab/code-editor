package jo.codeeditor.edit;

import jo.codeeditor.languages.LanguageProfile;
import jo.codeeditor.languages.LanguageRegistry;
import jo.codeeditor.languages.SyntaxFamily;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Complétion postfix.
 *
 * <p>L'utilisateur tape {@code obj.if} puis Entrée, et l'éditeur
 * génère {@code if (obj) {\n    \n}}. Depuis 4.5.0, la table des
 * postfixes est une DONNÉE DU PROFIL de langage
 * ({@link LanguageProfile#postfixTemplates}, entrées « nom=gabarit »
 * où le gabarit porte le jeton {@code ${expr}}) : elle complète et
 * surcharge les défauts de la famille ci-dessous, et un langage hors
 * de ces familles qui déclare une table bénéficie des postfixes aussi
 * — l'extension appartient à l'hôte.</p>
 *
 * <p>Défauts C_LIKE :</p>
 * <ul>
 *   <li>{@code .if} → {@code if (expr) { ... }}</li>
 *   <li>{@code .for} → {@code for (var item : expr) { ... }}</li>
 *   <li>{@code .notnull} → {@code if (expr != null) { ... }}</li>
 *   <li>{@code .return} → {@code return expr;}</li>
 *   <li>{@code .var} → {@code var v = expr;}</li>
 *   <li>{@code .while} → {@code while (expr) { ... }}</li>
 *   <li>{@code .try} → {@code try { expr } catch (Exception e) { ... }}</li>
 *   <li>{@code .else} → {@code if (!expr) { ... }}</li>
 * </ul>
 *
 * <p>Défauts PYTHON : formes sans accolades ({@code if expr:},
 * {@code for item in expr:}, {@code if expr is not None:},
 * {@code while expr:}, {@code if not expr:}, plus {@code .return} et
 * {@code .var}). Les profils intégrés {@code java} et {@code kotlin}
 * ajoutent {@code .sout} ({@code System.out.println(expr);} /
 * {@code println(expr)}).</p>
 *
 * <p><b>Périmètre historique préservé</b> : Java, Kotlin, JavaScript,
 * TypeScript, Go, Rust, C, C++, PHP, Swift, Dart, Groovy, Scala
 * (C_LIKE) + Python retombent sur leurs défauts de famille sans rien
 * déclarer.</p>
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

    /** Le jeton du gabarit remplacé par l'expression extraite. */
    private static final String EXPR_TOKEN = "${expr}";

    /** Défauts C_LIKE (ordonnés — ordre de détection historique). */
    private static final String[] C_LIKE_DEFAULTS = {
        "if=if (${expr}) {\n    \n}",
        "for=for (var item : ${expr}) {\n    \n}",
        "notnull=if (${expr} != null) {\n    \n}",
        "return=return ${expr};",
        "var=var v = ${expr};",
        "while=while (${expr}) {\n    \n}",
        "try=try {\n    ${expr}\n} catch (Exception e) {\n    \n}",
        "else=if (!(${expr})) {\n    \n}",
    };

    /** Défauts PYTHON. */
    private static final String[] PYTHON_DEFAULTS = {
        "if=if ${expr}:\n    ",
        "for=for item in ${expr}:\n    ",
        "notnull=if ${expr} is not None:\n    ",
        "return=return ${expr};",
        "var=v = ${expr}",
        "while=while ${expr}:\n    ",
        "else=if not ${expr}:\n    ",
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

        // Vérifier le langage : familles historiques, OU tout langage
        // qui déclare sa propre table (l'extension appartient à l'hôte).
        LanguageProfile profile = LanguageRegistry.forName(language);
        if (profile == null) return null;
        // 4.8.0 : borné aux langages de code — le profil « text » est
        // C_LIKE pour le routage du tokenizer mais n'a pas de syntaxe
        // d'appel : « mot.if » n'a pas de sens sur du texte brut.
        // Une table EXPLICITEMENT déclarée par l'hôte court-circuite
        // la porte (contrat 4.5.0 : « un langage hors de ces familles
        // qui déclare une table bénéficie des postfixes aussi »).
        if (!profile.callSyntax && profile.postfixTemplates.isEmpty()) return null;
        boolean isCLike = profile.family == SyntaxFamily.C_LIKE;
        boolean isPython = profile.family == SyntaxFamily.PYTHON;
        if (!isCLike && !isPython && profile.postfixTemplates.isEmpty()) return null;

        // Table effective : défauts de la famille, puis entrées du
        // profil (surcharge par nom, gabarit vide = retrait).
        Map<String, String> table = effectiveTable(profile, isCLike, isPython);
        if (table.isEmpty()) return null;

        // Détecter le postfix à la position du caret — le PLUS LONG
        // d'abord (une table peut contenir « if » et « ifelse »).
        String postfix = null;
        int postfixStart = -1;
        for (String name : table.keySet()) {
            String p = "." + name;
            if (pos >= p.length() && regionEquals(text, pos - p.length(), p)) {
                if (postfix == null || p.length() > postfix.length()) {
                    postfix = p;
                    postfixStart = pos - p.length();
                }
            }
        }
        if (postfix == null) return null;

        // Extraire l'expression avant le postfix.
        int exprStart = findExpressionStart(text, postfixStart);
        if (exprStart < 0 || exprStart >= postfixStart) return null;
        String expr = text.subSequence(exprStart, postfixStart).toString().trim();
        if (expr.isEmpty()) return null;

        // Générer le code : gabarit + expression, lignes de
        // continuation ré-indentées de l'indentation courante.
        String indent = getLineIndent(text, exprStart);
        String generated = generate(table.get(postfix.substring(1)), expr, indent);
        if (generated == null) return null;

        // Le remplacement : supprimer le postfix et l'expression,
        // insérer le code généré.
        int newCaret = exprStart + generated.indexOf('\n') + 1;
        if (newCaret < exprStart) newCaret = exprStart + generated.length();
        return new Result(exprStart, pos, generated, newCaret);
    }

    /** La table effective : défauts de famille + entrées du profil. */
    private static Map<String, String> effectiveTable(
            LanguageProfile profile, boolean isCLike, boolean isPython) {
        Map<String, String> table = new LinkedHashMap<>();
        if (isCLike) {
            for (String entry : C_LIKE_DEFAULTS) splitInto(table, entry);
        } else if (isPython) {
            for (String entry : PYTHON_DEFAULTS) splitInto(table, entry);
        }
        for (String entry : profile.postfixTemplates) splitInto(table, entry);
        return table;
    }

    /** Coupe « nom=gabarit » et l'insère (surcharge ; gabarit vide = retrait). */
    private static void splitInto(Map<String, String> table, String entry) {
        if (entry == null) return;
        int sep = entry.indexOf('=');
        if (sep <= 0) return;
        String name = entry.substring(0, sep);
        String template = entry.substring(sep + 1);
        if (template.isEmpty()) {
            table.remove(name); // gabarit vide : le profil RETIRE ce nom
        } else {
            table.put(name, template);
        }
    }

    /**
     * Matérialise un gabarit : jeton {@code ${expr}} remplacé par
     * l'expression, chaque ligne de continuation préfixée de
     * l'indentation de la ligne courante.
     */
    private static String generate(String template, String expr, String indent) {
        if (template == null) return null;
        String out = template.replace(EXPR_TOKEN, expr);
        if (!indent.isEmpty() && out.indexOf('\n') >= 0) {
            out = out.replace("\n", "\n" + indent);
        }
        return out;
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
