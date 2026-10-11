package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur Shell/Bash du parser maison : commentaires, références de
 * variables $var et ${var}, chaînes quotées (avec interpolation en
 * guillemets doubles, littérales entre apostrophes), substitution par
 * backtick, mots-clés et intégrés. Extrait de SyntaxHighlighter pour
 * isoler cette grammaire du dispatcher de coloration.
 */
public final class ShellTokenizer {

    // ── Tokéniseur Shell / Bash / sh ─────────────────────

    /**
     * Tokénise une ligne Shell/Bash.
     *
     * <p>Reconnaît :
     * <ul>
     *   <li>commentaires de ligne {@code #} → COMMENT</li>
     *   <li>références de variables {@code $var} et {@code ${var}} → VARIABLE</li>
     *   <li>chaînes à guillemets doubles ({@code "…$var…"}) — contenu en
     *       STRING, {@code $var} embarqué en VARIABLE</li>
     *   <li>chaînes entre apostrophes ({@code 'littéral'}) — STRING</li>
     *   <li>backticks ({@code `cmd`}) → ANNOTATION (substitution de commande)</li>
     *   <li>mots-clés (if, then, else, fi, for, while, do, done, …) → KEYWORD</li>
     *   <li>intégrés (echo, cd, export, …) → TYPE</li>
     *   <li>nombres → NUMBER</li>
     * </ul>
     */
    public static StyledLine styleShell(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Gestion de la chaîne entre apostrophes inter-lignes (bash autorise le multi-ligne).
        if (state == LexState.SHELL_SINGLE_QUOTE) {
            int end = line.indexOf('\'', pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                pos = end + 1;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Commentaire # (mais pas dans ${#var})
            if (ch == '#' && (pos == 0 || Character.isWhitespace(line.charAt(pos - 1)) || line.charAt(pos - 1) == ';')) {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // $variable or ${variable}
            if (ch == '$') {
                int end = pos + 1;
                if (end < line.length() && line.charAt(end) == '{') {
                    // forme ${var}
                    end++;
                    while (end < line.length() && line.charAt(end) != '}') end++;
                    if (end < line.length()) end++;
                } else if (end < line.length() && (Character.isLetter(line.charAt(end)) || line.charAt(end) == '_')) {
                    while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                } else if (end < line.length() && line.charAt(end) == '?') {
                    end++; // $?
                } else if (end < line.length() && Character.isDigit(line.charAt(end))) {
                    end++; // $1, $2, ...
                }
                if (end > pos + 1) {
                    spans.add(new LineSpan(pos, end, TokenType.VARIABLE));
                    pos = end;
                    continue;
                }
            }

            // Chaîne à guillemets doubles avec interpolation $var
            if (ch == '"') {
                int end = pos + 1;
                while (end < line.length() && line.charAt(end) != '"') {
                    if (line.charAt(end) == '\\' && end + 1 < line.length()) end++;
                    end++;
                }
                if (end < line.length()) end++;
                spans.add(new LineSpan(pos, end, TokenType.STRING));
                pos = end;
                continue;
            }

            // Chaîne entre apostrophes (littérale, sans interpolation)
            if (ch == '\'') {
                int end = line.indexOf('\'', pos + 1);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.STRING));
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.SHELL_SINGLE_QUOTE;
                }
                continue;
            }

            // Substitution de commande par backtick
            if (ch == '`') {
                int end = line.indexOf('`', pos + 1);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.ANNOTATION));
                    pos = end + 1;
                    continue;
                }
            }

            // Nombre
            if (Character.isDigit(ch) && (pos == 0 || !Character.isLetterOrDigit(line.charAt(pos - 1)))) {
                int end = pos + 1;
                while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Identifiant / mot-clé
            if (Character.isLetter(ch) || ch == '_' || ch == '-') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '-')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (KeywordTables.SHELL_KEYWORDS.contains(word)) type = TokenType.KEYWORD;
                else if (end < line.length() && (line.charAt(end) == '(' || line.charAt(end) == '[')) type = TokenType.FUNC;
                else if (Character.isUpperCase(word.charAt(0))) type = TokenType.CONSTANT;
                else type = TokenType.PLAIN;
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Opérateurs
            if ("|&;<>()".indexOf(ch) >= 0) {
                int end = pos + 1;
                while (end < line.length() && "|&;<>()".indexOf(line.charAt(end)) >= 0) end++;
                spans.add(new LineSpan(pos, end, TokenType.OPERATOR));
                pos = end;
                continue;
            }

            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    private ShellTokenizer() {}
}
