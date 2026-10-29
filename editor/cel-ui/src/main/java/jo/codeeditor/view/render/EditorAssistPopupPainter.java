package jo.codeeditor.view.render;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.popup.kit.PopupCard;
import jo.codeeditor.view.popup.kit.TextLayoutKit;
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
 * (liste + badges de type + runs de match, rangées deux lignes —
 * l'identité qualifiée sous le nom), popup d'aide de signature
 * (une surcharge à la fois, paramètres en TROIS PALIERS — déjà
 * frappés en accent, actif en gras + accent, à venir en atténué —
 * surcharges + documentation de la signature active), popup de
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

    /** Carte de popup partagée (fond verre + bordure, filets, gras
     * mémoïsé) — unifie les copies du motif dispersées dans ce painter
     * et porte le cache du Typeface gras. */
    private final PopupCard card;

    /** Rayons par coin du popup de complétion (réutilisé chaque frame). */
    private final float[] completionCornerRadii = new float[8];

    // Rayon de bordure du popup de complétion (les constantes hauteur de
    // ligne / largeur / nombre max de lignes restent sur EditorView car le
    // code tactile / de hit-test en a aussi besoin).
    // 4.10.0 : 14 dp — les QUATRE coins (le style à deux ailes
    // « coins hauts seuls » laissait la base carrée).
    static final float COMPLETION_BORDER_RADIUS_DP = 14f;
    // Popup d'aide de signature
    static final float SIGNATURE_HELP_ROW_HEIGHT_DP = 22f;
    // 4.8.0 : largeur ADAPTATIVE — le contenu est mesuré, la largeur
    // finale est bornée [min, max] (et par l'écran). Auparavant fixe à
    // 320 dp : les libellés courts étiraient une carte vide, les
    // longs étaient coupés NET sans ellipse (le paramètre actif
    // pouvait sortir de l'écran).
    static final float SIGNATURE_HELP_MIN_WIDTH_DP = 200f;
    static final float SIGNATURE_HELP_MAX_WIDTH_DP = 320f;
    // 4.10.0 : 14 dp — aligné sur le popup de complétion.
    static final float SIGNATURE_HELP_RADIUS_DP = 14f;
    /** 4.10.0 : fenêtrage — au-delà de 5 paramètres, seuls l'actif ± 1
     *  sont montrés (les autres en « … ») ; la vue peek (appui long)
     *  révèle la signature complète. */
    static final int SIGNATURE_WINDOW_THRESHOLD = 5;
    static final int SIGNATURE_WINDOW_RADIUS = 1;
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
        // Initialisé ici : la capture de view exige qu'elle soit posée.
        this.card = new PopupCard(view);
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
    /** Lignes affichées de la signature (1 hors peek, wrap en peek). */
    private final List<String> sigLines = new ArrayList<>();

    /** Indices du tableau de layout retourné par computeSignatureLayout(). */
    private static final int SIG_ANCHOR_X = 0, SIG_ANCHOR_Y = 1, SIG_WIDTH = 2,
            SIG_POPUP_H = 3, SIG_ROW_H = 4, SIG_STEPPER_H = 5, SIG_ACTIVE = 6,
            SIG_B_LEFT = 7, SIG_B_TOP = 8, SIG_B_RIGHT = 9, SIG_B_BOTTOM = 10,
            SIG_HAS_BADGE = 11, SIG_P_LEFT = 12, SIG_P_TOP = 13,
            SIG_P_RIGHT = 14, SIG_P_BOTTOM = 15;

    /**
     * Calcule le layout complet du popup d'aide de signature (4.10.0 —
     * une surcharge à la fois) : ligne de STEPPER « ‹ i/n › » (seulement
     * s'il y a un choix), ligne de signature UNIQUE fenêtrée autour du
     * paramètre actif (±1 au-delà de 5 paramètres — la vue peek révèle
     * la signature complète multi-lignes), bande de documentation du
     * paramètre actif puis javadoc. Largeur ADAPTATIVE bornée [min, max]
     * et par l'écran, ancre au-dessus de la ligne du caret (bascule en
     * dessous, clampée à la vue), rectangles des flèches du stepper.
     *
     * @return le tableau de layout (indices SIG_*), ou null (popup
     *         absent). Remplit sigDocLines/sigParamDocLabel et
     *         sigLines (wrap de la signature en vue peek).
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
        boolean peeked = view.signatureHelpController != null
                && view.signatureHelpController.isPeeked();

        // ── Index actif : l'override clavier/tactile du contrôleur
        // d'abord, sinon le choix du serveur.
        int activeIdx = view.getEffectiveActiveSignature();
        if (activeIdx < 0) {
            activeIdx = Math.max(0, Math.min(view.signatureHelpData.activeSignature,
                    view.signatureHelpData.signatures.size() - 1));
        }
        jo.codeeditor.completion.SignatureHelpController.Signature activeSig =
                view.signatureHelpData.signatures.get(activeIdx);

        // ── Libellé affiché : FENÊTRÉ autour du paramètre actif hors
        // peek (±1 paramètre au-delà du seuil), COMPLET en peek.
        String displayLabel;
        int displayActiveParam;
        if (peeked) {
            displayLabel = activeSig.label;
            displayActiveParam = activeSig.activeParameter;
        } else {
            int[] winActive = new int[1];
            displayLabel = windowSignature(activeSig.label,
                    activeSig.activeParameter, winActive);
            displayActiveParam = winActive[0];
        }

        // ── Mesure de la ligne de signature (hors peek, une seule
        // ligne ; en peek, wrap multi-lignes de la signature complète).
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        float padX = 8 * density;
        sigLines.clear();
        float sigTextH;
        if (peeked) {
            // La largeur de wrap dépend de la largeur du popup — deux
            // passes : d'abord la largeur naturelle (une ligne) pour
            // fixer la largeur, puis le wrap réel.
            float natural = view.textPaint.measureText(displayLabel);
            float viewW0 = view.getWidth();
            float maxW0 = Math.min(SIGNATURE_HELP_MAX_WIDTH_DP * density,
                    viewW0 - 8 * density);
            float width0 = Math.max(Math.min(natural + 2 * padX, maxW0),
                    Math.min(SIGNATURE_HELP_MIN_WIDTH_DP * density, maxW0));
            sigLines.addAll(wrapText(displayLabel,
                    width0 - 2 * padX, view.textPaint));
            sigTextH = sigLines.size() * rowH;
        } else {
            sigLines.add(displayLabel);
            sigTextH = rowH;
        }

        // ── Stepper de surcharges « ‹ i/n › » : présent seulement
        // s'il y a un choix à faire — ligne AU-DESSUS de la signature.
        int total = view.signatureHelpData.signatures.size();
        boolean hasBadge = total > 1;
        float stepperTextSize = view.metrics.getTextSize() * 0.7f;
        view.textPaint.setTextSize(stepperTextSize);
        String leftArrow = "\u2039";  // ‹
        String rightArrow = "\u203A"; // ›
        String countText = (activeIdx + 1) + "/" + total;
        float arrowW = view.textPaint.measureText(leftArrow);
        float countW = hasBadge ? view.textPaint.measureText(countText) : 0;
        float arrowPad = 4 * density;
        float stepperH = hasBadge ? rowH : 0;

        // ── Largeur finale : contenu borné [min, max] et par l'écran.
        float viewW = view.getWidth();
        float contentW = Math.max(naturalSigWidth(displayLabel, displayActiveParam),
                hasBadge ? countW + 2 * (arrowW + 2 * arrowPad) : 0)
                + 2 * padX;
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
        // En peek, la bande doc vit sous un séparateur (elle fait
        // partie de la vue étendue) ; hors peek elle est collée.
        float sigDivH = peeked && docH > 0 ? 5 * density : 0;
        float popupH = stepperH + sigTextH + sigDivH + docH;

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

        // ── Flèches du stepper ‹ i/n › : centrées horizontalement sur
        // la ligne du stepper, zones larges de flèche + marge (doigt).
        float arrowH = stepperTextSize * 1.6f;
        float stepperBase = anchorY + (rowH - arrowH) / 2f;
        float countTotal = countW + 2 * (arrowW + 2 * arrowPad);
        float leftCx = anchorX + (width - countTotal) / 2f + arrowPad;
        float rightCx = anchorX + (width + countTotal) / 2f - arrowPad;

        float[] out = new float[16];
        out[SIG_ANCHOR_X] = anchorX;
        out[SIG_ANCHOR_Y] = anchorY;
        out[SIG_WIDTH] = width;
        out[SIG_POPUP_H] = popupH;
        out[SIG_ROW_H] = rowH;
        out[SIG_STEPPER_H] = stepperH;
        out[SIG_ACTIVE] = activeIdx;
        // Flèche GAUCHE (‹) — rect tactile.
        out[SIG_B_LEFT] = leftCx - arrowW / 2f - arrowPad;
        out[SIG_B_TOP] = stepperBase;
        out[SIG_B_RIGHT] = leftCx + arrowW / 2f + arrowPad;
        out[SIG_B_BOTTOM] = stepperBase + arrowH;
        out[SIG_HAS_BADGE] = hasBadge ? 1 : 0;
        // Flèche DROITE (›) — rect tactile.
        out[SIG_P_LEFT] = rightCx - arrowW / 2f - arrowPad;
        out[SIG_P_TOP] = stepperBase;
        out[SIG_P_RIGHT] = rightCx + arrowW / 2f + arrowPad;
        out[SIG_P_BOTTOM] = stepperBase + arrowH;
        return out;
    }

    /**
     * Géométrie du popup d'aide de signature pour le geste — partagée
     * avec l'appui long « peek » : {left, top, right, bottom} du popup
     * en px écran, ou null (popup absent).
     */
    float[] signatureHelpPopupMetrics() {
        float[] layout = computeSignatureLayout();
        if (layout == null) return null;
        return new float[]{layout[SIG_ANCHOR_X], layout[SIG_ANCHOR_Y],
                layout[SIG_ANCHOR_X] + layout[SIG_WIDTH],
                layout[SIG_ANCHOR_Y] + layout[SIG_POPUP_H]};
    }

    /**
     * Géométrie des flèches du stepper de surcharges — partagée avec
     * le hit-test tactile : {left, top, right, bottom} de la flèche
     * GAUCHE puis de la flèche DROITE (‹ puis ›), ou null (popup absent
     * ou surcharge unique — pas de stepper).
     */
    float[] signatureHelpStepperMetrics() {
        float[] layout = computeSignatureLayout();
        if (layout == null || layout[SIG_HAS_BADGE] < 1) return null;
        return new float[]{layout[SIG_B_LEFT], layout[SIG_B_TOP],
                layout[SIG_B_RIGHT], layout[SIG_B_BOTTOM],
                layout[SIG_P_LEFT], layout[SIG_P_TOP],
                layout[SIG_P_RIGHT], layout[SIG_P_BOTTOM]};
    }

    /**
     * Fenêtre une signature autour de son paramètre actif : au-delà de
     * {@link #SIGNATURE_WINDOW_THRESHOLD} paramètres, seuls l'actif ±
     * {@link #SIGNATURE_WINDOW_RADIUS} restent affichés, les tronqués
     * remplacés par « … ». Le compte de l'actif est traduit dans le
     * repère FENÊTRÉ (outActive[0]). Sous le seuil, libellé intact.
     */
    public static String windowSignature(String label, int activeParam, int[] outActive) {
        outActive[0] = activeParam;
        if (label == null) return "";
        int openIdx = label.indexOf('(');
        int closeIdx = label.lastIndexOf(')');
        if (openIdx < 0 || closeIdx <= openIdx + 1) return label;
        // Découpe des plages de paramètres (virgules de profondeur 0).
        java.util.List<int[]> ranges = new ArrayList<>();
        int depth = 0, start = openIdx + 1;
        for (int i = openIdx + 1; i < closeIdx; i++) {
            char c = label.charAt(i);
            if (c == '(' || c == '[' || c == '{' || c == '<') depth++;
            else if (c == ')' || c == ']' || c == '}' || c == '>') depth--;
            else if (c == ',' && depth == 0) {
                ranges.add(new int[]{start, i});
                start = i + 1;
            }
        }
        ranges.add(new int[]{start, closeIdx});
        int n = ranges.size();
        int act = Math.max(0, Math.min(activeParam, n - 1));
        outActive[0] = act;
        if (n <= SIGNATURE_WINDOW_THRESHOLD) return label;
        int from = Math.max(0, act - SIGNATURE_WINDOW_RADIUS);
        int to = Math.min(n - 1, act + SIGNATURE_WINDOW_RADIUS);
        StringBuilder sb = new StringBuilder(label.substring(0, openIdx + 1));
        boolean first = true;
        for (int i = from; i <= to; i++) {
            int[] r = ranges.get(i);
            // Trim : la plage commence juste après la virgule — l'espace
            // de formatage ne doit pas se propager au libellé fenêtré.
            int st = r[0];
            while (st < r[1] && Character.isWhitespace(label.charAt(st))) st++;
            if (st >= r[1]) continue; // paramètre vide (recovery)
            if (!first) sb.append(", ");
            sb.append(label, st, r[1]);
            first = false;
        }
        if (from > 0) sb.insert(openIdx + 1, "…, ");
        if (to < n - 1) sb.append(", …");
        sb.append(label.substring(closeIdx));
        outActive[0] = act - from + (from > 0 ? 1 : 0);
        return sb.toString();
    }

    /** Largeur naturelle d'un libellé de signature (paramètre actif en
     *  gras — mesure approximée au poids normal, la largeur du gras
     *  étant proche). */
    private float naturalSigWidth(String label, int activeParam) {
        int[] range = findParameterRangeInLabel(label, activeParam);
        if (range == null) return view.textPaint.measureText(label);
        return view.textPaint.measureText(label.substring(0, range[0]))
                + view.textPaint.measureText(label.substring(range[0], range[1]))
                + view.textPaint.measureText(label.substring(range[1]));
    }
    /**
     * Dessine le popup d'aide de signature (4.10.0 — une surcharge à la
     * fois ; 4.11.0 — paramètres en trois paliers). Ancré AU-DESSUS de
     * la ligne du curseur (contrairement au popup de complétion, qui
     * est EN DESSOUS) ; s'il n'y a pas de place au-dessus, bascule en
     * dessous. Structure :
     * <ul>
     *   <li>ligne de STEPPER « ‹ i/n › » (seulement s'il y a un choix) —
     *       flèches en accent quand actives, atténuées aux bornes ;</li>
     *   <li>signature UNIQUE, fenêtrée autour du paramètre actif
     *       (±1 au-delà de 5 paramètres, « … » pour les tronqués) ;
     *       les paramètres DÉJÀ FRAPPÉS s'allument en accent (le
     *       parcours visible de l'appel — chaque branche déjà écrite
     *       ressort au fur et à mesure de la frappe), le paramètre
     *       ACTIF est en gras + accent et garanti VISIBLE (ellipsé
     *       autour de lui), ceux À VENIR restent en atténué ; l'appui
     *       long (peek) révèle la signature COMPLÈTE en
     *       multi-lignes ;</li>
     *   <li>bande de documentation : paramètre ACTIF (label en accent)
     *       puis javadoc, séparée par un filet en vue peek.</li>
     * </ul>
     */
    void drawSignatureHelpPopup(Canvas canvas) {
        float[] l = computeSignatureLayout();
        if (l == null) return;
        float density = view.getResources().getDisplayMetrics().density;
        float rowH = l[SIG_ROW_H];
        float width = l[SIG_WIDTH];
        float radius = SIGNATURE_HELP_RADIUS_DP * density;
        int activeIdx = (int) l[SIG_ACTIVE];
        float anchorX = l[SIG_ANCHOR_X];
        float anchorY = l[SIG_ANCHOR_Y];
        boolean hasStepper = l[SIG_HAS_BADGE] >= 1;
        float stepperH = l[SIG_STEPPER_H];
        float padX = 8 * density;
        boolean peeked = view.signatureHelpController != null
                && view.signatureHelpController.isPeeked();
        int total = view.signatureHelpData.signatures.size();

        // Fond + bordure — carte partagée du kit (même rendu que le
        // motif historique : fond glassBg, bordure hairline glassBorder).
        RectF rect = scratchRect;
        rect.set(anchorX, anchorY, anchorX + width, anchorY + l[SIG_POPUP_H]);
        card.drawSurface(canvas, rect, radius);

        canvas.save();
        canvas.clipRect(rect);

        // ── Stepper « ‹ i/n › » : flèche gauche, compteur (atténué),
        // flèche droite. Une flèche aux BORNES est atténuée (grisée) —
        // le hit-test tactile la respecte (pas de cycle au-delà).
        if (hasStepper) {
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
            float stepperBase = anchorY;
            float cy = stepperBase + rowH * 0.5f
                    - (view.textPaint.ascent() + view.textPaint.descent()) / 2f;
            String leftArrow = "\u2039";
            String rightArrow = "\u203A";
            String countText = (activeIdx + 1) + "/" + total;
            float arrowW = view.textPaint.measureText(leftArrow);
            float countW = view.textPaint.measureText(countText);
            float arrowPad = 4 * density;
            float countTotal = countW + 2 * (arrowW + 2 * arrowPad);
            float leftCx = anchorX + (width - countTotal) / 2f + arrowPad;
            float rightCx = anchorX + (width + countTotal) / 2f - arrowPad;
            view.textPaint.setColor(activeIdx > 0
                    ? view.theme.func : view.theme.gutterText);
            canvas.drawText(leftArrow, leftCx - arrowW / 2f, cy, view.textPaint);
            view.textPaint.setColor(view.theme.gutterText);
            canvas.drawText(countText, leftCx + arrowW / 2f + arrowPad, cy,
                    view.textPaint);
            view.textPaint.setColor(activeIdx < total - 1
                    ? view.theme.func : view.theme.gutterText);
            canvas.drawText(rightArrow, rightCx - arrowW / 2f, cy, view.textPaint);
        }

        // ── Signature unique : fenêtrée hors peek (sigLines = 1 ligne),
        // complète wrappée en peek. TROIS PALIERS (4.11.0) : paramètres
        // déjà frappés en ACCENT, actif en accent GRAS, à venir en
        // ATTÉNUÉ — la frappe allume la signature branche par branche.
        // L'actif reste garanti visible (ellipsizeAroundActive) et le
        // peek montre la signature entière par construction.
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        jo.codeeditor.completion.SignatureHelpController.Signature activeSig =
                view.signatureHelpData.signatures.get(activeIdx);
        int displayActiveParam = peeked ? activeSig.activeParameter
                : windowedActiveParam(activeSig);
        float sigTop = anchorY + stepperH;
        float availW = width - 2 * padX;
        for (int i = 0; i < sigLines.size(); i++) {
            String line = sigLines.get(i);
            float baseline = sigTop + i * rowH + rowH * 0.7f;
            drawSignatureLine(canvas, line, anchorX + padX, baseline,
                    displayActiveParam, availW, peeked);
        }

        // ── Bande documentation : paramètre ACTIF en tête (label en
        // accent sur la première ligne si le wrap ne l'a pas coupé),
        // puis la javadoc (sections @ en couleur de mot-clé). En peek,
        // un filet sépare la signature complète de sa documentation.
        if (!sigDocLines.isEmpty()) {
            float sigTextH = sigLines.size() * rowH;
            float docTop = sigTop + sigTextH + (peeked ? 5 * density : 0);
            if (peeked) {
                view.caretPaint.setStyle(Paint.Style.STROKE);
                view.caretPaint.setStrokeWidth(1f);
                view.caretPaint.setColor(view.theme.glassBorder);
                canvas.drawLine(anchorX, docTop, anchorX + width, docTop,
                        view.caretPaint);
                docTop += 1;
            }
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

    /** Index du paramètre actif dans le repère FENÊTRÉ du libellé affiché. */
    private int windowedActiveParam(
            jo.codeeditor.completion.SignatureHelpController.Signature sig) {
        int[] win = new int[1];
        windowSignature(sig.label, sig.activeParameter, win);
        return win[0];
    }

    /**
     * Dessine UNE ligne de signature en TROIS PALIERS quand sa
     * structure paramétrable est lisible et qu'elle tient : les
     * paramètres DÉJÀ FRAPPÉS en accent (le parcours visible de
     * l'appel — chaque branche déjà écrite s'allume à mesure que la
     * frappe avance), le paramètre ACTIF en accent GRAS, ceux À VENIR
     * en atténué (la structure qui suit l'actif — virgules, parenthèse
     * fermante — s'atténue avec eux). Dégradé honnête : structure
     * introuvable (pas de couple de parenthèses sur la ligne wrappée)
     * ou ligne trop large (ellipse) → trois zones pré/actif/post,
     * l'actif restant seul en gras + accent et la queue atténuée.
     */
    private void drawSignatureLine(Canvas canvas, String line, float x,
            float baseline, int activeParam, float availW, boolean peeked) {
        int[][] ranges = findParameterRangesInLabel(line);
        if (ranges != null && ranges.length > 0
                && (peeked || view.textPaint.measureText(line) <= availW)) {
            drawSignatureTiers(canvas, line, x, baseline, ranges, activeParam);
            return;
        }
        int[] paramRange = findParameterRangeInLabel(line, activeParam);
        String pre, param, post;
        if (paramRange == null) {
            pre = "";
            param = "";
            post = line;
        } else {
            pre = line.substring(0, paramRange[0]);
            param = line.substring(paramRange[0], paramRange[1]);
            post = line.substring(paramRange[1]);
        }
        if (!peeked) {
            String[] parts = ellipsizeAroundActive(pre, param, post,
                    availW, view.textPaint);
            pre = parts[0];
            param = parts[1];
            post = parts[2];
        }
        // La queue (virgules, paramètres restants, fermente) est la
        // part À VENIR de l'appel — atténuée comme les paliers complets.
        drawSignatureRowText(canvas, x, baseline, pre, param, post,
                paramRange != null);
    }

    /**
     * Rendu TROIS PALIERS d'une ligne de signature paramétrable :
     * paramètres déjà frappés en ACCENT (le déjà-écrit de l'appel),
     * paramètre ACTIF en accent GRAS, paramètres à venir en ATTÉNUÉ.
     * La structure (nom, parenthèses, virgules) reste en couleur de
     * texte jusqu'à l'actif, puis s'atténue avec les paramètres
     * restants — la ligne devient sa propre barre de progression.
     */
    private void drawSignatureTiers(Canvas canvas, String line, float x,
            float baseline, int[][] ranges, int activeParam) {
        int act = Math.max(0, Math.min(activeParam, ranges.length - 1));
        int cursor = 0;
        for (int p = 0; p < ranges.length; p++) {
            int start = ranges[p][0];
            if (start > cursor) {
                x = drawSignatureSegment(canvas,
                        line.substring(cursor, start), x, baseline,
                        p <= act ? view.theme.textColor : view.theme.gutterText,
                        false);
            }
            boolean actif = p == act;
            x = drawSignatureSegment(canvas,
                    line.substring(start, ranges[p][1]), x, baseline,
                    p <= act ? view.theme.func : view.theme.gutterText, actif);
            cursor = ranges[p][1];
        }
        if (cursor < line.length()) {
            drawSignatureSegment(canvas, line.substring(cursor), x, baseline,
                    view.theme.gutterText, false);
        }
    }

    /** Un segment typé de la ligne de signature — renvoie le X suivant. */
    private float drawSignatureSegment(Canvas canvas, String s, float x,
            float baseline, int color, boolean gras) {
        if (s == null || s.isEmpty()) return x;
        view.textPaint.setColor(color);
        view.textPaint.setTypeface(gras
                ? card.grasMémoïsé() : view.metrics.getTypeface());
        canvas.drawText(s, x, baseline, view.textPaint);
        return x + view.textPaint.measureText(s);
    }

    /**
     * Dessine une rangée de signature en trois segments dégradés : pré
     * et post en couleur de texte (post atténué au besoin), paramètre
     * actif en gras + couleur de fonction.
     */
    private void drawSignatureRowText(Canvas canvas, float x, float baseline,
            String pre, String param, String post, boolean postAttenué) {
        if (!pre.isEmpty()) {
            view.textPaint.setColor(view.theme.textColor);
            view.textPaint.setTypeface(view.metrics.getTypeface());
            canvas.drawText(pre, x, baseline, view.textPaint);
            x += view.textPaint.measureText(pre);
        }
        if (!param.isEmpty()) {
            view.textPaint.setColor(view.theme.func);
            view.textPaint.setTypeface(card.grasMémoïsé());
            canvas.drawText(param, x, baseline, view.textPaint);
            x += view.textPaint.measureText(param);
        }
        if (!post.isEmpty()) {
            view.textPaint.setColor(postAttenué
                    ? view.theme.gutterText : view.theme.textColor);
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
        return TextLayoutKit.ellipsize(s, maxW,
                TextLayoutKit.Truncate.TAIL, t -> paint.measureText(t));
    }

    /** Coupe {@code s} par la GAUCHE avec ellipse pour tenir dans maxW. */
    private static String truncateHead(String s, float maxW, Paint paint) {
        return TextLayoutKit.ellipsize(s, maxW,
                TextLayoutKit.Truncate.HEAD, t -> paint.measureText(t));
    }


    /**
     * Au mieux : trouve la plage [start, end) du paramètre actif dans un
     * label de signature comme {@code "foo(int x, String y, int z)"} afin
     * de le rendre en couleur d'accent. Renvoie null si introuvable.
     */
    private static int[] findParameterRangeInLabel(String label, int activeParam) {
        int[][] all = findParameterRangesInLabel(label);
        if (all == null || all.length == 0) return null;
        int act = Math.max(0, Math.min(activeParam, all.length - 1));
        return all[act];
    }

    /**
     * TOUTES les plages de paramètres [start, end) d'un libellé de
     * signature ({@code "foo(int x, String y)"} → deux plages),
     * séparées par les virgules de profondeur 0 entre les
     * parenthèses. Base du rendu TROIS PALIERS (4.11.0) : chaque
     * plage reçoit son palier (frappé / actif / à venir). Null si la
     * ligne n'expose pas de couple de parenthèses (lignes wrappées
     * de la vue peek).
     */
    static int[][] findParameterRangesInLabel(String label) {
        if (label == null) return null;
        int openIdx = label.indexOf('(');
        if (openIdx < 0) return null;
        int closeIdx = label.lastIndexOf(')');
        if (closeIdx <= openIdx) return null;
        List<int[]> ranges = new ArrayList<>();
        int depth = 0;
        int paramStart = openIdx + 1;
        for (int i = openIdx + 1; i < closeIdx; i++) {
            char c = label.charAt(i);
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth--;
            else if (c == ',' && depth == 0) {
                ajouterPlage(ranges, label, paramStart, i);
                paramStart = i + 1;
            }
        }
        ajouterPlage(ranges, label, paramStart, closeIdx);
        return ranges.toArray(new int[0][]);
    }

    /**
     * Ajoute la plage [start, end) SANS ses espaces de bord — la
     * structure (virgules, espaces de formatage) garde sa couleur
     * propre, le paramètre commence à son premier caractère
     * significatif. Une plage blanche est ignorée.
     */
    private static void ajouterPlage(List<int[]> ranges, String label,
            int start, int end) {
        while (start < end && Character.isWhitespace(label.charAt(start))) start++;
        while (end > start && Character.isWhitespace(label.charAt(end - 1))) end--;
        if (start < end) ranges.add(new int[]{start, end});
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

        // Fond + bordure — carte partagée du kit (rendu identique).
        RectF rect = scratchRect;
        rect.set(anchorX, anchorY, anchorX + popupW, anchorY + popupH);
        card.drawSurface(canvas, rect, radius);

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
        // Séparateur sous la bande signature — filet partagé du kit.
        if (sigBgBottom >= 0) {
            card.drawDivider(canvas, anchorX, anchorX + popupW, sigBgBottom);
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
     * signature (4.4.0). Délègue au kit partagé : mesure MOT à MOT
     * (l'ancien moteur mesurait après CHAQUE caractère) et coupe les
     * mots plus larges que la ligne, qu'il laissait déborder.
     */
    public static List<String> wrapText(String text, float maxWidth, Paint paint) {
        return TextLayoutKit.wrap(text, maxWidth, t -> paint.measureText(t));
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
        card.drawSurface(canvas, rect, radius);
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
        card.drawSurface(canvas, rect, radius);
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
     * valeur (accent + gras), l'identité qualifiée (signature, FQCN)
     * passe SOUS le libellé, seconde ligne atténuée.</p>
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

        // Fond + bordure — les QUATRE coins arrondis (4.10.0 : la base
        // carrée des « coins hauts seuls » tranchait avec la carte de
        // verre flottante que le popup est censé être).
        RectF rect = scratchRect;
        rect.set(anchorX, anchorY, anchorX + width, anchorY + popupH);
        float[] radii = completionCornerRadii;
        radii[0] = radius; radii[1] = radius;   // haut-gauche
        radii[2] = radius; radii[3] = radius;   // haut-droite
        radii[4] = radius; radii[5] = radius;   // bas-droite
        radii[6] = radius; radii[7] = radius;   // bas-gauche
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

    // ── Layout d'une rangée de complétion (4.9.1) ─────────────────
    // Rangée DEUX lignes : le libellé (runs de match en accent) sur
    // la première ligne, l'identité qualifiée — FQCN d'un type,
    // signature d'un membre — SOUS le libellé, en atténué, sur toute
    // la largeur restante. L'origine (container) reste un chip à
    // droite de la première ligne : le nom court vit à côté du chip,
    // le nom long vit dessous — jamais l'un contre l'autre sur la
    // même ligne. Calcul PUR (aucune mesure de paint n'y vit — les
    // largeurs mesurées entrent en paramètre) : la géométrie se
    // vérifie sans Canvas (CompletionRowLayoutTest).
    /** Indices du tableau de layout rempli par computeCompletionRowLayout. */
    static final int CR_BADGE_SIZE = 0, CR_BADGE_LEFT = 1, CR_BADGE_TOP = 2,
            CR_LABEL_X = 3, CR_LABEL_BASE = 4, CR_LABEL_MAX_RIGHT = 5,
            CR_CONTAINER_LEFT = 6, CR_CONTAINER_RIGHT = 7, CR_CONTAINER_BASE = 8,
            CR_DETAIL_X = 9, CR_DETAIL_BASE = 10, CR_DETAIL_MAX_RIGHT = 11,
            CR_GLYPH_CX = 12, CR_GLYPH_CY = 13, CR_GLYPH_SIZE = 14;
    /** Badge au plus 20 dp (la rangée deux lignes en fait 40). */
    static final float COMPLETION_BADGE_MAX_DP = 20f;
    /** Rayon du badge de type (4.10.0 : 4 → 6 dp, carré plus doux). */
    static final float COMPLETION_BADGE_RADIUS_DP = 6f;
    /** Glyphe ⓘ au plus 16 dp, et sa réserve en fin de première ligne. */
    static final float COMPLETION_GLYPH_MAX_DP = 16f;
    static final float COMPLETION_GLYPH_RESERVE_DP = 18f;
    /** Tableau de layout de rangée réutilisé (mono-thread, onDraw). */
    private final float[] completionRowScratch = new float[15];

    /**
     * Calcule la géométrie d'une rangée du popup de complétion
     * (4.10.0 — la ligne du MILIEU gagne) : le libellé occupe SEUL la
     * première ligne (toute la largeur, moins la réserve du glyphe) ;
     * la seconde ligne porte le CONTAINER à GAUCHE (l'origine du
     * symbole — paquet ou classe déclarante, ellipsé) et le DÉTAIL
     * (FQCN d'un type, type de retour/signature d'un membre) ALIGNÉ À
     * DROITE — la lecture devient « qui / d'où ça vient / ce que c'est »
     * sur trois axes distincts au lieu de deux en compétition. Toutes
     * les entrées sont des grandeurs déjà résolues (densité, hauteur de
     * rangée, largeur MESURÉE du container) : la méthode est pure,
     * remplissant le tableau fourni — aucun Canvas, aucune mesure,
     * testable seule.
     */
    static void computeCompletionRowLayout(float[] m, float anchorX, float y,
                                           float width, float rowH, float padX,
                                           float density, float containerW,
                                           boolean docGlyph) {
        // Badge — plafonné, centré sur la rangée entière.
        m[CR_BADGE_SIZE] = Math.min(rowH - 10 * density,
                COMPLETION_BADGE_MAX_DP * density);
        m[CR_BADGE_LEFT] = anchorX + padX;
        m[CR_BADGE_TOP] = y + (rowH - m[CR_BADGE_SIZE]) * 0.5f;
        // Première ligne : le libellé SEUL — il ne dispute plus sa
        // droite au chip d'origine (parti en seconde ligne) ; le glyphe
        // ⓘ garde sa réserve en fin de ligne quand il est montré.
        m[CR_LABEL_X] = m[CR_BADGE_LEFT] + m[CR_BADGE_SIZE] + 6 * density;
        m[CR_LABEL_BASE] = y + rowH * 0.45f;
        m[CR_CONTAINER_RIGHT] = anchorX + width - padX;
        if (docGlyph) {
            m[CR_LABEL_MAX_RIGHT] = m[CR_CONTAINER_RIGHT]
                    - COMPLETION_GLYPH_RESERVE_DP * density;
        } else {
            m[CR_LABEL_MAX_RIGHT] = m[CR_CONTAINER_RIGHT];
        }
        // Seconde ligne : container À GAUCHE (même colonne que le
        // libellé), détail ALIGNÉ À DROITE — chacun dans sa moitié.
        m[CR_CONTAINER_LEFT] = m[CR_LABEL_X];
        m[CR_CONTAINER_BASE] = y + rowH * 0.85f;
        m[CR_DETAIL_X] = m[CR_LABEL_X];
        m[CR_DETAIL_BASE] = y + rowH * 0.85f;
        m[CR_DETAIL_MAX_RIGHT] = m[CR_CONTAINER_RIGHT];
        // Glyphe ⓘ — centré sur la première ligne, dans sa réserve.
        m[CR_GLYPH_SIZE] = Math.min(rowH - 14 * density,
                COMPLETION_GLYPH_MAX_DP * density);
        m[CR_GLYPH_CX] = m[CR_CONTAINER_RIGHT] - m[CR_GLYPH_SIZE] * 0.5f - 2 * density;
        m[CR_GLYPH_CY] = y + rowH * 0.33f;
    }

    /**
     * ★ Une rangée du popup, DEUX lignes : badge de type (carré
     * arrondi teinté + glyphe) puis le libellé — caractères matchés
     * du préfixe en accent/gras — sur la première ligne, l'origine du
     * symbole (container) en chip à droite de cette ligne, et
     * l'identité qualifiée (FQCN d'un type, signature d'un membre)
     * SOUS le libellé, en atténué, sur toute la largeur restante. Le
     * nom court et le nom long ne se disputent plus la même ligne.
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

        // ── Mesures préalables : le container (seconde ligne, GAUCHE)
        // et le détail (seconde ligne, DROITE) se partagent la largeur —
        // chacun ellipsé À SA MOITIÉ AVANT le layout, la zone de l'un
        // ne recouvre jamais celle de l'autre (paint partagé : taille
        // posée puis restaurée). ──
        String container = (item.container != null) ? item.container : "";
        String detail = (item.detail != null) ? item.detail : "";
        float halfW = (width - 2 * padX - (width > 0 ? 8 * density : 0)) * 0.5f;
        float containerW = 0f;
        if (!container.isEmpty()) {
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
            container = ellipsizeTail(container, halfW);
            containerW = view.textPaint.measureText(container);
        }
        float detailW = 0f;
        if (!detail.isEmpty()) {
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            float detailAvail = Math.max(0, width - 2 * padX - 8 * density
                    - (containerW > 0 ? containerW + 8 * density : 0));
            detail = ellipsizeTail(detail, detailAvail);
            detailW = view.textPaint.measureText(detail);
        }
        float[] m = completionRowScratch;
        computeCompletionRowLayout(m, anchorX, y, width, rowH, padX,
                density, containerW, docGlyph);

        // ── Badge de type (plafonné, centré sur la rangée) ──
        jo.codeeditor.completion.CompletionKindBadge.Meta badge =
                jo.codeeditor.completion.CompletionKindBadge.meta(
                        item.kind, item.kindTag, item.icon, darkTheme,
                        view.theme.func);
        float badgeSize = m[CR_BADGE_SIZE];
        float badgeLeft = m[CR_BADGE_LEFT];
        float badgeTop = m[CR_BADGE_TOP];
        RectF badgeRect = scratchRect;
        badgeRect.set(badgeLeft, badgeTop, badgeLeft + badgeSize,
                badgeTop + badgeSize);

        // Fond : teinte du kind à 20 % d'alpha.
        view.bgPaint.setColor((badge.color & 0x00FFFFFF)
                | (0x33 << 24)); // alpha 51 ≈ 0.20
        float badgeRadius = COMPLETION_BADGE_RADIUS_DP * density;
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

        // ── Libellé (première ligne) : caractères matchés au préfixe en
        // accent + gras, clippés à sa zone — le chip d'origine et la
        // réserve du glyphe ne sont jamais recouverts. Popup trop
        // étroit pour le nom : badge, chip et seconde ligne seuls. ──
        float textX = m[CR_LABEL_X];
        float labelMaxRight = m[CR_LABEL_MAX_RIGHT];
        if (labelMaxRight > textX) {
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
                canvas.drawText(label, textX, m[CR_LABEL_BASE], view.textPaint);
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
                    canvas.drawText(seg, x, m[CR_LABEL_BASE], view.textPaint);
                    view.textPaint.setFakeBoldText(false);
                    x += view.textPaint.measureText(seg);
                    k = runEnd + 1;
                }
            }
            canvas.restore();
        }

        // ── Seconde ligne : container (l'ORIGINE du symbole — paquet ou
        // classe déclarante) À GAUCHE, détail (FQCN d'un type, type de
        // retour/signature d'un membre) ALIGNÉ À DROITE — deux axes de
        // lecture distincts, atténués. Le glyphe ⓘ vit en fin de
        // PREMIÈRE ligne (réserve dédiée). ──
        if (!container.isEmpty()) {
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
            view.textPaint.setColor(view.theme.gutterText);
            canvas.drawText(container, m[CR_CONTAINER_LEFT],
                    m[CR_CONTAINER_BASE], view.textPaint);
        }
        if (!detail.isEmpty()) {
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.75f);
            view.textPaint.setColor(view.theme.gutterText);
            canvas.drawText(detail, m[CR_DETAIL_MAX_RIGHT] - detailW,
                    m[CR_DETAIL_BASE], view.textPaint);
        }
        if (docGlyph) {
            // 4.4.0 : la documentation se réduit à une icône quand le
            // panneau ne tient pas (vue étroite).
            drawCompletionDocGlyph(canvas, m[CR_GLYPH_CX], m[CR_GLYPH_CY],
                    m[CR_GLYPH_SIZE]);
        }
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
    }

    /**
     * ★ 4.4.0 — Petit glyphe ⓘ de documentation de rangée : cercle
     * atténué + « i » italique. La position et la taille viennent du
     * layout partagé — première ligne, réserve dédiée (4.9.1). Le
     * paint partagé est restauré (style du caretPaint, taille du
     * textPaint).
     */
    private void drawCompletionDocGlyph(Canvas canvas, float cx, float cy,
                                        float size) {
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
     * Délégation au kit : coupe DICHOTOMIQUE, l'ancienne boucle
     * linéaire retraitait un caractère de trop par itération.
     */
    private String ellipsizeTail(String text, float maxW) {
        return TextLayoutKit.ellipsize(text, maxW,
                TextLayoutKit.Truncate.TAIL,
                t -> view.textPaint.measureText(t));
    }
}
