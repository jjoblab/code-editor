package jo.codeeditor.highlight;

/**
 * A colored span within a line of text.
 * Represents a token from startCol to endCol with a given TokenType.
 
 *
 * @since v1.0.0
*/
public final class LineSpan {
    public final int startCol;
    public final int endCol;
    public final TokenType type;

    public LineSpan(int startCol, int endCol, TokenType type) {
        this.startCol = startCol;
        this.endCol = endCol;
        this.type = type;
    }

    @Override
    public String toString() {
        return "LineSpan(" + startCol + "-" + endCol + ", " + type + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LineSpan)) return false;
        LineSpan s = (LineSpan) o;
        return startCol == s.startCol && endCol == s.endCol && type == s.type;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * startCol + endCol) + type.hashCode();
    }
}
