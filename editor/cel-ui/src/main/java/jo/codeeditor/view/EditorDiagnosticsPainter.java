package jo.codeeditor.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Rendu des diagnostics de l'éditeur : soulignements ondulés sous le
 * code fautif, chips de diagnostic en fin de ligne (une par ligne,
 * regroupées avec badge de comptage), popup sheet par diagnostic
 * (message complet + quick fixes), sheet basse listant tous les
 * diagnostics et sheet groupée par ligne.
 *
 * <p>Extrait d'EditorRenderer par composition — isole la couche
 * diagnostics du reste du pipeline (vers un futur sous-package
 * render/). Tout accès à l'état passe par la référence {@code view}
 * (champs package-privés d'EditorView, même package). La géométrie
 * des sheets est partagée avec les hit-tests tactiles via
 * {@link EditorView#diagnosticSheetMetrics} et
 * {@link EditorView#diagnosticListSheetMetrics}.</p>
 */
class EditorDiagnosticsPainter {
    private final EditorView view;

    // Objet de travail réutilisable — évite les allocations par frame.
    // Usage mono-thread (uniquement depuis onDraw sur le thread UI).
    private final Path scratchPath = new Path();

    // Soulignement ondulé de diagnostic
    static final float SQUIGGLE_PERIOD = 6f;
    static final float SQUIGGLE_AMPLITUDE = 2f;
    // Couches overlay (chips de diagnostic / popup / sheet)
    static final float OVERLAY_RADIUS_DP = 6f;
    static final float OVERLAY_ROW_HEIGHT_DP = 26f;

    EditorDiagnosticsPainter(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Soulignements ondulés de diagnostic
    // ════════════════════════════════════════════════════════════════

    void drawSquiggles(Canvas canvas, EditorDocument doc,
                        int firstVisible, int lastVisible,
                        float textAreaLeft, float lineHeight, float paddingTop) {
        List<DiagnosticShift.Diagnostic> diags = view.session.getDiagnostics();
        if (diags.isEmpty()) return;
        float charWidth = view.metrics.getCharWidth();
        for (DiagnosticShift.Diagnostic d : diags) {
            int line = doc.lineForOffset(d.start);
            if (line < firstVisible || line > lastVisible) continue;
            // Sauter les soulignements des lignes cachées (repliées).
            if (view.isLineFoldedCached(line)) continue;
            int lineStart = doc.lineStart(line);
            int lineEnd = doc.lineEnd(line);
            int startCol = EditorView.clamp(d.start - lineStart, 0, lineEnd - lineStart);
            int endCol = EditorView.clamp(d.end - lineStart, 0, lineEnd - lineStart);
            if (endCol <= startCol) endCol = startCol + 1;
            float y = view.docLineToY(line) - view.vOffset; // Y sensible aux plis
            float baseY = y + lineHeight - 2;
            view.squigglePaint.setColor(getSquiggleColor(d.severity));
            view.squigglePaint.setStrokeWidth(1.4f);
            view.squigglePaint.setStyle(Paint.Style.STROKE);
            view.squigglePaint.setAntiAlias(true);
            // Colonnes visuelles sensibles aux inlays — le soulignement
            // couvre la même plage écran que le texte (décalé) qu'il
            // souligne.
            float x1 = textAreaLeft + view.visualColFor(line, startCol) * charWidth - view.hOffset;
            float x2 = textAreaLeft + view.visualColFor(line, endCol) * charWidth - view.hOffset;
            Path path = scratchPath;
            path.reset();
            path.moveTo(x1, baseY);
            float step = SQUIGGLE_PERIOD / 2;
            float x = x1;
            boolean up = true;
            while (x < x2) {
                float nx = Math.min(x + step, x2);
                path.lineTo(nx, up ? baseY - SQUIGGLE_AMPLITUDE : baseY + SQUIGGLE_AMPLITUDE);
                x = nx;
                up = !up;
            }
            canvas.drawPath(path, view.squigglePaint);
        }
    }

    private int getSquiggleColor(int severity) {
        switch (severity) {
            case 3: return view.theme.error;
            case 2: return view.theme.warning;
            default: return view.theme.info;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Overlay des chips de diagnostic
    // ════════════════════════════════════════════════════════════════

    /**
     * UNE pilule par ligne (le diagnostic Erreur/Avertissement le plus
     * sévère), placée après la fin de ligne + un écart de 3 caractères,
     * centrée verticalement sur la rangée. Fond de couleur de sévérité à
     * 16 % d'alpha (forme pilule complète, sans bordure), icône point de
     * couleur de sévérité + message semi-gras. La toucher ouvre la sheet
     * de diagnostic (hit-test dans EditorInputHandler.handleTap via
     * EditorView.findDiagnosticChipHitAt).
     *
     * <p>La couche des chips est clippée à la zone de code (à droite de la
     * gouttière) pour qu'une chip qui défile à gauche glisse SOUS la
     * gouttière au lieu de la chevaucher. Les diagnostics Info/Hint n'ont
     * PAS de chip (soulignement ondulé + point de gouttière
     * uniquement).</p>
     *
     * <p><b>Regroupement par ligne :</b> les groupes par ligne viennent des
     * buckets par ligne de début mémoïsés par la session (pas de HashMap
     * de toute la liste à chaque frame), et une ligne portant plusieurs
     * diagnostics Erreur/Avertissement reçoit un badge de comptage à
     * l'extrémité droite de sa pilule — le toucher ouvre la sheet groupée
     * où chaque diagnostic de la ligne est atteignable, au lieu du seul
     * plus sévère.</p>
     */
    void drawDiagnosticChips(Canvas canvas, EditorDocument doc,
                              int firstVisible, int lastVisible,
                              float lineHeight, float paddingTop) {
        // ★ chipExtent : remis à zéro à chaque frame puis étendu par la
        // chip la plus à droite — maxH() l'utilise pour rendre scrollable
        // le débordement de chip.
        view.chipExtentContentX = 0f;
        if (!view.diagnosticChipsEnabled) return;
        if (view.session.getDiagnostics().isEmpty()) return;
        canvas.save();
        canvas.clipRect(view.metrics.getGutterWidth(), 0, view.getWidth(), view.getHeight());
        for (int line = firstVisible; line <= lastVisible; line++) {
            if (view.isLineFoldedCached(line)) continue;
            List<DiagnosticShift.Diagnostic> group = view.chipDiagnosticsForLine(line);
            if (group.isEmpty()) continue;
            DiagnosticShift.Diagnostic d = group.get(0);
            float[] m = view.diagnosticChipMetrics(d, line, group.size());
            if (m == null) continue;
            float x = m[0], y = m[1], w = m[2], h = m[3], iconR = m[4],
                    iconGap = m[5], padX = m[6], badgeD = m[7], badgeGap = m[8];
            int color = getSquiggleColor(d.severity);
            RectF rect = new RectF(x, y, x + w, y + h);
            // Fond de pilule : couleur de sévérité à 16 % d'alpha.
            view.bgPaint.setColor(color);
            view.bgPaint.setAlpha(41); // 0.16 × 255
            canvas.drawRoundRect(rect, h * 0.5f, h * 0.5f, view.bgPaint);
            view.bgPaint.setAlpha(255);
            // Substitut d'icône : un point rempli de couleur de sévérité
            // (un point se lit aussi bien à cette taille).
            view.selPaint.setColor(color);
            view.selPaint.setAntiAlias(true);
            canvas.drawCircle(x + padX + iconR, y + h * 0.5f, iconR, view.selPaint);
            // Label : taille code pleine, semi-gras, couleur de sévérité, une ligne.
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize());
            view.textPaint.setFakeBoldText(true);
            view.textPaint.setColor(color);
            String label = d.message != null ? d.message : "";
            // Retrancher à l'identique de diagnosticChipMetrics (dimensionnement partagé).
            float avail = view.getWidth() - x - 4 * view.getResources().getDisplayMetrics().density
                - padX * 2 - iconR * 2 - iconGap - (badgeD > 0 ? badgeD + badgeGap : 0f);
            if (label.length() > 90) label = label.substring(0, 88) + "…";
            while (label.length() > 1 && view.textPaint.measureText(label) > avail) {
                label = label.substring(0, label.length() - 2) + "…";
            }
            float baseline = y + h * 0.5f
                - (view.textPaint.ascent() + view.textPaint.descent()) * 0.5f;
            canvas.drawText(label, x + padX + iconR * 2 + iconGap, baseline, view.textPaint);
            float textW = view.textPaint.measureText(label);
            // Badge de comptage : cercle rempli de couleur de sévérité +
            // compteur blanc, seulement quand la ligne porte plusieurs
            // diagnostics.
            if (badgeD > 0) {
                float bcx = x + padX + iconR * 2 + iconGap + textW + badgeGap + badgeD * 0.5f;
                view.selPaint.setColor(color);
                canvas.drawCircle(bcx, y + h * 0.5f, badgeD * 0.5f, view.selPaint);
                String count = String.valueOf(group.size());
                view.textPaint.setTextSize(h * 0.55f);
                view.textPaint.setColor(0xFFFFFFFF);
                float cw = view.textPaint.measureText(count);
                float cbaseline = y + h * 0.5f
                    - (view.textPaint.ascent() + view.textPaint.descent()) * 0.5f;
                canvas.drawText(count, bcx - cw * 0.5f, cbaseline, view.textPaint);
                view.textPaint.setTextSize(view.metrics.getTextSize());
                view.textPaint.setColor(color);
            }
            // ★ Étendue CONTENU (hors gouttière, indépendante du scroll :
            // +hOffset annule la soustraction écran) de la chip — maxH()
            // l'utilise pour rendre scrollable le débordement.
            float extent = x - view.metrics.getGutterWidth() + view.hOffset
                    + padX + iconR * 2 + iconGap + textW
                    + (badgeD > 0 ? badgeGap + badgeD : 0f) + padX;
            if (extent > view.chipExtentContentX) {
                view.chipExtentContentX = extent;
            }
            view.textPaint.setFakeBoldText(false);
        }
        canvas.restore();
    }

    // ════════════════════════════════════════════════════════════════
    // Popup de diagnostic (sheet par diagnostic)
    // ════════════════════════════════════════════════════════════════

    /**
     * Sheet basse modale pour UN diagnostic. Layout (géométrie partagée
     * via {@link EditorView#diagnosticSheetMetrics}, aussi utilisée par le
     * hit-test du tap) :
     * <ul>
     *   <li><b>Scrim</b> sur l'éditeur au-dessus du panneau (tap = fermer) —
     *       la couche de gestes consomme les touchers tant que la sheet
     *       est ouverte.</li>
     *   <li><b>Panneau</b> ancré en bas avec coins du HAUT arrondis, fond
     *       glass et bordure hairline.</li>
     *   <li><b>Header</b> — point de sévérité + libellé de sévérité coloré +
     *       bouton de fermeture × rond.</li>
     *   <li><b>Message</b> — le texte COMPLET, wrappé (≤ 6 lignes).</li>
     *   <li><b>Quick fixes</b> — un divider + libellé + une rangée 44dp par
     *       action ; toucher une rangée l'applique.</li>
     * </ul>
     * Ouverte en touchant : le point de gouttière, une plage de
     * soulignement ondulé, ou une chip de diagnostic sur la ligne.
     */
    void drawDiagnosticPopup(Canvas canvas) {
        if (!view.diagnosticPopupVisible || view.diagnosticPopupItem == null || view.session == null) return;
        DiagnosticShift.Diagnostic d = view.diagnosticPopupItem;
        float density = view.getResources().getDisplayMetrics().density;
        float[] m = view.diagnosticSheetMetrics();
        if (m == null) return;
        float panelTop = m[0], panelBottom = m[1], actionStartY = m[2], actionRowH = m[3];
        float closeCx = m[4], closeCy = m[5], closeR = m[6];
        float width = view.getWidth();
        float radius = EditorView.DIAG_SHEET_RADIUS_DP * density;
        float padX = 16 * density;

        // 1. Scrim sur l'éditeur au-dessus du panneau (tap = fermer).
        view.bgPaint.setColor(android.graphics.Color.argb(96, 0, 0, 0));
        canvas.drawRect(0, 0, width, panelTop, view.bgPaint);

        // 2. Panneau — coins du HAUT arrondis uniquement (forme de sheet ancrée en bas).
        Path panel = scratchPath;
        panel.reset();
        panel.moveTo(0, panelBottom);
        panel.lineTo(0, panelTop + radius);
        panel.quadTo(0, panelTop, radius, panelTop);
        panel.lineTo(width - radius, panelTop);
        panel.quadTo(width, panelTop, width, panelTop + radius);
        panel.lineTo(width, panelBottom);
        panel.close();
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawPath(panel, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawPath(panel, view.caretPaint);

        // 3. Header — point de sévérité + libellé + bouton de fermeture.
        int color = getSquiggleColor(d.severity);
        view.selPaint.setColor(color);
        view.selPaint.setAntiAlias(true);
        canvas.drawCircle(padX + 5 * density, closeCy, 5 * density, view.selPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        view.textPaint.setFakeBoldText(true);
        view.textPaint.setColor(color);
        String sevLabel = d.severity == 3 ? "Error" : d.severity == 2 ? "Warning" : "Info";
        canvas.drawText(sevLabel, padX + 14 * density, closeCy + view.metrics.getTextSize() * 0.30f, view.textPaint);
        view.textPaint.setFakeBoldText(false);
        // Fermeture (×) : cercle en contour + glyphe.
        view.caretPaint.setStrokeWidth(1.2f * density);
        view.caretPaint.setColor(view.theme.gutterText);
        canvas.drawCircle(closeCx, closeCy, closeR * 0.62f, view.caretPaint);
        view.textPaint.setColor(view.theme.gutterText);
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
        float xGlyphW = view.textPaint.measureText("×") * 0.5f;
        canvas.drawText("×", closeCx - xGlyphW, closeCy + view.metrics.getTextSize() * 0.32f, view.textPaint);

        // 4. Message — texte complet, wrappé, ≤ DIAG_SHEET_MAX_MSG_LINES.
        view.textPaint.setColor(view.theme.textColor);
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        String msg = d.message != null ? d.message : "";
        float msgY = panelTop + EditorView.DIAG_SHEET_HEADER_DP * density
            + EditorView.DIAG_SHEET_MSG_LINE_DP * density * 0.8f;
        float maxMsgW = width - padX * 2;
        StringBuilder line = new StringBuilder();
        int drawn = 1;
        for (String word : msg.split("\\s+")) {
            String test = line.length() == 0 ? word : line + " " + word;
            if (view.textPaint.measureText(test) > maxMsgW && line.length() > 0) {
                canvas.drawText(line.toString(), padX, msgY, view.textPaint);
                msgY += EditorView.DIAG_SHEET_MSG_LINE_DP * density;
                if (++drawn > EditorView.DIAG_SHEET_MAX_MSG_LINES) break;
                line = new StringBuilder(word);
            } else {
                line = line.length() == 0 ? new StringBuilder(word) : line.append(" ").append(word);
            }
        }
        if (line.length() > 0 && drawn <= EditorView.DIAG_SHEET_MAX_MSG_LINES) {
            canvas.drawText(line.toString(), padX, msgY, view.textPaint);
        }

        // 5. Quick fixes — divider + libellé + rangées.
        int dl = view.session.getDocument().lineForOffset(d.start);
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(dl);
        if (actions != null && !actions.isEmpty()) {
            view.caretPaint.setStrokeWidth(1f);
            view.caretPaint.setColor(view.theme.glassBorder);
            canvas.drawLine(padX, actionStartY - EditorView.DIAG_SHEET_ACTIONS_BLOCK_DP * density * 0.72f,
                width - padX, actionStartY - EditorView.DIAG_SHEET_ACTIONS_BLOCK_DP * density * 0.72f, view.caretPaint);
            view.textPaint.setColor(view.theme.gutterText);
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
            view.textPaint.setFakeBoldText(true);
            canvas.drawText("QUICK FIXES", padX,
                actionStartY - EditorView.DIAG_SHEET_ACTIONS_BLOCK_DP * density * 0.28f, view.textPaint);
            view.textPaint.setFakeBoldText(false);
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            for (int i = 0; i < actions.size(); i++) {
                EditorView.CodeAction a = actions.get(i);
                float rowY = actionStartY + i * actionRowH;
                if (rowY + actionRowH > panelBottom) break;
                // Glyphe de rangée : point rempli — accent pour quickfix, atténué sinon.
                view.selPaint.setColor(a.kind != null && a.kind.equals("quickfix")
                    ? view.theme.keyword : view.theme.gutterText);
                canvas.drawCircle(padX + 3.2f * density, rowY + actionRowH * 0.5f, 3.2f * density, view.selPaint);
                view.textPaint.setColor(view.theme.textColor);
                canvas.drawText(a.title, padX + 12 * density, rowY + actionRowH * 0.5f
                    + view.metrics.getTextSize() * 0.32f, view.textPaint);
            }
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // Sheet de diagnostic (sheet basse listant tous les diagnostics)
    // ════════════════════════════════════════════════════════════════

    /** Dessine la sheet de diagnostic (sheet basse listant tous les diagnostics). */
    void drawDiagnosticSheet(Canvas canvas) {
        if (!view.diagnosticSheetVisible) return;
        List<DiagnosticShift.Diagnostic> diags = view.session.getDiagnostics();
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = OVERLAY_ROW_HEIGHT_DP * density;
        float radius = OVERLAY_RADIUS_DP * density;
        int maxRows = 8;
        int rowsToShow = Math.min(maxRows, diags.size() + 1);
        float sheetH = rowH * rowsToShow;
        float width = view.getWidth();
        float anchorX = 0;
        float anchorY = view.getHeight() - sheetH;
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + sheetH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRect(rect, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawLine(anchorX, anchorY, anchorX + width, anchorY, view.caretPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        // En-tête
        view.textPaint.setColor(view.theme.keyword);
        canvas.drawText("Diagnostics (" + diags.size() + ")",
            anchorX + 12 * density, anchorY + rowH * 0.7f, view.textPaint);
        // Rangées
        for (int i = 0; i < Math.min(rowsToShow - 1, diags.size()); i++) {
            int idx = view.diagnosticSheetScroll + i;
            if (idx >= diags.size()) break;
            DiagnosticShift.Diagnostic d = diags.get(idx);
            float y = anchorY + (i + 1) * rowH;
            String sev = d.severity == 3 ? "ERR" : d.severity == 2 ? "WRN" : "INF";
            view.textPaint.setColor(getSquiggleColor(d.severity));
            canvas.drawText(sev, anchorX + 12 * density, y + rowH * 0.7f, view.textPaint);
            String msg = (d.message == null ? "" : d.message);
            if (msg.length() > 60) msg = msg.substring(0, 57) + "...";
            view.textPaint.setColor(view.theme.textColor);
            canvas.drawText(msg, anchorX + 60 * density, y + rowH * 0.7f, view.textPaint);
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════

    // ═════════════════════════════════════════════════════════════════
    // Sheet de liste de diagnostics groupée
    // ═════════════════════════════════════════════════════════════════

    /**
     * Dessine la sheet de diagnostics GROUPÉE : une sheet basse modale
     * listant CHAQUE diagnostic dont le début se situe sur la ligne de la
     * chip. Même langage visuel que le popup par diagnostic (scrim,
     * panneau glass à coins du HAUT arrondis, bordure hairline, points de
     * sévérité). Toucher une rangée ouvre le popup de détail avec le
     * message complet + quick fixes ; la géométrie vient de
     * {@link EditorView#diagnosticListSheetMetrics} (partagée avec le
     * hit-test du tap). Sans elle, un avertissement caché derrière une
     * erreur sur la même ligne serait inatteignable depuis la chip.
     */
    void drawDiagnosticListSheet(Canvas canvas) {
        if (view.diagnosticListSheetLine < 0 || view.session == null) return;
        float[] m = view.diagnosticListSheetMetrics();
        if (m == null) return;
        float panelTop = m[0], panelBottom = m[1], headerH = m[2], rowH = m[3];
        int rows = (int) m[4];
        boolean more = m[5] > 0f;
        float closeCx = m[6], closeCy = m[7], closeR = m[8];
        float density = view.getResources().getDisplayMetrics().density;
        float width = view.getWidth();
        float radius = EditorView.DIAG_SHEET_RADIUS_DP * density;
        float padX = 16 * density;

        List<DiagnosticShift.Diagnostic> all =
                view.session.getDiagnosticsForLine(view.diagnosticListSheetLine);

        // 1. Scrim sur l'éditeur au-dessus du panneau (tap = fermer).
        view.bgPaint.setColor(android.graphics.Color.argb(96, 0, 0, 0));
        canvas.drawRect(0, 0, width, panelTop, view.bgPaint);

        // 2. Panneau — coins du HAUT arrondis uniquement (forme de sheet ancrée en bas).
        Path panel = scratchPath;
        panel.reset();
        panel.moveTo(0, panelBottom);
        panel.lineTo(0, panelTop + radius);
        panel.quadTo(0, panelTop, radius, panelTop);
        panel.lineTo(width - radius, panelTop);
        panel.quadTo(width, panelTop, width, panelTop + radius);
        panel.lineTo(width, panelBottom);
        panel.close();
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawPath(panel, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawPath(panel, view.caretPaint);

        // 3. En-tête — point de sévérité + « N problems on line L » + bouton de fermeture.
        int color = getSquiggleColor(all.get(0).severity);
        view.selPaint.setColor(color);
        view.selPaint.setAntiAlias(true);
        canvas.drawCircle(padX + 5 * density, closeCy, 5 * density, view.selPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        view.textPaint.setFakeBoldText(true);
        view.textPaint.setColor(color);
        String title = all.size() + (all.size() == 1 ? " problem" : " problems")
                + " on line " + (view.diagnosticListSheetLine + 1);
        canvas.drawText(title, padX + 14 * density,
                closeCy + view.metrics.getTextSize() * 0.30f, view.textPaint);
        view.textPaint.setFakeBoldText(false);
        // Fermeture (×) : cercle en contour + glyphe.
        view.caretPaint.setStrokeWidth(1.2f * density);
        view.caretPaint.setColor(view.theme.gutterText);
        canvas.drawCircle(closeCx, closeCy, closeR * 0.62f, view.caretPaint);
        view.textPaint.setColor(view.theme.gutterText);
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
        float xGlyphW = view.textPaint.measureText("×") * 0.5f;
        canvas.drawText("×", closeCx - xGlyphW,
                closeCy + view.metrics.getTextSize() * 0.32f, view.textPaint);

        // 4. Rangées — une par diagnostic de la ligne (point de sévérité + message).
        for (int i = 0; i < rows && i < all.size(); i++) {
            DiagnosticShift.Diagnostic d = all.get(i);
            float rowY = panelTop + headerH + i * rowH;
            view.selPaint.setColor(getSquiggleColor(d.severity));
            canvas.drawCircle(padX + 3.2f * density, rowY + rowH * 0.5f,
                    3.2f * density, view.selPaint);
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            view.textPaint.setColor(view.theme.textColor);
            String msg = d.message != null ? d.message : "";
            float avail = width - padX * 2 - 12 * density;
            while (msg.length() > 1 && view.textPaint.measureText(msg) > avail) {
                msg = msg.substring(0, msg.length() - 2) + "…";
            }
            canvas.drawText(msg, padX + 12 * density,
                    rowY + rowH * 0.5f + view.metrics.getTextSize() * 0.32f,
                    view.textPaint);
        }
        // 5. Rangée d'avis de troncature (non tappable).
        if (more) {
            float rowY = panelTop + headerH + rows * rowH;
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.8f);
            view.textPaint.setColor(view.theme.gutterText);
            String extra = "…" + (all.size() - EditorView.DIAG_LIST_MAX_ROWS) + " more";
            canvas.drawText(extra, padX + 12 * density,
                    rowY + rowH * 0.5f + view.metrics.getTextSize() * 0.30f,
                    view.textPaint);
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setStyle(Paint.Style.FILL);
    }
}
