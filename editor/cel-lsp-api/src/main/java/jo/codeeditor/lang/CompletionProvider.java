package jo.codeeditor.lang;


import java.util.List;

/**
 * Provides auto-completion items. The editor calls {@link #complete} on a
 * worker thread when the user types a trigger character or presses the
 * completion shortcut (Ctrl+Space). The provider publishes items to the
 * {@link CompletionPublisher}, which is thread-safe.
 *
 * @since v2.0.0
 */
public interface CompletionProvider {

    /**
     * Requests completion items for the given caret position.
     *
     * @param text      the full document text
     * @param caret     the caret offset
     * @param publisher the thread-safe publisher — call {@code publisher.addItem(...)}
     *                  for each item, then {@code publisher.flush()} when done
     */
    void complete(CharSequence text, int caret, CompletionPublisher publisher);

    /**
     * Returns the characters that trigger auto-completion (e.g. {@code "."},
     * {@code "@"}, {@code "#"}). The editor auto-triggers completion when
     * the user types one of these. Returns an empty list if auto-trigger
     * is disabled.
     */
    List<String> getTriggerCharacters();
}
