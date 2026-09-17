package jo.codeeditor.lang.model;

import jo.codeeditor.lang.provider.DefinitionProvider;

/**
 * Une cible de navigation (go-to-definition, références, etc.). Retournée
 * notamment par {@link DefinitionProvider}.
 */
public final class DefinitionLocation {

    /**
     * Le chemin du fichier (URI ou chemin absolu). Vide ou identique au
     * fichier courant → navigation dans le même document.
     */
    public final String path;
    /** L'offset cible dans le fichier (indice de caractère, 0-based). */
    public final int offset;
    /** Le libellé d'affichage (ex. « myMethod »). */
    public final String displayName;

    public DefinitionLocation(String path, int offset, String displayName) {
        this.path = path != null ? path : "";
        this.offset = offset;
        this.displayName = displayName != null ? displayName : "";
    }
}
