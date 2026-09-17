package jo.codeeditor.blocks;

import java.util.*;
import jo.codeeditor.view.chrome.EditorTheme;

/**
 * Édition par blocs : analyse le Java en un arbre de blocs typés, dessine
 * les blocs avec des formes et fournit la complétion dans les emplacements
 * de blocs. Éditeur structurel simplifié pour l'édition de code par blocs.
 * Porté depuis le BlockEditor.kt de CodeAssist.
 */
public class BlockEditor {

    // ── BlockType ─────────────────────────────────────────────────

    /**
     * Type d'un bloc dans l'arbre.
     */
    public enum BlockType {
        EXPRESSION,
        STATEMENT,
        BLOCK,
        VALUE
    }

    // ── BlockNode ─────────────────────────────────────────────────

    /**
     * Un nœud de l'arbre de blocs.
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

        /** Renvoie true si ce nœud a des enfants. */
        public boolean hasChildren() {
            return !children.isEmpty();
        }

        /** Renvoie la profondeur de l'arbre enraciné à ce nœud. */
        public int depth() {
            int max = 0;
            for (BlockNode child : children) {
                max = Math.max(max, child.depth());
            }
            return max + 1;
        }

        /** Renvoie le nombre total de nœuds dans l'arbre. */
        public int nodeCount() {
            int count = 1;
            for (BlockNode child : children) {
                count += child.nodeCount();
            }
            return count;
        }

        /** Recherche le nœud le plus profond contenant l'offset donné. */
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
     * Analyseur convertissant du code source Java en arbre de blocs.
     * Simplifié : identifie les déclarations de premier niveau, les
     * instructions et les expressions.
     */
    public static final class BlockParser {

        /**
         * Analyse du code source Java en arbre de blocs.
         *
         * @param source code source Java
         * @return nœud bloc racine
         */
        public static BlockNode parse(String source) {
            if (source == null || source.isEmpty()) {
                return new BlockNode(BlockType.BLOCK, "", 0, 0, Collections.emptyList(), "empty");
            }

            List<BlockNode> children = new ArrayList<>();
            int pos = 0;

            while (pos < source.length()) {
                // Ignore les blancs
                while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
                if (pos >= source.length()) break;

                BlockNode stmt = parseStatement(source, pos);
                if (stmt != null) {
                    children.add(stmt);
                    pos = stmt.endOffset;
                } else {
                    // Ignore le caractère non reconnu
                    pos++;
                }
            }

            return new BlockNode(BlockType.BLOCK, source, 0, source.length(), children, "file");
        }

        /**
         * Analyse une instruction unique commençant à pos.
         */
        private static BlockNode parseStatement(String source, int pos) {
            if (pos >= source.length()) return null;

            // Ignore les blancs
            while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) pos++;
            if (pos >= source.length()) return null;

            int start = pos;

            // Trouve la fin de cette instruction (point-virgule, accolade fermante ou EOF)
            int depth = 0;
            boolean inString = false;
            char stringChar = 0;

            while (pos < source.length()) {
                char ch = source.charAt(pos);

                if (inString) {
                    if (ch == '\\') {
                        pos++; // ignore l'échappement
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
                        // Instruction bloc : trouve l'accolade fermante correspondante
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

            // Instruction sans terminateur
            if (pos > start) {
                String text = source.substring(start, pos);
                BlockType type = classifyStatement(text);
                String label = extractLabel(text, type);
                return new BlockNode(type, text, start, pos, Collections.emptyList(), label);
            }

            return null;
        }

        /**
         * Analyse le corps d'un bloc (entre { et }).
         */
        private static List<BlockNode> parseBlockBody(String source, int closeBracePos) {
            // Trouve l'accolade ouvrante correspondant à closeBracePos
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
         * Classe une instruction d'après son texte.
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
         * Extrait un libellé court pour un bloc.
         */
        private static String extractLabel(String text, BlockType type) {
            String trimmed = text.trim();
            // Premier mot ou mot-clé
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
         * Trouve l'accolade fermante correspondant à l'accolade ouvrante à pos.
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
     * Produit les blocs sous forme de données structurées pour l'affichage.
     * Chaque bloc reçoit un type de forme, une couleur et un niveau
     * d'indentation.
     */
    public static final class BlockRenderer {

        /** Données de bloc rendu. */
        public static final class RenderedBlock {
            public final int indentLevel;
            public final BlockType type;
            public final String label;
            public final String shape; // "rect", "rounded", "diamond", "hexagon"
            public final int color;    // couleur ARGB
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

        /** Couleurs par défaut des types de bloc. */
        private static final int COLOR_EXPRESSION = 0xFF4FC3F7; // bleu clair
        private static final int COLOR_STATEMENT = 0xFF81C784;  // vert clair
        private static final int COLOR_BLOCK = 0xFFFFB74D;      // orange clair
        private static final int COLOR_VALUE = 0xFFBA68C8;      // violet clair

        /**
         * Rend un arbre de blocs en liste plate de blocs rendus.
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
     * Complétion dans les emplacements de blocs : suggère des complétions
     * valides selon le type d'emplacement (expression, instruction, etc.).
     */
    public static final class SlotCompletion {

        /** Une suggestion de complétion d'emplacement. */
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
         * Obtient les suggestions pour un emplacement du type donné.
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

    // ── Rendu ─────────────────────────────────────────────────────

    /**
     * Dessine l'arbre de blocs en rectangles colorés imbriqués (façon
     * Scratch). Chaque {@link BlockNode} est un rectangle arrondi dont la
     * couleur dépend de son {@link BlockType}. Les enfants sont dessinés
     * en retrait dans leur parent.
     *
     * <p>Rendu basique — les formes de blocs complètes façon Scratch
     * (tenons de puzzle, emplacements, ombres portées) peuvent être
     * ajoutées par-dessus ultérieurement. L'objectif ici est un visuel
     * fonctionnel permettant de voir la structure de blocs et de toucher
     * un bloc pour le sélectionner.
     *
     * @param canvas   le Canvas sur lequel dessiner
     * @param metrics  les métriques de l'éditeur (largeur de caractère, hauteur de ligne)
     * @param theme    le thème de l'éditeur (couleurs de fond/texte)
     * @param scrollY  l'offset de défilement vertical (px)
     * @param viewH    la hauteur du viewport (px)
     * @param viewW    la largeur du viewport (px)
     */
    public void draw(android.graphics.Canvas canvas, Object metrics, Object theme,
                     float scrollY, float viewH, float viewW) {
        if (root == null) return;
        // Casts sans réflexion via les classes EditorMetrics/EditorTheme —
        // mais BlockEditor est dans un autre package et ne doit pas dépendre
        // de la couche vue. On accepte donc Object et on caste ici pour
        // éviter une dépendance circulaire. L'appelant (EditorView) passe
        // toujours les bons types.
        jo.codeeditor.view.EditorMetrics m = (jo.codeeditor.view.EditorMetrics) metrics;
        jo.codeeditor.view.chrome.EditorTheme t = (jo.codeeditor.view.chrome.EditorTheme) theme;
        android.graphics.Paint bgPaint = new android.graphics.Paint();
        android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(m.getTypeface());
        textPaint.setTextSize(m.getTextSize() * 0.9f);
        float padX = m.getCharWidth() * 0.5f;
        float padY = m.getLineHeight() * 0.15f;
        float rowH = m.getLineHeight() * 1.1f;
        float indent = m.getCharWidth() * 2;
        // Parcourt l'arbre en profondeur, en dessinant chaque nœud comme une rangée.
        drawNode(canvas, root, m, t, bgPaint, textPaint,
            m.getPadLeft(), m.getPadTop() - scrollY, rowH, indent, padX, padY, viewW, 0);
    }

    private void drawNode(android.graphics.Canvas canvas, BlockNode node,
                          jo.codeeditor.view.EditorMetrics m, jo.codeeditor.view.chrome.EditorTheme t,
                          android.graphics.Paint bgPaint, android.graphics.Paint textPaint,
                          float x, float y, float rowH, float indent, float padX, float padY,
                          float viewW, int depth) {
        if (y > m.getPadTop() + 10000) return; // bien en dessous du viewport — arrêt
        // Couleur selon le type.
        int color;
        switch (node.type) {
            case STATEMENT: color = 0xFF4FC3F7; break; // bleu clair
            case EXPRESSION: color = 0xFF81C784; break; // vert
            case VALUE: color = 0xFFFFB74D; break; // orange
            case BLOCK: default: color = 0xFF9575CD; break; // violet
        }
        // Dessine le rectangle du bloc.
        float blockW = Math.min(viewW - x - m.getPadRight(), m.getCharWidth() * 30);
        android.graphics.RectF rect = new android.graphics.RectF(x, y, x + blockW, y + rowH);
        bgPaint.setColor(color);
        canvas.drawRoundRect(rect, rowH * 0.2f, rowH * 0.2f, bgPaint);
        // Libellé.
        String label = node.label != null && !node.label.isEmpty() ? node.label : node.type.name().toLowerCase(java.util.Locale.ROOT);
        if (node.text != null && !node.text.isEmpty() && node.text.length() < 30) {
            label = label + ": " + node.text.trim();
        }
        textPaint.setColor(0xFF000000);
        canvas.drawText(label, x + padX, y + rowH * 0.7f, textPaint);
        // Enfants (en retrait, en dessous).
        float childY = y + rowH + padY;
        for (BlockNode child : node.children) {
            drawNode(canvas, child, m, t, bgPaint, textPaint,
                x + indent, childY, rowH, indent, padX, padY, viewW, depth + 1);
            childY += rowH + padY;
        }
    }

    /** Le nœud bloc racine (analysé depuis la source). */
    private BlockNode root;

    /**
     * Analyse le texte source donné en arbre de blocs et le stocke comme
     * racine pour le rendu. À appeler avant {@link #draw} ou après toute
     * modification du document.
     *
     * @param source le texte source à analyser
     */
    public void setSource(String source) {
        this.root = BlockParser.parse(source);
    }

    /** Renvoie le nœud bloc racine, ou null si {@link #setSource} n'a pas été appelé. */
    public BlockNode getRoot() {
        return root;
    }
}

