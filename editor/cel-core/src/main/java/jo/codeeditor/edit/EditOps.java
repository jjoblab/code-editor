package jo.codeeditor.edit;

import java.util.*;

/**
 * Smart editing as pure functions over CharSequence.
 * Ported from CodeAssist EditOps.kt.
 * <p>
 * All methods are stateless; they take text + cursor info and return a RangeEdit.
 
 *
 * @since v1.0.0
*/
public final class EditOps {

    private EditOps() {}

    // ── Bracket / quote pairs ─────────────────────────────────────

    private static final String OPENERS  = "({[\"'";
    private static final String CLOSERS  = ")}]\"'";
    private static final Set<Character> CLOSER_SET = new HashSet<>(Arrays.asList(')', '}', ']', '"', '\''));
    private static final Map<Character, Character> OPENER_TO_CLOSER = new HashMap<>();
    private static final Map<Character, Character> CLOSER_TO_OPENER = new HashMap<>();

    static {
        OPENER_TO_CLOSER.put('(' , ')');
        OPENER_TO_CLOSER.put('{' , '}');
        OPENER_TO_CLOSER.put('[' , ']');
        OPENER_TO_CLOSER.put('"' , '"');
        OPENER_TO_CLOSER.put('\'', '\'');
        for (var e : OPENER_TO_CLOSER.entrySet()) {
            CLOSER_TO_OPENER.put(e.getValue(), e.getKey());
        }
    }

    public static char closerFor(char opener) {
        Character c = OPENER_TO_CLOSER.get(opener);
        return c != null ? c : '\0';
    }

    public static char openerFor(char closer) {
        Character c = CLOSER_TO_OPENER.get(closer);
        return c != null ? c : '\0';
    }

    public static boolean isOpener(char ch) {
        return OPENER_TO_CLOSER.containsKey(ch);
    }

    public static boolean isCloser(char ch) {
        return CLOSER_TO_OPENER.containsKey(ch);
    }

    public static boolean isPair(char open, char close) {
        Character c = OPENER_TO_CLOSER.get(open);
        return c != null && c == close;
    }

    // ── Language detection helpers ────────────────────────────────

    /**
     * Returns true if the character is an identifier char (letter, digit, underscore, $).
     */
    private static boolean isIdentChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
    }

    /**
     * Returns true if language is plain text (no auto-close, no smart indent).
     */
    private static boolean isPlainText(String lang) {
        return lang == null || lang.isEmpty() || "text".equals(lang) || "plaintext".equals(lang);
    }

    private static boolean isJavaOrKotlin(String lang) {
        return "java".equals(lang) || "kotlin".equals(lang);
    }

    private static boolean isXml(String lang) {
        return "xml".equals(lang) || "html".equals(lang);
    }

    // ── Document balance counting ────────────────────────────────

    private static final int BALANCE_SCAN_LIMIT = 50_000;

    /**
     * Counts openers minus closers in the document text (up to BALANCE_SCAN_LIMIT chars).
     */
    private static int docBalance(CharSequence text, char opener, char closer) {
        int count = 0;
        int limit = Math.min(text.length(), BALANCE_SCAN_LIMIT);
        for (int i = 0; i < limit; i++) {
            char ch = text.charAt(i);
            if (ch == opener) count++;
            else if (ch == closer) count--;
        }
        return count;
    }

    // ── smartInsert ──────────────────────────────────────────────

    /**
     * Smart character insertion with skip-over, auto-close, dedent.
     *
     * @param text     full document text
     * @param selStart selection start
     * @param selEnd   selection end
     * @param ch       character being typed
     * @param language language identifier
     * @return RangeEdit describing the edit
     */
    public static RangeEdit smartInsert(CharSequence text, int selStart, int selEnd, char ch, String language) {
        // If there is a selection, replace it
        if (selStart != selEnd) {
            return new RangeEdit(selStart, selEnd, String.valueOf(ch), selStart + 1);
        }

        int pos = selStart;

        // Skip-over: if the char at pos is the same closer, just move past it
        if (pos < text.length() && text.charAt(pos) == ch && isCloser(ch)) {
            return new RangeEdit(pos, pos, "", pos + 1);
        }

        // For plain text, just insert
        if (isPlainText(language)) {
            return new RangeEdit(pos, pos, String.valueOf(ch), pos + 1);
        }

        // Auto-close brackets
        if (isOpener(ch) && ch != '"' && ch != '\'') {
            char closer = closerFor(ch);
            // Balance check
            int balance = docBalance(text, ch, closer);
            if (balance < BALANCE_SCAN_LIMIT) {
                return new RangeEdit(pos, pos, "" + ch + closer, pos + 1);
            }
        }

        // Auto-close quotes (not after identifier char, not in plain text)
        if ((ch == '"' || ch == '\'') && isJavaOrKotlin(language)) {
            boolean afterIdent = pos > 0 && isIdentChar(text.charAt(pos - 1));
            if (!afterIdent) {
                char closer = closerFor(ch);
                return new RangeEdit(pos, pos, "" + ch + closer, pos + 1);
            }
        }

        // Smart Enter: delegate to newline handler
        if (ch == '\n') {
            return smartEnter(text, pos, language);
        }

        // Default: plain insert
        return new RangeEdit(pos, pos, String.valueOf(ch), pos + 1);
    }

    // ── smartBackspace ───────────────────────────────────────────

    /**
     * Smart backspace with empty-pair deletion, blank-line collapse, smart indent.
     */
    public static RangeEdit smartBackspace(CharSequence text, int selStart, int selEnd, String language) {
        // Delete selection first
        if (selStart != selEnd) {
            return new RangeEdit(selStart, selEnd, "", selStart);
        }

        int pos = selStart;
        if (pos <= 0) return new RangeEdit(0, 0, "", 0);

        char before = text.charAt(pos - 1);

        // Empty pair deletion: delete both brackets/quotes
        if (pos < text.length()) {
            char after = text.charAt(pos);
            if (isPair(before, after)) {
                return new RangeEdit(pos - 1, pos + 1, "", pos - 1);
            }
        }

        // Blank-line collapse: if we're at the start of a blank line, delete the preceding newline
        if (before == '\n' && isBlankLineBefore(text, pos)) {
            // Find the start of the previous blank line sequence
            int delStart = findBlankLineCollapseStart(text, pos);
            return new RangeEdit(delStart, pos, "", delStart);
        }

        // Smart closing-bracket indent: align to opener's indent
        if (isCloser(before) && isJavaOrKotlin(language)) {
            int openerPos = findMatchingOpener(text, pos - 1, before);
            if (openerPos >= 0) {
                int openerLineStart = lineStartForOffset(text, openerPos);
                int closerLineStart = lineStartForOffset(text, pos - 1);
                if (openerLineStart != closerLineStart) {
                    String openerIndent = extractIndent(text, openerLineStart);
                    String currentIndent = extractIndent(text, closerLineStart);
                    if (!openerIndent.equals(currentIndent) && currentIndent.length() > openerIndent.length()) {
                        // Replace current line's indent with opener's indent
                        return new RangeEdit(closerLineStart, closerLineStart + currentIndent.length(),
                            openerIndent, closerLineStart + openerIndent.length());
                    }
                }
            }
        }

        // Smart indent backspace: remove whole indent level
        if (before == ' ' || before == '\t') {
            int lineStart = lineStartForOffset(text, pos);
            String indent = extractIndent(text, lineStart);
            if (pos == lineStart + indent.length()) {
                // Cursor is at end of indent
                int indentSize = indent.length();
                int tabSize = detectTabSize(text);
                int removeCount = indentSize > 0 ? ((indentSize - 1) / tabSize + 1) : 0;
                removeCount = Math.min(removeCount, indentSize);
                if (removeCount > 0) {
                    return new RangeEdit(pos - removeCount, pos, "", pos - removeCount);
                }
            }
        }

        // Default: delete one character
        return new RangeEdit(pos - 1, pos, "", pos - 1);
    }

    // ── smartEnter / NewlineHandler ──────────────────────────────

    /**
     * Smart Enter: continue indent, deeper after openers, bracket expansion, etc.
     */
    public static RangeEdit smartEnter(CharSequence text, int pos, String language) {
        if (isXml(language)) {
            return xmlNewline(text, pos, language);
        }
        if ("kotlin".equals(language)) {
            return kotlinNewline(text, pos, language);
        }
        // Java and default
        return javaNewline(text, pos, language);
    }

    /**
     * Java/Kotlin newline handler.
     */
    private static RangeEdit javaNewline(CharSequence text, int pos, String language) {
        boolean isKotlin = "kotlin".equals(language);
        int lineStart = lineStartForOffset(text, pos);
        String currentIndent = extractIndent(text, lineStart);

        // Check if we're inside a string literal — don't do smart newline
        if (!isKotlin && isInString(text, pos)) {
            return plainNewline(text, pos, currentIndent);
        }

        char charBefore = pos > 0 ? text.charAt(pos - 1) : '\0';
        char charAfter = pos < text.length() ? text.charAt(pos) : '\0';

        // Empty pair expansion: {|} -> {<indent>\n<indent>cursor\n<indent>}
        if (isOpener(charBefore) && isCloser(charAfter) && isPair(charBefore, charAfter)) {
            String deeperIndent = currentIndent + "    ";
            String result = "\n" + deeperIndent + "\n" + currentIndent;
            return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
        }

        // Deeper indent after openers: { or ( or [
        if (isOpener(charBefore) && charBefore != '"' && charBefore != '\'') {
            String deeperIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Continue indent: just type newline with same indent
        // Also check for continuation indent (dangling operators)
        if (isDanglingOperator(text, pos - 1)) {
            String continuationIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + continuationIndent, pos + 1 + continuationIndent.length());
        }

        // Doc/block comment continuation: /** ... */ or /* ... */
        if (pos >= 2 && text.charAt(pos - 1) == '\n') {
            // Already on a newline? unlikely
        }
        if (charBefore == '*' && pos >= 2 && text.charAt(pos - 2) == '/' &&
            (charAfter == '*' || charAfter == ' ')) {
            // Doc comment: insert *  with indent
            return new RangeEdit(pos, pos, "\n" + currentIndent + " * ", pos + 1 + currentIndent.length() + 2);
        }
        if (charBefore == '*' && pos >= 2 && text.charAt(pos - 2) == '/' ) {
            return new RangeEdit(pos, pos, "\n" + currentIndent + " * ", pos + 1 + currentIndent.length() + 2);
        }

        // Block comment middle: if previous line starts with *  (after /*)
        if (pos > 0 && text.charAt(pos - 1) == '\n') {
            // Check if in block comment
            // ...
        }

        // Check if in block comment and user hits enter
        if (isInBlockComment(text, pos)) {
            // Look for leading * pattern
            // Insert *  with space
            return new RangeEdit(pos, pos, "\n" + currentIndent + " * ", pos + 1 + currentIndent.length() + 2);
        }

        // List bullet continuation (- or * at line start)
        String trimmedLine = extractLineText(text, lineStart);
        if (trimmedLine.startsWith("- ") || trimmedLine.startsWith("* ")) {
            String bullet = trimmedLine.substring(0, 2);
            if (trimmedLine.length() > 2) {
                return new RangeEdit(pos, pos, "\n" + currentIndent + bullet, pos + 1 + currentIndent.length() + 2);
            } else {
                // Empty list item: remove it
                return new RangeEdit(lineStart, pos, "\n", lineStart + 1);
            }
        }

        // Case label indent: case X: or default:
        if (isCaseLabel(text, pos - 1)) {
            String deeperIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Annotation-only line stays same indent
        if (isAnnotationOnlyLine(text, lineStart)) {
            return new RangeEdit(pos, pos, "\n" + currentIndent, pos + 1 + currentIndent.length());
        }

        // Default: continue with same indent
        return new RangeEdit(pos, pos, "\n" + currentIndent, pos + 1 + currentIndent.length());
    }

    /**
     * Kotlin newline handler (no string splits, no case labels, has arrow indent).
     */
    private static RangeEdit kotlinNewline(CharSequence text, int pos, String language) {
        int lineStart = lineStartForOffset(text, pos);
        String currentIndent = extractIndent(text, lineStart);

        char charBefore = pos > 0 ? text.charAt(pos - 1) : '\0';
        char charAfter = pos < text.length() ? text.charAt(pos) : '\0';

        // Empty pair expansion
        if (isOpener(charBefore) && isCloser(charAfter) && isPair(charBefore, charAfter)) {
            String deeperIndent = currentIndent + "    ";
            String result = "\n" + deeperIndent + "\n" + currentIndent;
            return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
        }

        // Deeper after openers
        if (isOpener(charBefore) && charBefore != '"' && charBefore != '\'') {
            String deeperIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Arrow (->) indent in when/match
        if (endsWithArrow(text, pos - 1)) {
            String deeperIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + deeperIndent, pos + 1 + deeperIndent.length());
        }

        // Dangling operator continuation
        if (isDanglingOperator(text, pos - 1)) {
            String continuationIndent = currentIndent + "    ";
            return new RangeEdit(pos, pos, "\n" + continuationIndent, pos + 1 + continuationIndent.length());
        }

        // Block comment continuation
        if (isInBlockComment(text, pos)) {
            return new RangeEdit(pos, pos, "\n" + currentIndent + " * ", pos + 1 + currentIndent.length() + 2);
        }

        // Empty pair expansion with openers
        if (isOpener(charBefore)) {
            char closer = closerFor(charBefore);
            if (closer != '\0') {
                String deeperIndent = currentIndent + "    ";
                String result = "\n" + deeperIndent + "\n" + currentIndent;
                return new RangeEdit(pos, pos, result, pos + 1 + deeperIndent.length());
            }
        }

        // Default: continue with same indent
        return new RangeEdit(pos, pos, "\n" + currentIndent, pos + 1 + currentIndent.length());
    }

    /**
     * XML newline handler: structural indent from element nesting.
     * <p>v3.3.3: Ported from CodeAssist's {@code XmlNewlineHandler}. Uses a
     * forward scan with a stack of opening-line indents to compute the
     * correct nesting depth — instead of the heuristic "indent deeper
     * after {@code >}" which incorrectly fired after self-closing tags
     * and after closing tags.
     * <p>Three cases:
     * <ol>
     *   <li><b>Inside an unclosed start tag</b> (cursor between {@code <tag}
     *       and {@code >}): align the wrapped attribute under the first
     *       attribute (or keep current indent if already on a wrapped line).</li>
     *   <li><b>Tag-pair expansion</b> ({@code <Foo>|</Foo>}): body on a
     *       deeper line, close tag de-dented under {@code <Foo>}.</li>
     *   <li><b>Default</b>: structural indent — one level deeper than the
     *       innermost still-open element's opening line.</li>
     * </ol>
     */
    private static RangeEdit xmlNewline(CharSequence text, int pos, String language) {
        int lineStart = lineStartForOffset(text, pos);
        String currentIndent = extractIndent(text, lineStart);
        String unit = detectIndentUnit(text);

        // Case 1: Inside an unclosed start tag → align wrapped attribute.
        int tagOpen = enclosingStartTag(text, pos);
        if (tagOpen >= 0) {
            String pad;
            if (lineStartForOffset(text, tagOpen) == lineStart) {
                // Tag opener is on this line → align under the first attribute.
                pad = " ".repeat(attributeAlignColumn(text, tagOpen, unit.length()));
            } else {
                // Tag opener on an earlier line → this line is already a
                // wrapped attribute; keep its indent.
                pad = currentIndent;
            }
            return new RangeEdit(pos, pos, "\n" + pad, pos + 1 + pad.length());
        }

        // Compute the structural indent for a new line at pos.
        String base = xmlIndentAt(text, pos, unit);

        // Case 2: Tag-pair expansion — <Foo …>|</Foo>
        int gt = prevNonBlankOnLine(text, pos);
        int closeLt = nextNonBlankOnLine(text, pos);
        if (gt >= 0 && text.charAt(gt) == '>'
            && (gt == 0 || text.charAt(gt - 1) != '/')
            && closeLt >= 0 && text.charAt(closeLt) == '<'
            && closeLt + 1 < text.length() && text.charAt(closeLt + 1) == '/') {
            String mid = "\n" + base;
            int start = pos;
            while (start > lineStart && (text.charAt(start - 1) == ' ' || text.charAt(start - 1) == '\t')) {
                start--;
            }
            String closeIndent = dropIndentLevel(base, unit);
            return new RangeEdit(start, closeLt, mid + "\n" + closeIndent,
                start + mid.length());
        }

        // Case 3: Default — structural indent.
        return new RangeEdit(pos, pos, "\n" + base, pos + 1 + base.length());
    }

    // ── XML newline helpers (ported from CodeAssist Newline.kt) ─────

    private static final int XML_INDENT_SCAN_LIMIT = 200_000;

    /**
     * Returns the offset of the innermost unclosed start tag's opening
     * {@code <} at or before {@code pos}, or -1 if the caret is not inside
     * a start tag. A start tag is "unclosed" if no {@code >} has been seen
     * since its {@code <} (i.e., we're inside its attribute list).
     * <p>Quote-aware: a {@code >} inside an attribute value doesn't close
     * the tag.
     */
    private static int enclosingStartTag(CharSequence text, int pos) {
        int i = pos - 1;
        boolean inQuote = false;
        char quote = ' ';
        while (i >= 0) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == quote) inQuote = false;
                i--;
                continue;
            }
            if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
                i--;
                continue;
            }
            if (c == '>') return -1; // previous tag closed
            if (c == '<') {
                // Found a `<`. Is it a start tag?
                if (i + 1 < text.length()) {
                    char after = text.charAt(i + 1);
                    if (after == '/' || after == '!' || after == '?') return -1;
                }
                return i;
            }
            i--;
        }
        return -1;
    }

    /**
     * The column (0-indexed) at which the first attribute of the tag
     * starting at {@code tagOpen} begins, for attribute-alignment on
     * wrap. Falls back to {@code tagOpenCol + unitLen + 1} (just past
     * the tag name) when the tag has no attributes yet.
     */
    private static int attributeAlignColumn(CharSequence text, int tagOpen, int unitLen) {
        int i = tagOpen + 1;
        // Skip the tag name.
        while (i < text.length() && isXmlNameChar(text.charAt(i))) i++;
        // Skip whitespace between name and first attribute.
        int wsStart = i;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i > wsStart && i < text.length() && text.charAt(i) != '>' && text.charAt(i) != '/') {
            // There's a first attribute — align under it.
            int tagLineStart = lineStartForOffset(text, tagOpen);
            return i - tagLineStart;
        }
        // No attributes yet — align one indent unit past the tag name's column.
        int tagLineStart = lineStartForOffset(text, tagOpen);
        int tagCol = tagOpen - tagLineStart;
        return tagCol + 1 + (i - (tagOpen + 1)) + unitLen;
    }

    /**
     * The structural indent for a new line at {@code pos}: one {@code unit}
     * deeper than the innermost still-open element's opening line, or ""
     * at the root. Forward scan maintains a stack of opening-line indents
     * (open tags push, close tags pop, self-closing don't), skipping
     * comments / CDATA / PIs.
     */
    private static String xmlIndentAt(CharSequence text, int pos, String unit) {
        if (pos > XML_INDENT_SCAN_LIMIT) {
            return extractIndent(text, lineStartForOffset(text, pos));
        }
        List<String> stack = new ArrayList<>();
        int i = 0;
        while (i < pos) {
            if (text.charAt(i) != '<') { i++; continue; }
            if (startsWith(text, "<!--", i)) {
                i = indexAfter(text, "-->", i + 4, pos);
            } else if (startsWith(text, "<![CDATA[", i)) {
                i = indexAfter(text, "]]>", i + 9, pos);
            } else if (i + 1 < text.length() && text.charAt(i + 1) == '?') {
                i = indexAfter(text, "?>", i + 2, pos);
            } else if (i + 1 < text.length() && text.charAt(i + 1) == '!') {
                i = indexAfter(text, ">", i + 2, pos);
            } else if (i + 1 < text.length() && text.charAt(i + 1) == '/') {
                // Close tag → pop.
                if (!stack.isEmpty()) stack.remove(stack.size() - 1);
                i = indexAfter(text, ">", i + 2, pos);
            } else if (i + 1 < text.length()
                && (Character.isLetter(text.charAt(i + 1)) || text.charAt(i + 1) == '_')) {
                // Open or self-closing tag.
                int gt = findTagEnd(text, i + 1, pos);
                if (gt < 0) {
                    // Tag unterminated before the caret — we're inside it.
                    i = pos;
                } else {
                    if (gt == 0 || text.charAt(gt - 1) != '/') {
                        // Not self-closing → push its opening-line indent.
                        stack.add(extractIndent(text, lineStartForOffset(text, i)));
                    }
                    i = gt + 1;
                }
            } else {
                i++;
            }
        }
        if (stack.isEmpty()) return "";
        return stack.get(stack.size() - 1) + unit;
    }

    /** Index of the {@code >} ending a tag whose name starts at {@code from}, honoring quoted values, or -1. */
    private static int findTagEnd(CharSequence text, int from, int limit) {
        int i = from;
        boolean inQuote = false;
        char quote = ' ';
        while (i < limit) {
            char c = text.charAt(i);
            if (inQuote) {
                if (c == quote) inQuote = false;
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quote = c;
            } else if (c == '>') {
                return i;
            } else if (c == '<') {
                return -1; // a new tag opens before this one closed → unterminated
            }
            i++;
        }
        return -1;
    }

    /** Offset just past needle's first occurrence in [from, limit), or limit when not found. */
    private static int indexAfter(CharSequence text, String needle, int from, int limit) {
        int idx = indexOf(text, needle, from);
        if (idx >= 0 && idx < limit) return idx + needle.length();
        return limit;
    }

    /** Index of previous non-blank char before pos on the same line, or -1. */
    private static int prevNonBlankOnLine(CharSequence text, int pos) {
        int i = pos - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i--;
        if (i >= 0 && text.charAt(i) != '\n') return i;
        return -1;
    }

    /** Index of next non-blank char at or after pos on the same line, or -1. */
    private static int nextNonBlankOnLine(CharSequence text, int pos) {
        int i = pos;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i++;
        if (i < text.length() && text.charAt(i) != '\n') return i;
        return -1;
    }

    /** Drops one indent unit from the given indent string (for de-denting close tags). */
    private static String dropIndentLevel(String indent, String unit) {
        if (indent.endsWith(unit)) return indent.substring(0, indent.length() - unit.length());
        // Fallback: drop trailing whitespace matching unit length.
        int drop = Math.min(indent.length(), unit.length());
        return indent.substring(0, indent.length() - drop);
    }

    private static boolean isXmlNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == ':' || c == '.' || c == '-';
    }

    private static boolean startsWith(CharSequence text, String prefix, int offset) {
        if (offset + prefix.length() > text.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (text.charAt(offset + i) != prefix.charAt(i)) return false;
        }
        return true;
    }

    /**
     * Plain newline with just indent continuation.
     */
    private static RangeEdit plainNewline(CharSequence text, int pos, String indent) {
        return new RangeEdit(pos, pos, "\n" + indent, pos + 1 + indent.length());
    }

    // ── smartEnter (Complete Statement) ──────────────────────────

    /**
     * Complete Statement: finish line with ; if needed, add { } block for control-flow.
     */
    public static RangeEdit smartEnterComplete(CharSequence text, int pos, String language) {
        if (!isJavaOrKotlin(language)) {
            return new RangeEdit(pos, pos, "", pos);
        }

        int lineStart = lineStartForOffset(text, pos);
        String lineText = extractLineText(text, lineStart);
        String trimmed = lineText.trim();
        String currentIndent = extractIndent(text, lineStart);

        // Control flow headers that need { } block
        String[] controlFlowKeywords = {"if", "else", "for", "while", "do", "try", "catch", "finally"};
        for (String kw : controlFlowKeywords) {
            if (trimmed.startsWith(kw) && (trimmed.endsWith("{") || trimmed.endsWith(")"))) {
                // Already has block or parens - add { }
                if (!trimmed.endsWith("{")) {
                    return new RangeEdit(pos, pos, " {\n" + currentIndent + "    \n" + currentIndent + "}",
                        pos + currentIndent.length() + 5);
                }
            }
        }

        // If line doesn't end with ; } or {, add ;
        char lastChar = trimmed.isEmpty() ? '\0' : trimmed.charAt(trimmed.length() - 1);
        if (lastChar != ';' && lastChar != '{' && lastChar != '}' && lastChar != ',') {
            return new RangeEdit(pos, pos, ";", pos + 1);
        }

        return new RangeEdit(pos, pos, "", pos);
    }

    // ── Word boundaries ──────────────────────────────────────────

    /**
     * Find word boundary to the left of pos.
     */
    public static int wordBoundaryLeft(CharSequence text, int pos) {
        if (pos <= 0) return 0;
        int i = pos - 1;
        // Skip whitespace
        while (i > 0 && Character.isWhitespace(text.charAt(i))) i--;
        if (i <= 0) return 0;
        // If on a word char, skip the word
        if (isIdentChar(text.charAt(i))) {
            while (i > 0 && isIdentChar(text.charAt(i - 1))) i--;
        } else {
            // Skip non-word non-whitespace (punctuation run)
            while (i > 0 && !Character.isWhitespace(text.charAt(i - 1)) && !isIdentChar(text.charAt(i - 1))) i--;
        }
        return i;
    }

    /**
     * Find word boundary to the right of pos.
     */
    public static int wordBoundaryRight(CharSequence text, int pos) {
        int len = text.length();
        if (pos >= len) return len;
        int i = pos;
        // Skip whitespace
        while (i < len && Character.isWhitespace(text.charAt(i))) i++;
        if (i >= len) return len;
        // If on a word char, skip the word
        if (isIdentChar(text.charAt(i))) {
            while (i < len && isIdentChar(text.charAt(i))) i++;
        } else {
            // Skip punctuation run
            while (i < len && !Character.isWhitespace(text.charAt(i)) && !isIdentChar(text.charAt(i))) i++;
        }
        return i;
    }

    /**
     * Find the word range at the given position.
     */
    public static int[] wordRangeAt(CharSequence text, int pos) {
        int len = text.length();
        if (len == 0) return new int[]{0, 0};
        int p = Math.min(Math.max(0, pos), len - 1);

        if (!isIdentChar(text.charAt(p))) {
            // If on whitespace, find the nearest word
            if (Character.isWhitespace(text.charAt(p))) {
                int left = wordBoundaryLeft(text, p);
                int right = wordBoundaryRight(text, p);
                return new int[]{left, right};
            }
            // Punctuation: just return that char
            return new int[]{p, p + 1};
        }

        int start = p;
        while (start > 0 && isIdentChar(text.charAt(start - 1))) start--;
        int end = p + 1;
        while (end < len && isIdentChar(text.charAt(end))) end++;
        return new int[]{start, end};
    }

    // ── Indent unit detection ────────────────────────────────────

    /**
     * Detects the indent unit: "\t", "  ", "    ", or "        " (tab vs 2/4/8 spaces).
     */
    public static String detectIndentUnit(CharSequence text) {
        int tabCount = 0;
        int sp2 = 0, sp4 = 0, sp8 = 0;
        int limit = Math.min(text.length(), 10_000);
        int lineStart = 0;

        for (int i = 0; i <= limit; i++) {
            if (i == limit || text.charAt(i) == '\n') {
                // Analyze line
                if (lineStart < i) {
                    char first = text.charAt(lineStart);
                    if (first == '\t') {
                        tabCount++;
                    } else if (first == ' ') {
                        int spaces = 0;
                        for (int j = lineStart; j < i && text.charAt(j) == ' '; j++) spaces++;
                        if (spaces >= 8) sp8++;
                        else if (spaces >= 4) sp4++;
                        else if (spaces >= 2) sp2++;
                    }
                }
                lineStart = i + 1;
            }
        }

        if (tabCount > sp4 && tabCount > sp2) return "\t";
        if (sp4 >= sp2 && sp4 >= sp8) return "    ";
        if (sp2 > sp4) return "  ";
        return "    "; // default
    }

    /**
     * Returns the detected tab size (number of spaces) for backspace purposes.
     */
    public static int detectTabSize(CharSequence text) {
        String unit = detectIndentUnit(text);
        return unit.length();
    }

    // ── deleteForward (pair-aware) ───────────────────────────────

    /**
     * Smart forward delete: pair-aware deletion.
     */
    public static RangeEdit smartDeleteForward(CharSequence text, int selStart, int selEnd, String language) {
        if (selStart != selEnd) {
            return new RangeEdit(selStart, selEnd, "", selStart);
        }
        int pos = selStart;
        if (pos >= text.length()) return new RangeEdit(pos, pos, "", pos);

        // Pair-aware: if cursor is between () {} [] "", delete both
        if (pos + 1 < text.length()) {
            char at = text.charAt(pos);
            char after = text.charAt(pos + 1);
            if (isPair(at, after)) {
                return new RangeEdit(pos, pos + 2, "", pos);
            }
        }

        return new RangeEdit(pos, pos + 1, "", pos);
    }

    // ── Line comment toggling ────────────────────────────────────

    /**
     * Toggle line comment for a range of text.
     */
    public static RangeEdit toggleLineComment(CharSequence text, int selStart, int selEnd, String commentPrefix) {
        if (commentPrefix == null) commentPrefix = "//";

        int startLine = lineStartForOffset(text, selStart);
        int endLine = lineStartForOffset(text, selEnd);
        if (endLine < selEnd) {
            // Include the line at selEnd
            int nextNewline = indexOf(text, '\n', selEnd);
            endLine = nextNewline >= 0 ? selEnd : lineStartForOffset(text, selEnd);
        }

        // Find actual end line
        int endLineOffset = selEnd;
        int nl = indexOf(text, '\n', endLineOffset);
        // endLine is the line start of the line containing selEnd
        endLine = lineStartForOffset(text, Math.min(selEnd, text.length() - 1));

        // Check if all lines are commented
        boolean allCommented = true;
        int scanPos = startLine;
        while (scanPos <= Math.min(endLine, text.length() - 1)) {
            int lineEnd = indexOf(text, '\n', scanPos);
            if (lineEnd < 0) lineEnd = text.length();
            String line = text.subSequence(scanPos, lineEnd).toString();
            String trimmed = line.trim();
            if (!trimmed.startsWith(commentPrefix)) {
                allCommented = false;
                break;
            }
            scanPos = lineEnd + 1;
            if (scanPos > text.length()) break;
        }

        // Build replacement
        StringBuilder sb = new StringBuilder();
        int scanPos2 = startLine;
        boolean first = true;
        while (scanPos2 <= endLine) {
            int lineEnd = indexOf(text, '\n', scanPos2);
            if (lineEnd < 0) lineEnd = text.length();
            String line = text.subSequence(scanPos2, lineEnd).toString();
            if (!first) sb.append('\n');
            first = false;

            if (allCommented) {
                // Remove comment prefix
                int idx = line.indexOf(commentPrefix);
                if (idx >= 0) {
                    int afterPrefix = idx + commentPrefix.length();
                    if (afterPrefix < line.length() && line.charAt(afterPrefix) == ' ') {
                        sb.append(line, 0, idx).append(line.substring(afterPrefix + 1));
                    } else {
                        sb.append(line, 0, idx).append(line.substring(afterPrefix));
                    }
                } else {
                    sb.append(line);
                }
            } else {
                sb.append(commentPrefix).append(" ").append(line);
            }

            if (lineEnd >= text.length()) break;
            scanPos2 = lineEnd + 1;
        }

        int endOffset = Math.min(endLine + extractLineText(text, endLine).length(), text.length());
        return new RangeEdit(startLine, endOffset, sb.toString(), startLine + sb.toString().length());
    }

    // ── Helper methods ───────────────────────────────────────────

    private static boolean isBlankLineBefore(CharSequence text, int pos) {
        // Check if the line before pos (ending at pos-1 with \n) is blank
        int lineStart = lineStartForOffset(text, pos - 1);
        for (int i = lineStart; i < pos - 1; i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

    private static int findBlankLineCollapseStart(CharSequence text, int pos) {
        // Walk back through consecutive blank lines
        int p = pos - 1; // skip the \n
        while (p > 0) {
            int ls = lineStartForOffset(text, p);
            boolean blank = true;
            for (int i = ls; i <= p; i++) {
                char ch = text.charAt(i);
                if (ch != '\n' && !Character.isWhitespace(ch)) {
                    blank = false;
                    break;
                }
            }
            if (!blank) break;
            p = ls - 1; // move to end of previous line
        }
        return Math.max(0, p + 1);
    }

    private static int findMatchingOpener(CharSequence text, int closerPos, char closer) {
        char opener = openerFor(closer);
        if (opener == '\0') return -1;
        int depth = 0;
        for (int i = closerPos; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == closer) depth++;
            else if (ch == opener) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    static int lineStartForOffset(CharSequence text, int offset) {
        if (offset <= 0) return 0;
        offset = Math.min(offset, text.length());
        for (int i = offset - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') return i + 1;
        }
        return 0;
    }

    static String extractIndent(CharSequence text, int lineStart) {
        StringBuilder sb = new StringBuilder();
        for (int i = lineStart; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ' || ch == '\t') {
                sb.append(ch);
            } else {
                break;
            }
        }
        return sb.toString();
    }

    static String extractLineText(CharSequence text, int lineStart) {
        int end = indexOf(text, '\n', lineStart);
        if (end < 0) end = text.length();
        return text.subSequence(lineStart, end).toString();
    }

    private static int indexOf(CharSequence text, char ch, int from) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == ch) return i;
        }
        return -1;
    }

    private static boolean isDanglingOperator(CharSequence text, int pos) {
        if (pos < 0 || pos >= text.length()) return false;
        char ch = text.charAt(pos);
        return ch == '+' || ch == '-' || ch == '*' || ch == '/'
            || ch == '&' || ch == '|' || ch == '^'
            || ch == '=' || ch == '<' || ch == '>'
            || (ch == '&' && pos + 1 < text.length() && text.charAt(pos + 1) == '&')
            || (ch == '|' && pos + 1 < text.length() && text.charAt(pos + 1) == '|')
            || ch == ',';
    }

    private static boolean endsWithArrow(CharSequence text, int pos) {
        // Check if text ends with "->" at pos
        return pos >= 1 && text.charAt(pos) == '>' && text.charAt(pos - 1) == '-';
    }

    private static boolean isInBlockComment(CharSequence text, int pos) {
        // Scan backward for /* without intervening */
        boolean foundOpen = false;
        for (int i = 0; i < pos - 1; i++) {
            if (text.charAt(i) == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                foundOpen = true;
            } else if (text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                foundOpen = false;
            }
        }
        return foundOpen;
    }

    private static boolean isInString(CharSequence text, int pos) {
        boolean inString = false;
        char quoteChar = '\0';
        for (int i = 0; i < pos; i++) {
            char ch = text.charAt(i);
            if (!inString) {
                // Line comment: skip to end of line, never inside a string there.
                if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                    // advance to end of line
                    while (i < pos && text.charAt(i) != '\n') i++;
                    // loop's i++ will move past the newline (or pos)
                    continue;
                }
                // Block comment open: skip to matching */
                if (ch == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                    int close = indexOf(text, "*/", i + 2);
                    if (close < 0) return false; // unterminated block comment → not in string
                    i = close + 1; // skip past "*/"; loop's i++ moves one more
                    continue;
                }
                if (ch == '"' || ch == '\'') {
                    inString = true;
                    quoteChar = ch;
                }
            } else {
                if (ch == '\\') {
                    i++; // skip escaped char
                } else if (ch == quoteChar) {
                    inString = false;
                }
            }
        }
        return inString;
    }

    private static int indexOf(CharSequence text, String search, int from) {
        int slen = search.length();
        int max = text.length() - slen;
        for (int i = from; i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < slen; j++) {
                if (text.charAt(i + j) != search.charAt(j)) { match = false; break; }
            }
            if (match) return i;
        }
        return -1;
    }

    private static boolean isCaseLabel(CharSequence text, int pos) {
        // Look backward from pos for "case " or "default"
        int lineStart = lineStartForOffset(text, pos);
        String line = text.subSequence(lineStart, pos + 1).toString().trim();
        return line.matches("case\\b.*:") || line.equals("default:");
    }

    private static boolean isAnnotationOnlyLine(CharSequence text, int lineStart) {
        String line = extractLineText(text, lineStart).trim();
        return line.startsWith("@") && !line.contains("(") && !line.contains(" ");
    }
}
