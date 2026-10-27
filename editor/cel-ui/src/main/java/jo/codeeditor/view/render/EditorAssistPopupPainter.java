package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.state.CompletionState;

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
    // P-05 (audit 3.69.0) : RectF de travail réutilisé — chaque popup,
    // culot et badge allouait un RectF par draw. Usage mono-thread (onDraw,
    // thread UI) ; chaque site fait set → draws immédiats séquentiels.
    private final RectF scratchRect = new RectF();

    /** Rayons par coin du popup de complétion (réutilisé chaque frame). */
    private final float[] completionCornerRadii = new float[8];

    // Rayon de bordure du popup de complétion (les constantes hauteur de
    // ligne / largeur / nombre max de lignes restent sur EditorView car le
    // code tactile / de hit-test en a aussi besoin).
    // 12 dp — coins du HAUT seuls, plus visibles.
    static final float COMPLETION_BORDER_RADIUS_DP = 12f;
    // Popup d'aide de signature
    static final float SIGNATURE_HELP_ROW_HEIGHT_DP = 22f;
    // 4.8.0 : largeur ADAPTATIVE — le contenu est mesuré, la largeur
    // finale est bornée [min, max] (et par l'écran). Auparavant fixe à
    // 320 dp : les libellés courts étiraient une carte vide, les
    // longs étaient coupés NET sans ellipse (le paramètre actif
    // pouvait sortir de l'écran).
    static final float SIGNATURE_HELP_MIN_WIDTH_DP = 200f;
    static final float SIGNATURE_HELP_MAX_WIDTH_DP = 320f;
    // 12 dp + bande de documentation sous la liste.
    static final float SIGNATURE_HELP_RADIUS_DP = 12f;
    static final int SIGNATURE_HELP_MAX_ROWS = 10;
    /** Lignes max de la bande doc (paramètre actif + javadoc). */
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

    // ── Layout du popup d'aide de signature (4.8.0) ────────────────
    // Calculé UNE fois par frame par computeSignatureLayout() et
    // partagé entre le dessin (drawSignatureHelpPopup) et le hit-test
    // tactile du badge de surcharges (signatureHelpBadgeMetrics) —
    // mêmes bornes, mêmes mesures, aucune divergence possible.
    // Mono-thread (onDraw / tap sur le fil UI).
    /** Lignes wrappées de la bande doc (paramètre actif puis javadoc). */
    private final List<String> sigDocLines = new ArrayList<>();
    /** Libellé du paramètre actif (tête de la bande doc, en accent). */
    private String sigParamDocLabel = "";

    /** Indices du tableau de layout retourné par computeSignatureLayout(). */
    private static final int SIG_ANCHOR_X = 0, SIG_ANCHOR_Y = 1, SIG_WIDTH = 2,
            SIG_POPUP_H = 3, SIG_ROW_H = 4, SIG_ROWS = 5, SIG_ACTIVE = 6,
            SIG_B_LEFT = 7, SIG_B_TOP = 8, SIG_B_RIGHT = 9, SIG_B_BOTTOM = 10,
            SIG_HAS_BADGE = 11;

    /**
     * Calcule le layout complet du popup d'aide de signature : largeur
     * ADAPTATIVE (contenu mesuré, borné [min, max] et par l'écran),
     * lignes de la bande doc (documentation du paramètre ACTIF en tête,
     * puis javadoc de la signature), ancre au-dessus de la ligne du
     * caret (bascule en dessous, clampée à la vue), et rectangle du
     * badge de surcharges.
     *
     * @return le tableau de layout (indices SIG_*), ou null (popup
     *         absent). Remplit sigDocLines/sigParamDocLabel.
     */
    private float[] computeSignatureLayout() {
        if (!view.signatureHelpVisible || view.signatureHelpData == null
                || view.signatureHelpData.signatures == null
                || view.signatureHelpData.signatures.isEmpty()
                || view.session == null) {
            return null;
        }
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = SIGNATURE_HELP_ROW_HEIGHT_DP * density;
        int rowsToShow = Math.min(SIGNATURE_HELP_MAX_ROWS,
                view.signatureHelpData.signatures.size());

        // ── Index actif : l'override clavier/tactile du contrôleur
        // d'abord, sinon le choix du serveur.
        int activeIdx = view.getEffectiveActiveSignature();
        if (activeIdx < 0) {
            activeIdx = Math.max(0, Math.min(view.signatureHelpData.activeSignature,
                    view.signatureHelpData.signatures.size() - 1));
        }
        jo.codeeditor.completion.SignatureHelpController.Signature activeSig =
                view.signatureHelpData.signatures.get(activeIdx);

        // ── Mesure des rangées : largeur naturelle du contenu.
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        float padX = 8 * density;
        float maxRowW = 0;
        for (int i = 0; i < rowsToShow; i++) {
            String label = view.signatureHelpData.signatures.get(i).label;
            int[] range = findParameterRangeInLabel(label,
                    view.signatureHelpData.signatures.get(i).activeParameter);
            float w;
            if (range == null) {
                w = view.textPaint.measureText(label);
            } else {
                w = view.textPaint.measureText(label.substring(0, range[0]))
                        + view.textPaint.measureText(label.substring(range[0], range[1]))
                        + view.textPaint.measureText(label.substring(range[1]));
            }
            if (w > maxRowW) maxRowW = w;
        }

        // ── Badge de surcharges « n/m » : présent seulement s'il y a
        // un choix à faire. Coin haut droit — la rangée 0 lui cède sa
        // droite, les autres rangées gardent toute la largeur.
        boolean hasBadge = view.signatureHelpData.signatures.size() > 1;
        float badgeTextSize = view.metrics.getTextSize() * 0.7f;
        view.textPaint.setTextSize(badgeTextSize);
        float badgeW = hasBadge
                ? view.textPaint.measureText((activeIdx + 1) + "/"
                        + view.signatureHelpData.signatures.size()) + 10 * density
                : 0;
        float badgeH = hasBadge ? badgeTextSize * 1.6f : 0;

        // ── Largeur finale : contenu borné [min, max] et par l'écran.
        float viewW = view.getWidth();
        float contentW = maxRowW + 2 * padX + (hasBadge ? badgeW + 4 * density : 0);
        float maxW = Math.min(SIGNATURE_HELP_MAX_WIDTH_DP * density, viewW - 8 * density);
        float width = Math.max(Math.min(contentW, maxW),
                Math.min(SIGNATURE_HELP_MIN_WIDTH_DP * density, maxW));

        // ── Bande doc : documentation du paramètre ACTIF en tête
        // (label en accent), puis la javadoc de la signature (QuickDoc,
        // sections @ en couleur de mot-clé). Plafond commun.
        sigDocLines.clear();
        sigParamDocLabel = "";
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
        float docWidth = width - 2 * padX;
        if (activeSig != null) {
            if (!activeSig.parameters.isEmpty()) {
                int activeParam = Math.max(0, Math.min(activeSig.activeParameter,
                        activeSig.parameters.size() - 1));
                jo.codeeditor.completion.SignatureHelpController.Parameter param =
                        activeSig.parameters.get(activeParam);
                if (param != null && !param.documentation.isEmpty()) {
                    sigParamDocLabel = param.label == null ? "" : param.label;
                    String head = sigParamDocLabel.isEmpty()
                            ? param.documentation
                            : sigParamDocLabel + " — " + param.documentation;
                    for (String w : wrapText(head, docWidth, view.textPaint)) {
                        if (sigDocLines.size() >= SIGNATURE_DOC_MAX_LINES) break;
                        sigDocLines.add(w);
                    }
                }
            }
            if (activeSig.documentation != null && !activeSig.documentation.isEmpty()) {
                jo.codeeditor.doc.QuickDoc.QuickDocContent parsed =
                        jo.codeeditor.doc.QuickDoc.parseQuickDoc(
                                activeSig.documentation, "java");
                if (!parsed.description.isEmpty()) {
                    for (String w : wrapText(parsed.description, docWidth, view.textPaint)) {
                        if (sigDocLines.size() >= SIGNATURE_DOC_MAX_LINES) break;
                        sigDocLines.add(w);
                    }
                }
                for (jo.codeeditor.doc.QuickDoc.DocSection s : parsed.sections) {
                    if (sigDocLines.size() >= SIGNATURE_DOC_MAX_LINES) break;
                    for (String item : s.items) {
                        if (sigDocLines.size() >= SIGNATURE_DOC_MAX_LINES) break;
                        String line = s.title + " " + item;
                        for (String w : wrapText(line, docWidth, view.textPaint)) {
                            if (sigDocLines.size() >= SIGNATURE_DOC_MAX_LINES) break;
                            sigDocLines.add(w);
                        }
                    }
                }
            }
        }
        float docRowH = view.metrics.getTextSize() * 0.75f * 1.3f;
        float docH = sigDocLines.isEmpty() ? 0
                : sigDocLines.size() * docRowH + 8 * density;
        float popupH = rowH * rowsToShow + docH;

        // ── Ancrage X = colonne du curseur, Y = au-dessus de la ligne
        // du curseur (bascule en dessous sinon, clampés à la vue).
        EditorDocument doc = view.session.getDocument();
        Selection sel = view.clampSelection(view.session.getSelection(), doc);
        int line = EditorView.clamp(doc.lineForOffset(sel.start), 0, doc.lineCount() - 1);
        int col = sel.start - doc.lineStart(line);
        float anchorX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
            + view.visualColFor(line, col) * view.metrics.getCharWidth() - view.scroll.hOffset;
        float caretY = view.docLineToY(line) - view.scroll.vOffset;
        float anchorY = caretY - 4 - popupH;
        if (anchorY < 0) {
            anchorY = caretY + view.metrics.getLineHeight() + 4;
        }
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, view.getHeight() - popupH - 4);
        }
        if (anchorX + width > viewW) {
            anchorX = Math.max(view.metrics.getGutterWidth(), viewW - width - 4);
        }

        float[] out = new float[12];
        out[SIG_ANCHOR_X] = anchorX;
        out[SIG_ANCHOR_Y] = anchorY;
        out[SIG_WIDTH] = width;
        out[SIG_POPUP_H] = popupH;
        out[SIG_ROW_H] = rowH;
        out[SIG_ROWS] = rowsToShow;
        out[SIG_ACTIVE] = activeIdx;
        out[SIG_B_LEFT] = anchorX + width - 6 * density - badgeW;
        out[SIG_B_TOP] = anchorY + (rowH - badgeH) / 2f;
        out[SIG_B_RIGHT] = anchorX + width - 6 * density;
        out[SIG_B_BOTTOM] = out[SIG_B_TOP] + badgeH;
        out[SIG_HAS_BADGE] = hasBadge ? 1 : 0;
        return out;
    }

    /**
     * Géométrie du badge de surcharges du popup d'aide de signature —
     * partagée avec le hit-test tactile (le tap sur le badge fait
     * défiler les surcharges, l'équivalent tactile des flèches
     * Haut/Bas du clavier).
     *
     * @return {left, top, right, bottom} en px écran, ou null (popup
     *         absent ou surcharge unique — pas de badge).
     */
    float[] signatureHelpBadgeMetrics() {
        float[] layout = computeSignatureLayout();
        if (layout == null || layout[SIG_HAS_BADGE] < 1) return null;
        return new float[]{layout[SIG_B_LEFT], layout[SIG_B_TOP],
                layout[SIG_B_RIGHT], layout[SIG_B_BOTTOM]};
    }

    /**
     * Dessine le popup d'aide de signature. Ancré AU-DESSUS de la
     * ligne du curseur (contrairement au popup de complétion, qui est
     * EN DESSOUS) ; s'il n'y a pas de place au-dessus, bascule en
     * dessous. Chaque signature tient sur une rangée ; le paramètre
     * actif est surligné en gras + couleur d'accent et garanti
     * VISIBLE — les débords sont ellipsés AUTOUR de lui (4.8.0 :
     * auparavant coupés net, l'actif pouvait sortir de l'écran). Un
     * badge « n/m » (coin haut droit) compte les surcharges et se
     * tâte pour les faire défiler ; la bande de documentation ouvre
     * sur le paramètre ACTIF (label en accent) puis la javadoc.
     */
    void drawSignatureHelpPopup(Canvas canvas) {
        float[] l = computeSignatureLayout();
        if (l == null) return;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = l[SIG_ROW_H];
        float width = l[SIG_WIDTH];
        float radius = SIGNATURE_HELP_RADIUS_DP * density;
        int rowsToShow = (int) l[SIG_ROWS];
        int activeIdx = (int) l[SIG_ACTIVE];
        float anchorX = l[SIG_ANCHOR_X];
        float anchorY = l[SIG_ANCHOR_Y];
        boolean hasBadge = l[SIG_HAS_BADGE] >= 1;
        float padX = 8 * density;

        // Fond + bordure
        RectF rect = scratchRect;
        rect.set(anchorX, anchorY, anchorX + width, anchorY + l[SIG_POPUP_H]);
        view.bgPaint.setColor(view.theme.glassBg);
        canvas.drawRoundRect(rect, radius, radius, view.bgPaint);
        view.caretPaint.setStyle(Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1f);
        view.caretPaint.setColor(view.theme.glassBorder);
        canvas.drawRoundRect(rect, radius, radius, view.caretPaint);

        // Rangées — clippées au rect ; le paramètre actif garanti
        // visible par ellipsizeAroundActive.
        canvas.save();
        canvas.clipRect(rect);
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        for (int i = 0; i < rowsToShow; i++) {
            jo.codeeditor.completion.SignatureHelpController.Signature sig =
                view.signatureHelpData.signatures.get(i);
            float y = anchorY + i * rowH;
            // Surligner la rangée de la signature active (sensible à
            // l'override clavier/tactile du contrôleur).
            if (i == activeIdx) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            // La rangée 0 cède sa droite au badge (coin haut droit).
            float availW = width - 2 * padX
                    - (hasBadge && i == 0
                        ? (l[SIG_B_RIGHT] - l[SIG_B_LEFT]) + 4 * density : 0);
            String label = sig.label;
            int[] paramRange = findParameterRangeInLabel(label, sig.activeParameter);
            if (paramRange == null) {
                String[] parts = ellipsizeAroundActive("", "", label, availW, view.textPaint);
                drawSignatureRowText(canvas, anchorX + padX, y + rowH * 0.7f,
                        parts[0], parts[1], parts[2]);
            } else {
                String pre = label.substring(0, paramRange[0]);
                String param = label.substring(paramRange[0], paramRange[1]);
                String post = label.substring(paramRange[1]);
                String[] parts = ellipsizeAroundActive(pre, param, post, availW, view.textPaint);
                drawSignatureRowText(canvas, anchorX + padX, y + rowH * 0.7f,
                        parts[0], parts[1], parts[2]);
            }
        }

        // ── Badge de surcharges (coin haut droit) : pill teintée,
        // texte « n/m » — la SEULE surface tactile du popup.
        if (hasBadge) {
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
            String badgeText = (activeIdx + 1) + "/"
                    + view.signatureHelpData.signatures.size();
            RectF badge = scratchRect;
            badge.set(l[SIG_B_LEFT], l[SIG_B_TOP], l[SIG_B_RIGHT], l[SIG_B_BOTTOM]);
            view.selPaint.setColor(view.theme.selection);
            canvas.drawRoundRect(badge, badge.height() / 2f, badge.height() / 2f, view.selPaint);
            view.textPaint.setColor(view.theme.keyword);
            canvas.drawText(badgeText,
                    badge.left + 5 * density,
                    badge.centerY() - (view.textPaint.ascent() + view.textPaint.descent()) / 2f,
                    view.textPaint);
        }

        // ── Bande documentation : paramètre ACTIF en tête (label en
        // accent sur la première ligne si le wrap ne l'a pas coupé),
        // puis la javadoc (sections @ en couleur de mot-clé).
        if (!sigDocLines.isEmpty()) {
            float docTop = anchorY + rowsToShow * rowH;
            view.caretPaint.setStyle(Paint.Style.STROKE);
            view.caretPaint.setStrokeWidth(1f);
            view.caretPaint.setColor(view.theme.glassBorder);
            canvas.drawLine(anchorX, docTop, anchorX + width, docTop,
                    view.caretPaint);
            view.selPaint.setColor(view.theme.selection);
            canvas.drawRect(anchorX, docTop, anchorX + width,
                    anchorY + l[SIG_POPUP_H], view.selPaint);
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            float docRowH = view.metrics.getTextSize() * 0.75f * 1.3f;
            float labelW = sigParamDocLabel.isEmpty()
                    ? 0 : view.textPaint.measureText(sigParamDocLabel);
            for (int i = 0; i < sigDocLines.size(); i++) {
                String line = sigDocLines.get(i);
                float baseline = docTop + 4 * density + i * docRowH
                        + docRowH * 0.75f;
                boolean accentHead = i == 0 && !sigParamDocLabel.isEmpty()
                        && line.startsWith(sigParamDocLabel)
                        && labelW < view.textPaint.measureText(line);
                if (accentHead) {
                    view.textPaint.setColor(view.theme.func);
                    canvas.drawText(sigParamDocLabel, anchorX + padX, baseline, view.textPaint);
                    view.textPaint.setColor(view.theme.textColor);
                    canvas.drawText(line.substring(sigParamDocLabel.length()),
                            anchorX + padX + labelW, baseline, view.textPaint);
                } else {
                    boolean isTag = !line.isEmpty() && line.charAt(0) == '@';
                    view.textPaint.setColor(isTag
                            ? view.theme.keyword : view.theme.textColor);
                    canvas.drawText(line, anchorX + padX, baseline, view.textPaint);
                }
            }
        }
        canvas.restore();
        // Réinitialiser l'état du paint.
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
    }

    /**
     * Dessine une rangée de signature en trois segments : pré et post
     * en couleur de texte, paramètre actif en gras + couleur de
     * fonction.
     */
    private void drawSignatureRowText(Canvas canvas, float x, float baseline,
            String pre, String param, String post) {
        if (!pre.isEmpty()) {
            view.textPaint.setColor(view.theme.textColor);
            view.textPaint.setTypeface(view.metrics.getTypeface());
            canvas.drawText(pre, x, baseline, view.textPaint);
            x += view.textPaint.measureText(pre);
        }
        if (!param.isEmpty()) {
            view.textPaint.setColor(view.theme.func);
            android.graphics.Typeface bold = android.graphics.Typeface.create(
                view.metrics.getTypeface(), android.graphics.Typeface.BOLD);
            view.textPaint.setTypeface(bold);
            canvas.drawText(param, x, baseline, view.textPaint);
            x += view.textPaint.measureText(param);
        }
        if (!post.isEmpty()) {
            view.textPaint.setColor(view.theme.textColor);
            view.textPaint.setTypeface(view.metrics.getTypeface());
            canvas.drawText(post, x, baseline, view.textPaint);
        }
        view.textPaint.setTypeface(view.metrics.getTypeface());
    }

    /**
     * Ellipse une rangée de signature AUTOUR du paramètre actif : le
     * paramètre est garanti visible, le PRÉFIXE est sacrifié en
     * premier (ellipse de tête — la fin du préfixe, immédiatement
     * avant le paramètre, est la partie la moins informative une fois
     * coupée), puis le suffixe (ellipse de queue). Ultime recours : le
     * paramètre lui-même est rogné à droite.
     */
    static String[] ellipsizeAroundActive(String pre, String param, String post,
            float availW, Paint paint) {
        final String ell = "…";
        float ellW = paint.measureText(ell);
        if (paint.measureText(pre) + paint.measureText(param)
                + paint.measureText(post) <= availW) {
            return new String[]{pre, param, post};
        }
        // Le paramètre SEUL ne tient pas : rogné à droite, pré/post vides.
        if (paint.measureText(param) + ellW > availW) {
            return new String[]{"", truncateTail(param,
                    Math.max(0, availW - ellW), paint), ""};
        }
        // Paramètre garanti entier : le suffixe cède d'abord, puis le
        // préfixe — chacun avec son ellipse si l'autre côté survit.
        float rest = availW - paint.measureText(param);
        String post2 = truncateTail(post, rest - (pre.isEmpty() ? 0 : ellW), paint);
        float post2W = post2.isEmpty() ? 0 : paint.measureText(post2);
        String pre2 = truncateHead(pre, rest - post2W - (post2.isEmpty() ? 0 : ellW), paint);
        return new String[]{pre2, param, post2};
    }

    /** Coupe {@code s} par la DROITE avec ellipse pour tenir dans maxW. */
    private static String truncateTail(String s, float maxW, Paint paint) {
        if (s == null) return "";
        if (s.isEmpty() || paint.measureText(s) <= maxW) return s;
        final String ell = "…";
        float ellW = paint.measureText(ell);
        if (maxW <= ellW) return "";
        int end = s.length();
        while (end > 0 && paint.measureText(s.substring(0, end)) + ellW > maxW) {
            end--;
        }
        return end <= 0 ? "" : s.substring(0, end) + ell;
    }

    /** Coupe {@code s} par la GAUCHE avec ellipse pour tenir dans maxW. */
    private static String truncateHead(String s, float maxW, Paint paint) {
        if (s == null) return "";
        if (s.isEmpty() || paint.measureText(s) <= maxW) return s;
        final String ell = "…";
        float ellW = paint.measureText(ell);
        if (maxW <= ellW) return "";
        int start = 0;
        while (start < s.length()
                && paint.measureText(ell + s.substring(start)) > maxW) {
            start++;
        }
        return start >= s.length() ? "" : ell + s.substring(start);
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
                - view.scroll.hOffset;
        float lineTopY = view.docLineToY(line) - view.scroll.vOffset;
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
        RectF rect = scratchRect;
        rect.set(anchorX, anchorY, anchorX + popupW, anchorY + popupH);
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

    /**
     * Découpe {@code text} par retour à la ligne pour tenir dans
     * {@code maxWidth} (px). Visible du groupe : l'ancrage du popup de
     * complétion (contrôleur) mesure les lignes du panneau de
     * documentation avec le MÊME moteur que la bande doc de l'aide de
     * signature (4.4.0).
     */
    public static List<String> wrapText(String text, float maxWidth, Paint paint) {
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
     * visible portant un diagnostic Erreur/Avertissement.
     *
     * <p>L'ampoule est un vrai glyphe d'ampoule (cercle rempli + petit
     * rectangle de base, en ambre) centré dans la colonne de la bande de
     * plis — elle ne chevauche pas les numéros de ligne car cette bande est
     * une colonne dédiée.
     *
     * <p>Pilotée par les DIAGNOSTICS (leur publication la déclenche —
     * correcte après défilement, immédiate, sans fenêtre de debounce) :
     * le CONTENU du popup, lui, est résolu à la demande au tap (une
     * requête par invocation). Sans résolveur d'actions branché, pas
     * d'ampoule (aucune action possible).
     */
    void drawCodeActionsBulbs(Canvas canvas, int firstVisible, int lastVisible,
                               float lineHeight, float paddingTop) {
        if (view.codeActionsResolver == null || view.session == null) return;
        float density = view.getResources().getDisplayMetrics().density;
        float bulbR = LIGHTBULB_RADIUS_DP * density;
        // Centre de la colonne de la bande de plis.
        float bulbX = view.metrics.getGutterWidth() - view.metrics.getFoldStripWidth() * 0.5f;
        view.selPaint.setAntiAlias(true);
        // Lignes portant un diagnostic (Erreur/Avertissement uniquement).
        java.util.Set<Integer> diagLines = new java.util.HashSet<>();
        for (DiagnosticShift.Diagnostic d : view.session.getDiagnostics()) {
            if (d.severity == 3 || d.severity == 2) { // erreur ou avertissement
                int ln = view.session.getDocument().lineForOffset(d.start);
                diagLines.add(ln);
            }
        }
        for (Integer line : diagLines) {
            if (line < firstVisible || line > lastVisible) continue;
            // Sauter l'ampoule des lignes cachées (repliées) + Y sensible aux plis.
            if (view.isLineFoldedCached(line)) continue;
            float cy = view.docLineToY(line) - view.scroll.vOffset + lineHeight * 0.5f; // sensible aux plis
            // Dessiner le glyphe d'ampoule : cercle rempli (l'ampoule) + petit
            // rectangle de base (le culot) en ambre.
            view.selPaint.setColor(android.graphics.Color.rgb(255, 193, 7)); // ambre 500
            canvas.drawCircle(bulbX, cy - bulbR * 0.2f, bulbR, view.selPaint);
            // Culot : petit rectangle arrondi sous l'ampoule.
            float socketW = bulbR * 0.8f;
            float socketH = bulbR * 0.5f;
            android.graphics.RectF socket = scratchRect;
            socket.set(bulbX - socketW * 0.5f, cy + bulbR * 0.6f,
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
        float anchorY = view.docLineToY(view.codeActionsPopupLine) - view.scroll.vOffset; // sensible aux plis
        if (anchorY + popupH > view.getHeight()) {
            anchorY = Math.max(0, anchorY - popupH + lineHeight);
        }
        RectF rect = scratchRect;
        rect.set(anchorX, anchorY, anchorX + width, anchorY + popupH);
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
        RectF rect = scratchRect;
        rect.set(anchorX, anchorY, anchorX + width, anchorY + popupH);
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
        if (!view.completion.visible || view.completion.items.isEmpty()) return;
        float[] anchor = view.completionPopupAnchor();
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = CompletionState.ROW_HEIGHT_DP * density;
        float width = CompletionState.WIDTH_DP * density;
        float radius = COMPLETION_BORDER_RADIUS_DP * density;
        // ★ 4.4.0 : le panneau de documentation est inclus dans la
        // hauteur du popup — la zone de LISTE en est déduite (6ᵉ
        // élément de l'ancre, source unique).
        float panelH = anchor.length > 5 ? anchor[5] : 0f;
        boolean panelShown = panelH > 0f;
        // L'ancre est la SOURCE UNIQUE — le nombre de rangées dérive de sa
        // hauteur (elle réduit les rangées quand le viewport est petit,
        // cf. completionRowsVisible).
        int rowsToShow = Math.max(1, Math.round((anchor[3] - panelH) / rowH));
        float popupH = anchor[3];
        float anchorX = anchor[0];
        float anchorY = anchor[1];

        // Fond + bordure — coins TOP-LEFT/TOP-RIGHT arrondis uniquement
        // (le haut du popup « s'attache » à la ligne du curseur, motif
        // bottom-docked de la diagnostic sheet inversé).
        RectF rect = scratchRect;
        rect.set(anchorX, anchorY, anchorX + width, anchorY + popupH);
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
            int idx = view.completion.scrollOffset + i;
            if (idx >= view.completion.items.size()) break;
            float y = anchorY + i * rowH;
            // Surligner la rangée sélectionnée
            if (idx == view.completion.selected) {
                view.selPaint.setColor(view.theme.selection);
                canvas.drawRect(anchorX + 1, y, anchorX + width - 1, y + rowH, view.selPaint);
            }
            jo.codeeditor.completion.CompletionSession.Item item = view.completion.items.get(idx);
            drawCompletionRow(canvas, item, anchorX, y, width, rowH, padX, density, darkTheme,
                    panelShown);
        }
        // ★ 4.4.0 — Panneau de documentation sous la liste (miroir de la
        // bande doc de l'aide de signature) : séparateur + fond teinté +
        // lignes plafonnées calculées par l'ancrage.
        if (panelShown && !view.completion.docPanelLines.isEmpty()) {
            float docTop = anchorY + rowsToShow * rowH;
            view.caretPaint.setStyle(Paint.Style.STROKE);
            view.caretPaint.setStrokeWidth(1f);
            view.caretPaint.setColor(view.theme.glassBorder);
            canvas.drawLine(anchorX, docTop, anchorX + width, docTop, view.caretPaint);
            view.selPaint.setColor(view.theme.selection);
            canvas.drawRect(anchorX, docTop, anchorX + width, anchorY + popupH, view.selPaint);
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            float docRowH = view.metrics.getTextSize() * 0.75f * 1.3f;
            for (int i = 0; i < view.completion.docPanelLines.size(); i++) {
                String l = view.completion.docPanelLines.get(i);
                boolean isTag = !l.isEmpty() && l.charAt(0) == '@';
                view.textPaint.setColor(isTag ? view.theme.keyword : view.theme.textColor);
                float baseline = docTop + 4 * density + i * docRowH + docRowH * 0.75f;
                canvas.drawText(l, anchorX + padX, baseline, view.textPaint);
            }
        }
        // Réinitialiser la taille du paint de texte
        view.textPaint.setTextSize(view.metrics.getTextSize());
        // Scrollbar s'il y a plus d'éléments que de visibles — la voie
        // suit la zone de LISTE uniquement (4.4.0 : le panneau n'est pas
        // une rangée).
        if (view.completion.items.size() > rowsToShow) {
            float listH = popupH - panelH;
            float sbX = anchorX + width - 3 * density;
            float sbH = listH * rowsToShow / (float) view.completion.items.size();
            float sbY = anchorY + (listH - sbH) * (view.completion.scrollOffset / (float)(view.completion.items.size() - rowsToShow));
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
     *
     * <p>★ 4.4.0 : quand le panneau de documentation est masqué (vue
     * étroite) et que l'item PORTE une documentation, un petit glyphe
     * ⓘ occupe la place du container (la documentation se réduit à une
     * icône) — le container, quand il existe, garde sa place.</p>
     */
    private void drawCompletionRow(Canvas canvas,
                                   jo.codeeditor.completion.CompletionSession.Item item,
                                   float anchorX, float y, float width, float rowH,
                                   float padX, float density, boolean darkTheme,
                                   boolean panelShown) {
        String label = item.label;
        boolean docGlyph = !panelShown && item.documentation != null
                && !item.documentation.isEmpty();

        // ── Badge de type ──
        jo.codeeditor.completion.CompletionKindBadge.Meta badge =
                jo.codeeditor.completion.CompletionKindBadge.meta(
                        item.kind, item.kindTag, item.icon, darkTheme,
                        view.theme.func);
        float badgeSize = rowH - 10 * density;   // rangée 28 dp → badge 18 dp
        float badgeLeft = anchorX + padX;
        float badgeTop = y + (rowH - badgeSize) * 0.5f;
        RectF badgeRect = scratchRect;
        badgeRect.set(badgeLeft, badgeTop, badgeLeft + badgeSize,
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

        // ── Zone droite : container (origine du symbole, 4.3.0) puis
        // detail — mesurés AVANT la zone label ──
        // Le container vit à l'extrême droite, tronqué proprement (40 %
        // de la largeur du popup au plus) ; le detail garde sa place à
        // gauche — sans container, la disposition est exactement celle
        // d'avant. Toutes les mesures sont en dp converties via la
        // densité, jamais de largeur codée en pixels.
        String detail = (item.detail != null) ? item.detail : "";
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
        float detailW = detail.isEmpty() ? 0f : view.textPaint.measureText(detail);
        String container = (item.container != null) ? item.container : "";
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
        if (!container.isEmpty()) {
            container = ellipsizeTail(container, width * 0.4f);
        }
        float containerW = container.isEmpty() ? 0f : view.textPaint.measureText(container);
        float containerRight = anchorX + width - padX;
        float containerLeft = container.isEmpty() ? containerRight : containerRight - containerW;
        float detailRight = container.isEmpty() ? containerRight : containerLeft - 6 * density;
        float detailLeft = detail.isEmpty() ? detailRight : detailRight - detailW;
        // Taille historique du libellé (celle du detail — la mesure du
        // container l'avait abaissée).
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);

        // ── Label : caractères matchés au préfixe en accent + gras ──
        float textX = badgeLeft + badgeSize + 6 * density;
        float labelMaxRight = detail.isEmpty()
                ? (container.isEmpty()
                        ? (anchorX + width - padX)
                        : (containerLeft - 6 * density))
                : (detailLeft - 8 * density);
        if (labelMaxRight <= textX) {
            // Aucune place pour le label (popup trop étroit) — badge +
            // zone droite seuls.
            if (!detail.isEmpty()) {
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
                view.textPaint.setColor(view.theme.gutterText);
                canvas.drawText(detail, detailLeft, y + rowH * 0.7f, view.textPaint);
            }
            if (!container.isEmpty()) {
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
                view.textPaint.setColor(view.theme.gutterText);
                canvas.drawText(container, containerLeft, y + rowH * 0.7f, view.textPaint);
            } else if (docGlyph) {
                drawCompletionDocGlyph(canvas, anchorX + width - padX, y, rowH, density);
            }
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            return;
        }

        String prefix = view.completion.prefix != null ? view.completion.prefix : "";
        // La surbrillance s'appuie sur le MÊME texte de référence que
        // celui qui a fait matcher le candidat (identifiant de tête ou
        // filterText) — jamais une parenthèse de signature, jamais une
        // lettre prise au fil d'un parcours glouton détaché du filtre.
        java.util.List<Integer> matched = (prefix.isEmpty())
                ? java.util.Collections.emptyList()
                : jo.codeeditor.completion.CompletionMatcher.matchPositionsForItem(
                        label, item.filterText, prefix);

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

        // ── Zone droite (couleur atténuée) : detail puis container ──
        if (!detail.isEmpty()) {
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            view.textPaint.setColor(view.theme.gutterText);
            canvas.drawText(detail, detailLeft, y + rowH * 0.7f, view.textPaint);
        }
        if (!container.isEmpty()) {
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
            view.textPaint.setColor(view.theme.gutterText);
            canvas.drawText(container, containerLeft, y + rowH * 0.7f, view.textPaint);
        } else if (docGlyph) {
            // 4.4.0 : la documentation se réduit à une icône quand le
            // panneau ne tient pas (vue étroite) et que le container
            // laisse la place libre.
            drawCompletionDocGlyph(canvas, containerRight, y, rowH, density);
        }
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
    }

    /**
     * ★ 4.4.0 — Petit glyphe ⓘ de documentation de rangée : cercle
     * atténué + « i » italique, aligné à droite de la rangée (place du
     * container). Le paint partagé est restauré (style du caretPaint,
     * taille du textPaint).
     */
    private void drawCompletionDocGlyph(Canvas canvas, float rightX, float y,
                                        float rowH, float density) {
        float size = rowH - 14 * density;   // rangée 28 dp → glyphe 14 dp
        float cx = rightX - size * 0.5f;
        float cy = y + rowH * 0.5f;
        android.graphics.Paint.Style savedStyle = view.caretPaint.getStyle();
        float savedStroke = view.caretPaint.getStrokeWidth();
        view.caretPaint.setStyle(android.graphics.Paint.Style.STROKE);
        view.caretPaint.setStrokeWidth(1.2f);
        view.caretPaint.setColor(view.theme.comment);
        canvas.drawCircle(cx, cy, size * 0.5f, view.caretPaint);
        view.caretPaint.setStyle(savedStyle);
        view.caretPaint.setStrokeWidth(savedStroke);
        float savedSize = view.textPaint.getTextSize();
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.6f);
        view.textPaint.setColor(view.theme.comment);
        view.textPaint.setTextSkewX(-0.25f);
        Paint.FontMetrics fm = view.textPaint.getFontMetrics();
        float w = view.textPaint.measureText("i");
        canvas.drawText("i", cx - w * 0.5f,
                cy - (fm.ascent + fm.descent) * 0.5f, view.textPaint);
        view.textPaint.setTextSkewX(0f);
        view.textPaint.setTextSize(savedSize);
    }

    /**
     * Tronque la FIN du texte avec une ellipse pour qu'il tienne dans
     * {@code maxW} (mesuré avec le paint courant) — un container trop
     * long (paquet profond) ne repousse jamais le detail ni le label.
     */
    private String ellipsizeTail(String text, float maxW) {
        if (view.textPaint.measureText(text) <= maxW) return text;
        final String ellipsis = "…";
        float ellipsisW = view.textPaint.measureText(ellipsis);
        int end = text.length();
        while (end > 0
                && view.textPaint.measureText(text.substring(0, end)) + ellipsisW > maxW) {
            end--;
        }
        return end <= 0 ? ellipsis : text.substring(0, end) + ellipsis;
    }
}
