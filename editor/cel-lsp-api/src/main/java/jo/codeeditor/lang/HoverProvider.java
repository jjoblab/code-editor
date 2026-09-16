package jo.codeeditor.lang;


/**
 * Provides hover/quick-doc content for the symbol at a given offset.
 * The editor calls {@link #hover} on a worker thread (off the UI thread
 * to avoid ANRs — Sora Editor's bug #845).
 *
 * @since v2.0.0
 */
public interface HoverProvider {

    /**
     * Returns the hover content for the symbol at the given offset, or
     * {@code null} if no hover is available.
     *
     * @param text   the full document text
     * @param offset the symbol offset
     * @return the hover content, or {@code null}
     */
    HoverContent hover(CharSequence text, int offset);
}
