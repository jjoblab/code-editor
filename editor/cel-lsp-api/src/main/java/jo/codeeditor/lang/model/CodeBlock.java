package jo.codeeditor.lang.model;

import jo.codeeditor.lang.Analyzer;

/**
 * Une région de folding (bloc de code). Retournée par
 * {@link Analyzer#computeBlocks()}.
 */
public final class CodeBlock {

    /** Offset de début du bloc (inclus). */
    public final int start;
    /** Offset de fin du bloc (exclu). */
    public final int end;
    /** Texte de substitution affiché quand le bloc est replié (ex. « {…} »). */
    public final String placeholder;
    /** Type de bloc : "block", "comment", "imports", "region", etc. */
    public final String kind;
    /** Indique si le bloc est actuellement replié. */
    public boolean collapsed;

    public CodeBlock(int start, int end, String placeholder, String kind, boolean collapsed) {
        this.start = start;
        this.end = end;
        this.placeholder = placeholder != null ? placeholder : "…";
        this.kind = kind != null ? kind : "block";
        this.collapsed = collapsed;
    }

    public CodeBlock(int start, int end) {
        this(start, end, "…", "block", false);
    }
}
