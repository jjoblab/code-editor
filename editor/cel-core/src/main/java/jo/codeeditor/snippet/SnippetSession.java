package jo.codeeditor.snippet;

import jo.codeeditor.shift.EditSpan;

import java.util.*;

/**
 * Tab-stop based snippet session.
 * Manages linked/mirrored placeholder ranges for code snippet insertion.
 * Ported from CodeAssist SnippetSession.kt.
 
 *
 * @since v1.0.0
*/
public class SnippetSession {

    /**
     * A tab stop with start, end, and linked indices.
     * Tab stops are ordered by their stop index (0 = final position).
     */
    public static final class TabStop {
        public int start;
        public int end;
        public final int index;
        public final String placeholder;
        /** Indices of other tab stops whose text mirrors this one. */
        public final List<Integer> linked;

        public TabStop(int start, int end, int index, String placeholder, List<Integer> linked) {
            this.start = start;
            this.end = end;
            this.index = index;
            this.placeholder = placeholder != null ? placeholder : "";
            this.linked = linked != null ? Collections.unmodifiableList(linked) : Collections.emptyList();
        }

        public int length() { return end - start; }

        @Override
        public String toString() {
            return "TabStop(" + index + ": [" + start + "," + end + ") \"" + placeholder + "\")";
        }
    }

    private final List<TabStop> stops;
    private int currentIndex;

    /**
     * Creates a snippet session from a list of tab stops.
     * Stops must be sorted by index (descending), with $0 as the final stop.
     */
    public SnippetSession(List<TabStop> stops) {
        this.stops = new ArrayList<>(stops);
        // Sort by index descending so we visit highest-numbered stops first
        this.stops.sort((a, b) -> Integer.compare(b.index, a.index));
        this.currentIndex = this.stops.isEmpty() ? 0 : this.stops.get(0).index;
    }

    /**
     * Parse a simple snippet string and create a session.
     * Supports $1, $2, ... and ${1:placeholder} syntax.
     *
     * @param snippet the snippet text
     * @param baseOffset the offset where the snippet is inserted
     * @return a SnippetSession, or null if no tab stops
     */
    public static SnippetSession parse(String snippet, int baseOffset) {
        List<TabStop> stops = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int pos = 0;
        Map<Integer, List<Integer>> linkedMap = new HashMap<>();

        // First pass: collect all tab stops
        List<int[]> rawStops = new ArrayList<>(); // [index, startPos, endPos, placeholderStart, placeholderEnd]
        while (pos < snippet.length()) {
            if (snippet.charAt(pos) == '$') {
                pos++;
                if (pos >= snippet.length()) break;

                if (snippet.charAt(pos) == '{') {
                    // ${N:placeholder}
                    pos++;
                    int numStart = pos;
                    while (pos < snippet.length() && Character.isDigit(snippet.charAt(pos))) pos++;
                    int index = Integer.parseInt(snippet.substring(numStart, pos));
                    String placeholder = "";
                    if (pos < snippet.length() && snippet.charAt(pos) == ':') {
                        pos++;
                        int phStart = pos;
                        int depth = 1;
                        while (pos < snippet.length() && depth > 0) {
                            if (snippet.charAt(pos) == '{') depth++;
                            else if (snippet.charAt(pos) == '}') depth--;
                            if (depth > 0) pos++;
                        }
                        placeholder = snippet.substring(phStart, pos);
                    }
                    if (pos < snippet.length() && snippet.charAt(pos) == '}') pos++;

                    int start = baseOffset + plain.length();
                    plain.append(placeholder);
                    int end = baseOffset + plain.length();
                    rawStops.add(new int[]{index, start, end, 0, 0});

                } else if (Character.isDigit(snippet.charAt(pos))) {
                    // $N
                    int numStart = pos;
                    while (pos < snippet.length() && Character.isDigit(snippet.charAt(pos))) pos++;
                    int index = Integer.parseInt(snippet.substring(numStart, pos));

                    int start = baseOffset + plain.length();
                    int end = start;
                    rawStops.add(new int[]{index, start, end, 0, 0});
                } else {
                    plain.append('$');
                }
            } else {
                plain.append(snippet.charAt(pos));
                pos++;
            }
        }

        // Group by index for linking
        Map<Integer, List<int[]>> grouped = new HashMap<>();
        for (int[] rs : rawStops) {
            grouped.computeIfAbsent(rs[0], k -> new ArrayList<>()).add(rs);
        }

        // Build stops: first occurrence is primary, rest are linked
        for (var entry : grouped.entrySet()) {
            int index = entry.getKey();
            List<int[]> group = entry.getValue();
            int[] primary = group.get(0);
            List<Integer> linked = new ArrayList<>();
            for (int i = 1; i < group.size(); i++) {
                // Create linked stops
                int[] lnk = group.get(i);
                stops.add(new TabStop(lnk[1], lnk[2], index, "", Collections.emptyList()));
            }
            stops.add(new TabStop(primary[1], primary[2], index, "", linked));
        }

        if (stops.isEmpty()) return null;
        return new SnippetSession(stops);
    }

    /**
     * Returns the current tab stop, or null if finished.
     */
    public TabStop current() {
        for (TabStop s : stops) {
            if (s.index == currentIndex) return s;
        }
        return null;
    }

    /**
     * Move to the next tab stop. Returns the new current stop, or null if done.
     */
    public TabStop next() {
        // Find the highest index less than current
        int nextIndex = Integer.MAX_VALUE;
        for (TabStop s : stops) {
            if (s.index < currentIndex && s.index < nextIndex) {
                nextIndex = s.index;
            }
        }
        if (nextIndex == Integer.MAX_VALUE) return null;
        currentIndex = nextIndex;
        return current();
    }

    /**
     * Move to the previous tab stop.
     */
    public TabStop prev() {
        int prevIndex = -1;
        for (TabStop s : stops) {
            if (s.index > currentIndex && s.index > prevIndex) {
                prevIndex = s.index;
            }
        }
        if (prevIndex < 0) return null;
        currentIndex = prevIndex;
        return current();
    }

    /**
     * Notify the session of an edit so it can re-anchor ranges.
     */
    public void onEdit(EditSpan span) {
        for (TabStop s : stops) {
            int newStart = mapStart(s.start, span);
            int newEnd = mapEnd(s.end, span);
            s.start = newStart;
            s.end = newEnd;
        }
    }

    /**
     * Returns all field ranges (start, end) for highlighting.
     */
    public List<int[]> fieldRanges() {
        List<int[]> ranges = new ArrayList<>();
        for (TabStop s : stops) {
            ranges.add(new int[]{s.start, s.end});
        }
        return ranges;
    }

    /**
     * Sync linked placeholders with the current stop's text.
     * If the current stop has linked stops, their ranges are updated to match.
     *
     * @param text the current document text
     */
    public void mirrorCurrent(String text) {
        TabStop cur = current();
        if (cur == null) return;
        String curText = text.substring(cur.start, cur.end);
        for (TabStop s : stops) {
            if (s.index == cur.index && s != cur) {
                // This is a mirror of the current stop
                int newLen = curText.length();
                s.end = s.start + newLen;
            }
        }
    }

    /**
     * Finish the snippet session and return the final caret position ($0).
     */
    public int finish() {
        for (TabStop s : stops) {
            if (s.index == 0) return s.start;
        }
        // If no $0, return end of last stop
        int maxEnd = 0;
        for (TabStop s : stops) {
            maxEnd = Math.max(maxEnd, s.end);
        }
        return maxEnd;
    }

    /**
     * Returns the list of all tab stops.
     */
    public List<TabStop> getStops() {
        return Collections.unmodifiableList(stops);
    }

    /**
     * Returns the current tab stop index.
     */
    public int getCurrentIndex() {
        return currentIndex;
    }

    // Mapping helpers
    private static int mapStart(int offset, EditSpan span) {
        if (offset <= span.start) return offset;
        if (offset <= span.start + span.removed) return span.start + span.added;
        return offset + span.delta();
    }

    private static int mapEnd(int offset, EditSpan span) {
        if (offset < span.start) return offset;
        if (offset < span.start + span.removed) return span.start;
        return offset + span.delta();
    }
}
