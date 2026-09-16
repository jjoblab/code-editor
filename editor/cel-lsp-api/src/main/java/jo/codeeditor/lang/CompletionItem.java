package jo.codeeditor.lang;

/**
 * A single completion item. Returned by {@link CompletionProvider}.
 *
 * <p>★ v2.30 : {@link #kindCode} transporte la valeur LSP moderne
 * (1-25) du kind — c'est elle qui pilote le BADGE DE TYPE du popup
 * (portage du KindBadge de CodeAssist : « K » mot-clé, « C » classe,
 * « I » interface, « M » méthode, « F » champ, « v » variable,
 * « p » package…). 0 = non spécifié — le renderer retombe alors sur
 * la string {@link #kind} historique. {@link #kindTag} raffine les
 * kinds que le protocole LSP ne distingue pas (« annotation »,
 * « package »…) transportés par le champ LSP {@code data}.</p>
 *
 * @since v2.0.0
 */
public final class CompletionItem {

    /** The label shown in the popup (e.g. "myMethod"). */
    public final String label;
    /** The detail shown on the right (e.g. "void — MyClass"). */
    public final String detail;
    /** The text to insert when the item is accepted. */
    public final String insertText;
    /** Kind icon: "k"=keyword, "m"=method, "f"=field, "c"=class, "v"=variable. */
    public final String kind;
    /**
     * ★ v2.30 : la valeur LSP moderne du kind (1=Text … 25=TypeParameter).
     * 0 = non spécifié (provider legacy) → badge dérivé de {@link #kind}.
     */
    public final int kindCode;
    /**
     * ★ v2.30 : raffinement du kind (« annotation », « package »,
     * « record », « parameter ») quand le protocole LSP seul ne
     * distingue pas deux natures. Null = pas de raffinement.
     */
    public final String kindTag;
    /** Sort priority (lower = higher in the list). */
    public final int sortPriority;
    /** Whether the item is a snippet (contains tab stops). */
    public final boolean isSnippet;

    public CompletionItem(String label, String detail, String insertText, String kind,
                          int sortPriority, boolean isSnippet) {
        this(label, detail, insertText, kind, 0, null, sortPriority, isSnippet);
    }

    /**
     * ★ v2.30 : constructeur complet avec kind LSP numérique + raffinement
     * (badge de type précis dans le popup, pattern KindBadge de CodeAssist).
     */
    public CompletionItem(String label, String detail, String insertText, String kind,
                          int kindCode, String kindTag,
                          int sortPriority, boolean isSnippet) {
        this.label = label != null ? label : "";
        this.detail = detail != null ? detail : "";
        this.insertText = insertText != null ? insertText : label;
        this.kind = kind != null ? kind : "v";
        this.kindCode = kindCode;
        this.kindTag = kindTag;
        this.sortPriority = sortPriority;
        this.isSnippet = isSnippet;
    }

    /** Convenience constructor for a simple keyword. */
    public CompletionItem(String label, String insertText) {
        this(label, "", insertText, "k", 100, false);
    }

    /**
     * v0.1.0.50: Optional post-accept action (ex: application des
     * {@code additionalTextEdits} LSP — auto-import d'un type non importé).
     * Le pont (cel-lsp) l'attache ; l'éditeur l'exécute APRÈS avoir inséré
     * le texte principal du candidat. Peut être null.
     */
    public Runnable postApplyAction;
}
