package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur Python du parser maison : commentaires #, décorateurs,
 * chaînes à triple guillemet (état inter-lignes), f-strings, nombres,
 * mots-clés et types intégrés. Extrait de SyntaxHighlighter pour isoler
 * cette grammaire du dispatcher de coloration.
 */
public final class PythonTokenizer {

    // ── Tokéniseur Python ────────────────────────────────

    /**
     * Tokénise une ligne Python. Prend en charge les commentaires #, les
     * chaînes à triple guillemet, les f-strings, les décorateurs et les
     * mots-clés.
     */
    public static StyledLine stylePython(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Gestion des chaînes à triple guillemet inter-lignes.
        if (state == LexState.BLOCK_COMMENT) {
            int end = findTripleQuoteEnd(line, pos, state);
            if (end >= 0) {
                spans.add(new LineSpan(pos, end, TokenType.STRING));
                pos = end;
                state = LexState.NORMAL;
            } else {
                spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                pos = line.length();
            }
        }

        while (pos < line.length()) {
            char ch = line.charAt(pos);
            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Commentaire #
            if (ch == '#') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Décorateur
            if (ch == '@' && (pos == 0 || Character.isWhitespace(line.charAt(pos - 1)))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '.')) end++;
                spans.add(new LineSpan(pos, end, TokenType.ANNOTATION));
                pos = end;
                continue;
            }

            // Chaîne à triple guillemet
            if (ch == '"' && pos + 2 < line.length() && line.charAt(pos + 1) == '"' && line.charAt(pos + 2) == '"') {
                int end = findTripleQuoteEnd(line, pos + 3, LexState.BLOCK_COMMENT);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end, TokenType.STRING));
                    pos = end;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT;
                }
                continue;
            }

            // Préfixe de f-string
            if ((ch == 'f' || ch == 'F' || ch == 'r' || ch == 'R' || ch == 'b' || ch == 'B')
                && pos + 1 < line.length() && (line.charAt(pos + 1) == '"' || line.charAt(pos + 1) == '\'')) {
                char quote = line.charAt(pos + 1);
                int end = SpanUtils.findStringEnd(line, pos + 2, quote);
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            // Chaîne ordinaire
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
                    if (Character.isDigit(c) || c == '.' || c == '_' || c == 'x' || c == 'X'
                        || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == 'j' || c == 'J') end++;
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
                if (KeywordTables.PYTHON_KEYWORDS.contains(word)) type = TokenType.KEYWORD;
                else if (KeywordTables.PYTHON_TYPES.contains(word)) type = TokenType.TYPE;
                else if (SpanUtils.isAllCaps(word)) type = TokenType.CONSTANT;
                else if (Character.isUpperCase(word.charAt(0))) type = TokenType.TYPE;
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

            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    /** Trouve la fin d'une chaîne à triple guillemet (""" ou '''). */
    private static int findTripleQuoteEnd(String line, int start, int state) {
        for (int i = start; i < line.length() - 2; i++) {
            if (line.charAt(i) == '"' && line.charAt(i + 1) == '"' && line.charAt(i + 2) == '"') {
                return i + 3;
            }
        }
        return -1;
    }

    private PythonTokenizer() {}
}
