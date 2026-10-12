package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur TOML du parser maison : commentaires, en-têtes de section
 * [table] et [[table de tableaux]], paires clé = valeur (chaînes,
 * nombres, dates, booléens). Extrait de SyntaxHighlighter pour isoler
 * cette grammaire du dispatcher de coloration.
 */
public final class TomlTokenizer {

    // ── Tokéniseur TOML ──────────────────────────────────

    /**
     * Tokénise une ligne TOML.
     *
     * <p>Reconnaît :
     * <ul>
     *   <li>commentaires {@code #} → COMMENT</li>
     *   <li>en-têtes {@code [section]} et {@code [[tableau-de-tableaux]]} → TYPE</li>
     *   <li>{@code clé = valeur} — clé en PROPERTY, valeur en STRING/NUMBER/CONSTANT</li>
     *   <li>chaînes (basique, littérale, basique multi-lignes, littérale multi-lignes) → STRING</li>
     *   <li>nombres, booléens, dates → NUMBER/CONSTANT</li>
     * </ul>
     */
    public static StyledLine styleToml(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        boolean seenEquals = false;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Commentaire
            if (ch == '#') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // En-tête de section [nom] ou [[nom]]
            if (!seenEquals && ch == '[') {
                int closeCount = 0;
                while (pos < line.length() && line.charAt(pos) == '[') { closeCount++; pos++; }
                int end = pos;
                while (end < line.length() && line.charAt(end) != ']') end++;
                if (end < line.length()) {
                    spans.add(new LineSpan(pos, end, TokenType.TYPE));
                    spans.add(new LineSpan(end, end + closeCount, TokenType.PUNCT));
                    pos = end + closeCount;
                    continue;
                }
            }

            // Signe égal
            if (ch == '=' && !seenEquals) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                seenEquals = true;
                continue;
            }

            if (!seenEquals) {
                // Clé
                int end = pos + 1;
                while (end < line.length() && line.charAt(end) != '=' && !Character.isWhitespace(line.charAt(end))) end++;
                spans.add(new LineSpan(pos, end, TokenType.PROPERTY));
                pos = end;
                continue;
            }

            // Valeur
            if (ch == '"' || ch == '\'') {
                int end = SpanUtils.findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                    continue;
                }
            }

            if (Character.isDigit(ch) || (ch == '-' && pos + 1 < line.length() && Character.isDigit(line.charAt(pos + 1)))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.' || line.charAt(end) == '-' || line.charAt(end) == ':' || line.charAt(end) == 'T' || line.charAt(end) == 'Z')) end++;
                spans.add(new LineSpan(pos, end, TokenType.NUMBER));
                pos = end;
                continue;
            }

            if (Character.isLetter(ch)) {
                int end = pos + 1;
                while (end < line.length() && Character.isLetter(line.charAt(end))) end++;
                String word = line.substring(pos, end);
                TokenType type = KeywordTables.TOML_KEYWORDS.contains(word) ? TokenType.CONSTANT : TokenType.STRING;
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
            pos++;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }

    private TomlTokenizer() {}
}
