package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;

/**
 * Géométrie du retour à la ligne (word wrap) — correspondance ligne
 * document ↔ rangée visuelle, consciente des plis repliés.
 *
 * <p>Responsabilités déplacées à l'identique depuis {@link EditorView} :</p>
 * <ul>
 *   <li><b>géométrie de repli d'une ligne</b> — calcul de
 *       {@link EditorView.WrapRows} (1re rangée pleine largeur, rangées de
 *       continuation indentées du leading-whitespace, cap à la
 *       demi-largeur) ;</li>
 *   <li><b>reconstruction du modèle de wrap</b> — recompte les rangées par
 *       ligne après édition/redimensionnement via la MÊME formule que le
 *       découpage du rendu (toute divergence laisserait la queue des
 *       lignes indentées jamais dessinée) ;</li>
 *   <li><b>mappings Y</b> — docLineToY (haut de la ligne dans l'espace
 *       contenu) et son inverse docLineForScreenY (recherche binaire sur
 *       {@code visibleIndex(l) = l - hiddenAbove(l)} + saut en avant dans
 *       le pli chevauchant, repli final sur la dernière ligne) ;</li>
 *   <li><b>colonne d'une rangée repliée</b> — wrappedColFor, conscient des
 *       rangées de continuation plus étroites.</li>
 * </ul>
 *
 * <p>EditorView conserve le type imbriqué {@link EditorView.WrapRows}
 * (source unique partagée par le rendu, le caret/tap, le scroll et les
 * chips ; référencé par les tests) et les relais package-privés. L'état
 * ({@code wordWrap}, {@code wrapModel}, {@code wrapWidthPx}) reste sur la
 * vue, lue par de nombreux collaborateurs.</p>
 */
class EditorWrapGeometry {

    private final EditorView view;

    EditorWrapGeometry(EditorView view) {
        this.view = view;
    }

    /**
     * Calcule la géométrie de pliage d'une ligne (word-wrap). Hors wrap,
     * retourne une rangée unique pleine largeur.
     */
    EditorView.WrapRows wrapRowsFor(int line, int lineLen) {
        if (!view.wordWrap || view.wrapModel == null || view.wrapWidthPx <= 0) {
            return new EditorView.WrapRows(Integer.MAX_VALUE / 4, 0, Integer.MAX_VALUE / 4, 1);
        }
        float charWidth = view.metrics.getCharWidth();
        int maxColsPerRow = Math.max(1, (int) (view.wrapWidthPx / charWidth));
        String text = lineLen > 0 ? view.session.getDocument().lineText(line) : "";
        int leadingWs = 0;
        for (int i = 0; i < text.length() && leadingWs < lineLen; i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') leadingWs++;
            else break;
        }
        int maxIndent = Math.max(0, maxColsPerRow / 2 - 1);
        int wrapIndentCols = Math.min(leadingWs, maxIndent);
        int colsPerCont = Math.max(1, maxColsPerRow - wrapIndentCols);
        int rows = lineLen <= maxColsPerRow ? 1
                : 1 + (lineLen - maxColsPerRow + colsPerCont - 1) / colsPerCont;
        return new EditorView.WrapRows(maxColsPerRow, wrapIndentCols, colsPerCont, rows);
    }

    /**
     * Retourne la coordonnée Y (dans l'espace contenu, avant vOffset) du
     * HAUT de la ligne de document donnée. Quand le word wrap est activé,
     * ceci tient compte des rangées supplémentaires des lignes repliées
     * précédentes.
     */
    float docLineToY(int docLine) {
        if (!view.wordWrap || view.wrapModel == null) {
            // Tient compte des régions de plis repliés — les lignes
            // cachées n'occupent aucune rangée visuelle, donc chaque ligne
            // doc sous un pli replié est remontée de (nombre de lignes
            // cachées au-dessus) × lineHeight. Sinon, replier un pli
            // laisserait un vide visuel (les lignes cachées seraient
            // sautées par la boucle de dessin, mais les lignes suivantes
            // seraient encore dessinées à leur Y d'origine).
            int hiddenAbove = view.countHiddenLinesAbove(docLine);
            return view.metrics.getPadTop() + (docLine - hiddenAbove) * view.metrics.getLineHeight();
        }
        return view.metrics.getPadTop() + view.wrapModel.topRow(docLine) * view.metrics.getLineHeight();
    }

    /**
     * Retourne le nombre de rangées visuelles occupées par la ligne de
     * document donnée. Toujours ≥ 1.
     */
    int rowsForDocLine(int docLine) {
        if (!view.wordWrap || view.wrapModel == null) return 1;
        return view.wrapModel.rowsOf(docLine);
    }

    /**
     * Fait correspondre une coordonnée Y écran vers une ligne de document,
     * en tenant compte des rangées repliées ET des plis repliés. Utilisé
     * par le tap-pour-positionner-le-caret et les calculs de scroll.
     */
    int docLineForScreenY(float screenY) {
        float contentY = screenY + view.vOffset - view.metrics.getPadTop();
        float lineHeight = view.metrics.getLineHeight();
        int visualRow = (int) (contentY / lineHeight);
        if (!view.wordWrap || view.wrapModel == null) {
            // Inverse le mapping Y conscient des plis : recherche binaire
            // sur visibleIndex(l) = l - hiddenAbove(l), qui est croissante,
            // plus un saut en avant dans le (au plus un) pli chevauchant
            // le résultat. Mêmes sémantiques qu'un parcours ligne par
            // ligne, y compris le repli final sur la dernière ligne doc.
            if (view.session == null) return Math.max(0, visualRow);
            EditorDocument doc = view.session.getDocument();
            if (doc == null) return Math.max(0, visualRow);
            EditorFoldIndex.FoldIndex idx = view.foldIndex.get();
            int docLineCount = doc.lineCount();
            if (idx.isEmpty() || docLineCount == 0) {
                return Math.max(0, visualRow);
            }
            // Un parcours ligne par ligne ne matchait jamais un visualRow
            // négatif — il retombait sur la dernière ligne. On préserve ça.
            if (visualRow < 0) return Math.max(0, docLineCount - 1);
            // Plus petite ligne avec visibleIndex >= visualRow.
            int lo = 0, hi = docLineCount - 1, best = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (mid - idx.hiddenAbove(mid) >= visualRow) {
                    best = mid;
                    hi = mid - 1;
                } else {
                    lo = mid + 1;
                }
            }
            if (best < 0) return Math.max(0, docLineCount - 1);
            // `best` peut elle-même être cachée (visibleIndex n'augmente pas
            // sur les lignes cachées) — sauter en avant vers la prochaine
            // ligne VISIBLE.
            while (best < docLineCount && idx.isHidden(best)) best++;
            if (best >= docLineCount) return Math.max(0, docLineCount - 1);
            return best;
        }
        return view.wrapModel.docLineForRow(visualRow);
    }

    /**
     * Retourne la colonne dans la ligne doc donnée pour un offset de
     * rangée repliée. Conscient des rangées de continuation (elles
     * démarrent à {@code maxColsPerRow + (r-1)*colsPerCont}, et non à
     * {@code rowInLine * maxColsPerRow} uniforme).
     */
    int wrappedColFor(int docLine, int rowInLine, int colInRow) {
        if (!view.wordWrap || view.wrapModel == null) return colInRow;
        EditorDocument doc = view.session.getDocument();
        int lineLen = doc.lineEnd(docLine) - doc.lineStart(docLine);
        EditorView.WrapRows wr = wrapRowsFor(docLine, lineLen);
        return wr.rowStartCol(rowInLine) + colInRow;
    }

    /** Reconstruit le modèle de wrap depuis le document courant + la largeur du viewport. */
    void rebuildWrapModel() {
        if (view.session == null) return;
        int lineCount = view.session.getDocument().lineCount();
        if (view.wrapModel == null || view.wrapModel.getLineCount() != lineCount) {
            view.wrapModel = new jo.codeeditor.wrap.WrapModel(lineCount);
        }
        // Utilise la largeur de texte effective (réduite quand l'aperçu
        // est actif).
        int textAreaW = view.getEffectiveTextWidth();
        view.wrapWidthPx = Math.max(1, textAreaW);
        // Le comptage passe par wrapRowsFor — même formule que le
        // DÉCOUPAGE du rendu (continuation indentée = rangée plus
        // étroite). Un simple ceil(len/maxCols) divergerait du découpage :
        // les lignes indentées repliées perdraient leur queue (rangées non
        // dessinées) et les Y des lignes suivantes seraient trop courts
        // d'autant.
        EditorDocument doc = view.session.getDocument();
        for (int i = 0; i < lineCount; i++) {
            int lineLen = doc.lineEnd(i) - doc.lineStart(i);
            view.wrapModel.setRows(i, wrapRowsFor(i, lineLen).rows);
        }
    }
}
