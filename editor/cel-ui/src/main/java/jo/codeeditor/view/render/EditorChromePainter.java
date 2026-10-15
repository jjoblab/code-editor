package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.icons.EditorIcons;
import jo.codeeditor.view.popup.EditorPopupAnchors;
import jo.codeeditor.view.preview.EditorPreviewController;
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
    // ★ M1 : la loupe redessine la bande de sélection, les squiggles
    // et le caret — mêmes peintres que le pipeline principal (mêmes
    // géométries wrap/inlay/plis), passés par composition.
    private final EditorHighlightPainter highlights;
    private final EditorDiagnosticsPainter diagnostics;

    // Objet de travail réutilisable — évite les allocations par frame.
    // Usage mono-thread (uniquement depuis onDraw sur le thread UI).
    private final Path scratchPath = new Path();
    // P-05 (audit 3.69.0) : RectF de travail réutilisé — cartes glass,
    // rangées pressées, ampoule, ombre et pilules allouaient un RectF par
    // draw, par frame. Usage mono-thread (onDraw, thread UI) ; chaque site
    // fait set -> draws immédiats séquentiels (l'ombre est consommée avant
    // que le fond ne reprenne le champ).
    private final RectF scratchRect = new RectF();

    // Paint dédié aux icônes d'aperçu du coin (B6 — moteur EditorIcons).
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Paint DÉDIÉ à la loupe. La loupe ne doit JAMAIS muter le textPaint
    // partagé du rendu principal (typeface, taille ni couleur) : ce paint
    // est synchronisé avant usage et porte la couleur de base
    // explicitement — un état résiduel ne peut fuiter vers la frame
    // suivante.
    private final android.text.TextPaint magnifierPaint = new android.text.TextPaint();

    // ── B13 (P1-2) : carte flottante du drag-déposer de bloc ──────
    // Peints DÉDIÉS (même discipline que la loupe : jamais de mutation
    // des paints partagés du pipeline principal).
    private final Paint blockCardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockCardTextPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
    /** Lignes affichées dans la carte (au-delà : « … » en pied). */
    private static final int BLOCK_CARD_MAX_LINES = 3;
    /** Caractères affichés par ligne de carte (ellipse « … »). */
    private static final int BLOCK_CARD_MAX_CHARS = 32;

    // ── Géométrie de la minimap (déplacée d'EditorView) ───────────
    static final float MINIMAP_WIDTH_DP = 60f;
    static final float MINIMAP_LINE_HEIGHT_PX = 2.5f;  // px par ligne doc

    // ── M3 (lot 2, J5) : loupe native en opt-in (API 28+) ──────
    // Le widget plateforme (android.widget.Magnifier) rend la VRAIE
    // frame de la vue — overlays système compris — dans une bulle
    // gérée par le window manager. Compromis documenté : style non
    // maîtrisé (la bulle suit les règles système), rendu exhaustif.
    // Créé paresseusement (jamais instancié sous API 28) ; la bulle
    // est refermée par dismissNativeMagnifier (relais
    // EditorView.dismissMagnifier au relâchement du geste).
    private android.widget.Magnifier nativeMagnifier;
    private boolean nativeMagnifierShown = false;

    EditorChromePainter(EditorView view, EditorTextPainter text,
            EditorHighlightPainter highlights, EditorDiagnosticsPainter diagnostics) {
        this.view = view;
        this.text = text;
        this.highlights = highlights;
        this.diagnostics = diagnostics;
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
        // ★ Restauration du paint partagé (même discipline que la loupe) :
        // le contour du viewport de minimap ne doit pas fuir en STROKE
        // vers la frame suivante (bande de sélection en contour sans
        // remplissage).
        view.selPaint.setStyle(android.graphics.Paint.Style.FILL);
        view.selPaint.setStrokeWidth(0f);
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
     * Dessinées seulement quand le fichier est prévisualisable, que le mode
     * d'aperçu est NONE et que les icônes n'ont pas été RELOCALISÉES dans
     * la barre de fil d'Ariane (B5 — {@code view.previewIconsVisible}).
     *
     * <p>B6 : les glyphes artisanals (panneaux + œil en arcs) laissent la
     * place au moteur d'icônes vectorielles ({@link EditorIcons}) — même
     * dessin que la barre et la feuille d'aperçu, tailles alignées sur les
     * constantes du contrôleur (le hit-test partage ces bornes).</p>
     */
    void drawPreviewIcons(Canvas canvas) {
        if (!view.previewable || !view.previewIconsVisible
                || view.previewMode != EditorView.PreviewMode.NONE) return;
        float density = view.getResources().getDisplayMetrics().density;
        float iconSize = EditorPreviewController.PREVIEW_ICON_SIZE_DP * density;
        float margin = EditorPreviewController.PREVIEW_ICON_MARGIN_DP * density;
        float iconY = margin;
        float iconW = iconSize;
        float fullX = view.getWidth() - margin - iconW;
        float splitX = fullX - iconW - margin * 0.5f;
        int iconColor = view.applyAlphaToColor(view.theme.gutterText, 0.65f);
        int accentColor = view.applyAlphaToColor(view.theme.keyword, 0.85f);

        // Icône d'aperçu scindé : panneau barré du séparateur central
        // (l'éditeur à gauche, l'aperçu à droite).
        iconPaint.setColor(iconColor);
        EditorIcons.draw(canvas, EditorIcons.Icon.SPLIT,
                splitX + iconW * 0.5f, iconY + iconW * 0.5f,
                iconW * 0.72f, iconPaint);

        // Icône d'aperçu plein : l'œil — accent léger pour la distinguer
        // du scindé (le plein écran est l'action « immersive »).
        iconPaint.setColor(accentColor);
        EditorIcons.draw(canvas, EditorIcons.Icon.EYE,
                fullX + iconW * 0.5f, iconY + iconW * 0.5f,
                iconW * 0.72f, iconPaint);
    }

    // ════════════════════════════════════════════════════════════════
    // Loupe
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine une loupe (bulle de zoom) au-dessus de la position du doigt
     * pendant les glissements. Montre une vue zoomée (facteur
     * {@code view.magnifierZoom}, défaut ×2) du texte autour du centre
     * de la loupe.
     *
     * <p>N'utilise QUE {@link #magnifierPaint} : le textPaint partagé du
     * rendu principal ne doit être ni lu (couleur résiduelle) ni écrit
     * ici — la loupe s'exécute en fin de pipeline, après toutes les
     * couches qui le mutent.</p>
     *
     * <p>M3 (lot 2, J5) : quand la loupe native est activée
     * ({@code view.nativeMagnifierEnabled}) et l'API le permet (28+),
     * la voie custom est court-circuitée — le widget plateforme
     * s'affiche via {@link #showNativeMagnifier(float, float)} et est
     * refermé via {@link #dismissNativeMagnifier()} (aucun rendu sur le
     * canvas de la vue).</p>
     */
    void drawMagnifier(Canvas canvas) {
        // M3 : voie native — le widget plateforme rend la vraie frame
        // (fenêtrage système, pas de canvas) ; à l'extinction du geste,
        // la bulle est refermée ici même (drawMagnifier est appelé à
        // CHAQUE frame, y compris la première sans geste actif).
        if (nativeMagnifierUsable(view.nativeMagnifierEnabled)) {
            if (view.magnifierActive) {
                showNativeMagnifier(view.magnifierX, view.magnifierY);
            } else {
                dismissNativeMagnifier();
            }
            return;
        }
        // Bascule custom en cours d'affichage natif (drapeau éteint à
        // chaud, ou API redescendu) : referme la bulle plateforme.
        dismissNativeMagnifier();
        if (!view.magnifierActive) return;
        float density = view.getResources().getDisplayMetrics().density;
        float magRadius = 60f * density; // rayon de la loupe
        float viewW = view.getWidth();
        float viewH = view.getHeight();
        // Borner le centre pour que la bulle ne soit JAMAIS coupée par les
        // bords de la vue : au-dessus du doigt si la place suffit, sinon
        // sous le doigt, sinon dans ce qui reste de la vue.
        float magCx = clampMagnifierCx(view.magnifierX, viewW, magRadius);
        float magCy = resolveMagnifierCy(view.magnifierY, viewH, magRadius);

        // Sauvegarder l'état du canvas.
        canvas.save();
        // Cliper en cercle (chemin de travail réutilisé — pas d'allocation
        // par frame).
        scratchPath.reset();
        scratchPath.addCircle(magCx, magCy, magRadius, android.graphics.Path.Direction.CW);
        canvas.clipPath(scratchPath);

        // Dessiner le fond de la loupe.
        view.bgPaint.setColor(view.theme.editorBg);
        canvas.drawRect(magCx - magRadius, magCy - magRadius, magCx + magRadius, magCy + magRadius, view.bgPaint);

        // Mettre à l'échelle le canvas autour du centre de la loupe
        // (M2 : facteur réglable, ×2 par défaut — lecture des grands
        // caractères : ×1.5 à ×3).
        canvas.scale(view.magnifierZoom, view.magnifierZoom, magCx, magCy);
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

            // ★ M1 — la loupe est un instrument de RÉGLAGE : montrer ce
            // qu'on règle, dans l'ordre du pipeline principal (bande
            // SOUS le texte, puis texte, puis squiggle, puis caret).
            // Bande de sélection : même peintre et même géométrie que la
            // passe principale (wrap par rangées, inlays tissés,
            // saute les lignes repliées).
            jo.codeeditor.document.Selection sel = view.clampSelection(
                    view.session.getSelection(), doc);
            if (!sel.isCursor()) {
                highlights.drawSelection(canvas, doc, sel, textAreaLeft,
                        lineHeight, 0f, firstLine, lastLine);
            }

            // Paint dédié : synchronisé depuis le paint du texte (typeface,
            // taille), couleur de base posée explicitement. Le paint partagé
            // du rendu principal n'est touché nulle part ci-dessous.
            magnifierPaint.set(view.textPaint);
            magnifierPaint.setTypeface(view.metrics.getTypeface());
            magnifierPaint.setTextSize(view.metrics.getTextSize());
            magnifierPaint.setColor(view.theme.textColor);
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
                    text.drawStyledLine(canvas, styled, lineText, textAreaLeft - view.hOffset, lineY, magnifierPaint,
                        layout != null ? layout.inlays : null,
                        layout != null ? layout.rawToVisual : null);
                } else {
                    magnifierPaint.setColor(view.theme.textColor);
                    canvas.drawText(lineText, textAreaLeft - view.hOffset, lineY + lineHeight * 0.78f, magnifierPaint);
                }
            }

            // ★ M1 — squiggles de diagnostic sur la même fenêtre de
            // lignes : même peintre que la passe principale (ondulé de
            // sévérité, voile Unnecessary, colonnes visuelles inlay-aware).
            diagnostics.drawSquiggles(canvas, doc, firstLine, lastLine,
                    textAreaLeft, lineHeight, 0f);

            // ★ M1 — barre de caret si la ligne du curseur est dans la
            // fenêtre (wrap/inlay-aware par caretScreenPos, source
            // unique du pipeline principal). Le clignotement reste
            // piloté par le drawCaret principal : la loupe est active
            // pendant un geste, phase pleine du curseur.
            if (sel.isCursor() && view.caretVisible) {
                int caretDocLine = jo.codeeditor.view.EditorView.clamp(
                        doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
                if (caretDocLine >= firstLine && caretDocLine <= lastLine
                        && !view.isLineFoldedCached(caretDocLine)) {
                    float[] caretPos = view.caretScreenPos(sel.start);
                    float lineTop = view.docLineToY(caretDocLine) - view.vOffset;
                    view.caretPaint.setColor(view.theme.caret);
                    view.caretPaint.setStyle(Paint.Style.STROKE);
                    view.caretPaint.setStrokeWidth(2f);
                    canvas.drawLine(caretPos[0], lineTop,
                            caretPos[0], lineTop + lineHeight, view.caretPaint);
                }
            }

            // ★ B13 (P1-2) — caret FANTÔME du drag-déposer de bloc :
            // le POINT DE DÉPÔT dans la fenêtre de la loupe (accent
            // plus épais que le caret réel — l'utilisateur règle le
            // dépôt, la loupe montre ce qu'il règle, M1).
            if (view.blockDragCtl.isActive()
                    && view.blockDragCtl.getDropOffset() >= 0) {
                int dropOff = view.blockDragCtl.getDropOffset();
                int dropDocLine = jo.codeeditor.view.EditorView.clamp(
                        doc.lineForOffset(dropOff), 0, doc.lineCount() - 1);
                if (dropDocLine >= firstLine && dropDocLine <= lastLine
                        && !view.isLineFoldedCached(dropDocLine)) {
                    float[] dropPos = view.caretScreenPos(dropOff);
                    float dropTop = view.docLineToY(dropDocLine) - view.vOffset;
                    view.caretPaint.setColor(view.theme.caret);
                    view.caretPaint.setStyle(Paint.Style.STROKE);
                    view.caretPaint.setStrokeWidth(3f * density);
                    canvas.drawLine(dropPos[0], dropTop,
                            dropPos[0], dropTop + lineHeight, view.caretPaint);
                    view.caretPaint.setStrokeWidth(2f);
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
        // ★ Restauration de l'état du paint PARTAGÉ : la loupe est le
        // dernier peintre de la frame ; sans remise à FILL, la frame
        // SUIVANTE dessine la bande de sélection, les occurrences et les
        // poignées en CONTOUR — exactement le bug « je vois la bordure du
        // rectangle mais pas la couleur » qui apparaît dès que le drag
        // (et donc la loupe) démarre. La loupe s'active à l'amorce du
        // drag de poignée (EditorSelectionGestures.dragHandle) et reste
        // visible tant que le doigt bouge : toutes les frames du drag
        // héritaient du style STROKE.
        view.selPaint.setStyle(Paint.Style.FILL);
        view.selPaint.setStrokeWidth(0f);
    }

    /** Centre X de la loupe borné dans la largeur de la vue : la bulle
     *  (rayon donné) ne doit jamais être coupée par un bord horizontal. */
    static float clampMagnifierCx(float fingerX, float viewWidth, float radius) {
        if (viewWidth <= 2 * radius) return viewWidth / 2f;
        return Math.max(radius, Math.min(fingerX, viewWidth - radius));
    }

    /** Centre Y de la loupe : AU-DESSUS du doigt si la place suffit,
     *  SINON sous le doigt si la place y suffit, SINON borné dans ce qui
     *  reste de la vue (vue plus petite que la bulle : centre de la vue).
     *  La bulle reste ainsi entièrement visible quel que soit le bord
     *  approché. */
    static float resolveMagnifierCy(float fingerY, float viewHeight, float radius) {
        if (viewHeight <= 2 * radius) return viewHeight / 2f;
        float above = fingerY - radius * 1.8f;
        if (above >= radius) return above;
        float below = fingerY + radius * 1.8f;
        if (below <= viewHeight - radius) return below;
        return Math.min(viewHeight - radius, Math.max(radius, fingerY));
    }

    // ── B13 (P1-2) : carte flottante du drag-déposer de bloc ──────

    /**
     * B13 — la carte flottante du bloc DÉPLACÉ : un extrait du texte
     * photographié à l'armement (3 lignes max, ellipse par ligne, pied
     * « … » si le bloc est plus long), porté SOUS le doigt pendant le
     * déplacement (la loupe est AU-DESSUS — les deux instruments ne se
     * chevauchent jamais), bord accent + ombre portée discrète façon
     * TextView drag shadow.
     *
     * <p>Résolution de la position : sous le doigt à 20 dp (marge de
     * lisibilité du point de dépôt) ; si la carte ne tient pas en bas
     * (doigt près du bord inférieur), elle passe au-dessus du doigt —
     * la loupe, elle, bascule déjà de l'autre côté. Clampe
     * horizontale aux bords de la vue. Peints dédiés + chemin de
     * travail réutilisé ; l'extrait est borné (3×32 caractères —
     * allocations minimes, geste transitoire).</p>
     */
    void drawBlockDragCard(Canvas canvas) {
        if (!view.blockDragCtl.isActive()) return;
        String block = view.blockDragCtl.getBlockText();
        if (block == null || block.isEmpty()) return;
        float density = view.getResources().getDisplayMetrics().density;
        float viewW = view.getWidth();
        float viewH = view.getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        // ── Extrait : 3 lignes max, ellipse à 32 caractères ──
        // (lignes via indexOf — pas de split qui alloue un tableau).
        String[] shown = new String[BLOCK_CARD_MAX_LINES + 1];
        int lineCount = 0;
        int rest = 0;
        int from = 0;
        while (from <= block.length() && lineCount <= BLOCK_CARD_MAX_LINES) {
            int nl = block.indexOf('\n', from);
            String raw = nl < 0 ? block.substring(from) : block.substring(from, nl);
            if (lineCount == BLOCK_CARD_MAX_LINES) {
                // 4e ligne rencontrée → pied « … » et on s'arrête.
                shown[lineCount] = "…";
                lineCount++;
                break;
            }
            if (raw.length() > BLOCK_CARD_MAX_CHARS) {
                raw = raw.substring(0, BLOCK_CARD_MAX_CHARS - 1) + "…";
            }
            shown[lineCount++] = raw.isEmpty() ? " " : raw;
            if (nl < 0) break;
            from = nl + 1;
        }
        rest = lineCount;

        // ── Géométrie de la carte ──
        blockCardTextPaint.set(view.textPaint);
        blockCardTextPaint.setTypeface(view.metrics.getTypeface());
        blockCardTextPaint.setTextSize(view.metrics.getTextSize());
        blockCardTextPaint.setColor(view.theme.textColor);
        float textH = view.metrics.getLineHeight();
        float pad = 6f * density;
        float cardH = rest * textH + 2 * pad;
        float cardW = 0;
        for (int i = 0; i < rest; i++) {
            cardW = Math.max(cardW, blockCardTextPaint.measureText(shown[i]));
        }
        cardW += 2 * pad;
        float maxW = 240f * density;
        if (cardW > maxW) cardW = maxW;

        // Sous le doigt (la loupe est au-dessus) ; au-dessus si la
        // carte ne tient pas en bas ; marge 20 dp du doigt.
        float fingerX = view.blockDragCtl.getFingerX();
        float fingerY = view.blockDragCtl.getFingerY();
        float gap = 20f * density;
        float cardLeft = fingerX - cardW / 2f;
        if (cardLeft < 2f * density) cardLeft = 2f * density;
        if (cardLeft + cardW > viewW - 2f * density) {
            cardLeft = viewW - 2f * density - cardW;
        }
        float cardTop = fingerY + gap;
        if (cardTop + cardH > viewH - 2f * density) {
            cardTop = fingerY - gap - cardH;
        }
        if (cardTop < 2f * density) cardTop = 2f * density;

        // ── Ombre portée discrète (deux strokes concentriques), carte
        //    pleine, bord accent, texte ──
        float r = 8f * density;
        scratchPath.reset();
        scratchPath.addRoundRect(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH, r, r,
                Path.Direction.CW);
        blockCardPaint.setStyle(Paint.Style.STROKE);
        blockCardPaint.setStrokeWidth(3f * density);
        blockCardPaint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.18f));
        canvas.drawPath(scratchPath, blockCardPaint);
        blockCardPaint.setStrokeWidth(1.5f * density);
        blockCardPaint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.25f));
        canvas.drawPath(scratchPath, blockCardPaint);
        blockCardPaint.setStyle(Paint.Style.FILL);
        blockCardPaint.setColor(view.applyAlphaToColor(view.theme.editorBg, 0.96f));
        canvas.drawPath(scratchPath, blockCardPaint);
        blockCardPaint.setStyle(Paint.Style.STROKE);
        blockCardPaint.setStrokeWidth(1.5f * density);
        blockCardPaint.setColor(view.theme.caret);
        canvas.drawPath(scratchPath, blockCardPaint);
        // Restaure le paint DÉDIÉ en FILL (discipline des paints).
        blockCardPaint.setStyle(Paint.Style.FILL);
        blockCardPaint.setStrokeWidth(0f);

        for (int i = 0; i < rest; i++) {
            canvas.drawText(shown[i], cardLeft + pad,
                    cardTop + pad + textH * (i + 0.78f), blockCardTextPaint);
        }
    }

    // ── M3 (lot 2, J5) : voie native (android.widget.Magnifier) ──

    /**
     * M3 — décision de bascule vers la loupe native : drapeau de
     * l'hôte {@code enabled} ET niveau d'API ≥ 28 (le widget
     * plateforme n'existe qu'à partir de Pie). Pure et testable en
     * isolement (Robolectric ne peut pas instancier le Magnifier).
     * Appelable sous TOUTES les API — la garde SDK_INT est interne.
     *
     * @param enabled le drapeau {@code view.nativeMagnifierEnabled}
     * @return vrai si la voie native doit être prise
     */
    static boolean nativeMagnifierUsable(boolean enabled) {
        return enabled && android.os.Build.VERSION.SDK_INT
                >= android.os.Build.VERSION_CODES.P;
    }

    /** Affiche la bulle native centrée sur le point du doigt (création
     *  paresseuse — API 28+ garanti par {@link #nativeMagnifierUsable}). */
    private void showNativeMagnifier(float x, float y) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return;
        if (nativeMagnifier == null) {
            nativeMagnifier = new android.widget.Magnifier(view);
        }
        if (nativeMagnifier != null) {
            nativeMagnifier.show(x, y);
            nativeMagnifierShown = true;
        }
    }

    /**
     * M3 — referme la bulle native le cas échéant (no-op en voie
     * custom, ou jamais créée). Appelé par frame via
     * {@link #drawMagnifier(Canvas)} et au relâchement du geste via le
     * relais {@code EditorRenderer.dismissNativeMagnifier()} ←
     * {@code EditorView.dismissMagnifier()}.
     */
    @androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP)
    public void dismissNativeMagnifier() {
        // Garde lint/API : le widget n'existe que sous Pie — sous API 28,
        // le champ est de toute façon null (création paresseuse gardée).
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            return;
        }
        if (nativeMagnifier != null && nativeMagnifierShown) {
            nativeMagnifier.dismiss();
        }
        nativeMagnifierShown = false;
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
        RectF rect = scratchRect;
        rect.set(x, y, x + w, y + h);
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
                canvas.drawText(view.getResources().getString(
                                jo.codeeditor.ui.R.string.cel_nav_nothing_found),
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
                scratchRect.set(x + 1, rowTop, x + w - 1, rowBottom);
                canvas.drawRoundRect(scratchRect,
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
        // T1 (lot 2) : rangée de débordement de la pill (action d'hôte).
        if (row.ref instanceof jo.codeeditor.view.SelectionAction) {
            return ((jo.codeeditor.view.SelectionAction) row.ref).label;
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
                    RectF bulb = scratchRect;
                    bulb.set(cx - 3.0f * s, cy - 5.2f * s,
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
        RectF shadowRect = scratchRect;
        shadowRect.set(m.x + 1, m.y + 2, m.x + m.w + 1, m.y + m.h + 2);
        view.bgPaint.setStyle(Paint.Style.FILL);
        view.bgPaint.setColor(withAlpha(0xFF000000, 0.24f * overallAlpha));
        canvas.drawRoundRect(shadowRect, m.radius, m.radius, view.bgPaint);
        // Fond de pilule glass.
        RectF rect = scratchRect; // réutilisé après consommation de l'ombre
        rect.set(m.x, m.y, m.x + m.w, m.y + m.h);
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
                RectF pr = scratchRect;
                pr.set(m.itemX[i], m.y + 2 * density,
                        m.itemX[i] + m.itemW[i], m.y + m.h - 2 * density);
                view.bgPaint.setColor(withAlpha(view.theme.caret, 0.22f * itemAlpha));
                canvas.drawRoundRect(pr, m.radius * 0.7f, m.radius * 0.7f, view.bgPaint);
            }

            if (m.isIcon[i]) {
                drawSelectionToolbarIcon(canvas, m, i, contentColor, itemAlpha, dy, density);
            } else {
                // T1 : item injecté désactivé → estompé (tap rejeté).
                boolean disabled = i < m.itemDisabled.length && m.itemDisabled[i];
                if (disabled) {
                    contentColor = view.theme.gutterText;
                    itemAlpha *= 0.55f;
                }
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
