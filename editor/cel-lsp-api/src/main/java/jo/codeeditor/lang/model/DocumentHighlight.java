package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.DocumentHighlightProvider;

/**
 * Une plage de surlignage d'occurrence. Retournée par
 * {@link DocumentHighlightProvider}.
 */
public final class DocumentHighlight {

    /** Offset de début (inclus). */
    public final int start;
    /** Offset de fin (exclu). */
    public final int end;
    /** Type : « read », « write », « text ». */
    public final String kind;

    public DocumentHighlight(int start, int end, String kind) {
        this.start = start;
        this.end = end;
        this.kind = kind != null ? kind : "text";
    }
}
