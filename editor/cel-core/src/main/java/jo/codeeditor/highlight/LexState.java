package jo.codeeditor.highlight;

/**
 * Constantes d'état du lexer pour l'analyse inter-lignes.
 */
public final class LexState {
    /** Code normal. */
    public static final int NORMAL = 0;
    /** Dans un commentaire de bloc (\/\* ... \*\/). */
    public static final int BLOCK_COMMENT = 1;
    /** Dans une chaîne XML/HTML s'étendant sur plusieurs lignes. */
    public static final int XML_STRING = 2;
    /** Dans une chaîne brute Kotlin (triple guillemet). */
    public static final int KT_RAW_STRING = 3;
    /** Dans une balise XML (entre {@code <} et {@code >}), afin que les
     *  balises multi-lignes (un attribut par ligne) bénéficient d'une
     *  coloration correcte des attributs. */
    public static final int XML_TAG = 4;
    // ── Ajouts du parser maison (langages supplémentaires) ─────────────
    /** Dans un commentaire de bloc CSS ({@code /* ... *\/}). */
    public static final int CSS_COMMENT = 5;
    /** Dans une chaîne CSS s'étendant sur plusieurs lignes (rare mais possible). */
    public static final int CSS_STRING = 6;
    /** Dans un littéral Shell entre apostrophes ('...' sur plusieurs lignes). */
    public static final int SHELL_SINGLE_QUOTE = 12;

    private LexState() {}
}
