package jo.codeeditor.document;

import jo.codeeditor.rope.Rope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Line-indexed text model backed by a Rope.
 * <p>
 * Maintains a parallel array of line start offsets for O(log n) line lookups.
 * The replace() method splices the line index incrementally: reuses the
 * unchanged prefix, scans breaks in the replacement, and shifts the suffix
 * by the edit delta.
 
 *
 * @since v1.0.0
*/
public final class EditorDocument {

    private Rope rope;
    private int[] lineStarts;
    private int revision;
    private String cachedText;
    private int cachedRevision = -1;

    private EditorDocument(Rope rope, int[] lineStarts, int revision) {
        this.rope = rope;
        this.lineStarts = lineStarts;
        this.revision = revision;
    }

    /**
     * Creates a new EditorDocument from the given text.
     */
    public static EditorDocument of(String text) {
        if (text == null) text = "";
        Rope rope = Rope.fromString(text);
        int[] starts = computeLineStarts(text);
        return new EditorDocument(rope, starts, 0);
    }

    /**
     * Computes line start offsets for the given text.
     * lineStarts[0] = 0, and each subsequent entry is the offset after a '\n'.
     */
    private static int[] computeLineStarts(String text) {
        if (text.isEmpty()) return new int[]{0};
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < starts.size(); i++) {
            result[i] = starts.get(i);
        }
        return result;
    }

    // ── Accessors ─────────────────────────────────────────────────

    /** Returns the full document text (cached per revision). */
    public String getText() {
        if (cachedRevision != revision) {
            cachedText = rope.toString();
            cachedRevision = revision;
        }
        return cachedText;
    }

    /** Returns the total number of characters. */
    public int length() {
        return rope.length();
    }

    /** Returns the number of lines. */
    public int lineCount() {
        return lineStarts.length;
    }

    /** Returns the character at the given offset. */
    public char charAt(int offset) {
        return rope.charAt(offset);
    }

    /** Returns the current revision number. */
    public int getRevision() {
        return revision;
    }

    // ── Line queries ──────────────────────────────────────────────

    /**
     * Returns the line number for the given character offset.
     * Uses binary search on lineStarts.
     */
    public int lineForOffset(int offset) {
        if (offset < 0) return 0;
        if (offset >= rope.length()) return lineStarts.length - 1;

        int lo = 0, hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /**
     * Returns the start offset of the given line (0-indexed, clamped).
     */
    public int lineStart(int line) {
        line = clampLine(line);
        return lineStarts[line];
    }

    /**
     * Returns the end offset of the given line (exclusive, clamped).
     * This is the offset of the newline character, or the document length
     * for the last line.
     */
    public int lineEnd(int line) {
        line = clampLine(line);
        if (line + 1 < lineStarts.length) {
            // End is just before the next line's start (the '\n')
            return lineStarts[line + 1] - 1;
        }
        return rope.length();
    }

    /**
     * Returns the text content of the given line (without the trailing newline).
     */
    public String lineText(int line) {
        int start = lineStart(line);
        int end = lineEnd(line);
        if (start >= end) return "";
        return getText().substring(start, end);
    }

    private int clampLine(int line) {
        return Math.max(0, Math.min(line, lineStarts.length - 1));
    }

    // ── Mutation ──────────────────────────────────────────────────

    /**
     * Replaces the text in [start, end) with the given insertion.
     * Returns a new EditorDocument with the line index spliced incrementally.
     *
     * @param start     start offset (inclusive)
     * @param end       end offset (exclusive)
     * @param insertion the replacement text
     * @return new EditorDocument with the edit applied
     */
    public EditorDocument replace(int start, int end, String insertion) {
        if (start < 0 || end > rope.length() || start > end) {
            throw new IndexOutOfBoundsException(
                "start=" + start + ", end=" + end + ", length=" + rope.length());
        }
        if (start == end && insertion.isEmpty()) return this;

        // Apply to rope
        Rope newRope = rope.replace(start, end, insertion);

        // Incremental line index splice (matches CodeAssist algorithm exactly)
        int delta = insertion.length() - (end - start);

        int firstLine = lineForOffset(start);
        int lastLine = (end > start) ? lineForOffset(end) : firstLine;

        // Count newlines in the replacement
        int breaks = 0;
        for (int i = 0; i < insertion.length(); i++) {
            if (insertion.charAt(i) == '\n') breaks++;
        }

        int tailCount = lineStarts.length - 1 - lastLine;
        int[] newLineStarts = new int[firstLine + 1 + breaks + tailCount];

        // Unchanged prefix: lineStarts[0..firstLine]
        System.arraycopy(lineStarts, 0, newLineStarts, 0, firstLine + 1);

        // Starts created inside the replacement
        int w = firstLine + 1;
        for (int i = 0; i < insertion.length(); i++) {
            if (insertion.charAt(i) == '\n') {
                newLineStarts[w++] = start + i + 1;
            }
        }

        // Shifted suffix: lines after lastLine
        for (int r = lastLine + 1; r < lineStarts.length; r++) {
            newLineStarts[w++] = lineStarts[r] + delta;
        }

        return new EditorDocument(newRope, newLineStarts, revision + 1);
    }

    @Override
    public String toString() {
        return "EditorDocument(lines=" + lineCount() + ", len=" + length() + ", rev=" + revision + ")";
    }

    // ── Large document check ─────────────────────────────────────

    private static final int CHAR_LIMIT = 2_500_000;
    private static final int LINE_LIMIT = 50_000;

    /**
     * Returns true if this document exceeds the "large" thresholds:
     * 2.5M characters or 50K lines.
     */
    public boolean isLarge() {
        return rope.length() > CHAR_LIMIT || lineStarts.length > LINE_LIMIT;
    }
}
