package jo.codeeditor.fold;

import java.util.*;

/**
 * Fold model: maps between document lines and visual (folded) lines.
 * Ported from CodeAssist FoldModel.kt.
 
 *
 * @since v1.0.0
*/
public class FoldModel {

    /**
     * Per-visual-line info about folds.
     * visualLine[i] tells which doc line(s) it represents.
     */
    public static final class VisualLine {
        public final int docLine;
        public final FoldedLineInfo foldInfo; // null if not folded

        public VisualLine(int docLine, FoldedLineInfo foldInfo) {
            this.docLine = docLine;
            this.foldInfo = foldInfo;
        }
    }

    private final List<VisualLine> visualLines;
    private final int totalDocLines;

    private FoldModel(List<VisualLine> visualLines, int totalDocLines) {
        this.visualLines = visualLines;
        this.totalDocLines = totalDocLines;
    }

    /**
     * Build a fold model from a document's line count and fold regions.
     * Regions are merged and sorted, then hidden lines are computed.
     *
     * @param lineCount total number of document lines
     * @param regions   fold regions (collapsed ones)
     * @param docText   document text (for computing prefix/suffix)
     * @return a FoldModel
     */
    public static FoldModel build(int lineCount, List<FoldRegion> regions, List<String> lineTexts) {
        // Filter to collapsed regions only
        List<FoldRegion> collapsed = new ArrayList<>();
        for (FoldRegion r : regions) {
            if (r.collapsed) collapsed.add(r);
        }

        // Sort by start
        collapsed.sort(Comparator.comparingInt(r -> r.start));

        // Merge overlapping regions
        List<FoldRegion> merged = mergeRegions(collapsed);

        // Compute hidden line ranges
        // For each region, figure out which lines are hidden
        Set<Integer> hiddenLines = new LinkedHashSet<>();
        Map<Integer, FoldedLineInfo> foldInfoMap = new HashMap<>();

        for (FoldRegion r : merged) {
            // Determine start/end lines
            int startLine = 0;
            int endLine = 0;
            if (lineTexts != null) {
                // Compute line from offset
                int offset = 0;
                for (int i = 0; i < lineTexts.size(); i++) {
                    int lineEnd = offset + lineTexts.get(i).length();
                    if (offset <= r.start && r.start <= lineEnd) startLine = i;
                    if (offset <= r.end && r.end <= lineEnd) { endLine = i; break; }
                    offset = lineEnd + 1; // +1 for newline
                }
            }

            // Hide lines between startLine+1 and endLine
            String prefixText = "";
            String suffixText = "";
            if (lineTexts != null && startLine < lineTexts.size()) {
                String firstLine = lineTexts.get(startLine);
                // Find the column of the fold start in the first line
                int foldStartCol = 0;
                int offset = 0;
                for (int i = 0; i < startLine; i++) {
                    offset += lineTexts.get(i).length() + 1;
                }
                foldStartCol = r.start - offset;
                prefixText = firstLine.substring(0, Math.min(foldStartCol, firstLine.length()));

                if (endLine < lineTexts.size()) {
                    String lastLine = lineTexts.get(endLine);
                    int lastLineOffset = 0;
                    for (int i = 0; i < endLine; i++) {
                        lastLineOffset += lineTexts.get(i).length() + 1;
                    }
                    int foldEndCol = r.end - lastLineOffset;
                    suffixText = lastLine.substring(Math.min(foldEndCol, lastLine.length()));
                }
            }

            FoldedLineInfo info = new FoldedLineInfo(
                startLine, endLine, prefixText.length(),
                prefixText.length() + r.placeholder.length(),
                r.placeholder
            );
            foldInfoMap.put(startLine, info);

            for (int i = startLine + 1; i <= endLine; i++) {
                hiddenLines.add(i);
            }
        }

        // Build visual lines
        List<VisualLine> vlines = new ArrayList<>();
        for (int i = 0; i < lineCount; i++) {
            if (!hiddenLines.contains(i)) {
                FoldedLineInfo info = foldInfoMap.get(i);
                vlines.add(new VisualLine(i, info));
            }
        }

        return new FoldModel(vlines, lineCount);
    }

    private static List<FoldRegion> mergeRegions(List<FoldRegion> sorted) {
        if (sorted.isEmpty()) return Collections.emptyList();
        List<FoldRegion> result = new ArrayList<>();
        FoldRegion current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            FoldRegion next = sorted.get(i);
            if (next.start <= current.end) {
                // Overlapping: merge
                int end = Math.max(current.end, next.end);
                current = new FoldRegion(current.start, end, current.placeholder, current.kind, true);
            } else {
                result.add(current);
                current = next;
            }
        }
        result.add(current);
        return result;
    }

    /**
     * Returns true if the given document line is hidden by a fold.
     */
    public boolean isHidden(int docLine) {
        for (VisualLine vl : visualLines) {
            if (vl.foldInfo != null && docLine > vl.docLine && docLine <= vl.foldInfo.endLine) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the fold starting at the given document line, or null.
     */
    public FoldedLineInfo foldStartingAt(int docLine) {
        for (VisualLine vl : visualLines) {
            if (vl.foldInfo != null && vl.docLine == docLine) {
                return vl.foldInfo;
            }
        }
        return null;
    }

    /**
     * Maps a visual row to the document line.
     */
    public int docLineForVisual(int visualRow) {
        if (visualRow < 0) return 0;
        if (visualRow >= visualLines.size()) return totalDocLines - 1;
        return visualLines.get(visualRow).docLine;
    }

    /**
     * Maps a document line to the visual row.
     */
    public int visualForDocLine(int docLine) {
        for (int i = 0; i < visualLines.size(); i++) {
            if (visualLines.get(i).docLine == docLine) return i;
            // If docLine is hidden, return the fold's visual line
            if (visualLines.get(i).foldInfo != null
                && docLine > visualLines.get(i).docLine
                && docLine <= visualLines.get(i).foldInfo.endLine) {
                return i;
            }
        }
        return visualLines.size() - 1;
    }

    /**
     * Returns the composite text for a visual line (prefix + placeholder + suffix).
     */
    public String compositeText(int visualRow, List<String> lineTexts) {
        if (visualRow < 0 || visualRow >= visualLines.size()) return "";
        VisualLine vl = visualLines.get(visualRow);
        if (vl.foldInfo == null) {
            // Not folded: return the line text directly
            if (vl.docLine < lineTexts.size()) return lineTexts.get(vl.docLine);
            return "";
        }
        FoldedLineInfo fi = vl.foldInfo;
        String lineText = vl.docLine < lineTexts.size() ? lineTexts.get(vl.docLine) : "";
        String prefix = lineText.substring(0, Math.min(fi.prefixEnd, lineText.length()));
        String suffix = "";
        if (fi.endLine < lineTexts.size()) {
            String lastLine = lineTexts.get(fi.endLine);
            // Suffix starts at suffixStart offset from the beginning of the last line's fold area
            // For simplicity, use suffixStart as a column in the last line
            int col = fi.suffixStart - fi.prefixEnd - fi.placeholder.length();
            suffix = lastLine.substring(Math.max(0, Math.min(col, lastLine.length())));
        }
        return prefix + fi.placeholder + suffix;
    }

    /**
     * Returns the number of visual lines.
     */
    public int visualLineCount() {
        return visualLines.size();
    }

    /**
     * Returns the total number of document lines.
     */
    public int totalDocLines() {
        return totalDocLines;
    }

    /**
     * Returns the visual lines list.
     */
    public List<VisualLine> getVisualLines() {
        return Collections.unmodifiableList(visualLines);
    }
}
