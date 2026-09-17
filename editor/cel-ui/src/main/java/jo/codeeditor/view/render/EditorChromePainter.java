package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.EditorPopupAnchors;
import jo.codeeditor.view.preview.EditorPreviewHost;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Chrome visuel de l'éditeur, autour de la zone de texte : minimap du
 * bord droit, panneau et icônes d'aperçu (XML/scindé/plein), loupe de
 * zoom, chevrons de pli dans la bande de gouttière, menu contextuel
 * unifié (NavMenu) et toolbar de sélection flottante.
 *
 * <p>Extrait d'EditorRenderer par composition — isole les couches de
 * chrome du pipeline principal (vers un futur sous-package render/).
 * Tout accès à l'état passe par la référence {@code view} (champs
 * package-privés d'EditorView, même package).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorChromePainter {
    private final EditorView view;
    private final EditorTextPainter text;

    // Objet de travail réutilisable — évite les allocations par frame.
    // Usage mono-thread (uniquement depuis onDraw sur le thread UI).
    private final Path scratchPath = new Path();

    // ── Géométrie de la minimap (déplacée d'EditorView) ───────────
    static final float MINIMAP_WIDTH_DP = 60f;
    static final float MINIMAP_LINE_HEIGHT_PX = 2.5f;  // px par ligne doc

    EditorChromePainter(EditorView view, EditorTextPainter text) {
        this.view = view;
        this.text = text;
    }

    /**
     * Dessine un aperçu miniature du fichier entier sur le bord droit de
     * l'éditeur. Chaque ligne du document est rendue comme une simple rangée
     * horizontale de segments colorés — réduite à
     * {@link #MINIMAP_LINE_HEIGHT_PX} px de haut et
     * {@link #MINIMAP_WIDTH_DP} dp de large. Un rectangle façon
     * scrollbar indique le viewport courant.
     *
     * <p>La minimap utilise les {@link StyledLine}s de la session (déjà
     * calculés par le {@link jo.codeeditor.highlight.SyntaxHighlighter}) afin
     * que les couleurs correspondent à l'éditeur. Seules les lignes visibles
     * (après le vOffset) plus quelques-unes au-dessus/en dessous sont
     * itérées — dessiner le fichier entier coûterait O(N) par frame, trop
     * lent pour des fichiers de 50 000 lignes.</p>
     */
    void drawMinimap(Canvas canvas) {
        if (view.session == null) return;
        EditorDocument doc = view.session.getDocument();
        int lineCount = doc.lineCount();
        if (lineCount <= 0) return;

        int minimapLeft = getMinimapLeft();
        int minimapWidth = getMinimapWidth();
        int height = view.getHeight();
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = MINIMAP_LINE_HEIGHT_PX * density;

        // Fond de la bande de minimap.
        view.bgPaint.setColor(view.applyAlphaToColor(view.theme.editorBg, 1f));
        // Légèrement différent du fond de l'éditeur pour que la bande soit visible.
        int darker = darkenColor(view.theme.editorBg, 0.85f);
        view.bgPaint.setColor(darker);
        canvas.drawRect(minimapLeft, 0, minimapLeft + minimapWidth, height, view.bgPaint);

        // Ligne de séparation au bord gauche de la minimap.
        view.selPaint.setColor(view.theme.gutterBorder);
        view.selPaint.setStrokeWidth(1f);
        canvas.drawLine(minimapLeft, 0, minimapLeft, height, view.selPaint);

        // Déterminer quelles lignes du document tombent dans le viewport de la
        // minimap. On rend ~height/rowH lignes en commençant par celle en haut
        // du viewport de l'éditeur.
        float editorLineHeight = view.metrics.getLineHeight();
        int firstVisibleDocLine = Math.max(0, (int) (view.vOffset / editorLineHeight) - 5);
        int visibleRows = (int) (height / rowH) + 10;
        int lastVisibleDocLine = Math.min(lineCount - 1, firstVisibleDocLine + visibleRows);

        // Pour chaque ligne visible, dessiner une rangée de segments colorés
        // d'après les spans de tokens du StyledLine.
        java.util.List<jo.codeeditor.highlight.StyledLine> styledLines = view.session.getStyledLines();
        float charWidth = view.metrics.getCharWidth();
        float maxCharsPerRow = Math.max(1, minimapWidth / Math.max(1, charWidth));

        for (int i = firstVisibleDocLine; i <= lastVisibleDocLine; i++) {
            float y = i * rowH - (view.vOffset / editorLineHeight) * rowH;
            if (y < -rowH || y > height) continue;
            if (i >= styledLines.size()) break;
            jo.codeeditor.highlight.StyledLine sl = styledLines.get(i);
            if (sl == null) continue;
            java.util.List<jo.codeeditor.highlight.LineSpan> spans = sl.spans;
            if (spans == null || spans.isEmpty()) continue;
            float x = minimapLeft;
            for (jo.codeeditor.highlight.LineSpan span : spans) {
                if (span == null) continue;
                int color = view.theme.colorForToken(span.type);
                view.squigglePaint.setColor(color);
                view.squigglePaint.setStyle(android.graphics.Paint.Style.FILL);
                // Chaque span est dessiné comme une fine barre horizontale —
                // largeur proportionnelle à la longueur du span, plafonnée à
                // la largeur de la minimap.
                float segW = Math.min(span.endCol - span.startCol, maxCharsPerRow) * (charWidth * 0.4f);
                if (segW < 0.5f) segW = 0.5f;
                canvas.drawRect(x, y, x + segW, y + rowH, view.squigglePaint);
                x += segW;
                if (x >= minimapLeft + minimapWidth) break;
            }
        }

        // Rectangle du viewport : montre la portion du fichier actuellement visible.
        float viewportY = 0;
        float viewportH = (height / editorLineHeight) * rowH;
        view.selPaint.setColor(view.applyAlphaToColor(view.theme.selection, 0.25f));
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        canvas.drawRect(minimapLeft, viewportY, minimapLeft + minimapWidth, viewportY + viewportH, view.selPaint);
        view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
        view.selPaint.setStrokeWidth(1f);
        view.selPaint.setColor(view.applyAlphaToColor(view.theme.selection, 0.6f));
        canvas.drawRect(minimapLeft, viewportY, minimapLeft + minimapWidth, viewportY + viewportH, view.selPaint);
    }

    /** Assombrit une couleur ARGB du facteur donné (0..1). */
    private static int darkenColor(int color, float factor) {
        int a = (color >>> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ── Géométrie de la minimap (déplacée d'EditorView) ───────────

    /**
     * Retourne la coordonnée X où la minimap commence (bord droit moins la
     * largeur de la minimap). Retourne la largeur complète quand la minimap
     * est désactivée.
     */
    private int getMinimapLeft() {
        if (!view.minimapEnabled) return view.getWidth();
        float density = view.getResources().getDisplayMetrics().density;
        return (int) (view.getWidth() - MINIMAP_WIDTH_DP * density);
    }

    /** Retourne la largeur de la minimap en pixels (0 si désactivée). */
    private int getMinimapWidth() {
        if (!view.minimapEnabled) return 0;
        float density = view.getResources().getDisplayMetrics().density;
        return (int) (MINIMAP_WIDTH_DP * density);
    }

    /**
     * Dessine l'aperçu de layout XML dans la zone du panneau d'aperçu.
     * Délègue au EditorPreviewHost (découplé).
     */
    void drawXmlPreview(Canvas canvas) {
        EditorPreviewHost host = view.getPreviewHost();
        if (host == null || !host.hasPreviewContent()) return;

        int previewLeft = view.getPreviewLeft();
        int previewWidth = view.getPreviewWidth();
        int height = view.getHeight();

        // Dessiner le fond de l'aperçu.
        view.bgPaint.setColor(view.theme.editorBg);
        canvas.drawRect(previewLeft, 0, previewLeft + previewWidth, height, view.bgPaint);

        // Dessiner l'aperçu via l'hôte.
        host.drawPreview(canvas, previewLeft, 0);

        // Dessiner la ligne de séparation de l'aperçu (identique au séparateur SPLIT).
        view.selPaint.setColor(view.theme.gutterBorder);
        view.selPaint.setStrokeWidth(1f);
        canvas.drawLine(previewLeft, 0, previewLeft, height, view.selPaint);
    }

    /**
     * Dessine les icônes d'aperçu scindé/plein en haut à droite.
     * Dessinées seulement quand le fichier est prévisualisable et que le
     * mode d'aperçu est NONE.
     */
    void drawPreviewIcons(Canvas canvas) {
        if (!view.previewable || view.previewMode != EditorView.PreviewMode.NONE) return;
        float density = view.getResources().getDisplayMetrics().density;
        float iconSize = 24f * density;
        float margin = 8f * density;
        float iconY = margin;
        float iconW = iconSize;
        float fullX = view.getWidth() - margin - iconW;
        float splitX = fullX - iconW - margin * 0.5f;
        int iconColor = view.applyAlphaToColor(view.theme.gutterText, 0.65f);
        int accentColor = view.applyAlphaToColor(view.theme.keyword, 0.85f);
        float cornerR = 3f * density;

        // Icône d'aperçu scindé : deux rectangles arrondis remplis côte à côte.
        // Panneau gauche (éditeur) — teinte de couleur d'accent.
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        view.selPaint.setColor(view.applyAlphaToColor(accentColor, 0.35f));
        float paneW = iconW * 0.38f;
        float paneH = iconW * 0.7f;
        float paneY = iconY + (iconW - paneH) * 0.5f;
        android.graphics.RectF leftPane = new android.graphics.RectF(
            splitX + iconW * 0.05f, paneY,
            splitX + iconW * 0.05f + paneW, paneY + paneH);
        canvas.drawRoundRect(leftPane, cornerR, cornerR, view.selPaint);
        // Bordure du panneau gauche.
        view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
        view.selPaint.setStrokeWidth(1.2f * density);
        view.selPaint.setColor(iconColor);
        canvas.drawRoundRect(leftPane, cornerR, cornerR, view.selPaint);
        // Panneau droit (aperçu) — contour seul.
        android.graphics.RectF rightPane = new android.graphics.RectF(
            splitX + iconW * 0.55f, paneY,
            splitX + iconW * 0.55f + paneW, paneY + paneH);
        view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
        canvas.drawRoundRect(rightPane, cornerR, cornerR, view.selPaint);
        // Ligne de séparation verticale entre les panneaux.
        float divX = splitX + iconW * 0.5f;
        canvas.drawLine(divX, paneY + 2f * density, divX, paneY + paneH - 2f * density, view.selPaint);

        // Icône d'aperçu plein : œil rempli avec pupille.
        float cx = fullX + iconW * 0.5f;
        float cy = iconY + iconW * 0.5f;
        float eyeW = iconW * 0.7f;
        float eyeH = iconW * 0.42f;
        // Forme de l'œil — amande remplie.
        android.graphics.Path eyePath = new android.graphics.Path();
        eyePath.moveTo(cx - eyeW * 0.5f, cy);
        // Courbe supérieure.
        android.graphics.RectF topArc = new android.graphics.RectF(
            cx - eyeW * 0.5f, cy - eyeH,
            cx + eyeW * 0.5f, cy + eyeH);
        eyePath.addArc(topArc, 200, 140);
        // Courbe inférieure (miroir).
        android.graphics.RectF botArc = new android.graphics.RectF(
            cx - eyeW * 0.5f, cy - eyeH * 0.3f,
            cx + eyeW * 0.5f, cy + eyeH * 1.7f);
        eyePath.arcTo(botArc, 20, 140);
        eyePath.close();
        // Remplir le fond de l'œil.
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        view.selPaint.setColor(view.applyAlphaToColor(iconColor, 0.15f));
        canvas.drawPath(eyePath, view.selPaint);
        // Contour de l'œil.
        view.selPaint.setStyle(android.graphics.Paint.Style.STROKE);
        view.selPaint.setStrokeWidth(1.5f * density);
        view.selPaint.setColor(iconColor);
        canvas.drawPath(eyePath, view.selPaint);
        // Pupille (cercle rempli).
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        view.selPaint.setColor(iconColor);
        canvas.drawCircle(cx, cy, iconW * 0.12f, view.selPaint);
    }

    // ════════════════════════════════════════════════════════════════
    // Loupe
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine une loupe (bulle de zoom) au-dessus de la position du doigt
     * pendant les glissements. Montre une vue zoomée ×2 du texte autour du
     * centre de la loupe.
     */
    void drawMagnifier(Canvas canvas) {
        if (!view.magnifierActive) return;
        float density = view.getResources().getDisplayMetrics().density;
        float magRadius = 60f * density; // rayon de la loupe
        float magCx = view.magnifierX;
        // Positionner la loupe AU-DESSUS du doigt pour ne pas couvrir le contenu.
        float magCy = view.magnifierY - magRadius * 1.8f;
        // Borner pour ne pas sortir de l'écran.
        magCy = Math.max(magRadius, magCy);

        // Sauvegarder l'état du canvas.
        canvas.save();
        // Cliper en cercle.
        android.graphics.Path clipPath = new android.graphics.Path();
        clipPath.addCircle(magCx, magCy, magRadius, android.graphics.Path.Direction.CW);
        canvas.clipPath(clipPath);

        // Dessiner le fond de la loupe.
        view.bgPaint.setColor(view.theme.editorBg);
        canvas.drawRect(magCx - magRadius, magCy - magRadius, magCx + magRadius, magCy + magRadius, view.bgPaint);

        // Mettre à l'échelle le canvas autour du centre de la loupe (zoom ×2).
        canvas.scale(2f, 2f, magCx, magCy);
        // Translater pour que le contenu sous le doigt soit centré dans la loupe.
        canvas.translate(-(view.magnifierX - magCx), -(view.magnifierY - magCy));

        // Redessiner le contenu texte à la position de la loupe. Réutiliser le
        // pipeline de dessin en rappelant draw() récursivement serait trop
        // lourd — dessiner à la place quelques lignes autour du point de contact.
        if (view.session != null) {
            jo.codeeditor.document.EditorDocument doc = view.session.getDocument();
            int caretLine = view.docLineForScreenY(view.magnifierY);
            int firstLine = Math.max(0, caretLine - 3);
            int lastLine = Math.min(doc.lineCount() - 1, caretLine + 3);
            float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
            float lineHeight = view.metrics.getLineHeight();
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize());
            for (int i = firstLine; i <= lastLine; i++) {
                if (view.isLineFoldedCached(i)) continue;
                float lineY = view.docLineToY(i) - view.vOffset;
                String lineText = doc.lineText(i);
                jo.codeeditor.highlight.StyledLine styled = null;
                java.util.List<jo.codeeditor.highlight.StyledLine> styledLines = view.session.getStyledLines();
                if (i < styledLines.size()) styled = styledLines.get(i);
                if (styled != null) {
                    // Chemin de la loupe — tisser aussi les inlays de la ligne
                    // pour que la vue zoomée corresponde au rendu réel.
                    LineRenderCache.LineCacheEntry layout = view.layoutForLine(i, lineText);
                    text.drawStyledLine(canvas, styled, lineText, textAreaLeft - view.hOffset, lineY, view.textPaint,
                        layout != null ? layout.inlays : null,
                        layout != null ? layout.rawToVisual : null);
                } else {
                    view.textPaint.setColor(view.theme.textColor);
                    canvas.drawText(lineText, textAreaLeft - view.hOffset, lineY + lineHeight * 0.78f, view.textPaint);
                }
            }
        }
        canvas.restore();

        // Dessiner la bordure de la loupe (anneau).
        view.selPaint.setStyle(Paint.Style.STROKE);
        view.selPaint.setStrokeWidth(2f * density);
        view.selPaint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.5f));
        canvas.drawCircle(magCx, magCy, magRadius, view.selPaint);
        // Anneau d'ombre intérieure discret.
        view.selPaint.setStrokeWidth(1f * density);
        view.selPaint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.2f));
        canvas.drawCircle(magCx, magCy, magRadius - 2f * density, view.selPaint);
    }

    // ════════════════════════════════════════════════════════════════
    // Chevrons de pli
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine les chevrons de pli. Réduit → chevron toujours visible.
     * Ouvert → seulement sur la ligne du curseur.
     *
     * <p>Petit triangle REMPLI, discret : chemin rempli (Style.FILL),
     * {@code r = 3.2dp}, couleur = gutterText à 0.8 d'alpha. Un chevron
     * trop gros et en contour (largeur de trait 1.4f avec
     * {@code r2 = foldStripWidth * 0.25} ≈ 3,5 dp+ à densité par défaut)
     * serait visuellement bien plus lourd et intrusif.
     */
    void drawFoldChevrons(Canvas canvas, EditorDocument doc,
                           int firstVisible, int lastVisible,
                           float lineHeight, float paddingTop) {
        if (view.session.getFoldRegions().isEmpty()) return;
        float foldStripX = view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth() * 0.5f;
        int caretLine = EditorView.clamp(doc.lineForOffset(view.session.getSelection().start), 0, doc.lineCount() - 1);
        // Triangle rempli — r = 3.2 dp, alpha 0.8.
        float density = view.getResources().getDisplayMetrics().density;
        float r = 3.2f * density; // demi-étendue en pixels
        view.caretPaint.setStyle(Paint.Style.FILL);
        view.caretPaint.setAntiAlias(true);
        Path p = scratchPath;
        for (DiagnosticShift.FoldRegion region : view.session.getFoldRegions()) {
            int startLine = doc.lineForOffset(region.start);
            if (startLine < firstVisible || startLine > lastVisible) continue;
            if (!region.collapsed && startLine != caretLine) continue;
            float y = view.docLineToY(startLine) - view.vOffset + lineHeight * 0.5f;
            // Appliquer un alpha de 0.8 à la couleur gutterText.
            int baseColor = view.theme.gutterText;
            view.caretPaint.setColor(applyAlpha(baseColor, 0.8f));
            p.reset();
            if (region.collapsed) {
                // ▸ pointe à droite — largeur ~r*1.3, hauteur ~2r
                p.moveTo(foldStripX - r * 0.6f, y - r);
                p.lineTo(foldStripX + r * 0.7f, y);
                p.lineTo(foldStripX - r * 0.6f, y + r);
                p.close();
            } else {
                // ▾ pointe en bas — largeur ~2r, hauteur ~r*1.3
                p.moveTo(foldStripX - r, y - r * 0.6f);
                p.lineTo(foldStripX + r, y - r * 0.6f);
                p.lineTo(foldStripX, y + r * 0.7f);
                p.close();
            }
            canvas.drawPath(p, view.caretPaint);
        }
    }

    /**
     * Applique un multiplicateur d'alpha à une couleur ARGB. Utilisé par
     * {@link #drawFoldChevrons} pour son chevron à alpha 0.8.
     */
    private static int applyAlpha(int color, float alpha) {
        int a = (color >>> 24) & 0xFF;
        int newA = (int) (a * alpha);
        return (newA << 24) | (color & 0x00FFFFFF);
    }

    // ════════════════════════════════════════════════════════════════
    // Menu contextuel unifié (NavMenu)
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine le menu contextuel unifié : carte glass arrondie,
     * sections en majuscules (GO TO / QUICK FIXES / INTENTIONS, affichées
     * seulement si non-vides), rangées icône + label, « Nothing found in
     * source. » quand tout est vide. Rangées 40dp, icône 16dp + gap 10dp,
     * header labelSmall semibold, press → fond teinté accent + icône
     * accent. Contenu scrollable (navMenuScrollY) borné à 360dp.
     */
    void drawNavMenu(Canvas canvas) {
        if (!view.navMenuVisible) return;
        float[] m = view.navMenuMetrics();
        if (m == null) return;
        List<EditorView.NavMenuRow> rows = view.navMenuRows();
        if (rows.isEmpty()) return;
        float density = view.getResources().getDisplayMetrics().density;
        float x = m[0], y = m[1], w = m[2], h = m[3];
        float rowH = view.NAV_MENU_ROW_HEIGHT_DP * density;
        float headerH = view.NAV_MENU_HEADER_HEIGHT_DP * density;
        float radius = 8 * density;

        // ── Carte glass (motif drawQuickDocPopup) ──────────────────
        RectF rect = new RectF(x, y, x + w, y + h);
        view.bgPaint.setStyle(Paint.Style.FILL);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);
        view.caretPaint.setStyle(Paint.Style.FILL);

        // ── Contenu, clippé + décalé du scroll ──────────────────────
        int save = canvas.save();
        canvas.clipRect(rect);
        float contentY = y - view.navMenuScrollY;
        for (int i = 0; i < rows.size(); i++) {
            EditorView.NavMenuRow row = rows.get(i);
            float rh = row.type == EditorView.NavMenuRow.TYPE_HEADER
                    ? headerH : rowH;
            float rowTop = contentY;
            float rowBottom = rowTop + rh;
            contentY = rowBottom;
            if (rowBottom < y || rowTop > y + h) continue; // hors fenêtre

            if (row.type == EditorView.NavMenuRow.TYPE_HEADER) {
                // SectionHeader : UPPERCASE, labelSmall semibold, outline.
                view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                view.textPaint.setTextSize(11 * density);
                view.textPaint.setColor(view.theme.gutterText);
                String header = String.valueOf(row.ref);
                canvas.drawText(header, x + 12 * density,
                        rowTop + headerH * 0.78f, view.textPaint);
                view.textPaint.setTypeface(view.metrics.getTypeface());
                view.textPaint.setTextSize(view.metrics.getTextSize());
                continue;
            }

            if (row.type == EditorView.NavMenuRow.TYPE_NOTHING) {
                // NothingFound : bodyMedium, outline, padding 14×12dp.
                view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
                view.textPaint.setTextSize(13 * density);
                view.textPaint.setColor(view.theme.gutterText);
                canvas.drawText("Nothing found in source.",
                        x + 14 * density, rowTop + rowH * 0.68f, view.textPaint);
                view.textPaint.setTypeface(view.metrics.getTypeface());
                view.textPaint.setTextSize(view.metrics.getTextSize());
                continue;
            }

            // ── Rangée actionnable : press feedback + icône + label ──
            boolean pressed = view.navMenuPressedIdx == i;
            if (pressed) {
                view.bgPaint.setStyle(Paint.Style.FILL);
                view.bgPaint.setColor(withAlpha(view.theme.caret, 0.22f));
                canvas.drawRoundRect(new RectF(x + 1, rowTop, x + w - 1, rowBottom),
                        6 * density, 6 * density, view.bgPaint);
            }
            int contentColor = pressed ? view.theme.caret : view.theme.textColor;
            float iconCx = x + 12 * density + 8 * density;
            float iconCy = rowTop + rowH * 0.5f;
            drawNavMenuIcon(canvas, row, iconCx, iconCy, contentColor, density);

            view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
            view.textPaint.setTextSize(13 * density);
            view.textPaint.setColor(contentColor);
            String label = navMenuRowLabel(row);
            if (label != null) {
                // Ellipsise : coupe + « … » si le label déborde.
                float maxLabelW = w - 12 * density - 16 * density - 10 * density
                        - 12 * density;
                String drawn = label;
                if (view.textPaint.measureText(label) > maxLabelW) {
                    StringBuilder sb = new StringBuilder(label);
                    while (sb.length() > 1
                            && view.textPaint.measureText(sb + "…") > maxLabelW) {
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    drawn = sb + "…";
                }
                canvas.drawText(drawn, x + 12 * density + 16 * density + 10 * density,
                        rowTop + rowH * 0.5f + view.textPaint.measureText("A") * 0.32f,
                        view.textPaint);
            }
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize());
        }
        canvas.restoreToCount(save);
        view.caretPaint.setStrokeWidth(1f);
    }

    /** Le libellé d'une rangée actionnable. */
    private String navMenuRowLabel(EditorView.NavMenuRow row) {
        if (row.ref instanceof NavigationMenu.NavOption) {
            return ((NavigationMenu.NavOption) row.ref).label;
        }
        if (row.ref instanceof EditorView.CodeAction) {
            return ((EditorView.CodeAction) row.ref).title;
        }
        if (row.ref instanceof NavigationMenu.NavTarget) {
            return ((NavigationMenu.NavTarget) row.ref).displayName;
        }
        return null;
    }

    /**
     * Icône 16dp d'une rangée, équivalents canvas des icônes de
     * navigation : code (chevrons ‹ ›, Declaration), layers (losange +
     * chevrons empilés, Implementations), box (hexagone, Type
     * declaration), pin (tête + tige, Super), gear (quick fixes),
     * lightbulb (intentions), dot (cibles génériques).
     */
    private void drawNavMenuIcon(Canvas canvas, EditorView.NavMenuRow row,
            float cx, float cy, int color, float density) {
        float s = density; // échelle : la grille de référence est 16dp
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1.4f * density);
        view.caretPaint.setColor(color);
        switch (row.type) {
            case EditorView.NavMenuRow.TYPE_OPTION: {
                // navIcon(kind) : Declaration → icône code (M9 8l-4 4 4 4
                // M15 8l4 4-4 4) ; Implementations → icône layers
                // (M12 4l8 4-8 4-8-4zM4 12l8 4 8-4M4 16l8 4 8-4) ;
                // Type declaration → icône box ; Super → icône pin
                // (M9 4h6l-.8 5 2.3 2.5h-9L9.8 9z + M12 13.5V20).
                String label = row.ref instanceof NavigationMenu.NavOption
                        ? ((NavigationMenu.NavOption) row.ref).label : "";
                if ("Type declaration".equals(label)) {
                    // Box : hexagone M12 3.3 l7.5 4.2 v9.2 L12 20.9 4.5 16.7 V7.5 z
                    // + arêtes internes (échelle /24 → 16dp : ×2/3).
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(cx, cy - 5.8f * s);
                    p.lineTo(cx + 5.0f * s, cy - 2.9f * s);
                    p.lineTo(cx + 5.0f * s, cy + 2.9f * s);
                    p.lineTo(cx, cy + 5.8f * s);
                    p.lineTo(cx - 5.0f * s, cy + 2.9f * s);
                    p.lineTo(cx - 5.0f * s, cy - 2.9f * s);
                    p.close();
                    canvas.drawPath(p, view.caretPaint);
                    canvas.drawLine(cx - 5.0f * s, cy - 2.9f * s, cx, cy,
                            view.caretPaint);
                    canvas.drawLine(cx, cy, cx + 5.0f * s, cy - 2.9f * s,
                            view.caretPaint);
                    canvas.drawLine(cx, cy, cx, cy + 5.8f * s, view.caretPaint);
                } else if ("Implementations".equals(label)) {
                    // Layers : losange supérieur + deux chevrons descendants
                    // (grille 24, ×0.7 pour le poids visuel des autres icônes
                    // nav).
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(cx, cy - 5.6f * s);
                    p.lineTo(cx + 5.6f * s, cy - 2.8f * s);
                    p.lineTo(cx, cy);
                    p.lineTo(cx - 5.6f * s, cy - 2.8f * s);
                    p.close();
                    canvas.drawPath(p, view.caretPaint);
                    p.reset();
                    p.moveTo(cx - 5.6f * s, cy + 2.8f * s);
                    p.lineTo(cx, cy + 5.6f * s);
                    p.lineTo(cx + 5.6f * s, cy + 2.8f * s);
                    canvas.drawPath(p, view.caretPaint);
                    canvas.drawLine(cx - 5.6f * s, cy + 5.6f * s,
                            cx, cy + 8.4f * s, view.caretPaint);
                    canvas.drawLine(cx, cy + 8.4f * s,
                            cx + 5.6f * s, cy + 5.6f * s, view.caretPaint);
                } else if ("Super".equals(label)) {
                    // Pin : tête trapézoïdale + tige verticale (grille 24,
                    // ×2/3 centré).
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(cx - 2.0f * s, cy - 5.3f * s);
                    p.lineTo(cx + 2.0f * s, cy - 5.3f * s);
                    p.lineTo(cx + 1.5f * s, cy - 2.0f * s);
                    p.lineTo(cx + 3.0f * s, cy - 0.3f * s);
                    p.lineTo(cx - 3.0f * s, cy - 0.3f * s);
                    p.lineTo(cx - 1.5f * s, cy - 2.0f * s);
                    p.close();
                    canvas.drawPath(p, view.caretPaint);
                    canvas.drawLine(cx, cy + 1.0f * s, cx, cy + 5.3f * s,
                            view.caretPaint);
                } else {
                    // Code : deux chevrons.
                    android.graphics.Path p = new android.graphics.Path();
                    p.moveTo(cx - 3.3f * s, cy - 2.7f * s);
                    p.lineTo(cx - 6.0f * s, cy);
                    p.lineTo(cx - 3.3f * s, cy + 2.7f * s);
                    p.moveTo(cx + 3.3f * s, cy - 2.7f * s);
                    p.lineTo(cx + 6.0f * s, cy);
                    p.lineTo(cx + 3.3f * s, cy + 2.7f * s);
                    canvas.drawPath(p, view.caretPaint);
                }
                break;
            }
            case EditorView.NavMenuRow.TYPE_ACTION: {
                // Engrenage (quick fixes) vs ampoule (intentions).
                boolean isQuickFix = row.section == EditorView.NavMenuRow.SECTION_QUICK_FIXES;
                if (isQuickFix) {
                    // Engrenage : anneau + moyeu + 8 dents radiales.
                    canvas.drawCircle(cx, cy, 4.6f * s, view.caretPaint);
                    canvas.drawCircle(cx, cy, 1.8f * s, view.caretPaint);
                    for (int k = 0; k < 8; k++) {
                        double a = Math.toRadians(k * 45.0);
                        float x1 = cx + (float) Math.cos(a) * 4.6f * s;
                        float y1 = cy + (float) Math.sin(a) * 4.6f * s;
                        float x2 = cx + (float) Math.cos(a) * 6.2f * s;
                        float y2 = cy + (float) Math.sin(a) * 6.2f * s;
                        canvas.drawLine(x1, y1, x2, y2, view.caretPaint);
                    }
                } else {
                    // Ampoule : dôme + base (2 traits).
                    RectF bulb = new RectF(cx - 3.0f * s, cy - 5.2f * s,
                            cx + 3.0f * s, cy + 1.2f * s);
                    canvas.drawArc(bulb, 180, 180, false, view.caretPaint);
                    canvas.drawLine(cx - 3.0f * s, cy - 2.0f * s,
                            cx - 3.0f * s, cy + 1.2f * s, view.caretPaint);
                    canvas.drawLine(cx + 3.0f * s, cy - 2.0f * s,
                            cx + 3.0f * s, cy + 1.2f * s, view.caretPaint);
                    canvas.drawLine(cx - 2.4f * s, cy + 3.2f * s,
                            cx + 2.4f * s, cy + 3.2f * s, view.caretPaint);
                    canvas.drawLine(cx - 1.8f * s, cy + 5.6f * s,
                            cx + 1.8f * s, cy + 5.6f * s, view.caretPaint);
                }
                break;
            }
            default: {
                // TYPE_TARGET → point (cercle plein r=2.3).
                view.bgPaint.setStyle(Paint.Style.FILL);
                view.bgPaint.setColor(color);
                canvas.drawCircle(cx, cy, 2.3f * s, view.bgPaint);
                break;
            }
        }
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setStyle(Paint.Style.FILL);
    }

    // ════════════════════════════════════════════════════════════════
    // Toolbar de sélection (Copier/Couper/Coller/Tout flottants)
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine la toolbar de sélection flottante — pilule dépolie ancrée
     * au-dessus de l'extrémité ACTIVE de la sélection (la toolbar suit le
     * doigt jusqu'où l'utilisateur a fini de sélectionner).
     *
     * <p><b>Caractéristiques :</b></p>
     * <ul>
     *   <li><b>géométrie partagée</b> — le layout vient de
     *       {@link EditorView#selectionToolbarMetrics()} (source unique pour
     *       le rendu ET le hit-test, sans calcul dupliqué) ;</li>
     *   <li><b>mode collapsed</b> — Copy/Cut disparaissent quand la sélection
     *       est vide (re-tap sur le curseur → Paste / Select all + icônes) ;</li>
     *   <li><b>boutons Docs ℹ / Actions ⋯</b> après un divider conditionnel ;</li>
     *   <li><b>animation d'entrée</b> — pop d'entrée (scale 0.96→1 + fade,
     *       160 ms) et cascade par item (fade + montée 5dp, 24 ms/item) ;</li>
     *   <li><b>feedback press</b> — fond teinté accent 22 % + texte accent
     *       sur l'item pressé.</li>
     * </ul>
     */
    void drawSelectionToolbar(Canvas canvas) {
        if (!view.selectionToolbarVisible || view.session == null) return;
        EditorPopupAnchors.SelectionToolbarMetrics m = view.selectionToolbarMetrics();
        if (m == null || m.count == 0) return;
        float density = view.getResources().getDisplayMetrics().density;

        // ── Animation d'entrée (pop + cascade) ─────────────────────
        long now = android.os.SystemClock.uptimeMillis();
        float tOverall = (now - view.selectionToolbarShownAt) / 160f;
        boolean animating = tOverall >= 0 && tOverall < 2.2f;
        float pop = clamp01(tOverall);
        float ease = 1f - (1f - pop) * (1f - pop) * (1f - pop); // easeOutCubic
        float scale = 0.96f + 0.04f * ease;
        float overallAlpha = clamp01(tOverall * 1.4f);
        if (animating) view.postInvalidateOnAnimation(); // boucle de frames

        int saveCount = canvas.save();
        if (animating) {
            canvas.scale(scale, scale, m.x + m.w * 0.5f, m.y + m.h * 0.5f);
        }

        // Ombre (rectangle arrondi légèrement plus grand, semi-transparent).
        RectF shadowRect = new RectF(m.x + 1, m.y + 2, m.x + m.w + 1, m.y + m.h + 2);
        view.bgPaint.setStyle(Paint.Style.FILL);
        view.bgPaint.setColor(withAlpha(0xFF000000, 0.24f * overallAlpha));
        canvas.drawRoundRect(shadowRect, m.radius, m.radius, view.bgPaint);
        // Fond de pilule glass.
        RectF rect = new RectF(m.x, m.y, m.x + m.w, m.y + m.h);
        view.bgPaint.setColor(withAlpha(view.theme.glassBg, overallAlpha));
        canvas.drawRoundRect(rect, m.radius, m.radius, view.bgPaint);
        // Bordure.
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(withAlpha(view.theme.glassBorder, overallAlpha));
        canvas.drawRoundRect(rect, m.radius, m.radius, view.caretPaint);

        // Divider conditionnel — entre le groupe texte et le groupe
        // d'icônes.
        if (m.dividerCount > 0 && m.dividerX != Float.MIN_VALUE) {
            view.caretPaint.setStrokeWidth(0.5f);
            canvas.drawLine(m.dividerX, m.y + m.padY, m.dividerX,
                    m.y + m.h - m.padY, view.caretPaint);
        }

        // Items.
        view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        view.textPaint.setTextSize(13 * density);
        for (int i = 0; i < m.count; i++) {
            // Cascade par item (index*30ms, fade + montée 5dp — resserrée
            // ici à 24ms pour la réactivité tactile).
            float p = animating
                    ? clamp01((now - view.selectionToolbarShownAt - i * 24f) / 120f)
                    : 1f;
            float itemAlpha = overallAlpha * p;
            if (itemAlpha <= 0f) continue;
            float dy = (1f - p) * 5 * density; // montée depuis 5dp dessous
            boolean pressed = view.selectionToolbarPressedIdx == i;
            int contentColor = pressed ? view.theme.caret : view.theme.textColor;

            if (pressed) {
                // Feedback press : fond teinté accent, coins arrondis.
                RectF pr = new RectF(m.itemX[i], m.y + 2 * density,
                        m.itemX[i] + m.itemW[i], m.y + m.h - 2 * density);
                view.bgPaint.setColor(withAlpha(view.theme.caret, 0.22f * itemAlpha));
                canvas.drawRoundRect(pr, m.radius * 0.7f, m.radius * 0.7f, view.bgPaint);
            }

            if (m.isIcon[i]) {
                drawSelectionToolbarIcon(canvas, m, i, contentColor, itemAlpha, dy, density);
            } else {
                float labelX = m.itemX[i] + m.padX;
                float labelY = m.y + m.h * 0.5f
                        + view.textPaint.measureText("A") * 0.35f + dy;
                view.textPaint.setColor(withAlpha(contentColor, itemAlpha));
                canvas.drawText(m.label[i], labelX, labelY, view.textPaint);
            }
        }
        // Réinitialiser les paints.
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setStyle(Paint.Style.FILL);
        canvas.restoreToCount(saveCount);
    }

    /**
     * Dessine un bouton icône de la toolbar de sélection :
     * <b>Docs</b> = cercle + « i », <b>Actions</b> = trois points. 16dp,
     * teinte atténuée (façon onSurfaceVariant).
     */
    private void drawSelectionToolbarIcon(Canvas canvas,
            EditorPopupAnchors.SelectionToolbarMetrics m, int i, int color,
            float alpha, float dy, float density) {
        float cx = m.itemX[i] + m.itemW[i] * 0.5f;
        float cy = m.y + m.h * 0.5f + dy;
        int c = withAlpha(color, alpha);
        view.bgPaint.setStyle(Paint.Style.FILL);
        if (m.action[i] == EditorView.SEL_ACT_DOCS) {
            // ℹ — cercle 14dp + « i » centré.
            view.caretPaint.setStyle(Paint.Style.STROKE);
            view.caretPaint.setStrokeWidth(1.2f * density);
            view.caretPaint.setColor(c);
            canvas.drawCircle(cx, cy, 7 * density, view.caretPaint);
            view.caretPaint.setStyle(Paint.Style.FILL);
            view.textPaint.setTextSize(11 * density);
            float tw = view.textPaint.measureText("i");
            float halfH = view.textPaint.measureText("A") * 0.32f;
            view.textPaint.setColor(c);
            canvas.drawText("i", cx - tw * 0.5f, cy + halfH, view.textPaint);
            view.textPaint.setTextSize(13 * density);
        } else {
            // ⋯ — trois points pleins.
            view.bgPaint.setColor(c);
            float r = 1.2f * density;
            float gap = 3.5f * density;
            canvas.drawCircle(cx - gap, cy, r, view.bgPaint);
            canvas.drawCircle(cx, cy, r, view.bgPaint);
            canvas.drawCircle(cx + gap, cy, r, view.bgPaint);
        }
    }

    /** Applique une alpha relative (0..1) à une couleur ARGB. */
    private static int withAlpha(int color, float alpha) {
        if (alpha <= 0f) return color & 0x00FFFFFF;
        if (alpha >= 1f) return color;
        int a = Math.round(android.graphics.Color.alpha(color) * alpha);
        return (color & 0x00FFFFFF) | (a << 24);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}
