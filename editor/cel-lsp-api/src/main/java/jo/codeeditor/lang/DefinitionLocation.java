package jo.codeeditor.lang;

/**
 * A go-to-definition target. Returned by {@link DefinitionProvider}.
 *
 * @since v2.0.0
 */
public final class DefinitionLocation {

    /** The file path (URI or absolute path). May be the current file. */
    public final String path;
    /** The target offset within the file. */
    public final int offset;
    /** The display name (e.g. "myMethod"). */
    public final String displayName;

    public DefinitionLocation(String path, int offset, String displayName) {
        this.path = path != null ? path : "";
        this.offset = offset;
        this.displayName = displayName != null ? displayName : "";
    }
}
