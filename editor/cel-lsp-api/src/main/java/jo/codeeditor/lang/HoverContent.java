package jo.codeeditor.lang;

/**
 * Hover/quick-doc content returned by {@link HoverProvider}.
 *
 * <p>The {@code markdown} field contains raw Markdown text (Javadoc/KDoc
 * rendered). The editor renders it off the UI thread and displays the
 * result in a popup.
 *
 * @since v2.0.0
 */
public final class HoverContent {

    /** The symbol's signature (e.g. "public void myMethod(int x)"). */
    public final String signature;
    /** The declaring container (e.g. "MyClass"). May be empty. */
    public final String container;
    /** The raw Markdown documentation text. May be empty. */
    public final String markdown;

    public HoverContent(String signature, String container, String markdown) {
        this.signature = signature != null ? signature : "";
        this.container = container != null ? container : "";
        this.markdown = markdown != null ? markdown : "";
    }

    /** Returns true if this content is empty (no signature and no markdown). */
    public boolean isEmpty() {
        return signature.isEmpty() && markdown.isEmpty();
    }
}
