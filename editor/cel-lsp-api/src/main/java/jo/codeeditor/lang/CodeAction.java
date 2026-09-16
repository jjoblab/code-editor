package jo.codeeditor.lang;

/**
 * A code action (quick-fix, refactor). Returned by {@link CodeActionsProvider}.
 *
 * <p>The {@code apply} runnable is called on the UI thread when the user
 * accepts the action. It should apply the fix to the document.
 *
 * @since v2.0.0
 */
public final class CodeAction {

    /** The action title (e.g. "Add missing import"). */
    public final String title;
    /** The action kind: "quickfix", "refactor", "source". */
    public final String kind;
    /** Whether this is the preferred action (shown first). */
    public final boolean isPreferred;
    /** The runnable that applies the action. Called on the UI thread. */
    public final Runnable apply;

    public CodeAction(String title, String kind, boolean isPreferred, Runnable apply) {
        this.title = title != null ? title : "";
        this.kind = kind != null ? kind : "quickfix";
        this.isPreferred = isPreferred;
        this.apply = apply;
    }

    /** Convenience constructor for a non-preferred quickfix. */
    public CodeAction(String title, Runnable apply) {
        this(title, "quickfix", false, apply);
    }
}
