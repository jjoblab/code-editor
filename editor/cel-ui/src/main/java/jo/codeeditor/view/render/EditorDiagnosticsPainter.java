package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.kit.PopupTokens;
import jo.codeeditor.view.popup.kit.TextLayoutKit;

import androidx.annotation.RestrictTo;

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
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorDiagnosticsPainter {
    private final EditorView view;

    // Objets de travail réutilisables — évitent les allocations par frame.
    // Usage mono-thread (uniquement depuis onDraw sur le thread UI).
    // P-05 (audit 3.69.0) : le RectF s'ajoute au Path — chaque pilule,
    // feuille et carte allouait un RectF par draw.
    private final Path scratchPath = new Path();
    private final RectF scratchRect = new RectF();

    /** Mesure du paint de texte courant, déléguée au kit de layout. */
    private final TextLayoutKit.Measurer mesureTexte;

    // Soulignement ondulé de diagnostic
    static final float SQUIGGLE_PERIOD = 6f;
    static final float SQUIGGLE_AMPLITUDE = 2f;
    // Voile gris du code inutilisé (tag LSP Unnecessary) — le grisé
    // Android Studio des imports morts : ~20 % d'alpha sur la plage.
    static final int UNNECESSARY_VEIL_COLOR = 0x338A8A8A;
    // Couches overlay (chips de diagnostic / popup / sheet)
    static final float OVERLAY_RADIUS_DP = 6f;
    static final float OVERLAY_ROW_HEIGHT_DP = 26f;

    EditorDiagnosticsPainter(EditorView view) {
        this.view = view;
        // Initialisés ici : la capture de view exige qu'elle soit posée.
        this.mesureTexte = t -> view.textPaint.measureText(t);
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
            float y = view.docLineToY(line) - view.scroll.vOffset; // Y sensible aux plis
            float baseY = y + lineHeight - 2;
            // Colonnes visuelles sensibles aux inlays — le soulignement
            // couvre la même plage écran que le texte (décalé) qu'il
            // souligne.
            float x1 = textAreaLeft + view.visualColFor(line, startCol) * charWidth - view.scroll.hOffset;
            float x2 = textAreaLeft + view.visualColFor(line, endCol) * charWidth - view.scroll.hOffset;
            // Tag Unnecessary (code inutilisé) : peinture ESTOMPÉE de la
            // plage (voile gris, texte atténué — le grisé Android Studio
            // des imports morts) AU LIEU du soulignement de sévérité :
            // l'information portée est « ce code ne sert à rien », pas
            // « cette ligne est fausse ».
            if (d.unnecessary) {
                view.squigglePaint.setStyle(Paint.Style.FILL);
                view.squigglePaint.setAntiAlias(false);
                view.squigglePaint.setColor(UNNECESSARY_VEIL_COLOR);
                canvas.drawRect(Math.min(x1, x2), y + 2,
                        Math.max(x1, x2), y + lineHeight - 2, view.squigglePaint);
                continue;
            }
            view.squigglePaint.setColor(getSquiggleColor(d.severity));
            view.squigglePaint.setStrokeWidth(1.4f);
            view.squigglePaint.setStyle(Paint.Style.STROKE);
            view.squigglePaint.setAntiAlias(true);
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
        // Source unique du mapping sévérité → couleur : les jetons.
        return PopupTokens.severity(view.theme, severity);
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
            RectF rect = scratchRect;
            rect.set(x, y, x + w, y + h);
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
            // Coupe de sécurité par caractères (90) puis ellipse PAR
            // LARGEur via le kit — la boucle historique à retrait de 2
            // caractères mesurait après CHAQUE itération et perdait un
            // caractère utile.
            if (label.length() > 90) label = label.substring(0, 90);
            label = TextLayoutKit.ellipsize(label, avail,
                    TextLayoutKit.Truncate.TAIL, mesureTexte);
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
            float extent = x - view.metrics.getGutterWidth() + view.scroll.hOffset
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

        // 1. Scrim sur l'éditeur au-dessus du panneau (tap = fermer) —
        // couleur du thème (plus d'argb en dur).
        view.bgPaint.setColor(view.theme.popupScrim);
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
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_BODY);
        view.textPaint.setFakeBoldText(true);
        view.textPaint.setColor(color);
        // D4 : en-tête enrichi — sévérité · code · source · ligne:col ·
        // badge ×N (champs vides omis, diagnostic nu = en-tête sobre).
        String header = jo.codeeditor.view.popup.EditorPopupAnchors
                .diagnosticHeaderLabel(view, d);
        canvas.drawText(header, padX + 14 * density, closeCy + view.metrics.getTextSize() * 0.30f, view.textPaint);
        view.textPaint.setFakeBoldText(false);
        // Fermeture (×) : cercle en contour + glyphe.
        view.caretPaint.setStrokeWidth(1.2f * density);
        view.caretPaint.setColor(view.theme.gutterText);
        canvas.drawCircle(closeCx, closeCy, closeR * 0.62f, view.caretPaint);
        view.textPaint.setColor(view.theme.gutterText);
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_TITLE);
        float xGlyphW = view.textPaint.measureText("×") * 0.5f;
        canvas.drawText("×", closeCx - xGlyphW, closeCy + view.metrics.getTextSize() * 0.32f, view.textPaint);

        // 4. Message — texte complet, wrappé, ≤ DIAG_SHEET_MAX_MSG_LINES.
        view.textPaint.setColor(view.theme.textColor);
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_BODY);
        String msg = d.message != null ? d.message : "";
        float msgY = panelTop + EditorView.DIAG_SHEET_HEADER_DP * density
            + EditorView.DIAG_SHEET_MSG_LINE_DP * density * 0.8f;
        float maxMsgW = width - padX * 2;
        // Wrap par le kit partagé (une mesure par mot, mot long coupé) —
        // même plafond de lignes que la boucle historique.
        java.util.List<String> msgLines = TextLayoutKit.wrap(
                msg, maxMsgW, mesureTexte);
        int maxLines = EditorView.DIAG_SHEET_MAX_MSG_LINES;
        for (int i = 0; i < Math.min(msgLines.size(), maxLines); i++) {
            canvas.drawText(msgLines.get(i), padX, msgY, view.textPaint);
            msgY += EditorView.DIAG_SHEET_MSG_LINE_DP * density;
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
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_MICRO);
            view.textPaint.setFakeBoldText(true);
            canvas.drawText(view.getResources().getString(
                    jo.codeeditor.ui.R.string.cel_diag_quick_fixes), padX,
                actionStartY - EditorView.DIAG_SHEET_ACTIONS_BLOCK_DP * density * 0.28f, view.textPaint);
            view.textPaint.setFakeBoldText(false);
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_TITLE);
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
        RectF rect = scratchRect;
        rect.set(anchorX, anchorY, anchorX + width, anchorY + sheetH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRect(rect, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawLine(anchorX, anchorY, anchorX + width, anchorY, view.caretPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_BODY);
        // En-tête — ressource (titre avec compte).
        view.textPaint.setColor(view.theme.keyword);
        canvas.drawText(view.getResources().getString(
                        jo.codeeditor.ui.R.string.cel_diag_sheet_title, diags.size()),
            anchorX + 12 * density, anchorY + rowH * 0.7f, view.textPaint);
        // Rangées
        for (int i = 0; i < Math.min(rowsToShow - 1, diags.size()); i++) {
            int idx = view.diagnosticSheetScroll + i;
            if (idx >= diags.size()) break;
            DiagnosticShift.Diagnostic d = diags.get(idx);
            float y = anchorY + (i + 1) * rowH;
            // Sévérités en ressources (surchargeables par l'hôte).
            String sev = d.severity == 3
                    ? view.getResources().getString(jo.codeeditor.ui.R.string.cel_diag_sev_error)
                    : d.severity == 2
                    ? view.getResources().getString(jo.codeeditor.ui.R.string.cel_diag_sev_warning)
                    : view.getResources().getString(jo.codeeditor.ui.R.string.cel_diag_sev_info);
            view.textPaint.setColor(getSquiggleColor(d.severity));
            canvas.drawText(sev, anchorX + 12 * density, y + rowH * 0.7f, view.textPaint);
            // Message ellipsé PAR LARGEUR (la coupe historique à 60
            // caractères rognait trop étroit / pas assez selon la police).
            String msg = d.message == null ? "" : d.message;
            float msgAvail = Math.max(0f, width - 60 * density - 12 * density);
            msg = TextLayoutKit.ellipsize(msg, msgAvail,
                    TextLayoutKit.Truncate.TAIL, mesureTexte);
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

        // 1. Scrim sur l'éditeur au-dessus du panneau (tap = fermer) —
        // couleur du thème (plus d'argb en dur).
        view.bgPaint.setColor(view.theme.popupScrim);
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
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_BODY);
        view.textPaint.setFakeBoldText(true);
        view.textPaint.setColor(color);
        String title = view.getResources().getQuantityString(
                jo.codeeditor.ui.R.plurals.cel_diag_problems, all.size(),
                all.size(), view.diagnosticListSheetLine + 1);
        canvas.drawText(title, padX + 14 * density,
                closeCy + view.metrics.getTextSize() * 0.30f, view.textPaint);
        view.textPaint.setFakeBoldText(false);
        // Fermeture (×) : cercle en contour + glyphe.
        view.caretPaint.setStrokeWidth(1.2f * density);
        view.caretPaint.setColor(view.theme.gutterText);
        canvas.drawCircle(closeCx, closeCy, closeR * 0.62f, view.caretPaint);
        view.textPaint.setColor(view.theme.gutterText);
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_TITLE);
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
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_TITLE);
            view.textPaint.setColor(view.theme.textColor);
            String msg = d.message != null ? d.message : "";
            float avail = width - padX * 2 - 12 * density;
            msg = TextLayoutKit.ellipsize(msg, avail,
                    TextLayoutKit.Truncate.TAIL, mesureTexte);
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
            String extra = view.getResources().getQuantityString(
                    jo.codeeditor.ui.R.plurals.cel_diag_more,
                    all.size() - EditorView.DIAG_LIST_MAX_ROWS,
                    all.size() - EditorView.DIAG_LIST_MAX_ROWS);
            canvas.drawText(extra, padX + 12 * density,
                    rowY + rowH * 0.5f + view.metrics.getTextSize() * 0.30f,
                    view.textPaint);
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setStyle(Paint.Style.FILL);
    }

    // ════════════════════════════════════════════════════════════════
    // Popup ancré de squiggle (D1)
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine le popup ancré de squiggle (D1) — le niveau 1 de la
     * lecture d'un diagnostic. Petite carte glass ancrée au-dessus de la
     * ligne (géométrie partagée
     * {@code EditorPopupAnchors.squigglePopupMetrics(view)}, aussi
     * utilisée par le hit-test) : <b>pas de scrim</b> (non modal — un tap
     * ailleurs referme, re-tap sur la plage → caret).
     *
     * <p>Deux modes : <b>mini-liste</b> (une rangée par diagnostic
     * superposé : point de sévérité + première ligne du message) et
     * <b>détail</b> (en-tête sévérité + message replié ≤ 3 lignes +
     * rangées de quick-fixes + rangée « See all » vers la fiche basse
     * existante — vue complète).</p>
     */
    void drawSquigglePopup(Canvas canvas) {
        jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m =
                jo.codeeditor.view.popup.EditorPopupAnchors.squigglePopupMetrics(view);
        if (m == null) return;
        float density = m.density;
        float radius = PopupTokens.RADIUS_CARD_DP * density;
        // UX-4 : les X du CONTENU viennent de la métrique (contentLeft =
        // m.x + padX) — le cadre ET son contenu suivent l'ancrage du mot.
        // Les largeurs disponibles restent calculées depuis le padding.
        float textX = m.contentLeft + 12 * density;
        float dotX = m.contentLeft + 3.2f * density;

        // 1. Carte glass (sans scrim — non modal).
        RectF rect = scratchRect;
        rect.set(m.x, m.y, m.x + m.w, m.y + m.h);
        view.bgPaint.setStyle(Paint.Style.FILL);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);
        view.caretPaint.setStyle(Paint.Style.FILL);

        // 2. Contenu par mode (UX-4 : X dérivés de contentLeft).
        if (m.isListMode()) {
            drawSquiggleMiniList(canvas, m, density, textX, dotX);
        } else {
            drawSquiggleDetail(canvas, m, density, textX, dotX);
        }
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
        view.caretPaint.setStrokeWidth(1f);
    }

    /** Mini-liste : une rangée par diagnostic (sévérité + 1re ligne).
     *  UX-4 : textX/dotX dérivés de {@code m.contentLeft} — le contenu
     *  suit la carte ancrée où qu'elle soit (gauche/droite). */
    private void drawSquiggleMiniList(Canvas canvas,
            jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m,
            float density, float textX, float dotX) {
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_TITLE);
        for (int i = 0; i < m.rows && i < m.choices.size(); i++) {
            DiagnosticShift.Diagnostic d = m.choices.get(i);
            float rowY = m.rowTop + i * m.rowH;
            view.selPaint.setColor(getSquiggleColor(d.severity));
            canvas.drawCircle(dotX, rowY + m.rowH * 0.5f,
                    3.2f * density, view.selPaint);
            view.textPaint.setColor(view.theme.textColor);
            String msg = firstLine(d.message);
            float avail = m.w - 2 * jo.codeeditor.view.popup.EditorPopupAnchors
                    .SQUIGGLE_POPUP_PAD_X_DP * density - 12 * density;
            msg = TextLayoutKit.ellipsize(msg, avail,
                    TextLayoutKit.Truncate.TAIL, mesureTexte);
            canvas.drawText(msg, textX,
                    rowY + m.rowH * 0.5f + view.metrics.getTextSize() * 0.32f,
                    view.textPaint);
        }
    }

    /** Détail : en-tête sévérité + message replié + quick-fixes + See all.
     *  UX-4 : textX/dotX dérivés de {@code m.contentLeft} — le contenu
     *  suit la carte ancrée où qu'elle soit (gauche/droite). */
    private void drawSquiggleDetail(Canvas canvas,
            jo.codeeditor.view.popup.EditorPopupAnchors.SquigglePopupMetrics m,
            float density, float textX, float dotX) {
        DiagnosticShift.Diagnostic d = m.item;
        int color = getSquiggleColor(d.severity);
        float padX = jo.codeeditor.view.popup.EditorPopupAnchors.SQUIGGLE_POPUP_PAD_X_DP
                * density;

        // En-tête : point de sévérité + libellé enrichi (D4 — sévérité ·
        // code · source · ligne:col · badge ×N), coloré (semibold).
        float headCy = m.y + m.headerH * 0.5f;
        view.selPaint.setColor(color);
        canvas.drawCircle(dotX, headCy, 3.2f * density, view.selPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_BODY);
        view.textPaint.setFakeBoldText(true);
        view.textPaint.setColor(color);
        String header = jo.codeeditor.view.popup.EditorPopupAnchors
                .diagnosticHeaderLabel(view, d);
        canvas.drawText(header, textX,
                headCy + view.metrics.getTextSize() * 0.30f, view.textPaint);
        view.textPaint.setFakeBoldText(false);

        // Message replié (≤ 3 lignes, motif de la fiche basse).
        view.textPaint.setColor(view.theme.textColor);
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_BODY);
        String msg = d.message != null ? d.message : "";
        float maxMsgW = m.w - padX * 2;
        float msgY = m.y + m.headerH
                + jo.codeeditor.view.popup.EditorPopupAnchors.SQUIGGLE_POPUP_MSG_LINE_DP
                * density * 0.8f;
        // Wrap par le kit (une mesure par mot) ; la dernière ligne
        // affichée est ellipsée PAR LARGEUR si le message déborde.
        java.util.List<String> msgLines = TextLayoutKit.wrap(
                msg, maxMsgW, mesureTexte);
        int shown = Math.min(msgLines.size(), m.msgLines);
        for (int i = 0; i < shown; i++) {
            String ligne = msgLines.get(i);
            if (i == shown - 1 && msgLines.size() > m.msgLines) {
                ligne = TextLayoutKit.ellipsize(ligne, maxMsgW,
                        TextLayoutKit.Truncate.TAIL, mesureTexte);
            }
            canvas.drawText(ligne, m.contentLeft, msgY, view.textPaint);
            msgY += jo.codeeditor.view.popup.EditorPopupAnchors.SQUIGGLE_POPUP_MSG_LINE_DP
                    * density;
        }

        // Quick-fixes (résolus à l'ouverture — rendus dès publication).
        int diagLine = view.session.getDocument().lineForOffset(d.start);
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(diagLine);
        if (actions != null && m.rows > 0) {
            view.textPaint.setColor(view.theme.gutterText);
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_MICRO);
            view.textPaint.setFakeBoldText(true);
            canvas.drawText(view.getResources().getString(
                    jo.codeeditor.ui.R.string.cel_diag_quick_fixes), m.contentLeft,
                    m.rowTop - 3 * density, view.textPaint);
            view.textPaint.setFakeBoldText(false);
            view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_TITLE);
            for (int i = 0; i < m.rows && i < actions.size(); i++) {
                EditorView.CodeAction a = actions.get(i);
                float rowY = m.rowTop + i * m.rowH;
                view.selPaint.setColor(a.kind != null && a.kind.equals("quickfix")
                        ? view.theme.keyword : view.theme.gutterText);
                canvas.drawCircle(dotX, rowY + m.rowH * 0.5f,
                        3.2f * density, view.selPaint);
                view.textPaint.setColor(view.theme.textColor);
                String title = a.title != null ? a.title : "";
                float avail = m.w - padX * 2 - 12 * density;
                title = TextLayoutKit.ellipsize(title, avail,
                        TextLayoutKit.Truncate.TAIL, mesureTexte);
                canvas.drawText(title, textX,
                        rowY + m.rowH * 0.5f + view.metrics.getTextSize() * 0.32f,
                        view.textPaint);
            }
        }

        // Rangée « See all » (accent + chevron ›).
        view.textPaint.setColor(view.theme.caret);
        view.textPaint.setTextSize(view.metrics.getTextSize() * PopupTokens.TEXT_TITLE);
        view.textPaint.setFakeBoldText(true);
        float seeAllCy = m.seeAllY + m.rowH * 0.5f;
        canvas.drawText(view.getResources().getString(
                jo.codeeditor.ui.R.string.cel_diag_see_all), textX,
                seeAllCy + view.metrics.getTextSize() * 0.32f, view.textPaint);
        view.textPaint.setFakeBoldText(false);
        view.caretPaint.setStrokeWidth(1.6f * density);
        view.caretPaint.setColor(view.theme.caret);
        float chevX = m.x + m.w - padX;
        canvas.drawLine(chevX - 4 * density, seeAllCy - 3.5f * density,
                chevX - 1.5f * density, seeAllCy, view.caretPaint);
        canvas.drawLine(chevX - 4 * density, seeAllCy + 3.5f * density,
                chevX - 1.5f * density, seeAllCy, view.caretPaint);
        view.caretPaint.setStrokeWidth(1f);
    }

    /** La première ligne d'un message (avant tout \n), sinon vide. */
    private static String firstLine(String message) {
        if (message == null || message.isEmpty()) return "";
        int nl = message.indexOf('\n');
        return nl >= 0 ? message.substring(0, nl) : message;
    }
}
