package jo.codeeditor.highlight;

import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Détecteur de régions pliables multi-langages. Java pur, testable sur JVM hôte.
 *
 * <p>Détecte les blocs de code pliables pour :
 * <ul>
 *   <li>Langages à accolades (Java, Kotlin, JS, C, etc.) — {@code { ... }}</li>
 *   <li>Lua — {@code function...end}, {@code do...end}, {@code if...then...end}</li>
 *   <li>Python — basé sur l'indentation ({@code def}, {@code class}, {@code if}, etc.)</li>
 *   <li>XML — blocs d'éléments ({@code <tag> ... </tag>})</li>
 * </ul>
 *
 * <p>Utilisation :
 * <pre>{@code
 * List<DiagnosticShift.FoldRegion> folds = FoldDetector.detect(text, "java");
 * session.setFoldRegions(folds);
 * }</pre>
 */
public final class FoldDetector {

    private FoldDetector() {}

    /**
     * Détecte les régions pliables pour le texte et le langage donnés.
     *
     * @param text     le texte complet du document
     * @param language l'identifiant du langage ("java", "kotlin", "lua", "python", "xml", etc.)
     * @return une liste de régions pliables (peut être vide)
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

    // ── Langages à accolades : Java, Kotlin, JS, C, etc. ──────────────────

    /**
     * Détecte les blocs { ... }. Ignore les accolades dans les chaînes et les commentaires.
     */
    public static List<DiagnosticShift.FoldRegion> detectBraceFolds(String text) {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        ArrayDeque<int[]> stack = new ArrayDeque<>(); // [offset, index de ligne]
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
                // Détection des commentaires.
                if (c == '/' && next == '/') { inLineComment = true; break; }
                if (c == '/' && next == '*') { inBlockComment = true; ci++; continue; }
                // Détection des chaînes.
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

    // ── Lua : function...end, do...end, if...then...end ───────────

    /**
     * Détecte les blocs Lua. Suit les ouvrants function/do/for/while et les fermants end.
     */
    public static List<DiagnosticShift.FoldRegion> detectLuaFolds(String text) {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        ArrayDeque<int[]> stack = new ArrayDeque<>(); // [offsetDébut, ligneDébut]
        String[] lines = text.split("\n", -1);
        int offset = 0;

        for (int li = 0; li < lines.length; li++) {
            String trimmed = lines[li].trim();
            // Ignore les commentaires et les chaînes.
            if (trimmed.startsWith("--")) { offset += lines[li].length() + 1; continue; }

            // Détection des ouvrants de bloc.
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
            // Détection des fermants de bloc.
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

    // ── Python : basé sur l'indentation ─────────────────────────────────────

    /**
     * Détecte les blocs Python via l'indentation. Un bloc commence quand une
     * ligne se terminant par ':' est suivie d'une ligne plus indentée, et se
     * termine quand l'indentation revient au niveau de l'ouvrant du bloc.
     */
    public static List<DiagnosticShift.FoldRegion> detectPythonFolds(String text) {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        ArrayDeque<int[]> stack = new ArrayDeque<>(); // [index de ligne, indentation, offset]
        int offset = 0;

        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
            String trimmed = line.trim();
            int indent = line.length() - line.stripLeading().length();

            // Dépile les blocs dont l'indentation >= l'indentation courante (une désindentation les ferme).
            while (!stack.isEmpty() && stack.peek()[1] >= indent && !trimmed.isEmpty()) {
                int[] open = stack.pop();
                if (li - open[0] >= 2) {
                    folds.add(new DiagnosticShift.FoldRegion(
                        open[2], offset, ": \u2026", "block", false));
                }
            }

            // Empile les ouvrants de bloc (lignes terminées par ':').
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
        // Ferme les blocs encore ouverts à la fin du fichier.
        while (!stack.isEmpty()) {
            int[] open = stack.pop();
            if (lines.length - open[0] >= 3) {
                folds.add(new DiagnosticShift.FoldRegion(
                    open[2], offset, ": \u2026", "block", false));
            }
        }
        return folds;
    }

    // ── XML : blocs d'éléments ──────────────────────────────────────

    /**
     * Détecte les blocs d'éléments XML : <tag ...> ... </tag>.
     */
    public static List<DiagnosticShift.FoldRegion> detectXmlFolds(String text) {
        List<DiagnosticShift.FoldRegion> folds = new ArrayList<>();
        // Analyse simple sans regex : trouve <tag> et le </tag> correspondant.
        int pos = 0;
        while (pos < text.length()) {
            int openIdx = text.indexOf('<', pos);
            if (openIdx < 0) break;
            // Ignore <?xml, <!--, <![CDATA[
            if (openIdx + 1 < text.length() && (text.charAt(openIdx + 1) == '?'
                || text.charAt(openIdx + 1) == '!' || text.charAt(openIdx + 1) == '/')) {
                pos = openIdx + 1;
                continue;
            }
            // Extrait le nom de la balise.
            int tagEnd = text.indexOf('>', openIdx);
            if (tagEnd < 0) break;
            String tagContent = text.substring(openIdx + 1, tagEnd).trim();
            // Ignore les balises auto-fermantes (<tag/>).
            if (tagContent.endsWith("/")) { pos = tagEnd + 1; continue; }
            // Balise avec attributs — n'extrait que le nom.
            int spaceIdx = tagContent.indexOf(' ');
            String tagName = spaceIdx >= 0 ? tagContent.substring(0, spaceIdx) : tagContent;
            if (tagName.isEmpty()) { pos = tagEnd + 1; continue; }

            // Recherche le </tagName> correspondant.
            String closeTag = "</" + tagName + ">";
            int closeIdx = text.indexOf(closeTag, tagEnd + 1);
            if (closeIdx < 0) { pos = tagEnd + 1; continue; }

            // Replie uniquement si la zone s'étend sur plusieurs lignes.
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

    // ── Markdown : titres ───────────────────────────────────────

    /**
     * Détecte les sections pliables Markdown : chaque titre ouvre une section
     * qui s'étend jusqu'au prochain titre de niveau égal ou supérieur.
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
                // Ferme le titre précédent si celui-ci est de niveau égal ou supérieur.
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
        // Ferme le dernier titre à la fin du fichier.
        if (lastHeadingOffset >= 0 && lines.length - lastHeadingLine >= 3) {
            folds.add(new DiagnosticShift.FoldRegion(
                lastHeadingOffset, offset, "#\u2026", "heading", false));
        }
        return folds;
    }
}
