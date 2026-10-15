package jo.codeeditor.view.chrome;

import jo.codeeditor.find.FindReplace;
import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.icons.EditorIcons;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Barre de recherche/remplacement DANS LE FICHIER (B10) — la vue de
 * {@link EditorSearchController}, dans le registre visuel de
 * {@link BreadcrumbBar} : fond et bordure teintés du thème de l'éditeur
 * lié, boutons pill (fond arrondi à l'appui, accent quand ACTIF),
 * cibles tactiles de 40 dp.
 *
 * <p><b>Structure</b> (trois rangées) :
 * <ul>
 *   <li>1 — {@link EditorIcons.Icon#SEARCH} + champ de requête + compte
 *       « 3/17 » + occurrence précédente/suivante + fermer ;</li>
 *   <li>2 — les trois bascules du moteur : « Aa » respecter la casse,
 *       « ab » mot entier, « .* » expression régulière (accent = actif),
 *       et la bascule du mode remplacer à droite ;</li>
 *   <li>3 — mode remplacer : champ de remplacement + « Remplacer »
 *       (l'occurrence courante, Enter) + « Tout » (une seule entrée
 *       d'annulation).</li>
 * </ul></p>
 *
 * <p><b>Intégration</b> — l'hôte pose la barre dans sa hiérarchie
 * (au-dessus de l'éditeur), l'ouvre par {@link #open()} et la ferme par
 * {@link #close()} (le ✕ fait de même). {@code Ctrl+F} de l'éditeur
 * route déjà vers {@code EditorView.OnFindRequestedListener} : le
 * listener de sélection de l'hôte n'a qu'à implémenter l'interface et
 * appeler {@link #open()}. Après un changement de session
 * (fichier/onglet), appeler {@link #refresh()} — le contrôleur
 * ré-accroche la session courante et recalcule les occurrences.</p>
 *
 * <p>Champ de requête : Enter = occurrence suivante (action clavier
 * « Rechercher »), Échap (clavier physique) referme. La requête
 * s'amorce depuis la sélection courante quand elle tient sur une ligne.
 * Le surlignage du viewport vit dans l'éditeur
 * ({@code setFindHighlights}) — rien à dessiner ici.</p>
 */
public class EditorSearchBar extends LinearLayout {

    /** Hauteur d'une rangée (dp) — cible tactile confortable. */
    static final float ROW_HEIGHT_DP = 40f;
    /** Taille du glyphe des boutons-icônes (dp). */
    static final float ICON_SIZE_DP = 18f;
    /** Taille du texte des bascules/champs (sp). */
    static final float TEXT_SIZE_SP = 13f;

    private final float density;
    private EditorView editorView;
    private EditorSearchController controller;

    private EditText queryField;
    private EditText replaceField;
    private TextView countView;
    private LinearLayout replaceRow;
    private BarButton caseChip;
    private BarButton wordChip;
    private BarButton regexChip;
    private BarButton replaceToggle;

    private final Paint bgPaint = new Paint();
    private final Paint borderPaint = new Paint();
    /** Garde : amorçage programmatique des champs sans re-déclencher. */
    private boolean settingFieldText = false;

    public EditorSearchBar(Context context) {
        this(context, null);
    }

    public EditorSearchBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setVisibility(GONE);
        density = getResources().getDisplayMetrics().density;
        buildRows();
    }

    // ════════════════════════════════════════════════════════════════
    // Construction
    // ════════════════════════════════════════════════════════════════

    private void buildRows() {
        Context ctx = getContext();

        // ── Rangée 1 : requête + compte + navigation + fermer ─────────
        LinearLayout row1 = new LinearLayout(ctx);
        row1.setOrientation(HORIZONTAL);

        BarButton searchGlyph = new BarButton(ctx, null,
                EditorIcons.Icon.SEARCH, null, null, null);
        searchGlyph.setEnabled(false);
        row1.addView(searchGlyph, rowParams(0));

        queryField = newField(ctx, jo.codeeditor.ui.R.string.cel_find_hint);
        queryField.setImeOptions(EditorInfo.IME_ACTION_SEARCH
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        queryField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                if (controller != null) controller.next();
                return true;
            }
            return false;
        });
        queryField.setOnKeyListener((v, keyCode, event) -> {
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                    && keyCode == KeyEvent.KEYCODE_ESCAPE) {
                close();
                return true;
            }
            return false;
        });
        queryField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (settingFieldText || controller == null) return;
                controller.setQuery(s.toString());
                updateCount(0, -1);
            }
        });
        row1.addView(queryField, rowParams(1f));

        countView = new TextView(ctx);
        countView.setTypeface(Typeface.MONOSPACE);
        countView.setTextSize(TEXT_SIZE_SP);
        countView.setPadding((int) (8 * density), 0, (int) (8 * density), 0);
        row1.addView(countView, rowParams(0));

        row1.addView(new BarButton(ctx, null,
                EditorIcons.Icon.CHEVRON_UP,
                getString(jo.codeeditor.ui.R.string.cel_find_prev),
                v -> { if (controller != null) controller.prev(); }, null),
                rowParams(0));
        row1.addView(new BarButton(ctx, null,
                EditorIcons.Icon.CHEVRON_DOWN,
                getString(jo.codeeditor.ui.R.string.cel_find_next),
                v -> { if (controller != null) controller.next(); }, null),
                rowParams(0));
        row1.addView(new BarButton(ctx, null,
                EditorIcons.Icon.CLOSE,
                getString(jo.codeeditor.ui.R.string.cel_find_close),
                v -> close(), null),
                rowParams(0));
        addView(row1, new LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx()));

        // ── Rangée 2 : bascules du moteur + mode remplacer ────────────
        LinearLayout row2 = new LinearLayout(ctx);
        row2.setOrientation(HORIZONTAL);

        caseChip = new BarButton(ctx, "Aa", null,
                getString(jo.codeeditor.ui.R.string.cel_find_case),
                v -> {
                    if (controller == null) return;
                    controller.setCaseSensitive(!controller.getOptions().caseSensitive);
                    syncChips();
                }, () -> controller != null
                        && controller.getOptions().caseSensitive);
        wordChip = new BarButton(ctx, "ab", null,
                getString(jo.codeeditor.ui.R.string.cel_find_word),
                v -> {
                    if (controller == null) return;
                    controller.setWholeWord(!controller.getOptions().wholeWord);
                    syncChips();
                }, () -> controller != null
                        && controller.getOptions().wholeWord);
        regexChip = new BarButton(ctx, ".*", null,
                getString(jo.codeeditor.ui.R.string.cel_find_regex),
                v -> {
                    if (controller == null) return;
                    controller.setRegex(!controller.getOptions().regex);
                    syncChips();
                }, () -> controller != null
                        && controller.getOptions().regex);
        replaceToggle = new BarButton(ctx, null,
                EditorIcons.Icon.SWAP_VERT,
                getString(jo.codeeditor.ui.R.string.cel_find_toggle_replace),
                v -> {
                    if (controller == null) return;
                    controller.setReplaceMode(!controller.isReplaceMode());
                    syncReplaceRow();
                }, () -> controller != null && controller.isReplaceMode());

        row2.addView(caseChip, rowParams(0));
        row2.addView(wordChip, rowParams(0));
        row2.addView(regexChip, rowParams(0));
        // Pousse la bascule remplacer à droite.
        View spacer = new View(ctx);
        row2.addView(spacer, rowParams(1f));
        row2.addView(replaceToggle, rowParams(0));
        addView(row2, new LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx()));

        // ── Rangée 3 : remplacement (mode remplacer) ─────────────────
        replaceRow = new LinearLayout(ctx);
        replaceRow.setOrientation(HORIZONTAL);

        replaceField = newField(ctx, jo.codeeditor.ui.R.string.cel_find_replace_hint);
        replaceField.setImeOptions(EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        replaceField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                performReplaceCurrent();
                return true;
            }
            return false;
        });
        replaceRow.addView(replaceField, rowParams(1f));

        replaceRow.addView(new BarButton(ctx,
                getString(jo.codeeditor.ui.R.string.cel_find_replace_one), null,
                getString(jo.codeeditor.ui.R.string.cel_find_replace_one),
                v -> performReplaceCurrent(), null),
                rowParams(0));
        replaceRow.addView(new BarButton(ctx,
                getString(jo.codeeditor.ui.R.string.cel_find_replace_all), null,
                getString(jo.codeeditor.ui.R.string.cel_find_replace_all),
                v -> {
                    if (controller == null) return;
                    controller.replaceAll(replaceField.getText().toString());
                }, null),
                rowParams(0));
        addView(replaceRow, new LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx()));
        replaceRow.setVisibility(GONE);
    }

    /**
     * Paramètres de position dans une rangée : largeur RÉELLE (poids 0)
     * ou part restante (poids 1) — hauteur de la rangée dans les deux
     * cas (cible tactile).
     */
    private LinearLayout.LayoutParams rowParams(float weight) {
        return weight > 0f
                ? new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
                : new LinearLayout.LayoutParams(
                        LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
    }

    private int rowHeightPx() {
        return (int) (ROW_HEIGHT_DP * density);
    }

    /** Champ de saisie monospace au style de la barre (fond arrondi). */
    private EditText newField(Context ctx, int hintRes) {
        EditText field = new EditText(ctx);
        field.setTypeface(Typeface.MONOSPACE);
        field.setTextSize(TEXT_SIZE_SP);
        field.setSingleLine(true);
        field.setHint(hintRes);
        field.setPadding((int) (10 * density), 0, (int) (10 * density), 0);
        field.setBackground(makeFieldBackground());
        return field;
    }

    private GradientDrawable makeFieldBackground() {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(10 * density);
        return bg;
    }

    private String getString(int res) {
        return getContext().getString(res);
    }

    private void performReplaceCurrent() {
        if (controller == null) return;
        controller.replaceCurrent(replaceField.getText().toString());
    }

    // ════════════════════════════════════════════════════════════════
    // Liaison et cycle de vie
    // ════════════════════════════════════════════════════════════════

    /**
     * Lie la barre à un {@link EditorView} : crée le contrôleur, reçoit
     * le compte d'occurrences, applique le thème. Sans liaison, la barre
     * reste muette (les boutons vérifient le contrôleur).
     */
    public void bind(EditorView view) {
        this.editorView = view;
        this.controller = new EditorSearchController(view);
        controller.setMatchesListener(this::updateCount);
        applyTheme();
        syncChips();
        syncReplaceRow();
    }

    /**
     * Ouvre la barre : amorce la requête depuis la sélection courante
     * (une ligne max), calcule les occurrences, amène la première à
     * partir du caret et donne le focus au champ de requête (clavier
     * prêt).
     */
    public void open() {
        if (controller == null) return;
        setVisibility(VISIBLE);
        CharSequence selection = "";
        if (editorView != null && editorView.getSession() != null) {
            jo.codeeditor.document.Selection sel =
                    editorView.getSession().getSelection();
            if (!sel.isCursor()) {
                selection = editorView.getSession().getText()
                        .subSequence(Math.min(sel.start, sel.end),
                                Math.max(sel.start, sel.end));
            }
        }
        String effective = controller.open(selection);
        if (!effective.contentEquals(queryField.getText())) {
            settingFieldText = true;
            queryField.setText(effective);
            settingFieldText = false;
        }
        syncChips();
        syncReplaceRow();
        requestFocus();
        queryField.requestFocus();
        queryField.setSelection(queryField.getText().length());
        InputMethodManager imm = imm();
        if (imm != null) imm.showSoftInput(queryField, 0);
        applyTheme();
    }

    /**
     * Referme : purge le surlignage, détache le listener d'édition, cache
     * la barre et rend le clavier à l'éditeur. La requête survit (une
     * réouverture retrouve le contexte).
     */
    public void close() {
        if (controller != null) controller.close();
        setVisibility(GONE);
        queryField.clearFocus();
        replaceField.clearFocus();
        InputMethodManager imm = imm();
        if (imm != null && getWindowToken() != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    // ── Accesseurs package-privés (tests de vue, même paquet) ──────

    /** Texte courant du champ de requête. */
    String queryFieldText() {
        return queryField.getText().toString();
    }

    /**
     * Écrit dans le champ de requête COMME UNE FRAPPE (watcher actif :
     * la recherche repart en différé — avance le looper pour l'attendre).
     */
    void setQueryFieldText(String text) {
        queryField.setText(text);
    }

    /** Texte courant du champ de remplacement. */
    String replaceFieldText() {
        return replaceField.getText().toString();
    }

    /** Écrit dans le champ de remplacement (sans effet de recherche). */
    void setReplaceFieldText(String text) {
        replaceField.setText(text);
    }

    /** Déclenche l'action clavier « Rechercher » du champ (= Enter). */
    void pressQueryAction() {
        queryField.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
    }

    /** Bascule le chip « Aa » (respecter la casse). */
    void toggleCaseChip() {
        caseChip.performClick();
    }

    /** Bascule le chip « ab » (mot entier). */
    void toggleWordChip() {
        wordChip.performClick();
    }

    /** Bascule le chip « .* » (expression régulière). */
    void toggleRegexChip() {
        regexChip.performClick();
    }

    /** Bascule le mode remplacer (rangée 3). */
    void toggleReplaceChip() {
        replaceToggle.performClick();
    }

    /** La rangée de remplacement est-elle visible ? */
    boolean replaceRowVisible() {
        return replaceRow.getVisibility() == VISIBLE;
    }

    /** Appuie « Remplacer » (l'occurrence courante). */
    void pressReplaceOne() {
        performReplaceCurrent();
    }

    /** Appuie « Tout » (toutes les occurrences). */
    void pressReplaceAll() {
        if (controller != null) {
            controller.replaceAll(replaceField.getText().toString());
        }
    }

    /** Le contrôleur câblé (état, actions) — nul avant {@link #bind}. */
    EditorSearchController controllerState() {
        return controller;
    }

    /**
     * Ré-accroche la session courante et recalcule les occurrences — à
     * appeler après un changement de fichier/onglet de l'hôte (miroir de
     * {@code BreadcrumbBar.refreshActions()}).
     */
    public void refresh() {
        if (controller == null) return;
        controller.refresh();
        syncChips();
        syncReplaceRow();
        applyTheme();
    }

    private InputMethodManager imm() {
        Object service = getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        return service instanceof InputMethodManager ? (InputMethodManager) service : null;
    }

    // ════════════════════════════════════════════════════════════════
    // État visuel
    // ════════════════════════════════════════════════════════════════

    /** Compte « courante/total » — vide sans requête, « 0/0 » sans hit ;
     *  D-05 : « trop coûteux » quand le budget est dépassé, « N+ » quand
     *  la collecte est tronquée au plafond. */
    void updateCount(int count, int currentIndex) {
        if (controller == null || controller.getQuery().isEmpty()) {
            countView.setText("");
        } else if (controller.isTooCostly()) {
            countView.setText(getContext().getString(
                    jo.codeeditor.ui.R.string.cel_find_too_costly));
        } else if (controller.isTruncated()) {
            countView.setText(getContext().getString(
                    jo.codeeditor.ui.R.string.cel_find_count_truncated,
                    count, FindReplace.MAX_MATCHES));
        } else if (count == 0) {
            countView.setText(getContext().getString(
                    jo.codeeditor.ui.R.string.cel_find_count, 0, 0));
        } else {
            countView.setText(getContext().getString(
                    jo.codeeditor.ui.R.string.cel_find_count,
                    currentIndex + 1, count));
        }
        countView.invalidate();
    }

    /** Ré-évalue l'état ACTIF (accent) des bascules depuis le contrôleur. */
    private void syncChips() {
        if (caseChip != null) caseChip.syncActive();
        if (wordChip != null) wordChip.syncActive();
        if (regexChip != null) regexChip.syncActive();
        if (replaceToggle != null) replaceToggle.syncActive();
    }

    private void syncReplaceRow() {
        if (replaceRow != null) {
            replaceRow.setVisibility(controller != null && controller.isReplaceMode()
                    ? VISIBLE : GONE);
        }
    }

    /**
     * Applique le thème de l'éditeur lié aux champs et au compte (fond
     * de champ, texte, indice, couleur du compte) — jeu sombre
     * historique sans liaison.
     */
    private void applyTheme() {
        EditorTheme theme = editorView != null ? editorView.getTheme() : null;
        int fieldBg = theme != null
                ? EditorView.applyAlphaToColor(theme.gutterText, 0.10f) : 0xFF262626;
        int fieldText = theme != null ? theme.textColor : 0xFFD4D4D4;
        int fieldHint = theme != null
                ? EditorView.applyAlphaToColor(theme.gutterText, 0.55f) : 0xFF808080;
        int countColor = theme != null
                ? EditorView.applyAlphaToColor(theme.gutterText, 0.9f) : 0xFFB8B8B8;

        styleField(queryField, fieldBg, fieldText, fieldHint);
        styleField(replaceField, fieldBg, fieldText, fieldHint);
        countView.setTextColor(countColor);
        invalidate();
    }

    private void styleField(EditText field, int bg, int text, int hint) {
        GradientDrawable drawable = makeFieldBackground();
        drawable.setColor(bg);
        field.setBackground(drawable);
        field.setTextColor(text);
        field.setHintTextColor(hint);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Fond + bordure inférieure — teintés du thème lié (miroir
        // BreadcrumbBar.dispatchDraw).
        EditorTheme theme = editorView != null ? editorView.getTheme() : null;
        bgPaint.setColor(theme != null ? theme.gutterBg : 0xFF1E1E1E);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        borderPaint.setColor(theme != null ? theme.gutterBorder : 0xFF323232);
        canvas.drawRect(0, getHeight() - 1, getWidth(), getHeight(), borderPaint);
        super.dispatchDraw(canvas);
    }

    // ════════════════════════════════════════════════════════════════
    // Bouton pill (icône ou libellé) — registre BreadcrumbBar
    // ════════════════════════════════════════════════════════════════

    /**
     * Bouton compact de la barre : glyphe vectoriel ou libellé court,
     * fond pill arrondi à l'appui, ACCENT quand la bascule qu'il porte
     * est active (fournisseur). Clic par le chemin standard
     * {@code setOnClickListener}/{@code performClick}.
     */
    private final class BarButton extends View {
        private final String label;
        private final EditorIcons.Icon icon;
        private final java.util.function.BooleanSupplier active;
        private boolean pressed = false;
        private boolean activeNow = false;
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint();
        private final RectF pillRect = new RectF();

        BarButton(Context ctx, String label, EditorIcons.Icon icon,
                String contentDescription,
                OnClickListener click, java.util.function.BooleanSupplier active) {
            super(ctx);
            this.label = label;
            this.icon = icon;
            this.active = active;
            if (contentDescription != null) {
                setContentDescription(contentDescription);
            }
            setMinimumWidth((int) (40 * density));
            setClickable(true);
            if (click != null) setOnClickListener(click);
        }

        void syncActive() {
            activeNow = active != null && active.getAsBoolean();
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float w;
            if (icon != null) {
                w = ICON_SIZE_DP * density + 22 * density;
            } else {
                w = chipTextPaint().measureText(label) + 18 * density;
            }
            if (w < getMinimumWidth()) w = getMinimumWidth();
            setMeasuredDimension((int) w, MeasureSpec.getSize(heightMeasureSpec));
        }

        private Paint chipTextPaint() {
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setTextSize(TEXT_SIZE_SP * density);
            text.setTypeface(Typeface.MONOSPACE);
            return text;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            EditorTheme theme = editorView != null ? editorView.getTheme() : null;
            boolean enabled = isEnabled();

            boolean highlight = enabled && (pressed || activeNow);
            if (highlight) {
                int color = activeNow
                        ? (theme != null ? theme.keyword : 0xFF6750A4)
                        : 0xFFFFFFFF;
                bgPaint.setColor(EditorView.applyAlphaToColor(color, 0.16f));
                pillRect.set(2 * density, 4 * density, w - 2 * density, h - 4 * density);
                float r = pillRect.height() * 0.5f;
                canvas.drawRoundRect(pillRect, r, r, bgPaint);
            }

            int color;
            if (!enabled) {
                color = theme != null
                        ? EditorView.applyAlphaToColor(theme.gutterText, 0.25f)
                        : 0xFF555555;
            } else if (activeNow) {
                color = theme != null ? theme.keyword : 0xFF6750A4;
            } else {
                color = theme != null
                        ? EditorView.applyAlphaToColor(theme.gutterText, 0.9f)
                        : 0xFFD4D4D4;
            }

            if (icon != null) {
                glyphPaint.setColor(color);
                EditorIcons.draw(canvas, icon, w / 2f, h / 2f,
                        ICON_SIZE_DP * density, glyphPaint);
                return;
            }
            Paint text = chipTextPaint();
            text.setColor(color);
            text.setFakeBoldText(true);
            text.setTextAlign(Paint.Align.CENTER);
            float y = h / 2f - (text.ascent() + text.descent()) / 2;
            canvas.drawText(label, w / 2f, y, text);
            text.setFakeBoldText(false);
            text.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!isEnabled()) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    pressed = false;
                    invalidate();
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    invalidate();
                    return true;
            }
            return false;
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }
    }
}
