package jo.codeeditor.blocks;

import java.util.*;

/**
 * Block editing: parse Java into a tree of typed blocks, render blocks
 * with shapes, and provide completion inside block slots.
 * This is a simplified structural editor for block-based code editing.
 * Ported from CodeAssist BlockEditor.kt.
 
 *
 * @since v1.0.7
*/
public class BlockEditor {

    // ── BlockType ─────────────────────────────────────────────────

    /**
     * Type of a block in the tree.
     */
    public enum BlockType {
        EXPRESSION,
        STATEMENT,
        BLOCK,
        VALUE
    }

    // ── BlockNode ─────────────────────────────────────────────────

    /**
     * A node in the block tree.
     */
    public static final class BlockNode {
        public final BlockType type;
        public final String text;
        public final int startOffset;
        public final int endOffset;
        public final List<BlockNode> children;
        public final String label;

        public BlockNode(BlockType type, String text, int startOffset, int endOffset,
                         List<BlockNode> children, String label) {
            this.type = type;
            this.text = text != null ? text : "";
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.children = children != null ? Collections.unmodifiableList(children) : Collections.emptyList();
            this.label = label != null ? label : "";
        }

        /** Returns true if this node has children. */
        public boolean hasChildren() {
            return !children.isEmpty();
        }

        /** Returns the depth of the tree rooted at this node. */
        public int depth() {
            int max = 0;
            for (BlockNode child : children) {
                max = Math.max(max, child.depth());
            }
            return max + 1;
        }

        /** Returns the total number of nodes in the tree. */
        public int nodeCount() {
            int count = 1;
            for (BlockNode child : children) {
                count += child.nodeCount();
            }
            return count;
        }

        /** Find the deepest node containing the given offset. */
        public BlockNode nodeAt(int offset) {
            if (offset < startOffset || offset >= endOffset) return null;
            for (BlockNode child : children) {
                BlockNode found = child.nodeAt(offset);
                if (found != null) return found;
            }
            return this;
        }

        @Override
        public String toString() {
            return "BlockNode(" + type + ", \"" + label + "\", [" + startOffset + "," + endOffset + "])";
        }
    }

    // ── BlockParser ───────────────────────────────────────────────

    /**
     * Parser that converts Java source text into a block tree.
     * Simplified: identifies top-level declarations, statements, and expressions.
     */
    public static final class BlockParser {

        /**
         * Parse Java source into a block tree.
         *
         * @param source Java source code
         * @return root block node
         */
        public static BlockNode parse(String source) {
            if (source == null || source.isEmpty()) {
                return new BlockNode(BlockType.BLOCK, "", 0, 0, Collections.emptyList(), "empty");
            }

            List<BlockNode> children = new ArrayList<>();
            int pos = 0;

            while (pos < source.length()) {
                // Skip whitespace
                while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
                if (pos >= source.length()) break;

                BlockNode stmt = parseStatement(source, pos);
                if (stmt != null) {
                    children.add(stmt);
                    pos = stmt.endOffset;
                } else {
                    // Skip unrecognized character
                    pos++;
                }
            }

            return new BlockNode(BlockType.BLOCK, source, 0, source.length(), children, "file");
        }

        /**
         * Parse a single statement starting at pos.
         */
        private static BlockNode parseStatement(String source, int pos) {
            if (pos >= source.length()) return null;

            // Skip whitespace
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
            if (pos >= source.length()) return null;

            int start = pos;

            // Find the end of this statement (semicolon, block close, or EOF)
            int depth = 0;
            boolean inString = false;
            char stringChar = 0;

            while (pos < source.length()) {
                char ch = source.charAt(pos);

                if (inString) {
                    if (ch == '\\') {
                        pos++; // skip escape
                    } else if (ch == stringChar) {
                        inString = false;
                    }
                } else {
                    if (ch == '"' || ch == '\'') {
                        inString = true;
                        stringChar = ch;
                    } else if (ch == '(' || ch == '{' || ch == '[') {
                        depth++;
                    } else if (ch == ')' || ch == '}' || ch == ']') {
                        depth--;
                    } else if (ch == ';' && depth == 0) {
                        pos++;
                        String text = source.substring(start, pos);
                        BlockType type = classifyStatement(text);
                        String label = extractLabel(text, type);
                        return new BlockNode(type, text, start, pos, Collections.emptyList(), label);
                    } else if (ch == '{' && depth == 1) {
                        // Block statement: find matching close brace
                        int blockEnd = findMatchingBrace(source, pos);
                        if (blockEnd >= 0) {
                            pos = blockEnd + 1;
                            String text = source.substring(start, pos);
                            List<BlockNode> blockChildren = parseBlockBody(source, pos - 1);
                            BlockType type = classifyStatement(text);
                            String label = extractLabel(text, type);
                            return new BlockNode(type, text, start, pos, blockChildren, label);
                        }
                    }
                }
                pos++;
            }

            // Statement without terminator
            if (pos > start) {
                String text = source.substring(start, pos);
                BlockType type = classifyStatement(text);
                String label = extractLabel(text, type);
                return new BlockNode(type, text, start, pos, Collections.emptyList(), label);
            }

            return null;
        }

        /**
         * Parse the body of a block (between { and }).
         */
        private static List<BlockNode> parseBlockBody(String source, int closeBracePos) {
            // Find the open brace matching closeBracePos
            int openBrace = -1;
            int depth = 0;
            for (int i = closeBracePos; i >= 0; i--) {
                char ch = source.charAt(i);
                if (ch == '}') depth++;
                else if (ch == '{') {
                    depth--;
                    if (depth == 0) { openBrace = i; break; }
                }
            }
            if (openBrace < 0) return Collections.emptyList();

            List<BlockNode> children = new ArrayList<>();
            int pos = openBrace + 1;
            while (pos < closeBracePos) {
                while (pos < closeBracePos && Character.isWhitespace(source.charAt(pos))) pos++;
                if (pos >= closeBracePos) break;

                BlockNode stmt = parseStatement(source, pos);
                if (stmt != null && stmt.endOffset <= closeBracePos) {
                    children.add(stmt);
                    pos = stmt.endOffset;
                } else {
                    pos++;
                }
            }
            return children;
        }

        /**
         * Classify a statement by its text.
         */
        private static BlockType classifyStatement(String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("if ") || trimmed.startsWith("for ") || trimmed.startsWith("while ")
                || trimmed.startsWith("switch ") || trimmed.startsWith("try ") || trimmed.startsWith("catch ")
                || trimmed.startsWith("else ") || trimmed.startsWith("do ") || trimmed.startsWith("finally ")) {
                return BlockType.STATEMENT;
            }
            if (trimmed.contains("{") && trimmed.contains("}")) {
                return BlockType.BLOCK;
            }
            if (trimmed.startsWith("return ") || trimmed.startsWith("int ") || trimmed.startsWith("String ")
                || trimmed.startsWith("var ") || trimmed.startsWith("final ") || trimmed.startsWith("const ")) {
                return BlockType.EXPRESSION;
            }
            return BlockType.STATEMENT;
        }

        /**
         * Extract a short label for a block.
         */
        private static String extractLabel(String text, BlockType type) {
            String trimmed = text.trim();
            // First word or keyword
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx > 0) {
                return trimmed.substring(0, spaceIdx);
            }
            if (trimmed.length() > 20) {
                return trimmed.substring(0, 20) + "...";
            }
            return trimmed;
        }

        /**
         * Find the matching closing brace for an opening brace at pos.
         */
        private static int findMatchingBrace(String source, int pos) {
            if (pos >= source.length() || source.charAt(pos) != '{') return -1;
            int depth = 0;
            boolean inString = false;
            char stringChar = 0;
            for (int i = pos; i < source.length(); i++) {
                char ch = source.charAt(i);
                if (inString) {
                    if (ch == '\\') { i++; }
                    else if (ch == stringChar) { inString = false; }
                } else {
                    if (ch == '"' || ch == '\'') { inString = true; stringChar = ch; }
                    else if (ch == '{') depth++;
                    else if (ch == '}') {
                        depth--;
                        if (depth == 0) return i;
                    }
                }
            }
            return -1;
        }
    }

    // ── BlockRenderer ─────────────────────────────────────────────

    /**
     * Renders blocks as structured data for display.
     * Each block gets a shape type, color, and indentation level.
     */
    public static final class BlockRenderer {

        /** Rendered block data. */
        public static final class RenderedBlock {
            public final int indentLevel;
            public final BlockType type;
            public final String label;
            public final String shape; // "rect", "rounded", "diamond", "hexagon"
            public final int color;    // ARGB color
            public final int startOffset;
            public final int endOffset;

            public RenderedBlock(int indentLevel, BlockType type, String label, String shape,
                                int color, int startOffset, int endOffset) {
                this.indentLevel = indentLevel;
                this.type = type;
                this.label = label;
                this.shape = shape;
                this.color = color;
                this.startOffset = startOffset;
                this.endOffset = endOffset;
            }

            @Override
            public String toString() {
                return "RenderedBlock(" + type + ", \"" + label + "\", indent=" + indentLevel + ")";
            }
        }

        /** Default colors for block types. */
        private static final int COLOR_EXPRESSION = 0xFF4FC3F7; // light blue
        private static final int COLOR_STATEMENT = 0xFF81C784;  // light green
        private static final int COLOR_BLOCK = 0xFFFFB74D;      // light orange
        private static final int COLOR_VALUE = 0xFFBA68C8;      // light purple

        /**
         * Render a block tree into a flat list of rendered blocks.
         */
        public static List<RenderedBlock> render(BlockNode root) {
            List<RenderedBlock> result = new ArrayList<>();
            renderNode(root, 0, result);
            return result;
        }

        private static void renderNode(BlockNode node, int indent, List<RenderedBlock> out) {
            String shape = shapeForType(node.type);
            int color = colorForType(node.type);

            out.add(new RenderedBlock(indent, node.type, node.label, shape, color,
                node.startOffset, node.endOffset));

            for (BlockNode child : node.children) {
                renderNode(child, indent + 1, out);
            }
        }

        private static String shapeForType(BlockType type) {
            switch (type) {
                case EXPRESSION: return "rounded";
                case STATEMENT: return "rect";
                case BLOCK: return "hexagon";
                case VALUE: return "diamond";
                default: return "rect";
            }
        }

        private static int colorForType(BlockType type) {
            switch (type) {
                case EXPRESSION: return COLOR_EXPRESSION;
                case STATEMENT: return COLOR_STATEMENT;
                case BLOCK: return COLOR_BLOCK;
                case VALUE: return COLOR_VALUE;
                default: return COLOR_STATEMENT;
            }
        }
    }

    // ── SlotCompletion ────────────────────────────────────────────

    /**
     * Completion inside block slots: suggests valid completions
     * based on the slot type (expression, statement, etc.).
     */
    public static final class SlotCompletion {

        /** A slot completion suggestion. */
        public static final class Suggestion {
            public final String text;
            public final String description;
            public final BlockType slotType;

            public Suggestion(String text, String description, BlockType slotType) {
                this.text = text != null ? text : "";
                this.description = description != null ? description : "";
                this.slotType = slotType;
            }

            @Override
            public String toString() {
                return "Suggestion(\"" + text + "\", " + slotType + ")";
            }
        }

        /**
         * Get suggestions for a slot of the given type.
         */
        public static List<Suggestion> suggestionsFor(BlockType slotType, String prefix) {
            List<Suggestion> result = new ArrayList<>();
            if (slotType == null) return result;

            switch (slotType) {
                case EXPRESSION:
                    addIfMatches(result, "true", "Boolean literal", prefix, BlockType.EXPRESSION);
                    addIfMatches(result, "false", "Boolean literal", prefix, BlockType.EXPRESSION);
                    addIfMatches(result, "null", "Null literal", prefix, BlockType.EXPRESSION);
                    addIfMatches(result, "this", "Current instance", prefix, BlockType.EXPRESSION);
                    addIfMatches(result, "new ", "Constructor call", prefix, BlockType.EXPRESSION);
                    addIfMatches(result, "return ", "Return statement", prefix, BlockType.EXPRESSION);
                    break;
                case STATEMENT:
                    addIfMatches(result, "if ()", "If statement", prefix, BlockType.STATEMENT);
                    addIfMatches(result, "for ()", "For loop", prefix, BlockType.STATEMENT);
                    addIfMatches(result, "while ()", "While loop", prefix, BlockType.STATEMENT);
                    addIfMatches(result, "switch ()", "Switch statement", prefix, BlockType.STATEMENT);
                    addIfMatches(result, "try {}", "Try block", prefix, BlockType.STATEMENT);
                    addIfMatches(result, "return;", "Return", prefix, BlockType.STATEMENT);
                    break;
                case VALUE:
                    addIfMatches(result, "0", "Integer zero", prefix, BlockType.VALUE);
                    addIfMatches(result, "\"\"", "Empty string", prefix, BlockType.VALUE);
                    addIfMatches(result, "true", "Boolean true", prefix, BlockType.VALUE);
                    addIfMatches(result, "false", "Boolean false", prefix, BlockType.VALUE);
                    addIfMatches(result, "null", "Null value", prefix, BlockType.VALUE);
                    break;
                case BLOCK:
                    addIfMatches(result, "{ }", "Empty block", prefix, BlockType.BLOCK);
                    break;
            }

            return result;
        }

        private static void addIfMatches(List<Suggestion> result, String text, String desc,
                                          String prefix, BlockType slotType) {
            if (prefix == null || prefix.isEmpty()
                || text.toLowerCase(java.util.Locale.ROOT).startsWith(prefix.toLowerCase(java.util.Locale.ROOT))) {
                result.add(new Suggestion(text, desc, slotType));
            }
        }
    }

    // ── Rendering (G7 — v1.0.9) ──────────────────────────────────

    /**
     * Renders the block tree as nested colored rectangles (Scratch-like).
     * Each {@link BlockNode} is a rounded rectangle whose color depends on
     * its {@link BlockType}. Children are drawn indented inside their parent.
     *
     * <p>This is a basic rendering — full Scratch-style block shapes (puzzle
     * tabs, slots, drop shadows) can be layered on top in a future version.
     * The goal here is a functional visual that lets the user see the block
     * structure and tap a block to select it.
     *
     * @param canvas   the Canvas to draw on
     * @param metrics  the editor metrics (for char width, line height)
     * @param theme    the editor theme (for background/text colors)
     * @param scrollY  the vertical scroll offset (px)
     * @param viewH    the viewport height (px)
     * @param viewW    the viewport width (px)
     * @since v1.0.9 (G7)
     */
    public void draw(android.graphics.Canvas canvas, Object metrics, Object theme,
                     float scrollY, float viewH, float viewW) {
        if (root == null) return;
        // We use reflection-free casts via the EditorMetrics/EditorTheme
        // classes — but BlockEditor is in a different package and shouldn't
        // depend on the view layer. So we accept Object and cast here to
        // avoid a circular dependency. The caller (EditorView) always passes
        // the right types.
        jo.codeeditor.view.EditorMetrics m = (jo.codeeditor.view.EditorMetrics) metrics;
        jo.codeeditor.view.EditorTheme t = (jo.codeeditor.view.EditorTheme) theme;
        android.graphics.Paint bgPaint = new android.graphics.Paint();
        android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(m.getTypeface());
        textPaint.setTextSize(m.getTextSize() * 0.9f);
        float padX = m.getCharWidth() * 0.5f;
        float padY = m.getLineHeight() * 0.15f;
        float rowH = m.getLineHeight() * 1.1f;
        float indent = m.getCharWidth() * 2;
        // Walk the tree depth-first, drawing each node as a row.
        drawNode(canvas, root, m, t, bgPaint, textPaint,
            m.getPadLeft(), m.getPadTop() - scrollY, rowH, indent, padX, padY, viewW, 0);
    }

    private void drawNode(android.graphics.Canvas canvas, BlockNode node,
                          jo.codeeditor.view.EditorMetrics m, jo.codeeditor.view.EditorTheme t,
                          android.graphics.Paint bgPaint, android.graphics.Paint textPaint,
                          float x, float y, float rowH, float indent, float padX, float padY,
                          float viewW, int depth) {
        if (y > m.getPadTop() + 10000) return; // far below viewport — stop
        // Color by type.
        int color;
        switch (node.type) {
            case STATEMENT: color = 0xFF4FC3F7; break; // light blue
            case EXPRESSION: color = 0xFF81C784; break; // green
            case VALUE: color = 0xFFFFB74D; break; // orange
            case BLOCK: default: color = 0xFF9575CD; break; // purple
        }
        // Draw the block rectangle.
        float blockW = Math.min(viewW - x - m.getPadRight(), m.getCharWidth() * 30);
        android.graphics.RectF rect = new android.graphics.RectF(x, y, x + blockW, y + rowH);
        bgPaint.setColor(color);
        canvas.drawRoundRect(rect, rowH * 0.2f, rowH * 0.2f, bgPaint);
        // Label.
        String label = node.label != null && !node.label.isEmpty() ? node.label : node.type.name().toLowerCase(java.util.Locale.ROOT);
        if (node.text != null && !node.text.isEmpty() && node.text.length() < 30) {
            label = label + ": " + node.text.trim();
        }
        textPaint.setColor(0xFF000000);
        canvas.drawText(label, x + padX, y + rowH * 0.7f, textPaint);
        // Children (indented, below).
        float childY = y + rowH + padY;
        for (BlockNode child : node.children) {
            drawNode(canvas, child, m, t, bgPaint, textPaint,
                x + indent, childY, rowH, indent, padX, padY, viewW, depth + 1);
            childY += rowH + padY;
        }
    }

    /** The root block node (parsed from the source). */
    private BlockNode root;

    /**
     * Parses the given source text into a block tree and stores it as the
     * root for rendering. Call this before {@link #draw} or after the
     * document changes.
     *
     * @param source the source text to parse
     * @since v1.0.9 (G7)
     */
    public void setSource(String source) {
        this.root = BlockParser.parse(source);
    }

    /** Returns the root block node, or null if {@link #setSource} wasn't called. */
    public BlockNode getRoot() {
        return root;
    }
}

