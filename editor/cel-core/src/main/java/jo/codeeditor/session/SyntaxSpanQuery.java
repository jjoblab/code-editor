package jo.codeeditor.session;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;

import java.util.List;

/**
 * Recherche du span syntaxique englobant une sélection (extraction de
 * {@code EditorSession}, plan 11 — le cliquet de budget de classe
 * exige la décomposition plutôt que le relèvement).
 *
 * <p>Recherche le span syntaxique (chaîne, caractère, chaîne brute,
 * commentaire, doc-comment, emphase, regexp) qui couvre ENTIÈREMENT la
 * sélection {@code [start, end)} — bornes converties en offsets
 * document. Renvoie {@code null} si la sélection s'étend sur plusieurs
 * lignes ou si aucun span englobant n'existe. Les bornes du span
 * INCLUENT les délimiteurs (guillemets, {@code /*} et
 * {@code *&#47;}).</p>
 */
final class SyntaxSpanQuery {

    private SyntaxSpanQuery() {
    }

    /**
     * Le span englobant strict de la sélection mono-ligne, ou
     * {@code null}. Consulte la ligne stylée correspondante dans la
     * liste fournie (eager ou paresseuse — l'accès est indexé).
     */
    static int[] enclosing(EditorDocument doc, List<StyledLine> styled,
            int start, int end) {
        if (end <= start) return null;
        int firstLine = doc.lineForOffset(start);
        int lastLine = doc.lineForOffset(Math.max(start, end - 1));
        if (firstLine != lastLine) return null; // multi-lignes : pas de span simple

        if (firstLine >= styled.size()) return null;
        StyledLine sl = styled.get(firstLine);
        if (sl == null || sl.spans == null) return null;
        int lineStart = doc.lineStart(firstLine);
        for (LineSpan s : sl.spans) {
            TokenType t = s.type;
            if (t != TokenType.STRING && t != TokenType.CHAR
                    && t != TokenType.RAW_STRING && t != TokenType.COMMENT
                    && t != TokenType.DOC_COMMENT && t != TokenType.REGEXP
                    && t != TokenType.EMPHASIS) {
                continue;
            }
            int s0 = lineStart + s.startCol;
            int s1 = lineStart + s.endCol;
            if (s0 <= start && end <= s1) {
                return new int[]{s0, s1};
            }
        }
        return null;
    }
}
