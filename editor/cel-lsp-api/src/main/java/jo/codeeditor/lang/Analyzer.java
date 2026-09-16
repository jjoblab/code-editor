package jo.codeeditor.lang;


import jo.codeeditor.highlight.StyledLine;

import java.util.List;

/**
 * Incremental analyzer: produces {@link StyledLine}s (syntax highlighting),
 * code blocks (folding), and bracket matches from the document text.
 *
 * <p>The analyzer runs on a worker thread. It receives text edits via
 * {@link #onInsert} / {@link #onDelete} / {@link #onReplace} and pushes
 * updated spans to the editor via the {@link StyleReceiver}.
 *
 * <p>Implementations should use the <b>state-convergence algorithm</b>:
 * re-tokenize from the edited line forward, and stop when the new line's
 * entry state matches the stored state. This gives O(edited lines) per
 * edit instead of O(whole file).
 *
 * @since v2.0.0
 */
public interface Analyzer {

    /**
     * Sets the receiver that the analyzer pushes style updates to.
     * Called once on the UI thread when the analyzer is attached.
     */
    void setReceiver(StyleReceiver receiver);

    /**
     * Called when the document text changes. The analyzer should
     * re-tokenize the affected lines and push the updated spans to
     * the {@link StyleReceiver}.
     *
     * @param text      the full document text
     * @param editStart the offset where the edit started
     * @param editEnd   the offset where the edit ended (after the edit)
     * @param inserted  the text that was inserted (may be empty)
     */
    void onReplace(CharSequence text, int editStart, int editEnd, CharSequence inserted);

    /**
     * Called when the document is loaded or reset. The analyzer should
     * re-tokenize the whole document.
     *
     * @param text the full document text
     */
    void reset(CharSequence text);

    /**
     * Returns the {@link StyledLine} for the given line. Called on the UI
     * thread during {@code onDraw}. If the analyzer hasn't tokenized this
     * line yet, returns {@code null} (the editor falls back to plain text).
     *
     * @param line the 0-based line index
     * @return the styled line, or {@code null} if not yet available
     */
    StyledLine styledLine(int line);

    /**
     * Returns the code blocks (folding regions) computed by the analyzer.
     * Called on the UI thread. May return an empty list if no blocks.
     */
    List<CodeBlock> computeBlocks();

    /**
     * Returns the bracket match for the bracket at the given offset, or
     * {@code null} if none.
     *
     * @param offset the caret offset
     * @return the matching bracket range, or {@code null}
     */
    BracketMatch computeBracketMatch(int offset);

    /**
     * Releases any resources (threads, parsers). Called on the UI thread.
     */
    void destroy();
}
