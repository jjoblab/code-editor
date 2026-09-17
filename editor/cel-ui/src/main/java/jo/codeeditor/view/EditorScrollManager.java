package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.session.EditorSession;

/**
 * Gestionnaire des mécaniques de défilement d'EditorView : recentrage du
 * caret dans la vue, défilement par ligne/offset et bornes maximales de
 * défilement.
 *
 * <p>EditorView délègue à cette classe {@code scrollCaretIntoView},
 * {@code scrollToLine}, {@code scrollToOffset}, {@code scrollBy},
 * {@code scrollHorizontallyBy}, {@code maxV} et {@code maxH}. L'état du
 * défilement ({@code vOffset}, {@code hOffset}) reste dans EditorView
 * (package-privé) et est modifié directement par ce gestionnaire.
 */
class EditorScrollManager {

    private final EditorView view;

    EditorScrollManager(EditorView view) {
        this.view = view;
    }

    /**
     * Ramène le caret dans la vue après chaque édition ou déplacement du
     * caret. Sensible au retour à la ligne automatique : le Y du caret est
     * calculé depuis le modèle de repli, afin qu'un caret sur la 3e rangée
     * repliée d'une longue ligne défile correctement.
     */
    void scrollCaretIntoView() {
        if (view.session == null || view.getWidth() == 0 || view.getHeight() == 0) return;
        EditorDocument doc = view.session.getDocument();
        int caret = view.session.getSelection().start;
        int line = EditorView.clamp(doc.lineForOffset(caret), 0, doc.lineCount() - 1);
        int col = caret - doc.lineStart(line);
        float lineHeight = view.metrics.getLineHeight();
        float charWidth = view.metrics.getCharWidth();
        float textLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        // Vertical : haut de la ligne du document, plus les rangées
        // supplémentaires si le caret est sur une rangée repliée.
        float caretY = view.docLineToY(line);
        if (view.wordWrap && view.wrapModel != null) {
            // ★ Rangée obtenue via wrapRowsFor (les rangées de continuation
            // sont plus étroites : l'ancien calcul col/maxColsPerRow était décalé).
            EditorDocument doc2 = view.session.getDocument();
            int lineLen = doc2.lineEnd(line) - doc2.lineStart(line);
            EditorView.WrapRows wr = view.wrapRowsFor(line, lineLen);
            caretY += wr.rowForCol(col) * lineHeight;
        }
        float viewH = view.getHeight();
        // Vertical — ne défile que si le caret est réellement hors du viewport.
        if (caretY < view.vOffset) {
            view.vOffset = Math.max(0, caretY - lineHeight);
        } else if (caretY + lineHeight > view.vOffset + viewH) {
            view.vOffset = caretY + lineHeight - viewH;
        }
        view.vOffset = EditorView.clamp(view.vOffset, 0, maxV());

        // Horizontal — ignoré en mode repli automatique (chaque rangée est
        // entièrement visible). On utilise caretScreenPos() pour un X de caret
        // sensible aux replis : si le caret est sur une ligne de début ou de
        // fin de repli, le X visible peut différer de col*charWidth.
        if (!view.wordWrap) {
            float[] screenPos = view.caretScreenPos(caret);
            float caretScreenX = screenPos[0];
            float viewW = view.getWidth() - textLeft - view.metrics.getPadRight();
            float margin = charWidth * 3;
            if (caretScreenX < textLeft + margin) {
                float visibleCol = (caretScreenX + view.hOffset - textLeft) / charWidth;
                view.hOffset = Math.max(0, visibleCol * charWidth - margin);
            } else if (caretScreenX > textLeft + viewW - margin) {
                float visibleCol = (caretScreenX + view.hOffset - textLeft) / charWidth;
                view.hOffset = visibleCol * charWidth - viewW + margin;
            }
            view.hOffset = EditorView.clamp(view.hOffset, 0, maxH());
        }

        view.invalidate();
    }

    void scrollToLine(int line) {
        float lineHeight = view.metrics.getLineHeight();
        float y = view.docLineToY(line);
        float viewHeight = view.getHeight();
        if (y < view.vOffset) {
            view.vOffset = y;
        } else if (y + lineHeight > view.vOffset + viewHeight) {
            view.vOffset = y + lineHeight - viewHeight;
        }
        view.vOffset = EditorView.clamp(view.vOffset, 0, maxV());
        // ★ Notifie le sticky-bottom des consoles.
        view.notifyScrollPositionChanged();
        view.invalidate();
    }

    void scrollToOffset(int offset) {
        if (view.session == null) return;
        EditorDocument doc = view.session.getDocument();
        int line = EditorView.clamp(doc.lineForOffset(offset), 0, doc.lineCount() - 1);
        view.session.expandFoldAt(offset);
        scrollToLine(line);
    }

    void scrollBy(float dy) {
        float newV = EditorView.clamp(view.vOffset + dy, 0, maxV());
        if (newV != view.vOffset) {
            view.vOffset = newV;
            // ★ Notifie le sticky-bottom des consoles.
            view.notifyScrollPositionChanged();
            view.invalidate();
        }
    }

    void scrollHorizontallyBy(float dx) {
        float newH = EditorView.clamp(view.hOffset + dx, 0, maxH());
        if (newH != view.hOffset) {
            view.hOffset = newH;
            view.invalidate();
        }
    }

    /**
     * Défilement vertical maximal : hauteur du contenu moins hauteur du
     * viewport, à 0 au minimum. En mode repli automatique, la hauteur du
     * contenu inclut les rangées supplémentaires de chaque ligne repliée.
     */
    float maxV() {
        if (view.session == null) return 0;
        float contentH;
        if (view.wordWrap && view.wrapModel != null) {
            contentH = view.metrics.getPadTop()
                + view.wrapModel.totalRows() * view.metrics.getLineHeight()
                + view.metrics.getPadBottom();
        } else {
            contentH = view.metrics.getPadTop()
                + view.session.getDocument().lineCount() * view.metrics.getLineHeight()
                + view.metrics.getPadBottom();
        }
        return Math.max(0, contentH - view.getHeight());
    }

    /**
     * Défilement horizontal maximal : largeur de la ligne la plus longue
     * moins largeur de la zone de texte, à 0 au minimum. Renvoie 0 quand
     * le repli automatique est actif (pas de défilement horizontal dans ce
     * mode).
     *
     * <p>★ L'étendue tient compte des inlay hints tissés au-delà de la fin
     * de ligne (longueur VISUELLE) ET du débordement des chips diagnostics
     * ({@code chipExtentContentX}, mesuré au draw pass — pattern
     * {@code contentWidth()} de CodeAssist EditorGeometry) : un hint/chip
     * qui dépasse la ligne la plus longue est ATTEIGNABLE au défilement
     * horizontal.</p>
     *
     * <p>Nombre maximal de colonnes mémoïsé : maxH() balaierait sinon
     * CHAQUE ligne du document plus les apports des inlays à CHAQUE appel
     * — or il est appelé plusieurs fois par frame (borne de défilement
     * dans scrollBy / scrollHorizontallyBy / fling / recentrage du caret).
     * Le balayage ne s'exécute qu'UNE FOIS par triplet
     * {@code (session, révision du document, révision des inlays)} :</p>
     *
     * <ul>
     *   <li>{@link EditorDocument} est IMMUABLE et remplacé à chaque
     *       édition, donc la seule référence du document est une clé
     *       d'édition fiable — un max réellement incrémental (maintenu
     *       à travers les éditions) exigerait de suivre QUELLE ligne
     *       était la plus longue, fragile face aux décalages des inlays,
     *       pour un gain non mesurable (un balayage O(lignes) par frappe
     *       au lieu de plusieurs par frame) ;</li>
     *   <li>{@code setInlayHints} incrémente la révision des inlays de la
     *       session, et une édition décale les hints tout en remplaçant le
     *       document — les deux chemins invalident le mémo.</li>
     * </ul>
     *
     * <p>La conversion en pixels (charWidth) et l'étendue des chips
     * ({@code chipExtentContentX}, mesurée au draw) restent volontairement
     * HORS du mémo : la valeur cachée est en COLONNES, les changements de
     * taille de police n'ont donc pas besoin de l'invalider.</p>
     */
    private EditorDocument maxColsDoc;
    private EditorSession maxColsSession;
    private int maxColsInlayRev = Integer.MIN_VALUE;
    private int cachedMaxCols = 0;

    float maxH() {
        if (view.session == null) return 0;
        if (view.wordWrap) return 0;
        EditorDocument doc = view.session.getDocument();
        if (doc == null) return 0;
        if (doc != maxColsDoc || view.session != maxColsSession
                || view.session.getInlayHintsRevision() != maxColsInlayRev) {
            cachedMaxCols = computeMaxCols(doc);
            maxColsDoc = doc;
            maxColsSession = view.session;
            maxColsInlayRev = view.session.getInlayHintsRevision();
        }
        int maxCols = cachedMaxCols;
        float contentW = view.metrics.getPadLeft()
                + (maxCols + 1) * view.metrics.getCharWidth();
        // ★ Chips : le débordement le plus à droite étend la largeur
        // scrollable (mesuré au frame précédent — rafraîchi à chaque draw).
        if (view.chipExtentContentX > contentW) {
            contentW = view.chipExtentContentX;
        }
        contentW += view.metrics.getPadRight();
        float textAreaW = view.getWidth() - view.metrics.getGutterWidth();
        return Math.max(0, contentW - textAreaW);
    }

    /**
     * Le balayage réel en O(lignes + hints) — exécuté une fois par révision
     * document/inlays au lieu d'une fois par appel à maxH().
     */
    private int computeMaxCols(EditorDocument doc) {
        int maxCols = 0;
        for (int i = 0; i < doc.lineCount(); i++) {
            int len = doc.lineEnd(i) - doc.lineStart(i);
            if (len > maxCols) maxCols = len;
        }
        // ★ Inlays : la longueur VISUELLE d'une ligne = len + Σ(longueur des
        // hints de la ligne) — O(hints), sans lookup de cache par ligne.
        java.util.List<jo.codeeditor.shift.DiagnosticShift.InlayHint> hints =
                view.session.getInlayHints();
        if (!hints.isEmpty()) {
            java.util.HashMap<Integer, Integer> extra = new java.util.HashMap<>();
            for (jo.codeeditor.shift.DiagnosticShift.InlayHint h : hints) {
                if (h.text == null) continue;
                int ln = doc.lineForOffset(Math.max(0,
                        Math.min(h.offset, doc.length())));
                extra.merge(ln, h.text.length(), Integer::sum);
            }
            for (int ln : extra.keySet()) {
                int visual = doc.lineEnd(ln) - doc.lineStart(ln) + extra.get(ln);
                if (visual > maxCols) maxCols = visual;
            }
        }
        return maxCols;
    }
}
