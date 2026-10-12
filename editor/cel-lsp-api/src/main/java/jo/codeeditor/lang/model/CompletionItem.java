package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.CompletionProvider;

/**
 * Un item de complétion. Retourné par {@link CompletionProvider}.
 *
 * <p>{@link #kindCode} transporte la valeur LSP moderne du kind (1-25) :
 * c'est elle qui pilote le badge de type du popup (« K » mot-clé,
 * « C » classe, « I » interface, « M » méthode, « F » champ, « v »
 * variable, « p » package…). 0 = non spécifié — le renderer retombe alors
 * sur la chaîne {@link #kind} historique. {@link #kindTag} raffine les
 * kinds que le protocole LSP ne distingue pas (« annotation »,
 * « package »…), transportés par le champ LSP {@code data}.</p>
 */
public final class CompletionItem {

    /** Le libellé affiché dans le popup (ex. « myMethod »). */
    public final String label;
    /** Le détail affiché à droite (ex. « void — MyClass »). */
    public final String detail;
    /** Le texte à insérer quand l'item est accepté. */
    public final String insertText;
    /** Icône de kind : « k »=mot-clé, « m »=méthode, « f »=champ, « c »=classe, « v »=variable. */
    public final String kind;
    /**
     * La valeur LSP moderne du kind (1=Text … 25=TypeParameter).
     * 0 = non spécifié → badge dérivé de {@link #kind}.
     */
    public final int kindCode;
    /**
     * Raffinement du kind (« annotation », « package », « record »,
     * « parameter ») quand le protocole LSP seul ne distingue pas deux
     * natures. Null = pas de raffinement.
     */
    public final String kindTag;
    /** Priorité de tri (plus bas = plus haut dans la liste). */
    public final int sortPriority;
    /** Indique si l'item est un snippet (contient des tab stops). */
    public final boolean isSnippet;

    public CompletionItem(String label, String detail, String insertText, String kind,
                          int sortPriority, boolean isSnippet) {
        this(label, detail, insertText, kind, 0, null, sortPriority, isSnippet);
    }

    /**
     * Constructeur complet : kind LSP numérique + raffinement (badge de
     * type précis dans le popup).
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

    /** Constructeur de commodité pour un simple mot-clé. */
    public CompletionItem(String label, String insertText) {
        this(label, "", insertText, "k", 100, false);
    }

    /**
     * Action optionnelle post-acceptation (ex. application des
     * {@code additionalTextEdits} LSP — auto-import d'un type non importé).
     * Le pont LSP (cel-lsp) l'attache ; l'éditeur l'exécute sur le thread
     * UI APRÈS avoir inséré le texte principal du candidat. Peut être null.
     */
    public Runnable postApplyAction;
}
