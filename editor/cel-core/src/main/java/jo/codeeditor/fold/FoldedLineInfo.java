package jo.codeeditor.fold;

/**
 * Informations sur le rendu visuel d'une ligne pliée.
 * Quand un pli est réduit, plusieurs lignes du document sont rendues comme
 * une seule ligne visuelle : préfixe + placeholder + suffixe.
 */
public final class FoldedLineInfo {
    /** Début de la ligne visuelle dans le document. */
    public final int startLine;
    /** Dernière ligne du document faisant partie de ce pli. */
    public final int endLine;
    /** Colonne où commence le placeholder (le préfixe s'arrête ici). */
    public final int prefixEnd;
    /** Colonne où commence le suffixe (le placeholder s'arrête ici). */
    public final int suffixStart;
    /** Texte du placeholder affiché pour le pli réduit. */
    public final String placeholder;
    /** ★ B21c : colonne de FIN du pli dans la DERNIÈRE ligne du pli — le
     *  suffixe commence à cette colonne. {@code -1} quand le pli se
     *  termine à la fin de la dernière ligne (aucun suffixe). Historique :
     *  cette colonne était calculée puis JETÉE — compositeText recalcule
     *  une colonne toujours 0, donc affichait la dernière ligne ENTIÈRE
     *  comme suffixe. */
    public final int lastLineFoldEndCol;

    public FoldedLineInfo(int startLine, int endLine, int prefixEnd, int suffixStart,
                          String placeholder) {
        this(startLine, endLine, prefixEnd, suffixStart, placeholder, -1);
    }

    public FoldedLineInfo(int startLine, int endLine, int prefixEnd, int suffixStart,
                          String placeholder, int lastLineFoldEndCol) {
        this.startLine = startLine;
        this.endLine = endLine;
        this.prefixEnd = prefixEnd;
        this.suffixStart = suffixStart;
        this.placeholder = placeholder;
        this.lastLineFoldEndCol = lastLineFoldEndCol;
    }

    @Override
    public String toString() {
        return "FoldedLineInfo(line " + startLine + "-" + endLine + ", prefix=" + prefixEnd
            + ", suffix=" + suffixStart + ", ph=\"" + placeholder + "\")";
    }
}
