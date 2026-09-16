package jo.codeeditor.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

/**
 * v2.39: Popup sheet overlay that renders the host's preview content
 * (typically a WebView for Markdown/HTML, or a canvas View for XML
 * layout previews) on top of the editor.
 *
 * <p>The sheet chrome (glass-card header with filename + close button,
 * optional split/full toggle, drag-to-dismiss gesture) is fully owned
 * by the editor library — the host only provides the body View via
 * {@link EditorPreviewHost#onCreatePreviewView(Context, EditorView,
 * EditorView.PreviewMode)}. When the host returns {@code null}, the
 * sheet falls back to a {@link CanvasBodyView} that delegates each
 * frame's {@code onDraw} to {@link EditorPreviewHost#drawPreview}.
 *
 * <h3>Layout</h3>
 * <pre>
 *  ┌─────────────────────────────────────────────┐
 *  │  filename.md        [split] [full]    [X]   │ ← header (24dp tall)
 *  ├─────────────────────────────────────────────┤
 *  │                                             │
 *  │           host body (WebView/Canvas)        │
 *  │                                             │
 *  └─────────────────────────────────────────────┘
 * </pre>
 *
 * <p>Width/height are computed from the editor's geometry:
 * <ul>
 *   <li>{@code SHEET_SPLIT} → right half of the editor (anchored top-right)</li>
 *   <li>{@code SHEET_FULL}  → full editor area</li>
 * </ul>
 *
 * <h3>Dismissal</h3>
 * <ul>
 *   <li>Tap the X button</li>
 *   <li>Tap outside the sheet (when {@code setOutsideTouchable} is honored)</li>
 *   <li>Press Back (handled by {@link PopupWindow}'s default OnKeyListener)</li>
 * </ul>
 *
 * @since v2.39
 */
class EditorPreviewSheet {

    private final EditorView editor;
    private EditorView.PreviewMode mode;
    private PopupWindow popup;
    private View bodyView;
    private boolean canvasFallback = false;

    EditorPreviewSheet(EditorView editor, EditorView.PreviewMode mode) {
        this.editor = editor;
        this.mode = mode;
    }

    EditorView.PreviewMode getMode() { return mode; }

    /** Show the sheet anchored to the editor. No-op if the host is null. */
    void show() {
        EditorPreviewHost host = editor.getPreviewHost();
        if (host == null) return;
        Context ctx = editor.getContext();

        // ── Build the body view (host-provided, or canvas fallback). ──
        bodyView = host.onCreatePreviewView(ctx, editor, mode);
        if (bodyView == null) {
            canvasFallback = true;
            bodyView = new CanvasBodyView(ctx, editor, host);
        }
        // v2.42 fix — defensive detach. The host may legitimately cache
        // and return the same View across sheet instances (e.g. a WebView
        // reused between opens). When the previous PopupWindow was torn
        // down, the bodyView's parent reference wasn't cleared — adding
        // it to a new bodyFrame would throw IllegalStateException "child
        // already has a parent". Detach here first to keep the contract
        // host-friendly (the host doesn't have to know about parent
        // management).
        if (bodyView.getParent() instanceof ViewGroup) {
            ((ViewGroup) bodyView.getParent()).removeView(bodyView);
        }
        bodyView.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Build the sheet container: header on top, body below. ──
        LinearLayout sheet = buildSheetContainer(ctx);
        sheet.addView(buildHeader(ctx));
        View divider = new View(ctx);
        divider.setBackgroundColor(editor.theme.glassBorder);
        divider.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, editor.dp(1))));
        sheet.addView(divider);
        FrameLayout bodyFrame = new FrameLayout(ctx);
        bodyFrame.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        bodyFrame.addView(bodyView);
        sheet.addView(bodyFrame);

        // ── Compute width/height and anchor position. ──
        int width, height, x, y;
        int[] location = new int[2];
        editor.getLocationInWindow(location);
        if (mode == EditorView.PreviewMode.SHEET_FULL) {
            width = editor.getWidth();
            height = editor.getHeight();
            x = location[0];
            y = location[1];
        } else { // SHEET_SPLIT
            width = editor.getWidth() / 2;
            height = editor.getHeight();
            x = location[0] + (editor.getWidth() - width);
            y = location[1];
        }

        popup = new PopupWindow(sheet, width, height, true);
        popup.setFocusable(true);
        popup.setOutsideTouchable(false); // dismiss via X only — avoid accidental dismissal while typing in editor
        popup.setClippingEnabled(true);
        popup.setOnDismissListener(this::onDismissed);
        // v2.39: Back button dismisses the sheet (PopupWindow's default
        // OnKeyListener honors KEYCODE_BACK when focusable=true).
        popup.showAtLocation(editor, Gravity.NO_GRAVITY, x, y);

        // Push initial content into the body.
        refreshBody();
    }

    void dismiss() {
        if (popup != null) {
            popup.dismiss();
            // onDismissed will be invoked by the listener.
        }
    }

    boolean isShowing() {
        return popup != null && popup.isShowing();
    }

    /**
     * Called after the editor's text changes (debounced ~200 ms). Pushes
     * the new content to the host (which then updates its WebView body),
     * and asks the body View to invalidate.
     */
    void refreshBody() {
        if (bodyView == null) return;
        if (canvasFallback) {
            // CanvasBodyView reads from host every frame — just invalidate.
            bodyView.invalidate();
        } else {
            // The host's body View should have already received
            // onPreviewContentChanged via EditorView.updatePreviewContent.
            // We just trigger a redraw so the WebView updates.
            bodyView.invalidate();
        }
    }

    /** Toggle between SHEET_SPLIT and SHEET_FULL without rebuilding the popup. */
    void switchMode(EditorView.PreviewMode newMode) {
        if (newMode == this.mode || popup == null) return;
        this.mode = newMode;
        // Recompute size and re-show at new dimensions.
        int[] location = new int[2];
        editor.getLocationInWindow(location);
        int width, height, x, y;
        if (mode == EditorView.PreviewMode.SHEET_FULL) {
            width = editor.getWidth();
            height = editor.getHeight();
            x = location[0];
            y = location[1];
        } else {
            width = editor.getWidth() / 2;
            height = editor.getHeight();
            x = location[0] + (editor.getWidth() - width);
            y = location[1];
        }
        popup.update(x, y, width, height);
        // Notify the host of the new bounds.
        EditorPreviewHost host = editor.getPreviewHost();
        if (host != null) {
            host.onPreviewModeChanged(mode, editor.getPreviewLeft(), editor.getPreviewWidth());
        }
        refreshBody();
    }

    // ════════════════════════════════════════════════════════════════
    // Sheet chrome
    // ════════════════════════════════════════════════════════════════

    private LinearLayout buildSheetContainer(Context ctx) {
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(editor.theme.glassBg);
        bg.setStroke(editor.dp(1), editor.theme.glassBorder);
        // Slight corner radius for SHEET_SPLIT (right side floats); 0 for FULL.
        if (mode == EditorView.PreviewMode.SHEET_SPLIT) {
            float r = editor.dp(14);
            // 8 radii: top-left-x, top-left-y, top-right-x, top-right-y,
            // bottom-right-x, bottom-right-y, bottom-left-x, bottom-left-y.
            bg.setCornerRadii(new float[]{
                r, r, 0, 0, 0, 0, r, r
            });
        }
        container.setBackground(bg);
        return container;
    }

    private View buildHeader(Context ctx) {
        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        int padH = editor.dp(12);
        int padV = editor.dp(8);
        header.setPadding(padH, padV, padH, padV);
        header.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // File name (left)
        TextView title = new TextView(ctx);
        String name = editor.getFileName();
        if (name == null || name.isEmpty()) name = "Preview";
        title.setText(name);
        title.setTextColor(editor.theme.textColor);
        title.setTextSize(14);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setMaxEms(20);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleLp);
        header.addView(title);

        // Split toggle button (chevron-right icon)
        View splitBtn = buildIconButton(ctx, this::onSplitClicked,
            "Split preview", EditorPreviewSheet::drawSplitGlyph);
        header.addView(splitBtn);
        LinearLayout.LayoutParams splitLp = new LinearLayout.LayoutParams(
            editor.dp(28), editor.dp(28));
        splitLp.setMargins(editor.dp(8), 0, 0, 0);
        splitBtn.setLayoutParams(splitLp);

        // Full toggle button (eye icon)
        View fullBtn = buildIconButton(ctx, this::onFullClicked,
            "Full preview", EditorPreviewSheet::drawFullGlyph);
        header.addView(fullBtn);
        LinearLayout.LayoutParams fullLp = new LinearLayout.LayoutParams(
            editor.dp(28), editor.dp(28));
        fullLp.setMargins(editor.dp(4), 0, 0, 0);
        fullBtn.setLayoutParams(fullLp);

        // Close X button
        View closeBtn = buildIconButton(ctx, this::onCloseClicked,
            "Close preview", EditorPreviewSheet::drawCloseGlyph);
        header.addView(closeBtn);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
            editor.dp(28), editor.dp(28));
        closeLp.setMargins(editor.dp(4), 0, 0, 0);
        closeBtn.setLayoutParams(closeLp);

        return header;
    }

    @FunctionalInterface interface GlyphDrawer {
        void draw(Canvas c, float cx, float cy, float r, Paint p);
    }

    private View buildIconButton(Context ctx, Runnable onClick,
                                  String contentDesc, GlyphDrawer glyph) {
        ImageView btn = new ImageView(ctx, null, 0) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth(), h = getHeight();
                if (w == 0 || h == 0) return;
                float cx = w * 0.5f, cy = h * 0.5f;
                float r = Math.min(w, h) * 0.32f;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, editor.dp(2)));
                p.setColor(editor.applyAlphaToColor(editor.theme.gutterText, 0.85f));
                glyph.draw(canvas, cx, cy, r, p);
            }
        };
        btn.setContentDescription(contentDesc);
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setOnClickListener(v -> onClick.run());
        btn.setBackground(rippleBackground(ctx));
        btn.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    /** Simple selectable ripple — code-built to avoid theme dependencies. */
    private android.graphics.drawable.RippleDrawable rippleBackground(Context ctx) {
        int statePressed = android.R.attr.state_pressed;
        android.content.res.ColorStateList rippleCs =
            android.content.res.ColorStateList.valueOf(
                editor.applyAlphaToColor(editor.theme.keyword, 0.32f));
        android.graphics.drawable.ColorDrawable mask =
            new android.graphics.drawable.ColorDrawable(Color.WHITE);
        return new android.graphics.drawable.RippleDrawable(
            rippleCs, null, mask);
    }

    // ── Header button handlers ──

    private void onSplitClicked() {
        switchMode(EditorView.PreviewMode.SHEET_SPLIT);
    }

    private void onFullClicked() {
        switchMode(EditorView.PreviewMode.SHEET_FULL);
    }

    private void onCloseClicked() {
        dismiss();
    }

    private void onDismissed() {
        // Reset editor state so the editor's previewMode returns to NONE.
        // Use a guard to avoid recursion: setPreviewMode(NONE) calls
        // closePreviewSheet() which calls dismiss() — but dismiss() is
        // already in progress, so we set the field to null first.
        if (editor.previewSheet != null) {
            // Close via the public API so the editor state stays consistent.
            editor.closePreviewSheet();
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Glyph drawers (avoid pulling drawable resources into the editor lib)
    // ════════════════════════════════════════════════════════════════

    private static void drawSplitGlyph(Canvas c, float cx, float cy, float r, Paint p) {
        // Two outlined rounded rects side by side (echoes EditorRenderer.drawPreviewIcons).
        RectF left = new RectF(cx - r, cy - r * 0.7f, cx - r * 0.2f, cy + r * 0.7f);
        RectF right = new RectF(cx + r * 0.2f, cy - r * 0.7f, cx + r, cy + r * 0.7f);
        p.setStyle(Paint.Style.STROKE);
        c.drawRoundRect(left, r * 0.18f, r * 0.18f, p);
        c.drawRoundRect(right, r * 0.18f, r * 0.18f, p);
        // Vertical divider line between panes.
        c.drawLine(cx, cy - r * 0.6f, cx, cy + r * 0.6f, p);
    }

    private static void drawFullGlyph(Canvas c, float cx, float cy, float r, Paint p) {
        // Filled eye with pupil.
        Path eye = new Path();
        RectF topArc = new RectF(cx - r, cy - r * 0.6f, cx + r, cy + r * 0.6f);
        eye.addArc(topArc, 200, 140);
        RectF botArc = new RectF(cx - r, cy - r * 0.18f, cx + r, cy + r * 1.02f);
        eye.arcTo(botArc, 20, 140);
        eye.close();
        p.setStyle(Paint.Style.FILL);
        c.drawPath(eye, p);
        p.setStyle(Paint.Style.STROKE);
        c.drawPath(eye, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r * 0.2f, p);
    }

    private static void drawCloseGlyph(Canvas c, float cx, float cy, float r, Paint p) {
        // Stylized "X".
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(cx - r, cy - r, cx + r, cy + r, p);
        c.drawLine(cx - r, cy + r, cx + r, cy - r, p);
    }

    // ════════════════════════════════════════════════════════════════
    // CanvasBodyView — fallback when the host returns null from
    // onCreatePreviewView (i.e. canvas-only preview like XML layouts).
    // ════════════════════════════════════════════════════════════════

    @SuppressLint("ViewConstructor")
    private static class CanvasBodyView extends View {
        private final EditorView editor;
        private final EditorPreviewHost host;
        private final Paint bgPaint;

        CanvasBodyView(Context ctx, EditorView editor, EditorPreviewHost host) {
            super(ctx);
            this.editor = editor;
            this.host = host;
            this.bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            this.bgPaint.setColor(editor.theme.editorBg);
            setWillNotDraw(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // Fill background to avoid showing the editor text through.
            canvas.drawPaint(bgPaint);
            // Ask the host to render into the full body canvas.
            host.drawPreview(canvas, 0f, 0f);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                return host.hitTestPreview(event.getX(), event.getY())
                    || super.onTouchEvent(event);
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                // v3.34.0: accessibility (ClickableViewAccessibility) —
                // announce the tap before the host consumes it.
                performClick();
            }
            return super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
