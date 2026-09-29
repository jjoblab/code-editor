package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Paint;

import jo.codeeditor.cache.LineRenderCache;
import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.highlight.LineSpan;
import jo.codeeditor.highlight.StyledLine;
import jo.codeeditor.highlight.TokenType;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Rendu du texte pur de l'éditeur : lignes stylisées (chemin
 * historique drawText par span et chemin ligatures via
 * StaticLayout), lignes repliées par retour à la ligne, ligne
 * composite d'un pli réduit, tokens sémantiques et inlay hints
 * tissés dans la ligne, caractères non imprimables.
 *
 * <p>Extrait d'EditorRenderer par composition afin d'isoler la
 * couche de texte du reste du pipeline de dessin (premier pas
 * vers un futur sous-package render/). Tout accès à l'état
 * passe par la référence {@code view} (champs package-privés
 * d'EditorView, même package).</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorTextPainter {
    private final EditorView view;

    /** Marge de colonnes de part et d'autre de la fenêtre visible — la
     *  loupe (zoom ~2x) et le surlignage de sélection débordent légèrement
     *  du viewport. */
    private static final int VISIBLE_COL_MARGIN = 16;
    /** Garde-fou : largeur minimale de la fenêtre en colonnes — une ligne
     *  courte ou un viewport étroit dessinent au moins cette tranche. */
    private static final int VISIBLE_COL_MIN = 64;

    EditorTextPainter(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Fenêtre de colonnes visibles (parité sora TextRow.draw)
    // ════════════════════════════════════════════════════════════════

    /**
     * Fenêtre de colonnes visibles {@code [première, dernière)} pour la
     * ligne donnée, dérivée du défilement horizontal ({@code hOffset}),
     * de la largeur du viewport et d'une marge.
     *
     * <p>★ Correctif « minifier » : sora-editor ne dessine jamais une
     * ligne entière — {@code TextRow.draw(canvas, beginOffset,
     * endOffset)} borne le dessin à la fenêtre horizontale visible. Une
     * ligne minifiée (des centaines de Ko sur une seule ligne) ne
     * façonnait ici que la tranche visible : le chemin ligatures
     * construisait un StaticLayout de la ligne ENTIÈRE (secondes de
     * façonnage + dizaines de Mo par frame), le chemin drawText
     * shaping chaque span géant, et drawNonPrintableChars itérait chaque
     * caractère. Désormais tout le dessin non-wrap est clippé à cette
     * fenêtre — le coût devient indépendant de la longueur de la
     * ligne.</p>
     */
    private int[] visibleColWindow(int lineLen) {
        float charWidth = view.metrics.getCharWidth();
        if (charWidth <= 0f || view.getWidth() <= 0) {
            return new int[]{0, lineLen};
        }
        int firstCol = Math.max(0,
                (int) Math.floor(view.hOffset / charWidth) - VISIBLE_COL_MARGIN);
        int viewportCols = (int) Math.ceil(view.getWidth() / charWidth);
        int lastCol = Math.min(lineLen, firstCol
                + Math.max(viewportCols + 2 * VISIBLE_COL_MARGIN, VISIBLE_COL_MIN));
        return new int[]{firstCol, lastCol};
    }

    /** Découpe les spans d'un StyledLine à la fenêtre {@code [from, to)}
     *  (bornes décalées) pour le rendu ligatures d'une tranche de ligne. */
    private static StyledLine sliceSpans(StyledLine styled, int from, int to) {
        java.util.ArrayList<LineSpan> out = new java.util.ArrayList<>(styled.spans.size());
        for (LineSpan span : styled.spans) {
            int start = Math.max(span.startCol, from);
            int end = Math.min(span.endCol, to);
            if (start >= end) continue;
            out.add(new LineSpan(start - from, end - from, span.type));
        }
        return new StyledLine(out, styled.entryState, styled.exitState);
    }

    /**
     * Dessine une ligne SANS style (retombe monochrome) clippée à la
     * fenêtre de colonnes visibles — remplace le drawText pleine ligne du
     * chemin de repli du renderer.
     */
    void drawPlainLine(Canvas canvas, String lineText, float x, float y, Paint paint) {
        if (lineText == null || lineText.isEmpty()) return;
        int[] win = visibleColWindow(lineText.length());
        int s = Math.max(0, win[0]);
        int e = Math.min(lineText.length(), win[1]);
        if (s >= e) return;
        canvas.drawText(lineText, s, e,
                x + s * view.metrics.getCharWidth(), y, paint);
    }

    // ════════════════════════════════════════════════════════════════
    // Caractères non imprimables
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine les indicateurs de caractères non imprimables (espaces,
     * tabulations, retours à la ligne) pour la ligne donnée. Appelé après
     * le dessin du texte stylisé de la ligne.
     *
     * <p>Représentation visuelle :
     * <ul>
     *   <li>Espace → point médian discret (·) centré dans la cellule du caractère</li>
     *   <li>Tabulation → flèche droite discrète (→) centrée dans la cellule du caractère</li>
     *   <li>Retour à la ligne final → ¬ discret en fin de ligne</li>
     * </ul>
     */
    void drawNonPrintableChars(Canvas canvas, String lineText, float x, float y,
                                        float charWidth, float lineHeight,
                                        List<LineRenderCache.InlayPiece> inlays, int[] rawToVisual) {
        view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
        view.textPaint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.35f));
        view.textPaint.setFakeBoldText(false);
        // ★ Ne itérer que la FENÊTRE VISIBLE de colonnes : sur une ligne
        // minifiée de 500 Ko, l'itération caractère par caractère
        // déclenchait jusqu'à 500 000 drawText par frame.
        int[] win = visibleColWindow(lineText.length());
        for (int i = Math.max(0, win[0]); i < Math.min(lineText.length(), win[1]); i++) {
            char c = lineText.charAt(i);
            // Position sensible aux inlays — la colonne visuelle du caractère
            // brut i est rawToVisual[i] + (inlays ancrés À i tissés avant lui).
            int vis = (rawToVisual != null && i < rawToVisual.length) ? rawToVisual[i] : i;
            if (c == ' ') {
                canvas.drawText("·", x + vis * charWidth + charWidth * 0.35f,
                    y + lineHeight * 0.78f, view.textPaint);
            } else if (c == '\t') {
                canvas.drawText("→", x + vis * charWidth + charWidth * 0.2f,
                    y + lineHeight * 0.78f, view.textPaint);
            }
        }
        // Indicateur de retour à la ligne final (¬) en fin de ligne —
        // seulement s'il tombe dans le viewport (sur une ligne minifiée,
        // la fin de ligne est à des centaines de milliers de colonnes).
        int endVis = (rawToVisual != null && lineText.length() < rawToVisual.length)
            ? rawToVisual[lineText.length()] : lineText.length();
        float nlX = x + endVis * charWidth + charWidth * 0.2f;
        if (nlX >= -charWidth && nlX <= view.getWidth() + charWidth) {
            canvas.drawText("¬", nlX, y + lineHeight * 0.78f, view.textPaint);
        }
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // Ligne de pli composite
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine le texte composite d'une ligne de tête de pli : préfixe +
     * placeholder + suffixe. Le placeholder est teinté d'un fond de chip
     * discret afin que le pli se distingue visuellement d'une ligne
     * normale.
     *
     * <p>La partie visible garde sa coloration syntaxique : le slice du
     * préfixe est délégué à {@link #drawStyledLine} (clippé à
     * {@code [0, prefixEndCol]}) et le slice du suffixe à un second appel
     * {@code drawStyledLine} sur le StyledLine de la endLine (clippé à
     * {@code [suffixStartCol, lastLine.length())}, avec un décalage X de la
     * largeur préfixe + placeholder). Sans cela, un
     * {@code public int add(int a, int b) {...}} replié perdrait la couleur
     * de ses mots-clés (public/int) et de ses types (int) — tout paraîtrait
     * blanc.
     */
    void drawCompositeFoldLine(Canvas canvas, EditorDocument doc,
                                DiagnosticShift.FoldRegion fold,
                                float x, float y, Paint paint) {
        float charWidth = view.metrics.getCharWidth();
        float lineHeight = view.metrics.getLineHeight();
        int startLine = doc.lineForOffset(fold.start);
        int endLine = doc.lineForOffset(fold.end);
        String firstLine = doc.lineText(startLine);
        String lastLine = endLine < doc.lineCount() ? doc.lineText(endLine) : "";
        int prefixEndCol = EditorView.clamp(fold.start - doc.lineStart(startLine), 0, firstLine.length());
        int suffixStartCol = EditorView.clamp(fold.end - doc.lineStart(endLine), 0, lastLine.length());
        String prefix = firstLine.substring(0, prefixEndCol);
        String suffix = lastLine.substring(suffixStartCol);
        float prefixW = prefix.length() * charWidth;
        float placeW = fold.placeholder.length() * charWidth;

        // Dessiner le préfixe avec coloration syntaxique en consultant le
        // StyledLine de la startLine et en parcourant ses spans, clippés à
        // [0, prefixEndCol]. Retombe sur theme.textColor si aucun style
        // n'est disponible (ex. aucun Language défini).
        boolean prefixColored = false;
        if (!prefix.isEmpty() && view.session != null) {
            LineRenderCache.LineCacheEntry layout = view.layoutForLine(startLine, firstLine);
            StyledLine styled = layout != null ? (StyledLine) layout.layout : null;
            if (styled != null && styled.spans != null && !styled.spans.isEmpty()) {
                // Parcourir les spans de la startLine mais clipper chacun à
                // [0, prefixEndCol]. Impossible d'appeler simplement
                // drawStyledLine(canvas, styled, firstLine, x, y, paint) :
                // cela dessinerait la ligne ENTIÈRE (y compris la partie
                // après le début du pli, cachée par la chip placeholder). On
                // réplique donc le corps de drawStyledLine en sautant les
                // spans qui n'intersectent pas [0, prefixEndCol].
                for (LineSpan span : styled.spans) {
                    int start = EditorView.clamp(span.startCol, 0, firstLine.length());
                    int end = EditorView.clamp(span.endCol, 0, firstLine.length());
                    // Cliper à la plage du préfixe.
                    if (end <= 0 || start >= prefixEndCol) continue;
                    int clippedStart = Math.max(start, 0);
                    int clippedEnd = Math.min(end, prefixEndCol);
                    if (clippedEnd <= clippedStart) continue;
                    String tokenText = firstLine.substring(clippedStart, clippedEnd);
                    // Support de l'aperçu de couleur — même comportement
                    // que drawStyledLine.
                    Integer colorBg = null;
                    if (tokenText.startsWith("#") && tokenText.length() >= 4) {
                        colorBg = parseColorLiteral(tokenText);
                    }
                    if (colorBg != null) {
                        float bgX1 = x + clippedStart * charWidth;
                        float bgX2 = x + clippedEnd * charWidth;
                        float bgY1 = y + lineHeight * 0.1f;
                        float bgY2 = y + lineHeight * 0.9f;
                        view.selPaint.setColor(colorBg);
                        view.selPaint.setAntiAlias(false);
                        android.graphics.RectF bgRect = new android.graphics.RectF(bgX1, bgY1, bgX2, bgY2);
                        canvas.drawRoundRect(bgRect, lineHeight * 0.15f, lineHeight * 0.15f, view.selPaint);
                        view.selPaint.setAntiAlias(true);
                        paint.setColor(getContrastColor(colorBg));
                    } else {
                        paint.setColor(view.theme.colorForToken(span.type));
                    }
                    canvas.drawText(tokenText, x + clippedStart * charWidth,
                        y + lineHeight * 0.78f, paint);
                }
                prefixColored = true;
            }
        }
        if (!prefixColored && !prefix.isEmpty()) {
            paint.setColor(view.theme.textColor);
            canvas.drawText(prefix, x, y + lineHeight * 0.78f, paint);
        }

        // Dessiner le fond de chip du placeholder + son texte.
        view.selPaint.setColor(view.theme.findMatch);
        canvas.drawRect(x + prefixW, y, x + prefixW + placeW, y + lineHeight, view.selPaint);
        // Teinter légèrement le texte du placeholder pour qu'il se lise
        // comme une chip.
        paint.setColor(view.theme.annotation != 0 ? view.theme.annotation : view.theme.textColor);
        canvas.drawText(fold.placeholder, x + prefixW, y + lineHeight * 0.78f, paint);

        // Dessiner le suffixe avec coloration syntaxique en consultant le
        // StyledLine de la endLine et en parcourant ses spans, clippés à
        // [suffixStartCol, lastLine.length()). Le décalage X couvre
        // prefixW + placeW (tout ce qui est dessiné avant le suffixe).
        boolean suffixColored = false;
        if (!suffix.isEmpty() && view.session != null) {
            LineRenderCache.LineCacheEntry layout = view.layoutForLine(endLine, lastLine);
            StyledLine styled = layout != null ? (StyledLine) layout.layout : null;
            if (styled != null && styled.spans != null && !styled.spans.isEmpty()) {
                float suffixX = x + prefixW + placeW;
                for (LineSpan span : styled.spans) {
                    int start = EditorView.clamp(span.startCol, 0, lastLine.length());
                    int end = EditorView.clamp(span.endCol, 0, lastLine.length());
                    // Cliper à la plage du suffixe [suffixStartCol, lastLine.length()).
                    if (end <= suffixStartCol || start >= lastLine.length()) continue;
                    int clippedStart = Math.max(start, suffixStartCol);
                    int clippedEnd = Math.min(end, lastLine.length());
                    if (clippedEnd <= clippedStart) continue;
                    String tokenText = lastLine.substring(clippedStart, clippedEnd);
                    Integer colorBg = null;
                    if (tokenText.startsWith("#") && tokenText.length() >= 4) {
                        colorBg = parseColorLiteral(tokenText);
                    }
                    if (colorBg != null) {
                        float bgX1 = suffixX + (clippedStart - suffixStartCol) * charWidth;
                        float bgX2 = suffixX + (clippedEnd - suffixStartCol) * charWidth;
                        float bgY1 = y + lineHeight * 0.1f;
                        float bgY2 = y + lineHeight * 0.9f;
                        view.selPaint.setColor(colorBg);
                        view.selPaint.setAntiAlias(false);
                        android.graphics.RectF bgRect = new android.graphics.RectF(bgX1, bgY1, bgX2, bgY2);
                        canvas.drawRoundRect(bgRect, lineHeight * 0.15f, lineHeight * 0.15f, view.selPaint);
                        view.selPaint.setAntiAlias(true);
                        paint.setColor(getContrastColor(colorBg));
                    } else {
                        paint.setColor(view.theme.colorForToken(span.type));
                    }
                    canvas.drawText(tokenText,
                        suffixX + (clippedStart - suffixStartCol) * charWidth,
                        y + lineHeight * 0.78f, paint);
                }
                suffixColored = true;
            }
        }
        if (!suffixColored && !suffix.isEmpty()) {
            paint.setColor(view.theme.textColor);
            canvas.drawText(suffix, x + prefixW + placeW, y + lineHeight * 0.78f, paint);
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Inlay hints (chemin historique d'itération globale — conservé par
    // parité, non appelé)
    // ════════════════════════════════════════════════════════════════

    /** Dessine les inlay hints en texte fantôme à leurs colonnes d'ancrage. */
    void drawInlayHints(Canvas canvas, EditorDocument doc,
                         int firstVisible, int lastVisible,
                         float textAreaLeft, float lineHeight, float paddingTop) {
        List<DiagnosticShift.InlayHint> hints = view.session.getInlayHints();
        if (hints.isEmpty()) return;
        float charWidth = view.metrics.getCharWidth();
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        view.textPaint.setColor(view.theme.annotation);
        // Fond discret
        view.selPaint.setColor(view.theme.findMatch);
        for (DiagnosticShift.InlayHint h : hints) {
            int line = doc.lineForOffset(h.offset);
            if (line < firstVisible || line > lastVisible) continue;
            // Sauter les inlay hints des lignes cachées (repliées).
            if (view.isLineFoldedCached(line)) continue;
            int col = h.offset - doc.lineStart(line);
            float x = textAreaLeft + col * charWidth - view.hOffset;
            float y = view.docLineToY(line) - view.vOffset; // Y sensible aux plis
            float w = view.textPaint.measureText(h.text);
            // Fond de chip
            view.selPaint.setAlpha(120);
            canvas.drawRect(x, y + lineHeight * 0.15f, x + w + 4, y + lineHeight * 0.85f, view.selPaint);
            view.selPaint.setAlpha(255);
            canvas.drawText(h.text, x + 2, y + lineHeight * 0.7f, view.textPaint);
        }
        // Restaurer la taille du paint de texte
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // Tokens sémantiques (chemin historique d'itération globale — conservé
    // par parité, non appelé)
    // ════════════════════════════════════════════════════════════════

    /**
     * Superpose les tokens sémantiques aux spans de coloration syntaxique
     * lexicale. Les tokens sémantiques viennent du serveur de langage
     * (méthode, classe, enum, etc.) et remplacent la couleur approximative
     * du lexer quand ils sont présents.
     *
     * <p>Chemin historique — itère la liste globale des tokens. Conservé pour
     * les appelants qui n'utilisent pas encore le cache par ligne (ex. lignes
     * repliées). Le chemin en cache passe par {@link #drawCachedSemSpans}.
     */
    void drawSemanticTokens(Canvas canvas, EditorDocument doc, int lineNum,
                             String lineText, float x, float y, Paint paint) {
        List<DiagnosticShift.SemanticToken> tokens = view.session.getSemanticTokens();
        if (tokens.isEmpty()) return;
        int lineStart = doc.lineStart(lineNum);
        int lineEnd = doc.lineEnd(lineNum);
        float charWidth = view.metrics.getCharWidth();
        float lineHeight = view.metrics.getLineHeight();
        for (DiagnosticShift.SemanticToken t : tokens) {
            int tokEnd = t.start + t.length;
            if (t.start >= lineEnd || tokEnd <= lineStart) continue;
            int startCol = EditorView.clamp(t.start - lineStart, 0, lineText.length());
            int endCol = EditorView.clamp(tokEnd - lineStart, 0, lineText.length());
            if (startCol >= endCol) continue;
            // Mapper type sémantique → couleur de token (réutiliser la
            // palette du lexer).
            TokenType ttype = EditorView.semanticTypeToTokenType(t.type);
            if (ttype == null) continue;
            paint.setColor(view.theme.colorForToken(ttype));
            String piece = lineText.substring(startCol, endCol);
            canvas.drawText(piece, x + startCol * charWidth, y + lineHeight * 0.78f, paint);
        }
    }

    /**
     * Dessine les spans sémantiques par ligne mis en cache (calculés une
     * fois en cas de miss par {@link EditorView#layoutForLine}). Équivalent
     * à {@link #drawSemanticTokens} mais O(spans de cette ligne) au lieu de
     * O(nombre global de tokens).
     *
     * <p>Sensible aux inlays — les spans sont dessinés aux colonnes
     * VISUELLES (tissées) pour qu'une plage colorée sémantiquement tombe
     * exactement sur le texte (décalé).
     */
    void drawCachedSemSpans(Canvas canvas, List<LineRenderCache.SemSpan> spans,
                             String lineText, float x, float y, Paint paint,
                             List<LineRenderCache.InlayPiece> inlays, int[] rawToVisual) {
        for (LineRenderCache.SemSpan s : spans) {
            int start = EditorView.clamp(s.start, 0, lineText.length());
            int end = EditorView.clamp(s.end, 0, lineText.length());
            if (start >= end) continue;
            paint.setColor(s.color);
            drawRawRange(canvas, lineText, inlays, rawToVisual, start, end, x, y, paint);
        }
    }

    /**
     * Dessine les inlay hints par ligne mis en cache en texte fantôme tissé
     * DANS la ligne. Le hint est dessiné à la colonne VISUELLE de son ancrage
     * (rawToVisual[col], c.-à-d. juste avant le caractère ancré) et occupe
     * {@code text.length()} colonnes visuelles ; le texte qui le suit a déjà
     * été décalé à droite par {@link #drawRawRange}, donc le hint ne recouvre
     * JAMAIS le code.
     *
     * <p>Style : inlays rendus en texte atténué italique sans chip de fond —
     * tonalité gutter-text + légère inclinaison (faux italique) à 85 % de la
     * taille.
     */
    void drawCachedInlays(Canvas canvas, List<LineRenderCache.InlayPiece> inlays,
                           int[] rawToVisual, float lineY, Paint paint) {
        float charWidth = view.metrics.getCharWidth();
        float lineHeight = view.metrics.getLineHeight();
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        paint.setTypeface(view.metrics.getTypeface());
        paint.setTextSize(view.metrics.getTextSize() * 0.85f);
        paint.setColor(view.applyAlphaToColor(view.theme.gutterText, 0.9f));
        paint.setTextSkewX(-0.25f); // faux italique pour monospace
        for (LineRenderCache.InlayPiece p : inlays) {
            int vis = (rawToVisual != null && p.col >= 0 && p.col < rawToVisual.length)
                ? rawToVisual[p.col] : p.col;
            float x = textAreaLeft + vis * charWidth - view.hOffset;
            if (x + p.text.length() * charWidth >= view.metrics.getGutterWidth()) {
                canvas.drawText(p.text, x + charWidth * 0.08f, lineY + lineHeight * 0.75f, paint);
            }
        }
        paint.setTextSkewX(0f);
        paint.setTextSize(view.metrics.getTextSize());
    }

    /**
     * Dessine la plage de texte brut {@code [start, end)} d'une ligne avec
     * les inlays de la ligne TISSÉS DEDANS — le dessin canonique du « texte
     * fantôme ». Chaque slice entre deux ancrages d'inlay est dessinée à sa
     * colonne épissée (visuelle), donc le code APRÈS un hint est décalé à
     * droite de la largeur du hint : la colonne visuelle de début de slice =
     * {@code rawToVisual[start]}, et chaque inlay ancré dans la plage fait
     * avancer le curseur visuel de la longueur de son texte avant que le
     * caractère ancré ne soit dessiné.
     */
    private void drawRawRange(Canvas canvas, String lineText,
                               List<LineRenderCache.InlayPiece> inlays, int[] rawToVisual,
                               int start, int end, float x, float y, Paint paint) {
        float charWidth = view.metrics.getCharWidth();
        float baseline = y + view.metrics.getLineHeight() * 0.78f;
        // ★ Clip à la fenêtre de colonnes visibles (parité sora
        // TextRow.draw) : un span géant de ligne minifiée ne dessine que
        // sa tranche visible.
        int[] win = visibleColWindow(lineText.length());
        int clippedStart = Math.max(start, win[0]);
        int clippedEnd = Math.min(end, win[1]);
        if (clippedStart >= clippedEnd) return;
        start = clippedStart;
        end = clippedEnd;
        if (inlays == null || inlays.isEmpty()) {
            canvas.drawText(lineText, start, end, x + start * charWidth, baseline, paint);
            return;
        }
        // Le curseur visuel démarre à rawToVisual[start] (= start + inlays
        // strictement avant start) — un inlay ancré À start le fait avancer
        // davantage.
        int vis = (rawToVisual != null && start >= 0 && start < rawToVisual.length)
            ? rawToVisual[start] : start;
        int pos = start;
        int i = 0;
        while (i < inlays.size() && inlays.get(i).col < start) i++;
        while (pos < end) {
            if (i < inlays.size() && inlays.get(i).col <= pos) {
                // Inlay ancré ici : il occupe des colonnes visuelles (dessiné
                // séparément par drawCachedInlays) — avancer au-delà de sa
                // largeur.
                vis += inlays.get(i).text.length();
                i++;
                continue;
            }
            int next = (i < inlays.size() && inlays.get(i).col < end) ? inlays.get(i).col : end;
            canvas.drawText(lineText, pos, next, x + vis * charWidth, baseline, paint);
            vis += next - pos;
            pos = next;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Ligne stylisée + aides couleur
    // ════════════════════════════════════════════════════════════════

    void drawStyledLine(Canvas canvas, StyledLine styled, String lineText,
                         float x, float y, Paint paint,
                         List<LineRenderCache.InlayPiece> inlays, int[] rawToVisual) {
        float lineHeight = view.metrics.getLineHeight();
        float charWidth = view.metrics.getCharWidth();

        // Mode ligatures de police — StaticLayout avec ForegroundColorSpan
        // pour que les ligatures se forment (≠ depuis !=, → depuis ->, etc.)
        // ET que les couleurs de syntaxe soient préservées. StaticLayout
        // traite le texte via le moteur de façonnage de la police, qui
        // applique les ligatures — contrairement à Canvas.drawText qui
        // dessine chaque glyphe indépendamment.
        //
        // ★ Rich spans : en plus des ForegroundColorSpan, on pose des
        // StyleSpan pour rendre les COMMENT en italique et les KEYWORD en
        // gras. Ces effets visuels améliorent la lisibilité (les
        // commentaires reculent, les mots-clés avancent) — convention de
        // tous les éditeurs pro (VS Code, IntelliJ, Sublime).
        //
        // ★ Le StaticLayout façonné est mémoïsé CONTENU-ADRESSÉ par
        // EditorView.shapedLayoutFor : ~25 % des lignes d'un fichier réel
        // sont identiques ("}", "    }") et le façonnage + le
        // SpannableStringBuilder par frame étaient le coût dominant du path
        // ligatures. Les lignes identiques partagent désormais UN seul
        // layout.
        if (view.fontLigatures && styled != null && styled.spans != null) {
            // ★ Fenêtre visible (parité sora TextRow.draw(begin, end)) :
            // ne façonner QUE la tranche visible. Un StaticLayout de la
            // ligne entière minifiée (500 Ko) coûtait plusieurs secondes
            // de façonnage et des dizaines de Mo ; la tranche est bornée
            // par le viewport (~100-200 colonnes).
            int[] win = visibleColWindow(lineText.length());
            int firstCol = win[0];
            int lastCol = win[1];
            if (lastCol <= firstCol) return;
            if (lastCol - firstCol < lineText.length()) {
                String slice = lineText.substring(firstCol, lastCol);
                StyledLine sliced = sliceSpans(styled, firstCol, lastCol);
                android.text.StaticLayout sl = view.shapedLayoutFor(slice, sliced, paint);
                canvas.save();
                canvas.translate(x + firstCol * charWidth,
                        y + lineHeight * 0.78f - sl.getLineBaseline(0));
                sl.draw(canvas);
                canvas.restore();
                return;
            }
            // Ligne courte (tient dans la fenêtre) : chemin intégral.
            android.text.StaticLayout sl = view.shapedLayoutFor(lineText, styled, paint);
            canvas.save();
            canvas.translate(x, y + lineHeight * 0.78f - sl.getLineBaseline(0));
            sl.draw(canvas);
            canvas.restore();
            return;
        }

        // ★ Path sans ligatures : on garde le rendu drawText historique mais
        // on bascule paint.setTypeface() par span pour le rendu
        // italique/gras. Comme le path ligatures est le défaut (98 % des
        // appareils modernes supportent les ligatures de JetBrains Mono),
        // ce path est rarement pris — mais on garde la cohérence visuelle.
        android.graphics.Typeface savedTypeface = paint.getTypeface();
        // ★ Clip global à la fenêtre visible : les spans hors écran sont
        // sautés, les spans géants ne dessinent que leur tranche visible
        // (drawRawRange re-clippe, mais le substring + le fond de couleur
        // précalculés restent bornés au viewport).
        int[] win = visibleColWindow(lineText.length());
        for (LineSpan span : styled.spans) {
            int start = EditorView.clamp(span.startCol, 0, lineText.length());
            int end = EditorView.clamp(span.endCol, 0, lineText.length());
            if (end <= win[0] || start >= win[1]) continue;  // hors fenêtre
            start = Math.max(start, win[0]);
            end = Math.min(end, win[1]);
            if (start >= end) continue;
            String tokenText = lineText.substring(start, end);

            // Appliquer italique/gras pour COMMENT/KEYWORD/ANNOTATION.
            int styleFlag = 0;  // Typeface.NORMAL
            if (span.type == jo.codeeditor.highlight.TokenType.COMMENT) {
                styleFlag = android.graphics.Typeface.ITALIC;
            } else if (span.type == jo.codeeditor.highlight.TokenType.KEYWORD
                    || span.type == jo.codeeditor.highlight.TokenType.ANNOTATION) {
                styleFlag = android.graphics.Typeface.BOLD;
            }
            if (styleFlag != 0) {
                paint.setTypeface(android.graphics.Typeface.create(
                    savedTypeface, styleFlag));
            } else if (paint.getTypeface() != savedTypeface) {
                paint.setTypeface(savedTypeface);
            }

            // Aperçu de couleur — le code lui-même reçoit un fond coloré.
            // #RRGGBB, #RGB, #RRGGBBAA, #RGBA sont rendus avec la couleur
            // réelle en fond et une couleur de texte contrastée par-dessus.
            Integer colorBg = null;
            if (tokenText.startsWith("#") && tokenText.length() >= 4) {
                colorBg = parseColorLiteral(tokenText);
            }
            if (colorBg != null) {
                // Dessiner le fond coloré derrière le texte du token.
                float bgX1 = x + start * charWidth;
                float bgX2 = x + end * charWidth;
                float bgY1 = y + lineHeight * 0.1f;
                float bgY2 = y + lineHeight * 0.9f;
                view.selPaint.setColor(colorBg);
                view.selPaint.setAntiAlias(false);
                // Rectangle arrondi pour un rendu plus agréable.
                android.graphics.RectF bgRect = new android.graphics.RectF(bgX1, bgY1, bgX2, bgY2);
                canvas.drawRoundRect(bgRect, lineHeight * 0.15f, lineHeight * 0.15f, view.selPaint);
                view.selPaint.setAntiAlias(true);
                // Choisir une couleur de texte contrastée (noir ou blanc selon la luminance).
                int contrastColor = getContrastColor(colorBg);
                paint.setColor(contrastColor);
            } else {
                paint.setColor(view.theme.colorForToken(span.type));
            }
            // Dessin sensible aux inlays — les slices aux ancrages d'inlay
            // sont dessinées à leurs colonnes épissées (visuelles) pour que
            // le code après un hint soit décalé à droite au lieu d'être
            // recouvert.
            drawRawRange(canvas, lineText, inlays, rawToVisual, start, end, x, y, paint);
        }
    }

    /**
     * Renvoie noir ou blanc selon celui qui contraste le mieux avec la
     * couleur de fond donnée.
     */
    private static int getContrastColor(int bgColor) {
        int r = (bgColor >> 16) & 0xFF;
        int g = (bgColor >> 8) & 0xFF;
        int b = bgColor & 0xFF;
        // Luminance relative (luminosité perceptive).
        double luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
        return luminance > 0.5 ? 0xFF000000 : 0xFFFFFFFF;
    }

    /**
     * Analyse un littéral de couleur (#RGB, #RRGGBB, #RRGGBBAA, #AARRGGBB)
     * en un int de couleur Android. Renvoie null si ce n'est pas une
     * couleur valide.
     */
    private static Integer parseColorLiteral(String text) {
        if (text == null || !text.startsWith("#")) return null;
        try {
            int len = text.length() - 1; // sans le #
            if (len == 3) {
                // #RGB → #RRGGBB
                String r = String.valueOf(text.charAt(1));
                String g = String.valueOf(text.charAt(2));
                String b = String.valueOf(text.charAt(3));
                return (0xFF000000 | Integer.parseInt(r + r + g + g + b + b, 16));
            } else if (len == 4) {
                // #RGBA → #RRGGBBAA
                String r = String.valueOf(text.charAt(1));
                String g = String.valueOf(text.charAt(2));
                String b = String.valueOf(text.charAt(3));
                String a = String.valueOf(text.charAt(4));
                int rgb = Integer.parseInt(r + r + g + g + b + b, 16);
                int alpha = Integer.parseInt(a + a, 16);
                return (alpha << 24) | rgb;
            } else if (len == 6 || len == 8) {
                return (int) android.graphics.Color.parseColor(text);
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // Ligne repliée (wrap)
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine une ligne repliée (wrap) en N rangées visuelles. Chaque
     * rangée montre la slice de la ligne correspondant à sa plage de
     * colonnes, avec les couleurs de spans du lexer.
     *
     * <p>Les rangées de continuation sont indentées de la colonne
     * d'espaces de tête de la ligne (style VS Code / IntelliJ). La
     * première rangée est dessinée à {@code textAreaLeft} ; les suivantes
     * à {@code textAreaLeft + leadingWhitespaceCols * charWidth} pour
     * qu'un corps de méthode Java replié s'aligne sous le nom de la
     * méthode, pas sous la colonne 0.</p>
     */
    void drawWrappedLine(Canvas canvas, EditorDocument doc,
                          List<StyledLine> styledLines, int lineNum,
                          String lineText, float textAreaLeft,
                          float lineTopY, float lineHeight) {
        float charWidth = view.metrics.getCharWidth();
        int lineLen = lineText.length();
        // ★ Géométrie UNIQUE (EditorView.wrapRowsFor) — comptage et
        // découpage ne peuvent pas diverger. Un comptage rows =
        // ceil(len/maxCols) avec un découpage des rangées de continuation
        // sur un colsPerContinuationRow ÉTROIT laisserait la queue de la
        // ligne (jusqu'à wrapIndentCols caractères) jamais dessinée.
        EditorView.WrapRows wr = view.wrapRowsFor(lineNum, lineLen);
        int rows = wr.rows;
        StyledLine styled = lineNum < styledLines.size() ? styledLines.get(lineNum) : null;

        // ★ N'itérer que les rangées VISIBLES : une ligne minifiée avec
        // word-wrap s'étale sur des dizaines de milliers de rangées —
        // seule la trentaine visible doit être dessinée (parité sora :
        // le rendu borne par le viewport, pas par la ligne).
        float viewH = view.getHeight();
        int rFirst = Math.max(0,
                (int) Math.floor((-lineHeight - lineTopY) / lineHeight));
        int rLast = Math.min(rows - 1,
                (int) Math.ceil((viewH - lineTopY) / lineHeight));
        // Pointeur de départ dans les spans (triés par startCol, sortie
        // du tokenizer) : les rangées étant parcourues en ordre croissant,
        // les spans terminés avant la rangée courante ne sont JAMAIS
        // réexaminés — O(spans) au total au lieu de O(rangées × spans)
        // sur une ligne minifiée à des milliers de spans.
        int spanBase = 0;
        for (int r = rFirst; r <= rLast; r++) {
            int rowStartCol = wr.rowStartCol(r);
            int rowEndCol = wr.rowEndCol(r, lineLen);
            float rowX = r == 0 ? textAreaLeft
                    : textAreaLeft + wr.wrapIndentCols * charWidth;
            float rowY = lineTopY + r * lineHeight;
            if (rowEndCol <= rowStartCol) continue;
            if (styled != null) {
                // Dessiner chaque span clippé à la plage de colonnes de cette rangée.
                while (spanBase < styled.spans.size()
                        && styled.spans.get(spanBase).endCol <= rowStartCol) {
                    spanBase++;
                }
                for (int si = spanBase; si < styled.spans.size(); si++) {
                    LineSpan span = styled.spans.get(si);
                    if (span.startCol >= rowEndCol) break;
                    int start = EditorView.clamp(span.startCol, 0, lineLen);
                    int end = EditorView.clamp(span.endCol, 0, lineLen);
                    if (end <= rowStartCol || start >= rowEndCol) continue;
                    int drawStart = Math.max(start, rowStartCol);
                    int drawEnd = Math.min(end, rowEndCol);
                    if (drawStart >= drawEnd) continue;
                    view.textPaint.setColor(view.theme.colorForToken(span.type));
                    String tokenText = lineText.substring(drawStart, drawEnd);
                    canvas.drawText(tokenText,
                        rowX + (drawStart - rowStartCol) * charWidth,
                        rowY + lineHeight * 0.78f, view.textPaint);
                }
            } else {
                view.textPaint.setColor(view.theme.textColor);
                String rowText = lineText.substring(rowStartCol, rowEndCol);
                canvas.drawText(rowText, rowX, rowY + lineHeight * 0.78f, view.textPaint);
            }
        }
    }
}
