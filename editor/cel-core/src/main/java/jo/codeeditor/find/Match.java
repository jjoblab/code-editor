package jo.codeeditor.find;

/**
 * A text match with start and end offsets.
 
 *
 * @since v1.0.0
*/
public final class Match {
    public final int start;
    public final int end;

    public Match(int start, int end) {
        this.start = start;
        this.end = end;
    }

    public int length() { return end - start; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Match)) return false;
        Match m = (Match) o;
        return start == m.start && end == m.end;
    }

    @java.lang.Override
    public int hashCode() {
        return 31 * start + end;
    }

    @Override
    public String toString() {
        return "Match(" + start + ", " + end + ")";
    }
}
