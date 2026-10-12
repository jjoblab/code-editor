package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur Lua du parser maison : commentaires -- et --[[ ]], chaînes
 * longues [[ ]], chaînes quotées avec échappements, mots-clés et
 * fonctions intégrées. Extrait de SyntaxHighlighter pour isoler cette
 * grammaire du dispatcher de coloration.
 */
public final class LuaTokenizer {

    // ── Tokéniseur Lua ───────────────────────────────────

    /**
     * Tokénise une ligne Lua. Prend en charge :
     * <ul>
     *   <li>commentaires de ligne {@code --} et commentaires de bloc {@code --[[ }]]}</li>
     *   <li>chaînes à guillemets simples/doubles avec séquences d'échappement</li>
     *   <li>chaînes longues {@code [[ ... ]]}</li>
     *   <li>mots-clés, fonctions intégrées, nombres, opérateurs</li>
     * </ul>
     */
    public static StyledLine styleLua(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Gestion inter-lignes du commentaire de bloc (--[[ ]]) et de la chaîne longue ([[ ]])
        if (state == LexState.BLOCK_COMMENT) {
            int end = line.indexOf("]]", pos);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                pos = end + 2;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);
            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Commentaire de ligne --
            if (ch == '-' && pos + 1 < line.length() && line.charAt(pos + 1) == '-') {
                // Vérifie un commentaire de bloc --[[
                if (pos + 3 < line.length() && line.charAt(pos + 2) == '[' && line.charAt(pos + 3) == '[') {
                    int end = line.indexOf("]]", pos + 4);
                    if (end >= 0) {
                        spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                        pos = end + 2;
                    } else {
                        spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                        pos = line.length();
                        state = LexState.BLOCK_COMMENT;
                    }
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                }
                continue;
            }

            // [[ chaîne longue ]]
            if (ch == '[' && pos + 1 < line.length() && line.charAt(pos + 1) == '[') {
                int end = line.indexOf("]]", pos + 2);
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 2, TokenType.STRING);
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT; // réutilisé pour les chaînes longues
                }
                continue;
            }

            // Littéral de chaînes
            if (ch == '"' || ch == '\'') {
                int end = SpanUtils.findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            // Nombre
            if (Character.isDigit(ch)) {
                int end = pos + 1;
                while (end < line.length()) {
                    char c = line.charAt(end);
                    if (Character.isDigit(c) || c == '.' || c == 'x' || c == 'X'
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')) end++;
                    else break;
                }
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Identifiant / mot-clé
            if (Character.isLetter(ch) || ch == '_') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (KeywordTables.LUA_KEYWORDS.contains(word)) type = TokenType.KEYWORD;
                else if (KeywordTables.LUA_BUILTINS.contains(word)) type = TokenType.TYPE;
                else if (SpanUtils.isAllCaps(word)) type = TokenType.CONSTANT;
                else if (end < line.length() && line.charAt(end) == '(') type = TokenType.FUNC;
                else type = TokenType.PLAIN;
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Opérateur
            if (SpanUtils.OPERATORS.indexOf(ch) >= 0) {
                int end = pos + 1;
                while (end < line.length() && SpanUtils.OPERATORS.indexOf(line.charAt(end)) >= 0) end++;
                spans.add(new LineSpan(pos, end, TokenType.OPERATOR));
                pos = end;
                continue;
            }

            // Ponctuation
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    private LuaTokenizer() {}
}
