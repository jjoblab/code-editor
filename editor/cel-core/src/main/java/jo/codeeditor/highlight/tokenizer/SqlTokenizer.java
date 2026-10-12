package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur SQL du parser maison : commentaires de ligne et de bloc
 * (multi-lignes), chaînes entre apostrophes, identifiants quotés,
 * mots-clés et nombres. Extrait de SyntaxHighlighter pour isoler cette
 * grammaire du dispatcher de coloration.
 */
public final class SqlTokenizer {

    // ── Tokéniseur SQL ───────────────────────────────────

    /**
     * Tokénise une ligne SQL.
     *
     * <p>Reconnaît :
     * <ul>
     *   <li>commentaires de ligne {@code --} → COMMENT</li>
     *   <li>commentaires de bloc (slash-astérisque ... astérisque-slash, peuvent s'étendre sur plusieurs lignes) → COMMENT</li>
     *   <li>chaînes entre apostrophes ({@code 'littéral'}) → STRING</li>
     *   <li>identifiants entre guillemets doubles ({@code "nom"}) → PROPERTY</li>
     *   <li>mots-clés (SELECT, FROM, WHERE, …) → KEYWORD</li>
     *   <li>nombres → NUMBER</li>
     * </ul>
     */
    public static StyledLine styleSql(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Gestion du commentaire de bloc inter-lignes.
        if (state == LexState.BLOCK_COMMENT) {
            int end = line.indexOf("*/", pos);
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

            // Commentaire de ligne
            if (ch == '-' && pos + 1 < line.length() && line.charAt(pos + 1) == '-') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Commentaire de bloc
            if (ch == '/' && pos + 1 < line.length() && line.charAt(pos + 1) == '*') {
                int end = line.indexOf("*/", pos + 2);
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 2, TokenType.COMMENT));
                    pos = end + 2;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                    pos = line.length();
                    state = LexState.BLOCK_COMMENT;
                }
                continue;
            }

            // Chaîne entre apostrophes
            if (ch == '\'') {
                int end = SpanUtils.findStringEnd(line, pos + 1, '\'');
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                }
                continue;
            }

            // Identifiant entre guillemets doubles
            if (ch == '"') {
                int end = SpanUtils.findStringEnd(line, pos + 1, '"');
                if (end >= 0) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.PROPERTY));
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.PROPERTY));
                    pos = line.length();
                }
                continue;
            }

            // Nombre
            if (Character.isDigit(ch) || (ch == '.' && pos + 1 < line.length() && Character.isDigit(line.charAt(pos + 1)))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) end++;
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
                if (KeywordTables.SQL_KEYWORDS.contains(word)) type = TokenType.KEYWORD;
                else if (end < line.length() && line.charAt(end) == '(') type = TokenType.FUNC;
                else if (Character.isUpperCase(word.charAt(0))) type = TokenType.CONSTANT;
                else type = TokenType.PLAIN;
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Opérateurs
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

    private SqlTokenizer() {}
}
