package jo.codeeditor.view;

import jo.codeeditor.document.EditorDocument;

import java.util.ArrayList;
import java.util.List;

/**
 * v3.36.0 — The context handed to every {@link EditorDecorationPainter}
 * once per render pass (roadmap item 9).
 *
 * <p>A painter reads the viewport/window it is asked to decorate and
 * pushes decorations via the {@code add*} methods. Decorations outside the
 * visible line range are harmless (they are simply not drawn) but wasteful
 * — good painters only decorate the visible window.</p>
 *
 * <p><b>Threading:</b> {@code paint} runs on the UI thread inside the
 * render pass — keep it fast and allocation-light. A painter that throws
 * is REMOVED from the host instead of crashing the editor (CodeAssist
 * {@code EditorPainterHost} policy).</p>
 *
 * @since v3.36.0
 */
public final class EditorPaintContext {

    private final EditorView view;
    private final EditorDocument doc;
    private final int firstVisibleLine;
    private final int lastVisibleLine;
    private final float density;

    final List<EditorDecorations.TextDecoration> textDecorations = new ArrayList<>(0);
    final List<EditorDecorations.GutterMark> gutterMarks = new ArrayList<>(0);
    final List<EditorDecorations.PluginInlay> pluginInlays = new ArrayList<>(0);

    EditorPaintContext(EditorView view, EditorDocument doc,
            int firstVisibleLine, int lastVisibleLine, float density) {
        this.view = view;
        this.doc = doc;
        this.firstVisibleLine = firstVisibleLine;
        this.lastVisibleLine = lastVisibleLine;
        this.density = density;
    }

    /** The document being rendered (read-only use). */
    public EditorDocument getDocument() {
        return doc;
    }

    /** First document line of the (fold/wrap-aware) visible window. */
    public int getFirstVisibleLine() {
        return firstVisibleLine;
    }

    /** Last document line of the (fold/wrap-aware) visible window. */
    public int getLastVisibleLine() {
        return lastVisibleLine;
    }

    /** Screen density (px per dp). */
    public float getDensity() {
        return density;
    }

    /** Rendered line height in px (from the editor metrics). */
    public float getLineHeight() {
        return view.metrics.getLineHeight();
    }

    /** Monospace char width in px (from the editor metrics). */
    public float getCharWidth() {
        return view.metrics.getCharWidth();
    }

    /** Adds a colored range decoration (underline/box/strike) in the text area. */
    public void addTextDecoration(int start, int end, int color, int style) {
        if (end <= start) return;
        if (doc != null) {
            int len = doc.length();
            if (start < 0 || start >= len) return;
            if (end > len) end = len;
        }
        textDecorations.add(new EditorDecorations.TextDecoration(start, end, color, style));
    }

    /** Adds a colored bar on the gutter for {@code line}. */
    public void addGutterMark(int line, int color) {
        if (line < 0) return;
        gutterMarks.add(new EditorDecorations.GutterMark(line, color));
    }

    /** Adds phantom text after the line containing {@code offset}. */
    public void addPluginInlay(int offset, String text, int color) {
        if (offset < 0 || text == null || text.isEmpty()) return;
        pluginInlays.add(new EditorDecorations.PluginInlay(offset, text, color));
    }
}
