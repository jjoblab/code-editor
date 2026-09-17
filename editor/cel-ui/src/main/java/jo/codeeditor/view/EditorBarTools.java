package jo.codeeditor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import jo.codeeditor.view.chrome.SymbolBarView;

/**
 * Barre d'outils de l'éditeur — placée directement au-dessus de l'EditorView,
 * contenant les icônes de contrôle : Annuler, Rétablir, A+, A−, ¶
 * (non-imprimables), == (ligatures).
 *
 * <p>Contrairement aux icônes d'outils dessinées sur Canvas, c'est une vraie
 * {@link View} Android qui vit dans le layout (comme {@link SymbolBarView}).
 * Elle se connecte directement à son {@link EditorView} — l'hôte doit
 * seulement la placer dans le layout et éventuellement basculer sa
 * visibilité.
 *
 * <p>La barre lit le thème de l'éditeur pour les couleurs et appelle l'API
 * publique de l'éditeur au toucher. Les icônes à bascule (¶, ==)
 * synchronisent leur état visuel depuis les champs de l'éditeur.
 */
public class EditorBarTools extends LinearLayout {

    private EditorView editorView;
    private final Paint textPaint;
    private final Paint bgPaint;
    private final float density;
    private final int barHeightPx;

    public EditorBarTools(Context context) {
        this(context, null);
    }

    public EditorBarTools(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        density = getResources().getDisplayMetrics().density;
        barHeightPx = (int) (36 * density);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(14 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        bgPaint = new Paint();
        buildButtons();
    }

    /**
     * Connecte cette barre à son EditorView. Doit être appelée avant toute
     * interaction avec la barre. Celle-ci lit l'état de l'éditeur pour les
     * icônes à bascule et appelle l'API de l'éditeur au toucher.
     */
    public void setEditorView(EditorView editorView) {
        this.editorView = editorView;
    }

    /**
     * Invalide les boutons de chaque {@link EditorBarTools} trouvé parmi
     * les enfants du parent donné, pour rafraîchir leurs états
     * activé/désactivé (undo/redo) et à bascule (¶, ==). Appelé par
     * EditorView.notifyTextChanged après chaque édition — corps déplacé
     * à l'identique (la barre sait rafraîchir ses propres boutons).
     */
    static void invalidateButtonsIn(android.view.ViewParent p) {
        if (p instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup) p;
            for (int i = 0; i < parent.getChildCount(); i++) {
                if (parent.getChildAt(i) instanceof EditorBarTools) {
                    EditorBarTools bar = (EditorBarTools) parent.getChildAt(i);
                    for (int j = 0; j < bar.getChildCount(); j++) {
                        bar.getChildAt(j).invalidate();
                    }
                }
            }
        }
    }

    private void buildButtons() {
        // Annuler
        addBarButton("↶", "undo");
        // Rétablir
        addBarButton("↷", "redo");
        // Séparateur
        addDivider();
        // A+ (augmente la taille de police)
        addBarButton("A+", "font_plus");
        // A- (réduit la taille de police)
        addBarButton("A−", "font_minus");
        // Séparateur
        addDivider();
        // ¶ (bascule non-imprimables)
        addBarButton("¶", "nonprintable");
        // == (bascule ligatures)
        addBarButton("==", "ligatures");
    }

    private void addDivider() {
        View div = new View(getContext());
        div.setLayoutParams(new LayoutParams(1, (int) (22 * density)));
        div.setBackgroundColor(0xFF3A3A3A);
        android.widget.LinearLayout.LayoutParams lp =
            new android.widget.LinearLayout.LayoutParams(1, (int) (22 * density));
        lp.setMargins((int) (4 * density), (int) (7 * density), (int) (4 * density), (int) (7 * density));
        div.setLayoutParams(lp);
        addView(div);
    }

    private void addBarButton(String label, String action) {
        BarButton btn = new BarButton(getContext(), label, action);
        addView(btn);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(barHeightPx, MeasureSpec.EXACTLY));
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Dessine l'arrière-plan.
        bgPaint.setColor(editorView != null ? editorView.getTheme().gutterBg : 0xFF1E1E1E);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        // Bordure inférieure.
        bgPaint.setColor(editorView != null ? editorView.getTheme().gutterBorder : 0xFF333333);
        canvas.drawRect(0, getHeight() - 1, getWidth(), getHeight(), bgPaint);
        super.dispatchDraw(canvas);
    }

    /** Un bouton de la barre d'outils. */
    private class BarButton extends View {
        private final String label;
        private final String action;
        private boolean pressed = false;

        BarButton(Context ctx, String label, String action) {
            super(ctx);
            this.label = label;
            this.action = action;
            int minW = (int) (40 * density);
            setMinimumWidth(minW);
            setLayoutParams(new LayoutParams(minW, LayoutParams.MATCH_PARENT));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();

            // Arrière-plan à l'appui.
            if (pressed) {
                bgPaint.setColor(0x33FFFFFF);
                canvas.drawRect(0, 0, w, h, bgPaint);
            }

            // Détermine l'état du bouton.
            boolean active = false;
            boolean disabled = false;
            if (editorView != null) {
                if (action.equals("nonprintable")) {
                    active = editorView.showNonPrintable;
                } else if (action.equals("ligatures")) {
                    active = editorView.fontLigatures;
                } else if (action.equals("undo")) {
                    disabled = !editorView.getSession().getUndoManager().canUndo();
                } else if (action.equals("redo")) {
                    disabled = !editorView.getSession().getUndoManager().canRedo();
                }
            }

            // Couleur du texte.
            int accentColor = editorView != null ? editorView.getTheme().keyword : 0xFF6750A4;
            int normalColor = editorView != null
                ? EditorView.applyAlphaToColor(editorView.getTheme().gutterText, 0.8f)
                : 0xFFD4D4D4;
            int disabledColor = editorView != null
                ? EditorView.applyAlphaToColor(editorView.getTheme().gutterText, 0.25f)
                : 0xFF555555;

            if (disabled) {
                textPaint.setColor(disabledColor);
            } else if (active) {
                textPaint.setColor(accentColor);
            } else {
                textPaint.setColor(normalColor);
            }
            textPaint.setFakeBoldText(true);

            float x = w / 2f;
            float y = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2;
            canvas.drawText(label, x, y, textPaint);
            textPaint.setFakeBoldText(false);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    pressed = false;
                    invalidate();
                    // performClick pour l'accessibilité (talkback) + lint.
                    performClick();
                    handleAction();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    invalidate();
                    return true;
            }
            return false;
        }

        private void handleAction() {
            if (editorView == null) return;
            switch (action) {
                case "undo":
                    if (editorView.getSession().getUndoManager().canUndo()) {
                        editorView.getSession().undo();
                        editorView.notifyTextChanged();
                    }
                    break;
                case "redo":
                    if (editorView.getSession().getUndoManager().canRedo()) {
                        editorView.getSession().redo();
                        editorView.notifyTextChanged();
                    }
                    break;
                case "font_plus":
                    editorView.increaseFontSize();
                    break;
                case "font_minus":
                    editorView.decreaseFontSize();
                    break;
                case "nonprintable":
                    editorView.setShowNonPrintable(!editorView.showNonPrintable);
                    invalidate();
                    break;
                case "ligatures":
                    editorView.setFontLigatures(!editorView.fontLigatures);
                    invalidate();
                    break;
            }
            // Invalide tous les boutons pour rafraîchir les états annuler/rétablir.
            EditorBarTools parent = (EditorBarTools) getParent();
            if (parent != null) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    parent.getChildAt(i).invalidate();
                }
            }
        }
    }
}
