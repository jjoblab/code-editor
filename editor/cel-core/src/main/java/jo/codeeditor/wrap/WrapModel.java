package jo.codeeditor.wrap;

import java.util.*;

/**
 * Word-wrap model: tracks per-document-line wrap-row counts.
 * Uses a prefix sum of visual rows for O(1) line-to-row mapping.
 * Fold-aware: can overlay fold model's visual lines.
 * Ported from CodeAssist WrapModel.kt.
 
 *
 * @since v1.0.0
*/
public class WrapModel {

    /** Per-document-line wrap row count. */
    private int[] rowsPerLine;
    /** Prefix sum: prefixSum[i] = total visual rows for lines 0..i-1. */
    private long[] prefixSum;
    /** Number of document lines. */
    private int lineCount;

    /**
     * Creates a WrapModel with default 1 row per line.
     */
    public WrapModel(int lineCount) {
        this.lineCount = lineCount;
        this.rowsPerLine = new int[lineCount];
        Arrays.fill(rowsPerLine, 1);
        rebuildPrefixSum();
    }

    /**
     * Resize the model when document line count changes.
     */
    public void resize(int newLineCount) {
        if (newLineCount == lineCount) return;
        int[] newRows = new int[newLineCount];
        int copyLen = Math.min(lineCount, newLineCount);
        System.arraycopy(rowsPerLine, 0, newRows, 0, copyLen);
        for (int i = copyLen; i < newLineCount; i++) {
            newRows[i] = 1;
        }
        rowsPerLine = newRows;
        lineCount = newLineCount;
        rebuildPrefixSum();
    }

    /**
     * Set the wrap row count for a specific document line.
     */
    public void setRows(int line, int count) {
        if (line < 0 || line >= lineCount) return;
        rowsPerLine[line] = Math.max(1, count);
        rebuildPrefixSum();
    }

    /**
     * Returns the top visual row for a document line.
     */
    public long topRow(int line) {
        if (line <= 0) return 0;
        if (line >= lineCount) return prefixSum[lineCount];
        return prefixSum[line];
    }

    /**
     * Returns the number of visual rows for a document line.
     */
    public int rowsOf(int line) {
        if (line < 0 || line >= lineCount) return 1;
        return rowsPerLine[line];
    }

    /**
     * Maps a visual row to the document line it belongs to.
     * Uses binary search on the prefix sum.
     */
    public int docLineForRow(long row) {
        if (row < 0) return 0;
        // Binary search
        int lo = 0, hi = lineCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (prefixSum[mid + 1] <= row) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return Math.min(lo, lineCount - 1);
    }

    /**
     * Returns the total number of visual rows.
     */
    public long totalRows() {
        return prefixSum[lineCount];
    }

    /**
     * Returns the number of document lines.
     */
    public int getLineCount() {
        return lineCount;
    }

    private void rebuildPrefixSum() {
        prefixSum = new long[lineCount + 1];
        prefixSum[0] = 0;
        for (int i = 0; i < lineCount; i++) {
            prefixSum[i + 1] = prefixSum[i] + rowsPerLine[i];
        }
    }
}
