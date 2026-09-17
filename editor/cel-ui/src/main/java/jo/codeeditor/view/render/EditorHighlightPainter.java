package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Paint;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.find.Match;

/**
 * Couches de surlignage et curseur : bande de sélection,
 * occurrences de recherche et de symbole LSP, encadrés de
 * parenthèses appariées, guides d'indentation, curseur
 * clignotant/glissant (via CaretAnimator) et poignées de
 * sélection mobiles.
 *
 * <p>Extrait d'EditorRenderer par composition — isole les couches
 * dessinées autour du texte du reste du pipeline (vers un futur
 * sous-package render/).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorHighlightPainter {
    private final EditorView view;

    EditorHighlightPainter(EditorView view) {
        this.view = view;
    }

    // Guides d'indentation
    static final int INDENT_UNIT_COLS = 4;

    // Clignotement du curseur (plein après édition + période de clignotement)
    static final long CARET_BLINK_MS = 530;

    static final long CARET_SOLID_AFTER_EDIT_MS = 530;

    // ════════════════════════════════════════════════════════════════
    // Surlignages de recherche
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine chaque occurrence de recherche du viewport en bande discrète,
     * et l'occurrence courante en bande plus forte. Dessiné AVANT le texte
     * pour que les couleurs de syntaxe soient au-dessus.
     */
    void drawFindHighlights(Canvas canvas, EditorDocument doc,
                             float textAreaLeft, float lineHeight, float paddingTop,
                             int firstVisible, int lastVisible) {
        float charWidth = view.metrics.getCharWidth();
        for (int i = 0; i < view.findHighlights.size(); i++) {
            Match m = view.findHighlights.get(i);
            int line = doc.lineForOffset(m.start);
            if (line < firstVisible || line > lastVisible) continue;
            // Sauter les surlignages de recherche des lignes cachées (repliées).
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int startCol = EditorView.clamp(m.start - lineStart, 0, doc.lineEnd(line) - lineStart);
            int endCol = EditorView.clamp(m.end - lineStart, 0, doc.lineEnd(line) - lineStart);
            if (endCol <= startCol) endCol = startCol + 1;
            float y = view.docLineToY(line) - view.vOffset; // Y sensible aux plis
            float x1 = textAreaLeft + view.visualColFor(line, startCol) * charWidth - view.hOffset;
            float x2 = textAreaLeft + view.visualColFor(line, endCol) * charWidth - view.hOffset;
            view.selPaint.setColor(i == view.findCurrentIndex ? view.theme.findCurrent : view.theme.findMatch);
            canvas.drawRect(x1, y, x2, y + lineHeight, view.selPaint);
        }
    }

    /**
     * Dessine les plages de surlignage de document (occurrences LSP du
     * symbole sous le curseur) en rectangles à teinte douce derrière le
     * texte. Même style que les surlignages de recherche mais avec
     * {@code theme.annotation} (gris-vert délavé) pour s'en distinguer
     * visuellement.
     *
     * <p>Dessiné APRÈS les surlignages de recherche pour éviter la
     * concurrence visuelle — les occurrences de recherche (action de
     * l'utilisateur) priment sur les occurrences LSP (passives).</p>
     */
    void drawDocumentHighlights(Canvas canvas, EditorDocument doc,
                                float textAreaLeft, float lineHeight, float paddingTop,
                                int firstVisible, int lastVisible) {
        float charWidth = view.metrics.getCharWidth();
        // Utiliser la couleur de thème dédiée « occurrence » — distincte de
        // findMatch pour que l'utilisateur distingue les occurrences LSP
        // (passives) des occurrences de recherche explicites (actives).
        view.selPaint.setColor(view.theme.occurrence);
        view.selPaint.setAlpha(100);
        for (int[] range : view.documentHighlights) {
            if (range == null || range.length < 2) continue;
            int start = range[0];
            int end = range[1];
            if (end <= start) end = start + 1;
            int line = doc.lineForOffset(start);
            if (line < firstVisible || line > lastVisible) continue;
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int startCol = EditorView.clamp(start - lineStart, 0, doc.lineEnd(line) - lineStart);
            int endCol = EditorView.clamp(end - lineStart, 0, doc.lineEnd(line) - lineStart);
            if (endCol <= startCol) endCol = startCol + 1;
            float y = view.docLineToY(line) - view.vOffset;
            float x1 = textAreaLeft + view.visualColFor(line, startCol) * charWidth - view.hOffset;
            float x2 = textAreaLeft + view.visualColFor(line, endCol) * charWidth - view.hOffset;
            canvas.drawRect(x1, y, x2, y + lineHeight, view.selPaint);
        }
        view.selPaint.setAlpha(255);
    }

    /**
     * Dessine les encadrés de parenthèses appariées. Un rectangle de
     * contour 1 px encadre ET la parenthèse ouvrante ET la fermante de la
     * paire adjacente au curseur, dans la couleur du curseur à 45 %
     * d'alpha.
     *
     * <p>Le positionnement est sensible aux inlays
     * ({@link EditorView#visualColFor} — un inlay hint tissé avant la
     * parenthèse décale l'encadré vers la droite, exactement là où le
     * glyphe se rend réellement) et aux plis (une parenthèse dans une
     * région réduite est sautée ; la ligne composite montre la colonne
     * brute).
     */
    void drawBracketMatchBoxes(Canvas canvas, EditorDocument doc,
                               float textAreaLeft, float lineHeight,
                               int firstVisible, int lastVisible) {
        if (view.bracketPair == null) return;
        float charWidth = view.metrics.getCharWidth();
        for (int off : view.bracketPair) {
            if (off < 0 || off >= doc.length()) continue;
            int line = doc.lineForOffset(off);
            if (line < firstVisible || line > lastVisible) continue;
            // Sauter les parenthèses cachées par un pli réduit.
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int col = EditorView.clamp(off - lineStart, 0, doc.lineEnd(line) - lineStart);
            float y = view.docLineToY(line) - view.vOffset;
            float x1 = textAreaLeft + view.visualColFor(line, col) * charWidth - view.hOffset;
            float x2 = x1 + charWidth;
            // Sauter quand entièrement défilée hors de la plage de colonnes visibles.
            if (x2 <= view.metrics.getGutterWidth() || x1 >= canvas.getWidth()) continue;
            view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
            view.selPaint.setStrokeWidth(1f);
            view.selPaint.setColor(view.applyAlphaToColor(view.theme.caret, 0.45f));
            canvas.drawRect(x1, y, x2, y + lineHeight, view.selPaint);
            view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
            view.selPaint.setStrokeWidth(0f);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Surlignage de sélection
    // ════════════════════════════════════════════════════════════════

    void drawSelection(Canvas canvas, EditorDocument doc, Selection sel,
                        float textAreaLeft, float lineHeight, float paddingTop,
                        int firstVisible, int lastVisible) {
        view.selPaint.setColor(view.theme.selection);
        int selStartLine = EditorView.clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        int selEndLine = EditorView.clamp(doc.lineForOffset(Math.max(0, sel.end - 1)), 0, doc.lineCount() - 1);
        float charWidth = view.metrics.getCharWidth();
        for (int i = Math.max(selStartLine, firstVisible); i <= Math.min(selEndLine, lastVisible); i++) {
            // Sauter les lignes cachées (repliées) — pas de bande de sélection dans le trou.
            if (view.isLineFoldedCached(i)) continue;
            float y = view.docLineToY(i) - view.vOffset; // Y sensible aux plis
            int lineStart = doc.lineStart(i);
            int lineEnd = doc.lineEnd(i);
            int colStart = (i == selStartLine) ? sel.start - lineStart : 0;
            int colEnd = (i == selEndLine) ? sel.end - lineStart : lineEnd - lineStart;
            colStart = EditorView.clamp(colStart, 0, lineEnd - lineStart);
            colEnd = EditorView.clamp(colEnd, 0, lineEnd - lineStart);
            // Sensible aux inlays — mapper les colonnes brutes vers les
            // colonnes visuelles tissées pour que la bande de sélection
            // suive le texte décalé.
            float x1 = textAreaLeft + view.visualColFor(i, colStart) * charWidth - view.hOffset;
            float x2 = textAreaLeft + view.visualColFor(i, colEnd) * charWidth - view.hOffset;
            // Marqueur de traîne après la fin de ligne pour qu'une ligne
            // sélectionnée vide montre quand même une bande.
            float trailing = (colEnd == colStart) ? charWidth * 0.6f : 0;
            canvas.drawRect(x1, y, Math.max(x1 + 1, x2 + trailing), y + lineHeight, view.selPaint);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Curseur
    // ════════════════════════════════════════════════════════════════

    void drawCaret(Canvas canvas, EditorDocument doc, Selection sel,
                    float textAreaLeft, float lineHeight, float paddingTop) {
        if (!sel.isCursor()) return;
        // ★ La visibilité du curseur est pilotée par EditorView.caretVisible,
        // PAS par EditorSession.readOnly : lier les deux empêcherait
        // ConsoleLogView d'utiliser setReadOnly(true) puis de muter le
        // document programmatiquement (appendLine serait no-op car
        // EditorSession.replaceRangeWithCaret bloque sous readOnly=true).
        // ConsoleLogView appelle setFocusable(false) (bloque l'IME)
        // + setCaretVisible(false) (cache le curseur) — la session reste
        // mutable, le curseur reste masqué.
        if (!view.caretVisible) return;

        // ── CaretAnimator possède TOUT l'état clignotement + glide ────
        // Le renderer délègue le clignotement à caretAnim.updateBlink() et
        // le glide à caretAnim.snapTo()/glideTo(). Il lit caretAnim.animX/Y
        // pour la position finale de dessin. AUCUN champ alias dupliqué sur
        // EditorView : sinon la branche snap de drawCaret écraserait l'alias
        // fraîchement mis à jour avec l'animX/animY périmé de l'animator
        // (reliquat d'un glide annulé), causant un retard visuel d'une
        // frame à chaque frappe.
        CaretAnimator ca = view.caretAnim;

        // Logique de clignotement — plein pendant SOLID_AFTER_EDIT_MS après
        // chaque édition / déplacement du curseur ; puis bascule on/off
        // toutes les BLINK_MS. Le curseur reste plein pendant la frappe —
        // pas de « curseur fantôme ».
        boolean drawVisible = ca.updateBlink();
        // Planifier le prochain redraw pour que le clignotement bascule
        // effectivement.
        long now = System.currentTimeMillis();
        long sinceEdit = now - ca.lastEditTime;
        if (sinceEdit < CARET_SOLID_AFTER_EDIT_MS) {
            // Phase pleine — planifier un redraw juste après la fin de la
            // période pleine.
            long delay = CARET_SOLID_AFTER_EDIT_MS - sinceEdit + 1;
            view.postInvalidateDelayed(delay);
        } else {
            long elapsed = now - ca.lastToggle;
            long delay = CARET_BLINK_MS - elapsed + 1;
            if (delay <= 0) delay = CARET_BLINK_MS;
            view.postInvalidateDelayed(delay);
        }
        if (!drawVisible) return;

        int line = EditorView.clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        int col = sel.start - doc.lineStart(line);
        float[] screenPos = view.caretScreenPos(sel.start);
        float targetX = screenPos[0];
        float targetY = screenPos[1];
        // ★ Le décalage de rangée wrap est DÉJÀ calculé par caretScreenPos
        // (source unique) — ne pas le rajouter une seconde fois : sinon, en
        // mode wrap, le curseur serait dessiné rowInLine rangées TROP BAS.
        // Mettre à jour la cible du glide. Snap si : premier placement, OU
        // texte édité (rev != révision courante du document), OU saut plus
        // grand qu'un viewport, OU retour à la ligne actif.
        int docRev = doc.getRevision();
        boolean snap = !ca.ready
            || ca.rev != docRev
            || view.wordWrap
            || Math.abs(targetY - ca.animY) > view.getHeight()
            || Math.abs(targetX - ca.animX) > view.getWidth();
        if (snap) {
            // Snapper directement à la cible — source unique de vérité.
            ca.snapTo(targetX, targetY, docRev);
        } else if (targetX != ca.targetX || targetY != ca.targetY) {
            // Nouveau déplacement dans le viewport — démarrer un glide du courant vers la cible.
            ca.glideTo(targetX, targetY, docRev);
        }
        // Ne pas dessiner si le curseur est hors du viewport.
        if (ca.animY + lineHeight < 0 || ca.animY > view.getHeight()) return;

        view.caretPaint.setColor(view.theme.caret);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(2f);
        canvas.drawLine(ca.animX, ca.animY, ca.animX, ca.animY + lineHeight, view.caretPaint);
    }

    // ════════════════════════════════════════════════════════════════
    // Guides d'indentation
    // ════════════════════════════════════════════════════════════════

    /**
     * Guides d'indentation modernisés :
     * <ul>
     *   <li><b>Pontage</b> — une ligne VIDE hérite de l'indentation la
     *       moins profonde de ses plus proches voisines non vides, pour
     *       qu'un guide traverse les rangées vides d'un bloc comme UNE
     *       seule ligne verticale continue au lieu d'une ligne
     *       pointillée.</li>
     *   <li><b>Niveaux stricts</b> — un guide est dessiné à chaque niveau
     *       de 4 colonnes STRICTEMENT inférieur à l'indentation propre de
     *       la ligne (jamais sous le premier caractère), conformément à
     *       {@code level < cols}.</li>
     *   <li><b>Extrémités arrondies</b> — fins de ligne plus douces et
     *       modernes.</li>
     * </ul>
     */
    void drawIndentGuides(Canvas canvas, EditorDocument doc,
                           int firstVisible, int lastVisible,
                           float textAreaLeft, float lineHeight, float paddingTop) {
        view.guidePaint.setColor(view.theme.indentGuide);
        view.guidePaint.setStrokeWidth(1f);
        view.guidePaint.setStrokeCap(Paint.Cap.ROUND);
        float charWidth = view.metrics.getCharWidth();
        for (int i = firstVisible; i <= lastVisible; i++) {
            // Sauter les guides d'indentation des lignes cachées (repliées).
            if (view.isLineFoldedCached(i)) continue;
            String lineText = doc.lineText(i);
            int cols = leadingIndentOrBlank(lineText);
            if (cols < 0) {
                // Ligne vide — faire le pont avec la moins profonde des plus
                // proches voisines non vides (scan borné à 64 rangées de
                // chaque côté).
                int up = i - 1, upCapped = 64;
                while (up >= 0 && upCapped-- > 0 && leadingIndentOrBlank(doc.lineText(up)) < 0) up--;
                int dn = i + 1, dnCapped = 64;
                int lineCount = doc.lineCount();
                while (dn < lineCount && dnCapped-- > 0 && leadingIndentOrBlank(doc.lineText(dn)) < 0) dn++;
                int a = (up >= 0) ? leadingIndentOrBlank(doc.lineText(up)) : 0;
                int b = (dn < lineCount) ? leadingIndentOrBlank(doc.lineText(dn)) : 0;
                cols = Math.min(a, b);
            }
            if (cols < 0) cols = 0;
            if (cols < INDENT_UNIT_COLS) continue;
            float y = view.docLineToY(i) - view.vOffset; // Y sensible aux plis
            int level = INDENT_UNIT_COLS;
            while (level < cols) {
                float x = textAreaLeft + level * charWidth - view.hOffset;
                if (x >= view.metrics.getGutterWidth()) {
                    canvas.drawLine(x, y, x, y + lineHeight, view.guidePaint);
                }
                level += INDENT_UNIT_COLS;
            }
        }
    }

    /**
     * Largeur des espaces de tête en colonnes, ou -1 pour une ligne VIDE
     * (espaces seuls — aucun caractère visible). Cette sentinelle permet
     * à la couche des guides de faire le pont sur les lignes vides.
     */
    static int leadingIndentOrBlank(String s) {
        if (s == null) return 0;
        int i = 0;
        int cols = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ') cols++;
            else if (c == '\t') cols += 4; // avancement plat, cohérent avec la grille des guides
            else return cols;
            i++;
        }
        return -1;
    }

    // ════════════════════════════════════════════════════════════════
    // Poignées de sélection (mobile)
    // ════════════════════════════════════════════════════════════════

    /** Dessine les poignées de sélection en cercles remplis sous les lignes d'ancrage. */
    void drawSelectionHandles(Canvas canvas) {
        if (!view.handlesVisible) return;
        Selection sel = view.session.getSelection();
        float density = view.getResources().getDisplayMetrics().density;
        float r = view.HANDLE_RADIUS_DP * density;
        view.selPaint.setColor(view.theme.caret);
        view.selPaint.setAntiAlias(true);
        if (sel.isCursor()) {
            float[] pos = view.caretScreenPos(sel.start);
            canvas.drawCircle(pos[0], pos[1] + view.metrics.getLineHeight() + r * 0.6f, r, view.selPaint);
        } else {
            float[] posA = view.caretScreenPos(sel.start);
            canvas.drawCircle(posA[0], posA[1] + view.metrics.getLineHeight() + r * 0.6f, r, view.selPaint);
            float[] posB = view.caretScreenPos(sel.end);
            canvas.drawCircle(posB[0], posB[1] + view.metrics.getLineHeight() + r * 0.6f, r, view.selPaint);
        }
    }
}
