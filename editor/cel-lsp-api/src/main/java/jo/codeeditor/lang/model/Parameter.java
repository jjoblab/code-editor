package jo.codeeditor.lang.model;

/**
 * Un paramètre d'une {@link Signature}.
 */
public final class Parameter {

    /** Le libellé du paramètre (ex. « int x »). */
    public final String label;
    /** La documentation du paramètre. Peut être vide. */
    public final String documentation;

    public Parameter(String label, String documentation) {
        this.label = label != null ? label : "";
        this.documentation = documentation != null ? documentation : "";
    }
}
