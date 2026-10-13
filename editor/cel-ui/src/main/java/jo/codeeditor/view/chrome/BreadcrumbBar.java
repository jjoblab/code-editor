package jo.codeeditor.view.chrome;

import jo.codeeditor.view.EditorView;
import jo.codeeditor.view.icons.EditorIcons;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Barre de navigation à fil d'Ariane — affiche la chaîne de portées
 * courante (fichier › classe › méthode) au-dessus de l'éditeur, et porte
 * la zone d'<b>actions d'éditeur</b> en haut à droite.
 *
 * <p><b>B6 (refonte moderne)</b> : les actions troquent les glyphes
 * texte/emoji (« ↶ », « 👁 » — rendu incohérent selon les polices de
 * l'appareil) contre des <b>icônes vectorielles dessinées</b>
 * ({@link EditorIcons}, teintées du thème de l'éditeur) ; les boutons
 * prennent le registre pill moderne (fond arrondi à l'appui, accent quand
 * actif) ; la barre grandit à 40 dp (cible tactile confortable).</p>
 *
 * <p><b>Intelligence intégrée</b> — {@link #addEditorActions()} câble
 * SEULEMENT les trois actions d'un IDE (annuler, rétablir, aperçu) sans
 * une ligne de code hôte : annuler/rétablir suivent la session liée
 * (grisés quand l'historique est vide, rafraîchis après chaque édition) ;
 * l'action d'aperçu n'EXISTE que pour un fichier prévisualisable
 * ({@code GONE} sinon — pas un bouton grisé qui squatte la rangée),
 * reflète l'état ouvert (accent) et <b>bascule</b> : un tap ouvre la
 * feuille scindée, un second la referme.</p>
 *
 * <p>Suit la position du caret et met à jour les segments du fil d'Ariane
 * via le {@link jo.codeeditor.lang.provider.SymbolProvider} lorsqu'il est
 * disponible. Les couleurs suivent le thème de l'éditeur lié
 * ({@link #bind}) — gouttière pour le fond, texte pour le dernier
 * segment, accent pour les actions actives ; sans éditeur lié, le jeu
 * sombre historique est utilisé.</p>
 */
public class BreadcrumbBar extends LinearLayout {

    private String[] segments = new String[0];
    private final Paint textPaint;
    private final Paint chevronPaint;
    private final Paint bgPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private final float density;
    private int barHeightPx;
    private EditorView editorView;
    private long lastUpdate = 0;
    private static final long DEBOUNCE_MS = 200;

    /** Taille du glyphe des boutons-icônes (dp). */
    static final float ICON_SIZE_DP = 20f;
    /** Largeur minimale d'un bouton (dp) — cible tactile confortable. */
    static final float BUTTON_MIN_WIDTH_DP = 40f;

    /** La vue qui dessine les segments (côté gauche, prend la place restante). */
    private final SegmentsView segmentsView;

    /** Les boutons de la zone d'actions (côté droit, ordre d'ajout). */
    private final List<ActionButton> actions = new ArrayList<>();

    public BreadcrumbBar(Context context) {
        this(context, null);
    }

    public BreadcrumbBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        density = getResources().getDisplayMetrics().density;
        barHeightPx = (int) (40 * density);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(12 * density);
        textPaint.setTypeface(Typeface.MONOSPACE);
        chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chevronPaint.setTextSize(11 * density);
        segmentsView = new SegmentsView(context);
        addView(segmentsView, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
    }

    /**
     * Lie cette barre de fil d'Ariane à un EditorView. La barre suit la
     * position du caret et met à jour les segments via le SymbolProvider.
     */
    public void bind(EditorView view) {
        this.editorView = view;
        // Utilise addOnSelectionChangedListener (et non un setter) pour ne pas
        // remplacer le listener principal (ex. barre d'état de MainActivity).
        view.addOnSelectionChangedListener((line, col, isCursor) -> {
            long now = System.currentTimeMillis();
            if (now - lastUpdate < DEBOUNCE_MS) return;
            lastUpdate = now;
            postDelayed(this::updateSegments, DEBOUNCE_MS);
        });
        refreshActions();
    }

    /** Met à jour les segments du fil d'Ariane depuis le SymbolProvider du langage courant. */
    private void updateSegments() {
        if (editorView == null || editorView.getSession() == null) {
            segments = new String[0];
            invalidate();
            return;
        }
        String fileName = editorView.getSession().getLanguage() != null
            ? editorView.getSession().getLanguage() : "file";
        // Tente d'obtenir les symboles depuis le SPI Language.
        jo.codeeditor.lang.Language lang = editorView.getLanguage();
        if (lang != null && lang.getSymbolProvider() != null) {
            try {
                java.util.List<jo.codeeditor.lang.model.Symbol> symbols =
                    lang.getSymbolProvider().symbols(editorView.getSession().getText());
                int caret = editorView.getSession().getSelection().start;
                // Recherche les symboles englobants (offset <= caret < end).
                java.util.List<jo.codeeditor.lang.model.Symbol> enclosing = new java.util.ArrayList<>();
                for (jo.codeeditor.lang.model.Symbol s : symbols) {
                    if (s.offset <= caret) {
                        enclosing.add(s);
                    }
                }
                // Tri par offset (le plus externe en premier).
                enclosing.sort((a, b) -> Integer.compare(a.offset, b.offset));
                // Construit les segments : fichier › classe › méthode.
                java.util.List<String> segs = new java.util.ArrayList<>();
                segs.add(fileName);
                for (jo.codeeditor.lang.model.Symbol s : enclosing) {
                    segs.add(s.name);
                }
                segments = segs.toArray(new String[0]);
            } catch (Exception e) {
                segments = new String[]{fileName};
            }
        } else {
            segments = new String[]{fileName};
        }
        invalidate();
    }

    /** Définit les segments directement (usage manuel sans SymbolProvider). */
    public void setSegments(String[] segs) {
        this.segments = segs != null ? segs : new String[0];
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(barHeightPx, MeasureSpec.EXACTLY));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Arrière-plan + bordure inférieure — teintés du thème de l'éditeur
        // lié (jeu sombre historique sans liaison). Les enfants (segments +
        // actions) se dessinent par-dessus.
        jo.codeeditor.view.chrome.EditorTheme theme =
                editorView != null ? editorView.getTheme() : null;
        bgPaint.setColor(theme != null ? theme.gutterBg : 0xFF1E1E1E);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        borderPaint.setColor(theme != null ? theme.gutterBorder : 0xFF323232);
        canvas.drawRect(0, getHeight() - 1, getWidth(), getHeight(), borderPaint);
        super.dispatchDraw(canvas);
    }

    // ════════════════════════════════════════════════════════════════
    // B6 — zone d'actions d'éditeur (haut à droite), version moderne
    // ════════════════════════════════════════════════════════════════

    /**
     * Ajoute un bouton d'action TEXTE à droite de la barre (registre
     * historique B5, pour les libellés courts comme « A+ »). Le bouton est
     * compact (hauteur de la barre, largeur du libellé + padding), teinté
     * du thème de l'éditeur lié, pressable, accessible
     * ({@code performClick}).
     *
     * @param label             le libellé court affiché (ex. « A+ », « ↶ »)
     * @param contentDescription le libellé d'accessibilité (null = label)
     * @param enabled           fournisseur d'état activé (null = toujours
     *                          actif) — ré-évalué à chaque rafraîchissement
     * @param click             le clic (null = bouton inerte)
     * @return le bouton ajouté (pour un retrait ciblé via {@link #removeAction})
     */
    public ActionButton addAction(String label, String contentDescription,
            BooleanSupplier enabled, OnClickListener click) {
        ActionButton button = new ActionButton(getContext(), label, null, enabled, click);
        if (contentDescription != null) {
            button.setContentDescription(contentDescription);
        }
        attach(button);
        return button;
    }

    /**
     * B6 — ajoute un bouton d'action ICÔNE à droite de la barre : glyphe
     * vectoriel {@link EditorIcons} (net à toute densité, teinté du thème),
     * cible tactile de 40 dp minimum, content description pour TalkBack.
     * C'est le registre recommandé pour les actions d'éditeur.
     *
     * @param icon              l'icône dessinée (jamais nulle)
     * @param contentDescription le libellé d'accessibilité
     * @param enabled           fournisseur d'état activé (null = toujours
     *                          actif) — ré-évalué à chaque rafraîchissement
     * @param click             le clic (null = bouton inerte)
     * @return le bouton ajouté (pour un retrait ciblé via {@link #removeAction})
     */
    public ActionButton addIconAction(EditorIcons.Icon icon, String contentDescription,
            BooleanSupplier enabled, OnClickListener click) {
        if (icon == null) throw new IllegalArgumentException("icon != null");
        ActionButton button = new ActionButton(getContext(), null, icon, enabled, click);
        if (contentDescription != null) {
            button.setContentDescription(contentDescription);
        }
        attach(button);
        return button;
    }

    /**
     * B6 — le raccourci « barre d'IDE » : ajoute EXACTEMENT les trois
     * actions d'éditeur <b>câblées</b> sur l'éditeur lié — annuler
     * ({@link EditorIcons.Icon#UNDO}), rétablir
     * ({@link EditorIcons.Icon#REDO}) et aperçu
     * ({@link EditorIcons.Icon#EYE}), dans cet ordre.
     *
     * <ul>
     *   <li><b>Annuler / rétablir</b> suivent la session courante de
     *       l'éditeur (reliée à chaque évaluation — le changement de
     *       session est couvert) : grisés sans historique, un tap
     *       dépile ; l'état se rafraîchit après chaque édition via le
     *       miroir {@code EditorView.notifyTextChanged} ;</li>
     *   <li><b>Aperçu</b> ne s'ajoute VISUELLEMENT que pour un fichier
     *       prévisualisable ({@code GONE} sinon — voir
     *       {@link #addPreviewAction()}), s'allume de l'accent quand la
     *       feuille est ouverte, et bascule : ouvrir / fermer ;</li>
     *   <li>Les icônes d'aperçu du CANVAS se retirent au même moment —
     *       une seule entrée d'interaction.</li>
     * </ul>
     *
     * @return les trois boutons dans l'ordre d'ajout (annuler, rétablir,
     *         aperçu) — l'hôte peut en retirer un via
     *         {@link #removeAction(ActionButton)}
     */
    public ActionButton[] addEditorActions() {
        if (editorView == null) {
            throw new IllegalStateException("bind(editor) doit précéder addEditorActions()");
        }
        final EditorView editor = editorView;
        ActionButton undo = addIconAction(EditorIcons.Icon.UNDO,
                getContext().getString(jo.codeeditor.ui.R.string.cel_undo),
                () -> editor.getSession() != null
                        && editor.getSession().getUndoManager().canUndo(),
                v -> {
                    if (editor.getSession() != null) editor.getSession().undo();
                });
        ActionButton redo = addIconAction(EditorIcons.Icon.REDO,
                getContext().getString(jo.codeeditor.ui.R.string.cel_redo),
                () -> editor.getSession() != null
                        && editor.getSession().getUndoManager().canRedo(),
                v -> {
                    if (editor.getSession() != null) editor.getSession().redo();
                });
        ActionButton preview = addPreviewAction();
        return new ActionButton[]{undo, redo, preview};
    }

    /**
     * B6 — ajoute l'action <b>aperçu</b> (icône œil) câblée sur l'éditeur
     * lié.
     *
     * <p>Intelligence de l'action :
     * <ul>
     *   <li>le bouton n'est <b>visible que si le fichier courant est
     *       prévisualisable</b> ({@code canPreview} du hôte — .md, .html,
     *       …) : sinon {@code GONE}, la rangée ne garde rien d'inutile.
     *       L'état est relu à chaque rafraîchissement — le changement de
     *       nom de fichier ({@code setFileName}) rafraîchit la barre sœur
     *       via le miroir du contrôleur d'aperçu ;</li>
     *   <li>quand la feuille d'aperçu est OUVERTE, le bouton s'allume de
     *       l'accent du thème (état actif) ;</li>
     *   <li>le tap <b>bascule</b> : feuille scindée si fermée, fermeture si
     *       ouverte (plus besoin de viser le X de la feuille) ;</li>
     *   <li>les icônes d'aperçu du coin haut-droit du CANVAS se retirent
     *       au même moment
     *       ({@code EditorView.setPreviewIconsVisible(false)}) : une seule
     *       entrée d'interaction, au même endroit que les autres
     *       boutons.</li>
     * </ul>
     */
    public ActionButton addPreviewAction() {
        if (editorView == null) {
            throw new IllegalStateException("bind(editor) doit précéder addPreviewAction()");
        }
        final EditorView editor = editorView;
        editor.setPreviewIconsVisible(false);
        ActionButton button = new ActionButton(getContext(), null, EditorIcons.Icon.EYE,
                () -> editor.previewable,
                v -> {
                    if (editor.previewMode.isSheet()) {
                        editor.closePreviewSheet();
                    } else {
                        editor.openPreview(false);
                    }
                });
        // N'existe que pour un fichier prévisualisable.
        button.visible = () -> editor.previewable;
        button.active = () -> editor.previewMode != EditorView.PreviewMode.NONE;
        button.setContentDescription(
                getContext().getString(jo.codeeditor.ui.R.string.cel_preview));
        attach(button);
        refreshActions();
        return button;
    }

    /** Pose le bouton dans la rangée (fin = le plus à droite). */
    private void attach(ActionButton button) {
        actions.add(button);
        addView(button, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
    }

    /** Retire un bouton d'action de la barre (no-op s'il n'y est pas). */
    public void removeAction(ActionButton button) {
        if (button == null) return;
        actions.remove(button);
        removeView(button);
    }

    /** Retire tous les boutons d'action (les segments restent). */
    public void clearActions() {
        for (ActionButton button : actions) removeView(button);
        actions.clear();
    }

    /**
     * Ré-évalue l'état (activé/grisé, visible/gone, actif/accent) de chaque
     * bouton d'action — l'état n'est lu qu'au rafraîchissement, ce qui
     * force la relecture (changement d'échantillon, de session, d'historique
     * annuler/rétablir, de nom de fichier…). Appelé automatiquement après
     * chaque édition ({@code notifyTextChanged}), chaque changement de nom
     * de fichier et chaque bascule du mode d'aperçu.
     */
    public void refreshActions() {
        for (ActionButton button : actions) {
            button.setEnabled(button.enabled == null || button.enabled.getAsBoolean());
            if (button.visible != null) {
                button.setVisibility(button.visible.getAsBoolean() ? VISIBLE : GONE);
            }
            button.activeNow = button.active != null && button.active.getAsBoolean();
            button.invalidate();
        }
        segmentsView.invalidate();
    }

    /**
     * Invalide les boutons de chaque {@link BreadcrumbBar} trouvée parmi les
     * enfants du parent donné — miroir de
     * {@code EditorBarTools.invalidateButtonsIn} : appelé par
     * {@code EditorView.notifyTextChanged} après chaque édition (et par le
     * contrôleur d'aperçu au changement de nom de fichier / de mode) pour
     * rafraîchir les états activé/visible/actif.
     */
    public static void invalidateActionsIn(ViewParent p) {
        if (p instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup) p;
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (parent.getChildAt(i) instanceof BreadcrumbBar) {
                    ((BreadcrumbBar) parent.getChildAt(i)).refreshActions();
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Vue de segments (gauche)
    // ════════════════════════════════════════════════════════════════

    /** Dessine la chaîne de segments « fichier › classe › méthode ». */
    private final class SegmentsView extends View {

        SegmentsView(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            jo.codeeditor.view.chrome.EditorTheme theme =
                    editorView != null ? editorView.getTheme() : null;
            int chevronColor = theme != null ? theme.gutterText : 0xFF808080;
            int segmentColor = theme != null ? theme.gutterText : 0xFF858585;
            int lastColor = theme != null ? theme.textColor : 0xFFD4D4D4;
            float padX = 12 * density;
            float padY = (getHeight() - textPaint.getTextSize()) * 0.5f
                + textPaint.getTextSize() * 0.35f;
            float x = padX;
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) {
                    // Dessine le chevron ›.
                    chevronPaint.setColor(chevronColor);
                    canvas.drawText("\u203A", x, padY, chevronPaint);
                    x += chevronPaint.measureText("\u203A") + 7 * density;
                }
                // Dessine le texte du segment.
                boolean isLast = (i == segments.length - 1);
                textPaint.setColor(isLast ? lastColor : segmentColor);
                textPaint.setTypeface(isLast
                    ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    : Typeface.MONOSPACE);
                canvas.drawText(segments[i], x, padY, textPaint);
                x += textPaint.measureText(segments[i]) + 7 * density;
                if (x > getWidth() + 200 * density) break; // chaîne folle : stop
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Bouton d'action (droite) — registre pill moderne B6
    // ════════════════════════════════════════════════════════════════

    /**
     * Un bouton compact de la zone d'actions — glyphe ICÔNE vectorielle
     * (B6) ou libellé texte (registre historique B5), teinté du thème de
     * l'éditeur lié. Registre pill moderne : fond arrondi (demi-hauteur)
     * à l'appui, glyphe en accent quand la action est ACTIVE (ex. aperçu
     * ouvert), atténué quand elle est indisponible. Le clic passe par le
     * chemin STANDARD {@code setOnClickListener}/{@code performClick}
     * (accessibilité, tests) ; l'état est rafraîchi par
     * {@link BreadcrumbBar#refreshActions()}.
     */
    public final class ActionButton extends View {
        private final String label;
        private final EditorIcons.Icon icon;
        private final BooleanSupplier enabled;
        /** Visibilité ré-évaluée au rafraîchissement (null = toujours visible). */
        BooleanSupplier visible;
        /** État « actif » (accent) ré-évalué au rafraîchissement (null = jamais). */
        BooleanSupplier active;
        boolean activeNow;
        private boolean pressed = false;

        ActionButton(Context ctx, String label, EditorIcons.Icon icon,
                BooleanSupplier enabled, OnClickListener click) {
            super(ctx);
            this.label = label;
            this.icon = icon;
            this.enabled = enabled;
            String description = label != null ? label
                    : (icon != null ? icon.name() : "");
            setContentDescription(description);
            setMinimumWidth((int) (BUTTON_MIN_WIDTH_DP * density));
            if (click != null) setOnClickListener(click);
            setClickable(true);
            setEnabled(enabled == null || enabled.getAsBoolean());
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int w;
            if (icon != null) {
                w = (int) (ICON_SIZE_DP * density + 20 * density);
            } else {
                float textW = textPaint.measureText(label);
                w = (int) (textW + 16 * density);
            }
            if (w < getMinimumWidth()) w = getMinimumWidth();
            setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();

            jo.codeeditor.view.chrome.EditorTheme theme =
                    editorView != null ? editorView.getTheme() : null;
            boolean enabledState = isEnabled();

            // Fond pill : à l'appui (blanc voilé) ou en état ACTIF (accent
            // voilé — l'aperçu ouvert s'affiche allumé).
            boolean highlight = (enabledState && pressed) || (enabledState && activeNow);
            if (highlight) {
                int highlightColor = activeNow
                        ? (theme != null ? theme.keyword : 0xFF6750A4)
                        : 0xFFFFFFFF;
                bgPaint.setColor(EditorView.applyAlphaToColor(highlightColor, 0.16f));
                pillRect.set(2 * density, 4 * density, w - 2 * density, h - 4 * density);
                float r = (pillRect.height()) * 0.5f;
                canvas.drawRoundRect(pillRect, r, r, bgPaint);
            }

            if (icon != null) {
                // Glyphe vectoriel — accent si actif, sobre si disponible,
                // atténué si indisponible.
                int color;
                if (!enabledState) {
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
                iconPaint.setColor(color);
                EditorIcons.draw(canvas, icon, w / 2f, h / 2f,
                        ICON_SIZE_DP * density, iconPaint);
                return;
            }

            // Registre texte historique (B5).
            int normalColor = theme != null
                ? EditorView.applyAlphaToColor(theme.gutterText, 0.8f)
                : 0xFFD4D4D4;
            int disabledColor = theme != null
                ? EditorView.applyAlphaToColor(theme.gutterText, 0.25f)
                : 0xFF555555;
            textPaint.setColor(enabledState ? normalColor : disabledColor);
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.CENTER);
            float y = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2;
            canvas.drawText(label, w / 2f, y, textPaint);
            textPaint.setFakeBoldText(false);
            textPaint.setTextAlign(Paint.Align.LEFT);
            if (enabledState && (pressed || activeNow)) {
                // Accent du thème sur le glyphe pendant l'appui / actif.
                textPaint.setColor(theme != null ? theme.keyword : 0xFF6750A4);
                canvas.drawText(label, w / 2f, y, textPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // Grisé : le tap retombe vers le parent (pas d'appui, pas de
            // clic, pas de consommation).
            if (!isEnabled()) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    pressed = false;
                    invalidate();
                    // performClick déclenche l'OnClickListener standard
                    // (accessibilité talkback + harnais de test).
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
            // Chemin standard explicite (lint ClickableViewAccessibility,
            // talkback) — déclenche l'OnClickListener posé à l'ajout.
            return super.performClick();
        }
    }
}
