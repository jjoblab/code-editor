package jo.codeeditor.session;

import jo.codeeditor.document.Selection;

/**
 * Déplacements du CURSEUR de la session (L1/D-01 — extrait
 * d'EditorSession pour tenir son plafond de lignes, plan 02) :
 * horizontal, vertical, début/fin de ligne intelligents, bords du
 * document. Sémantique inchangée, déplacée à l'identique.
 *
 * <p>Collaborateur statique package-privé sur le modèle de
 * {@code LineOperations} : tout l'état vit dans la session
 * ({@code doc} / {@code selection} package-privés, mutés par
 * {@code setSelection}) — aucune copie. Les méthodes publiques
 * d'EditorSession sont de fins relais (zéro rupture d'API).</p>
 */
final class CursorMoves {

    private CursorMoves() {
    }

    /** Déplacement horizontal de {@code delta} caractères (sélection optionnelle). */
    static void moveHorizontal(EditorSession s, int delta, boolean selecting) {
        int newPos = Math.max(0, Math.min(s.doc.length(), s.selection.start + delta));
        if (selecting) {
            int anchor = s.selection.isCursor() ? s.selection.start
                    : (delta > 0 ? s.selection.start : s.selection.end);
            s.setSelection(Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos)));
        } else {
            s.setSelection(Selection.cursor(newPos));
        }
    }

    /** Déplacement vertical de {@code lines} lignes (colonne conservée, clamp fin de ligne). */
    static void moveVertical(EditorSession s, int lines, boolean selecting) {
        int currentLine = s.doc.lineForOffset(s.selection.start);
        int currentCol = s.selection.start - s.doc.lineStart(currentLine);
        int targetLine = Math.max(0, Math.min(s.doc.lineCount() - 1, currentLine + lines));
        int targetLineStart = s.doc.lineStart(targetLine);
        int targetLineEnd = s.doc.lineEnd(targetLine);
        int newPos = Math.min(targetLineStart + currentCol, targetLineEnd);

        if (selecting) {
            int anchor = s.selection.start;
            s.setSelection(Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos)));
        } else {
            s.setSelection(Selection.cursor(newPos));
        }
    }

    /**
     * Début de ligne intelligent : au premier non-blanc, ou au début de
     * ligne si on y est déjà.
     */
    static void moveLineStart(EditorSession s, boolean selecting) {
        int line = s.doc.lineForOffset(s.selection.start);
        int lineStart = s.doc.lineStart(line);
        String lineText = s.doc.lineText(line);
        int firstNonWs = lineStart;
        for (int i = 0; i < lineText.length(); i++) {
            if (!Character.isWhitespace(lineText.charAt(i))) {
                firstNonWs = lineStart + i;
                break;
            }
        }
        int newPos = (s.selection.start == firstNonWs) ? lineStart : firstNonWs;

        if (selecting) {
            int anchor = s.selection.start;
            s.setSelection(Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos)));
        } else {
            s.setSelection(Selection.cursor(newPos));
        }
    }

    /** Fin de ligne logique. */
    static void moveLineEnd(EditorSession s, boolean selecting) {
        int line = s.doc.lineForOffset(s.selection.start);
        int newPos = s.doc.lineEnd(line);
        if (selecting) {
            int anchor = s.selection.start;
            s.setSelection(Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos)));
        } else {
            s.setSelection(Selection.cursor(newPos));
        }
    }

    /** Bord du document (début ou fin). */
    static void moveDocBoundary(EditorSession s, boolean toEnd, boolean selecting) {
        int newPos = toEnd ? s.doc.length() : 0;
        if (selecting) {
            int anchor = s.selection.start;
            s.setSelection(Selection.range(Math.min(anchor, newPos), Math.max(anchor, newPos)));
        } else {
            s.setSelection(Selection.cursor(newPos));
        }
    }
}
