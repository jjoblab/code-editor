package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur Smali (bytecode dex Android) du parser maison : directives
 * (.class, .method, …), registres p et v, descripteurs de type L…;,
 * littéraux hexadécimaux et opcodes. Extrait de SyntaxHighlighter pour
 * isoler cette grammaire du dispatcher de coloration.
 */
public final class SmaliTokenizer {

    // ── Tokéniseur Smali ─────────────────────────────────

    /**
     * Tokénise une ligne Smali.
     *
     * <p>Reconnaît :
     * <ul>
     *   <li>directives ({@code .class}, {@code .method}, {@code .field}, …) → KEYWORD</li>
     *   <li>registres ({@code p0}-{@code p9}, {@code v0}-{@code v15}) → VARIABLE</li>
     *   <li>commentaires ({@code #}) → COMMENT</li>
     *   <li>chaînes ({@code "…"}) → STRING</li>
     *   <li>descripteurs de type ({@code Ljava/lang/String;}) → TYPE</li>
     *   <li>littéraux hexadécimaux ({@code 0x1A}) → NUMBER</li>
     *   <li>opcodes (invoke-*, move*, etc.) → FUNC</li>
     * </ul>
     */
    public static StyledLine styleSmali(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Commentaire
            if (ch == '#') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Directive (.class, .method, etc.)
            if (ch == '.') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '-' || line.charAt(end) == '_')) end++;
                String word = line.substring(pos, end);
                if (KeywordTables.SMALI_KEYWORDS.contains(word)) {
                    spans.add(new LineSpan(pos, end, TokenType.KEYWORD));
                } else {
                    spans.add(new LineSpan(pos, end, TokenType.ANNOTATION));
                }
                pos = end;
                continue;
            }

            // Littéral de chaîne
            if (ch == '"') {
                int end = SpanUtils.findStringEnd(line, pos + 1, '"');
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                    continue;
                }
            }

            // Descripteur de type (L...; ou [L...;)
            if (ch == 'L' || (ch == '[' && pos + 1 < line.length() && line.charAt(pos + 1) == 'L')) {
                int end = line.indexOf(';', pos);
                if (end >= 0 && end - pos < 200) {
                    spans.add(new LineSpan(pos, end + 1, TokenType.TYPE));
                    pos = end + 1;
                    continue;
                }
            }

            // Littéral hexadécimal (0x...)
            if (ch == '0' && pos + 1 < line.length() && (line.charAt(pos + 1) == 'x' || line.charAt(pos + 1) == 'X')) {
                int end = pos + 2;
                while (end < line.length() && SpanUtils.isHexChar(line.charAt(end))) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Nombre
            if (Character.isDigit(ch) || ch == '-' || ch == '+') {
                int end = pos + 1;
                while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == 't' || line.charAt(end) == 'L' || line.charAt(end) == 'l')) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            // Registre (p0-p9, v0-v15) ou opcode
            if (Character.isLetter(ch) || ch == '_') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '-' || line.charAt(end) == '/')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (KeywordTables.SMALI_REGISTERS.contains(word)) {
                    type = TokenType.VARIABLE;
                } else if (word.contains("-") || word.endsWith("/")) {
                    // Semblable à un opcode (invoke-direct, move-result-object, etc.)
                    type = TokenType.FUNC;
                } else if (Character.isUpperCase(word.charAt(0))) {
                    type = TokenType.TYPE;
                } else {
                    type = TokenType.PLAIN;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Opérateurs / ponctuation
            if (SpanUtils.OPERATORS.indexOf(ch) >= 0) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                continue;
            }

            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }

    private SmaliTokenizer() {}
}
