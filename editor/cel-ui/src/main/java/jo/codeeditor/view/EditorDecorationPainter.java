package jo.codeeditor.view;

/**
 * v3.36.0 — The plugin painter SPI (roadmap item 9, port of CodeAssist
 * v3.20's {@code EditorPainter}).
 *
 * <p>Register implementations on an editor via
 * {@code EditorView.getPainterHost().register(painter)} — or ship them as
 * Java {@link java.util.ServiceLoader} services
 * ({@code META-INF/services/jo.codeeditor.view.EditorDecorationPainter})
 * and call {@link EditorPainterHost#loadFromClasspath()}.</p>
 *
 * <p>{@link #paint(EditorPaintContext)} runs once per render pass on the
 * UI thread; decorate only the visible window and keep allocations light.
 * A painter that throws is removed from the host so a broken plugin can
 * never crash the editor (CodeAssist {@code EditorPainterHost} policy).
 * </p>
 *
 * <p>Example — underline every TODO:</p>
 * <pre>{@code
 * public class TodoPainter implements EditorDecorationPainter {
 *     public void paint(EditorPaintContext ctx) {
 *         EditorDocument doc = ctx.getDocument();
 *         for (int line = ctx.getFirstVisibleLine();
 *                 line <= ctx.getLastVisibleLine(); line++) {
 *             String text = doc.lineText(line);
 *             int i = text.indexOf("TODO");
 *             if (i >= 0) {
 *                 int start = doc.lineStart(line) + i;
 *                 ctx.addTextDecoration(start, start + 4,
 *                         0xFFFFB300, EditorDecorations.DecorationStyles.UNDERLINE);
 *             }
 *         }
 *     }
 * }
 * }</pre>
 *
 * @since v3.36.0
 */
public interface EditorDecorationPainter {

    /**
     * Optional stable id — used to deduplicate ServiceLoader-provided
     * painters. Defaults to the class name.
     */
    default String id() {
        return getClass().getName();
    }

    /**
     * Contributes decorations for the current render pass. Runs on the UI
     * thread inside the draw — keep it fast. Throwing here removes the
     * painter from its host.
     */
    void paint(EditorPaintContext ctx);
}
