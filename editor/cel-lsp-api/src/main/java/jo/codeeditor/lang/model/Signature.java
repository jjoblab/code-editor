package jo.codeeditor.lang.model;

import java.util.Collections;
import java.util.List;

/**
 * Une signature de fonction dans {@link SignatureHelp}.
 */
public final class Signature {

    /** Le libellé complet (ex. « myMethod(int x, String y) »). */
    public final String label;
    /** La documentation (Markdown). Peut être vide. */
    public final String documentation;
    /** Les paramètres. */
    public final List<Parameter> parameters;
    /** L'index du paramètre actif. */
    public final int activeParameter;

    public Signature(String label, String documentation, List<Parameter> parameters, int activeParameter) {
        this.label = label != null ? label : "";
        this.documentation = documentation != null ? documentation : "";
        this.parameters = parameters != null ? Collections.unmodifiableList(parameters) : Collections.emptyList();
        this.activeParameter = activeParameter;
    }
}
