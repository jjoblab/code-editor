package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur YAML du parser maison : clés vs valeurs (après deux-points),
 * commentaires, séparateurs de document, ancres/alias/tags, chaînes
 * quotées et scalaires brutes. Extrait de SyntaxHighlighter pour isoler
 * cette grammaire du dispatcher de coloration.
 */
public final class YamlTokenizer {

    // ── Tokéniseur YAML ──────────────────────────────────

    /**
     * Tokénise une ligne YAML.
     *
     * <p>Reconnaît :
     * <ul>
     *   <li>{@code key:} (avec espace optionnel) → PROPERTY</li>
     *   <li>commentaires {@code #} → COMMENT</li>
     *   <li>séparateur de document {@code ---} → KEYWORD</li>
     *   <li>{@code &ancre}, {@code *alias}, {@code !tag} → ANNOTATION</li>
     *   <li>chaînes (entre guillemets ou brutes) → STRING</li>
     *   <li>nombres / booléens / null → NUMBER/CONSTANT</li>
     *   <li>séquences en flux {@code [a, b, c]} et mappings en flux {@code {k: v}} → PUNCT</li>
     * </ul>
     */
    public static StyledLine styleYaml(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        boolean afterColon = false;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            if (Character.isWhitespace(ch)) { pos++; continue; }

            // Commentaire #
            if (ch == '#') {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            // Séparateur de document
            if (ch == '-' && pos + 2 < line.length() && line.charAt(pos + 1) == '-' && line.charAt(pos + 2) == '-') {
                spans.add(new LineSpan(pos, pos + 3, TokenType.KEYWORD));
                pos += 3;
                continue;
            }

            // Ancre/alias/tag
            if (ch == '&' || ch == '*' || ch == '!') {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '_' || line.charAt(end) == '-')) end++;
                spans.add(new LineSpan(pos, end, TokenType.ANNOTATION));
                pos = end;
                continue;
            }

            // Littéral de chaîne (entre guillemets)
            if (ch == '"' || ch == '\'') {
                int end = SpanUtils.findStringEnd(line, pos + 1, ch);
                if (end >= 0) {
                    SpanUtils.addStringSpans(spans, line, pos, end + 1, TokenType.STRING);
                    pos = end + 1;
                    afterColon = false;
                    continue;
                }
            }

            // Nombre / booléen / null (uniquement en valeur, jamais en clé)
            if (afterColon && (Character.isDigit(ch) || ch == '-' || ch == '.' || Character.isLetter(ch))) {
                int end = pos + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || line.charAt(end) == '.' || line.charAt(end) == '-' || line.charAt(end) == '_' || line.charAt(end) == ':' || line.charAt(end) == '/')) end++;
                String word = line.substring(pos, end);
                TokenType type;
                if (Character.isDigit(word.charAt(0)) || (word.length() > 1 && (word.charAt(0) == '-' || word.charAt(0) == '+') && Character.isDigit(word.charAt(1)))) {
                    type = TokenType.NUMBER;
                } else if ("true".equals(word) || "false".equals(word) || "True".equals(word) || "False".equals(word)
                        || "yes".equals(word) || "no".equals(word) || "Yes".equals(word) || "No".equals(word)
                        || "null".equals(word) || "Null".equals(word) || "~".equals(word)) {
                    type = TokenType.CONSTANT;
                } else {
                    type = TokenType.STRING;
                }
                spans.add(new LineSpan(pos, end, type));
                pos = end;
                continue;
            }

            // Indicateur de scalaire de bloc (| ou >) — émis en KEYWORD
            if (!afterColon && (ch == '|' || ch == '>')) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.KEYWORD));
                pos++;
                continue;
            }

            // Clé — identifiant suivi de ':' (ou fin de ligne)
            if (!afterColon && (Character.isLetter(ch) || ch == '_' || ch == '"' || ch == '\'' || ch == '-' || ch == '.' || ch == '/')) {
                int end = pos + 1;
                while (end < line.length()
                        && line.charAt(end) != ':'
                        && line.charAt(end) != ' '
                        && line.charAt(end) != '\t'
                        && line.charAt(end) != '#') {
                    end++;
                }
                // Vérifie s'il est suivi de ':'
                int lookAhead = end;
                while (lookAhead < line.length() && Character.isWhitespace(line.charAt(lookAhead))) lookAhead++;
                if (lookAhead < line.length() && line.charAt(lookAhead) == ':') {
                    spans.add(new LineSpan(pos, end, TokenType.PROPERTY));
                    pos = end;
                    continue;
                }
                // Sinon c'est une valeur scalaire brute
                spans.add(new LineSpan(pos, end, TokenType.STRING));
                pos = end;
                afterColon = true;
                continue;
            }

            // Deux-points (après la clé)
            if (ch == ':') {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                afterColon = true;
                continue;
            }

            // Indicateur d'élément de liste
            if (ch == '-') {
                int next = pos + 1;
                if (next >= line.length() || Character.isWhitespace(line.charAt(next))) {
                    spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                    pos++;
                    continue;
                }
            }

            // Indicateurs de flux
            if ("[]{}".indexOf(ch) >= 0) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
                pos++;
                continue;
            }

            // Valeur scalaire brute (continuation)
            int end = pos + 1;
            while (end < line.length() && line.charAt(end) != '#' && line.charAt(end) != ':' && !Character.isWhitespace(line.charAt(end))) end++;
            spans.add(new LineSpan(pos, end, TokenType.STRING));
            pos = end;
            afterColon = true;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }

    private YamlTokenizer() {}
}
