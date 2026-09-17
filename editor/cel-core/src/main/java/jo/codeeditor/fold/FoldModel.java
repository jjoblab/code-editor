package jo.codeeditor.fold;

import java.util.*;

/**
 * Modèle de pliage : correspondance entre lignes du document et lignes
 * visuelles (pliées).
 * Reprend le design du {@code FoldModel.kt} de CodeAssist.
 */
public class FoldModel {

    /**
     * Infos de pliage par ligne visuelle.
     * visualLine[i] indique quelle(s) ligne(s) du document elle représente.
     */
    public static final class VisualLine {
        public final int docLine;
        public final FoldedLineInfo foldInfo; // null si non pliée

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
     * Construit un modèle de pliage à partir du nombre de lignes d'un
     * document et de ses régions pliables. Les régions sont fusionnées
     * et triées, puis les lignes masquées sont calculées.
     *
     * @param lineCount nombre total de lignes du document
     * @param regions   régions pliables (celles réduites)
     * @param docText   texte du document (pour calculer préfixe/suffixe)
     * @return un FoldModel
     */
    public static FoldModel build(int lineCount, List<FoldRegion> regions, List<String> lineTexts) {
        // Ne garde que les régions réduites
        List<FoldRegion> collapsed = new ArrayList<>();
        for (FoldRegion r : regions) {
            if (r.collapsed) collapsed.add(r);
        }

        // Tri par début
        collapsed.sort(Comparator.comparingInt(r -> r.start));

        // Fusionne les régions qui se chevauchent
        List<FoldRegion> merged = mergeRegions(collapsed);

        // Calcule les plages de lignes masquées
        // Pour chaque région, détermine quelles lignes sont masquées
        Set<Integer> hiddenLines = new LinkedHashSet<>();
        Map<Integer, FoldedLineInfo> foldInfoMap = new HashMap<>();

        for (FoldRegion r : merged) {
            // Détermine les lignes de début/fin
            int startLine = 0;
            int endLine = 0;
            if (lineTexts != null) {
                // Calcule la ligne à partir de l'offset
                int offset = 0;
                for (int i = 0; i < lineTexts.size(); i++) {
                    int lineEnd = offset + lineTexts.get(i).length();
                    if (offset <= r.start && r.start <= lineEnd) startLine = i;
                    if (offset <= r.end && r.end <= lineEnd) { endLine = i; break; }
                    offset = lineEnd + 1; // +1 pour le retour à la ligne
                }
            }

            // Masque les lignes entre startLine+1 et endLine
            String prefixText = "";
            String suffixText = "";
            if (lineTexts != null && startLine < lineTexts.size()) {
                String firstLine = lineTexts.get(startLine);
                // Trouve la colonne du début du pli dans la première ligne
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

        // Construit les lignes visuelles
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
                // Chevauchement : fusion
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
     * Renvoie true si la ligne de document donnée est masquée par un pli.
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
     * Renvoie le pli commençant à la ligne de document donnée, ou null.
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
     * Fait correspondre une rangée visuelle à la ligne de document.
     */
    public int docLineForVisual(int visualRow) {
        if (visualRow < 0) return 0;
        if (visualRow >= visualLines.size()) return totalDocLines - 1;
        return visualLines.get(visualRow).docLine;
    }

    /**
     * Fait correspondre une ligne de document à la rangée visuelle.
     */
    public int visualForDocLine(int docLine) {
        for (int i = 0; i < visualLines.size(); i++) {
            if (visualLines.get(i).docLine == docLine) return i;
            // Si docLine est masquée, renvoie la ligne visuelle du pli
            if (visualLines.get(i).foldInfo != null
                && docLine > visualLines.get(i).docLine
                && docLine <= visualLines.get(i).foldInfo.endLine) {
                return i;
            }
        }
        return visualLines.size() - 1;
    }

    /**
     * Renvoie le texte composite d'une ligne visuelle
     * (préfixe + placeholder + suffixe).
     */
    public String compositeText(int visualRow, List<String> lineTexts) {
        if (visualRow < 0 || visualRow >= visualLines.size()) return "";
        VisualLine vl = visualLines.get(visualRow);
        if (vl.foldInfo == null) {
            // Non pliée : renvoie directement le texte de la ligne
            if (vl.docLine < lineTexts.size()) return lineTexts.get(vl.docLine);
            return "";
        }
        FoldedLineInfo fi = vl.foldInfo;
        String lineText = vl.docLine < lineTexts.size() ? lineTexts.get(vl.docLine) : "";
        String prefix = lineText.substring(0, Math.min(fi.prefixEnd, lineText.length()));
        String suffix = "";
        if (fi.endLine < lineTexts.size()) {
            String lastLine = lineTexts.get(fi.endLine);
            // Le suffixe commence à l'offset suffixStart depuis le début de la
            // zone de pli de la dernière ligne
            // Par simplicité, utilise suffixStart comme colonne dans la dernière ligne
            int col = fi.suffixStart - fi.prefixEnd - fi.placeholder.length();
            suffix = lastLine.substring(Math.max(0, Math.min(col, lastLine.length())));
        }
        return prefix + fi.placeholder + suffix;
    }

    /**
     * Renvoie le nombre de lignes visuelles.
     */
    public int visualLineCount() {
        return visualLines.size();
    }

    /**
     * Renvoie le nombre total de lignes du document.
     */
    public int totalDocLines() {
        return totalDocLines;
    }

    /**
     * Renvoie la liste des lignes visuelles.
     */
    public List<VisualLine> getVisualLines() {
        return Collections.unmodifiableList(visualLines);
    }
}
