package jo.codeeditor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

/**
 * v3.20.0: Editor toolbar bar — sits directly above the EditorView, containing
 * editor control icons: Undo, Redo, A+, A−, ¶ (non-printable), == (ligatures).
 *
 * <p>Unlike the Canvas-drawn toolbar icons from v3.18.0, this is a real
 * Android {@link View} that lives in the layout (like {@link SymbolBarView}).
 * It connects directly to its {@link EditorView} — the host only needs to
 * place it in the layout and optionally toggle its visibility.
 *
 * <p>The bar reads the editor's theme for colors and calls the editor's
 * public API on tap. Toggle icons (¶, ==) sync their visual state from
 * the editor's fields.
 *
 * @since v3.20.0
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
     * Connects this bar to its EditorView. Must be called before the bar
     * is interacted with. The bar reads the editor's state for toggle
     * icons and calls the editor's API on tap.
     */
    public void setEditorView(EditorView editorView) {
        this.editorView = editorView;
    }

    private void buildButtons() {
        // Undo
        addBarButton("↶", "undo");
        // Redo
        addBarButton("↷", "redo");
        // Divider
        addDivider();
        // A+ (increase font size)
        addBarButton("A+", "font_plus");
        // A- (decrease font size)
        addBarButton("A−", "font_minus");
        // Divider
        addDivider();
        // ¶ (non-printable toggle)
        addBarButton("¶", "nonprintable");
        // == (ligatures toggle)
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
        // Draw background.
        bgPaint.setColor(editorView != null ? editorView.getTheme().gutterBg : 0xFF1E1E1E);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        // Bottom border.
        bgPaint.setColor(editorView != null ? editorView.getTheme().gutterBorder : 0xFF333333);
        canvas.drawRect(0, getHeight() - 1, getWidth(), getHeight(), bgPaint);
        super.dispatchDraw(canvas);
    }

    /** A single button in the toolbar. */
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

            // Background on press.
            if (pressed) {
                bgPaint.setColor(0x33FFFFFF);
                canvas.drawRect(0, 0, w, h, bgPaint);
            }

            // Determine button state.
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

            // Text color.
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
                    // v3.34.0: performClick for accessibility (talkback) + lint.
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
            // v3.20.1: Invalidate all buttons so undo/redo states update.
            EditorBarTools parent = (EditorBarTools) getParent();
            if (parent != null) {
                for (int i = 0; i < parent.getChildCount(); i++) {
                    parent.getChildAt(i).invalidate();
                }
            }
        }
    }
}
