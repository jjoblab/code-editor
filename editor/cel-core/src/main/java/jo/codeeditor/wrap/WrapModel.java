package jo.codeeditor.wrap;

import java.util.*;

/**
 * Modèle de retour à la ligne automatique : suit le nombre de rangées de
 * wrap par ligne du document. Utilise une somme préfixe de rangées
 * visuelles pour un mapping ligne→rangée en O(1). Conscient des plis :
 * peut superposer les lignes visuelles du modèle de pliage. Reprend le
 * design du {@code WrapModel.kt} de CodeAssist.
 */
public class WrapModel {

    /** Nombre de rangées de wrap par ligne du document. */
    private int[] rowsPerLine;
    /** Somme préfixe : prefixSum[i] = total des rangées visuelles des lignes 0..i-1. */
    private long[] prefixSum;
    /** Nombre de lignes du document. */
    private int lineCount;

    /**
     * Crée un WrapModel avec 1 rangée par ligne par défaut.
     */
    public WrapModel(int lineCount) {
        this.lineCount = lineCount;
        this.rowsPerLine = new int[lineCount];
        Arrays.fill(rowsPerLine, 1);
        rebuildPrefixSum();
    }

    /**
     * Redimensionne le modèle quand le nombre de lignes du document change.
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
     * Définit le nombre de rangées de wrap pour une ligne précise du document.
     */
    public void setRows(int line, int count) {
        if (line < 0 || line >= lineCount) return;
        rowsPerLine[line] = Math.max(1, count);
        rebuildPrefixSum();
    }

    /**
     * Renvoie la rangée visuelle de tête d'une ligne du document.
     */
    public long topRow(int line) {
        if (line <= 0) return 0;
        if (line >= lineCount) return prefixSum[lineCount];
        return prefixSum[line];
    }

    /**
     * Renvoie le nombre de rangées visuelles d'une ligne du document.
     */
    public int rowsOf(int line) {
        if (line < 0 || line >= lineCount) return 1;
        return rowsPerLine[line];
    }

    /**
     * Fait correspondre une rangée visuelle à la ligne du document à
     * laquelle elle appartient. Utilise une recherche binaire sur la
     * somme préfixe.
     */
    public int docLineForRow(long row) {
        if (row < 0) return 0;
        // Recherche binaire
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
     * Renvoie le nombre total de rangées visuelles.
     */
    public long totalRows() {
        return prefixSum[lineCount];
    }

    /**
     * Renvoie le nombre de lignes du document.
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
