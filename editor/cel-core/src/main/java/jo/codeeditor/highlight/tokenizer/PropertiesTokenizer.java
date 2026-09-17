package jo.codeeditor.highlight.tokenizer;

import jo.codeeditor.highlight.LexState;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokéniseur .properties (et .conf) du parser maison : commentaires
 * {@code #}/{@code !}, paires clé=valeur (ou clé:valeur) et séquences
 * d'échappement en valeur. Extrait de SyntaxHighlighter pour isoler cette
 * grammaire du dispatcher de coloration.
 */
public final class PropertiesTokenizer {

    // ── Tokéniseur Properties ────────────────────────────

    /**
     * Tokénise une ligne de fichier Java Properties.
     *
     * <p>Reconnaît :
     * <ul>
     *   <li>commentaires {@code #} et {@code !} → COMMENT</li>
     *   <li>{@code clé=valeur} ou {@code clé:valeur} — clé en PROPERTY,
     *       valeur en STRING</li>
     *   <li>séquences d'échappement (backslash-n, backslash-t, backslash-uXXXX) → ESCAPE</li>
     * </ul>
     */
    public static StyledLine styleProperties(String line, int entryState) {
        List<LineSpan> spans = new ArrayList<>();
        int pos = 0;
        boolean seenEquals = false;

        while (pos < line.length()) {
            char ch = line.charAt(pos);

            // Commentaire (# ou ! en début de ligne, éventuellement après des blancs)
            if (!seenEquals && (ch == '#' || ch == '!')) {
                spans.add(new LineSpan(pos, line.length(), TokenType.COMMENT));
                pos = line.length();
                continue;
            }

            if (Character.isWhitespace(ch)) { pos++; continue; }

            if (!seenEquals && (ch == '=' || ch == ':')) {
                spans.add(new LineSpan(pos, pos + 1, TokenType.OPERATOR));
                pos++;
                seenEquals = true;
                continue;
            }

            if (!seenEquals) {
                // Clé
                int end = pos + 1;
                while (end < line.length() && line.charAt(end) != '=' && line.charAt(end) != ':'
                        && !Character.isWhitespace(line.charAt(end))) {
                    end++;
                }
                spans.add(new LineSpan(pos, end, TokenType.PROPERTY));
                pos = end;
                continue;
            }

            // Valeur — analyse jusqu'à la fin de ligne, gère les échappements
            if (ch == '\\') {
                int end = pos + 2;
                if (pos + 1 < line.length()) {
                    char next = line.charAt(pos + 1);
                    if (next == 'u' && pos + 5 < line.length()) end = pos + 6;
                }
                spans.add(new LineSpan(pos, Math.min(end, line.length()), TokenType.ESCAPE));
                pos = end;
                continue;
            }

            // Valeur de chaîne (reste de la ligne)
            int end = pos + 1;
            while (end < line.length() && line.charAt(end) != '\\') end++;
            spans.add(new LineSpan(pos, end, TokenType.STRING));
            pos = end;
        }
        return new StyledLine(spans, entryState, LexState.NORMAL);
    }

    private PropertiesTokenizer() {}
}
