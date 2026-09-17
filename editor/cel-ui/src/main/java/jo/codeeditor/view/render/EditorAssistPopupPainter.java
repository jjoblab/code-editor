package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;

import androidx.annotation.RestrictTo;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.document.Selection;
import jo.codeeditor.navigation.NavigationMenu;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.ArrayList;
import java.util.List;

/**
 * Popups d'assistance flottants de l'éditeur : popup de complétion
 * (liste + badges de type + runs de match), popup d'aide de signature
 * (surcharges + documentation de la signature active), popup de
 * documentation rapide (quick doc), ampoules de code actions dans la
 * bande de plis + leur popup, et popup d'aller-au-symbole.
 *
 * <p>Extrait d'EditorRenderer par composition — isole la couche des
 * popups d'assistance du reste du pipeline (vers un futur sous-package
 * render/). Tout accès à l'état passe par la référence {@code view}
 * (champs package-privés d'EditorView, même package). La géométrie du
 * quick doc est partagée avec les hit-tests tactiles via
 * {@link #quickDocMetrics()}.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class EditorAssistPopupPainter {
    private final EditorView view;

    // Objets de travail réutilisables — évite les allocations par frame.
    // Usage mono-thread (uniquement depuis onDraw sur le thread UI).
    private final Path scratchPath = new Path();

    /** Rayons par coin du popup de complétion (réutilisé chaque frame). */
    private final float[] completionCornerRadii = new float[8];

    // Rayon de bordure du popup de complétion (les constantes hauteur de
    // ligne / largeur / nombre max de lignes restent sur EditorView car le
    // code tactile / de hit-test en a aussi besoin).
    // 12 dp — coins du HAUT seuls, plus visibles.
    static final float COMPLETION_BORDER_RADIUS_DP = 12f;
    // Popup d'aide de signature
    static final float SIGNATURE_HELP_ROW_HEIGHT_DP = 22f;
    static final float SIGNATURE_HELP_WIDTH_DP = 320f;
    // 12 dp + bande de documentation sous la liste.
    static final float SIGNATURE_HELP_RADIUS_DP = 12f;
    static final int SIGNATURE_HELP_MAX_ROWS = 10;
    /** Lignes max de la documentation de la signature active. */
    static final int SIGNATURE_DOC_MAX_LINES = 6;
    // Popup de documentation rapide
    // 12 dp — carte flottante.
    static final float QUICK_DOC_MAX_WIDTH_DP = 320f;
    static final float QUICK_DOC_MAX_HEIGHT_DP = 300f;
    static final float QUICK_DOC_RADIUS_DP = 12f;
    // Ampoule des code actions
    // Réduit à 5,5 dp — l'ampoule (+ son anneau de halo) déborde sinon de la
    // bande de plis (~16,8 dp) et de la hauteur de ligne ; 5,5 dp la garde
    // confortablement à l'intérieur.
    static final float LIGHTBULB_RADIUS_DP = 5.5f;

    EditorAssistPopupPainter(EditorView view) {
        this.view = view;
    }

    // ════════════════════════════════════════════════════════════════
    // Popup d'aide de signature
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine le popup d'aide de signature. Ancré AU-DESSUS de la ligne du
     * curseur (contrairement au popup de complétion, qui est EN DESSOUS).
     * S'il n'y a pas de place au-dessus, basculer en dessous. Chaque
     * signature tient sur une rangée ; le paramètre actif est surligné en
     * gras + couleur d'accent.
     */
    void drawSignatureHelpPopup(Canvas canvas) {
        if (!view.signatureHelpVisible || view.signatureHelpData == null
            || view.signatureHelpData.signatures == null
            || view.signatureHelpData.signatures.isEmpty()) {
            return;
        }
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = SIGNATURE_HELP_ROW_HEIGHT_DP * density;
        float width = SIGNATURE_HELP_WIDTH_DP * density;
        float radius = SIGNATURE_HELP_RADIUS_DP * density;
        int rowsToShow = Math.min(SIGNATURE_HELP_MAX_ROWS, view.signatureHelpData.signatures.size());

        // ── ★ Documentation de la signature ACTIVE (javadoc de sa
        // déclaration source) : bande sous la liste des surcharges, motif
        // IntelliJ. Parsée par QuickDoc (description + sections compactées),
        // plafonnée à SIGNATURE_DOC_MAX_LINES lignes.
        //
        // ── ★ L'index actif vient du controller (override clavier Up/Down
        // via cycleActiveSignature). Si l'override n'est pas posé, on
        // retombe sur activeSignature du serveur LSP.
        List<String> docLines = new ArrayList<>();
        int activeIdx = view.getEffectiveActiveSignature();
        if (activeIdx < 0) {
            activeIdx = Math.max(0, Math.min(view.signatureHelpData.activeSignature,
                    view.signatureHelpData.signatures.size() - 1));
        }
        jo.codeeditor.completion.SignatureHelpController.Signature activeSig =
                view.signatureHelpData.signatures.get(activeIdx);
        if (activeSig != null && activeSig.documentation != null
                && !activeSig.documentation.isEmpty()) {
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            jo.codeeditor.doc.QuickDoc.QuickDocContent parsed =
                    jo.codeeditor.doc.QuickDoc.parseQuickDoc(
                            activeSig.documentation, "java");
            float docWidth = width - 16 * density;
            if (!parsed.description.isEmpty()) {
                docLines.addAll(wrapText(parsed.description, docWidth, view.textPaint));
            }
            for (jo.codeeditor.doc.QuickDoc.DocSection s : parsed.sections) {
                if (docLines.size() >= SIGNATURE_DOC_MAX_LINES) break;
                for (String item : s.items) {
                    if (docLines.size() >= SIGNATURE_DOC_MAX_LINES) break;
                    String line = s.title + " " + item;
                    docLines.addAll(wrapText(line, docWidth, view.textPaint));
                }
            }
            if (docLines.size() > SIGNATURE_DOC_MAX_LINES) {
                docLines = new ArrayList<>(
                        docLines.subList(0, SIGNATURE_DOC_MAX_LINES));
            }
        }
        float docRowH = view.metrics.getTextSize() * 0.75f * 1.3f;
        float docH = docLines.isEmpty() ? 0
                : docLines.size() * docRowH + 8 * density;
        float popupH = rowH * rowsToShow + docH;

        // Ancrage X = colonne du curseur, Y = haut de la ligne du curseur.
        // Sensible aux inlays — le popup suit le X tissé du curseur.
        EditorDocument doc = view.session.getDocument();
        Selection sel = view.clampSelection(view.session.getSelection(), doc);
        int line = EditorView.clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        int col = sel.start - doc.lineStart(line);
        float anchorX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
            + view.visualColFor(line, col) * view.metrics.getCharWidth() - view.hOffset;
        float caretY = view.docLineToY(line) - view.vOffset;
        // Par défaut : le popup se place AU-DESSUS de la ligne du curseur.
        float anchorY = caretY - 4 - popupH;
        // S'il ne tient pas au-dessus, basculer en dessous.
        if (anchorY < 0) {
            anchorY = caretY + view.metrics.getLineHeight() + 4;
        }
        // Clamp bas — le popup ne sort pas de l'éditeur.
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, view.getHeight() - popupH - 4);
        }
        // Clamp horizontal.
        float viewW = view.getWidth();
        if (anchorX + width > viewW) {
            anchorX = Math.max(view.metrics.getGutterWidth(), viewW - width - 4);
        }

        // Fond + bordure
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + popupH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);

        // Rangées — clippées au rect (labels longs ellipsisés de fait).
        canvas.save();
        canvas.clipRect(rect);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        float padX = 8 * density;
        for (int i = 0; i < rowsToShow; i++) {
            jo.codeeditor.completion.SignatureHelpController.Signature sig =
                view.signatureHelpData.signatures.get(i);
            float y = anchorY + i * rowH;
            // Surligner la rangée de la signature active.
            // Utiliser la signature active effective (sensible à l'override).
            if (i == activeIdx) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            // Dessiner le label. Le paramètre actif est rendu en couleur
            // d'accent + gras en découpant le label autour de lui.
            String label = sig.label;
            int activeParam = sig.activeParameter;
            // Trouver la plage du paramètre actif dans le label (au mieux :
            // découpe par virgules à profondeur 0 — même logique
            // qu'activeParameterIndex).
            int[] paramRange = findParameterRangeInLabel(label, activeParam);
            if (paramRange == null) {
                view.textPaint.setColor(view.theme.textColor);
                canvas.drawText(label, anchorX + padX, y + rowH * 0.7f, view.textPaint);
            } else {
                // Dessiner pré-param, param (accent + gras), post-param.
                String pre = label.substring(0, paramRange[0]);
                String param = label.substring(paramRange[0], paramRange[1]);
                String post = label.substring(paramRange[1]);
                float x = anchorX + padX;
                if (!pre.isEmpty()) {
                    view.textPaint.setColor(view.theme.textColor);
                    view.textPaint.setTypeface(view.metrics.getTypeface());
                    canvas.drawText(pre, x, y + rowH * 0.7f, view.textPaint);
                    x += view.textPaint.measureText(pre);
                }
                if (!param.isEmpty()) {
                    view.textPaint.setColor(view.theme.func);
                    android.graphics.Typeface bold = android.graphics.Typeface.create(
                        view.metrics.getTypeface(), android.graphics.Typeface.BOLD);
                    view.textPaint.setTypeface(bold);
                    canvas.drawText(param, x, y + rowH * 0.7f, view.textPaint);
                    x += view.textPaint.measureText(param);
                }
                if (!post.isEmpty()) {
                    view.textPaint.setColor(view.theme.textColor);
                    view.textPaint.setTypeface(view.metrics.getTypeface());
                    canvas.drawText(post, x, y + rowH * 0.7f, view.textPaint);
                }
            }
        }
        // ── ★ Bande documentation de la signature ACTIVE ──
        if (!docLines.isEmpty()) {
            float docTop = anchorY + rowsToShow * rowH;
            // Divider + fond légèrement teinté.
            view.caretPaint.setStyle(Paint.Style.STROKE);
            view.caretPaint.setStrokeWidth(1f);
            view.caretPaint.setColor(view.theme.glassBorder);
            canvas.drawLine(anchorX, docTop, anchorX + width, docTop,
                    view.caretPaint);
            view.selPaint.setColor(view.theme.selection);
            canvas.drawRect(anchorX, docTop, anchorX + width,
                    anchorY + popupH, view.selPaint);
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            for (int i = 0; i < docLines.size(); i++) {
                String l = docLines.get(i);
                boolean isTag = !l.isEmpty() && l.charAt(0) == '@';
                view.textPaint.setColor(isTag
                        ? view.theme.keyword : view.theme.textColor);
                float baseline = docTop + 4 * density + i * docRowH
                        + docRowH * 0.75f;
                canvas.drawText(l, anchorX + padX, baseline, view.textPaint);
            }
        }
        canvas.restore();
        // Réinitialiser l'état du paint.
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    /**
     * Au mieux : trouve la plage [start, end) du paramètre actif dans un
     * label de signature comme {@code "foo(int x, String y, int z)"} afin
     * de le rendre en couleur d'accent. Renvoie null si introuvable.
     */
    private static int[] findParameterRangeInLabel(String label, int activeParam) {
        int openIdx = label.indexOf('(');
        if (openIdx < 0) return null;
        int closeIdx = label.lastIndexOf(')');
        if (closeIdx <= openIdx) return null;
        int depth = 0;
        int paramStart = openIdx + 1;
        int paramIdx = 0;
        for (int i = openIdx + 1; i < closeIdx; i++) {
            char c = label.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                if (paramIdx == activeParam) {
                    return new int[]{paramStart, i};
                }
                paramStart = i + 1;
                paramIdx++;
            }
        }
        // Dernier paramètre
        if (paramIdx == activeParam && paramStart < closeIdx) {
            return new int[]{paramStart, closeIdx};
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    // Popup de documentation rapide
    // ════════════════════════════════════════════════════════════════

    /**
     * Une ligne rendue du quick doc, avec son style.
     * SIG = signature (fence) sur fond teinté ; SECTION = titre @param/… ;
     * ITEM = élément de section indenté ; TEXT = corps de description.
     */
    static final class DocRow {
        static final int SIG = 0;
        static final int TEXT = 1;
        static final int SECTION = 2;
        static final int ITEM = 3;
        final int type;
        final String text;
        DocRow(int type, String text) { this.type = type; this.text = text; }
    }

    /**
     * Construit les lignes du quick doc : signature (fences)
     * en tête, puis description wrappée, puis sections. Le wrap est fait
     * sur le texte BRUT (les backticks de l'inline code ne changent pas la
     * largeur — seule la couleur change au rendu).
     */
    private List<DocRow> buildQuickDocRows(float textWidth, Paint paint) {
        List<DocRow> rows = new ArrayList<>();
        jo.codeeditor.doc.QuickDoc.QuickDocContent c = view.quickDocContent;
        if (c == null) return rows;
        if (c.signature != null && !c.signature.isEmpty()) {
            for (String line : c.signature.split("\n", -1)) {
                for (String w : wrapText(line, textWidth - 16, paint)) {
                    rows.add(new DocRow(DocRow.SIG, w));
                }
            }
        }
        if (c.description != null && !c.description.isEmpty()) {
            for (String w : wrapText(c.description, textWidth, paint)) {
                rows.add(new DocRow(DocRow.TEXT, w));
            }
        }
        for (jo.codeeditor.doc.QuickDoc.DocSection s : c.sections) {
            rows.add(new DocRow(DocRow.SECTION, s.title));
            for (String item : s.items) {
                for (String w : wrapText(item, textWidth - 12, paint)) {
                    rows.add(new DocRow(DocRow.ITEM, "  " + w));
                }
            }
        }
        return rows;
    }

    /**
     * Géométrie du quick doc : source unique pour le rendu ET le
     * hit-test. {@code out} reçoit {anchorX, anchorY, popupW, popupH,
     * contentH, rowH} en px écran, ou la méthode rend null (popup absent ou
     * ligne d'ancre hors viewport → le popup suit le texte au scroll).
     */
    float[] quickDocMetrics() {
        if (!view.quickDocVisible || view.quickDocContent == null
                || view.session == null) {
            return null;
        }
        float density = view.getResources().getDisplayMetrics().density;
        // Largeur : 80 % de l'éditeur, plafonnée à 320 dp.
        float maxW = Math.min(QUICK_DOC_MAX_WIDTH_DP * density,
                view.getWidth() * 0.8f);
        float maxH = QUICK_DOC_MAX_HEIGHT_DP * density;
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        float padX = 10 * density;
        float padY = 8 * density;
        float rowH = view.metrics.getTextSize() * 1.25f;
        List<DocRow> rows = buildQuickDocRows(maxW - 2 * padX, view.textPaint);
        if (rows.isEmpty()) return null;
        // Largeur réelle = plus longue ligne, capée.
        float textW = 0;
        for (DocRow r : rows) {
            float w = view.textPaint.measureText(r.text);
            if (w > textW) textW = w;
        }
        float popupW = Math.min(maxW, textW + 2 * padX);
        float contentH = rows.size() * rowH + 2 * padY;
        float popupH = Math.min(maxH, contentH);
        // ── Ancrage du popup ──
        EditorDocument doc = view.session.getDocument();
        int safeOffset = Math.min(view.quickDocAnchorOffset, doc.length());
        int line = EditorView.clamp(doc.lineForOffset(safeOffset), 0, doc.lineCount() - 1);
        int col = safeOffset - doc.lineStart(line);
        float charX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
                + (view.visualColFor(line, col) + 0.5f) * view.metrics.getCharWidth()
                - view.hOffset;
        float lineTopY = view.docLineToY(line) - view.vOffset;
        float lineBottomY = lineTopY + view.rowsForDocLine(line)
                * view.metrics.getLineHeight();
        // L'ancre sort du viewport → le popup ne se dessine pas (il
        // reviendra si la ligne revient — il suit le texte).
        if (lineBottomY < 0 || lineTopY > view.getHeight()) return null;
        // Centré sur le caractère (charX - width/2), clampé.
        float anchorX = charX - popupW * 0.5f;
        if (anchorX + popupW > view.getWidth()) {
            anchorX = view.getWidth() - popupW - 4 * density;
        }
        if (anchorX < view.metrics.getGutterWidth()) {
            anchorX = view.metrics.getGutterWidth();
        }
        // AU-DESSUS de la ligne par défaut, en dessous sinon.
        float gap = 10 * density;
        float anchorY = lineTopY - gap - popupH;
        if (anchorY < 0) anchorY = lineBottomY + gap;
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, view.getHeight() - popupH - 4 * density);
        }
        // Compat : les anciens champs de coordonnées restent nourris.
        view.quickDocX = charX;
        view.quickDocY = lineTopY;
        return new float[]{anchorX, anchorY, popupW, popupH, contentH, rowH};
    }

    /**
     * Dessine le popup de quick doc : ancrage centré sur le caractère
     * (au-dessus de la ligne par défaut), le popup SUIT le texte au scroll,
     * signature (fence) en tête sur fond teinté, inline code coloré, corps
     * scrollable au drag.
     */
    void drawQuickDocPopup(Canvas canvas) {
        if (!view.quickDocVisible || view.quickDocContent == null) return;
        float[] m = quickDocMetrics();
        if (m == null) return;
        float density = view.getResources().getDisplayMetrics().density;
        float radius = QUICK_DOC_RADIUS_DP * density;
        float anchorX = m[0], anchorY = m[1];
        float popupW = m[2], popupH = m[3], contentH = m[4], rowH = m[5];
        float padX = 10 * density;
        float padY = 8 * density;

        // Clamp du scroll du corps (le drag met à jour quickDocScrollY).
        float maxScroll = Math.max(0, contentH - popupH);
        if (view.quickDocScrollY < 0) view.quickDocScrollY = 0;
        if (view.quickDocScrollY > maxScroll) view.quickDocScrollY = maxScroll;
        float scrollY = view.quickDocScrollY;

        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        List<DocRow> rows = buildQuickDocRows(popupW - 2 * padX, view.textPaint);

        // Fond + bordure
        RectF rect = new RectF(anchorX, anchorY, anchorX + popupW, anchorY + popupH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);

        // Corps scrollable, clippé au rect.
        canvas.save();
        canvas.clipRect(rect);
        float sigBgBottom = -1f;
        for (int i = 0; i < rows.size(); i++) {
            DocRow r = rows.get(i);
            float rowTop = anchorY + padY + i * rowH - scrollY;
            float rowBottom = rowTop + rowH;
            if (rowBottom < anchorY || rowTop > anchorY + popupH) continue;
            float baseline = rowTop + rowH * 0.78f;
            if (r.type == DocRow.SIG) {
                // Bande teinte pleine largeur pour la signature.
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX, rowTop - 1, anchorX + popupW,
                        rowBottom, view.selPaint);
                sigBgBottom = rowBottom;
                drawInlineCodeLine(canvas, r.text, anchorX + padX, baseline,
                        view.theme.textColor, view.theme.func);
            } else if (r.type == DocRow.SECTION) {
                view.textPaint.setColor(view.theme.keyword);
                view.textPaint.setFakeBoldText(true);
                canvas.drawText(r.text, anchorX + padX, baseline, view.textPaint);
                view.textPaint.setFakeBoldText(false);
            } else {
                drawInlineCodeLine(canvas, r.text, anchorX + padX, baseline,
                        view.theme.textColor, view.theme.func);
            }
        }
        // Séparateur sous la bande signature.
        if (sigBgBottom >= 0) {
            view.caretPaint.setStyle(Paint.Style.STROKE);
            view.caretPaint.setStrokeWidth(1f);
            view.caretPaint.setColor(view.theme.glassBorder);
            canvas.drawLine(anchorX, sigBgBottom, anchorX + popupW, sigBgBottom,
                    view.caretPaint);
        }
        // Scrollbar si le corps déborde.
        if (contentH > popupH) {
            float sbX = anchorX + popupW - 3 * density;
            float sbH = popupH * popupH / contentH;
            float sbY = anchorY + (popupH - sbH) * (scrollY / maxScroll);
            view.selPaint.setColor(view.theme.gutterBorder);
            canvas.drawRect(sbX, sbY, sbX + 2 * density, sbY + sbH, view.selPaint);
        }
        canvas.restore();
        // Réinitialiser le paint
        view.textPaint.setTextSize(view.metrics.getTextSize());
        view.textPaint.setTypeface(view.metrics.getTypeface());
    }

    /**
     * Dessine une ligne en alternant segments normaux et segments
     * {@code `inline code`} (backticks conservés au rendu, code en couleur
     * accent — la police est déjà monospace dans l'éditeur).
     */
    private void drawInlineCodeLine(Canvas canvas, String line, float x,
                                    float baseline, int normalColor, int codeColor) {
        if (line == null || line.isEmpty()) return;
        int start = 0;
        boolean inCode = false;
        float cx = x;
        while (start < line.length()) {
            int tick = line.indexOf('`', start);
            String seg = tick >= 0 ? line.substring(start, tick)
                    : line.substring(start);
            if (!seg.isEmpty()) {
                view.textPaint.setColor(inCode ? codeColor : normalColor);
                canvas.drawText(seg, cx, baseline, view.textPaint);
                cx += view.textPaint.measureText(seg);
            }
            if (tick < 0) break;
            // Rend le backtick en couleur code (il reste visible, délimiteur
            // de code).
            view.textPaint.setColor(codeColor);
            canvas.drawText("`", cx, baseline, view.textPaint);
            cx += view.textPaint.measureText("`");
            start = tick + 1;
            inCode = !inCode;
        }
    }

    /** Découpe {@code text} par retour à la ligne pour tenir dans {@code maxWidth} (px). */
    private static List<String> wrapText(String text, float maxWidth, Paint paint) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] parts = text.split("\n");
        for (String part : parts) {
            if (part.isEmpty()) {
                out.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < part.length(); i++) {
                line.append(part.charAt(i));
                if (paint.measureText(line.toString()) > maxWidth) {
                    // Revenir à la dernière espace.
                    int lastSpace = line.lastIndexOf(" ");
                    if (lastSpace > 0) {
                        out.add(line.substring(0, lastSpace));
                        line = new StringBuilder(line.substring(lastSpace + 1));
                    } else {
                        out.add(line.toString());
                        line = new StringBuilder();
                    }
                }
            }
            if (line.length() > 0) out.add(line.toString());
        }
        return out;
    }

    // ════════════════════════════════════════════════════════════════
    // Ampoule des code actions + popup
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine une ampoule 💡 dans la bande de plis pour chaque ligne
     * visible ayant des code actions. Taper sur l'ampoule ouvre le popup
     * d'actions.
     *
     * <p>L'ampoule est un vrai glyphe d'ampoule (cercle rempli + petit
     * rectangle de base, en ambre) centré dans la colonne de la bande de
     * plis — elle ne chevauche pas les numéros de ligne car cette bande est
     * une colonne dédiée.
     *
     * <p>L'ampoule n'est dessinée que sur les lignes portant un diagnostic
     * (Erreur/Avertissement) — elle est liée aux quick-fixes sur erreurs,
     * pas affichée sur chaque ligne.
     */
    void drawCodeActionsBulbs(Canvas canvas, int firstVisible, int lastVisible,
                               float lineHeight, float paddingTop) {
        if (view.codeActionsByLine.isEmpty()) return;
        float density = view.getResources().getDisplayMetrics().density;
        float bulbR = LIGHTBULB_RADIUS_DP * density;
        // Centre de la colonne de la bande de plis.
        float bulbX = view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth() * 0.5f;
        view.selPaint.setAntiAlias(true);
        // Construire l'ensemble des lignes portant un diagnostic (Erreur/Avertissement uniquement).
        java.util.Set<Integer> diagLines = new java.util.HashSet<>();
        for (DiagnosticShift.Diagnostic d : view.session.getDiagnostics()) {
            if (d.severity == 3 || d.severity == 2) { // erreur ou avertissement
                int ln = view.session.getDocument().lineForOffset(d.start);
                diagLines.add(ln);
            }
        }
        for (java.util.Map.Entry<Integer, List<EditorView.CodeAction>> e : view.codeActionsByLine.entrySet()) {
            int line = e.getKey();
            if (line < firstVisible || line > lastVisible) continue;
            // Ne montrer l'ampoule que sur les lignes portant un diagnostic.
            // Sans cette porte, un resolver qui renvoie des actions pour
            // chaque ligne (comme le resolver de démo) spammerait des
            // ampoules partout, noyant les chevrons de pli.
            if (!diagLines.contains(line)) continue;
            // Sauter l'ampoule des lignes cachées (repliées) + Y sensible aux plis.
            if (view.isLineFoldedCached(line)) continue;
            float cy = view.docLineToY(line) - view.vOffset + lineHeight * 0.5f; // sensible aux plis
            // Dessiner le glyphe d'ampoule : cercle rempli (l'ampoule) + petit
            // rectangle de base (le culot) en ambre.
            view.selPaint.setColor(android.graphics.Color.rgb(255, 193, 7)); // ambre 500
            canvas.drawCircle(bulbX, cy - bulbR * 0.2f, bulbR, view.selPaint);
            // Culot : petit rectangle arrondi sous l'ampoule.
            float socketW = bulbR * 0.8f;
            float socketH = bulbR * 0.5f;
            android.graphics.RectF socket = new android.graphics.RectF(
                bulbX - socketW * 0.5f, cy + bulbR * 0.6f,
                bulbX + socketW * 0.5f, cy + bulbR * 0.6f + socketH);
            view.selPaint.setColor(android.graphics.Color.rgb(120, 90, 0));
            canvas.drawRoundRect(socket, bulbR * 0.15f, bulbR * 0.15f, view.selPaint);
            // Anneau de halo discret autour de l'ampoule pour la faire
            // ressortir sur les thèmes sombres.
            view.selPaint.setStyle(Paint.Style.STROKE);
            view.selPaint.setStrokeWidth(1f);
            view.selPaint.setColor(android.graphics.Color.argb(60, 255, 193, 7));
            canvas.drawCircle(bulbX, cy - bulbR * 0.2f, bulbR + 1.5f * density, view.selPaint);
            view.selPaint.setStyle(Paint.Style.FILL);
        }
    }

    /**
     * Dessine le popup de code actions ancré à l'ampoule de la ligne
     * active. Chaque rangée est une action ; taper pour appliquer.
     */
    void drawCodeActionsPopup(Canvas canvas) {
        if (!view.codeActionsPopupVisible || view.codeActionsPopupLine < 0) return;
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(view.codeActionsPopupLine);
        if (actions == null || actions.isEmpty()) {
            view.codeActionsPopupVisible = false;
            return;
        }
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.CODE_ACTIONS_ROW_HEIGHT_DP * density;
        float width = view.CODE_ACTIONS_POPUP_WIDTH_DP * density;
        float radius = QUICK_DOC_RADIUS_DP * density;
        int rowsToShow = Math.min(view.CODE_ACTIONS_MAX_ROWS, actions.size());
        float popupH = rowH * rowsToShow;
        float lineHeight = view.metrics.getLineHeight();
        float paddingTop = view.metrics.getPadTop();
        float anchorX = view.metrics.getGutterWidth() + 4 * density;
        float anchorY = view.docLineToY(view.codeActionsPopupLine) - view.vOffset; // sensible aux plis
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, anchorY - popupH + lineHeight);
        }
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + popupH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
        float padX = 8 * density;
        for (int i = 0; i < rowsToShow; i++) {
            EditorView.CodeAction a = actions.get(i);
            float y = anchorY + i * rowH;
            if (i == view.codeActionsSelected) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            view.textPaint.setColor(view.theme.textColor);
            canvas.drawText(a.title, anchorX + padX, y + rowH * 0.7f, view.textPaint);
            // Badge de type à droite
            if (!a.kind.isEmpty()) {
                float titleW = view.textPaint.measureText(a.title);
                view.textPaint.setColor(view.theme.gutterText);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
                canvas.drawText(a.kind, anchorX + padX + titleW + 8 * density,
                    y + rowH * 0.7f, view.textPaint);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            }
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // Popup d'aller-au-symbole
    // ════════════════════════════════════════════════════════════════

    /** Dessine le popup d'aller-au-symbole : champ de filtre + liste scrollable. */
    void drawGoToSymbolPopup(Canvas canvas) {
        if (!view.goToSymbolVisible) return;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.GO_TO_SYMBOL_ROW_HEIGHT_DP * density;
        float width = view.GO_TO_SYMBOL_WIDTH_DP * density;
        float radius = view.GO_TO_SYMBOL_RADIUS_DP * density;
        float filterH = rowH;
        int rowsToShow = Math.min(view.GO_TO_SYMBOL_MAX_ROWS, view.goToSymbolFiltered.size());
        float popupH = filterH + rowH * rowsToShow;
        // Centré en haut.
        float anchorX = (view.getWidth() - width) * 0.5f;
        float anchorY = 8 * density;
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + popupH);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);
        // Champ de filtre (seul le texte est rendu — la saisie est gérée par l'hôte).
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
        view.textPaint.setColor(view.theme.gutterText);
        float padX = 8 * density;
        String filterLabel = "Filter: " + view.goToSymbolFilter;
        canvas.drawText(filterLabel, anchorX + padX, anchorY + filterH * 0.7f, view.textPaint);
        // Curseur en fin de filtre
        float caretX = anchorX + padX + view.textPaint.measureText(filterLabel);
        view.caretPaint.setColor(view.theme.caret);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1.5f);
        canvas.drawLine(caretX, anchorY + 4, caretX, anchorY + filterH - 4, view.caretPaint);
        // Séparateur
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawLine(anchorX, anchorY + filterH, anchorX + width, anchorY + filterH, view.caretPaint);
        // Rangées
        for (int i = 0; i < rowsToShow; i++) {
            int idx = view.goToSymbolScrollOffset + i;
            if (idx >= view.goToSymbolFiltered.size()) break;
            NavigationMenu.Symbol s = view.goToSymbolFiltered.get(idx);
            float y = anchorY + filterH + i * rowH;
            if (idx == view.goToSymbolSelected) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            view.textPaint.setColor(view.theme.textColor);
            canvas.drawText(s.name, anchorX + padX, y + rowH * 0.7f, view.textPaint);
            // Type + conteneur à droite
            String detail = s.kind + (s.container.isEmpty() ? "" : " — " + s.container);
            if (!detail.isEmpty()) {
                view.textPaint.setColor(view.theme.gutterText);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
                canvas.drawText(detail, anchorX + width - padX - view.textPaint.measureText(detail),
                    y + rowH * 0.7f, view.textPaint);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            }
        }
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    // ════════════════════════════════════════════════════════════════
    // Popup de complétion
    // ════════════════════════════════════════════════════════════════

    /**
     * Dessine le popup de complétion en overlay au-dessus du contenu de
     * l'éditeur. Ancré au début du token, sous la ligne du curseur, clampé
     * au viewport.
     *
     * <p>★ Badge de type par suggestion : un carré arrondi teinté précède
     * chaque label — « K » violet pour un mot-clé, « C » doré pour une
     * classe, « I » cyan pour une interface, « E » orange pour un enum,
     * « M » pour une méthode, « F » bleu pour un champ, « v » pour une
     * variable, « p » gris pour un package, « @ » pour une annotation…
     * Les caractères du label qui matchent le préfixe tapé sont mis en
     * valeur (accent + gras), le detail (signature/package) passe aligné
     * à droite.</p>
     */
    void drawCompletionPopup(Canvas canvas) {
        if (!view.completionVisible || view.completionItems.isEmpty()) return;
        float[] anchor = view.completionPopupAnchor();
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = view.COMPLETION_ROW_HEIGHT_DP * density;
        float width = view.COMPLETION_WIDTH_DP * density;
        float radius = COMPLETION_BORDER_RADIUS_DP * density;
        // L'ancre est la SOURCE UNIQUE — le nombre de rangées dérive de sa
        // hauteur (elle réduit les rangées quand le viewport est petit,
        // cf. completionRowsVisible).
        int rowsToShow = Math.max(1, Math.round(anchor[3] / rowH));
        float popupH = rowH * rowsToShow;
        float anchorX = anchor[0];
        float anchorY = anchor[1];

        // Fond + bordure — coins TOP-LEFT/TOP-RIGHT arrondis uniquement
        // (le haut du popup « s'attache » à la ligne du curseur, motif
        // bottom-docked de la diagnostic sheet inversé).
        RectF rect = new RectF(anchorX, anchorY, anchorX + width, anchorY + popupH);
        float[] radii = completionCornerRadii;
        radii[0] = radius; radii[1] = radius;   // haut-gauche
        radii[2] = radius; radii[3] = radius;   // haut-droite
        radii[4] = 0f; radii[5] = 0f;           // bas-droite (droit)
        radii[6] = 0f; radii[7] = 0f;           // bas-gauche (droit)
        Path bg = scratchPath;
        bg.reset();
        bg.addRoundRect(rect, radii, Path.Direction.CW);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawPath(bg, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawPath(bg, view.caretPaint);

        // Rangées
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        float padX = 8 * density;
        // Clip au path arrondi — la sélection de rangée et le
        // label ne débordent plus sur les coins droits.
        canvas.save();
        canvas.clipPath(bg);
        // ★ Thème sombre ? (pour assombrir la palette badge sur glass clair)
        boolean darkTheme = (view.theme.editorBg & 0xFFFFFF) < 0x808080;
        for (int i = 0; i < rowsToShow; i++) {
            int idx = view.completionScrollOffset + i;
            if (idx >= view.completionItems.size()) break;
            float y = anchorY + i * rowH;
            // Surligner la rangée sélectionnée
            if (idx == view.completionSelected) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            jo.codeeditor.completion.CompletionSession.Item item = view.completionItems.get(idx);
            drawCompletionRow(canvas, item, anchorX, y, width, rowH, padX, density, darkTheme);
        }
        // Réinitialiser la taille du paint de texte
        view.textPaint.setTextSize(view.metrics.getTextSize());
        // Scrollbar s'il y a plus d'éléments que de visibles
        if (view.completionItems.size() > rowsToShow) {
            float sbX = anchorX + width - 3 * density;
            float sbH = popupH * rowsToShow / (float) view.completionItems.size();
            float sbY = anchorY + (popupH - sbH) * (view.completionScrollOffset / (float)(view.completionItems.size() - rowsToShow));
            view.selPaint.setColor(view.theme.gutterBorder);
            canvas.drawRect(sbX, sbY, sbX + 2 * density, sbY + sbH, view.selPaint);
        }
        canvas.restore();
    }

    /**
     * ★ Une ligne du popup : badge de type (carré arrondi teinté +
     * glyphe) puis label (caractères matchés du préfixe en accent/gras) et
     * detail aligné à droite (signature, package). Le label est clippé à
     * la zone restante pour ne jamais recouvrir le detail.
     */
    private void drawCompletionRow(Canvas canvas,
                                   jo.codeeditor.completion.CompletionSession.Item item,
                                   float anchorX, float y, float width, float rowH,
                                   float padX, float density, boolean darkTheme) {
        String label = item.label;

        // ── Badge de type ──
        jo.codeeditor.completion.CompletionKindBadge.Meta badge =
                jo.codeeditor.completion.CompletionKindBadge.meta(
                        item.kind, item.kindTag, item.icon, darkTheme,
                        view.theme.func);
        float badgeSize = rowH - 10 * density;   // rangée 28 dp → badge 18 dp
        float badgeLeft = anchorX + padX;
        float badgeTop = y + (rowH - badgeSize) * 0.5f;
        RectF badgeRect = new RectF(badgeLeft, badgeTop, badgeLeft + badgeSize,
                badgeTop + badgeSize);

        // Fond : teinte du kind à 20 % d'alpha.
        view.bgPaint.setColor((badge.color & 0x00FFFFFF)
                | (0x33 << 24)); // alpha 51 ≈ 0.20
        float badgeRadius = 4 * density;
        canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, view.bgPaint);

        // Glyphe centré (police code, semi-gras — fake bold sur Canvas).
        view.textPaint.setTextSize(badgeSize
                * jo.codeeditor.completion.CompletionKindBadge.glyphSizeFactor(badge.glyph));
        view.textPaint.setColor(badge.color);
        view.textPaint.setFakeBoldText(true);
        Paint.FontMetrics bfm = view.textPaint.getFontMetrics();
        float glyphBaseY = badgeTop + badgeSize * 0.5f
                - (bfm.ascent + bfm.descent) * 0.5f;
        float glyphW = view.textPaint.measureText(badge.glyph);
        canvas.drawText(badge.glyph, badgeLeft + (badgeSize - glyphW) * 0.5f,
                glyphBaseY, view.textPaint);
        view.textPaint.setFakeBoldText(false);
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);

        // ── Detail aligné à droite (mesuré AVANT la zone label) ──
        String detail = (item.detail != null) ? item.detail : "";
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
        float detailW = detail.isEmpty() ? 0f : view.textPaint.measureText(detail);
        float detailRight = anchorX + width - padX;
        float detailLeft = detail.isEmpty() ? detailRight : detailRight - detailW;

        // ── Label : caractères matchés au préfixe en accent + gras ──
        float textX = badgeLeft + badgeSize + 6 * density;
        float labelMaxRight = detail.isEmpty()
                ? (anchorX + width - padX)
                : (detailLeft - 8 * density);
        if (labelMaxRight <= textX) {
            // Aucune place pour le label (popup trop étroit) — badge seul.
            if (!detail.isEmpty()) {
                view.textPaint.setColor(view.theme.gutterText);
                canvas.drawText(detail, detailLeft, y + rowH * 0.7f, view.textPaint);
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            }
            return;
        }

        String prefix = view.completionPrefix != null ? view.completionPrefix : "";
        java.util.List<Integer> matched = (prefix.isEmpty())
                ? java.util.Collections.emptyList()
                : jo.codeeditor.completion.CompletionSession.matchPositions(label, prefix);

        canvas.save();
        canvas.clipRect(textX, y, labelMaxRight, y + rowH);
        if (matched.isEmpty()) {
            view.textPaint.setColor(view.theme.keyword);
            canvas.drawText(label, textX, y + rowH * 0.7f, view.textPaint);
        } else {
            // Runs contigus : matché (accent + gras) / non-matché (normal).
            float x = textX;
            int n = label.length();
            int k = 0;
            while (k < n) {
                boolean isMatch = matched.contains(k);
                int runEnd = k;
                while (runEnd + 1 < n
                        && matched.contains(runEnd + 1) == isMatch) {
                    runEnd++;
                }
                String seg = label.substring(k, runEnd + 1);
                view.textPaint.setColor(isMatch ? view.theme.func : view.theme.keyword);
                view.textPaint.setFakeBoldText(isMatch);
                canvas.drawText(seg, x, y + rowH * 0.7f, view.textPaint);
                view.textPaint.setFakeBoldText(false);
                x += view.textPaint.measureText(seg);
                k = runEnd + 1;
            }
        }
        canvas.restore();

        // ── Detail (couleur atténuée, à droite) ──
        if (!detail.isEmpty()) {
            view.textPaint.setColor(view.theme.gutterText);
            canvas.drawText(detail, detailLeft, y + rowH * 0.7f, view.textPaint);
        }
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
    }
}
