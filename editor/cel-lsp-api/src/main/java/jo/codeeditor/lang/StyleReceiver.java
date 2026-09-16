package jo.codeeditor.lang;

/**
 * Receives style updates from an {@link Analyzer}. The analyzer calls
 * {@link #onStylesUpdated} on a worker thread; the editor's implementation
 * posts the update to the UI thread and invalidates the affected lines.
 *
 * @since v2.0.0
 */
public interface StyleReceiver {

    /**
     * Called by the analyzer when a range of lines has been re-tokenized.
     *
     * @param startLine the first updated line (0-based, inclusive)
     * @param endLine   the last updated line (0-based, inclusive)
     */
    void onStylesUpdated(int startLine, int endLine);

    /**
     * Called by the analyzer when the code blocks (folding regions) have
     * been recomputed.
     */
    void onBlocksUpdated();
}
