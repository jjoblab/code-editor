package jo.codeeditor.session;

import jo.codeeditor.document.Selection;
import jo.codeeditor.edit.CommentSyntax;

/**
 * Bascule des commentaires d'une session, pilotée par la syntaxe du langage
 * courant (préfixe de ligne famille C, encadrement par paire de bloc pour
 * XML/HTML/Markdown, no-op documenté pour JSON). Existe pour isoler de
 * {@link EditorSession} cette responsabilité d'édition multi-lignes sans
 * en changer le comportement ; détient la surcharge explicite de syntaxe.
 */
final class CommentToggler {

    private final EditorSession session;

    /**
     * Surcharge de syntaxe de commentaire pour les hôtes éditant des langages
     * que {@link CommentSyntax#forLanguage(String)} ne connaît pas.
     * Quand null (le défaut), la syntaxe est dérivée du langage de la
     * session.
     */
    private volatile CommentSyntax commentSyntaxOverride;

    CommentToggler(EditorSession session) {
        this.session = session;
    }

    /**
     * La syntaxe de commentaire effective. La surcharge explicite gagne,
     * sinon la syntaxe est résolue depuis l'identifiant de langage courant
     * via {@link CommentSyntax#forLanguage(String)} (défaut Java/famille C
     * préservé à l'octet près).
     */
    CommentSyntax commentSyntax() {
        CommentSyntax override = commentSyntaxOverride;
        if (override != null) return override;
        return CommentSyntax.forLanguage(session.language);
    }

    /** Installe (ou retire avec null) la surcharge de syntaxe de commentaire. */
    void setCommentSyntaxOverride(CommentSyntax syntax) {
        this.commentSyntaxOverride = syntax;
    }

    /**
     * Calcule la plage de lignes [startLine, endLine] sur laquelle la bascule
     * de commentaire s'applique, depuis la sélection courante. Une fin de
     * sélection tombant exactement à un début de ligne N'inclut PAS cette
     * ligne encore vide. Retourne null quand la plage est vide.
     */
    private int[] commentLineRange() {
        int startLine = session.doc.lineForOffset(session.selection.start);
        int endLine = session.doc.lineForOffset(session.selection.end);
        // Si la fin de sélection tombe exactement à un début de ligne, ne pas
        // inclure cette ligne (encore vide) dans la plage — c'est le début de
        // la ligne suivante, pas la fin de l'actuelle.
        if (session.selection.end != session.selection.start
            && session.selection.end == session.doc.lineStart(endLine)
            && endLine > startLine) {
            endLine--;
        }
        if (endLine < startLine) return null;
        return new int[]{startLine, endLine};
    }

    /**
     * Bascule les commentaires de ligne sur la sélection, avec la syntaxe de
     * commentaire du langage COURANT (voir {@link #commentSyntax()}) :
     * préfixe de ligne quand disponible, encadrement par paire de bloc sinon
     * (comportement VS Code pour XML/HTML/Markdown), no-op documenté pour
     * les langages sans aucune syntaxe de commentaire (JSON).
     */
    void toggleLineComment() {
        CommentSyntax syntax = commentSyntax();
        if (syntax.hasLine()) {
            int[] range = commentLineRange();
            if (range != null) toggleLineCommentWithPrefix(syntax.lineComment, range[0], range[1]);
        } else if (syntax.hasBlock()) {
            int[] range = commentLineRange();
            if (range != null) toggleLineCommentWithBlockPair(syntax.blockStart, syntax.blockEnd, range[0], range[1]);
        }
        // Ni commentaire de ligne ni de bloc (JSON) : no-op documenté.
    }

    /**
     * Comportement famille C, paramétré par le préfixe de commentaire.
     * Une ligne est considérée « commentée » seulement si elle a un contenu
     * non blanc commençant par le préfixe. Les lignes purement vides sont
     * ignorées pour décider d'ajouter ou retirer les commentaires, mais
     * reçoivent quand même le préfixe en commentant (pour garder le bloc
     * cohérent).
     */
    private void toggleLineCommentWithPrefix(String commentPrefix, int startLine, int endLine) {
        boolean allCommented = true;
        for (int i = startLine; i <= endLine; i++) {
            String trimmed = session.doc.lineText(i).trim();
            if (trimmed.isEmpty()) continue;            // ignorer les lignes vides
            if (!trimmed.startsWith(commentPrefix)) {
                allCommented = false;
                break;
            }
        }
        // Si toutes les lignes non vides étaient commentées, on décommente.
        // Cas limite : s'il n'y a aucune ligne non vide, commenter par défaut.
        boolean hasNonBlank = false;
        for (int i = startLine; i <= endLine; i++) {
            if (!session.doc.lineText(i).trim().isEmpty()) { hasNonBlank = true; break; }
        }
        if (!hasNonBlank) allCommented = false;

        int prefixLen = commentPrefix.length();
        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            String line = session.doc.lineText(i);
            if (i > startLine) sb.append('\n');
            if (allCommented) {
                int idx = line.indexOf(commentPrefix);
                if (idx >= 0) {
                    if (idx + prefixLen < line.length() && line.charAt(idx + prefixLen) == ' ') {
                        sb.append(line, 0, idx).append(line.substring(idx + prefixLen + 1));
                    } else {
                        sb.append(line, 0, idx).append(line.substring(idx + prefixLen));
                    }
                } else {
                    sb.append(line);
                }
            } else {
                sb.append(commentPrefix).append(' ').append(line);
            }
        }
        int rangeStart = session.doc.lineStart(startLine);
        int rangeEnd = session.doc.lineEnd(endLine);
        session.replaceRange(rangeStart, rangeEnd, sb.toString());
        // Restaurer une sélection couvrant TOUT le bloc transformé.
        // replaceRange() réduit la sélection à un caret, ce qui rendait
        // l'aller-retour multi-lignes impossible — la seconde bascule ne
        // voyait que la ligne du caret et la décommentait seule. Épingler la
        // sélection sur la plage remplacée laisse la prochaine bascule voir
        // toutes les lignes à nouveau.
        int selEnd = Math.min(rangeStart + sb.length(), session.doc.length());
        session.setSelection(Selection.range(rangeStart, Math.max(rangeStart, selEnd)));
    }

    /**
     * Repli façon VS Code pour les syntaxes sans commentaire de ligne (XML,
     * HTML, Markdown) : chaque ligne est encadrée par
     * {@code blockStart … blockEnd}. Le décommentage retire la paire plus
     * les espaces (optionnelles) collées à elle.
     */
    private void toggleLineCommentWithBlockPair(String blockStart, String blockEnd, int startLine, int endLine) {
        boolean allCommented = true;
        for (int i = startLine; i <= endLine; i++) {
            String trimmed = session.doc.lineText(i).trim();
            if (trimmed.isEmpty()) continue;            // ignorer les lignes vides
            if (!(trimmed.startsWith(blockStart) && trimmed.endsWith(blockEnd))) {
                allCommented = false;
                break;
            }
        }
        boolean hasNonBlank = false;
        for (int i = startLine; i <= endLine; i++) {
            if (!session.doc.lineText(i).trim().isEmpty()) { hasNonBlank = true; break; }
        }
        if (!hasNonBlank) allCommented = false;

        StringBuilder sb = new StringBuilder();
        for (int i = startLine; i <= endLine; i++) {
            String line = session.doc.lineText(i);
            if (i > startLine) sb.append('\n');
            if (allCommented) {
                // Retirer le fermeur (côté droit) EN PREMIER pour que sa
                // suppression ne puisse pas décaler l'index de l'ouvreur
                // (l'ouvreur est toujours à sa gauche).
                String cur = line;
                int endIdx = cur.lastIndexOf(blockEnd);
                if (endIdx >= 0) {
                    int cutEnd = endIdx;
                    if (cutEnd > 0 && cur.charAt(cutEnd - 1) == ' ') cutEnd--;
                    cur = cur.substring(0, cutEnd) + cur.substring(endIdx + blockEnd.length());
                }
                int startIdx = cur.indexOf(blockStart);
                if (startIdx >= 0) {
                    int after = startIdx + blockStart.length();
                    if (after < cur.length() && cur.charAt(after) == ' ') after++;
                    cur = cur.substring(0, startIdx) + cur.substring(after);
                }
                sb.append(cur);
            } else if (line.trim().isEmpty()) {
                // Ligne vide : rester minimal — juste la paire, sans espaces internes.
                sb.append(blockStart).append(blockEnd);
            } else {
                sb.append(blockStart).append(' ').append(line).append(' ').append(blockEnd);
            }
        }
        int rangeStart = session.doc.lineStart(startLine);
        int rangeEnd = session.doc.lineEnd(endLine);
        session.replaceRange(rangeStart, rangeEnd, sb.toString());
        // Même correctif d'aller-retour multi-lignes que la variante préfixe —
        // garder la sélection sur tout le bloc transformé.
        int selEnd = Math.min(rangeStart + sb.length(), session.doc.length());
        session.setSelection(Selection.range(rangeStart, Math.max(rangeStart, selEnd)));
    }

    /**
     * Bascule un commentaire de bloc autour de la sélection, avec la paire
     * de bloc du langage COURANT.
     *
     * <p>La paire vient de {@link #commentSyntax()} (XML obtient
     * {@code <!-- … -->}, Lua {@code --[[ … ]]}…). Les langages sans
     * commentaire de bloc (Python, JSON, shell…) sont un no-op documenté.</p>
     */
    void toggleBlockComment() {
        CommentSyntax syntax = commentSyntax();
        if (!syntax.hasBlock()) return;
        String blockStart = syntax.blockStart;
        String blockEnd = syntax.blockEnd;
        int start = session.selection.start;
        int end = session.selection.end;
        String text = session.doc.getText();

        // Vérifier si la sélection (ou la zone l'entourant immédiatement) est
        // déjà enveloppée dans blockStart … blockEnd. On regarde les caractères
        // juste AVANT start et juste À end pour que le test fonctionne même
        // quand start == 0 ou end == len.
        boolean alreadyWrapped =
            start >= blockStart.length()
            && end + blockEnd.length() <= text.length()
            && text.regionMatches(start - blockStart.length(), blockStart, 0, blockStart.length())
            && text.regionMatches(end, blockEnd, 0, blockEnd.length());

        // Grouper les éditions (éventuellement deux) en une seule étape d'annulation.
        session.beginBatch();
        try {
            if (alreadyWrapped) {
                // Retirer le fermeur (côté droit) d'abord, puis l'ouvreur (à
                // gauche), pour que l'offset côté gauche reste valide.
                // Une ESPACE optionnelle collée à un délimiteur est retirée
                // avec lui — l'enveloppement insère la paire avec des espaces
                // de lisibilité.
                int closerEnd = end + blockEnd.length();
                int closerStart = end;
                if (closerStart > start && text.charAt(closerStart - 1) == ' ') {
                    closerStart--;
                }
                int openerStart = start - blockStart.length();
                int openerEnd = start;
                if (openerEnd < closerStart && text.charAt(openerEnd) == ' ') {
                    openerEnd++;
                }
                int innerLen = closerStart - openerEnd;
                session.replaceRange(closerStart, closerEnd, "");
                session.replaceRange(openerStart, openerEnd, "");
                // Restaurer la sélection sur le contenu décommenté.
                int selEnd = Math.min(openerStart + Math.max(0, innerLen), session.doc.length());
                session.setSelection(Selection.range(Math.max(0, openerStart), Math.max(0, selEnd)));
            } else {
                // Insérer le fermeur à droite d'abord, puis l'ouvreur à gauche.
                session.replaceRange(end, end, " " + blockEnd);
                session.replaceRange(start, start, blockStart + " ");
                // Épingler la sélection juste APRÈS l'ouvreur et juste
                // AVANT le fermeur (+ les deux espaces insérées) pour que la
                // prochaine bascule détecte la paire et dé-envelope exactement
                // ce bloc — replaceRange() avait réduit la sélection à un
                // caret, rendant les aller-retours commentaire→décommentage
                // impossibles.
                int selStart = Math.min(start + blockStart.length(), session.doc.length());
                int selEnd2 = Math.min(end + blockStart.length() + 2, session.doc.length());
                session.setSelection(Selection.range(selStart, Math.max(selStart, selEnd2)));
            }
        } finally {
            session.endBatch();
        }
    }
}
