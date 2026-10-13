package jo.codeeditor.view.chrome;

import jo.codeeditor.view.EditorView;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
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
 * courante (fichier › classe › méthode) au-dessus de l'éditeur.
 *
 * <p><b>B5 (retour terrain post-J6)</b> : la barre porte aussi la zone
 * d'<b>actions d'éditeur</b> en haut à droite — les boutons jusque-là
 * dispersés (agrandir/réduire la police, icône d'aperçu, annuler,
 * rétablir, tous les boutons de l'hôte) se regroupent ici, à droite
 * des segments, via {@link #addAction} / {@link #addPreviewAction}.
 * Une seule rangée, un seul endroit, comme le bandeau d'un IDE.</p>
 *
 * <p>Suit la position du caret et met à jour les segments du fil d'Ariane
 * via le {@link jo.codeeditor.lang.provider.SymbolProvider} lorsqu'il est
 * disponible.</p>
 *
 * <p>Les couleurs suivent le thème de l'éditeur lié ({@link #bind}) —
 * gouttière pour le fond, texte pour le dernier segment, accent pour les
 * actions actives ; sans éditeur lié, le jeu sombre historique est
 * utilisé.</p>
 */
public class BreadcrumbBar extends LinearLayout {

    private String[] segments = new String[0];
    private final Paint textPaint;
    private final Paint chevronPaint;
    private final Paint bgPaint = new Paint();
    private final Paint borderPaint = new Paint();
    private final float density;
    private int barHeightPx;
    private EditorView editorView;
    private long lastUpdate = 0;
    private static final long DEBOUNCE_MS = 200;

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
        barHeightPx = (int) (36 * density);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(12 * density);
        textPaint.setTypeface(Typeface.MONOSPACE);
        chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chevronPaint.setTextSize(10 * density);
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
    // B5 — zone d'actions d'éditeur (haut à droite)
    // ════════════════════════════════════════════════════════════════

    /**
     * Ajoute un bouton d'action à droite de la barre (ordre d'ajout = ordre
     * d'affichage, le plus à droite en dernier). Le bouton est compact
     * (hauteur de la barre, largeur du libellé + padding), teinté du thème
     * de l'éditeur lié, pressable, accessible ({@code performClick}).
     *
     * @param label             le glyphe court affiché (ex. « A+ », « ↶ »)
     * @param contentDescription le libellé d'accessibilité (null = label)
     * @param enabled           fournisseur d'état activé (null = toujours
     *                          actif) — ré-évalué à chaque rafraîchissement
     * @param click             le clic (null = bouton inerte)
     * @return le bouton ajouté (pour un retrait ciblé via {@link #removeAction})
     */
    public ActionButton addAction(String label, String contentDescription,
            BooleanSupplier enabled, OnClickListener click) {
        ActionButton button = new ActionButton(getContext(), label, enabled, click);
        if (contentDescription != null) {
            button.setContentDescription(contentDescription);
        }
        actions.add(button);
        addView(button, new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        return button;
    }

    /**
     * B5 — ajoute l'action <b>aperçu</b> (icône œil) câblée sur l'éditeur
     * lié : un tap ouvre l'aperçu (feuille scindée par défaut, l'en-tête de
     * la feuille bascule plein écran) quand le fichier courant est
     * prévisualisable.
     *
     * <p>Les icônes d'aperçu du coin haut-droit du CANVAS se retirent au
     * même moment ({@code EditorView.setPreviewIconsVisible(false)}) :
     * l'entrée d'interaction déménage dans la barre, elle ne se duplique
     * pas. Le bouton se grise quand le fichier n'est pas prévisualisable
     * (état lu à chaque rafraîchissement — l'hôte appelle
     * {@link #refreshActions()} quand l'échantillon change).</p>
     */
    public ActionButton addPreviewAction() {
        if (editorView == null) {
            throw new IllegalStateException("bind(editor) doit précéder addPreviewAction()");
        }
        final EditorView editor = editorView;
        editor.setPreviewIconsVisible(false);
        return addAction("👁", getContext().getString(jo.codeeditor.ui.R.string.cel_preview),
                () -> editor.previewable,
                v -> editor.openPreview(false));
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
     * Ré-évalue l'état (activé/grisé) de chaque bouton d'action — l'état
     * n'est lu qu'au rafraîchissement, ce qui force la relecture (changement
     * d'échantillon, de session, d'historique annuler/rétablir…).
     */
    public void refreshActions() {
        for (ActionButton button : actions) {
            button.setEnabled(button.enabled == null || button.enabled.getAsBoolean());
            button.invalidate();
        }
        segmentsView.invalidate();
    }

    /**
     * Invalide les boutons de chaque {@link BreadcrumbBar} trouvée parmi les
     * enfants du parent donné — miroir de
     * {@code EditorBarTools.invalidateButtonsIn} : appelé par
     * {@code EditorView.notifyTextChanged} après chaque édition pour
     * rafraîchir les états activé/désactivé (annuler/rétablir, aperçu).
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
                    x += chevronPaint.measureText("\u203A") + 6 * density;
                }
                // Dessine le texte du segment.
                boolean isLast = (i == segments.length - 1);
                textPaint.setColor(isLast ? lastColor : segmentColor);
                textPaint.setTypeface(isLast
                    ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    : Typeface.MONOSPACE);
                canvas.drawText(segments[i], x, padY, textPaint);
                x += textPaint.measureText(segments[i]) + 6 * density;
                if (x > getWidth() + 200 * density) break; // chaîne folle : stop
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Bouton d'action (droite)
    // ════════════════════════════════════════════════════════════════

    /**
     * Un bouton compact de la zone d'actions — même registre visuel que
     * les boutons de {@code EditorBarTools} (glyphe centré, semibold,
     * appui en surbrillance, accent pendant l'appui) mais câblé par
     * l'hôte : libellé, état et clic sont fournis à l'ajout. Le clic
     * passe par le chemin STANDARD {@code setOnClickListener}/
     * {@code performClick} (accessibilité, tests) ; l'état grisé est
     * porté par {@code setEnabled} — rafraîchi par
     * {@link BreadcrumbBar#refreshActions()}
     */
    public final class ActionButton extends View {
        private final String label;
        private final BooleanSupplier enabled;
        private boolean pressed = false;

        ActionButton(Context ctx, String label, BooleanSupplier enabled,
                OnClickListener click) {
            super(ctx);
            this.label = label != null ? label : "";
            this.enabled = enabled;
            setContentDescription(this.label);
            setMinimumWidth((int) (32 * density));
            if (click != null) setOnClickListener(click);
            setClickable(true);
            setEnabled(enabled == null || enabled.getAsBoolean());
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            float textW = textPaint.measureText(label);
            int w = (int) (textW + 16 * density);
            if (w < getMinimumWidth()) w = getMinimumWidth();
            setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();

            // Arrière-plan à l'appui.
            if (pressed) {
                bgPaint.setColor(0x33FFFFFF);
                canvas.drawRect(0, 0, w, h, bgPaint);
            }

            // Couleur du glyphe — sobre si actif, atténué si désactivé
            // (l'accent du thème marque l'APPUI, pas un état à bascule).
            jo.codeeditor.view.chrome.EditorTheme theme =
                    editorView != null ? editorView.getTheme() : null;
            boolean enabledState = isEnabled();
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
            if (enabledState && pressed) {
                // Accent du thème sur le glyphe pendant l'appui.
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
