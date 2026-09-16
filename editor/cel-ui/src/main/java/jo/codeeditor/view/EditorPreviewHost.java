package jo.codeeditor.view;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

/**
 * v3.31.0: Host-side contract for preview functionality.
 *
 * <p>The {@link EditorView} no longer depends on preview modules directly.
 * Instead, the host app implements this interface and registers it via
 * {@link EditorView#setPreviewHost(EditorPreviewHost)}.
 *
 * <p>This decoupling allows the editor library to be used without any
 * preview modules, and allows the preview modules to evolve independently.
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * EditorView editor = findViewById(R.id.editor);
 * editor.setPreviewHost(new MyPreviewHost(this));
 * editor.setFileName("layout.xml");  // triggers canPreview() check
 * editor.setPreviewMode(EditorView.PreviewMode.SPLIT);
 * }</pre>
 *
 * <p>The host is responsible for:
 * <ul>
 *   <li>Detecting whether a file is previewable (XML layout, Markdown, HTML)</li>
 *   <li>Rendering the preview when the editor enters SPLIT/FULL mode</li>
 *   <li>Drawing the preview onto the Canvas (called from EditorRenderer)</li>
 *   <li>Updating the preview when the editor text changes (debounced)</li>
 *   <li>Hit-testing taps in the preview pane</li>
 * </ul>
 *
 * @since v3.31.0
 */
public interface EditorPreviewHost {

    /**
     * Returns true if the given file name can be previewed by this host.
     *
     * <p>Called by {@link EditorView#setFileName(String)} to determine
     * whether to draw the preview icons in the top-right corner.
     *
     * @param fileName the current file name (may be null)
     * @return true if the file is previewable (.xml, .md, .html, etc.)
     */
    boolean canPreview(String fileName);

    /**
     * Called when the editor enters or leaves preview mode.
     *
     * <p>The host should show/hide its preview surface and start/stop
     * rendering. For XML layouts, the host typically inflates the XML
     * and draws the resulting View tree. For Markdown/HTML, the host
     * typically shows a WebView overlay.
     *
     * @param mode      the new preview mode (NONE, SPLIT, or FULL)
     * @param previewLeft the X coordinate where the preview pane starts
     * @param previewWidth the width of the preview pane in pixels
     */
    void onPreviewModeChanged(EditorView.PreviewMode mode, int previewLeft, int previewWidth);

    /**
     * Called when the editor text has changed (debounced ~200ms).
     *
     * <p>The host should re-render its preview to reflect the new text.
     * For XML layouts, this means re-inflating the XML.
     *
     * @param text the current editor text
     */
    void onPreviewContentChanged(CharSequence text);

    /**
     * Draws the preview onto the given Canvas.
     *
     * <p>Called every frame by {@link jo.codeeditor.view.EditorRenderer}
     * when preview mode is active. The host should draw its preview
     * content at the given offset.
     *
     * <p>For XML layouts, this typically calls
     * {@code NativeXmlPreviewRenderer.draw(canvas, offsetX, offsetY)}.
     * For Markdown/HTML, this is usually a no-op (the WebView handles
     * its own drawing).
     *
     * @param canvas   the Canvas to draw on
     * @param offsetX  horizontal offset (preview pane left edge)
     * @param offsetY  vertical offset (usually 0)
     */
    void drawPreview(Canvas canvas, float offsetX, float offsetY);

    /**
     * Returns true if the preview has content ready to draw.
     *
     * <p>Used by {@link EditorView#isXmlPreviewActive()} to determine
     * whether to call {@link #drawPreview(Canvas, float, float)}.
     */
    boolean hasPreviewContent();

    /**
     * Hit-tests a point in the preview pane.
     *
     * <p>Called when the user taps inside the preview area. The host
     * should select the corresponding view (for XML layouts) or
     * ignore the tap (for WebView-based previews).
     *
     * @param x the X coordinate relative to the preview pane
     * @param y the Y coordinate relative to the preview pane
     * @return true if a view was hit and selected
     */
    boolean hitTestPreview(float x, float y);

    /**
     * v2.39: Optional hook for view-based preview (WebView for Markdown/HTML).
     *
     * <p>When the user taps the split/fullscreen preview badge for a
     * {@code .md}/{@code .html} file, the editor opens a popup sheet
     * (see {@link EditorView#openPreview(boolean)}) and asks the host
     * to provide an Android {@link View} that renders the preview
     * content. The host typically returns a {@code WebView} configured
     * for Markdown rendering, or a custom canvas View.
     *
     * <p>The sheet itself (chrome, header, close button, drag-to-dismiss)
     * is owned by the editor library — the host only owns the body View.
     * The editor calls {@link #onPreviewContentChanged(CharSequence)}
     * (debounced ~200 ms) so the host can re-render its body.
     *
     * <p>Returning {@code null} (the default) tells the editor to fall
     * back to {@link #drawPreview(Canvas, float, float)} for canvas-only
     * hosts (e.g. native XML layout preview). The sheet body then hosts
     * a {@code View} whose {@code onDraw} delegates to {@code drawPreview}.
     *
     * <p>Hosts that override this method should ensure the returned View
     * is reusable across {@code onPreviewContentChanged} calls — i.e.
     * update its content in-place rather than re-inflating.
     *
     * @param ctx    the application context for inflating new views
     * @param editor the editor view the sheet is anchored to
     * @param mode   the requested preview mode (one of
     *               {@link EditorView.PreviewMode#SHEET_SPLIT} or
     *               {@link EditorView.PreviewMode#SHEET_FULL})
     * @return a fully-initialized preview View, or {@code null} to
     *         fall back to canvas drawing via {@link #drawPreview}
     * @since v2.39
     */
    default View onCreatePreviewView(Context ctx, EditorView editor,
                                     EditorView.PreviewMode mode) {
        return null;
    }
}
