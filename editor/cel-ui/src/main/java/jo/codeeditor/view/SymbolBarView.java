package jo.codeeditor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.util.Arrays;
import java.util.List;

/**
 * A symbol/action bar that sits above the IME keyboard, like CodeAssist's
 * EditorSymbolBar. Provides quick access to code symbols and editor actions
 * without dismissing the keyboard.
 *
 * <p>v3.2.0 — inspired by CodeAssist's EditorSymbolBar.
 * <ul>
 *   <li>Pinned keys: Tab, //, ↑, ↓, Dup</li>
 *   <li>Scrolling symbols: { } ( ) ; = . , " ' : &lt; &gt; / * [ ] + - &amp; | ! ? @ # _ % \</li>
 * </ul>
 *
 * <p>Uses raw {@code onTouchEvent} (NOT {@code setOnClickListener}) so the
 * editor keeps focus and the IME stays open — same as CodeAssist.
 *
 * @since v3.2.0
 */
public class SymbolBarView extends LinearLayout {

    /** Listener for symbol bar taps. */
    public interface OnSymbolTap {
        void onSymbol(String symbol);
        void onAction(String actionId);
    }

    private OnSymbolTap listener;
    private final Paint textPaint;
    private final Paint bgPaint;
    private final float density;
    private final int barHeightPx;

    // Default keys: pinned actions + scrolling symbols.
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
        // Pinned keys.
        for (int i = 0; i < PINNED_KEYS.length; i++) {
            addKey(PINNED_KEYS[i], PINNED_ACTIONS[i], true);
        }
        // Divider.
        View div = new View(getContext());
        div.setLayoutParams(new LayoutParams(1, (int) (24 * density)));
        div.setBackgroundColor(0xFF3A3A3A);
        addView(div);
        // Scrolling symbols.
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

    /** A single key in the symbol bar. */
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
            // Background.
            if (pressed) {
                bgPaint.setColor(0x33FFFFFF);
                canvas.drawRect(0, 0, w, h, bgPaint);
            }
            // Text.
            textPaint.setColor(pinned ? 0xFF6750A4 : 0xFFD4D4D4); // accent for pinned, light gray for symbols
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
                    // v3.34.0: performClick for accessibility (talkback) + lint.
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
