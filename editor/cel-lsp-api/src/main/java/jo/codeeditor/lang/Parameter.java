package jo.codeeditor.lang;

/**
 * A single parameter in a {@link Signature}.
 *
 * @since v2.0.0
 */
public final class Parameter {

    /** The parameter label (e.g. "int x"). */
    public final String label;
    /** The parameter documentation. May be empty. */
    public final String documentation;

    public Parameter(String label, String documentation) {
        this.label = label != null ? label : "";
        this.documentation = documentation != null ? documentation : "";
    }
}
