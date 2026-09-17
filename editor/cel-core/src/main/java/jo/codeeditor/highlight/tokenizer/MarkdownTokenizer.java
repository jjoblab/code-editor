package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur Markdown du parser maison : titres, gras, code en ligne,
 * liens, listes et bascule inter-lignes des blocs de code clôturés
 * (état LexState.BLOCK_COMMENT réutilisé). Extrait de SyntaxHighlighter
 * pour isoler cette grammaire de balisage du dispatcher de coloration.
 */
public final class MarkdownTokenizer {

    /**
     * Coloration de ligne Markdown avec prise en charge des blocs de code clôturés.
     */
    public static StyledLine styleMarkdown(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int state = entryState;

        // Bloc de code clôturé : ``` en début de ligne
        if (trimStart(line).startsWith("```")) {
            // Bascule l'état de bloc de code clôturé
            if (state == LexState.NORMAL) {
                state = LexState.BLOCK_COMMENT; // réutilise BLOCK_COMMENT pour le bloc de code clôturé
            } else {
                state = LexState.NORMAL;
            }
            spans.add(new LineSpan(0, line.length(), TokenType.KEYWORD));
            return new StyledLine(spans, entryState, state);
        }

        // Dans un bloc de code clôturé : rendu en commentaire (monospace)
        if (state == LexState.BLOCK_COMMENT) {
            spans.add(new LineSpan(0, line.length(), TokenType.COMMENT));
            return new StyledLine(spans, entryState, state);
        }

        // Markdown normal
        int pos = 0;
        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Titre : # en début de ligne
            if (pos == 0 && ch == '#') {
                int end = pos;
                while (end < line.length() && line.charAt(end) == '#') end++;
                spans.add(new LineSpan(pos, end, TokenType.KEYWORD));
                pos = end;
                continue;
            }

            // Gras : **texte**
            if (ch == '*' && pos + 1 < line.length() && line.charAt(pos + 1) == '*') {
                int close = line.indexOf("**", pos + 2);
                if (close >= 0) {
                    spans.add(new LineSpan(pos, close + 2, TokenType.ANNOTATION));
                    pos = close + 2;
                    continue;
                }
            }

            // Code en ligne : `texte`
            if (ch == '`') {
                int close = line.indexOf('`', pos + 1);
                if (close >= 0) {
                    spans.add(new LineSpan(pos, close + 1, TokenType.STRING));
                    pos = close + 1;
                    continue;
                }
            }

            // Lien : [texte](url)
            if (ch == '[') {
                int closeBracket = line.indexOf(']', pos + 1);
                if (closeBracket >= 0 && closeBracket + 1 < line.length() && line.charAt(closeBracket + 1) == '(') {
                    int closeParen = line.indexOf(')', closeBracket + 2);
                    if (closeParen >= 0) {
                        spans.add(new LineSpan(pos, closeParen + 1, TokenType.FUNC));
                        pos = closeParen + 1;
                        continue;
                    }
                }
            }

            // Liste non ordonnée : - ou * en début de ligne (après blancs optionnels)
            if (pos == 0 && (ch == '-' || ch == '*')) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.PUNCT));
                pos++;
                continue;
            }

            // Texte brut
            int end = pos + 1;
            while (end < line.length()) {
                char c = line.charAt(end);
                if (c == '*' || c == '`' || c == '[' || c == '#') break;
                end++;
            }
            if (end > pos + 1 || spans.isEmpty() || spans.get(spans.size() - 1).type != TokenType.PLAIN) {
                spans.add(new LineSpan(pos, end, TokenType.PLAIN));
            } else {
                // Étend la dernière portion PLAIN
                LineSpan last = spans.get(spans.size() - 1);
                spans.set(spans.size() - 1, new LineSpan(last.startCol, end, TokenType.PLAIN));
            }
            pos = end;
        }

        return new StyledLine(spans, entryState, state);
    }

    /**
     * Supprime les blancs de tête d'une chaîne.
     */
    private static String trimStart(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    private MarkdownTokenizer() {}
}
