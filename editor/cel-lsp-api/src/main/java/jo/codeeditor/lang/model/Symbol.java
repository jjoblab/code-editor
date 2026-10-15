package jo.codeeditor.lang.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import jo.codeeditor.lang.provider.SymbolProvider;

/**
 * Un symbole de document. Retourné par {@link SymbolProvider}.
 */
public final class Symbol {

    /** Le nom du symbole (ex. « myMethod »). */
    @NonNull public final String name;
    /** L'offset du symbole. */
    public final int offset;
    /** Le type de symbole : « class », « method », « field », « interface », « enum ». */
    @NonNull public final String kind;
    /** Le conteneur déclarant (ex. « MyClass »). Peut être vide. */
    @Nullable public final String container;

    public Symbol(@NonNull String name, int offset, @NonNull String kind, @Nullable String container) {
        this.name = name != null ? name : "";
        this.offset = offset;
        this.kind = kind != null ? kind : "field";
        this.container = container != null ? container : "";
    }
}
