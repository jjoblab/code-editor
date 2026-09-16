package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;
import jo.codeeditor.shift.DiagnosticShift;

import java.util.List;

/**
 * v3.36.0 — Popup anchor geometry extracted from EditorView (roadmap item
 * 11: "poursuite du démantèlement d'EditorView — extraire
 * metrics/scroll-state/popup-anchors vers les managers").
 *
 * <p>This class owns every PURE GEOMETRY computation for the editor's
 * canvas-drawn popups — the shared "single source of truth" consumed by
 * BOTH the draw pass ({@code EditorRenderer}) and the tap hit-tests
 * ({@code EditorInputHandler}):</p>
 * <ul>
 *   <li>{@link #diagnosticSheetMetrics(EditorView)} — the per-diagnostic
 *       detail sheet (v2.31);</li>
 *   <li>{@link #diagnosticListSheetMetrics(EditorView)} — the grouped
 *       per-line sheet (v3.36.0, roadmap item 5);</li>
 *   <li>{@link #diagnosticChipMetrics(EditorView, DiagnosticShift.Diagnostic, int, int)}
 *       — the diagnostic chip pill (+ count badge);</li>
 *   <li>{@link #selectionToolbarMetrics(EditorView)} — the floating
 *       selection toolbar (v2.34);</li>
 *   <li>{@link #navMenuMetrics(EditorView)} — the unified context menu
 *       (v2.36);</li>
 *   <li>{@link #countWrappedLines(EditorView, String, float)} — shared
 *       word-wrap line counter.</li>
 * </ul>
 *
 * <p>EditorView keeps thin delegating wrappers with the historical
 * signatures (renderer, input handler and tests are unchanged); the
 * bodies now live here. All methods are static and read the view's
 * package-private state through the {@code view} reference — same access
 * pattern as the other v3.x manager classes.</p>
 *
 * @since v3.36.0
 */
final class EditorPopupAnchors {

    private EditorPopupAnchors() {}

    // ════════════════════════════════════════════════════════════════
    // Shared word-wrap counter
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.31: Word-wraps {@code msg} with the diagnostic-sheet text paint and
     * returns the number of visual lines (min 1). Used by BOTH the draw pass
     * and the hit-test so they always agree on the panel height.
     */
    static int countWrappedLines(EditorView view, String msg, float maxW) {
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
    // Diagnostic detail sheet (v2.31)
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.31: Shared geometry of the diagnostic sheet (the CodeAssist
     * DiagnosticSheet port). Returns {@code null} when nothing is showing,
     * else {@code [panelTop, panelBottom, actionStartY, actionRowH,
     * closeCx, closeCy, closeR]}.
     */
    static float[] diagnosticSheetMetrics(EditorView view) {
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
    // Grouped diagnostic list sheet (v3.36.0, roadmap item 5)
    // ════════════════════════════════════════════════════════════════

    /**
     * v3.36.0 (roadmap item 5): shared geometry of the GROUPED diagnostic
     * sheet. Returns null when hidden or its line has no diagnostics left,
     * else {@code [panelTop, panelBottom, headerH, rowH, rowCount, moreRow,
     * closeCx, closeCy, closeR]}.
     */
    static float[] diagnosticListSheetMetrics(EditorView view) {
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
    // Diagnostic chip (v2.31 + v3.36.0 count badge)
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.31: The diagnostic CHIP pill geometry for {@code d} on {@code line}
     * (CodeAssist DiagnosticChip port). Returns {@code [x, y, w, h, iconR,
     * iconGap, padX, badgeD, badgeGap]} or null when the pill would be fully
     * off-screen. Single source of truth for the draw pass and the tap
     * hit-test.
     *
     * <p>v3.36.0 (roadmap item 5): {@code badgeCount > 1} appends a count
     * badge at the right end of the pill — its width is included in
     * {@code w} so the hit-test matches what is drawn.</p>
     */
    static float[] diagnosticChipMetrics(EditorView view,
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
            // ★ v2.33 — CodeAssist DiagnosticChipsLayer : la chip se place
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
            // Inlay-aware visual line length so the chip never covers a hint.
            int visualLen = view.visualColFor(line, lineLen);
            chipX = view.metrics.getGutterWidth() + view.metrics.getPadLeft()
                    + visualLen * charWidth - view.hOffset
                    + charWidth * EditorView.DIAG_CHIP_GAP_CHARS;
            y = view.docLineToY(line) - view.vOffset;
        }
        if (y + lineHeight < 0 || y > view.getHeight()) return null;
        // Content-sized pill height (~1.24em, CodeAssist) centred in the row.
        float pillH = view.metrics.getTextSize() * 1.25f;
        float pillY = y + (lineHeight - pillH) * 0.5f;
        // v3.36.0 — count badge geometry (roadmap item 5): filled circle of
        // pill height × 0.82 + 4dp gap, only when the line carries more
        // than one Error/Warning.
        boolean badge = badgeCount > 1;
        float badgeD = badge ? pillH * 0.82f : 0f;
        float badgeGap = badge ? 4 * density : 0f;
        // Message truncated with an ellipsis so the pill fits the viewport.
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
        if (chipX + pillW < view.metrics.getGutterWidth()) return null; // fully under the gutter
        return new float[]{chipX, pillY, pillW, pillH, iconR, iconGap, padX, badgeD, badgeGap};
    }

    /** v2.31 signature (no badge) — kept for existing callers and tests. */
    static float[] diagnosticChipMetrics(EditorView view,
            DiagnosticShift.Diagnostic d, int line) {
        return diagnosticChipMetrics(view, d, line, 0);
    }

    // ════════════════════════════════════════════════════════════════
    // Selection toolbar (v2.34)
    // ════════════════════════════════════════════════════════════════

    /**
     * v2.34 — géométrie partagée + liste d'actions de la toolbar flottante
     * de sélection (portage {@code SelectionToolbar} de CodeAssist). SOURCE
     * UNIQUE de vérité pour le rendu ET le hit-test.
     */
    static SelectionToolbarMetrics selectionToolbarMetrics(EditorView view) {
        if (!view.selectionToolbarVisible || view.session == null) return null;
        jo.codeeditor.document.Selection sel = view.session.getSelection();
        float density = view.getResources().getDisplayMetrics().density;

        // Ancre = extrémité ACTIVE de la sélection (le bout qui suit le
        // doigt — parité CodeAssist geometry.caretGeometry(selActive)).
        float[] anchor = view.caretScreenPos(sel.end);

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
        float iconW = 32 * density; // 16dp icon + 2×8dp padding

        if (hasSelection) {
            m.addText(EditorView.SEL_ACT_COPY, "Copy", view.textPaint.measureText("Copy"));
            m.addText(EditorView.SEL_ACT_CUT, "Cut", view.textPaint.measureText("Cut"));
        }
        m.addText(EditorView.SEL_ACT_PASTE, "Paste", view.textPaint.measureText("Paste"));
        m.addText(EditorView.SEL_ACT_SELECT_ALL, "Select all",
                view.textPaint.measureText("Select all"));
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
     * v2.34 — géométrie de la toolbar de sélection : la pill + les items
     * actionnables (texte ou icône) + les positions des dividers. Consommée
     * par le rendu et le hit-test (une seule source de layout).
     */
    static final class SelectionToolbarMetrics {
        float x, y;                       // coin haut-gauche de la pill
        float w, h;                       // dimensions de la pill
        float radius;                     // rayon (pill complète)
        float padX, padY, btnGap;
        int count;                        // items actionnables
        final float[] itemX = new float[8];
        final float[] itemW = new float[8];
        final int[] action = new int[8];
        final String[] label = new String[8]; // null pour un item icône
        final boolean[] isIcon = new boolean[8];
        float dividerX = Float.MIN_VALUE;  // 1 divider max (avant les icônes)
        int dividerCount;

        void addText(int act, String text, float width) {
            itemX[count] = -1; // rempli par la passe de layout
            itemW[count] = width + 2 * padX;
            action[count] = act;
            label[count] = text;
            isIcon[count] = false;
            count++;
        }

        void addIcon(int act) {
            itemX[count] = -1;
            itemW[count] = 0; // rempli par applyIconWidth(iconW)
            action[count] = act;
            label[count] = null;
            isIcon[count] = true;
            count++;
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

        /** True si (x, y) est dans la pill (le geste y est englouti). */
        boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }

        /**
         * L'INDEX d'item à (px, py), ou -1. Les dividers et les gaps entre
         * items ne sont pas actionnables (parité CodeAssist).
         */
        int itemIndexAt(float px, float py) {
            if (!contains(px, py)) return -1;
            for (int i = 0; i < count; i++) {
                if (px >= itemX[i] && px < itemX[i] + itemW[i]) return i;
            }
            return -1;
        }

        /** L'action à (px, py), ou -1 (délègue à {@link #itemIndexAt}). */
        int actionAt(float px, float py) {
            int i = itemIndexAt(px, py);
            return i < 0 ? -1 : action[i];
        }
    }

    // ════════════════════════════════════════════════════════════════
    // NavMenu (v2.36)
    // ════════════════════════════════════════════════════════════════

    /** v2.36 — géométrie du menu contextuel unifié : {@code [x, y, w, h]}. */
    static float[] navMenuMetrics(EditorView view) {
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
        float anchorY = view.docLineToY(view.navMenuLine) - view.vOffset
                + view.metrics.getLineHeight() + EditorView.NAV_MENU_GAP_DP * density;
        float x = anchorX - EditorView.NAV_MENU_MARGIN_DP * density;
        if (x + w > view.getWidth() - EditorView.NAV_MENU_MARGIN_DP * density) {
            x = view.getWidth() - EditorView.NAV_MENU_MARGIN_DP * density - w;
        }
        if (x < EditorView.NAV_MENU_MARGIN_DP * density) x = EditorView.NAV_MENU_MARGIN_DP * density;
        float y = anchorY;
        if (y + h > view.getHeight() - EditorView.NAV_MENU_MARGIN_DP * density) {
            // Pas de place en dessous — bascule AU-DESSUS de la ligne.
            y = view.docLineToY(view.navMenuLine) - view.vOffset - h
                    - EditorView.NAV_MENU_GAP_DP * density;
        }
        if (y < EditorView.NAV_MENU_MARGIN_DP * density) y = EditorView.NAV_MENU_MARGIN_DP * density;
        return new float[]{x, y, w, h};
    }
}
