package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.SymbolProvider;

/**
 * Un symbole de document. Retourné par {@link SymbolProvider}.
 */
public final class Symbol {

    /** Le nom du symbole (ex. « myMethod »). */
    public final String name;
    /** L'offset du symbole. */
    public final int offset;
    /** Le type de symbole : « class », « method », « field », « interface », « enum ». */
    public final String kind;
    /** Le conteneur déclarant (ex. « MyClass »). Peut être vide. */
    public final String container;

    public Symbol(String name, int offset, String kind, String container) {
        this.name = name != null ? name : "";
        this.offset = offset;
        this.kind = kind != null ? kind : "field";
        this.container = container != null ? container : "";
    }
}
