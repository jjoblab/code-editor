package jo.codeeditor.view.chrome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Barre de symboles/actions placée au-dessus du clavier IME, comme
 * l'EditorSymbolBar de CodeAssist. Donne un accès rapide aux symboles de
 * code et aux actions de l'éditeur sans fermer le clavier.
 *
 * <p>Inspirée de l'EditorSymbolBar de CodeAssist.
 * <ul>
 *   <li>Touches épinglées : Tab, //, ↑, ↓, Dup</li>
 *   <li>Symboles défilants : { } ( ) ; = . , " ' : &lt; &gt; / * [ ] + - &amp; | ! ? @ # _ % \</li>
 * </ul>
 *
 * <p>Utilise {@code onTouchEvent} brut (PAS {@code setOnClickListener}) pour
 * que l'éditeur garde le focus et que l'IME reste ouvert — comme
 * CodeAssist.
 */
public class SymbolBarView extends LinearLayout {

    /** Écouteur des touchers sur la barre de symboles. */
    public interface OnSymbolTap {
        void onSymbol(String symbol);
        void onAction(String actionId);
    }

    private OnSymbolTap listener;
    private final Paint textPaint;
    private final Paint bgPaint;
    private final float density;
    private final int barHeightPx;

    // Touches par défaut : actions épinglées + symboles défilants.
    private static final String[] PINNED_KEYS = {"Tab", "//", "↑", "↓", "Dup"};
    private static final String[] PINNED_ACTIONS = {"tab", "comment", "move_up", "move_down", "duplicate"};
    private static final String[] SYMBOLS = {
        "{", "}", "(", ")", ";", "=", ".", ",", "\"", "'", ":",
        "<", ">", "/", "*", "[", "]", "+", "-", "&", "|", "!", "?",
        "@", "#", "_", "%", "\\"
    };

    public SymbolBarView(Context context) {
        this(context, null);
    }

    public SymbolBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        density = getResources().getDisplayMetrics().density;
        barHeightPx = (int) (38 * density);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(14 * density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        bgPaint = new Paint();
        buildKeys();
    }

    private void buildKeys() {
        // Touches épinglées.
        for (int i = 0; i < PINNED_KEYS.length; i++) {
            addKey(PINNED_KEYS[i], PINNED_ACTIONS[i], true);
        }
        // Séparateur.
        View div = new View(getContext());
        div.setLayoutParams(new LayoutParams(1, (int) (24 * density)));
        div.setBackgroundColor(0xFF3A3A3A);
        addView(div);
        // Symboles défilants.
        for (String sym : SYMBOLS) {
            addKey(sym, sym, false);
        }
    }

    private void addKey(String label, String action, boolean pinned) {
        SymbolKey key = new SymbolKey(getContext(), label, action, pinned);
        addView(key);
    }

    public void setOnSymbolTap(OnSymbolTap listener) {
        this.listener = listener;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(barHeightPx, MeasureSpec.EXACTLY));
    }

    /** Une touche de la barre de symboles. */
    private class SymbolKey extends View {
        private final String label;
        private final String action;
        private final boolean pinned;
        private boolean pressed = false;

        SymbolKey(Context ctx, String label, String action, boolean pinned) {
            super(ctx);
            this.label = label;
            this.action = action;
            this.pinned = pinned;
            int minW = (int) (36 * density);
            setMinimumWidth(minW);
            setLayoutParams(new LayoutParams(minW, LayoutParams.MATCH_PARENT));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            // Arrière-plan.
            if (pressed) {
                bgPaint.setColor(0x33FFFFFF);
                canvas.drawRect(0, 0, w, h, bgPaint);
            }
            // Texte.
            textPaint.setColor(pinned ? 0xFF6750A4 : 0xFFD4D4D4); // accent pour les épinglées, gris clair pour les symboles
            float x = w / 2f;
            float y = h / 2f - (textPaint.ascent() + textPaint.descent()) / 2;
            canvas.drawText(label, x, y, textPaint);
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
                    if (listener != null) {
                        if (pinned) {
                            listener.onAction(action);
                        } else {
                            listener.onSymbol(action);
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false;
                    invalidate();
                    return true;
            }
            return false;
        }
    }
}
