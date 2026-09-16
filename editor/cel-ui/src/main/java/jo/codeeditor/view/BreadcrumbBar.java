package jo.codeeditor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Breadcrumb navigation bar — shows the current scope chain
 * (file › class › method) above the editor, like IntelliJ/CodeAssist.
 *
 * <p>Tracks the caret position and updates the breadcrumb segments
 * via the {@link jo.codeeditor.lang.SymbolProvider} if available.
 *
 * <p>v3.3.2 — inspired by CodeAssist's EditorBreadcrumbBar.
 *
 * @since v3.3.2
 */
public class BreadcrumbBar extends View {

    private String[] segments = new String[0];
    private final Paint textPaint;
    private final Paint chevronPaint;
    private final float density;
    private int barHeightPx;
    private EditorView editorView;
    private Runnable updateRunnable;
    private long lastUpdate = 0;
    private static final long DEBOUNCE_MS = 200;

    public BreadcrumbBar(Context context) {
        this(context, null);
    }

    public BreadcrumbBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        barHeightPx = (int) (28 * density);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(12 * density);
        textPaint.setTypeface(Typeface.MONOSPACE);
        chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        chevronPaint.setTextSize(10 * density);
        setWillNotDraw(false);
    }

    /**
     * Binds this breadcrumb bar to an EditorView. The bar will track
     * caret position and update segments via the SymbolProvider.
     */
    public void bind(EditorView view) {
        this.editorView = view;
        // v3.4.0: Use addOnSelectionChangedListener (not set) so we don't
        // replace the primary listener (e.g. MainActivity's status bar).
        view.addOnSelectionChangedListener((line, col, isCursor) -> {
            long now = System.currentTimeMillis();
            if (now - lastUpdate < DEBOUNCE_MS) return;
            lastUpdate = now;
            postDelayed(this::updateSegments, DEBOUNCE_MS);
        });
    }

    /** Updates the breadcrumb segments from the current language's SymbolProvider. */
    private void updateSegments() {
        if (editorView == null || editorView.getSession() == null) {
            segments = new String[0];
            invalidate();
            return;
        }
        String fileName = editorView.getSession().getLanguage() != null
            ? editorView.getSession().getLanguage() : "file";
        // Try to get symbols from the Language SPI.
        jo.codeeditor.lang.Language lang = editorView.getLanguage();
        if (lang != null && lang.getSymbolProvider() != null) {
            try {
                java.util.List<jo.codeeditor.lang.Symbol> symbols =
                    lang.getSymbolProvider().symbols(editorView.getSession().getText());
                int caret = editorView.getSession().getSelection().start;
                // Find enclosing symbols (offset <= caret < end).
                java.util.List<jo.codeeditor.lang.Symbol> enclosing = new java.util.ArrayList<>();
                for (jo.codeeditor.lang.Symbol s : symbols) {
                    if (s.offset <= caret) {
                        enclosing.add(s);
                    }
                }
                // Sort by offset (outermost first).
                enclosing.sort((a, b) -> Integer.compare(a.offset, b.offset));
                // Build segments: file › class › method.
                java.util.List<String> segs = new java.util.ArrayList<>();
                segs.add(fileName);
                for (jo.codeeditor.lang.Symbol s : enclosing) {
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

    /** Sets segments directly (for manual use without SymbolProvider). */
    public void setSegments(String[] segs) {
        this.segments = segs != null ? segs : new String[0];
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(barHeightPx, MeasureSpec.EXACTLY));
    }

    // v3.34.0: preallocated paints — onDraw allocated two Paint objects
    // per frame (lint DrawAllocation), forcing GC churn on low-end devices
    // whenever the breadcrumb was visible.
    private final Paint bgPaint = new Paint();
    private final Paint borderPaint = new Paint();

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (segments.length == 0) return;
        float padX = 12 * density;
        float padY = (getHeight() - textPaint.getTextSize()) * 0.5f
            + textPaint.getTextSize() * 0.35f;
        // Background.
        bgPaint.setColor(0xFF1E1E1E); // dark
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        // Bottom border.
        borderPaint.setColor(0xFF323232);
        borderPaint.setStrokeWidth(1f);
        canvas.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1, borderPaint);

        float x = padX;
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                // Draw chevron ›.
                chevronPaint.setColor(0xFF808080);
                canvas.drawText("\u203A", x, padY, chevronPaint);
                x += chevronPaint.measureText("\u203A") + 6 * density;
            }
            // Draw segment text.
            boolean isLast = (i == segments.length - 1);
            textPaint.setColor(isLast ? 0xFFD4D4D4 : 0xFF858585);
            textPaint.setTypeface(isLast ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                : Typeface.MONOSPACE);
            canvas.drawText(segments[i], x, padY, textPaint);
            x += textPaint.measureText(segments[i]) + 6 * density;
        }
    }
}
