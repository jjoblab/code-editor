package jo.codeeditor.fold;

import java.util.*;

/**
 * Une région pliable du document.
 */
public final class FoldRegion {
    public final int start;
    public final int end;
    public final String placeholder;
    public final String kind;
    public final boolean collapsed;

    public FoldRegion(int start, int end, String placeholder, String kind, boolean collapsed) {
        this.start = start;
        this.end = end;
        this.placeholder = placeholder != null ? placeholder : "...";
        this.kind = kind != null ? kind : "block";
        this.collapsed = collapsed;
    }

    @Override
    public String toString() {
        return "FoldRegion([" + start + "," + end + ") " + kind + " " + (collapsed ? "collapsed" : "expanded") + ")";
    }
}
