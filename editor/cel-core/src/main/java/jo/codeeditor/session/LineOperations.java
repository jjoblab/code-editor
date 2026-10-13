package jo.codeeditor.session;

import jo.codeeditor.document.Selection;

/**
 * Opérations sur les LIGNES de la session (plan 01/02 — extrait
 * d'EditorSession pour tenir son plafond de lignes) : duplication,
 * suppression, jonction et <b>mouvements</b> (P2 lot 2 J6 — bloc
 * multi-lignes, sélection ré-épinglée).
 *
 * <p>Collaborateur statique package-privé : tout l'état vit dans la
 * session ({@code doc} / {@code selection} package-privés, remplacés
 * par {@code replaceRange} / {@code setSelection}) — aucune copie, le
 * décalage incrémental et l'undo restent ceux de la session. Les
 * méthodes publiques d'EditorSession sont de fins relais (zéro rupture
 * d'API).</p>
 *
 * @author jo@Dev
 */
final class LineOperations {

    private LineOperations() {
    }

    /** Duplique la sélection juste après elle-même (caret → sa ligne). */
    static void duplicateSelection(EditorSession s) {
        if (s.selection.isCursor()) {
            duplicateLine(s);
            return;
        }
        String selected = s.doc.subText(s.selection.start, s.selection.end);
        s.replaceRange(s.selection.end, s.selection.end, selected);
    }

    /** Duplique les lignes couvertes par la sélection (une étape d'undo). */
    static void duplicateLine(EditorSession s) {
        int startLine = s.doc.lineForOffset(s.selection.start);
        int endLine = s.doc.lineForOffset(s.selection.end);
        int insertPos = s.doc.lineEnd(endLine);
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        for (int i = startLine; i <= endLine; i++) {
            if (i > startLine) sb.append('\n');
            sb.append(s.doc.lineText(i));
        }
        s.replaceRange(insertPos, insertPos, sb.toString());
    }

    /** Mono-ligne historique vers le haut (la sélection ne suit pas). */
    static void moveLineUp(EditorSession s) {
        int line = s.doc.lineForOffset(s.selection.start);
        if (line <= 0) return;
        String currentLine = s.doc.lineText(line);
        String prevLine = s.doc.lineText(line - 1);
        int prevStart = s.doc.lineStart(line - 1);
        int currEnd = s.doc.lineEnd(line);
        s.replaceRange(prevStart, currEnd, currentLine + "\n" + prevLine);
    }

    /** Mono-ligne historique vers le bas (la sélection ne suit pas). */
    static void moveLineDown(EditorSession s) {
        int line = s.doc.lineForOffset(s.selection.start);
        if (line >= s.doc.lineCount() - 1) return;
        String currentLine = s.doc.lineText(line);
        String nextLine = s.doc.lineText(line + 1);
        int currStart = s.doc.lineStart(line);
        int nextEnd = s.doc.lineEnd(line + 1);
        s.replaceRange(currStart, nextEnd, nextLine + "\n" + currentLine);
    }

    /** Relais historique : mono-ligne selon la direction. */
    static void moveLines(EditorSession s, boolean down) {
        if (down) {
            moveLineDown(s);
        } else {
            moveLineUp(s);
        }
    }

    /** Supprime les lignes couvertes (le '\n' de tête ou de queue est
     *  emporté pour ne pas laisser de ligne vide). */
    static void deleteLines(EditorSession s) {
        int startLine = s.doc.lineForOffset(s.selection.start);
        int endLine = s.doc.lineForOffset(s.selection.end);
        int rangeStart = s.doc.lineStart(startLine);
        int rangeEnd = s.doc.lineEnd(endLine);
        if (rangeEnd < s.doc.length()) {
            rangeEnd++;
        } else if (rangeStart > 0) {
            rangeStart--;
        }
        s.replaceRange(rangeStart, rangeEnd, "");
    }

    /** Joint la ligne courante à la suivante (un espace, trim de tête). */
    static void joinLines(EditorSession s) {
        int line = s.doc.lineForOffset(s.selection.start);
        if (line >= s.doc.lineCount() - 1) return;
        int lineEnd = s.doc.lineEnd(line);
        int nextEnd = s.doc.lineEnd(line + 1);
        String nextLine = s.doc.lineText(line + 1);
        s.replaceRange(lineEnd, Math.min(nextEnd, s.doc.length()),
                " " + nextLine.trim());
    }

    /**
     * Déplace le BLOC de lignes couvert par la sélection d'une ligne
     * vers le haut ou le bas (P2 « mouvements de lignes », lot 2 J6).
     *
     * <p>Sémantique IntelliJ/VS Code — la décision multi-lignes retenue
     * au lot 2 : une sélection couvrant plusieurs lignes déplace le
     * BLOC ENTIER (les lignes de la première à la dernière borne),
     * une sélection réduite au caret déplace SA ligne. Une sélection
     * qui se termine EXACTEMENT au début de la ligne suivante ne
     * l'inclut PAS (sélection au caractère près, pas à la ligne près).
     * La sélection est RÉ-ÉPINGLÉE sur le bloc déplacé (offsets
     * conservés en relatif — l'aller-retour est stable), ce que
     * {@code moveLineUp}/{@code moveLineDown} (mono-ligne,
     * historiques) ne font pas. No-op aux bords du document (première
     * ligne en haut, dernière ligne RÉELLE en bas) ; UNE étape
     * d'undo.</p>
     *
     * @param s la session porteuse
     * @param down true → vers le bas, false → vers le haut
     * @return true si le document a changé
     */
    static boolean moveSelectedLines(EditorSession s, boolean down) {
        int startLine = s.doc.lineForOffset(s.selection.start);
        int endLine = s.doc.lineForOffset(s.selection.end);
        // Sélection finissant au DÉBUT de la ligne suivante : cette
        // ligne n'est pas « couverte » (le classique « sélection de la
        // fin de ligne sans le \n »).
        if (s.selection.end > s.selection.start
                && s.selection.end == s.doc.lineStart(endLine) && endLine > 0) {
            endLine--;
        }
        // La QUEUE VIDE (ligne finale après le dernier '\n' —
        // lineStart == length) n'est pas une cible de déplacement :
        // échanger un bloc avec elle insérerait une ligne blanche.
        // Dernière ligne RÉELLE (contenu ou ligne vide du document) :
        int lastLine = s.doc.lineCount() - 1;
        if (lastLine > 0 && s.doc.lineStart(lastLine) >= s.doc.length()) lastLine--;
        // Une sélection réduite à la queue vide n'a rien à déplacer.
        if (startLine == endLine && startLine > lastLine) return false;
        // Modèle « séparateur conservé » (celui du moveLineDown
        // historique) : la plage remplacée EXCLUT le '\n' final de la
        // dernière ligne impliquée, et le remplacement le resynthétise
        // ENTRE les contenus échangés — les unités de ligne échangent
        // leurs CONTENUS, les séparateurs restent en place. Rien à
        // réparer pour un document sans '\n' final.
        int oldStart = s.selection.start;
        int oldEnd = s.selection.end;
        if (down) {
            if (endLine >= lastLine) return false;
            int blockStart = s.doc.lineStart(startLine);
            int swapEnd = s.doc.lineEnd(endLine + 1);
            String nextLine = s.doc.lineText(endLine + 1);
            int delta = nextLine.length() + 1;
            s.replaceRange(blockStart, swapEnd,
                    nextLine + "\n" + blockText(s, startLine, endLine));
            repositionSelection(s, oldStart, oldEnd, delta);
            return true;
        }
        if (startLine <= 0) return false;
        int prevStart = s.doc.lineStart(startLine - 1);
        int blockEnd = s.doc.lineEnd(endLine);
        String prevLine = s.doc.lineText(startLine - 1);
        int delta = -(prevLine.length() + 1);
        s.replaceRange(prevStart, blockEnd,
                blockText(s, startLine, endLine) + "\n" + prevLine);
        repositionSelection(s, oldStart, oldEnd, delta);
        return true;
    }

    /**
     * Déplace la plage de caractères [start, end) à l'offset
     * {@code target} — B13 (P1-2 « drag-déposer de bloc ») : la
     * suppression + la réinsertion forment UNE seule étape
     * d'annulation (lot d'éditions), et la sélection est
     * RÉ-ÉPINGLÉE sur le texte déplacé (l'aller-retour undo/redo et
     * le re-drag restent stables).
     *
     * <p>Sémantique des bornes (parité TextView drag &amp; drop) :</p>
     * <ul>
     *   <li>{@code target} est l'offset d'insertion AVANT suppression
     *       — un dépôt au-delà de la source ({@code target >= end})
     *       est corrigé de la longueur retirée ;</li>
     *   <li>un dépôt DANS la source ({@code start <= target <= end})
     *       est un no-op : le document serait identique — rien n'est
     *       enregistré sur la pile d'annulation (retourne false) ;</li>
     *   <li>lecture-seule / plage vide (caret) : no-op (false) ;</li>
     *   <li>les plis repliés chevauchant la zone éditée se déplient
     *       (comportement du point de mutation — le point de dépôt
     *       devient visible).</li>
     * </ul>
     *
     * @param s      la session porteuse
     * @param start  début de la plage déplacée (offset document)
     * @param end    fin de la plage déplacée (exclue)
     * @param target offset d'insertion AVANT suppression (offset document)
     * @return true si le document a changé
     */
    static boolean moveRange(EditorSession s, int start, int end, int target) {
        if (s.isReadOnly()) return false;
        int len = s.doc.length();
        if (start < 0) start = 0;
        if (end > len) end = len;
        if (target < 0) target = 0;
        if (target > len) target = len;
        if (start >= end) return false; // caret ou plage inversée : rien
        if (target >= start && target <= end) return false; // dépôt dans la source
        String text = s.doc.subText(start, end);
        s.beginBatch();
        try {
            s.replaceRange(start, end, "");
            int insertAt = target > end ? target - (end - start) : target;
            s.replaceRange(insertAt, insertAt, text);
            s.setSelection(Selection.range(insertAt,
                    Math.min(insertAt + text.length(), s.doc.length())));
        } finally {
            s.endBatch();
        }
        return true;
    }

    /** Contenu du bloc de lignes [start, end] joint par '\n' — SANS
     *  séparateur final (le remplacement le resynthétise). */
    private static String blockText(EditorSession s, int startLine, int endLine) {
        StringBuilder block = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            if (i > startLine) block.append('\n');
            block.append(s.doc.lineText(i));
        }
        return block.toString();
    }

    /** Ré-épingle la sélection sur le bloc déplacé (offsets relatifs
     *  conservés — l'aller-retour est stable). Les bornes ANCIENNES
     *  doivent être capturées AVANT replaceRange : l'édition replie la
     *  sélection sur son bord, on la repose ensuite en relatif. */
    private static void repositionSelection(EditorSession s,
            int oldStart, int oldEnd, int delta) {
        boolean cursor = oldStart == oldEnd;
        int start = Math.max(0, oldStart + delta);
        int end = Math.max(0, oldEnd + delta);
        if (start > s.doc.length()) start = s.doc.length();
        if (end > s.doc.length()) end = s.doc.length();
        if (start > end) start = end;
        s.setSelection(cursor ? Selection.cursor(start)
                : Selection.range(start, end));
    }
}
