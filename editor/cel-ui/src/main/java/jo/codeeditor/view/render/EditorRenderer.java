package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Orchestrateur du rendu Canvas de l'éditeur : {@link #draw(Canvas)}
 * enchaîne les couches (fond, surlignages, texte, gouttière, curseur,
 * popups, overlays) et délègue chaque domaine à un collaborateur dédié
 * par composition : EditorTextPainter (texte pur), EditorHighlightPainter
 * (surlignages + curseur), EditorDiagnosticsPainter (diagnostics),
 * EditorAssistPopupPainter (popups d'assistance), EditorChromePainter
 * (chrome : minimap, aperçus, loupe, chevrons, NavMenu, toolbar).
 * Appelé par {@link EditorView#onDraw(Canvas)}.
 *
 * <p>Tout accès à l'état passe par la référence {@code view} (champs
 * package-privés d'EditorView, même package). Les utilitaires partagés
 * avec des chemins hors dessin restent sur EditorView (ex.
 * {@link EditorView#docLineToY(int)},
 * {@link EditorView#layoutForLine(int, String)},
 * {@link EditorView#caretScreenPos(int)}). Les méthodes conservées ici
 * préservent l'API package-privée appelée par EditorView et les tests
 * via des relais ({@link #quickDocMetrics()},
 * {@link #leadingIndentOrBlank(String)},
 * {@link #drawCompletionPopup(Canvas)},
 * {@link #drawDiagnosticPopup(Canvas)}).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorRenderer {
    private final EditorView view;

    // Collaborateurs de rendu (composition) — un par domaine de dessin.
    private final EditorTextPainter text;
    private final EditorHighlightPainter highlights;
    private final EditorDiagnosticsPainter diagnostics;
    private final EditorAssistPopupPainter assist;
    private final EditorChromePainter chrome;

    // Objets de travail réutilisables — évite les allocations par frame / par
    // ligne. Usage mono-thread (uniquement depuis onDraw sur le thread UI),
    // donc aucune synchronisation nécessaire.
    private final RectF scratchRectF = new RectF();

    public EditorRenderer(EditorView view) {
        this.view = view;
        this.text = new EditorTextPainter(view);
        this.highlights = new EditorHighlightPainter(view);
        this.diagnostics = new EditorDiagnosticsPainter(view);
        this.assist = new EditorAssistPopupPainter(view);
        this.chrome = new EditorChromePainter(view, text);
    }

    // ════════════════════════════════════════════════════════════════
    // Point d'entrée principal — appelé par EditorView.onDraw()
    // ════════════════════════════════════════════════════════════════

    /** Point d'entrée principal — appelé par {@link EditorView#onDraw(Canvas)}. */
    public void draw(Canvas canvas) {
        if (view.session == null) return;

        final EditorDocument doc = view.session.getDocument();
        final List<StyledLine> styledLines = view.session.getStyledLines();
        final Selection sel = view.clampSelection(view.session.getSelection(), doc);

        final int width = view.getWidth();
        final int height = view.getHeight();
        final float lineHeight = view.metrics.getLineHeight();
        final float paddingTop = view.metrics.getPadTop();
        final float paddingLeft = view.metrics.getPadLeft();
        final float gutterWidth = view.metrics.getGutterWidth();
        final float textAreaLeft = gutterWidth + paddingLeft;

        final int currentLine = clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        // firstVisible/lastVisible sont des RANGÉES VISUELLES (pas des lignes
        // du document). Quand des plis réduits existent, la rangée visuelle N
        // correspond à une ligne de document > N (les lignes cachées sont
        // sautées). Utiliser les rangées visuelles directement comme indices
        // de lignes fait itérer la boucle de dessin sur une plage trop étroite
        // — le bas du viewport n'est pas redessiné et les passes non sensibles
        // aux plis (sélection, guides d'indentation, soulignements ondulés)
        // dessinent à un mauvais Y. D'où : quand des plis existent, calculer
        // la plage de lignes via le mappeur sensible aux plis
        // docLineForScreenY (comme le retour à la ligne le fait).
        boolean hasCollapsedFolds = !view.session.getCollapsedFolds().isEmpty();
        final int firstVisible = Math.max(0, (int) Math.floor(view.vOffset / lineHeight) - 1);
        final int lastVisible  = Math.min(doc.lineCount() - 1,
                                          (int) Math.ceil((view.vOffset + height) / lineHeight) + 1);
        // Quand le retour à la ligne automatique est actif, la plage de lignes
        // visibles du document est plus large car chaque ligne s'étale sur
        // plusieurs rangées visuelles. Calculer une plage inclusive de lignes
        // du document, également sensible aux plis quand ils existent.
        final int firstDocVisible = (view.wordWrap || hasCollapsedFolds)
            ? clamp(view.docLineForScreenY(0), 0, doc.lineCount() - 1)
            : firstVisible;
        final int lastDocVisible = (view.wordWrap || hasCollapsedFolds)
            ? clamp(view.docLineForScreenY(height), 0, doc.lineCount() - 1)
            : lastVisible;

        // Décorations de plugins : exécuter les EditorDecorationPainters
        // enregistrés UNE fois par frame (hôte vide = no-op renvoyant une frame
        // EMPTY partagée). Les marques de gouttière sont poussées vers le
        // GutterView avant son dessin ; les décorations de texte + inlays de
        // plugins sont dessinés dans le clip de la zone de texte après les
        // soulignements ondulés.
        final EditorPainterHost.Frame pluginFrame =
                view.painterHost.apply(view, firstDocVisible, lastDocVisible);

        // 1. Fond de l'éditeur
        view.bgPaint.setColor(view.theme.editorBg);
        canvas.drawRect(0, 0, width, height, view.bgPaint);

        // NB : refreshCodeActions n'est PAS appelé depuis le chemin de
        // dessin — le rafraîchissement des code actions est débounce via
        // scheduleCodeActionsRefresh() (dans onTextChanged et sur les grands
        // changements de scroll/sélection) afin de ne pas bloquer le thread UI
        // avec des appels LSP à chaque frame.

        // 2. Bande de ligne courante (seulement curseur réduit — pas de bande pendant une sélection)
        if (sel.isCursor()) {
            float lineY = view.docLineToY(currentLine) - view.vOffset;
            int rows = view.wordWrap ? view.rowsForDocLine(currentLine) : 1;
            float bandH = lineHeight * rows;
            if (lineY < height && lineY + bandH > 0) {
                view.bgPaint.setColor(view.theme.currentLine);
                canvas.drawRect(gutterWidth, lineY, width, lineY + bandH, view.bgPaint);
            }
        }

        // 3. Guides d'indentation (fins traits verticaux à chaque INDENT_UNIT_COLS) — dessinés avant le texte
        highlights.drawIndentGuides(canvas, doc, firstVisible, lastVisible, textAreaLeft, lineHeight, paddingTop);

        // La gouttière est dessinée APRÈS le texte (en overlay
        // semi-transparent) pour que le texte qui défile derrière reste
        // faiblement visible. Seuls les chevrons de pli sont dessinés ici
        // (dans le clip de la gouttière) afin d'être au-dessus du texte ; le
        // fond complet de la gouttière + les numéros de ligne sont dessinés
        // plus loin.

        // 5. Surlignage de sélection (bande par ligne, un rect par ligne visible)
        if (!sel.isCursor()) {
            try {
                highlights.drawSelection(canvas, doc, sel, textAreaLeft, lineHeight, paddingTop, firstVisible, lastVisible);
            } catch (RuntimeException ignored) {
            }
        }

        // 6. Surlignages de recherche (chaque occurrence teintée ; l'occurrence
        //    courante plus fortement). Dessinés AVANT le texte pour que les
        //    couleurs de syntaxe soient au-dessus.
        if (!view.findHighlights.isEmpty()) {
            try {
                highlights.drawFindHighlights(canvas, doc, textAreaLeft, lineHeight, paddingTop,
                                    firstVisible, lastVisible);
            } catch (RuntimeException ignored) {}
        }

        // 6.5. Surlignages de document — occurrences LSP du symbole sous le
        //      curseur. Dessinés en rectangle à teinte douce derrière le texte
        //      (entre les surlignages de recherche et le texte).
        if (!view.documentHighlights.isEmpty()) {
            try {
                highlights.drawDocumentHighlights(canvas, doc, textAreaLeft, lineHeight,
                    paddingTop, firstVisible, lastVisible);
            } catch (RuntimeException ignored) {}
        }

        // Quand le mode aperçu est actif, la zone de texte est clippée pour
        // exclure le panneau d'aperçu. En mode FULL, aucun texte n'est dessiné.
        int effectiveWidth = width;
        if (view.previewMode == EditorView.PreviewMode.SPLIT) {
            effectiveWidth = width / 2;
        } else if (view.previewMode == EditorView.PreviewMode.FULL) {
            view.selPaint.setColor(view.theme.gutterBorder);
            view.selPaint.setStrokeWidth(1f);
            canvas.drawLine(0, 0, 0, height, view.selPaint);
            return;
        }

        // 7. Texte — PAS de clip gouttière ! Le texte est dessiné pleine
        //    largeur pour défiler sous la zone de gouttière ; la gouttière
        //    (semi-transparente) est dessinée PAR-DESSUS le texte ensuite,
        //    créant l'effet verre. Texte pour les lignes de tête de pli +
        //    inlay hints tissés dans la ligne + tokens sémantiques par-dessus
        //    les spans lexicaux. Sensible au retour à la ligne : chaque ligne
        //    du document peut s'étaler sur plusieurs rangées visuelles,
        //    rendues séparément. Le cache de rendu par ligne fournit le
        //    StyledLine + inlays filtrés + spans sémantiques filtrés afin que
        //    le chemin de dessin soit O(lignes visibles) et non
        //    O(lignes visibles × nombre global de tokens).
        //
        //    Les soulignements ondulés sont dessinés DANS ce bloc clipRect
        //    afin de ne jamais déborder dans la zone de gouttière : sans
        //    clip, un soulignement à la colonne 0 sur une ligne défilée à
        //    droite (hOffset > 0) se dessinerait par-dessus la gouttière —
        //    il semblerait flotter au-dessus au lieu d'être masqué derrière
        //    elle.
        // Clip sur effectiveWidth (exclut le panneau d'aperçu) mais PAS sur
        // gutterWidth — le texte défile sous la gouttière et l'overlay de
        // gouttière est dessiné par-dessus ensuite.
        canvas.save();
        canvas.clipRect(0, 0, effectiveWidth, height);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
        int docStart = Math.min(firstVisible, firstDocVisible);
        int docEnd = Math.max(lastVisible, lastDocVisible);
        for (int i = docStart; i <= docEnd; i++) {
            // Plis : sauter entièrement les lignes cachées. Elles n'occupent
            // aucune rangée visuelle.
            if (view.isLineFoldedCached(i)) continue;
            float lineY = view.docLineToY(i) - view.vOffset;
            String lineText = doc.lineText(i);

            // Texte composite pour la ligne de DÉBUT d'un pli réduit :
            // "if (x) {" + "…" + "}" sur une seule rangée visuelle.
            DiagnosticShift.FoldRegion collapsed = view.collapsedFoldStartingAtLine(i);
            if (collapsed != null) {
                text.drawCompositeFoldLine(canvas, doc, collapsed, textAreaLeft - view.hOffset, lineY, view.textPaint);
                continue;
            }

            if (view.wordWrap && view.wrapModel != null && view.wrapModel.rowsOf(i) > 1) {
                // Ligne repliée (wrap) : rendre chaque rangée visuelle séparément.
                text.drawWrappedLine(canvas, doc, styledLines, i, lineText, textAreaLeft, lineY, lineHeight);
            } else {
                // Recherche dans le cache : hit O(1) sur les lignes inchangées,
                // recalcule les inlays/spans sémantiques filtrés + les cartes de
                // colonnes en cas de miss.
                LineRenderCache.LineCacheEntry layout = view.layoutForLine(i, lineText);
                StyledLine styled = layout != null ? (StyledLine) layout.layout : null;
                if (styled != null) {
                    text.drawStyledLine(canvas, styled, lineText, textAreaLeft - view.hOffset, lineY, view.textPaint,
                        layout.inlays, layout.rawToVisual);
                    // Dessiner les caractères non imprimables si activé.
                    if (view.showNonPrintable) {
                        text.drawNonPrintableChars(canvas, lineText, textAreaLeft - view.hOffset, lineY,
                            view.metrics.getCharWidth(), lineHeight, layout.inlays, layout.rawToVisual);
                    }
                    // Overlay de tokens sémantiques (recolore des plages
                    // précises avec les types de tokens fournis par le serveur
                    // — ex. méthode/classe/enum). Utilise les spans filtrés par
                    // ligne mis en cache.
                    if (layout.semSpans != null && !layout.semSpans.isEmpty()) {
                        text.drawCachedSemSpans(canvas, layout.semSpans, lineText,
                            textAreaLeft - view.hOffset, lineY, view.textPaint,
                            layout.inlays, layout.rawToVisual);
                    }
                    // Inlay hints : texte fantôme tissé DANS la ligne (le texte
                    // après un hint est décalé à droite).
                    if (layout.inlays != null && !layout.inlays.isEmpty()) {
                        text.drawCachedInlays(canvas, layout.inlays, layout.rawToVisual, lineY, view.textPaint);
                    }
                } else {
                    view.textPaint.setColor(view.theme.textColor);
                    canvas.drawText(lineText, textAreaLeft - view.hOffset, lineY + lineHeight * 0.78f, view.textPaint);
                }
            }
        }
        // Les inlay hints situés sur des lignes HORS de la plage visible
        // chaude du cache (rare — seulement quand le cache vient d'être vidé)
        // sont dessinés via le chemin historique d'itération globale afin de
        // n'en manquer aucun. text.drawInlayHints() n'est volontairement PAS
        // appelé ici — les inlays de chaque ligne visible sont dessinés par
        // ligne via text.drawCachedInlays() ci-dessus.

        // Soulignements ondulés de diagnostic — dessinés DANS le clip de la
        // zone de texte afin de ne jamais déborder dans la gouttière : sans
        // clip, un soulignement à la colonne 0 sur une ligne défilée à droite
        // (hOffset > 0) se dessinerait par-dessus la gouttière — il
        // semblerait flotter au-dessus au lieu d'être masqué derrière elle.
        diagnostics.drawSquiggles(canvas, doc, firstVisible, lastVisible, textAreaLeft, lineHeight, paddingTop);

        // Encadrés de parenthèses appariées — rectangle de CONTOUR 1 px
        // autour de chaque parenthèse de la paire sous/derrière le curseur,
        // dans la couleur d'accent à 45 % d'alpha. Dessinés après les
        // soulignements ondulés et dans le clip de la zone de texte.
        highlights.drawBracketMatchBoxes(canvas, doc, textAreaLeft, lineHeight, firstVisible, lastVisible);

        // Décorations de texte + inlays de plugins, par-dessus les couches
        // propres de l'éditeur (dans le clip pour ne jamais déborder dans la
        // gouttière).
        if (!pluginFrame.isEmpty()) {
            drawPluginDecorations(canvas, doc, pluginFrame, textAreaLeft, lineHeight);
        }

        canvas.restore();

        // Dessiner la gouttière PAR-DESSUS le texte en overlay
        // semi-transparent : effet verre — le texte qui défile derrière la
        // gouttière reste faiblement visible à travers le fond à 88 % d'alpha.
        canvas.save();
        canvas.clipRect(0, 0, gutterWidth, height);
        // Marques de gouttière des plugins (barres façon VCS blame).
        if (!pluginFrame.gutterMarks.isEmpty()) {
            java.util.Map<Integer, Integer> marks = new java.util.HashMap<>(pluginFrame.gutterMarks.size() * 2);
            for (EditorDecorations.GutterMark gm : pluginFrame.gutterMarks) {
                marks.put(gm.line, gm.color);
            }
            view.gutterView.setPluginMarks(marks);
        } else {
            view.gutterView.setPluginMarks(null);
        }
        view.gutterView.draw(canvas, view.vOffset, height, doc.lineCount(), currentLine);
        chrome.drawFoldChevrons(canvas, doc, firstVisible, lastVisible, lineHeight, paddingTop);
        canvas.restore();

        // Ligne de séparation de l'aperçu en mode SPLIT.
        if (view.previewMode == EditorView.PreviewMode.SPLIT) {
            int dividerX = width / 2;
            view.selPaint.setColor(view.theme.gutterBorder);
            view.selPaint.setStrokeWidth(1f);
            canvas.drawLine(dividerX, 0, dividerX, height, view.selPaint);
        }

        // 9. Curseur
        highlights.drawCaret(canvas, doc, sel, textAreaLeft, lineHeight, paddingTop);

        // 10. Poignées de sélection (mobile) — dessinées après le curseur
        //     pour être au-dessus du texte et faciles à saisir.
        highlights.drawSelectionHandles(canvas);

        // 11. Popup de complétion (dessiné en dernier pour être au-dessus de tout)
        assist.drawCompletionPopup(canvas);

        // 12. Popup d'aide de signature (dessiné en dernier — au-dessus de la ligne du curseur)
        assist.drawSignatureHelpPopup(canvas);

        // 13. Popup de documentation rapide
        assist.drawQuickDocPopup(canvas);

        // 14. Ampoules de code actions + popup
        assist.drawCodeActionsBulbs(canvas, firstVisible, lastVisible, lineHeight, paddingTop);
        assist.drawCodeActionsPopup(canvas);
        // 14.5. Menu contextuel unifié (NavMenu — toolbar de sélection)
        chrome.drawNavMenu(canvas);

        // 15. Popup d'aller-au-symbole
        assist.drawGoToSymbolPopup(canvas);

        // 16. Couches overlay : chips de diagnostic + toolbar de sélection +
        //     sheet de diagnostic. (Le go-to-line et le rename utilisent de
        //     vrais PopupWindow Android, donc rien à dessiner sur le Canvas.)
        if (view.diagnosticChipsEnabled) {
            diagnostics.drawDiagnosticChips(canvas, doc, firstVisible, lastVisible, lineHeight, paddingTop);
        }
        diagnostics.drawDiagnosticListSheet(canvas);
        chrome.drawSelectionToolbar(canvas);
        diagnostics.drawDiagnosticPopup(canvas);
        diagnostics.drawDiagnosticSheet(canvas);

        // Dessiner les icônes d'aperçu en haut à droite quand le fichier est
        // prévisualisable (.md/.html/.xml) et que le mode d'aperçu est NONE.
        chrome.drawPreviewIcons(canvas);
        // Dessiner l'aperçu de layout XML si actif.
        if (view.isXmlPreviewActive()) {
            chrome.drawXmlPreview(canvas);
        }
        // Les icônes de toolbar (A+, A-, ¶, ==) vivent dans la vue EditorBarTools.
        // Dessiner la loupe si active (actuellement désactivée).
        chrome.drawMagnifier(canvas);

        // Dessiner la minimap sur le bord droit.
        if (view.minimapEnabled) {
            chrome.drawMinimap(canvas);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Décorations de plugins (EditorPainterHost)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Dessine les décorations de plugins de la frame :
     * <ul>
     *   <li><b>Décorations de texte</b> — soulignement / encadré / barré
     *       sur des plages du document, sensibles au wrap (une plage sur
     *       plusieurs rangées repliées dessine un segment par rangée),
     *       dessinées par-dessus les soulignements ondulés dans le clip de
     *       la zone de texte ;</li>
     *   <li><b>Inlays de plugins</b> — texte fantôme après la fin de ligne
     *       à 85 % de la taille. Quand la ligne porte une chip de
     *       diagnostic, l'inlay démarre après la chip (+8dp) pour que les
     *       deux ne se chevauchent jamais.</li>
     * </ul>
     * Les décorations sont passives (non hit-testables) par design — les
     * overlays interactifs restent les couches propres de l'éditeur.
     */
    void drawPluginDecorations(Canvas canvas, EditorDocument doc,
                               EditorPainterHost.Frame frame,
                               float textAreaLeft, float lineHeight) {
        float density = view.getResources().getDisplayMetrics().density;
        float charWidth = view.metrics.getCharWidth();

        // ── Décorations de texte ──────────────────────────────────
        for (EditorDecorations.TextDecoration td : frame.textDecorations) {
            int start = clamp(td.start, 0, doc.length());
            int end = clamp(td.end, start, doc.length());
            if (end <= start) continue;
            int startLine = doc.lineForOffset(start);
            int endLine = doc.lineForOffset(Math.max(start, end - 1));
            for (int line = startLine; line <= endLine; line++) {
                if (view.isLineFoldedCached(line)) continue;
                float lineY = view.docLineToY(line) - view.vOffset;
                if (lineY + lineHeight < 0 || lineY > view.getHeight()) continue;
                int lineStart = doc.lineStart(line);
                int lineEnd = doc.lineEnd(line);
                int s = Math.max(start, lineStart) - lineStart;
                int e = Math.min(end, lineEnd) - lineStart;
                if (e <= s) continue;
                int lineLen = lineEnd - lineStart;
                // Sensible au wrap : un segment par rangée repliée touchée
                // par la plage (géométrie mono-rangée quand le retour à la
                // ligne est inactif).
                EditorView.WrapRows wr = view.wrapRowsFor(line, lineLen);
                int rowA = wr.rowForCol(s);
                int rowB = wr.rowForCol(Math.max(s, e - 1));
                for (int row = rowA; row <= rowB; row++) {
                    int rs = Math.max(s, wr.rowStartCol(row));
                    int re = Math.min(e, wr.rowEndCol(row, lineLen));
                    if (re <= rs) continue;
                    float rowY = lineY + row * lineHeight;
                    float rowIndent = row == 0 ? 0f : wr.wrapIndentCols * charWidth;
                    float x1 = textAreaLeft - view.hOffset + rowIndent + rs * charWidth;
                    float x2 = textAreaLeft - view.hOffset + rowIndent + re * charWidth;
                    switch (td.style) {
                        case EditorDecorations.DecorationStyles.BOX:
                            view.selPaint.setStyle(Paint.Style.STROKE);
                            view.selPaint.setStrokeWidth(1f);
                            view.selPaint.setColor(td.color);
                            canvas.drawRoundRect(x1, rowY + lineHeight * 0.08f,
                                    x2, rowY + lineHeight * 0.92f, 3f, 3f, view.selPaint);
                            view.selPaint.setStyle(Paint.Style.FILL);
                            view.selPaint.setStrokeWidth(1f);
                            break;
                        case EditorDecorations.DecorationStyles.STRIKE_THROUGH:
                            view.selPaint.setColor(td.color);
                            view.selPaint.setStyle(Paint.Style.FILL);
                            canvas.drawRect(x1, rowY + lineHeight * 0.5f - 1f,
                                    x2, rowY + lineHeight * 0.5f + 1f, view.selPaint);
                            break;
                        case EditorDecorations.DecorationStyles.UNDERLINE:
                        default:
                            view.selPaint.setColor(td.color);
                            view.selPaint.setStyle(Paint.Style.FILL);
                            canvas.drawRect(x1, rowY + lineHeight * 0.86f,
                                    x2, rowY + lineHeight * 0.86f + 2.5f * density,
                                    view.selPaint);
                            break;
                    }
                }
            }
        }

        // ── Inlays de plugins ──────────────────────────────────
        for (EditorDecorations.PluginInlay inlay : frame.pluginInlays) {
            int off = clamp(inlay.offset, 0, doc.length());
            int line = doc.lineForOffset(off);
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int lineLen = doc.lineEnd(line) - lineStart;
            EditorView.WrapRows wr = view.wrapRowsFor(line, lineLen);
            int lastRow = wr.rows - 1;
            // X après la fin visuelle de ligne (reflète les règles de
            // placement de la chip de diagnostic pour que les deux
            // s'empilent au lieu de se chevaucher).
            float x;
            if (view.wordWrap && view.wrapModel != null && lastRow > 0) {
                int rowStart = wr.rowStartCol(lastRow);
                int rowEnd = wr.rowEndCol(lastRow, lineLen);
                x = textAreaLeft + wr.wrapIndentCols * charWidth
                        + (rowEnd - rowStart) * charWidth + charWidth;
            } else {
                int visualLen = view.visualColFor(line, lineLen);
                x = textAreaLeft + visualLen * charWidth - view.hOffset + charWidth;
            }
            List<DiagnosticShift.Diagnostic> chip = view.chipDiagnosticsForLine(line);
            if (!chip.isEmpty()) {
                float[] cm = view.diagnosticChipMetrics(chip.get(0), line, chip.size());
                if (cm != null && cm[0] + cm[2] + 8 * density > x) {
                    x = cm[0] + cm[2] + 8 * density;
                }
            }
            float y = view.docLineToY(line) + lastRow * lineHeight - view.vOffset;
            if (y + lineHeight < 0 || y > view.getHeight()) continue;
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
            view.textPaint.setColor(inlay.color);
            canvas.drawText(inlay.text, x,
                    y + lineHeight * 0.5f
                        - (view.textPaint.ascent() + view.textPaint.descent()) * 0.5f,
                    view.textPaint);
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
        view.selPaint.setStyle(Paint.Style.FILL);
    }

    // Aides locales (délégation à EditorView quand partagées)
    // ════════════════════════════════════════════════════════════════

    /**
     * Délègue à {@link EditorView#clamp(int, int, int)} pour que les
     * méthodes de dessin puissent appeler {@code clamp(...)} nu, sans
     * qualificateur de classe.
     */
    private static int clamp(int v, int lo, int hi) {
        return EditorView.clamp(v, lo, hi);
    }

    // ════════════════════════════════════════════════════════════════
    // API conservée — appelée par EditorView et les tests
    // ════════════════════════════════════════════════════════════════

    /** Délègue à {@link EditorHighlightPainter} (tests des guides d'indentation). */
    static int leadingIndentOrBlank(String s) { return EditorHighlightPainter.leadingIndentOrBlank(s); }

    /** Délègue à {@link EditorDiagnosticsPainter} (smoke test du popup de diagnostic). */
    void drawDiagnosticPopup(Canvas canvas) { diagnostics.drawDiagnosticPopup(canvas); }

    /** Délègue à {@link EditorAssistPopupPainter} (hit-tests du quick doc sur EditorView). */
    public float[] quickDocMetrics() { return assist.quickDocMetrics(); }

    /** Délègue à {@link EditorAssistPopupPainter} (smoke test du badge de complétion). */
    void drawCompletionPopup(Canvas canvas) { assist.drawCompletionPopup(canvas); }
}
