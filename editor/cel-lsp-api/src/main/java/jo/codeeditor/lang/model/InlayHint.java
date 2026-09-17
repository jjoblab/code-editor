package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.InlayHintProvider;

/**
 * Un inlay hint (texte fantôme inséré à un offset). Retourné par
 * {@link InlayHintProvider}.
 */
public final class InlayHint {

    /** L'offset où le hint est inséré. */
    public final int offset;
    /** Le texte du hint (ex. « : String »). */
    public final String text;
    /** Le type de hint : « type », « parameter », « decorator ». */
    public final String kind;

    public InlayHint(int offset, String text, String kind) {
        this.offset = offset;
        this.text = text != null ? text : "";
        this.kind = kind != null ? kind : "type";
    }
}
