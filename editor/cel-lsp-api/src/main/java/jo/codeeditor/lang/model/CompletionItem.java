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
    /**
     * Texte de filtrage (champ LSP {@code filterText}) — le libellé
     * contre lequel le préfixe tapé doit être apparié quand il ne
     * correspond pas au {@link #label} (ex. label « toString() »,
     * filterText « tostring »). Null = absent → le filtrage utilise
     * {@link #label}.
     */
    public final String filterText;
    /**
     * Clé de tri secondaire (champ LSP {@code sortText}) — départage
     * les candidats de même priorité de tri (ordre naturel croissant,
     * null en dernier). Null = absent → départage par label.
     */
    public final String sortText;
    /**
     * Plage de remplacement optionnelle en OFFSETS document (champ LSP
     * {@code textEdit}, sémantique <b>remplacement</b> : la plage
     * couvre tout le texte à écraser, y compris ce qui précède le token
     * courant). {@code -1} sur {@link #replaceStart} = plage non
     * spécifiée → l'éditeur remplace le token courant
     * {@code [tokenStart, caret)} ; {@code #replaceEnd} vaut alors
     * aussi {@code -1}.
     *
     * <p>Le pont LSP normalise la plage avant de la poser : bornée au
     * document, ordre start &le; end (une plage inversée d'un serveur
     * bogué est réordonnée), rejetée si elle démarre strictement après
     * le caret. Quand la plage est spécifiée, l'acceptation remplace
     * EXACTEMENT {@code [replaceStart, replaceEnd)} par le texte
     * inséré — jamais le token ET la plage (c'est la double application
     * qui produit « console.console.log »).</p>
     */
    public final int replaceStart;
    /** Fin de la plage de remplacement (exclusive) ; {@code -1} = non spécifiée. */
    public final int replaceEnd;

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
        this(label, detail, insertText, kind, kindCode, kindTag, sortPriority, isSnippet,
                null, null, -1, -1);
    }

    /**
     * Constructeur étendu (L1/D-01) : transport de {@code filterText},
     * {@code sortText} et de la plage de remplacement optionnelle
     * {@code textEdit} (déjà convertie en offsets document par le pont,
     * sémantique remplacement). Les anciens constructeurs délèguent
     * avec {@code filterText}/{@code sortText} absents et plage non
     * spécifiée — rétrocompatible.
     */
    public CompletionItem(String label, String detail, String insertText, String kind,
                          int kindCode, String kindTag,
                          int sortPriority, boolean isSnippet,
                          String filterText, String sortText,
                          int replaceStart, int replaceEnd) {
        this.label = label != null ? label : "";
        this.detail = detail != null ? detail : "";
        // Retombe sur le label NEUTRALISÉ (jamais null) : un item à
        // label null et insertText null produisait historiquement un
        // insertText null — source de NPE à l'acceptation. Durcissement
        // couvert par CompletionItemFieldsTest.
        this.insertText = insertText != null ? insertText : this.label;
        this.kind = kind != null ? kind : "v";
        this.kindCode = kindCode;
        this.kindTag = kindTag;
        this.sortPriority = sortPriority;
        this.isSnippet = isSnippet;
        this.filterText = filterText;
        this.sortText = sortText;
        this.replaceStart = replaceStart;
        this.replaceEnd = replaceEnd;
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
