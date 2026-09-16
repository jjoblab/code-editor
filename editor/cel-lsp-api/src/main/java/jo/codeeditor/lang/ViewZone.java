package jo.codeeditor.lang;

/**
 * A view zone — an inline UI gap inserted between lines. Returned by
 * {@link ViewZoneProvider}.
 *
 * <p>The {@code heightPx} is the pixel height of the gap. The editor
 * reserves this space between {@code afterLine} and the next line, and
 * renders the {@code content} (a custom Android {@link android.view.View})
 * in the gap.
 *
 * @since v2.0.0
 */
public final class ViewZone {

    /** The line after which the gap is inserted (0-based). */
    public final int afterLine;
    /** The gap height in pixels. */
    public final int heightPx;
    /** The custom View to render in the gap. May be null for a blank gap. */
    public final android.view.View content;
    /** A unique ID for the zone (used for update/remove). */
    public final int id;

    public ViewZone(int afterLine, int heightPx, android.view.View content, int id) {
        this.afterLine = afterLine;
        this.heightPx = heightPx;
        this.content = content;
        this.id = id;
    }
}
