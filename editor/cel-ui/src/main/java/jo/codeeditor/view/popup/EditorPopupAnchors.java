package jo.codeeditor.view.popup;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.SelectionAction;
import jo.codeeditor.view.preview.EditorPreviewController;

import androidx.annotation.RestrictTo;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * Géométrie d'ancrage des popups, extraite d'EditorView.
 *
 * <p>Cette classe possède toutes les computations de GÉOMÉTRIE PURE pour
 * les popups dessinés sur le canvas de l'éditeur — la « source de vérité »
 * unique partagée par la passe de dessin ({@code EditorRenderer}) ET les
 * tests de touche ({@code EditorInputHandler}) :</p>
 * <ul>
 *   <li>{@link #diagnosticSheetMetrics(EditorView)} — la fiche détaillée
 *       par diagnostic ;</li>
 *   <li>{@link #diagnosticListSheetMetrics(EditorView)} — la fiche groupée
 *       par ligne ;</li>
 *   <li>{@link #diagnosticChipMetrics(EditorView, DiagnosticShift.Diagnostic, int, int)}
 *       — la pill de diagnostic (+ badge de comptage) ;</li>
 *   <li>{@link #selectionToolbarMetrics(EditorView)} — la toolbar flottante
 *       de sélection ;</li>
 *   <li>{@link #navMenuMetrics(EditorView)} — le menu contextuel unifié ;</li>
 *   <li>{@link #hitTestToolbarIcons(EditorView, float, float)} — le hit-test
 *       des icônes de toolbar (A+, A−, ¶, lig) ;</li>
 *   <li>{@link #countWrappedLines(EditorView, String, float)} — compteur
 *       partagé de retour à la ligne.</li>
 * </ul>
 *
 * <p>EditorView conserve de fins wrappers délégants avec les signatures
 * historiques (renderer, input handler et tests inchangés) ; les corps
 * vivent désormais ici. Toutes les méthodes sont statiques et lisent l'état
 * package-private de la vue via la référence {@code view} — même motif
 * d'accès que les autres classes manager.</p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class EditorPopupAnchors {

    private EditorPopupAnchors() {}

    // ════════════════════════════════════════════════════════════════
    // D2 — pattern « popup ancré » factorisé
    // ════════════════════════════════════════════════════════════════

    /**
     * D2 (P1) — la géométrie partagée du pattern « popup ancré » :
     * positionner un rectangle de taille {@code (w, h)} ancré à la
     * bande verticale {@code [anchorTopY, anchorBottomY]} d'une ligne
     * du document (le flip vertical ne recouvre JAMAIS la ligne
     * d'ancre), avec clamp horizontal dans la vue.
     *
     * <p>Avant D2, ce calcul (ancre + flip + clamp) était dupliqué
     * méthode par méthode ; les trois consommateurs historiques le
     * partagent désormais — chaque paramétrage reproduit EXACTEMENT le
     * comportement précédent (aucun déplacement de popup, tests
     * existants inchangés) :</p>
     * <ul>
     *   <li><b>popup de complétion</b> ({@code EditorCompletionPopup})
     *       — {@code preferAbove=false}, gap 4 px, yMargin 0, seuil X 0,
     *       inset X 4 px, ancre = X du caret aligné à gauche, borne
     *       gauche = gouttière ;</li>
     *   <li><b>NavMenu</b> ({@code navMenuMetrics}) —
     *       {@code preferAbove=false}, gap et marges NAV_MENU, ancre
     *       pré-décalée de la marge, alignée à gauche, borne gauche =
     *       marge ;</li>
     *   <li><b>popup de diagnostic ancré</b> ({@code squigglePopupMetrics})
     *       — {@code preferAbove=true} (au-dessus de la ligne, flip
     *       dessous), gap et marge 4 dp, ancre centrée sur le mot
     *       diagnostiqué, borne gauche = gouttière + 4 dp.</li>
     * </ul>
     *
     * <p>Placement vertical : {@code preferAbove} place le BAS du popup
     * à {@code anchorTopY - gap} ; s'il ne tient pas (marge haute), il
     * passe SOUS la ligne ({@code anchorBottomY + gap}) ; en dernier
     * recours il est clampé en bas de vue. {@code preferBelow} place le
     * HAUT du popup à {@code anchorBottomY + gap} ; s'il déborde, il
     * passe AU-DESSUS de la ligne ; clampé en haut de vue en dernier
     * recours. Placement horizontal : centré sur l'ancre
     * ({@code centerOnAnchor}) ou aligné à gauche dessus ; déborde à
     * droite au-delà de {@code viewW - xThresholdInset} → ramené à
     * {@code viewW - xInset - w} ; jamais à gauche de
     * {@code leftBound}.</p>
     *
     * @param view la vue hôte (largeur/hauteur du viewport)
     * @param anchorX l'ancre horizontale (colonne du caret, centre du
     *                mot diagnostiqué, ou position pré-décalée)
     * @param anchorTopY le HAUT de la ligne d'ancre (bande fold/wrap
     *                   aware côté appelant)
     * @param anchorBottomY le BAS de la ligne d'ancre
     * @param w largeur du popup
     * @param h hauteur du popup
     * @param preferAbove vrai = AU-DESSUS de la ligne en priorité,
     *                    faux = EN DESSOUS en priorité
     * @param gap l'écart visuel entre le popup et la ligne d'ancre
     * @param yMargin la marge verticale minimale aux bords de la vue
     * @param xThresholdInset l'inset du SEUIL de débordement droit
     * @param xInset l'inset de la valeur clampée à droite
     * @param centerOnAnchor vrai = popup centré sur l'ancre X
     * @param leftBound la borne gauche (gouttière + inset, ou marge)
     * @return {@code {x, y}} — le coin haut-gauche du popup
     */
    public static float[] anchorAboveBelow(EditorView view, float anchorX,
            float anchorTopY, float anchorBottomY, float w, float h,
            boolean preferAbove, float gap, float yMargin,
            float xThresholdInset, float xInset, boolean centerOnAnchor,
            float leftBound) {
        float viewW = view.getWidth();
        float viewH = view.getHeight();
        float y;
        if (preferAbove) {
            y = anchorTopY - gap - h;
            if (y < yMargin) {
                // Pas de place au-dessus — bascule SOUS la ligne.
                y = anchorBottomY + gap;
            }
            if (y + h > viewH - yMargin) {
                y = Math.max(yMargin, viewH - yMargin - h);
            }
            if (y < 0) y = 0;
        } else {
            y = anchorBottomY + gap;
            if (y + h > viewH - yMargin) {
                // Pas de place en dessous — bascule AU-DESSUS de la ligne.
                y = anchorTopY - gap - h;
            }
            if (y < yMargin) y = yMargin;
        }
        float x = centerOnAnchor ? anchorX - w * 0.5f : anchorX;
        if (x + w > viewW - xThresholdInset) {
            x = viewW - xInset - w;
        }
        if (x < leftBound) x = leftBound;
        return new float[]{x, y};
    }

    // ════════════════════════════════════════════════════════════════
    // Compteur de retour à la ligne partagé
    // ════════════════════════════════════════════════════════════════

    /**
     * Effectue le retour à la ligne automatique de {@code msg} avec le paint
     * texte de la fiche de diagnostic et renvoie le nombre de lignes visuelles
     * (min 1). Utilisé par la passe de dessin ET le hit-test afin qu'ils
     * s'accordent toujours sur la hauteur du panneau.
     */
    public static int countWrappedLines(EditorView view, String msg, float maxW) {
        if (msg == null || msg.isEmpty()) return 1;
        int lines = 1;
        StringBuilder cur = new StringBuilder();
        for (String word : msg.split("\\s+")) {
            String test = cur.length() == 0 ? word : cur + " " + word;
            if (view.textPaint.measureText(test) > maxW && cur.length() > 0) {
                lines++;
                cur = new StringBuilder(word);
            } else {
                cur = cur.length() == 0 ? new StringBuilder(word) : cur.append(" ").append(word);
            }
        }
        return lines;
    }

    // ════════════════════════════════════════════════════════════════
    // Fiche détaillée de diagnostic
    // ════════════════════════════════════════════════════════════════

    /**
     * Géométrie partagée de la fiche de diagnostic (portage du DiagnosticSheet
     * standard). Renvoie {@code null} quand rien n'est affiché, sinon
     * {@code [panelTop, panelBottom, actionStartY, actionRowH,
     * closeCx, closeCy, closeR]}.
     */
    public static float[] diagnosticSheetMetrics(EditorView view) {
        if (!view.diagnosticPopupVisible || view.diagnosticPopupItem == null
                || view.session == null) return null;
        DiagnosticShift.Diagnostic d = view.diagnosticPopupItem;
        float density = view.getResources().getDisplayMetrics().density;
        int line = view.session.getDocument().lineForOffset(d.start);
        List<EditorView.CodeAction> actions = view.codeActionsByLine.get(line);
        int actionCount = actions != null ? actions.size() : 0;
        String msg = d.message != null ? d.message : "";
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
        int msgLines = Math.min(countWrappedLines(view, msg, view.getWidth() - 24 * density),
                EditorView.DIAG_SHEET_MAX_MSG_LINES);
        view.textPaint.setTextSize(view.metrics.getTextSize());
        float headerH = EditorView.DIAG_SHEET_HEADER_DP * density;
        float msgH = Math.max(1, msgLines) * EditorView.DIAG_SHEET_MSG_LINE_DP * density;
        float actionsH = actionCount > 0
                ? EditorView.DIAG_SHEET_ACTIONS_BLOCK_DP * density
                        + actionCount * EditorView.DIAG_SHEET_ACTION_ROW_DP * density
                : 0;
        float sheetH = headerH + msgH + actionsH
                + EditorView.DIAG_SHEET_BOTTOM_PAD_DP * density;
        float panelTop = Math.max(0, view.getHeight() - sheetH);
        float actionStartY = panelTop + headerH + msgH
                + EditorView.DIAG_SHEET_ACTIONS_BLOCK_DP * density;
        return new float[]{
                panelTop, view.getHeight(), actionStartY,
                EditorView.DIAG_SHEET_ACTION_ROW_DP * density,
                view.getWidth() - 30 * density, panelTop + headerH * 0.5f, 15 * density};
    }

    // ════════════════════════════════════════════════════════════════
    // Fiche de liste des diagnostics groupés par ligne
    // ════════════════════════════════════════════════════════════════

    /**
     * Géométrie partagée de la fiche des diagnostics GROUPÉS. Renvoie null
     * quand elle est masquée ou que sa ligne n'a plus de diagnostics, sinon
     * {@code [panelTop, panelBottom, headerH, rowH, rowCount, moreRow,
     * closeCx, closeCy, closeR]}.
     */
    public static float[] diagnosticListSheetMetrics(EditorView view) {
        if (view.diagnosticListSheetLine < 0 || view.session == null) return null;
        List<DiagnosticShift.Diagnostic> all =
                view.session.getDiagnosticsForLine(view.diagnosticListSheetLine);
        if (all.isEmpty()) return null;
        float density = view.getResources().getDisplayMetrics().density;
        int rows = Math.min(all.size(), EditorView.DIAG_LIST_MAX_ROWS);
        boolean more = all.size() > EditorView.DIAG_LIST_MAX_ROWS;
        float headerH = EditorView.DIAG_SHEET_HEADER_DP * density;
        float rowH = EditorView.DIAG_LIST_ROW_DP * density;
        float sheetH = headerH + (rows + (more ? 1 : 0)) * rowH
                + EditorView.DIAG_SHEET_BOTTOM_PAD_DP * density;
        float panelTop = Math.max(0, view.getHeight() - sheetH);
        return new float[]{
                panelTop, view.getHeight(), headerH, rowH, rows, more ? 1f : 0f,
                view.getWidth() - 30 * density, panelTop + headerH * 0.5f, 15 * density};
    }

    // ════════════════════════════════════════════════════════════════
    // Chip de diagnostic (+ badge de comptage)
    // ════════════════════════════════════════════════════════════════

    /**
     * Géométrie de la pill (chip) de diagnostic pour {@code d} sur
     * {@code line} (motif chip de diagnostic). Renvoie
     * {@code [x, y, w, h, iconR, iconGap, padX, badgeD, badgeGap]} ou null
     * quand la pill serait entièrement hors écran. Source unique de vérité
     * pour la passe de dessin et le hit-test.
     *
     * <p>Quand {@code badgeCount > 1}, un badge de comptage est ajouté à
     * l'extrémité droite de la pill — sa largeur est incluse dans {@code w}
     * afin que le hit-test corresponde à ce qui est dessiné.</p>
     */
    public static float[] diagnosticChipMetrics(EditorView view,
            DiagnosticShift.Diagnostic d, int line, int badgeCount) {
        if (view.session == null) return null;
        EditorDocument doc = view.session.getDocument();
        if (line < 0 || line >= doc.lineCount()) return null;
        float density = view.getResources().getDisplayMetrics().density;
        float charWidth = view.metrics.getCharWidth();
        float lineHeight = view.metrics.getLineHeight();
        int lineLen = doc.lineEnd(line) - doc.lineStart(line);
        float chipX;
        float y;
        if (view.wordWrap && view.wrapModel != null) {
            // ★ La chip se place
            // après la FIN de la DERNIÈRE rangée repliée (lastSub), pas sur
            // la première ni à la longueur NON repliée.
            EditorView.WrapRows wr = view.wrapRowsFor(line, lineLen);
            int lastRow = wr.rows - 1;
            float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
            if (lastRow == 0) {
                // Rangée unique : les inlays sont tissés → colonne VISUELLE.
                int visualLen = view.visualColFor(line, lineLen);
                chipX = textAreaLeft + visualLen * charWidth
                        + charWidth * EditorView.DIAG_CHIP_GAP_CHARS;
            } else {
                int rowStart = wr.rowStartCol(lastRow);
                int rowEnd = wr.rowEndCol(lastRow, lineLen);
                chipX = textAreaLeft + wr.wrapIndentCols * charWidth
                        + (rowEnd - rowStart) * charWidth
                        + charWidth * EditorView.DIAG_CHIP_GAP_CHARS;
            }
            y = view.docLineToY(line) + lastRow * lineHeight - view.vOffset;
        } else {
            // Longueur visuelle tenant compte des inlays pour que la chip
            // ne recouvre jamais un hint.
            int visualLen = view.visualColFor(line, lineLen);
            chipX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
                    + visualLen * charWidth - view.hOffset
                    + charWidth * EditorView.DIAG_CHIP_GAP_CHARS;
            y = view.docLineToY(line) - view.vOffset;
        }
        if (y + lineHeight < 0 || y > view.getHeight()) return null;
        // Hauteur de pill dimensionnée au contenu (~1.24em)
        // centrée dans la rangée.
        float pillH = view.metrics.getTextSize() * 1.25f;
        float pillY = y + (lineHeight - pillH) * 0.5f;
        // Géométrie du badge de comptage : cercle plein de hauteur pill
        // × 0.82 + écart de 4dp, uniquement quand la ligne porte plus d'un
        // Error/Warning.
        boolean badge = badgeCount > 1;
        float badgeD = badge ? pillH * 0.82f : 0f;
        float badgeGap = badge ? 4 * density : 0f;
        // Message tronqué avec une ellipse pour que la pill tienne dans le
        // viewport.
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
        view.textPaint.setFakeBoldText(true);
        String msg = d.message != null ? d.message : "";
        float padX = 6 * density;
        float iconR = view.metrics.getTextSize() * 0.26f;
        float iconGap = 5 * density;
        float avail = view.getWidth() - chipX - 4 * density
                - padX * 2 - iconR * 2 - iconGap
                - (badge ? badgeD + badgeGap : 0f);
        String label = msg;
        if (label.length() > 90) label = label.substring(0, 88) + "…";
        while (label.length() > 1 && view.textPaint.measureText(label) > avail) {
            label = label.substring(0, label.length() - 2) + "…";
        }
        view.textPaint.setFakeBoldText(false);
        float textW = view.textPaint.measureText(label);
        float pillW = padX + iconR * 2 + iconGap + textW
                + (badge ? badgeGap + badgeD : 0f) + padX;
        if (chipX + pillW < view.metrics.getGutterWidth()) return null; // entièrement sous le gutter
        return new float[]{chipX, pillY, pillW, pillH, iconR, iconGap, padX, badgeD, badgeGap};
    }

    /** Signature historique sans badge — conservée pour les appelants et tests existants. */
    public static float[] diagnosticChipMetrics(EditorView view,
            DiagnosticShift.Diagnostic d, int line) {
        return diagnosticChipMetrics(view, d, line, 0);
    }

    // ════════════════════════════════════════════════════════════════
    // Popup ancré de squiggle (D1)
    // ════════════════════════════════════════════════════════════════

    /** Largeur max du popup ancré de squiggle. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_MAX_WIDTH_DP = 320f;
    /** Écart entre la ligne ancre et le popup. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_GAP_DP = 6f;
    /** Hauteur d'en-tête (point de sévérité + libellé) du popup de détail. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_HEADER_DP = 24f;
    /** Hauteur d'une ligne de message replié (≤ 3 lignes). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_MSG_LINE_DP = 17f;
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SQUIGGLE_POPUP_MAX_MSG_LINES = 3;
    /** Hauteur d'une rangée (quick-fix ou « See all ») du popup de détail. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_ROW_DP = 32f;
    /** Quick-fixes affichés au maximum dans le popup compact. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SQUIGGLE_POPUP_MAX_QUICK_FIX_ROWS = 3;
    /** Hauteur d'une rangée de la mini-liste multi-diagnostics. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_LIST_ROW_DP = 34f;
    /** Rangées maximum de la mini-liste. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final int SQUIGGLE_POPUP_MAX_LIST_ROWS = 6;
    /** Hauteur du micro-libellé « QUICK FIXES ». */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_LABEL_DP = 14f;
    /** Padding horizontal interne du popup. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_PAD_X_DP = 12f;
    /** Padding bas interne du popup. */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_PAD_BOTTOM_DP = 6f;
    /** Largeur MINIMALE du popup (UX-3, J3 — la carte se resserre sur son
     *  contenu mais garde une taille de carte décente : sévérité + une
     *  courte rangée). */
    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public static final float SQUIGGLE_POPUP_MIN_WIDTH_DP = 132f;
    /** Marge de sécurité ajoutée à la largeur mesurée du contenu (la
     *  mesure omet le gras simulé et les métriques varient selon la
     *  fonte). */
    private static final float SQUIGGLE_POPUP_W_SAFETY_DP = 4f;
    /** Largeur réservée au chevron « › » de la rangée « See all ». */
    private static final float SQUIGGLE_POPUP_CHEVRON_DP = 12f;
    /** Indent des rangées (point de sévérité + libellé) et de l'en-tête. */
    private static final float SQUIGGLE_POPUP_INDENT_DP = 12f;

    /**
     * Géométrie partagée du popup ancré de squiggle (D1) — source unique
     * de vérité pour la passe de dessin
     * ({@code EditorDiagnosticsPainter.drawSquigglePopup}) et le hit-test
     * ({@code EditorPopupHitTester.hitSquigglePopup}).
     *
     * <p>Deux modes : <b>mini-liste</b> (plusieurs diagnostics couvrent
     * l'offset tapé — une rangée par diagnostic, {@link #choices}) et
     * <b>détail</b> (un diagnostic — {@link #item}, message replié +
     * rangées de quick-fixes + rangée « See all »). Ancrage : au-dessus de
     * la ligne du diagnostic (flip en dessous si pas de place), X ancré au
     * centre du mot diagnostiqué, clampé dans la zone de texte.</p>
     *
     * <p><b>UX-3 (J3 — retour terrain)</b> : la largeur se resserre sur le
     * CONTENU (elle était TOUJOURS le maximum — un message court ouvrait
     * une carte de 320 dp). Chaque pièce est mesurée avec le paint du
     * painter ; le message n'est candidat que s'il tient SANS repli
     * (sinon il a besoin de la pleine largeur pour se replier) ; les
     * rangées ellipsées plafonnent leur candidature à la largeur
     * disponible. Bornes : [MIN, min(MAX, viewport)].</p>
     */
    public static SquigglePopupMetrics squigglePopupMetrics(EditorView view) {
        if (!view.squigglePopupVisible || view.session == null) return null;
        DiagnosticShift.Diagnostic item = view.squigglePopupItem;
        List<DiagnosticShift.Diagnostic> choices = view.squigglePopupChoices;
        boolean listMode = choices != null && !choices.isEmpty();
        if (!listMode && item == null) return null;
        float density = view.getResources().getDisplayMetrics().density;
        jo.codeeditor.document.EditorDocument doc = view.session.getDocument();

        SquigglePopupMetrics m = new SquigglePopupMetrics();
        m.density = density;
        m.choices = listMode ? choices : java.util.Collections.emptyList();
        m.item = item;
        m.w = Math.min(SQUIGGLE_POPUP_MAX_WIDTH_DP * density,
                view.getWidth() - 2 * SQUIGGLE_POPUP_GAP_DP * density);
        // Largeur de la plus large pièce de contenu (UX-3) — m.w reste
        // le MAXIMUM jusqu'au resserrement final.
        float needed = 0f;

        // ── Contenu : hauteurs + candidates de largeur par mode ────
        float rowH;
        float contentTop;
        if (listMode) {
            m.rows = Math.min(m.choices.size(), SQUIGGLE_POPUP_MAX_LIST_ROWS);
            rowH = SQUIGGLE_POPUP_LIST_ROW_DP * density;
            m.rowH = rowH;
            contentTop = 0f;
            // Chaque rangée : 1re ligne du message, ellipsée par le
            // painter — candidature plafonnée à la place disponible
            // (une rangée longue garde la pleine largeur, une courte
            // resserre la carte).
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            float rowAvail = m.w - 2 * SQUIGGLE_POPUP_PAD_X_DP * density;
            for (int i = 0; i < m.rows; i++) {
                String first = firstLineOf(m.choices.get(i).message);
                needed = Math.max(needed, Math.min(
                        view.textPaint.measureText(first), rowAvail));
            }
        } else {
            int line = doc.lineForOffset(item.start);
            List<EditorView.CodeAction> actions = view.codeActionsByLine.get(line);
            int nActions = actions != null
                    ? Math.min(actions.size(), SQUIGGLE_POPUP_MAX_QUICK_FIX_ROWS) : 0;
            view.textPaint.setTypeface(view.metrics.getTypeface());
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.85f);
            String msg = item.message != null ? item.message : "";
            int msgLines = Math.min(countWrappedLines(view, msg,
                    m.w - 2 * SQUIGGLE_POPUP_PAD_X_DP * density),
                    SQUIGGLE_POPUP_MAX_MSG_LINES);
            // En-tête : point de sévérité + libellé (« Error », « Warning »).
            String sevLabel = item.severity == 3 ? "Error"
                    : item.severity == 2 ? "Warning" : "Info";
            needed = Math.max(needed,
                    SQUIGGLE_POPUP_INDENT_DP * density
                            + view.textPaint.measureText(sevLabel));
            // Le message n'est candidat que s'il tient SANS repli à la
            // largeur max — sinon il a besoin de la PLEINE largeur pour
            // se replier (msgLines a été calculé pour elle).
            float msgW = view.textPaint.measureText(msg);
            if (msgW <= m.w - 2 * SQUIGGLE_POPUP_PAD_X_DP * density) {
                needed = Math.max(needed, msgW);
            } else {
                needed = m.w;
            }
            if (nActions > 0) {
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.7f);
                needed = Math.max(needed, view.textPaint.measureText("QUICK FIXES"));
                view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
                float rowAvail = m.w - 2 * SQUIGGLE_POPUP_PAD_X_DP * density
                        - SQUIGGLE_POPUP_INDENT_DP * density;
                for (int i = 0; i < nActions && i < actions.size(); i++) {
                    String title = actions.get(i).title != null
                            ? actions.get(i).title : "";
                    needed = Math.max(needed, Math.min(
                            view.textPaint.measureText(title), rowAvail));
                }
            }
            rowH = SQUIGGLE_POPUP_ROW_DP * density;
            m.rowH = rowH;
            m.rows = nActions;
            m.seeAll = true;
            m.headerH = SQUIGGLE_POPUP_HEADER_DP * density;
            m.msgLines = Math.max(1, msgLines);
            contentTop = m.headerH + m.msgLines * SQUIGGLE_POPUP_MSG_LINE_DP * density
                    + (nActions > 0 ? SQUIGGLE_POPUP_LABEL_DP * density : 0f);
            // Rangée « See all » + chevron.
            view.textPaint.setTextSize(view.metrics.getTextSize() * 0.9f);
            needed = Math.max(needed, SQUIGGLE_POPUP_INDENT_DP * density
                    + view.textPaint.measureText("See all")
                    + SQUIGGLE_POPUP_CHEVRON_DP * density);
        }
        // ── UX-3 : resserrement de la carte sur son contenu ────────
        m.w = jo.codeeditor.view.EditorView.clamp(
                needed + 2 * SQUIGGLE_POPUP_PAD_X_DP * density
                        + SQUIGGLE_POPUP_W_SAFETY_DP * density,
                SQUIGGLE_POPUP_MIN_WIDTH_DP * density, m.w);
        m.h = contentTop + m.rows * rowH
                + (m.seeAll ? rowH : 0f) + SQUIGGLE_POPUP_PAD_BOTTOM_DP * density;

        // ── Ancrage : au-dessus de la ligne du diagnostic, sinon dessous ──
        DiagnosticShift.Diagnostic anchor = listMode ? m.choices.get(0) : item;
        int anchorLine = doc.lineForOffset(anchor.start);
        float lineTop = view.docLineToY(anchorLine) - view.vOffset;
        float lineBottom = lineTop + view.metrics.getLineHeight();
        float gap = SQUIGGLE_POPUP_GAP_DP * density;

        // X ancré au centre du mot diagnostiqué (clamp zone de texte).
        int lineStart = doc.lineStart(anchorLine);
        int lineLen = doc.lineEnd(anchorLine) - lineStart;
        int startCol = jo.codeeditor.view.EditorView.clamp(anchor.start - lineStart,
                0, lineLen);
        int endCol = jo.codeeditor.view.EditorView.clamp(anchor.end - lineStart,
                0, lineLen);
        if (endCol <= startCol) endCol = startCol + 1;
        float textAreaLeft = view.metrics.getGutterWidth() + view.metrics.getPadLeft();
        float charWidth = view.metrics.getCharWidth();
        float midX = textAreaLeft
                + view.visualColFor(anchorLine, (startCol + endCol) / 2) * charWidth
                - view.hOffset;
        // D2 : pattern « popup ancré » factorisé — préfère AU-DESSUS de la
        // ligne (flip dessous), marge 4 dp, ancre centrée sur le mot,
        // borne gauche = gouttière + 4 dp. Paramétrage identique au
        // calcul historique (motif complétion).
        float[] pos = anchorAboveBelow(view, midX, lineTop, lineBottom,
                m.w, m.h, true, gap, 4 * density,
                4 * density, 4 * density, true,
                view.metrics.getGutterWidth() + 4 * density);
        m.x = pos[0];
        m.y = pos[1];

        m.rowTop = m.y + contentTop;
        m.seeAllY = m.rowTop + m.rows * rowH;
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
        return m;
    }

    /** La première ligne d'un message (avant tout \n), sinon vide — même
     *  règle que le painter de la mini-liste (mesure de candidature UX-3). */
    private static String firstLineOf(String message) {
        if (message == null || message.isEmpty()) return "";
        int nl = message.indexOf('\n');
        return nl >= 0 ? message.substring(0, nl) : message;
    }

    /**
     * Géométrie du popup ancré de squiggle : le panneau + les rangées
     * actionnables (quick-fixes en mode détail, diagnostics en mode
     * liste) + la rangée « See all » (mode détail uniquement). Hit-test :
     * {@link #hit(float, float)} → -1 hors panneau, 0..{@code rows}-1 =
     * rangée, {@code rows} = « See all » (si présent).
     */
    public static final class SquigglePopupMetrics {
        /** Le diagnostic détaillé (mode détail, primaire en mode liste). */
        public DiagnosticShift.Diagnostic item;
        /** Les rangées de la mini-liste (vide en mode détail). */
        public List<DiagnosticShift.Diagnostic> choices;
        public float x, y, w, h;
        public float rowH;
        /** Y du HAUT de la première rangée actionnable. */
        public float rowTop;
        /** Nombre de rangées actionnables (quick-fixes ou choix). */
        public int rows;
        /** Rangée « See all » présente (mode détail). */
        public boolean seeAll;
        /** Y du HAUT de la rangée « See all ». */
        public float seeAllY;
        /** Hauteur d'en-tête (mode détail). */
        public float headerH;
        /** Lignes de message replié (mode détail). */
        public int msgLines;
        /** Densité écran (dessin). */
        public float density;

        /** Vrai en mode mini-liste multi-diagnostics. */
        public boolean isListMode() {
            return choices != null && !choices.isEmpty();
        }

        /**
         * Le hit à (px, py) : -1 hors panneau, l'index de rangée
         * (0..rows-1), ou {@code rows} pour la rangée « See all »
         * (sentinelle — uniquement présente en mode détail).
         */
        public int hit(float px, float py) {
            if (px < x || px > x + w || py < y || py > y + h) return -1;
            if (seeAll && py >= seeAllY && py < seeAllY + rowH) return rows;
            if (py < rowTop) return -1;
            int row = (int) ((py - rowTop) / rowH);
            if (row < 0 || row >= rows) return -1;
            return row;
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Toolbar de sélection
    // ════════════════════════════════════════════════════════════════

    /**
     * Géométrie partagée + liste d'actions de la toolbar flottante de
     * sélection. SOURCE UNIQUE de vérité pour le rendu ET le hit-test.
     *
     * <p>T1 (lot 2) : les actions injectées par l'hôte
     * ({@code view.popupManager.selActions.actions}) s'insèrent ENTRE les
     * canoniques d'édition et les icônes Docs/Actions, uniquement avec
     * une vraie sélection ; celles qui ne tiennent pas dans la largeur de
     * la vue peuplent la liste de débordement {@code m.overflow}.</p>
     *
     * <p>UX-1 (lot 2, J3 — retour terrain) : la pill ne porte PLUS d'item
     * « ⋯ » de débordement — elle affichait DEUX boutons à trois points
     * (le débordement T1 + l'icône Actions du lot 1). L'icône Actions
     * « ⋯ » en fin de pill est l'UNIQUE débordement : elle ouvre le menu
     * contextuel unifié dont la section « MORE » liste les actions non
     * affichées (la liste reste peuplée ICI — la métrique demeure la
     * source unique ; le menu lit
     * {@code view.popupManager.selActions.overflow}).</p>
     */
    public static SelectionToolbarMetrics selectionToolbarMetrics(EditorView view) {
        if (!view.selectionToolbarVisible || view.session == null) return null;
        jo.codeeditor.document.Selection sel = view.session.getSelection();
        float density = view.getResources().getDisplayMetrics().density;

        // T4 (lot 2, J5) — ancre = borne EN DÉPLACEMENT pendant le drag
        // (poignée ou drag-select au doigt : la pill suit le geste),
        // extrémité finale au repos. La sélection étant NORMALISÉE
        // (start ≤ end), l'ancrage brut sur sel.end collait la pill à
        // la borne DROITE même pendant le drag de la poignée GAUCHE ;
        // l'état du geste (borne mobile = celle qui n'est pas l'ancre
        // figée) vit dans EditorSelectionGestures, via le relais
        // view.selectionToolbarAnchorOffset().
        int anchorOffset = view.selectionToolbarAnchorOffset();
        float[] anchor = view.caretScreenPos(anchorOffset >= 0 ? anchorOffset : sel.end);

        boolean hasSelection = !sel.isCursor();

        // ── Construction de la liste d'actions ──────────────────────
        view.textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        view.textPaint.setTextSize(13 * density);
        SelectionToolbarMetrics m = new SelectionToolbarMetrics();
        m.padX = 12 * density;
        m.padY = 8 * density;
        m.btnGap = 2 * density;
        m.h = 14 * density + 2 * m.padY;
        m.radius = m.h * 0.5f;
        float iconW = 32 * density; // icône 16dp + padding 2×8dp

        if (hasSelection) {
            m.addText(EditorView.SEL_ACT_COPY, "Copy", view.textPaint.measureText("Copy"));
            m.addText(EditorView.SEL_ACT_CUT, "Cut", view.textPaint.measureText("Cut"));
        }
        m.addText(EditorView.SEL_ACT_PASTE, "Paste", view.textPaint.measureText("Paste"));
        m.addText(EditorView.SEL_ACT_SELECT_ALL, "Select all",
                view.textPaint.measureText("Select all"));

        // ── T1 : actions injectées par l'hôte (fit par la largeur) ──
        java.util.List<jo.codeeditor.view.SelectionAction> hostActions =
                view.popupManager.selActions.actions;
        int textCount = m.count;
        if (hasSelection && !hostActions.isEmpty()) {
            // Largeur des canoniques (textes + divider + icônes + gaps).
            float base = 0;
            for (int i = 0; i < textCount; i++) base += m.itemW[i] + m.btnGap;
            base += 6 * density + iconW * 2 + 3 * m.btnGap;
            float avail = view.getWidth() - 8 * density - base;
            int n = hostActions.size();
            int shown = 0;
            float used = 0;
            for (SelectionAction sa : hostActions) {
                if (sa == null) continue;
                float textW = view.textPaint.measureText(sa.label);
                float w = textW + 2 * m.padX;
                // UX-1 : plus de réservation de place pour un item « ⋯ »
                // (supprimé) — les injectées occupent toute la place
                // disponible ; celles qui débordent peuplent la section
                // « MORE » du menu unifié (via l'icône Actions).
                if (used + w > avail) break;
                m.addInjected(sa, textW);
                used += w + m.btnGap;
                shown++;
            }
            // Le débordement alimente la section « MORE » du menu
            // contextuel unifié (ouvert par l'icône Actions « ⋯ »).
            view.popupManager.selActions.overflow.clear();
            if (shown < n) {
                for (int i = shown; i < hostActions.size(); i++) {
                    if (hostActions.get(i) != null) {
                        view.popupManager.selActions.overflow.add(hostActions.get(i));
                    }
                }
            }
            for (SelectionAction sa : view.popupManager.selActions.overflow) {
                m.overflow.add(sa);
            }
        } else {
            view.popupManager.selActions.overflow.clear();
        }

        m.addIcon(EditorView.SEL_ACT_DOCS);
        m.addIcon(EditorView.SEL_ACT_ACTIONS);
        m.dividerCount = 1;
        float dividerW = 6 * density;

        // ── Layout provisionnel (une passe) + largeur totale ────────
        m.applyIconWidth(iconW);
        float cursor = 0;
        for (int i = 0; i < m.count; i++) {
            m.itemX[i] = cursor;
            cursor += m.itemW[i];
            boolean dividerBefore = m.dividerCount > 0 && m.isIcon[i]
                    && i > 0 && !m.isIcon[i - 1];
            cursor += (i == m.count - 1) ? 0 : (dividerBefore ? dividerW : m.btnGap);
        }
        float totalW = cursor;
        if (m.dividerCount > 0) {
            m.dividerX = m.firstIconX() - dividerW * 0.5f;
        }

        // ── Position de la pill (ancre centrée, clamp viewport) ─────
        m.w = totalW;
        m.x = anchor[0] - totalW * 0.5f;
        if (m.x < 4) m.x = 4;
        if (m.x + totalW > view.getWidth() - 4) m.x = view.getWidth() - totalW - 4;
        if (m.x < 0) m.x = 0; // viewport plus étroit que la pill
        m.y = anchor[1] - m.h - 6 * density;
        if (m.y < 4) {
            // Pas de place au-dessus — bascule SOUS la ligne.
            m.y = anchor[1] + view.metrics.getLineHeight() + 6 * density;
        }
        // Décalage du layout provisionnel vers la position finale.
        for (int i = 0; i < m.count; i++) {
            m.itemX[i] += m.x;
        }
        m.dividerX += m.x;
        view.textPaint.setTypeface(view.metrics.getTypeface());
        view.textPaint.setTextSize(view.metrics.getTextSize());
        return m;
    }

    /**
     * Géométrie de la toolbar de sélection : la pill + les items actionnables
     * (texte ou icône) + les positions des dividers. Consommée par le rendu et
     * le hit-test (une seule source de layout).
     *
     * <p>Tableaux dynamiques (T1) : les actions injectées peuvent
     * dépasser l'ancienne capacité fixe de 8 — la largeur de vue est
     * désormais le seul plafond d'affichage. {@code injected[i]}
     * référence l'action d'hôte d'un item injecté ;
     * {@code itemDisabled[i]} porte l'estompage ; {@code overflow}
     * liste les actions non affichées (section « MORE » du menu unifié,
     * atteinte par l'icône Actions « ⋯ » — plus d'item dédié depuis
     * UX-1).</p>
     */
    public static final class SelectionToolbarMetrics {
        public float x, y;                       // coin haut-gauche de la pill
        public float w, h;                       // dimensions de la pill
        public float radius;                     // rayon (pill complète)
        public float padX, padY, btnGap;
        public int count;                        // items actionnables
        public float[] itemX = new float[8];
        public float[] itemW = new float[8];
        public int[] action = new int[8];
        public String[] label = new String[8]; // null pour un item icône
        public boolean[] isIcon = new boolean[8];
        /** L'action d'hôte injectée de l'item (null sinon). */
        public jo.codeeditor.view.SelectionAction[] injected =
                new jo.codeeditor.view.SelectionAction[8];
        /** Item estompé (action désactivée) — tap rejeté. */
        public boolean[] itemDisabled = new boolean[8];
        /** Actions non affichées (→ section « MORE » du menu unifié, via
         *  l'icône Actions « ⋯ » — UX-1). */
        public final java.util.List<jo.codeeditor.view.SelectionAction> overflow =
                new java.util.ArrayList<>();
        public float dividerX = Float.MIN_VALUE;  // 1 divider max (avant les icônes)
        public int dividerCount;

        void addText(int act, String text, float width) {
            ensure(count + 1);
            itemX[count] = -1; // rempli par la passe de layout
            itemW[count] = width + 2 * padX;
            action[count] = act;
            label[count] = text;
            isIcon[count] = false;
            count++;
        }

        void addIcon(int act) {
            ensure(count + 1);
            itemX[count] = -1;
            itemW[count] = 0; // rempli par applyIconWidth(iconW)
            action[count] = act;
            label[count] = null;
            isIcon[count] = true;
            count++;
        }

        /** Item injecté par l'hôte (T1) : libellé + référence + drapeau
         *  d'estompage si l'action est désactivée. */
        void addInjected(jo.codeeditor.view.SelectionAction sa, float textWidth) {
            ensure(count + 1);
            itemX[count] = -1;
            itemW[count] = textWidth + 2 * padX;
            action[count] = jo.codeeditor.view.EditorSelectionActions.SEL_ACT_INJECTED;
            label[count] = sa.label;
            isIcon[count] = false;
            injected[count] = sa;
            itemDisabled[count] = !sa.isEnabled();
            count++;
        }

        /** Croissance des tableaux (capacité 8 d'origine — T1). */
        private void ensure(int cap) {
            if (cap <= itemX.length) return;
            int n = Math.max(cap, itemX.length * 2);
            itemX = java.util.Arrays.copyOf(itemX, n);
            itemW = java.util.Arrays.copyOf(itemW, n);
            action = java.util.Arrays.copyOf(action, n);
            label = java.util.Arrays.copyOf(label, n);
            isIcon = java.util.Arrays.copyOf(isIcon, n);
            injected = java.util.Arrays.copyOf(injected, n);
            itemDisabled = java.util.Arrays.copyOf(itemDisabled, n);
        }

        /** La position (provisionnelle) du premier item icône. */
        float firstIconX() {
            for (int i = 0; i < count; i++) {
                if (isIcon[i]) return itemX[i];
            }
            return Float.MIN_VALUE;
        }

        /** Applique la largeur réelle des items icônes. */
        void applyIconWidth(float iconW) {
            for (int i = 0; i < count; i++) {
                if (isIcon[i]) itemW[i] = iconW;
            }
        }

        /** Vrai si (x, y) est dans la pill (le geste y est englouti). */
        public boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        /**
         * L'INDEX d'item à (px, py), ou -1. Les dividers et les gaps entre
         * items ne sont pas actionnables.
         */
        public int itemIndexAt(float px, float py) {
            if (!contains(px, py)) return -1;
            for (int i = 0; i < count; i++) {
                if (px >= itemX[i] && px < itemX[i] + itemW[i]) return i;
            }
            return -1;
        }

        /** L'action à (px, py), ou -1 (délègue à {@link #itemIndexAt}). */
        public int actionAt(float px, float py) {
            int i = itemIndexAt(px, py);
            return i < 0 ? -1 : action[i];
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Menu contextuel unifié (NavMenu)
    // ════════════════════════════════════════════════════════════════

    /** Géométrie du menu contextuel unifié : {@code [x, y, w, h]}. */
    public static float[] navMenuMetrics(EditorView view) {
        if (!view.navMenuVisible || view.navMenuLine < 0) return null;
        float density = view.getResources().getDisplayMetrics().density;
        float contentH = view.navMenuContentHeight();
        float maxH = EditorView.NAV_MENU_MAX_HEIGHT_DP * density;
        float h = Math.min(contentH, maxH);
        float w = Math.min(EditorView.NAV_MENU_MAX_WIDTH_DP * density,
                Math.max(EditorView.NAV_MENU_MIN_WIDTH_DP * density,
                        (view.getWidth() - 2 * EditorView.NAV_MENU_MARGIN_DP * density) * 0.9f));
        // Ancre : X du caret (clampé ≥ gutter + marge), bas de la ligne.
        float[] caret = view.caretScreenPos(view.navMenuCaretOffset);
        float anchorX = Math.max(
                view.metrics.getGutterWidth() + EditorView.NAV_MENU_MARGIN_DP * density,
                caret[0]);
        float lineTop = view.docLineToY(view.navMenuLine) - view.vOffset;
        float lineBottom = lineTop + view.metrics.getLineHeight();
        // D2 : pattern « popup ancré » factorisé — préfère SOUS la ligne,
        // bascule AU-DESSUS (jamais sur la ligne d'ancre), marges
        // NAV_MENU partout, ancre pré-décalée de la marge (alignée à
        // gauche). Paramétrage identique au calcul historique.
        float[] pos = anchorAboveBelow(view,
                anchorX - EditorView.NAV_MENU_MARGIN_DP * density,
                lineTop, lineBottom, w, h,
                false, EditorView.NAV_MENU_GAP_DP * density,
                EditorView.NAV_MENU_MARGIN_DP * density,
                EditorView.NAV_MENU_MARGIN_DP * density,
                EditorView.NAV_MENU_MARGIN_DP * density,
                false, EditorView.NAV_MENU_MARGIN_DP * density);
        return new float[]{pos[0], pos[1], w, h};
    }

    // ════════════════════════════════════════════════════════════════
    // Icônes de toolbar (A+, A−, ¶, lig)
    // ════════════════════════════════════════════════════════════════

    /**
     * Test de toucher des icônes de toolbar (A+, A-, ¶, lig) en haut à
     * droite. Retourne : 0=aucun toucher, 1=A+, 2=A-, 3=¶ (non
     * imprimables), 4=lig (ligatures).
     */
    public static int hitTestToolbarIcons(EditorView view, float x, float y) {
        float density = view.getResources().getDisplayMetrics().density;
        float iconSize = EditorPreviewController.PREVIEW_ICON_SIZE_DP * density;
        float margin = EditorPreviewController.PREVIEW_ICON_MARGIN_DP * density;
        float iconY = margin;
        // Disposition de droite à gauche : [lig] [¶] [A-] [A+]
        float startX = view.getWidth() - margin;
        float ligX = startX - iconSize;
        float npX = ligX - iconSize - margin * 0.5f;
        float aMinusX = npX - iconSize - margin * 0.5f;
        float aPlusX = aMinusX - iconSize - margin * 0.5f;
        if (y >= iconY && y <= iconY + iconSize) {
            if (x >= aPlusX && x <= aPlusX + iconSize) return 1;
            if (x >= aMinusX && x <= aMinusX + iconSize) return 2;
            if (x >= npX && x <= npX + iconSize) return 3;
            if (x >= ligX && x <= ligX + iconSize) return 4;
        }
        return 0;
    }
}
