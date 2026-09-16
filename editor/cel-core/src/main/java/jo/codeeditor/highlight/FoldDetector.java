package jo.codeeditor.highlight;

import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Multi-language fold region detector. Pure Java, testable on host JVM.
 *
 * <p>Detects foldable code blocks for:
 * <ul>
 *   <li>Brace-based languages (Java, Kotlin, JS, C, etc.) — {@code { ... }}</li>
 *   <li>Lua — {@code function...end}, {@code do...end}, {@code if...then...end}</li>
 *   <li>Python — indent-based ({@code def}, {@code class}, {@code if}, etc.)</li>
 *   <li>XML — element blocks ({@code <tag> ... </tag>})</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "java");
 * session.setFoldRegions(folds);
 * }</pre>
 *
 * @since v3.3.0
 */
public final class FoldDetector {

    private FoldDetector() {}

    /**
     * Detects fold regions for the given text and language.
     *
     * @param text     the full document text
     * @param language the language id ("java", "kotlin", "lua", "python", "xml", etc.)
     * @return a list of fold regions (may be empty)
     */
    public static List<DiagnosticShift.FoldRegion> detect(String text, String language) {
        if (text == null || text.isEmpty()) return new ArrayList<>();
        if (language == null) return detectBraceFolds(text);
        switch (language.toLowerCase(java.util.Locale.ROOT)) {
            case "lua": return detectLuaFolds(text);
            case "python": case "py": return detectPythonFolds(text);
            case "xml": return detectXmlFolds(text);
            case "markdown": case "md": return detectMarkdownFolds(text);
            default: return detectBraceFolds(text);
        }
    }

    // ── Brace-based: Java, Kotlin, JS, C, etc. ──────────────────

    /**
     * Detects { ... } blocks. Skips braces inside strings and comments.
     */
    public static List<DiagnosticShift.FoldRegion> detectBraceFolds(String text) {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        ArrayDeque<int[]> stack = new ArrayDeque<>(); // [offset, lineIdx]
        String[] lines = text.split("\n", -1);
        boolean inString = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        char stringChar = 0;
        int offset = 0;

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            inLineComment = false;
            for (int ci = 0; ci < line.length(); ci++) {
                char c = line.charAt(ci);
                char next = ci + 1 < line.length() ? line.charAt(ci + 1) : 0;

                if (inLineComment) break;
                if (inBlockComment) {
                    if (c == '*' && next == '/') { inBlockComment = false; ci++; }
                    continue;
                }
                if (inString) {
                    if (c == '\\') { ci++; continue; }
                    if (c == stringChar) inString = false;
                    continue;
                }
                // Comment detection.
                if (c == '/' && next == '/') { inLineComment = true; break; }
                if (c == '/' && next == '*') { inBlockComment = true; ci++; continue; }
                // String detection.
                if (c == '"' || c == '\'') { inString = true; stringChar = c; continue; }

                if (c == '{') {
                    stack.push(new int[]{offset + ci, li});
                } else if (c == '}' && !stack.isEmpty()) {
                    int[] open = stack.pop();
                    if (li - open[1] >= 2) {
                        folds.add(new DiagnosticShift.FoldRegion(
                            open[0], offset + ci + 1, "{\u2026}", "block", false));
                    }
                }
            }
            offset += lines[li].length() + 1;
        }
        return folds;
    }

    // ── Lua: function...end, do...end, if...then...end ───────────

    /**
     * Detects Lua blocks. Tracks function/do/for/while openers and end closers.
     */
    public static List<DiagnosticShift.FoldRegion> detectLuaFolds(String text) {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        ArrayDeque<int[]> stack = new ArrayDeque<>(); // [startOffset, startLine]
        String[] lines = text.split("\n", -1);
        int offset = 0;

        for (int li = 0; li < lines.length; li++) {
            String trimmed = lines[li].trim();
            // Skip comments and strings.
            if (trimmed.startsWith("--")) { offset += lines[li].length() + 1; continue; }

            // Detect block openers.
            boolean opens = trimmed.startsWith("function ")
                || trimmed.startsWith("local function ")
                || (trimmed.startsWith("if ") && trimmed.contains(" then"))
                || (trimmed.startsWith("for ") && trimmed.contains(" do"))
                || (trimmed.startsWith("while ") && trimmed.contains(" do"))
                || trimmed.equals("do")
                || trimmed.startsWith("repeat");
            if (opens) {
                stack.push(new int[]{offset, li});
            }
            // Detect block closers.
            if (trimmed.equals("end") || trimmed.startsWith("end ") || trimmed.startsWith("end)")
                || trimmed.startsWith("end}") || trimmed.startsWith("until ")) {
                if (!stack.isEmpty()) {
                    int[] open = stack.pop();
                    if (li - open[1] >= 2) {
                        folds.add(new DiagnosticShift.FoldRegion(
                            open[0], offset + trimmed.length(), "{\u2026}", "block", false));
                    }
                }
            }
            offset += lines[li].length() + 1;
        }
        return folds;
    }

    // ── Python: indent-based ─────────────────────────────────────

    /**
     * Detects Python blocks via indentation. A block starts when a line
     * ending with ':' is followed by a more-indented line, and ends when
     * the indentation returns to the block opener's level.
     */
    public static List<DiagnosticShift.FoldRegion> detectPythonFolds(String text) {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        ArrayDeque<int[]> stack = new ArrayDeque<>(); // [lineIdx, indent, offset]
        int offset = 0;

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String trimmed = line.trim();
            int indent = line.length() - line.stripLeading().length();

            // Pop blocks whose indent >= current indent (dedent closes them).
            while (!stack.isEmpty() && stack.peek()[1] >= indent && !trimmed.isEmpty()) {
                int[] open = stack.pop();
                if (li - open[0] >= 2) {
                    folds.add(new DiagnosticShift.FoldRegion(
                        open[2], offset, ": \u2026", "block", false));
                }
            }

            // Push block openers (lines ending with ':').
            if (trimmed.endsWith(":") && (
                trimmed.startsWith("def ") || trimmed.startsWith("class ")
                || trimmed.startsWith("if ") || trimmed.startsWith("elif ")
                || trimmed.startsWith("for ") || trimmed.startsWith("while ")
                || trimmed.startsWith("try:") || trimmed.startsWith("try :")
                || trimmed.startsWith("with ") || trimmed.startsWith("except ")
                || trimmed.startsWith("else:") || trimmed.startsWith("finally:"))) {
                stack.push(new int[]{li, indent, offset});
            }
            offset += lines[li].length() + 1;
        }
        // Close remaining open blocks at EOF.
        while (!stack.isEmpty()) {
            int[] open = stack.pop();
            if (lines.length - open[0] >= 3) {
                folds.add(new DiagnosticShift.FoldRegion(
                    open[2], offset, ": \u2026", "block", false));
            }
        }
        return folds;
    }

    // ── XML: element blocks ──────────────────────────────────────

    /**
     * Detects XML element blocks: <tag ...> ... </tag>.
     */
    public static List<DiagnosticShift.FoldRegion> detectXmlFolds(String text) {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        // Simple regex-free scan: find <tag> and matching </tag>.
        int pos = 0;
        while (pos < text.length()) {
            int openIdx = text.indexOf('<', pos);
            if (openIdx < 0) break;
            // Skip <?xml, <!--, <![CDATA[
            if (openIdx + 1 < text.length() && (text.charAt(openIdx + 1) == '?'
                || text.charAt(openIdx + 1) == '!' || text.charAt(openIdx + 1) == '/')) {
                pos = openIdx + 1;
                continue;
            }
            // Extract tag name.
            int tagEnd = text.indexOf('>', openIdx);
            if (tagEnd < 0) break;
            String tagContent = text.substring(openIdx + 1, tagEnd).trim();
            // Skip self-closing tags (<tag/>).
            if (tagContent.endsWith("/")) { pos = tagEnd + 1; continue; }
            // Skip tags with attributes — extract the name.
            int spaceIdx = tagContent.indexOf(' ');
            String tagName = spaceIdx >= 0 ? tagContent.substring(0, spaceIdx) : tagContent;
            if (tagName.isEmpty()) { pos = tagEnd + 1; continue; }

            // Find matching </tagName>.
            String closeTag = "</" + tagName + ">";
            int closeIdx = text.indexOf(closeTag, tagEnd + 1);
            if (closeIdx < 0) { pos = tagEnd + 1; continue; }

            // Only fold if it spans multiple lines.
            int newlines = 0;
            for (int k = tagEnd; k < closeIdx; k++) if (text.charAt(k) == '\n') newlines++;
            if (newlines >= 2) {
                folds.add(new DiagnosticShift.FoldRegion(
                    openIdx, closeIdx + closeTag.length(), "<" + tagName + ">\u2026", "element", false));
            }
            pos = closeIdx + closeTag.length();
        }
        return folds;
    }

    // ── Markdown: headings ───────────────────────────────────────

    /**
     * Detects Markdown foldable sections: each heading starts a section
     * that extends to the next heading of the same or higher level.
     */
    public static List<DiagnosticShift.FoldRegion> detectMarkdownFolds(String text) {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int offset = 0;
        int lastHeadingOffset = -1;
        int lastHeadingLevel = 0;
        int lastHeadingLine = -1;

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li].trim();
            if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                // Close previous heading if this one is same or higher level.
                if (lastHeadingOffset >= 0 && level <= lastHeadingLevel
                    && li - lastHeadingLine >= 2) {
                    folds.add(new DiagnosticShift.FoldRegion(
                        lastHeadingOffset, offset, "#\u2026", "heading", false));
                }
                lastHeadingOffset = offset;
                lastHeadingLevel = level;
                lastHeadingLine = li;
            }
            offset += lines[li].length() + 1;
        }
        // Close last heading at EOF.
        if (lastHeadingOffset >= 0 && lines.length - lastHeadingLine >= 3) {
            folds.add(new DiagnosticShift.FoldRegion(
                lastHeadingOffset, offset, "#\u2026", "heading", false));
        }
        return folds;
    }
}
