package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

import java.util.List;

/**
 * Index de plis repliés à sommes préfixes, mémoïsé : répond à
 * « lignes cachées au-dessus de » et « ligne cachée ? » en O(log plis)
 * au lieu d'un balayage de la liste des plis à chaque appel.
 *
 * <p>Extrait d'EditorView car la passe de dessin appelle
 * {@code docLineToY} une fois par ligne visible : sans cet index, le
 * dessin serait O(viewport × plis × log lignes) avec des plis repliés.
 * L'index stocke les plis repliés en tableaux parallèles fusionnés et
 * triés.</p>
 *
 * <p>Clé de cache : (identité session, session.getFoldRevision(),
 * identité doc). Une simple comparaison de référence de liste NE PEUT
 * PAS suffire car getFoldRegions() enveloppe une NOUVELLE vue
 * unmodifiable à chaque appel et toggleFoldAtLine()/expandFoldAt()
 * mutent la liste sous-jacente en place — le compteur de révision
 * incrémenté à chaque mutation est le seul signal d'invalidation
 * fiable.</p>
 */
final class EditorFoldIndex {

    private final EditorView view;

    private EditorSession foldIndexSession;
    private int foldIndexRev = -1;
    private EditorDocument foldIndexDoc;
    private FoldIndex foldIndexCache = FoldIndex.EMPTY;

    EditorFoldIndex(EditorView view) {
        this.view = view;
    }

    /** Index mémoïsé, reconstruit quand (session, révision de plis,
     *  document) change. */
    FoldIndex get() {
        EditorSession s = view.session;
        if (s == null) return FoldIndex.EMPTY;
        if (s != foldIndexSession || s.getFoldRevision() != foldIndexRev
                || s.getDocument() != foldIndexDoc) {
            foldIndexSession = s;
            foldIndexRev = s.getFoldRevision();
            foldIndexDoc = s.getDocument();
            foldIndexCache = FoldIndex.build(s.getFoldRegions(), foldIndexDoc);
        }
        return foldIndexCache;
    }

    /**
     * Index des plis repliés, fusionnés/triés, avec sommes préfixes.
     * Les régions chevauchantes sont FUSIONNÉES (l'union est la
     * sémantique correcte et correspond à FoldModel.mergeRegions) —
     * sinon elles seraient comptées deux fois dans countHiddenLinesAbove.
     */
    static final class FoldIndex {
        final int[] startLines;    // fusionnés, triés, sans chevauchement
        final int[] endLines;      // (endLines[i] - startLines[i]) lignes cachées chacun
        final int[] hiddenBefore;  // sommes préfixes de (end - start), taille n+1
        final int[] startSum;      // sommes préfixes de startLines, taille n+1

        static final FoldIndex EMPTY = new FoldIndex(
                new int[0], new int[0], new int[0], new int[0]);

        private FoldIndex(int[] startLines, int[] endLines,
                          int[] hiddenBefore, int[] startSum) {
            this.startLines = startLines;
            this.endLines = endLines;
            this.hiddenBefore = hiddenBefore;
            this.startSum = startSum;
        }

        static FoldIndex build(List<jo.codeeditor.shift.DiagnosticShift.FoldRegion> folds,
                               EditorDocument doc) {
            int n = 0;
            for (jo.codeeditor.shift.DiagnosticShift.FoldRegion r : folds) {
                if (r.collapsed) n++;
            }
            if (n == 0) return EMPTY;
            int[] s = new int[n];
            int[] e = new int[n];
            int m = 0;
            for (jo.codeeditor.shift.DiagnosticShift.FoldRegion r : folds) {
                if (!r.collapsed) continue;
                s[m] = doc.lineForOffset(r.start);
                e[m] = doc.lineForOffset(r.end);
                if (e[m] < s[m]) { int t = s[m]; s[m] = e[m]; e[m] = t; }
                m++;
            }
            // Tri des deux tableaux parallèles par startLine (tri par
            // index — le nombre de plis est petit, typiquement des
            // dizaines).
            Integer[] order = new Integer[m];
            for (int i = 0; i < m; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(s[a], s[b]));
            int[] ss = new int[m];
            int[] ee = new int[m];
            for (int i = 0; i < m; i++) { ss[i] = s[order[i]]; ee[i] = e[order[i]]; }
            // Fusion des régions chevauchantes (union — même sémantique
            // que FoldModel.mergeRegions).
            int[] ms = new int[m];
            int[] me = new int[m];
            int k = 0;
            for (int i = 0; i < m; i++) {
                if (k > 0 && ss[i] <= me[k - 1]) {
                    if (ee[i] > me[k - 1]) me[k - 1] = ee[i];
                } else {
                    ms[k] = ss[i];
                    me[k] = ee[i];
                    k++;
                }
            }
            // Sommes préfixes : hiddenBefore[t] = Σ_{i<t} (me[i]-ms[i]) ;
            // startSum[t] = Σ_{i<t} ms[i].
            int[] hiddenBefore = new int[k + 1];
            int[] startSum = new int[k + 1];
            for (int i = 0; i < k; i++) {
                hiddenBefore[i + 1] = hiddenBefore[i] + (me[i] - ms[i]);
                startSum[i + 1] = startSum[i] + ms[i];
            }
            return new FoldIndex(java.util.Arrays.copyOf(ms, k),
                    java.util.Arrays.copyOf(me, k), hiddenBefore, startSum);
        }

        boolean isEmpty() { return startLines.length == 0; }

        /**
         * Nombre de lignes doc strictement AU-DESSUS de {@code docLine}
         * cachées par des plis repliés. O(log plis).
         */
        int hiddenAbove(int docLine) {
            int n = startLines.length;
            if (n == 0 || docLine <= 0) return 0;
            // Les plis [0, k) commencent strictement au-dessus de docLine.
            int k = lowerBound(startLines, docLine);
            if (k == 0) return 0;
            // Parmi eux, les plis [0, j) finissent aussi strictement
            // au-dessus de docLine (entièrement au-dessus — endLines est
            // trié car les régions sont fusionnées).
            int j = lowerBound(endLines, docLine);
            int sum = hiddenBefore[j];
            // Les plis [j, k) chevauchent docLine : chacun cache
            // (docLine-1-startLine) lignes au-dessus de lui.
            sum += (k - j) * (docLine - 1) - (startSum[k] - startSum[j]);
            return sum;
        }

        /** Vrai quand {@code docLine} est À L'INTÉRIEUR d'un pli replié —
         * c.-à-d. startLine < docLine <= endLine pour une région fusionnée. O(log plis). */
        boolean isHidden(int docLine) {
            int n = startLines.length;
            if (n == 0 || docLine <= 0) return false;
            int k = lowerBound(startLines, docLine);
            if (k == 0) return false;
            // Régions triées et sans chevauchement : le seul candidat
            // avec start < docLine est l'index k-1, et (endLines étant
            // trié) aucune région antérieure ne peut atteindre docLine.
            return endLines[k - 1] >= docLine;
        }

        private static int lowerBound(int[] a, int key) {
            int lo = 0, hi = a.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (a[mid] < key) lo = mid + 1; else hi = mid;
            }
            return lo;
        }
    }
}
