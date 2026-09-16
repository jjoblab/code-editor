package jo.codeeditor.lang;

/**
 * A document symbol. Returned by {@link SymbolProvider}.
 *
 * @since v2.0.0
 */
public final class Symbol {

    /** The symbol name (e.g. "myMethod"). */
    public final String name;
    /** The symbol offset. */
    public final int offset;
    /** The symbol kind: "class", "method", "field", "interface", "enum". */
    public final String kind;
    /** The declaring container (e.g. "MyClass"). May be empty. */
    public final String container;

    public Symbol(String name, int offset, String kind, String container) {
        this.name = name != null ? name : "";
        this.offset = offset;
        this.kind = kind != null ? kind : "field";
        this.container = container != null ? container : "";
    }
}
