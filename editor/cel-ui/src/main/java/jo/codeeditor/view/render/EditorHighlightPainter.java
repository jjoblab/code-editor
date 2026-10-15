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
        for (int i = 0; i < view.find.highlights.size(); i++) {
            Match m = view.find.highlights.get(i);
            int line = doc.lineForOffset(m.start);
            if (line < firstVisible || line > lastVisible) continue;
            // Sauter les surlignages de recherche des lignes cachées (repliées).
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int startCol = EditorView.clamp(m.start - lineStart, 0, doc.lineEnd(line) - lineStart);
            int endCol = EditorView.clamp(m.end - lineStart, 0, doc.lineEnd(line) - lineStart);
            if (endCol <= startCol) endCol = startCol + 1;
            view.selPaint.setColor(i == view.find.currentIndex ? view.theme.findCurrent : view.theme.findMatch);
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
     * Lot B refonte : guides d'indentation basés sur les blocs
     * de code (paires d'accolades pour C_LIKE, régions
     * d'indentation pour non C_LIKE), avec cache par révision
     * du document, calculés à la demande.
     *
     * <p><b>Cache (CodeBlockIndex) :</b> les blocs sont calculés
     * une fois par révision du document et cachés. La clé de
     * cache est {@code (session, doc.getRevision(), doc)}.
     * Recalcul uniquement quand le document change — pas à
     * chaque frame : les blocs sont calculés une fois par
     * révision et servis par le cache intégré à la vue (pas besoin
     * d'analyseur de langage séparé).</p>
     *
     * <p><b>Deux modes (indentCountMode) :</b></p>
     * <ul>
     *   <li><b>C_LIKE</b> (Java, Kotlin, etc.) : le guide est
     *       centré à la position réelle du {@code {} dans la
     *       ligne ({@code startCol * charWidth}).</li>
     *   <li><b>Non C_LIKE</b> (Python, YAML, etc.) : le guide
     *       est positionné à {@code indentLevel * spaceWidth}.
     *       Utilise l'algorithme IndentRange (balayage
     *       bas-vers-haut) avec mode {@code offSide} pour
     *       Python.</li>
     * </ul>
     *
     * <p><b>Recherche binaire :</b> les blocs sont triés par
     * {@code startLine}, ce qui permet une recherche binaire
     * pour trouver le premier bloc visible en O(log blocs).</p>
     *
     * <p><b>Caractéristiques :</b></p>
     * <ul>
     *   <li>API publique {@link IndentGuideStyle} pour
     *       activer/désactiver les guides (OFF/INDENT/INDENT_WITH_SCOPE).</li>
     *   <li>Portée active pour les langages non C_LIKE (basée
     *       sur l'indentation).</li>
     *   <li>Cache intégré à la vue, pas besoin d'analyseur de
     *       langage dédié — fonctionne pour tous les langages
     *       enregistrés dans le registre.</li>
     * </ul>
     */
    void drawIndentGuides(Canvas canvas, EditorDocument doc,
                           int firstVisible, int lastVisible,
                           float textAreaLeft, float lineHeight, float paddingTop) {
        float charWidth = view.metrics.getCharWidth();
        float density = view.getResources().getDisplayMetrics().density;
        float baseStroke = Math.max(1f, 0.75f * density);
        float activeStroke = Math.max(1.5f, 1.1f * density);

        view.guidePaint.setStrokeCap(Paint.Cap.BUTT);
        view.guidePaint.setAntiAlias(true);

        // Récupérer les blocs cachés (recalcul uniquement si le
        // document a changé).
        int[][] blocks = view.getCodeBlocks();
        boolean indentCountMode = view.isIndentCountMode();

        // Portée active.
        boolean scopeActive = view.indentGuideStyle
                == jo.codeeditor.view.IndentGuideStyle.INDENT_WITH_SCOPE;
        int caretDepth = -1;
        if (scopeActive && view.session != null) {
            CharSequence docChars = doc.charSequence();
            int caret = view.session.getSelection().start;
            if (indentCountMode) {
                // Non C_LIKE : profondeur basée sur l'indentation.
                int caretLine = doc.lineForOffset(caret);
                int indent = leadingIndentOfLine(doc, doc.charSequence(), caretLine);
                caretDepth = indent < 0 ? -1 : indent;
            } else {
                // C_LIKE : profondeur basée sur les accolades.
                caretDepth = jo.codeeditor.view.EditorView
                        .braceScopeLevel(docChars, caret);
            }
        }

        float offsetX = textAreaLeft - view.hOffset;

        // Recherche binaire : premier bloc dont startLine >= firstVisible.
        // On balaye à partir de là — les blocs avant sont hors viewport.
        int startIdx = findFirstVisibleBlock(blocks, firstVisible);

        for (int bi = startIdx; bi < blocks.length; bi++) {
            int[] block = blocks[bi];
            int startLine = block[0];
            int startCol = block[1];
            int endLine = block[2];
            int endCol = block[3];

            // Arrêter si on dépasse le viewport (les blocs sont
            // triés par startLine).
            if (startLine > lastVisible) break;

            // X : le guide est centré au MILIEU de la colonne
            // d'indentation : x = (col_x + (col+1)_x) / 2.
            // On utilise min(startCol, endCol) pour la position de
            // base, puis on ajoute un demi-
            // caractère pour centrer.
            int baseCol = Math.min(startCol, endCol);
            float centerX = offsetX + (baseCol + 0.5f) * charWidth;
            if (centerX < view.metrics.getGutterWidth()) continue;

            // Y : du BAS de la ligne d'ouverture au HAUT de la
            // ligne de fermeture (C_LIKE) ou au BAS de la dernière
            // ligne (non C_LIKE).
            float top = view.docLineToY(startLine) - view.vOffset + lineHeight;
            float bottom;
            if (indentCountMode) {
                bottom = view.docLineToY(endLine) - view.vOffset + lineHeight;
            } else {
                bottom = view.docLineToY(endLine) - view.vOffset;
            }
            if (top >= bottom) continue;

            // Profondeur du bloc pour la portée active.
            int blockDepth = computeBlockDepth(blocks, bi);

            // Couleur : 3 niveaux —
            // 1. MATCHED_BRACES : si l'accolade appariée du caret
            //    est sur ce bloc (accolade ouvrante ou fermante).
            // 2. SELECTED : si le caret est à l'intérieur de ce
            //    bloc (portée active).
            // 3. NORMAL : sinon.
            int guideColor = view.theme.indentGuide;
            float stroke = baseStroke;
            if (scopeActive) {
                int activeState = getBlockActiveState(
                        indentCountMode, caretDepth, blockDepth,
                        blocks, bi, view, doc);
                if (activeState == 2) {
                    // MATCHED_BRACES : accolade appariée du caret.
                    guideColor = view.theme.caret;
                    stroke = activeStroke;
                } else if (activeState == 1) {
                    // SELECTED : caret à l'intérieur du bloc.
                    guideColor = view.theme.caret;
                    stroke = activeStroke;
                }
            }
            view.guidePaint.setColor(guideColor);
            view.guidePaint.setStrokeWidth(stroke);

            // Soft wraps : le guide est interrompu aux points
            // de soft wrap. On vérifie si le guide traverse des
            // lignes enroulées et on dessine segment par segment.
            if (view.wordWrap && view.wrapModel != null) {
                drawGuideWithSoftWraps(canvas, centerX, top, bottom,
                        startLine, endLine, stroke);
            } else {
                // Pas de soft wrap : un seul rectangle.
                float halfWidth = stroke / 2f;
                canvas.drawRect(centerX - halfWidth, top,
                        centerX + halfWidth, bottom, view.guidePaint);
            }
        }
    }

    /**
     * Détermine l'état actif d'un bloc.
     * @return 0 = normal, 1 = sélectionné (caret à l'intérieur),
     *         2 = accolade appariée (caret sur { ou } du bloc).
     */
    private static int getBlockActiveState(boolean indentCountMode,
            int caretDepth, int blockDepth, int[][] blocks, int idx,
            EditorView view, EditorDocument doc) {
        int[] block = blocks[idx];
        int startLine = block[0];
        int endLine = block[2];

        if (view.session == null) return 0;
        int caret = view.session.getSelection().start;
        int caretLine = doc.lineForOffset(caret);

        // MATCHED_BRACES : le caret est sur la ligne du { ou du }.
        if (!indentCountMode) {
            if (caretLine == startLine || caretLine == endLine) {
                // Vérifier que le caret est proche de l'accolade.
                int startCol = block[1];
                int endCol = block[3];
                int caretCol = caret - doc.lineStart(caretLine);
                if (caretLine == startLine && caretCol >= startCol
                        && caretCol <= startCol + 1) {
                    return 2;
                }
                if (caretLine == endLine && caretCol >= endCol
                        && caretCol <= endCol + 1) {
                    return 2;
                }
            }
        }

        // SELECTED : le caret est à l'intérieur du bloc.
        if (indentCountMode) {
            return caretDepth >= 0 && caretDepth > block[1] ? 1 : 0;
        } else {
            return caretDepth == blockDepth + 1 ? 1 : 0;
        }
    }

    /**
     * Dessine le guide en tenant compte des soft wraps.
     * Le guide est interrompu aux points de soft wrap pour
     * éviter qu'il traverse le texte enroulé.
     */
    private void drawGuideWithSoftWraps(Canvas canvas, float centerX,
            float top, float bottom, int startLine, int endLine,
            float stroke) {
        float halfWidth = stroke / 2f;
        float segmentTop = top;

        for (int li = startLine + 1; li <= endLine; li++) {
            float lineY = view.docLineToY(li) - view.vOffset;
            if (lineY > top && lineY < bottom) {
                // Vérifier si cette ligne est une continuation de
                // soft wrap (rangée > 0 de la ligne précédente).
                int prevRows = view.wrapModel.rowsOf(li - 1);
                if (prevRows > 1) {
                    // La ligne précédente a plusieurs rangées —
                    // le guide s'arrête avant le soft wrap et
                    // reprend après.
                    float softWrapY = lineY;
                    if (segmentTop < softWrapY) {
                        canvas.drawRect(centerX - halfWidth, segmentTop,
                                centerX + halfWidth, softWrapY,
                                view.guidePaint);
                    }
                    segmentTop = softWrapY;
                }
            }
        }
        // Dessiner le dernier segment.
        if (segmentTop < bottom) {
            canvas.drawRect(centerX - halfWidth, segmentTop,
                    centerX + halfWidth, bottom, view.guidePaint);
        }
    }

    /**
     * Recherche binaire : premier bloc dont startLine >= firstVisible.
     * Les blocs sont triés par startLine (cf. CodeBlockIndex).
     */
    private static int findFirstVisibleBlock(int[][] blocks, int firstVisible) {
        int lo = 0, hi = blocks.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (blocks[mid][0] < firstVisible) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Calcule la profondeur d'un bloc (combien de blocs le contiennent).
     */
    private static int computeBlockDepth(int[][] blocks, int idx) {
        int[] target = blocks[idx];
        int depth = 0;
        for (int i = 0; i < blocks.length; i++) {
            if (i == idx) continue;
            int[] other = blocks[i];
            if (other[0] < target[0]
                    && (other[2] > target[2]
                        || (other[2] == target[2] && other[3] > target[3]))) {
                depth++;
            }
        }
        return depth;
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
