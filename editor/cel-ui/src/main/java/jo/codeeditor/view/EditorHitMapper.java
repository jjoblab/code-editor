package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;

/**
 * Mapping point écran ↔ offset document et position écran du caret —
 * conscient du word wrap, des plis repliés et des inlay hints.
 *
 * <p>Responsabilités déplacées à l'identique depuis {@link EditorView} :</p>
 * <ul>
 *   <li><b>caretScreenPos</b> — coordonnées écran du caret d'un offset
 *       (poignées de sélection, loupe, tests d'ancrage de zoom) : en mode
 *       wrap via la géométrie de rangée partagée ; hors wrap, X conscient
 *       des inlays (colonne VISUELLE tissée), des lignes de DÉBUT de pli
 *       (caret au-delà du préfixe aligné avant la chip {@code {...}}) et
 *       des lignes de FIN de pli (suffixe composite
 *       préfixe+placeholder+suffixe) ;</li>
 *   <li><b>offsetAt</b> — mappe un tap écran en offset document borné :
 *       colonne calculée depuis le X relatif à la zone de texte (le double
 *       retranchement historique padLeft+gutterWidth décalait chaque tap
 *       de ~5,5 caractères vers la gauche), remappage visualToRaw pour les
 *       inlays, et bornage à la longueur visible COMPOSITE des lignes de
 *       début de pli (le caret ne peut atterrir que sur des positions
 *       visibles — tap sur la chip = juste après, sur le suffixe).</li>
 * </ul>
 *
 * <p>EditorView conserve les relais package-privés (gestes de sélection,
 * tap resolver et tests les appellent).</p>
 */
class EditorHitMapper {

    private final EditorView view;

    EditorHitMapper(EditorView view) {
        this.view = view;
    }

    /**
     * Retourne les coordonnées écran du caret de l'offset donné,
     * utilisées pour positionner les poignées de sélection.
     */
    float[] caretScreenPos(int offset) {
        EditorDocument doc = view.session.getDocument();
        int line = EditorView.clamp(doc.lineForOffset(offset), 0, doc.lineCount() - 1);
        int col = offset - doc.lineStart(line);
        float charWidth = view.metrics.getCharWidth();
        float x;
        float y = view.docLineToY(line) - view.vOffset;
        if (view.wordWrap && view.wrapModel != null) {
            // Mapping rangée/colonne via wrapRowsFor (les rangées de
            // continuation sont PLUS ÉTROITES que la première — un simple
            // col/maxColsPerRow placerait le caret une rangée trop haut dès
            // que l'indent de continuation > 0).
            EditorView.WrapRows wr = view.wrapRowsFor(line, doc.lineEnd(line) - doc.lineStart(line));
            int rowInLine = wr.rowForCol(col);
            int colInRow = col - wr.rowStartCol(rowInLine);
            y += rowInLine * view.metrics.getLineHeight();
            x = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
                    + (rowInLine > 0 ? wr.wrapIndentCols * charWidth : 0)
                    + colInRow * charWidth;
        } else {
            // X du caret conscient des plis. Défaut : le calcul standard
            // col*charWidth ; si le caret tombe sur une ligne de début de
            // pli au-delà du préfixe, ou sur un suffixe de ligne de fin de
            // pli, surcharger x avec le calcul de position visible.
            // Conscient des inlays — le caret s'ancre à la colonne
            // VISUELLE (tissée), c.-à-d. juste AVANT un hint ancré à sa
            // colonne brute (sémantique rawToVisual).
            x = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
                + view.visualColFor(line, col) * charWidth - view.hOffset;

            // Ligne de début de pli : un caret au-delà du préfixe s'aligne
            // sur le bord droit du préfixe (juste avant la chip {...}) pour
            // ne pas être dessiné sur la chip.
            DiagnosticShift.FoldRegion fold = view.collapsedFoldStartingAtLine(line);
            if (fold != null) {
                int startLine = doc.lineForOffset(fold.start);
                int prefixEndCol = fold.start - doc.lineStart(startLine);
                if (col > prefixEndCol) {
                    x = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
                        + prefixEndCol * charWidth - view.hOffset;
                }
            } else if (view.session != null) {
                // Ligne de fin de pli : si col est dans la zone du suffixe,
                // repositionne X à prefixW + placeholderW + colInSuffix*charWidth
                // pour que le caret soit rendu à la position visible correcte
                // sur la ligne composite.
                for (DiagnosticShift.FoldRegion r : view.session.getFoldRegions()) {
                    if (!r.collapsed) continue;
                    int eLine = doc.lineForOffset(r.end);
                    if (eLine != line) continue;
                    int sLine = doc.lineForOffset(r.start);
                    String firstLine = doc.lineText(sLine);
                    String lastLine = line < doc.lineCount() ? doc.lineText(line) : "";
                    int prefixEnd = EditorView.clamp(r.start - doc.lineStart(sLine), 0, firstLine.length());
                    int suffixStart = EditorView.clamp(r.end - doc.lineStart(line), 0, lastLine.length());
                    int suffixLen = lastLine.length() - suffixStart;
                    if (col >= suffixStart && col <= suffixStart + suffixLen) {
                        int colInSuffix = col - suffixStart;
                        x = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
                            + (prefixEnd + r.placeholder.length() + colInSuffix) * charWidth - view.hOffset;
                    }
                    break; // Un seul pli peut se terminer sur une ligne donnée.
                }
            }
        }
        return new float[]{x, y};
    }

    /** Mappe un point écran (x, y) en offset document, borné à la fin de ligne.
     *  La colonne est calculée directement depuis le X relatif à la zone de
     *  texte — l'ancien code retranchait padLeft+gutterWidth deux fois (une
     *  fois dans offsetAt, une fois dans xToCol), ce qui décalait chaque tap
     *  de ~5,5 caractères vers la gauche.
     *
     *  <p>Si la ligne tapée est une ligne de DÉBUT de pli (c.-à-d. porte une
     *  région de pli réduite comme {@code public int add(int a, int b) {...}}),
     *  le texte visible n'est que {@code prefix + placeholder + suffix}
     *  (ex. {@code public int add(int a, int b) {...}}). La colonne est bornée
     *  à la longueur visible composite, pas à la longueur complète de la
     *  ligne document : le caret ne peut atterrir que sur des positions
     *  visibles — dans le préfixe, sur la chip placeholder, ou dans le
     *  suffixe. Placer le caret JUSTE APRÈS la chip {@code {...}} (sur le
     *  suffixe, ex. le {@code }} fermant) est une position visible valide et
     *  reste accessible. */
    int offsetAt(float x, float y) {
        EditorDocument doc = view.session.getDocument();
        int line;
        int col;
        float charWidth = view.metrics.getCharWidth();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        if (view.wordWrap && view.wrapModel != null) {
            // Mode wrap : déterminer la ligne document + la rangée dans cette ligne.
            line = EditorView.clamp(view.docLineForScreenY(y), 0, doc.lineCount() - 1);
            float lineTopY = view.docLineToY(line);
            int lineLen = doc.lineEnd(line) - doc.lineStart(line);
            EditorView.WrapRows wr = view.wrapRowsFor(line, lineLen);
            int rowInLine = (int) ((y + view.vOffset - lineTopY) / view.metrics.getLineHeight());
            rowInLine = EditorView.clamp(rowInLine, 0, wr.rows - 1);
            // ★ Inversion fidèle de la géométrie de pliage — la rangée de
            // continuation est indentée et plus étroite ; le tap y atterrit
            // sur la BONNE colonne (l'ancien col*maxColsPerRow décalait le
            // caret vers la droite sur les rangées indentées).
            float rowX = rowInLine > 0
                    ? textAreaLeft + wr.wrapIndentCols * charWidth : textAreaLeft;
            int colInRow = (int) ((x - rowX) / charWidth + 0.5f);
            int rowCap = rowInLine == 0 ? wr.maxColsPerRow : wr.colsPerCont;
            colInRow = EditorView.clamp(colInRow, 0, rowCap);
            col = wr.rowStartCol(rowInLine) + colInRow;
            col = EditorView.clamp(col, 0, lineLen);
        } else {
            // Le chemin sans wrap doit passer par un mapper conscient des
            // plis : l'ancien calcul brut (contentY - padTop) / lineHeight
            // renvoyait une ligne décalée VERS LE HAUT du nombre de lignes
            // cachées dès qu'un pli au-dessus du point tapé était réduit — le
            // texte s'insérait À L'INTÉRIEUR de la région réduite (caret sur
            // une ligne cachée, texte tapé invisible jusqu'à l'expansion du
            // pli). On passe donc par le même mapper conscient des plis que
            // la branche wrap et le chemin fold-strip de handleTap.
            line = EditorView.clamp(view.docLineForScreenY(y), 0, doc.lineCount() - 1);
            // Colonne depuis le X relatif à la zone de texte (tient compte du
            // défilement horizontal). textAreaLeft est l'endroit où la col 0
            // est dessinée à l'écran (moins hOffset).
            float colScreenX = textAreaLeft - view.hOffset;
            int visualCol = (int) ((x - colScreenX) / charWidth + 0.5f);
            // Conscient des inlays — remappe la colonne VISUELLE tapée vers
            // la colonne brute du document ; un toucher dans un hint s'aligne
            // sur son ancre (sémantique visualToRaw de CodeAssist).
            col = view.rawColFor(line, visualCol);

            // Si la ligne porte un pli réduit, borne col à la longueur
            // visible COMPOSITE (préfixe + placeholder + suffixe), pas à la
            // longueur complète de la ligne document. L'utilisateur peut ainsi
            // taper juste après la chip {...} — le caret atterrit au début du
            // suffixe (ex. sur le } fermant) au lieu de disparaître dans le
            // corps caché du pli.
            DiagnosticShift.FoldRegion fold = view.collapsedFoldStartingAtLine(line);
            if (fold != null) {
                int startLine = doc.lineForOffset(fold.start);
                int endLine = doc.lineForOffset(fold.end);
                String firstLine = doc.lineText(startLine);
                String lastLine = endLine < doc.lineCount() ? doc.lineText(endLine) : "";
                int prefixEndCol = EditorView.clamp(fold.start - doc.lineStart(startLine), 0, firstLine.length());
                int suffixStartCol = EditorView.clamp(fold.end - doc.lineStart(endLine), 0, lastLine.length());
                int suffixLen = lastLine.length() - suffixStartCol;
                int compositeLen = prefixEndCol + fold.placeholder.length() + suffixLen;
                // Borne la colonne tapée à la longueur visible composite.
                col = EditorView.clamp(col, 0, compositeLen);
                // Remappage de la colonne visible vers un offset document :
                // - col ∈ [0, prefixEndCol] → col doc = col (zone préfixe)
                // - col ∈ [prefixEndCol, prefixEndCol + placeholder.length()] →
                //   aligne sur fold.end (juste avant le suffixe, i.e. sur le }).
                //   C'est la position « juste après le {...} » voulue par l'utilisateur.
                // - col ∈ [prefixEndCol + placeholder.length(), compositeLen] →
                //   col doc = suffixStartCol + (col - prefixEndCol - placeholder.length())
                //   (quelque part dans le suffixe)
                if (col <= prefixEndCol) {
                    // Zone préfixe — col est déjà la colonne document.
                } else if (col <= prefixEndCol + fold.placeholder.length()) {
                    // Tap sur la chip placeholder — place le caret APRÈS la
                    // chip (au début du suffixe, i.e. sur le }). L'ancien
                    // comportement alignait sur prefixEndCol (avant la chip),
                    // ce qui rendait le caret invisible pour l'utilisateur.
                    // Mappe sur le début du suffixe de la ligne de FIN.
                    int endLineStart = doc.lineStart(endLine);
                    int endLineEnd = doc.lineEnd(endLine);
                    return Math.min(endLineStart + suffixStartCol, endLineEnd);
                } else {
                    // Tap au-delà du placeholder — mappe sur le suffixe de
                    // la ligne de FIN. Retourne un offset sur endLine pour que
                    // le caret soit rendu au X correct (suffixX + colInSuffix*charWidth).
                    int colInSuffix = col - prefixEndCol - fold.placeholder.length();
                    int endLineDocCol = suffixStartCol + colInSuffix;
                    int endLineStart = doc.lineStart(endLine);
                    int endLineEnd = doc.lineEnd(endLine);
                    return Math.min(endLineStart + endLineDocCol, endLineEnd);
                }
            } else {
                int lineLen = doc.lineEnd(line) - doc.lineStart(line);
                col = EditorView.clamp(col, 0, lineLen);
            }
        }
        int lineStart = doc.lineStart(line);
        int lineEnd = doc.lineEnd(line);
        return Math.min(lineStart + col, lineEnd);
    }
}
