package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.InlayHintProvider;

/**
 * Un inlay hint (texte fantôme inséré à un offset). Retourné par
 * {@link InlayHintProvider}.
 *
 * <p>I2 (lot 2, J3) : le champ {@code tooltip} additionnel (null par
 * défaut — zéro rupture) porte soit le tooltip servi directement par le
 * serveur, soit celui résolu paresseusement par
 * {@link InlayHintProvider#resolveHint} au premier appui long.</p>
 */
public final class InlayHint {

    /** L'offset où le hint est inséré. */
    public final int offset;
    /** Le texte du hint (ex. « : String »). */
    public final String text;
    /** Le type de hint : « type », « parameter », « decorator ». */
    public final String kind;
    /** Le tooltip du hint (null si le serveur n'en fournit pas). */
    public final String tooltip;

    public InlayHint(int offset, String text, String kind) {
        this(offset, text, kind, null);
    }

    public InlayHint(int offset, String text, String kind, String tooltip) {
        this.offset = offset;
        this.text = text != null ? text : "";
        this.kind = kind != null ? kind : "type";
        this.tooltip = tooltip;
    }
}
