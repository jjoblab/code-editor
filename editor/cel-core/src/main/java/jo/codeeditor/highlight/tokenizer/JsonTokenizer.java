package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur JSON du parser maison : clés (PROPERTY) vs valeurs (STRING),
 * nombres, booléens/null et échappements, avec reprise inter-lignes d'une
 * chaîne non fermée. Extrait de SyntaxHighlighter pour isoler cette
 * grammaire du dispatcher de coloration.
 */
public final class JsonTokenizer {

    // ── Tokéniseur JSON ──────────────────────────────────

    /**
     * Tokénise une ligne JSON. Les clés JSON (chaînes avant deux-points)
     * reçoivent {@link TokenType#PROPERTY}, les valeurs
     * {@link TokenType#STRING}. Booléens/null reçoivent
     * {@link TokenType#KEYWORD}.
     */
    public static StyledLine styleJson(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        int state = entryState;

        // Gestion des chaînes inter-lignes (multi-lignes non standard en JSON, mais par sécurité).
        if (state == LexState.XML_STRING) {
            int end = SpanUtils.findXmlStringEnd(line, pos);
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

            // Chaîne (clé ou valeur possible).
            if (ch == '"') {
                int end = SpanUtils.findStringEnd(line, pos + 1, '"');
                if (end >= 0) {
                    // Vérifie s'il s'agit d'une clé (suivie d'un deux-points).
                    int after = end + 1;
                    while (after < line.length() && Character.isWhitespace(line.charAt(after))) after++;
                    TokenType type = (after < line.length() && line.charAt(after) == ':')
                        ? TokenType.PROPERTY : TokenType.STRING;
                    // Met en évidence les séquences d'échappement dans les chaînes.
                    SpanUtils.addStringSpans(spans, line, pos, end + 1, type);
                    pos = end + 1;
                } else {
                    spans.add(new LineSpan(pos, line.length(), TokenType.STRING));
                    pos = line.length();
                    state = LexState.XML_STRING;
                }
                continue;
            }

            // Nombre
            if (Character.isDigit(ch) || (ch == '-' && pos + 1 < line.length() && Character.isDigit(line.charAt(pos + 1)))) {
                int end = pos + 1;
                while (end < line.length()) {
                    char c = line.charAt(end);
                    if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') end++;
                    else break;
                }
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Mot-clé (true, false, null)
            if (Character.isLetter(ch)) {
                int end = pos + 1;
                while (end < line.length() && Character.isLetter(line.charAt(end))) end++;
                String word = line.substring(pos, end);
                if (KeywordTables.JSON_KEYWORDS.contains(word)) {
                    spans.add(new LineSpan(pos, end, TokenType.KEYWORD));
                } else {
                    spans.add(new LineSpan(pos, end, TokenType.PLAIN));
                }
                pos = end;
                continue;
            }

            // Opérateur (deux-points, virgule)
            if (ch == ':' || ch == ',') {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                continue;
            }

            // Ponctuation (crochets)
            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, state);
    }

    private JsonTokenizer() {}
}
