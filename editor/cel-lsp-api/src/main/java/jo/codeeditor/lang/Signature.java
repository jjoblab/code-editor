package jo.codeeditor.lang;

import java.util.Collections;
import java.util.List;

/**
 * A single function signature in {@link SignatureHelp}.
 *
 * @since v2.0.0
 */
public final class Signature {

    /** The full label (e.g. "myMethod(int x, String y)"). */
    public final String label;
    /** The documentation (Markdown). May be empty. */
    public final String documentation;
    /** The parameters. */
    public final List<Parameter> parameters;
    /** The active parameter index. */
    public final int activeParameter;

    public Signature(String label, String documentation, List<Parameter> parameters, int activeParameter) {
        this.label = label != null ? label : "";
        this.documentation = documentation != null ? documentation : "";
        this.parameters = parameters != null ? Collections.unmodifiableList(parameters) : Collections.emptyList();
        this.activeParameter = activeParameter;
    }
}
