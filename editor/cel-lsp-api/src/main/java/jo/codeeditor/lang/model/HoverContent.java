package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.HoverProvider;

/**
 * Contenu de hover/quick-doc retourné par {@link HoverProvider}.
 *
 * <p>Le champ {@code markdown} contient du Markdown brut (Javadoc/KDoc
 * rendue). L'éditeur le met en forme hors du thread UI et affiche le
 * résultat dans un popup.</p>
 */
public final class HoverContent {

    /** La signature du symbole (ex. « public void myMethod(int x) »). */
    public final String signature;
    /** Le conteneur déclarant (ex. « MyClass »). Peut être vide. */
    public final String container;
    /** La documentation Markdown brute. Peut être vide. */
    public final String markdown;

    public HoverContent(String signature, String container, String markdown) {
        this.signature = signature != null ? signature : "";
        this.container = container != null ? container : "";
        this.markdown = markdown != null ? markdown : "";
    }

    /** Retourne vrai si ce contenu est vide (ni signature ni markdown). */
    public boolean isEmpty() {
        return signature.isEmpty() && markdown.isEmpty();
    }
}
