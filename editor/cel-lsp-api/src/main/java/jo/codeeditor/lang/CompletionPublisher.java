package jo.codeeditor.lang;


import java.util.List;

/**
 * Thread-safe completion publisher. The {@link CompletionProvider} calls
 * {@link #addItem} from a worker thread; the editor's implementation
 * batches the items and flushes them to the UI thread.
 *
 * <p>Modeled after Sora Editor's {@code CompletionPublisher} — well-tuned
 * for thousands of items with threshold flushing + binary-search insertion.
 *
 * @since v2.0.0
 */
public interface CompletionPublisher {

    /**
     * Adds a completion item. Thread-safe.
     */
    void addItem(CompletionItem item);

    /**
     * Adds multiple items at once. Thread-safe.
     */
    void addItems(List<CompletionItem> items);

    /**
     * Flushes pending items to the UI. Called by the provider when it's
     * done adding items. Thread-safe.
     */
    void flush();

    /**
     * Cancels the current completion request. Thread-safe.
     */
    void cancel();

    /**
     * Returns true if the completion was cancelled.
     */
    boolean isCancelled();
}
