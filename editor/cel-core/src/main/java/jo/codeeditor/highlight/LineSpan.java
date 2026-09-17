package jo.codeeditor.highlight;

/**
 * Portion colorée au sein d'une ligne de texte.
 * Représente un jeton de startCol à endCol avec un TokenType donné.
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
