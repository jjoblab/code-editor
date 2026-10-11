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
    /**
     * Lot B (audit v3.41.0) : l'unité d'indentation n'est plus figée
     * à 4 colonnes. {@link #drawIndentGuides} détecte l'unité réelle
     * (2/4/8 espaces ou tabulation) via {@link jo.codeeditor.edit.EditOps#detectIndentUnit}.
     * La constante reste pour compatibilité ascendante des appels
     * existants, mais n'est plus utilisée dans drawIndentGuides.
     */
    @Deprecated
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
            view.selPaint.setColor(i == view.findCurrentIndex ? view.theme.findCurrent : view.theme.findMatch);
            // Bande consciente du wrap (une rect par rangée visuelle).
            drawColRangeBand(canvas, doc, line, startCol, endCol, textAreaLeft, lineHeight);
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
            // Bande consciente du wrap (une rect par rangée visuelle).
            drawColRangeBand(canvas, doc, line, startCol, endCol, textAreaLeft, lineHeight);
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

    /**
     * Dessine la bande de la plage de colonnes {@code [colStart, colEnd)}
     * de la ligne de document donnée avec le paint courant
     * ({@code view.selPaint}), UNE RECT PAR RANGÉE VISUELLE en mode
     * word-wrap.
     *
     * <p>Bug « les caractères sélectionnés n'ont plus de couleur » (config
     * demo : wrap activé, ligne minifiée) : la géométrie historique posait
     * UNE seule rect pleine ligne à {@code colonne × charWidth} — comme si
     * la ligne ne wrappait pas. Dès que la sélection visait des colonnes
     * au-delà de la capacité de la 1re rangée (~100 colonnes), la bande
     * était dessinée HORS ÉCRAN à droite : poignées et drag corrects
     * (caretScreenPos est wrap-aware) mais AUCUNE couleur sur les mots
     * sélectionnés. Chaque passe qui dessine une plage de colonnes
     * (sélection, occurrences de recherche, occurrences LSP) passe par
     * ce helper pour que la bande suive les rangées du modèle de wrap.</p>
     *
     * <p>Hors wrap : chemin historique inchangé — une seule rect, X
     * sensible aux inlays via {@code visualColFor} (tissage des colonnes).</p>
     */
    private void drawColRangeBand(Canvas canvas, EditorDocument doc, int line,
                                  int colStart, int colEnd,
                                  float textAreaLeft, float lineHeight) {
        // ★ Discipline d'état du paint partagé : la loupe et la minimap
        // dessinent leurs contours avec CE MÊME paint en Style.STROKE.
        // Forcer FILL ici garantit que la bande de sélection / recherche /
        // occurrences LSP reste un REMPLISSAGE même si un peintre de la
        // frame précédente a laissé le style en STROKE (bug « bordure du
        // rectangle visible mais pas la couleur de sélection » pendant le
        // drag de la loupe).
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        final int lineLen = doc.lineEnd(line) - doc.lineStart(line);
        if (view.wordWrap && view.wrapModel != null) {
            // Mode wrap : une rect par rangée couverte. Le X suit la même
            // géométrie que caretScreenPos/offsetAt (colonnes BRUTES — les
            // rangées de continuation démarrent à wrapIndentCols).
            EditorView.WrapRows wr = view.wrapRowsFor(line, lineLen);
            float charWidth = view.metrics.getCharWidth();
            float lineTop = view.docLineToY(line) - view.vOffset;
            if (colStart >= colEnd) {
                // Ligne vide couverte par une sélection multi-lignes :
                // marqueur de traîne pour que la rangée reste visible.
                float x = textAreaLeft - view.hOffset;
                canvas.drawRect(x, lineTop, x + charWidth * 0.6f,
                        lineTop + lineHeight, view.selPaint);
                return;
            }
            for (int r = 0; r < wr.rows; r++) {
                int rowStart = wr.rowStartCol(r);
                int rowEnd = wr.rowEndCol(r, lineLen);
                int s = Math.max(colStart, rowStart);
                int e = Math.min(colEnd, rowEnd);
                if (e <= s) continue;
                float rowX = textAreaLeft
                        + (r > 0 ? wr.wrapIndentCols * charWidth : 0);
                float x1 = rowX + (s - rowStart) * charWidth - view.hOffset;
                float x2 = rowX + (e - rowStart) * charWidth - view.hOffset;
                float y = lineTop + r * lineHeight;
                canvas.drawRect(x1, y, Math.max(x1 + 1, x2), y + lineHeight,
                        view.selPaint);
            }
            return;
        }
        // Hors wrap : bande unique, colonnes visuelles tissées d'inlays
        // (chemin historique, inchangé).
        float charWidth = view.metrics.getCharWidth();
        float y = view.docLineToY(line) - view.vOffset;
        float x1 = textAreaLeft + view.visualColFor(line, colStart) * charWidth - view.hOffset;
        float x2 = textAreaLeft + view.visualColFor(line, colEnd) * charWidth - view.hOffset;
        // Marqueur de traîne après la fin de ligne pour qu'une ligne
        // sélectionnée vide montre quand même une bande.
        float trailing = (colEnd == colStart) ? charWidth * 0.6f : 0;
        canvas.drawRect(x1, y, Math.max(x1 + 1, x2 + trailing), y + lineHeight, view.selPaint);
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
        for (int i = Math.max(selStartLine, firstVisible); i <= Math.min(selEndLine, lastVisible); i++) {
            // Sauter les lignes cachées (repliées) — pas de bande de sélection dans le trou.
            if (view.isLineFoldedCached(i)) continue;
            int lineStart = doc.lineStart(i);
            int lineEnd = doc.lineEnd(i);
            int colStart = (i == selStartLine) ? sel.start - lineStart : 0;
            int colEnd = (i == selEndLine) ? sel.end - lineStart : lineEnd - lineStart;
            colStart = EditorView.clamp(colStart, 0, lineEnd - lineStart);
            colEnd = EditorView.clamp(colEnd, 0, lineEnd - lineStart);
            // Bande consciente du wrap : une rect par rangée visuelle
            // (l'ancienne rect pleine ligne partait hors écran dès la 2e
            // rangée wrappée — sélection « sans couleur »).
            drawColRangeBand(canvas, doc, i, colStart, colEnd, textAreaLeft, lineHeight);
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
    /**
     * <p><b>Lot B refonte (audit v3.41.0) — guides d'indentation basés sur
     * les paires d'accolades, approche l'éditeur de code de référence :</b></p>
     *
     * <p>Chaque paire d'accolades {@code { … }} crée un bloc de code.
     * Un guide vertical est dessiné au centre de la colonne de
     * l'accolade ouvrante, du bas de la ligne d'ouverture jusqu'au
     * haut de la ligne de fermeture. Le guide ne déborde pas au-delà
     * du bloc — il commence à {@code rowBottom(startLine)} et
     * s'arrête à {@code rowTop(endLine)}.</p>
     *
     * <p>Approche inspirée de l'éditeur de code de référence  :</p>
     * <ul>
     *   <li>Les guides sont basés sur les <b>blocs</b> (paires
     *       d'accolades), pas sur l'indentation ligne par ligne.
     *       Chaque paire {@code { ... }} génère un guide.</li>
     *   <li>Le guide est <b>centré</b> à la position de l'accolade
     *       ouvrante : {@code centerX = offsetX + startColumn *
     *       spaceWidth}, puis le rectangle fait
     *       {@code [centerX - w/2, centerX + w/2]}.</li>
     *   <li>Le guide s'étend du <b>bas de la ligne d'ouverture</b>
     *       au <b>haut de la ligne de fermeture</b> — pas au-delà.</li>
     *   <li>Le bloc actif (qui contient le caret) reçoit la couleur
     *       d'accent ({@code theme.caret}) et une épaisseur
     *       légèrement supérieure.</li>
     * </ul>
     *
     * <p><b>Pour les langages non C_LIKE</b> (Python, YAML, etc.),
     * on retombe sur l'approche par niveau d'indentation (l'ancien
     * comportement). Les accolades ne sont pas significatives pour
     * ces langages.</p>
     *
     * <p><b>Performance</b> : la détection des paires d'accolades
     * utilise {@link EditorView#braceScopeLevel} (déjà calculé
     * pour la portée active si INDENT_WITH_SCOPE). Le balayage des
     * lignes visibles est O(visible × profondeur) par frame. Pas
     * d'allocation de chaîne — balayage direct sur la rope.</p>
     */
    void drawIndentGuides(Canvas canvas, EditorDocument doc,
                           int firstVisible, int lastVisible,
                           float textAreaLeft, float lineHeight, float paddingTop) {
        CharSequence docChars = doc.charSequence();
        String indentUnit = jo.codeeditor.edit.EditOps.detectIndentUnit(docChars);
        int unitCols;
        switch (indentUnit) {
            case "\t": unitCols = jo.codeeditor.edit.EditOps.detectTabSize(docChars); break;
            case "  ": unitCols = 2; break;
            case "        ": unitCols = 8; break;
            case "    ":
            default: unitCols = 4; break;
        }
        float charWidth = view.metrics.getCharWidth();
        float density = view.getResources().getDisplayMetrics().density;
        float baseStroke = Math.max(1f, 0.75f * density);
        float activeStroke = Math.max(1.5f, 1.1f * density);

        view.guidePaint.setStrokeCap(Paint.Cap.BUTT);
        view.guidePaint.setAntiAlias(true);

        // Pour les langages C_LIKE, utiliser l'approche par blocs
        // (paires d'accolades). Pour les autres, retomber sur
        // l'approche par niveau d'indentation.
        jo.codeeditor.languages.LanguageProfile profile =
                view.session != null ? view.session.getLanguageProfile() : null;
        boolean useBlocks = profile != null
                && profile.family == jo.codeeditor.languages.SyntaxFamily.C_LIKE;

        if (useBlocks) {
            drawBlockBasedGuides(canvas, doc, docChars, firstVisible, lastVisible,
                    textAreaLeft, lineHeight, charWidth, baseStroke, activeStroke,
                    indentUnit, unitCols);
        } else {
            drawIndentLevelGuides(canvas, doc, docChars, firstVisible, lastVisible,
                    textAreaLeft, lineHeight, charWidth, baseStroke, activeStroke,
                    unitCols);
        }
    }

    /**
     * Lot B refonte : guides basés sur les paires d'accolades
     * (approche l'éditeur de code de référence). Chaque paire {@code { … }} génère
     * un guide vertical centré à la colonne de l'accolade
     * ouvrante, du bas de la ligne d'ouverture au haut de la
     * ligne de fermeture.
     */
    private void drawBlockBasedGuides(Canvas canvas, EditorDocument doc,
            CharSequence docChars, int firstVisible, int lastVisible,
            float textAreaLeft, float lineHeight, float charWidth,
            float baseStroke, float activeStroke,
            String indentUnit, int unitCols) {
        // Portée active : profondeur d'accolades du caret.
        boolean scopeActive = view.indentGuideStyle
                == jo.codeeditor.view.IndentGuideStyle.INDENT_WITH_SCOPE;
        int caretBraceDepth = -1;
        if (scopeActive && view.session != null) {
            int caret = view.session.getSelection().start;
            caretBraceDepth = jo.codeeditor.view.EditorView
                    .braceScopeLevel(docChars, caret);
        }

        // Détecter les paires d'accolades visibles. On balaye
        // les lignes visibles et pour chaque '{' trouvé (hors
        // chaîne/commentaire), on cherche le '}' correspondant
        // via une pile d'accolades.
        // Optimisation : on ne balaye que les lignes visibles
        // (firstVisible..lastVisible) plus les lignes après
        // (jusqu'à trouver la fermeture).
        java.util.List<int[]> blocks = detectBraceBlocks(
                doc, docChars, firstVisible, lastVisible);

        float offsetX = textAreaLeft - view.hOffset;

        for (int bi = 0; bi < blocks.size(); bi++) {
            int[] block = blocks.get(bi);
            int startLine = block[0];
            int startCol = block[1];
            int endLine = block[2];
            // int endCol = block[3]; // pas utilisé pour X
            boolean toBottomOfEndLine = block[4] == 1;

            // X = centre de la colonne de l'accolade ouvrante.
            float centerX = offsetX + startCol * charWidth;
            if (centerX < view.metrics.getGutterWidth()) continue;

            // Y : du bas de la ligne d'ouverture au haut de la
            // ligne de fermeture (ou au bas si toBottomOfEndLine).
            float top = view.docLineToY(startLine) - view.vOffset + lineHeight;
            float bottom;
            if (toBottomOfEndLine) {
                bottom = view.docLineToY(endLine) - view.vOffset + lineHeight;
            } else {
                bottom = view.docLineToY(endLine) - view.vOffset;
            }

            if (top >= bottom) continue;

            // Couleur : accent si c'est le bloc actif.
            int blockDepth = getBlockDepth(blocks, bi);
            boolean isActive = scopeActive && caretBraceDepth == blockDepth + 1;
            view.guidePaint.setColor(isActive ? view.theme.caret : view.theme.indentGuide);
            view.guidePaint.setStrokeWidth(isActive ? activeStroke : baseStroke);

            // Dessiner le guide comme un rectangle fin (approche
            // l'éditeur de code de référence) pour un rendu net.
            float halfWidth = (isActive ? activeStroke : baseStroke) / 2f;
            canvas.drawRect(centerX - halfWidth, top,
                    centerX + halfWidth, bottom, view.guidePaint);
        }
    }

    /**
     * Détecte les paires d'accolades {@code { … }} dans les
     * lignes visibles. Retourne une liste de
     * {@code [startLine, startCol, endLine, endCol, toBottomOfEndLine]}.
     *
     * <p>Le balayage ignore les chaînes et les commentaires. Il
     * est borné aux lignes visibles plus quelques lignes après
     * pour trouver les fermetures.</p>
     */
    private java.util.List<int[]> detectBraceBlocks(EditorDocument doc,
            CharSequence docChars, int firstVisible, int lastVisible) {
        java.util.List<int[]> result = new java.util.ArrayList<>();
        // Pile des '{' en attente de fermeture.
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();

        // État lexer (conscient des chaînes/commentaires).
        boolean inString = false;
        char quoteChar = '\0';
        boolean inLineComment = false;
        boolean inBlockComment = false;

        int lineCount = doc.lineCount();
        int scanLimit = Math.min(lineCount, lastVisible + 200);

        for (int li = 0; li < scanLimit; li++) {
            int lineStart = doc.lineStart(li);
            int lineEnd = doc.lineEnd(li);
            inLineComment = false;

            for (int ci = lineStart; ci < lineEnd; ci++) {
                char c = docChars.charAt(ci);
                char next = ci + 1 < docChars.length() ? docChars.charAt(ci + 1) : 0;

                if (inLineComment) break;
                if (inBlockComment) {
                    if (c == '*' && next == '/') { inBlockComment = false; ci++; }
                    continue;
                }
                if (inString) {
                    if (c == '\\') { ci++; continue; }
                    if (c == quoteChar) inString = false;
                    continue;
                }
                if (c == '/' && next == '/') { inLineComment = true; break; }
                if (c == '/' && next == '*') { inBlockComment = true; ci++; continue; }
                if (c == '"' || c == '\'') { inString = true; quoteChar = c; continue; }

                if (c == '{') {
                    int col = ci - lineStart;
                    stack.push(new int[]{li, col});
                } else if (c == '}' && !stack.isEmpty()) {
                    int[] open = stack.pop();
                    int endCol = ci - lineStart;
                    // On ne garde que les blocs visibles (startLine
                    // dans la fenêtre visible).
                    if (open[0] >= firstVisible && open[0] <= lastVisible) {
                        // toBottomOfEndLine : true si le '}' est
                        // le seul caractère non-blanc de sa ligne.
                        boolean toBottom = isEndOfLineBrace(docChars, ci);
                        result.add(new int[]{open[0], open[1], li, endCol, toBottom ? 1 : 0});
                    }
                }
            }
        }
        // Les blocs non fermés (pile restante) sont ignorés —
        // le document est en cours d'édition, le '}' n'existe pas.
        return result;
    }

    /** Vérifie si le '}' à l'offset donné est le dernier
     *  caractère non-blanc de sa ligne (→ toBottomOfEndLine=true). */
    private static boolean isEndOfLineBrace(CharSequence text, int braceOffset) {
        int lineEnd = braceOffset + 1;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') lineEnd++;
        for (int i = braceOffset + 1; i < lineEnd; i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }

    /** Calcule la profondeur d'un bloc (combien de blocs le contiennent). */
    private static int getBlockDepth(java.util.List<int[]> blocks, int idx) {
        int[] target = blocks.get(idx);
        int depth = 0;
        for (int i = 0; i < blocks.size(); i++) {
            if (i == idx) continue;
            int[] other = blocks.get(i);
            // 'other' contient 'target' si other.startLine < target.startLine
            // et other.endLine > target.endLine (ou == avec endCol > ).
            if (other[0] < target[0]
                    && (other[2] > target[2]
                        || (other[2] == target[2] && other[3] > target[3]))) {
                depth++;
            }
        }
        return depth;
    }

    /**
     * Ancienne approche par niveau d'indentation (pour les
     * langages non C_LIKE : Python, YAML, XML, etc.).
     */
    private void drawIndentLevelGuides(Canvas canvas, EditorDocument doc,
            CharSequence docChars, int firstVisible, int lastVisible,
            float textAreaLeft, float lineHeight, float charWidth,
            float baseStroke, float activeStroke, int unitCols) {
        // Portée active : profondeur d'indentation du caret.
        int caretIndent = -1;
        boolean scopeActive = view.indentGuideStyle
                == jo.codeeditor.view.IndentGuideStyle.INDENT_WITH_SCOPE;
        if (scopeActive && view.session != null) {
            int caret = view.session.getSelection().start;
            int caretLine = doc.lineForOffset(caret);
            caretIndent = leadingIndentOfLine(doc, docChars, caretLine);
            if (caretIndent < 0) caretIndent = -1;
        }

        int maxLevel = 0;
        for (int i = firstVisible; i <= lastVisible; i++) {
            if (view.isLineFoldedCached(i)) continue;
            int cols = leadingIndentOrBlank(doc, docChars, i);
            if (cols < 0) {
                cols = bridgeBlank(doc, docChars, i);
            }
            if (cols > maxLevel) maxLevel = cols;
        }
        for (int level = unitCols; level <= maxLevel; level += unitCols) {
            boolean isActiveLevel = scopeActive
                    && (caretIndent >= level && caretIndent < level + unitCols);
            view.guidePaint.setColor(isActiveLevel ? view.theme.caret : view.theme.indentGuide);
            view.guidePaint.setStrokeWidth(isActiveLevel ? activeStroke : baseStroke);
            float x = textAreaLeft + level * charWidth - view.hOffset;
            if (x < view.metrics.getGutterWidth()) continue;
            float runStartY = -1;
            float runEndY = -1;
            for (int i = firstVisible; i <= lastVisible; i++) {
                if (view.isLineFoldedCached(i)) {
                    if (runStartY >= 0) {
                        canvas.drawLine(x, runStartY, x, runEndY + lineHeight, view.guidePaint);
                        runStartY = -1;
                        runEndY = -1;
                    }
                    continue;
                }
                int cols = leadingIndentOrBlank(doc, docChars, i);
                if (cols < 0) {
                    cols = bridgeBlank(doc, docChars, i);
                }
                boolean hasGuide = cols >= level;
                float y = view.docLineToY(i) - view.vOffset;
                int rows = 1;
                if (view.wordWrap && view.wrapModel != null) {
                    rows = view.wrapModel.rowsOf(i);
                    if (rows < 1) rows = 1;
                }
                if (hasGuide) {
                    if (runStartY < 0) {
                        runStartY = y;
                        runEndY = y + (rows - 1) * lineHeight;
                    } else if (y == runEndY + lineHeight) {
                        runEndY = y + (rows - 1) * lineHeight;
                    } else {
                        canvas.drawLine(x, runStartY, x, runEndY + lineHeight, view.guidePaint);
                        runStartY = y;
                        runEndY = y + (rows - 1) * lineHeight;
                    }
                } else {
                    if (runStartY >= 0) {
                        canvas.drawLine(x, runStartY, x, runEndY + lineHeight, view.guidePaint);
                        runStartY = -1;
                        runEndY = -1;
                    }
                }
            }
            if (runStartY >= 0) {
                canvas.drawLine(x, runStartY, x, runEndY + lineHeight, view.guidePaint);
            }
        }
    }

    /**
     * Largeur des espaces de tête en colonnes, ou -1 pour une ligne VIDE
     * (espaces seuls — aucun caractère visible). Cette sentinelle permet
     * à la couche des guides de faire le pont sur les lignes vides.
     *
     * <p><b>Lot B</b> : conservé pour compatibilité des tests
     * (EditorRenderer.leadingIndentOrBlank délègue à cette version).
     * Le chemin interne principal de drawIndentGuides utilise la version
     * sans allocation
     * {@link #leadingIndentOrBlank(EditorDocument, CharSequence, int)}.</p>
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

    /**
     * Lot B : largeur des espaces de tête en colonnes pour la ligne
     * {@code i}, sans allouer de String. Lit directement la rope via
     * {@code charSequence.charAt} (O(log n) par caractère).
     *
     * @return -1 pour une ligne VIDE (espaces seuls, aucun caractère
     *         visible) — sentinelle pour le pontage ; sinon le nombre
     *         de colonnes d'indentation.
     */
    private static int leadingIndentOrBlank(EditorDocument doc, CharSequence chars, int i) {
        int start = doc.lineStart(i);
        int end = doc.lineEnd(i);
        int cols = 0;
        boolean onlyWhitespace = true;
        for (int j = start; j < end; j++) {
            char c = chars.charAt(j);
            if (c == ' ') { cols++; onlyWhitespace = true; }
            else if (c == '\t') {
                // Lot B : tabulation — avancement aligné sur la largeur
                // de tabulation détectée (cf. drawIndentGuides).
                cols += 4; // fallback si pas détecté — cohérent avec
                           // l'ancien leadingIndentOrBlank
                onlyWhitespace = true;
            } else {
                onlyWhitespace = false;
                break;
            }
        }
        return onlyWhitespace ? -1 : cols;
    }

    /**
     * Lot B : calcule l'indentation de pontage d'une ligne vide
     * (la moins profonde des plus proches voisines non vides,
     * scan borné à 64 rangées de chaque côté).
     */
    private static int bridgeBlank(EditorDocument doc, CharSequence chars, int i) {
        int up = i - 1, upCapped = 64;
        while (up >= 0 && upCapped-- > 0 && leadingIndentOrBlank(doc, chars, up) < 0) up--;
        int lineCount = doc.lineCount();
        int dn = i + 1, dnCapped = 64;
        while (dn < lineCount && dnCapped-- > 0 && leadingIndentOrBlank(doc, chars, dn) < 0) dn++;
        int a = (up >= 0) ? leadingIndentOrBlank(doc, chars, up) : 0;
        int b = (dn < lineCount) ? leadingIndentOrBlank(doc, chars, dn) : 0;
        if (a < 0) a = 0;
        if (b < 0) b = 0;
        return Math.min(a, b);
    }

    /**
     * Lot B : largeur des espaces de tête d'une ligne, sans allouer.
     * Retourne 0 pour une ligne vide (pas de portée active sur une
     * ligne vide).
     */
    private static int leadingIndentOfLine(EditorDocument doc, CharSequence chars, int i) {
        if (i < 0 || i >= doc.lineCount()) return 0;
        int cols = leadingIndentOrBlank(doc, chars, i);
        return cols < 0 ? 0 : cols;
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
        // Poignées REMPLIES : forcer FILL (le paint partagé peut arriver
        // en STROKE si un peintre concurrent l'a laissé dans cet état).
        view.selPaint.setStyle(Paint.Style.FILL);
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
